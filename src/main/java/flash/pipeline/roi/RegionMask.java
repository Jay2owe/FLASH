package flash.pipeline.roi;

import ij.ImagePlus;
import ij.gui.Roi;

import java.awt.Rectangle;

/**
 * Single source of truth for applying a tissue ROI during 3D Object Analysis.
 *
 * <p><b>Why this class exists.</b> Each saved ROI pair holds two ROIs per image:
 * <ul>
 *   <li><b>index 0 — "original"</b>: the traced region in original image
 *       coordinates (the box can sit anywhere in the image).</li>
 *   <li><b>index 1 — "cropped"</b>: the same shape after {@code Image &gt; Crop},
 *       which ImageJ slides so its bounding box sits at the top-left (0,0).
 *       This only happens for non-rectangular ROIs; for rectangles ImageJ
 *       returns {@code null} and the writer falls back to the original.</li>
 * </ul>
 *
 * <p>The index-1 ROI is a <i>presentation/QC</i> artifact (it matches the saved
 * cropped preview image). It must <b>never</b> drive geometry. Feeding the
 * top-left-shifted index-1 ROI to a centroid filter that runs on the full,
 * uncropped image keeps the wrong objects; feeding it to a clear-outside mask
 * double-shifts it (the masker re-derives the cropped frame itself) and erases
 * almost all of the tissue. See {@code RegionMaskTest}.
 *
 * <p>{@code RegionMask} is therefore built <b>only</b> from the original
 * (index-0) ROI, and every crop/mask/filter operation is derived from it. There
 * is no way to hand it the shifted ROI.
 *
 * <p><b>Coordinate spaces.</b> {@link #filterByCentroid(ImagePlus)} runs against
 * a full-image label map, so the region is used in original coordinates.
 * {@link #cropToBounds(ImagePlus)} and {@link #cropAndMask(ImagePlus)} crop to
 * the region's bounding box, which re-bases the result so the region's top-left
 * becomes (0,0) — the region-relative coordinate system wanted downstream.
 *
 * <p><b>Thread safety.</b> A single instance may be shared across the parallel
 * per-channel filter threads. {@link #filterByCentroid(ImagePlus)} clones the
 * region before {@link Roi#contains} (whose mask cache is not thread-safe);
 * the crop/mask paths go through {@link RoiOps#removeNonRoiThreadSafe} which
 * clones the masking ROI internally.
 */
public final class RegionMask {

    /** Original-coordinate ROI (index 0), defensively cloned on construction. */
    private final Roi region;

    private RegionMask(Roi region) {
        this.region = region;
    }

    /**
     * @param originalRoi the index-0 (original-coordinate) ROI, or {@code null}
     *                    when the analysis runs without an ROI restriction
     * @return a {@code RegionMask}, or {@code null} if {@code originalRoi} is null
     */
    public static RegionMask from(Roi originalRoi) {
        return originalRoi == null ? null : new RegionMask((Roi) originalRoi.clone());
    }

    /** @return the region's bounding box in original image coordinates. */
    public Rectangle bounds() {
        return region.getBounds();
    }

    /**
     * Removes labels whose centroid falls outside the region from a
     * <b>full-image</b> (uncropped) label map. The region is in original image
     * coordinates — the same space as the label map at this stage.
     *
     * @return the number of labels removed
     */
    public int filterByCentroid(ImagePlus fullImageLabelMap) {
        if (fullImageLabelMap == null) return 0;
        // Clone so concurrent Roi.contains() mask-cache initialisation is safe.
        return filterLabelsByCentroid(fullImageLabelMap, (Roi) region.clone());
    }

    /**
     * Crops to the region's bounding box <b>only</b> — no shape mask. Whole
     * objects are preserved (an object kept by the centroid filter is not
     * clipped to the traced outline), and coordinates become region-relative
     * (top-left = 0,0). This is the post-centroid-filter behaviour for
     * centroid mode.
     */
    public void cropToBounds(ImagePlus imp) {
        if (imp == null) return;
        RoiOps.removeNonRoiThreadSafe(imp, region, null);
    }

    /**
     * Crops to the bounding box <b>and</b> clears outside the traced shape. Both
     * are derived from the original ROI; {@link RoiOps#removeNonRoiThreadSafe}
     * shifts the shape into the cropped frame itself. This is the crop-first
     * mode behaviour (objects straddling the outline are clipped to it).
     */
    public void cropAndMask(ImagePlus imp) {
        if (imp == null) return;
        RoiOps.removeNonRoiThreadSafe(imp, region, region);
    }

    /**
     * Counts distinct non-zero labels in a label map. Used for the count-
     * preservation QC invariant (cropping must relocate objects, not destroy
     * them).
     */
    public static int countLabels(ImagePlus labelImage) {
        if (labelImage == null) return 0;
        ij.ImageStack stack = labelImage.getStack();
        java.util.HashSet<Integer> labels = new java.util.HashSet<Integer>();
        for (int s = 1; s <= stack.getSize(); s++) {
            ij.process.ImageProcessor ip = stack.getProcessor(s);
            int nPixels = ip.getPixelCount();
            for (int i = 0; i < nPixels; i++) {
                int v = ip.get(i);
                if (v != 0) labels.add(v);
            }
        }
        return labels.size();
    }

    /**
     * Removes labels from a label image whose 2D centroid (averaged across Z)
     * falls outside {@code roi}. The {@code roi} must be in the same coordinate
     * space as {@code labelImage}.
     *
     * @return number of labels removed
     */
    public static int filterLabelsByCentroid(ImagePlus labelImage, Roi roi) {
        if (labelImage == null || roi == null) return 0;

        ij.ImageStack stack = labelImage.getStack();
        int nSlices = stack.getSize();

        // Pass 1: accumulate centroid sums per label
        java.util.Map<Integer, long[]> centroids = new java.util.LinkedHashMap<Integer, long[]>();
        for (int s = 1; s <= nSlices; s++) {
            ij.process.ImageProcessor ip = stack.getProcessor(s);
            int nPixels = ip.getPixelCount();
            int w = ip.getWidth();
            for (int i = 0; i < nPixels; i++) {
                int label = ip.get(i);
                if (label == 0) continue;
                long[] acc = centroids.get(label);
                if (acc == null) {
                    acc = new long[3]; // [sumX, sumY, count]
                    centroids.put(label, acc);
                }
                acc[0] += i % w;  // x
                acc[1] += i / w;  // y
                acc[2]++;
            }
        }

        // Determine which labels have centroids outside the ROI
        java.util.Set<Integer> reject = new java.util.HashSet<Integer>();
        for (java.util.Map.Entry<Integer, long[]> entry : centroids.entrySet()) {
            long[] acc = entry.getValue();
            double cx = (double) acc[0] / acc[2];
            double cy = (double) acc[1] / acc[2];
            if (!roi.contains((int) Math.round(cx), (int) Math.round(cy))) {
                reject.add(entry.getKey());
            }
        }

        if (reject.isEmpty()) return 0;

        // Pass 2: zero out rejected labels
        for (int s = 1; s <= nSlices; s++) {
            ij.process.ImageProcessor ip = stack.getProcessor(s);
            int nPixels = ip.getPixelCount();
            for (int i = 0; i < nPixels; i++) {
                if (reject.contains(ip.get(i))) {
                    ip.set(i, 0);
                }
            }
        }

        return reject.size();
    }
}
