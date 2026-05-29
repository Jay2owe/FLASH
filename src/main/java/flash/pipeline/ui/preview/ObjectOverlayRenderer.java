package flash.pipeline.ui.preview;

import ij.ImagePlus;
import ij.ImageStack;
import ij.process.ColorProcessor;
import ij.process.ImageProcessor;

import java.awt.image.ColorModel;
import java.awt.image.IndexColorModel;

public final class ObjectOverlayRenderer {

    private static final double OVERLAY_ALPHA = 0.35;
    private static final int GHOST_RGB = 0x808080;

    private ObjectOverlayRenderer() {
    }

    public static ImagePlus renderOverlay(ImagePlus source, ImagePlus labelMap) {
        return renderOverlay(source, labelMap, null);
    }

    public static ImagePlus renderOverlay(ImagePlus source, ImagePlus labelMap,
                                          PreviewDisplaySettings displaySettings) {
        return renderFiltered(source, labelMap, null, true, displaySettings);
    }

    public static ImagePlus renderLabelMap(ImagePlus labelMap, int objectCount) {
        ImagePlus result = renderFiltered(null, labelMap, null, true, null);
        if (result != null) {
            result.setTitle(objectCount > 0
                    ? "Object label preview"
                    : "Object label preview (no objects)");
        }
        return result;
    }

    public static ImagePlus renderFiltered(ImagePlus source,
                                           ImagePlus labelMap,
                                           java.util.Set<Integer> removedLabels,
                                           boolean showRemoved,
                                           PreviewDisplaySettings displaySettings) {
        if (labelMap == null || labelMap.getStack() == null
                || labelMap.getStackSize() < 1) {
            return null;
        }
        boolean overlay = source != null && source.getStack() != null
                && source.getStackSize() > 0;
        ImageStack labelStack = labelMap.getStack();
        ImageStack sourceStack = overlay ? source.getStack() : null;
        ImageStack out = new ImageStack(labelMap.getWidth(), labelMap.getHeight());

        for (int i = 1; i <= labelStack.getSize(); i++) {
            ImageProcessor labels = labelStack.getProcessor(i);
            ImageProcessor src = overlay
                    ? sourceStack.getProcessor(Math.min(i, sourceStack.getSize()))
                    : null;
            out.addSlice(labelStack.getSliceLabel(i),
                    renderFilteredSlice(source, src, labels, removedLabels,
                            showRemoved, displaySettings));
        }

        ImagePlus result = new ImagePlus(overlay
                ? "Object overlay | " + safeTitle(source)
                : "Object preview", out);
        copyDimensions(overlay ? source : labelMap, result);
        copyOverlay(labelMap, result);
        return result;
    }

