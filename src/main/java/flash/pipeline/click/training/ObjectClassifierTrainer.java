package flash.pipeline.click.training;

import smile.base.cart.SplitRule;
import smile.classification.RandomForest;
import smile.data.DataFrame;
import smile.data.formula.Formula;
import smile.data.vector.BaseVector;
import smile.data.vector.DoubleVector;
import smile.data.vector.IntVector;
import smile.validation.Bag;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ForkJoinPool;
import java.util.stream.LongStream;

public final class ObjectClassifierTrainer {
    private static final int MIN_EXAMPLES_PER_CLASS = 20;
    private static final int NTREES = 100;
    private static final int MAX_DEPTH = 8;
    private static final int NODE_SIZE = 2;
    private static final double SUBSAMPLE = 1.0;
    private static final double LOW_QUALITY_ACCURACY = 0.7;
    private static final Object SMILE_GLOBAL_RNG_LOCK = new Object();

    public enum QualityFlag { OK, LOW }

    public static final class TrainingResult {
        public final RandomForest model;
        public final String[] featureNames;
        public final double crossValAccuracy;
        public final double[] featureImportance;
        public final int positiveExamples;
        public final int negativeExamples;
        public final QualityFlag quality;
        public final int seed;
        public final String backendVersion;
        public final String device;
        public final String deterministicMode;
        public final int[] foldAssignments;

        public TrainingResult(RandomForest model,
                              String[] featureNames,
                              double crossValAccuracy,
                              double[] featureImportance,
                              int positiveExamples,
                              int negativeExamples,
                              QualityFlag quality) {
            this(model, featureNames, crossValAccuracy, featureImportance,
                    positiveExamples, negativeExamples, quality, 0,
                    "smile-2.6.0", "cpu", "legacy-unspecified", new int[0]);
        }

