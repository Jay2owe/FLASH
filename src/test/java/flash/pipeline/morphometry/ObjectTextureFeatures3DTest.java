package flash.pipeline.morphometry;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class ObjectTextureFeatures3DTest {
    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void xOrientedPatternHasStrongXAxisGaborResponse() {
        ObjectPatch3D patch = fullPatch(16, 16, 8, new VoxelFunction() {
            @Override
            public float value(int x, int y, int z) {
                return (float) Math.cos(2.0 * Math.PI * x / 8.0);
            }
        });

        ObjectTextureFeatures3D.FeatureVector vector = ObjectTextureFeatures3D.computeFeatures(patch);

        assertTrue(vector.valid);
        assertTrue(vector.reliable);
        assertEquals(ObjectTextureFeatures3D.DEFAULT_FEATURE_DIM, vector.features.length);
        assertTrue("x=" + vector.features[0] + ", y=" + vector.features[1],
                vector.features[0] > vector.features[1] * 1.2f);
        assertTrue("x=" + vector.features[0] + ", z=" + vector.features[2],
                vector.features[0] > vector.features[2] * 1.2f);
    }

    @Test
    public void threeDimensionalCheckerboardPeaksAtFinestWaveletScale() {
        ObjectPatch3D patch = fullPatch(16, 16, 8, new VoxelFunction() {
            @Override
            public float value(int x, int y, int z) {
                return ((x + y + z) & 1) == 0 ? 1.0f : -1.0f;
            }
        });

        ObjectTextureFeatures3D.FeatureVector vector = ObjectTextureFeatures3D.computeFeatures(patch);

        assertTrue(vector.features[4] > vector.features[5]);
        assertTrue(vector.features[4] > vector.features[6]);
        assertTrue(vector.features[4] > vector.features[7]);
    }

    @Test
    public void centroidSaveLoadRoundTripPreservesValues() throws Exception {
        double[][] centroids = {
                {1.0, 2.0, 3.5, 4.0, 5.0, 6.0, 7.0, 8.0},
                {-1.25, 0.0, 1.0e-3, 4.0, 5.5, 6.5, 7.5, 8.5}
        };
        File file = temp.newFile("centroids3d.txt");

        ObjectTextureFeatures3D.CentroidsIO.save(file, centroids);
        double[][] loaded = ObjectTextureFeatures3D.CentroidsIO.load(file, 8);

        assertNotNull(loaded);
        assertEquals(centroids.length, loaded.length);
        for (int i = 0; i < centroids.length; i++) {
            assertArrayEquals(centroids[i], loaded[i], 1.0e-9);
        }
    }

    @Test
    public void kMeansUsesFixedSeedForDeterministicCentroids() {
        List<ObjectTextureFeatures3D.FeatureVector> vectors = Arrays.asList(
                vector(0.0f), vector(0.1f), vector(0.2f), vector(0.3f),
                vector(10.0f), vector(10.1f), vector(10.2f), vector(10.3f));

        double[][] first = ObjectTextureFeatures3D.fitCentroids(vectors, 2);
        double[][] second = ObjectTextureFeatures3D.fitCentroids(vectors, 2);

        assertEquals(2, first.length);
        assertEquals(2, second.length);
        for (int i = 0; i < first.length; i++) {
            assertArrayEquals(first[i], second[i], 0.0);
        }

        ObjectTextureFeatures3D.ClassAssignment assignment =
                ObjectTextureFeatures3D.assignToCentroids(vector(10.25f), first);
        assertTrue(assignment.classLabel >= 0 && assignment.classLabel < 2);
        assertTrue(assignment.classDistance < 1.0);
    }

    private static ObjectTextureFeatures3D.FeatureVector vector(float base) {
        float[] features = new float[ObjectTextureFeatures3D.DEFAULT_FEATURE_DIM];
        for (int i = 0; i < features.length; i++) {
            features[i] = base + i * 0.01f;
        }
        return new ObjectTextureFeatures3D.FeatureVector(features, true, true);
    }

    private static ObjectPatch3D fullPatch(int width, int height, int depth, VoxelFunction function) {
        float[] intensity = new float[width * height * depth];
        byte[] mask = new byte[intensity.length];
        int slice = width * height;
        for (int z = 0; z < depth; z++) {
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int i = z * slice + y * width + x;
                    intensity[i] = function.value(x, y, z);
                    mask[i] = 1;
                }
            }
        }
        return new ObjectPatch3D(intensity, mask, width, height, depth, 1.0, 1.0);
    }

    private interface VoxelFunction {
        float value(int x, int y, int z);
    }
}
