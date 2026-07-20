package flash.pipeline.intelligence.identity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

/**
 * One field of a {@link NamingGrammar}. Two modes:
 * <ul>
 *   <li><b>CAPTURE</b> — a regex whose match (group 1 if present, else the whole
 *       match) becomes the value. Typically the animal: {@code (?<=_)M\d+}.</li>
 *   <li><b>ALIAS</b> — an ordered list of {@link ValuePattern}: the first whose
 *       pattern matches sets its canonical value. This is how "LH = _1,
 *       RH = _2" and multi-value conditions are expressed.</li>
 * </ul>
 * {@link Type#CONDITION} rules carry an {@link #axisLabel} so each becomes an
 * independent condition axis (the multi-condition model).
 */
public final class FieldRule {

    public enum Type { ANIMAL, HEMISPHERE, REGION, CONDITION }

    public final Type type;
    public final String axisLabel;            // for CONDITION; "" otherwise
    public final Pattern capture;             // CAPTURE mode; null in ALIAS mode
    public final List<ValuePattern> values;   // ALIAS mode; empty in CAPTURE mode

    private FieldRule(Type type, String axisLabel, Pattern capture, List<ValuePattern> values) {
        if (type == null) throw new IllegalArgumentException("Naming rule type is required.");
        this.type = type;
        this.axisLabel = axisLabel == null ? "" : axisLabel;
        if (this.axisLabel.length() > ValuePattern.MAX_CANONICAL_CHARACTERS) {
            throw new ValuePattern.UnsafePatternException(this.axisLabel,
                    "condition axis labels are limited to "
                            + ValuePattern.MAX_CANONICAL_CHARACTERS + " characters");
        }
        this.capture = capture;
        this.values = values == null ? Collections.<ValuePattern>emptyList()
                : Collections.unmodifiableList(new ArrayList<ValuePattern>(values));
    }

    public static FieldRule capture(Type type, String axisLabel, String regex) {
        return new FieldRule(type, axisLabel, ValuePattern.compileSafe(regex), null);
    }

    public static FieldRule alias(Type type, String axisLabel, List<ValuePattern> values) {
        if (values == null || values.isEmpty()) {
            throw new ValuePattern.UnsafePatternException("",
                    "alias rules must contain at least one canonical value");
        }
        return new FieldRule(type, axisLabel, null, values);
    }

    public boolean isCapture() {
        return capture != null;
    }
}
