package flash.pipeline.io;

import flash.pipeline.runtime.BioFormatsRuntime;
import ij.IJ;
import ij.ImagePlus;
import flash.pipeline.intelligence.JunkFileFilter;

import java.io.File;
import java.io.IOException;
import java.nio.file.LinkOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import loci.formats.ImageReader;
import loci.formats.Memoizer;
import loci.plugins.BF;
import loci.plugins.in.ImporterOptions;

/**
 * Utility for scanning a directory for a single .lif file and opening its series via Bio-Formats.
 */
public class LifIO {

    /**
     * Returns all {@code .lif} files in the given directory, sorted
     * case-insensitively by name for deterministic ordering.
     *
     * @param directory path to search
     * @return a sorted list of {@code .lif} files (never null, may be empty)
     */
    public static List<File> listLifFiles(String directory) {
        File dir = new File(directory);
        if (!dir.isDirectory()) return new ArrayList<File>();

        File[] candidates = JunkFileFilter.listCleanFiles(dir);
        List<File> sorted = new ArrayList<File>();
        for (File candidate : candidates) {
            if (candidate.getName().toLowerCase(Locale.ROOT).endsWith(".lif")
                    && !Files.isSymbolicLink(candidate.toPath())) {
                sorted.add(candidate);
            }
        }
        if (sorted.isEmpty()) return sorted;

        Collections.sort(sorted, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                return a.getName().compareToIgnoreCase(b.getName());
            }
        });
        return sorted;
    }

    /**
     * Returns exactly one {@code .lif} file from the directory.
     *
     * @param directory path containing the {@code .lif} file
     * @return the single {@code .lif} file
     * @throws IllegalArgumentException if no {@code .lif} file exists or
     *         more than one is present (message includes all conflicting filenames)
     */
    public static File requireSingleLifFile(String directory) {
        List<File> lifs = listLifFiles(directory);
        if (lifs.isEmpty()) {
            throw new IllegalArgumentException("No .lif file found in: " + directory);
        }
        if (lifs.size() > 1) {
            StringBuilder names = new StringBuilder();
            for (int i = 0; i < lifs.size(); i++) {
                if (i > 0) names.append(", ");
                names.append(lifs.get(i).getName());
            }
            throw new IllegalArgumentException(
                    "Multiple .lif files found in directory (expected exactly one): "
                    + names + "\nDirectory: " + directory);
        }
        return lifs.get(0);
    }

    /**
     * Opens all series from the directory's single .lif file (virtual stacks).
     */
    public static List<ImagePlus> openAllSeries(String directory) throws Exception {
        return openAllSeries(directory, true);
    }

    /**
     * Opens all series from the directory's single .lif file.
     *
     * @param directory the directory containing a .lif file
     * @param virtual   true for virtual stacks (sequential use only),
     *                  false to materialize pixels (safe for parallel threads)
     */
    public static List<ImagePlus> openAllSeries(String directory, boolean virtual) throws Exception {
        return openAllImages(requireSingleLifFile(directory), virtual);
    }

    /**
     * Opens all series from a .lif file (headless, no UI).
     * Uses virtual stacks so slices are read from disk on demand,
     * reducing RAM usage for large files.
     */
    public static List<ImagePlus> openAllImages(File lifFile) throws Exception {
        return openAllImages(lifFile, true);
    }

    /**
     * Opens all series from a .lif file with configurable virtual stack mode.
     *
     * @param lifFile the .lif file
     * @param virtual true for virtual stacks (low RAM, shared reader — NOT thread-safe for parallel),
     *                false to fully materialize all pixel data (thread-safe, higher RAM)
     */
    public static List<ImagePlus> openAllImages(File lifFile, boolean virtual) throws Exception {
        BioFormatsRuntime.markUsage();
        lifFile = requireReadableLifFile(lifFile);
        if (virtual) {
            // Virtual stacks: open series-by-series rather than via
            // setOpenAllSeries(true). Per the project quirks list and the
            // LifIO non-virtual path, setOpenAllSeries can confuse the
            // Bio-Formats series UI/state and is avoided everywhere else.
            int totalSeries = getSeriesCount(lifFile);
            List<ImagePlus> out = new ArrayList<ImagePlus>();
            for (int s = 0; s < totalSeries; s++) {
                ImporterOptions options = new ImporterOptions();
                options.setId(lifFile.getAbsolutePath());
                options.setAutoscale(true);
                options.setColorMode(ImporterOptions.COLOR_MODE_DEFAULT);
                options.setStackFormat(ImporterOptions.VIEW_STANDARD);
                options.setVirtual(true);
                for (int i = 0; i < totalSeries; i++) {
                    options.setSeriesOn(i, i == s);
                }
                ImagePlus[] imps = BF.openImagePlus(options);
                if (imps != null) {
                    for (ImagePlus imp : imps) {
                        if (imp != null) out.add(imp);
                    }
                }
            }
            return out;
        }

        // Non-virtual: parallel load each series with its own reader
        return openAllImagesParallel(lifFile);
    }

    /**
     * Opens all series from a .lif file in parallel. Each series gets its own
     * Bio-Formats reader so pixel reading happens concurrently.
     */
    private static List<ImagePlus> openAllImagesParallel(final File lifFile) throws Exception {
        BioFormatsRuntime.markUsage();
        final int totalSeries = getSeriesCount(lifFile);
        if (totalSeries == 0) return new ArrayList<ImagePlus>();

        int nThreads = Math.min(totalSeries, Math.max(1, Runtime.getRuntime().availableProcessors() / 2));
        IJ.log("Loading " + totalSeries + " images using " + nThreads + " threads...");

        ExecutorService pool = Executors.newFixedThreadPool(nThreads);
        final String lifPath = lifFile.getAbsolutePath();

        // Submit one task per series
        List<Future<ImagePlus>> futures = new ArrayList<Future<ImagePlus>>();
        for (int s = 0; s < totalSeries; s++) {
            final int seriesIdx = s;
            futures.add(pool.submit(new Callable<ImagePlus>() {
                @Override
                public ImagePlus call() throws Exception {
                    ImporterOptions opts = new ImporterOptions();
                    opts.setId(lifPath);
                    opts.setOpenAllSeries(false);
                    opts.setAutoscale(true);
                    opts.setColorMode(ImporterOptions.COLOR_MODE_DEFAULT);
                    opts.setStackFormat(ImporterOptions.VIEW_STANDARD);
                    opts.setVirtual(false);
                    for (int i = 0; i < totalSeries; i++) {
                        opts.setSeriesOn(i, i == seriesIdx);
                    }
                    ImagePlus[] result = BF.openImagePlus(opts);
                    return (result != null && result.length > 0) ? result[0] : null;
                }
            }));
        }

        // Collect results in order, with progress
        List<ImagePlus> out = new ArrayList<ImagePlus>();
        for (int s = 0; s < futures.size(); s++) {
            try {
                ImagePlus imp = futures.get(s).get();
                if (imp != null) out.add(imp);
            } catch (Exception e) {
                IJ.log("WARNING: Failed to load series " + (s + 1) + ": " + e.getMessage());
            }
            IJ.showProgress(s + 1, totalSeries);
            IJ.showStatus("Loading images... " + (s + 1) + "/" + totalSeries);
        }
        pool.shutdown();
        IJ.showStatus("Loaded " + out.size() + " images");
        return out;
    }

    /**
     * Opens the Bio-Formats import dialog so the user can manually select
     * which series to open. Does NOT call setOpenAllSeries — the standard
     * Bio-Formats UI will show checkboxes for each series.
     */
    public static List<ImagePlus> openWithUI(File lifFile) throws Exception {
        BioFormatsRuntime.markUsage();
        lifFile = requireReadableLifFile(lifFile);
        ImporterOptions options = new ImporterOptions();
        options.setId(lifFile.getAbsolutePath());
        options.setWindowless(false);
        options.setAutoscale(true);
        options.setColorMode(ImporterOptions.COLOR_MODE_DEFAULT);
        options.setStackFormat(ImporterOptions.VIEW_STANDARD);
        options.setVirtual(true);
        // Don't call setOpenAllSeries — let the user pick via the UI

        ImagePlus[] imps = BF.openImagePlus(options);
        return toList(imps);
    }

    /**
     * Randomly selects {@code count} series from a .lif file and opens them,
     * bypassing the Bio-Formats UI entirely.
     *
     * @param lifFile the .lif file to read from
     * @param count   how many random series to open (clamped to total available)
     */
    public static List<ImagePlus> openRandomSeries(File lifFile, int count) throws Exception {
        BioFormatsRuntime.markUsage();
        lifFile = requireReadableLifFile(lifFile);
        // First, determine total series count using a lightweight reader
        int totalSeries;
        try (Memoizer reader = new Memoizer(new ImageReader())) {
            reader.setId(lifFile.getAbsolutePath());
            totalSeries = reader.getSeriesCount();
        }

        if (totalSeries <= 0) {
            IJ.log("Warning: no series found in " + lifFile.getName());
            return new ArrayList<>();
        }

        // Clamp count
        int toOpen = Math.min(count, totalSeries);

        // Build shuffled index list, take first 'toOpen'
        List<Integer> indices = new ArrayList<>();
        for (int i = 0; i < totalSeries; i++) indices.add(i);
        Collections.shuffle(indices);
        List<Integer> selected = new ArrayList<Integer>(indices.subList(0, toOpen));
        Collections.sort(selected); // open in order for consistency

        IJ.log("Randomly selected " + toOpen + "/" + totalSeries + " series from " + lifFile.getName());
        return openSelectedSeries(lifFile, selected);
    }

    /**
     * Opens only the requested series indices from a .lif file.
     * Invalid indices are ignored.
     */
    public static List<ImagePlus> openSelectedSeries(File lifFile, List<Integer> selectedSeriesIndices) throws Exception {
        lifFile = requireReadableLifFile(lifFile);
        if (selectedSeriesIndices == null || selectedSeriesIndices.isEmpty()) {
            return new ArrayList<ImagePlus>();
        }

        BioFormatsRuntime.markUsage();
        int totalSeries;
        try (Memoizer reader = new Memoizer(new ImageReader())) {
            reader.setId(lifFile.getAbsolutePath());
            totalSeries = reader.getSeriesCount();
        }

        if (totalSeries <= 0) {
            IJ.log("Warning: no series found in " + lifFile.getName());
            return new ArrayList<ImagePlus>();
        }

        Set<Integer> selected = new LinkedHashSet<Integer>();
        for (Integer index : selectedSeriesIndices) {
            if (index == null) continue;
            if (index >= 0 && index < totalSeries) {
                selected.add(index);
            }
        }

        if (selected.isEmpty()) {
            IJ.log("Warning: no valid series indices selected from " + lifFile.getName());
            return new ArrayList<ImagePlus>();
        }

        ImporterOptions options = new ImporterOptions();
        options.setId(lifFile.getAbsolutePath());
        options.setWindowless(true);
        options.setAutoscale(true);
        options.setColorMode(ImporterOptions.COLOR_MODE_DEFAULT);
        options.setStackFormat(ImporterOptions.VIEW_STANDARD);
        options.setVirtual(true);

        // Explicitly enable only selected series
        for (int i = 0; i < totalSeries; i++) {
            options.setSeriesOn(i, selected.contains(i));
        }

        ImagePlus[] imps = BF.openImagePlus(options);
        return toList(imps);
    }

    /**
     * Creates a {@link DeferredImageSupplier} for the directory's single .lif file.
     * The supplier opens one series at a time, keeping memory usage low.
     *
     * @param directory the directory containing exactly one .lif file
     * @return a deferred supplier for on-demand series opening
     * @throws Exception if no .lif file is found or the file cannot be read
     */
    public static DeferredImageSupplier createDeferredSupplier(String directory) throws Exception {
        return new DeferredImageSupplier(requireSingleLifFile(directory));
    }

    /**
     * Returns the total number of series in a .lif file without opening any images.
     * Uses Memoizer so subsequent calls skip metadata parsing.
     */
    public static int getSeriesCount(File lifFile) throws Exception {
        BioFormatsRuntime.markUsage();
        lifFile = requireReadableLifFile(lifFile);
        try (Memoizer reader = new Memoizer(new ImageReader())) {
            reader.setId(lifFile.getAbsolutePath());
            return reader.getSeriesCount();
        }
    }

    /**
     * Reads calibration, Z-slice count, and series name for every series in a
     * .lif file without loading any pixel data. Uses Bio-Formats'
     * {@code loci.formats.meta.MetadataRetrieve} to obtain physical pixel sizes.
     */
    public static List<SeriesMeta> readAllSeriesMetadata(File lifFile) throws Exception {
        BioFormatsRuntime.markUsage();
        lifFile = requireReadableLifFile(lifFile);
        List<SeriesMeta> metas = new ArrayList<>();
        loci.formats.meta.MetadataStore store = loci.formats.MetadataTools.createOMEXMLMetadata();
        try (Memoizer reader = new Memoizer(new ImageReader())) {
            reader.setMetadataStore(store);
            reader.setId(lifFile.getAbsolutePath());
            int total = reader.getSeriesCount();
            loci.formats.meta.MetadataRetrieve retrieve = (loci.formats.meta.MetadataRetrieve) store;
            for (int s = 0; s < total; s++) {
                reader.setSeries(s);
                int sizeX = reader.getSizeX();
                int sizeY = reader.getSizeY();
                int sizeZ = reader.getSizeZ();
                int sizeC = reader.getSizeC();
                String name = null;
                double pw = 1.0, ph = 1.0, pd = 1.0;
                String unit = "pixel";
                try {
                    name = retrieve.getImageName(s);
                    Object pxW = retrieve.getPixelsPhysicalSizeX(s);
                    Object pxH = retrieve.getPixelsPhysicalSizeY(s);
                    Object pxD = retrieve.getPixelsPhysicalSizeZ(s);
                    if (pxW != null) {
                        // Length.value(UNITS.MICROMETER) via reflection to avoid compile dep
                        java.lang.reflect.Method valueMethod = pxW.getClass().getMethod("value", Class.forName("ome.units.unit.Unit"));
                        Object microUnit = Class.forName("ome.units.UNITS").getField("MICROMETER").get(null);
                        Number val = (Number) valueMethod.invoke(pxW, microUnit);
                        pw = val.doubleValue();
                        unit = "micron";
                    }
                    if (pxH != null) {
                        java.lang.reflect.Method valueMethod = pxH.getClass().getMethod("value", Class.forName("ome.units.unit.Unit"));
                        Object microUnit = Class.forName("ome.units.UNITS").getField("MICROMETER").get(null);
                        ph = ((Number) valueMethod.invoke(pxH, microUnit)).doubleValue();
                    }
                    if (pxD != null) {
                        java.lang.reflect.Method valueMethod = pxD.getClass().getMethod("value", Class.forName("ome.units.unit.Unit"));
                        Object microUnit = Class.forName("ome.units.UNITS").getField("MICROMETER").get(null);
                        pd = ((Number) valueMethod.invoke(pxD, microUnit)).doubleValue();
                    }
                } catch (Exception ignored) {
                }
                metas.add(new SeriesMeta(s, name, sizeX, sizeY, sizeZ, sizeC, pw, ph, pd, unit));
            }
        }
        return metas;
    }

    /**
     * Reads calibration and Z-slice count for every series in the directory's .lif file.
     */
    public static List<SeriesMeta> readAllSeriesMetadata(String directory) throws Exception {
        return readAllSeriesMetadata(requireSingleLifFile(directory));
    }

    private static List<ImagePlus> toList(ImagePlus[] imps) {
        List<ImagePlus> out = new ArrayList<>();
        if (imps != null) {
            for (ImagePlus imp : imps) {
                if (imp != null) out.add(imp);
            }
        }
        return out;
    }

    static File requireReadableLifFile(File lifFile) throws IOException {
        if (lifFile == null) {
            throw new IOException("Bio-Formats container file is required.");
        }
        Path path = lifFile.toPath();
        if (Files.isSymbolicLink(path)) {
            throw new IOException("Refusing to open symbolic-link container file: " + lifFile);
        }
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Container file does not exist: " + lifFile);
        }
        String name = lifFile.getName() == null ? "" : lifFile.getName().toLowerCase(Locale.ROOT);
        if (!isSupportedContainerFileName(name)) {
            throw new IOException("Expected a Bio-Formats container file: " + lifFile);
        }
        return lifFile.getCanonicalFile();
    }

    private static boolean isSupportedContainerFileName(String lowerName) {
        for (String extension : ImageSourceDispatcher.CONTAINER_EXTENSIONS) {
            if (lowerName.endsWith(extension)) return true;
        }
        return false;
    }
}
