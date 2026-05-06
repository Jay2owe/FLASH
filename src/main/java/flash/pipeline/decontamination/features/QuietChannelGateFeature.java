package flash.pipeline.decontamination.features;

import flash.pipeline.decontamination.CorrectionFeature;
import flash.pipeline.decontamination.CorrectionImageOps;
import flash.pipeline.decontamination.CorrectionPipeline;
import flash.pipeline.decontamination.SpectralDecontaminationConfig;
import ij.ImagePlus;

import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * Builds a mask from a corrected target image while requiring low contaminant signal.
 */
public class QuietChannelGateFeature implements CorrectionPipeline.ExecutableFeature {

    public static final String ID = "quiet_channel_gate";

    private static final String TARGET_MODE = "target_threshold_mode";
    private static final String TARGET_VALUE = "target_threshold_value";
    private static final String TARGET_PERCENTILE = "target_threshold_percentile";
    private static final String CONTAMINANT_MODE = "contaminant_threshold_mode";
    private static final String CONTAMINANT_VALUE = "contaminant_threshold_value";
    private static final String CONTAMINANT_PERCENTILE = "contaminant_threshold_percentile";

    private final Set<RequiredChannel> requiredChannels =
            Collections.singleton(RequiredChannel.CONTAMINANT);

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public String getDisplayName() {
        return "Quiet-channel gate";
    }

    @Override
    public String getDescription() {
        return "Keeps target-positive pixels only where contaminant channels stay low.";
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
        SpectralDecontaminationConfig config = state.getConfig();
        if (config == null) {
            throw new IllegalArgumentException("Spectral Decontamination config is required.");
        }

        ImagePlus source = state.getSourceImage();
        ImagePlus corrected = state.getCorrectedImage();
        CorrectionImageOps.require16Bit(source, "Source");
        CorrectionImageOps.requireSingleChannel16Bit(corrected, "Corrected");

        List<Integer> contaminantChannels = CorrectionImageOps.contaminantChannels(config);
        if (contaminantChannels.isEmpty()) {
            throw new IllegalArgumentException("Quiet-channel gate requires at least one contaminant channel.");
        }

        Settings settings = Settings.from(state.getFeatureSettings(ID));
        double targetThreshold = CorrectionImageOps.thresholdForMode(
                CorrectionImageOps.histogramForSingleChannel(corrected),
                settings.getTargetThresholdMode(),
                settings.getTargetThresholdPercentile(),
                settings.getTargetThresholdValue());

        double[] contaminantThresholds = new double[contaminantChannels.size()];
        for (int i = 0; i < contaminantChannels.size(); i++) {
            contaminantThresholds[i] = CorrectionImageOps.thresholdForMode(
                    CorrectionImageOps.histogramForChannel(source, contaminantChannels.get(i).intValue()),
                    settings.getContaminantThresholdMode(),
                    settings.getContaminantThresholdPercentile(),
                    settings.getContaminantThresholdValue());
        }

        int planeCount = CorrectionImageOps.planeCount(corrected);
        byte[][] maskPlanes = new byte[planeCount][];
        long keptPixels = 0L;
        long totalPixels = 0L;

        for (int plane = 0; plane < planeCount; plane++) {
            short[] correctedPixels = CorrectionImageOps.singleChannelPlanePixels(corrected, plane);
            short[][] contaminantPixels = new short[contaminantChannels.size()][];
            for (int i = 0; i < contaminantChannels.size(); i++) {
                contaminantPixels[i] = CorrectionImageOps.channelPlanePixels(
                        source,
                        contaminantChannels.get(i).intValue(),
                        plane);
            }

            byte[] mask = new byte[correctedPixels.length];
            for (int pixel = 0; pixel < correctedPixels.length; pixel++) {
                totalPixels++;
                boolean keep = (correctedPixels[pixel] & 0xffff) >= targetThreshold;
                for (int i = 0; keep && i < contaminantPixels.length; i++) {
                    keep = (contaminantPixels[i][pixel] & 0xffff) <= contaminantThresholds[i];
                }
                if (keep) {
                    mask[pixel] = (byte) 255;
                    keptPixels++;
                }
            }
            maskPlanes[plane] = mask;
        }

        state.setMaskImage(CorrectionImageOps.createMaskImageLike(
                corrected,
                "quiet_channel_gate_mask",
                maskPlanes));

        CorrectionPipeline.FeatureSummary summary = new CorrectionPipeline.FeatureSummary(ID, getDisplayName())
                .put("target_threshold_mode", settings.getTargetThresholdMode().getKey())
                .put("contaminant_threshold_mode", settings.getContaminantThresholdMode().getKey())
                .putDouble("target_threshold", targetThreshold)
                .putInt("kept_pixels", keptPixels)
                .putInt("total_pixels", totalPixels)
                .putDouble("kept_fraction", totalPixels <= 0L ? 0.0 : (double) keptPixels / (double) totalPixels);
        for (int i = 0; i < contaminantChannels.size(); i++) {
            summary.putDouble("contaminant_threshold_"
                    + CorrectionImageOps.channelLabel(contaminantChannels.get(i).intValue()),
                    contaminantThresholds[i]);
        }
        state.addSummary(summary);
    }

