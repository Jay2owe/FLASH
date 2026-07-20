package flash.pipeline.naming;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Reversible codec for channel-derived filename segments.
 *
 * <p>Raw channel names (user-supplied marker labels such as "DAPI", "Iba1",
 * "GFP/YFP") are the source of truth for UI, logs, CSV contents, and
 * exported summaries. Only when a channel name crosses the filesystem
 * boundary should it be encoded via {@link #toSafe(String)}.</p>
 *
 * <p>Encoding uses percent-encoding (like URLs) for the minimal set of
 * characters that are unsafe on Windows: {@code \ / : * ? " < > |}, the
 * escape character {@code %}, and Unicode control code points. Percent escapes
 * contain UTF-8 bytes, so every valid Unicode code point round-trips. Names that
 * are already safe pass through unchanged. {@link #toRaw(String)} reverses the
 * encoding.</p>
 */
public final class ChannelFilenameCodec {

    /** Windows-forbidden filename characters plus the escape char. */
    private static final String UNSAFE_CHARS = "\\/:*?\"<>|%";

    /** Reserved device names on Windows (case-insensitive, with or without extension). */
    private static final Pattern RESERVED_NAME = Pattern.compile(
            "(?i)^(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(\\..*)?$");

    private ChannelFilenameCodec() { }

    /**
     * Encode a raw channel name into a filesystem-safe filename segment.
     * Safe names are returned unchanged. Unsafe characters are percent-encoded.
     *
     * @param raw the original channel/marker name (never null)
     * @return a Windows-safe filename segment that can be decoded back via {@link #toRaw(String)}
     */
    public static String toSafe(String raw) {
        if (raw == null || raw.isEmpty()) return raw;

        StringBuilder sb = new StringBuilder(raw.length() + 8);
        for (int i = 0; i < raw.length();) {
            char c = raw.charAt(i);
            if (Character.isSurrogate(c)
                    && (Character.isHighSurrogate(c)
                    && (i + 1 >= raw.length() || !Character.isLowSurrogate(raw.charAt(i + 1)))
                    || Character.isLowSurrogate(c))) {
                // Ill-formed UTF-16 cannot be represented losslessly as UTF-8. Preserve the
                // code unit explicitly and keep it out of a Windows filename.
                sb.append("%u").append(String.format(Locale.US, "%04X", (int) c));
                i++;
                continue;
            }
            int cp = raw.codePointAt(i);
            if (isUnsafeCodePoint(cp)) {
                appendUtf8Escapes(sb, cp);
            } else {
                sb.appendCodePoint(cp);
            }
            i += Character.charCount(cp);
        }

        // Trailing dots and spaces are stripped by Windows — encode them
        int len = sb.length();
        while (len > 0) {
            char last = sb.charAt(len - 1);
            if (last == '.' || last == ' ') {
                sb.replace(len - 1, len, String.format(Locale.US, "%%%02X", (int) last));
                len = sb.length();
            } else {
                break;
            }
        }

        String result = sb.toString();

        // Reserved device names: prefix with %5F to disambiguate
        if (RESERVED_NAME.matcher(result).matches()) {
            result = "%5F" + result;
        }

        return result;
    }

    /**
     * Decode a safe filename segment back to the original raw channel name.
     *
     * @param safe the encoded segment produced by {@link #toSafe(String)}
     * @return the original raw channel name
     */
    public static String toRaw(String safe) {
        if (safe == null || safe.isEmpty()) return safe;

        // Undo reserved-name prefix
        if (safe.startsWith("%5F") && RESERVED_NAME.matcher(safe.substring(3)).matches()) {
            safe = safe.substring(3);
        }

        StringBuilder sb = new StringBuilder(safe.length());
        for (int i = 0; i < safe.length(); i++) {
            char c = safe.charAt(i);
            if (c == '%' && i + 5 < safe.length() && safe.charAt(i + 1) == 'u') {
                int codeUnit = parseHex(safe, i + 2, 4);
                if (codeUnit >= 0) {
                    sb.append((char) codeUnit);
                    i += 5;
                    continue;
                }
            }
            if (c == '%' && i + 2 < safe.length()) {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                int end = i;
                while (end + 2 < safe.length() && safe.charAt(end) == '%') {
                    // %uXXXX is a distinct escape and must be handled by the outer loop.
                    if (end + 1 < safe.length() && safe.charAt(end + 1) == 'u') break;
                    int value = parseHex(safe, end + 1, 2);
                    if (value < 0) break;
                    bytes.write(value);
                    end += 3;
                }
                if (end == i) {
                    // Not a valid escape. Consume the literal '%' exactly once.
                    sb.append(c);
                    continue;
                }
                String decoded = decodeUtf8(bytes.toByteArray());
                if (decoded != null && !decoded.isEmpty()) {
                    sb.append(decoded);
                    i = end - 1;
                } else {
                    sb.append(safe, i, end);
                    i = end - 1;
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * Returns true if the raw channel name is already safe for use as a
     * Windows filename segment (no encoding needed).
     */
    public static boolean isSafe(String raw) {
        if (raw == null || raw.isEmpty()) return true;
        for (int i = 0; i < raw.length();) {
            char c = raw.charAt(i);
            if (Character.isSurrogate(c)
                    && (Character.isHighSurrogate(c)
                    && (i + 1 >= raw.length() || !Character.isLowSurrogate(raw.charAt(i + 1)))
                    || Character.isLowSurrogate(c))) return false;
            int cp = raw.codePointAt(i);
            if (isUnsafeCodePoint(cp)) return false;
            i += Character.charCount(cp);
        }
        if (raw.endsWith(".") || raw.endsWith(" ")) return false;
        if (RESERVED_NAME.matcher(raw).matches()) return false;
        return true;
    }

    /**
     * Canonical comparison key for a channel-derived filename on Windows.
     * The raw display name remains untouched; only its filesystem encoding is
     * folded case-insensitively for collision detection.
     */
    public static String windowsCollisionKey(String raw) {
        if (raw == null) return null;
        return toSafe(raw).toLowerCase(Locale.ROOT);
    }

    private static boolean isUnsafeCodePoint(int cp) {
        return cp <= Character.MAX_VALUE && UNSAFE_CHARS.indexOf((char) cp) >= 0
                || Character.getType(cp) == Character.CONTROL;
    }

    private static void appendUtf8Escapes(StringBuilder out, int cp) {
        byte[] encoded = new String(Character.toChars(cp)).getBytes(StandardCharsets.UTF_8);
        for (byte b : encoded) {
            out.append('%').append(String.format(Locale.US, "%02X", b & 0xff));
        }
    }

    private static int parseHex(String value, int offset, int length) {
        if (offset < 0 || offset + length > value.length()) return -1;
        int parsed = 0;
        for (int i = 0; i < length; i++) {
            int digit = Character.digit(value.charAt(offset + i), 16);
            if (digit < 0) return -1;
            parsed = parsed * 16 + digit;
        }
        return parsed;
    }

    private static String decodeUtf8(byte[] bytes) {
        if (bytes == null || bytes.length == 0) return null;
        try {
            CharBuffer chars = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes));
            return chars.toString();
        } catch (CharacterCodingException e) {
            return null;
        }
    }
}
