package flash.pipeline.ui.sandbox.variation;

import flash.pipeline.image.FilterMacroParser.OpType;
import flash.pipeline.image.dag.DagIR;
import flash.pipeline.image.dag.DagLine;
import flash.pipeline.image.dag.DagNode;
import flash.pipeline.image.variation.FilterCompatibility;
import flash.pipeline.image.variation.OpTypeParamRegistry;
import flash.pipeline.image.variation.ParamSpec;
import flash.pipeline.image.variation.VariantAxis;
import flash.pipeline.image.variation.VariantAxis.AlternativeValue;
import flash.pipeline.image.variation.VariantAxis.Kind;
import flash.pipeline.image.variation.VariantPlan;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class VariationChooserDialogTest {

    @Test
    public void sweepPanelGeometricSpacingMatchesSpecExample() {
        SweepPanel panel = new SweepPanel();
        panel.setNode(gaussianNode("n1", "sigma=2 stack"));

        ParamSpec spec = panel.getSelectedParam();
        assertEquals("sigma", spec.argKey);
        assertEquals(ParamSpec.Scale.LOG, spec.scale);
        panel.setSweepRange(0.5, 4.0, 4);

        VariantAxis axis = panel.buildAxis();
        assertEquals(Kind.PARAM_SWEEP, axis.kind);
        assertEquals("n1", axis.nodeId);
        assertEquals(4, axis.alternatives.size());

        double[] expected = {0.5, 1.0, 2.0, 4.0};
        for (int i = 0; i < expected.length; i++) {
            AlternativeValue alt = axis.alternatives.get(i);
            assertEquals("step " + i, expected[i],
                    parseDoubleArg(alt.args, "sigma"), 1e-6);
            assertNull(alt.type);
            assertTrue(alt.label.startsWith("sigma="));
        }
    }

    @Test
    public void sweepPanelLinearSpacingProducesArithmeticSteps() {
        SweepPanel panel = new SweepPanel();
        panel.setNode(new DagNode("n1", OpType.MEDIAN, "radius=2 stack"));
        assertEquals(ParamSpec.Scale.LINEAR, panel.getSelectedParam().scale);
        panel.setSweepRange(1.0, 10.0, 4);

        VariantAxis axis = panel.buildAxis();
        double[] expected = {1.0, 4.0, 7.0, 10.0};
        assertEquals(expected.length, axis.alternatives.size());
        for (int i = 0; i < expected.length; i++) {
            assertEquals("step " + i, expected[i],
                    parseDoubleArg(axis.alternatives.get(i).args, "radius"), 1e-9);
        }
    }

    @Test
    public void swapPanelUsesCompatibleAlternativesOnlyAndAppliesDefaults() {
        SwapPanel panel = new SwapPanel();
        panel.setNode(gaussianNode("n7", "sigma=2 stack"));
        panel.setTicked(OpType.GAUSSIAN_BLUR, OpType.MEDIAN, OpType.MEAN);

        VariantAxis axis = panel.buildAxis();
        assertEquals(Kind.FILTER_SWAP, axis.kind);
        assertEquals("n7", axis.nodeId);
        assertEquals("baseline excluded", 2, axis.alternatives.size());

        AlternativeValue median = findByType(axis.alternatives, OpType.MEDIAN);
        assertEquals(OpTypeParamRegistry.argsForDefaults(OpType.MEDIAN), median.args);
        assertEquals("Median", median.label);

        AlternativeValue mean = findByType(axis.alternatives, OpType.MEAN);
        assertEquals(OpTypeParamRegistry.argsForDefaults(OpType.MEAN), mean.args);
        assertEquals("Mean", mean.label);
    }

    @Test
    public void sweepChoicesSkipDisabledAndUnsweepableNodes() {
        DagNode sweepable = gaussianNode("n1", "sigma=2 stack");
        DagNode unsweepable = new DagNode("n2", OpType.INVERT, "");
        DagNode disabledSweepable = new DagNode("n3", OpType.MEDIAN, "radius=2 stack");
        disabledSweepable.disabled = true;

        List<VariationChooserDialog.NodeChoice> choices =
                VariationChooserDialog.nodeChoicesFor(
                        singleLineDag(sweepable, unsweepable, disabledSweepable), true);

        assertEquals(1, choices.size());
        assertEquals("n1", choices.get(0).node.id);
        assertEquals("line_A", choices.get(0).lineId);
    }

    @Test
    public void swapChoicesSkipDisabledAndNonSwappableNodes() {
        DagNode swappable = gaussianNode("n1", "sigma=2 stack");
        DagNode nonSwappable = new DagNode("n2", OpType.INVERT, "");
        DagNode disabledSwappable = new DagNode("n3", OpType.MEDIAN, "radius=2 stack");
        disabledSwappable.disabled = true;

        List<VariationChooserDialog.NodeChoice> choices =
                VariationChooserDialog.nodeChoicesFor(
                        singleLineDag(swappable, nonSwappable, disabledSwappable), false);

        assertEquals(1, choices.size());
        assertEquals("n1", choices.get(0).node.id);
    }

    @Test
    public void choosePlansRejectsCartesianWhenAdvancedClosed() {
        DagIR baseline = singleLineDag(gaussianNode("n1", "sigma=2 stack"));
        VariantAxis axis = new VariantAxis("n1", Kind.PARAM_SWEEP, Arrays.asList(
                new AlternativeValue("sigma=1.0", null, "sigma=1.0"),
                new AlternativeValue("sigma=4.0", null, "sigma=4.0")));
        try {
            VariationChooserDialog.choosePlans(baseline, Collections.singletonList(axis),
                    false, true, 9);
            fail("expected IllegalStateException for cartesian without advanced");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().toLowerCase().contains("advanced"));
        }
    }

    @Test
    public void choosePlansAllowsOfatWhenAdvancedClosed() {
        DagIR baseline = singleLineDag(gaussianNode("n1", "sigma=2 stack"));
        VariantAxis axis = new VariantAxis("n1", Kind.PARAM_SWEEP, Arrays.asList(
                new AlternativeValue("sigma=1.0", null, "sigma=1.0"),
                new AlternativeValue("sigma=4.0", null, "sigma=4.0")));

        List<VariantPlan> plans = VariationChooserDialog.choosePlans(
                baseline, Collections.singletonList(axis), false, false, 9);

        assertEquals(3, plans.size());
        assertEquals("baseline", plans.get(0).label);
    }

    @Test
    public void choosePlansClampsRequestedCapToHardCap() {
        DagIR baseline = singleLineDag(gaussianNode("n1", "sigma=2 stack"));
        VariantAxis axis = new VariantAxis("n1", Kind.PARAM_SWEEP,
                Collections.singletonList(
                        new AlternativeValue("sigma=1.0", null, "sigma=1.0")));

        List<VariantPlan> plans = VariationChooserDialog.choosePlans(
                baseline, Collections.singletonList(axis), true, true, 99);

        assertEquals(1, plans.size());
    }

    @Test
    public void filterCompatibility3dDoesNotIncludeAny2dFilters() {
        List<OpType> alts = FilterCompatibility.alternativesFor(OpType.GAUSSIAN_BLUR_3D);
        assertFalse(alts.isEmpty());
        for (int i = 0; i < alts.size(); i++) {
            OpType t = alts.get(i);
            assertTrue("2D pollution: " + t,
                    t == OpType.GAUSSIAN_BLUR_3D
                            || t == OpType.MEDIAN_3D
                            || t == OpType.MINIMUM_3D);
        }
    }

    @Test
    public void filterCompatibilityUnknownOpsReturnEmpty() {
        assertTrue(FilterCompatibility.alternativesFor(null).isEmpty());
        assertTrue(FilterCompatibility.alternativesFor(OpType.UNKNOWN).isEmpty());
    }

    @Test
    public void sweepPanelAlternativeCountReflectsStepsSpinner() {
        SweepPanel panel = new SweepPanel();
        panel.setNode(gaussianNode("n1", "sigma=2 stack"));
        panel.setSweepRange(1.0, 8.0, 6);
        assertEquals(6, panel.alternativeCount());
        assertEquals(6, panel.buildAxis().alternatives.size());
    }

    @Test
    public void swapPanelEmptyTickListMakesPanelNotReady() {
        SwapPanel panel = new SwapPanel();
        panel.setNode(gaussianNode("n1", "sigma=2 stack"));
        assertFalse(panel.isReady());
        assertEquals(0, panel.alternativeCount());
    }

    private static DagNode gaussianNode(String id, String args) {
        return new DagNode(id, OpType.GAUSSIAN_BLUR, args);
    }

    private static DagIR singleLineDag(DagNode... nodes) {
        DagLine line = new DagLine("line_A", Arrays.asList(nodes));
        return new DagIR(1,
                Collections.singletonList(line),
                Collections.emptyList(),
                "line_A",
                "native");
    }

    private static AlternativeValue findByType(List<AlternativeValue> alts, OpType type) {
        for (int i = 0; i < alts.size(); i++) {
            AlternativeValue alt = alts.get(i);
            if (alt.type == type) return alt;
        }
        fail("no alternative for type " + type);
        return null;
    }

    private static double parseDoubleArg(String args, String key) {
        Double v = OpTypeParamRegistry.parseArgs(OpType.GAUSSIAN_BLUR, args).get(key);
        if (v != null) return v.doubleValue();
        v = OpTypeParamRegistry.parseArgs(OpType.MEDIAN, args).get(key);
        if (v != null) return v.doubleValue();
        fail("missing arg key " + key + " in " + args);
        return Double.NaN;
    }
}
