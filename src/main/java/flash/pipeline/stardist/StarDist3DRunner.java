package flash.pipeline.stardist;

import flash.pipeline.bin.BinConfig;
import flash.pipeline.image.GpuConcurrency;
import flash.pipeline.image.ImageOps;
import flash.pipeline.segmentation.SegmentationMethod;
import flash.pipeline.segmentation.SegmentationRunFailureException;
import flash.pipeline.segmentation.StarDistLinkingParams;
import flash.pipeline.segmentation.StarDistPostFilters;
import flash.pipeline.segmentation.catalog.ModelCatalog;
import flash.pipeline.segmentation.catalog.ModelCatalogIO;
import flash.pipeline.segmentation.catalog.ModelEntry;
import de.csbdresden.stardist.StarDist2DModel;
import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.measure.ResultsTable;
import ij.process.ImageProcessor;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import fiji.plugin.trackmate.Model;
import fiji.plugin.trackmate.Settings;
import fiji.plugin.trackmate.Spot;
import fiji.plugin.trackmate.TrackMate;
import fiji.plugin.trackmate.action.LabelImgExporter;
import fiji.plugin.trackmate.detection.DetectorKeys;
import fiji.plugin.trackmate.stardist.StarDistCustomDetectorFactory;
import fiji.plugin.trackmate.stardist.StarDistDetectorFactory;
import fiji.plugin.trackmate.tracking.jaqaman.SparseLAPTrackerFactory;

/**
 * Runs StarDist 2D per-slice via TrackMate to produce a 3D label image.
 * <p>
 * Strategy: swap Z and T dimensions so each Z-slice is treated as a
 * "timepoint" for StarDist 2D, then export a label image and swap back.
 * Each detected object gets a globally unique label across all slices.
 */
public class StarDist3DRunner {

    public static final String OBJECT_STATS_PROPERTY = "flash.stardist.objectStats";
    public static final String STATS_AREA_MEAN = "StarDist Area Mean";
    public static final String STATS_QUALITY_MEAN = "StarDist Quality Mean";
    public static final String STATS_INTENSITY_MEAN = "StarDist Intensity Mean";
    public static final String DEFAULT_STARDIST_MODEL_KEY = SegmentationMethod.DEFAULT_STARDIST_MODEL_KEY;
    private static final int MAX_BITSET_LABEL = 10_000_000;
    private static final String DEFAULT_STARDIST_MODEL_RESOURCE = "models/2D/dsb2018_heavy_augment.zip";

    interface WarningSink {
        void warn(String message);
    }

    private static final WarningSink DEFAULT_WARNING_SINK = new WarningSink() {
        @Override public void warn(String message) {
            IJ.log(message);
        }
    };

    private static WarningSink warningSink = DEFAULT_WARNING_SINK;

    /** Whether the TF thread-cap system properties have already been written.
     *  TensorFlow reads the properties at session construction, so first-write
     *  wins; we record the decision here so the first StarDist call logs it. */
    private static final AtomicBoolean TF_THREAD_CAP_LOGGED = new AtomicBoolean(false);
    private static volatile File defaultStarDistModelFile;

    static {
        applyTensorFlowThreadCap();
    }

    /**
     * Writes {@code tensorflow.inter_op_parallelism_threads} and
     * {@code tensorflow.intra_op_parallelism_threads} system properties from
     * {@link GpuConcurrency#threadsPerInference()} so that TF-Java's session
     * honours the per-inference CPU budget. Called from the static initialiser
     * before any {@link StarDistDetectorFactory} is instantiated.
     * <p>
     * Idempotent: if the properties are already set (by a launch wrapper or a
     * prior call), they are left alone — TF's first read wins.
     */
    private static void applyTensorFlowThreadCap() {
        try {
            int threads = GpuConcurrency.threadsPerInference();
            String tStr = Integer.toString(threads);
            String[] props = {
                    "tensorflow.inter_op_parallelism_threads",
                    "tensorflow.intra_op_parallelism_threads"
            };
            for (String key : props) {
                if (System.getProperty(key) == null) {
                    System.setProperty(key, tStr);
                }
            }
        } catch (Throwable ignored) {
            // Thread capping is best-effort — a property failure must not
            // break StarDist itself.
        }
    }

