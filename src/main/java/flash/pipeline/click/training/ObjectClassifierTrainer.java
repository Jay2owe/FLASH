package flash.pipeline.click.training;

import smile.base.cart.SplitRule;
import smile.classification.RandomForest;
import smile.data.DataFrame;
import smile.data.formula.Formula;
import smile.data.vector.BaseVector;
import smile.data.vector.DoubleVector;
import smile.data.vector.IntVector;
import smile.validation.ClassificationValidations;
import smile.validation.CrossValidation;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import java.util.stream.LongStream;

public final class ObjectClassifierTrainer {
    private static final int MIN_EXAMPLES_PER_CLASS = 20;
    private static final int NTREES = 100;
    private static final int MAX_DEPTH = 8;
    private static final int NODE_SIZE = 2;
    private static final double SUBSAMPLE = 1.0;
    private static final double LOW_QUALITY_ACCURACY = 0.7;

    public enum QualityFlag { OK, LOW }

    public static final class TrainingResult {
        public final RandomForest model;
        public final String[] featureNames;
        public final double crossValAccuracy;
        public final double[] featureImportance;
        public final int positiveExamples;
        public final int negativeExamples;
        public final QualityFlag quality;

        public TrainingResult(RandomForest model,
                              String[] featureNames,
                              double crossValAccuracy,
                              double[] featureImportance,
                              int positiveExamples,
                              int negativeExamples,
                              QualityFlag quality) {
            this.model = model;
            this.featureNames = featureNames == null ? new String[0] : Arrays.copyOf(featureNames, featureNames.length);
            this.crossValAccuracy = crossValAccuracy;
            this.featureImportance = featureImportance == null
                    ? new double[0]
                    : Arrays.copyOf(featureImportance, featureImportance.length);
            this.positiveExamples = positiveExamples;
            this.negativeExamples = negativeExamples;
            this.quality = quality == null ? QualityFlag.LOW : quality;
        }
    }

    public TrainingResult train(List<ObjectFeatureExtractor.FeatureRow> positives,
                                List<ObjectFeatureExtractor.FeatureRow> negatives,
                                int seed) {
        List<ObjectFeatureExtractor.FeatureRow> safePositives = positives == null
                ? new ArrayList<ObjectFeatureExtractor.FeatureRow>()
                : positives;
        List<ObjectFeatureExtractor.FeatureRow> safeNegatives = negatives == null
                ? new ArrayList<ObjectFeatureExtractor.FeatureRow>()
                : negatives;

        if (safePositives.size() < MIN_EXAMPLES_PER_CLASS
                || safeNegatives.size() < MIN_EXAMPLES_PER_CLASS) {
            throw new IllegalArgumentException("Need at least 20 positive and 20 negative object examples "
                    + "to train the Smile Random Forest. Current counts: "
                    + safePositives.size() + " positive, "
                    + safeNegatives.size() + " negative.");
        }

        String[] featureNames = dropAllMissingFeatures(
                determineFeatureNames(safePositives, safeNegatives),
                safePositives,
                safeNegatives);
        if (featureNames.length == 0) {
            throw new IllegalArgumentException("Training examples must contain at least one numeric feature.");
        }

        DataFrame data = toDataFrame(safePositives, safeNegatives, featureNames);
        final Formula formula = Formula.lhs("label");
        final int mtry = Math.max(1, (int) Math.floor(Math.sqrt(featureNames.length)));
        final int maxNodes = Math.max(2, data.nrows() / 5);

        final AtomicInteger cvRound = new AtomicInteger();
        BiFunction<Formula, DataFrame, RandomForest> trainer =
                new BiFunction<Formula, DataFrame, RandomForest>() {
                    @Override
                    public RandomForest apply(Formula f, DataFrame frame) {
                        int round = cvRound.incrementAndGet();
                        return fit(f, frame, mtry, maxNodes, seed + (round * 9973));
                    }
                };

        ClassificationValidations<RandomForest> cv =
                CrossValidation.classification(5, formula, data, trainer);
        double accuracy = cv == null || cv.avg == null ? Double.NaN : cv.avg.accuracy;

        RandomForest model = fit(formula, data, mtry, maxNodes, seed);
        double[] importance = relativeImportance(model.importance(), featureNames.length);
        QualityFlag quality = Double.isFinite(accuracy) && accuracy >= LOW_QUALITY_ACCURACY
                ? QualityFlag.OK
                : QualityFlag.LOW;

        return new TrainingResult(model, featureNames, accuracy, importance,
                safePositives.size(), safeNegatives.size(), quality);
    }

