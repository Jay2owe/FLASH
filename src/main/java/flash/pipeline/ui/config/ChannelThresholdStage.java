package flash.pipeline.ui.config;

import flash.pipeline.ui.preview.PreviewPairPanel;
import flash.pipeline.ui.preview.ThresholdControlPanel;
import flash.pipeline.ui.preview.ThresholdOverlayRenderer;
import ij.ImagePlus;
import ij.process.ImageProcessor;

import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.Color;

public final class ChannelThresholdStage implements ConfigQcStage {

    public interface ThresholdStore {
        String get();
        void set(String token);
    }

    public interface PreviewAdapter {
        ImagePlus createRawSource(ConfigQcContext context) throws Exception;
        ImagePlus createThresholdSource(ConfigQcContext context) throws Exception;
        void close(ImagePlus image);
    }

    private final ThresholdStore thresholdStore;
    private final PreviewAdapter previewAdapter;

    private ConfigQcActions actions;
    private PreviewPairPanel preview;
    private ConfigQcContext activeContext;
    private ThresholdControlPanel control;
    private JLabel feedbackLabel;
    private ImagePlus rawSource;
    private ImagePlus thresholdSource;
    private ImagePlus adjustedPreview;
    private Double restartLowerThreshold;

    public ChannelThresholdStage(ThresholdStore thresholdStore, PreviewAdapter previewAdapter) {
        if (thresholdStore == null) {
            throw new IllegalArgumentException("thresholdStore must not be null");
        }
        if (previewAdapter == null) {
            throw new IllegalArgumentException("previewAdapter must not be null");
        }
        this.thresholdStore = thresholdStore;
        this.previewAdapter = previewAdapter;
    }

    @Override
    public String title() {
        return "Channel Threshold";
    }

    @Override
    public boolean showPreviewDisplayControls() {
        return false;
    }

    @Override
    public boolean controlsCanExpand() {
        return true;
    }

    @Override
    public JComponent buildControls(ConfigQcContext context, ConfigQcActions actions) {
        this.actions = actions;
        this.activeContext = context;

        JPanel panel = new JPanel(new BorderLayout(0, 4));
        panel.setOpaque(false);

        JLabel help = new JLabel("Adjust threshold to isolate the channel of interest.");
        help.setForeground(new Color(90, 90, 90));
        panel.add(help, BorderLayout.NORTH);

        control = new ThresholdControlPanel();
        control.setMethod("Default");
        control.setBackgroundMode("Dark");
        control.setListener(new ThresholdControlPanel.Listener() {
            @Override public void thresholdChanged(double lower, double upper, boolean adjusting) {
                updateThresholdPreview("Threshold preview.");
            }

            @Override public void autoRequested(String method, String background) {
                setStatus("Auto threshold preview: " + formatThreshold(control.getLowerThreshold()) + ".");
            }

            @Override public void resetRequested() {
                setStatus("Reset threshold preview.");
            }

            @Override public void setRequested() {
                if (ChannelThresholdStage.this.actions != null
                        && ChannelThresholdStage.this.lockIn(activeContext)) {
                    ChannelThresholdStage.this.actions.nextImage();
                }
            }
        });
        panel.add(buildControlPanel(control), BorderLayout.CENTER);

        feedbackLabel = new JLabel(" ");
        feedbackLabel.setForeground(new Color(90, 90, 90));
        panel.add(feedbackLabel, BorderLayout.SOUTH);
        return panel;
    }

    @Override
    public void onEnter(ConfigQcContext context, PreviewPairPanel preview) {
        closeImages();
        this.activeContext = context;
        this.preview = preview;
        try {
            rawSource = previewAdapter.createRawSource(context);
            thresholdSource = previewAdapter.createThresholdSource(context);
            if (thresholdSource == null) {
                thresholdSource = rawSource;
            }
            if (thresholdSource == null) {
                throw new IllegalStateException("No threshold source image is available.");
            }
            if (preview != null) {
                preview.setOriginal(rawSource == null ? thresholdSource : rawSource);
                preview.setAdjusted(null);
            }
            if (control != null) {
                control.setImage(thresholdSource);
                applySavedOrAutoThreshold();
            }
            updateThresholdPreview("Threshold preview.");
        } catch (Exception e) {
            setError("Could not prepare threshold preview: " + e.getMessage());
        }
    }

    @Override
    public boolean lockIn(ConfigQcContext context) {
        if (control == null || thresholdSource == null) {
            setError("No threshold preview is available.");
            return false;
        }
        String token = formatThreshold(control.getLowerThreshold());
        thresholdStore.set(token);
        restartLowerThreshold = null;
        setStatus("Locked threshold: " + token + ".");
        return true;
    }

    @Override
    public void skipCurrentImage(ConfigQcContext context) {
        setStatus("Skipped this image; saved threshold is unchanged.");
    }

    @Override
    public void restartStage(ConfigQcContext context) {
        if (control != null) {
            restartLowerThreshold = Double.valueOf(control.getLowerThreshold());
        }
        setStatus("Restarting threshold review from the first image.");
    }

    @Override
    public void onLeave(ConfigQcContext context) {
        closeImages();
        preview = null;
        activeContext = null;
    }

