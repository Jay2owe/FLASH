package flash.pipeline.spatial;

import ij.ImagePlus;
import ij.ImageStack;
import ij.process.ImageProcessor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Extracts 2D morphological features from saved label images (object maps).
 *
 * <p>Works entirely from label images stored in {@code Image Analysis/<animal>/}
 * — no raw fluorescence images needed for shape features. Each unique non-zero
 * label's XY footprint is accumulated independently across all z-slices (so that
 * two labels whose voxel columns overlap at different z don't conflate into one),
 * then measured in 2D.
 *
 * <p>Features extracted per object:
 * <ul>
 *   <li>Area (pixels and calibrated)</li>
 *   <li>Perimeter</li>
 *   <li>Circularity = 4π × area / perimeter²</li>
 *   <li>Convex hull area</li>
 *   <li>Solidity = area / convex hull area</li>
 *   <li>Bounding box aspect ratio</li>
 *   <li>Feret diameter (max caliper)</li>
 *   <li>Extent = area / bounding box area</li>
 * </ul>
 */
public final class MorphologyExtractor {

    private MorphologyExtractor() {}

    /** Morphology features for a single object. */
    public static final class ObjectMorphology {
        public final int label;
        public final double area;
        public final double areaUm2;
        public final double perimeter;
        public final double circularity;
        public final double convexHullArea;
        public final double solidity;
        public final double aspectRatio;
        public final double feretDiameter;
        public final double extent;
        public final int boundingBoxWidth;
        public final int boundingBoxHeight;

        public ObjectMorphology(int label, double area, double areaUm2, double perimeter,
                                double circularity, double convexHullArea, double solidity,
                                double aspectRatio, double feretDiameter, double extent,
                                int bbWidth, int bbHeight) {
            this.label = label;
            this.area = area;
            this.areaUm2 = areaUm2;
            this.perimeter = perimeter;
            this.circularity = circularity;
            this.convexHullArea = convexHullArea;
            this.solidity = solidity;
            this.aspectRatio = aspectRatio;
            this.feretDiameter = feretDiameter;
            this.extent = extent;
            this.boundingBoxWidth = bbWidth;
            this.boundingBoxHeight = bbHeight;
        }
    }

