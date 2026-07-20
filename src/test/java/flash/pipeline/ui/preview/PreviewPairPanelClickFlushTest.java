package flash.pipeline.ui.preview;

import flash.pipeline.click.ClickStore;
import flash.pipeline.click.ClicksConfigIO;
import ij.ImagePlus;
import ij.ImageStack;
import ij.process.ByteProcessor;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.awt.event.MouseEvent;
import java.io.File;
import java.util.List;

import static org.junit.Assert.assertEquals;

public class PreviewPairPanelClickFlushTest {
    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    @Test
    public void flushClicksSyncPersistsJustClickedMarker() throws Exception {
        File bin = tempFolder.newFolder("bin");
        PreviewPairPanel pair = clickCapturePair(bin, new ClickStore());

        addPositiveClick(pair);
        pair.flushClicksSync();

        assertPersistedClick(bin, ClickStore.Verdict.POSITIVE);
    }

    @Test
    public void clearClickCaptureFlushesJustClickedMarkerBeforeDetach() throws Exception {
        File bin = tempFolder.newFolder("bin");
        PreviewPairPanel pair = clickCapturePair(bin, new ClickStore());

        addPositiveClick(pair);
        pair.clearClickCapture();

        assertPersistedClick(bin, ClickStore.Verdict.POSITIVE);
    }

    @Test
    public void asyncClickWriteStillPersistsWithoutExplicitFlush() throws Exception {
        File bin = tempFolder.newFolder("bin");
        PreviewPairPanel pair = clickCapturePair(bin, new ClickStore());

        addPositiveClick(pair);
        PreviewPairPanel.drainPendingClickWritesForTest();

        assertPersistedClick(bin, ClickStore.Verdict.POSITIVE);
    }

    private static PreviewPairPanel clickCapturePair(File bin, ClickStore store) {
        PreviewPairPanel pair = new PreviewPairPanel("Filtered", "Objects");
        pair.setClickCapture(bin, store, "Mouse1_LH_SCN", 2);
        ImagePlus raw = clickSource("raw");
        ImagePlus filtered = clickSource("filtered");
        ImagePlus labels = clickLabels("Object labels");
        pair.setOriginal(filtered);
        pair.setLargePreviewImages(raw, filtered, labels);
        pair.setAdjusted(labels);
        return pair;
    }

    private static void addPositiveClick(PreviewPairPanel pair) {
        pair.originalPreviewForTest().firePixelClickForTest(
                2.0, 1.0, 2, MouseEvent.BUTTON1, MouseEvent.SHIFT_DOWN_MASK);
    }

    private static void assertPersistedClick(File bin, ClickStore.Verdict verdict) {
        ClickStore persisted = ClicksConfigIO.read(bin);
        List<ClickStore.Click> clicks = persisted.all();
        assertEquals(1, clicks.size());
        ClickStore.Click click = clicks.get(0);
        assertEquals("Mouse1_LH_SCN", click.imageName);
        assertEquals(2, click.channelOneBased);
        assertEquals(11, click.label);
        assertEquals(2, click.z);
        assertEquals(2.0, click.x, 0.0001);
        assertEquals(1.0, click.y, 0.0001);
        assertEquals(verdict, click.verdict);
    }

    private static ImagePlus clickSource(String title) {
        ImageStack stack = new ImageStack(3, 2);
        for (int i = 0; i < 2; i++) {
            ByteProcessor processor = new ByteProcessor(3, 2);
            processor.set(0, 0, 50);
            processor.set(2, 1, 100);
            stack.addSlice(processor);
        }
        return new ImagePlus(title, stack);
    }

    private static ImagePlus clickLabels(String title) {
        ImageStack stack = new ImageStack(3, 2);
        ByteProcessor first = new ByteProcessor(3, 2);
        first.set(0, 0, 7);
        first.set(1, 0, 0);
        stack.addSlice(first);
        ByteProcessor second = new ByteProcessor(3, 2);
        second.set(2, 1, 11);
        stack.addSlice(second);
        return new ImagePlus(title, stack);
    }
}
