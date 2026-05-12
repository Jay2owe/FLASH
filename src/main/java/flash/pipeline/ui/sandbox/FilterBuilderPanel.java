package flash.pipeline.ui.sandbox;

import flash.pipeline.image.FilterExecutor;
import flash.pipeline.image.NamedFilterLoader;
import flash.pipeline.image.dag.DagIR;
import flash.pipeline.image.dag.DagToIjmEmitter;
import flash.pipeline.image.dag.IjmToDagLoader;
import flash.pipeline.ui.preview.PreviewPairPanel;
import ij.IJ;
import ij.ImagePlus;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Window;
import java.util.ArrayList;
import java.util.List;

/**
 * Reusable editor body for the channel filter builder. The standalone
 * {@link SandboxDialog} hosts an instance of this panel, and the wizard's
 * filter-hyperparameters stage will host another (stage 03+).
 *
 * <p>External code mutates and inspects the chain only through DAG IR
 * snapshots. {@link SandboxModel} stays package-private.</p>
 */
public final class FilterBuilderPanel extends JPanel {

    /** Provides preview source/output images. Mirrors the historical
     * {@code SandboxDialog.PreviewHandler}; {@code SandboxDialog.PreviewHandler}
     * extends this so legacy callers compile unchanged. */
    public interface PreviewRunner {
        ImagePlus createSource() throws Exception;

        default ImagePlus getSourceForDisplay() throws Exception {
            return createSource();
        }

        ImagePlus showPreview(ImagePlus result, ImagePlus existingPreview) throws Exception;

        void close(ImagePlus imp);
    }

    /**
     * Public DTO returned by {@link #nodeSummaries()}. Lets callers see the
     * order, identity, and disabled state of nodes in a linear pipeline
     * without leaking the package-private {@link SandboxModel}.
     */
    public static final class NodeSummary {
        public final String id;
        public final String commandName;
        public final boolean disabled;

        public NodeSummary(String id, String commandName, boolean disabled) {
            this.id = id == null ? "" : id;
            this.commandName = commandName == null ? "" : commandName;
            this.disabled = disabled;
        }
    }

    private final SandboxModel model;
    private final NodeEditorPanel nodeEditor;
    private final CombinerEditorPanel combinerEditor;
    private final DagCanvasPanel canvas;
    private final FilterCatalog catalog;
    private final PreviewPairPanel previews;
    private final boolean ownsPreviews;
    private final PreviewRunner runner;
    private final List<Runnable> changeListeners = new ArrayList<Runnable>();

    private final JLabel status = new JLabel(" ");
    private final JLabel legacyBanner = new JLabel(
            "This chain runs through legacy execution (slower, single-threaded per image).");
    private final JButton previewSelected = new JButton("Preview up to selected step");
    private final JButton previewFinal = new JButton("Preview full filter");
    private final JButton startFromPreset = new JButton("Start from a preset...");
    private final JButton help = new JButton("?");

    private String savedIjmSnapshot;
    private int savedNodeCount;
    private ImagePlus sourceImage;
    private ImagePlus previewImage;
    private boolean busy = false;

