package flash.pipeline.runrecord;

import flash.pipeline.analyses.wizard.BinPreset;
import flash.pipeline.analyses.wizard.IntensityPreset;
import flash.pipeline.analyses.wizard.SpatialPreset;
import flash.pipeline.analyses.wizard.ThreeDObjectPreset;
import flash.pipeline.bin.BinConfig;
import flash.pipeline.decontamination.wizard.SpectralDecontamPreset;
import flash.pipeline.deconv.wizard.DeconvPreset;
import flash.pipeline.ui.config.ParticleSizeStage;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Shared helpers for turning a run-record parameter map back into the existing
 * dialog DTOs/presets. This intentionally stays shallow: unknown keys are
 * reported and ignored, while each caller still applies the resulting domain
 * object through its normal preset/config path.
 */
public final class LoadedRunParameters {

    private static final ThreadLocal<Result> LAST_RESULT = new ThreadLocal<Result>();

    public static final Set<String> THREE_D_OBJECT_KEYS = keys(
            "name", "description", "libraryVersion",
            "doVolumetric", "doCpc", "doIntensityColoc",
            "extractProcessLength", "runSpatial", "classicalCentroidFiltering",
            "colocThresholdPercent", "processMarkerHints", "nuclearMarkerHints");

    public static final Set<String> SPATIAL_KEYS = keys(
            "name", "description", "libraryVersion",
            "doDistances", "doSpatialStats", "doVolColoc", "doCpc", "doVoronoi",
            "doHeatmaps", "doPhenotyping", "do2DMorphology", "do3DMorphology",
            "doCompositeIndices", "doPopMorphometrics", "doSpatialMorphometrics",
            "doObjectGLCM", "doObjectFractal", "doObjectTextureClass",
            "doObjectTextureClassFractions", "doNative3DTexture",
            "textureClassK", "kdeBandwidth", "heatmapLut", "clusterK",
            "colocThresholdPercent", "forceRerun");

    public static final Set<String> INTENSITY_KEYS = keys(
            "name", "description", "libraryVersion", "strategy", "defaultMode",
            "channelModes", "thresholds", "maskChannelHint", "roiSetNameHints",
            "spatial");

    public static final Set<String> DECONV_KEYS = keys(
            "name", "description", "engineKey", "algorithm", "psfModel",
            "iterations", "regularization", "scopeModality", "pinholeAU",
            "sampleRI");

    public static final Set<String> SPECTRAL_KEYS = keys(
            "name", "description", "libraryVersion", "contaminationType",
            "calibration", "strength", "goal", "targetChannelIndex",
            "bleedThroughChannelIndexes", "autofluorescenceChannelIndexes",
            "excludedChannelIndexes", "controlConditionNames",
            "experimentalConditionNames", "correctionPresetId", "expertMode",
            "correctionFeatureIds", "featureSettings");

    public static final Set<String> BIN_KEYS = keys(
            "name", "description", "libraryVersion", "zSliceMode", "channels",
            "bin_preset", "channel_names", "channel_colors", "object_thresholds",
            "particle_sizes", "display_min_max", "intensity_thresholds",
            "segmentation_methods", "filter_presets", "z_slice_mode");

    public static final Set<String> SETUP_STAGE_KEYS = keys(
            "channels", "object_thresholds", "particle_sizes",
            "segmentation_methods", "filter_presets");

    public static final Set<String> LINE_DISTANCE_KEYS = keys(
            "line_sets", "draw_new", "landmark", "custom_name", "draw_on_subset");

    private LoadedRunParameters() {
    }

    public static void rememberLastResult(Result result) {
        LAST_RESULT.set(result == null ? Result.empty() : result);
    }

    public static Result consumeLastResult() {
        Result result = LAST_RESULT.get();
        LAST_RESULT.remove();
        return result;
    }

