package flash.pipeline.image.variation;

import flash.pipeline.image.FilterMacroParser.OpType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One axis of variation: a node, a variation kind, and concrete alternatives.
 */
public final class VariantAxis {

    public enum Kind {
        PARAM_SWEEP,
        FILTER_SWAP
    }

    public final String nodeId;
    public final Kind kind;
    public final List<AlternativeValue> alternatives;

    public VariantAxis(String nodeId, Kind kind, List<AlternativeValue> alternatives) {
        if (nodeId == null || nodeId.length() == 0) {
            throw new IllegalArgumentException("nodeId must not be empty");
        }
        if (kind == null) {
            throw new IllegalArgumentException("kind must not be null");
        }
        this.nodeId = nodeId;
        this.kind = kind;
        if (alternatives == null) {
            this.alternatives = Collections.emptyList();
        } else {
            this.alternatives = Collections.unmodifiableList(
                    new ArrayList<AlternativeValue>(alternatives));
        }
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof VariantAxis)) return false;
        VariantAxis other = (VariantAxis) obj;
        return nodeId.equals(other.nodeId)
                && kind == other.kind
                && alternatives.equals(other.alternatives);
    }

    @Override
    public int hashCode() {
        int result = nodeId.hashCode();
        result = 31 * result + kind.hashCode();
        result = 31 * result + alternatives.hashCode();
        return result;
    }

    public static final class AlternativeValue {
        public final String label;
        public final OpType type;
        public final String args;

        public AlternativeValue(String label, OpType type, String args) {
            this.label = label == null ? "" : label;
            this.type = type;
            this.args = args == null ? "" : args;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (!(obj instanceof AlternativeValue)) return false;
            AlternativeValue other = (AlternativeValue) obj;
            return label.equals(other.label)
                    && type == other.type
                    && args.equals(other.args);
        }

        @Override
        public int hashCode() {
            int result = label.hashCode();
            result = 31 * result + (type == null ? 0 : type.hashCode());
            result = 31 * result + args.hashCode();
            return result;
        }
    }
}