    /**
     * Runs StarDist on the given input image and returns the label image.
     * Uses TrackMate with StarDist detector, swapping Z/T so each z-slice
     * is processed as a 2D frame.
     *
     * @param input       the image to segment (not modified)
     * @param probThresh  probability threshold (e.g. 0.5)
     * @param nmsThresh   non-maximum suppression overlap threshold (e.g. 0.3)
     * @return label image where each object has a unique integer value, or null on failure
     */
    public static ImagePlus run(ImagePlus input, double probThresh, double nmsThresh) {
        return run(input, probThresh, nmsThresh, null,
                BinConfig.DEFAULT_STARDIST_LINKING_MAX_DISTANCE,
                BinConfig.DEFAULT_STARDIST_GAP_CLOSING_MAX_DISTANCE,
                BinConfig.DEFAULT_STARDIST_MAX_FRAME_GAP,
                0, Double.POSITIVE_INFINITY, 0, 0);
    }

    /**
     * Runs StarDist on the given input image and returns the label image.
     *
     * @param input       the image to segment (not modified)
     * @param probThresh  probability threshold (e.g. 0.5)
     * @param nmsThresh   non-maximum suppression overlap threshold (e.g. 0.3)
     * @param channelName optional channel name for log tagging (may be null)
     * @return label image where each object has a unique integer value, or null on failure
     */
    public static ImagePlus run(ImagePlus input, double probThresh, double nmsThresh, String channelName) {
        return run(input, probThresh, nmsThresh, channelName,
                BinConfig.DEFAULT_STARDIST_LINKING_MAX_DISTANCE,
                BinConfig.DEFAULT_STARDIST_GAP_CLOSING_MAX_DISTANCE,
                BinConfig.DEFAULT_STARDIST_MAX_FRAME_GAP,
                0, Double.POSITIVE_INFINITY, 0, 0);
    }

    public static ImagePlus run(ImagePlus input, SegmentationMethod method, String channelName) {
        return run(input, method, channelName, null);
    }

    public static ImagePlus run(ImagePlus input, SegmentationMethod method, String channelName, File projectRoot) {
        SegmentationMethod safe = method == null
                ? SegmentationMethod.classical("classical")
                : method;
        StarDistLinkingParams linking = SegmentationMethod.starDistLinking(safe);
        StarDistPostFilters filters = SegmentationMethod.starDistPostFilters(safe);
        return run(input,
                SegmentationMethod.starDistProb(safe),
                SegmentationMethod.starDistNms(safe),
                channelName,
                linking.linkingMaxDistance,
                linking.gapClosingMaxDistance,
                linking.maxFrameGap,
                filters.areaMin,
                filters.areaMax,
                filters.qualityMin,
                filters.intensityMin,
                SegmentationMethod.starDistModelKey(safe),
                projectRoot);
    }

    /**
     * Runs StarDist with post-detection spot filters.
     *
     * @param input        the image to segment (not modified)
     * @param probThresh   probability threshold (e.g. 0.5)
     * @param nmsThresh    non-maximum suppression overlap threshold (e.g. 0.3)
     * @param channelName  optional channel name for log tagging (may be null)
     * @param linkingMaxDistance TrackMate linking max distance across adjacent Z-slices
     * @param gapClosingMaxDistance TrackMate gap-closing max distance across skipped Z-slices
     * @param maxFrameGap  TrackMate max frame gap across skipped Z-slices
     * @param areaMin      minimum spot area to keep (0 = no filter)
     * @param areaMax      maximum spot area to keep (Infinity = no filter)
     * @param qualityMin   minimum quality/confidence to keep (0 = no filter)
     * @param intensityMin minimum mean intensity to keep (0 = no filter)
     * @return label image where each object has a unique integer value, or null on failure
     */
    public static ImagePlus run(ImagePlus input, double probThresh, double nmsThresh,
                                String channelName,
                                double linkingMaxDistance, double gapClosingMaxDistance, int maxFrameGap,
                                double areaMin, double areaMax,
                                double qualityMin, double intensityMin) {
        return run(input, probThresh, nmsThresh, channelName,
                linkingMaxDistance, gapClosingMaxDistance, maxFrameGap,
                areaMin, areaMax, qualityMin, intensityMin,
                DEFAULT_STARDIST_MODEL_KEY, null);
    }

