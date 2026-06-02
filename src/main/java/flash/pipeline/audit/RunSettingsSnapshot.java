package flash.pipeline.audit;

import flash.pipeline.bin.BinConfig;
import flash.pipeline.bin.BinConfigIO;
import flash.pipeline.bin.BinField;
import flash.pipeline.cli.CLIConfig;
import flash.pipeline.intelligence.AnalysisStatusScanner;
import flash.pipeline.io.FlashProjectLayout;
import flash.pipeline.segmentation.SegmentationMethod;
import flash.pipeline.segmentation.SegmentationTokenParser;
import flash.pipeline.segmentation.catalog.ModelCatalog;
import flash.pipeline.segmentation.catalog.ModelCatalogIO;
import flash.pipeline.segmentation.catalog.ModelEntry;
import flash.pipeline.ui.wizard.JsonIO;
import flash.pipeline.zslice.ZSliceMode;
import flash.pipeline.zslice.ZSliceRange;
import flash.pipeline.zslice.ZSliceSelection;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;

/** Writes the per-run FLASH settings snapshot and replay macro line. */
public final class RunSettingsSnapshot {
    public static final String SETTINGS_EXTENSION = ".json";
    public static final String REPLAY_EXTENSION = ".txt";

    public final String flashVersion;
    public final String timestamp;
    public final String analysis;
    public final int analysisIndex;
    public final String directory;
    public final BinConfig binConfig;
    public final Map<String, String> fieldSources;
    public final String replayCommand;

    private RunSettingsSnapshot(String flashVersion,
                                String timestamp,
                                String analysis,
                                int analysisIndex,
                                String directory,
                                BinConfig binConfig,
                                Map<String, String> fieldSources,
                                String replayCommand) {
        this.flashVersion = flashVersion;
        this.timestamp = timestamp;
        this.analysis = analysis;
        this.analysisIndex = analysisIndex;
        this.directory = directory;
        this.binConfig = binConfig == null ? new BinConfig() : binConfig;
        this.fieldSources = fieldSources == null
                ? new LinkedHashMap<String, String>()
                : new LinkedHashMap<String, String>(fieldSources);
        this.replayCommand = replayCommand;
    }

    public static RunSettingsSnapshot create(String directory,
                                             String analysisName,
                                             int analysisIndex,
                                             Set<BinField> requiredFields,
                                             Map<BinField, String> observedSources,
                                             CLIConfig cliConfig) {
        BinConfig cfg = BinConfigIO.readPartialFromDirectory(directory);
        Map<String, String> sources = fieldSources(cfg, requiredFields, observedSources);
        String replay = ReplayCommandFormatter.format(directory, analysisIndex, cfg);
        return new RunSettingsSnapshot(
                flashVersion(),
                Instant.now().toString(),
                analysisName,
                analysisIndex,
                absolutePath(directory),
                cfg,
                sources,
                replay);
    }

    public static void writeForAnalysis(String directory,
                                        String analysisName,
                                        int analysisIndex,
                                        Set<BinField> requiredFields,
                                        Map<BinField, String> observedSources,
                                        CLIConfig cliConfig) throws IOException {
        RunSettingsSnapshot snapshot = create(directory, analysisName, analysisIndex,
                requiredFields, observedSources, cliConfig);
        FlashProjectLayout layout = FlashProjectLayout.forDirectory(directory);
        String baseName = safeRecordName(analysisIndex, analysisName);

        File snapshotsDir = layout.settingsSnapshotsWriteDir();
        ensureDirectory(snapshotsDir);
        BinConfigIO.writeAtomic(new File(snapshotsDir, baseName + SETTINGS_EXTENSION).toPath(),
                Arrays.asList(trimTrailingNewline(snapshot.toJson())));

        File replayDir = layout.replayCommandsWriteDir();
        ensureDirectory(replayDir);
        BinConfigIO.writeAtomic(new File(replayDir, baseName + REPLAY_EXTENSION).toPath(),
                Arrays.asList(snapshot.replayCommand));

        AnalysisStatusScanner.appendRunHistory(new File(directory),
                analysisIndex, analysisName, snapshot.timestamp);
    }

    public String toJson() {
        return JsonIO.write(toJsonObject()) + "\n";
    }

    public static RunSettingsSnapshot fromJson(String json) throws IOException {
        Map<String, Object> root = JsonIO.parseObject(json);
        BinConfig cfg = binConfigFromJson(JsonIO.asObject(root.get("bin_config")));
        Map<String, String> sources = stringMap(JsonIO.asObject(root.get("field_sources")));
        return new RunSettingsSnapshot(
                JsonIO.stringValue(root.get("flash_version")),
                JsonIO.stringValue(root.get("timestamp")),
                JsonIO.stringValue(root.get("analysis")),
                JsonIO.intValue(root.get("analysis_index"), -1),
                JsonIO.stringValue(root.get("directory")),
                cfg,
                sources,
                JsonIO.stringValue(root.get("replay_command")));
    }

