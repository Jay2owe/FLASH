package flash.pipeline.analyses.wizard;

import java.util.Locale;

/**
 * In-memory configuration for {@code MasterAggregationAnalysis}.
 * <p>
 * The aggregation step has only two real user-facing choices — grouping
 * granularity and which outputs to emit — plus an optional preset name.
 * Downstream analyses (notably {@code StatisticalAnalysis}) read
 * {@link #getGranularity()} so they can enable paired-hemisphere or
 * paired-region tests when the master CSVs were aggregated that way.
 */
public final class AggregationConfig {

    /** How rows in master CSVs should be grouped. */
    public enum Granularity {
        /** One row per animal — the original behavior. */
        ANIMAL,
        /** One row per (animal, hemisphere) pair. */
        HEMISPHERE,
        /** One row per (animal, region) pair. */
        REGION,
        /** One row per (animal, section) pair (no within-animal averaging). */
        SECTION;

        public String token() {
            return name().toLowerCase(Locale.ROOT);
        }

        public static Granularity parse(String value, Granularity fallback) {
            if (value == null) return fallback;
            String trimmed = value.trim();
            if (trimmed.isEmpty()) return fallback;
            String normalized = trimmed.replace('-', '_').replace(' ', '_')
                    .toUpperCase(Locale.ROOT);
            if ("HEMI".equals(normalized)) normalized = "HEMISPHERE";
            try {
                return Granularity.valueOf(normalized);
            } catch (IllegalArgumentException e) {
                return fallback;
            }
        }
    }

    /** Which output columns are written to the master CSVs. */
    public enum OutputMode {
        /** Write both raw totals and per-mm^3 companions (default). */
        RAW_AND_PERMM3,
        /** Write raw totals only. */
        RAW_ONLY,
        /** Write per-mm^3 columns only (means are always kept). */
        PERMM3_ONLY;

        public String token() {
            if (this == RAW_AND_PERMM3) return "both";
            if (this == RAW_ONLY) return "raw";
            return "normalized";
        }

        public static OutputMode parse(String value, OutputMode fallback) {
            if (value == null) return fallback;
            String trimmed = value.trim();
            if (trimmed.isEmpty()) return fallback;
            String normalized = trimmed.toLowerCase(Locale.ROOT);
            if ("both".equals(normalized)
                    || "raw+permm3".equals(normalized)
                    || "raw_and_permm3".equals(normalized)) {
                return RAW_AND_PERMM3;
            }
            if ("raw".equals(normalized) || "raw_only".equals(normalized)) {
                return RAW_ONLY;
            }
            if ("permm3".equals(normalized)
                    || "per_mm3".equals(normalized)
                    || "per-mm3".equals(normalized)
                    || "normalized".equals(normalized)
                    || "normalised".equals(normalized)
                    || "permm3_only".equals(normalized)) {
                return PERMM3_ONLY;
            }
            return fallback;
        }
    }

    private Granularity granularity = Granularity.ANIMAL;
    private OutputMode outputMode = OutputMode.RAW_AND_PERMM3;
    private String presetName = null;

    public AggregationConfig() {}

    public Granularity getGranularity() {
        return granularity;
    }

    public void setGranularity(Granularity granularity) {
        this.granularity = granularity == null ? Granularity.ANIMAL : granularity;
    }

    public OutputMode getOutputMode() {
        return outputMode;
    }

    public void setOutputMode(OutputMode outputMode) {
        this.outputMode = outputMode == null ? OutputMode.RAW_AND_PERMM3 : outputMode;
    }

    public String getPresetName() {
        return presetName;
    }

    public void setPresetName(String presetName) {
        this.presetName = (presetName == null || presetName.trim().isEmpty())
                ? null : presetName.trim();
    }

    /** Applies a preset's granularity and output choices in-place. */
    public void applyPreset(AggregationPreset preset) {
        if (preset == null) return;
        setGranularity(preset.getGranularity());
        setOutputMode(preset.getOutputMode());
        setPresetName(preset.getName());
    }

    /** True if the config differs from the default all-defaults state. */
    public boolean hasConfiguration() {
        return granularity != Granularity.ANIMAL
                || outputMode != OutputMode.RAW_AND_PERMM3
                || (presetName != null && !presetName.isEmpty());
    }
}
