package flash.pipeline.roi;

import flash.pipeline.io.DeferredImageSupplier;
import flash.pipeline.naming.ImageNameParser;
import flash.pipeline.naming.NameParts;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.table.AbstractTableModel;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Frame;
import java.awt.GraphicsEnvironment;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Stage 03 image picker: choose which images each region's ROI set is drawn on.
 *
 * <p>Rows are the project's image series with their region; columns include one checkbox per
 * region in the session. A series whose region matches a region name is auto-ticked under that
 * column, so the common case (filenames already carry the region) needs no interaction. A
 * multi-region image can be ticked under several columns.
 *
 * <p>The Region cell is editable: it is the image's anatomical label (seeded from the project
 * file or parsed from the filename). Edits are returned so the caller can write them back to
 * {@code project.json}. Editing the label does NOT re-tick the checkbox columns — those mean
 * "draw this region's ROI here" and stay under the user's control.
 *
 * <p>Headless / suppressed runs skip the dialog and return the auto-selection.
 */
public final class RegionImageSelectionDialog {

    private RegionImageSelectionDialog() {}

    /** Per-series picker row. {@link #seriesIndex} is zero-based. */
    public static final class Row {
        public final int seriesIndex;
        public final String title;
        public final String parsedRegion;

        Row(int seriesIndex, String title, String parsedRegion) {
            this.seriesIndex = seriesIndex;
            this.title = title;
            this.parsedRegion = parsedRegion;
        }
    }

    /** Picker outcome: per-region image selections plus the (possibly edited) region per series. */
    public static final class Result {
        public final LinkedHashMap<String, LinkedHashSet<Integer>> perRegion;
        /** seriesIndex -&gt; region label shown/edited in the picker. */
        public final LinkedHashMap<Integer, String> editedRegions;

        public Result(LinkedHashMap<String, LinkedHashSet<Integer>> perRegion,
                      LinkedHashMap<Integer, String> editedRegions) {
            this.perRegion = perRegion;
            this.editedRegions = editedRegions;
        }
    }

    /** Build picker rows (series index, title, region) from the image supplier. */
    public static List<Row> buildRows(DeferredImageSupplier supplier, int totalImages) {
        return buildRows(supplier, totalImages, null);
    }

    /**
     * Build picker rows, preferring a per-series region override (e.g. the authoritative region
     * from {@code project.json}, keyed by zero-based series index) over the filename-parsed
     * region when an override is present and non-empty.
     */
    public static List<Row> buildRows(DeferredImageSupplier supplier, int totalImages,
                                      Map<Integer, String> regionOverrides) {
        List<Row> rows = new ArrayList<Row>();
        for (int i = 0; i < totalImages; i++) {
            String title;
            try {
                title = supplier.getSeriesName(i);
            } catch (Exception e) {
                title = null;
            }
            if (title == null || title.trim().isEmpty()) title = "series " + (i + 1);
            String region = regionOverrides == null ? null : regionOverrides.get(Integer.valueOf(i));
            if (region == null || region.trim().isEmpty()) {
                NameParts parts = ImageNameParser.parse(title);
                region = (parts == null || parts.region == null) ? "" : parts.region.trim();
            } else {
                region = region.trim();
            }
            rows.add(new Row(i, title, region));
        }
        return rows;
    }

    /** Case-insensitive, trimmed match between a series' region and a region name. */
    public static boolean regionMatches(String parsedRegion, String regionName) {
        if (parsedRegion == null || regionName == null) return false;
        return parsedRegion.trim().equalsIgnoreCase(regionName.trim());
    }

    /** Auto-selection: each region gets the series whose region matches its name. */
    public static LinkedHashMap<String, LinkedHashSet<Integer>> autoSelect(
            List<Row> rows, List<String> regionNames) {
        LinkedHashMap<String, LinkedHashSet<Integer>> sel =
                new LinkedHashMap<String, LinkedHashSet<Integer>>();
        for (String r : regionNames) sel.put(r, new LinkedHashSet<Integer>());
        for (Row row : rows) {
            for (String r : regionNames) {
                if (regionMatches(row.parsedRegion, r)) sel.get(r).add(row.seriesIndex);
            }
        }
        return sel;
    }

