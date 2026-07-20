package flash.pipeline.intelligence.identity;

import flash.pipeline.intelligence.MiniJson;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Versioned, deterministic JSON codec for reusable naming grammars. */
public final class NamingGrammarCodec {

    public static final int SCHEMA_VERSION = 1;
    public static final int MAX_RULES = 64;
    public static final int MAX_ALIAS_VALUES_PER_RULE = 64;
    public static final int MAX_TOTAL_PATTERNS = 256;
    public static final int MAX_NAME_CHARACTERS = 512;

    /** A grammar written by a newer FLASH version. */
    public static final class UnsupportedVersionException extends IOException {
        private final int version;

        UnsupportedVersionException(int version) {
            super("Naming grammar schemaVersion " + version
                    + " is newer than the supported version " + SCHEMA_VERSION + ".");
            this.version = version;
        }

        public int version() {
            return version;
        }
    }

    /** Structurally malformed JSON that is not an unsafe-regex diagnosis. */
    public static final class CorruptGrammarException extends IOException {
        CorruptGrammarException(String message) {
            super(message);
        }

        CorruptGrammarException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** A syntactically valid document containing a pattern outside the safe subset. */
    public static final class UnsafeGrammarException extends IOException {
        UnsafeGrammarException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private NamingGrammarCodec() {
    }

    public static String toJson(NamingGrammar grammar) {
        validate(grammar);
        Map<String, Object> root = new LinkedHashMap<String, Object>();
        root.put("schemaVersion", Integer.valueOf(SCHEMA_VERSION));
        root.put("name", grammar == null ? "" : grammar.name);
        List<Object> fields = new ArrayList<Object>();
        if (grammar != null) {
            for (FieldRule rule : grammar.rules) fields.add(ruleToMap(rule));
        }
        root.put("fields", fields);
        return MiniJson.write(root);
    }

    public static NamingGrammar fromJson(String json) throws IOException {
        Object parsed;
        try {
            parsed = MiniJson.parse(json);
        } catch (IOException e) {
            throw new CorruptGrammarException("Could not parse naming grammar JSON: "
                    + e.getMessage(), e);
        } catch (RuntimeException e) {
            throw new CorruptGrammarException("Could not parse naming grammar JSON.", e);
        }
        return fromParsed(parsed);
    }

    static NamingGrammar fromParsed(Object parsed) throws IOException {
        if (!(parsed instanceof Map)) {
            throw corrupt("Grammar JSON root must be an object.");
        }
        Map<?, ?> root = (Map<?, ?>) parsed;
        boolean legacy = !root.containsKey("schemaVersion");
        int version = legacy ? 0 : exactInteger(root.get("schemaVersion"), "schemaVersion");
        if (version > SCHEMA_VERSION) throw new UnsupportedVersionException(version);
        if (version < 0) throw corrupt("Grammar schemaVersion must not be negative.");
        requireOnlyKeys(root, legacy
                ? set("name", "fields")
                : set("schemaVersion", "name", "fields"), "grammar root");

        String name = requiredString(root, "name", "grammar root");
        if (name.length() > MAX_NAME_CHARACTERS) {
            throw unsafe("Grammar name exceeds " + MAX_NAME_CHARACTERS + " characters.", null);
        }
        Object fieldsObj = root.get("fields");
        if (!(fieldsObj instanceof List)) {
            throw corrupt("Grammar field 'fields' must be an array.");
        }
        List<?> encodedRules = (List<?>) fieldsObj;
        if (encodedRules.size() > MAX_RULES) {
            throw unsafe("Naming grammars are limited to " + MAX_RULES + " rules.", null);
        }
        List<FieldRule> rules = new ArrayList<FieldRule>();
        for (int i = 0; i < encodedRules.size(); i++) {
            Object encoded = encodedRules.get(i);
            if (!(encoded instanceof Map)) {
                throw corrupt("Grammar field 'fields[" + i + "]' must be an object.");
            }
            rules.add(mapToRule((Map<?, ?>) encoded, i));
        }
        NamingGrammar grammar = new NamingGrammar(name, rules);
        try {
            validate(grammar);
        } catch (ValuePattern.UnsafePatternException e) {
            throw unsafe(e.getMessage(), e);
        }
        return grammar;
    }

