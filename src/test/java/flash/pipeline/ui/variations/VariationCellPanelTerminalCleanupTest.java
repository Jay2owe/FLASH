package flash.pipeline.ui.variations;

import flash.pipeline.testutil.EdtUncaughtExceptionCapture;
import ij.ImagePlus;
import ij.process.ByteProcessor;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import javax.swing.SwingUtilities;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class VariationCellPanelTerminalCleanupTest {

    @Before
    public void resetCoordinatorBeforeTest() {
        VariationCleanupCoordinator.resetForTest();
    }

    @After
    public void resetCoordinatorAfterTest() {
        VariationCleanupCoordinator.resetForTest();
    }

    @Test
    public void offEdtDisposeWaitsAndQueuedResultCannotResurrectCell()
            throws Exception {
        final VariationCellPanel cell = cell();
        final TrackingImage lateImage = new TrackingImage("late-result");
        final VariationResult lateResult = VariationResult.success(
                ParameterCombo.builder().build(), lateImage, 1, 1L, null);
        final CountDownLatch edtBlocked = new CountDownLatch(1);
        final CountDownLatch releaseEdt = new CountDownLatch(1);
        final AtomicReference<Throwable> disposeFailure =
                new AtomicReference<Throwable>();
        final AtomicBoolean disposeInterrupted = new AtomicBoolean();

        SwingUtilities.invokeLater(new Runnable() {
            @Override public void run() {
                edtBlocked.countDown();
                await(releaseEdt);
            }
        });
        assertTrue(edtBlocked.await(5L, TimeUnit.SECONDS));

        Thread disposer = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    cell.disposeImages();
                    disposeInterrupted.set(Thread.currentThread().isInterrupted());
                } catch (Throwable failure) {
                    disposeFailure.set(failure);
                }
            }
        }, "variation-terminal-disposer");
        disposer.start();
        waitUntilBlocked(disposer);
        assertTrue("off-EDT disposal must wait for the EDT", disposer.isAlive());
        disposer.interrupt();

        cell.setResult(lateResult);
        releaseEdt.countDown();
        disposer.join(5000L);
        assertFalse("terminal disposal did not finish", disposer.isAlive());
        SwingUtilities.invokeAndWait(new Runnable() {
            @Override public void run() {
                // Drain the setter queued behind terminal disposal.
            }
        });

        assertNull(disposeFailure.get());
        assertTrue("the waiting caller's interrupt must be restored",
                disposeInterrupted.get());
        assertTrue(cell.terminalCleanupComplete());
        assertNull(cell.cachedLabelForTest());
        assertEquals(1, lateImage.closeCalls);
        assertEquals(1, lateImage.flushCalls);
        cell.disposeImages();
        assertEquals(1, lateImage.closeCalls);
        assertEquals(1, lateImage.flushCalls);
    }

    @Test
    public void multiCellTerminalDrainRetriesAllAndKeepsFatalPrecedence()
            throws Exception {
        final InterruptedException interrupted =
                new InterruptedException("terminal cleanup interrupted");
        final RuntimeException ordinaryFailure =
                new RuntimeException("ordinary terminal failure", interrupted);
        final ThreadDeath fatalFailure = new ThreadDeath();
        final RetryingDisposer ordinaryDisposer =
                new RetryingDisposer(ordinaryFailure);
        final RetryingDisposer fatalDisposer = new RetryingDisposer(fatalFailure);
        final VariationCellPanel ordinaryCell = cell();
        final VariationCellPanel fatalCell = cell();
        final VariationResult ordinary = ownedFilter(
                new TrackingImage("ordinary-terminal"), ordinaryDisposer);
        final VariationResult fatal = ownedFilter(
                new TrackingImage("fatal-terminal"), fatalDisposer);

        SwingUtilities.invokeAndWait(new Runnable() {
            @Override public void run() {
                ordinaryCell.setResult(ordinary);
                fatalCell.setResult(fatal);
                try {
                    VariationCellPanel.disposeAllImages(
                            Arrays.asList(ordinaryCell, fatalCell));
                    fail("Expected merged terminal cleanup failure.");
                } catch (ThreadDeath expected) {
                    assertSame(fatalFailure, expected);
                    assertEquals(1, expected.getSuppressed().length);
                    assertSame(ordinaryFailure, expected.getSuppressed()[0]);
                    assertFalse(Thread.currentThread().isInterrupted());
                    assertEquals("fatal disposer must stop the synchronous drain",
                            1, fatalDisposer.calls);
                    assertFalse(fatalCell.terminalCleanupComplete());
                } finally {
                    Thread.interrupted();
                }
                assertTrue(ordinaryCell.terminalCleanupComplete());
                assertFalse(fatalCell.terminalCleanupComplete());
                VariationCellPanel.disposeAllImages(
                        Arrays.asList(ordinaryCell, fatalCell));
                assertTrue(fatalCell.terminalCleanupComplete());
            }
        });

        assertEquals(2, ordinaryDisposer.calls);
        assertEquals(1, ordinaryDisposer.successfulCloses);
        assertEquals(2, fatalDisposer.calls);
        assertEquals(1, fatalDisposer.successfulCloses);
    }

    @Test
    public void dialogStyleFatalRecoveryRetiresCellAndNestedResultClaims()
            throws Exception {
        final ThreadDeath fatal = new ThreadDeath();
        final RetryingDisposer disposer = new RetryingDisposer(fatal);
        final VariationCellPanel cell = cell();
        final VariationResult result = ownedFilter(
                new TrackingImage("dialog-fatal-recovery"), disposer);

        SwingUtilities.invokeAndWait(new Runnable() {
            @Override public void run() {
                cell.setResult(result);
                try {
                    cell.disposeImages();
                    fail("Expected the first dialog cleanup to fail fatally.");
                } catch (ThreadDeath expected) {
                    assertSame(fatal, expected);
                }
                assertFalse(cell.terminalCleanupComplete());
                assertEquals("the cell and its direct result are both retained",
                        2, VariationCleanupCoordinator.pendingCountForTest());

                // A second dispose() is how a modal explicitly recovers after its
                // first fatal cleanup. Its final registration must retire both
                // coordinator claims without requiring a coordinator drain/reset.
                cell.disposeImages();
                assertTrue(cell.terminalCleanupComplete());
                VariationCleanupCoordinator.registerCells(Arrays.asList(cell));
                assertEquals(0, VariationCleanupCoordinator.pendingCountForTest());

                VariationCleanupCoordinator.registerCells(Arrays.asList(cell));
                VariationCleanupCoordinator.registerCellsFatal(Arrays.asList(cell));
                VariationCleanupCoordinator.registerResult(result);
                VariationCleanupCoordinator.registerResultFatal(result);
                assertEquals(0, VariationCleanupCoordinator.pendingCountForTest());
            }
        });

        assertEquals(2, disposer.calls);
        assertEquals(1, disposer.successfulCloses);
    }

    @Test
    public void explicitDirectResultRecoveryRetiresFatalClaimIdempotently()
            throws Exception {
        final ThreadDeath fatal = new ThreadDeath();
        final RetryingDisposer disposer = new RetryingDisposer(fatal);
        final VariationResult result = ownedFilter(
                new TrackingImage("direct-fatal-recovery"), disposer);

        assertSame(fatal, VariationCleanupSupport.disposeRejectedResult(result));
        assertEquals(1, VariationCleanupCoordinator.pendingCountForTest());

        // Re-registering an incomplete fatal claim must not make it retryable.
        VariationCleanupCoordinator.registerResult(result);
        VariationCleanupCoordinator.registerResultFatal(result);
        Thread.sleep(600L);
        assertEquals(1, disposer.calls);
        assertEquals(1, VariationCleanupCoordinator.pendingCountForTest());

        assertNull(VariationCleanupSupport.disposeRejectedResult(result));
        assertEquals(2, disposer.calls);
        assertEquals(1, disposer.successfulCloses);
        assertEquals(1, VariationCleanupCoordinator.pendingCountForTest());

        VariationCleanupCoordinator.registerResult(result);
        VariationCleanupCoordinator.registerResult(result);
        VariationCleanupCoordinator.registerResultFatal(result);
        assertEquals(0, VariationCleanupCoordinator.pendingCountForTest());
        assertEquals("retirement must not call the disposer again", 2, disposer.calls);
    }

    @Test
    public void completedResultRegistrationCancelsQueuedRetryWithoutExtraDispose()
            throws Exception {
        PausedDisposer disposer = new PausedDisposer(
                new RuntimeException("ordinary queued cleanup"));
        VariationResult result = ownedFilter(
                new TrackingImage("queued-retry-retirement"), disposer);

        assertTrue(VariationCleanupSupport.disposeRejectedResult(result)
                instanceof RuntimeException);
        assertEquals(8, disposer.calls);
        assertEquals(1, VariationCleanupCoordinator.pendingCountForTest());

        disposer.allowSuccess = true;
        assertNull(VariationCleanupSupport.disposeRejectedResult(result));
        assertEquals(9, disposer.calls);
        VariationCleanupCoordinator.registerResult(result);
        assertEquals(0, VariationCleanupCoordinator.pendingCountForTest());

        Thread.sleep(600L);
        assertEquals("a queued drain must not revisit the retired identity",
                9, disposer.calls);
        assertEquals(0, VariationCleanupCoordinator.pendingCountForTest());
    }

    @Test
    public void terminalDrainIsBoundedWhenNoProgressAndRemainsRetryable()
            throws Exception {
        final RuntimeException persistentFailure =
                new RuntimeException("persistent terminal failure");
        final PausedDisposer disposer = new PausedDisposer(persistentFailure);
        final VariationCellPanel cell = cell();
        final VariationResult result = ownedFilter(
                new TrackingImage("persistent-terminal"), disposer);

        SwingUtilities.invokeAndWait(new Runnable() {
            @Override public void run() {
                cell.setResult(result);
                try {
                    cell.disposeImages();
                    fail("Expected bounded terminal cleanup failure.");
                } catch (RuntimeException expected) {
                    assertSame(persistentFailure, expected);
                }
                assertEquals("one initial attempt plus three stagnant retries",
                        4, disposer.calls);
                assertFalse(cell.terminalCleanupComplete());
                disposer.allowSuccess = true;
                cell.disposeImages();
                assertTrue(cell.terminalCleanupComplete());
                cell.disposeImages();
            }
        });

        assertEquals(5, disposer.calls);
        assertEquals(1, disposer.successfulCloses);
    }

    @Test
    public void coordinatorCompletesCleanupAfterFifthAttemptPostModal()
            throws Exception {
        final int pendingBefore = VariationCleanupCoordinator.pendingCountForTest();
        final CountingFailureDisposer disposer = new CountingFailureDisposer(5);
        final VariationCellPanel cell = cell();
        final VariationResult result = ownedFilter(
                new TrackingImage("coordinated-terminal"), disposer);

        SwingUtilities.invokeAndWait(new Runnable() {
            @Override public void run() {
                cell.setResult(result);
                try {
                    cell.disposeImages();
                    fail("Expected initial bounded cleanup failure.");
                } catch (RuntimeException expected) {
                    // The modal may close after transferring this live cell.
                }
                assertFalse(cell.terminalCleanupComplete());
                VariationCleanupCoordinator.registerCells(Arrays.asList(cell));
                assertEquals(pendingBefore + 1,
                        VariationCleanupCoordinator.pendingCountForTest());
                VariationCleanupCoordinator.drainNowForTest();
            }
        });

        assertTrue(cell.terminalCleanupComplete());
        assertTrue("cleanup must continue beyond the modal's four attempts",
                disposer.calls >= 6);
        assertEquals(1, disposer.successfulCloses);
        assertEquals(pendingBefore,
                VariationCleanupCoordinator.pendingCountForTest());
    }

    @Test
    public void coordinatorRetainsPersistentClaimWithoutSynchronousBusyLoop()
            throws Exception {
        final int pendingBefore = VariationCleanupCoordinator.pendingCountForTest();
        final RuntimeException persistentFailure =
                new RuntimeException("coordinator persistent failure");
        final PausedDisposer disposer = new PausedDisposer(persistentFailure);
        final VariationCellPanel cell = cell();
        final VariationResult result = ownedFilter(
                new TrackingImage("coordinator-persistent"), disposer);

        SwingUtilities.invokeAndWait(new Runnable() {
            @Override public void run() {
                cell.setResult(result);
                try {
                    cell.disposeImages();
                } catch (RuntimeException expected) {
                    assertSame(persistentFailure, expected);
                }
                VariationCleanupCoordinator.registerCells(Arrays.asList(cell));
                int before = disposer.calls;
                Throwable retryFailure = VariationCleanupCoordinator.drainNowForTest();
                assertSame(persistentFailure, retryFailure);
                assertEquals("one coordinator drain remains bounded",
                        before + 4, disposer.calls);
                assertFalse(cell.terminalCleanupComplete());
                assertEquals(pendingBefore + 1,
                        VariationCleanupCoordinator.pendingCountForTest());
                disposer.allowSuccess = true;
                VariationCleanupCoordinator.drainNowForTest();
            }
        });

        assertTrue(cell.terminalCleanupComplete());
        assertEquals(1, disposer.successfulCloses);
        assertEquals(pendingBefore,
                VariationCleanupCoordinator.pendingCountForTest());
    }

    @Test
    public void coordinatorFailureDoesNotCreateEdtInterruptState()
            throws Exception {
        final int pendingBefore = VariationCleanupCoordinator.pendingCountForTest();
        final RuntimeException interruptedFailure = new RuntimeException(
                "coordinator interrupted cleanup",
                new InterruptedException("nested cleanup interruption"));
        final PausedDisposer disposer = new PausedDisposer(interruptedFailure);
        final VariationResult result = ownedFilter(
                new TrackingImage("coordinator-interrupted"), disposer);

        SwingUtilities.invokeAndWait(new Runnable() {
            @Override public void run() {
                Throwable initial = VariationCleanupSupport.disposeRejectedResult(result);
                assertSame(interruptedFailure, initial);
                assertFalse(Thread.currentThread().isInterrupted());
                assertEquals(pendingBefore + 1,
                        VariationCleanupCoordinator.pendingCountForTest());

                Throwable retry = VariationCleanupCoordinator.drainNowForTest();
                assertSame(interruptedFailure, retry);
                assertFalse(Thread.currentThread().isInterrupted());

                disposer.allowSuccess = true;
                assertNull(VariationCleanupCoordinator.drainNowForTest());
                assertFalse(Thread.currentThread().isInterrupted());
            }
        });

        assertEquals(1, disposer.successfulCloses);
        assertEquals(pendingBefore,
                VariationCleanupCoordinator.pendingCountForTest());
    }

    @Test
    public void coordinatorRethrowsThreadDeathOnceWithNoDefaultHandlerAndRetainsClaim()
            throws Exception {
        Thread.UncaughtExceptionHandler previousDefault =
                Thread.getDefaultUncaughtExceptionHandler();
        ThreadDeath fatal = new ThreadDeath();
        PausedThrowableDisposer disposer = new PausedThrowableDisposer(fatal);
        VariationResult result = ownedFilter(
                new TrackingImage("coordinator-thread-death"), disposer);
        Thread.setDefaultUncaughtExceptionHandler(null);
        try (EdtUncaughtExceptionCapture capture =
                     EdtUncaughtExceptionCapture.install()) {
            rejectAndRethrowOnEdt(result);
            waitForEdtFailure(capture);
            assertSame(fatal, capture.failure());
            assertEquals(1, capture.count());
            assertEquals(1, disposer.calls);
            assertEquals(1, VariationCleanupCoordinator.pendingCountForTest());

            Thread.sleep(750L);
            assertEquals("fatal claim must not create an automatic retry storm",
                    1, capture.count());

            disposer.allowSuccess = true;
            assertNull(drainOnEdt());
            assertEquals(0, VariationCleanupCoordinator.pendingCountForTest());
            assertEquals(2, disposer.calls);
            assertEquals(1, disposer.successfulCloses);
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previousDefault);
        }
    }

    @Test
    public void coordinatorRethrowsVmErrorThroughCustomDefaultHandlerExactlyOnce()
            throws Exception {
        final Thread.UncaughtExceptionHandler previousDefault =
                Thread.getDefaultUncaughtExceptionHandler();
        final AtomicInteger delivered = new AtomicInteger();
        final AtomicReference<Throwable> observed = new AtomicReference<Throwable>();
        final CountDownLatch deliveredLatch = new CountDownLatch(1);
        final TestVmError fatal = new TestVmError("coordinator-vm-error");
        final PausedThrowableDisposer disposer = new PausedThrowableDisposer(fatal);
        final VariationResult result = ownedFilter(
                new TrackingImage("coordinator-vm-error"), disposer);
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override public void uncaughtException(Thread thread, Throwable failure) {
                delivered.incrementAndGet();
                observed.compareAndSet(null, failure);
                deliveredLatch.countDown();
            }
        });
        try {
            // Route this EDT through its ThreadGroup so the configured default handler is the
            // single standard uncaught-exception destination. Production never invokes it directly.
            SwingUtilities.invokeAndWait(new Runnable() {
                @Override public void run() {
                    Thread current = Thread.currentThread();
                    current.setUncaughtExceptionHandler(current.getThreadGroup());
                }
            });
            rejectAndRethrowOnEdt(result);
            assertTrue("VM-fatal cleanup did not escape the EDT",
                    deliveredLatch.await(5L, TimeUnit.SECONDS));
            assertSame(fatal, observed.get());
            assertEquals(1, delivered.get());
            assertEquals(1, disposer.calls);
            assertEquals(1, VariationCleanupCoordinator.pendingCountForTest());

            Thread.sleep(750L);
            assertEquals("custom default handler must not receive a duplicate fatal",
                    1, delivered.get());

            disposer.allowSuccess = true;
            assertNull(drainOnEdt());
            assertEquals(0, VariationCleanupCoordinator.pendingCountForTest());
            assertEquals(2, disposer.calls);
            assertEquals(1, disposer.successfulCloses);
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previousDefault);
        }
    }

    @Test
    public void coordinatorKeepsOrdinaryPersistentFailureOnAutomaticRetrySchedule()
            throws Exception {
        PausedDisposer disposer = new PausedDisposer(
                new RuntimeException("ordinary automatic retry"));
        VariationResult result = ownedFilter(
                new TrackingImage("coordinator-ordinary-automatic"), disposer);

        assertTrue(rejectOnEdt(result) instanceof RuntimeException);
        int initialCalls = disposer.calls;
        waitForAdditionalCalls(disposer, initialCalls);
        assertEquals(1, VariationCleanupCoordinator.pendingCountForTest());

        disposer.allowSuccess = true;
        waitForCoordinatorToDrain();
        assertEquals(0, VariationCleanupCoordinator.pendingCountForTest());
        assertEquals(1, disposer.successfulCloses);
    }

    private static VariationCellPanel cell() {
        return new VariationCellPanel(ParameterCombo.builder().build(),
                new ImagePlus("source", new ByteProcessor(1, 1)), null, null);
    }

    private static VariationResult ownedFilter(
            ImagePlus image,
            VariationResult.ImageDisposer disposer) {
        return VariationResult.filterSuccess(ParameterCombo.builder().build(), image,
                1L, new int[256], 1.0d, 1.0d, disposer);
    }

    private static Throwable rejectOnEdt(final VariationResult result) throws Exception {
        final AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
        SwingUtilities.invokeAndWait(new Runnable() {
            @Override public void run() {
                failure.set(VariationCleanupSupport.disposeRejectedResult(result));
            }
        });
        return failure.get();
    }

    private static void rejectAndRethrowOnEdt(final VariationResult result) {
        SwingUtilities.invokeLater(new Runnable() {
            @Override public void run() {
                VariationCleanupSupport.rethrow(
                        VariationCleanupSupport.disposeRejectedResult(result));
            }
        });
    }

    private static Throwable drainOnEdt() throws Exception {
        final AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
        SwingUtilities.invokeAndWait(new Runnable() {
            @Override public void run() {
                failure.set(VariationCleanupCoordinator.drainNowForTest());
            }
        });
        return failure.get();
    }

    private static void waitForEdtFailure(EdtUncaughtExceptionCapture capture)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5L);
        while (capture.failure() == null && System.nanoTime() < deadline) {
            Thread.sleep(10L);
        }
        assertTrue("fatal cleanup did not escape the EDT", capture.failure() != null);
    }

    private static void waitForAdditionalCalls(PausedDisposer disposer, int initialCalls)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5L);
        while (disposer.calls <= initialCalls && System.nanoTime() < deadline) {
            Thread.sleep(10L);
        }
        assertTrue("ordinary cleanup was not automatically retried",
                disposer.calls > initialCalls);
    }

    private static void waitForCoordinatorToDrain() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5L);
        while (VariationCleanupCoordinator.pendingCountForTest() != 0
                && System.nanoTime() < deadline) {
            Thread.sleep(10L);
        }
        assertEquals("coordinator did not finish the retryable claim",
                0, VariationCleanupCoordinator.pendingCountForTest());
    }

    private static void waitUntilBlocked(Thread thread) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5L);
        while (thread.isAlive() && System.nanoTime() < deadline) {
            Thread.State state = thread.getState();
            if (state == Thread.State.WAITING || state == Thread.State.TIMED_WAITING) {
                return;
            }
            Thread.yield();
        }
        fail("disposal thread did not block waiting for the EDT");
    }

    private static void await(CountDownLatch latch) {
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

    private static final class RetryingDisposer
            implements VariationResult.ImageDisposer {
        private Throwable failure;
        int calls;
        int successfulCloses;

        RetryingDisposer(Throwable failure) {
            this.failure = failure;
        }

        @Override public void dispose(ImagePlus image) {
            calls++;
            if (failure != null) {
                Throwable firstFailure = failure;
                failure = null;
                rethrow(firstFailure);
            }
            image.close();
            successfulCloses++;
        }
    }

    private static final class PausedDisposer
            implements VariationResult.ImageDisposer {
        private final RuntimeException failure;
        volatile boolean allowSuccess;
        volatile int calls;
        volatile int successfulCloses;

        PausedDisposer(RuntimeException failure) {
            this.failure = failure;
        }

        @Override public void dispose(ImagePlus image) {
            calls++;
            if (!allowSuccess) {
                throw failure;
            }
            image.close();
            successfulCloses++;
        }
    }

    private static final class PausedThrowableDisposer
            implements VariationResult.ImageDisposer {
        private final Throwable failure;
        boolean allowSuccess;
        int calls;
        int successfulCloses;

        PausedThrowableDisposer(Throwable failure) {
            this.failure = failure;
        }

        @Override public void dispose(ImagePlus image) {
            calls++;
            if (!allowSuccess) {
                rethrow(failure);
            }
            image.close();
            successfulCloses++;
        }
    }

    private static final class TestVmError extends VirtualMachineError {
        TestVmError(String message) {
            super(message);
        }
    }

    private static final class CountingFailureDisposer
            implements VariationResult.ImageDisposer {
        private int failuresRemaining;
        int calls;
        int successfulCloses;

        CountingFailureDisposer(int failuresRemaining) {
            this.failuresRemaining = failuresRemaining;
        }

        @Override public void dispose(ImagePlus image) {
            calls++;
            if (failuresRemaining > 0) {
                failuresRemaining--;
                throw new RuntimeException("counted cleanup failure");
            }
            image.close();
            successfulCloses++;
        }
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
        throw new AssertionError(failure);
    }
}
