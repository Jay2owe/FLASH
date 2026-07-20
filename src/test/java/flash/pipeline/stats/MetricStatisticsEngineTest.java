package flash.pipeline.stats;

import flash.pipeline.analyses.StatisticsConfig;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Tests for the metric-level analysis engine — branch selection, pairwise
 * output, and Bonferroni correction.
 */
public class MetricStatisticsEngineTest {

    // ---- Two-group parametric (large n, normal data) ----

    @Test
    public void twoGroup_parametric_usesWelchTest() {
        // n=10 per group -> passes normality gate (n>=8)
        List<Double> g1 = Arrays.asList(10.0, 11.0, 12.0, 13.0, 14.0, 15.0, 16.0, 17.0, 18.0, 19.0);
        List<Double> g2 = Arrays.asList(20.0, 21.0, 22.0, 23.0, 24.0, 25.0, 26.0, 27.0, 28.0, 29.0);

        List<String> conditions = Arrays.asList("Control", "Treatment");
        LinkedHashMap<String, List<Double>> groups = new LinkedHashMap<String, List<Double>>();
        groups.put("Control", g1);
        groups.put("Treatment", g2);

        List<StatisticRow> rows = MetricStatisticsEngine.analyseMetric("TestMetric", conditions, groups);

        // Should produce 1 global + 1 pairwise = 2 rows
        assertEquals(2, rows.size());

        StatisticRow global = rows.get(0);
        assertEquals("Welch's t-test", global.test);
        assertEquals("TestMetric", global.metric);
        assertTrue("p should be very small for well-separated groups", global.pValue < 0.001);
        assertEquals("Yes", global.significant);

        StatisticRow pair = rows.get(1);
        assertEquals("Control", pair.group1);
        assertEquals("Treatment", pair.group2);
        assertEquals("Welch's t-test", pair.pairwiseTest);
        assertFalse(Double.isNaN(pair.pairwisePValue));
    }

    // ---- Two-group non-parametric (small n -> n<8 gate) ----

    @Test
    public void twoGroup_smallN_fallsBackToMannWhitney() {
        List<Double> g1 = Arrays.asList(1.0, 2.0, 3.0, 4.0, 5.0);
        List<Double> g2 = Arrays.asList(6.0, 7.0, 8.0, 9.0, 10.0);

        List<String> conditions = Arrays.asList("A", "B");
        LinkedHashMap<String, List<Double>> groups = new LinkedHashMap<String, List<Double>>();
        groups.put("A", g1);
        groups.put("B", g2);

        List<StatisticRow> rows = MetricStatisticsEngine.analyseMetric("SmallMetric", conditions, groups);

        assertEquals(2, rows.size());
        assertEquals("Mann-Whitney U", rows.get(0).test);
    }

    // ---- Three-group parametric with pairwise + Bonferroni ----

    @Test
    public void threeGroup_parametric_producesPairwiseWithBonferroni() {
        // n=10 each, normally distributed ranges
        List<Double> g1 = Arrays.asList(10.0, 11.0, 12.0, 13.0, 14.0, 15.0, 16.0, 17.0, 18.0, 19.0);
        List<Double> g2 = Arrays.asList(20.0, 21.0, 22.0, 23.0, 24.0, 25.0, 26.0, 27.0, 28.0, 29.0);
        List<Double> g3 = Arrays.asList(30.0, 31.0, 32.0, 33.0, 34.0, 35.0, 36.0, 37.0, 38.0, 39.0);

        List<String> conditions = Arrays.asList("Low", "Mid", "High");
        LinkedHashMap<String, List<Double>> groups = new LinkedHashMap<String, List<Double>>();
        groups.put("Low", g1);
        groups.put("Mid", g2);
        groups.put("High", g3);

        List<StatisticRow> rows = MetricStatisticsEngine.analyseMetric("ThreeMetric", conditions, groups);

        // 1 global + 3 pairwise = 4
        assertEquals(4, rows.size());

        StatisticRow global = rows.get(0);
        assertEquals("One-way ANOVA", global.test);
        assertEquals("Yes", global.significant);

        // Check all 3 pairwise rows exist
        assertEquals("Low", rows.get(1).group1);
        assertEquals("Mid", rows.get(1).group2);
        assertEquals("Low", rows.get(2).group1);
        assertEquals("High", rows.get(2).group2);
        assertEquals("Mid", rows.get(3).group1);
        assertEquals("High", rows.get(3).group2);

        // Bonferroni: corrected = raw * 3, but capped at 1.0
        for (int i = 1; i <= 3; i++) {
            StatisticRow pair = rows.get(i);
            double expected = Math.min(pair.pairwisePValue * 3, 1.0);
            assertEquals(expected, pair.correctedPValue, 1e-10);
        }
    }

