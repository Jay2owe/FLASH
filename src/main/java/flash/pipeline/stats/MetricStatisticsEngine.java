package flash.pipeline.stats;

import flash.pipeline.analyses.StatisticsConfig;
import org.apache.commons.math3.distribution.TDistribution;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

/**
 * Pure-computation engine for per-metric statistical testing.
 * <p>
 * No Swing, no file I/O — designed for direct unit testing.
 * Produces {@link StatisticRow} results for each metric.
 * <p>
 * The 3-argument {@link #analyseMetric(String, List, LinkedHashMap)} overload
 * delegates to the 4-argument overload with a default {@link StatisticsConfig}
 * (unpaired, auto K&sup2; gate, Bonferroni-corrected pairwise) and reproduces
 * the engine's pre-wizard behaviour bit-for-bit on every original column.
 */
public final class MetricStatisticsEngine {

    private MetricStatisticsEngine() {}

    /**
     * Run the full analysis for one metric using the legacy default
     * configuration (unpaired, automatic distribution detection,
     * Bonferroni post-hoc).
     */
    public static List<StatisticRow> analyseMetric(
            String metric,
            List<String> conditionOrder,
            LinkedHashMap<String, List<Double>> groups) {
        return analyseMetric(metric, conditionOrder, groups, new StatisticsConfig());
    }

    /**
     * Run the full analysis for one metric, routing tests on
     * {@link StatisticsConfig#pairedMode}, {@link StatisticsConfig#distributionMode},
     * and {@link StatisticsConfig#postHocMethod}.
     *
     * @param metric         the metric column name
     * @param conditionOrder ordered condition names
     * @param groups         per-condition value lists (same key order as conditionOrder)
     * @param cfg            routing config; {@code null} treated as defaults
     * @return list of result rows (1 global + pairwise rows)
     */
    public static List<StatisticRow> analyseMetric(
            String metric,
            List<String> conditionOrder,
            LinkedHashMap<String, List<Double>> groups,
            StatisticsConfig cfg) {

        if (cfg == null) cfg = new StatisticsConfig();
        if (cfg.pairedMode == StatisticsConfig.PairedMode.OFF) {
            String insufficientReason = insufficientGroupReason(conditionOrder, groups);
            if (insufficientReason != null) {
                List<StatisticRow> skipped = new ArrayList<StatisticRow>();
                skipped.add(skippedRow(metric, insufficientReason));
                return skipped;
            }
        }

        // -- Decide parametric vs non-parametric path --
        boolean useParametric;
        String normalityInfo;

        if (cfg.distributionMode == StatisticsConfig.DistributionMode.ASSUME_NORMAL) {
            useParametric = true;
            normalityInfo = "user assumed normal (K2 gate skipped)";
        } else if (cfg.distributionMode == StatisticsConfig.DistributionMode.ASSUME_SKEWED) {
            useParametric = false;
            normalityInfo = "user assumed skewed (K2 gate skipped)";
        } else {
            // AUTO — D'Agostino-Pearson K² gate per group.
            StringBuilder sb = new StringBuilder();
            boolean allNormal = true;
            for (String cond : conditionOrder) {
                List<Double> vals = groups.get(cond);
                boolean normal;
                if (vals.size() < 8) {
                    normal = false;
                    if (sb.length() > 0) sb.append("; ");
                    sb.append(cond).append(": n<8 -> non-parametric");
                } else {
                    double k2 = dagostinoPearsonK2(vals);
                    normal = k2 < 5.991;
                    if (sb.length() > 0) sb.append("; ");
                    sb.append(cond).append(": K2=")
                            .append(String.format(Locale.ROOT, "%.3f", k2))
                            .append(normal ? " (normal)" : " (non-normal)");
                }
                if (!normal) allNormal = false;
            }
            useParametric = allNormal;
            normalityInfo = sb.toString();
        }

        // -- Branch on paired vs unpaired --
        if (cfg.pairedMode != StatisticsConfig.PairedMode.OFF) {
            return runPaired(metric, conditionOrder, groups, useParametric,
                    normalityInfo, cfg);
        }
        return runUnpaired(metric, conditionOrder, groups, useParametric,
                normalityInfo, cfg);
    }

