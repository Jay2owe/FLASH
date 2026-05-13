package flash.pipeline.image.variation;

import flash.pipeline.image.dag.Combiner;
import flash.pipeline.image.dag.DagIR;
import flash.pipeline.image.dag.DagLine;
import ij.ImagePlus;
import ij.ImageStack;
import ij.gui.Roi;
import ij.measure.Calibration;
import ij.process.FloatProcessor;
import org.junit.Test;

import java.awt.Rectangle;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class RoiCropperTest {

    @Test
    public void cropToRoiPreservesHyperstackDimensionsAndCalibration() {
        ImagePlus source = hyperstack(5, 4, 2, 3, 2);
        Calibration cal = new Calibration();
        cal.pixelWidth = 0.5;
        cal.pixelHeight = 0.25;
        source.setCalibration(cal);
        source.setDisplayRange(10.0, 200.0);

        ImagePlus cropped = RoiCropper.cropToRoi(source, new Roi(1, 1, 3, 2));

        assertEquals(3, cropped.getWidth());
        assertEquals(2, cropped.getHeight());
        assertEquals(2, cropped.getNChannels());
        assertEquals(3, cropped.getNSlices());
        assertEquals(2, cropped.getNFrames());
        assertEquals(12, cropped.getStackSize());
        assertEquals(0.5, cropped.getCalibration().pixelWidth, 0.0);
        assertEquals(0.25, cropped.getCalibration().pixelHeight, 0.0);
        assertEquals(10.0, cropped.getDisplayRangeMin(), 0.0);
        assertEquals(200.0, cropped.getDisplayRangeMax(), 0.0);
        assertEquals(pixel(source, 2, 3, 2, 1, 1),
                pixel(cropped, 2, 3, 2, 0, 0), 0.0);
    }

    @Test
    public void cropToRoiClampsBoundsToImage() {
        ImagePlus source = hyperstack(5, 4, 1, 1, 1);

        ImagePlus cropped = RoiCropper.cropToRoi(source, new Roi(-1, -1, 3, 3));

        assertEquals(2, cropped.getWidth());
        assertEquals(2, cropped.getHeight());
        assertEquals(pixel(source, 1, 1, 1, 0, 0),
                pixel(cropped, 1, 1, 1, 0, 0), 0.0);
    }

    @Test
    public void cropToRoiRestoresSourceRoi() {
        ImagePlus source = hyperstack(5, 4, 1, 1, 1);
        Roi original = new Roi(0, 0, 1, 1);
        source.setRoi(original);

        RoiCropper.cropToRoi(source, new Roi(1, 1, 2, 2));

        Rectangle restored = source.getRoi().getBounds();
        assertEquals(original.getBounds(), restored);
    }

    @Test
    public void nullRoiReturnsSourceUnchanged() {
        ImagePlus source = hyperstack(5, 4, 1, 1, 1);

        assertSame(source, RoiCropper.cropToRoi(source, null));
    }

    @Test
    public void emptyClampedRoiThrows() {
        try {
            RoiCropper.cropToRoi(hyperstack(5, 4, 1, 1, 1), new Roi(6, 0, 2, 2));
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("ROI bounds are empty"));
        }
    }

    @Test
    public void sourcePreparerNarrowsCurrentTimepointAndReturnsDisplayCopy() {
        ImagePlus source = hyperstack(5, 4, 1, 2, 3);
        source.setPositionWithoutUpdate(1, 2, 3);
        source.setDisplayRange(2.0, 30.0);

        VariationSourcePreparer.PreparedSource prepared =
                VariationSourcePreparer.prepare(source, new Roi(1, 1, 2, 2));

        assertEquals(2, prepared.executionSource.getWidth());
        assertEquals(2, prepared.executionSource.getHeight());
        assertEquals(1, prepared.executionSource.getNFrames());
        assertEquals(1, prepared.displaySource.getNFrames());
        assertNotSame(prepared.executionSource, prepared.displaySource);
        assertEquals(pixel(source, 1, 2, 3, 1, 1),
                pixel(prepared.executionSource, 1, 2, 1, 0, 0), 0.0);
        assertEquals(pixel(prepared.executionSource, 1, 2, 1, 0, 0),
                pixel(prepared.displaySource, 1, 2, 1, 0, 0), 0.0);
        assertEquals(2.0, prepared.displaySource.getDisplayRangeMin(), 0.0);
        assertEquals(30.0, prepared.displaySource.getDisplayRangeMax(), 0.0);
    }

    @Test
    public void runReturnsDisplaySourceMatchingExecutedRoiTimepointSource() {
        ImagePlus source = hyperstack(5, 4, 1, 2, 3);
        source.setPositionWithoutUpdate(1, 2, 3);

        VariationRunResult run = VariationSourcePreparer.run(
                source,
                new Roi(1, 1, 2, 2),
                Collections.singletonList(new VariantPlan("identity",
                        identityDag(), Collections.<String, String>emptyMap())),
                null);

        try {
            VariantResult result = run.results.get(0);
            assertTrue("identity variant should execute successfully", result.error == null);
            assertTrue("identity variant should return an output image", result.output != null);
            assertEquals(2, run.displaySource.getWidth());
            assertEquals(2, run.displaySource.getHeight());
            assertEquals(1, run.displaySource.getNFrames());
            assertEquals(pixel(source, 1, 2, 3, 1, 1),
                    pixel(run.displaySource, 1, 2, 1, 0, 0), 0.0);
            assertEquals("raw display source and variant execution source must match",
                    pixel(run.displaySource, 1, 2, 1, 0, 0),
                    pixel(result.output, 1, 2, 1, 0, 0), 0.0);
        } finally {
            run.displaySource.flush();
            if (!run.results.isEmpty() && run.results.get(0).output != null) {
                run.results.get(0).output.flush();
            }
        }
    }

    private static ImagePlus hyperstack(int width, int height,
                                        int channels, int slices, int frames) {
        ImageStack stack = new ImageStack(width, height);
        for (int t = 1; t <= frames; t++) {
            for (int z = 1; z <= slices; z++) {
                for (int c = 1; c <= channels; c++) {
                    FloatProcessor fp = new FloatProcessor(width, height);
                    for (int y = 0; y < height; y++) {
                        for (int x = 0; x < width; x++) {
                            fp.setf(x, y, (float) (c * 1000 + z * 100 + t * 10 + y * width + x));
                        }
                    }
                    stack.addSlice(fp);
                }
            }
        }
        ImagePlus imp = new ImagePlus("hyper", stack);
        imp.setDimensions(channels, slices, frames);
        imp.setOpenAsHyperStack(true);
        return imp;
    }

    private static double pixel(ImagePlus imp, int channel, int slice,
                                int frame, int x, int y) {
        int index = imp.getStackIndex(channel, slice, frame);
        return imp.getStack().getProcessor(index).getf(x, y);
    }

    private static DagIR identityDag() {
        return new DagIR(1,
                Collections.singletonList(new DagLine("line_A",
                        Collections.emptyList())),
                Collections.<Combiner>emptyList(),
                "line_A",
                "native");
    }
}
