package flash.pipeline.ui.variations;

import flash.pipeline.ui.FlashTheme;
import flash.pipeline.ui.variations.analysis.HistogramShapeStability;

import javax.swing.JPanel;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.util.Locale;

public final class HistogramShapeStrip extends JPanel {

    private static final int DEFAULT_WIDTH = 760;
    private static final int DEFAULT_HEIGHT = 112;
    private static final int STRIP_LEFT_PAD = 16;
    private static final int STRIP_RIGHT_PAD = 16;
    private static final int HISTOGRAM_TOP_PAD = 32;
    private static final int HISTOGRAM_BOTTOM_PAD = 24;
    private static final int TILE_GAP = 6;
    private static final int MINI_PAD_X = 5;
    private static final int MINI_PAD_Y = 5;
    private static final int DOWNSAMPLED_BARS = 32;

    private static final Color HISTOGRAM_FILL = new Color(0x56, 0xB4, 0xE9, 0xC8);
    private static final Color HISTOGRAM_RIM = new Color(0x2F, 0x3A, 0x43, 0x90);
    private static final Color LABEL = new Color(0x4E, 0x5D, 0x65);
    private static final Color STAR = new Color(0xF0, 0xE4, 0x42);
    private static final Color PLATEAU_FILL = new Color(0xF0, 0xE4, 0x42, 0x33);

    private double[] xs = new double[0];
    private int[][] histograms = new int[0][0];
    private int[] plateauRange;
    private int winnerIndex = -1;

    public HistogramShapeStrip() {
        setOpaque(false);
        setFont(FlashTheme.mono(10f));
    }

    public HistogramShapeStrip(HistogramShapeStability.Result result) {
        this();
        setData(result);
    }

    public HistogramShapeStrip(double[] xs,
                               int[][] histograms,
                               int[] plateauRange,
                               int winnerIndex) {
        this();
        setData(xs, histograms, plateauRange, winnerIndex);
    }

    public void setData(HistogramShapeStability.Result result) {
        if (result == null) {
            setData(null, null, null, -1);
            return;
        }
        setData(result.orderedAxisValues(), result.orderedHistograms(),
                result.plateauRange(), result.winnerIndex());
    }

    public void setData(double[] newXs,
                        int[][] newHistograms,
                        int[] newPlateauRange,
                        int newWinnerIndex) {
        xs = copy(newXs);
        histograms = copy(newHistograms);
        plateauRange = copyRange(newPlateauRange);
        winnerIndex = newWinnerIndex;
        revalidate();
        repaint();
    }

    @Override public Dimension getPreferredSize() {
        return new Dimension(DEFAULT_WIDTH, DEFAULT_HEIGHT);
    }

    @Override public Dimension getMinimumSize() {
        return new Dimension(1, DEFAULT_HEIGHT);
    }

    @Override public Dimension getMaximumSize() {
        return new Dimension(Integer.MAX_VALUE, DEFAULT_HEIGHT);
    }

    int winnerIndexForTest() {
        return winnerIndex;
    }

    int[] plateauRangeForTest() {
        return copyRange(plateauRange);
    }

