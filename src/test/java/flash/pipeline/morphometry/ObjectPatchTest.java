package flash.pipeline.morphometry;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class ObjectPatchTest {

    @Test
    public void acceptsRowMajorArraysAndReportsIndices() {
        float[] intensity = {1.0f, 2.0f, 3.0f, 4.0f, 5.0f, 6.0f};
        byte[] mask = {1, 0, 1, 0, 1, 1};

        ObjectPatch patch = new ObjectPatch(intensity, mask, 3, 2, 0.5);

        assertSame(intensity, patch.intensity);
        assertSame(mask, patch.mask);
        assertEquals(3, patch.width);
        assertEquals(2, patch.height);
        assertEquals(0.5, patch.pixelSize_um, 0.0);
        assertEquals(4, patch.index(1, 1));
        assertTrue(patch.containsObjectPixel(0, 0));
        assertFalse(patch.containsObjectPixel(1, 0));
        assertEquals(4, patch.objectPixelCount());
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsWrongIntensityLength() {
        new ObjectPatch(new float[5], new byte[6], 3, 2, 1.0);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsWrongMaskLength() {
        new ObjectPatch(new float[6], new byte[5], 3, 2, 1.0);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsNonPositiveWidth() {
        new ObjectPatch(new float[1], new byte[1], 0, 1, 1.0);
    }

    @Test(expected = IndexOutOfBoundsException.class)
    public void indexRejectsOutOfBoundsCoordinates() {
        ObjectPatch patch = new ObjectPatch(new float[4], new byte[4], 2, 2, 1.0);
        patch.index(2, 0);
    }
}
