package flash.pipeline.analyses.wizard;

import flash.pipeline.ui.wizard.JsonIO;
import flash.pipeline.ui.wizard.Preset;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Persisted setup for the nested Intensity-Spatial Analysis options.
 */
public final class IntensitySpatialPreset implements Preset<IntensitySpatialConfig> {

    public static final String CURRENT_LIBRARY_VERSION = "1";

    private final String name;
    private final String description;
    private final String libraryVersion;
    private final IntensitySpatialConfig config;

    public IntensitySpatialPreset(String name,
                                  String description,
                                  String libraryVersion,
                                  IntensitySpatialConfig config) {
        this.name = requireText("name", name);
        this.description = emptyToNull(description);
        this.libraryVersion = emptyToNull(libraryVersion) == null
                ? CURRENT_LIBRARY_VERSION
                : libraryVersion.trim();
        this.config = config == null ? IntensitySpatialConfig.disabled() : config;
    }

    public String getName() { return name; }
    public String getDescription() { return description; }
    public String getLibraryVersion() { return libraryVersion; }
    public IntensitySpatialConfig getPayload() { return config; }
    public IntensitySpatialConfig getConfig() { return config; }

    public Map<String, Object> toJsonObject() {
        Map<String, Object> root = new LinkedHashMap<String, Object>();
        root.put("name", name);
        if (description != null) root.put("description", description);
        root.put("libraryVersion", libraryVersion);
        root.put("config", config.toJsonObject());
        return root;
    }

    public String toJson() {
        return JsonIO.write(toJsonObject());
    }

    public static IntensitySpatialPreset fromJson(String json) throws IOException {
        return fromJsonObject(JsonIO.parseObject(json));
    }

    public static IntensitySpatialPreset fromJsonObject(Map<String, Object> root)
            throws IOException {
        if (root == null) throw new IOException("Preset JSON object is required.");
        Object configObject = first(root, "config", "spatial", "intensitySpatialConfig");
        IntensitySpatialConfig spatial = configObject == null
                ? IntensitySpatialConfig.fromJsonObject(root)
                : IntensitySpatialConfig.fromJsonObject(configObject);
        return new IntensitySpatialPreset(
                stringOr(root.get("name"), "Intensity-Spatial Preset"),
                JsonIO.stringValue(root.get("description")),
                stringOr(root.get("libraryVersion"), CURRENT_LIBRARY_VERSION),
                spatial);
    }

    private static Object first(Map<String, Object> root, String... keys) {
        for (String key : keys) {
            if (root.containsKey(key)) return root.get(key);
        }
        return null;
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