    /**
     * Build a "skipped" row for metrics with insufficient data.
     */
    public static StatisticRow skippedRow(String metric, String reason) {
        return StatisticRow.builder()
                .metric(metric)
                .test("Skipped")
                .inferentialUnit("Animal")
                .notes("Insufficient data (min n=3 per group): " + reason)
                .build();
    }

    // ================================================================
    //  Unpaired branch
    // ================================================================

    private static List<StatisticRow> runUnpaired(String metric,
                                                  List<String> conditionOrder,
                                                  LinkedHashMap<String, List<Double>> groups,
                                                  boolean useParametric,
                                                  String normalityInfo,
                                                  StatisticsConfig cfg) {
        int k = conditionOrder.size();
        List<StatisticRow> results = new ArrayList<StatisticRow>();

        String testName;
        double statistic;
        double pValue;

        if (k == 2) {
            List<Double> g1 = groups.get(conditionOrder.get(0));
            List<Double> g2 = groups.get(conditionOrder.get(1));
            if (useParametric) {
                testName = "Welch's t-test";
                double[] tp = ParametricStatistics.welchTTest(g1, g2);
                statistic = tp[0];
                pValue = tp[1];
            } else {
                testName = "Mann-Whitney U";
                double[] up = mannWhitneyU(g1, g2);
                statistic = up[0];
                pValue = up[1];
            }
        } else {
            List<List<Double>> groupList = new ArrayList<List<Double>>();
            for (String cond : conditionOrder) {
                groupList.add(groups.get(cond));
            }
            if (useParametric) {
                testName = "One-way ANOVA";
                double[] fp = ParametricStatistics.oneWayAnova(groupList);
                statistic = fp[0];
                pValue = fp[1];
            } else {
                testName = "Kruskal-Wallis";
                double[] hp = kruskalWallis(groupList);
                statistic = hp[0];
                pValue = hp[1];
            }
        }

        boolean significant = pValue < 0.05;

        StatisticRow.Builder global = StatisticRow.builder()
                .metric(metric)
                .test(testName)
                .statistic(statistic)
                .pValue(pValue)
                .significant(significant ? "Yes" : "No")
                .normalityResult(normalityInfo)
                .inferentialUnit("Animal")
                .totalNAnimals(totalN(groups));
        if (k == 2) {
            String c1 = conditionOrder.get(0);
            String c2 = conditionOrder.get(1);
            addPairEffect(global, c1, c2, groups.get(c1), groups.get(c2), false);
        }
        results.add(global.build());

        StatisticsConfig.PostHocMethod method = cfg.postHocMethod;
        if (method == StatisticsConfig.PostHocMethod.TUKEY) {
            appendTukeyPairs(results, metric, conditionOrder, groups,
                    testName, statistic, pValue, significant, normalityInfo);
        } else if (method == StatisticsConfig.PostHocMethod.DUNNS) {
            appendDunnsPairs(results, metric, conditionOrder, groups,
                    testName, statistic, pValue, significant, normalityInfo);
        } else {
            // BONFERRONI (default) or NONE — per-pair Welch / Mann-Whitney loop.
            boolean applyCorrection = method != StatisticsConfig.PostHocMethod.NONE;
            appendBonferroniPairs(results, metric, conditionOrder, groups,
                    useParametric, false, testName, statistic, pValue,
                    significant, normalityInfo, applyCorrection);
        }

        return results;
    }

    // ================================================================
    //  Paired branch
    // ================================================================

