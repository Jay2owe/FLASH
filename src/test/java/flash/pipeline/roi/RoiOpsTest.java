package flash.pipeline.roi;

import ij.ImagePlus;
import ij.ImageStack;
import ij.gui.Roi;
import ij.process.ByteProcessor;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class RoiOpsTest {

    @Test
    public void threadSafeCropRestoresSourceProcessorRoi() {
        ByteProcessor source = new ByteProcessor(4, 4);
        source.setRoi(0, 0, 3, 3);
        ImageStack stack = new ImageStack(4, 4);
        stack.addSlice(source);
        ImagePlus imp = new ImagePlus("source", stack);

        RoiOps.removeNonRoiThreadSafe(imp, new Roi(1, 1, 2, 2), null);

        assertEquals(2, imp.getWidth());
        assertEquals(2, imp.getHeight());
        assertEquals("source processor ROI must be restored", 3, source.getRoi().width);
        assertEquals("source processor ROI must be restored", 3, source.getRoi().height);
    }

    @Test
    public void threadSafeClearOutsideRestoresProcessorRoi() {
        ByteProcessor source = new ByteProcessor(4, 4);
        source.setRoi(0, 0, 3, 3);
        ImageStack stack = new ImageStack(4, 4);
        stack.addSlice(source);
        ImagePlus imp = new ImagePlus("source", stack);

        RoiOps.removeNonRoiThreadSafe(imp, null, new Roi(1, 1, 2, 2));

        assertEquals("processor ROI must be restored", 3, source.getRoi().width);
        assertEquals("processor ROI must be restored", 3, source.getRoi().height);
    }
}
