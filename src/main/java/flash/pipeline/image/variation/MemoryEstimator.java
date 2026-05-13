package flash.pipeline.image.variation;

import ij.IJ;
import ij.ImagePlus;

import java.util.Locale;

/**
 * Projects the memory pressure of a variant run before dispatching it.
 */
public final class MemoryEstimator {

    public static final double OVERHEAD_FACTOR = 1.3;
    public static final double BUDGET_FRACTION = 0.25;

    private MemoryEstimator() {}

    public static MemoryEstimate estimate(ImagePlus source, int variantCount) {
        return estimate(source, variantCount, IJ.maxMemory());
    }

    static MemoryEstimate estimate(ImagePlus source, int variantCount, long maxHeapBytes) {
        if (variantCount < 1) {
            throw new IllegalArgumentException("variantCount must be >= 1, was " + variantCount);
        }
        if (maxHeapBytes < 1L) {
            throw new IllegalArgumentException("maxHeapBytes must be >= 1, was " + maxHeapBytes);
        }

        long sourceBytes = computeSourceBytes(source);
        long projected = (long) (sourceBytes * (double) variantCount * OVERHEAD_FACTOR);
        double headroom = (double) projected / (double) maxHeapBytes;
        boolean exceeds = headroom > BUDGET_FRACTION;
        String message = format(variantCount, sourceBytes, projected,
                maxHeapBytes, headroom, exceeds);
        return new MemoryEstimate(sourceBytes, projected, maxHeapBytes,
                headroom, exceeds, message);
    }

    static long computeSourceBytes(ImagePlus imp) {
        if (imp == null) return 0L;
        long width = imp.getWidth();
        long height = imp.getHeight();
        long stackSize = imp.getStackSize();
        if (stackSize < 1L) stackSize = 1L;

        int bytesPerPixel;
        int bitDepth = imp.getBitDepth();
        if (bitDepth == 24) {
            bytesPerPixel = 4;
        } else if (bitDepth <= 0) {
            bytesPerPixel = 1;
        } else {
            bytesPerPixel = bitDepth / 8;
            if (bytesPerPixel < 1) bytesPerPixel = 1;
        }
        return width * height * stackSize * (long) bytesPerPixel;
    }

    private static String format(int variantCount, long sourceBytes, long projected,
                                 long maxHeap, double headroom, boolean exceeds) {
        String verdict = exceeds ? " - ROI mode required" : " - fits in budget";
        return String.format(Locale.US,
                "%d variants x %s = %s (%.0f%% of %s heap)%s",
                Integer.valueOf(variantCount),
                formatGiB(sourceBytes),
                formatGiB(projected),
                Double.valueOf(headroom * 100.0),
                formatGiB(maxHeap),
                verdict);
    }

    private static String formatGiB(long bytes) {
        double gib = bytes / (double) (1L << 30);
        return String.format(Locale.US, "%.1f GiB", Double.valueOf(gib));
    }
}
