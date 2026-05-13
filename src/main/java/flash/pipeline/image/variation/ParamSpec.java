package flash.pipeline.image.variation;

/**
 * Specification for one numeric parameter of a filter operation.
 */
public final class ParamSpec {

    public enum Scale { LINEAR, LOG }

    public final String name;
    public final String argKey;
    public final double defaultValue;
    public final double min;
    public final double max;
    public final Scale scale;
    public final String unit;
    public final boolean isInteger;

    public ParamSpec(String name, String argKey, double defaultValue,
                     double min, double max, Scale scale,
                     String unit, boolean isInteger) {
        if (name == null || name.length() == 0) {
            throw new IllegalArgumentException("name must not be empty");
        }
        if (argKey == null || argKey.length() == 0) {
            throw new IllegalArgumentException("argKey must not be empty");
        }
        if (scale == null) {
            throw new IllegalArgumentException("scale must not be null");
        }
        if (min > max) {
            throw new IllegalArgumentException(
                    "min (" + min + ") must be <= max (" + max + ")");
        }
        this.name = name;
        this.argKey = argKey;
        this.defaultValue = defaultValue;
        this.min = min;
        this.max = max;
        this.scale = scale;
        this.unit = unit == null ? "" : unit;
        this.isInteger = isInteger;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof ParamSpec)) return false;
        ParamSpec other = (ParamSpec) obj;
        return name.equals(other.name)
                && argKey.equals(other.argKey)
                && Double.compare(defaultValue, other.defaultValue) == 0
                && Double.compare(min, other.min) == 0
                && Double.compare(max, other.max) == 0
                && scale == other.scale
                && unit.equals(other.unit)
                && isInteger == other.isInteger;
    }

    @Override
    public int hashCode() {
        int result = name.hashCode();
        result = 31 * result + argKey.hashCode();
        long d = Double.doubleToLongBits(defaultValue);
        result = 31 * result + (int) (d ^ (d >>> 32));
        long lo = Double.doubleToLongBits(min);
        result = 31 * result + (int) (lo ^ (lo >>> 32));
        long hi = Double.doubleToLongBits(max);
        result = 31 * result + (int) (hi ^ (hi >>> 32));
        result = 31 * result + scale.hashCode();
        result = 31 * result + unit.hashCode();
        result = 31 * result + (isInteger ? 1 : 0);
        return result;
    }

    @Override
    public String toString() {
        return "ParamSpec{" + argKey + "=" + defaultValue
                + " [" + min + ".." + max + "] " + scale
                + (unit.length() == 0 ? "" : " " + unit)
                + (isInteger ? " int" : "") + "}";
    }
}
