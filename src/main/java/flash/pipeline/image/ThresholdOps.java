package flash.pipeline.image;

import ij.ImagePlus;
import ij.process.ImageProcessor;

/** Threshold utilities to match macro behavior without invoking ImageJ commands/macros. */
public final class ThresholdOps {

    private ThresholdOps() {}

    /**
     * Macro get_stack_threshold(): slice 6, setAutoThreshold("Default dark"), return lower threshold.
     *
     * <p>This implementation avoids {@code IJ.run("Auto Threshold", ...)} and instead uses ImageJ1's
     * {@link ImageProcessor#setAutoThreshold(String)} which is the same mechanism used by the UI.
     *
     * <p>We only return the threshold value; we do not apply thresholding to the image.
     */
    public static double defaultDarkThresholdAtSlice6(ImagePlus imp) {
        if (imp == null) return Double.NaN;

        int z = Math.min(6, Math.max(1, imp.getNSlices()));

        ImageProcessor ip = imp.getStack().getProcessor(z);
        if (ip == null) return Double.NaN;

        // Compute the auto-threshold on this slice.
        // For IJ 1.53f, this parses strings like "Default dark".
        ip.setAutoThreshold("Default dark");

        return ip.getMinThreshold();
    }
}
