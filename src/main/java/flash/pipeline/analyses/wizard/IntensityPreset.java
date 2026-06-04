package flash.pipeline.analyses.wizard;

import flash.pipeline.ui.wizard.JsonIO;
import flash.pipeline.ui.wizard.Preset;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Persisted setup for Intensity Analysis.
 */
public final class IntensityPreset implements Preset<IntensityPreset> {

    public static final String CURRENT_LIBRARY_VERSION = "2";

    private final String name;
    private final String description;
    private final String libraryVersion;
    private final String strategy;
    private final String defaultMode;
    private final Map<String, String> channelModes;
    private final Map<String, String> thresholds;
    private final String maskChannelHint;
    private final List<String> roiSetNameHints;
    private final Map<String, String> filterSources;
    private final IntensitySpatialConfig spatial;

    public IntensityPreset(String name,
                           String description,
                           String libraryVersion,
                           String strategy,
                           String defaultMode,
                           Map<String, String> channelModes,
                           Map<String, String> thresholds,
                           String maskChannelHint,
                           List<String> roiSetNameHints) {
        this(name, description, libraryVersion, strategy, defaultMode, channelModes,
                thresholds, maskChannelHint, roiSetNameHints, null, null);
    }

    public IntensityPreset(String name,
                           String description,
                           String libraryVersion,
                           String strategy,
                           String defaultMode,
                           Map<String, String> channelModes,
                           Map<String, String> thresholds,
                           String maskChannelHint,
                           List<String> roiSetNameHints,
                           IntensitySpatialConfig spatial) {
        this(name, description, libraryVersion, strategy, defaultMode, channelModes,
                thresholds, maskChannelHint, roiSetNameHints, spatial, null);
    }

    public IntensityPreset(String name,
                           String description,
                           String libraryVersion,
                           String strategy,
                           String defaultMode,
                           Map<String, String> channelModes,
                           Map<String, String> thresholds,
                           String maskChannelHint,
                           List<String> roiSetNameHints,
                           IntensitySpatialConfig spatial,
                           Map<String, String> filterSources) {
        this.name = requireText("name", name);
        this.description = emptyToNull(description);
        this.libraryVersion = emptyToNull(libraryVersion) == null
                ? CURRENT_LIBRARY_VERSION
                : libraryVersion.trim();
        this.strategy = emptyToNull(strategy) == null ? "custom" : strategy.trim();
        this.defaultMode = emptyToNull(defaultMode) == null
                ? IntensitySetupConfig.MODE_WHOLE_ROI_MEAN
                : defaultMode.trim();
        this.channelModes = immutableStringMap(channelModes);
        this.thresholds = immutableStringMap(thresholds);
        this.maskChannelHint = emptyToNull(maskChannelHint);
        this.roiSetNameHints = immutableStringList(roiSetNameHints);
        this.filterSources = immutableStringMap(filterSources);
        this.spatial = spatial == null ? IntensitySpatialConfig.disabled() : spatial;
    }

    public String getName() { return name; }
    public String getDescription() { return description; }
    public String getLibraryVersion() { return libraryVersion; }
    public IntensityPreset getPayload() { return this; }
    public String getStrategy() { return strategy; }
    public String getDefaultMode() { return defaultMode; }
    public Map<String, String> getChannelModes() { return channelModes; }
    public Map<String, String> getThresholds() { return thresholds; }
    public String getMaskChannelHint() { return maskChannelHint; }
    public List<String> getRoiSetNameHints() { return roiSetNameHints; }
    public Map<String, String> getFilterSources() { return filterSources; }
    public IntensitySpatialConfig getSpatial() { return spatial; }

    public Map<String, Object> toJsonObject() {
        Map<String, Object> root = new LinkedHashMap<String, Object>();
        root.put("name", name);
        if (description != null) root.put("description", description);
        root.put("libraryVersion", libraryVersion);
        root.put("strategy", strategy);
        root.put("defaultMode", defaultMode);
        root.put("channelModes", new LinkedHashMap<String, String>(channelModes));
        root.put("thresholds", new LinkedHashMap<String, String>(thresholds));
        if (maskChannelHint != null) root.put("maskChannelHint", maskChannelHint);
        root.put("roiSetNameHints", new ArrayList<String>(roiSetNameHints));
        if (!filterSources.isEmpty()) {
            root.put("filterSources", new LinkedHashMap<String, String>(filterSources));
        }
        if (spatial != null && spatial.hasConfiguration()) {
            root.put("spatial", spatial.toJsonObject());
        }
        return root;
    }

    public String toJson() {
        return JsonIO.write(toJsonObject());
    }

    public static IntensityPreset fromJson(String json) throws IOException {
        return fromJsonObject(JsonIO.parseObject(json));
    }

    public static IntensityPreset fromJsonObject(Map<String, Object> root) throws IOException {
        if (root == null) throw new IOException("Preset JSON object is required.");
        return new IntensityPreset(
                stringOr(root.get("name"), "Intensity Preset"),
                JsonIO.stringValue(root.get("description")),
                stringOr(root.get("libraryVersion"), CURRENT_LIBRARY_VERSION),
                stringOr(root.get("strategy"), "custom"),
                stringOr(root.get("defaultMode"), IntensitySetupConfig.MODE_WHOLE_ROI_MEAN),
                stringMap(root.get("channelModes")),
                stringMap(root.get("thresholds")),
                JsonIO.stringValue(root.get("maskChannelHint")),
                stringList(root.get("roiSetNameHints")),
                IntensitySpatialConfig.fromJsonObject(root.get("spatial")),
                stringMap(root.get("filterSources")));
    }

    private static Map<String, String> stringMap(Object value) {
        Map<String, String> out = new LinkedHashMap<String, String>();
        Map<String, Object> source = JsonIO.asObject(value);
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null) {
                out.put(entry.getKey(), String.valueOf(entry.getValue()));
            }
        }
        return out;
    }

    private static List<String> stringList(Object value) {
        List<String> out = new ArrayList<String>();
        for (Object item : JsonIO.asList(value)) {
            if (item != null) {
                String text = String.valueOf(item).trim();
                if (!text.isEmpty()) out.add(text);
            }
        }
        return out;
    }

    private static Map<String, String> immutableStringMap(Map<String, String> values) {
        Map<String, String> out = new LinkedHashMap<String, String>();
        if (values != null) {
            for (Map.Entry<String, String> entry : values.entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null) {
                    out.put(entry.getKey(), entry.getValue());
                }
            }
        }
        return Collections.unmodifiableMap(out);
    }

    private static List<String> immutableStringList(List<String> values) {
        List<String> out = new ArrayList<String>();
        if (values != null) {
            for (String value : values) {
                String text = emptyToNull(value);
                if (text != null) out.add(text);
            }
        }
        return Collections.unmodifiableList(out);
    }

    private static String requireText(String label, String value) {
        String trimmed = emptyToNull(value);
        if (trimmed == null) throw new IllegalArgumentException(label + " is required.");
        return trimmed;
    }

    private static String emptyToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String stringOr(Object value, String fallback) {
        String text = JsonIO.stringValue(value);
        return text == null || text.trim().isEmpty() ? fallback : text.trim();
    }
}
