package flash.pipeline.ui.wizard;

import ij.IJ;
import flash.pipeline.io.ConditionManifestIO;
import flash.pipeline.naming.ConditionAssignments;
import flash.pipeline.naming.ConditionAxis;
import flash.pipeline.naming.ConditionMatrixCopy;
import flash.pipeline.ui.FlashTheme;
import flash.pipeline.ui.NextStepLabels;

import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.event.TableModelEvent;
import javax.swing.event.TableModelListener;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Shared editable condition-assignment review table. Extracted from
 * {@code StatisticalAnalysis} so {@code MasterAggregationAnalysis} and the other
 * condition-review entry points can reuse the same review UX.
 *
 * <p>Two layouts:
 * <ul>
 *   <li><b>Single-axis</b> (the default, and every legacy project): two columns
 *       {@code Animal Name | Condition}, the condition being edited directly.</li>
 *   <li><b>Multi-axis</b> (a condition matrix, e.g. Genotype &times; Timepoint):
 *       {@code Animal | Genotype | Timepoint | … | Combined group}. Each axis
 *       column is edited independently; the read-only <i>Combined group</i>
 *       column is derived live and is exactly what statistics and exports group
 *       by.</li>
 * </ul>
 *
 * <p>Use {@link #forProject(String, Set, String, String[], int)} to build the
 * right layout automatically from the project's {@code Conditions.csv}. The
 * legacy {@code new ConditionManifestPanel(animals, prefill)} constructors stay
 * single-axis for back-compat. Persist edits with
 * {@link #persist(String, ConditionManifestPanel)}, which writes per-axis for a
 * matrix project and composite-only for a single-axis project. Rows whose value
 * is blank are highlighted to surface unassigned animals.
 */
public final class ConditionManifestPanel {

    private static final java.awt.Color UNASSIGNED_BG = FlashTheme.TABLE_REQUIRED_BG;

    static final String DEFAULT_INTRO_HTML =
            "<html><body style='width:420px'>"
            + "<b>Condition assignment review</b><br>"
            + "Edit the Condition column to assign animals to experimental groups."
            + " Rows highlighted in pink need review: if saved blank, FLASH may fall"
            + " back to filename auto-detection, and animals it cannot resolve are left"
            + " out of condition-level outputs."
            + "</body></html>";

    static final String MULTI_AXIS_INTRO_HTML =
            "<html><body style='width:480px'>"
            + "<b>Condition matrix review</b><br>"
            + "Edit each condition axis column (e.g. Genotype, Timepoint) to assign"
            + " animals to groups. The <i>Combined group</i> column is derived"
            + " automatically and is what statistics and exports group by. Pink cells"
            + " are unassigned."
            + "</body></html>";

    private final DefaultTableModel model;
    private final JTable table;
    private final JPanel component;

    /** True when the table carries one column per condition axis (a matrix project). */
    private final boolean multiAxis;
    /** Axis schema in matrix mode (empty in single-axis mode). */
    private final List<ConditionAxis> axes;
    /**
     * Full resolved model in matrix mode (carries every animal, including any not
     * shown in this review set), so {@link #collectAssignmentsModel()} can preserve
     * unedited animals when writing back. {@code null} in single-axis mode.
     */
    private final ConditionAssignments seedModel;

    public ConditionManifestPanel(Set<String> animals, Map<String, String> prefill) {
        this(animals, prefill, null);
    }

    /**
     * @param introHtml optional caller-specific instructions (HTML). When
     *                  {@code null} or blank the shared default copy is used.
     */
    public ConditionManifestPanel(Set<String> animals, Map<String, String> prefill, String introHtml) {
        this(animals, prefill, introHtml, null, -1);
    }

    public ConditionManifestPanel(Set<String> animals, Map<String, String> prefill, String introHtml,
                                  String[] workflowSteps, int workflowActiveIndex) {
        if (animals == null) {
            throw new IllegalArgumentException("animals is required.");
        }
        this.multiAxis = false;
        this.axes = new java.util.ArrayList<ConditionAxis>();
        this.seedModel = null;

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

        this.component = buildComponent(animals.size(), introHtml, workflowSteps, workflowActiveIndex,
                450, 2);
    }

    /** Matrix-mode constructor: one editable column per axis plus a derived Combined group. */
    private ConditionManifestPanel(Set<String> animals, ConditionAssignments seed, String introHtml,
                                   String[] workflowSteps, int workflowActiveIndex) {
        if (animals == null) {
            throw new IllegalArgumentException("animals is required.");
        }
        this.multiAxis = true;
        this.seedModel = seed;
        this.axes = seed.axes();
        final int axisCount = axes.size();

        String[] columnNames = new String[2 + axisCount];
        columnNames[0] = "Animal";
        for (int a = 0; a < axisCount; a++) {
            columnNames[1 + a] = ConditionMatrixCopy.axisLabel(axes.get(a));
        }
        columnNames[1 + axisCount] = "Combined group";

        Object[][] data = new Object[animals.size()][2 + axisCount];
        int r = 0;
        for (String animal : animals) {
            data[r][0] = animal;
            for (int a = 0; a < axisCount; a++) {
                data[r][1 + a] = seed.get(animal, axes.get(a).id);
            }
            data[r][1 + axisCount] = seed.composite(animal, "_");
            r++;
        }

        final int firstAxisCol = 1;
        final int lastAxisCol = axisCount;            // inclusive
        final int combinedCol = 1 + axisCount;
        this.model = new DefaultTableModel(data, columnNames) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return column >= firstAxisCol && column <= lastAxisCol;
            }
        };
        // Recompute the derived Combined group whenever an axis cell is edited.
        this.model.addTableModelListener(new TableModelListener() {
            @Override
            public void tableChanged(TableModelEvent e) {
                if (e.getType() != TableModelEvent.UPDATE) return;
                int col = e.getColumn();
                if (col < firstAxisCol || col > lastAxisCol) return;   // ignore the derived col itself
                int row = e.getFirstRow();
                if (row < 0 || row >= model.getRowCount()) return;
                String combined = combinedFor(row);
                Object current = model.getValueAt(row, combinedCol);
                if (!combined.equals(current == null ? "" : current.toString())) {
                    model.setValueAt(combined, row, combinedCol);
                }
            }
        });

        this.table = new JTable(model);
        table.setRowHeight(24);
        table.getColumnModel().getColumn(0).setPreferredWidth(160);
        for (int a = 0; a < axisCount; a++) {
            table.getColumnModel().getColumn(1 + a).setPreferredWidth(120);
        }
        table.getColumnModel().getColumn(combinedCol).setPreferredWidth(180);
        table.setDefaultRenderer(Object.class, new NullHighlightRenderer());

        int width = 200 + axisCount * 130;
        this.component = buildComponent(animals.size(), introHtml, workflowSteps, workflowActiveIndex,
                Math.min(900, Math.max(460, width)), 2 + axisCount);
    }

    private JPanel buildComponent(int rowCount, String introHtml, String[] workflowSteps,
                                  int workflowActiveIndex, int preferredWidth, int columnCount) {
        JScrollPane scrollPane = new JScrollPane(table);
        scrollPane.setPreferredSize(new Dimension(preferredWidth,
                Math.min(400, 40 + rowCount * 25)));

        JPanel panel = new JPanel(new BorderLayout(8, 8));
        panel.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));

        String intro = introHtml != null && !introHtml.trim().isEmpty()
                ? introHtml
                : (multiAxis ? MULTI_AXIS_INTRO_HTML : DEFAULT_INTRO_HTML);
        JLabel instructions = new JLabel(intro);
        JComponent tracker = workflowRow(workflowSteps, workflowActiveIndex);
        if (tracker != null) {
            JPanel header = new JPanel(new BorderLayout(0, 6));
            header.setOpaque(false);
            header.add(tracker, BorderLayout.NORTH);
            header.add(instructions, BorderLayout.CENTER);
            panel.add(header, BorderLayout.NORTH);
        } else {
            panel.add(instructions, BorderLayout.NORTH);
        }
        panel.add(scrollPane, BorderLayout.CENTER);
        return panel;
    }

    /**
     * Build the panel for a project, choosing the matrix layout automatically when
     * {@code Conditions.csv} carries two or more condition axes. A single-axis (or
     * absent) manifest yields the legacy two-column composite table.
     */
    public static ConditionManifestPanel forProject(String directory, Set<String> animals,
                                                    String introHtml, String[] workflowSteps,
                                                    int workflowActiveIndex) {
        Set<String> safeAnimals = animals == null ? new LinkedHashSet<String>() : animals;
        ConditionAssignments model = directory == null
                ? new ConditionAssignments()
                : ConditionManifestIO.resolveAssignmentsModel(directory, safeAnimals);
        if (ConditionMatrixCopy.isMulti(model.axes())) {
            return new ConditionManifestPanel(safeAnimals, model, introHtml,
                    workflowSteps, workflowActiveIndex);
        }
        LinkedHashMap<String, String> prefill = new LinkedHashMap<String, String>();
        for (String animal : safeAnimals) {
            if (animal == null) continue;
            prefill.put(animal, model.composite(animal, "_"));
        }
        return new ConditionManifestPanel(safeAnimals, prefill, introHtml,
                workflowSteps, workflowActiveIndex);
    }

    /** Build a matrix panel directly from a resolved model (test seam; no disk read). */
    static ConditionManifestPanel forMatrixModel(Set<String> animals, ConditionAssignments model) {
        return new ConditionManifestPanel(animals, model, null, null, -1);
    }

    /** True when this panel edits one column per condition axis (a matrix project). */
    public boolean isMultiAxis() {
        return multiAxis;
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

    /** Derived combined-group label for a matrix row: axis values joined in order, blanks skipped. */
    private String combinedFor(int row) {
        StringBuilder sb = new StringBuilder();
        for (int a = 0; a < axes.size(); a++) {
            String s = stringAt(row, 1 + a);
            if (s.isEmpty()) continue;
            if (sb.length() > 0) sb.append('_');
            sb.append(s);
        }
        return sb.toString();
    }

    private String stringAt(int row, int column) {
        Object v = model.getValueAt(row, column);
        return v == null ? "" : v.toString().trim();
    }

    private void stopEditing() {
        if (table.isEditing() && table.getCellEditor() != null) {
            table.getCellEditor().stopCellEditing();
        }
    }

    /**
     * Stops any active edit and returns the current animal -&gt; condition map
     * preserving insertion order. In matrix mode the value is the derived
     * composite (Combined group) so callers expecting a single grouping label are
     * unchanged.
     */
    public LinkedHashMap<String, String> collectAssignments() {
        stopEditing();
        LinkedHashMap<String, String> assignments = new LinkedHashMap<String, String>();
        for (int r = 0; r < model.getRowCount(); r++) {
            String animal = stringAt(r, 0);
            if (animal.isEmpty()) continue;
            assignments.put(animal, multiAxis ? combinedFor(r) : stringAt(r, 1));
        }
        return assignments;
    }

    /**
     * Collect the full N-axis model from the table (matrix mode). Animals present
     * in the seed model but not shown in this review set are preserved; shown
     * animals take their values from the table. In single-axis mode this returns a
     * one-axis model built from {@link #collectAssignments()}.
     */
    public ConditionAssignments collectAssignmentsModel() {
        stopEditing();
        if (!multiAxis) {
            return ConditionAssignments.ofLegacy(collectAssignments());
        }
        ConditionAssignments out = new ConditionAssignments();
        for (ConditionAxis axis : axes) {
            out.addAxis(axis);
        }
        LinkedHashSet<String> shown = new LinkedHashSet<String>();
        for (int r = 0; r < model.getRowCount(); r++) {
            String animal = stringAt(r, 0);
            if (animal.isEmpty()) continue;
            shown.add(animal);
            for (int a = 0; a < axes.size(); a++) {
                String v = stringAt(r, 1 + a);
                if (!v.isEmpty()) out.put(animal, axes.get(a).id, v);
            }
        }
        // Preserve animals carried by the manifest but absent from this review set.
        if (seedModel != null) {
            for (String animal : seedModel.animals()) {
                if (shown.contains(animal)) continue;
                for (ConditionAxis axis : axes) {
                    String v = seedModel.get(animal, axis.id);
                    if (v != null && !v.trim().isEmpty()) {
                        out.put(animal, axis.id, v.trim());
                    }
                }
            }
        }
        return out;
    }

    /**
     * Persist a panel's edits to the project's condition manifest, choosing the
     * right writer for its layout: a matrix panel writes per-axis
     * {@code Condition_<axis>} columns; a single-axis panel writes the composite
     * column without ever collapsing an existing multi-axis file.
     *
     * @return {@code true} if anything was written.
     */
    public static boolean persist(String directory, ConditionManifestPanel panel) {
        if (directory == null || panel == null) return false;
        try {
            if (panel.multiAxis) {
                ConditionManifestIO.saveAssignments(directory, panel.collectAssignmentsModel());
                return true;
            }
            return ConditionManifestIO.saveAssignmentsPreservingMultiAxis(
                    directory, panel.collectAssignments());
        } catch (Exception e) {
            IJ.log("Warning: could not save condition assignments: " + e.getMessage());
            return false;
        }
    }

    /**
     * Convenience dialog for callers that only need the condition-assignment
     * step with a next-action button and Cancel. Returns the edited
     * assignments when the next action is confirmed, or {@code null} if the
     * user cancelled. Confirmed assignments are persisted to the project's
     * condition manifest.
     */
    public static LinkedHashMap<String, String> showDialog(String directory,
                                                           Set<String> animals,
                                                           Map<String, String> prefill,
                                                           String dialogTitle) {
        return showDialog(null, directory, animals, prefill, dialogTitle,
                NextStepLabels.SELECT_REPRESENTATIVES, null);
    }

    /**
     * Configurable variant for callers other than Representative Figure. The
     * primary-button text and instructions are caller-supplied so later condition
     * review entry points (aggregation, statistics, Excel, diagnostics) are no
     * longer tied to {@link NextStepLabels#SELECT_REPRESENTATIVES}.
     */
    public static LinkedHashMap<String, String> showDialog(java.awt.Component parent,
                                                           String directory,
                                                           Set<String> animals,
                                                           Map<String, String> prefill,
                                                           String dialogTitle,
                                                           String primaryButtonText,
                                                           String introHtml) {
        return showDialog(parent, directory, animals, prefill, dialogTitle, primaryButtonText,
                introHtml, null, -1);
    }

    public static LinkedHashMap<String, String> showDialog(java.awt.Component parent,
                                                           String directory,
                                                           Set<String> animals,
                                                           Map<String, String> prefill,
                                                           String dialogTitle,
                                                           String primaryButtonText,
                                                           String introHtml,
                                                           String[] workflowSteps,
                                                           int workflowActiveIndex) {
        // Build the axis-aware layout from the project when we have a directory; fall
        // back to the supplied composite prefill only when there is no project context.
        ConditionManifestPanel panel = directory != null
                ? forProject(directory, animals, introHtml, workflowSteps, workflowActiveIndex)
                : new ConditionManifestPanel(animals, prefill, introHtml, workflowSteps, workflowActiveIndex);
        String primary = primaryButtonText == null || primaryButtonText.trim().isEmpty()
                ? NextStepLabels.SELECT_REPRESENTATIVES
                : primaryButtonText;
        Object[] options = new Object[]{primary, "Cancel"};
        int result = JOptionPane.showOptionDialog(
                parent,
                panel.getComponent(),
                dialogTitle == null ? "Condition Assignment" : dialogTitle,
                JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE,
                null,
                options,
                options[0]);
        if (result != JOptionPane.OK_OPTION) return null;

        LinkedHashMap<String, String> assignments = panel.collectAssignments();
        if (directory != null) {
            persist(directory, panel);
        }
        return assignments;
    }

    private static JComponent workflowRow(String[] steps, int activeIndex) {
        if (steps == null || steps.length == 0) {
            return null;
        }
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        row.setOpaque(false);
        int visibleIndex = 0;
        for (int i = 0; i < steps.length; i++) {
            String step = steps[i] == null ? "" : steps[i].trim();
            if (step.isEmpty()) {
                continue;
            }
            if (visibleIndex > 0) {
                row.add(workflowSeparator());
            }
            row.add(workflowChip(step, i == activeIndex));
            visibleIndex++;
        }
        return visibleIndex == 0 ? null : row;
    }

    private static JLabel workflowChip(String text, boolean active) {
        JLabel chip = new JLabel(" " + text + " ");
        chip.setOpaque(true);
        chip.setFont(chip.getFont().deriveFont(active ? Font.BOLD : Font.PLAIN, 11f));
        chip.setBorder(BorderFactory.createLineBorder(FlashTheme.TEXT_HEADER, 1, true));
        chip.setBackground(active ? FlashTheme.TEXT_HEADER : FlashTheme.SURFACE);
        chip.setForeground(active ? FlashTheme.TEXT_ON_DARK : FlashTheme.TEXT_HEADER);
        return chip;
    }

    private static JLabel workflowSeparator() {
        JLabel label = new JLabel(">");
        label.setForeground(FlashTheme.TEXT_MUTED);
        label.setFont(label.getFont().deriveFont(Font.BOLD, 11f));
        return label;
    }

    private final class NullHighlightRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable table, Object value,
                                                       boolean isSelected, boolean hasFocus,
                                                       int row, int column) {
            Component c = super.getTableCellRendererComponent(
                    table, value, isSelected, hasFocus, row, column);
            boolean highlight;
            if (multiAxis) {
                // Highlight a blank axis cell (columns 1..axisCount); never the
                // Animal or derived Combined group columns.
                highlight = column >= 1 && column <= axes.size()
                        && (value == null || value.toString().trim().isEmpty());
            } else {
                Object condValue = table.getModel().getValueAt(row, 1);
                highlight = condValue == null || condValue.toString().trim().isEmpty();
            }
            if (!isSelected) {
                c.setBackground(highlight ? UNASSIGNED_BG : table.getBackground());
                boolean combinedCol = multiAxis && column == axes.size() + 1;
                c.setForeground(combinedCol ? FlashTheme.TEXT_MUTED : table.getForeground());
            }
            return c;
        }
    }
}
