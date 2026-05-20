package flash.pipeline.stats;

import org.apache.commons.math3.distribution.ChiSquaredDistribution;
import org.apache.commons.math3.distribution.FDistribution;
import org.apache.commons.math3.distribution.NormalDistribution;
import org.apache.commons.math3.distribution.TDistribution;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Paired-sample statistical tests for statistics wizard routing.
 */
public final class PairedTests {

    private PairedTests() {}

    /**
     * Paired two-sided t-test. Invalid pairs are dropped symmetrically.
     *
     * @return {@code [t-statistic, two-tailed p-value]}
     */
    public static double[] pairedTTest(List<Double> a, List<Double> b) {
        requireSameSize(a, b);

        List<Double> differences = finiteDifferences(a, b);
        int n = differences.size();
        if (n < 2) return new double[]{0.0, 1.0};

        double mean = mean(differences);
        double variance = variance(differences, mean);
        if (variance == 0.0) return new double[]{0.0, 1.0};

        double t = mean / Math.sqrt(variance / n);
        TDistribution distribution = new TDistribution(n - 1);
        double p = 2.0 * distribution.cumulativeProbability(-Math.abs(t));
        return new double[]{t, p};
    }

    /**
     * Wilcoxon signed-rank test. Invalid pairs are dropped symmetrically.
     * <p>
     * Uses average ranks for ties. Zero differences are ranked using Pratt-style
     * ranking, then omitted from the signed-rank sums. Exact two-sided p-values
     * are used for up to 25 non-zero differences; larger samples use a normal
     * approximation.
     *
     * @return {@code [W-statistic, two-tailed p-value]}
     */
    public static double[] wilcoxonSignedRank(List<Double> a, List<Double> b) {
        requireSameSize(a, b);

        List<Double> differences = finiteDifferences(a, b);
        if (differences.isEmpty()) return new double[]{0.0, 1.0};

        List<RankedDifference> ranked = rankAbsoluteDifferences(differences);
        List<Double> nonZeroRanks = new ArrayList<Double>();
        double positive = 0.0;
        double negative = 0.0;
        for (RankedDifference item : ranked) {
            if (item.difference > 0.0) {
                positive += item.rank;
                nonZeroRanks.add(item.rank);
            } else if (item.difference < 0.0) {
                negative += item.rank;
                nonZeroRanks.add(item.rank);
            }
        }

        if (nonZeroRanks.isEmpty()) return new double[]{0.0, 1.0};

        double w = Math.min(positive, negative);
        double p;
        if (nonZeroRanks.size() <= 25) {
            p = exactWilcoxonPValue(nonZeroRanks, w);
        } else {
            p = approximateWilcoxonPValue(nonZeroRanks, positive);
        }
        return new double[]{w, p};
    }

    /**
     * Balanced one-way repeated-measures ANOVA. Subject is the value index.
     * Invalid subject rows are dropped across all groups.
     * <p>
     * This returns uncorrected p-values; no sphericity correction is applied.
     *
     * @return {@code [F-statistic, p-value]}
     */
    public static double[] repeatedMeasuresAnova(List<List<Double>> groups) {
        List<List<Double>> cleanGroups = cleanBalancedGroups(groups);
        int k = cleanGroups.size();
        int n = cleanGroups.get(0).size();
        if (k < 2 || n < 2) return new double[]{0.0, 1.0};

        double grandMean = grandMean(cleanGroups);
        double ssTotal = 0.0;
        for (List<Double> group : cleanGroups) {
            for (double value : group) {
                double diff = value - grandMean;
                ssTotal += diff * diff;
            }
        }

        double ssConditions = 0.0;
        for (List<Double> group : cleanGroups) {
            double diff = mean(group) - grandMean;
            ssConditions += n * diff * diff;
        }

        double ssSubjects = 0.0;
        for (int subject = 0; subject < n; subject++) {
            double subjectSum = 0.0;
            for (List<Double> group : cleanGroups) {
                subjectSum += group.get(subject);
            }
            double diff = subjectSum / k - grandMean;
            ssSubjects += k * diff * diff;
        }

        double ssError = ssTotal - ssConditions - ssSubjects;
        if (ssError <= 0.0) return new double[]{0.0, 1.0};

        int dfConditions = k - 1;
        int dfError = (k - 1) * (n - 1);
        double f = (ssConditions / dfConditions) / (ssError / dfError);
        FDistribution distribution = new FDistribution(dfConditions, dfError);
        double p = 1.0 - distribution.cumulativeProbability(f);
        return new double[]{f, p};
    }

