package flash.pipeline.io;

import flash.pipeline.naming.ImageNameParser;
import flash.pipeline.runtime.BioFormatsRuntime;
import ij.IJ;
import ij.ImagePlus;
import loci.formats.ImageReader;
import loci.formats.MetadataTools;
import loci.formats.Memoizer;
import loci.formats.meta.IMetadata;
import loci.plugins.BF;
import loci.plugins.in.ImporterOptions;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Opens series one at a time instead of loading all into memory.
 * <p>
 * Supports two input modes:
 * <ul>
 *   <li>{@code CONTAINER} — a single multi-series Bio-Formats file (e.g.
 *       {@code .lif}, {@code .czi}, {@code .nd2}, {@code .ome.tif}). Each
 *       series index maps to a Bio-Formats series within the file.</li>
 *   <li>{@code TIFF_FOLDER} — a list of single-series TIFF files. Each
 *       series index maps to one file. Per-series ordering follows the
 *       order of the supplied list.</li>
 * </ul>
 * Each call to {@link #openSeries(int)} reads a single series from disk,
 * allowing the caller to process and close it before opening the next.
 * This keeps peak memory proportional to one series rather than the
 * entire input.
 */
public class DeferredImageSupplier {

    public enum Mode { CONTAINER, TIFF_FOLDER }

    private final Mode mode;
    /**
     * CONTAINER mode: the first source file (for backward-compatible callers
     * that assume single-container). TIFF_FOLDER mode: {@code null}.
     * Multi-container callers should iterate via {@link #getContainerFileForSeries(int)}.
     */
    private final File containerFile;
    /** CONTAINER mode: every container in order. Always at least one entry. */
    private final List<File> containerFiles;
    /**
     * CONTAINER mode: parallel to {@link #containerFiles}; entry {@code c}
     * is the TOTAL series count in container {@code c} (i.e. what
     * Bio-Formats reports, not the narrowed include count). Used by the
     * open/openMaterialized methods to set {@code setSeriesOn} only on the
     * relevant container's series range. {@code null} in TIFF_FOLDER mode.
     */
    private final int[] containerSeriesCounts;
    /**
     * CONTAINER mode: global series index → (containerIndex, localSeriesIndex).
     * Honours per-container series narrowing supplied at construction time.
     * Always non-null and non-empty in CONTAINER mode.
     */
    private final List<SeriesRef> seriesRefs;
    /** TIFF_FOLDER mode: ordered list of single-series TIFFs. CONTAINER mode: {@code null}. */
    private final List<File> tiffFiles;
    /** Display name used as the leading component in synthesised series titles. */
    private final String containerDisplayName;
    private final int totalSeries;

    private static final class SeriesRef {
        final int containerIndex;
        final int localSeriesIndex;
        SeriesRef(int containerIndex, int localSeriesIndex) {
            this.containerIndex = containerIndex;
            this.localSeriesIndex = localSeriesIndex;
        }
    }

    private static final int PREFETCH_THREADS = 2;
    private ExecutorService prefetchPool;
    private final ConcurrentHashMap<Integer, Future<ImagePlus>> prefetchCache =
            new ConcurrentHashMap<Integer, Future<ImagePlus>>();

    /**
     * Creates a supplier for a single multi-series Bio-Formats file.
     * Reads only the metadata (series count) during construction.
     *
     * @param lifFile the source file to read from
     * @throws Exception if the file cannot be read or has no series
     */
    public DeferredImageSupplier(File lifFile) throws Exception {
        this.mode = Mode.CONTAINER;
        this.containerFile = lifFile;
        this.containerFiles = Collections.singletonList(lifFile);
        this.tiffFiles = null;
        this.containerDisplayName = lifFile.getName();
        BioFormatsRuntime.markUsage();
        int localCount;
        Memoizer reader = new Memoizer(new ImageReader());
        try {
            reader.setId(lifFile.getAbsolutePath());
            localCount = reader.getSeriesCount();
        } finally {
            reader.close();
        }
        this.containerSeriesCounts = new int[]{localCount};
        List<SeriesRef> refs = new ArrayList<SeriesRef>(localCount);
        for (int s = 0; s < localCount; s++) {
            refs.add(new SeriesRef(0, s));
        }
        this.seriesRefs = Collections.unmodifiableList(refs);
        this.totalSeries = localCount;
    }

    /**
     * Creates a CONTAINER-mode supplier spanning multiple Bio-Formats files.
     * Series are numbered globally in container order: container 0's series
     * first, then container 1's, and so on. Each container may be narrowed via
     * {@code includedSeriesPerContainer} — pass {@code null} or an empty list
     * for that container to include every series. Probing series counts opens
     * each file's metadata via Bio-Formats Memoizer.
     *
     * <p>Multi-container suppliers must contain at least one container, and
     * the resolved global series count must be positive (a project that
     * narrows every container down to nothing is rejected).
     *
     * @param containers                  one or more multi-series container files
     * @param includedSeriesPerContainer  optional per-container series filter;
     *                                    {@code null} means "include all series
     *                                    in every container"; otherwise the
     *                                    list must have one entry per container
     */
    public static DeferredImageSupplier multiContainer(
            List<File> containers,
            List<List<Integer>> includedSeriesPerContainer) throws Exception {
        if (containers == null || containers.isEmpty()) {
            throw new IllegalArgumentException("multiContainer: at least one container is required");
        }
        if (includedSeriesPerContainer != null
                && includedSeriesPerContainer.size() != containers.size()) {
            throw new IllegalArgumentException(
                    "multiContainer: includedSeriesPerContainer size ("
                            + includedSeriesPerContainer.size()
                            + ") must match container count (" + containers.size() + ")");
        }
        BioFormatsRuntime.markUsage();
        List<SeriesRef> refs = new ArrayList<SeriesRef>();
        int[] localCounts = new int[containers.size()];
        for (int c = 0; c < containers.size(); c++) {
            File container = containers.get(c);
            if (container == null) {
                throw new IllegalArgumentException("multiContainer: container at index " + c + " is null");
            }
            int totalInContainer;
            Memoizer reader = new Memoizer(new ImageReader());
            try {
                reader.setId(container.getAbsolutePath());
                totalInContainer = reader.getSeriesCount();
            } finally {
                reader.close();
            }
            localCounts[c] = totalInContainer;
            List<Integer> included = includedSeriesPerContainer == null
                    ? null : includedSeriesPerContainer.get(c);
            if (included == null || included.isEmpty()) {
                for (int s = 0; s < totalInContainer; s++) {
                    refs.add(new SeriesRef(c, s));
                }
            } else {
                for (Integer s : included) {
                    if (s == null) continue;
                    int local = s.intValue();
                    validateIncludedSeriesIndex(
                            "multiContainer", container, c, local, totalInContainer);
                    refs.add(new SeriesRef(c, local));
                }
            }
        }
        if (refs.isEmpty()) {
            throw new IllegalArgumentException(
                    "multiContainer: no series remain after applying per-container series filters");
        }
        return new DeferredImageSupplier(containers, localCounts, Collections.unmodifiableList(refs));
    }

    /**
     * Test-only factory that bypasses the Bio-Formats series-count probe.
     * Tests supply the per-container series counts directly so the routing
     * math can be exercised without real container files on disk.
     */
    static DeferredImageSupplier multiContainerForTests(
            List<File> containers,
            int[] perContainerSeriesCounts,
            List<List<Integer>> includedSeriesPerContainer) {
        if (containers == null || containers.isEmpty()) {
            throw new IllegalArgumentException("multiContainerForTests: containers required");
        }
        if (perContainerSeriesCounts == null || perContainerSeriesCounts.length != containers.size()) {
            throw new IllegalArgumentException(
                    "multiContainerForTests: perContainerSeriesCounts length must match containers");
        }
        if (includedSeriesPerContainer != null && includedSeriesPerContainer.size() != containers.size()) {
            throw new IllegalArgumentException(
                    "multiContainerForTests: includedSeriesPerContainer size must match containers");
        }
        List<SeriesRef> refs = new ArrayList<SeriesRef>();
        for (int c = 0; c < containers.size(); c++) {
            int total = perContainerSeriesCounts[c];
            List<Integer> include = includedSeriesPerContainer == null
                    ? null : includedSeriesPerContainer.get(c);
            if (include == null || include.isEmpty()) {
                for (int s = 0; s < total; s++) refs.add(new SeriesRef(c, s));
            } else {
                for (Integer s : include) {
                    if (s == null) continue;
                    int local = s.intValue();
                    validateIncludedSeriesIndex(
                            "multiContainerForTests", containers.get(c), c, local, total);
                    refs.add(new SeriesRef(c, local));
                }
            }
        }
        if (refs.isEmpty()) {
            throw new IllegalArgumentException(
                    "multiContainerForTests: no series remain after applying filters");
        }
        return new DeferredImageSupplier(containers, perContainerSeriesCounts, Collections.unmodifiableList(refs));
    }

    private static void validateIncludedSeriesIndex(String context, File container,
                                                    int containerIndex, int local,
                                                    int totalInContainer) {
        if (local >= 0 && local < totalInContainer) {
            return;
        }
        String label = container == null ? ("container " + containerIndex) : container.getName();
        throw new IllegalArgumentException(
                context + ": series index " + local + " is out of range for "
                        + label + " (container index " + containerIndex
                        + ", total series " + totalInContainer + ")");
    }

    private static void validateResolvedRoutingTable(List<File> containers,
                                                     int[] localCounts,
                                                     List<SeriesRef> resolvedRefs) {
        if (containers == null || containers.isEmpty()) {
            throw new IllegalArgumentException("multiContainer: containers required");
        }
        if (localCounts == null || localCounts.length != containers.size()) {
            throw new IllegalArgumentException(
                    "multiContainer: local series-count table must match container count");
        }
        if (resolvedRefs == null || resolvedRefs.isEmpty()) {
            throw new IllegalArgumentException(
                    "multiContainer: no series remain after applying per-container series filters");
        }
        for (int c = 0; c < localCounts.length; c++) {
            if (localCounts[c] < 0) {
                throw new IllegalArgumentException(
                        "multiContainer: negative series count for container index " + c);
            }
        }
        for (SeriesRef ref : resolvedRefs) {
            if (ref == null) {
                throw new IllegalArgumentException("multiContainer: routing table contains a null series ref");
            }
            if (ref.containerIndex < 0 || ref.containerIndex >= containers.size()) {
                throw new IllegalArgumentException(
                        "multiContainer: routing table container index out of range: "
                                + ref.containerIndex);
            }
            validateIncludedSeriesIndex("multiContainer routing table",
                    containers.get(ref.containerIndex), ref.containerIndex,
                    ref.localSeriesIndex, localCounts[ref.containerIndex]);
        }
    }

    /** Internal constructor for the multiContainer factory. */
    private DeferredImageSupplier(List<File> containers, int[] localCounts, List<SeriesRef> resolvedRefs) {
        validateResolvedRoutingTable(containers, localCounts, resolvedRefs);
        this.mode = Mode.CONTAINER;
        this.containerFiles = Collections.unmodifiableList(new ArrayList<File>(containers));
        this.containerFile = this.containerFiles.get(0);
        this.tiffFiles = null;
        this.containerDisplayName = this.containerFiles.size() == 1
                ? this.containerFiles.get(0).getName()
                : this.containerFiles.get(0).getName() + " (+" + (this.containerFiles.size() - 1) + " more)";
        this.containerSeriesCounts = localCounts.clone();
        this.seriesRefs = Collections.unmodifiableList(new ArrayList<SeriesRef>(resolvedRefs));
        this.totalSeries = resolvedRefs.size();
    }

    /**
     * Creates a supplier for a folder of single-series TIFF files. Each
     * file becomes one series in input-list order. Construction does not
     * open any files — metadata is read on demand by per-series methods
     * or via {@link #readTiffFolderMetadata(List, String)}.
     *
     * @param tiffFiles            ordered list of TIFF files (must be non-empty)
     * @param containerDisplayName label used as the leading component of
     *                             synthesised series titles (e.g. the
     *                             project directory name)
     * @throws IllegalArgumentException if {@code tiffFiles} is null or empty
     */
    public DeferredImageSupplier(List<File> tiffFiles, String containerDisplayName) {
        if (tiffFiles == null || tiffFiles.isEmpty()) {
            throw new IllegalArgumentException("tiffFiles must not be empty");
        }
        this.mode = Mode.TIFF_FOLDER;
        this.containerFile = null;
        this.containerFiles = null;
        this.containerSeriesCounts = null;
        this.seriesRefs = null;
        this.tiffFiles = new ArrayList<File>(tiffFiles);
        this.containerDisplayName = containerDisplayName != null ? containerDisplayName : "";
        this.totalSeries = this.tiffFiles.size();
    }

    /**
     * Delegating constructor — wraps a parent supplier so subclasses can
     * intercept {@link #openSeries(int)} / {@link #openSeriesMaterialized(int)}
     * variants without re-running Bio-Formats setup. Final state (mode, source
     * file, TIFF list, display name, series count) is copied from the parent;
     * the prefetch pool is NOT shared — callers that rely on prefetch state
     * should drive {@code startPrefetch} on the parent and route reads through
     * the wrapper's overrides.
     *
     * @param parent the supplier whose state to mirror; must not be {@code null}
     */
    protected DeferredImageSupplier(DeferredImageSupplier parent) {
        if (parent == null) {
            throw new IllegalArgumentException("parent supplier must not be null");
        }
        this.mode = parent.mode;
        this.containerFile = parent.containerFile;
        this.containerFiles = parent.containerFiles;
        this.containerSeriesCounts = parent.containerSeriesCounts;
        this.seriesRefs = parent.seriesRefs;
        this.tiffFiles = parent.tiffFiles;
        this.containerDisplayName = parent.containerDisplayName;
        this.totalSeries = parent.totalSeries;
    }

    /** Returns the input mode this supplier was constructed for. */
    public Mode getMode() {
        return mode;
    }

    /** Returns the total number of series. */
    public int getTotalSeries() {
        return totalSeries;
    }

    /**
     * Returns the source file. CONTAINER mode returns the FIRST source file
     * (backwards compatible with single-container callers); TIFF_FOLDER mode
     * returns the parent directory of the first TIFF for downstream
     * "container directory" lookups. Multi-container callers should iterate
     * per series via {@link #getContainerFileForSeries(int)}.
     */
    public File getContainerFile() {
        if (mode == Mode.CONTAINER) {
            return containerFile;
        }
        File parent = tiffFiles.get(0).getParentFile();
        return parent != null ? parent : tiffFiles.get(0);
    }

    /** Returns every container file in CONTAINER mode, or {@code null} in TIFF_FOLDER mode. */
    public List<File> getContainerFiles() {
        return containerFiles;
    }

    /**
     * Returns the source file backing a given global series index.
     * CONTAINER mode resolves through the routing table so multi-container
     * suppliers correctly identify which file a series came from.
     * TIFF_FOLDER mode returns the single TIFF for that series.
     */
    public File getContainerFileForSeries(int globalSeriesIndex) {
        if (globalSeriesIndex < 0 || globalSeriesIndex >= totalSeries) {
            throw new IllegalArgumentException(
                    "Series index " + globalSeriesIndex + " out of range [0, " + totalSeries + ")");
        }
        if (mode == Mode.TIFF_FOLDER) {
            return tiffFiles.get(globalSeriesIndex);
        }
        return containerFiles.get(seriesRefs.get(globalSeriesIndex).containerIndex);
    }

    /**
     * Returns the source-local series index backing a global series index.
     * For project-backed container suppliers this maps the project/included
     * series numbering back to Bio-Formats' original series number inside the
     * source file. TIFF folder entries are single-series files, so their local
     * index is always 0.
     */
    public int getLocalSeriesIndexForSeries(int globalSeriesIndex) {
        if (globalSeriesIndex < 0 || globalSeriesIndex >= totalSeries) {
            throw new IllegalArgumentException(
                    "Series index " + globalSeriesIndex + " out of range [0, " + totalSeries + ")");
        }
        if (mode == Mode.TIFF_FOLDER) {
            return 0;
        }
        return seriesRefs.get(globalSeriesIndex).localSeriesIndex;
    }

    /** Returns the display name used for synthesised series titles. */
    public String getContainerDisplayName() {
        return containerDisplayName;
    }

    /** True when this supplier reads from a folder of single-series TIFFs. */
    public boolean isTiffFolderMode() {
        return mode == Mode.TIFF_FOLDER;
    }

    /**
     * Returns the title/name of a single series.
     * <p>
     * TIFF_FOLDER mode synthesises a title shaped like a Bio-Formats
     * multi-series title — {@code "<displayName> - <basename>"} — so
     * downstream parsers ({@link ImageNameParser#extractBioFormatsSeriesName})
     * recover the bare basename unchanged.
     */
    public String getSeriesName(int seriesIndex) throws Exception {
        if (seriesIndex < 0 || seriesIndex >= totalSeries) {
            throw new IllegalArgumentException("Series index " + seriesIndex + " out of range");
        }
        if (mode == Mode.TIFF_FOLDER) {
            return synthesizeTiffSeriesTitle(tiffFiles.get(seriesIndex));
        }
        SeriesRef ref = seriesRefs.get(seriesIndex);
        File container = containerFiles.get(ref.containerIndex);
        BioFormatsRuntime.markUsage();
        Memoizer reader = new Memoizer(new ImageReader());
        try {
            reader.setId(container.getAbsolutePath());
            reader.setSeries(ref.localSeriesIndex);
            MetadataTools.populatePixels(reader.getMetadataStore(), reader);
            String bfName = reader.getMetadataStore() instanceof IMetadata
                    ? ((IMetadata) reader.getMetadataStore()).getImageName(ref.localSeriesIndex)
                    : "Series " + (ref.localSeriesIndex + 1);
            if (bfName == null) bfName = "Series " + (ref.localSeriesIndex + 1);
            return prefixWithContainerNameIfMulti(container, bfName);
        } finally {
            reader.close();
        }
    }

    /**
     * Returns all series names without loading any pixel data.
     */
    public List<String> getAllSeriesNames() throws Exception {
        List<String> names = new ArrayList<String>();
        if (mode == Mode.TIFF_FOLDER) {
            for (File f : tiffFiles) {
                names.add(synthesizeTiffSeriesTitle(f));
            }
            return names;
        }
        BioFormatsRuntime.markUsage();
        // Cache per-container name lookups so we open each container once.
        String[][] perContainerNames = new String[containerFiles.size()][];
        for (int s = 0; s < totalSeries; s++) {
            SeriesRef ref = seriesRefs.get(s);
            if (perContainerNames[ref.containerIndex] == null) {
                perContainerNames[ref.containerIndex] = readSeriesNamesForContainer(containerFiles.get(ref.containerIndex));
            }
            String bfName = perContainerNames[ref.containerIndex][ref.localSeriesIndex];
            if (bfName == null) bfName = "Series " + (ref.localSeriesIndex + 1);
            names.add(prefixWithContainerNameIfMulti(containerFiles.get(ref.containerIndex), bfName));
        }
        return names;
    }

    private String[] readSeriesNamesForContainer(File container) throws Exception {
        Memoizer reader = new Memoizer(new ImageReader());
        try {
            reader.setId(container.getAbsolutePath());
            int n = reader.getSeriesCount();
            String[] out = new String[n];
            loci.formats.meta.MetadataStore store = reader.getMetadataStore();
            MetadataTools.populatePixels(store, reader);
            for (int i = 0; i < n; i++) {
                if (store instanceof IMetadata) {
                    out[i] = ((IMetadata) store).getImageName(i);
                }
            }
            return out;
        } finally {
            reader.close();
        }
    }

    private String prefixWithContainerNameIfMulti(File container, String bfName) {
        if (containerFiles.size() <= 1) {
            return bfName;
        }
        return container.getName() + " - " + bfName;
    }

    /**
     * Opens a single series by index.
     * <p>
     * CONTAINER mode opens the series via Bio-Formats with virtual stacks.
     * TIFF_FOLDER mode opens the corresponding TIFF file via Bio-Formats
     * (also virtual). Each invocation creates its own reader, so callers
     * should close the returned {@link ImagePlus} when done to free memory.
     *
     * @param seriesIndex zero-based series index
     * @return the opened image, or {@code null} if the series could not be read
     * @throws Exception           if Bio-Formats encounters an I/O error
     * @throws IllegalArgumentException if seriesIndex is out of range
     */
    public ImagePlus openSeries(int seriesIndex) throws Exception {
        if (seriesIndex < 0 || seriesIndex >= totalSeries) {
            throw new IllegalArgumentException(
                    "Series index " + seriesIndex + " out of range [0, " + totalSeries + ")");
        }

        BioFormatsRuntime.markUsage();
        if (mode == Mode.TIFF_FOLDER) {
            return openTiffFile(tiffFiles.get(seriesIndex), seriesIndex, true, -1);
        }
        return openContainerSeries(seriesRefs.get(seriesIndex), true, -1);
    }

    private ImagePlus openContainerSeries(SeriesRef ref, boolean virtual, int channelIndex) throws Exception {
        File container = containerFiles.get(ref.containerIndex);
        int localTotal = containerSeriesCounts[ref.containerIndex];
        if (ref.localSeriesIndex < 0 || ref.localSeriesIndex >= localTotal) {
            throw new IllegalStateException(
                    "CONTAINER routing table points to local series "
                            + ref.localSeriesIndex + " in " + container.getName()
                            + ", but that container reports " + localTotal + " series.");
        }

        ImporterOptions options = new ImporterOptions();
        options.setId(container.getAbsolutePath());
        options.setWindowless(true);
        options.setAutoscale(true);
        options.setColorMode(ImporterOptions.COLOR_MODE_DEFAULT);
        options.setStackFormat(ImporterOptions.VIEW_STANDARD);
        options.setOpenAllSeries(false);
        options.setVirtual(virtual);

        for (int i = 0; i < localTotal; i++) {
            options.setSeriesOn(i, i == ref.localSeriesIndex);
        }
        if (channelIndex >= 0) {
            options.setCBegin(ref.localSeriesIndex, channelIndex);
            options.setCEnd(ref.localSeriesIndex, channelIndex);
        }

        ImagePlus[] imps = BF.openImagePlus(options);
        return (imps != null && imps.length > 0) ? imps[0] : null;
    }

    /**
     * Opens a single series fully materialized (non-virtual).
     * Thread-safe: each invocation creates its own Bio-Formats reader.
     * <p>
     * Unlike {@link #openSeries(int)}, the returned image contains all pixel
     * data in memory and does not share a reader, so it is safe for concurrent
     * access from worker threads.
     *
     * @param seriesIndex zero-based series index
     * @return the opened image, or {@code null} if the series could not be read
     * @throws Exception           if Bio-Formats encounters an I/O error
     * @throws IllegalArgumentException if seriesIndex is out of range
     */
    public ImagePlus openSeriesMaterialized(int seriesIndex) throws Exception {
        if (seriesIndex < 0 || seriesIndex >= totalSeries) {
            throw new IllegalArgumentException(
                    "Series index " + seriesIndex + " out of range [0, " + totalSeries + ")");
        }

        BioFormatsRuntime.markUsage();
        if (mode == Mode.TIFF_FOLDER) {
            return openTiffFile(tiffFiles.get(seriesIndex), seriesIndex, false, -1);
        }
        return openContainerSeries(seriesRefs.get(seriesIndex), false, -1);
    }

    /**
     * Opens a single series fully materialized (non-virtual), but only loads
     * the specified channel to reduce memory and processing overhead.
     * Thread-safe: each invocation creates its own Bio-Formats reader.
     *
     * @param seriesIndex zero-based series index
     * @param channelIndex zero-based channel index
     * @return the opened image containing only the specified channel, or {@code null} if the series could not be read
     * @throws Exception           if Bio-Formats encounters an I/O error
     * @throws IllegalArgumentException if seriesIndex is out of range
     */
    public ImagePlus openSeriesMaterializedChannel(int seriesIndex, int channelIndex) throws Exception {
        if (seriesIndex < 0 || seriesIndex >= totalSeries) {
            throw new IllegalArgumentException(
                    "Series index " + seriesIndex + " out of range [0, " + totalSeries + ")");
        }

        BioFormatsRuntime.markUsage();
        if (mode == Mode.TIFF_FOLDER) {
            return openTiffFile(tiffFiles.get(seriesIndex), seriesIndex, false, channelIndex);
        }
        return openContainerSeries(seriesRefs.get(seriesIndex), false, channelIndex);
    }

    /**
     * Starts background loading of the next {@code lookahead} series starting
     * from {@code fromIndex}. Already-queued indices are not re-submitted.
     * Prefetched images are retrieved via {@link #getOrLoadMaterialized(int)}.
     *
     * @param fromIndex first series index to prefetch
     * @param lookahead number of series to prefetch ahead
     */
    public void startPrefetch(int fromIndex, int lookahead) {
        if (prefetchPool == null) {
            prefetchPool = Executors.newFixedThreadPool(PREFETCH_THREADS, new java.util.concurrent.ThreadFactory() {
                private int count = 0;
                @Override
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "IHF-Prefetch-" + (++count));
                    t.setDaemon(true);
                    return t;
                }
            });
        }
        int end = Math.min(fromIndex + lookahead, totalSeries);
        for (int i = fromIndex; i < end; i++) {
            if (!prefetchCache.containsKey(i)) {
                final int idx = i;
                prefetchCache.put(i, prefetchPool.submit(new Callable<ImagePlus>() {
                    @Override
                    public ImagePlus call() throws Exception {
                        return openSeriesMaterialized(idx);
                    }
                }));
            }
        }
    }

    /**
     * Returns a materialized image for the given series, using the prefetch
     * cache if available. Falls back to synchronous loading if the series
     * was not prefetched.
     *
     * @param seriesIndex zero-based series index
     * @return the opened image, or {@code null} if the series could not be read
     * @throws Exception if Bio-Formats encounters an I/O error
     */
    public ImagePlus getOrLoadMaterialized(int seriesIndex) throws Exception {
        Future<ImagePlus> future = prefetchCache.remove(seriesIndex);
        if (future != null) {
            try {
                ImagePlus result = future.get();
                IJ.log("  (prefetched)");
                return result;
            } catch (ExecutionException e) {
                Throwable cause = e.getCause();
                if (cause instanceof Exception) throw (Exception) cause;
                throw new RuntimeException("Prefetched image series " + seriesIndex
                        + " failed with a non-Exception cause", cause);
            }
        }
        return openSeriesMaterialized(seriesIndex);
    }

    /**
     * Shuts down the prefetch thread pool and discards any queued images.
     * Safe to call multiple times or when no prefetch was started.
     */
    public void shutdownPrefetch() {
        if (prefetchPool != null) {
            prefetchPool.shutdownNow();
            prefetchPool = null;
        }
        for (Future<ImagePlus> f : prefetchCache.values()) {
            f.cancel(true);
        }
        prefetchCache.clear();
    }

    /**
     * Reads calibration and Z-slice count for every TIFF in the supplied
     * list. Each file is opened header-only via Bio-Formats; pixels are
     * not read. The returned list has the same order as the input.
     * <p>
     * Memoization writes a {@code .bfmemo} file next to each TIFF; on
     * read-only inputs the write may fail silently inside Bio-Formats —
     * we don't propagate that here.
     *
     * @param tiffFiles            ordered list of TIFF files
     * @param containerDisplayName display name used to synthesise series
     *                             names (e.g. project directory name)
     * @return per-file metadata in input order
     */
    public static List<SeriesMeta> readTiffFolderMetadata(List<File> tiffFiles,
                                                          String containerDisplayName) throws Exception {
        if (tiffFiles == null || tiffFiles.isEmpty()) {
            return Collections.emptyList();
        }
        BioFormatsRuntime.markUsage();
        List<SeriesMeta> metas = new ArrayList<SeriesMeta>();
        String displayName = containerDisplayName != null ? containerDisplayName : "";
        for (int i = 0; i < tiffFiles.size(); i++) {
            File f = tiffFiles.get(i);
            int sizeX = 0;
            int sizeY = 0;
            int sizeZ = 1;
            int sizeC = 0;
            double pw = 1.0, ph = 1.0, pd = 1.0;
            String unit = "pixel";
            loci.formats.meta.MetadataStore store = loci.formats.MetadataTools.createOMEXMLMetadata();
            Memoizer reader = new Memoizer(new ImageReader());
            try {
                reader.setMetadataStore(store);
                reader.setId(f.getAbsolutePath());
                if (reader.getSeriesCount() > 0) {
                    reader.setSeries(0);
                }
                sizeX = reader.getSizeX();
                sizeY = reader.getSizeY();
                sizeZ = reader.getSizeZ();
                sizeC = reader.getSizeC();
                if (store instanceof loci.formats.meta.MetadataRetrieve) {
                    loci.formats.meta.MetadataRetrieve retrieve =
                            (loci.formats.meta.MetadataRetrieve) store;
                    try {
                        Object pxW = retrieve.getPixelsPhysicalSizeX(0);
                        Object pxH = retrieve.getPixelsPhysicalSizeY(0);
                        Object pxD = retrieve.getPixelsPhysicalSizeZ(0);
                        if (pxW != null) {
                            java.lang.reflect.Method valueMethod = pxW.getClass().getMethod("value", Class.forName("ome.units.unit.Unit"));
                            Object microUnit = Class.forName("ome.units.UNITS").getField("MICROMETER").get(null);
                            Number val = (Number) valueMethod.invoke(pxW, microUnit);
                            if (val != null) {
                                pw = val.doubleValue();
                                unit = "micron";
                            }
                        }
                        if (pxH != null) {
                            java.lang.reflect.Method valueMethod = pxH.getClass().getMethod("value", Class.forName("ome.units.unit.Unit"));
                            Object microUnit = Class.forName("ome.units.UNITS").getField("MICROMETER").get(null);
                            Number val = (Number) valueMethod.invoke(pxH, microUnit);
                            if (val != null) {
                                ph = val.doubleValue();
                            }
                        }
                        if (pxD != null) {
                            java.lang.reflect.Method valueMethod = pxD.getClass().getMethod("value", Class.forName("ome.units.unit.Unit"));
                            Object microUnit = Class.forName("ome.units.UNITS").getField("MICROMETER").get(null);
                            Number val = (Number) valueMethod.invoke(pxD, microUnit);
                            if (val != null) {
                                pd = val.doubleValue();
                            }
                        }
                    } catch (Exception ignored) {
                    }
                }
            } finally {
                try { reader.close(); } catch (Exception ignored) {}
            }
            String name = displayName + " - " + ImageNameParser.stripExtension(f.getName());
            metas.add(new SeriesMeta(i, name, sizeX, sizeY, sizeZ, sizeC, pw, ph, pd, unit));
        }
        return metas;
    }

    private String synthesizeTiffSeriesTitle(File f) {
        String prefix = (containerDisplayName == null || containerDisplayName.isEmpty())
                ? "" : containerDisplayName + " - ";
        return prefix + ImageNameParser.stripExtension(f.getName());
    }

    private ImagePlus openTiffFile(File f, int seriesIndex, boolean virtual, int channelIndex) throws Exception {
        ImporterOptions options = new ImporterOptions();
        options.setId(f.getAbsolutePath());
        options.setWindowless(true);
        options.setAutoscale(true);
        options.setColorMode(ImporterOptions.COLOR_MODE_DEFAULT);
        options.setStackFormat(ImporterOptions.VIEW_STANDARD);
        options.setOpenAllSeries(false);
        options.setVirtual(virtual);
        // Single-series TIFFs always live at series index 0 within their own file.
        options.setSeriesOn(0, true);
        if (channelIndex >= 0) {
            options.setCBegin(0, channelIndex);
            options.setCEnd(0, channelIndex);
        }
        ImagePlus[] imps = BF.openImagePlus(options);
        ImagePlus imp = (imps != null && imps.length > 0) ? imps[0] : null;
        if (imp != null) {
            imp.setTitle(synthesizeTiffSeriesTitle(f));
        }
        return imp;
    }
}
