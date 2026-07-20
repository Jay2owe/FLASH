package flash.pipeline.ui.variations;

import java.util.Locale;

public final class MacroVariation {

    public static final String SOURCE_NONE = MacroToken.SOURCE_NONE;
    public static final String SOURCE_CURRENT_CHANNEL = MacroToken.SOURCE_CURRENT_CHANNEL;
    public static final String SOURCE_BUNDLED_PRESET = MacroToken.SOURCE_BUNDLED_PRESET;
    public static final String SOURCE_SAVED_PRESET = MacroToken.SOURCE_SAVED_PRESET;
    public static final String SOURCE_PASTED = MacroToken.SOURCE_PASTED;
    public static final String SOURCE_RECORDED = MacroToken.SOURCE_RECORDED;

    private final String token;
    private final String displayName;
    private final String sourceKind;
    private final String sourceName;
    private final String scriptText;
    private final String normalizedScriptHash;

    public MacroVariation(String displayName,
                          String sourceKind,
                          String sourceName,
                          String scriptText) {
        this(MacroToken.forScript(sourceKind, sourceName, scriptText),
                displayName,
                sourceName,
                scriptText);
    }

    public MacroVariation(MacroToken token,
                          String displayName,
                          String sourceName,
                          String scriptText) {
        if (token == null || MacroToken.NONE_VALUE.equals(token.value())) {
            this.token = MacroToken.NONE_VALUE;
            this.displayName = "No macro";
            this.sourceKind = SOURCE_NONE;
            this.sourceName = "";
            this.scriptText = "";
            this.normalizedScriptHash = MacroToken.none().normalizedScriptHash();
            return;
        }
        this.token = token.value();
        this.displayName = cleanDisplayName(displayName, sourceName);
        this.sourceKind = token.sourceKind();
        this.sourceName = sourceName == null ? "" : sourceName.trim();
        this.scriptText = scriptText == null ? "" : scriptText;
        this.normalizedScriptHash = token.normalizedScriptHash();
    }

    private MacroVariation(String token,
                           String displayName,
                           String sourceKind,
                           String sourceName,
                           String scriptText,
                           String normalizedScriptHash) {
        String safeToken = token == null ? "" : token.trim();
        if (safeToken.isEmpty() || MacroToken.NONE_VALUE.equals(safeToken)) {
            this.token = MacroToken.NONE_VALUE;
            this.displayName = "No macro";
            this.sourceKind = SOURCE_NONE;
            this.sourceName = "";
            this.scriptText = "";
            this.normalizedScriptHash = MacroToken.none().normalizedScriptHash();
            return;
        }
        this.token = safeToken;
        this.displayName = cleanDisplayName(displayName, sourceName);
        this.sourceKind = normalizeSourceKind(sourceKind, safeToken);
        this.sourceName = sourceName == null ? "" : sourceName.trim();
        this.scriptText = scriptText == null ? "" : scriptText;
        this.normalizedScriptHash = cleanHash(normalizedScriptHash, safeToken);
    }

    public static MacroVariation none() {
        return new MacroVariation(MacroToken.none(), "No macro", "", "");
    }

    public static MacroVariation currentChannel(String displayName,
                                                String sourceName,
                                                String scriptText) {
        return new MacroVariation(displayName, SOURCE_CURRENT_CHANNEL,
                sourceName, scriptText);
    }

    public static MacroVariation bundledPreset(String displayName,
                                               String sourceName,
                                               String scriptText) {
        return new MacroVariation(displayName, SOURCE_BUNDLED_PRESET,
                sourceName, scriptText);
    }

    public static MacroVariation savedPreset(String displayName,
                                             String sourceName,
                                             String scriptText) {
        return new MacroVariation(displayName, SOURCE_SAVED_PRESET,
                sourceName, scriptText);
    }

    public static MacroVariation pasted(String displayName,
                                        String scriptText) {
        return new MacroVariation(displayName, SOURCE_PASTED, "", scriptText);
    }

    public static MacroVariation recorded(String displayName,
                                          String sourceName,
                                          String scriptText) {
        return new MacroVariation(displayName, SOURCE_RECORDED,
                sourceName, scriptText);
    }

    public static MacroVariation identityOnly(String token,
                                              String displayName,
                                              String sourceKind,
                                              String sourceName,
                                              String normalizedScriptHash) {
        return new MacroVariation(token, displayName, sourceKind, sourceName,
                "", normalizedScriptHash);
    }

    public String token() {
        return token;
    }

    public String getToken() {
        return token;
    }

    public String displayName() {
        return displayName;
    }

    public String getDisplayName() {
        return displayName;
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

    public String scriptText() {
        return scriptText;
    }

    public String getScriptText() {
        return scriptText;
    }

    public String normalizedScriptHash() {
        return normalizedScriptHash;
    }

    public String getNormalizedScriptHash() {
        return normalizedScriptHash;
    }

    private static String cleanDisplayName(String displayName, String sourceName) {
        String display = displayName == null ? "" : displayName.trim();
        if (!display.isEmpty()) {
            return display;
        }
        String source = sourceName == null ? "" : sourceName.trim();
        return source.isEmpty() ? "Macro" : source;
    }

    private static String normalizeSourceKind(String sourceKind, String token) {
        String kind = sourceKind == null
                ? ""
                : sourceKind.trim().toUpperCase(Locale.ROOT);
        if (SOURCE_NONE.equals(kind)
                || SOURCE_CURRENT_CHANNEL.equals(kind)
                || SOURCE_BUNDLED_PRESET.equals(kind)
                || SOURCE_SAVED_PRESET.equals(kind)
                || SOURCE_PASTED.equals(kind)
                || SOURCE_RECORDED.equals(kind)) {
            return kind;
        }
        try {
            return MacroToken.parse(token).sourceKind();
        } catch (RuntimeException e) {
            return SOURCE_PASTED;
        }
    }

    private static String cleanHash(String normalizedScriptHash, String token) {
        String hash = normalizedScriptHash == null
                ? ""
                : normalizedScriptHash.trim().toLowerCase(Locale.ROOT);
        if (!hash.isEmpty()) {
            return hash;
        }
        try {
            return MacroToken.parse(token).normalizedScriptHash();
        } catch (RuntimeException e) {
            return "";
        }
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof MacroVariation)) return false;
        MacroVariation other = (MacroVariation) obj;
        return token.equals(other.token)
                && displayName.equals(other.displayName)
                && sourceKind.equals(other.sourceKind)
                && sourceName.equals(other.sourceName)
                && scriptText.equals(other.scriptText)
                && normalizedScriptHash.equals(other.normalizedScriptHash);
    }

    @Override
    public int hashCode() {
        int result = token.hashCode();
        result = 31 * result + displayName.hashCode();
        result = 31 * result + sourceKind.hashCode();
        result = 31 * result + sourceName.hashCode();
        result = 31 * result + scriptText.hashCode();
        result = 31 * result + normalizedScriptHash.hashCode();
        return result;
    }

    @Override
    public String toString() {
        return displayName + " (" + token + ")";
    }
}
