package flash.pipeline.morphometry;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ObjectTextureGLCM3DTest {

    @Test
    public void uniformVolumeHasEnergyOneAndIsNotReliable() {
        ObjectPatch3D patch = fullPatch(8, 8, 4, new VoxelFunction() {
            @Override
            public float value(int x, int y, int z) {
                return 7.0f;
            }
        });

        ObjectTextureGLCM3D.Result result = ObjectTextureGLCM3D.compute(patch);

        assertTrue(result.valid);
        assertFalse(result.reliable);
        assertTrue(result.coOccurrencePairs >= 16);
        assertEquals(0.0, result.contrast, 1.0e-12);
        assertEquals(1.0, result.asm, 1.0e-12);
        assertEquals(0.0, result.entropy, 1.0e-12);
        assertEquals(1.0, result.homogeneity, 1.0e-12);
    }

    @Test
    public void checkerboardVolumeHasHighContrast() {
        ObjectPatch3D patch = fullPatch(10, 10, 5, new VoxelFunction() {
            @Override
            public float value(int x, int y, int z) {
                return ((x + y + z) & 1) == 0 ? 0.0f : 255.0f;
            }
        });

        ObjectTextureGLCM3D.Result result = ObjectTextureGLCM3D.compute(patch);

        assertTrue(result.valid);
        assertTrue(result.reliable);
        assertTrue("contrast=" + result.contrast, result.contrast > 300.0);
    }

    @Test
    public void zGradientHasHighCorrelation() {
        ObjectPatch3D patch = fullPatch(10, 10, 8, new VoxelFunction() {
            @Override
            public float value(int x, int y, int z) {
                return z;
            }
        });

        ObjectTextureGLCM3D.Result result = ObjectTextureGLCM3D.compute(patch);

        assertTrue(result.valid);
        assertTrue(result.reliable);
        assertTrue("correlation=" + result.correlation, result.correlation > 0.8);
    }

    @Test
    public void tinyMaskIsNotReliable() {
        int width = 4;
        int height = 4;
        int depth = 2;
        float[] intensity = new float[width * height * depth];
        byte[] mask = new byte[intensity.length];
        for (int i = 0; i < intensity.length; i++) intensity[i] = i;
        mask[0] = 1;
        mask[1] = 1;
        mask[width] = 1;
        mask[width + 1] = 1;

        ObjectTextureGLCM3D.Result result = ObjectTextureGLCM3D.compute(
                new ObjectPatch3D(intensity, mask, width, height, depth, 1.0, 1.0));

        assertFalse(result.valid);
        assertFalse(result.reliable);
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
