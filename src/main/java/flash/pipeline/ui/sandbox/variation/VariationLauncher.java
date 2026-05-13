package flash.pipeline.ui.sandbox.variation;

import flash.pipeline.image.dag.DagIR;
import flash.pipeline.image.variation.VariationRunResult;
import ij.ImagePlus;

import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import java.awt.Window;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.concurrent.atomic.AtomicBoolean;

public final class VariationLauncher {

    public interface SourceProvider {
        ImagePlus createSource() throws Exception;
        void close(ImagePlus image);
    }

    private VariationLauncher() {}

    public static void open(Window owner,
                            String title,
                            DagIR baseline,
                            SourceProvider sourceProvider,
                            VariationActionsBinder actions,
                            VariationSessionLog sessionLog) {
        if (baseline == null) throw new IllegalArgumentException("baseline must not be null");
        if (sourceProvider == null) throw new IllegalArgumentException("sourceProvider must not be null");
        if (actions == null) throw new IllegalArgumentException("actions must not be null");

        final ImagePlus source;
        try {
            source = sourceProvider.createSource();
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(owner,
                    "No preview image is available:\n" + ex.getMessage(),
                    "Create variations",
                    JOptionPane.WARNING_MESSAGE);
            return;
        }
        if (source == null) {
            JOptionPane.showMessageDialog(owner,
                    "No preview image is available.",
                    "Create variations",
                    JOptionPane.WARNING_MESSAGE);
            return;
        }

        final AtomicBoolean closed = new AtomicBoolean(false);
        Runnable closeSource = new Runnable() {
            @Override public void run() {
                if (closed.compareAndSet(false, true)) {
                    sourceProvider.close(source);
                }
            }
        };

        VariationChooserDialog dialog = new VariationChooserDialog(
                owner,
                baseline,
                source,
                result -> {
                    closeSource.run();
                    showResults(owner, title, result, actions, sessionLog);
                });
        dialog.addWindowListener(new WindowAdapter() {
            @Override public void windowClosed(WindowEvent e) {
                closeSource.run();
            }
        });
        dialog.setVisible(true);
    }

    public static VariantGridFrame showResults(Window owner,
                                               String title,
                                               VariationRunResult result,
                                               VariationActionsBinder actions,
                                               VariationSessionLog sessionLog) {
        if (result == null) throw new IllegalArgumentException("result must not be null");
        VariantGridFrame frame = new VariantGridFrame(
                title == null || title.trim().isEmpty() ? "Filter variations" : title,
                result.displaySource,
                result.results);
        frame.setActionListener(actions);
        frame.setSessionLog(sessionLog);
        frame.attachExporters(new MontageExporter(frame), new IjmClipboardExporter(frame));
        if (sessionLog != null) sessionLog.recordGenerate(result.results);
        frame.setLocationRelativeTo(owner);
        SwingUtilities.invokeLater(new Runnable() {
            @Override public void run() {
                frame.setVisible(true);
            }
        });
        return frame;
    }
}