    /**
     * Extracts morphology features from a label image.
     *
     * @param labelImage label image (each non-zero value = one object)
     * @param pixelSize  microns per pixel (for calibrated area)
     * @return list of per-object morphology features
     */
    public static List<ObjectMorphology> extract(ImagePlus labelImage, double pixelSize) {
        if (labelImage == null) return new ArrayList<ObjectMorphology>();
        if (pixelSize <= 0 || Double.isNaN(pixelSize) || Double.isInfinite(pixelSize)) pixelSize = 1.0;

        int w = labelImage.getWidth();
        int h = labelImage.getHeight();

        // Per-label XY footprint accumulated across all z-slices. Pixel coords are packed
        // as (long y * w + x). Using a Set deduplicates the case where the same label's
        // voxel column spans multiple z-slices, while keeping each label's footprint
        // independent of any other label's z-range. (Max-projection conflates two labels
        // that occupy the same XY column at different z, silently zeroing one of them.)
        Map<Integer, Set<Long>> footprints = new LinkedHashMap<Integer, Set<Long>>();
        ImageStack stack = labelImage.getImageStack();
        int nSlices = stack.getSize();
        for (int z = 1; z <= nSlices; z++) {
            ImageProcessor ip = stack.getProcessor(z);
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int label = labelFromPixel(ip.getf(x, y));
                    if (label <= 0) continue;
                    Set<Long> set = footprints.get(label);
                    if (set == null) {
                        set = new HashSet<Long>();
                        footprints.put(label, set);
                    }
                    set.add((long) y * w + x);
                }
            }
        }

        List<ObjectMorphology> results = new ArrayList<ObjectMorphology>();
        for (Map.Entry<Integer, Set<Long>> entry : footprints.entrySet()) {
            int label = entry.getKey();
            Set<Long> packed = entry.getValue();
            if (packed.size() < 3) continue;
            List<int[]> pixels = new ArrayList<int[]>(packed.size());
            for (Long p : packed) {
                long key = p.longValue();
                int x = (int) (key % w);
                int y = (int) (key / w);
                pixels.add(new int[]{x, y});
            }
            results.add(measureObject(label, pixels, w, h, pixelSize));
        }
        return results;
    }

    private static int labelFromPixel(float value) {
        if (!Float.isFinite(value) || value <= 0f) return 0;
        return value > Integer.MAX_VALUE ? 0 : Math.round(value);
    }

    private static ObjectMorphology measureObject(int label, List<int[]> pixels,
                                                    int imgW, int imgH, double pixelSize) {
        // Bounding box
        int minX = Integer.MAX_VALUE, maxX = 0, minY = Integer.MAX_VALUE, maxY = 0;
        for (int[] p : pixels) {
            if (p[0] < minX) minX = p[0];
            if (p[0] > maxX) maxX = p[0];
            if (p[1] < minY) minY = p[1];
            if (p[1] > maxY) maxY = p[1];
        }
        int bbW = maxX - minX + 1;
        int bbH = maxY - minY + 1;

        // Area
        double area = pixels.size();
        double areaUm2 = area * pixelSize * pixelSize;

        // Build binary mask for perimeter and convex hull
        boolean[][] mask = new boolean[bbH][bbW];
        for (int[] p : pixels) {
            mask[p[1] - minY][p[0] - minX] = true;
        }

        // Perimeter: count boundary pixels (4-connected edge pixels)
        double perimeter = computePerimeter(mask, bbW, bbH);

        // Circularity
        double circularity = perimeter > 0 ? 4.0 * Math.PI * area / (perimeter * perimeter) : 0;
        // Values > 1 are a known digitisation artefact of the 4-connected
        // boundary-pixel perimeter estimator on small/rough shapes. Keep the
        // raw value so downstream stats see actual variance; callers can
        // clamp for display if needed.

        // Convex hull area (Andrew's monotone chain on boundary pixels)
        List<int[]> boundary = extractBoundaryPixels(mask, bbW, bbH, minX, minY);
        List<int[]> hull = convexHull(boundary);
        double convexHullArea = polygonArea(hull);
        if (convexHullArea < area) convexHullArea = area; // sanity

        // Solidity
        double solidity = convexHullArea > 0 ? area / convexHullArea : 0;

        // Aspect ratio
        double aspectRatio = bbH > 0 ? (double) bbW / bbH : 1.0;
        if (aspectRatio < 1.0) aspectRatio = 1.0 / aspectRatio;

        // Feret diameter (rotating calipers on convex hull, exact)
        double feret = computeFeret(boundary, hull) * pixelSize;

        // Extent
        double bbArea = (double) bbW * bbH;
        double extent = bbArea > 0 ? area / bbArea : 0;

        return new ObjectMorphology(label, area, areaUm2, perimeter * pixelSize,
                circularity, convexHullArea * pixelSize * pixelSize, solidity,
                aspectRatio, feret, extent, bbW, bbH);
    }

    private static double computePerimeter(boolean[][] mask, int w, int h) {
        int count = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (!mask[y][x]) continue;
                // Check 4-connected neighbors; if any is outside or false, this is boundary
                if (x == 0 || !mask[y][x - 1] ||
                    x == w - 1 || !mask[y][x + 1] ||
                    y == 0 || !mask[y - 1][x] ||
                    y == h - 1 || !mask[y + 1][x]) {
                    count++;
                }
            }
        }
        return count;
    }

    private static List<int[]> extractBoundaryPixels(boolean[][] mask, int w, int h,
                                                      int offsetX, int offsetY) {
        List<int[]> boundary = new ArrayList<int[]>();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (!mask[y][x]) continue;
                if (x == 0 || !mask[y][x - 1] ||
                    x == w - 1 || !mask[y][x + 1] ||
                    y == 0 || !mask[y - 1][x] ||
                    y == h - 1 || !mask[y + 1][x]) {
                    boundary.add(new int[]{x + offsetX, y + offsetY});
                }
            }
        }
        return boundary;
    }

    /**
     * Convex hull via Andrew's monotone chain. Returns hull vertices in counter-clockwise
     * order (no closing duplicate). Handles collinear input correctly: if all points are
     * collinear the result is the two extreme points (size 2). If fewer than 2 distinct
     * points, returns the input copy.
     */
    private static List<int[]> convexHull(List<int[]> points) {
        int n = points.size();
        if (n < 2) return new ArrayList<int[]>(points);
        List<int[]> sorted = new ArrayList<int[]>(points);
        Collections.sort(sorted, new Comparator<int[]>() {
            @Override public int compare(int[] a, int[] b) {
                if (a[0] != b[0]) return Integer.compare(a[0], b[0]);
                return Integer.compare(a[1], b[1]);
            }
        });

        int[][] hull = new int[2 * n][];
        int k = 0;
        // Lower hull
        for (int i = 0; i < n; i++) {
            int[] p = sorted.get(i);
            while (k >= 2 && cross(hull[k - 2], hull[k - 1], p) <= 0) k--;
            hull[k++] = p;
        }
        // Upper hull
        int lower = k + 1;
        for (int i = n - 2; i >= 0; i--) {
            int[] p = sorted.get(i);
            while (k >= lower && cross(hull[k - 2], hull[k - 1], p) <= 0) k--;
            hull[k++] = p;
        }
        // last point == first point, drop it
        List<int[]> out = new ArrayList<int[]>(k - 1);
        for (int i = 0; i < k - 1; i++) out.add(hull[i]);
        return out;
    }

    /** Shoelace area of a polygon given by hull vertices in order (no closing duplicate). */
    private static double polygonArea(List<int[]> hull) {
        if (hull.size() < 3) return 0;
        double area = 0;
        for (int i = 0; i < hull.size(); i++) {
            int j = (i + 1) % hull.size();
            area += (double) hull.get(i)[0] * hull.get(j)[1];
            area -= (double) hull.get(j)[0] * hull.get(i)[1];
        }
        return Math.abs(area) / 2.0;
    }

    private static double cross(int[] o, int[] a, int[] b) {
        return (double)(a[0] - o[0]) * (b[1] - o[1]) - (double)(a[1] - o[1]) * (b[0] - o[0]);
    }

    /**
     * Maximum caliper diameter (Feret) via rotating calipers on the precomputed hull.
     * Exact for any point set whose convex hull has ≥ 2 vertices. Falls back to brute force
     * over the boundary when the hull degenerates (e.g. fewer than 2 vertices).
     */
    private static double computeFeret(List<int[]> boundary, List<int[]> hull) {
        if (boundary.size() < 2) return 0;
        int h = hull.size();
        if (h < 2) {
            double max = 0;
            for (int i = 0; i < boundary.size(); i++) {
                int[] a = boundary.get(i);
                for (int j = i + 1; j < boundary.size(); j++) {
                    int[] b = boundary.get(j);
                    double dx = a[0] - b[0];
                    double dy = a[1] - b[1];
                    double d = dx * dx + dy * dy;
                    if (d > max) max = d;
                }
            }
            return Math.sqrt(max);
        }
        if (h == 2) {
            int[] a = hull.get(0);
            int[] b = hull.get(1);
            double dx = a[0] - b[0];
            double dy = a[1] - b[1];
            return Math.sqrt(dx * dx + dy * dy);
        }
        // Rotating calipers: for each hull edge (i, i+1), advance k while the perpendicular
        // distance from hull[k] to the edge keeps increasing; record the max squared distance
        // between the edge endpoints and hull[k].
        int k = 1;
        double maxSq = 0;
        for (int i = 0; i < h; i++) {
            int ni = (i + 1) % h;
            // |cross| is proportional to perpendicular distance from k to edge (i, ni)
            while (Math.abs(cross(hull.get(i), hull.get(ni), hull.get((k + 1) % h)))
                   > Math.abs(cross(hull.get(i), hull.get(ni), hull.get(k)))) {
                k = (k + 1) % h;
            }
            double dx1 = hull.get(i)[0] - hull.get(k)[0];
            double dy1 = hull.get(i)[1] - hull.get(k)[1];
            double dx2 = hull.get(ni)[0] - hull.get(k)[0];
            double dy2 = hull.get(ni)[1] - hull.get(k)[1];
            double d1 = dx1 * dx1 + dy1 * dy1;
            double d2 = dx2 * dx2 + dy2 * dy2;
            if (d1 > maxSq) maxSq = d1;
            if (d2 > maxSq) maxSq = d2;
        }
        return Math.sqrt(maxSq);
    }
}
