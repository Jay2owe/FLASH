package flash.pipeline.runrecord.ui;

import flash.pipeline.runrecord.RunRecord;
import flash.pipeline.runrecord.RunRecordIO;
import flash.pipeline.runrecord.RunSummary;
import flash.pipeline.ui.FlashTheme;

import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTree;
import javax.swing.SwingConstants;
import javax.swing.border.EmptyBorder;
import javax.swing.table.AbstractTableModel;
import javax.swing.text.BadLocationException;
import javax.swing.text.DefaultHighlighter;
import javax.swing.text.Highlighter;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreeNode;
import java.awt.BorderLayout;
import java.awt.Color;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Right-side run detail panel. The table index carries only summaries; callers
 * pass a selected summary here and the full JSONL record is loaded on demand.
 */
public final class RunDetailPanel extends JPanel {

    private static final Color WARN_PREFIX = new Color(255, 231, 145);
    private static final Color ERROR_PREFIX = new Color(250, 190, 190);
    private static final Color INFO_PREFIX = new Color(210, 230, 248);

    private final JLabel titleLabel = new JLabel("No run selected");
    private final JTree parameterTree;
    private final InputsModel inputsModel = new InputsModel();
    private final OutputsModel outputsModel = new OutputsModel();
    private final JTextArea messagesArea = new JTextArea();

    private RunSummary currentSummary;
    private RunRecord currentRecord;