    // ---- Skipped row ----

    @Test
    public void skippedRow_hasCorrectFields() {
        StatisticRow row = MetricStatisticsEngine.skippedRow("SomeMetric", "GroupA n=1");
        assertEquals("SomeMetric", row.metric);
        assertEquals("Skipped", row.test);
        assertTrue(Double.isNaN(row.statistic));
        assertTrue(Double.isNaN(row.pValue));
        assertTrue(row.notes.contains("GroupA n=1"));
    }

    @Test
    public void emptyGroup_returnsSkippedRowInsteadOfDividingByZero() {
        List<String> conditions = Arrays.asList("A", "B");
        LinkedHashMap<String, List<Double>> groups = new LinkedHashMap<String, List<Double>>();
        groups.put("A", new ArrayList<Double>());
        groups.put("B", Arrays.asList(1.0, 2.0, 3.0));

        List<StatisticRow> rows = MetricStatisticsEngine.analyseMetric("EmptyMetric", conditions, groups);

        assertEquals(1, rows.size());
        assertEquals("Skipped", rows.get(0).test);
        assertTrue(rows.get(0).notes.contains("A n=0"));
    }

    @Test
    public void unpairedNonfiniteValuesAreRemovedOnceForEveryReportedField() {
        List<String> conditions = Arrays.asList("A", "B");
        LinkedHashMap<String, List<Double>> clean = new LinkedHashMap<String, List<Double>>();
        clean.put("A", Arrays.asList(1.0, 2.0, 3.0, 4.0));
        clean.put("B", Arrays.asList(5.0, 6.0, 7.0, 8.0));

        LinkedHashMap<String, List<Double>> contaminated =
                new LinkedHashMap<String, List<Double>>();
        contaminated.put("A", Arrays.asList(
                1.0, Double.NaN, 2.0, Double.POSITIVE_INFINITY, 3.0, null, 4.0));
        contaminated.put("B", Arrays.asList(
                Double.NEGATIVE_INFINITY, 5.0, 6.0, Double.NaN, 7.0, 8.0));

        StatisticsConfig cfg = new StatisticsConfig();
        cfg.distributionMode = StatisticsConfig.DistributionMode.ASSUME_NORMAL;
        List<StatisticRow> expected = MetricStatisticsEngine.analyseMetric(
                "M", conditions, clean, cfg);
        List<StatisticRow> actual = MetricStatisticsEngine.analyseMetric(
                "M", conditions, contaminated, cfg);

        assertEquals(2, actual.size());
        for (int i = 0; i < actual.size(); i++) {
            assertEquivalentCalculations(expected.get(i), actual.get(i));
        }
        StatisticRow pair = actual.get(1);
        assertEquals(4, pair.group1NAnimals);
        assertEquals(4, pair.group2NAnimals);
        assertEquals(8, pair.totalNAnimals);
        assertEquals(2.5, pair.group1Mean, 0.0);
        assertEquals(6.5, pair.group2Mean, 0.0);
        assertEquals(4.0, pair.effectSize, 0.0);
        assertEquals(7, contaminated.get("A").size());

        cfg.distributionMode = StatisticsConfig.DistributionMode.ASSUME_SKEWED;
        expected = MetricStatisticsEngine.analyseMetric("M", conditions, clean, cfg);
        actual = MetricStatisticsEngine.analyseMetric("M", conditions, contaminated, cfg);
        for (int i = 0; i < actual.size(); i++) {
            assertEquivalentCalculations(expected.get(i), actual.get(i));
        }
    }

