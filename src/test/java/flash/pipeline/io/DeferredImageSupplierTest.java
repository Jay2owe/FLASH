package flash.pipeline.io;

import flash.pipeline.testutil.TestWait;
import ij.ImagePlus;
import ij.ImageStack;
import ij.process.ByteProcessor;
import org.junit.Test;

import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Regression tests for prefetched-image ownership and physical retirement. */
public class DeferredImageSupplierTest {

    @Test
    public void interruptedCallerRemainsVisibleUntilShutdownJoinsLateResult()
            throws Exception {
        final BlockingPrefetch fixture = new BlockingPrefetch(
                new TrackingImagePlus("late-joined"));
        fixture.supplier.setPrefetchShutdownTimeoutMillisForTests(2000L);
        ExecutorService callerExecutor = Executors.newSingleThreadExecutor();
        ExecutorService shutdownExecutor = Executors.newSingleThreadExecutor();
        try {
            fixture.supplier.startPrefetch(0, 1);
            TestWait.awaitLatch("prefetch opener to enter", fixture.entered, 2000L);

            CallerResult caller = interruptWaitingCaller(
                    fixture.supplier, callerExecutor, 0, 1);
            assertTrue("getOrLoad must restore interruption before rethrowing",
                    caller.interruptRestored);
            TestWait.awaitLatch("prefetch opener to receive cancellation",
                    fixture.openerInterrupted, 2000L);
            assertEquals("abandoned entry must be registered exactly once",
                    1, fixture.supplier.retiringPrefetchCountForTests());

            Future<Void> shutdown = shutdownExecutor.submit(new Callable<Void>() {
                @Override
                public Void call() {
                    fixture.supplier.shutdownPrefetch();
                    return null;
                }
            });
            assertFalse("shutdown must wait for physical callable exit",
                    shutdown.isDone());

            fixture.release.countDown();
            TestWait.get("prefetch shutdown to join late result", shutdown, 2000L);

            assertEquals(0, fixture.supplier.retiringPrefetchCountForTests());
            assertEquals(1, fixture.image.closeCalls);
            assertEquals(1, fixture.image.flushCalls);
            fixture.supplier.shutdownPrefetch();
            assertEquals(1, fixture.image.closeCalls);
            assertEquals(1, fixture.image.flushCalls);
        } finally {
            fixture.release.countDown();
            fixture.supplier.shutdownPrefetch();
            TestWait.shutdown("interrupted prefetch caller", callerExecutor, 2000L);
            TestWait.shutdown("prefetch shutdown", shutdownExecutor, 2000L);
        }
    }

    @Test
    public void timedOutRetirementIsDeduplicatedAndRepeatedShutdownConverges()
            throws Exception {
        final BlockingPrefetch fixture = new BlockingPrefetch(
                new TrackingImagePlus("late-after-timeout"));
        fixture.supplier.setPrefetchShutdownTimeoutMillisForTests(25L);
        ExecutorService callerExecutor = Executors.newSingleThreadExecutor();
        try {
            fixture.supplier.startPrefetch(0, 1);
            TestWait.awaitLatch("prefetch opener to enter", fixture.entered, 2000L);
            interruptWaitingCaller(fixture.supplier, callerExecutor, 0, 1);

            fixture.supplier.shutdownPrefetch();
            assertEquals("timed-out physical work must remain visible",
                    1, fixture.supplier.retiringPrefetchCountForTests());
            fixture.supplier.shutdownPrefetch();
            assertEquals("repeated timeout must not duplicate retirement",
                    1, fixture.supplier.retiringPrefetchCountForTests());

            fixture.release.countDown();
            fixture.supplier.setPrefetchShutdownTimeoutMillisForTests(2000L);
            fixture.supplier.shutdownPrefetch();
            assertEquals("completed retirement must not remain stale",
                    0, fixture.supplier.retiringPrefetchCountForTests());
            assertEquals(1, fixture.image.closeCalls);
            assertEquals(1, fixture.image.flushCalls);

            fixture.supplier.shutdownPrefetch();
            assertEquals(0, fixture.supplier.retiringPrefetchCountForTests());
            assertEquals(1, fixture.image.closeCalls);
            assertEquals(1, fixture.image.flushCalls);
        } finally {
            fixture.release.countDown();
            fixture.supplier.setPrefetchShutdownTimeoutMillisForTests(2000L);
            fixture.supplier.shutdownPrefetch();
            TestWait.shutdown("timed-out prefetch caller", callerExecutor, 2000L);
        }
    }

