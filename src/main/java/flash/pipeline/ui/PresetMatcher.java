package flash.pipeline.ui;

import flash.pipeline.image.NamedFilterLoader;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Matches imported filter macros against bundled presets.
 *
 * Exact and whitespace-normalized matches are safe to demote directly. A
 * structural match means the same ImageJ commands run in the same order, but
 * one or more command arguments differ; callers should preserve the imported
 * macro file because it carries those parameter overrides.
 */
public final class PresetMatcher {

    private static final Pattern RUN_PATTERN = Pattern.compile(
            "run\\s*\\(\\s*\"([^\"]+)\"(?:\\s*,\\s*\"([^\"]*)\")?\\s*\\)\\s*;?\\s*$");
    private static final Pattern ARG_TOKEN_PATTERN = Pattern.compile("([^\\s=]+)=([^\\s]+)");

    private PresetMatcher() {}

    public static Match match(String macroContent) {
        if (macroContent == null) return null;
        for (String presetName : NamedFilterLoader.FILTER_NAMES) {
            String bundled = NamedFilterLoader.loadFilterContent(presetName);
            if (bundled == null) continue;
            if (normalize(bundled).equals(normalize(macroContent))) {
                return new Match(presetName, false, Collections.<Integer, Map<String, String>>emptyMap());
            }

            StructuralComparison structural = compareStructure(bundled, macroContent);
            if (structural.matches) {
                return new Match(presetName, true, structural.overrides);
            }
        }
        return null;
    }

    public static String matchPresetName(String macroContent) {
        Match match = match(macroContent);
        return match == null ? null : match.presetName;
    }

    static String normalize(String text) {
        if (text == null) return "";
        String unified = text.replace("\r\n", "\n").replace('\r', '\n');
        String[] lines = unified.split("\n", -1);
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(line.trim());
        }
        return new String(sb.toString().trim().getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
    }

    private static StructuralComparison compareStructure(String bundled, String imported) {
        ParsedMacro preset = parseRunOnlyMacro(bundled);
        ParsedMacro actual = parseRunOnlyMacro(imported);
        if (!preset.runOnly || !actual.runOnly) return StructuralComparison.noMatch();
        if (preset.runs.size() != actual.runs.size()) return StructuralComparison.noMatch();
        if (preset.runs.isEmpty()) return StructuralComparison.noMatch();

        Map<Integer, Map<String, String>> overrides = new LinkedHashMap<Integer, Map<String, String>>();
        for (int i = 0; i < preset.runs.size(); i++) {
            RunLine p = preset.runs.get(i);
            RunLine a = actual.runs.get(i);
            if (!p.command.equals(a.command)) return StructuralComparison.noMatch();
            if (!p.args.equals(a.args)) {
                Map<String, String> changed = parameterOverrides(p.args, a.args);
                if (changed.isEmpty()) changed.put("_args", a.args);
                overrides.put(Integer.valueOf(i), changed);
            }
        }
        return new StructuralComparison(true, overrides);
    }

    private static ParsedMacro parseRunOnlyMacro(String macro) {
        ParsedMacro parsed = new ParsedMacro();
        if (macro == null) return parsed;
        String unified = macro.replace("\r\n", "\n").replace('\r', '\n');
        String[] lines = unified.split("\n");
        for (String line : lines) {
            String t = line == null ? "" : line.trim();
            if (t.isEmpty() || t.startsWith("//") || t.startsWith("/*") || t.startsWith("*")) continue;
            Matcher m = RUN_PATTERN.matcher(t);
            if (!m.matches()) {
                parsed.runOnly = false;
                return parsed;
            }
            parsed.runs.add(new RunLine(normalizeCommand(m.group(1)), m.group(2) == null ? "" : m.group(2).trim()));
        }
        return parsed;
    }

    private static String normalizeCommand(String command) {
        String c = command == null ? "" : command.trim();
        if (c.endsWith("...")) c = c.substring(0, c.length() - 3).trim();
        return c;
    }

    private static Map<String, String> parameterOverrides(String presetArgs, String actualArgs) {
        Map<String, String> preset = parseArgs(presetArgs);
        Map<String, String> actual = parseArgs(actualArgs);
        Map<String, String> changed = new LinkedHashMap<String, String>();
        for (Map.Entry<String, String> entry : actual.entrySet()) {
            String previous = preset.get(entry.getKey());
            if (previous == null || !previous.equals(entry.getValue())) {
                changed.put(entry.getKey(), entry.getValue());
            }
        }
        for (String key : preset.keySet()) {
            if (!actual.containsKey(key)) changed.put(key, "");
        }
        return changed;
    }

    private static Map<String, String> parseArgs(String args) {
        Map<String, String> parsed = new LinkedHashMap<String, String>();
        if (args == null || args.trim().isEmpty()) return parsed;
        Matcher m = ARG_TOKEN_PATTERN.matcher(args);
        while (m.find()) {
            parsed.put(m.group(1), m.group(2));
        }
        return parsed;
    }

    public static final class Match {
        public final String presetName;
        public final boolean structural;
        public final Map<Integer, Map<String, String>> parameterOverrides;

        private Match(String presetName, boolean structural, Map<Integer, Map<String, String>> parameterOverrides) {
            this.presetName = presetName;
            this.structural = structural;
            this.parameterOverrides = parameterOverrides;
        }

        public boolean hasParameterOverrides() {
            return parameterOverrides != null && !parameterOverrides.isEmpty();
        }
    }

    private static final class ParsedMacro {
        boolean runOnly = true;
        final List<RunLine> runs = new ArrayList<RunLine>();
    }

    private static final class RunLine {
        final String command;
        final String args;

        RunLine(String command, String args) {
            this.command = command == null ? "" : command;
            this.args = args == null ? "" : args;
        }
    }

    private static final class StructuralComparison {
        final boolean matches;
        final Map<Integer, Map<String, String>> overrides;

        StructuralComparison(boolean matches, Map<Integer, Map<String, String>> overrides) {
            this.matches = matches;
            this.overrides = overrides;
        }

        static StructuralComparison noMatch() {
            return new StructuralComparison(false, Collections.<Integer, Map<String, String>>emptyMap());
        }
    }
}
