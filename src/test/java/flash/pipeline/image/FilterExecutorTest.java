package flash.pipeline.image;

import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.Prefs;
import ij.macro.Interpreter;
import ij.process.ByteProcessor;
import ij.process.FloatProcessor;
import ij.process.ImageProcessor;
import ij.process.ShortProcessor;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Numerical parity between {@link FilterExecutor#runThreadSafe} (native Java
 * path) and the locked legacy IJ1 macro path. Tests run on synthetic 32×32
 * slices with reproducible random pixel noise so the same input is fed to
 * both paths.
 *
 * Headless-friendly: uses {@link Interpreter#batchMode} so {@code imp.show()}
 * registers in WindowManager without spawning AWT windows.
 */
public class FilterExecutorTest {

    private static final int W = 32;
    private static final int H = 32;
    private static final long SEED = 42L;

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

    // ── helpers ──

    private static ImagePlus makeByteSlice(String title) {
        ByteProcessor bp = new ByteProcessor(W, H);
        Random r = new Random(SEED);
        byte[] px = (byte[]) bp.getPixels();
        for (int i = 0; i < px.length; i++) {
            px[i] = (byte) (r.nextInt(256));
        }
        return new ImagePlus(title, bp);
    }

    private static ImagePlus makeShortSlice(String title) {
        ShortProcessor sp = new ShortProcessor(W, H);
        Random r = new Random(SEED);
        short[] px = (short[]) sp.getPixels();
        for (int i = 0; i < px.length; i++) {
            px[i] = (short) (r.nextInt(4096));
        }
        return new ImagePlus(title, sp);
    }

    private static ImagePlus makeByteStack(String title, int slices) {
        ImageStack stk = new ImageStack(W, H);
        Random r = new Random(SEED);
        for (int s = 0; s < slices; s++) {
            ByteProcessor bp = new ByteProcessor(W, H);
            byte[] px = (byte[]) bp.getPixels();
            for (int i = 0; i < px.length; i++) px[i] = (byte) r.nextInt(256);
            stk.addSlice(bp);
        }
        return new ImagePlus(title, stk);
    }

    private static ImagePlus makeBinaryStack(String title, int slices) {
        ImageStack stk = new ImageStack(W, H);
        Random r = new Random(SEED);
        for (int s = 0; s < slices; s++) {
            ByteProcessor bp = new ByteProcessor(W, H);
            byte[] px = (byte[]) bp.getPixels();
            for (int i = 0; i < px.length; i++) {
                px[i] = (byte) (r.nextInt(100) < 30 ? 255 : 0);
            }
            stk.addSlice(bp);
        }
        return new ImagePlus(title, stk);
    }

    /** Runs the macro through the locked legacy path on imp. */
    private static void runMacroLegacy(ImagePlus imp, String macro) {
        WindowManagerLock.LOCK.lock();
        try {
            imp.show();
            imp.setActivated();
            IJ.runMacro(macro);
            if (imp.getWindow() != null) {
                imp.changes = false;
                imp.hide();
            }
        } finally {
            WindowManagerLock.LOCK.unlock();
        }
    }

    private static void assertSlicesEqual(ImagePlus a, ImagePlus b, double tol) {
        ImageStack sa = a.getStack();
        ImageStack sb = b.getStack();
        assertEquals("slice count", sa.getSize(), sb.getSize());
        for (int s = 1; s <= sa.getSize(); s++) {
            ImageProcessor pa = sa.getProcessor(s);
            ImageProcessor pb = sb.getProcessor(s);
            int n = pa.getWidth() * pa.getHeight();
            for (int i = 0; i < n; i++) {
                double va = pa.getf(i);
                double vb = pb.getf(i);
                if (Math.abs(va - vb) > tol) {
                    throw new AssertionError("slice " + s + " idx " + i
                            + " native=" + vb + " legacy=" + va
                            + " diff=" + Math.abs(va - vb));
                }
            }
        }
    }

    // ── single-slice rank/convolution filters ──

    @Test
    public void median_matchesIj1Macro() {
        ImagePlus legacy = makeByteSlice("legacy");
        ImagePlus nativ = makeByteSlice("native");
        String macro = "run(\"Median...\", \"radius=2 stack\");";
        runMacroLegacy(legacy, macro);
        assertTrue(FilterExecutor.runThreadSafe(nativ, macro));
        assertSlicesEqual(legacy, nativ, 0.5);
    }

    @Test
    public void mean_matchesIj1Macro() {
        ImagePlus legacy = makeByteSlice("legacy");
        ImagePlus nativ = makeByteSlice("native");
        String macro = "run(\"Mean...\", \"radius=2 stack\");";
        runMacroLegacy(legacy, macro);
        assertTrue(FilterExecutor.runThreadSafe(nativ, macro));
        assertSlicesEqual(legacy, nativ, 0.5);
    }

    @Test
    public void minimum_matchesIj1Macro() {
        ImagePlus legacy = makeByteSlice("legacy");
        ImagePlus nativ = makeByteSlice("native");
        String macro = "run(\"Minimum...\", \"radius=3 stack\");";
        runMacroLegacy(legacy, macro);
        assertTrue(FilterExecutor.runThreadSafe(nativ, macro));
        assertSlicesEqual(legacy, nativ, 0.5);
    }

    @Test
    public void maximum_matchesIj1Macro() {
        ImagePlus legacy = makeByteSlice("legacy");
        ImagePlus nativ = makeByteSlice("native");
        String macro = "run(\"Maximum...\", \"radius=3 stack\");";
        runMacroLegacy(legacy, macro);
        assertTrue(FilterExecutor.runThreadSafe(nativ, macro));
        assertSlicesEqual(legacy, nativ, 0.5);
    }

    @Test
    public void variance_matchesIj1Macro() {
        ImagePlus legacy = makeByteSlice("legacy");
        ImagePlus nativ = makeByteSlice("native");
        String macro = "run(\"Variance...\", \"radius=2 stack\");";
        runMacroLegacy(legacy, macro);
        assertTrue(FilterExecutor.runThreadSafe(nativ, macro));
        assertSlicesEqual(legacy, nativ, 0.5);
    }

    @Test
    public void gaussianBlur_matchesIj1Macro() {
        ImagePlus legacy = makeByteSlice("legacy");
        ImagePlus nativ = makeByteSlice("native");
        String macro = "run(\"Gaussian Blur...\", \"sigma=2 stack\");";
        runMacroLegacy(legacy, macro);
        assertTrue(FilterExecutor.runThreadSafe(nativ, macro));
        assertSlicesEqual(legacy, nativ, 0.5);
    }

    @Test
    public void subtractBackground_matchesIj1Macro() {
        ImagePlus legacy = makeByteSlice("legacy");
        ImagePlus nativ = makeByteSlice("native");
        String macro = "run(\"Subtract Background...\", \"rolling=20 stack\");";
        runMacroLegacy(legacy, macro);
        assertTrue(FilterExecutor.runThreadSafe(nativ, macro));
        assertSlicesEqual(legacy, nativ, 1.0);
    }

    @Test
    public void unsharpMask_matchesPreviousNativeBehaviour() {
        // Unsharp is an IHF-specific compound — assert idempotent native execution
        // (parity with macro is already validated by stage 01's regression).
        ImagePlus a = makeByteSlice("a");
        ImagePlus b = makeByteSlice("b");
        String macro = "run(\"Unsharp Mask...\", \"radius=10 mask=0.60 stack\");";
        assertTrue(FilterExecutor.runThreadSafe(a, macro));
        assertTrue(FilterExecutor.runThreadSafe(b, macro));
        assertSlicesEqual(a, b, 0.0);
    }

    // ── pixel math ──

    @Test
    public void invert_matchesIj1Macro() {
        ImagePlus legacy = makeByteSlice("legacy");
        ImagePlus nativ = makeByteSlice("native");
        String macro = "run(\"Invert\", \"stack\");";
        runMacroLegacy(legacy, macro);
        assertTrue(FilterExecutor.runThreadSafe(nativ, macro));
        assertSlicesEqual(legacy, nativ, 0.0);
    }

    @Test
    public void add_matchesIj1Macro() {
        ImagePlus legacy = makeByteSlice("legacy");
        ImagePlus nativ = makeByteSlice("native");
        String macro = "run(\"Add...\", \"value=20 stack\");";
        runMacroLegacy(legacy, macro);
        assertTrue(FilterExecutor.runThreadSafe(nativ, macro));
        assertSlicesEqual(legacy, nativ, 0.5);
    }

    @Test
    public void subtract_matchesIj1Macro() {
        ImagePlus legacy = makeByteSlice("legacy");
        ImagePlus nativ = makeByteSlice("native");
        String macro = "run(\"Subtract...\", \"value=15 stack\");";
        runMacroLegacy(legacy, macro);
        assertTrue(FilterExecutor.runThreadSafe(nativ, macro));
        assertSlicesEqual(legacy, nativ, 0.5);
    }

    @Test
    public void multiply_matchesIj1Macro() {
        // 32-bit input avoids 8-bit clamping at 255 which makes parity exact.
        FloatProcessor fp = new FloatProcessor(W, H);
        Random r = new Random(SEED);
        float[] px = (float[]) fp.getPixels();
        for (int i = 0; i < px.length; i++) px[i] = r.nextInt(50);
        ImagePlus legacy = new ImagePlus("legacy", fp.duplicate());
        ImagePlus nativ = new ImagePlus("native", fp.duplicate());
        String macro = "run(\"Multiply...\", \"value=2.0 stack\");";
        runMacroLegacy(legacy, macro);
        assertTrue(FilterExecutor.runThreadSafe(nativ, macro));
        assertSlicesEqual(legacy, nativ, 1e-5);
    }

    @Test
    public void divide_matchesIj1MacroOn32Bit() {
        FloatProcessor fp = new FloatProcessor(W, H);
        Random r = new Random(SEED);
        float[] px = (float[]) fp.getPixels();
        for (int i = 0; i < px.length; i++) px[i] = 100 + r.nextInt(400);
        ImagePlus legacy = new ImagePlus("legacy", fp.duplicate());
        ImagePlus nativ = new ImagePlus("native", fp.duplicate());
        String macro = "run(\"Divide...\", \"value=4.0 stack\");";
        runMacroLegacy(legacy, macro);
        assertTrue(FilterExecutor.runThreadSafe(nativ, macro));
        assertSlicesEqual(legacy, nativ, 1e-4);
    }

    // ── morphology smoke / parity ──

    @Test
    public void dilate_matchesIj1Macro() {
        ImagePlus legacy = makeBinaryStack("legacy", 1);
        ImagePlus nativ = makeBinaryStack("native", 1);
        String macro = "run(\"Dilate\");";
        runMacroLegacy(legacy, macro);
        assertTrue(FilterExecutor.runThreadSafe(nativ, macro));
        assertSlicesEqual(legacy, nativ, 0.0);
    }

    @Test
    public void erode_matchesIj1Macro() {
        ImagePlus legacy = makeBinaryStack("legacy", 1);
        ImagePlus nativ = makeBinaryStack("native", 1);
        String macro = "run(\"Erode\");";
        runMacroLegacy(legacy, macro);
        assertTrue(FilterExecutor.runThreadSafe(nativ, macro));
        assertSlicesEqual(legacy, nativ, 0.0);
    }

    @Test
    public void open_runsWithoutCrashing() {
        ImagePlus imp = makeBinaryStack("imp", 1);
        assertTrue(FilterExecutor.runThreadSafe(imp, "run(\"Open\");"));
    }

    @Test
    public void closeDash_runsWithoutCrashing() {
        ImagePlus imp = makeBinaryStack("imp", 1);
        assertTrue(FilterExecutor.runThreadSafe(imp, "run(\"Close-\");"));
    }

    @Test
    public void fillHoles_fillsObviousHole() {
        ByteProcessor bp = new ByteProcessor(W, H);
        // Solid foreground ring with a hole at the centre
        for (int y = 8; y < 24; y++) {
            for (int x = 8; x < 24; x++) {
                if (x == 8 || x == 23 || y == 8 || y == 23) bp.set(x, y, 255);
            }
        }
        ImagePlus imp = new ImagePlus("hole", bp);
        assertTrue(FilterExecutor.runThreadSafe(imp, "run(\"Fill Holes\");"));
        // Centre pixel must be filled to foreground.
        assertEquals(255, imp.getProcessor().get(16, 16));
    }

    @Test
    public void skeletonize_runsWithoutCrashing() {
        ImagePlus imp = makeBinaryStack("imp", 1);
        assertTrue(FilterExecutor.runThreadSafe(imp, "run(\"Skeletonize\");"));
    }

    // ── bit-depth conversion ──

    @Test
    public void convertTo8Bit_changesBitDepth() {
        ImagePlus imp = makeShortSlice("16bit-input");
        assertEquals(16, imp.getBitDepth());
        assertTrue(FilterExecutor.runThreadSafe(imp, "run(\"8-bit\");"));
        assertEquals(8, imp.getBitDepth());
    }

    @Test
    public void convertTo32Bit_changesBitDepth() {
        ImagePlus imp = makeByteSlice("8bit-input");
        assertEquals(8, imp.getBitDepth());
        assertTrue(FilterExecutor.runThreadSafe(imp, "run(\"32-bit\");"));
        assertEquals(32, imp.getBitDepth());
    }

    @Test
    public void convertTo16Bit_changesBitDepth() {
        ImagePlus imp = makeByteSlice("8bit-input");
        assertTrue(FilterExecutor.runThreadSafe(imp, "run(\"16-bit\");"));
        assertEquals(16, imp.getBitDepth());
    }

    // ── 3D filters ──

    @Test
    public void gaussianBlur3D_matchesIj1Macro() {
        ImagePlus legacy = makeByteStack("legacy", 5);
        ImagePlus nativ = makeByteStack("native", 5);
        String macro = "run(\"Gaussian Blur 3D...\", \"x=2 y=2 z=1\");";
        runMacroLegacy(legacy, macro);
        assertTrue(FilterExecutor.runThreadSafe(nativ, macro));
        assertSlicesEqual(legacy, nativ, 0.5);
    }

    @Test
    public void median3D_matchesIj1Macro() {
        ImagePlus legacy = makeByteStack("legacy", 5);
        ImagePlus nativ = makeByteStack("native", 5);
        String macro = "run(\"Median 3D...\", \"x=1 y=1 z=1\");";
        runMacroLegacy(legacy, macro);
        assertTrue(FilterExecutor.runThreadSafe(nativ, macro));
        assertSlicesEqual(legacy, nativ, 1.0);
    }

    @Test
    public void minimum3D_matchesIj1Macro() {
        ImagePlus legacy = makeByteStack("legacy", 5);
        ImagePlus nativ = makeByteStack("native", 5);
        String macro = "run(\"Minimum 3D...\", \"x=2 y=2 z=1\");";
        runMacroLegacy(legacy, macro);
        assertTrue(FilterExecutor.runThreadSafe(nativ, macro));
        assertSlicesEqual(legacy, nativ, 1.0);
    }

    // ── threshold + enhance ──

    @Test
    public void enhanceContrast_runsWithoutCrashing() {
        ImagePlus imp = makeByteSlice("imp");
        assertTrue(FilterExecutor.runThreadSafe(imp,
                "run(\"Enhance Contrast...\", \"saturated=1.0 normalize process_all\");"));
    }

    @Test
    public void autoLocalThreshold_runsIfFijiPluginPresent() {
        try {
            Class.forName("fiji.threshold.Auto_Local_Threshold");
        } catch (ClassNotFoundException notOnClasspath) {
            Assume.assumeNoException("fiji.threshold.Auto_Local_Threshold not available outside Fiji",
                    notOnClasspath);
        }
        ImagePlus imp = makeByteSlice("imp");
        assertTrue(FilterExecutor.runThreadSafe(imp,
                "run(\"Auto Local Threshold\", \"method=Bernsen radius=15 parameter_1=0 parameter_2=0 white stack\");"));
    }
}
