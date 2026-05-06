package flash.pipeline.analyses.wizard;

import flash.pipeline.ui.wizard.JsonIO;
import flash.pipeline.ui.wizard.Preset;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Persisted {@link AggregationConfig} choices for the main-panel dropdown.
 */
public final class AggregationPreset implements Preset<AggregationPreset> {

    public static final String CURRENT_LIBRARY_VERSION = "1";

    private final String name;
    private final String description;
    private final String libraryVersion;
    private final AggregationConfig.Granularity granularity;
    private final AggregationConfig.OutputMode outputMode;

    public AggregationPreset(String name,
                             String description,
                             AggregationConfig.Granularity granularity,
                             AggregationConfig.OutputMode outputMode) {
        this.name = requireText("name", name);
        this.description = emptyToNull(description);
        this.libraryVersion = CURRENT_LIBRARY_VERSION;
        this.granularity = granularity == null
                ? AggregationConfig.Granularity.ANIMAL : granularity;
        this.outputMode = outputMode == null
                ? AggregationConfig.OutputMode.RAW_AND_PERMM3 : outputMode;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public String getDescription() {
        return description;
    }

    @Override
    public String getLibraryVersion() {
        return libraryVersion;
    }

    public AggregationConfig.Granularity getGranularity() {
        return granularity;
    }

    public AggregationConfig.OutputMode getOutputMode() {
        return outputMode;
    }

    @Override
    public AggregationPreset getPayload() {
        return this;
    }

    @Override
    public Map<String, Object> toJsonObject() {
        Map<String, Object> root = new LinkedHashMap<String, Object>();
        root.put("name", name);
        if (description != null) {
            root.put("description", description);
        }
        root.put("libraryVersion", libraryVersion);
        root.put("granularity", granularity.token());
        root.put("output", outputMode.token());
        return root;
    }

    public String toJson() {
        return JsonIO.write(toJsonObject());
    }

    public static AggregationPreset fromJson(String json) throws IOException {
        return fromJsonObject(JsonIO.parseObject(json));
    }

    public static AggregationPreset fromJsonObject(Map<String, Object> root) throws IOException {
        if (root == null) {
            throw new IOException("Preset JSON object is required.");
        }
        String name = stringOr(root.get("name"), "Aggregation Preset");
        String description = JsonIO.stringValue(root.get("description"));
        AggregationConfig.Granularity granularity = AggregationConfig.Granularity.parse(
                JsonIO.stringValue(root.get("granularity")),
                AggregationConfig.Granularity.ANIMAL);
        AggregationConfig.OutputMode outputMode = AggregationConfig.OutputMode.parse(
                JsonIO.stringValue(root.get("output")),
                AggregationConfig.OutputMode.RAW_AND_PERMM3);
        return new AggregationPreset(name, description, granularity, outputMode);
    }

    private static String requireText(String label, String value) {
        String trimmed = emptyToNull(value);
        if (trimmed == null) {
            throw new IllegalArgumentException(label + " is required.");
        }
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