    /** Validate programmatically constructed grammars before matching or saving. */
    static void validate(NamingGrammar grammar) {
        if (grammar == null) {
            throw new ValuePattern.UnsafePatternException("", "grammar is missing");
        }
        if (grammar.name.length() > MAX_NAME_CHARACTERS) {
            throw new ValuePattern.UnsafePatternException(grammar.name,
                    "grammar names are limited to " + MAX_NAME_CHARACTERS + " characters");
        }
        if (grammar.name.trim().isEmpty()) {
            throw new ValuePattern.UnsafePatternException(grammar.name,
                    "grammar name must not be blank");
        }
        if (grammar.rules.size() > MAX_RULES) {
            throw new ValuePattern.UnsafePatternException("",
                    "naming grammars are limited to " + MAX_RULES + " rules");
        }
        int totalPatterns = 0;
        for (FieldRule rule : grammar.rules) {
            if (rule == null || rule.type == null) {
                throw new ValuePattern.UnsafePatternException("", "grammar contains a null rule");
            }
            if (rule.isCapture()) {
                ValuePattern.requireSafe(rule.capture);
                totalPatterns++;
            } else {
                if (rule.values.isEmpty() || rule.values.size() > MAX_ALIAS_VALUES_PER_RULE) {
                    throw new ValuePattern.UnsafePatternException("",
                            "alias rules must contain 1-" + MAX_ALIAS_VALUES_PER_RULE + " values");
                }
                for (ValuePattern value : rule.values) {
                    if (value == null || value.canonical.trim().isEmpty() || value.patterns.isEmpty()) {
                        throw new ValuePattern.UnsafePatternException("",
                                "alias values require a nonblank canonical value and at least one pattern");
                    }
                    for (Pattern pattern : value.patterns) {
                        ValuePattern.requireSafe(pattern);
                        totalPatterns++;
                    }
                }
            }
            if (totalPatterns > MAX_TOTAL_PATTERNS) {
                throw new ValuePattern.UnsafePatternException("",
                        "naming grammars are limited to " + MAX_TOTAL_PATTERNS + " total patterns");
            }
        }
    }

    private static Map<String, Object> ruleToMap(FieldRule rule) {
        Map<String, Object> fm = new LinkedHashMap<String, Object>();
        fm.put("type", rule.type.name());
        if (rule.type == FieldRule.Type.CONDITION) fm.put("axisLabel", rule.axisLabel);
        if (rule.isCapture()) {
            fm.put("mode", "capture");
            fm.put("pattern", rule.capture.pattern());
        } else {
            fm.put("mode", "alias");
            List<Object> values = new ArrayList<Object>();
            for (ValuePattern vp : rule.values) {
                Map<String, Object> vm = new LinkedHashMap<String, Object>();
                vm.put("canonical", vp.canonical);
                List<Object> matches = new ArrayList<Object>();
                for (Pattern p : vp.patterns) matches.add(p.pattern());
                vm.put("match", matches);
                values.add(vm);
            }
            fm.put("values", values);
        }
        return fm;
    }

