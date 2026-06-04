package flash.pipeline.ui.variations;

import flash.pipeline.ui.variations.analysis.HistogramShapeStability;

import javax.swing.JPanel;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;

/**
 * Compact histogram-shape stability strip for filter variation grids.
 */
public final class HistogramShapeStrip extends JPanel {

    private static final Color BACKGROUND = new Color(0x1E, 0x20, 0x24);
    private static final Color BAR = new Color(0x56, 0xB4, 0xE9, 0xB8);
    private static final Color AXIS = new Color(0x8E, 0x9A, 0xA3);
    private static final Color PLATEAU = new Color(0xF0, 0xE4, 0x42, 0x33);
    private static final Color WINNER = new Color(0xF0, 0xE4, 0x42);
    private static final int DOWNSAMPLED_BINS = 32;

    private HistogramShapeStability.Result result;

    public HistogramShapeStrip() {
        setOpaque(true);
        setBackground(BACKGROUND);
        setPreferredSize(new Dimension(640, 58));
        setMinimumSize(new Dimension(120, 42));
        setVisible(false);
    }

    public void setResult(HistogramShapeStability.Result result) {
        this.result = result;
        setVisible(result != null
                && result.hasPlateau()
                && result.orderedHistograms().length > 0);
        repaint();
    }

    HistogramShapeStability.Result resultForTest() {
        return result;
    }

    @Override protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        HistogramShapeStability.Result snapshot = result;
        if (snapshot == null) {
            return;
        }
        int[][] histograms = snapshot.orderedHistograms();
        double[] values = snapshot.orderedAxisValues();
        if (histograms.length == 0) {
            return;
        }

        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            int width = Math.max(1, getWidth());
            int height = Math.max(1, getHeight());
            int left = 8;
            int right = 8;
            int top = 7;
            int labelHeight = 13;
            int plotTop = top + labelHeight;
            int plotBottom = height - 9;
            int plotHeight = Math.max(8, plotBottom - plotTop);
            int count = histograms.length;
            double tileWidth = (width - left - right) / (double) Math.max(1, count);

            paintPlateau(g2, snapshot, left, tileWidth, plotTop, plotHeight, count);
            for (int i = 0; i < count; i++) {
                paintMiniHistogram(g2, histograms[i],
                        (int) Math.round(left + i * tileWidth),
                        plotTop,
                        Math.max(2, (int) Math.floor(tileWidth) - 2),
                        plotHeight);
            }
            g2.setColor(AXIS);
            g2.drawLine(left, plotBottom, width - right, plotBottom);
            paintWinner(g2, snapshot, left, tileWidth, plotTop);
            paintLabels(g2, snapshot, values, left, tileWidth, width, top);
        } finally {
            g2.dispose();
        }
    }

    private void paintPlateau(Graphics2D g2,
                              HistogramShapeStability.Result snapshot,
                              int left,
                              double tileWidth,
                              int y,
                              int height,
                              int count) {
        int[] range = snapshot.plateauRange();
        if (range == null || range.length < 2) {
            return;
        }
        int start = clamp(Math.min(range[0], range[1]), 0, count - 1);
        int end = clamp(Math.max(range[0], range[1]), 0, count - 1);
        int x = (int) Math.round(left + start * tileWidth);
        int w = Math.max(1, (int) Math.round((end - start + 1) * tileWidth));
        g2.setColor(PLATEAU);
        g2.fillRect(x, y, w, height);
    }

    private void paintMiniHistogram(Graphics2D g2,
                                    int[] histogram,
                                    int x,
                                    int y,
                                    int width,
                                    int height) {
        int[] bins = downsample(histogram, DOWNSAMPLED_BINS);
        long max = 0L;
        for (int i = 0; i < bins.length; i++) {
            if (bins[i] > max) {
                max = bins[i];
            }
        }
        if (max <= 0L) {
            return;
        }
        double barWidth = width / (double) bins.length;
        g2.setColor(BAR);
        for (int i = 0; i < bins.length; i++) {
            int barHeight = Math.max(1,
                    (int) Math.round((bins[i] / (double) max) * height));
            int bx = x + (int) Math.floor(i * barWidth);
            int bw = Math.max(1, (int) Math.ceil(barWidth));
            g2.fillRect(bx, y + height - barHeight, bw, barHeight);
        }
    }

    private void paintWinner(Graphics2D g2,
                             HistogramShapeStability.Result snapshot,
                             int left,
                             double tileWidth,
                             int y) {
        int winner = snapshot.winnerIndex();
        if (winner < 0) {
            return;
        }
        int cx = (int) Math.round(left + (winner + 0.5d) * tileWidth);
        g2.setColor(WINNER);
        g2.fillOval(cx - 4, y - 5, 8, 8);
        g2.setColor(new Color(0x22, 0x22, 0x22));
        g2.drawString("*", cx - 3, y + 2);
    }

    private void paintLabels(Graphics2D g2,
                             HistogramShapeStability.Result snapshot,
                             double[] values,
                             int left,
                             double tileWidth,
                             int width,
                             int y) {
        String title = "Histogram shape";
        if (snapshot.primaryAxis != null) {
            title += " - " + snapshot.primaryAxis.displayLabel();
        }
        g2.setColor(new Color(0xF0, 0xF0, 0xF0));
        FontMetrics fm = g2.getFontMetrics();
        g2.drawString(title, left, y + fm.getAscent());
        if (values.length == 0) {
            return;
        }
        g2.setColor(AXIS);
        String first = format(values[0]);
        String last = format(values[values.length - 1]);
        int baseline = getHeight() - 2;
        g2.drawString(first, left, baseline);
        int lastWidth = fm.stringWidth(last);
        g2.drawString(last, Math.max(left, width - lastWidth - 8), baseline);
    }

    private static int[] downsample(int[] histogram, int bins) {
        int[] out = new int[Math.max(1, bins)];
        if (histogram == null || histogram.length == 0) {
            return out;
        }
        for (int i = 0; i < histogram.length; i++) {
            int target = (int) Math.floor(i * (out.length / (double) histogram.length));
            target = clamp(target, 0, out.length - 1);
            out[target] += Math.max(0, histogram[i]);
        }
        return out;
    }

    private static String format(double value) {
        if (Math.abs(value - Math.rint(value)) < 0.0000001d
                && Math.abs(value) < 1000000000.0d) {
            return String.valueOf((long) Math.rint(value));
        }
        String text = String.format(java.util.Locale.ROOT, "%.3f",
                Double.valueOf(value));
        return text.replaceAll("0+$", "").replaceAll("\\.$", "");
    }

    private static int clamp(int value, int min, int max) {
        if (value < min) return min;
        if (value > max) return max;
        return value;
    }
}