    private Map<String, Object> toJsonObject() {
        Map<String, Object> root = JsonIO.object();
        root.put("flash_version", flashVersion);
        root.put("timestamp", timestamp);
        root.put("analysis", analysis);
        root.put("analysis_index", Integer.valueOf(analysisIndex));
        root.put("directory", directory);
        root.put("bin_config", binConfigToJson(binConfig));
        root.put("segmentation_models", segmentationModelsToJson(binConfig, directory));
        root.put("field_sources", new LinkedHashMap<String, String>(fieldSources));
        root.put("replay_command", replayCommand);
        return root;
    }

    private static void ensureDirectory(File dir) throws IOException {
        if (dir == null) return;
        if (!dir.isDirectory() && !dir.mkdirs() && !dir.isDirectory()) {
            throw new IOException("Could not create run-records folder: " + dir.getAbsolutePath());
        }
    }

    private static String trimTrailingNewline(String value) {
        if (value == null) return "";
        int end = value.length();
        while (end > 0) {
            char ch = value.charAt(end - 1);
            if (ch != '\n' && ch != '\r') break;
            end--;
        }
        return value.substring(0, end);
    }

    public static String safeRecordName(int analysisIndex, String analysisName) {
        String name = analysisName == null ? "analysis" : analysisName.trim();
        if (name.isEmpty()) name = "analysis";
        name = name.replaceAll("[^a-zA-Z0-9._ -]", "_")
                .replaceAll("\\s+", " ")
                .trim();
        if (name.isEmpty()) name = "analysis";
        return name;
    }

    private static Map<String, String> fieldSources(BinConfig cfg,
                                                    Set<BinField> requiredFields,
                                                    Map<BinField, String> observedSources) {
        EnumMap<BinField, String> sourceMap = new EnumMap<BinField, String>(BinField.class);
        if (observedSources != null) {
            sourceMap.putAll(observedSources);
        }
        Map<String, String> out = new LinkedHashMap<String, String>();
        if (requiredFields == null || requiredFields.isEmpty()) return out;
        for (BinField field : requiredFields) {
            String source = sourceMap.get(field);
            if (source == null && hasField(cfg, field)) {
                source = "loaded";
            }
            if (source == null) {
                source = "missing";
            }
            out.put(jsonKey(field), source);
        }
        return out;
    }

    private static boolean hasField(BinConfig cfg, BinField field) {
        if (cfg == null || field == null) return false;
        switch (field) {
            case CHANNEL_NAMES: return cfg.hasChannelNames();
            case CHANNEL_COLORS: return cfg.hasChannelColors();
            case OBJECT_THRESHOLDS: return cfg.hasChannelThresholds();
            case PARTICLE_SIZES: return cfg.hasChannelSizes();
            case DISPLAY_MIN_MAX: return cfg.hasChannelMinMax();
            case INTENSITY_THRESHOLDS: return cfg.hasChannelIntensityThresholds();
            case SEGMENTATION_METHODS: return cfg.hasSegmentationMethods();
            case FILTER_PRESETS: return cfg.hasChannelFilterPresets();
            case Z_SLICE: return cfg.hasZSliceConfig();
            default: return false;
        }
    }

    public static String jsonKey(BinField field) {
        if (field == null) return "";
        switch (field) {
            case CHANNEL_NAMES: return "channel_names";
            case CHANNEL_COLORS: return "channel_colors";
            case OBJECT_THRESHOLDS: return "object_thresholds";
            case PARTICLE_SIZES: return "particle_sizes";
            case DISPLAY_MIN_MAX: return "display_min_max";
            case INTENSITY_THRESHOLDS: return "intensity_thresholds";
            case SEGMENTATION_METHODS: return "segmentation_methods";
            case FILTER_PRESETS: return "filter_presets";
            case Z_SLICE: return "z_slice_mode";
            default: return "";
        }
    }

