package flash.pipeline.segmentation;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class MorphPredicate {
    public enum Operator {
        GE(">="),
        LE("<="),
        GT(">"),
        LT("<");

        private final String symbol;

        Operator(String symbol) {
            this.symbol = symbol;
        }

        public String symbol() {
            return symbol;
        }
    }

    private static final Set<String> SUPPORTED_FEATURES = new HashSet<String>(Arrays.asList(
            "volume",
            "surface_area",
            "sphericity",
            "elongation",
            "compactness",
            "mean_intensity",
            "max_intensity",
            "feret_diameter_max"));

    public final String featureName;
    public final Operator op;
    public final double value;

    public MorphPredicate(String featureName, Operator op, double value) {
        if (featureName == null || featureName.trim().isEmpty()) {
            throw new IllegalArgumentException("Morph predicate feature must not be blank.");
        }
        if (op == null) {
            throw new IllegalArgumentException("Morph predicate operator must not be null.");
        }
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException("Morph predicate value must be finite.");
        }
        this.featureName = featureName.trim();
        this.op = op;
        this.value = value;
    }

    public boolean matches(double observed) {
        if (!SUPPORTED_FEATURES.contains(featureName)) return true;
        if (!Double.isFinite(observed)) return false;
        if (op == Operator.GE) return observed >= value;
        if (op == Operator.LE) return observed <= value;
        if (op == Operator.GT) return observed > value;
        if (op == Operator.LT) return observed < value;
        return false;
    }

    public String format() {
        return featureName + op.symbol() + Double.toString(value);
    }

    public static MorphPredicate parse(String text) {
        if (text == null) {
            throw new IllegalArgumentException("Morph predicate is null.");
        }
        String trimmed = text.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("Morph predicate is blank.");
        }
        String[] operators = {">=", "<=", ">", "<"};
        Operator[] values = {Operator.GE, Operator.LE, Operator.GT, Operator.LT};
        for (int i = 0; i < operators.length; i++) {
            int at = trimmed.indexOf(operators[i]);
            if (at > 0) {
                String feature = trimmed.substring(0, at).trim();
                String rawValue = trimmed.substring(at + operators[i].length()).trim();
                try {
                    return new MorphPredicate(feature, values[i], Double.parseDouble(rawValue));
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("Invalid morph predicate value: " + text, e);
                }
            }
        }
        throw new IllegalArgumentException("Invalid morph predicate: " + text);
    }

    static List<MorphPredicate> parseList(String decoded) {
        List<MorphPredicate> predicates = new ArrayList<MorphPredicate>();
        if (decoded == null || decoded.trim().isEmpty()) return predicates;
        String[] parts = decoded.split(",", -1);
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i] == null ? "" : parts[i].trim();
            if (!part.isEmpty()) {
                predicates.add(parse(part));
            }
        }
        return predicates;
    }

    static String formatList(List<MorphPredicate> predicates) {
        if (predicates == null || predicates.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < predicates.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(predicates.get(i).format());
        }
        return sb.toString();
    }
}
