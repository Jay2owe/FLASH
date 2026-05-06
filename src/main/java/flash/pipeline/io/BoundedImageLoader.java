package flash.pipeline.io;

import ij.IJ;
import ij.ImagePlus;

import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Bounded producer-consumer image loader. Loads images from a
 * {@link DeferredImageSupplier} (or TIF cache) into a bounded queue,
 * providing natural backpressure so only N images are in memory at once.
 * <p>
 * Supports configurable number of producer (loader) threads for
 * parallel I/O. When TIF cache is enabled and a cache exists, images
 * are loaded from fast individual TIF files instead of re-parsing the .lif.
 * When the cache doesn't exist yet, images are loaded from .lif and
 * simultaneously saved to the cache for future runs.
 */
public class BoundedImageLoader {

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
    private final AtomicInteger nextLoadIndex = new AtomicInteger(0);
    private final AtomicInteger producersFinished = new AtomicInteger(0);
    private final AtomicBoolean allProducersDone = new AtomicBoolean(false);
    private ExecutorService loaderPool;
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
        this.supplier = supplier;
        this.indicesToLoad = indicesToLoad;
        this.queue = new ArrayBlockingQueue<IndexedImage>(Math.max(1, bufferSize));
        this.loaderThreads = Math.max(1, loaderThreads);
        this.useTifCache = useTifCache;
        this.directory = directory;
    }

    /** Sets series names for logging. Index corresponds to series number. */
    public void setSeriesNames(List<String> names) {
        this.seriesNames = names;
    }

    /** Starts the producer thread(s). Call this before {@link #take()}. */
    public void start() {
        if (indicesToLoad.isEmpty()) {
            allProducersDone.set(true);
            return;
        }

        // Always load from cache if it exists and has all required images,
        // even if the user has cache-saving turned off.
        final boolean cacheHit = directory != null && TifCache.cacheExists(directory)
                && TifCache.cacheSize(directory) >= indicesToLoad.size();
        final boolean cacheMiss = useTifCache && directory != null && !cacheHit;

        if (cacheHit) {
            IJ.log("Loading from TIF cache (" + TifCache.cacheSize(directory) + " cached images)");
        } else if (cacheMiss) {
            IJ.log("TIF cache enabled — images will be cached for future runs");
        }

        int effectiveLoaders = Math.min(loaderThreads, indicesToLoad.size());
        if (effectiveLoaders > 1) {
            IJ.log("Image loading: " + effectiveLoaders + " loader threads (parallel I/O)");
        }

        loaderPool = Executors.newFixedThreadPool(effectiveLoaders);
        for (int t = 0; t < effectiveLoaders; t++) {
            final int loaderNum = t + 1;
            loaderPool.submit(new Runnable() {
                @Override
                public void run() {
                    while (true) {
                        int i = nextLoadIndex.getAndIncrement();
                        if (i >= indicesToLoad.size()) break;

                        int idx = indicesToLoad.get(i);
                        try {
                            String loaderTag = effectiveLoaders > 1
                                    ? "Loader " + loaderNum : "Loader";
                            String seriesLabel = (seriesNames != null && idx < seriesNames.size()
                                    && seriesNames.get(idx) != null)
                                    ? seriesNames.get(idx) : "series " + (idx + 1);
                            IJ.log(loaderTag + ": loading " + seriesLabel
                                    + " [" + (i + 1) + "/" + indicesToLoad.size() + "]");
                            IJ.showStatus("Loading " + seriesLabel
                                    + " (" + (i + 1) + "/" + indicesToLoad.size() + ")...");

                            ImagePlus imp;
                            if (cacheHit) {
                                // Load from cached TIF (fast)
                                imp = TifCache.loadSingle(directory, idx);
                            } else {
                                // Load from .lif via Bio-Formats
                                imp = supplier.openSeriesMaterialized(idx);
                                // Save to cache for next time
                                if (cacheMiss && imp != null) {
                                    TifCache.saveToCache(directory, imp, idx);
                                }
                            }

                            if (imp != null) {
                                String title = imp.getTitle();
                                int dashIdx = title.lastIndexOf(" - ");
                                String shortName = dashIdx >= 0 ? title.substring(dashIdx + 3) : title;
                                IJ.log(loaderTag + ": loaded " + shortName
                                        + " [" + (i + 1) + "/" + indicesToLoad.size() + "]");
                                queue.put(new IndexedImage(idx, imp));
                            } else {
                                IJ.log("WARNING: Series " + (idx + 1) + " returned null, skipping");
                            }
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            break;
                        } catch (Exception e) {
                            IJ.log("WARNING: Failed to load series " + (idx + 1) + ": " + e.getMessage());
                        }
                    }
                    if (producersFinished.incrementAndGet() >= Math.min(loaderThreads, indicesToLoad.size())) {
                        allProducersDone.set(true);
                    }
                }
            });
        }
        loaderPool.shutdown();
    }

    /**
     * Takes the next image from the queue. Blocks until one is available.
     * Returns {@code null} when all images have been consumed.
     */
    public IndexedImage take() throws InterruptedException {
        while (true) {
            IndexedImage img = queue.poll(200, TimeUnit.MILLISECONDS);
            if (img != null) return img;
            if (allProducersDone.get() && queue.isEmpty()) return null;
        }
    }

    /** Returns the total number of images that will be loaded. */
    public int totalToLoad() {
        return indicesToLoad.size();
    }

    /** Interrupts the producer threads (for early cancellation). */
    public void cancel() {
        if (loaderPool != null) {
            loaderPool.shutdownNow();
        }
        IndexedImage remaining;
        while ((remaining = queue.poll()) != null) {
            remaining.image.changes = false;
            remaining.image.close();
            remaining.image.flush();
        }
    }
}
