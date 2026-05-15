package flash.pipeline.segmentation;

import flash.pipeline.image.ImageOps;
import flash.pipeline.objects.ObjectsCounter3DWrapper;
import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.measure.ResultsTable;
import ij.process.ImageProcessor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class EnhancedClassicalRunner {
    public static final String OBJECT_STATS_PROPERTY = "flash.enhanced3doc.objectStats";
    public static final String PREDICATE_COUNTS_PROPERTY = "flash.enhanced3doc.predicateCounts";
    public static final String PREDICATE_LABELS_PROPERTY = "flash.enhanced3doc.predicateLabels";

    private static final String FEATURE_VOLUME = "volume";
    private static final String FEATURE_SURFACE_AREA = "surface_area";
    private static final String FEATURE_SPHERICITY = "sphericity";
    private static final String FEATURE_ELONGATION = "elongation";
    private static final String FEATURE_COMPACTNESS = "compactness";
    private static final String FEATURE_MEAN_INTENSITY = "mean_intensity";
    private static final String FEATURE_MAX_INTENSITY = "max_intensity";
    private static final String FEATURE_FERET_DIAMETER_MAX = "feret_diameter_max";

    private static final Set<String> SUPPORTED_FEATURES = new HashSet<String>(Arrays.asList(
            FEATURE_VOLUME,
            FEATURE_SURFACE_AREA,
            FEATURE_SPHERICITY,
            FEATURE_ELONGATION,
            FEATURE_COMPACTNESS,
            FEATURE_MEAN_INTENSITY,
            FEATURE_MAX_INTENSITY,
            FEATURE_FERET_DIAMETER_MAX));

    public ImagePlus run(ImagePlus channelImage, EnhancedClassicalParameters params) {
        if (channelImage == null) {
            throw new IllegalArgumentException("channelImage must not be null");
        }
        EnhancedClassicalParameters safe = params == null
                ? new EnhancedClassicalParameters(0, 0, Integer.MAX_VALUE,
                Collections.<MorphPredicate>emptyList(), null, null)
                : params;

        ObjectsCounter3DWrapper wrapper = new ObjectsCounter3DWrapper();
        ObjectsCounter3DWrapper.Result detected = wrapper.runNative(
                channelImage,
                safe.threshold,
                safe.minSize,
                safe.maxSize,
                false,
                safe.intensityImage,
                true,
                false);

        ImagePlus labelImage = detected == null ? null : detected.getObjectsMap();
        if (labelImage == null) {
            labelImage = emptyLabelMapLike(channelImage);
        }
        if (labelImage == null) {
            return null;
        }
        labelImage.setTitle("Enhanced Classical label image");

        List<MorphPredicate> predicates = safe.morphPredicates;
        ResultsTable detectedStats = detected == null ? new ResultsTable() : detected.getStatistics();
        if (predicates == null || predicates.isEmpty()) {
            ResultsTable stats = detectedStats == null ? new ResultsTable() : copyOf(detectedStats);
            labelImage.setProperty(OBJECT_STATS_PROPERTY, stats);
            labelImage.setProperty(PREDICATE_COUNTS_PROPERTY, new int[0]);
            labelImage.setProperty(PREDICATE_LABELS_PROPERTY, new String[0]);
            return labelImage;
        }

        FeatureContext featureContext = new FeatureContext(predicates, detectedStats,
                safe.intensityImage, safe.warningSink);
        Map<Integer, FeatureValues> features = computeFeaturesByLabel(labelImage, featureContext);
        FilterResult filter = evaluateFilters(features, predicates, safe.warningSink);
        ImagePlus filteredLabels = relabelConsecutively(labelImage, filter.oldToNewLabel);

        ObjectsCounter3DWrapper.Result recounted = wrapper.fromLabelImage(
                filteredLabels,
                safe.intensityImage,
                0,
                Integer.MAX_VALUE,
                true,
                false);
        ResultsTable stats = recounted == null ? new ResultsTable() : recounted.getStatistics();
        stats = appendReferencedMorphColumns(filteredLabels, safe.intensityImage, stats,
                predicates, safe.warningSink);
        filteredLabels.setProperty(OBJECT_STATS_PROPERTY, stats);
        filteredLabels.setProperty(PREDICATE_COUNTS_PROPERTY, toIntArray(filter.survivingAfterPredicate));
        filteredLabels.setProperty(PREDICATE_LABELS_PROPERTY, toStringArray(predicates));

        if (recounted != null && recounted.getObjectsMap() != null
                && recounted.getObjectsMap() != filteredLabels) {
            recounted.getObjectsMap().changes = false;
            recounted.getObjectsMap().close();
            recounted.getObjectsMap().flush();
        }
        return filteredLabels;
    }

    public static ResultsTable statsProperty(ImagePlus labelImage) {
        Object property = labelImage == null ? null : labelImage.getProperty(OBJECT_STATS_PROPERTY);
        return property instanceof ResultsTable ? (ResultsTable) property : null;
    }

    public static ResultsTable appendReferencedMorphColumns(ImagePlus labelImage,
                                                            ImagePlus intensityImage,
                                                            ResultsTable stats,
                                                            List<MorphPredicate> predicates,
                                                            EnhancedClassicalParameters.WarningSink warningSink) {
        ResultsTable safeStats = stats == null ? new ResultsTable() : copyOf(stats);
        if (labelImage == null || predicates == null || predicates.isEmpty() || safeStats.size() == 0) {
            return safeStats;
        }
        FeatureContext featureContext = new FeatureContext(predicates, safeStats,
                intensityImage, warningSink);
        Map<Integer, FeatureValues> features = computeFeaturesByLabel(labelImage, featureContext);
        for (int row = 0; row < safeStats.size(); row++) {
            int label = labelForRow(safeStats, row);
            FeatureValues values = features.get(Integer.valueOf(label));
            if (values == null) continue;
            if (featureContext.needs(FEATURE_SPHERICITY)) {
                setFinite(safeStats, "Morph_Sphericity", row, values.sphericity);
            }
            if (featureContext.needs(FEATURE_COMPACTNESS)) {
                setFinite(safeStats, "Morph_Compactness", row, values.compactness);
            }
            if (featureContext.needs(FEATURE_ELONGATION)) {
                setFinite(safeStats, "Morph_Elongation", row, values.elongation);
            }
            if (featureContext.needs(FEATURE_FERET_DIAMETER_MAX)) {
                setFinite(safeStats, "Morph_Feret3D_um", row, values.feretDiameterMax);
            }
            if (featureContext.needs(FEATURE_MAX_INTENSITY)) {
                setFinite(safeStats, "Max", row, values.maxIntensity);
            }
        }
        return safeStats;
    }

    private static Map<Integer, FeatureValues> computeFeaturesByLabel(ImagePlus labelImage,
                                                                      FeatureContext context) {
        if (labelImage == null || labelImage.getStack() == null) {
            return Collections.emptyMap();
        }
        mcib3d.image3d.ImageHandler labelHandler = mcib3d.image3d.ImageHandler.wrap(labelImage);
        mcib3d.geom2.Objects3DIntPopulation population =
                new mcib3d.geom2.Objects3DIntPopulation(labelHandler);
        mcib3d.image3d.ImageHandler intensityHandler = context.intensityImage == null
                ? null
                : mcib3d.image3d.ImageHandler.wrap(context.intensityImage);

        Map<Integer, FeatureValues> out = new LinkedHashMap<Integer, FeatureValues>();
        List<mcib3d.geom2.Object3DInt> objects = population.getObjects3DInt();
        for (int i = 0; i < objects.size(); i++) {
            mcib3d.geom2.Object3DInt object = objects.get(i);
            int label = Math.round(object.getLabel());
            FeatureValues values = new FeatureValues();

            if (context.needs(FEATURE_VOLUME)) {
                try {
                    values.volume = new mcib3d.geom2.measurements.MeasureVolume(object).getVolumePix();
                } catch (RuntimeException e) {
                    values.volume = Double.NaN;
                }
            }
            if (context.needs(FEATURE_SURFACE_AREA)) {
                try {
                    values.surfaceArea = new mcib3d.geom2.measurements.MeasureSurface(object).getSurfaceContactUnit();
                } catch (RuntimeException e) {
                    values.surfaceArea = Double.NaN;
                }
            }
            if (context.needs(FEATURE_SPHERICITY) || context.needs(FEATURE_COMPACTNESS)) {
                mcib3d.geom2.measurements.MeasureCompactness compactness =
                        new mcib3d.geom2.measurements.MeasureCompactness(object);
                if (context.needs(FEATURE_SPHERICITY)) {
                    values.sphericity = safeMeasurement(compactness,
                            mcib3d.geom2.measurements.MeasureCompactness.SPHER_CORRECTED);
                }
                if (context.needs(FEATURE_COMPACTNESS)) {
                    values.compactness = safeMeasurement(compactness,
                            mcib3d.geom2.measurements.MeasureCompactness.COMP_CORRECTED);
                }
            }
            if (context.needs(FEATURE_ELONGATION)) {
                mcib3d.geom2.measurements.MeasureEllipsoid ellipsoid =
                        new mcib3d.geom2.measurements.MeasureEllipsoid(object);
                values.elongation = safeMeasurement(ellipsoid,
                        mcib3d.geom2.measurements.MeasureEllipsoid.ELL_ELONGATION);
            }
            if (context.needs(FEATURE_FERET_DIAMETER_MAX)) {
                mcib3d.geom2.measurements.MeasureFeret feret =
                        new mcib3d.geom2.measurements.MeasureFeret(object);
                values.feretDiameterMax = safeMeasurement(feret,
                        mcib3d.geom2.measurements.MeasureFeret.FERET_UNIT);
            }

            if (context.needs(FEATURE_MEAN_INTENSITY)) {
                values.meanIntensity = meanFromStats(context.statsByLabel, label);
            }
            if ((context.needs(FEATURE_MEAN_INTENSITY) && !Double.isFinite(values.meanIntensity))
                    || context.needs(FEATURE_MAX_INTENSITY)) {
                if (intensityHandler == null) {
                    if (context.needs(FEATURE_MEAN_INTENSITY)) {
                        values.markUnavailable(FEATURE_MEAN_INTENSITY);
                    }
                    if (context.needs(FEATURE_MAX_INTENSITY)) {
                        values.markUnavailable(FEATURE_MAX_INTENSITY);
                    }
                } else {
                    mcib3d.geom2.measurements.MeasureIntensity intensity =
                            new mcib3d.geom2.measurements.MeasureIntensity(object);
                    intensity.setIntensityImage(intensityHandler);
                    if (context.needs(FEATURE_MEAN_INTENSITY) && !Double.isFinite(values.meanIntensity)) {
                        values.meanIntensity = safeMeasurement(intensity,
                                mcib3d.geom2.measurements.MeasureIntensity.INTENSITY_AVG);
                    }
                    if (context.needs(FEATURE_MAX_INTENSITY)) {
                        values.maxIntensity = safeMeasurement(intensity,
                                mcib3d.geom2.measurements.MeasureIntensity.INTENSITY_MAX);
                    }
                }
            }

            out.put(Integer.valueOf(label), values);
        }
        return out;
    }

    private static FilterResult evaluateFilters(Map<Integer, FeatureValues> features,
                                                List<MorphPredicate> predicates,
                                                EnhancedClassicalParameters.WarningSink warningSink) {
        FilterResult result = new FilterResult();
        if (features == null || features.isEmpty()) return result;

        Set<Integer> surviving = new HashSet<Integer>(features.keySet());
        for (int i = 0; i < predicates.size(); i++) {
            MorphPredicate predicate = predicates.get(i);
            List<Integer> labels = new ArrayList<Integer>(surviving);
            Collections.sort(labels);
            for (Integer label : labels) {
                FeatureValues values = features.get(label);
                if (!predicatePasses(predicate, values, warningSink)) {
                    surviving.remove(label);
                }
            }
            result.survivingAfterPredicate.add(Integer.valueOf(surviving.size()));
        }

        List<Integer> ordered = new ArrayList<Integer>(features.keySet());
        Collections.sort(ordered);
        int next = 1;
        for (Integer label : ordered) {
            if (surviving.contains(label)) {
                result.oldToNewLabel.put(label, Integer.valueOf(next++));
            }
        }
        return result;
    }

    private static boolean predicatePasses(MorphPredicate predicate,
                                           FeatureValues values,
                                           EnhancedClassicalParameters.WarningSink warningSink) {
        if (predicate == null) return true;
        String feature = normalizeFeature(predicate.featureName);
        if (!SUPPORTED_FEATURES.contains(feature)) {
            warn(warningSink, "Warning: unknown Enhanced Classical morph feature '"
                    + predicate.featureName + "'; predicate treated as true.");
            return true;
        }
        if (values == null) return false;
        if (values.isUnavailable(feature)) {
            warn(warningSink, "Warning: Enhanced Classical feature '" + feature
                    + "' is unavailable; predicate treated as true.");
            return true;
        }
        return predicate.matches(values.value(feature));
    }

    private static ImagePlus relabelConsecutively(ImagePlus labelImage,
                                                  Map<Integer, Integer> oldToNewLabel) {
        ImagePlus relabelled = ImageOps.duplicateThreadSafe(labelImage);
        ImageStack stack = relabelled.getStack();
        if (stack == null || oldToNewLabel == null || oldToNewLabel.isEmpty()) {
            zeroLabels(relabelled);
            return relabelled;
        }
        for (int slice = 1; slice <= stack.getSize(); slice++) {
            ImageProcessor processor = stack.getProcessor(slice);
            if (processor == null) continue;
            for (int i = 0; i < processor.getPixelCount(); i++) {
                int oldLabel = labelFromPixel(processor.getf(i));
                Integer next = oldToNewLabel.get(Integer.valueOf(oldLabel));
                processor.setf(i, next == null ? 0f : next.floatValue());
            }
        }
        relabelled.updateAndDraw();
        return relabelled;
    }

    private static ImagePlus emptyLabelMapLike(ImagePlus source) {
        ImagePlus empty = ImageOps.duplicateThreadSafe(source);
        zeroLabels(empty);
        return empty;
    }

    private static void zeroLabels(ImagePlus image) {
        if (image == null || image.getStack() == null) return;
        ImageStack stack = image.getStack();
        for (int slice = 1; slice <= stack.getSize(); slice++) {
            ImageProcessor processor = stack.getProcessor(slice);
            if (processor == null) continue;
            processor.setValue(0.0);
            processor.fill();
        }
        image.updateAndDraw();
    }

    private static int labelFromPixel(float value) {
        if (!Float.isFinite(value) || value <= 0f) return 0;
        return value > Integer.MAX_VALUE ? 0 : Math.round(value);
    }

    private static int labelForRow(ResultsTable table, int row) {
        try {
            double label = table.getValue("Label", row);
            if (Double.isFinite(label) && label > 0) return (int) Math.round(label);
        } catch (RuntimeException ignored) {
        }
        return row + 1;
    }

    private static Map<Integer, Double> meanByLabel(ResultsTable stats) {
        Map<Integer, Double> out = new HashMap<Integer, Double>();
        if (stats == null) return out;
        for (int row = 0; row < stats.size(); row++) {
            try {
                double mean = stats.getValue("Mean", row);
                if (Double.isFinite(mean)) {
                    out.put(Integer.valueOf(labelForRow(stats, row)), Double.valueOf(mean));
                }
            } catch (RuntimeException ignored) {
            }
        }
        return out;
    }

    private static double meanFromStats(Map<Integer, Double> statsByLabel, int label) {
        Double value = statsByLabel == null ? null : statsByLabel.get(Integer.valueOf(label));
        return value == null ? Double.NaN : value.doubleValue();
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

    private static void setFinite(ResultsTable table, String column, int row, double value) {
        if (table != null && Double.isFinite(value)) {
            table.setValue(column, row, value);
        }
    }

    private static ResultsTable copyOf(ResultsTable src) {
        ResultsTable dst = new ResultsTable();
        if (src == null || src.size() == 0) return dst;
        String[] headings = src.getHeadings();
        for (int row = 0; row < src.size(); row++) {
            dst.incrementCounter();
            if (headings == null) continue;
            for (int h = 0; h < headings.length; h++) {
                String heading = headings[h];
                if (heading == null || heading.trim().isEmpty()) continue;
                try {
                    dst.setValue(heading, row, src.getValue(heading, row));
                } catch (RuntimeException ignored) {
                }
            }
        }
        return dst;
    }

    private static int[] toIntArray(List<Integer> values) {
        if (values == null || values.isEmpty()) return new int[0];
        int[] out = new int[values.size()];
        for (int i = 0; i < values.size(); i++) {
            out[i] = values.get(i).intValue();
        }
        return out;
    }

    private static String[] toStringArray(List<MorphPredicate> predicates) {
        if (predicates == null || predicates.isEmpty()) return new String[0];
        String[] out = new String[predicates.size()];
        for (int i = 0; i < predicates.size(); i++) {
            out[i] = predicates.get(i) == null ? "" : predicates.get(i).format();
        }
        return out;
    }

    private static String normalizeFeature(String feature) {
        return feature == null ? "" : feature.trim().toLowerCase(java.util.Locale.ROOT);
    }

    private static void warn(EnhancedClassicalParameters.WarningSink sink, String message) {
        EnhancedClassicalParameters.WarningSink safe = sink == null
                ? new EnhancedClassicalParameters.WarningSink() {
            @Override public void warn(String message) {
                IJ.log(message);
            }
        }
                : sink;
        safe.warn(message);
    }

    private static final class FeatureContext {
        private final Set<String> requiredFeatures = new HashSet<String>();
        private final Map<Integer, Double> statsByLabel;
        private final ImagePlus intensityImage;

        FeatureContext(List<MorphPredicate> predicates,
                       ResultsTable stats,
                       ImagePlus intensityImage,
                       EnhancedClassicalParameters.WarningSink warningSink) {
            if (predicates != null) {
                for (int i = 0; i < predicates.size(); i++) {
                    MorphPredicate predicate = predicates.get(i);
                    if (predicate == null) continue;
                    String feature = normalizeFeature(predicate.featureName);
                    if (SUPPORTED_FEATURES.contains(feature)) {
                        requiredFeatures.add(feature);
                    } else {
                        warn(warningSink, "Warning: unknown Enhanced Classical morph feature '"
                                + predicate.featureName + "'; predicate treated as true.");
                    }
                }
            }
            this.statsByLabel = meanByLabel(stats);
            this.intensityImage = intensityImage;
        }

        boolean needs(String feature) {
            return requiredFeatures.contains(feature);
        }
    }

    private static final class FeatureValues {
        double volume = Double.NaN;
        double surfaceArea = Double.NaN;
        double sphericity = Double.NaN;
        double elongation = Double.NaN;
        double compactness = Double.NaN;
        double meanIntensity = Double.NaN;
        double maxIntensity = Double.NaN;
        double feretDiameterMax = Double.NaN;
        private final Set<String> unavailable = new HashSet<String>();

        void markUnavailable(String feature) {
            unavailable.add(feature);
        }

        boolean isUnavailable(String feature) {
            return unavailable.contains(feature);
        }

        double value(String feature) {
            if (FEATURE_VOLUME.equals(feature)) return volume;
            if (FEATURE_SURFACE_AREA.equals(feature)) return surfaceArea;
            if (FEATURE_SPHERICITY.equals(feature)) return sphericity;
            if (FEATURE_ELONGATION.equals(feature)) return elongation;
            if (FEATURE_COMPACTNESS.equals(feature)) return compactness;
            if (FEATURE_MEAN_INTENSITY.equals(feature)) return meanIntensity;
            if (FEATURE_MAX_INTENSITY.equals(feature)) return maxIntensity;
            if (FEATURE_FERET_DIAMETER_MAX.equals(feature)) return feretDiameterMax;
            return Double.NaN;
        }
    }

    private static final class FilterResult {
        final Map<Integer, Integer> oldToNewLabel = new LinkedHashMap<Integer, Integer>();
        final List<Integer> survivingAfterPredicate = new ArrayList<Integer>();
    }
}
