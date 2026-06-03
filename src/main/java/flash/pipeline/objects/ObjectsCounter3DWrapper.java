package flash.pipeline.objects;

import Utilities.Counter3D;
import flash.pipeline.image.ImageOps;
import ij.ImagePlus;
import ij.ImageStack;
import ij.Prefs;
import ij.WindowManager;
import ij.measure.Calibration;
import ij.measure.ResultsTable;
import ij.process.ImageProcessor;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.Vector;

/**
 * Wrapper for 3D object counting that provides two implementations:
 *
 * <ol>
 *   <li><b>Native mcib3d</b> ({@link #runNative}) — uses mcib3d-core's {@code ImageLabeller},
 *       {@code Objects3DIntPopulation}, and per-object measurement classes. Fully instance-based
 *       with no global state (Prefs, WindowManager), making it <b>thread-safe</b> and suitable
 *       for parallel execution without locks.</li>
 *   <li><b>Legacy Counter3D</b> ({@link #run}) — thin wrapper around Fiji's "3D Objects Counter".
 *       Uses global Prefs and WindowManager; requires external synchronisation (COUNTER3D_LOCK).</li>
 * </ol>
 *
 * <p>Both return the same {@link Result} type so callers can switch transparently.
 */
public final class ObjectsCounter3DWrapper {

    public static final class Result {
        private final ResultsTable statistics;
        private final ImagePlus objectsMap;
        private final ImagePlus maskedImage;
        private final boolean foundObjects;

        public Result(ResultsTable statistics, ImagePlus objectsMap, ImagePlus maskedImage, boolean foundObjects) {
            this.statistics = statistics;
            this.objectsMap = objectsMap;
            this.maskedImage = maskedImage;
            this.foundObjects = foundObjects;
        }

        public ResultsTable getStatistics() {
            return statistics;
        }

        public ImagePlus getObjectsMap() {
            return objectsMap;
        }

        public ImagePlus getMaskedImage() {
            return maskedImage;
        }

        public boolean isFoundObjects() {
            return foundObjects;
        }
    }

    /**
     * Runs 3D object counting on {@code img}.
     *
     * @param img thresholded-by-intensity image (Counter3D will zero pixels below {@code threshold})
     * @param threshold intensity threshold
     * @param minSize minimum object size (voxels)
     * @param maxSize maximum object size (voxels)
     * @param excludeOnEdges whether to exclude objects touching edges
     * @param redirect whether to redirect intensity measurements to the image titled by prefs key
     *                 {@code 3D-OC-Options_redirectTo.string}
     * @param wantObjectsMap whether to compute an objects map image
     * @param wantMaskedImage whether to compute/show a masked image (only meaningful when redirect is true)
     */
    public Result run(
            ImagePlus img,
            int threshold,
            int minSize,
            int maxSize,
            boolean excludeOnEdges,
            boolean redirect,
            boolean wantObjectsMap,
            boolean wantMaskedImage
    ) {
        return run(img, threshold, minSize, maxSize, excludeOnEdges, redirect, null, wantObjectsMap, wantMaskedImage);
    }

