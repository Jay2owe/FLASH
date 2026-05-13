package flash.pipeline.analyses;

import flash.pipeline.image.ImageCalcOps;
import flash.pipeline.image.ThreadSafeMeasure;
import ij.ImagePlus;
import ij.ImageStack;
import ij.measure.ResultsTable;
import ij.process.ByteProcessor;
import ij.process.FloatProcessor;
import ij.process.ShortProcessor;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class IntensityMaskingTest {
    private static final String LEGACY_RAW_INT_DEN = "Raw" + "IntDen";
    private static final String FORBIDDEN_UNFILTERED_BINARIZED = "IntDen_Unfiltered" + "_binarized";

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
    public void writeMeasurementColumnsWithoutBinarizationEmitsRawFirstBaseSchema() {
        ResultsTable table = new ResultsTable();
        table.incrementCounter();

        IntensityAnalysisV2.writeMeasurementColumns(table, 0,
                123.0, 67.0, 456.0, false, Double.NaN, Double.NaN);

        assertEquals(123.0, table.getValue("IntDen", 0), 0.0001);
        assertEquals(67.0, table.getValue("%Area", 0), 0.0001);
        assertEquals(456.0, table.getValue("IntDen_Unfiltered", 0), 0.0001);
        assertTrue(table.getColumnIndex("IntDen_binarized") < 0);
        assertTrue(table.getColumnIndex("%Area_binarized") < 0);
        assertTrue(table.getColumnIndex(LEGACY_RAW_INT_DEN) < 0);
        assertTrue(table.getColumnIndex(FORBIDDEN_UNFILTERED_BINARIZED) < 0);
    }

    @Test
    public void writeMeasurementColumnsWithBinarizationEmitsAdditiveBinarizedPartners() {
        ResultsTable table = new ResultsTable();
        table.incrementCounter();

        IntensityAnalysisV2.writeMeasurementColumns(table, 0,
                123.0, 67.0, 456.0, true, 89.0, 12.0);

        assertEquals(123.0, table.getValue("IntDen", 0), 0.0001);
        assertEquals(89.0, table.getValue("IntDen_binarized", 0), 0.0001);
        assertEquals(67.0, table.getValue("%Area", 0), 0.0001);
        assertEquals(12.0, table.getValue("%Area_binarized", 0), 0.0001);
        assertEquals(456.0, table.getValue("IntDen_Unfiltered", 0), 0.0001);
        assertTrue(table.getColumnIndex(LEGACY_RAW_INT_DEN) < 0);
        assertTrue(table.getColumnIndex(FORBIDDEN_UNFILTERED_BINARIZED) < 0);
    }

    @Test
    public void sliceMeasurementKeepsFilteredValueAndMapsOldBinarizedIntDenToPartner() {
        ImageStack filteredStack = new ImageStack(3, 1);
        filteredStack.addSlice(new FloatProcessor(3, 1, new float[]{5.0f, 20.0f, 30.0f}, null));
        ImagePlus filtered = new ImagePlus("filtered", filteredStack);

        ImageStack rawStack = new ImageStack(3, 1);
        rawStack.addSlice(new FloatProcessor(3, 1, new float[]{10.0f, 100.0f, 200.0f}, null));
        ImagePlus raw = new ImagePlus("raw", rawStack);

        ImageStack binaryStack = new ImageStack(3, 1);
        binaryStack.addSlice(new ByteProcessor(3, 1, new byte[]{0, (byte) 255, (byte) 255}, null));
        ImagePlus binarizedRawInMask = ImageCalcOps.andStackThreadSafe(
                new ImagePlus("binary", binaryStack), raw);

        ThreadSafeMeasure.SliceResult result =
                ThreadSafeMeasure.measureAllSlices(filtered, raw, binarizedRawInMask, null)[0];

        assertEquals(55.0, result.intDenFilteredFullRoi, 0.0001);
        assertEquals(100.0, result.areaFractionFilteredFullRoi, 0.0001);
        assertEquals(310.0, result.intDenUnfilteredFullRoi, 0.0001);
        assertEquals(300.0, result.intDenBinarizedRawInMask, 0.0001);
        assertEquals(66.6667, result.areaFractionBinarized, 0.0001);
        assertTrue(result.hasBinarizedMeasurement);
    }
}
