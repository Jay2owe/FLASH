package flash.pipeline.naming;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Detects an experimental condition label from an animal identifier.
 * <p>
 * This keeps the legacy behaviour for names like {@code NLGF11 -> NLGF},
 * while also handling identifiers where an animal number appears between a
 * model name and a suffix condition, e.g.
 * {@code Syn1WeekTwo -> SynWeekTwo}.
 */
public final class ConditionNameParser {

    private static final Pattern DIGIT_RUN = Pattern.compile("\\d+");

    private ConditionNameParser() {}

    public static String detectCondition(String animalName) {
        if (animalName == null) return "";

        String trimmed = animalName.trim();
        if (trimmed.isEmpty()) return "";

        int internalStart = -1;
        int internalEnd = -1;
        int trailingStart = -1;
        int trailingEnd = -1;

        Matcher matcher = DIGIT_RUN.matcher(trimmed);
        while (matcher.find()) {
            String before = trimmed.substring(0, matcher.start());
            String after = trimmed.substring(matcher.end());

            if (!containsLetter(before)) continue;

            if (startsWithLetterAfterSeparators(after)) {
                internalStart = matcher.start();
                internalEnd = matcher.end();
                break;
            }

            if (after.isEmpty()) {
                trailingStart = matcher.start();
                trailingEnd = matcher.end();
            }
        }

        if (internalStart >= 0) {
            return joinConditionParts(
                    trimmed.substring(0, internalStart),
                    trimmed.substring(internalEnd),
                    trimmed);
        }

        if (trailingStart >= 0) {
            return joinConditionParts(
                    trimmed.substring(0, trailingStart),
                    trimmed.substring(trailingEnd),
                    trimmed);
        }

        return legacyPrefix(trimmed);
    }

    private static boolean containsLetter(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (Character.isLetter(value.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    private static boolean startsWithLetterAfterSeparators(String value) {
        int i = 0;
        while (i < value.length() && isSeparator(value.charAt(i))) {
            i++;
        }
        return i < value.length() && Character.isLetter(value.charAt(i));
    }

    private static String joinConditionParts(String before, String after, String fallback) {
        String prefix = trimTrailingSeparators(before);
        String suffix = trimLeadingSeparators(after);

        if (suffix.isEmpty()) {
            return prefix.isEmpty() ? fallback : prefix;
        }
        if (prefix.isEmpty()) {
            return suffix;
        }

        char separator = preferredSeparator(before, after);
        return separator == 0 ? prefix + suffix : prefix + separator + suffix;
    }

    private static char preferredSeparator(String before, String after) {
        char trailing = lastSeparator(before);
        if (trailing != 0) return trailing;

        char leading = firstSeparator(after);
        if (leading != 0) return leading;

        return 0;
    }

    private static char lastSeparator(String value) {
        for (int i = value.length() - 1; i >= 0; i--) {
            char c = value.charAt(i);
            if (isSeparator(c)) return c;
            if (!Character.isWhitespace(c)) break;
        }
        return 0;
    }

    private static char firstSeparator(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isWhitespace(c)) continue;
            return isSeparator(c) ? c : 0;
        }
        return 0;
    }

    private static String trimTrailingSeparators(String value) {
        int end = value.length();
        while (end > 0 && isSeparator(value.charAt(end - 1))) {
            end--;
        }
        return value.substring(0, end);
    }

    private static String trimLeadingSeparators(String value) {
        int start = 0;
        while (start < value.length() && isSeparator(value.charAt(start))) {
            start++;
        }
        return value.substring(start);
    }

    private static boolean isSeparator(char c) {
        return c == '-' || c == '_';
    }

    private static String legacyPrefix(String animalName) {
        StringBuilder prefix = new StringBuilder();
        for (char c : animalName.toCharArray()) {
            if (Character.isLetter(c) || c == '-' || c == '_') {
                prefix.append(c);
            } else {
                break;
            }
        }
        return prefix.length() > 0 ? prefix.toString() : animalName;
    }
}
