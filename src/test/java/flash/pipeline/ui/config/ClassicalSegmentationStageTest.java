package flash.pipeline.ui.config;

import flash.pipeline.help.SetupHelpCatalog;
import flash.pipeline.objects.ObjectsCounter3DWrapper;
import flash.pipeline.testutil.EdtUncaughtExceptionCapture;
import flash.pipeline.testutil.TestWait;
import flash.pipeline.ui.preview.LabelMapStyler;
import flash.pipeline.ui.preview.PreviewPairPanel;
import flash.pipeline.ui.variations.ParameterCombo;
import flash.pipeline.ui.variations.ParameterId;
import ij.ImagePlus;
import ij.ImageStack;
import ij.measure.ResultsTable;
import ij.process.ByteProcessor;
import org.junit.Test;

import javax.swing.AbstractButton;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JComponent;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.util.Arrays;
import java.util.IdentityHashMap;
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

public class ClassicalSegmentationStageTest {

    @Test
    public void enteringCreatesRawAndFilteredSources() {
        RecordingPreviewAdapter adapter = new RecordingPreviewAdapter();
        ClassicalSegmentationStage stage = stage(
                new RecordingThresholdStore("20"),
                new RecordingSizeStore("1-Infinity"),
                adapter);

        stage.buildControls(context(), new RecordingActions());
        stage.onEnter(context(), new PreviewPairPanel("Original", "Objects"));

        assertEquals(1, adapter.rawSourceCreations);
        assertEquals(1, adapter.filteredSourceCreations);
        assertNotNull(stage.thresholdPreviewForTest());
        assertEquals(SetupHelpCatalog.CLASSICAL_OBJECT_SEGMENTATION, stage.helpTopic());
    }

    @Test
    public void variationsButtonPresent_andDisabledWithoutPreview() {
        RecordingPreviewAdapter adapter = new RecordingPreviewAdapter();
        ClassicalSegmentationStage stage = stage(
                new RecordingThresholdStore("20"),
                new RecordingSizeStore("1-Infinity"),
                adapter);

        JComponent controls = stage.buildControls(context(), new RecordingActions());
        JButton variations = findButton(controls, "Parameter Variations...");

        assertNotNull(variations);
        assertFalse(variations.isEnabled());
        assertEquals("Run/prepare a preview before opening parameter variations.",
                variations.getToolTipText());

        stage.onEnter(context(), new PreviewPairPanel("Original", "Objects"));

        assertTrue(variations.isEnabled());
    }

    @Test
    public void applyCombo_writesFieldsAndTriggersRefresh() throws Exception {
        RecordingPreviewAdapter adapter = new RecordingPreviewAdapter();
        ClassicalSegmentationStage stage = stage(
                new RecordingThresholdStore("20"),
                new RecordingSizeStore("1-Infinity"),
                adapter);

        stage.buildControls(context(), new RecordingActions());
        stage.onEnter(context(), new PreviewPairPanel("Original", "Objects"));
        adapter.previewRuns = 0;

        stage.applyVariationComboForTest(ParameterCombo.builder()
                .put(ParameterId.THRESHOLD, Double.valueOf(45.0d))
                .put(ParameterId.MIN_SIZE, Integer.valueOf(3))
                .put(ParameterId.MAX_SIZE, Integer.valueOf(4))
                .build());
        waitForPreviewRuns(adapter, 1);

        assertEquals("45", stage.currentThresholdTokenForTest());
        assertEquals("3-4", stage.currentSizeTokenForTest());
        assertEquals(45, adapter.lastThreshold);
    }

    @Test
    public void lockInCanWriteAlgorithmicThresholdToken() {
        RecordingPreviewAdapter adapter = new RecordingPreviewAdapter();
        RecordingThresholdStore thresholdStore = new RecordingThresholdStore("20");
        RecordingSizeStore sizeStore = new RecordingSizeStore("1-Infinity");
        ClassicalSegmentationStage stage = stage(thresholdStore, sizeStore, adapter);

        stage.buildControls(context(), new RecordingActions());
        stage.onEnter(context(), new PreviewPairPanel("Original", "Objects"));
        stage.setAlgorithmThresholdForTest("IsoData", "Dark");

        assertTrue(stage.lockIn(context()));

        assertEquals("auto:IsoData:dark", thresholdStore.token);
        assertEquals("1-Infinity", sizeStore.token);
        assertEquals("auto:IsoData:dark", stage.currentThresholdTokenForTest());
    }

