package flash.pipeline.naming;

import java.util.LinkedHashSet;
import java.util.List;

/**
 * Display-only copy for the N-axis condition model (the "condition matrix").
 *
 * <p>This is the single source of the user-facing wording for multi-axis
 * conditions, e.g. {@code "Genotype × Timepoint"},
 * {@code "Full condition matrix (Genotype × Timepoint)"},
 * {@code "Genotype only"}, and the Project Builder status line. Centralising it
 * keeps the matrix vocabulary consistent across the project builder, condition
 * review, statistics, and exports.
 *
 * <p>Everything here is display-only: no output is ever fed back into
 * {@link ConditionAxis#normaliseId}, a CSV header, or a JSON key. The
 * {@link #CROSS multiplication sign} and the words "matrix"/"axes" appear only in
 * labels, tooltips, banners and cell text.
 *
 * <p>A project with fewer than two axes is treated as single-axis: the helpers
 * fall back to the plain word "Condition" and never introduce matrix language.
 */
public final class ConditionMatrixCopy {

    /** The multiplication sign used to join axis labels (display only). */
    public static final String CROSS = "×";

    private ConditionMatrixCopy() {}

    /** True when there are at least two condition axes (the single matrix gate). */
    public static boolean isMulti(List<ConditionAxis> axes) {
        return axes != null && axes.size() >= 2;
    }

    /** Display label for one axis: its label, else its id, else {@code "Condition"}. */
    public static String axisLabel(ConditionAxis axis) {
        if (axis == null) return "Condition";
        if (axis.label != null && !axis.label.trim().isEmpty()) return axis.label.trim();
        if (axis.id != null && !axis.id.isEmpty()) return axis.id;
        return "Condition";
    }

    /** Axis labels joined with {@code " × "}, e.g. {@code "Genotype × Timepoint"}. */
    public static String axesLabel(List<ConditionAxis> axes) {
        if (axes == null || axes.isEmpty()) return "Condition";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < axes.size(); i++) {
            if (i > 0) sb.append(' ').append(CROSS).append(' ');
            sb.append(axisLabel(axes.get(i)));
        }
        return sb.toString();
    }

    /**
     * Grouping label for the full combination, e.g.
     * {@code "Full condition matrix (Genotype × Timepoint)"}. A single-axis
     * project returns the plain word {@code "Condition"}.
     */
    public static String matrixGroupingLabel(List<ConditionAxis> axes) {
        if (!isMulti(axes)) return "Condition";
        return "Full condition matrix (" + axesLabel(axes) + ")";
    }

    /** Per-axis grouping label, e.g. {@code "Genotype only"}. */
    public static String axisOnlyLabel(ConditionAxis axis) {
        return axisLabel(axis) + " only";
    }

    /**
     * One-line status for a project's condition assignments.
     *
     * <p>Multi-axis:
     * {@code "Condition axes: Genotype × Timepoint  (3 × 3 = 9 groups, 12 animals)"}
     * where the per-axis counts are the distinct values seen on each axis and the
     * product is the matrix <em>capacity</em> (the number of possible groups), not
     * the number of occupied groups (use {@link #populatedGroupCount} for that).
     *
     * <p>Single-axis: {@code "Condition: 3 values, 12 animals"} (no matrix wording).
     */
    public static String statusLine(ConditionAssignments assignments) {
        if (assignments == null) return "Condition: 0 values, 0 animals";
        List<ConditionAxis> axes = assignments.axes();
        int animals = assignments.size();
        if (!isMulti(axes)) {
            String axisId = axes.isEmpty() ? "condition" : axes.get(0).id;
            int values = assignments.distinctValues(axisId).size();
            return "Condition: " + values + (values == 1 ? " value, " : " values, ")
                    + animals + (animals == 1 ? " animal" : " animals");
        }
        StringBuilder counts = new StringBuilder();
        long capacity = 1;
        for (int i = 0; i < axes.size(); i++) {
            int v = assignments.distinctValues(axes.get(i).id).size();
            if (i > 0) counts.append(' ').append(CROSS).append(' ');
            counts.append(v);
            capacity *= v;
        }
        return "Condition axes: " + axesLabel(axes) + "  ("
                + counts + " = " + capacity + " groups, "
                + animals + (animals == 1 ? " animal" : " animals") + ")";
    }

    /**
     * Number of distinct, non-blank occupied composite groups (the matrix cells
     * that actually contain animals), as opposed to the theoretical capacity used
     * by {@link #statusLine}.
     */
    public static int populatedGroupCount(ConditionAssignments assignments) {
        if (assignments == null) return 0;
        LinkedHashSet<String> groups = new LinkedHashSet<String>();
        for (String animal : assignments.animals()) {
            String composite = assignments.composite(animal, "_");
            if (composite != null && !composite.trim().isEmpty()) {
                groups.add(composite.trim());
            }
        }
        return groups.size();
    }
}
