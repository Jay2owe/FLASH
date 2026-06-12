package flash.pipeline.ui.config;

import flash.pipeline.image.DisplayRangeSetting;
import flash.pipeline.help.SetupHelpCatalog;
import flash.pipeline.help.SetupHelpTopic;
import flash.pipeline.ui.FlashTheme;
import flash.pipeline.ui.preview.FijiStyleRangeSliderPanel;
import flash.pipeline.ui.preview.MinMaxControlPanel;
import flash.pipeline.ui.preview.PreviewPairPanel;
import ij.ImagePlus;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.FlowLayout;

public final class DisplayRangeStage implements ConfigQcStage {

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
    private FijiStyleRangeSliderPanel saturationSlider;
    private JRadioButton manualModeRadio;
    private JRadioButton autoModeRadio;
    private JPanel manualCard;
    private JPanel autoCard;
    private JPanel manualBody;
    private JPanel autoBody;
    private JLabel feedbackLabel;
    private ImagePlus sourceImage;
    private ImagePlus adjustedPreview;
    private DisplayRangeSetting restartSetting;

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
    public SetupHelpTopic helpTopic() {
        return SetupHelpCatalog.DISPLAY_RANGE;
    }

    @Override
    public boolean showPreviewDisplayControls() {
        return false;
    }

    @Override
    public boolean showPreviewLutToggle() {
        return true;
    }

    @Override
    public boolean controlsCanExpand() {
        return true;
    }

