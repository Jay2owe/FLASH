package flash.pipeline.ui.variations;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

public final class MacroToken {

    public static final String SOURCE_NONE = "NONE";
    public static final String SOURCE_CURRENT_CHANNEL = "CURRENT_CHANNEL";
    public static final String SOURCE_BUNDLED_PRESET = "BUNDLED_PRESET";
    public static final String SOURCE_SAVED_PRESET = "SAVED_PRESET";
    public static final String SOURCE_PASTED = "PASTED";
    public static final String SOURCE_RECORDED = "RECORDED";
    public static final String NONE_VALUE = "macro:none";

    private final String value;
    private final String sourceKind;
    private final String sourceName;
    private final String normalizedScriptHash;

    private MacroToken(String value,
                       String sourceKind,
                       String sourceName,
                       String normalizedScriptHash) {
        String safeValue = value == null ? "" : value.trim();
        if (safeValue.isEmpty()) {
            throw new IllegalArgumentException("macro token must not be empty");
        }
        this.value = safeValue;
        this.sourceKind = normalizeSourceKind(sourceKind);
        this.sourceName = sourceName == null ? "" : sourceName.trim();
        this.normalizedScriptHash = normalizedScriptHash == null
                ? ""
                : normalizedScriptHash.trim().toLowerCase(Locale.ROOT);
    }

    public static MacroToken none() {
        return new MacroToken(NONE_VALUE, SOURCE_NONE, "", sha256(""));
    }

    public static MacroToken forScript(String sourceKind,
                                       String sourceName,
                                       String scriptText) {
        String normalized = normalizeScriptText(scriptText);
        if (normalized.isEmpty()) {
            return none();
        }
        String hash = sha256(normalized);
        String shortHash = hash.substring(0, 16);
        String normalizedKind = normalizeSourceKind(sourceKind);
        String token;
        if (SOURCE_CURRENT_CHANNEL.equals(normalizedKind)) {
            token = "macro:current:" + shortHash;
        } else if (SOURCE_BUNDLED_PRESET.equals(normalizedKind)) {
            token = "macro:bundled:" + slug(sourceName) + ":" + shortHash;
        } else if (SOURCE_SAVED_PRESET.equals(normalizedKind)) {
            token = "macro:saved:" + slug(sourceName) + ":" + shortHash;
        } else {
            token = "macro:adhoc:" + shortHash;
        }
        return new MacroToken(token, normalizedKind, sourceName, hash);
    }

    public static MacroToken parse(String token) {
        String value = token == null ? "" : token.trim();
        if (value.isEmpty() || NONE_VALUE.equals(value)) {
            return none();
        }
        if (!isMacroToken(value)) {
            throw new IllegalArgumentException("invalid macro token: " + value);
        }
        return new MacroToken(value, sourceKindForToken(value), "", hashPart(value));
    }

    public static String tokenString(Object value) {
        if (value instanceof MacroToken) {
            return ((MacroToken) value).value();
        }
        if (value instanceof MacroVariation) {
            return ((MacroVariation) value).token();
        }
        Object normalized = ParameterValueList.normalizeValue(value);
        String text = String.valueOf(normalized);
        String trimmed = text.trim();
        if (trimmed.isEmpty()) {
            return NONE_VALUE;
        }
        if (isMacroToken(trimmed)) {
            return trimmed;
        }
        return forScript(SOURCE_PASTED, "", text).value();
    }

    public static boolean isMacroToken(String value) {
        String text = value == null ? "" : value.trim();
        if (NONE_VALUE.equals(text)) {
            return true;
        }
        return text.startsWith("macro:current:")
                || text.startsWith("macro:bundled:")
                || text.startsWith("macro:saved:")
                || text.startsWith("macro:adhoc:");
    }

    public static String normalizeScriptText(String scriptText) {
        String raw = scriptText == null ? "" : scriptText;
        raw = raw.replace("\r\n", "\n").replace('\r', '\n');
        String[] lines = raw.split("\n", -1);
        String[] trimmed = new String[lines.length];
        for (int i = 0; i < lines.length; i++) {
            trimmed[i] = trimTrailingWhitespace(lines[i]);
        }
        int last = trimmed.length - 1;
        while (last >= 0 && trimmed[last].isEmpty()) {
            last--;
        }
        if (last < 0) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (int i = 0; i <= last; i++) {
            if (i > 0) {
                out.append('\n');
            }
            out.append(trimmed[i]);
        }
        return out.toString();
    }

    public String value() {
        return value;
    }

    public String getValue() {
        return value;
    }

    public String sourceKind() {
        return sourceKind;
    }

    public String getSourceKind() {
        return sourceKind;
    }

    public String sourceName() {
        return sourceName;
    }

    public String getSourceName() {
        return sourceName;
    }

    public String normalizedScriptHash() {
        return normalizedScriptHash;
    }

    public String getNormalizedScriptHash() {
        return normalizedScriptHash;
    }

    private static String normalizeSourceKind(String sourceKind) {
        String kind = sourceKind == null
                ? SOURCE_PASTED
                : sourceKind.trim().toUpperCase(Locale.ROOT);
        if (SOURCE_NONE.equals(kind)
                || SOURCE_CURRENT_CHANNEL.equals(kind)
                || SOURCE_BUNDLED_PRESET.equals(kind)
                || SOURCE_SAVED_PRESET.equals(kind)
                || SOURCE_PASTED.equals(kind)
                || SOURCE_RECORDED.equals(kind)) {
            return kind;
        }
        return SOURCE_PASTED;
    }

    private static String sourceKindForToken(String token) {
        if (NONE_VALUE.equals(token)) {
            return SOURCE_NONE;
        }
        if (token.startsWith("macro:current:")) {
            return SOURCE_CURRENT_CHANNEL;
        }
        if (token.startsWith("macro:bundled:")) {
            return SOURCE_BUNDLED_PRESET;
        }
        if (token.startsWith("macro:saved:")) {
            return SOURCE_SAVED_PRESET;
        }
        return SOURCE_PASTED;
    }

    private static String hashPart(String token) {
        int index = token == null ? -1 : token.lastIndexOf(':');
        return index < 0 ? "" : token.substring(index + 1).trim().toLowerCase(Locale.ROOT);
    }

    private static String trimTrailingWhitespace(String line) {
        String safeLine = line == null ? "" : line;
        int end = safeLine.length();
        while (end > 0 && Character.isWhitespace(safeLine.charAt(end - 1))) {
            end--;
        }
        return end == safeLine.length() ? safeLine : safeLine.substring(0, end);
    }

    private static String slug(String value) {
        String raw = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        StringBuilder out = new StringBuilder();
        boolean dash = false;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            boolean alphaNumeric = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9');
            if (alphaNumeric) {
                out.append(c);
                dash = false;
            } else if (!dash && out.length() > 0) {
                out.append('-');
                dash = true;
            }
        }
        while (out.length() > 0 && out.charAt(out.length() - 1) == '-') {
            out.deleteCharAt(out.length() - 1);
        }
        if (out.length() == 0) {
            return "unnamed";
        }
        if (out.length() > 48) {
            return out.substring(0, 48);
        }
        return out.toString();
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest((value == null ? "" : value)
                    .getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(bytes.length * 2);
            for (int i = 0; i < bytes.length; i++) {
                out.append(String.format(Locale.ROOT, "%02x",
                        Integer.valueOf(bytes[i] & 0xff)));
            }
            return out.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof MacroToken)) return false;
        MacroToken other = (MacroToken) obj;
        return value.equals(other.value);
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }

    @Override
    public String toString() {
        return value;
    }
}
