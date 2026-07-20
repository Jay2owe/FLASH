package flash.pipeline.decontamination;

import ij.ImagePlus;
import ij.ImageStack;
import ij.process.ColorProcessor;
import ij.process.ShortProcessor;
import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class SpectralMergeRendererTest {

    // 2 pixels, 3 channels (target=0, bleed-through=1, autofluorescence=2).
    // pixel0: target only bright; pixel1: bleed-through only bright.
    private static ImagePlus image() {
        ImageStack stack = new ImageStack(2, 1);
        stack.addSlice(new ShortProcessor(2, 1, new short[]{1000, 0}, null));    // target
        stack.addSlice(new ShortProcessor(2, 1, new short[]{0, 1000}, null));    // bleed-through
        stack.addSlice(new ShortProcessor(2, 1, new short[]{0, 0}, null));       // autofluorescence
        ImagePlus image = new ImagePlus("merge-src", stack);
        image.setDimensions(3, 1, 1);
        return image;
    }

    private static SpectralDecontaminationConfig config() {
        SpectralDecontaminationConfig config = new SpectralDecontaminationConfig();
        config.setTargetChannelIndex(0);
        config.setBleedThroughChannelIndexes(Arrays.asList(Integer.valueOf(1)));
        config.setAutofluorescenceChannelIndexes(Arrays.asList(Integer.valueOf(2)));
        return config;
    }

    @Test
    public void beforeMergeColoursTargetGreenAndBleedThroughMagenta() {
        ImagePlus image = image();
        SpectralDecontaminationConfig config = config();
        SpectralMergeRenderer.DisplayScales scales = SpectralMergeRenderer.computeScales(image, config);
        ImagePlus merge = SpectralMergeRenderer.buildBeforeMerge(image, config, scales, "before");

        assertEquals(1, merge.getStackSize());
        ColorProcessor cp = (ColorProcessor) merge.getStack().getProcessor(1);

        int p0 = cp.getPixel(0, 0);
        assertEquals("target pixel red", 0, (p0 >> 16) & 0xff);
        assertEquals("target pixel green", 255, (p0 >> 8) & 0xff);
        assertEquals("target pixel blue", 0, p0 & 0xff);

        int p1 = cp.getPixel(1, 0);
        assertEquals("bleed pixel red", 255, (p1 >> 16) & 0xff);
        assertEquals("bleed pixel green", 0, (p1 >> 8) & 0xff);
        assertEquals("bleed pixel blue", 255, p1 & 0xff);
    }

    @Test
    public void afterMergeUsesCorrectedTargetAndSharedScales() {
        ImagePlus image = image();
        SpectralDecontaminationConfig config = config();
        SpectralMergeRenderer.DisplayScales scales = SpectralMergeRenderer.computeScales(image, config);

        // Corrected target: same geometry as one plane, target halved at pixel0.
        ImageStack correctedStack = new ImageStack(2, 1);
        correctedStack.addSlice(new ShortProcessor(2, 1, new short[]{500, 0}, null));
        ImagePlus corrected = new ImagePlus("corrected", correctedStack);
        corrected.setDimensions(1, 1, 1);

        ImagePlus merge = SpectralMergeRenderer.buildAfterMerge(image, corrected, config, scales, "after");
        ColorProcessor cp = (ColorProcessor) merge.getStack().getProcessor(1);
        int p0 = cp.getPixel(0, 0);
        // Halved target vs a shared max of 1000 -> ~128 green, still no red/blue.
        int green = (p0 >> 8) & 0xff;
        assertTrue("corrected target should dim relative to before (" + green + ")", green > 100 && green < 160);
        assertEquals(0, (p0 >> 16) & 0xff);
        assertEquals(0, p0 & 0xff);
    }

    @Test
    public void afterMergeFallsBackToRawTargetWhenCorrectedNull() {
        ImagePlus image = image();
        SpectralDecontaminationConfig config = config();
        SpectralMergeRenderer.DisplayScales scales = SpectralMergeRenderer.computeScales(image, config);
        ImagePlus merge = SpectralMergeRenderer.buildAfterMerge(image, null, config, scales, "after-null");
        ColorProcessor cp = (ColorProcessor) merge.getStack().getProcessor(1);
        assertEquals(255, (cp.getPixel(0, 0) >> 8) & 0xff);
    }
}
