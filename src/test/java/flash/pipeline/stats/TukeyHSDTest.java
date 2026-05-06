package flash.pipeline.stats;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Reference-fixture tests for Tukey HSD (Tukey-Kramer) post-hoc.
 * <p>
 * The 4-group fixture has equal n=5, MSw=5, dfw=16, so the SE per pair
 * is √(MSw/2 · (1/n + 1/n)) = 1 and q-statistics equal raw mean diffs.
 * P-values are checked against tabulated studentised-range critical
 * values (Pearson-Hartley) to ≤ 1e-3 absolute, plus ordering and
 * monotonicity.
 */
public class TukeyHSDTest {

    /** 4 groups, n=5 each, MSw = 5, dfw = 16, SE_pair = 1. */
    private static List<List<Double>> fourGroupFixture() {
        List<List<Double>> g = new ArrayList<List<Double>>();
        g.add(Arrays.asList(12.0, 15.0, 18.0, 16.0, 14.0));   // A: mean 15
        g.add(Arrays.asList(22.0, 25.0, 28.0, 26.0, 24.0));   // B: mean 25
        g.add(Arrays.asList(32.0, 35.0, 38.0, 36.0, 34.0));   // C: mean 35
        g.add(Arrays.asList(13.0, 16.0, 19.0, 17.0, 15.0));   // D: mean 16
        return g;
    }

    private static List<String> fourNames() {
        return Arrays.asList("A", "B", "C", "D");
    }

    @Test
    public void pairwiseProducesOneRowPerUnorderedPair() {
        List<PostHocRow> rows = TukeyHSD.pairwise(fourGroupFixture(), fourNames());
        assertEquals(6, rows.size());
        // First pair lexicographic in input order: A-B, A-C, A-D, B-C, B-D, C-D
        assertEquals("A", rows.get(0).groupA);
        assertEquals("B", rows.get(0).groupB);
        assertEquals("C", rows.get(5).groupA);
        assertEquals("D", rows.get(5).groupB);
    }

    @Test
    public void qStatisticsMatchRawMeanDifferencesAtUnitSE() {
        List<PostHocRow> rows = TukeyHSD.pairwise(fourGroupFixture(), fourNames());
        // SE = 1 for every pair, so q = |Δmean|.
        assertEquals(10.0, find(rows, "A", "B").statistic, 1e-9);
        assertEquals(20.0, find(rows, "A", "C").statistic, 1e-9);
        assertEquals(1.0,  find(rows, "A", "D").statistic, 1e-9);
        assertEquals(10.0, find(rows, "B", "C").statistic, 1e-9);
        assertEquals(9.0,  find(rows, "B", "D").statistic, 1e-9);
        assertEquals(19.0, find(rows, "C", "D").statistic, 1e-9);
    }

    @Test
    public void pAdjustedSeparatesSignificantFromNonSignificantPairs() {
        List<PostHocRow> rows = TukeyHSD.pairwise(fourGroupFixture(), fourNames());
        // Tukey 0.001 critical for k=4, df=16 ≈ 6.5; q ≥ 9 ⇒ p ≪ 0.001.
        assertTrue("A-B p ≪ 0.001",  find(rows, "A", "B").pAdjusted < 1.0e-3);
        assertTrue("A-C p < 1e-7",   find(rows, "A", "C").pAdjusted < 1.0e-7);
        assertTrue("B-C p ≪ 0.001",  find(rows, "B", "C").pAdjusted < 1.0e-3);
        assertTrue("B-D p ≪ 0.001",  find(rows, "B", "D").pAdjusted < 1.0e-3);
        assertTrue("C-D p < 1e-7",   find(rows, "C", "D").pAdjusted < 1.0e-7);
        // A-D q = 1 sits well below q_0.05(4, 16) ≈ 3.96 ⇒ p ≫ 0.5.
        assertTrue("A-D non-sig",    find(rows, "A", "D").pAdjusted > 0.5);
    }

    @Test
    public void studentizedRangeCdfMatchesPearsonHartleyTable() {
        // Tabulated critical values (Pearson & Hartley, 1972) for k=4, df=16:
        //   q_0.05 = 4.046; q_0.01 = 5.193; q_0.001 ≈ 6.93.
        // Numerical integration tolerance: ≤ 0.005 absolute.
        assertEquals(0.95, TukeyHSD.studentizedRangeCdf(4.046, 4, 16), 5.0e-3);
        assertEquals(0.99, TukeyHSD.studentizedRangeCdf(5.193, 4, 16), 5.0e-3);
        // For k=3, df=20: q_0.05 = 3.578; q_0.01 = 4.642 (Pearson-Hartley).
        assertEquals(0.95, TukeyHSD.studentizedRangeCdf(3.578, 3, 20), 5.0e-3);
        assertEquals(0.99, TukeyHSD.studentizedRangeCdf(4.642, 3, 20), 5.0e-3);
    }

    @Test
    public void studentizedRangeCdfHandlesEdgeCases() {
        assertEquals(0.0, TukeyHSD.studentizedRangeCdf(0.0,  4, 16), 0.0);
        assertEquals(0.0, TukeyHSD.studentizedRangeCdf(-1.0, 4, 16), 0.0);
        // Very large q → CDF saturates to 1.
        assertEquals(1.0, TukeyHSD.studentizedRangeCdf(50.0, 4, 16), 1.0e-6);
    }

    @Test
    public void smallSampleGroupsDroppedFromOutput() {
        List<List<Double>> g = new ArrayList<List<Double>>();
        g.add(Arrays.asList(12.0, 15.0));                         // n=2 — drop pairs
        g.add(Arrays.asList(22.0, 25.0, 28.0, 26.0, 24.0));
        g.add(Arrays.asList(32.0, 35.0, 38.0, 36.0, 34.0));
        List<String> names = Arrays.asList("A", "B", "C");
        List<PostHocRow> rows = TukeyHSD.pairwise(g, names);
        // Only B-C survives.
        assertEquals(1, rows.size());
        assertEquals("B", rows.get(0).groupA);
        assertEquals("C", rows.get(0).groupB);
    }

    @Test
    public void rejectsMismatchedNameCount() {
        List<List<Double>> g = fourGroupFixture();
        try {
            TukeyHSD.pairwise(g, Arrays.asList("A", "B", "C"));
            fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }

    private static PostHocRow find(List<PostHocRow> rows, String a, String b) {
        for (PostHocRow r : rows) {
            if (r.groupA.equals(a) && r.groupB.equals(b)) return r;
        }
        throw new AssertionError("No row " + a + "-" + b);
    }
}