    public static final class Settings {
        private CorrectionImageOps.ThresholdMode targetThresholdMode =
                CorrectionImageOps.ThresholdMode.PERCENTILE;
        private double targetThresholdValue = 0.0;
        private double targetThresholdPercentile = 90.0;
        private CorrectionImageOps.ThresholdMode contaminantThresholdMode =
                CorrectionImageOps.ThresholdMode.MEDIAN;
        private double contaminantThresholdValue = 0.0;
        private double contaminantThresholdPercentile = 50.0;

        public CorrectionImageOps.ThresholdMode getTargetThresholdMode() {
            return targetThresholdMode;
        }

        public Settings setTargetThresholdMode(CorrectionImageOps.ThresholdMode targetThresholdMode) {
            this.targetThresholdMode = targetThresholdMode == null
                    ? CorrectionImageOps.ThresholdMode.PERCENTILE
                    : targetThresholdMode;
            return this;
        }

        public double getTargetThresholdValue() {
            return targetThresholdValue;
        }

        public Settings setTargetThresholdValue(double targetThresholdValue) {
            this.targetThresholdValue = targetThresholdValue;
            return this;
        }

        public double getTargetThresholdPercentile() {
            return targetThresholdPercentile;
        }

        public Settings setTargetThresholdPercentile(double targetThresholdPercentile) {
            this.targetThresholdPercentile = targetThresholdPercentile;
            return this;
        }

        public CorrectionImageOps.ThresholdMode getContaminantThresholdMode() {
            return contaminantThresholdMode;
        }

        public Settings setContaminantThresholdMode(CorrectionImageOps.ThresholdMode contaminantThresholdMode) {
            this.contaminantThresholdMode = contaminantThresholdMode == null
                    ? CorrectionImageOps.ThresholdMode.MEDIAN
                    : contaminantThresholdMode;
            return this;
        }

        public double getContaminantThresholdValue() {
            return contaminantThresholdValue;
        }

        public Settings setContaminantThresholdValue(double contaminantThresholdValue) {
            this.contaminantThresholdValue = contaminantThresholdValue;
            return this;
        }

        public double getContaminantThresholdPercentile() {
            return contaminantThresholdPercentile;
        }

        public Settings setContaminantThresholdPercentile(double contaminantThresholdPercentile) {
            this.contaminantThresholdPercentile = contaminantThresholdPercentile;
            return this;
        }

        public CorrectionPipeline.Settings toPipelineSettings() {
            return new CorrectionPipeline.Settings()
                    .put(TARGET_MODE, targetThresholdMode.getKey())
                    .putDouble(TARGET_VALUE, targetThresholdValue)
                    .putDouble(TARGET_PERCENTILE, targetThresholdPercentile)
                    .put(CONTAMINANT_MODE, contaminantThresholdMode.getKey())
                    .putDouble(CONTAMINANT_VALUE, contaminantThresholdValue)
                    .putDouble(CONTAMINANT_PERCENTILE, contaminantThresholdPercentile);
        }

        public static Settings from(CorrectionPipeline.Settings raw) {
            Settings settings = new Settings();
            if (raw == null) return settings;
            settings.setTargetThresholdMode(CorrectionImageOps.ThresholdMode.fromKey(
                    raw.get(TARGET_MODE, settings.targetThresholdMode.getKey()),
                    settings.targetThresholdMode));
            settings.setTargetThresholdValue(raw.getDouble(TARGET_VALUE, settings.targetThresholdValue));
            settings.setTargetThresholdPercentile(raw.getDouble(
                    TARGET_PERCENTILE,
                    settings.targetThresholdPercentile));
            settings.setContaminantThresholdMode(CorrectionImageOps.ThresholdMode.fromKey(
                    raw.get(CONTAMINANT_MODE, settings.contaminantThresholdMode.getKey()),
                    settings.contaminantThresholdMode));
            settings.setContaminantThresholdValue(raw.getDouble(
                    CONTAMINANT_VALUE,
                    settings.contaminantThresholdValue));
            settings.setContaminantThresholdPercentile(raw.getDouble(
                    CONTAMINANT_PERCENTILE,
                    settings.contaminantThresholdPercentile));
            return settings;
        }
    }
}
