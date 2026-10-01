package flash.pipeline.ui.main;

import flash.pipeline.ui.FlashTheme;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import java.awt.Color;
import java.awt.Cursor;

/** Small rounded-looking status button used in the main dialog's top strip. */
public final class StatusChip extends JButton {

    public enum State { NEUTRAL, OK, WARN }

    private State state = State.NEUTRAL;

    public StatusChip(String text) {
        super(text);
        setFont(FlashTheme.caption());
        setFocusPainted(false);
        setOpaque(true);
        setContentAreaFilled(true);
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        apply(State.NEUTRAL, text, null);
    }

    /** Sets the colour state, label and tooltip together. */
    public void apply(State newState, String text, String tooltip) {
        state = newState == null ? State.NEUTRAL : newState;
        String dot = state == State.NEUTRAL ? "" : "● ";
        setText(dot + (text == null ? "" : text));
        setToolTipText(tooltip);
        Color bg;
        Color fg;
        Color border;
        if (state == State.OK) {
            bg = FlashTheme.PRIMARY_BG;
            fg = FlashTheme.SUCCESS_FG;
            border = FlashTheme.PRIMARY_BORDER;
        } else if (state == State.WARN) {
            bg = FlashTheme.WARNING_BG;
            fg = FlashTheme.WARNING_FG;
            border = FlashTheme.WARNING_BORDER;
        } else {
            bg = FlashTheme.SURFACE_RAISED;
            fg = FlashTheme.TEXT_PRIMARY;
            border = FlashTheme.BORDER_STRONG;
        }
        setBackground(bg);
        setForeground(fg);
        setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(border), FlashTheme.pad(1, 7, 1, 7)));
        revalidate();
        repaint();
    }

    public State getState() {
        return state;
    }
}
