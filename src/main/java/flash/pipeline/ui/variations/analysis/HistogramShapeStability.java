package flash.pipeline.ui.variations.analysis;

import flash.pipeline.ui.variations.ParameterCombo;
import flash.pipeline.ui.variations.ParameterKey;
import flash.pipeline.ui.variations.VariationResult;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public final class HistogramShapeStability {

    private HistogramShapeStability() {
    }

    public static Result detect(List<VariationResult> resultsByCombo,
                                ParameterKey primaryAxis) {
        if (resultsByCombo == null
                || primaryAxis == null
                || primaryAxis.valueKind() != ParameterKey.ValueKind.NUMBER) {
            return Result.empty(primaryAxis);
        }

        List<Point> points = completedHistogramPoints(resultsByCombo, primaryAxis);
        if (points.size() < 4) {
            return Result.fromPoints(primaryAxis, points, new double[0],
                    null, -1);
        }
        Collections.sort(points, new Comparator<Point>() {
            @Override public int compare(Point a, Point b) {
                return Double.compare(a.x, b.x);
            }
        });

        double[] distances = new double[points.size() - 1];
        double[] distanceXs = new double[distances.length];
        for (int i = 0; i < distances.length; i++) {
            Point left = points.get(i);
            Point right = points.get(i + 1);
            distances[i] = wasserstein1(left.histogram, right.histogram);
            distanceXs[i] = (left.x + right.x) / 2.0d;
        }

        int[] distancePlateau = KneeDetector.findPlateauRange(distanceXs, distances);
        if (distancePlateau == null || distancePlateau.length < 2) {
            return Result.fromPoints(primaryAxis, points, distances, null, -1);
        }

        int startDistance = clamp(Math.min(distancePlateau[0], distancePlateau[1]),
                0, distances.length - 1);
        int endDistance = clamp(Math.max(distancePlateau[0], distancePlateau[1]),
                0, distances.length - 1);
        int plateauStartIndex = startDistance;
        int plateauEndIndex = clamp(endDistance + 1, 0, points.size() - 1);
        if (plateauEndIndex <= plateauStartIndex) {
            return Result.fromPoints(primaryAxis, points, distances, null, -1);
        }
        int winnerIndex = plateauStartIndex
                + (plateauEndIndex - plateauStartIndex) / 2;
        return Result.fromPoints(primaryAxis, points, distances,
                new int[] { plateauStartIndex, plateauEndIndex }, winnerIndex);
    }

    private static List<Point> completedHistogramPoints(List<VariationResult> results,
                                                        ParameterKey primaryAxis) {
        List<Point> points = new ArrayList<Point>();
        for (int i = 0; i < results.size(); i++) {
            VariationResult result = results.get(i);
            if (result == null
                    || result.hasError()
                    || result.kind() != VariationResult.Kind.FILTER) {
                continue;
            }
            Object value = result.combo().get(primaryAxis);
            if (!(value instanceof Number)) {
                continue;
            }
            double x = ((Number) value).doubleValue();
            if (!isFinite(x)) {
                continue;
            }
            int[] histogram = result.histogram();
            if (histogram == null || histogram.length == 0
                    || total(histogram) <= 0L) {
                continue;
            }
            points.add(new Point(result.combo(), x, histogram));
        }
        return points;
    }

    private static double wasserstein1(int[] left, int[] right) {
        long leftTotal = total(left);
        long rightTotal = total(right);
        if (leftTotal <= 0L || rightTotal <= 0L) {
            return 0.0d;
        }
        int bins = Math.max(left == null ? 0 : left.length,
                right == null ? 0 : right.length);
        double leftCdf = 0.0d;
        double rightCdf = 0.0d;
        double distance = 0.0d;
        for (int i = 0; i < bins; i++) {
            leftCdf += countAt(left, i) / (double) leftTotal;
            rightCdf += countAt(right, i) / (double) rightTotal;
            distance += Math.abs(leftCdf - rightCdf);
        }
        return distance;
    }

    private static int countAt(int[] values, int index) {
        if (values == null || index < 0 || index >= values.length) {
            return 0;
        }
        return Math.max(0, values[index]);
    }

    private static long total(int[] values) {
        long total = 0L;
        if (values == null) {
            return total;
        }
        for (int i = 0; i < values.length; i++) {
            if (values[i] > 0) {
                total += values[i];
            }
        }
        return total;
    }

    private static boolean isFinite(double value) {
        return !Double.isNaN(value) && !Double.isInfinite(value);
    }

    private static int clamp(int value, int min, int max) {
        if (value < min) {
            return min;
        }
        if (value > max) {
            return max;
        }
        return value;
    }

    private static final class Point {
        final ParameterCombo combo;
        final double x;
        final int[] histogram;

        Point(ParameterCombo combo, double x, int[] histogram) {
            this.combo = combo;
            this.x = x;
            this.histogram = histogram == null ? new int[0] : histogram.clone();
        }
    }

    public static final class Result {
        public final ParameterKey primaryAxis;
        public final ParameterCombo plateauStartCombo;
        public final ParameterCombo plateauEndCombo;
        public final double[] distances;
        public final ParameterCombo winnerCombo;

        private final double[] orderedAxisValues;
        private final int[][] orderedHistograms;
        private final ParameterCombo[] orderedCombos;
        private final int[] plateauRange;
        private final int winnerIndex;

        private Result(ParameterKey primaryAxis,
                       ParameterCombo[] orderedCombos,
                       double[] orderedAxisValues,
                       int[][] orderedHistograms,
                       double[] distances,
                       int[] plateauRange,
                       int winnerIndex) {
            this.primaryAxis = primaryAxis;
            this.orderedCombos = orderedCombos == null
                    ? new ParameterCombo[0]
                    : orderedCombos.clone();
            this.orderedAxisValues = copy(orderedAxisValues);
            this.orderedHistograms = copy(orderedHistograms);
            this.distances = copy(distances);
            this.plateauRange = copy(plateauRange);
            this.winnerIndex = winnerIndex;
            this.plateauStartCombo = comboAt(this.orderedCombos,
                    this.plateauRange == null ? -1 : this.plateauRange[0]);
            this.plateauEndCombo = comboAt(this.orderedCombos,
                    this.plateauRange == null ? -1 : this.plateauRange[1]);
            this.winnerCombo = comboAt(this.orderedCombos, winnerIndex);
        }

        public boolean hasPlateau() {
            return plateauRange != null
                    && plateauStartCombo != null
                    && plateauEndCombo != null
                    && winnerCombo != null;
        }

        public double[] orderedAxisValues() {
            return copy(orderedAxisValues);
        }

        public int[][] orderedHistograms() {
            return copy(orderedHistograms);
        }

        public ParameterCombo[] orderedCombos() {
            return orderedCombos.clone();
        }

        public int[] plateauRange() {
            return copy(plateauRange);
        }

        public int winnerIndex() {
            return winnerIndex;
        }

        static Result empty(ParameterKey primaryAxis) {
            return new Result(primaryAxis, new ParameterCombo[0], new double[0],
                    new int[0][0], new double[0], null, -1);
        }

        static Result fromPoints(ParameterKey primaryAxis,
                                 List<Point> points,
                                 double[] distances,
                                 int[] plateauRange,
                                 int winnerIndex) {
            int size = points == null ? 0 : points.size();
            ParameterCombo[] combos = new ParameterCombo[size];
            double[] xs = new double[size];
            int[][] histograms = new int[size][];
            for (int i = 0; i < size; i++) {
                Point point = points.get(i);
                combos[i] = point.combo;
                xs[i] = point.x;
                histograms[i] = point.histogram.clone();
            }
            return new Result(primaryAxis, combos, xs, histograms, distances,
                    plateauRange, winnerIndex);
        }

        private static ParameterCombo comboAt(ParameterCombo[] combos, int index) {
            if (combos == null || index < 0 || index >= combos.length) {
                return null;
            }
            return combos[index];
        }

        private static double[] copy(double[] values) {
            if (values == null) {
                return new double[0];
            }
            double[] copy = new double[values.length];
            System.arraycopy(values, 0, copy, 0, values.length);
            return copy;
        }

        private static int[] copy(int[] values) {
            if (values == null || values.length < 2) {
                return null;
            }
            return new int[] { values[0], values[1] };
        }

        private static int[][] copy(int[][] values) {
            if (values == null) {
                return new int[0][0];
            }
            int[][] copy = new int[values.length][];
            for (int i = 0; i < values.length; i++) {
                copy[i] = values[i] == null ? new int[0] : values[i].clone();
            }
            return copy;
        }
    }
}
