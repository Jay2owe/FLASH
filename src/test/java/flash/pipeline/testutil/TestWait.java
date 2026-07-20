package flash.pipeline.testutil;

import ij.ImagePlus;
import ij.WindowManager;

import javax.swing.SwingUtilities;
import java.awt.Window;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.LockSupport;

public final class TestWait {
    private TestWait() {
    }

    public interface Condition {
        boolean isMet() throws Exception;
    }

    public static void await(String description, long timeoutMillis, Condition condition)
            throws Exception {
        if (description == null || description.trim().isEmpty()) {
            throw new IllegalArgumentException("description must not be blank");
        }
        if (timeoutMillis <= 0L) {
            throw new IllegalArgumentException("timeoutMillis must be positive");
        }
        if (condition == null) {
            throw new IllegalArgumentException("condition must not be null");
        }
        long timeoutNanos = TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        long started = System.nanoTime();
        while (true) {
            try {
                if (condition.isMet()) {
                    return;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw e;
            }
            long elapsed = System.nanoTime() - started;
            if (elapsed >= timeoutNanos) {
                throw new AssertionError("Timed out after " + timeoutMillis
                        + " ms waiting for: " + description);
            }
            LockSupport.parkNanos(Math.min(TimeUnit.MILLISECONDS.toNanos(10L),
                    timeoutNanos - elapsed));
            if (Thread.interrupted()) {
                Thread.currentThread().interrupt();
                throw new InterruptedException("Interrupted while waiting for: " + description);
            }
        }
    }

    public static void until(String message, Condition condition, long timeoutMillis)
            throws Exception {
        await(message, timeoutMillis, condition);
    }

    public static void awaitLatch(String description, CountDownLatch latch,
                                  long timeoutMillis) throws InterruptedException {
        if (!latch.await(timeoutMillis, TimeUnit.MILLISECONDS)) {
            throw new AssertionError("Timed out after " + timeoutMillis
                    + " ms waiting for: " + description);
        }
    }

    public static <T> T get(String description, Future<T> future, long timeoutMillis)
            throws Exception {
        try {
            return future.get(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new AssertionError("Timed out after " + timeoutMillis
                    + " ms waiting for: " + description, e);
        }
    }

    public static void shutdown(String description, ExecutorService executor,
                                long timeoutMillis) throws InterruptedException {
        executor.shutdown();
        if (!executor.awaitTermination(timeoutMillis, TimeUnit.MILLISECONDS)) {
            executor.shutdownNow();
            if (!executor.awaitTermination(timeoutMillis, TimeUnit.MILLISECONDS)) {
                throw new AssertionError("Timed out after " + timeoutMillis
                        + " ms waiting for executor shutdown: " + description);
            }
        }
    }

    public static ResourceSnapshot snapshotResources() {
        drainEventDispatchThread();
        return new ResourceSnapshot(currentThreads(), currentWindows(), currentImages());
    }

    public static final class ResourceSnapshot {
        private final Set<Thread> threads;
        private final Set<Window> windows;
        private final Set<ImagePlus> images;

        private ResourceSnapshot(Set<Thread> threads, Set<Window> windows,
                                 Set<ImagePlus> images) {
            this.threads = threads;
            this.windows = windows;
            this.images = images;
        }

        public void assertNoLeaks(String owner, long timeoutMillis) throws Exception {
            try {
                await(owner + " to release its threads, windows, and ImageJ images",
                        timeoutMillis, new Condition() {
                            @Override public boolean isMet() {
                                drainEventDispatchThread();
                                return leaks().isEmpty();
                            }
                        });
            } catch (AssertionError timeout) {
                List<String> leaked = leaks();
                throw new AssertionError(owner + " leaked resources: " + join(leaked), timeout);
            }
        }

        private List<String> leaks() {
            List<String> leaked = new ArrayList<String>();
            for (Thread thread : currentThreads()) {
                if (!threads.contains(thread)) {
                    leaked.add("thread '" + thread.getName() + "' (#" + thread.getId() + ")");
                }
            }
            for (Window window : currentWindows()) {
                if (!windows.contains(window)) {
                    leaked.add("window '" + window.getName() + "' ("
                            + window.getClass().getName() + ")");
                }
            }
            for (ImagePlus image : currentImages()) {
                if (!images.contains(image)) {
                    leaked.add("ImageJ image '" + image.getTitle() + "' (#"
                            + image.getID() + ")");
                }
            }
            Collections.sort(leaked);
            return leaked;
        }
    }

    private static Set<Thread> currentThreads() {
        Set<Thread> result = identitySet();
        for (Thread thread : Thread.getAllStackTraces().keySet()) {
            if (thread.isAlive() && !thread.isDaemon()) {
                result.add(thread);
            }
        }
        return result;
    }

    private static Set<Window> currentWindows() {
        Set<Window> result = identitySet();
        for (Window window : Window.getWindows()) {
            if (window.isDisplayable()) {
                result.add(window);
            }
        }
        return result;
    }

    private static Set<ImagePlus> currentImages() {
        Set<ImagePlus> result = identitySet();
        int[] ids = WindowManager.getIDList();
        if (ids != null) {
            for (int id : ids) {
                ImagePlus image = WindowManager.getImage(id);
                if (image != null) {
                    result.add(image);
                }
            }
        }
        ImagePlus temporary = WindowManager.getTempCurrentImage();
        if (temporary != null) {
            result.add(temporary);
        }
        return result;
    }

    private static <T> Set<T> identitySet() {
        return Collections.newSetFromMap(new IdentityHashMap<T, Boolean>());
    }

    private static String join(List<String> values) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) result.append(", ");
            result.append(values.get(i));
        }
        return result.toString();
    }

    private static void drainEventDispatchThread() {
        if (SwingUtilities.isEventDispatchThread()) return;
        try {
            SwingUtilities.invokeAndWait(new Runnable() {
                @Override public void run() {
                    // Queue barrier.
                }
            });
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while draining the event-dispatch thread", e);
        } catch (InvocationTargetException e) {
            throw new AssertionError("Event-dispatch thread failed while draining", e.getCause());
        }
    }
}
