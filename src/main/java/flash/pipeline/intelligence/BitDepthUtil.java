package flash.pipeline.intelligence;

import ij.ImagePlus;
import ij.process.ImageProcessor;
import ij.process.ImageStatistics;

/**
 * L-01 Bit-Depth Auditor.
 *
 * Works out the real brightness range of an image regardless of whether it
 * claims to be 8-bit, 12-bit-in-16-bit, 14-bit-in-16-bit, or 16-bit.
 * Provides a "saturation ceiling" that future saturation-QC checks should
 * compare against instead of the hard-coded 65535.
 *
 * Returns the power-of-two ceiling that matches the true container:
 *   8-bit              -> 255
 *   16-bit using only up to 4095   -> 4095 (12-bit)
 *   16-bit using up to 16383       -> 16383 (14-bit)
 *   16-bit using up to 65535       -> 65535
 *   32-bit float                   -> Float.MAX_VALUE (not clipped)
 */
public final class BitDepthUtil {

    /** Unknown / non-integer image (e.g. 32-bit float). */
    public static final int CEILING_UNKNOWN = -1;

    private BitDepthUtil() {}

    /**
     * Returns the saturation ceiling for this image based on the largest
     * pixel value actually observed across the stack. Pure histogram scan,
     * no per-pixel iteration on the caller's side.
     */
    public static int saturationCeiling(ImagePlus imp) {
        if (imp == null) return CEILING_UNKNOWN;

        int bitDepth = imp.getBitDepth();
        if (bitDepth == 8) return 255;
        if (bitDepth == 32) return CEILING_UNKNOWN;

        int observedMax = 0;
        int slices = imp.getStackSize();
        for (int z = 1; z <= slices; z++) {
            ImageProcessor ip = imp.getStack().getProcessor(z);
            ImageStatistics s = ip.getStatistics();
            if (s.max > observedMax) observedMax = (int) s.max;
            if (observedMax >= 65535) break;
        }
        return ceilingForObserved(observedMax);
    }

    /**
     * Returns the saturation ceiling for a single processor (one slice).
     * Cheaper variant used inside per-slice loops.
     */
    public static int saturationCeiling(ImageProcessor ip) {
        if (ip == null) return CEILING_UNKNOWN;
        if (ip.getBitDepth() == 8) return 255;
        if (ip.getBitDepth() == 32) return CEILING_UNKNOWN;
        ImageStatistics s = ip.getStatistics();
        return ceilingForObserved((int) s.max);
    }

    /** Picks the smallest power-of-two-minus-one that contains the observed max. */
    private static int ceilingForObserved(int observedMax) {
        if (observedMax <= 255)   return 255;
        if (observedMax <= 4095)  return 4095;   // 12-bit
        if (observedMax <= 16383) return 16383;  // 14-bit
        return 65535;
    }

    /** Effective bit-depth inferred from the ceiling (8, 12, 14, 16). */
    public static int effectiveBitDepth(int ceiling) {
        switch (ceiling) {
            case 255:   return 8;
            case 4095:  return 12;
            case 16383: return 14;
            case 65535: return 16;
            default:    return -1;
        }
    }

    /**
     * Fraction of pixels at the saturation ceiling. Ignores zeros.
     * Returns 0 if ceiling is unknown (e.g. 32-bit float).
     */
    public static double saturatedFraction(ImageProcessor ip, int ceiling) {
        if (ip == null || ceiling <= 0) return 0.0;
        int[] hist = ip.getHistogram();
        if (hist == null || hist.length == 0) return 0.0;
        long total = 0;
        for (int i = 0; i < hist.length; i++) total += hist[i];
        if (total == 0) return 0.0;

        // Histogram length matches container (256 for 8-bit, 65536 for 16-bit).
        // Map the ceiling to the matching histogram bin.
        int ceilingBin = Math.min(ceiling, hist.length - 1);
        long clipped = hist[ceilingBin];
        return (double) clipped / (double) total;
    }
}
