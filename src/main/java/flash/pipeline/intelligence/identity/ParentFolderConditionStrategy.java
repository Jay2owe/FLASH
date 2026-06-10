package flash.pipeline.intelligence.identity;

import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Inference preset: the immediate parent folder names the condition
 * ({@code .../WT/animal.lif}). Hardened against false positives — date-like,
 * pure-numeric, and pipeline-junk folder names ({@code Images/Raw/Export/LIF…})
 * are rejected, as are caller-supplied names (the output root, the project name).
 *
 * <p>When the parent level does not yield 2–4 distinct valid groups across the
 * batch, the grandparent level is tried (the level giving a balanced 2–4 groups
 * wins) so {@code .../WT/slide1/img.lif} still resolves.
 */
public final class ParentFolderConditionStrategy implements IdentityStrategy {

    public static final String AXIS_CONDITION = "Condition";

    private static final Set<String> JUNK = new HashSet<String>();
    static {
        for (String s : new String[]{
                "images", "image", "raw", "rawdata", "export", "exports",
                "lif", "lifs", "czi", "nd2", "tif", "tiff", "ome", "ometiff",
                "data", "output", "outputs", "result", "results", "flash",
                "project", "projects", "temp", "tmp", "new", "old", "backup",
                "archive", "scans", "scan", "microscopy", "imaging"}) {
            JUNK.add(s);
        }
    }

    private final Set<String> rejectNames;

    public ParentFolderConditionStrategy() {
        this(null);
    }

    /** @param rejectExtra additional folder names to reject (e.g. output root, project name). */
    public ParentFolderConditionStrategy(Set<String> rejectExtra) {
        this.rejectNames = new HashSet<String>();
        if (rejectExtra != null) {
            for (String s : rejectExtra) {
                if (s != null) rejectNames.add(s.trim().toLowerCase(Locale.ROOT));
            }
        }
    }

    @Override
    public Map<SourceRecord, PartialIdentity> detect(List<SourceRecord> batch) {
        Map<SourceRecord, PartialIdentity> out = new IdentityHashMap<SourceRecord, PartialIdentity>();
        if (batch == null || batch.isEmpty()) return out;

        boolean useGrandparent = chooseGrandparentLevel(batch);
        for (SourceRecord rec : batch) {
            String label = validLabel(useGrandparent ? rec.grandparentFolder : rec.parentFolder);
            if (label.isEmpty()) continue;
            out.put(rec, new PartialIdentity().condition(
                    AXIS_CONDITION, label, Confidence.MEDIUM,
                    "parent folder" + (useGrandparent ? " (1 level up)" : "")));
        }
        return out;
    }

    /**
     * Fall back to the grandparent level only when the parent level is clearly
     * per-file (every valid parent distinct) and the grandparent yields a genuine
     * 2–4 grouped split (some group has more than one member).
     */
    private boolean chooseGrandparentLevel(List<SourceRecord> batch) {
        int parentCount = validCount(batch, false);
        int parentDistinct = distinctValid(batch, false);
        boolean parentAllUnique = parentCount > 1 && parentDistinct == parentCount;

        int grandCount = validCount(batch, true);
        int grandDistinct = distinctValid(batch, true);
        boolean grandBalanced = grandDistinct >= 2 && grandDistinct <= 4 && grandDistinct < grandCount;

        return parentAllUnique && grandBalanced;
    }

    private int validCount(List<SourceRecord> batch, boolean grandparent) {
        int n = 0;
        for (SourceRecord rec : batch) {
            if (!validLabel(grandparent ? rec.grandparentFolder : rec.parentFolder).isEmpty()) n++;
        }
        return n;
    }

    private int distinctValid(List<SourceRecord> batch, boolean grandparent) {
        Set<String> distinct = new LinkedHashSet<String>();
        for (SourceRecord rec : batch) {
            String label = validLabel(grandparent ? rec.grandparentFolder : rec.parentFolder);
            if (!label.isEmpty()) distinct.add(label);
        }
        return distinct.size();
    }

    private String validLabel(String folder) {
        return isJunkFolder(folder) ? "" : folder.trim();
    }

    /** True when {@code name} is empty, a reject-name, date-like, pure-numeric, or pipeline junk. */
    boolean isJunkFolder(String name) {
        if (name == null) return true;
        String t = name.trim();
        if (t.isEmpty()) return true;
        String lower = t.toLowerCase(Locale.ROOT);
        if (rejectNames.contains(lower)) return true;
        if (JUNK.contains(lower)) return true;
        if (t.matches("\\d+")) return true;            // pure number (incl. a bare year)
        return isDateLike(t);
    }

    private static boolean isDateLike(String t) {
        return t.matches("\\d{8}")                                  // yyyymmdd
                || t.matches("\\d{4}[-_./]\\d{1,2}[-_./]\\d{1,2}")  // yyyy-mm-dd
                || t.matches("\\d{1,2}[-_./]\\d{1,2}[-_./]\\d{2,4}");// dd-mm-yyyy
    }
}
