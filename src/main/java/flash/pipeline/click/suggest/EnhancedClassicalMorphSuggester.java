package flash.pipeline.click.suggest;

import flash.pipeline.segmentation.EnhancedClassicalRunner;
import flash.pipeline.segmentation.MorphPredicate;
import ij.ImagePlus;
import ij.measure.ResultsTable;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class EnhancedClassicalMorphSuggester
        implements ParameterSuggester<EnhancedClassicalMorphSuggester.EnhancedClassicalSuggestion> {

    public static final String FEATURE_VOLUME = "volume";
    public static final String FEATURE_SURFACE_AREA = "surface_area";
    public static final String FEATURE_SPHERICITY = "sphericity";
    public static final String FEATURE_ELONGATION = "elongation";
    public static final String FEATURE_COMPACTNESS = "compactness";
    public static final String FEATURE_MEAN_INTENSITY = "mean_intensity";
    public static final String FEATURE_MAX_INTENSITY = "max_intensity";
    public static final String FEATURE_FERET_DIAMETER_MAX = "feret_diameter_max";

    private static final List<String> FEATURE_ORDER = Collections.unmodifiableList(Arrays.asList(
            FEATURE_VOLUME,
            FEATURE_SURFACE_AREA,
            FEATURE_SPHERICITY,
            FEATURE_ELONGATION,
            FEATURE_COMPACTNESS,
            FEATURE_MEAN_INTENSITY,
            FEATURE_MAX_INTENSITY,
            FEATURE_FERET_DIAMETER_MAX));

    public static final class EnhancedClassicalSuggestion {
        public final ClassicalParameterSuggester.ClassicalSuggestion parameterSuggestion;
        public final MorphSuggestion morphSuggestion;

        EnhancedClassicalSuggestion(ClassicalParameterSuggester.ClassicalSuggestion parameterSuggestion,
                                    MorphSuggestion morphSuggestion) {
            this.parameterSuggestion = parameterSuggestion == null
                    ? ClassicalParameterSuggester.ClassicalSuggestion.none()
                    : parameterSuggestion;
            this.morphSuggestion = morphSuggestion;
        }

        public boolean hasParameterSuggestion() {
            return parameterSuggestion != null && parameterSuggestion.hasSuggestion();
        }

        public boolean hasMorphSuggestion() {
            return morphSuggestion != null;
        }
    }

    public static final class MorphSuggestion {
        public final String featureName;
        public final MorphPredicate.Operator operator;
        public final double value;
        public final double margin;
        public final int badRemoved;
        public final int badTotal;
        public final int collateralRemoved;

        MorphSuggestion(String featureName,
                        MorphPredicate.Operator operator,
                        double value,
                        double margin,
                        int badRemoved,
                        int badTotal,
                        int collateralRemoved) {
            this.featureName = featureName;
            this.operator = operator;
            this.value = value;
            this.margin = margin;
            this.badRemoved = Math.max(0, badRemoved);
            this.badTotal = Math.max(0, badTotal);
            this.collateralRemoved = Math.max(0, collateralRemoved);
        }

        public MorphPredicate toPredicate() {
            return new MorphPredicate(featureName, operator, value);
        }

        public String predicateText() {
            return featureName + " " + operator.symbol() + " " + formatNumber(value);
        }

        public String compactPredicateText() {
            return featureName + operator.symbol() + Double.toString(value);
        }

        public String message() {
            return "Suggested morph predicate: " + predicateText()
                    + ". Removes " + badRemoved + "/" + badTotal
                    + " bad clicks; affects " + collateralRemoved
                    + " unclicked objects.";
        }
    }

    private final ClassicalParameterSuggester classicalSuggester;

    public EnhancedClassicalMorphSuggester() {
        this(new ClassicalParameterSuggester());
    }

    EnhancedClassicalMorphSuggester(ClassicalParameterSuggester classicalSuggester) {
        this.classicalSuggester = classicalSuggester == null
                ? new ClassicalParameterSuggester()
                : classicalSuggester;
    }

    @Override
    public EnhancedClassicalSuggestion suggest(SuggestionContext ctx) {
        ClassicalParameterSuggester.ClassicalSuggestion parameter =
                classicalSuggester.suggest(ctx);
        MorphSuggestion morph = suggestMorph(ctx);
        if ((parameter == null || !parameter.hasSuggestion()) && morph == null) {
            return null;
        }
        return new EnhancedClassicalSuggestion(parameter, morph);
    }

    public MorphSuggestion suggestMorph(SuggestionContext ctx) {
        if (ctx == null || ctx.labelImage == null) return null;
        final Set<Integer> badLabels =
                SuggestionSupport.labelsForClicks(ctx.labelImage, ctx.negativeClicks);
        final Set<Integer> positiveLabels =
                SuggestionSupport.labelsForClicks(ctx.labelImage, ctx.positiveClicks);
        if (badLabels.isEmpty() || positiveLabels.isEmpty()) return null;

        Set<Integer> clickedLabels = new HashSet<Integer>(badLabels);
        clickedLabels.addAll(positiveLabels);
        Map<Integer, FeatureValues> clickedFeatures = computeFeaturesByLabel(
                ctx.labelImage,
                ctx.channelImage,
                clickedLabels,
                new HashSet<String>(FEATURE_ORDER));
        if (clickedFeatures.isEmpty()) return null;

        List<Candidate> candidates = new ArrayList<Candidate>();
        for (int i = 0; i < FEATURE_ORDER.size(); i++) {
            Candidate candidate = separatorForFeature(FEATURE_ORDER.get(i), i,
                    clickedFeatures, badLabels, positiveLabels);
            if (candidate != null) candidates.add(candidate);
        }

        Candidate best = bestCandidate(candidates);
        if (best == null) return null;

        Map<Integer, FeatureValues> allFeatures = computeFeaturesByLabel(
                ctx.labelImage,
                ctx.channelImage,
                null,
                Collections.singleton(best.featureName));
        if (allFeatures.isEmpty()) return null;
        int collateral = countCollateral(allFeatures, badLabels, positiveLabels, best);
        return new MorphSuggestion(best.featureName, best.operator, best.value, best.margin,
                badLabels.size(), badLabels.size(), collateral);
    }

    private static Candidate separatorForFeature(String featureName,
                                                 int order,
                                                 Map<Integer, FeatureValues> features,
                                                 Set<Integer> badLabels,
                                                 Set<Integer> positiveLabels) {
        double badMin = Double.POSITIVE_INFINITY;
        double badMax = Double.NEGATIVE_INFINITY;
        for (Integer label : badLabels) {
            FeatureValues values = features.get(label);
            double value = values == null ? Double.NaN : values.value(featureName);
            if (!Double.isFinite(value)) return null;
            badMin = Math.min(badMin, value);
            badMax = Math.max(badMax, value);
        }

        double goodMin = Double.POSITIVE_INFINITY;
        double goodMax = Double.NEGATIVE_INFINITY;
        for (Integer label : positiveLabels) {
            FeatureValues values = features.get(label);
            double value = values == null ? Double.NaN : values.value(featureName);
            if (!Double.isFinite(value)) return null;
            goodMin = Math.min(goodMin, value);
            goodMax = Math.max(goodMax, value);
        }

        if (badMax + SuggestionSupport.EPSILON < goodMin) {
            double margin = goodMin - badMax;
            return new Candidate(featureName, MorphPredicate.Operator.GE,
                    midpoint(badMax, goodMin), margin,
                    scoreMargin(featureName, margin, badMin, badMax, goodMin, goodMax),
                    order);
        }
        if (goodMax + SuggestionSupport.EPSILON < badMin) {
            double margin = badMin - goodMax;
            return new Candidate(featureName, MorphPredicate.Operator.LE,
                    midpoint(goodMax, badMin), margin,
                    scoreMargin(featureName, margin, badMin, badMax, goodMin, goodMax),
                    order);
        }
        return null;
    }

    private static Candidate bestCandidate(List<Candidate> candidates) {
        if (candidates == null || candidates.isEmpty()) return null;
        Collections.sort(candidates, new Comparator<Candidate>() {
            @Override public int compare(Candidate left, Candidate right) {
                int byScore = Double.compare(right.score, left.score);
                if (byScore != 0) return byScore;
                int byMargin = Double.compare(right.margin, left.margin);
                if (byMargin != 0) return byMargin;
                return Integer.compare(left.order, right.order);
            }
        });
        return candidates.get(0);
    }

    private static int countCollateral(Map<Integer, FeatureValues> allFeatures,
                                       Set<Integer> badLabels,
                                       Set<Integer> positiveLabels,
                                       Candidate candidate) {
        int count = 0;
        for (Map.Entry<Integer, FeatureValues> entry : allFeatures.entrySet()) {
            Integer label = entry.getKey();
            if (label == null) continue;
            if (badLabels != null && badLabels.contains(label)) continue;
            if (positiveLabels != null && positiveLabels.contains(label)) continue;
            double value = entry.getValue() == null
                    ? Double.NaN
                    : entry.getValue().value(candidate.featureName);
            if (!matches(candidate, value)) count++;
        }
        return count;
    }

    private static boolean matches(Candidate candidate, double value) {
        if (candidate == null || !Double.isFinite(value)) return false;
        if (candidate.operator == MorphPredicate.Operator.GE) return value >= candidate.value;
        if (candidate.operator == MorphPredicate.Operator.LE) return value <= candidate.value;
        if (candidate.operator == MorphPredicate.Operator.GT) return value > candidate.value;
        if (candidate.operator == MorphPredicate.Operator.LT) return value < candidate.value;
        return false;
    }

    private static Map<Integer, FeatureValues> computeFeaturesByLabel(ImagePlus labelImage,
                                                                      ImagePlus intensityImage,
                                                                      Set<Integer> labelsOfInterest,
                                                                      Set<String> requiredFeatures) {
        if (labelImage == null || labelImage.getStack() == null
                || requiredFeatures == null || requiredFeatures.isEmpty()) {
            return Collections.emptyMap();
        }
        Set<Integer> wanted = labelsOfInterest == null
                ? null
                : new HashSet<Integer>(labelsOfInterest);
        Map<Integer, Double> meansFromStats = meanByLabel(
                EnhancedClassicalRunner.statsProperty(labelImage));
        mcib3d.image3d.ImageHandler labelHandler = mcib3d.image3d.ImageHandler.wrap(labelImage);
        mcib3d.geom2.Objects3DIntPopulation population =
                new mcib3d.geom2.Objects3DIntPopulation(labelHandler);
        mcib3d.image3d.ImageHandler intensityHandler = intensityImage == null
                ? null
                : mcib3d.image3d.ImageHandler.wrap(intensityImage);

        Map<Integer, FeatureValues> out = new LinkedHashMap<Integer, FeatureValues>();
        List<mcib3d.geom2.Object3DInt> objects = population.getObjects3DInt();
        for (int i = 0; i < objects.size(); i++) {
            mcib3d.geom2.Object3DInt object = objects.get(i);
            int label = Math.round(object.getLabel());
            if (label <= 0) continue;
            if (wanted != null && !wanted.contains(Integer.valueOf(label))) continue;

            FeatureValues values = new FeatureValues();
            if (requiredFeatures.contains(FEATURE_VOLUME)) {
                values.volume = safeVolume(object);
            }
            if (requiredFeatures.contains(FEATURE_SURFACE_AREA)) {
                values.surfaceArea = safeSurface(object);
            }
            if (requiredFeatures.contains(FEATURE_SPHERICITY)
                    || requiredFeatures.contains(FEATURE_COMPACTNESS)) {
                try {
                    mcib3d.geom2.measurements.MeasureCompactness compactness =
                            new mcib3d.geom2.measurements.MeasureCompactness(object);
                    if (requiredFeatures.contains(FEATURE_SPHERICITY)) {
                        values.sphericity = safeMeasurement(compactness,
                                mcib3d.geom2.measurements.MeasureCompactness.SPHER_CORRECTED);
                    }
                    if (requiredFeatures.contains(FEATURE_COMPACTNESS)) {
                        values.compactness = safeMeasurement(compactness,
                                mcib3d.geom2.measurements.MeasureCompactness.COMP_CORRECTED);
                    }
                } catch (RuntimeException e) {
                    values.sphericity = Double.NaN;
                    values.compactness = Double.NaN;
                }
            }
            if (requiredFeatures.contains(FEATURE_ELONGATION)) {
                values.elongation = safeEllipsoid(object,
                        mcib3d.geom2.measurements.MeasureEllipsoid.ELL_ELONGATION);
            }
            if (requiredFeatures.contains(FEATURE_FERET_DIAMETER_MAX)) {
                values.feretDiameterMax = safeFeret(object);
            }

            if (requiredFeatures.contains(FEATURE_MEAN_INTENSITY)) {
                Double mean = meansFromStats.get(Integer.valueOf(label));
                values.meanIntensity = mean == null ? Double.NaN : mean.doubleValue();
            }
            if ((requiredFeatures.contains(FEATURE_MEAN_INTENSITY)
                    && !Double.isFinite(values.meanIntensity))
                    || requiredFeatures.contains(FEATURE_MAX_INTENSITY)) {
                if (intensityHandler != null) {
                    try {
                        mcib3d.geom2.measurements.MeasureIntensity intensity =
                                new mcib3d.geom2.measurements.MeasureIntensity(object);
                        intensity.setIntensityImage(intensityHandler);
                        if (requiredFeatures.contains(FEATURE_MEAN_INTENSITY)
                                && !Double.isFinite(values.meanIntensity)) {
                            values.meanIntensity = safeMeasurement(intensity,
                                    mcib3d.geom2.measurements.MeasureIntensity.INTENSITY_AVG);
                        }
                        if (requiredFeatures.contains(FEATURE_MAX_INTENSITY)) {
                            values.maxIntensity = safeMeasurement(intensity,
                                    mcib3d.geom2.measurements.MeasureIntensity.INTENSITY_MAX);
                        }
                    } catch (RuntimeException e) {
                        values.meanIntensity = Double.NaN;
                        values.maxIntensity = Double.NaN;
                    }
                }
            }
            out.put(Integer.valueOf(label), values);
        }
        return out;
    }

    private static Map<Integer, Double> meanByLabel(ResultsTable stats) {
        Map<Integer, Double> out = new HashMap<Integer, Double>();
        if (stats == null) return out;
        for (int row = 0; row < stats.size(); row++) {
            int label = labelForRow(stats, row);
            double value = tableValue(stats, "Mean", row);
            if (label > 0 && Double.isFinite(value)) {
                out.put(Integer.valueOf(label), Double.valueOf(value));
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

    private static double safeEllipsoid(mcib3d.geom2.Object3DInt object, String name) {
        try {
            mcib3d.geom2.measurements.MeasureEllipsoid ellipsoid =
                    new mcib3d.geom2.measurements.MeasureEllipsoid(object);
            return safeMeasurement(ellipsoid, name);
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

    private static double midpoint(double left, double right) {
        return left + ((right - left) / 2.0d);
    }

    private static double scoreMargin(String featureName,
                                      double margin,
                                      double badMin,
                                      double badMax,
                                      double goodMin,
                                      double goodMax) {
        double scale = Math.max(Math.max(Math.abs(badMin), Math.abs(badMax)),
                Math.max(Math.abs(goodMin), Math.abs(goodMax)));
        if (!Double.isFinite(scale) || scale < SuggestionSupport.EPSILON) {
            return margin * featureWeight(featureName);
        }
        return (margin / scale) * featureWeight(featureName);
    }

    private static double featureWeight(String featureName) {
        // Keep shape predicates from being drowned out by size/intensity units.
        if (FEATURE_SPHERICITY.equals(featureName)) return 5.0d;
        if (FEATURE_COMPACTNESS.equals(featureName)) return 1.5d;
        if (FEATURE_ELONGATION.equals(featureName)) return 1.0d;
        return 0.75d;
    }

    private static String formatNumber(double value) {
        if (!Double.isFinite(value)) return String.valueOf(value);
        DecimalFormat format = new DecimalFormat("0.######",
                DecimalFormatSymbols.getInstance(Locale.US));
        return format.format(value);
    }

    private static final class Candidate {
        final String featureName;
        final MorphPredicate.Operator operator;
        final double value;
        final double margin;
        final double score;
        final int order;

        Candidate(String featureName,
                  MorphPredicate.Operator operator,
                  double value,
                  double margin,
                  double score,
                  int order) {
            this.featureName = featureName;
            this.operator = operator;
            this.value = value;
            this.margin = margin;
            this.score = score;
            this.order = order;
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
}
