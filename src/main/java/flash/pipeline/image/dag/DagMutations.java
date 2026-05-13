package flash.pipeline.image.dag;

import flash.pipeline.image.FilterMacroParser.OpType;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure helpers that rebuild a DAG with one node changed.
 *
 * <p>Every node is copied because {@link DagNode#disabled} is mutable in the
 * builder UI. The rebuilt DAG asks for native execution so {@link DagIR}
 * recomputes legacy routing from the remaining enabled legacy nodes.</p>
 */
public final class DagMutations {

    private DagMutations() {}

    public static DagIR withNodeArgs(DagIR src, String nodeId, String newArgs) {
        return rebuildWith(src, nodeId, new NodeRewriter() {
            @Override public DagNode rewrite(DagNode node) {
                DagNode copy = new DagNode(node.id, node.type, newArgs,
                        node.commandName, node.menuPath);
                copy.disabled = node.disabled;
                return copy;
            }
        });
    }

    public static DagIR withNodeSubstituted(DagIR src, String nodeId,
                                            final OpType newType, final String newArgs) {
        return rebuildWith(src, nodeId, new NodeRewriter() {
            @Override public DagNode rewrite(DagNode node) {
                DagNode copy = new DagNode(node.id, newType, newArgs, "", "");
                copy.disabled = node.disabled;
                return copy;
            }
        });
    }

    private static DagIR rebuildWith(DagIR src, String nodeId, NodeRewriter rewriter) {
        if (src == null) throw new IllegalArgumentException("src must not be null");
        if (nodeId == null) throw new IllegalArgumentException("nodeId must not be null");

        boolean found = false;
        List<DagLine> lines = new ArrayList<DagLine>(src.lines.size());
        for (int i = 0; i < src.lines.size(); i++) {
            DagLine line = src.lines.get(i);
            List<DagNode> ops = new ArrayList<DagNode>(line.ops.size());
            for (int j = 0; j < line.ops.size(); j++) {
                DagNode node = line.ops.get(j);
                if (nodeId.equals(node.id)) {
                    ops.add(rewriter.rewrite(node));
                    found = true;
                } else {
                    ops.add(copyNode(node));
                }
            }
            lines.add(new DagLine(line.id, ops));
        }
        if (!found) throw new IllegalArgumentException("nodeId not found: " + nodeId);
        return new DagIR(src.version, lines, src.combiners, src.output, "native");
    }

    private interface NodeRewriter {
        DagNode rewrite(DagNode node);
    }

    private static DagNode copyNode(DagNode node) {
        DagNode copy = new DagNode(node.id, node.type, node.args,
                node.commandName, node.menuPath);
        copy.disabled = node.disabled;
        return copy;
    }
}
