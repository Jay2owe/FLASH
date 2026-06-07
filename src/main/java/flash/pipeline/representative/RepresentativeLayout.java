package flash.pipeline.representative;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Ordered rows of representative figure conditions.
 */
public final class RepresentativeLayout {
    private final List<List<String>> rows;

    public RepresentativeLayout(List<List<String>> rows) {
        this.rows = normalizeRows(rows);
    }

    public static RepresentativeLayout allInOneRow(List<String> conditionNames) {
        List<List<String>> rows = new ArrayList<List<String>>();
        rows.add(conditionNames == null
                ? Collections.<String>emptyList()
                : new ArrayList<String>(conditionNames));
        return new RepresentativeLayout(rows);
    }

    public static RepresentativeLayout fromRowAssignments(List<String> orderedConditions,
                                                          List<Integer> rowNumbers) {
        if (orderedConditions == null) {
            throw new IllegalArgumentException("Condition list is required.");
        }
        if (rowNumbers == null || rowNumbers.size() != orderedConditions.size()) {
            throw new IllegalArgumentException("Each condition must have one row assignment.");
        }
        Map<Integer, List<String>> byRow = new TreeMap<Integer, List<String>>();
        for (int i = 0; i < orderedConditions.size(); i++) {
            String condition = clean(orderedConditions.get(i));
            if (condition.isEmpty()) continue;
            Integer assigned = rowNumbers.get(i);
            int rowNumber = assigned == null ? 1 : Math.max(1, assigned.intValue());
            List<String> row = byRow.get(Integer.valueOf(rowNumber));
            if (row == null) {
                row = new ArrayList<String>();
                byRow.put(Integer.valueOf(rowNumber), row);
            }
            row.add(condition);
        }
        return new RepresentativeLayout(new ArrayList<List<String>>(byRow.values()));
    }

    public List<List<String>> rows() {
        return rows;
    }

    public int rowCount() {
        return rows.size();
    }

    public int conditionCount() {
        int count = 0;
        for (int i = 0; i < rows.size(); i++) {
            count += rows.get(i).size();
        }
        return count;
    }

    public int maxColumnCount() {
        int max = 0;
        for (int i = 0; i < rows.size(); i++) {
            max = Math.max(max, rows.get(i).size());
        }
        return max;
    }

    public List<String> flattenedConditions() {
        List<String> out = new ArrayList<String>();
        for (int r = 0; r < rows.size(); r++) {
            out.addAll(rows.get(r));
        }
        return Collections.unmodifiableList(out);
    }

    public boolean containsExactlyConditions(List<String> conditionNames) {
        LinkedHashSet<String> expected = new LinkedHashSet<String>();
        if (conditionNames != null) {
            for (String conditionName : conditionNames) {
                String clean = clean(conditionName);
                if (!clean.isEmpty()) expected.add(clean);
            }
        }
        LinkedHashSet<String> actual = new LinkedHashSet<String>(flattenedConditions());
        return expected.size() == actual.size() && expected.equals(actual);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof RepresentativeLayout)) return false;
        RepresentativeLayout that = (RepresentativeLayout) other;
        return rows.equals(that.rows);
    }

    @Override
    public int hashCode() {
        return rows.hashCode();
    }

    @Override
    public String toString() {
        return rows.toString();
    }

    private static List<List<String>> normalizeRows(List<List<String>> inputRows) {
        List<List<String>> normalized = new ArrayList<List<String>>();
        LinkedHashSet<String> seen = new LinkedHashSet<String>();
        if (inputRows != null) {
            for (List<String> inputRow : inputRows) {
                List<String> row = new ArrayList<String>();
                if (inputRow != null) {
                    for (String value : inputRow) {
                        String condition = clean(value);
                        if (condition.isEmpty()) continue;
                        if (!seen.add(condition)) {
                            throw new IllegalArgumentException(
                                    "Condition appears more than once in layout: " + condition);
                        }
                        row.add(condition);
                    }
                }
                if (!row.isEmpty()) {
                    normalized.add(Collections.unmodifiableList(row));
                }
            }
        }
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("At least one condition is required.");
        }
        return Collections.unmodifiableList(normalized);
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
