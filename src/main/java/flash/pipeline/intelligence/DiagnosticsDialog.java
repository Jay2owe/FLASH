package flash.pipeline.intelligence;

import ij.IJ;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSeparator;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.SwingConstants;
import javax.swing.WindowConstants;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Frame;
import java.awt.Toolkit;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;

/**
 * Swing dialog that renders a {@link DiagnosticsReport}. Runs the scan on
 * a background thread and streams section headers as each completes so the
 * user has something to look at while metadata reads finish.
 */
public final class DiagnosticsDialog {

    private static final Color BG = new Color(245, 245, 245);
    private static final Color HEADER = new Color(55, 71, 79);
    private static final Color OK_COLOR    = new Color(46, 125, 50);
    private static final Color INFO_COLOR  = new Color(60, 60, 60);
    private static final Color WARN_COLOR  = new Color(191, 106, 18);
    private static final Color ERROR_COLOR = new Color(198, 40, 40);

    private final String directory;

    public DiagnosticsDialog(String directory) {
        this.directory = directory;
    }

    /** Runs the report in the background and opens a dialog. Non-blocking. */
    public void open() {
        openInternal(false);
    }

    /** Opens the dialog and waits until the user closes it. */
    public void openBlocking() {
        openInternal(true);
    }

    private void openInternal(boolean waitForClose) {
        if (directory == null || directory.isEmpty()) {
            IJ.showMessage("Check my data", "No directory selected.");
            return;
        }
        final JDialog dialog = new JDialog((Frame) null, "Check my data", false);
        dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        final CountDownLatch closed = waitForClose ? new CountDownLatch(1) : null;
        final WindowAdapter closeListener;
        if (waitForClose) {
            closeListener = new WindowAdapter() {
                @Override public void windowClosed(WindowEvent e) {
                    closed.countDown();
                }
            };
            dialog.addWindowListener(closeListener);
        } else {
            closeListener = null;
        }

        final JPanel contentPanel = new JPanel();
        contentPanel.setLayout(new BoxLayout(contentPanel, BoxLayout.Y_AXIS));
        contentPanel.setBackground(BG);
        contentPanel.setBorder(BorderFactory.createEmptyBorder(12, 16, 12, 16));

        final JCheckBox deepScan = new JCheckBox(
                "Also check image quality (opens images; slower)");
        deepScan.setBackground(BG);
        deepScan.setAlignmentX(Component.LEFT_ALIGNMENT);
        contentPanel.add(deepScan);
        contentPanel.add(Box.createVerticalStrut(8));

        final JButton runBtn = new JButton("Run scan");
        runBtn.setAlignmentX(Component.LEFT_ALIGNMENT);
        contentPanel.add(runBtn);

        final JLabel busy = new JLabel(" ");
        busy.setForeground(HEADER);
        busy.setFont(busy.getFont().deriveFont(Font.BOLD, 13f));
        busy.setAlignmentX(Component.LEFT_ALIGNMENT);
        contentPanel.add(Box.createVerticalStrut(8));
        contentPanel.add(busy);

        JScrollPane scroll = new JScrollPane(contentPanel);
        scroll.setBorder(null);
        scroll.getViewport().setBackground(BG);

        JPanel buttonBar = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 8));
        buttonBar.setBackground(BG);
        final JButton saveBtn = new JButton("Save as text…");
        saveBtn.setEnabled(false);
        JButton closeBtn = new JButton("Close");
        saveBtn.setPreferredSize(new Dimension(140, 28));
        closeBtn.setPreferredSize(new Dimension(90, 28));
        closeBtn.addActionListener(e -> dialog.dispose());
        buttonBar.add(saveBtn);
        buttonBar.add(closeBtn);

        dialog.setLayout(new BorderLayout());
        dialog.add(scroll, BorderLayout.CENTER);
        dialog.add(buttonBar, BorderLayout.SOUTH);
        dialog.setPreferredSize(new Dimension(640, 480));
        dialog.pack();
        Dimension screen = Toolkit.getDefaultToolkit().getScreenSize();
        int maxH = (int) (screen.height * 0.85);
        if (dialog.getHeight() > maxH) dialog.setSize(dialog.getWidth(), maxH);
        dialog.setLocationRelativeTo(null);
        dialog.setVisible(true);

        runBtn.addActionListener(e -> {
            runBtn.setEnabled(false);
            deepScan.setEnabled(false);
            busy.setText("Scanning folder…");
            final DiagnosticsRunner.Options opts = new DiagnosticsRunner.Options();
            opts.runImageQuality = deepScan.isSelected();
            SwingWorker<DiagnosticsReport, Void> worker = new SwingWorker<DiagnosticsReport, Void>() {
                @Override protected DiagnosticsReport doInBackground() {
                    return DiagnosticsRunner.run(directory, opts);
                }
                @Override protected void done() {
                    busy.setText(" ");
                    try {
                        DiagnosticsReport report = get();
                        renderReport(contentPanel, report);
                        saveBtn.setEnabled(true);
                        for (java.awt.event.ActionListener al : saveBtn.getActionListeners()) {
                            saveBtn.removeActionListener(al);
                        }
                        saveBtn.addActionListener(ev -> saveAsText(dialog, report));
                    } catch (Exception ex) {
                        JLabel err = new JLabel("Scan failed: " + ex.getMessage());
                        err.setForeground(ERROR_COLOR);
                        contentPanel.add(err);
                    }
                    contentPanel.revalidate();
                    contentPanel.repaint();
                }
            };
            worker.execute();
        });

        if (waitForClose) {
            try {
                closed.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                SwingUtilities.invokeLater(dialog::dispose);
            } finally {
                dialog.removeWindowListener(closeListener);
            }
        }
    }

    private static void renderReport(JPanel panel, DiagnosticsReport report) {
        int warns = report.countAtLeast(DiagnosticsReport.Severity.WARN);
        JLabel summary = new JLabel(warns == 0
                ? "No issues detected."
                : warns + " issue" + (warns == 1 ? "" : "s") + " to review.");
        summary.setForeground(warns == 0 ? OK_COLOR : WARN_COLOR);
        summary.setFont(summary.getFont().deriveFont(Font.BOLD, 14f));
        summary.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.add(summary);
        panel.add(Box.createVerticalStrut(8));

        for (DiagnosticsReport.Section section : report.sections) {
            if (section.isEmpty()) continue;
            JLabel title = new JLabel(section.title);
            title.setForeground(HEADER);
            title.setFont(title.getFont().deriveFont(Font.BOLD, 12f));
            title.setAlignmentX(Component.LEFT_ALIGNMENT);
            panel.add(Box.createVerticalStrut(6));
            panel.add(title);

            JSeparator sep = new JSeparator(SwingConstants.HORIZONTAL);
            sep.setMaximumSize(new Dimension(Integer.MAX_VALUE, 1));
            sep.setAlignmentX(Component.LEFT_ALIGNMENT);
            panel.add(sep);
            panel.add(Box.createVerticalStrut(2));

            for (DiagnosticsReport.Finding f : section.findings) {
                JLabel line = new JLabel("  " + glyphFor(f.severity) + "  " + f.message);
                line.setForeground(colorFor(f.severity));
                line.setFont(line.getFont().deriveFont(Font.PLAIN, 12f));
                line.setAlignmentX(Component.LEFT_ALIGNMENT);
                panel.add(line);
            }
        }
    }

    private static String glyphFor(DiagnosticsReport.Severity s) {
        switch (s) {
            case OK: return "✓";
            case INFO: return "·";
            case WARN: return "!";
            case ERROR: return "×";
            default: return "·";
        }
    }

    private static Color colorFor(DiagnosticsReport.Severity s) {
        switch (s) {
            case OK: return OK_COLOR;
            case INFO: return INFO_COLOR;
            case WARN: return WARN_COLOR;
            case ERROR: return ERROR_COLOR;
            default: return INFO_COLOR;
        }
    }

    private static void saveAsText(JDialog parent, DiagnosticsReport report) {
        java.awt.FileDialog fd = new java.awt.FileDialog(parent, "Save report as text", java.awt.FileDialog.SAVE);
        fd.setFile("ihf-diagnostics.txt");
        fd.setVisible(true);
        String fileName = fd.getFile();
        String dir = fd.getDirectory();
        if (fileName == null || dir == null) return;
        File out = new File(dir, fileName);
        try (java.io.BufferedWriter w = java.nio.file.Files.newBufferedWriter(
                out.toPath(), java.nio.charset.StandardCharsets.UTF_8)) {
            w.write("FLASH — diagnostics report\n");
            w.write("Folder: " + report.directory + "\n");
            w.write("Time:   " + new java.util.Date(report.timestampMillis) + "\n\n");
            for (DiagnosticsReport.Section section : report.sections) {
                if (section.isEmpty()) continue;
                w.write("── " + section.title + " ──\n");
                for (DiagnosticsReport.Finding f : section.findings) {
                    w.write("  [" + f.severity + "] " + f.message + "\n");
                }
                w.write("\n");
            }
            IJ.log("Diagnostics report saved to " + out.getAbsolutePath());
        } catch (IOException e) {
            IJ.showMessage("Save failed", e.getMessage());
        }
    }
}
