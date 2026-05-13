package flash.pipeline.intensity.spatial;

import ij.ImagePlus;
import ij.measure.Calibration;

import java.util.Locale;

final class CalibrationUtil {
    private CalibrationUtil() {
    }

    enum Axis {
        X,
        Y,
        Z
    }

    static double pixelSizeUm(ImagePlus image, Axis axis) {
        Calibration cal = image == null ? null : image.getCalibration();
        if (cal == null) return 1.0;
        double raw = axis == Axis.X ? cal.pixelWidth
                : axis == Axis.Y ? cal.pixelHeight
                : cal.pixelDepth;
        double value = toMicrons(raw, cal.getUnit());
        return value > 0.0 && Double.isFinite(value) ? value : 1.0;
    }

    static double toMicrons(double value, String unit) {
        double multiplier = micronMultiplier(unit);
        return value * multiplier;
    }

    private static double micronMultiplier(String unit) {
        String normalized = normalize(unit);
        if (normalized.isEmpty() || "pixel".equals(normalized) || "pixels".equals(normalized)
                || "px".equals(normalized)) {
            throw new IllegalArgumentException("Spatial calibration is in pixels, not microns.");
        }
        if ("um".equals(normalized) || "\u00b5m".equals(normalized) || "\u03bcm".equals(normalized)
                || "micron".equals(normalized) || "microns".equals(normalized)
                || "micrometer".equals(normalized) || "micrometers".equals(normalized)
                || "micrometre".equals(normalized) || "micrometres".equals(normalized)) {
            return 1.0;
        }
        if ("nm".equals(normalized) || "nanometer".equals(normalized) || "nanometers".equals(normalized)
                || "nanometre".equals(normalized) || "nanometres".equals(normalized)) {
            return 0.001;
        }
        if ("mm".equals(normalized) || "millimeter".equals(normalized) || "millimeters".equals(normalized)
                || "millimetre".equals(normalized) || "millimetres".equals(normalized)) {
            return 1000.0;
        }
        if ("cm".equals(normalized) || "centimeter".equals(normalized) || "centimeters".equals(normalized)
                || "centimetre".equals(normalized) || "centimetres".equals(normalized)) {
            return 10000.0;
        }
        if ("m".equals(normalized) || "meter".equals(normalized) || "meters".equals(normalized)
                || "metre".equals(normalized) || "metres".equals(normalized)) {
            return 1000000.0;
        }
        throw new IllegalArgumentException("Unknown spatial calibration unit: " + unit);
    }

    private static String normalize(String unit) {
        String normalized = unit == null ? "" : unit.trim().toLowerCase(Locale.ROOT);
        normalized = normalized.replace(" ", "").replace("_", "").replace("-", "");
        if (normalized.endsWith("/pixel")) {
            normalized = normalized.substring(0, normalized.length() - "/pixel".length()).trim();
        } else if (normalized.endsWith("/pixels")) {
            normalized = normalized.substring(0, normalized.length() - "/pixels".length()).trim();
        } else if (normalized.endsWith("/px")) {
            normalized = normalized.substring(0, normalized.length() - "/px".length()).trim();
        }
        return normalized;
    }
}
