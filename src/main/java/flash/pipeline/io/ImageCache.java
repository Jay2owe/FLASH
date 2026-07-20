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
        DeferredImageSupplier supplier = createSupplier(directory);
        List<ImagePlus> images = new ArrayList<ImagePlus>();
        Throwable failure = null;
        try {
            if (supplier == null) {
                throw new IllegalStateException("Image source supplier was null for " + directory);
            }
            int total = supplier.getTotalSeries();
            for (int series = 0; series < total; series++) {
                IJ.showProgress(series, Math.max(1, total));
                ImagePlus image = supplier.openSeriesMaterialized(series);
                if (image == null) {
                    throw new IllegalStateException("Image source returned null for series "
                            + (series + 1) + " of " + total);
                }
                images.add(image);
            }
        } catch (Throwable loadFailure) {
            failure = loadFailure;
        }
        if (supplier != null) {
            try {
                supplier.shutdownPrefetch();
            } catch (Throwable shutdownFailure) {
                failure = mergeFailure(failure, shutdownFailure);
            }
        }
        if (failure != null) {
            failure = closeImages(images, failure);
            rethrow(failure);
        }
        return images;
    }

    /** Test seam for deterministic supplier failures without real Bio-Formats I/O. */
    DeferredImageSupplier createSupplier(String directory) throws Exception {
        return ImageSourceDispatcher.createSupplier(directory);
    }

    /**
     * Duplicates all cached images into a new list safe for destructive processing.
     * Returns null if no cache exists for the directory.
     */
    public List<ImagePlus> duplicateImages(String directory) {
        List<ImagePlus> source = getImages(directory);
        if (source == null) return null;
        List<ImagePlus> copies = new ArrayList<ImagePlus>(source.size());
        try {
            for (ImagePlus imp : source) {
                ImagePlus copy = imp.duplicate();
                if (copy == null) {
                    throw new IllegalStateException("Image duplication returned null");
                }
                copies.add(copy);
            }
            return copies;
        } catch (Throwable failure) {
            throwUnchecked(closeImages(copies, failure));
            return null; // unreachable
        }
    }

    /** Returns the time taken to load the image source (ms). */
    public long getLoadTimeMs() {
        return loadTimeMs;
    }

    /** Closes all cached images and frees memory. */
    public void release() {
        List<ImagePlus> toClose = cached;
        cached = null;
        cachedDirectory = null;
        loadTimeMs = 0;
        if (toClose != null) {
            Throwable failure = closeImages(toClose, null);
            if (failure != null) {
                throwUnchecked(failure);
            }
        }
    }

    private static Throwable closeImages(List<ImagePlus> images, Throwable failure) {
        for (ImagePlus image : images) {
            if (image == null) continue;
            try {
                image.changes = false;
            } catch (Throwable closeFailure) {
                failure = mergeFailure(failure, closeFailure);
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
        }
        return failure;
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

    private static boolean isVmFatal(Throwable failure) {
        return failure instanceof VirtualMachineError || failure instanceof ThreadDeath;
    }

    private static void rethrow(Throwable failure) throws Exception {
        if (failure instanceof Exception) throw (Exception) failure;
        if (failure instanceof Error) throw (Error) failure;
        throw new IllegalStateException("Image loading failed", failure);
    }

    private static void throwUnchecked(Throwable failure) {
        if (failure instanceof Error) throw (Error) failure;
        if (failure instanceof RuntimeException) throw (RuntimeException) failure;
        throw new IllegalStateException("Image cleanup failed", failure);
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
