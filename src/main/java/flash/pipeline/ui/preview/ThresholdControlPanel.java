package flash.pipeline.ui.preview;

import ij.ImagePlus;
import ij.process.ImageProcessor;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.Locale;

public final class ThresholdControlPanel extends JPanel {

    public interface Listener {
        void thresholdChanged(double lower, double upper, boolean adjusting);
        void autoRequested(String method, String background);
        void resetRequested();
        void setRequested();
    }

    private static final String[] METHODS = {
            "Default", "Huang", "Intermodes", "IsoData", "Li", "MaxEntropy",
            "Mean", "MinError", "Minimum", "Moments", "Otsu", "Percentile",
            "RenyiEntropy", "Shanbhag", "Triangle", "Yen"
    };
    private static final String[] BACKGROUNDS = {"Dark", "Light"};
    private static final String[] PREVIEWS = {
            ThresholdOverlayRenderer.MODE_RED_OVERLAY,
            ThresholdOverlayRenderer.MODE_MASK,
            ThresholdOverlayRenderer.MODE_FILTERED
    };

    private final HistogramPanel histogramPanel = new HistogramPanel();
    private final FijiStyleRangeSliderPanel lowerSlider = new FijiStyleRangeSliderPanel("Lower threshold");
    private final FijiStyleRangeSliderPanel upperSlider = new FijiStyleRangeSliderPanel("Upper threshold");
    private final JComboBox<String> methodCombo = new JComboBox<String>(METHODS);
    private final JComboBox<String> backgroundCombo = new JComboBox<String>(BACKGROUNDS);
    private final JComboBox<String> previewCombo = new JComboBox<String>(PREVIEWS);
    private final JLabel previewLabel = new JLabel("Preview:");
    private final JButton autoButton = new JButton("Auto");
    private final JButton resetButton = new JButton("Reset");
    private final JButton setButton = new JButton("Set");

    private ImagePlus image;
    private double imageDomainMin = 0.0;
    private double imageDomainMax = 255.0;
    private double domainMin = 0.0;
    private double domainMax = 255.0;
    private double resetLower = 0.0;
    private double resetUpper = 255.0;
    private double lowerValue = 0.0;
    private double upperValue = 255.0;
    private boolean updating;
    private Listener listener;

