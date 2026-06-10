package flash.pipeline.analyses;

import flash.pipeline.io.ConditionManifestIO;
import flash.pipeline.io.CsvSupport;
import flash.pipeline.io.FlashProjectLayout;
import flash.pipeline.io.IoUtils;
import ij.IJ;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Shared helper for applying the current {@code Conditions.csv} to aggregation
 * output. Aggregation is the point where raw per-image rows become
 * condition-grouped project tables, so conditions are resolved here from the
 * animals present in the raw results — even when those analyses ran before the
 * manifest was corrected.
 *
 * <p>Grouped master rows (per-hemisphere, per-region) carry the parent animal's
 * condition, not the row key's, via {@link #parentAnimal(String, Map)}.
 */
public final class AggregationConditionSupport {

    static final String CONDITION_COLUMN = "Condition";
    static final String SNAPSHOT_FILENAME = "Condition Assignments Used.csv";

    // Intensity master filenames are private to MasterAggregationAnalysis; mirror
    // the stable output names here so the refresh path can target them directly.
    private static final String[] MASTER_TABLE_FILENAMES = {
            FlashProjectLayout.MASTER_OBJECTS_FILENAME,
            FlashProjectLayout.MASTER_INTENSITIES_FILENAME,
            "Image Intensities_MIP.csv",
            "Image Intensities_3D.csv"
    };

    private AggregationConditionSupport() {}

    /**
     * Resolve the current condition for each group key by looking up its parent
     * animal in {@code Conditions.csv} (auto-detecting for animals missing from
     * the manifest, exactly like the rest of FLASH).
     *
     * @param groupKeyToAnimal optional map from grouped row key to parent animal;
     *                         when a key is absent the parent is extracted from
     *                         the key itself.
     */
    static LinkedHashMap<String, String> resolveConditionsForGroups(
            String directory,
            Set<String> groupKeys,
            Map<String, String> groupKeyToAnimal) {
        LinkedHashSet<String> parentAnimals = new LinkedHashSet<String>();
        if (groupKeys != null) {
            for (String groupKey : groupKeys) {
                parentAnimals.add(parentAnimal(groupKey, groupKeyToAnimal));
            }
        }
        LinkedHashMap<String, String> byAnimal =
                ConditionManifestIO.resolveAssignments(directory, parentAnimals);

        LinkedHashMap<String, String> byGroup = new LinkedHashMap<String, String>();
        if (groupKeys != null) {
            for (String groupKey : groupKeys) {
                String parent = parentAnimal(groupKey, groupKeyToAnimal);
                String condition = byAnimal.get(parent);
                byGroup.put(groupKey, condition == null ? "" : condition);
            }
        }
        return byGroup;
    }

    /** Parent animal for a (possibly grouped) row key. */
    static String parentAnimal(String groupKey, Map<String, String> groupKeyToAnimal) {
        if (groupKeyToAnimal != null && groupKeyToAnimal.containsKey(groupKey)) {
            String mapped = groupKeyToAnimal.get(groupKey);
            if (mapped != null && !mapped.trim().isEmpty()) {
                return mapped.trim();
            }
        }
        String extracted = ConditionManifestIO.extractAnimalName(groupKey);
        return extracted == null || extracted.trim().isEmpty()
                ? (groupKey == null ? "" : groupKey.trim())
                : extracted.trim();
    }

