package flash.pipeline.spatial;

import org.junit.Test;

import static org.junit.Assert.*;

public class SpatialStatisticsTest {

    @Test
    public void computeGCurve_isBoundedMonotonicAndEndsAtOne() {
        double[][] points = new double[][]{
                {0.10, 0.10},
                {0.20, 0.10},
                {0.50, 0.50},
                {0.80, 0.80},
                {0.81, 0.80}
        };

        SpatialStatistics.Curve curve = SpatialStatistics.computeGCurve(points, 6);

        assertEquals(6, curve.radii.length);
        assertEquals(6, curve.values.length);
        for (int i = 0; i < curve.values.length; i++) {
            assertTrue(curve.values[i] >= 0.0);
            assertTrue(curve.values[i] <= 1.0);
            if (i > 0) {
                assertTrue(curve.radii[i] >= curve.radii[i - 1]);
                assertTrue(curve.values[i] >= curve.values[i - 1]);
            }
        }
        assertEquals(1.0, curve.values[curve.values.length - 1], 1e-9);
    }

    @Test
    public void computeRipleysK_regularGridShowsDispersionAtShortRange() {
        double[][] grid = new double[][]{
                {0.20, 0.20}, {0.20, 0.40}, {0.20, 0.60}, {0.20, 0.80},
                {0.40, 0.20}, {0.40, 0.40}, {0.40, 0.60}, {0.40, 0.80},
                {0.60, 0.20}, {0.60, 0.40}, {0.60, 0.60}, {0.60, 0.80},
                {0.80, 0.20}, {0.80, 0.40}, {0.80, 0.60}, {0.80, 0.80}
        };
        SpatialStatistics.RectangularWindow window =
                new SpatialStatistics.RectangularWindow(0.0, 0.0, 1.0, 1.0);
        double[] radii = new double[]{0.10};

        double[] k = SpatialStatistics.computeRipleysK(grid, window, radii);
        double[] csr = SpatialStatistics.csrExpectation(radii);

        assertEquals(1, k.length);
        assertFalse(Double.isNaN(k[0]));
        assertTrue(k[0] < csr[0]);
    }

    @Test
    public void computeRipleysK_clusteredPointsExceedCsrAtShortRange() {
        double[][] clustered = new double[][]{
                {0.20, 0.20}, {0.21, 0.20}, {0.20, 0.21},
                {0.70, 0.20}, {0.71, 0.20}, {0.70, 0.21},
                {0.70, 0.70}, {0.71, 0.70}, {0.70, 0.71}
        };
        SpatialStatistics.RectangularWindow window =
                new SpatialStatistics.RectangularWindow(0.0, 0.0, 1.0, 1.0);
        double[] radii = new double[]{0.03};

        double[] k = SpatialStatistics.computeRipleysK(clustered, window, radii);
        double[] csr = SpatialStatistics.csrExpectation(radii);

        assertEquals(1, k.length);
        assertFalse(Double.isNaN(k[0]));
        assertTrue(k[0] > csr[0]);
    }

    @Test
    public void computeLFunction_matchesCsrBaseline() {
        double[] radii = new double[]{0.05, 0.10, 0.20};
        double[] k = SpatialStatistics.csrExpectation(radii);

        double[] l = SpatialStatistics.computeLFunction(k, radii);

        assertEquals(radii.length, l.length);
        for (double value : l) {
            assertEquals(0.0, value, 1e-9);
        }
    }

    @Test
    public void monteCarloEnvelopes_areReproducibleAndOrdered() {
        SpatialStatistics.RectangularWindow window =
                new SpatialStatistics.RectangularWindow(0.0, 0.0, 1.0, 1.0);
        double[] radii = new double[]{0.05, 0.10, 0.15};

        SpatialStatistics.MonteCarloEnvelope first =
                SpatialStatistics.monteCarloEnvelopes(25, window, 20, radii, 1234L);
        SpatialStatistics.MonteCarloEnvelope second =
                SpatialStatistics.monteCarloEnvelopes(25, window, 20, radii, 1234L);

        assertArrayEquals(first.lower, second.lower, 0.0);
        assertArrayEquals(first.upper, second.upper, 0.0);
        for (int i = 0; i < radii.length; i++) {
            if (!Double.isNaN(first.lower[i]) && !Double.isNaN(first.upper[i])) {
                assertTrue(first.lower[i] <= first.upper[i]);
            }
        }
    }

    @Test
    public void heatmapSkipsNonFiniteCentroidsAndWeights() {
        double[][] centroids = new double[][]{
                {2.0, 2.0},
                {Double.NaN, 1.0},
                {1.0, Double.POSITIVE_INFINITY}
        };
        double[] weights = new double[]{1.0, 5.0, Double.POSITIVE_INFINITY};

        assertNotNull(DensityHeatmapGenerator.generate(centroids, 8, 8, 1.0, Double.POSITIVE_INFINITY));
        assertNotNull(DensityHeatmapGenerator.generateWeighted(centroids, weights, 8, 8, 1.0, Double.NaN));
    }

    @Test(expected = IllegalArgumentException.class)
    public void ripleysKRejectsNonFiniteCentroids() {
        SpatialStatistics.computeRipleysK(
                new double[][]{{0.0, 0.0}, {Double.POSITIVE_INFINITY, 1.0}},
                new SpatialStatistics.RectangularWindow(0.0, 0.0, 2.0, 2.0),
                new double[]{0.5});
    }
}
