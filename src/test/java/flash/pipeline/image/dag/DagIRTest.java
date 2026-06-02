package flash.pipeline.image.dag;

import flash.pipeline.image.FilterMacroParser.OpType;
import flash.pipeline.image.NamedFilterLoader;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DagIRTest {

    @Test
    public void isLinear_singleLineSingleOpIsLinear() {
        DagIR dag = new DagIR(
                1,
                Arrays.asList(new DagLine("line_A", Arrays.asList(
                        new DagNode("n1", OpType.GAUSSIAN_BLUR, "sigma=2 stack")))),
                Collections.<Combiner>emptyList(),
                "line_A",
                "native");

        assertTrue(dag.isLinear());
    }

    @Test
    public void isLinear_bundledPresetsClassifiedByBranchStructure() {
        // Compound, branched presets (multiple Duplicate working copies +
        // imageCalculator) must seed a NON-linear DAG so the editor routes them
        // to the branched view instead of flattening + corrupting them. Flat
        // chains stay linear. See docs/filter-branch-robustness.
        java.util.Set<String> branched = new java.util.HashSet<String>(
                Arrays.asList("Puncta Resolve", "Diffuse Object"));
        for (String preset : NamedFilterLoader.FILTER_NAMES) {
            String macro = NamedFilterLoader.loadFilterContent(preset);
            DagIR dag = IjmToDagLoader.load(macro);
            if (branched.contains(preset)) {
                assertFalse("compound preset must seed a branched DAG: " + preset,
                        dag.isLinear());
            } else {
                assertTrue("flat preset must seed a linear DAG: " + preset,
                        dag.isLinear());
            }
        }
    }

    @Test
    public void isLinear_branchedFixtureWithCombinerIsFalse() {
        DagIR dag = new DagIR(
                1,
                Arrays.asList(
                        new DagLine("A", Arrays.asList(
                                new DagNode("a1", OpType.GAUSSIAN_BLUR, "sigma=2 stack"))),
                        new DagLine("B", Arrays.asList(
                                new DagNode("b1", OpType.MEDIAN, "radius=3 stack")))),
                Arrays.asList(new Combiner("C", CombinerOp.AND, Arrays.asList("A", "B"))),
                "C",
                "native");

        assertFalse(dag.isLinear());
    }

    @Test
    public void isLinear_twoLinesWithoutCombinerIsFalse() {
        DagIR dag = new DagIR(
                1,
                Arrays.asList(
                        new DagLine("A", Arrays.asList(
                                new DagNode("a1", OpType.GAUSSIAN_BLUR, "sigma=2 stack"))),
                        new DagLine("B", Arrays.asList(
                                new DagNode("b1", OpType.MEDIAN, "radius=3 stack")))),
                Collections.<Combiner>emptyList(),
                "A",
                "native");

        assertFalse(dag.isLinear());
    }

    @Test
    public void isLinear_outputMismatchIsFalse() {
        DagIR dag = new DagIR(
                1,
                Arrays.asList(new DagLine("line_A", Arrays.asList(
                        new DagNode("n1", OpType.GAUSSIAN_BLUR, "sigma=2 stack")))),
                Arrays.asList(new Combiner("C", CombinerOp.AND, Arrays.asList("line_A", "line_A"))),
                "C",
                "native");

        assertFalse("output points at combiner, not the line", dag.isLinear());
    }

    @Test
    public void disabledFlagDefaultsFalseAndAffectsEquality() {
        DagNode active = new DagNode("n1", OpType.GAUSSIAN_BLUR, "sigma=2 stack");
        DagNode disabledNode = new DagNode("n1", OpType.GAUSSIAN_BLUR, "sigma=2 stack");
        disabledNode.disabled = true;

        assertFalse("default disabled state must be false", active.disabled);
        assertEquals("disabled defaults to false on construction", false, active.disabled);
        // Disabled state participates in equality so two DAGs that differ only
        // by an eye-toggled node compare unequal — required so MacroStore can
        // detect the fork-on-first-edit transition.
        org.junit.Assert.assertNotEquals(active, disabledNode);
    }
}
