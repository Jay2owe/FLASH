package flash.pipeline.ui.variations;

import ij.IJ;

import javax.swing.SwingWorker;
import javax.swing.SwingUtilities;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

public final class VariationExecutor extends SwingWorker<Void, VariationResult> {

    private final ParameterSweep sweep;
    private final VariationStrategy strategy;
    private final VariationCache cache;
    private final BiConsumer<VariationResult, Integer> onResult;
    private final Consumer<String> onStatus;
    private final Set<VariationResult> pendingResults = Collections.newSetFromMap(
            new IdentityHashMap<VariationResult, Boolean>());
    /** Guarded by {@link #pendingResults}; accepted identities are never reused. */
    private final Set<VariationResult> acceptedResults = Collections.newSetFromMap(
            new IdentityHashMap<VariationResult, Boolean>());
    /** Guarded by {@link #pendingResults}; claims not yet committed to cleanup. */
    private final Set<VariationResult> uncommittedDisposals = Collections.newSetFromMap(
            new IdentityHashMap<VariationResult, Boolean>());
    private int deliveredCount;
    /** Guarded by {@link #pendingResults}; pauses every producer-owned result claim. */
    private Throwable fatalPause;
    /** Guarded by {@link #pendingResults}. */
    private boolean fatalPauseSurfaced;
    /** Guarded by {@link #pendingResults}; deterministic seam for ownership-race tests. */
    private Runnable producerDisposalClaimedHookForTest;
    /** Guarded by {@link #pendingResults}; runs after cleanup commit in tests. */
    private Runnable committedDisposalHookForTest;
    /** Read outside the monitor only after an atomic snapshot; test-only failure seam. */
    private FatalRetentionHook fatalRetentionHookForTest;

    interface FatalRetentionHook {
        void beforeRegister(VariationResult result);
    }

    public VariationExecutor(ParameterSweep sweep,
                             VariationStrategy strategy,
                             VariationCache cache,
                             BiConsumer<VariationResult, Integer> onResult,
                             Consumer<String> onStatus) {
        if (sweep == null) {
            throw new IllegalArgumentException("sweep must not be null");
        }
        if (strategy == null) {
            throw new IllegalArgumentException("strategy must not be null");
        }
        this.sweep = sweep;
        this.strategy = strategy;
        this.cache = cache;
        this.onResult = onResult;
        this.onStatus = onStatus;
    }

    public VariationCache cache() {
        return cache;
    }

    @Override
    protected Void doInBackground() throws Exception {
        postStatus("Running " + strategy.getClass().getSimpleName()
                + " (" + sweep.cellCount() + " cells)...");
        boolean completed = false;
        try {
            strategy.dispatch(sweep, this::publishResult, this::isCancellationRequested);
            completed = true;
        } catch (Throwable t) {
            if (isFatal(t)) {
                rethrowFailure(recordStrategyFatal(t));
                return null;
            }
            if (!isCancellationRequested()) {
                String message = "Parameter variations stopped early: " + errorMessage(t);
                IJ.log(message);
                postStatus(message);
            }
        }
        if (completed && !isCancellationRequested()) {
            postStatus("Parameter variations complete.");
        }
        return null;
    }

    void publishResult(VariationResult result) {
        if (result == null) {
            return;
        }
        boolean fatalAlreadyRecorded;
        synchronized (pendingResults) {
            if (!acceptedResults.add(result)) {
                return;
            }
            fatalAlreadyRecorded = fatalPause != null;
            pendingResults.add(result);
        }
        if (fatalAlreadyRecorded) {
            retainPendingResultAfterFatal(result);
            return;
        }
        if (pausePublishedResultIfFatal(result)) {
            return;
        }
        if (isCancellationRequested()) {
            Throwable failure = disposePendingResult(result);
            rethrowFailure(failure);
            return;
        }
        try {
            publish(result);
        } catch (Throwable t) {
            if (isFatal(t)) {
                rethrowFailure(recordStrategyFatal(t));
            }
            rethrowFailure(mergeFailure(t, disposePendingResult(result)));
        }
    }

