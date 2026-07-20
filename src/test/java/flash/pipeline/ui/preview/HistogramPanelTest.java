package flash.pipeline.ui.preview;

import ij.ImagePlus;
import ij.process.ByteProcessor;
import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

public class HistogramPanelTest {

    @Test
    public void calculatesBinsAcrossProcessorRange() {
        ByteProcessor processor = new ByteProcessor(4, 1);
        processor.set(0, 0, 0);
        processor.set(1, 0, 1);
        processor.set(2, 0, 2);
        processor.set(3, 0, 3);

        HistogramPanel.Histogram histogram = HistogramPanel.calculateHistogram(processor, 4);

        assertEquals(0.0, histogram.getMinimum(), 0.0001);
        assertEquals(3.0, histogram.getMaximum(), 0.0001);
        assertEquals(4, histogram.getFinitePixelCount());
        assertArrayEquals(new int[]{1, 1, 1, 1}, histogram.getBins());
    }

    @Test
    public void constantImageUsesSafeOneUnitDomain() {
        ByteProcessor processor = new ByteProcessor(2, 1);
        processor.set(0, 0, 7);
        processor.set(1, 0, 7);

        HistogramPanel.Histogram histogram = HistogramPanel.calculateHistogram(processor, 4);

        assertEquals(7.0, histogram.getMinimum(), 0.0001);
        assertEquals(8.0, histogram.getMaximum(), 0.0001);
        assertArrayEquals(new int[]{2, 0, 0, 0}, histogram.getBins());
    }

    @Test
    public void panelCanBeConstructedAndPopulatedHeadlessly() {
        HistogramPanel panel = new HistogramPanel();
        panel.setImage(new ImagePlus("histogram", byteProcessor(0, 5, 10)));

        assertFalse(panel.histogramForTest().isEmpty());
        assertEquals(3, panel.histogramForTest().getFinitePixelCount());
    }

    private static ByteProcessor byteProcessor(int... values) {
        ByteProcessor processor = new ByteProcessor(values.length, 1);
        for (int i = 0; i < values.length; i++) {
            processor.set(i, 0, values[i]);
        }
        return processor;
    }
}
