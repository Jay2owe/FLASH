package flash.pipeline.ui.wizard;

import javax.swing.BorderFactory;
import javax.swing.border.Border;
import java.awt.Color;
import java.awt.Font;
import java.awt.Insets;

/**
 * Shared visual constants for setup-helper wizard controls.
 */
public final class WizardTheme {

    public static final Color BACKGROUND = new Color(245, 245, 245);
    public static final Color ACTION_BLUE = new Color(25, 118, 210);
    public static final Color TAG_FOREGROUND = new Color(88, 88, 88);
    public static final Color TAG_BACKGROUND = new Color(236, 239, 241);
    public static final Color BUTTON_BACKGROUND = new Color(232, 240, 254);
    public static final Color BUTTON_FOREGROUND = new Color(25, 91, 163);
    public static final Border BUTTON_BORDER = BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(new Color(144, 164, 174), 1),
            BorderFactory.createEmptyBorder(3, 8, 3, 8));
    public static final Insets BUTTON_INSETS = new Insets(2, 8, 2, 8);
    public static final Font TAG_FONT = new Font(Font.SANS_SERIF, Font.PLAIN, 10);

    private WizardTheme() {
    }
}