    @Override
    protected void process(List<VariationResult> chunks) {
        if (chunks == null) {
            return;
        }
        Throwable terminalFailure = null;
        Throwable callbackFatalToSurface = null;
        for (int i = 0; i < chunks.size(); i++) {
            VariationResult result = chunks.get(i);
            if (result == null) {
                continue;
            }
            Throwable recordedFatal = recordedFatalPause();
            if (recordedFatal != null) {
                retainPendingResultAfterFatal(result);
                continue;
            }
            if (terminalFailure != null || isCancellationRequested()) {
                if (isFatal(terminalFailure)) {
                    retainPendingResultAfterFatal(result);
                } else {
                    terminalFailure = mergeFailure(terminalFailure,
                            disposePendingResult(result));
                }
                continue;
            }
            if (onResult == null) {
                Throwable disposalFailure = disposePendingResult(result);
                if (isFatal(disposalFailure)) {
                    terminalFailure = mergeFailure(terminalFailure, disposalFailure);
                    cancel(false);
                } else if (disposalFailure != null) {
                    IJ.log("Could not dispose unhandled parameter variation result "
                            + deliveredCount + ": " + errorMessage(disposalFailure));
                }
                deliveredCount++;
                continue;
            }
            try {
                if (!claimResultForCallback(result)) {
                    retainPendingResultAfterFatal(result);
                    continue;
                }
                onResult.accept(result, Integer.valueOf(deliveredCount));
                recordedFatal = recordedFatalPause();
                if (recordedFatal == null) {
                    result.transferOwnership();
                } else {
                    // A callback already in progress owns its own handoff boundary. Preserve an
                    // ownership transfer it performed itself; otherwise pause the producer claim.
                    retainProducerOwnedResultAfterFatal(result);
                }
            } catch (Throwable t) {
                Throwable failure;
                recordedFatal = recordedFatalPause();
                if (recordedFatal != null) {
                    retainProducerOwnedResultAfterFatal(result);
                    addSuppressed(recordedFatal, t);
                    failure = null;
                } else if (isFatal(t)) {
                    Throwable fatalToSurface = recordCallbackFatal(t, result);
                    cancel(false);
                    terminalFailure = mergeFailure(terminalFailure,
                            fatalToSurface);
                    callbackFatalToSurface = mergeFailure(
                            callbackFatalToSurface, fatalToSurface);
                    failure = fatalToSurface;
                } else {
                    failure = mergeFailure(t,
                            disposeProducerOwnedResultUnlessFatal(result));
                }
                if (failure == null) {
                    // A previously surfaced canonical fatal won the race.
                } else if (isFatal(failure)) {
                    terminalFailure = mergeFailure(terminalFailure, failure);
                    cancel(false);
                } else {
                    IJ.log("Parameter variation result handler failed for result "
                            + deliveredCount + ": " + errorMessage(failure));
                }
            }
            deliveredCount++;
        }
        if (isFatal(terminalFailure)) {
            retainPendingResultsAfterFatal();
        } else if (isCancellationRequested() || terminalFailure != null) {
            terminalFailure = mergeFailure(terminalFailure, disposePendingResults());
        }
        Throwable recordedFatal = recordedFatalPause();
        if (isFatal(terminalFailure) && recordedFatal != null) {
            addSuppressed(recordedFatal, terminalFailure);
        } else if (isFatal(terminalFailure)) {
            rethrowFailure(terminalFailure);
        }
        if (callbackFatalToSurface != null) {
            rethrowFailure(callbackFatalToSurface);
        }
        if (terminalFailure != null) {
            IJ.log("Could not dispose cancelled parameter variation results: "
                    + errorMessage(terminalFailure));
        }
    }

    @Override
    protected void done() {
        if (recordedFatalPause() != null) {
            // Catch any producer result published immediately around fatal recording. Queued
            // process() calls run before done() on the EDT and only repeat this idempotent handoff.
            Throwable fatal = resolveFatalOutcome(
                    null, retainTrackedClaimsAfterFatal(), true);
            if (fatal != null) {
                rethrowFailure(fatal);
            }
            return;
        }
        Throwable failure = disposePendingResults();
        if (isFatal(failure)) {
            Throwable fatalToSurface = resolveFatalOutcome(
                    failure, null, true);
            if (fatalToSurface != null) {
                rethrowFailure(fatalToSurface);
            }
            return;
        }
        if (failure != null) {
            IJ.log("Could not dispose undelivered parameter variation results: "
                    + errorMessage(failure));
        }
    }

    private boolean isCancellationRequested() {
        return isCancelled() || Thread.currentThread().isInterrupted();
    }

    private void postStatus(final String text) {
        if (onStatus == null) {
            return;
        }
        if (SwingUtilities.isEventDispatchThread()) {
            deliverStatusOnEdt(text);
            return;
        }
        SwingUtilities.invokeLater(new Runnable() {
            @Override public void run() {
                deliverStatusOnEdt(text);
            }
        });
    }

