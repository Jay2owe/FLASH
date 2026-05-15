package flash.pipeline.ui;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import java.awt.Dimension;
import java.awt.Font;

/**
 * Shared compact help button styling for small row-level question-mark actions.
 */
public final class HelpButton {

    private static final Dimension SIZE = FlashTheme.HELP_BUTTON_SIZE;

    private HelpButton() {
    }

    public static JButton question(String tooltip) {
        JButton button = new JButton("?");
        button.setToolTipText(tooltip);
        button.getAccessibleContext().setAccessibleName(tooltip);
        button.setFont(button.getFont().deriveFont(Font.BOLD, 11f));
        button.setBackground(FlashTheme.INFO_BG);
        button.setForeground(FlashTheme.INFO_FG);
        button.setOpaque(true);
        button.setContentAreaFilled(true);
        button.setFocusPainted(false);
        button.setBorder(BorderFactory.createLineBorder(FlashTheme.INFO_BORDER));
        button.setMargin(new java.awt.Insets(0, 0, 0, 0));
        button.setMinimumSize(SIZE);
        button.setPreferredSize(SIZE);
        button.setMaximumSize(SIZE);
        return button;
    }
}