    public static ImagePlus run(ImagePlus input, double probThresh, double nmsThresh,
                                String channelName,
                                double linkingMaxDistance, double gapClosingMaxDistance, int maxFrameGap,
                                double areaMin, double areaMax,
                                double qualityMin, double intensityMin,
                                String modelKey,
                                File projectRoot) {
        if (!StarDistDetector.isAvailable()) {
            String message = StarDistDetector.getAvailabilityMessage();
            IJ.log("WARNING: " + message);
            IllegalStateException cause = new IllegalStateException(message);
            throw failure("StarDist failed: " + message, cause);
        }

        if (TF_THREAD_CAP_LOGGED.compareAndSet(false, true)) {
            String inter = System.getProperty("tensorflow.inter_op_parallelism_threads", "unset");
            String intra = System.getProperty("tensorflow.intra_op_parallelism_threads", "unset");
            IJ.log("    StarDist TF thread cap: inter_op=" + inter + ", intra_op=" + intra);
        }

        try {
            long startTime = System.currentTimeMillis();
            double safeLinkingMaxDistance = Math.max(0, linkingMaxDistance);
            double safeGapClosingMaxDistance = Math.max(0, gapClosingMaxDistance);
            int safeMaxFrameGap = Math.max(0, maxFrameGap);
            File modelFile = resolveStarDistModelFile(modelKey, projectRoot, input, channelName);

            ImagePlus dup = duplicateInputForTrackMate(input);

            // Remember original dimensions.
            int c = dup.getNChannels();
            int z = dup.getNSlices();
            int t = dup.getNFrames();
            int stackSize = dup.getStackSize();

            if (stackSize == 0) {
                String chTag0 = (channelName != null && !channelName.isEmpty())
                        ? " [" + channelName + "]" : "";
                IJ.log("WARNING: StarDist" + chTag0 + " input image has 0 slices — cannot process");
                dup.changes = false;
                dup.close();
                dup.flush();
                throw new IllegalStateException("StarDist" + chTag0
                        + " input image has 0 slices - cannot process");
            }

            // Pad channel dim from 1 → 2 (duplicating the single channel) so
            // TrackMate's isHyperStack() check passes via getNDimensions() > 3
            // instead of via a visible window. Without this, a c=1 input after
            // the Z→T swap has dims (c=1, z=1, t=N) → getNDimensions()=3 and
            // the only way to satisfy isHyperStack() is to show(), which forces
            // serialisation on the shared WindowManager. StarDist's
            // KEY_TARGET_CHANNEL=1 makes it ignore the padding channel.
            int effectiveC = c;
            if (c == 1) {
                ImageStack oldStack = dup.getImageStack();
                int nSlices = oldStack.getSize();
                ImageStack paddedStack = new ImageStack(dup.getWidth(), dup.getHeight());
                for (int i = 1; i <= nSlices; i++) {
                    ImageProcessor ip = oldStack.getProcessor(i);
                    paddedStack.addSlice(ip);
                    paddedStack.addSlice(ip.duplicate());
                }
                dup.setStack(paddedStack);
                effectiveC = 2;
            }

            // Swap Z -> T so each z-slice becomes a timepoint for 2D StarDist.
            dup.setDimensions(effectiveC, 1, z * t);
            dup.setOpenAsHyperStack(true);

            String chTag2 = (channelName != null && !channelName.isEmpty())
                    ? " [" + channelName + "]" : "";
            IJ.log("    StarDist" + chTag2 + " input: C=" + dup.getNChannels()
                    + " Z=" + dup.getNSlices() + " T=" + dup.getNFrames()
                    + " stackSize=" + stackSize + " (" + dup.getWidth() + "x" + dup.getHeight()
                    + ") [original Z=" + z + ", padded=" + (c == 1) + "]");

            // Dimension padding + setOpenAsHyperStack(true) lets TrackMate treat
            // the image as a hyperstack without a visible window, so no shared
            // WindowManager state is touched. The GPU semaphore is now the sole
            // gate — concurrent StarDist calls are limited only by VRAM.
            ImagePlus labelImp = null;
            GpuConcurrency.gpuSemaphore().acquireUninterruptibly();
            try {
                try {
                    Model model = new Model();
                    model.setLogger(fiji.plugin.trackmate.Logger.VOID_LOGGER);
                    Settings settings = new Settings(dup);
                    settings.addAllAnalyzers();

                    configureStarDistDetector(settings, probThresh, nmsThresh, modelFile);

                    // Tracker links 2D detections across Z-slices (swapped to timepoints)
                    // into 3D objects. Required for the Z/T swap strategy.
                    settings.trackerFactory = new SparseLAPTrackerFactory();
                    settings.trackerSettings = settings.trackerFactory.getDefaultSettings();
                    settings.trackerSettings.put("LINKING_MAX_DISTANCE", Double.valueOf(safeLinkingMaxDistance));
                    settings.trackerSettings.put("GAP_CLOSING_MAX_DISTANCE", Double.valueOf(safeGapClosingMaxDistance));
                    settings.trackerSettings.put("MAX_FRAME_GAP", Integer.valueOf(safeMaxFrameGap));

                    TrackMate trackmate = new TrackMate(model, settings);

                    if (!trackmate.checkInput()) {
                        String message = "StarDist TrackMate input check failed: "
                                + safeTrackMateMessage(trackmate.getErrorMessage());
                        IJ.log("WARNING: " + message);
                        throw new IllegalStateException(message);
                    }

                    if (!trackmate.execDetection()) {
                        logTrackMateFailure("detection", trackmate.getErrorMessage());
                        throw new IllegalStateException("StarDist detection failed: "
                                + safeTrackMateMessage(trackmate.getErrorMessage()));
                    }

                    if (!trackmate.computeSpotFeatures(false)) {
                        String message = "StarDist feature computation failed: "
                                + safeTrackMateMessage(trackmate.getErrorMessage());
                        IJ.log("WARNING: " + message);
                        throw new IllegalStateException(message);
                    }

                    trackmate.execInitialSpotFiltering();
                    trackmate.execSpotFiltering(false);

                    // If no spots survived detection + filtering, return empty label image
                    int nSpots = model.getSpots().getNSpots(true);
                    if (nSpots == 0) {
                        String chTag3 = (channelName != null && !channelName.isEmpty())
                                ? " [" + channelName + "]" : "";
                        long timeMs2 = System.currentTimeMillis() - startTime;
                        IJ.log("    StarDist" + chTag3 + ": 0 objects detected (" + timeMs2 + " ms)");
                        // Return empty label image (all zeros)
                        ImagePlus emptyLabel = ij.IJ.createImage("Label Image", "16-bit black",
                                dup.getWidth(), dup.getHeight(), z * t);
                        emptyLabel.setDimensions(1, z * t, 1);
                        if (input.getCalibration() != null) {
                            emptyLabel.setCalibration(input.getCalibration().copy());
                        }
                        emptyLabel.setProperty(OBJECT_STATS_PROPERTY, new ResultsTable());
                        return emptyLabel;
                    }

                    // Spots exist — run tracker to link 2D detections into 3D objects
                    int spotsBeforeTracking = nSpots;
                    if (!trackmate.execTracking()) {
                        String message = "StarDist tracking failed: "
                                + safeTrackMateMessage(trackmate.getErrorMessage());
                        IJ.log("WARNING: " + message);
                        throw new IllegalStateException(message);
                    }
                    int nTracks = model.getTrackModel().nTracks(true);

                    // Remove unlinked spots (not part of any track) from the model.
                    // Only tracked spots represent valid 3D objects — unlinked spots are
                    // single-slice detections the tracker couldn't link. If they remain,
                    // LabelImgExporter includes them as separate labels, inflating object
                    // counts and corrupting colocalisation.
                    java.util.Set<Spot> trackedSpots = new java.util.HashSet<Spot>();
                    for (Integer trackID : model.getTrackModel().trackIDs(true)) {
                        trackedSpots.addAll(model.getTrackModel().trackSpots(trackID));
                    }
                    java.util.List<Spot> unlinked = new java.util.ArrayList<Spot>();
                    for (Spot spot : model.getSpots().iterable(true)) {
                        if (!trackedSpots.contains(spot)) {
                            unlinked.add(spot);
                        }
                    }
                    for (Spot spot : unlinked) {
                        model.getSpots().remove(spot, spot.getFeature(Spot.FRAME).intValue());
                    }
                    ResultsTable objectStats = buildObjectStats(model);

                    String chTagTrack = (channelName != null && !channelName.isEmpty())
                            ? " [" + channelName + "]" : "";
                    IJ.log("    StarDist" + chTagTrack + " 3D linking: " + spotsBeforeTracking
                            + " 2D spots → " + nTracks + " tracks (" + unlinked.size()
                            + " unlinked spots removed) across " + (z * t) + " Z-slices"
                            + " [linking=" + safeLinkingMaxDistance
                            + ", gapClosing=" + safeGapClosingMaxDistance
                            + ", frameGap=" + safeMaxFrameGap + "]");

                    // REGRESSION GUARD: Must use LABEL_IS_TRACK_ID (not LABEL_IS_INDEX_MOVIE_UNIQUE).
                    // INDEX_MOVIE_UNIQUE gives each 2D spot a unique label — 2870 labels for 435 tracks.
                    // TRACK_ID gives all spots in the same track one shared label — one 3D object per track.
                    // exportTracksOnly=true excludes unlinked spots that the tracker couldn't link.
                    ImagePlus exported = LabelImgExporter.createLabelImagePlus(
                            trackmate, false, true,
                            LabelImgExporter.LabelIdPainting.LABEL_IS_TRACK_ID);

                    // Defensive: LabelImgExporter doesn't normally show the
                    // label image, but if it ever does, detach it so post-
                    // processing (bit-depth convert, setDimensions) doesn't
                    // touch WindowManager from worker threads.
                    if (exported != null && exported.getWindow() != null) {
                        exported.changes = false;
                        exported.hide();
                    }

                    labelImp = exported;
                    if (labelImp != null) {
                        labelImp.setProperty(OBJECT_STATS_PROPERTY, objectStats);
                    }
                } finally {
                    // Release the duplicate's pixel buffer. dup was never
                    // shown, so no WindowManager state to clean up — just free
                    // memory. CPU footprint roughly doubles during the padded
                    // StarDist call and returns to baseline here.
                    dup.changes = false;
                    dup.flush();
                }
            } finally {
                GpuConcurrency.gpuSemaphore().release();
            }

            if (labelImp == null) {
                String message = "StarDist label image export returned null";
                IJ.log("WARNING: " + message);
                throw new IllegalStateException(message);
            }

            // REGRESSION GUARD: LabelImgExporter produces 32-bit float. Must convert to 16-bit.
            // 32-bit labels cause mcib3d Objects3DIntPopulation to misparse objects and
            // downstream colocalisation/object counting to produce wrong results.
            if (labelImp.getBitDepth() != 16) {
                ImageStack oldStack = labelImp.getStack();
                ImageStack newStack = new ImageStack(oldStack.getWidth(), oldStack.getHeight());
                for (int s = 1; s <= oldStack.getSize(); s++) {
                    ImageProcessor fp = oldStack.getProcessor(s);
                    newStack.addSlice(fp.convertToShort(false));
                }
                labelImp.setStack(newStack);
            }

            labelImp.setDimensions(1, z * t, 1);
            labelImp.setTitle("Label Image");

            if (input.getCalibration() != null) {
                labelImp.setCalibration(input.getCalibration().copy());
            }

            Object statsProperty = labelImp.getProperty(OBJECT_STATS_PROPERTY);
            ResultsTable objectStats = statsProperty instanceof ResultsTable
                    ? (ResultsTable) statsProperty
                    : new ResultsTable();
            int removedByStarDistFilters = applyObjectFilters(
                    labelImp, objectStats, areaMin, areaMax, qualityMin, intensityMin);
            if (removedByStarDistFilters > 0) {
                String chTagFilter = (channelName != null && !channelName.isEmpty())
                        ? " [" + channelName + "]" : "";
                IJ.log("    StarDist" + chTagFilter + " object filters removed "
                        + removedByStarDistFilters + " object(s)");
            }

            int nObjects = countLabels(labelImp);
            long timeMs = System.currentTimeMillis() - startTime;
            String chTag = (channelName != null && !channelName.isEmpty())
                    ? " [" + channelName + "]" : "";
            StringBuilder filterInfo = new StringBuilder();
            if (areaMin > 0 || Double.isFinite(areaMax))
                filterInfo.append(" area=").append(areaMin).append("-")
                        .append(Double.isFinite(areaMax) ? String.valueOf(areaMax) : "Inf");
            if (qualityMin > 0) filterInfo.append(" quality>=").append(qualityMin);
            if (intensityMin > 0) filterInfo.append(" intensity>=").append(intensityMin);
            IJ.log("    StarDist" + chTag + ": " + nObjects + " objects detected (" + timeMs + " ms)"
                    + (filterInfo.length() > 0 ? " [filters:" + filterInfo + "]" : ""));

            return labelImp;

        } catch (Exception e) {
            IJ.log("WARNING: StarDist failed for channel='" + channelName
                    + "', input='" + (input == null ? "<null>" : input.getTitle())
                    + "', modelKey='" + modelKey + "', probThresh=" + probThresh
                    + ", nmsThresh=" + nmsThresh
                    + ", linkingMaxDistance=" + linkingMaxDistance
                    + ", gapClosingMaxDistance=" + gapClosingMaxDistance
                    + ", maxFrameGap=" + maxFrameGap + ": " + exceptionSummary(e));
            throw failure("StarDist failed: " + exceptionSummary(e), e);
        } catch (LinkageError e) {
            IJ.log("WARNING: StarDist failed due to an incompatible runtime: "
                    + e.getClass().getSimpleName() + " - " + e.getMessage());
            IJ.log("WARNING: " + StarDistDetector.getAvailabilityMessage());
            IJ.log("WARNING: StarDist runtime context: channel='" + channelName
                    + "', input='" + (input == null ? "<null>" : input.getTitle())
                    + "', modelKey='" + modelKey + "'.");
            throw failure("StarDist failed due to an incompatible runtime: " + exceptionSummary(e), e);
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    static void configureStarDistDetector(Settings settings, double probThresh, double nmsThresh)
            throws IOException {
        configureStarDistDetector(settings, probThresh, nmsThresh, defaultStarDistModelFile());
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    static void configureStarDistDetector(Settings settings, double probThresh, double nmsThresh,
                                          File modelFile)
            throws IOException {
        if (modelFile == null || !modelFile.isFile()) {
            throw new IOException("StarDist model file does not exist: " + modelFile);
        }
        requireUnitThreshold("StarDist probability threshold", probThresh);
        requireUnitThreshold("StarDist NMS threshold", nmsThresh);
        StarDistCustomDetectorFactory factory = new StarDistCustomDetectorFactory();
        settings.detectorFactory = factory;
        settings.detectorSettings = factory.getDefaultSettings();
        settings.detectorSettings.put(DetectorKeys.KEY_TARGET_CHANNEL, Integer.valueOf(1));
        settings.detectorSettings.put(StarDistCustomDetectorFactory.KEY_MODEL_FILEPATH,
                modelFile.getAbsolutePath());
        settings.detectorSettings.put(StarDistCustomDetectorFactory.KEY_SCORE_THRESHOLD,
                Double.valueOf(probThresh));
        settings.detectorSettings.put(StarDistCustomDetectorFactory.KEY_OVERLAP_THRESHOLD,
                Double.valueOf(nmsThresh));
    }

    private static void requireUnitThreshold(String label, double value) {
        if (!Double.isFinite(value) || value < 0.0d || value > 1.0d) {
            throw new IllegalArgumentException(label + " must be finite and between 0 and 1.");
        }
    }

    static void setWarningSinkForTest(WarningSink sink) {
        warningSink = sink == null ? DEFAULT_WARNING_SINK : sink;
    }

    public static File resolveStarDistModelFile(SegmentationMethod method, File projectRoot,
                                                ImagePlus input, String channelName) throws IOException {
        SegmentationMethod safe = method == null
                ? SegmentationMethod.classical("classical")
                : method;
        return resolveStarDistModelFile(SegmentationMethod.starDistModelKey(safe),
                projectRoot, input, channelName);
    }

    public static File resolveStarDistModelFile(String modelKey, File projectRoot,
                                                ImagePlus input, String channelName) throws IOException {
        String requestedKey = safeModelKey(modelKey);
        Path root = projectRootPath(projectRoot);
        ModelCatalog catalog = ModelCatalogIO.read(root);
        ModelEntry entry = starDistEntry(catalog, requestedKey);
        if (entry == null) {
            if (DEFAULT_STARDIST_MODEL_KEY.equals(requestedKey)) {
                return defaultStarDistModelFile();
            }
            throw new IllegalArgumentException("StarDist model '" + requestedKey
                    + "' not found in catalog. Please import it via Manage Models "
                    + "or select a different model.");
        }

        warnIfRgbAdvancedModel(entry, input, channelName);
        Path resolved = catalog.resolve(entry);
        if (resolved == null || !Files.isRegularFile(resolved)) {
            if (DEFAULT_STARDIST_MODEL_KEY.equals(entry.modelKey)) {
                return defaultStarDistModelFile();
            }
            throw new IllegalStateException("StarDist model file for '" + entry.modelKey
                    + "' does not exist: " + resolved
                    + ". Please import it via Manage Models or select a different model.");
        }
        return resolved.toFile();
    }

    private static ModelEntry starDistEntry(ModelCatalog catalog, String modelKey) {
        if (catalog == null || modelKey == null) return null;
        Optional<ModelEntry> found = catalog.get(modelKey);
        if (!found.isPresent()) return null;
        ModelEntry entry = found.get();
        return entry.engine == ModelEntry.Engine.STARDIST ? entry : null;
    }

    private static Path projectRootPath(File projectRoot) {
        if (projectRoot != null) {
            return projectRoot.toPath().toAbsolutePath().normalize();
        }
        return Paths.get(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();
    }

    private static String safeModelKey(String modelKey) {
        return modelKey == null || modelKey.trim().isEmpty()
                ? DEFAULT_STARDIST_MODEL_KEY
                : modelKey.trim();
    }

    private static void warnIfRgbAdvancedModel(ModelEntry entry, ImagePlus input, String channelName) {
        if (entry == null || !metadataBoolean(entry, "advanced")) return;
        if (!metadataBoolean(entry, "rgbOnly")) return;
        if (isRgbInput(input)) return;
        String chTag = channelName == null || channelName.trim().isEmpty()
                ? ""
                : " for channel '" + channelName.trim() + "'";
        warn("WARNING: StarDist model '" + entry.name + "'" + chTag
                + " is marked advanced/RGB. FLASH normally sends a per-channel grayscale image, "
                + "so this model may be incompatible unless the channel is configured as RGB.");
    }

    private static boolean metadataBoolean(ModelEntry entry, String key) {
        if (entry == null || key == null) return false;
        Object value = entry.metadata.get(key);
        if (value instanceof Boolean) return ((Boolean) value).booleanValue();
        return value != null && Boolean.parseBoolean(String.valueOf(value));
    }

    private static boolean isRgbInput(ImagePlus input) {
        if (input == null) return false;
        return input.getType() == ImagePlus.COLOR_RGB || input.getNChannels() >= 3;
    }

    private static void warn(String message) {
        WarningSink sink = warningSink == null ? DEFAULT_WARNING_SINK : warningSink;
        sink.warn(message);
    }

    private static File defaultStarDistModelFile() throws IOException {
        File cached = defaultStarDistModelFile;
        if (cached != null && cached.isFile()) return cached;
        synchronized (StarDist3DRunner.class) {
            cached = defaultStarDistModelFile;
            if (cached != null && cached.isFile()) return cached;

            URL resource = StarDist2DModel.class.getClassLoader()
                    .getResource(DEFAULT_STARDIST_MODEL_RESOURCE);
            if (resource == null) {
                throw new IOException("StarDist default model resource not found: "
                        + DEFAULT_STARDIST_MODEL_RESOURCE);
            }
            if ("file".equalsIgnoreCase(resource.getProtocol())) {
                try {
                    cached = new File(resource.toURI());
                } catch (URISyntaxException e) {
                    cached = new File(resource.getPath());
                }
            } else {
                Path temp = Files.createTempFile("flash-stardist-dsb2018-heavy-augment-", ".zip");
                try (InputStream in = resource.openStream()) {
                    Files.copy(in, temp, StandardCopyOption.REPLACE_EXISTING);
                }
                cached = temp.toFile();
                cached.deleteOnExit();
            }
            defaultStarDistModelFile = cached;
            return cached;
        }
    }

    static ImagePlus duplicateInputForTrackMate(ImagePlus input) {
        ImagePlus dup = ImageOps.duplicateThreadSafe(input);
        dup.setTitle("StarDist_input");
        return dup;
    }

    /**
     * Counts distinct positive labels across all slices of a label image.
     *
     * @param labelImage label image produced by StarDist
     * @return number of labelled objects
     */
    public static int countLabels(ImagePlus labelImage) {
        if (labelImage == null || labelImage.getStack() == null) return 0;
        BitSet labels = new BitSet();
        Set<Integer> highLabels = null;
        int nSlices = labelImage.getStackSize();
        for (int s = 1; s <= nSlices; s++) {
            ImageProcessor ip = labelImage.getStack().getProcessor(s);
            if (ip == null) continue;
            for (int i = 0; i < ip.getPixelCount(); i++) {
                int label = labelFromPixel(ip.getf(i));
                if (label > 0) {
                    if (label <= MAX_BITSET_LABEL) {
                        labels.set(label);
                    } else {
                        if (highLabels == null) highLabels = new HashSet<Integer>();
                        highLabels.add(Integer.valueOf(label));
                    }
                }
            }
        }
        return labels.cardinality() + (highLabels == null ? 0 : highLabels.size());
    }

    private static ResultsTable buildObjectStats(Model model) {
        ResultsTable table = new ResultsTable();
        if (model == null || model.getTrackModel() == null) return table;
        List<Integer> trackIds = new ArrayList<Integer>(model.getTrackModel().trackIDs(true));
        Collections.sort(trackIds);
        int row = 0;
        for (Integer trackId : trackIds) {
            Set<Spot> spots = model.getTrackModel().trackSpots(trackId);
            if (spots == null || spots.isEmpty()) continue;
            MetricStats area = new MetricStats();
            MetricStats quality = new MetricStats();
            MetricStats intensity = new MetricStats();
            for (Spot spot : spots) {
                Double radius = spot.getFeature(Spot.RADIUS);
                if (radius != null && Double.isFinite(radius.doubleValue())) {
                    area.add(Math.PI * radius.doubleValue() * radius.doubleValue());
                }
                quality.add(spot.getFeature(Spot.QUALITY));
                intensity.add(spot.getFeature("MEAN_INTENSITY_CH1"));
            }
            table.incrementCounter();
            table.setValue("Label", row, trackId.intValue());
            if (area.hasValues()) table.setValue(STATS_AREA_MEAN, row, area.mean());
            if (quality.hasValues()) table.setValue(STATS_QUALITY_MEAN, row, quality.mean());
            if (intensity.hasValues()) table.setValue(STATS_INTENSITY_MEAN, row, intensity.mean());
            row++;
        }
        return table;
    }

    public static int applyObjectFilters(ImagePlus labelImage,
                                         ResultsTable objectStats,
                                         double areaMin,
                                         double areaMax,
                                         double qualityMin,
                                         double intensityMin) {
        if (labelImage == null || objectStats == null || objectStats.size() == 0) return 0;
        BitSet labelsToRemove = new BitSet();
        Set<Integer> highLabelsToRemove = null;
        int removedLabels = 0;
        for (int row = 0; row < objectStats.size(); row++) {
            int label = labelForRow(objectStats, row);
            if (label <= 0) continue;
            double area = metric(objectStats, STATS_AREA_MEAN, row);
            double quality = metric(objectStats, STATS_QUALITY_MEAN, row);
            double intensity = metric(objectStats, STATS_INTENSITY_MEAN, row);
            boolean remove = false;
            if (Double.isFinite(area)) {
                remove = (areaMin > 0 && area < areaMin)
                        || (Double.isFinite(areaMax) && area > areaMax);
            }
            if (!remove && qualityMin > 0 && Double.isFinite(quality)) {
                remove = quality < qualityMin;
            }
            if (!remove && intensityMin > 0 && Double.isFinite(intensity)) {
                remove = intensity < intensityMin;
            }
            if (remove) {
                if (label <= MAX_BITSET_LABEL) {
                    if (!labelsToRemove.get(label)) {
                        labelsToRemove.set(label);
                        removedLabels++;
                    }
                } else {
                    if (highLabelsToRemove == null) highLabelsToRemove = new HashSet<Integer>();
                    if (highLabelsToRemove.add(Integer.valueOf(label))) {
                        removedLabels++;
                    }
                }
            }
        }
        if (removedLabels == 0 || labelImage.getStack() == null) return 0;
        ImageStack stack = labelImage.getStack();
        for (int slice = 1; slice <= stack.getSize(); slice++) {
            ImageProcessor processor = stack.getProcessor(slice);
            if (processor == null) continue;
            for (int i = 0; i < processor.getPixelCount(); i++) {
                int label = labelFromPixel(processor.getf(i));
                if (label > 0 && shouldRemoveLabel(label, labelsToRemove, highLabelsToRemove)) {
                    processor.setf(i, 0f);
                }
            }
        }
        labelImage.updateAndDraw();
        return removedLabels;
    }

    private static boolean shouldRemoveLabel(int label, BitSet labelsToRemove, Set<Integer> highLabelsToRemove) {
        if (label <= MAX_BITSET_LABEL) {
            return labelsToRemove.get(label);
        }
        return highLabelsToRemove != null && highLabelsToRemove.contains(Integer.valueOf(label));
    }

    private static int labelForRow(ResultsTable table, int row) {
        try {
            double label = table.getValue("Label", row);
            if (Double.isFinite(label) && label > 0) return (int) Math.round(label);
        } catch (RuntimeException ignored) {
            // Fall through to row order.
        }
        return row + 1;
    }

    private static int labelFromPixel(float value) {
        if (!Float.isFinite(value) || value <= 0f) return 0;
        return value > Integer.MAX_VALUE ? 0 : Math.round(value);
    }

    private static double metric(ResultsTable table, String column, int row) {
        try {
            double value = table.getValue(column, row);
            return Double.isFinite(value) ? value : Double.NaN;
        } catch (RuntimeException e) {
            return Double.NaN;
        }
    }

    private static final class MetricStats {
        private double sum;
        private int count;

        void add(Double value) {
            if (value != null && Double.isFinite(value.doubleValue())) {
                add(value.doubleValue());
            }
        }

        void add(double value) {
            if (Double.isFinite(value)) {
                sum += value;
                count++;
            }
        }

        boolean hasValues() {
            return count > 0;
        }

        double mean() {
            return count == 0 ? Double.NaN : sum / count;
        }
    }

    private static void logTrackMateFailure(String phase, String message) {
        String safeMessage = safeTrackMateMessage(message);
        IJ.log("WARNING: StarDist " + phase + " failed: " + safeMessage);
        if (safeMessage.contains("NullPointerException")) {
            IJ.log("WARNING: StarDist returned a NullPointerException inside TrackMate/StarDist. "
                    + "This is commonly caused by a broken Fiji StarDist runtime, including duplicate "
                    + "or cloud-sync-conflicted StarDist jars. Use Dependencies > Auto-Fix StarDist, "
                    + "close Fiji, and restart Fiji before retrying.");
        }
    }

    private static String safeTrackMateMessage(String message) {
        return message == null || message.trim().isEmpty()
                ? "No error message was returned by TrackMate."
                : message.trim();
    }

    private static SegmentationRunFailureException failure(String message, Throwable cause) {
        return new SegmentationRunFailureException(message, cause);
    }

    private static String exceptionSummary(Throwable throwable) {
        if (throwable == null) {
            return "unknown error";
        }
        String message = throwable.getMessage();
        return throwable.getClass().getSimpleName()
                + (message == null || message.trim().isEmpty() ? "" : " - " + message.trim());
    }

}
