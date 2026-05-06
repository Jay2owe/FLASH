package flash.pipeline.decontamination.features;

import flash.pipeline.decontamination.CorrectionFeatureRegistry;
import flash.pipeline.decontamination.CorrectionImageOps;
import flash.pipeline.decontamination.CorrectionPipeline;
import flash.pipeline.decontamination.SpectralDecontaminationConfig;
import ij.ImagePlus;
import ij.ImageStack;
import ij.process.ShortProcessor;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

public class BasicCorrectionFeaturesTest {

    private final CorrectionFeatureRegistry registry = CorrectionFeatureRegistry.getDefault();

    @Test
    public void linearUnmixingManualWeightsSubtractExpectedSignal() {
        ImagePlus source = multiChannelImage(2, 2,
                new int[]{50, 50, 100, 120},
                new int[]{100, 100, 0, 0});
        SpectralDecontaminationConfig config = baseConfig();

        CorrectionPipeline pipeline = new CorrectionPipeline();
        pipeline.setFeatureIds(strings(LinearUnmixingFeature.ID));

        CorrectionPipeline.ExecutionState state = CorrectionPipeline.ExecutionState.create(source, config);
        state.setFeatureSettings(LinearUnmixingFeature.ID,
                new LinearUnmixingFeature.Settings()
                        .setWeightMode(LinearUnmixingFeature.WeightMode.MANUAL)
                        .setManualWeight(1, 0.5)
                        .toPipelineSettings());

        pipeline.execute(registry, state);

        assertArrayEquals(new int[]{0, 0, 100, 120}, unsignedShortPixels(state.getCorrectedImage()));
        assertEquals("manual", state.getFeatureSummaries().get(0).getValues().get("weight_mode"));
        assertEquals("0.500000", state.getFeatureSummaries().get(0).getValues().get("weight_channel_2"));
    }

    @Test
    public void linearUnmixingFittedWeightsReduceSyntheticBleedThrough() {
        ImagePlus source = multiChannelImage(2, 2,
                new int[]{50, 50, 100, 120},
                new int[]{100, 100, 0, 0});
        SpectralDecontaminationConfig config = baseConfig();

        CorrectionPipeline pipeline = new CorrectionPipeline();
        pipeline.setFeatureIds(strings(LinearUnmixingFeature.ID));

        CorrectionPipeline.ExecutionState state = CorrectionPipeline.ExecutionState.create(source, config);
        state.setFeatureSettings(LinearUnmixingFeature.ID,
                new LinearUnmixingFeature.Settings()
                        .setWeightMode(LinearUnmixingFeature.WeightMode.FITTED)
                        .setFitPercentile(50.0)
                        .toPipelineSettings());

        pipeline.execute(registry, state);

        assertArrayEquals(new int[]{0, 0, 100, 120}, unsignedShortPixels(state.getCorrectedImage()));
        double fittedWeight = Double.parseDouble(
                state.getFeatureSummaries().get(0).getValues().get("weight_channel_2"));
        assertEquals(0.5, fittedWeight, 0.0001);
        assertEquals("2", state.getFeatureSummaries().get(0).getValues().get("fit_pixel_count"));
    }

    @Test
    public void quietChannelGateRemovesPixelsWhereContaminantIsHigh() {
        ImagePlus source = multiChannelImage(2, 2,
                new int[]{100, 100, 100, 20},
                new int[]{10, 200, 5, 5});
        SpectralDecontaminationConfig config = baseConfig();

        CorrectionPipeline pipeline = new CorrectionPipeline();
        pipeline.setFeatureIds(strings(LinearUnmixingFeature.ID, QuietChannelGateFeature.ID));

        CorrectionPipeline.ExecutionState state = CorrectionPipeline.ExecutionState.create(source, config);
        state.setFeatureSettings(LinearUnmixingFeature.ID,
                new LinearUnmixingFeature.Settings()
                        .setWeightMode(LinearUnmixingFeature.WeightMode.MANUAL)
                        .setManualWeight(1, 0.0)
                        .toPipelineSettings());
        state.setFeatureSettings(QuietChannelGateFeature.ID,
                new QuietChannelGateFeature.Settings()
                        .setTargetThresholdMode(CorrectionImageOps.ThresholdMode.MEDIAN)
                        .setContaminantThresholdMode(CorrectionImageOps.ThresholdMode.PERCENTILE)
                        .setContaminantThresholdPercentile(75.0)
                        .toPipelineSettings());

        pipeline.execute(registry, state);

        assertNotNull(state.getMaskImage());
        assertArrayEquals(new int[]{255, 0, 255, 0}, unsignedBytePixels(state.getMaskImage()));
        assertEquals("100.000000", state.getFeatureSummaries().get(1).getValues().get("target_threshold"));
        assertEquals("10.000000",
                state.getFeatureSummaries().get(1).getValues().get("contaminant_threshold_channel_2"));
    }

