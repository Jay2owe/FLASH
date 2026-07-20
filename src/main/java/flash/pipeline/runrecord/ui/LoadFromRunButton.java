package flash.pipeline.runrecord.ui;

import flash.pipeline.io.FlashProjectLayout;
import flash.pipeline.runrecord.EnvironmentSnapshot;
import flash.pipeline.runrecord.LoadedRunParameters;
import flash.pipeline.runrecord.RunRecord;
import flash.pipeline.runrecord.RunRecordIO;
import flash.pipeline.runrecord.RunSummary;
import flash.pipeline.ui.FlashTheme;
import flash.pipeline.ui.PipelineDialog;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.RowSorter;
import javax.swing.SortOrder;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;
import javax.swing.table.TableRowSorter;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GraphicsEnvironment;
import java.awt.Window;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Button that lets an analysis dialog copy parameters from a previous run of
 * the same analysis.
 */
public final class LoadFromRunButton {

    private static final String LABEL = "Load settings from previous run...";
    private static RunSelectionProvider selectionProviderForTests;

    private LoadFromRunButton() {
    }

    public static JButton install(PipelineDialog dialog,
                                  String analysisKey,
                                  File projectRoot,
                                  Consumer<Map<String, Object>> applyParameters) {
        JButton button = create(analysisKey, projectRoot, applyParameters);
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        row.setOpaque(false);
        row.setBorder(FlashTheme.pad(0, 12, 4, 12));
        row.add(button);
        if (dialog != null) {
            dialog.setNorthSlot(row);
        }
        return button;
    }

    public static JButton create(String analysisKey,
                                 File projectRoot,
                                 Consumer<Map<String, Object>> applyParameters) {
        final JButton button = new JButton(LABEL);
        button.setToolTipText("Copy saved parameters from an earlier run of this analysis.");
        button.addActionListener(e -> loadFromRun(button, analysisKey, projectRoot, applyParameters));
        return button;
    }

    public static void setSelectionProviderForTests(RunSelectionProvider provider) {
        selectionProviderForTests = provider;
    }

    private static void loadFromRun(Component owner,
                                    String analysisKey,
                                    File projectRoot,
                                    Consumer<Map<String, Object>> applyParameters) {
        if (applyParameters == null) {
            return;
        }
        List<RunSummary> summaries = summariesFor(projectRoot, analysisKey);
        RunRecord record = chooseRecord(owner, analysisKey, projectRoot, summaries);
        if (record == null) {
            return;
        }

        boolean versionMismatch = hasVersionMismatch(record);
        applyParameters.accept(record.parameters == null
                ? Collections.<String, Object>emptyMap()
                : record.parameters);
        LoadedRunParameters.Result result = LoadedRunParameters.consumeLastResult();
        if (result == null) {
            Map<String, Object> parameters = record.parameters == null
                    ? Collections.<String, Object>emptyMap()
                    : record.parameters;
            result = LoadedRunParameters.resultForKnownKeys(parameters, parameters.keySet());
        }

        if (!GraphicsEnvironment.isHeadless() && selectionProviderForTests == null) {
            String message = result.summary();
            if (versionMismatch) {
                message = versionMismatchText(record.flashVersion, EnvironmentSnapshot.flashVersion())
                        + "\n\n" + message;
            }
            JOptionPane.showMessageDialog(owner, message, "Loaded run settings",
                    versionMismatch ? JOptionPane.WARNING_MESSAGE : JOptionPane.INFORMATION_MESSAGE);
        }
    }

    private static RunRecord chooseRecord(Component owner,
                                          String analysisKey,
                                          File projectRoot,
                                          List<RunSummary> summaries) {
        RunSelectionProvider provider = selectionProviderForTests;
        if (provider != null) {
            return provider.choose(owner, analysisKey, projectRoot, summaries);
        }
        if (summaries == null || summaries.isEmpty()) {
            if (!GraphicsEnvironment.isHeadless()) {
                JOptionPane.showMessageDialog(owner,
                        "No previous runs were found for " + safe(analysisKey) + ".",
                        "Load settings", JOptionPane.INFORMATION_MESSAGE);
            }
            return null;
        }
        if (GraphicsEnvironment.isHeadless()) {
            RunSummary first = summaries.get(0);
            return first == null || first.recordFile == null ? null : RunRecordIO.readLatest(first.recordFile);
        }
        RunSummary selected = showPicker(owner, analysisKey, summaries);
        return selected == null || selected.recordFile == null
                ? null
                : RunRecordIO.readLatest(selected.recordFile);
    }

    private static RunSummary showPicker(Component owner, String analysisKey, List<RunSummary> summaries) {
        final Window window = owner == null ? null : SwingUtilities.getWindowAncestor(owner);
        final JDialog dialog = new JDialog(window, "Load settings from previous run",
                java.awt.Dialog.ModalityType.APPLICATION_MODAL);
        dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);

