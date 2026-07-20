package flash.pipeline.ui.variations.integration;

import flash.pipeline.cellpose.CellposeRuntime;
import flash.pipeline.testutil.TestWait;
import flash.pipeline.ui.variations.CropSpec;
import flash.pipeline.ui.variations.VariationResult;
import flash.pipeline.ui.variations.strategy.CellposePersistent;

import ij.ImagePlus;

import org.junit.Assume;
import org.junit.Ignore;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Deterministic persistent-process fixtures plus the opt-in live Cellpose test.
 * <p>
 * Remove {@link Ignore} from the live test and run after configuring Cellpose:
 * {@code .\mvnw.cmd '-Denforcer.skip=true' '-Dtest=flash.pipeline.ui.variations.integration.CellposePersistentIntegrationTest' test}
 */
public class CellposePersistentIntegrationTest {

    @Test
    public void persistentStubSignalsReadyAndHandlesRequestsBeforeCrash() throws Exception {
        CellposeOneShotIntegrationTest.StubProcess helper =
                CellposeOneShotIntegrationTest.StubProcess.launch(
                        CellposeOneShotIntegrationTest.StubMode.PERSISTENT);
        long pid = helper.rootPid();
        try {
            helper.awaitReady();
            helper.writeLine("request-one");
            helper.awaitStdout("RESULT=request-one");
            helper.awaitStderr("TRACE=request-one");

            String unicodeRequest = "r\u00e9sultat-\u03b2";
            helper.writeLine(unicodeRequest);
            helper.awaitStdout("RESULT=" + unicodeRequest);
            helper.awaitStderr("TRACE=" + unicodeRequest);

            helper.writeLine("crash");
            assertTrue("Persistent crash helper did not exit",
                    helper.awaitExit(5_000L));
            helper.awaitDrainers();
            assertEquals(37, helper.exitCode());
            assertTrue(helper.stderrContains("PERSISTENT_CRASH"));
        } finally {
            helper.close();
        }
        CellposeOneShotIntegrationTest.StubProcess.awaitPidExit(pid);
    }

    @Test
    public void persistentCancellationTerminatesRootDescendantAndDrainers()
            throws Exception {
        final CellposeOneShotIntegrationTest.StubProcess helper =
                CellposeOneShotIntegrationTest.StubProcess.launch(
                        CellposeOneShotIntegrationTest.StubMode.TREE);
        long rootPid = helper.rootPid();
        long childPid = -1L;
        try {
            helper.awaitReady();
            TestWait.await("stub descendant PID", 5_000L,
                    new TestWait.Condition() {
                        @Override public boolean isMet() {
                            return helper.descendantPid() > 0L;
                        }
                    });
            childPid = helper.descendantPid();
            assertTrue("Root process did not remain alive",
                    CellposeOneShotIntegrationTest.StubProcess.isPidAlive(rootPid));
            assertTrue("Descendant process did not remain alive",
                    CellposeOneShotIntegrationTest.StubProcess.isPidAlive(childPid));
            assertFalse("Tree helper unexpectedly exited",
                    helper.awaitExit(100L));

            helper.cancel();
            helper.assertStopped();
            helper.awaitDrainers();
            helper.close();
            helper.close();
        } finally {
            helper.close();
        }
        CellposeOneShotIntegrationTest.StubProcess.awaitPidExit(rootPid);
        if (childPid > 0L) {
            CellposeOneShotIntegrationTest.StubProcess.awaitPidExit(childPid);
        }
    }

    @Test(timeout = 15_000L)
    public void persistentHungRequestHasBoundedIdempotentCleanup() throws Exception {
        CellposeOneShotIntegrationTest.StubProcess helper =
                CellposeOneShotIntegrationTest.StubProcess.launch(
                        CellposeOneShotIntegrationTest.StubMode.PERSISTENT);
        long pid = helper.rootPid();
        try {
            helper.awaitReady();
            helper.writeLine("hang");
            helper.awaitStdout("HANGING");

            helper.cancel();
            helper.close();
            helper.close();
            helper.awaitDrainers();
            helper.assertStopped();
        } finally {
            helper.close();
        }
        CellposeOneShotIntegrationTest.StubProcess.awaitPidExit(pid);
    }

    @Ignore("TODO(cellpose-live-runtime): requires an installed Cellpose runtime; covered by the live-engine validation lane.")
    @Test
    public void persistentHelperKeepsLaterCellOverheadBelowFiftyPercent() throws Exception {
        CellposeRuntime.Status status = CellposeRuntime.probeConfigured();
        Assume.assumeTrue(status.message + "\n" + status.details, status.ready);

        ImagePlus source = VariationIntegrationTestSupport.loadSyntheticBlobStack();
        CellposePersistent strategy = new CellposePersistent(source,
                CropSpec.full(),
                null,
                new VariationIntegrationTestSupport.RealCellposePreviewAdapter(),
                VariationIntegrationTestSupport.cellposeBaseParameters(),
                null,
                "DAPI");
        List<VariationResult> results = new ArrayList<VariationResult>();

        strategy.dispatch(VariationIntegrationTestSupport.cellposeThreeCellSweep(),
                results::add,
                () -> false);

        assertEquals(3, results.size());
        for (int i = 0; i < results.size(); i++) {
            VariationResult result = results.get(i);
            assertFalse(result.hasError());
            assertNotNull(result.label());
            assertTrue("Expected Cellpose objects in cell " + i,
                    result.nObjects() > 0);
        }

        long first = Math.max(1L, results.get(0).durationMs());
        double laterAverage = (results.get(1).durationMs()
                + results.get(2).durationMs()) / 2.0d;
        assertTrue("Later cells averaged " + laterAverage
                        + " ms; first cell took " + first + " ms",
                laterAverage <= first * 1.5d);
    }
}
