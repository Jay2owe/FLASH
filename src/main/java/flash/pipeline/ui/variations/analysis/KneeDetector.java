package flash.pipeline.ui.variations.analysis;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.OptionalInt;

public final class KneeDetector {

    private static final double FLAT_DIFFERENCE_RANGE = 0.1;
    private static final double SMALL_SWEEP_CLOSE_TO_MAX = 0.05;

    private KneeDetector() {
    }

    public static OptionalInt findKneeIndex(double[] x, double[] y) {
        if (x == null || y == null) {
            return OptionalInt.empty();
        }
        int n = Math.min(x.length, y.length);
        List<Point> points = new ArrayList<Point>(n);
        for (int i = 0; i < n; i++) {
            if (isFinite(x[i]) && isFinite(y[i])) {
                points.add(new Point(i, x[i], y[i]));
            }
        }
        if (points.size() < 4) {
            return OptionalInt.empty();
        }
        Collections.sort(points, new Comparator<Point>() {
            @Override public int compare(Point a, Point b) {
                return Double.compare(a.x, b.x);
            }
        });

        Range xRange = range(points, true);
        Range yRange = range(points, false);
        if (xRange.span() <= 0.0 || yRange.span() <= 0.0) {
            return OptionalInt.empty();
        }

        boolean increasing = points.get(points.size() - 1).y >= points.get(0).y;
        double[] differences = new double[points.size()];
        double minDifference = Double.POSITIVE_INFINITY;
        double maxDifference = Double.NEGATIVE_INFINITY;
        int maxIndex = -1;
        for (int i = 0; i < points.size(); i++) {
            Point point = points.get(i);
            double xNorm = (point.x - xRange.min) / xRange.span();
            double yNorm = increasing
                    ? (point.y - yRange.min) / yRange.span()
                    : (yRange.max - point.y) / yRange.span();
            double difference = yNorm - xNorm;
            differences[i] = difference;
            if (difference < minDifference) {
                minDifference = difference;
            }
            if (difference > maxDifference) {
                maxDifference = difference;
                maxIndex = i;
            }
        }
        if (maxIndex < 0 || maxDifference - minDifference < FLAT_DIFFERENCE_RANGE) {
            return OptionalInt.empty();
        }

        int kneeIndex = maxIndex;
        int steepestTransitionIndex = steepestTransitionKnee(points, yRange, increasing);
        // Small sweeps can put the peak one sample after the visible bend.
        if (Math.abs(steepestTransitionIndex - maxIndex) <= 1) {
            kneeIndex = steepestTransitionIndex;
        } else {
            for (int i = 1; i < maxIndex; i++) {
                if (maxDifference - differences[i] <= SMALL_SWEEP_CLOSE_TO_MAX) {
                    kneeIndex = i;
                    break;
                }
            }
        }
        return OptionalInt.of(points.get(kneeIndex).originalIndex);
    }

    private static boolean isFinite(double value) {
        return !Double.isNaN(value) && !Double.isInfinite(value);
    }

    private static Range range(List<Point> points, boolean useX) {
        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < points.size(); i++) {
            double value = useX ? points.get(i).x : points.get(i).y;
            if (value < min) {
                min = value;
            }
            if (value > max) {
                max = value;
            }
        }
        return new Range(min, max);
    }

    private static int steepestTransitionKnee(List<Point> points,
                                              Range yRange,
                                              boolean increasing) {
        int bestSegment = 0;
        double bestDelta = Double.NEGATIVE_INFINITY;
        for (int i = 1; i < points.size(); i++) {
            double previous = normalisedY(points.get(i - 1).y, yRange, increasing);
            double current = normalisedY(points.get(i).y, yRange, increasing);
            double delta = Math.abs(current - previous);
            if (delta > bestDelta) {
                bestDelta = delta;
                bestSegment = i - 1;
            }
        }
        return increasing ? bestSegment : bestSegment + 1;
    }

    private static double normalisedY(double y, Range yRange, boolean increasing) {
        return increasing
                ? (y - yRange.min) / yRange.span()
                : (yRange.max - y) / yRange.span();
    }

    private static final class Point {
        final int originalIndex;
        final double x;
        final double y;

        Point(int originalIndex, double x, double y) {
            this.originalIndex = originalIndex;
            this.x = x;
            this.y = y;
        }
    }

    private static final class Range {
        final double min;
        final double max;

        Range(double min, double max) {
            this.min = min;
            this.max = max;
        }

        double span() {
            return max - min;
        }
    }
}
