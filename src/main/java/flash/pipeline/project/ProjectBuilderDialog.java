package flash.pipeline.project;

import flash.pipeline.intelligence.identity.Confidence;
import flash.pipeline.io.ConditionManifestIO;
import flash.pipeline.io.FlashProjectLayout;
import flash.pipeline.io.ImageSourceDispatcher;
import flash.pipeline.io.LifIO;
import flash.pipeline.io.RosterIO;
import flash.pipeline.io.SeriesMeta;
import flash.pipeline.naming.ConditionAssignments;
import flash.pipeline.naming.ConditionAxis;
import flash.pipeline.ui.FlashTheme;
import flash.pipeline.ui.wizard.RegionTableCellEditor;
import ij.IJ;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JComboBox;
import javax.swing.DefaultCellEditor;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JList;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.TransferHandler;
import javax.swing.ListSelectionModel;
import javax.swing.WindowConstants;
import javax.swing.event.TableModelEvent;
import javax.swing.event.TableModelListener;
import javax.swing.filechooser.FileFilter;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableCellEditor;
import javax.swing.table.TableCellRenderer;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.awt.Window;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Modal Project Builder dialog. Replaces the bare {@code DirectoryChooser}
 * entry points by letting the user assemble a {@link ProjectFile} from one
 * or more folders / files, edit per-file metadata (animal, hemisphere,
 * region, condition), and pick an output root that can differ from the
 * source folder.
 *
 * <p>On OK, the dialog writes {@code <outputRoot>/FLASH/Config/.settings/project.json}
 * and records the open in the recent-projects list.
 *
 * <p>Series count for each added file is probed asynchronously so the UI
 * does not block while Bio-Formats parses metadata.
 */
public final class ProjectBuilderDialog {

    public static final class Result {
        public final ProjectFile project;
        /** Absolute path to the chosen output root — what gets passed as "directory" to analyses. */
        public final File outputRoot;
        /** Absolute path to the saved project.json. */
        public final File projectFile;

        Result(ProjectFile project, File outputRoot, File projectFile) {
            this.project = project;
            this.outputRoot = outputRoot;
            this.projectFile = projectFile;
        }
    }

    private static final String WRITER_ID = "FLASH-ProjectBuilder";

