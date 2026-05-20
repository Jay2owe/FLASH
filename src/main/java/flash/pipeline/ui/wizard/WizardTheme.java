package flash.pipeline.ui.wizard;

import flash.pipeline.ui.FlashTheme;

import javax.swing.BorderFactory;
import javax.swing.border.Border;
import java.awt.Color;
import java.awt.Font;
import java.awt.Insets;

/**
 * Shared visual constants for setup-helper wizard controls.
 */
public final class WizardTheme {

    public static final Color BACKGROUND = FlashTheme.SURFACE;
    public static final Color ACTION_BLUE = new Color(25, 118, 210);
    public static final Color TAG_FOREGROUND = new Color(88, 88, 88);
    public static final Color TAG_BACKGROUND = new Color(236, 239, 241);
    public static final Color BUTTON_BACKGROUND = FlashTheme.INFO_BG;
    public static final Color BUTTON_FOREGROUND = FlashTheme.INFO_FG;
    public static final Border BUTTON_BORDER = BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(FlashTheme.INFO_BORDER, 1),
            FlashTheme.pad(3, 8, 3, 8));
    public static final Insets BUTTON_INSETS = new Insets(2, 8, 2, 8);
    public static final Font TAG_FONT = FlashTheme.caption().deriveFont(10f);

    private WizardTheme() {
    }
}
