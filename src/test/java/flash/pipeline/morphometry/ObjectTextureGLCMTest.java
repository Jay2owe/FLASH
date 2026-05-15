package flash.pipeline.morphometry;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ObjectTextureGLCMTest {

    @Test
    public void uniformPatchHasEnergyOneAndNoContrast() {
        ObjectPatch patch = fullPatch(32, 32, new PixelFunction() {
            @Override
            public float value(int x, int y) {
                return 7.0f;
            }
        });

        ObjectTextureGLCM.Result result = ObjectTextureGLCM.compute(patch);

        assertTrue(result.valid);
        assertFalse(result.reliable);
        assertTrue(result.coOccurrencePairs >= 16);
        assertEquals(0.0, result.contrast, 1.0e-12);
        assertEquals(1.0, result.asm, 1.0e-12);
        assertEquals(0.0, result.entropy, 1.0e-12);
        assertEquals(1.0, result.homogeneity, 1.0e-12);
    }

    @Test
    public void checkerboardHasHighContrastAndLowerHomogeneity() {
        ObjectPatch patch = fullPatch(32, 32, new PixelFunction() {
            @Override
            public float value(int x, int y) {
                return ((x + y) & 1) == 0 ? 0.0f : 255.0f;
            }
        });

        ObjectTextureGLCM.Result result = ObjectTextureGLCM.compute(patch);

        assertTrue(result.valid);
        assertTrue(result.reliable);
        assertTrue("contrast=" + result.contrast, result.contrast > 300.0);
        assertTrue("homogeneity=" + result.homogeneity, result.homogeneity < 0.7);
    }

    @Test
    public void linearGradientHasHighCorrelation() {
        ObjectPatch patch = fullPatch(32, 32, new PixelFunction() {
            @Override
            public float value(int x, int y) {
                return x;
            }
        });

        ObjectTextureGLCM.Result result = ObjectTextureGLCM.compute(patch);

        assertTrue(result.valid);
        assertTrue(result.reliable);
        assertTrue("correlation=" + result.correlation, result.correlation > 0.9);
    }

    @Test
    public void tinyMaskIsNotReliable() {
        int width = 8;
        int height = 8;
        float[] intensity = new float[width * height];
        byte[] mask = new byte[width * height];
        mask[1 * width + 1] = 1;
        mask[1 * width + 2] = 1;
        mask[2 * width + 1] = 1;
        mask[2 * width + 2] = 1;
        for (int i = 0; i < intensity.length; i++) intensity[i] = i;

        ObjectTextureGLCM.Result result =
                ObjectTextureGLCM.compute(new ObjectPatch(intensity, mask, width, height, 1.0));

        assertFalse(result.valid);
        assertFalse(result.reliable);
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
