package flash.pipeline.objects;

import ij.ImagePlus;
import ij.ImageStack;
import ij.measure.ResultsTable;
import ij.process.ByteProcessor;
import ij.process.FloatProcessor;
import org.junit.Test;

import java.lang.reflect.Method;
import java.util.List;

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
        assertEquals(100, ObjectsCounter3DWrapper.parseMinSizeVoxels("Infinity", 100));
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
    public void fromLabelImageKeepsObjectsExactlyAtSizeBounds() {
        assumeTrue(ObjectsCounter3DWrapper.isMcib3dAvailable());

        ByteProcessor labels = new ByteProcessor(5, 1);
        labels.set(0, 0, 1);
        labels.set(1, 0, 1);
        labels.set(2, 0, 2);
        labels.set(3, 0, 2);
        labels.set(4, 0, 2);
        ImagePlus labelImage = new ImagePlus("labels", labels);
        ImagePlus redirect = new ImagePlus("redirect", new ByteProcessor(5, 1));

        ObjectsCounter3DWrapper.Result result = new ObjectsCounter3DWrapper()
                .fromLabelImage(labelImage, redirect, 2, 3, true, false);

        assertEquals(2, result.getStatistics().size());
        assertEquals(1, result.getObjectsMap().getProcessor().get(0, 0));
        assertEquals(2, result.getObjectsMap().getProcessor().get(2, 0));
    }

    @Test
    public void runNativeKeepsThresholdedObjectsExactlyAtSizeBounds() {
        assumeTrue(ObjectsCounter3DWrapper.isMcib3dAvailable());

        ByteProcessor pixels = new ByteProcessor(6, 1);
        pixels.set(0, 0, 200);
        pixels.set(1, 0, 200);
        pixels.set(3, 0, 200);
        pixels.set(4, 0, 200);
        pixels.set(5, 0, 200);
        ImagePlus image = new ImagePlus("thresholded", pixels);

        ObjectsCounter3DWrapper.Result result = new ObjectsCounter3DWrapper()
                .runNative(image, 100, 2, 3, false, image, true, false);

        assertEquals(2, result.getStatistics().size());
        assertEquals(2, CpcUtils.extractObjects(result.getObjectsMap()).size());
    }

    @Test
    public void fromLabelImageReportsBoundingCubeColumns() {
        assumeTrue(ObjectsCounter3DWrapper.isMcib3dAvailable());

        ImageStack labels = new ImageStack(5, 5);
        for (int z = 0; z < 3; z++) {
            ByteProcessor slice = new ByteProcessor(5, 5);
            for (int y = 2; y <= 4; y++) {
                for (int x = 1; x <= 3; x++) {
                    slice.set(x, y, 7);
                }
            }
            labels.addSlice(slice);
        }
        ImagePlus labelImage = new ImagePlus("labels", labels);
        ImagePlus redirect = new ImagePlus("redirect", labels.duplicate());

        ObjectsCounter3DWrapper.Result result = new ObjectsCounter3DWrapper()
                .fromLabelImage(labelImage, redirect, 0, Integer.MAX_VALUE, true, false);
        ResultsTable stats = result.getStatistics();

        assertEquals(1, stats.size());
        assertEquals(7.0, stats.getValue("Label", 0), 0.0);
        assertEquals(1.0, stats.getValue("BX", 0), 0.0);
        assertEquals(2.0, stats.getValue("BY", 0), 0.0);
        assertEquals(0.0, stats.getValue("BZ", 0), 0.0);
        assertEquals(3.0, stats.getValue("B-width", 0), 0.0);
        assertEquals(3.0, stats.getValue("B-height", 0), 0.0);
        assertEquals(3.0, stats.getValue("B-depth", 0), 0.0);
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

    @Test
    public void thresholdCopyZerosNonFinitePixels() throws Exception {
        FloatProcessor pixels = new FloatProcessor(3, 1);
        pixels.setf(0, 0, Float.NaN);
        pixels.setf(1, 0, Float.POSITIVE_INFINITY);
        pixels.setf(2, 0, 11.0f);
        ImagePlus source = new ImagePlus("float-threshold", pixels);

        ImagePlus thresholded = invokeThresholdCopy(source, 10);

        assertEquals(0.0f, thresholded.getProcessor().getf(0, 0), 0.0f);
        assertEquals(0.0f, thresholded.getProcessor().getf(1, 0), 0.0f);
        assertEquals(11.0f, thresholded.getProcessor().getf(2, 0), 0.0f);
    }

    @Test
    public void cpcExtractionIgnoresNonFiniteLabels() {
        FloatProcessor pixels = new FloatProcessor(3, 1);
        pixels.setf(0, 0, 1.0f);
        pixels.setf(1, 0, Float.NaN);
        pixels.setf(2, 0, Float.POSITIVE_INFINITY);
        ImagePlus labels = new ImagePlus("labels", pixels);

        List<CpcUtils.ObjectInfo> objects = CpcUtils.extractObjects(labels);

        assertEquals(1, objects.size());
        assertEquals(1, objects.get(0).label);
        assertEquals(1, objects.get(0).voxelCount);
    }

    private static ImagePlus invokeThresholdCopy(ImagePlus source, int threshold) throws Exception {
        Method method = ObjectsCounter3DWrapper.class.getDeclaredMethod(
                "thresholdCopy", ImagePlus.class, int.class);
        method.setAccessible(true);
        return (ImagePlus) method.invoke(null, source, threshold);
    }
}
