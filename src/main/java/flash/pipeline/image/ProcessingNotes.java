package flash.pipeline.image;

/**
 * Macro-compatible per-channel processing note strings used in Analysis Details.
 */
public final class ProcessingNotes {
    private ProcessingNotes() {}

    public static String automatic(double saturation) {
        return "Automatic (saturation = " + saturation + ")";
    }

    public static String manual() {
        return "Manual";
    }

    public static String none() {
        return "None";
    }

    /** For Custom Min-Max method, macro stores token like "min-max" (e.g. 0-65535) or "None". */
    public static String customMinMaxToken(String token) {
        return token == null ? "None" : token;
    }
}
