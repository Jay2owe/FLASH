package flash.pipeline.ui.preview;

import ij.ImagePlus;
import ij.ImageStack;
import ij.process.ColorProcessor;
import ij.process.ImageProcessor;

import java.awt.image.ColorModel;
import java.awt.image.IndexColorModel;

public final class ObjectOverlayRenderer {

    private static final double OVERLAY_ALPHA = 0.58;

    private ObjectOverlayRenderer() {
    }

    public static ImagePlus renderOverlay(ImagePlus source, ImagePlus labelMap) {
        if (source == null || labelMap == null) return null;
        ImageStack sourceStack = source.getStack();
        ImageStack labelStack = labelMap.getStack();
        if (sourceStack == null || labelStack == null
                || sourceStack.getSize() < 1 || labelStack.getSize() < 1) {
            return null;
        }

        ImageStack out = new ImageStack(source.getWidth(), source.getHeight());
        for (int i = 1; i <= sourceStack.getSize(); i++) {
            ImageProcessor sourceProcessor = sourceStack.getProcessor(i);
            ImageProcessor labelProcessor = labelStack.getProcessor(
                    Math.min(i, labelStack.getSize()));
            out.addSlice(sourceStack.getSliceLabel(i),
                    renderOverlaySlice(sourceProcessor, labelProcessor));
        }

        ImagePlus result = new ImagePlus("Object overlay | " + safeTitle(source), out);
        copyDimensions(source, result);
        return result;
    }

    public static ImagePlus renderLabelMap(ImagePlus labelMap, int objectCount) {
        if (labelMap == null || labelMap.getStack() == null || labelMap.getStackSize() < 1) {
            return null;
        }
        ImageStack in = labelMap.getStack();
        ImageStack out = new ImageStack(labelMap.getWidth(), labelMap.getHeight());
        for (int i = 1; i <= in.getSize(); i++) {
            ImageProcessor labels = in.getProcessor(i);
            int width = labels.getWidth();
            int height = labels.getHeight();
            int[] pixels = new int[width * height];
            int p = 0;
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    pixels[p++] = LabelMapStyler.rgbForLabel(labelAt(labels, x, y));
                }
            }
            ColorProcessor cp = new ColorProcessor(width, height);
            cp.setPixels(pixels);
            out.addSlice(in.getSliceLabel(i), cp);
        }
        ImagePlus result = new ImagePlus(objectCount > 0
                ? "Object label preview"
                : "Object label preview (no objects)", out);
        copyDimensions(labelMap, result);
        return result;
    }

    private static ColorProcessor renderOverlaySlice(ImageProcessor source,
                                                     ImageProcessor labels) {
        int width = source.getWidth();
        int height = source.getHeight();
        int[] pixels = new int[width * height];
        double min = source.getMin();
        double max = source.getMax();
        if (!(max > min)) {
            max = min + 1.0;
        }

        int p = 0;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int base = baseRgb(source, x, y, min, max);
                int label = labelAt(labels, x, y);
                pixels[p++] = label > 0
                        ? blend(base, LabelMapStyler.rgbForLabel(label), OVERLAY_ALPHA)
                        : base;
            }
        }
        ColorProcessor cp = new ColorProcessor(width, height);
        cp.setPixels(pixels);
        return cp;
    }

    private static int baseRgb(ImageProcessor source, int x, int y, double min, double max) {
        int index = scaledByte(source.getPixelValue(x, y), min, max);
        ColorModel model = source.getColorModel();
        if (model instanceof IndexColorModel) {
            IndexColorModel indexed = (IndexColorModel) model;
            return (indexed.getRed(index) << 16)
                    | (indexed.getGreen(index) << 8)
                    | indexed.getBlue(index);
        }
        return (index << 16) | (index << 8) | index;
    }

    private static int labelAt(ImageProcessor labels, int x, int y) {
        if (labels == null || x < 0 || y < 0
                || x >= labels.getWidth() || y >= labels.getHeight()) {
            return 0;
        }
        double value = labels.getPixelValue(x, y);
        if (!Double.isFinite(value) || value <= 0.0) return 0;
        return (int) Math.round(value);
    }

    private static int scaledByte(double value, double min, double max) {
        if (!Double.isFinite(value)) return 0;
        double scaled = (value - min) / (max - min);
        if (scaled < 0.0) return 0;
        if (scaled > 1.0) return 255;
        return (int) Math.round(scaled * 255.0);
    }

    private static int blend(int base, int overlay, double alpha) {
        double a = Math.max(0.0, Math.min(1.0, alpha));
        int br = (base >> 16) & 0xff;
        int bg = (base >> 8) & 0xff;
        int bb = base & 0xff;
        int or = (overlay >> 16) & 0xff;
        int og = (overlay >> 8) & 0xff;
        int ob = overlay & 0xff;
        int r = (int) Math.round(br * (1.0 - a) + or * a);
        int g = (int) Math.round(bg * (1.0 - a) + og * a);
        int b = (int) Math.round(bb * (1.0 - a) + ob * a);
        return (r << 16) | (g << 8) | b;
    }

    private static String safeTitle(ImagePlus source) {
        String title = source == null ? null : source.getTitle();
        return title == null || title.trim().isEmpty() ? "source" : title;
    }

    private static void copyDimensions(ImagePlus source, ImagePlus result) {
        result.setCalibration(source.getCalibration());
        int channels = Math.max(1, source.getNChannels());
        int slices = Math.max(1, source.getNSlices());
        int frames = Math.max(1, source.getNFrames());
        if (result.getStackSize() == channels * slices * frames) {
            result.setDimensions(channels, slices, frames);
        }
        result.setOpenAsHyperStack(source.isHyperStack());
    }
}
