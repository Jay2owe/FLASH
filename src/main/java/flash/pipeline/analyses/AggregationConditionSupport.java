package flash.pipeline.analyses;

import flash.pipeline.io.ConditionManifestIO;
import flash.pipeline.io.CsvSupport;
import flash.pipeline.io.IoUtils;
import ij.IJ;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
final class AggregationConditionSupport {

    static final String CONDITION_COLUMN = "Condition";
    static final String SNAPSHOT_FILENAME = "Condition Assignments Used.csv";

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
}
