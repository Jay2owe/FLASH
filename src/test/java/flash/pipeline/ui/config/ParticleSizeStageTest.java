package flash.pipeline.ui.config;

import flash.pipeline.objects.ObjectsCounter3DWrapper;
import flash.pipeline.testutil.EdtUncaughtExceptionCapture;
import flash.pipeline.testutil.TestWait;
import flash.pipeline.ui.preview.LabelMapStyler;
import flash.pipeline.ui.preview.PreviewPairPanel;
import ij.ImagePlus;
import ij.ImageStack;
import ij.measure.ResultsTable;
import ij.process.ByteProcessor;
import org.junit.Test;

import javax.swing.JButton;
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

public class ParticleSizeStageTest {

    @Test
    public void parsesSizeTokensForFields() {
        ParticleSizeStage.SizeToken token =
                ParticleSizeStage.parseSizeToken("12.4-99.6");

        assertEquals("12", token.minText);
        assertEquals("100", token.maxText);
        assertEquals("12-100", token.toToken());

        ParticleSizeStage.SizeToken infinity =
                ParticleSizeStage.parseSizeToken("25-inf");
        assertEquals("25-Infinity", infinity.toToken());

        assertEquals("100-Infinity",
                ParticleSizeStage.parseSizeToken("not-a-range").toToken());
    }

    @Test
    public void textFieldEditMarksPreviewStaleWithoutRunningObjectPreview() throws Exception {
        RecordingStore store = new RecordingStore("1-Infinity");
        RecordingPreviewAdapter adapter = new RecordingPreviewAdapter();
        RecordingActions actions = new RecordingActions();
        ParticleSizeStage stage = new ParticleSizeStage(store, adapter);

        stage.buildControls(context(), actions);
        stage.onEnter(context(), new PreviewPairPanel("Original", "Adjusted"));
        adapter.previewRuns = 0;

        stage.setMinSizeForTest("2");

        assertTrue(stage.isPreviewStaleForTest());
        assertTrue(actions.status.contains("Preview"));
        assertTrue(actions.previewButtonStale);
        assertEquals("\u25CF Run Preview", actions.previewButton.getText());
        assertEquals("Field edits must not execute the object preview",
                0, adapter.previewRuns);
    }

    @Test
    public void previewRunsOnlyWhenExplicitlyRequested() throws Exception {
        RecordingStore store = new RecordingStore("1-Infinity");
        RecordingPreviewAdapter adapter = new RecordingPreviewAdapter();
        RecordingActions actions = new RecordingActions();
        ParticleSizeStage stage = new ParticleSizeStage(store, adapter);

        stage.buildControls(context(), actions);
        stage.onEnter(context(), new PreviewPairPanel("Original", "Adjusted"));

        assertEquals(0, adapter.previewRuns);
        assertEquals(42, stage.thresholdForTest());
        assertEquals("\u25CF Run Preview", actions.previewButton.getText());

        stage.runPreviewNowForTest();

        assertEquals(1, adapter.previewRuns);
        assertEquals(42, adapter.lastThreshold);
        assertEquals(1, adapter.lastMinSize);
        assertFalse(stage.isPreviewStaleForTest());
        assertNotNull(actions.adjustedPreview);
        assertEquals("Objects: 2 ready", actions.status);
        assertFalse(actions.previewButtonStale);
        assertEquals("Run Preview", actions.previewButton.getText());
        assertEquals(0, adapter.closeCount(adapter.lastLabel));
        stage.onLeave(context());
        assertEquals(1, adapter.closeCount(adapter.lastLabel));
    }

