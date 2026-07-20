package flash.pipeline.runrecord.ui;

import flash.pipeline.runrecord.RunDiff;
import flash.pipeline.runrecord.RunRecord;
import flash.pipeline.ui.FlashTheme;

import javax.swing.BorderFactory;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.table.AbstractTableModel;
import java.awt.BorderLayout;
import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Side-by-side parent/child run diff view. */
public final class RunDiffPanel extends JPanel {
    private final DiffTableModel diffModel;

    public RunDiffPanel(RunRecord parent, RunRecord child) {
        super(new BorderLayout(0, 8));
        setBackground(FlashTheme.SURFACE);
        setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        diffModel = new DiffTableModel(RunDiff.diff(parent, child));
        JTable table = new JTable(diffModel);
        table.setFillsViewportHeight(true);
        table.setRowHeight(22);
        table.getColumnModel().getColumn(0).setPreferredWidth(80);
        table.getColumnModel().getColumn(1).setPreferredWidth(210);
        table.getColumnModel().getColumn(2).setPreferredWidth(230);
        table.getColumnModel().getColumn(3).setPreferredWidth(230);

        JTextArea ioSummary = new JTextArea(inputOutputDelta(parent, child));
        ioSummary.setEditable(false);
        ioSummary.setLineWrap(true);
        ioSummary.setWrapStyleWord(true);
        ioSummary.setBackground(FlashTheme.SURFACE_RAISED);
        ioSummary.setFont(FlashTheme.mono(12f));

        JScrollPane diffScroll = new JScrollPane(table);
        diffScroll.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createLineBorder(FlashTheme.BORDER), "Parameters"));
        JScrollPane ioScroll = new JScrollPane(ioSummary);
        ioScroll.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createLineBorder(FlashTheme.BORDER), "Inputs / outputs"));

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, diffScroll, ioScroll);
        split.setResizeWeight(0.72);
        split.setBorder(BorderFactory.createEmptyBorder());
        add(split, BorderLayout.CENTER);
    }

    public int diffCountForTests() {
        return diffModel.getRowCount();
    }

    private static String inputOutputDelta(RunRecord parent, RunRecord child) {
        StringBuilder out = new StringBuilder();
        appendDelta(out, "Inputs", inputPaths(parent), inputPaths(child));
        out.append('\n');
        appendDelta(out, "Outputs", outputPaths(parent), outputPaths(child));
        return out.toString();
    }

    private static void appendDelta(StringBuilder out, String label,
                                    Set<String> parent, Set<String> child) {
        Set<String> added = new LinkedHashSet<String>(child);
        added.removeAll(parent);
        Set<String> removed = new LinkedHashSet<String>(parent);
        removed.removeAll(child);
        out.append(label).append('\n');
        out.append("  Parent: ").append(parent.size())
                .append("   Child: ").append(child.size()).append('\n');
        out.append("  Added: ").append(joinFileNames(added)).append('\n');
        out.append("  Removed: ").append(joinFileNames(removed)).append('\n');
    }

    private static Set<String> inputPaths(RunRecord record) {
        Set<String> out = new LinkedHashSet<String>();
        if (record != null && record.inputs != null) {
            for (RunRecord.InputItem input : record.inputs) {
                if (input != null && input.path != null && !input.path.trim().isEmpty()) {
                    out.add(input.path);
                }
            }
        }
        return out;
    }

    private static Set<String> outputPaths(RunRecord record) {
        Set<String> out = new LinkedHashSet<String>();
        if (record != null && record.outputs != null) {
            for (RunRecord.OutputItem output : record.outputs) {
                if (output != null && output.path != null && !output.path.trim().isEmpty()) {
                    out.add(output.path);
                }
            }
        }
        return out;
    }

    private static String joinFileNames(Set<String> paths) {
        if (paths == null || paths.isEmpty()) {
            return "(none)";
        }
        List<String> names = new ArrayList<String>();
        for (String path : paths) {
            names.add(new File(path).getName());
        }
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < names.size(); i++) {
            if (i > 0) {
                out.append(", ");
            }
            out.append(names.get(i));
        }
        return out.toString();
    }

    private static final class DiffTableModel extends AbstractTableModel {
        private final String[] columns = {"Change", "Parameter", "Parent", "Child"};
        private final List<RunDiff.DiffEntry> rows;

        DiffTableModel(List<RunDiff.DiffEntry> rows) {
            this.rows = rows == null
                    ? new ArrayList<RunDiff.DiffEntry>()
                    : new ArrayList<RunDiff.DiffEntry>(rows);
        }

        public int getRowCount() {
            return rows.size();
        }

        public int getColumnCount() {
            return columns.length;
        }

        @Override
        public String getColumnName(int column) {
            return columns[column];
        }

        public Object getValueAt(int rowIndex, int columnIndex) {
            RunDiff.DiffEntry row = rows.get(rowIndex);
            switch (columnIndex) {
                case 0:
                    return row.kind().name().toLowerCase(java.util.Locale.ROOT);
                case 1:
                    return row.path();
                case 2:
                    return String.valueOf(row.parentValue());
                case 3:
                    return String.valueOf(row.childValue());
                default:
                    return "";
            }
        }
    }
}
