package flash.pipeline.stats;

import org.apache.commons.math3.distribution.NormalDistribution;
import org.apache.commons.math3.special.Gamma;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Tukey's HSD (Honestly Significant Difference) post-hoc test.
 * <p>
 * Pairwise q-statistics use the Tukey-Kramer formulation
 * {@code q = |mean_i - mean_j| / sqrt(MSw/2 * (1/n_i + 1/n_j))} with
 * MSw and dfw from the one-way ANOVA decomposition. Adjusted p-values
 * are 1 - F_Q(q; k, dfw) where F_Q is the studentised-range CDF.
 * <p>
 * Commons Math 3.6.1 does not ship a studentised-range distribution, so
 * the CDF is approximated by the standard double-integral form
 * <pre>
 *   F_Q(q; k, n) = ∫_0^∞ f_S(s) · k · ∫_{-∞}^∞ φ(z)·[Φ(z+qs) - Φ(z)]^(k-1) dz · ds
 * </pre>
 * with S = √(W/n), W ~ χ²(n). Outer and inner integrals use the
 * trapezoidal rule on truncated ranges; tested accuracy is ≤ 1e-3 absolute
 * over the table of common critical values.
 */
public final class TukeyHSD {

    private static final NormalDistribution STD_NORMAL =
            new NormalDistribution(null, 0.0, 1.0);

    private TukeyHSD() {}

    /**
     * Tukey-Kramer pairwise post-hoc with studentised-range adjustment.
     *
     * @param groups          per-group value lists (k ≥ 2)
     * @param conditionNames  same-length list of group names
     * @return one row per unordered pair (i &lt; j); pairs containing a
     *         group with n &lt; 3 are dropped silently
     */
    public static List<PostHocRow> pairwise(List<List<Double>> groups,
                                            List<String> conditionNames) {
        if (groups == null || conditionNames == null) {
            throw new IllegalArgumentException("groups and conditionNames must not be null");
        }
        int k = groups.size();
        if (conditionNames.size() != k) {
            throw new IllegalArgumentException(
                    "groups.size() != conditionNames.size()");
        }
        if (k < 2) return Collections.emptyList();

        int N = 0;
        double[] means = new double[k];
        int[] ns = new int[k];
        for (int i = 0; i < k; i++) {
            List<Double> g = groups.get(i);
            ns[i] = g.size();
            N += ns[i];
            double sum = 0.0;
            for (Double v : g) sum += v;
            means[i] = ns[i] == 0 ? Double.NaN : sum / ns[i];
        }

        double ssWithin = 0.0;
        for (int i = 0; i < k; i++) {
            double m = means[i];
            for (Double v : groups.get(i)) {
                double d = v - m;
                ssWithin += d * d;
            }
        }
        int dfWithin = N - k;
        if (dfWithin <= 0 || ssWithin <= 0.0) return Collections.emptyList();
        double msWithin = ssWithin / dfWithin;

        List<PostHocRow> rows = new ArrayList<PostHocRow>();
        for (int i = 0; i < k; i++) {
            for (int j = i + 1; j < k; j++) {
                if (ns[i] < 3 || ns[j] < 3) continue;
                double se = Math.sqrt(msWithin / 2.0 * (1.0 / ns[i] + 1.0 / ns[j]));
                if (se == 0.0) {
                    rows.add(new PostHocRow(conditionNames.get(i),
                            conditionNames.get(j), 0.0, 1.0));
                    continue;
                }
                double q = Math.abs(means[i] - means[j]) / se;
                double p = 1.0 - studentizedRangeCdf(q, k, dfWithin);
                if (p < 0.0) p = 0.0;
                if (p > 1.0) p = 1.0;
                rows.add(new PostHocRow(conditionNames.get(i),
                        conditionNames.get(j), q, p));
            }
        }
        return rows;
    }

    /**
     * Studentised-range CDF F_Q(q; k, df).
     * Package-private for direct testing against tabulated critical values.
     */
    static double studentizedRangeCdf(double q, int k, double df) {
        if (q <= 0.0) return 0.0;
        if (k < 2 || df < 1) return Double.NaN;

        // Outer integration over s ∈ [sMin, sMax] for the chi density
        // f_S(s) = 2 · (df/2)^(df/2) / Γ(df/2) · s^(df-1) · exp(-df·s²/2).
        // Mean of S → 1 as df → ∞; tails fall off rapidly so [1e-7, 8] is safe.
        final double sMin = 1.0e-7;
        final double sMax = 8.0;
        final int nOuter = 240;
        final double hOuter = (sMax - sMin) / nOuter;

        double logConst = Math.log(2.0)
                + (df / 2.0) * Math.log(df / 2.0)
                - Gamma.logGamma(df / 2.0);

        double cdf = 0.0;
        for (int i = 0; i <= nOuter; i++) {
            double s = sMin + i * hOuter;
            double weight = (i == 0 || i == nOuter) ? 0.5 : 1.0;
            double logFs = logConst
                    + (df - 1) * Math.log(s)
                    - df * s * s / 2.0;
            if (logFs < -700.0) continue;
            double fs = Math.exp(logFs);
            if (fs == 0.0) continue;
            double fr = rangeCdfStandardNormal(q * s, k);
            cdf += weight * fs * fr * hOuter;
        }
        if (cdf < 0.0) cdf = 0.0;
        if (cdf > 1.0) cdf = 1.0;
        return cdf;
    }

    /**
     * F_R(w; k) = P(R_k ≤ w) for the range R_k of k iid N(0,1) samples,
     * as a function of the standardised range w ≥ 0.
     */
    private static double rangeCdfStandardNormal(double w, int k) {
        if (w <= 0.0) return 0.0;
        // Inner integration: k · ∫_{-∞}^∞ φ(z) · [Φ(z+w) - Φ(z)]^(k-1) dz.
        // φ decays as |z| grows; [-8, 8] captures the kernel to ≤ 1e-15.
        final double zMin = -8.0;
        final double zMax = 8.0;
        final int n = 400;
        final double h = (zMax - zMin) / n;
        double sum = 0.0;
        for (int i = 0; i <= n; i++) {
            double z = zMin + i * h;
            double phi = STD_NORMAL.density(z);
            if (phi == 0.0) continue;
            double diff = STD_NORMAL.cumulativeProbability(z + w)
                    - STD_NORMAL.cumulativeProbability(z);
            if (diff <= 0.0) continue;
            double weight = (i == 0 || i == n) ? 0.5 : 1.0;
            sum += weight * phi * Math.pow(diff, k - 1) * h;
        }
        double r = k * sum;
        if (r < 0.0) r = 0.0;
        if (r > 1.0) r = 1.0;
        return r;
    }
}
