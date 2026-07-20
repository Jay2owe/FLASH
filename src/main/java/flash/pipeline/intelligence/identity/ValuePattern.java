package flash.pipeline.intelligence.identity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * One row of an alias field rule: a canonical value plus the safe regular
 * expressions that map to it.
 *
 * <p>The expressions deliberately use a constrained Java-regex subset. Java
 * regex matching cannot be stopped reliably by interrupting a worker, so an
 * expression is rejected before compilation when it contains backreferences,
 * quantified alternatives, nested quantifiers, repeated adjacent unbounded
 * tokens, or an unbounded wildcard/literal. Boundary look-arounds used by the
 * built-in hemisphere examples remain supported.</p>
 */
public final class ValuePattern {

    public static final int MAX_PATTERN_CHARACTERS = 512;
    public static final int MAX_PATTERNS_PER_VALUE = 32;
    public static final int MAX_CANONICAL_CHARACTERS = 512;
    public static final int MAX_GROUP_DEPTH = 16;

    /** Actionable validation failure for an expression outside the safe subset. */
    public static final class UnsafePatternException extends IllegalArgumentException {
        private final String expression;

        UnsafePatternException(String expression, String reason) {
            super("Unsafe naming pattern '" + abbreviate(expression) + "': " + reason);
            this.expression = expression == null ? "" : expression;
        }

        public String expression() {
            return expression;
        }
    }

    public final String canonical;
    public final List<Pattern> patterns;

    public ValuePattern(String canonical, List<String> regexes) {
        this.canonical = canonical == null ? "" : canonical;
        if (this.canonical.length() > MAX_CANONICAL_CHARACTERS) {
            throw new UnsafePatternException(this.canonical,
                    "canonical values are limited to " + MAX_CANONICAL_CHARACTERS + " characters");
        }
        if (regexes != null && regexes.size() > MAX_PATTERNS_PER_VALUE) {
            throw new UnsafePatternException("",
                    "each alias value is limited to " + MAX_PATTERNS_PER_VALUE + " patterns");
        }
        List<Pattern> compiled = new ArrayList<Pattern>();
        if (regexes != null) {
            for (String rx : regexes) {
                if (rx != null && !rx.isEmpty()) {
                    compiled.add(compileSafe(rx));
                }
            }
        }
        this.patterns = Collections.unmodifiableList(compiled);
    }

    /** Compile an expression only after it passes the constrained grammar. */
    static Pattern compileSafe(String regex) {
        validateRegex(regex);
        try {
            return Pattern.compile(regex);
        } catch (PatternSyntaxException e) {
            throw new UnsafePatternException(regex, "invalid Java regular expression: "
                    + e.getDescription());
        }
    }

    /** Re-check a compiled expression when validating a complete grammar. */
    static void requireSafe(Pattern pattern) {
        if (pattern == null) {
            throw new UnsafePatternException("", "pattern is missing");
        }
        validateRegex(pattern.pattern());
    }

    /** True if any pattern is found in {@code s}. */
    public boolean matches(String s) {
        if (s == null) return false;
        for (Pattern p : patterns) {
            if (p.matcher(s).find()) return true;
        }
        return false;
    }

