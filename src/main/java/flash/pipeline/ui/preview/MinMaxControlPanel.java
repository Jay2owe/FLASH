package flash.pipeline.ui.preview;

import ij.ImagePlus;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;

public final class MinMaxControlPanel extends JPanel {

    public interface Listener {
        void rangeChanged(double min, double max, boolean adjusting);
        void autoRequested();
        void resetRequested();
        void setRequested();
    }

    private static final double AUTO_SATURATION_FRACTION = 0.0035;
    private static final int HISTOGRAM_MINIMUM_HEIGHT = 48;
    private static final int HISTOGRAM_PREFERRED_HEIGHT = 62;

    private final HistogramPanel histogramPanel = new HistogramPanel();
    private final FijiStyleRangeSliderPanel minimumSlider = new FijiStyleRangeSliderPanel("Minimum");
    private final FijiStyleRangeSliderPanel maximumSlider = new FijiStyleRangeSliderPanel("Maximum");
    private final FijiStyleRangeSliderPanel brightnessSlider = new FijiStyleRangeSliderPanel("Brightness");
    private final FijiStyleRangeSliderPanel contrastSlider = new FijiStyleRangeSliderPanel("Contrast");
    private final boolean includeSetButton;

    private double domainMin = 0.0;
    private double domainMax = 255.0;
    private double resetMin = 0.0;
    private double resetMax = 255.0;
    private double minValue = 0.0;
    private double maxValue = 255.0;
    private boolean updating;
    private Listener listener;

    public MinMaxControlPanel() {
        this(true);
    }

