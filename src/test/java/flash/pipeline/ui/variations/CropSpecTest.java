package flash.pipeline.ui.variations;

import ij.ImagePlus;
import ij.ImageStack;
import ij.process.ShortProcessor;
import org.junit.Test;

import java.awt.Rectangle;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

public class CropSpecTest {

    @Test
    public void fullCropReturnsOriginalReference() {
        ImagePlus source = stack(32, 24, 3);

        assertSame(source, CropSpec.full().apply(source));
    }

    @Test
    public void centred256CropsAStackAroundTheImageCentre() {
        ImagePlus source = stack(1024, 1024, 10);

        ImagePlus cropped = CropSpec.centre256().apply(source);

        assertEquals(256, cropped.getWidth());
        assertEquals(256, cropped.getHeight());
        assertEquals(10, cropped.getStackSize());
        assertEquals(1, cropped.getNChannels());
        assertEquals(10, cropped.getNSlices());
        assertEquals(1, cropped.getNFrames());
        assertEquals(new Rectangle(384, 384, 256, 256),
                CropSpec.centre256().boundsFor(source));
        assertEquals(pixelValue(384, 384, 0),
                cropped.getStack().getProcessor(1).get(0, 0));
        assertEquals(pixelValue(639, 639, 9),
                cropped.getStack().getProcessor(10).get(255, 255));
    }

    private static ImagePlus stack(int width, int height, int slices) {
        ImageStack stack = new ImageStack(width, height);
        for (int z = 0; z < slices; z++) {
            ShortProcessor processor = new ShortProcessor(width, height);
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    processor.set(x, y, pixelValue(x, y, z));
                }
            }
            stack.addSlice("z" + (z + 1), processor);
        }
        ImagePlus image = new ImagePlus("stack", stack);
        image.setDimensions(1, slices, 1);
        return image;
    }

    private static int pixelValue(int x, int y, int z) {
        return x + y + z * 1000;
    }
}
