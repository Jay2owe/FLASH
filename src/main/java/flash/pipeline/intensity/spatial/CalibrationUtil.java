package flash.pipeline.intensity.spatial;

import ij.ImagePlus;
import ij.measure.Calibration;

import java.util.Locale;

/**
 * Converts ImageJ spatial calibration into canonical microns without inventing
 * a physical scale when the source metadata is missing or invalid.
 */
public final class CalibrationUtil {
    private CalibrationUtil() {
    }

    public enum Axis {
        X,
        Y,
        Z
    }

    /** The reason an axis does or does not have a canonical physical value. */
    public enum State {
        PHYSICAL,
        MISSING_CALIBRATION,
        PIXEL_UNIT,
        MISSING_UNIT,
        UNKNOWN_UNIT,
        NONFINITE_SCALE,
        NONPOSITIVE_SCALE
    }

    /**
     * One independently validated axis, retaining the source value and unit as
     * provenance. A non-physical result has {@link Double#NaN} microns.
     */
    public static final class CanonicalScale {
        private final double rawValue;
        private final String originalUnit;
        private final String normalizedUnit;
        private final double microns;
        private final State state;

        private CanonicalScale(double rawValue, String originalUnit,
                               String normalizedUnit, double microns, State state) {
            this.rawValue = rawValue;
            this.originalUnit = originalUnit;
            this.normalizedUnit = normalizedUnit;
            this.microns = microns;
            this.state = state;
        }

        public double rawValue() {
            return rawValue;
        }

        public String originalUnit() {
            return originalUnit;
        }

        public String normalizedUnit() {
            return normalizedUnit;
        }

        public double microns() {
            return microns;
        }

        public State state() {
            return state;
        }

        public boolean hasMicrons() {
            return state == State.PHYSICAL;
        }

        public boolean isPixelUnit() {
            return state == State.PIXEL_UNIT;
        }

        public double requireMicrons() {
            if (!hasMicrons()) {
                throw new IllegalArgumentException(failureMessage(this));
            }
            return microns;
        }

        @Override
        public String toString() {
            return "CanonicalScale[state=" + state + ", raw=" + rawValue
                    + ", originalUnit=" + originalUnit
                    + (hasMicrons() ? ", microns=" + microns : "") + "]";
        }
    }

    /** Canonical X/Y/Z axes. Invalidity on one axis does not erase the others. */
    public static final class CanonicalCalibration {
        private final CanonicalScale x;
        private final CanonicalScale y;
        private final CanonicalScale z;

        private CanonicalCalibration(CanonicalScale x, CanonicalScale y, CanonicalScale z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }

        public CanonicalScale x() {
            return x;
        }

        public CanonicalScale y() {
            return y;
        }

        public CanonicalScale z() {
            return z;
        }

        public CanonicalScale axis(Axis axis) {
            if (axis == null) {
                throw new IllegalArgumentException("Calibration axis is required.");
            }
            return axis == Axis.X ? x : axis == Axis.Y ? y : z;
        }

        public boolean isFullyPhysical() {
            return x.hasMicrons() && y.hasMicrons() && z.hasMicrons();
        }
    }

    /** Returns the typed canonical calibration for an image. */
    public static CanonicalCalibration canonicalize(ImagePlus image) {
        Calibration calibration = image == null ? null : image.getCalibration();
        if (calibration == null) {
            CanonicalScale missing = missingCalibration();
            return new CanonicalCalibration(missing, missing, missing);
        }
        return canonicalize(calibration.pixelWidth, calibration.pixelHeight,
                calibration.pixelDepth, calibration.getUnit());
    }

    /** Converts three raw axes independently while retaining their common source unit. */
    public static CanonicalCalibration canonicalize(double pixelWidth, double pixelHeight,
                                                     double pixelDepth, String unit) {
        return new CanonicalCalibration(
                canonicalize(pixelWidth, unit),
                canonicalize(pixelHeight, unit),
                canonicalize(pixelDepth, unit));
    }

    /** Converts one raw scale into a typed, canonical value. */
    public static CanonicalScale canonicalize(double rawValue, String unit) {
        String normalized = normalize(unit);
        Unit unitKind = classifyUnit(normalized);
        if (unitKind.state != State.PHYSICAL) {
            return missing(rawValue, unit, normalized, unitKind.state);
        }
        if (!Double.isFinite(rawValue)) {
            return missing(rawValue, unit, normalized, State.NONFINITE_SCALE);
        }
        if (rawValue <= 0.0) {
            return missing(rawValue, unit, normalized, State.NONPOSITIVE_SCALE);
        }
        double microns = rawValue * unitKind.micronMultiplier;
        if (!Double.isFinite(microns)) {
            return missing(rawValue, unit, normalized, State.NONFINITE_SCALE);
        }
        return new CanonicalScale(rawValue, unit, normalized, microns, State.PHYSICAL);
    }

