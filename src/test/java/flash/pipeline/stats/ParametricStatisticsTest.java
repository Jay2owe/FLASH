package flash.pipeline.stats;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Regression tests for parametric statistics with frozen reference values
 * from SciPy 1.12.
 *
 * Reference values verified by manual calculation with sample variance (n-1):
 * <pre>
 * # Welch's t-test: [1,2,3,4,5] vs [2,4,6,8,10]
 *   m1=3, m2=6, v1=2.5, v2=10, se=sqrt(2.5/5+10/5)=1.5811
 *   t = -3/1.5811 = -1.8974, df=5.6 (Welch-Satterthwaite)
 *
 * # One-way ANOVA: [1..5], [2,4,6,8,10], [3,6,9,12,15]
 *   Grand mean=6, F=3.8571, df=(2,12)
 *
 * # Welch's t-test: [10,12,14,16,18] vs [30,32,34,36,38]
 *   m1=14, m2=34, v1=10, v2=10, se=2, t=-10.0, df=8
 * </pre>
 */
public class ParametricStatisticsTest {

    private static final double TOL_STAT = 1e-3;
    private static final double TOL_P = 1e-4;

    // ---- Welch's t-test regression ----

    @Test
    public void welchTTest_matchesSciPyReference() {
        List<Double> a = Arrays.asList(1.0, 2.0, 3.0, 4.0, 5.0);
        List<Double> b = Arrays.asList(2.0, 4.0, 6.0, 8.0, 10.0);

        double[] result = ParametricStatistics.welchTTest(a, b);
        double t = result[0];
        double p = result[1];

        // Manual: t = (3-6)/sqrt(2.5/5 + 10/5) = -1.8974
        assertEquals(-1.8974, t, TOL_STAT);
        assertEquals(0.1075, p, TOL_P);
    }

    @Test
    public void welchTTest_highSeparation_matchesSciPy() {
        List<Double> a = Arrays.asList(10.0, 12.0, 14.0, 16.0, 18.0);
        List<Double> b = Arrays.asList(30.0, 32.0, 34.0, 36.0, 38.0);

        double[] result = ParametricStatistics.welchTTest(a, b);
        double t = result[0];
        double p = result[1];

        // Manual: t = (14-34)/sqrt(10/5 + 10/5) = -20/2 = -10.0
        assertEquals(-10.0, t, TOL_STAT);
        assertTrue("p should be very small", p < 0.0001);
    }

    @Test
    public void welchTTest_identicalGroups_returnsNeutral() {
        List<Double> a = Arrays.asList(5.0, 5.0, 5.0);
        List<Double> b = Arrays.asList(5.0, 5.0, 5.0);

        double[] result = ParametricStatistics.welchTTest(a, b);

        assertEquals(0.0, result[0], 1e-10);
        assertEquals(1.0, result[1], 1e-10);
    }

    @Test
    public void welchTTest_degenerateInputsReturnNeutral() {
        assertArrayEquals(new double[]{0.0, 1.0},
                ParametricStatistics.welchTTest(new ArrayList<Double>(), Arrays.asList(1.0, 2.0)),
                1e-10);
        assertArrayEquals(new double[]{0.0, 1.0},
                ParametricStatistics.welchTTest(null, Arrays.asList(1.0, 2.0)),
                1e-10);
    }

    // ---- One-way ANOVA regression ----

    @Test
    public void oneWayAnova_matchesSciPyReference() {
        List<List<Double>> groups = new ArrayList<List<Double>>();
        groups.add(Arrays.asList(1.0, 2.0, 3.0, 4.0, 5.0));
        groups.add(Arrays.asList(2.0, 4.0, 6.0, 8.0, 10.0));
        groups.add(Arrays.asList(3.0, 6.0, 9.0, 12.0, 15.0));

        double[] result = ParametricStatistics.oneWayAnova(groups);
        double F = result[0];
        double p = result[1];

        // Grand mean=6, SSB=90, SSW=140, F=90/2 / (140/12) = 3.8571
        assertEquals(3.8571, F, TOL_STAT);
        assertEquals(0.0509, p, TOL_P);
    }

    @Test
    public void oneWayAnova_identicalGroups_returnsNeutral() {
        List<List<Double>> groups = new ArrayList<List<Double>>();
        groups.add(Arrays.asList(5.0, 5.0, 5.0));
        groups.add(Arrays.asList(5.0, 5.0, 5.0));
        groups.add(Arrays.asList(5.0, 5.0, 5.0));

        double[] result = ParametricStatistics.oneWayAnova(groups);

        // F=0 (no between-group variance), p=1.0 (degenerate guard)
        assertEquals(0.0, result[0], 1e-10);
        assertEquals(1.0, result[1], 1e-10);
    }

    @Test
    public void oneWayAnova_zeroWithinVarianceButDifferentMeans() {
        // Each group is constant but groups differ — should not throw
        List<List<Double>> groups = new ArrayList<List<Double>>();
        groups.add(Arrays.asList(1.0, 1.0, 1.0));
        groups.add(Arrays.asList(2.0, 2.0, 2.0));
        groups.add(Arrays.asList(3.0, 3.0, 3.0));

        double[] result = ParametricStatistics.oneWayAnova(groups);

        // ssWithin == 0 triggers the guard -> [0, 1]
        assertEquals(0.0, result[0], 1e-10);
        assertEquals(1.0, result[1], 1e-10);
    }

    @Test
    public void oneWayAnova_degenerateGroupsReturnNeutral() {
        List<List<Double>> groups = new ArrayList<List<Double>>();
        groups.add(Arrays.asList(1.0, 2.0));
        groups.add(new ArrayList<Double>());

        assertArrayEquals(new double[]{0.0, 1.0},
                ParametricStatistics.oneWayAnova(groups), 1e-10);
        assertArrayEquals(new double[]{0.0, 1.0},
                ParametricStatistics.oneWayAnova(null), 1e-10);
    }
}
