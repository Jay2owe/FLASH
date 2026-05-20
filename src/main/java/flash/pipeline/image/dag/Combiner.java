package flash.pipeline.image.dag;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class Combiner {
    public final String id;
    public final CombinerOp op;
    public final List<String> inputs;

    public Combiner(String id, CombinerOp op, List<String> inputs) {
        this.id = id == null ? "" : id;
        this.op = op == null ? CombinerOp.ADD : op;
        if (inputs == null) {
            this.inputs = Collections.emptyList();
        } else {
            this.inputs = Collections.unmodifiableList(new ArrayList<String>(inputs));
        }
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof Combiner)) return false;
        Combiner other = (Combiner) obj;
        return id.equals(other.id)
                && op == other.op
                && inputs.equals(other.inputs);
    }

    @Override
    public int hashCode() {
        int result = id.hashCode();
        result = 31 * result + op.hashCode();
        result = 31 * result + inputs.hashCode();
        return result;
    }
}
