package flash.pipeline.image.dag;

import flash.pipeline.image.FilterMacroParser;
import flash.pipeline.image.FilterMacroParser.Op;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Seeds a Sandbox DAG from saved IJ1 macro text.
 */
public final class IjmToDagLoader {

    /**
     * Mirrors {@code FilterMacroParser.RUN_PATTERN}. We re-apply it here to
     * recover the command name from unknown-tier {@code run(...)} lines so
     * stage 03/04 can preserve those nodes through the DAG round-trip via the
     * embedded JSON header. Without this, unknown commands lost their
     * {@code commandName} and {@link DagToIjmEmitter} silently dropped them.
     */
    private static final Pattern RUN_PATTERN = Pattern.compile(
            "run\\s*\\(\\s*\"([^\"]+)\"(?:\\s*,\\s*\"([^\"]*)\")?\\s*\\)");

    private IjmToDagLoader() {}

    public static DagIR load(String macroContent) {
        DagIR embedded = loadEmbeddedDag(macroContent);
        if (embedded != null) return embedded;

        List<Op> ops = FilterMacroParser.parseString(macroContent);
        List<DagNode> nodes = new ArrayList<DagNode>();
        boolean legacy = false;
        for (int i = 0; i < ops.size(); i++) {
            Op op = ops.get(i);
            String nodeId = "node_" + (i + 1);
            if (op.type == FilterMacroParser.OpType.UNKNOWN) {
                legacy = true;
                Matcher matcher = RUN_PATTERN.matcher(op.args);
                if (matcher.find()) {
                    String cmd = matcher.group(1).trim();
                    if (cmd.endsWith("...")) {
                        cmd = cmd.substring(0, cmd.length() - 3).trim();
                    }
                    String runArgs = matcher.group(2) == null ? "" : matcher.group(2);
                    nodes.add(new DagNode(nodeId, op.type, runArgs, cmd, ""));
                } else {
                    // Non-run statement (selectWindow, imageCalculator, assignment, ...);
                    // keep the raw line in args so the legacy executor can replay it
                    // when the embedded JSON header is preserved through round-trip.
                    nodes.add(new DagNode(nodeId, op.type, op.args));
                }
            } else {
                nodes.add(new DagNode(nodeId, op.type, op.args));
            }
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