    static DataFrame toDataFrame(List<ObjectFeatureExtractor.FeatureRow> positives,
                                 List<ObjectFeatureExtractor.FeatureRow> negatives,
                                 String[] featureNames) {
        int rows = positives.size() + negatives.size();
        BaseVector[] columns = new BaseVector[featureNames.length + 1];
        for (int col = 0; col < featureNames.length; col++) {
            double[] values = new double[rows];
            int row = 0;
            for (ObjectFeatureExtractor.FeatureRow positive : positives) {
                values[row++] = ObjectFeatureExtractor.alignedValue(positive, featureNames[col]);
            }
            for (ObjectFeatureExtractor.FeatureRow negative : negatives) {
                values[row++] = ObjectFeatureExtractor.alignedValue(negative, featureNames[col]);
            }
            columns[col] = DoubleVector.of(featureNames[col], values);
        }

        int[] labels = new int[rows];
        int row = 0;
        for (int i = 0; i < positives.size(); i++) {
            labels[row++] = 1;
        }
        for (int i = 0; i < negatives.size(); i++) {
            labels[row++] = 0;
        }
        columns[featureNames.length] = IntVector.of("label", labels);
        return DataFrame.of(columns);
    }

    static String[] determineFeatureNames(List<ObjectFeatureExtractor.FeatureRow> positives,
                                          List<ObjectFeatureExtractor.FeatureRow> negatives) {
        ObjectFeatureExtractor.FeatureRow first = firstRow(positives);
        if (first == null) first = firstRow(negatives);
        if (first == null || first.featureNames == null || first.featureNames.length == 0) {
            return new String[0];
        }
        String[] names = Arrays.copyOf(first.featureNames, first.featureNames.length);
        for (int i = 0; i < names.length; i++) {
            if (names[i] == null || names[i].trim().isEmpty()) {
                throw new IllegalArgumentException("Feature names must not be blank.");
            }
            names[i] = names[i].trim();
        }
        return names;
    }

    static String[] dropAllMissingFeatures(String[] featureNames,
                                           List<ObjectFeatureExtractor.FeatureRow> positives,
                                           List<ObjectFeatureExtractor.FeatureRow> negatives) {
        if (featureNames == null || featureNames.length == 0) return new String[0];
        List<String> kept = new ArrayList<String>();
        for (String featureName : featureNames) {
            if (hasFiniteValue(featureName, positives) || hasFiniteValue(featureName, negatives)) {
                kept.add(featureName);
            }
        }
        return kept.toArray(new String[0]);
    }

    private static boolean hasFiniteValue(String featureName, List<ObjectFeatureExtractor.FeatureRow> rows) {
        if (rows == null) return false;
        for (ObjectFeatureExtractor.FeatureRow row : rows) {
            double value = ObjectFeatureExtractor.alignedValue(row, featureName);
            if (Double.isFinite(value)) return true;
        }
        return false;
    }

    private static ObjectFeatureExtractor.FeatureRow firstRow(List<ObjectFeatureExtractor.FeatureRow> rows) {
        if (rows == null) return null;
        for (ObjectFeatureExtractor.FeatureRow row : rows) {
            if (row != null) return row;
        }
        return null;
    }

    private static RandomForest fit(Formula formula,
                                    DataFrame data,
                                    int mtry,
                                    int maxNodes,
                                    int seed) {
        LongStream seeds = new Random(seed).longs(NTREES);
        return RandomForest.fit(formula, data, NTREES, mtry, SplitRule.GINI,
                MAX_DEPTH, maxNodes, NODE_SIZE, SUBSAMPLE, null, seeds);
    }

    private static double[] relativeImportance(double[] raw, int featureCount) {
        double[] out = new double[featureCount];
        if (raw == null) return out;
        double sum = 0.0;
        int n = Math.min(raw.length, out.length);
        for (int i = 0; i < n; i++) {
            if (Double.isFinite(raw[i]) && raw[i] > 0.0) {
                out[i] = raw[i];
                sum += raw[i];
            }
        }
        if (sum > 0.0) {
            for (int i = 0; i < out.length; i++) {
                out[i] /= sum;
            }
        }
        return out;
    }
}