    /**
     * Same as {@link #run(ImagePlus, int, int, int, boolean, boolean, boolean, boolean)} but lets the caller
     * specify the redirect target title deterministically.
     */
    public Result run(
            ImagePlus img,
            int threshold,
            int minSize,
            int maxSize,
            boolean excludeOnEdges,
            boolean redirect,
            String redirectToTitle,
            boolean wantObjectsMap,
            boolean wantMaskedImage
    ) {
        if (img == null) throw new IllegalArgumentException("img is null");

        // Counter3D consults Prefs to decide whether to build/show the masked image.
        // We set it explicitly to ensure deterministic behavior.
        Prefs.set("3D-OC-Options_showMaskedImg.boolean", wantMaskedImage);

        // Deterministic redirect target (avoids needing to call "3D OC Options" via IJ.run).
        if (redirectToTitle != null && !redirectToTitle.trim().isEmpty()) {
            Prefs.set("3D-OC-Options_redirectTo.string", redirectToTitle);
        }

        // Counter3D will throw NPE in prepareImgArrayForRedirect() if redirect is true but the
        // redirect image is not found. In the original plugin this is guarded before running.
        boolean safeRedirect = redirect;
        if (safeRedirect) {
            String redirTitle = Prefs.get("3D-OC-Options_redirectTo.string", "none");
            ImagePlus redir = (redirTitle == null || "none".equalsIgnoreCase(redirTitle)) ? null : WindowManager.getImage(redirTitle);
            if (redir == null) {
                safeRedirect = false;
            }
        }

        Counter3D oc = new Counter3D(img, threshold, minSize, maxSize, excludeOnEdges, safeRedirect);

        // Build a macro-compatible statistics table WITHOUT calling Counter3D.showStatistics(),
        // to avoid UI windows and global ResultsTable state.
        ResultsTable stats = buildStatisticsTable(oc, img.getCalibration());

        ImagePlus objectsMap = null;
        if (wantObjectsMap) {
            int fontSize = (int) Prefs.get("3D-OC-Options_fontSize.double", 10);
            boolean showNb = Prefs.get("3D-OC-Options_showNb.boolean", true);
            try {
                objectsMap = oc.getObjMap(showNb, fontSize);
            } catch (NullPointerException npe) {
                // Counter3D can throw NPE here when no objects were found (it leaves internal arrays null).
                objectsMap = null;
            }
        }

        // Masked image: Counter3D builds and shows it internally when wantMaskedImage is true.
        // We try to fetch it by title (stable in Counter3D: "Masked image for "+title).
        ImagePlus masked = null;
        if (wantMaskedImage) {
            masked = findOpenImageTitleContains("masked image for ");
        }

        boolean foundObjects = stats != null && stats.size() > 0;
        return new Result(stats, objectsMap, masked, foundObjects);
    }

