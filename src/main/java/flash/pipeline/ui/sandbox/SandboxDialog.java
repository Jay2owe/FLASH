package flash.pipeline.ui.sandbox;

import flash.pipeline.image.dag.DagIR;
import flash.pipeline.image.dag.DagIRSerializer;
import flash.pipeline.image.dag.DagToIjmEmitter;
import flash.pipeline.image.dag.IjmToDagLoader;
import ij.IJ;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
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

/**
 * Thin modal shell hosting a {@link FilterBuilderPanel}. The panel owns the
 * editor body and its preview pair; this class only adds Save/Cancel and the
 * synchronous {@link SecondaryLoop} that keeps the modal {@link #show} contract.
 */
public final class SandboxDialog extends JDialog {

    /**
     * Backwards-compatible alias for {@link FilterBuilderPanel.PreviewRunner};
     * pre-existing callers reference {@code SandboxDialog.PreviewHandler}.
     */
    public interface PreviewHandler extends FilterBuilderPanel.PreviewRunner {
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

    private final FilterBuilderPanel panel;
    private final CountDownLatch done = new CountDownLatch(1);
    private final JButton save = new JButton("Save");
    private final JButton cancel = new JButton("Cancel");

    private SecondaryLoop loop;
    private Result result = Result.cancel();

    private SandboxDialog(String channelLabel, DagIR initialDag, PreviewHandler previewHandler) {
        super((java.awt.Frame) null, "Filter Builder - " + safe(channelLabel), false);
        this.panel = new FilterBuilderPanel(initialDag, /*sharedPreview=*/null, previewHandler, null);

        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        setMinimumSize(new Dimension(980, 620));
        setLayout(new BorderLayout(8, 8));
        add(panel, BorderLayout.CENTER);
        add(buildFooter(), BorderLayout.SOUTH);
        wireButtons();
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

    private JPanel buildFooter() {
        JPanel footer = new JPanel(new GridBagLayout());
        footer.setBorder(BorderFactory.createEmptyBorder(4, 8, 8, 8));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridx = 0;
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = new Insets(0, 0, 0, 8);
        footer.add(new JPanel(), gbc);

        gbc.weightx = 0.0;
        gbc.fill = GridBagConstraints.NONE;
        gbc.insets = new Insets(0, 0, 0, 6);
        gbc.gridx++;
        footer.add(cancel, gbc);
        gbc.gridx++;
        gbc.insets = new Insets(0, 0, 0, 0);
        footer.add(save, gbc);
        return footer;
    }

    private void wireButtons() {
        save.addActionListener(e -> {
            DagIR dag = panel.currentDag();
            result = new Result(dag, DagToIjmEmitter.emit(dag));
            panel.markSaved();
            close();
        });
        cancel.addActionListener(e -> {
            if (!panel.confirmDiscardIfDirty()) return;
            result = Result.cancel();
            close();
        });
        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override public void windowOpened(java.awt.event.WindowEvent e) {
                panel.focusCatalog();
            }
            @Override public void windowClosing(java.awt.event.WindowEvent e) {
                if (!panel.confirmDiscardIfDirty()) return;
                result = Result.cancel();
                close();
            }
            @Override public void windowClosed(java.awt.event.WindowEvent e) {
                panel.releaseResources();
                done.countDown();
                if (loop != null) loop.exit();
            }
        });
    }

    private void close() {
        dispose();
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
