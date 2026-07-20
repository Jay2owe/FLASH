package flash.pipeline.image;

import ij.ImagePlus;

/**
 * Native Java equivalents for common filter macros.
 *
 * Next wave: we will parse the .ijm filter files and map supported ops to Java.
 */
public final class FilterOps {

    private FilterOps() {}

    /** Placeholder: apply nothing. */
    public static void applyNoop(ImagePlus imp) {
        // intentionally blank
    }
}
