package flash.pipeline.image.variation;

import flash.pipeline.image.dag.DagIR;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One planned variant: display label, mutated DAG, and parameter delta.
 */
public final class VariantPlan {

    public final String label;
    public final DagIR dag;
    public final Map<String, String> paramDelta;

    public VariantPlan(String label, DagIR dag, Map<String, String> paramDelta) {
        if (dag == null) throw new IllegalArgumentException("dag must not be null");
        this.label = label == null ? "" : label;
        this.dag = dag;
        if (paramDelta == null || paramDelta.isEmpty()) {
            this.paramDelta = Collections.emptyMap();
        } else {
            this.paramDelta = Collections.unmodifiableMap(
                    new LinkedHashMap<String, String>(paramDelta));
        }
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof VariantPlan)) return false;
        VariantPlan other = (VariantPlan) obj;
        return label.equals(other.label)
                && dag.equals(other.dag)
                && paramDelta.equals(other.paramDelta);
    }

    @Override
    public int hashCode() {
        int result = label.hashCode();
        result = 31 * result + dag.hashCode();
        result = 31 * result + paramDelta.hashCode();
        return result;
    }
}