        final RunsTableModel model = new RunsTableModel(summaries);
        final JTable table = new JTable(model);
        final TableRowSorter<RunsTableModel> sorter = new TableRowSorter<RunsTableModel>(model);
        table.setRowSorter(sorter);
        sorter.setSortKeys(Collections.singletonList(
                new RowSorter.SortKey(RunsTableModel.COL_STARTED_AT, SortOrder.DESCENDING)));
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setFillsViewportHeight(true);
        table.getTableHeader().setReorderingAllowed(false);

        final JLabel banner = new JLabel(" ");
        banner.setOpaque(true);
        banner.setBackground(FlashTheme.WARNING_BG);
        banner.setForeground(FlashTheme.WARNING_FG);
        banner.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(FlashTheme.WARNING_BORDER),
                FlashTheme.pad(5, 8, 5, 8)));
        banner.setVisible(false);

        final RunSummary[] selected = new RunSummary[1];
        table.getSelectionModel().addListSelectionListener(e -> {
            if (e.getValueIsAdjusting()) return;
            RunSummary summary = selectedSummary(table, model);
            selected[0] = summary;
            if (summary != null && versionDiffers(summary.flashVersion, EnvironmentSnapshot.flashVersion())) {
                banner.setText(versionMismatchText(summary.flashVersion, EnvironmentSnapshot.flashVersion()));
                banner.setVisible(true);
            } else {
                banner.setVisible(false);
            }
        });

        JPanel content = new JPanel(new BorderLayout(8, 8));
        content.setBorder(FlashTheme.pad(12, 14, 12, 14));
        content.setBackground(FlashTheme.SURFACE);
        JLabel title = new JLabel("Previous " + safe(analysisKey) + " runs");
        title.setFont(FlashTheme.h2());
        title.setForeground(FlashTheme.TEXT_HEADER);
        content.add(title, BorderLayout.NORTH);
        JScrollPane scroll = new JScrollPane(table);
        scroll.setPreferredSize(new Dimension(780, 320));
        content.add(scroll, BorderLayout.CENTER);

        JPanel south = new JPanel(new BorderLayout(8, 8));
        south.setOpaque(false);
        south.add(banner, BorderLayout.NORTH);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        buttons.setOpaque(false);
        JButton cancel = new JButton("Cancel");
        JButton ok = new JButton("OK");
        ok.setEnabled(false);
        cancel.addActionListener(e -> {
            selected[0] = null;
            dialog.dispose();
        });
        ok.addActionListener(e -> dialog.dispose());
        table.getSelectionModel().addListSelectionListener(e -> ok.setEnabled(table.getSelectedRow() >= 0));
        buttons.add(cancel);
        buttons.add(ok);
        south.add(buttons, BorderLayout.SOUTH);
        content.add(south, BorderLayout.SOUTH);

        dialog.setContentPane(content);
        dialog.pack();
        dialog.setLocationRelativeTo(window);
        if (table.getRowCount() > 0) {
            table.setRowSelectionInterval(0, 0);
        }
        dialog.setVisible(true);
        return selected[0];
    }

    private static RunSummary selectedSummary(JTable table, RunsTableModel model) {
        int selected = table.getSelectedRow();
        if (selected < 0) {
            return null;
        }
        return model.getSummaryAt(table.convertRowIndexToModel(selected));
    }

    private static List<RunSummary> summariesFor(File projectRoot, String analysisKey) {
        File root = projectRoot == null ? new File(".") : projectRoot;
        FlashProjectLayout layout = FlashProjectLayout.forDirectory(root.getAbsolutePath());
        List<RunSummary> summaries = RunRecordIO.readIndex(layout.runJsonlWriteDir());
        List<RunSummary> filtered = new ArrayList<RunSummary>();
        for (RunSummary summary : summaries) {
            if (summary != null && analysisMatches(summary, analysisKey)) {
                filtered.add(summary);
            }
        }
        return filtered;
    }

    private static boolean analysisMatches(RunSummary summary, String analysisKey) {
        String needle = safe(analysisKey).trim();
        if (needle.isEmpty()) {
            return true;
        }
        return safe(summary.analysis).trim().equalsIgnoreCase(needle)
                || safe(summary.analysisLabel).trim().equalsIgnoreCase(needle)
                || RunsTableModel.displayAnalysis(summary).trim().equalsIgnoreCase(needle);
    }

    private static boolean hasVersionMismatch(RunRecord record) {
        return record != null && versionDiffers(record.flashVersion, EnvironmentSnapshot.flashVersion());
    }

    private static boolean versionDiffers(String runVersion, String currentVersion) {
        String left = safe(runVersion).trim();
        String right = safe(currentVersion).trim();
        return !left.isEmpty() && !right.isEmpty()
                && !left.toLowerCase(Locale.ROOT).equals(right.toLowerCase(Locale.ROOT));
    }

    private static String versionMismatchText(String runVersion, String currentVersion) {
        return "This run was made on FLASH " + safe(runVersion)
                + "; you are on " + safe(currentVersion)
                + ". Some parameter names may have changed.";
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    public interface RunSelectionProvider {
        RunRecord choose(Component owner, String analysisKey, File projectRoot, List<RunSummary> summaries);
    }
}
