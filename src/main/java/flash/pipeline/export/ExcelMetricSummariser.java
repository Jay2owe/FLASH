package flash.pipeline.export;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Computes summary statistics for per-metric Excel sheets when the chosen
 * preset selects {@code summary_statistics} or {@code both}.
 * <p>
 * Returns mean / SEM / median / IQR / count for a list of doubles. All
 * computations ignore {@code null} and {@code NaN} entries.
 */
public final class ExcelMetricSummariser {

    private ExcelMetricSummariser() {}

    /** Snapshot of per-condition summary statistics rendered in each metric sheet. */
    public static final class Summary {
        public final int count;
        public final double mean;
        public final double sem;
        public final double median;
        public final double q1;
        public final double q3;

        Summary(int count, double mean, double sem, double median, double q1, double q3) {
            this.count = count;
            this.mean = mean;
            this.sem = sem;
            this.median = median;
            this.q1 = q1;
            this.q3 = q3;
        }

        public double iqr() {
            return q3 - q1;
        }

        /** True when no finite samples were supplied. */
        public boolean isEmpty() {
            return count == 0;
        }
    }

    public static Summary summarise(List<Double> values) {
        List<Double> cleaned = new ArrayList<Double>();
        if (values != null) {
            for (Double value : values) {
                if (value == null) continue;
                double d = value.doubleValue();
                if (Double.isNaN(d) || Double.isInfinite(d)) continue;
                cleaned.add(Double.valueOf(d));
            }
        }
        int n = cleaned.size();
        if (n == 0) {
            return new Summary(0, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN);
        }

        double sum = 0.0;
        for (Double d : cleaned) {
            sum += d.doubleValue();
        }
        double mean = sum / n;

        double varianceSum = 0.0;
        for (Double d : cleaned) {
            double delta = d.doubleValue() - mean;
            varianceSum += delta * delta;
        }
        double sem;
        if (n <= 1) {
            sem = Double.NaN;
        } else {
            double sampleVariance = varianceSum / (n - 1);
            double sd = Math.sqrt(sampleVariance);
            sem = sd / Math.sqrt((double) n);
        }

        Collections.sort(cleaned);
        double median = percentile(cleaned, 50.0);
        double q1 = percentile(cleaned, 25.0);
        double q3 = percentile(cleaned, 75.0);

        return new Summary(n, mean, sem, median, q1, q3);
    }

    /** Convenience wrapper accepting primitive doubles. */
    public static Summary summarise(double... values) {
        if (values == null || values.length == 0) {
            return summarise(Collections.<Double>emptyList());
        }
        List<Double> boxed = new ArrayList<Double>(values.length);
        for (double v : values) {
            boxed.add(Double.valueOf(v));
        }
        return summarise(boxed);
    }

    /**
     * Linear-interpolated percentile matching NumPy's default method.
     * Assumes the input list is already sorted ascending.
     */
    static double percentile(List<Double> sorted, double percent) {
        int n = sorted.size();
        if (n == 0) return Double.NaN;
        if (n == 1) return sorted.get(0).doubleValue();
        double rank = (percent / 100.0) * (n - 1);
        int lower = (int) Math.floor(rank);
        int upper = (int) Math.ceil(rank);
        if (lower == upper) {
            return sorted.get(lower).doubleValue();
        }
        double fraction = rank - lower;
        double lowerValue = sorted.get(lower).doubleValue();
        double upperValue = sorted.get(upper).doubleValue();
        return lowerValue + fraction * (upperValue - lowerValue);
    }

    static double percentile(double[] values, double percent) {
        if (values == null || values.length == 0) return Double.NaN;
        double[] copy = values.clone();
        Arrays.sort(copy);
        List<Double> boxed = new ArrayList<Double>(copy.length);
        for (double v : copy) {
            boxed.add(Double.valueOf(v));
        }
        return percentile(boxed, percent);
    }
}