    public ThresholdControlPanel() {
        super(new BorderLayout(0, 6));
        setBorder(BorderFactory.createTitledBorder("Threshold"));
        flash.pipeline.ui.FlashIcons.apply(autoButton, flash.pipeline.ui.FlashIcons.wand());
        flash.pipeline.ui.FlashIcons.apply(resetButton, flash.pipeline.ui.FlashIcons.refresh());
        flash.pipeline.ui.FlashIcons.apply(setButton, flash.pipeline.ui.FlashIcons.check());
        add(histogramPanel, BorderLayout.NORTH);
        add(buildControls(), BorderLayout.CENTER);
        add(buildActions(), BorderLayout.SOUTH);
        wireControls();
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public void setImage(ImagePlus image) {
        this.image = image;
        HistogramPanel.Histogram histogram = HistogramPanel.calculateHistogram(image, HistogramPanel.DEFAULT_BIN_COUNT);
        histogramPanel.setHistogram(histogram);
        if (histogram.isEmpty()) {
            imageDomainMin = 0.0;
            imageDomainMax = 255.0;
        } else {
            imageDomainMin = histogram.getMinimum();
            imageDomainMax = histogram.getMaximum();
        }
        domainMin = imageDomainMin;
        domainMax = imageDomainMax;
        double[] existing = existingThreshold(image);
        if (existing == null) {
            resetLower = domainMin;
            resetUpper = domainMax;
        } else {
            resetLower = existing[0];
            resetUpper = existing[1];
        }
        configureSliderDomains();
        applyThreshold(resetLower, resetUpper, false, false);
        refreshLayoutAndPaint();
    }

    public void setThreshold(double lower, double upper) {
        applyThreshold(lower, upper, false, false);
        refreshLayoutAndPaint();
    }

    public void setThresholdPreservingRange(double lower, double upper) {
        applyThreshold(lower, upper, false, false, true);
        refreshLayoutAndPaint();
    }

    public double getLowerThreshold() {
        return lowerValue;
    }

    public double getUpperThreshold() {
        return upperValue;
    }

    public String getMethod() {
        Object selected = methodCombo.getSelectedItem();
        return selected == null ? "Default" : selected.toString();
    }

    public void setMethod(String method) {
        setComboSelection(methodCombo, method);
    }

    public String getBackgroundMode() {
        Object selected = backgroundCombo.getSelectedItem();
        return selected == null ? "Dark" : selected.toString();
    }

    public void setBackgroundMode(String background) {
        setComboSelection(backgroundCombo, background);
    }

    public String getPreviewMode() {
        Object selected = previewCombo.getSelectedItem();
        return selected == null ? "Red overlay" : selected.toString();
    }

    public void setPreviewMode(String previewMode) {
        setComboSelection(previewCombo, previewMode);
    }

    public void setPreviewSelectorVisible(boolean visible) {
        previewLabel.setVisible(visible);
        previewCombo.setVisible(visible);
        revalidate();
        repaint();
    }

    public boolean isPreviewSelectorVisible() {
        return previewCombo.isVisible();
    }

    public void setSetButtonVisible(boolean visible) {
        setButton.setVisible(visible);
        revalidate();
        repaint();
    }

    HistogramPanel histogramPanelForTest() {
        return histogramPanel;
    }

    FijiStyleRangeSliderPanel lowerSliderForTest() {
        return lowerSlider;
    }

    FijiStyleRangeSliderPanel upperSliderForTest() {
        return upperSlider;
    }

    JButton autoButtonForTest() {
        return autoButton;
    }

    JButton resetButtonForTest() {
        return resetButton;
    }

    private JPanel buildControls() {
        JPanel controls = new JPanel();
        controls.setOpaque(false);
        controls.setLayout(new BoxLayout(controls, BoxLayout.Y_AXIS));
        controls.add(lowerSlider);
        controls.add(upperSlider);
        controls.add(buildSelectors());
        return controls;
    }

    private JPanel buildSelectors() {
        JPanel selectors = new JPanel(new GridBagLayout());
        selectors.setOpaque(false);
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridy = 0;
        gbc.insets = new Insets(2, 0, 2, 8);
        gbc.anchor = GridBagConstraints.WEST;

        gbc.gridx = 0;
        selectors.add(new JLabel("Method:"), gbc);
        gbc.gridx = 1;
        selectors.add(methodCombo, gbc);
        gbc.gridx = 2;
        selectors.add(new JLabel("Background:"), gbc);
        gbc.gridx = 3;
        selectors.add(backgroundCombo, gbc);

        gbc.gridy = 1;
        gbc.gridx = 0;
        selectors.add(previewLabel, gbc);
        gbc.gridx = 1;
        gbc.gridwidth = 3;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        selectors.add(previewCombo, gbc);
        return selectors;
    }

    private JPanel buildActions() {
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        actions.setOpaque(false);
        autoButton.addActionListener(e -> {
            double[] autoThreshold = calculateAutoThreshold(getMethod(), getBackgroundMode());
            applyThreshold(autoThreshold[0], autoThreshold[1], true, false);
            if (listener != null) listener.autoRequested(getMethod(), getBackgroundMode());
        });
        resetButton.addActionListener(e -> {
            applyThreshold(resetLower, resetUpper, true, false);
            if (listener != null) listener.resetRequested();
        });
        setButton.addActionListener(e -> {
            if (listener != null) listener.setRequested();
        });
        actions.add(autoButton);
        actions.add(resetButton);
        actions.add(setButton);
        actions.add(Box.createHorizontalGlue());
        return actions;
    }

    private void wireControls() {
        lowerSlider.setListener((value, adjusting) -> {
            if (updating) return;
            applyThreshold(Math.min(value, upperValue), upperValue, true, adjusting);
        });
        upperSlider.setListener((value, adjusting) -> {
            if (updating) return;
            applyThreshold(lowerValue, Math.max(value, lowerValue), true, adjusting);
        });
        previewCombo.addActionListener(e -> {
            if (!updating && listener != null) {
                listener.thresholdChanged(lowerValue, upperValue, false);
            }
        });
    }

    private void configureSliderDomains() {
        lowerSlider.setRange(domainMin, domainMax);
        upperSlider.setRange(domainMin, domainMax);
    }

    private void applyThreshold(double requestedLower, double requestedUpper, boolean fire, boolean adjusting) {
        applyThreshold(requestedLower, requestedUpper, fire, adjusting, false);
    }

    private void applyThreshold(double requestedLower, double requestedUpper, boolean fire, boolean adjusting,
                                boolean allowDomainExpansion) {
        if (allowDomainExpansion && expandDomainToInclude(requestedLower, requestedUpper)) {
            configureSliderDomains();
        }
        double lower = FijiStyleRangeSliderPanel.clamp(requestedLower, domainMin, domainMax);
        double upper = FijiStyleRangeSliderPanel.clamp(requestedUpper, domainMin, domainMax);
        if (lower > upper) {
            double swap = lower;
            lower = upper;
            upper = swap;
        }

        lowerValue = lower;
        upperValue = upper;

        updating = true;
        try {
            lowerSlider.setValue(lowerValue);
            upperSlider.setValue(upperValue);
            histogramPanel.setMarkers(lowerValue, upperValue);
        } finally {
            updating = false;
        }

        if (fire && listener != null) {
            listener.thresholdChanged(lowerValue, upperValue, adjusting);
        }
    }

    private boolean expandDomainToInclude(double first, double second) {
        double newMin = domainMin;
        double newMax = domainMax;
        if (Double.isFinite(first)) {
            if (first < newMin) newMin = first;
            if (first > newMax) newMax = first;
        }
        if (Double.isFinite(second)) {
            if (second < newMin) newMin = second;
            if (second > newMax) newMax = second;
        }
        if (newMin == domainMin && newMax == domainMax) {
            return false;
        }
        domainMin = newMin;
        domainMax = newMax;
        return true;
    }

    private void refreshLayoutAndPaint() {
        histogramPanel.revalidate();
        histogramPanel.repaint();
        lowerSlider.revalidate();
        lowerSlider.repaint();
        upperSlider.revalidate();
        upperSlider.repaint();
        revalidate();
        repaint();
    }

    private double[] calculateAutoThreshold(String method, String background) {
        ImageProcessor processor = duplicateCurrentProcessor();
        if (processor != null) {
            try {
                processor.setAutoThreshold(method + " " + background.toLowerCase(Locale.US));
                double lower = processor.getMinThreshold();
                double upper = processor.getMaxThreshold();
                if (isValidThreshold(lower)) {
                    if (!isValidThreshold(upper) || upper < lower) {
                        upper = imageDomainMax;
                    }
                    return new double[]{
                            FijiStyleRangeSliderPanel.clamp(lower, imageDomainMin, imageDomainMax),
                            FijiStyleRangeSliderPanel.clamp(upper, imageDomainMin, imageDomainMax)
                    };
                }
            } catch (RuntimeException ignored) {
                // Keep the embedded control usable even if an ImageJ method is unavailable.
            }
        }
        return new double[]{imageDomainMin, imageDomainMax};
    }

    private ImageProcessor duplicateCurrentProcessor() {
        if (image == null || image.getStack() == null || image.getStackSize() < 1) return null;
        ImageProcessor processor = image.getProcessor();
        return processor == null ? null : processor.duplicate();
    }

    private double[] existingThreshold(ImagePlus image) {
        if (image == null || image.getStack() == null || image.getStackSize() < 1) return null;
        ImageProcessor processor = image.getProcessor();
        if (processor == null) return null;
        double lower = processor.getMinThreshold();
        double upper = processor.getMaxThreshold();
        if (!isValidThreshold(lower) || !isValidThreshold(upper)) return null;
        return new double[]{
                FijiStyleRangeSliderPanel.clamp(lower, domainMin, domainMax),
                FijiStyleRangeSliderPanel.clamp(upper, domainMin, domainMax)
        };
    }

    private static boolean isValidThreshold(double value) {
        return Double.isFinite(value) && value != ImageProcessor.NO_THRESHOLD;
    }

    private static void setComboSelection(JComboBox<String> combo, String value) {
        if (value == null) return;
        for (int i = 0; i < combo.getItemCount(); i++) {
            if (value.equalsIgnoreCase(combo.getItemAt(i))) {
                combo.setSelectedIndex(i);
                return;
            }
        }
    }
}
