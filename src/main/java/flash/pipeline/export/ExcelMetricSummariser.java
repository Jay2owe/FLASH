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
            return safeDifference(q3, q1);
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

        double scale = 0.0;
        for (Double d : cleaned) {
            scale = Math.max(scale, Math.abs(d.doubleValue()));
        }
        double mean;
        if (scale == 0.0) {
            mean = 0.0;
        } else {
            // Sum values after scaling them into [-1, 1]. Neumaier compensation
            // preserves small contributions without allowing a finite sum to
            // overflow before the division by n.
            double normalizedSum = 0.0;
            double compensation = 0.0;
            for (Double d : cleaned) {
                double normalized = d.doubleValue() / scale;
                double next = normalizedSum + normalized;
                if (Math.abs(normalizedSum) >= Math.abs(normalized)) {
                    compensation += (normalizedSum - next) + normalized;
                } else {
                    compensation += (normalized - next) + normalizedSum;
                }
                normalizedSum = next;
            }
            mean = scale * ((normalizedSum + compensation) / (double) n);
        }

        double sem;
        if (n <= 1) {
            sem = Double.NaN;
        } else if (scale == 0.0) {
            sem = 0.0;
        } else {
            // Compute the standard error in normalized space. This avoids
            // squaring Double.MAX_VALUE and also avoids an overflowing sample
            // standard deviation when the final SEM is representable.
            double normalizedMean = mean / scale;
            double squaredSum = 0.0;
            double compensation = 0.0;
            for (Double d : cleaned) {
                double delta = d.doubleValue() / scale - normalizedMean;
                double square = delta * delta;
                double adjusted = square - compensation;
                double next = squaredSum + adjusted;
                compensation = (next - squaredSum) - adjusted;
                squaredSum = next;
            }
            double divisor = ((double) (n - 1)) * (double) n;
            sem = scale * Math.sqrt(squaredSum / divisor);
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
        return weightedAverage(lowerValue, upperValue, fraction);
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

    private static double weightedAverage(double lower, double upper, double upperWeight) {
        double lowerWeight = 1.0 - upperWeight;
        return lower * lowerWeight + upper * upperWeight;
    }

    private static double safeDifference(double upper, double lower) {
        if ((upper >= 0.0 && lower < 0.0) || (upper < 0.0 && lower >= 0.0)) {
            return (upper * 0.5 - lower * 0.5) * 2.0;
        }
        return upper - lower;
    }
}
