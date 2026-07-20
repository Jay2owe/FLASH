package flash.pipeline.orientation;

import flash.pipeline.naming.OrientationManifestRow;

import java.util.Locale;

/**
 * Named reusable ROI orientation transform.
 */
public final class OrientationPreset {
    public final String name;
    public final OrientationTransformState transform;

    public OrientationPreset(String name, OrientationTransformState transform) {
        String normalizedName = name == null ? "" : name.trim();
        if (normalizedName.length() == 0) {
            throw new IllegalArgumentException("Preset name must not be blank");
        }

        this.name = normalizedName;
        this.transform = transform == null ? OrientationTransformState.identity() : transform;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof OrientationPreset)) return false;

        OrientationPreset that = (OrientationPreset) other;
        return normalizedName(name).equals(normalizedName(that.name))
                && sameTransform(transform, that.transform);
    }

    @Override
    public int hashCode() {
        int result = normalizedName(name).hashCode();
        result = 31 * result + transformHash(transform);
        return result;
    }

    @Override
    public String toString() {
        return "OrientationPreset{"
                + "name='" + name + '\''
                + ", rotateDegrees=" + transform.rotateDegrees.degrees()
                + ", flipHorizontal=" + transform.flipHorizontal
                + ", flipVertical=" + transform.flipVertical
                + '}';
    }

    private static String normalizedName(String value) {
        return value.toLowerCase(Locale.ROOT);
    }

    private static boolean sameTransform(OrientationTransformState first,
                                         OrientationTransformState second) {
        return first.rotateDegrees == second.rotateDegrees
                && first.flipHorizontal == second.flipHorizontal
                && first.flipVertical == second.flipVertical;
    }

    private static int transformHash(OrientationTransformState state) {
        int result = state.rotateDegrees == null
                ? OrientationManifestRow.RotationDegrees.DEG_0.hashCode()
                : state.rotateDegrees.hashCode();
        result = 31 * result + (state.flipHorizontal ? 1 : 0);
        result = 31 * result + (state.flipVertical ? 1 : 0);
        return result;
    }
}
