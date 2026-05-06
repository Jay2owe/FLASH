package flash.pipeline.analyses;

import ij.ImagePlus;
import ij.ImageStack;
import ij.measure.ResultsTable;
import ij.process.ByteProcessor;
import ij.process.FloatProcessor;
import ij.process.ShortProcessor;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class IntensityMaskingTest {

    @Test
    public void channelRoiMaskAndsMeasurementPixels() {
        ImageStack maskStack = new ImageStack(2, 1);
        maskStack.addSlice(new ByteProcessor(2, 1, new byte[]{(byte) 255, 0}, null));
        ImageStack measurementStack = new ImageStack(2, 1);
        measurementStack.addSlice(new ByteProcessor(2, 1, new byte[]{10, 20}, null));

        ImagePlus masked = IntensityAnalysisV2.applyRoiChannelMask(
                new ImagePlus("mask", maskStack),
                new ImagePlus("measurement", measurementStack));

        assertEquals(10, masked.getStack().getProcessor(1).get(0, 0));
        assertEquals(0, masked.getStack().getProcessor(1).get(1, 0));
    }

    @Test
    public void channelRoiMaskPreservesSixteenBitMeasurementPixels() {
        ImageStack maskStack = new ImageStack(2, 1);
        maskStack.addSlice(new ByteProcessor(2, 1, new byte[]{(byte) 255, 0}, null));
        ImageStack measurementStack = new ImageStack(2, 1);
        measurementStack.addSlice(new ShortProcessor(2, 1, new short[]{1000, 2000}, null));

        ImagePlus masked = IntensityAnalysisV2.applyRoiChannelMask(
                new ImagePlus("mask", maskStack),
                new ImagePlus("measurement", measurementStack));

        assertEquals(1000, masked.getStack().getProcessor(1).get(0, 0));
        assertEquals(0, masked.getStack().getProcessor(1).get(1, 0));
    }

    @Test
    public void createRoiChannelMaskUsesExplicitThreshold() {
        ImageStack sourceStack = new ImageStack(3, 1);
        sourceStack.addSlice(new ByteProcessor(3, 1, new byte[]{5, 15, 25}, null));

        ImagePlus mask = IntensityAnalysisV2.createRoiChannelMask(
                new ImagePlus("source", sourceStack), null, false, 15.0, false);

        assertEquals(0, mask.getStack().getProcessor(1).get(0, 0));
        assertEquals(255, mask.getStack().getProcessor(1).get(1, 0));
        assertEquals(255, mask.getStack().getProcessor(1).get(2, 0));
    }

    @Test
    public void createRoiChannelMaskUsesFractionalThresholdOnFloatImages() {
        ImageStack sourceStack = new ImageStack(3, 1);
        sourceStack.addSlice(new FloatProcessor(3, 1,
                new float[]{0.25f, 0.75f, 1.25f}, null));

        ImagePlus mask = IntensityAnalysisV2.createRoiChannelMask(
                new ImagePlus("source", sourceStack), null, false, 0.8, false);

        assertEquals(0, mask.getStack().getProcessor(1).get(0, 0));
        assertEquals(0, mask.getStack().getProcessor(1).get(1, 0));
        assertEquals(255, mask.getStack().getProcessor(1).get(2, 0));
    }

    @Test
    public void writeMeasurementColumnsEmitsIntDenAreaAndRawIntDen() {
        ResultsTable table = new ResultsTable();
        table.incrementCounter();

        IntensityAnalysisV2.writeMeasurementColumns(table, 0, 123.0, 67.0, 456.0);

        assertEquals(123.0, table.getValue("IntDen", 0), 0.0001);
        assertEquals(67.0, table.getValue("%Area", 0), 0.0001);
        assertEquals(456.0, table.getValue("RawIntDen", 0), 0.0001);
    }
}
