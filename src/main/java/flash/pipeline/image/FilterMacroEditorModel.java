package flash.pipeline.image;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Introspects filter macros into editable sections and parameters, then renders
 * the updated macro back to source text after the user changes values.
 */
public final class FilterMacroEditorModel {

    private static final Pattern RUN_PATTERN = Pattern.compile(
            "^(\\s*run\\(\"([^\"]+)\"\\s*,\\s*\")([^\"]*)(\"\\s*\\)\\s*;?.*)$");
    private static final Pattern ASSIGNMENT_PATTERN = Pattern.compile(
            "^(\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*=\\s*)([^;]+?)(\\s*;.*)$");
    private static final Pattern NUMERIC_PATTERN = Pattern.compile("-?[0-9]*\\.?[0-9]+");
    private static final Pattern IDENTIFIER_PATTERN = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final Pattern BOOLEAN_PATTERN = Pattern.compile("(?i:true|false)");

    private FilterMacroEditorModel() {}

    public static MacroDefinition parse(String macroContent) {
        List<String> lines = splitLines(macroContent);
        List<String> lineEndings = splitLineEndings(macroContent);
        List<SectionBuilder> builders = new ArrayList<SectionBuilder>();
        SectionBuilder current = new SectionBuilder(null);

        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            String trimmed = line == null ? "" : line.trim();
            if (trimmed.isEmpty()) continue;

            String sectionTitle = parseSectionHeading(trimmed);
            if (sectionTitle != null) {
                if (!current.entries.isEmpty()) {
                    builders.add(current);
                }
                current = new SectionBuilder(sectionTitle);
                continue;
            }

            Entry entry = parseRunEntry(line, i);
            if (entry == null) entry = parseAssignmentEntry(line, i);
            if (entry != null) {
                current.entries.add(entry);
            }
        }

        if (!current.entries.isEmpty()) {
            builders.add(current);
        }