    @Test
    public void replacementCloseFailureRetainsOldAndKeepsNewUntilDispose()
            throws Exception {
        RecordingPreviewAdapter adapter = new RecordingPreviewAdapter();
        ParticleSizeStage stage = new ParticleSizeStage(
                new RecordingStore("1-Infinity"), adapter);
        ConfigQcContext context = context();
        stage.buildControls(context, new RecordingActions());
        stage.onEnter(context, new PreviewPairPanel("Original", "Adjusted"));

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
        ParticleSizeStage stage = new ParticleSizeStage(
                new RecordingStore("1-Infinity"), adapter);
        stage.buildControls(context, new RecordingActions());
        stage.onEnter(context, new PreviewPairPanel("Original", "Adjusted"));

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
        ParticleSizeStage stage = new ParticleSizeStage(
                new RecordingStore("1-Infinity"), adapter);
        stage.buildControls(context, new RecordingActions());
        stage.onEnter(context, new PreviewPairPanel("Original", "Adjusted"));

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
        ParticleSizeStage stage = new ParticleSizeStage(
                new RecordingStore("1-Infinity"), adapter);
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
    public void ordinaryCleanupFailureStillAttemptsEveryDistinctResultImage() throws Exception {
        RecordingPreviewAdapter adapter = new RecordingPreviewAdapter();
        RuntimeException countFailure = new IllegalStateException("count failed");
        RuntimeException cleanupFailure = new IllegalArgumentException("close failed");
        adapter.includeDistinctMasked = true;
        adapter.countFailure = countFailure;
        adapter.closeFailure = cleanupFailure;
        ConfigQcContext context = context();
        ParticleSizeStage stage = new ParticleSizeStage(
                new RecordingStore("1-Infinity"), adapter);
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

        assertEquals(1, adapter.closeCount(adapter.lastMasked));
        assertEquals(1, adapter.closeCount(adapter.lastLabel));
    }

    @Test
    public void callbackFailureClosesProvisionalLabelExactlyOnce() throws Exception {
        RecordingPreviewAdapter adapter = new RecordingPreviewAdapter();
        RecordingActions actions = new RecordingActions();
        RuntimeException callbackFailure = new IllegalStateException("callback failed");
        ConfigQcContext context = context();
        ParticleSizeStage stage = new ParticleSizeStage(
                new RecordingStore("1-Infinity"), adapter);
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
        ParticleSizeStage stage = new ParticleSizeStage(
                new RecordingStore("1-Infinity"), adapter);
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
            ParticleSizeStage stage = new ParticleSizeStage(
                    new RecordingStore("1-Infinity"), adapter);
            stage.buildControls(context, new RecordingActions());
            stage.onEnter(context, new PreviewPairPanel("Original", "Adjusted"));

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
        ParticleSizeStage stage = new ParticleSizeStage(
                new RecordingStore("1-Infinity"), adapter);
        stage.buildControls(context, new RecordingActions());
        stage.onEnter(context, new PreviewPairPanel("Original", "Adjusted"));

        stage.runPreviewOnWorkerForTest();
        assertTrue(adapter.previewStarted.await(2L, TimeUnit.SECONDS));
        stage.onLeave(context);
        TestWait.until("cancelled particle-size result was not closed", new TestWait.Condition() {
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
        ParticleSizeStage stage = new ParticleSizeStage(
                new RecordingStore("1-Infinity"), adapter);
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
        assertSame(adapter.filteredSource, adapter.lastLabel);
        assertSame(null, stage.previewWorkerCompletionFailureForTest());
    }

    @Test
    public void queuedCancellationReleasesInputWithoutStartingAdapter() throws Exception {
        RecordingPreviewAdapter adapter = new RecordingPreviewAdapter();
        ConfigQcContext context = context();
        ParticleSizeStage stage = new ParticleSizeStage(
                new RecordingStore("1-Infinity"), adapter);
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
        ParticleSizeStage stage = new ParticleSizeStage(
                new RecordingStore("1-Infinity"), adapter);
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
            ParticleSizeStage stage = new ParticleSizeStage(
                    new RecordingStore("1-Infinity"), adapter);
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
        ParticleSizeStage stage = new ParticleSizeStage(
                new RecordingStore("1-Infinity"), adapter);
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
        adapter.aliasResultImages = true;
        adapter.failNextPreviewResultClose = true;
        adapter.closeFailure = cleanupFailure;
        ConfigQcContext context = context();
        ParticleSizeStage stage = new ParticleSizeStage(
                new RecordingStore("1-Infinity"), adapter);
        stage.buildControls(context, new RecordingActions());
        stage.onEnter(context, new PreviewPairPanel("Original", "Adjusted"));

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

    private static void waitForWorkerCompletion(final ParticleSizeStage stage)
            throws Exception {
        TestWait.until("particle-size worker physical completion was not handled",
                new TestWait.Condition() {
                    @Override public boolean isMet() {
                        return stage.previewWorkerCompletionHandledForTest();
                    }
                }, 3000L);
    }

    @Test
    public void sizeEditsAfterPreviewRelabelRemovedObjectsWithoutRerunning() throws Exception {
        RecordingStore store = new RecordingStore("1-Infinity");
        RecordingPreviewAdapter adapter = new RecordingPreviewAdapter();
        RecordingActions actions = new RecordingActions();
        ParticleSizeStage stage = new ParticleSizeStage(store, adapter);
        PreviewPairPanel pair = new PreviewPairPanel("Original", "Adjusted");

        stage.buildControls(context(), actions);
        stage.onEnter(context(), pair);
        stage.runPreviewNowForTest();
        adapter.previewRuns = 0;

        stage.setMinSizeForTest("5");

        assertFalse(stage.isPreviewStaleForTest());
        assertEquals("Size edits must reuse cached object statistics",
                0, adapter.previewRuns);
        assertEquals("Objects: 1 kept; removed 1 small, 0 large", actions.status);
        assertEquals("Objects: 1 kept; removed 1 small, 0 large",
                stage.sizeCutoffSummaryForTest());
        assertFalse(actions.previewButtonStale);
        ImagePlus rendered = pair.duplicateCurrentObjectPreviewForComparison("Rendered object preview");
        assertRgbPixel(rendered, 0, 0, 0x000000);
        assertRgbPixel(rendered, 1, 0, LabelMapStyler.rgbForLabel(2));
    }

    @Test
    public void onEnterCreatesRawAndFilteredSourcesAndDefaultsToFiltered() {
        RecordingPreviewAdapter adapter = new RecordingPreviewAdapter();
        ParticleSizeStage stage = new ParticleSizeStage(
                new RecordingStore("1-Infinity"), adapter);

        stage.buildControls(context(), new RecordingActions());
        stage.onEnter(context(), new PreviewPairPanel("Original", "Adjusted"));

        assertEquals(1, adapter.rawSourceCreations);
        assertEquals(1, adapter.filteredSourceCreations);
        assertTrue(stage.currentSourceTitleForTest().startsWith("filtered"));
        assertEquals(2, stage.largePreviewPaneCountForTest());
    }

    @Test
    public void sourceSwitchingDoesNotRunObjectPreviewBeforeLabelsExist() {
        RecordingPreviewAdapter adapter = new RecordingPreviewAdapter();
        RecordingActions actions = new RecordingActions();
        ParticleSizeStage stage = new ParticleSizeStage(
                new RecordingStore("1-Infinity"), adapter);

        stage.buildControls(context(), actions);
        stage.onEnter(context(), new PreviewPairPanel("Original", "Adjusted"));
        adapter.previewRuns = 0;

        stage.selectRawSourceForTest();

        assertTrue(stage.currentSourceTitleForTest().startsWith("raw"));
        assertEquals(0, adapter.previewRuns);
        assertEquals(null, actions.adjustedPreview);
    }

    @Test
    public void overlayToggleRendersObjectsOverSelectedSource() throws Exception {
        RecordingPreviewAdapter adapter = new RecordingPreviewAdapter();
        RecordingActions actions = new RecordingActions();
        ParticleSizeStage stage = new ParticleSizeStage(
                new RecordingStore("1-Infinity"), adapter);

        stage.buildControls(context(), actions);
        stage.onEnter(context(), new PreviewPairPanel("Original", "Adjusted",
                PreviewPairPanel.PreviewLayout.HORIZONTAL_SLIM));
        stage.runPreviewNowForTest();

        assertEquals("Object label preview", actions.adjustedPreview.getTitle());
        assertEquals(3, stage.largePreviewPaneCountForTest());
        assertFalse(stage.objectOverlaySelectedForTest());

        stage.setShowOverlayForTest(true);

        assertTrue(stage.objectOverlaySelectedForTest());

        stage.selectRawSourceForTest();

        assertTrue(stage.currentSourceTitleForTest().startsWith("raw"));
        assertEquals("Objects: 2 ready", actions.status);
    }

    @Test
    public void lockInWritesNormalizedSizeToken() {
        RecordingStore store = new RecordingStore("1-Infinity");
        ParticleSizeStage stage = new ParticleSizeStage(store, new RecordingPreviewAdapter());
        ConfigQcContext context = context();

        stage.buildControls(context, new RecordingActions());
        stage.onEnter(context, new PreviewPairPanel("Original", "Adjusted"));
        stage.setMinSizeForTest("4.6");
        stage.setMaxSizeForTest("20.2");

        assertTrue(stage.lockIn(context));

        assertEquals("5-20", store.token);
        assertEquals("5-20", stage.currentSizeTokenForTest());
    }

    @Test
    public void lockInRejectsFiniteMaxNotGreaterThanMin() {
        RecordingStore store = new RecordingStore("1-Infinity");
        ParticleSizeStage stage = new ParticleSizeStage(store, new RecordingPreviewAdapter());
        ConfigQcContext context = context();

        stage.buildControls(context, new RecordingActions());
        stage.onEnter(context, new PreviewPairPanel("Original", "Adjusted"));
        stage.setMinSizeForTest("20");
        stage.setMaxSizeForTest("20");

        assertFalse(stage.lockIn(context));
        assertEquals("1-Infinity", store.token);
    }

    @Test
    public void restartKeepsCurrentEditedSizeAfterStageRebuild() {
        RecordingStore store = new RecordingStore("1-Infinity");
        ParticleSizeStage stage = new ParticleSizeStage(store, new RecordingPreviewAdapter());
        ConfigQcContext context = context();

        stage.buildControls(context, new RecordingActions());
        stage.onEnter(context, new PreviewPairPanel("Original", "Adjusted"));
        stage.setMinSizeForTest("8");
        stage.setMaxSizeForTest("30");

        stage.restartStage(context);
        stage.buildControls(context, new RecordingActions());
        stage.onEnter(context, new PreviewPairPanel("Original", "Adjusted"));

        assertEquals("8-30", stage.currentSizeTokenForTest());
        assertEquals("1-Infinity", store.token);
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
        ImageStack stack = new ImageStack(3, 3);
        ByteProcessor processor = new ByteProcessor(3, 3);
        processor.set(1, 1, 12);
        stack.addSlice(processor);
        return new ImagePlus(title, stack);
    }

    private static final class RecordingStore implements ParticleSizeStage.SizeStore {
        String token;

        RecordingStore(String token) {
            this.token = token;
        }

        @Override public String get() {
            return token;
        }

        @Override public void set(String token) {
            this.token = token;
        }
    }

    private static final class RecordingPreviewAdapter implements ParticleSizeStage.PreviewAdapter {
        int rawSourceCreations;
        int filteredSourceCreations;
        int previewRuns;
        int lastThreshold;
        int lastMinSize;
        boolean aliasResultImages;
        boolean includeDistinctMasked;
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

        @Override public int resolveThreshold(ImagePlus filteredSource, ConfigQcContext context) {
            return 42;
        }

        @Override public ObjectsCounter3DWrapper.Result runPreview(ImagePlus filteredSource,
                                                                   int threshold,
                                                                   int minSize,
                                                                   int maxSize) {
            previewRuns++;
            lastThreshold = threshold;
            lastMinSize = minSize;
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
            labels.set(0, 0, 1);
            labels.set(1, 0, 2);
            ResultsTable stats = new ResultsTable();
            stats.incrementCounter();
            stats.setValue("Label", 0, 1);
            stats.setValue("Volume (pixel^3)", 0, 2);
            stats.incrementCounter();
            stats.setValue("Label", 1, 2);
            stats.setValue("Volume (pixel^3)", 1, 10);
            lastLabel = nextLabel != null
                    ? nextLabel
                    : returnFilteredSource
                            ? filteredSource
                            : new ImagePlus("labels", labels);
            nextLabel = null;
            if (aliasResultImages) {
                lastMasked = lastLabel;
            } else if (includeDistinctMasked) {
                lastMasked = new ImagePlus("masked", labels.duplicate());
            } else {
                lastMasked = null;
            }
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

}
