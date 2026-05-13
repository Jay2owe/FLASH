package flash.pipeline.image.dag;

import flash.pipeline.image.FilterMacroParser.OpType;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.fail;

public class DagMutationsTest {

    @Test
    public void withNodeArgsPreservesMetadataAndCopiesAllNodes() {
        DagIR src = sampleDag();
        DagIR mutated = DagMutations.withNodeArgs(src, "n1", "sigma=4 stack");

        DagNode original = src.lines.get(0).ops.get(0);
        DagNode replaced = mutated.lines.get(0).ops.get(0);
        assertEquals("sigma=4 stack", replaced.args);
        assertEquals(original.id, replaced.id);
        assertEquals(original.type, replaced.type);
        assertEquals(original.commandName, replaced.commandName);
        assertEquals(original.menuPath, replaced.menuPath);
        assertEquals(original.disabled, replaced.disabled);

        assertEquals(src.version, mutated.version);
        assertEquals(src.combiners, mutated.combiners);
        assertEquals(src.output, mutated.output);
        assertEquals(src.executionTier, mutated.executionTier);
        assertNotSame(original, replaced);
        assertNotSame(src.lines.get(1).ops.get(0), mutated.lines.get(1).ops.get(0));
    }

    @Test
    public void withNodeSubstitutedResetsLegacyFieldsKeepsIdAndDisabledState() {
        DagIR src = legacyDag(false);
        DagIR mutated = DagMutations.withNodeSubstituted(
                src, "legacy", OpType.MEDIAN, "radius=3 stack");

        DagNode replaced = mutated.lines.get(0).ops.get(0);
        assertEquals("legacy", replaced.id);
        assertEquals(OpType.MEDIAN, replaced.type);
        assertEquals("radius=3 stack", replaced.args);
        assertEquals("", replaced.commandName);
        assertEquals("", replaced.menuPath);
        assertEquals(false, replaced.disabled);
        assertEquals("native", mutated.executionTier);
    }

    @Test
    public void substitutedDisabledLegacyNodeStaysDisabledAndDoesNotForceLegacy() {
        DagIR src = legacyDag(true);
        assertEquals("native", src.executionTier);

        DagIR mutated = DagMutations.withNodeSubstituted(
                src, "legacy", OpType.GAUSSIAN_BLUR, "sigma=2 stack");

        DagNode replaced = mutated.lines.get(0).ops.get(0);
        assertEquals("legacy", replaced.id);
        assertEquals(true, replaced.disabled);
        assertEquals("native", mutated.executionTier);
    }

    @Test
    public void enabledUnknownForcesLegacyButDisabledUnknownDoesNot() {
        DagIR enabled = legacyDag(false);
        DagIR disabled = legacyDag(true);

        assertEquals("legacy", enabled.executionTier);
        assertEquals("native", disabled.executionTier);
    }

    @Test
    public void unknownNodeIdThrows() {
        try {
            DagMutations.withNodeArgs(sampleDag(), "missing", "sigma=1 stack");
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }

    @Test
    public void nullSourceThrows() {
        try {
            DagMutations.withNodeSubstituted(null, "n1", OpType.MEDIAN, "radius=1 stack");
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }

    private static DagIR sampleDag() {
        DagNode disabledLegacy = new DagNode("n1", OpType.UNKNOWN, "sigma=2 stack",
                "Plugin Blur", "Plugins>Blur");
        disabledLegacy.disabled = true;
        DagLine gaussianLine = new DagLine("line_1", Collections.singletonList(disabledLegacy));
        DagLine medianLine = new DagLine("line_2", Collections.singletonList(
                new DagNode("n2", OpType.MEDIAN, "radius=2 stack")));
        Combiner combiner = new Combiner("combined", CombinerOp.ADD,
                Arrays.asList("line_1", "line_2"));
        return new DagIR(1, Arrays.asList(gaussianLine, medianLine),
                Collections.singletonList(combiner), "combined", "native");
    }

    private static DagIR legacyDag(boolean disabled) {
        DagNode legacy = new DagNode("legacy", OpType.UNKNOWN, "raw",
                "Plugin Filter", "Plugins>Filter");
        legacy.disabled = disabled;
        return new DagIR(1,
                Collections.singletonList(new DagLine("line_1",
                        Collections.singletonList(legacy))),
                Collections.<Combiner>emptyList(),
                "line_1",
                "native");
    }
}
