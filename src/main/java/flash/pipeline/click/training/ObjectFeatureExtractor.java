package flash.pipeline.click.training;

import flash.pipeline.objects.LabelIndex;
import flash.pipeline.stardist.StarDist3DRunner;
import ij.ImagePlus;
import ij.ImageStack;
import ij.measure.Calibration;
import ij.measure.ResultsTable;
import ij.process.ImageProcessor;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Extracts one stable feature vector per labelled object.
 */
public final class ObjectFeatureExtractor {
    private static final int MAX_EXACT_FLOAT_LABEL = 16_777_216;
    public static final String FEATURE_VOLUME = "volume";
    public static final String FEATURE_SURFACE_AREA = "surface_area";
    public static final String FEATURE_FERET_DIAMETER_MAX = "feret_diameter_max";
    public static final String FEATURE_SPHERICITY = "sphericity";
    public static final String FEATURE_ELONGATION = "elongation";
    public static final String FEATURE_COMPACTNESS = "compactness";
    public static final String FEATURE_MEAN_INTENSITY = "mean_intensity";
    public static final String FEATURE_STD_INTENSITY = "std_intensity";
    public static final String FEATURE_MIN_INTENSITY = "min_intensity";
    public static final String FEATURE_MAX_INTENSITY = "max_intensity";
    public static final String FEATURE_CENTROID_Z = "centroid_z";
    public static final String FEATURE_QUALITY = "quality";
    public static final String FEATURE_MEAN_CELLPROB = "mean_cellprob";
    public static final String FEATURE_STD_CELLPROB = "std_cellprob";

    private static final String[] UNIVERSAL_FEATURE_NAMES = new String[] {
            FEATURE_VOLUME,
            FEATURE_SURFACE_AREA,
            FEATURE_FERET_DIAMETER_MAX,
            FEATURE_SPHERICITY,
            FEATURE_ELONGATION,
            FEATURE_COMPACTNESS,
            FEATURE_MEAN_INTENSITY,
            FEATURE_STD_INTENSITY,
            FEATURE_MIN_INTENSITY,
            FEATURE_MAX_INTENSITY,
            FEATURE_CENTROID_Z
    };

    private static final String[] ALL_FEATURE_NAMES = new String[] {
            FEATURE_VOLUME,
            FEATURE_SURFACE_AREA,
            FEATURE_FERET_DIAMETER_MAX,
            FEATURE_SPHERICITY,
            FEATURE_ELONGATION,
            FEATURE_COMPACTNESS,
            FEATURE_MEAN_INTENSITY,
            FEATURE_STD_INTENSITY,
            FEATURE_MIN_INTENSITY,
            FEATURE_MAX_INTENSITY,
            FEATURE_CENTROID_Z,
            FEATURE_QUALITY,
            FEATURE_MEAN_CELLPROB,
            FEATURE_STD_CELLPROB
    };

    private static final Map<String, Integer> FEATURE_INDEX = featureIndex();

    /** Typed boundary failure raised before any partially registered row is created. */
    public static final class GeometryMismatchException extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;

