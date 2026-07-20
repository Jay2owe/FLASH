package flash.pipeline.decontamination.features;

import flash.pipeline.decontamination.CorrectionFeatureRegistry;
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

/**
 * Covers the {@code weight_scale} subtraction-strength multiplier added to
 * {@link LinearUnmixingFeature}. Default 1.0 must reproduce the un-scaled result; other values
 * scale the (fitted or manual) weights before subtraction; negatives clamp to zero.
 */
public class LinearUnmixingWeightScaleTest {

    private final CorrectionFeatureRegistry registry = CorrectionFeatureRegistry.getDefault();

    @Test
    public void defaultScaleReproducesUnscaledResult() {
        // Fitted weight is 0.5; scale 1.0 (default) => corrected = target - 0.5*contaminant.
        int[] corrected = runManual(1.0, false, 0.0);
        assertArrayEquals(new int[]{0, 0, 100, 120}, corrected);
    }

    @Test
    public void halfScaleHalvesTheSubtraction() {
        // Manual weight 0.5, scale 0.5 => effective 0.25 => 50 - 0.25*100 = 25.
        int[] corrected = runManual(0.5, true, 0.5);
        assertArrayEquals(new int[]{25, 25, 100, 120}, corrected);
    }

    @Test
    public void negativeScaleClampsToZeroWeight() {
        // Manual weight 0.5, scale -2.0 => clamped to 0 => target passes through unchanged.
        int[] corrected = runManual(-2.0, true, 0.5);
        assertArrayEquals(new int[]{50, 50, 100, 120}, corrected);
    }

    @Test
    public void summaryRecordsWeightScale() {
        CorrectionPipeline.ExecutionState state = execute(0.75, true, 0.5);
        assertEquals("0.750000", state.getFeatureSummaries().get(0).getValues().get("weight_scale"));
    }

    private int[] runManual(double scale, boolean manual, double manualWeight) {
        return unsignedShortPixels(execute(scale, manual, manualWeight).getCorrectedImage());
    }

    private CorrectionPipeline.ExecutionState execute(double scale, boolean manual, double manualWeight) {
        ImagePlus source = multiChannelImage(2, 2,
                new int[]{50, 50, 100, 120},
                new int[]{100, 100, 0, 0});
        SpectralDecontaminationConfig config = baseConfig();

        CorrectionPipeline pipeline = new CorrectionPipeline();
        pipeline.setFeatureIds(strings(LinearUnmixingFeature.ID));

        LinearUnmixingFeature.Settings settings = new LinearUnmixingFeature.Settings()
                .setWeightScale(scale);
        if (manual) {
            settings.setWeightMode(LinearUnmixingFeature.WeightMode.MANUAL).setManualWeight(1, manualWeight);
        } else {
            settings.setWeightMode(LinearUnmixingFeature.WeightMode.FITTED).setFitPercentile(50.0);
        }

        CorrectionPipeline.ExecutionState state = CorrectionPipeline.ExecutionState.create(source, config);
        state.setFeatureSettings(LinearUnmixingFeature.ID, settings.toPipelineSettings());
        pipeline.execute(registry, state);
        return state;
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

    private static List<String> strings(String... values) {
        List<String> out = new ArrayList<String>();
        out.addAll(Arrays.asList(values));
        return out;
    }
}