    private void deliverStatusOnEdt(String text) {
        if (!claimStatusDelivery()) {
            return;
        }
        Throwable callbackFailure = null;
        Throwable retentionFailure = null;
        try {
            onStatus.accept(text);
        } catch (Throwable failure) {
            callbackFailure = failure;
            if (isFatal(failure)) {
                // Do not mark this fatal surfaced until the canonical first fatal
                // has been selected below. A strategy fatal may have won while
                // this already-authorized callback was running.
                retentionFailure = recordFatalPause(failure, null);
            }
        }
        if (callbackFailure == null) {
            return;
        }
        if (!isFatal(callbackFailure)) {
            rethrowFailure(resolveFatalOutcome(
                    callbackFailure, null, true));
            return;
        }
        Throwable cancellationFailure = null;
        try {
            // Cancellation must be attempted even when fatal retention itself
            // failed; cancellation-ignoring producers are still guarded by
            // fatalPause when they publish their final result.
            cancel(false);
        } catch (Throwable failure) {
            cancellationFailure = failure;
        }
        Throwable fatalToSurface = resolveFatalOutcome(callbackFailure,
                mergeFailure(retentionFailure, cancellationFailure), true);
        rethrowFailure(fatalToSurface);
    }

    private boolean claimStatusDelivery() {
        synchronized (pendingResults) {
            if (fatalPause != null) {
                // A fatal transition that wins this monitor suppresses every
                // status task queued before or after it.
                return false;
            }
            // A successful monitor-linearized check is the delivery claim. A
            // later fatal may become canonical, but cannot revoke a callback
            // whose claim already committed.
            return true;
        }
    }

    private boolean claimResultForCallback(VariationResult result) {
        synchronized (pendingResults) {
            if (fatalPause != null) {
                return false;
            }
            return pendingResults.remove(result);
        }
    }

    private Throwable disposePendingResult(VariationResult result) {
        if (result == null) {
            return null;
        }
        boolean pause;
        synchronized (pendingResults) {
            if (!pendingResults.contains(result)) {
                return null;
            }
            pause = fatalPause != null;
            if (!pause) {
                pendingResults.remove(result);
                uncommittedDisposals.add(result);
            }
        }
        if (pause) {
            retainPendingResultAfterFatal(result);
            return null;
        }
        return commitAndDisposeProducerOwnedResult(result);
    }

    private void retainPendingResultAfterFatal(VariationResult result) {
        if (result == null) {
            return;
        }
        boolean tracked;
        synchronized (pendingResults) {
            tracked = pendingResults.contains(result)
                    || uncommittedDisposals.contains(result);
        }
        if (tracked) {
            retryFatalRetention();
        }
    }

    private void retainProducerOwnedResultAfterFatal(VariationResult result) {
        if (result != null && result.hasDirectOwnership()) {
            synchronized (pendingResults) {
                uncommittedDisposals.add(result);
            }
            retryFatalRetention();
        }
    }

    private Throwable recordStrategyFatal(Throwable fatal) {
        Throwable retentionFailure = recordFatalPause(fatal, null);
        return resolveFatalOutcome(fatal, retentionFailure, false);
    }

    private Throwable recordCallbackFatal(Throwable fatal,
                                          VariationResult activeResult) {
        Throwable retentionFailure = recordFatalPause(fatal, activeResult);
        return resolveFatalOutcome(fatal, retentionFailure, true);
    }

    private Throwable recordFatalPause(Throwable fatal,
                                       VariationResult activeResult) {
        if (!isFatal(fatal)) {
            return null;
        }
        synchronized (pendingResults) {
            // This is the same monitor used by claimStatusDelivery(). Therefore
            // either this transition suppresses a status callback, or an earlier
            // status claim has already authorized that callback to run outside
            // the monitor.
            if (fatalPause == null) {
                fatalPause = fatal;
            } else if (fatalPause != fatal) {
                addSuppressed(fatalPause, fatal);
            }
        }
        if (activeResult != null && activeResult.hasDirectOwnership()) {
            synchronized (pendingResults) {
                uncommittedDisposals.add(activeResult);
            }
        }
        return retainTrackedClaimsAfterFatal();
    }

    private Throwable recordedFatalPause() {
        synchronized (pendingResults) {
            return fatalPause;
        }
    }

