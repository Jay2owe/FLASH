package flash.pipeline.atlas;

/** Conservative exact resolver for user-entered atlas region text. */
public final class AtlasRegionResolver {
    private AtlasRegionResolver() {
    }

    public static Resolved resolve(String value, AtlasRegionLibrary library) {
        return resolveOne(value, library, true);
    }

    public static Resolved resolveFromRegionAndRoi(String region, String roi, AtlasRegionLibrary library) {
        Resolved fromRegion = resolveOne(region, library, true);
        if (fromRegion.isResolved()) return fromRegion;
        return resolveOne(roi, library, true);
    }

    public static String canonicalizeIfExact(String value, AtlasRegionLibrary library) {
        Resolved resolved = resolveOne(value, library, false);
        return resolved.isResolved() ? resolved.getRegion().getAcronym() : safe(value);
    }

    private static Resolved resolveOne(String value, AtlasRegionLibrary library, boolean allowSectionSuffix) {
        String input = safe(value);
        if (library == null || input.isEmpty()) {
            return Resolved.unresolved(input);
        }
        AtlasRegion exact = exact(input, library);
        if (exact != null) {
            return Resolved.exact(input, exact);
        }
        if (allowSectionSuffix) {
            String stripped = stripTrailingDigits(input);
            if (!stripped.equals(input) && !stripped.isEmpty()) {
                AtlasRegion strippedRegion = exact(stripped, library);
                if (strippedRegion != null) {
                    return Resolved.stripped(input, stripped, strippedRegion);
                }
            }
        }
        return Resolved.unresolved(input);
    }

    private static AtlasRegion exact(String value, AtlasRegionLibrary library) {
        AtlasRegion byAcronym = library.byAcronymOrAlias(value);
        if (byAcronym != null) return byAcronym;
        AtlasRegion byName = library.byName(value);
        if (byName != null) return byName;
        return byId(value, library);
    }

    private static AtlasRegion byId(String value, AtlasRegionLibrary library) {
        String text = safe(value);
        if (text.isEmpty()) return null;
        for (int i = 0; i < text.length(); i++) {
            if (!Character.isDigit(text.charAt(i))) return null;
        }
        try {
            return library.byId(Integer.parseInt(text));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String stripTrailingDigits(String value) {
        String text = safe(value);
        int i = text.length();
        while (i > 0 && Character.isDigit(text.charAt(i - 1))) {
            i--;
        }
        return text.substring(0, i).trim();
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }

    public static final class Resolved {
        private final String input;
        private final String matchedText;
        private final AtlasRegion region;
        private final boolean exact;
        private final boolean strippedSectionSuffix;

        private Resolved(String input,
                         String matchedText,
                         AtlasRegion region,
                         boolean exact,
                         boolean strippedSectionSuffix) {
            this.input = safe(input);
            this.matchedText = safe(matchedText);
            this.region = region;
            this.exact = exact;
            this.strippedSectionSuffix = strippedSectionSuffix;
        }

        static Resolved exact(String input, AtlasRegion region) {
            return new Resolved(input, input, region, true, false);
        }

        static Resolved stripped(String input, String matchedText, AtlasRegion region) {
            return new Resolved(input, matchedText, region, false, true);
        }

        static Resolved unresolved(String input) {
            return new Resolved(input, "", null, false, false);
        }

        public String getInput() {
            return input;
        }

        public String getMatchedText() {
            return matchedText;
        }

        public AtlasRegion getRegion() {
            return region;
        }

        public boolean isResolved() {
            return region != null;
        }

        public boolean isExact() {
            return exact;
        }

        public boolean isStrippedSectionSuffix() {
            return strippedSectionSuffix;
        }
    }
}
