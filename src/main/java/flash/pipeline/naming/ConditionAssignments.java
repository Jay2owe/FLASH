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

    /**
     * Group label for an animal under a chosen axis. When {@code axisId} is
     * {@code null}/blank the {@link #composite(String, String) composite} of all
     * axes is returned (the back-compat single label); otherwise the value of
     * that single axis is returned. This is the one place downstream consumers
     * pick "group by one axis" vs "group by the full combination" without ever
     * parsing the composite string themselves.
     */
    public String groupLabel(String animal, String axisId) {
        if (axisId == null || ConditionAxis.normaliseId(axisId).isEmpty()) {
            return composite(animal, "_");
        }
        return get(animal, axisId);
    }

    /**
     * Ordered, de-duplicated, non-blank group labels across all animals for the
     * chosen axis (or the composite when {@code axisId} is blank) — the group
     * order for per-axis aggregation/statistics/Excel.
     */
    public Set<String> distinctLabels(String axisId) {
        Set<String> out = new LinkedHashSet<String>();
        for (String animal : byAnimal.keySet()) {
            String label = groupLabel(animal, axisId);
            if (label != null && !label.trim().isEmpty()) out.add(label.trim());
        }
        return out;
    }

    /**
     * {@code label -> animals} for the chosen axis (or composite when blank),
     * preserving animal insertion order within each group and label first-seen
     * order across groups. Animals with a blank label are skipped.
     */
    public Map<String, List<String>> groupAnimalsByAxis(String axisId) {
        Map<String, List<String>> out = new LinkedHashMap<String, List<String>>();
        for (String animal : byAnimal.keySet()) {
            String label = groupLabel(animal, axisId);
            if (label == null || label.trim().isEmpty()) continue;
            String key = label.trim();
            List<String> members = out.get(key);
            if (members == null) {
                members = new ArrayList<String>();
                out.put(key, members);
            }
            members.add(animal);
        }
        return out;
    }

    /**
     * Axes ordered by descending number of distinct non-blank values (ties keep
     * schema order) — used to pick the two highest-cardinality axes for the
     * sample-sheet grid. Axes with no values are dropped.
     */
    public List<ConditionAxis> axesByCardinalityDescending() {
        List<ConditionAxis> ordered = new ArrayList<ConditionAxis>();
        for (ConditionAxis a : axes) {
            if (!distinctValues(a.id).isEmpty()) ordered.add(a);
        }
        // Stable insertion sort by descending distinct-value count (schema order on ties).
        for (int i = 1; i < ordered.size(); i++) {
            ConditionAxis key = ordered.get(i);
            int keyCard = distinctValues(key.id).size();
            int j = i - 1;
            while (j >= 0 && distinctValues(ordered.get(j).id).size() < keyCard) {
                ordered.set(j + 1, ordered.get(j));
                j--;
            }
            ordered.set(j + 1, key);
        }
        return ordered;
    }

    public boolean isEmpty() {
        return byAnimal.isEmpty();
    }

    /** Number of animals carrying at least one assignment. */
    public int size() {
        return byAnimal.size();
    }
}
