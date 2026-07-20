package flash.pipeline.ui.variations;

/**
 * Sweepable axes for a deconvolution parameter-variations grid. Mirrors
 * {@link ParameterId} (segmentation) but for the per-channel deconvolution settings:
 * engine, algorithm, iteration count, regularization strength, and PSF model.
 */
public enum DeconvParameterId implements ParameterKey {

    ENGINE("engine", "engine", ValueKind.STRING),
    ALGORITHM("algorithm", "algorithm", ValueKind.STRING),
    ITERATIONS("iterations", "iterations", ValueKind.NUMBER),
    REGULARIZATION("regularization", "regularization", ValueKind.NUMBER),
    PSF_MODEL("psf_model", "PSF model", ValueKind.STRING);

    private final String stableKey;
    private final String displayLabel;
    private final ValueKind valueKind;

    DeconvParameterId(String stableKey, String displayLabel, ValueKind valueKind) {
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

    public static DeconvParameterId fromStableKey(String key) {
        if (key == null) {
            return null;
        }
        String trimmed = key.trim();
        for (DeconvParameterId id : values()) {
            if (id.name().equalsIgnoreCase(trimmed) || id.stableKey.equalsIgnoreCase(trimmed)) {
                return id;
            }
        }
        return null;
    }
}