    public RunDetailPanel() {
        super(new BorderLayout(0, 8));
        setBorder(new EmptyBorder(8, 8, 8, 8));
        setBackground(FlashTheme.SURFACE);

        titleLabel.setFont(FlashTheme.h2());
        titleLabel.setForeground(FlashTheme.TEXT_HEADER);
        add(titleLabel, BorderLayout.NORTH);

        parameterTree = createParameterTree(null);
        JScrollPane parametersScroll = titledScroll("Parameters", parameterTree);

        JTable inputsTable = new JTable(inputsModel);
        inputsTable.setFillsViewportHeight(true);
        inputsTable.setRowHeight(22);

        JTable outputsTable = new JTable(outputsModel);
        outputsTable.setFillsViewportHeight(true);
        outputsTable.setRowHeight(22);

        messagesArea.setEditable(false);
        messagesArea.setLineWrap(true);
        messagesArea.setWrapStyleWord(true);
        messagesArea.setFont(FlashTheme.mono(12f));
        messagesArea.setBackground(FlashTheme.SURFACE_RAISED);
        messagesArea.setText("Select a run to inspect its messages.");

        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Inputs", new JScrollPane(inputsTable));
        tabs.addTab("Outputs", new JScrollPane(outputsTable));
        tabs.addTab("Messages", new JScrollPane(messagesArea));

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, parametersScroll, tabs);
        split.setResizeWeight(0.58);
        split.setBorder(BorderFactory.createEmptyBorder());
        add(split, BorderLayout.CENTER);
    }

    public void showSummary(RunSummary summary) {
        currentSummary = summary;
        if (summary == null) {
            setRecord(null);
            return;
        }
        if (summary.recordFile == null || !summary.recordFile.isFile()) {
            setMissingRecord(summary, "Run record file is missing.");
            return;
        }
        RunRecord record = RunRecordIO.readLatest(summary.recordFile);
        if (record == null) {
            setMissingRecord(summary, "Run record file could not be decoded.");
            return;
        }
        setRecord(record);
    }

    public void setRecord(RunRecord record) {
        currentRecord = record;
        if (record == null) {
            titleLabel.setText("No run selected");
            replaceParameterRoot(new DefaultMutableTreeNode("parameters"));
            inputsModel.setInputs(null);
            outputsModel.setOutputs(null);
            setMessages(null);
            return;
        }
        titleLabel.setText(summaryTitle(record));
        replaceParameterRoot(buildParametersRoot(record.parameters));
        inputsModel.setInputs(record.inputs);
        outputsModel.setOutputs(record.outputs);
        setMessages(record.messages);
    }

    public RunRecord getCurrentRecord() {
        return currentRecord;
    }

    public RunSummary getCurrentSummary() {
        return currentSummary;
    }

    public JTree getParameterTreeForTests() {
        return parameterTree;
    }

    public boolean parameterTreeContainsForTests(String text) {
        if (text == null) {
            return false;
        }
        DefaultMutableTreeNode root = (DefaultMutableTreeNode) parameterTree.getModel().getRoot();
        return containsNode(root, text);
    }

    public static JTree createParameterTree(Map<String, Object> parameters) {
        JTree tree = new JTree(buildParametersRoot(parameters));
        tree.setRootVisible(true);
        tree.setShowsRootHandles(true);
        tree.setBackground(FlashTheme.SURFACE_RAISED);
        expandAll(tree);
        return tree;
    }

    private static JScrollPane titledScroll(String title, JComponent component) {
        JScrollPane scroll = new JScrollPane(component);
        scroll.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createLineBorder(FlashTheme.BORDER), title));
        return scroll;
    }

    private void setMissingRecord(RunSummary summary, String message) {
        currentRecord = null;
        titleLabel.setText(RunsTableModel.displayAnalysis(summary) + " - " + RunsTableModel.shortId(summary.runId));
        replaceParameterRoot(new DefaultMutableTreeNode("parameters"));
        inputsModel.setInputs(null);
        outputsModel.setOutputs(null);
        List<RunRecord.Message> messages = new ArrayList<RunRecord.Message>();
        messages.add(new RunRecord.Message("warn", System.currentTimeMillis(), message));
        setMessages(messages);
    }

    private static String summaryTitle(RunRecord record) {
        String analysis = record.analysis == null || record.analysis.trim().isEmpty()
                ? record.analysisLabel
                : record.analysis;
        if (analysis == null || analysis.trim().isEmpty()) {
            analysis = "(analysis)";
        }
        String id = RunsTableModel.shortId(record.runId);
        String status = record.status == null ? "" : record.status;
        return analysis + " - " + status + (id.isEmpty() ? "" : " - " + id);
    }

    private void replaceParameterRoot(DefaultMutableTreeNode root) {
        parameterTree.setModel(new DefaultTreeModel(root));
        expandAll(parameterTree);
    }

    private void setMessages(List<RunRecord.Message> messages) {
        Highlighter highlighter = messagesArea.getHighlighter();
        highlighter.removeAllHighlights();
        if (messages == null || messages.isEmpty()) {
            messagesArea.setText("No warnings or errors were recorded.");
            return;
        }

        StringBuilder text = new StringBuilder();
        List<int[]> prefixes = new ArrayList<int[]>();
        List<Color> colors = new ArrayList<Color>();
        for (RunRecord.Message message : messages) {
            String level = safe(message.level).trim().toUpperCase();
            if (level.isEmpty()) {
                level = "INFO";
            }
            String prefix = "[" + level + "]";
            int start = text.length();
            text.append(prefix);
            int end = text.length();
            text.append(' ');
            if (message.atMillis > 0L) {
                text.append(RunsTableModel.formatDateTime(message.atMillis)).append(' ');
            }
            text.append(safe(message.text)).append('\n');
            prefixes.add(new int[]{start, end});
            colors.add(prefixColor(level));
        }
        messagesArea.setText(text.toString());
        for (int i = 0; i < prefixes.size(); i++) {
            int[] range = prefixes.get(i);
            try {
                highlighter.addHighlight(range[0], range[1],
                        new DefaultHighlighter.DefaultHighlightPainter(colors.get(i)));
            } catch (BadLocationException ignored) {
                // A stale highlight range is harmless; the plain text is still visible.
            }
        }
        messagesArea.setCaretPosition(0);
    }

    private static Color prefixColor(String level) {
        if ("ERROR".equals(level) || "FAILED".equals(level)) {
            return ERROR_PREFIX;
        }
        if ("WARN".equals(level) || "WARNING".equals(level)) {
            return WARN_PREFIX;
        }
        return INFO_PREFIX;
    }

    private static DefaultMutableTreeNode buildParametersRoot(Map<String, Object> parameters) {
        DefaultMutableTreeNode root = new DefaultMutableTreeNode("parameters");
        if (parameters == null || parameters.isEmpty()) {
            root.add(new DefaultMutableTreeNode("(none)"));
            return root;
        }
        for (Map.Entry<String, Object> entry : parameters.entrySet()) {
            root.add(nodeFor(entry.getKey(), entry.getValue()));
        }
        return root;
    }

    @SuppressWarnings("unchecked")
    private static DefaultMutableTreeNode nodeFor(String name, Object value) {
        String label = name == null ? "" : name;
        if (value instanceof Map) {
            DefaultMutableTreeNode node = new DefaultMutableTreeNode(label);
            Map<Object, Object> map = (Map<Object, Object>) value;
            if (map.isEmpty()) {
                node.add(new DefaultMutableTreeNode("(empty)"));
            } else {
                for (Map.Entry<Object, Object> entry : map.entrySet()) {
                    node.add(nodeFor(String.valueOf(entry.getKey()), entry.getValue()));
                }
            }
            return node;
        }
        if (value instanceof List) {
            DefaultMutableTreeNode node = new DefaultMutableTreeNode(label);
            List<?> list = (List<?>) value;
            if (list.isEmpty()) {
                node.add(new DefaultMutableTreeNode("(empty)"));
            } else {
                for (int i = 0; i < list.size(); i++) {
                    node.add(nodeFor("[" + i + "]", list.get(i)));
                }
            }
            return node;
        }
        return new DefaultMutableTreeNode(label + ": " + String.valueOf(value));
    }

    private static boolean containsNode(DefaultMutableTreeNode node, String text) {
        if (node == null) {
            return false;
        }
        Object value = node.getUserObject();
        if (value != null && String.valueOf(value).contains(text)) {
            return true;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            if (containsNode((DefaultMutableTreeNode) node.getChildAt(i), text)) {
                return true;
            }
        }
        return false;
    }

    private static void expandAll(JTree tree) {
        for (int i = 0; i < tree.getRowCount(); i++) {
            tree.expandRow(i);
        }
    }

    private static String fileName(String path) {
        if (path == null || path.trim().isEmpty()) {
            return "";
        }
        return new File(path).getName();
    }

    private static String formatBytes(long bytes) {
        if (bytes < 0L) {
            return "-";
        }
        if (bytes < 1024L) {
            return bytes + " B";
        }
        double value = bytes / 1024.0;
        if (value < 1024.0) {
            return String.format(java.util.Locale.ROOT, "%.1f KB", value);
        }
        value = value / 1024.0;
        if (value < 1024.0) {
            return String.format(java.util.Locale.ROOT, "%.1f MB", value);
        }
        return String.format(java.util.Locale.ROOT, "%.1f GB", value / 1024.0);
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static final class InputsModel extends AbstractTableModel {
        private final List<RunRecord.InputItem> inputs = new ArrayList<RunRecord.InputItem>();
        private final String[] columns = {"File", "Animal", "Condition", "Status", "Duration"};

        void setInputs(List<RunRecord.InputItem> next) {
            inputs.clear();
            if (next != null) {
                inputs.addAll(next);
            }
            fireTableDataChanged();
        }

        public int getRowCount() {
            return inputs.size();
        }

        public int getColumnCount() {
            return columns.length;
        }

        @Override
        public String getColumnName(int column) {
            return columns[column];
        }

        public Object getValueAt(int rowIndex, int columnIndex) {
            RunRecord.InputItem input = inputs.get(rowIndex);
            switch (columnIndex) {
                case 0:
                    return fileName(input.path);
                case 1:
                    return safe(input.animalId);
                case 2:
                    return safe(input.condition);
                case 3:
                    return safe(input.status);
                case 4:
                    return RunsTableModel.formatDuration(input.durationMillis);
                default:
                    return "";
            }
        }
    }

    private static final class OutputsModel extends AbstractTableModel {
        private final List<RunRecord.OutputItem> outputs = new ArrayList<RunRecord.OutputItem>();
        private final String[] columns = {"File", "Kind", "Size"};

        void setOutputs(List<RunRecord.OutputItem> next) {
            outputs.clear();
            if (next != null) {
                outputs.addAll(next);
            }
            fireTableDataChanged();
        }

        public int getRowCount() {
            return outputs.size();
        }

        public int getColumnCount() {
            return columns.length;
        }

        @Override
        public String getColumnName(int column) {
            return columns[column];
        }

        public Object getValueAt(int rowIndex, int columnIndex) {
            RunRecord.OutputItem output = outputs.get(rowIndex);
            switch (columnIndex) {
                case 0:
                    return fileName(output.path);
                case 1:
                    return safe(output.kind);
                case 2:
                    return formatBytes(output.sizeBytes);
                default:
                    return "";
            }
        }
    }
}