    private static FieldRule mapToRule(Map<?, ?> fm, int index) throws IOException {
        String location = "fields[" + index + "]";
        String typeText = requiredString(fm, "type", location);
        FieldRule.Type type;
        try {
            type = FieldRule.Type.valueOf(typeText);
        } catch (IllegalArgumentException e) {
            throw corrupt("Unknown field rule type at " + location + ": " + typeText, e);
        }
        String axisLabel = optionalString(fm, "axisLabel", location);
        if (type != FieldRule.Type.CONDITION && fm.containsKey("axisLabel")) {
            throw corrupt(location + " may use 'axisLabel' only for CONDITION rules.");
        }
        String mode = requiredString(fm, "mode", location);
        try {
            if ("capture".equals(mode)) {
                requireOnlyKeys(fm, type == FieldRule.Type.CONDITION
                        ? set("type", "axisLabel", "mode", "pattern")
                        : set("type", "mode", "pattern"), location);
                return FieldRule.capture(type, axisLabel,
                        requiredString(fm, "pattern", location));
            }
            if (!"alias".equals(mode)) {
                throw corrupt("Unknown field rule mode at " + location + ": " + mode);
            }
            requireOnlyKeys(fm, type == FieldRule.Type.CONDITION
                    ? set("type", "axisLabel", "mode", "values")
                    : set("type", "mode", "values"), location);
            Object valsObj = fm.get("values");
            if (!(valsObj instanceof List)) {
                throw corrupt(location + " field 'values' must be an array.");
            }
            List<?> encodedValues = (List<?>) valsObj;
            if (encodedValues.isEmpty() || encodedValues.size() > MAX_ALIAS_VALUES_PER_RULE) {
                throw unsafe(location + " must contain 1-" + MAX_ALIAS_VALUES_PER_RULE
                        + " alias values.", null);
            }
            List<ValuePattern> values = new ArrayList<ValuePattern>();
            for (int v = 0; v < encodedValues.size(); v++) {
                Object encoded = encodedValues.get(v);
                if (!(encoded instanceof Map)) {
                    throw corrupt(location + ".values[" + v + "] must be an object.");
                }
                Map<?, ?> vm = (Map<?, ?>) encoded;
                String valueLocation = location + ".values[" + v + "]";
                requireOnlyKeys(vm, set("canonical", "match"), valueLocation);
                String canonical = requiredString(vm, "canonical", valueLocation);
                if (canonical.trim().isEmpty()) {
                    throw corrupt(valueLocation + " field 'canonical' must not be blank.");
                }
                Object msObj = vm.get("match");
                if (!(msObj instanceof List)) {
                    throw corrupt(valueLocation + " field 'match' must be an array.");
                }
                List<?> encodedMatches = (List<?>) msObj;
                if (encodedMatches.isEmpty()
                        || encodedMatches.size() > ValuePattern.MAX_PATTERNS_PER_VALUE) {
                    throw unsafe(valueLocation + " must contain 1-"
                            + ValuePattern.MAX_PATTERNS_PER_VALUE + " patterns.", null);
                }
                List<String> matches = new ArrayList<String>();
                for (int m = 0; m < encodedMatches.size(); m++) {
                    Object match = encodedMatches.get(m);
                    if (!(match instanceof String) || ((String) match).isEmpty()) {
                        throw corrupt(valueLocation + ".match[" + m
                                + "] must be a nonempty string.");
                    }
                    matches.add((String) match);
                }
                values.add(new ValuePattern(canonical, matches));
            }
            return FieldRule.alias(type, axisLabel, values);
        } catch (ValuePattern.UnsafePatternException e) {
            throw unsafe(e.getMessage(), e);
        }
    }

    private static String requiredString(Map<?, ?> map, String key, String location)
            throws CorruptGrammarException {
        Object value = map.get(key);
        if (!(value instanceof String)) {
            throw corrupt(location + " field '" + key + "' must be a string.");
        }
        return (String) value;
    }

    private static String optionalString(Map<?, ?> map, String key, String location)
            throws CorruptGrammarException {
        if (!map.containsKey(key)) return "";
        return requiredString(map, key, location);
    }

    private static int exactInteger(Object value, String field) throws CorruptGrammarException {
        if (!(value instanceof Number)) throw corrupt("Grammar field '" + field + "' must be an integer.");
        double asDouble = ((Number) value).doubleValue();
        int asInt = ((Number) value).intValue();
        if (!Double.isFinite(asDouble) || asDouble != (double) asInt) {
            throw corrupt("Grammar field '" + field + "' must be an integer.");
        }
        return asInt;
    }

    private static void requireOnlyKeys(Map<?, ?> map, Set<String> allowed, String location)
            throws CorruptGrammarException {
        for (Object key : map.keySet()) {
            if (!(key instanceof String) || !allowed.contains(key)) {
                throw corrupt("Unknown field '" + key + "' in " + location + ".");
            }
        }
        for (String required : allowed) {
            if (("axisLabel".equals(required)) || map.containsKey(required)) continue;
            throw corrupt(location + " is missing field '" + required + "'.");
        }
    }

    private static Set<String> set(String... values) {
        return new HashSet<String>(Arrays.asList(values));
    }

    private static CorruptGrammarException corrupt(String message) {
        return new CorruptGrammarException(message);
    }

    private static CorruptGrammarException corrupt(String message, Throwable cause) {
        return new CorruptGrammarException(message, cause);
    }

    private static UnsafeGrammarException unsafe(String message, Throwable cause) {
        return new UnsafeGrammarException(message, cause);
    }
}