    public FilterBuilderPanel(DagIR seed,
                              PreviewPairPanel sharedPreview,
                              PreviewRunner runner,
                              Runnable onModelChanged) {
        super(new BorderLayout(8, 8));
        this.model = SandboxModel.fromDag(seed);
        this.savedIjmSnapshot = DagToIjmEmitter.emit(model.toDag());
        this.savedNodeCount = countNodes(model);
        this.runner = runner;

        this.nodeEditor = new NodeEditorPanel(new Runnable() {
            @Override public void run() {
                canvas.rebuild();
                notifyListeners();
            }
        });
        this.combinerEditor = new CombinerEditorPanel(model, new Runnable() {
            @Override public void run() {
                canvas.rebuild();
                notifyListeners();
            }
        });
        this.catalog = new FilterCatalog();
        this.canvas = new DagCanvasPanel(model, new DagCanvasPanel.CatalogSupplier() {
            @Override public FilterCatalog.Entry getSelectedCatalogEntry() {
                return catalog.getSelectedEntry();
            }
        }, new DagCanvasPanel.NodeCreator() {
            @Override public boolean addNode(SandboxModel.Line line, FilterCatalog.Entry entry) {
                return addCatalogNode(line, entry);
            }
        }, new Runnable() {
            @Override public void run() { refreshEditors(); }
        }, new Runnable() {
            @Override public void run() {
                refreshEditors();
                notifyListeners();
            }
        });

        if (sharedPreview != null) {
            this.previews = sharedPreview;
            this.ownsPreviews = false;
        } else {
            this.previews = new PreviewPairPanel(null, "Source image", "Preview output");
            this.ownsPreviews = true;
        }
        this.previews.largeViewButton().setToolTipText(
                "Open source and preview images in a larger synced view");
        this.previews.displayControlsButton().setToolTipText(
                "Open temporary preview brightness and contrast controls");

        catalog.setAddRequestListener(new FilterCatalog.AddRequestListener() {
            @Override public void onAddRequested(FilterCatalog.Entry entry) {
                SandboxModel.Line target = resolveTargetLine();
                if (target == null) return;
                if (addCatalogNode(target, entry)) canvas.rebuild();
                refreshEditors();
            }
        });

        legacyBanner.setOpaque(true);
        legacyBanner.setBackground(new Color(255, 244, 204));
        legacyBanner.setBorder(BorderFactory.createEmptyBorder(6, 10, 6, 10));
        legacyBanner.setVisible(false);

        add(legacyBanner, BorderLayout.NORTH);
        add(buildMain(), BorderLayout.CENTER);
        add(buildFooter(), BorderLayout.SOUTH);

        wireButtons();
        refreshSourcePreview();
        refreshEditors();

        if (onModelChanged != null) addChangeListener(onModelChanged);
    }

    // ── public API ────────────────────────────────────────────────────────

    public DagIR currentDag() {
        return model.toDag();
    }

    public String currentIjm() {
        return DagToIjmEmitter.emit(currentDag());
    }

    public boolean isDirty() {
        return !currentIjm().equals(savedIjmSnapshot);
    }

    public void loadPreset(DagIR seed, String label) {
        SandboxModel fresh = SandboxModel.fromDag(seed);
        model.lines.clear();
        model.lines.addAll(fresh.lines);
        model.combiners.clear();
        model.combiners.addAll(fresh.combiners);
        model.selected = model.lines.isEmpty() ? null : model.lines.get(0);
        savedIjmSnapshot = currentIjm();
        savedNodeCount = countNodes(model);
        canvas.rebuild();
        refreshEditors();
        if (label != null && label.length() > 0) {
            status.setText("Loaded preset: " + label);
        }
        notifyListeners();
    }

    public void markSaved() {
        savedIjmSnapshot = currentIjm();
        savedNodeCount = countNodes(model);
    }

    // ── Stage 04 structural-mutation API ─────────────────────────────────
    //
    // Each method requires a single linear line — the wizard composes one
    // hidden panel for the linear-edit case, and the branched-preset banner
    // gates structural controls when the DAG isn't linear. Callers get a
    // clear IllegalStateException if they reach for these methods on a
    // branched DAG; they should consult isLinear() on a freshly loaded
    // DagIR before structural ops.

    public void appendNode(FilterCatalog.Entry entry) {
        if (entry == null || entry.stub) return;
        SandboxModel.Line line = singleLineOrThrow();
        model.addNode(line, entry);
        canvas.rebuild();
        notifyListeners();
    }

    public void appendNode(FilterCatalog.Entry entry, String args) {
        if (entry == null || entry.stub) return;
        SandboxModel.Line line = singleLineOrThrow();
        model.addNode(line, entry, args);
        canvas.rebuild();
        notifyListeners();
    }

    public void insertNodeAt(int index, FilterCatalog.Entry entry) {
        if (entry == null || entry.stub) return;
        SandboxModel.Line line = singleLineOrThrow();
        model.addNode(line, entry);
        SandboxModel.Node added = line.nodes.remove(line.nodes.size() - 1);
        int target = index;
        if (target < 0) target = 0;
        if (target > line.nodes.size()) target = line.nodes.size();
        line.nodes.add(target, added);
        model.selected = added;
        canvas.rebuild();
        notifyListeners();
    }

    public void removeNode(String nodeId) {
        SandboxModel.Line line = singleLineOrThrow();
        SandboxModel.Node target = findNodeById(line, nodeId);
        if (target == null) return;
        model.removeNode(line, target);
        canvas.rebuild();
        notifyListeners();
    }