    @Override
    public JComponent buildControls(ConfigQcContext context, ConfigQcActions actions) {
        this.actions = actions;
        this.activeContext = context;

        JPanel panel = new JPanel(new BorderLayout(0, 6));
        panel.setOpaque(false);

        JLabel help = new JLabel("Choose one display method for this channel.");
        help.setForeground(FlashTheme.TEXT_HELP);
        panel.add(help, BorderLayout.NORTH);

        JPanel cards = new JPanel();
        cards.setOpaque(false);
        cards.setLayout(new BoxLayout(cards, BoxLayout.Y_AXIS));

        manualModeRadio = new JRadioButton("Manual min/max display range", true);
        autoModeRadio = new JRadioButton("Auto-enhance contrast");
        manualModeRadio.setOpaque(false);
        autoModeRadio.setOpaque(false);
        ButtonGroup group = new ButtonGroup();
        group.add(manualModeRadio);
        group.add(autoModeRadio);

        control = new MinMaxControlPanel(false, "Suggest");
        control.setListener(new MinMaxControlPanel.Listener() {
            @Override public void rangeChanged(double min, double max, boolean adjusting) {
                updateAdjustedPreview("Manual display range preview.");
            }

            @Override public void autoRequested() {
                setStatus("Suggested manual display range.");
            }

            @Override public void resetRequested() {
                setStatus("Reset manual display range.");
            }

            @Override public void setRequested() {
            }
        });
        manualBody = new JPanel(new BorderLayout());
        manualBody.setOpaque(false);
        manualBody.add(buildControlPanel(control), BorderLayout.CENTER);
        manualBody.add(buildSetAction(), BorderLayout.SOUTH);
        manualCard = buildChoiceCard(manualModeRadio, manualBody);

        saturationSlider = new FijiStyleRangeSliderPanel("Saturated pixels (%)");
        saturationSlider.setRange(0.0, 10.0);
        saturationSlider.setValue(DisplayRangeSetting.DEFAULT_AUTO_SATURATION_PERCENT);
        saturationSlider.setListener(new FijiStyleRangeSliderPanel.Listener() {
            @Override public void valueChanged(double value, boolean adjusting) {
                updateAdjustedPreview("Auto-enhance preview.");
            }
        });
        autoBody = new JPanel(new BorderLayout(0, 4));
        autoBody.setOpaque(false);
        autoBody.add(saturationSlider, BorderLayout.CENTER);
        autoBody.add(buildAutoActions(), BorderLayout.SOUTH);
        autoCard = buildChoiceCard(autoModeRadio, autoBody);

        manualModeRadio.addActionListener(e -> selectMode(true, true));
        autoModeRadio.addActionListener(e -> selectMode(false, true));

        cards.add(manualCard);
        cards.add(Box.createVerticalStrut(6));
        cards.add(autoCard);
        panel.add(cards, BorderLayout.CENTER);

        feedbackLabel = new JLabel(" ");
        feedbackLabel.setForeground(FlashTheme.TEXT_HELP);
        panel.add(feedbackLabel, BorderLayout.SOUTH);
        updateModeState();
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
                DisplayRangeSetting persisted = restartSetting != null
                        ? restartSetting : DisplayRangeSetting.parse(rangeStore.get());
                if (persisted.isAutoEnhance()) {
                    setAutoSaturation(persisted.saturationPercent());
                    selectMode(false, false);
                } else {
                    selectMode(true, false);
                    if (persisted.isManual()) {
                        control.setRange(persisted.min(), persisted.max());
                    }
                }
            }
            updateAdjustedPreview(currentSetting().isAutoEnhance()
                    ? "Auto-enhance preview." : "Manual display range preview.");
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
        String token = currentSetting().toToken();
        rangeStore.set(token);
        restartSetting = null;
        setStatus("Locked display range: " + currentSetting().summary() + ".");
        return true;
    }

    @Override
    public void skipCurrentImage(ConfigQcContext context) {
        setStatus("Skipped this image; saved display range is unchanged.");
    }

    @Override
    public void restartStage(ConfigQcContext context) {
        restartSetting = currentSetting();
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
            selectMode(true, false);
            control.setRange(min, max);
            updateAdjustedPreview("Manual display range preview.");
        }
    }

    void setAutoSaturationForTest(double saturation) {
        selectMode(false, false);
        setAutoSaturation(saturation);
        updateAdjustedPreview("Auto-enhance preview.");
    }

    String currentRangeTokenForTest() {
        return currentSetting().toToken();
    }

    private JComponent buildControlPanel(MinMaxControlPanel control) {
        JPanel wrapper = new JPanel(new BorderLayout());
        wrapper.setOpaque(false);
        wrapper.setBorder(FlashTheme.pad(2, 0, 0, 0));
        wrapper.add(control, BorderLayout.CENTER);
        return wrapper;
    }

    private JPanel buildChoiceCard(JRadioButton radio, JPanel body) {
        JPanel card = new JPanel(new BorderLayout(0, 4));
        card.setOpaque(true);
        card.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(FlashTheme.BORDER),
                FlashTheme.pad(6, 8, 8, 8)));

        JPanel header = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        header.setOpaque(false);
        header.add(radio);
        card.add(header, BorderLayout.NORTH);
        card.add(body, BorderLayout.CENTER);
        return card;
    }

    private JPanel buildAutoActions() {
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        actions.setOpaque(false);
        JButton reset = new JButton("Reset");
        flash.pipeline.ui.FlashIcons.apply(reset, flash.pipeline.ui.FlashIcons.refresh());
        reset.addActionListener(e -> {
            setAutoSaturation(DisplayRangeSetting.DEFAULT_AUTO_SATURATION_PERCENT);
            updateAdjustedPreview("Auto-enhance preview.");
        });
        JButton set = new JButton("Set");
        flash.pipeline.ui.FlashIcons.apply(set, flash.pipeline.ui.FlashIcons.check());
        set.addActionListener(e -> {
            if (DisplayRangeStage.this.actions != null
                    && DisplayRangeStage.this.lockIn(activeContext)) {
                DisplayRangeStage.this.actions.nextImage();
            }
        });
        actions.add(reset);
        actions.add(set);
        return actions;
    }

    private JPanel buildSetAction() {
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        actions.setOpaque(false);
        JButton set = new JButton("Set");
        flash.pipeline.ui.FlashIcons.apply(set, flash.pipeline.ui.FlashIcons.check());
        set.addActionListener(e -> {
            if (DisplayRangeStage.this.actions != null
                    && DisplayRangeStage.this.lockIn(activeContext)) {
                DisplayRangeStage.this.actions.nextImage();
            }
        });
        actions.add(set);
        return actions;
    }

    private void selectMode(boolean manual, boolean updatePreview) {
        if (manualModeRadio != null) manualModeRadio.setSelected(manual);
        if (autoModeRadio != null) autoModeRadio.setSelected(!manual);
        updateModeState();
        if (updatePreview) {
            updateAdjustedPreview(manual
                    ? "Manual display range preview." : "Auto-enhance preview.");
        }
    }

    private void updateModeState() {
        boolean manual = manualModeRadio == null || manualModeRadio.isSelected();
        setEnabledDeep(manualBody, manual);
        setEnabledDeep(autoBody, !manual);
        updateCardStyle(manualCard, manual);
        updateCardStyle(autoCard, !manual);
    }

    private void updateCardStyle(JPanel card, boolean selected) {
        if (card == null) return;
        Color border = selected ? FlashTheme.PRIMARY_BORDER : FlashTheme.BORDER;
        card.setBackground(selected ? FlashTheme.SURFACE_RAISED : FlashTheme.SURFACE_MUTED);
        card.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(border),
                FlashTheme.pad(6, 8, 8, 8)));
    }

    private void setEnabledDeep(Component component, boolean enabled) {
        if (component == null) return;
        component.setEnabled(enabled);
        if (component instanceof java.awt.Container) {
            Component[] children = ((java.awt.Container) component).getComponents();
            for (int i = 0; i < children.length; i++) {
                setEnabledDeep(children[i], enabled);
            }
        }
    }

    private void updateAdjustedPreview(String text) {
        if (sourceImage == null || control == null || saturationSlider == null) return;
        ImagePlus previous = adjustedPreview;
        adjustedPreview = sourceImage.duplicate();
        adjustedPreview.setTitle("Display range preview");
        DisplayRangeSetting setting = currentSetting();
        setting.applyTo(adjustedPreview);
        adjustedPreview.updateAndDraw();
        if (previous != null && previous != sourceImage) {
            previewAdapter.close(previous);
        }
        if (actions != null) {
            actions.setAdjustedPreview(adjustedPreview, text);
        } else if (preview != null) {
            preview.setAdjusted(adjustedPreview);
            preview.setAdjustedState(PreviewPairPanel.PreviewState.READY, text);
        }
        setStatus(text + " " + setting.summary());
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

    private DisplayRangeSetting currentSetting() {
        if (autoModeRadio != null && autoModeRadio.isSelected()) {
            double saturation = saturationSlider == null
                    ? DisplayRangeSetting.DEFAULT_AUTO_SATURATION_PERCENT
                    : saturationSlider.getValue();
            return DisplayRangeSetting.autoEnhance(saturation);
        }
        if (control == null) {
            return DisplayRangeSetting.none();
        }
        return DisplayRangeSetting.manual(control.getMinValue(), control.getMaxValue());
    }

    private void setAutoSaturation(double saturation) {
        if (saturationSlider == null) return;
        double safe = DisplayRangeSetting.normalizeSaturation(
                saturation, DisplayRangeSetting.DEFAULT_AUTO_SATURATION_PERCENT);
        saturationSlider.setRange(0.0, Math.max(10.0, safe));
        saturationSlider.setValue(safe);
    }

    static double[] parseRange(String token) {
        return DisplayRangeSetting.parseManualRange(token);
    }

    static String formatRange(double min, double max) {
        return DisplayRangeSetting.formatManualToken(min, max);
    }
}
