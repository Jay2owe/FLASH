package flash.pipeline.image.dag;

import flash.pipeline.image.FilterMacroParser.OpType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class DagIRSerializer {
    private DagIRSerializer() {}

    public static String toJson(DagIR dag) {
        if (dag == null) throw new IllegalArgumentException("DAG must not be null");
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        appendField(sb, "version", Integer.toString(dag.version));
        sb.append(",");
        appendField(sb, "executionTier", quote(dag.executionTier));
        sb.append(",");
        sb.append(quote("lines")).append(":[");
        for (int i = 0; i < dag.lines.size(); i++) {
            if (i > 0) sb.append(",");
            DagLine line = dag.lines.get(i);
            sb.append("{");
            appendField(sb, "id", quote(line.id));
            sb.append(",");
            sb.append(quote("ops")).append(":[");
            for (int j = 0; j < line.ops.size(); j++) {
                if (j > 0) sb.append(",");
                DagNode node = line.ops.get(j);
                sb.append("{");
                appendField(sb, "id", quote(node.id));
                sb.append(",");
                appendField(sb, "type", quote(node.type.name()));
                sb.append(",");
                appendField(sb, "args", quote(node.args));
                if (node.commandName.length() > 0 || node.menuPath.length() > 0) {
                    sb.append(",");
                    appendField(sb, "commandName", quote(node.commandName));
                    sb.append(",");
                    appendField(sb, "menuPath", quote(node.menuPath));
                }
                if (node.disabled) {
                    sb.append(",");
                    appendField(sb, "disabled", "true");
                }
                sb.append("}");
            }
            sb.append("]}");
        }
        sb.append("],");
        sb.append(quote("combiners")).append(":[");
        for (int i = 0; i < dag.combiners.size(); i++) {
            if (i > 0) sb.append(",");
            Combiner combiner = dag.combiners.get(i);
            sb.append("{");
            appendField(sb, "id", quote(combiner.id));
            sb.append(",");
            appendField(sb, "op", quote(combiner.op.name()));
            sb.append(",");
            sb.append(quote("inputs")).append(":[");
            for (int j = 0; j < combiner.inputs.size(); j++) {
                if (j > 0) sb.append(",");
                sb.append(quote(combiner.inputs.get(j)));
            }
            sb.append("]}");
        }
        sb.append("],");
        appendField(sb, "output", quote(dag.output));
        sb.append("}");
        return sb.toString();
    }

    public static DagIR fromJson(String json) {
        Object root = new JsonParser(json).parse();
        Map<String, Object> obj = asObject(root, "root");
        int version = asInt(required(obj, "version"), "version");
        String executionTier = asString(required(obj, "executionTier"), "executionTier");
        String output = asString(required(obj, "output"), "output");

        List<DagLine> lines = new ArrayList<DagLine>();
        List<Object> rawLines = asArray(required(obj, "lines"), "lines");
        for (int i = 0; i < rawLines.size(); i++) {
            Map<String, Object> rawLine = asObject(rawLines.get(i), "lines[" + i + "]");
            String id = asString(required(rawLine, "id"), "lines[" + i + "].id");
            List<DagNode> ops = new ArrayList<DagNode>();
            List<Object> rawOps = asArray(required(rawLine, "ops"), "lines[" + i + "].ops");
            for (int j = 0; j < rawOps.size(); j++) {
                Map<String, Object> rawNode = asObject(rawOps.get(j),
                        "lines[" + i + "].ops[" + j + "]");
                String nodeId = asString(required(rawNode, "id"), "node.id");
                String typeName = asString(required(rawNode, "type"), "node.type");
                String args = asString(optional(rawNode, "args", ""), "node.args");
                String commandName = asString(optional(rawNode, "commandName", ""), "node.commandName");
                String menuPath = asString(optional(rawNode, "menuPath", ""), "node.menuPath");
                Object rawDisabled = optional(rawNode, "disabled", Boolean.FALSE);
                boolean disabled = rawDisabled instanceof Boolean
                        ? ((Boolean) rawDisabled).booleanValue()
                        : false;
                DagNode dagNode = new DagNode(nodeId, parseOpType(typeName), args, commandName, menuPath);
                dagNode.disabled = disabled;
                ops.add(dagNode);
            }
            lines.add(new DagLine(id, ops));
        }

        List<Combiner> combiners = new ArrayList<Combiner>();
        List<Object> rawCombiners = asArray(required(obj, "combiners"), "combiners");
        for (int i = 0; i < rawCombiners.size(); i++) {
            Map<String, Object> rawCombiner = asObject(rawCombiners.get(i),
                    "combiners[" + i + "]");
            String id = asString(required(rawCombiner, "id"), "combiner.id");
            String opName = asString(required(rawCombiner, "op"), "combiner.op");
            List<Object> rawInputs = asArray(required(rawCombiner, "inputs"), "combiner.inputs");
            List<String> inputs = new ArrayList<String>();
            for (int j = 0; j < rawInputs.size(); j++) {
                inputs.add(asString(rawInputs.get(j), "combiner.inputs[" + j + "]"));
            }
            combiners.add(new Combiner(id, CombinerOp.valueOf(opName), inputs));
        }

        validate(version, executionTier, output, lines, combiners);
        return new DagIR(version, lines, combiners, output, executionTier);
    }

    private static void appendField(StringBuilder sb, String name, String value) {
        sb.append(quote(name)).append(":").append(value);
    }

    private static String quote(String value) {
        String v = value == null ? "" : value;
        StringBuilder sb = new StringBuilder(v.length() + 2);
        sb.append('"');
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        String hex = Integer.toHexString(c);
                        sb.append("\\u");
                        for (int j = hex.length(); j < 4; j++) sb.append('0');
                        sb.append(hex);
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
        return sb.toString();
    }

    private static void validate(int version, String tier, String output,
                                 List<DagLine> lines, List<Combiner> combiners) {
        if (version < 1) throw new IllegalArgumentException("Unsupported DAG version: " + version);
        if (!"native".equals(tier) && !"legacy".equals(tier)) {
            throw new IllegalArgumentException("executionTier must be native or legacy");
        }
        if (output.length() == 0) throw new IllegalArgumentException("output is required");

        Map<String, Boolean> ids = new LinkedHashMap<String, Boolean>();
        for (DagLine line : lines) {
            if (line.id.length() == 0) throw new IllegalArgumentException("Line id is required");
            if (ids.containsKey(line.id)) throw new IllegalArgumentException("Duplicate DAG id: " + line.id);
            ids.put(line.id, Boolean.TRUE);
        }
        for (Combiner combiner : combiners) {
            if (combiner.id.length() == 0) throw new IllegalArgumentException("Combiner id is required");
            if (combiner.inputs.size() < 2) {
                throw new IllegalArgumentException("Combiner requires at least two inputs: " + combiner.id);
            }
            for (String input : combiner.inputs) {
                if (!ids.containsKey(input)) {
                    throw new IllegalArgumentException("Unknown combiner input: " + input);
                }
            }
            if (ids.containsKey(combiner.id)) throw new IllegalArgumentException("Duplicate DAG id: " + combiner.id);
            ids.put(combiner.id, Boolean.TRUE);
        }
        if (!ids.containsKey(output)) throw new IllegalArgumentException("Unknown DAG output: " + output);
    }

    private static OpType parseOpType(String value) {
        try {
            return OpType.valueOf(value);
        } catch (RuntimeException e) {
            return OpType.UNKNOWN;
        }
    }

    private static Object required(Map<String, Object> obj, String key) {
        if (!obj.containsKey(key)) throw new IllegalArgumentException("Missing field: " + key);
        return obj.get(key);
    }

    private static Object optional(Map<String, Object> obj, String key, Object defaultValue) {
        if (!obj.containsKey(key)) return defaultValue;
        return obj.get(key);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asObject(Object value, String label) {
        if (!(value instanceof Map)) throw new IllegalArgumentException(label + " must be an object");
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> asArray(Object value, String label) {
        if (!(value instanceof List)) throw new IllegalArgumentException(label + " must be an array");
        return (List<Object>) value;
    }

    private static String asString(Object value, String label) {
        if (!(value instanceof String)) throw new IllegalArgumentException(label + " must be a string");
        return (String) value;
    }

    private static int asInt(Object value, String label) {
        if (!(value instanceof Number)) throw new IllegalArgumentException(label + " must be a number");
        return ((Number) value).intValue();
    }

    private static final class JsonParser {
        private final String text;
        private int pos;

        JsonParser(String text) {
            this.text = text == null ? "" : text;
        }

        Object parse() {
            Object value = parseValue();
            skipWhitespace();
            if (pos != text.length()) throw error("Unexpected trailing content");
            return value;
        }

        private Object parseValue() {
            skipWhitespace();
            if (pos >= text.length()) throw error("Unexpected end of JSON");
            char c = text.charAt(pos);
            if (c == '{') return parseObject();
            if (c == '[') return parseArray();
            if (c == '"') return parseString();
            if (c == '-' || (c >= '0' && c <= '9')) return parseNumber();
            if (text.startsWith("true", pos)) { pos += 4; return Boolean.TRUE; }
            if (text.startsWith("false", pos)) { pos += 5; return Boolean.FALSE; }
            if (text.startsWith("null", pos)) { pos += 4; return null; }
            throw error("Unexpected token");
        }

        private Map<String, Object> parseObject() {
            expect('{');
            Map<String, Object> map = new LinkedHashMap<String, Object>();
            skipWhitespace();
            if (peek('}')) { pos++; return map; }
            while (true) {
                skipWhitespace();
                String key = parseString();
                skipWhitespace();
                expect(':');
                map.put(key, parseValue());
                skipWhitespace();
                if (peek('}')) { pos++; return map; }
                expect(',');
            }
        }

        private List<Object> parseArray() {
            expect('[');
            List<Object> list = new ArrayList<Object>();
            skipWhitespace();
            if (peek(']')) { pos++; return list; }
            while (true) {
                list.add(parseValue());
                skipWhitespace();
                if (peek(']')) { pos++; return list; }
                expect(',');
            }
        }

        private String parseString() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (pos < text.length()) {
                char c = text.charAt(pos++);
                if (c == '"') return sb.toString();
                if (c != '\\') {
                    sb.append(c);
                    continue;
                }
                if (pos >= text.length()) throw error("Unterminated escape");
                char esc = text.charAt(pos++);
                switch (esc) {
                    case '"': sb.append('"'); break;
                    case '\\': sb.append('\\'); break;
                    case '/': sb.append('/'); break;
                    case 'b': sb.append('\b'); break;
                    case 'f': sb.append('\f'); break;
                    case 'n': sb.append('\n'); break;
                    case 'r': sb.append('\r'); break;
                    case 't': sb.append('\t'); break;
                    case 'u':
                        if (pos + 4 > text.length()) throw error("Bad unicode escape");
                        String hex = text.substring(pos, pos + 4);
                        sb.append((char) Integer.parseInt(hex, 16));
                        pos += 4;
                        break;
                    default:
                        throw error("Bad escape");
                }
            }
            throw error("Unterminated string");
        }

        private Number parseNumber() {
            int start = pos;
            if (peek('-')) pos++;
            while (pos < text.length() && Character.isDigit(text.charAt(pos))) pos++;
            if (peek('.')) {
                pos++;
                while (pos < text.length() && Character.isDigit(text.charAt(pos))) pos++;
            }
            if (peek('e') || peek('E')) {
                pos++;
                if (peek('+') || peek('-')) pos++;
                while (pos < text.length() && Character.isDigit(text.charAt(pos))) pos++;
            }
            String n = text.substring(start, pos);
            if (n.indexOf('.') >= 0 || n.indexOf('e') >= 0 || n.indexOf('E') >= 0) {
                return Double.valueOf(n);
            }
            return Long.valueOf(n);
        }

        private void skipWhitespace() {
            while (pos < text.length()) {
                char c = text.charAt(pos);
                if (c != ' ' && c != '\n' && c != '\r' && c != '\t') return;
                pos++;
            }
        }

        private boolean peek(char expected) {
            return pos < text.length() && text.charAt(pos) == expected;
        }

        private void expect(char expected) {
            skipWhitespace();
            if (!peek(expected)) throw error("Expected '" + expected + "'");
            pos++;
        }

        private IllegalArgumentException error(String message) {
            return new IllegalArgumentException(message + " at offset " + pos);
        }
    }
}
