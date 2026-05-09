package flash.pipeline.image.dag;

import flash.pipeline.image.FilterMacroParser.OpType;

/**
 * Emits a Sandbox DAG as an IJ1 macro fallback.
 *
 * The native executor is still preferred for Sandbox-authored filters. This
 * macro exists so saved custom filters remain portable through the existing
 * legacy macro path.
 */
public final class DagToIjmEmitter {

    private DagToIjmEmitter() {}

    public static String emit(DagIR dag) {
        if (dag == null) throw new IllegalArgumentException("DAG must not be null");
        StringBuilder sb = new StringBuilder();
        sb.append("// @ihf-dag v1 executionTier=").append(dag.executionTier).append('\n');
        sb.append("// ").append(DagIRSerializer.toJson(dag)).append('\n');
        sb.append("source_id = getImageID();\n");

        for (int i = 0; i < dag.lines.size(); i++) {
            DagLine line = dag.lines.get(i);
            String lineId = macroIdentifier(line.id);
            sb.append("selectImage(source_id);\n");
            sb.append("run(\"Duplicate...\", \"title=").append(escapeMacroArg(lineId)).append(" duplicate\");\n");
            sb.append(lineId).append(" = getImageID();\n");
            for (int j = 0; j < line.ops.size(); j++) {
                DagNode node = line.ops.get(j);
                // Disabled nodes still appear in the embedded DAG JSON header
                // (serialized above via DagIRSerializer.toJson), so a reload
                // restores them. They are simply omitted from the IJM body so
                // the macro skips them at execution time.
                if (node.disabled) continue;
                sb.append(emitRun(node));
            }
        }

        for (int i = 0; i < dag.combiners.size(); i++) {
            Combiner combiner = dag.combiners.get(i);
            if (combiner.inputs.size() < 2) continue;
            String currentTitle = macroIdentifier(combiner.inputs.get(0));
            for (int j = 1; j < combiner.inputs.size(); j++) {
                String nextTitle = macroIdentifier(combiner.inputs.get(j));
                sb.append("imageCalculator(\"").append(imageCalculatorCommand(combiner.op))
                        .append(" create\", \"").append(escapeMacroArg(currentTitle))
                        .append("\", \"").append(escapeMacroArg(nextTitle)).append("\");\n");
                String outTitle = macroIdentifier(combiner.id);
                if (j < combiner.inputs.size() - 1) {
                    outTitle = macroIdentifier(combiner.id + "_" + j);
                }
                sb.append("rename(\"").append(escapeMacroArg(outTitle)).append("\");\n");
                currentTitle = outTitle;
            }
            sb.append(macroIdentifier(combiner.id)).append(" = getImageID();\n");
        }

        String output = macroTitleForId(dag.output);
        if (output.length() > 0) {
            sb.append("selectImage(\"").append(escapeMacroArg(output)).append("\");\n");
        }
        return sb.toString();
    }

    private static String emitRun(DagNode node) {
        String command = commandFor(node.type);
        boolean legacyCommand = false;
        if (command == null && node.commandName != null && node.commandName.trim().length() > 0) {
            command = node.commandName.trim();
            legacyCommand = true;
        }
        if (command == null) {
            return "// UNKNOWN node omitted: " + node.id + "\n";
        }
        String args = node.args == null ? "" : node.args;
        if (args.trim().isEmpty()) {
            return "run(\"" + command + "\");\n";
        }
        if (legacyCommand) {
            return "run(\"" + escapeMacroArg(command) + "\", \"" + escapeMacroArg(args) + "\");\n";
        }
        return "run(\"" + command + "...\", \"" + escapeMacroArg(args) + "\");\n";
    }

    public static String commandFor(OpType type) {
        if (type == null) return null;
        switch (type) {
            case GAUSSIAN_BLUR: return "Gaussian Blur";
            case SUBTRACT_BACKGROUND: return "Subtract Background";
            case MEDIAN: return "Median";
            case MEAN: return "Mean";
            case UNSHARP_MASK: return "Unsharp Mask";
            case MINIMUM: return "Minimum";
            case MAXIMUM: return "Maximum";
            case VARIANCE: return "Variance";
            case DILATE: return "Dilate";
            case ERODE: return "Erode";
            case OPEN: return "Open";
            case CLOSE_: return "Close-";
            case FILL_HOLES: return "Fill Holes";
            case SKELETONIZE: return "Skeletonize";
            case INVERT: return "Invert";
            case ADD: return "Add";
            case SUBTRACT: return "Subtract";
            case MULTIPLY: return "Multiply";
            case DIVIDE: return "Divide";
            case AUTO_LOCAL_THRESHOLD: return "Auto Local Threshold";
            case CONVERT_8BIT: return "8-bit";
            case CONVERT_16BIT: return "16-bit";
            case CONVERT_32BIT: return "32-bit";
            case ENHANCE_CONTRAST: return "Enhance Contrast";
            case GAUSSIAN_BLUR_3D: return "Gaussian Blur 3D";
            case MEDIAN_3D: return "Median 3D";
            case MINIMUM_3D: return "Minimum 3D";
            default: return null;
        }
    }

    private static String imageCalculatorCommand(CombinerOp op) {
        if (op == null) return "Add";
        switch (op) {
            case AND: return "AND";
            case OR: return "OR";
            case ADD: return "Add";
            case SUBTRACT: return "Subtract";
            case DIFFERENCE: return "Difference";
            case AVG: return "Average";
            case MAX: return "Max";
            case MIN: return "Min";
            default: return "Add";
        }
    }

    public static String macroTitleForId(String id) {
        return macroIdentifier(id);
    }

    private static String macroIdentifier(String id) {
        if (id == null || id.trim().isEmpty()) return "";
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

    private static String escapeMacroArg(String text) {
        if (text == null) return "";
        return text.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
