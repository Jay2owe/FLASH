package flash.pipeline.io;

import flash.pipeline.testutil.TestWait;
import ij.ImagePlus;
import ij.ImageStack;
import ij.process.ByteProcessor;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

/**
 * Tests for {@link BoundedImageLoader} edge cases.
 */
public class BoundedImageLoaderTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void start_noOpsOnEmptyIndexList() throws InterruptedException {
        // Empty schedule should not crash or create any threads
        BoundedImageLoader loader = new BoundedImageLoader(
                null, // supplier not needed — no indices to load
                Collections.<Integer>emptyList(),
                2);
        loader.start();

        // take() should return null immediately (all producers done)
        assertNull(loader.take());
    }

    @Test
    public void totalToLoad_returnsZeroForEmptySchedule() {
        BoundedImageLoader loader = new BoundedImageLoader(
                null,
                new ArrayList<Integer>(),
                2);

        assertEquals(0, loader.totalToLoad());
    }

    @Test
    public void take_returnsLoadedImagesThenRethrowsLoaderFailure() throws Exception {
        DeferredImageSupplier supplier = new DeferredImageSupplier(
                Arrays.asList(new File("unused-0.tif"), new File("unused-1.tif")), "test") {
            @Override
            public ImagePlus openSeriesMaterialized(int seriesIndex) throws Exception {
                if (seriesIndex == 0) {
                    ImageStack stack = new ImageStack(1, 1);
                    stack.addSlice(new ByteProcessor(1, 1));
                    return new ImagePlus("loaded", stack);
                }
                throw new Exception("boom");
            }
        };

        BoundedImageLoader loader = new BoundedImageLoader(
                supplier, Arrays.asList(0, 1), 2, 1);
        loader.start();

        BoundedImageLoader.IndexedImage first = loader.take();
        assertNotNull(first);
        assertEquals(0, first.index);

        try {
            loader.take();
            fail("Expected loader failure to be rethrown");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("Failed to load series 2"));
            assertNotNull(expected.getCause());
            assertEquals("boom", expected.getCause().getMessage());
        } finally {
            first.image.close();
            first.image.flush();
            loader.close();
        }
    }

    @Test
    public void takeDrainsQueuedImageThenSurfacesAssertionErrorWithinBound() throws Exception {
        assertErrorAfterDrain(new AssertionError("assertion failed"));
    }

    @Test
    public void takeDrainsQueuedImageThenSurfacesNoClassDefFoundErrorWithinBound() throws Exception {
        assertErrorAfterDrain(new NoClassDefFoundError("missing dependency"));
    }

    @Test
    public void vmFatalFailureTakesPrecedenceOverEarlierProducerFailure() throws Exception {
        final CountDownLatch firstFailureRecorded = new CountDownLatch(1);
        final Exception earlier = new Exception("earlier nonfatal failure");
        final ThreadDeath fatal = new ThreadDeath();
        DeferredImageSupplier supplier = new DeferredImageSupplier(
                Arrays.asList(new File("unused-0.tif"), new File("unused-1.tif"),
                        new File("unused-2.tif")), "test") {
            @Override
            public ImagePlus openSeriesMaterialized(int seriesIndex) throws Exception {
                if (seriesIndex == 0) throw earlier;
                if (seriesIndex == 1) {
                    TestWait.awaitLatch("earlier producer failure to be recorded",
                            firstFailureRecorded, 2000L);
                    throw fatal;
                }
                // The producer can only reach index 2 after it has caught and
                // recorded the index-0 exception.
                firstFailureRecorded.countDown();
                return singlePixel("queued-before-fatal");
            }
        };
        final BoundedImageLoader loader = new BoundedImageLoader(
                supplier, Arrays.asList(0, 1, 2), 2, 2);
        ExecutorService consumer = Executors.newSingleThreadExecutor();
        BoundedImageLoader.IndexedImage queued = null;
        try {
            loader.start();
            queued = loader.take();
            assertNotNull(queued);
            assertEquals(2, queued.index);

            Future<BoundedImageLoader.IndexedImage> terminal = consumer.submit(
                    new Callable<BoundedImageLoader.IndexedImage>() {
                @Override
                public BoundedImageLoader.IndexedImage call() throws Exception {
                    return loader.take();
                }
            });
            try {
                TestWait.get("fatal producer failure to reach the consumer", terminal, 2000L);
                fail("Expected VM-fatal producer failure");
            } catch (ExecutionException expected) {
                assertSame(fatal, expected.getCause());
            }

            Throwable[] suppressed = fatal.getSuppressed();
            assertEquals(1, suppressed.length);
            assertTrue(suppressed[0] instanceof IllegalStateException);
            assertSame(earlier, suppressed[0].getCause());
        } finally {
            close(queued);
            loader.close();
            TestWait.shutdown("fatal precedence consumer", consumer, 2000L);
        }
    }

    @Test
    public void closeIsIdempotentAndClosesQueuedAndInFlightImages() throws Exception {
        final CountDownLatch bothOpened = new CountDownLatch(2);
        final TrackingImagePlus[] opened = new TrackingImagePlus[2];
        final AtomicReference<Thread> producerThread = new AtomicReference<Thread>();
        DeferredImageSupplier supplier = new DeferredImageSupplier(
                Arrays.asList(new File("unused-0.tif"), new File("unused-1.tif")), "test") {
            @Override
            public ImagePlus openSeriesMaterialized(int seriesIndex) {
                producerThread.set(Thread.currentThread());
                TrackingImagePlus image = new TrackingImagePlus("owned-" + seriesIndex);
                opened[seriesIndex] = image;
                bothOpened.countDown();
                return image;
            }
        };

        BoundedImageLoader loader = new BoundedImageLoader(
                supplier, Arrays.asList(0, 1), 1, 1);
        loader.start();
        TestWait.awaitLatch("both loader images to materialize", bothOpened, 2000L);

        loader.close();
        loader.close();

        assertNotNull(producerThread.get());
        assertFalse("producer thread should terminate before close returns",
                producerThread.get().isAlive());
        assertNull(loader.take());
        for (TrackingImagePlus image : opened) {
            assertNotNull(image);
            assertTrue("owned image should be closed", image.closeCalls > 0);
            assertTrue("owned image should be flushed", image.flushCalls > 0);
        }
    }

    @Test
    public void repeatedCancellationWaitsForPhysicalProducerThreadExit() throws Exception {
        for (int iteration = 0; iteration < 200; iteration++) {
            final CountDownLatch producerEntered = new CountDownLatch(1);
            final CountDownLatch blockUntilCancelled = new CountDownLatch(1);
            final AtomicReference<Thread> producerThread = new AtomicReference<Thread>();
            DeferredImageSupplier supplier = new DeferredImageSupplier(
                    Collections.singletonList(new File("unused.tif")), "test") {
                @Override
                public ImagePlus openSeriesMaterialized(int seriesIndex) throws Exception {
                    producerThread.set(Thread.currentThread());
                    producerEntered.countDown();
                    blockUntilCancelled.await();
                    return singlePixel("unexpected");
                }
            };
            BoundedImageLoader loader = new BoundedImageLoader(
                    supplier, Collections.singletonList(Integer.valueOf(0)), 1, 1);
            try {
                loader.start();
                TestWait.awaitLatch("cancellation producer " + iteration,
                        producerEntered, 2000L);
                loader.close();
                Thread producer = producerThread.get();
                assertNotNull("iteration " + iteration + " did not create a producer",
                        producer);
                assertFalse("iteration " + iteration
                                + " returned from close before its producer thread exited",
                        producer.isAlive());
            } finally {
                loader.close();
            }
        }
    }

    @Test
    public void closeTruthfullyReportsInterruptIgnoringProducerThenConverges()
            throws Exception {
        final CountDownLatch producerEntered = new CountDownLatch(1);
        final CountDownLatch cancellationObserved = new CountDownLatch(1);
        final CountDownLatch releaseProducer = new CountDownLatch(1);
        final AtomicReference<Thread> producerThread = new AtomicReference<Thread>();
        final TrackingImagePlus lateResult = new TrackingImagePlus("late-result");
        DeferredImageSupplier supplier = new DeferredImageSupplier(
                Collections.singletonList(new File("unused.tif")), "test") {
            @Override
            public ImagePlus openSeriesMaterialized(int seriesIndex) {
                producerThread.set(Thread.currentThread());
                producerEntered.countDown();
                boolean released = false;
                while (!released) {
                    try {
                        releaseProducer.await();
                        released = true;
                    } catch (InterruptedException ignoredCancellation) {
                        cancellationObserved.countDown();
                        // Simulate an in-process library call that cannot be
                        // forcibly terminated and ignores interruption.
                    }
                }
                return lateResult;
            }
        };
        BoundedImageLoader loader = new BoundedImageLoader(
                supplier, Collections.singletonList(Integer.valueOf(0)),
                1, 1, false, null, 100L);
        try {
            loader.start();
            TestWait.awaitLatch("interrupt-ignoring producer",
                    producerEntered, 2000L);
            Thread producer = producerThread.get();
            assertNotNull(producer);
            assertTrue("abandonable producer must be daemon", producer.isDaemon());

            Thread.currentThread().interrupt();
            try {
                loader.close();
                fail("Expected explicit incomplete-cleanup failure");
            } catch (BoundedImageLoader.CleanupIncompleteException expected) {
                assertEquals(1, expected.getLiveProducerCount());
                assertEquals("Image loader cleanup is incomplete: 1 producer thread(s) have not exited",
                        expected.getMessage());
            } finally {
                assertTrue("close must restore caller interruption",
                        Thread.currentThread().isInterrupted());
                Thread.interrupted();
            }

            TestWait.awaitLatch("producer cancellation interrupt",
                    cancellationObserved, 2000L);
            assertTrue("producer must still be physically alive at deadline",
                    producer.isAlive());
            assertTrue(producer.isDaemon());
            assertFalse("live producer must not be reported done",
                    loader.allProducersDoneForTests());
            assertEquals(BoundedImageLoader.ShutdownState.INCOMPLETE,
                    loader.getShutdownState());
            assertEquals(0, lateResult.closeCalls);
            assertEquals(0, lateResult.flushCalls);

            releaseProducer.countDown();
            TestWait.await("late producer physical exit", 2000L,
                    new TestWait.Condition() {
                @Override
                public boolean isMet() {
                    Thread worker = producerThread.get();
                    return worker != null && !worker.isAlive();
                }
            });
            TestWait.await("late result exact-once cleanup", 2000L,
                    new TestWait.Condition() {
                @Override
                public boolean isMet() {
                    return lateResult.closeCalls == 1
                            && lateResult.flushCalls == 1;
                }
            });

            assertTrue(loader.allProducersDoneForTests());
            assertEquals(BoundedImageLoader.ShutdownState.COMPLETE,
                    loader.getShutdownState());
            loader.close();
            loader.close();
            assertEquals(1, lateResult.closeCalls);
            assertEquals(1, lateResult.flushCalls);
        } finally {
            Thread.interrupted();
            releaseProducer.countDown();
            Thread producer = producerThread.get();
            if (producer != null) {
                producer.join(2000L);
            }
            try {
                loader.close();
            } catch (BoundedImageLoader.CleanupIncompleteException ignored) {
                // The bounded failure is the contract under test. A daemon
                // worker cannot hold the test JVM if an assertion released it late.
            }
        }
    }

    @Test
    public void blockedTakeReportsIncompleteCleanupThenConvergesAfterPhysicalExit()
            throws Exception {
        final CountDownLatch producerEntered = new CountDownLatch(1);
        final CountDownLatch cancellationObserved = new CountDownLatch(1);
        final CountDownLatch releaseProducer = new CountDownLatch(1);
        final CountDownLatch consumerEntered = new CountDownLatch(1);
        final AtomicReference<Thread> producerThread = new AtomicReference<Thread>();
        final TrackingImagePlus lateResult = new TrackingImagePlus("late-take-result");
        DeferredImageSupplier supplier = new DeferredImageSupplier(
                Collections.singletonList(new File("unused.tif")), "test") {
            @Override
            public ImagePlus openSeriesMaterialized(int seriesIndex) {
                producerThread.set(Thread.currentThread());
                producerEntered.countDown();
                boolean released = false;
                while (!released) {
                    try {
                        releaseProducer.await();
                        released = true;
                    } catch (InterruptedException ignoredCancellation) {
                        cancellationObserved.countDown();
                    }
                }
                return lateResult;
            }
        };
        final BoundedImageLoader loader = new BoundedImageLoader(
                supplier, Collections.singletonList(Integer.valueOf(0)),
                1, 1, false, null, 100L);
        ExecutorService operations = Executors.newFixedThreadPool(2);
        try {
            loader.start();
            TestWait.awaitLatch("take-timeout producer", producerEntered, 2000L);
            Future<BoundedImageLoader.IndexedImage> blockedTake = operations.submit(
                    new Callable<BoundedImageLoader.IndexedImage>() {
                @Override
                public BoundedImageLoader.IndexedImage call() throws Exception {
                    consumerEntered.countDown();
                    return loader.take();
                }
            });
            TestWait.awaitLatch("blocked take to enter", consumerEntered, 2000L);
            assertFalse("take must initially wait for a result", blockedTake.isDone());

            Future<Void> boundedClose = operations.submit(new Callable<Void>() {
                @Override
                public Void call() {
                    loader.close();
                    return null;
                }
            });
            TestWait.awaitLatch("take-timeout cancellation", cancellationObserved, 2000L);
            try {
                TestWait.get("bounded incomplete close", boundedClose, 2000L);
                fail("Expected close to report incomplete cleanup");
            } catch (ExecutionException expected) {
                assertTrue(expected.getCause()
                        instanceof BoundedImageLoader.CleanupIncompleteException);
            }

            try {
                TestWait.get("blocked take terminal state", blockedTake, 2000L);
                fail("Expected blocked take to report incomplete cleanup");
            } catch (ExecutionException expected) {
                assertTrue(expected.getCause()
                        instanceof BoundedImageLoader.CleanupIncompleteException);
                assertEquals(1, ((BoundedImageLoader.CleanupIncompleteException)
                        expected.getCause()).getLiveProducerCount());
            }
            assertEquals(BoundedImageLoader.ShutdownState.INCOMPLETE,
                    loader.getShutdownState());
            assertEquals(0, lateResult.closeCalls);
            assertEquals(0, lateResult.flushCalls);

            releaseProducer.countDown();
            TestWait.await("take-timeout producer physical exit", 2000L,
                    new TestWait.Condition() {
                @Override
                public boolean isMet() {
                    Thread producer = producerThread.get();
                    return producer != null && !producer.isAlive();
                }
            });
            TestWait.await("take-timeout late result cleanup", 2000L,
                    new TestWait.Condition() {
                @Override
                public boolean isMet() {
                    return lateResult.closeCalls == 1 && lateResult.flushCalls == 1;
                }
            });

            assertNull("take must converge after physical producer exit", loader.take());
            assertEquals(BoundedImageLoader.ShutdownState.COMPLETE,
                    loader.getShutdownState());
            assertEquals(1, lateResult.closeCalls);
            assertEquals(1, lateResult.flushCalls);
        } finally {
            releaseProducer.countDown();
            Thread producer = producerThread.get();
            if (producer != null) producer.join(2000L);
            try {
                loader.close();
            } catch (BoundedImageLoader.CleanupIncompleteException ignored) {
                // A failed assertion may reach cleanup before the daemon exits.
            }
            TestWait.shutdown("blocked take operations", operations, 2000L);
        }
    }

    @Test
    public void incompleteShutdownKeepsCallerOwnedImageAndFatalFailurePrecedence()
            throws Exception {
        final CountDownLatch hangingProducerEntered = new CountDownLatch(1);
        final CountDownLatch cancellationObserved = new CountDownLatch(1);
        final CountDownLatch releaseProducer = new CountDownLatch(1);
        final AtomicReference<Thread> fatalProducerThread = new AtomicReference<Thread>();
        final AtomicReference<Thread> hangingProducerThread = new AtomicReference<Thread>();
        final ThreadDeath fatal = new ThreadDeath();
        final TrackingImagePlus callerOwned = new TrackingImagePlus("caller-owned-before-fatal");
        final TrackingImagePlus lateResult = new TrackingImagePlus("late-after-fatal");
        DeferredImageSupplier supplier = new DeferredImageSupplier(
                Arrays.asList(new File("unused-0.tif"), new File("unused-1.tif"),
                        new File("unused-2.tif")), "test") {
            @Override
            public ImagePlus openSeriesMaterialized(int seriesIndex) {
                if (seriesIndex == 0) return callerOwned;
                if (seriesIndex == 1) {
                    fatalProducerThread.set(Thread.currentThread());
                    throw fatal;
                }
                hangingProducerThread.set(Thread.currentThread());
                hangingProducerEntered.countDown();
                boolean released = false;
                while (!released) {
                    try {
                        releaseProducer.await();
                        released = true;
                    } catch (InterruptedException ignoredCancellation) {
                        cancellationObserved.countDown();
                    }
                }
                return lateResult;
            }
        };
        final BoundedImageLoader loader = new BoundedImageLoader(
                supplier, Arrays.asList(0, 1, 2), 1, 2,
                false, null, 100L);
        ExecutorService operations = Executors.newFixedThreadPool(2);
        BoundedImageLoader.IndexedImage transferred = null;
        try {
            loader.start();
            transferred = loader.take();
            assertNotNull(transferred);
            assertSame(callerOwned, transferred.image);
            TestWait.awaitLatch("fatal-precedence hanging producer",
                    hangingProducerEntered, 2000L);
            TestWait.await("fatal producer to record failure and exit", 2000L,
                    new TestWait.Condition() {
                @Override
                public boolean isMet() {
                    Thread producer = fatalProducerThread.get();
                    return producer != null && !producer.isAlive();
                }
            });

            Future<BoundedImageLoader.IndexedImage> terminalTake = operations.submit(
                    new Callable<BoundedImageLoader.IndexedImage>() {
                @Override
                public BoundedImageLoader.IndexedImage call() throws Exception {
                    return loader.take();
                }
            });
            Future<Void> boundedClose = operations.submit(new Callable<Void>() {
                @Override
                public Void call() {
                    loader.close();
                    return null;
                }
            });
            TestWait.awaitLatch("fatal-precedence cancellation",
                    cancellationObserved, 2000L);
            try {
                TestWait.get("fatal-precedence close", boundedClose, 2000L);
                fail("Expected incomplete close");
            } catch (ExecutionException expected) {
                assertTrue(expected.getCause()
                        instanceof BoundedImageLoader.CleanupIncompleteException);
            }
            try {
                TestWait.get("fatal-precedence take", terminalTake, 2000L);
                fail("Expected the recorded fatal producer failure");
            } catch (ExecutionException expected) {
                assertSame(fatal, expected.getCause());
            }

            assertEquals("consumer-owned image must not be reclaimed by close",
                    0, callerOwned.closeCalls);
            assertEquals(0, callerOwned.flushCalls);
            close(transferred);
            transferred = null;
            assertEquals(1, callerOwned.closeCalls);
            assertEquals(1, callerOwned.flushCalls);
        } finally {
            close(transferred);
            releaseProducer.countDown();
            Thread producer = hangingProducerThread.get();
            if (producer != null) producer.join(2000L);
            try {
                loader.close();
            } catch (BoundedImageLoader.CleanupIncompleteException ignored) {
                // A failed assertion may reach cleanup before the daemon exits.
            }
            TestWait.shutdown("fatal-precedence operations", operations, 2000L);
        }
        assertEquals(1, lateResult.closeCalls);
        assertEquals(1, lateResult.flushCalls);
    }

    @Test
    public void takePropagatesConsumerInterruption() throws Exception {
        final CountDownLatch producerEntered = new CountDownLatch(1);
        final CountDownLatch releaseProducer = new CountDownLatch(1);
        final CountDownLatch consumerEntered = new CountDownLatch(1);
        final AtomicReference<Thread> consumerThread = new AtomicReference<Thread>();
        DeferredImageSupplier supplier = new DeferredImageSupplier(
                Collections.singletonList(new File("unused.tif")), "test") {
            @Override
            public ImagePlus openSeriesMaterialized(int seriesIndex) throws Exception {
                producerEntered.countDown();
                releaseProducer.await();
                return singlePixel("released-after-consumer-interrupt");
            }
        };
        final BoundedImageLoader loader = new BoundedImageLoader(
                supplier, Collections.singletonList(Integer.valueOf(0)), 1, 1);
        ExecutorService consumer = Executors.newSingleThreadExecutor();
        try {
            loader.start();
            TestWait.awaitLatch("interrupt-test producer", producerEntered, 2000L);
            Future<BoundedImageLoader.IndexedImage> blockedTake = consumer.submit(
                    new Callable<BoundedImageLoader.IndexedImage>() {
                @Override
                public BoundedImageLoader.IndexedImage call() throws Exception {
                    consumerThread.set(Thread.currentThread());
                    consumerEntered.countDown();
                    return loader.take();
                }
            });
            TestWait.awaitLatch("interrupt-test consumer", consumerEntered, 2000L);
            consumerThread.get().interrupt();
            try {
                TestWait.get("interrupted take", blockedTake, 2000L);
                fail("Expected take to propagate InterruptedException");
            } catch (ExecutionException expected) {
                assertTrue(expected.getCause() instanceof InterruptedException);
            }
        } finally {
            releaseProducer.countDown();
            loader.close();
            TestWait.shutdown("interrupted take consumer", consumer, 2000L);
        }
    }

    @Test
    public void closePromotesFatalQueueCleanupFailureAndStillDrainsEveryImage() throws Exception {
        final RuntimeException earlier = new RuntimeException("earlier close failure");
        final ThreadDeath fatal = new ThreadDeath();
        final CleanupFailingImagePlus first =
                new CleanupFailingImagePlus("nonfatal-cleanup", earlier);
        final CleanupFailingImagePlus second =
                new CleanupFailingImagePlus("fatal-cleanup", fatal);
        final CountDownLatch bothOpened = new CountDownLatch(2);
        final AtomicReference<Thread> producerThread = new AtomicReference<Thread>();
        DeferredImageSupplier supplier = new DeferredImageSupplier(
                Arrays.asList(new File("unused-0.tif"), new File("unused-1.tif")), "test") {
            @Override
            public ImagePlus openSeriesMaterialized(int seriesIndex) {
                producerThread.set(Thread.currentThread());
                bothOpened.countDown();
                return seriesIndex == 0 ? first : second;
            }
        };
        BoundedImageLoader loader = new BoundedImageLoader(
                supplier, Arrays.asList(0, 1), 2, 1);
        loader.start();
        TestWait.awaitLatch("both cleanup-failure images to materialize", bothOpened, 2000L);
        TestWait.await("cleanup-failure producer to finish", 2000L,
                new TestWait.Condition() {
            @Override
            public boolean isMet() {
                Thread thread = producerThread.get();
                return thread != null && !thread.isAlive();
            }
        });

        try {
            loader.close();
            fail("Expected fatal queue-cleanup failure");
        } catch (ThreadDeath expected) {
            assertSame(fatal, expected);
        }

        assertEquals(1, fatal.getSuppressed().length);
        assertSame(earlier, fatal.getSuppressed()[0]);
        assertTrue("first image flush should still run", first.flushCalls > 0);
        assertTrue("second image flush should still run", second.flushCalls > 0);
        assertNull(loader.take());
        loader.close();
    }

    @Test
    public void deferredPrefetchPreservesNullAndErrorContracts() throws Exception {
        DeferredImageSupplier nullSupplier = oneSeriesSupplier(null, null);
        nullSupplier.startPrefetch(0, 1);
        try {
            assertNull(nullSupplier.getOrLoadMaterialized(0));
        } finally {
            nullSupplier.shutdownPrefetch();
        }

        AssertionError failure = new AssertionError("prefetch assertion");
        DeferredImageSupplier errorSupplier = oneSeriesSupplier(null, failure);
        errorSupplier.startPrefetch(0, 1);
        try {
            errorSupplier.getOrLoadMaterialized(0);
            fail("Expected the original prefetch Error");
        } catch (AssertionError expected) {
            assertSame(failure, expected);
        } finally {
            errorSupplier.shutdownPrefetch();
        }
    }

    @Test
    public void shutdownPrefetchClosesCompletedUnconsumedResultExactlyOnce()
            throws Exception {
        TrackingImagePlus owned = new TrackingImagePlus("completed-unconsumed");
        DeferredImageSupplier supplier = oneSeriesSupplier(owned, null);
        supplier.startPrefetch(0, 1);
        assertTrue("prefetch did not complete",
                supplier.awaitPrefetchCompletionForTests(0, 2000L));

        supplier.shutdownPrefetch();
        supplier.shutdownPrefetch();

        assertEquals("completed unconsumed result must close exactly once",
                1, owned.closeCalls);
        assertEquals("completed unconsumed result must flush exactly once",
                1, owned.flushCalls);
    }

    @Test
    public void shutdownPrefetchNeverClosesConsumedCallerOwnedResult()
            throws Exception {
        TrackingImagePlus callerOwned = new TrackingImagePlus("caller-owned");
        DeferredImageSupplier supplier = oneSeriesSupplier(callerOwned, null);
        supplier.startPrefetch(0, 1);
        assertTrue("prefetch did not complete",
                supplier.awaitPrefetchCompletionForTests(0, 2000L));

        ImagePlus consumed = supplier.getOrLoadMaterialized(0);
        assertSame(callerOwned, consumed);
        supplier.shutdownPrefetch();
        supplier.shutdownPrefetch();

        assertEquals(0, callerOwned.closeCalls);
        assertEquals(0, callerOwned.flushCalls);
        callerOwned.close();
        callerOwned.flush();
        assertEquals(1, callerOwned.closeCalls);
        assertEquals(1, callerOwned.flushCalls);
    }

    @Test
    public void shutdownPrefetchCancelsJoinsAndDisposesLateCompletion()
            throws Exception {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch interrupted = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicReference<Thread> worker = new AtomicReference<Thread>();
        final TrackingImagePlus late = new TrackingImagePlus("late-result");
        final DeferredImageSupplier supplier = new DeferredImageSupplier(
                Collections.singletonList(new File("unused-0.tif")), "test") {
            @Override
            public ImagePlus openSeriesMaterialized(int seriesIndex) {
                worker.set(Thread.currentThread());
                entered.countDown();
                boolean released = false;
                while (!released) {
                    try {
                        release.await();
                        released = true;
                    } catch (InterruptedException expectedCancellation) {
                        interrupted.countDown();
                        // Deliberately ignore cancellation and return late.
                    }
                }
                return late;
            }
        };
        ExecutorService closer = Executors.newSingleThreadExecutor();
        try {
            supplier.startPrefetch(0, 1);
            TestWait.awaitLatch("late prefetch to enter", entered, 2000L);
            Future<Void> shutdown = closer.submit(new Callable<Void>() {
                @Override
                public Void call() {
                    supplier.shutdownPrefetch();
                    return null;
                }
            });
            TestWait.awaitLatch("late prefetch cancellation", interrupted, 2000L);
            assertFalse("shutdown must join physical callable exit", shutdown.isDone());
            release.countDown();
            TestWait.get("late prefetch shutdown", shutdown, 2000L);

            assertNotNull(worker.get());
            assertFalse("prefetch worker must exit before shutdown returns",
                    worker.get().isAlive());
            assertEquals(1, late.closeCalls);
            assertEquals(1, late.flushCalls);
            supplier.shutdownPrefetch();
            assertEquals(1, late.closeCalls);
            assertEquals(1, late.flushCalls);
        } finally {
            release.countDown();
            supplier.shutdownPrefetch();
            TestWait.shutdown("late prefetch closer", closer, 2000L);
        }
    }

    @Test
    public void shutdownPrefetchIgnoresCancellationAndNullResults()
            throws Exception {
        DeferredImageSupplier cancelled = new DeferredImageSupplier(
                Collections.singletonList(new File("unused-0.tif")), "test") {
            @Override
            public ImagePlus openSeriesMaterialized(int seriesIndex) {
                throw new CancellationException("cancelled prefetch");
            }
        };
        cancelled.startPrefetch(0, 1);
        assertTrue(cancelled.awaitPrefetchCompletionForTests(0, 2000L));
        cancelled.shutdownPrefetch();

        DeferredImageSupplier nullResult = oneSeriesSupplier(null, null);
        nullResult.startPrefetch(0, 1);
        assertTrue(nullResult.awaitPrefetchCompletionForTests(0, 2000L));
        nullResult.shutdownPrefetch();
        nullResult.shutdownPrefetch();
    }

    @Test
    public void shutdownPrefetchSurfacesExecutionCauseAndOriginalError()
            throws Exception {
        final Exception checked = new Exception("completed prefetch failure");
        DeferredImageSupplier failed = new DeferredImageSupplier(
                Collections.singletonList(new File("unused-0.tif")), "test") {
            @Override
            public ImagePlus openSeriesMaterialized(int seriesIndex) throws Exception {
                throw checked;
            }
        };
        failed.startPrefetch(0, 1);
        assertTrue(failed.awaitPrefetchCompletionForTests(0, 2000L));
        try {
            failed.shutdownPrefetch();
            fail("Expected completed prefetch failure");
        } catch (IllegalStateException expected) {
            assertSame(checked, expected.getCause());
        }
        failed.shutdownPrefetch();

        AssertionError error = new AssertionError("completed prefetch error");
        DeferredImageSupplier errored = oneSeriesSupplier(null, error);
        errored.startPrefetch(0, 1);
        assertTrue(errored.awaitPrefetchCompletionForTests(0, 2000L));
        try {
            errored.shutdownPrefetch();
            fail("Expected original prefetch Error");
        } catch (AssertionError expected) {
            assertSame(error, expected);
        }
        errored.shutdownPrefetch();
    }

    @Test
    public void shutdownPrefetchPromotesVmFatalCloseAndFlushesEveryResult()
            throws Exception {
        RuntimeException ordinary = new RuntimeException("ordinary close failure");
        ThreadDeath fatal = new ThreadDeath();
        final CleanupFailingImagePlus first =
                new CleanupFailingImagePlus("ordinary-prefetch-close", ordinary);
        final CleanupFailingImagePlus second =
                new CleanupFailingImagePlus("fatal-prefetch-close", fatal);
        DeferredImageSupplier supplier = new DeferredImageSupplier(
                Arrays.asList(new File("unused-0.tif"), new File("unused-1.tif")), "test") {
            @Override
            public ImagePlus openSeriesMaterialized(int seriesIndex) {
                return seriesIndex == 0 ? first : second;
            }
        };
        supplier.startPrefetch(0, 2);
        assertTrue(supplier.awaitPrefetchCompletionForTests(0, 2000L));
        assertTrue(supplier.awaitPrefetchCompletionForTests(1, 2000L));

        try {
            supplier.shutdownPrefetch();
            fail("Expected VM-fatal prefetch close failure");
        } catch (ThreadDeath expected) {
            assertSame(fatal, expected);
        }
        assertEquals(1, fatal.getSuppressed().length);
        assertSame(ordinary, fatal.getSuppressed()[0]);
        assertEquals(1, first.closeCalls);
        assertEquals(1, second.closeCalls);
        assertEquals(1, first.flushCalls);
        assertEquals(1, second.flushCalls);
        supplier.shutdownPrefetch();
    }

    @Test
    public void shutdownPrefetchRestoresCallerInterruptionAfterCleanup()
            throws Exception {
        TrackingImagePlus owned = new TrackingImagePlus("interrupt-restored");
        DeferredImageSupplier supplier = oneSeriesSupplier(owned, null);
        supplier.startPrefetch(0, 1);
        assertTrue(supplier.awaitPrefetchCompletionForTests(0, 2000L));

        Thread.currentThread().interrupt();
        try {
            supplier.shutdownPrefetch();
            assertTrue("shutdown must restore the caller interrupt",
                    Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
        assertEquals(1, owned.closeCalls);
        assertEquals(1, owned.flushCalls);
    }

    @Test
    public void start_ignoresExistingCacheWhenCacheDisabled() throws Exception {
        File project = temp.newFolder("cache-disabled");
        TifCache.saveToCache(project.getAbsolutePath(), singlePixel("cached"), 0);

        BoundedImageLoader loader = new BoundedImageLoader(
                freshSupplier(false),
                Collections.singletonList(Integer.valueOf(0)),
                1,
                1,
                false,
                project.getAbsolutePath());
        loader.start();

        BoundedImageLoader.IndexedImage loaded = loader.take();
        try {
            assertNotNull(loaded);
            assertEquals("fresh", loaded.image.getTitle());
            assertNull(loader.take());
        } finally {
            close(loaded);
        }
    }

    @Test
    public void start_ignoresSharedCacheForTiffFolderSupplier() throws Exception {
        File project = temp.newFolder("cache-tiff-folder");
        TifCache.saveToCache(project.getAbsolutePath(), singlePixel("cached"), 0);

        BoundedImageLoader loader = new BoundedImageLoader(
                freshSupplier(true),
                Collections.singletonList(Integer.valueOf(0)),
                1,
                1,
                true,
                project.getAbsolutePath());
        loader.start();

        BoundedImageLoader.IndexedImage loaded = loader.take();
        try {
            assertNotNull(loaded);
            assertEquals("fresh", loaded.image.getTitle());
            assertNull(loader.take());
        } finally {
            close(loaded);
        }
    }

    private static DeferredImageSupplier freshSupplier(final boolean tiffFolderMode) {
        return new DeferredImageSupplier(
                Collections.singletonList(new File("unused-0.tif")), "test") {
            @Override
            public boolean isTiffFolderMode() {
                return tiffFolderMode;
            }

            @Override
            public ImagePlus openSeriesMaterialized(int seriesIndex) {
                return singlePixel("fresh");
            }
        };
    }

    private static DeferredImageSupplier oneSeriesSupplier(
            final ImagePlus result, final Error failure) {
        return new DeferredImageSupplier(
                Collections.singletonList(new File("unused-0.tif")), "test") {
            @Override
            public ImagePlus openSeriesMaterialized(int seriesIndex) {
                if (failure != null) throw failure;
                return result;
            }
        };
    }

    private static ImagePlus singlePixel(String title) {
        return new ImagePlus(title, singlePixelStack());
    }

    private static ImageStack singlePixelStack() {
        ImageStack stack = new ImageStack(1, 1);
        stack.addSlice(new ByteProcessor(1, 1));
        return stack;
    }

    private static void close(BoundedImageLoader.IndexedImage indexed) {
        if (indexed == null || indexed.image == null) return;
        indexed.image.close();
        indexed.image.flush();
    }

    private static void assertErrorAfterDrain(final Error failure) throws Exception {
        DeferredImageSupplier supplier = new DeferredImageSupplier(
                Arrays.asList(new File("unused-0.tif"), new File("unused-1.tif")), "test") {
            @Override
            public ImagePlus openSeriesMaterialized(int seriesIndex) {
                if (seriesIndex == 0) return singlePixel("queued-before-error");
                throw failure;
            }
        };
        final BoundedImageLoader loader = new BoundedImageLoader(
                supplier, Arrays.asList(0, 1), 2, 1);
        ExecutorService consumer = Executors.newSingleThreadExecutor();
        BoundedImageLoader.IndexedImage first = null;
        try {
            loader.start();
            first = loader.take();
            assertNotNull("queued image must be drained before the failure", first);
            assertEquals(0, first.index);

            Future<BoundedImageLoader.IndexedImage> terminal = consumer.submit(
                    new Callable<BoundedImageLoader.IndexedImage>() {
                @Override
                public BoundedImageLoader.IndexedImage call() throws Exception {
                    return loader.take();
                }
            });
            try {
                TestWait.get("loader failure after producer Error", terminal, 2000L);
                fail("Expected producer Error to be surfaced");
            } catch (ExecutionException expected) {
                assertSame(failure, expected.getCause());
            }
        } finally {
            close(first);
            loader.close();
            TestWait.shutdown("bounded loader error consumer", consumer, 2000L);
        }
    }

    private static final class TrackingImagePlus extends ImagePlus {
        volatile int closeCalls;
        volatile int flushCalls;

        TrackingImagePlus(String title) {
            super(title, singlePixelStack());
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

    private static final class CleanupFailingImagePlus extends ImagePlus {
        private final Throwable closeFailure;
        volatile int closeCalls;
        volatile int flushCalls;

        CleanupFailingImagePlus(String title, Throwable closeFailure) {
            super(title, singlePixelStack());
            this.closeFailure = closeFailure;
        }

        @Override
        public void close() {
            closeCalls++;
            if (closeFailure instanceof Error) throw (Error) closeFailure;
            throw (RuntimeException) closeFailure;
        }

        @Override
        public void flush() {
            flushCalls++;
            super.flush();
        }
    }
}
