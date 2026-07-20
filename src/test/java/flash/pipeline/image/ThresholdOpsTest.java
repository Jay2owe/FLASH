package flash.pipeline.image;

import ij.ImagePlus;
import ij.ImageStack;
import ij.process.ByteProcessor;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ThresholdOpsTest {

    @Test
    public void bareMethodAliasResolvesAsAutoThreshold() {
        ThresholdOps.AutoThresholdSpec spec =
                ThresholdOps.autoThresholdSpecForToken("otsu", false);

        assertEquals("Otsu", spec.method);
        assertEquals("dark", spec.background);
        assertTrue(ThresholdOps.isAutoThresholdToken("otsu"));
        assertEquals("auto:Otsu:dark", ThresholdOps.formatAutoToken(spec.method, spec.background));
    }

    @Test
    public void applyStackThresholdInPlaceAcceptsAlgorithmicToken() {
        ImagePlus image = image(0, 0, 255, 255);

        assertTrue(ThresholdOps.applyStackThresholdInPlace(image, "auto:Otsu:dark", false));

        for (int i = 0; i < image.getProcessor().getPixelCount(); i++) {
            int value = image.getProcessor().get(i);
            assertTrue(value == 0 || value == 255);
        }
    }

    private static ImagePlus image(int... values) {
        ImageStack stack = new ImageStack(values.length, 1);
        ByteProcessor processor = new ByteProcessor(values.length, 1);
        for (int i = 0; i < values.length; i++) {
            processor.set(i, 0, values[i]);
        }
        stack.addSlice(processor);
        return new ImagePlus("threshold", stack);
    }
}
