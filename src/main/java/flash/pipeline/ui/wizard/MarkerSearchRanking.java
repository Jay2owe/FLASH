package flash.pipeline.ui.wizard;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;

/**
 * Shared marker search ranking for marker library search and typeahead.
 */
public final class MarkerSearchRanking {

    public static final int NO_MATCH = Integer.MAX_VALUE;

    private MarkerSearchRanking() {
    }

    public static int rank(String id,
                           String displayName,
                           List<String> aliases,
                           List<String> nameHints,
                           String query) {
        String normalized = normalize(query);
        if (normalized.length() == 0) {
            return NO_MATCH;
        }
        if (exactKey(query).equals(exactKey(id))) {
            return 0;
        }
        if (exactKey(query).equals(exactKey(displayName))
                || containsExactKey(aliases, exactKey(query))) {
            return 1;
        }
        if (matchesPrefix(displayName, normalized) || matchesPrefix(aliases, normalized)) {
            return 2;
        }
        if (matchesSubstring(displayName, normalized) || matchesSubstring(aliases, normalized)) {
            return 3;
        }
        if (matchesSubstring(nameHints, normalized)) {
            return 4;
        }
        if (matchesSubstring(id, normalized)) {
            return 5;
        }
        return NO_MATCH;
    }

    /**
     * Returns whether {@code query} names this exact marker rather than merely
     * producing a search suggestion.  Accepted marker binding deliberately
     * uses this stricter predicate: a prefix/substring hit is useful while the
     * user is typing, but is not a safe biological identity to persist.
     */
    public static boolean isExactMarkerMatch(String id,
                                             String displayName,
                                             List<String> aliases,
                                             String query) {
        String exact = exactKey(query);
        if (exact.length() == 0) {
            return false;
        }
        return safeTrim(query).equals(safeTrim(id))
                || exact.equals(exactKey(displayName))
                || containsExactKey(aliases, exact);
    }

    /**
     * Search normalization keeps every Unicode letter, digit, and combining
     * mark. Punctuation and spacing are ignored, but biologically meaningful
     * distinctions such as Greek alpha, beta, delta, and epsilon are never
     * erased.
     */
    public static String normalize(String value) {
        String canonical = Normalizer.normalize(safeTrim(value), Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT);
        StringBuilder out = new StringBuilder(canonical.length());
        for (int offset = 0; offset < canonical.length();) {
            int codePoint = canonical.codePointAt(offset);
            int type = Character.getType(codePoint);
            if (Character.isLetterOrDigit(codePoint)
                    || type == Character.NON_SPACING_MARK
                    || type == Character.COMBINING_SPACING_MARK
                    || type == Character.ENCLOSING_MARK) {
                out.appendCodePoint(codePoint);
            }
            offset += Character.charCount(codePoint);
        }
        return out.toString();
    }

    /** Case-insensitive, Unicode-canonical key for exact display synonyms. */
    public static String exactKey(String value) {
        return Normalizer.normalize(safeTrim(value), Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT);
    }

    private static boolean matchesPrefix(List<String> values, String query) {
        if (values == null) return false;
        for (String value : values) {
            if (matchesPrefix(value, query)) {
                return true;
            }
        }
        return false;
    }

    private static boolean matchesSubstring(List<String> values, String query) {
        if (values == null) return false;
        for (String value : values) {
            if (matchesSubstring(value, query)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsExactKey(List<String> values, String query) {
        if (values == null) return false;
        for (String value : values) {
            if (query.equals(exactKey(value))) {
                return true;
            }
        }
        return false;
    }

    private static boolean matchesPrefix(String value, String query) {
        return normalize(value).startsWith(query);
    }

    private static boolean matchesSubstring(String value, String query) {
        return normalize(value).contains(query);
    }

    private static String safeTrim(String value) {
        return value == null ? "" : value.trim();
    }
}
