package flash.pipeline.intelligence.identity;

import java.util.Locale;
import java.util.regex.Matcher;

/**
 * Applies a {@link NamingGrammar} to one seed string, producing a
 * {@link PartialIdentity}. User-defined matches are HIGH confidence (explicit
 * intent beats heuristics). Each matched field records provenance
 * {@code "your pattern (<field/axis>)"}.
 */
public final class GrammarInterpreter {

    private static final String PROV = "your pattern";

    public PartialIdentity apply(NamingGrammar grammar, String seed) {
        PartialIdentity p = new PartialIdentity();
        if (grammar == null) return p;
        String s = seed == null ? "" : seed;
        for (FieldRule rule : grammar.rules) {
            String value = evaluate(rule, s);
            if (value == null || value.trim().isEmpty()) continue;
            String prov = PROV + " (" + label(rule) + ")";
            switch (rule.type) {
                case ANIMAL:     p.animal(value, Confidence.HIGH, prov); break;
                case HEMISPHERE: p.hemisphere(value, Confidence.HIGH, prov); break;
                case REGION:     p.region(value, Confidence.HIGH, prov); break;
                case CONDITION:  p.condition(rule.axisLabel, value, Confidence.HIGH, prov); break;
                default: break;
            }
        }
        return p;
    }

    private static String evaluate(FieldRule rule, String s) {
        if (rule.isCapture()) {
            Matcher m = rule.capture.matcher(s);
            if (m.find()) {
                return m.groupCount() >= 1 && m.group(1) != null ? m.group(1) : m.group();
            }
            return null;
        }
        for (ValuePattern vp : rule.values) {
            if (vp.matches(s)) return vp.canonical;
        }
        return null;
    }

    private static String label(FieldRule rule) {
        if (rule.type == FieldRule.Type.CONDITION && !rule.axisLabel.isEmpty()) {
            return rule.axisLabel;
        }
        return rule.type.name().toLowerCase(Locale.ROOT);
    }
}
