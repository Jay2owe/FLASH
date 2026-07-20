package flash.pipeline.ui.variations;

public final class SlotSubstitutionKey implements ParameterKey {

    public enum Axis {
        FILTER,
        SCALE
    }

    private final int stepIndex;
    private final String roleName;
    private final Axis axis;

    private SlotSubstitutionKey(int stepIndex, String roleName, Axis axis) {
        if (stepIndex < 0) {
            throw new IllegalArgumentException("stepIndex must be >= 0");
        }
        if (axis == null) {
            throw new IllegalArgumentException("axis must not be null");
        }
        this.stepIndex = stepIndex;
        this.roleName = roleName == null ? "" : roleName.trim();
        this.axis = axis;
    }

    public static SlotSubstitutionKey filterAxis(int stepIndex, String roleName) {
        return new SlotSubstitutionKey(stepIndex, roleName, Axis.FILTER);
    }

    public static SlotSubstitutionKey scaleAxis(int stepIndex, String roleName) {
        return new SlotSubstitutionKey(stepIndex, roleName, Axis.SCALE);
    }

    public int stepIndex() {
        return stepIndex;
    }

    public String roleName() {
        return roleName;
    }

    public Axis axis() {
        return axis;
    }

    public boolean orderable() {
        return true;
    }

    @Override public String stableKey() {
        return "filter.slot."
                + stepIndex
                + "."
                + roleName
                + "."
                + axis.name().toLowerCase(java.util.Locale.ROOT);
    }

    @Override public String displayLabel() {
        return axis == Axis.FILTER ? "filter" : "scale";
    }

    @Override public ValueKind valueKind() {
        return ValueKind.STRING;
    }

    @Override public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof SlotSubstitutionKey)) return false;
        SlotSubstitutionKey other = (SlotSubstitutionKey) obj;
        return stepIndex == other.stepIndex
                && roleName.equals(other.roleName)
                && axis == other.axis;
    }

    @Override public int hashCode() {
        int result = stepIndex;
        result = 31 * result + roleName.hashCode();
        result = 31 * result + axis.hashCode();
        return result;
    }

    @Override public String toString() {
        return stableKey();
    }
}
