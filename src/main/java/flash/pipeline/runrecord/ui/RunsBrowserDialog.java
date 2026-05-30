package flash.pipeline.runrecord.ui;

import flash.pipeline.io.FlashProjectLayout;
import flash.pipeline.project.ProjectFile;
import flash.pipeline.project.ProjectFileIO;
import flash.pipeline.replay.Replay;
import flash.pipeline.replay.ReplayPlan;
import flash.pipeline.runrecord.RunRecord;
import flash.pipeline.runrecord.RunRecordIO;
import flash.pipeline.runrecord.RunSummary;
import flash.pipeline.ui.FlashTheme;

import javax.swing.BorderFactory;
import javax.swing.DefaultComboBoxModel;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.RowFilter;
import javax.swing.SortOrder;
import javax.swing.WindowConstants;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.event.ListSelectionEvent;
import javax.swing.event.ListSelectionListener;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableColumnModel;
import javax.swing.table.TableRowSorter;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.GraphicsEnvironment;
import java.awt.Window;
import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.List;

/**
 * Browser for JSONL run records under {@code FLASH/Results/Run Records/runs}.
 */
public final class RunsBrowserDialog extends JDialog {

    private final File projectRoot;
    private final FlashProjectLayout layout;
    private final RunsTableModel model;
    private final JTable table;
    private final TableRowSorter<RunsTableModel> sorter;
    private final RunDetailPanel detailPanel = new RunDetailPanel();
    private final JTextField textFilter = new JTextField(18);
    private final JComboBox<String> analysisFilter;
    private final JComboBox<String> statusFilter;
    private final JComboBox<RunsTableModel.DateRange> dateFilter;
    private final JButton viewSettingsButton = new JButton("View settings...");
    private final JButton reproduceButton = new JButton("Reproduce verbatim");
    private final JButton diffWithParentButton = new JButton("Diff with parent");

    public RunsBrowserDialog(Window owner, File projectRoot) {
        super(owner, "FLASH Runs", ModalityType.APPLICATION_MODAL);
        if (GraphicsEnvironment.isHeadless()) {
            throw new IllegalStateException("RunsBrowserDialog cannot run headless.");
        }
        if (projectRoot == null) {
            throw new IllegalArgumentException("projectRoot must not be null.");
        }
        this.projectRoot = projectRoot.getAbsoluteFile();
        this.layout = FlashProjectLayout.forDirectory(this.projectRoot.getAbsolutePath());
        this.model = new RunsTableModel(layout);
        this.table = new JTable(model);
        this.sorter = new TableRowSorter<RunsTableModel>(model);
        this.analysisFilter = new JComboBox<String>(new DefaultComboBoxModel<String>(
                model.analysisValues().toArray(new String[model.analysisValues().size()])));
        this.statusFilter = new JComboBox<String>(new DefaultComboBoxModel<String>(
                model.statusValues().toArray(new String[model.statusValues().size()])));
        this.dateFilter = new JComboBox<RunsTableModel.DateRange>(new RunsTableModel.DateRange[]{
                RunsTableModel.DateRange.ALL,
                RunsTableModel.DateRange.LAST_24_HOURS,
                RunsTableModel.DateRange.LAST_7_DAYS
        });

        setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        setContentPane(buildContent());
        pack();
        setMinimumSize(new Dimension(980, 600));
        setLocationRelativeTo(owner);
        selectFirstRowIfAvailable();
    }

    public static void open(Window owner, File projectRoot) {
        RunsBrowserDialog dialog = new RunsBrowserDialog(owner, projectRoot);
        dialog.setVisible(true);
    }

