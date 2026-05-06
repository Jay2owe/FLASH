package flash.pipeline.image.dag;

import ij.ImagePlus;
import ij.ImageStack;
import ij.Prefs;
import ij.macro.Interpreter;
import ij.plugin.ImageCalculator;
import ij.process.ByteProcessor;
import ij.process.ImageProcessor;
import flash.pipeline.image.FilterExecutor;
import flash.pipeline.image.FilterMacroParser.OpType;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class DagExecutorTest {
    private static final int W = 32;
    private static final int H = 32;
    private static final long SEED = 123L;

    private boolean priorBatchMode;
    private boolean priorBlackBg;

    @Before
    public void setUp() {
        priorBatchMode = Interpreter.batchMode;
        Interpreter.batchMode = true;
        priorBlackBg = Prefs.blackBackground;
        Prefs.blackBackground = true;
    }

    @After
    public void tearDown() {
        Interpreter.batchMode = priorBatchMode;
        Prefs.blackBackground = priorBlackBg;
    }

    @Test
    public void singleLineDagEqualsRunThreadSafe() throws Exception {
        ImagePlus expected = makeByteStack("expected", 5);
        ImagePlus source = makeByteStack("source", 5);
        String macro = "run(\"Gaussian Blur...\", \"sigma=2 stack\");\n"
                + "run(\"Median...\", \"radius=2 stack\");";
        assertTrue(FilterExecutor.runThreadSafe(expected, macro));

        DagIR dag = new DagIR(
                1,
                Arrays.asList(new DagLine("A", Arrays.asList(
                        new DagNode("a1", OpType.GAUSSIAN_BLUR, "sigma=2 stack"),
                        new DagNode("a2", OpType.MEDIAN, "radius=2 stack")))),
                Arrays.<Combiner>asList(),
                "A",
                "native");

        ImagePlus actual = FilterExecutor.runDagThreadSafe(source, dag);

        assertSlicesEqual(expected, actual, 0.0);
    }

    @Test
    public void twoLineAndCombinerEqualsImageCalculator() throws Exception {
        ImagePlus a = makeBinaryStack("a", 1);
        ImagePlus b = makeBinaryStack("b", 1);
        assertTrue(FilterExecutor.runThreadSafe(b, "run(\"Invert\");"));
        ImagePlus expected = new ImageCalculator().run("AND create", a, b);

        DagIR dag = new DagIR(
                1,
                Arrays.asList(
                        new DagLine("A", Arrays.<DagNode>asList()),
                        new DagLine("B", Arrays.asList(new DagNode("b1", OpType.INVERT, "")))),
                Arrays.asList(new Combiner("C", CombinerOp.AND, Arrays.asList("A", "B"))),
                "C",
                "native");

        ImagePlus actual = FilterExecutor.runDagThreadSafe(makeBinaryStack("source", 1), dag);

        assertSlicesEqual(expected, actual, 0.0);
        expected.flush();
        actual.flush();
    }

    @Test(expected = DagRejectedException.class)
    public void unknownOpThrowsDagRejectedException() throws Exception {
        DagIR dag = new DagIR(
                1,
                Arrays.asList(new DagLine("A", Arrays.asList(
                        new DagNode("unknown", OpType.UNKNOWN, "run(\"Plugin\")")))),
                Arrays.<Combiner>asList(),
                "A",
                "native");

        FilterExecutor.runDagThreadSafe(makeByteStack("source", 1), dag);
    }

    @Test(expected = DagRejectedException.class)
    public void legacyDagThrowsFromNativeExecutor() throws Exception {
        DagIR dag = new DagIR(
                1,
                Arrays.asList(new DagLine("A", Arrays.asList(
                        new DagNode("legacy", OpType.UNKNOWN, "",
                                "Invert", "Process > Binary > Invert")))),
                Arrays.<Combiner>asList(),
                "A",
                "legacy");

        FilterExecutor.runDagThreadSafe(makeByteStack("source", 1), dag);
    }

    @Test
    public void dagExecutionDoesNotChangeWindowManagerSnapshot() throws Exception {
        Set<Integer> before = snapshotWindowIds();
        DagIR dag = new DagIR(
                1,
                Arrays.asList(new DagLine("A", Arrays.asList(
                        new DagNode("a1", OpType.MEAN, "radius=1 stack")))),
                Arrays.<Combiner>asList(),
                "A",
                "native");

        ImagePlus result = FilterExecutor.runDagThreadSafe(makeByteStack("source", 5), dag);
        Set<Integer> after = snapshotWindowIds();

        assertEquals(before, after);
        result.flush();
    }

    private static ImagePlus makeByteStack(String title, int slices) {
        ImageStack stack = new ImageStack(W, H);
        Random r = new Random(SEED);
        for (int s = 0; s < slices; s++) {
            ByteProcessor bp = new ByteProcessor(W, H);
            byte[] pixels = (byte[]) bp.getPixels();
            for (int i = 0; i < pixels.length; i++) {
                pixels[i] = (byte) r.nextInt(256);
            }
            stack.addSlice(bp);
        }
        return new ImagePlus(title, stack);
    }

    private static ImagePlus makeBinaryStack(String title, int slices) {
        ImageStack stack = new ImageStack(W, H);
        Random r = new Random(SEED);
        for (int s = 0; s < slices; s++) {
            ByteProcessor bp = new ByteProcessor(W, H);
            byte[] pixels = (byte[]) bp.getPixels();
            for (int i = 0; i < pixels.length; i++) {
                pixels[i] = (byte) (r.nextBoolean() ? 255 : 0);
            }
            stack.addSlice(bp);
        }
        return new ImagePlus(title, stack);
    }

    private static void assertSlicesEqual(ImagePlus expected, ImagePlus actual, double tol) {
        ImageStack se = expected.getStack();
        ImageStack sa = actual.getStack();
        assertEquals("slice count", se.getSize(), sa.getSize());
        for (int s = 1; s <= se.getSize(); s++) {
            ImageProcessor pe = se.getProcessor(s);
            ImageProcessor pa = sa.getProcessor(s);
            int n = pe.getWidth() * pe.getHeight();
            for (int i = 0; i < n; i++) {
                double ve = pe.getf(i);
                double va = pa.getf(i);
                if (Math.abs(ve - va) > tol) {
                    throw new AssertionError("slice " + s + " idx " + i
                            + " expected=" + ve + " actual=" + va);
                }
            }
        }
    }

    private static Set<Integer> snapshotWindowIds() {
        Set<Integer> ids = new HashSet<Integer>();
        int[] raw = ij.WindowManager.getIDList();
        if (raw != null) {
            for (int id : raw) ids.add(id);
        }
        return ids;
    }
}
