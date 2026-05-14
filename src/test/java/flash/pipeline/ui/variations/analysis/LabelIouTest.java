package flash.pipeline.ui.variations.analysis;

import ij.ImagePlus;
import ij.process.ByteProcessor;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class LabelIouTest {

    @Test
    public void identicalImagesReturnOne() {
        ImagePlus a = mask(4, 4, 1, 2, 5, 6);
        ImagePlus b = mask(4, 4, 1, 2, 5, 6);

        assertEquals(1.0d, LabelIou.iou(a, b), 0.000001d);
    }

    @Test
    public void disjointImagesReturnZero() {
        ImagePlus a = mask(4, 4, 0, 1);
        ImagePlus b = mask(4, 4, 14, 15);

        assertEquals(0.0d, LabelIou.iou(a, b), 0.000001d);
    }

    @Test
    public void subsetImageReturnsIntersectionOverUnion() {
        ImagePlus subset = mask(4, 4, 0, 1);
        ImagePlus superset = mask(4, 4, 0, 1, 2, 3);

        assertEquals(0.5d, LabelIou.iou(subset, superset), 0.000001d);
    }

    private static ImagePlus mask(int width, int height, int... activeIndexes) {
        byte[] pixels = new byte[width * height];
        for (int i = 0; i < activeIndexes.length; i++) {
            pixels[activeIndexes[i]] = (byte) 255;
        }
        return new ImagePlus("mask", new ByteProcessor(width, height, pixels, null));
    }
}
