package flash.pipeline.zslice;

import java.util.Locale;

/**
 * Global strategy for how Z-slice selections are interpreted.
 */
public enum ZSliceMode {
    FULL("zslice:full", "Full stack"),
    PER_IMAGE("zslice:per_image", "Custom per image"),
    SAME_COUNT("zslice:same_count", "Same slice count per image"),
    SAME_ABSOLUTE("zslice:same_absolute", "Same absolute slice window");

    public final String configToken;
    public final String displayName;

    ZSliceMode(String configToken, String displayName) {
        this.configToken = configToken;
        this.displayName = displayName;
    }

    public boolean usesSubset() {
        return this != FULL;
    }

    public static ZSliceMode fromConfigToken(String token) {
        if (token == null) return FULL;
        String normalized = token.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) return FULL;
        if ("full".equals(normalized) || FULL.configToken.equals(normalized)) return FULL;
        if ("per_image".equals(normalized) || PER_IMAGE.configToken.equals(normalized)) return PER_IMAGE;
        if ("same_count".equals(normalized) || SAME_COUNT.configToken.equals(normalized)) return SAME_COUNT;
        if ("same_absolute".equals(normalized) || SAME_ABSOLUTE.configToken.equals(normalized)) return SAME_ABSOLUTE;
        return FULL;
    }
}
