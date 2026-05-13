package flash.pipeline.image.variation;

import ij.ImagePlus;
import ij.ImageStack;
import ij.process.ByteProcessor;
import ij.process.ColorProcessor;
import ij.process.FloatProcessor;
import ij.process.ShortProcessor;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class MemoryEstimatorTest {

    @Test
    public void computeSourceBytesAccountsForBitDepthAndStackSize() {
        assertEquals(40L, MemoryEstimator.computeSourceBytes(byteStack(5, 4, 2)));
        assertEquals(80L, MemoryEstimator.computeSourceBytes(shortStack(5, 4, 2)));
        assertEquals(160L, MemoryEstimator.computeSourceBytes(floatStack(5, 4, 2)));
        assertEquals(160L, MemoryEstimator.computeSourceBytes(rgbStack(5, 4, 2)));
    }

    @Test
    public void estimateUsesDeterministicProjectionAndBudgetFraction() {
        ImagePlus source = shortStack(10, 10, 2);

        MemoryEstimate estimate = MemoryEstimator.estimate(source, 5, 10000L);

        assertEquals(400L, estimate.sourceBytes);
        assertEquals(2600L, estimate.projectedBytes);
        assertEquals(10000L, estimate.maxHeap);
        assertEquals(0.26, estimate.headroomFraction, 0.000001);
        assertTrue(estimate.exceedsBudget);
        assertTrue(estimate.humanReadable.contains("ROI mode required"));
    }

    @Test
    public void estimateFitsAtBudgetThreshold() {
        ImagePlus source = byteStack(10, 10, 1);

        MemoryEstimate estimate = MemoryEstimator.estimate(source, 10, 5200L);

        assertEquals(1300L, estimate.projectedBytes);
        assertEquals(0.25, estimate.headroomFraction, 0.000001);
        assertFalse(estimate.exceedsBudget);
        assertTrue(estimate.humanReadable.contains("fits in budget"));
    }

    @Test
    public void estimateRejectsInvalidInputs() {
        try {
            MemoryEstimator.estimate(byteStack(1, 1, 1), 0, 100L);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("variantCount"));
        }

        try {
            MemoryEstimator.estimate(byteStack(1, 1, 1), 1, 0L);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("maxHeapBytes"));
        }
    }

    @Test
    public void nullSourceHasZeroBytesButStillProjects() {
        MemoryEstimate estimate = MemoryEstimator.estimate(null, 3, 100L);

        assertEquals(0L, estimate.sourceBytes);
        assertEquals(0L, estimate.projectedBytes);
        assertFalse(estimate.exceedsBudget);
    }

    private static ImagePlus byteStack(int width, int height, int slices) {
        ImageStack stack = new ImageStack(width, height);
        for (int i = 0; i < slices; i++) {
            stack.addSlice(new ByteProcessor(width, height));
        }
        return new ImagePlus("byte", stack);
    }

    private static ImagePlus shortStack(int width, int height, int slices) {
        ImageStack stack = new ImageStack(width, height);
        for (int i = 0; i < slices; i++) {
            stack.addSlice(new ShortProcessor(width, height));
        }
        return new ImagePlus("short", stack);
    }

    private static ImagePlus floatStack(int width, int height, int slices) {
        ImageStack stack = new ImageStack(width, height);
        for (int i = 0; i < slices; i++) {
            stack.addSlice(new FloatProcessor(width, height));
        }
        return new ImagePlus("float", stack);
    }

    private static ImagePlus rgbStack(int width, int height, int slices) {
        ImageStack stack = new ImageStack(width, height);
        for (int i = 0; i < slices; i++) {
            stack.addSlice(new ColorProcessor(width, height));
        }
        return new ImagePlus("rgb", stack);
    }
}