    public void reorder(int fromIndex, int toIndex) {
        SandboxModel.Line line = singleLineOrThrow();
        int n = line.nodes.size();
        if (fromIndex < 0 || fromIndex >= n) return;
        int target = toIndex;
        if (target < 0) target = 0;
        if (target >= n) target = n - 1;
        if (fromIndex == target) return;
        SandboxModel.Node node = line.nodes.remove(fromIndex);
        line.nodes.add(target, node);
        canvas.rebuild();
        notifyListeners();
    }

    public void setNodeDisabled(String nodeId, boolean disabled) {
        SandboxModel.Line line = singleLineOrThrow();
        SandboxModel.Node target = findNodeById(line, nodeId);
        if (target == null || target.disabled == disabled) return;
        target.disabled = disabled;
        canvas.rebuild();
        notifyListeners();
    }

    /**
     * Stage 04: pushes parameter-row text edits from the wizard accordion
     * back into the hidden builder's SandboxModel so subsequent structural
     * mutations don't lose those edits. The wizard is the single source of
     * truth for parameter values; this method just keeps the model in sync.
     */
    public void updateNodeArgs(String nodeId, String args) {
        SandboxModel.Line line = singleLineOrThrow();
        SandboxModel.Node target = findNodeById(line, nodeId);
        if (target == null) return;
        target.args = args == null ? "" : args;
    }

    public List<NodeSummary> nodeSummaries() {
        SandboxModel.Line line = singleLineOrThrow();
        List<NodeSummary> out = new ArrayList<NodeSummary>(line.nodes.size());
        for (int i = 0; i < line.nodes.size(); i++) {
            SandboxModel.Node n = line.nodes.get(i);
            out.add(new NodeSummary(n.id, summaryCommandFor(n), n.disabled));
        }
        return out;
    }

    /**
     * Emits the IJM with every node included, even disabled ones. Used by
     * the wizard accordion to keep disabled rows visible (greyed out) so
     * the user can re-enable them without dropping into the canvas.
     * {@link #currentIjm()} continues to filter disabled nodes for execution.
     */
    public String currentDisplayIjm() {
        SandboxModel.Line line = singleLineOrThrow();
        boolean[] saved = new boolean[line.nodes.size()];
        for (int i = 0; i < line.nodes.size(); i++) {
            saved[i] = line.nodes.get(i).disabled;
            line.nodes.get(i).disabled = false;
        }
        try {
            return DagToIjmEmitter.emit(model.toDag());
        } finally {
            for (int i = 0; i < line.nodes.size(); i++) {
                line.nodes.get(i).disabled = saved[i];
            }
        }
    }

    private SandboxModel.Line singleLineOrThrow() {
        if (model.lines.size() != 1 || !model.combiners.isEmpty()) {
            throw new IllegalStateException(
                    "Structural mutation requires a single linear DAG line; this builder has "
                            + model.lines.size() + " line(s) and "
                            + model.combiners.size() + " combiner(s).");
        }
        return model.lines.get(0);
    }

    private static SandboxModel.Node findNodeById(SandboxModel.Line line, String nodeId) {
        if (nodeId == null) return null;
        for (int i = 0; i < line.nodes.size(); i++) {
            SandboxModel.Node n = line.nodes.get(i);
            if (nodeId.equals(n.id)) return n;
        }
        return null;
    }

    private static String summaryCommandFor(SandboxModel.Node node) {
        if (node.commandName != null && !node.commandName.isEmpty()) return node.commandName;
        String cmd = DagToIjmEmitter.commandFor(node.type);
        return cmd == null ? "" : cmd;
    }

    public void addChangeListener(Runnable r) {
        if (r != null) changeListeners.add(r);
    }

    /**
     * Returns true if there are no unsaved changes, or the user confirmed
     * discarding them. Shows a confirmation dialog parented to this panel's
     * hosting Window when dirty.
     */
    public boolean confirmDiscardIfDirty() {
        if (!isDirty()) return true;
        int delta = Math.abs(countNodes(model) - savedNodeCount);
        String message = delta == 1
                ? "Discard your changes? You've modified 1 step."
                : "Discard your changes? You've modified " + delta + " step(s).";
        Object[] options = new Object[] { "Keep editing", "Discard changes" };
        Window owner = SwingUtilities.getWindowAncestor(this);
        int choice = JOptionPane.showOptionDialog(owner,
                message,
                "Discard changes?",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE,
                null,
                options,
                options[0]);
        return choice == JOptionPane.NO_OPTION;
    }