    @Test
    public void normalLeftPaneIsThresholdPreviewNotRawSource() {
        RecordingPreviewAdapter adapter = new RecordingPreviewAdapter();
        ClassicalSegmentationStage stage = stage(
                new RecordingThresholdStore("20"),
                new RecordingSizeStore("1-Infinity"),
                adapter);

        stage.buildControls(context(), new RecordingActions());
        stage.onEnter(context(), new PreviewPairPanel("Original", "Objects"));

        assertEquals("Threshold preview", stage.currentNormalLeftPreviewTitleForTest());
        assertFalse("raw".equals(stage.currentNormalLeftPreviewTitleForTest()));
        assertFalse("filtered".equals(stage.currentNormalLeftPreviewTitleForTest()));
        assertEquals(2, stage.largePreviewPaneCountForTest());
    }

    @Test
    public void normalPreviewHidesSourceModeControls() {
        RecordingPreviewAdapter adapter = new RecordingPreviewAdapter();
        ClassicalSegmentationStage stage = stage(
                new RecordingThresholdStore("20"),
                new RecordingSizeStore("1-Infinity"),
                adapter);
        PreviewPairPanel pair = new PreviewPairPanel("Original", "Objects");

        stage.buildControls(context(), new RecordingActions());
        stage.onEnter(context(), pair);

        assertEquals("Threshold preview", stage.currentNormalLeftPreviewTitleForTest());
        assertFalse(hasVisibleText(pair.previewToolstrip(), "Source:"));
        assertFalse(hasVisibleText(pair.previewToolstrip(), "Raw"));
        assertFalse(hasVisibleText(pair.previewToolstrip(), "Filtered"));
    }

    @Test
    public void largePreviewAddsObjectPaneAfterLabelsExist() throws Exception {
        RecordingPreviewAdapter adapter = new RecordingPreviewAdapter();
        ClassicalSegmentationStage stage = stage(
                new RecordingThresholdStore("20"),
                new RecordingSizeStore("1-Infinity"),
                adapter);

        stage.buildControls(context(), new RecordingActions());
        stage.onEnter(context(), new PreviewPairPanel("Original", "Objects"));

        assertEquals(2, stage.largePreviewPaneCountForTest());

        stage.runPreviewNowForTest();

        assertEquals(3, stage.largePreviewPaneCountForTest());
        assertEquals(0, adapter.closeCount(adapter.lastLabel));
        stage.onLeave(context());
        assertEquals(1, adapter.closeCount(adapter.lastLabel));
    }

