package flash.pipeline.ui.variations;

import javax.swing.SwingUtilities;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;

/** Shared failure and EDT handling for variation ownership cleanup. */
public final class VariationCleanupSupport {

    interface Task {
        void run();
    }

    private VariationCleanupSupport() {
    }

    static void runOnEdtAndWait(final Task task) {
        if (task == null) {
            return;
        }
        if (SwingUtilities.isEventDispatchThread()) {
            task.run();
            return;
        }
        FutureTask<Throwable> future = new FutureTask<Throwable>(
                new Callable<Throwable>() {
                    @Override public Throwable call() {
                        try {
                            task.run();
                            return null;
                        } catch (Throwable failure) {
                            return failure;
                        }
                    }
                });
        SwingUtilities.invokeLater(future);
        boolean restoreInterrupt = Thread.interrupted();
        Throwable failure = null;
        for (;;) {
            try {
                failure = future.get();
                break;
            } catch (InterruptedException interrupted) {
                restoreInterrupt = true;
            } catch (ExecutionException execution) {
                failure = execution.getCause();
                break;
            }
        }
        if (VariationResult.containsInterruptedFailure(failure)) {
            restoreInterrupt = true;
        }
        if (restoreInterrupt) {
            Thread.currentThread().interrupt();
        }
        rethrow(failure);
    }

    public static Throwable disposeRejectedResult(VariationResult result) {
        if (result == null) {
            return null;
        }
        Throwable failure = null;
        result.transferOwnership();
        for (int pass = 0; pass < 8
                && result.pendingTransferredImages().length > 0; pass++) {
            try {
                result.releaseTransferredImages();
            } catch (Throwable transferredFailure) {
                failure = merge(failure, transferredFailure);
                if (isVmFatal(failure)) {
                    break;
                }
            }
        }
        if (result.pendingTransferredImages().length > 0) {
            if (isVmFatal(failure)) {
                VariationCleanupCoordinator.registerResultFatal(result);
            } else {
                VariationCleanupCoordinator.registerResult(result);
            }
        }
        return failure;
    }

    /** Retains a producer-owned result without invoking its disposer after a sibling fatal. */
    static void retainRejectedResultAfterFatal(VariationResult result) {
        if (result == null) {
            return;
        }
        result.transferOwnership();
        VariationCleanupCoordinator.registerResultFatal(result);
    }

    /** Rejects a result only while its producer still owns the image lease. */
    public static Throwable disposeProducerOwnedRejectedResult(
            VariationResult result) {
        if (result == null || !result.hasDirectOwnership()) {
            return null;
        }
        return disposeRejectedResult(result);
    }

    static Throwable merge(Throwable primary, Throwable additional) {
        if (additional == null) {
            return primary;
        }
        if (primary == null) {
            return additional;
        }
        if (isVmFatal(additional) && !isVmFatal(primary)) {
            addSuppressed(additional, primary);
            return additional;
        }
        addSuppressed(primary, additional);
        return primary;
    }

    static void rethrow(Throwable failure) {
        if (failure == null) {
            return;
        }
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
        throw new IllegalStateException("Could not dispose variation images.", failure);
    }

    static boolean isVmFatal(Throwable failure) {
        return failure instanceof ThreadDeath || failure instanceof VirtualMachineError;
    }

    static void rethrowFatalAfterRegisteringCells(
            Throwable failure, List<VariationCellPanel> cells) {
        if (!isVmFatal(failure)) {
            return;
        }
        VariationCleanupCoordinator.registerCellsFatal(cells);
        rethrow(failure);
    }

    private static void addSuppressed(Throwable primary, Throwable suppressed) {
        if (primary == suppressed) {
            return;
        }
        try {
            primary.addSuppressed(suppressed);
        } catch (RuntimeException ignored) {
            // Suppression is diagnostic only.
        }
    }
}
