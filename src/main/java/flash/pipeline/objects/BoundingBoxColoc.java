package flash.pipeline.objects;

import ij.ImagePlus;
import ij.ImageStack;
import ij.process.ImageProcessor;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Shared bounding-box "volume signal fill" geometry (Family B / BB-Vol), callable from both
 * 3D Object Analysis (producer) and Spatial Analysis (consumer). Counts partner-channel object
 * voxels that fall inside a source object's bounding box, as a percentage of the box volume.
 *
 * <p>Two continuous metrics per source object:
 * <ul>
 *   <li><b>best</b> — the single partner object contributing the most voxels in the box,</li>
 *   <li><b>total</b> — all partner-channel voxels in the box (the union, since each voxel has one
 *       label).</li>
 * </ul>
 * Both are ≤ 100 (partner voxels are a subset of the box) and {@code best ≤ total ≤ 100}.
 */
public final class BoundingBoxColoc {

    private BoundingBoxColoc() {}

    /**
     * {@code {bestPct, totalPct}} for one source box filled by the partner label image. Iterates
     * only the sub-volume bounded by the box (clamped to the image), so cost is the box volume.
     */
    public static float[] fillPercent(ImagePlus partnerLabels, CpcUtils.ObjectInfo srcBox) {
        float[] zero = new float[] {0f, 0f};
        if (srcBox == null || partnerLabels == null) return zero;
        long boxVol = srcBox.bbVolume();
        if (boxVol <= 0 || srcBox.isBoxEmpty()) return zero;
        ImageStack stack = partnerLabels.getStack();
        if (stack == null) return zero;

        int w = partnerLabels.getWidth();
        int h = partnerLabels.getHeight();
        int nz = stack.getSize();
        int x0 = Math.max(0, srcBox.xmin), x1 = Math.min(w - 1, srcBox.xmax);
        int y0 = Math.max(0, srcBox.ymin), y1 = Math.min(h - 1, srcBox.ymax);
        int z0 = Math.max(0, srcBox.zmin), z1 = Math.min(nz - 1, srcBox.zmax);
        if (x1 < x0 || y1 < y0 || z1 < z0) return zero;

        gnu.trove.map.hash.TIntIntHashMap counts = new gnu.trove.map.hash.TIntIntHashMap();
        long total = 0L;
        for (int z = z0; z <= z1; z++) {
            ImageProcessor ip = stack.getProcessor(z + 1);
            for (int y = y0; y <= y1; y++) {
                for (int x = x0; x <= x1; x++) {
                    int label = labelFromPixel(ip.getf(x, y));
                    if (label <= 0) continue;
                    counts.adjustOrPutValue(label, 1, 1);
                    total++;
                }
            }
        }
        long best = 0L;
        for (int v : counts.values()) {
            if (v > best) best = v;
        }
        float bestPct = (float) ((double) best / (double) boxVol * 100.0);
        float totalPct = (float) ((double) total / (double) boxVol * 100.0);
        return new float[] {bestPct, totalPct};
    }

    /**
     * Population-level convenience: {@code {bestPct, totalPct}} per source object, keyed by source
     * label, where each source box is filled by the partner label image.
     */
    public static Map<Integer, float[]> fillPercents(ImagePlus partnerLabels,
                                                     List<CpcUtils.ObjectInfo> sources) {
        Map<Integer, float[]> out = new LinkedHashMap<Integer, float[]>();
        if (sources == null) return out;
        for (CpcUtils.ObjectInfo src : sources) {
            out.put(src.label, fillPercent(partnerLabels, src));
        }
        return out;
    }

    private static int labelFromPixel(float value) {
        if (!Float.isFinite(value) || value <= 0f) return 0;
        return value > Integer.MAX_VALUE ? 0 : Math.round(value);
    }
}
