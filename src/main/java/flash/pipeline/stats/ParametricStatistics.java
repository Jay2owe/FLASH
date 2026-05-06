package flash.pipeline.stats;

import org.apache.commons.math3.distribution.FDistribution;
import org.apache.commons.math3.distribution.TDistribution;
import org.apache.commons.math3.stat.inference.OneWayAnova;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Parametric statistical tests backed by Apache Commons Math.
 * <p>
 * Replaces the previous hand-rolled incomplete-beta / F-CDF implementation
 * that produced incorrect p-values.
 */
public final class ParametricStatistics {

    private ParametricStatistics() {}

    /**
     * Welch's two-sample t-test (unequal variances).
     *
     * @return {@code [t-statistic, two-tailed p-value]}
     */
    public static double[] welchTTest(List<Double> a, List<Double> b) {
        int n1 = a.size();
        int n2 = b.size();
        double m1 = mean(a);
        double m2 = mean(b);
        double v1 = variance(a, m1);
        double v2 = variance(b, m2);

        double se = Math.sqrt(v1 / n1 + v2 / n2);
        if (se == 0) return new double[]{0.0, 1.0};

        double t = (m1 - m2) / se;

        // Welch-Satterthwaite degrees of freedom
        double num = (v1 / n1 + v2 / n2) * (v1 / n1 + v2 / n2);
        double den = (v1 * v1) / (n1 * n1 * (n1 - 1))
                   + (v2 * v2) / (n2 * n2 * (n2 - 1));
        double df = (den == 0) ? 1 : num / den;

        // Two-tailed p-value from the t-distribution
        TDistribution tDist = new TDistribution(df);
        double p = 2.0 * tDist.cumulativeProbability(-Math.abs(t));

        return new double[]{t, p};
    }

    /**
     * One-way ANOVA (F-test).
     *
     * @return {@code [F-statistic, p-value]}
     */
    public static double[] oneWayAnova(List<List<Double>> groups) {
        int k = groups.size();
        int N = 0;
        double grandSum = 0;
        for (List<Double> g : groups) {
            N += g.size();
            for (double v : g) grandSum += v;
        }
        double grandMean = grandSum / N;

        double ssBetween = 0;
        double ssWithin = 0;
        for (List<Double> g : groups) {
            double gMean = mean(g);
            ssBetween += g.size() * (gMean - grandMean) * (gMean - grandMean);
            for (double v : g) {
                ssWithin += (v - gMean) * (v - gMean);
            }
        }

        int dfBetween = k - 1;
        int dfWithin = N - k;

        if (dfWithin <= 0 || ssWithin == 0) {
            return new double[]{0.0, 1.0};
        }

        double msBetween = ssBetween / dfBetween;
        double msWithin = ssWithin / dfWithin;
        double F = msBetween / msWithin;

        // p-value from the F-distribution
        FDistribution fDist = new FDistribution(dfBetween, dfWithin);
        double p = 1.0 - fDist.cumulativeProbability(F);

        return new double[]{F, p};
    }

    // -- helpers --

    static double mean(List<Double> data) {
        double sum = 0;
        for (double v : data) sum += v;
        return sum / data.size();
    }

    static double variance(List<Double> data, double mean) {
        if (data.size() < 2) return 0;
        double sum = 0;
        for (double v : data) {
            double d = v - mean;
            sum += d * d;
        }
        return sum / (data.size() - 1);
    }
}
