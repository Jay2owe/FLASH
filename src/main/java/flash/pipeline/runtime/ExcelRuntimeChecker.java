package flash.pipeline.runtime;

import ij.IJ;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.io.File;
import java.util.List;

/**
 * Checks and repairs the Apache POI runtime required for Excel export.
 * This remains an optional dependency exposed from the Dependencies dialog.
 */
public final class ExcelRuntimeChecker {

    private static final DependencySpec EXCEL_SPEC =
            DependencyRegistry.get(DependencyId.APACHE_POI_RUNTIME);

    private ExcelRuntimeChecker() {}

    public static boolean checkAndOffer() {
        File fijiDir = getFijiDir();
        if (fijiDir == null) {
            IJ.showMessage("IHF Pipeline - Excel Runtime Check",
                    "Could not determine the Fiji.app directory.\n\n"
                            + "Open Fiji from its installed location, or run:\n"
                            + "scripts\\repair-fiji-runtime.ps1");
            return false;
        }

        List<String> issues = check(fijiDir);
        if (issues.isEmpty()) {
            IJ.showMessage("IHF Pipeline - Excel Runtime Check",
                    "Excel runtime is fully verified.");
            return true;
        }

        return showRepairDialog(fijiDir, issues);
    }

    public static List<String> check(File fijiDir) {
        return DependencyRegistry.checkJarRequirements(
                fijiDir,
                EXCEL_SPEC.getJarRequirements(),
                EXCEL_SPEC.getJarIgnorePrefixes());
    }

    public static List<String> repair(File fijiDir) {
        return DependencyRegistry.repairJarRequirements(
                fijiDir,
                EXCEL_SPEC.getJarRequirements(),
                EXCEL_SPEC.getJarIgnorePrefixes());
    }

    private static boolean showRepairDialog(final File fijiDir, final List<String> issues) {
        final boolean[] result = { false };

        try {
            SwingUtilities.invokeAndWait(new Runnable() {
                @Override
                public void run() {
                    JDialog dialog = new JDialog(IJ.getInstance(),
                            "IHF Pipeline - Excel Runtime Check", true);
                    dialog.setLayout(new BorderLayout(10, 10));

                    JLabel header = new JLabel(
                            "<html><b>Excel runtime needs repair</b><br>"
                                    + "The following JARs are missing or mismatched for Excel export:</html>");
                    header.setBorder(BorderFactory.createEmptyBorder(10, 10, 0, 10));
                    dialog.add(header, BorderLayout.NORTH);

                    StringBuilder sb = new StringBuilder();
                    for (String issue : issues) {
                        sb.append("  - ").append(issue).append("\n");
                    }
                    sb.append("\nAuto-Fix will download about ")
                            .append(DependencyRegistry.formatApproxSize(EXCEL_SPEC.getApproxDownloadSizeBytes()))
                            .append(" into Fiji.app/jars/\n");
                    sb.append("and disable conflicting versions when needed. Restart Fiji afterwards.");

                    JTextArea textArea = new JTextArea(sb.toString());
                    textArea.setEditable(false);
                    textArea.setFont(new Font("Monospaced", Font.PLAIN, 12));
                    textArea.setBackground(new Color(245, 245, 245));
                    textArea.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

                    JScrollPane scroll = new JScrollPane(textArea);
                    scroll.setPreferredSize(new Dimension(560, 220));
                    scroll.setBorder(BorderFactory.createEmptyBorder(5, 10, 5, 10));
                    dialog.add(scroll, BorderLayout.CENTER);

                    JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 8));

                    final JButton skipBtn = new JButton("Skip (Excel won't work)");
                    skipBtn.addActionListener(e -> {
                        result[0] = false;
                        dialog.dispose();
                    });

                    final JButton fixBtn = new JButton(EXCEL_SPEC.formatButtonLabel(null));
                    fixBtn.addActionListener(e -> {
                        fixBtn.setEnabled(false);
                        fixBtn.setText("Fixing...");
                        skipBtn.setEnabled(false);

                        new Thread(new Runnable() {
                            @Override
                            public void run() {
                                final List<String> actions = repair(fijiDir);
                                SwingUtilities.invokeLater(new Runnable() {
                                    @Override
                                    public void run() {
                                        showRepairResultAndClose(dialog, actions);
                                        result[0] = true;
                                    }
                                });
                            }
                        }, "IHF-Excel-Runtime-Repair").start();
                    });

                    buttons.add(skipBtn);
                    buttons.add(fixBtn);
                    dialog.add(buttons, BorderLayout.SOUTH);

                    dialog.pack();
                    dialog.setLocationRelativeTo(IJ.getInstance());
                    dialog.setVisible(true);
                }
            });
        } catch (Exception e) {
            IJ.log("WARNING: ExcelRuntimeChecker dialog failed: " + e.getMessage());
        }

        return result[0];
    }

    private static void showRepairResultAndClose(JDialog parent, List<String> actions) {
        parent.dispose();

        boolean hadFailure = false;
        StringBuilder sb = new StringBuilder();
        for (String action : actions) {
            sb.append(action).append("\n");
            if (action.startsWith("FAILED")) {
                hadFailure = true;
            }
        }

        if (hadFailure) {
            IJ.showMessage("IHF Pipeline - Excel Repair Incomplete",
                    "Some repairs failed:\n\n" + sb
                            + "\nTry closing Fiji first, or run:\n"
                            + "scripts/repair-fiji-runtime.ps1");
        } else {
            IJ.showMessage("IHF Pipeline - Excel Repair Complete",
                    "Excel runtime repaired:\n\n" + sb
                            + "\nPlease restart Fiji for changes to take effect.\n"
                            + "Excel Summary Export will work after restart.");
        }
    }

    public static File getFijiDir() {
        return DependencyRegistry.resolveFijiDir();
    }
}
