package flash.pipeline.ui.sandbox;

import flash.pipeline.image.FilterMacroParser.OpType;
import flash.pipeline.image.dag.Combiner;
import flash.pipeline.image.dag.CombinerOp;
import flash.pipeline.image.dag.DagIR;
import flash.pipeline.image.dag.DagLine;
import flash.pipeline.image.dag.DagNode;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

final class SandboxModel {
    static final int MAX_LINES = 4;

    final List<Line> lines = new ArrayList<Line>();
    final List<CombinerNode> combiners = new ArrayList<CombinerNode>();
    Object selected;
    private int nextNode = 1;
    private int nextCombiner = 1;

    static SandboxModel fromDag(DagIR dag) {
        SandboxModel model = new SandboxModel();
        if (dag != null) {
            for (int i = 0; i < dag.lines.size(); i++) {
                DagLine dagLine = dag.lines.get(i);
                Line line = new Line(dagLine.id);
                for (int j = 0; j < dagLine.ops.size(); j++) {
                    DagNode node = dagLine.ops.get(j);
                    Node n = new Node(node.id.length() == 0 ? "node_" + model.nextNode++ : node.id,
                            node.type, node.args, node.commandName, node.menuPath);
                    n.disabled = node.disabled;
                    line.nodes.add(n);
                }
                model.lines.add(line);
            }
            for (int i = 0; i < dag.combiners.size(); i++) {
                Combiner c = dag.combiners.get(i);
                model.combiners.add(new CombinerNode(c.id, c.op, c.inputs));
            }
        }
        if (model.lines.isEmpty()) {
            model.lines.add(new Line("line_A"));
        }
        model.reseedCounters();
        model.selected = model.lines.get(0);
        return model;
    }

    DagIR toDag() {
        List<DagLine> dagLines = new ArrayList<DagLine>();
        for (int i = 0; i < lines.size(); i++) {
            Line line = lines.get(i);
            List<DagNode> nodes = new ArrayList<DagNode>();
            for (int j = 0; j < line.nodes.size(); j++) {
                Node node = line.nodes.get(j);
                DagNode dn = new DagNode(node.id, node.type, node.args, node.commandName, node.menuPath);
                dn.disabled = node.disabled;
                nodes.add(dn);
            }
            dagLines.add(new DagLine(line.id, nodes));
        }
        List<Combiner> dagCombiners = new ArrayList<Combiner>();
        for (int i = 0; i < combiners.size(); i++) {
            CombinerNode combiner = combiners.get(i);
            dagCombiners.add(new Combiner(combiner.id, combiner.op, combiner.inputs));
        }
        String output = dagCombiners.isEmpty()
                ? lines.get(0).id
                : dagCombiners.get(dagCombiners.size() - 1).id;
        return new DagIR(1, dagLines, dagCombiners, output, executionTier(dagLines));
    }

    DagIR toPartialDag() {
        if (selected instanceof Node) {
            Node selectedNode = (Node) selected;
            List<DagLine> partialLines = new ArrayList<DagLine>();
            for (int i = 0; i < lines.size(); i++) {
                Line line = lines.get(i);
                List<DagNode> nodes = new ArrayList<DagNode>();
                for (int j = 0; j < line.nodes.size(); j++) {
                    Node node = line.nodes.get(j);
                    nodes.add(new DagNode(node.id, node.type, node.args));
                    if (node == selectedNode) break;
                }
                partialLines.add(new DagLine(line.id, nodes));
                if (line.nodes.contains(selectedNode)) {
                    return new DagIR(1, partialLines, Collections.<Combiner>emptyList(),
                            line.id, executionTier(partialLines));
                }
            }
        }
        if (selected instanceof CombinerNode) {
            CombinerNode selectedCombiner = (CombinerNode) selected;
            List<Combiner> partialCombiners = new ArrayList<Combiner>();
            DagIR full = toDag();
            for (int i = 0; i < combiners.size(); i++) {
                CombinerNode c = combiners.get(i);
                partialCombiners.add(new Combiner(c.id, c.op, c.inputs));
                if (c == selectedCombiner) {
                    return new DagIR(1, full.lines, partialCombiners, c.id, full.executionTier);
                }
            }
        }
        return toDag();
    }

