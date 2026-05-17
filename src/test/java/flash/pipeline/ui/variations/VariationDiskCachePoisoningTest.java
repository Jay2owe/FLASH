package flash.pipeline.ui.variations;

import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.process.ByteProcessor;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class VariationDiskCachePoisoningTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void sameTitleAndDimensionsDifferentPixelsProduceDifferentDiskKeys()
            throws Exception {
        File binFolder = temp.newFolder(".bin");
        ImagePlus sourceA = sourceStack(31);
        ImagePlus sourceB = sourceStack(127);
        FilterParameterId sigma = new FilterParameterId(
                0, 0, 0, "Gaussian Blur", "sigma");
        Map<ParameterKey, ParameterValueList> values =
                new LinkedHashMap<ParameterKey, ParameterValueList>();
        values.put(sigma, ParameterValueList.ofDoubles(2.0d));
        ParameterCombo combo = new ParameterCombo(
                Collections.singletonMap(sigma, Double.valueOf(2.0d)));

        ParameterSweep sweepA = sweep(values,
                FilterVariationEngineContext.sourceImageHash(sourceA));
        String poisonedKey = VariationCache.keyFor(sweepA, combo);
        VariationCache cache = new VariationCache(binFolder);
        File poisonedFile = cache.fileForTest(poisonedKey);
        assertTrue(poisonedFile.getParentFile().mkdirs());
        IJ.saveAs(sentinelImage(), "Tiff", poisonedFile.getAbsolutePath());

        ImagePlus loaded = new VariationCache(binFolder).get(poisonedKey);
        assertNotNull(loaded);
        assertEquals(255, loaded.getStack().getProcessor(1).getPixel(0, 0));

        ParameterSweep sweepB = sweep(values,
                FilterVariationEngineContext.sourceImageHash(sourceB));
        String sourceBKey = VariationCache.keyFor(sweepB, combo);

        assertNotEquals(poisonedKey, sourceBKey);
    }

    private static ParameterSweep sweep(
            Map<ParameterKey, ParameterValueList> values,
            String sourceImageHash) {
        return new ParameterSweep(ParameterSweep.Method.FILTER,
                values, CropSpec.full(), "C1 (DAPI)", sourceImageHash,
                "filter:macrohash");
    }

    private static ImagePlus sourceStack(int value) {
        ImageStack stack = new ImageStack(16, 16);
        for (int z = 1; z <= 3; z++) {
            ByteProcessor processor = new ByteProcessor(16, 16);
            processor.setValue(value + z);
            processor.fill();
            stack.addSlice("z" + z, processor);
        }
        ImagePlus image = new ImagePlus(
                "Filter source | C1 (DAPI) | slideA", stack);
        image.setDimensions(1, 3, 1);
        return image;
    }

    private static ImagePlus sentinelImage() {
        ByteProcessor processor = new ByteProcessor(16, 16);
        processor.setValue(255);
        processor.fill();
        return new ImagePlus("poisoned-cache-entry", processor);
    }
}