    @Test
    public void hardVetoCreatesExpectedVetoMask() {
        ImagePlus source = multiChannelImage(2, 2,
                new int[]{1, 1, 1, 1},
                new int[]{10, 200, 5, 250});
        SpectralDecontaminationConfig config = baseConfig();

        CorrectionPipeline pipeline = new CorrectionPipeline();
        pipeline.setFeatureIds(strings(HardVetoFeature.ID));

        CorrectionPipeline.ExecutionState state = CorrectionPipeline.ExecutionState.create(source, config);
        state.setFeatureSettings(HardVetoFeature.ID,
                new HardVetoFeature.Settings()
                        .setThresholdMode(CorrectionImageOps.ThresholdMode.PERCENTILE)
                        .setThresholdPercentile(75.0)
                        .toPipelineSettings());

        pipeline.execute(registry, state);

        assertArrayEquals(new int[]{0, 255, 0, 255}, unsignedBytePixels(state.getVetoMaskImage()));
        assertEquals("200.000000", state.getFeatureSummaries().get(0).getValues().get("threshold_channel_2"));
    }

    @Test
    public void thresholdCorrectedTargetCreatesExpectedMask() {
        ImagePlus source = multiChannelImage(2, 2,
                new int[]{5, 10, 100, 200},
                new int[]{0, 0, 0, 0});
        SpectralDecontaminationConfig config = baseConfig();

        CorrectionPipeline pipeline = new CorrectionPipeline();
        pipeline.setFeatureIds(strings(LinearUnmixingFeature.ID, ThresholdCorrectedTargetFeature.ID));

        CorrectionPipeline.ExecutionState state = CorrectionPipeline.ExecutionState.create(source, config);
        state.setFeatureSettings(LinearUnmixingFeature.ID,
                new LinearUnmixingFeature.Settings()
                        .setWeightMode(LinearUnmixingFeature.WeightMode.MANUAL)
                        .setManualWeight(1, 0.0)
                        .toPipelineSettings());
        state.setFeatureSettings(ThresholdCorrectedTargetFeature.ID,
                new ThresholdCorrectedTargetFeature.Settings()
                        .setThresholdMode(CorrectionImageOps.ThresholdMode.MEDIAN)
                        .toPipelineSettings());

        pipeline.execute(registry, state);

        assertArrayEquals(new int[]{0, 0, 255, 255}, unsignedBytePixels(state.getMaskImage()));
        assertEquals("55.000000", state.getFeatureSummaries().get(1).getValues().get("threshold"));
    }

    private static SpectralDecontaminationConfig baseConfig() {
        SpectralDecontaminationConfig config = new SpectralDecontaminationConfig();
        config.setTargetChannelIndex(0);
        config.setGoal(SpectralDecontaminationConfig.Goal.CREATE_CLEANED_MASK);
        config.setBleedThroughChannelIndexes(Arrays.asList(Integer.valueOf(1)));
        return config;
    }

    private static ImagePlus multiChannelImage(int width, int height, int[]... channels) {
        ImageStack stack = new ImageStack(width, height);
        for (int[] channel : channels) {
            stack.addSlice(new ShortProcessor(width, height, toShorts(channel), null));
        }
        ImagePlus image = new ImagePlus("synthetic", stack);
        image.setDimensions(channels.length, 1, 1);
        return image;
    }

    private static short[] toShorts(int[] values) {
        short[] out = new short[values.length];
        for (int i = 0; i < values.length; i++) {
            out[i] = (short) values[i];
        }
        return out;
    }

    private static int[] unsignedShortPixels(ImagePlus image) {
        short[] pixels = (short[]) image.getStack().getProcessor(1).getPixels();
        int[] out = new int[pixels.length];
        for (int i = 0; i < pixels.length; i++) {
            out[i] = pixels[i] & 0xffff;
        }
        return out;
    }

    private static int[] unsignedBytePixels(ImagePlus image) {
        byte[] pixels = (byte[]) image.getStack().getProcessor(1).getPixels();
        int[] out = new int[pixels.length];
        for (int i = 0; i < pixels.length; i++) {
            out[i] = pixels[i] & 0xff;
        }
        return out;
    }

    private static List<String> strings(String... values) {
        List<String> out = new ArrayList<String>();
        out.addAll(Arrays.asList(values));
        return out;
    }
}
