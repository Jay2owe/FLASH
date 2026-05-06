package flash.pipeline.decontamination.features;

import flash.pipeline.decontamination.CorrectionFeatureRegistry;
import flash.pipeline.decontamination.CorrectionPipeline;
import flash.pipeline.decontamination.SpectralDecontaminationConfig;
import ij.ImagePlus;
import ij.ImageStack;
import ij.process.ByteProcessor;
import ij.process.ShortProcessor;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class LocalAutofluorescenceFeaturesTest {

    private final CorrectionFeatureRegistry registry = CorrectionFeatureRegistry.getDefault();

    @Test
    public void localKHandlesSpatiallyVaryingAutofluorescenceBetterThanGlobalRatio() {
        int[] autofluorescence = new int[]{
                100, 100, 100, 100, 100, 100,
                100, 100, 100, 100, 100, 100
        };
        int[] target = new int[]{
                20, 20, 20, 80, 80, 80,
                20, 20, 20, 80, 80, 80
        };
        int[] cleanTarget = new int[target.length];
        ImagePlus source = multiChannelImage(6, 2, target, autofluorescence);

        SpectralDecontaminationConfig config = autofluorescenceConfig();

        CorrectionPipeline globalPipeline = new CorrectionPipeline();
        globalPipeline.setFeatureIds(strings(GlobalRatioCorrectionFeature.ID));
        CorrectionPipeline.ExecutionState globalState = CorrectionPipeline.ExecutionState.create(source, config);
        globalState.setFeatureSettings(GlobalRatioCorrectionFeature.ID,
                new GlobalRatioCorrectionFeature.Settings()
                        .setQuietTargetPercentile(100.0)
                        .toPipelineSettings());
        globalPipeline.execute(registry, globalState);

        CorrectionPipeline localPipeline = new CorrectionPipeline();
        localPipeline.setFeatureIds(strings(LocalKCorrectionFeature.ID));
        CorrectionPipeline.ExecutionState localState = CorrectionPipeline.ExecutionState.create(source, config);
        localState.setFeatureSettings(LocalKCorrectionFeature.ID,
                new LocalKCorrectionFeature.Settings()
                        .setQuietTargetPercentile(100.0)
                        .setWindowRadius(1)
                        .setMinWindowFitPixels(3)
                        .toPipelineSettings());
        localPipeline.execute(registry, localState);

        double globalError = meanAbsoluteError(unsignedShortPixels(globalState.getCorrectedImage()), cleanTarget);
        double localError = meanAbsoluteError(unsignedShortPixels(localState.getCorrectedImage()), cleanTarget);

        assertTrue(localError < globalError);
        assertTrue(localError <= 5.0);
    }

    @Test
    public void globalRatioExcludesSaturatedPixelsFromCoefficientFit() {
        ImagePlus source = multiChannelImage(2, 2,
                new int[]{25, 50, 65535, 100},
                new int[]{50, 100, 65535, 200});
        SpectralDecontaminationConfig config = autofluorescenceConfig();

        CorrectionPipeline pipeline = new CorrectionPipeline();
        pipeline.setFeatureIds(strings(GlobalRatioCorrectionFeature.ID));

        CorrectionPipeline.ExecutionState state = CorrectionPipeline.ExecutionState.create(source, config);
        state.setFeatureSettings(GlobalRatioCorrectionFeature.ID,
                new GlobalRatioCorrectionFeature.Settings()
                        .setQuietTargetPercentile(100.0)
                        .toPipelineSettings());

        pipeline.execute(registry, state);

        double coefficient = Double.parseDouble(
                state.getFeatureSummaries().get(0).getValues().get("coefficient_channel_2"));
        assertEquals(0.5, coefficient, 0.0001);
        assertEquals("1", state.getFeatureSummaries().get(0).getValues().get("saturated_pixels_excluded"));
        assertArrayEquals(new int[]{0, 0, 32768, 0}, unsignedShortPixels(state.getCorrectedImage()));
    }

    @Test
    public void localCorrelationVetoRemovesPixelsThatTrackAutofluorescence() {
        ImagePlus source = multiChannelImage(5, 1,
                new int[]{10, 20, 30, 40, 50},
                new int[]{10, 20, 30, 40, 50});
        ImagePlus corrected = singleChannelShortImage(5, 1, new int[]{10, 20, 30, 40, 50});
        ImagePlus mask = singleChannelMaskImage(5, 1, new int[]{255, 255, 255, 255, 255});

        SpectralDecontaminationConfig config = autofluorescenceConfig();
        CorrectionPipeline.ExecutionState state = CorrectionPipeline.ExecutionState.create(source, config);
        state.setCorrectedImage(corrected);
        state.setMaskImage(mask);
        state.setFeatureSettings(LocalCorrelationVetoFeature.ID,
                new LocalCorrelationVetoFeature.Settings()
                        .setWindowRadius(1)
                        .setMinWindowPixels(2)
                        .setCorrelationThreshold(0.95)
                        .toPipelineSettings());

        new LocalCorrelationVetoFeature().apply(state);
        new VetoMasksFeature().apply(state);

        int[] filteredMask = unsignedBytePixels(state.getMaskImage());
        assertEquals(0, filteredMask[1]);
        assertEquals(0, filteredMask[2]);
        assertEquals(0, filteredMask[3]);
        assertTrue(Integer.parseInt(
                state.getFeatureSummaries().get(0).getValues().get("veto_pixels")) >= 3);
    }

    private static SpectralDecontaminationConfig autofluorescenceConfig() {
        SpectralDecontaminationConfig config = new SpectralDecontaminationConfig();
        config.setTargetChannelIndex(0);
        config.setGoal(SpectralDecontaminationConfig.Goal.CREATE_CLEANED_MASK);
        config.setAutofluorescenceChannelIndexes(Arrays.asList(Integer.valueOf(1)));
        return config;
    }

    private static ImagePlus multiChannelImage(int width, int height, int[]... channels) {
        ImageStack stack = new ImageStack(width, height);
        for (int[] channel : channels) {
            stack.addSlice(new ShortProcessor(width, height, toShorts(channel), null));
        }
        ImagePlus image = new ImagePlus("synthetic", stack);
        image.setDimensions(channels.length, 1, 1);
        image.setOpenAsHyperStack(true);
        return image;
    }

    private static ImagePlus singleChannelShortImage(int width, int height, int[] pixels) {
        ImageStack stack = new ImageStack(width, height);
        stack.addSlice(new ShortProcessor(width, height, toShorts(pixels), null));
        ImagePlus image = new ImagePlus("corrected", stack);
        image.setDimensions(1, 1, 1);
        return image;
    }

    private static ImagePlus singleChannelMaskImage(int width, int height, int[] pixels) {
        ImageStack stack = new ImageStack(width, height);
        stack.addSlice(new ByteProcessor(width, height, toBytes(pixels), null));
        ImagePlus image = new ImagePlus("mask", stack);
        image.setDimensions(1, 1, 1);
        return image;
    }

    private static double meanAbsoluteError(int[] actual, int[] expected) {
        double sum = 0.0;
        for (int i = 0; i < actual.length; i++) {
            sum += Math.abs(actual[i] - expected[i]);
        }
        return sum / (double) actual.length;
    }

    private static short[] toShorts(int[] values) {
        short[] out = new short[values.length];
        for (int i = 0; i < values.length; i++) {
            out[i] = (short) values[i];
        }
        return out;
    }

    private static byte[] toBytes(int[] values) {
        byte[] out = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            out[i] = (byte) values[i];
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
