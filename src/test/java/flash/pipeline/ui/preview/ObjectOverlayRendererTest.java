package flash.pipeline.ui.preview;

import ij.ImagePlus;
import ij.process.ByteProcessor;
import ij.process.ImageProcessor;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class ObjectOverlayRendererTest {

    @Test
    public void objectOverlayUsesLightAlphaAndKeepsBackgroundVisible() {
        ByteProcessor sourceProcessor = new ByteProcessor(2, 1);
        sourceProcessor.set(0, 0, 0);
        sourceProcessor.set(1, 0, 255);
        ImagePlus source = new ImagePlus("source", sourceProcessor);

        ByteProcessor labelProcessor = new ByteProcessor(2, 1);
        labelProcessor.set(0, 0, 1);
        labelProcessor.set(1, 0, 0);
        ImagePlus labels = new ImagePlus("labels", labelProcessor);

        ImagePlus overlay = ObjectOverlayRenderer.renderOverlay(source, labels);
        ImageProcessor rendered = overlay.getProcessor();

        assertEquals(blend(0x000000, LabelMapStyler.rgbForLabel(1), 0.35),
                rendered.getPixel(0, 0) & 0xffffff);
        assertEquals(0xffffff, rendered.getPixel(1, 0) & 0xffffff);
    }

    @Test
    public void objectOverlayAppliesDisplayRangeOnlyToSourceBackground() {
        ByteProcessor sourceProcessor = new ByteProcessor(2, 1);
        sourceProcessor.set(0, 0, 100);
        sourceProcessor.set(1, 0, 100);
        ImagePlus source = new ImagePlus("source", sourceProcessor);

        ByteProcessor labelProcessor = new ByteProcessor(2, 1);
        labelProcessor.set(0, 0, 1);
        labelProcessor.set(1, 0, 0);
        ImagePlus labels = new ImagePlus("labels", labelProcessor);

        ImagePlus overlay = ObjectOverlayRenderer.renderOverlay(
                source,
                labels,
                PreviewDisplaySettings.of(100.0, 200.0,
                        PreviewDisplaySettings.LutMode.GREY, "Grays"));
        ImageProcessor rendered = overlay.getProcessor();

        assertEquals(blend(0x000000, LabelMapStyler.rgbForLabel(1), 0.35),
                rendered.getPixel(0, 0) & 0xffffff);
        assertEquals(0x000000, rendered.getPixel(1, 0) & 0xffffff);
    }

    private static int blend(int base, int overlay, double alpha) {
        int br = (base >> 16) & 0xff;
        int bg = (base >> 8) & 0xff;
        int bb = base & 0xff;
        int or = (overlay >> 16) & 0xff;
        int og = (overlay >> 8) & 0xff;
        int ob = overlay & 0xff;
        int r = (int) Math.round(br * (1.0 - alpha) + or * alpha);
        int g = (int) Math.round(bg * (1.0 - alpha) + og * alpha);
        int b = (int) Math.round(bb * (1.0 - alpha) + ob * alpha);
        return (r << 16) | (g << 8) | b;
    }
}