    @Test
    public void lateCleanupFailureIsReportedAfterTimeoutAndThenRetired()
            throws Exception {
        RuntimeException cleanupFailure =
                new RuntimeException("late cleanup failed");
        final CleanupFailingImagePlus image = new CleanupFailingImagePlus(
                "late-cleanup-failure", cleanupFailure);
        final BlockingPrefetch fixture = new BlockingPrefetch(image);
        fixture.supplier.setPrefetchShutdownTimeoutMillisForTests(25L);
        ExecutorService callerExecutor = Executors.newSingleThreadExecutor();
        try {
            fixture.supplier.startPrefetch(0, 1);
            TestWait.awaitLatch("prefetch opener to enter", fixture.entered, 2000L);
            interruptWaitingCaller(fixture.supplier, callerExecutor, 0, 1);

            fixture.supplier.shutdownPrefetch();
            assertEquals(1, fixture.supplier.retiringPrefetchCountForTests());
            fixture.release.countDown();
            fixture.supplier.setPrefetchShutdownTimeoutMillisForTests(2000L);
            try {
                fixture.supplier.shutdownPrefetch();
                fail("Expected delayed cleanup failure");
            } catch (RuntimeException expected) {
                assertSame(cleanupFailure, expected);
            }

            assertEquals(0, fixture.supplier.retiringPrefetchCountForTests());
            assertEquals(1, image.closeCalls);
            assertEquals("flush must run even when close fails", 1, image.flushCalls);
            fixture.supplier.shutdownPrefetch();
        } finally {
            fixture.release.countDown();
            fixture.supplier.setPrefetchShutdownTimeoutMillisForTests(2000L);
            shutdownIgnoringFailure(fixture.supplier);
            TestWait.shutdown("cleanup-failure caller", callerExecutor, 2000L);
        }
    }

    @Test
    public void normalCallerTransferUnregistersWithoutClosingCallerImage()
            throws Exception {
        TrackingImagePlus image = new TrackingImagePlus("caller-owned");
        final DeferredImageSupplier supplier = supplierReturning(image);
        supplier.startPrefetch(0, 1);
        assertTrue(supplier.awaitPrefetchCompletionForTests(0, 2000L));

        assertSame(image, supplier.getOrLoadMaterialized(0));
        assertEquals(0, supplier.retiringPrefetchCountForTests());
        supplier.shutdownPrefetch();
        assertEquals(0, image.closeCalls);
        assertEquals(0, image.flushCalls);

        image.close();
        image.flush();
        assertEquals(1, image.closeCalls);
        assertEquals(1, image.flushCalls);
    }

    @Test
    public void callerConsumedTaskFailureIsNotDeliveredAgainByShutdown()
            throws Exception {
        final AssertionError failure =
                new AssertionError("caller-consumed prefetch failure");
        DeferredImageSupplier supplier = new DeferredImageSupplier(
                Collections.singletonList(new File("unused-0.tif")), "test") {
            @Override
            public ImagePlus openSeriesMaterialized(int seriesIndex) {
                throw failure;
            }
        };
        supplier.startPrefetch(0, 1);

        try {
            supplier.getOrLoadMaterialized(0);
            fail("Expected original prefetched task failure");
        } catch (AssertionError expected) {
            assertSame(failure, expected);
        }

        assertEquals("caller-consumed failure must leave retirement tracking",
                0, supplier.retiringPrefetchCountForTests());
        supplier.shutdownPrefetch();
        supplier.shutdownPrefetch();
        assertEquals(0, supplier.retiringPrefetchCountForTests());
    }

    @Test
    public void lateVmFatalCleanupOutranksEarlierOrdinaryFailure()
            throws Exception {
        final RuntimeException ordinary =
                new RuntimeException("ordinary late cleanup failure");
        final ThreadDeath fatal = new ThreadDeath();
        final CleanupFailingImagePlus first = new CleanupFailingImagePlus(
                "ordinary-late-cleanup", ordinary);
        final CleanupFailingImagePlus second = new CleanupFailingImagePlus(
                "fatal-late-cleanup", fatal);
        final CountDownLatch entered = new CountDownLatch(2);
        final CountDownLatch release = new CountDownLatch(1);
        DeferredImageSupplier supplier = new DeferredImageSupplier(
                Arrays.asList(new File("unused-0.tif"),
                        new File("unused-1.tif")), "test") {
            @Override
            public ImagePlus openSeriesMaterialized(int seriesIndex) {
                entered.countDown();
                boolean released = false;
                while (!released) {
                    try {
                        release.await();
                        released = true;
                    } catch (InterruptedException ignoredCancellation) {
                        // Exercise interrupt-ignoring image openers.
                    }
                }
                return seriesIndex == 0 ? first : second;
            }
        };
        supplier.setPrefetchShutdownTimeoutMillisForTests(25L);
        ExecutorService callerExecutor = Executors.newFixedThreadPool(2);
        try {
            supplier.startPrefetch(0, 2);
            TestWait.awaitLatch("both prefetch openers to enter", entered, 2000L);
            interruptWaitingCaller(supplier, callerExecutor, 0, 1);
            interruptWaitingCaller(supplier, callerExecutor, 1, 2);

            supplier.shutdownPrefetch();
            assertEquals(2, supplier.retiringPrefetchCountForTests());
            release.countDown();
            supplier.setPrefetchShutdownTimeoutMillisForTests(2000L);
            try {
                supplier.shutdownPrefetch();
                fail("Expected VM-fatal delayed cleanup failure");
            } catch (ThreadDeath expected) {
                assertSame(fatal, expected);
            }

            assertEquals(1, fatal.getSuppressed().length);
            assertSame(ordinary, fatal.getSuppressed()[0]);
            assertEquals(1, first.closeCalls);
            assertEquals(1, first.flushCalls);
            assertEquals(1, second.closeCalls);
            assertEquals(1, second.flushCalls);
            assertEquals(0, supplier.retiringPrefetchCountForTests());
            supplier.shutdownPrefetch();
        } finally {
            release.countDown();
            supplier.setPrefetchShutdownTimeoutMillisForTests(2000L);
            shutdownIgnoringFailure(supplier);
            TestWait.shutdown("VM-fatal cleanup callers", callerExecutor, 2000L);
        }
    }