    private static Map<String, Object> binConfigToJson(BinConfig cfg) {
        BinConfig safe = cfg == null ? new BinConfig() : cfg;
        Map<String, Object> bin = JsonIO.object();
        bin.put("channel_names", copyList(safe.channelNames));
        bin.put("channel_colors", copyList(safe.channelColors));
        bin.put("object_thresholds", copyList(safe.channelThresholds));
        bin.put("particle_sizes", copyList(safe.channelSizes));
        bin.put("display_min_max", copyList(safe.channelMinMax));
        bin.put("intensity_thresholds", copyList(safe.channelIntensityThresholds));
        bin.put("segmentation_methods", copyList(safe.segmentationMethods));
        bin.put("filter_presets", copyList(safe.channelFilterPresets));
        bin.put("z_slice_mode", ReplayCommandFormatter.zSliceCliToken(safe));
        bin.put("z_slice_config_present", Boolean.valueOf(safe.zSliceConfigPresent));
        bin.put("z_slice_selections", zSliceSelectionsToJson(safe));
        return bin;
    }

    private static BinConfig binConfigFromJson(Map<String, Object> bin) {
        BinConfig cfg = new BinConfig();
        addStrings(cfg.channelNames, JsonIO.asList(bin.get("channel_names")));
        addStrings(cfg.channelColors, JsonIO.asList(bin.get("channel_colors")));
        addStrings(cfg.channelThresholds, JsonIO.asList(bin.get("object_thresholds")));
        addStrings(cfg.channelSizes, JsonIO.asList(bin.get("particle_sizes")));
        addStrings(cfg.channelMinMax, JsonIO.asList(bin.get("display_min_max")));
        addStrings(cfg.channelIntensityThresholds, JsonIO.asList(bin.get("intensity_thresholds")));
        addStrings(cfg.segmentationMethods, JsonIO.asList(bin.get("segmentation_methods")));
        addStrings(cfg.channelFilterPresets, JsonIO.asList(bin.get("filter_presets")));
        cfg.zSliceMode = ZSliceMode.fromConfigToken(JsonIO.stringValue(bin.get("z_slice_mode")));
        cfg.zSliceConfigPresent = JsonIO.booleanValue(bin.get("z_slice_config_present"), false);
        for (Object obj : JsonIO.asList(bin.get("z_slice_selections"))) {
            Map<String, Object> row = JsonIO.asObject(obj);
            int seriesIndex = JsonIO.intValue(row.get("series_index"), -1);
            int totalSlices = JsonIO.intValue(row.get("total_slices"), 0);
            int start = JsonIO.intValue(row.get("start_slice"), 0);
            int end = JsonIO.intValue(row.get("end_slice"), 0);
            if (seriesIndex >= 0 && start > 0 && end >= start) {
                cfg.zSliceSelections.put(Integer.valueOf(seriesIndex),
                        new ZSliceSelection(seriesIndex,
                                JsonIO.stringValue(row.get("series_name")),
                                totalSlices,
                                new ZSliceRange(start, end)));
            }
        }
        return cfg;
    }

    private static List<Object> segmentationModelsToJson(BinConfig cfg, String directory) {
        List<Object> rows = new ArrayList<Object>();
        BinConfig safe = cfg == null ? new BinConfig() : cfg;
        ModelCatalog catalog = readModelCatalog(directory);
        int count = safe.segmentationMethods == null ? 0 : safe.segmentationMethods.size();
        for (int i = 0; i < count; i++) {
            rows.add(segmentationModelRow(i, safe.segmentationMethods.get(i), catalog));
        }
        return rows;
    }

    private static Map<String, Object> segmentationModelRow(int channelIndex,
                                                            String methodToken,
                                                            ModelCatalog catalog) {
        Map<String, Object> row = JsonIO.object();
        String raw = methodToken == null ? "" : methodToken;
        SegmentationMethod method = SegmentationTokenParser.parseLenient(raw);
        row.put("channel_index", Integer.valueOf(channelIndex));
        row.put("method", raw);
        row.put("engine", snapshotEngine(method));

        String modelKey = modelKey(method);
        if (!modelKey.isEmpty()) {
            row.put("model_key", modelKey);
            Optional<ModelEntry> found = resolveModel(catalog, method, modelKey);
            if (found.isPresent()) {
                ModelEntry entry = found.get();
                row.put("display_name", displayName(entry));
                row.put("source_type", sourceType(entry));
            } else {
                row.put("source_type", "unknown");
            }
        } else {
            row.put("source_type", "unknown");
        }
        return row;
    }

