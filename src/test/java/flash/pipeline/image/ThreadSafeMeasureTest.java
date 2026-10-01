package flash.pipeline.image;

import ij.ImagePlus;
import ij.ImageStack;
import ij.gui.Roi;
import ij.measure.Calibration;
import ij.process.ByteProcessor;
import org.junit.Test;
import java.util.concurrent.CancellationException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ThreadSafeMeasureTest {

    @Test(expected = IllegalArgumentException.class)
    public void rejectsTimeSeriesInsteadOfMeasuringOnlyFirstFrame() {
        ImageStack stack = new ImageStack(1, 1);
        for (int i = 0; i < 4; i++) stack.addSlice(new ByteProcessor(1, 1));
        ImagePlus image = new ImagePlus("time-series", stack);
        image.setDimensions(1, 2, 2);
        ThreadSafeMeasure.measureAllSlices(image, image, null, null);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsDifferentGeometryBeforeMeasurement() {
        ThreadSafeMeasure.measureAllSlices(new ImagePlus("filtered", new ByteProcessor(2, 1)),
                new ImagePlus("raw", new ByteProcessor(1, 2)), null, null);
    }

    @Test public void cancellationPreservesInterruptAndDoesNotReturnMeasurements() {
        Thread.currentThread().interrupt();
        try {
            ThreadSafeMeasure.measureAllSlices(new ImagePlus("filtered", new ByteProcessor(1, 1)),
                    null, null, null);
            org.junit.Assert.fail("Interrupted measurement must cancel");
        } catch (CancellationException expected) {
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    @Test public void nestedMeasurementMatchesKnownPixelSumForEverySlice() {
        ImageStack stack = new ImageStack(2, 1);
        for (int i = 1; i <= 4; i++) stack.addSlice(new ByteProcessor(2, 1,
                new byte[]{(byte) i, (byte) (2 * i)}, null));
        ImagePlus image = new ImagePlus("nested", stack);
        ParallelContext.enterParallel();
        try {
            ThreadSafeMeasure.SliceResult[] result = ThreadSafeMeasure.measureAllSlices(image, image, null, null);
            assertEquals(4, result.length);
            for (int i = 0; i < 4; i++) assertEquals(3 * (i + 1), result[i].intDenFilteredFullRoi, 1e-9);
        } finally {
            ParallelContext.exitParallel();
        }
    }

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

    @Test
    public void measureSliceNormalizesInfiniteStatisticsToNaN() {
        ImageStack stack = new ImageStack(1, 1);
        stack.addSlice(new ByteProcessor(1, 1, new byte[] {1}, null));
        ImagePlus image = new ImagePlus("measure-inf", stack);
        Calibration calibration = new Calibration();
        calibration.pixelWidth = Double.POSITIVE_INFINITY;
        calibration.pixelHeight = 1.0;
        image.setCalibration(calibration);

        ThreadSafeMeasure.SliceResult result =
                ThreadSafeMeasure.measureSlice(image, null, null, 1, null);

        assertTrue(Double.isNaN(result.intDenFilteredFullRoi));
    }
}