    private static List<StatisticRow> runPaired(String metric,
                                                List<String> conditionOrder,
                                                LinkedHashMap<String, List<Double>> groups,
                                                boolean useParametric,
                                                String normalityInfo,
                                                StatisticsConfig cfg) {
        int k = conditionOrder.size();
        int firstSize = groups.get(conditionOrder.get(0)).size();
        StringBuilder sizes = new StringBuilder();
        boolean unequal = false;
        for (String cond : conditionOrder) {
            int sz = groups.get(cond).size();
            if (sizes.length() > 0) sizes.append(", ");
            sizes.append(cond).append(" n=").append(sz);
            if (sz != firstSize) unequal = true;
        }
        if (unequal) {
            throw new IllegalArgumentException(
                    "Paired analysis requires equal group sizes for metric '"
                            + metric + "': " + sizes.toString());
        }

        List<StatisticRow> results = new ArrayList<StatisticRow>();
        String testName;
        double statistic;
        double pValue;

        if (k == 2) {
            List<Double> g1 = groups.get(conditionOrder.get(0));
            List<Double> g2 = groups.get(conditionOrder.get(1));
            if (useParametric) {
                testName = "Paired t-test";
                double[] tp = PairedTests.pairedTTest(g1, g2);
                statistic = tp[0];
                pValue = tp[1];
            } else {
                testName = "Wilcoxon signed-rank";
                double[] wp = PairedTests.wilcoxonSignedRank(g1, g2);
                statistic = wp[0];
                pValue = wp[1];
            }
        } else {
            List<List<Double>> groupList = new ArrayList<List<Double>>();
            for (String cond : conditionOrder) {
                groupList.add(groups.get(cond));
            }
            if (useParametric) {
                testName = "Repeated-measures ANOVA";
                double[] fp = PairedTests.repeatedMeasuresAnova(groupList);
                statistic = fp[0];
                pValue = fp[1];
            } else {
                testName = "Friedman test";
                double[] hp = PairedTests.friedman(groupList);
                statistic = hp[0];
                pValue = hp[1];
            }
        }

        boolean significant = pValue < 0.05;

        StatisticRow.Builder global = StatisticRow.builder()
                .metric(metric)
                .test(testName)
                .statistic(statistic)
                .pValue(pValue)
                .significant(significant ? "Yes" : "No")
                .normalityResult(normalityInfo)
                .paired(true)
                .inferentialUnit("Animal")
                .totalNAnimals(firstSize);
        if (k == 2) {
            String c1 = conditionOrder.get(0);
            String c2 = conditionOrder.get(1);
            addPairEffect(global, c1, c2, groups.get(c1), groups.get(c2), true);
        }
        results.add(global.build());

        // Pairwise post-hoc: paired t / Wilcoxon per pair. Tukey/Dunn's are
        // independence-based and fall back to Bonferroni-corrected paired
        // tests in paired mode; NONE leaves the corrected p-value blank.
        boolean applyCorrection = cfg.postHocMethod != StatisticsConfig.PostHocMethod.NONE;
        appendBonferroniPairs(results, metric, conditionOrder, groups,
                useParametric, true, testName, statistic, pValue,
                significant, normalityInfo, applyCorrection);

        return results;
    }

    // ================================================================
    //  Pairwise helpers
    // ================================================================