        GeometryMismatchException(String message) {
            super(message);
        }
    }

    public static final class FeatureRow implements Serializable {
        private static final long serialVersionUID = 1L;

        public final int label;
        public final double[] features;
        public final String[] featureNames;

        public FeatureRow(int label, double[] features, String[] featureNames) {
            if (features == null) {
                throw new IllegalArgumentException("features must not be null");
            }
            if (featureNames == null) {
                throw new IllegalArgumentException("featureNames must not be null");
            }
            if (features.length != featureNames.length) {
                throw new IllegalArgumentException("features and featureNames must have the same length");
            }
            this.label = label;
            this.features = Arrays.copyOf(features, features.length);
            this.featureNames = Arrays.copyOf(featureNames, featureNames.length);
        }

        public FeatureRow(int label, Map<String, Double> featuresByName) {
            this(label, valuesFromMap(featuresByName, ALL_FEATURE_NAMES), ALL_FEATURE_NAMES);
        }

        public double value(String featureName) {
            int index = indexOf(featureNames, featureName);
            return index < 0 ? Double.NaN : features[index];
        }

        public Map<String, Double> asMap() {
            Map<String, Double> out = new LinkedHashMap<String, Double>();
            for (int i = 0; i < featureNames.length; i++) {
                out.put(featureNames[i], Double.valueOf(features[i]));
            }
            return Collections.unmodifiableMap(out);
        }
    }

    public List<FeatureRow> extractFromLabelImage(ImagePlus labelImage,
                                                  ImagePlus rawChannelImage,
                                                  ImagePlus auxImage,
                                                  Set<Integer> labelsOfInterest) {
        if (labelsOfInterest != null && labelsOfInterest.isEmpty()) {
            return Collections.emptyList();
        }
        if (labelImage == null || labelImage.getStack() == null) {
            return Collections.emptyList();
        }
        requireSupportedGeometry(labelImage, "label image");
        if (rawChannelImage != null) {
            requireRegisteredPair(labelImage, rawChannelImage, "raw channel image");
        }
        if (auxImage != null) {
            requireRegisteredPair(labelImage, auxImage, "auxiliary image");
        }

        Set<Integer> wantedLabels = labelsOfInterest == null
                ? null
                : new HashSet<Integer>(labelsOfInterest);
        Set<Integer> canonicalPixelLabels = canonicalLabels(labelImage, null);
        Set<Integer> expectedLabels = new HashSet<Integer>(canonicalPixelLabels);
        if (wantedLabels != null) expectedLabels.retainAll(wantedLabels);
        Map<Integer, PixelStats> intensity = pixelStatsByLabel(labelImage, rawChannelImage, wantedLabels);
        Map<Integer, PixelStats> cellprob = pixelStatsByLabel(labelImage, auxImage, wantedLabels);
        Map<Integer, Double> quality = qualityByLabel(labelImage, canonicalPixelLabels);

        mcib3d.image3d.ImageHandler labelHandler = mcib3d.image3d.ImageHandler.wrap(labelImage);
        mcib3d.geom2.Objects3DIntPopulation population =
                new mcib3d.geom2.Objects3DIntPopulation(labelHandler);
        List<mcib3d.geom2.Object3DInt> objects = population.getObjects3DInt();

        List<FeatureRow> rows = new ArrayList<FeatureRow>();
        Set<Integer> emittedLabels = new HashSet<Integer>();
        for (int i = 0; i < objects.size(); i++) {
            mcib3d.geom2.Object3DInt object = objects.get(i);
            int label = checkedPositiveLabel(object.getLabel(),
                    "mcib3d object " + i);
            if (wantedLabels != null && !wantedLabels.contains(Integer.valueOf(label))) continue;
            if (!emittedLabels.add(Integer.valueOf(label))) {
                throw labelFailure("mcib3d emitted duplicate feature object label " + label + ".");
            }

            double[] features = nanArray(ALL_FEATURE_NAMES.length);
            put(features, FEATURE_VOLUME, safeVolume(object));
            put(features, FEATURE_SURFACE_AREA, safeSurface(object));
            put(features, FEATURE_FERET_DIAMETER_MAX, safeFeret(object));
            put(features, FEATURE_SPHERICITY, safeCompactness(object,
                    mcib3d.geom2.measurements.MeasureCompactness.SPHER_CORRECTED));
            put(features, FEATURE_ELONGATION, safeEllipsoid(object,
                    mcib3d.geom2.measurements.MeasureEllipsoid.ELL_ELONGATION));
            put(features, FEATURE_COMPACTNESS, safeCompactness(object,
                    mcib3d.geom2.measurements.MeasureCompactness.COMP_CORRECTED));
            put(features, FEATURE_CENTROID_Z, safeCentroidZ(object));

            PixelStats rawStats = intensity.get(Integer.valueOf(label));
            if (rawStats != null && rawStats.count > 0L) {
                put(features, FEATURE_MEAN_INTENSITY, rawStats.mean());
                put(features, FEATURE_STD_INTENSITY, rawStats.std());
                put(features, FEATURE_MIN_INTENSITY, rawStats.min);
                put(features, FEATURE_MAX_INTENSITY, rawStats.max);
            }

            Double starDistQuality = quality.get(Integer.valueOf(label));
            if (starDistQuality != null) {
                put(features, FEATURE_QUALITY, starDistQuality.doubleValue());
            }

            PixelStats cellprobStats = cellprob.get(Integer.valueOf(label));
            if (cellprobStats != null && cellprobStats.count > 0L) {
                put(features, FEATURE_MEAN_CELLPROB, cellprobStats.mean());
                put(features, FEATURE_STD_CELLPROB, cellprobStats.std());
            }

            rows.add(new FeatureRow(label, features, ALL_FEATURE_NAMES));
        }
        if (!emittedLabels.equals(expectedLabels)) {
            Set<Integer> missing = new TreeSet<Integer>(expectedLabels);
            missing.removeAll(emittedLabels);
            Set<Integer> unexpected = new TreeSet<Integer>(emittedLabels);
            unexpected.removeAll(expectedLabels);
            throw labelFailure("feature extraction did not preserve the canonical pixel-label set; "
                    + "missing=" + missing + ", unexpected=" + unexpected + ".");
        }
        Collections.sort(rows, new java.util.Comparator<FeatureRow>() {
            @Override
            public int compare(FeatureRow left, FeatureRow right) {
                return Integer.compare(left.label, right.label);
            }
        });
        return rows;
    }

    public String[] universalFeatureNames() {
        return Arrays.copyOf(UNIVERSAL_FEATURE_NAMES, UNIVERSAL_FEATURE_NAMES.length);
    }

    public String[] featureNames() {
        return Arrays.copyOf(ALL_FEATURE_NAMES, ALL_FEATURE_NAMES.length);
    }

    public static String[] allFeatureNames() {
        return Arrays.copyOf(ALL_FEATURE_NAMES, ALL_FEATURE_NAMES.length);
    }

    static double alignedValue(FeatureRow row, String featureName) {
        if (row == null || featureName == null) return Double.NaN;
        return row.value(featureName);
    }

    private static double[] valuesFromMap(Map<String, Double> values, String[] names) {
        double[] out = nanArray(names.length);
        if (values == null) return out;
        for (int i = 0; i < names.length; i++) {
            Double value = values.get(names[i]);
            out[i] = value == null ? Double.NaN : value.doubleValue();
        }
        return out;
    }

    private static Map<String, Integer> featureIndex() {
        Map<String, Integer> out = new HashMap<String, Integer>();
        for (int i = 0; i < ALL_FEATURE_NAMES.length; i++) {
            out.put(ALL_FEATURE_NAMES[i], Integer.valueOf(i));
        }
        return Collections.unmodifiableMap(out);
    }

    private static int indexOf(String[] names, String featureName) {
        if (names == null || featureName == null) return -1;
        for (int i = 0; i < names.length; i++) {
            if (featureName.equals(names[i])) return i;
        }
        return -1;
    }

    private static double[] nanArray(int size) {
        double[] out = new double[size];
        Arrays.fill(out, Double.NaN);
        return out;
    }

    private static void put(double[] features, String name, double value) {
        Integer index = FEATURE_INDEX.get(name);
        if (index != null) {
            features[index.intValue()] = value;
        }
    }

    private static double safeVolume(mcib3d.geom2.Object3DInt object) {
        try {
            return new mcib3d.geom2.measurements.MeasureVolume(object).getVolumePix();
        } catch (RuntimeException e) {
            return Double.NaN;
        }
    }

    private static double safeSurface(mcib3d.geom2.Object3DInt object) {
        try {
            return new mcib3d.geom2.measurements.MeasureSurface(object).getSurfaceContactUnit();
        } catch (RuntimeException e) {
            return Double.NaN;
        }
    }

    private static double safeFeret(mcib3d.geom2.Object3DInt object) {
        try {
            mcib3d.geom2.measurements.MeasureFeret feret =
                    new mcib3d.geom2.measurements.MeasureFeret(object);
            return safeMeasurement(feret, mcib3d.geom2.measurements.MeasureFeret.FERET_UNIT);
        } catch (RuntimeException e) {
            return Double.NaN;
        }
    }

    private static double safeCompactness(mcib3d.geom2.Object3DInt object, String name) {
        try {
            mcib3d.geom2.measurements.MeasureCompactness compactness =
                    new mcib3d.geom2.measurements.MeasureCompactness(object);
            return safeMeasurement(compactness, name);
        } catch (RuntimeException e) {
            return Double.NaN;
        }
    }

    private static double safeEllipsoid(mcib3d.geom2.Object3DInt object, String name) {
        try {
            mcib3d.geom2.measurements.MeasureEllipsoid ellipsoid =
                    new mcib3d.geom2.measurements.MeasureEllipsoid(object);
            return safeMeasurement(ellipsoid, name);
        } catch (RuntimeException e) {
            return Double.NaN;
        }
    }

    private static double safeCentroidZ(mcib3d.geom2.Object3DInt object) {
        try {
            mcib3d.geom2.measurements.MeasureCentroid centroid =
                    new mcib3d.geom2.measurements.MeasureCentroid(object);
            return safeMeasurement(centroid, "CentroidZ");
        } catch (RuntimeException e) {
            return Double.NaN;
        }
    }

    private static double safeMeasurement(mcib3d.geom2.measurements.MeasureAbstract measure,
                                          String name) {
        try {
            Double value = measure.getValueMeasurement(name);
            return value == null ? Double.NaN : value.doubleValue();
        } catch (RuntimeException e) {
            return Double.NaN;
        }
    }

    private static Map<Integer, Double> qualityByLabel(ImagePlus labelImage,
                                                       Set<Integer> canonicalPixelLabels) {
        ResultsTable table = LabelIndex.starDistStats(labelImage);
        if (table == null) return Collections.emptyMap();
        Map<Integer, Double> out = new HashMap<Integer, Double>();
        Set<Integer> seenLabels = new HashSet<Integer>();
        for (int row = 0; row < table.size(); row++) {
            int label = requiredLabelForRow(table, row);
            if (!seenLabels.add(Integer.valueOf(label))) {
                throw labelFailure("duplicate StarDist statistics label " + label
                        + " at row " + row + ".");
            }
            double quality = tableValue(table, StarDist3DRunner.STATS_QUALITY_MEAN, row);
            if (!Double.isFinite(quality)) {
                quality = tableValue(table, FEATURE_QUALITY, row);
            }
            if (label > 0 && Double.isFinite(quality)) {
                out.put(Integer.valueOf(label), Double.valueOf(quality));
            }
        }
        if (!seenLabels.equals(canonicalPixelLabels)) {
            Set<Integer> missingRows = new TreeSet<Integer>(canonicalPixelLabels);
            missingRows.removeAll(seenLabels);
            Set<Integer> unexpectedRows = new TreeSet<Integer>(seenLabels);
            unexpectedRows.removeAll(canonicalPixelLabels);
            throw labelFailure("feature pixel/statistics label sets differ; missing statistics="
                    + missingRows + ", statistics without pixels=" + unexpectedRows + ".");
        }
        return out;
    }

    private static int requiredLabelForRow(ResultsTable table, int row) {
        try {
            double label = table.getValue("Label", row);
            return checkedPositiveLabel(label, "StarDist statistics row " + row);
        } catch (RuntimeException e) {
            if (e instanceof IllegalArgumentException
                    && e.getMessage() != null
                    && e.getMessage().startsWith("LABEL_IDENTITY_UNSUPPORTED:")) {
                throw e;
            }
            throw labelFailure("missing canonical StarDist statistics Label at row " + row + ".", e);
        }
    }

    private static double tableValue(ResultsTable table, String column, int row) {
        try {
            double value = table.getValue(column, row);
            return Double.isFinite(value) ? value : Double.NaN;
        } catch (RuntimeException e) {
            return Double.NaN;
        }
    }

    private static Map<Integer, PixelStats> pixelStatsByLabel(ImagePlus labelImage,
                                                              ImagePlus valueImage,
                                                              Set<Integer> labelsOfInterest) {
        if (labelImage == null || valueImage == null
                || labelImage.getStack() == null || valueImage.getStack() == null) {
            return Collections.emptyMap();
        }
        int slices = labelImage.getStackSize();
        Map<Integer, PixelStats> out = new HashMap<Integer, PixelStats>();
        ImageStack labelStack = labelImage.getStack();
        ImageStack valueStack = valueImage.getStack();
        for (int slice = 1; slice <= slices; slice++) {
            ImageProcessor labels = labelStack.getProcessor(slice);
            ImageProcessor values = valueStack.getProcessor(slice);
            if (labels == null || values == null) continue;
            for (int i = 0; i < labels.getPixelCount(); i++) {
                int label = checkedLabelPixel(labels.getf(i), slice, i);
                if (label <= 0) continue;
                if (labelsOfInterest != null && !labelsOfInterest.contains(Integer.valueOf(label))) continue;
                float raw = values.getf(i);
                if (!Float.isFinite(raw)) continue;
                Integer key = Integer.valueOf(label);
                PixelStats stats = out.get(key);
                if (stats == null) {
                    stats = new PixelStats();
                    out.put(key, stats);
                }
                stats.add(raw);
            }
        }
        return out;
    }

    private static void requireRegisteredPair(ImagePlus labels,
                                              ImagePlus values,
                                              String valueRole) {
        requireSupportedGeometry(values, valueRole);
        if (labels.getWidth() != values.getWidth()
                || labels.getHeight() != values.getHeight()
                || labels.getNChannels() != values.getNChannels()
                || labels.getNSlices() != values.getNSlices()
                || labels.getNFrames() != values.getNFrames()
                || !sameSpatialCalibration(labels, values)) {
            throw new GeometryMismatchException("Object-feature image registration mismatch: labels "
                    + geometry(labels) + ", " + valueRole + " " + geometry(values)
                    + ". Expected identical X/Y/Z/C/T and spatial calibration; no feature rows were produced.");
        }
    }

    private static void requireSupportedGeometry(ImagePlus image, String role) {
        if (image == null || image.getStack() == null || image.getStackSize() <= 0) {
            throw new GeometryMismatchException("Object-feature " + role + " is missing or empty.");
        }
        long planes = (long) image.getNChannels() * (long) image.getNSlices()
                * (long) image.getNFrames();
        if (image.getWidth() <= 0 || image.getHeight() <= 0
                || image.getNChannels() != 1 || image.getNSlices() <= 0
                || image.getNFrames() != 1 || planes != image.getStackSize()) {
            throw new GeometryMismatchException("Unsupported object-feature " + role
                    + " geometry: " + geometry(image)
                    + ". Expected C=1 and T=1; time must not be flattened into Z.");
        }
    }

    private static boolean sameSpatialCalibration(ImagePlus first, ImagePlus second) {
        Calibration left = first == null ? null : first.getCalibration();
        Calibration right = second == null ? null : second.getCalibration();
        if (left == null || right == null) return false;
        return validSpatialCalibration(left) && validSpatialCalibration(right)
                && Double.compare(left.pixelWidth, right.pixelWidth) == 0
                && Double.compare(left.pixelHeight, right.pixelHeight) == 0
                && Double.compare(left.pixelDepth, right.pixelDepth) == 0
                && Double.compare(left.xOrigin, right.xOrigin) == 0
                && Double.compare(left.yOrigin, right.yOrigin) == 0
                && Double.compare(left.zOrigin, right.zOrigin) == 0
                && safeUnit(left).equals(safeUnit(right));
    }

    private static boolean validSpatialCalibration(Calibration calibration) {
        return calibration != null
                && Double.isFinite(calibration.pixelWidth) && calibration.pixelWidth > 0.0d
                && Double.isFinite(calibration.pixelHeight) && calibration.pixelHeight > 0.0d
                && Double.isFinite(calibration.pixelDepth) && calibration.pixelDepth > 0.0d
                && Double.isFinite(calibration.xOrigin)
                && Double.isFinite(calibration.yOrigin)
                && Double.isFinite(calibration.zOrigin);
    }

    private static String safeUnit(Calibration calibration) {
        String unit = calibration == null ? "" : calibration.getUnit();
        return unit == null ? "" : unit;
    }

    private static String geometry(ImagePlus image) {
        if (image == null) return "<null>";
        return "X=" + image.getWidth() + ",Y=" + image.getHeight()
                + ",Z=" + image.getNSlices() + ",C=" + image.getNChannels()
                + ",T=" + image.getNFrames() + ",stack=" + image.getStackSize();
    }

    private static Set<Integer> canonicalLabels(ImagePlus labelImage,
                                                 Set<Integer> labelsOfInterest) {
        Set<Integer> labels = new HashSet<Integer>();
        ImageStack stack = labelImage.getStack();
        for (int slice = 1; slice <= stack.getSize(); slice++) {
            ImageProcessor processor = stack.getProcessor(slice);
            for (int pixel = 0; pixel < processor.getPixelCount(); pixel++) {
                int label = checkedLabelPixel(processor.getf(pixel), slice, pixel);
                if (label > 0 && (labelsOfInterest == null
                        || labelsOfInterest.contains(Integer.valueOf(label)))) {
                    labels.add(Integer.valueOf(label));
                }
            }
        }
        return labels;
    }

    private static int checkedLabelPixel(float value, int slice, int pixel) {
        if (value == 0.0f) return 0;
        return checkedPositiveLabel(value, "label-image pixel " + pixel + " in slice " + slice);
    }

    private static int checkedPositiveLabel(double value, String location) {
        if (!Double.isFinite(value) || value <= 0.0d
                || value > MAX_EXACT_FLOAT_LABEL || value != Math.rint(value)) {
            throw labelFailure(location + " must be a positive integral label no greater than "
                    + MAX_EXACT_FLOAT_LABEL + "; found " + value + ".");
        }
        return (int) value;
    }

    private static IllegalArgumentException labelFailure(String message) {
        return new IllegalArgumentException("LABEL_IDENTITY_UNSUPPORTED: " + message);
    }

    private static IllegalArgumentException labelFailure(String message, Throwable cause) {
        return new IllegalArgumentException("LABEL_IDENTITY_UNSUPPORTED: " + message, cause);
    }

    private static final class PixelStats {
        long count;
        double sum;
        double sumSquares;
        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;

        void add(double value) {
            count++;
            sum += value;
            sumSquares += value * value;
            if (value < min) min = value;
            if (value > max) max = value;
        }

        double mean() {
            return count == 0L ? Double.NaN : sum / (double) count;
        }

        double std() {
            if (count == 0L) return Double.NaN;
            double mean = mean();
            double variance = (sumSquares / (double) count) - (mean * mean);
            return Math.sqrt(Math.max(0.0, variance));
        }
    }
}
