package flash.pipeline.ui.config;

import flash.pipeline.stardist.StarDist3DRunner;
import flash.pipeline.testutil.EdtUncaughtExceptionCapture;
import flash.pipeline.testutil.TestWait;
import flash.pipeline.ui.preview.PreviewPairPanel;
import flash.pipeline.ui.variations.ParameterCombo;
import flash.pipeline.ui.variations.ParameterId;
import ij.ImagePlus;
import ij.ImageStack;
import ij.measure.ResultsTable;
import ij.process.ByteProcessor;
import ij.process.ImageProcessor;
import org.junit.Test;

import javax.swing.AbstractButton;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import javax.swing.text.JTextComponent;
import java.awt.Component;
import java.awt.Container;
import java.util.Arrays;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class StarDistParameterStageTest {

    @Test
    public void parsesAndRendersMethodToken() {
        String token = "stardist:0.7:0.2:linking=15.0:gapClosing=16.0:"
                + "frameGap=2:area=3.0-99.0:quality=0.8:intensity=22.0:"
                + "model=stardist_versatile_fluo";

        StarDistParameterStage.Parameters params = StarDistParameterStage.parseMethod(token);

        assertEquals(token, StarDistParameterStage.formatMethod(params));
    }

    @Test
    public void variationsButtonPresent_andDisabledWithoutPreview() {
        StarDistParameterStage stage = new StarDistParameterStage(
                new RecordingStore("stardist:0.5:0.4"),
                new RecordingPreviewAdapter());

        JComponent controls = stage.buildControls(context(), new RecordingActions());
        JButton variations = findButton(controls, "Parameter Variations...");

        assertNotNull(variations);
        assertFalse(variations.isEnabled());
        assertEquals("Run/prepare a preview before opening parameter variations.",
                variations.getToolTipText());

        stage.onEnter(context(), new PreviewPairPanel("Original", "Adjusted"));

        assertTrue(variations.isEnabled());
    }

    @Test
    public void applyCombo_writesFieldsAndTriggersRefresh() throws Exception {
        RecordingPreviewAdapter adapter = new RecordingPreviewAdapter();
        StarDistParameterStage stage = new StarDistParameterStage(
                new RecordingStore("stardist:0.5:0.4"),
                adapter);

        stage.buildControls(context(), new RecordingActions());
        stage.onEnter(context(), new PreviewPairPanel("Original", "Adjusted"));
        adapter.previewRuns = 0;

        stage.applyVariationComboForTest(ParameterCombo.builder()
                .put(ParameterId.PROB_THRESH, Double.valueOf(0.8d))
                .put(ParameterId.NMS_THRESH, Double.valueOf(0.25d))
                .put(ParameterId.LINKING_MAX, Double.valueOf(11.0d))
                .put(ParameterId.GAP_CLOSING_MAX, Double.valueOf(12.0d))
                .put(ParameterId.FRAME_GAP, Integer.valueOf(3))
                .put(ParameterId.AREA_MIN, Double.valueOf(5.0d))
                .put(ParameterId.AREA_MAX, Double.valueOf(40.0d))
                .put(ParameterId.QUALITY_MIN, Double.valueOf(0.6d))
                .put(ParameterId.INTENSITY_MIN, Double.valueOf(21.0d))
                .build());
        waitForPreviewRuns(adapter, 1);

        assertEquals("stardist:0.8:0.25:linking=11.0:gapClosing=12.0:"
                        + "frameGap=3:area=5.0-40.0:quality=0.6:intensity=21.0:"
                        + "model=stardist_versatile_fluo",
                stage.currentMethodForTest());
        assertNotNull(adapter.lastPreviewParameters);
        assertEquals(0.8d, adapter.lastPreviewParameters.probabilityThreshold, 0.001);
        assertEquals(0.25d, adapter.lastPreviewParameters.nmsThreshold, 0.001);
    }

    @Test
    public void allParameterFieldsContributeToMethodToken() {
        StarDistParameterStage stage = new StarDistParameterStage(
                new RecordingStore("stardist:0.5:0.4"),
                new RecordingPreviewAdapter());

        stage.buildControls(context(), new RecordingActions());
        stage.setProbabilityForTest("0.7");
        stage.setNmsForTest("0.2");
        stage.setLinkingForTest("15");
        stage.setGapClosingForTest("16");
        stage.setFrameGapForTest("2");
        stage.setAreaMinForTest("3");
        stage.setAreaMaxForTest("99");
        stage.setQualityMinForTest("0.8");
        stage.setIntensityMinForTest("22");

        assertEquals("stardist:0.7:0.2:linking=15.0:gapClosing=16.0:"
                        + "frameGap=2:area=3.0-99.0:quality=0.8:intensity=22.0:"
                        + "model=stardist_versatile_fluo",
                stage.currentMethodForTest());
    }

    @Test
    public void detectionThresholdsAreClampedToUnitRange() {
        StarDistParameterStage.Parameters params = new StarDistParameterStage.Parameters(
                1.5d, -0.2d, 5.0d, 5.0d, 1, 0.0d,
                Double.POSITIVE_INFINITY, 0.0d, 0.0d);

        assertEquals(1.0d, params.probabilityThreshold, 0.001);
        assertEquals(0.0d, params.nmsThreshold, 0.001);
    }

    @Test
    public void textFieldEditMarksPreviewStaleWithoutRunningPreview() {
        RecordingStore store = new RecordingStore("stardist:0.5:0.4");
        RecordingPreviewAdapter adapter = new RecordingPreviewAdapter();
        RecordingActions actions = new RecordingActions();
        StarDistParameterStage stage = new StarDistParameterStage(store, adapter);

        stage.buildControls(context(), actions);
        stage.onEnter(context(), new PreviewPairPanel("Original", "Adjusted"));
        adapter.previewRuns = 0;

        stage.setProbabilityForTest("0.9");

        assertTrue(stage.isPreviewStaleForTest());
        assertTrue(actions.status.contains("Preview"));
        assertTrue(actions.previewButtonStale);
        assertEquals("\u25CF Run Preview", actions.previewButton.getText());
        assertEquals("Field edits must not execute StarDist preview",
                0, adapter.previewRuns);
    }

    @Test
    public void controlsUseCompactGroupedRowsWithoutHelperText() {
        StarDistParameterStage stage = new StarDistParameterStage(
                new RecordingStore("stardist:0.5:0.4"),
                new RecordingPreviewAdapter());

        JComponent controls = stage.buildControls(context(), new RecordingActions());

        assertContainsText(controls, "Detection:");
        assertContainsText(controls, "Probability");
        assertContainsText(controls, "Linking:");
        assertContainsText(controls, "Gap distance");
        assertContainsText(controls, "Filters:");
        assertContainsText(controls, "Area min");
        assertContainsText(controls, "Area max");
        assertContainsText(controls, "Quality min");
        assertContainsText(controls, "Final 3D voxel volume:");
        assertContainsText(controls, "Min");
        assertContainsText(controls, "Max");
        assertNotContainsText(controls, "Object size:");
        assertFalse("Area min belongs in Filters, not Detection.",
                siblingContainerContains(controls, "Detection:", "Area min"));
        assertFalse("Area max belongs in Filters, not Detection.",
                siblingContainerContains(controls, "Detection:", "Area max"));
        assertTrue("Area min should be grouped with filters.",
                siblingContainerContains(controls, "Filters:", "Area min"));
        assertTrue("Area max should be grouped with filters.",
                siblingContainerContains(controls, "Filters:", "Area max"));
        assertContainsText(controls, "Run Preview");
        assertNotContainsText(controls, "Minimum confidence required for StarDist");
        assertNotContainsText(controls, "Edit parameters, then press");
    }

    @Test
    public void previewRunsOnlyWhenExplicitlyRequested() throws Exception {
        RecordingStore store = new RecordingStore("stardist:0.5:0.4");
        RecordingPreviewAdapter adapter = new RecordingPreviewAdapter();
        RecordingActions actions = new RecordingActions();
        StarDistParameterStage stage = new StarDistParameterStage(store, adapter);

        stage.buildControls(context(), actions);
        stage.onEnter(context(), new PreviewPairPanel("Original", "Adjusted"));

        assertEquals(0, adapter.previewRuns);
        assertEquals("\u25CF Run Preview", actions.previewButton.getText());

        stage.runPreviewNowForTest();

        assertEquals(1, adapter.previewRuns);
        assertFalse(stage.isPreviewStaleForTest());
        assertNotNull(actions.adjustedPreview);
        assertEquals("Objects: 2 ready", actions.status);
        assertFalse(actions.previewButtonStale);
        assertEquals("Run Preview", actions.previewButton.getText());
        assertEquals(3, stage.largePreviewPaneCountForTest());
        assertEquals(0, adapter.closeCount(adapter.lastPreviewResult));
        stage.onLeave(context());
        assertEquals(1, adapter.closeCount(adapter.lastPreviewResult));
    }

    @Test
    public void replacementCloseFailureRetainsOldAndKeepsNewUntilDispose()
            throws Exception {
        RecordingPreviewAdapter adapter = new RecordingPreviewAdapter();
        StarDistParameterStage stage = new StarDistParameterStage(
                new RecordingStore("stardist:0.5:0.4:5:5:2"), adapter);
        ConfigQcContext context = context();
        stage.buildControls(context, new RecordingActions());
        stage.onEnter(context, new PreviewPairPanel("Original", "Adjusted"));

        stage.runPreviewNowForTest();
        ImagePlus first = adapter.lastPreviewResult;
        RuntimeException closeFailure = new IllegalStateException("old close failed");
        adapter.closeFailure = closeFailure;
        Thread.currentThread().interrupt();
        try {
            stage.runPreviewNowForTest();
            fail("Expected old-preview cleanup failure");
        } catch (IllegalStateException expected) {
            assertSame(closeFailure, expected);
        } finally {
            assertTrue("replacement cleanup must restore caller interruption",
                    Thread.currentThread().isInterrupted());
            Thread.interrupted();
        }

        ImagePlus second = adapter.lastPreviewResult;
        assertNotSame(first, second);
        assertSame("new preview must stay installed after old cleanup fails",
                second, stage.labelPreviewForTest());
        assertEquals(1, adapter.closeCount(first));
        assertEquals(0, adapter.successfulCloseCount(first));
        assertEquals(0, adapter.closeCount(second));
        assertFalse("adapter close must run with the caller interrupt cleared",
                adapter.closeObservedInterrupt);

        adapter.nextPreviewResult = first;
        adapter.closeFailure = null;
        stage.runPreviewNowForTest();
        assertSame("retained identity reused as the active preview must remain open",
                first, stage.labelPreviewForTest());
        assertEquals(1, adapter.closeCount(first));
        assertEquals(0, adapter.successfulCloseCount(first));
        assertEquals(1, adapter.closeCount(second));
        stage.onLeave(context);
        stage.onLeave(context);
        assertEquals("failed old close must be retried once", 2, adapter.closeCount(first));
        assertEquals(1, adapter.successfulCloseCount(first));
        assertEquals(1, adapter.closeCount(second));
        assertEquals(1, adapter.successfulCloseCount(second));
    }

    @Test
    public void countFailureClosesUnpublishedLabelExactlyOnce() throws Exception {
        RecordingPreviewAdapter adapter = new RecordingPreviewAdapter();
        RuntimeException countFailure = new IllegalStateException("count failed");
        adapter.countFailure = countFailure;
        ConfigQcContext context = context();
        StarDistParameterStage stage = new StarDistParameterStage(
                new RecordingStore("stardist:0.5:0.4"), adapter);
        stage.buildControls(context, new RecordingActions());
        stage.onEnter(context, new PreviewPairPanel("Original", "Adjusted"));

        try {
            stage.runPreviewNowForTest();
            fail("Expected count failure");
        } catch (RuntimeException failure) {
            assertSame(countFailure, failure);
        }

        assertEquals(1, adapter.closeCount(adapter.lastPreviewResult));
    }

    @Test
    public void borrowedSourceReturnedAsLabelIsNotClosedByFailedProducer() throws Exception {
        RecordingPreviewAdapter adapter = new RecordingPreviewAdapter();
        adapter.returnFilteredSource = true;
        adapter.countFailure = new IllegalStateException("count failed");
        ConfigQcContext context = context();
        StarDistParameterStage stage = new StarDistParameterStage(
                new RecordingStore("stardist:0.5:0.4"), adapter);
        stage.buildControls(context, new RecordingActions());
        stage.onEnter(context, new PreviewPairPanel("Original", "Adjusted"));

        try {
            stage.runPreviewNowForTest();
            fail("Expected count failure");
        } catch (IllegalStateException expected) {
            // The stage still owns its filtered source.
        }

        assertSame(adapter.filteredSource, adapter.lastPreviewResult);
        assertEquals(0, adapter.closeCount(adapter.lastPreviewResult));
        stage.onLeave(context);
        assertEquals(1, adapter.closeCount(adapter.lastPreviewResult));
    }

    @Test
    public void vmFatalCleanupOutranksOrdinaryCountFailure() throws Exception {
        RecordingPreviewAdapter adapter = new RecordingPreviewAdapter();
        RuntimeException countFailure = new IllegalStateException("count failed");
        PreviewVmError cleanupFailure = new PreviewVmError("close failed");
        adapter.countFailure = countFailure;
        adapter.closeFailure = cleanupFailure;
        ConfigQcContext context = context();
        StarDistParameterStage stage = new StarDistParameterStage(
                new RecordingStore("stardist:0.5:0.4"), adapter);
        stage.buildControls(context, new RecordingActions());
        stage.onEnter(context, new PreviewPairPanel("Original", "Adjusted"));

        try {
            stage.runPreviewNowForTest();
            fail("Expected VM-fatal cleanup failure");
        } catch (PreviewVmError failure) {
            assertSame(cleanupFailure, failure);
            assertEquals(1, failure.getSuppressed().length);
            assertSame(countFailure, failure.getSuppressed()[0]);
        }

        assertEquals(1, adapter.closeCount(adapter.lastPreviewResult));
        ImagePlus unpublished = adapter.lastPreviewResult;
        adapter.countFailure = null;
        adapter.closeFailure = null;
        stage.onLeave(context);
        assertEquals("failed unpublished cleanup must be retried on disposal",
                2, adapter.closeCount(unpublished));
        assertEquals(1, adapter.successfulCloseCount(unpublished));
    }

    @Test
    public void ordinaryCleanupFailureIsSuppressedUnderPrimaryFailure() throws Exception {
        RecordingPreviewAdapter adapter = new RecordingPreviewAdapter();
        RuntimeException countFailure = new IllegalStateException("count failed");
        RuntimeException cleanupFailure = new IllegalArgumentException("close failed");
        adapter.countFailure = countFailure;
        adapter.closeFailure = cleanupFailure;
        ConfigQcContext context = context();
        StarDistParameterStage stage = new StarDistParameterStage(
                new RecordingStore("stardist:0.5:0.4"), adapter);
        stage.buildControls(context, new RecordingActions());
        stage.onEnter(context, new PreviewPairPanel("Original", "Adjusted"));

        try {
            stage.runPreviewNowForTest();
            fail("Expected primary count failure");
        } catch (RuntimeException failure) {
            assertSame(countFailure, failure);
            assertEquals(1, failure.getSuppressed().length);
            assertSame(cleanupFailure, failure.getSuppressed()[0]);
        }

        assertEquals(1, adapter.closeCount(adapter.lastPreviewResult));
    }

    @Test
    public void callbackFailureClosesProvisionalLabelExactlyOnce() throws Exception {
        RecordingPreviewAdapter adapter = new RecordingPreviewAdapter();
        RecordingActions actions = new RecordingActions();
        RuntimeException callbackFailure = new IllegalStateException("callback failed");
        ConfigQcContext context = context();
        StarDistParameterStage stage = new StarDistParameterStage(
                new RecordingStore("stardist:0.5:0.4"), adapter);
        stage.buildControls(context, actions);
        stage.onEnter(context, new PreviewPairPanel("Original", "Adjusted"));
        actions.statusFailure = callbackFailure;
        actions.statusFailurePrefix = "Objects:";

        try {
            stage.runPreviewNowForTest();
            fail("Expected callback failure");
        } catch (RuntimeException failure) {
            assertSame(callbackFailure, failure);
        }

        assertEquals(1, adapter.closeCount(adapter.lastPreviewResult));
        stage.onLeave(context);
        assertEquals(1, adapter.closeCount(adapter.lastPreviewResult));
    }

    @Test
    public void interruptedCountRestoresInterruptAndClosesResult() throws Exception {
        RecordingPreviewAdapter adapter = new RecordingPreviewAdapter();
        InterruptedException interruption = new InterruptedException("stop");
        adapter.countFailure = interruption;
        ConfigQcContext context = context();
        StarDistParameterStage stage = new StarDistParameterStage(
                new RecordingStore("stardist:0.5:0.4"), adapter);
        stage.buildControls(context, new RecordingActions());
        stage.onEnter(context, new PreviewPairPanel("Original", "Adjusted"));
        Thread.interrupted();
        try {
            stage.runPreviewNowForTest();
            fail("Expected interruption");
        } catch (InterruptedException failure) {
            assertSame(interruption, failure);
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
        assertEquals(1, adapter.closeCount(adapter.lastPreviewResult));
    }

    @Test
    public void asyncInterruptedCountDoesNotInterruptOrEscapeEdt() throws Exception {
        EdtUncaughtExceptionCapture capture = EdtUncaughtExceptionCapture.install();
        try {
            RecordingPreviewAdapter adapter = new RecordingPreviewAdapter();
            InterruptedException interruption = new InterruptedException("count interrupted");
            adapter.countFailure = interruption;
            ConfigQcContext context = context();
            StarDistParameterStage stage = new StarDistParameterStage(
                    new RecordingStore("stardist:0.5:0.4"), adapter);
            stage.buildControls(context, new RecordingActions());
            stage.onEnter(context, new PreviewPairPanel("Original", "Adjusted"));

            stage.runPreviewOnWorkerForTest();
            waitForWorkerCompletion(stage);

            assertSame(interruption, stage.previewWorkerCompletionFailureForTest());
            assertFalse(stage.previewWorkerCompletionObservedInterruptForTest());
            assertEquals(1, adapter.closeCount(adapter.lastPreviewResult));
            stage.onLeave(context);
            assertEquals(1, adapter.closeCount(adapter.filteredSource));
        } finally {
            capture.close();
        }
        assertNull("install-side interruption must not escape on EDT", capture.failure());
    }

    @Test
    public void cancellationClosesLateWorkerResultExactlyOnce() throws Exception {
        RecordingPreviewAdapter adapter = new RecordingPreviewAdapter();
        adapter.blockUntilCancelled = true;
        ConfigQcContext context = context();
        StarDistParameterStage stage = new StarDistParameterStage(
                new RecordingStore("stardist:0.5:0.4"), adapter);
        stage.buildControls(context, new RecordingActions());
        stage.onEnter(context, new PreviewPairPanel("Original", "Adjusted"));

        stage.runPreviewOnWorkerForTest();
        assertTrue(adapter.previewStarted.await(2L, TimeUnit.SECONDS));
        stage.onLeave(context);
        TestWait.until("cancelled StarDist result was not closed", new TestWait.Condition() {
            @Override public boolean isMet() {
                return adapter.lastPreviewResult != null
                        && adapter.closeCount(adapter.lastPreviewResult) == 1;
            }
        }, 3000L);
        assertEquals(1, adapter.closeCount(adapter.lastPreviewResult));
    }

    @Test
    public void cancellationIgnoringWorkerKeepsInputUsableUntilPhysicalReturn() throws Exception {
        RecordingPreviewAdapter adapter = new RecordingPreviewAdapter();
        adapter.blockUntilReleased = true;
        adapter.returnFilteredSource = true;
        ConfigQcContext context = context();
        StarDistParameterStage stage = new StarDistParameterStage(
                new RecordingStore("stardist:0.5:0.4"), adapter);
        stage.buildControls(context, new RecordingActions());
        stage.onEnter(context, new PreviewPairPanel("Original", "Adjusted"));

        stage.runPreviewOnWorkerForTest();
        assertTrue(adapter.previewStarted.await(2L, TimeUnit.SECONDS));
        stage.onLeave(context);
        assertEquals("leased input must remain open after onLeave",
                0, adapter.closeCount(adapter.filteredSource));
        adapter.allowPreviewReturn.countDown();
        assertTrue(adapter.previewReturned.await(2L, TimeUnit.SECONDS));
        waitForWorkerCompletion(stage);

        assertTrue("adapter must still be able to read the leased input",
                adapter.inputUsableAtReturn);
        assertEquals("leased input closes once after physical return",
                1, adapter.closeCount(adapter.filteredSource));
        assertSame(adapter.filteredSource, adapter.lastPreviewResult);
        assertSame(null, stage.previewWorkerCompletionFailureForTest());
    }

    @Test
    public void queuedCancellationReleasesInputWithoutStartingAdapter() throws Exception {
        RecordingPreviewAdapter adapter = new RecordingPreviewAdapter();
        ConfigQcContext context = context();
        StarDistParameterStage stage = new StarDistParameterStage(
                new RecordingStore("stardist:0.5:0.4"), adapter);
        stage.setPreviewWorkerExecutorForTest(TestPreviewWorkerExecutors.QUEUED);
        stage.buildControls(context, new RecordingActions());
        stage.onEnter(context, new PreviewPairPanel("Original", "Adjusted"));

        stage.runPreviewOnWorkerForTest();
        stage.onLeave(context);
        waitForWorkerCompletion(stage);

        assertEquals(0, adapter.previewRuns);
        assertEquals(1, adapter.closeCount(adapter.filteredSource));
        assertFalse(stage.previewWorkerActiveForTest());
    }

    @Test
    public void rejectedExecutionRecordsFailureAndReleasesLease() {
        final RuntimeException rejection = new java.util.concurrent.RejectedExecutionException(
                "rejected");
        RecordingPreviewAdapter adapter = new RecordingPreviewAdapter();
        ConfigQcContext context = context();
        StarDistParameterStage stage = new StarDistParameterStage(
                new RecordingStore("stardist:0.5:0.4"), adapter);
        stage.setPreviewWorkerExecutorForTest(
                TestPreviewWorkerExecutors.rejecting(rejection));
        stage.buildControls(context, new RecordingActions());
        stage.onEnter(context, new PreviewPairPanel("Original", "Adjusted"));

        stage.runPreviewOnWorkerForTest();

        assertSame(rejection, stage.previewWorkerCompletionFailureForTest());
        assertEquals(0, adapter.previewRuns);
        assertFalse(stage.previewWorkerActiveForTest());
        stage.onLeave(context);
        assertEquals(1, adapter.closeCount(adapter.filteredSource));
    }

    @Test
    public void lateCleanupFailuresAreRetriedAndHandedOffAfterPhysicalExit() throws Exception {
        assertLateCleanupFailure(new IllegalStateException("late close failed"));
        assertLateCleanupFailure(new ThreadDeath());
    }

    @Test
    public void cancelledNonfatalFailuresDoNotInterruptOrEscapeEdt() throws Exception {
        assertCancelledNonfatalFailure(new InterruptedException("cancelled"), true);
        assertCancelledNonfatalFailure(new IllegalStateException("ordinary failure"), false);
    }

    private static void assertCancelledNonfatalFailure(
            final Throwable workerFailure, boolean expectWorkerInterrupt) throws Exception {
        EdtUncaughtExceptionCapture capture = EdtUncaughtExceptionCapture.install();
        try {
            RecordingPreviewAdapter adapter = new RecordingPreviewAdapter();
            adapter.blockUntilReleased = true;
            adapter.previewFailureAfterRelease = workerFailure;
            ConfigQcContext context = context();
            StarDistParameterStage stage = new StarDistParameterStage(
                    new RecordingStore("stardist:0.5:0.4"), adapter);
            stage.buildControls(context, new RecordingActions());
            stage.onEnter(context, new PreviewPairPanel("Original", "Adjusted"));

            stage.runPreviewOnWorkerForTest();
            assertTrue(adapter.previewStarted.await(2L, TimeUnit.SECONDS));
            stage.onLeave(context);
            adapter.allowPreviewReturn.countDown();
            assertTrue(adapter.previewReturned.await(2L, TimeUnit.SECONDS));
            waitForWorkerCompletion(stage);

            assertSame(workerFailure, stage.previewWorkerCompletionFailureForTest());
            if (expectWorkerInterrupt) {
                assertTrue(stage.previewWorkerFailureObservedInterruptForTest());
            } else {
                assertFalse(stage.previewWorkerFailureObservedInterruptForTest());
            }
            assertFalse(stage.previewWorkerCompletionObservedInterruptForTest());
            assertEquals(1, adapter.closeCount(adapter.filteredSource));
        } finally {
            capture.close();
        }
        assertNull("cancelled nonfatal failure must not escape on EDT", capture.failure());
    }

    @Test
    public void fatalDeferredInputCloseOutranksCancelledWorkerFailure() throws Exception {
        assertFatalDeferredInputClose(new ThreadDeath());
        assertFatalDeferredInputClose(new PreviewVmError("fatal close"));
    }

    private static void assertFatalDeferredInputClose(final Throwable fatalFailure)
            throws Exception {
        RecordingPreviewAdapter adapter = new RecordingPreviewAdapter();
        IllegalStateException workerFailure = new IllegalStateException("worker failed");
        adapter.blockUntilReleased = true;
        adapter.previewFailureAfterRelease = workerFailure;
        adapter.failNextFilteredSourceClose = true;
        adapter.closeFailure = fatalFailure;
        ConfigQcContext context = context();
        StarDistParameterStage stage = new StarDistParameterStage(
                new RecordingStore("stardist:0.5:0.4"), adapter);
        stage.buildControls(context, new RecordingActions());
        stage.onEnter(context, new PreviewPairPanel("Original", "Adjusted"));

        stage.runPreviewOnWorkerForTest();
        assertTrue(adapter.previewStarted.await(2L, TimeUnit.SECONDS));
        stage.onLeave(context);
        adapter.allowPreviewReturn.countDown();
        assertTrue(adapter.previewReturned.await(2L, TimeUnit.SECONDS));
        waitForWorkerCompletion(stage);

        assertSame(fatalFailure, stage.previewWorkerCompletionFailureForTest());
        assertEquals(1, fatalFailure.getSuppressed().length);
        assertSame(workerFailure, fatalFailure.getSuppressed()[0]);
    }

    private static void assertLateCleanupFailure(final Throwable cleanupFailure)
            throws Exception {
        RecordingPreviewAdapter adapter = new RecordingPreviewAdapter();
        adapter.blockUntilCancelled = true;
        adapter.failNextPreviewResultClose = true;
        adapter.closeFailure = cleanupFailure;
        ConfigQcContext context = context();
        StarDistParameterStage stage = new StarDistParameterStage(
                new RecordingStore("stardist:0.5:0.4"), adapter);
        stage.buildControls(context, new RecordingActions());
        stage.onEnter(context, new PreviewPairPanel("Original", "Adjusted"));

        stage.runPreviewOnWorkerForTest();
        assertTrue(adapter.previewStarted.await(2L, TimeUnit.SECONDS));
        stage.onLeave(context);
        assertTrue(adapter.previewReturned.await(2L, TimeUnit.SECONDS));
        waitForWorkerCompletion(stage);

        assertSame(cleanupFailure, stage.previewWorkerCompletionFailureForTest());
        assertEquals("late result close must receive one bounded retry",
                2, adapter.closeCount(adapter.lastPreviewResult));
        assertEquals(1, adapter.successfulCloseCount(adapter.lastPreviewResult));
    }

    private static void waitForWorkerCompletion(final StarDistParameterStage stage)
            throws Exception {
        TestWait.until("StarDist worker physical completion was not handled",
                new TestWait.Condition() {
                    @Override public boolean isMet() {
                        return stage.previewWorkerCompletionHandledForTest();
                    }
                }, 3000L);
    }

    @Test
    public void starDistFilterEditsAfterPreviewRelabelRemovedObjectsWithoutRerunning() throws Exception {
        RecordingStore store = new RecordingStore(
                "stardist:0.5:0.4:area=5.0-30.0:quality=0.5:intensity=50.0");
        RecordingPreviewAdapter adapter = new RecordingPreviewAdapter();
        PreviewPairPanel pair = new PreviewPairPanel("Original", "Adjusted");
        RecordingActions actions = new RecordingActions(pair);
        StarDistParameterStage stage = new StarDistParameterStage(store, adapter);

        stage.buildControls(context(), actions);
        stage.onEnter(context(), pair);
        stage.runPreviewNowForTest();

        assertEquals(1, adapter.previewRuns);
        assertNotNull(adapter.lastPreviewParameters);
        assertEquals(0.0, adapter.lastPreviewParameters.areaMin, 0.001);
        assertTrue(Double.isInfinite(adapter.lastPreviewParameters.areaMax));
        assertEquals(0.0, adapter.lastPreviewParameters.qualityMin, 0.001);
        assertEquals(0.0, adapter.lastPreviewParameters.intensityMin, 0.001);
        assertEquals("Objects: 1 kept; removed 1 by StarDist filters",
                actions.status);
        assertLabelOnlyRenderHides(pair, 1);
        assertLabelOnlyRenderKeeps(pair, 2);

        adapter.previewRuns = 0;
        stage.setAreaMaxForTest("10");

        assertFalse(stage.isPreviewStaleForTest());
        assertEquals("StarDist filter edits must reuse cached object metrics",
                0, adapter.previewRuns);
        assertEquals("Objects: 0 kept; removed 2 by StarDist filters",
                actions.status);
        assertLabelOnlyRenderHides(pair, 2);

        assertTrue(stage.lockIn(context()));
        assertEquals("stardist:0.5:0.4:area=5.0-10.0:quality=0.5:intensity=50.0:"
                        + "model=stardist_versatile_fluo",
                store.token);
    }

    @Test
    public void finalVoxelVolumeEditsAfterPreviewRelabelRemovedObjectsWithoutRerunning() throws Exception {
        RecordingStore store = new RecordingStore("stardist:0.5:0.4");
        RecordingSizeStore sizeStore = new RecordingSizeStore("0-Infinity");
        RecordingPreviewAdapter adapter = new RecordingPreviewAdapter();
        PreviewPairPanel pair = new PreviewPairPanel("Original", "Adjusted");
        RecordingActions actions = new RecordingActions(pair);
        StarDistParameterStage stage = new StarDistParameterStage(store, sizeStore, adapter);

        stage.buildControls(context(), actions);
        stage.onEnter(context(), pair);
        stage.runPreviewNowForTest();
        adapter.previewRuns = 0;

        stage.setSizeMinForTest("2");

        assertFalse(stage.isPreviewStaleForTest());
        assertEquals("Final voxel volume edits must reuse cached label sizes",
                0, adapter.previewRuns);
        assertEquals("Objects: 1 kept; removed 1 small, 0 large",
                actions.status);
        assertEquals("Objects: 1 kept; removed 1 small, 0 large",
                stage.sizeCutoffSummaryForTest());
        assertLabelOnlyRenderHides(pair, 1);
        assertLabelOnlyRenderKeeps(pair, 2);

        assertTrue(stage.lockIn(context()));
        assertEquals("2-Infinity", sizeStore.token);
        assertEquals("stardist:0.5:0.4:model=stardist_versatile_fluo", store.token);
    }

    @Test
    public void invalidFinalVoxelVolumeAfterFreshPreviewStaysInErrorState() throws Exception {
        RecordingStore store = new RecordingStore("stardist:0.5:0.4");
        RecordingSizeStore sizeStore = new RecordingSizeStore("4-2");
        RecordingPreviewAdapter adapter = new RecordingPreviewAdapter();
        RecordingActions actions = new RecordingActions(new PreviewPairPanel("Original", "Adjusted"));
        StarDistParameterStage stage = new StarDistParameterStage(store, sizeStore, adapter);

        stage.buildControls(context(), actions);
        stage.onEnter(context(), actions.pair);
        stage.runPreviewNowForTest();

        assertTrue(stage.isPreviewStaleForTest());
        assertTrue(actions.previewButtonStale);
        assertEquals("Enter valid StarDist filters and final voxel volume limits.",
                actions.status);
    }

    @Test
    public void finalVoxelVolumeMaxRemovesOnlyObjectsAboveMax() throws Exception {
        RecordingStore store = new RecordingStore("stardist:0.5:0.4");
        RecordingSizeStore sizeStore = new RecordingSizeStore("0-1");
        RecordingPreviewAdapter adapter = new RecordingPreviewAdapter();
        PreviewPairPanel pair = new PreviewPairPanel("Original", "Adjusted");
        RecordingActions actions = new RecordingActions(pair);
        StarDistParameterStage stage = new StarDistParameterStage(store, sizeStore, adapter);

        stage.buildControls(context(), actions);
        stage.onEnter(context(), pair);
        stage.runPreviewNowForTest();

        assertEquals("Objects: 1 kept; removed 0 small, 1 large",
                actions.status);
        assertLabelOnlyRenderKeeps(pair, 1);
        assertLabelOnlyRenderHides(pair, 2);
    }

    @Test
    public void loadedRunParticleSizeAppliesToFinalVoxelVolumeFilter() throws Exception {
        RecordingStore store = new RecordingStore("stardist:0.5:0.4");
        RecordingSizeStore sizeStore = new RecordingSizeStore("0-Infinity");
        RecordingPreviewAdapter adapter = new RecordingPreviewAdapter();
        PreviewPairPanel pair = new PreviewPairPanel("Original", "Adjusted");
        RecordingActions actions = new RecordingActions(pair);
        StarDistParameterStage stage = new StarDistParameterStage(store, sizeStore, adapter);
        Map<String, Object> loaded = new HashMap<String, Object>();
        loaded.put("segmentation_methods", Arrays.asList("stardist:0.6:0.3"));
        loaded.put("particle_sizes", Arrays.asList("3-Infinity"));

        stage.buildControls(context(), actions);
        stage.onEnter(context(), pair);
        stage.applyLoadedParameters(loaded);
        stage.runPreviewNowForTest();

        assertEquals("3-Infinity", sizeStore.token);
        assertEquals(0.6, adapter.lastPreviewParameters.probabilityThreshold, 0.001);
        assertEquals(0.3, adapter.lastPreviewParameters.nmsThreshold, 0.001);
        assertEquals("Objects: 1 kept; removed 1 small, 0 large",
                actions.status);
        assertLabelOnlyRenderHides(pair, 1);
        assertLabelOnlyRenderKeeps(pair, 2);
    }

    @Test
    public void starDistAndFinalVoxelVolumeFiltersDoNotDoubleCountSameRemovedObject() throws Exception {
        RecordingStore store = new RecordingStore("stardist:0.5:0.4:quality=0.5");
        RecordingSizeStore sizeStore = new RecordingSizeStore("2-Infinity");
        RecordingPreviewAdapter adapter = new RecordingPreviewAdapter();
        PreviewPairPanel pair = new PreviewPairPanel("Original", "Adjusted");
        RecordingActions actions = new RecordingActions(pair);
        StarDistParameterStage stage = new StarDistParameterStage(store, sizeStore, adapter);

        stage.buildControls(context(), actions);
        stage.onEnter(context(), pair);
        stage.runPreviewNowForTest();

        assertEquals("Objects: 1 kept; removed 1 by StarDist filters",
                actions.status);
        assertLabelOnlyRenderHides(pair, 1);
        assertLabelOnlyRenderKeeps(pair, 2);

        stage.setSizeMinForTest("4");

        assertEquals("Objects: 0 kept; removed 1 by StarDist filters, 1 small, 0 large",
                actions.status);
        assertLabelOnlyRenderHides(pair, 1);
        assertLabelOnlyRenderHides(pair, 2);
    }

    @Test
    public void failedPreviewClearsOldOutputSoSourceStackStaysBrowsable() throws Exception {
        RecordingStore store = new RecordingStore("stardist:0.5:0.4");
        RecordingPreviewAdapter adapter = new RecordingPreviewAdapter();
        ConfigQcContext context = context(13);
        PreviewPairPanel pair = new PreviewPairPanel("Original", "Adjusted");
        RecordingActions actions = new RecordingActions(pair);
        StarDistParameterStage stage = new StarDistParameterStage(store, adapter);

        stage.buildControls(context, actions);
        stage.onEnter(context, pair);
        stage.runPreviewNowForTest();
        pair.setCurrentZ(13);
        assertEquals("The one-slice object map constrains the paired preview while it is installed.",
                1, pair.getCurrentZ());

        adapter.returnNullPreview = true;
        stage.runPreviewNowForTest();
        pair.setCurrentZ(13);

        assertEquals(13, pair.getCurrentZ());
        assertTrue(stage.isPreviewStaleForTest());
        assertEquals(2, stage.largePreviewPaneCountForTest());
        assertTrue(actions.previewButtonStale);
        assertEquals("StarDist returned no label map.", actions.status);
    }

    @Test
    public void sourceToggleSwapsRawAndFilteredWithoutRunningPreview() {
        RecordingPreviewAdapter adapter = new RecordingPreviewAdapter();
        StarDistParameterStage stage = new StarDistParameterStage(
                new RecordingStore("stardist:0.5:0.4"), adapter);

        stage.buildControls(context(), new RecordingActions());
        stage.onEnter(context(), new PreviewPairPanel("Original", "Adjusted",
                PreviewPairPanel.PreviewLayout.HORIZONTAL_SLIM));
        adapter.previewRuns = 0;

        assertEquals(1, adapter.rawSourceCreations);
        assertEquals(1, adapter.filteredSourceCreations);
        assertTrue(stage.currentSourceTitleForTest().startsWith("filtered"));
        assertEquals(2, stage.largePreviewPaneCountForTest());

        stage.selectRawSourceForTest();

        assertTrue(stage.currentSourceTitleForTest().startsWith("raw"));
        assertEquals(0, adapter.previewRuns);
    }

    @Test
    public void overlayToggleUsesSharedPreviewControls() throws Exception {
        RecordingPreviewAdapter adapter = new RecordingPreviewAdapter();
        StarDistParameterStage stage = new StarDistParameterStage(
                new RecordingStore("stardist:0.5:0.4"), adapter);

        stage.buildControls(context(), new RecordingActions());
        stage.onEnter(context(), new PreviewPairPanel("Original", "Adjusted",
                PreviewPairPanel.PreviewLayout.HORIZONTAL_SLIM));
        stage.runPreviewNowForTest();

        assertFalse(stage.objectOverlaySelectedForTest());

        stage.setShowOverlayForTest(true);

        assertTrue(stage.objectOverlaySelectedForTest());
    }

    @Test
    public void restartKeepsCurrentEditedParametersAfterStageRebuild() {
        RecordingStore store = new RecordingStore("stardist:0.5:0.4");
        StarDistParameterStage stage = new StarDistParameterStage(
                store, new RecordingPreviewAdapter());
        ConfigQcContext context = context();

        stage.buildControls(context, new RecordingActions());
        stage.onEnter(context, new PreviewPairPanel("Original", "Adjusted"));
        stage.setProbabilityForTest("0.92");

        stage.restartStage(context);
        stage.buildControls(context, new RecordingActions());
        stage.onEnter(context, new PreviewPairPanel("Original", "Adjusted"));

        assertTrue(stage.currentMethodForTest().startsWith("stardist:0.92:0.4"));
        assertEquals("stardist:0.5:0.4", store.token);
    }

    private static ConfigQcContext context() {
        return context(1);
    }

    private static ConfigQcContext context(int slices) {
        return ConfigQcContext.fromImages(
                null,
                null,
                null,
                Arrays.asList(image("QC image", slices)),
                Arrays.asList("IBA1"),
                0);
    }

    private static ImagePlus image(String title) {
        return image(title, 1);
    }

    private static ImagePlus image(String title, int slices) {
        ImageStack stack = new ImageStack(3, 3);
        for (int i = 0; i < Math.max(1, slices); i++) {
            ByteProcessor processor = new ByteProcessor(3, 3);
            processor.set(1, 1, 12 + i);
            stack.addSlice(processor);
        }
        return new ImagePlus(title, stack);
    }

    private static void assertContainsText(Component root, String expected) {
        assertTrue("Missing helper text: " + expected, containsText(root, expected));
    }

    private static void assertNotContainsText(Component root, String unexpected) {
        assertFalse("Unexpected text: " + unexpected, containsText(root, unexpected));
    }

    private static boolean containsText(Component component, String expected) {
        String text = null;
        if (component instanceof JLabel) {
            text = ((JLabel) component).getText();
        } else if (component instanceof AbstractButton) {
            text = ((AbstractButton) component).getText();
        } else if (component instanceof JTextComponent) {
            text = ((JTextComponent) component).getText();
        }
        if (text != null && text.contains(expected)) {
            return true;
        }
        if (component instanceof Container) {
            Component[] children = ((Container) component).getComponents();
            for (int i = 0; i < children.length; i++) {
                if (containsText(children[i], expected)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static JButton findButton(Container root, String text) {
        if (root == null || text == null) return null;
        for (Component component : root.getComponents()) {
            if (component instanceof JButton
                    && text.equals(((JButton) component).getText())) {
                return (JButton) component;
            }
            if (component instanceof Container) {
                JButton found = findButton((Container) component, text);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static boolean siblingContainerContains(Component root, String anchorText, String expectedText) {
        Component container = findParentContainingLabel(root, anchorText);
        return container != null && containsText(container, expectedText);
    }

    private static Component findParentContainingLabel(Component component, String text) {
        if (component instanceof JLabel && text.equals(((JLabel) component).getText())) {
            return component.getParent();
        }
        if (component instanceof Container) {
            Component[] children = ((Container) component).getComponents();
            for (int i = 0; i < children.length; i++) {
                Component found = findParentContainingLabel(children[i], text);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static final class RecordingStore implements StarDistParameterStage.ParameterStore {
        String token;

        RecordingStore(String token) {
            this.token = token;
        }

        @Override public String getMethodToken() {
            return token;
        }

        @Override public void save(String methodToken) {
            token = methodToken;
        }
    }

    private static final class RecordingSizeStore implements StarDistParameterStage.SizeStore {
        String token;

        RecordingSizeStore(String token) {
            this.token = token;
        }

        @Override public String get() {
            return token;
        }

        @Override public void set(String token) {
            this.token = token;
        }
    }

    private static final class RecordingPreviewAdapter implements StarDistParameterStage.PreviewAdapter {
        int rawSourceCreations;
        int filteredSourceCreations;
        volatile int previewRuns;
        boolean returnNullPreview;
        boolean returnFilteredSource;
        Throwable countFailure;
        Throwable closeFailure;
        boolean blockUntilCancelled;
        boolean blockUntilReleased;
        final CountDownLatch previewStarted = new CountDownLatch(1);
        final CountDownLatch previewReturned = new CountDownLatch(1);
        final CountDownLatch allowPreviewReturn = new CountDownLatch(1);
        boolean failNextPreviewResultClose;
        boolean failNextFilteredSourceClose;
        Throwable previewFailureAfterRelease;
        volatile boolean inputUsableAtReturn;
        ImagePlus filteredSource;
        ImagePlus lastPreviewResult;
        ImagePlus nextPreviewResult;
        final IdentityHashMap<ImagePlus, Integer> closeCounts =
                new IdentityHashMap<ImagePlus, Integer>();
        final IdentityHashMap<ImagePlus, Integer> successfulCloseCounts =
                new IdentityHashMap<ImagePlus, Integer>();
        boolean closeObservedInterrupt;
        StarDistParameterStage.Parameters lastPreviewParameters;

        @Override public ImagePlus createRawSource(ConfigQcContext context) {
            rawSourceCreations++;
            ImagePlus source = context.getCurrentImagePlus().duplicate();
            source.setTitle("raw");
            return source;
        }

        @Override public ImagePlus createFilteredSource(ConfigQcContext context) {
            filteredSourceCreations++;
            ImagePlus source = context.getCurrentImagePlus().duplicate();
            source.setTitle("filtered");
            filteredSource = source;
            return source;
        }

        @Override public ImagePlus runPreview(ImagePlus filteredSource,
                                              StarDistParameterStage.Parameters parameters) {
            previewRuns++;
            lastPreviewParameters = parameters;
            if (blockUntilCancelled) {
                previewStarted.countDown();
                try {
                    new CountDownLatch(1).await();
                } catch (InterruptedException expected) {
                    Thread.currentThread().interrupt();
                }
            }
            if (blockUntilReleased) {
                previewStarted.countDown();
                boolean interrupted = false;
                while (true) {
                    try {
                        allowPreviewReturn.await();
                        break;
                    } catch (InterruptedException expected) {
                        interrupted = true;
                    }
                }
                if (interrupted) Thread.currentThread().interrupt();
                inputUsableAtReturn = filteredSource.getStack() != null
                        && filteredSource.getStackSize() > 0
                        && filteredSource.getProcessor() != null;
                if (previewFailureAfterRelease != null) {
                    if (!(previewFailureAfterRelease instanceof InterruptedException)) {
                        Thread.interrupted();
                    }
                    previewReturned.countDown();
                    throwTestFailure(previewFailureAfterRelease);
                }
            }
            if (returnNullPreview) {
                previewReturned.countDown();
                return null;
            }
            if (returnFilteredSource) {
                lastPreviewResult = filteredSource;
                previewReturned.countDown();
                return filteredSource;
            }
            if (nextPreviewResult != null) {
                lastPreviewResult = nextPreviewResult;
                nextPreviewResult = null;
                previewReturned.countDown();
                return lastPreviewResult;
            }
            ByteProcessor processor = new ByteProcessor(4, 1);
            processor.set(0, 0, 1);
            processor.set(1, 0, 2);
            processor.set(2, 0, 2);
            processor.set(3, 0, 2);
            ImagePlus labels = new ImagePlus("labels", processor);
            ResultsTable stats = new ResultsTable();
            stats.incrementCounter();
            stats.setValue("Label", 0, 1);
            stats.setValue(StarDist3DRunner.STATS_AREA_MEAN, 0, 4);
            stats.setValue(StarDist3DRunner.STATS_QUALITY_MEAN, 0, 0.2);
            stats.setValue(StarDist3DRunner.STATS_INTENSITY_MEAN, 0, 10);
            stats.incrementCounter();
            stats.setValue("Label", 1, 2);
            stats.setValue(StarDist3DRunner.STATS_AREA_MEAN, 1, 20);
            stats.setValue(StarDist3DRunner.STATS_QUALITY_MEAN, 1, 0.9);
            stats.setValue(StarDist3DRunner.STATS_INTENSITY_MEAN, 1, 100);
            labels.setProperty(StarDist3DRunner.OBJECT_STATS_PROPERTY, stats);
            lastPreviewResult = labels;
            previewReturned.countDown();
            return labels;
        }

        @Override public int countLabels(ImagePlus labelImage) {
            if (countFailure != null) throwTestFailure(countFailure);
            return (int) labelImage.getProcessor().getStats().max;
        }

        @Override public synchronized void close(ImagePlus image) {
            if (image == null) return;
            closeObservedInterrupt |= Thread.currentThread().isInterrupted();
            Integer count = closeCounts.get(image);
            closeCounts.put(image, Integer.valueOf(count == null ? 1 : count.intValue() + 1));
            if (failNextPreviewResultClose) {
                if (image == lastPreviewResult) {
                    failNextPreviewResultClose = false;
                    Throwable failure = closeFailure;
                    closeFailure = null;
                    throwTestFailure(failure);
                }
            } else if (failNextFilteredSourceClose) {
                if (image == filteredSource) {
                    failNextFilteredSourceClose = false;
                    Throwable failure = closeFailure;
                    closeFailure = null;
                    throwTestFailure(failure);
                }
            } else if (closeFailure != null) {
                throwTestFailure(closeFailure);
            }
            Integer successes = successfulCloseCounts.get(image);
            successfulCloseCounts.put(image, Integer.valueOf(
                    successes == null ? 1 : successes.intValue() + 1));
            image.flush();
        }

        synchronized int closeCount(ImagePlus image) {
            Integer count = closeCounts.get(image);
            return count == null ? 0 : count.intValue();
        }

        synchronized int successfulCloseCount(ImagePlus image) {
            Integer count = successfulCloseCounts.get(image);
            return count == null ? 0 : count.intValue();
        }
    }

    private static final class RecordingActions implements ConfigQcActions {
        String status = "";
        ImagePlus adjustedPreview;
        PreviewPairPanel pair;
        JButton previewButton;
        boolean previewButtonStale;
        Throwable statusFailure;
        String statusFailurePrefix;

        RecordingActions() {
        }

        RecordingActions(PreviewPairPanel pair) {
            this.pair = pair;
        }

        @Override public void setStatus(String text) {
            if (statusFailure != null && (statusFailurePrefix == null
                    || (text != null && text.startsWith(statusFailurePrefix)))) {
                throwTestFailure(statusFailure);
            }
            status = text;
        }

        @Override public void markPreviewStale(String text) {
            status = text;
        }

        @Override public void setAdjustedPreview(ImagePlus image, String text) {
            adjustedPreview = image;
            status = text;
            if (pair != null) {
                pair.setAdjusted(image);
                pair.setAdjustedState(PreviewPairPanel.PreviewState.READY, text);
            }
        }

        @Override public void registerPreviewButton(JButton button) {
            previewButton = button;
            setPreviewButtonStale(true);
        }

        @Override public void setPreviewButtonStale(boolean stale) {
            previewButtonStale = stale;
            if (previewButton != null) {
                previewButton.setText(stale ? "\u25CF Run Preview" : "Run Preview");
            }
        }

        @Override public void nextImage() {
        }

        @Override public void skipCurrentImage() {
        }

        @Override public void restartStage() {
        }

        @Override public void cancel() {
        }
    }

    private static final class PreviewVmError extends VirtualMachineError {
        PreviewVmError(String message) {
            super(message);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> void throwTestFailure(Throwable failure) throws T {
        throw (T) failure;
    }

    private static void assertLabelOnlyRenderHides(PreviewPairPanel pair, int label) {
        assertLabelOnlyRenderPixel(pair, label, true);
    }

    private static void assertLabelOnlyRenderKeeps(PreviewPairPanel pair, int label) {
        assertLabelOnlyRenderPixel(pair, label, false);
    }

    private static void assertLabelOnlyRenderPixel(PreviewPairPanel pair, int label, boolean hidden) {
        ImagePlus rendered = pair.duplicateCurrentObjectPreviewForComparison("rendered");
        assertNotNull(rendered);
        ImageProcessor processor = rendered.getProcessor();
        int x = label == 1 ? 0 : 1;
        int rgb = processor.getPixel(x, 0) & 0xffffff;
        if (hidden) {
            assertEquals(0, rgb);
        } else {
            assertTrue("Expected kept label " + label + " to render with a visible color.",
                    rgb != 0);
        }
    }

    private static void waitForStatus(final RecordingActions actions,
                                      final String expectedStatus) throws Exception {
        TestWait.until("status did not become " + expectedStatus, new TestWait.Condition() {
            @Override public boolean isMet() {
                return expectedStatus.equals(actions.status);
            }
        }, 3000L);
        SwingUtilities.invokeAndWait(new Runnable() {
            @Override public void run() {
            }
        });
    }

    private static void waitForPreviewRuns(RecordingPreviewAdapter adapter,
                                           int expectedRuns) throws Exception {
        TestWait.until("preview did not run " + expectedRuns + " time(s)", new TestWait.Condition() {
            @Override public boolean isMet() {
                return adapter.previewRuns >= expectedRuns;
            }
        }, 3000L);
        SwingUtilities.invokeAndWait(new Runnable() {
            @Override public void run() {
            }
        });
        assertEquals(expectedRuns, adapter.previewRuns);
    }
}
