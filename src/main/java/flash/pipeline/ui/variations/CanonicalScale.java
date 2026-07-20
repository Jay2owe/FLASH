package flash.pipeline.ui.variations;

import flash.pipeline.image.FilterMacroParser.OpType;

import java.util.Locale;

public enum CanonicalScale {
    SMALL("small"),
    MEDIUM("medium"),
    LARGE("large");

    private final String label;

    CanonicalScale(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public static CanonicalScale fromLabel(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        for (CanonicalScale scale : values()) {
            if (scale.label.equals(normalized)
                    || scale.name().toLowerCase(Locale.ROOT).equals(normalized)) {
                return scale;
            }
        }
        return null;
    }

    public static ScaleValue valueFor(OpType filterType, CanonicalScale scale) {
        if (filterType == null || scale == null) {
            return ScaleValue.none();
        }
        switch (filterType) {
            case GAUSSIAN_BLUR:
                return ScaleValue.of("sigma", value(scale, 0.8d, 1.5d, 3.0d));
            case MEDIAN:
            case MEAN:
            case MINIMUM:
            case MAXIMUM:
            case VARIANCE:
                return ScaleValue.of("radius", value(scale, 2.0d, 4.0d, 8.0d));
            case SUBTRACT_BACKGROUND:
                return ScaleValue.of("rolling", value(scale, 25.0d, 50.0d, 100.0d));
            case AUTO_LOCAL_THRESHOLD:
                return ScaleValue.of("radius", value(scale, 7.0d, 15.0d, 31.0d));
            case DILATE:
            case ERODE:
            case OPEN:
            case CLOSE_:
            case FILL_HOLES:
            case SKELETONIZE:
                return ScaleValue.none();
            default:
                return ScaleValue.none();
        }
    }

    public static boolean isParameterless(OpType filterType) {
        return valueFor(filterType, MEDIUM).isNone();
    }

    public static String valueLabel(OpType filterType, CanonicalScale scale) {
        ScaleValue value = valueFor(filterType, scale);
        return value.isNone()
                ? "default"
                : shortParamLabel(value.paramKey()) + " " + format(value.value());
    }

    public static String comboLabel(String filterLabel,
                                    OpType filterType,
                                    CanonicalScale scale) {
        String safeFilter = filterLabel == null ? "" : filterLabel.trim();
        ScaleValue value = valueFor(filterType, scale);
        if (value.isNone()) {
            return safeFilter.length() == 0 ? "default" : safeFilter + " default";
        }
        return (safeFilter.length() == 0 ? "" : safeFilter + " ")
                + shortParamLabel(value.paramKey())
                + " "
                + format(value.value());
    }

    public static String format(double value) {
        if (Math.abs(value - Math.rint(value)) < 0.0000001d
                && Math.abs(value) < 1000000000.0d) {
            return String.valueOf((long) Math.rint(value));
        }
        String text = String.format(Locale.ROOT, "%.3f", Double.valueOf(value));
        return text.replaceAll("0+$", "").replaceAll("\\.$", "");
    }

    private static double value(CanonicalScale scale,
                                double small,
                                double medium,
                                double large) {
        switch (scale) {
            case SMALL:
                return small;
            case LARGE:
                return large;
            case MEDIUM:
            default:
                return medium;
        }
    }

    private static String shortParamLabel(String key) {
        String normalized = key == null ? "" : key.trim();
        if ("radius".equals(normalized)) {
            return "r";
        }
        return normalized;
    }

    public static final class ScaleValue {
        private static final ScaleValue NONE = new ScaleValue("", 0.0d, true);

        private final String paramKey;
        private final double value;
        private final boolean none;

        private ScaleValue(String paramKey, double value, boolean none) {
            this.paramKey = paramKey == null ? "" : paramKey;
            this.value = value;
            this.none = none;
        }

        public static ScaleValue of(String paramKey, double value) {
            String key = paramKey == null ? "" : paramKey.trim();
            if (key.isEmpty()) {
                return none();
            }
            return new ScaleValue(key, value, false);
        }

        public static ScaleValue none() {
            return NONE;
        }

        public boolean isNone() {
            return none;
        }

        public boolean hasValue() {
            return !none;
        }

        public String paramKey() {
            return paramKey;
        }

        public double value() {
            return value;
        }

        @Override public boolean equals(Object obj) {
            if (this == obj) return true;
            if (!(obj instanceof ScaleValue)) return false;
            ScaleValue other = (ScaleValue) obj;
            if (none != other.none) return false;
            if (none) return true;
            return paramKey.equals(other.paramKey)
                    && Double.compare(value, other.value) == 0;
        }

        @Override public int hashCode() {
            int result = paramKey.hashCode();
            long bits = Double.doubleToLongBits(value);
            result = 31 * result + (int) (bits ^ (bits >>> 32));
            result = 31 * result + (none ? 1 : 0);
            return result;
        }

        @Override public String toString() {
            return none ? "default" : paramKey + "=" + format(value);
        }
    }
}