        public TrainingResult(RandomForest model,
                              String[] featureNames,
                              double crossValAccuracy,
                              double[] featureImportance,
                              int positiveExamples,
                              int negativeExamples,
                              QualityFlag quality,
                              int seed,
                              String backendVersion,
                              String device,
                              String deterministicMode,
                              int[] foldAssignments) {
            this.model = model;
            this.featureNames = featureNames == null ? new String[0] : Arrays.copyOf(featureNames, featureNames.length);
            this.crossValAccuracy = crossValAccuracy;
            this.featureImportance = featureImportance == null
                    ? new double[0]
                    : Arrays.copyOf(featureImportance, featureImportance.length);
            this.positiveExamples = positiveExamples;
            this.negativeExamples = negativeExamples;
            this.quality = quality == null ? QualityFlag.LOW : quality;
            this.seed = seed;
            this.backendVersion = backendVersion == null ? "unknown" : backendVersion;
            this.device = device == null ? "unknown" : device;
            this.deterministicMode = deterministicMode == null
                    ? "unknown" : deterministicMode;
            this.foldAssignments = foldAssignments == null
                    ? new int[0] : Arrays.copyOf(foldAssignments, foldAssignments.length);
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

        String[] featureNames = dropNonFiniteFeatures(
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

        int[] foldAssignments = deterministicFoldAssignments(
                safePositives.size(), safeNegatives.size(), 5, seed);
        Bag[] folds = bagsFromAssignments(foldAssignments, 5);
        double accuracy = crossValidationAccuracy(formula, data, folds,
                mtry, maxNodes, seed);

        RandomForest model = fit(formula, data, mtry, maxNodes, seed);
        double[] importance = relativeImportance(model.importance(), featureNames.length);
        QualityFlag quality = Double.isFinite(accuracy) && accuracy >= LOW_QUALITY_ACCURACY
                ? QualityFlag.OK
                : QualityFlag.LOW;

        return new TrainingResult(model, featureNames, accuracy, importance,
                safePositives.size(), safeNegatives.size(), quality, seed,
                "smile-2.6.0", "cpu",
                "global-smile-rng-lock-explicit-seeded-folds-serial-tree-streams",
                foldAssignments);
    }

    private static double crossValidationAccuracy(Formula formula,
                                                  DataFrame data,
                                                  Bag[] folds,
                                                  int mtry,
                                                  int maxNodes,
                                                  int seed) {
        double accuracySum = 0.0;
        for (int fold = 0; fold < folds.length; fold++) {
            Bag bag = folds[fold];
            DataFrame training = data.of(bag.samples);
            DataFrame validation = data.of(bag.oob);
            RandomForest model = fit(formula, training, mtry, maxNodes,
                    foldSeed(seed, fold));
            int[] prediction = model.predict(validation);
            int correct = 0;
            for (int row = 0; row < prediction.length; row++) {
                if (prediction[row] == validation.getInt(row, "label")) {
                    correct++;
                }
            }
            accuracySum += prediction.length == 0
                    ? 0.0 : correct / (double) prediction.length;
        }
        return folds.length == 0 ? Double.NaN : accuracySum / folds.length;
    }

    private static int foldSeed(int seed, int fold) {
        return seed ^ (0x9e3779b9 * (fold + 1));
    }

    static int[] deterministicFoldAssignments(int positiveCount,
                                              int negativeCount,
                                              int foldCount,
                                              int seed) {
        if (positiveCount < foldCount || negativeCount < foldCount || foldCount < 2) {
            throw new IllegalArgumentException(
                    "Each class must contain at least one example per deterministic fold.");
        }
        int[] assignments = new int[positiveCount + negativeCount];
        List<Integer> positives = new ArrayList<Integer>();
        List<Integer> negatives = new ArrayList<Integer>();
        for (int i = 0; i < positiveCount; i++) positives.add(Integer.valueOf(i));
        for (int i = 0; i < negativeCount; i++) {
            negatives.add(Integer.valueOf(positiveCount + i));
        }
        Collections.shuffle(positives, new Random((((long) seed) << 32) ^ 0x51ed270bL));
        Collections.shuffle(negatives, new Random((((long) seed) << 32) ^ 0x7f4a7c15L));
        assignRoundRobin(assignments, positives, foldCount);
        assignRoundRobin(assignments, negatives, foldCount);
        return assignments;
    }

    private static void assignRoundRobin(int[] assignments,
                                         List<Integer> indices,
                                         int foldCount) {
        for (int i = 0; i < indices.size(); i++) {
            assignments[indices.get(i).intValue()] = i % foldCount;
        }
    }

    private static Bag[] bagsFromAssignments(int[] assignments, int foldCount) {
        Bag[] bags = new Bag[foldCount];
        for (int fold = 0; fold < foldCount; fold++) {
            int testCount = 0;
            for (int assignment : assignments) {
                if (assignment == fold) testCount++;
            }
            int[] train = new int[assignments.length - testCount];
            int[] test = new int[testCount];
            int trainIndex = 0;
            int testIndex = 0;
            for (int row = 0; row < assignments.length; row++) {
                if (assignments[row] == fold) {
                    test[testIndex++] = row;
                } else {
                    train[trainIndex++] = row;
                }
            }
            bags[fold] = new Bag(train, test);
        }
        return bags;
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
        return dropNonFiniteFeatures(featureNames, positives, negatives);
    }

    static String[] dropNonFiniteFeatures(String[] featureNames,
                                          List<ObjectFeatureExtractor.FeatureRow> positives,
                                          List<ObjectFeatureExtractor.FeatureRow> negatives) {
        if (featureNames == null || featureNames.length == 0) return new String[0];
        List<String> kept = new ArrayList<String>();
        for (String featureName : featureNames) {
            if (allRowsFinite(featureName, positives) && allRowsFinite(featureName, negatives)) {
                kept.add(featureName);
            }
        }
        return kept.toArray(new String[0]);
    }

    private static boolean allRowsFinite(String featureName, List<ObjectFeatureExtractor.FeatureRow> rows) {
        if (rows == null || rows.isEmpty()) return false;
        for (ObjectFeatureExtractor.FeatureRow row : rows) {
            double value = ObjectFeatureExtractor.alignedValue(row, featureName);
            if (!Double.isFinite(value)) return false;
        }
        return true;
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
        final Formula safeFormula = formula;
        final DataFrame safeData = data;
        final int safeMtry = mtry;
        final int safeMaxNodes = maxNodes;
        final int safeSeed = seed;
        synchronized (SMILE_GLOBAL_RNG_LOCK) {
            ForkJoinPool deterministicPool = new ForkJoinPool(1);
            try {
                return deterministicPool.submit(new Callable<RandomForest>() {
                    @Override public RandomForest call() {
                        LongStream seeds = new Random(safeSeed).longs(NTREES);
                        return RandomForest.fit(safeFormula, safeData, NTREES, safeMtry,
                                SplitRule.GINI, MAX_DEPTH, safeMaxNodes, NODE_SIZE,
                                SUBSAMPLE, null, seeds);
                    }
                }).get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(
                        "Interrupted while fitting deterministic Random Forest.", e);
            } catch (ExecutionException e) {
                Throwable cause = e.getCause();
                if (cause instanceof RuntimeException) {
                    throw (RuntimeException) cause;
                }
                if (cause instanceof Error) {
                    throw (Error) cause;
                }
                throw new IllegalStateException(
                        "Deterministic Random Forest fit failed.", cause);
            } finally {
                deterministicPool.shutdown();
            }
        }
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
