package flash.pipeline.project;

import flash.pipeline.io.FlashProjectLayout;
import flash.pipeline.io.ImageSourceDispatcher;
import flash.pipeline.io.LifIO;
import flash.pipeline.io.SeriesMeta;
import flash.pipeline.ui.FlashTheme;
import ij.IJ;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.TransferHandler;
import javax.swing.WindowConstants;
import javax.swing.filechooser.FileFilter;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
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
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
    private final File pluginsDir;
    private final ExecutorService probeExecutor =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "FLASH-series-probe");
                t.setDaemon(true);
                return t;
            });

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

        table = new JTable(model);
        table.setAutoCreateRowSorter(false);
        table.setFillsViewportHeight(true);
        table.setRowHeight(22);
        table.getTableHeader().setReorderingAllowed(false);
        applyColumnPreferences();
        attachContextMenu();
        attachDragAndDrop();
        attachSeriesDoubleClick();

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
        JButton remove = new JButton("Remove selected");

        addFolder.addActionListener(new ActionListener() {
            @Override public void actionPerformed(ActionEvent e) {
                JFileChooser chooser = new JFileChooser();
                chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
                if (chooser.showOpenDialog(dialog) == JFileChooser.APPROVE_OPTION) {
                    addFolderRecursively(chooser.getSelectedFile());
                    seedOutputRootIfBlank(chooser.getSelectedFile());
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

        remove.addActionListener(new ActionListener() {
            @Override public void actionPerformed(ActionEvent e) {
                removeSelectedRows();
            }
        });

        bar.add(addFolder);
        bar.add(addFiles);
        bar.add(openRecent);
        bar.add(Box.createHorizontalStrut(12));
        bar.add(remove);
        return bar;
    }

    private JLabel buildHintRow() {
        JLabel hint = new JLabel("Tip: drag files or folders here. Right-click a row for series and condition options.");
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
        int[] widths = {60, 280, 110, 110, 100, 100, 110, 160};
        for (int c = 0; c < widths.length && c < table.getColumnCount(); c++) {
            table.getColumnModel().getColumn(c).setPreferredWidth(widths[c]);
        }
    }

    // ── Context menu / DnD / series shortcut ───────────────────────────────

    private void attachContextMenu() {
        final JPopupMenu menu = new JPopupMenu();
        JMenuItem setCondition = new JMenuItem("Set condition…");
        JMenuItem configureSeries = new JMenuItem("Configure series…");
        JMenuItem removeRow = new JMenuItem("Remove");

        setCondition.addActionListener(new ActionListener() {
            @Override public void actionPerformed(ActionEvent e) {
                bulkSetCondition();
            }
        });
        configureSeries.addActionListener(new ActionListener() {
            @Override public void actionPerformed(ActionEvent e) {
                int[] sel = table.getSelectedRows();
                if (sel.length == 0) return;
                openSeriesDialog(sel[0]);
            }
        });
        removeRow.addActionListener(new ActionListener() {
            @Override public void actionPerformed(ActionEvent e) {
                removeSelectedRows();
            }
        });

        menu.add(setCondition);
        menu.add(configureSeries);
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
                menu.show(e.getComponent(), e.getX(), e.getY());
            }
        });
    }

    private void attachSeriesDoubleClick() {
        table.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() != 2) return;
                int row = table.rowAtPoint(e.getPoint());
                int col = table.columnAtPoint(e.getPoint());
                if (row >= 0 && col == ProjectManifestTableModel.COL_SERIES) {
                    openSeriesDialog(row);
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
        final String lowered = source.getName().toLowerCase(Locale.ROOT);
        final boolean isContainer = isContainerExtension(lowered);
        probeExecutor.submit(new SwingWorker<Integer, Void>() {
            @Override protected Integer doInBackground() throws Exception {
                if (!isContainer) return Integer.valueOf(1);
                return Integer.valueOf(LifIO.getSeriesCount(source));
            }
            @Override protected void done() {
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
    }

    private void openSeriesDialog(int rowIndex) {
        ProjectManifestTableModel.Row row = model.get(rowIndex);
        File source = row.source;
        if (source == null) return;
        List<SeriesSelectionDialog.SeriesEntry> entries = new ArrayList<SeriesSelectionDialog.SeriesEntry>();
        try {
            List<SeriesMeta> metas = LifIO.readAllSeriesMetadata(source);
            for (SeriesMeta m : metas) {
                entries.add(new SeriesSelectionDialog.SeriesEntry(m.index, m.name));
            }
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(dialog,
                    "Could not read series from this file:\n" + ex.getMessage(),
                    "Series unavailable", JOptionPane.WARNING_MESSAGE);
            return;
        }
        if (entries.isEmpty()) {
            JOptionPane.showMessageDialog(dialog,
                    "This file reports no series.",
                    "No series", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        List<Integer> chosen = SeriesSelectionDialog.show(dialog, source.getName(), entries, row.selectedSeries);
        if (chosen != null) {
            model.setSelectedSeries(rowIndex, chosen);
            model.setSeriesCount(rowIndex, entries.size());
        }
    }

    private void bulkSetCondition() {
        int[] sel = table.getSelectedRows();
        if (sel.length == 0) return;
        String preset = sel.length == 1 ? model.get(sel[0]).condition : "";
        String value = (String) JOptionPane.showInputDialog(dialog,
                "Set condition for " + sel.length + " selected row(s):",
                "Set condition",
                JOptionPane.PLAIN_MESSAGE,
                null, null, preset);
        if (value == null) return;
        model.setConditionForRows(sel, value);
    }

    private void removeSelectedRows() {
        int[] sel = table.getSelectedRows();
        for (int i = sel.length - 1; i >= 0; i--) {
            model.removeRow(sel[i]);
        }
    }

    private void openRecentPicker() {
        if (pluginsDir == null) {
            JOptionPane.showMessageDialog(dialog,
                    "Recent projects list is unavailable (plugins directory not found).",
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
        String[] labels = new String[recents.size()];
        for (int i = 0; i < recents.size(); i++) {
            RecentProject r = recents.get(i);
            labels[i] = (r.name.isEmpty() ? "(unnamed)" : r.name) + " — " + r.path;
        }
        String pick = (String) JOptionPane.showInputDialog(dialog,
                "Choose a recent project:",
                "Open recent",
                JOptionPane.PLAIN_MESSAGE,
                null, labels, labels[0]);
        if (pick == null) return;
        for (int i = 0; i < labels.length; i++) {
            if (labels[i].equals(pick)) {
                loadRecent(recents.get(i));
                return;
            }
        }
    }

    private void loadRecent(RecentProject recent) {
        File projectJson = new File(recent.path);
        File settingsDir = projectJson.getParentFile();
        ProjectFile loaded = ProjectFileIO.read(settingsDir);
        if (loaded == null) {
            JOptionPane.showMessageDialog(dialog,
                    "Could not read project file:\n" + projectJson.getAbsolutePath(),
                    "Open failed", JOptionPane.WARNING_MESSAGE);
            return;
        }
        model.clear();
        model.loadFromProjectFile(loaded);
        nameField.setText(loaded.name == null ? "" : loaded.name);
        outputRootField.setText(loaded.outputRoot == null ? "" : loaded.outputRoot);
        // Re-probe series counts (counts are not persisted).
        for (int i = 0; i < model.getRowCount(); i++) {
            probeSeriesCountAsync(i, model.get(i).source);
        }
    }

    // ── Save ───────────────────────────────────────────────────────────────

    private void onSave() {
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

        ProjectFile project = model.toProjectFile(name, outputRoot.getAbsolutePath(), WRITER_ID);
        File projectFile = new File(settingsDir, ProjectFileIO.FILE_NAME);
        try {
            ProjectFileIO.write(settingsDir, project);
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(dialog,
                    "Could not write project file:\n" + ex.getMessage(),
                    "Save failed", JOptionPane.ERROR_MESSAGE);
            return;
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

    private int countIncluded() {
        int n = 0;
        for (int i = 0; i < model.getRowCount(); i++) {
            if (model.get(i).include) n++;
        }
        return n;
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

    private static void collectAcceptedSources(File folder, List<File> out) {
        File[] children = folder.listFiles();
        if (children == null) return;
        for (File child : children) {
            if (child.isDirectory()) {
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
