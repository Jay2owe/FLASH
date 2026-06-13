package flash.pipeline.representative;

import flash.pipeline.bin.BinConfig;
import flash.pipeline.bin.BinConfigIO;
import flash.pipeline.io.ConditionManifestIO;
import flash.pipeline.io.CsvSupport;
import flash.pipeline.io.DeferredImageSupplier;
import flash.pipeline.io.FlashProjectLayout;
import flash.pipeline.io.ImageSourceDispatcher;
import flash.pipeline.io.SeriesMeta;
import flash.pipeline.naming.ConditionNameParser;
import flash.pipeline.naming.ImageNameParser;
import flash.pipeline.naming.NameParts;
import flash.pipeline.qc.QcMinMaxPerConditionSelector;
import flash.pipeline.qc.QcSelectionCandidate;
import flash.pipeline.qc.QcSelectionChannel;
import flash.pipeline.results.RunIdCsv;
import ij.IJ;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Loads the statistic table used to guide representative image selection.
 */
public final class RepresentativeStatLoader {

    public static final String NO_EXISTING_RESULT_OPTION =
            "No numeric result columns found";

    private static final Pattern QC_CHANNEL_SCORE =
            Pattern.compile("(?i)^Channel(\\d+)Score$");
    private static final int DISCOVERY_ROW_LIMIT = 100;

    private RepresentativeStatLoader() {
    }

    public static RepresentativeStatTable load(String directory,
                                               RepresentativeStatistic statistic,
                                               ExistingResultOption existingResult,
                                               int parallelThreads) throws Exception {
        return load(directory, statistic, existingResult, parallelThreads, null);
    }

    public static RepresentativeStatTable load(String directory,
                                               RepresentativeStatistic statistic,
                                               ExistingResultOption existingResult,
                                               int parallelThreads,
                                               List<SeriesMeta> metas) throws Exception {
        RepresentativeStatistic mode = statistic == null
                ? RepresentativeStatistic.QUICK
                : statistic;
        if (mode == RepresentativeStatistic.NONE) {
            return new RepresentativeStatTable();
        }
        if (mode == RepresentativeStatistic.EXISTING_RESULT) {
            return loadExistingResult(directory, existingResult,
                    metas == null ? ImageSourceDispatcher.readAllMetadata(directory) : metas);
        }
        return loadQuick(directory, parallelThreads, metas);
    }

    public static RepresentativeStatTable loadQuick(String directory,
                                                    int parallelThreads) throws Exception {
        return loadQuick(directory, parallelThreads, null);
    }

    public static RepresentativeStatTable loadQuick(String directory,
                                                    int parallelThreads,
                                                    List<SeriesMeta> metas) throws Exception {
        long start = System.currentTimeMillis();
        FlashProjectLayout layout = FlashProjectLayout.forDirectory(directory);
        File container = resolveQuickContainerFile(directory);
        DeferredImageSupplier supplier = ImageSourceDispatcher.createSupplier(directory);
        try {
            List<SeriesMeta> effectiveMetas =
                    metas == null ? ImageSourceDispatcher.readAllMetadata(directory) : metas;

            BinConfig cfg = BinConfigIO.readPartialFromDirectory(directory);
            List<QcSelectionChannel> channels = buildAllChannels(cfg, effectiveMetas);
            if (channels.isEmpty()) {
                throw new IOException("No image channels were found for quick representative scoring. "
                        + "Run Set Up Configuration or check the source image metadata.");
            }

            IJ.log("[Representative Figure] Quick statistic scoring "
                    + effectiveMetas.size() + " metadata series across "
                    + channels.size() + " channel"
                    + (channels.size() == 1 ? "" : "s")
                    + " using " + Math.max(1, parallelThreads) + " thread"
                    + (Math.max(1, parallelThreads) == 1 ? "" : "s") + ".");
            QcMinMaxPerConditionSelector.SelectionResult result =
                    QcMinMaxPerConditionSelector.selectMinMaxPerCondition(
                            directory,
                            layout.configurationWriteDir(),
                            container,
                            supplier,
                            effectiveMetas,
                            channels,
                            cfg.getZSliceConfig(),
                            false,
                            Math.max(1, parallelThreads),
                            null);
            IJ.log("[Representative Figure] Quick statistic scores "
                    + (result.usedCache ? "loaded from cache" : "computed")
                    + " in " + elapsed(start) + ": "
                    + result.scoresFile.getAbsolutePath());

            return loadQuickScoresCsv(result.scoresFile, channelLabelsByNumber(channels));
        } finally {
            supplier.shutdownPrefetch();
        }
    }

