package flash.pipeline.results;

import ij.measure.ResultsTable;

import java.util.LinkedHashSet;
import java.util.Set;

/** Utility methods to make ResultsTable output deterministic and macro-compatible. */
public final class ResultsTableCleaner {

    private ResultsTableCleaner() {}

    /**
     * Keep only the specified columns. Deletes all other columns if present.
     * Attempts to preserve the order as provided by colsToKeep by re-setting columns.
     */
    public static void keepOnlyColumns(ResultsTable t, String[] colsToKeep) {
        if (t == null || colsToKeep == null) return;

        Set<String> keep = new LinkedHashSet<>();
        for (String c : colsToKeep) {
            if (c != null && !c.trim().isEmpty()) keep.add(c);
        }

        // Delete all numeric columns not in keep
        String[] headings = t.getHeadings();
        if (headings != null) {
            for (String h : headings) {
                if (h == null) continue;
                if (!keep.contains(h)) {
                    try {
                        t.deleteColumn(h);
                    } catch (Exception ignored) {
                    }
                }
            }
        }

        // Ensure kept columns exist (fill missing with zeros) and enforce ordering by "touching" them
        int rows = t.size();
        for (String c : colsToKeep) {
            if (c == null) continue;
            if (rows > 0) {
                try {
                    // touch value
                    double v = t.getValue(c, 0);
                    t.setValue(c, 0, v);
                } catch (Exception e) {
                    // create column
                    for (int r = 0; r < rows; r++) t.setValue(c, r, 0);
                }
            }
        }
    }
}
