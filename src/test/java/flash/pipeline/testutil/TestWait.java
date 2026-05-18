package flash.pipeline.testutil;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

public final class TestWait {
    private TestWait() {
    }

    public interface Condition {
        boolean isMet() throws Exception;
    }

    public static void until(String message, Condition condition, long timeoutMillis)
            throws Exception {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        Throwable lastFailure = null;
        while (System.nanoTime() <= deadline) {
            try {
                if (condition.isMet()) {
                    return;
                }
            } catch (Throwable t) {
                lastFailure = t;
            }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10L));
        }
        AssertionError error = new AssertionError(message);
        if (lastFailure != null) {
            error.initCause(lastFailure);
        }
        throw error;
    }
}
