package flash.pipeline.image;

import ij.ImagePlus;

/**
 * Computes how many GPU inference workers the current JVM heap can safely hold
 * in flight at once. Mirrors {@link AdaptiveParallelism#computeSafeThreads} so
 * the GPU semaphore respects the same memory invariant as the CPU-side pools.
 * <p>
 * Heap, not VRAM, is the binding constraint whenever StarDist's Java side
 * buffers the duplicated hyperstack + TrackMate label image while CUDA runs.
 * See {@code docs/GPU_CONCURRENCY/PLAN_1_AUTO_DETECT_AND_UI.md} for the
 * rationale behind composing heap / VRAM / system-RAM budgets.
 */
public final class HeapBudget {

    /** Matches {@link AdaptiveParallelism} — keep in sync if either changes. */
    static final long MIN_FREE_BYTES = 500L * 1024 * 1024;

    /** Per-StarDist-worker heap footprint.
     *  Estimate for a 2048x2048x13-slice 16-bit stack held alongside its label
     *  image and TrackMate intermediate buffers. Err high. */
    public static final long BYTES_PER_STARDIST_WORKER = 1500L * 1024 * 1024;

    /** Conservative FFT working-set multiplier for 3D deconvolution. */
    public static final long DECONVOLUTION_FFT_MULTIPLIER_NUMERATOR = 7L;

    private HeapBudget() {}

    /**
     * Heap-derived permit count, computed without forcing GC.
     *
     * @param bytesPerWorker estimated peak heap per in-flight GPU worker
     * @return {@code max(1, availHeap / bytesPerWorker)}
     */
    public static int heapPermitsFor(long bytesPerWorker) {
        if (bytesPerWorker <= 0L) return 1;
        long maxMem = Runtime.getRuntime().maxMemory();
        long usedMem = Runtime.getRuntime().totalMemory()
                - Runtime.getRuntime().freeMemory();
        long availMem = maxMem - usedMem - MIN_FREE_BYTES;
        if (availMem <= 0L) return 1;
        long permits = availMem / bytesPerWorker;
        if (permits < 1L) return 1;
        if (permits > Integer.MAX_VALUE) return Integer.MAX_VALUE;
        return (int) permits;
    }

    public static long availableHeapBytes() {
        long maxMem = Runtime.getRuntime().maxMemory();
        long usedMem = Runtime.getRuntime().totalMemory()
                - Runtime.getRuntime().freeMemory();
        return maxMem - usedMem - MIN_FREE_BYTES;
    }

    /**
     * Estimates the JVM heap required to deconvolve one single-channel 3D stack.
     * The multiplier folds padded input/output volumes, a complex FFT working
     * buffer, and one extra float workspace into one helper so callers do not
     * hand-roll conflicting formulas.
     */
    public static long requiredFor3DDeconv(ImagePlus stack) {
        if (stack == null) return Long.MAX_VALUE;
        return requiredFor3DDeconv(stackBytes(stack));
    }

    /**
     * Estimated memory available to 3D deconvolution right now.
     * Heap is the primary hard cap; free physical RAM is used only as an
     * upper bound when it is available from the operating system.
     */
    public static long estimatedAvailable() {
        long heap = availableHeapBytes();
        long freeRam = SystemRamBudget.freePhysicalMemoryBytes();
        if (freeRam <= 0L) return heap;
        return Math.min(heap, freeRam);
    }

    static long requiredFor3DDeconv(long stackBytes) {
        if (stackBytes <= 0L) return 0L;
        long scaled = stackBytes * DECONVOLUTION_FFT_MULTIPLIER_NUMERATOR;
        if (scaled / DECONVOLUTION_FFT_MULTIPLIER_NUMERATOR != stackBytes) {
            return Long.MAX_VALUE;
        }
        return scaled;
    }

    static long stackBytes(ImagePlus image) {
        long voxels = (long) image.getWidth() * (long) image.getHeight() * (long) image.getStackSize();
        int bitDepth = image.getBitDepth();
        int bytesPerPixel = Math.max(1, bitDepth / 8);
        if (bitDepth == 24) bytesPerPixel = 4;
        return voxels * bytesPerPixel;
    }
}
