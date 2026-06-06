package flash.pipeline.deconv.qc;

import flash.pipeline.ui.preview.PreviewPairPanel;
import ij.IJ;
import ij.ImagePlus;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JPanel;
import javax.swing.WindowConstants;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GraphicsEnvironment;
import java.awt.Frame;

/**
 * Modal RAW-vs-DECONV preview shown before a full deconvolution batch starts.
 *
 * <p>The visual surface reuses the shared {@link PreviewPairPanel} (the same component used by Set
 * Up Configuration) so deconvolution gets the familiar raw/processed inspection experience. The
 * decision contract is unchanged: run the full batch, go back to reconfigure, or cancel.
 *
 * <p>Image ownership: the {@link PreviewContent} stacks belong to the caller. This dialog only
 * displays them and never closes them; the caller must close them after {@code show(...)} returns.
 */
public final class DeconvPreviewDialog {

    private static volatile HeadlessProbe headlessProbe = new HeadlessProbe() {
        @Override
        public boolean isHeadless() {
            return GraphicsEnvironment.isHeadless() || IJ.getInstance() == null;
        }
    };

    private DeconvPreviewDialog() {}

    public static Decision show(PreviewContent content, boolean skipPreview) {
        if (skipPreview || isHeadless() || content == null) {
            return Decision.RUN_FULL_BATCH;
        }
        return showPreviewPanel(content);
    }

    private static Decision showPreviewPanel(PreviewContent content) {
        final Decision[] decision = new Decision[]{Decision.CANCEL};
        final JDialog dialog = new JDialog((Frame) null, "3D Deconvolution Preview", true);
        dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        dialog.setLayout(new BorderLayout(0, 8));
        dialog.add(buildPreviewRoot(content), BorderLayout.CENTER);
        dialog.add(buildButtonRow(dialog, decision), BorderLayout.SOUTH);
        dialog.setMinimumSize(new Dimension(760, 600));
        dialog.pack();
        dialog.setLocationRelativeTo(null);
        dialog.setVisible(true);
        return decision[0];
    }

    private static JPanel buildPreviewRoot(PreviewContent content) {
        PreviewPairPanel preview = new PreviewPairPanel(
                null,
                content.rawLabel,
                content.deconvolvedLabel,
                PreviewPairPanel.PreviewLayout.HORIZONTAL_SLIM);
        preview.setOriginal(content.rawStack);
        preview.setAdjusted(content.deconvolvedStack);
        preview.setAdjustedState(PreviewPairPanel.PreviewState.READY, "Preview complete.");
        preview.setDisplayControlsAvailable(true);
        // No raw/filtered source toggle: the two panes already are raw vs deconvolved.
        preview.setSourceToggleVisible(false);

        JPanel root = new JPanel(new BorderLayout(0, 8));
        root.add(preview.previewToolstrip(), BorderLayout.NORTH);
        root.add(preview, BorderLayout.CENTER);
        root.add(preview.sharedZRow(), BorderLayout.SOUTH);
        root.setBorder(BorderFactory.createEmptyBorder(8, 8, 0, 8));
        root.setPreferredSize(new Dimension(720, 520));
        return root;
    }

    private static JPanel buildButtonRow(final JDialog dialog, final Decision[] decision) {
        JButton runButton = new JButton("Use settings & run batch");
        JButton backButton = new JButton("Back to settings");
        JButton cancelButton = new JButton("Cancel");

        runButton.addActionListener(e -> {
            decision[0] = Decision.RUN_FULL_BATCH;
            dialog.dispose();
        });
        backButton.addActionListener(e -> {
            decision[0] = Decision.RECONFIGURE;
            dialog.dispose();
        });
        cancelButton.addActionListener(e -> {
            decision[0] = Decision.CANCEL;
            dialog.dispose();
        });

        JPanel row = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 8));
        row.add(runButton);
        row.add(backButton);
        row.add(cancelButton);
        dialog.getRootPane().setDefaultButton(runButton);
        return row;
    }

    static void setHeadlessProbeForTest(HeadlessProbe probe) {
        if (probe != null) {
            headlessProbe = probe;
        }
    }

    static void resetForTest() {
        headlessProbe = new HeadlessProbe() {
            @Override
            public boolean isHeadless() {
                return GraphicsEnvironment.isHeadless() || IJ.getInstance() == null;
            }
        };
    }

    private static boolean isHeadless() {
        return headlessProbe.isHeadless();
    }

    interface HeadlessProbe {
        boolean isHeadless();
    }

    public enum Decision {
        RUN_FULL_BATCH,
        RECONFIGURE,
        CANCEL
    }

    public static final class PreviewContent {
        public final ImagePlus rawStack;
        public final ImagePlus deconvolvedStack;
        public final String rawLabel;
        public final String deconvolvedLabel;

        public PreviewContent(ImagePlus rawStack,
                              ImagePlus deconvolvedStack,
                              String rawLabel,
                              String deconvolvedLabel) {
            this.rawStack = rawStack;
            this.deconvolvedStack = deconvolvedStack;
            this.rawLabel = rawLabel == null ? "Raw" : rawLabel;
            this.deconvolvedLabel = deconvolvedLabel == null ? "Deconvolved" : deconvolvedLabel;
        }
    }
}
