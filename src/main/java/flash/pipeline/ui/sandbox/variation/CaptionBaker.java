package flash.pipeline.ui.sandbox.variation;

import ij.ImagePlus;
import ij.ImageStack;
import ij.process.ImageProcessor;

import java.awt.Color;
import java.awt.Font;

/**
 * Bakes a single-line label into every image slice for later montage export.
 */
public final class CaptionBaker {

    private static final int MARGIN_PX = 6;
    private static final int FONT_PT = 14;

    private CaptionBaker() {}

    public static void bakeAll(ImagePlus imp, String caption) {
        if (imp == null) throw new IllegalArgumentException("imp must not be null");
        if (caption == null || caption.isEmpty()) return;

        ImageStack stack = imp.getStack();
        if (stack == null || stack.getSize() == 0) {
            ImageProcessor processor = imp.getProcessor();
            if (processor != null) bakeOne(processor, caption);
            imp.updateAndDraw();
            return;
        }

        for (int i = 1; i <= stack.getSize(); i++) {
            bakeOne(stack.getProcessor(i), caption);
        }
        imp.updateAndDraw();
    }

    private static void bakeOne(ImageProcessor processor, String caption) {
        processor.setFont(new Font("SansSerif", Font.BOLD, FONT_PT));
        processor.setAntialiasedText(true);
        int x = MARGIN_PX;
        int y = processor.getHeight() - MARGIN_PX;

        processor.setColor(Color.BLACK);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                if (dx == 0 && dy == 0) continue;
                processor.drawString(caption, x + dx, y + dy);
            }
        }
        processor.setColor(Color.WHITE);
        processor.drawString(caption, x, y);
    }
}
