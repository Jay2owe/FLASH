package flash.pipeline.ui;

import javax.swing.border.EmptyBorder;
import java.awt.Color;
import java.awt.Font;

public final class FlashTheme {

    private FlashTheme() {}

    public static final Color SURFACE = new Color(245, 245, 245);
    public static final Color SURFACE_RAISED = new Color(255, 255, 255);
    public static final Color SURFACE_MUTED = new Color(238, 238, 238);

    public static final Color BORDER = new Color(225, 228, 232);
    public static final Color BORDER_STRONG = new Color(195, 200, 205);

    public static final Color TEXT_HEADER = new Color(55, 71, 79);
    public static final Color TEXT_SUBHEADER = new Color(78, 93, 101);
    public static final Color TEXT_PRIMARY = new Color(33, 33, 33);
    public static final Color TEXT_MUTED = new Color(117, 117, 117);

    public static final Color PRIMARY_BG = new Color(235, 248, 239);
    public static final Color PRIMARY_FG = new Color(37, 103, 62);
    public static final Color PRIMARY_BORDER = new Color(111, 173, 130);

    public static final Color DANGER_BG = new Color(252, 240, 240);
    public static final Color DANGER_FG = new Color(137, 44, 44);
    public static final Color DANGER_BORDER = new Color(196, 108, 108);

    public static final int SPACE_XS = 4;
    public static final int SPACE_S = 8;
    public static final int SPACE_M = 12;
    public static final int SPACE_L = 16;
    public static final int SPACE_XL = 24;
    public static final int SPACE_XXL = 32;

    public static EmptyBorder pad(int v) {
        return new EmptyBorder(v, v, v, v);
    }

    public static EmptyBorder pad(int vertical, int horizontal) {
        return new EmptyBorder(vertical, horizontal, vertical, horizontal);
    }

    private static Font base() {
        return new Font("SansSerif", Font.PLAIN, 12);
    }

    public static Font h1() { return base().deriveFont(Font.BOLD, 16f); }
    public static Font h2() { return base().deriveFont(Font.BOLD, 13f); }
    public static Font body() { return base().deriveFont(Font.PLAIN, 12f); }
    public static Font bodyMedium() { return base().deriveFont(Font.BOLD, 12f); }
    public static Font caption() { return base().deriveFont(Font.PLAIN, 11f); }
    public static Font captionItalic() { return base().deriveFont(Font.ITALIC, 11f); }
    public static Font mono(float size) { return new Font("Monospaced", Font.PLAIN, (int) size); }
}
