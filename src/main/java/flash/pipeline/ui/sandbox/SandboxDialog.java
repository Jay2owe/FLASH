package flash.pipeline.ui.sandbox;

import flash.pipeline.image.FilterExecutor;
import flash.pipeline.image.NamedFilterLoader;
import flash.pipeline.image.dag.DagIR;
import flash.pipeline.image.dag.DagIRSerializer;
import flash.pipeline.image.dag.DagToIjmEmitter;
import flash.pipeline.image.dag.IjmToDagLoader;
import ij.IJ;
import ij.ImagePlus;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.GraphicsEnvironment;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.SecondaryLoop;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.CountDownLatch;

public final class SandboxDialog extends JDialog {

    public interface PreviewHandler {
        ImagePlus createSource() throws Exception;
        ImagePlus showPreview(ImagePlus result, ImagePlus existingPreview) throws Exception;
        void close(ImagePlus imp);
    }

    public static final class Result {
        public final DagIR dag;
        public final String ijmFallback;

        private Result(DagIR dag, String ijmFallback) {
            this.dag = dag;
            this.ijmFallback = ijmFallback;
        }

        public static Result cancel() {
            return new Result(null, null);
        }
    }

    private final SandboxModel model;
    private final PreviewHandler previewHandler;
    private final CountDownLatch done = new CountDownLatch(1);
    private final NodeEditorPanel nodeEditor;
    private final CombinerEditorPanel combinerEditor;
    private final DagCanvasPanel canvas;
    private final FilterCatalog catalog;
    private final JLabel status = new JLabel(" ");
    private final JLabel legacyBanner = new JLabel("This chain runs through legacy execution (slower, single-threaded per image).");
    private final JButton previewSelected = new JButton("Preview up to selected step");
    private final JButton previewFinal = new JButton("Preview full filter");
    private final JButton startFromPreset = new JButton("Start from a preset...");
    private final JButton help = new JButton("?");
    private final JButton save = new JButton("Save");
    private final JButton cancel = new JButton("Cancel");

    private SecondaryLoop loop;
    private Result result = Result.cancel();
    private ImagePlus previewImage;
    private boolean busy = false;
    private final String initialIjm;
    private final int initialNodeCount;

    private SandboxDialog(String channelLabel, DagIR initialDag, PreviewHandler previewHandler) {
        super((java.awt.Frame) null, "Filter Builder - " + safe(channelLabel), false);
        this.model = SandboxModel.fromDag(initialDag);
        this.initialIjm = DagToIjmEmitter.emit(model.toDag());
        this.initialNodeCount = countNodes(model);
        this.previewHandler = previewHandler;
        this.nodeEditor = new NodeEditorPanel(new Runnable() {
            @Override public void run() { canvas.rebuild(); }
        });
        this.combinerEditor = new CombinerEditorPanel(model, new Runnable() {
            @Override public void run() { canvas.rebuild(); }
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
            @Override public void run() { refreshEditors(); }
        });
        catalog.setAddRequestListener(new FilterCatalog.AddRequestListener() {
            @Override public void onAddRequested(FilterCatalog.Entry entry) {
                SandboxModel.Line target = resolveTargetLine();
                if (target == null) return;
                if (addCatalogNode(target, entry)) canvas.rebuild();
                refreshEditors();
            }
        });

        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        setMinimumSize(new Dimension(980, 620));
        setLayout(new BorderLayout(8, 8));
        legacyBanner.setOpaque(true);
        legacyBanner.setBackground(new Color(255, 244, 204));
        legacyBanner.setBorder(BorderFactory.createEmptyBorder(6, 10, 6, 10));
        legacyBanner.setVisible(false);
        add(legacyBanner, BorderLayout.NORTH);
        add(buildMain(), BorderLayout.CENTER);
        add(buildFooter(), BorderLayout.SOUTH);
        wireButtons();
        refreshEditors();
        pack();
        setLocationRelativeTo(null);
    }

    public static Result show(String channelLabel, File binFolder, int channelIndex,
                              String seedMacro, PreviewHandler previewHandler) {
        if (GraphicsEnvironment.isHeadless()) return Result.cancel();
        final DagIR initialDag = loadInitialDag(binFolder, channelIndex, seedMacro);
        final SandboxDialog dialog = new SandboxDialog(channelLabel, initialDag, previewHandler);
        dialog.setVisible(true);

        if (SwingUtilities.isEventDispatchThread()) {
            dialog.loop = java.awt.Toolkit.getDefaultToolkit().getSystemEventQueue().createSecondaryLoop();
            dialog.loop.enter();
        } else {
            try {
                dialog.done.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return Result.cancel();
            }
        }
        return dialog.result;
    }

