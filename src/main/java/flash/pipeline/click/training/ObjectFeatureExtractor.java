package flash.pipeline.click.training;

import flash.pipeline.objects.LabelIndex;
import flash.pipeline.stardist.StarDist3DRunner;
import ij.ImagePlus;
import ij.ImageStack;
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

/**
 * Extracts one stable feature vector per labelled object.
 */
public final class ObjectFeatureExtractor {
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

        Set<Integer> wantedLabels = labelsOfInterest == null
                ? null
                : new HashSet<Integer>(labelsOfInterest);
        Map<Integer, PixelStats> intensity = pixelStatsByLabel(labelImage, rawChannelImage, wantedLabels);
        Map<Integer, PixelStats> cellprob = pixelStatsByLabel(labelImage, auxImage, wantedLabels);
        Map<Integer, Double> quality = qualityByLabel(labelImage);

        mcib3d.image3d.ImageHandler labelHandler = mcib3d.image3d.ImageHandler.wrap(labelImage);
        mcib3d.geom2.Objects3DIntPopulation population =
                new mcib3d.geom2.Objects3DIntPopulation(labelHandler);
        List<mcib3d.geom2.Object3DInt> objects = population.getObjects3DInt();

        List<FeatureRow> rows = new ArrayList<FeatureRow>();
        for (int i = 0; i < objects.size(); i++) {
            mcib3d.geom2.Object3DInt object = objects.get(i);
            int label = Math.round(object.getLabel());
            if (label <= 0) continue;
            if (wantedLabels != null && !wantedLabels.contains(Integer.valueOf(label))) continue;

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

    private static Map<Integer, Double> qualityByLabel(ImagePlus labelImage) {
        ResultsTable table = LabelIndex.starDistStats(labelImage);
        if (table == null || table.size() == 0) return Collections.emptyMap();
        Map<Integer, Double> out = new HashMap<Integer, Double>();
        for (int row = 0; row < table.size(); row++) {
            int label = labelForRow(table, row);
            double quality = tableValue(table, StarDist3DRunner.STATS_QUALITY_MEAN, row);
            if (!Double.isFinite(quality)) {
                quality = tableValue(table, FEATURE_QUALITY, row);
            }
            if (label > 0 && Double.isFinite(quality)) {
                out.put(Integer.valueOf(label), Double.valueOf(quality));
            }
        }
        return out;
    }

    private static int labelForRow(ResultsTable table, int row) {
        try {
            double label = table.getValue("Label", row);
            if (Double.isFinite(label) && label > 0) return (int) Math.round(label);
        } catch (RuntimeException ignored) {
        }
        return row + 1;
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
        if (labelImage.getWidth() != valueImage.getWidth()
                || labelImage.getHeight() != valueImage.getHeight()) {
            return Collections.emptyMap();
        }
        int slices = Math.min(labelImage.getStackSize(), valueImage.getStackSize());
        Map<Integer, PixelStats> out = new HashMap<Integer, PixelStats>();
        ImageStack labelStack = labelImage.getStack();
        ImageStack valueStack = valueImage.getStack();
        for (int slice = 1; slice <= slices; slice++) {
            ImageProcessor labels = labelStack.getProcessor(slice);
            ImageProcessor values = valueStack.getProcessor(slice);
            if (labels == null || values == null) continue;
            for (int i = 0; i < labels.getPixelCount(); i++) {
                int label = labelFromPixel(labels.getf(i));
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

    private static int labelFromPixel(float value) {
        if (!Float.isFinite(value) || value <= 0f) return 0;
        return value > Integer.MAX_VALUE ? 0 : Math.round(value);
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
