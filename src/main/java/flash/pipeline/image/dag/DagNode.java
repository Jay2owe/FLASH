package flash.pipeline.image.dag;

import flash.pipeline.image.FilterMacroParser.OpType;

public final class DagNode {
    public final String id;
    public final OpType type;
    public final String args;
    public final String commandName;
    public final String menuPath;

    /**
     * Stage 04 toggles this through the inline eye control. Disabled nodes are
     * omitted from the emitted IJM body but always serialized in the embedded
     * DAG JSON header so a round-trip preserves the field. The field is mutable
     * by design: the UI flips it in place, and equality/hash include it so two
     * DAGs that differ only by disabled state are not considered equal.
     */
    public boolean disabled;

    public DagNode(OpType type, String args) {
        this("", type, args);
    }

    public DagNode(String id, OpType type, String args) {
        this(id, type, args, "", "");
    }

    public DagNode(String id, OpType type, String args, String commandName, String menuPath) {
        this.id = id == null ? "" : id;
        this.type = type == null ? OpType.UNKNOWN : type;
        this.args = args == null ? "" : args;
        this.commandName = commandName == null ? "" : commandName;
        this.menuPath = menuPath == null ? "" : menuPath;
        this.disabled = false;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof DagNode)) return false;
        DagNode other = (DagNode) obj;
        return id.equals(other.id)
                && type == other.type
                && args.equals(other.args)
                && commandName.equals(other.commandName)
                && menuPath.equals(other.menuPath)
                && disabled == other.disabled;
    }

    @Override
    public int hashCode() {
        int result = id.hashCode();
        result = 31 * result + type.hashCode();
        result = 31 * result + args.hashCode();
        result = 31 * result + commandName.hashCode();
        result = 31 * result + menuPath.hashCode();
        result = 31 * result + (disabled ? 1 : 0);
        return result;
    }
}
