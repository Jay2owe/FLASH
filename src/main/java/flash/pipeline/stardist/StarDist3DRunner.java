package flash.pipeline.stardist;

import flash.pipeline.bin.BinConfig;
import flash.pipeline.image.GpuConcurrency;
import flash.pipeline.image.ImageOps;
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
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
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
    private static final String DEFAULT_STARDIST_MODEL_RESOURCE = "models/2D/dsb2018_heavy_augment.zip";

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
        if (!StarDistDetector.isAvailable()) {
            IJ.log("WARNING: " + StarDistDetector.getAvailabilityMessage());
            return null;
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
                return null;
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

                    configureStarDistDetector(settings, probThresh, nmsThresh);

                    // Tracker links 2D detections across Z-slices (swapped to timepoints)
                    // into 3D objects. Required for the Z/T swap strategy.
                    settings.trackerFactory = new SparseLAPTrackerFactory();
                    settings.trackerSettings = settings.trackerFactory.getDefaultSettings();
                    settings.trackerSettings.put("LINKING_MAX_DISTANCE", Double.valueOf(safeLinkingMaxDistance));
                    settings.trackerSettings.put("GAP_CLOSING_MAX_DISTANCE", Double.valueOf(safeGapClosingMaxDistance));
                    settings.trackerSettings.put("MAX_FRAME_GAP", Integer.valueOf(safeMaxFrameGap));

                    TrackMate trackmate = new TrackMate(model, settings);

                    if (!trackmate.checkInput()) {
                        IJ.log("WARNING: StarDist TrackMate input check failed: " + trackmate.getErrorMessage());
                        return null;
                    }

                    if (!trackmate.execDetection()) {
                        logTrackMateFailure("detection", trackmate.getErrorMessage());
                        return null;
                    }

                    if (!trackmate.computeSpotFeatures(false)) {
                        IJ.log("WARNING: StarDist feature computation failed: " + trackmate.getErrorMessage());
                        return null;
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
                        IJ.log("WARNING: StarDist tracking failed: " + trackmate.getErrorMessage());
                        return null;
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
                IJ.log("WARNING: StarDist label image export returned null");
                return null;
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
            IJ.log("WARNING: StarDist failed: " + e.getClass().getSimpleName()
                    + " - " + e.getMessage());
            java.io.StringWriter sw = new java.io.StringWriter();
            e.printStackTrace(new java.io.PrintWriter(sw));
            IJ.log(sw.toString());
            return null;
        } catch (LinkageError e) {
            IJ.log("WARNING: StarDist failed due to an incompatible runtime: "
                    + e.getClass().getSimpleName() + " - " + e.getMessage());
            IJ.log("WARNING: " + StarDistDetector.getAvailabilityMessage());
            return null;
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    static void configureStarDistDetector(Settings settings, double probThresh, double nmsThresh)
            throws IOException {
        StarDistCustomDetectorFactory factory = new StarDistCustomDetectorFactory();
        settings.detectorFactory = factory;
        settings.detectorSettings = factory.getDefaultSettings();
        settings.detectorSettings.put(DetectorKeys.KEY_TARGET_CHANNEL, Integer.valueOf(1));
        settings.detectorSettings.put(StarDistCustomDetectorFactory.KEY_MODEL_FILEPATH,
                defaultStarDistModelFile().getAbsolutePath());
        settings.detectorSettings.put(StarDistCustomDetectorFactory.KEY_SCORE_THRESHOLD,
                Double.valueOf(probThresh));
        settings.detectorSettings.put(StarDistCustomDetectorFactory.KEY_OVERLAP_THRESHOLD,
                Double.valueOf(nmsThresh));
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
        Set<Integer> labels = new HashSet<Integer>();
        int nSlices = labelImage.getStackSize();
        for (int s = 1; s <= nSlices; s++) {
            ImageProcessor ip = labelImage.getStack().getProcessor(s);
            if (ip == null) continue;
            for (int i = 0; i < ip.getPixelCount(); i++) {
                int label = Math.round(ip.getf(i));
                if (label > 0) labels.add(Integer.valueOf(label));
            }
        }
        return labels.size();
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
        Set<Integer> labelsToRemove = new HashSet<Integer>();
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
            if (remove) labelsToRemove.add(Integer.valueOf(label));
        }
        if (labelsToRemove.isEmpty() || labelImage.getStack() == null) return 0;
        ImageStack stack = labelImage.getStack();
        for (int slice = 1; slice <= stack.getSize(); slice++) {
            ImageProcessor processor = stack.getProcessor(slice);
            if (processor == null) continue;
            for (int i = 0; i < processor.getPixelCount(); i++) {
                int label = Math.round(processor.getf(i));
                if (label > 0 && labelsToRemove.contains(Integer.valueOf(label))) {
                    processor.setf(i, 0f);
                }
            }
        }
        labelImage.updateAndDraw();
        return labelsToRemove.size();
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
        String safeMessage = message == null || message.trim().isEmpty()
                ? "No error message was returned by TrackMate."
                : message;
        IJ.log("WARNING: StarDist " + phase + " failed: " + safeMessage);
        if (safeMessage.contains("NullPointerException")) {
            IJ.log("WARNING: StarDist returned a NullPointerException inside TrackMate/StarDist. "
                    + "This is commonly caused by a broken Fiji StarDist runtime, including duplicate "
                    + "or Dropbox-conflicted StarDist jars. Use Dependencies > Auto-Fix StarDist, "
                    + "close Fiji, and restart Fiji before retrying.");
        }
    }

}
