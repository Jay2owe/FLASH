package flash.pipeline.ui.variations;

import ij.IJ;

import javax.swing.SwingUtilities;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Keeps terminal cleanup claims alive after their modal window has closed. */
final class VariationCleanupCoordinator {

    private static final long RETRY_DELAY_MS = 250L;
    private static final IdentityHashMap<VariationCellPanel, Registration> CELLS =
            new IdentityHashMap<VariationCellPanel, Registration>();
    private static final IdentityHashMap<VariationResult, Registration> RESULTS =
            new IdentityHashMap<VariationResult, Registration>();
    private static final ScheduledExecutorService SCHEDULER =
            Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
                @Override public Thread newThread(Runnable task) {
                    Thread thread = new Thread(task, "flash-variation-cleanup");
                    thread.setDaemon(true);
                    return thread;
                }
            });

    private static ScheduledFuture<?> scheduledRetry;
    private static boolean edtDrainQueued;
    private static long retryGeneration;

    private VariationCleanupCoordinator() {
    }

    static void registerCells(List<VariationCellPanel> cells) {
        registerCells(cells, false);
    }

    static void registerCellsFatal(List<VariationCellPanel> cells) {
        registerCells(cells, true);
    }

    static void registerCellFatal(VariationCellPanel cell) {
        if (cell == null) {
            return;
        }
        List<VariationCellPanel> cells = new ArrayList<VariationCellPanel>(1);
        cells.add(cell);
        registerCells(cells, true);
    }

    private static void registerCells(List<VariationCellPanel> cells,
                                      boolean fatalPaused) {
        if (cells == null) {
            return;
        }
        synchronized (VariationCleanupCoordinator.class) {
            for (int i = 0; i < cells.size(); i++) {
                VariationCellPanel cell = cells.get(i);
                if (cell == null) {
                    continue;
                }
                if (cell.terminalCleanupComplete()) {
                    retireRegistrationLocked(CELLS, cell);
                    continue;
                }
                Registration registration = CELLS.get(cell);
                if (registration == null) {
                    registration = new Registration();
                    CELLS.put(cell, registration);
                }
                registration.fatalPaused |= fatalPaused;
            }
            finishRegistrationLocked();
        }
    }

    static void registerResult(VariationResult result) {
        registerResult(result, false);
    }

    static void registerResultFatal(VariationResult result) {
        registerResult(result, true);
    }

    private static void registerResult(VariationResult result,
                                       boolean fatalPaused) {
        if (result == null) {
            return;
        }
        synchronized (VariationCleanupCoordinator.class) {
            if (result.pendingTransferredImages().length == 0) {
                retireRegistrationLocked(RESULTS, result);
                finishRegistrationLocked();
                return;
            }
            Registration registration = RESULTS.get(result);
            if (registration == null) {
                registration = new Registration();
                RESULTS.put(result, registration);
            }
            registration.fatalPaused |= fatalPaused;
            finishRegistrationLocked();
        }
    }

    private static void finishRegistrationLocked() {
        retireCompletedClaimsLocked();
        if (!hasRetryableClaimsLocked() && scheduledRetry != null) {
            scheduledRetry.cancel(false);
            scheduledRetry = null;
        }
        scheduleRetryLocked();
    }

    /**
     * A successful explicit modal retry completes claims outside a coordinator
     * drain. Reconcile every known owner while registration is locked so the
     * modal's final registration also retires nested direct-result claims.
     */
    private static void retireCompletedClaimsLocked() {
        Iterator<Map.Entry<VariationCellPanel, Registration>> cells =
                CELLS.entrySet().iterator();
        while (cells.hasNext()) {
            Map.Entry<VariationCellPanel, Registration> entry = cells.next();
            if (entry.getKey().terminalCleanupComplete()) {
                if (entry.getValue().draining) {
                    entry.getValue().retirementRequested = true;
                } else {
                    cells.remove();
                }
            }
        }
        Iterator<Map.Entry<VariationResult, Registration>> results =
                RESULTS.entrySet().iterator();
        while (results.hasNext()) {
            Map.Entry<VariationResult, Registration> entry = results.next();
            if (entry.getKey().pendingTransferredImages().length == 0) {
                if (entry.getValue().draining) {
                    entry.getValue().retirementRequested = true;
                } else {
                    results.remove();
                }
            }
        }
    }

    private static <T> void retireRegistrationLocked(
            IdentityHashMap<T, Registration> registrations, T owner) {
        Registration registration = registrations.get(owner);
        if (registration == null) {
            return;
        }
        if (registration.draining) {
            registration.retirementRequested = true;
        } else {
            registrations.remove(owner);
        }
    }

    static Throwable drainNowForTest() {
        boolean callerIsEdt = SwingUtilities.isEventDispatchThread();
        final AtomicReference<Throwable> failure =
                new AtomicReference<Throwable>();
        VariationCleanupSupport.runOnEdtAndWait(
                new VariationCleanupSupport.Task() {
                    @Override public void run() {
                        failure.set(drainOnEdt(true));
                        synchronized (VariationCleanupCoordinator.class) {
                            scheduleRetryLocked();
                        }
                    }
                });
        Throwable result = failure.get();
        if (!callerIsEdt && VariationResult.containsInterruptedFailure(result)) {
            Thread.currentThread().interrupt();
        }
        return result;
    }

    static synchronized int pendingCountForTest() {
        return CELLS.size() + RESULTS.size();
    }

    /** Clears test-created claims and invalidates already-queued retry work. */
    static void resetForTest() {
        VariationCleanupSupport.runOnEdtAndWait(
                new VariationCleanupSupport.Task() {
                    @Override public void run() {
                        synchronized (VariationCleanupCoordinator.class) {
                            retryGeneration++;
                            if (scheduledRetry != null) {
                                scheduledRetry.cancel(false);
                                scheduledRetry = null;
                            }
                            CELLS.clear();
                            RESULTS.clear();
                            edtDrainQueued = false;
                        }
                    }
                });
    }

    private static void scheduleRetryLocked() {
        if (!hasRetryableClaimsLocked()
                || scheduledRetry != null || edtDrainQueued) {
            return;
        }
        final long generation = retryGeneration;
        scheduledRetry = SCHEDULER.schedule(new Runnable() {
            @Override public void run() {
                queueEdtDrain(generation);
            }
        }, RETRY_DELAY_MS, TimeUnit.MILLISECONDS);
    }

    private static void queueEdtDrain(final long generation) {
        synchronized (VariationCleanupCoordinator.class) {
            if (generation != retryGeneration) {
                return;
            }
            scheduledRetry = null;
            if (CELLS.isEmpty() && RESULTS.isEmpty()) {
                return;
            }
            if (edtDrainQueued) {
                return;
            }
            edtDrainQueued = true;
        }
        SwingUtilities.invokeLater(new Runnable() {
            @Override public void run() {
                synchronized (VariationCleanupCoordinator.class) {
                    if (generation != retryGeneration) {
                        return;
                    }
                }
                Throwable failure = null;
                try {
                    try {
                        failure = drainOnEdt(false);
                    } catch (Throwable drainFailure) {
                        failure = VariationCleanupSupport.merge(failure, drainFailure);
                        pauseAllClaimsForFatal(failure);
                    }
                    try {
                        reportFailure(failure);
                    } catch (Throwable reportFailure) {
                        failure = VariationCleanupSupport.merge(failure, reportFailure);
                        pauseAllClaimsForFatal(failure);
                    }
                } finally {
                    synchronized (VariationCleanupCoordinator.class) {
                        if (generation == retryGeneration) {
                            edtDrainQueued = false;
                            scheduleRetryLocked();
                        }
                    }
                }
                if (VariationCleanupSupport.isVmFatal(failure)) {
                    // Let the EDT's actual uncaught-exception path observe the fatal exactly once.
                    // The failed owner remains registered but paused from automatic retries.
                    VariationCleanupSupport.rethrow(failure);
                }
            }
        });
    }

    private static Throwable drainOnEdt(boolean includeFatalPaused) {
        List<DrainClaim<VariationCellPanel>> cells =
                new ArrayList<DrainClaim<VariationCellPanel>>();
        List<DrainClaim<VariationResult>> results =
                new ArrayList<DrainClaim<VariationResult>>();
        synchronized (VariationCleanupCoordinator.class) {
            for (Map.Entry<VariationCellPanel, Registration> entry
                    : CELLS.entrySet()) {
                cells.add(new DrainClaim<VariationCellPanel>(
                        entry.getKey(), entry.getValue()));
            }
            for (Map.Entry<VariationResult, Registration> entry
                    : RESULTS.entrySet()) {
                results.add(new DrainClaim<VariationResult>(
                        entry.getKey(), entry.getValue()));
            }
        }
        Throwable visibleFailure = null;
        for (int i = 0; i < cells.size(); i++) {
            DrainClaim<VariationCellPanel> claim = cells.get(i);
            if (!isCurrentAndEligible(CELLS, claim, includeFatalPaused)) {
                continue;
            }
            Throwable failure = null;
            try {
                claim.owner.disposeImages();
            } catch (Throwable cellFailure) {
                failure = cellFailure;
            }
            visibleFailure = recordOutcome(CELLS, claim, failure,
                    claim.owner.terminalCleanupComplete(), visibleFailure);
            if (VariationCleanupSupport.isVmFatal(visibleFailure)) {
                pauseAllClaimsForFatal(visibleFailure);
                return visibleFailure;
            }
        }
        for (int i = 0; i < results.size(); i++) {
            DrainClaim<VariationResult> claim = results.get(i);
            if (!isCurrentAndEligible(RESULTS, claim, includeFatalPaused)) {
                continue;
            }
            Throwable failure = null;
            try {
                failure = VariationCleanupSupport.disposeRejectedResult(claim.owner);
            } catch (Throwable resultFailure) {
                failure = resultFailure;
            }
            visibleFailure = recordOutcome(RESULTS, claim, failure,
                    claim.owner.pendingTransferredImages().length == 0,
                    visibleFailure);
            if (VariationCleanupSupport.isVmFatal(visibleFailure)) {
                pauseAllClaimsForFatal(visibleFailure);
                return visibleFailure;
            }
        }
        return visibleFailure;
    }

    private static <T> Throwable recordOutcome(
            IdentityHashMap<T, Registration> registrations,
            DrainClaim<T> claim,
            Throwable failure,
            boolean complete,
            Throwable visibleFailure) {
        synchronized (VariationCleanupCoordinator.class) {
            Registration registration = registrations.get(claim.owner);
            if (registration != claim.registration) {
                return visibleFailure;
            }
            registration.draining = false;
            if (complete || registration.retirementRequested) {
                registrations.remove(claim.owner);
            }
            if (!complete) {
                registration.fatalPaused = VariationCleanupSupport.isVmFatal(failure);
            }
            if (failure != null
                    && (!registration.failureReported
                    || VariationCleanupSupport.isVmFatal(failure))) {
                registration.failureReported = true;
                return VariationCleanupSupport.merge(visibleFailure, failure);
            }
        }
        return visibleFailure;
    }

    private static <T> boolean isCurrentAndEligible(
            IdentityHashMap<T, Registration> registrations,
            DrainClaim<T> claim,
            boolean includeFatalPaused) {
        synchronized (VariationCleanupCoordinator.class) {
            Registration current = registrations.get(claim.owner);
            if (current != claim.registration || current.draining
                    || (!includeFatalPaused && current.fatalPaused)) {
                return false;
            }
            current.draining = true;
            return true;
        }
    }

    private static void reportFailure(Throwable failure) {
        if (failure == null) {
            return;
        }
        boolean fatal = VariationCleanupSupport.isVmFatal(failure);
        IJ.log((fatal
                ? "Deferred variation cleanup hit a fatal error; claim retained and automatic retry paused: "
                : "Deferred variation cleanup failed and remains scheduled: ")
                + failure.getClass().getSimpleName() + ": " + safeMessage(failure));
    }

    private static boolean hasRetryableClaimsLocked() {
        for (Registration registration : CELLS.values()) {
            if (!registration.fatalPaused) return true;
        }
        for (Registration registration : RESULTS.values()) {
            if (!registration.fatalPaused) return true;
        }
        return false;
    }

    private static synchronized void pauseAllClaimsForFatal(Throwable failure) {
        if (!VariationCleanupSupport.isVmFatal(failure)) return;
        for (Registration registration : CELLS.values()) {
            registration.fatalPaused = true;
        }
        for (Registration registration : RESULTS.values()) {
            registration.fatalPaused = true;
        }
    }

    private static String safeMessage(Throwable failure) {
        String message = failure.getMessage();
        return message == null || message.trim().isEmpty()
                ? "no message" : message.trim();
    }

    private static final class Registration {
        private boolean failureReported;
        private boolean fatalPaused;
        private boolean draining;
        private boolean retirementRequested;
    }

    private static final class DrainClaim<T> {
        private final T owner;
        private final Registration registration;

        private DrainClaim(T owner, Registration registration) {
            this.owner = owner;
            this.registration = registration;
        }
    }
}
