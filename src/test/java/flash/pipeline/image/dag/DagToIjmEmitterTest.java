package flash.pipeline.image.dag;

import flash.pipeline.image.FilterExecutor;
import flash.pipeline.image.FilterMacroParser.OpType;
import flash.pipeline.image.NamedFilterLoader;
import ij.ImagePlus;
import ij.ImageStack;
import ij.WindowManager;
import ij.macro.Interpreter;
import ij.process.ByteProcessor;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class DagToIjmEmitterTest {

    private boolean priorBatchMode;

    @Before
    public void setUp() {
        priorBatchMode = Interpreter.batchMode;
        Interpreter.batchMode = true;
    }

    @After
    public void tearDown() {
        Interpreter.batchMode = priorBatchMode;
    }

    @Test
    public void bundledPresetsRoundTripThroughEmbeddedDagJson() {
        for (String preset : NamedFilterLoader.FILTER_NAMES) {
            String macro = NamedFilterLoader.loadFilterContent(preset);
            DagIR dag = IjmToDagLoader.load(macro);

            DagIR parsed = IjmToDagLoader.load(DagToIjmEmitter.emit(dag));

            assertEquals("round-trip " + preset, dag, parsed);
        }
    }

    @Test
    public void emittedFallbackRunsThroughEmbeddedDagWithoutLeakingWindows() {
        DagIR dag = new DagIR(
                1,
                Arrays.asList(new DagLine("line_A", Arrays.asList(
                        new DagNode("n1", OpType.ADD, "value=20 stack")))),
                Arrays.<Combiner>asList(),
                "line_A",
                "native");
        ImagePlus source = makeImage();
        source.show();
        Set<Integer> before = snapshotWindowIds();

        boolean nativePath = FilterExecutor.runThreadSafe(source, DagToIjmEmitter.emit(dag));
        Set<Integer> after = snapshotWindowIds();

        assertTrue("emitted Sandbox macro should run through the embedded DAG", nativePath);
        assertEquals("embedded DAG result should be applied back to the source image",
                20, source.getProcessor().get(0, 0));
        assertTrue("embedded DAG execution should not leave new image windows", before.containsAll(after));
        source.changes = false;
        source.close();
        source.flush();
    }

    @Test
    public void runMacroStringAppliesEmbeddedDagResultInPlace() {
        DagIR dag = new DagIR(
                1,
                Arrays.asList(new DagLine("line_A", Arrays.asList(
                        new DagNode("n1", OpType.ADD, "value=20 stack")))),
                Arrays.<Combiner>asList(),
                "line_A",
                "native");
        ImagePlus source = makeImage();

        FilterExecutor.runMacroString(source, DagToIjmEmitter.emit(dag));

        assertEquals("public macro entry point should not show the raw unfiltered source",
                20, source.getProcessor().get(0, 0));
    }

    @Test
    public void emitsLegacyCommandByRecordedName() {
        DagIR dag = new DagIR(
                1,
                Arrays.asList(new DagLine("line_A", Arrays.asList(
                        new DagNode("n1", OpType.UNKNOWN, "sampling=5 stack",
                                "Kuwahara Filter", "Plugins > Filters > Kuwahara Filter...")))),
                Arrays.<Combiner>asList(),
                "line_A",
                "legacy");

        String macro = DagToIjmEmitter.emit(dag);

        assertTrue(macro.contains("executionTier=legacy"));
        assertTrue(macro.contains("run(\"Kuwahara Filter\", \"sampling=5 stack\");"));
    }

    private static ImagePlus makeImage() {
        ImageStack stack = new ImageStack(16, 16);
        ByteProcessor bp = new ByteProcessor(16, 16);
        byte[] pixels = (byte[]) bp.getPixels();
        for (int i = 0; i < pixels.length; i++) {
            pixels[i] = (byte) (i & 0xff);
        }
        stack.addSlice(bp);
        return new ImagePlus("source", stack);
    }

    private static Set<Integer> snapshotWindowIds() {
        Set<Integer> ids = new HashSet<Integer>();
        int[] raw = WindowManager.getIDList();
        if (raw != null) {
            for (int id : raw) ids.add(id);
        }
        return ids;
    }
}