    @Test
    public void autoRoutingUsesFiniteSampleSizeAndFiniteNormalityInput() {
        List<String> conditions = Arrays.asList("A", "B");
        LinkedHashMap<String, List<Double>> groups = new LinkedHashMap<String, List<Double>>();
        groups.put("A", Arrays.asList(
                1.0, 2.0, 3.0, 4.0, Double.NaN, 5.0, 6.0, 7.0, 8.0));
        groups.put("B", Arrays.asList(
                11.0, 12.0, Double.POSITIVE_INFINITY, 13.0, 14.0,
                15.0, 16.0, 17.0, 18.0));

        List<StatisticRow> rows = MetricStatisticsEngine.analyseMetric(
                "M", conditions, groups);

        assertEquals("Welch's t-test", rows.get(0).test);
        assertTrue(rows.get(0).normalityResult.contains("A: K2="));
        assertTrue(rows.get(0).normalityResult.contains("B: K2="));
        assertEquals(16, rows.get(0).totalNAnimals);
    }

    @Test
    public void tiedRankTestsMatchHandComputedReferences() {
        List<List<Double>> groups = new ArrayList<List<Double>>();
        groups.add(Arrays.asList(0.0, 0.0, 0.0));
        groups.add(Arrays.asList(0.0, 1.0, 1.0));
        groups.add(Arrays.asList(1.0, 1.0, 1.0));

        // Pooled midranks are 2.5 for four zeroes and 7 for five ones.
        // Untied H=4.2 and C=1-(60+120)/(9^3-9)=0.75, so H/C=5.6.
        double[] kw = MetricStatisticsEngine.kruskalWallis(groups);
        assertEquals(5.6, kw[0], 1.0e-12);
        assertEquals(Math.exp(-2.8), kw[1], 1.0e-12); // chi-square df=2

        // For [0,0,0] vs [0,1,1], U=1.5. Tie-corrected variance is
        // 9/12 * (7 - (60+6)/(6*5)) = 3.6; z=-3/sqrt(3.6).
        double[] mw = MetricStatisticsEngine.mannWhitneyU(
                Arrays.asList(0.0, 0.0, 0.0),
                Arrays.asList(0.0, 1.0, 1.0));
        assertEquals(1.5, mw[0], 0.0);
        assertEquals(0.11384630, mw[1], 1.0e-7);
    }

    @Test
    public void allTiedRankTestsAreFiniteAndNeutral() {
        List<List<Double>> groups = new ArrayList<List<Double>>();
        groups.add(Arrays.asList(1.0, 1.0, 1.0));
        groups.add(Arrays.asList(1.0, 1.0, 1.0));
        groups.add(Arrays.asList(1.0, 1.0, 1.0));

        double[] kw = MetricStatisticsEngine.kruskalWallis(groups);
        double[] mw = MetricStatisticsEngine.mannWhitneyU(
                groups.get(0), groups.get(1));

        assertArrayEquals(new double[]{0.0, 1.0}, kw, 0.0);
        assertEquals(4.5, mw[0], 0.0);
        assertEquals(1.0, mw[1], 0.0);
        assertTrue(Double.isFinite(mw[0]));

        LinkedHashMap<String, List<Double>> tiedGroups =
                new LinkedHashMap<String, List<Double>>();
        tiedGroups.put("A", groups.get(0));
        tiedGroups.put("B", groups.get(1));
        tiedGroups.put("C", groups.get(2));
        StatisticsConfig cfg = new StatisticsConfig();
        cfg.distributionMode = StatisticsConfig.DistributionMode.ASSUME_SKEWED;
        cfg.postHocMethod = StatisticsConfig.PostHocMethod.DUNNS;
        List<StatisticRow> rows = MetricStatisticsEngine.analyseMetric(
                "All tied", Arrays.asList("A", "B", "C"), tiedGroups, cfg);
        assertEquals(4, rows.size());
        assertEquals(0.0, rows.get(0).statistic, 0.0);
        assertEquals(1.0, rows.get(0).pValue, 0.0);
        for (int i = 1; i < rows.size(); i++) {
            assertEquals(0.0, rows.get(i).pairwiseStatistic, 0.0);
            assertEquals(1.0, rows.get(i).correctedPValue, 0.0);
        }
    }

