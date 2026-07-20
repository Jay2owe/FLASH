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
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
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

    /**
     * Resolves a saved result option against the project that is open now and
     * revalidates the exact numeric column before it can drive recommendations.
     */
    public static ExistingResultOption rebindExistingResult(
            String directory, ExistingResultOption saved) throws IOException {
        if (saved == null) {
            throw new IOException("Pick an existing result CSV before using Existing result mode.");
        }
        String requestedColumn = saved.columnName == null ? "" : saved.columnName.trim();
        if (requestedColumn.isEmpty()) {
            throw new IOException("Pick a numeric result column before using Existing result mode.");
        }
        if (isIdentityColumn(requestedColumn)) {
            throw new IOException("Column '" + requestedColumn
                    + "' is identity/grouping metadata, not a measurable statistic.");
        }

        FlashProjectLayout layout = FlashProjectLayout.forDirectory(directory);
        File boundFile = saved.externalImport
                ? requireExplicitExternalFile(saved.file)
                : resolveContainedResultFile(
                        layout.projectRoot(), layout.resultsRoot(), saved.relativePath);
        CsvSnapshot csv = readCsv(boundFile, -1);
        int valueColumn = csv.exactColumnIndex(requestedColumn);
        if (valueColumn < 0) {
            throw new IOException("Exact column '" + requestedColumn + "' was not found in "
                    + boundFile.getAbsolutePath());
        }
        boolean hasFiniteValue = false;
        for (List<String> row : csv.rows) {
            if (parseFiniteDouble(cell(row, valueColumn)) != null) {
                hasFiniteValue = true;
                break;
            }
        }
        if (!hasFiniteValue) {
            throw new IOException("Column '" + requestedColumn + "' in "
                    + boundFile.getAbsolutePath()
                    + " does not contain a finite numeric value.");
        }
        String relative = saved.externalImport
                ? boundFile.getAbsolutePath()
                : relativePath(layout.resultsRoot(), boundFile);
        return new ExistingResultOption(
                boundFile, csv.header.get(valueColumn), relative, saved.externalImport);
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
        option = rebindExistingResult(directory, option);
        // Defence-in-depth: a per-axis condition column (Condition_Genotype…) is grouping
        // metadata, not a statistic. Discovery already hides it; reject it on load too so a
        // stale/hand-edited saved config can never use it as the representative statistic.
        if (option.columnName.trim().regionMatches(true, 0, "Condition_", 0, "Condition_".length())) {
            throw new IOException("Column '" + option.columnName.trim()
                    + "' is a condition axis label, not a measurable statistic.");
        }

        CsvSnapshot csv = readCsv(option.file, -1);
        int valueCol = csv.exactColumnIndex(option.columnName);
        if (valueCol < 0) {
            throw new IOException("Column '" + option.columnName + "' was not found in "
                    + option.file.getAbsolutePath());
        }

        List<QcSelectionCandidate> candidates =
                QcMinMaxPerConditionSelector.buildCandidates(directory, metas);
        List<String> configuredChannels = configuredChannelNames(directory);
        String channelName = inferChannelName(option.file, option.columnName, configuredChannels);

        List<RowIdentity> rowIdentities = new ArrayList<RowIdentity>();
        LinkedHashSet<String> rowAnimals = new LinkedHashSet<String>();
        boolean requiresDurableSources = false;
        for (List<String> row : csv.rows) {
            RowIdentity identity = RowIdentity.from(csv.header, row);
            rowIdentities.add(identity);
            requiresDurableSources |= identity.hasAnyDurableKey();
            if (!identity.animalName.trim().isEmpty()) {
                rowAnimals.add(identity.animalName);
            }
        }
        LinkedHashSet<String> conditionAnimals = new LinkedHashSet<String>(rowAnimals);
        conditionAnimals.addAll(SeriesLookup.canonicalAnimals(candidates));
        Map<String, String> rowAssignments =
                ConditionManifestIO.resolveAssignments(directory, conditionAnimals);
        SeriesLookup lookup = SeriesLookup.from(
                directory, candidates, requiresDurableSources, rowAssignments);

        LinkedHashMap<String, Accumulator> accumulators =
                new LinkedHashMap<String, Accumulator>();
        for (int rowIndex = 0; rowIndex < csv.rows.size(); rowIndex++) {
            List<String> row = csv.rows.get(rowIndex);
            Double value = parseFiniteDouble(cell(row, valueCol));
            if (value == null) continue;

            RowIdentity identity = applyManifestCondition(
                    rowIdentities.get(rowIndex), rowAssignments);
            SeriesSeed seed = lookup.resolve(identity, rowIndex + 2);

            String key = seed.seriesId + "\n" + channelName;
            Accumulator accumulator = accumulators.get(key);
            if (accumulator == null) {
                accumulator = new Accumulator(seed, channelName, identity.conditionName);
                accumulators.put(key, accumulator);
            }
            accumulator.add(value.doubleValue());
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
        if (identity.conditionExplicit) {
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
        // Per-axis condition metadata (Condition_Genotype, Condition_Timepoint…) are
        // grouping labels, not measurable statistics — never offer them as result metrics.
        if (column != null
                && column.trim().regionMatches(true, 0, "Condition_", 0, "Condition_".length())) {
            return true;
        }
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

    private static File requireExplicitExternalFile(File file) throws IOException {
        if (file == null || !file.isFile()) {
            throw new IOException("Explicit external result CSV was not found: "
                    + (file == null ? "<null>" : file.getAbsolutePath()));
        }
        try {
            Path real = file.toPath().toRealPath();
            if (!Files.isRegularFile(real)) {
                throw new IOException("Explicit external result is not a regular file: "
                        + file.getAbsolutePath());
            }
            return real.toFile();
        } catch (IOException e) {
            throw new IOException("Could not resolve explicit external result CSV: "
                    + file.getAbsolutePath(), e);
        }
    }

    private static File resolveContainedResultFile(File projectRoot,
                                                   File resultsRoot,
                                                   String relativePath) throws IOException {
        String saved = relativePath == null ? "" : relativePath.trim();
        if (saved.isEmpty()) {
            throw new IOException("Saved result is missing its path relative to the current Results folder.");
        }
        String normalized = saved.replace('\\', File.separatorChar)
                .replace('/', File.separatorChar);
        final Path relative;
        try {
            relative = Paths.get(normalized);
        } catch (InvalidPathException e) {
            throw new IOException("Saved result path has invalid syntax: " + saved, e);
        }
        if (relative.isAbsolute() || startsWithSeparator(normalized)
                || looksLikeWindowsPath(normalized) || containsParentSegment(relative)) {
            throw new IOException("Saved result path must stay beneath the current Results folder: "
                    + saved);
        }
        if (projectRoot == null || !projectRoot.isDirectory()
                || resultsRoot == null || !resultsRoot.isDirectory()) {
            throw new IOException("Current project/Results folders were not found: "
                    + (projectRoot == null ? "<null>" : projectRoot.getAbsolutePath())
                    + " / "
                    + (resultsRoot == null ? "<null>" : resultsRoot.getAbsolutePath()));
        }
        final Path realProjectRoot;
        final Path realRoot;
        try {
            realProjectRoot = projectRoot.toPath().toRealPath();
            realRoot = resultsRoot.toPath().toRealPath();
        } catch (IOException e) {
            throw new IOException("Could not resolve the current project/Results folders: "
                    + (projectRoot == null ? "<null>" : projectRoot.getAbsolutePath())
                    + " / " + resultsRoot.getAbsolutePath(), e);
        }
        if (!realRoot.startsWith(realProjectRoot)) {
            throw new IOException("Current Results folder escapes the physically opened project: "
                    + resultsRoot.getAbsolutePath());
        }
        Path lexical = realRoot.resolve(relative).normalize();
        if (!lexical.startsWith(realRoot) || !Files.exists(lexical)) {
            throw new IOException("Saved result does not exist beneath the current Results folder: "
                    + saved);
        }
        final Path realCandidate;
        try {
            realCandidate = lexical.toRealPath();
        } catch (IOException e) {
            throw new IOException("Could not resolve saved result beneath current Results: "
                    + lexical, e);
        }
        if (!realCandidate.startsWith(realRoot) || !Files.isRegularFile(realCandidate)) {
            throw new IOException("Saved result escapes the current Results folder: " + saved);
        }
        return realCandidate.toFile();
    }

    private static boolean containsParentSegment(Path path) {
        for (Path part : path) {
            if ("..".equals(part.toString())) {
                return true;
            }
        }
        return false;
    }

    private static boolean startsWithSeparator(String path) {
        return path.startsWith("/") || path.startsWith("\\");
    }

    private static boolean looksLikeWindowsPath(String path) {
        return path.length() >= 2 && Character.isLetter(path.charAt(0))
                && path.charAt(1) == ':';
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

    private static String portableRelativePath(File file) {
        if (file == null) return "";
        File absolute = file.getAbsoluteFile();
        File cursor = absolute.getParentFile();
        while (cursor != null) {
            if (FlashProjectLayout.RESULTS_DIR.equalsIgnoreCase(cursor.getName())) {
                return relativePath(cursor, absolute);
            }
            cursor = cursor.getParentFile();
        }
        return file.getName();
    }

    public static final class ExistingResultOption {
        public final File file;
        public final String columnName;
        public final String relativePath;
        public final String label;
        public final boolean externalImport;

        public ExistingResultOption(File file, String columnName) {
            this(file, columnName, portableRelativePath(file), false);
        }

        public ExistingResultOption(File file, String columnName, String relativePath) {
            this(file, columnName,
                    relativePath == null || relativePath.trim().isEmpty()
                            ? portableRelativePath(file) : relativePath,
                    false);
        }

        public ExistingResultOption(File file,
                                    String columnName,
                                    String relativePath,
                                    boolean externalImport) {
            this.file = file;
            this.columnName = columnName == null ? "" : columnName.trim();
            // Four-argument construction is also the deserialization path. Preserve an
            // absent Results-relative provenance so replay fails closed instead of
            // guessing a same-named file in the currently open project.
            this.relativePath = relativePath == null ? "" : relativePath.trim();
            this.externalImport = externalImport;
            this.label = (externalImport ? "External: " : "")
                    + this.relativePath + " :: " + this.columnName;
        }

        public static ExistingResultOption externalImport(File file, String columnName) {
            return new ExistingResultOption(
                    file, columnName, file == null ? "" : file.getAbsolutePath(), true);
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

        int exactColumnIndex(String name) throws IOException {
            String expected = RepresentativeFigureConfig.normalizeIdentity(name);
            int match = -1;
            for (int i = 0; i < header.size(); i++) {
                if (!expected.equals(RepresentativeFigureConfig.normalizeIdentity(header.get(i)))) {
                    continue;
                }
                if (match >= 0) {
                    throw new IOException("Column '" + name + "' is duplicated in "
                            + (file == null ? "<unknown CSV>" : file.getAbsolutePath()));
                }
                match = i;
            }
            return match;
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
        final String sourceKey;
        final String imageKey;
        final String seriesName;
        final String animalName;
        final String conditionName;
        final String hemisphere;
        final String region;
        final int biologicalEvidenceFields;
        final boolean conditionExplicit;

        RowIdentity(int seriesIndex,
                    int seriesNumber,
                    String sourceKey,
                    String imageKey,
                    String seriesName,
                    String animalName,
                    String conditionName,
                    String hemisphere,
                    String region,
                    int biologicalEvidenceFields,
                    boolean conditionExplicit) {
            this.seriesIndex = seriesIndex;
            this.seriesNumber = seriesNumber;
            this.sourceKey = RepresentativeFigureConfig.normalizeIdentity(sourceKey);
            this.imageKey = RepresentativeFigureConfig.normalizeIdentity(imageKey);
            this.seriesName = seriesName;
            this.animalName = animalName;
            this.conditionName = conditionName;
            this.hemisphere = hemisphere;
            this.region = region;
            this.biologicalEvidenceFields = biologicalEvidenceFields;
            this.conditionExplicit = conditionExplicit;
        }

        RowIdentity withCondition(String conditionName) {
            return new RowIdentity(seriesIndex, seriesNumber, sourceKey, imageKey,
                    seriesName, animalName, conditionName, hemisphere, region,
                    biologicalEvidenceFields, conditionExplicit);
        }

        boolean hasAnyDurableKey() {
            return !sourceKey.isEmpty() || !imageKey.isEmpty();
        }

        boolean hasCompleteDurableKey() {
            return !sourceKey.isEmpty() && !imageKey.isEmpty();
        }

        int biologicalFieldCount() {
            return biologicalEvidenceFields;
        }

        static RowIdentity from(List<String> header, List<String> row) {
            LinkedHashMap<String, Integer> columns = new LinkedHashMap<String, Integer>();
            for (int i = 0; i < header.size(); i++) {
                columns.put(normalizeHeader(header.get(i)), Integer.valueOf(i));
            }

            int seriesIndex = parseInt(value(columns, row, "SeriesIndex"), -1);
            int seriesNumber = parseInt(value(columns, row, "SeriesNumber"), 0);
            String sourceKey = firstValue(columns, row,
                    "SourceKey", "SourceContainerKey", "ContainerKey");
            String imageKey = firstValue(columns, row,
                    "ImageKey", "SourceImageKey");
            String seriesName = firstValue(columns, row, "SeriesName", "ImageName", "Image", "Title");
            String animal = firstValue(columns, row, "AnimalName", "Animal Name", "Animal", "Sample");
            String condition = firstValue(columns, row, "Condition", "ConditionName");
            boolean conditionExplicit = !identityValue(condition).isEmpty();
            String hemisphere = firstValue(columns, row, "Hemisphere");
            String region = firstValue(columns, row, "Region");
            int biologicalEvidenceFields = countNonBlank(
                    seriesName, animal, condition, hemisphere, region);

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

            return new RowIdentity(seriesIndex, seriesNumber, sourceKey, imageKey, seriesName,
                    animal, condition, hemisphere, region, biologicalEvidenceFields,
                    conditionExplicit);
        }

        private static int countNonBlank(String... values) {
            int count = 0;
            if (values != null) {
                for (String value : values) {
                    if (!identityValue(value).isEmpty()) count++;
                }
            }
            return count;
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
        final String sourceKey;
        final String imageKey;
        final String seriesName;
        final String animalName;
        final String conditionName;
        final String hemisphere;
        final String region;

        SeriesSeed(String directory,
                   QcSelectionCandidate candidate,
                   File sourceFile,
                   Map<String, String> conditionAssignments) {
            NameParts parts = ImageNameParser.parse(candidate == null ? "" : candidate.seriesName);
            int index = candidate == null ? -1 : candidate.seriesIndex;
            this.seriesId = RepresentativeStatTable.seriesIdForIndex(index);
            this.seriesIndex = index;
            this.seriesNumber = candidate == null ? 0 : candidate.seriesNumber;
            this.sourceKey = RepresentativeFigureConfig.sourceKey(directory, sourceFile);
            this.imageKey = RepresentativeFigureConfig.imageKey(
                    candidate == null ? "" : candidate.seriesName);
            this.seriesName = candidate == null ? "" : candidate.seriesName;
            this.animalName = !identityValue(parts.animal).isEmpty()
                    ? parts.animal : (candidate == null ? "" : candidate.animalName);
            String assignedCondition = conditionAssignments == null
                    ? null : conditionAssignments.get(this.animalName);
            this.conditionName = assignedCondition == null || assignedCondition.trim().isEmpty()
                    ? (candidate == null ? "" : candidate.conditionName)
                    : assignedCondition.trim();
            this.hemisphere = parts.hemisphere;
            this.region = parts.csvRegion();
        }
    }

    private static final class SeriesLookup {
        final List<SeriesSeed> seeds = new ArrayList<SeriesSeed>();

        static SeriesLookup from(String directory,
                                 List<QcSelectionCandidate> candidates,
                                 boolean resolveDurableSources,
                                 Map<String, String> conditionAssignments) throws IOException {
            SeriesLookup lookup = new SeriesLookup();
            if (candidates == null) return lookup;
            DeferredImageSupplier supplier = null;
            try {
                if (resolveDurableSources) {
                    try {
                        supplier = ImageSourceDispatcher.createSupplier(directory);
                    } catch (Exception e) {
                        throw new IOException(
                                "Could not resolve current source files for durable result-row identity.", e);
                    }
                }
                for (QcSelectionCandidate candidate : candidates) {
                    File source = null;
                    if (supplier != null) {
                        try {
                            source = supplier.getContainerFileForSeries(candidate.seriesIndex);
                        } catch (RuntimeException e) {
                            throw new IOException("Could not map current series "
                                    + candidate.seriesIndex + " to its source container.", e);
                        }
                    }
                    lookup.seeds.add(new SeriesSeed(
                            directory, candidate, source, conditionAssignments));
                }
            } finally {
                if (supplier != null) {
                    supplier.shutdownPrefetch();
                }
            }
            return lookup;
        }

        static LinkedHashSet<String> canonicalAnimals(
                List<QcSelectionCandidate> candidates) {
            LinkedHashSet<String> animals = new LinkedHashSet<String>();
            if (candidates == null) return animals;
            for (QcSelectionCandidate candidate : candidates) {
                if (candidate == null) continue;
                NameParts parts = ImageNameParser.parse(candidate.seriesName);
                String animal = !identityValue(parts.animal).isEmpty()
                        ? parts.animal : candidate.animalName;
                if (!identityValue(animal).isEmpty()) {
                    animals.add(animal);
                }
            }
            return animals;
        }

        SeriesSeed resolve(RowIdentity identity, int csvRowNumber) throws IOException {
            if (identity == null) {
                throw rowIdentityError(csvRowNumber, "identity is missing");
            }
            if (identity.hasAnyDurableKey() && !identity.hasCompleteDurableKey()) {
                throw rowIdentityError(csvRowNumber,
                        "durable identity must contain both SourceKey and ImageKey");
            }
            if (!identity.hasCompleteDurableKey() && identity.biologicalFieldCount() < 2) {
                throw rowIdentityError(csvRowNumber,
                        "at least two biological identity fields are required for legacy migration");
            }
            int hint = numericHint(identity, csvRowNumber);
            List<SeriesSeed> matches = new ArrayList<SeriesSeed>();
            for (SeriesSeed seed : seeds) {
                if (identity.hasCompleteDurableKey()
                        && (!identity.sourceKey.equals(seed.sourceKey)
                        || !identity.imageKey.equals(seed.imageKey))) {
                    continue;
                }
                if (!identityFieldMatches(identity.seriesName, seed.seriesName)
                        || !identityFieldMatches(identity.animalName, seed.animalName)
                        || !identityFieldMatches(identity.conditionName, seed.conditionName)
                        || !identityFieldMatches(identity.hemisphere, seed.hemisphere)
                        || !identityFieldMatches(identity.region, seed.region)) {
                    continue;
                }
                matches.add(seed);
            }
            if (matches.isEmpty()) {
                throw rowIdentityError(csvRowNumber,
                        "no current source series matches all supplied identity fields");
            }
            if (matches.size() > 1) {
                throw rowIdentityError(csvRowNumber,
                        "identity is ambiguous across " + matches.size() + " current series");
            }
            SeriesSeed resolved = matches.get(0);
            if (!identity.hasCompleteDurableKey()
                    && hint >= 0 && hint != resolved.seriesIndex) {
                throw rowIdentityError(csvRowNumber,
                        "legacy series index hint " + hint + " conflicts with animal/image identity "
                                + "for current series " + resolved.seriesIndex);
            }
            return resolved;
        }

        private static int numericHint(RowIdentity identity,
                                       int csvRowNumber) throws IOException {
            int fromIndex = identity.seriesIndex;
            int fromNumber = identity.seriesNumber > 0
                    ? identity.seriesNumber - 1 : -1;
            if (fromIndex >= 0 && fromNumber >= 0 && fromIndex != fromNumber) {
                throw rowIdentityError(csvRowNumber,
                        "SeriesIndex and SeriesNumber disagree");
            }
            return fromIndex >= 0 ? fromIndex : fromNumber;
        }

        private static IOException rowIdentityError(int row, String detail) {
            return new IOException("Existing result row " + row
                    + " has unsafe or stale source identity: " + detail + ".");
        }
    }

    private static boolean identityFieldMatches(String supplied, String current) {
        String expected = identityValue(supplied);
        return expected.isEmpty() || expected.equals(identityValue(current));
    }

    private static String identityValue(String value) {
        return RepresentativeFigureConfig.normalizeIdentity(
                cleanSpreadsheetPrefix(value));
    }
}
