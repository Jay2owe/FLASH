package flash.pipeline.image;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Maps each line of a filter macro to the image branch it belongs to.
 *
 * <p>Part of the universal branch-autodetection design
 * (docs/filter-branch-robustness/08_design_pivot.md): the macro text is the
 * source of truth, and branch membership is overlaid on it without re-emitting
 * through the DAG. The accordion uses this to show per-branch sections; the
 * parameter sweep uses it to label which branch each swept parameter is on.
 *
 * <p>Line indices are 0-based into {@code macro.split("\\r?\\n", -1)}, matching
 * {@code FilterMacroEditorModel.Entry.lineIndex}, so callers can resolve an
 * entry's branch with {@code labelByLine(macro).get(entry.lineIndex)}.
 *
 * <p>Recognition mirrors {@code IjmToDagLoader}: a {@code Duplicate... title=X}
 * line opens branch {@code X}; an {@code imageCalculator(...)} starts the
 * combine / post-combine region; {@code selectWindow}/{@code selectImage}
 * re-targets the active branch (back to the source path unless it names a known
 * branch).
 */
public final class FilterBranchLabels {

    public static final String SOURCE = "source";
    public static final String COMBINE = "combine";
    public static final String AFTER_COMBINE = "after combine";

    private static final Pattern RUN_PATTERN = Pattern.compile(
            "run\\s*\\(\\s*\"([^\"]+)\"(?:\\s*,\\s*\"([^\"]*)\")?\\s*\\)");
    private static final Pattern TITLE_PATTERN = Pattern.compile(
            "title\\s*=\\s*([^\\s\"]+)");
    private static final Pattern SELECT_TARGET = Pattern.compile(
            "select(?:Window|Image)\\s*\\(\\s*\"?([^\")]+?)\"?\\s*\\)");

    private FilterBranchLabels() {}

    /** Line index (0-based) -> human branch label for every line in the macro. */
    public static Map<Integer, String> labelByLine(String macro) {
        Map<Integer, String> out = new LinkedHashMap<Integer, String>();
        if (macro == null) {
            return out;
        }
        String[] lines = macro.split("\\r?\\n", -1);
        Set<String> seenBranches = new LinkedHashSet<String>();
        String current = SOURCE;
        boolean postCombine = false;

        for (int i = 0; i < lines.length; i++) {
            String raw = lines[i] == null ? "" : lines[i].trim();

            if (postCombine) {
                out.put(i, AFTER_COMBINE);
                continue;
            }
            if (raw.isEmpty() || raw.startsWith("//")) {
                out.put(i, current);
                continue;
            }
            if (raw.startsWith("imageCalculator")) {
                out.put(i, COMBINE);
                postCombine = true;
                continue;
            }
            String dupTitle = duplicateTitle(raw);
            if (dupTitle != null) {
                current = humanize(dupTitle);
                seenBranches.add(current);
                out.put(i, current);
                continue;
            }
            if (raw.startsWith("selectWindow") || raw.startsWith("selectImage")) {
                String target = humanize(selectTarget(raw));
                current = seenBranches.contains(target) ? target : SOURCE;
                out.put(i, current);
                continue;
            }
            out.put(i, current);
        }
        return out;
    }

    /** Distinct branch labels in first-appearance order. */
    public static List<String> branches(String macro) {
        Set<String> ordered = new LinkedHashSet<String>(labelByLine(macro).values());
        return new ArrayList<String>(ordered);
    }

    /**
     * True when the macro duplicates the source more than once or merges paths —
     * i.e. it is genuinely branched. Mirrors {@code IjmToDagLoader.isBranched}.
     */
    public static boolean isBranched(String macro) {
        if (macro == null) {
            return false;
        }
        int duplicateCount = 0;
        boolean hasCombine = false;
        for (String line : macro.split("\\r?\\n", -1)) {
            String raw = line == null ? "" : line.trim();
            if (raw.startsWith("imageCalculator")) {
                hasCombine = true;
            }
            if (duplicateTitle(raw) != null) {
                duplicateCount++;
            }
        }
        return hasCombine || duplicateCount >= 2;
    }

    /** Returns the working-copy title of a {@code Duplicate} run line, else null. */
    private static String duplicateTitle(String raw) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        Matcher m = RUN_PATTERN.matcher(raw);
        if (!m.find()) {
            return null;
        }
        if (!"duplicate".equals(normalizeCommand(m.group(1)))) {
            return null;
        }
        String args = m.group(2) == null ? "" : m.group(2);
        Matcher t = TITLE_PATTERN.matcher(args);
        return t.find() ? t.group(1) : "branch";
    }

    private static String selectTarget(String raw) {
        Matcher m = SELECT_TARGET.matcher(raw);
        return m.find() ? m.group(1).trim() : "";
    }

    private static String normalizeCommand(String command) {
        if (command == null) {
            return "";
        }
        String s = command.trim();
        while (s.endsWith(".")) {
            s = s.substring(0, s.length() - 1).trim();
        }
        return s.toLowerCase(Locale.ROOT);
    }

    /**
     * Readable branch label from a working-copy title: drop leading underscores,
     * turn remaining underscores into spaces, trim. {@code _dens -> dens},
     * {@code _edge -> edge}, {@code DoG_small -> DoG small}. No invented
     * expansions.
     */
    static String humanize(String title) {
        if (title == null) {
            return "";
        }
        String s = title.trim();
        int start = 0;
        while (start < s.length() && s.charAt(start) == '_') {
            start++;
        }
        s = s.substring(start);
        s = s.replace('_', ' ').trim();
        return s;
    }
}