    /**
     * Friedman test with standard tie correction. Invalid subject rows are
     * dropped across all groups.
     *
     * @return {@code [chi-square statistic, p-value]}
     */
    public static double[] friedman(List<List<Double>> groups) {
        List<List<Double>> cleanGroups = cleanBalancedGroups(groups);
        int k = cleanGroups.size();
        int n = cleanGroups.get(0).size();
        if (k < 2 || n < 2) return new double[]{0.0, 1.0};

        double[] rankSums = new double[k];
        double tieCorrectionSum = 0.0;
        for (int subject = 0; subject < n; subject++) {
            List<RankedValue> values = new ArrayList<RankedValue>();
            for (int group = 0; group < k; group++) {
                values.add(new RankedValue(group, cleanGroups.get(group).get(subject)));
            }
            Collections.sort(values, new Comparator<RankedValue>() {
                public int compare(RankedValue left, RankedValue right) {
                    return Double.compare(left.value, right.value);
                }
            });

            int index = 0;
            while (index < values.size()) {
                int end = index + 1;
                while (end < values.size() && values.get(end).value == values.get(index).value) {
                    end++;
                }
                double rank = (index + 1 + end) / 2.0;
                int tied = end - index;
                if (tied > 1) tieCorrectionSum += tied * tied * tied - tied;
                for (int i = index; i < end; i++) {
                    rankSums[values.get(i).group] += rank;
                }
                index = end;
            }
        }

        double rankSquareSum = 0.0;
        for (double rankSum : rankSums) {
            rankSquareSum += rankSum * rankSum;
        }

        double q = (12.0 / (n * k * (k + 1.0))) * rankSquareSum - 3.0 * n * (k + 1.0);
        double correction = 1.0 - tieCorrectionSum / (n * k * (k * k - 1.0));
        if (correction > 0.0) q /= correction;

        ChiSquaredDistribution distribution = new ChiSquaredDistribution(k - 1);
        double p = 1.0 - distribution.cumulativeProbability(q);
        return new double[]{q, p};
    }

    private static void requireSameSize(List<Double> a, List<Double> b) {
        if (a == null || b == null) {
            throw new IllegalArgumentException("Paired samples must not be null");
        }
        if (a.size() != b.size()) {
            throw new IllegalArgumentException("Paired samples must have the same size");
        }
    }

    private static List<Double> finiteDifferences(List<Double> a, List<Double> b) {
        List<Double> differences = new ArrayList<Double>();
        for (int i = 0; i < a.size(); i++) {
            double av = a.get(i);
            double bv = b.get(i);
            if (Double.isFinite(av) && Double.isFinite(bv)) {
                differences.add(av - bv);
            }
        }
        return differences;
    }

    private static List<List<Double>> cleanBalancedGroups(List<List<Double>> groups) {
        if (groups == null || groups.isEmpty()) {
            throw new IllegalArgumentException("At least one group is required");
        }

        if (groups.get(0) == null) {
            throw new IllegalArgumentException("Repeated-measures groups must not be null");
        }
        int size = groups.get(0).size();
        for (List<Double> group : groups) {
            if (group == null) {
                throw new IllegalArgumentException("Repeated-measures groups must not be null");
            }
            if (group.size() != size) {
                throw new IllegalArgumentException("Repeated-measures groups must have the same size");
            }
        }

        List<List<Double>> cleanGroups = new ArrayList<List<Double>>();
        for (int group = 0; group < groups.size(); group++) {
            cleanGroups.add(new ArrayList<Double>());
        }

        for (int subject = 0; subject < size; subject++) {
            boolean finite = true;
            for (List<Double> group : groups) {
                if (!Double.isFinite(group.get(subject))) {
                    finite = false;
                    break;
                }
            }
            if (finite) {
                for (int group = 0; group < groups.size(); group++) {
                    cleanGroups.get(group).add(groups.get(group).get(subject));
                }
            }
        }

        return cleanGroups;
    }

