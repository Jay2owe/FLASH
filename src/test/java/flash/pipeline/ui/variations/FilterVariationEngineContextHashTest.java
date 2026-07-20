package flash.pipeline.ui.variations;

import ij.ImagePlus;
import ij.ImageStack;
import ij.process.ByteProcessor;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;

public class FilterVariationEngineContextHashTest {

    @Test
    public void sourceImageHashIncludesPixelContent() {
        ImagePlus a = stack("C1 (DAPI)", 16, 16, 3, 0);
        ImagePlus b = stack("C1 (DAPI)", 16, 16, 3, 128);
        ImagePlus sameAsA = stack("C1 (DAPI)", 16, 16, 3, 0);

        String hashA = FilterVariationEngineContext.sourceImageHash(a);
        String hashB = FilterVariationEngineContext.sourceImageHash(b);
        String hashSameAsA =
                FilterVariationEngineContext.sourceImageHash(sameAsA);

        assertFalse("Same title, dimensions, and stack size but different "
                        + "pixels must not share a source image hash",
                hashA.equals(hashB));
        assertEquals("Same title, dimensions, stack size, and pixels should "
                        + "keep a stable cache key",
                hashA, hashSameAsA);
    }

    @Test
    public void sourceImageHashIncludesPixelsAwayFromSamplePoints() {
        ImagePlus a = stack("C1 (DAPI)", 16, 16, 3, 0);
        ImagePlus b = stack("C1 (DAPI)", 16, 16, 3, 0);
        b.getStack().getProcessor(2).set(1, 14, 255);

        String hashA = FilterVariationEngineContext.sourceImageHash(a);
        String hashB = FilterVariationEngineContext.sourceImageHash(b);

        assertFalse("Changing an off-centre pixel must change the cache key",
                hashA.equals(hashB));
    }

    @Test
    public void sourceImageHashHandlesEmptyZeroDimStack() {
        ImagePlus empty = new ZeroDimImagePlus();

        String hash = FilterVariationEngineContext.sourceImageHash(empty);

        assertNotNull(hash);
        assertFalse(hash.isEmpty());
    }

    private static ImagePlus stack(String title, int width, int height,
                                   int slices, int value) {
        ImageStack stack = new ImageStack(width, height);
        for (int z = 0; z < slices; z++) {
            ByteProcessor processor = new ByteProcessor(width, height);
            processor.setValue(value);
            processor.fill();
            stack.addSlice("z" + (z + 1), processor);
        }
        ImagePlus image = new ImagePlus(title, stack);
        image.setDimensions(1, slices, 1);
        return image;
    }

    private static final class ZeroDimImagePlus extends ImagePlus {
        @Override public String getTitle() {
            return "empty";
        }

        @Override public int getWidth() {
            return 0;
        }

        @Override public int getHeight() {
            return 0;
        }

        @Override public int getStackSize() {
            return 0;
        }

        @Override public ImageStack getStack() {
            return new ImageStack(0, 0);
        }
    }
}
