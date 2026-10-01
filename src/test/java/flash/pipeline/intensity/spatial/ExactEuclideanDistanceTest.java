package flash.pipeline.intensity.spatial;

import org.junit.Test;
import java.util.Arrays;
import java.util.Random;
import static org.junit.Assert.*;

public class ExactEuclideanDistanceTest {
    @Test
    public void offAxisDistanceIsStraightLineRatherThanGridPath() {
        boolean[] mask = new boolean[12];
        boolean[] valid = new boolean[12];
        Arrays.fill(valid, true);
        mask[0] = true;
        double[] distance = ExactEuclideanDistance.toMask(mask, valid, 4, 3, 1, 1, 1, 1);
        assertEquals(Math.sqrt(5.0), distance[6], 1e-12);
        // A hole excludes measurements there, but does not bend Euclidean distance.
        valid[1] = false;
        valid[4] = false;
        valid[5] = false;
        distance = ExactEuclideanDistance.toMask(mask, valid, 4, 3, 1, 1, 1, 1);
        assertEquals(Math.sqrt(5.0), distance[6], 1e-12);
    }

    @Test
    public void anisotropicThreeDimensionalTransformMatchesIndependentNearestSeedSearch() {
        int width = 7, height = 5, depth = 4;
        boolean[] mask = new boolean[width * height * depth];
        boolean[] valid = new boolean[mask.length];
        Arrays.fill(valid, true);
        Random random = new Random(58);
        for (int i = 0; i < mask.length; i++) mask[i] = random.nextDouble() < 0.12;
        mask[0] = true;
        double sx = 0.3, sy = 0.7, sz = 2.5;
        double[] distances = ExactEuclideanDistance.toMask(mask, valid,
                width, height, depth, sx, sy, sz);
        for (int i = 0; i < mask.length; i++) {
            double expected = Double.POSITIVE_INFINITY;
            for (int j = 0; j < mask.length; j++) {
                if (!mask[j]) continue;
                double dx = (i % width - j % width) * sx;
                double dy = ((i / width) % height - (j / width) % height) * sy;
                double dz = (i / (width * height) - j / (width * height)) * sz;
                expected = Math.min(expected, Math.sqrt(dx * dx + dy * dy + dz * dz));
            }
            assertEquals("voxel " + i, expected, distances[i], 1e-12);
        }
    }

    @Test
    public void missingCalibrationCannotFabricatePhysicalDistance() {
        double[] distances = ExactEuclideanDistance.toMask(new boolean[]{true, false},
                new boolean[]{true, true}, 2, 1, 1, Double.NaN, 1, 1);
        assertTrue(Double.isNaN(distances[0]));
        assertTrue(Double.isNaN(distances[1]));
    }

    @Test
    public void noSeedsRetainsInfiniteDistance() {
        double[] distances = ExactEuclideanDistance.toMask(new boolean[2],
                new boolean[]{true, true}, 2, 1, 1, 1, 1, 1);
        assertEquals(Double.POSITIVE_INFINITY, distances[1], 0.0);
    }

    @Test
    public void invalidSeedCannotContributeDistanceButValidSeedRemainsZero() {
        double[] distances = ExactEuclideanDistance.toMask(new boolean[]{true, false, true},
                new boolean[]{false, true, true}, 3, 1, 1, 2, 3, 1);
        assertEquals(4.0, distances[0], 1e-12);
        assertEquals(2.0, distances[1], 1e-12);
        assertEquals(0.0, distances[2], 0.0);
    }

    @Test
    public void largeAndSmallFiniteScalesDoNotOverflowTheirSquares() {
        for (double scale : new double[]{1e200, 1e-200}) {
            double[] distances = ExactEuclideanDistance.toMask(
                    new boolean[]{true, false, false, false},
                    new boolean[]{true, true, true, true}, 2, 2, 1, scale, scale, 1);
            assertEquals(0.0, distances[0], 0.0);
            assertEquals(Math.sqrt(2.0), distances[3] / scale, 1e-12);
        }
    }

    @Test
    public void unrepresentableAxisRatioProducesMissingDistanceInsteadOfZero() {
        double[] distances = ExactEuclideanDistance.toMask(new boolean[]{true, false},
                new boolean[]{true, true}, 2, 1, 1, 1e-200, 1e200, 1);
        assertTrue(Double.isNaN(distances[0]));
        assertTrue(Double.isNaN(distances[1]));
    }
}