    /**
     * Releases preview-handler-owned images and any owned dialogs.
     * Call when the host window is closing.
     */
    public void releaseResources() {
        if (runner != null) {
            runner.close(previewImage);
            if (sourceImage != previewImage) {
                runner.close(sourceImage);
            }
        }
        previewImage = null;
        sourceImage = null;
        if (ownsPreviews) {
            previews.disposeDisplayControlsDialog();
        }
    }

    /** Catalog search field receives focus on next event-dispatch tick. */
    public void focusCatalog() {
        SwingUtilities.invokeLater(new Runnable() {
            @Override public void run() {
                catalog.focusSearch();
            }
        });
    }

    // ── layout ────────────────────────────────────────────────────────────

    private JPanel buildMain() {
        JPanel right = new JPanel(new BorderLayout(6, 6));
        right.add(catalog, BorderLayout.CENTER);

        JPanel editors = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        editors.add(nodeEditor, gbc);
        gbc.gridy = 1;
        editors.add(combinerEditor, gbc);
        gbc.gridy = 2;
        gbc.weighty = 1.0;
        gbc.fill = GridBagConstraints.BOTH;
        editors.add(new JPanel(), gbc);
        right.add(editors, BorderLayout.SOUTH);

        JScrollPane canvasScroll = new JScrollPane(canvas);
        canvasScroll.setBorder(BorderFactory.createEmptyBorder());

        JLabel intro = new JLabel("Pick a step from 'Available steps' on the right, then click '+ Add step' on the branch you want it on.");
        intro.setOpaque(true);
        intro.setBackground(new Color(232, 244, 252));
        intro.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, new Color(180, 200, 220)),
                BorderFactory.createEmptyBorder(6, 10, 6, 10)));

        JPanel left = new JPanel(new BorderLayout(0, 4));
        left.add(intro, BorderLayout.NORTH);
        left.add(canvasScroll, BorderLayout.CENTER);

        JSplitPane editorSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, right);
        editorSplit.setResizeWeight(0.72);
        editorSplit.setDividerLocation(700);

        JPanel main = new JPanel(new BorderLayout());
        main.setBorder(BorderFactory.createEmptyBorder(8, 8, 0, 8));

        if (ownsPreviews) {
            JPanel previewColumn = new JPanel(new BorderLayout());
            previewColumn.setMinimumSize(new Dimension(260, 1));
            previewColumn.add(previews, BorderLayout.CENTER);

            JSplitPane mainSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, previewColumn, editorSplit);
            mainSplit.setResizeWeight(0.30);
            mainSplit.setDividerLocation(320);
            main.add(mainSplit, BorderLayout.CENTER);
        } else {
            main.add(editorSplit, BorderLayout.CENTER);
        }
        return main;
    }

    private JPanel buildFooter() {
        JPanel footer = new JPanel(new GridBagLayout());
        footer.setBorder(BorderFactory.createEmptyBorder(4, 8, 8, 8));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(0, 0, 0, 6);

        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.weightx = 0.0;
        gbc.fill = GridBagConstraints.NONE;
        footer.add(previews.largeViewButton(), gbc);
        gbc.gridx++;
        footer.add(previews.displayControlsButton(), gbc);
        gbc.gridx++;
        footer.add(startFromPreset, gbc);
        gbc.gridx++;
        footer.add(help, gbc);

        gbc.gridx++;
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = new Insets(0, 8, 0, 8);
        footer.add(status, gbc);

        gbc.weightx = 0.0;
        gbc.fill = GridBagConstraints.NONE;
        gbc.insets = new Insets(0, 0, 0, 6);
        gbc.gridx++;
        footer.add(previewSelected, gbc);
        gbc.gridx++;
        gbc.insets = new Insets(0, 0, 0, 0);
        footer.add(previewFinal, gbc);
        return footer;
    }

    private void wireButtons() {
        previewSelected.addActionListener(e -> preview(model.toPartialDag()));
        previewFinal.addActionListener(e -> preview(model.toDag()));
        startFromPreset.addActionListener(e -> startFromPreset());
        help.setToolTipText("What do these buttons do?");
        help.addActionListener(e -> showSandboxHelp());
    }

    // ── preview ───────────────────────────────────────────────────────────

    private void refreshSourcePreview() {
        if (runner == null) {
            replaceSourceImage(null);
            return;
        }
        try {
            replaceSourceImage(runner.getSourceForDisplay());
        } catch (Exception ex) {
            replaceSourceImage(null);
            status.setText("Source preview unavailable.");
            IJ.log("WARNING: sandbox source preview unavailable: " + ex.getMessage());
        }
    }

    private void replaceSourceImage(ImagePlus display) {
        if (display == sourceImage) {
            previews.setOriginal(sourceImage);
            return;
        }
        ImagePlus oldSource = sourceImage;
        sourceImage = display;
        previews.setOriginal(sourceImage);
        if (oldSource != null && oldSource != previewImage && runner != null) {
            runner.close(oldSource);
        }
    }

    private void preview(final DagIR dag) {
        final Window owner = SwingUtilities.getWindowAncestor(this);
        if (runner == null) {
            IJ.showMessage("Sandbox Preview", "No preview image is available.");
            previews.setAdjustedState(PreviewPairPanel.PreviewState.ERROR,
                    "No preview image is available.");
            return;
        }
        refreshSourcePreview();
        setBusy(true, "Running preview...");
        previews.setAdjustedState(PreviewPairPanel.PreviewState.RUNNING, "Running preview...");
        Thread worker = new Thread(new Runnable() {
            @Override public void run() {
                ImagePlus source = null;
                ImagePlus rendered = null;
                try {
                    source = runner.createSource();
                    if (source == null) throw new IllegalStateException("No preview source image is available.");
                    if ("legacy".equals(dag.executionTier)) {
                        rendered = FilterExecutor.runLegacyDagSandboxed(source, dag);
                    } else {
                        rendered = FilterExecutor.runDagThreadSafe(source, dag);
                    }
                    final ImagePlus previewResult = rendered;
                    SwingUtilities.invokeLater(new Runnable() {
                        @Override public void run() {
                            try {
                                previewImage = runner.showPreview(previewResult, previewImage);
                                previews.setAdjusted(previewImage);
                                previews.setAdjustedState(PreviewPairPanel.PreviewState.READY,
                                        "Preview complete.");
                                setBusy(false, "Preview complete.");
                            } catch (Exception ex) {
                                runner.close(previewResult);
                                JOptionPane.showMessageDialog(owner,
                                        "Preview display failed:\n" + ex.getMessage(),
                                        "Sandbox Preview",
                                        JOptionPane.WARNING_MESSAGE);
                                previews.setAdjustedState(PreviewPairPanel.PreviewState.ERROR,
                                        ex.getMessage());
                                setBusy(false, "Preview failed.");
                            }
                        }
                    });
                    rendered = null;
                } catch (final Exception ex) {
                    final String message = ex.getMessage();
                    SwingUtilities.invokeLater(new Runnable() {
                        @Override public void run() {
                            JOptionPane.showMessageDialog(owner,
                                    "Preview failed:\n" + message,
                                    "Sandbox Preview",
                                    JOptionPane.WARNING_MESSAGE);
                            previews.setAdjustedState(PreviewPairPanel.PreviewState.ERROR, message);
                            setBusy(false, "Preview failed.");
                        }
                    });
                } finally {
                    if (runner != null) {
                        runner.close(source);
                        runner.close(rendered);
                    }
                }
            }
        }, "sandbox-dag-preview");
        worker.setDaemon(true);
        worker.start();
    }

    private void setBusy(boolean busy, String message) {
        this.busy = busy;
        startFromPreset.setEnabled(!busy);
        refreshPreviewButtons();
        status.setText(message == null ? " " : message);
    }

    private void refreshPreviewButtons() {
        previewSelected.setEnabled(!busy && model.selected instanceof SandboxModel.Node);
        previewFinal.setEnabled(!busy && hasAnyNode(model));
    }

    private static boolean hasAnyNode(SandboxModel model) {
        for (int i = 0; i < model.lines.size(); i++) {
            if (!model.lines.get(i).nodes.isEmpty()) return true;
        }
        return false;
    }

    // ── editors / catalog ─────────────────────────────────────────────────

    private void refreshEditors() {
        Object selected = model.selected;
        nodeEditor.setNode(selected instanceof SandboxModel.Node ? (SandboxModel.Node) selected : null);
        combinerEditor.setCombiner(selected instanceof SandboxModel.CombinerNode
                ? (SandboxModel.CombinerNode) selected
                : null);
        legacyBanner.setVisible(model.hasLegacyNode());
        refreshPreviewButtons();
    }

    private SandboxModel.Line resolveTargetLine() {
        Object sel = model.selected;
        if (sel instanceof SandboxModel.Node) {
            SandboxModel.Node node = (SandboxModel.Node) sel;
            for (int i = 0; i < model.lines.size(); i++) {
                SandboxModel.Line line = model.lines.get(i);
                if (line.nodes.contains(node)) return line;
            }
        }
        if (sel instanceof SandboxModel.Line) return (SandboxModel.Line) sel;
        if (!model.lines.isEmpty()) {
            SandboxModel.Line first = model.lines.get(0);
            model.selected = first;
            return first;
        }
        return null;
    }

    private void startFromPreset() {
        if (!confirmDiscardIfDirty()) return;
        Window owner = SwingUtilities.getWindowAncestor(this);
        String[] presets = NamedFilterLoader.FILTER_NAMES;
        String chosen = (String) JOptionPane.showInputDialog(owner,
                "Choose a preset to start from:",
                "Start from a preset",
                JOptionPane.PLAIN_MESSAGE,
                null,
                presets,
                presets.length > 0 ? presets[0] : null);
        if (chosen == null) return;
        String content = NamedFilterLoader.loadFilterContent(chosen);
        if (content == null) {
            JOptionPane.showMessageDialog(owner,
                    "Could not load preset: " + chosen,
                    "Start from a preset",
                    JOptionPane.WARNING_MESSAGE);
            return;
        }
        DagIR dag = IjmToDagLoader.load(content);
        loadPreset(dag, chosen);
    }

    private boolean addCatalogNode(SandboxModel.Line line, FilterCatalog.Entry entry) {
        if (line == null || entry == null || entry.stub) {
            if (entry == null) {
                status.setText("Pick a step from 'Available steps' first.");
            }
            return false;
        }
        Window owner = SwingUtilities.getWindowAncestor(this);
        if (!entry.legacy) {
            model.addNode(line, entry);
            notifyListeners();
            return true;
        }
        if (runner == null) {
            JOptionPane.showMessageDialog(owner,
                    "No preview image is available for Fiji's parameter dialog.",
                    "Fiji Command",
                    JOptionPane.WARNING_MESSAGE);
            return false;
        }
        ImagePlus source = null;
        try {
            source = runner.createSource();
            RecorderParameterProbe.ProbeResult probe =
                    RecorderParameterProbe.probe(source, entry.commandName);
            if (probe.userCancelled) {
                if (probe.errorMessage.length() > 0) {
                    JOptionPane.showMessageDialog(owner,
                            "Command was not added:\n" + probe.errorMessage,
                            "Fiji Command",
                            JOptionPane.WARNING_MESSAGE);
                }
                return false;
            }
            model.addNode(line, entry, probe.optionsString);
            notifyListeners();
            return true;
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(owner,
                    "Command was not added:\n" + ex.getMessage(),
                    "Fiji Command",
                    JOptionPane.WARNING_MESSAGE);
            return false;
        } finally {
            if (runner != null) runner.close(source);
        }
    }

    private void showSandboxHelp() {
        Window owner = SwingUtilities.getWindowAncestor(this);
        String msg = "<html><body style='width:380px;'>"
                + "Build the channel's custom filter as a chain of steps. Pick a step "
                + "from <b>Available steps</b> on the right, then click <b>+ Add step</b> "
                + "on the branch you want it on. Click a step in <b>Your filter</b> to "
                + "edit its settings."
                + "<br><br>"
                + "<b>Start from a preset...</b><br>"
                + "Replaces the current chain with one of the bundled filter presets "
                + "as a starting point."
                + "<br><br>"
                + "<b>Preview up to selected step</b><br>"
                + "Runs the chain only up to the step you have selected, so you can "
                + "see intermediate results."
                + "<br><br>"
                + "<b>Preview full filter</b><br>"
                + "Runs the entire chain on the sample image."
                + "<br><br>"
                + "<b>Large view</b><br>"
                + "Opens the source and preview images in a larger window with synced Z sliders."
                + "</body></html>";
        JOptionPane.showMessageDialog(owner, msg, "Filter Builder — Help",
                JOptionPane.INFORMATION_MESSAGE);
    }

    private void notifyListeners() {
        for (int i = 0; i < changeListeners.size(); i++) {
            changeListeners.get(i).run();
        }
    }

    private static int countNodes(SandboxModel model) {
        int total = model.combiners.size();
        for (int i = 0; i < model.lines.size(); i++) {
            total += model.lines.get(i).nodes.size();
        }
        return total;
    }
}
