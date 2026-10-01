package flash.pipeline.image;

import ij.ImagePlus;
import ij.ImageStack;
import ij.process.FloatProcessor;
import org.junit.Test;
import static org.junit.Assert.*;

public class ImageCalcGeometryTest {
    @Test(timeout = 10000) public void failedCalculationWaitsForWorkersBeforeInputsCanClose() {
        org.junit.Assume.assumeTrue(Runtime.getRuntime().availableProcessors() >= 2);
        for (boolean subtract : new boolean[]{false, true}) {
            java.util.concurrent.CountDownLatch started = new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.atomic.AtomicBoolean stopped = new java.util.concurrent.atomic.AtomicBoolean();
            FloatProcessor failing = new FloatProcessor(2, 2) {
                @Override public ij.process.ImageProcessor duplicate() {
                    try {
                        if (!started.await(3, java.util.concurrent.TimeUnit.SECONDS))
                            throw new AssertionError("Sibling worker did not start");
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    throw new IllegalStateException("Injected slice failure");
                }
            };
            FloatProcessor waiting = new FloatProcessor(2, 2) {
                @Override public ij.process.ImageProcessor duplicate() {
                    started.countDown();
                    try {
                        new java.util.concurrent.CountDownLatch(1).await();
                    } catch (InterruptedException expected) {
                        setf(0, 123);
                    } finally {
                        stopped.set(true);
                    }
                    return super.duplicate();
                }
            };
            ij.process.ImageProcessor[] processors = {failing, waiting,
                    new FloatProcessor(2, 2), new FloatProcessor(2, 2)};
            ImageStack special = new ImageStack(2, 2) {
                @Override public ij.process.ImageProcessor getProcessor(int index) {
                    return processors[index - 1];
                }
            };
            for (int i = 0; i < 4; i++) special.addSlice(new FloatProcessor(2, 2));
            ImagePlus signal = new ImagePlus("signal", special);
            ImagePlus other = stack(2, 2, 4);
            try {
                if (subtract) ImageCalcOps.subtractStackThreadSafe(signal, other);
                else ImageCalcOps.andStackThreadSafe(other, signal);
                fail("Worker failure was accepted");
            } catch (RuntimeException expected) {
                assertTrue("Sibling workers must stop before calculation returns", stopped.get());
                assertEquals(123, waiting.getf(0), 0);
            }
        }
    }

    @Test public void mismatchedPlanesOrShapeCannotSilentlyLoseData() {
        ImagePlus signal = stack(3, 2, 2);
        for (ImagePlus mask : new ImagePlus[]{stack(3, 2, 1), stack(2, 3, 2)}) {
            try { ImageCalcOps.andStackThreadSafe(mask, signal); fail("Invalid geometry accepted"); }
            catch (IllegalArgumentException expected) { }
            try { ImageCalcOps.subtractStackThreadSafe(signal, mask); fail("Invalid geometry accepted"); }
            catch (IllegalArgumentException expected) { }
        }
    }

    @Test public void frameAndChannelLayoutSurvivesBothOperations() {
        ImagePlus a = stack(3, 2, 8);
        ImagePlus b = stack(3, 2, 8);
        a.setDimensions(2, 2, 2);
        b.setDimensions(2, 2, 2);
        b.getCalibration().pixelWidth = 0.7;
        for (ImagePlus result : new ImagePlus[]{ImageCalcOps.andStackThreadSafe(a, b),
                ImageCalcOps.subtractStackThreadSafe(b, a)}) {
            assertEquals(2, result.getNChannels());
            assertEquals(2, result.getNSlices());
            assertEquals(2, result.getNFrames());
            assertEquals(0.7, result.getCalibration().pixelWidth, 0);
        }
    }

    private static ImagePlus stack(int width, int height, int planes) {
        ImageStack stack = new ImageStack(width, height);
        for (int i = 0; i < planes; i++) stack.addSlice(new FloatProcessor(width, height));
        return new ImagePlus("fixture", stack);
    }
}