    private static void validateRegex(String regex) {
        if (regex == null || regex.isEmpty()) {
            throw new UnsafePatternException(regex, "pattern must not be empty");
        }
        if (regex.length() > MAX_PATTERN_CHARACTERS) {
            throw new UnsafePatternException(regex,
                    "patterns are limited to " + MAX_PATTERN_CHARACTERS + " characters");
        }

        List<GroupState> groups = new ArrayList<GroupState>();
        groups.add(new GroupState(false));
        for (int i = 0; i < regex.length();) {
            char c = regex.charAt(i);
            GroupState current = groups.get(groups.size() - 1);

            if (c == '\\') {
                if (i + 1 >= regex.length()) {
                    throw new UnsafePatternException(regex, "trailing escape");
                }
                char escaped = regex.charAt(i + 1);
                if (escaped >= '1' && escaped <= '9' || escaped == 'k') {
                    throw new UnsafePatternException(regex, "backreferences are not allowed");
                }
                if (escaped == 'Q') {
                    int quoteEnd = regex.indexOf("\\E", i + 2);
                    if (quoteEnd < 0) quoteEnd = regex.length();
                    int atomEnd = quoteEnd < regex.length() ? quoteEnd + 2 : quoteEnd;
                    i = processAtom(regex, i, atomEnd, AtomKind.LITERAL, null, current);
                    continue;
                }
                int atomEnd = i + 2;
                if ((escaped == 'p' || escaped == 'P') && atomEnd < regex.length()
                        && regex.charAt(atomEnd) == '{') {
                    int propertyEnd = regex.indexOf('}', atomEnd + 1);
                    if (propertyEnd < 0) {
                        throw new UnsafePatternException(regex, "unterminated Unicode property");
                    }
                    atomEnd = propertyEnd + 1;
                }
                AtomKind kind = isZeroWidthEscape(escaped) ? AtomKind.ZERO_WIDTH : AtomKind.CLASS;
                i = processAtom(regex, i, atomEnd, kind, null, current);
                continue;
            }

            if (c == '[') {
                int end = classEnd(regex, i);
                i = processAtom(regex, i, end, AtomKind.CLASS, null, current);
                continue;
            }

            if (c == '(') {
                FlagGroup flags = flagGroup(regex, i);
                if (flags != null && flags.closesImmediately) {
                    i = flags.contentStart;
                    continue;
                }
                GroupState nested = groupState(regex, i);
                if (groups.size() >= MAX_GROUP_DEPTH + 1) {
                    throw new UnsafePatternException(regex,
                            "group nesting is limited to " + MAX_GROUP_DEPTH);
                }
                groups.add(nested);
                i = nested.contentStart;
                continue;
            }

            if (c == ')') {
                if (groups.size() == 1) {
                    throw new UnsafePatternException(regex, "unmatched closing parenthesis");
                }
                GroupState closed = groups.remove(groups.size() - 1);
                GroupState parent = groups.get(groups.size() - 1);
                if (closed.lookAround && closed.hasQuantifier) {
                    throw new UnsafePatternException(regex,
                            "quantifiers inside look-arounds are outside the safe subset");
                }
                i = processAtom(regex, closed.groupStart, i + 1,
                        closed.lookAround ? AtomKind.ZERO_WIDTH : AtomKind.GROUP, closed, parent);
                continue;
            }

            if (c == '|') {
                current.hasAlternation = true;
                current.lastUnbounded = false;
                i++;
                continue;
            }

            if (c == '^' || c == '$') {
                i = processAtom(regex, i, i + 1, AtomKind.ZERO_WIDTH, null, current);
                continue;
            }

            if (isQuantifierStart(c)) {
                throw new UnsafePatternException(regex, "quantifier has no preceding atom");
            }

            i = processAtom(regex, i, i + 1,
                    c == '.' ? AtomKind.DOT : AtomKind.LITERAL, null, current);
        }
        if (groups.size() != 1) {
            throw new UnsafePatternException(regex, "unclosed parenthesis");
        }
    }

    private static int processAtom(String regex, int atomStart, int atomEnd, AtomKind kind,
                                   GroupState closed, GroupState context) {
        Quantifier q = quantifierAt(regex, atomEnd);
        if (q.present) {
            if (kind == AtomKind.ZERO_WIDTH) {
                throw new UnsafePatternException(regex, "zero-width assertions may not be quantified");
            }
            if (closed != null && (closed.hasQuantifier || closed.hasAlternation)) {
                throw new UnsafePatternException(regex,
                        "groups containing quantifiers or alternatives may not be quantified");
            }
            if (q.unbounded && (kind == AtomKind.LITERAL || kind == AtomKind.DOT)) {
                throw new UnsafePatternException(regex,
                        "unbounded repetition is allowed only for a character class");
            }
            if (q.unbounded && context.lastUnbounded) {
                throw new UnsafePatternException(regex,
                        "adjacent unbounded repetitions are ambiguous");
            }
            context.hasQuantifier = true;
            if (q.unbounded) {
                context.lastUnbounded = true;
            } else if (q.minimum > 0) {
                context.lastUnbounded = false;
            }
            return q.end;
        }

        if (kind != AtomKind.ZERO_WIDTH) {
            if (kind == AtomKind.GROUP && closed != null && closed.hasQuantifier
                    && context.lastUnbounded) {
                throw new UnsafePatternException(regex,
                        "adjacent variable-length groups are ambiguous");
            }
            if (closed != null) {
                context.hasQuantifier |= closed.hasQuantifier;
                context.hasAlternation |= closed.hasAlternation;
            }
            context.lastUnbounded = false;
        }
        return atomEnd;
    }

    private static Quantifier quantifierAt(String regex, int offset) {
        if (offset >= regex.length()) return Quantifier.NONE;
        char c = regex.charAt(offset);
        int end;
        int minimum;
        boolean unbounded;
        if (c == '*' || c == '+' || c == '?') {
            minimum = c == '+' ? 1 : 0;
            unbounded = c != '?';
            end = offset + 1;
        } else if (c == '{') {
            int close = regex.indexOf('}', offset + 1);
            if (close < 0) return Quantifier.NONE;
            String body = regex.substring(offset + 1, close);
            int comma = body.indexOf(',');
            try {
                if (comma < 0) {
                    minimum = Integer.parseInt(body);
                    unbounded = false;
                    if (minimum > 1000) {
                        throw new UnsafePatternException(regex,
                                "bounded repetitions must have an upper limit at most 1000");
                    }
                } else {
                    minimum = Integer.parseInt(body.substring(0, comma));
                    String maximum = body.substring(comma + 1);
                    unbounded = maximum.isEmpty();
                    if (!maximum.isEmpty()) {
                        int max = Integer.parseInt(maximum);
                        if (max < minimum || max > 1000) {
                            throw new UnsafePatternException(regex,
                                    "bounded repetitions must have an upper limit at most 1000");
                        }
                    }
                }
            } catch (NumberFormatException e) {
                return Quantifier.NONE;
            }
            end = close + 1;
        } else {
            return Quantifier.NONE;
        }
        if (end < regex.length() && (regex.charAt(end) == '?' || regex.charAt(end) == '+')) {
            end++;
        }
        return new Quantifier(true, unbounded, minimum, end);
    }

