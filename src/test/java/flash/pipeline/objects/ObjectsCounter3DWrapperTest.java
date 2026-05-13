package flash.pipeline.objects;

import ij.ImagePlus;
import ij.ImageStack;
import ij.process.ByteProcessor;
import ij.process.FloatProcessor;
import org.junit.Test;

import java.lang.reflect.Method;

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

    @Test
    public void thresholdCopyUsesFloatPixelValuesForThresholdComparison() throws Exception {
        FloatProcessor pixels = new FloatProcessor(3, 1);
        pixels.setf(0, 0, 9.6f);
        pixels.setf(1, 0, 10.0f);
        pixels.setf(2, 0, 10.25f);
        ImagePlus source = new ImagePlus("float-threshold", pixels);

        ImagePlus thresholded = invokeThresholdCopy(source, 10);

        assertEquals(0.0f, thresholded.getProcessor().getf(0, 0), 0.0f);
        assertEquals(10.0f, thresholded.getProcessor().getf(1, 0), 0.0f);
        assertEquals(10.25f, thresholded.getProcessor().getf(2, 0), 0.0f);
        assertEquals(9.6f, source.getProcessor().getf(0, 0), 0.0f);
    }

    private static ImagePlus invokeThresholdCopy(ImagePlus source, int threshold) throws Exception {
        Method method = ObjectsCounter3DWrapper.class.getDeclaredMethod(
                "thresholdCopy", ImagePlus.class, int.class);
        method.setAccessible(true);
        return (ImagePlus) method.invoke(null, source, threshold);
    }
}