    private static ModelCatalog readModelCatalog(String directory) {
        if (directory == null || directory.trim().isEmpty()) {
            return null;
        }
        try {
            return ModelCatalogIO.read(new File(directory).toPath().toAbsolutePath().normalize());
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static Optional<ModelEntry> resolveModel(ModelCatalog catalog,
                                                     SegmentationMethod method,
                                                     String modelKey) {
        if (catalog == null || method == null || modelKey == null || modelKey.trim().isEmpty()) {
            return Optional.empty();
        }
        Optional<ModelEntry> found = catalog.get(modelKey.trim());
        if (!found.isPresent()) {
            return Optional.empty();
        }
        ModelEntry entry = found.get();
        if (method.isStarDist() && entry.engine != ModelEntry.Engine.STARDIST) {
            return Optional.empty();
        }
        if (method.isCellpose() && entry.engine != ModelEntry.Engine.CELLPOSE) {
            return Optional.empty();
        }
        if (method.isTrainedRf() && entry.engine != ModelEntry.Engine.SMILE_RF) {
            return Optional.empty();
        }
        return Optional.of(entry);
    }

    private static String modelKey(SegmentationMethod method) {
        if (method == null) {
            return "";
        }
        if (method.isStarDist()) {
            return safe(SegmentationMethod.starDistModelKey(method));
        }
        if (method.isCellpose()) {
            return safe(SegmentationMethod.cellposeModelKey(method));
        }
        if (method.isTrainedRf()) {
            return safe(SegmentationMethod.trainedRfModelKey(method));
        }
        return "";
    }

    private static String snapshotEngine(SegmentationMethod method) {
        if (method == null || method.isClassical()) {
            return "classical";
        }
        if (method.isEnhancedClassical()) {
            return "classical_enhanced";
        }
        if (method.isStarDist()) {
            return "stardist";
        }
        if (method.isCellpose()) {
            return "cellpose";
        }
        if (method.isTrainedRf()) {
            return "trained_rf";
        }
        return "classical";
    }

    private static String displayName(ModelEntry entry) {
        if (entry == null) {
            return null;
        }
        String name = safe(entry.name);
        return name.isEmpty() ? safe(entry.modelKey) : name;
    }

    private static String sourceType(ModelEntry entry) {
        if (entry == null || entry.source == null) {
            return "unknown";
        }
        if (entry.source == ModelEntry.Source.STOCK_BUILTIN) {
            return "fiji_builtin";
        }
        if (entry.source == ModelEntry.Source.STOCK_RESOURCE) {
            return "stock";
        }
        if (entry.source == ModelEntry.Source.USER_IMPORTED
                || entry.source == ModelEntry.Source.USER_TRAINED) {
            return "user";
        }
        return "unknown";
    }

    private static List<Object> zSliceSelectionsToJson(BinConfig cfg) {
        List<Object> rows = new ArrayList<Object>();
        for (Map.Entry<Integer, ZSliceSelection> entry : cfg.zSliceSelections.entrySet()) {
            ZSliceSelection selection = entry.getValue();
            if (selection == null || selection.range == null) continue;
            Map<String, Object> row = JsonIO.object();
            row.put("series_index", Integer.valueOf(selection.seriesIndex));
            row.put("series_name", selection.seriesName);
            row.put("total_slices", Integer.valueOf(selection.totalSlices));
            row.put("start_slice", Integer.valueOf(selection.range.startSlice));
            row.put("end_slice", Integer.valueOf(selection.range.endSlice));
            rows.add(row);
        }
        return rows;
    }

    private static List<String> copyList(List<String> values) {
        return values == null ? new ArrayList<String>() : new ArrayList<String>(values);
    }

    private static void addStrings(List<String> target, List<Object> values) {
        for (Object value : values) {
            target.add(JsonIO.stringValue(value));
        }
    }

    private static Map<String, String> stringMap(Map<String, Object> raw) {
        Map<String, String> out = new LinkedHashMap<String, String>();
        for (Map.Entry<String, Object> entry : raw.entrySet()) {
            out.put(entry.getKey(), JsonIO.stringValue(entry.getValue()));
        }
        return out;
    }

    /** Shared FLASH version lookup; reused by {@code flash.pipeline.runrecord.EnvironmentSnapshot}. */
    public static String flashVersion() {
        Package pkg = RunSettingsSnapshot.class.getPackage();
        String version = pkg == null ? null : pkg.getImplementationVersion();
        if (version != null && !version.trim().isEmpty()) {
            return version.trim();
        }
        InputStream in = RunSettingsSnapshot.class.getResourceAsStream(
                "/META-INF/maven/io.github.jay2owe/flash/pom.properties");
        if (in != null) {
            try {
                Properties props = new Properties();
                props.load(in);
                version = props.getProperty("version");
                if (version != null && !version.trim().isEmpty()) {
                    return version.trim();
                }
            } catch (IOException ignored) {
                // Fall through to an explicit unknown marker.
            } finally {
                try {
                    in.close();
                } catch (IOException ignored) {
                    // Nothing useful to do while writing audit metadata.
                }
            }
        }
        return "unknown";
    }

    private static String absolutePath(String directory) {
        return directory == null ? "" : new File(directory).getAbsolutePath();
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
