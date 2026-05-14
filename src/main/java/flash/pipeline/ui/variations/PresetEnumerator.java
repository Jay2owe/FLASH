package flash.pipeline.ui.variations;

import flash.pipeline.image.FilterMacroEditorModel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class PresetEnumerator {

    private static final String CUSTOM_PRESET = "Custom";

    private final List<String> presetOptions;
    private final FilterVariationEngineContext.PresetMacroLoader loader;

    public PresetEnumerator(List<String> presetOptions,
                            FilterVariationEngineContext.PresetMacroLoader loader) {
        this.presetOptions = copyOptions(presetOptions);
        this.loader = loader;
    }

    public Result enumerate() {
        List<PresetInfo> readable = new ArrayList<PresetInfo>();
        List<SkippedPreset> skipped = new ArrayList<SkippedPreset>();
        Set<String> seen = new LinkedHashSet<String>();
        for (int i = 0; i < presetOptions.size(); i++) {
            String name = safeName(presetOptions.get(i));
            if (name.length() == 0 || CUSTOM_PRESET.equalsIgnoreCase(name)) {
                continue;
            }
            String normalized = name.toLowerCase(Locale.ROOT);
            if (!seen.add(normalized)) {
                continue;
            }
            if (loader == null) {
                skipped.add(new SkippedPreset(name, "No preset loader available"));
                continue;
            }
            try {
                String macro = loader.loadPresetMacro(name);
                if (macro == null || macro.trim().isEmpty()) {
                    skipped.add(new SkippedPreset(name, "No macro content"));
                    continue;
                }
                FilterMacroEditorModel.MacroDefinition parsed =
                        FilterMacroEditorModel.parse(macro);
                readable.add(new PresetInfo(name, macro, parsed,
                        chainSummary(parsed), numericParams(parsed)));
            } catch (Exception e) {
                skipped.add(new SkippedPreset(name, reasonFor(e)));
            }
        }
        return new Result(readable, skipped);
    }

    private static LinkedHashMap<String, NumericParam> numericParams(
            FilterMacroEditorModel.MacroDefinition macro) {
        LinkedHashMap<String, NumericParam> out =
                new LinkedHashMap<String, NumericParam>();
        if (macro == null) {
            return out;
        }
        List<FilterMacroEditorModel.Section> sections = macro.getSections();
        for (int i = 0; i < sections.size(); i++) {
            FilterMacroEditorModel.Section section = sections.get(i);
            for (int j = 0; j < section.entries.size(); j++) {
                FilterMacroEditorModel.Entry entry = section.entries.get(j);
                for (int k = 0; k < entry.parameters.size(); k++) {
                    FilterMacroEditorModel.Parameter parameter =
                            entry.parameters.get(k);
                    if (parameter == null || parameter.key == null) {
                        continue;
                    }
                    String key = parameter.key.trim();
                    if (key.isEmpty() || out.containsKey(key)) {
                        continue;
                    }
                    Double value = finiteDouble(parameter.getValue());
                    if (value == null) {
                        value = finiteDouble(parameter.defaultValue);
                    }
                    if (value != null) {
                        out.put(key, new NumericParam(key, value.doubleValue()));
                    }
                }
            }
        }
        return out;
    }

    private static String chainSummary(FilterMacroEditorModel.MacroDefinition macro) {
        List<String> names = new ArrayList<String>();
        if (macro != null) {
            List<FilterMacroEditorModel.Section> sections = macro.getSections();
            for (int i = 0; i < sections.size(); i++) {
                FilterMacroEditorModel.Section section = sections.get(i);
                for (int j = 0; j < section.entries.size(); j++) {
                    String label = section.entries.get(j).summaryLabel;
                    if (label == null || label.trim().isEmpty()) {
                        label = section.entries.get(j).label;
                    }
                    if (label != null && label.trim().length() > 0) {
                        names.add(label.trim());
                    }
                }
            }
        }
        if (names.isEmpty()) {
            return "none";
        }
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < names.size(); i++) {
            if (i > 0) {
                out.append(" -> ");
            }
            out.append(names.get(i));
        }
        return out.toString();
    }

    private static Double finiteDouble(String value) {
        try {
            double parsed = Double.parseDouble(value == null ? "" : value.trim());
            return Double.isNaN(parsed) || Double.isInfinite(parsed)
                    ? null
                    : Double.valueOf(parsed);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String reasonFor(Exception e) {
        if (e == null) {
            return "Could not load preset";
        }
        String message = e.getMessage();
        return message == null || message.trim().isEmpty()
                ? e.getClass().getSimpleName()
                : message.trim();
    }

    private static List<String> copyOptions(List<String> source) {
        if (source == null || source.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> out = new ArrayList<String>();
        for (int i = 0; i < source.size(); i++) {
            String value = source.get(i);
            if (value != null) {
                out.add(value);
            }
        }
        return Collections.unmodifiableList(out);
    }

    private static String safeName(String value) {
        return value == null ? "" : value.trim();
    }

    public static final class Result {
        private final List<PresetInfo> readablePresets;
        private final List<SkippedPreset> skippedPresets;

        Result(List<PresetInfo> readablePresets,
               List<SkippedPreset> skippedPresets) {
            this.readablePresets = Collections.unmodifiableList(
                    new ArrayList<PresetInfo>(readablePresets));
            this.skippedPresets = Collections.unmodifiableList(
                    new ArrayList<SkippedPreset>(skippedPresets));
        }

        public List<PresetInfo> readablePresets() {
            return readablePresets;
        }

        public List<SkippedPreset> skippedPresets() {
            return skippedPresets;
        }

        public PresetInfo readablePreset(String presetName) {
            String expected = safeName(presetName);
            for (int i = 0; i < readablePresets.size(); i++) {
                PresetInfo info = readablePresets.get(i);
                if (info.name().equalsIgnoreCase(expected)) {
                    return info;
                }
            }
            return null;
        }

        public List<String> readableNames() {
            List<String> out = new ArrayList<String>();
            for (int i = 0; i < readablePresets.size(); i++) {
                out.add(readablePresets.get(i).name());
            }
            return out;
        }

        public List<String> allNumericParamKeys() {
            LinkedHashSet<String> out = new LinkedHashSet<String>();
            for (int i = 0; i < readablePresets.size(); i++) {
                out.addAll(readablePresets.get(i).numericParamKeys());
            }
            return new ArrayList<String>(out);
        }

        public List<String> commonNumericParamKeys() {
            LinkedHashSet<String> common = new LinkedHashSet<String>();
            if (readablePresets.isEmpty()) {
                return new ArrayList<String>();
            }
            common.addAll(readablePresets.get(0).numericParamKeys());
            for (int i = 1; i < readablePresets.size(); i++) {
                common.retainAll(readablePresets.get(i).numericParamKeys());
            }
            return new ArrayList<String>(common);
        }

        public String defaultXParamKey() {
            List<String> common = commonNumericParamKeys();
            if (!common.isEmpty()) {
                return common.get(0);
            }
            List<String> all = allNumericParamKeys();
            for (int i = 0; i < all.size(); i++) {
                if ("sigma".equalsIgnoreCase(all.get(i))) {
                    return all.get(i);
                }
            }
            return all.isEmpty() ? "" : all.get(0);
        }
    }

    public static final class PresetInfo {
        private final String name;
        private final String macroContent;
        private final FilterMacroEditorModel.MacroDefinition macroDefinition;
        private final String chainSummary;
        private final LinkedHashMap<String, NumericParam> numericParams;

        PresetInfo(String name,
                   String macroContent,
                   FilterMacroEditorModel.MacroDefinition macroDefinition,
                   String chainSummary,
                   LinkedHashMap<String, NumericParam> numericParams) {
            this.name = safeName(name);
            this.macroContent = macroContent == null ? "" : macroContent;
            this.macroDefinition = macroDefinition;
            this.chainSummary = chainSummary == null ? "" : chainSummary;
            this.numericParams = new LinkedHashMap<String, NumericParam>(
                    numericParams == null
                            ? Collections.<String, NumericParam>emptyMap()
                            : numericParams);
        }

        public String name() {
            return name;
        }

        public String macroContent() {
            return macroContent;
        }

        public FilterMacroEditorModel.MacroDefinition macroDefinition() {
            return macroDefinition;
        }

        public String chainSummary() {
            return chainSummary;
        }

        public boolean hasNumericParam(String key) {
            return numericParams.containsKey(key);
        }

        public NumericParam numericParam(String key) {
            return numericParams.get(key);
        }

        public List<String> numericParamKeys() {
            return new ArrayList<String>(numericParams.keySet());
        }

        public Map<String, NumericParam> numericParams() {
            return Collections.unmodifiableMap(numericParams);
        }
    }

    public static final class NumericParam {
        private final String key;
        private final double baseValue;

        NumericParam(String key, double baseValue) {
            this.key = safeName(key);
            this.baseValue = baseValue;
        }

        public String key() {
            return key;
        }

        public double baseValue() {
            return baseValue;
        }
    }

    public static final class SkippedPreset {
        private final String name;
        private final String reason;

        SkippedPreset(String name, String reason) {
            this.name = safeName(name);
            this.reason = reason == null ? "" : reason.trim();
        }

        public String name() {
            return name;
        }

        public String reason() {
            return reason;
        }
    }
}