        List<Section> sections = new ArrayList<Section>();
        for (int i = 0; i < builders.size(); i++) {
            SectionBuilder builder = builders.get(i);
            if (builder.entries.isEmpty()) continue;
            String title = builder.title;
            if (title == null || title.trim().isEmpty()) {
                title = builders.size() == 1 ? "Filter Steps" : "Additional Steps";
            }
            sections.add(new Section(title, buildSummary(builder.entries), builder.entries));
        }
        return new MacroDefinition(lines, lineEndings, sections);
    }

    private static List<String> splitLines(String macroContent) {
        List<String> lines = new ArrayList<String>();
        if (macroContent == null || macroContent.isEmpty()) return lines;
        String[] raw = macroContent.split("\\r?\\n", -1);
        for (int i = 0; i < raw.length; i++) {
            lines.add(raw[i]);
        }
        return lines;
    }

    private static List<String> splitLineEndings(String macroContent) {
        List<String> lineEndings = new ArrayList<String>();
        if (macroContent == null || macroContent.isEmpty()) return lineEndings;
        int start = 0;
        while (true) {
            int newline = macroContent.indexOf('\n', start);
            if (newline < 0) {
                lineEndings.add("");
                break;
            }
            if (newline > 0 && macroContent.charAt(newline - 1) == '\r') {
                lineEndings.add("\r\n");
            } else {
                lineEndings.add("\n");
            }
            start = newline + 1;
            if (start == macroContent.length()) {
                lineEndings.add("");
                break;
            }
        }
        return lineEndings;
    }

    private static String parseSectionHeading(String trimmedLine) {
        if (!trimmedLine.startsWith("//")) return null;
        String body = trimmedLine.substring(2).trim();
        if (body.isEmpty()) return null;

        boolean looksLikeSection = trimmedLine.contains("===")
                || trimmedLine.contains("---")
                || body.startsWith("Step ")
                || body.equals(body.toUpperCase(Locale.ROOT));
        if (!looksLikeSection) return null;

        body = body.replaceAll("^[=\\-\\s]+", "").replaceAll("[=\\-\\s]+$", "").trim();
        if (body.isEmpty()) return null;
        body = body.replaceAll("\\s*\\([^)]*\\)\\s*$", "").trim();
        int plusIndex = body.indexOf(" + ");
        if (plusIndex > 0) {
            body = body.substring(0, plusIndex).trim();
        }
        return body.isEmpty() ? null : body;
    }

    private static Entry parseRunEntry(String line, int lineIndex) {
        Matcher matcher = RUN_PATTERN.matcher(line);
        if (!matcher.matches()) return null;

        String command = normalizeCommandName(matcher.group(2));
        String args = matcher.group(3);
        List<RunToken> tokens = new ArrayList<RunToken>();
        List<Parameter> parameters = new ArrayList<Parameter>();

        String trimmedArgs = args == null ? "" : args.trim();
        if (!trimmedArgs.isEmpty()) {
            String[] parts = trimmedArgs.split("\\s+");
            for (int i = 0; i < parts.length; i++) {
                String token = parts[i];
                int equalsIndex = token.indexOf('=');
                if (equalsIndex <= 0 || equalsIndex == token.length() - 1) {
                    tokens.add(RunToken.literal(token));
                    continue;
                }
                String key = token.substring(0, equalsIndex).trim();
                String value = token.substring(equalsIndex + 1).trim();
                if (key.isEmpty() || value.isEmpty()) {
                    tokens.add(RunToken.literal(token));
                    continue;
                }

                Parameter parameter = new Parameter(key, value, value, "", "");
                parameters.add(parameter);
                tokens.add(RunToken.parameter(key, parameter));
            }
        }

        if (parameters.isEmpty()) return null;

        String label = displayLabelForCommand(command, parameters);
        String summary = summaryLabelForCommand(command, parameters);
        return Entry.forRun(lineIndex, label, summary, matcher.group(1), matcher.group(4), tokens, parameters);
    }

    private static Entry parseAssignmentEntry(String line, int lineIndex) {
        Matcher matcher = ASSIGNMENT_PATTERN.matcher(line);
        if (!matcher.matches()) return null;

        LiteralValue literal = parseLiteralValue(matcher.group(3));
        if (literal == null) return null;

        String variableName = matcher.group(2);
        Parameter parameter = new Parameter(variableName, literal.displayValue,
                literal.displayValue, literal.valuePrefix, literal.valueSuffix);
        List<Parameter> parameters = new ArrayList<Parameter>();
        parameters.add(parameter);
        return Entry.forAssignment(lineIndex, humanizeIdentifier(variableName),
                humanizeIdentifier(variableName), matcher.group(1), matcher.group(4), parameter);
    }

    private static LiteralValue parseLiteralValue(String rawValue) {
        if (rawValue == null) return null;
        String trimmed = rawValue.trim();
        if (trimmed.isEmpty()) return null;
        if (NUMERIC_PATTERN.matcher(trimmed).matches()
                || BOOLEAN_PATTERN.matcher(trimmed).matches()
                || IDENTIFIER_PATTERN.matcher(trimmed).matches()) {
            return new LiteralValue(trimmed, "", "");
        }
        if ((trimmed.startsWith("\"") && trimmed.endsWith("\""))
                || (trimmed.startsWith("'") && trimmed.endsWith("'"))) {
            String quote = trimmed.substring(0, 1);
            return new LiteralValue(trimmed.substring(1, trimmed.length() - 1), quote, quote);
        }
        return null;
    }

    private static String buildSummary(List<Entry> entries) {
        List<String> labels = new ArrayList<String>();
        for (int i = 0; i < entries.size(); i++) {
            String summary = entries.get(i).summaryLabel;
            if (summary != null && !summary.trim().isEmpty()) {
                labels.add(summary);
            }
        }
        return join(labels, ", ");
    }

    private static String normalizeCommandName(String command) {
        if (command == null) return "";
        String normalized = command.trim();
        while (normalized.endsWith(".")) {
            normalized = normalized.substring(0, normalized.length() - 1).trim();
        }
        return normalized;
    }

    private static String displayLabelForCommand(String command, List<Parameter> parameters) {
        if ("Auto Local Threshold".equalsIgnoreCase(command)) {
            Parameter method = findParameter(parameters, "method");
            if (method != null && method.getValue() != null && !method.getValue().trim().isEmpty()) {
                return "Auto Local Threshold (" + method.getValue().trim() + ")";
            }
        }
        return command;
    }

    private static String summaryLabelForCommand(String command, List<Parameter> parameters) {
        if ("Auto Local Threshold".equalsIgnoreCase(command)) {
            Parameter method = findParameter(parameters, "method");
            if (method != null && method.getValue() != null && !method.getValue().trim().isEmpty()) {
                return method.getValue().trim();
            }
        }
        return command;
    }

    private static Parameter findParameter(List<Parameter> parameters, String key) {
        if (parameters == null || key == null) return null;
        for (int i = 0; i < parameters.size(); i++) {
            Parameter parameter = parameters.get(i);
            if (key.equalsIgnoreCase(parameter.key)) return parameter;
        }
        return null;
    }

    private static String humanizeIdentifier(String token) {
        if (token == null || token.trim().isEmpty()) return "";
        String[] parts = token.trim().split("_+");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i];
            if (part.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            if (part.length() <= 2 && part.equals(part.toLowerCase(Locale.ROOT))) {
                sb.append(part.toUpperCase(Locale.ROOT));
            } else {
                sb.append(Character.toUpperCase(part.charAt(0)));
                if (part.length() > 1) {
                    sb.append(part.substring(1));
                }
            }
        }
        return sb.toString();
    }

    private static String join(List<String> items, String delimiter) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) sb.append(delimiter);
            sb.append(items.get(i));
        }
        return sb.toString();
    }

    public static final class MacroDefinition {
        private final List<String> originalLines;
        private final List<String> originalLineEndings;
        private final List<Section> mutableSections;
        private final List<Section> sections;
        private boolean mutated;

        MacroDefinition(List<String> originalLines, List<String> originalLineEndings, List<Section> sections) {
            this.originalLines = new ArrayList<String>(originalLines);
            this.originalLineEndings = new ArrayList<String>(originalLineEndings);
            this.mutableSections = new ArrayList<Section>(sections);
            this.sections = Collections.unmodifiableList(this.mutableSections);
            this.mutated = false;
        }

        public List<Section> getSections() {
            return sections;
        }

        public boolean hasEditableParameters() {
            return !sections.isEmpty();
        }

        public int editableParameterCount() {
            int count = 0;
            for (int i = 0; i < sections.size(); i++) {
                List<Entry> entries = sections.get(i).entries;
                for (int j = 0; j < entries.size(); j++) {
                    count += entries.get(j).parameters.size();
                }
            }
            return count;
        }

        public Section addSection(String title) {
            Section section = new Section(title, "", new ArrayList<Entry>());
            mutableSections.add(section);
            markMutated();
            return section;
        }

        public Entry addRunEntry(Section target, String command, List<Parameter> params) {
            Section section = requireSection(target);
            String macroCommand = command == null ? "" : command.trim();
            String normalizedCommand = normalizeCommandName(macroCommand);
            List<Parameter> safeParams = params == null
                    ? Collections.<Parameter>emptyList()
                    : params;
            List<RunToken> tokens = new ArrayList<RunToken>();
            List<Parameter> entryParams = new ArrayList<Parameter>();
            for (int i = 0; i < safeParams.size(); i++) {
                Parameter parameter = safeParams.get(i);
                if (parameter == null) continue;
                entryParams.add(parameter);
                tokens.add(RunToken.parameter(parameter.key, parameter));
            }
            if (shouldAppendStackLiteral(macroCommand, tokens)) {
                tokens.add(RunToken.literal("stack"));
            }
            Entry entry = Entry.forRun(section.mutableEntries.size(),
                    displayLabelForCommand(normalizedCommand, entryParams),
                    summaryLabelForCommand(normalizedCommand, entryParams),
                    "run(\"" + macroCommand + "\", \"",
                    "\");",
                    tokens,
                    entryParams);
            section.mutableEntries.add(entry);
            markMutated();
            return entry;
        }

        public Entry addAssignmentEntry(Section target, String variable, String value) {
            Section section = requireSection(target);
            String variableName = variable == null ? "" : variable.trim();
            if (!IDENTIFIER_PATTERN.matcher(variableName).matches()) {
                throw new IllegalArgumentException("Assignment variable must be a valid macro identifier.");
            }
            LiteralValue literal = parseLiteralValue(value);
            if (literal == null) {
                literal = new LiteralValue(value == null ? "" : value, "", "");
            }
            Parameter parameter = new Parameter(variableName, literal.displayValue,
                    literal.displayValue, literal.valuePrefix, literal.valueSuffix);
            Entry entry = Entry.forAssignment(section.mutableEntries.size(),
                    humanizeIdentifier(variableName),
                    humanizeIdentifier(variableName),
                    variableName + " = ",
                    ";",
                    parameter);
            section.mutableEntries.add(entry);
            markMutated();
            return entry;
        }

        public void removeEntry(Entry entry) {
            Section owner = findOwner(entry);
            if (owner == null) {
                throw new IllegalArgumentException("Entry does not belong to this macro definition.");
            }
            owner.mutableEntries.remove(entry);
            markMutated();
        }

        public void moveEntry(Entry entry, int newIndex) {
            Section owner = findOwner(entry);
            if (owner == null) {
                throw new IllegalArgumentException("Entry does not belong to this macro definition.");
            }
            int oldIndex = owner.mutableEntries.indexOf(entry);
            owner.mutableEntries.remove(oldIndex);
            owner.mutableEntries.add(newIndex, entry);
            markMutated();
        }

        public void moveEntryToSection(Entry entry, Section target, int newIndex) {
            Section owner = findOwner(entry);
            if (owner == null) {
                throw new IllegalArgumentException("Entry does not belong to this macro definition.");
            }
            Section targetSection = requireSection(target);
            if (owner == targetSection) {
                moveEntry(entry, newIndex);
                return;
            }
            owner.mutableEntries.remove(entry);
            targetSection.mutableEntries.add(newIndex, entry);
            markMutated();
        }

        public String render() {
            if (mutated) return renderFromSections();

            List<String> updated = new ArrayList<String>(originalLines);
            for (int i = 0; i < sections.size(); i++) {
                List<Entry> entries = sections.get(i).entries;
                for (int j = 0; j < entries.size(); j++) {
                    Entry entry = entries.get(j);
                    updated.set(entry.lineIndex, entry.renderLine());
                }
            }
            return renderOriginalLines(updated);
        }

        private String renderOriginalLines(List<String> updated) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < updated.size(); i++) {
                sb.append(updated.get(i));
                if (i < originalLineEndings.size()) {
                    sb.append(originalLineEndings.get(i));
                } else if (i < updated.size() - 1) {
                    sb.append('\n');
                }
            }
            return sb.toString();
        }

        private String renderFromSections() {
            StringBuilder sb = new StringBuilder();
            boolean wroteSection = false;
            for (int i = 0; i < sections.size(); i++) {
                Section section = sections.get(i);
                if (section.mutableEntries.isEmpty()) continue;
                if (wroteSection) sb.append('\n');
                if (section.title != null && !section.title.trim().isEmpty()) {
                    sb.append("// ===== ").append(section.title).append(" =====\n");
                }
                for (int j = 0; j < section.mutableEntries.size(); j++) {
                    sb.append(section.mutableEntries.get(j).renderLine()).append('\n');
                }
                if (sb.length() > 0 && sb.charAt(sb.length() - 1) == '\n') {
                    sb.setLength(sb.length() - 1);
                }
                wroteSection = true;
            }
            return sb.toString();
        }

        private Section requireSection(Section target) {
            if (target == null || !mutableSections.contains(target)) {
                throw new IllegalArgumentException("Section does not belong to this macro definition.");
            }
            return target;
        }

        private Section findOwner(Entry entry) {
            if (entry == null) return null;
            for (int i = 0; i < mutableSections.size(); i++) {
                Section section = mutableSections.get(i);
                if (section.mutableEntries.contains(entry)) return section;
            }
            return null;
        }

        private void markMutated() {
            mutated = true;
            refreshSectionSummariesAndPositions();
        }

        private void refreshSectionSummariesAndPositions() {
            for (int i = 0; i < mutableSections.size(); i++) {
                Section section = mutableSections.get(i);
                section.summary = buildSummary(section.mutableEntries);
                for (int j = 0; j < section.mutableEntries.size(); j++) {
                    section.mutableEntries.get(j).lineIndex = j;
                }
            }
        }

        private static boolean shouldAppendStackLiteral(String command, List<RunToken> tokens) {
            if (tokens == null || tokens.isEmpty()) return false;
            for (int i = 0; i < tokens.size(); i++) {
                RunToken token = tokens.get(i);
                if (token != null && token.parameter == null && "stack".equalsIgnoreCase(token.literal)) {
                    return false;
                }
            }
            String normalized = normalizeCommandName(command);
            return normalized.length() > 0 && normalized.toLowerCase(Locale.ROOT).indexOf("3d") < 0;
        }

        public String summarizeValues(int maxParameters) {
            List<String> pairs = new ArrayList<String>();
            for (int i = 0; i < sections.size(); i++) {
                List<Entry> entries = sections.get(i).entries;
                for (int j = 0; j < entries.size(); j++) {
                    List<Parameter> parameters = entries.get(j).parameters;
                    for (int k = 0; k < parameters.size(); k++) {
                        Parameter parameter = parameters.get(k);
                        if (maxParameters > 0 && pairs.size() >= maxParameters) {
                            int remaining = editableParameterCount() - pairs.size();
                            if (remaining > 0) {
                                pairs.add("+" + remaining + " more");
                            }
                            return join(pairs, "\n");
                        }
                        pairs.add(parameter.key + "=" + parameter.getValue());
                    }
                }
            }
            return join(pairs, "\n");
        }
    }

    public static final class Section {
        public final String title;
        public String summary;
        public final List<Entry> entries;
        private final List<Entry> mutableEntries;

        Section(String title, String summary, List<Entry> entries) {
            this.title = title;
            this.summary = summary;
            this.mutableEntries = new ArrayList<Entry>(entries);
            this.entries = Collections.unmodifiableList(this.mutableEntries);
        }

        public String headerText() {
            if (summary == null || summary.trim().isEmpty()) return title;
            return title + ": " + summary;
        }
    }

    public static final class Entry {
        public int lineIndex;
        public final String label;
        public final String summaryLabel;
        public final List<Parameter> parameters;

        private final String prefix;
        private final String suffix;
        private final List<RunToken> runTokens;
        private final Parameter assignmentParameter;

        private Entry(int lineIndex, String label, String summaryLabel,
                      String prefix, String suffix,
                      List<RunToken> runTokens,
                      List<Parameter> parameters,
                      Parameter assignmentParameter) {
            this.lineIndex = lineIndex;
            this.label = label;
            this.summaryLabel = summaryLabel;
            this.prefix = prefix;
            this.suffix = suffix;
            this.runTokens = runTokens == null
                    ? Collections.<RunToken>emptyList()
                    : Collections.unmodifiableList(new ArrayList<RunToken>(runTokens));
            this.parameters = Collections.unmodifiableList(new ArrayList<Parameter>(parameters));
            this.assignmentParameter = assignmentParameter;
        }

        static Entry forRun(int lineIndex, String label, String summaryLabel,
                            String prefix, String suffix,
                            List<RunToken> runTokens,
                            List<Parameter> parameters) {
            return new Entry(lineIndex, label, summaryLabel, prefix, suffix, runTokens, parameters, null);
        }

        static Entry forAssignment(int lineIndex, String label, String summaryLabel,
                                   String prefix, String suffix,
                                   Parameter parameter) {
            List<Parameter> parameters = new ArrayList<Parameter>();
            parameters.add(parameter);
            return new Entry(lineIndex, label, summaryLabel, prefix, suffix,
                    new ArrayList<RunToken>(), parameters, parameter);
        }

        String renderLine() {
            if (assignmentParameter != null) {
                return prefix + assignmentParameter.renderValue() + suffix;
            }
            List<String> renderedTokens = new ArrayList<String>();
            for (int i = 0; i < runTokens.size(); i++) {
                renderedTokens.add(runTokens.get(i).render());
            }
            return prefix + join(renderedTokens, " ") + suffix;
        }
    }

    public static final class Parameter {
        public final String key;
        public final String defaultValue;

        private final String valuePrefix;
        private final String valueSuffix;
        private String value;

        public Parameter(String key, String defaultValue, String value, String valuePrefix, String valueSuffix) {
            this.key = key;
            this.defaultValue = defaultValue;
            this.value = value;
            this.valuePrefix = valuePrefix;
            this.valueSuffix = valueSuffix;
        }

        public String getValue() {
            return value;
        }

        public void setValue(String value) {
            this.value = value == null ? "" : value;
        }

        String renderValue() {
            return valuePrefix + value + valueSuffix;
        }
    }

    private static final class SectionBuilder {
        final String title;
        final List<Entry> entries = new ArrayList<Entry>();

        SectionBuilder(String title) {
            this.title = title;
        }
    }

    private static final class LiteralValue {
        final String displayValue;
        final String valuePrefix;
        final String valueSuffix;

        LiteralValue(String displayValue, String valuePrefix, String valueSuffix) {
            this.displayValue = displayValue;
            this.valuePrefix = valuePrefix;
            this.valueSuffix = valueSuffix;
        }
    }

    private static final class RunToken {
        final String literal;
        final String key;
        final Parameter parameter;

        private RunToken(String literal, String key, Parameter parameter) {
            this.literal = literal;
            this.key = key;
            this.parameter = parameter;
        }

        static RunToken literal(String literal) {
            return new RunToken(literal, null, null);
        }

        static RunToken parameter(String key, Parameter parameter) {
            return new RunToken(null, key, parameter);
        }

        String render() {
            if (parameter == null) return literal;
            return key + "=" + parameter.getValue();
        }
    }
}