    public static PresetLoad<ThreeDObjectPreset> threeDObjectPreset(Map<String, Object> parameters) {
        Map<String, Object> map = recognizedMap(parameters, THREE_D_OBJECT_KEYS);
        putDefault(map, "name", "Loaded 3D Object run");
        putDefault(map, "libraryVersion", ThreeDObjectPreset.CURRENT_LIBRARY_VERSION);
        try {
            return new PresetLoad<ThreeDObjectPreset>(
                    ThreeDObjectPreset.fromJsonObject(map),
                    resultForKnownKeys(parameters, THREE_D_OBJECT_KEYS));
        } catch (IOException e) {
            return new PresetLoad<ThreeDObjectPreset>(
                    defaultThreeDObjectPreset(),
                    resultForKnownKeys(parameters, THREE_D_OBJECT_KEYS));
        }
    }

    public static PresetLoad<SpatialPreset> spatialPreset(Map<String, Object> parameters) {
        Map<String, Object> map = recognizedMap(parameters, SPATIAL_KEYS);
        putDefault(map, "name", "Loaded Spatial run");
        putDefault(map, "libraryVersion", SpatialPreset.CURRENT_LIBRARY_VERSION);
        try {
            return new PresetLoad<SpatialPreset>(
                    SpatialPreset.fromJsonObject(map),
                    resultForKnownKeys(parameters, SPATIAL_KEYS));
        } catch (IOException e) {
            return new PresetLoad<SpatialPreset>(
                    defaultSpatialPreset(),
                    resultForKnownKeys(parameters, SPATIAL_KEYS));
        }
    }

    public static PresetLoad<IntensityPreset> intensityPreset(Map<String, Object> parameters) {
        Map<String, Object> map = recognizedMap(parameters, INTENSITY_KEYS);
        putDefault(map, "name", "Loaded Intensity run");
        putDefault(map, "libraryVersion", IntensityPreset.CURRENT_LIBRARY_VERSION);
        try {
            return new PresetLoad<IntensityPreset>(
                    IntensityPreset.fromJsonObject(map),
                    resultForKnownKeys(parameters, INTENSITY_KEYS));
        } catch (IOException e) {
            try {
                return new PresetLoad<IntensityPreset>(
                        IntensityPreset.fromJsonObject(defaultIntensityMap()),
                        resultForKnownKeys(parameters, INTENSITY_KEYS));
            } catch (IOException impossible) {
                throw new IllegalStateException(impossible);
            }
        }
    }

    public static PresetLoad<DeconvPreset> deconvPreset(Map<String, Object> parameters) {
        Map<String, Object> map = recognizedMap(parameters, DECONV_KEYS);
        putDefault(map, "name", "Loaded Deconvolution run");
        try {
            return new PresetLoad<DeconvPreset>(
                    DeconvPreset.fromJsonObject(map),
                    resultForKnownKeys(parameters, DECONV_KEYS));
        } catch (IOException e) {
            try {
                return new PresetLoad<DeconvPreset>(
                        DeconvPreset.fromJsonObject(defaultDeconvMap()),
                        resultForKnownKeys(parameters, DECONV_KEYS));
            } catch (IOException impossible) {
                throw new IllegalStateException(impossible);
            }
        }
    }

    public static PresetLoad<SpectralDecontamPreset> spectralPreset(Map<String, Object> parameters) {
        Map<String, Object> map = recognizedMap(parameters, SPECTRAL_KEYS);
        putDefault(map, "name", "Loaded Spectral run");
        putDefault(map, "libraryVersion", SpectralDecontamPreset.CURRENT_LIBRARY_VERSION);
        try {
            return new PresetLoad<SpectralDecontamPreset>(
                    SpectralDecontamPreset.fromJsonObject(map),
                    resultForKnownKeys(parameters, SPECTRAL_KEYS));
        } catch (IOException e) {
            try {
                return new PresetLoad<SpectralDecontamPreset>(
                        SpectralDecontamPreset.fromJsonObject(defaultSpectralMap()),
                        resultForKnownKeys(parameters, SPECTRAL_KEYS));
            } catch (IOException impossible) {
                throw new IllegalStateException(impossible);
            }
        }
    }

