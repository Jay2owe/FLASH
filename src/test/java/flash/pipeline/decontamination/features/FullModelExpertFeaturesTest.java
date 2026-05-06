package flash.pipeline.decontamination.features;

import flash.pipeline.bin.BinConfig;
import flash.pipeline.decontamination.CorrectionFeatureRegistry;
import flash.pipeline.decontamination.CorrectionPipeline;
import flash.pipeline.decontamination.SpectralDecontaminationConfig;
import flash.pipeline.decontamination.SpectralOutputWriter;
import flash.pipeline.decontamination.SpectralPreviewRenderer;
import flash.pipeline.decontamination.SpectralPreviewSelector;
import ij.ImagePlus;
import ij.ImageStack;
import ij.process.ShortProcessor;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class FullModelExpertFeaturesTest {

    private final CorrectionFeatureRegistry registry = CorrectionFeatureRegistry.getDefault();

    @Test
    public void fullForwardModelReducesCombinedAutofluorescenceAndBleedThrough() {
        int[] autofluorescence = new int[]{
                20, 40, 60, 80, 100, 120,
                20, 40, 60, 80, 100, 120
        };
        int[] bleedOneTrue = new int[]{
                0, 0, 200, 200, 0, 0,
                50, 50, 0, 0, 150, 150
        };
        int[] bleedTwoTrue = new int[]{
                0, 120, 0, 120, 0, 120,
                0, 60, 0, 60, 0, 60
        };
        int[] cleanTarget = new int[]{
                0, 0, 0, 40, 0, 160,
                20, 0, 0, 60, 180, 0
        };

        int[] observedTarget = mixTarget(cleanTarget, autofluorescence, bleedOneTrue, bleedTwoTrue);
        int[] observedBleedOne = mixBleed(bleedOneTrue, autofluorescence, 0.7);
        int[] observedBleedTwo = mixBleed(bleedTwoTrue, autofluorescence, 0.4);
        ImagePlus source = multiChannelImage(6, 2, observedTarget, observedBleedOne, observedBleedTwo, autofluorescence);

        SpectralDecontaminationConfig config = new SpectralDecontaminationConfig();
        config.setTargetChannelIndex(0);
        config.setGoal(SpectralDecontaminationConfig.Goal.CREATE_CLEANED_IMAGE);
        config.setBleedThroughChannelIndexes(Arrays.asList(Integer.valueOf(1), Integer.valueOf(2)));
        config.setAutofluorescenceChannelIndexes(Arrays.asList(Integer.valueOf(3)));

        CorrectionPipeline pipeline = new CorrectionPipeline();
        pipeline.setExpertMode(true);
        pipeline.setFeatureIds(Arrays.asList(FullForwardModelFeature.ID));

        CorrectionPipeline.ExecutionState state = CorrectionPipeline.ExecutionState.create(source, config);
        state.setFeatureSettings(FullForwardModelFeature.ID,
                new FullForwardModelFeature.Settings()
                        .setWindowRadius(1)
                        .setQuietTargetPercentile(70.0)
                        .setSourceQuietPercentile(55.0)
                        .setSourceBrightPercentile(75.0)
                        .setMinLocalFitPixels(3)
                        .setMinBleedFitPixels(4)
                        .setWriteParameterMaps(true)
                        .toPipelineSettings());

        pipeline.execute(registry, state);

        double rawError = meanAbsoluteError(observedTarget, cleanTarget);
        double correctedError = meanAbsoluteError(unsignedShortPixels(state.getCorrectedImage()), cleanTarget);

        assertTrue(correctedError < rawError);
        assertTrue(correctedError <= 25.0);
        assertTrue(state.getParameterMaps().containsKey(FullForwardModelFeature.parameterMapKeyForChannel(3)));

        List<Map<String, String>> coefficientRows = SpectralOutputWriter.buildCoefficientRows(
                0,
                "synthetic",
                "Condition A",
                "experimental",
                SpectralOutputWriter.RunMetadata.fromConfig(config, registry),
                "processed",
                state.getFeatureSummaries());
        assertTrue(hasMetric(coefficientRows, "bleed_coefficient_channel_2"));
        assertTrue(hasMetric(coefficientRows, "bleed_coefficient_channel_3"));
        assertTrue(hasMetric(coefficientRows, "source_purification_coefficient_channel_2_channel_4"));
    }

    @Test
    public void envelopeCorrectionRemovesResidualBrightTail() {
        int[] contaminant = new int[]{
                0, 20, 40, 60, 80, 100,
                0, 30, 60, 90, 120, 150
        };
        int[] cleanTarget = new int[]{
                0, 0, 0, 0, 45, 70,
                0, 0, 10, 0, 55, 0
        };
        int[] correctedInput = new int[cleanTarget.length];
        for (int i = 0; i < correctedInput.length; i++) {
            correctedInput[i] = cleanTarget[i] + (int) Math.round(contaminant[i] * 0.35);
        }

        ImagePlus source = multiChannelImage(6, 2, new int[cleanTarget.length], contaminant);
        ImagePlus corrected = singleChannelImage(6, 2, correctedInput);

        SpectralDecontaminationConfig config = new SpectralDecontaminationConfig();
        config.setTargetChannelIndex(0);
        config.setGoal(SpectralDecontaminationConfig.Goal.CREATE_CLEANED_IMAGE);
        config.setBleedThroughChannelIndexes(Arrays.asList(Integer.valueOf(1)));

        CorrectionPipeline.ExecutionState state = CorrectionPipeline.ExecutionState.create(source, config);
        state.setCorrectedImage(corrected);
        state.setFeatureSettings(EnvelopeCorrectionFeature.ID,
                new EnvelopeCorrectionFeature.Settings()
                        .setDominantContaminantPercentile(35.0)
                        .setEnvelopePercentile(100.0)
                        .setBinCount(6)
                        .setMinBinPixels(2)
                        .toPipelineSettings());

        new EnvelopeCorrectionFeature().apply(state);

        double beforeError = meanAbsoluteError(correctedInput, cleanTarget);
        double afterError = meanAbsoluteError(unsignedShortPixels(state.getCorrectedImage()), cleanTarget);

        assertTrue(afterError < beforeError);
    }

    @Test
    public void previewMetricsExposeWarningsFromSmallExpertFitPools() {
        ImagePlus source = multiChannelImage(2, 1,
                new int[]{12, 20},
                new int[]{8, 12},
                new int[]{6, 10});

        SpectralDecontaminationConfig config = new SpectralDecontaminationConfig();
        config.setTargetChannelIndex(0);
        config.setGoal(SpectralDecontaminationConfig.Goal.CREATE_CLEANED_IMAGE);
        config.setBleedThroughChannelIndexes(Arrays.asList(Integer.valueOf(1)));
        config.setAutofluorescenceChannelIndexes(Arrays.asList(Integer.valueOf(2)));

        CorrectionPipeline pipeline = new CorrectionPipeline();
        pipeline.setExpertMode(true);
        pipeline.setFeatureIds(Arrays.asList(FullForwardModelFeature.ID));
        CorrectionPipeline.ExecutionState state = CorrectionPipeline.ExecutionState.create(source, config);
        state.setFeatureSettings(FullForwardModelFeature.ID,
                new FullForwardModelFeature.Settings()
                        .setWindowRadius(1)
                        .setQuietTargetPercentile(100.0)
                        .setSourceQuietPercentile(100.0)
                        .setSourceBrightPercentile(50.0)
                        .setMinLocalFitPixels(3)
                        .setMinBleedFitPixels(3)
                        .toPipelineSettings());
        pipeline.execute(registry, state);

        SpectralPreviewRenderer.PreviewMetrics metrics = SpectralPreviewRenderer.PreviewMetrics.from(
                source,
                new BinConfigStub("Target", "Bleed", "Auto"),
                state.getFeatureSummaries(),
                new SpectralPreviewSelector.ImageScores(0.0, 0.0, 0.0, 0.0, -1));

        assertFalse(metrics.warningLines.isEmpty());
    }

    private static boolean hasMetric(List<Map<String, String>> rows, String metric) {
        for (Map<String, String> row : rows) {
            if (metric.equals(row.get("Metric"))) {
                return true;
            }
        }
        return false;
    }

    private static int[] mixTarget(int[] cleanTarget,
                                   int[] autofluorescence,
                                   int[] bleedOneTrue,
                                   int[] bleedTwoTrue) {
        int[] observed = new int[cleanTarget.length];
        for (int i = 0; i < observed.length; i++) {
            observed[i] = cleanTarget[i]
                    + (int) Math.round(autofluorescence[i] * 0.5)
                    + (int) Math.round(bleedOneTrue[i] * 0.25)
                    + (int) Math.round(bleedTwoTrue[i] * 0.15);
        }
        return observed;
    }

    private static int[] mixBleed(int[] bleedTrue, int[] autofluorescence, double coefficient) {
        int[] observed = new int[bleedTrue.length];
        for (int i = 0; i < observed.length; i++) {
            observed[i] = bleedTrue[i] + (int) Math.round(autofluorescence[i] * coefficient);
        }
        return observed;
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

    private static ImagePlus singleChannelImage(int width, int height, int[] pixels) {
        ImageStack stack = new ImageStack(width, height);
        stack.addSlice(new ShortProcessor(width, height, toShorts(pixels), null));
        ImagePlus image = new ImagePlus("corrected", stack);
        image.setDimensions(1, 1, 1);
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

    private static double meanAbsoluteError(int[] actual, int[] expected) {
        double sum = 0.0;
        for (int i = 0; i < actual.length; i++) {
            sum += Math.abs(actual[i] - expected[i]);
        }
        return sum / (double) actual.length;
    }

    private static final class BinConfigStub extends BinConfig {
        BinConfigStub(String... channelNames) {
            this.channelNames.addAll(Arrays.asList(channelNames));
        }
    }
}
