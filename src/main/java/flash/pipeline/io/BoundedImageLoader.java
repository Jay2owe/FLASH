package flash.pipeline.io;

import ij.IJ;
import ij.ImagePlus;

import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Bounded producer-consumer image loader. Loads images from a
 * {@link DeferredImageSupplier} (or TIF cache) into a bounded queue,
 * providing natural backpressure so only N images are in memory at once.
 * <p>
 * Supports configurable number of producer (loader) threads for
 * parallel I/O. When the TIF cache is enabled, each entry is reused only if
 * its source identity and source-local series still match. Missing or invalid
 * entries are loaded from the container and atomically refreshed.
 */
public class BoundedImageLoader implements AutoCloseable {

    private static final long DEFAULT_CLOSE_TIMEOUT_MILLIS = 5000L;
    private static final AtomicInteger LOADER_THREAD_SEQUENCE = new AtomicInteger(0);

    /**
     * Observable cancellation state. {@link #INCOMPLETE} means the bounded
     * close deadline expired while at least one producer thread was physically
     * alive; another {@link #close()} call can converge after that thread exits.
     */
    public enum ShutdownState {
        OPEN,
        CANCELLATION_REQUESTED,
        INCOMPLETE,
        COMPLETE
    }

    /**
     * Raised when cooperative cancellation cannot physically join every
     * producer within the configured close deadline.
     */
    public static final class CleanupIncompleteException
            extends IllegalStateException {
        private final int liveProducerCount;

        CleanupIncompleteException(int liveProducerCount) {
            super("Image loader cleanup is incomplete: " + liveProducerCount
                    + " producer thread(s) have not exited");
            this.liveProducerCount = liveProducerCount;
        }

        public int getLiveProducerCount() {
            return liveProducerCount;
        }
    }

    /** Wrapper pairing an image with its series index. */
    public static class IndexedImage {
        public final int index;
        public final ImagePlus image;

        public IndexedImage(int index, ImagePlus image) {
            this.index = index;
            this.image = image;
        }
    }

    private final ArrayBlockingQueue<IndexedImage> queue;
    private final DeferredImageSupplier supplier;
    private final List<Integer> indicesToLoad;
    private final int loaderThreads;
    private final boolean useTifCache;
    private final String directory;
    private final long closeTimeoutNanos;
    private final AtomicInteger nextLoadIndex = new AtomicInteger(0);
    private final AtomicInteger producersFinished = new AtomicInteger(0);
    private final AtomicBoolean allProducersDone = new AtomicBoolean(false);
    private final AtomicBoolean started = new AtomicBoolean(false);
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicReference<ShutdownState> shutdownState =
            new AtomicReference<ShutdownState>(ShutdownState.OPEN);
    private final AtomicReference<Throwable> loadFailure =
            new AtomicReference<Throwable>();
    private final CopyOnWriteArrayList<Thread> producerThreads =
            new CopyOnWriteArrayList<Thread>();
    private volatile ExecutorService loaderPool;
    private volatile int expectedProducers;
    /** Optional series names from .lif metadata, indexed by series number. */
    private List<String> seriesNames;

    /**
     * Creates a loader with a single producer thread, no TIF cache.
     */
    public BoundedImageLoader(DeferredImageSupplier supplier,
                              List<Integer> indicesToLoad, int bufferSize) {
        this(supplier, indicesToLoad, bufferSize, 1, false, null);
    }

    /**
     * Creates a loader with configurable producer thread count, no TIF cache.
     */
    public BoundedImageLoader(DeferredImageSupplier supplier,
                              List<Integer> indicesToLoad, int bufferSize,
                              int loaderThreads) {
        this(supplier, indicesToLoad, bufferSize, loaderThreads, false, null);
    }

    /**
     * Creates a loader with full configuration.
     *
     * @param supplier       the deferred supplier for on-demand series loading
     * @param indicesToLoad  which series indices to load (pre-filtered by skip-existing)
     * @param bufferSize     max images to keep in the queue (2-4 recommended)
     * @param loaderThreads  number of producer threads for parallel I/O
     * @param useTifCache    if true, save/load from TIF cache
     * @param directory      working directory (needed for TIF cache location)
     */
    public BoundedImageLoader(DeferredImageSupplier supplier,
                              List<Integer> indicesToLoad, int bufferSize,
                              int loaderThreads, boolean useTifCache,
                              String directory) {
        this(supplier, indicesToLoad, bufferSize, loaderThreads, useTifCache,
                directory, DEFAULT_CLOSE_TIMEOUT_MILLIS);
    }

