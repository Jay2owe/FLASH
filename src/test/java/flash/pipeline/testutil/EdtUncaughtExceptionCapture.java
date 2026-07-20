package flash.pipeline.testutil;

import javax.swing.SwingUtilities;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Captures exceptions escaping an EDT callback without changing worker behavior. */
public final class EdtUncaughtExceptionCapture implements AutoCloseable {

    private final AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
    private final AtomicInteger failureCount = new AtomicInteger();
    private Thread installedThread;
    private Thread.UncaughtExceptionHandler previousHandler;

    public static EdtUncaughtExceptionCapture install() throws Exception {
        final EdtUncaughtExceptionCapture capture = new EdtUncaughtExceptionCapture();
        SwingUtilities.invokeAndWait(new Runnable() {
            @Override public void run() {
                capture.installedThread = Thread.currentThread();
                capture.previousHandler = capture.installedThread.getUncaughtExceptionHandler();
                capture.installedThread.setUncaughtExceptionHandler(
                        new Thread.UncaughtExceptionHandler() {
                            @Override public void uncaughtException(Thread thread, Throwable error) {
                                capture.failureCount.incrementAndGet();
                                capture.failure.compareAndSet(null, error);
                            }
                        });
            }
        });
        return capture;
    }

    public Throwable failure() {
        return failure.get();
    }

    public int count() {
        return failureCount.get();
    }

    @Override public void close() throws Exception {
        SwingUtilities.invokeAndWait(new Runnable() {
            @Override public void run() {
                if (Thread.currentThread() == installedThread) {
                    installedThread.setUncaughtExceptionHandler(previousHandler);
                }
            }
        });
    }
}
