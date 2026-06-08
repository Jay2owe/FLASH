package flash.pipeline.intelligence.identity;

import flash.pipeline.naming.ConditionAxis;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The resolved identity for one record, accumulated by {@link IdentityResolver}
 * from ordered strategies. Each field carries its winning value, confidence and
 * provenance. Filled first-wins: once a field is set, a lower-precedence
 * strategy cannot override it (so user grammar/roster beat heuristics).
 */
public final class IdentityCandidate {

    private FieldValue animal;
    private FieldValue hemisphere;
    private FieldValue region;
    private final Map<String, FieldValue> conditions = new LinkedHashMap<String, FieldValue>();

    /** Merge a strategy's contribution, keeping any field already set. */
    void fillFrom(PartialIdentity partial) {
        if (partial == null) return;
        if (animal == null && partial.animal() != null) animal = partial.animal();
        if (hemisphere == null && partial.hemisphere() != null) hemisphere = partial.hemisphere();
        if (region == null && partial.region() != null) region = partial.region();
        for (Map.Entry<String, FieldValue> e : partial.conditions().entrySet()) {
            if (!conditions.containsKey(e.getKey())) {
                conditions.put(e.getKey(), e.getValue());
            }
        }
    }

    public FieldValue getAnimal() { return animal; }
    public FieldValue getHemisphere() { return hemisphere; }
    public FieldValue getRegion() { return region; }

    public FieldValue getCondition(String axisId) {
        return conditions.get(ConditionAxis.normaliseId(axisId));
    }

    /** All resolved condition axes, keyed by normalised axis id. */
    public Map<String, FieldValue> conditions() {
        return Collections.unmodifiableMap(conditions);
    }

    public String animalValue() { return valueOf(animal); }
    public String hemisphereValue() { return valueOf(hemisphere); }
    public String regionValue() { return valueOf(region); }
    public String conditionValue(String axisId) { return valueOf(getCondition(axisId)); }

    private static String valueOf(FieldValue fv) {
        return fv == null ? "" : fv.value;
    }
}
