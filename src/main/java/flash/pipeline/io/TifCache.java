package flash.pipeline.io;

import flash.pipeline.intelligence.JunkFileFilter;
import flash.pipeline.naming.ChannelFilenameCodec;

import ij.IJ;
import ij.ImagePlus;
import ij.io.FileSaver;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Manages a directory of raw multi-channel TIF files cached from a .lif file.
 * On first load, images are saved as individual TIFs to {@code FLASH/Cache/TIF/}.
 * Subsequent analyses load from the cache instead of re-parsing the .lif.
 */
public class TifCache {

    /** Cache directory name, created inside the working directory. */
    public static final String CACHE_DIR = FlashProjectLayout.TIF_CACHE_DIR;
    public static final String LEGACY_CACHE_DIR = FlashProjectLayout.LEGACY_TIF_CACHE_DIR;

    /**
     * Returns the write cache directory for the given working directory.
     */
    public static File getCacheDir(String directory) {
        return FlashProjectLayout.forDirectory(directory).tifCacheWriteDir();
    }

    /**
     * Checks if a TIF cache exists and contains at least one .tif file.
     */
    public static boolean cacheExists(String directory) {
        File cacheDir = firstExistingCacheDir(directory);
        if (!cacheDir.isDirectory()) return false;
        File[] tifs = listTifs(cacheDir);
        return tifs != null && tifs.length > 0;
    }

    /**
     * Saves a single image to the cache directory as a TIFF.
     * The filename is the image title with .tif extension.
     * Thread-safe: each call writes to a unique file.
     *
     * @param directory the working directory (cache dir is created inside)
     * @param imp       the image to save
     * @param index     the series index (used for filename ordering)
     */
    public static void saveToCache(String directory, ImagePlus imp, int index) {
        File cacheDir = getCacheDir(directory);
        if (!cacheDir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            cacheDir.mkdirs();
        }
        String safeName = sanitizeFilename(imp.getTitle());
        // Prefix with zero-padded index to preserve order
        String filename = String.format(Locale.ROOT, "%04d_%s.tif", index, safeName);
        File outFile = new File(cacheDir, filename);
        FileSaver saver = new FileSaver(imp);
        if (imp.getStackSize() > 1) {
            saver.saveAsTiffStack(outFile.getAbsolutePath());
        } else {
            saver.saveAsTiff(outFile.getAbsolutePath());
        }
    }

    /**
     * Loads all cached TIF files from the cache directory.
     * Returns them in filename order (which preserves original series order).
     *
     * @param directory the working directory
     * @return list of loaded images, or empty list if no cache
     */
    public static List<ImagePlus> loadAll(String directory) {
        File cacheDir = firstExistingCacheDir(directory);
        File[] tifs = listTifs(cacheDir);
        if (tifs == null || tifs.length == 0) return new ArrayList<ImagePlus>();

        Arrays.sort(tifs, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                return a.getName().compareTo(b.getName());
            }
        });

        List<ImagePlus> images = new ArrayList<ImagePlus>();
        for (File tif : tifs) {
            ImagePlus imp = IJ.openImage(tif.getAbsolutePath());
            if (imp != null) {
                // Restore original title by stripping the index prefix
                String name = tif.getName();
                if (name.length() > 5 && name.charAt(4) == '_') {
                    name = name.substring(5);
                }
                if (name.endsWith(".tif")) {
                    name = name.substring(0, name.length() - 4);
                }
                imp.setTitle(name);
                images.add(imp);
            }
        }
        return images;
    }

    /**
     * Opens a single cached TIF by its index in filename order.
     *
     * @param directory the working directory
     * @param index     zero-based index into sorted file list
     * @return the loaded image, or null if not found
     */
    public static ImagePlus loadSingle(String directory, int index) {
        File cacheDir = firstExistingCacheDir(directory);
        File[] tifs = listTifs(cacheDir);
        File tif = findTifForSeries(tifs, index);
        if (tif == null) return null;
        ImagePlus imp = IJ.openImage(tif.getAbsolutePath());
        if (imp != null) {
            String name = tif.getName();
            if (name.length() > 5 && name.charAt(4) == '_') {
                name = name.substring(5);
            }
            if (name.endsWith(".tif")) {
                name = name.substring(0, name.length() - 4);
            }
            imp.setTitle(name);
        }
        return imp;
    }

    /**
     * Returns true only when every requested source series has a matching
     * zero-padded cached TIFF. A raw file count is not enough because
     * skip-existing runs may request sparse series indices.
     */
    public static boolean hasAllSeries(String directory, List<Integer> indices) {
        if (indices == null || indices.isEmpty()) return false;
        File cacheDir = firstExistingCacheDir(directory);
        File[] tifs = listTifs(cacheDir);
        if (tifs == null || tifs.length == 0) return false;
        for (Integer index : indices) {
            if (index == null || findTifForSeries(tifs, index.intValue()) == null) {
                return false;
            }
        }
        return true;
    }

    /**
     * Returns the number of cached TIF files.
     */
    public static int cacheSize(String directory) {
        File cacheDir = firstExistingCacheDir(directory);
        File[] tifs = listTifs(cacheDir);
        return tifs == null ? 0 : tifs.length;
    }

    private static File firstExistingCacheDir(String directory) {
        List<File> dirs = FlashProjectLayout.forDirectory(directory).tifCacheReadDirs();
        for (int i = 0; i < dirs.size(); i++) {
            File dir = dirs.get(i);
            if (dir.isDirectory()) return dir;
        }
        return dirs.get(0);
    }

    private static File[] listTifs(File dir) {
        if (dir == null || !dir.isDirectory()) return null;
        File[] files = JunkFileFilter.listCleanFiles(dir);
        List<File> tifs = new ArrayList<File>();
        for (File file : files) {
            if (file.getName().toLowerCase(Locale.ROOT).endsWith(".tif")) {
                tifs.add(file);
            }
        }
        return tifs.toArray(new File[tifs.size()]);
    }

    private static File findTifForSeries(File[] tifs, int index) {
        if (tifs == null || index < 0) return null;
        String prefix = String.format(Locale.ROOT, "%04d_", index);
        for (File tif : tifs) {
            if (tif.getName().startsWith(prefix)) {
                return tif;
            }
        }
        return null;
    }

    private static String sanitizeFilename(String title) {
        if (title == null) return "image";
        return ChannelFilenameCodec.toSafe(title);
    }
}