    private final JDialog dialog;
    private final JTextField nameField;
    private final JTextField outputRootField;
    private final ProjectManifestTableModel model;
    private final JTable table;
    private JLabel reviewSummary;
    private int popupColumn = -1;
    private final File pluginsDir;
    private final ExecutorService probeExecutor =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "FLASH-series-probe");
                t.setDaemon(true);
                return t;
            });
    private final AtomicInteger probeGeneration = new AtomicInteger();
    private volatile boolean closed;

    private Result result;

    private ProjectBuilderDialog(Window owner, File pluginsDir, File suggestedSourceFolder) {
        this.pluginsDir = pluginsDir;
        this.model = new ProjectManifestTableModel();

        dialog = new JDialog(owner, "FLASH project", JDialog.ModalityType.APPLICATION_MODAL);
        dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);

        JPanel content = new JPanel(new BorderLayout(8, 8));
        content.setBorder(BorderFactory.createEmptyBorder(12, 14, 12, 14));
        content.setBackground(FlashTheme.SURFACE);

        nameField = new JTextField(28);
        outputRootField = new JTextField(40);
        if (suggestedSourceFolder != null) {
            outputRootField.setText(suggestedSourceFolder.getAbsolutePath());
            nameField.setText(suggestedSourceFolder.getName());
        }
        content.add(buildHeader(), BorderLayout.NORTH);

        table = new JTable(model) {
            @Override
            public Component prepareRenderer(TableCellRenderer renderer, int row, int column) {
                Component c = super.prepareRenderer(renderer, row, column);
                applyConfidenceCue(c, row, column);
                return c;
            }

            @Override
            public String getToolTipText(MouseEvent event) {
                int row = rowAtPoint(event.getPoint());
                int column = columnAtPoint(event.getPoint());
                String tip = confidenceTooltip(row, column);
                return tip != null ? tip : super.getToolTipText(event);
            }
        };
        table.setAutoCreateRowSorter(false);
        table.setFillsViewportHeight(true);
        table.setRowHeight(22);
        table.getTableHeader().setReorderingAllowed(false);
        applyColumnPreferences();
        attachTableStructureRefresh();
        attachContextMenu();
        attachDragAndDrop();
        attachExpansionClick();

        JScrollPane scroll = new JScrollPane(table);
        scroll.setPreferredSize(new Dimension(960, 320));
        scroll.setBorder(BorderFactory.createLineBorder(FlashTheme.BORDER));

        JPanel center = new JPanel(new BorderLayout(0, 6));
        center.setBackground(FlashTheme.SURFACE);
        center.add(buildToolbar(), BorderLayout.NORTH);
        center.add(scroll, BorderLayout.CENTER);
        center.add(buildHintRow(), BorderLayout.SOUTH);
        content.add(center, BorderLayout.CENTER);

        content.add(buildFooter(), BorderLayout.SOUTH);

        dialog.setContentPane(content);
        if (suggestedSourceFolder != null) {
            loadExistingProjectIfPresent(suggestedSourceFolder, false);
        }
        model.addTableModelListener(new TableModelListener() {
            @Override public void tableChanged(TableModelEvent e) {
                refreshReviewSummary();
            }
        });
        refreshReviewSummary();
        dialog.pack();
        dialog.setMinimumSize(new Dimension(720, 480));
        dialog.setLocationRelativeTo(owner);
    }

    /** Show the dialog modally. Returns the result on OK, or {@code null} on cancel. */
    public static Result open(Window owner, File pluginsDir, File suggestedSourceFolder) {
        if (java.awt.GraphicsEnvironment.isHeadless()) {
            throw new IllegalStateException("ProjectBuilderDialog cannot run headless.");
        }
        ProjectBuilderDialog dlg = new ProjectBuilderDialog(owner, pluginsDir, suggestedSourceFolder);
        try {
            dlg.dialog.setVisible(true);
            return dlg.result;
        } finally {
            dlg.closed = true;
            dlg.probeGeneration.incrementAndGet();
            dlg.probeExecutor.shutdownNow();
        }
    }

    // ── Layout helpers ─────────────────────────────────────────────────────

    private JPanel buildHeader() {
        JPanel panel = new JPanel();
        panel.setLayout(new BorderLayout(0, 6));
        panel.setBackground(FlashTheme.SURFACE);

        JPanel nameRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        nameRow.setBackground(FlashTheme.SURFACE);
        JLabel nameLabel = new JLabel("Project name:");
        nameLabel.setForeground(FlashTheme.TEXT_HEADER);
        nameRow.add(nameLabel);
        nameRow.add(nameField);

        JPanel outRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        outRow.setBackground(FlashTheme.SURFACE);
        JLabel outLabel = new JLabel("Output root:");
        outLabel.setForeground(FlashTheme.TEXT_HEADER);
        JButton browse = new JButton("Browse…");
        browse.addActionListener(new ActionListener() {
            @Override public void actionPerformed(ActionEvent e) {
                JFileChooser chooser = new JFileChooser();
                chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
                if (!outputRootField.getText().trim().isEmpty()) {
                    chooser.setCurrentDirectory(new File(outputRootField.getText().trim()));
                }
                if (chooser.showOpenDialog(dialog) == JFileChooser.APPROVE_OPTION) {
                    outputRootField.setText(chooser.getSelectedFile().getAbsolutePath());
                }
            }
        });
        outRow.add(outLabel);
        outRow.add(outputRootField);
        outRow.add(browse);

        panel.add(nameRow, BorderLayout.NORTH);
        panel.add(outRow, BorderLayout.CENTER);
        return panel;
    }

    private JPanel buildToolbar() {
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        bar.setBackground(FlashTheme.SURFACE);

        JButton addFolder = new JButton("Add folder…");
        JButton addFiles = new JButton("Add files…");
        JButton openRecent = new JButton("Open recent…");
        JButton openProject = new JButton("Open project...");
        JButton remove = new JButton("Remove selected");

        addFolder.addActionListener(new ActionListener() {
            @Override public void actionPerformed(ActionEvent e) {
                JFileChooser chooser = new JFileChooser();
                chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
                if (chooser.showOpenDialog(dialog) == JFileChooser.APPROVE_OPTION) {
                    File selected = chooser.getSelectedFile();
                    if (!loadExistingProjectIfPresent(selected, true)) {
                        addFolderRecursively(selected);
                        seedOutputRootIfBlank(selected);
                    }
                }
            }
        });

        addFiles.addActionListener(new ActionListener() {
            @Override public void actionPerformed(ActionEvent e) {
                JFileChooser chooser = new JFileChooser();
                chooser.setMultiSelectionEnabled(true);
                chooser.setFileFilter(new FileFilter() {
                    @Override public boolean accept(File f) {
                        if (f.isDirectory()) return true;
                        return isAcceptedSource(f);
                    }
                    @Override public String getDescription() {
                        return "Microscopy files (.lif .czi .nd2 .ome.tif .tif …)";
                    }
                });
                if (chooser.showOpenDialog(dialog) == JFileChooser.APPROVE_OPTION) {
                    File[] files = chooser.getSelectedFiles();
                    for (File f : files) addFileSafely(f);
                    if (files.length > 0) seedOutputRootIfBlank(files[0].getParentFile());
                }
            }
        });

        openRecent.addActionListener(new ActionListener() {
            @Override public void actionPerformed(ActionEvent e) {
                openRecentPicker();
            }
        });

        openProject.addActionListener(new ActionListener() {
            @Override public void actionPerformed(ActionEvent e) {
                openProjectChooser();
            }
        });

        remove.addActionListener(new ActionListener() {
            @Override public void actionPerformed(ActionEvent e) {
                removeSelectedRows();
            }
        });

        JButton reviewNext = new JButton("Review next");
        reviewNext.setToolTipText("Jump to the next row with a low-confidence auto-detected value.");
        reviewNext.addActionListener(new ActionListener() {
            @Override public void actionPerformed(ActionEvent e) {
                jumpToNextLowConfidence();
            }
        });

        reviewSummary = new JLabel("");
        reviewSummary.setForeground(FlashTheme.TEXT_MUTED);

        JButton importRoster = new JButton("Import roster…");
        importRoster.setToolTipText("Merge a colony-roster CSV/TSV by AnimalName (validated first).");
        importRoster.addActionListener(new ActionListener() {
            @Override public void actionPerformed(ActionEvent e) { importRosterAction(); }
        });
        JButton exportRoster = new JButton("Export roster…");
        exportRoster.setToolTipText("Write a roster template (AnimalName, Condition_<axis>…) pre-filled with current values.");
        exportRoster.addActionListener(new ActionListener() {
            @Override public void actionPerformed(ActionEvent e) { exportRosterAction(); }
        });
        JButton editGrammar = new JButton("Edit grammar…");
        editGrammar.setToolTipText("Define a naming grammar to auto-fill identity from filenames (live preview).");
        editGrammar.addActionListener(new ActionListener() {
            @Override public void actionPerformed(ActionEvent e) {
                IdentityGrammarDialog.show(dialog, model, grammarProjectDir());
                applyColumnPreferences();
            }
        });

        bar.add(addFolder);
        bar.add(addFiles);
        bar.add(openProject);
        bar.add(openRecent);
        bar.add(Box.createHorizontalStrut(12));
        bar.add(remove);
        bar.add(Box.createHorizontalStrut(12));
        bar.add(importRoster);
        bar.add(exportRoster);
        bar.add(editGrammar);
        bar.add(Box.createHorizontalStrut(12));
        bar.add(reviewNext);
        bar.add(reviewSummary);
        return bar;
    }

    /** Project directory used to store/load naming grammars (output root, else CWD). */
    private String grammarProjectDir() {
        String out = outputRootField.getText() == null ? "" : outputRootField.getText().trim();
        if (!out.isEmpty()) return out;
        return System.getProperty("user.dir", ".");
    }

    private void exportRosterAction() {
        JFileChooser chooser = new JFileChooser();
        chooser.setSelectedFile(new File("roster.csv"));
        if (chooser.showSaveDialog(dialog) != JFileChooser.APPROVE_OPTION) return;
        File f = chooser.getSelectedFile();
        boolean tsv = f.getName().toLowerCase(Locale.ROOT).endsWith(".tsv");
        String text = RosterIO.export(model.toConditionAssignments(), tsv);
        try {
            java.nio.file.Files.write(f.toPath(), text.getBytes("UTF-8"));
            IJ.log("[FLASH] Roster exported: " + f.getAbsolutePath());
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(dialog, "Could not write roster: " + ex.getMessage(),
                    "Export roster", JOptionPane.WARNING_MESSAGE);
        }
    }

    private void importRosterAction() {
        JFileChooser chooser = new JFileChooser();
        if (chooser.showOpenDialog(dialog) != JFileChooser.APPROVE_OPTION) return;
        File f = chooser.getSelectedFile();
        String text;
        try {
            text = new String(java.nio.file.Files.readAllBytes(f.toPath()), "UTF-8");
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(dialog, "Could not read roster: " + ex.getMessage(),
                    "Import roster", JOptionPane.WARNING_MESSAGE);
            return;
        }
        RosterIO.Roster roster = RosterIO.parse(text);
        RosterIO.Validation v = RosterIO.validate(roster, model.toConditionAssignments());
        StringBuilder summary = new StringBuilder();
        summary.append("Roster: ").append(roster.byAnimal.size()).append(" animal(s), ")
                .append(roster.axes.size()).append(" condition column(s).\n")
                .append("Unmatched (not in project): ").append(v.unmatched.size()).append('\n')
                .append("Duplicate rows in roster: ").append(v.duplicates.size()).append('\n')
                .append("Conflicts with confirmed values: ").append(v.conflicts.size()).append("\n\n")
                .append("Apply roster values to matching animals? Confirmed cells are preserved.");
        int choice = JOptionPane.showConfirmDialog(dialog, summary.toString(), "Import roster",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE);
        if (choice != JOptionPane.OK_OPTION) return;
        int n = model.importRoster(roster.axes, roster.byAnimal, false);
        applyColumnPreferences();
        IJ.log("[FLASH] Roster import updated " + n + " cell(s) across " + roster.byAnimal.size() + " animal(s).");
    }

    private JLabel buildHintRow() {
        JLabel hint = new JLabel("Tip: drag files or folders here. Click the ▸ triangle on a multi-series file "
                + "to set per-series details. A series needs a hemisphere (LH/RH) for its override to take effect.");
        hint.setForeground(FlashTheme.TEXT_MUTED);
        return hint;
    }

    private JPanel buildFooter() {
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        bar.setBackground(FlashTheme.SURFACE);
        JButton cancel = new JButton("Cancel");
        JButton save = new JButton("Save & Open");
        cancel.addActionListener(new ActionListener() {
            @Override public void actionPerformed(ActionEvent e) {
                result = null;
                dialog.dispose();
            }
        });
        save.addActionListener(new ActionListener() {
            @Override public void actionPerformed(ActionEvent e) {
                onSave();
            }
        });
        bar.add(cancel);
        bar.add(save);
        dialog.getRootPane().setDefaultButton(save);
        return bar;
    }

    private void applyColumnPreferences() {
        for (int c = 0; c < table.getColumnCount(); c++) {
            table.getColumnModel().getColumn(c).setPreferredWidth(preferredWidthForColumn(c));
        }
        if (hasColumn(ProjectManifestTableModel.COL_HEMISPHERE)) {
            installEditableCombo(ProjectManifestTableModel.COL_HEMISPHERE,
                    choices("", "LH", "RH"));
        }
        if (hasColumn(ProjectManifestTableModel.COL_REGION)) {
            table.getColumnModel().getColumn(ProjectManifestTableModel.COL_REGION)
                    .setCellEditor(new RegionTableCellEditor());
        }
        for (int c = ProjectManifestTableModel.COL_CONDITION; c < model.notesColumn(); c++) {
            ConditionAxis axis = model.conditionAxisAtColumn(c);
            installEditableCombo(c, conditionChoices(axis));
        }
        if (hasColumn(ProjectManifestTableModel.COL_FILE)) {
            table.getColumnModel().getColumn(ProjectManifestTableModel.COL_FILE)
                    .setCellRenderer(new FileColumnRenderer());
        }
    }

    private void attachTableStructureRefresh() {
        model.addTableModelListener(new TableModelListener() {
            @Override public void tableChanged(TableModelEvent e) {
                if (e.getFirstRow() == TableModelEvent.HEADER_ROW) {
                    SwingUtilities.invokeLater(new Runnable() {
                        @Override public void run() {
                            applyColumnPreferences();
                        }
                    });
                }
            }
        });
    }

    private int preferredWidthForColumn(int column) {
        if (column == ProjectManifestTableModel.COL_INCLUDE) return 60;
        if (column == ProjectManifestTableModel.COL_FILE) return 280;
        if (column == ProjectManifestTableModel.COL_SERIES) return 110;
        if (column == ProjectManifestTableModel.COL_ANIMAL) return 110;
        if (column == ProjectManifestTableModel.COL_HEMISPHERE) return 100;
        if (column == ProjectManifestTableModel.COL_REGION) return 100;
        if (model.isConditionColumn(column)) return 120;
        if (column == model.notesColumn()) return 160;
        return 100;
    }

    private boolean hasColumn(int column) {
        return column >= 0 && column < table.getColumnCount();
    }

    private void installEditableCombo(int column, List<String> values) {
        if (!hasColumn(column)) return;
        JComboBox<String> combo = new JComboBox<String>(values.toArray(new String[values.size()]));
        combo.setEditable(true);
        table.getColumnModel().getColumn(column).setCellEditor(new DefaultCellEditor(combo));
    }

    private List<String> conditionChoices(ConditionAxis axis) {
        LinkedHashSet<String> values = new LinkedHashSet<String>();
        values.add("");
        if (axis != null) {
            values.addAll(model.distinctConditionValues(axis.id));
        }
        return new ArrayList<String>(values);
    }

    private static List<String> choices(String... values) {
        List<String> out = new ArrayList<String>();
        if (values != null) {
            for (String value : values) {
                out.add(value == null ? "" : value);
            }
        }
        return out;
    }

    /**
     * Draws the File column as a tree: a disclosure triangle on expandable
     * container files and an indent on the per-series child rows beneath them.
     */
    private final class FileColumnRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable t, Object value, boolean selected,
                                                       boolean focused, int row, int column) {
            super.getTableCellRendererComponent(t, value, selected, focused, row, column);
            String text = value == null ? "" : value.toString();
            if (model.isSeriesRow(row)) {
                setText("        " + text);
            } else if (model.isExpandableFileRow(row)) {
                setText((model.isExpanded(row) ? "▾  " : "▸  ") + text);
            } else {
                setText("     " + text);
            }
            return this;
        }
    }

    // ── Stage 09: confidence cue (tint + glyph) + provenance tooltip ────────

    private boolean isDetectableColumn(int column) {
        return column == ProjectManifestTableModel.COL_ANIMAL
                || column == ProjectManifestTableModel.COL_HEMISPHERE
                || column == ProjectManifestTableModel.COL_REGION
                || model.isConditionColumn(column);
    }

    /** Tint + glyph an identity cell by its auto-detection confidence; neutral for user-set / undetected. */
    private void applyConfidenceCue(Component c, int row, int column) {
        if (row < 0 || column < 0 || row >= model.getRowCount()) return;
        if (table.isRowSelected(row)) return;            // keep selection highlight
        boolean detected = isDetectableColumn(column)
                && !model.isUserSet(row, column)
                && !model.provenanceAt(row, column).isEmpty();
        if (!detected) {
            c.setBackground(table.getBackground());
            return;
        }
        Confidence confidence = model.confidenceAt(row, column);
        Color tint = confidenceColor(confidence);
        c.setBackground(tint != null ? tint : table.getBackground());
        if (c instanceof JLabel) {
            JLabel label = (JLabel) c;
            String glyph = confidenceGlyph(confidence);
            String text = label.getText();
            if (!glyph.isEmpty() && text != null && !text.isEmpty()) {
                label.setText(glyph + "  " + text);
            }
        }
    }

    private static Color confidenceColor(Confidence confidence) {
        if (confidence == null) return null;
        switch (confidence) {
            case HIGH:   return FlashTheme.PRIMARY_BG;
            case MEDIUM: return FlashTheme.WARNING_BG;
            case LOW:
            case NONE:   return FlashTheme.SURFACE_MUTED;
            default:     return null;
        }
    }

    private static String confidenceGlyph(Confidence confidence) {
        if (confidence == null) return "";
        switch (confidence) {
            case HIGH:   return "✓";   // check
            case MEDIUM: return "•";   // bullet
            case LOW:
            case NONE:   return "?";
            default:     return "";
        }
    }

    private String confidenceTooltip(int row, int column) {
        if (row < 0 || column < 0 || row >= model.getRowCount()) return null;
        if (!isDetectableColumn(column)) return null;
        if (model.isUserSet(row, column)) return "Set by you";
        String provenance = model.provenanceAt(row, column);
        if (provenance == null || provenance.isEmpty()) return null;
        Confidence confidence = model.confidenceAt(row, column);
        return "<html>" + escapeHtml(provenance) + "<br/><i>confidence: "
                + (confidence == null ? "none" : confidence.name().toLowerCase(Locale.ROOT))
                + "</i></html>";
    }

    private static String escapeHtml(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private int lastReviewedRow = -1;

    /** Select the next low/none-confidence row that still needs review (wraps around). */
    private void jumpToNextLowConfidence() {
        int next = model.nextLowConfidenceRow(lastReviewedRow);
        if (next < 0) next = model.nextLowConfidenceRow(-1);   // wrap to top
        if (next < 0) {
            JOptionPane.showMessageDialog(dialog,
                    "No rows need review — every detected identity is high-confidence or confirmed.",
                    "Review", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        lastReviewedRow = next;
        table.getSelectionModel().setSelectionInterval(next, next);
        table.scrollRectToVisible(table.getCellRect(next, 0, true));
    }

    private void refreshReviewSummary() {
        if (reviewSummary == null) return;
        int total = model.getRowCount();
        if (total == 0) {
            reviewSummary.setText("");
            return;
        }
        int high = model.highConfidenceRowCount();
        int needReview = total - high;
        reviewSummary.setText(high + "/" + total + " high-confidence"
                + (needReview > 0 ? "; " + needReview + " need review" : ""));
    }

    // ── Context menu / DnD / series shortcut ───────────────────────────────

    private void attachContextMenu() {
        final JPopupMenu menu = new JPopupMenu();
        JMenuItem setAnimal = new JMenuItem("Set animal ID…");
        JMenuItem setHemisphere = new JMenuItem("Set hemisphere…");
        JMenuItem setRegion = new JMenuItem("Set region…");
        JMenuItem setCondition = new JMenuItem("Set condition…");
        final JMenuItem renameCondition = new JMenuItem("Rename condition value…");
        final JMenuItem mergeSimilar = new JMenuItem("Merge similar values…");
        final JMenuItem fillDown = new JMenuItem("Fill down");
        final JMenuItem fillBlanks = new JMenuItem("Fill blanks (from above)");
        final JMenuItem applySameAnimal = new JMenuItem("Apply cell to same animal");
        final JMenuItem toggleSeries = new JMenuItem("Expand series");
        JMenuItem removeRow = new JMenuItem("Remove");

        setAnimal.addActionListener(new ActionListener() {
            @Override public void actionPerformed(ActionEvent e) {
                bulkSetIdentity(ProjectManifestTableModel.COL_ANIMAL, "animal ID", null);
            }
        });
        setHemisphere.addActionListener(new ActionListener() {
            @Override public void actionPerformed(ActionEvent e) {
                bulkSetIdentity(ProjectManifestTableModel.COL_HEMISPHERE, "hemisphere",
                        new String[]{"", "LH", "RH"});
            }
        });
        setRegion.addActionListener(new ActionListener() {
            @Override public void actionPerformed(ActionEvent e) {
                bulkSetIdentity(ProjectManifestTableModel.COL_REGION, "region", null);
            }
        });
        setCondition.addActionListener(new ActionListener() {
            @Override public void actionPerformed(ActionEvent e) {
                bulkSetCondition();
            }
        });
        renameCondition.addActionListener(new ActionListener() {
            @Override public void actionPerformed(ActionEvent e) {
                renameConditionValueAction();
            }
        });
        mergeSimilar.addActionListener(new ActionListener() {
            @Override public void actionPerformed(ActionEvent e) {
                mergeSimilarConditionsAction();
            }
        });
        fillDown.addActionListener(new ActionListener() {
            @Override public void actionPerformed(ActionEvent e) {
                int col = fillColumn();
                if (col >= 0) { model.fillDown(table.getSelectedRows(), col); applyColumnPreferences(); }
            }
        });
        fillBlanks.addActionListener(new ActionListener() {
            @Override public void actionPerformed(ActionEvent e) {
                int col = fillColumn();
                if (col >= 0) { model.fillBlanks(table.getSelectedRows(), col); applyColumnPreferences(); }
            }
        });
        applySameAnimal.addActionListener(new ActionListener() {
            @Override public void actionPerformed(ActionEvent e) {
                int col = fillColumn();
                if (col >= 0) { model.applyToSameAnimal(table.getSelectedRows(), col); applyColumnPreferences(); }
            }
        });
        toggleSeries.addActionListener(new ActionListener() {
            @Override public void actionPerformed(ActionEvent e) {
                int[] sel = table.getSelectedRows();
                if (sel.length == 0) return;
                toggleExpand(sel[0]);
            }
        });
        removeRow.addActionListener(new ActionListener() {
            @Override public void actionPerformed(ActionEvent e) {
                removeSelectedRows();
            }
        });

        menu.add(setAnimal);
        menu.add(setHemisphere);
        menu.add(setRegion);
        menu.add(setCondition);
        menu.addSeparator();
        menu.add(renameCondition);
        menu.add(mergeSimilar);
        menu.addSeparator();
        menu.add(fillDown);
        menu.add(fillBlanks);
        menu.add(applySameAnimal);
        menu.addSeparator();
        menu.add(toggleSeries);
        menu.addSeparator();
        menu.add(removeRow);

        table.addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) { maybeShow(e); }
            @Override public void mouseReleased(MouseEvent e) { maybeShow(e); }
            private void maybeShow(MouseEvent e) {
                if (!e.isPopupTrigger()) return;
                int row = table.rowAtPoint(e.getPoint());
                if (row >= 0 && !table.isRowSelected(row)) {
                    table.setRowSelectionInterval(row, row);
                }
                popupColumn = table.columnAtPoint(e.getPoint());
                boolean fillable = isDetectableColumn(fillColumn());
                fillDown.setEnabled(fillable);
                fillBlanks.setEnabled(fillable);
                applySameAnimal.setEnabled(fillable);
                boolean conditionCol = model.isConditionColumn(popupColumn);
                renameCondition.setEnabled(conditionCol);
                mergeSimilar.setEnabled(conditionCol);
                boolean expandable = row >= 0 && model.isExpandableFileRow(row);
                toggleSeries.setEnabled(expandable);
                toggleSeries.setText(expandable && model.isExpanded(row)
                        ? "Collapse series" : "Expand series");
                menu.show(e.getComponent(), e.getX(), e.getY());
            }
        });
    }

    /** Column targeted by fill actions: the cell under the popup, falling back to the focused column. */
    private int fillColumn() {
        if (isDetectableColumn(popupColumn)) return popupColumn;
        int focused = table.getSelectedColumn();
        return isDetectableColumn(focused) ? focused : -1;
    }

    /** Rename the clicked condition cell's value across every row for that axis. */
    private void renameConditionValueAction() {
        int col = popupColumn;
        if (!model.isConditionColumn(col)) return;
        int row = table.getSelectedRow();
        if (row < 0) return;
        ConditionAxis axis = model.conditionAxisAtColumn(col);
        if (axis == null) return;
        Object cur = model.getValueAt(row, col);
        String current = cur == null ? "" : cur.toString();
        String to = JOptionPane.showInputDialog(dialog,
                "Rename \"" + current + "\" (" + axisLabel(axis) + ") on all matching rows to:",
                current);
        if (to == null) return;
        int n = model.renameConditionValue(axis.id, current, to);
        applyColumnPreferences();
        IJ.log("[FLASH] Renamed condition value on " + n + " row(s).");
    }

    /** Offer to merge fuzzy-similar values for the clicked condition axis. */
    private void mergeSimilarConditionsAction() {
        int col = popupColumn;
        if (!model.isConditionColumn(col)) return;
        ConditionAxis axis = model.conditionAxisAtColumn(col);
        if (axis == null) return;
        List<List<String>> groups = model.fuzzyConditionGroups(axis.id, 2);
        if (groups.isEmpty()) {
            JOptionPane.showMessageDialog(dialog,
                    "No similar values found for " + axisLabel(axis) + ".",
                    "Merge similar values", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        for (List<String> group : groups) {
            String target = (String) JOptionPane.showInputDialog(dialog,
                    "These look similar — merge into one?\n" + group + "\nKeep as:",
                    "Merge similar values", JOptionPane.QUESTION_MESSAGE,
                    null, group.toArray(new String[group.size()]), group.get(0));
            if (target == null) continue;   // skip this group
            model.mergeConditionValues(axis.id, group, target);
        }
        applyColumnPreferences();
    }

    private void attachExpansionClick() {
        table.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                int row = table.rowAtPoint(e.getPoint());
                int col = table.columnAtPoint(e.getPoint());
                if (row < 0) return;
                // Clicking the File cell (the triangle) of a multi-series
                // container toggles its per-series rows. Series rows ignore it.
                if (col == ProjectManifestTableModel.COL_FILE && model.isExpandableFileRow(row)) {
                    toggleExpand(row);
                }
            }
        });
    }

    @SuppressWarnings("unchecked")
    private void attachDragAndDrop() {
        table.setTransferHandler(new TransferHandler() {
            @Override public boolean canImport(TransferSupport support) {
                return support.isDataFlavorSupported(DataFlavor.javaFileListFlavor);
            }
            @Override public boolean importData(TransferSupport support) {
                if (!canImport(support)) return false;
                try {
                    Transferable t = support.getTransferable();
                    List<File> files = (List<File>) t.getTransferData(DataFlavor.javaFileListFlavor);
                    for (File f : files) {
                        if (f.isDirectory()) {
                            addFolderRecursively(f);
                            seedOutputRootIfBlank(f);
                        } else {
                            addFileSafely(f);
                            seedOutputRootIfBlank(f.getParentFile());
                        }
                    }
                    return true;
                } catch (Exception ex) {
                    IJ.log("[FLASH] Drag-drop failed: " + ex.getMessage());
                    return false;
                }
            }
        });
    }

    // ── Actions ────────────────────────────────────────────────────────────

    private void addFolderRecursively(File folder) {
        if (folder == null || !folder.isDirectory()) return;
        List<File> hits = new ArrayList<File>();
        collectAcceptedSources(folder, hits);
        Collections.sort(hits, new Comparator<File>() {
            @Override public int compare(File a, File b) {
                return a.getName().compareToIgnoreCase(b.getName());
            }
        });
        for (File f : hits) addFileSafely(f);
    }

    private void addFileSafely(File file) {
        if (file == null || !file.isFile() || !isAcceptedSource(file)) return;
        if (model.containsSource(file)) return;
        final int row = model.addFile(file);
        probeSeriesCountAsync(row, file);
    }

    private void probeSeriesCountAsync(final int row, final File source) {
        if (closed) return;
        final String lowered = source.getName().toLowerCase(Locale.ROOT);
        final boolean isContainer = isContainerExtension(lowered);
        final int generation = probeGeneration.get();
        try {
            probeExecutor.submit(new SwingWorker<Integer, Void>() {
                @Override protected Integer doInBackground() throws Exception {
                    if (!isContainer) return Integer.valueOf(1);
                    return Integer.valueOf(LifIO.getSeriesCount(source));
                }
                @Override protected void done() {
                    if (closed || generation != probeGeneration.get() || !dialog.isDisplayable()) {
                        return;
                    }
                    try {
                        int count = get().intValue();
                        if (row < model.getRowCount() && model.get(row).source.equals(source)) {
                            model.setSeriesCount(row, count);
                        }
                    } catch (Exception e) {
                        IJ.log("[FLASH] Series probe failed for " + source.getName() + ": " + e.getMessage());
                    }
                }
            });
        } catch (RejectedExecutionException e) {
            if (!closed) {
                IJ.log("[FLASH] Series probe was rejected for " + source.getName() + ": " + e.getMessage());
            }
        }
    }

    /**
     * Toggle the per-series rows of a multi-series container. The first
     * expansion lazily reads the series names from the file so each series row
     * can be pre-filled and edited independently.
     */
    private void toggleExpand(int rowIndex) {
        if (!model.isExpandableFileRow(rowIndex)) {
            return;
        }
        if (model.isExpanded(rowIndex)) {
            model.setExpanded(rowIndex, false);
            return;
        }
        ProjectManifestTableModel.Row row = model.getFile(model.fileIndexAt(rowIndex));
        if (row.series.isEmpty() && !populateSeriesEntries(rowIndex, row)) {
            return;
        }
        model.setExpanded(rowIndex, true);
    }

    /** Probe the source's series names and build the per-series rows. */
    private boolean populateSeriesEntries(int rowIndex, ProjectManifestTableModel.Row row) {
        File source = row.source;
        if (source == null) {
            return false;
        }
        List<ProjectManifestTableModel.SeriesEntry> entries =
                new ArrayList<ProjectManifestTableModel.SeriesEntry>();
        Cursor previous = dialog.getCursor();
        try {
            dialog.setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
            for (SeriesMeta m : LifIO.readAllSeriesMetadata(source)) {
                entries.add(new ProjectManifestTableModel.SeriesEntry(m.index, m.name));
            }
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(dialog,
                    "Could not read series from this file:\n" + ex.getMessage(),
                    "Series unavailable", JOptionPane.WARNING_MESSAGE);
            return false;
        } finally {
            dialog.setCursor(previous);
        }
        if (entries.isEmpty()) {
            JOptionPane.showMessageDialog(dialog,
                    "This file reports no series.",
                    "No series", JOptionPane.INFORMATION_MESSAGE);
            return false;
        }
        model.setSeriesEntries(rowIndex, entries);
        return true;
    }

    /**
     * Context-menu bulk assign for a fixed identity column (animal / hemisphere /
     * region). When {@code options} is non-null the prompt shows a fixed dropdown
     * (e.g. LH/RH); otherwise it is a free-text field seeded with the current
     * value of a single selection. Works on mixed file+series selections.
     */
    private void bulkSetIdentity(int column, String label, String[] options) {
        int[] sel = table.getSelectedRows();
        if (sel.length == 0) return;
        String preset = sel.length == 1 ? identityPreset(sel[0], column) : "";
        String value = (String) JOptionPane.showInputDialog(dialog,
                "Set " + label + " for " + sel.length + " selected row(s):",
                "Set " + label,
                JOptionPane.PLAIN_MESSAGE,
                null, options, preset);
        if (value == null) return;   // cancelled
        model.setIdentityForRows(sel, column, value);
        applyColumnPreferences();
    }

    private String identityPreset(int row, int column) {
        Object value = model.getValueAt(row, column);
        return value == null ? "" : value.toString();
    }

    private void bulkSetCondition() {
        int[] sel = table.getSelectedRows();
        if (sel.length == 0) return;
        List<ConditionAxis> axes = visibleConditionAxes();
        if (axes.isEmpty()) return;
        ConditionAxis initialAxis = defaultBulkConditionAxis(axes);
        if (axes.size() == 1) {
            String preset = bulkConditionPreset(sel, initialAxis);
            String label = axisLabel(initialAxis);
            String value = (String) JOptionPane.showInputDialog(dialog,
                    "Set " + label + " for " + sel.length + " selected row(s):",
                    "Set " + label,
                    JOptionPane.PLAIN_MESSAGE,
                    null, null, preset);
            if (value == null) return;
            model.setConditionForRows(sel, initialAxis.id, value);
            applyColumnPreferences();
            return;
        }

        final JComboBox<ConditionAxisChoice> axisCombo =
                new JComboBox<ConditionAxisChoice>(axisChoices(axes));
        axisCombo.setSelectedItem(new ConditionAxisChoice(initialAxis));
        final JTextField valueField = new JTextField(bulkConditionPreset(sel, initialAxis), 20);
        axisCombo.addActionListener(new ActionListener() {
            @Override public void actionPerformed(ActionEvent e) {
                ConditionAxisChoice choice = (ConditionAxisChoice) axisCombo.getSelectedItem();
                if (choice != null) {
                    valueField.setText(bulkConditionPreset(sel, choice.axis));
                }
            }
        });

        JPanel panel = new JPanel(new GridLayout(0, 1, 4, 4));
        panel.add(new JLabel("Axis:"));
        panel.add(axisCombo);
        panel.add(new JLabel("Value:"));
        panel.add(valueField);

        int choice = JOptionPane.showConfirmDialog(dialog, panel,
                "Set condition", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (choice != JOptionPane.OK_OPTION) return;
        ConditionAxisChoice selected = (ConditionAxisChoice) axisCombo.getSelectedItem();
        if (selected == null) return;
        model.setConditionForRows(sel, selected.axis.id, valueField.getText());
        applyColumnPreferences();
    }

    private List<ConditionAxis> visibleConditionAxes() {
        List<ConditionAxis> axes = new ArrayList<ConditionAxis>();
        for (int c = ProjectManifestTableModel.COL_CONDITION; c < model.notesColumn(); c++) {
            ConditionAxis axis = model.conditionAxisAtColumn(c);
            if (axis != null) axes.add(axis);
        }
        return axes;
    }

    private ConditionAxis defaultBulkConditionAxis(List<ConditionAxis> axes) {
        int selectedColumn = table.getSelectedColumn();
        if (model.isConditionColumn(selectedColumn)) {
            ConditionAxis axis = model.conditionAxisAtColumn(selectedColumn);
            if (axis != null) return axis;
        }
        return axes.get(0);
    }

    private String bulkConditionPreset(int[] selectedRows, ConditionAxis axis) {
        if (selectedRows == null || selectedRows.length != 1 || axis == null) return "";
        int column = model.conditionColumnForAxis(axis.id);
        if (column < 0) return "";
        Object value = model.getValueAt(selectedRows[0], column);
        return value == null ? "" : value.toString();
    }

    private static ConditionAxisChoice[] axisChoices(List<ConditionAxis> axes) {
        ConditionAxisChoice[] choices = new ConditionAxisChoice[axes.size()];
        for (int i = 0; i < axes.size(); i++) {
            choices[i] = new ConditionAxisChoice(axes.get(i));
        }
        return choices;
    }

    private static String axisLabel(ConditionAxis axis) {
        return axis == null || axis.label == null || axis.label.trim().isEmpty()
                ? "Condition" : axis.label.trim();
    }

    private static final class ConditionAxisChoice {
        final ConditionAxis axis;

        ConditionAxisChoice(ConditionAxis axis) {
            this.axis = axis;
        }

        @Override public String toString() {
            return axisLabel(axis);
        }

        @Override public boolean equals(Object other) {
            if (!(other instanceof ConditionAxisChoice)) return false;
            ConditionAxisChoice o = (ConditionAxisChoice) other;
            if (axis == null || o.axis == null) return axis == o.axis;
            return axis.id.equals(o.axis.id);
        }

        @Override public int hashCode() {
            return axis == null ? 0 : axis.id.hashCode();
        }
    }

    private void removeSelectedRows() {
        int[] sel = table.getSelectedRows();
        // Selected rows may be file headers and/or their series children; map
        // each to its owning file, then delete files highest-index-first so the
        // remaining indexes stay valid.
        java.util.TreeSet<Integer> fileIndexes = new java.util.TreeSet<Integer>();
        for (int v : sel) {
            if (v >= 0 && v < model.getRowCount()) {
                fileIndexes.add(Integer.valueOf(model.fileIndexAt(v)));
            }
        }
        for (Integer fileIndex : fileIndexes.descendingSet()) {
            model.removeFile(fileIndex.intValue());
        }
    }

    private void openProjectChooser() {
        File recentRoot = mostRecentProjectRoot();
        File browseDir = recentRoot;
        if (recentRoot != null) {
            File parent = recentRoot.getParentFile();
            if (parent != null && parent.isDirectory()) {
                // Browse the folder that *contains* the recent project so the
                // project folder is listed and pre-selected, ready to open.
                browseDir = parent;
            }
        }
        File projectJson = chooseProjectJson("Open FLASH project", browseDir, recentRoot);
        if (projectJson != null) {
            loadProjectJson(projectJson, true, null);
        }
    }

    /**
     * The output-root folder of the most recently opened project that still
     * exists on disk, or {@code null}. Used to point the Open-project chooser
     * at a location the user will recognise.
     */
    private File mostRecentProjectRoot() {
        if (pluginsDir == null) {
            return null;
        }
        for (RecentProject recent : RecentProjectsStore.read(pluginsDir)) {
            if (recent == null || recent.path == null || recent.path.trim().isEmpty()) {
                continue;
            }
            File projectJson = ProjectPathResolver.resolveProjectJson(new File(recent.path));
            if (projectJson == null) {
                continue;
            }
            File root = FlashProjectLayout.projectRootForConfigurationDir(projectJson.getParentFile());
            if (root != null && root.isDirectory()) {
                return root;
            }
        }
        return null;
    }

    private void openRecentPicker() {
        if (pluginsDir == null) {
            JOptionPane.showMessageDialog(dialog,
                    "Recent projects list is unavailable (settings directory not found).",
                    "Recent projects", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        List<RecentProject> recents = RecentProjectsStore.read(pluginsDir);
        if (recents.isEmpty()) {
            JOptionPane.showMessageDialog(dialog,
                    "No recent projects yet.",
                    "Recent projects", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        final String[] labels = new String[recents.size()];
        for (int i = 0; i < recents.size(); i++) {
            RecentProject r = recents.get(i);
            labels[i] = (r.name.isEmpty() ? "(unnamed)" : r.name) + " - " + r.path;
        }
        JList<String> list = new JList<String>(labels);
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setSelectedIndex(0);
        list.setVisibleRowCount(Math.min(12, labels.length));
        JScrollPane scroll = new JScrollPane(list);
        scroll.setPreferredSize(new Dimension(680, Math.min(300, 28 * labels.length + 24)));

        int choice = JOptionPane.showConfirmDialog(dialog,
                scroll, "Open recent", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (choice != JOptionPane.OK_OPTION || list.getSelectedIndex() < 0) {
            return;
        }
        loadRecent(recents.get(list.getSelectedIndex()));
    }

    private void loadRecent(RecentProject recent) {
        if (recent == null || recent.path == null || recent.path.trim().isEmpty()) {
            return;
        }
        File storedProjectJson = new File(recent.path);
        File projectJson = ProjectPathResolver.resolveProjectJson(storedProjectJson);
        if (projectJson == null) {
            projectJson = locateMissingRecentProject(recent, storedProjectJson);
        }
        if (projectJson != null) {
            loadProjectJson(projectJson, true, recent.path);
        }
    }

    private File locateMissingRecentProject(RecentProject recent, File storedProjectJson) {
        int choice = JOptionPane.showConfirmDialog(dialog,
                "This recent project could not be found at its saved path:\n"
                        + storedProjectJson.getAbsolutePath()
                        + "\n\nLocate the moved project now?",
                "Recent project moved", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
        if (choice != JOptionPane.OK_OPTION) {
            return null;
        }
        File start = ProjectPathResolver.nearestExistingParent(storedProjectJson);
        return chooseProjectJson("Locate " + (recent.name.isEmpty() ? "FLASH project" : recent.name),
                start, null);
    }

    /**
     * Show the folder/file chooser and resolve the picked location to a
     * {@code project.json}.
     *
     * @param browseDir directory the chooser opens at, or {@code null}.
     * @param preselect file or folder highlighted on open, or {@code null}.
     */
    private File chooseProjectJson(String title, File browseDir, File preselect) {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle(title);
        chooser.setApproveButtonText("Open");
        chooser.setFileSelectionMode(JFileChooser.FILES_AND_DIRECTORIES);
        chooser.setAccessory(buildOpenProjectHint());
        chooser.setFileFilter(new FileFilter() {
            @Override public boolean accept(File f) {
                if (f == null) return false;
                return f.isDirectory() || ProjectFileIO.FILE_NAME.equalsIgnoreCase(f.getName());
            }
            @Override public String getDescription() {
                return "FLASH project folder (the folder that contains 'FLASH'), "
                        + "the FLASH folder, or project.json";
            }
        });
        if (browseDir != null && browseDir.isDirectory()) {
            chooser.setCurrentDirectory(browseDir);
        }
        if (preselect != null && preselect.exists()) {
            chooser.setSelectedFile(preselect);
        }
        if (chooser.showOpenDialog(dialog) != JFileChooser.APPROVE_OPTION) {
            return null;
        }
        // Resolve robustly: the explicit selection first, then the chooser's
        // current directory. The latter rescues the common case where the user
        // double-clicked the project (or FLASH) folder so the chooser navigated
        // *into* it and returned no selection.
        File projectJson = ProjectPathResolver.resolveProjectJsonNear(chooser.getSelectedFile());
        if (projectJson == null) {
            projectJson = ProjectPathResolver.resolveProjectJsonNear(chooser.getCurrentDirectory());
        }
        if (projectJson == null) {
            JOptionPane.showMessageDialog(dialog,
                    "That location does not contain a FLASH project.\n\n"
                            + "Pick the project folder (the one that holds the 'FLASH' folder),\n"
                            + "the 'FLASH' folder itself, or a project.json file.",
                    "No FLASH project here", JOptionPane.WARNING_MESSAGE);
        }
        return projectJson;
    }

    private JComponent buildOpenProjectHint() {
        JLabel hint = new JLabel("<html><div style='width:160px'>"
                + "<b>Open a FLASH project</b><br><br>"
                + "Pick the <b>project folder</b> &mdash; the one that contains the "
                + "<b>FLASH</b> folder (your project's output folder).<br><br>"
                + "Selecting the <b>FLASH</b> folder or a <b>project.json</b> "
                + "file also works.</div></html>");
        hint.setBorder(BorderFactory.createEmptyBorder(6, 12, 6, 8));
        hint.setVerticalAlignment(SwingConstants.TOP);
        return hint;
    }

    private boolean loadProjectJson(File projectJson, boolean showErrors, String obsoleteRecentPath) {
        if (projectJson == null) {
            return false;
        }
        File settingsDir = projectJson.getParentFile();
        File fallbackOutputRoot = FlashProjectLayout.projectRootForConfigurationDir(settingsDir);
        boolean loaded = loadProjectFromSettingsDir(settingsDir, fallbackOutputRoot, showErrors);
        if (loaded) {
            rememberOpenedProject(projectJson, obsoleteRecentPath);
        }
        return loaded;
    }

    private void rememberOpenedProject(File projectJson, String obsoleteRecentPath) {
        if (pluginsDir == null || projectJson == null) {
            return;
        }
        try {
            RecentProjectsStore.recordOpenedReplacing(pluginsDir,
                    new RecentProject(nameField.getText().trim(), projectJson.getAbsolutePath(),
                            System.currentTimeMillis()),
                    obsoleteRecentPath);
        } catch (IOException ex) {
            IJ.log("[FLASH] Could not update recent projects: " + ex.getMessage());
        }
    }

    private boolean loadExistingProjectIfPresent(File outputRoot, boolean promptIfModelHasRows) {
        File settingsDir = existingProjectSettingsDir(outputRoot);
        if (settingsDir == null) {
            return false;
        }
        if (promptIfModelHasRows && model.getRowCount() > 0) {
            int choice = JOptionPane.showConfirmDialog(dialog,
                    "This folder already contains a FLASH project.\n"
                            + "Open that project instead of adding source files from the folder?",
                    "Open existing project", JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE);
            if (choice != JOptionPane.YES_OPTION) {
                return false;
            }
        }
        return loadProjectFromSettingsDir(settingsDir, outputRoot, promptIfModelHasRows);
    }

    private boolean loadProjectFromSettingsDir(File settingsDir, File fallbackOutputRoot, boolean showErrors) {
        ProjectFile loaded = ProjectFileIO.read(settingsDir);
        if (loaded == null) {
            if (showErrors) {
                File projectJson = settingsDir == null ? null : new File(settingsDir, ProjectFileIO.FILE_NAME);
                JOptionPane.showMessageDialog(dialog,
                        "Could not read project file:\n"
                                + (projectJson == null ? "(unknown)" : projectJson.getAbsolutePath()),
                        "Open failed", JOptionPane.WARNING_MESSAGE);
            }
            return false;
        }
        File projectJson = settingsDir == null ? null : new File(settingsDir, ProjectFileIO.FILE_NAME);
        ProjectPathResolver.relocateForLoad(loaded, projectJson, fallbackOutputRoot);
        loadProject(loaded, fallbackOutputRoot);
        return true;
    }

    private void loadProject(ProjectFile loaded, File fallbackOutputRoot) {
        probeGeneration.incrementAndGet();
        model.clear();
        model.loadFromProjectFile(loaded);
        nameField.setText(loaded.name == null ? "" : loaded.name);
        String outputRoot = loaded.outputRoot == null ? "" : loaded.outputRoot.trim();
        if (outputRoot.isEmpty() && fallbackOutputRoot != null) {
            outputRoot = fallbackOutputRoot.getAbsolutePath();
        }
        outputRootField.setText(outputRoot);
        // Re-probe series counts (counts are not persisted).
        for (int i = 0; i < model.getRowCount(); i++) {
            probeSeriesCountAsync(i, model.get(i).source);
        }
    }

    // ── Save ───────────────────────────────────────────────────────────────

    private void onSave() {
        if (!commitActiveTableEdit()) {
            return;
        }
        String name = nameField.getText().trim();
        String outRoot = outputRootField.getText().trim();
        if (name.isEmpty()) {
            JOptionPane.showMessageDialog(dialog, "Project name is required.", "Validation", JOptionPane.WARNING_MESSAGE);
            return;
        }
        if (outRoot.isEmpty()) {
            JOptionPane.showMessageDialog(dialog, "Output root is required.", "Validation", JOptionPane.WARNING_MESSAGE);
            return;
        }
        if (countIncluded() == 0) {
            JOptionPane.showMessageDialog(dialog,
                    "At least one row must be ticked for inclusion.",
                    "Validation", JOptionPane.WARNING_MESSAGE);
            return;
        }

        File outputRoot = new File(outRoot);
        ProjectFile project = model.toProjectFile(name, outputRoot.getAbsolutePath(), WRITER_ID);
        if (hasMixedIncludedSourceTypes(project)) {
            JOptionPane.showMessageDialog(dialog,
                    "This project mixes multi-series container files and bare TIFF files.\n"
                            + "Please save those source types as separate FLASH projects.",
                    "Validation", JOptionPane.WARNING_MESSAGE);
            return;
        }

        if (!outputRoot.exists() && !outputRoot.mkdirs()) {
            JOptionPane.showMessageDialog(dialog,
                    "Could not create output root:\n" + outputRoot.getAbsolutePath(),
                    "Output root", JOptionPane.WARNING_MESSAGE);
            return;
        }
        if (!outputRoot.isDirectory() || !outputRoot.canWrite()) {
            JOptionPane.showMessageDialog(dialog,
                    "Output root is not a writable directory:\n" + outputRoot.getAbsolutePath(),
                    "Output root", JOptionPane.WARNING_MESSAGE);
            return;
        }

        File settingsDir = FlashProjectLayout.forDirectory(outputRoot.getAbsolutePath()).configurationWriteDir();
        if (!settingsDir.exists() && !settingsDir.mkdirs()) {
            JOptionPane.showMessageDialog(dialog,
                    "Could not create settings directory:\n" + settingsDir.getAbsolutePath(),
                    "Output root", JOptionPane.WARNING_MESSAGE);
            return;
        }

        File projectFile = new File(settingsDir, ProjectFileIO.FILE_NAME);
        try {
            ProjectFileIO.write(settingsDir, project);
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(dialog,
                    "Could not write project file:\n" + ex.getMessage(),
                    "Save failed", JOptionPane.ERROR_MESSAGE);
            return;
        }

        // Mirror per-row conditions to Conditions.csv so the existing
        // aggregation / stats / Excel pipeline (which already reads via
        // ConditionManifestIO) honours the project's explicit assignments
        // without any downstream changes.
        try {
            ConditionManifestIO.saveAssignments(
                    outputRoot.getAbsolutePath(),
                    deriveConditionAssignmentsModel(project));
        } catch (IOException ex) {
            IJ.log("[FLASH] Could not write Conditions.csv: " + ex.getMessage());
        }

        // Seed Image Orientation.csv from per-series identity so downstream
        // analyses honour the user's animal/hemisphere/region assignments
        // instead of re-parsing each series name.
        try {
            ProjectMetadataSeeder.seedOrientationManifest(outputRoot, project);
        } catch (IOException ex) {
            IJ.log("[FLASH] Could not seed Image Orientation.csv: " + ex.getMessage());
        }

        if (pluginsDir != null) {
            try {
                RecentProjectsStore.recordOpened(pluginsDir,
                        new RecentProject(name, projectFile.getAbsolutePath(), System.currentTimeMillis()));
            } catch (IOException ex) {
                IJ.log("[FLASH] Could not update recent projects: " + ex.getMessage());
            }
        }

        result = new Result(project, outputRoot, projectFile);
        dialog.dispose();
    }

    private boolean commitActiveTableEdit() {
        if (!table.isEditing()) {
            return true;
        }
        TableCellEditor editor = table.getCellEditor();
        return editor == null || editor.stopCellEditing();
    }

    /**
     * Derive the animalId → condition map written to Conditions.csv.
     *
     * <p>Only items where {@code include=true} contribute. Items without an
     * explicit condition are omitted so the downstream
     * {@link ConditionManifestIO#resolveAssignments} fallback path
     * ({@code ConditionNameParser.detectCondition}) still applies to them.
     *
     * <p>When two items share an animalId with conflicting conditions,
     * the last-seen wins — the order in the project file is the order the
     * user added the rows.
     */
    static java.util.LinkedHashMap<String, String> deriveConditionAssignments(ProjectFile project) {
        ConditionAssignments assignments = deriveConditionAssignmentsModel(project);
        java.util.LinkedHashMap<String, String> out = new java.util.LinkedHashMap<String, String>();
        for (String animal : assignments.animals()) {
            String condition = assignments.composite(animal, "_");
            if (condition != null && !condition.trim().isEmpty()) {
                out.put(animal, condition.trim());
            }
        }
        return out;
    }

    static ConditionAssignments deriveConditionAssignmentsModel(ProjectFile project) {
        ConditionAssignments out = new ConditionAssignments();
        if (project == null) return out;
        List<ConditionAxis> axes = conditionAxesForProject(project);
        for (ConditionAxis axis : axes) {
            out.addAxis(axis);
        }
        if (project.items == null) return out;
        for (ProjectFile.Item item : project.items) {
            if (item == null || !item.include) continue;
            // An expanded multi-series file contributes one (animal, condition)
            // pair per included series; otherwise the file-level pair applies.
            if (item.seriesMeta != null && !item.seriesMeta.isEmpty()) {
                for (ProjectFile.SeriesItem series : item.seriesMeta) {
                    if (series == null || !series.include) continue;
                    putAssignments(out, axes, series.animalId,
                            series.condition, series.conditions);
                }
            } else {
                putAssignments(out, axes, item.animalId, item.condition, item.conditions);
            }
        }
        return out;
    }

    private static List<ConditionAxis> conditionAxesForProject(ProjectFile project) {
        List<ConditionAxis> axes = new ArrayList<ConditionAxis>();
        if (project != null && project.conditionAxes != null) {
            for (ConditionAxis axis : project.conditionAxes) {
                if (axis == null) continue;
                boolean exists = false;
                for (ConditionAxis existing : axes) {
                    if (existing.id.equals(axis.id)) {
                        exists = true;
                        break;
                    }
                }
                if (!exists) axes.add(axis);
            }
        }
        if (axes.isEmpty()) {
            axes.add(ConditionAxis.of("Condition"));
        }
        return axes;
    }

    private static void putAssignments(ConditionAssignments out, List<ConditionAxis> axes,
                                       String animalId, String primaryCondition,
                                       Map<String, String> conditions) {
        String animal = animalId == null ? "" : animalId.trim();
        if (animal.isEmpty() || axes == null || axes.isEmpty()) return;
        String primaryAxisId = axes.get(0).id;
        for (ConditionAxis axis : axes) {
            String value = conditions == null ? "" : conditions.get(axis.id);
            if ((value == null || value.trim().isEmpty()) && axis.id.equals(primaryAxisId)) {
                value = primaryCondition;
            }
            String condition = value == null ? "" : value.trim();
            if (!condition.isEmpty()) {
                out.put(animal, axis.id, condition);
            }
        }
    }

    private int countIncluded() {
        int n = 0;
        for (int i = 0; i < model.getRowCount(); i++) {
            if (model.get(i).include) n++;
        }
        return n;
    }

    static boolean hasMixedIncludedSourceTypes(ProjectFile project) {
        boolean sawContainer = false;
        boolean sawBareTiff = false;
        if (project == null || project.items == null) return false;
        for (ProjectFile.Item item : project.items) {
            if (item == null || !item.include || item.path == null) continue;
            String name = new File(item.path).getName().toLowerCase(Locale.ROOT);
            if (isContainerExtension(name)) {
                sawContainer = true;
            } else if (isBareTiffExtension(name)) {
                sawBareTiff = true;
            }
            if (sawContainer && sawBareTiff) return true;
        }
        return false;
    }

    static File existingProjectSettingsDir(File outputRoot) {
        if (outputRoot == null || !outputRoot.isDirectory()) return null;
        File settingsDir = FlashProjectLayout.forDirectory(outputRoot.getAbsolutePath())
                .configurationWriteDir();
        return ProjectFileIO.exists(settingsDir) ? settingsDir : null;
    }

    static List<File> collectAcceptedSourcesForTests(File folder) {
        List<File> out = new ArrayList<File>();
        collectAcceptedSources(folder, out);
        return out;
    }

    private void seedOutputRootIfBlank(File source) {
        if (source == null) return;
        if (outputRootField.getText().trim().isEmpty()) {
            outputRootField.setText(source.getAbsolutePath());
            if (nameField.getText().trim().isEmpty()) {
                nameField.setText(source.getName());
            }
        }
    }

    // ── File type filtering ────────────────────────────────────────────────

    private static boolean isAcceptedSource(File f) {
        if (f == null) return false;
        String name = f.getName().toLowerCase(Locale.ROOT);
        if (isContainerExtension(name)) return true;
        return name.endsWith(".tif") || name.endsWith(".tiff");
    }

    private static boolean isContainerExtension(String loweredName) {
        for (String ext : ImageSourceDispatcher.CONTAINER_EXTENSIONS) {
            if (loweredName.endsWith(ext)) return true;
        }
        return false;
    }

    private static boolean isBareTiffExtension(String loweredName) {
        return !isContainerExtension(loweredName)
                && (loweredName.endsWith(".tif") || loweredName.endsWith(".tiff"));
    }

    private static void collectAcceptedSources(File folder, List<File> out) {
        File[] children = folder.listFiles();
        if (children == null) return;
        for (File child : children) {
            if (child.isDirectory()) {
                if (FlashProjectLayout.FLASH_DIR.equalsIgnoreCase(child.getName())) {
                    continue;
                }
                // Recurse one level — typical lab folders nest condition subfolders
                // under a parent. We do NOT walk the whole tree to avoid swallowing
                // unrelated trees the user did not mean to include.
                File[] grand = child.listFiles();
                if (grand == null) continue;
                for (File g : grand) {
                    if (g.isFile() && isAcceptedSource(g)) out.add(g);
                }
            } else if (child.isFile() && isAcceptedSource(child)) {
                out.add(child);
            }
        }
    }
}
