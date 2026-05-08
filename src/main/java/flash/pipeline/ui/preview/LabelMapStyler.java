package flash.pipeline.ui.preview;

import ij.ImagePlus;
import ij.process.LUT;
import ij.process.ImageProcessor;

import java.awt.Color;

public final class LabelMapStyler {

    private static final LUT CATEGORICAL_LUT = createCategoricalLut();

    private LabelMapStyler() {
    }

    public static ImagePlus apply(ImagePlus labelImage, int objectCount) {
        if (labelImage == null) return null;
        labelImage.setDisplayRange(0, Math.max(1, Math.max(objectCount, maxDisplayValue(labelImage))));
        labelImage.setLut(CATEGORICAL_LUT);
        labelImage.updateAndDraw();
        return labelImage;
    }

    private static LUT createCategoricalLut() {
        byte[] red = new byte[256];
        byte[] green = new byte[256];
        byte[] blue = new byte[256];
        red[0] = 0;
        green[0] = 0;
        blue[0] = 0;
        for (int i = 1; i < 256; i++) {
            float hue = (float) ((i * 0.61803398875) % 1.0);
            Color color = Color.getHSBColor(hue, 0.85f, 1.0f);
            red[i] = (byte) color.getRed();
            green[i] = (byte) color.getGreen();
            blue[i] = (byte) color.getBlue();
        }
        return new LUT(red, green, blue);
    }

    private static int maxDisplayValue(ImagePlus image) {
        if (image == null || image.getStack() == null || image.getStackSize() < 1) {
            return 1;
        }
        double max = 0;
        for (int i = 1; i <= image.getStackSize(); i++) {
            ImageProcessor processor = image.getStack().getProcessor(i);
            if (processor != null) {
                max = Math.max(max, processor.getStats().max);
            }
        }
        if (!Double.isFinite(max) || max < 1) return 1;
        return (int) Math.round(max);
    }
}