    /** Canonical values are already converted; returning the same instance prevents double conversion. */
    public static CanonicalCalibration canonicalize(CanonicalCalibration calibration) {
        if (calibration == null) {
            CanonicalScale missing = missingCalibration();
            return new CanonicalCalibration(missing, missing, missing);
        }
        return calibration;
    }

    /** Canonical scales are already converted; returning the same instance prevents double conversion. */
    public static CanonicalScale canonicalize(CanonicalScale scale) {
        return scale == null ? missingCalibration() : scale;
    }

    /** Returns a typed axis result so callers can distinguish pixels, bad values, and unknown units. */
    public static CanonicalScale pixelSize(ImagePlus image, Axis axis) {
        return canonicalize(image).axis(axis);
    }

    /**
     * Legacy numeric adapter. Missing or invalid calibration is represented by
     * {@code NaN}; it is never silently replaced with one micron.
     */
    static double pixelSizeUm(ImagePlus image, Axis axis) {
        return pixelSize(image, axis).microns();
    }

    /** Converts a valid positive physical scale, failing explicitly otherwise. */
    static double toMicrons(double value, String unit) {
        return canonicalize(value, unit).requireMicrons();
    }

    private static CanonicalScale missingCalibration() {
        return missing(Double.NaN, null, "", State.MISSING_CALIBRATION);
    }

    private static CanonicalScale missing(double rawValue, String originalUnit,
                                          String normalizedUnit, State state) {
        return new CanonicalScale(rawValue, originalUnit, normalizedUnit, Double.NaN, state);
    }

    private static Unit classifyUnit(String normalized) {
        if (normalized.isEmpty()) {
            return new Unit(State.MISSING_UNIT, Double.NaN);
        }
        if ("pixel".equals(normalized) || "pixels".equals(normalized) || "px".equals(normalized)) {
            return new Unit(State.PIXEL_UNIT, Double.NaN);
        }
        if ("um".equals(normalized) || "\u00b5m".equals(normalized) || "\u03bcm".equals(normalized)
                || "micron".equals(normalized) || "microns".equals(normalized)
                || "micrometer".equals(normalized) || "micrometers".equals(normalized)
                || "micrometre".equals(normalized) || "micrometres".equals(normalized)) {
            return new Unit(State.PHYSICAL, 1.0);
        }
        if ("nm".equals(normalized) || "nanometer".equals(normalized) || "nanometers".equals(normalized)
                || "nanometre".equals(normalized) || "nanometres".equals(normalized)) {
            return new Unit(State.PHYSICAL, 0.001);
        }
        if ("mm".equals(normalized) || "millimeter".equals(normalized) || "millimeters".equals(normalized)
                || "millimetre".equals(normalized) || "millimetres".equals(normalized)) {
            return new Unit(State.PHYSICAL, 1000.0);
        }
        if ("cm".equals(normalized) || "centimeter".equals(normalized) || "centimeters".equals(normalized)
                || "centimetre".equals(normalized) || "centimetres".equals(normalized)) {
            return new Unit(State.PHYSICAL, 10000.0);
        }
        if ("m".equals(normalized) || "meter".equals(normalized) || "meters".equals(normalized)
                || "metre".equals(normalized) || "metres".equals(normalized)) {
            return new Unit(State.PHYSICAL, 1000000.0);
        }
        return new Unit(State.UNKNOWN_UNIT, Double.NaN);
    }

    private static String failureMessage(CanonicalScale scale) {
        switch (scale.state()) {
            case MISSING_CALIBRATION:
                return "Spatial calibration metadata is missing.";
            case PIXEL_UNIT:
                return "Spatial calibration is in pixels, not microns.";
            case MISSING_UNIT:
                return "Spatial calibration unit is missing.";
            case UNKNOWN_UNIT:
                return "Unknown spatial calibration unit: " + scale.originalUnit();
            case NONFINITE_SCALE:
                return "Spatial calibration scale must be finite: " + scale.rawValue();
            case NONPOSITIVE_SCALE:
                return "Spatial calibration scale must be positive: " + scale.rawValue();
            default:
                return "Spatial calibration is invalid.";
        }
    }

    private static String normalize(String unit) {
        String normalized = unit == null ? "" : unit.trim().toLowerCase(Locale.ROOT);
        normalized = normalized.replace(" ", "").replace("_", "").replace("-", "");
        if (normalized.endsWith("/pixels")) {
            normalized = normalized.substring(0, normalized.length() - "/pixels".length()).trim();
        } else if (normalized.endsWith("/pixel")) {
            normalized = normalized.substring(0, normalized.length() - "/pixel".length()).trim();
        } else if (normalized.endsWith("/px")) {
            normalized = normalized.substring(0, normalized.length() - "/px".length()).trim();
        }
        return normalized;
    }

    private static final class Unit {
        private final State state;
        private final double micronMultiplier;

        private Unit(State state, double micronMultiplier) {
            this.state = state;
            this.micronMultiplier = micronMultiplier;
        }
    }
}