    private static List<RankedDifference> rankAbsoluteDifferences(List<Double> differences) {
        List<RankedDifference> ranked = new ArrayList<RankedDifference>();
        for (double difference : differences) {
            ranked.add(new RankedDifference(difference, Math.abs(difference)));
        }
        Collections.sort(ranked, new Comparator<RankedDifference>() {
            public int compare(RankedDifference left, RankedDifference right) {
                return Double.compare(left.absoluteDifference, right.absoluteDifference);
            }
        });

        int index = 0;
        while (index < ranked.size()) {
            int end = index + 1;
            while (end < ranked.size()
                    && ranked.get(end).absoluteDifference == ranked.get(index).absoluteDifference) {
                end++;
            }
            double rank = (index + 1 + end) / 2.0;
            for (int i = index; i < end; i++) {
                ranked.get(i).rank = rank;
            }
            index = end;
        }

        return ranked;
    }

    private static double exactWilcoxonPValue(List<Double> ranks, double observedW) {
        Map<Integer, Long> counts = new HashMap<Integer, Long>();
        counts.put(0, 1L);
        int total = 0;
        for (double rank : ranks) {
            int scaledRank = (int) Math.round(rank * 2.0);
            total += scaledRank;
            Map<Integer, Long> next = new HashMap<Integer, Long>();
            for (Map.Entry<Integer, Long> entry : counts.entrySet()) {
                addCount(next, entry.getKey(), entry.getValue());
                addCount(next, entry.getKey() + scaledRank, entry.getValue());
            }
            counts = next;
        }

        int observed = (int) Math.round(observedW * 2.0);
        long extreme = 0L;
        long all = 1L << ranks.size();
        for (Map.Entry<Integer, Long> entry : counts.entrySet()) {
            int sum = entry.getKey();
            if (Math.min(sum, total - sum) <= observed) {
                extreme += entry.getValue();
            }
        }
        return Math.min(1.0, extreme / (double) all);
    }

    private static void addCount(Map<Integer, Long> counts, int key, long value) {
        Long existing = counts.get(key);
        counts.put(key, existing == null ? value : existing + value);
    }

    private static double approximateWilcoxonPValue(List<Double> ranks, double positive) {
        double rankSum = 0.0;
        double rankSquareSum = 0.0;
        for (double rank : ranks) {
            rankSum += rank;
            rankSquareSum += rank * rank;
        }
        double mean = rankSum / 2.0;
        double variance = rankSquareSum / 4.0;
        if (variance == 0.0) return 1.0;

        double zNumerator = Math.abs(positive - mean) - 0.5;
        if (zNumerator < 0.0) zNumerator = 0.0;
        double z = zNumerator / Math.sqrt(variance);
        NormalDistribution distribution = new NormalDistribution();
        return 2.0 * distribution.cumulativeProbability(-Math.abs(z));
    }

    private static double grandMean(List<List<Double>> groups) {
        double sum = 0.0;
        int count = 0;
        for (List<Double> group : groups) {
            for (double value : group) {
                sum += value;
                count++;
            }
        }
        return sum / count;
    }

    private static double mean(List<Double> data) {
        double sum = 0.0;
        for (double value : data) {
            sum += value;
        }
        return sum / data.size();
    }

    private static double variance(List<Double> data, double mean) {
        if (data.size() < 2) return 0.0;
        double sum = 0.0;
        for (double value : data) {
            double diff = value - mean;
            sum += diff * diff;
        }
        return sum / (data.size() - 1);
    }

    private static final class RankedDifference {
        private final double difference;
        private final double absoluteDifference;
        private double rank;

        private RankedDifference(double difference, double absoluteDifference) {
            this.difference = difference;
            this.absoluteDifference = absoluteDifference;
        }
    }

    private static final class RankedValue {
        private final int group;
        private final double value;

        private RankedValue(int group, double value) {
            this.group = group;
            this.value = value;
        }
    }
}
