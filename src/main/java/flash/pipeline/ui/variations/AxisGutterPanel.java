package flash.pipeline.ui.variations;

import flash.pipeline.ui.FlashTheme;

import javax.swing.JPanel;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.util.ArrayList;
import java.util.List;

public final class AxisGutterPanel extends JPanel {

    public enum Mode {
        TOP,
        LEFT
    }

    public static final int TOP_HEIGHT = 42;
    public static final int LEFT_WIDTH = 92;
    public static final int DEFAULT_CELL_WIDTH = 360;
    public static final int DEFAULT_CELL_HEIGHT = 330;

    private static final int GAP = 8;
    private static final int TITLE_BASELINE = 13;
    private static final Color TITLE_COLOR = new Color(78, 93, 101);
    private static final Color VALUE_COLOR = new Color(33, 33, 33);

    private final Mode mode;
    private ParameterKey axis;
    private final List<Object> values = new ArrayList<Object>();

    public AxisGutterPanel(Mode mode, ParameterId axis, List<?> values) {
        this(mode, (ParameterKey) axis, values);
    }

    public AxisGutterPanel(Mode mode, ParameterKey axis, List<?> values) {
        if (mode == null) {
            throw new IllegalArgumentException("mode must not be null");
        }
        this.mode = mode;
        setOpaque(false);
        setAxis(axis, values);
    }

    public void setAxis(ParameterId axis, List<?> newValues) {
        setAxis((ParameterKey) axis, newValues);
    }

    public void setAxis(ParameterKey axis, List<?> newValues) {
        this.axis = axis;
        values.clear();
        if (newValues != null) {
            for (int i = 0; i < newValues.size(); i++) {
                values.add(newValues.get(i));
            }
        }
        revalidate();
        repaint();
    }

    @Override public Dimension getPreferredSize() {
        int count = Math.max(1, values.size());
        if (mode == Mode.TOP) {
            int width = count * DEFAULT_CELL_WIDTH + Math.max(0, count - 1) * GAP;
            return new Dimension(width, TOP_HEIGHT);
        }
        int height = count * DEFAULT_CELL_HEIGHT + Math.max(0, count - 1) * GAP;
        return new Dimension(LEFT_WIDTH, height);
    }

    @Override protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            if (mode == Mode.TOP) {
                paintTop(g2);
            } else {
                paintLeft(g2);
            }
        } finally {
            g2.dispose();
        }
    }

    private void paintTop(Graphics2D g2) {
        int count = Math.max(1, values.size());
        int slotWidth = slotWidth(getWidth(), count);
        int x = 0;

        g2.setFont(titleFont());
        g2.setColor(TITLE_COLOR);
        drawCentered(g2, ParameterLabels.labelFor(axis) + " ->",
                getWidth() / 2, TITLE_BASELINE);

        g2.setFont(valueFont());
        g2.setColor(VALUE_COLOR);
        FontMetrics metrics = g2.getFontMetrics();
        int baseline = TITLE_BASELINE + 20;
        for (int i = 0; i < values.size(); i++) {
            int centerX = x + slotWidth / 2;
            drawCentered(g2, valueText(values.get(i)), centerX, baseline, metrics);
            x += slotWidth + GAP;
        }
    }

    private void paintLeft(Graphics2D g2) {
        int count = Math.max(1, values.size());
        int slotHeight = slotHeight(getHeight(), count);
        int y = 0;

        g2.setFont(titleFont());
        g2.setColor(TITLE_COLOR);
        drawRotatedCentered(g2, "v " + ParameterLabels.labelFor(axis),
                13, getHeight() / 2, -Math.PI / 2.0d);

        g2.setFont(valueFont());
        g2.setColor(VALUE_COLOR);
        int centerX = Math.max(40, getWidth() / 2 + 16);
        for (int i = 0; i < values.size(); i++) {
            int centerY = y + slotHeight / 2;
            drawRotatedCentered(g2, valueText(values.get(i)),
                    centerX, centerY, -Math.PI / 2.0d);
            y += slotHeight + GAP;
        }
    }

    private static int slotWidth(int totalWidth, int count) {
        return Math.max(1, (totalWidth - Math.max(0, count - 1) * GAP) / count);
    }

    private static int slotHeight(int totalHeight, int count) {
        return Math.max(1, (totalHeight - Math.max(0, count - 1) * GAP) / count);
    }

    private static Font titleFont() {
        return FlashTheme.bodyMedium().deriveFont(11f);
    }

    private static Font valueFont() {
        return FlashTheme.mono(11f);
    }

    private static void drawCentered(Graphics2D g2, String text, int centerX, int baseline) {
        drawCentered(g2, text, centerX, baseline, g2.getFontMetrics());
    }

    private static void drawCentered(Graphics2D g2,
                                     String text,
                                     int centerX,
                                     int baseline,
                                     FontMetrics metrics) {
        String safeText = text == null ? "" : text;
        int width = metrics.stringWidth(safeText);
        g2.drawString(safeText, centerX - width / 2, baseline);
    }

    private static void drawRotatedCentered(Graphics2D g2,
                                            String text,
                                            int centerX,
                                            int centerY,
                                            double theta) {
        String safeText = text == null ? "" : text;
        FontMetrics metrics = g2.getFontMetrics();
        int width = metrics.stringWidth(safeText);
        int ascent = metrics.getAscent();
        AffineTransform old = g2.getTransform();
        g2.rotate(theta, centerX, centerY);
        g2.drawString(safeText, centerX - width / 2,
                centerY + ascent / 2 - 2);
        g2.setTransform(old);
    }

    private static String valueText(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
