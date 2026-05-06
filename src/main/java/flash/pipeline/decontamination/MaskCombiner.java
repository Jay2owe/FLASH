package flash.pipeline.decontamination;

/**
 * Deterministic boolean-mask combination helpers.
 */
public final class MaskCombiner {

    private MaskCombiner() {
    }

    public static boolean[] and(boolean[] left, boolean[] right) {
        validateLengths(left, right);
        boolean[] out = new boolean[left.length];
        for (int i = 0; i < left.length; i++) {
            out[i] = left[i] && right[i];
        }
        return out;
    }

    public static boolean[] or(boolean[] left, boolean[] right) {
        validateLengths(left, right);
        boolean[] out = new boolean[left.length];
        for (int i = 0; i < left.length; i++) {
            out[i] = left[i] || right[i];
        }
        return out;
    }

    public static boolean[] andNot(boolean[] includeMask, boolean[] vetoMask) {
        validateLengths(includeMask, vetoMask);
        boolean[] out = new boolean[includeMask.length];
        for (int i = 0; i < includeMask.length; i++) {
            out[i] = includeMask[i] && !vetoMask[i];
        }
        return out;
    }

    private static void validateLengths(boolean[] left, boolean[] right) {
        if (left == null || right == null) {
            throw new IllegalArgumentException("Masks must not be null.");
        }
        if (left.length != right.length) {
            throw new IllegalArgumentException("Masks must have the same length.");
        }
    }
}
