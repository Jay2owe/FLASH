package flash.pipeline.io;

import ij.IJ;
import ij.ImagePlus;

import java.util.ArrayList;
import java.util.List;

/**
 * Lazy image cache shared across analyses within a pipeline session.
 * Images are loaded on first request and reused for subsequent analyses.
 * Each caller should duplicate images before destructive processing.
 */
public class ImageCache {

    private List<ImagePlus> cached = null;
    private long loadTimeMs = 0;
    private String cachedDirectory = null;

    /**
     * Returns the cached images for the given directory, loading them if needed.
     * The returned list is the shared cache — callers MUST duplicate before modifying.
     *
     * @param directory the project/input directory
     * @return the list of loaded images, or null if loading failed
     */
    public List<ImagePlus> getImages(String directory) {
        if (cached != null && directory.equals(cachedDirectory)) {
            IJ.log("Using cached images (" + cached.size() + " series, loaded in "
                    + formatDuration(loadTimeMs) + ")");
            return cached;
        }
        // Directory changed — release old cache
        release();

        IJ.showStatus("Opening source images...");
        IJ.showProgress(0);
        long t0 = System.currentTimeMillis();
        try {
            cached = openAllMaterialized(directory);
        } catch (Exception e) {
            IJ.log("WARNING: " + e.getMessage());
            return null;
        }
        loadTimeMs = System.currentTimeMillis() - t0;
        cachedDirectory = directory;
        IJ.log("Loaded " + cached.size() + " images in " + formatDuration(loadTimeMs));
        IJ.showStatus("Loaded " + cached.size() + " images");
        IJ.showProgress(1.0);
        return cached;
    }

    private List<ImagePlus> openAllMaterialized(String directory) throws Exception {
        DeferredImageSupplier supplier = ImageSourceDispatcher.createSupplier(directory);
        List<ImagePlus> images = new ArrayList<ImagePlus>();
        try {
            int total = supplier.getTotalSeries();
            for (int series = 0; series < total; series++) {
                IJ.showProgress(series, Math.max(1, total));
                images.add(supplier.openSeriesMaterialized(series));
            }
        } finally {
            supplier.shutdownPrefetch();
        }
        return images;
    }

    /**
     * Duplicates all cached images into a new list safe for destructive processing.
     * Returns null if no cache exists for the directory.
     */
    public List<ImagePlus> duplicateImages(String directory) {
        List<ImagePlus> source = getImages(directory);
        if (source == null) return null;
        List<ImagePlus> copies = new ArrayList<ImagePlus>(source.size());
        for (ImagePlus imp : source) {
            copies.add(imp.duplicate());
        }
        return copies;
    }

    /** Returns the time taken to load the .lif file (ms). */
    public long getLoadTimeMs() {
        return loadTimeMs;
    }

    /** Closes all cached images and frees memory. */
    public void release() {
        if (cached != null) {
            for (ImagePlus imp : cached) {
                if (imp != null) {
                    imp.changes = false;
                    imp.close();
                    imp.flush();
                }
            }
            cached = null;
            cachedDirectory = null;
            loadTimeMs = 0;
        }
    }

    private static String formatDuration(long ms) {
        long seconds = ms / 1000;
        if (seconds < 60) return seconds + "s";
        long minutes = seconds / 60;
        long remSec = seconds % 60;
        if (minutes < 60) return minutes + "m " + remSec + "s";
        long hours = minutes / 60;
        long remMin = minutes % 60;
        return hours + "h " + remMin + "m";
    }
}
