package flash.pipeline.project;

import flash.pipeline.intelligence.AnalysisStatus;
import flash.pipeline.intelligence.AnalysisStatusScanner;
import flash.pipeline.io.FlashProjectLayout;
import flash.pipeline.ui.FlashTheme;
import ij.IJ;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.KeyStroke;
import javax.swing.SwingWorker;
import javax.swing.TransferHandler;
import javax.swing.WindowConstants;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GraphicsEnvironment;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Window;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Modal FLASH home screen. It returns the user's intent and leaves routing to
 * the pipeline entry point.
 */
public final class ProjectHomeDialog {
    private static final int STATUS_THREADS = Math.max(2,
            Math.min(4, Runtime.getRuntime().availableProcessors() / 2));
    private static final ExecutorService STATUS_EXECUTOR =
            Executors.newFixedThreadPool(STATUS_THREADS, new ThreadFactory() {
                @Override public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "FLASH-home-status");
                    t.setDaemon(true);
                    return t;
                }
            });

    public static final class Choice {
        public enum Action { OPEN_EXISTING, NEW_PROJECT, EDIT_EXISTING, BROWSE_FOLDER, CANCEL }

        public final Action action;
        public final File projectJson;
        public final File folder;

        public Choice(Action action, File projectJson, File folder) {
            this.action = action == null ? Action.CANCEL : action;
            this.projectJson = projectJson;
            this.folder = folder;
        }

        public static Choice openExisting(File projectJson) {
            return new Choice(Action.OPEN_EXISTING, projectJson, null);
        }

        public static Choice editExisting(File projectJson) {
            return new Choice(Action.EDIT_EXISTING, projectJson, null);
        }

        public static Choice browseFolder(File folder) {
            return new Choice(Action.BROWSE_FOLDER, null, folder);
        }

        public static Choice newProject() {
            return new Choice(Action.NEW_PROJECT, null, null);
        }

        public static Choice cancel() {
            return new Choice(Action.CANCEL, null, null);
        }
    }

    private final JDialog dialog;
    private final File pluginsDir;
    private final long nowMillis;
    private final JPanel recentStack;
    private final AtomicInteger probeGeneration = new AtomicInteger();
    private volatile boolean closed;
    private Choice result = Choice.cancel();

    private ProjectHomeDialog(Window owner, File pluginsDir, long nowMillis) {
        this.pluginsDir = pluginsDir;
        this.nowMillis = nowMillis;
        this.dialog = new JDialog(owner, "FLASH Home", JDialog.ModalityType.APPLICATION_MODAL);
        this.dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);

        JPanel content = new JPanel(new BorderLayout(12, 8));
        content.setBorder(FlashTheme.pad(14, 16));
        content.setBackground(FlashTheme.SURFACE);

        recentStack = new JPanel();
        recentStack.setLayout(new BoxLayout(recentStack, BoxLayout.Y_AXIS));
        recentStack.setBackground(FlashTheme.SURFACE_RAISED);

        content.add(buildMainColumns(), BorderLayout.CENTER);
        content.add(buildFooter(), BorderLayout.SOUTH);
        attachDragAndDrop(content);
        bindEscape();

        dialog.setContentPane(content);
        dialog.pack();
        dialog.setMinimumSize(new Dimension(820, 500));
        dialog.setLocationRelativeTo(owner);
        showRecentMessage("Loading recent projects...");
        dialog.addWindowListener(new WindowAdapter() {
            @Override public void windowOpened(WindowEvent e) {
                loadRecentsAsync();
            }

            @Override public void windowClosed(WindowEvent e) {
                closed = true;
                probeGeneration.incrementAndGet();
            }
        });
    }

    public static Choice open(Window owner, File pluginsDir) {
        if (GraphicsEnvironment.isHeadless()) {
            throw new IllegalStateException("ProjectHomeDialog cannot run headless.");
        }
        ProjectHomeDialog dlg = new ProjectHomeDialog(owner, pluginsDir, System.currentTimeMillis());
        try {
            dlg.dialog.setVisible(true);
            return dlg.result;
        } finally {
            dlg.closed = true;
            dlg.probeGeneration.incrementAndGet();
        }
    }

    private JPanel buildMainColumns() {
        JPanel columns = new JPanel(new GridBagLayout());
        columns.setBackground(FlashTheme.SURFACE);

        GridBagConstraints left = new GridBagConstraints();
        left.gridx = 0;
        left.gridy = 0;
        left.weightx = 0.62;
        left.weighty = 1.0;
        left.fill = GridBagConstraints.BOTH;
        left.insets = new Insets(0, 0, 0, 8);
        columns.add(buildRecentPanel(), left);

        GridBagConstraints right = new GridBagConstraints();
        right.gridx = 1;
        right.gridy = 0;
        right.weightx = 0.38;
        right.weighty = 1.0;
        right.fill = GridBagConstraints.BOTH;
        right.insets = new Insets(0, 8, 0, 0);
        columns.add(buildStartPanel(), right);

        return columns;
    }

    private JPanel buildRecentPanel() {
        JPanel panel = new JPanel(new BorderLayout(0, 8));
        panel.setBackground(FlashTheme.SURFACE);
        panel.add(sectionHeader("RECENT PROJECTS"), BorderLayout.NORTH);

        JScrollPane scroll = new JScrollPane(recentStack);
        scroll.setPreferredSize(new Dimension(500, 360));
        scroll.setBorder(BorderFactory.createLineBorder(FlashTheme.BORDER));
        attachDragAndDrop(recentStack);
        attachDragAndDrop(scroll);
        attachDragAndDrop(panel);
        panel.add(scroll, BorderLayout.CENTER);

        JLabel hint = new JLabel("double-click to open - right-click to edit, locate, or remove");
        hint.setFont(FlashTheme.caption());
        hint.setForeground(FlashTheme.TEXT_MUTED);
        panel.add(hint, BorderLayout.SOUTH);
        return panel;
    }

    private JPanel buildStartPanel() {
        JPanel panel = new JPanel(new BorderLayout(0, 8));
        panel.setBackground(FlashTheme.SURFACE);
        panel.add(sectionHeader("START SOMETHING"), BorderLayout.NORTH);

        JPanel body = new JPanel();
        body.setBackground(FlashTheme.SURFACE);
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
        attachDragAndDrop(body);

        JPanel dropZone = new JPanel();
        dropZone.setLayout(new BoxLayout(dropZone, BoxLayout.Y_AXIS));
        dropZone.setBackground(FlashTheme.SURFACE_RAISED);
        dropZone.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(FlashTheme.BORDER_STRONG),
                FlashTheme.pad(28, 18)));
        attachDragAndDrop(dropZone);

        JLabel dropText = new JLabel("Drop a project folder here, or...");
        dropText.setAlignmentX(JComponent.CENTER_ALIGNMENT);
        dropText.setForeground(FlashTheme.TEXT_SUBHEADER);
        JButton browse = new JButton("Browse for folder");
        browse.setAlignmentX(JComponent.CENTER_ALIGNMENT);
        browse.addActionListener(new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) {
                browseForFolder();
            }
        });

        dropZone.add(dropText);
        dropZone.add(Box.createVerticalStrut(12));
        dropZone.add(browse);

        JButton newProject = new JButton("+ New project");
        newProject.setAlignmentX(JComponent.LEFT_ALIGNMENT);
        newProject.addActionListener(new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) {
                choose(Choice.newProject());
            }
        });

        body.add(dropZone);
        body.add(Box.createVerticalStrut(12));
        body.add(newProject);
        body.add(Box.createVerticalGlue());

        panel.add(body, BorderLayout.CENTER);
        attachDragAndDrop(panel);
        return panel;
    }

    private JPanel buildFooter() {
        JPanel footer = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        footer.setBackground(FlashTheme.SURFACE);
        JButton settings = new JButton("Settings");
        settings.addActionListener(new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) {
                JOptionPane.showMessageDialog(dialog,
                        "Settings are configured inside a project.",
                        "FLASH Settings", JOptionPane.INFORMATION_MESSAGE);
            }
        });
        JButton help = new JButton("Help");
        help.addActionListener(new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) {
                JOptionPane.showMessageDialog(dialog,
                        "Open a project to view analysis help.",
                        "FLASH Help", JOptionPane.INFORMATION_MESSAGE);
            }
        });
        footer.add(settings);
        footer.add(help);
        return footer;
    }

    private JLabel sectionHeader(String text) {
        JLabel label = new JLabel(text);
        label.setFont(FlashTheme.h2());
        label.setForeground(FlashTheme.TEXT_HEADER);
        return label;
    }

    private void bindEscape() {
        dialog.getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "cancel");
        dialog.getRootPane().getActionMap().put("cancel", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) {
                choose(Choice.cancel());
            }
        });
    }

    private void loadRecentsAsync() {
        showRecentMessage("Loading recent projects...");
        final int generation = probeGeneration.get();
        SwingWorker<List<RecentProject>, Void> worker =
                new SwingWorker<List<RecentProject>, Void>() {
                    @Override protected List<RecentProject> doInBackground() {
                        if (pluginsDir == null) {
                            return Collections.emptyList();
                        }
                        return RecentProjectsStore.read(pluginsDir);
                    }

                    @Override protected void done() {
                        if (closed || generation != probeGeneration.get()
                                || !dialog.isDisplayable()) {
                            return;
                        }
                        try {
                            renderRecents(get());
                        } catch (Exception e) {
                            IJ.log("[FLASH] Could not load recent projects: " + e.getMessage());
                            renderRecents(Collections.<RecentProject>emptyList());
                        }
                    }
                };
        submit(worker, "recent projects");
    }

    private void renderRecents(List<RecentProject> recents) {
        recentStack.removeAll();
        if (recents == null || recents.isEmpty()) {
            addRecentMessage("No recent projects yet - start one on the right");
        } else {
            RecentProjectCard.Actions actions = new RecentProjectCard.Actions() {
                @Override public void open(RecentProjectCard card) {
                    chooseRecentAsync(card, Choice.Action.OPEN_EXISTING);
                }

                @Override public void edit(RecentProjectCard card) {
                    chooseRecentAsync(card, Choice.Action.EDIT_EXISTING);
                }

                @Override public void locate(RecentProjectCard card) {
                    locateRecent(card);
                }

                @Override public void remove(RecentProjectCard card) {
                    removeRecentAsync(card);
                }
            };
            for (int i = 0; i < recents.size(); i++) {
                RecentProjectCard card = new RecentProjectCard(recents.get(i), i == 0,
                        nowMillis, actions);
                card.setAlignmentX(JComponent.LEFT_ALIGNMENT);
                attachDragAndDrop(card);
                recentStack.add(card);
                if (i + 1 < recents.size()) {
                    recentStack.add(Box.createVerticalStrut(8));
                }
                startStatusProbe(card);
            }
        }
        recentStack.revalidate();
        recentStack.repaint();
    }

    private void showRecentMessage(String message) {
        recentStack.removeAll();
        addRecentMessage(message);
        recentStack.revalidate();
        recentStack.repaint();
    }

    private void addRecentMessage(String message) {
        JLabel empty = new JLabel(message);
        empty.setBorder(FlashTheme.pad(18));
        empty.setForeground(FlashTheme.TEXT_MUTED);
        empty.setFont(FlashTheme.body());
        recentStack.add(empty);
    }

    private void startStatusProbe(final RecentProjectCard card) {
        if (card == null || closed) {
            return;
        }
        card.setChecking();
        final int generation = probeGeneration.get();
        SwingWorker<RecentProjectCard.StatusResult, Void> worker =
                new SwingWorker<RecentProjectCard.StatusResult, Void>() {
                    @Override protected RecentProjectCard.StatusResult doInBackground() {
                        return resolveStatus(card.recent());
                    }

                    @Override protected void done() {
                        if (closed || generation != probeGeneration.get()
                                || !dialog.isDisplayable()) {
                            return;
                        }
                        try {
                            card.applyStatusResult(get());
                        } catch (Exception e) {
                            IJ.log("[FLASH] Could not scan project status: " + e.getMessage());
                            card.applyStatusResult(null);
                        }
                    }
                };
        submit(worker, "project status");
    }

    private RecentProjectCard.StatusResult resolveStatus(RecentProject recent) {
        ProjectService.ResolveOutcome outcome = ProjectService.resolveRecent(
                recent == null ? null : recent.path);
        if (outcome.projectJson == null) {
            return RecentProjectCard.StatusResult.unresolved(
                    unavailableTextForPath(recent == null ? null : recent.path));
        }
        File outputRoot = FlashProjectLayout.projectRootForConfigurationDir(
                outcome.projectJson.getParentFile());
        if (outputRoot == null) {
            return RecentProjectCard.StatusResult.resolved(outcome,
                    "project found - status unavailable");
        }
        Map<Integer, AnalysisStatus> statuses = new AnalysisStatusScanner().scan(outputRoot);
        return RecentProjectCard.StatusResult.resolved(outcome,
                RecentProjectCard.progressSummary(statuses));
    }

    private void chooseRecentAsync(final RecentProjectCard card, final Choice.Action action) {
        if (card == null) {
            return;
        }
        File alreadyResolved = card.resolvedProjectJson();
        if (alreadyResolved != null) {
            chooseResolvedRecent(card, action, card.resolveOutcome());
            return;
        }
        if (card.isUnresolved()) {
            card.applyStatusResult(RecentProjectCard.StatusResult.unresolved(
                    unavailableTextForPath(card.recent().path)));
            return;
        }

        card.setChecking();
        final int generation = probeGeneration.get();
        SwingWorker<ProjectService.ResolveOutcome, Void> worker =
                new SwingWorker<ProjectService.ResolveOutcome, Void>() {
            @Override protected ProjectService.ResolveOutcome doInBackground() {
                return ProjectService.resolveRecent(card.recent().path);
            }

            @Override protected void done() {
                if (closed || generation != probeGeneration.get()
                        || !dialog.isDisplayable()) {
                    return;
                }
                try {
                    ProjectService.ResolveOutcome outcome = get();
                    if (outcome == null || outcome.projectJson == null) {
                        card.applyStatusResult(RecentProjectCard.StatusResult.unresolved(
                                unavailableTextForPath(card.recent().path)));
                        return;
                    }
                    chooseResolvedRecent(card, action, outcome);
                } catch (Exception e) {
                    IJ.log("[FLASH] Could not resolve recent project: " + e.getMessage());
                    card.applyStatusResult(null);
                }
            }
        };
        submit(worker, "recent project resolve");
    }

    private void chooseResolvedRecent(final RecentProjectCard card, Choice.Action action,
                                      ProjectService.ResolveOutcome outcome) {
        File projectJson = outcome == null ? card.resolvedProjectJson() : outcome.projectJson;
        if (projectJson == null) {
            card.applyStatusResult(RecentProjectCard.StatusResult.unresolved(
                    unavailableTextForPath(card.recent().path)));
            return;
        }
        if (action == Choice.Action.EDIT_EXISTING) {
            choose(Choice.editExisting(projectJson));
            return;
        }
        if (outcome != null && outcome.relocated) {
            if (!confirmRelocatedOpen(projectJson)) {
                return;
            }
            recordReplacementAndOpen(card, projectJson, outcome.storedPath);
            return;
        }
        choose(Choice.openExisting(projectJson));
    }

    private boolean confirmRelocatedOpen(File projectJson) {
        int result = JOptionPane.showOptionDialog(dialog,
                relocationMessage(projectJson),
                "FLASH Project Reconnected",
                JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.INFORMATION_MESSAGE,
                null,
                new String[]{"Open", "Cancel"},
                "Open");
        return result == JOptionPane.OK_OPTION;
    }

    private void recordReplacementAndOpen(final RecentProjectCard card, final File projectJson,
                                          final String obsoletePath) {
        if (card == null || projectJson == null) {
            return;
        }
        card.setReconnecting();
        final int generation = probeGeneration.get();
        SwingWorker<File, Void> worker = new SwingWorker<File, Void>() {
            @Override protected File doInBackground() throws IOException {
                if (pluginsDir != null) {
                    RecentProjectsStore.recordOpenedReplacing(pluginsDir,
                            replacementEntryForResolvedPath(card.recent(), projectJson,
                                    System.currentTimeMillis()),
                            obsoletePath);
                }
                return projectJson;
            }

            @Override protected void done() {
                if (closed || generation != probeGeneration.get()
                        || !dialog.isDisplayable()) {
                    return;
                }
                try {
                    choose(Choice.openExisting(get()));
                } catch (Exception e) {
                    IJ.log("[FLASH] Could not update recent project path: " + e.getMessage());
                    choose(Choice.openExisting(projectJson));
                }
            }
        };
        submit(worker, "recent project relocation");
    }

    private void locateRecent(final RecentProjectCard card) {
        if (card == null) {
            return;
        }
        JFileChooser chooser = new JFileChooser();
        chooser.setFileSelectionMode(JFileChooser.FILES_AND_DIRECTORIES);
        chooser.setDialogTitle("Locate FLASH project");
        File start = startingDirectoryForStoredPath(card.recent().path);
        if (start != null) {
            chooser.setCurrentDirectory(start);
        }
        if (chooser.showOpenDialog(dialog) == JFileChooser.APPROVE_OPTION) {
            resolveLocatedRecentAsync(card, chooser.getSelectedFile());
        }
    }

    private void resolveLocatedRecentAsync(final RecentProjectCard card, final File selected) {
        if (card == null || selected == null) {
            return;
        }
        card.setChecking();
        final int generation = probeGeneration.get();
        SwingWorker<File, Void> worker = new SwingWorker<File, Void>() {
            @Override protected File doInBackground() {
                return ProjectService.resolveProjectJson(selected);
            }

            @Override protected void done() {
                if (closed || generation != probeGeneration.get()
                        || !dialog.isDisplayable()) {
                    return;
                }
                try {
                    File projectJson = get();
                    if (projectJson == null) {
                        card.applyStatusResult(RecentProjectCard.StatusResult.unresolved(
                                unavailableTextForPath(card.recent().path)));
                        JOptionPane.showMessageDialog(dialog,
                                "That folder is not a FLASH project.",
                                "Locate FLASH Project",
                                JOptionPane.INFORMATION_MESSAGE);
                        return;
                    }
                    recordReplacementAndOpen(card, projectJson, card.recent().path);
                } catch (Exception e) {
                    IJ.log("[FLASH] Could not locate recent project: " + e.getMessage());
                    card.applyStatusResult(null);
                }
            }
        };
        submit(worker, "recent project locate");
    }

    private void removeRecentAsync(final RecentProjectCard card) {
        if (card == null) {
            return;
        }
        final int generation = probeGeneration.incrementAndGet();
        showRecentMessage("Updating recent projects...");
        SwingWorker<List<RecentProject>, Void> worker = new SwingWorker<List<RecentProject>, Void>() {
            @Override protected List<RecentProject> doInBackground() throws IOException {
                List<RecentProject> current = pluginsDir == null
                        ? Collections.<RecentProject>emptyList()
                        : RecentProjectsStore.read(pluginsDir);
                List<RecentProject> next = withoutRecent(current, card.recent());
                if (pluginsDir != null) {
                    RecentProjectsStore.write(pluginsDir, next);
                }
                return next;
            }

            @Override protected void done() {
                if (closed || generation != probeGeneration.get()
                        || !dialog.isDisplayable()) {
                    return;
                }
                try {
                    renderRecents(get());
                } catch (Exception e) {
                    IJ.log("[FLASH] Could not remove recent project: " + e.getMessage());
                    loadRecentsAsync();
                }
            }
        };
        submit(worker, "recent project remove");
    }

    private void browseForFolder() {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        chooser.setDialogTitle("Choose a project folder");
        if (chooser.showOpenDialog(dialog) == JFileChooser.APPROVE_OPTION) {
            choose(Choice.browseFolder(chooser.getSelectedFile()));
        }
    }

    static String relocationMessage(File projectJson) {
        String path = projectJson == null ? "(unknown path)" : projectJson.getAbsolutePath();
        return "Reconnected - your project moved with Dropbox and was found at:\n"
                + path + "\nOpen it?";
    }

    static RecentProject replacementEntryForResolvedPath(RecentProject recent, File projectJson,
                                                         long openedAt) {
        String name = recent == null ? "" : recent.name;
        String path = projectJson == null ? "" : projectJson.getAbsolutePath();
        return new RecentProject(name, path, openedAt);
    }

    static File startingDirectoryForStoredPath(String storedPath) {
        if (storedPath == null || storedPath.trim().isEmpty()) {
            return null;
        }
        File stored = new File(storedPath);
        File parent = stored.getParentFile();
        if (parent != null && parent.isDirectory()) {
            return parent.getAbsoluteFile();
        }
        File near = ProjectPathResolver.nearestExistingParent(parent == null ? stored : parent);
        return near == null ? null : near.getAbsoluteFile();
    }

    static String unavailableTextForPath(String storedPath) {
        return storedRootAvailable(storedPath)
                ? "Unavailable - folder missing"
                : "Unavailable - still syncing or offline?";
    }

    static boolean storedRootAvailable(String storedPath) {
        if (storedPath == null || storedPath.trim().isEmpty()) {
            return true;
        }
        File stored = new File(storedPath).getAbsoluteFile();
        if (stored.exists()) {
            return true;
        }
        java.nio.file.Path rootPath = stored.toPath().getRoot();
        if (rootPath == null) {
            return true;
        }
        File rootFile = rootPath.toFile();
        if (rootFile.exists()) {
            return true;
        }
        File[] roots = File.listRoots();
        if (roots == null) {
            return false;
        }
        String wanted = normaliseStoredPath(rootFile.getAbsolutePath());
        for (int i = 0; i < roots.length; i++) {
            if (normaliseStoredPath(roots[i].getAbsolutePath()).equals(wanted)) {
                return true;
            }
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private void attachDragAndDrop(JComponent component) {
        component.setTransferHandler(new TransferHandler() {
            @Override public boolean canImport(TransferSupport support) {
                return support.isDataFlavorSupported(DataFlavor.javaFileListFlavor);
            }

            @Override public boolean importData(TransferSupport support) {
                if (!canImport(support)) {
                    return false;
                }
                try {
                    Transferable transferable = support.getTransferable();
                    List<File> files = (List<File>) transferable.getTransferData(
                            DataFlavor.javaFileListFlavor);
                    for (int i = 0; files != null && i < files.size(); i++) {
                        File f = files.get(i);
                        if (f != null && f.isDirectory()) {
                            choose(Choice.browseFolder(f));
                            return true;
                        }
                    }
                } catch (Exception e) {
                    IJ.log("[FLASH] Home drag-drop failed: " + e.getMessage());
                }
                return false;
            }
        });
    }

    private void choose(Choice choice) {
        result = choice == null ? Choice.cancel() : choice;
        closed = true;
        probeGeneration.incrementAndGet();
        dialog.dispose();
    }

    private void submit(SwingWorker<?, ?> worker, String label) {
        try {
            STATUS_EXECUTOR.submit(worker);
        } catch (RejectedExecutionException e) {
            if (!closed) {
                IJ.log("[FLASH] Home " + label + " task was rejected: " + e.getMessage());
            }
        }
    }

    static List<RecentProject> withoutRecent(List<RecentProject> entries, RecentProject remove) {
        if (entries == null || entries.isEmpty()) {
            return new ArrayList<RecentProject>();
        }
        List<RecentProject> out = new ArrayList<RecentProject>();
        String removePath = normaliseStoredPath(remove == null ? null : remove.path);
        for (int i = 0; i < entries.size(); i++) {
            RecentProject entry = entries.get(i);
            if (!normaliseStoredPath(entry == null ? null : entry.path).equals(removePath)) {
                out.add(entry);
            }
        }
        return out;
    }

    private static String normaliseStoredPath(String path) {
        if (path == null) {
            return "";
        }
        return path.replace('\\', '/').toLowerCase(java.util.Locale.ROOT);
    }
}