    private static ColorProcessor renderFilteredSlice(ImagePlus sourceImage,
                                                      ImageProcessor source,
                                                      ImageProcessor labels,
                                                      java.util.Set<Integer> removed,
                                                      boolean showRemoved,
                                                      PreviewDisplaySettings settings) {
        int width = labels.getWidth();
        int height = labels.getHeight();
        int[] pixels = new int[width * height];
        boolean overlay = source != null;
        double[] range = overlay ? displayRange(sourceImage, source, settings) : null;
        ColorModel model = overlay ? colorModel(source, settings) : null;

        int p = 0;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int base = overlay && x < source.getWidth() && y < source.getHeight()
                        ? baseRgb(source, x, y, range[0], range[1], model)
                        : 0x000000;
                int label = labelAt(labels, x, y);
                if (label <= 0) {
                    pixels[p++] = base;
                    continue;
                }
                boolean isRemoved = removed != null && removed.contains(Integer.valueOf(label));
                if (isRemoved && !showRemoved) {
                    pixels[p++] = base;
                } else if (isRemoved) {
                    pixels[p++] = overlay ? blend(base, GHOST_RGB, OVERLAY_ALPHA) : GHOST_RGB;
                } else {
                    int color = LabelMapStyler.rgbForLabel(label);
                    pixels[p++] = overlay ? blend(base, color, OVERLAY_ALPHA) : color;
                }
            }
        }
        ColorProcessor cp = new ColorProcessor(width, height);
        cp.setPixels(pixels);
        return cp;
    }

    private static double[] displayRange(ImagePlus sourceImage, ImageProcessor source,
                                         PreviewDisplaySettings displaySettings) {
        double min;
        double max;
        if (displaySettings != null && displaySettings.hasDisplayRange()) {
            min = displaySettings.getDisplayMin();
            max = displaySettings.getDisplayMax();
        } else {
            min = sourceImage == null ? Double.NaN : sourceImage.getDisplayRangeMin();
            max = sourceImage == null ? Double.NaN : sourceImage.getDisplayRangeMax();
            if (!(max > min)) {
                min = source.getMin();
                max = source.getMax();
            }
        }
        if (!(max > min)) {
            max = min + 1.0;
        }
        return new double[]{min, max};
    }

    private static ColorModel colorModel(ImageProcessor source,
                                         PreviewDisplaySettings displaySettings) {
        if (displaySettings == null) return source.getColorModel();
        return colorModelFor(displaySettings.effectiveLutName());
    }

    private static int baseRgb(ImageProcessor source, int x, int y, double min, double max,
                               ColorModel model) {
        int index = scaledByte(source.getPixelValue(x, y), min, max);
        if (model instanceof IndexColorModel) {
            IndexColorModel indexed = (IndexColorModel) model;
            return (indexed.getRed(index) << 16)
                    | (indexed.getGreen(index) << 8)
                    | indexed.getBlue(index);
        }
        return (index << 16) | (index << 8) | index;
    }

    public static int labelAt(ImageProcessor labels, int x, int y) {
        if (labels == null || x < 0 || y < 0
                || x >= labels.getWidth() || y >= labels.getHeight()) {
            return 0;
        }
        double value = labels.getPixelValue(x, y);
        if (!Double.isFinite(value) || value <= 0.0) return 0;
        return (int) Math.round(value);
    }

    private static int labelRgb(ColorModel labelColorModel, ImageProcessor labels, int label) {
        ColorModel model = labelColorModel != null
                ? labelColorModel
                : labels == null ? null : labels.getColorModel();
        if (model instanceof IndexColorModel) {
            IndexColorModel indexed = (IndexColorModel) model;
            int index = ((Math.max(1, label) - 1) % 255) + 1;
            int rgb = (indexed.getRed(index) << 16)
                    | (indexed.getGreen(index) << 8)
                    | indexed.getBlue(index);
            int strongestChannel = Math.max(indexed.getRed(index),
                    Math.max(indexed.getGreen(index), indexed.getBlue(index)));
            if (strongestChannel > 16) {
                return rgb;
            }
        }
        return LabelMapStyler.rgbForLabel(label);
    }

    private static ColorModel colorModelForLabelMap(ImagePlus labelMap) {
        if (labelMap == null) return null;
        try {
            ImageProcessor processor = labelMap.getProcessor();
            if (processor != null && processor.getColorModel() != null) {
                return processor.getColorModel();
            }
        } catch (RuntimeException ignored) {
            // Fall through to default label colors.
        }
        return null;
    }

    private static int scaledByte(double value, double min, double max) {
        if (!Double.isFinite(value)) return 0;
        double scaled = (value - min) / (max - min);
        if (scaled < 0.0) return 0;
        if (scaled > 1.0) return 255;
        return (int) Math.round(scaled * 255.0);
    }

    private static ColorModel colorModelFor(String lutName) {
        String normalized = PreviewDisplaySettings.normalizeLutName(lutName);
        byte[] red = new byte[256];
        byte[] green = new byte[256];
        byte[] blue = new byte[256];
        for (int i = 0; i < 256; i++) {
            int r = i;
            int g = i;
            int b = i;
            if ("Red".equals(normalized)) {
                g = 0;
                b = 0;
            } else if ("Green".equals(normalized)) {
                r = 0;
                b = 0;
            } else if ("Blue".equals(normalized)) {
                r = 0;
                g = 0;
            } else if ("Cyan".equals(normalized)) {
                r = 0;
            } else if ("Magenta".equals(normalized)) {
                g = 0;
            } else if ("Yellow".equals(normalized)) {
                b = 0;
            }
            red[i] = (byte) r;
            green[i] = (byte) g;
            blue[i] = (byte) b;
        }
        return new IndexColorModel(8, 256, red, green, blue);
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

    private static void copyOverlay(ImagePlus source, ImagePlus result) {
        if (source == null || result == null) return;
        try {
            ij.gui.Overlay overlay = source.getOverlay();
            if (overlay != null) {
                result.setOverlay(overlay.duplicate());
            }
        } catch (RuntimeException ignored) {
            // Overlays are optional preview adornments.
        }
    }
}
