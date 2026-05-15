package flash.pipeline.image;

import ij.ImagePlus;
import ij.ImageStack;
import ij.gui.Roi;
import ij.process.ByteProcessor;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class ThreadSafeMeasureTest {

    @Test
    public void measureSliceDoesNotLeakRoiOntoSourceProcessor() {
        ImageStack stack = new ImageStack(2, 1);
        stack.addSlice(new ByteProcessor(2, 1, new byte[] {10, 20}, null));
        ImagePlus image = new ImagePlus("measure", stack);

        ThreadSafeMeasure.SliceResult roiResult =
                ThreadSafeMeasure.measureSlice(image, null, null, 1, new Roi(0, 0, 1, 1));
        ThreadSafeMeasure.SliceResult fullResult =
                ThreadSafeMeasure.measureSlice(image, null, null, 1, null);

        assertEquals(10.0, roiResult.intDenFilteredFullRoi, 0.0001);
        assertEquals(30.0, fullResult.intDenFilteredFullRoi, 0.0001);
    }
}
