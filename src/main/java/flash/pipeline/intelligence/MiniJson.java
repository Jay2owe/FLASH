package flash.pipeline.intelligence;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Small JSON reader/writer for the summary history store.
 * Supports objects, arrays, strings, booleans, null, and numbers.
 */
public final class MiniJson {

    private MiniJson() {}

    public static String write(Object value) {
        StringBuilder out = new StringBuilder();
        writeValue(out, value);
        return out.toString();
    }

    public static Object parse(String json) throws IOException {
        Parser parser = new Parser(json);
        Object value = parser.parseValue();
        parser.skipWhitespace();
        if (!parser.isAtEnd()) {
            throw new IOException("Unexpected trailing JSON content.");
        }
        return value;
    }

    private static void writeValue(StringBuilder out, Object value) {
        if (value == null) {
            out.append("null");
        } else if (value instanceof String) {
            writeString(out, (String) value);
        } else if (value instanceof Number) {
            Number number = (Number) value;
            if (number instanceof Double || number instanceof Float) {
                double d = number.doubleValue();
                if (Double.isNaN(d) || Double.isInfinite(d)) {
                    out.append("null");
                    return;
                }
            }
            out.append(number.toString());
        } else if (value instanceof Boolean) {
            out.append(Boolean.TRUE.equals(value) ? "true" : "false");
        } else if (value instanceof Map) {
            writeObject(out, (Map<?, ?>) value);
        } else if (value instanceof List) {
            writeArray(out, (List<?>) value);
        } else {
            writeString(out, String.valueOf(value));
        }
    }