    public static RepresentativeStatTable loadQuickScoresCsv(File scoresFile) throws IOException {
        return loadQuickScoresCsv(scoresFile, Collections.<Integer, String>emptyMap());
    }

    public static RepresentativeStatTable loadQuickScoresCsv(File scoresFile,
                                                             Map<Integer, String> channelNamesByNumber)
            throws IOException {
        CsvSnapshot csv = readCsv(scoresFile, -1);
        RepresentativeStatTable table = new RepresentativeStatTable();
        if (csv.header.isEmpty()) return table;

        int conditionCol = csv.columnIndex("Condition");
        int seriesIndexCol = csv.columnIndex("SeriesIndex");
        int seriesNumberCol = csv.columnIndex("SeriesNumber");
        int seriesNameCol = csv.columnIndex("SeriesName");
        int animalCol = csv.columnIndex("AnimalName");
        if (animalCol < 0) animalCol = csv.columnIndex("Animal Name");

        LinkedHashMap<Integer, Integer> scoreColsByChannel =
                new LinkedHashMap<Integer, Integer>();
        for (int i = 0; i < csv.header.size(); i++) {
            Matcher matcher = QC_CHANNEL_SCORE.matcher(csv.header.get(i));
            if (matcher.matches()) {
                scoreColsByChannel.put(Integer.valueOf(parseInt(matcher.group(1), -1)),
                        Integer.valueOf(i));
            }
        }

        for (List<String> row : csv.rows) {
            int seriesIndex = parseInt(cell(row, seriesIndexCol), -1);
            int seriesNumber = parseInt(cell(row, seriesNumberCol),
                    seriesIndex >= 0 ? seriesIndex + 1 : 0);
            String seriesName = cell(row, seriesNameCol);
            String animal = cleanSpreadsheetPrefix(cell(row, animalCol));
            String condition = cell(row, conditionCol);
            if (condition.trim().isEmpty()) {
                condition = ConditionNameParser.detectCondition(animal);
            }
            NameParts parts = ImageNameParser.parse(seriesName);

            for (Map.Entry<Integer, Integer> scoreCol : scoreColsByChannel.entrySet()) {
                Double value = parseFiniteDouble(cell(row, scoreCol.getValue().intValue()));
                if (value == null) continue;
                Integer channelNumber = scoreCol.getKey();
                String channelName = channelNamesByNumber.get(channelNumber);
                if (channelName == null || channelName.trim().isEmpty()) {
                    channelName = "C" + channelNumber;
                }
                table.putValue(
                        RepresentativeStatTable.seriesIdForIndex(seriesIndex),
                        seriesIndex,
                        seriesNumber,
                        seriesName,
                        animal,
                        condition,
                        parts.hemisphere,
                        parts.csvRegion(),
                        channelName,
                        value.doubleValue());
            }
        }
        return table;
    }

