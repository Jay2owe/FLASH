package flash.pipeline.project;

import flash.pipeline.io.ImageSourceDispatcher;
import flash.pipeline.naming.ConditionAxis;
import flash.pipeline.naming.ConditionNameParser;
import flash.pipeline.naming.ImageNameParser;
import flash.pipeline.naming.NameParts;

import javax.swing.table.AbstractTableModel;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Backing JTable model for the Project Builder dialog. Holds one
 * {@link Row} per source file the user has staged, with live filename
 * parsing pre-filling animal/hemisphere/region/condition.
 *
 * <p>Multi-series container files (LIF/CZI/ND2) can be <em>expanded</em> into
 * one editable {@link SeriesRow} per Bio-Formats series, so each series can be
 * given its own animal/hemisphere/region/condition. The model presents a flat
 * list of "visible" rows to the JTable — file rows, plus the series rows of any
 * expanded file directly beneath their parent. Series rows stay collapsed until
 * the user expands a file, so a model with nothing expanded behaves exactly like
 * the original one-row-per-file table.
 *
 * <p>The model is intentionally view-agnostic and unit-testable: the dialog
 * supplies series count / names externally (via {@link #setSeriesCount(int, int)}
 * and {@link #setSeriesEntries(int, List)}) so this class never touches
 * Bio-Formats and runs cleanly under headless tests.
 *
 * <p>Series selection semantics: an empty {@link Row#selectedSeries} list and an
 * empty {@link Row#series} list both mean "all series in the file" — the common
 * case for single-series TIFFs and unfiltered LIFs.
 */
public final class ProjectManifestTableModel extends AbstractTableModel {

    public static final int COL_INCLUDE = 0;
    public static final int COL_FILE = 1;
    public static final int COL_SERIES = 2;
    public static final int COL_ANIMAL = 3;
    public static final int COL_HEMISPHERE = 4;
    public static final int COL_REGION = 5;
    /** First column in the generated condition-axis block. */
    public static final int COL_CONDITION = 6;
    /** Legacy notes column index when the table has only one condition column. */
    public static final int COL_NOTES = 7;

    private static final String[] FIXED_COLUMNS = {
            "Include", "File", "Series", "Animal ID", "Hemisphere", "Region"
    };
    private static final String NOTES_COLUMN = "Notes";
    private static final ConditionAxis DEFAULT_CONDITION_AXIS = ConditionAxis.of("Condition");

    /** File rows, in display order. */
    private final List<Row> rows = new ArrayList<Row>();
    /** Flattened file + expanded-series rows the JTable actually renders. */
    private final List<View> visible = new ArrayList<View>();
    /** Ordered condition-axis schema (multi-condition). Empty = single implicit "Condition". */
    private final List<ConditionAxis> conditionAxes = new ArrayList<ConditionAxis>();

    /** Index/name pair used to populate a file's per-series rows. */
    public static final class SeriesEntry {
        public final int index;
        public final String name;

        public SeriesEntry(int index, String name) {
            this.index = index;
            this.name = name == null ? "" : name;
        }
    }

    /** Per-series identity row owned by a {@link Row}. */
    public static final class SeriesRow {
        public final int index;
        public String name = "";
        public boolean include = true;
        public String animalId = "";
        public String hemisphere = "";
        public String region = "";
        public String condition = "";
        /** Extra condition axes beyond the primary (axisId -&gt; value). */
        public final Map<String, String> conditions = new LinkedHashMap<String, String>();
        public String notes = "";

        SeriesRow(int index) {
            this.index = index;
        }

        /** Display label for the File column, e.g. {@code "[0] CA1-LH"}. */
        public String label() {
            return "[" + index + "] " + (name == null || name.trim().isEmpty() ? "(unnamed)" : name);
        }
    }

    public static final class Row {
        public final File source;
        public boolean include = true;
        /** -1 until probed; >= 0 once series count is known. */
        public int seriesCount = -1;
        /** Legacy "which series to include" list — empty means "include all". */
        public final List<Integer> selectedSeries = new ArrayList<Integer>();
        /** Per-series rows once the file has been expanded; empty otherwise. */
        public final List<SeriesRow> series = new ArrayList<SeriesRow>();
        public boolean expanded;
        public String animalId = "";
        public String hemisphere = "";
        public String region = "";
        public String condition = "";
        /** Extra condition axes beyond the primary (axisId -&gt; value). */
        public final Map<String, String> conditions = new LinkedHashMap<String, String>();
        public String notes = "";

        Row(File source) {
            this.source = source;
        }

        /** True when this file can be expanded into per-series rows. */
        public boolean expandable() {
            return !series.isEmpty() || seriesCount > 1;
        }

        /**
         * Display string for the Series column. Returns one of:
         * <ul>
         *   <li>"" — count not yet probed</li>
         *   <li>"all (N)" — every series included and count known</li>
         *   <li>"M of N" — some series excluded</li>
         *   <li>"1" — single-series file (count==1)</li>
         * </ul>
         */
        public String seriesDisplay() {
            if (!series.isEmpty()) {
                int total = series.size();
                if (total <= 1) return "1";
                int included = 0;
                for (SeriesRow s : series) {
                    if (s.include) included++;
                }
                return included == total ? "all (" + total + ")" : included + " of " + total;
            }
            if (seriesCount < 0) return "";
            if (seriesCount <= 1) return "1";
            if (selectedSeries.isEmpty()) return "all (" + seriesCount + ")";
            return selectedSeries.size() + " of " + seriesCount;
        }
    }

    /** Maps one rendered table row to a file (and optionally one of its series). */
    private static final class View {
        final int fileIndex;
        /** -1 for a file row, otherwise the 0-based position within {@link Row#series}. */
        final int seriesPos;

        View(int fileIndex, int seriesPos) {
            this.fileIndex = fileIndex;
            this.seriesPos = seriesPos;
        }
    }

    public ProjectManifestTableModel() {
        rebuildVisible();
    }

    /** Add a file, parsing sample files for animal/hemisphere/region/condition. */
    public int addFile(File source) {
        Row row = new Row(source);
        applyParsedName(source, row);
        rows.add(row);
        rebuildVisible();
        int viewIndex = fileViewIndex(rows.size() - 1);
        fireTableRowsInserted(viewIndex, viewIndex);
        return rows.size() - 1;
    }

    private static void applyParsedName(File source, Row row) {
        if (isContainerSource(source)) {
            return;
        }
        NameParts parts = ImageNameParser.parse(source.getName());
        if (parts.strictMatch) {
            row.animalId = nullToEmpty(parts.animal);
            row.hemisphere = nullToEmpty(parts.hemisphere);
            row.region = nullToEmpty(parts.region);
            row.condition = nullToEmpty(parts.condition);
        } else {
            row.animalId = nullToEmpty(parts.animal);
        }
        if (row.condition.isEmpty()) {
            row.condition = ImageNameParser.guessConditionFromParentFolder(source);
        }
    }

    /** Returns the file {@link Row} owning the given rendered row index. */
    public Row get(int rowIndex) {
        return rows.get(fileIndexAt(rowIndex));
    }

    /** Number of file rows (independent of expansion). */
    public int fileCount() {
        return rows.size();
    }

    /** File {@link Row} by file index (not view index). */
    public Row getFile(int fileIndex) {
        return rows.get(fileIndex);
    }

    /** The file index that the given rendered row belongs to. */
    public int fileIndexAt(int rowIndex) {
        return visible.get(rowIndex).fileIndex;
    }

    /** True when the rendered row is a per-series row rather than a file row. */
    public boolean isSeriesRow(int rowIndex) {
        return visible.get(rowIndex).seriesPos >= 0;
    }

    /** The {@link SeriesRow} at the given rendered row, or {@code null} for a file row. */
    public SeriesRow seriesRowAt(int rowIndex) {
        View v = visible.get(rowIndex);
        if (v.seriesPos < 0) return null;
        return rows.get(v.fileIndex).series.get(v.seriesPos);
    }

    /** True when the rendered row is an expandable file row. */
    public boolean isExpandableFileRow(int rowIndex) {
        View v = visible.get(rowIndex);
        return v.seriesPos < 0 && rows.get(v.fileIndex).expandable();
    }

    /** True when the rendered row is an expanded file row. */
    public boolean isExpanded(int rowIndex) {
        View v = visible.get(rowIndex);
        return v.seriesPos < 0 && rows.get(v.fileIndex).expanded;
    }

    /** Remove the file owning the given rendered row. */
    public void removeRow(int rowIndex) {
        removeFile(fileIndexAt(rowIndex));
    }

    /** Remove a file by its file index (not view index). */
    public void removeFile(int fileIndex) {
        rows.remove(fileIndex);
        rebuildVisible();
        fireTableDataChanged();
    }

    public void clear() {
        boolean had = !rows.isEmpty();
        rows.clear();
        rebuildVisible();
        if (had) {
            fireTableDataChanged();
        }
    }

    /** True if the model already contains a row pointing at the same source path. */
    public boolean containsSource(File source) {
        if (source == null) return false;
        String target = canonicalise(source);
        for (Row r : rows) {
            if (canonicalise(r.source).equals(target)) {
                return true;
            }
        }
        return false;
    }

    /** Set series count from an external probe (e.g. {@code LifIO.getSeriesCount}). */
    public void setSeriesCount(int rowIndex, int seriesCount) {
        Row row = get(rowIndex);
        row.seriesCount = seriesCount;
        int fileView = fileViewIndex(fileIndexAt(rowIndex));
        fireTableCellUpdated(fileView, COL_SERIES);
    }

    /** Update which series are selected for a row. Empty list = include all. */
    public void setSelectedSeries(int rowIndex, List<Integer> selected) {
        Row row = get(rowIndex);
        row.selectedSeries.clear();
        if (selected != null) {
            row.selectedSeries.addAll(selected);
        }
        int fileView = fileViewIndex(fileIndexAt(rowIndex));
        fireTableCellUpdated(fileView, COL_SERIES);
    }

    /**
     * Populate (or replace) a file's per-series rows from probed series metadata.
     * Each series row is pre-filled by parsing its own series name, falling back
     * to the file-level metadata when the name is non-conforming. Does not change
     * expansion state — call {@link #setExpanded(int, boolean)} to reveal them.
     */
    public void setSeriesEntries(int rowIndex, List<SeriesEntry> entries) {
        int fileIndex = fileIndexAt(rowIndex);
        Row row = rows.get(fileIndex);
        row.series.clear();
        if (entries != null) {
            for (SeriesEntry entry : entries) {
                if (entry == null) continue;
                SeriesRow series = new SeriesRow(entry.index);
                series.name = entry.name == null ? "" : entry.name;
                seedSeriesFromName(row, series);
                if (!row.selectedSeries.isEmpty()) {
                    series.include = row.selectedSeries.contains(Integer.valueOf(series.index));
                }
                row.series.add(series);
            }
            if (!entries.isEmpty()) {
                row.seriesCount = entries.size();
            }
        }
        rebuildVisible();
        fireTableDataChanged();
    }

    private static void seedSeriesFromName(Row file, SeriesRow series) {
        NameParts parts = parseSeriesName(file, series);
        if (parts.strictMatch) {
            series.animalId = firstNonEmpty(parts.animal, file.animalId);
            series.hemisphere = firstNonEmpty(parts.hemisphere, file.hemisphere);
            series.region = firstNonEmpty(parts.region, file.region);
            series.condition = firstNonEmpty(parts.condition,
                    ConditionNameParser.detectCondition(series.animalId));
        } else {
            series.animalId = firstNonEmpty(parts.animal, file.animalId);
            series.hemisphere = file.hemisphere;
            series.region = file.region;
            series.condition = file.condition;
        }
    }

    private static NameParts parseSeriesName(Row file, SeriesRow series) {
        String name = series == null ? "" : nullToEmpty(series.name).trim();
        NameParts direct = ImageNameParser.parse(name);
        if (direct.strictMatch || name.isEmpty() || name.indexOf(" - ") >= 0) {
            return direct;
        }
        return ImageNameParser.parse(seriesParseSeed(file, series));
    }

    private static String seriesParseSeed(Row file, SeriesRow series) {
        String name = series == null ? "" : nullToEmpty(series.name).trim();
        if (name.isEmpty() || name.indexOf(" - ") >= 0) {
            return name;
        }
        File source = file == null ? null : file.source;
        String container = source == null ? "" : nullToEmpty(source.getName()).trim();
        return container.isEmpty() ? name : container + " - " + name;
    }

    /** Expand or collapse a file's per-series rows. */
    public void setExpanded(int rowIndex, boolean expanded) {
        int fileIndex = fileIndexAt(rowIndex);
        Row row = rows.get(fileIndex);
        if (row.expanded == expanded) return;
        row.expanded = expanded;
        rebuildVisible();
        fireTableDataChanged();
    }

    /** Bulk-assign a condition to a set of rendered rows (file or series level). */
    public void setConditionForRows(int[] rowIndexes, String condition) {
        setConditionForRows(rowIndexes, primaryAxisId(), condition);
    }

    // ── Multi-condition axes ───────────────────────────────────────────────

    /** Ordered condition-axis schema (defensive copy). Empty = single implicit "Condition". */
    public List<ConditionAxis> conditionAxes() {
        return new ArrayList<ConditionAxis>(conditionAxes);
    }

    /** Replace the condition-axis schema (de-duplicated by id). */
    public void setConditionAxes(List<ConditionAxis> axes) {
        conditionAxes.clear();
        if (axes != null) {
            for (ConditionAxis axis : axes) {
                if (axis == null) continue;
                addAxisIfMissing(axis);
            }
        }
        fireTableStructureChanged();
    }

    /** Add one condition axis to the visible condition block. */
    public void addConditionAxis(ConditionAxis axis) {
        if (axis == null || hasAxis(axis.id)) return;
        conditionAxes.add(axis);
        fireTableStructureChanged();
    }

    /** Remove a non-primary condition axis and its stored values. */
    public void removeConditionAxis(String axisId) {
        String norm = ConditionAxis.normaliseId(axisId);
        if (norm.isEmpty() || norm.equals(primaryAxisId())) return;
        boolean removed = false;
        for (int i = conditionAxes.size() - 1; i >= 0; i--) {
            if (conditionAxes.get(i).id.equals(norm)) {
                conditionAxes.remove(i);
                removed = true;
            }
        }
        if (!removed) return;
        for (Row row : rows) {
            row.conditions.remove(norm);
            for (SeriesRow series : row.series) {
                series.conditions.remove(norm);
            }
        }
        fireTableStructureChanged();
    }

    public int notesColumn() {
        return COL_CONDITION + conditionAxisCount();
    }

    public boolean isConditionColumn(int columnIndex) {
        return columnIndex >= COL_CONDITION && columnIndex < notesColumn();
    }

    public ConditionAxis conditionAxisAtColumn(int columnIndex) {
        if (!isConditionColumn(columnIndex)) return null;
        if (conditionAxes.isEmpty()) return DEFAULT_CONDITION_AXIS;
        return conditionAxes.get(columnIndex - COL_CONDITION);
    }

    public int conditionColumnForAxis(String axisId) {
        String norm = ConditionAxis.normaliseId(axisId);
        if (norm.isEmpty()) norm = primaryAxisId();
        if (conditionAxes.isEmpty()) {
            return "condition".equals(norm) ? COL_CONDITION : -1;
        }
        for (int i = 0; i < conditionAxes.size(); i++) {
            if (conditionAxes.get(i).id.equals(norm)) {
                return COL_CONDITION + i;
            }
        }
        return -1;
    }

    private String primaryAxisId() {
        return conditionAxes.isEmpty() ? "condition" : conditionAxes.get(0).id;
    }

    private int conditionAxisCount() {
        return conditionAxes.isEmpty() ? 1 : conditionAxes.size();
    }

    private boolean shouldPersistAxes() {
        if (conditionAxes.isEmpty()) return false;
        return !(conditionAxes.size() == 1 && "condition".equals(conditionAxes.get(0).id));
    }

    private boolean hasAxis(String axisId) {
        String norm = ConditionAxis.normaliseId(axisId);
        for (ConditionAxis axis : conditionAxes) {
            if (axis.id.equals(norm)) return true;
        }
        return false;
    }

    private void addAxisIfMissing(ConditionAxis axis) {
        if (axis != null && !hasAxis(axis.id)) {
            conditionAxes.add(axis);
        }
    }

    /** Ordered, de-duplicated, non-blank values seen for one axis (dropdowns / merge). */
    public Set<String> distinctConditionValues(String axisId) {
        String norm = ConditionAxis.normaliseId(axisId);
        boolean primary = norm.equals(primaryAxisId());
        Set<String> out = new LinkedHashSet<String>();
        for (Row r : rows) {
            collectValue(out, primary ? r.condition : r.conditions.get(norm));
            for (SeriesRow s : r.series) {
                collectValue(out, primary ? s.condition : s.conditions.get(norm));
            }
        }
        return out;
    }

    /** Bulk-assign a value on one condition axis to a set of rendered rows. */
    public void setConditionForRows(int[] rowIndexes, String axisId, String value) {
        if (rowIndexes == null) return;
        String norm = ConditionAxis.normaliseId(axisId);
        if (norm.isEmpty()) norm = primaryAxisId();
        boolean primary = norm.equals(primaryAxisId());
        String v = value == null ? "" : value;
        int conditionColumn = conditionColumnForAxis(norm);
        for (int idx : rowIndexes) {
            if (idx < 0 || idx >= visible.size()) continue;
            SeriesRow series = seriesRowAt(idx);
            if (series != null) {
                if (primary) series.condition = v;
                else putOrRemove(series.conditions, norm, v);
            } else {
                Row row = rows.get(fileIndexAt(idx));
                if (isContainerSource(row.source)) continue;
                if (primary) {
                    row.condition = v;
                    for (SeriesRow s : row.series) s.condition = v;
                } else {
                    putOrRemove(row.conditions, norm, v);
                    for (SeriesRow s : row.series) putOrRemove(s.conditions, norm, v);
                }
            }
            if (conditionColumn >= 0) {
                fireTableCellUpdated(idx, conditionColumn);
            } else {
                fireTableRowsUpdated(idx, idx);
            }
        }
    }

    /**
     * Bulk-assign a fixed identity field (animal ID, hemisphere, or region) to a
     * set of rendered rows. Mirrors {@link #setConditionForRows(int[], String, String)}:
     * container header rows are skipped (their identity is not editable and is
     * derived from the per-series rows), and a file-level edit cascades to that
     * file's series rows so a mixed file+series selection stays consistent.
     *
     * @param column one of {@link #COL_ANIMAL}, {@link #COL_HEMISPHERE}, {@link #COL_REGION}
     */
    public void setIdentityForRows(int[] rowIndexes, int column, String value) {
        if (rowIndexes == null) return;
        if (column != COL_ANIMAL && column != COL_HEMISPHERE && column != COL_REGION) return;
        String v = value == null ? "" : value;
        for (int idx : rowIndexes) {
            if (idx < 0 || idx >= visible.size()) continue;
            SeriesRow series = seriesRowAt(idx);
            if (series != null) {
                setSeriesValue(series, v, column);
            } else {
                Row row = rows.get(fileIndexAt(idx));
                if (isContainerSource(row.source)) continue;
                setRowIdentity(row, column, v);
                for (SeriesRow s : row.series) setSeriesValue(s, v, column);
            }
            fireTableCellUpdated(idx, column);
        }
    }

    private void setRowIdentity(Row row, int column, String value) {
        switch (column) {
            case COL_ANIMAL:     row.animalId = value; break;
            case COL_HEMISPHERE: row.hemisphere = value; break;
            case COL_REGION:     row.region = value; break;
            default:             break;
        }
    }

    /** Complete axis-&gt;value map for persistence; empty for single-axis projects. */
    private Map<String, String> fullConditions(String primaryValue, Map<String, String> extras) {
        Map<String, String> full = new LinkedHashMap<String, String>();
        if (!shouldPersistAxes()) return full;   // implicit legacy axis -> legacy field only
        String primaryId = primaryAxisId();
        for (ConditionAxis axis : conditionAxes) {
            String value = axis.id.equals(primaryId)
                    ? primaryValue
                    : (extras == null ? "" : extras.get(axis.id));
            String trimmed = value == null ? "" : value.trim();
            if (!trimmed.isEmpty()) {
                full.put(axis.id, trimmed);
            }
        }
        return full;
    }

    /** Split a persisted axis-&gt;value map into extras + return the primary value. */
    private String applyLoadedConditions(Map<String, String> targetExtras,
                                         Map<String, String> source, String currentPrimary) {
        targetExtras.clear();
        String primary = nullToEmpty(currentPrimary);
        if (source == null) return primary;
        String primaryId = primaryAxisId();
        for (Map.Entry<String, String> e : source.entrySet()) {
            if (e.getKey() == null || e.getValue() == null) continue;
            if (e.getKey().equals(primaryId)) {
                if (primary.isEmpty()) primary = e.getValue();
            } else {
                targetExtras.put(e.getKey(), e.getValue());
            }
        }
        return primary;
    }

    private static void collectValue(Set<String> out, String value) {
        if (value != null && !value.trim().isEmpty()) out.add(value.trim());
    }

    private static void putOrRemove(Map<String, String> map, String key, String value) {
        if (value == null || value.trim().isEmpty()) map.remove(key);
        else map.put(key, value);
    }

    /** Serialise the current state to a {@link ProjectFile} ready for IO. */
    public ProjectFile toProjectFile(String name, String outputRoot, String writerId) {
        ProjectFile project = new ProjectFile();
        project.name = name == null ? "" : name;
        project.outputRoot = outputRoot == null ? "" : outputRoot;
        project.writerId = writerId == null ? "" : writerId;
        project.writtenAtMillis = System.currentTimeMillis();
        if (shouldPersistAxes()) {
            project.conditionAxes = conditionAxes();
        }
        for (Row row : rows) {
            ProjectFile.Item item = new ProjectFile.Item();
            item.path = row.source == null ? "" : row.source.getAbsolutePath();
            item.include = row.include;
            if (!isContainerSource(row.source)) {
                item.animalId = row.animalId;
                item.hemisphere = row.hemisphere;
                item.region = row.region;
                item.condition = row.condition;
                item.conditions = fullConditions(row.condition, row.conditions);
            } else {
                item.animalId = "";
                item.hemisphere = "";
                item.region = "";
                item.condition = "";
            }
            item.notes = row.notes;
            if (row.series.isEmpty()) {
                item.series.addAll(row.selectedSeries);
            } else {
                List<Integer> included = new ArrayList<Integer>();
                List<Integer> includedInSavedOrder = includedSeriesIndexesInSavedOrder(row);
                for (SeriesRow s : row.series) {
                    ProjectFile.SeriesItem meta = new ProjectFile.SeriesItem();
                    meta.index = s.index;
                    meta.include = s.include;
                    meta.name = s.name;
                    meta.animalId = s.animalId;
                    meta.hemisphere = s.hemisphere;
                    meta.region = s.region;
                    meta.condition = s.condition;
                    meta.conditions = fullConditions(s.condition, s.conditions);
                    meta.notes = s.notes;
                    item.seriesMeta.add(meta);
                    if (s.include) included.add(Integer.valueOf(s.index));
                }
                // Collapse "all included" to the empty "include all" sentinel.
                if (!row.selectedSeries.isEmpty() || included.size() != row.series.size()) {
                    item.series.addAll(includedInSavedOrder.isEmpty()
                            ? included
                            : includedInSavedOrder);
                }
            }
            project.items.add(item);
        }
        ProjectPathResolver.addRelativePathHints(project, new File(project.outputRoot));
        return project;
    }

    private static List<Integer> includedSeriesIndexesInSavedOrder(Row row) {
        List<Integer> ordered = new ArrayList<Integer>();
        if (row == null || row.series == null || row.series.isEmpty()) return ordered;
        if (row.selectedSeries != null && !row.selectedSeries.isEmpty()) {
            for (Integer saved : row.selectedSeries) {
                if (saved == null) continue;
                SeriesRow series = findSeriesByIndex(row.series, saved.intValue());
                if (series != null && series.include) {
                    ordered.add(Integer.valueOf(series.index));
                }
            }
            for (SeriesRow series : row.series) {
                if (series == null || !series.include) continue;
                Integer index = Integer.valueOf(series.index);
                if (!ordered.contains(index)) {
                    ordered.add(index);
                }
            }
            return ordered;
        }
        for (SeriesRow series : row.series) {
            if (series != null && series.include) {
                ordered.add(Integer.valueOf(series.index));
            }
        }
        return ordered;
    }

    private static SeriesRow findSeriesByIndex(List<SeriesRow> seriesRows, int index) {
        if (seriesRows == null) return null;
        for (SeriesRow series : seriesRows) {
            if (series != null && series.index == index) {
                return series;
            }
        }
        return null;
    }

    /** Populate the model from a previously saved {@link ProjectFile}. */
    public void loadFromProjectFile(ProjectFile project) {
        int oldColumnCount = getColumnCount();
        rows.clear();
        conditionAxes.clear();
        if (project != null && project.conditionAxes != null) {
            for (ConditionAxis axis : project.conditionAxes) {
                if (axis != null) addAxisIfMissing(axis);
            }
        }
        if (project != null && project.items != null) {
            for (ProjectFile.Item item : project.items) {
                if (item == null || item.path == null || item.path.isEmpty()) continue;
                Row row = new Row(new File(item.path));
                row.include = item.include;
                if (!isContainerSource(row.source)) {
                    row.animalId = nullToEmpty(item.animalId);
                    row.hemisphere = nullToEmpty(item.hemisphere);
                    row.region = nullToEmpty(item.region);
                    row.condition = nullToEmpty(item.condition);
                    row.condition = applyLoadedConditions(row.conditions, item.conditions, row.condition);
                }
                row.notes = nullToEmpty(item.notes);
                if (item.series != null) {
                    row.selectedSeries.addAll(item.series);
                }
                if (item.seriesMeta != null && !item.seriesMeta.isEmpty()) {
                    boolean hasExplicitSeriesSelection = !row.selectedSeries.isEmpty();
                    String loadedFileCondition = nullToEmpty(item.condition);
                    for (ProjectFile.SeriesItem meta : item.seriesMeta) {
                        if (meta == null) continue;
                        SeriesRow series = new SeriesRow(meta.index);
                        series.include = hasExplicitSeriesSelection
                                ? row.selectedSeries.contains(Integer.valueOf(meta.index))
                                : meta.include;
                        series.name = nullToEmpty(meta.name);
                        series.animalId = nullToEmpty(meta.animalId);
                        series.hemisphere = nullToEmpty(meta.hemisphere);
                        series.region = nullToEmpty(meta.region);
                        series.condition = nullToEmpty(meta.condition);
                        series.condition = applyLoadedConditions(series.conditions, meta.conditions, series.condition);
                        series.notes = nullToEmpty(meta.notes);
                        repairLoadedSeriesMetadata(row, series, loadedFileCondition);
                        row.series.add(series);
                    }
                    row.seriesCount = row.series.size();
                }
                rows.add(row);
            }
        }
        rebuildVisible();
        if (oldColumnCount != getColumnCount()) {
            fireTableStructureChanged();
        } else {
            fireTableDataChanged();
        }
    }

    private static void repairLoadedSeriesMetadata(Row file, SeriesRow series,
                                                   String loadedFileCondition) {
        if (file == null || series == null) return;
        String name = nullToEmpty(series.name).trim();
        if (name.isEmpty()) return;

        String existingAnimal = nullToEmpty(series.animalId).trim();
        String existingHemisphere = nullToEmpty(series.hemisphere).trim();
        String existingRegion = nullToEmpty(series.region).trim();
        String extractedSeriesName = ImageNameParser.extractBioFormatsSeriesName(name);
        boolean looksLikeOldUnparsedSeries =
                existingHemisphere.isEmpty()
                        && existingRegion.isEmpty()
                        && (existingAnimal.isEmpty()
                        || existingAnimal.equals(name)
                        || existingAnimal.equals(extractedSeriesName));
        if (!looksLikeOldUnparsedSeries) return;

        SeriesRow parsed = new SeriesRow(series.index);
        parsed.name = name;
        seedSeriesFromName(file, parsed);
        if (parsed.hemisphere.isEmpty() && parsed.region.isEmpty()) return;

        if (existingAnimal.isEmpty()
                || existingAnimal.equals(name)
                || existingAnimal.equals(extractedSeriesName)) {
            series.animalId = parsed.animalId;
        }
        if (existingHemisphere.isEmpty()) {
            series.hemisphere = parsed.hemisphere;
        }
        if (existingRegion.isEmpty()) {
            series.region = parsed.region;
        }

        String existingCondition = nullToEmpty(series.condition).trim();
        String fileCondition = firstNonEmpty(file.condition, loadedFileCondition).trim();
        if (existingCondition.isEmpty()
                || (!fileCondition.isEmpty() && existingCondition.equals(fileCondition))) {
            series.condition = parsed.condition;
        }
    }

    public List<Row> rowsView() {
        return Collections.unmodifiableList(rows);
    }

    // ── Visible-row bookkeeping ─────────────────────────────────────────────

    private void rebuildVisible() {
        visible.clear();
        for (int f = 0; f < rows.size(); f++) {
            visible.add(new View(f, -1));
            Row row = rows.get(f);
            if (row.expanded) {
                for (int s = 0; s < row.series.size(); s++) {
                    visible.add(new View(f, s));
                }
            }
        }
    }

    /** Rendered-row index of the given file's header row. */
    private int fileViewIndex(int fileIndex) {
        for (int v = 0; v < visible.size(); v++) {
            View view = visible.get(v);
            if (view.fileIndex == fileIndex && view.seriesPos < 0) {
                return v;
            }
        }
        return Math.max(0, visible.size() - 1);
    }

    // ── AbstractTableModel ──────────────────────────────────────────────────

    @Override
    public int getRowCount() {
        return visible.size();
    }

    @Override
    public int getColumnCount() {
        return FIXED_COLUMNS.length + conditionAxisCount() + 1;
    }

    @Override
    public String getColumnName(int column) {
        if (column >= 0 && column < FIXED_COLUMNS.length) {
            return FIXED_COLUMNS[column];
        }
        if (isConditionColumn(column)) {
            ConditionAxis axis = conditionAxisAtColumn(column);
            return axis == null || axis.label.isEmpty() ? "Condition" : axis.label;
        }
        if (column == notesColumn()) {
            return NOTES_COLUMN;
        }
        return "";
    }

    @Override
    public Class<?> getColumnClass(int column) {
        return column == COL_INCLUDE ? Boolean.class : String.class;
    }

    @Override
    public boolean isCellEditable(int rowIndex, int columnIndex) {
        if (!isSeriesRow(rowIndex)) {
            Row row = rows.get(fileIndexAt(rowIndex));
            if (isContainerSource(row.source) && isIdentityColumn(columnIndex)) {
                return false;
            }
        }
        if (isConditionColumn(columnIndex) || columnIndex == notesColumn()) {
            return true;
        }
        switch (columnIndex) {
            case COL_INCLUDE:
            case COL_ANIMAL:
            case COL_HEMISPHERE:
            case COL_REGION:
                return true;
            default:
                return false;
        }
    }

    @Override
    public Object getValueAt(int rowIndex, int columnIndex) {
        SeriesRow series = seriesRowAt(rowIndex);
        if (series != null) {
            if (isConditionColumn(columnIndex)) {
                return conditionValue(series.condition, series.conditions, columnIndex);
            }
            if (columnIndex == notesColumn()) {
                return series.notes;
            }
            switch (columnIndex) {
                case COL_INCLUDE:    return Boolean.valueOf(series.include);
                case COL_FILE:       return series.label();
                case COL_SERIES:     return "";
                case COL_ANIMAL:     return series.animalId;
                case COL_HEMISPHERE: return series.hemisphere;
                case COL_REGION:     return series.region;
                default:             return "";
            }
        }
        Row row = rows.get(fileIndexAt(rowIndex));
        if (isConditionColumn(columnIndex)) {
            return conditionValue(row.condition, row.conditions, columnIndex);
        }
        if (columnIndex == notesColumn()) {
            return row.notes;
        }
        switch (columnIndex) {
            case COL_INCLUDE:    return Boolean.valueOf(row.include);
            case COL_FILE:       return row.source == null ? "" : row.source.getName();
            case COL_SERIES:     return row.seriesDisplay();
            case COL_ANIMAL:     return row.animalId;
            case COL_HEMISPHERE: return row.hemisphere;
            case COL_REGION:     return row.region;
            default:             return "";
        }
    }

    @Override
    public void setValueAt(Object value, int rowIndex, int columnIndex) {
        SeriesRow series = seriesRowAt(rowIndex);
        if (series != null) {
            setSeriesValue(series, value, columnIndex);
            fireTableCellUpdated(rowIndex, columnIndex);
            // Series include changes alter the parent's "M of N" summary.
            if (columnIndex == COL_INCLUDE) {
                fireTableCellUpdated(fileViewIndex(fileIndexAt(rowIndex)), COL_SERIES);
            }
            return;
        }
        Row row = rows.get(fileIndexAt(rowIndex));
        if (isContainerSource(row.source) && isIdentityColumn(columnIndex)) {
            return;
        }
        boolean cascade = !row.series.isEmpty();
        if (isConditionColumn(columnIndex)) {
            setConditionValue(row, columnIndex, stringValue(value));
            if (cascade) {
                for (SeriesRow s : row.series) {
                    setConditionValue(s, columnIndex, stringValue(value));
                }
            }
        } else if (columnIndex == notesColumn()) {
            row.notes = stringValue(value);
            if (cascade) for (SeriesRow s : row.series) s.notes = row.notes;
        } else {
            switch (columnIndex) {
                case COL_INCLUDE:
                    row.include = value instanceof Boolean ? ((Boolean) value).booleanValue() : false;
                    break;
                case COL_ANIMAL:
                    row.animalId = stringValue(value);
                    if (cascade) for (SeriesRow s : row.series) s.animalId = row.animalId;
                    break;
                case COL_HEMISPHERE:
                    row.hemisphere = stringValue(value);
                    if (cascade) for (SeriesRow s : row.series) s.hemisphere = row.hemisphere;
                    break;
                case COL_REGION:
                    row.region = stringValue(value);
                    if (cascade) for (SeriesRow s : row.series) s.region = row.region;
                    break;
                default:
                    return;
            }
        }
        if (cascade && columnIndex != COL_INCLUDE && row.expanded) {
            // Refresh the now-cascaded series rows beneath this file.
            fireTableRowsUpdated(rowIndex, rowIndex + row.series.size());
        } else {
            fireTableCellUpdated(rowIndex, columnIndex);
        }
    }

    private void setSeriesValue(SeriesRow series, Object value, int columnIndex) {
        if (isConditionColumn(columnIndex)) {
            setConditionValue(series, columnIndex, stringValue(value));
            return;
        }
        if (columnIndex == notesColumn()) {
            series.notes = stringValue(value);
            return;
        }
        switch (columnIndex) {
            case COL_INCLUDE:
                series.include = value instanceof Boolean ? ((Boolean) value).booleanValue() : false;
                break;
            case COL_ANIMAL:
                series.animalId = stringValue(value);
                break;
            case COL_HEMISPHERE:
                series.hemisphere = stringValue(value);
                break;
            case COL_REGION:
                series.region = stringValue(value);
                break;
            default:
                break;
        }
    }

    private String conditionValue(String primaryValue, Map<String, String> extras, int columnIndex) {
        ConditionAxis axis = conditionAxisAtColumn(columnIndex);
        if (axis == null) return "";
        if (axis.id.equals(primaryAxisId())) {
            return nullToEmpty(primaryValue);
        }
        return extras == null ? "" : nullToEmpty(extras.get(axis.id));
    }

    private void setConditionValue(Row row, int columnIndex, String value) {
        ConditionAxis axis = conditionAxisAtColumn(columnIndex);
        if (axis == null) return;
        if (axis.id.equals(primaryAxisId())) {
            row.condition = value;
        } else {
            putOrRemove(row.conditions, axis.id, value);
        }
    }

    private void setConditionValue(SeriesRow series, int columnIndex, String value) {
        ConditionAxis axis = conditionAxisAtColumn(columnIndex);
        if (axis == null) return;
        if (axis.id.equals(primaryAxisId())) {
            series.condition = value;
        } else {
            putOrRemove(series.conditions, axis.id, value);
        }
    }

    private static String firstNonEmpty(String a, String b) {
        if (a != null && !a.trim().isEmpty()) return a;
        return b == null ? "" : b;
    }

    private static String stringValue(Object value) {
        return value == null ? "" : value.toString();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private boolean isIdentityColumn(int columnIndex) {
        return columnIndex == COL_ANIMAL
                || columnIndex == COL_HEMISPHERE
                || columnIndex == COL_REGION
                || isConditionColumn(columnIndex);
    }

    private static boolean isContainerSource(File source) {
        if (source == null || source.getName() == null) return false;
        String lower = source.getName().toLowerCase(Locale.ROOT);
        for (String ext : ImageSourceDispatcher.CONTAINER_EXTENSIONS) {
            if (lower.endsWith(ext)) return true;
        }
        return false;
    }

    private static String canonicalise(File file) {
        if (file == null) return "";
        try {
            return file.getCanonicalPath().toLowerCase(java.util.Locale.ROOT);
        } catch (java.io.IOException e) {
            return file.getAbsolutePath().toLowerCase(java.util.Locale.ROOT);
        }
    }
}
