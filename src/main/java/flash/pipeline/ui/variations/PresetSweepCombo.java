package flash.pipeline.ui.variations;

import java.util.Map;

public final class PresetSweepCombo {

    private final String presetName;
    private final String xParamKey;
    private final Object xValue;

    private PresetSweepCombo(String presetName, String xParamKey, Object xValue) {
        this.presetName = presetName == null ? "" : presetName.trim();
        String normalizedParam = xParamKey == null ? "" : xParamKey.trim();
        this.xParamKey = normalizedParam.length() == 0 ? null : normalizedParam;
        this.xValue = xValue;
    }

    public static PresetSweepCombo forPresetOnly(String presetName) {
        return new PresetSweepCombo(presetName, null, null);
    }

    public static PresetSweepCombo from(ParameterCombo combo) {
        if (combo == null) {
            return null;
        }
        String presetName = null;
        String xParamKey = null;
        Object xValue = null;
        String keyParam = null;
        boolean hasXValue = false;
        for (Map.Entry<ParameterKey, Object> entry : combo.values().entrySet()) {
            if (!(entry.getKey() instanceof PresetSweepKey)) {
                continue;
            }
            PresetSweepKey key = (PresetSweepKey) entry.getKey();
            if (key.role() == PresetSweepKey.Role.PRESET_NAME) {
                presetName = stringValue(entry.getValue());
            } else if (key.role() == PresetSweepKey.Role.X_PARAM_KEY) {
                xParamKey = stringValue(entry.getValue());
            } else if (key.role() == PresetSweepKey.Role.X_VALUE) {
                xValue = entry.getValue();
                keyParam = key.paramKey();
                hasXValue = true;
            }
        }
        if (presetName == null || presetName.trim().isEmpty()) {
            return null;
        }
        if (!hasXValue) {
            return forPresetOnly(presetName);
        }
        if (xValue == null) {
            return null;
        }
        if (xParamKey == null || xParamKey.trim().isEmpty()) {
            xParamKey = keyParam;
        }
        if (xParamKey == null || xParamKey.trim().isEmpty()) {
            return null;
        }
        return new PresetSweepCombo(presetName, xParamKey, xValue);
    }

    public static boolean isIncompatible(Throwable error) {
        return error instanceof IncompatiblePresetException;
    }

    public String presetName() {
        return presetName;
    }

    public String xParamKey() {
        return xParamKey;
    }

    public Object xValue() {
        return xValue;
    }

    public String displayValue() {
        return formatValue(xValue);
    }

    private static String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static String formatValue(Object value) {
        if (value instanceof Number) {
            double number = ((Number) value).doubleValue();
            if (Math.abs(number - Math.rint(number)) < 0.0000001d
                    && Math.abs(number) < 1000000000.0d) {
                return String.valueOf((long) Math.rint(number));
            }
            String text = String.format(java.util.Locale.ROOT, "%.3f",
                    Double.valueOf(number));
            return text.replaceAll("0+$", "").replaceAll("\\.$", "");
        }
        return value == null ? "" : String.valueOf(value);
    }

    public static final class IncompatiblePresetException
            extends IllegalArgumentException {
        public IncompatiblePresetException(String message) {
            super(message);
        }
    }
}