    @Override protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            int count = Math.min(xs.length, histograms.length);
            if (count <= 0) {
                return;
            }
            paintPlateauBand(g2, count);
            paintHistograms(g2, count);
            paintWinnerStar(g2, count);
        } finally {
            g2.dispose();
        }
    }

    private void paintPlateauBand(Graphics2D g2, int count) {
        if (plateauRange == null || count <= 0) {
            return;
        }
        int start = clamp(Math.min(plateauRange[0], plateauRange[1]), 0, count - 1);
        int end = clamp(Math.max(plateauRange[0], plateauRange[1]), 0, count - 1);
        Rectangle startTile = tileBounds(start, count);
        Rectangle endTile = tileBounds(end, count);
        int left = Math.min(startTile.x, endTile.x);
        int right = Math.max(startTile.x + startTile.width,
                endTile.x + endTile.width);
        int top = Math.max(0, HISTOGRAM_TOP_PAD - 16);
        int bottom = Math.max(top + 1, getHeight() - HISTOGRAM_BOTTOM_PAD + 10);
        g2.setColor(PLATEAU_FILL);
        g2.fillRect(left, top, Math.max(1, right - left), bottom - top);
    }

    private void paintHistograms(Graphics2D g2, int count) {
        Font oldFont = g2.getFont();
        g2.setFont(getFont());
        FontMetrics metrics = g2.getFontMetrics();
        for (int i = 0; i < count; i++) {
            Rectangle tile = tileBounds(i, count);
            Rectangle plot = plotBounds(tile);
            int[] bars = downsample(histograms[i], DOWNSAMPLED_BARS);
            int max = max(bars);
            g2.setColor(HISTOGRAM_RIM);
            g2.setStroke(new BasicStroke(1f));
            g2.drawRect(plot.x, plot.y, Math.max(1, plot.width),
                    Math.max(1, plot.height));
            g2.setColor(HISTOGRAM_FILL);
            int barWidth = Math.max(1,
                    (int) Math.ceil(plot.width / (double) bars.length));
            for (int b = 0; b < bars.length; b++) {
                double fraction = max <= 0 ? 0.0d : bars[b] / (double) max;
                int height = Math.max(1,
                        (int) Math.round(fraction * Math.max(1, plot.height - 1)));
                int x = plot.x + Math.min(plot.width - 1, b * barWidth);
                int y = plot.y + plot.height - height;
                int width = Math.max(1,
                        Math.min(barWidth, plot.x + plot.width - x));
                g2.fillRect(x, y, width, height);
            }
            String label = formatTick(xs[i]);
            int labelX = tile.x + tile.width / 2 - metrics.stringWidth(label) / 2;
            int labelY = Math.min(getHeight() - 5, plot.y + plot.height + 15);
            g2.setColor(LABEL);
            g2.drawString(label, labelX, labelY);
        }
        g2.setFont(oldFont);
    }

    private void paintWinnerStar(Graphics2D g2, int count) {
        if (winnerIndex < 0 || winnerIndex >= count) {
            return;
        }
        Rectangle tile = tileBounds(winnerIndex, count);
        String star = "\u2605";
        Font oldFont = g2.getFont();
        Font font = FlashTheme.bodyMedium().deriveFont(Font.BOLD, 18f);
        g2.setFont(font);
        FontMetrics metrics = g2.getFontMetrics();
        int x = tile.x + tile.width / 2 - metrics.stringWidth(star) / 2;
        int y = Math.max(metrics.getAscent(), HISTOGRAM_TOP_PAD - 12);
        g2.setColor(new Color(0, 0, 0, 130));
        g2.drawString(star, x + 1, y + 1);
        g2.setColor(STAR);
        g2.drawString(star, x, y);
        g2.setFont(oldFont);
    }

    private Rectangle tileBounds(int index, int count) {
        int left = Math.min(Math.max(0, STRIP_LEFT_PAD), Math.max(0, getWidth() - 1));
        int right = Math.max(left + 1, getWidth() - STRIP_RIGHT_PAD);
        int available = Math.max(1, right - left - TILE_GAP * Math.max(0, count - 1));
        int baseWidth = Math.max(1, available / Math.max(1, count));
        int remainder = Math.max(0, available - baseWidth * count);
        int x = left;
        for (int i = 0; i < index; i++) {
            x += baseWidth + (i < remainder ? 1 : 0) + TILE_GAP;
        }
        int width = baseWidth + (index < remainder ? 1 : 0);
        int top = Math.min(Math.max(0, HISTOGRAM_TOP_PAD),
                Math.max(0, getHeight() - 1));
        int bottom = Math.max(top + 1, getHeight() - HISTOGRAM_BOTTOM_PAD);
        return new Rectangle(x, top, width, bottom - top);
    }

    private static Rectangle plotBounds(Rectangle tile) {
        int x = tile.x + Math.min(MINI_PAD_X, Math.max(0, tile.width / 3));
        int y = tile.y + MINI_PAD_Y;
        int width = Math.max(1, tile.width - MINI_PAD_X * 2);
        int height = Math.max(1, tile.height - MINI_PAD_Y * 2);
        return new Rectangle(x, y, width, height);
    }

    private static int[] downsample(int[] histogram, int targetBins) {
        int bins = Math.max(1, targetBins);
        int[] out = new int[bins];
        if (histogram == null || histogram.length == 0) {
            return out;
        }
        for (int i = 0; i < histogram.length; i++) {
            int target = (int) Math.floor(i * bins / (double) histogram.length);
            if (target < 0) {
                target = 0;
            } else if (target >= bins) {
                target = bins - 1;
            }
            if (histogram[i] > 0) {
                out[target] += histogram[i];
            }
        }
        return out;
    }

    private static int max(int[] values) {
        int max = 0;
        if (values == null) {
            return max;
        }
        for (int i = 0; i < values.length; i++) {
            if (values[i] > max) {
                max = values[i];
            }
        }
        return max;
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

    private static double[] copy(double[] values) {
        if (values == null) {
            return new double[0];
        }
        double[] copy = new double[values.length];
        System.arraycopy(values, 0, copy, 0, values.length);
        return copy;
    }

    private static int[][] copy(int[][] values) {
        if (values == null) {
            return new int[0][0];
        }
        int[][] copy = new int[values.length][];
        for (int i = 0; i < values.length; i++) {
            copy[i] = values[i] == null ? new int[0] : values[i].clone();
        }
        return copy;
    }

    private static int[] copyRange(int[] range) {
        if (range == null || range.length < 2) {
            return null;
        }
        return new int[] { range[0], range[1] };
    }

    private static int clamp(int value, int min, int max) {
        if (value < min) {
            return min;
        }
        if (value > max) {
            return max;
        }
        return value;
    }
}
