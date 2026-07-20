package flash.pipeline.ui.variations;

/**
 * Sweepable axes for a spectral-decontamination parameter-variations grid. Mirrors
 * {@link DeconvParameterId} but for the correction-stack knobs that most change the
 * decontamination output: subtraction strength, quiet-pixel fit percentile,
 * autofluorescence mode (global vs local) and window radius, mask threshold percentile,
 * ROC allowed false-positive rate, and the min/max size filter.
 *
 * <p>Each axis maps onto one or more feature {@code Settings} keys inside a
 * {@link flash.pipeline.decontamination.SpectralDecontaminationConfig}; see
 * {@code SpectralComboSettings} for the mapping. {@link #AF_MODE} is <em>structural</em> —
 * resolving it swaps which autofluorescence feature is in the stack rather than editing a
 * numeric setting. {@link #ROC_FPR} is a batch-time axis: ROC needs control/experimental
 * images and calibration, so it is not run in the single-crop preview grid.</p>
 */
public enum SpectralParameterId implements ParameterKey {

    STRENGTH("unmix_strength", "Subtraction strength", ValueKind.NUMBER),
    FIT_PERCENTILE("fit_percentile", "Unmixing fit percentile", ValueKind.NUMBER),
    AF_MODE("af_mode", "Autofluorescence mode", ValueKind.STRING),
    AF_WINDOW_RADIUS("af_window_radius", "AF window radius", ValueKind.NUMBER),
    AF_QUIET_PERCENTILE("af_quiet_percentile", "AF quiet percentile", ValueKind.NUMBER),
    MASK_PERCENTILE("mask_percentile", "Mask threshold percentile", ValueKind.NUMBER),
    ROC_FPR("roc_fpr", "Allowed false-positive rate", ValueKind.NUMBER),
    SIZE_MIN("size_min_voxels", "Min size (voxels)", ValueKind.NUMBER),
    SIZE_MAX("size_max_voxels", "Max size (voxels)", ValueKind.NUMBER);

    /** Categorical value for {@link #AF_MODE}: image-level global ratio correction. */
    public static final String AF_MODE_GLOBAL = "global";
    /** Categorical value for {@link #AF_MODE}: spatially-adaptive local-k correction. */
    public static final String AF_MODE_LOCAL = "local";

    private final String stableKey;
    private final String displayLabel;
    private final ValueKind valueKind;

    SpectralParameterId(String stableKey, String displayLabel, ValueKind valueKind) {
        this.stableKey = stableKey;
        this.displayLabel = displayLabel;
        this.valueKind = valueKind;
    }

    @Override
    public String stableKey() {
        return stableKey;
    }

    @Override
    public String displayLabel() {
        return displayLabel;
    }

    @Override
    public ValueKind valueKind() {
        return valueKind;
    }

    public static SpectralParameterId fromStableKey(String key) {
        if (key == null) {
            return null;
        }
        String trimmed = key.trim();
        for (SpectralParameterId id : values()) {
            if (id.name().equalsIgnoreCase(trimmed) || id.stableKey.equalsIgnoreCase(trimmed)) {
                return id;
            }
        }
        return null;
    }
}
