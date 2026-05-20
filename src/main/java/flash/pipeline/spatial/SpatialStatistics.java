package flash.pipeline.spatial;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * Pure spatial point-pattern statistics for 2D centroid data.
 *
 * <p>This class intentionally has no ImageJ dependencies so it can be reused
 * both inline during object analysis and in standalone CSV-driven reruns.
 */
public final class SpatialStatistics {

    private SpatialStatistics() {}

    public static final class RectangularWindow {
        public final double minX;
        public final double minY;
        public final double maxX;
        public final double maxY;

        public RectangularWindow(double minX, double minY, double maxX, double maxY) {
            if (!Double.isFinite(minX) || !Double.isFinite(minY)
                    || !Double.isFinite(maxX) || !Double.isFinite(maxY)
                    || !(maxX > minX) || !(maxY > minY)) {
                throw new IllegalArgumentException("Window bounds must define a positive-area rectangle.");
            }
            this.minX = minX;
            this.minY = minY;
            this.maxX = maxX;
            this.maxY = maxY;
        }

        public double width() {
            return maxX - minX;
        }

        public double height() {
            return maxY - minY;
        }

        public double area() {
            return width() * height();
        }
    }

    public static final class Curve {
        public final double[] radii;
        public final double[] values;

        public Curve(double[] radii, double[] values) {
            this.radii = copy(radii);
            this.values = copy(values);
        }
    }

    public static final class MonteCarloEnvelope {
        public final double[] lower;
        public final double[] upper;

        public MonteCarloEnvelope(double[] lower, double[] upper) {
            this.lower = copy(lower);
            this.upper = copy(upper);
        }
    }

    /**
     * Convenience wrapper for callers that only need empirical G values.
     */
    public static double[] computeGFunction(double[][] centroids, int nBins) {
        return computeGCurve(centroids, nBins).values;
    }

    /**
     * Computes the empirical nearest-neighbour CDF (G-function) over evenly
     * spaced radii from 0 to the maximum observed nearest-neighbour distance.
     */
    public static Curve computeGCurve(double[][] centroids, int nBins) {
        if (nBins <= 0) {
            throw new IllegalArgumentException("nBins must be >= 1");
        }
        double[] nn = computeNearestNeighborDistances(centroids);
        double[] radii = new double[nBins];
        double[] values = new double[nBins];
        if (nn.length == 0) {
            return new Curve(radii, values);
        }

        double max = 0.0;
        for (double d : nn) {
            if (d > max) max = d;
        }
        for (int i = 0; i < nBins; i++) {
            double radius = max == 0.0 ? 0.0 : max * (i + 1) / nBins;
            radii[i] = radius;
            int count = 0;
            for (double d : nn) {
                if (d <= radius) count++;
            }
            values[i] = ((double) count) / nn.length;
        }
        return new Curve(radii, values);
    }

    /**
     * Computes the empirical nearest-neighbour CDF (G-function) evaluated at
     * caller-provided radii.
     */
    public static double[] computeGFunction(double[][] centroids, double[] radii) {
        return computeGCurve(centroids, radii).values;
    }

    public static Curve computeGCurve(double[][] centroids, double[] radii) {
        validateRadii(radii);
        double[] nn = computeNearestNeighborDistances(centroids);
        double[] values = new double[radii.length];
        if (nn.length == 0) {
            return new Curve(radii, values);
        }

        for (int i = 0; i < radii.length; i++) {
            int count = 0;
            for (double d : nn) {
                if (d <= radii[i]) count++;
            }
            values[i] = ((double) count) / nn.length;
        }
        return new Curve(radii, values);
    }

    /**
     * Computes Ripley's K for a rectangular observation window using reduced
     * sample border correction: only anchor points at least r from the window
     * edge contribute at each radius.
     */
    public static double[] computeRipleysK(double[][] centroids, RectangularWindow window, double[] radii) {
        validateCentroids(centroids);
        validateRadii(radii);

        double[] k = new double[radii.length];
        int n = centroids.length;
        if (n < 2) {
            return k;
        }

        double area = window.area();
        for (int ri = 0; ri < radii.length; ri++) {
            double radius = radii[ri];
            int interiorAnchors = 0;
            int pairCount = 0;

            for (int i = 0; i < n; i++) {
                double x = centroids[i][0];
                double y = centroids[i][1];
                if (!isInteriorAnchor(x, y, window, radius)) {
                    continue;
                }
                interiorAnchors++;
                for (int j = 0; j < n; j++) {
                    if (i == j) continue;
                    if (distance2D(centroids[i], centroids[j]) <= radius) {
                        pairCount++;
                    }
                }
            }

            if (interiorAnchors == 0) {
                k[ri] = Double.NaN;
            } else {
                k[ri] = area * pairCount / (interiorAnchors * (double) (n - 1));
            }
        }

        return k;
    }

    public static double[] computeLFunction(double[] kValues, double[] radii) {
        if (kValues == null || radii == null || kValues.length != radii.length) {
            throw new IllegalArgumentException("K and radii arrays must be non-null and equal length.");
        }

        double[] l = new double[kValues.length];
        for (int i = 0; i < kValues.length; i++) {
            double k = kValues[i];
            if (Double.isNaN(k) || k < 0.0) {
                l[i] = Double.NaN;
            } else {
                l[i] = Math.sqrt(k / Math.PI) - radii[i];
            }
        }
        return l;
    }

