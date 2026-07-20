package flash.pipeline.image;

import ij.ImagePlus;
import ij.ImageStack;
import ij.gui.OvalRoi;
import ij.gui.Roi;
import ij.process.ByteProcessor;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * Guards that Intensity Analysis measures within the ROI correctly:
 * the ROI is applied (as a shape mask) to the FULL, uncropped image in
 * ORIGINAL coordinates. This is why Intensity Analysis is not exposed to the
 * index-1 ("cropped", top-left-shifted) ROI trap — it never crops, so the
 * original-coordinate index-0 ROI lines up with the full-size image.
 *
 * <p>The fixture places three bright blocks so the result distinguishes:
 * <ul>
 *   <li>correct: ROI applied at its true location, shape honored,</li>
 *   <li>bounding-box-only (mask ignored),</li>
 *   <li>and the shifted-to-(0,0) ROI bug.</li>
 * </ul>
 */
public class ThreadSafeMeasureRoiTest {

    private static final int W = 100;
    private static final int H = 80;

    private static void block(ByteProcessor bp, int x0, int y0, int w, int h, int v) {
        for (int y = y0; y < y0 + h; y++)
            for (int x = x0; x < x0 + w; x++)
                bp.set(x, y, v);
    }

    private static ImagePlus singleSlice(ByteProcessor bp) {
        ImageStack st = new ImageStack(W, H);
        st.addSlice(bp);
        return new ImagePlus("img", st);
    }

    @Test
    public void measuresWithinRoiShapeAtOriginalLocation() {
        ByteProcessor bp = new ByteProcessor(W, H);
        // 6x6 block fully inside the oval interior -> the ONLY pixels that should count.
        block(bp, 47, 29, 6, 6, 100);   // 36 px * 100 = 3600
        // 4x4 block inside the oval BOUNDING BOX but outside the oval SHAPE.
        block(bp, 31, 21, 4, 4, 50);    // counted only if the mask is ignored (bbox-only)
        // 4x4 block that lands inside the (wrong) top-left-shifted oval at (0,0).
        block(bp, 12, 8, 4, 4, 25);     // counted only if the ROI were shifted to (0,0)

        ImagePlus img = singleSlice(bp);
        Roi oval = new OvalRoi(30, 20, 40, 25);   // index-0, original coordinates

        ThreadSafeMeasure.SliceResult r =
                ThreadSafeMeasure.measureSlice(img, img, null, 1, oval);

        // Correct behaviour: only the in-shape block at the true location counts.
        // (bbox-only would give 4400; shifted-to-origin would give a different value.)
        assertEquals("IntDen restricted to ROI shape at its real location",
                3600.0, r.intDenFilteredFullRoi, 1.0);
        assertEquals("raw IntDen restricted identically",
                3600.0, r.intDenUnfilteredFullRoi, 1.0);
    }

    @Test
    public void shiftedRoiWouldMeasureTheWrongRegion() {
        // Documents the trap: the top-left-shifted index-1 ROI would measure a
        // different region of the same full image. Intensity Analysis avoids this
        // by always using the index-0 (original-coordinate) ROI.
        ByteProcessor bp = new ByteProcessor(W, H);
        block(bp, 47, 29, 6, 6, 100);   // 3600: inside the TRUE oval only
        block(bp, 12, 8, 4, 4, 25);     // 400:  inside the SHIFTED oval only

        ImagePlus img = singleSlice(bp);
        Roi shifted = new OvalRoi(0, 0, 40, 25);   // index-1 style

        ThreadSafeMeasure.SliceResult r =
                ThreadSafeMeasure.measureSlice(img, img, null, 1, shifted);

        // The shifted ROI measures the decoy region (400), never the real signal (3600).
        assertEquals("shifted ROI measures the wrong region",
                400.0, r.intDenFilteredFullRoi, 1.0);
    }
}