    @Test
    public void kruskalAndDunnUseCompatibleTiedPooledRanks() {
        List<String> conditions = Arrays.asList("Low", "Mid", "High");
        LinkedHashMap<String, List<Double>> groups = new LinkedHashMap<String, List<Double>>();
        groups.put("Low", Arrays.asList(0.0, 0.0, 0.0));
        groups.put("Mid", Arrays.asList(0.0, 1.0, 1.0));
        groups.put("High", Arrays.asList(1.0, 1.0, 1.0));
        StatisticsConfig cfg = new StatisticsConfig();
        cfg.distributionMode = StatisticsConfig.DistributionMode.ASSUME_SKEWED;
        cfg.postHocMethod = StatisticsConfig.PostHocMethod.DUNNS;

        List<StatisticRow> rows = MetricStatisticsEngine.analyseMetric(
                "Binary", conditions, groups, cfg);

        assertEquals(5.6, rows.get(0).statistic, 1.0e-12);
        StatisticRow lowHigh = findPair(rows, "Low", "High");
        // Dunn: sigma^2=9*10/12-180/(12*8)=5.625;
        // z=(2.5-7)/sqrt(5.625*(1/3+1/3)).
        assertEquals(-2.323790008, lowHigh.pairwiseStatistic, 1.0e-9);
    }

    @Test
    public void deterministicAnovaAndTukeyRowsRetainEffects() {
        List<String> conditions = Arrays.asList("A", "B", "C");
        LinkedHashMap<String, List<Double>> groups = new LinkedHashMap<String, List<Double>>();
        groups.put("A", Arrays.asList(1.0, 1.0, 1.0));
        groups.put("B", Arrays.asList(2.0, 2.0, 2.0));
        groups.put("C", Arrays.asList(3.0, 3.0, 3.0));
        StatisticsConfig cfg = new StatisticsConfig();
        cfg.distributionMode = StatisticsConfig.DistributionMode.ASSUME_NORMAL;
        cfg.postHocMethod = StatisticsConfig.PostHocMethod.TUKEY;

        List<StatisticRow> rows = MetricStatisticsEngine.analyseMetric(
                "Constant", conditions, groups, cfg);

        assertEquals(4, rows.size());
        assertEquals(Double.POSITIVE_INFINITY, rows.get(0).statistic, 0.0);
        assertEquals(0.0, rows.get(0).pValue, 0.0);
        assertEquals("Yes", rows.get(0).significant);
        for (int i = 1; i < rows.size(); i++) {
            StatisticRow pair = rows.get(i);
            assertEquals(Double.POSITIVE_INFINITY, pair.pairwiseStatistic, 0.0);
            assertEquals(0.0, pair.correctedPValue, 0.0);
            assertTrue(Double.isFinite(pair.group1Mean));
            assertTrue(Double.isFinite(pair.group2Mean));
            assertTrue(Double.isFinite(pair.effectSize));
            assertEquals(pair.effectSize, pair.effectCI95Low, 0.0);
            assertEquals(pair.effectSize, pair.effectCI95High, 0.0);
        }
    }

    private static void assertEquivalentCalculations(StatisticRow expected,
                                                     StatisticRow actual) {
        assertEquals(expected.test, actual.test);
        assertEquals(expected.statistic, actual.statistic, 0.0);
        assertEquals(expected.pValue, actual.pValue, 0.0);
        assertEquals(expected.normalityResult, actual.normalityResult);
        assertEquals(expected.pairwiseStatistic, actual.pairwiseStatistic, 0.0);
        assertEquals(expected.pairwisePValue, actual.pairwisePValue, 0.0);
        assertEquals(expected.correctedPValue, actual.correctedPValue, 0.0);
        assertEquals(expected.group1NAnimals, actual.group1NAnimals);
        assertEquals(expected.group2NAnimals, actual.group2NAnimals);
        assertEquals(expected.totalNAnimals, actual.totalNAnimals);
        assertEquals(expected.group1Mean, actual.group1Mean, 0.0);
        assertEquals(expected.group2Mean, actual.group2Mean, 0.0);
        assertEquals(expected.effectSize, actual.effectSize, 0.0);
        assertEquals(expected.effectCI95Low, actual.effectCI95Low, 0.0);
        assertEquals(expected.effectCI95High, actual.effectCI95High, 0.0);
    }

    private static StatisticRow findPair(List<StatisticRow> rows, String a, String b) {
        for (StatisticRow row : rows) {
            if (a.equals(row.group1) && b.equals(row.group2)) return row;
        }
        throw new AssertionError("No row " + a + "-" + b);
    }
}
