package flash.pipeline.runtime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashSet;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Guards the stale-TensorFlow-crash-sentinel auto-clear. Regression target:
 * an orphaned {@code lib/<platform>/.crashed} file made Fiji refuse to load
 * TensorFlow ("Could not load TensorFlow" dialog) and StarDist 3D stopped
 * working even though TensorFlow itself was fine. See
 * {@code docs/fixed_bugs/tensorflow-stale-crash-sentinel.md}.
 *
 * <p>Only the pure file logic ({@code evaluate}/{@code noteLoadedOk}) is tested,
 * fed a temp directory; the public entry points resolve the real Fiji.app dir
 * and log via ImageJ, which is out of scope for a headless unit test.
 */
public class TensorFlowCrashSentinelTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private File sentinel(File dir) {
        return new File(dir, TensorFlowCrashSentinel.SENTINEL_NAME);
    }

    private File marker(File dir) {
        return new File(dir, TensorFlowCrashSentinel.MARKER_NAME);
    }

    @Test
    public void noSentinel_isNoOp() throws IOException {
        File dir = tmp.newFolder("win64");
        assertEquals(TensorFlowCrashSentinel.Outcome.NONE,
                TensorFlowCrashSentinel.evaluate(dir));
        assertFalse("no marker should be created when there was nothing to clear",
                marker(dir).exists());
    }

    @Test
    public void orphanedSentinel_isClearedAndMarked() throws IOException {
        File dir = tmp.newFolder("win64");
        assertTrue(sentinel(dir).createNewFile());

        assertEquals(TensorFlowCrashSentinel.Outcome.CLEARED,
                TensorFlowCrashSentinel.evaluate(dir));

        assertFalse("stale sentinel must be deleted", sentinel(dir).exists());
        assertTrue("one-retry marker must be written", marker(dir).exists());
    }

    @Test
    public void sentinelReappearingAfterClear_isLeftInPlace() throws IOException {
        File dir = tmp.newFolder("win64");
        // Simulate: we cleared once (marker present) and a fresh sentinel is back,
        // i.e. TensorFlow crashed again -> genuine, repeatable crash.
        assertTrue(sentinel(dir).createNewFile());
        assertTrue(marker(dir).createNewFile());

        assertEquals(TensorFlowCrashSentinel.Outcome.REPEATED,
                TensorFlowCrashSentinel.evaluate(dir));

        assertTrue("a repeatable-crash sentinel must NOT be deleted",
                sentinel(dir).exists());
    }

    @Test
    public void successfulLoad_reArmsAutoClear() throws IOException {
        File dir = tmp.newFolder("win64");
        assertTrue(marker(dir).createNewFile());

        TensorFlowCrashSentinel.noteLoadedOk(dir);

        assertFalse("a successful load clears the marker so the next orphan is auto-cleared",
                marker(dir).exists());

        // And a fresh orphan after a good run is cleared again, not treated as repeatable.
        assertTrue(sentinel(dir).createNewFile());
        assertEquals(TensorFlowCrashSentinel.Outcome.CLEARED,
                TensorFlowCrashSentinel.evaluate(dir));
    }

    @Test
    public void evaluate_toleratesMissingDirectory() {
        File missing = new File(tmp.getRoot(), "does-not-exist");
        assertEquals(TensorFlowCrashSentinel.Outcome.NONE,
                TensorFlowCrashSentinel.evaluate(missing));
        // noteLoadedOk on a missing dir must not throw.
        TensorFlowCrashSentinel.noteLoadedOk(missing);
        TensorFlowCrashSentinel.noteLoadedOk(null);
    }

    @Test
    public void platformSubdir_matchesFijiNativeLayout() {
        String sub = TensorFlowCrashSentinel.platformSubdir();
        // Whatever host runs the test, the result must be one of Fiji's lib/ subfolders.
        HashSet<String> valid = new HashSet<String>(
                Arrays.asList("win64", "win32", "macosx", "linux64", "linux32"));
        assertTrue("platform subdir '" + sub + "' must match a Fiji lib/ folder",
                sub != null && valid.contains(sub));
    }
}
