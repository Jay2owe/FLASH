package flash.pipeline.naming;

import java.util.Locale;

/**
 * One experimental grouping dimension in the N-condition model, e.g.
 * {@code "Genotype"} or {@code "Timepoint"}. Immutable.
 *
 * <p>Identity is the normalised {@link #id}: two axes with the same id
 * (case-insensitive, separator-insensitive) are {@link #equals equal}
 * regardless of display {@link #label} or {@link #order}. This lets the
 * single legacy {@code AnimalName,Condition} mapping be represented as one
 * axis named {@code Condition} while richer studies carry several axes.
 *
 * @see ConditionAssignments
 */
public final class ConditionAxis {

    /** Stable, normalised key (lower-case, non-alphanumerics collapsed to {@code _}). */
    public final String id;
    /** Display label, e.g. {@code "Genotype"}. */
    public final String label;
    /** Column order within the condition block of the manifest table. */
    public final int order;

    public ConditionAxis(String id, String label, int order) {
        String normId = normaliseId(id);
        if (normId.isEmpty()) {
            // Fall back to deriving the id from the display label.
            normId = normaliseId(label);
        }
        this.id = normId;
        this.label = label == null ? "" : label.trim();
        this.order = order;
    }

    /** Axis whose id is derived from {@code label}, order {@code 0}. */
    public static ConditionAxis of(String label) {
        return new ConditionAxis(null, label, 0);
    }

    /** Axis with an explicit id, display label and order. */
    public static ConditionAxis of(String id, String label, int order) {
        return new ConditionAxis(id, label, order);
    }

    /**
     * Column name used for this axis in {@code Conditions.csv}, e.g.
     * {@code Condition_Genotype}. Whitespace in the label is collapsed to a single
     * underscore (not removed) so the header is a single token that still
     * {@link #normaliseId normalises} back to this axis's {@link #id} on read
     * (e.g. {@code "Time Point"} -&gt; {@code Condition_Time_Point} -&gt; id
     * {@code time_point}); CSV quoting is handled by the writer.
     */
    public String csvColumnName() {
        String base = label.isEmpty() ? id : label;
        String token = base.replaceAll("\\s+", "_");
        return "Condition_" + token;
    }

    /**
     * Normalise an id/label to a stable key: lower-case, runs of
     * non-alphanumeric characters collapsed to a single underscore, with
     * leading/trailing underscores trimmed.
     */
    public static String normaliseId(String raw) {
        if (raw == null) return "";
        String lower = raw.trim().toLowerCase(Locale.ROOT);
        String collapsed = lower.replaceAll("[^a-z0-9]+", "_");
        int start = 0;
        int end = collapsed.length();
        while (start < end && collapsed.charAt(start) == '_') start++;
        while (end > start && collapsed.charAt(end - 1) == '_') end--;
        return collapsed.substring(start, end);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ConditionAxis)) return false;
        return id.equals(((ConditionAxis) o).id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        return "ConditionAxis{id=" + id + ", label=" + label + ", order=" + order + "}";
    }
}
