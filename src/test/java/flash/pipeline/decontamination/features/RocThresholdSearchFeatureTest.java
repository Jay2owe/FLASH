package flash.pipeline.decontamination.features;

import flash.pipeline.decontamination.CorrectionFeatureRegistry;
import flash.pipeline.decontamination.CorrectionPipeline;
import flash.pipeline.decontamination.RocSearchResult;
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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class RocThresholdSearchFeatureTest {

    private final CorrectionFeatureRegistry registry = CorrectionFeatureRegistry.getDefault();

    @Test
    public void pipelineRejectsRocSearchWithoutConditionRoles() {
        SpectralDecontaminationConfig config = baseConfig();
        config.setBleedThroughChannelIndexes(Arrays.asList(Integer.valueOf(1)));

        CorrectionPipeline pipeline = new CorrectionPipeline();
        pipeline.setFeatureIds(strings(LinearUnmixingFeature.ID, RocThresholdSearchFeature.ID));

        List<String> errors = pipeline.validate(registry, config, false);

        assertFalse(errors.isEmpty());
        assertTrue(contains(errors, "ROC threshold search: Select at least one control condition first."));
        assertTrue(contains(errors, "ROC threshold search: Select at least one experimental condition first."));
    }

    @Test
    public void searchSelectsReproducibleThresholdUnderFalsePositiveLimit() {
        SpectralDecontaminationConfig config = baseConfigWithConditions();
        RocThresholdSearchFeature.Settings settings = new RocThresholdSearchFeature.Settings()
                .setMetric(RocThresholdSearchFeature.Metric.POSITIVE_VOLUME)
                .setAllowedFalsePositiveRate(0.0)
                .setThresholdMin(0.0)
                .setThresholdMax(100.0)
                .setThresholdStep(10.0);
        List<Double> thresholds = RocThresholdSearchFeature.buildThresholdGrid(settings);

        List<RocThresholdSearchFeature.MeasuredSample> measured =
                new ArrayList<RocThresholdSearchFeature.MeasuredSample>();
        measured.add(measure("control", true, false, image(10, 20, 30, 40), config, settings, thresholds));
        measured.add(measure("treated", false, true, image(10, 20, 90, 100), config, settings, thresholds));

        RocSearchResult result = RocThresholdSearchFeature.searchMeasured(
                measured,
                settings,
                thresholds,
                "unit_test");

        assertEquals(50.0, result.getSelectedThreshold(), 0.0);
        assertEquals(0.0, result.getSelectedFalsePositiveRate(), 0.0);
        assertEquals(1.0, result.getSelectedExperimentalRetention(), 0.0);
        assertEquals(thresholds.size(), result.getGridPointCount());
    }

    @Test
    public void applyUsesSelectedThresholdToCreateMask() {
        SpectralDecontaminationConfig config = baseConfigWithConditions();
        config.setFeatureSettings(RocThresholdSearchFeature.ID,
                new RocThresholdSearchFeature.Settings()
                        .setSelectedThreshold(50.0)
                        .setSelectedFalsePositiveRate(0.0)
                        .setSelectedExperimentalRetention(1.0)
                        .setControlImageCount(1)
                        .setExperimentalImageCount(1)
                        .setGridPointCount(11)
                        .toPipelineSettings());
        ImagePlus source = twoChannelImage(
                new int[]{10, 50, 60, 100},
                new int[]{0, 0, 0, 0});
        ImagePlus corrected = image(10, 50, 60, 100);

        CorrectionPipeline.ExecutionState state = CorrectionPipeline.ExecutionState.create(source, config);
        state.setCorrectedImage(corrected);

        new RocThresholdSearchFeature().apply(state);

        assertArrayEquals(new int[]{0, 255, 255, 255}, unsignedBytePixels(state.getMaskImage()));
        assertEquals("50.000000",
                state.getFeatureSummaries().get(0).getValues().get("selected_threshold"));
        assertEquals("0.000000",
                state.getFeatureSummaries().get(0).getValues().get("selected_false_positive_rate"));
    }

    private static RocThresholdSearchFeature.MeasuredSample measure(String condition,
                                                                    boolean control,
                                                                    boolean experimental,
                                                                    ImagePlus corrected,
                                                                    SpectralDecontaminationConfig config,
                                                                    RocThresholdSearchFeature.Settings settings,
                                                                    List<Double> thresholds) {
        return RocThresholdSearchFeature.measureSample(
                new RocThresholdSearchFeature.SearchSample(
                        condition,
                        condition,
                        control,
                        experimental,
                        corrected,
                        corrected),
                config,
                settings,
                thresholds);
    }

    private static SpectralDecontaminationConfig baseConfig() {
        SpectralDecontaminationConfig config = new SpectralDecontaminationConfig();
        config.setTargetChannelIndex(0);
        config.setGoal(SpectralDecontaminationConfig.Goal.CREATE_CLEANED_MASK);
        return config;
    }

    private static SpectralDecontaminationConfig baseConfigWithConditions() {
        SpectralDecontaminationConfig config = baseConfig();
        config.setBleedThroughChannelIndexes(Arrays.asList(Integer.valueOf(1)));
        config.setControlConditionNames(strings("control"));
        config.setExperimentalConditionNames(strings("treated"));
        return config;
    }

    private static ImagePlus image(int... pixels) {
        ImageStack stack = new ImageStack(2, 2);
        stack.addSlice(new ShortProcessor(2, 2, toShorts(pixels), null));
        ImagePlus image = new ImagePlus("corrected", stack);
        image.setDimensions(1, 1, 1);
        return image;
    }

    private static ImagePlus twoChannelImage(int[] target, int[] contaminant) {
        ImageStack stack = new ImageStack(2, 2);
        stack.addSlice(new ShortProcessor(2, 2, toShorts(target), null));
        stack.addSlice(new ShortProcessor(2, 2, toShorts(contaminant), null));
        ImagePlus image = new ImagePlus("source", stack);
        image.setDimensions(2, 1, 1);
        return image;
    }

    private static short[] toShorts(int... values) {
        short[] out = new short[values.length];
        for (int i = 0; i < values.length; i++) {
            out[i] = (short) values[i];
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

    private static boolean contains(List<String> errors, String expected) {
        for (String error : errors) {
            if (expected.equals(error)) return true;
        }
        return false;
    }

    private static List<String> strings(String... values) {
        List<String> out = new ArrayList<String>();
        out.addAll(Arrays.asList(values));
        return out;
    }
}
