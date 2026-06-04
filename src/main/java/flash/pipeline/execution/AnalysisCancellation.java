package flash.pipeline.execution;

/**
 * Tracks explicit GUI cancel actions while an analysis is being configured or
 * run. Swing cancel callbacks usually execute on the event thread, so this is
 * process-scoped rather than thread-local.
 */
public final class AnalysisCancellation {

    private static final Object LOCK = new Object();
    private static Scope activeScope;
    private static int cancelGeneration;

    private AnalysisCancellation() {
    }

    public static Scope openGuiAnalysisScope() {
        synchronized (LOCK) {
            Scope scope = new Scope(cancelGeneration);
            activeScope = scope;
            return scope;
        }
    }

    public static void markDialogCancelRequested() {
        synchronized (LOCK) {
            if (activeScope != null && !activeScope.closed) {
                cancelGeneration++;
            }
        }
    }

    public static boolean wasCancelRequestedInActiveScope() {
        synchronized (LOCK) {
            return activeScope != null && activeScope.wasCancelRequestedLocked();
        }
    }

    public static final class Scope implements AutoCloseable {
        private final int startGeneration;
        private boolean closed;

        private Scope(int startGeneration) {
            this.startGeneration = startGeneration;
        }

        public boolean wasCancelRequested() {
            synchronized (LOCK) {
                return wasCancelRequestedLocked();
            }
        }

        private boolean wasCancelRequestedLocked() {
            return !closed && cancelGeneration > startGeneration;
        }

        @Override
        public void close() {
            synchronized (LOCK) {
                if (closed) {
                    return;
                }
                closed = true;
                if (activeScope == this) {
                    activeScope = null;
                }
            }
        }
    }
}
