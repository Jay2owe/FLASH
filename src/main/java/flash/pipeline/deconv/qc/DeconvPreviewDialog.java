package flash.pipeline.deconv.qc;

import ij.IJ;
import ij.ImagePlus;
import ij.gui.ImageCanvas;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.GraphicsEnvironment;
import java.awt.GridLayout;

/**
 * Modal RAW-vs-DECONV preview shown before a full deconvolution batch starts.
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

        JPanel previews = new JPanel(new GridLayout(1, 2, 12, 0));
        previews.add(buildPreviewPanel(content.rawProjection, content.rawLabel));
        previews.add(buildPreviewPanel(content.deconvolvedProjection, content.deconvolvedLabel));

        JPanel root = new JPanel(new BorderLayout(0, 10));
        root.add(previews, BorderLayout.CENTER);

        Object[] options = {"Run full batch", "Reconfigure", "Cancel"};
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

    private static JPanel buildPreviewPanel(ImagePlus image, String label) {
        JPanel panel = new JPanel(new BorderLayout(0, 6));
        panel.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));

        JLabel text = new JLabel(label == null ? "" : label, SwingConstants.CENTER);
        panel.add(text, BorderLayout.NORTH);

        if (image != null) {
            ImageCanvas canvas = new ImageCanvas(image);
            canvas.setSize(image.getWidth(), image.getHeight());
            panel.add(wrapCanvas(canvas), BorderLayout.CENTER);
        }
        return panel;
    }

    private static Component wrapCanvas(ImageCanvas canvas) {
        JPanel wrapper = new JPanel();
        wrapper.setLayout(new BoxLayout(wrapper, BoxLayout.Y_AXIS));
        wrapper.add(canvas);
        return wrapper;
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