    BoundedImageLoader(DeferredImageSupplier supplier,
                       List<Integer> indicesToLoad, int bufferSize,
                       int loaderThreads, boolean useTifCache,
                       String directory, long closeTimeoutMillis) {
        if (closeTimeoutMillis <= 0L) {
            throw new IllegalArgumentException("Close timeout must be positive");
        }
        this.supplier = supplier;
        this.indicesToLoad = indicesToLoad;
        this.queue = new ArrayBlockingQueue<IndexedImage>(Math.max(1, bufferSize));
        this.loaderThreads = Math.max(1, loaderThreads);
        this.useTifCache = useTifCache;
        this.directory = directory;
        this.closeTimeoutNanos = TimeUnit.MILLISECONDS.toNanos(closeTimeoutMillis);
    }

    /** Sets series names for logging. Index corresponds to series number. */
    public void setSeriesNames(List<String> names) {
        this.seriesNames = names;
    }

    /** Starts the producer thread(s). Call this before {@link #take()}. */
    public synchronized void start() {
        if (!started.compareAndSet(false, true)) {
            return;
        }
        if (closed.get()) {
            allProducersDone.set(true);
            return;
        }
        if (indicesToLoad.isEmpty()) {
            allProducersDone.set(true);
            return;
        }

        // Loose/input TIFF folders already consist of independently materialized
        // files. Each container/project series may use the shared cache only
        // after it has been bound to its physical source and source-local
        // series index inside the worker that will consume it.
        final boolean cacheConfigured = useTifCache
                && directory != null
                && supplier != null
                && !supplier.isTiffFolderMode();
        final boolean cacheAllowed = cacheConfigured;
        if (cacheAllowed) {
            IJ.log("Verified TIF cache enabled - matching source-bound entries will be reused");
        }

        int effectiveLoaders = Math.min(loaderThreads, indicesToLoad.size());
        expectedProducers = effectiveLoaders;
        if (effectiveLoaders > 1) {
            IJ.log("Image loading: " + effectiveLoaders + " loader threads (parallel I/O)");
        }

        loaderPool = Executors.newFixedThreadPool(effectiveLoaders, new ThreadFactory() {
            @Override
            public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable, "FLASH-ImageLoader-"
                        + LOADER_THREAD_SEQUENCE.incrementAndGet());
                // A native/library image open may ignore interruption. Java
                // cannot forcibly stop it, so an abandoned producer must not
                // keep the JVM alive after bounded cooperative cancellation.
                thread.setDaemon(true);
                producerThreads.add(thread);
                return thread;
            }
        });
        for (int t = 0; t < effectiveLoaders; t++) {
            final int loaderNum = t + 1;
            loaderPool.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        while (!closed.get()) {
                            int i = nextLoadIndex.getAndIncrement();
                            if (i >= indicesToLoad.size()) break;

                            int idx = indicesToLoad.get(i);
                            String seriesLabel = "series " + (idx + 1);
                            ImagePlus ownedImage = null;
                            try {
                                String loaderTag = effectiveLoaders > 1
                                        ? "Loader " + loaderNum : "Loader";
                                seriesLabel = (seriesNames != null && idx < seriesNames.size()
                                        && seriesNames.get(idx) != null)
                                        ? seriesNames.get(idx) : "series " + (idx + 1);
                                IJ.log(loaderTag + ": loading " + seriesLabel
                                        + " [" + (i + 1) + "/" + indicesToLoad.size() + "]");
                                IJ.showStatus("Loading " + seriesLabel
                                        + " (" + (i + 1) + "/" + indicesToLoad.size() + ")...");

                                TifCache.CacheRequest request = null;
                                if (cacheAllowed) {
                                    // Refresh the identity immediately before using the entry;
                                    // start() may have preceded this worker by several seconds.
                                    try {
                                        request = TifCache.requestFor(
                                                supplier.getContainerFileForSeries(idx),
                                                supplier.getLocalSeriesIndexForSeries(idx));
                                    } catch (Exception identityFailure) {
                                        request = null;
                                        IJ.log("WARNING: TIFF cache bypassed for series "
                                                + (idx + 1) + " because source provenance "
                                                + "could not be refreshed: "
                                                + identityFailure.getMessage());
                                    }
                                }
                                ownedImage = cacheAllowed && request != null
                                        ? TifCache.loadSingle(directory, idx, request)
                                        : null;
                                if (ownedImage == null) {
                                    // A cache entry that changed after the initial validation
                                    // is a miss, never a reason to serve unverified pixels.
                                    ownedImage = supplier.openSeriesMaterialized(idx);
                                    if (cacheAllowed && ownedImage != null) {
                                        try {
                                            // Re-fingerprint immediately after the source open so
                                            // a replacement cannot inherit an earlier identity.
                                            TifCache.CacheRequest freshRequest = TifCache.requestFor(
                                                    supplier.getContainerFileForSeries(idx),
                                                    supplier.getLocalSeriesIndexForSeries(idx));
                                            if (TifCache.sameRequest(request, freshRequest)) {
                                                TifCache.saveToCache(directory, ownedImage, idx, freshRequest);
                                            } else {
                                                IJ.log("WARNING: TIFF cache not refreshed for series "
                                                        + (idx + 1) + " because its source changed "
                                                        + "while the image was loading");
                                            }
                                        } catch (Exception cacheFailure) {
                                            // Caching is optional. Keep the freshly loaded pixels and
                                            // leave the prior completed generation untouched.
                                            IJ.log("WARNING: Could not refresh TIFF cache for series "
                                                    + (idx + 1) + ": " + cacheFailure.getMessage());
                                        }
                                    }
                                }

                                if (ownedImage == null) {
                                    IJ.log("WARNING: Series " + (idx + 1) + " returned null");
                                    recordLoadFailure("Series " + (idx + 1)
                                            + " (" + seriesLabel + ") returned null");
                                    continue;
                                }
                                if (closed.get()) {
                                    break;
                                }

                                String title = ownedImage.getTitle();
                                int dashIdx = title.lastIndexOf(" - ");
                                String shortName = dashIdx >= 0 ? title.substring(dashIdx + 3) : title;
                                IJ.log(loaderTag + ": loaded " + shortName
                                        + " [" + (i + 1) + "/" + indicesToLoad.size() + "]");
                                IndexedImage queuedImage = new IndexedImage(idx, ownedImage);
                                queue.put(queuedImage);
                                ownedImage = null; // queue or consumer now owns the image
                                if (closed.get() && queue.remove(queuedImage)) {
                                    // Cancellation may race the final closed check and
                                    // queue transfer. Reclaim only if no consumer did.
                                    closeImage(queuedImage.image);
                                }
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                break;
                            } catch (Exception e) {
                                IJ.log("WARNING: Failed to load series " + (idx + 1) + ": " + e.getMessage());
                                recordLoadFailure("Failed to load series " + (idx + 1)
                                        + " (" + seriesLabel + ")", e);
                            } finally {
                                closeImage(ownedImage);
                            }
                        }
                    } catch (Throwable failure) {
                        recordLoadFailure(failure);
                        if (isVmFatal(failure)) {
                            throw (Error) failure;
                        }
                    } finally {
                        // Do not set allProducersDone here: this thread is still
                        // physically alive until its finally block returns.
                        producersFinished.incrementAndGet();
                    }
                }
            });
        }
        loaderPool.shutdown();
    }

    /**
     * Takes the next image from the queue. Blocks until one is available.
     * Returns {@code null} when all images have been consumed.
     *
     * @throws CleanupIncompleteException if cancellation reached its bounded
     *         deadline while a producer remained physically alive
     */
    public IndexedImage take() throws InterruptedException {
        while (true) {
            IndexedImage img = queue.poll(200, TimeUnit.MILLISECONDS);
            if (img != null) return img;

            if (closed.get()) {
                // Queue ownership wins every race: only inspect terminal state
                // after poll failed to transfer an image to this consumer.
                // A VM-fatal producer failure remains primary even when a
                // different producer ignored cancellation past the deadline.
                throwRecordedFatalFailure();

                if (shutdownState.get() == ShutdownState.INCOMPLETE) {
                    if (refreshAllProducersDone() && queue.isEmpty()) {
                        shutdownState.compareAndSet(ShutdownState.INCOMPLETE,
                                ShutdownState.COMPLETE);
                        return null;
                    }
                    int liveProducers = countLiveProducers();
                    if (liveProducers > 0) {
                        throw new CleanupIncompleteException(liveProducers);
                    }
                    // A producer may have exited between the completion check
                    // and live-thread count. Re-evaluate instead of reporting a
                    // false zero-producer incomplete state.
                    continue;
                }
            }
            if (refreshAllProducersDone() && queue.isEmpty()) {
                if (closed.get()) {
                    shutdownState.set(ShutdownState.COMPLETE);
                }
                throwRecordedFailure();
                return null;
            }
        }
    }

    /** Returns the total number of images that will be loaded. */
    public int totalToLoad() {
        return indicesToLoad.size();
    }

    /**
     * Returns the current cancellation state. Observing the state also
     * recognizes a formerly incomplete shutdown once every producer has
     * physically exited and no transferred image remains queued.
     */
    public ShutdownState getShutdownState() {
        if (closed.get() && refreshAllProducersDone() && queue.isEmpty()) {
            shutdownState.set(ShutdownState.COMPLETE);
        }
        return shutdownState.get();
    }

    /** Interrupts the producer threads (for early cancellation). */
    public void cancel() {
        close();
    }

    /**
     * Stops all producers, waits for cooperative termination within a fixed
     * bound, and closes every image that had transferred to the queue.
     * Safe to call repeatedly. If a producer ignores cancellation, the call
     * fails explicitly instead of claiming physical termination; call again
     * after the producer exits to converge the state.
     *
     * @throws CleanupIncompleteException if a producer remains alive at the
     *         bounded deadline
     */
    @Override
    public void close() {
        closed.set(true);
        shutdownState.compareAndSet(ShutdownState.OPEN,
                ShutdownState.CANCELLATION_REQUESTED);
        shutdownState.compareAndSet(ShutdownState.INCOMPLETE,
                ShutdownState.CANCELLATION_REQUESTED);
        long closeDeadlineNanos = System.nanoTime()
                + closeTimeoutNanos;
        boolean restoreInterrupt = Thread.interrupted();
        Thread current = Thread.currentThread();
        ExecutorService pool;
        synchronized (this) {
            pool = loaderPool;
            if (!started.get()) {
                allProducersDone.set(true);
            }
        }
        if (pool != null) {
            pool.shutdownNow();
            if (!producerThreads.contains(current)) {
                long remainingNanos = remainingNanos(closeDeadlineNanos);
                if (remainingNanos > 0L) {
                    try {
                        pool.awaitTermination(remainingNanos, TimeUnit.NANOSECONDS);
                    } catch (InterruptedException e) {
                        restoreInterrupt = true;
                    }
                }
            }

            restoreInterrupt = joinProducerThreads(
                    current, closeDeadlineNanos, restoreInterrupt);
        }
        IndexedImage remaining;
        Throwable closeFailure = null;
        try {
            while ((remaining = queue.poll()) != null) {
                try {
                    closeImage(remaining.image);
                } catch (Throwable failure) {
                    closeFailure = mergeFailure(closeFailure, failure);
                }
            }
            int liveProducers = countLiveProducers();
            if (liveProducers > 0) {
                CleanupIncompleteException incomplete =
                        new CleanupIncompleteException(liveProducers);
                shutdownState.compareAndSet(ShutdownState.CANCELLATION_REQUESTED,
                        ShutdownState.INCOMPLETE);
                try {
                    IJ.log("WARNING: " + incomplete.getMessage());
                } catch (Throwable loggingFailure) {
                    closeFailure = mergeFailure(closeFailure, loggingFailure);
                }
                closeFailure = mergeFailure(incomplete, closeFailure);
            } else {
                // No producer remains physically alive. This is truthful even
                // if an executor task was cancelled before it began running.
                allProducersDone.set(true);
                shutdownState.set(ShutdownState.COMPLETE);
            }
        } finally {
            if (restoreInterrupt) {
                current.interrupt();
            }
        }
        if (closeFailure != null) {
            throwUnchecked(closeFailure);
        }
    }

    private boolean joinProducerThreads(Thread current,
                                        long deadlineNanos,
                                        boolean restoreInterrupt) {
        for (Thread producer : producerThreads) {
            if (producer == null || producer == current) {
                continue;
            }
            while (producer.isAlive()) {
                long remainingNanos = remainingNanos(deadlineNanos);
                if (remainingNanos <= 0L) {
                    return restoreInterrupt;
                }
                long millis = TimeUnit.NANOSECONDS.toMillis(remainingNanos);
                int nanos = (int) (remainingNanos
                        - TimeUnit.MILLISECONDS.toNanos(millis));
                try {
                    producer.join(millis, nanos);
                } catch (InterruptedException e) {
                    // Continue bounded cleanup, then restore the signal to the caller.
                    restoreInterrupt = true;
                }
            }
        }
        return restoreInterrupt;
    }

    private int countLiveProducers() {
        int live = 0;
        for (Thread producer : producerThreads) {
            if (producer != null && producer.isAlive()) {
                live++;
            }
        }
        return live;
    }

    private boolean refreshAllProducersDone() {
        if (allProducersDone.get()) {
            return true;
        }
        if (!started.get()) {
            return false;
        }
        if (expectedProducers == 0) {
            allProducersDone.set(true);
            return true;
        }
        if (producersFinished.get() < expectedProducers
                || countLiveProducers() > 0) {
            return false;
        }
        allProducersDone.set(true);
        return true;
    }

    boolean allProducersDoneForTests() {
        return refreshAllProducersDone();
    }

    private static long remainingNanos(long deadlineNanos) {
        long remaining = deadlineNanos - System.nanoTime();
        return remaining > 0L ? remaining : 0L;
    }

    private void recordLoadFailure(String message) {
        recordLoadFailure(message, null);
    }

    private void recordLoadFailure(String message, Throwable cause) {
        Throwable failure = cause == null
                ? new IllegalStateException(message)
                : new IllegalStateException(message, cause);
        recordLoadFailure(failure);
    }

    private synchronized void recordLoadFailure(Throwable failure) {
        if (failure == null) return;
        Throwable current = loadFailure.get();
        if (current == null) {
            loadFailure.set(failure);
            return;
        }
        if (current == failure) return;

        if (isVmFatal(failure) && !isVmFatal(current)) {
            // Publish the fatal failure first so even a rare suppression
            // allocation failure cannot leave a nonfatal cause as primary.
            loadFailure.set(failure);
            failure.addSuppressed(current);
        } else {
            current.addSuppressed(failure);
        }
    }

    private void throwRecordedFailure() {
        Throwable failure = loadFailure.get();
        if (failure == null || closed.get()) {
            return;
        }
        if (failure instanceof Error) {
            throw (Error) failure;
        }
        if (failure instanceof RuntimeException) {
            throw (RuntimeException) failure;
        }
        throw new IllegalStateException("Image loader failed", failure);
    }

    private void throwRecordedFatalFailure() {
        Throwable failure = loadFailure.get();
        if (isVmFatal(failure)) {
            throw (Error) failure;
        }
    }

    private static boolean isVmFatal(Throwable failure) {
        return failure instanceof VirtualMachineError || failure instanceof ThreadDeath;
    }

    private static void closeImage(ImagePlus image) {
        if (image == null) return;
        // ImageJ cleanup may lazily initialize AWT. Do not present it with an
        // interrupted thread, but restore the caller's cancellation signal.
        boolean restoreInterrupt = Thread.interrupted();
        Throwable failure = null;
        try {
            try {
                image.changes = false;
            } catch (Throwable closeFailure) {
                failure = closeFailure;
            }
            try {
                image.close();
            } catch (Throwable closeFailure) {
                failure = mergeFailure(failure, closeFailure);
            }
            try {
                image.flush();
            } catch (Throwable closeFailure) {
                failure = mergeFailure(failure, closeFailure);
            }
        } finally {
            if (restoreInterrupt) {
                Thread.currentThread().interrupt();
            }
        }
        if (failure != null) {
            throwUnchecked(failure);
        }
    }

    private static Throwable mergeFailure(Throwable primary, Throwable additional) {
        if (primary == null) return additional;
        if (additional != null && additional != primary) {
            if (isVmFatal(additional) && !isVmFatal(primary)) {
                additional.addSuppressed(primary);
                return additional;
            }
            primary.addSuppressed(additional);
        }
        return primary;
    }

    private static void throwUnchecked(Throwable failure) {
        if (failure instanceof Error) throw (Error) failure;
        if (failure instanceof RuntimeException) throw (RuntimeException) failure;
        throw new IllegalStateException("Image loader cleanup failed", failure);
    }
}
