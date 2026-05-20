package flash.pipeline.ui.variations.analysis;

import ij.ImagePlus;
import ij.ImageStack;
import ij.process.ImageProcessor;

public final class LabelIou {

    private LabelIou() {
    }

    public static double iou(ImagePlus a, ImagePlus b) {
        validateCompatible(a, b);
        int width = a.getWidth();
        int height = a.getHeight();
        int pixelsPerSlice = width * height;
        int slices = Math.max(1, a.getStackSize());
        ImageStack aStack = a.getStack();
        ImageStack bStack = b.getStack();

        long intersection = 0L;
        long union = 0L;
        for (int z = 1; z <= slices; z++) {
            ImageProcessor aProcessor = aStack.getProcessor(z);
            ImageProcessor bProcessor = bStack.getProcessor(z);
            Object aPixels = aProcessor.getPixels();
            Object bPixels = bProcessor.getPixels();
            if (pixelArrayLength(aPixels) < pixelsPerSlice
                    || pixelArrayLength(bPixels) < pixelsPerSlice) {
                throw new IllegalArgumentException("label pixel array is smaller than image dimensions");
            }
            for (int i = 0; i < pixelsPerSlice; i++) {
                boolean inA = positive(aPixels, i);
                boolean inB = positive(bPixels, i);
                if (inA && inB) {
                    intersection++;
                }
                if (inA || inB) {
                    union++;
                }
            }
        }
        return union == 0L ? 0.0 : (double) intersection / (double) union;
    }

    private static void validateCompatible(ImagePlus a, ImagePlus b) {
        if (a == null || b == null) {
            throw new IllegalArgumentException("label images must not be null");
        }
        if (a.getWidth() != b.getWidth()
                || a.getHeight() != b.getHeight()
                || a.getStackSize() != b.getStackSize()) {
            throw new IllegalArgumentException("label images must have matching dimensions");
        }
        if (a.getStack() == null || b.getStack() == null) {
            throw new IllegalArgumentException("label images must have stacks");
        }
    }

    private static int pixelArrayLength(Object pixels) {
        if (pixels instanceof byte[]) {
            return ((byte[]) pixels).length;
        }
        if (pixels instanceof short[]) {
            return ((short[]) pixels).length;
        }
        if (pixels instanceof int[]) {
            return ((int[]) pixels).length;
        }
        if (pixels instanceof float[]) {
            return ((float[]) pixels).length;
        }
        if (pixels instanceof double[]) {
            return ((double[]) pixels).length;
        }
        throw new IllegalArgumentException("unsupported label pixel array type: "
                + (pixels == null ? "null" : pixels.getClass().getName()));
    }

    private static boolean positive(Object pixels, int index) {
        if (pixels instanceof byte[]) {
            return (((byte[]) pixels)[index] & 0xff) > 0;
        }
        if (pixels instanceof short[]) {
            return (((short[]) pixels)[index] & 0xffff) > 0;
        }
        if (pixels instanceof int[]) {
            return ((int[]) pixels)[index] > 0;
        }
        if (pixels instanceof float[]) {
            return ((float[]) pixels)[index] > 0.0f;
        }
        if (pixels instanceof double[]) {
            return ((double[]) pixels)[index] > 0.0d;
        }
        throw new IllegalArgumentException("unsupported label pixel array type: "
                + (pixels == null ? "null" : pixels.getClass().getName()));
    }
}