    public MinMaxControlPanel(boolean includeSetButton) {
        super(new BorderLayout(0, 6));
        this.includeSetButton = includeSetButton;
        setBorder(BorderFactory.createTitledBorder("Brightness/Contrast"));
        histogramPanel.setMinimumSize(new Dimension(220, HISTOGRAM_MINIMUM_HEIGHT));
        histogramPanel.setPreferredSize(new Dimension(320, HISTOGRAM_PREFERRED_HEIGHT));
        add(histogramPanel, BorderLayout.NORTH);
        add(buildControls(), BorderLayout.CENTER);
        add(buildActions(), BorderLayout.SOUTH);
        wireSliders();
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public void setImage(ImagePlus image) {
        HistogramPanel.Histogram histogram = HistogramPanel.calculateHistogram(image, HistogramPanel.DEFAULT_BIN_COUNT);
        histogramPanel.setHistogram(histogram);
        if (histogram.isEmpty()) {
            domainMin = 0.0;
            domainMax = 255.0;
        } else {
            domainMin = histogram.getMinimum();
            domainMax = histogram.getMaximum();
        }
        resetMin = displayRangeMin(image, domainMin);
        resetMax = displayRangeMax(image, domainMax);
        configureSliderDomains();
        applyRange(resetMin, resetMax, false, false);
    }

    public void setRange(double min, double max) {
        applyRange(min, max, false, false);
    }

    public double getMinValue() {
        return minValue;
    }

    public double getMaxValue() {
        return maxValue;
    }

    HistogramPanel histogramPanelForTest() {
        return histogramPanel;
    }

    FijiStyleRangeSliderPanel minimumSliderForTest() {
        return minimumSlider;
    }

    FijiStyleRangeSliderPanel maximumSliderForTest() {
        return maximumSlider;
    }

    private JPanel buildControls() {
        JPanel controls = new JPanel();
        controls.setOpaque(false);
        controls.setLayout(new BoxLayout(controls, BoxLayout.Y_AXIS));
        controls.add(minimumSlider);
        controls.add(maximumSlider);
        controls.add(brightnessSlider);
        controls.add(contrastSlider);
        return controls;
    }

    private JPanel buildActions() {
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        actions.setOpaque(false);
        JButton auto = new JButton("Auto");
        JButton reset = new JButton("Reset");
        auto.addActionListener(e -> {
            double[] autoRange = calculateAutoDisplayRange();
            applyRange(autoRange[0], autoRange[1], true, false);
            if (listener != null) listener.autoRequested();
        });
        reset.addActionListener(e -> {
            applyRange(resetMin, resetMax, true, false);
            if (listener != null) listener.resetRequested();
        });
        actions.add(auto);
        actions.add(reset);
        if (includeSetButton) {
            JButton set = new JButton("Set");
            set.addActionListener(e -> {
                if (listener != null) listener.setRequested();
            });
            actions.add(set);
        }
        actions.add(Box.createHorizontalGlue());
        return actions;
    }

    private void wireSliders() {
        minimumSlider.setListener((value, adjusting) -> {
            if (updating) return;
            applyRange(Math.min(value, maxValue), maxValue, true, adjusting);
        });
        maximumSlider.setListener((value, adjusting) -> {
            if (updating) return;
            applyRange(minValue, Math.max(value, minValue), true, adjusting);
        });
        brightnessSlider.setListener((value, adjusting) -> {
            if (updating) return;
            applyBrightness(value, adjusting);
        });
        contrastSlider.setListener((value, adjusting) -> {
            if (updating) return;
            applyContrastWidth(value, adjusting);
        });
    }

    private void configureSliderDomains() {
        minimumSlider.setRange(domainMin, domainMax);
        maximumSlider.setRange(domainMin, domainMax);
        brightnessSlider.setRange(domainMin, domainMax);
        contrastSlider.setRange(0.0, Math.max(1.0, domainMax - domainMin));
    }

    private void applyRange(double requestedMin, double requestedMax, boolean fire, boolean adjusting) {
        double min = FijiStyleRangeSliderPanel.clamp(requestedMin, domainMin, domainMax);
        double max = FijiStyleRangeSliderPanel.clamp(requestedMax, domainMin, domainMax);
        if (min > max) {
            double swap = min;
            min = max;
            max = swap;
        }

        minValue = min;
        maxValue = max;

        updating = true;
        try {
            minimumSlider.setValue(minValue);
            maximumSlider.setValue(maxValue);
            updateBrightnessContrastSliders();
            histogramPanel.setMarkers(minValue, maxValue);
        } finally {
            updating = false;
        }

        if (fire && listener != null) {
            listener.rangeChanged(minValue, maxValue, adjusting);
        }
    }

    private void updateBrightnessContrastSliders() {
        double center = (minValue + maxValue) / 2.0;
        double width = Math.max(0.0, maxValue - minValue);
        brightnessSlider.setValue(center);
        contrastSlider.setValue(width);
    }

    private void applyBrightness(double center, boolean adjusting) {
        double width = Math.max(0.0, maxValue - minValue);
        if (width >= domainMax - domainMin) {
            applyRange(domainMin, domainMax, true, adjusting);
            return;
        }
        double halfWidth = width / 2.0;
        double safeCenter = FijiStyleRangeSliderPanel.clamp(center, domainMin + halfWidth, domainMax - halfWidth);
        applyRange(safeCenter - halfWidth, safeCenter + halfWidth, true, adjusting);
    }

    private void applyContrastWidth(double width, boolean adjusting) {
        double fullWidth = Math.max(0.0, domainMax - domainMin);
        double safeWidth = FijiStyleRangeSliderPanel.clamp(width, 0.0, fullWidth);
        double center = (minValue + maxValue) / 2.0;
        double newMin = center - safeWidth / 2.0;
        double newMax = center + safeWidth / 2.0;
        if (newMin < domainMin) {
            newMax += domainMin - newMin;
            newMin = domainMin;
        }
        if (newMax > domainMax) {
            newMin -= newMax - domainMax;
            newMax = domainMax;
        }
        applyRange(newMin, newMax, true, adjusting);
    }

    private double[] calculateAutoDisplayRange() {
        HistogramPanel.Histogram histogram = histogramPanel.histogramForTest();
        if (histogram == null || histogram.isEmpty()) {
            return new double[]{domainMin, domainMax};
        }

        int[] bins = histogram.getBins();
        int total = histogram.getFinitePixelCount();
        int saturated = (int) Math.floor(total * AUTO_SATURATION_FRACTION);
        int lowerBin = firstBinAboveCount(bins, saturated);
        int upperBin = lastBinBelowCount(bins, total - saturated);
        double low = binToValue(lowerBin, bins.length, histogram.getMinimum(), histogram.getMaximum());
        double high = binToValue(upperBin + 1, bins.length, histogram.getMinimum(), histogram.getMaximum());
        if (!(high > low)) {
            return new double[]{domainMin, domainMax};
        }
        return new double[]{
                FijiStyleRangeSliderPanel.clamp(low, domainMin, domainMax),
                FijiStyleRangeSliderPanel.clamp(high, domainMin, domainMax)
        };
    }

    private static int firstBinAboveCount(int[] bins, int count) {
        int cumulative = 0;
        for (int i = 0; i < bins.length; i++) {
            cumulative += bins[i];
            if (cumulative > count) return i;
        }
        return Math.max(0, bins.length - 1);
    }

    private static int lastBinBelowCount(int[] bins, int count) {
        int cumulative = 0;
        for (int i = 0; i < bins.length; i++) {
            cumulative += bins[i];
            if (cumulative >= count) return i;
        }
        return Math.max(0, bins.length - 1);
    }

    private static double binToValue(int bin, int binCount, double minimum, double maximum) {
        if (binCount < 1) return minimum;
        double normalized = FijiStyleRangeSliderPanel.clamp(bin / (double) binCount, 0.0, 1.0);
        return minimum + normalized * (maximum - minimum);
    }

    private static double displayRangeMin(ImagePlus image, double fallback) {
        if (!hasUsableImage(image)) return fallback;
        double value;
        try {
            value = image.getDisplayRangeMin();
        } catch (RuntimeException e) {
            return fallback;
        }
        if (!Double.isFinite(value)) return fallback;
        return FijiStyleRangeSliderPanel.clamp(value, fallback, displayRangeMax(image, fallback));
    }

    private static double displayRangeMax(ImagePlus image, double fallback) {
        if (!hasUsableImage(image)) return fallback;
        double value;
        try {
            value = image.getDisplayRangeMax();
        } catch (RuntimeException e) {
            return fallback;
        }
        if (!Double.isFinite(value)) return fallback;
        return Math.max(fallback, value);
    }

    private static boolean hasUsableImage(ImagePlus image) {
        try {
            return image != null && image.getStack() != null && image.getStackSize() > 0;
        } catch (RuntimeException e) {
            return false;
        }
    }
}
