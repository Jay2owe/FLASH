package flash.pipeline.ui.variations;

import flash.pipeline.image.HeapBudget;

import ij.ImagePlus;

import java.awt.Rectangle;

public final class ResourceGuard {

    private ResourceGuard() {
    }

    public static Feasibility assessFeasibility(ParameterSweep sweep, ImagePlus source) {
        if (sweep == null) {
            return new Feasibility(false, 0L, availableBytes(),
                    "No parameter sweep was provided.");
        }
        if (source == null) {
            return new Feasibility(false, 0L, availableBytes(),
                    "No source image was provided.");
        }
        Rectangle crop = sweep.cropSpec().boundsFor(source);
        long cropZ = Math.max(1L, (long) source.getStackSize());
        long cellCount = Math.max(1L, sweep.cellCount());
        long estimated = estimateBytes(crop.width, crop.height, cropZ, cellCount);
        long available = availableBytes();
        boolean ok = estimated <= (long) (available * 0.5d);
        String message = ok
                ? "This sweep fits the current memory budget."
                : "This sweep needs ~" + formatGb(estimated) + " GB, you have ~"
                + formatGb(available) + " GB free. Reduce cell count, crop tighter, or both.";
        return new Feasibility(ok, estimated, available, message);
    }

    private static long availableBytes() {
        long estimated = Math.max(0L, HeapBudget.estimatedAvailable());
        if (estimated > 0L) {
            return estimated;
        }
        long maxMem = Runtime.getRuntime().maxMemory();
        long usedMem = Runtime.getRuntime().totalMemory()
                - Runtime.getRuntime().freeMemory();
        return Math.max(0L, maxMem - usedMem);
    }

    private static long estimateBytes(int cropW, int cropH, long cropZ, long cellCount) {
        double estimated = (double) cropW
                * (double) cropH
                * (double) cropZ
                * 2.0d
                * (double) cellCount
                * 1.5d;
        if (estimated >= Long.MAX_VALUE) {
            return Long.MAX_VALUE;
        }
        return (long) Math.ceil(estimated);
    }

    private static String formatGb(long bytes) {
        double gb = bytes / (1024.0d * 1024.0d * 1024.0d);
        return String.format(java.util.Locale.ROOT, "%.1f", Double.valueOf(gb));
    }

    public static final class Feasibility {
        public final boolean ok;
        public final long estimatedBytes;
        public final long availableBytes;
        public final String message;

        public Feasibility(boolean ok,
                           long estimatedBytes,
                           long availableBytes,
                           String message) {
            this.ok = ok;
            this.estimatedBytes = estimatedBytes;
            this.availableBytes = availableBytes;
            this.message = message == null ? "" : message;
        }

        public boolean isOk() {
            return ok;
        }

        public long getEstimatedBytes() {
            return estimatedBytes;
        }

        public long getAvailableBytes() {
            return availableBytes;
        }

        public String getMessage() {
            return message;
        }
    }
}