    private JPanel buildContent() {
        JPanel content = new JPanel(new BorderLayout(8, 8));
        content.setBorder(BorderFactory.createEmptyBorder(12, 14, 12, 14));
        content.setBackground(FlashTheme.SURFACE);

        JPanel top = new JPanel(new BorderLayout(0, 8));
        top.setOpaque(false);
        top.add(buildHeader(), BorderLayout.NORTH);
        top.add(buildFilterRow(), BorderLayout.CENTER);
        JLabel emptyNote = buildEmptyNote();
        if (emptyNote != null) {
            top.add(emptyNote, BorderLayout.SOUTH);
        }
        content.add(top, BorderLayout.NORTH);

        configureTable();
        JScrollPane tableScroll = new JScrollPane(table);
        tableScroll.setBorder(BorderFactory.createLineBorder(FlashTheme.BORDER));

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, tableScroll, detailPanel);
        split.setResizeWeight(0.62);
        split.setBorder(BorderFactory.createEmptyBorder());
        content.add(split, BorderLayout.CENTER);
        content.add(buildButtons(), BorderLayout.SOUTH);
        return content;
    }

    private JPanel buildHeader() {
        JPanel panel = new JPanel(new BorderLayout(8, 2));
        panel.setOpaque(false);

        JLabel title = new JLabel(projectName());
        title.setFont(FlashTheme.h1());
        title.setForeground(FlashTheme.TEXT_HEADER);
        panel.add(title, BorderLayout.WEST);

        JLabel runs = new JLabel(model.getRowCount() + " run" + (model.getRowCount() == 1 ? "" : "s"));
        runs.setFont(FlashTheme.bodyMedium());
        runs.setForeground(FlashTheme.TEXT_SUBHEADER);
        panel.add(runs, BorderLayout.EAST);

        JLabel output = new JLabel("<html><body width='760'>Output root: "
                + escapeHtml(projectRoot.getAbsolutePath()) + "</body></html>");
        output.setFont(FlashTheme.caption());
        output.setForeground(FlashTheme.TEXT_MUTED);
        panel.add(output, BorderLayout.SOUTH);
        return panel;
    }

    private JPanel buildFilterRow() {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        row.setOpaque(false);
        row.add(new JLabel("Filter"));
        row.add(textFilter);
        row.add(new JLabel("Analysis"));
        row.add(analysisFilter);
        row.add(new JLabel("Status"));
        row.add(statusFilter);
        row.add(new JLabel("Date"));
        row.add(dateFilter);

        DocumentListener documentListener = new DocumentListener() {
            public void insertUpdate(DocumentEvent e) {
                applyFilters();
            }

            public void removeUpdate(DocumentEvent e) {
                applyFilters();
            }

            public void changedUpdate(DocumentEvent e) {
                applyFilters();
            }
        };
        textFilter.getDocument().addDocumentListener(documentListener);
        analysisFilter.addActionListener(e -> applyFilters());
        statusFilter.addActionListener(e -> applyFilters());
        dateFilter.addActionListener(e -> applyFilters());
        return row;
    }

    private JLabel buildEmptyNote() {
        if (model.getRowCount() > 0) {
            return null;
        }
        String text = legacySidecarsPresent()
                ? "No JSONL run records found. Legacy run sidecars are ignored by this browser."
                : "No JSONL run records found.";
        JLabel label = new JLabel(text);
        label.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(FlashTheme.WARNING_BORDER),
                BorderFactory.createEmptyBorder(6, 8, 6, 8)));
        label.setOpaque(true);
        label.setBackground(FlashTheme.WARNING_BG);
        label.setForeground(FlashTheme.WARNING_FG);
        return label;
    }

    private void configureTable() {
        table.setRowSorter(sorter);
        sorter.setSortKeys(Collections.singletonList(
                new javax.swing.RowSorter.SortKey(RunsTableModel.COL_STARTED_AT, SortOrder.DESCENDING)));
        table.setFillsViewportHeight(true);
        table.setRowHeight(24);
        table.setSelectionMode(javax.swing.ListSelectionModel.SINGLE_SELECTION);
        table.getTableHeader().setReorderingAllowed(false);
        table.getColumnModel().getColumn(RunsTableModel.COL_STATUS).setCellRenderer(new StatusRenderer());
        applyColumnWidths(table.getColumnModel());
        table.getSelectionModel().addListSelectionListener(new ListSelectionListener() {
            public void valueChanged(ListSelectionEvent e) {
                if (!e.getValueIsAdjusting()) {
                    updateSelection();
                }
            }
        });
    }

    private static void applyColumnWidths(TableColumnModel columns) {
        columns.getColumn(RunsTableModel.COL_STARTED_AT).setPreferredWidth(135);
        columns.getColumn(RunsTableModel.COL_ANALYSIS).setPreferredWidth(170);
        columns.getColumn(RunsTableModel.COL_STATUS).setPreferredWidth(80);
        columns.getColumn(RunsTableModel.COL_DURATION).setPreferredWidth(80);
        columns.getColumn(RunsTableModel.COL_INPUTS).setPreferredWidth(60);
        columns.getColumn(RunsTableModel.COL_FLASH_VERSION).setPreferredWidth(90);
        columns.getColumn(RunsTableModel.COL_PARENT_RUN_ID).setPreferredWidth(70);
        columns.getColumn(RunsTableModel.COL_RUN_ID).setPreferredWidth(70);
    }

    private JPanel buildButtons() {
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        buttons.setOpaque(false);

        JButton openOutputButton = new JButton("Open output folder");
        openOutputButton.addActionListener(e -> openOutputFolder());
        buttons.add(openOutputButton);

        viewSettingsButton.setEnabled(false);
        viewSettingsButton.addActionListener(e -> showSettingsPopup());
        buttons.add(viewSettingsButton);

        reproduceButton.setEnabled(false);
        reproduceButton.addActionListener(e -> reproduceSelectedRun());
        buttons.add(reproduceButton);

        diffWithParentButton.setEnabled(false);
        diffWithParentButton.addActionListener(e -> showDiffWithParent());
        buttons.add(diffWithParentButton);

        JButton loadSettingsButton = new JButton("Load settings into dialog...");
        loadSettingsButton.setEnabled(false);
        buttons.add(loadSettingsButton);

        JButton closeButton = new JButton("Close");
        closeButton.addActionListener(e -> dispose());
        buttons.add(closeButton);
        return buttons;
    }

    private void applyFilters() {
        RowFilter<RunsTableModel, Integer> filter = RunsTableModel.createRowFilter(
                new RunsTableModel.FilterCriteria(
                        textFilter.getText(),
                        (String) analysisFilter.getSelectedItem(),
                        (String) statusFilter.getSelectedItem(),
                        (RunsTableModel.DateRange) dateFilter.getSelectedItem(),
                        System.currentTimeMillis()));
        sorter.setRowFilter(filter);
        if (table.getRowCount() > 0) {
            table.setRowSelectionInterval(0, 0);
        } else {
            detailPanel.showSummary(null);
            viewSettingsButton.setEnabled(false);
            reproduceButton.setEnabled(false);
            diffWithParentButton.setEnabled(false);
        }
    }

    private void selectFirstRowIfAvailable() {
        if (table.getRowCount() > 0) {
            table.setRowSelectionInterval(0, 0);
        }
    }

    private void updateSelection() {
        RunSummary summary = selectedSummary();
        detailPanel.showSummary(summary);
        viewSettingsButton.setEnabled(summary != null);
        reproduceButton.setEnabled(summary != null);
        diffWithParentButton.setEnabled(summary != null
                && summary.parentRunId != null
                && !summary.parentRunId.trim().isEmpty());
    }

    private RunSummary selectedSummary() {
        int selected = table.getSelectedRow();
        if (selected < 0) {
            return null;
        }
        int modelRow = table.convertRowIndexToModel(selected);
        return model.getSummaryAt(modelRow);
    }

    private RunRecord selectedRecord() {
        RunRecord loaded = detailPanel.getCurrentRecord();
        if (loaded != null) {
            return loaded;
        }
        RunSummary summary = selectedSummary();
        if (summary == null || summary.recordFile == null) {
            return null;
        }
        return RunRecordIO.readLatest(summary.recordFile);
    }

    private void openOutputFolder() {
        RunSummary summary = selectedSummary();
        File folder = outputFolder(summary);
        if (folder == null || !folder.isDirectory()) {
            showWarning("Output folder does not exist:\n" + (folder == null ? "(unknown)" : folder.getAbsolutePath()),
                    "Open output folder");
            return;
        }
        if (!Desktop.isDesktopSupported()) {
            showWarning("Desktop file manager integration is not available in this session.",
                    "Open output folder");
            return;
        }
        try {
            Desktop.getDesktop().open(folder);
        } catch (IOException | RuntimeException e) {
            showWarning("Could not open output folder:\n" + e.getMessage(), "Open output folder");
        }
    }

    private File outputFolder(RunSummary summary) {
        if (summary != null && summary.outputRoot != null && !summary.outputRoot.trim().isEmpty()) {
            return new File(summary.outputRoot);
        }
        return projectRoot;
    }

    private void showSettingsPopup() {
        RunRecord record = selectedRecord();
        if (record == null) {
            showWarning("No settings are available for the selected run.", "View settings");
            return;
        }
        javax.swing.JTree tree = RunDetailPanel.createParameterTree(record.parameters);
        JScrollPane scroll = new JScrollPane(tree);
        scroll.setPreferredSize(new Dimension(560, 460));
        JOptionPane.showMessageDialog(this, scroll, "Run settings", JOptionPane.PLAIN_MESSAGE);
    }

    private void reproduceSelectedRun() {
        RunRecord record = selectedRecord();
        if (record == null) {
            showWarning("No run record is available for replay.", "Reproduce verbatim");
            return;
        }
        ReplayPlan plan = Replay.plan(record);
        if (plan.status() == ReplayPlan.Status.BLOCKED) {
            showWarning(joinLines(plan.messages()), "Reproduce verbatim");
            return;
        }
        try {
            Replay.execute(plan, this);
            model.setRuns(RunRecordIO.readIndex(layout.runJsonlWriteDir()));
            applyFilters();
        } catch (RuntimeException e) {
            showWarning(e.getMessage(), "Reproduce verbatim");
        }
    }

    private void showDiffWithParent() {
        RunRecord child = selectedRecord();
        if (child == null || child.parentRunId == null || child.parentRunId.trim().isEmpty()) {
            showWarning("The selected run has no parent run.", "Diff with parent");
            return;
        }
        RunSummary parentSummary = findSummaryByRunId(child.parentRunId);
        if (parentSummary == null || parentSummary.recordFile == null) {
            showWarning("Parent run record was not found: " + child.parentRunId, "Diff with parent");
            return;
        }
        RunRecord parent = RunRecordIO.readLatest(parentSummary.recordFile);
        if (parent == null) {
            showWarning("Parent run record could not be decoded.", "Diff with parent");
            return;
        }
        RunDiffPanel panel = new RunDiffPanel(parent, child);
        panel.setPreferredSize(new Dimension(820, 560));
        JOptionPane.showMessageDialog(this, panel, "Diff with parent", JOptionPane.PLAIN_MESSAGE);
    }

    private RunSummary findSummaryByRunId(String runId) {
        if (runId == null || runId.trim().isEmpty()) {
            return null;
        }
        List<RunSummary> summaries = RunRecordIO.readIndex(layout.runJsonlWriteDir());
        for (RunSummary summary : summaries) {
            if (summary != null && runId.equals(summary.runId)) {
                return summary;
            }
        }
        return null;
    }

    private void showWarning(String message, String title) {
        JOptionPane.showMessageDialog(this, message, title, JOptionPane.WARNING_MESSAGE);
    }

    private static String joinLines(List<String> lines) {
        StringBuilder out = new StringBuilder();
        if (lines != null) {
            for (String line : lines) {
                if (out.length() > 0) {
                    out.append('\n');
                }
                out.append(line);
            }
        }
        return out.toString();
    }

    private boolean legacySidecarsPresent() {
        File root = layout.runRecordsRoot();
        if (root == null) {
            return false;
        }
        File history = new File(root, FlashProjectLayout.RUN_HISTORY_FILENAME);
        if (history.isFile()) {
            return true;
        }
        return new File(root, FlashProjectLayout.SETTINGS_SNAPSHOTS_DIR).exists()
                || new File(root, FlashProjectLayout.REPLAY_COMMANDS_DIR).exists()
                || new File(root, FlashProjectLayout.ANALYSIS_DETAILS_DIR).exists();
    }

    private String projectName() {
        ProjectFile project = ProjectFileIO.read(layout.configurationWriteDir());
        if (project != null && project.name != null && !project.name.trim().isEmpty()) {
            return project.name.trim();
        }
        String name = projectRoot.getName();
        return name == null || name.trim().isEmpty() ? "FLASH project" : name;
    }

    private static String escapeHtml(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static final class StatusRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected,
                                                       boolean hasFocus, int row, int column) {
            JLabel label = (JLabel) super.getTableCellRendererComponent(
                    table, value, isSelected, hasFocus, row, column);
            String status = value == null ? "" : String.valueOf(value);
            label.setIcon(new StatusIcon(status));
            label.setIconTextGap(6);
            return label;
        }
    }

    private static final class StatusIcon implements Icon {
        private final Color color;

        StatusIcon(String status) {
            String normalised = status == null ? "" : status.trim().toLowerCase(java.util.Locale.ROOT);
            if ("failed".equals(normalised) || "error".equals(normalised)) {
                color = FlashTheme.DANGER_FG;
            } else if ("warn".equals(normalised) || "warning".equals(normalised)) {
                color = FlashTheme.WARNING_FG;
            } else if ("ok".equals(normalised) || "success".equals(normalised)) {
                color = FlashTheme.SUCCESS_FG;
            } else {
                color = FlashTheme.TEXT_MUTED;
            }
        }

        public void paintIcon(Component c, Graphics g, int x, int y) {
            Color old = g.getColor();
            g.setColor(color);
            g.fillOval(x + 2, y + 4, 8, 8);
            g.setColor(old);
        }

        public int getIconWidth() {
            return 12;
        }

        public int getIconHeight() {
            return 16;
        }
    }
}