    public static MonteCarloEnvelope monteCarloEnvelopes(int nSimulations,
                                                         RectangularWindow window,
                                                         int nPoints,
                                                         double[] radii) {
        return monteCarloEnvelopes(nSimulations, window, nPoints, radii, System.nanoTime());
    }

    public static MonteCarloEnvelope monteCarloEnvelopes(int nSimulations,
                                                         RectangularWindow window,
                                                         int nPoints,
                                                         double[] radii,
                                                         long seed) {
        if (nSimulations <= 0) {
            throw new IllegalArgumentException("nSimulations must be >= 1");
        }
        if (nPoints < 0) {
            throw new IllegalArgumentException("nPoints must be >= 0");
        }
        validateRadii(radii);

        double[][] samples = new double[nSimulations][];
        Random random = new Random(seed);
        for (int sim = 0; sim < nSimulations; sim++) {
            double[][] csr = generateCSRPoints(window, nPoints, random);
            samples[sim] = computeRipleysK(csr, window, radii);
        }

        double[] lower = new double[radii.length];
        double[] upper = new double[radii.length];
        for (int ri = 0; ri < radii.length; ri++) {
            List<Double> valid = new ArrayList<Double>();
            for (int sim = 0; sim < nSimulations; sim++) {
                double value = samples[sim][ri];
                if (!Double.isNaN(value)) {
                    valid.add(value);
                }
            }
            if (valid.isEmpty()) {
                lower[ri] = Double.NaN;
                upper[ri] = Double.NaN;
                continue;
            }
            double[] sorted = new double[valid.size()];
            for (int i = 0; i < valid.size(); i++) {
                sorted[i] = valid.get(i);
            }
            Arrays.sort(sorted);
            lower[ri] = percentile(sorted, 0.025);
            upper[ri] = percentile(sorted, 0.975);
        }

        return new MonteCarloEnvelope(lower, upper);
    }

    public static double[] csrExpectation(double[] radii) {
        validateRadii(radii);
        double[] expected = new double[radii.length];
        for (int i = 0; i < radii.length; i++) {
            expected[i] = Math.PI * radii[i] * radii[i];
        }
        return expected;
    }

    public static double[] computeNearestNeighborDistances(double[][] centroids) {
        validateCentroids(centroids);
        if (centroids.length < 2) {
            return new double[0];
        }
        double[] nn = new double[centroids.length];
        for (int i = 0; i < centroids.length; i++) {
            double min = Double.POSITIVE_INFINITY;
            for (int j = 0; j < centroids.length; j++) {
                if (i == j) continue;
                double d = distance2D(centroids[i], centroids[j]);
                if (d < min) {
                    min = d;
                }
            }
            nn[i] = min;
        }
        return nn;
    }

    private static boolean isInteriorAnchor(double x, double y, RectangularWindow window, double radius) {
        return x >= window.minX + radius
                && x <= window.maxX - radius
                && y >= window.minY + radius
                && y <= window.maxY - radius;
    }

    private static double[][] generateCSRPoints(RectangularWindow window, int nPoints, Random random) {
        double[][] points = new double[nPoints][2];
        for (int i = 0; i < nPoints; i++) {
            points[i][0] = window.minX + random.nextDouble() * window.width();
            points[i][1] = window.minY + random.nextDouble() * window.height();
        }
        return points;
    }

    private static double percentile(double[] sortedValues, double quantile) {
        if (sortedValues.length == 0) {
            return Double.NaN;
        }
        if (sortedValues.length == 1) {
            return sortedValues[0];
        }
        double position = quantile * (sortedValues.length - 1);
        int lower = (int) Math.floor(position);
        int upper = (int) Math.ceil(position);
        if (lower == upper) {
            return sortedValues[lower];
        }
        double fraction = position - lower;
        return sortedValues[lower] + fraction * (sortedValues[upper] - sortedValues[lower]);
    }

    private static double distance2D(double[] a, double[] b) {
        double dx = a[0] - b[0];
        double dy = a[1] - b[1];
        return Math.sqrt(dx * dx + dy * dy);
    }

    private static void validateCentroids(double[][] centroids) {
        if (centroids == null) {
            throw new IllegalArgumentException("centroids must not be null");
        }
        for (int i = 0; i < centroids.length; i++) {
            if (centroids[i] == null || centroids[i].length < 2) {
                throw new IllegalArgumentException("Each centroid must contain at least x and y values.");
            }
            if (!Double.isFinite(centroids[i][0]) || !Double.isFinite(centroids[i][1])) {
                throw new IllegalArgumentException("Centroid coordinates must be finite real numbers.");
            }
        }
    }

    private static void validateRadii(double[] radii) {
        if (radii == null) {
            throw new IllegalArgumentException("radii must not be null");
        }
        for (int i = 0; i < radii.length; i++) {
            if (radii[i] < 0.0 || !Double.isFinite(radii[i])) {
                throw new IllegalArgumentException("radii must be non-negative real numbers.");
            }
        }
    }

    private static double[] copy(double[] values) {
        return values == null ? null : Arrays.copyOf(values, values.length);
    }
}
