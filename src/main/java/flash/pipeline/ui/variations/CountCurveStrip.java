package flash.pipeline.ui.variations;

import flash.pipeline.ui.FlashTheme;

import javax.swing.JPanel;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Path2D;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.OptionalInt;

public class CountCurveStrip extends JPanel {

    private static final int DEFAULT_WIDTH = 760;
    private static final int DEFAULT_HEIGHT = 120;
    private static final int MINI_WIDTH = 120;
    private static final int MINI_HEIGHT = 42;
    private static final int STRIP_LEFT_PAD = 18;
    private static final int STRIP_RIGHT_PAD = 18;
    private static final int STRIP_TOP_PAD = 14;
    private static final int STRIP_BOTTOM_PAD = 26;
    private static final int MINI_PAD_X = 6;
    private static final int MINI_PAD_Y = 5;
    private static final int HIT_RADIUS = 8;

    private static final Color AXIS = new Color(0x7C, 0x86, 0x8E);
    private static final Color TICK = new Color(0x9A, 0xA4, 0xAC);
    private static final Color LABEL = new Color(0x4E, 0x5D, 0x65);
    private static final Color CURVE = new Color(0x56, 0xB4, 0xE9);
    private static final Color POINT = new Color(0xFF, 0xFF, 0xFF);
    private static final Color POINT_BORDER = new Color(0x2F, 0x3A, 0x43);
    private static final Color STAR = new Color(0xF0, 0xE4, 0x42);
    private static final Color PLATEAU_FILL = new Color(0xF0, 0xE4, 0x42, 33);
    private static final Color SELECTED_RING = new Color(0xE6, 0x9F, 0x00);

    private final List<ChangeListener> listeners = new ArrayList<ChangeListener>();
    private final boolean showAxis;
    private final boolean showLabels;
    private final Dimension preferredSize;

    private double[] xs = new double[0];
    private double[] ys = new double[0];
    private double sharedYMax = Double.NaN;
    private OptionalInt stableCountIndex = OptionalInt.empty();
    private OptionalInt selectedIndex = OptionalInt.empty();
    private int[] plateauRange;

    public CountCurveStrip(double[] xs,
                           double[] ys,
                           OptionalInt stableCountIndex,
                           int[] plateauRange) {
        this(xs, ys, stableCountIndex, plateauRange, true, true, Double.NaN,
                new Dimension(DEFAULT_WIDTH, DEFAULT_HEIGHT));
    }

