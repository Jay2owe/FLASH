package flash.pipeline.objects;

import flash.pipeline.stardist.StarDist3DRunner;
import flash.pipeline.ui.preview.ObjectOverlayRenderer;
import ij.ImagePlus;
import ij.ImageStack;
import ij.measure.ResultsTable;
import ij.process.ImageProcessor;

public final class LabelIndex {

    private LabelIndex() {
    }

    public static int getLabelAt(ImagePlus labelImage, int x, int y, int oneBasedZ) {
        if (labelImage == null || oneBasedZ < 1) return 0;
        ImageStack stack;
        try {
            stack = labelImage.getStack();
        } catch (RuntimeException e) {
            return 0;
        }
        if (stack == null || oneBasedZ > stack.getSize()) return 0;
        ImageProcessor processor;
        try {
            processor = stack.getProcessor(oneBasedZ);
        } catch (RuntimeException e) {
            return 0;
        }
        return ObjectOverlayRenderer.labelAt(processor, x, y);
    }

    /** StarDist attaches a per-label ResultsTable via OBJECT_STATS_PROPERTY. */
    public static ResultsTable starDistStats(ImagePlus labelImage) {
        if (labelImage == null) return null;
        Object stats;
        try {
            stats = labelImage.getProperty(StarDist3DRunner.OBJECT_STATS_PROPERTY);
        } catch (RuntimeException e) {
            return null;
        }
        return stats instanceof ResultsTable ? (ResultsTable) stats : null;
    }

    public static boolean hasStarDistStats(ImagePlus labelImage) {
        return starDistStats(labelImage) != null;
    }
}
