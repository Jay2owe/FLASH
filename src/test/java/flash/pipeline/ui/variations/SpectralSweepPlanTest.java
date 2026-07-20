package flash.pipeline.ui.variations;

import flash.pipeline.decontamination.CorrectionPipeline;
import flash.pipeline.decontamination.SpectralDecontaminationConfig;

import org.junit.Test;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class SpectralSweepPlanTest {

    private static SpectralDecontaminationConfig base(String... featureIds) {
        SpectralDecontaminationConfig config = new SpectralDecontaminationConfig();
        config.setTargetChannelIndex(0);
        config.setBleedThroughChannelIndexes(Arrays.asList(Integer.valueOf(1)));
        config.setAutofluorescenceChannelIndexes(Arrays.asList(Integer.valueOf(2)));
        CorrectionPipeline pipeline = new CorrectionPipeline();
        pipeline.setFeatureIds(Arrays.asList(featureIds));
        config.setCorrectionPipeline(pipeline);
        return config;
    }

    private static ParameterSweep sweep(Map<ParameterKey, ParameterValueList> lists) {
        return new ParameterSweep(ParameterSweep.Method.SPECTRAL, lists, CropSpec.full(), "ch", "hash");
    }

    private static Map<ParameterKey, ParameterValueList> lists() {
        return new LinkedHashMap<ParameterKey, ParameterValueList>();
    }

    @Test
    public void everyDistinctComboIsExecutable() {
        Map<ParameterKey, ParameterValueList> lists = lists();
        lists.put(SpectralParameterId.STRENGTH, ParameterValueList.ofDoubles(0.5, 1.0, 1.5));
        SpectralSweepPlan plan = SpectralSweepPlan.forSweep(
                sweep(lists), base("linear_unmixing", "threshold_corrected_target", "size_filter"),
                null, Long.MAX_VALUE);
        assertEquals(3, plan.executableCount());
        assertEquals(0, plan.skippedCount());
    }

    @Test
    public void rocCombosAreSkipped() {
        Map<ParameterKey, ParameterValueList> lists = lists();
        lists.put(SpectralParameterId.STRENGTH, ParameterValueList.ofDoubles(0.5, 1.0));
        SpectralSweepPlan plan = SpectralSweepPlan.forSweep(
                sweep(lists), base("linear_unmixing", "roc_threshold_search", "size_filter"),
                null, Long.MAX_VALUE);
        assertEquals(0, plan.executableCount());
        assertEquals(2, plan.skippedCount());
    }

    @Test
    public void inertAfWindowRadiusIsDeduplicated() {
        // AF mode is global (no local_k in the stack), so the window radius has no effect: the
        // two radii resolve to the same config and should collapse to a single executable combo.
        Map<ParameterKey, ParameterValueList> lists = lists();
        lists.put(SpectralParameterId.AF_WINDOW_RADIUS, ParameterValueList.ofInts(2, 4));
        SpectralSweepPlan plan = SpectralSweepPlan.forSweep(
                sweep(lists), base("global_ratio_correction", "size_filter"),
                null, Long.MAX_VALUE);
        assertEquals(1, plan.executableCount());
        assertEquals(1, plan.skippedCount());
    }

    @Test
    public void validationDropsCombosWhenRegistryProvidedAndStackInvalid() {
        // A local_k_correction with no autofluorescence channel is invalid; the default registry
        // should reject it so nothing is executable.
        SpectralDecontaminationConfig b = base("local_k_correction", "size_filter");
        b.setAutofluorescenceChannelIndexes(Arrays.<Integer>asList());
        Map<ParameterKey, ParameterValueList> lists = lists();
        lists.put(SpectralParameterId.AF_WINDOW_RADIUS, ParameterValueList.ofInts(2));
        SpectralSweepPlan plan = SpectralSweepPlan.forSweep(
                sweep(lists), b,
                flash.pipeline.decontamination.CorrectionFeatureRegistry.getDefault(), Long.MAX_VALUE);
        assertEquals(0, plan.executableCount());
    }

    @Test
    public void tooManyCombinationsThrows() {
        Map<ParameterKey, ParameterValueList> lists = lists();
        lists.put(SpectralParameterId.STRENGTH, ParameterValueList.ofDoubles(0.5, 1.0, 1.5));
        try {
            SpectralSweepPlan.forSweep(sweep(lists), base("linear_unmixing"), null, 2L);
            fail("expected TooManyCombinationsException");
        } catch (SpectralSweepPlan.TooManyCombinationsException e) {
            assertTrue(e.rawCount() >= 3);
            assertEquals(2L, e.maxRawCombos());
        }
    }
}
