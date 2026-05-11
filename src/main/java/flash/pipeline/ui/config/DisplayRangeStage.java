package flash.pipeline.ui.config;

import flash.pipeline.ui.preview.MinMaxControlPanel;
import flash.pipeline.ui.preview.PreviewPairPanel;
import ij.ImagePlus;

import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ScrollPaneConstants;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;

public final class DisplayRangeStage implements ConfigQcStage {

    private static final int CONTROL_PANEL_MAX_HEIGHT = 130;

    public interface RangeStore {
        String get();
        void set(String token);
    }

    public interface PreviewAdapter {
        ImagePlus createSource(ConfigQcContext context) throws Exception;
        void close(ImagePlus image);
    }

    private final RangeStore rangeStore;
    private final PreviewAdapter previewAdapter;

    private ConfigQcActions actions;
    private PreviewPairPanel preview;
    private ConfigQcContext activeContext;
    private MinMaxControlPanel control;
    private JLabel feedbackLabel;
    private ImagePlus sourceImage;
    private ImagePlus adjustedPreview;
    private double[] restartRange;

    public DisplayRangeStage(RangeStore rangeStore, PreviewAdapter previewAdapter) {
        if (rangeStore == null) {
            throw new IllegalArgumentException("rangeStore must not be null");
        }
        if (previewAdapter == null) {
            throw new IllegalArgumentException("previewAdapter must not be null");
        }
        this.rangeStore = rangeStore;
        this.previewAdapter = previewAdapter;
    }

    @Override
    public String title() {
        return "Display Range";
    }

    @Override
    public boolean showPreviewDisplayControls() {
        return false;
    }

    @Override
    public JComponent buildControls(ConfigQcContext context, ConfigQcActions actions) {
        this.actions = actions;
        this.activeContext = context;

        JPanel panel = new JPanel(new BorderLayout(0, 4));
        panel.setOpaque(false);

        JLabel help = new JLabel("Adjust min/max on the channel projection.");
        help.setForeground(new Color(90, 90, 90));
        panel.add(help, BorderLayout.NORTH);

        control = new MinMaxControlPanel();
        control.setListener(new MinMaxControlPanel.Listener() {
            @Override public void rangeChanged(double min, double max, boolean adjusting) {
                updateAdjustedPreview("Display range preview.");
            }

            @Override public void autoRequested() {
                setStatus("Auto display range preview.");
            }

            @Override public void resetRequested() {
                setStatus("Reset display range preview.");
            }

            @Override public void setRequested() {
                if (DisplayRangeStage.this.actions != null
                        && DisplayRangeStage.this.lockIn(activeContext)) {
                    DisplayRangeStage.this.actions.nextImage();
                }
            }
        });
        panel.add(buildBoundedControlPanel(control), BorderLayout.CENTER);

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
            sourceImage = previewAdapter.createSource(context);
            if (sourceImage == null) {
                throw new IllegalStateException("No display-range source image is available.");
            }
            if (preview != null) {
                preview.setOriginal(sourceImage);
                preview.setAdjusted(null);
            }
            if (control != null) {
                control.setImage(sourceImage);
                double[] persisted = restartRange != null ? restartRange : parseRange(rangeStore.get());
                if (persisted != null) {
                    control.setRange(persisted[0], persisted[1]);
                }
            }
            updateAdjustedPreview("Display range preview.");
        } catch (Exception e) {
            setError("Could not prepare display range preview: " + e.getMessage());
        }
    }

    @Override
    public boolean lockIn(ConfigQcContext context) {
        if (control == null || sourceImage == null) {
            setError("No display range preview is available.");
            return false;
        }
        String token = formatRange(control.getMinValue(), control.getMaxValue());
        rangeStore.set(token);
        restartRange = null;
        setStatus("Locked display range: " + token + ".");
        return true;
    }

    @Override
    public void skipCurrentImage(ConfigQcContext context) {
        setStatus("Skipped this image; saved display range is unchanged.");
    }

    @Override
    public void restartStage(ConfigQcContext context) {
        if (control != null) {
            restartRange = new double[]{control.getMinValue(), control.getMaxValue()};
        }
        setStatus("Restarting display range review from the first image.");
    }

    @Override
    public void onLeave(ConfigQcContext context) {
        closeImages();
        preview = null;
        activeContext = null;
    }

    void setRangeForTest(double min, double max) {
        if (control != null) {
            control.setRange(min, max);
            updateAdjustedPreview("Display range preview.");
        }
    }

    String currentRangeTokenForTest() {
        return control == null ? "" : formatRange(control.getMinValue(), control.getMaxValue());
    }

    private JComponent buildBoundedControlPanel(MinMaxControlPanel control) {
        JPanel bounded = new JPanel(new BorderLayout());
        bounded.setOpaque(false);
        bounded.setBorder(BorderFactory.createEmptyBorder(2, 0, 0, 0));

        Dimension preferred = control.getPreferredSize();
        if (preferred.height > CONTROL_PANEL_MAX_HEIGHT) {
            JScrollPane scroll = new JScrollPane(control,
                    ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                    ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
            scroll.setBorder(BorderFactory.createEmptyBorder());
            scroll.setOpaque(false);
            scroll.getViewport().setOpaque(false);
            scroll.setPreferredSize(new Dimension(preferred.width, CONTROL_PANEL_MAX_HEIGHT));
            bounded.add(scroll, BorderLayout.CENTER);
        } else {
            bounded.add(control, BorderLayout.CENTER);
        }
        return bounded;
    }

    private void updateAdjustedPreview(String text) {
        if (sourceImage == null || control == null) return;
        if (adjustedPreview == null) {
            adjustedPreview = sourceImage.duplicate();
            adjustedPreview.setTitle("Display range preview");
        }
        adjustedPreview.setDisplayRange(control.getMinValue(), control.getMaxValue());
        adjustedPreview.updateAndDraw();
        if (actions != null) {
            actions.setAdjustedPreview(adjustedPreview, text);
        } else if (preview != null) {
            preview.setAdjusted(adjustedPreview);
            preview.setAdjustedState(PreviewPairPanel.PreviewState.READY, text);
        }
        setStatus(text + " " + formatRange(control.getMinValue(), control.getMaxValue()));
    }

    private void closeImages() {
        ImagePlus adjusted = adjustedPreview;
        ImagePlus source = sourceImage;
        adjustedPreview = null;
        sourceImage = null;
        if (adjusted != null && adjusted != source) previewAdapter.close(adjusted);
        if (source != null) previewAdapter.close(source);
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

    static double[] parseRange(String token) {
        if (token == null || token.trim().isEmpty() || "None".equalsIgnoreCase(token.trim())) {
            return null;
        }
        String[] parts = token.trim().split("-");
        if (parts.length != 2) return null;
        try {
            double min = Double.parseDouble(parts[0].trim());
            double max = Double.parseDouble(parts[1].trim());
            if (!Double.isFinite(min) || !Double.isFinite(max)) return null;
            return new double[]{min, max};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static String formatRange(double min, double max) {
        return formatInteger(min) + "-" + formatInteger(max);
    }

    private static String formatInteger(double value) {
        return String.valueOf((int) Math.round(value));
    }
}
