package flash.pipeline.image;

import ij.ImagePlus;
import ij.ImageStack;
import ij.process.ByteProcessor;
import ij.process.FloatProcessor;
import ij.process.ShortProcessor;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class ImageCalcOpsTest {

    @Test
    public void andStackThreadSafeUsesFirstStackAsMaskAndPreservesSixteenBitSignal() {
        ImageStack maskStack = new ImageStack(3, 1);
        maskStack.addSlice(new ByteProcessor(3, 1, new byte[]{0, (byte) 255, (byte) 255}, null));
        ImageStack signalStack = new ImageStack(3, 1);
        signalStack.addSlice(new ShortProcessor(3, 1, new short[]{1000, 2000, 3000}, null));

        ImagePlus masked = ImageCalcOps.andStackThreadSafe(
                new ImagePlus("mask", maskStack),
                new ImagePlus("signal", signalStack));

        assertEquals(0, masked.getStack().getProcessor(1).get(0));
        assertEquals(2000, masked.getStack().getProcessor(1).get(1));
        assertEquals(3000, masked.getStack().getProcessor(1).get(2));
    }

    @Test
    public void andStackThreadSafePreservesFloatSignalPrecision() {
        ImageStack maskStack = new ImageStack(3, 1);
        maskStack.addSlice(new ByteProcessor(3, 1, new byte[]{0, (byte) 255, 0}, null));
        ImageStack signalStack = new ImageStack(3, 1);
        signalStack.addSlice(new FloatProcessor(3, 1,
                new float[]{0.5f, 1.5f, 2.5f}, null));

        ImagePlus masked = ImageCalcOps.andStackThreadSafe(
                new ImagePlus("mask", maskStack),
                new ImagePlus("signal", signalStack));

        assertEquals(0.0, masked.getStack().getProcessor(1).getf(0), 0.0001);
        assertEquals(1.5, masked.getStack().getProcessor(1).getf(1), 0.0001);
        assertEquals(0.0, masked.getStack().getProcessor(1).getf(2), 0.0001);
    }

    @Test
    public void subtractStackThreadSafePreservesFloatPrecision() {
        ImageStack a = new ImageStack(3, 1);
        a.addSlice(new FloatProcessor(3, 1, new float[]{1.75f, 2.5f, 0.25f}, null));
        ImageStack b = new ImageStack(3, 1);
        b.addSlice(new FloatProcessor(3, 1, new float[]{0.5f, 1.25f, 1.0f}, null));

        ImagePlus subtracted = ImageCalcOps.subtractStackThreadSafe(
                new ImagePlus("a", a),
                new ImagePlus("b", b));

        assertEquals(1.25, subtracted.getStack().getProcessor(1).getf(0), 0.0001);
        assertEquals(1.25, subtracted.getStack().getProcessor(1).getf(1), 0.0001);
        assertEquals(0.0, subtracted.getStack().getProcessor(1).getf(2), 0.0001);
    }
}
