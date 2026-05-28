package flash.pipeline.ui;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JRootPane;
import javax.swing.KeyStroke;
import javax.swing.SwingConstants;
import javax.swing.WindowConstants;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dialog;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Frame;
import java.awt.GraphicsEnvironment;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.List;

public final class CancelConfirmationDialog {

    public enum Choice { SAVE_AND_EXIT, KEEP_WORKING, DISCARD_AND_EXIT }

    private static final String TITLE = "Cancel Set Up Configuration?";

    private CancelConfirmationDialog() {
    }

    public static Choice show(Window owner, String stepLabel,
                              List<String> progressLines, String draftPath) {
        if (GraphicsEnvironment.isHeadless()) {
            return Choice.DISCARD_AND_EXIT;
        }

        final Choice[] choice = new Choice[]{Choice.KEEP_WORKING};
        final JDialog dialog = owner == null
                ? new JDialog((Frame) null, TITLE, true)
                : new JDialog(owner, TITLE, Dialog.ModalityType.APPLICATION_MODAL);
        dialog.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        dialog.getContentPane().setLayout(new BorderLayout());
        dialog.getContentPane().setBackground(FlashTheme.SURFACE);

        JPanel body = new JPanel();
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
        body.setBackground(FlashTheme.SURFACE);
        body.setBorder(FlashTheme.pad(16, 18, 12, 18));
        dialog.getContentPane().add(body, BorderLayout.CENTER);

        JLabel title = new JLabel(TITLE);
        title.setFont(FlashTheme.h1());
        title.setForeground(FlashTheme.TEXT_HEADER);
        title.setAlignmentX(Component.LEFT_ALIGNMENT);
        body.add(title);
        body.add(Box.createVerticalStrut(10));

        if (stepLabel != null && !stepLabel.trim().isEmpty()) {
            JLabel step = new JLabel(stepLabel);
            step.setFont(FlashTheme.bodyMedium());
            step.setForeground(FlashTheme.TEXT_PRIMARY);
            step.setAlignmentX(Component.LEFT_ALIGNMENT);
            body.add(step);
            body.add(Box.createVerticalStrut(8));
        }

        if (progressLines != null && !progressLines.isEmpty()) {
            JPanel progress = new JPanel();
            progress.setLayout(new BoxLayout(progress, BoxLayout.Y_AXIS));
            progress.setOpaque(false);
            progress.setAlignmentX(Component.LEFT_ALIGNMENT);
            for (String line : progressLines) {
                JLabel item = new JLabel("  " + (line == null ? "" : line));
                item.setFont(FlashTheme.body());
                item.setForeground(FlashTheme.TEXT_PRIMARY);
                item.setAlignmentX(Component.LEFT_ALIGNMENT);
                progress.add(item);
                progress.add(Box.createVerticalStrut(2));
            }
            body.add(progress);
            body.add(Box.createVerticalStrut(8));
        }

        if (draftPath != null && !draftPath.trim().isEmpty()) {
            JLabel savedTo = new JLabel("<html><body width='420'>Saved to: "
                    + escapeHtml(draftPath) + "</body></html>");
            savedTo.setFont(FlashTheme.caption());
            savedTo.setForeground(FlashTheme.TEXT_MUTED);
            savedTo.setAlignmentX(Component.LEFT_ALIGNMENT);
            body.add(savedTo);
        }

        JPanel footer = new JPanel(new BorderLayout());
        footer.setBackground(FlashTheme.SURFACE);
        footer.setBorder(FlashTheme.pad(4, 12, 12, 12));

        JButton discard = new JButton("Discard & Exit");
        JButton keepWorking = new JButton("Keep Working");
        JButton save = new JButton("Save & Exit");
        styleActionButton(discard, FlashTheme.DANGER_BG, FlashTheme.DANGER_FG, FlashTheme.DANGER_BORDER);
        styleActionButton(save, FlashTheme.PRIMARY_BG, FlashTheme.PRIMARY_FG, FlashTheme.PRIMARY_BORDER);

        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        left.setOpaque(false);
        left.add(discard);
        footer.add(left, BorderLayout.WEST);

        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        right.setOpaque(false);
        right.add(keepWorking);
        right.add(save);
        footer.add(right, BorderLayout.EAST);
        dialog.getContentPane().add(footer, BorderLayout.SOUTH);

        discard.addActionListener(e -> close(dialog, choice, Choice.DISCARD_AND_EXIT));
        keepWorking.addActionListener(e -> close(dialog, choice, Choice.KEEP_WORKING));
        save.addActionListener(e -> close(dialog, choice, Choice.SAVE_AND_EXIT));
        dialog.addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) {
                close(dialog, choice, Choice.KEEP_WORKING);
            }
        });

        JRootPane root = dialog.getRootPane();
        root.setDefaultButton(keepWorking);
        root.getInputMap(JRootPane.WHEN_IN_FOCUSED_WINDOW).put(
                KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "keepWorking");
        root.getActionMap().put("keepWorking", new javax.swing.AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) {
                close(dialog, choice, Choice.KEEP_WORKING);
            }
        });

        dialog.pack();
        dialog.setMinimumSize(new Dimension(Math.max(420, dialog.getPreferredSize().width),
                dialog.getPreferredSize().height));
        dialog.setLocationRelativeTo(owner);
        keepWorking.requestFocusInWindow();
        dialog.setVisible(true);
        return choice[0];
    }

    private static void close(JDialog dialog, Choice[] choice, Choice selected) {
        choice[0] = selected;
        dialog.dispose();
    }

    private static void styleActionButton(JButton button, Color background, Color foreground, Color border) {
        button.setBackground(background);
        button.setForeground(foreground);
        button.setOpaque(true);
        button.setContentAreaFilled(true);
        button.setFocusPainted(false);
        button.setHorizontalAlignment(SwingConstants.CENTER);
        button.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(border),
                FlashTheme.pad(3, 10, 3, 10)));
    }

    private static String escapeHtml(String text) {
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }
}
