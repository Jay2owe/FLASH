package flash.pipeline.stardist;

import flash.pipeline.runtime.DependencyId;
import flash.pipeline.runtime.DependencyRegistry;
import flash.pipeline.runtime.DependencySpec;
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
 * Checks that the Fiji runtime has the pinned Java-side StarDist stack.
 * Existing startup behavior stays in place for now.
 */
public class RuntimeChecker {

    private static final DependencySpec STARDIST_SPEC =
            DependencyRegistry.get(DependencyId.STARDIST_RUNTIME);

    /** Only show the startup dialog once per Fiji session. */
    private static boolean checkedThisSession = false;

    /**
     * Checks the runtime once per session from plugin startup.
     *
     * @return true if the runtime is OK (either already correct, or was
     *         repaired and user should restart), false if the user declined
     */
    public static boolean checkAndOffer() {
        return checkAndOffer(false);
    }

    /**
     * Checks the runtime and optionally forces the UI path even after the
     * startup prompt has already run once this session.
     */
    public static boolean checkAndOffer(boolean force) {
        if (checkedThisSession && !force) {
            return true;
        }
        if (!force) {
            checkedThisSession = true;
        }

        File fijiDir = getFijiDir();
        if (fijiDir == null) {
            if (force) {
                IJ.showMessage("IHF Pipeline - StarDist Runtime Check",
                        "Could not determine the Fiji.app directory.\n\n"
                                + "Open Fiji from its installed location and try again.");
                return false;
            }
            return true;
        }

        List<String> issues = check(fijiDir);
        if (issues.isEmpty()) {
            if (force) {
                IJ.showMessage("StarDist", "StarDist runtime is fully verified.");
            }
            return true;
        }

        return showRepairDialog(fijiDir, issues);
    }

    /**
     * Silent check only - returns the list of jar issues without showing UI.
     */
    public static List<String> check(File fijiDir) {
        return DependencyRegistry.checkJarRequirements(
                fijiDir,
                STARDIST_SPEC.getJarRequirements(),
                STARDIST_SPEC.getJarIgnorePrefixes());
    }

    /**
     * Downloads the pinned Java-side StarDist jars and disables wrong versions.
     *
     * @return list of actions taken
     */
    public static List<String> repair(File fijiDir) {
        return DependencyRegistry.repairJarRequirements(
                fijiDir,
                STARDIST_SPEC.getJarRequirements(),
                STARDIST_SPEC.getJarIgnorePrefixes());
    }

    private static boolean showRepairDialog(final File fijiDir, final List<String> issues) {
        final boolean[] result = { false };

        try {
            SwingUtilities.invokeAndWait(new Runnable() {
                @Override
                public void run() {
                    JDialog dialog = new JDialog(IJ.getInstance(),
                            "IHF Pipeline - StarDist Runtime Check", true);
                    dialog.setLayout(new BorderLayout(10, 10));

                    JLabel header = new JLabel(
                            "<html><b>StarDist runtime needs repair</b><br>"
                                    + "The following Java-side JARs don't match the versions required by IHF Pipeline:</html>");
                    header.setBorder(BorderFactory.createEmptyBorder(10, 10, 0, 10));
                    dialog.add(header, BorderLayout.NORTH);

                    StringBuilder sb = new StringBuilder();
                    for (String issue : issues) {
                        sb.append("  \u2022 ").append(issue).append("\n");
                    }
                    sb.append("\nAuto-Fix will download about ")
                            .append(DependencyRegistry.formatApproxSize(STARDIST_SPEC.getApproxDownloadSizeBytes()))
                            .append(" of Java-side StarDist jars and disable wrong versions.\n");
                    sb.append("The larger native TensorFlow runtime is repaired by the separate TensorFlow native row.");

                    JTextArea textArea = new JTextArea(sb.toString());
                    textArea.setEditable(false);
                    textArea.setFont(new Font("Monospaced", Font.PLAIN, 12));
                    textArea.setBackground(new Color(245, 245, 245));
                    textArea.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

                    JScrollPane scroll = new JScrollPane(textArea);
                    scroll.setPreferredSize(new Dimension(560, 230));
                    scroll.setBorder(BorderFactory.createEmptyBorder(5, 10, 5, 10));
                    dialog.add(scroll, BorderLayout.CENTER);

                    JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 8));

                    final JButton skipBtn = new JButton("Skip (StarDist won't work)");
                    skipBtn.addActionListener(e -> {
                        result[0] = false;
                        dialog.dispose();
                    });

                    final JButton fixBtn = new JButton(STARDIST_SPEC.formatButtonLabel(null));
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
                        }, "IHF-StarDist-Runtime-Repair").start();
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
            IJ.log("WARNING: RuntimeChecker dialog failed: " + e.getMessage());
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
            IJ.showMessage("IHF Pipeline - Repair Incomplete",
                    "Some repairs failed:\n\n" + sb
                            + "\nTry closing all Fiji windows first, or run the repair script:\n"
                            + "scripts/repair-fiji-runtime.ps1");
        } else {
            IJ.showMessage("IHF Pipeline - Repair Complete",
                    "Java-side StarDist jars fixed:\n\n" + sb
                            + "\nPlease restart Fiji for changes to take effect.\n"
                            + "If the TensorFlow native row is also missing, repair it before using StarDist.");
        }
    }

    public static File getFijiDir() {
        return DependencyRegistry.resolveFijiDir();
    }
}
