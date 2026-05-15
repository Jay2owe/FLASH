package flash.pipeline.ui.config;

import flash.pipeline.ui.FlashTheme;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dialog;
import java.awt.GraphicsEnvironment;
import java.awt.Window;

/**
 * Small modal popover that asks the user for a preset name. Returns the
 * trimmed name on Save, or {@code null} if the user cancelled, closed the
 * dialog, or the runtime is headless.
 */
public final class SaveAsPresetPopover {

    private SaveAsPresetPopover() {}

    public static String prompt(Window owner, String suggestedName) {
        if (GraphicsEnvironment.isHeadless()) return null;

        final JDialog dialog = new JDialog(owner, "Save as preset",
                Dialog.ModalityType.APPLICATION_MODAL);
        final String[] result = new String[]{null};

        JPanel content = new JPanel(new BorderLayout(8, 8));
        content.setBackground(FlashTheme.SURFACE);
        content.setBorder(FlashTheme.pad(10, 12, 10, 12));

        content.add(new JLabel("Preset name:"), BorderLayout.NORTH);

        final JTextField field = new JTextField(
                suggestedName == null ? "" : suggestedName, 24);
        content.add(field, BorderLayout.CENTER);

        JPanel buttons = new JPanel();
        buttons.setLayout(new BoxLayout(buttons, BoxLayout.X_AXIS));
        buttons.add(Box.createHorizontalGlue());
        JButton cancel = new JButton("Cancel");
        JButton save = new JButton("Save");
        styleActionButton(cancel, FlashTheme.DANGER_BG, FlashTheme.DANGER_FG, FlashTheme.DANGER_BORDER);
        styleActionButton(save, FlashTheme.PRIMARY_BG, FlashTheme.PRIMARY_FG, FlashTheme.PRIMARY_BORDER);
        buttons.add(cancel);
        buttons.add(Box.createHorizontalStrut(8));
        buttons.add(save);
        content.add(buttons, BorderLayout.SOUTH);

        cancel.addActionListener(e -> dialog.dispose());
        save.addActionListener(e -> {
            String text = field.getText();
            result[0] = text == null ? "" : text.trim();
            dialog.dispose();
        });

        dialog.getRootPane().setDefaultButton(save);
        dialog.setContentPane(content);
        dialog.pack();
        dialog.setLocationRelativeTo(owner);
        SwingUtilities.invokeLater(field::selectAll);
        dialog.setVisible(true);

        return result[0] == null || result[0].isEmpty() ? null : result[0];
    }

    private static void styleActionButton(JButton button, Color background, Color foreground, Color border) {
        button.setBackground(background);
        button.setForeground(foreground);
        button.setOpaque(true);
        button.setContentAreaFilled(true);
        button.setFocusPainted(false);
        button.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(border),
                FlashTheme.pad(3, 10, 3, 10)));
    }
}
