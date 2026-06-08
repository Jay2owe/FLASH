package flash.pipeline.intelligence.identity;

/**
 * A single identity field as contributed by a strategy or resolved by the
 * {@link IdentityResolver}: the value, the {@link Confidence}, and a short
 * human-readable provenance string ("from LIF series name", "your pattern",
 * "cross-batch frequency", ...). Immutable.
 */
public final class FieldValue {

    public final String value;
    public final Confidence confidence;
    public final String provenance;

    public FieldValue(String value, Confidence confidence, String provenance) {
        this.value = value == null ? "" : value;
        this.confidence = confidence == null ? Confidence.NONE : confidence;
        this.provenance = provenance == null ? "" : provenance;
    }

    public boolean isBlank() {
        return value.trim().isEmpty();
    }

    @Override
    public String toString() {
        return "FieldValue{value=" + value + ", confidence=" + confidence
                + ", provenance=" + provenance + "}";
    }
}