    public static PresetLoad<BinPreset> binPreset(Map<String, Object> parameters) {
        Map<String, Object> map;
        if (parameters != null && parameters.containsKey("channels")) {
            map = recognizedMap(parameters, BIN_KEYS);
        } else {
            map = flatBinMap(parameters);
        }
        putDefault(map, "name", "Loaded Channel Configuration run");
        putDefault(map, "libraryVersion", BinPreset.CURRENT_LIBRARY_VERSION);
        try {
            return new PresetLoad<BinPreset>(
                    BinPreset.fromJsonObject(map),
                    resultForKnownKeys(parameters, BIN_KEYS));
        } catch (IOException e) {
            try {
                return new PresetLoad<BinPreset>(
                        BinPreset.fromJsonObject(defaultBinMap()),
                        resultForKnownKeys(parameters, BIN_KEYS));
            } catch (IOException impossible) {
                throw new IllegalStateException(impossible);
            }
        }
    }

    public static PresetLoad<BinConfig> binConfig(Map<String, Object> parameters) {
        PresetLoad<BinPreset> preset = binPreset(parameters);
        return new PresetLoad<BinConfig>(preset.payload.getPayload(), preset.result);
    }

    public static ValueLoad<String> objectThreshold(Map<String, Object> parameters, int channelIndex) {
        return new ValueLoad<String>(
                channelValue(parameters, "object_thresholds", "objectThreshold", channelIndex, null),
                resultForKnownKeys(parameters, SETUP_STAGE_KEYS));
    }

    public static ValueLoad<ParticleSizeStage.SizeToken> particleSize(Map<String, Object> parameters,
                                                                      int channelIndex) {
        String token = channelValue(parameters, "particle_sizes", "particleSize", channelIndex, null);
        return new ValueLoad<ParticleSizeStage.SizeToken>(
                token == null ? null : parseParticleSizeToken(token),
                resultForKnownKeys(parameters, SETUP_STAGE_KEYS));
    }

    public static ValueLoad<String> segmentationMethod(Map<String, Object> parameters, int channelIndex) {
        return new ValueLoad<String>(
                channelValue(parameters, "segmentation_methods", "segmentationMethod", channelIndex, null),
                resultForKnownKeys(parameters, SETUP_STAGE_KEYS));
    }

    public static ValueLoad<String> filterPreset(Map<String, Object> parameters, int channelIndex) {
        return new ValueLoad<String>(
                channelValue(parameters, "filter_presets", "filterPreset", channelIndex, null),
                resultForKnownKeys(parameters, SETUP_STAGE_KEYS));
    }

    public static Result resultForKnownKeys(Map<String, Object> parameters, Set<String> knownKeys) {
        List<String> applied = new ArrayList<String>();
        List<String> ignored = new ArrayList<String>();
        if (parameters != null) {
            for (String key : parameters.keySet()) {
                if (knownKeys != null && knownKeys.contains(key)) {
                    applied.add(key);
                } else {
                    ignored.add(key);
                }
            }
        }
        return new Result(applied, ignored);
    }

    /**
     * Parses a Line Distance run's stored parameters into the dialog selections
     * it should restore. {@code line_sets} accepts either a {@code List} (as the
     * GUI capture writes) or a single comma-separated {@code String} (as
     * {@code LineDistanceAnalysisCommand} writes), so both record shapes load.
     * Unknown/missing keys default safely and never throw.
     */
    public static ValueLoad<LineDistanceSelections> lineDistanceSelections(Map<String, Object> parameters) {
        List<String> sets = stringListOrCsv(parameters == null ? null : parameters.get("line_sets"));
        boolean drawNew = booleanValue(parameters == null ? null : parameters.get("draw_new"), false);
        boolean drawOnSubset = booleanValue(parameters == null ? null : parameters.get("draw_on_subset"), false);
        String landmark = trimmedOrNull(parameters == null ? null : parameters.get("landmark"));
        String customName = trimmedOrNull(parameters == null ? null : parameters.get("custom_name"));
        LineDistanceSelections value = new LineDistanceSelections(
                sets, drawNew, landmark, customName, drawOnSubset);
        return new ValueLoad<LineDistanceSelections>(value,
                resultForKnownKeys(parameters, LINE_DISTANCE_KEYS));
    }

