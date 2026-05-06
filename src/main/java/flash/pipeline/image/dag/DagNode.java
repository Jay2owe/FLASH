package flash.pipeline.image.dag;

import flash.pipeline.image.FilterMacroParser.OpType;

public final class DagNode {
    public final String id;
    public final OpType type;
    public final String args;
    public final String commandName;
    public final String menuPath;

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
                && menuPath.equals(other.menuPath);
    }

    @Override
    public int hashCode() {
        int result = id.hashCode();
        result = 31 * result + type.hashCode();
        result = 31 * result + args.hashCode();
        result = 31 * result + commandName.hashCode();
        result = 31 * result + menuPath.hashCode();
        return result;
    }
}
