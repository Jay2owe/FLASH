package flash.pipeline.intelligence.identity;

import flash.pipeline.naming.ConditionAxis;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Applies one validated {@link NamingGrammar} to a tolerant filename seed.
 * Ordered aliases retain their documented precedence when they match separate
 * tokens (for example a composite label containing two condition words). When
 * different canonicals claim the same or overlapping source span, or two
 * field rules assign different values to one target, matching fails with an
 * actionable ambiguity instead of selecting accidentally.
 */
public final class GrammarInterpreter {

    public static final int MAX_SEED_CHARACTERS = 4096;
    public static final int MAX_MATCHES_PER_PATTERN = 256;
    public static final int MAX_MATCHES_PER_RULE = 512;
    private static final String PROV = "your pattern";

    /** A seed/rule combination has more than one defensible identity. */
    public static final class AmbiguousMatchException extends IllegalArgumentException {
        AmbiguousMatchException(String message) {
            super(message);
        }
    }

    public PartialIdentity apply(NamingGrammar grammar, String seed) {
        PartialIdentity out = new PartialIdentity();
        if (grammar == null) return out;
        NamingGrammarCodec.validate(grammar);
        String text = seed == null ? "" : seed;
        if (text.length() > MAX_SEED_CHARACTERS) {
            throw new ValuePattern.UnsafePatternException("",
                    "filename/series seeds are limited to " + MAX_SEED_CHARACTERS
                            + " characters before grammar matching");
        }

        Map<String, Assignment> assignments = new LinkedHashMap<String, Assignment>();
        for (FieldRule rule : grammar.rules) {
            String value = evaluate(rule, text);
            if (value == null || value.trim().isEmpty()) continue;
            String target = targetKey(rule);
            Assignment previous = assignments.get(target);
            if (previous != null && !previous.value.equals(value)) {
                throw new AmbiguousMatchException("Ambiguous naming grammar for " + label(rule)
                        + ": both '" + previous.value + "' and '" + value + "' match this seed.");
            }
            if (previous == null) assignments.put(target, new Assignment(rule, value));
        }

        for (Assignment assignment : assignments.values()) {
            FieldRule rule = assignment.rule;
            String value = assignment.value;
            String provenance = PROV + " (" + label(rule) + ")";
            switch (rule.type) {
                case ANIMAL:
                    out.animal(value, Confidence.HIGH, provenance);
                    break;
                case HEMISPHERE:
                    out.hemisphere(value, Confidence.HIGH, provenance);
                    break;
                case REGION:
                    out.region(value, Confidence.HIGH, provenance);
                    break;
                case CONDITION:
                    String axis = rule.axisLabel == null || rule.axisLabel.trim().isEmpty()
                            ? "Condition" : rule.axisLabel;
                    out.condition(axis, value, Confidence.HIGH, provenance);
                    break;
                default:
                    break;
            }
        }
        return out;
    }

    private static String evaluate(FieldRule rule, String text) {
        return rule.isCapture() ? evaluateCapture(rule, text) : evaluateAlias(rule, text);
    }

    private static String evaluateCapture(FieldRule rule, String text) {
        Matcher matcher = rule.capture.matcher(text);
        String value = null;
        int matches = 0;
        while (matcher.find()) {
            if (++matches > MAX_MATCHES_PER_PATTERN) {
                throw new ValuePattern.UnsafePatternException(rule.capture.pattern(),
                        "one pattern produced more than " + MAX_MATCHES_PER_PATTERN + " matches");
            }
            String found = matcher.groupCount() >= 1 && matcher.group(1) != null
                    ? matcher.group(1) : matcher.group();
            if (found == null || found.trim().isEmpty()) continue;
            if (value == null) value = found;
            else if (!value.equals(found)) {
                throw new AmbiguousMatchException("Ambiguous naming grammar for " + label(rule)
                        + ": capture pattern finds both '" + value + "' and '" + found + "'.");
            }
        }
        return value;
    }

    private static String evaluateAlias(FieldRule rule, String text) {
        List<AliasMatch> matches = new ArrayList<AliasMatch>();
        String firstCanonical = null;
        for (ValuePattern value : rule.values) {
            boolean valueMatched = false;
            for (Pattern pattern : value.patterns) {
                Matcher matcher = pattern.matcher(text);
                int patternMatches = 0;
                while (matcher.find()) {
                    if (++patternMatches > MAX_MATCHES_PER_PATTERN) {
                        throw new ValuePattern.UnsafePatternException(pattern.pattern(),
                                "one pattern produced more than " + MAX_MATCHES_PER_PATTERN + " matches");
                    }
                    matches.add(new AliasMatch(value.canonical, matcher.start(), matcher.end()));
                    if (matches.size() > MAX_MATCHES_PER_RULE) {
                        throw new ValuePattern.UnsafePatternException(pattern.pattern(),
                                "one rule produced more than " + MAX_MATCHES_PER_RULE + " matches");
                    }
                    valueMatched = true;
                }
            }
            // Deliberate compatibility policy: ordered aliases retain precedence
            // for separate tokens; only competing claims over the same source
            // characters are inherently ambiguous.
            if (valueMatched && firstCanonical == null) firstCanonical = value.canonical;
        }
        for (int i = 0; i < matches.size(); i++) {
            AliasMatch left = matches.get(i);
            for (int j = i + 1; j < matches.size(); j++) {
                AliasMatch right = matches.get(j);
                if (!left.canonical.equals(right.canonical) && left.overlaps(right)) {
                    throw new AmbiguousMatchException("Ambiguous naming grammar for " + label(rule)
                            + ": overlapping patterns map the same text to both '"
                            + left.canonical + "' and '" + right.canonical + "'.");
                }
            }
        }
        return firstCanonical;
    }

    private static String targetKey(FieldRule rule) {
        if (rule.type != FieldRule.Type.CONDITION) return rule.type.name();
        String axis = rule.axisLabel == null || rule.axisLabel.trim().isEmpty()
                ? "Condition" : rule.axisLabel;
        return "CONDITION:" + ConditionAxis.normaliseId(axis);
    }

    private static String label(FieldRule rule) {
        if (rule.type == FieldRule.Type.CONDITION
                && rule.axisLabel != null && !rule.axisLabel.trim().isEmpty()) {
            return rule.axisLabel.trim();
        }
        return rule.type.name().toLowerCase(Locale.ROOT);
    }

    private static final class Assignment {
        final FieldRule rule;
        final String value;

        Assignment(FieldRule rule, String value) {
            this.rule = rule;
            this.value = value;
        }
    }

    private static final class AliasMatch {
        final String canonical;
        final int start;
        final int end;

        AliasMatch(String canonical, int start, int end) {
            this.canonical = canonical;
            this.start = start;
            this.end = end;
        }

        boolean overlaps(AliasMatch other) {
            if (start == end || other.start == other.end) {
                return start == other.start && end == other.end;
            }
            return start < other.end && other.start < end;
        }
    }
}
