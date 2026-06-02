package flash.pipeline.image.dag;

import flash.pipeline.image.NamedFilterLoader;
import org.junit.Test;

import java.util.Locale;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Regression coverage for the duplicate / branch corruption documented in
 * docs/filter-branch-robustness.
 */
public class IjmToDagLoaderDuplicateTest {

    @Test
    public void punctaResolveLoadsAsBranchedWithNoDuplicateNode() {
        String macro = NamedFilterLoader.loadFilterContent("Puncta Resolve");
        assertNotNull(macro);

        DagIR dag = IjmToDagLoader.load(macro);

        assertFalse("a branched compound filter must not be classified linear",
                dag.isLinear());
        assertNoDuplicateNode(dag);
    }

    @Test
    public void branchedMacroNeverProducesADuplicateNode() {
        String macro = ""
                + "original = getTitle();\n"
                + "run(\"Duplicate...\", \"title=_a duplicate\");\n"
                + "run(\"Gaussian Blur 3D...\", \"x=2 y=2 z=1\");\n"
                + "selectWindow(original);\n"
                + "run(\"Duplicate...\", \"title=_b duplicate\");\n"
                + "run(\"Median...\", \"radius=3 stack\");\n"
                + "imageCalculator(\"Add create stack\", \"_a\", \"_b\");\n";

        DagIR dag = IjmToDagLoader.load(macro);

        assertFalse(dag.isLinear());
        assertNoDuplicateNode(dag);
    }

    @Test
    public void duplicateScaffoldingIsStrippedFromLinearMacro() {
        // A single sandbox-style working copy is scaffolding, not a filter step.
        String macro = ""
                + "source_id = getImageID();\n"
                + "selectImage(source_id);\n"
                + "run(\"Duplicate...\", \"title=line_A duplicate\");\n"
                + "line_A = getImageID();\n"
                + "run(\"Gaussian Blur...\", \"sigma=2 stack\");\n";

        DagIR dag = IjmToDagLoader.load(macro);

        assertTrue("single working copy stays linear", dag.isLinear());
        assertNoDuplicateNode(dag);
        assertTrue("the real filter step survives",
                dag.lines.get(0).ops.size() >= 1);
    }

    @Test
    public void roundTripNeverEmitsBareDuplicate() {
        String macro = NamedFilterLoader.loadFilterContent("Puncta Resolve");
        DagIR dag = IjmToDagLoader.load(macro);

        String emitted = DagToIjmEmitter.emit(dag);

        assertFalse("emitted macro must not contain a bare run(\"Duplicate\", ...)",
                emitted.matches("(?s).*run\\(\"Duplicate\"\\s*[,)].*"));
    }

    private static void assertNoDuplicateNode(DagIR dag) {
        for (DagLine line : dag.lines) {
            for (DagNode node : line.ops) {
                String cmd = node.commandName == null ? "" : node.commandName.trim();
                while (cmd.endsWith(".")) cmd = cmd.substring(0, cmd.length() - 1).trim();
                assertFalse("scaffolding Duplicate must never become a filter node",
                        "duplicate".equals(cmd.toLowerCase(Locale.ROOT)));
            }
        }
    }
}