    private static int classEnd(String regex, int start) {
        boolean escaped = false;
        for (int i = start + 1; i < regex.length(); i++) {
            char c = regex.charAt(i);
            if (escaped) {
                escaped = false;
            } else if (c == '\\') {
                escaped = true;
            } else if (c == ']') {
                return i + 1;
            }
        }
        throw new UnsafePatternException(regex, "unterminated character class");
    }

    private static GroupState groupState(String regex, int start) {
        if (start + 1 >= regex.length() || regex.charAt(start + 1) != '?') {
            return new GroupState(start, start + 1, false);
        }
        if (startsWith(regex, start, "(?:")) return new GroupState(start, start + 3, false);
        if (startsWith(regex, start, "(?=") || startsWith(regex, start, "(?!")) {
            return new GroupState(start, start + 3, true);
        }
        if (startsWith(regex, start, "(?<=") || startsWith(regex, start, "(?<!")) {
            return new GroupState(start, start + 4, true);
        }
        if (startsWith(regex, start, "(?>")) {
            throw new UnsafePatternException(regex, "atomic groups are outside the supported subset");
        }
        if (startsWith(regex, start, "(?<")) {
            int close = regex.indexOf('>', start + 3);
            if (close < 0) throw new UnsafePatternException(regex, "unterminated named group");
            String name = regex.substring(start + 3, close);
            if (!name.matches("[A-Za-z][A-Za-z0-9]*")) {
                throw new UnsafePatternException(regex, "invalid named group");
            }
            return new GroupState(start, close + 1, false);
        }
        FlagGroup flags = flagGroup(regex, start);
        if (flags != null && !flags.closesImmediately) {
            return new GroupState(start, flags.contentStart, false);
        }
        throw new UnsafePatternException(regex, "unsupported group construct");
    }

    private static FlagGroup flagGroup(String regex, int start) {
        if (!startsWith(regex, start, "(?")) return null;
        int i = start + 2;
        boolean sawFlag = false;
        while (i < regex.length()) {
            char c = regex.charAt(i);
            if ("idmsuxU-".indexOf(c) >= 0) {
                sawFlag = true;
                i++;
                continue;
            }
            if (!sawFlag) return null;
            if (c == ')') return new FlagGroup(true, i + 1);
            if (c == ':') return new FlagGroup(false, i + 1);
            return null;
        }
        return null;
    }

    private static boolean startsWith(String value, int offset, String prefix) {
        return offset >= 0 && offset + prefix.length() <= value.length()
                && value.regionMatches(offset, prefix, 0, prefix.length());
    }

    private static boolean isQuantifierStart(char c) {
        return c == '*' || c == '+' || c == '?' || c == '{';
    }

    private static boolean isZeroWidthEscape(char c) {
        return c == 'A' || c == 'b' || c == 'B' || c == 'G'
                || c == 'Z' || c == 'z';
    }

    private static String abbreviate(String value) {
        if (value == null) return "";
        return value.length() <= 80 ? value : value.substring(0, 77) + "...";
    }

    private enum AtomKind { LITERAL, CLASS, GROUP, DOT, ZERO_WIDTH }

    private static final class GroupState {
        final int groupStart;
        final int contentStart;
        final boolean lookAround;
        boolean hasQuantifier;
        boolean hasAlternation;
        boolean lastUnbounded;

        GroupState(boolean root) {
            this(-1, 0, false);
        }

        GroupState(int groupStart, int contentStart, boolean lookAround) {
            this.groupStart = groupStart;
            this.contentStart = contentStart;
            this.lookAround = lookAround;
        }
    }

    private static final class Quantifier {
        static final Quantifier NONE = new Quantifier(false, false, 1, -1);
        final boolean present;
        final boolean unbounded;
        final int minimum;
        final int end;

        Quantifier(boolean present, boolean unbounded, int minimum, int end) {
            this.present = present;
            this.unbounded = unbounded;
            this.minimum = minimum;
            this.end = end;
        }
    }

    private static final class FlagGroup {
        final boolean closesImmediately;
        final int contentStart;

        FlagGroup(boolean closesImmediately, int contentStart) {
            this.closesImmediately = closesImmediately;
            this.contentStart = contentStart;
        }
    }
}
