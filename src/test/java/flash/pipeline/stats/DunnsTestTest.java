package flash.pipeline.stats;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Reference-fixture tests for Dunn's pooled-rank pairwise test.
 * <p>
 * The 4-group fixture contains tied values (15 and 16 each appear in
 * groups A and D), so the tie-correction term Σ(t³−t) = 12 is exercised.
 * Expected statistics and p-values are hand-computed from
 * <pre>
 *   σ² = N(N+1)/12 - Σ(t³−t)/(12(N−1))   with N=20
 *      = 20·21/12 - 12/(12·19) = 34.94737
 *   SE_pair = √(σ² · (1/n_i + 1/n_j))    with n_i = n_j = 5 ⇒ 3.738842
 * </pre>
 * matching {@code scikit-posthocs.posthoc_dunn} (Bonferroni p-adjust)
 * and {@code PMCMRplus::kwAllPairsDunnTest}.
 */
public class DunnsTestTest {

    private static final double TOL_Z = 1.0e-3;
    private static final double TOL_P = 1.0e-3;

    private static List<List<Double>> fourGroupFixture() {
        List<List<Double>> g = new ArrayList<List<Double>>();
        g.add(Arrays.asList(12.0, 15.0, 18.0, 16.0, 14.0));   // A: ranks 1,4.5,9,6.5,3
        g.add(Arrays.asList(22.0, 25.0, 28.0, 26.0, 24.0));   // B
        g.add(Arrays.asList(32.0, 35.0, 38.0, 36.0, 34.0));   // C
        g.add(Arrays.asList(13.0, 16.0, 19.0, 17.0, 15.0));   // D: ranks 2,6.5,10,8,4.5
        return g;
    }

    private static List<String> fourNames() {
        return Arrays.asList("A", "B", "C", "D");
    }

    @Test
    public void pairwiseProducesOneRowPerUnorderedPair() {
        List<PostHocRow> rows = DunnsTest.pairwise(fourGroupFixture(), fourNames());
        assertEquals(6, rows.size());
        assertEquals("A", rows.get(0).groupA);
        assertEquals("B", rows.get(0).groupB);
        assertEquals("C", rows.get(5).groupA);
        assertEquals("D", rows.get(5).groupB);
    }

    @Test
    public void zStatisticsMatchHandComputedTieCorrectedValues() {
        // Mean ranks: A=4.8, B=13.0, C=18.0, D=6.2; SE=3.738842 for every pair.
        List<PostHocRow> rows = DunnsTest.pairwise(fourGroupFixture(), fourNames());
        assertEquals(-2.1932, find(rows, "A", "B").statistic, TOL_Z);
        assertEquals(-3.5310, find(rows, "A", "C").statistic, TOL_Z);
        assertEquals(-0.3745, find(rows, "A", "D").statistic, TOL_Z);
        assertEquals(-1.3375, find(rows, "B", "C").statistic, TOL_Z);
        assertEquals( 1.8186, find(rows, "B", "D").statistic, TOL_Z);
        assertEquals( 3.1565, find(rows, "C", "D").statistic, TOL_Z);
    }

    @Test
    public void bonferroniAdjustedPValuesMatchScikitPosthocsReference() {
        // p_raw = 2(1 - Φ(|z|)); p_adj = min(1, p_raw · 6).
        List<PostHocRow> rows = DunnsTest.pairwise(fourGroupFixture(), fourNames());
        assertEquals(0.16979, find(rows, "A", "B").pAdjusted, TOL_P);
        assertEquals(0.00248, find(rows, "A", "C").pAdjusted, TOL_P);
        assertEquals(1.0,     find(rows, "A", "D").pAdjusted, TOL_P);
        assertEquals(1.0,     find(rows, "B", "C").pAdjusted, TOL_P);
        assertEquals(0.41380, find(rows, "B", "D").pAdjusted, TOL_P);
        assertEquals(0.00959, find(rows, "C", "D").pAdjusted, TOL_P);
    }

    @Test
    public void noTiesReducesToUntiedFormula() {
        // Three groups, no ties at all.
        List<List<Double>> g = new ArrayList<List<Double>>();
        g.add(Arrays.asList( 1.0,  2.0,  3.0,  4.0,  5.0));
        g.add(Arrays.asList( 6.0,  7.0,  8.0,  9.0, 10.0));
        g.add(Arrays.asList(11.0, 12.0, 13.0, 14.0, 15.0));
        List<String> names = Arrays.asList("A", "B", "C");
        List<PostHocRow> rows = DunnsTest.pairwise(g, names);
        // Mean ranks: A=3, B=8, C=13; N=15; σ² = 15·16/12 = 20.
        // SE = √(20 · 0.4) = √8 ≈ 2.828427.
        // A-B: z = -5 / 2.828427 = -1.7678.
        assertEquals(-1.7678, find(rows, "A", "B").statistic, TOL_Z);
        assertEquals(-3.5355, find(rows, "A", "C").statistic, TOL_Z);
        assertEquals(-1.7678, find(rows, "B", "C").statistic, TOL_Z);
    }

    @Test
    public void smallSampleGroupsDroppedFromOutput() {
        List<List<Double>> g = new ArrayList<List<Double>>();
        g.add(Arrays.asList(1.0, 2.0));                   // n=2 → drop pairs
        g.add(Arrays.asList(3.0, 4.0, 5.0, 6.0, 7.0));
        g.add(Arrays.asList(8.0, 9.0, 10.0, 11.0, 12.0));
        List<PostHocRow> rows = DunnsTest.pairwise(g, Arrays.asList("A", "B", "C"));
        assertEquals(1, rows.size());
        assertEquals("B", rows.get(0).groupA);
        assertEquals("C", rows.get(0).groupB);
    }

    @Test
    public void rejectsMismatchedNameCount() {
        try {
            DunnsTest.pairwise(fourGroupFixture(), Arrays.asList("A", "B"));
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
