package flash.pipeline.intelligence;

import flash.pipeline.bin.BinConfig;
import flash.pipeline.bin.BinConfigIO;
import flash.pipeline.io.CsvTableIO;
import flash.pipeline.io.FlashProjectLayout;
import flash.pipeline.naming.ImageNameParser;
import flash.pipeline.naming.NameParts;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class SummaryHistoryStore {

    static final String FILE_NAME = "summary_history.json";
    static final String LEGACY_FILE_NAME = ".ihf-summary.json";
    private static final String[][] SUMMARY_TABLES = new String[][] {
            { FlashProjectLayout.MASTER_OBJECTS_FILENAME,
                    FlashProjectLayout.LEGACY_MASTER_OBJECTS_FILENAME },
            { FlashProjectLayout.MASTER_INTENSITIES_FILENAME,
                    FlashProjectLayout.LEGACY_MASTER_INTENSITIES_FILENAME }
    };
    private static final String IMAGE_METRICS_KEY = "metrics";

    private SummaryHistoryStore() {}

    static Snapshot capture(String directory) {
        Snapshot snapshot = new Snapshot();
        snapshot.generatedAt = Instant.now().toString();
        snapshot.settings.putAll(captureSettings(directory));
        snapshot.imageMetadata.putAll(captureImageMetadata(directory));
        snapshot.tables.putAll(captureTables(directory));
        return snapshot;
    }

    static Snapshot load(String directory) throws IOException {
        File file = firstExistingHistoryFile(directory);
        if (!file.isFile()) return null;
        String json = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        Object parsed = MiniJson.parse(json);
        if (!(parsed instanceof Map)) {
            throw new IOException("Summary history file does not contain a JSON object.");
        }
        return Snapshot.fromJson(asMap(parsed));
    }

    static void save(String directory, Snapshot snapshot) throws IOException {
        if (directory == null || directory.trim().isEmpty() || snapshot == null) return;
        File file = historyWriteFile(directory);
        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory()) {
            throw new IOException("Could not create summary history folder: " + parent.getAbsolutePath());
        }
        Files.write(file.toPath(),
                MiniJson.write(snapshot.toJson()).getBytes(StandardCharsets.UTF_8));
    }

    static File historyWriteFile(String directory) {
        return FlashProjectLayout.forDirectory(directory).statusWriteFile(FILE_NAME);
    }

    private static File firstExistingHistoryFile(String directory) {
        FlashProjectLayout layout = FlashProjectLayout.forDirectory(directory);
        File newFile = layout.statusWriteFile(FILE_NAME);
        if (newFile.isFile()) return newFile;
        File legacy = new File(directory, LEGACY_FILE_NAME);
        return legacy.isFile() ? legacy : newFile;
    }

    static List<String> diffLines(Snapshot previous, Snapshot current) {
        List<String> lines = new ArrayList<String>();
        lines.add("—— R-07 Re-run diff ——");
        if (previous == null) {
            lines.add("  INFO  no previous run snapshot found in this folder.");
            lines.add("");
            return lines;
        }

        List<String> changedSettings = changedSettings(previous.settings, current.settings);
        if (changedSettings.isEmpty()) {
            lines.add("  OK    no saved setting changes detected.");
        } else {
            lines.add("  WARN  settings changed since the previous saved run:");
            int limit = Math.min(6, changedSettings.size());
            for (int i = 0; i < limit; i++) {
                lines.add("        " + changedSettings.get(i));
            }
            if (changedSettings.size() > limit) {
                lines.add("        ... and " + (changedSettings.size() - limit) + " more");
            }
        }

        List<String> tableLines = tableDiffLines(previous.tables, current.tables);
        if (tableLines.isEmpty()) {
            lines.add("  OK    numeric summaries did not move.");
        } else {
            lines.addAll(tableLines);
        }
        lines.add("");
        return lines;
    }

    private static Map<String, String> captureSettings(String directory) {
        LinkedHashMap<String, String> settings = new LinkedHashMap<String, String>();
        if (directory == null || directory.trim().isEmpty()) return settings;

        try {
            BinConfig cfg = BinConfigIO.readFromDirectory(directory);
            settings.put("Channel names", joinList(cfg.channelNames));
            settings.put("Channel colours", joinList(cfg.channelColors));
            settings.put("Object thresholds", joinList(cfg.channelThresholds));
            settings.put("Particle sizes", joinList(cfg.channelSizes));
            settings.put("Display ranges", joinList(cfg.channelMinMax));
            settings.put("Intensity thresholds", joinList(cfg.channelIntensityThresholds));
            settings.put("Segmentation methods", joinList(cfg.segmentationMethods));
            settings.put("Filter presets", joinList(cfg.channelFilterPresets));
            settings.put("Z-slice mode", cfg.zSliceMode == null ? "" : cfg.zSliceMode.name());
            settings.put("Z-slice summary", cfg.getZSliceConfig().summary());
        } catch (IOException e) {
            settings.put("Bin status", "Unavailable: " + e.getMessage());
        }

        return settings;
    }

    private static Map<String, Map<String, Object>> captureImageMetadata(String directory) {
        LinkedHashMap<String, Map<String, Object>> out = new LinkedHashMap<String, Map<String, Object>>();
        LinkedHashMap<String, Integer> duplicates = new LinkedHashMap<String, Integer>();
        LinkedHashMap<String, String> identityToSnapshotKey = new LinkedHashMap<String, String>();

        List<MetadataDiagnostics.SeriesInfo> series = MetadataDiagnostics.scanDirectory(directory);
        for (MetadataDiagnostics.SeriesInfo info : series) {
            if (info == null) continue;
            String baseKey = imageMetadataKey(info);
            Integer seen = duplicates.get(baseKey);
            int suffix = seen == null ? 1 : seen + 1;
            duplicates.put(baseKey, suffix);
            String key = suffix == 1 ? baseKey : baseKey + " #" + suffix;

            LinkedHashMap<String, Object> meta = new LinkedHashMap<String, Object>();
            meta.put("file", info.file);
            meta.put("seriesIndex", info.seriesIndex);
            meta.put("imageName", info.imageName);
            meta.put("displayName", MetadataDiagnostics.displaySeriesLabel(info));

            NameParts parts = parseImageName(info.imageName, info.file);
            meta.put("animalId", parts.animal);
            meta.put("hemisphere", trimToEmpty(parts.hemisphere));
            meta.put("region", trimToEmpty(parts.csvRegion()));
            meta.put("sectionIndex", info.seriesIndex + 1);

            meta.put("channelCount", info.sizeC);
            meta.put("bitDepth", bitDepthFromPixelType(info.pixelType));
            meta.put("pixelSizeX", info.pixelSizeXUm);
            meta.put("acquisitionDate", info.acquisitionDate);
            meta.put("mean", null);
            meta.put("median", null);
            meta.put("p95", null);
            meta.put("saturationPct", null);
            meta.put("focusScore", null);
            meta.put("snrDb", null);
            meta.put(IMAGE_METRICS_KEY, new LinkedHashMap<String, Object>());
            out.put(key, meta);

            String identity = buildImageIdentity(parts.animal, parts.hemisphere,
                    parts.csvRegion(), info.seriesIndex + 1);
            if (!identity.isEmpty() && !identityToSnapshotKey.containsKey(identity)) {
                identityToSnapshotKey.put(identity, key);
            }
        }

        mergeObjectMetrics(directory, out, duplicates, identityToSnapshotKey);
        mergeIntensityMetrics(directory, out, duplicates, identityToSnapshotKey);
        return out;
    }

    private static void mergeObjectMetrics(String directory,
                                           Map<String, Map<String, Object>> imageMetadata,
                                           Map<String, Integer> duplicateKeys,
                                           Map<String, String> identityToSnapshotKey) {
        for (File csvFile : firstNamedCsvFiles(
                FlashProjectLayout.forDirectory(directory).objectDataReadDirs())) {
            String lower = csvFile.getName().toLowerCase(Locale.ROOT);
            if (!lower.endsWith(".csv")) continue;
            if (lower.contains("temp_")) continue;

            List<String[]> rows;
            try {
                rows = readCsvRows(csvFile);
            } catch (IOException ignored) {
                continue;
            }
            if (rows.size() < 2) continue;

            String[] header = rows.get(0);
            int animalCol = findColumn(header, "Animal Name", "Animal", "AnimalName");
            if (animalCol < 0) continue;

            int hemiCol = findColumn(header, "Hemisphere");
            int regionCol = findColumn(header, "Region");
            int scnCol = findColumn(header, "SCN");
            int roiCol = findColumn(header, "ROI");
            int labelCol = findColumn(header, "Label");
            int volumeCol = findColumn(header, "Volume (micron^3)");
            int meanCol = findColumn(header, "Mean");
            int intDenCol = findColumn(header, "IntDen");

            String metricPrefix = stripExtension(csvFile.getName());
            LinkedHashMap<String, ObjectMetricAccumulator> byImage =
                    new LinkedHashMap<String, ObjectMetricAccumulator>();

            for (int rowIndex = 1; rowIndex < rows.size(); rowIndex++) {
                String[] row = rows.get(rowIndex);
                String animalId = valueAt(row, animalCol);
                if (animalId.isEmpty()) continue;

                String hemisphere = valueAt(row, hemiCol);
                String region = valueAt(row, regionCol);
                String roi = valueAt(row, roiCol);
                int sectionIndex = parseSectionIndex(valueAt(row, scnCol), roi);
                String identity = buildImageIdentity(animalId, hemisphere, region, sectionIndex);
                ObjectMetricAccumulator acc = byImage.get(identity);
                if (acc == null) {
                    acc = new ObjectMetricAccumulator(animalId, hemisphere, region, sectionIndex, roi);
                    byImage.put(identity, acc);
                }

                double label = numberAt(row, labelCol);
                double volume = numberAt(row, volumeCol);
                double mean = numberAt(row, meanCol);
                double intDen = numberAt(row, intDenCol);
                boolean realObject = label > 0.0 || volume > 0.0 || mean > 0.0 || intDen > 0.0;
                if (!realObject) continue;

                acc.objectCount++;
                if (!Double.isNaN(mean)) {
                    acc.meanSum += mean;
                    acc.meanCount++;
                }
                if (!Double.isNaN(intDen)) {
                    acc.intDenSum += intDen;
                    acc.intDenCount++;
                }
            }

            for (ObjectMetricAccumulator acc : byImage.values()) {
                acc.metrics.put(metricPrefix + " object count", (double) acc.objectCount);
                if (acc.meanCount > 0) {
                    acc.metrics.put(metricPrefix + " object mean intensity", acc.meanSum / acc.meanCount);
                }
                if (acc.intDenCount > 0) {
                    acc.metrics.put(metricPrefix + " object integrated density", acc.intDenSum / acc.intDenCount);
                }
                mergeImageMetrics(imageMetadata, duplicateKeys, identityToSnapshotKey, acc);
            }
        }
    }

    private static void mergeIntensityMetrics(String directory,
                                              Map<String, Map<String, Object>> imageMetadata,
                                              Map<String, Integer> duplicateKeys,
                                              Map<String, String> identityToSnapshotKey) {
        for (File csvFile : firstNamedCsvFiles(
                FlashProjectLayout.forDirectory(directory).intensityDataReadDirs())) {
            String lower = csvFile.getName().toLowerCase(Locale.ROOT);
            if (!lower.endsWith(".csv")) continue;

            List<String[]> rows;
            try {
                rows = readCsvRows(csvFile);
            } catch (IOException ignored) {
                continue;
            }
            if (rows.size() < 2) continue;

            String[] header = rows.get(0);
            int animalCol = findColumn(header, "Animal Name", "Animal", "AnimalName");
            if (animalCol < 0) continue;

            int hemiCol = findColumn(header, "Hemisphere");
            int regionCol = findColumn(header, "Region");
            int roiCol = findColumn(header, "ROI");
            int intDenCol = findColumn(header, "IntDen");
            int areaCol = findColumn(header, "%Area");
            int intDenUnfilteredCol = findColumn(header, "IntDen_Unfiltered", "RawIntDen");
            String metricPrefix = stripExtension(csvFile.getName());

            LinkedHashMap<String, IntensityMetricAccumulator> byImage =
                    new LinkedHashMap<String, IntensityMetricAccumulator>();

            for (int rowIndex = 1; rowIndex < rows.size(); rowIndex++) {
                String[] row = rows.get(rowIndex);
                String animalId = valueAt(row, animalCol);
                if (animalId.isEmpty()) continue;

                String hemisphere = valueAt(row, hemiCol);
                String region = valueAt(row, regionCol);
                String roi = valueAt(row, roiCol);
                int sectionIndex = parseSectionIndex("", roi);
                String identity = buildImageIdentity(animalId, hemisphere, region, sectionIndex);
                IntensityMetricAccumulator acc = byImage.get(identity);
                if (acc == null) {
                    acc = new IntensityMetricAccumulator(animalId, hemisphere, region, sectionIndex, roi);
                    byImage.put(identity, acc);
                }

                acc.addIntDen(numberAt(row, intDenCol));
                acc.addArea(numberAt(row, areaCol));
                acc.addIntDenUnfiltered(numberAt(row, intDenUnfilteredCol));
            }

            for (IntensityMetricAccumulator acc : byImage.values()) {
                if (acc.intDenCount > 0) {
                    acc.metrics.put(metricPrefix + " mean intensity", acc.intDenSum / acc.intDenCount);
                }
                if (acc.areaCount > 0) {
                    acc.metrics.put(metricPrefix + " mean area %", acc.areaSum / acc.areaCount);
                }
                if (acc.intDenUnfilteredCount > 0) {
                    acc.metrics.put(metricPrefix + " mean unfiltered intensity",
                            acc.intDenUnfilteredSum / acc.intDenUnfilteredCount);
                }
                mergeImageMetrics(imageMetadata, duplicateKeys, identityToSnapshotKey, acc);
            }
        }
    }

    private static void mergeImageMetrics(Map<String, Map<String, Object>> imageMetadata,
                                          Map<String, Integer> duplicateKeys,
                                          Map<String, String> identityToSnapshotKey,
                                          ImageMetricAccumulator acc) {
        if (acc.metrics.isEmpty()) return;

        String identity = buildImageIdentity(acc.animalId, acc.hemisphere, acc.region, acc.sectionIndex);
        String snapshotKey = identityToSnapshotKey.get(identity);
        Map<String, Object> meta = snapshotKey == null ? null : imageMetadata.get(snapshotKey);
        if (meta == null) {
            String baseKey = acc.displayName();
            Integer seen = duplicateKeys.get(baseKey);
            int suffix = seen == null ? 1 : seen + 1;
            duplicateKeys.put(baseKey, suffix);
            snapshotKey = suffix == 1 ? baseKey : baseKey + " #" + suffix;
            meta = createSyntheticImageMetadata(acc);
            imageMetadata.put(snapshotKey, meta);
            if (!identity.isEmpty() && !identityToSnapshotKey.containsKey(identity)) {
                identityToSnapshotKey.put(identity, snapshotKey);
            }
        }

        meta.put("animalId", acc.animalId);
        meta.put("hemisphere", acc.hemisphere);
        meta.put("region", acc.region);
        meta.put("sectionIndex", acc.sectionIndex);
        if (trimToEmpty(stringValue(meta.get("displayName"))).isEmpty()) {
            meta.put("displayName", acc.displayName());
        }

        Map<String, Object> metricStore = metricStore(meta);
        for (Map.Entry<String, Double> entry : acc.metrics.entrySet()) {
            metricStore.put(entry.getKey(), entry.getValue());
        }
    }

    private static Map<String, Object> createSyntheticImageMetadata(ImageMetricAccumulator acc) {
        LinkedHashMap<String, Object> meta = new LinkedHashMap<String, Object>();
        meta.put("file", "");
        meta.put("seriesIndex", Math.max(0, acc.sectionIndex - 1));
        meta.put("imageName", "");
        meta.put("displayName", acc.displayName());
        meta.put("animalId", acc.animalId);
        meta.put("hemisphere", acc.hemisphere);
        meta.put("region", acc.region);
        meta.put("sectionIndex", acc.sectionIndex);
        meta.put("channelCount", null);
        meta.put("bitDepth", null);
        meta.put("pixelSizeX", null);
        meta.put("acquisitionDate", null);
        meta.put("mean", null);
        meta.put("median", null);
        meta.put("p95", null);
        meta.put("saturationPct", null);
        meta.put("focusScore", null);
        meta.put("snrDb", null);
        meta.put(IMAGE_METRICS_KEY, new LinkedHashMap<String, Object>());
        return meta;
    }

    private static Map<String, Object> metricStore(Map<String, Object> meta) {
        Object existing = meta.get(IMAGE_METRICS_KEY);
        if (existing instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> store = (Map<String, Object>) existing;
            return store;
        }
        LinkedHashMap<String, Object> store = new LinkedHashMap<String, Object>();
        meta.put(IMAGE_METRICS_KEY, store);
        return store;
    }

    private static List<String[]> readCsvRows(File csvFile) throws IOException {
        List<String> lines = Files.readAllLines(csvFile.toPath(), StandardCharsets.UTF_8);
        List<String[]> rows = new ArrayList<String[]>(lines.size());
        for (String line : lines) {
            rows.add(CsvTableIO.parseCsvLine(line));
        }
        return rows;
    }

    private static NameParts parseImageName(String imageName, String fileName) {
        String seed = trimToEmpty(imageName);
        if (seed.isEmpty()) seed = stripKnownImageExtension(fileName);
        return ImageNameParser.parse(seed);
    }

    private static String buildImageIdentity(String animalId, String hemisphere,
                                             String region, int sectionIndex) {
        String animal = trimToEmpty(animalId);
        if (animal.isEmpty()) return "";
        return animal.toLowerCase(Locale.ROOT) + "|"
                + trimToEmpty(hemisphere).toLowerCase(Locale.ROOT) + "|"
                + trimToEmpty(region).toLowerCase(Locale.ROOT) + "|"
                + Math.max(0, sectionIndex);
    }

    private static int findColumn(String[] header, String... names) {
        for (String name : names) {
            int idx = indexOf(header, name);
            if (idx >= 0) return idx;
        }
        return -1;
    }

    private static String valueAt(String[] row, int index) {
        if (row == null || index < 0 || index >= row.length) return "";
        return trimToEmpty(row[index]);
    }

    private static double numberAt(String[] row, int index) {
        Double value = parseDouble(valueAt(row, index));
        return value == null ? Double.NaN : value.doubleValue();
    }

    private static int parseSectionIndex(String scnValue, String roiValue) {
        Double numericScn = parseDouble(trimToEmpty(scnValue));
        if (numericScn != null && numericScn.doubleValue() > 0.0) {
            return (int) Math.round(numericScn.doubleValue());
        }

        String roi = trimToEmpty(roiValue);
        int end = roi.length() - 1;
        while (end >= 0 && Character.isDigit(roi.charAt(end))) end--;
        if (end < roi.length() - 1) {
            try {
                return Integer.parseInt(roi.substring(end + 1));
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        return 0;
    }

    private static String stripKnownImageExtension(String name) {
        String trimmed = trimToEmpty(name);
        if (trimmed.toLowerCase(Locale.ROOT).endsWith(".ome.tiff")) {
            return trimmed.substring(0, trimmed.length() - 9);
        }
        if (trimmed.toLowerCase(Locale.ROOT).endsWith(".ome.tif")) {
            return trimmed.substring(0, trimmed.length() - 8);
        }
        int dot = trimmed.lastIndexOf('.');
        return dot > 0 ? trimmed.substring(0, dot) : trimmed;
    }

    private static String stripExtension(String name) {
        return stripKnownImageExtension(name);
    }

    private static Map<String, TableSnapshot> captureTables(String directory) {
        LinkedHashMap<String, TableSnapshot> tables = new LinkedHashMap<String, TableSnapshot>();
        FlashProjectLayout layout = FlashProjectLayout.forDirectory(directory);
        for (String[] fileNames : SUMMARY_TABLES) {
            File csv = firstExistingFile(layout.aggregationReadFiles(fileNames));
            if (!csv.isFile()) continue;
            try {
                TableSnapshot table = captureTable(csv);
                if (table != null && !table.rows.isEmpty()) {
                    tables.put(fileNames[0], table);
                }
            } catch (IOException ignored) {
            }
        }
        return tables;
    }

    private static List<File> firstNamedCsvFiles(List<File> dirs) {
        List<File> out = new ArrayList<File>();
        LinkedHashSet<String> seenNames = new LinkedHashSet<String>();
        for (File dir : dirs) {
            if (dir == null || !dir.isDirectory()) continue;
            File[] files = JunkFileFilter.listCleanFiles(dir);
            Arrays.sort(files, new Comparator<File>() {
                @Override
                public int compare(File a, File b) {
                    return a.getName().compareToIgnoreCase(b.getName());
                }
            });
            for (File file : files) {
                String lower = file.getName().toLowerCase(Locale.ROOT);
                if (!lower.endsWith(".csv")) continue;
                if (seenNames.contains(lower)) continue;
                seenNames.add(lower);
                out.add(file);
            }
        }
        return out;
    }

    private static File firstExistingFile(List<File> dirs, String fileName) {
        for (File dir : dirs) {
            File file = new File(dir, fileName);
            if (file.isFile()) return file;
        }
        return new File(dirs.get(0), fileName);
    }

    private static File firstExistingFile(List<File> files) {
        for (File file : files) {
            if (file != null && file.isFile()) return file;
        }
        return files.isEmpty() ? null : files.get(0);
    }

    private static TableSnapshot captureTable(File csvFile) throws IOException {
        List<String> lines = Files.readAllLines(csvFile.toPath(), StandardCharsets.UTF_8);
        if (lines.size() < 2) return null;

        String[] header = CsvTableIO.parseCsvLine(lines.get(0));
        List<String> keyColumns = chooseKeyColumns(header);
        TableSnapshot snapshot = new TableSnapshot();
        snapshot.keyColumns.addAll(keyColumns);

        LinkedHashMap<String, Integer> duplicateKeys = new LinkedHashMap<String, Integer>();
        for (int rowIndex = 1; rowIndex < lines.size(); rowIndex++) {
            String[] row = CsvTableIO.parseCsvLine(lines.get(rowIndex));
            if (row.length == 0) continue;

            RowSnapshot rowSnapshot = new RowSnapshot();
            String rowKey = buildRowKey(keyColumns, header, row, rowIndex, rowSnapshot.labels);
            Integer seen = duplicateKeys.get(rowKey);
            int suffix = seen == null ? 1 : seen + 1;
            duplicateKeys.put(rowKey, suffix);
            if (suffix > 1) {
                rowKey = rowKey + " #" + suffix;
            }

            for (int c = 0; c < header.length; c++) {
                String column = safeHeader(header[c], c);
                String value = c < row.length ? row[c].trim() : "";
                if (rowSnapshot.labels.containsKey(column)) continue;
                Double numeric = parseDouble(value);
                if (numeric != null) {
                    rowSnapshot.metrics.put(column, numeric);
                }
            }

            snapshot.rows.put(rowKey, rowSnapshot);
        }
        return snapshot;
    }

    private static List<String> chooseKeyColumns(String[] header) {
        List<String> preferred = Arrays.asList(
                "Animal Name", "Animal", "Region", "Hemisphere",
                "ROI", "ROI Set", "SCN", "Condition", "ConditionName", "numSections");
        List<String> keys = new ArrayList<String>();
        for (String candidate : preferred) {
            if (indexOf(header, candidate) >= 0) {
                keys.add(candidate);
            }
        }
        return keys;
    }

    private static String buildRowKey(List<String> keyColumns,
                                      String[] header,
                                      String[] row,
                                      int rowIndex,
                                      Map<String, String> labelStore) {
        List<String> parts = new ArrayList<String>();
        for (String keyColumn : keyColumns) {
            int index = indexOf(header, keyColumn);
            if (index < 0) continue;
            String value = index < row.length ? row[index].trim() : "";
            if (value.isEmpty()) continue;
            labelStore.put(keyColumn, value);
            parts.add(value);
        }
        if (parts.isEmpty()) {
            return "row " + rowIndex;
        }
        return joinList(parts);
    }

    private static List<String> changedSettings(Map<String, String> previous,
                                                Map<String, String> current) {
        List<String> lines = new ArrayList<String>();
        LinkedHashSet<String> keys = new LinkedHashSet<String>();
        if (previous != null) keys.addAll(previous.keySet());
        if (current != null) keys.addAll(current.keySet());

        for (String key : keys) {
            String oldValue = previous == null ? "" : trimToEmpty(previous.get(key));
            String newValue = current == null ? "" : trimToEmpty(current.get(key));
            if (oldValue.equals(newValue)) continue;
            lines.add(key + ": " + compactValue(oldValue) + " -> " + compactValue(newValue));
        }
        return lines;
    }

    private static List<String> tableDiffLines(Map<String, TableSnapshot> previous,
                                               Map<String, TableSnapshot> current) {
        List<String> lines = new ArrayList<String>();
        LinkedHashSet<String> tableNames = new LinkedHashSet<String>();
        if (previous != null) tableNames.addAll(previous.keySet());
        if (current != null) tableNames.addAll(current.keySet());

        for (String tableName : tableNames) {
            TableSnapshot before = previous == null ? null : previous.get(tableName);
            TableSnapshot after = current == null ? null : current.get(tableName);

            if (before == null && after != null) {
                lines.add("  INFO  " + tableName + " is new in this run.");
                continue;
            }
            if (before != null && after == null) {
                lines.add("  WARN  " + tableName + " is missing in this run.");
                continue;
            }

            int added = countAddedRows(before, after);
            int removed = countAddedRows(after, before);
            if (added > 0 || removed > 0) {
                lines.add("  INFO  " + tableName + ": " + added + " row(s) added, "
                        + removed + " removed.");
            }

            List<MetricShift> shifts = collectMetricShifts(tableName, before, after);
            if (!shifts.isEmpty()) {
                lines.add("  WARN  " + tableName + " moved:");
                int limit = Math.min(6, shifts.size());
                for (int i = 0; i < limit; i++) {
                    MetricShift shift = shifts.get(i);
                    lines.add("        " + shift.rowKey + " :: " + shift.metricName + " "
                            + formatShift(shift.oldValue, shift.newValue));
                }
                if (shifts.size() > limit) {
                    lines.add("        ... and " + (shifts.size() - limit) + " more");
                }
            }
        }

        return lines;
    }

    private static int countAddedRows(TableSnapshot before, TableSnapshot after) {
        int added = 0;
        if (before == null || after == null) return added;
        for (String rowKey : after.rows.keySet()) {
            if (!before.rows.containsKey(rowKey)) added++;
        }
        return added;
    }

    private static List<MetricShift> collectMetricShifts(String tableName,
                                                         TableSnapshot before,
                                                         TableSnapshot after) {
        List<MetricShift> shifts = new ArrayList<MetricShift>();
        if (before == null || after == null) return shifts;

        for (Map.Entry<String, RowSnapshot> entry : after.rows.entrySet()) {
            RowSnapshot oldRow = before.rows.get(entry.getKey());
            if (oldRow == null) continue;

            RowSnapshot newRow = entry.getValue();
            LinkedHashSet<String> metricNames = new LinkedHashSet<String>();
            metricNames.addAll(oldRow.metrics.keySet());
            metricNames.addAll(newRow.metrics.keySet());

            for (String metricName : metricNames) {
                Double oldValue = oldRow.metrics.get(metricName);
                Double newValue = newRow.metrics.get(metricName);
                if (oldValue == null || newValue == null) continue;
                if (Math.abs(oldValue - newValue) < 1e-9) continue;
                shifts.add(new MetricShift(tableName, entry.getKey(), metricName, oldValue, newValue));
            }
        }

        Collections.sort(shifts, new Comparator<MetricShift>() {
            @Override
            public int compare(MetricShift a, MetricShift b) {
                return Double.compare(b.score(), a.score());
            }
        });
        return shifts;
    }

    private static String formatShift(double oldValue, double newValue) {
        double delta = newValue - oldValue;
        if (Math.abs(oldValue) > 1e-9) {
            double pct = (delta / oldValue) * 100.0;
            return fmt(oldValue) + " -> " + fmt(newValue) + " ("
                    + signedFmt(delta) + ", " + signedPct(pct) + "%)";
        }
        return fmt(oldValue) + " -> " + fmt(newValue) + " (" + signedFmt(delta) + ")";
    }

    private static String joinList(List<String> values) {
        if (values == null || values.isEmpty()) return "";
        StringBuilder out = new StringBuilder();
        for (String value : values) {
            String trimmed = trimToEmpty(value);
            if (trimmed.isEmpty()) continue;
            if (out.length() > 0) out.append(" | ");
            out.append(trimmed);
        }
        return out.toString();
    }

    private static String compactValue(String value) {
        String trimmed = trimToEmpty(value);
        if (trimmed.isEmpty()) return "(blank)";
        return trimmed.length() > 80 ? trimmed.substring(0, 77) + "..." : trimmed;
    }

    private static String imageMetadataKey(MetadataDiagnostics.SeriesInfo info) {
        String displayName = trimToEmpty(MetadataDiagnostics.displaySeriesLabel(info));
        if (!displayName.isEmpty()) return displayName;
        return "series " + info.seriesIndex;
    }

    private static Integer bitDepthFromPixelType(String pixelType) {
        if (pixelType == null) return null;
        String lower = pixelType.toLowerCase(Locale.ROOT);
        if (lower.contains("8")) return 8;
        if (lower.contains("16")) return 16;
        if (lower.contains("32")) return 32;
        return null;
    }

    private static Double parseDouble(String value) {
        if (value == null || value.trim().isEmpty()) return null;
        try {
            return Double.valueOf(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static int indexOf(String[] header, String columnName) {
        if (header == null || columnName == null) return -1;
        for (int i = 0; i < header.length; i++) {
            if (columnName.equalsIgnoreCase(trimToEmpty(header[i]))) return i;
        }
        return -1;
    }

    private static String safeHeader(String headerValue, int index) {
        String trimmed = trimToEmpty(headerValue);
        return trimmed.isEmpty() ? "Column" + index : trimmed;
    }

    private static String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    private static String fmt(double value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }

    private static String signedFmt(double value) {
        return String.format(Locale.ROOT, "%+.3f", value);
    }

    private static String signedPct(double value) {
        return String.format(Locale.ROOT, "%+.1f", value);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : new LinkedHashMap<String, Object>();
    }

    @SuppressWarnings("unchecked")
    private static List<Object> asList(Object value) {
        return value instanceof List ? (List<Object>) value : new ArrayList<Object>();
    }

    private static final class MetricShift {
        final String tableName;
        final String rowKey;
        final String metricName;
        final double oldValue;
        final double newValue;

        MetricShift(String tableName, String rowKey, String metricName, double oldValue, double newValue) {
            this.tableName = tableName;
            this.rowKey = rowKey;
            this.metricName = metricName;
            this.oldValue = oldValue;
            this.newValue = newValue;
        }

        double score() {
            double absDelta = Math.abs(newValue - oldValue);
            if (Math.abs(oldValue) > 1e-9) {
                return absDelta / Math.abs(oldValue);
            }
            return absDelta + 1_000_000.0;
        }
    }

    static final class Snapshot {
        int schemaVersion = 2;
        String generatedAt = "";
        final Map<String, String> settings = new LinkedHashMap<String, String>();
        final Map<String, Map<String, Object>> imageMetadata = new LinkedHashMap<String, Map<String, Object>>();
        final Map<String, TableSnapshot> tables = new LinkedHashMap<String, TableSnapshot>();

        Map<String, Object> toJson() {
            LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
            out.put("schemaVersion", schemaVersion);
            out.put("generatedAt", generatedAt);
            out.put("settings", new LinkedHashMap<String, Object>(settings));

            LinkedHashMap<String, Object> metadataJson = new LinkedHashMap<String, Object>();
            for (Map.Entry<String, Map<String, Object>> entry : imageMetadata.entrySet()) {
                metadataJson.put(entry.getKey(), entry.getValue());
            }
            out.put("imageMetadata", metadataJson);

            LinkedHashMap<String, Object> tablesJson = new LinkedHashMap<String, Object>();
            for (Map.Entry<String, TableSnapshot> entry : tables.entrySet()) {
                tablesJson.put(entry.getKey(), entry.getValue().toJson());
            }
            out.put("tables", tablesJson);
            return out;
        }

        static Snapshot fromJson(Map<String, Object> json) {
            Snapshot snapshot = new Snapshot();
            Object version = json.get("schemaVersion");
            if (version instanceof Number) {
                snapshot.schemaVersion = ((Number) version).intValue();
            }
            snapshot.generatedAt = trimToEmpty(stringValue(json.get("generatedAt")));

            Map<String, Object> settingsJson = asMap(json.get("settings"));
            for (Map.Entry<String, Object> entry : settingsJson.entrySet()) {
                snapshot.settings.put(entry.getKey(), stringValue(entry.getValue()));
            }

            Map<String, Object> metadataJson = asMap(json.get("imageMetadata"));
            for (Map.Entry<String, Object> entry : metadataJson.entrySet()) {
                snapshot.imageMetadata.put(entry.getKey(), asMap(entry.getValue()));
            }

            Map<String, Object> tablesJson = asMap(json.get("tables"));
            for (Map.Entry<String, Object> entry : tablesJson.entrySet()) {
                snapshot.tables.put(entry.getKey(), TableSnapshot.fromJson(asMap(entry.getValue())));
            }
            return snapshot;
        }
    }

    static final class TableSnapshot {
        final List<String> keyColumns = new ArrayList<String>();
        final Map<String, RowSnapshot> rows = new LinkedHashMap<String, RowSnapshot>();

        Map<String, Object> toJson() {
            LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
            out.put("keyColumns", new ArrayList<String>(keyColumns));
            LinkedHashMap<String, Object> rowsJson = new LinkedHashMap<String, Object>();
            for (Map.Entry<String, RowSnapshot> entry : rows.entrySet()) {
                rowsJson.put(entry.getKey(), entry.getValue().toJson());
            }
            out.put("rows", rowsJson);
            return out;
        }

        static TableSnapshot fromJson(Map<String, Object> json) {
            TableSnapshot snapshot = new TableSnapshot();
            for (Object keyColumn : asList(json.get("keyColumns"))) {
                snapshot.keyColumns.add(stringValue(keyColumn));
            }
            Map<String, Object> rowsJson = asMap(json.get("rows"));
            for (Map.Entry<String, Object> entry : rowsJson.entrySet()) {
                snapshot.rows.put(entry.getKey(), RowSnapshot.fromJson(asMap(entry.getValue())));
            }
            return snapshot;
        }
    }

    static final class RowSnapshot {
        final Map<String, String> labels = new LinkedHashMap<String, String>();
        final Map<String, Double> metrics = new LinkedHashMap<String, Double>();

        Map<String, Object> toJson() {
            LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
            out.put("labels", new LinkedHashMap<String, Object>(labels));
            LinkedHashMap<String, Object> metricsJson = new LinkedHashMap<String, Object>();
            for (Map.Entry<String, Double> entry : metrics.entrySet()) {
                metricsJson.put(entry.getKey(), entry.getValue());
            }
            out.put("metrics", metricsJson);
            return out;
        }

        static RowSnapshot fromJson(Map<String, Object> json) {
            RowSnapshot snapshot = new RowSnapshot();
            Map<String, Object> labelsJson = asMap(json.get("labels"));
            for (Map.Entry<String, Object> entry : labelsJson.entrySet()) {
                snapshot.labels.put(entry.getKey(), stringValue(entry.getValue()));
            }
            Map<String, Object> metricsJson = asMap(json.get("metrics"));
            for (Map.Entry<String, Object> entry : metricsJson.entrySet()) {
                Object value = entry.getValue();
                if (value instanceof Number) {
                    snapshot.metrics.put(entry.getKey(), ((Number) value).doubleValue());
                }
            }
            return snapshot;
        }
    }

    private static String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private abstract static class ImageMetricAccumulator {
        final String animalId;
        final String hemisphere;
        final String region;
        final int sectionIndex;
        final String roiLabel;
        final Map<String, Double> metrics = new LinkedHashMap<String, Double>();

        ImageMetricAccumulator(String animalId, String hemisphere,
                               String region, int sectionIndex, String roiLabel) {
            this.animalId = trimToEmpty(animalId);
            this.hemisphere = trimToEmpty(hemisphere);
            this.region = trimToEmpty(region);
            this.sectionIndex = Math.max(0, sectionIndex);
            this.roiLabel = trimToEmpty(roiLabel);
        }

        String displayName() {
            StringBuilder sb = new StringBuilder();
            if (!animalId.isEmpty()) sb.append(animalId);
            if (!hemisphere.isEmpty()) {
                if (sb.length() > 0) sb.append(" ");
                sb.append(hemisphere);
            }
            if (!region.isEmpty()) {
                if (sb.length() > 0) sb.append(" ");
                sb.append(region);
            }
            if (sectionIndex > 0) {
                if (sb.length() > 0) sb.append(" ");
                sb.append("section ").append(sectionIndex);
            } else if (!roiLabel.isEmpty()) {
                if (sb.length() > 0) sb.append(" ");
                sb.append(roiLabel);
            }
            return sb.length() == 0 ? "image" : sb.toString();
        }
    }

    private static final class ObjectMetricAccumulator extends ImageMetricAccumulator {
        int objectCount = 0;
        double meanSum = 0.0;
        int meanCount = 0;
        double intDenSum = 0.0;
        int intDenCount = 0;

        ObjectMetricAccumulator(String animalId, String hemisphere,
                                String region, int sectionIndex, String roiLabel) {
            super(animalId, hemisphere, region, sectionIndex, roiLabel);
        }
    }

    private static final class IntensityMetricAccumulator extends ImageMetricAccumulator {
        double intDenSum = 0.0;
        int intDenCount = 0;
        double areaSum = 0.0;
        int areaCount = 0;
        double intDenUnfilteredSum = 0.0;
        int intDenUnfilteredCount = 0;

        IntensityMetricAccumulator(String animalId, String hemisphere,
                                   String region, int sectionIndex, String roiLabel) {
            super(animalId, hemisphere, region, sectionIndex, roiLabel);
        }

        void addIntDen(double value) {
            if (!Double.isNaN(value)) {
                intDenSum += value;
                intDenCount++;
            }
        }

        void addArea(double value) {
            if (!Double.isNaN(value)) {
                areaSum += value;
                areaCount++;
            }
        }

        void addIntDenUnfiltered(double value) {
            if (!Double.isNaN(value)) {
                intDenUnfilteredSum += value;
                intDenUnfilteredCount++;
            }
        }
    }
}
