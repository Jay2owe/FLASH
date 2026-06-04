package flash.pipeline.roi;

import ij.ImagePlus;
import ij.ImageStack;
import ij.gui.OvalRoi;
import ij.gui.Roi;
import ij.process.ByteProcessor;
import ij.process.ImageProcessor;
import ij.process.ShortProcessor;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Tests for {@link RegionMask}, the single source of truth for applying a tissue
 * ROI during 3D Object Analysis.
 *
 * <p>These lock in the fix for the historical bug where the top-left-shifted
 * index-1 ("cropped") ROI drove geometry instead of the original-coordinate
 * index-0 ROI:
 * <ul>
 *   <li>The centroid filter (run on a FULL-image label map) kept top-left junk
 *       and discarded the real in-region objects.</li>
 *   <li>The clear-outside mask was double-shifted (the masker re-derives the
 *       cropped frame itself) and erased ~99% of non-rectangular tissue.</li>
 * </ul>
 * Both failures only appear for non-rectangular ROIs (oval/polygon/freehand),
 * which is exactly what tissue tracing produces.
 */
public class RegionMaskTest {

    // 100x80 frame, region bounding box (30,20)-(70,45).
    private static final int W = 100;
    private static final int H = 80;
    private static final Roi REGION = new Roi(30, 20, 40, 25);

    private static ImagePlus labelImage(int[] labelAtXY) {
        ImageStack stack = new ImageStack(W, H);
        ShortProcessor sp = new ShortProcessor(W, H);
        for (int i = 0; i < labelAtXY.length; i++) sp.set(i, labelAtXY[i]);
        stack.addSlice(sp);
        return new ImagePlus("labels", stack);
    }

    /** Stamp a solid w x h block of {@code label} with top-left at (x,y). */
    private static void block(int[] px, int x, int y, int w, int h, int label) {
        for (int yy = y; yy < y + h; yy++)
            for (int xx = x; xx < x + w; xx++)
                px[yy * W + xx] = label;
    }

    private static int nonZero(ImagePlus imp) {
        ImageProcessor ip = imp.getProcessor();
        int n = 0;
        for (int i = 0; i < ip.getPixelCount(); i++) if (ip.get(i) != 0) n++;
        return n;
    }

    private static ImagePlus filledByte() {
        ByteProcessor bp = new ByteProcessor(W, H);
        bp.setColor(255);
        bp.fill();
        return new ImagePlus("filled", bp);
    }

    /** Approximate interior pixel count of an oval bounding box w x h. */
    private static int ovalArea(int w, int h) {
        OvalRoi o = new OvalRoi(0, 0, w, h);
        int a = 0;
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++)
                if (o.contains(x, y)) a++;
        return a;
    }

    @Test
    public void centroidFilterKeepsInRegionDropsCornerAndOutside() {
        int[] px = new int[W * H];
        block(px, 48, 30, 4, 4, 1);   // A: centroid ~ (49.5,31.5) — inside REGION
        block(px, 8, 8, 4, 4, 2);     // B: top-left corner — OUTSIDE REGION
        block(px, 83, 58, 4, 4, 3);   // C: bottom-right — OUTSIDE REGION
        ImagePlus img = labelImage(px);

        int removed = RegionMask.from(REGION).filterByCentroid(img);

        assertEquals("two out-of-region objects removed", 2, removed);
        ImageProcessor ip = img.getProcessor();
        assertEquals("A (in-region) survives", 1, ip.get(49 + 31 * W));
        assertEquals("B (corner) removed", 0, ip.get(9 + 9 * W));
        assertEquals("C (outside) removed", 0, ip.get(84 + 59 * W));
        assertEquals("count after filter", 1, RegionMask.countLabels(img));
    }

    /**
     * Documents the trap: feeding the top-left-shifted index-1 ROI to the
     * centroid filter on a full-image map does the WRONG thing — it keeps the
     * corner object and discards the real in-region object. RegionMask makes
     * this impossible by only ever using the original-coordinate ROI.
     */
    @Test
    public void shiftedRoiMisfiltersTheFullImageMap() {
        int[] px = new int[W * H];
        block(px, 48, 30, 4, 4, 1);   // A: inside the true region
        block(px, 8, 8, 4, 4, 2);     // B: top-left corner
        ImagePlus img = labelImage(px);

        // index-1 style ROI: same size, shifted so its bbox sits at (0,0).
        Roi shifted = new Roi(0, 0, 40, 25);
        int removed = RegionMask.filterLabelsByCentroid(img, shifted);

        ImageProcessor ip = img.getProcessor();
        assertEquals("wrong ROI keeps the corner object", 2, ip.get(9 + 9 * W));
        assertEquals("wrong ROI discards the real in-region object", 0, ip.get(49 + 31 * W));
        assertEquals("only the in-region object was (wrongly) removed", 1, removed);
    }

    @Test
    public void cropToBoundsRebasesCoordinatesAndKeepsObjects() {
        int[] px = new int[W * H];
        block(px, 40, 30, 3, 3, 7);   // object fully inside the region
        ImagePlus img = labelImage(px);

        RegionMask.from(REGION).cropToBounds(img);

        assertEquals("cropped to region bbox width", 40, img.getWidth());
        assertEquals("cropped to region bbox height", 25, img.getHeight());
        ImageProcessor ip = img.getProcessor();
        // (40,30) -> (40-30, 30-20) = (10,10) in the region-relative frame.
        assertEquals("object re-based to region-relative coords", 7, ip.get(10 + 10 * 40));
        assertEquals("object preserved across crop", 1, RegionMask.countLabels(img));
    }

    @Test
    public void cropAndMaskNonRectNotAtOriginPreservesTissue() {
        // Regression for the ~99% wipeout: a non-rectangular ROI not at the origin
        // must keep roughly its own area, not collapse to a sliver.
        ImagePlus imp = filledByte();
        RegionMask.from(new OvalRoi(30, 20, 40, 25)).cropAndMask(imp);

        assertEquals(40, imp.getWidth());
        assertEquals(25, imp.getHeight());
        int surviving = nonZero(imp);
        int expected = ovalArea(40, 25);   // ~788
        assertTrue("tissue not wiped out (got " + surviving + ")", surviving > expected * 0.8);
        assertTrue("masked outside the oval (got " + surviving + ")", surviving < 40 * 25);
    }

    /**
     * Documents the original double-shift failure: passing the top-left-shifted
     * ROI as the clear-outside shape erases almost all of the tissue, because
     * {@link RoiOps#removeNonRoiThreadSafe} re-derives the cropped frame itself.
     */
    @Test
    public void shiftedClearOutsideRoiWipesOutTissue() {
        ImagePlus imp = filledByte();
        Roi original = new OvalRoi(30, 20, 40, 25);
        Roi shiftedToTopLeft = new OvalRoi(0, 0, 40, 25);   // index-1 style
        RoiOps.removeNonRoiThreadSafe(imp, original, shiftedToTopLeft);
        assertTrue("shifted clear-outside ROI wipes tissue (got " + nonZero(imp) + ")",
                nonZero(imp) < 50);
    }

    @Test
    public void nullHandling() {
        assertNull("null original ROI yields no mask", RegionMask.from(null));
        assertEquals(0, RegionMask.from(REGION).filterByCentroid(null));
        assertEquals(0, RegionMask.countLabels(null));
        // crop calls are no-ops on null images (must not throw)
        RegionMask.from(REGION).cropToBounds(null);
        RegionMask.from(REGION).cropAndMask(null);
    }
}
