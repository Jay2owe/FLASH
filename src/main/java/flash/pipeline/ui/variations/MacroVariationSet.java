package flash.pipeline.ui.variations;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class MacroVariationSet {

    private final LinkedHashMap<String, MacroVariation> variations;

    public MacroVariationSet(List<MacroVariation> variations) {
        LinkedHashMap<String, MacroVariation> out =
                new LinkedHashMap<String, MacroVariation>();
        out.put(MacroToken.NONE_VALUE, MacroVariation.none());
        if (variations != null) {
            for (int i = 0; i < variations.size(); i++) {
                put(out, variations.get(i));
            }
        }
        this.variations = new LinkedHashMap<String, MacroVariation>(out);
    }

    public MacroVariationSet(Map<String, MacroVariation> variations) {
        LinkedHashMap<String, MacroVariation> out =
                new LinkedHashMap<String, MacroVariation>();
        out.put(MacroToken.NONE_VALUE, MacroVariation.none());
        if (variations != null) {
            for (Map.Entry<String, MacroVariation> entry : variations.entrySet()) {
                put(out, entry.getValue());
            }
        }
        this.variations = new LinkedHashMap<String, MacroVariation>(out);
    }

    public static MacroVariationSet none() {
        return new MacroVariationSet(Collections.<MacroVariation>emptyList());
    }

    public static MacroVariationSet of(MacroVariation... variations) {
        List<MacroVariation> list = new ArrayList<MacroVariation>();
        if (variations != null) {
            for (int i = 0; i < variations.length; i++) {
                list.add(variations[i]);
            }
        }
        return new MacroVariationSet(list);
    }

    public MacroVariation resolve(String token) {
        String safeToken = token == null || token.trim().isEmpty()
                ? MacroToken.NONE_VALUE
                : token.trim();
        return variations.get(safeToken);
    }

    public List<String> tokens() {
        return new ArrayList<String>(variations.keySet());
    }

    public List<MacroVariation> variations() {
        return new ArrayList<MacroVariation>(variations.values());
    }

    public Map<String, MacroVariation> asMap() {
        return Collections.unmodifiableMap(variations);
    }

    public String displayNameFor(String token) {
        MacroVariation variation = resolve(token);
        return variation == null ? token : variation.displayName();
    }

    public String identityForToken(String token) {
        String safeToken = token == null || token.trim().isEmpty()
                ? MacroToken.NONE_VALUE
                : token.trim();
        if (MacroToken.NONE_VALUE.equals(safeToken)) {
            return "macro:none";
        }
        MacroVariation variation = resolve(safeToken);
        String hash = variation == null
                ? parsedHash(safeToken)
                : variation.normalizedScriptHash();
        return "macro:" + safeToken + ":" + (hash == null ? "" : hash);
    }

    public String toCanonicalJson() {
        return CanonicalJson.write(toCanonicalObject());
    }

    public Object toCanonicalObject() {
        LinkedHashMap<String, Object> root = new LinkedHashMap<String, Object>();
        List<Object> rows = new ArrayList<Object>();
        for (MacroVariation variation : variations.values()) {
            rows.add(canonicalRow(variation));
        }
        root.put("variations", rows);
        return root;
    }

    private static void put(LinkedHashMap<String, MacroVariation> out,
                            MacroVariation variation) {
        if (variation == null) {
            return;
        }
        String token = variation.token();
        if (token == null || token.trim().isEmpty()
                || MacroToken.NONE_VALUE.equals(token.trim())) {
            out.put(MacroToken.NONE_VALUE, MacroVariation.none());
            return;
        }
        out.put(token.trim(), variation);
    }

    private static Map<String, Object> canonicalRow(MacroVariation variation) {
        LinkedHashMap<String, Object> row = new LinkedHashMap<String, Object>();
        row.put("displayName", variation.displayName());
        row.put("normalizedScriptHash", variation.normalizedScriptHash());
        row.put("sourceKind", variation.sourceKind());
        row.put("sourceName", variation.sourceName());
        row.put("token", variation.token());
        return row;
    }

    private static String parsedHash(String token) {
        try {
            return MacroToken.parse(token).normalizedScriptHash();
        } catch (RuntimeException e) {
            return "invalid";
        }
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof MacroVariationSet)) return false;
        MacroVariationSet other = (MacroVariationSet) obj;
        return variations.equals(other.variations);
    }

    @Override
    public int hashCode() {
        return variations.hashCode();
    }

    @Override
    public String toString() {
        return variations.values().toString();
    }
}
