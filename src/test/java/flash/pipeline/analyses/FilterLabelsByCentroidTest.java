package flash.pipeline.analyses;

import ij.ImagePlus;
import ij.ImageStack;
import ij.gui.Roi;
import ij.process.ShortProcessor;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Tests for ThreeDObjectAnalysis.filterLabelsByCentroid.
 * Guards against regression: StarDist must run before ROI crop, and objects
 * outside the ROI must be removed by centroid check — not by the hard edge
 * artifacts that occur when zeroing the image before detection.
 */
public class FilterLabelsByCentroidTest {

    /** Create a 16-bit label image with given width, height, and one slice. */
    private static ImagePlus makeLabelImage(int w, int h, int[][] pixels) {
        ImageStack stack = new ImageStack(w, h);
        for (int[] slicePixels : pixels) {
            ShortProcessor sp = new ShortProcessor(w, h);
            for (int i = 0; i < slicePixels.length; i++) {
                sp.set(i, slicePixels[i]);
            }
            stack.addSlice(sp);
        }
        return new ImagePlus("labels", stack);
    }

    @Test
    public void removesLabelsOutsideRoi() {
        // 10x10 image, ROI covers left half (x=0..4)
        // Label 1 centroid at (2,2) — inside ROI
        // Label 2 centroid at (7,2) — outside ROI
        int w = 10, h = 10;
        int[] slice = new int[w * h];
        // Label 1: 3x3 block at (1,1)
        for (int y = 1; y <= 3; y++)
            for (int x = 1; x <= 3; x++)
                slice[y * w + x] = 1;
        // Label 2: 3x3 block at (6,1)
        for (int y = 1; y <= 3; y++)
            for (int x = 6; x <= 8; x++)
                slice[y * w + x] = 2;

        ImagePlus img = makeLabelImage(w, h, new int[][] { slice });
        Roi roi = new Roi(0, 0, 5, 10); // left half

        int removed = ThreeDObjectAnalysis.filterLabelsByCentroid(img, roi);

        assertEquals("one label should be removed", 1, removed);

        // Label 1 should still exist, label 2 should be zeroed
        ShortProcessor sp = (ShortProcessor) img.getStack().getProcessor(1);
        assertEquals("label 1 pixel should survive", 1, sp.get(2 + 2 * w));
        assertEquals("label 2 pixel should be zeroed", 0, sp.get(7 + 2 * w));
    }

    @Test
    public void keepsAllLabelsInsideRoi() {
        int w = 10, h = 10;
        int[] slice = new int[w * h];
        // Label 1 at (2,2), Label 2 at (3,5) — both inside a full-image ROI
        for (int y = 1; y <= 3; y++)
            for (int x = 1; x <= 3; x++)
                slice[y * w + x] = 1;
        for (int y = 4; y <= 6; y++)
            for (int x = 2; x <= 4; x++)
                slice[y * w + x] = 2;

        ImagePlus img = makeLabelImage(w, h, new int[][] { slice });
        Roi roi = new Roi(0, 0, 10, 10); // full image

        int removed = ThreeDObjectAnalysis.filterLabelsByCentroid(img, roi);

        assertEquals("no labels should be removed", 0, removed);
    }

    @Test
    public void handlesNullInputsGracefully() {
        assertEquals(0, ThreeDObjectAnalysis.filterLabelsByCentroid(null, new Roi(0, 0, 10, 10)));
        int[] slice = new int[100];
        ImagePlus img = makeLabelImage(10, 10, new int[][] { slice });
        assertEquals(0, ThreeDObjectAnalysis.filterLabelsByCentroid(img, null));
    }

    @Test
    public void worksAcrossMultipleZSlices() {
        // Label 1 spans slices 1-3 at (2,2) — inside ROI
        // Label 2 spans slices 1-3 at (8,8) — outside ROI
        int w = 10, h = 10;
        int[][] slices = new int[3][w * h];
        for (int s = 0; s < 3; s++) {
            // Label 1
            for (int y = 1; y <= 3; y++)
                for (int x = 1; x <= 3; x++)
                    slices[s][y * w + x] = 1;
            // Label 2
            for (int y = 7; y <= 9; y++)
                for (int x = 7; x <= 9; x++)
                    slices[s][y * w + x] = 2;
        }

        ImagePlus img = makeLabelImage(w, h, slices);
        Roi roi = new Roi(0, 0, 6, 6); // top-left quadrant

        int removed = ThreeDObjectAnalysis.filterLabelsByCentroid(img, roi);

        assertEquals("label 2 should be removed", 1, removed);

        // Verify all slices had label 2 zeroed
        for (int s = 1; s <= 3; s++) {
            ShortProcessor sp = (ShortProcessor) img.getStack().getProcessor(s);
            assertEquals("label 1 should survive on slice " + s, 1, sp.get(2 + 2 * w));
            assertEquals("label 2 should be zeroed on slice " + s, 0, sp.get(8 + 8 * w));
        }
    }

    @Test
    public void emptyImageReturnsZero() {
        int[] slice = new int[100]; // all zeros
        ImagePlus img = makeLabelImage(10, 10, new int[][] { slice });
        int removed = ThreeDObjectAnalysis.filterLabelsByCentroid(img, new Roi(0, 0, 5, 5));
        assertEquals(0, removed);
    }

    @Test
    public void centroidOnBoundaryIsKept() {
        // Label with centroid exactly at ROI edge (x=4) with ROI width=5 (x=0..4)
        int w = 10, h = 10;
        int[] slice = new int[w * h];
        // Single pixel at (4, 5) — exactly on ROI boundary
        slice[5 * w + 4] = 1;

        ImagePlus img = makeLabelImage(w, h, new int[][] { slice });
        Roi roi = new Roi(0, 0, 5, 10); // x=0..4 inclusive

        int removed = ThreeDObjectAnalysis.filterLabelsByCentroid(img, roi);
        assertEquals("centroid on boundary should be kept", 0, removed);
    }
}
