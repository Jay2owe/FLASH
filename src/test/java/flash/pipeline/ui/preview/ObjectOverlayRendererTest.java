package flash.pipeline.ui.preview;

import flash.pipeline.ui.Debouncer;
import ij.ImagePlus;
import ij.measure.ResultsTable;
import ij.process.ByteProcessor;
import ij.process.ImageProcessor;
import ij.process.ShortProcessor;
import org.junit.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

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

    @Test
    public void filteredLabelMapHidesRemovedLabelsAsBlack() {
        ByteProcessor labelProcessor = new ByteProcessor(3, 1);
        labelProcessor.set(0, 0, 1);
        labelProcessor.set(1, 0, 2);
        labelProcessor.set(2, 0, 0);
        ImagePlus labels = new ImagePlus("labels", labelProcessor);
        Set<Integer> removed = new HashSet<Integer>();
        removed.add(Integer.valueOf(2));

        ImagePlus renderedImage = ObjectOverlayRenderer.renderFiltered(
                null, labels, removed, false, null);
        ImageProcessor rendered = renderedImage.getProcessor();

        assertEquals(LabelMapStyler.rgbForLabel(1),
                rendered.getPixel(0, 0) & 0xffffff);
        assertEquals(0x000000, rendered.getPixel(1, 0) & 0xffffff);
        assertEquals(0x000000, rendered.getPixel(2, 0) & 0xffffff);
    }

    @Test
    public void filteredLabelMapShowsRemovedLabelsAsGreyGhosts() {
        ByteProcessor labelProcessor = new ByteProcessor(2, 1);
        labelProcessor.set(0, 0, 1);
        labelProcessor.set(1, 0, 2);
        ImagePlus labels = new ImagePlus("labels", labelProcessor);
        Set<Integer> removed = new HashSet<Integer>();
        removed.add(Integer.valueOf(2));

        ImagePlus renderedImage = ObjectOverlayRenderer.renderFiltered(
                null, labels, removed, true, null);
        ImageProcessor rendered = renderedImage.getProcessor();

        assertEquals(LabelMapStyler.rgbForLabel(1),
                rendered.getPixel(0, 0) & 0xffffff);
        assertEquals(0x808080, rendered.getPixel(1, 0) & 0xffffff);
    }

    @Test
    public void filteredOverlayHidesRemovedLabelsAsSourceGreyscale() {
        ByteProcessor sourceProcessor = new ByteProcessor(1, 1);
        sourceProcessor.set(0, 0, 200);
        ImagePlus source = new ImagePlus("source", sourceProcessor);

        ByteProcessor labelProcessor = new ByteProcessor(1, 1);
        labelProcessor.set(0, 0, 1);
        ImagePlus labels = new ImagePlus("labels", labelProcessor);
        Set<Integer> removed = new HashSet<Integer>();
        removed.add(Integer.valueOf(1));

        ImagePlus overlay = ObjectOverlayRenderer.renderFiltered(
                source, labels, removed, false,
                PreviewDisplaySettings.of(0.0, 255.0,
                        PreviewDisplaySettings.LutMode.GREY, "Grays"));
        ImageProcessor rendered = overlay.getProcessor();

        assertEquals(0xc8c8c8, rendered.getPixel(0, 0) & 0xffffff);
    }

    @Test
    public void filteredOverlayShowsRemovedLabelsAsGreyGhostsOverSource() {
        ByteProcessor sourceProcessor = new ByteProcessor(1, 1);
        sourceProcessor.set(0, 0, 200);
        ImagePlus source = new ImagePlus("source", sourceProcessor);

        ByteProcessor labelProcessor = new ByteProcessor(1, 1);
        labelProcessor.set(0, 0, 1);
        ImagePlus labels = new ImagePlus("labels", labelProcessor);
        Set<Integer> removed = new HashSet<Integer>();
        removed.add(Integer.valueOf(1));

        ImagePlus overlay = ObjectOverlayRenderer.renderFiltered(
                source, labels, removed, true,
                PreviewDisplaySettings.of(0.0, 255.0,
                        PreviewDisplaySettings.LutMode.GREY, "Grays"));
        ImageProcessor rendered = overlay.getProcessor();

        assertEquals(blend(0xc8c8c8, 0x808080, 0.35),
                rendered.getPixel(0, 0) & 0xffffff);
    }

    @Test
    public void debouncerFlushesOnlyPendingTrigger() {
        final AtomicInteger calls = new AtomicInteger();
        Debouncer debouncer = new Debouncer(100, new Runnable() {
            @Override
            public void run() {
                calls.incrementAndGet();
            }
        });

        debouncer.trigger();
        debouncer.cancel();
        debouncer.flushNow();

        assertEquals(0, calls.get());

        debouncer.trigger();
        debouncer.flushNow();

        assertEquals(1, calls.get());
    }

    @Test
    public void filteredLabelMapDoesNotCollideAboveTwoHundredFiftyFiveLabels() {
        ImagePlus labels = labelMapOneToTwoThousand();
        Set<Integer> removed = new HashSet<Integer>();
        removed.add(Integer.valueOf(260));

        ImagePlus hidden = ObjectOverlayRenderer.renderFiltered(
                null, labels, removed, false, null);
        ImageProcessor hiddenProcessor = hidden.getProcessor();

        assertEquals(LabelMapStyler.rgbForLabel(5),
                hiddenProcessor.getPixel(4, 0) & 0xffffff);
        assertEquals(0x000000, hiddenProcessor.getPixel(259, 0) & 0xffffff);
        assertEquals(LabelMapStyler.rgbForLabel(515),
                hiddenProcessor.getPixel(514, 0) & 0xffffff);

        ImagePlus ghosts = ObjectOverlayRenderer.renderFiltered(
                null, labels, removed, true, null);
        ImageProcessor ghostProcessor = ghosts.getProcessor();

        assertEquals(0x808080, ghostProcessor.getPixel(259, 0) & 0xffffff);
        assertEquals(LabelMapStyler.rgbForLabel(515),
                ghostProcessor.getPixel(514, 0) & 0xffffff);
    }

    @Test
    public void filteredOverlayDoesNotCollideAboveTwoHundredFiftyFiveLabels() {
        ImagePlus source = new ImagePlus("source", new ByteProcessor(2000, 1));
        ImagePlus labels = labelMapOneToTwoThousand();
        Set<Integer> removed = new HashSet<Integer>();
        removed.add(Integer.valueOf(260));

        ImagePlus hidden = ObjectOverlayRenderer.renderFiltered(
                source, labels, removed, false,
                PreviewDisplaySettings.of(0.0, 255.0,
                        PreviewDisplaySettings.LutMode.GREY, "Grays"));
        ImageProcessor hiddenProcessor = hidden.getProcessor();

        assertEquals(blend(0x000000, LabelMapStyler.rgbForLabel(5), 0.35),
                hiddenProcessor.getPixel(4, 0) & 0xffffff);
        assertEquals(0x000000, hiddenProcessor.getPixel(259, 0) & 0xffffff);
        assertEquals(blend(0x000000, LabelMapStyler.rgbForLabel(515), 0.35),
                hiddenProcessor.getPixel(514, 0) & 0xffffff);

        ImagePlus ghosts = ObjectOverlayRenderer.renderFiltered(
                source, labels, removed, true,
                PreviewDisplaySettings.of(0.0, 255.0,
                        PreviewDisplaySettings.LutMode.GREY, "Grays"));
        ImageProcessor ghostProcessor = ghosts.getProcessor();

        assertEquals(blend(0x000000, 0x808080, 0.35),
                ghostProcessor.getPixel(259, 0) & 0xffffff);
        assertEquals(blend(0x000000, LabelMapStyler.rgbForLabel(515), 0.35),
                ghostProcessor.getPixel(514, 0) & 0xffffff);
    }

    @Test
    public void sizeFilterSummaryReportsRemovedLabels() {
        ResultsTable stats = new ResultsTable();
        stats.incrementCounter();
        stats.setValue("Label", 0, 1);
        stats.setValue("Volume (pixel^3)", 0, 2);
        stats.incrementCounter();
        stats.setValue("Label", 1, 2);
        stats.setValue("Volume (pixel^3)", 1, 50);
        stats.incrementCounter();
        stats.setValue("Label", 2, 3);
        stats.setValue("Volume (pixel^3)", 2, 200);

        ObjectSizeFilterPreview.Summary summary =
                ObjectSizeFilterPreview.summarize(stats, null, 5, 100, true);
        Set<Integer> removed = summary.removedLabels();

        assertEquals(2, removed.size());
        assertTrue(removed.contains(Integer.valueOf(1)));
        assertFalse(removed.contains(Integer.valueOf(2)));
        assertTrue(removed.contains(Integer.valueOf(3)));
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

    private static ImagePlus labelMapOneToTwoThousand() {
        ShortProcessor processor = new ShortProcessor(2000, 1);
        for (int x = 0; x < 2000; x++) {
            processor.set(x, 0, x + 1);
        }
        return new ImagePlus("labels", processor);
    }
}
