package flash.pipeline.ui.variations;

import flash.pipeline.testutil.EdtUncaughtExceptionCapture;
import ij.ImagePlus;
import ij.process.ByteProcessor;

import org.junit.Test;

import javax.swing.SwingUtilities;
import java.awt.EventQueue;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class VariationExecutorTest {

    private static final class TestVmError extends VirtualMachineError {
        private TestVmError(String message) {
            super(message);
        }
    }

    private static final class NoopStrategy implements VariationStrategy {

        private final int resultCount;

        private NoopStrategy(int resultCount) {
            this.resultCount = Math.max(0, resultCount);
        }

        @Override
        public void dispatch(ParameterSweep sweep,
                             Consumer<VariationResult> publisher,
                             BooleanSupplier cancelCheck) {
            for (int i = 0; i < resultCount; i++) {
                if (cancelCheck != null && cancelCheck.getAsBoolean()) {
                    return;
                }
                ParameterCombo combo = comboForIndex(sweep, i);
                ImagePlus label = new ImagePlus("noop-" + i,
                        new ByteProcessor(1, 1));
                publisher.accept(VariationResult.success(combo, label,
                        i, 0L, null));
            }
        }

        private static ParameterCombo comboForIndex(ParameterSweep sweep,
                                                    int index) {
            if (sweep != null) {
                List<ParameterCombo> combos = sweep.combos();
                if (!combos.isEmpty()) {
                    return combos.get(index % combos.size());
                }
            }
            return ParameterCombo.builder().build();
        }
    }

    @Test
    public void noopStrategyPublishesFiveResultsOnEdtAndCompletes() throws Exception {
        final CountDownLatch delivered = new CountDownLatch(5);
        final List<Integer> objectCounts = Collections.synchronizedList(new ArrayList<Integer>());
        final AtomicBoolean allCallbacksOnEdt = new AtomicBoolean(true);

        VariationExecutor worker = new VariationExecutor(singleCellSweep(),
                new NoopStrategy(5),
                null,
                (result, index) -> {
                    allCallbacksOnEdt.compareAndSet(true, SwingUtilities.isEventDispatchThread());
                    objectCounts.add(Integer.valueOf(result.getNObjects()));
                    delivered.countDown();
                },
                null);

        worker.execute();

        assertTrue(delivered.await(5, TimeUnit.SECONDS));
        worker.get(5, TimeUnit.SECONDS);
        EventQueue.invokeAndWait(new Runnable() {
            @Override
            public void run() {
            }
        });

        assertEquals(5, objectCounts.size());
        assertEquals(Integer.valueOf(0), objectCounts.get(0));
        assertEquals(Integer.valueOf(4), objectCounts.get(4));
        assertTrue(allCallbacksOnEdt.get());
    }

    @Test
    public void resultCallbackFailureDoesNotBlockLaterResults() throws Exception {
        final CountDownLatch laterDelivered = new CountDownLatch(2);
        final List<Integer> indexes = Collections.synchronizedList(new ArrayList<Integer>());
        final AtomicBoolean threwOnce = new AtomicBoolean(false);

        VariationExecutor worker = new VariationExecutor(singleCellSweep(),
                new NoopStrategy(3),
                null,
                (result, index) -> {
                    indexes.add(index);
                    if (index.intValue() == 0 && threwOnce.compareAndSet(false, true)) {
                        throw new RuntimeException("simulated UI callback failure");
                    }
                    laterDelivered.countDown();
                },
                null);

        worker.execute();

        assertTrue(laterDelivered.await(5, TimeUnit.SECONDS));
        worker.get(5, TimeUnit.SECONDS);
        EventQueue.invokeAndWait(new Runnable() {
            @Override
            public void run() {
            }
        });

        assertEquals(3, indexes.size());
        assertEquals(Integer.valueOf(0), indexes.get(0));
        assertEquals(Integer.valueOf(1), indexes.get(1));
        assertEquals(Integer.valueOf(2), indexes.get(2));
    }

    @Test
    public void cancellationIsHonouredBetweenPublishes() throws Exception {
        final AtomicInteger published = new AtomicInteger();
        final AtomicInteger delivered = new AtomicInteger();
        final CountDownLatch firstDelivered = new CountDownLatch(1);
        final CountDownLatch cancellationRequested = new CountDownLatch(1);
        final AtomicReference<VariationExecutor> workerRef =
                new AtomicReference<VariationExecutor>();

        VariationStrategy cancellable = new VariationStrategy() {
            @Override
            public void dispatch(ParameterSweep sweep,
                                 Consumer<VariationResult> publisher,
                                 BooleanSupplier cancelCheck) throws Exception {
                for (int i = 0; i < 5; i++) {
                    if (cancelCheck.getAsBoolean()) {
                        return;
                    }
                    published.incrementAndGet();
                    publisher.accept(fakeResult(i));
                    if (i == 0) {
                        assertTrue("cancel callback did not run",
                                cancellationRequested.await(5L, TimeUnit.SECONDS));
                    }
                }
            }
        };

        VariationExecutor worker = new VariationExecutor(singleCellSweep(),
                cancellable,
                null,
                (result, index) -> {
                    if (delivered.incrementAndGet() == 1) {
                        firstDelivered.countDown();
                        workerRef.get().cancel(false);
                        cancellationRequested.countDown();
                    }
                },
                null);
        workerRef.set(worker);

        worker.execute();

        assertTrue(firstDelivered.await(5, TimeUnit.SECONDS));
        try {
            worker.get(5, TimeUnit.SECONDS);
            fail("Expected worker.get() to report cancellation.");
        } catch (CancellationException expected) {
            // Expected once the EDT callback cancels the SwingWorker.
        }
        EventQueue.invokeAndWait(new Runnable() {
            @Override
            public void run() {
            }
        });

        assertEquals(1, published.get());
        assertEquals(1, delivered.get());
    }

    @Test
    public void queuedStatusFatalPausesLatePublishAndEscapesOnce()
            throws Exception {
        VariationCleanupCoordinator.resetForTest();
        final AtomicInteger statusCallbacks = new AtomicInteger();
        final AtomicInteger resultCallbacks = new AtomicInteger();
        final AtomicInteger firstDisposals = new AtomicInteger();
        final AtomicInteger lateDisposals = new AtomicInteger();
        final VariationResult first = ownedResult(0, firstDisposals);
        final VariationResult late = ownedResult(1, lateDisposals);
        final ThreadDeath fatal = new ThreadDeath();
        final CountDownLatch edtBlocked = new CountDownLatch(1);
        final CountDownLatch releaseEdt = new CountDownLatch(1);
        final CountDownLatch firstPublished = new CountDownLatch(1);
        final CountDownLatch latePublished = new CountDownLatch(1);
        VariationStrategy strategy = new VariationStrategy() {
            @Override public void dispatch(ParameterSweep sweep,
                                           Consumer<VariationResult> publisher,
                                           BooleanSupplier cancelCheck) {
                publisher.accept(first);
                firstPublished.countDown();
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5L);
                while (!cancelCheck.getAsBoolean()
                        && System.nanoTime() < deadline) {
                    Thread.yield();
                }
                if (!cancelCheck.getAsBoolean()) {
                    throw new AssertionError("status fatal did not cancel worker");
                }
                // Ignore cancellation once: fatalPause, not the ordinary
                // cancellation disposer, must retain this producer claim.
                publisher.accept(late);
                latePublished.countDown();
            }
        };
        VariationExecutor worker = new VariationExecutor(singleCellSweep(), strategy,
                null, (result, index) -> resultCallbacks.incrementAndGet(),
                text -> {
                    statusCallbacks.incrementAndGet();
                    throw fatal;
                });

        try (EdtUncaughtExceptionCapture capture =
                     EdtUncaughtExceptionCapture.install()) {
            SwingUtilities.invokeLater(new Runnable() {
                @Override public void run() {
                    edtBlocked.countDown();
                    awaitUninterruptibly(releaseEdt);
                }
            });
            assertTrue(edtBlocked.await(5L, TimeUnit.SECONDS));
            worker.execute();
            assertTrue(firstPublished.await(5L, TimeUnit.SECONDS));
            releaseEdt.countDown();

            waitForEdtFailure(capture);
            assertTrue(latePublished.await(5L, TimeUnit.SECONDS));
            try {
                worker.get(5L, TimeUnit.SECONDS);
                fail("Expected status fatal to cancel the worker.");
            } catch (CancellationException expected) {
                // The status fatal already escaped the EDT.
            }
            EventQueue.invokeAndWait(new Runnable() {
                @Override public void run() {
                }
            });

            assertTrue(capture.failure() == fatal);
            assertEquals(1, capture.count());
            assertTrue(worker.isCancelled());
            assertEquals(1, statusCallbacks.get());
            assertEquals(0, resultCallbacks.get());
            assertEquals(2, VariationCleanupCoordinator.pendingCountForTest());
            assertEquals(0, firstDisposals.get());
            assertEquals(0, lateDisposals.get());

            TimeUnit.MILLISECONDS.sleep(400L);
            EventQueue.invokeAndWait(new Runnable() {
                @Override public void run() {
                }
            });
            assertEquals("queued completion/warning status must not surface again",
                    1, statusCallbacks.get());
            assertEquals(1, capture.count());

            assertNull(VariationCleanupCoordinator.drainNowForTest());
            assertEquals(1, firstDisposals.get());
            assertEquals(1, lateDisposals.get());
            assertEquals(0, VariationCleanupCoordinator.pendingCountForTest());
        } finally {
            releaseEdt.countDown();
            VariationCleanupCoordinator.resetForTest();
        }
    }

    @Test
    public void directEdtStatusFatalRetainsTrackedClaimAndEscapesOnce()
            throws Exception {
        VariationCleanupCoordinator.resetForTest();
        final AtomicInteger statusCallbacks = new AtomicInteger();
        final AtomicInteger resultCallbacks = new AtomicInteger();
        final AtomicInteger disposals = new AtomicInteger();
        final AtomicBoolean callbackOnEdt = new AtomicBoolean();
        final VariationResult tracked = ownedResult(0, disposals);
        final TestVmError fatal = new TestVmError("direct status fatal");
        final VariationExecutor worker = new VariationExecutor(singleCellSweep(),
                new NoopStrategy(0), null,
                (result, index) -> resultCallbacks.incrementAndGet(),
                text -> {
                    callbackOnEdt.set(SwingUtilities.isEventDispatchThread());
                    statusCallbacks.incrementAndGet();
                    throw fatal;
                });
        worker.stagePendingResultForTest(tracked);

        try (EdtUncaughtExceptionCapture capture =
                     EdtUncaughtExceptionCapture.install()) {
            SwingUtilities.invokeLater(new Runnable() {
                @Override public void run() {
                    try {
                        worker.doInBackground();
                    } catch (Exception checkedFailure) {
                        throw new AssertionError(checkedFailure);
                    }
                }
            });
            waitForEdtFailure(capture);
            EventQueue.invokeAndWait(new Runnable() {
                @Override public void run() {
                }
            });

            assertTrue(capture.failure() == fatal);
            assertEquals(1, capture.count());
            assertTrue(callbackOnEdt.get());
            assertTrue(worker.isCancelled());
            assertEquals(1, statusCallbacks.get());
            assertEquals(0, resultCallbacks.get());
            assertEquals(1, VariationCleanupCoordinator.pendingCountForTest());
            assertEquals(0, disposals.get());

            TimeUnit.MILLISECONDS.sleep(400L);
            EventQueue.invokeAndWait(new Runnable() {
                @Override public void run() {
                }
            });
            assertEquals(1, capture.count());
            assertEquals(1, statusCallbacks.get());
            assertNull(VariationCleanupCoordinator.drainNowForTest());
            assertEquals(1, disposals.get());
            assertEquals(0, VariationCleanupCoordinator.pendingCountForTest());
        } finally {
            VariationCleanupCoordinator.resetForTest();
        }
    }

    @Test
    public void claimedStatusFatalSurfacesEarlierStrategyFatalCanonically()
            throws Exception {
        VariationCleanupCoordinator.resetForTest();
        final AtomicInteger statusCallbacks = new AtomicInteger();
        final CountDownLatch statusClaimed = new CountDownLatch(1);
        final ThreadDeath strategyFatal = new ThreadDeath();
        final TestVmError callbackFatal =
                new TestVmError("status callback fatal after strategy fatal");
        final AtomicReference<VariationExecutor> workerRef =
                new AtomicReference<VariationExecutor>();
        VariationStrategy strategy = new VariationStrategy() {
            @Override public void dispatch(ParameterSweep sweep,
                                           Consumer<VariationResult> publisher,
                                           BooleanSupplier cancelCheck)
                    throws Exception {
                assertTrue("status callback did not claim delivery",
                        statusClaimed.await(5L, TimeUnit.SECONDS));
                throw strategyFatal;
            }
        };
        VariationExecutor worker = new VariationExecutor(singleCellSweep(), strategy,
                null, null, text -> {
                    statusCallbacks.incrementAndGet();
                    statusClaimed.countDown();
                    try {
                        workerRef.get().get(5L, TimeUnit.SECONDS);
                        throw new AssertionError("Expected strategy fatal future.");
                    } catch (ExecutionException expected) {
                        if (expected.getCause() != strategyFatal) {
                            throw new AssertionError(
                                    "Unexpected strategy failure.", expected);
                        }
                    } catch (Exception unexpected) {
                        throw new AssertionError(unexpected);
                    }
                    throw callbackFatal;
                });
        workerRef.set(worker);

        try (EdtUncaughtExceptionCapture capture =
                     EdtUncaughtExceptionCapture.install()) {
            worker.execute();
            waitForEdtFailure(capture);
            EventQueue.invokeAndWait(new Runnable() {
                @Override public void run() {
                }
            });

            assertTrue("first recorded fatal must remain canonical",
                    capture.failure() == strategyFatal);
            assertEquals(1, capture.count());
            assertEquals(1, statusCallbacks.get());
            boolean callbackSuppressed = false;
            for (Throwable suppressed : strategyFatal.getSuppressed()) {
                callbackSuppressed |= suppressed == callbackFatal;
            }
            assertTrue("callback fatal must be suppressed on the canonical fatal",
                    callbackSuppressed);

            TimeUnit.MILLISECONDS.sleep(400L);
            EventQueue.invokeAndWait(new Runnable() {
                @Override public void run() {
                }
            });
            assertEquals("canonical fatal must surface exactly once",
                    1, capture.count());
        } finally {
            VariationCleanupCoordinator.resetForTest();
        }
    }

    @Test
    public void persistentRetentionFailureDoesNotResurfaceStatusFatal()
            throws Exception {
        VariationCleanupCoordinator.resetForTest();
        final AtomicInteger statusCallbacks = new AtomicInteger();
        final AtomicInteger resultCallbacks = new AtomicInteger();
        final AtomicInteger retentionAttempts = new AtomicInteger();
        final AtomicInteger disposals = new AtomicInteger();
        final VariationResult tracked = ownedResult(0, disposals);
        final ThreadDeath fatal = new ThreadDeath();
        final RuntimeException retentionFailure =
                new RuntimeException("persistent retention failure");
        final VariationExecutor worker = new VariationExecutor(singleCellSweep(),
                new NoopStrategy(0), null,
                (result, index) -> resultCallbacks.incrementAndGet(),
                text -> {
                    statusCallbacks.incrementAndGet();
                    throw fatal;
                });
        worker.stagePendingResultForTest(tracked);
        worker.setFatalRetentionHookForTest(
                new VariationExecutor.FatalRetentionHook() {
                    @Override public void beforeRegister(VariationResult result) {
                        retentionAttempts.incrementAndGet();
                        throw retentionFailure;
                    }
                });

        try (EdtUncaughtExceptionCapture capture =
                     EdtUncaughtExceptionCapture.install()) {
            SwingUtilities.invokeLater(new Runnable() {
                @Override public void run() {
                    try {
                        worker.doInBackground();
                    } catch (Exception checkedFailure) {
                        throw new AssertionError(checkedFailure);
                    }
                }
            });
            SwingUtilities.invokeLater(new Runnable() {
                @Override public void run() {
                    worker.process(Collections.singletonList(tracked));
                }
            });
            SwingUtilities.invokeLater(new Runnable() {
                @Override public void run() {
                    worker.done();
                }
            });

            waitForEdtFailure(capture);
            EventQueue.invokeAndWait(new Runnable() {
                @Override public void run() {
                }
            });
            TimeUnit.MILLISECONDS.sleep(400L);
            EventQueue.invokeAndWait(new Runnable() {
                @Override public void run() {
                }
            });

            assertTrue(capture.failure() == fatal);
            assertEquals("queued process/done retries must not resurface fatal",
                    1, capture.count());
            assertEquals(1, statusCallbacks.get());
            assertEquals(0, resultCallbacks.get());
            assertTrue("every retry must attempt retained registration",
                    retentionAttempts.get() >= 3);
            assertEquals(0, VariationCleanupCoordinator.pendingCountForTest());
            assertEquals(0, disposals.get());

            worker.setFatalRetentionHookForTest(null);
            EventQueue.invokeAndWait(new Runnable() {
                @Override public void run() {
                    worker.done();
                }
            });
            assertEquals(1, capture.count());
            assertEquals(1, VariationCleanupCoordinator.pendingCountForTest());
            assertNull(VariationCleanupCoordinator.drainNowForTest());
            assertEquals(1, disposals.get());
            assertEquals(0, VariationCleanupCoordinator.pendingCountForTest());
        } finally {
            worker.setFatalRetentionHookForTest(null);
            VariationCleanupCoordinator.resetForTest();
        }
    }

    @Test
    public void cancelledPublisherDisposesAcceptedResultExactlyOnce() {
        final AtomicInteger disposals = new AtomicInteger();
        VariationResult result = ownedResult(0, disposals);
        VariationExecutor worker = executor(null);
        worker.cancel(false);

        worker.publishResult(result);
        result.dispose();

        assertEquals(1, disposals.get());
        assertFalse(result.ownsImagesForTest());
    }

    @Test
    public void fatalRecordedAfterCancellationClaimPausesBeforeDisposal()
            throws Exception {
        VariationCleanupCoordinator.resetForTest();
        final AtomicInteger disposals = new AtomicInteger();
        final VariationResult result = ownedResult(0, disposals);
        final VariationExecutor worker = executor(null);
        final ThreadDeath fatal = new ThreadDeath();
        final CountDownLatch claimRegistered = new CountDownLatch(1);
        final CountDownLatch releaseClaim = new CountDownLatch(1);
        final AtomicReference<Throwable> publisherFailure =
                new AtomicReference<Throwable>();
        worker.cancel(false);
        worker.setProducerDisposalClaimedHookForTest(new Runnable() {
            @Override public void run() {
                claimRegistered.countDown();
                awaitUninterruptibly(releaseClaim);
            }
        });
        Thread publisher = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    worker.publishResult(result);
                } catch (Throwable failure) {
                    publisherFailure.set(failure);
                }
            }
        }, "variation-cancelled-publisher");

        try {
            publisher.start();
            assertTrue(claimRegistered.await(5L, TimeUnit.SECONDS));

            // The claim is absent from pendingResults but remains uncommitted.
            // Fatal recording must seize it without waiting for the publisher.
            worker.recordFatalPauseForTest(fatal);
            releaseClaim.countDown();
            publisher.join(5000L);

            assertFalse(publisher.isAlive());
            assertNull(publisherFailure.get());
            assertEquals(0, disposals.get());
            assertEquals(1, VariationCleanupCoordinator.pendingCountForTest());
            assertNull(VariationCleanupCoordinator.drainNowForTest());
            assertEquals(1, disposals.get());
            assertEquals(0, VariationCleanupCoordinator.pendingCountForTest());
        } finally {
            releaseClaim.countDown();
            publisher.join(5000L);
            VariationCleanupCoordinator.resetForTest();
        }
    }

    @Test
    public void fatalAfterCleanupCommitRetainsActiveResultForDrain() {
        VariationCleanupCoordinator.resetForTest();
        final AtomicInteger disposals = new AtomicInteger();
        final VariationResult result = ownedResult(0, disposals);
        final VariationExecutor worker = executor(null);
        final ThreadDeath fatal = new ThreadDeath();
        worker.cancel(false);
        worker.setCommittedDisposalHookForTest(new Runnable() {
            @Override public void run() {
                throw fatal;
            }
        });

        try {
            try {
                worker.publishResult(result);
                fail("Expected committed cleanup fatal.");
            } catch (ThreadDeath expected) {
                assertTrue(expected == fatal);
            }

            assertEquals(0, disposals.get());
            assertEquals(1, VariationCleanupCoordinator.pendingCountForTest());
            assertNull(VariationCleanupCoordinator.drainNowForTest());
            assertEquals(1, disposals.get());
            assertEquals(0, VariationCleanupCoordinator.pendingCountForTest());
        } finally {
            VariationCleanupCoordinator.resetForTest();
        }
    }

    @Test
    public void failedFatalRegistrationAttemptsLaterClaimsAndRetriesFirst() {
        VariationCleanupCoordinator.resetForTest();
        final AtomicInteger firstDisposals = new AtomicInteger();
        final AtomicInteger secondDisposals = new AtomicInteger();
        final AtomicInteger firstAttempts = new AtomicInteger();
        final AtomicInteger secondAttempts = new AtomicInteger();
        final VariationResult first = ownedResult(0, firstDisposals);
        final VariationResult second = ownedResult(1, secondDisposals);
        final ThreadDeath triggerFatal = new ThreadDeath();
        final TestVmError registrationFatal =
                new TestVmError("fatal registration seam");
        final VariationExecutor worker = executor(null);
        worker.stagePendingResultForTest(first);
        worker.stagePendingResultForTest(second);
        worker.setFatalRetentionHookForTest(
                new VariationExecutor.FatalRetentionHook() {
                    @Override public void beforeRegister(VariationResult result) {
                        if (result == first
                                && firstAttempts.getAndIncrement() == 0) {
                            throw registrationFatal;
                        }
                        if (result == second) {
                            secondAttempts.incrementAndGet();
                        }
                    }
                });

        try {
            try {
                worker.recordFatalPauseForTest(triggerFatal);
                fail("Expected fatal registration failure.");
            } catch (ThreadDeath expected) {
                assertTrue("original fatal must retain precedence",
                        expected == triggerFatal);
            }

            assertEquals(1, firstAttempts.get());
            assertEquals(1, secondAttempts.get());
            assertEquals("later claim must register despite the first failure",
                    1, VariationCleanupCoordinator.pendingCountForTest());
            assertEquals(0, firstDisposals.get());
            assertEquals(0, secondDisposals.get());

            worker.setFatalRetentionHookForTest(null);
            worker.done();
            assertEquals(2, VariationCleanupCoordinator.pendingCountForTest());
            assertNull(VariationCleanupCoordinator.drainNowForTest());
            assertEquals(1, firstDisposals.get());
            assertEquals(1, secondDisposals.get());
            assertEquals(0, VariationCleanupCoordinator.pendingCountForTest());
        } finally {
            worker.setFatalRetentionHookForTest(null);
            VariationCleanupCoordinator.resetForTest();
        }
    }

    @Test
    public void duplicatePublishIdentityDeliversOnlyOnce() throws Exception {
        final AtomicInteger callbacks = new AtomicInteger();
        final AtomicInteger disposals = new AtomicInteger();
        final CountDownLatch delivered = new CountDownLatch(1);
        final CountDownLatch edtBlocked = new CountDownLatch(1);
        final CountDownLatch releaseEdt = new CountDownLatch(1);
        final VariationResult result = ownedResult(0, disposals);
        VariationExecutor worker = executor((deliveredResult, index) -> {
            callbacks.incrementAndGet();
            delivered.countDown();
        });

        try {
            SwingUtilities.invokeLater(new Runnable() {
                @Override public void run() {
                    edtBlocked.countDown();
                    awaitUninterruptibly(releaseEdt);
                }
            });
            assertTrue(edtBlocked.await(5L, TimeUnit.SECONDS));
            worker.publishResult(result);
            worker.publishResult(result);
            releaseEdt.countDown();

            assertTrue(delivered.await(5L, TimeUnit.SECONDS));
            EventQueue.invokeAndWait(new Runnable() {
                @Override public void run() {
                }
            });
            assertEquals(1, callbacks.get());
            assertEquals(0, disposals.get());
            result.releaseTransferredImages();
            assertEquals(1, disposals.get());
        } finally {
            releaseEdt.countDown();
        }
    }

    @Test
    public void untrackedProcessChunkNeverReachesCallback() {
        final AtomicInteger callbacks = new AtomicInteger();
        final AtomicInteger disposals = new AtomicInteger();
        VariationResult untracked = ownedResult(0, disposals);
        VariationExecutor worker = executor((result, index) ->
                callbacks.incrementAndGet());

        worker.process(Collections.singletonList(untracked));

        assertEquals(0, callbacks.get());
        assertEquals(0, disposals.get());
        assertTrue(untracked.ownsImagesForTest());
        untracked.dispose();
        assertEquals(1, disposals.get());
    }

    @Test
    public void fatalRecordedAfterRejectedCallbackPausesBeforeDisposal()
            throws Exception {
        VariationCleanupCoordinator.resetForTest();
        final AtomicInteger disposals = new AtomicInteger();
        final VariationResult result = ownedResult(0, disposals);
        final ThreadDeath fatal = new ThreadDeath();
        final VariationExecutor worker = executor((delivered, index) -> {
            throw new RuntimeException("callback rejected result");
        });
        worker.setProducerDisposalClaimedHookForTest(new Runnable() {
            @Override public void run() {
                worker.recordFatalPauseForTest(fatal);
            }
        });
        worker.stagePendingResultForTest(result);

        try {
            EventQueue.invokeAndWait(new Runnable() {
                @Override public void run() {
                    worker.process(Collections.singletonList(result));
                }
            });

            assertEquals(0, disposals.get());
            assertEquals(1, VariationCleanupCoordinator.pendingCountForTest());
            assertNull(VariationCleanupCoordinator.drainNowForTest());
            assertEquals(1, disposals.get());
            assertEquals(0, VariationCleanupCoordinator.pendingCountForTest());
        } finally {
            VariationCleanupCoordinator.resetForTest();
        }
    }

    @Test
    public void committedDisposerDoesNotBlockConcurrentFatalRecording()
            throws Exception {
        VariationCleanupCoordinator.resetForTest();
        final AtomicInteger disposals = new AtomicInteger();
        final CountDownLatch disposalEntered = new CountDownLatch(1);
        final CountDownLatch releaseDisposal = new CountDownLatch(1);
        final CountDownLatch fatalRecorded = new CountDownLatch(1);
        final AtomicReference<Throwable> publisherFailure =
                new AtomicReference<Throwable>();
        final VariationResult result = ownedResult(0,
                new VariationResult.ImageDisposer() {
                    @Override public void dispose(ImagePlus image) {
                        disposalEntered.countDown();
                        awaitUninterruptibly(releaseDisposal);
                        disposals.incrementAndGet();
                    }
                });
        final VariationExecutor worker = executor(null);
        final ThreadDeath fatal = new ThreadDeath();
        worker.cancel(false);
        Thread publisher = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    worker.publishResult(result);
                } catch (Throwable failure) {
                    publisherFailure.set(failure);
                }
            }
        }, "variation-blocked-disposer");
        Thread fatalRecorder = new Thread(new Runnable() {
            @Override public void run() {
                worker.recordFatalPauseForTest(fatal);
                fatalRecorded.countDown();
            }
        }, "variation-fatal-recorder");

        try {
            publisher.start();
            assertTrue(disposalEntered.await(5L, TimeUnit.SECONDS));
            fatalRecorder.start();

            assertTrue("fatal recording blocked behind arbitrary disposal",
                    fatalRecorded.await(5L, TimeUnit.SECONDS));
            assertEquals(0, disposals.get());
            releaseDisposal.countDown();
            publisher.join(5000L);
            fatalRecorder.join(5000L);

            assertFalse(publisher.isAlive());
            assertFalse(fatalRecorder.isAlive());
            assertNull(publisherFailure.get());
            assertEquals(1, disposals.get());
            assertEquals(0, VariationCleanupCoordinator.pendingCountForTest());
        } finally {
            releaseDisposal.countDown();
            publisher.join(5000L);
            fatalRecorder.join(5000L);
            VariationCleanupCoordinator.resetForTest();
        }
    }

    @Test
    public void surfacedCanonicalSuppressesLateCommittedCleanupFatal()
            throws Exception {
        VariationCleanupCoordinator.resetForTest();
        final AtomicInteger disposals = new AtomicInteger();
        final CountDownLatch cleanupCommitted = new CountDownLatch(1);
        final CountDownLatch releaseCleanup = new CountDownLatch(1);
        final AtomicReference<Throwable> publisherFailure =
                new AtomicReference<Throwable>();
        final VariationResult result = ownedResult(0, disposals);
        final VariationExecutor worker = executor(null);
        final ThreadDeath canonical = new ThreadDeath();
        final TestVmError cleanupFatal =
                new TestVmError("late committed cleanup fatal");
        worker.cancel(false);
        worker.setCommittedDisposalHookForTest(new Runnable() {
            @Override public void run() {
                cleanupCommitted.countDown();
                awaitUninterruptibly(releaseCleanup);
                throw cleanupFatal;
            }
        });
        Thread publisher = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    worker.publishResult(result);
                } catch (Throwable failure) {
                    publisherFailure.set(failure);
                }
            }
        }, "variation-late-cleanup-fatal");

        try {
            publisher.start();
            assertTrue("cleanup did not reach its committed boundary",
                    cleanupCommitted.await(5L, TimeUnit.SECONDS));

            // This test seam represents an already-surfaced canonical fatal.
            // The committed cleanup is no longer tracked when it is recorded.
            worker.recordFatalPauseForTest(canonical);
            releaseCleanup.countDown();
            publisher.join(5000L);

            assertFalse(publisher.isAlive());
            assertNull("an already-surfaced canonical must not escape again",
                    publisherFailure.get());
            assertEquals(0, disposals.get());
            assertEquals(1, VariationCleanupCoordinator.pendingCountForTest());
            boolean cleanupFatalSuppressed = false;
            for (Throwable suppressed : canonical.getSuppressed()) {
                cleanupFatalSuppressed |= suppressed == cleanupFatal;
            }
            assertTrue("late cleanup fatal must remain diagnostic",
                    cleanupFatalSuppressed);

            assertNull(VariationCleanupCoordinator.drainNowForTest());
            assertEquals(1, disposals.get());
            assertEquals(0, VariationCleanupCoordinator.pendingCountForTest());
        } finally {
            releaseCleanup.countDown();
            publisher.join(5000L);
            VariationCleanupCoordinator.resetForTest();
        }
    }

    @Test
    public void cleanupFatalPausesCancellationIgnoringLatePublish()
            throws Exception {
        VariationCleanupCoordinator.resetForTest();
        final AtomicInteger fatalDisposals = new AtomicInteger();
        final AtomicInteger lateDisposals = new AtomicInteger();
        final TestVmError fatal = new TestVmError("fatal disposer");
        final VariationResult first = ownedResult(0,
                new VariationResult.ImageDisposer() {
                    @Override public void dispose(ImagePlus image) {
                        if (fatalDisposals.incrementAndGet() == 1) {
                            throw fatal;
                        }
                    }
                });
        final VariationResult late = ownedResult(1, lateDisposals);
        final VariationExecutor worker = executor(null);
        final AtomicReference<Throwable> publisherFailure =
                new AtomicReference<Throwable>();
        worker.cancel(false);
        Thread publisher = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    worker.publishResult(first);
                } catch (Throwable failure) {
                    publisherFailure.set(failure);
                }
            }
        }, "variation-fatal-disposer");

        try {
            publisher.start();
            publisher.join(5000L);
            assertFalse(publisher.isAlive());
            assertTrue(publisherFailure.get() == fatal);

            // Simulate a producer that ignores cancellation once after cleanup failed.
            worker.publishResult(late);

            assertEquals(1, fatalDisposals.get());
            assertEquals(0, lateDisposals.get());
            assertEquals(2, VariationCleanupCoordinator.pendingCountForTest());
            assertNull(VariationCleanupCoordinator.drainNowForTest());
            assertEquals(2, fatalDisposals.get());
            assertEquals(1, lateDisposals.get());
            assertEquals(0, VariationCleanupCoordinator.pendingCountForTest());
        } finally {
            publisher.join(5000L);
            VariationCleanupCoordinator.resetForTest();
        }
    }

    @Test
    public void thrownCleanupFatalPausesClaimAndLatePublish() {
        VariationCleanupCoordinator.resetForTest();
        final AtomicInteger firstDisposals = new AtomicInteger();
        final AtomicInteger lateDisposals = new AtomicInteger();
        final VariationResult first = ownedResult(0, firstDisposals);
        final VariationResult late = ownedResult(1, lateDisposals);
        final VariationExecutor worker = executor(null);
        final ThreadDeath fatal = new ThreadDeath();
        worker.cancel(false);
        worker.setProducerDisposalClaimedHookForTest(new Runnable() {
            @Override public void run() {
                throw fatal;
            }
        });

        try {
            try {
                worker.publishResult(first);
                fail("Expected fatal cleanup entry failure.");
            } catch (ThreadDeath expected) {
                assertTrue(expected == fatal);
            }
            worker.publishResult(late);

            assertEquals(0, firstDisposals.get());
            assertEquals(0, lateDisposals.get());
            assertEquals(2, VariationCleanupCoordinator.pendingCountForTest());
            assertNull(VariationCleanupCoordinator.drainNowForTest());
            assertEquals(1, firstDisposals.get());
            assertEquals(1, lateDisposals.get());
            assertEquals(0, VariationCleanupCoordinator.pendingCountForTest());
        } finally {
            VariationCleanupCoordinator.resetForTest();
        }
    }

    @Test
    public void fatalRecordedAfterBulkClaimPausesCurrentAndRemainingResults()
            throws Exception {
        VariationCleanupCoordinator.resetForTest();
        final AtomicInteger firstDisposals = new AtomicInteger();
        final AtomicInteger secondDisposals = new AtomicInteger();
        final VariationResult first = ownedResult(0, firstDisposals);
        final VariationResult second = ownedResult(1, secondDisposals);
        final VariationExecutor worker = executor(null);
        final ThreadDeath fatal = new ThreadDeath();
        final CountDownLatch edtBlocked = new CountDownLatch(1);
        final CountDownLatch releaseEdt = new CountDownLatch(1);
        worker.setProducerDisposalClaimedHookForTest(new Runnable() {
            @Override public void run() {
                worker.recordFatalPauseForTest(fatal);
            }
        });

        try {
            SwingUtilities.invokeLater(new Runnable() {
                @Override public void run() {
                    edtBlocked.countDown();
                    awaitUninterruptibly(releaseEdt);
                }
            });
            assertTrue(edtBlocked.await(5L, TimeUnit.SECONDS));
            worker.publishResult(first);
            worker.publishResult(second);

            worker.done();

            assertEquals(0, firstDisposals.get());
            assertEquals(0, secondDisposals.get());
            assertEquals(2, VariationCleanupCoordinator.pendingCountForTest());
            releaseEdt.countDown();
            EventQueue.invokeAndWait(new Runnable() {
                @Override public void run() {
                }
            });
            assertNull(VariationCleanupCoordinator.drainNowForTest());
            assertEquals(1, firstDisposals.get());
            assertEquals(1, secondDisposals.get());
            assertEquals(0, VariationCleanupCoordinator.pendingCountForTest());
        } finally {
            releaseEdt.countDown();
            EventQueue.invokeAndWait(new Runnable() {
                @Override public void run() {
                }
            });
            VariationCleanupCoordinator.resetForTest();
        }
    }

    @Test
    public void failedHandlerDisposesResultAndLaterResultIsTransferred() {
        final AtomicInteger firstDisposals = new AtomicInteger();
        final AtomicInteger secondDisposals = new AtomicInteger();
        final AtomicInteger callbacks = new AtomicInteger();
        final VariationResult first = ownedResult(0, firstDisposals);
        final VariationResult second = ownedResult(1, secondDisposals);
        VariationExecutor worker = executor((result, index) -> {
            callbacks.incrementAndGet();
            if (index.intValue() == 0) {
                throw new RuntimeException("handler failed");
            }
        });
        worker.stagePendingResultForTest(first);
        worker.stagePendingResultForTest(second);

        worker.process(Arrays.asList(first, second));
        first.dispose();
        second.dispose();

        assertEquals(2, callbacks.get());
        assertEquals(1, firstDisposals.get());
        assertEquals(0, secondDisposals.get());
        assertFalse(first.ownsImagesForTest());
        assertFalse(second.ownsImagesForTest());
    }

    @Test
    public void returningHandlerCanRejectResultWithoutOwnershipResurrection() {
        final AtomicInteger disposals = new AtomicInteger();
        final VariationResult rejected = ownedResult(0, disposals);
        VariationExecutor worker = executor((result, index) -> result.dispose());
        worker.stagePendingResultForTest(rejected);

        worker.process(Collections.singletonList(rejected));
        rejected.dispose();

        assertEquals(1, disposals.get());
        assertFalse(rejected.ownsImagesForTest());
    }

    @Test
    public void throwingHandlerCannotReclaimResultItAlreadyTransferred() {
        final AtomicInteger disposals = new AtomicInteger();
        final VariationResult installed = ownedResult(0, disposals);
        VariationExecutor worker = executor((result, index) -> {
            result.transferOwnership();
            throw new RuntimeException("failure after install");
        });
        worker.stagePendingResultForTest(installed);

        worker.process(Collections.singletonList(installed));

        assertEquals(0, disposals.get());
        installed.releaseTransferredImages();
        assertEquals(1, disposals.get());
    }

    @Test
    public void fatalHandlerRetainsEveryUndeliveredResultWithoutDisposal() {
        VariationCleanupCoordinator.resetForTest();
        final AtomicInteger firstDisposals = new AtomicInteger();
        final AtomicInteger secondDisposals = new AtomicInteger();
        VariationResult first = ownedResult(0, firstDisposals);
        VariationResult second = ownedResult(1, secondDisposals);
        final ThreadDeath fatal = new ThreadDeath();
        VariationExecutor worker = executor((result, index) -> {
            throw fatal;
        });
        worker.stagePendingResultForTest(first);
        worker.stagePendingResultForTest(second);

        try {
            try {
                worker.process(Arrays.asList(first, second));
                fail("Expected fatal handler failure.");
            } catch (ThreadDeath expected) {
                assertTrue(expected == fatal);
            }

            assertEquals(0, firstDisposals.get());
            assertEquals(0, secondDisposals.get());
            assertEquals(2, VariationCleanupCoordinator.pendingCountForTest());

            assertNull(VariationCleanupCoordinator.drainNowForTest());
            assertEquals(1, firstDisposals.get());
            assertEquals(1, secondDisposals.get());
            assertEquals(0, VariationCleanupCoordinator.pendingCountForTest());
        } finally {
            VariationCleanupCoordinator.resetForTest();
        }
    }

    @Test
    public void callbackThreadDeathPausesConcurrentLatePublishAndEscapesOnce()
            throws Exception {
        assertCallbackFatalPausesConcurrentLatePublish(new ThreadDeath());
    }

    @Test
    public void callbackVmErrorPausesConcurrentLatePublishAndEscapesOnce()
            throws Exception {
        assertCallbackFatalPausesConcurrentLatePublish(
                new TestVmError("fatal callback"));
    }

    @Test
    public void callbackFatalPreservesConsumerTransferredOwnership() {
        VariationCleanupCoordinator.resetForTest();
        final AtomicInteger disposals = new AtomicInteger();
        final VariationResult installed = ownedResult(0, disposals);
        final ThreadDeath fatal = new ThreadDeath();
        VariationExecutor worker = executor((result, index) -> {
            result.transferOwnership();
            throw fatal;
        });
        worker.stagePendingResultForTest(installed);

        try {
            try {
                worker.process(Collections.singletonList(installed));
                fail("Expected fatal handler failure.");
            } catch (ThreadDeath expected) {
                assertTrue(expected == fatal);
            }

            assertEquals(0, VariationCleanupCoordinator.pendingCountForTest());
            assertEquals(0, disposals.get());
            installed.releaseTransferredImages();
            assertEquals(1, disposals.get());
        } finally {
            VariationCleanupCoordinator.resetForTest();
        }
    }

    @Test
    public void strategyThreadDeathMakesFutureExceptionalAndEscapesOnceOnEdt()
            throws Exception {
        assertStrategyFatalPausesQueuedResults(new ThreadDeath());
    }

    @Test
    public void strategyVmErrorMakesFutureExceptionalAndEscapesOnceOnEdt()
            throws Exception {
        assertStrategyFatalPausesQueuedResults(new TestVmError("fatal strategy"));
    }

    @Test
    public void strategyFatalDoesNotReclaimConsumerTransferredDuringCallback()
            throws Exception {
        VariationCleanupCoordinator.resetForTest();
        final AtomicInteger callbacks = new AtomicInteger();
        final AtomicInteger disposals = new AtomicInteger();
        final CountDownLatch consumerInstalled = new CountDownLatch(1);
        final CountDownLatch releaseCallback = new CountDownLatch(1);
        final ThreadDeath fatal = new ThreadDeath();
        final VariationResult result = ownedResult(0, disposals);
        VariationStrategy strategy = new VariationStrategy() {
            @Override public void dispatch(ParameterSweep sweep,
                                           Consumer<VariationResult> publisher,
                                           BooleanSupplier cancelCheck) throws Exception {
                publisher.accept(result);
                assertTrue("consumer callback did not start",
                        consumerInstalled.await(5L, TimeUnit.SECONDS));
                throw fatal;
            }
        };
        VariationExecutor worker = new VariationExecutor(singleCellSweep(), strategy,
                null, (delivered, index) -> {
                    callbacks.incrementAndGet();
                    delivered.transferOwnership();
                    consumerInstalled.countDown();
                    awaitUninterruptibly(releaseCallback);
                }, null);

        try (EdtUncaughtExceptionCapture capture =
                     EdtUncaughtExceptionCapture.install()) {
            worker.execute();
            assertTrue(consumerInstalled.await(5L, TimeUnit.SECONDS));
            assertFutureFailedWith(worker, fatal);
            assertEquals(0, VariationCleanupCoordinator.pendingCountForTest());
            assertEquals(0, disposals.get());

            releaseCallback.countDown();
            waitForEdtFailure(capture);

            assertTrue(capture.failure() == fatal);
            assertEquals(1, capture.count());
            assertEquals(1, callbacks.get());
            assertEquals(0, VariationCleanupCoordinator.pendingCountForTest());
            assertEquals(0, disposals.get());

            result.releaseTransferredImages();
            assertEquals(1, disposals.get());
        } finally {
            releaseCallback.countDown();
            VariationCleanupCoordinator.resetForTest();
        }
    }

    private static void assertStrategyFatalPausesQueuedResults(final Error fatal)
            throws Exception {
        VariationCleanupCoordinator.resetForTest();
        final AtomicInteger callbacks = new AtomicInteger();
        final AtomicInteger firstDisposals = new AtomicInteger();
        final AtomicInteger secondDisposals = new AtomicInteger();
        final List<String> statuses =
                Collections.synchronizedList(new ArrayList<String>());
        final VariationResult first = ownedResult(0, firstDisposals);
        final VariationResult second = ownedResult(1, secondDisposals);
        final CountDownLatch edtBlocked = new CountDownLatch(1);
        final CountDownLatch releaseEdt = new CountDownLatch(1);
        VariationStrategy strategy = new VariationStrategy() {
            @Override public void dispatch(ParameterSweep sweep,
                                           Consumer<VariationResult> publisher,
                                           BooleanSupplier cancelCheck) {
                publisher.accept(first);
                publisher.accept(second);
                throw fatal;
            }
        };
        VariationExecutor worker = new VariationExecutor(singleCellSweep(), strategy,
                null, (result, index) -> callbacks.incrementAndGet(), statuses::add);

        try (EdtUncaughtExceptionCapture capture =
                     EdtUncaughtExceptionCapture.install()) {
            SwingUtilities.invokeLater(new Runnable() {
                @Override public void run() {
                    edtBlocked.countDown();
                    awaitUninterruptibly(releaseEdt);
                }
            });
            assertTrue(edtBlocked.await(5L, TimeUnit.SECONDS));

            worker.execute();
            assertFutureFailedWith(worker, fatal);
            assertEquals(2, VariationCleanupCoordinator.pendingCountForTest());
            assertEquals(0, callbacks.get());
            assertEquals(0, firstDisposals.get());
            assertEquals(0, secondDisposals.get());

            releaseEdt.countDown();
            waitForEdtFailure(capture);
            assertTrue(capture.failure() == fatal);
            assertEquals(1, capture.count());
            assertEquals(0, callbacks.get());
            assertEquals(2, VariationCleanupCoordinator.pendingCountForTest());
            assertEquals(0, firstDisposals.get());
            assertEquals(0, secondDisposals.get());

            TimeUnit.MILLISECONDS.sleep(400L);
            EventQueue.invokeAndWait(new Runnable() {
                @Override public void run() {
                }
            });
            assertEquals("fatal-paused claims must not create an automatic retry storm",
                    1, capture.count());
            assertEquals(2, VariationCleanupCoordinator.pendingCountForTest());

            assertNull(VariationCleanupCoordinator.drainNowForTest());
            assertEquals(0, VariationCleanupCoordinator.pendingCountForTest());
            assertEquals(1, firstDisposals.get());
            assertEquals(1, secondDisposals.get());
            assertEquals(1, capture.count());
            assertFalse("fatal sweep must not publish a normal completion status",
                    statuses.contains("Parameter variations complete."));
        } finally {
            releaseEdt.countDown();
            VariationCleanupCoordinator.resetForTest();
        }
    }

    private static void assertCallbackFatalPausesConcurrentLatePublish(
            final Error fatal) throws Exception {
        VariationCleanupCoordinator.resetForTest();
        final AtomicInteger callbacks = new AtomicInteger();
        final AtomicInteger firstDisposals = new AtomicInteger();
        final AtomicInteger lateDisposals = new AtomicInteger();
        final List<String> statuses =
                Collections.synchronizedList(new ArrayList<String>());
        final VariationResult first = ownedResult(0, firstDisposals);
        final VariationResult late = ownedResult(1, lateDisposals);
        final CountDownLatch edtBlocked = new CountDownLatch(1);
        final CountDownLatch releaseEdt = new CountDownLatch(1);
        final CountDownLatch firstPublished = new CountDownLatch(1);
        final CountDownLatch latePublished = new CountDownLatch(1);
        VariationStrategy strategy = new VariationStrategy() {
            @Override public void dispatch(ParameterSweep sweep,
                                           Consumer<VariationResult> publisher,
                                           BooleanSupplier cancelCheck) {
                publisher.accept(first);
                firstPublished.countDown();
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5L);
                while (!cancelCheck.getAsBoolean()
                        && System.nanoTime() < deadline) {
                    Thread.yield();
                }
                if (!cancelCheck.getAsBoolean()) {
                    throw new AssertionError("callback fatal did not cancel worker");
                }
                // Deliberately ignore cancellation once. The fatal pause, rather than the
                // ordinary cancellation disposer, must retain this concurrent late result.
                publisher.accept(late);
                latePublished.countDown();
            }
        };
        VariationExecutor worker = new VariationExecutor(singleCellSweep(), strategy,
                null, (result, index) -> {
                    callbacks.incrementAndGet();
                    throw fatal;
                }, statuses::add);

        try (EdtUncaughtExceptionCapture capture =
                     EdtUncaughtExceptionCapture.install()) {
            SwingUtilities.invokeLater(new Runnable() {
                @Override public void run() {
                    edtBlocked.countDown();
                    awaitUninterruptibly(releaseEdt);
                }
            });
            assertTrue(edtBlocked.await(5L, TimeUnit.SECONDS));

            worker.execute();
            assertTrue(firstPublished.await(5L, TimeUnit.SECONDS));
            assertEquals(0, callbacks.get());
            releaseEdt.countDown();

            waitForEdtFailure(capture);
            assertTrue(latePublished.await(5L, TimeUnit.SECONDS));
            try {
                worker.get(5L, TimeUnit.SECONDS);
                fail("Expected callback fatal to cancel the worker.");
            } catch (CancellationException expected) {
                // The callback fatal already escaped the EDT; done() must not surface it again.
            }
            EventQueue.invokeAndWait(new Runnable() {
                @Override public void run() {
                }
            });

            assertTrue(capture.failure() == fatal);
            assertEquals(1, capture.count());
            assertEquals(1, callbacks.get());
            assertEquals(2, VariationCleanupCoordinator.pendingCountForTest());
            assertEquals(0, firstDisposals.get());
            assertEquals(0, lateDisposals.get());

            TimeUnit.MILLISECONDS.sleep(400L);
            EventQueue.invokeAndWait(new Runnable() {
                @Override public void run() {
                }
            });
            assertEquals("callback fatal must not create an automatic retry storm",
                    1, capture.count());
            assertEquals(1, callbacks.get());
            assertEquals(2, VariationCleanupCoordinator.pendingCountForTest());
            assertEquals(0, firstDisposals.get());
            assertEquals(0, lateDisposals.get());
            assertFalse("fatal callback must not publish a normal completion status",
                    statuses.contains("Parameter variations complete."));

            assertNull(VariationCleanupCoordinator.drainNowForTest());
            assertEquals(0, VariationCleanupCoordinator.pendingCountForTest());
            assertEquals(1, firstDisposals.get());
            assertEquals(1, lateDisposals.get());
            assertNull(VariationCleanupCoordinator.drainNowForTest());
            assertEquals(1, firstDisposals.get());
            assertEquals(1, lateDisposals.get());
            assertEquals(1, capture.count());
        } finally {
            releaseEdt.countDown();
            VariationCleanupCoordinator.resetForTest();
        }
    }

    private static void assertFutureFailedWith(VariationExecutor worker,
                                               Throwable fatal) throws Exception {
        try {
            worker.get(5L, TimeUnit.SECONDS);
            fail("Expected fatal strategy future failure.");
        } catch (ExecutionException expected) {
            assertTrue("future must preserve the original fatal as its cause",
                    expected.getCause() == fatal);
        }
    }

    private static void waitForEdtFailure(EdtUncaughtExceptionCapture capture)
            throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5L);
        while (capture.count() == 0 && System.nanoTime() < deadline) {
            TimeUnit.MILLISECONDS.sleep(10L);
        }
        assertTrue("fatal did not escape the EDT", capture.count() > 0);
    }

    private static void awaitUninterruptibly(CountDownLatch latch) {
        boolean interrupted = false;
        for (;;) {
            try {
                latch.await();
                break;
            } catch (InterruptedException ignored) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static VariationExecutor executor(
            java.util.function.BiConsumer<VariationResult, Integer> handler) {
        return new VariationExecutor(singleCellSweep(), new NoopStrategy(0),
                null, handler, null);
    }

    private static VariationResult ownedResult(int index,
                                               final AtomicInteger disposals) {
        return ownedResult(index, new VariationResult.ImageDisposer() {
            @Override public void dispose(ImagePlus image) {
                disposals.incrementAndGet();
            }
        });
    }

    private static VariationResult ownedResult(
            int index, VariationResult.ImageDisposer disposer) {
        return VariationResult.filterSuccess(fakeResult(index).combo(),
                new ImagePlus("owned-" + index, new ByteProcessor(1, 1)),
                0L, new int[256], 0.0d, 0.0d, disposer);
    }

    private static ParameterSweep singleCellSweep() {
        Map<ParameterId, ParameterValueList> values =
                new LinkedHashMap<ParameterId, ParameterValueList>();
        values.put(ParameterId.THRESHOLD, ParameterValueList.ofInts(1));
        return new ParameterSweep(ParameterSweep.Method.CLASSICAL,
                values, CropSpec.full(), "DAPI", "abc");
    }

    private static VariationResult fakeResult(int index) {
        ImagePlus label = new ImagePlus("fake-" + index, new ByteProcessor(1, 1));
        return VariationResult.success(ParameterCombo.builder()
                .put(ParameterId.THRESHOLD, Integer.valueOf(index))
                .build(), label, index, 0L, null);
    }
}
