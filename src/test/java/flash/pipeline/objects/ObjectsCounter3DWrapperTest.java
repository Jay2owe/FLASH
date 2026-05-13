package flash.pipeline.objects;

import ij.ImagePlus;
import ij.ImageStack;
import ij.process.ByteProcessor;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assume.assumeTrue;

public class ObjectsCounter3DWrapperTest {

    @Test
    public void parseSizeHelpersAcceptInfinityAndRoundedNumbers() {
        ImageStack stack = new ImageStack(4, 5);
        stack.addSlice(new ByteProcessor(4, 5));
        stack.addSlice(new ByteProcessor(4, 5));
        stack.addSlice(new ByteProcessor(4, 5));
        ImagePlus reference = new ImagePlus("reference", stack);

        assertEquals(13, ObjectsCounter3DWrapper.parseMinSizeVoxels("12.6", 100));
        assertEquals(60, ObjectsCounter3DWrapper.parseMaxSizeVoxels("Infinity", reference));
        assertEquals(60, ObjectsCounter3DWrapper.parseMaxSizeVoxels("inf", reference));
        assertEquals(8, ObjectsCounter3DWrapper.parseMaxSizeVoxels("7.6", reference));
    }

    @Test
    public void fromLabelImageAppliesSizeFilterBeforeMeasuringObjects() {
        assumeTrue(ObjectsCounter3DWrapper.isMcib3dAvailable());

        ByteProcessor labels = new ByteProcessor(4, 1);
        labels.set(0, 0, 1);
        labels.set(1, 0, 2);
        labels.set(2, 0, 2);
        labels.set(3, 0, 2);
        ImagePlus labelImage = new ImagePlus("labels", labels);
        ImagePlus redirect = new ImagePlus("redirect", new ByteProcessor(4, 1));

        ObjectsCounter3DWrapper.Result result = new ObjectsCounter3DWrapper()
                .fromLabelImage(labelImage, redirect, 2, Integer.MAX_VALUE, true, true);

        assertEquals(1, result.getStatistics().size());
        assertEquals(0, result.getObjectsMap().getProcessor().get(0, 0));
        assertEquals(2, result.getObjectsMap().getProcessor().get(1, 0));
    }
}
