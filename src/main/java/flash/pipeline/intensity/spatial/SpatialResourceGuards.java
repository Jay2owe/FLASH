package flash.pipeline.intensity.spatial;

/**
 * Centralized memory guards for intensity-spatial image products.
 */
final class SpatialResourceGuards {
    static final String MAX_MIP_PIXELS_PROPERTY = "flash.intensity.spatial.maxMipPixels";
    static final String MAX_PAIR_PLANE_PIXELS_PROPERTY = "flash.intensity.spatial.max2dPixels";
    static final String MAX_COLOC_IMAGE_PIXELS_PROPERTY = "flash.intensity.spatial.maxColocPixels";

    private static final int DEFAULT_MAX_MIP_PIXELS = 25_000_000;
    private static final int DEFAULT_MAX_PAIR_PLANE_PIXELS = 8_388_608;
    private static final int DEFAULT_MAX_COLOC_IMAGE_PIXELS = 4_194_304;

    private SpatialResourceGuards() {
    }

    static long pixelCount(int width, int height, String label) {
        if (width <= 0 || height <= 0) return 0L;
        long pixels = (long) width * (long) height;
        if (pixels > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(label + " is too large ("
                    + width + "x" + height + " = " + pixels
                    + " pixels; Java array limit " + Integer.MAX_VALUE + ").");
        }
        return pixels;
    }

    static void checkMipPixels(int width, int height) {
        long pixels = pixelCount(width, height, "MIP projection");
        int maxPixels = intProperty(MAX_MIP_PIXELS_PROPERTY, DEFAULT_MAX_MIP_PIXELS);
        checkPixelLimit(pixels, maxPixels, "MIP projection", MAX_MIP_PIXELS_PROPERTY);
        checkHeapBudget(pixels * 4L, "MIP projection");
    }

    static void checkPairPlanePixels(int width, int height) {
        long pixels = pixelCount(width, height, "2D intensity-spatial pair plane");
        int maxPixels = intProperty(MAX_PAIR_PLANE_PIXELS_PROPERTY, DEFAULT_MAX_PAIR_PLANE_PIXELS);
        checkPixelLimit(pixels, maxPixels, "2D intensity-spatial pair plane",
                MAX_PAIR_PLANE_PIXELS_PROPERTY);
        checkHeapBudget(pixels * 20L, "2D intensity-spatial pair plane");
    }

    static boolean colocImagesAllowed(int pixelCount) {
        int maxPixels = intProperty(MAX_COLOC_IMAGE_PIXELS_PROPERTY,
                DEFAULT_MAX_COLOC_IMAGE_PIXELS);
        return pixelCount <= maxPixels;
    }

    static void checkColocImagePixels(int pixelCount) {
        int maxPixels = intProperty(MAX_COLOC_IMAGE_PIXELS_PROPERTY,
                DEFAULT_MAX_COLOC_IMAGE_PIXELS);
        checkPixelLimit(pixelCount, maxPixels, "Coloc 2 image conversion",
                MAX_COLOC_IMAGE_PIXELS_PROPERTY);
        checkHeapBudget(pixelCount * 12L, "Coloc 2 image conversion");
    }

    static int maxColocImagePixels() {
        return intProperty(MAX_COLOC_IMAGE_PIXELS_PROPERTY, DEFAULT_MAX_COLOC_IMAGE_PIXELS);
    }

    private static void checkPixelLimit(long pixels,
                                        int maxPixels,
                                        String label,
                                        String propertyName) {
        if (pixels <= maxPixels) return;
        throw new IllegalArgumentException(label + " skipped for " + pixels
                + " pixels; guard limit is " + maxPixels
                + " pixels. To override, set -D" + propertyName + "=<pixels>.");
    }

    private static void checkHeapBudget(long estimatedBytes, String label) {
        Runtime runtime = Runtime.getRuntime();
        long max = runtime.maxMemory();
        if (max == Long.MAX_VALUE) return;
        long used = runtime.totalMemory() - runtime.freeMemory();
        long available = max - used;
        long reserve = Math.max(64L * 1024L * 1024L, max / 4L);
        if (estimatedBytes > Math.max(0L, available - reserve)) {
            throw new IllegalArgumentException(label + " skipped because estimated allocation "
                    + formatBytes(estimatedBytes) + " would leave less than "
                    + formatBytes(reserve) + " heap reserve.");
        }
    }

    private static int intProperty(String name, int fallback) {
        String raw = System.getProperty(name);
        if (raw == null || raw.trim().isEmpty()) return fallback;
        try {
            int value = Integer.parseInt(raw.trim());
            return value > 0 ? value : fallback;
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private static String formatBytes(long bytes) {
        long safe = Math.max(0L, bytes);
        long mb = 1024L * 1024L;
        if (safe < mb) {
            return safe + " bytes";
        }
        return (safe / mb) + " MB";
    }
}