    private static DagIR loadInitialDag(File binFolder, int channelIndex, String seedMacro) {
        // Lookup order: per-channel .bin/C{n}_Sandbox.dag.json, then seedMacro via IjmToDagLoader.
        // (Future: a shared .bin/Shared_Sandbox.dag.json could slot in between once a writer exists.)
        File channelDag = binFolder == null ? null
                : new File(binFolder, "C" + (channelIndex + 1) + "_Sandbox.dag.json");
        if (channelDag != null && channelDag.exists()) {
            try {
                return DagIRSerializer.fromJson(new String(Files.readAllBytes(channelDag.toPath()), StandardCharsets.UTF_8));
            } catch (Exception e) {
                IJ.log("WARNING: could not load " + channelDag.getName() + ": " + e.getMessage());
            }
        }
        return IjmToDagLoader.load(seedMacro);
    }

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

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, right);
        split.setResizeWeight(0.72);
        split.setDividerLocation(700);

        JPanel main = new JPanel(new BorderLayout());
        main.setBorder(BorderFactory.createEmptyBorder(8, 8, 0, 8));
        main.add(split, BorderLayout.CENTER);
        return main;
    }

    private JPanel buildFooter() {
        JPanel footer = new JPanel(new GridBagLayout());
        footer.setBorder(BorderFactory.createEmptyBorder(4, 8, 8, 8));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(0, 0, 0, 6);

        // Left cluster: start-from-preset, then help.
        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.weightx = 0.0;
        gbc.fill = GridBagConstraints.NONE;
        footer.add(startFromPreset, gbc);
        gbc.gridx++;
        footer.add(help, gbc);

        // Status spacer expands to push the right cluster to the right.
        gbc.gridx++;
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = new Insets(0, 8, 0, 8);
        footer.add(status, gbc);

        // Right cluster: cancel, preview-up-to-selected, preview-full, save.
        gbc.weightx = 0.0;
        gbc.fill = GridBagConstraints.NONE;
        gbc.insets = new Insets(0, 0, 0, 6);
        gbc.gridx++;
        footer.add(cancel, gbc);
        gbc.gridx++;
        footer.add(previewSelected, gbc);
        gbc.gridx++;
        footer.add(previewFinal, gbc);
        gbc.gridx++;
        gbc.insets = new Insets(0, 0, 0, 0);
        footer.add(save, gbc);
        return footer;
    }

    private void showSandboxHelp() {
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
                + "<b>Save</b><br>"
                + "Saves the current chain as the channel's custom filter."
                + "<br><br>"
                + "<b>Cancel</b><br>"
                + "Closes the builder without saving. You'll be asked to confirm if "
                + "you've made changes."
                + "</body></html>";
        JOptionPane.showMessageDialog(this, msg, "Filter Builder — Help",
                JOptionPane.INFORMATION_MESSAGE);
    }

    private void wireButtons() {
        previewSelected.addActionListener(e -> preview(model.toPartialDag()));
        previewFinal.addActionListener(e -> preview(model.toDag()));
        startFromPreset.addActionListener(e -> startFromPreset());
        help.setToolTipText("What do these buttons do?");
        help.addActionListener(e -> showSandboxHelp());
        save.addActionListener(e -> {
            DagIR dag = model.toDag();
            result = new Result(dag, DagToIjmEmitter.emit(dag));
            close();
        });
        cancel.addActionListener(e -> {
            if (!confirmDiscardIfDirty()) return;
            result = Result.cancel();
            close();
        });
        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override public void windowOpened(java.awt.event.WindowEvent e) {
                SwingUtilities.invokeLater(new Runnable() {
                    @Override public void run() {
                        catalog.focusSearch();
                    }
                });
            }
            @Override public void windowClosing(java.awt.event.WindowEvent e) {
                if (!confirmDiscardIfDirty()) return;
                result = Result.cancel();
                close();
            }
            @Override public void windowClosed(java.awt.event.WindowEvent e) {
                if (previewHandler != null) previewHandler.close(previewImage);
                done.countDown();
                if (loop != null) loop.exit();
            }
        });
    }

    private boolean confirmDiscardIfDirty() {
        String currentIjm = DagToIjmEmitter.emit(model.toDag());
        if (currentIjm.equals(initialIjm)) return true;
        int delta = Math.abs(countNodes(model) - initialNodeCount);
        String message = delta == 1
                ? "Discard your changes? You've modified 1 step."
                : "Discard your changes? You've modified " + delta + " step(s).";
        Object[] options = new Object[] { "Keep editing", "Discard changes" };
        int choice = JOptionPane.showOptionDialog(this,
                message,
                "Discard changes?",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE,
                null,
                options,
                options[0]);
        return choice == JOptionPane.NO_OPTION;
    }

    private static int countNodes(SandboxModel model) {
        int total = model.combiners.size();
        for (int i = 0; i < model.lines.size(); i++) {
            total += model.lines.get(i).nodes.size();
        }
        return total;
    }

    private void preview(final DagIR dag) {
        if (previewHandler == null) {
            IJ.showMessage("Sandbox Preview", "No preview image is available.");
            return;
        }
        setBusy(true, "Running preview...");
        Thread worker = new Thread(new Runnable() {
            @Override public void run() {
                ImagePlus source = null;
                ImagePlus rendered = null;
                try {
                    source = previewHandler.createSource();
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
                                previewImage = previewHandler.showPreview(previewResult, previewImage);
                                setBusy(false, "Preview complete.");
                            } catch (Exception ex) {
                                previewHandler.close(previewResult);
                                IJ.showMessage("Sandbox Preview", "Preview display failed:\n" + ex.getMessage());
                                setBusy(false, "Preview failed.");
                            }
                        }
                    });
                    rendered = null;
                } catch (final Exception ex) {
                    final String message = ex.getMessage();
                    SwingUtilities.invokeLater(new Runnable() {
                        @Override public void run() {
                            IJ.showMessage("Sandbox Preview", "Preview failed:\n" + message);
                            setBusy(false, "Preview failed.");
                        }
                    });
                } finally {
                    if (previewHandler != null) {
                        previewHandler.close(source);
                        previewHandler.close(rendered);
                    }
                }
            }
        }, "sandbox-dag-preview");
        worker.setDaemon(true);
        worker.start();
    }

    private void setBusy(boolean busy, String message) {
        this.busy = busy;
        save.setEnabled(!busy);
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
        String[] presets = NamedFilterLoader.FILTER_NAMES;
        String chosen = (String) JOptionPane.showInputDialog(this,
                "Choose a preset to start from:",
                "Start from a preset",
                JOptionPane.PLAIN_MESSAGE,
                null,
                presets,
                presets.length > 0 ? presets[0] : null);
        if (chosen == null) return;
        String content = NamedFilterLoader.loadFilterContent(chosen);
        if (content == null) {
            IJ.showMessage("Start from a preset", "Could not load preset: " + chosen);
            return;
        }
        DagIR dag = IjmToDagLoader.load(content);
        SandboxModel fresh = SandboxModel.fromDag(dag);
        model.lines.clear();
        model.lines.addAll(fresh.lines);
        model.combiners.clear();
        model.combiners.addAll(fresh.combiners);
        model.selected = model.lines.isEmpty() ? null : model.lines.get(0);
        canvas.rebuild();
        refreshEditors();
        status.setText("Loaded preset: " + chosen);
    }

    private boolean addCatalogNode(SandboxModel.Line line, FilterCatalog.Entry entry) {
        if (line == null || entry == null || entry.stub) {
            if (entry == null) {
                status.setText("Pick a step from 'Available steps' first.");
            }
            return false;
        }
        if (!entry.legacy) {
            model.addNode(line, entry);
            return true;
        }
        if (previewHandler == null) {
            IJ.showMessage("Fiji Command", "No preview image is available for Fiji's parameter dialog.");
            return false;
        }
        ImagePlus source = null;
        try {
            source = previewHandler.createSource();
            RecorderParameterProbe.ProbeResult probe =
                    RecorderParameterProbe.probe(source, entry.commandName);
            if (probe.userCancelled) {
                if (probe.errorMessage.length() > 0) {
                    IJ.showMessage("Fiji Command", "Command was not added:\n" + probe.errorMessage);
                }
                return false;
            }
            model.addNode(line, entry, probe.optionsString);
            return true;
        } catch (Exception ex) {
            IJ.showMessage("Fiji Command", "Command was not added:\n" + ex.getMessage());
            return false;
        } finally {
            if (previewHandler != null) previewHandler.close(source);
        }
    }

    private void close() {
        dispose();
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
