package flash.pipeline.naming;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Mutable holder for per-animal condition values across one or more
 * {@link ConditionAxis axes} — the N-condition data model.
 *
 * <p>Storage is {@code animal -> (axisId -> value)} with insertion-ordered
 * iteration. The schema (the ordered list of {@link ConditionAxis}) is kept
 * separately so columns/exports are self-describing. The single legacy
 * {@code AnimalName,Condition} mapping is one axis named {@code Condition}
 * (see {@link #ofLegacy}); {@link #composite} collapses every axis back to a
 * single label for back-compat consumers.
 *
 * <p>Accessors return defensive copies so callers cannot mutate internal state.
 */
public final class ConditionAssignments {

    private final List<ConditionAxis> axes = new ArrayList<ConditionAxis>();
    private final Map<String, Map<String, String>> byAnimal =
            new LinkedHashMap<String, Map<String, String>>();

    public ConditionAssignments() {
    }

    /**
     * Build a single-axis assignment named {@code Condition} from a legacy
     * {@code animal -> condition} map. This is the bridge used when reading a
     * legacy {@code AnimalName,Condition} manifest.
     */
    public static ConditionAssignments ofLegacy(Map<String, String> animalToCondition) {
        ConditionAssignments out = new ConditionAssignments();
        ConditionAxis axis = ConditionAxis.of("Condition");
        out.addAxis(axis);
        if (animalToCondition != null) {
            for (Map.Entry<String, String> e : animalToCondition.entrySet()) {
                if (e.getKey() == null) continue;
                out.put(e.getKey(), axis.id, e.getValue());
            }
        }
        return out;
    }

    /** The axis schema, in order (defensive copy). */
    public List<ConditionAxis> axes() {
        return new ArrayList<ConditionAxis>(axes);
    }

    /** The axis with the given (normalised) id, or {@code null}. */
    public ConditionAxis axisById(String axisId) {
        String norm = ConditionAxis.normaliseId(axisId);
        for (ConditionAxis a : axes) {
            if (a.id.equals(norm)) return a;
        }
        return null;
    }

    /** Register an axis (idempotent on id; insertion order preserved). */
    public void addAxis(ConditionAxis axis) {
        if (axis == null) return;
        for (ConditionAxis a : axes) {
            if (a.id.equals(axis.id)) return;
        }
        axes.add(axis);
    }

    /** Value for {@code animal} on the given axis, or {@code ""} if unset. */
    public String get(String animal, String axisId) {
        if (animal == null) return "";
        Map<String, String> row = byAnimal.get(animal);
        if (row == null) return "";
        String v = row.get(ConditionAxis.normaliseId(axisId));
        return v == null ? "" : v;
    }

    /** Set {@code animal}'s value on the given axis. Null value stored as {@code ""}. */
    public void put(String animal, String axisId, String value) {
        if (animal == null) return;
        String norm = ConditionAxis.normaliseId(axisId);
        if (norm.isEmpty()) return;
        Map<String, String> row = byAnimal.get(animal);
        if (row == null) {
            row = new LinkedHashMap<String, String>();
            byAnimal.put(animal, row);
        }
        row.put(norm, value == null ? "" : value);
    }

    /** Animals carrying at least one assignment, in insertion order (defensive copy). */
    public Set<String> animals() {
        return new LinkedHashSet<String>(byAnimal.keySet());
    }

    /** An animal's {@code axisId -> value} view (defensive copy). */
    public Map<String, String> valuesFor(String animal) {
        Map<String, String> row = animal == null ? null : byAnimal.get(animal);
        return row == null ? new LinkedHashMap<String, String>()
                : new LinkedHashMap<String, String>(row);
    }

    /**
     * Ordered, de-duplicated, non-blank values seen for one axis — for
     * condition dropdowns (C2) and rename/merge (C4).
     */
    public Set<String> distinctValues(String axisId) {
        String norm = ConditionAxis.normaliseId(axisId);
        Set<String> out = new LinkedHashSet<String>();
        for (Map<String, String> row : byAnimal.values()) {
            String v = row.get(norm);
            if (v != null && !v.trim().isEmpty()) out.add(v);
        }
        return out;
    }

    /**
     * Collapse an animal's axis values into one label, joined in axis order
     * with {@code delimiter} (default {@code _}), skipping blank axes. This is
     * the back-compat single-condition view.
     */
    public String composite(String animal, String delimiter) {
        String d = delimiter == null ? "_" : delimiter;
        StringBuilder sb = new StringBuilder();
        for (ConditionAxis a : axes) {
            String v = get(animal, a.id);
            if (v == null || v.trim().isEmpty()) continue;
            if (sb.length() > 0) sb.append(d);
            sb.append(v.trim());
        }
        return sb.toString();
    }

    public boolean isEmpty() {
        return byAnimal.isEmpty();
    }

    /** Number of animals carrying at least one assignment. */
    public int size() {
        return byAnimal.size();
    }
}
