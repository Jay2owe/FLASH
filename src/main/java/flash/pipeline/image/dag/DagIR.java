package flash.pipeline.image.dag;

import flash.pipeline.image.FilterMacroParser.OpType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class DagIR {
    public final int version;
    public final List<DagLine> lines;
    public final List<Combiner> combiners;
    public final String output;
    public final String executionTier;

    public DagIR(int version, List<DagLine> lines, List<Combiner> combiners,
                 String output, String executionTier) {
        this.version = version;
        if (lines == null) {
            this.lines = Collections.emptyList();
        } else {
            this.lines = Collections.unmodifiableList(new ArrayList<DagLine>(lines));
        }
        if (combiners == null) {
            this.combiners = Collections.emptyList();
        } else {
            this.combiners = Collections.unmodifiableList(new ArrayList<Combiner>(combiners));
        }
        this.output = output == null ? "" : output;
        String requestedTier = executionTier == null ? "native" : executionTier;
        this.executionTier = hasTierTwoNode(this.lines) ? "legacy" : requestedTier;
    }

    /**
     * True when the DAG has no combiners and a single line whose id matches
     * {@code output}. Stages 03/04 use this to decide whether the inline
     * accordion can edit the pipeline directly, or whether the user must drop
     * into the canvas builder for branched / combined pipelines.
     */
    public boolean isLinear() {
        return combiners.isEmpty()
                && lines.size() == 1
                && output != null
                && output.length() > 0
                && output.equals(lines.get(0).id);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof DagIR)) return false;
        DagIR other = (DagIR) obj;
        return version == other.version
                && lines.equals(other.lines)
                && combiners.equals(other.combiners)
                && output.equals(other.output)
                && executionTier.equals(other.executionTier);
    }

    @Override
    public int hashCode() {
        int result = version;
        result = 31 * result + lines.hashCode();
        result = 31 * result + combiners.hashCode();
        result = 31 * result + output.hashCode();
        result = 31 * result + executionTier.hashCode();
        return result;
    }

    private static boolean hasTierTwoNode(List<DagLine> lines) {
        if (lines == null) return false;
        for (int i = 0; i < lines.size(); i++) {
            DagLine line = lines.get(i);
            if (line == null || line.ops == null) continue;
            for (int j = 0; j < line.ops.size(); j++) {
                DagNode node = line.ops.get(j);
                if (node == null || node.disabled) continue;
                if (node.type == OpType.UNKNOWN) return true;
                if (node.commandName.length() > 0) return true;
            }
        }
        return false;
    }
}
