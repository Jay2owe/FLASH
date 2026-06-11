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
 * of centroid-based colocalization for use within FLASH.
 */
public final class CpcUtils {

    private CpcUtils() {}

    /** A single 3D object extracted from a label image. */
    public static class ObjectInfo {
        public final int label;
        public double cx, cy, cz;
        public int voxelCount;
        public int partnerLabel;
        /** Inclusive voxel-index bounding box. Empty objects keep min &gt; max. */
        public int xmin = Integer.MAX_VALUE, xmax = Integer.MIN_VALUE;
        public int ymin = Integer.MAX_VALUE, ymax = Integer.MIN_VALUE;
        public int zmin = Integer.MAX_VALUE, zmax = Integer.MIN_VALUE;

        public ObjectInfo(int label) {
            this.label = label;
        }

        public boolean isColocalized() {
            return partnerLabel > 0;
        }

        /** True when no voxels have been recorded, so the bounding box is undefined. */
        public boolean isBoxEmpty() {
            return xmax < xmin || ymax < ymin || zmax < zmin;
        }

        /** Bounding-box volume in voxels (width*height*depth, inclusive); 0 when empty. */
        public long bbVolume() {
            if (isBoxEmpty()) return 0L;
            return (long) (xmax - xmin + 1) * (ymax - ymin + 1) * (zmax - zmin + 1);
        }

        /** True when point (px,py,pz) lies within the inclusive bounding box. */
        public boolean bbContains(double px, double py, double pz) {
            if (isBoxEmpty()) return false;
            return px >= xmin && px <= xmax
                    && py >= ymin && py <= ymax
                    && pz >= zmin && pz <= zmax;
        }

        /** Volume (voxels) of the intersection of this box with another; 0 when disjoint or empty. */
        public long bbIntersectionVolume(ObjectInfo o) {
            if (o == null || isBoxEmpty() || o.isBoxEmpty()) return 0L;
            long ox = Math.max(0, Math.min(xmax, o.xmax) - Math.max(xmin, o.xmin) + 1);
            long oy = Math.max(0, Math.min(ymax, o.ymax) - Math.max(ymin, o.ymin) + 1);
            long oz = Math.max(0, Math.min(zmax, o.zmax) - Math.max(zmin, o.zmin) + 1);
            return ox * oy * oz;
        }
    }

