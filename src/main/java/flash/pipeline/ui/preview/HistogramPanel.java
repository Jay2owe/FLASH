package flash.pipeline.ui.preview;

import ij.ImagePlus;
import ij.process.ImageProcessor;

import javax.swing.BorderFactory;
import javax.swing.JPanel;
import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.util.Arrays;

public final class HistogramPanel extends JPanel {

    public static final int DEFAULT_BIN_COUNT = 256;

    private static final Color BACKGROUND = new Color(250, 250, 250);
    private static final Color AXIS = new Color(145, 145, 145);
    private static final Color BAR = new Color(95, 95, 95);
    private static final Color OVERLAY = new Color(210, 35, 35);

    private Histogram histogram = Histogram.empty(DEFAULT_BIN_COUNT);
    private double lowerMarker = Double.NaN;
    private double upperMarker = Double.NaN;

    public HistogramPanel() {
        setBorder(BorderFactory.createLineBorder(new Color(180, 180, 180)));
        setBackground(BACKGROUND);
        setOpaque(true);
        setMinimumSize(new Dimension(220, 80));
        setPreferredSize(new Dimension(320, 110));
    }

    public void setImage(ImagePlus image) {
        setHistogram(calculateHistogram(image, DEFAULT_BIN_COUNT));
    }

    public void setMarkers(double lower, double upper) {
        this.lowerMarker = lower;
        this.upperMarker = upper;
        repaint();
    }

    void setHistogram(Histogram histogram) {
        this.histogram = histogram == null ? Histogram.empty(DEFAULT_BIN_COUNT) : histogram;
        repaint();
    }

    Histogram histogramForTest() {
        return histogram;
    }

    public static Histogram calculateHistogram(ImagePlus image, int binCount) {
        if (image == null || image.getStack() == null || image.getStackSize() < 1) {
            return Histogram.empty(binCount);
        }
        return calculateHistogram(image.getProcessor(), binCount);
    }

