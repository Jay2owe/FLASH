package flash.pipeline.intelligence;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
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

    /** Default document limit: 8 MiB of UTF-8 input. */
    public static final long DEFAULT_MAX_UTF8_BYTES = 8L * 1024L * 1024L;
    /** Default document limit: 8 million UTF-16 code units. */
    public static final int DEFAULT_MAX_INPUT_CHARACTERS = 8 * 1024 * 1024;
    /** Default container nesting limit. */
    public static final int DEFAULT_MAX_NESTING_DEPTH = 128;
    /** Default aggregate limit, including values and object keys. */
    public static final long DEFAULT_MAX_TOTAL_NODES = 250000L;
    /** Default decoded length of any one string, in UTF-16 code units. */
    public static final int DEFAULT_MAX_STRING_CHARACTERS = 1024 * 1024;
    /** Default member/element limit for any one object or array. */
    public static final int DEFAULT_MAX_COLLECTION_SIZE = 100000;
    /** Default character limit for any one numeric token. */
    public static final int DEFAULT_MAX_NUMBER_CHARACTERS = 128;

    public static final Limits DEFAULT_LIMITS = new Limits(
            DEFAULT_MAX_UTF8_BYTES,
            DEFAULT_MAX_INPUT_CHARACTERS,
            DEFAULT_MAX_NESTING_DEPTH,
            DEFAULT_MAX_TOTAL_NODES,
            DEFAULT_MAX_STRING_CHARACTERS,
            DEFAULT_MAX_COLLECTION_SIZE,
            DEFAULT_MAX_NUMBER_CHARACTERS);

    /** Resource dimension reported by {@link LimitExceededException}. */
    public enum LimitDimension {
        INPUT_UTF8_BYTES,
        INPUT_CHARACTERS,
        NESTING_DEPTH,
        TOTAL_NODES,
        STRING_CHARACTERS,
        COLLECTION_ENTRIES,
        NUMBER_CHARACTERS
    }

    /** Immutable parser resource limits. Zero is allowed for every dimension. */
    public static final class Limits {
        private final long maxUtf8Bytes;
        private final int maxInputCharacters;
        private final int maxNestingDepth;
        private final long maxTotalNodes;
        private final int maxStringCharacters;
        private final int maxCollectionSize;
        private final int maxNumberCharacters;

        public Limits(long maxUtf8Bytes,
                      int maxInputCharacters,
                      int maxNestingDepth,
                      long maxTotalNodes,
                      int maxStringCharacters,
                      int maxCollectionSize,
                      int maxNumberCharacters) {
            requireNonNegative("maxUtf8Bytes", maxUtf8Bytes);
            requireNonNegative("maxInputCharacters", maxInputCharacters);
            requireNonNegative("maxNestingDepth", maxNestingDepth);
            requireNonNegative("maxTotalNodes", maxTotalNodes);
            requireNonNegative("maxStringCharacters", maxStringCharacters);
            requireNonNegative("maxCollectionSize", maxCollectionSize);
            requireNonNegative("maxNumberCharacters", maxNumberCharacters);
            this.maxUtf8Bytes = maxUtf8Bytes;
            this.maxInputCharacters = maxInputCharacters;
            this.maxNestingDepth = maxNestingDepth;
            this.maxTotalNodes = maxTotalNodes;
            this.maxStringCharacters = maxStringCharacters;
            this.maxCollectionSize = maxCollectionSize;
            this.maxNumberCharacters = maxNumberCharacters;
        }

        public long getMaxUtf8Bytes() {
            return maxUtf8Bytes;
        }

        public int getMaxInputCharacters() {
            return maxInputCharacters;
        }

        public int getMaxNestingDepth() {
            return maxNestingDepth;
        }

        public long getMaxTotalNodes() {
            return maxTotalNodes;
        }

        public int getMaxStringCharacters() {
            return maxStringCharacters;
        }

        public int getMaxCollectionSize() {
            return maxCollectionSize;
        }

        public int getMaxNumberCharacters() {
            return maxNumberCharacters;
        }

        private static void requireNonNegative(String name, long value) {
            if (value < 0L) {
                throw new IllegalArgumentException(name + " must be non-negative.");
            }
        }
    }

    /** Checked, typed diagnostic for a parser resource-limit failure. */
    public static final class LimitExceededException extends IOException {
        private static final long serialVersionUID = 1L;

        private final String source;
        private final LimitDimension dimension;
        private final long limit;
        private final long measured;

        LimitExceededException(String source,
                               LimitDimension dimension,
                               long limit,
                               long measured) {
            super("JSON input '" + source + "' exceeds " + dimension
                    + " limit " + limit + " (measured " + measured + ").");
            this.source = source;
            this.dimension = dimension;
            this.limit = limit;
            this.measured = measured;
        }

        public String getSource() {
            return source;
        }

        public LimitDimension getDimension() {
            return dimension;
        }

        public long getLimit() {
            return limit;
        }

        public long getMeasured() {
            return measured;
        }
    }

    private MiniJson() {}

    public static String write(Object value) {
        StringBuilder out = new StringBuilder();
        writeValue(out, value);
        return out.toString();
    }

    public static Object parse(String json) throws IOException {
        return parse(json, DEFAULT_LIMITS, "JSON string");
    }

    public static Object parse(String json, Limits limits) throws IOException {
        return parse(json, limits, "JSON string");
    }

    /** Parses a Java string and includes {@code source} in any limit diagnostic. */
    public static Object parse(String json, Limits limits, String source) throws IOException {
        Limits checkedLimits = requireLimits(limits);
        String input = json == null ? "" : json;
        String checkedSource = normalizeSource(source, "JSON string");
        validateStringInput(input, checkedLimits, checkedSource);
        return parseChecked(input, checkedLimits, checkedSource);
    }

    /**
     * Parses UTF-8 after checking byte and decoded-character limits, before a
     * Java string or object tree is created.
     */
    public static Object parseUtf8(byte[] utf8) throws IOException {
        return parseUtf8(utf8, DEFAULT_LIMITS, "UTF-8 JSON");
    }

    public static Object parseUtf8(byte[] utf8, Limits limits) throws IOException {
        return parseUtf8(utf8, limits, "UTF-8 JSON");
    }

    /** Parses strictly valid UTF-8 and includes {@code source} in diagnostics. */
    public static Object parseUtf8(byte[] utf8, Limits limits, String source) throws IOException {
        Limits checkedLimits = requireLimits(limits);
        byte[] input = utf8 == null ? new byte[0] : utf8;
        String checkedSource = normalizeSource(source, "UTF-8 JSON");
        if ((long) input.length > checkedLimits.maxUtf8Bytes) {
            throw exceeded(checkedSource, LimitDimension.INPUT_UTF8_BYTES,
                    checkedLimits.maxUtf8Bytes, input.length);
        }
        validateUtf8(input, checkedLimits, checkedSource);
        return parseChecked(new String(input, StandardCharsets.UTF_8),
                checkedLimits, checkedSource);
    }

    /**
     * Reads at most one byte beyond the configured limit, then delegates to
     * the strict UTF-8 parser. The caller retains ownership of the stream.
     */
    public static Object parseUtf8(InputStream input) throws IOException {
        return parseUtf8(input, DEFAULT_LIMITS, "UTF-8 JSON stream");
    }

    public static Object parseUtf8(InputStream input, Limits limits) throws IOException {
        return parseUtf8(input, limits, "UTF-8 JSON stream");
    }

    public static Object parseUtf8(InputStream input, Limits limits, String source)
            throws IOException {
        if (input == null) {
            throw new IllegalArgumentException("JSON input stream must not be null.");
        }
        Limits checkedLimits = requireLimits(limits);
        String checkedSource = normalizeSource(source, "UTF-8 JSON stream");
        int initialCapacity = (int) Math.min(8192L, checkedLimits.maxUtf8Bytes);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(initialCapacity);
        byte[] buffer = new byte[8192];
        long total = 0L;
        while (true) {
            long remaining = checkedLimits.maxUtf8Bytes - total;
            int requested = (int) Math.min((long) buffer.length,
                    saturatedAdd(remaining, 1L));
            int read = input.read(buffer, 0, requested);
            if (read < 0) {
                break;
            }
            if (read == 0) {
                int single = input.read();
                if (single < 0) {
                    break;
                }
                buffer[0] = (byte) single;
                read = 1;
            }
            long measured = saturatedAdd(total, read);
            if (measured > checkedLimits.maxUtf8Bytes) {
                throw exceeded(checkedSource, LimitDimension.INPUT_UTF8_BYTES,
                        checkedLimits.maxUtf8Bytes, measured);
            }
            bytes.write(buffer, 0, read);
            total = measured;
        }
        return parseUtf8(bytes.toByteArray(), checkedLimits, checkedSource);
    }

    private static Object parseChecked(String input, Limits limits, String source)
            throws IOException {
        Parser parser = new Parser(input, limits, source);
        Object value = parser.parseValue(0);
        parser.skipWhitespace();
        if (!parser.isAtEnd()) {
            throw new IOException("Unexpected trailing JSON content.");
        }
        return value;
    }

    private static Limits requireLimits(Limits limits) {
        if (limits == null) {
            throw new IllegalArgumentException("JSON limits must not be null.");
        }
        return limits;
    }

    private static String normalizeSource(String source, String fallback) {
        return source == null || source.trim().isEmpty() ? fallback : source;
    }

    private static void validateStringInput(String input, Limits limits, String source)
            throws IOException {
        if (input.length() > limits.maxInputCharacters) {
            throw exceeded(source, LimitDimension.INPUT_CHARACTERS,
                    limits.maxInputCharacters, input.length());
        }
        long byteCount = 0L;
        for (int i = 0; i < input.length(); i++) {
            char ch = input.charAt(i);
            int bytes;
            if (ch <= 0x7f) {
                bytes = 1;
            } else if (ch <= 0x7ff) {
                bytes = 2;
            } else if (Character.isHighSurrogate(ch)) {
                if (i + 1 >= input.length()
                        || !Character.isLowSurrogate(input.charAt(i + 1))) {
                    throw malformedSurrogate(i);
                }
                i++;
                bytes = 4;
            } else if (Character.isLowSurrogate(ch)) {
                throw malformedSurrogate(i);
            } else {
                bytes = 3;
            }
            byteCount = saturatedAdd(byteCount, bytes);
            if (byteCount > limits.maxUtf8Bytes) {
                throw exceeded(source, LimitDimension.INPUT_UTF8_BYTES,
                        limits.maxUtf8Bytes, byteCount);
            }
        }
    }

    /** Validates strict UTF-8 and counts UTF-16 code units without decoding. */
    private static void validateUtf8(byte[] input, Limits limits, String source)
            throws IOException {
        long characters = 0L;
        int index = 0;
        while (index < input.length) {
            int first = input[index] & 0xff;
            int byteCount;
            int characterCount;
            if (first <= 0x7f) {
                byteCount = 1;
                characterCount = 1;
            } else if (first >= 0xc2 && first <= 0xdf) {
                requireContinuation(input, index, 1);
                byteCount = 2;
                characterCount = 1;
            } else if (first >= 0xe0 && first <= 0xef) {
                requireContinuation(input, index, 2);
                int second = input[index + 1] & 0xff;
                if ((first == 0xe0 && second < 0xa0)
                        || (first == 0xed && second > 0x9f)) {
                    throw malformedUtf8(index);
                }
                byteCount = 3;
                characterCount = 1;
            } else if (first >= 0xf0 && first <= 0xf4) {
                requireContinuation(input, index, 3);
                int second = input[index + 1] & 0xff;
                if ((first == 0xf0 && second < 0x90)
                        || (first == 0xf4 && second > 0x8f)) {
                    throw malformedUtf8(index);
                }
                byteCount = 4;
                characterCount = 2;
            } else {
                throw malformedUtf8(index);
            }
            characters = saturatedAdd(characters, characterCount);
            if (characters > limits.maxInputCharacters) {
                throw exceeded(source, LimitDimension.INPUT_CHARACTERS,
                        limits.maxInputCharacters, characters);
            }
            index += byteCount;
        }
    }

    private static void requireContinuation(byte[] input, int start, int count)
            throws IOException {
        if (start > input.length - count - 1) {
            throw malformedUtf8(start);
        }
        for (int offset = 1; offset <= count; offset++) {
            int value = input[start + offset] & 0xff;
            if (value < 0x80 || value > 0xbf) {
                throw malformedUtf8(start + offset);
            }
        }
    }

    private static IOException malformedUtf8(int index) {
        return new IOException("Malformed UTF-8 JSON input at byte " + index + ".");
    }

    private static IOException malformedSurrogate(int index) {
        return new IOException("Lone surrogate in JSON input at position " + index + ".");
    }

    private static LimitExceededException exceeded(String source,
                                                    LimitDimension dimension,
                                                    long limit,
                                                    long measured) {
        return new LimitExceededException(source, dimension, limit, measured);
    }

    private static long saturatedAdd(long value, long increment) {
        return value > Long.MAX_VALUE - increment ? Long.MAX_VALUE : value + increment;
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
        private final Limits limits;
        private final String source;
        private int index = 0;
        private long nodeCount = 0L;

        Parser(String input, Limits limits, String source) {
            this.input = input;
            this.limits = limits;
            this.source = source;
        }

        Object parseValue(int parentDepth) throws IOException {
            skipWhitespace();
            if (isAtEnd()) {
                throw new IOException("Unexpected end of JSON.");
            }

            char ch = input.charAt(index);
            if (!isValueStart(ch)) {
                throw new IOException("Unexpected JSON token at position " + index + ".");
            }
            if (ch == '{' || ch == '[') {
                int depth = enterDepth(parentDepth);
                chargeNode();
                return ch == '{' ? parseObject(depth) : parseArray(depth);
            }
            chargeNode();
            if (ch == '"') return parseString();
            if (ch == 't' || ch == 'f') return parseBoolean();
            if (ch == 'n') return parseNull();
            return parseNumber();
        }

        Map<String, Object> parseObject(int depth) throws IOException {
            expect('{');
            LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
            skipWhitespace();
            if (peek('}')) {
                index++;
                return out;
            }
            int members = 0;
            while (true) {
                skipWhitespace();
                if (!peek('"')) {
                    throw new IOException("Expected JSON object key at position " + index + ".");
                }
                members = chargeCollectionEntry(members);
                chargeNode();
                String key = parseString();
                skipWhitespace();
                expect(':');
                Object value = parseValue(depth);
                out.put(key, value);
                skipWhitespace();
                if (peek('}')) {
                    index++;
                    return out;
                }
                expect(',');
                skipWhitespace();
                if (peek('}')) {
                    throw new IOException("Trailing comma in JSON object at position " + index + ".");
                }
            }
        }

        List<Object> parseArray(int depth) throws IOException {
            expect('[');
            List<Object> out = new ArrayList<Object>();
            skipWhitespace();
            if (peek(']')) {
                index++;
                return out;
            }
            int elements = 0;
            while (true) {
                skipWhitespace();
                if (peek(']')) {
                    throw new IOException("Trailing comma in JSON array at position " + index + ".");
                }
                if (isAtEnd() || !isValueStart(input.charAt(index))) {
                    throw new IOException("Unexpected JSON token at position " + index + ".");
                }
                elements = chargeCollectionEntry(elements);
                out.add(parseValue(depth));
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
            int decodedLength = 0;
            while (!isAtEnd()) {
                int characterPosition = index;
                char ch = input.charAt(index++);
                if (ch == '"') {
                    return out.toString();
                }
                if (ch != '\\') {
                    if (ch < 0x20) {
                        throw new IOException("Unescaped control in JSON string at position "
                                + characterPosition + ".");
                    }
                    if (Character.isHighSurrogate(ch)) {
                        if (isAtEnd() || !Character.isLowSurrogate(input.charAt(index))) {
                            throw malformedSurrogate(characterPosition);
                        }
                        decodedLength = chargeStringCharacters(decodedLength, 2);
                        out.append(ch).append(input.charAt(index++));
                    } else if (Character.isLowSurrogate(ch)) {
                        throw malformedSurrogate(characterPosition);
                    } else {
                        decodedLength = chargeStringCharacters(decodedLength, 1);
                        out.append(ch);
                    }
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
                        decodedLength = chargeStringCharacters(decodedLength, 1);
                        out.append(esc);
                        break;
                    case 'b':
                        decodedLength = chargeStringCharacters(decodedLength, 1);
                        out.append('\b');
                        break;
                    case 'f':
                        decodedLength = chargeStringCharacters(decodedLength, 1);
                        out.append('\f');
                        break;
                    case 'n':
                        decodedLength = chargeStringCharacters(decodedLength, 1);
                        out.append('\n');
                        break;
                    case 'r':
                        decodedLength = chargeStringCharacters(decodedLength, 1);
                        out.append('\r');
                        break;
                    case 't':
                        decodedLength = chargeStringCharacters(decodedLength, 1);
                        out.append('\t');
                        break;
                    case 'u':
                        char unicode = parseUnicodeEscape();
                        if (Character.isHighSurrogate(unicode)) {
                            if (index > input.length() - 6
                                    || input.charAt(index) != '\\'
                                    || input.charAt(index + 1) != 'u') {
                                throw malformedSurrogate(characterPosition);
                            }
                            index += 2;
                            char low = parseUnicodeEscape();
                            if (!Character.isLowSurrogate(low)) {
                                throw malformedSurrogate(characterPosition);
                            }
                            decodedLength = chargeStringCharacters(decodedLength, 2);
                            out.append(unicode).append(low);
                        } else if (Character.isLowSurrogate(unicode)) {
                            throw malformedSurrogate(characterPosition);
                        } else {
                            decodedLength = chargeStringCharacters(decodedLength, 1);
                            out.append(unicode);
                        }
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
            if (peek('-')) {
                consumeNumberCharacter(start);
            }
            if (isAtEnd()) {
                throw invalidNumber(start);
            }
            if (peek('0')) {
                consumeNumberCharacter(start);
                if (!isAtEnd() && isAsciiDigit(input.charAt(index))) {
                    throw invalidNumber(start);
                }
            } else if (input.charAt(index) >= '1' && input.charAt(index) <= '9') {
                do {
                    consumeNumberCharacter(start);
                } while (!isAtEnd() && isAsciiDigit(input.charAt(index)));
            } else {
                throw invalidNumber(start);
            }

            boolean floatingPoint = false;
            if (!isAtEnd() && input.charAt(index) == '.') {
                floatingPoint = true;
                consumeNumberCharacter(start);
                if (isAtEnd() || !isAsciiDigit(input.charAt(index))) {
                    throw invalidNumber(start);
                }
                do {
                    consumeNumberCharacter(start);
                } while (!isAtEnd() && isAsciiDigit(input.charAt(index)));
            }
            if (!isAtEnd() && (input.charAt(index) == 'e' || input.charAt(index) == 'E')) {
                floatingPoint = true;
                consumeNumberCharacter(start);
                if (!isAtEnd() && (input.charAt(index) == '+' || input.charAt(index) == '-')) {
                    consumeNumberCharacter(start);
                }
                if (isAtEnd() || !isAsciiDigit(input.charAt(index))) {
                    throw invalidNumber(start);
                }
                do {
                    consumeNumberCharacter(start);
                } while (!isAtEnd() && isAsciiDigit(input.charAt(index)));
            }

            String token = input.substring(start, index);
            try {
                if (floatingPoint) {
                    Double value = Double.valueOf(token);
                    return Double.isFinite(value.doubleValue())
                            ? value : new BigDecimal(token);
                }
                try {
                    return Long.valueOf(token);
                } catch (NumberFormatException outsideLongRange) {
                    return new BigInteger(token);
                }
            } catch (NumberFormatException e) {
                throw new IOException("Invalid JSON number at position " + start + ".", e);
            }
        }

        char parseUnicodeEscape() throws IOException {
            if (index > input.length() - 4) {
                throw new IOException("Incomplete unicode escape in JSON string.");
            }
            int value = 0;
            for (int offset = 0; offset < 4; offset++) {
                int digit = hexDigit(input.charAt(index + offset));
                if (digit < 0) {
                    throw new IOException("Invalid unicode escape at position " + index + ".");
                }
                value = (value << 4) | digit;
            }
            index += 4;
            return (char) value;
        }

        private int enterDepth(int parentDepth) throws LimitExceededException {
            long measured = (long) parentDepth + 1L;
            if (measured > limits.maxNestingDepth) {
                throw exceeded(source, LimitDimension.NESTING_DEPTH,
                        limits.maxNestingDepth, measured);
            }
            return (int) measured;
        }

        private void chargeNode() throws LimitExceededException {
            long measured = saturatedAdd(nodeCount, 1L);
            if (measured > limits.maxTotalNodes) {
                throw exceeded(source, LimitDimension.TOTAL_NODES,
                        limits.maxTotalNodes, measured);
            }
            nodeCount = measured;
        }

        private int chargeCollectionEntry(int current) throws LimitExceededException {
            long measured = (long) current + 1L;
            if (measured > limits.maxCollectionSize) {
                throw exceeded(source, LimitDimension.COLLECTION_ENTRIES,
                        limits.maxCollectionSize, measured);
            }
            return (int) measured;
        }

        private int chargeStringCharacters(int current, int increment)
                throws LimitExceededException {
            long measured = (long) current + increment;
            if (measured > limits.maxStringCharacters) {
                throw exceeded(source, LimitDimension.STRING_CHARACTERS,
                        limits.maxStringCharacters, measured);
            }
            return (int) measured;
        }

        private void consumeNumberCharacter(int start) throws LimitExceededException {
            index++;
            long measured = (long) index - start;
            if (measured > limits.maxNumberCharacters) {
                throw exceeded(source, LimitDimension.NUMBER_CHARACTERS,
                        limits.maxNumberCharacters, measured);
            }
        }

        private IOException invalidNumber(int start) {
            return new IOException("Invalid JSON number at position " + start + ".");
        }

        private boolean isValueStart(char ch) {
            return ch == '{' || ch == '[' || ch == '"' || ch == 't' || ch == 'f'
                    || ch == 'n' || ch == '-' || isAsciiDigit(ch);
        }

        private boolean isAsciiDigit(char ch) {
            return ch >= '0' && ch <= '9';
        }

        private int hexDigit(char ch) {
            if (ch >= '0' && ch <= '9') return ch - '0';
            if (ch >= 'a' && ch <= 'f') return ch - 'a' + 10;
            if (ch >= 'A' && ch <= 'F') return ch - 'A' + 10;
            return -1;
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