    private static List<String> stringListOrCsv(Object value) {
        List<String> out = new ArrayList<String>();
        if (value == null) {
            return out;
        }
        if (value instanceof List<?> || value.getClass().isArray()) {
            for (Object item : asList(value)) {
                if (item == null) continue;
                String token = String.valueOf(item).trim();
                if (!token.isEmpty()) out.add(token);
            }
            return out;
        }
        for (String part : String.valueOf(value).split(",")) {
            String token = part == null ? "" : part.trim();
            if (!token.isEmpty()) out.add(token);
        }
        return out;
    }

    private static boolean booleanValue(Object value, boolean fallback) {
        if (value instanceof Boolean) {
            return ((Boolean) value).booleanValue();
        }
        if (value == null) {
            return fallback;
        }
        String text = String.valueOf(value).trim();
        if (text.equalsIgnoreCase("true")) return true;
        if (text.equalsIgnoreCase("false")) return false;
        return fallback;
    }

    private static String trimmedOrNull(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    public static Set<String> keys(String... names) {
        LinkedHashSet<String> out = new LinkedHashSet<String>();
        if (names != null) {
            out.addAll(Arrays.asList(names));
        }
        return Collections.unmodifiableSet(out);
    }

    private static Map<String, Object> recognizedMap(Map<String, Object> parameters, Set<String> keys) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        if (parameters == null || keys == null) {
            return out;
        }
        for (String key : keys) {
            if (parameters.containsKey(key)) {
                out.put(key, parameters.get(key));
            }
        }
        return out;
    }

    private static Map<String, Object> flatBinMap(Map<String, Object> parameters) {
        Map<String, Object> root = new LinkedHashMap<String, Object>();
        if (parameters == null) {
            root.put("channels", Collections.emptyList());
            return root;
        }
        Object zSlice = parameters.containsKey("z_slice_mode")
                ? parameters.get("z_slice_mode")
                : parameters.get("zSliceMode");
        if (zSlice != null) {
            root.put("zSliceMode", String.valueOf(zSlice));
        }
        List<Object> names = asList(parameters.get("channel_names"));
        List<Object> colors = asList(parameters.get("channel_colors"));
        List<Object> thresholds = asList(parameters.get("object_thresholds"));
        List<Object> sizes = asList(parameters.get("particle_sizes"));
        List<Object> ranges = asList(parameters.get("display_min_max"));
        List<Object> intensity = asList(parameters.get("intensity_thresholds"));
        List<Object> segmentation = asList(parameters.get("segmentation_methods"));
        List<Object> filters = asList(parameters.get("filter_presets"));
        int count = maxSize(names, colors, thresholds, sizes, ranges, intensity, segmentation, filters);
        List<Object> channels = new ArrayList<Object>();
        for (int i = 0; i < count; i++) {
            Map<String, Object> channel = new LinkedHashMap<String, Object>();
            channel.put("name", valueAt(names, i, "Channel" + (i + 1)));
            channel.put("color", valueAt(colors, i, "Grays"));
            channel.put("objectThreshold", valueAt(thresholds, i, "default"));
            channel.put("particleSize", valueAt(sizes, i, "100-Infinity"));
            channel.put("displayRange", valueAt(ranges, i, "None"));
            channel.put("intensityThreshold", valueAt(intensity, i, "default"));
            channel.put("segmentationMethod", valueAt(segmentation, i, "classical"));
            channel.put("filterPreset", valueAt(filters, i, "Default"));
            channels.add(channel);
        }
        root.put("channels", channels);
        return root;
    }

