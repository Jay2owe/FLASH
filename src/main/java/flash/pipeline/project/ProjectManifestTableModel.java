package flash.pipeline.project;

import flash.pipeline.naming.ImageNameParser;
import flash.pipeline.naming.NameParts;

import javax.swing.table.AbstractTableModel;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Backing JTable model for the Project Builder dialog. Holds one
 * {@link Row} per source file the user has staged, with live filename
 * parsing pre-filling animal/hemisphere/region/condition.
 *
 * <p>The model is intentionally view-agnostic and unit-testable: the dialog
 * supplies series count probing externally via {@link #setSeriesCount(int, int)}
 * so this class never touches Bio-Formats and runs cleanly under headless tests.
 *
 * <p>Series selection semantics: an empty {@link Row#selectedSeries} list
 * means "all series in the file" — the common case for single-series TIFFs
 * and unfiltered LIFs.
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

    private final List<Row> rows = new ArrayList<Row>();

    public static final class Row {
        public final File source;
        public boolean include = true;
        /** -1 until probed; >= 0 once series count is known. */
        public int seriesCount = -1;
        /** Empty list means "include all series" — the common case. */
        public final List<Integer> selectedSeries = new ArrayList<Integer>();
        public String animalId = "";
        public String hemisphere = "";
        public String region = "";
        public String condition = "";
        public String notes = "";

        Row(File source) {
            this.source = source;
        }

        /**
         * Display string for the Series column. Returns one of:
         * <ul>
         *   <li>"" — count not yet probed</li>
         *   <li>"all (N)" — selectedSeries empty and count known</li>
         *   <li>"M of N" — selectedSeries narrowed</li>
         *   <li>"1" — single-series file (count==1)</li>
         * </ul>
         */
        public String seriesDisplay() {
            if (seriesCount < 0) return "";
            if (seriesCount <= 1) return "1";
            if (selectedSeries.isEmpty()) return "all (" + seriesCount + ")";
            return selectedSeries.size() + " of " + seriesCount;
        }
    }

    /** Add a file, parsing its name for animal/hemisphere/region/condition. */
    public int addFile(File source) {
        Row row = new Row(source);
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
        rows.add(row);
        int idx = rows.size() - 1;
        fireTableRowsInserted(idx, idx);
        return idx;
    }

    public Row get(int rowIndex) {
        return rows.get(rowIndex);
    }

    public void removeRow(int rowIndex) {
        rows.remove(rowIndex);
        fireTableRowsDeleted(rowIndex, rowIndex);
    }

    public void clear() {
        int last = rows.size() - 1;
        rows.clear();
        if (last >= 0) {
            fireTableRowsDeleted(0, last);
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
        Row row = rows.get(rowIndex);
        row.seriesCount = seriesCount;
        fireTableCellUpdated(rowIndex, COL_SERIES);
    }

    /** Update which series are selected for a row. Empty list = include all. */
    public void setSelectedSeries(int rowIndex, List<Integer> selected) {
        Row row = rows.get(rowIndex);
        row.selectedSeries.clear();
        if (selected != null) {
            row.selectedSeries.addAll(selected);
        }
        fireTableCellUpdated(rowIndex, COL_SERIES);
    }

    /** Bulk-assign a condition to a set of rows. */
    public void setConditionForRows(int[] rowIndexes, String condition) {
        if (rowIndexes == null) return;
        String value = condition == null ? "" : condition;
        for (int idx : rowIndexes) {
            if (idx >= 0 && idx < rows.size()) {
                rows.get(idx).condition = value;
                fireTableCellUpdated(idx, COL_CONDITION);
            }
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
            item.animalId = row.animalId;
            item.hemisphere = row.hemisphere;
            item.region = row.region;
            item.condition = row.condition;
            item.notes = row.notes;
            item.series.addAll(row.selectedSeries);
            project.items.add(item);
        }
        ProjectPathResolver.addRelativePathHints(project, new File(project.outputRoot));
        return project;
    }

    /** Populate the model from a previously saved {@link ProjectFile}. */
    public void loadFromProjectFile(ProjectFile project) {
        clear();
        if (project == null || project.items == null) return;
        for (ProjectFile.Item item : project.items) {
            if (item == null || item.path == null || item.path.isEmpty()) continue;
            Row row = new Row(new File(item.path));
            row.include = item.include;
            row.animalId = nullToEmpty(item.animalId);
            row.hemisphere = nullToEmpty(item.hemisphere);
            row.region = nullToEmpty(item.region);
            row.condition = nullToEmpty(item.condition);
            row.notes = nullToEmpty(item.notes);
            if (item.series != null) {
                row.selectedSeries.addAll(item.series);
            }
            rows.add(row);
        }
        if (!rows.isEmpty()) {
            fireTableRowsInserted(0, rows.size() - 1);
        }
    }

    public List<Row> rowsView() {
        return Collections.unmodifiableList(rows);
    }

    // ── AbstractTableModel ──────────────────────────────────────────────────

    @Override
    public int getRowCount() {
        return rows.size();
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
        Row row = rows.get(rowIndex);
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
        Row row = rows.get(rowIndex);
        switch (columnIndex) {
            case COL_INCLUDE:
                row.include = value instanceof Boolean ? ((Boolean) value).booleanValue() : false;
                break;
            case COL_ANIMAL:
                row.animalId = stringValue(value);
                break;
            case COL_HEMISPHERE:
                row.hemisphere = stringValue(value);
                break;
            case COL_REGION:
                row.region = stringValue(value);
                break;
            case COL_CONDITION:
                row.condition = stringValue(value);
                break;
            case COL_NOTES:
                row.notes = stringValue(value);
                break;
            default:
                return;
        }
        fireTableCellUpdated(rowIndex, columnIndex);
    }

    private static String stringValue(Object value) {
        return value == null ? "" : value.toString();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
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