    void addLine() {
        if (lines.size() >= MAX_LINES) return;
        Line line = new Line("line_" + (char) ('A' + lines.size()));
        lines.add(line);
        selected = line;
    }

    void addNode(Line line, FilterCatalog.Entry entry) {
        addNode(line, entry, entry == null ? "" : entry.defaultArgs);
    }

    void addNode(Line line, FilterCatalog.Entry entry, String args) {
        if (line == null || entry == null || entry.stub || entry.type == null) return;
        Node node = new Node("node_" + nextNode++, entry.type, args,
                entry.legacy ? entry.commandName : "",
                entry.legacy ? entry.menuPath : "");
        line.nodes.add(node);
        selected = node;
    }

    boolean hasLegacyNode() {
        for (int i = 0; i < lines.size(); i++) {
            Line line = lines.get(i);
            for (int j = 0; j < line.nodes.size(); j++) {
                if (line.nodes.get(j).isLegacy()) return true;
            }
        }
        return false;
    }

    void removeNode(Line line, Node node) {
        if (line == null || node == null) return;
        line.nodes.remove(node);
        if (selected == node) selected = line;
    }

    void removeLine(Line line) {
        if (line == null || lines.size() <= 1) return;
        lines.remove(line);
        for (int i = combiners.size() - 1; i >= 0; i--) {
            if (combiners.get(i).inputs.contains(line.id)) combiners.remove(i);
        }
        selected = lines.get(0);
    }

    void addCombiner() {
        if (lines.size() < 2) return;
        CombinerNode combiner = new CombinerNode("combiner_" + nextCombiner++, CombinerOp.AND,
                Arrays.asList(lines.get(0).id, lines.get(1).id));
        combiners.add(combiner);
        selected = combiner;
    }

    void removeCombiner(CombinerNode combiner) {
        combiners.remove(combiner);
        if (selected == combiner) selected = lines.get(0);
    }

    private void reseedCounters() {
        nextNode = 1;
        nextCombiner = 1;
        for (int i = 0; i < lines.size(); i++) {
            Line line = lines.get(i);
            for (int j = 0; j < line.nodes.size(); j++) {
                nextNode++;
            }
        }
        nextCombiner = combiners.size() + 1;
    }

    private static String executionTier(List<DagLine> dagLines) {
        for (int i = 0; i < dagLines.size(); i++) {
            DagLine line = dagLines.get(i);
            for (int j = 0; j < line.ops.size(); j++) {
                DagNode node = line.ops.get(j);
                if (node.commandName.length() > 0 || node.type == OpType.UNKNOWN) {
                    return "legacy";
                }
            }
        }
        return "native";
    }

    static final class Line {
        final String id;
        final List<Node> nodes = new ArrayList<Node>();

        Line(String id) {
            this.id = id == null || id.trim().isEmpty() ? "line_A" : id;
        }
    }

    static final class Node {
        final String id;
        OpType type;
        String args;
        String commandName;
        String menuPath;
        // Stage 04: tracked here so the panel's structural API can flip the
        // eye toggle. fromDag/toDag round-trip the value through DagNode.disabled.
        boolean disabled;

        Node(String id, OpType type, String args) {
            this(id, type, args, "", "");
        }

        Node(String id, OpType type, String args, String commandName, String menuPath) {
            this.id = id == null || id.trim().isEmpty() ? "node" : id;
            this.type = type == null ? OpType.UNKNOWN : type;
            this.args = args == null ? "" : args;
            this.commandName = commandName == null ? "" : commandName;
            this.menuPath = menuPath == null ? "" : menuPath;
            this.disabled = false;
        }

        boolean isLegacy() {
            return commandName.length() > 0 || type == OpType.UNKNOWN;
        }
    }

    static final class CombinerNode {
        final String id;
        CombinerOp op;
        List<String> inputs;

        CombinerNode(String id, CombinerOp op, List<String> inputs) {
            this.id = id == null || id.trim().isEmpty() ? "combiner" : id;
            this.op = op == null ? CombinerOp.AND : op;
            this.inputs = inputs == null
                    ? new ArrayList<String>()
                    : new ArrayList<String>(inputs);
        }
    }
}
