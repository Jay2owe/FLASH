package flash.pipeline.project;

import flash.pipeline.io.ImageSourceDispatcher;
import flash.pipeline.naming.ConditionNameParser;
import flash.pipeline.naming.ImageNameParser;
import flash.pipeline.naming.NameParts;

import javax.swing.table.AbstractTableModel;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

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
    public static final int COL_CONDITION = 6;
    public static final int COL_NOTES = 7;

    private static final String[] COLUMNS = {
            "Include", "File", "Series", "Animal ID", "Hemisphere", "Region", "Condition", "Notes"
    };

    /** File rows, in display order. */
    private final List<Row> rows = new ArrayList<Row>();
    /** Flattened file + expanded-series rows the JTable actually renders. */
    private final List<View> visible = new ArrayList<View>();

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
        if (rowIndexes == null) return;
        String value = condition == null ? "" : condition;
        for (int idx : rowIndexes) {
            if (idx < 0 || idx >= visible.size()) continue;
            SeriesRow series = seriesRowAt(idx);
            if (series != null) {
                series.condition = value;
            } else {
                Row row = rows.get(fileIndexAt(idx));
                if (isContainerSource(row.source)) {
                    continue;
                }
                row.condition = value;
                for (SeriesRow s : row.series) {
                    s.condition = value;
                }
            }
            fireTableCellUpdated(idx, COL_CONDITION);
        }
    }

    /** Serialise the current state to a {@link ProjectFile} ready for IO. */
    public ProjectFile toProjectFile(String name, String outputRoot, String writerId) {
        ProjectFile project = new ProjectFile();
        project.name = name == null ? "" : name;
        project.outputRoot = outputRoot == null ? "" : outputRoot;
        project.writerId = writerId == null ? "" : writerId;
        project.writtenAtMillis = System.currentTimeMillis();
        for (Row row : rows) {
            ProjectFile.Item item = new ProjectFile.Item();
            item.path = row.source == null ? "" : row.source.getAbsolutePath();
            item.include = row.include;
            if (!isContainerSource(row.source)) {
                item.animalId = row.animalId;
                item.hemisphere = row.hemisphere;
                item.region = row.region;
                item.condition = row.condition;
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
        rows.clear();
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
        fireTableDataChanged();
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
        return COLUMNS.length;
    }

    @Override
    public String getColumnName(int column) {
        return COLUMNS[column];
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
        switch (columnIndex) {
            case COL_INCLUDE:
            case COL_ANIMAL:
            case COL_HEMISPHERE:
            case COL_REGION:
            case COL_CONDITION:
            case COL_NOTES:
                return true;
            default:
                return false;
        }
    }

    @Override
    public Object getValueAt(int rowIndex, int columnIndex) {
        SeriesRow series = seriesRowAt(rowIndex);
        if (series != null) {
            switch (columnIndex) {
                case COL_INCLUDE:    return Boolean.valueOf(series.include);
                case COL_FILE:       return series.label();
                case COL_SERIES:     return "";
                case COL_ANIMAL:     return series.animalId;
                case COL_HEMISPHERE: return series.hemisphere;
                case COL_REGION:     return series.region;
                case COL_CONDITION:  return series.condition;
                case COL_NOTES:      return series.notes;
                default:             return "";
            }
        }
        Row row = rows.get(fileIndexAt(rowIndex));
        switch (columnIndex) {
            case COL_INCLUDE:    return Boolean.valueOf(row.include);
            case COL_FILE:       return row.source == null ? "" : row.source.getName();
            case COL_SERIES:     return row.seriesDisplay();
            case COL_ANIMAL:     return row.animalId;
            case COL_HEMISPHERE: return row.hemisphere;
            case COL_REGION:     return row.region;
            case COL_CONDITION:  return row.condition;
            case COL_NOTES:      return row.notes;
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
            case COL_CONDITION:
                row.condition = stringValue(value);
                if (cascade) for (SeriesRow s : row.series) s.condition = row.condition;
                break;
            case COL_NOTES:
                row.notes = stringValue(value);
                if (cascade) for (SeriesRow s : row.series) s.notes = row.notes;
                break;
            default:
                return;
        }
        if (cascade && columnIndex != COL_INCLUDE && row.expanded) {
            // Refresh the now-cascaded series rows beneath this file.
            fireTableRowsUpdated(rowIndex, rowIndex + row.series.size());
        } else {
            fireTableCellUpdated(rowIndex, columnIndex);
        }
    }

    private static void setSeriesValue(SeriesRow series, Object value, int columnIndex) {
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
            case COL_CONDITION:
                series.condition = stringValue(value);
                break;
            case COL_NOTES:
                series.notes = stringValue(value);
                break;
            default:
                break;
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

    private static boolean isIdentityColumn(int columnIndex) {
        return columnIndex == COL_ANIMAL
                || columnIndex == COL_HEMISPHERE
                || columnIndex == COL_REGION
                || columnIndex == COL_CONDITION;
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
