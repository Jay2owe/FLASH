package flash.pipeline.image.variation;

import flash.pipeline.image.FilterMacroParser.OpType;
import flash.pipeline.image.dag.Combiner;
import flash.pipeline.image.dag.CombinerOp;
import flash.pipeline.image.dag.DagIR;
import flash.pipeline.image.dag.DagLine;
import flash.pipeline.image.dag.DagNode;
import flash.pipeline.image.variation.VariantAxis.AlternativeValue;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class VariantSamplerTest {

    @Test
    public void ofatProducesBaselinePlusOneVariantPerAlternative() {
        DagIR baseline = fourNodeDag();
        List<VariantAxis> axes = Arrays.asList(
                paramAxis("n1", "sigma=2 stack", "sigma=4 stack"),
                paramAxis("n2", "radius=2 stack", "radius=4 stack"),
                paramAxis("n3", "sigma=2 stack", "sigma=4 stack"),
                paramAxis("n4", "radius=2 stack", "radius=4 stack"));

        List<VariantPlan> plans = VariantSampler.ofat(baseline, axes, 9);

        assertEquals(9, plans.size());
        assertEquals("baseline", plans.get(0).label);
        assertSame(baseline, plans.get(0).dag);
        assertTrue(plans.get(0).paramDelta.isEmpty());
        for (int i = 1; i < plans.size(); i++) {
            assertEquals(1, plans.get(i).paramDelta.size());
            assertNotEquals(baseline, plans.get(i).dag);
        }
    }

    @Test
    public void ofatDoesNotStackAlternativesAcrossAxes() {
        DagIR baseline = fourNodeDag();
        List<VariantAxis> axes = Arrays.asList(
                paramAxis("n1", "sigma=4 stack"),
                paramAxis("n2", "radius=4 stack"));

        List<VariantPlan> plans = VariantSampler.ofat(baseline, axes, 16);

        assertEquals(3, plans.size());
        assertEquals("sigma=4 stack", plans.get(1).dag.lines.get(0).ops.get(0).args);
        assertEquals("radius=1 stack", plans.get(1).dag.lines.get(1).ops.get(0).args);
        assertEquals("sigma=1 stack", plans.get(2).dag.lines.get(0).ops.get(0).args);
        assertEquals("radius=4 stack", plans.get(2).dag.lines.get(1).ops.get(0).args);
    }

    @Test
    public void ofatRespectsMaxVariantsCapMidAxis() {
        DagIR baseline = fourNodeDag();
        List<VariantAxis> axes = Arrays.asList(
                paramAxis("n1", "sigma=1 stack", "sigma=4 stack", "sigma=8 stack"),
                paramAxis("n2", "radius=1 stack", "radius=4 stack", "radius=8 stack"));

        List<VariantPlan> plans = VariantSampler.ofat(baseline, axes, 4);

        assertEquals(4, plans.size());
        assertEquals("baseline", plans.get(0).label);
        for (int i = 1; i < plans.size(); i++) {
            assertEquals("n1", onlyKey(plans.get(i)));
        }
    }

    @Test
    public void ofatBaselineOnlyWhenMaxVariantsIsOne() {
        DagIR baseline = fourNodeDag();
        List<VariantAxis> axes = Collections.singletonList(
                paramAxis("n1", "sigma=4 stack"));

        List<VariantPlan> plans = VariantSampler.ofat(baseline, axes, 1);

        assertEquals(1, plans.size());
        assertEquals("baseline", plans.get(0).label);
    }

    @Test
    public void ofatThrowsOnInvalidMaxVariants() {
        try {
            VariantSampler.ofat(fourNodeDag(), Collections.<VariantAxis>emptyList(), 0);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }

    @Test
    public void ofatAppliesParamSweepAndFilterSwapMutations() {
        DagIR baseline = fourNodeDag();
        VariantAxis sweep = new VariantAxis("n1", VariantAxis.Kind.PARAM_SWEEP,
                Collections.singletonList(
                        new AlternativeValue("sigma=4", null, "sigma=4 stack")));
        VariantAxis swap = new VariantAxis("n1", VariantAxis.Kind.FILTER_SWAP,
                Collections.singletonList(
                        new AlternativeValue("Median", OpType.MEDIAN, "radius=2 stack")));

        List<VariantPlan> plans = VariantSampler.ofat(
                baseline, Arrays.asList(sweep, swap), 16);

        assertEquals(3, plans.size());
        DagNode swept = plans.get(1).dag.lines.get(0).ops.get(0);
        assertEquals(OpType.GAUSSIAN_BLUR, swept.type);
        assertEquals("sigma=4 stack", swept.args);
        DagNode swapped = plans.get(2).dag.lines.get(0).ops.get(0);
        assertEquals(OpType.MEDIAN, swapped.type);
        assertEquals("radius=2 stack", swapped.args);
    }

    @Test
    public void filterSwapAlternativeMustDeclareType() {
        VariantAxis badSwap = new VariantAxis("n1", VariantAxis.Kind.FILTER_SWAP,
                Collections.singletonList(
                        new AlternativeValue("bad", null, "")));
        try {
            VariantSampler.ofat(fourNodeDag(), Collections.singletonList(badSwap), 16);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("null type"));
        }
    }

    @Test
    public void cartesianTwoByThreeProducesSixVariants() {
        DagIR baseline = fourNodeDag();
        List<VariantAxis> axes = Arrays.asList(
                paramAxis("n1", "sigma=1 stack", "sigma=4 stack"),
                paramAxis("n2", "radius=1 stack", "radius=4 stack", "radius=8 stack"));

        List<VariantPlan> plans = VariantSampler.cartesian(baseline, axes, 16);

        assertEquals(6, plans.size());
        for (VariantPlan plan : plans) {
            assertEquals(2, plan.paramDelta.size());
            assertTrue(plan.paramDelta.containsKey("n1"));
            assertTrue(plan.paramDelta.containsKey("n2"));
        }
        for (int i = 0; i < plans.size(); i++) {
            for (int j = i + 1; j < plans.size(); j++) {
                assertNotEquals("plans " + i + " and " + j + " collided",
                        plans.get(i).dag, plans.get(j).dag);
            }
        }
    }

    @Test
    public void cartesianAppliesEveryAxisToEveryVariant() {
        DagIR baseline = fourNodeDag();
        List<VariantAxis> axes = Arrays.asList(
                paramAxis("n1", "sigma=4 stack"),
                paramAxis("n2", "radius=4 stack"));

        List<VariantPlan> plans = VariantSampler.cartesian(baseline, axes, 16);

        assertEquals(1, plans.size());
        DagIR mutated = plans.get(0).dag;
        assertEquals("sigma=4 stack", mutated.lines.get(0).ops.get(0).args);
        assertEquals("radius=4 stack", mutated.lines.get(1).ops.get(0).args);
    }

    @Test
    public void cartesianThrowsWhenProductExceedsMaxVariants() {
        List<VariantAxis> axes = Arrays.asList(
                paramAxis("n1", "sigma=1 stack", "sigma=4 stack"),
                paramAxis("n2", "radius=1 stack", "radius=4 stack"));

        try {
            VariantSampler.cartesian(fourNodeDag(), axes, 3);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().toLowerCase().contains("exceeds"));
        }
    }

    @Test
    public void cartesianThrowsWhenMaxVariantsExceedsHardCap() {
        try {
            VariantSampler.cartesian(fourNodeDag(),
                    Collections.singletonList(paramAxis("n1", "sigma=4 stack")),
                    17);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().toLowerCase().contains("hard cap"));
        }
    }

    @Test
    public void cartesianEmptyAxesProducesEmptyList() {
        List<VariantPlan> plans = VariantSampler.cartesian(
                fourNodeDag(), Collections.<VariantAxis>emptyList(), 16);
        assertTrue(plans.isEmpty());
    }

    private static VariantAxis paramAxis(String nodeId, String... argValues) {
        List<AlternativeValue> alts = new ArrayList<AlternativeValue>(argValues.length);
        for (String args : argValues) {
            alts.add(new AlternativeValue(args, null, args));
        }
        return new VariantAxis(nodeId, VariantAxis.Kind.PARAM_SWEEP, alts);
    }

    private static String onlyKey(VariantPlan plan) {
        return plan.paramDelta.keySet().iterator().next();
    }

    private static DagIR fourNodeDag() {
        DagLine line1 = new DagLine("line_1",
                Collections.singletonList(new DagNode(
                        "n1", OpType.GAUSSIAN_BLUR, "sigma=1 stack")));
        DagLine line2 = new DagLine("line_2",
                Collections.singletonList(new DagNode(
                        "n2", OpType.MEDIAN, "radius=1 stack")));
        DagLine line3 = new DagLine("line_3",
                Collections.singletonList(new DagNode(
                        "n3", OpType.GAUSSIAN_BLUR, "sigma=1 stack")));
        DagLine line4 = new DagLine("line_4",
                Collections.singletonList(new DagNode(
                        "n4", OpType.MEDIAN, "radius=1 stack")));
        Combiner combiner = new Combiner("combined", CombinerOp.ADD,
                Arrays.asList("line_1", "line_2", "line_3", "line_4"));
        return new DagIR(1,
                Arrays.asList(line1, line2, line3, line4),
                Collections.singletonList(combiner),
                "combined",
                "native");
    }
}
