package flash.pipeline.ui.sandbox.variation;

import ij.ImagePlus;
import ij.ImageStack;
import ij.process.ByteProcessor;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class CaptionBakerTest {

    private static final int W = 64;
    private static final int H = 64;

    @Test
    public void bakingMarksPixelsInBottomLeftRegion() {
        ImagePlus imp = whiteImage(W, H);
        CaptionBaker.bakeAll(imp, "TEST");

        ByteProcessor processor = (ByteProcessor) imp.getProcessor();
        assertTrue(anyNonWhite(processor, 2, H - 24, 40, 22));
    }

    @Test
    public void bakingLeavesTopRightRegionUntouched() {
        ImagePlus imp = whiteImage(W, H);
        CaptionBaker.bakeAll(imp, "TEST");

        ByteProcessor processor = (ByteProcessor) imp.getProcessor();
        assertFalse(anyNonWhite(processor, W - 16, 0, 16, 16));
    }

    @Test
    public void bakingAppliesToEverySliceOfAStack() {
        ImageStack stack = new ImageStack(W, H);
        for (int i = 0; i < 5; i++) {
            ByteProcessor processor = new ByteProcessor(W, H);
            processor.setColor(255);
            processor.fill();
            stack.addSlice("s" + i, processor);
        }
        ImagePlus imp = new ImagePlus("stack", stack);

        CaptionBaker.bakeAll(imp, "Z");

        for (int i = 1; i <= stack.getSize(); i++) {
            ByteProcessor processor = (ByteProcessor) stack.getProcessor(i);
            assertTrue(anyNonWhite(processor, 2, H - 24, 40, 22));
        }
    }

    @Test
    public void emptyOrNullCaptionIsNoOp() {
        ImagePlus imp = whiteImage(W, H);
        CaptionBaker.bakeAll(imp, "");
        CaptionBaker.bakeAll(imp, null);
        ByteProcessor processor = (ByteProcessor) imp.getProcessor();
        assertEquals(0L, countNonWhite(processor, 0, 0, W, H));
    }

    private static ImagePlus whiteImage(int width, int height) {
        ByteProcessor processor = new ByteProcessor(width, height);
        processor.setColor(255);
        processor.fill();
        return new ImagePlus("white", processor);
    }

    private static boolean anyNonWhite(ByteProcessor processor, int x, int y, int w, int h) {
        return countNonWhite(processor, x, y, w, h) > 0;
    }

    private static long countNonWhite(ByteProcessor processor, int x, int y, int w, int h) {
        int x2 = Math.min(processor.getWidth(), x + w);
        int y2 = Math.min(processor.getHeight(), y + h);
        long count = 0;
        for (int yy = Math.max(0, y); yy < y2; yy++) {
            for (int xx = Math.max(0, x); xx < x2; xx++) {
                if ((processor.get(xx, yy) & 0xff) != 255) count++;
            }
        }
        return count;
    }
}