    private static void appendBonferroniPairs(List<StatisticRow> results,
                                              String metric,
                                              List<String> conditionOrder,
                                              LinkedHashMap<String, List<Double>> groups,
                                              boolean useParametric,
                                              boolean paired,
                                              String testName,
                                              double statistic,
                                              double pValue,
                                              boolean significant,
                                              String normalityInfo,
                                              boolean applyCorrection) {
        int k = conditionOrder.size();
        int numComparisons = k * (k - 1) / 2;
        String postHocLabel = applyCorrection ? "Bonferroni" : "None";

        for (int i = 0; i < k; i++) {
            for (int j = i + 1; j < k; j++) {
                String c1 = conditionOrder.get(i);
                String c2 = conditionOrder.get(j);
                List<Double> g1 = groups.get(c1);
                List<Double> g2 = groups.get(c2);

                String pairTest;
                double pairStat;
                double pairP;
                if (paired) {
                    if (useParametric) {
                        pairTest = "Paired t-test";
                        double[] tp = PairedTests.pairedTTest(g1, g2);
                        pairStat = tp[0];
                        pairP = tp[1];
                    } else {
                        pairTest = "Wilcoxon signed-rank";
                        double[] wp = PairedTests.wilcoxonSignedRank(g1, g2);
                        pairStat = wp[0];
                        pairP = wp[1];
                    }
                } else {
                    if (useParametric) {
                        pairTest = "Welch's t-test";
                        double[] tp = ParametricStatistics.welchTTest(g1, g2);
                        pairStat = tp[0];
                        pairP = tp[1];
                    } else {
                        pairTest = "Mann-Whitney U";
                        double[] up = mannWhitneyU(g1, g2);
                        pairStat = up[0];
                        pairP = up[1];
                    }
                }

                double corrected;
                double pForSignificance;
                if (applyCorrection) {
                    corrected = Math.min(pairP * numComparisons, 1.0);
                    pForSignificance = corrected;
                } else {
                    corrected = Double.NaN;
                    pForSignificance = pairP;
                }
                String sig = significanceStars(pForSignificance);

                StatisticRow.Builder row = StatisticRow.builder()
                        .metric(metric)
                        .test(testName)
                        .statistic(statistic)
                        .pValue(pValue)
                        .significant(significant ? "Yes" : "No")
                        .normalityResult(normalityInfo)
                        .group1(c1)
                        .group2(c2)
                        .pairwiseTest(pairTest)
                        .pairwiseStatistic(pairStat)
                        .pairwisePValue(pairP)
                        .correctedPValue(corrected)
                        .significance(sig)
                        .paired(paired)
                        .postHocMethod(postHocLabel)
                        .inferentialUnit("Animal")
                        .totalNAnimals(totalN(groups));
                addPairEffect(row, c1, c2, g1, g2, paired);
                results.add(row.build());
            }
        }
    }

    private static void appendTukeyPairs(List<StatisticRow> results,
                                         String metric,
                                         List<String> conditionOrder,
                                         LinkedHashMap<String, List<Double>> groups,
                                         String testName,
                                         double statistic,
                                         double pValue,
                                         boolean significant,
                                         String normalityInfo) {
        List<List<Double>> groupList = new ArrayList<List<Double>>();
        for (String cond : conditionOrder) groupList.add(groups.get(cond));
        List<PostHocRow> rows = TukeyHSD.pairwise(groupList, conditionOrder);
        for (PostHocRow tr : rows) {
            String sig = significanceStars(tr.pAdjusted);
            StatisticRow.Builder row = StatisticRow.builder()
                    .metric(metric)
                    .test(testName)
                    .statistic(statistic)
                    .pValue(pValue)
                    .significant(significant ? "Yes" : "No")
                    .normalityResult(normalityInfo)
                    .group1(tr.groupA)
                    .group2(tr.groupB)
                    .pairwiseTest("Tukey HSD")
                    .pairwiseStatistic(tr.statistic)
                    .pairwisePValue(Double.NaN)
                    .correctedPValue(tr.pAdjusted)
                    .significance(sig)
                    .postHocMethod("Tukey HSD")
                    .inferentialUnit("Animal")
                    .totalNAnimals(totalN(groups));
            addPairEffect(row, tr.groupA, tr.groupB,
                    groups.get(tr.groupA), groups.get(tr.groupB), false);
            results.add(row.build());
        }
    }

