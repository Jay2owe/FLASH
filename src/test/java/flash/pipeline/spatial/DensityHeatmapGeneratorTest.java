package flash.pipeline.spatial;

import ij.ImagePlus;
import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class DensityHeatmapGeneratorTest {

    private static final double TWO_PI = 2.0 * Math.PI;
    private static final double FLOAT_TOLERANCE = 2.0e-7;

    @Test
    public void unweightedBoundaryMatchesHandCalculationAndTranslatedCrop() {
        ImagePlus boundary = DensityHeatmapGenerator.generate(
                new double[][]{{0.0, 0.0}}, 2, 2, 1.0, 1.0);
        ImagePlus translated = DensityHeatmapGenerator.generate(
                new double[][]{{1.0, 1.0}}, 4, 4, 1.0, 1.0);
        assertNotNull(boundary);
        assertNotNull(translated);

        try {
            for (int y = 0; y < 2; y++) {
                for (int x = 0; x < 2; x++) {
                    double handCalculated = gaussian(x, y) / TWO_PI;
                    assertEquals(handCalculated, boundary.getProcessor().getf(x, y), FLOAT_TOLERANCE);
                    assertEquals("translating the point and crop together must preserve density",
                            boundary.getProcessor().getf(x, y),
                            translated.getProcessor().getf(x + 1, y + 1), 0.0);
                }
            }
        } finally {
            close(boundary);
            close(translated);
        }
    }

    @Test
    public void weightedBoundaryUsesAdmittedCountAndPreservesAmplitude() {
        double[][] points = new double[][]{{0.0, 0.0}, {-1.0, 0.0}};
        double[] weights = new double[]{1.0, 3.0};
        ImagePlus heatmap = DensityHeatmapGenerator.generateWeighted(
                points, weights, 2, 2, 1.0, 1.0);
        assertNotNull(heatmap);

        try {
            for (int y = 0; y < 2; y++) {
                for (int x = 0; x < 2; x++) {
                    double first = gaussian(x, y);
                    double clippedEdgeSample = gaussian(x + 1, y);
                    double handCalculated = (first + 3.0 * clippedEdgeSample) / (2.0 * TWO_PI);
                    assertEquals(handCalculated, heatmap.getProcessor().getf(x, y), FLOAT_TOLERANCE);
                }
            }
        } finally {
            close(heatmap);
        }
    }

    @Test
    public void unsupportedOffImagePointsLeaveExplicitBandwidthPixelsBitIdentical() {
        double[][] base = new double[][]{{0.0, 0.0}, {1.0, 1.0}};
        double[][] withOffImage = new double[][]{{0.0, 0.0}, {1.0, 1.0}, {5.0, 0.0}};

        assertHeatmapsBitIdentical(
                DensityHeatmapGenerator.generate(base, 2, 2, 1.0, 1.0),
                DensityHeatmapGenerator.generate(withOffImage, 2, 2, 1.0, 1.0));
        assertHeatmapsBitIdentical(
                DensityHeatmapGenerator.generateWeighted(
                        base, new double[]{1.0, 3.0}, 2, 2, 1.0, 1.0),
                DensityHeatmapGenerator.generateWeighted(
                        withOffImage, new double[]{1.0, 3.0, Double.MAX_VALUE}, 2, 2, 1.0, 1.0));
    }

    @Test
    public void multiplyingAdmittedWeightsMultipliesEveryWeightedPixel() {
        double[][] points = new double[][]{{0.0, 0.0}, {-1.0, 0.0}};
        ImagePlus base = DensityHeatmapGenerator.generateWeighted(
                points, new double[]{1.0, 3.0}, 2, 2, 1.0, 1.0);
        ImagePlus scaled = DensityHeatmapGenerator.generateWeighted(
                points, new double[]{10.0, 30.0}, 2, 2, 1.0, 1.0);
        assertNotNull(base);
        assertNotNull(scaled);

        try {
            for (int y = 0; y < 2; y++) {
                for (int x = 0; x < 2; x++) {
                    assertEquals(10.0 * base.getProcessor().getf(x, y),
                            scaled.getProcessor().getf(x, y), 2.0e-6);
                }
            }
        } finally {
            close(base);
            close(scaled);
        }
    }

    @Test
    public void unsupportedOffImagePointsCannotChangeAutomaticBandwidth() {
        double[][] base = new double[][]{{0.0, 0.0}, {1.0, 1.0}};
        double[][] withExtremeOffImage = new double[][]{
                {0.0, 0.0}, {1.0, 1.0}, {Double.MAX_VALUE, -Double.MAX_VALUE}
        };

        assertHeatmapsBitIdentical(
                DensityHeatmapGenerator.generate(base, 3, 3, 1.0, 0.0),
                DensityHeatmapGenerator.generate(withExtremeOffImage, 3, 3, 1.0, 0.0));
    }

    @Test
    public void allUnsupportedPointsReturnDocumentedEmptyResult() {
        double[][] points = new double[][]{
                {-4.0, 0.0}, {0.0, 5.0}, {Double.NaN, 0.0}
        };

        assertNull(DensityHeatmapGenerator.generate(points, 2, 2, 1.0, 1.0));
        assertNull(DensityHeatmapGenerator.generateWeighted(
                points, new double[]{1.0, 2.0, 3.0}, 2, 2, 1.0, 1.0));
    }

    @Test(timeout = 2000L)
    public void extremeFiniteCoordinatesAndHugeKernelRemainBoundedToImage() {
        assertNull(DensityHeatmapGenerator.generate(
                new double[][]{{Double.MAX_VALUE, Double.MAX_VALUE}},
                4, 3, Double.MIN_NORMAL, Double.MAX_VALUE));

        ImagePlus heatmap = DensityHeatmapGenerator.generate(
                new double[][]{{1.0, 1.0}}, 4, 3, 1.0, Double.MAX_VALUE);
        assertNotNull(heatmap);
        try {
            assertEquals(4, heatmap.getWidth());
            assertEquals(3, heatmap.getHeight());
            for (float value : (float[]) heatmap.getProcessor().getPixels()) {
                assertTrue(Float.isFinite(value));
                assertEquals(0.0, value, 0.0);
            }
        } finally {
            close(heatmap);
        }
    }

    private static double gaussian(double dx, double dy) {
        return Math.exp(-(dx * dx + dy * dy) / 2.0);
    }

    private static void assertHeatmapsBitIdentical(ImagePlus expected, ImagePlus actual) {
        assertNotNull(expected);
        assertNotNull(actual);
        try {
            assertEquals(expected.getWidth(), actual.getWidth());
            assertEquals(expected.getHeight(), actual.getHeight());
            assertArrayEquals((float[]) expected.getProcessor().getPixels(),
                    (float[]) actual.getProcessor().getPixels(), 0.0f);
        } finally {
            close(expected);
            close(actual);
        }
    }

    private static void close(ImagePlus image) {
        if (image == null) return;
        image.close();
        image.flush();
    }
}
