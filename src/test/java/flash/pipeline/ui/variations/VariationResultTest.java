package flash.pipeline.ui.variations;

import ij.ImagePlus;
import ij.process.ByteProcessor;

import org.junit.Test;

import javax.swing.SwingUtilities;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

public class VariationResultTest {

    @Test
    public void derivedResultsShareOneIdentityDeduplicatedLease() {
        final AtomicInteger disposals = new AtomicInteger();
        ImagePlus image = image("owned");
        VariationResult original = ownedFilter(image, disposals);
        VariationResult derived = original.withMeanNeighbourIou(0.75d);

        assertSame(image, original.label());
        assertSame(original.label(), original.previewImage());
        assertTrue(original.ownsImagesForTest());

        derived.dispose();
        original.dispose();

        assertEquals(1, disposals.get());
        assertFalse(original.ownsImagesForTest());
        assertFalse(derived.ownsImagesForTest());
    }

    @Test
    public void transferIsOneWayAndPreventsLaterDisposal() {
        final AtomicInteger disposals = new AtomicInteger();
        VariationResult result = ownedFilter(image("adopted"), disposals);

        result.transferOwnership();
        result.dispose();
        result.transferOwnership();

        assertEquals(0, disposals.get());
        assertFalse(result.ownsImagesForTest());
    }

    @Test
    public void borrowedResultNeverClosesImage() {
        TrackingImage image = new TrackingImage("cached");
        VariationResult result = VariationResult.borrowedFilterSuccess(
                combo(), image, 0L, new int[256], 0.0d, 0.0d);

        result.dispose();

        assertEquals(0, image.closeCalls);
        assertEquals(0, image.flushCalls);
    }

    @Test
    public void directDisposalStillFlushesAfterCloseFailureAndIsExactOnce() {
        RuntimeException closeFailure = new RuntimeException("close failed");
        FailingImage image = new FailingImage("failing", closeFailure);
        VariationResult result = VariationResult.filterSuccess(
                combo(), image, 0L, new int[256], 0.0d, 0.0d);

        try {
            result.dispose();
            fail("Expected close failure.");
        } catch (RuntimeException expected) {
            assertSame(closeFailure, expected);
        }
        result.dispose();

        assertEquals(1, image.closeCalls);
        assertEquals(1, image.flushCalls);
    }

    @Test
    public void directVmFatalStopsImmediatelyAndRetainsClaimForExplicitRetry() {
        final ThreadDeath fatal = new ThreadDeath();
        final AtomicBoolean allowSuccess = new AtomicBoolean();
        final AtomicInteger calls = new AtomicInteger();
        VariationResult result = VariationResult.filterSuccess(
                combo(), image("direct-fatal"), 0L, new int[256], 0.0d, 0.0d,
                new VariationResult.ImageDisposer() {
                    @Override public void dispose(ImagePlus ignored) {
                        calls.incrementAndGet();
                        if (!allowSuccess.get()) {
                            throw fatal;
                        }
                    }
                });

        try {
            result.dispose();
            fail("Expected direct fatal cleanup failure.");
        } catch (ThreadDeath expected) {
            assertSame(fatal, expected);
        }
        assertEquals(1, calls.get());
        assertTrue(result.ownsImagesForTest());

        allowSuccess.set(true);
        result.dispose();
        assertEquals(2, calls.get());
        assertFalse(result.ownsImagesForTest());
    }

