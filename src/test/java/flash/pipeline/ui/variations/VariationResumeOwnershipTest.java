package flash.pipeline.ui.variations;

import ij.ImagePlus;
import ij.process.ByteProcessor;
import org.junit.Test;

import javax.swing.SwingUtilities;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class VariationResumeOwnershipTest {

    @Test
    public void partialResumeReplacesRestoredAndCompletesPendingWithoutLeaking()
            throws Exception {
        final ParameterCombo restoredCombo = combo(1);
        final ParameterCombo pendingCombo = combo(2);
        final TrackingImage restoredImage = image("restored");
        final TrackingImage replacementImage = image("replacement");
        final TrackingImage pendingImage = image("pending");
        final VariationCellPanel restoredCell = cell(restoredCombo);
        final VariationCellPanel pendingCell = cell(pendingCombo);
        final VariationResult restored = VariationResult.success(restoredCombo,
                restoredImage, 10, 1L, null);
        final VariationResult replacement = VariationResult.success(restoredCombo,
                replacementImage, 11, 2L, null);
        final VariationResult pending = VariationResult.success(pendingCombo,
                pendingImage, 12, 3L, null);

        SwingUtilities.invokeAndWait(new Runnable() {
            @Override public void run() {
                restoredCell.setResult(restored);
                pendingCell.setState("running");
            }
        });

        final VariationExecutor worker = new VariationExecutor(twoCellSweep(),
                new EmptyStrategy(), null,
                (result, index) -> {
                    if (result.combo().equals(restoredCombo)) {
                        restoredCell.setResult(result);
                    } else {
                        pendingCell.setResult(result);
                    }
                }, null);
        worker.stagePendingResultForTest(replacement);
        worker.stagePendingResultForTest(pending);
        SwingUtilities.invokeAndWait(new Runnable() {
            @Override public void run() {
                worker.process(Arrays.asList(replacement, pending));
            }
        });

        assertEquals("restored lease released on replacement", 1,
                restoredImage.closeCalls);
        assertEquals(1, restoredImage.flushCalls);
        assertEquals(0, replacementImage.closeCalls);
        assertEquals(0, pendingImage.closeCalls);
        assertFalse(replacement.ownsImagesForTest());
        assertFalse(pending.ownsImagesForTest());
        assertSame(replacementImage, restoredCell.cachedLabelForTest());
        assertSame(pendingImage, pendingCell.cachedLabelForTest());

        SwingUtilities.invokeAndWait(new Runnable() {
            @Override public void run() {
                restoredCell.disposeImages();
                pendingCell.disposeImages();
                restoredCell.disposeImages();
                pendingCell.disposeImages();
            }
        });

        assertEquals(1, restoredImage.closeCalls);
        assertEquals(1, replacementImage.closeCalls);
        assertEquals(1, replacementImage.flushCalls);
        assertEquals(1, pendingImage.closeCalls);
        assertEquals(1, pendingImage.flushCalls);
    }

    @Test
    public void fatalCallbackAfterReplacementCannotDisposeCellOwnedFilterImage()
            throws Exception {
        final ParameterCombo combo = combo(1);
        final AtomicInteger restoredDisposals = new AtomicInteger();
        final AtomicInteger replacementDisposals = new AtomicInteger();
        final VariationCellPanel cell = cell(combo);
        final VariationResult restored = ownedFilter(combo, "restored-filter",
                restoredDisposals, null);
        final VariationResult replacement = ownedFilter(combo, "replacement-filter",
                replacementDisposals, null);
        final ThreadDeath fatal = new ThreadDeath();

        SwingUtilities.invokeAndWait(new Runnable() {
            @Override public void run() {
                cell.setResult(restored);
            }
        });
        final VariationExecutor worker = new VariationExecutor(oneCellSweep(),
                new EmptyStrategy(), null,
                (result, index) -> {
                    cell.setResult(result);
                    throw fatal;
                }, null);
        worker.stagePendingResultForTest(replacement);

        SwingUtilities.invokeAndWait(new Runnable() {
            @Override public void run() {
                try {
                    worker.process(Arrays.asList(replacement));
                    fail("Expected fatal result callback.");
                } catch (ThreadDeath expected) {
                    assertSame(fatal, expected);
                }
            }
        });

        assertEquals(1, restoredDisposals.get());
        assertEquals(0, replacementDisposals.get());
        assertFalse(replacement.ownsImagesForTest());

        SwingUtilities.invokeAndWait(new Runnable() {
            @Override public void run() {
                cell.disposeImages();
                cell.disposeImages();
            }
        });
        assertEquals(1, restoredDisposals.get());
        assertEquals(1, replacementDisposals.get());
    }

    @Test
    public void fatalPriorCleanupLeavesReplacementLeaseInstalledForDisposal()
            throws Exception {
        final ParameterCombo combo = combo(1);
        final AtomicInteger restoredDisposals = new AtomicInteger();
        final AtomicInteger replacementDisposals = new AtomicInteger();
        final ThreadDeath fatal = new ThreadDeath();
        final VariationCellPanel cell = cell(combo);
        final VariationResult restored = ownedFilter(combo, "fatal-restored",
                restoredDisposals, fatal);
        final VariationResult replacement = ownedFilter(combo, "kept-replacement",
                replacementDisposals, null);

        SwingUtilities.invokeAndWait(new Runnable() {
            @Override public void run() {
                cell.setResult(restored);
                try {
                    cell.setResult(replacement);
                    fail("Expected fatal prior-result cleanup.");
                } catch (ThreadDeath expected) {
                    assertSame(fatal, expected);
                }
            }
        });

        assertEquals(1, restoredDisposals.get());
        assertEquals(0, replacementDisposals.get());
        assertFalse(replacement.ownsImagesForTest());

        SwingUtilities.invokeAndWait(new Runnable() {
            @Override public void run() {
                cell.disposeImages();
                cell.disposeImages();
            }
        });
        assertEquals(2, restoredDisposals.get());
        assertEquals(1, replacementDisposals.get());
    }

    @Test
    public void transientFilterCleanupRetriesOnNextReplacementExactlyOnce()
            throws Exception {
        final ParameterCombo combo = combo(1);
        final TrackingImage restoredImage = image("retry-filter-restored");
        final TrackingImage replacementImage = image("retry-filter-replacement");
        final TrackingImage finalImage = image("retry-filter-final");
        final RuntimeException transientFailure =
                new RuntimeException("transient filter cleanup");
        final RetryingDisposer restoredDisposer =
                new RetryingDisposer(1, transientFailure);
        final RetryingDisposer replacementDisposer =
                new RetryingDisposer(0, null);
        final RetryingDisposer finalDisposer = new RetryingDisposer(0, null);
        final VariationResult restored = ownedFilter(combo, restoredImage,
                restoredDisposer);
        final VariationResult replacement = ownedFilter(combo, replacementImage,
                replacementDisposer);
        final VariationResult finalResult = ownedFilter(combo, finalImage,
                finalDisposer);
        final VariationCellPanel cell = cell(combo);

        SwingUtilities.invokeAndWait(new Runnable() {
            @Override public void run() {
                cell.setResult(restored);
                try {
                    cell.setResult(replacement);
                    fail("Expected transient restored-result cleanup failure.");
                } catch (RuntimeException expected) {
                    assertSame(transientFailure, expected);
                }
                assertFalse(replacement.ownsImagesForTest());
                cell.setResult(finalResult);
                cell.disposeImages();
                cell.disposeImages();
            }
        });

        assertEquals(2, restoredDisposer.calls);
        assertEquals(1, restoredDisposer.successfulCloses);
        assertEquals(1, restoredImage.closeCalls);
        assertEquals(1, replacementDisposer.calls);
        assertEquals(1, replacementImage.closeCalls);
        assertEquals(1, finalDisposer.calls);
        assertEquals(1, finalImage.closeCalls);
    }

    @Test
    public void transientSegmentationCloseRetriesOnDisposeWithoutDoubleClose()
            throws Exception {
        final ParameterCombo combo = combo(1);
        final RuntimeException transientFailure =
                new RuntimeException("transient segmentation close");
        final FailingOnceCloseImage restoredImage =
                new FailingOnceCloseImage("retry-segmentation-restored",
                        transientFailure);
        final TrackingImage replacementImage = image("retry-segmentation-replacement");
        final VariationResult restored = VariationResult.success(combo,
                restoredImage, 1, 1L, null);
        final VariationResult replacement = VariationResult.success(combo,
                replacementImage, 2, 2L, null);
        final VariationCellPanel cell = cell(combo);

        SwingUtilities.invokeAndWait(new Runnable() {
            @Override public void run() {
                cell.setResult(restored);
                try {
                    cell.setResult(replacement);
                    fail("Expected transient segmentation cleanup failure.");
                } catch (RuntimeException expected) {
                    assertSame(transientFailure, expected);
                }
                assertSame("replacement fields publish only after installation",
                        restoredImage, cell.cachedLabelForTest());
                cell.disposeImages();
                cell.disposeImages();
            }
        });

        assertEquals(2, restoredImage.closeAttempts);
        assertEquals(1, restoredImage.closeCalls);
        assertEquals(1, replacementImage.closeCalls);
    }

    @Test
    public void retainedAliasCarriesBuiltInCleanupPhaseIntoIndependentResult()
            throws Exception {
        final ParameterCombo combo = combo(1);
        final RuntimeException closeFailure =
                new RuntimeException("retained alias close failed");
        final FailingOnceCloseImage sharedImage =
                new FailingOnceCloseImage("phase-handoff-shared", closeFailure);
        final TrackingImage interimImage = image("phase-handoff-interim");
        final VariationResult original = VariationResult.success(combo,
                sharedImage, 1, 1L, null);
        final VariationResult interim = VariationResult.success(combo,
                interimImage, 2, 2L, null);
        final VariationResult independent = VariationResult.success(combo,
                sharedImage, 3, 3L, null);
        final VariationCellPanel cell = cell(combo);

        SwingUtilities.invokeAndWait(new Runnable() {
            @Override public void run() {
                cell.setResult(original);
                try {
                    cell.setResult(interim);
                    fail("Expected first close failure.");
                } catch (RuntimeException expected) {
                    assertSame(closeFailure, expected);
                }
                assertEquals("flush must wait for a successful close", 0,
                        sharedImage.flushCalls);
                cell.setResult(independent);
                cell.disposeImages();
                cell.disposeImages();
            }
        });

        assertEquals(2, sharedImage.closeAttempts);
        assertEquals(1, sharedImage.closeCalls);
        assertEquals("deferred flush phase must run once after handoff", 1,
                sharedImage.flushCalls);
        assertEquals(1, interimImage.closeCalls);
    }

    @Test
    public void pendingAliasIsRetainedByIndependentActiveResult()
            throws Exception {
        final ParameterCombo combo = combo(1);
        final TrackingImage sharedImage = image("independent-shared-image");
        final TrackingImage interimImage = image("independent-interim-image");
        final RuntimeException transientFailure =
                new RuntimeException("shared cleanup must be retained");
        final RetryingDisposer pendingDisposer =
                new RetryingDisposer(1, transientFailure);
        final RetryingDisposer interimDisposer =
                new RetryingDisposer(0, null);
        final VariationResult pending = ownedFilter(combo, sharedImage,
                pendingDisposer);
        final VariationResult interim = ownedFilter(combo, interimImage,
                interimDisposer);
        final VariationResult independent = VariationResult.success(combo,
                sharedImage, 3, 3L, null);
        final VariationCellPanel cell = cell(combo);

        SwingUtilities.invokeAndWait(new Runnable() {
            @Override public void run() {
                cell.setResult(pending);
                try {
                    cell.setResult(interim);
                    fail("Expected first pending cleanup failure.");
                } catch (RuntimeException expected) {
                    assertSame(transientFailure, expected);
                }
                cell.setResult(independent);
                assertSame(sharedImage, cell.cachedLabelForTest());
                cell.disposeImages();
                cell.disposeImages();
            }
        });

        assertEquals("the original custom claim must clean the active alias",
                2, pendingDisposer.calls);
        assertEquals(1, pendingDisposer.successfulCloses);
        assertEquals(1, interimDisposer.calls);
        assertEquals(1, sharedImage.closeCalls);
        assertEquals(0, sharedImage.flushCalls);
    }

    @Test
    public void directSetLabelRetainsAliasFromPendingCleanup()
            throws Exception {
        final ParameterCombo combo = combo(1);
        final TrackingImage sharedImage = image("direct-label-shared-image");
        final TrackingImage interimImage = image("direct-label-interim-image");
        final TrackingImage replacementLabel = image("direct-label-replacement");
        final RuntimeException transientFailure =
                new RuntimeException("direct label cleanup must be retained");
        final RetryingDisposer pendingDisposer =
                new RetryingDisposer(1, transientFailure);
        final RetryingDisposer interimDisposer =
                new RetryingDisposer(0, null);
        final VariationResult pending = ownedFilter(combo, sharedImage,
                pendingDisposer);
        final VariationResult interim = ownedFilter(combo, interimImage,
                interimDisposer);
        final VariationCellPanel cell = cell(combo);

        SwingUtilities.invokeAndWait(new Runnable() {
            @Override public void run() {
                cell.setResult(pending);
                try {
                    cell.setResult(interim);
                    fail("Expected first pending cleanup failure.");
                } catch (RuntimeException expected) {
                    assertSame(transientFailure, expected);
                }
                cell.setLabel(sharedImage, null, 4, 4L);
                assertSame(sharedImage, cell.cachedLabelForTest());
                cell.setLabel(replacementLabel, null, 5, 5L);
                cell.disposeImages();
                cell.disposeImages();
            }
        });

        assertEquals("the pending custom claim must follow the explicit label",
                2, pendingDisposer.calls);
        assertEquals(1, pendingDisposer.successfulCloses);
        assertEquals(1, interimDisposer.calls);
        assertEquals(1, sharedImage.closeCalls);
        assertEquals(0, sharedImage.flushCalls);
        assertEquals(1, replacementLabel.closeCalls);
        assertEquals(1, replacementLabel.flushCalls);
    }

    @Test
    public void directLabelsRemainOwnedAcrossOverwriteAndDispose()
            throws Exception {
        final ParameterCombo combo = combo(1);
        final TrackingImage first = image("direct-first");
        final TrackingImage second = image("direct-second");
        final VariationCellPanel cell = cell(combo);

        SwingUtilities.invokeAndWait(new Runnable() {
            @Override public void run() {
                cell.setLabel(first, null, 1, 1L);
                cell.setLabel(second, null, 2, 2L);
                cell.disposeImages();
                cell.disposeImages();
            }
        });

        assertEquals(1, first.closeCalls);
        assertEquals(1, first.flushCalls);
        assertEquals(1, second.closeCalls);
        assertEquals(1, second.flushCalls);
    }

    @Test
    public void directLabelPublicationWaitsForLeaseInstallationAcrossFailure()
            throws Exception {
        final ParameterCombo combo = combo(1);
        final RuntimeException cleanupFailure =
                new RuntimeException("old direct label cleanup failed");
        final FailingOnceCloseImage first =
                new FailingOnceCloseImage("transaction-first", cleanupFailure);
        final TrackingImage second = image("transaction-second");
        final TrackingImage third = image("transaction-third");
        final VariationCellPanel cell = cell(combo);

        SwingUtilities.invokeAndWait(new Runnable() {
            @Override public void run() {
                cell.setLabel(first, null, 1, 1L);
                try {
                    cell.setLabel(second, null, 2, 2L);
                    fail("Expected prior-label cleanup failure.");
                } catch (RuntimeException expected) {
                    assertSame(cleanupFailure, expected);
                }
                assertSame("the new field must not publish before installation",
                        first, cell.cachedLabelForTest());
                cell.setLabel(third, null, 3, 3L);
                assertSame(third, cell.cachedLabelForTest());
                cell.disposeImages();
            }
        });

        assertEquals(2, first.closeAttempts);
        assertEquals(1, first.closeCalls);
        assertEquals(1, first.flushCalls);
        assertEquals("the installed but unpublished lease must remain durable",
                1, second.closeCalls);
        assertEquals(1, second.flushCalls);
        assertEquals(1, third.closeCalls);
        assertEquals(1, third.flushCalls);
    }

    @Test
    public void callbackCleanupCannotReclaimReplacementInstalledBeforeFailure()
            throws Exception {
        final ParameterCombo combo = combo(1);
        final RuntimeException cleanupFailure =
                new RuntimeException("callback replacement cleanup failed");
        final RetryingDisposer firstDisposer =
                new RetryingDisposer(1, cleanupFailure);
        final RetryingDisposer secondDisposer = new RetryingDisposer(0, null);
        final RetryingDisposer thirdDisposer = new RetryingDisposer(0, null);
        final TrackingImage firstImage = image("callback-first");
        final TrackingImage secondImage = image("callback-second");
        final TrackingImage thirdImage = image("callback-third");
        final VariationResult first = ownedFilter(combo, firstImage, firstDisposer);
        final VariationResult second = ownedFilter(combo, secondImage, secondDisposer);
        final VariationResult third = ownedFilter(combo, thirdImage, thirdDisposer);
        final VariationCellPanel cell = cell(combo);

        SwingUtilities.invokeAndWait(new Runnable() {
            @Override public void run() {
                cell.setResult(first);
                try {
                    cell.setResult(second);
                    fail("Expected result callback failure.");
                } catch (RuntimeException expected) {
                    assertSame(cleanupFailure, expected);
                    // Mirrors VariationExecutor's callback-failure cleanup.
                    second.dispose();
                }
                cell.setResult(third);
                cell.disposeImages();
            }
        });

        assertEquals(2, firstDisposer.calls);
        assertEquals(1, firstDisposer.successfulCloses);
        assertEquals(1, secondDisposer.calls);
        assertEquals(1, secondDisposer.successfulCloses);
        assertEquals(1, thirdDisposer.calls);
        assertEquals(1, thirdDisposer.successfulCloses);
    }

    @Test
    public void fatalCleanupOutranksOrdinaryFailureAndBothClaimsRetry()
            throws Exception {
        final ParameterCombo combo = combo(1);
        final TrackingImage ordinaryImage = image("ordinary-pending");
        final TrackingImage fatalImage = image("fatal-pending");
        final TrackingImage installedImage = image("installed-after-fatal");
        final InterruptedException cleanupInterrupted =
                new InterruptedException("ordinary pending cleanup interrupted");
        final RuntimeException ordinaryFailure =
                new RuntimeException("ordinary pending cleanup", cleanupInterrupted);
        final ThreadDeath fatalFailure = new ThreadDeath();
        final RetryingDisposer ordinaryDisposer =
                new RetryingDisposer(2, ordinaryFailure);
        final RetryingDisposer fatalDisposer =
                new RetryingDisposer(1, fatalFailure);
        final RetryingDisposer installedDisposer =
                new RetryingDisposer(0, null);
        final VariationResult ordinary = ownedFilter(combo, ordinaryImage,
                ordinaryDisposer);
        final VariationResult fatal = ownedFilter(combo, fatalImage, fatalDisposer);
        final VariationResult installed = ownedFilter(combo, installedImage,
                installedDisposer);
        final VariationCellPanel cell = cell(combo);

        SwingUtilities.invokeAndWait(new Runnable() {
            @Override public void run() {
                cell.setResult(ordinary);
                try {
                    cell.setResult(fatal);
                    fail("Expected first ordinary cleanup failure.");
                } catch (RuntimeException expected) {
                    assertSame(ordinaryFailure, expected);
                    assertFalse(Thread.currentThread().isInterrupted());
                } finally {
                    Thread.interrupted();
                }
                try {
                    cell.setResult(installed);
                    fail("Expected VM-fatal cleanup precedence.");
                } catch (ThreadDeath expected) {
                    assertSame(fatalFailure, expected);
                    assertEquals(1, expected.getSuppressed().length);
                    assertSame(ordinaryFailure, expected.getSuppressed()[0]);
                    assertFalse(Thread.currentThread().isInterrupted());
                } finally {
                    Thread.interrupted();
                }
                assertFalse(installed.ownsImagesForTest());
                cell.disposeImages();
                cell.disposeImages();
            }
        });

        assertEquals(3, ordinaryDisposer.calls);
        assertEquals(1, ordinaryDisposer.successfulCloses);
        assertEquals(1, ordinaryImage.closeCalls);
        assertEquals(2, fatalDisposer.calls);
        assertEquals(1, fatalDisposer.successfulCloses);
        assertEquals(1, fatalImage.closeCalls);
        assertEquals(1, installedDisposer.calls);
        assertEquals(1, installedImage.closeCalls);
    }

    @Test
    public void derivedReplacementKeepsSharedLeaseUntilCellDisposal()
            throws Exception {
        final ParameterCombo combo = combo(1);
        final TrackingImage image = image("shared-derived");
        final VariationCellPanel cell = cell(combo);
        final VariationResult original = VariationResult.success(combo, image,
                10, 1L, null);
        final VariationResult derived = original.withMeanNeighbourIou(0.75d);

        SwingUtilities.invokeAndWait(new Runnable() {
            @Override public void run() {
                cell.setResult(original);
                cell.setResult(derived);
                cell.disposeImages();
            }
        });

        assertEquals(1, image.closeCalls);
        assertEquals(1, image.flushCalls);
    }

    private static VariationResult ownedFilter(ParameterCombo combo,
                                               String title,
                                               final AtomicInteger disposals,
                                               final ThreadDeath fatal) {
        return VariationResult.filterSuccess(combo,
                new ImagePlus(title, new ByteProcessor(1, 1)),
                1L, new int[256], 1.0d, 1.0d,
                new VariationResult.ImageDisposer() {
                    @Override public void dispose(ImagePlus image) {
                        int call = disposals.incrementAndGet();
                        if (fatal != null && call == 1) {
                            throw fatal;
                        }
                    }
                });
    }

    private static VariationResult ownedFilter(ParameterCombo combo,
                                               ImagePlus image,
                                               VariationResult.ImageDisposer disposer) {
        return VariationResult.filterSuccess(combo, image,
                1L, new int[256], 1.0d, 1.0d, disposer);
    }

    private static VariationCellPanel cell(ParameterCombo combo) {
        return new VariationCellPanel(combo,
                new ImagePlus("source", new ByteProcessor(1, 1)), null, null);
    }

    private static ParameterCombo combo(int threshold) {
        return ParameterCombo.builder()
                .put(ParameterId.THRESHOLD, Integer.valueOf(threshold))
                .build();
    }

    private static ParameterSweep oneCellSweep() {
        Map<ParameterId, ParameterValueList> values =
                new LinkedHashMap<ParameterId, ParameterValueList>();
        values.put(ParameterId.THRESHOLD, ParameterValueList.ofInts(1));
        return new ParameterSweep(ParameterSweep.Method.CLASSICAL, values,
                CropSpec.full(), "DAPI", "resume-hash");
    }

    private static ParameterSweep twoCellSweep() {
        Map<ParameterId, ParameterValueList> values =
                new LinkedHashMap<ParameterId, ParameterValueList>();
        values.put(ParameterId.THRESHOLD, ParameterValueList.ofInts(1, 2));
        return new ParameterSweep(ParameterSweep.Method.CLASSICAL, values,
                CropSpec.full(), "DAPI", "resume-hash");
    }

    private static TrackingImage image(String title) {
        return new TrackingImage(title);
    }

    private static final class EmptyStrategy implements VariationStrategy {
        @Override public void dispatch(ParameterSweep sweep,
                                       Consumer<VariationResult> publisher,
                                       BooleanSupplier cancelCheck) {
        }
    }

    private static class TrackingImage extends ImagePlus {
        int closeCalls;
        int flushCalls;

        TrackingImage(String title) {
            super(title, new ByteProcessor(1, 1));
        }

        @Override public void close() {
            closeCalls++;
            super.close();
        }

        @Override public void flush() {
            flushCalls++;
            super.flush();
        }
    }

    private static final class FailingOnceCloseImage extends TrackingImage {
        private final RuntimeException failure;
        int closeAttempts;

        FailingOnceCloseImage(String title, RuntimeException failure) {
            super(title);
            this.failure = failure;
        }

        @Override public void close() {
            closeAttempts++;
            if (closeAttempts == 1) {
                throw failure;
            }
            super.close();
        }
    }

    private static final class RetryingDisposer
            implements VariationResult.ImageDisposer {
        private int failuresRemaining;
        private final Throwable failure;
        int calls;
        int successfulCloses;

        RetryingDisposer(int failuresRemaining, Throwable failure) {
            this.failuresRemaining = Math.max(0, failuresRemaining);
            this.failure = failure;
        }

        @Override public void dispose(ImagePlus image) {
            calls++;
            if (failuresRemaining > 0) {
                failuresRemaining--;
                rethrow(failure);
            }
            image.close();
            successfulCloses++;
        }

        private static void rethrow(Throwable failure) {
            if (failure instanceof ThreadDeath) {
                throw (ThreadDeath) failure;
            }
            if (failure instanceof VirtualMachineError) {
                throw (VirtualMachineError) failure;
            }
            if (failure instanceof RuntimeException) {
                throw (RuntimeException) failure;
            }
            if (failure instanceof Error) {
                throw (Error) failure;
            }
            throw new IllegalStateException("Missing retry failure.", failure);
        }
    }
}
