package flash.pipeline.export;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ExcelMetricSummariserTest {

    private static final double TOL = 1e-3;

    @Test
    public void summariseComputesMeanAndSemToThreeDecimals() {
        ExcelMetricSummariser.Summary summary =
                ExcelMetricSummariser.summarise(2.0, 4.0, 4.0, 4.0, 5.0, 5.0, 7.0, 9.0);
        assertEquals(8, summary.count);
        assertEquals(5.0, summary.mean, TOL);
        // stddev sample = 2.138; SEM = stddev / sqrt(8) = 0.756
        assertEquals(0.756, summary.sem, TOL);
    }

    @Test
    public void summariseComputesMedianAndIqrToThreeDecimals() {
        ExcelMetricSummariser.Summary summary =
                ExcelMetricSummariser.summarise(1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0, 9.0);
        assertEquals(5.0, summary.median, TOL);
        // NumPy linear percentile: Q1 = 3.0, Q3 = 7.0
        assertEquals(3.0, summary.q1, TOL);
        assertEquals(7.0, summary.q3, TOL);
        assertEquals(4.0, summary.iqr(), TOL);
    }

    @Test
    public void summariseIgnoresNaNAndNullEntries() {
        ExcelMetricSummariser.Summary summary = ExcelMetricSummariser.summarise(
                Arrays.asList(Double.valueOf(1.0), null, Double.valueOf(Double.NaN),
                        Double.valueOf(2.0), Double.valueOf(3.0)));
        assertEquals(3, summary.count);
        assertEquals(2.0, summary.mean, TOL);
    }

    @Test
    public void summariseEmptyReturnsNaN() {
        ExcelMetricSummariser.Summary empty = ExcelMetricSummariser.summarise(Collections.<Double>emptyList());
        assertTrue(empty.isEmpty());
        assertEquals(0, empty.count);
        assertTrue(Double.isNaN(empty.mean));
        assertTrue(Double.isNaN(empty.median));
    }

    @Test
    public void summariseSingleValueHasZeroSemNaN() {
        ExcelMetricSummariser.Summary summary = ExcelMetricSummariser.summarise(42.0);
        assertEquals(1, summary.count);
        assertEquals(42.0, summary.mean, TOL);
        assertTrue(Double.isNaN(summary.sem));
        assertEquals(42.0, summary.median, TOL);
    }
}
