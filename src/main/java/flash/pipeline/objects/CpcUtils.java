package flash.pipeline.objects;

import ij.ImagePlus;
import ij.ImageStack;
import ij.process.ImageProcessor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Centre-Particle Coincidence utilities — self-contained implementation
 * of centroid-based colocalization for use within the IHF Pipeline.
 */
public final class CpcUtils {

    private CpcUtils() {}

    /** A single 3D object extracted from a label image. */
    public static class ObjectInfo {
        public final int label;
        public double cx, cy, cz;
        public int voxelCount;
        public int partnerLabel;

        public ObjectInfo(int label) {
            this.label = label;
        }

        public boolean isColocalized() {
            return partnerLabel > 0;
        }
    }

    /**
     * Extract all objects and compute geometric centroids from a label image.
     * Each unique non-zero pixel value is treated as a separate object.
     */
    public static List<ObjectInfo> extractObjects(ImagePlus img) {
        ImageStack stack = img.getStack();
        int w = img.getWidth();
        int h = img.getHeight();
        int nSlices = stack.getSize();

        Map<Integer, long[]> stats = new LinkedHashMap<Integer, long[]>();
        for (int z = 0; z < nSlices; z++) {
            ImageProcessor ip = stack.getProcessor(z + 1);
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int label = (int) ip.getf(x, y);
                    if (label <= 0) continue;
                    long[] s = stats.get(label);
                    if (s == null) {
                        s = new long[4];
                        stats.put(label, s);
                    }
                    s[0] += x;
                    s[1] += y;
                    s[2] += z;
                    s[3]++;
                }
            }
        }

        List<ObjectInfo> objects = new ArrayList<ObjectInfo>(stats.size());
        for (Map.Entry<Integer, long[]> entry : stats.entrySet()) {
            ObjectInfo obj = new ObjectInfo(entry.getKey());
            long[] s = entry.getValue();
            obj.voxelCount = (int) s[3];
            obj.cx = (double) s[0] / s[3];
            obj.cy = (double) s[1] / s[3];
            obj.cz = (double) s[2] / s[3];
            objects.add(obj);
        }
        return objects;
    }

    /**
     * For each object, look up the voxel value in the target label image
     * at the object's centroid position. Sets {@code partnerLabel}.
     */
    public static void testCoincidence(List<ObjectInfo> objects, ImagePlus targetImage) {
        ImageStack stack = targetImage.getStack();
        int w = targetImage.getWidth();
        int h = targetImage.getHeight();
        int nSlices = stack.getSize();

        for (ObjectInfo obj : objects) {
            int x = (int) Math.round(obj.cx);
            int y = (int) Math.round(obj.cy);
            int z = (int) Math.round(obj.cz);

            if (x >= 0 && x < w && y >= 0 && y < h && z >= 0 && z < nSlices) {
                obj.partnerLabel = (int) stack.getProcessor(z + 1).getf(x, y);
            }
        }
    }

    /** Deep copy object list so each pairwise test gets its own partnerLabel state. */
    public static List<ObjectInfo> copyObjects(List<ObjectInfo> originals) {
        List<ObjectInfo> copy = new ArrayList<ObjectInfo>(originals.size());
        for (ObjectInfo o : originals) {
            ObjectInfo c = new ObjectInfo(o.label);
            c.cx = o.cx;
            c.cy = o.cy;
            c.cz = o.cz;
            c.voxelCount = o.voxelCount;
            copy.add(c);
        }
        return copy;
    }
}
