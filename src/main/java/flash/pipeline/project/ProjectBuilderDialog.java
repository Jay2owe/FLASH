package flash.pipeline.project;

import flash.pipeline.io.ConditionManifestIO;
import flash.pipeline.io.FlashProjectLayout;
import flash.pipeline.io.ImageSourceDispatcher;
import flash.pipeline.io.LifIO;
import flash.pipeline.io.SeriesMeta;
import flash.pipeline.ui.FlashTheme;
import flash.pipeline.ui.wizard.RegionTableCellEditor;
import ij.IJ;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.JButton;
import javax.swing.JComponent;
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
import javax.swing.filechooser.FileFilter;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableCellEditor;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
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

        table = new JTable(model);
        table.setAutoCreateRowSorter(false);
        table.setFillsViewportHeight(true);
        table.setRowHeight(22);
        table.getTableHeader().setReorderingAllowed(false);
        applyColumnPreferences();
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

        bar.add(addFolder);
        bar.add(addFiles);
        bar.add(openProject);
        bar.add(openRecent);
        bar.add(Box.createHorizontalStrut(12));
        bar.add(remove);
        return bar;
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
        int[] widths = {60, 280, 110, 110, 100, 100, 110, 160};
        for (int c = 0; c < widths.length && c < table.getColumnCount(); c++) {
            table.getColumnModel().getColumn(c).setPreferredWidth(widths[c]);
        }
        table.getColumnModel().getColumn(ProjectManifestTableModel.COL_REGION)
                .setCellEditor(new RegionTableCellEditor());
        table.getColumnModel().getColumn(ProjectManifestTableModel.COL_FILE)
                .setCellRenderer(new FileColumnRenderer());
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

    // ── Context menu / DnD / series shortcut ───────────────────────────────

    private void attachContextMenu() {
        final JPopupMenu menu = new JPopupMenu();
        JMenuItem setCondition = new JMenuItem("Set condition…");
        final JMenuItem toggleSeries = new JMenuItem("Expand series");
        JMenuItem removeRow = new JMenuItem("Remove");

        setCondition.addActionListener(new ActionListener() {
            @Override public void actionPerformed(ActionEvent e) {
                bulkSetCondition();
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

        menu.add(setCondition);
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
                boolean expandable = row >= 0 && model.isExpandableFileRow(row);
                toggleSeries.setEnabled(expandable);
                toggleSeries.setText(expandable && model.isExpanded(row)
                        ? "Collapse series" : "Expand series");
                menu.show(e.getComponent(), e.getX(), e.getY());
            }
        });
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
                    deriveConditionAssignments(project));
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
        java.util.LinkedHashMap<String, String> out = new java.util.LinkedHashMap<String, String>();
        if (project == null || project.items == null) return out;
        for (ProjectFile.Item item : project.items) {
            if (item == null || !item.include) continue;
            // An expanded multi-series file contributes one (animal, condition)
            // pair per included series; otherwise the file-level pair applies.
            if (item.seriesMeta != null && !item.seriesMeta.isEmpty()) {
                for (ProjectFile.SeriesItem series : item.seriesMeta) {
                    if (series == null || !series.include) continue;
                    putAssignment(out, series.animalId, series.condition);
                }
            } else {
                putAssignment(out, item.animalId, item.condition);
            }
        }
        return out;
    }

    private static void putAssignment(java.util.LinkedHashMap<String, String> out,
                                      String animalId, String conditionValue) {
        String animal = animalId == null ? "" : animalId.trim();
        String condition = conditionValue == null ? "" : conditionValue.trim();
        if (animal.isEmpty() || condition.isEmpty()) return;
        out.put(animal, condition);
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
