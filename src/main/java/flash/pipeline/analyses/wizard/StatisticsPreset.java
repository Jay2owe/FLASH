package flash.pipeline.analyses.wizard;

import flash.pipeline.analyses.StatisticsConfig;
import flash.pipeline.ui.wizard.JsonIO;
import flash.pipeline.ui.wizard.Preset;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Persisted {@link StatisticsConfig} choices for the Statistics Wizard
 * preset dropdown.
 */
public final class StatisticsPreset implements Preset<StatisticsPreset> {

    public static final String CURRENT_LIBRARY_VERSION = "1";

    private final String name;
    private final String description;
    private final String libraryVersion;
    private final StatisticsConfig.PairedMode pairedMode;
    private final StatisticsConfig.DistributionMode distributionMode;
    private final StatisticsConfig.PostHocMethod postHocMethod;
    private final List<String> metricFilter;

    public StatisticsPreset(String name,
                            String description,
                            StatisticsConfig.PairedMode pairedMode,
                            StatisticsConfig.DistributionMode distributionMode,
                            StatisticsConfig.PostHocMethod postHocMethod,
                            List<String> metricFilter) {
        this.name = requireText("name", name);
        this.description = emptyToNull(description);
        this.libraryVersion = CURRENT_LIBRARY_VERSION;
        this.pairedMode = pairedMode == null ? StatisticsConfig.PairedMode.OFF : pairedMode;
        this.distributionMode = distributionMode == null
                ? StatisticsConfig.DistributionMode.AUTO : distributionMode;
        this.postHocMethod = postHocMethod == null
                ? StatisticsConfig.PostHocMethod.BONFERRONI : postHocMethod;
        this.metricFilter = freezeList(metricFilter);
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

    @Override
    public StatisticsPreset getPayload() {
        return this;
    }

    public StatisticsConfig.PairedMode getPairedMode() {
        return pairedMode;
    }

    public StatisticsConfig.DistributionMode getDistributionMode() {
        return distributionMode;
    }

    public StatisticsConfig.PostHocMethod getPostHocMethod() {
        return postHocMethod;
    }

    /**
     * Returns the metric column allow-list, or {@code null} when every numeric
     * column should be tested.
     */
    public List<String> getMetricFilter() {
        return metricFilter;
    }

    /** Builds a fresh {@link StatisticsConfig} with this preset's choices. */
    public StatisticsConfig toConfig() {
        StatisticsConfig cfg = new StatisticsConfig();
        cfg.pairedMode = pairedMode;
        cfg.distributionMode = distributionMode;
        cfg.postHocMethod = postHocMethod;
        cfg.metricFilter = metricFilter == null ? null : new ArrayList<String>(metricFilter);
        return cfg;
    }

    @Override
    public Map<String, Object> toJsonObject() {
        Map<String, Object> root = new LinkedHashMap<String, Object>();
        root.put("name", name);
        if (description != null) {
            root.put("description", description);
        }
        root.put("libraryVersion", libraryVersion);
        root.put("pairedMode", pairedMode.name());
        root.put("distributionMode", distributionMode.name());
        root.put("postHocMethod", postHocMethod.name());
        if (metricFilter == null) {
            root.put("metricFilter", null);
        } else {
            root.put("metricFilter", new ArrayList<String>(metricFilter));
        }
        return root;
    }

    public String toJson() {
        return JsonIO.write(toJsonObject());
    }

    public static StatisticsPreset fromJson(String json) throws IOException {
        return fromJsonObject(JsonIO.parseObject(json));
    }

    public static StatisticsPreset fromJsonObject(Map<String, Object> root) throws IOException {
        if (root == null) {
            throw new IOException("Preset JSON object is required.");
        }
        String name = stringOr(root.get("name"), "Statistics Preset");
        String description = JsonIO.stringValue(root.get("description"));
        StatisticsConfig.PairedMode paired = StatisticsConfig.PairedMode.parse(
                JsonIO.stringValue(root.get("pairedMode")),
                StatisticsConfig.PairedMode.OFF);
        StatisticsConfig.DistributionMode distribution = StatisticsConfig.DistributionMode.parse(
                JsonIO.stringValue(root.get("distributionMode")),
                StatisticsConfig.DistributionMode.AUTO);
        StatisticsConfig.PostHocMethod postHoc = StatisticsConfig.PostHocMethod.parse(
                JsonIO.stringValue(root.get("postHocMethod")),
                StatisticsConfig.PostHocMethod.BONFERRONI);
        List<String> metricFilter = parseMetricFilter(root);
        return new StatisticsPreset(name, description, paired, distribution, postHoc, metricFilter);
    }

    private static List<String> parseMetricFilter(Map<String, Object> root) {
        if (!root.containsKey("metricFilter")) {
            return null;
        }
        Object raw = root.get("metricFilter");
        if (raw == null) {
            return null;
        }
        List<String> out = new ArrayList<String>();
        for (Object item : JsonIO.asList(raw)) {
            if (item == null) continue;
            String text = String.valueOf(item).trim();
            if (!text.isEmpty()) out.add(text);
        }
        return out;
    }

    private static List<String> freezeList(List<String> values) {
        if (values == null) {
            return null;
        }
        List<String> out = new ArrayList<String>();
        for (String value : values) {
            if (value == null) continue;
            String trimmed = value.trim();
            if (!trimmed.isEmpty()) out.add(trimmed);
        }
        return Collections.unmodifiableList(out);
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