    private static void appendDunnsPairs(List<StatisticRow> results,
                                         String metric,
                                         List<String> conditionOrder,
                                         LinkedHashMap<String, List<Double>> groups,
                                         String testName,
                                         double statistic,
                                         double pValue,
                                         boolean significant,
                                         String normalityInfo) {
        List<List<Double>> groupList = new ArrayList<List<Double>>();
        for (String cond : conditionOrder) groupList.add(groups.get(cond));
        List<PostHocRow> rows = DunnsTest.pairwise(groupList, conditionOrder);
        for (PostHocRow dr : rows) {
            String sig = significanceStars(dr.pAdjusted);
            StatisticRow.Builder row = StatisticRow.builder()
                    .metric(metric)
                    .test(testName)
                    .statistic(statistic)
                    .pValue(pValue)
                    .significant(significant ? "Yes" : "No")
                    .normalityResult(normalityInfo)
                    .group1(dr.groupA)
                    .group2(dr.groupB)
                    .pairwiseTest("Dunn's")
                    .pairwiseStatistic(dr.statistic)
                    .pairwisePValue(Double.NaN)
                    .correctedPValue(dr.pAdjusted)
                    .significance(sig)
                    .postHocMethod("Dunn's")
                    .inferentialUnit("Animal")
                    .totalNAnimals(totalN(groups));
            addPairEffect(row, dr.groupA, dr.groupB,
                    groups.get(dr.groupA), groups.get(dr.groupB), false);
            results.add(row.build());
        }
    }

    // ================================================================
    //  Animal-level effect summaries
    // ================================================================

    private static int totalN(LinkedHashMap<String, List<Double>> groups) {
        int n = 0;
        if (groups == null) return 0;
        for (List<Double> values : groups.values()) {
            if (values == null) continue;
            for (Double value : values) {
                if (value != null && Double.isFinite(value.doubleValue())) n++;
            }
        }
        return n;
    }

    private static void addPairEffect(StatisticRow.Builder row,
                                      String group1,
                                      String group2,
                                      List<Double> values1,
                                      List<Double> values2,
                                      boolean paired) {
        if (row == null || values1 == null || values2 == null
                || values1.isEmpty() || values2.isEmpty()) {
            return;
        }

        double mean1 = mean(values1);
        double mean2 = mean(values2);
        double effect = mean2 - mean1;
        int n1 = finiteCount(values1);
        int n2 = finiteCount(values2);
        double[] ci = paired
                ? pairedMeanDifferenceCi(values1, values2)
                : independentMeanDifferenceCi(values1, values2);

        row.group1(group1)
           .group2(group2)
           .group1NAnimals(n1)
           .group2NAnimals(n2)
           .totalNAnimals(paired ? n1 : n1 + n2)
           .group1Mean(mean1)
           .group2Mean(mean2)
           .effectSizeType("MeanDifference_Group2MinusGroup1")
           .effectSize(effect)
           .effectCI95Low(ci[0])
           .effectCI95High(ci[1]);
    }

    private static int finiteCount(List<Double> values) {
        int n = 0;
        if (values == null) return 0;
        for (Double value : values) {
            if (value != null && Double.isFinite(value.doubleValue())) n++;
        }
        return n;
    }

    private static double[] independentMeanDifferenceCi(List<Double> a, List<Double> b) {
        int n1 = finiteCount(a);
        int n2 = finiteCount(b);
        if (n1 < 2 || n2 < 2) {
            return new double[]{Double.NaN, Double.NaN};
        }

        double m1 = mean(a);
        double m2 = mean(b);
        double v1 = variance(a, m1);
        double v2 = variance(b, m2);
        double effect = m2 - m1;
        double se = Math.sqrt(v1 / n1 + v2 / n2);
        if (se == 0.0) {
            return new double[]{effect, effect};
        }

        double term1 = v1 / n1;
        double term2 = v2 / n2;
        double numerator = (term1 + term2) * (term1 + term2);
        double denominator = (term1 * term1) / (n1 - 1.0)
                + (term2 * term2) / (n2 - 1.0);
        if (denominator <= 0.0) {
            return new double[]{Double.NaN, Double.NaN};
        }
        double df = numerator / denominator;
        double critical = new TDistribution(df).inverseCumulativeProbability(0.975);
        double margin = critical * se;
        return new double[]{effect - margin, effect + margin};
    }

