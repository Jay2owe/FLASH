package flash.pipeline.objects;

import ij.ImagePlus;
import ij.ImageStack;
import ij.process.ByteProcessor;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Stage 01 foundations: {@link CpcUtils.ObjectInfo} bounding-box extraction and geometry helpers.
 */
public class CpcUtilsBoundingBoxTest {

    /** Builds a 6x6x3 label stack with two overlapping cuboids and checks the BB fields. */
    @Test
    public void extractObjectsComputesBoundingBoxExtents() {
        ImagePlus img = twoCuboids();
        List<CpcUtils.ObjectInfo> objects = CpcUtils.extractObjects(img);
        assertEquals(2, objects.size());

        CpcUtils.ObjectInfo a = byLabel(objects, 1);
        CpcUtils.ObjectInfo b = byLabel(objects, 2);

        // Object 1: x[1..3] y[1..2] z[0..1]
        assertEquals(1, a.xmin);
        assertEquals(3, a.xmax);
        assertEquals(1, a.ymin);
        assertEquals(2, a.ymax);
        assertEquals(0, a.zmin);
        assertEquals(1, a.zmax);
        assertEquals(3L * 2L * 2L, a.bbVolume());

        // Object 2: x[2..4] y[2..3] z[1..2]
        assertEquals(2, b.xmin);
        assertEquals(4, b.xmax);
        assertEquals(2, b.ymin);
        assertEquals(3, b.ymax);
        assertEquals(1, b.zmin);
        assertEquals(2, b.zmax);
        assertEquals(3L * 2L * 2L, b.bbVolume());
    }

    @Test
    public void bbContainsRespectsInclusiveBounds() {
        CpcUtils.ObjectInfo a = byLabel(CpcUtils.extractObjects(twoCuboids()), 1);
        assertTrue(a.bbContains(2, 2, 1));   // interior
        assertTrue(a.bbContains(1, 1, 0));   // corner is inclusive
        assertTrue(a.bbContains(3, 2, 1));   // far corner inclusive
        assertFalse(a.bbContains(4, 2, 1));  // x past xmax
        assertFalse(a.bbContains(2, 2, 2));  // z past zmax
        assertFalse(a.bbContains(5, 5, 5));  // far outside
    }

    @Test
    public void bbIntersectionVolumeMatchesOverlapRegion() {
        List<CpcUtils.ObjectInfo> objects = CpcUtils.extractObjects(twoCuboids());
        CpcUtils.ObjectInfo a = byLabel(objects, 1);
        CpcUtils.ObjectInfo b = byLabel(objects, 2);
        // x: [2..3]=2, y: [2..2]=1, z: [1..1]=1 -> 2 voxels, symmetric.
        assertEquals(2L, a.bbIntersectionVolume(b));
        assertEquals(2L, b.bbIntersectionVolume(a));
    }

    @Test
    public void emptyBoxHelpersAreSafe() {
        CpcUtils.ObjectInfo empty = new CpcUtils.ObjectInfo(99);
        assertTrue(empty.isBoxEmpty());
        assertEquals(0L, empty.bbVolume());
        assertFalse(empty.bbContains(0, 0, 0));
        CpcUtils.ObjectInfo a = byLabel(CpcUtils.extractObjects(twoCuboids()), 1);
        assertEquals(0L, empty.bbIntersectionVolume(a));
        assertEquals(0L, a.bbIntersectionVolume(empty));
    }

    @Test
    public void copyObjectsCarriesBoundingBox() {
        List<CpcUtils.ObjectInfo> objects = CpcUtils.extractObjects(twoCuboids());
        List<CpcUtils.ObjectInfo> copy = CpcUtils.copyObjects(objects);
        CpcUtils.ObjectInfo a = byLabel(objects, 1);
        CpcUtils.ObjectInfo ca = byLabel(copy, 1);
        assertEquals(a.xmin, ca.xmin);
        assertEquals(a.xmax, ca.xmax);
        assertEquals(a.ymin, ca.ymin);
        assertEquals(a.ymax, ca.ymax);
        assertEquals(a.zmin, ca.zmin);
        assertEquals(a.zmax, ca.zmax);
        assertEquals(a.bbVolume(), ca.bbVolume());
    }

    private static ImagePlus twoCuboids() {
        int w = 6, h = 6, nz = 3;
        ImageStack stack = new ImageStack(w, h);
        for (int z = 0; z < nz; z++) {
            stack.addSlice(new ByteProcessor(w, h));
        }
        // Object 1: x[1..3] y[1..2] z[0..1]
        fill(stack, 1, 3, 1, 2, 0, 1, 1);
        // Object 2: x[2..4] y[2..3] z[1..2]. It is written second, so it overwrites the few shared
        // voxels at z=1; object 1's box extremes are still anchored by its full z=0 plane, so both
        // bounding boxes remain as declared above.
        fill(stack, 2, 4, 2, 3, 1, 2, 2);
        return new ImagePlus("two-cuboids", stack);
    }

    private static void fill(ImageStack stack, int x0, int x1, int y0, int y1,
                             int z0, int z1, int label) {
        for (int z = z0; z <= z1; z++) {
            ByteProcessor ip = (ByteProcessor) stack.getProcessor(z + 1);
            for (int y = y0; y <= y1; y++) {
                for (int x = x0; x <= x1; x++) {
                    ip.set(x, y, label);
                }
            }
        }
    }

    private static CpcUtils.ObjectInfo byLabel(List<CpcUtils.ObjectInfo> objects, int label) {
        for (CpcUtils.ObjectInfo o : objects) {
            if (o.label == label) return o;
        }
        assertNotNull("missing label " + label, null);
        return null;
    }
}