    /**
     * Show the picker, or return the auto-selection when {@code autoSelectOnly} (headless /
     * suppressed). Returns a {@link Result}, or {@code null} if the user cancelled.
     */
    public static Result choose(List<Row> rows, List<String> regionNames, boolean autoSelectOnly) {
        LinkedHashMap<String, LinkedHashSet<Integer>> defaults = autoSelect(rows, regionNames);
        if (autoSelectOnly || GraphicsEnvironment.isHeadless()) {
            return new Result(defaults, regionsFromRows(rows));
        }

        final Model model = new Model(rows, regionNames, defaults);
        final JTable table = new JTable(model);
        table.setRowHeight(22);
        table.getColumnModel().getColumn(0).setMaxWidth(40);
        // Commit an in-progress cell edit when focus leaves the table (e.g. clicking Draw).
        table.putClientProperty("terminateEditOnFocusLost", Boolean.TRUE);

        final boolean[] confirmed = {false};
        final JDialog dialog = new JDialog((Frame) null, "Choose images for each region", true);
        dialog.setLayout(new BorderLayout());

        JLabel note = new JLabel("<html>Region is the image's anatomical label (editable, saved to the project). "
                + "The checkboxes pick which images each region's ROIs are drawn on.</html>");
        note.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
        dialog.add(note, BorderLayout.NORTH);

        JScrollPane scroll = new JScrollPane(table);
        scroll.setPreferredSize(new Dimension(720, 420));
        dialog.add(scroll, BorderLayout.CENTER);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton resetBtn = new JButton("Reset to matches");
        JButton clearBtn = new JButton("Clear all");
        JButton okBtn = new JButton("Draw");
        JButton cancelBtn = new JButton("Cancel");
        resetBtn.addActionListener(e -> model.resetToDefaults());
        clearBtn.addActionListener(e -> model.clearAll());
        okBtn.addActionListener(e -> {
            if (table.isEditing() && table.getCellEditor() != null) {
                table.getCellEditor().stopCellEditing();
            }
            confirmed[0] = true;
            dialog.dispose();
        });
        cancelBtn.addActionListener(e -> { confirmed[0] = false; dialog.dispose(); });
        buttons.add(resetBtn);
        buttons.add(clearBtn);
        buttons.add(cancelBtn);
        buttons.add(okBtn);
        dialog.add(buttons, BorderLayout.SOUTH);

        dialog.pack();
        dialog.setLocationRelativeTo(null);
        dialog.setVisible(true);

        if (!confirmed[0]) return null;
        return new Result(model.selection(), model.editedRegions());
    }

    private static LinkedHashMap<Integer, String> regionsFromRows(List<Row> rows) {
        LinkedHashMap<Integer, String> map = new LinkedHashMap<Integer, String>();
        for (Row r : rows) map.put(Integer.valueOf(r.seriesIndex), r.parsedRegion);
        return map;
    }

    /** Table model: fixed columns (#, Image, Region) then one Boolean column per region. */
    private static final class Model extends AbstractTableModel {
        private static final int FIXED = 3;
        private final List<Row> rows;
        private final List<String> regions;
        private final boolean[][] checked; // [rowIndex][regionIndex]
        private final boolean[][] defaults;
        private final String[] regionEdits; // editable Region cell per row

        Model(List<Row> rows, List<String> regions,
              LinkedHashMap<String, LinkedHashSet<Integer>> initial) {
            this.rows = rows;
            this.regions = regions;
            this.checked = new boolean[rows.size()][regions.size()];
            this.defaults = new boolean[rows.size()][regions.size()];
            this.regionEdits = new String[rows.size()];
            for (int r = 0; r < rows.size(); r++) {
                regionEdits[r] = rows.get(r).parsedRegion == null ? "" : rows.get(r).parsedRegion;
                int seriesIndex = rows.get(r).seriesIndex;
                for (int c = 0; c < regions.size(); c++) {
                    boolean on = initial.get(regions.get(c)).contains(seriesIndex);
                    checked[r][c] = on;
                    defaults[r][c] = on;
                }
            }
        }

        @Override public int getRowCount() { return rows.size(); }
        @Override public int getColumnCount() { return FIXED + regions.size(); }

        @Override public String getColumnName(int col) {
            if (col == 0) return "#";
            if (col == 1) return "Image";
            if (col == 2) return "Region";
            return regions.get(col - FIXED);
        }

        @Override public Class<?> getColumnClass(int col) {
            return col >= FIXED ? Boolean.class : String.class;
        }

        @Override public boolean isCellEditable(int row, int col) {
            return col == 2 || col >= FIXED;
        }

        @Override public Object getValueAt(int row, int col) {
            Row r = rows.get(row);
            if (col == 0) return r.seriesIndex + 1;
            if (col == 1) return r.title;
            if (col == 2) return regionEdits[row];
            return checked[row][col - FIXED];
        }

        @Override public void setValueAt(Object value, int row, int col) {
            if (col == 2) {
                regionEdits[row] = value == null ? "" : value.toString();
                fireTableCellUpdated(row, col);
                return;
            }
            if (col >= FIXED && value instanceof Boolean) {
                checked[row][col - FIXED] = (Boolean) value;
                fireTableCellUpdated(row, col);
            }
        }

        void resetToDefaults() {
            for (int r = 0; r < checked.length; r++) {
                System.arraycopy(defaults[r], 0, checked[r], 0, defaults[r].length);
            }
            fireTableDataChanged();
        }

        void clearAll() {
            for (boolean[] r : checked) java.util.Arrays.fill(r, false);
            fireTableDataChanged();
        }

        LinkedHashMap<String, LinkedHashSet<Integer>> selection() {
            LinkedHashMap<String, LinkedHashSet<Integer>> sel =
                    new LinkedHashMap<String, LinkedHashSet<Integer>>();
            for (int c = 0; c < regions.size(); c++) {
                LinkedHashSet<Integer> idx = new LinkedHashSet<Integer>();
                for (int r = 0; r < rows.size(); r++) {
                    if (checked[r][c]) idx.add(rows.get(r).seriesIndex);
                }
                sel.put(regions.get(c), idx);
            }
            return sel;
        }

        LinkedHashMap<Integer, String> editedRegions() {
            LinkedHashMap<Integer, String> map = new LinkedHashMap<Integer, String>();
            for (int r = 0; r < rows.size(); r++) {
                map.put(Integer.valueOf(rows.get(r).seriesIndex),
                        regionEdits[r] == null ? "" : regionEdits[r]);
            }
            return map;
        }
    }
}
