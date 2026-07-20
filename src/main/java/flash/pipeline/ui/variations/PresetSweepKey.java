package flash.pipeline.ui.variations;

import java.util.Locale;

public final class PresetSweepKey implements ParameterKey {

    public enum Role {
        X_VALUE,
        PRESET_NAME,
        X_PARAM_KEY
    }

    private final Role role;
    private final String paramKey;

    private PresetSweepKey(Role role, String paramKey) {
        if (role == null) {
            throw new IllegalArgumentException("role must not be null");
        }
        this.role = role;
        this.paramKey = paramKey == null ? "" : paramKey.trim();
    }

    public static PresetSweepKey xValue(String paramKey) {
        return new PresetSweepKey(Role.X_VALUE, paramKey);
    }

    public static PresetSweepKey presetName() {
        return new PresetSweepKey(Role.PRESET_NAME, "");
    }

    public static PresetSweepKey xParamKey() {
        return new PresetSweepKey(Role.X_PARAM_KEY, "");
    }

    public Role role() {
        return role;
    }

    public String paramKey() {
        return paramKey;
    }

    public boolean orderable() {
        return role == Role.X_VALUE || role == Role.PRESET_NAME;
    }

    @Override public String stableKey() {
        if (role == Role.X_VALUE) {
            return "filter.preset.0.xValue." + safeKey(paramKey);
        }
        if (role == Role.PRESET_NAME) {
            return "filter.preset.1.presetName";
        }
        return "filter.preset.2.xParamKey";
    }

    @Override public String displayLabel() {
        if (role == Role.X_VALUE) {
            return paramKey.length() == 0 ? "value" : paramKey;
        }
        if (role == Role.PRESET_NAME) {
            return "preset";
        }
        return "X parameter";
    }

    @Override public ValueKind valueKind() {
        return role == Role.X_VALUE ? ValueKind.NUMBER : ValueKind.STRING;
    }

    @Override public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof PresetSweepKey)) return false;
        PresetSweepKey other = (PresetSweepKey) obj;
        return role == other.role && paramKey.equals(other.paramKey);
    }

    @Override public int hashCode() {
        int result = role.hashCode();
        result = 31 * result + paramKey.hashCode();
        return result;
    }

    @Override public String toString() {
        return stableKey();
    }

    private static String safeKey(String value) {
        String raw = value == null ? "" : value.trim();
        if (raw.isEmpty()) {
            return "value";
        }
        return raw.replaceAll("[^A-Za-z0-9_.-]", "_")
                .toLowerCase(Locale.ROOT);
    }
}
