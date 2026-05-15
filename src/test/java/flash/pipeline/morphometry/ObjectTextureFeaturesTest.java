package flash.pipeline.morphometry;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileWriter;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class ObjectTextureFeaturesTest {
    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void uniformPatchHasNearZeroGaborAndWaveletFeatures() {
        ObjectPatch patch = fullPatch(32, 32, new PixelFunction() {
            @Override
            public float value(int x, int y) {
                return 5.0f;
            }
        });

        ObjectTextureFeatures.FeatureVector vector = ObjectTextureFeatures.computeFeatures(patch);

        assertTrue(vector.valid);
        assertTrue(vector.reliable);
        assertEquals(ObjectTextureFeatures.DEFAULT_FEATURE_DIM, vector.features.length);
        for (int i = 0; i < vector.features.length; i++) {
            assertEquals(0.0, vector.features[i], 1.0e-7);
        }
    }

    @Test
    public void horizontalBandsHaveStrongNinetyDegreeGaborResponse() {
        ObjectPatch patch = fullPatch(64, 64, new PixelFunction() {
            @Override
            public float value(int x, int y) {
                return ((y / 8) & 1) == 0 ? 1.0f : -1.0f;
            }
        });

        ObjectTextureFeatures.FeatureVector vector = ObjectTextureFeatures.computeFeatures(patch);

        assertTrue(vector.valid);
        assertTrue(vector.reliable);
        float response90 = vector.features[2];
        assertTrue("response90=" + response90 + ", response0=" + vector.features[0],
                response90 > vector.features[0] * 1.5f);
        assertTrue("response90=" + response90 + ", response45=" + vector.features[1],
                response90 > vector.features[1] * 1.2f);
        assertTrue("response90=" + response90 + ", response135=" + vector.features[3],
                response90 > vector.features[3] * 1.2f);
    }

    @Test
    public void pixelCheckerboardPeaksAtFinestWaveletScale() {
        ObjectPatch patch = fullPatch(64, 64, new PixelFunction() {
            @Override
            public float value(int x, int y) {
                return ((x + y) & 1) == 0 ? 1.0f : -1.0f;
            }
        });

        ObjectTextureFeatures.FeatureVector vector = ObjectTextureFeatures.computeFeatures(patch);

        assertTrue(vector.features[4] > vector.features[5]);
        assertTrue(vector.features[4] > vector.features[6]);
        assertTrue(vector.features[4] > vector.features[7]);
    }

    @Test
    public void smallPatchIsValidButNotReliable() {
        ObjectPatch patch = fullPatch(8, 8, new PixelFunction() {
            @Override
            public float value(int x, int y) {
                return x;
            }
        });

        ObjectTextureFeatures.FeatureVector vector = ObjectTextureFeatures.computeFeatures(patch);

        assertTrue(vector.valid);
        assertTrue(!vector.reliable);
    }

    @Test
    public void centroidSaveLoadRoundTripPreservesValues() throws Exception {
        double[][] centroids = {
                {1.0, 2.0, 3.5, 4.0, 5.0, 6.0, 7.0, 8.0},
                {-1.25, 0.0, 1.0e-3, 4.0, 5.5, 6.5, 7.5, 8.5}
        };
        File file = temp.newFile("centroids.txt");

        ObjectTextureFeatures.CentroidsIO.save(file, centroids);
        double[][] loaded = ObjectTextureFeatures.CentroidsIO.load(file, 8);

        assertNotNull(loaded);
        assertEquals(centroids.length, loaded.length);
        for (int i = 0; i < centroids.length; i++) {
            assertArrayEquals(centroids[i], loaded[i], 1.0e-9);
        }
    }

    @Test
    public void centroidLoadReturnsNullForDimensionMismatch() throws Exception {
        File file = temp.newFile("bad-centroids.txt");
        FileWriter writer = new FileWriter(file);
        try {
            writer.write("1 2 3\n");
        } finally {
            writer.close();
        }

        assertNull(ObjectTextureFeatures.CentroidsIO.load(file, 8));
    }

    @Test
    public void kMeansUsesFixedSeedForDeterministicCentroids() {
        List<ObjectTextureFeatures.FeatureVector> vectors = Arrays.asList(
                vector(0.0f), vector(0.1f), vector(0.2f), vector(0.3f),
                vector(10.0f), vector(10.1f), vector(10.2f), vector(10.3f));

        double[][] first = ObjectTextureFeatures.fitCentroids(vectors, 2);
        double[][] second = ObjectTextureFeatures.fitCentroids(vectors, 2);

        assertEquals(2, first.length);
        assertEquals(2, second.length);
        for (int i = 0; i < first.length; i++) {
            assertArrayEquals(first[i], second[i], 0.0);
        }

        ObjectTextureFeatures.ClassAssignment assignment =
                ObjectTextureFeatures.assignToCentroids(vector(10.25f), first);
        assertTrue(assignment.classLabel >= 0 && assignment.classLabel < 2);
        assertTrue(assignment.classDistance < 1.0);
    }

    private static ObjectTextureFeatures.FeatureVector vector(float base) {
        float[] features = new float[ObjectTextureFeatures.DEFAULT_FEATURE_DIM];
        for (int i = 0; i < features.length; i++) {
            features[i] = base + i * 0.01f;
        }
        return new ObjectTextureFeatures.FeatureVector(features, true, true);
    }

    private static ObjectPatch fullPatch(int width, int height, PixelFunction function) {
        float[] intensity = new float[width * height];
        byte[] mask = new byte[width * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int i = y * width + x;
                intensity[i] = function.value(x, y);
                mask[i] = 1;
            }
        }
        return new ObjectPatch(intensity, mask, width, height, 1.0);
    }

    private interface PixelFunction {
        float value(int x, int y);
    }
}
