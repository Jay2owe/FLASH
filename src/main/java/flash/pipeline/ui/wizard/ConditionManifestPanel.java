package flash.pipeline.ui.wizard;

import ij.IJ;
import flash.pipeline.io.ConditionManifestIO;
import flash.pipeline.ui.FlashTheme;

import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Shared editable condition-assignment review table. Extracted from
 * {@code StatisticalAnalysis} so {@code MasterAggregationAnalysis} can reuse
 * the same review UX.
 * <p>
 * Callers instantiate the panel with an initial set of animals and optional
 * prefill (usually {@link ConditionManifestIO#resolveAssignments(String, Set)}),
 * embed {@link #getComponent()} wherever they want, and then call
 * {@link #collectAssignments()} after accepting edits. Rows whose condition is
 * blank are highlighted in red to surface unassigned animals.
 */
public final class ConditionManifestPanel {

    private static final java.awt.Color UNASSIGNED_BG = FlashTheme.TABLE_REQUIRED_BG;

    private final DefaultTableModel model;
    private final JTable table;
    private final JPanel component;

    public ConditionManifestPanel(Set<String> animals, Map<String, String> prefill) {
        if (animals == null) {
            throw new IllegalArgumentException("animals is required.");
        }
        Map<String, String> safePrefill = prefill == null
                ? new LinkedHashMap<String, String>()
                : prefill;

        String[] columnNames = {"Animal Name", "Condition"};
        Object[][] data = new Object[animals.size()][2];
        int i = 0;
        for (String animal : animals) {
            data[i][0] = animal;
            String cond = safePrefill.get(animal);
            data[i][1] = cond == null ? "" : cond;
            i++;
        }

        this.model = new DefaultTableModel(data, columnNames) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return column == 1;
            }
        };

        this.table = new JTable(model);
        table.setRowHeight(24);
        table.getColumnModel().getColumn(0).setPreferredWidth(200);
        table.getColumnModel().getColumn(1).setPreferredWidth(200);
        table.setDefaultRenderer(Object.class, new NullHighlightRenderer());

        JScrollPane scrollPane = new JScrollPane(table);
        scrollPane.setPreferredSize(new Dimension(450, Math.min(400, 40 + animals.size() * 25)));

        JPanel panel = new JPanel(new BorderLayout(8, 8));
        panel.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));

        JLabel instructions = new JLabel(
                "<html><body style='width:420px'>"
                + "<b>Condition assignment review</b><br>"
                + "Edit the Condition column to assign animals to experimental groups."
                + " Rows highlighted in pink have no condition and will be dropped by downstream analyses."
                + "</body></html>");
        panel.add(instructions, BorderLayout.NORTH);
        panel.add(scrollPane, BorderLayout.CENTER);

        this.component = panel;
    }

    /** Returns the JPanel to embed in a parent dialog. */
    public JComponent getComponent() {
        return component;
    }

    /** Direct access to the underlying table (for tests and advanced layouts). */
    public JTable getTable() {
        return table;
    }

    /** Direct access to the table model (for tests). */
    public DefaultTableModel getModel() {
        return model;
    }

    /**
     * Stops any active edit and returns the current animal -> condition map
     * preserving insertion order.
     */
    public LinkedHashMap<String, String> collectAssignments() {
        if (table.isEditing()) {
            table.getCellEditor().stopCellEditing();
        }
        LinkedHashMap<String, String> assignments = new LinkedHashMap<String, String>();
        for (int r = 0; r < model.getRowCount(); r++) {
            Object animalObj = model.getValueAt(r, 0);
            Object condObj = model.getValueAt(r, 1);
            String animal = animalObj == null ? "" : animalObj.toString().trim();
            String condition = condObj == null ? "" : condObj.toString().trim();
            if (animal.isEmpty()) continue;
            assignments.put(animal, condition);
        }
        return assignments;
    }

    /**
     * Convenience dialog for callers that only need the condition-assignment
     * step with OK / Cancel. Returns the edited assignments on OK, or
     * {@code null} if the user cancelled. On OK the assignments are persisted
     * to the project's condition manifest.
     */
    public static LinkedHashMap<String, String> showDialog(String directory,
                                                           Set<String> animals,
                                                           Map<String, String> prefill,
                                                           String dialogTitle) {
        ConditionManifestPanel panel = new ConditionManifestPanel(animals, prefill);
        int result = JOptionPane.showConfirmDialog(
                null, panel.getComponent(),
                dialogTitle == null ? "Condition Assignment" : dialogTitle,
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (result != JOptionPane.OK_OPTION) return null;

        LinkedHashMap<String, String> assignments = panel.collectAssignments();
        if (directory != null) {
            try {
                ConditionManifestIO.saveAssignments(directory, assignments);
            } catch (Exception e) {
                IJ.log("Warning: could not save condition assignments: " + e.getMessage());
            }
        }
        return assignments;
    }

    private static final class NullHighlightRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable table, Object value,
                                                       boolean isSelected, boolean hasFocus,
                                                       int row, int column) {
            Component c = super.getTableCellRendererComponent(
                    table, value, isSelected, hasFocus, row, column);
            Object condValue = table.getModel().getValueAt(row, 1);
            boolean unassigned = condValue == null || condValue.toString().trim().isEmpty();
            if (!isSelected) {
                c.setBackground(unassigned ? UNASSIGNED_BG : table.getBackground());
            }
            return c;
        }
    }
}
