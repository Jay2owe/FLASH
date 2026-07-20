package flash.pipeline.objects;

import ij.ImagePlus;
import ij.ImageStack;
import ij.process.ShortProcessor;
import org.junit.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * Voxel-precise marker-centroid containment counting used to resolve fused/clustered objects.
 * {@link CpcUtils#countCentroidsInLabels} tallies how many marker centroids land on each target
 * object's voxels (no tolerance) — the primitive behind the {@code *_OverlapCount_*} column.
 */
public class CpcUtilsCentroidCountTest {

    private static ImagePlus single2D(int w, int h, int[] pix, String title) {
        ImageStack stack = new ImageStack(w, h);
        ShortProcessor sp = new ShortProcessor(w, h);
        for (int i = 0; i < pix.length; i++) sp.set(i, pix[i]);
        stack.addSlice(sp);
        return new ImagePlus(title, stack);
    }

    private static CpcUtils.ObjectInfo marker(int label, double x, double y, double z) {
        CpcUtils.ObjectInfo o = new CpcUtils.ObjectInfo(label);
        o.cx = x; o.cy = y; o.cz = z;
        return o;
    }

    @Test
    public void countsMarkerCentroidsInsideTargetVoxels() {
        // 8x8 target: object 1 fills the block x[1..4]y[1..4]; object 2 fills x[6..7]y[6..7].
        int w = 8, h = 8;
        int[] target = new int[w * h];
        for (int y = 1; y <= 4; y++) for (int x = 1; x <= 4; x++) target[y * w + x] = 1;
        for (int y = 6; y <= 7; y++) for (int x = 6; x <= 7; x++) target[y * w + x] = 2;
        ImagePlus targetImg = single2D(w, h, target, "target");

        List<CpcUtils.ObjectInfo> markers = new ArrayList<CpcUtils.ObjectInfo>();
        markers.add(marker(10, 2, 2, 0));      // inside object 1
        markers.add(marker(11, 3, 4, 0));      // inside object 1 -> object 1 is a fused cluster of 2
        markers.add(marker(12, 6, 6, 0));      // inside object 2
        markers.add(marker(13, 0, 0, 0));      // on background -> not counted
        markers.add(marker(14, 100, 100, 0));  // outside image bounds -> not counted

        Map<Integer, Integer> counts = CpcUtils.countCentroidsInLabels(markers, targetImg);

        assertEquals(Integer.valueOf(2), counts.get(1));
        assertEquals(Integer.valueOf(1), counts.get(2));
        assertNull("background label is never a key", counts.get(0));
    }

    @Test
    public void roundsCentroidLikeTestCoincidence() {
        // Centroid x=3.4 rounds to voxel 3 (inside object 1); x=4.6 rounds to 5 (background).
        int w = 8, h = 8;
        int[] target = new int[w * h];
        for (int y = 1; y <= 4; y++) for (int x = 1; x <= 4; x++) target[y * w + x] = 1;
        ImagePlus targetImg = single2D(w, h, target, "target");

        List<CpcUtils.ObjectInfo> markers = new ArrayList<CpcUtils.ObjectInfo>();
        markers.add(marker(1, 3.4, 2.0, 0.0));  // -> (3,2) inside
        markers.add(marker(2, 4.6, 2.0, 0.0));  // -> (5,2) background

        Map<Integer, Integer> counts = CpcUtils.countCentroidsInLabels(markers, targetImg);
        assertEquals(Integer.valueOf(1), counts.get(1));
    }

    @Test
    public void matchesCpcContainsViaTestCoincidence() {
        // Cross-validate against the existing CPC containment path: extracting marker objects,
        // running testCoincidence against the target, and tallying partnerLabels must equal
        // countCentroidsInLabels for the same inputs (the dedicated helper is a clean encapsulation).
        int w = 8, h = 8;
        int[] target = new int[w * h];
        for (int y = 1; y <= 5; y++) for (int x = 1; x <= 5; x++) target[y * w + x] = 7;
        ImagePlus targetImg = single2D(w, h, target, "target");

        int[] markerPix = new int[w * h];
        markerPix[2 * w + 2] = 1;   // centroid (2,2) inside target 7
        markerPix[3 * w + 3] = 2;   // centroid (3,3) inside target 7
        markerPix[7 * w + 7] = 3;   // centroid (7,7) background
        ImagePlus markerImg = single2D(w, h, markerPix, "marker");

        List<CpcUtils.ObjectInfo> markers = CpcUtils.extractObjects(markerImg);
        Map<Integer, Integer> mine = CpcUtils.countCentroidsInLabels(markers, targetImg);

        List<CpcUtils.ObjectInfo> copy = CpcUtils.copyObjects(markers);
        CpcUtils.testCoincidence(copy, targetImg);
        Map<Integer, Integer> viaCpc = new LinkedHashMap<Integer, Integer>();
        for (CpcUtils.ObjectInfo o : copy) {
            if (o.partnerLabel > 0) {
                Integer prev = viaCpc.get(o.partnerLabel);
                viaCpc.put(o.partnerLabel, (prev != null ? prev : 0) + 1);
            }
        }
        assertEquals(viaCpc, mine);
        assertEquals(Integer.valueOf(2), mine.get(7));
    }

    @Test
    public void nullAndEmptyInputsAreSafe() {
        ImagePlus targetImg = single2D(4, 4, new int[16], "empty-target");
        assertEquals(0, CpcUtils.countCentroidsInLabels(null, targetImg).size());
        assertEquals(0, CpcUtils.countCentroidsInLabels(new ArrayList<CpcUtils.ObjectInfo>(), null).size());
        // All-background target: a real marker centroid lands on label 0, so nothing is counted.
        List<CpcUtils.ObjectInfo> markers = new ArrayList<CpcUtils.ObjectInfo>();
        markers.add(marker(1, 2, 2, 0));
        assertEquals(0, CpcUtils.countCentroidsInLabels(markers, targetImg).size());
    }
}