    private static String channelValue(Map<String, Object> parameters, String flatKey,
                                       String channelKey, int channelIndex,
                                       String fallback) {
        if (parameters == null) {
            return fallback;
        }
        List<Object> values = asList(parameters.get(flatKey));
        if (!values.isEmpty()) {
            Object value = valueAt(values, channelIndex, fallback);
            return value == null ? fallback : String.valueOf(value);
        }
        List<Object> channels = asList(parameters.get("channels"));
        if (channelIndex >= 0 && channelIndex < channels.size()) {
            Object channel = channels.get(channelIndex);
            if (channel instanceof Map<?, ?>) {
                @SuppressWarnings("unchecked")
                Map<String, Object> map = (Map<String, Object>) channel;
                Object value = map.get(channelKey);
                return value == null ? fallback : String.valueOf(value);
            }
        }
        return fallback;
    }

    private static List<Object> asList(Object value) {
        if (value == null) {
            return Collections.emptyList();
        }
        if (value instanceof List<?>) {
            List<Object> out = new ArrayList<Object>();
            out.addAll((List<?>) value);
            return out;
        }
        if (value.getClass().isArray()) {
            int n = java.lang.reflect.Array.getLength(value);
            List<Object> out = new ArrayList<Object>();
            for (int i = 0; i < n; i++) {
                out.add(java.lang.reflect.Array.get(value, i));
            }
            return out;
        }
        return Collections.singletonList(value);
    }

    private static Object valueAt(List<Object> values, int index, Object fallback) {
        if (values == null || index < 0 || index >= values.size()) return fallback;
        Object value = values.get(index);
        return value == null ? fallback : value;
    }

    @SafeVarargs
    private static int maxSize(List<Object>... lists) {
        int max = 0;
        if (lists != null) {
            for (List<Object> list : lists) {
                if (list != null && list.size() > max) {
                    max = list.size();
                }
            }
        }
        return max;
    }

    private static void putDefault(Map<String, Object> map, String key, Object value) {
        if (map != null && !map.containsKey(key)) {
            map.put(key, value);
        }
    }

    private static ParticleSizeStage.SizeToken parseParticleSizeToken(String token) {
        if (token == null || token.trim().isEmpty()) {
            return new ParticleSizeStage.SizeToken("100", "Infinity");
        }
        String[] parts = token.trim().split("-", 2);
        if (parts.length != 2) {
            return new ParticleSizeStage.SizeToken("100", "Infinity");
        }
        String min = parts[0] == null || parts[0].trim().isEmpty()
                ? "100" : parts[0].trim();
        String max = parts[1] == null || parts[1].trim().isEmpty()
                ? "Infinity" : parts[1].trim();
        if ("inf".equalsIgnoreCase(max)) {
            max = "Infinity";
        }
        return new ParticleSizeStage.SizeToken(min, max);
    }

    private static ThreeDObjectPreset defaultThreeDObjectPreset() {
        return new ThreeDObjectPreset(
                "Loaded 3D Object run",
                null,
                ThreeDObjectPreset.CURRENT_LIBRARY_VERSION,
                false,
                false,
                false,
                false,
                false,
                false,
                30.0,
                null,
                null);
    }

    private static SpatialPreset defaultSpatialPreset() {
        return new SpatialPreset(
                "Loaded Spatial run",
                null,
                SpatialPreset.CURRENT_LIBRARY_VERSION,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                4,
                0.0,
                "Fire",
                0,
                30.0);
    }

    private static Map<String, Object> defaultIntensityMap() {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("name", "Loaded Intensity run");
        out.put("libraryVersion", IntensityPreset.CURRENT_LIBRARY_VERSION);
        return out;
    }

