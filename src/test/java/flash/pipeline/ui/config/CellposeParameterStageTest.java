package flash.pipeline.ui.config;

import flash.pipeline.cellpose.CellposeRuntime;
import flash.pipeline.testutil.EdtUncaughtExceptionCapture;
import flash.pipeline.testutil.TestWait;
import flash.pipeline.ui.preview.LabelMapStyler;
import flash.pipeline.ui.preview.PreviewPairPanel;
import flash.pipeline.ui.variations.ParameterCombo;
import flash.pipeline.ui.variations.ParameterId;
import ij.ImagePlus;
import ij.ImageStack;
import ij.process.ByteProcessor;
import org.junit.Test;

import javax.swing.AbstractButton;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import javax.swing.text.JTextComponent;
import java.awt.Component;
import java.awt.Container;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.concurrent.CompletableFuture;
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

public class CellposeParameterStageTest {

    @Test
    public void parsesAndRendersMethodTokenWithCompanionChannel() {
        String token = "cellpose:30.0:cyto3:0.4:0.0:gpu=false:chan2=1";

        CellposeParameterStage.Parameters params =
                CellposeParameterStage.parseMethod(token, true, 3, 0);

        assertEquals("cellpose:30.0:0.4:0.0:gpu=false:chan2=1:model=cellpose_cyto3",
                CellposeParameterStage.formatMethod(params));
    }

