package flash.pipeline.analyses;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * In-memory configuration for {@code StatisticalAnalysis}.
 * <p>
 * Mirrors the user-facing knobs exposed by the Statistics Wizard: paired
 * design, distribution caution, and post-hoc method, plus an optional
 * filter restricting which metric columns are tested.
 * <p>
 * Defaults reproduce the engine's pre-wizard behaviour exactly:
 * unpaired, automatic K&sup2; normality gate, Bonferroni-corrected
 * pairwise post-hoc.
 */
public final class StatisticsConfig {

    /** How groups are paired across animals. */
    public enum PairedMode {
        OFF, HEMISPHERE, REGION, SESSION;

        public static PairedMode parse(String value, PairedMode fallback) {
            if (value == null) return fallback;
            String trimmed = value.trim();
            if (trimmed.isEmpty()) return fallback;
            String normalized = trimmed.replace('-', '_').replace(' ', '_')
                    .toUpperCase(Locale.ROOT);
            if ("HEMI".equals(normalized)) normalized = "HEMISPHERE";
            if ("UNPAIRED".equals(normalized) || "NONE".equals(normalized)) {
                normalized = "OFF";
            }
            try {
                return PairedMode.valueOf(normalized);
            } catch (IllegalArgumentException e) {
                return fallback;
            }
        }
    }

    /** How the engine decides between parametric and non-parametric tests. */
    public enum DistributionMode {
        AUTO, ASSUME_NORMAL, ASSUME_SKEWED;

        public static DistributionMode parse(String value, DistributionMode fallback) {
            if (value == null) return fallback;
            String trimmed = value.trim();
            if (trimmed.isEmpty()) return fallback;
            String normalized = trimmed.replace('-', '_').replace(' ', '_')
                    .toUpperCase(Locale.ROOT);
            if ("NORMAL".equals(normalized) || "PARAMETRIC".equals(normalized)) {
                normalized = "ASSUME_NORMAL";
            } else if ("SKEWED".equals(normalized)
                    || "NONPARAMETRIC".equals(normalized)
                    || "NON_PARAMETRIC".equals(normalized)) {
                normalized = "ASSUME_SKEWED";
            }
            try {
                return DistributionMode.valueOf(normalized);
            } catch (IllegalArgumentException e) {
                return fallback;
            }
        }
    }

    /** Pairwise post-hoc strategy applied for k &ge; 3 groups. */
    public enum PostHocMethod {
        BONFERRONI, TUKEY, DUNNS, NONE;

        public static PostHocMethod parse(String value, PostHocMethod fallback) {
            if (value == null) return fallback;
            String trimmed = value.trim();
            if (trimmed.isEmpty()) return fallback;
            String normalized = trimmed.replace('-', '_').replace(' ', '_')
                    .replace("'", "")
                    .toUpperCase(Locale.ROOT);
            if ("TUKEY_HSD".equals(normalized) || "HSD".equals(normalized)) {
                normalized = "TUKEY";
            } else if ("DUNN".equals(normalized) || "DUNNS_TEST".equals(normalized)) {
                normalized = "DUNNS";
            }
            try {
                return PostHocMethod.valueOf(normalized);
            } catch (IllegalArgumentException e) {
                return fallback;
            }
        }
    }

    /** How nested rows should be collapsed into one animal-level value. */
    public enum MetricAggregation {
        AUTO, MEAN, SUM;

        public static MetricAggregation parse(String value, MetricAggregation fallback) {
            if (value == null) return fallback;
            String trimmed = value.trim();
            if (trimmed.isEmpty()) return fallback;
            String normalized = trimmed.replace('-', '_').replace(' ', '_')
                    .toUpperCase(Locale.ROOT);
            if ("AVERAGE".equals(normalized) || "AVG".equals(normalized)) {
                normalized = "MEAN";
            } else if ("COUNT".equals(normalized) || "COUNTS".equals(normalized)
                    || "TOTAL".equals(normalized) || "SUMMED".equals(normalized)) {
                normalized = "SUM";
            } else if ("DEFAULT".equals(normalized) || "INFER".equals(normalized)
                    || "HEURISTIC".equals(normalized)) {
                normalized = "AUTO";
            }
            try {
                return MetricAggregation.valueOf(normalized);
            } catch (IllegalArgumentException e) {
                return fallback;
            }
        }
    }

    public PairedMode pairedMode = PairedMode.OFF;
    public DistributionMode distributionMode = DistributionMode.AUTO;
    public PostHocMethod postHocMethod = PostHocMethod.BONFERRONI;
    /** {@code null} or empty means "test every numeric metric column". */
    public List<String> metricFilter = null;
    /** Per-metric nested-row collapse overrides; absent or AUTO uses the built-in heuristic. */
    public Map<String, MetricAggregation> metricAggregationOverrides = null;

    public StatisticsConfig() {}

    public MetricAggregation metricAggregationFor(String metric) {
        if (metric == null || metricAggregationOverrides == null
                || metricAggregationOverrides.isEmpty()) {
            return MetricAggregation.AUTO;
        }
        String trimmed = metric.trim();
        MetricAggregation matched = null;
        for (Map.Entry<String, MetricAggregation> entry : metricAggregationOverrides.entrySet()) {
            String key = entry.getKey();
            if (key != null && key.trim().equalsIgnoreCase(trimmed)) {
                matched = entry.getValue();
            }
        }
        return matched == null ? MetricAggregation.AUTO : matched;
    }

    public void putMetricAggregationOverride(String metric, MetricAggregation aggregation) {
        if (metric == null) return;
        String trimmed = metric.trim();
        if (trimmed.isEmpty()) return;
        if (aggregation == null || aggregation == MetricAggregation.AUTO) {
            if (metricAggregationOverrides != null) {
                String existing = existingMetricAggregationKey(trimmed);
                metricAggregationOverrides.remove(existing == null ? trimmed : existing);
                if (metricAggregationOverrides.isEmpty()) {
                    metricAggregationOverrides = null;
                }
            }
            return;
        }
        if (metricAggregationOverrides == null) {
            metricAggregationOverrides = new LinkedHashMap<String, MetricAggregation>();
        }
        String existing = existingMetricAggregationKey(trimmed);
        if (existing != null) {
            metricAggregationOverrides.remove(existing);
        }
        metricAggregationOverrides.put(trimmed, aggregation);
    }

    private String existingMetricAggregationKey(String metric) {
        if (metric == null || metricAggregationOverrides == null) return null;
        for (String key : metricAggregationOverrides.keySet()) {
            if (key != null && key.trim().equalsIgnoreCase(metric.trim())) {
                return key;
            }
        }
        return null;
    }
}