    private static Map<String, Object> defaultDeconvMap() {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("name", "Loaded Deconvolution run");
        out.put("engineKey", "clij2-fft");
        out.put("algorithm", "RL");
        out.put("psfModel", "GIBSON_LANNI");
        out.put("iterations", Integer.valueOf(15));
        out.put("regularization", Double.valueOf(0.01));
        out.put("scopeModality", "WIDEFIELD");
        return out;
    }

    private static Map<String, Object> defaultSpectralMap() {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("name", "Loaded Spectral run");
        out.put("libraryVersion", SpectralDecontamPreset.CURRENT_LIBRARY_VERSION);
        return out;
    }

    private static Map<String, Object> defaultBinMap() {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("name", "Loaded Channel Configuration run");
        out.put("libraryVersion", BinPreset.CURRENT_LIBRARY_VERSION);
        out.put("channels", Collections.emptyList());
        return out;
    }

    public static final class PresetLoad<T> {
        public final T payload;
        public final Result result;

        public PresetLoad(T payload, Result result) {
            this.payload = payload;
            this.result = result == null ? Result.empty() : result;
        }
    }

    public static final class ValueLoad<T> {
        public final T value;
        public final Result result;

        public ValueLoad(T value, Result result) {
            this.value = value;
            this.result = result == null ? Result.empty() : result;
        }
    }

    /** Restorable Line Distance dialog selections parsed from a run record. */
    public static final class LineDistanceSelections {
        public final List<String> selectedSets;
        public final boolean drawNew;
        public final String landmark;
        public final String customName;
        public final boolean drawOnSubset;

        public LineDistanceSelections(List<String> selectedSets, boolean drawNew,
                                      String landmark, String customName, boolean drawOnSubset) {
            List<String> copy = new ArrayList<String>();
            if (selectedSets != null) {
                copy.addAll(selectedSets);
            }
            this.selectedSets = Collections.unmodifiableList(copy);
            this.drawNew = drawNew;
            this.landmark = landmark;
            this.customName = customName;
            this.drawOnSubset = drawOnSubset;
        }
    }

    public static final class Result {
        private final List<String> appliedKeys;
        private final List<String> ignoredKeys;

        public Result(List<String> appliedKeys, List<String> ignoredKeys) {
            this.appliedKeys = unmodifiableCopy(appliedKeys);
            this.ignoredKeys = unmodifiableCopy(ignoredKeys);
        }

        public static Result empty() {
            return new Result(Collections.<String>emptyList(), Collections.<String>emptyList());
        }

        public static Result merge(Result... results) {
            LinkedHashSet<String> applied = new LinkedHashSet<String>();
            LinkedHashSet<String> ignored = new LinkedHashSet<String>();
            if (results != null) {
                for (Result result : results) {
                    if (result == null) continue;
                    applied.addAll(result.appliedKeys);
                    ignored.addAll(result.ignoredKeys);
                }
            }
            ignored.removeAll(applied);
            return new Result(new ArrayList<String>(applied), new ArrayList<String>(ignored));
        }

        public List<String> getAppliedKeys() {
            return appliedKeys;
        }

        public List<String> getIgnoredKeys() {
            return ignoredKeys;
        }

        public boolean hasIgnoredKeys() {
            return !ignoredKeys.isEmpty();
        }

        public String summary() {
            return "Applied: " + joinOrNone(appliedKeys)
                    + "\nIgnored: " + joinOrNone(ignoredKeys);
        }

        private static List<String> unmodifiableCopy(List<String> source) {
            List<String> copy = new ArrayList<String>();
            if (source != null) {
                copy.addAll(source);
            }
            return Collections.unmodifiableList(copy);
        }

        private static String joinOrNone(List<String> values) {
            if (values == null || values.isEmpty()) {
                return "(none)";
            }
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < values.size(); i++) {
                if (i > 0) out.append(", ");
                out.append(values.get(i));
            }
            return out.toString();
        }
    }
}
