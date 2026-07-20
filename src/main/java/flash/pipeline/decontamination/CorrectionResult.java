package flash.pipeline.decontamination;

/**
 * Available pipeline outputs after simulating a correction stack.
 */
public final class CorrectionResult {

    private final boolean sourceImageAvailable;
    private final boolean correctedImageAvailable;
    private final boolean maskAvailable;
    private final boolean vetoMaskAvailable;
    private final boolean objectScoreAvailable;
    private final boolean metricAvailable;
    private final int thresholdFeatureCount;

    private CorrectionResult(boolean sourceImageAvailable,
                             boolean correctedImageAvailable,
                             boolean maskAvailable,
                             boolean vetoMaskAvailable,
                             boolean objectScoreAvailable,
                             boolean metricAvailable,
                             int thresholdFeatureCount) {
        this.sourceImageAvailable = sourceImageAvailable;
        this.correctedImageAvailable = correctedImageAvailable;
        this.maskAvailable = maskAvailable;
        this.vetoMaskAvailable = vetoMaskAvailable;
        this.objectScoreAvailable = objectScoreAvailable;
        this.metricAvailable = metricAvailable;
        this.thresholdFeatureCount = thresholdFeatureCount;
    }

    public static CorrectionResult initial() {
        return new CorrectionResult(true, false, false, false, false, false, 0);
    }

    public boolean hasInput(CorrectionFeature.InputType type) {
        if (type == null) return false;
        if (type == CorrectionFeature.InputType.SOURCE_IMAGE) return sourceImageAvailable;
        if (type == CorrectionFeature.InputType.CORRECTED_IMAGE) return correctedImageAvailable;
        if (type == CorrectionFeature.InputType.MASK) return maskAvailable;
        return vetoMaskAvailable;
    }

    public boolean hasVetoMask() {
        return vetoMaskAvailable;
    }

    public int getThresholdFeatureCount() {
        return thresholdFeatureCount;
    }

    public CorrectionResult after(CorrectionFeature feature) {
        boolean corrected = correctedImageAvailable;
        boolean mask = maskAvailable;
        boolean veto = vetoMaskAvailable;
        boolean objectScore = objectScoreAvailable;
        boolean metric = metricAvailable;
        int thresholds = thresholdFeatureCount + (feature != null && feature.isThresholdFeature() ? 1 : 0);

        if (feature != null) {
            if (feature.getOutputType() == CorrectionFeature.OutputType.CORRECTED_IMAGE) {
                corrected = true;
            } else if (feature.getOutputType() == CorrectionFeature.OutputType.MASK) {
                mask = true;
            } else if (feature.getOutputType() == CorrectionFeature.OutputType.VETO_MASK) {
                veto = true;
            } else if (feature.getOutputType() == CorrectionFeature.OutputType.OBJECT_SCORE) {
                objectScore = true;
            } else if (feature.getOutputType() == CorrectionFeature.OutputType.METRIC) {
                metric = true;
            }
        }

        return new CorrectionResult(sourceImageAvailable, corrected, mask, veto, objectScore, metric, thresholds);
    }
}
