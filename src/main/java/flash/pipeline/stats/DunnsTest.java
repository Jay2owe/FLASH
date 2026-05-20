package flash.pipeline.stats;

import org.apache.commons.math3.distribution.NormalDistribution;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Dunn's pairwise post-hoc test driven by pooled Kruskal-Wallis ranks.
 * <p>
 * Standardised statistic per pair (Dunn 1964; Daniel 1990):
 * <pre>
 *   z = (R̄_i - R̄_j) / sqrt( σ² · (1/n_i + 1/n_j) )
 *   σ² = N(N+1)/12 - Σ(t_g³ - t_g) / (12(N-1))
 * </pre>
 * Two-tailed p-values are corrected by Bonferroni over k(k-1)/2 comparisons.
 */
public final class DunnsTest {

    private static final NormalDistribution STD_NORMAL =
            new NormalDistribution(null, 0.0, 1.0);

    private DunnsTest() {}

    /**
     * Pairwise pooled-rank z-tests with Bonferroni correction.
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
        int[] ns = new int[k];
        for (int i = 0; i < k; i++) {
            ns[i] = groups.get(i).size();
            N += ns[i];
        }
        if (N < 2) return Collections.emptyList();

        double[] allValues = new double[N];
        int[] tags = new int[N];
        int idx = 0;
        for (int gi = 0; gi < k; gi++) {
            for (Double v : groups.get(gi)) {
                allValues[idx] = v;
                tags[idx] = gi;
                idx++;
            }
        }

        double[] ranks = midRanks(allValues);
        double[] meanRank = new double[k];
        for (int i = 0; i < N; i++) {
            meanRank[tags[i]] += ranks[i];
        }
        for (int i = 0; i < k; i++) {
            if (ns[i] > 0) meanRank[i] /= ns[i];
        }

        double tieSum = tieCorrectionSum(allValues);
        double sigma2;
        if (N > 1) {
            sigma2 = N * (N + 1.0) / 12.0 - tieSum / (12.0 * (N - 1));
        } else {
            sigma2 = 0.0;
        }

        int numComparisons = k * (k - 1) / 2;
        List<PostHocRow> rows = new ArrayList<PostHocRow>();
        for (int i = 0; i < k; i++) {
            for (int j = i + 1; j < k; j++) {
                if (ns[i] < 3 || ns[j] < 3) continue;
                double diff = meanRank[i] - meanRank[j];
                double se = Math.sqrt(sigma2 * (1.0 / ns[i] + 1.0 / ns[j]));
                if (se == 0.0) {
                    rows.add(new PostHocRow(conditionNames.get(i),
                            conditionNames.get(j), 0.0, 1.0));
                    continue;
                }
                double z = diff / se;
                double pRaw = 2.0 * (1.0 - STD_NORMAL.cumulativeProbability(Math.abs(z)));
                if (pRaw < 0.0) pRaw = 0.0;
                if (pRaw > 1.0) pRaw = 1.0;
                double pAdj = Math.min(1.0, pRaw * numComparisons);
                rows.add(new PostHocRow(conditionNames.get(i),
                        conditionNames.get(j), z, pAdj));
            }
        }
        return rows;
    }

    /**
     * Mid-ranks (average rank for tied values) over the pooled sample.
     */
    private static double[] midRanks(double[] values) {
        int n = values.length;
        Integer[] idx = new Integer[n];
        for (int i = 0; i < n; i++) idx[i] = i;
        final double[] vals = values;
        Arrays.sort(idx, new Comparator<Integer>() {
            @Override
            public int compare(Integer a, Integer b) {
                return Double.compare(vals[a], vals[b]);
            }
        });
        double[] ranks = new double[n];
        int i = 0;
        while (i < n) {
            int j = i;
            while (j < n - 1 && values[idx[j + 1]] == values[idx[j]]) j++;
            double avg = (i + 1 + j + 1) / 2.0;
            for (int t = i; t <= j; t++) ranks[idx[t]] = avg;
            i = j + 1;
        }
        return ranks;
    }

    /**
     * Σ(t_g³ - t_g) over each tie group g, where t_g is the count of
     * tied observations sharing a value.
     */
    private static double tieCorrectionSum(double[] values) {
        double[] sorted = values.clone();
        Arrays.sort(sorted);
        double total = 0.0;
        int i = 0;
        while (i < sorted.length) {
            int j = i;
            while (j < sorted.length - 1 && sorted[j + 1] == sorted[i]) j++;
            int t = j - i + 1;
            if (t > 1) {
                total += (double) t * t * t - t;
            }
            i = j + 1;
        }
        return total;
    }
}
