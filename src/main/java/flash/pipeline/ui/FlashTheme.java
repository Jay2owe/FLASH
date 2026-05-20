package flash.pipeline.ui;

import javax.swing.border.EmptyBorder;
import java.awt.Color;
import java.awt.Dimension;
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
    public static final Color TEXT_HELP = new Color(90, 90, 90);
    public static final Color TEXT_DISABLED = new Color(102, 102, 102);
    public static final Color TEXT_ON_DARK = Color.WHITE;

    public static final Color PRIMARY_BG = new Color(235, 248, 239);
    public static final Color PRIMARY_FG = new Color(37, 103, 62);
    public static final Color PRIMARY_BORDER = new Color(111, 173, 130);

    public static final Color DANGER_BG = new Color(252, 240, 240);
    public static final Color DANGER_FG = new Color(137, 44, 44);
    public static final Color DANGER_BORDER = new Color(196, 108, 108);

    public static final Color INFO_BG = new Color(232, 245, 253);
    public static final Color INFO_FG = new Color(15, 87, 140);
    public static final Color INFO_BORDER = new Color(71, 145, 196);

    public static final Color WARNING_BG = new Color(255, 244, 204);
    public static final Color WARNING_FG = new Color(90, 60, 0);
    public static final Color WARNING_BORDER = new Color(220, 200, 130);

    public static final Color FIELD_ATTENTION_BG = new Color(255, 246, 170);
    public static final Color TABLE_REQUIRED_BG = DANGER_BG;

    public static final Color SUCCESS_FG = new Color(40, 110, 70);
    public static final Color LINK_FG = new Color(50, 110, 200);

    public static final Color STAGE_ACTIVE_BG = new Color(222, 239, 231);

    public static final Color TILE_BG = new Color(250, 250, 250);
    public static final Color TILE_HOVER_BG = new Color(240, 245, 252);
    public static final Color TILE_BORDER = new Color(180, 180, 180);
    public static final Color SELECTION_BORDER = new Color(70, 120, 200);

    public static final Color TOGGLE_ON = new Color(76, 175, 80);
    public static final Color TOGGLE_OFF = new Color(189, 189, 189);
    public static final Color TOGGLE_DISABLED = new Color(220, 220, 220);
    public static final Color TOGGLE_THUMB = Color.WHITE;
    public static final Color TOGGLE_THUMB_SHADOW = new Color(0, 0, 0, 40);

    public static final int SPACE_XS = 4;
    public static final int SPACE_S = 8;
    public static final int SPACE_M = 12;
    public static final int SPACE_L = 16;
    public static final int SPACE_XL = 24;
    public static final int SPACE_XXL = 32;

    public static final int ICON_ACTION_SIZE = 14;
    public static final int ICON_SECTION_SIZE = 18;
    public static final int ICON_PHASE_SIZE = 13;
    public static final int ICON_SMALL_SIZE = 12;
    private static final int HELP_BUTTON_SIDE = 22;

    public static EmptyBorder pad(int v) {
        return new EmptyBorder(v, v, v, v);
    }

    public static EmptyBorder pad(int vertical, int horizontal) {
        return new EmptyBorder(vertical, horizontal, vertical, horizontal);
    }

    public static EmptyBorder pad(int top, int left, int bottom, int right) {
        return new EmptyBorder(top, left, bottom, right);
    }

    public static Dimension helpButtonSize() {
        return new Dimension(HELP_BUTTON_SIDE, HELP_BUTTON_SIDE);
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