    /**
     * Write the minimal condition snapshot used by later stages to detect stale
     * master-table condition labels. Format:
     * <pre>RowName,ParentAnimal,Condition,run_id</pre>
     *
     * @param rowsByName ordered map of row name -&gt; {parentAnimal, condition}.
     */
    static void writeSnapshot(File detailsDir,
                              final LinkedHashMap<String, String[]> rowsByName,
                              final String runId) {
        if (detailsDir == null || rowsByName == null || rowsByName.isEmpty()) {
            return;
        }
        final File out = new File(detailsDir, SNAPSHOT_FILENAME);
        try {
            IoUtils.mustMkdirs(detailsDir);
            CsvSupport.writeAtomically(out, new CsvSupport.WriterAction() {
                @Override
                public void write(PrintWriter pw) {
                    pw.println(CsvSupport.joinRow(
                            Arrays.asList("RowName", "ParentAnimal", "Condition", "run_id")));
                    for (Map.Entry<String, String[]> entry : rowsByName.entrySet()) {
                        String[] value = entry.getValue();
                        String parent = value != null && value.length > 0 ? safe(value[0]) : "";
                        String condition = value != null && value.length > 1 ? safe(value[1]) : "";
                        pw.println(CsvSupport.joinRow(Arrays.asList(
                                safe(entry.getKey()), parent, condition, runId == null ? "" : runId)));
                    }
                }
            });
        } catch (IOException e) {
            IJ.log("Warning: could not write condition snapshot: " + e.getMessage());
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    // ------------------------------------------------------ Post-hoc refresh

    /** Outcome of refreshing one master CSV's Condition column. */
    public static final class RefreshResult {
        public final boolean rewritten;
        public final int rowsUpdated;
        public final int rowsUnresolved;
        public final String message;

        RefreshResult(boolean rewritten, int rowsUpdated, int rowsUnresolved, String message) {
            this.rewritten = rewritten;
            this.rowsUpdated = rowsUpdated;
            this.rowsUnresolved = rowsUnresolved;
            this.message = message;
        }

        static RefreshResult skipped(String message) {
            return new RefreshResult(false, 0, 0, message);
        }
    }

    /** Aggregate outcome of refreshing every master table in a project. */
    public static final class RefreshSummary {
        public final int filesUpdated;
        public final int rowsUpdated;
        public final int rowsUnresolved;
        public final List<String> messages;

        RefreshSummary(int filesUpdated, int rowsUpdated, int rowsUnresolved, List<String> messages) {
            this.filesUpdated = filesUpdated;
            this.rowsUpdated = rowsUpdated;
            this.rowsUnresolved = rowsUnresolved;
            this.messages = messages;
        }

        public boolean anyUpdated() {
            return filesUpdated > 0;
        }
    }

    /**
     * Reapply the current {@code Conditions.csv} to every existing master table
     * in the project without recomputing any numeric metrics. Old master CSVs
     * with no Condition column gain one after AnimalName; newer ones have their
     * Condition column updated in place.
     */
    public static RefreshSummary refreshAllMasterTables(String directory) {
        File summaryDir = FlashProjectLayout.forDirectory(directory).tablesProjectSummaryWriteDir();
        int filesUpdated = 0;
        int rowsUpdated = 0;
        int rowsUnresolved = 0;
        List<String> messages = new ArrayList<String>();
        for (String name : MASTER_TABLE_FILENAMES) {
            File master = new File(summaryDir, name);
            if (!master.isFile()) continue;
            RefreshResult result = refreshMasterCsvConditions(directory, master);
            messages.add(result.message);
            IJ.log("[Aggregation] " + result.message);
            if (result.rewritten) {
                filesUpdated++;
                rowsUpdated += result.rowsUpdated;
                rowsUnresolved += result.rowsUnresolved;
            }
        }
        if (filesUpdated == 0) {
            messages.add("No master tables found to refresh.");
        }
        return new RefreshSummary(filesUpdated, rowsUpdated, rowsUnresolved, messages);
    }

    /**
     * Add or update the Condition column of a single master CSV from the current
     * manifest, preserving all numeric and run-id columns. Atomic rewrite: on
     * failure the original file is left untouched.
     */
    public static RefreshResult refreshMasterCsvConditions(String directory, File masterCsv) {
        if (masterCsv == null || !masterCsv.isFile()) {
            return RefreshResult.skipped("Master table not found: "
                    + (masterCsv == null ? "<null>" : masterCsv.getName()));
        }

        final String[] header;
        final List<String[]> rows = new ArrayList<String[]>();
        try {
            CsvSupport.RecordReader reader = CsvSupport.openRecordReader(masterCsv);
            try {
                CsvSupport.Record headerRecord = reader.readRecord();
                if (headerRecord == null) {
                    return RefreshResult.skipped("Empty master table: " + masterCsv.getName());
                }
                header = CsvSupport.parseRecord(headerRecord.text);
                CsvSupport.Record record;
                while ((record = reader.readRecord()) != null) {
                    if (CsvSupport.isBlankRecord(record.text)) continue;
                    rows.add(CsvSupport.parseRecord(record.text));
                }
            } finally {
                reader.close();
            }
        } catch (IOException e) {
            return RefreshResult.skipped("Could not read " + masterCsv.getName() + ": " + e.getMessage());
        }

        final int animalIdx = indexOf(header, "AnimalName");
        if (animalIdx < 0) {
            return RefreshResult.skipped("No AnimalName column in " + masterCsv.getName() + "; skipped.");
        }
        final int existingConditionIdx = indexOf(header, CONDITION_COLUMN);
        final boolean insert = existingConditionIdx < 0;
        final int insertAt = animalIdx + 1;

        // The snapshot (written by Stage 03 aggregation) carries the authoritative
        // grouped-row -> parent-animal mapping; fall back to name extraction for
        // old master tables written before snapshots existed.
        Map<String, String> snapshotParents = readSnapshotRowParents(directory);

        LinkedHashSet<String> parents = new LinkedHashSet<String>();
        for (String[] row : rows) {
            String rowName = animalIdx < row.length ? row[animalIdx].trim() : "";
            parents.add(resolveParent(rowName, snapshotParents));
        }
        LinkedHashMap<String, String> byAnimal =
                ConditionManifestIO.resolveAssignments(directory, parents);

        final List<String> outHeader = new ArrayList<String>(Arrays.asList(header));
        if (insert) {
            outHeader.add(insertAt, CONDITION_COLUMN);
        }

        final List<List<String>> outRows = new ArrayList<List<String>>();
        int updated = 0;
        int unresolved = 0;
        for (String[] row : rows) {
            String rowName = animalIdx < row.length ? row[animalIdx].trim() : "";
            String parent = resolveParent(rowName, snapshotParents);
            String condition = byAnimal.get(parent);
            if (condition == null || condition.trim().isEmpty()) {
                condition = "";
                unresolved++;
            } else {
                condition = condition.trim();
                updated++;
            }

            List<String> cells = new ArrayList<String>(header.length + 1);
            for (int j = 0; j < header.length; j++) {
                cells.add(j < row.length ? row[j] : "");
            }
            if (insert) {
                cells.add(insertAt, condition);
            } else if (existingConditionIdx < cells.size()) {
                cells.set(existingConditionIdx, condition);
            } else {
                cells.add(condition);
            }
            outRows.add(cells);
        }

        try {
            CsvSupport.writeAtomically(masterCsv, new CsvSupport.WriterAction() {
                @Override
                public void write(PrintWriter pw) {
                    pw.println(CsvSupport.joinRow(outHeader));
                    for (List<String> row : outRows) {
                        pw.println(CsvSupport.joinRow(row));
                    }
                }
            });
        } catch (IOException e) {
            return RefreshResult.skipped("Could not rewrite " + masterCsv.getName()
                    + " (left unchanged): " + e.getMessage());
        }

        String message = (insert ? "Added Condition column to " : "Refreshed conditions in ")
                + masterCsv.getName() + " (" + updated + " row" + (updated == 1 ? "" : "s")
                + (unresolved > 0 ? ", " + unresolved + " unresolved" : "") + ").";
        return new RefreshResult(true, updated, unresolved, message);
    }

    private static int indexOf(String[] header, String name) {
        if (header == null) return -1;
        for (int i = 0; i < header.length; i++) {
            if (header[i] != null && header[i].trim().equals(name)) {
                return i;
            }
        }
        return -1;
    }

    private static String resolveParent(String rowName, Map<String, String> snapshotParents) {
        if (snapshotParents != null) {
            String parent = snapshotParents.get(rowName);
            if (parent != null && !parent.trim().isEmpty()) {
                return parent.trim();
            }
        }
        return parentAnimal(rowName, null);
    }

    /** Read the row-name -&gt; parent-animal mapping from the aggregation snapshot, if present. */
    static Map<String, String> readSnapshotRowParents(String directory) {
        LinkedHashMap<String, String> out = new LinkedHashMap<String, String>();
        File snapshot = new File(
                FlashProjectLayout.forDirectory(directory).analysisDetailsWriteDir(), SNAPSHOT_FILENAME);
        if (!snapshot.isFile()) return out;
        try {
            CsvSupport.RecordReader reader = CsvSupport.openRecordReader(snapshot);
            try {
                CsvSupport.Record headerRecord = reader.readRecord();
                if (headerRecord == null) return out;
                String[] header = CsvSupport.parseRecord(headerRecord.text);
                int rowNameIdx = indexOf(header, "RowName");
                int parentIdx = indexOf(header, "ParentAnimal");
                if (rowNameIdx < 0 || parentIdx < 0) return out;
                CsvSupport.Record record;
                while ((record = reader.readRecord()) != null) {
                    if (CsvSupport.isBlankRecord(record.text)) continue;
                    String[] row = CsvSupport.parseRecord(record.text);
                    String rowName = rowNameIdx < row.length ? row[rowNameIdx].trim() : "";
                    String parent = parentIdx < row.length ? row[parentIdx].trim() : "";
                    if (!rowName.isEmpty() && !parent.isEmpty()) {
                        out.put(rowName, parent);
                    }
                }
            } finally {
                reader.close();
            }
        } catch (IOException ignored) {
            // A missing/garbled snapshot just means we fall back to name extraction.
        }
        return out;
    }
}
