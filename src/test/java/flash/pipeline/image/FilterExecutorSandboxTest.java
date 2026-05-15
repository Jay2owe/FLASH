package flash.pipeline.image;

import ij.ImagePlus;
import ij.ImageStack;
import ij.WindowManager;
import ij.macro.Interpreter;
import ij.measure.ResultsTable;
import ij.process.ByteProcessor;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Verifies the FilterExecutor legacy-macro sandbox: stray windows created by
 * a custom macro must be cleaned up, the kept input image and pre-existing
 * windows must survive, and global ResultsTable / RoiManager state must be
 * reset after every legacy run.
 *
 * Tests use {@link Interpreter#batchMode} so {@code imp.show()} registers in
 * WindowManager without spawning real AWT windows, making the test reliable
 * in headless environments.
 */
public class FilterExecutorSandboxTest {

    private boolean priorBatchMode;
    private Method sandbox;

    @Before
    public void setUp() throws Exception {
        priorBatchMode = Interpreter.batchMode;
        Interpreter.batchMode = true;
        clearAllWindows();
        ResultsTable rt = ResultsTable.getResultsTable();
        if (rt != null) rt.reset();
        sandbox = FilterExecutor.class.getDeclaredMethod(
                "runLegacyMacroSandboxed", ImagePlus.class, Runnable.class);
        sandbox.setAccessible(true);
    }

    @After
    public void tearDown() {
        clearAllWindows();
        ResultsTable rt = ResultsTable.getResultsTable();
        if (rt != null) rt.reset();
        Interpreter.batchMode = priorBatchMode;
    }

    @Test
    public void wrapper_closesStrayImageCreatedByLambda() throws Exception {
        ImagePlus imp = newImage("input");

        invokeSandbox(imp, new Runnable() {
            @Override
            public void run() {
                imp.show();
                imp.setActivated();
                ImagePlus stray = newImage("stray-leaked");
                stray.show();
            }
        });

        assertWindowAbsent("stray-leaked");
    }

    @Test
    public void wrapper_closesMultipleStrayImages() throws Exception {
        ImagePlus imp = newImage("input");

        invokeSandbox(imp, new Runnable() {
            @Override
            public void run() {
                imp.show();
                newImage("stray-A").show();
                newImage("stray-B").show();
                newImage("stray-C").show();
            }
        });

        assertWindowAbsent("stray-A");
        assertWindowAbsent("stray-B");
        assertWindowAbsent("stray-C");
    }

    @Test
    public void wrapper_preservesPreExistingWindows() throws Exception {
        ImagePlus pre = newImage("pre-existing");
        pre.show();
        ImagePlus imp = newImage("input");

        invokeSandbox(imp, new Runnable() {
            @Override
            public void run() {
                imp.show();
                newImage("stray-leaked").show();
            }
        });

        assertTrue("Pre-existing window must NOT be cleaned up by sandbox",
                isWindowOpen("pre-existing"));
        assertWindowAbsent("stray-leaked");
    }

    @Test
    public void wrapper_resetsResultsTableAfterMacro() throws Exception {
        invokeSandbox(null, new Runnable() {
            @Override
            public void run() {
                ResultsTable rt = ResultsTable.getResultsTable();
                assertNotNull(rt);
                rt.incrementCounter();
                rt.addValue("dummy", 42.0);
            }
        });

        ResultsTable rt = ResultsTable.getResultsTable();
        if (rt != null) {
            assertEquals(
                    "ResultsTable must be reset to zero rows after sandbox",
                    0, rt.getCounter());
        }
    }

    @Test
    public void wrapper_handlesNullImpGracefully() throws Exception {
        invokeSandbox(null, new Runnable() {
            @Override
            public void run() {
                /* no-op */
            }
        });
        // Just asserting no exception was thrown.
    }

    @Test
    public void wrapper_runsCleanupEvenWhenLambdaThrows() throws Exception {
        ImagePlus imp = newImage("input");
        try {
            invokeSandbox(imp, new Runnable() {
                @Override
                public void run() {
                    imp.show();
                    newImage("stray-thrown").show();
                    throw new RuntimeException("boom");
                }
            });
            fail("expected RuntimeException to propagate");
        } catch (RuntimeException expected) {
            assertEquals("boom", expected.getMessage());
        }

        assertWindowAbsent("stray-thrown");
    }

    @Test
    public void wrapper_doesNotCloseImpItself() throws Exception {
        ImagePlus imp = newImage("input");

        invokeSandbox(imp, new Runnable() {
            @Override
            public void run() {
                imp.show();
            }
        });

        assertTrue("Kept input image must survive sandbox cleanup",
                isWindowOpen("input"));
    }

    @Test
    public void wrapper_adoptsResultWhenMacroClosesOriginal() throws Exception {
        final ImagePlus imp = newImage("input");

        invokeSandbox(imp, new Runnable() {
            @Override public void run() {
                imp.show();
                imp.setActivated();
                ImagePlus result = newImage("input");
                result.getProcessor().set(0, 0, 123);
                imp.changes = false;
                imp.close();
                result.show();
                result.setActivated();
            }
        });

        assertEquals(123, imp.getProcessor().getPixel(0, 0));
    }

    /** The public legacy entry point must route through the sandbox without throwing on a no-op macro. */
    @Test
    public void publicEntryPoint_runMacroString_smoke() {
        ImagePlus imp = newImage("input");
        FilterExecutor.runMacroString(imp, "// no-op\n");

        ResultsTable rt = ResultsTable.getResultsTable();
        if (rt != null) {
            assertEquals(0, rt.getCounter());
        }
    }

    private void invokeSandbox(ImagePlus imp, Runnable macroCall) throws Exception {
        try {
            sandbox.invoke(null, imp, macroCall);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException) throw (RuntimeException) cause;
            if (cause instanceof Error) throw (Error) cause;
            throw new RuntimeException(cause);
        }
    }

    private static ImagePlus newImage(String title) {
        ImageStack s = new ImageStack(4, 4);
        s.addSlice(new ByteProcessor(4, 4));
        return new ImagePlus(title, s);
    }

    private static void clearAllWindows() {
        int[] ids = WindowManager.getIDList();
        if (ids == null) return;
        for (int id : ids) {
            ImagePlus w = WindowManager.getImage(id);
            if (w != null) {
                w.changes = false;
                w.close();
            }
        }
    }

    private static boolean isWindowOpen(String title) {
        int[] ids = WindowManager.getIDList();
        if (ids == null) return false;
        for (int id : ids) {
            ImagePlus w = WindowManager.getImage(id);
            if (w != null && title.equals(w.getTitle())) return true;
        }
        return false;
    }

    private static void assertWindowAbsent(String title) {
        int[] ids = WindowManager.getIDList();
        if (ids == null) return;
        for (int id : ids) {
            ImagePlus w = WindowManager.getImage(id);
            if (w != null) {
                assertNotEquals(
                        "Sandbox should have closed window: " + title,
                        title, w.getTitle());
            }
        }
    }
}
