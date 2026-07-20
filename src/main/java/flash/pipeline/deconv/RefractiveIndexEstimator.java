package flash.pipeline.deconv;

import ij.IJ;

import java.util.Locale;

/**
 * Best-effort refractive-index lookups from microscope metadata or user hints.
 * Downstream UI code may override these inferred values when the user provides
 * a more specific mounting-medium description.
 */
public final class RefractiveIndexEstimator {

    private RefractiveIndexEstimator() {}

    public static double immersionRI(String immersion) {
        String value = normalize(immersion);
        if (value == null) return 1.0;
        if (value.contains("oil")) return 1.515;
        if (value.contains("water")) return 1.33;
        if (value.contains("glycerol") || value.contains("glycerin")) return 1.47;
        if (value.contains("silicone")) return 1.40;
        if (value.contains("air")) return 1.0;
        if (value.contains("multi")) return 1.0;

        IJ.log("Unknown immersion medium '" + immersion + "'; defaulting RI to 1.0");
        return 1.0;
    }

    public static double mountingMediumRI(String hint) {
        String value = normalize(hint);
        if (value == null) return Double.NaN;
        if (value.contains("vectashield")) return 1.45;
        if (value.contains("prolong")) return 1.47;
        if (value.contains("cfm-3") || value.contains("cfm3")) return 1.52;
        if (value.contains("glycerol") || value.contains("glycerin")) return 1.47;
        if (value.contains("aqueous") || value.contains("pbs") || value.contains("water")) return 1.33;
        if (value.contains("clarity") || value.contains("idisco")
                || value.contains("cubic") || value.contains("ce3d")) {
            return 1.46;
        }
        return Double.NaN;
    }

    public static double inferSampleRI(String immersion, String mountingHint) {
        double mountingMedium = mountingMediumRI(mountingHint);
        if (!Double.isNaN(mountingMedium)) return mountingMedium;
        return immersionRI(immersion);
    }

    private static String normalize(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        if (trimmed.isEmpty()) return null;
        return trimmed.toLowerCase(Locale.ROOT);
    }
}
