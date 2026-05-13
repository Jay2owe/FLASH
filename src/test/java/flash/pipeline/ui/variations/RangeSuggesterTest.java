package flash.pipeline.ui.variations;

import ij.ImagePlus;
import ij.ImageStack;
import ij.process.AutoThresholder;
import ij.process.ByteProcessor;
import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class RangeSuggesterTest {

    @Test
    public void classicalThresholdSuggestionsSpanAutoThresholdMethods() {
        ImagePlus source = noisyGradientStack();
        ParameterCombo base = ParameterCombo.builder()
                .put(ParameterId.THRESHOLD, Integer.valueOf(100))
                .put(ParameterId.MIN_SIZE, Integer.valueOf(20))
                .put(ParameterId.MAX_SIZE, Integer.valueOf(1000))
                .build();
        VariationEngineContext context = VariationEngineContext.forClassical(
                "DAPI", source, source, null, base, null);
        Map<ParameterId, ParameterValueList> draftValues =
                new LinkedHashMap<ParameterId, ParameterValueList>();
        draftValues.put(ParameterId.THRESHOLD, ParameterValueList.ofInts(100));
        draftValues.put(ParameterId.MIN_SIZE, ParameterValueList.ofInts(20));
        draftValues.put(ParameterId.MAX_SIZE, ParameterValueList.ofInts(1000));
        ParameterSweep draft = new ParameterSweep(ParameterSweep.Method.CLASSICAL,
                draftValues, CropSpec.full(), "DAPI", "hash");

        Map<ParameterId, ParameterValueList> suggestions =
                RangeSuggester.suggest(context, draft);
        ParameterValueList thresholds = suggestions.get(ParameterId.THRESHOLD);

        assertNotNull(thresholds);
        assertCloseToAny(thresholds.values(),
                thresholdFor(source, AutoThresholder.Method.Otsu));
        assertCloseToAny(thresholds.values(),
                thresholdFor(source, AutoThresholder.Method.Li));
        assertCloseToAny(thresholds.values(),
                thresholdFor(source, AutoThresholder.Method.Triangle));
    }

    private static void assertCloseToAny(List<Object> suggestions, int expected) {
        for (int i = 0; i < suggestions.size(); i++) {
            int value = ((Number) suggestions.get(i)).intValue();
            double allowed = Math.max(1.0d, Math.abs(expected) * 0.05d);
            if (Math.abs(value - expected) <= allowed) {
                return;
            }
        }
        assertTrue("No suggestion within 5% of " + expected + ": " + suggestions, false);
    }

    private static int thresholdFor(ImagePlus source, AutoThresholder.Method method) {
        int[] histogram = new int[256];
        int width = source.getWidth();
        int height = source.getHeight();
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int max = 0;
                for (int z = 1; z <= source.getStackSize(); z++) {
                    int value = source.getStack().getProcessor(z).get(x, y) & 0xff;
                    if (value > max) {
                        max = value;
                    }
                }
                histogram[max]++;
            }
        }
        return new AutoThresholder().getThreshold(method, histogram);
    }

    private static ImagePlus noisyGradientStack() {
        int width = 128;
        int height = 128;
        int slices = 5;
        ImageStack stack = new ImageStack(width, height);
        for (int z = 0; z < slices; z++) {
            ByteProcessor processor = new ByteProcessor(width, height);
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int noise = (x * 17 + y * 31 + z * 43) % 23;
                    int value = Math.min(255, x * 2 + noise);
                    processor.set(x, y, value);
                }
            }
            stack.addSlice("z" + (z + 1), processor);
        }
        ImagePlus image = new ImagePlus("noisy-gradient", stack);
        image.setDimensions(1, slices, 1);
        return image;
    }
}
