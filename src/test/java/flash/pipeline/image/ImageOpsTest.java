package flash.pipeline.image;

import ij.ImagePlus;
import ij.ImageStack;
import ij.process.ByteProcessor;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class ImageOpsTest {

    @Test
    public void duplicateThreadSafeIgnoresExistingProcessorRoi() {
        ByteProcessor bp = new ByteProcessor(4, 4);
        bp.set(0, 0, 11);
        bp.set(3, 3, 99);
        bp.setRoi(1, 1, 2, 2);
        ImageStack stack = new ImageStack(4, 4);
        stack.addSlice(bp);

        ImagePlus duplicate = ImageOps.duplicateThreadSafe(new ImagePlus("source", stack));

        assertEquals(4, duplicate.getWidth());
        assertEquals(4, duplicate.getHeight());
        assertEquals(11, duplicate.getProcessor().get(0, 0));
        assertEquals(99, duplicate.getProcessor().get(3, 3));
        assertEquals("source ROI must be restored", 2, bp.getRoi().width);
    }
}
