package flash.pipeline.image;

import java.lang.management.ManagementFactory;

/**
 * Computes how many Cellpose Python subprocesses system RAM can safely hold.
 * Cellpose does its work outside the JVM, so heap is irrelevant — what matters
 * is physical free memory at probe time (with a fixed OS reserve).
 * <p>
 * Uses {@code com.sun.management.OperatingSystemMXBean} via reflection so the
 * compile-time target stays Java 8 / Fiji-compatible and we never link against
 * a Sun-internal symbol.
 */
public final class SystemRamBudget {

    /** Reserve for OS, Fiji, and user apps. */
    private static final long OS_RESERVE_BYTES = 2L * 1024 * 1024 * 1024;

    /** Per-Cellpose-subprocess RAM footprint estimate.
     *  Cellpose-SAM at 512x512x20 loads ~2 GiB of weights + working set.
     *  Err high. */
    public static final long BYTES_PER_CELLPOSE_WORKER = 3L * 1024 * 1024 * 1024;

    private SystemRamBudget() {}

    /**
     * RAM-derived permit count for Cellpose.
     * @return {@code max(1, (freeRam - OS_RESERVE) / bytesPerWorker)} or
     *         {@code 1} if the MXBean is unavailable.
     */
    public static int ramPermitsFor(long bytesPerWorker) {
        if (bytesPerWorker <= 0L) return 1;
        long free = freePhysicalMemoryBytes();
        if (free <= 0L) return 1;
        long avail = free - OS_RESERVE_BYTES;
        if (avail <= 0L) return 1;
        long permits = avail / bytesPerWorker;
        if (permits < 1L) return 1;
        if (permits > Integer.MAX_VALUE) return Integer.MAX_VALUE;
        return (int) permits;
    }

    /**
     * Free physical memory (bytes). Falls back to {@code 0} when the Sun-private
     * OperatingSystemMXBean is unavailable (non-Oracle/OpenJDK, restricted
     * reflection, or stripped runtime).
     */
    public static long freePhysicalMemoryBytes() {
        try {
            java.lang.management.OperatingSystemMXBean bean =
                    ManagementFactory.getOperatingSystemMXBean();
            // Prefer the modern Java 14+ getFreeMemorySize(); fall back to the
            // Sun-private getFreePhysicalMemorySize() on Java 8 / Fiji.
            Long v = invokeLongNoArg(bean, "getFreeMemorySize");
            if (v != null) return v;
            v = invokeLongNoArg(bean, "getFreePhysicalMemorySize");
            if (v != null) return v;
        } catch (Throwable ignored) { }
        return 0L;
    }

    public static long totalPhysicalMemoryBytes() {
        try {
            java.lang.management.OperatingSystemMXBean bean =
                    ManagementFactory.getOperatingSystemMXBean();
            Long v = invokeLongNoArg(bean, "getTotalMemorySize");
            if (v != null) return v;
            v = invokeLongNoArg(bean, "getTotalPhysicalMemorySize");
            if (v != null) return v;
        } catch (Throwable ignored) { }
        return 0L;
    }

    private static Long invokeLongNoArg(Object target, String methodName) {
        try {
            java.lang.reflect.Method m = target.getClass().getMethod(methodName);
            m.setAccessible(true);
            Object out = m.invoke(target);
            if (out instanceof Number) return ((Number) out).longValue();
        } catch (Throwable ignored) { }
        return null;
    }
}
