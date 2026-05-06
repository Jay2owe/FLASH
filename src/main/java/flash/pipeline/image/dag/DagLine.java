package flash.pipeline.image.dag;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class DagLine {
    public final String id;
    public final List<DagNode> ops;

    public DagLine(String id, List<DagNode> ops) {
        this.id = id == null ? "" : id;
        if (ops == null) {
            this.ops = Collections.emptyList();
        } else {
            this.ops = Collections.unmodifiableList(new ArrayList<DagNode>(ops));
        }
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof DagLine)) return false;
        DagLine other = (DagLine) obj;
        return id.equals(other.id) && ops.equals(other.ops);
    }

    @Override
    public int hashCode() {
        int result = id.hashCode();
        result = 31 * result + ops.hashCode();
        return result;
    }
}