    private static double[] pairedMeanDifferenceCi(List<Double> a, List<Double> b) {
        if (a == null || b == null || a.size() != b.size()) {
            return new double[]{Double.NaN, Double.NaN};
        }
        List<Double> differences = new ArrayList<Double>();
        for (int i = 0; i < a.size(); i++) {
            double av = a.get(i);
            double bv = b.get(i);
            if (Double.isFinite(av) && Double.isFinite(bv)) {
                differences.add(bv - av);
            }
        }
        if (differences.size() < 2) {
            return new double[]{Double.NaN, Double.NaN};
        }
        double effect = mean(differences);
        double variance = variance(differences, effect);
        double se = Math.sqrt(variance / differences.size());
        if (se == 0.0) {
            return new double[]{effect, effect};
        }
        double critical = new TDistribution(differences.size() - 1)
                .inverseCumulativeProbability(0.975);
        double margin = critical * se;
        return new double[]{effect - margin, effect + margin};
    }

    // ================================================================
    //  Significance stars
    // ================================================================

    static String significanceStars(double p) {
        if (p < 0.0001) return "****";
        if (p < 0.001) return "***";
        if (p < 0.01) return "**";
        if (p < 0.05) return "*";
        return "ns";
    }

    // ================================================================
    //  D'Agostino-Pearson omnibus normality test
    // ================================================================

    static double dagostinoPearsonK2(List<Double> data) {
        int n = data.size();
        if (n < 8) return Double.MAX_VALUE;

        double mean = mean(data);
        double std = stdDev(data, mean);
        if (std == 0) return 0.0;

        double m3 = 0, m4 = 0;
        for (double x : data) {
            double d = x - mean;
            m3 += d * d * d;
            m4 += d * d * d * d;
        }
        m3 /= n;
        m4 /= n;
        double skewness = m3 / (std * std * std);
        double kurtosis = m4 / (std * std * std * std) - 3.0;

        double seSkew = Math.sqrt(6.0 * (n - 2) / ((n + 1.0) * (n + 3.0)));
        double seKurt = Math.sqrt(24.0 * n * (n - 2) * (n - 3)
                / ((n + 1.0) * (n + 1.0) * (n + 3.0) * (n + 5.0)));

        if (seSkew == 0 || seKurt == 0) return 0.0;

        double z1 = skewness / seSkew;
        double z2 = kurtosis / seKurt;

        return z1 * z1 + z2 * z2;
    }

    // ================================================================
    //  Non-parametric tests (moved from StatisticalAnalysis, unchanged)
    // ================================================================

    static double[] kruskalWallis(List<List<Double>> groups) {
        if (groups == null || groups.size() < 2) {
            return new double[]{0.0, 1.0};
        }
        int N = 0;
        for (List<Double> g : groups) {
            if (g == null || g.isEmpty()) {
                return new double[]{0.0, 1.0};
            }
            N += g.size();
        }
        if (N == 0) {
            return new double[]{0.0, 1.0};
        }

        double[] allValues = new double[N];
        int[] groupTag = new int[N];
        int idx = 0;
        for (int gi = 0; gi < groups.size(); gi++) {
            for (double v : groups.get(gi)) {
                allValues[idx] = v;
                groupTag[idx] = gi;
                idx++;
            }
        }

        double[] ranks = computeRanks(allValues);

        double[] rankSum = new double[groups.size()];
        for (int i = 0; i < N; i++) {
            rankSum[groupTag[i]] += ranks[i];
        }

        double H = 0;
        for (int gi = 0; gi < groups.size(); gi++) {
            int ni = groups.get(gi).size();
            H += (rankSum[gi] * rankSum[gi]) / ni;
        }
        H = (12.0 / (N * (N + 1))) * H - 3.0 * (N + 1);

        int df = groups.size() - 1;
        double p = chiSquaredPValue(H, df);
        return new double[]{H, p};
    }

