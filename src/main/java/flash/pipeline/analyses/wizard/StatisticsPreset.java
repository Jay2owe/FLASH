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
    private final Map<String, StatisticsConfig.MetricAggregation> metricAggregationOverrides;

    public StatisticsPreset(String name,
                            String description,
                            StatisticsConfig.PairedMode pairedMode,
                            StatisticsConfig.DistributionMode distributionMode,
                            StatisticsConfig.PostHocMethod postHocMethod,
                            List<String> metricFilter) {
        this(name, description, pairedMode, distributionMode, postHocMethod,
                metricFilter, null);
    }

    public StatisticsPreset(String name,
                            String description,
                            StatisticsConfig.PairedMode pairedMode,
                            StatisticsConfig.DistributionMode distributionMode,
                            StatisticsConfig.PostHocMethod postHocMethod,
                            List<String> metricFilter,
                            Map<String, StatisticsConfig.MetricAggregation> metricAggregationOverrides) {
        this.name = requireText("name", name);
        this.description = emptyToNull(description);
        this.libraryVersion = CURRENT_LIBRARY_VERSION;
        this.pairedMode = pairedMode == null ? StatisticsConfig.PairedMode.OFF : pairedMode;
        this.distributionMode = distributionMode == null
                ? StatisticsConfig.DistributionMode.AUTO : distributionMode;
        this.postHocMethod = postHocMethod == null
                ? StatisticsConfig.PostHocMethod.BONFERRONI : postHocMethod;
        this.metricFilter = freezeList(metricFilter);
        this.metricAggregationOverrides = freezeMetricAggregationOverrides(metricAggregationOverrides);
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

    public Map<String, StatisticsConfig.MetricAggregation> getMetricAggregationOverrides() {
        return metricAggregationOverrides;
    }

    /** Builds a fresh {@link StatisticsConfig} with this preset's choices. */
    public StatisticsConfig toConfig() {
        StatisticsConfig cfg = new StatisticsConfig();
        cfg.pairedMode = pairedMode;
        cfg.distributionMode = distributionMode;
        cfg.postHocMethod = postHocMethod;
        cfg.metricFilter = metricFilter == null ? null : new ArrayList<String>(metricFilter);
        cfg.metricAggregationOverrides = metricAggregationOverrides == null
                ? null
                : new LinkedHashMap<String, StatisticsConfig.MetricAggregation>(
                        metricAggregationOverrides);
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
        if (metricAggregationOverrides == null) {
            root.put("metricAggregationOverrides", null);
        } else {
            LinkedHashMap<String, String> out = new LinkedHashMap<String, String>();
            for (Map.Entry<String, StatisticsConfig.MetricAggregation> entry
                    : metricAggregationOverrides.entrySet()) {
                out.put(entry.getKey(), entry.getValue().name());
            }
            root.put("metricAggregationOverrides", out);
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
        Map<String, StatisticsConfig.MetricAggregation> metricAggregationOverrides =
                parseMetricAggregationOverrides(root);
        return new StatisticsPreset(name, description, paired, distribution, postHoc,
                metricFilter, metricAggregationOverrides);
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

    private static Map<String, StatisticsConfig.MetricAggregation> parseMetricAggregationOverrides(
            Map<String, Object> root) {
        Object raw = root.get("metricAggregationOverrides");
        if (raw == null) {
            raw = root.get("metricAggregation");
        }
        if (raw == null) {
            return null;
        }
        LinkedHashMap<String, StatisticsConfig.MetricAggregation> out =
                new LinkedHashMap<String, StatisticsConfig.MetricAggregation>();
        if (raw instanceof Map<?, ?>) {
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) raw).entrySet()) {
                if (entry.getKey() == null) continue;
                putMetricAggregation(out, String.valueOf(entry.getKey()),
                        JsonIO.stringValue(entry.getValue()));
            }
        } else {
            for (Object item : JsonIO.asList(raw)) {
                if (item == null) continue;
                String text = String.valueOf(item).trim();
                int sep = text.lastIndexOf(':');
                if (sep < 0) sep = text.lastIndexOf('=');
                if (sep <= 0 || sep + 1 >= text.length()) continue;
                putMetricAggregation(out, text.substring(0, sep), text.substring(sep + 1));
            }
        }
        return out.isEmpty() ? null : out;
    }

    private static void putMetricAggregation(
            Map<String, StatisticsConfig.MetricAggregation> out,
            String metric,
            String aggregation) {
        if (metric == null || aggregation == null) return;
        String trimmed = metric.trim();
        if (trimmed.isEmpty()) return;
        StatisticsConfig.MetricAggregation parsed =
                StatisticsConfig.MetricAggregation.parse(
                        aggregation, StatisticsConfig.MetricAggregation.AUTO);
        if (parsed == StatisticsConfig.MetricAggregation.AUTO) return;
        String existing = existingMetricAggregationKey(out, trimmed);
        if (existing != null) {
            out.remove(existing);
        }
        out.put(trimmed, parsed);
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

    private static Map<String, StatisticsConfig.MetricAggregation>
    freezeMetricAggregationOverrides(
            Map<String, StatisticsConfig.MetricAggregation> values) {
        if (values == null) {
            return null;
        }
        LinkedHashMap<String, StatisticsConfig.MetricAggregation> out =
                new LinkedHashMap<String, StatisticsConfig.MetricAggregation>();
        for (Map.Entry<String, StatisticsConfig.MetricAggregation> entry : values.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) continue;
            String trimmed = entry.getKey().trim();
            if (trimmed.isEmpty()
                    || entry.getValue() == StatisticsConfig.MetricAggregation.AUTO) {
                continue;
            }
            String existing = existingMetricAggregationKey(out, trimmed);
            if (existing != null) {
                out.remove(existing);
            }
            out.put(trimmed, entry.getValue());
        }
        return Collections.unmodifiableMap(out);
    }

    private static String existingMetricAggregationKey(
            Map<String, StatisticsConfig.MetricAggregation> values,
            String metric) {
        if (values == null || metric == null) return null;
        String trimmed = metric.trim();
        for (String key : values.keySet()) {
            if (key != null && key.trim().equalsIgnoreCase(trimmed)) {
                return key;
            }
        }
        return null;
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