    protected CountCurveStrip(double[] xs,
                              double[] ys,
                              OptionalInt stableCountIndex,
                              int[] plateauRange,
                              boolean showAxis,
                              boolean showLabels,
                              double sharedYMax,
                              Dimension preferredSize) {
        this.showAxis = showAxis;
        this.showLabels = showLabels;
        this.preferredSize = preferredSize == null
                ? new Dimension(DEFAULT_WIDTH, DEFAULT_HEIGHT)
                : new Dimension(preferredSize);
        setOpaque(false);
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        setFont(FlashTheme.mono(10f));
        replaceData(xs, ys, stableCountIndex, plateauRange, sharedYMax);
        addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                selectPoint(e);
            }
        });
    }

    public void setData(double[] newXs,
                        double[] newYs,
                        OptionalInt newStableCountIndex,
                        int[] newPlateauRange) {
        replaceData(newXs, newYs, newStableCountIndex, newPlateauRange, Double.NaN);
    }

    protected final void setDataWithSharedYMax(double[] newXs,
                                               double[] newYs,
                                               OptionalInt newStableCountIndex,
                                               int[] newPlateauRange,
                                               double newSharedYMax) {
        replaceData(newXs, newYs, newStableCountIndex, newPlateauRange, newSharedYMax);
    }

    public OptionalInt selectedIndex() {
        return selectedIndex;
    }

    public OptionalInt getSelectedIndex() {
        return selectedIndex();
    }

    public void addChangeListener(ChangeListener listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    public void removeChangeListener(ChangeListener listener) {
        listeners.remove(listener);
    }

    @Override public Dimension getPreferredSize() {
        return new Dimension(preferredSize);
    }

    @Override public Dimension getMinimumSize() {
        return new Dimension(1, Math.min(preferredSize.height, MINI_HEIGHT));
    }

    @Override public Dimension getMaximumSize() {
        return new Dimension(Integer.MAX_VALUE, preferredSize.height);
    }

    @Override protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            List<PlotPoint> points = sortedPoints();
            PlotBounds bounds = boundsFor(points);
            paintPlateauBand(g2, points, bounds);
            if (showAxis) {
                paintAxis(g2, points, bounds);
            }
            paintCurve(g2, points, bounds);
            paintPoints(g2, points, bounds);
            paintStableStar(g2, points, bounds);
        } finally {
            g2.dispose();
        }
    }

    protected final double configuredYMax() {
        return isFinite(sharedYMax) && sharedYMax > 0.0d
                ? sharedYMax
                : computedYMax(ys);
    }

    protected static Dimension miniPreferredSize() {
        return new Dimension(MINI_WIDTH, MINI_HEIGHT);
    }

    private void replaceData(double[] newXs,
                             double[] newYs,
                             OptionalInt newStableCountIndex,
                             int[] newPlateauRange,
                             double newSharedYMax) {
        xs = copy(newXs);
        ys = copy(newYs);
        stableCountIndex = newStableCountIndex == null
                ? OptionalInt.empty()
                : newStableCountIndex;
        plateauRange = copyRange(newPlateauRange);
        sharedYMax = newSharedYMax;
        selectedIndex = OptionalInt.empty();
        revalidate();
        repaint();
    }

    private void paintPlateauBand(Graphics2D g2,
                                  List<PlotPoint> points,
                                  PlotBounds bounds) {
        if (plateauRange == null || points.isEmpty()) {
            return;
        }
        PlotPoint start = pointByOriginalIndex(points, plateauRange[0]);
        PlotPoint end = pointByOriginalIndex(points, plateauRange[1]);
        if (start == null || end == null) {
            return;
        }
        int x1 = xFor(start, bounds);
        int x2 = xFor(end, bounds);
        int left = Math.min(x1, x2);
        int width = Math.max(3, Math.abs(x2 - x1));
        g2.setColor(PLATEAU_FILL);
        g2.fillRect(left, bounds.top, width, Math.max(1, bounds.bottom - bounds.top));
    }

    private void paintAxis(Graphics2D g2,
                           List<PlotPoint> points,
                           PlotBounds bounds) {
        g2.setStroke(new BasicStroke(1.5f));
        g2.setColor(AXIS);
        g2.drawLine(bounds.left, bounds.bottom, bounds.right, bounds.bottom);
        g2.setFont(getFont());
        FontMetrics metrics = g2.getFontMetrics();
        int lastLabelRight = Integer.MIN_VALUE;
        for (int i = 0; i < points.size(); i++) {
            PlotPoint point = points.get(i);
            int x = xFor(point, bounds);
            g2.setColor(TICK);
            g2.drawLine(x, bounds.bottom, x, bounds.bottom + 5);
            if (!showLabels) {
                continue;
            }
            String label = formatTick(point.x);
            int labelWidth = metrics.stringWidth(label);
            int labelLeft = x - labelWidth / 2;
            int labelRight = labelLeft + labelWidth;
            if (labelLeft > lastLabelRight + 4
                    && labelLeft >= 0
                    && labelRight <= getWidth()) {
                g2.setColor(LABEL);
                g2.drawString(label, labelLeft, bounds.bottom + 17);
                lastLabelRight = labelRight;
            }
        }
    }

    private void paintCurve(Graphics2D g2,
                            List<PlotPoint> points,
                            PlotBounds bounds) {
        if (points.isEmpty()) {
            return;
        }
        Path2D.Double path = new Path2D.Double();
        for (int i = 0; i < points.size(); i++) {
            PlotPoint point = points.get(i);
            int x = xFor(point, bounds);
            int y = yFor(point.y, bounds);
            if (i == 0) {
                path.moveTo(x, y);
            } else {
                path.lineTo(x, y);
            }
        }
        g2.setColor(CURVE);
        g2.setStroke(new BasicStroke(showAxis ? 2f : 1.6f,
                BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g2.draw(path);
    }

    private void paintPoints(Graphics2D g2,
                             List<PlotPoint> points,
                             PlotBounds bounds) {
        int radius = showAxis ? 3 : 2;
        for (int i = 0; i < points.size(); i++) {
            PlotPoint point = points.get(i);
            int x = xFor(point, bounds);
            int y = yFor(point.y, bounds);
            g2.setColor(POINT);
            g2.fillOval(x - radius, y - radius, radius * 2, radius * 2);
            g2.setColor(POINT_BORDER);
            g2.setStroke(new BasicStroke(1.5f));
            g2.drawOval(x - radius, y - radius, radius * 2, radius * 2);
            if (selectedIndex.isPresent()
                    && selectedIndex.getAsInt() == point.originalIndex) {
                g2.setColor(SELECTED_RING);
                g2.setStroke(new BasicStroke(1.5f));
                g2.drawOval(x - radius - 3, y - radius - 3,
                        radius * 2 + 6, radius * 2 + 6);
            }
        }
    }

    private void paintStableStar(Graphics2D g2,
                                 List<PlotPoint> points,
                                 PlotBounds bounds) {
        if (!stableCountIndex.isPresent()) {
            return;
        }
        PlotPoint point = pointByOriginalIndex(points, stableCountIndex.getAsInt());
        if (point == null) {
            return;
        }
        int x = xFor(point, bounds);
        int y = yFor(point.y, bounds);
        Font oldFont = g2.getFont();
        Font starFont = FlashTheme.bodyMedium().deriveFont(Font.BOLD,
                showAxis ? 18f : 14f);
        g2.setFont(starFont);
        FontMetrics metrics = g2.getFontMetrics();
        String star = "\u2605";
        int drawX = x - metrics.stringWidth(star) / 2;
        int drawY = y - (showAxis ? 8 : 5);
        g2.setColor(new Color(0, 0, 0, 120));
        g2.drawString(star, drawX + 1, drawY + 1);
        g2.setColor(STAR);
        g2.drawString(star, drawX, drawY);
        g2.setFont(oldFont);
    }

    private void selectPoint(MouseEvent event) {
        if (event == null) {
            return;
        }
        List<PlotPoint> points = sortedPoints();
        if (points.isEmpty()) {
            return;
        }
        PlotBounds bounds = boundsFor(points);
        PlotPoint nearest = null;
        double bestDistanceSq = HIT_RADIUS * HIT_RADIUS;
        for (int i = 0; i < points.size(); i++) {
            PlotPoint point = points.get(i);
            int x = xFor(point, bounds);
            int y = yFor(point.y, bounds);
            double dx = event.getX() - x;
            double dy = event.getY() - y;
            double distanceSq = dx * dx + dy * dy;
            if (distanceSq <= bestDistanceSq) {
                bestDistanceSq = distanceSq;
                nearest = point;
            }
        }
        if (nearest == null) {
            return;
        }
        selectedIndex = OptionalInt.of(nearest.originalIndex);
        repaint();
        fireChanged();
    }

    private void fireChanged() {
        ChangeEvent event = new ChangeEvent(this);
        for (int i = 0; i < listeners.size(); i++) {
            listeners.get(i).stateChanged(event);
        }
    }

    private List<PlotPoint> sortedPoints() {
        int n = Math.min(xs.length, ys.length);
        List<PlotPoint> points = new ArrayList<PlotPoint>(n);
        for (int i = 0; i < n; i++) {
            if (isFinite(xs[i]) && isFinite(ys[i])) {
                points.add(new PlotPoint(i, xs[i], ys[i]));
            }
        }
        Collections.sort(points, new Comparator<PlotPoint>() {
            @Override public int compare(PlotPoint a, PlotPoint b) {
                return Double.compare(a.x, b.x);
            }
        });
        for (int i = 0; i < points.size(); i++) {
            points.get(i).order = i;
        }
        return points;
    }

    private PlotBounds boundsFor(List<PlotPoint> points) {
        int leftPad = showAxis ? STRIP_LEFT_PAD : MINI_PAD_X;
        int rightPad = showAxis ? STRIP_RIGHT_PAD : MINI_PAD_X;
        int topPad = showAxis ? STRIP_TOP_PAD : MINI_PAD_Y;
        int bottomPad = showAxis ? STRIP_BOTTOM_PAD : MINI_PAD_Y;
        int left = Math.min(Math.max(0, leftPad), Math.max(0, getWidth() - 1));
        int right = Math.max(left + 1, getWidth() - rightPad);
        int top = Math.min(Math.max(0, topPad), Math.max(0, getHeight() - 1));
        int bottom = Math.max(top + 1, getHeight() - bottomPad);
        double minX = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < points.size(); i++) {
            PlotPoint point = points.get(i);
            if (point.x < minX) {
                minX = point.x;
            }
            if (point.x > maxX) {
                maxX = point.x;
            }
        }
        if (!isFinite(minX) || !isFinite(maxX)) {
            minX = 0.0d;
            maxX = 1.0d;
        }
        return new PlotBounds(left, right, top, bottom, minX, maxX,
                Math.max(1.0d, configuredYMax()), Math.max(1, points.size()));
    }

    private int xFor(PlotPoint point, PlotBounds bounds) {
        if (bounds.maxX > bounds.minX) {
            double fraction = (point.x - bounds.minX) / (bounds.maxX - bounds.minX);
            return bounds.left + (int) Math.round(clamp(fraction) * bounds.width());
        }
        if (bounds.pointCount <= 1) {
            return bounds.left + bounds.width() / 2;
        }
        double fraction = point.order / (double) (bounds.pointCount - 1);
        return bounds.left + (int) Math.round(fraction * bounds.width());
    }

    private int yFor(double y, PlotBounds bounds) {
        double fraction = clamp(y / bounds.maxY);
        return bounds.bottom
                - (int) Math.round(fraction * Math.max(1, bounds.height()));
    }

    private static PlotPoint pointByOriginalIndex(List<PlotPoint> points, int index) {
        for (int i = 0; i < points.size(); i++) {
            PlotPoint point = points.get(i);
            if (point.originalIndex == index) {
                return point;
            }
        }
        return null;
    }

    private static String formatTick(double value) {
        if (Math.abs(value - Math.rint(value)) < 0.0000001d
                && Math.abs(value) < 1000000000.0d) {
            return String.valueOf((long) Math.rint(value));
        }
        String text;
        double magnitude = Math.abs(value);
        if (magnitude >= 1000.0d || (magnitude > 0.0d && magnitude < 0.01d)) {
            text = String.format(Locale.ROOT, "%.2g", Double.valueOf(value));
        } else {
            text = String.format(Locale.ROOT, "%.3f", Double.valueOf(value));
        }
        return text.replaceAll("0+$", "").replaceAll("\\.$", "");
    }

    private static double computedYMax(double[] values) {
        double max = 1.0d;
        if (values == null) {
            return max;
        }
        for (int i = 0; i < values.length; i++) {
            if (isFinite(values[i]) && values[i] > max) {
                max = values[i];
            }
        }
        return max;
    }

    private static double[] copy(double[] values) {
        if (values == null) {
            return new double[0];
        }
        double[] copy = new double[values.length];
        System.arraycopy(values, 0, copy, 0, values.length);
        return copy;
    }

    private static int[] copyRange(int[] range) {
        if (range == null || range.length < 2) {
            return null;
        }
        return new int[] { range[0], range[1] };
    }

    private static boolean isFinite(double value) {
        return !Double.isNaN(value) && !Double.isInfinite(value);
    }

    private static double clamp(double value) {
        if (value < 0.0d) {
            return 0.0d;
        }
        if (value > 1.0d) {
            return 1.0d;
        }
        return value;
    }

    private static final class PlotPoint {
        final int originalIndex;
        final double x;
        final double y;
        int order;

        PlotPoint(int originalIndex, double x, double y) {
            this.originalIndex = originalIndex;
            this.x = x;
            this.y = y;
        }
    }

    private static final class PlotBounds {
        final int left;
        final int right;
        final int top;
        final int bottom;
        final double minX;
        final double maxX;
        final double maxY;
        final int pointCount;

        PlotBounds(int left,
                   int right,
                   int top,
                   int bottom,
                   double minX,
                   double maxX,
                   double maxY,
                   int pointCount) {
            this.left = left;
            this.right = right;
            this.top = top;
            this.bottom = bottom;
            this.minX = minX;
            this.maxX = maxX;
            this.maxY = maxY;
            this.pointCount = pointCount;
        }

        int width() {
            return Math.max(1, right - left);
        }

        int height() {
            return Math.max(1, bottom - top);
        }
    }
}
