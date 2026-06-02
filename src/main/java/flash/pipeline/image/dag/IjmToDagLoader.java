package flash.pipeline.image.dag;

import flash.pipeline.image.FilterMacroParser;
import flash.pipeline.image.FilterMacroParser.Op;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Seeds a Sandbox DAG from saved IJ1 macro text.
 *
 * <p>REGRESSION GUARD (docs/filter-branch-robustness): this loader must never
 * (a) turn the per-branch {@code run("Duplicate...", "title=X duplicate")}
 * working-copy scaffolding into a filter node, (b) strip the {@code ...}
 * ellipsis from a recovered command name, or (c) flatten a genuinely branched
 * macro (one that uses {@code imageCalculator} or more than one
 * {@code Duplicate... title=} working copy) into a single "linear" line. Any of
 * those produced the {@code run("Duplicate", "x=2 y=2 z=1")} -> Image5D crash:
 * a {@code Duplicate} node (ellipsis dropped) that picked up a later filter's
 * args through positional re-matching.
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

    /** {@code title=<value>} inside a Duplicate args string. */
    private static final Pattern TITLE_PATTERN = Pattern.compile(
            "title\\s*=\\s*([^\\s\"]+)");

    /** {@code ident = getImageID()} / {@code ident = getTitle()} binding lines. */
    private static final Pattern IMAGE_HANDLE_ASSIGN = Pattern.compile(
            "^[A-Za-z_][A-Za-z0-9_]*\\s*=\\s*get(ImageID|Title)\\s*\\(\\s*\\)\\s*;?$");

    private IjmToDagLoader() {}

    public static DagIR load(String macroContent) {
        DagIR embedded = loadEmbeddedDag(macroContent);
        if (embedded != null) return embedded;
        return parseLegacyMacro(macroContent);
    }

    private static DagIR parseLegacyMacro(String macroContent) {
        List<Op> ops = FilterMacroParser.parseString(macroContent);
        boolean branched = isBranched(ops);
        return branched ? buildBranchedDag(ops) : buildLinearDag(ops);
    }

    // ── Linear (single-line) macros ─────────────────────────────────────────

    private static DagIR buildLinearDag(List<Op> ops) {
        List<DagNode> nodes = new ArrayList<DagNode>();
        boolean legacy = false;
        int[] counter = new int[]{0};
        for (int i = 0; i < ops.size(); i++) {
            Op op = ops.get(i);
            if (isScaffolding(op)) continue;
            DagNode node = toNode(op, counter);
            if (node == null) continue;
            if (node.type == FilterMacroParser.OpType.UNKNOWN) legacy = true;
            nodes.add(node);
        }
        DagLine line = new DagLine("line_A", nodes);
        return new DagIR(1,
                Collections.singletonList(line),
                Collections.<Combiner>emptyList(),
                line.id,
                legacy ? "legacy" : "native");
    }

    // ── Branched macros ─────────────────────────────────────────────────────
    //
    // We do not attempt a faithful execution model for branched legacy macros:
    // they run from their original text through the native/legacy executor, not
    // from a re-emitted DAG. The job here is only to produce a serializable,
    // genuinely non-linear DAG that never contains a Duplicate node, so the
    // editor routes the macro to the branched/Custom-builder view instead of
    // flattening and corrupting it. Combiners are intentionally omitted because
    // imageCalculator inputs reference renamed handles that need not match line
    // ids (DagIRSerializer.validate would reject them).

    private static DagIR buildBranchedDag(List<Op> ops) {
        Set<String> usedLineIds = new HashSet<String>();
        List<DagLine> lines = new ArrayList<DagLine>();
        int[] counter = new int[]{0};

        BranchLine source = new BranchLine("source");
        BranchLine current = source;
        List<BranchLine> duplicateLines = new ArrayList<BranchLine>();

        for (int i = 0; i < ops.size(); i++) {
            Op op = ops.get(i);
            String raw = op.type == FilterMacroParser.OpType.UNKNOWN ? safeTrim(op.args) : "";

            String dupTitle = duplicateTitle(op, raw);
            if (dupTitle != null) {
                BranchLine branch = new BranchLine(uniqueLineId(dupTitle, usedLineIds));
                duplicateLines.add(branch);
                current = branch;
                continue;
            }
            if (isStructuralBranchLine(op, raw)) {
                // selectWindow/selectImage/imageCalculator/rename/close/handle binds —
                // structural, never a filter node. Re-targeting back to a non-branch
                // handle resets the active line to the source path.
                if (isSelect(raw)) current = source;
                continue;
            }
            DagNode node = toNode(op, counter);
            if (node != null) current.nodes.add(node);
        }

        if (!source.nodes.isEmpty()) {
            lines.add(new DagLine(uniqueLineId("source", usedLineIds), source.nodes));
        }
        for (int i = 0; i < duplicateLines.size(); i++) {
            BranchLine branch = duplicateLines.get(i);
            lines.add(new DagLine(branch.id, branch.nodes));
        }
        if (lines.isEmpty()) {
            lines.add(new DagLine(uniqueLineId("line_A", usedLineIds),
                    Collections.<DagNode>emptyList()));
        }
        // Guarantee non-linearity for the (rare) single-duplicate + imageCalculator
        // shape so the editor never treats a branched macro as linear.
        if (lines.size() < 2) {
            lines.add(new DagLine(uniqueLineId("branch_b", usedLineIds),
                    Collections.<DagNode>emptyList()));
        }
        return new DagIR(1, lines, Collections.<Combiner>emptyList(),
                lines.get(0).id, "legacy");
    }

    private static final class BranchLine {
        final String id;
        final List<DagNode> nodes = new ArrayList<DagNode>();

        BranchLine(String id) {
            this.id = id;
        }
    }

    // ── Classification helpers ──────────────────────────────────────────────

    /** A macro is branched when it duplicates the source more than once or merges paths. */
    private static boolean isBranched(List<Op> ops) {
        int duplicateCount = 0;
        boolean hasCombine = false;
        for (int i = 0; i < ops.size(); i++) {
            Op op = ops.get(i);
            String raw = op.type == FilterMacroParser.OpType.UNKNOWN ? safeTrim(op.args) : "";
            if (duplicateTitle(op, raw) != null) duplicateCount++;
            if (raw.startsWith("imageCalculator")) hasCombine = true;
        }
        return hasCombine || duplicateCount >= 2;
    }

    /** Returns the duplicate working-copy title, or null when the op is not a Duplicate run. */
    private static String duplicateTitle(Op op, String raw) {
        if (op.type != FilterMacroParser.OpType.UNKNOWN || raw.isEmpty()) return null;
        Matcher m = RUN_PATTERN.matcher(raw);
        if (!m.find()) return null;
        if (!"duplicate".equals(normalizeCommand(m.group(1)))) return null;
        String args = m.group(2) == null ? "" : m.group(2);
        Matcher t = TITLE_PATTERN.matcher(args);
        if (t.find()) return t.group(1);
        return op.type == FilterMacroParser.OpType.UNKNOWN ? "branch" : null;
    }

    /** True for any line that is image-duplication / branch scaffolding, never a filter step. */
    private static boolean isScaffolding(Op op) {
        if (op.type != FilterMacroParser.OpType.UNKNOWN) return false;
        String raw = safeTrim(op.args);
        if (raw.isEmpty()) return true;
        if (duplicateTitle(op, raw) != null) return true;
        return isStructuralBranchLine(op, raw);
    }

    private static boolean isStructuralBranchLine(Op op, String raw) {
        if (op.type != FilterMacroParser.OpType.UNKNOWN || raw.isEmpty()) return false;
        if (IMAGE_HANDLE_ASSIGN.matcher(raw).matches()) return true;
        if (isSelect(raw)) return true;
        if (raw.startsWith("imageCalculator")) return true;
        if (raw.startsWith("rename")) return true;
        if (raw.startsWith("close")) return true;
        // A bare Duplicate run with no title= is still scaffolding in a filter context.
        Matcher m = RUN_PATTERN.matcher(raw);
        if (m.find() && "duplicate".equals(normalizeCommand(m.group(1)))) return true;
        return false;
    }

    private static boolean isSelect(String raw) {
        return raw.startsWith("selectWindow") || raw.startsWith("selectImage");
    }

    /**
     * Builds a node from an op. Real filter ops keep their type; legitimate but
     * unknown {@code run("Command...", args)} lines keep their command name
     * VERBATIM (including any {@code ...}); anything else is kept as a raw
     * legacy-replay node so the executor can still run it.
     */
    private static DagNode toNode(Op op, int[] counter) {
        String nodeId = "node_" + (++counter[0]);
        if (op.type != FilterMacroParser.OpType.UNKNOWN) {
            return new DagNode(nodeId, op.type, op.args);
        }
        String raw = safeTrim(op.args);
        Matcher matcher = RUN_PATTERN.matcher(raw);
        if (matcher.find()) {
            // Preserve the command name exactly as authored. Stripping "..." here
            // turned "Duplicate..." into "Duplicate", which ImageJ dispatches to
            // the Image5D plugin -> "Image is not an Image5D".
            String cmd = matcher.group(1).trim();
            String runArgs = matcher.group(2) == null ? "" : matcher.group(2);
            return new DagNode(nodeId, op.type, runArgs, cmd, "");
        }
        // Non-run statement (assignment, etc.); keep the raw line in args so the
        // legacy executor can replay it through the embedded JSON header.
        return new DagNode(nodeId, op.type, raw);
    }

    private static String normalizeCommand(String command) {
        if (command == null) return "";
        String s = command.trim();
        while (s.endsWith(".")) s = s.substring(0, s.length() - 1).trim();
        return s.toLowerCase(java.util.Locale.ROOT);
    }

    private static String uniqueLineId(String base, Set<String> used) {
        String id = sanitizeId(base);
        if (id.isEmpty()) id = "line";
        String candidate = id;
        int n = 1;
        while (used.contains(candidate)) {
            candidate = id + "_" + (n++);
        }
        used.add(candidate);
        return candidate;
    }

    private static String sanitizeId(String id) {
        if (id == null) return "";
        StringBuilder sb = new StringBuilder();
        String trimmed = id.trim();
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                    || (c >= '0' && c <= '9') || c == '_') {
                sb.append(c);
            } else {
                sb.append('_');
            }
        }
        return sb.toString();
    }

    private static String safeTrim(String s) {
        return s == null ? "" : s.trim();
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