    @Test
    public void replacementCloseFailureRetainsOldAndKeepsNewUntilDispose()
            throws Exception {
        RecordingPreviewAdapter adapter = new RecordingPreviewAdapter();
        ClassicalSegmentationStage stage = stage(
                new RecordingThresholdStore("20"),
                new RecordingSizeStore("1-Infinity"), adapter);
        ConfigQcContext context = context();
        stage.buildControls(context, new RecordingActions());
        stage.onEnter(context, new PreviewPairPanel("Original", "Objects"));

        stage.runPreviewNowForTest();
        ImagePlus first = adapter.lastLabel;
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

        ImagePlus second = adapter.lastLabel;
        assertNotSame(first, second);
        assertSame("new preview must stay installed after old cleanup fails",
                second, stage.labelPreviewForTest());
        assertEquals(1, adapter.closeCount(first));
        assertEquals(0, adapter.successfulCloseCount(first));
        assertEquals(0, adapter.closeCount(second));
        assertFalse("adapter close must run with the caller interrupt cleared",
                adapter.closeObservedInterrupt);

        adapter.nextLabel = first;
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
    public void countFailureClosesAliasedResultImagesExactlyOnce() throws Exception {
        RecordingPreviewAdapter adapter = new RecordingPreviewAdapter();
        RuntimeException countFailure = new IllegalStateException("count failed");
        adapter.aliasResultImages = true;
        adapter.countFailure = countFailure;
        ConfigQcContext context = context();
        ClassicalSegmentationStage stage = stage(
                new RecordingThresholdStore("20"),
                new RecordingSizeStore("1-Infinity"), adapter);
        stage.buildControls(context, new RecordingActions());
        stage.onEnter(context, new PreviewPairPanel("Original", "Objects"));

        try {
            stage.runPreviewNowForTest();
            fail("Expected count failure");
        } catch (RuntimeException failure) {
            assertSame(countFailure, failure);
        }

        assertSame(adapter.lastLabel, adapter.lastMasked);
        assertEquals(1, adapter.closeCount(adapter.lastLabel));
    }

    @Test
    public void borrowedSourceReturnedAsLabelIsNotClosedByFailedProducer() throws Exception {
        RecordingPreviewAdapter adapter = new RecordingPreviewAdapter();
        adapter.returnFilteredSource = true;
        adapter.countFailure = new IllegalStateException("count failed");
        ConfigQcContext context = context();
        ClassicalSegmentationStage stage = stage(
                new RecordingThresholdStore("20"),
                new RecordingSizeStore("1-Infinity"), adapter);
        stage.buildControls(context, new RecordingActions());
        stage.onEnter(context, new PreviewPairPanel("Original", "Objects"));

        try {
            stage.runPreviewNowForTest();
            fail("Expected count failure");
        } catch (IllegalStateException expected) {
            // The stage still owns its filtered source.
        }

        assertSame(adapter.filteredSource, adapter.lastLabel);
        assertEquals(0, adapter.closeCount(adapter.lastLabel));
        stage.onLeave(context);
        assertEquals(1, adapter.closeCount(adapter.lastLabel));
    }

    @Test
    public void vmFatalCleanupOutranksOrdinaryCountFailure() throws Exception {
        RecordingPreviewAdapter adapter = new RecordingPreviewAdapter();
        RuntimeException countFailure = new IllegalStateException("count failed");
        PreviewVmError cleanupFailure = new PreviewVmError("close failed");
        adapter.aliasResultImages = true;
        adapter.countFailure = countFailure;
        adapter.closeFailure = cleanupFailure;
        ConfigQcContext context = context();
        ClassicalSegmentationStage stage = stage(
                new RecordingThresholdStore("20"),
                new RecordingSizeStore("1-Infinity"), adapter);
        stage.buildControls(context, new RecordingActions());
        stage.onEnter(context, new PreviewPairPanel("Original", "Objects"));

        try {
            stage.runPreviewNowForTest();
            fail("Expected VM-fatal cleanup failure");
        } catch (PreviewVmError failure) {
            assertSame(cleanupFailure, failure);
            assertEquals(1, failure.getSuppressed().length);
            assertSame(countFailure, failure.getSuppressed()[0]);
        }

        assertEquals(1, adapter.closeCount(adapter.lastLabel));
        ImagePlus unpublished = adapter.lastLabel;
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
        ClassicalSegmentationStage stage = stage(
                new RecordingThresholdStore("20"),
                new RecordingSizeStore("1-Infinity"), adapter);
        stage.buildControls(context, actions);
        stage.onEnter(context, new PreviewPairPanel("Original", "Objects"));
        actions.adjustedPreviewFailure = callbackFailure;

        try {
            stage.runPreviewNowForTest();
            fail("Expected callback failure");
        } catch (RuntimeException failure) {
            assertSame(callbackFailure, failure);
        }

        assertEquals(1, adapter.closeCount(adapter.lastLabel));
        stage.onLeave(context);
        assertEquals(1, adapter.closeCount(adapter.lastLabel));
    }

    @Test
    public void interruptedCountRestoresInterruptAndClosesResult() throws Exception {
        RecordingPreviewAdapter adapter = new RecordingPreviewAdapter();
        InterruptedException interruption = new InterruptedException("stop");
        adapter.countFailure = interruption;
        ConfigQcContext context = context();
        ClassicalSegmentationStage stage = stage(
                new RecordingThresholdStore("20"),
                new RecordingSizeStore("1-Infinity"), adapter);
        stage.buildControls(context, new RecordingActions());
        stage.onEnter(context, new PreviewPairPanel("Original", "Objects"));
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
        assertEquals(1, adapter.closeCount(adapter.lastLabel));
    }

    @Test
    public void asyncInterruptedCountDoesNotInterruptOrEscapeEdt() throws Exception {
        EdtUncaughtExceptionCapture capture = EdtUncaughtExceptionCapture.install();
        try {
            RecordingPreviewAdapter adapter = new RecordingPreviewAdapter();
            InterruptedException interruption = new InterruptedException("count interrupted");
            adapter.countFailure = interruption;
            ConfigQcContext context = context();
            ClassicalSegmentationStage stage = stage(
                    new RecordingThresholdStore("20"),
                    new RecordingSizeStore("1-Infinity"), adapter);
            stage.buildControls(context, new RecordingActions());
            stage.onEnter(context, new PreviewPairPanel("Original", "Objects"));

            stage.runPreviewOnWorkerForTest();
            waitForWorkerCompletion(stage);

            assertSame(interruption, stage.previewWorkerCompletionFailureForTest());
            assertFalse(stage.previewWorkerCompletionObservedInterruptForTest());
            assertEquals(1, adapter.closeCount(adapter.lastLabel));
            stage.onLeave(context);
            assertEquals(1, adapter.closeCount(adapter.filteredSource));
        } finally {
            capture.close();
        }
        assertNull("install-side interruption must not escape on EDT", capture.failure());
    }

    @Test
    public void cancellationClosesLateAliasedResultExactlyOnce() throws Exception {
        RecordingPreviewAdapter adapter = new RecordingPreviewAdapter();
        adapter.aliasResultImages = true;
        adapter.blockUntilCancelled = true;
        ConfigQcContext context = context();
        ClassicalSegmentationStage stage = stage(
                new RecordingThresholdStore("20"),
                new RecordingSizeStore("1-Infinity"), adapter);
        stage.buildControls(context, new RecordingActions());
        stage.onEnter(context, new PreviewPairPanel("Original", "Objects"));

        stage.runPreviewOnWorkerForTest();
        assertTrue(adapter.previewStarted.await(2L, TimeUnit.SECONDS));
        stage.onLeave(context);
        TestWait.until("cancelled Classical result was not closed", new TestWait.Condition() {
            @Override public boolean isMet() {
                return adapter.lastLabel != null
                        && adapter.closeCount(adapter.lastLabel) == 1;
            }
        }, 3000L);
        assertSame(adapter.lastLabel, adapter.lastMasked);
        assertEquals(1, adapter.closeCount(adapter.lastLabel));
    }

    @Test
    public void cancellationIgnoringWorkerKeepsInputUsableUntilPhysicalReturn() throws Exception {
        RecordingPreviewAdapter adapter = new RecordingPreviewAdapter();
        adapter.blockUntilReleased = true;
        adapter.returnFilteredSource = true;
        ConfigQcContext context = context();
        ClassicalSegmentationStage stage = stage(
                new RecordingThresholdStore("20"),
                new RecordingSizeStore("1-Infinity"), adapter);
        stage.buildControls(context, new RecordingActions());
        stage.onEnter(context, new PreviewPairPanel("Original", "Objects"));

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
        assertSame(adapter.filteredSource, adapter.lastLabel);
        assertSame(null, stage.previewWorkerCompletionFailureForTest());
    }

    @Test
    public void queuedCancellationReleasesInputWithoutStartingAdapter() throws Exception {
        RecordingPreviewAdapter adapter = new RecordingPreviewAdapter();
        ConfigQcContext context = context();
        ClassicalSegmentationStage stage = stage(new RecordingThresholdStore("20"),
                new RecordingSizeStore("1-Infinity"), adapter);
        stage.setPreviewWorkerExecutorForTest(TestPreviewWorkerExecutors.QUEUED);
        stage.buildControls(context, new RecordingActions());
        stage.onEnter(context, new PreviewPairPanel("Original", "Objects"));

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
        ClassicalSegmentationStage stage = stage(new RecordingThresholdStore("20"),
                new RecordingSizeStore("1-Infinity"), adapter);
        stage.setPreviewWorkerExecutorForTest(
                TestPreviewWorkerExecutors.rejecting(rejection));
        stage.buildControls(context, new RecordingActions());
        stage.onEnter(context, new PreviewPairPanel("Original", "Objects"));

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
            ClassicalSegmentationStage stage = stage(
                    new RecordingThresholdStore("20"),
                    new RecordingSizeStore("1-Infinity"), adapter);
            stage.buildControls(context, new RecordingActions());
            stage.onEnter(context, new PreviewPairPanel("Original", "Objects"));

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
        ClassicalSegmentationStage stage = stage(
                new RecordingThresholdStore("20"),
                new RecordingSizeStore("1-Infinity"), adapter);
        stage.buildControls(context, new RecordingActions());
        stage.onEnter(context, new PreviewPairPanel("Original", "Objects"));

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
        adapter.aliasResultImages = true;
        adapter.failNextPreviewResultClose = true;
        adapter.closeFailure = cleanupFailure;
        ConfigQcContext context = context();
        ClassicalSegmentationStage stage = stage(
                new RecordingThresholdStore("20"),
                new RecordingSizeStore("1-Infinity"), adapter);
        stage.buildControls(context, new RecordingActions());
        stage.onEnter(context, new PreviewPairPanel("Original", "Objects"));

        stage.runPreviewOnWorkerForTest();
        assertTrue(adapter.previewStarted.await(2L, TimeUnit.SECONDS));
        stage.onLeave(context);
        assertTrue(adapter.previewReturned.await(2L, TimeUnit.SECONDS));
        waitForWorkerCompletion(stage);

        assertSame(cleanupFailure, stage.previewWorkerCompletionFailureForTest());
        assertEquals("late aliased result close must receive one bounded retry",
                2, adapter.closeCount(adapter.lastLabel));
        assertEquals(1, adapter.successfulCloseCount(adapter.lastLabel));
    }

    private static void waitForWorkerCompletion(final ClassicalSegmentationStage stage)
            throws Exception {
        TestWait.until("Classical worker physical completion was not handled",
                new TestWait.Condition() {
                    @Override public boolean isMet() {
                        return stage.previewWorkerCompletionHandledForTest();
                    }
                }, 3000L);
    }

    @Test
    public void thresholdEditRerendersWithoutRunningObjectPreview() {
        RecordingPreviewAdapter adapter = new RecordingPreviewAdapter();
        RecordingActions actions = new RecordingActions();
        ClassicalSegmentationStage stage = stage(
                new RecordingThresholdStore("20"),
                new RecordingSizeStore("1-Infinity"),
                adapter);

        stage.buildControls(context(), actions);
        stage.onEnter(context(), new PreviewPairPanel("Original", "Objects"));
        adapter.previewRuns = 0;
        ImagePlus firstPreview = stage.thresholdPreviewForTest();

        stage.setThresholdForTest(50.0, 100.0);

        assertTrue(firstPreview != stage.thresholdPreviewForTest());
        assertEquals(0, adapter.previewRuns);
        assertTrue(stage.isObjectPreviewStaleForTest());
        assertTrue(actions.status.contains("Object preview is out of date"));
    }

    @Test
    public void staleObjectStateDoesNotReplaceLiveThresholdPreview() throws Exception {
        RecordingPreviewAdapter adapter = new RecordingPreviewAdapter();
        ClassicalSegmentationStage stage = stage(
                new RecordingThresholdStore("20"),
                new RecordingSizeStore("1-Infinity"),
                adapter);
        PreviewPairPanel pair = new PreviewPairPanel("Original", "Objects");
        RecordingActions actions = new RecordingActions();

        stage.buildControls(context(), actions);
        stage.onEnter(context(), pair);
        stage.runPreviewNowForTest();

        ImagePlus firstThresholdPreview = stage.thresholdPreviewForTest();
        assertEquals("Threshold preview", stage.currentNormalLeftPreviewTitleForTest());
        assertEquals("Object label preview", actions.adjustedPreview.getTitle());

        stage.setThresholdForTest(80.0, 100.0);

        assertTrue(stage.isObjectPreviewStaleForTest());
        assertTrue(firstThresholdPreview != stage.thresholdPreviewForTest());
        assertEquals("Threshold preview", stage.currentNormalLeftPreviewTitleForTest());
        assertEquals("Object label preview", actions.adjustedPreview.getTitle());
        assertTrue(actions.status.contains("Object preview is out of date"));
    }

    @Test
    public void thresholdEditsMarkStaleButSizeEditsRelabelLive() throws Exception {
        RecordingPreviewAdapter adapter = new RecordingPreviewAdapter();
        RecordingActions actions = new RecordingActions();
        ClassicalSegmentationStage stage = stage(
                new RecordingThresholdStore("20"),
                new RecordingSizeStore("1-Infinity"),
                adapter);
        PreviewPairPanel pair = new PreviewPairPanel("Original", "Objects");

        stage.buildControls(context(), actions);
        stage.onEnter(context(), pair);
        stage.runPreviewNowForTest();

        assertFalse(stage.isObjectPreviewStaleForTest());

        stage.setThresholdForTest(60.0, 100.0);

        assertTrue(stage.isObjectPreviewStaleForTest());
        assertEquals(1, adapter.previewRuns);

        stage.runPreviewNowForTest();
        assertFalse(stage.isObjectPreviewStaleForTest());

        stage.setMinSizeForTest("3");

        assertFalse(stage.isObjectPreviewStaleForTest());
        assertEquals("Size edits must not execute the object preview",
                2, adapter.previewRuns);
        assertFalse(actions.previewButtonStale);
        assertEquals("Objects: 1 kept; removed 1 small, 0 large. Threshold 60.",
                actions.status);
        assertEquals("Objects: 1 kept; removed 1 small, 0 large",
                stage.sizeCutoffSummaryForTest());
        ImagePlus rendered = pair.duplicateCurrentObjectPreviewForComparison("Rendered object preview");
        assertRgbPixel(rendered, 0, 0, 0x000000);
        assertRgbPixel(rendered, 1, 0, LabelMapStyler.rgbForLabel(2));
    }

    @Test
    public void objectPreviewUsesCurrentUnsavedThreshold() throws Exception {
        RecordingThresholdStore thresholdStore = new RecordingThresholdStore("20");
        RecordingPreviewAdapter adapter = new RecordingPreviewAdapter();
        ClassicalSegmentationStage stage = stage(
                thresholdStore,
                new RecordingSizeStore("1-Infinity"),
                adapter);

        stage.buildControls(context(), new RecordingActions());
        stage.onEnter(context(), new PreviewPairPanel("Original", "Objects"));
        stage.setThresholdForTest(64.0, 100.0);

        stage.runPreviewNowForTest();

        assertEquals(64, adapter.lastThreshold);
        assertEquals("20", thresholdStore.token);
    }

    @Test
    public void objectPreviewAppliesCurrentSizeFilterWhenRun() throws Exception {
        RecordingPreviewAdapter adapter = new RecordingPreviewAdapter();
        RecordingActions actions = new RecordingActions();
        ClassicalSegmentationStage stage = stage(
                new RecordingThresholdStore("20"),
                new RecordingSizeStore("3-Infinity"),
                adapter);

        stage.buildControls(context(), actions);
        stage.onEnter(context(), new PreviewPairPanel("Original", "Objects"));
        stage.runPreviewNowForTest();

        assertEquals(3, adapter.lastMinSize);
        assertEquals(4, adapter.lastMaxSize);
        assertEquals("Objects: 1 ready. Threshold 20.",
                actions.status);
        assertLabelPixel(actions.adjustedPreview, 0, 0, 0);
        assertLabelPixel(actions.adjustedPreview, 1, 0, 2);
    }

    @Test
    public void looseningSizeFilterMarksPreviewStaleBecauseMissingObjectsNeedRerun() throws Exception {
        RecordingPreviewAdapter adapter = new RecordingPreviewAdapter();
        RecordingActions actions = new RecordingActions();
        ClassicalSegmentationStage stage = stage(
                new RecordingThresholdStore("20"),
                new RecordingSizeStore("3-Infinity"),
                adapter);

        stage.buildControls(context(), actions);
        stage.onEnter(context(), new PreviewPairPanel("Original", "Objects"));
        stage.runPreviewNowForTest();
        adapter.previewRuns = 0;

        stage.setMinSizeForTest("1");

        assertTrue(stage.isObjectPreviewStaleForTest());
        assertEquals("Loosening the range must not silently miss newly included objects",
                0, adapter.previewRuns);
        assertTrue(actions.previewButtonStale);
        assertTrue(actions.status.contains("out of date"));
    }

    @Test
    public void sizeEditsDoNotCapturePreviousComparisonSettings() throws Exception {
        RecordingPreviewAdapter adapter = new RecordingPreviewAdapter();
        ClassicalSegmentationStage stage = stage(
                new RecordingThresholdStore("20"),
                new RecordingSizeStore("1-Infinity"),
                adapter);

        stage.buildControls(context(), new RecordingActions());
        stage.onEnter(context(), new PreviewPairPanel("Original", "Objects"));
        stage.runPreviewNowForTest();

        stage.setMinSizeForTest("3");

        assertEquals("3-Infinity", stage.currentSizeTokenForTest());

        stage.restorePreviousComparisonSettingsForTest();

        assertEquals("3-Infinity", stage.currentSizeTokenForTest());
        assertEquals("20", stage.currentThresholdTokenForTest());
    }

    @Test
    public void lockInWritesThresholdAndSize() {
        RecordingThresholdStore thresholdStore = new RecordingThresholdStore("20");
        RecordingSizeStore sizeStore = new RecordingSizeStore("1-Infinity");
        ClassicalSegmentationStage stage = stage(
                thresholdStore,
                sizeStore,
                new RecordingPreviewAdapter());
        ConfigQcContext context = context();

        stage.buildControls(context, new RecordingActions());
        stage.onEnter(context, new PreviewPairPanel("Original", "Objects"));
        stage.setThresholdForTest(42.4, 100.0);
        stage.setMinSizeForTest("4.6");
        stage.setMaxSizeForTest("20.2");

        assertTrue(stage.lockIn(context));

        assertEquals("42", thresholdStore.token);
        assertEquals("5-20", sizeStore.token);
        assertEquals("42", stage.currentThresholdTokenForTest());
        assertEquals("5-20", stage.currentSizeTokenForTest());
    }

    @Test
    public void savedThresholdAboveCurrentImageMaximumIsNotClampedWhenLockedAgain() throws Exception {
        RecordingThresholdStore thresholdStore = new RecordingThresholdStore("200");
        RecordingPreviewAdapter adapter = new RecordingPreviewAdapter();
        ClassicalSegmentationStage stage = stage(
                thresholdStore,
                new RecordingSizeStore("1-Infinity"),
                adapter);
        ConfigQcContext context = context();

        stage.buildControls(context, new RecordingActions());
        stage.onEnter(context, new PreviewPairPanel("Original", "Objects"));

        assertEquals("200", stage.currentThresholdTokenForTest());

        stage.runPreviewNowForTest();
        assertEquals(200, adapter.lastThreshold);

        assertTrue(stage.lockIn(context));
        assertEquals("200", thresholdStore.token);
        assertEquals("200", stage.currentThresholdTokenForTest());
    }

    @Test
    public void restartPreservesUnsavedThresholdAndSize() {
        RecordingThresholdStore thresholdStore = new RecordingThresholdStore("20");
        RecordingSizeStore sizeStore = new RecordingSizeStore("1-Infinity");
        ClassicalSegmentationStage stage = stage(
                thresholdStore,
                sizeStore,
                new RecordingPreviewAdapter());
        ConfigQcContext context = context();

        stage.buildControls(context, new RecordingActions());
        stage.onEnter(context, new PreviewPairPanel("Original", "Objects"));
        stage.setThresholdForTest(55.0, 100.0);
        stage.setMinSizeForTest("8");
        stage.setMaxSizeForTest("30");

        stage.restartStage(context);
        stage.buildControls(context, new RecordingActions());
        stage.onEnter(context, new PreviewPairPanel("Original", "Objects"));

        assertEquals("55", stage.currentThresholdTokenForTest());
        assertEquals("8-30", stage.currentSizeTokenForTest());
        assertEquals("20", thresholdStore.token);
        assertEquals("1-Infinity", sizeStore.token);
    }

    private static ClassicalSegmentationStage stage(RecordingThresholdStore thresholdStore,
                                                    RecordingSizeStore sizeStore,
                                                    RecordingPreviewAdapter adapter) {
        return new ClassicalSegmentationStage(thresholdStore, sizeStore, adapter);
    }

    private static ConfigQcContext context() {
        return ConfigQcContext.fromImages(
                null,
                null,
                null,
                Arrays.asList(image("QC image")),
                Arrays.asList("IBA1"),
                0);
    }

    private static ImagePlus image(String title) {
        ImageStack stack = new ImageStack(4, 1);
        ByteProcessor processor = new ByteProcessor(4, 1);
        processor.set(0, 0, 0);
        processor.set(1, 0, 25);
        processor.set(2, 0, 75);
        processor.set(3, 0, 100);
        stack.addSlice(processor);
        return new ImagePlus(title, stack);
    }

    private static boolean hasVisibleText(Container root, String text) {
        if (root == null || text == null) return false;
        for (Component component : root.getComponents()) {
            if (!component.isVisible()) continue;
            String componentText = null;
            if (component instanceof AbstractButton) {
                componentText = ((AbstractButton) component).getText();
            } else if (component instanceof JLabel) {
                componentText = ((JLabel) component).getText();
            }
            if (text.equals(componentText)) {
                return true;
            }
            if (component instanceof Container && hasVisibleText((Container) component, text)) {
                return true;
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

    private static final class RecordingThresholdStore
            implements ClassicalSegmentationStage.ThresholdStore {
        String token;

        RecordingThresholdStore(String token) {
            this.token = token;
        }

        @Override public String get() {
            return token;
        }

        @Override public void set(String token) {
            this.token = token;
        }
    }

    private static final class RecordingSizeStore
            implements ClassicalSegmentationStage.SizeStore {
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

    private static final class RecordingPreviewAdapter
            implements ClassicalSegmentationStage.PreviewAdapter {
        int rawSourceCreations;
        int filteredSourceCreations;
        volatile int previewRuns;
        int lastThreshold;
        int lastMinSize;
        int lastMaxSize;
        boolean aliasResultImages;
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
        ImagePlus lastLabel;
        ImagePlus lastMasked;
        ImagePlus nextLabel;
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

        @Override public ObjectsCounter3DWrapper.Result runPreview(ImagePlus filteredSource,
                                                                   int threshold,
                                                                   int minSize,
                                                                   int maxSize) {
            previewRuns++;
            lastThreshold = threshold;
            lastMinSize = minSize;
            lastMaxSize = maxSize;
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
            ByteProcessor labels = new ByteProcessor(2, 1);
            ResultsTable stats = new ResultsTable();
            int row = 0;
            if (withinSize(2, minSize, maxSize)) {
                labels.set(0, 0, 1);
                stats.incrementCounter();
                stats.setValue("Label", row, 1);
                stats.setValue("Volume (pixel^3)", row, 2);
                row++;
            }
            if (withinSize(4, minSize, maxSize)) {
                labels.set(1, 0, 2);
                stats.incrementCounter();
                stats.setValue("Label", row, 2);
                stats.setValue("Volume (pixel^3)", row, 4);
            }
            lastLabel = nextLabel != null
                    ? nextLabel
                    : returnFilteredSource
                            ? filteredSource
                            : new ImagePlus("labels", labels);
            nextLabel = null;
            lastMasked = aliasResultImages ? lastLabel : null;
            previewReturned.countDown();
            return new ObjectsCounter3DWrapper.Result(
                    stats, lastLabel, lastMasked, true);
        }

        @Override public int countObjects(ObjectsCounter3DWrapper.Result result) {
            if (countFailure != null) throwTestFailure(countFailure);
            return result == null || result.getStatistics() == null
                    ? 0
                    : result.getStatistics().size();
        }

        @Override public synchronized void close(ImagePlus image) {
            if (image == null) return;
            closeObservedInterrupt |= Thread.currentThread().isInterrupted();
            Integer count = closeCounts.get(image);
            closeCounts.put(image, Integer.valueOf(count == null ? 1 : count.intValue() + 1));
            if (failNextPreviewResultClose) {
                if (image == lastLabel) {
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

        private static boolean withinSize(int voxels, int minSize, int maxSize) {
            return voxels >= minSize && voxels <= maxSize;
        }
    }

    private static final class RecordingActions implements ConfigQcActions {
        String status = "";
        ImagePlus adjustedPreview;
        JButton previewButton;
        boolean previewButtonStale;
        Throwable adjustedPreviewFailure;

        @Override public void setStatus(String text) {
            status = text;
        }

        @Override public void markPreviewStale(String text) {
            status = text;
        }

        @Override public void setAdjustedPreview(ImagePlus image, String text) {
            if (adjustedPreviewFailure != null) throwTestFailure(adjustedPreviewFailure);
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
        assertEquals(expectedRgb, image.getProcessor().getPixel(x, y) & 0xffffff);
    }

    private static void assertLabelPixel(ImagePlus labelImage, int x, int y, int expectedLabel) {
        assertNotNull(labelImage);
        assertEquals(expectedLabel, labelImage.getProcessor().get(x, y));
    }

    private static void waitForStatus(final RecordingActions actions,
                                      final String expected) throws Exception {
        TestWait.until("status did not become " + expected, new TestWait.Condition() {
            @Override public boolean isMet() {
                return expected.equals(actions.status);
            }
        }, 3000L);
        SwingUtilities.invokeAndWait(new Runnable() {
            @Override public void run() {
            }
        });
    }

    private static void waitForStatusContains(final RecordingActions actions,
                                              final String expected) throws Exception {
        TestWait.until("status did not contain " + expected, new TestWait.Condition() {
            @Override public boolean isMet() {
                return actions.status != null && actions.status.contains(expected);
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