    private boolean pausePublishedResultIfFatal(VariationResult result) {
        boolean fatalRecorded;
        synchronized (pendingResults) {
            fatalRecorded = fatalPause != null;
        }
        if (!fatalRecorded) {
            return false;
        }
        retainPendingResultAfterFatal(result);
        return true;
    }

    private void retainPendingResultsAfterFatal() {
        retryFatalRetention();
    }

    private Throwable disposePendingResults() {
        List<VariationResult> snapshot;
        synchronized (pendingResults) {
            snapshot = new ArrayList<VariationResult>(pendingResults);
        }
        Throwable failure = null;
        for (int i = 0; i < snapshot.size(); i++) {
            failure = mergeFailure(failure,
                    disposePendingResult(snapshot.get(i)));
            if (isFatal(failure)) {
                // commitAndDisposeProducerOwnedResult records the fatal before
                // returning, atomically pausing every remaining claim.
                break;
            }
        }
        return failure;
    }

    private Throwable disposeProducerOwnedResultUnlessFatal(
            VariationResult result) {
        if (result == null) {
            return null;
        }
        boolean pause;
        synchronized (pendingResults) {
            if (!result.hasDirectOwnership()) {
                return null;
            }
            pause = fatalPause != null;
            if (!uncommittedDisposals.add(result)) {
                return null;
            }
        }
        if (pause) {
            retryFatalRetention();
            return null;
        }
        return commitAndDisposeProducerOwnedResult(result);
    }

    private Throwable commitAndDisposeProducerOwnedResult(
            VariationResult result) {
        Throwable failure = runProducerDisposalClaimedHookForTest();
        if (isFatal(failure)) {
            return recordCleanupFatal(failure, result);
        }

        boolean committed;
        boolean pause;
        synchronized (pendingResults) {
            committed = uncommittedDisposals.contains(result);
            pause = committed && fatalPause != null;
            if (committed && !pause) {
                uncommittedDisposals.remove(result);
            }
        }
        if (!committed) {
            // A fatal recorder seized and retained this uncommitted claim.
            return failure;
        }
        if (pause) {
            retryFatalRetention();
            return failure;
        }

        Throwable disposalFailure;
        disposalFailure = runCommittedDisposalHookForTest();
        if (disposalFailure == null) {
            try {
                disposalFailure =
                        VariationCleanupSupport.disposeProducerOwnedRejectedResult(result);
            } catch (Throwable thrownFailure) {
                disposalFailure = thrownFailure;
            }
        }
        failure = mergeFailure(failure, disposalFailure);
        if (isFatal(failure)) {
            failure = recordCleanupFatal(failure, result);
        }
        return failure;
    }

    private Throwable recordCleanupFatal(Throwable fatal,
                                         VariationResult activeResult) {
        if (activeResult != null) {
            // A committed cleanup is no longer in either tracked set. Reintroduce
            // it unconditionally: cleanup may already have transferred ownership
            // before throwing, and fatal registration is identity-idempotent.
            synchronized (pendingResults) {
                uncommittedDisposals.add(activeResult);
            }
        }
        Throwable retentionFailure = recordFatalPause(fatal, null);
        return resolveFatalOutcome(fatal, retentionFailure, false);
    }

    private Throwable retainTrackedClaimsAfterFatal() {
        Set<VariationResult> tracked = Collections.newSetFromMap(
                new IdentityHashMap<VariationResult, Boolean>());
        FatalRetentionHook hook;
        synchronized (pendingResults) {
            tracked.addAll(pendingResults);
            tracked.addAll(uncommittedDisposals);
            hook = fatalRetentionHookForTest;
        }

        Throwable failure = null;
        for (VariationResult result : tracked) {
            boolean registered = false;
            try {
                if (hook != null) {
                    hook.beforeRegister(result);
                }
                // Unconditional by design. A failed earlier attempt may already
                // have transferred ownership, while coordinator registration is
                // identity-idempotent.
                VariationCleanupSupport.retainRejectedResultAfterFatal(result);
                registered = true;
            } catch (Throwable registrationFailure) {
                failure = mergeFailure(failure, registrationFailure);
            }
            if (registered) {
                synchronized (pendingResults) {
                    pendingResults.remove(result);
                    uncommittedDisposals.remove(result);
                }
            }
        }
        return failure;
    }

    private void retryFatalRetention() {
        resolveFatalOutcome(null, retainTrackedClaimsAfterFatal(), false);
    }