    static double[] mannWhitneyU(List<Double> a, List<Double> b) {
        if (a == null || b == null || a.isEmpty() || b.isEmpty()) {
            return new double[]{0.0, 1.0};
        }
        int n1 = a.size();
        int n2 = b.size();
        int N = n1 + n2;

        double[] allValues = new double[N];
        int[] groupTag = new int[N];
        for (int i = 0; i < n1; i++) {
            allValues[i] = a.get(i);
            groupTag[i] = 0;
        }
        for (int i = 0; i < n2; i++) {
            allValues[n1 + i] = b.get(i);
            groupTag[n1 + i] = 1;
        }

        double[] ranks = computeRanks(allValues);

        double R1 = 0;
        for (int i = 0; i < N; i++) {
            if (groupTag[i] == 0) R1 += ranks[i];
        }

        double U = R1 - n1 * (n1 + 1.0) / 2.0;

        double meanU = n1 * n2 / 2.0;
        double stdU = Math.sqrt(n1 * n2 * (N + 1.0) / 12.0);

        if (stdU == 0) return new double[]{U, 1.0};

        double z = (U - meanU) / stdU;

        double p = 2.0 * (1.0 - normalCdf(Math.abs(z)));
        p = Math.min(p, 1.0);
        return new double[]{U, p};
    }

    // ================================================================
    //  Rank computation
    // ================================================================

    static double[] computeRanks(double[] values) {
        int n = values.length;
        Integer[] indices = new Integer[n];
        for (int i = 0; i < n; i++) indices[i] = i;

        final double[] vals = values;
        Arrays.sort(indices, new java.util.Comparator<Integer>() {
            @Override
            public int compare(Integer a, Integer b) {
                return Double.compare(vals[a], vals[b]);
            }
        });

        double[] ranks = new double[n];
        int i = 0;
        while (i < n) {
            int j = i;
            while (j < n - 1 && values[indices[j + 1]] == values[indices[j]]) {
                j++;
            }
            double avgRank = (i + 1 + j + 1) / 2.0;
            for (int ti = i; ti <= j; ti++) {
                ranks[indices[ti]] = avgRank;
            }
            i = j + 1;
        }
        return ranks;
    }

    // ================================================================
    //  Distribution approximations (non-parametric branch only)
    // ================================================================

    static double chiSquaredPValue(double x, double df) {
        if (x <= 0 || df <= 0) return 1.0;
        double z = Math.pow(x / df, 1.0 / 3.0) - (1.0 - 2.0 / (9.0 * df));
        z /= Math.sqrt(2.0 / (9.0 * df));
        return 1.0 - normalCdf(z);
    }

    static double normalCdf(double z) {
        return 0.5 * (1.0 + erf(z / Math.sqrt(2.0)));
    }

    static double erf(double x) {
        boolean negative = x < 0;
        x = Math.abs(x);

        double t = 1.0 / (1.0 + 0.3275911 * x);
        double t2 = t * t;
        double t3 = t2 * t;
        double t4 = t3 * t;
        double t5 = t4 * t;

        double poly = 0.254829592 * t
                     - 0.284496736 * t2
                     + 1.421413741 * t3
                     - 1.453152027 * t4
                     + 1.061405429 * t5;

        double result = 1.0 - poly * Math.exp(-x * x);
        return negative ? -result : result;
    }

    // ================================================================
    //  Descriptive statistics
    // ================================================================

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

    static double stdDev(List<Double> data, double mean) {
        return Math.sqrt(variance(data, mean));
    }

    private static String insufficientGroupReason(List<String> conditionOrder,
                                                  LinkedHashMap<String, List<Double>> groups) {
        if (conditionOrder == null || groups == null || conditionOrder.size() < 2) {
            return "fewer than two groups";
        }
        for (String cond : conditionOrder) {
            List<Double> vals = groups.get(cond);
            int n = 0;
            if (vals != null) {
                for (Double value : vals) {
                    if (value != null && Double.isFinite(value.doubleValue())) {
                        n++;
                    }
                }
            }
            if (n < 3) {
                return cond + " n=" + n;
            }
        }
        return null;
    }
}
