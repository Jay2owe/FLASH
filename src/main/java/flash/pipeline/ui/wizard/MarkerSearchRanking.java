package flash.pipeline.ui.wizard;

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
        if (matchesPrefix(displayName, normalized) || matchesPrefix(aliases, normalized)) {
            return 0;
        }
        if (matchesSubstring(displayName, normalized) || matchesSubstring(aliases, normalized)) {
            return 1;
        }
        if (matchesSubstring(nameHints, normalized)) {
            return 2;
        }
        if (matchesSubstring(id, normalized)) {
            return 3;
        }
        return NO_MATCH;
    }

    public static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "");
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

    private static boolean matchesPrefix(String value, String query) {
        return normalize(value).startsWith(query);
    }

    private static boolean matchesSubstring(String value, String query) {
        return normalize(value).contains(query);
    }
}
