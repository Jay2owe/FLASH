package flash.pipeline.image;

import ij.IJ;
import ij.ImagePlus;
import flash.pipeline.io.DeferredImageSupplier;

/**
 * Computes safe parallelism levels based on available JVM heap memory
 * and estimated per-image memory requirements.
 *
 * <p>Guardrail for future parallel work: if each worker materializes one or more
 * images or label stacks in memory, do not size the pool from
 * {@code Runtime.getRuntime().availableProcessors()} alone. Route worker counts
 * through this class so memory pressure constrains parallelism before the JVM or
 * OS is forced into emergency recovery.
 */
public class AdaptiveParallelism {

    /** Minimum free memory to maintain (500 MB). */
    private static final long MIN_FREE_BYTES = 500L * 1024 * 1024;

    /**
     * Estimates memory in bytes for one fully materialized image.
     * For a 16-bit hyperstack: width * height * 2 * nSlices * nChannels
     *
     * @param width         image width in pixels
     * @param height        image height in pixels
     * @param nSlices       number of Z slices
     * @param nChannels     number of channels
     * @param bytesPerPixel bytes per pixel (1 for 8-bit, 2 for 16-bit, 4 for 32-bit)
     * @return estimated bytes for the full image
     */
    public static long estimateImageBytes(int width, int height, int nSlices,
                                           int nChannels, int bytesPerPixel) {
        return (long) width * height * bytesPerPixel * nSlices * nChannels;
    }

    /**
     * Computes how many images can safely be processed in parallel
     * given current memory conditions and estimated per-image size.
     *
     * @param bytesPerImage estimated memory per image in bytes
     * @param requestedThreads the number of threads the user requested
     * @return safe thread count (at least 1, at most requestedThreads)
     */
    public static int computeSafeThreads(long bytesPerImage, int requestedThreads) {
        long maxMem = Runtime.getRuntime().maxMemory();
        long usedMem = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
        long availMem = maxMem - usedMem - MIN_FREE_BYTES;

        if (availMem <= 0) {
            // Memory is tight - force GC and re-check
            System.gc();
            try { Thread.sleep(100); } catch (InterruptedException e) { /* ignore */ }
            usedMem = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
            availMem = maxMem - usedMem - MIN_FREE_BYTES;
        }

        if (availMem <= 0 || bytesPerImage <= 0) return 1;

        int memThreads = (int) (availMem / bytesPerImage);
        int safe = Math.max(1, Math.min(memThreads, requestedThreads));
        return safe;
    }

    /**
     * Computes a memory-safe worker count for tasks that materialize data fully
     * in memory per worker.
     *
     * <p>Use this for CPU pools where each worker opens full label images,
     * duplicates hyperstacks, or otherwise allocates large independent buffers.
     * The result is clamped both by the amount of work available and by current
     * heap headroom, so callers do not accidentally launch one worker per core
     * when RAM only supports a smaller number.
     *
     * @param bytesPerWorker estimated bytes held by one worker while active
     * @param requestedThreads user-requested or caller-requested worker count
     * @param taskCount number of work items that could run in parallel
     * @return safe worker count in {@code [1, min(requestedThreads, taskCount)]}
     */
    public static int computeSafeThreadsForInMemoryTasks(long bytesPerWorker,
                                                         int requestedThreads,
                                                         int taskCount) {
        int requested = Math.max(1, Math.min(Math.max(1, requestedThreads), Math.max(1, taskCount)));
        if (taskCount <= 0 || bytesPerWorker <= 0L) {
            return requested;
        }
        return computeSafeThreads(bytesPerWorker, requested);
    }

    /**
     * Computes safe threads and logs the decision.
     * Opens series 0 from the supplier to estimate image dimensions.
     *
     * @param supplier         the image supplier (already initialized)
     * @param requestedThreads the user's requested thread count
     * @param bufferSize       the BoundedImageLoader queue size
     * @return safe thread count
     */
    public static int computeAndLog(DeferredImageSupplier supplier,
                                     int requestedThreads, int bufferSize) {
        long maxMem = Runtime.getRuntime().maxMemory();
        long usedMem = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
        long availMem = maxMem - usedMem - MIN_FREE_BYTES;

        // Try to estimate from the first series metadata without heavy pixel loading
        long estimatedBytes = 0;
        try {
            ImagePlus sample = supplier.openSeries(0);
            if (sample != null) {
                estimatedBytes = estimateImageBytes(
                    sample.getWidth(), sample.getHeight(),
                    sample.getNSlices(), sample.getNChannels(),
                    sample.getBitDepth() / 8);
                sample.close();
                sample.flush();
            }
        } catch (Exception e) {
            // Fall back to a conservative estimate (500 MB per image)
            estimatedBytes = 500L * 1024 * 1024;
        }

        // Account for buffer: total in-flight = workers + bufferSize
        long totalNeeded = estimatedBytes * (requestedThreads + bufferSize);

        int safe;
        if (totalNeeded > availMem && availMem > 0 && estimatedBytes > 0) {
            // Reduce threads to fit in memory (buffer size is fixed)
            int maxWorkers = Math.max(1, (int) ((availMem / estimatedBytes) - bufferSize));
            safe = Math.max(1, Math.min(maxWorkers, requestedThreads));
            IJ.log("Memory-adaptive parallelism: reduced from " + requestedThreads
                    + " to " + safe + " threads");
        } else {
            safe = Math.max(1, requestedThreads);
        }

        IJ.log("  Max heap: " + formatMB(maxMem) + " MB");
        IJ.log("  In use: " + formatMB(usedMem) + " MB");
        IJ.log("  Available: " + formatMB(availMem) + " MB");
        IJ.log("  Est. per image: " + formatMB(estimatedBytes) + " MB");
        IJ.log("  Threads: " + safe + " (requested " + requestedThreads + ")");

        return safe;
    }

    private static long formatMB(long bytes) {
        return bytes / (1024 * 1024);
    }
}
