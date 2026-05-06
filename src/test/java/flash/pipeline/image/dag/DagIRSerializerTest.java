package flash.pipeline.image.dag;

import flash.pipeline.image.FilterMacroParser.OpType;
import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;

public class DagIRSerializerTest {

    @Test
    public void roundTripPreservesDag() {
        DagIR dag = new DagIR(
                1,
                Arrays.asList(
                        new DagLine("A", Arrays.asList(
                                new DagNode("a1", OpType.GAUSSIAN_BLUR, "sigma=2 stack"),
                                new DagNode("a2", OpType.SUBTRACT_BACKGROUND, "rolling=50 stack"))),
                        new DagLine("B", Arrays.asList(
                                new DagNode("b1", OpType.MEDIAN, "radius=3 stack")))),
                Arrays.asList(new Combiner("C", CombinerOp.AND, Arrays.asList("A", "B"))),
                "C",
                "native");

        DagIR parsed = DagIRSerializer.fromJson(DagIRSerializer.toJson(dag));

        assertEquals(dag, parsed);
    }

    @Test
    public void fromJsonAcceptsEscapedArgs() {
        DagIR dag = new DagIR(
                1,
                Arrays.asList(new DagLine("A", Arrays.asList(
                        new DagNode("a1", OpType.ENHANCE_CONTRAST,
                                "saturated=1.0 normalize note=\"x\"")))),
                Arrays.<Combiner>asList(),
                "A",
                "native");

        DagIR parsed = DagIRSerializer.fromJson(DagIRSerializer.toJson(dag));

        assertEquals("saturated=1.0 normalize note=\"x\"",
                parsed.lines.get(0).ops.get(0).args);
    }

    @Test
    public void roundTripPreservesLegacyCommandMetadata() {
        DagIR dag = new DagIR(
                1,
                Arrays.asList(new DagLine("A", Arrays.asList(
                        new DagNode("a1", OpType.UNKNOWN,
                                "sampling=5 stack",
                                "Kuwahara Filter",
                                "Plugins > Filters > Kuwahara Filter...")))),
                Arrays.<Combiner>asList(),
                "A",
                "native");

        DagIR parsed = DagIRSerializer.fromJson(DagIRSerializer.toJson(dag));

        assertEquals("legacy", parsed.executionTier);
        assertEquals("Kuwahara Filter", parsed.lines.get(0).ops.get(0).commandName);
        assertEquals("Plugins > Filters > Kuwahara Filter...",
                parsed.lines.get(0).ops.get(0).menuPath);
    }
}
