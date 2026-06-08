package flash.pipeline.intelligence.identity;

import flash.pipeline.naming.ConditionAxis;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What a single {@link IdentityStrategy} contributes for one record. Any field
 * may be left unset ({@code null}) meaning "no opinion"; blank values are
 * dropped so a strategy can offer a field unconditionally and have it ignored
 * when empty. Condition axes are keyed by normalised axis id.
 *
 * <p>Setters return {@code this} for fluent construction.
 */
public final class PartialIdentity {

    private FieldValue animal;
    private FieldValue hemisphere;
    private FieldValue region;
    private final Map<String, FieldValue> conditions = new LinkedHashMap<String, FieldValue>();

    public PartialIdentity animal(String value, Confidence c, String provenance) {
        this.animal = make(value, c, provenance);
        return this;
    }

    public PartialIdentity hemisphere(String value, Confidence c, String provenance) {
        this.hemisphere = make(value, c, provenance);
        return this;
    }

    public PartialIdentity region(String value, Confidence c, String provenance) {
        this.region = make(value, c, provenance);
        return this;
    }

    public PartialIdentity condition(String axisId, String value, Confidence c, String provenance) {
        String norm = ConditionAxis.normaliseId(axisId);
        FieldValue fv = make(value, c, provenance);
        if (!norm.isEmpty() && fv != null) {
            conditions.put(norm, fv);
        }
        return this;
    }

    public FieldValue animal() { return animal; }
    public FieldValue hemisphere() { return hemisphere; }
    public FieldValue region() { return region; }
    public Map<String, FieldValue> conditions() { return Collections.unmodifiableMap(conditions); }

    private static FieldValue make(String value, Confidence c, String provenance) {
        if (value == null || value.trim().isEmpty()) return null;
        return new FieldValue(value, c, provenance);
    }
}