    private static void writeObject(StringBuilder out, Map<?, ?> map) {
        out.append('{');
        boolean first = true;
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!first) out.append(',');
            writeString(out, String.valueOf(entry.getKey()));
            out.append(':');
            writeValue(out, entry.getValue());
            first = false;
        }
        out.append('}');
    }

    private static void writeArray(StringBuilder out, List<?> list) {
        out.append('[');
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) out.append(',');
            writeValue(out, list.get(i));
        }
        out.append(']');
    }

    private static void writeString(StringBuilder out, String value) {
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            switch (ch) {
                case '"':
                    out.append("\\\"");
                    break;
                case '\\':
                    out.append("\\\\");
                    break;
                case '\b':
                    out.append("\\b");
                    break;
                case '\f':
                    out.append("\\f");
                    break;
                case '\n':
                    out.append("\\n");
                    break;
                case '\r':
                    out.append("\\r");
                    break;
                case '\t':
                    out.append("\\t");
                    break;
                default:
                    if (ch < 0x20) {
                        out.append(String.format(Locale.ROOT, "\\u%04x", (int) ch));
                    } else {
                        out.append(ch);
                    }
            }
        }
        out.append('"');
    }

    private static final class Parser {
        private final String input;
        private int index = 0;

        Parser(String input) {
            this.input = input == null ? "" : input;
        }

        Object parseValue() throws IOException {
            skipWhitespace();
            if (isAtEnd()) {
                throw new IOException("Unexpected end of JSON.");
            }

            char ch = input.charAt(index);
            if (ch == '{') return parseObject();
            if (ch == '[') return parseArray();
            if (ch == '"') return parseString();
            if (ch == 't' || ch == 'f') return parseBoolean();
            if (ch == 'n') return parseNull();
            if (ch == '-' || (ch >= '0' && ch <= '9')) return parseNumber();
            throw new IOException("Unexpected JSON token at position " + index + ".");
        }

        Map<String, Object> parseObject() throws IOException {
            LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
            expect('{');
            skipWhitespace();
            if (peek('}')) {
                index++;
                return out;
            }
            while (true) {
                skipWhitespace();
                String key = parseString();
                skipWhitespace();
                expect(':');
                Object value = parseValue();
                out.put(key, value);
                skipWhitespace();
                if (peek('}')) {
                    index++;
                    return out;
                }
                expect(',');
            }
        }

        List<Object> parseArray() throws IOException {
            List<Object> out = new ArrayList<Object>();
            expect('[');
            skipWhitespace();
            if (peek(']')) {
                index++;
                return out;
            }
            while (true) {
                out.add(parseValue());
                skipWhitespace();
                if (peek(']')) {
                    index++;
                    return out;
                }
                expect(',');
            }
        }

        String parseString() throws IOException {
            expect('"');
            StringBuilder out = new StringBuilder();
            while (!isAtEnd()) {
                char ch = input.charAt(index++);
                if (ch == '"') {
                    return out.toString();
                }
                if (ch != '\\') {
                    out.append(ch);
                    continue;
                }
                if (isAtEnd()) {
                    throw new IOException("Invalid escape at end of JSON string.");
                }
                char esc = input.charAt(index++);
                switch (esc) {
                    case '"':
                    case '\\':
                    case '/':
                        out.append(esc);
                        break;
                    case 'b':
                        out.append('\b');
                        break;
                    case 'f':
                        out.append('\f');
                        break;
                    case 'n':
                        out.append('\n');
                        break;
                    case 'r':
                        out.append('\r');
                        break;
                    case 't':
                        out.append('\t');
                        break;
                    case 'u':
                        out.append(parseUnicodeEscape());
                        break;
                    default:
                        throw new IOException("Invalid JSON escape sequence: \\" + esc);
                }
            }
            throw new IOException("Unterminated JSON string.");
        }

        Boolean parseBoolean() throws IOException {
            if (match("true")) return Boolean.TRUE;
            if (match("false")) return Boolean.FALSE;
            throw new IOException("Invalid JSON boolean at position " + index + ".");
        }

        Object parseNull() throws IOException {
            if (!match("null")) {
                throw new IOException("Invalid JSON null at position " + index + ".");
            }
            return null;
        }

        Number parseNumber() throws IOException {
            int start = index;
            if (peek('-')) index++;
            while (!isAtEnd() && Character.isDigit(input.charAt(index))) index++;
            boolean floatingPoint = false;
            if (!isAtEnd() && input.charAt(index) == '.') {
                floatingPoint = true;
                index++;
                while (!isAtEnd() && Character.isDigit(input.charAt(index))) index++;
            }
            if (!isAtEnd() && (input.charAt(index) == 'e' || input.charAt(index) == 'E')) {
                floatingPoint = true;
                index++;
                if (!isAtEnd() && (input.charAt(index) == '+' || input.charAt(index) == '-')) index++;
                while (!isAtEnd() && Character.isDigit(input.charAt(index))) index++;
            }

            String token = input.substring(start, index);
            try {
                return floatingPoint ? Double.valueOf(token) : Long.valueOf(token);
            } catch (NumberFormatException e) {
                throw new IOException("Invalid JSON number: " + token, e);
            }
        }

        char parseUnicodeEscape() throws IOException {
            if (index + 4 > input.length()) {
                throw new IOException("Incomplete unicode escape in JSON string.");
            }
            String hex = input.substring(index, index + 4);
            index += 4;
            try {
                return (char) Integer.parseInt(hex, 16);
            } catch (NumberFormatException e) {
                throw new IOException("Invalid unicode escape: \\u" + hex, e);
            }
        }

        void skipWhitespace() {
            while (!isAtEnd()) {
                char ch = input.charAt(index);
                if (ch == ' ' || ch == '\n' || ch == '\r' || ch == '\t') {
                    index++;
                } else {
                    break;
                }
            }
        }

        void expect(char expected) throws IOException {
            skipWhitespace();
            if (isAtEnd() || input.charAt(index) != expected) {
                throw new IOException("Expected '" + expected + "' at position " + index + ".");
            }
            index++;
        }

        boolean match(String token) {
            if (input.regionMatches(index, token, 0, token.length())) {
                index += token.length();
                return true;
            }
            return false;
        }

        boolean peek(char expected) {
            return !isAtEnd() && input.charAt(index) == expected;
        }

        boolean isAtEnd() {
            return index >= input.length();
        }
    }
}
