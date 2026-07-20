package flash.pipeline.ui;

import javax.swing.JComponent;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;

/**
 * Shared selection-dot painter for clickable choice cards.
 */
public final class ChoiceRadioIndicator {
    public static final int SIZE = 18;
    private static final int DOT_DIAMETER = 14;
    private static final int DOT_OFFSET = (SIZE - DOT_DIAMETER) / 2;
    private static final int VISUAL_INSET = 10;
    private static final int COMPONENT_INSET = VISUAL_INSET - DOT_OFFSET;

    private ChoiceRadioIndicator() {}

    public static Dimension reservedSize() {
        return new Dimension(SIZE, SIZE);
    }

    public static void paintTopLeft(JComponent host, Graphics g,
                                    boolean selected, boolean enabled) {
        if (host == null || g == null) {
            return;
        }
        paint(g, COMPONENT_INSET, COMPONENT_INSET, selected, enabled);
    }

    /** Paints the dot at the left edge, vertically centred in the host's height. */
    public static void paintLeftCentered(JComponent host, Graphics g,
                                         boolean selected, boolean enabled) {
        if (host == null || g == null) {
            return;
        }
        int dotTop = (host.getHeight() - DOT_DIAMETER) / 2;
        paint(g, COMPONENT_INSET, dotTop - DOT_OFFSET, selected, enabled);
    }

    /**
     * Multi-select variant: a rounded checkbox in the same top-left slot as
     * {@link #paintTopLeft}. Filled with a white tick when selected, hollow
     * outline otherwise.
     */
    public static void paintCheckTopLeft(JComponent host, Graphics g,
                                         boolean selected, boolean enabled) {
        if (host == null || g == null) {
            return;
        }
        paintCheck(g, COMPONENT_INSET, COMPONENT_INSET, selected, enabled);
    }

    private static void paintCheck(Graphics g, int x, int y,
                                   boolean selected, boolean enabled) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                RenderingHints.VALUE_ANTIALIAS_ON);
        int boxX = x + DOT_OFFSET;
        int boxY = y + DOT_OFFSET;
        int side = DOT_DIAMETER;
        int arc = 4;
        if (!enabled) {
            g2.setColor(FlashTheme.BORDER_STRONG);
            g2.setStroke(new BasicStroke(1.4f));
            g2.drawRoundRect(boxX, boxY, side - 1, side - 1, arc, arc);
        } else if (selected) {
            g2.setColor(FlashTheme.SELECTION_BORDER);
            g2.fillRoundRect(boxX, boxY, side, side, arc, arc);
            g2.setColor(Color.WHITE);
            g2.setStroke(new BasicStroke(1.8f));
            g2.drawLine(boxX + 3, boxY + side / 2, boxX + side / 2 - 1, boxY + side - 4);
            g2.drawLine(boxX + side / 2 - 1, boxY + side - 4, boxX + side - 3, boxY + 3);
        } else {
            g2.setColor(FlashTheme.BORDER_STRONG);
            g2.setStroke(new BasicStroke(1.6f));
            g2.drawRoundRect(boxX, boxY, side - 1, side - 1, arc, arc);
        }
        g2.dispose();
    }

    private static void paint(Graphics g, int x, int y,
                              boolean selected, boolean enabled) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                RenderingHints.VALUE_ANTIALIAS_ON);
        int dotX = x + DOT_OFFSET;
        int dotY = y + DOT_OFFSET;
        if (!enabled) {
            g2.setColor(FlashTheme.BORDER_STRONG);
            g2.setStroke(new BasicStroke(1.4f));
            g2.drawOval(dotX, dotY, DOT_DIAMETER - 1, DOT_DIAMETER - 1);
        } else if (selected) {
            g2.setColor(FlashTheme.SELECTION_BORDER);
            g2.fillOval(dotX, dotY, DOT_DIAMETER, DOT_DIAMETER);
            g2.setColor(Color.WHITE);
            g2.fillOval(dotX + 4, dotY + 4,
                    DOT_DIAMETER - 8, DOT_DIAMETER - 8);
        } else {
            g2.setColor(FlashTheme.BORDER_STRONG);
            g2.setStroke(new BasicStroke(1.6f));
            g2.drawOval(dotX, dotY, DOT_DIAMETER - 1, DOT_DIAMETER - 1);
        }
        g2.dispose();
    }
}