    @Test
    public void disposalRestoresInterruptAfterCleanup() {
        final AtomicInteger interruptedDuringCleanup = new AtomicInteger();
        VariationResult result = VariationResult.filterSuccess(combo(), image("interrupt"),
                0L, new int[256], 0.0d, 0.0d,
                new VariationResult.ImageDisposer() {
                    @Override public void dispose(ImagePlus ignored) {
                        if (Thread.currentThread().isInterrupted()) {
                            interruptedDuringCleanup.incrementAndGet();
                        }
                    }
                });

        Thread.currentThread().interrupt();
        try {
            result.dispose();
            assertEquals(0, interruptedDuringCleanup.get());
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    public void transferredBuiltInCleanupRetriesFlushWithoutClosingTwice() {
        RuntimeException flushFailure = new RuntimeException("flush failed");
        FailingOnceFlushImage image =
                new FailingOnceFlushImage("flush-retry", flushFailure);
        VariationResult result = VariationResult.filterSuccess(
                combo(), image, 0L, new int[256], 0.0d, 0.0d);
        result.transferOwnership();

        try {
            result.releaseTransferredImages();
            fail("Expected first flush failure.");
        } catch (RuntimeException expected) {
            assertSame(flushFailure, expected);
        }
        result.releaseTransferredImages();
        result.releaseTransferredImages();

        assertEquals(1, image.closeCalls);
        assertEquals(2, image.flushAttempts);
        assertEquals(1, image.flushCalls);
        assertEquals(0, result.pendingTransferredImages().length);
    }

    @Test
    public void cleanupInterruptedExceptionDoesNotCreateInterruptState() {
        final InterruptedException interrupted =
                new InterruptedException("cleanup interrupted");
        VariationResult result = VariationResult.filterSuccess(combo(), image("interrupt"),
                0L, new int[256], 0.0d, 0.0d,
                new VariationResult.ImageDisposer() {
                    @Override public void dispose(ImagePlus ignored) {
                        sneakyThrow(interrupted);
                    }
                });

        try {
            result.dispose();
            fail("Expected wrapped interruption.");
        } catch (IllegalStateException expected) {
            assertSame(interrupted, expected.getCause());
            assertFalse(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    public void edtCleanupPreservesOnlyInterruptStatePresentOnEntry()
            throws Exception {
        final AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
        SwingUtilities.invokeAndWait(new Runnable() {
            @Override public void run() {
                try {
                    interruptedResult("clear-edt").dispose();
                    fail("Expected wrapped interruption.");
                } catch (IllegalStateException expected) {
                    assertFalse(Thread.currentThread().isInterrupted());
                }

                Thread.currentThread().interrupt();
                try {
                    interruptedResult("interrupted-edt").dispose();
                    fail("Expected wrapped interruption.");
                } catch (IllegalStateException expected) {
                    assertTrue(Thread.currentThread().isInterrupted());
                } catch (Throwable unexpected) {
                    failure.set(unexpected);
                } finally {
                    Thread.interrupted();
                }
            }
        });
        if (failure.get() != null) {
            throw new AssertionError(failure.get());
        }
    }

    @Test
    public void offEdtWaiterInfersInterruptionWithoutInterruptingEdt()
            throws Exception {
        final AtomicBoolean edtInterrupted = new AtomicBoolean(true);
        final AtomicBoolean callerInterrupted = new AtomicBoolean();
        final AtomicReference<Throwable> unexpected = new AtomicReference<Throwable>();
        Thread caller = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    VariationCleanupSupport.runOnEdtAndWait(
                            new VariationCleanupSupport.Task() {
                                @Override public void run() {
                                    try {
                                        interruptedResult("off-edt-waiter").dispose();
                                    } finally {
                                        edtInterrupted.set(
                                                Thread.currentThread().isInterrupted());
                                    }
                                }
                            });
                    unexpected.set(new AssertionError("Expected wrapped interruption."));
                } catch (IllegalStateException expected) {
                    callerInterrupted.set(Thread.currentThread().isInterrupted());
                } catch (Throwable failure) {
                    unexpected.set(failure);
                } finally {
                    Thread.interrupted();
                }
            }
        }, "variation-cleanup-waiter");

        caller.start();
        caller.join(5000L);

        assertFalse("cleanup waiter did not finish", caller.isAlive());
        assertNull(unexpected.get());
        assertTrue(callerInterrupted.get());
        assertFalse(edtInterrupted.get());
    }

    private static VariationResult interruptedResult(String title) {
        final InterruptedException interrupted =
                new InterruptedException("cleanup interrupted");
        return VariationResult.filterSuccess(combo(), image(title),
                0L, new int[256], 0.0d, 0.0d,
                new VariationResult.ImageDisposer() {
                    @Override public void dispose(ImagePlus ignored) {
                        sneakyThrow(interrupted);
                    }
                });
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> void sneakyThrow(Throwable failure) throws T {
        throw (T) failure;
    }

    private static VariationResult ownedFilter(ImagePlus image,
                                               final AtomicInteger disposals) {
        return VariationResult.filterSuccess(combo(), image, 0L, new int[256],
                0.0d, 0.0d, new VariationResult.ImageDisposer() {
                    @Override public void dispose(ImagePlus ignored) {
                        disposals.incrementAndGet();
                    }
                });
    }

    private static ParameterCombo combo() {
        return ParameterCombo.builder().build();
    }

    private static ImagePlus image(String title) {
        return new ImagePlus(title, new ByteProcessor(1, 1));
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

    private static final class FailingImage extends TrackingImage {
        private final RuntimeException closeFailure;

        FailingImage(String title, RuntimeException closeFailure) {
            super(title);
            this.closeFailure = closeFailure;
        }

        @Override public void close() {
            closeCalls++;
            throw closeFailure;
        }
    }

    private static final class FailingOnceFlushImage extends TrackingImage {
        private final RuntimeException flushFailure;
        int flushAttempts;

        FailingOnceFlushImage(String title, RuntimeException flushFailure) {
            super(title);
            this.flushFailure = flushFailure;
        }

        @Override public void flush() {
            flushAttempts++;
            if (flushAttempts == 1) {
                throw flushFailure;
            }
            super.flush();
        }
    }
}