    void setThresholdForTest(double lower, double upper) {
        if (control != null) {
            control.setThreshold(lower, upper);
            updateThresholdPreview("Threshold preview.");
        }
    }

    void setPreviewModeForTest(String mode) {
        if (control != null) {
            control.setPreviewMode(mode);
            updateThresholdPreview("Threshold preview.");
        }
    }

    String currentThresholdTokenForTest() {
        return control == null ? "" : formatThreshold(control.getLowerThreshold());
    }

    private JComponent buildControlPanel(ThresholdControlPanel control) {
        JPanel wrapper = new JPanel(new BorderLayout());
        wrapper.setOpaque(false);
        wrapper.setBorder(BorderFactory.createEmptyBorder(2, 0, 0, 0));
        wrapper.add(control, BorderLayout.CENTER);
        return wrapper;
    }

    private void applySavedOrAutoThreshold() {
        if (control == null || thresholdSource == null) return;
        String token = normalizeThresholdToken(thresholdStore.get());
        double upper = imageMaximum(thresholdSource);
        if (restartLowerThreshold != null && Double.isFinite(restartLowerThreshold.doubleValue())) {
            control.setThreshold(restartLowerThreshold.doubleValue(), upper);
            return;
        }
        if (isNumericThresholdToken(token)) {
            try {
                control.setThreshold(Double.parseDouble(token), upper);
                return;
            } catch (NumberFormatException ignored) {
                // Fall through to automatic suggestion.
            }
        }
        double auto = defaultDarkThreshold(thresholdSource);
        if (Double.isFinite(auto)) {
            control.setThreshold(auto, upper);
        }
    }

    private void updateThresholdPreview(String text) {
        if (thresholdSource == null || control == null) return;
        ImagePlus next = ThresholdOverlayRenderer.render(
                thresholdSource,
                control.getLowerThreshold(),
                control.getUpperThreshold(),
                control.getPreviewMode());
        if (next == null) return;
        next.setTitle("Threshold preview");
        ImagePlus old = adjustedPreview;
        adjustedPreview = next;
        if (actions != null) {
            actions.setAdjustedPreview(adjustedPreview, text);
        } else if (preview != null) {
            preview.setAdjusted(adjustedPreview);
            preview.setAdjustedState(PreviewPairPanel.PreviewState.READY, text);
        }
        if (old != null && old != adjustedPreview) {
            previewAdapter.close(old);
        }
        setStatus(text + " Lower: " + formatThreshold(control.getLowerThreshold()));
    }

    private void closeImages() {
        ImagePlus adjusted = adjustedPreview;
        ImagePlus threshold = thresholdSource;
        ImagePlus raw = rawSource;
        adjustedPreview = null;
        thresholdSource = null;
        rawSource = null;
        if (adjusted != null && adjusted != threshold && adjusted != raw) previewAdapter.close(adjusted);
        if (threshold != null && threshold != raw) previewAdapter.close(threshold);
        if (raw != null) previewAdapter.close(raw);
    }

    private void setStatus(String text) {
        if (feedbackLabel != null) {
            feedbackLabel.setText(text == null || text.trim().isEmpty() ? " " : text);
        }
        if (actions != null) {
            actions.setStatus(text);
        }
    }

    private void setError(String text) {
        if (preview != null) {
            preview.setAdjustedState(PreviewPairPanel.PreviewState.ERROR, text);
        }
        setStatus(text);
    }

    static double defaultDarkThreshold(ImagePlus image) {
        ImageProcessor processor = currentProcessorDuplicate(image);
        if (processor == null) return Double.NaN;
        try {
            processor.setAutoThreshold("Default dark");
            double threshold = processor.getMinThreshold();
            if (isValidThresholdValue(threshold)) return threshold;
        } catch (RuntimeException ignored) {
            // Keep the embedded stage usable if an ImageJ method is unavailable.
        }
        return Double.NaN;
    }

    private static ImageProcessor currentProcessorDuplicate(ImagePlus image) {
        if (image == null || image.getStack() == null || image.getStackSize() < 1) return null;
        ImageProcessor processor = image.getProcessor();
        return processor == null ? null : processor.duplicate();
    }

    private static double imageMaximum(ImagePlus image) {
        if (image == null) return 255.0;
        ImageProcessor processor = image.getProcessor();
        if (processor == null) return 255.0;
        double max = processor.getMax();
        return Double.isFinite(max) ? max : 255.0;
    }

    private static boolean isValidThresholdValue(double threshold) {
        return Double.isFinite(threshold) && threshold != ImageProcessor.NO_THRESHOLD;
    }

    static String normalizeThresholdToken(String token) {
        if (token == null) return "";
        String trimmed = token.trim();
        if ("default".equalsIgnoreCase(trimmed)) return "default";
        return trimmed;
    }

    static boolean isNumericThresholdToken(String token) {
        if (token == null || token.trim().isEmpty()) return false;
        try {
            double parsed = Double.parseDouble(token.trim());
            return Double.isFinite(parsed);
        } catch (NumberFormatException e) {
            return false;
        }
    }

    static String formatThreshold(double threshold) {
        return String.valueOf((int) Math.round(threshold));
    }
}
