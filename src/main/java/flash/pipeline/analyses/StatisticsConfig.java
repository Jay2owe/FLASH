package flash.pipeline.analyses;

import java.util.List;
import java.util.Locale;

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

    public PairedMode pairedMode = PairedMode.OFF;
    public DistributionMode distributionMode = DistributionMode.AUTO;
    public PostHocMethod postHocMethod = PostHocMethod.BONFERRONI;
    /** {@code null} or empty means "test every numeric metric column". */
    public List<String> metricFilter = null;

    public StatisticsConfig() {}
}