    // ──────────────────────────────────────────────────────────────────────
    //  Native mcib3d implementation — fully thread-safe, no global state
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Thread-safe 3D object counting using mcib3d-core ({@code ImageLabeller} +
     * {@code Objects3DIntPopulation} + per-object measurement classes).
     *
     * <p>Unlike the legacy {@link #run} method, this does NOT touch {@link Prefs},
     * {@link WindowManager}, or any other global state. It can run concurrently
     * from multiple threads without locks.
     *
     * @param img             thresholded/filtered image (pixels &ge; {@code threshold} are foreground)
     * @param threshold       intensity threshold (pixels strictly below this are zeroed)
     * @param minSize         minimum object size in voxels
     * @param maxSize         maximum object size in voxels
     * @param excludeOnEdges  whether to exclude objects touching image borders
     * @param redirectImage   optional unfiltered image for intensity measurements (may be null)
     * @param wantObjectsMap  whether to return a labelled objects-map image
     * @param wantMaskedImage whether to build a masked (redirect * label mask) image
     * @return same {@link Result} as the legacy method, column-compatible
     * @throws NoClassDefFoundError if mcib3d-core is not on the classpath
     */
    public Result runNative(
            ImagePlus img,
            int threshold,
            int minSize,
            int maxSize,
            boolean excludeOnEdges,
            ImagePlus redirectImage,
            boolean wantObjectsMap,
            boolean wantMaskedImage
    ) {
        if (img == null) throw new IllegalArgumentException("img is null");

        // 1. Create a binary (thresholded) copy — mcib3d ImageLabeller labels all non-zero voxels
        ImagePlus thresholded = thresholdCopy(img, threshold);

        // 2. Connected component labelling via mcib3d ImageLabeller
        mcib3d.image3d.ImageHandler threshIH = mcib3d.image3d.ImageHandler.wrap(thresholded);
        mcib3d.image3d.ImageLabeller labeller = new mcib3d.image3d.ImageLabeller();
        labeller.setMinSize(minSize);
        labeller.setMaxSize(maxSize);
        mcib3d.image3d.ImageHandler labelledIH = labeller.getLabels(threshIH);

        // Wrap as ImagePlus for downstream use
        ImagePlus labelledImp = labelledIH.getImagePlus();
        labelledImp.setCalibration(img.getCalibration() != null ? img.getCalibration().copy() : null);

        // 3. Build population from labelled image
        mcib3d.geom2.Objects3DIntPopulation population =
                new mcib3d.geom2.Objects3DIntPopulation(labelledIH);

        // 3b. Exclude border-touching objects if requested
        if (excludeOnEdges) {
            mcib3d.geom2.Objects3DIntPopulationComputation popComp =
                    new mcib3d.geom2.Objects3DIntPopulationComputation(population);
            population = popComp.getExcludeBorders(labelledIH, false);
        }

        int nbObjects = population.getNbObjects();

        // 4. Build statistics table with the same columns as the legacy method
        Calibration cal = img.getCalibration();
        mcib3d.image3d.ImageHandler convergenceIH =
                (redirectImage != null) ? mcib3d.image3d.ImageHandler.wrap(redirectImage) : null;
        ResultsTable stats = buildNativeStatisticsTable(population, cal, convergenceIH);

        // 5. Objects map
        ImagePlus objectsMap = null;
        if (wantObjectsMap) {
            objectsMap = ImageOps.duplicateThreadSafe(labelledImp);
            objectsMap.setTitle("Objects map of " + img.getTitle());
        }

        // 6. Masked image (redirect image masked by segmented objects)
        ImagePlus masked = null;
        if (wantMaskedImage && redirectImage != null && nbObjects > 0) {
            masked = buildMaskedImage(redirectImage, labelledImp);
            masked.setTitle("Masked image for " + img.getTitle());
        }

        // Clean up temporary thresholded image
        thresholded.changes = false;
        thresholded.close();

        boolean foundObjects = nbObjects > 0;
        return new Result(stats, objectsMap, masked, foundObjects);
    }

    /**
     * Thread-safe 3D object counting from a pre-computed label image (e.g. from StarDist 3D).
     * Skips the labelling step entirely — the label image already contains unique integer labels
     * per object. Builds the same {@link Result} as {@link #runNative}.
     *
     * @param labelImage      pre-labelled image (each object has a unique integer value, background is 0)
     * @param redirectImage   optional unfiltered image for intensity measurements (may be null)
     * @param wantObjectsMap  whether to return a labelled objects-map image
     * @param wantMaskedImage whether to build a masked (redirect * label mask) image
     * @return same {@link Result} as other methods, column-compatible
     * @throws NoClassDefFoundError if mcib3d-core is not on the classpath
     */
    public Result fromLabelImage(
            ImagePlus labelImage,
            ImagePlus redirectImage,
            boolean wantObjectsMap,
            boolean wantMaskedImage
    ) {
        return fromLabelImage(labelImage, redirectImage, 0, Integer.MAX_VALUE,
                wantObjectsMap, wantMaskedImage);
    }

    public Result fromLabelImage(
            ImagePlus labelImage,
            ImagePlus redirectImage,
            int minSize,
            int maxSize,
            boolean wantObjectsMap,
            boolean wantMaskedImage
    ) {
        if (labelImage == null) throw new IllegalArgumentException("labelImage is null");

        ImagePlus filteredLabelImage = filterLabelImageBySize(labelImage, minSize, maxSize);
        boolean closeFiltered = filteredLabelImage != labelImage;

        try {
            mcib3d.image3d.ImageHandler labelledIH = mcib3d.image3d.ImageHandler.wrap(filteredLabelImage);

            // Build population directly from pre-existing labels
            mcib3d.geom2.Objects3DIntPopulation population =
                    new mcib3d.geom2.Objects3DIntPopulation(labelledIH);

            int nbObjects = population.getNbObjects();

            // Build statistics table with the same columns as runNative
            Calibration cal = filteredLabelImage.getCalibration();
            mcib3d.image3d.ImageHandler convergenceIH =
                    (redirectImage != null) ? mcib3d.image3d.ImageHandler.wrap(redirectImage) : null;
            ResultsTable stats = buildNativeStatisticsTable(population, cal, convergenceIH);

            // Objects map
            ImagePlus objectsMap = null;
            if (wantObjectsMap) {
                objectsMap = ImageOps.duplicateThreadSafe(filteredLabelImage);
                objectsMap.setTitle("Objects map of " + labelImage.getTitle());
            }

            // Masked image
            ImagePlus masked = null;
            if (wantMaskedImage && redirectImage != null && nbObjects > 0) {
                masked = buildMaskedImage(redirectImage, filteredLabelImage);
                masked.setTitle("Masked image for " + labelImage.getTitle());
            }

            boolean foundObjects = nbObjects > 0;
            return new Result(stats, objectsMap, masked, foundObjects);
        } finally {
            if (closeFiltered) {
                filteredLabelImage.changes = false;
                filteredLabelImage.close();
                filteredLabelImage.flush();
            }
        }
    }

    private static ImagePlus filterLabelImageBySize(ImagePlus labelImage, int minSize, int maxSize) {
        if (labelImage == null || labelImage.getStack() == null) return labelImage;
        int safeMin = Math.max(0, minSize);
        int safeMax = Math.max(safeMin, maxSize);
        if (safeMin <= 0 && safeMax == Integer.MAX_VALUE) return labelImage;

        Map<Integer, Integer> voxelsByLabel = new HashMap<Integer, Integer>();
        ImageStack stack = labelImage.getStack();
        for (int slice = 1; slice <= stack.getSize(); slice++) {
            ImageProcessor processor = stack.getProcessor(slice);
            if (processor == null) continue;
            for (int i = 0; i < processor.getPixelCount(); i++) {
                int label = labelFromPixel(processor.getf(i));
                if (label <= 0) continue;
                Integer previous = voxelsByLabel.get(Integer.valueOf(label));
                voxelsByLabel.put(Integer.valueOf(label),
                        Integer.valueOf(previous == null ? 1 : incrementVoxelCount(previous.intValue())));
            }
        }
        if (voxelsByLabel.isEmpty()) return labelImage;

        Set<Integer> labelsToRemove = new HashSet<Integer>();
        for (Map.Entry<Integer, Integer> entry : voxelsByLabel.entrySet()) {
            int voxels = entry.getValue().intValue();
            if (voxels < safeMin || voxels > safeMax) {
                labelsToRemove.add(entry.getKey());
            }
        }
        if (labelsToRemove.isEmpty()) return labelImage;

        ImagePlus filtered = ImageOps.duplicateThreadSafe(labelImage);
        filtered.setTitle(labelImage.getTitle() + " size-filtered");
        ImageStack filteredStack = filtered.getStack();
        for (int slice = 1; slice <= filteredStack.getSize(); slice++) {
            ImageProcessor processor = filteredStack.getProcessor(slice);
            if (processor == null) continue;
            for (int i = 0; i < processor.getPixelCount(); i++) {
                int label = labelFromPixel(processor.getf(i));
                if (label > 0 && labelsToRemove.contains(Integer.valueOf(label))) {
                    processor.setf(i, 0f);
                }
            }
        }
        return filtered;
    }

    /**
     * Returns true if mcib3d-core classes are available at runtime.
     * Call once at startup to decide between native and legacy paths.
     */
    public static boolean isMcib3dAvailable() {
        try {
            Class.forName("mcib3d.image3d.ImageLabeller");
            Class.forName("mcib3d.geom2.Objects3DIntPopulation");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    /**
     * Create a binary copy of {@code img} where pixels &ge; threshold keep their value
     * and pixels below threshold are zeroed. This replicates Counter3D's internal
     * thresholding behaviour so that ImageLabeller labels the correct foreground.
     */
    private static ImagePlus thresholdCopy(ImagePlus img, int threshold) {
        ImagePlus dup = ImageOps.duplicateThreadSafe(img);
        ImageStack stack = dup.getStack();
        for (int s = 1; s <= stack.size(); s++) {
            ImageProcessor ip = stack.getProcessor(s);
            for (int i = 0; i < ip.getPixelCount(); i++) {
                if (ip.getf(i) < threshold) {
                    ip.setf(i, 0f);
                } else if (!Float.isFinite(ip.getf(i))) {
                    ip.setf(i, 0f);
                }
            }
        }
        return dup;
    }

    /**
     * Build a ResultsTable from mcib3d population measurements, with the same columns
     * as the legacy {@link #buildStatisticsTable} so downstream code is unaffected.
     */
    private static ResultsTable buildNativeStatisticsTable(
            mcib3d.geom2.Objects3DIntPopulation population,
            Calibration cal,
            mcib3d.image3d.ImageHandler convergenceIH) {

        ResultsTable rt = new ResultsTable();
        if (population == null || population.getNbObjects() == 0) return rt;

        String unit = cal == null ? "pixel" : cal.getUnit();
        double voxelVol = 1.0;
        if (cal != null) voxelVol = cal.pixelWidth * cal.pixelHeight * cal.pixelDepth;

        String volCol = "Volume (" + unit + "^3)";
        String surfCol = "Surface (" + unit + "^2)";

        java.util.List<mcib3d.geom2.Object3DInt> objects = population.getObjects3DInt();
        for (int i = 0; i < objects.size(); i++) {
            mcib3d.geom2.Object3DInt obj = objects.get(i);
            rt.incrementCounter();

            // Volume: voxel count * calibrated voxel volume
            mcib3d.geom2.measurements.MeasureVolume mv = new mcib3d.geom2.measurements.MeasureVolume(obj);
            double volumePix = mv.getVolumePix();
            rt.setValue(volCol, i, volumePix * voxelVol);

            // Surface (calibrated)
            mcib3d.geom2.measurements.MeasureSurface ms = new mcib3d.geom2.measurements.MeasureSurface(obj);
            double surfUnit = ms.getSurfaceContactUnit();
            rt.setValue(surfCol, i, surfUnit);

            // Intensity measurements — use redirect image if provided
            double intDen = 0;
            double mean = 0;
            if (convergenceIH != null) {
                mcib3d.geom2.measurements.MeasureIntensity mi =
                        new mcib3d.geom2.measurements.MeasureIntensity(obj);
                mi.setIntensityImage(convergenceIH);
                // getValueMeasurement(name) returns Double for the named measurement
                Double sumVal = mi.getValueMeasurement(mcib3d.geom2.measurements.MeasureIntensity.INTENSITY_SUM);
                if (sumVal != null) intDen = sumVal;
                Double avgVal = mi.getValueMeasurement(mcib3d.geom2.measurements.MeasureIntensity.INTENSITY_AVG);
                if (avgVal != null) mean = avgVal;
            }
            rt.setValue("IntDen", i, intDen);
            rt.setValue("Mean", i, mean);

            // Centre of mass — uses redirect image for intensity-weighted centre
            double comX = 0, comY = 0, comZ = 0;
            if (convergenceIH != null) {
                try {
                    mcib3d.geom2.measurements.MeasureCenterOfMass mcom =
                            new mcib3d.geom2.measurements.MeasureCenterOfMass(obj, convergenceIH);
                    Double cx = mcom.getValueMeasurement(mcib3d.geom2.measurements.MeasureCenterOfMass.MASS_CENTER_X_PIX);
                    Double cy = mcom.getValueMeasurement(mcib3d.geom2.measurements.MeasureCenterOfMass.MASS_CENTER_Y_PIX);
                    Double cz = mcom.getValueMeasurement(mcib3d.geom2.measurements.MeasureCenterOfMass.MASS_CENTER_Z_PIX);
                    if (cx != null) comX = cx;
                    if (cy != null) comY = cy;
                    if (cz != null) comZ = cz;
                } catch (Exception e) {
                    // Fallback to geometric centroid if center of mass fails
                    try {
                        mcib3d.geom2.measurements.MeasureCentroid mc =
                                new mcib3d.geom2.measurements.MeasureCentroid(obj);
                        Double cx = mc.getValueMeasurement("CentroidX");
                        Double cy = mc.getValueMeasurement("CentroidY");
                        Double cz = mc.getValueMeasurement("CentroidZ");
                        if (cx != null) comX = cx;
                        if (cy != null) comY = cy;
                        if (cz != null) comZ = cz;
                    } catch (Exception ignored) {
                    }
                }
            }
            rt.setValue("XM", i, comX);
            rt.setValue("YM", i, comY);
            rt.setValue("ZM", i, comZ);

            // Label: pixel value in the label image (used by CPC colocalization)
            rt.setValue("Label", i, (int) obj.getLabel());
        }

        return rt;
    }

    /**
     * Create a masked image: for each voxel, if the labelled image has a non-zero label,
     * copy the corresponding voxel from the redirect image; otherwise set to zero.
     * This replicates Counter3D's "Masked image" output.
     */
    private static ImagePlus buildMaskedImage(ImagePlus redirectImage, ImagePlus labelledImage) {
        ImagePlus masked = ImageOps.duplicateThreadSafe(redirectImage);
        masked.setTitle("Masked image");
        ImageStack maskedStack = masked.getStack();
        ImageStack labelStack = labelledImage.getStack();
        int nSlices = Math.min(maskedStack.size(), labelStack.size());
        for (int s = 1; s <= nSlices; s++) {
            ImageProcessor mp = maskedStack.getProcessor(s);
            ImageProcessor lp = labelStack.getProcessor(s);
            for (int i = 0; i < mp.getPixelCount(); i++) {
                if (lp.getf(i) == 0) {
                    mp.set(i, 0);
                }
            }
        }
        return masked;
    }

    // ──────────────────────────────────────────────────────────────────────
    //  Utility methods
    // ──────────────────────────────────────────────────────────────────────

    /** Converts a token like "Infinity" into a voxel count. */
    public static int parseMaxSizeVoxels(String token, ImagePlus reference) {
        if (token == null) return maxPossibleVoxels(reference);
        String t = token.trim();
        if (t.isEmpty()) return maxPossibleVoxels(reference);
        if ("infinity".equalsIgnoreCase(t) || "inf".equalsIgnoreCase(t)) return maxPossibleVoxels(reference);
        return parseFiniteVoxelCount(t, maxPossibleVoxels(reference));
    }

    public static int parseMinSizeVoxels(String token, int fallback) {
        if (token == null) return fallback;
        String t = token.trim();
        if (t.isEmpty()) return fallback;
        return parseFiniteVoxelCount(t, fallback);
    }

    private static int maxPossibleVoxels(ImagePlus imp) {
        if (imp == null) return Integer.MAX_VALUE;
        long vox = (long) imp.getWidth() * (long) imp.getHeight() * (long) imp.getNSlices();
        return vox > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) vox;
    }

    private static int parseFiniteVoxelCount(String token, int fallback) {
        double parsed = Double.parseDouble(token);
        if (!Double.isFinite(parsed)) return fallback;
        if (parsed <= 0) return 0;
        if (parsed >= Integer.MAX_VALUE) return Integer.MAX_VALUE;
        return (int) Math.round(parsed);
    }

    private static int incrementVoxelCount(int current) {
        return current == Integer.MAX_VALUE ? Integer.MAX_VALUE : current + 1;
    }

    private static int labelFromPixel(float value) {
        if (!Float.isFinite(value) || value <= 0f) return 0;
        return value > Integer.MAX_VALUE ? 0 : Math.round(value);
    }

    private static ResultsTable copyOf(ResultsTable src) {
        ResultsTable dst = new ResultsTable();
        if (src == null || src.size() == 0) return dst;

        String[] headings = src.getHeadings();
        if (headings != null) {
            for (int i = 0; i < headings.length; i++) {
                String h = headings[i];
                if (h != null && !h.isEmpty()) setTableHeading(dst, i, h);
            }
        }

        for (int r = 0; r < src.size(); r++) {
            dst.incrementCounter();
            if (headings != null) {
                for (String h : headings) {
                    if (h == null || h.isEmpty()) continue;
                    try {
                        double v = src.getValue(h, r);
                        dst.setValue(h, r, v);
                    } catch (Exception ignored) {
                        // ignore non-numeric columns
                    }
                }
            }
        }
        return dst;
    }

    /**
     * Create a ResultsTable with one row per detected object, using the underlying Object3D fields.
     * Uses reflection because Object3D is packaged inside the 3D Objects Counter dependency.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static ResultsTable buildStatisticsTable(Counter3D oc, Calibration cal) {
        ResultsTable rt = new ResultsTable();
        if (oc == null) return rt;

        Vector objs;
        try {
            objs = oc.getObjectsList();
        } catch (Throwable t) {
            // Counter3D may call IJ.error("No object found") and still throw; treat as empty.
            return rt;
        }
        if (objs == null || objs.isEmpty()) return rt;

        String unit = cal == null ? "pixel" : cal.getUnit();
        double voxelVol = 1.0;
        if (cal != null) voxelVol = cal.pixelWidth * cal.pixelHeight * cal.pixelDepth;

        // Headings to match the subset your pipeline exports.
        setTableHeading(rt, 0, "Volume (" + unit + "^3)");
        setTableHeading(rt, 1, "Surface (" + unit + "^2)");
        setTableHeading(rt, 2, "IntDen");
        setTableHeading(rt, 3, "Mean");
        setTableHeading(rt, 4, "XM");
        setTableHeading(rt, 5, "YM");
        setTableHeading(rt, 6, "ZM");

        for (int i = 0; i < objs.size(); i++) {
            Object o = objs.get(i);
            if (o == null) continue;

            rt.incrementCounter();

            // size (voxels)
            double size = getDoubleField(o, "size", 0);
            double volume = size * voxelVol;
            rt.setValue("Volume (" + unit + "^3)", i, volume);

            // surface calibrated
            double surface = getDoubleField(o, "surf_cal", 0);
            rt.setValue("Surface (" + unit + "^2)", i, surface);

            // intensities
            double intDen = getDoubleField(o, "int_dens", 0);
            rt.setValue("IntDen", i, intDen);

            double mean = getDoubleField(o, "mean_gray", 0);
            rt.setValue("Mean", i, mean);

            // centre of mass array: c_mass[0..2]
            double[] com = getDoubleArrayField(o, "c_mass");
            if (com != null) {
                if (com.length > 0) rt.setValue("XM", i, com[0]);
                if (com.length > 1) rt.setValue("YM", i, com[1]);
                if (com.length > 2) rt.setValue("ZM", i, com[2]);
            } else {
                rt.setValue("XM", i, 0);
                rt.setValue("YM", i, 0);
                rt.setValue("ZM", i, 0);
            }

            // Label: Counter3D uses 1-based sequential labels
            rt.setValue("Label", i, i + 1);
        }

        return rt;
    }

    private static double getDoubleField(Object obj, String fieldName, double fallback) {
        try {
            Field f = obj.getClass().getField(fieldName);
            Object v = f.get(obj);
            if (v instanceof Number) return ((Number) v).doubleValue();
        } catch (Exception ignored) {
        }
        return fallback;
    }

    @SuppressWarnings("deprecation")
    private static void setTableHeading(ResultsTable table, int column, String heading) {
        table.setHeading(column, heading);
    }

    private static double[] getDoubleArrayField(Object obj, String fieldName) {
        try {
            Field f = obj.getClass().getField(fieldName);
            Object v = f.get(obj);
            if (v instanceof float[]) {
                float[] a = (float[]) v;
                double[] d = new double[a.length];
                for (int i = 0; i < a.length; i++) d[i] = a[i];
                return d;
            }
            if (v instanceof double[]) {
                return (double[]) v;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static ImagePlus findOpenImageTitleContains(String needleLower) {
        if (needleLower == null) return null;
        int[] ids = WindowManager.getIDList();
        if (ids == null) return null;
        for (int id : ids) {
            ImagePlus imp = WindowManager.getImage(id);
            if (imp == null) continue;
            String title = imp.getTitle();
            if (title == null) continue;
            if (title.toLowerCase(Locale.ROOT).contains(needleLower)) return imp;
        }
        return null;
    }
}
