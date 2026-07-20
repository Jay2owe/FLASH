package flash.pipeline.naming;

import java.text.Normalizer;
import java.util.Locale;

/**
 * One experimental grouping dimension in the N-condition model, e.g.
 * {@code "Genotype"} or {@code "Timepoint"}. Immutable.
 *
 * <p>Identity is the normalised {@link #id}: two axes with the same id
 * (case-insensitive, separator-insensitive) are {@link #equals equal}
 * regardless of display {@link #label} or {@link #order}. This lets the
 * single legacy {@code AnimalName,Condition} mapping be represented as one
 * axis named {@code Condition} while richer studies carry several axes.
 *
 * @see ConditionAssignments
 */
public final class ConditionAxis {

    /** Stable, normalised key. Unicode identities use the versioned {@code u1$} form. */
    public final String id;
    /** Display label, e.g. {@code "Genotype"}. */
    public final String label;
    /** Column order within the condition block of the manifest table. */
    public final int order;

    public ConditionAxis(String id, String label, int order) {
        String normId = normaliseId(id);
        if (normId.isEmpty()) {
            // Fall back to deriving the id from the display label.
            normId = normaliseId(label);
        }
        this.id = normId;
        this.label = label == null ? "" : label.trim();
        this.order = order;
    }

    /** Axis whose id is derived from {@code label}, order {@code 0}. */
    public static ConditionAxis of(String label) {
        return new ConditionAxis(null, label, 0);
    }

    /** Axis with an explicit id, display label and order. */
    public static ConditionAxis of(String id, String label, int order) {
        return new ConditionAxis(id, label, order);
    }

    /**
     * Column name used for this axis in {@code Conditions.csv}, e.g.
     * {@code Condition_Genotype}. Whitespace in the label is collapsed to a single
     * underscore (not removed) so the header is a single token that still
     * {@link #normaliseId normalises} back to this axis's {@link #id} on read
     * (e.g. {@code "Time Point"} -&gt; {@code Condition_Time_Point} -&gt; id
     * {@code time_point}); CSV quoting is handled by the writer.
     */
    public String csvColumnName() {
        String base = label.isEmpty() ? id : label;
        String token = base.replaceAll("\\s+", "_");
        // The header must normalise back to this axis's canonical id on read. When an
        // explicit id diverges from the label, fall back to the id as the token so the
        // round-trip preserves identity (readable label token otherwise).
        if (!normaliseId(token).equals(id)) {
            token = id;
        }
        return "Condition_" + token;
    }

    /**
     * Normalise an id/label to a stable key.
     *
     * <p>ASCII identifiers retain the historical lower-case/underscore form so
     * existing projects continue to resolve. Identities containing Unicode
     * letters or numbers use a versioned {@code u1$} form based on Unicode NFKC
     * (compatibility composition) and {@link Locale#ROOT} lower-casing. Unicode
     * letters and numbers are retained; every other code point is represented as
     * {@code $HEX$}. The version marker makes the representation idempotent when a
     * persisted id is read again.</p>
     *
     * <p>Blank input remains the empty sentinel used by editors while a label is
     * being drafted. {@link ConditionAssignments#addAxis(ConditionAxis)} rejects
     * that sentinel before it can enter a persisted assignment schema. Unknown
     * or malformed versioned ids are rejected instead of being silently migrated
     * to a different key.</p>
     */
    public static String normaliseId(String raw) {
        if (raw == null) return "";
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) return "";

        if (looksVersioned(trimmed)) {
            return normaliseVersionedId(trimmed);
        }

        String lower = Normalizer.normalize(trimmed, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT);
        if (canUseLegacyForm(lower)) {
            return legacyNormalise(lower);
        }

