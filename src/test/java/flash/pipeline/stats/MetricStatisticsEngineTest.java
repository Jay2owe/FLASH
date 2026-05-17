package flash.pipeline.stats;

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
}
