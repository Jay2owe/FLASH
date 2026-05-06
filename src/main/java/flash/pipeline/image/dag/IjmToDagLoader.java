package flash.pipeline.image.dag;

import flash.pipeline.image.FilterMacroParser;
import flash.pipeline.image.FilterMacroParser.Op;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Seeds a Sandbox DAG from saved IJ1 macro text.
 */
public final class IjmToDagLoader {

    private IjmToDagLoader() {}

    public static DagIR load(String macroContent) {
        DagIR embedded = loadEmbeddedDag(macroContent);
        if (embedded != null) return embedded;

        List<Op> ops = FilterMacroParser.parseString(macroContent);
        List<DagNode> nodes = new ArrayList<DagNode>();
        boolean legacy = false;
        for (int i = 0; i < ops.size(); i++) {
            Op op = ops.get(i);
            if (op.type == FilterMacroParser.OpType.UNKNOWN) legacy = true;
            nodes.add(new DagNode("node_" + (i + 1), op.type, op.args));
        }
        DagLine line = new DagLine("line_A", nodes);
        return new DagIR(1,
                Collections.singletonList(line),
                Collections.<Combiner>emptyList(),
                line.id,
                legacy ? "legacy" : "native");
    }

    public static DagIR loadEmbeddedDag(String macroContent) {
        if (macroContent == null) return null;
        String[] lines = macroContent.replace("\r\n", "\n").replace('\r', '\n').split("\n");
        boolean sawHeader = false;
        for (int i = 0; i < lines.length; i++) {
            String trimmed = lines[i].trim();
            if (trimmed.startsWith("// @ihf-dag")) {
                sawHeader = true;
                continue;
            }
            if (!sawHeader) continue;
            if (!trimmed.startsWith("//")) return null;
            String body = trimmed.substring(2).trim();
            if (body.startsWith("{")) {
                return DagIRSerializer.fromJson(body);
            }
        }
        return null;
    }
}
