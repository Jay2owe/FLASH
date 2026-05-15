package flash.pipeline.morphometry;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ObjectFractalTest {

    @Test
    public void filledSquareHasDimensionNearTwo() {
        ObjectPatch patch = maskPatch(32, 32, new MaskFunction() {
            @Override
            public boolean object(int x, int y) {
                return true;
            }
        });

        ObjectFractal.Result result = ObjectFractal.compute(patch);

        assertTrue(result.valid);
        assertTrue(result.reliable);
        assertEquals(2.0, result.fractalDim, 0.05);
        assertTrue(result.fractalDim_R2 > 0.99);
        assertTrue(Double.isFinite(result.lacunarityMean));
        assertTrue(Double.isFinite(result.lacunaritySpread));
    }

    @Test
    public void diagonalLineHasDimensionNearOne() {
        ObjectPatch patch = maskPatch(32, 32, new MaskFunction() {
            @Override
            public boolean object(int x, int y) {
                return x == y;
            }
        });

        ObjectFractal.Result result = ObjectFractal.compute(patch);

        assertTrue(result.valid);
        assertTrue(result.reliable);
        assertEquals(1.0, result.fractalDim, 0.15);
    }

    @Test
    public void smallMaskIsInvalid() {
        ObjectPatch patch = maskPatch(4, 4, new MaskFunction() {
            @Override
            public boolean object(int x, int y) {
                return true;
            }
        });

        ObjectFractal.Result result = ObjectFractal.compute(patch);

        assertFalse(result.valid);
        assertFalse(result.reliable);
    }

    @Test
    public void sierpinskiTriangleHasCanonicalDimension() {
        ObjectPatch patch = maskPatch(64, 64, new MaskFunction() {
            @Override
            public boolean object(int x, int y) {
                return (x & y) == 0;
            }
        });

        ObjectFractal.Result result = ObjectFractal.compute(patch);

        assertTrue(result.valid);
        assertTrue(result.reliable);
        assertEquals(Math.log(3.0) / Math.log(2.0), result.fractalDim, 0.08);
    }

    private static ObjectPatch maskPatch(int width, int height, MaskFunction function) {
        float[] intensity = new float[width * height];
        byte[] mask = new byte[width * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int i = y * width + x;
                intensity[i] = 1.0f;
                mask[i] = function.object(x, y) ? (byte) 1 : (byte) 0;
            }
        }
        return new ObjectPatch(intensity, mask, width, height, 1.0);
    }

    private interface MaskFunction {
        boolean object(int x, int y);
    }
}