    public static Histogram calculateHistogram(ImageProcessor processor, int binCount) {
        int safeBinCount = Math.max(1, binCount);
        if (processor == null || processor.getWidth() < 1 || processor.getHeight() < 1) {
            return Histogram.empty(safeBinCount);
        }

        double minimum = Double.POSITIVE_INFINITY;
        double maximum = Double.NEGATIVE_INFINITY;
        int finitePixelCount = 0;
        int width = processor.getWidth();
        int height = processor.getHeight();

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                double value = processor.getPixelValue(x, y);
                if (!Double.isFinite(value)) continue;
                if (value < minimum) minimum = value;
                if (value > maximum) maximum = value;
                finitePixelCount++;
            }
        }

        if (finitePixelCount == 0) {
            return Histogram.empty(safeBinCount);
        }

        if (!(maximum > minimum)) {
            maximum = minimum + 1.0;
        }

        int[] bins = new int[safeBinCount];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                double value = processor.getPixelValue(x, y);
                if (!Double.isFinite(value)) continue;
                bins[valueToBin(value, minimum, maximum, safeBinCount)]++;
            }
        }
        return new Histogram(bins, minimum, maximum, finitePixelCount);
    }

    static int valueToBin(double value, double minimum, double maximum, int binCount) {
        int safeBinCount = Math.max(1, binCount);
        if (!(maximum > minimum)) return 0;
        double normalized = (value - minimum) / (maximum - minimum);
        int bin = (int) Math.floor(normalized * safeBinCount);
        if (bin < 0) return 0;
        if (bin >= safeBinCount) return safeBinCount - 1;
        return bin;
    }

    @Override protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int width = getWidth();
            int height = getHeight();
            int left = 8;
            int right = 8;
            int top = 8;
            int bottom = 20;
            int plotWidth = Math.max(1, width - left - right);
            int plotHeight = Math.max(1, height - top - bottom);

            g2.setColor(BACKGROUND);
            g2.fillRect(0, 0, width, height);

            if (histogram.isEmpty()) {
                drawCenteredText(g2, "No histogram", width, height);
                return;
            }

            drawMarkerOverlay(g2, left, top, plotWidth, plotHeight);
            drawBars(g2, left, top, plotWidth, plotHeight);

            g2.setColor(AXIS);
            g2.drawLine(left, top + plotHeight, left + plotWidth, top + plotHeight);
            drawLabels(g2, left, top + plotHeight + 14, plotWidth);
        } finally {
            g2.dispose();
        }
    }

    private void drawBars(Graphics2D g2, int left, int top, int plotWidth, int plotHeight) {
        int[] bins = histogram.getBins();
        int maxCount = histogram.getMaxCount();
        if (maxCount < 1) return;

        g2.setColor(BAR);
        for (int i = 0; i < bins.length; i++) {
            int x0 = left + (int) Math.floor((i * plotWidth) / (double) bins.length);
            int x1 = left + (int) Math.floor(((i + 1) * plotWidth) / (double) bins.length);
            int barWidth = Math.max(1, x1 - x0);
            int barHeight = (int) Math.round((bins[i] / (double) maxCount) * plotHeight);
            g2.fillRect(x0, top + plotHeight - barHeight, barWidth, Math.max(1, barHeight));
        }
    }

    private void drawMarkerOverlay(Graphics2D g2, int left, int top, int plotWidth, int plotHeight) {
        if (!Double.isFinite(lowerMarker) || !Double.isFinite(upperMarker)) return;
        double lower = Math.min(lowerMarker, upperMarker);
        double upper = Math.max(lowerMarker, upperMarker);
        int lowerX = valueToX(lower, left, plotWidth);
        int upperX = valueToX(upper, left, plotWidth);

        g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.18f));
        g2.setColor(OVERLAY);
        g2.fillRect(lowerX, top, Math.max(1, upperX - lowerX), plotHeight);
        g2.setComposite(AlphaComposite.SrcOver);
        g2.setColor(OVERLAY.darker());
        g2.drawLine(lowerX, top, lowerX, top + plotHeight);
        g2.drawLine(upperX, top, upperX, top + plotHeight);
    }

    private int valueToX(double value, int left, int plotWidth) {
        double minimum = histogram.getMinimum();
        double maximum = histogram.getMaximum();
        if (!(maximum > minimum)) return left;
        double clamped = FijiStyleRangeSliderPanel.clamp(value, minimum, maximum);
        double normalized = (clamped - minimum) / (maximum - minimum);
        return left + (int) Math.round(normalized * plotWidth);
    }

    private void drawLabels(Graphics2D g2, int left, int baseline, int plotWidth) {
        String minimum = FijiStyleRangeSliderPanel.formatNumber(histogram.getMinimum());
        String maximum = FijiStyleRangeSliderPanel.formatNumber(histogram.getMaximum());
        FontMetrics metrics = g2.getFontMetrics();
        g2.setColor(new Color(90, 90, 90));
        g2.drawString(minimum, left, baseline);
        g2.drawString(maximum, left + plotWidth - metrics.stringWidth(maximum), baseline);
    }

    private void drawCenteredText(Graphics2D g2, String text, int width, int height) {
        FontMetrics metrics = g2.getFontMetrics();
        g2.setColor(new Color(130, 130, 130));
        int x = Math.max(0, (width - metrics.stringWidth(text)) / 2);
        int y = Math.max(metrics.getAscent(), (height + metrics.getAscent()) / 2);
        g2.drawString(text, x, y);
    }

    public static final class Histogram {
        private final int[] bins;
        private final double minimum;
        private final double maximum;
        private final int finitePixelCount;
        private final int maxCount;

        private Histogram(int[] bins, double minimum, double maximum, int finitePixelCount) {
            this.bins = bins == null ? new int[0] : Arrays.copyOf(bins, bins.length);
            this.minimum = minimum;
            this.maximum = maximum;
            this.finitePixelCount = finitePixelCount;
            int max = 0;
            for (int bin : this.bins) {
                if (bin > max) max = bin;
            }
            this.maxCount = max;
        }

        static Histogram empty(int binCount) {
            return new Histogram(new int[Math.max(1, binCount)], 0.0, 1.0, 0);
        }

        public int[] getBins() {
            return Arrays.copyOf(bins, bins.length);
        }

        public int getBinCount() {
            return bins.length;
        }

        public int getMaxCount() {
            return maxCount;
        }

        public double getMinimum() {
            return minimum;
        }

        public double getMaximum() {
            return maximum;
        }

        public int getFinitePixelCount() {
            return finitePixelCount;
        }

        public boolean isEmpty() {
            return finitePixelCount < 1;
        }
    }
}
