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
 * Builds a veto mask from very high contaminant intensities.
 */
public class HardVetoFeature implements CorrectionPipeline.ExecutableFeature {

    public static final String ID = "hard_veto";

    private static final String THRESHOLD_MODE = "threshold_mode";
    private static final String THRESHOLD_VALUE = "threshold_value";
    private static final String THRESHOLD_PERCENTILE = "threshold_percentile";

    private final Set<RequiredChannel> requiredChannels =
            Collections.singleton(RequiredChannel.CONTAMINANT);

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public String getDisplayName() {
        return "Hard veto";
    }

    @Override
    public String getDescription() {
        return "Builds a veto mask where contaminant channels are very high.";
    }

    @Override
    public InputType getRequiredInputType() {
        return InputType.SOURCE_IMAGE;
    }

    @Override
    public OutputType getOutputType() {
        return OutputType.VETO_MASK;
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
        return false;
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
        CorrectionImageOps.require16Bit(source, "Source");
        List<Integer> contaminantChannels = CorrectionImageOps.contaminantChannels(config);
        if (contaminantChannels.isEmpty()) {
            throw new IllegalArgumentException("Hard veto requires at least one contaminant channel.");
        }

        Settings settings = Settings.from(state.getFeatureSettings(ID));
        double[] thresholds = new double[contaminantChannels.size()];
        for (int i = 0; i < contaminantChannels.size(); i++) {
            thresholds[i] = CorrectionImageOps.thresholdForMode(
                    CorrectionImageOps.histogramForChannel(source, contaminantChannels.get(i).intValue()),
                    settings.getThresholdMode(),
                    settings.getThresholdPercentile(),
                    settings.getThresholdValue());
        }

        int planeCount = CorrectionImageOps.planeCount(source);
        byte[][] vetoPlanes = new byte[planeCount][];
        long vetoPixels = 0L;
        long totalPixels = 0L;

        for (int plane = 0; plane < planeCount; plane++) {
            short[][] contaminantPixels = new short[contaminantChannels.size()][];
            for (int i = 0; i < contaminantChannels.size(); i++) {
                contaminantPixels[i] = CorrectionImageOps.channelPlanePixels(
                        source,
                        contaminantChannels.get(i).intValue(),
                        plane);
            }

            byte[] veto = new byte[source.getWidth() * source.getHeight()];
            for (int pixel = 0; pixel < veto.length; pixel++) {
                totalPixels++;
                boolean reject = false;
                for (int i = 0; i < contaminantPixels.length; i++) {
                    reject = (contaminantPixels[i][pixel] & 0xffff) >= thresholds[i];
                    if (reject) break;
                }
                if (reject) {
                    veto[pixel] = (byte) 255;
                    vetoPixels++;
                }
            }
            vetoPlanes[plane] = veto;
        }

        state.setVetoMaskImage(CorrectionImageOps.createMaskImageLike(
                source,
                "hard_veto_mask",
                vetoPlanes));

        CorrectionPipeline.FeatureSummary summary = new CorrectionPipeline.FeatureSummary(ID, getDisplayName())
                .put("threshold_mode", settings.getThresholdMode().getKey())
                .putInt("veto_pixels", vetoPixels)
                .putInt("total_pixels", totalPixels)
                .putDouble("veto_fraction", totalPixels <= 0L ? 0.0 : (double) vetoPixels / (double) totalPixels);
        for (int i = 0; i < contaminantChannels.size(); i++) {
            summary.putDouble("threshold_"
                    + CorrectionImageOps.channelLabel(contaminantChannels.get(i).intValue()),
                    thresholds[i]);
        }
        state.addSummary(summary);
    }

    public static final class Settings {
        private CorrectionImageOps.ThresholdMode thresholdMode =
                CorrectionImageOps.ThresholdMode.PERCENTILE;
        private double thresholdValue = 0.0;
        private double thresholdPercentile = 99.0;

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
