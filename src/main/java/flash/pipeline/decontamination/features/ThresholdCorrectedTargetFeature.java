package flash.pipeline.decontamination.features;

import flash.pipeline.decontamination.CorrectionFeature;
import flash.pipeline.decontamination.CorrectionImageOps;
import flash.pipeline.decontamination.CorrectionPipeline;
import ij.ImagePlus;

import java.util.Collections;
import java.util.Set;

/**
 * Thresholds the corrected target image into a binary mask.
 */
public class ThresholdCorrectedTargetFeature implements CorrectionPipeline.ExecutableFeature {

    public static final String ID = "threshold_corrected_target";

    private static final String THRESHOLD_MODE = "threshold_mode";
    private static final String THRESHOLD_VALUE = "threshold_value";
    private static final String THRESHOLD_PERCENTILE = "threshold_percentile";

    private final Set<RequiredChannel> requiredChannels =
            Collections.singleton(RequiredChannel.TARGET);

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public String getDisplayName() {
        return "Threshold corrected target";
    }

    @Override
    public String getDescription() {
        return "Creates a mask from a corrected target image.";
    }

    @Override
    public InputType getRequiredInputType() {
        return InputType.CORRECTED_IMAGE;
    }

    @Override
    public OutputType getOutputType() {
        return OutputType.MASK;
    }

    @Override
    public Set<RequiredChannel> getRequiredChannels() {
        return requiredChannels;
    }

    @Override
    public boolean requiresConditions() {
        return false;
    }

    @Override
    public boolean requiresControls() {
        return false;
    }

    @Override
    public boolean requiresExistingObjectMaps() {
        return false;
    }

    @Override
    public boolean canPreviewCheaply() {
        return true;
    }

    @Override
    public boolean isExpertOnly() {
        return false;
    }

    @Override
    public boolean isThresholdFeature() {
        return true;
    }

    @Override
    public boolean requiresVetoMask() {
        return false;
    }

    @Override
    public void apply(CorrectionPipeline.ExecutionState state) {
        if (state == null) {
            throw new IllegalArgumentException("Execution state is required.");
        }

        ImagePlus corrected = state.getCorrectedImage();
        CorrectionImageOps.requireSingleChannel16Bit(corrected, "Corrected");

        Settings settings = Settings.from(state.getFeatureSettings(ID));
        double threshold = CorrectionImageOps.thresholdForMode(
                CorrectionImageOps.histogramForSingleChannel(corrected),
                settings.getThresholdMode(),
                settings.getThresholdPercentile(),
                settings.getThresholdValue());

        int planeCount = CorrectionImageOps.planeCount(corrected);
        byte[][] maskPlanes = new byte[planeCount][];
        long positivePixels = 0L;
        long totalPixels = 0L;

        for (int plane = 0; plane < planeCount; plane++) {
            short[] correctedPixels = CorrectionImageOps.singleChannelPlanePixels(corrected, plane);
            byte[] mask = new byte[correctedPixels.length];
            for (int pixel = 0; pixel < correctedPixels.length; pixel++) {
                totalPixels++;
                boolean positive = (correctedPixels[pixel] & 0xffff) >= threshold;
                if (positive) {
                    mask[pixel] = (byte) 255;
                    positivePixels++;
                }
            }
            maskPlanes[plane] = mask;
        }

        state.setMaskImage(CorrectionImageOps.createMaskImageLike(
                corrected,
                "threshold_corrected_target_mask",
                maskPlanes));

        state.addSummary(new CorrectionPipeline.FeatureSummary(ID, getDisplayName())
                .put("threshold_mode", settings.getThresholdMode().getKey())
                .putDouble("threshold", threshold)
                .putInt("positive_pixels", positivePixels)
                .putInt("total_pixels", totalPixels)
                .putDouble("positive_fraction", totalPixels <= 0L
                        ? 0.0
                        : (double) positivePixels / (double) totalPixels));
    }

    public static final class Settings {
        private CorrectionImageOps.ThresholdMode thresholdMode =
                CorrectionImageOps.ThresholdMode.PERCENTILE;
        private double thresholdValue = 0.0;
        private double thresholdPercentile = 90.0;

        public CorrectionImageOps.ThresholdMode getThresholdMode() {
            return thresholdMode;
        }

        public Settings setThresholdMode(CorrectionImageOps.ThresholdMode thresholdMode) {
            this.thresholdMode = thresholdMode == null
                    ? CorrectionImageOps.ThresholdMode.PERCENTILE
                    : thresholdMode;
            return this;
        }

        public double getThresholdValue() {
            return thresholdValue;
        }

        public Settings setThresholdValue(double thresholdValue) {
            this.thresholdValue = thresholdValue;
            return this;
        }

        public double getThresholdPercentile() {
            return thresholdPercentile;
        }

        public Settings setThresholdPercentile(double thresholdPercentile) {
            this.thresholdPercentile = thresholdPercentile;
            return this;
        }

        public CorrectionPipeline.Settings toPipelineSettings() {
            return new CorrectionPipeline.Settings()
                    .put(THRESHOLD_MODE, thresholdMode.getKey())
                    .putDouble(THRESHOLD_VALUE, thresholdValue)
                    .putDouble(THRESHOLD_PERCENTILE, thresholdPercentile);
        }

        public static Settings from(CorrectionPipeline.Settings raw) {
            Settings settings = new Settings();
            if (raw == null) return settings;
            settings.setThresholdMode(CorrectionImageOps.ThresholdMode.fromKey(
                    raw.get(THRESHOLD_MODE, settings.thresholdMode.getKey()),
                    settings.thresholdMode));
            settings.setThresholdValue(raw.getDouble(THRESHOLD_VALUE, settings.thresholdValue));
            settings.setThresholdPercentile(raw.getDouble(
                    THRESHOLD_PERCENTILE,
                    settings.thresholdPercentile));
            return settings;
        }
    }
}
