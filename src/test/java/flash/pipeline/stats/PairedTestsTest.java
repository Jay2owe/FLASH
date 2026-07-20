package flash.pipeline.stats;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Reference-fixture tests for paired statistics.
 */
public class PairedTestsTest {

    private static final double TOL_STAT = 1e-3;
    private static final double TOL_P = 1e-4;

    @Test
    public void pairedTTest_matchesSciPyReference() {
        List<Double> a = Arrays.asList(2.0, 4.0, 6.0, 8.0, 10.0);
        List<Double> b = Arrays.asList(3.0, 5.0, 5.0, 9.0, 12.0);

        double[] result = PairedTests.pairedTTest(a, b);

        assertEquals(-1.6330, result[0], TOL_STAT);
        assertEquals(0.1778, result[1], TOL_P);
    }

    @Test
    public void wilcoxonSignedRank_matchesSciPyReference() {
        List<Double> a = Arrays.asList(2.0, 4.0, 6.0, 8.0, 10.0);
        List<Double> b = Arrays.asList(3.0, 5.0, 5.0, 9.0, 12.0);

        double[] result = PairedTests.wilcoxonSignedRank(a, b);

        assertEquals(2.5, result[0], TOL_STAT);
        assertEquals(0.3125, result[1], TOL_P);
    }

    @Test
    public void repeatedMeasuresAnova_matchesRReference() {
        List<List<Double>> groups = fixtureGroups();

        double[] result = PairedTests.repeatedMeasuresAnova(groups);

        assertEquals(18.0, result[0], TOL_STAT);
        assertEquals(0.0029, result[1], TOL_P);
    }

    @Test
    public void friedman_matchesRReference() {
        List<List<Double>> groups = fixtureGroups();

        double[] result = PairedTests.friedman(groups);

        assertEquals(8.0, result[0], TOL_STAT);
        assertEquals(0.0183, result[1], TOL_P);
    }

    @Test
    public void pairedTests_dropInvalidPairsSymmetrically() {
        List<Double> a = Arrays.asList(2.0, Double.NaN, 6.0, 8.0, Double.POSITIVE_INFINITY, 10.0);
        List<Double> b = Arrays.asList(3.0, 5.0, 5.0, Double.NEGATIVE_INFINITY, 12.0, 12.0);

        double[] result = PairedTests.pairedTTest(a, b);
        double[] expected = PairedTests.pairedTTest(
                Arrays.asList(2.0, 6.0, 10.0),
                Arrays.asList(3.0, 5.0, 12.0));

        assertEquals(expected[0], result[0], 1e-10);
        assertEquals(expected[1], result[1], 1e-10);
    }

    @Test(expected = IllegalArgumentException.class)
    public void pairedTTest_rejectsSizeMismatch() {
        PairedTests.pairedTTest(Arrays.asList(1.0, 2.0), Arrays.asList(1.0));
    }

    @Test(expected = IllegalArgumentException.class)
    public void pairedTTest_rejectsNullSample() {
        PairedTests.pairedTTest(null, Arrays.asList(1.0));
    }

    @Test(expected = IllegalArgumentException.class)
    public void repeatedMeasuresAnova_rejectsUnbalancedGroups() {
        List<List<Double>> groups = new ArrayList<List<Double>>();
        groups.add(Arrays.asList(1.0, 2.0));
        groups.add(Arrays.asList(1.0, 2.0, 3.0));

        PairedTests.repeatedMeasuresAnova(groups);
    }

    private static List<List<Double>> fixtureGroups() {
        List<List<Double>> groups = new ArrayList<List<Double>>();
        groups.add(Arrays.asList(10.0, 17.0, 5.0, 17.0));
        groups.add(Arrays.asList(22.0, 24.0, 10.0, 21.0));
        groups.add(Arrays.asList(27.0, 29.0, 15.0, 22.0));
        return groups;
    }
}
