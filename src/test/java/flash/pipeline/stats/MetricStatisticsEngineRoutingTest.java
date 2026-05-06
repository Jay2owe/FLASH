package flash.pipeline.stats;

import flash.pipeline.analyses.StatisticsConfig;
import org.junit.Test;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Routing matrix for {@link MetricStatisticsEngine#analyseMetric(String, List, LinkedHashMap, StatisticsConfig)}.
 * <p>
 * Each test checks one corner of the (paired, distribution, post-hoc) cube
 * and asserts the engine reaches the right test variant. Numeric results
 * are checked at the test-name level — accuracy of the underlying tests is
 * covered by {@code PairedTestsTest}, {@code TukeyHSDTest}, {@code DunnsTestTest},
 * and {@code MetricStatisticsEngineTest}.
 */
public class MetricStatisticsEngineRoutingTest {

    // ------------ fixtures ------------

    private static LinkedHashMap<String, List<Double>> twoGroupNormal() {
        LinkedHashMap<String, List<Double>> g = new LinkedHashMap<String, List<Double>>();
        g.put("Control",   Arrays.asList(10.0, 11.0, 12.0, 13.0, 14.0, 15.0, 16.0, 17.0, 18.0, 19.0));
        g.put("Treatment", Arrays.asList(20.0, 21.0, 22.0, 23.0, 24.0, 25.0, 26.0, 27.0, 28.0, 29.0));
        return g;
    }

    private static LinkedHashMap<String, List<Double>> threeGroupNormal() {
        LinkedHashMap<String, List<Double>> g = new LinkedHashMap<String, List<Double>>();
        g.put("Low",  Arrays.asList(10.0, 11.0, 12.0, 13.0, 14.0, 15.0, 16.0, 17.0, 18.0, 19.0));
        g.put("Mid",  Arrays.asList(20.0, 21.0, 22.0, 23.0, 24.0, 25.0, 26.0, 27.0, 28.0, 29.0));
        g.put("High", Arrays.asList(30.0, 31.0, 32.0, 33.0, 34.0, 35.0, 36.0, 37.0, 38.0, 39.0));
        return g;
    }

    private static LinkedHashMap<String, List<Double>> threeGroupSmallN() {
        // n=5 < 8 ⇒ AUTO falls back to non-parametric (Kruskal-Wallis path).
        LinkedHashMap<String, List<Double>> g = new LinkedHashMap<String, List<Double>>();
        g.put("Low",  Arrays.asList(10.0, 11.0, 12.0, 13.0, 14.0));
        g.put("Mid",  Arrays.asList(20.0, 21.0, 22.0, 23.0, 24.0));
        g.put("High", Arrays.asList(30.0, 31.0, 32.0, 33.0, 34.0));
        return g;
    }

    private static List<String> twoConditions() {
        return Arrays.asList("Control", "Treatment");
    }

    private static List<String> threeConditions() {
        return Arrays.asList("Low", "Mid", "High");
    }

    // ------------ default-config regression ------------

    @Test
    public void defaultConfig_reproducesLegacyTwoGroupBehaviour() {
        LinkedHashMap<String, List<Double>> groups = twoGroupNormal();

        List<StatisticRow> legacy = MetricStatisticsEngine.analyseMetric(
                "M", twoConditions(), groups);
        List<StatisticRow> withCfg = MetricStatisticsEngine.analyseMetric(
                "M", twoConditions(), groups, new StatisticsConfig());

        assertEquals(legacy.size(), withCfg.size());
        for (int i = 0; i < legacy.size(); i++) {
            StatisticRow a = legacy.get(i);
            StatisticRow b = withCfg.get(i);
            assertEquals("test name",        a.test, b.test);
            assertEquals("statistic",        a.statistic,        b.statistic, 1e-12);
            assertEquals("pValue",           a.pValue,           b.pValue, 1e-12);
            assertEquals("significant",      a.significant,      b.significant);
            assertEquals("normalityResult",  a.normalityResult,  b.normalityResult);
            assertEquals("group1",           a.group1,           b.group1);
            assertEquals("group2",           a.group2,           b.group2);
            assertEquals("pairwiseTest",     a.pairwiseTest,     b.pairwiseTest);
            assertEquals("pairwiseStatistic",a.pairwiseStatistic,b.pairwiseStatistic, 1e-12);
            assertEquals("pairwisePValue",   a.pairwisePValue,   b.pairwisePValue, 1e-12);
            assertEquals("correctedPValue",  a.correctedPValue,  b.correctedPValue, 1e-12);
            assertEquals("significance",     a.significance,     b.significance);
            assertEquals("notes",            a.notes,            b.notes);
        }
    }

    @Test
    public void defaultConfig_reproducesLegacyThreeGroupBehaviour() {
        LinkedHashMap<String, List<Double>> groups = threeGroupNormal();

        List<StatisticRow> legacy = MetricStatisticsEngine.analyseMetric(
                "M", threeConditions(), groups);
        List<StatisticRow> withCfg = MetricStatisticsEngine.analyseMetric(
                "M", threeConditions(), groups, new StatisticsConfig());

        assertEquals(4, legacy.size());
        assertEquals(legacy.size(), withCfg.size());
        for (int i = 0; i < legacy.size(); i++) {
            assertEquals(legacy.get(i).test,           withCfg.get(i).test);
            assertEquals(legacy.get(i).statistic,      withCfg.get(i).statistic, 1e-12);
            assertEquals(legacy.get(i).pValue,         withCfg.get(i).pValue, 1e-12);
            assertEquals(legacy.get(i).pairwiseTest,   withCfg.get(i).pairwiseTest);
            assertEquals(legacy.get(i).pairwisePValue, withCfg.get(i).pairwisePValue, 1e-12);
            assertEquals(legacy.get(i).correctedPValue,withCfg.get(i).correctedPValue, 1e-12);
        }
    }

    @Test
    public void defaultConfig_pairwiseRowsAreFlaggedBonferroni() {
        List<StatisticRow> rows = MetricStatisticsEngine.analyseMetric(
                "M", threeConditions(), threeGroupNormal(), new StatisticsConfig());
        assertEquals(4, rows.size());
        // Global row: paired=false, no post-hoc tag.
        assertFalse(rows.get(0).paired);
        assertEquals("", rows.get(0).postHocMethod);
        // Pairwise rows: Bonferroni-tagged.
        for (int i = 1; i < rows.size(); i++) {
            assertFalse("pairwise row " + i + " paired", rows.get(i).paired);
            assertEquals("Bonferroni", rows.get(i).postHocMethod);
        }
    }

    // ------------ distribution-mode forcing ------------

    @Test
    public void assumeNormal_skipsK2GateAndPicksParametric() {
        StatisticsConfig cfg = new StatisticsConfig();
        cfg.distributionMode = StatisticsConfig.DistributionMode.ASSUME_NORMAL;

        // n=5 per group: AUTO would fall back to Kruskal-Wallis. Forced normal
        // path must reach One-way ANOVA + Welch's pairwise.
        List<StatisticRow> rows = MetricStatisticsEngine.analyseMetric(
                "M", threeConditions(), threeGroupSmallN(), cfg);
        assertEquals("One-way ANOVA", rows.get(0).test);
        assertTrue(rows.get(0).normalityResult.contains("user assumed normal"));
        assertEquals("Welch's t-test", rows.get(1).pairwiseTest);
    }

    @Test
    public void assumeSkewed_forcesNonParametricEvenWithLargeNormalData() {
        StatisticsConfig cfg = new StatisticsConfig();
        cfg.distributionMode = StatisticsConfig.DistributionMode.ASSUME_SKEWED;

        // n=10 normal-ish data: AUTO would pick ANOVA. Forced skewed path
        // must reach Kruskal-Wallis + Mann-Whitney pairwise.
        List<StatisticRow> rows = MetricStatisticsEngine.analyseMetric(
                "M", threeConditions(), threeGroupNormal(), cfg);
        assertEquals("Kruskal-Wallis", rows.get(0).test);
        assertTrue(rows.get(0).normalityResult.contains("user assumed skewed"));
        assertEquals("Mann-Whitney U", rows.get(1).pairwiseTest);
    }

    // ------------ paired routing ------------

    @Test
    public void hemispherePaired_twoGroups_invokesPairedTTest() {
        StatisticsConfig cfg = new StatisticsConfig();
        cfg.pairedMode = StatisticsConfig.PairedMode.HEMISPHERE;

        List<StatisticRow> rows = MetricStatisticsEngine.analyseMetric(
                "M", twoConditions(), twoGroupNormal(), cfg);

        assertEquals(2, rows.size());
        assertEquals("Paired t-test", rows.get(0).test);
        assertTrue(rows.get(0).paired);
        assertEquals("Paired t-test", rows.get(1).pairwiseTest);
        assertTrue(rows.get(1).paired);
        assertEquals("Bonferroni", rows.get(1).postHocMethod);
    }

    @Test
    public void hemispherePaired_twoGroups_skewedRoutesToWilcoxon() {
        StatisticsConfig cfg = new StatisticsConfig();
        cfg.pairedMode = StatisticsConfig.PairedMode.HEMISPHERE;
        cfg.distributionMode = StatisticsConfig.DistributionMode.ASSUME_SKEWED;

        List<StatisticRow> rows = MetricStatisticsEngine.analyseMetric(
                "M", twoConditions(), twoGroupNormal(), cfg);

        assertEquals("Wilcoxon signed-rank", rows.get(0).test);
        assertEquals("Wilcoxon signed-rank", rows.get(1).pairwiseTest);
        assertTrue(rows.get(0).paired);
    }

    @Test
    public void pairedMode_unequalGroupSizes_throwsIllegalArgument() {
        StatisticsConfig cfg = new StatisticsConfig();
        cfg.pairedMode = StatisticsConfig.PairedMode.HEMISPHERE;

        LinkedHashMap<String, List<Double>> groups = new LinkedHashMap<String, List<Double>>();
        groups.put("L", Arrays.asList(1.0, 2.0, 3.0));
        groups.put("R", Arrays.asList(1.0, 2.0));

        try {
            MetricStatisticsEngine.analyseMetric(
                    "BrokenMetric", Arrays.asList("L", "R"), groups, cfg);
            fail("Expected IllegalArgumentException for unequal paired sizes");
        } catch (IllegalArgumentException expected) {
            assertTrue("error mentions metric name",
                    expected.getMessage().contains("BrokenMetric"));
        }
    }

    // ------------ post-hoc routing (k>=3 unpaired) ------------

    @Test
    public void tukeyPostHoc_replacesBonferroniLoopWithStudentisedRange() {
        StatisticsConfig cfg = new StatisticsConfig();
        cfg.postHocMethod = StatisticsConfig.PostHocMethod.TUKEY;

        List<StatisticRow> rows = MetricStatisticsEngine.analyseMetric(
                "M", threeConditions(), threeGroupNormal(), cfg);

        assertEquals("One-way ANOVA", rows.get(0).test);
        // 3 pairwise rows, each tagged Tukey HSD.
        assertEquals(4, rows.size());
        for (int i = 1; i < rows.size(); i++) {
            assertEquals("Tukey HSD", rows.get(i).pairwiseTest);
            assertEquals("Tukey HSD", rows.get(i).postHocMethod);
            assertTrue("Tukey corrected p in [0, 1]",
                    rows.get(i).correctedPValue >= 0.0 && rows.get(i).correctedPValue <= 1.0);
            assertTrue("pairwise raw p left blank for Tukey",
                    Double.isNaN(rows.get(i).pairwisePValue));
        }
    }

    @Test
    public void dunnsPostHoc_runsOverPooledRanks() {
        StatisticsConfig cfg = new StatisticsConfig();
        cfg.postHocMethod = StatisticsConfig.PostHocMethod.DUNNS;
        cfg.distributionMode = StatisticsConfig.DistributionMode.ASSUME_SKEWED;

        List<StatisticRow> rows = MetricStatisticsEngine.analyseMetric(
                "M", threeConditions(), threeGroupNormal(), cfg);

        assertEquals("Kruskal-Wallis", rows.get(0).test);
        assertEquals(4, rows.size());
        for (int i = 1; i < rows.size(); i++) {
            assertEquals("Dunn's", rows.get(i).pairwiseTest);
            assertEquals("Dunn's", rows.get(i).postHocMethod);
            assertTrue("Dunn's corrected p in [0, 1]",
                    rows.get(i).correctedPValue >= 0.0 && rows.get(i).correctedPValue <= 1.0);
        }
    }

    @Test
    public void noneCorrection_emitsRawPairsAndBlanksCorrectedColumn() {
        StatisticsConfig cfg = new StatisticsConfig();
        cfg.postHocMethod = StatisticsConfig.PostHocMethod.NONE;

        List<StatisticRow> rows = MetricStatisticsEngine.analyseMetric(
                "M", threeConditions(), threeGroupNormal(), cfg);

        assertEquals("One-way ANOVA", rows.get(0).test);
        assertEquals(4, rows.size());
        for (int i = 1; i < rows.size(); i++) {
            assertEquals("Welch's t-test", rows.get(i).pairwiseTest);
            assertEquals("None", rows.get(i).postHocMethod);
            assertFalse("raw pairwise p must be present",
                    Double.isNaN(rows.get(i).pairwisePValue));
            assertTrue("corrected p must be blank under NONE",
                    Double.isNaN(rows.get(i).correctedPValue));
        }
    }
}