        StringBuilder out = new StringBuilder(lower.length() + 8);
        out.append("u1$");
        for (int offset = 0; offset < lower.length();) {
            int cp = lower.codePointAt(offset);
            if (Character.isLetterOrDigit(cp)) {
                out.appendCodePoint(cp);
            } else if (Character.isWhitespace(cp) || cp == '_') {
                // CSV headers collapse whitespace to underscores. Give both spellings one
                // canonical separator so the persisted header resolves to the same id.
                appendEncodedCodePoint(out, ' ');
            } else {
                appendEncodedCodePoint(out, cp);
            }
            offset += Character.charCount(cp);
        }
        return out.toString();
    }

    private static boolean looksVersioned(String value) {
        if (value.length() < 3 || value.charAt(0) != 'u') return false;
        int i = 1;
        while (i < value.length() && Character.isDigit(value.charAt(i))) i++;
        return i > 1 && i < value.length() && value.charAt(i) == '$';
    }

    private static String normaliseVersionedId(String value) {
        int separator = value.indexOf('$');
        String version = value.substring(1, separator);
        if (!"1".equals(version)) {
            throw new IllegalArgumentException("Unsupported condition identity version: " + version);
        }
        String payload = value.substring(separator + 1);
        if (payload.isEmpty()) {
            throw new IllegalArgumentException("Malformed u1 condition identity: empty payload.");
        }

        StringBuilder out = new StringBuilder(value.length());
        out.append("u1$");
        for (int offset = 0; offset < payload.length();) {
            int cp = payload.codePointAt(offset);
            if (Character.isLetterOrDigit(cp)) {
                String token = new String(Character.toChars(cp));
                out.append(Normalizer.normalize(token, Normalizer.Form.NFKC)
                        .toLowerCase(Locale.ROOT));
                offset += Character.charCount(cp);
                continue;
            }
            if (cp != '$') {
                throw new IllegalArgumentException(
                        "Malformed u1 condition identity at payload offset " + offset + ".");
            }
            int end = payload.indexOf('$', offset + 1);
            if (end < 0 || end == offset + 1) {
                throw new IllegalArgumentException(
                        "Malformed u1 condition identity escape at payload offset " + offset + ".");
            }
            String hex = payload.substring(offset + 1, end);
            int encoded;
            try {
                encoded = Integer.parseInt(hex, 16);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Malformed u1 condition identity escape: " + hex, e);
            }
            if (!Character.isValidCodePoint(encoded)
                    || (encoded >= Character.MIN_SURROGATE && encoded <= Character.MAX_SURROGATE)
                    || Character.isLetterOrDigit(encoded)) {
                throw new IllegalArgumentException("Invalid u1 condition identity escape: " + hex);
            }
            appendEncodedCodePoint(out, encoded);
            offset = end + 1;
        }
        if (out.length() == 3) {
            throw new IllegalArgumentException("Malformed u1 condition identity: empty payload.");
        }
        return out.toString();
    }

    private static boolean canUseLegacyForm(String value) {
        boolean hasAsciiLetterOrNumber = false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c >= 'a' && c <= 'z' || c >= '0' && c <= '9') {
                hasAsciiLetterOrNumber = true;
            } else if (c > 0x7f || Character.isISOControl(c)) {
                return false;
            }
        }
        return hasAsciiLetterOrNumber;
    }

    private static String legacyNormalise(String lower) {
        String collapsed = lower.replaceAll("[^a-z0-9]+", "_");
        int start = 0;
        int end = collapsed.length();
        while (start < end && collapsed.charAt(start) == '_') start++;
        while (end > start && collapsed.charAt(end - 1) == '_') end--;
        return collapsed.substring(start, end);
    }

    private static void appendEncodedCodePoint(StringBuilder out, int cp) {
        out.append('$').append(Integer.toHexString(cp).toUpperCase(Locale.ROOT)).append('$');
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ConditionAxis)) return false;
        return id.equals(((ConditionAxis) o).id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        return "ConditionAxis{id=" + id + ", label=" + label + ", order=" + order + "}";
    }
}
