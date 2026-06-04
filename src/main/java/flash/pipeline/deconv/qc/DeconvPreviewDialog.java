package flash.pipeline.deconv.qc;

import flash.pipeline.ui.preview.PreviewPairPanel;
import ij.IJ;
import ij.ImagePlus;

import javax.swing.JOptionPane;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.GraphicsEnvironment;

/**
 * Modal RAW-vs-DECONV preview shown before a full deconvolution batch starts.
 *
 * <p>The visual surface reuses the shared {@link PreviewPairPanel} (the same component used by Set
 * Up Configuration) so deconvolution gets the familiar raw/processed inspection experience. The
 * decision contract is unchanged: run the full batch, go back to reconfigure, or cancel.
 *
 * <p>Image ownership: the {@link PreviewContent} projections belong to the caller. This dialog only
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
        JPanel root = buildPreviewRoot(content);

        Object[] options = {"Use settings & run batch", "Back to settings", "Cancel"};
        int choice = JOptionPane.showOptionDialog(
                null,
                root,
                "3D Deconvolution Preview",
                JOptionPane.DEFAULT_OPTION,
                JOptionPane.PLAIN_MESSAGE,
                null,
                options,
                options[0]
        );
        if (choice == 0) return Decision.RUN_FULL_BATCH;
        if (choice == 1) return Decision.RECONFIGURE;
        return Decision.CANCEL;
    }

    private static JPanel buildPreviewRoot(PreviewContent content) {
        PreviewPairPanel preview = new PreviewPairPanel(
                null,
                content.rawLabel,
                content.deconvolvedLabel,
                PreviewPairPanel.PreviewLayout.HORIZONTAL_SLIM);
        preview.setOriginal(content.rawProjection);
        preview.setAdjusted(content.deconvolvedProjection);
        preview.setAdjustedState(PreviewPairPanel.PreviewState.READY, "Preview complete.");
        preview.setDisplayControlsAvailable(true);
        // No raw/filtered source toggle: the two panes already are raw vs deconvolved.
        preview.setSourceToggleVisible(false);

        JPanel root = new JPanel(new BorderLayout(0, 8));
        root.add(preview.previewToolstrip(), BorderLayout.NORTH);
        root.add(preview, BorderLayout.CENTER);
        root.add(preview.sharedZRow(), BorderLayout.SOUTH);
        root.setPreferredSize(new Dimension(720, 520));
        return root;
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
        public final ImagePlus rawProjection;
        public final ImagePlus deconvolvedProjection;
        public final String rawLabel;
        public final String deconvolvedLabel;

        public PreviewContent(ImagePlus rawProjection,
                              ImagePlus deconvolvedProjection,
                              String rawLabel,
                              String deconvolvedLabel) {
            this.rawProjection = rawProjection;
            this.deconvolvedProjection = deconvolvedProjection;
            this.rawLabel = rawLabel == null ? "Raw" : rawLabel;
            this.deconvolvedLabel = deconvolvedLabel == null ? "Deconvolved" : deconvolvedLabel;
        }
    }
}
