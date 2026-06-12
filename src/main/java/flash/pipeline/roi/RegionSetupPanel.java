package flash.pipeline.roi;

import flash.pipeline.ui.wizard.RegionTableCellEditor;

import javax.swing.DefaultCellEditor;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.TableColumn;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Setup-dialog component (Dialog 1) that collects the list of regions to draw in a single
 * Draw ROIs pass, each with its own drawing channel. A {@code Region | Channel} table with
 * Add / Remove controls; the brightness/display-adjustment mode stays a single session-wide
 * choice (it is display-only and never affects saved ROI geometry), so it is collected by the
 * host dialog, not per region.
 *
 * <p>Embedded into the host {@code PipelineDialog} via {@code addComponent}; the host reads
 * {@link #rows()} after the dialog is confirmed and converts them with {@link #toSpecs}.
 * Only the pure {@link #toSpecs} logic is unit-tested; the Swing wiring is exercised manually.
 */
public final class RegionSetupPanel extends JPanel {

    private final Model model;
    private final JTable table;

    public RegionSetupPanel(String[] channelChoices, String defaultChannelChoice,
                            String seedRegionName) {
        String[] choices = channelChoices == null ? new String[0] : channelChoices;
        String defaultChoice = (defaultChannelChoice == null || defaultChannelChoice.isEmpty())
                ? (choices.length > 0 ? choices[0] : "1")
                : defaultChannelChoice;
        this.model = new Model(defaultChoice);
        model.addRow(seedRegionName == null ? "" : seedRegionName.trim(), defaultChoice);

        setLayout(new BorderLayout(0, 4));
        this.table = new JTable(model);
        // Commit an in-progress cell edit when focus leaves the table (e.g. the user clicks the
        // dialog's Draw button without pressing Enter), so rows() never reads a stale value.
        table.putClientProperty("terminateEditOnFocusLost", Boolean.TRUE);
        table.setRowHeight(22);
        table.getColumnModel().getColumn(0).setPreferredWidth(220);
        table.getColumnModel().getColumn(0).setCellEditor(new RegionTableCellEditor());
        if (choices.length > 0) {
            TableColumn channelColumn = table.getColumnModel().getColumn(1);
            channelColumn.setCellEditor(
                    new DefaultCellEditor(new JComboBox<String>(choices)));
        }
        JScrollPane scroll = new JScrollPane(table);
        scroll.setPreferredSize(new Dimension(440, 116));
        add(scroll, BorderLayout.CENTER);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        JButton add = new JButton("Add region");
        JButton remove = new JButton("Remove selected");
        add.addActionListener(e -> {
            if (table.isEditing()) table.getCellEditor().stopCellEditing();
            model.addRow("", defaultChoice);
        });
        remove.addActionListener(e -> {
            if (table.isEditing()) table.getCellEditor().stopCellEditing();
            int r = table.getSelectedRow();
            if (r >= 0) model.removeRow(r);
        });
        buttons.add(add);
        buttons.add(remove);
        add(buttons, BorderLayout.SOUTH);
    }

    /** Current rows as {@code {regionName, channelChoiceString}}, raw and unvalidated. */
    public List<String[]> rows() {
        // Flush any in-progress cell edit so a value typed but not yet committed (no Enter/Tab)
        // is captured rather than dropped.
        if (table.isEditing() && table.getCellEditor() != null) {
            table.getCellEditor().stopCellEditing();
        }
        return model.rowsCopy();
    }

    /** Recursively enable/disable the table and its controls (e.g. greyed out in append mode). */
    public void setRegionEditingEnabled(boolean enabled) {
        setEnabledTree(this, enabled);
    }

    private static void setEnabledTree(Component component, boolean enabled) {
        component.setEnabled(enabled);
        if (component instanceof Container) {
            for (Component child : ((Container) component).getComponents()) {
                setEnabledTree(child, enabled);
            }
        }
    }

    /** Parses a channel-choice string (e.g. {@code "2 (GFP)"}) into a 1-based channel index. */
    public interface ChannelParser {
        int parse(String channelChoice);
    }

    /**
     * Build validated {@link RegionDrawSpec}s from raw {@code {name, channelChoice}} rows.
     * Blank-name rows are dropped; region names are de-duplicated case-insensitively
     * (first occurrence wins). The session-wide {@code displayMode} is applied to every spec.
     * Returns an empty list if no usable region remains.
     */
    public static List<RegionDrawSpec> toSpecs(List<String[]> rawRows,
                                               ChannelParser channelParser,
                                               String displayMode) {
        List<RegionDrawSpec> specs = new ArrayList<RegionDrawSpec>();
        if (rawRows == null) return specs;
        Set<String> seen = new LinkedHashSet<String>();
        for (String[] row : rawRows) {
            if (row == null || row.length < 2) continue;
            String name = row[0] == null ? "" : row[0].trim();
            if (name.isEmpty()) continue;
            if (!seen.add(name.toLowerCase(Locale.ROOT))) continue; // duplicate region name
            int channel = channelParser == null ? 1 : channelParser.parse(row[1]);
            specs.add(new RegionDrawSpec(name, channel, displayMode));
        }
        return specs;
    }

    /** Two-column ({@code Region}, {@code Channel}) editable table model. */
    private static final class Model extends AbstractTableModel {
        private final List<String[]> data = new ArrayList<String[]>();
        private final String defaultChoice;

        Model(String defaultChoice) {
            this.defaultChoice = defaultChoice;
        }

        void addRow(String name, String channelChoice) {
            data.add(new String[]{name == null ? "" : name,
                    channelChoice == null ? defaultChoice : channelChoice});
            fireTableRowsInserted(data.size() - 1, data.size() - 1);
        }

        void removeRow(int row) {
            if (row < 0 || row >= data.size()) return;
            // Keep at least one region row so the session always has a target.
            if (data.size() == 1) {
                data.set(0, new String[]{"", defaultChoice});
                fireTableRowsUpdated(0, 0);
                return;
            }
            data.remove(row);
            fireTableRowsDeleted(row, row);
        }

        List<String[]> rowsCopy() {
            List<String[]> copy = new ArrayList<String[]>(data.size());
            for (String[] r : data) copy.add(new String[]{r[0], r[1]});
            return copy;
        }

        @Override public int getRowCount() { return data.size(); }
        @Override public int getColumnCount() { return 2; }

        @Override public String getColumnName(int col) {
            return col == 0 ? "Region" : "Channel";
        }

        @Override public boolean isCellEditable(int row, int col) { return true; }

        @Override public Object getValueAt(int row, int col) {
            return data.get(row)[col];
        }

        @Override public void setValueAt(Object value, int row, int col) {
            if (row < 0 || row >= data.size() || col < 0 || col > 1) return;
            data.get(row)[col] = value == null ? "" : value.toString();
            fireTableCellUpdated(row, col);
        }
    }
}
