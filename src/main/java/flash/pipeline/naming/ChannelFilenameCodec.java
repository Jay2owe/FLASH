package flash.pipeline.naming;

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
 * characters that are unsafe on Windows: {@code \ / : * ? " < > |} plus
 * the escape character {@code %} itself. Names that are already safe pass
 * through unchanged. {@link #toRaw(String)} reverses the encoding.</p>
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
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (UNSAFE_CHARS.indexOf(c) >= 0) {
                sb.append('%');
                sb.append(String.format(Locale.US, "%02X", (int) c));
            } else {
                sb.append(c);
            }
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
            if (c == '%' && i + 2 < safe.length()) {
                String hex = safe.substring(i + 1, i + 3);
                try {
                    int code = Integer.parseInt(hex, 16);
                    sb.append((char) code);
                    i += 2;
                } catch (NumberFormatException e) {
                    sb.append(c);
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
        for (int i = 0; i < raw.length(); i++) {
            if (UNSAFE_CHARS.indexOf(raw.charAt(i)) >= 0) return false;
        }
        if (raw.endsWith(".") || raw.endsWith(" ")) return false;
        if (RESERVED_NAME.matcher(raw).matches()) return false;
        return true;
    }
}
