package flash.pipeline.image;

import ij.ImagePlus;
import ij.ImageStack;
import ij.measure.Calibration;
import ij.process.ImageProcessor;

import java.awt.Rectangle;

/**
 * Thread-safe image-plane operations.
 *
 * <p>{@code ij.plugin.Duplicator} touches shared ImageJ state and is not safe
 * to call from worker threads. This helper performs an equivalent
 * channel/slice/frame copy using {@link ImageProcessor#crop()} per slice — the
 * documented per-slice safe path that returns a detached processor without any
 * WindowManager interaction.</p>
 */
public final class ImageOps {
    private ImageOps() {}

    /** Thread-safe full-stack duplicate. */
    public static ImagePlus duplicateThreadSafe(ImagePlus src) {
        if (src == null) return null;
        return duplicateThreadSafe(src,
                1, Math.max(1, src.getNChannels()),
                1, Math.max(1, src.getNSlices()),
                1, Math.max(1, src.getNFrames()));
    }

    /**
     * Thread-safe duplicate of a sub-range. Mirrors
     * {@code Duplicator.run(imp, firstC, lastC, firstZ, lastZ, firstT, lastT)}
     * but performs a per-slice {@code ip.crop()} so it can run concurrently
     * from multiple worker threads.
     */
    public static ImagePlus duplicateThreadSafe(ImagePlus src,
            int firstC, int lastC, int firstZ, int lastZ,
            int firstT, int lastT) {
        if (src == null) return null;
        int nC = lastC - firstC + 1;
        int nZ = lastZ - firstZ + 1;
        int nT = lastT - firstT + 1;
        ImageStack inStack = src.getImageStack();
        ImageStack out = new ImageStack(src.getWidth(), src.getHeight());
        for (int t = firstT; t <= lastT; t++) {
            for (int z = firstZ; z <= lastZ; z++) {
                for (int c = firstC; c <= lastC; c++) {
                    int idx = src.getStackIndex(c, z, t);
                    ImageProcessor ip = inStack.getProcessor(idx);
                    Rectangle oldRoi = ip.getRoi();
                    ip.setRoi(0, 0, src.getWidth(), src.getHeight());
                    ImageProcessor cropped = ip.crop();
                    if (oldRoi != null) {
                        ip.setRoi(oldRoi);
                    } else {
                        ip.resetRoi();
                    }
                    out.addSlice(inStack.getSliceLabel(idx), cropped);
                }
            }
        }
        ImagePlus dup = new ImagePlus(src.getTitle(), out);
        dup.setDimensions(nC, nZ, nT);
        if (nC > 1 || nZ > 1 || nT > 1) {
            dup.setOpenAsHyperStack(src.isHyperStack());
        }
        Calibration cal = src.getCalibration();
        if (cal != null) dup.setCalibration(cal.copy());
        return dup;
    }
}