    private static void shutdownIgnoringFailure(DeferredImageSupplier supplier) {
        try {
            supplier.shutdownPrefetch();
        } catch (Throwable ignoredCleanupFailure) {
            // Test cleanup must not mask the assertion that triggered it.
        }
    }

    private static CallerResult interruptWaitingCaller(
            final DeferredImageSupplier supplier,
            ExecutorService callerExecutor,
            final int seriesIndex,
            final int expectedRetirementCount) throws Exception {
        final AtomicReference<Thread> callerThread = new AtomicReference<Thread>();
        final AtomicBoolean interruptionRestored = new AtomicBoolean();
        Future<Void> caller = callerExecutor.submit(new Callable<Void>() {
            @Override
            public Void call() throws Exception {
                callerThread.set(Thread.currentThread());
                try {
                    supplier.getOrLoadMaterialized(seriesIndex);
                    fail("Expected interrupted prefetch wait");
                } catch (InterruptedException expected) {
                    interruptionRestored.set(Thread.currentThread().isInterrupted());
                }
                return null;
            }
        });
        TestWait.await("caller to register prefetched entry", 2000L,
                new TestWait.Condition() {
                    @Override
                    public boolean isMet() {
                        return callerThread.get() != null
                                && supplier.retiringPrefetchCountForTests()
                                == expectedRetirementCount;
                    }
                });
        callerThread.get().interrupt();
        TestWait.get("interrupted prefetched-image caller", caller, 2000L);
        return new CallerResult(interruptionRestored.get());
    }

    private static DeferredImageSupplier supplierReturning(final ImagePlus image) {
        return new DeferredImageSupplier(
                Collections.singletonList(new File("unused-0.tif")), "test") {
            @Override
            public ImagePlus openSeriesMaterialized(int seriesIndex) {
                return image;
            }
        };
    }

    private static ImageStack onePixelStack() {
        ImageStack stack = new ImageStack(1, 1);
        stack.addSlice(new ByteProcessor(1, 1));
        return stack;
    }

    private static final class CallerResult {
        final boolean interruptRestored;

        CallerResult(boolean interruptRestored) {
            this.interruptRestored = interruptRestored;
        }
    }

    private static final class BlockingPrefetch {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch openerInterrupted = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final TrackingImagePlus image;
        final DeferredImageSupplier supplier;

        BlockingPrefetch(TrackingImagePlus lateImage) {
            this.image = lateImage;
            this.supplier = new DeferredImageSupplier(
                    Collections.singletonList(new File("unused-0.tif")), "test") {
                @Override
                public ImagePlus openSeriesMaterialized(int seriesIndex) {
                    entered.countDown();
                    boolean released = false;
                    while (!released) {
                        try {
                            release.await();
                            released = true;
                        } catch (InterruptedException ignoredCancellation) {
                            openerInterrupted.countDown();
                        }
                    }
                    return image;
                }
            };
        }
    }

    private static class TrackingImagePlus extends ImagePlus {
        volatile int closeCalls;
        volatile int flushCalls;

        TrackingImagePlus(String title) {
            super(title, onePixelStack());
        }

        @Override
        public void close() {
            closeCalls++;
            super.close();
        }

        @Override
        public void flush() {
            flushCalls++;
            super.flush();
        }
    }

    private static final class CleanupFailingImagePlus extends TrackingImagePlus {
        private final Throwable closeFailure;

        CleanupFailingImagePlus(String title, Throwable closeFailure) {
            super(title);
            this.closeFailure = closeFailure;
        }

        @Override
        public void close() {
            closeCalls++;
            if (closeFailure instanceof Error) {
                throw (Error) closeFailure;
            }
            throw (RuntimeException) closeFailure;
        }
    }
}