    /**
     * Attaches every diagnostic to the first recorded VM-fatal. Only callers at
     * a real surface boundary may claim that canonical fatal for throwing.
     */
    private Throwable resolveFatalOutcome(Throwable primary,
                                          Throwable additional,
                                          boolean claimForSurface) {
        synchronized (pendingResults) {
            Throwable canonical = fatalPause;
            if (canonical == null) {
                return mergeFailure(primary, additional);
            }
            if (primary != null && primary != canonical) {
                addSuppressed(canonical, primary);
            }
            if (additional != null && additional != canonical) {
                addSuppressed(canonical, additional);
            }
            if (fatalPauseSurfaced) {
                return null;
            }
            if (claimForSurface) {
                fatalPauseSurfaced = true;
            }
            return canonical;
        }
    }

    void setProducerDisposalClaimedHookForTest(Runnable hook) {
        synchronized (pendingResults) {
            producerDisposalClaimedHookForTest = hook;
        }
    }

    void setFatalRetentionHookForTest(FatalRetentionHook hook) {
        synchronized (pendingResults) {
            fatalRetentionHookForTest = hook;
        }
    }

    void setCommittedDisposalHookForTest(Runnable hook) {
        synchronized (pendingResults) {
            committedDisposalHookForTest = hook;
        }
    }

    void stagePendingResultForTest(VariationResult result) {
        if (result == null) {
            throw new IllegalArgumentException("result must not be null");
        }
        synchronized (pendingResults) {
            if (fatalPause != null) {
                throw new IllegalStateException("executor is fatal-paused");
            }
            if (!acceptedResults.add(result)) {
                throw new IllegalArgumentException("result was already accepted");
            }
            pendingResults.add(result);
        }
    }

    void recordFatalPauseForTest(Throwable fatal) {
        Throwable retentionFailure = recordFatalPause(fatal, null);
        Throwable fatalToSurface = resolveFatalOutcome(
                fatal, retentionFailure, true);
        if (retentionFailure != null) {
            // Existing tests use this seam as the initiating surface boundary.
            rethrowFailure(fatalToSurface);
        }
    }

    private Throwable runProducerDisposalClaimedHookForTest() {
        Runnable hook;
        synchronized (pendingResults) {
            hook = producerDisposalClaimedHookForTest;
            producerDisposalClaimedHookForTest = null;
        }
        if (hook != null) {
            try {
                hook.run();
            } catch (Throwable failure) {
                return failure;
            }
        }
        return null;
    }

    private Throwable runCommittedDisposalHookForTest() {
        Runnable hook;
        synchronized (pendingResults) {
            hook = committedDisposalHookForTest;
            committedDisposalHookForTest = null;
        }
        if (hook != null) {
            try {
                hook.run();
            } catch (Throwable failure) {
                return failure;
            }
        }
        return null;
    }

    private static Throwable mergeFailure(Throwable primary, Throwable additional) {
        if (additional == null) {
            return primary;
        }
        if (primary == null) {
            return additional;
        }
        if (isFatal(additional) && !isFatal(primary)) {
            addSuppressed(additional, primary);
            return additional;
        }
        addSuppressed(primary, additional);
        return primary;
    }

    private static void addSuppressed(Throwable primary, Throwable suppressed) {
        if (primary == suppressed) {
            return;
        }
        try {
            for (Throwable existing : primary.getSuppressed()) {
                if (existing == suppressed) {
                    return;
                }
            }
            primary.addSuppressed(suppressed);
        } catch (RuntimeException ignored) {
            // Suppression is diagnostic only.
        }
    }

    private static boolean isFatal(Throwable t) {
        return t instanceof ThreadDeath || t instanceof VirtualMachineError;
    }

    private static void rethrowFailure(Throwable t) {
        if (t == null) {
            return;
        }
        if (t instanceof ThreadDeath) {
            throw (ThreadDeath) t;
        }
        if (t instanceof VirtualMachineError) {
            throw (VirtualMachineError) t;
        }
        if (t instanceof RuntimeException) {
            throw (RuntimeException) t;
        }
        if (t instanceof Error) {
            throw (Error) t;
        }
        throw new IllegalStateException("Parameter variation execution failed.", t);
    }

    private static String errorMessage(Throwable error) {
        if (error == null) {
            return "unknown error";
        }
        String message = error.getMessage();
        return message == null || message.trim().isEmpty()
                ? error.getClass().getSimpleName()
                : message.trim();
    }
}
