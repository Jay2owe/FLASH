package flash.pipeline.ui;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;

/**
 * Shared compact help button styling for small row-level question-mark actions.
 */
public final class HelpButton {

    private static final Color BACKGROUND = new Color(232, 245, 253);
    private static final Color FOREGROUND = new Color(15, 87, 140);
    private static final Color BORDER = new Color(71, 145, 196);
    private static final Dimension SIZE = new Dimension(22, 22);

    private HelpButton() {
    }

    public static JButton question(String tooltip) {
        JButton button = new JButton("?");
        button.setToolTipText(tooltip);
        button.getAccessibleContext().setAccessibleName(tooltip);
        button.setFont(button.getFont().deriveFont(Font.BOLD, 11f));
        button.setBackground(BACKGROUND);
        button.setForeground(FOREGROUND);
        button.setOpaque(true);
        button.setContentAreaFilled(true);
        button.setFocusPainted(false);
        button.setBorder(BorderFactory.createLineBorder(BORDER));
        button.setMargin(new java.awt.Insets(0, 0, 0, 0));
        button.setMinimumSize(SIZE);
        button.setPreferredSize(SIZE);
        button.setMaximumSize(SIZE);
        return button;
    }
}