    /**
     * Extract all objects and compute geometric centroids from a label image.
     * Each unique non-zero pixel value is treated as a separate object.
     */
    public static List<ObjectInfo> extractObjects(ImagePlus img) {
        if (img == null || img.getStack() == null) return new ArrayList<ObjectInfo>();
        ImageStack stack = img.getStack();
        int w = img.getWidth();
        int h = img.getHeight();
        int nSlices = stack.getSize();

        // Per label: [0]=sumX [1]=sumY [2]=sumZ [3]=count [4]=xmin [5]=xmax [6]=ymin [7]=ymax [8]=zmin [9]=zmax
        Map<Integer, long[]> stats = new LinkedHashMap<Integer, long[]>();
        for (int z = 0; z < nSlices; z++) {
            ImageProcessor ip = stack.getProcessor(z + 1);
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int label = labelFromPixel(ip.getf(x, y));
                    if (label <= 0) continue;
                    long[] s = stats.get(label);
                    if (s == null) {
                        s = new long[10];
                        s[4] = Long.MAX_VALUE; s[5] = Long.MIN_VALUE;
                        s[6] = Long.MAX_VALUE; s[7] = Long.MIN_VALUE;
                        s[8] = Long.MAX_VALUE; s[9] = Long.MIN_VALUE;
                        stats.put(label, s);
                    }
                    s[0] += x;
                    s[1] += y;
                    s[2] += z;
                    s[3]++;
                    if (x < s[4]) s[4] = x;
                    if (x > s[5]) s[5] = x;
                    if (y < s[6]) s[6] = y;
                    if (y > s[7]) s[7] = y;
                    if (z < s[8]) s[8] = z;
                    if (z > s[9]) s[9] = z;
                }
            }
        }

        List<ObjectInfo> objects = new ArrayList<ObjectInfo>(stats.size());
        for (Map.Entry<Integer, long[]> entry : stats.entrySet()) {
            ObjectInfo obj = new ObjectInfo(entry.getKey());
            long[] s = entry.getValue();
            obj.voxelCount = s[3] > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) s[3];
            obj.cx = (double) s[0] / s[3];
            obj.cy = (double) s[1] / s[3];
            obj.cz = (double) s[2] / s[3];
            obj.xmin = (int) s[4];
            obj.xmax = (int) s[5];
            obj.ymin = (int) s[6];
            obj.ymax = (int) s[7];
            obj.zmin = (int) s[8];
            obj.zmax = (int) s[9];
            objects.add(obj);
        }
        return objects;
    }

    /**
     * For each object, look up the voxel value in the target label image
     * at the object's centroid position. Sets {@code partnerLabel}.
     */
    public static void testCoincidence(List<ObjectInfo> objects, ImagePlus targetImage) {
        if (objects == null || targetImage == null || targetImage.getStack() == null) return;
        ImageStack stack = targetImage.getStack();
        int w = targetImage.getWidth();
        int h = targetImage.getHeight();
        int nSlices = stack.getSize();

        for (ObjectInfo obj : objects) {
            int x = (int) Math.round(obj.cx);
            int y = (int) Math.round(obj.cy);
            int z = (int) Math.round(obj.cz);

            if (x >= 0 && x < w && y >= 0 && y < h && z >= 0 && z < nSlices) {
                obj.partnerLabel = labelFromPixel(stack.getProcessor(z + 1).getf(x, y));
            }
        }
    }

    /**
     * Geometric analogue of {@link #testCoincidence}: for each source object set {@code partnerLabel}
     * to a partner object whose <em>bounding box</em> contains the source centroid (0 if none).
     * Because a box ⊇ its object, this is strictly more permissive than voxel-level coincidence, so
     * a source flagged by {@link #testCoincidence} is always flagged here too.
     */
    public static void testBoundingBoxCoincidence(List<ObjectInfo> sources, List<ObjectInfo> partners) {
        if (sources == null || partners == null) return;
        for (ObjectInfo s : sources) {
            s.partnerLabel = 0;
            for (ObjectInfo p : partners) {
                if (p.bbContains(s.cx, s.cy, s.cz)) {
                    s.partnerLabel = p.label;
                    break;
                }
            }
        }
    }

    /**
     * For each source object, count how many partner centroids fall inside the source bounding box.
     * Returned map is keyed by source label (centroids may lie in several overlapping boxes, so this
     * is computed per source rather than by partner-label bouncing).
     */
    public static Map<Integer, Integer> countCentroidsInBoxes(List<ObjectInfo> sources,
                                                              List<ObjectInfo> partners) {
        Map<Integer, Integer> counts = new LinkedHashMap<Integer, Integer>();
        if (sources == null) return counts;
        for (ObjectInfo s : sources) {
            int c = 0;
            if (partners != null && !s.isBoxEmpty()) {
                for (ObjectInfo p : partners) {
                    if (s.bbContains(p.cx, p.cy, p.cz)) c++;
                }
            }
            counts.put(s.label, c);
        }
        return counts;
    }

    /** Deep copy object list so each pairwise test gets its own partnerLabel state. */
    public static List<ObjectInfo> copyObjects(List<ObjectInfo> originals) {
        if (originals == null) return new ArrayList<ObjectInfo>();
        List<ObjectInfo> copy = new ArrayList<ObjectInfo>(originals.size());
        for (ObjectInfo o : originals) {
            ObjectInfo c = new ObjectInfo(o.label);
            c.cx = o.cx;
            c.cy = o.cy;
            c.cz = o.cz;
            c.voxelCount = o.voxelCount;
            c.xmin = o.xmin;
            c.xmax = o.xmax;
            c.ymin = o.ymin;
            c.ymax = o.ymax;
            c.zmin = o.zmin;
            c.zmax = o.zmax;
            copy.add(c);
        }
        return copy;
    }

    private static int labelFromPixel(float value) {
        if (!Float.isFinite(value) || value <= 0f) return 0;
        return value > Integer.MAX_VALUE ? 0 : Math.round(value);
    }
}
