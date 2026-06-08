package flash.pipeline.intelligence.identity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

/**
 * One row of an alias field rule: a canonical value plus the regex patterns that
 * map to it. E.g. {@code WeekFour <- ["Week[_ ]?Four", "W4", "4wk"]} or the
 * hemisphere alias {@code RH <- ["(?<![A-Za-z0-9])2(?![A-Za-z0-9])"]} (the
 * "RH = _2" rule). Bare literals like {@code "SCN"} are valid regexes.
 */
public final class ValuePattern {

    public final String canonical;
    public final List<Pattern> patterns;

    public ValuePattern(String canonical, List<String> regexes) {
        this.canonical = canonical == null ? "" : canonical;
        List<Pattern> compiled = new ArrayList<Pattern>();
        if (regexes != null) {
            for (String rx : regexes) {
                if (rx != null && !rx.isEmpty()) {
                    compiled.add(Pattern.compile(rx));
                }
            }
        }
        this.patterns = Collections.unmodifiableList(compiled);
    }

    /** True if any pattern is found in {@code s}. */
    public boolean matches(String s) {
        if (s == null) return false;
        for (Pattern p : patterns) {
            if (p.matcher(s).find()) return true;
        }
        return false;
    }
}