    public static List<ExistingResultOption> discoverExistingResultOptions(String directory) {
        FlashProjectLayout layout = FlashProjectLayout.forDirectory(directory);
        File tablesRoot = layout.tablesRoot();
        if (!tablesRoot.isDirectory()) {
            return Collections.emptyList();
        }

        List<File> csvFiles = new ArrayList<File>();
        collectCsvFiles(tablesRoot, csvFiles);
        Collections.sort(csvFiles, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                return relativePath(layout.resultsRoot(), a)
                        .compareToIgnoreCase(relativePath(layout.resultsRoot(), b));
            }
        });

        List<ExistingResultOption> options = new ArrayList<ExistingResultOption>();
        for (File csvFile : csvFiles) {
            if (FlashProjectLayout.CONDITIONS_FILENAME.equalsIgnoreCase(csvFile.getName())) {
                continue;
            }
            try {
                CsvSnapshot csv = readCsv(csvFile, DISCOVERY_ROW_LIMIT);
                for (String column : discoverNumericColumns(csv)) {
                    options.add(new ExistingResultOption(
                            csvFile,
                            column,
                            relativePath(layout.resultsRoot(), csvFile)));
                }
            } catch (IOException e) {
                IJ.log("[Representative Figure] Could not inspect result CSV "
                        + csvFile.getAbsolutePath() + ": " + e.getMessage());
            }
        }
        return options;
    }

    public static RepresentativeStatTable loadExistingResult(String directory,
                                                             ExistingResultOption option)
            throws Exception {
        return loadExistingResult(directory, option,
                ImageSourceDispatcher.readAllMetadata(directory));
    }

    public static RepresentativeStatTable loadExistingResult(String directory,
                                                             ExistingResultOption option,
                                                             List<SeriesMeta> metas)
            throws IOException {
        if (option == null || option.file == null || !option.file.isFile()) {
            throw new IOException("Pick an existing result CSV before using Existing result mode.");
        }
        if (option.columnName == null || option.columnName.trim().isEmpty()) {
            throw new IOException("Pick a numeric result column before using Existing result mode.");
        }

        CsvSnapshot csv = readCsv(option.file, -1);
        int valueCol = csv.columnIndex(option.columnName);
        if (valueCol < 0) {
            throw new IOException("Column '" + option.columnName + "' was not found in "
                    + option.file.getAbsolutePath());
        }

        List<QcSelectionCandidate> candidates =
                QcMinMaxPerConditionSelector.buildCandidates(directory, metas);
        SeriesLookup lookup = SeriesLookup.from(candidates);
        List<String> configuredChannels = configuredChannelNames(directory);
        String channelName = inferChannelName(option.file, option.columnName, configuredChannels);

        List<RowIdentity> rowIdentities = new ArrayList<RowIdentity>();
        LinkedHashSet<String> rowAnimals = new LinkedHashSet<String>();
        for (List<String> row : csv.rows) {
            RowIdentity identity = RowIdentity.from(csv.header, row);
            rowIdentities.add(identity);
            if (!identity.animalName.trim().isEmpty()) {
                rowAnimals.add(identity.animalName);
            }
        }
        Map<String, String> rowAssignments =
                ConditionManifestIO.resolveAssignments(directory, rowAnimals);

        LinkedHashMap<String, Accumulator> accumulators =
                new LinkedHashMap<String, Accumulator>();
        int skipped = 0;
        for (int rowIndex = 0; rowIndex < csv.rows.size(); rowIndex++) {
            List<String> row = csv.rows.get(rowIndex);
            Double value = parseFiniteDouble(cell(row, valueCol));
            if (value == null) continue;

            RowIdentity identity = applyManifestCondition(
                    rowIdentities.get(rowIndex), rowAssignments);
            SeriesSeed seed = lookup.resolve(identity);
            if (seed == null) {
                skipped++;
                continue;
            }

            String key = seed.seriesId + "\n" + channelName;
            Accumulator accumulator = accumulators.get(key);
            if (accumulator == null) {
                accumulator = new Accumulator(seed, channelName, identity.conditionName);
                accumulators.put(key, accumulator);
            }
            accumulator.add(value.doubleValue());
        }

        if (skipped > 0) {
            IJ.log("[Representative Figure] Existing result skipped " + skipped
                    + " row(s) that could not be mapped unambiguously to a source series.");
        }

        RepresentativeStatTable table = new RepresentativeStatTable();
        for (Accumulator accumulator : accumulators.values()) {
            if (accumulator.count <= 0) continue;
            SeriesSeed seed = accumulator.seed;
            table.putValue(
                    seed.seriesId,
                    seed.seriesIndex,
                    seed.seriesNumber,
                    seed.seriesName,
                    seed.animalName,
                    accumulator.conditionName,
                    seed.hemisphere,
                    seed.region,
                    accumulator.channelName,
                    accumulator.mean());
        }
        return table;
    }

    private static RowIdentity applyManifestCondition(RowIdentity identity,
                                                      Map<String, String> assignments) {
        if (identity == null || assignments == null || assignments.isEmpty()) {
            return identity;
        }
        String condition = assignments.get(identity.animalName);
        if (condition == null || condition.trim().isEmpty()) {
            return identity;
        }
        return identity.withCondition(condition);
    }

    private static List<QcSelectionChannel> buildAllChannels(BinConfig cfg,
                                                             List<SeriesMeta> metas) {
        int n = cfg == null || cfg.channelNames == null ? 0 : cfg.channelNames.size();
        if (n <= 0) {
            n = maxChannelCount(metas);
        }

        List<QcSelectionChannel> channels = new ArrayList<QcSelectionChannel>();
        for (int i = 0; i < n; i++) {
            String name = cfg != null && cfg.channelNames != null && i < cfg.channelNames.size()
                    ? cfg.channelNames.get(i)
                    : "";
            if (name == null || name.trim().isEmpty()) {
                name = "C" + (i + 1);
            }
            channels.add(new QcSelectionChannel(i, name, true, true, true));
        }
        return channels;
    }

    private static int maxChannelCount(List<SeriesMeta> metas) {
        int max = 0;
        if (metas != null) {
            for (SeriesMeta meta : metas) {
                if (meta != null && meta.nChannels > max) {
                    max = meta.nChannels;
                }
            }
        }
        return max;
    }

    private static Map<Integer, String> channelLabelsByNumber(List<QcSelectionChannel> channels) {
        LinkedHashMap<Integer, String> out = new LinkedHashMap<Integer, String>();
        for (QcSelectionChannel channel : channels) {
            out.put(Integer.valueOf(channel.channelNumber), channel.channelName);
        }
        return out;
    }

    private static File resolveQuickContainerFile(String directory) {
        boolean hasProjectManifest = ImageSourceDispatcher.hasProjectManifest(directory);
        List<File> projectContainers = ImageSourceDispatcher.projectContainerFiles(directory);
        if (!projectContainers.isEmpty()) {
            if (projectContainers.size() == 1) {
                return projectContainers.get(0);
            }
            throw new IllegalArgumentException(
                    "Project contains multiple container files. Quick representative scoring "
                            + "currently supports one container at a time.");
        }
        List<File> projectTiffs = ImageSourceDispatcher.projectTiffFiles(directory);
        if (!projectTiffs.isEmpty()) {
            return null;
        }
        if (hasProjectManifest) {
            throw new IllegalArgumentException(
                    "Project does not contain an included container source for quick scoring.");
        }
        ImageSourceDispatcher.SourceMode mode = ImageSourceDispatcher.detectMode(directory);
        if (mode == ImageSourceDispatcher.SourceMode.CONTAINER) {
            return ImageSourceDispatcher.selectContainer(new File(directory));
        }
        return null;
    }

    private static List<String> discoverNumericColumns(CsvSnapshot csv) {
        List<String> columns = new ArrayList<String>();
        for (int i = 0; i < csv.header.size(); i++) {
            String column = csv.header.get(i);
            if (isIdentityColumn(column)) continue;
            boolean numeric = false;
            for (List<String> row : csv.rows) {
                if (parseFiniteDouble(cell(row, i)) != null) {
                    numeric = true;
                    break;
                }
            }
            if (numeric) {
                columns.add(column);
            }
        }
        return columns;
    }

    private static boolean isIdentityColumn(String column) {
        String normalized = normalizeHeader(column);
        return normalized.equals("animalname")
                || normalized.equals("animal")
                || normalized.equals("condition")
                || normalized.equals("region")
                || normalized.equals("hemisphere")
                || normalized.equals("roi")
                || normalized.equals("roiset")
                || normalized.equals("scn")
                || normalized.equals("seriesindex")
                || normalized.equals("seriesnumber")
                || normalized.equals("seriesname")
                || normalized.equals("numsections")
                || normalized.equals("numzslices")
                || normalized.equals(normalizeHeader(RunIdCsv.RUN_ID_COLUMN))
                || normalized.equals(normalizeHeader(RunIdCsv.SOURCE_RUN_ID_COLUMN));
    }

    private static List<String> configuredChannelNames(String directory) {
        try {
            BinConfig cfg = BinConfigIO.readPartialFromDirectory(directory);
            return cfg == null || cfg.channelNames == null
                    ? Collections.<String>emptyList()
                    : new ArrayList<String>(cfg.channelNames);
        } catch (RuntimeException e) {
            return Collections.emptyList();
        }
    }

    private static String inferChannelName(File file, String columnName, List<String> configuredChannels) {
        String column = columnName == null ? "" : columnName.trim();
        if (configuredChannels != null) {
            for (String channel : configuredChannels) {
                String clean = channel == null ? "" : channel.trim();
                if (clean.isEmpty()) continue;
                if (startsWithChannelPrefix(column, clean)) {
                    return clean;
                }
            }
        }

        String stem = fileStem(file == null ? "" : file.getName());
        if (!stem.isEmpty()
                && !FlashProjectLayout.MASTER_OBJECTS_FILENAME.equalsIgnoreCase(file.getName())
                && !FlashProjectLayout.MASTER_INTENSITIES_FILENAME.equalsIgnoreCase(file.getName())
                && !FlashProjectLayout.STATISTICS_FILENAME.equalsIgnoreCase(file.getName())) {
            return stem;
        }

        return column.isEmpty() ? "Existing result" : column;
    }

    private static boolean startsWithChannelPrefix(String columnName, String channelName) {
        String column = columnName == null ? "" : columnName.trim();
        String channel = channelName == null ? "" : channelName.trim();
        if (column.length() <= channel.length()) return false;
        if (!column.regionMatches(true, 0, channel, 0, channel.length())) return false;
        char next = column.charAt(channel.length());
        return next == '_' || next == '-' || Character.isWhitespace(next);
    }

    private static String fileStem(String name) {
        String text = name == null ? "" : name.trim();
        int dot = text.lastIndexOf('.');
        return dot > 0 ? text.substring(0, dot) : text;
    }

    private static void collectCsvFiles(File dir, List<File> out) {
        File[] files = dir == null ? null : dir.listFiles();
        if (files == null) return;
        for (File file : files) {
            if (file.isDirectory()) {
                collectCsvFiles(file, out);
            } else if (file.getName().toLowerCase(Locale.ROOT).endsWith(".csv")) {
                out.add(file);
            }
        }
    }

    private static CsvSnapshot readCsv(File file, int maxRows) throws IOException {
        CsvSnapshot snapshot = new CsvSnapshot(file);
        if (file == null || !file.isFile()) {
            throw new IOException("CSV file not found: " + (file == null ? "null" : file.getAbsolutePath()));
        }
        CsvSupport.RecordReader reader = CsvSupport.openRecordReader(file);
        try {
            CsvSupport.Record headerRecord = reader.readRecord();
            if (headerRecord == null) return snapshot;
            String[] header = CsvSupport.parseRecord(headerRecord.text);
            for (String column : header) {
                snapshot.header.add(column == null ? "" : column.trim());
            }

            CsvSupport.Record record;
            while ((record = reader.readRecord()) != null) {
                if (CsvSupport.isBlankRecord(record.text)) continue;
                String[] fields = CsvSupport.parseRecord(record.text);
                List<String> row = new ArrayList<String>();
                for (int i = 0; i < snapshot.header.size(); i++) {
                    row.add(i < fields.length ? fields[i] : "");
                }
                snapshot.rows.add(row);
                if (maxRows >= 0 && snapshot.rows.size() >= maxRows) break;
            }
            return snapshot;
        } finally {
            reader.close();
        }
    }

    private static String relativePath(File root, File file) {
        if (root == null || file == null) return file == null ? "" : file.getName();
        try {
            String rootPath = root.getCanonicalPath();
            String filePath = file.getCanonicalPath();
            if (filePath.equals(rootPath)) return file.getName();
            if (filePath.startsWith(rootPath + File.separator)) {
                return filePath.substring(rootPath.length() + 1);
            }
        } catch (IOException ignored) {
        }
        return file.getName();
    }

    private static String cell(List<String> row, int index) {
        if (row == null || index < 0 || index >= row.size()) return "";
        return row.get(index);
    }

    private static int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt((value == null ? "" : value.trim()));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static Double parseFiniteDouble(String value) {
        if (value == null || value.trim().isEmpty()) return null;
        try {
            double parsed = Double.parseDouble(value.trim());
            if (Double.isNaN(parsed) || Double.isInfinite(parsed)) return null;
            return Double.valueOf(parsed);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String cleanSpreadsheetPrefix(String value) {
        String text = value == null ? "" : value.trim();
        if (text.length() > 1 && text.charAt(0) == '\'') {
            char next = text.charAt(1);
            if (next == '=' || next == '+' || next == '-' || next == '@'
                    || next == '\t' || next == '\r' || next == '\n') {
                return text.substring(1);
            }
        }
        return text;
    }

    private static String normalizeHeader(String value) {
        String text = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String elapsed(long startMillis) {
        long elapsed = Math.max(0L, System.currentTimeMillis() - startMillis);
        if (elapsed < 1000L) {
            return elapsed + " ms";
        }
        return String.format(Locale.ROOT, "%.1f s", elapsed / 1000.0);
    }

    private static String normalizeKeyPart(String value) {
        return cleanSpreadsheetPrefix(value).toLowerCase(Locale.ROOT);
    }

    private static String key(String animal, String condition, String hemisphere, String region) {
        String a = normalizeKeyPart(animal);
        if (a.isEmpty()) return "";
        return a + "|" + normalizeKeyPart(condition)
                + "|" + normalizeKeyPart(hemisphere)
                + "|" + normalizeKeyPart(region);
    }

    public static final class ExistingResultOption {
        public final File file;
        public final String columnName;
        public final String relativePath;
        public final String label;

        public ExistingResultOption(File file, String columnName) {
            this(file, columnName, file == null ? "" : file.getName());
        }

        public ExistingResultOption(File file, String columnName, String relativePath) {
            this.file = file;
            this.columnName = columnName == null ? "" : columnName.trim();
            this.relativePath = relativePath == null || relativePath.trim().isEmpty()
                    ? (file == null ? "" : file.getName())
                    : relativePath.trim();
            this.label = this.relativePath + " :: " + this.columnName;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private static final class CsvSnapshot {
        final File file;
        final List<String> header = new ArrayList<String>();
        final List<List<String>> rows = new ArrayList<List<String>>();
        final Map<String, Integer> columnIndex = new LinkedHashMap<String, Integer>();

        CsvSnapshot(File file) {
            this.file = file;
        }

        int columnIndex(String name) {
            if (columnIndex.isEmpty()) {
                for (int i = 0; i < header.size(); i++) {
                    columnIndex.put(normalizeHeader(header.get(i)), Integer.valueOf(i));
                }
            }
            Integer index = columnIndex.get(normalizeHeader(name));
            return index == null ? -1 : index.intValue();
        }
    }

    private static final class Accumulator {
        final SeriesSeed seed;
        final String channelName;
        final String conditionName;
        double sum = 0.0;
        int count = 0;

        Accumulator(SeriesSeed seed, String channelName, String conditionName) {
            this.seed = seed;
            this.channelName = channelName;
            this.conditionName = conditionName == null || conditionName.trim().isEmpty()
                    ? seed.conditionName
                    : conditionName.trim();
        }

        void add(double value) {
            sum += value;
            count++;
        }

        double mean() {
            return count <= 0 ? Double.NaN : sum / count;
        }
    }

    private static final class RowIdentity {
        final int seriesIndex;
        final int seriesNumber;
        final String seriesName;
        final String animalName;
        final String conditionName;
        final String hemisphere;
        final String region;

        RowIdentity(int seriesIndex,
                    int seriesNumber,
                    String seriesName,
                    String animalName,
                    String conditionName,
                    String hemisphere,
                    String region) {
            this.seriesIndex = seriesIndex;
            this.seriesNumber = seriesNumber;
            this.seriesName = seriesName;
            this.animalName = animalName;
            this.conditionName = conditionName;
            this.hemisphere = hemisphere;
            this.region = region;
        }

        RowIdentity withCondition(String conditionName) {
            return new RowIdentity(seriesIndex, seriesNumber, seriesName, animalName,
                    conditionName, hemisphere, region);
        }

        static RowIdentity from(List<String> header, List<String> row) {
            LinkedHashMap<String, Integer> columns = new LinkedHashMap<String, Integer>();
            for (int i = 0; i < header.size(); i++) {
                columns.put(normalizeHeader(header.get(i)), Integer.valueOf(i));
            }

            int seriesIndex = parseInt(value(columns, row, "SeriesIndex"), -1);
            int seriesNumber = parseInt(value(columns, row, "SeriesNumber"), 0);
            String seriesName = firstValue(columns, row, "SeriesName", "ImageName", "Image", "Title");
            String animal = firstValue(columns, row, "AnimalName", "Animal Name", "Animal", "Sample");
            String condition = firstValue(columns, row, "Condition", "ConditionName");
            String hemisphere = firstValue(columns, row, "Hemisphere");
            String region = firstValue(columns, row, "Region");

            NameParts parts = seriesName.trim().isEmpty()
                    ? null
                    : ImageNameParser.parse(seriesName);
            if (animal.trim().isEmpty()) {
                animal = ConditionManifestIO.extractAnimalName(seriesName);
            }
            if (animal.trim().isEmpty() && parts != null) {
                animal = parts.animal;
            }
            if (hemisphere.trim().isEmpty()) {
                hemisphere = parts == null ? "" : parts.hemisphere;
            }
            if (region.trim().isEmpty()) {
                region = parts == null ? "" : parts.csvRegion();
            }
            animal = cleanSpreadsheetPrefix(animal);
            if (condition.trim().isEmpty()) {
                condition = ConditionNameParser.detectCondition(animal);
            }

            return new RowIdentity(seriesIndex, seriesNumber, seriesName,
                    animal, condition, hemisphere, region);
        }

        private static String firstValue(Map<String, Integer> columns,
                                         List<String> row,
                                         String... names) {
            for (String name : names) {
                String value = value(columns, row, name);
                if (!value.trim().isEmpty()) return value;
            }
            return "";
        }

        private static String value(Map<String, Integer> columns,
                                    List<String> row,
                                    String name) {
            Integer index = columns.get(normalizeHeader(name));
            return index == null ? "" : cell(row, index.intValue());
        }
    }

    private static final class SeriesSeed {
        final String seriesId;
        final int seriesIndex;
        final int seriesNumber;
        final String seriesName;
        final String animalName;
        final String conditionName;
        final String hemisphere;
        final String region;

        SeriesSeed(QcSelectionCandidate candidate) {
            NameParts parts = ImageNameParser.parse(candidate == null ? "" : candidate.seriesName);
            int index = candidate == null ? -1 : candidate.seriesIndex;
            this.seriesId = RepresentativeStatTable.seriesIdForIndex(index);
            this.seriesIndex = index;
            this.seriesNumber = candidate == null ? 0 : candidate.seriesNumber;
            this.seriesName = candidate == null ? "" : candidate.seriesName;
            this.animalName = candidate == null ? "" : candidate.animalName;
            this.conditionName = candidate == null ? "" : candidate.conditionName;
            this.hemisphere = parts.hemisphere;
            this.region = parts.csvRegion();
        }
    }

    private static final class SeriesLookup {
        final Map<String, SeriesSeed> bySeriesId = new LinkedHashMap<String, SeriesSeed>();
        final Map<String, List<SeriesSeed>> byKey = new LinkedHashMap<String, List<SeriesSeed>>();

        static SeriesLookup from(List<QcSelectionCandidate> candidates) {
            SeriesLookup lookup = new SeriesLookup();
            if (candidates == null) return lookup;
            for (QcSelectionCandidate candidate : candidates) {
                SeriesSeed seed = new SeriesSeed(candidate);
                lookup.bySeriesId.put(seed.seriesId, seed);
                lookup.addSeed(seed);
            }
            return lookup;
        }

        SeriesSeed resolve(RowIdentity identity) {
            if (identity == null) return null;
            if (identity.seriesIndex >= 0) {
                SeriesSeed seed = bySeriesId.get(
                        RepresentativeStatTable.seriesIdForIndex(identity.seriesIndex));
                if (seed != null) return seed;
            }
            if (identity.seriesNumber > 0) {
                SeriesSeed seed = bySeriesId.get(
                        RepresentativeStatTable.seriesIdForIndex(identity.seriesNumber - 1));
                if (seed != null) return seed;
            }

            List<String> keys = keysForIdentity(identity);
            for (String key : keys) {
                SeriesSeed seed = uniqueSeed(byKey.get(key));
                if (seed != null) return seed;
            }
            return null;
        }

        private void addSeed(SeriesSeed seed) {
            addSeedForAnimal(seed, seed.animalName);

            NameParts parts = ImageNameParser.parse(seed.seriesName);
            String parsedAnimal = parts == null ? "" : parts.animal;
            if (!normalizeKeyPart(parsedAnimal).equals(normalizeKeyPart(seed.animalName))) {
                addSeedForAnimal(seed, parsedAnimal);
            }
        }

        private void addSeedForAnimal(SeriesSeed seed, String animalName) {
            add(seed, animalName, seed.conditionName, seed.hemisphere, seed.region);
            add(seed, animalName, seed.conditionName, "", seed.region);
            add(seed, animalName, "", seed.hemisphere, seed.region);
            add(seed, animalName, "", "", seed.region);
            add(seed, animalName, seed.conditionName, seed.hemisphere, "");
            add(seed, animalName, seed.conditionName, "", "");
            add(seed, animalName, "", seed.hemisphere, "");
            add(seed, animalName, "", "", "");
        }

        private void add(SeriesSeed seed,
                         String animal,
                         String condition,
                         String hemisphere,
                         String region) {
            String key = key(animal, condition, hemisphere, region);
            if (key.isEmpty()) return;
            List<SeriesSeed> list = byKey.get(key);
            if (list == null) {
                list = new ArrayList<SeriesSeed>();
                byKey.put(key, list);
            }
            for (SeriesSeed existing : list) {
                if (existing.seriesId.equals(seed.seriesId)) return;
            }
            list.add(seed);
        }

        private static List<String> keysForIdentity(RowIdentity identity) {
            LinkedHashSet<String> keys = new LinkedHashSet<String>();
            addKey(keys, identity.animalName, identity.conditionName,
                    identity.hemisphere, identity.region);
            addKey(keys, identity.animalName, identity.conditionName,
                    "", identity.region);
            addKey(keys, identity.animalName, "",
                    identity.hemisphere, identity.region);
            addKey(keys, identity.animalName, "",
                    "", identity.region);
            addKey(keys, identity.animalName, identity.conditionName,
                    identity.hemisphere, "");
            addKey(keys, identity.animalName, identity.conditionName,
                    "", "");
            addKey(keys, identity.animalName, "",
                    identity.hemisphere, "");
            addKey(keys, identity.animalName, "",
                    "", "");
            return new ArrayList<String>(keys);
        }

        private static void addKey(LinkedHashSet<String> keys,
                                   String animal,
                                   String condition,
                                   String hemisphere,
                                   String region) {
            String key = key(animal, condition, hemisphere, region);
            if (!key.isEmpty()) {
                keys.add(key);
            }
        }

        private static SeriesSeed uniqueSeed(List<SeriesSeed> seeds) {
            if (seeds == null || seeds.size() != 1) return null;
            return seeds.get(0);
        }
    }
}
