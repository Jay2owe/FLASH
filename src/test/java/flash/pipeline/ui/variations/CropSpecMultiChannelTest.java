package flash.pipeline.ui.variations;

import flash.pipeline.decontamination.CorrectionImageOps;
import ij.ImagePlus;
import ij.ImageStack;
import ij.process.ShortProcessor;
import org.junit.Test;

import java.awt.Rectangle;

import static org.junit.Assert.assertEquals;

public class CropSpecMultiChannelTest {

    /** 2 channels x 2 slices, 4x4. Value encodes channel/slice so we can verify indexing survives. */
    private static ImagePlus hyperstack() {
        ImageStack stack = new ImageStack(4, 4);
        // Hyperstack slice order for nC=2, nZ=2: (c1,z1),(c2,z1),(c1,z2),(c2,z2).
        stack.addSlice(constant(4, 4, 10)); // c1 z1
        stack.addSlice(constant(4, 4, 20)); // c2 z1
        stack.addSlice(constant(4, 4, 11)); // c1 z2
        stack.addSlice(constant(4, 4, 21)); // c2 z2
        ImagePlus image = new ImagePlus("hs", stack);
        image.setDimensions(2, 2, 1);
        return image;
    }

    private static ShortProcessor constant(int w, int h, int value) {
        short[] px = new short[w * h];
        for (int i = 0; i < px.length; i++) {
            px[i] = (short) value;
        }
        return new ShortProcessor(w, h, px, null);
    }

    @Test
    public void customCropPreservesChannelAndSliceDimensions() {
        ImagePlus cropped = CropSpec.custom(new Rectangle(1, 1, 2, 2)).applyMultiChannel(hyperstack());
        assertEquals(2, cropped.getNChannels());
        assertEquals(2, cropped.getNSlices());
        assertEquals(1, cropped.getNFrames());
        assertEquals(2, cropped.getWidth());
        assertEquals(2, cropped.getHeight());
        // Channel/slice values survive at the right (channel, plane) coordinates.
        assertEquals(10, CorrectionImageOps.channelPlanePixels(cropped, 0, 0)[0] & 0xffff);
        assertEquals(20, CorrectionImageOps.channelPlanePixels(cropped, 1, 0)[0] & 0xffff);
        assertEquals(11, CorrectionImageOps.channelPlanePixels(cropped, 0, 1)[0] & 0xffff);
        assertEquals(21, CorrectionImageOps.channelPlanePixels(cropped, 1, 1)[0] & 0xffff);
    }

    @Test
    public void fullModeReturnsSourceUnchanged() {
        ImagePlus source = hyperstack();
        assertEquals(source, CropSpec.full().applyMultiChannel(source));
    }
}
