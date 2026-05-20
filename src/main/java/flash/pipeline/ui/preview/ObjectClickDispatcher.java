package flash.pipeline.ui.preview;

import ij.ImagePlus;
import ij.ImageStack;
import ij.process.ImageProcessor;

import java.awt.event.MouseEvent;

final class ObjectClickDispatcher {

    interface Handler {
        void objectClicked(int label, int z, double x, double y,
                           boolean positive, boolean clear);
    }

    private ObjectClickDispatcher() {
    }

    static void dispatch(ImagePlus labelImage, double x, double y, int z,
                         int button, int modifiers, Handler handler) {
        if (handler == null) return;
        int label = labelAt(labelImage, z, (int) x, (int) y);
        if (label <= 0) return;

        boolean clear = button == MouseEvent.BUTTON3;
        boolean left = button == MouseEvent.BUTTON1;
        if (!clear && !left) return;

        boolean positive = left && (modifiers & MouseEvent.SHIFT_DOWN_MASK) != 0;
        handler.objectClicked(label, z, x, y, positive, clear);
    }

    static int labelAt(ImagePlus labelImage, int z, int x, int y) {
        if (labelImage == null || z < 1) return 0;
        ImageStack stack;
        try {
            stack = labelImage.getStack();
        } catch (RuntimeException e) {
            return 0;
        }
        if (stack == null || z > stack.getSize()) return 0;
        ImageProcessor processor;
        try {
            processor = stack.getProcessor(z);
        } catch (RuntimeException e) {
            return 0;
        }
        return ObjectOverlayRenderer.labelAt(processor, x, y);
    }
}