    @Test
    public void variationsButtonPresent_andDisabledWithoutPreview() {
        CellposeParameterStage stage = stage(
                new RecordingStore("cellpose:30.0:cyto3:0.4:0.0:gpu=false"),
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
        CellposeParameterStage stage = stage(
                new RecordingStore("cellpose:30.0:cyto3:0.4:0.0:gpu=false"),
                adapter);

        stage.buildControls(context(), new RecordingActions());
        stage.onEnter(context(), new PreviewPairPanel("Original", "Adjusted"));
        adapter.previewRuns = 0;

        stage.applyVariationComboForTest(ParameterCombo.builder()
                .put(ParameterId.DIAMETER, Double.valueOf(22.0d))
                .put(ParameterId.FLOW_THRESHOLD, Double.valueOf(0.6d))
                .put(ParameterId.CELLPROB_THRESHOLD, Double.valueOf(0.2d))
                .put(ParameterId.MODEL, "nuclei")
                .build());
        waitForPreviewRuns(adapter, 1);

        assertEquals("cellpose:22.0:0.6:0.2:gpu=false:model=cellpose_nuclei",
                stage.currentMethodForTest());
    }

    @Test
    public void unsupportedModelDropsCompanionChannel() {
        CellposeParameterStage.Parameters params =
                CellposeParameterStage.parseMethod(
                        "cellpose:12.0:nuclei:0.5:-1.0:gpu=true:chan2=1",
                        true,
                        3,
                        0);

        assertEquals(-1, params.secondChannelIndex);
        assertFalse(CellposeParameterStage.formatMethod(params).contains("chan2="));
    }

    @Test
    public void nonPositiveDiameterFallsBackToDefault() {
        CellposeParameterStage.Parameters params = new CellposeParameterStage.Parameters(
                "cellpose_cyto3", -1, 0.0d, 0.4d, 0.0d, false);

        assertEquals(30.0d, params.diameter, 0.001);
    }

    @Test
    public void textFieldEditMarksPreviewStaleWithoutRunningPreview() {
        RecordingStore store = new RecordingStore("cellpose:30.0:cyto3:0.4:0.0:gpu=false");
        RecordingPreviewAdapter adapter = new RecordingPreviewAdapter();
        RecordingActions actions = new RecordingActions();
        CellposeParameterStage stage = stage(store, adapter);

        stage.buildControls(context(), actions);
        stage.onEnter(context(), new PreviewPairPanel("Original", "Adjusted"));
        adapter.previewRuns = 0;

        stage.setDiameterForTest("44.0");

        assertTrue(stage.isPreviewStaleForTest());
        assertTrue(actions.status.contains("Preview"));
        assertTrue(actions.previewButtonStale);
        assertEquals("\u25CF Run Preview", actions.previewButton.getText());
        assertEquals("Field edits must not execute Cellpose preview",
                0, adapter.previewRuns);
    }

    @Test
    public void controlsUseCompactRowsWithRuntimeHints() {
        CellposeParameterStage stage = stage(
                new RecordingStore("cellpose:30.0:cyto3:0.4:0.0:gpu=false"),
                new RecordingPreviewAdapter());

        JComponent controls = stage.buildControls(context(), new RecordingActions());

        assertContainsText(controls, "Model");
        assertContainsText(controls, "Companion");
        assertContainsText(controls, "Use GPU");
        assertContainsText(controls, "Install GPU Support");
        assertContainsText(controls, "Detection:");
        assertContainsText(controls, "Diameter");
        assertContainsText(controls, "Flow threshold");
        assertContainsText(controls, "Cell probability");
        assertContainsText(controls, "Object size:");
        assertContainsText(controls, "cyto3: Recommended first-pass model");
        assertContainsText(controls, "Companion: optional second channel");
        assertContainsText(controls, "Runtime: Cellpose is not configured yet.");
        assertContainsText(controls, "Run Preview");
        assertFalse("Old duplicate help text should be removed",
                containsText(controls, "Edit parameters, then press"));
        assertTrue(stage.installGpuButtonReachableForTest());
    }

    @Test
    public void allParameterFieldsContributeToMethodToken() {
        CellposeParameterStage stage = stage(
                new RecordingStore("cellpose:30.0:cyto3:0.4:0.0:gpu=false"),
                new RecordingPreviewAdapter());

        stage.buildControls(context(), new RecordingActions());
        stage.setModelForTest("cyto2");
        stage.setCompanionForTest("C2 (Companion)");
        stage.setDiameterForTest("44");
        stage.setFlowForTest("0.6");
        stage.setCellprobForTest("0.2");
        stage.setUseGpuForTest(true);

        assertEquals("cellpose:44.0:0.6:0.2:gpu=true:chan2=1:model=cellpose_cyto2",
                stage.currentMethodForTest());
    }

    @Test
    public void hintsUpdateWhenModelAndCompanionChange() {
        CellposeParameterStage stage = stage(
                new RecordingStore("cellpose:30.0:cyto3:0.4:0.0:gpu=false"),
                new RecordingPreviewAdapter());

        stage.buildControls(context(), new RecordingActions());

        assertTrue(stage.modelHintTextForTest().contains("cyto3"));
        assertTrue(stage.companionHintTextForTest().contains("optional second channel"));
        assertEquals("Runtime: Cellpose is not configured yet.", stage.runtimeHintTextForTest());

        stage.setCompanionForTest("C2 (Companion)");
        assertTrue(stage.companionHintTextForTest().contains("using C2 (Companion)"));

        stage.setModelForTest("nuclei");
        assertTrue(stage.modelHintTextForTest().contains("nuclei"));
        assertTrue(stage.companionHintTextForTest().contains("not used"));
    }

    @Test
    public void previewUsesSelectedCompanionChannelOnlyWhenRequested() throws Exception {
        RecordingStore store = new RecordingStore("cellpose:30.0:cyto3:0.4:0.0:gpu=false:chan2=1");
        RecordingPreviewAdapter adapter = new RecordingPreviewAdapter();
        RecordingActions actions = new RecordingActions();
        CellposeParameterStage stage = stage(store, adapter);

        stage.buildControls(context(), actions);
        stage.onEnter(context(), new PreviewPairPanel("Original", "Adjusted"));

        assertEquals(0, adapter.previewRuns);

        stage.runPreviewNowForTest();

        assertEquals(1, adapter.previewRuns);
        assertEquals(1, adapter.lastCompanionIndex);
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
        CellposeParameterStage stage = stage(
                new RecordingStore("cellpose:30.0:cyto3:0.4:0.0:gpu=false"), adapter);
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
        CellposeParameterStage stage = stage(
                new RecordingStore("cellpose:30.0:cyto3:0.4:0.0:gpu=false"), adapter);
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
        CellposeParameterStage stage = stage(
                new RecordingStore("cellpose:30.0:cyto3:0.4:0.0:gpu=false"), adapter);
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
        CellposeParameterStage stage = stage(
                new RecordingStore("cellpose:30.0:cyto3:0.4:0.0:gpu=false"), adapter);
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
    public void callbackFailureClosesProvisionalLabelExactlyOnce() throws Exception {
        RecordingPreviewAdapter adapter = new RecordingPreviewAdapter();
        RecordingActions actions = new RecordingActions();
        RuntimeException callbackFailure = new IllegalStateException("callback failed");
        ConfigQcContext context = context();
        CellposeParameterStage stage = stage(
                new RecordingStore("cellpose:30.0:cyto3:0.4:0.0:gpu=false"), adapter);
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
        CellposeParameterStage stage = stage(
                new RecordingStore("cellpose:30.0:cyto3:0.4:0.0:gpu=false"), adapter);
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
            CellposeParameterStage stage = stage(
                    new RecordingStore("cellpose:30.0:cyto3:0.4:0.0:gpu=false"), adapter);
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
        CellposeParameterStage stage = stage(
                new RecordingStore("cellpose:30.0:cyto3:0.4:0.0:gpu=false"), adapter);
        stage.buildControls(context, new RecordingActions());
        stage.onEnter(context, new PreviewPairPanel("Original", "Adjusted"));

        stage.runPreviewOnWorkerForTest();
        assertTrue(adapter.previewStarted.await(2L, TimeUnit.SECONDS));
        stage.onLeave(context);
        TestWait.until("cancelled Cellpose result was not closed", new TestWait.Condition() {
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
        CellposeParameterStage stage = stage(
                new RecordingStore("cellpose:30.0:cyto3:0.4:0.0:gpu=false"), adapter);
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
        CellposeParameterStage stage = stage(
                new RecordingStore("cellpose:30.0:cyto3:0.4:0.0:gpu=false"), adapter);
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
        CellposeParameterStage stage = stage(
                new RecordingStore("cellpose:30.0:cyto3:0.4:0.0:gpu=false"), adapter);
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
            CellposeParameterStage stage = stage(
                    new RecordingStore("cellpose:30.0:cyto3:0.4:0.0:gpu=false"), adapter);
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
        CellposeParameterStage stage = stage(
                new RecordingStore("cellpose:30.0:cyto3:0.4:0.0:gpu=false"), adapter);
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
        CellposeParameterStage stage = stage(
                new RecordingStore("cellpose:30.0:cyto3:0.4:0.0:gpu=false"), adapter);
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

    private static void waitForWorkerCompletion(final CellposeParameterStage stage)
            throws Exception {
        TestWait.until("Cellpose worker physical completion was not handled",
                new TestWait.Condition() {
                    @Override public boolean isMet() {
                        return stage.previewWorkerCompletionHandledForTest();
                    }
                }, 3000L);
    }

    @Test
    public void sizeEditsAfterPreviewRelabelRemovedObjectsWithoutRerunning() throws Exception {
        RecordingStore store = new RecordingStore("cellpose:30.0:cyto3:0.4:0.0:gpu=false");
        RecordingSizeStore sizeStore = new RecordingSizeStore("0-Infinity");
        RecordingPreviewAdapter adapter = new RecordingPreviewAdapter();
        RecordingActions actions = new RecordingActions();
        CellposeParameterStage stage = stage(store, sizeStore, adapter);
        PreviewPairPanel pair = new PreviewPairPanel("Original", "Adjusted");

        stage.buildControls(context(), actions);
        stage.onEnter(context(), pair);
        stage.runPreviewNowForTest();
        adapter.previewRuns = 0;

        stage.setSizeMinForTest("2");

        assertFalse(stage.isPreviewStaleForTest());
        assertEquals("Size edits must reuse cached label sizes",
                0, adapter.previewRuns);
        assertEquals("Objects: 1 kept; removed 1 small, 0 large", actions.status);
        assertEquals("Objects: 1 kept; removed 1 small, 0 large",
                stage.sizeCutoffSummaryForTest());
        assertFalse(actions.previewButtonStale);
        ImagePlus rendered = pair.duplicateCurrentObjectPreviewForComparison("Rendered object preview");
        assertRgbPixel(rendered, 0, 0, 0x000000);
        assertRgbPixel(rendered, 1, 0, LabelMapStyler.rgbForLabel(2));

        assertTrue(stage.lockIn(context()));
        assertEquals("2-Infinity", sizeStore.token);
    }

    @Test
    public void sourceToggleSwapsRawAndFilteredWithoutRunningPreview() {
        RecordingStore store = new RecordingStore("cellpose:30.0:cyto3:0.4:0.0:gpu=false");
        RecordingPreviewAdapter adapter = new RecordingPreviewAdapter();
        CellposeParameterStage stage = stage(store, adapter);

        stage.buildControls(context(), new RecordingActions());
        stage.onEnter(context(), new PreviewPairPanel("Original", "Adjusted"));
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
        RecordingStore store = new RecordingStore("cellpose:30.0:cyto3:0.4:0.0:gpu=false");
        RecordingPreviewAdapter adapter = new RecordingPreviewAdapter();
        CellposeParameterStage stage = stage(store, adapter);

        stage.buildControls(context(), new RecordingActions());
        stage.onEnter(context(), new PreviewPairPanel("Original", "Adjusted",
                PreviewPairPanel.PreviewLayout.HORIZONTAL_SLIM));
        stage.runPreviewNowForTest();

        assertFalse(stage.objectOverlaySelectedForTest());

        stage.setShowOverlayForTest(true);

        assertTrue(stage.objectOverlaySelectedForTest());
    }

    @Test(timeout = 1000L)
    public void buildControlsReturnsBeforeRuntimeProbeFutureCompletes() {
        CompletableFuture<CellposeRuntime.Status> runtimeFuture =
                new CompletableFuture<CellposeRuntime.Status>();
        CellposeParameterStage stage = stage(
                new RecordingStore("cellpose:30.0:cyto3:0.4:0.0"),
                new RecordingPreviewAdapter(),
                new RecordingRuntimeAdapter(CellposeRuntime.Status.unknown(), runtimeFuture));

        stage.buildControls(context(), new RecordingActions());

        assertFalse(runtimeFuture.isDone());
        assertEquals("Runtime: Checking Cellpose...", stage.runtimeHintTextForTest());
    }

    @Test
    public void runtimeLabelUpdatesWhenAsyncProbeCompletes() throws Exception {
        CompletableFuture<CellposeRuntime.Status> runtimeFuture =
                new CompletableFuture<CellposeRuntime.Status>();
        CellposeParameterStage stage = stage(
                new RecordingStore("cellpose:30.0:cyto3:0.4:0.0"),
                new RecordingPreviewAdapter(),
                new RecordingRuntimeAdapter(CellposeRuntime.Status.unknown(), runtimeFuture));

        stage.buildControls(context(), new RecordingActions());
        runtimeFuture.complete(CellposeRuntime.probe(""));
        flushEdt();

        assertEquals("Runtime: Cellpose is not configured yet.", stage.runtimeHintTextForTest());
    }

    @Test
    public void runtimeLabelDoesNotUpdateAfterStageLeaves() throws Exception {
        CompletableFuture<CellposeRuntime.Status> runtimeFuture =
                new CompletableFuture<CellposeRuntime.Status>();
        CellposeParameterStage stage = stage(
                new RecordingStore("cellpose:30.0:cyto3:0.4:0.0"),
                new RecordingPreviewAdapter(),
                new RecordingRuntimeAdapter(CellposeRuntime.Status.unknown(), runtimeFuture));

        stage.buildControls(context(), new RecordingActions());
        stage.onLeave(context());
        runtimeFuture.complete(CellposeRuntime.probe(""));
        flushEdt();

        assertEquals("Runtime: Checking Cellpose...", stage.runtimeHintTextForTest());
    }

    @Test
    public void runtimeProbeCallbackDoesNotCaptureStageStrongly() throws Exception {
        Field[] fields = CellposeParameterStage.RuntimeProbeCallback.class.getDeclaredFields();
        boolean hasWeakStageReference = false;
        for (int i = 0; i < fields.length; i++) {
            Field field = fields[i];
            assertFalse("Runtime probe callback must not retain the enclosing stage",
                    "this$0".equals(field.getName()));
            if ("stageRef".equals(field.getName())) {
                hasWeakStageReference = WeakReference.class.equals(field.getType());
            }
        }
        assertTrue(hasWeakStageReference);
    }

    @Test
    public void restartKeepsCurrentEditedParametersAfterStageRebuild() {
        RecordingStore store = new RecordingStore("cellpose:30.0:cyto3:0.4:0.0:gpu=false");
        CellposeParameterStage stage = stage(store, new RecordingPreviewAdapter());
        ConfigQcContext context = context();

        stage.buildControls(context, new RecordingActions());
        stage.onEnter(context, new PreviewPairPanel("Original", "Adjusted"));
        stage.setDiameterForTest("44.0");

        stage.restartStage(context);
        stage.buildControls(context, new RecordingActions());
        stage.onEnter(context, new PreviewPairPanel("Original", "Adjusted"));

        assertTrue(stage.currentMethodForTest().startsWith("cellpose:44.0:0.4:0.0"));
        assertEquals("cellpose:30.0:cyto3:0.4:0.0:gpu=false", store.token);
    }

    private static CellposeParameterStage stage(RecordingStore store,
                                                RecordingPreviewAdapter adapter) {
        return stage(store, new RecordingSizeStore("0-Infinity"), adapter);
    }

    private static CellposeParameterStage stage(RecordingStore store,
                                                RecordingSizeStore sizeStore,
                                                RecordingPreviewAdapter adapter) {
        return stage(store, sizeStore, adapter, new RecordingRuntimeAdapter());
    }

    private static CellposeParameterStage stage(RecordingStore store,
                                                RecordingPreviewAdapter adapter,
                                                RecordingRuntimeAdapter runtimeAdapter) {
        return stage(store, new RecordingSizeStore("0-Infinity"), adapter, runtimeAdapter);
    }

    private static CellposeParameterStage stage(RecordingStore store,
                                                RecordingSizeStore sizeStore,
                                                RecordingPreviewAdapter adapter,
                                                RecordingRuntimeAdapter runtimeAdapter) {
        return new CellposeParameterStage(
                store,
                sizeStore,
                adapter,
                runtimeAdapter,
                Arrays.asList("Primary", "Companion", "Other"),
                0,
                false);
    }

    private static ConfigQcContext context() {
        return ConfigQcContext.fromImages(
                null,
                null,
                null,
                Arrays.asList(image("QC image")),
                Arrays.asList("Primary", "Companion", "Other"),
                0);
    }

    private static ImagePlus image(String title) {
        ImageStack stack = new ImageStack(3, 3);
        ByteProcessor processor = new ByteProcessor(3, 3);
        processor.set(1, 1, 12);
        stack.addSlice(processor);
        return new ImagePlus(title, stack);
    }

    private static void assertContainsText(Component root, String expected) {
        assertTrue("Missing helper text: " + expected, containsText(root, expected));
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

    private static final class RecordingStore implements CellposeParameterStage.ParameterStore {
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

    private static final class RecordingPreviewAdapter implements CellposeParameterStage.PreviewAdapter {
        int rawSourceCreations;
        int filteredSourceCreations;
        volatile int previewRuns;
        int lastCompanionIndex = -2;
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

        @Override public ImagePlus createFilteredCompanionSource(ConfigQcContext context, int channelIndex) {
            lastCompanionIndex = channelIndex;
            ImagePlus companion = context.getCurrentImagePlus().duplicate();
            companion.setTitle("companion");
            return companion;
        }

        @Override public ImagePlus runPreview(ImagePlus filteredSource,
                                              ImagePlus filteredCompanionSource,
                                              CellposeParameterStage.Parameters parameters) {
            previewRuns++;
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
            lastPreviewResult = new ImagePlus("labels", processor);
            previewReturned.countDown();
            return lastPreviewResult;
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

    private static final class RecordingSizeStore implements CellposeParameterStage.SizeStore {
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

    private static final class RecordingRuntimeAdapter implements CellposeParameterStage.RuntimeAdapter {
        private final CellposeRuntime.Status cachedStatus;
        private final CompletableFuture<CellposeRuntime.Status> runtimeFuture;

        RecordingRuntimeAdapter() {
            this(CellposeRuntime.probe(""),
                    CompletableFuture.completedFuture(CellposeRuntime.probe("")));
        }

        RecordingRuntimeAdapter(CellposeRuntime.Status cachedStatus,
                                CompletableFuture<CellposeRuntime.Status> runtimeFuture) {
            this.cachedStatus = cachedStatus;
            this.runtimeFuture = runtimeFuture;
        }

        @Override public CellposeRuntime.Status cachedRuntimeStatus() {
            return cachedStatus;
        }

        @Override public CompletableFuture<CellposeRuntime.Status> probeRuntimeAsync() {
            return runtimeFuture;
        }

        @Override public boolean nvidiaGpuLikelyAvailable() {
            return false;
        }

        @Override public CellposeParameterStage.GpuInstallResult installGpuSupport() {
            return new CellposeParameterStage.GpuInstallResult(false, "not installed", "");
        }
    }

    private static final class RecordingActions implements ConfigQcActions {
        String status = "";
        ImagePlus adjustedPreview;
        JButton previewButton;
        boolean previewButtonStale;
        Throwable statusFailure;
        String statusFailurePrefix;

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

    private static void assertRgbPixel(ImagePlus image, int x, int y, int expectedRgb) {
        assertNotNull(image);
        int actual = image.getProcessor().getPixel(x, y) & 0xffffff;
        assertEquals(expectedRgb, actual);
    }

    private static void waitForPreviewRuns(RecordingPreviewAdapter adapter,
                                           int expectedRuns) throws Exception {
        TestWait.until("preview did not run " + expectedRuns + " time(s)", new TestWait.Condition() {
            @Override public boolean isMet() {
                return adapter.previewRuns >= expectedRuns;
            }
        }, 3000L);
        flushEdt();
        assertEquals(expectedRuns, adapter.previewRuns);
    }

    private static void flushEdt() throws Exception {
        SwingUtilities.invokeAndWait(new Runnable() {
            @Override public void run() {
            }
        });
    }
}
