package flash.pipeline.naming;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Suggests LH/RH from conservative built-in terms plus optional user aliases.
 * These suggestions are for the setup UI only; downstream analyses use saved
 * manifest rows, not filename alias guesses.
 */
public final class HemisphereAliasMatcher {

    private static final List<String> BUILT_IN_LH = Collections.unmodifiableList(
            Arrays.asList("LH", "L", "Left", "LeftHemi", "LeftHemisphere"));
    private static final List<String> BUILT_IN_RH = Collections.unmodifiableList(
            Arrays.asList("RH", "R", "Right", "RightHemi", "RightHemisphere"));

    private HemisphereAliasMatcher() {}

    public static Map<OrientationManifestRow.Hemisphere, List<String>> builtInAliases() {
        LinkedHashMap<OrientationManifestRow.Hemisphere, List<String>> aliases =
                new LinkedHashMap<OrientationManifestRow.Hemisphere, List<String>>();
        aliases.put(OrientationManifestRow.Hemisphere.LH, new ArrayList<String>(BUILT_IN_LH));
        aliases.put(OrientationManifestRow.Hemisphere.RH, new ArrayList<String>(BUILT_IN_RH));
        return aliases;
    }

    public static Suggestion match(String text) {
        return match(text, null);
    }

    public static Suggestion match(String text,
                                   Map<OrientationManifestRow.Hemisphere, List<String>> userAliases) {
        String source = text == null ? "" : text;
        if (source.trim().isEmpty()) return Suggestion.unknown();

        List<String> tokens = tokenize(source);
        Match lh = findMatch(source, tokens, OrientationManifestRow.Hemisphere.LH, userAliases);
        Match rh = findMatch(source, tokens, OrientationManifestRow.Hemisphere.RH, userAliases);
        if (lh.found && rh.found) return Suggestion.ambiguous(lh.alias + " / " + rh.alias);
        if (lh.found) return Suggestion.of(OrientationManifestRow.Hemisphere.LH, lh.alias);
        if (rh.found) return Suggestion.of(OrientationManifestRow.Hemisphere.RH, rh.alias);
        return Suggestion.unknown();
    }

    public static boolean matchesAliasToken(String token,
                                            OrientationManifestRow.Hemisphere hemisphere,
                                            Map<OrientationManifestRow.Hemisphere, List<String>> userAliases) {
        String normalizedToken = normalize(token);
        if (normalizedToken.isEmpty()) return false;
        List<String> aliases = aliasesFor(hemisphere, userAliases);
        for (String alias : aliases) {
            if (normalizedToken.equals(normalize(alias))) return true;
        }
        return false;
    }

    private static Match findMatch(String source,
                                   List<String> tokens,
                                   OrientationManifestRow.Hemisphere hemisphere,
                                   Map<OrientationManifestRow.Hemisphere, List<String>> userAliases) {
        List<String> aliases = aliasesFor(hemisphere, userAliases);
        for (String alias : aliases) {
            String normalizedAlias = normalize(alias);
            if (normalizedAlias.isEmpty()) continue;
            for (String token : tokens) {
                if (normalize(token).equals(normalizedAlias)) {
                    return new Match(true, alias);
                }
            }
        }

        String normalizedSource = normalize(source);
        for (String alias : aliases) {
            String normalizedAlias = normalize(alias);
            if (normalizedAlias.length() >= 4 && normalizedSource.contains(normalizedAlias)) {
                return new Match(true, alias);
            }
        }
        return new Match(false, "");
    }

    private static List<String> aliasesFor(OrientationManifestRow.Hemisphere hemisphere,
                                           Map<OrientationManifestRow.Hemisphere, List<String>> userAliases) {
        ArrayList<String> aliases = new ArrayList<String>();
        if (hemisphere == OrientationManifestRow.Hemisphere.LH) {
            aliases.addAll(BUILT_IN_LH);
        } else if (hemisphere == OrientationManifestRow.Hemisphere.RH) {
            aliases.addAll(BUILT_IN_RH);
        }
        if (userAliases != null) {
            List<String> extra = userAliases.get(hemisphere);
            if (extra != null) {
                for (String alias : extra) {
                    if (alias != null && !alias.trim().isEmpty()) aliases.add(alias.trim());
                }
            }
        }
        return aliases;
    }

    private static List<String> tokenize(String text) {
        String[] raw = text.split("[^A-Za-z0-9]+");
        ArrayList<String> tokens = new ArrayList<String>();
        for (String token : raw) {
            if (token != null && !token.trim().isEmpty()) tokens.add(token.trim());
        }
        return tokens;
    }

    private static String normalize(String value) {
        if (value == null) return "";
        StringBuilder sb = new StringBuilder();
        String upper = value.toUpperCase(Locale.ROOT);
        for (int i = 0; i < upper.length(); i++) {
            char c = upper.charAt(i);
            if (Character.isLetterOrDigit(c)) sb.append(c);
        }
        return sb.toString();
    }

    private static final class Match {
        final boolean found;
        final String alias;

        Match(boolean found, String alias) {
            this.found = found;
            this.alias = alias == null ? "" : alias;
        }
    }

    public static final class Suggestion {
        public final OrientationManifestRow.Hemisphere hemisphere;
        public final String matchedAlias;
        public final boolean ambiguous;

        private Suggestion(OrientationManifestRow.Hemisphere hemisphere,
                           String matchedAlias,
                           boolean ambiguous) {
            this.hemisphere = hemisphere == null
                    ? OrientationManifestRow.Hemisphere.UNKNOWN : hemisphere;
            this.matchedAlias = matchedAlias == null ? "" : matchedAlias;
            this.ambiguous = ambiguous;
        }

        static Suggestion of(OrientationManifestRow.Hemisphere hemisphere, String alias) {
            return new Suggestion(hemisphere, alias, false);
        }

        static Suggestion ambiguous(String aliases) {
            return new Suggestion(OrientationManifestRow.Hemisphere.UNKNOWN, aliases, true);
        }

        static Suggestion unknown() {
            return new Suggestion(OrientationManifestRow.Hemisphere.UNKNOWN, "", false);
        }

        public boolean hasHemisphere() {
            return hemisphere == OrientationManifestRow.Hemisphere.LH
                    || hemisphere == OrientationManifestRow.Hemisphere.RH;
        }
    }
}
