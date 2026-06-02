package flash.pipeline.ui.preview;

import ij.ImagePlus;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Dialog;
import java.awt.FlowLayout;
import java.awt.GraphicsEnvironment;
import java.awt.GridLayout;
import java.awt.Rectangle;
import java.awt.Window;

public final class LargePreviewDialog extends JDialog {

    private static final double INITIAL_DESKTOP_WIDTH_FRACTION = 0.82;
    private static final double INITIAL_DESKTOP_HEIGHT_FRACTION = 0.80;

    interface SliceListener {
        void zSliceChanged(int zSlice);
    }

    interface SourceChoiceListener {
        void sourceChoiceChanged(PreviewPairPanel.SourceMode mode);
    }

    interface DisplayActionListener {
        void adjustBrightnessContrastRequested();
        void lutToggleRequested();
    }

    public interface ObjectClickListener {
        void objectClicked(int label, int z, double x, double y,
                           boolean positive, boolean clear);
    }

    /**
     * Fired when the dialog's overlay controls are driven by an upstream renderer
     * (the object-filter preview flow). The dialog forwards the requested state instead
     * of rendering the overlay locally, because it lacks the removed-label filter data.
     */
    interface OverlayChoiceListener {
        void overlayToggleChanged(boolean selected);
        void overlaySourceChanged(boolean rawSource);
    }

    private final ImagePreviewPanel originalPreview = new ImagePreviewPanel("Original image");
    private final ImagePreviewPanel adjustedPreview = new ImagePreviewPanel("Adjusted preview");
    private final ImagePreviewPanel extraPreview = new ImagePreviewPanel("Object map");
    private final JPanel previewsPanel = new JPanel();
    private final JPanel sourceControls = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
    private final JComboBox<String> sourceChoice = new JComboBox<String>();
    private final JPanel overlayControls = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
    private final JCheckBox overlayCheck = new JCheckBox("Overlay objects");
    private final JComboBox<String> overlaySourceChoice = new JComboBox<String>();
    private final JButton displayControlsButton = new JButton("Adjust Brightness/Contrast");
    private final JButton lutToggleButton = new JButton("Grey LUT");
    private SliceListener sliceListener;
    private SourceChoiceListener sourceChoiceListener;
    private DisplayActionListener displayActionListener;
    private ObjectClickListener objectClickListener;
    private OverlayChoiceListener overlayChoiceListener;
    private boolean syncingSlices;
    private boolean updatingSourceChoice;
    private boolean updatingOverlayControls;
    private boolean externalOverlayControl;
    private boolean extraPreviewVisible;
    private PreviewDisplaySettings originalDisplaySettings = PreviewDisplaySettings.defaultFor("Grays");
    private PreviewDisplaySettings adjustedDisplaySettings = PreviewDisplaySettings.defaultFor("Grays");
    private PreviewDisplaySettings sourceChoiceOriginalDisplaySettings =
            PreviewDisplaySettings.defaultFor("Grays");
    private PreviewDisplaySettings sourceChoiceFilteredDisplaySettings =
            PreviewDisplaySettings.defaultFor("Grays");
    private PreviewPairPanel.SourceMode sourceChoiceMode = PreviewPairPanel.SourceMode.RAW;
    private ImagePlus originalImage;
    private ImagePlus adjustedImage;
    private ImagePlus extraDisplayImage;
    private ImagePlus objectLabelImage;
    private ImagePlus sourceChoiceOriginalImage;
    private ImagePlus sourceChoiceFilteredImage;
    private ImagePlus generatedOverlayImage;

    public LargePreviewDialog(Window owner) {
        super(owner, "Large preview", ModalityType.MODELESS);
        installModalExclusion();
        setDefaultCloseOperation(HIDE_ON_CLOSE);
        setLayout(new BorderLayout(8, 8));
        add(buildPreviews(), BorderLayout.CENTER);
        add(buildFooter(), BorderLayout.SOUTH);
        wireSliceSync();
        wireObjectClicks();
        wireSourceControls();
        wireOverlayControls();
        wireDisplayActionControls();
        pack();
        setMinimumSize(new Dimension(820, 520));
        sizeNearDesktop();
    }

    void setSliceListener(SliceListener sliceListener) {
        this.sliceListener = sliceListener;
    }

    void setSourceChoiceListener(SourceChoiceListener sourceChoiceListener) {
        this.sourceChoiceListener = sourceChoiceListener;
    }

    void setDisplayActionListener(DisplayActionListener displayActionListener) {
        this.displayActionListener = displayActionListener;
    }

    public void setObjectClickListener(ObjectClickListener objectClickListener) {
        this.objectClickListener = objectClickListener;
    }

    void setOverlayChoiceListener(OverlayChoiceListener overlayChoiceListener) {
        this.overlayChoiceListener = overlayChoiceListener;
    }

    /**
     * Lets an upstream renderer (object-filter preview) own the overlay controls. When
     * {@code external} is true the controls reflect the supplied state and any user change
     * is forwarded through {@link OverlayChoiceListener} rather than rendered locally; when
     * false the dialog returns to rendering its own overlay from the raw label map.
     */
    void setExternalOverlayState(boolean external,
                                 boolean toggleEnabled,
                                 boolean selected,
                                 boolean sourceEnabled,
                                 boolean rawSource) {
        externalOverlayControl = external;
        if (!external) {
            updateOverlayControls();
            return;
        }
        updatingOverlayControls = true;
        try {
            overlayControls.setVisible(true);
            overlayCheck.setSelected(selected);
            overlayCheck.setEnabled(toggleEnabled);
            overlaySourceChoice.setSelectedItem(rawSource ? "Raw image" : "Filtered image");
            overlaySourceChoice.setEnabled(sourceEnabled);
        } finally {
            updatingOverlayControls = false;
        }
        overlayControls.revalidate();
        overlayControls.repaint();
    }

    void setDisplayActionState(String lutButtonText, String lutButtonTooltip) {
        displayControlsButton.setVisible(true);
        displayControlsButton.setEnabled(true);
        lutToggleButton.setVisible(true);
        lutToggleButton.setEnabled(true);
        lutToggleButton.setText(lutButtonText == null || lutButtonText.trim().isEmpty()
                ? "Grey LUT"
                : lutButtonText);
        lutToggleButton.setToolTipText(lutButtonTooltip);
    }

    void setSourceChoices(ImagePlus originalSource,
                          PreviewDisplaySettings originalSettings,
                          ImagePlus filteredSource,
                          PreviewDisplaySettings filteredSettings,
                          PreviewPairPanel.SourceMode selectedMode) {
        sourceChoiceOriginalImage = originalSource;
        sourceChoiceFilteredImage = filteredSource;
        sourceChoiceOriginalDisplaySettings = safeDisplaySettings(originalSettings);
        sourceChoiceFilteredDisplaySettings = safeDisplaySettings(filteredSettings);
        sourceChoiceMode = availableSourceChoiceMode(selectedMode);
        updateSourceControls();
        refreshObjectPreviewImage();
        updateOverlayControls();
    }

    void clearSourceChoices() {
        sourceChoiceOriginalImage = null;
        sourceChoiceFilteredImage = null;
        sourceChoiceOriginalDisplaySettings = PreviewDisplaySettings.defaultFor("Grays");
        sourceChoiceFilteredDisplaySettings = PreviewDisplaySettings.defaultFor("Grays");
        sourceChoiceMode = PreviewPairPanel.SourceMode.RAW;
        updateSourceControls();
        refreshObjectPreviewImage();
        updateOverlayControls();
    }

    public void setImages(ImagePlus originalImage, ImagePlus adjustedImage, int zSlice) {
        setImages(originalImage, adjustedImage, null, zSlice);
    }

    public void setImages(ImagePlus originalImage, ImagePlus adjustedImage,
                          ImagePlus extraImage, int zSlice) {
        setImages(originalImage, adjustedImage, extraImage, extraImage, zSlice);
    }

    public void setImages(ImagePlus originalImage, ImagePlus adjustedImage,
                          ImagePlus extraDisplayImage,
                          ImagePlus objectClickLabelImage,
                          int zSlice) {
        this.originalImage = originalImage;
        this.adjustedImage = adjustedImage;
        this.extraDisplayImage = extraDisplayImage;
        this.objectLabelImage = objectClickLabelImage;
        configurePreviewPanel(extraDisplayImage != null);
        originalPreview.setImage(originalImage);
        adjustedPreview.setImage(adjustedImage);
        refreshObjectPreviewImage();
        updateOverlayControls();
        setCurrentZ(zSlice);
    }

    public void setAdjustedStatusText(String text) {
        if (extraPreviewVisible) {
            extraPreview.setStatusText(text);
        } else {
            adjustedPreview.setStatusText(text);
        }
    }

    public void setDisplaySettings(PreviewDisplaySettings settings) {
        PreviewDisplaySettings safe = safeDisplaySettings(settings);
        setDisplaySettings(safe, safe);
    }

    public void setDisplaySettings(PreviewDisplaySettings originalSettings,
                                   PreviewDisplaySettings adjustedSettings) {
        originalDisplaySettings = safeDisplaySettings(originalSettings);
        adjustedDisplaySettings = safeDisplaySettings(adjustedSettings);
        originalPreview.setDisplaySettingsEnabled(true);
        adjustedPreview.setDisplaySettingsEnabled(true);
        extraPreview.setDisplaySettingsEnabled(!extraPreviewVisible);
        originalPreview.setDisplaySettings(originalDisplaySettings);
        adjustedPreview.setDisplaySettings(adjustedDisplaySettings);
        extraPreview.setDisplaySettings(adjustedDisplaySettings);
        refreshObjectPreviewImage();
    }

    void setObjectSizeGuide(ObjectSizeFilterPreview.Summary summary) {
        originalPreview.setObjectSizeGuide(summary);
        adjustedPreview.setObjectSizeGuide(summary);
        extraPreview.setObjectSizeGuide(summary);
    }

    public void setCurrentZ(int zSlice) {
        if (syncingSlices) return;
        syncingSlices = true;
        try {
            int clamped = PreviewPairPanel.clampSharedZ(
                    zSlice,
                    originalPreview.getSliceCount(),
                    adjustedPreview.getSliceCount(),
                    extraPreviewVisible ? extraPreview.getSliceCount() : Integer.MAX_VALUE);
            originalPreview.setCurrentZ(clamped);
            adjustedPreview.setCurrentZ(clamped);
            extraPreview.setCurrentZ(clamped);
        } finally {
            syncingSlices = false;
        }
    }

    int getCurrentZForTest() {
        return originalPreview.getCurrentZ();
    }

    Window ownerForTest() {
        return getOwner();
    }

    int visiblePreviewCountForTest() {
        return extraPreviewVisible ? 3 : 2;
    }

    boolean overlayControlsVisibleForTest() {
        return overlayControls.isVisible();
    }

    boolean overlayCheckEnabledForTest() {
        return overlayCheck.isEnabled();
    }

    boolean overlayCheckSelectedForTest() {
        return overlayCheck.isSelected();
    }

    boolean overlaySourceEnabledForTest() {
        return overlaySourceChoice.isEnabled();
    }

    void clickOverlayCheckForTest() {
        overlayCheck.doClick();
    }

    void selectOverlaySourceFromUiForTest(String label) {
        overlaySourceChoice.setSelectedItem(label);
    }

    boolean sourceControlsVisibleForTest() {
        return sourceControls.isVisible();
    }

    String selectedSourceChoiceForTest() {
        Object selected = sourceChoice.getSelectedItem();
        return selected == null ? "" : selected.toString();
    }

    void setSourceChoiceForTest(String label) {
        sourceChoice.setSelectedItem(label);
    }

    void setOverlaySelectedForTest(boolean selected) {
        overlayCheck.setSelected(selected);
        refreshObjectPreviewImage();
        updateOverlayControls();
    }

    void setOverlaySourceForTest(String sourceLabel) {
        overlaySourceChoice.setSelectedItem(sourceLabel);
        refreshObjectPreviewImage();
    }

    String extraPreviewTitleForTest() {
        return extraPreview.titleTextForTest();
    }

    JButton displayControlsButtonForTest() {
        return displayControlsButton;
    }

    JButton lutToggleButtonForTest() {
        return lutToggleButton;
    }

    ij.process.ImageProcessor extraPreviewRenderedProcessorForTest() {
        return extraPreview.renderedProcessorForTest();
    }

    void fireExtraPreviewClickForTest(double x, double y, int z, int button, int modifiers) {
        extraPreview.firePixelClickForTest(x, y, z, button, modifiers);
    }

    void raiseForUser() {
        setVisible(true);
        boolean previousAlwaysOnTop = isAlwaysOnTop();
        try {
            setAlwaysOnTop(true);
        } catch (SecurityException ignored) {
            // Best-effort only; toFront still handles normal desktop cases.
        }
        toFront();
        requestFocus();
        requestFocusInWindow();
        try {
            setAlwaysOnTop(previousAlwaysOnTop);
        } catch (SecurityException ignored) {
            // Leave focus behavior unchanged if the desktop rejects the change.
        }
    }

    private JPanel buildPreviews() {
        previewsPanel.setBorder(BorderFactory.createEmptyBorder(8, 8, 0, 8));
        configurePreviewPanel(false);
        return previewsPanel;
    }

    private void configurePreviewPanel(boolean showExtraPreview) {
        if (extraPreviewVisible == showExtraPreview && previewsPanel.getComponentCount() > 0) {
            return;
        }
        extraPreviewVisible = showExtraPreview;
        previewsPanel.removeAll();
        previewsPanel.setLayout(new GridLayout(1, showExtraPreview ? 3 : 2, 8, 0));
        previewsPanel.setPreferredSize(new Dimension(showExtraPreview ? 1320 : 1080, 620));
        previewsPanel.add(originalPreview);
        previewsPanel.add(adjustedPreview);
        if (showExtraPreview) {
            previewsPanel.add(extraPreview);
        } else {
            extraPreview.setImage(null);
            extraPreview.setStatusText(null);
        }
        previewsPanel.revalidate();
        previewsPanel.repaint();
    }

    private JPanel buildFooter() {
        JPanel footer = new JPanel(new BorderLayout(8, 8));
        footer.setBorder(BorderFactory.createEmptyBorder(0, 8, 8, 8));

        JPanel leftControls = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        leftControls.setOpaque(false);

        sourceChoice.addItem("Original");
        sourceChoice.addItem("Filtered");
        sourceControls.setOpaque(false);
        sourceControls.add(new JLabel("Source:"));
        sourceControls.add(sourceChoice);
        sourceControls.setVisible(false);
        leftControls.add(sourceControls);

        overlaySourceChoice.addItem("Filtered image");
        overlaySourceChoice.addItem("Raw image");
        overlayControls.setOpaque(false);
        overlayCheck.setOpaque(false);
        overlayControls.add(overlayCheck);
        overlayControls.add(new JLabel("over"));
        overlayControls.add(overlaySourceChoice);
        overlayControls.setVisible(false);
        leftControls.add(overlayControls);
        footer.add(leftControls, BorderLayout.WEST);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        buttons.setOpaque(false);
        buttons.add(displayControlsButton);
        buttons.add(lutToggleButton);
        JButton close = new JButton("Close");
        close.addActionListener(e -> setVisible(false));
        buttons.add(close);
        footer.add(buttons, BorderLayout.EAST);
        return footer;
    }

    private void wireSliceSync() {
        ImagePreviewPanel.ZSliceChangeListener listener = new ImagePreviewPanel.ZSliceChangeListener() {
            @Override public void zSliceChanged(ImagePreviewPanel source, int zSlice) {
                setCurrentZ(zSlice);
                if (sliceListener != null) {
                    sliceListener.zSliceChanged(getCurrentZForTest());
                }
            }
        };
        originalPreview.setZSliceChangeListener(listener);
        adjustedPreview.setZSliceChangeListener(listener);
        extraPreview.setZSliceChangeListener(listener);
    }

    private void wireObjectClicks() {
        ImagePreviewPanel.PixelClickListener listener = new ImagePreviewPanel.PixelClickListener() {
            @Override public void pixelClicked(ImagePreviewPanel src, double imageX, double imageY,
                                               int z, int button, int modifiers) {
                dispatchObjectClick(imageX, imageY, z, button, modifiers);
            }
        };
        originalPreview.setPixelClickListener(listener);
        adjustedPreview.setPixelClickListener(listener);
        extraPreview.setPixelClickListener(listener);
    }

    private void dispatchObjectClick(double x, double y, int z, int button, int modifiers) {
        if (objectClickListener == null) return;
        ObjectClickDispatcher.dispatch(objectLabelImage, x, y, z, button, modifiers,
                new ObjectClickDispatcher.Handler() {
                    @Override public void objectClicked(int label, int clickZ,
                                                        double clickX, double clickY,
                                                        boolean positive, boolean clear) {
                        objectClickListener.objectClicked(label, clickZ, clickX, clickY,
                                positive, clear);
                    }
                });
    }

    private void wireSourceControls() {
        sourceChoice.addActionListener(e -> {
            if (updatingSourceChoice) return;
            PreviewPairPanel.SourceMode selected = sourceChoice.getSelectedIndex() == 1
                    ? PreviewPairPanel.SourceMode.FILTERED
                    : PreviewPairPanel.SourceMode.RAW;
            sourceChoiceMode = availableSourceChoiceMode(selected);
            updateSourceControls();
            refreshObjectPreviewImage();
            updateOverlayControls();
            if (sourceChoiceListener != null) {
                sourceChoiceListener.sourceChoiceChanged(sourceChoiceMode);
            }
        });
    }

    private void wireOverlayControls() {
        overlayCheck.addActionListener(e -> {
            if (updatingOverlayControls) return;
            if (externalOverlayControl) {
                if (overlayChoiceListener != null) {
                    overlayChoiceListener.overlayToggleChanged(overlayCheck.isSelected());
                }
                return;
            }
            refreshObjectPreviewImage();
            updateOverlayControls();
        });
        overlaySourceChoice.addActionListener(e -> {
            if (updatingOverlayControls) return;
            if (externalOverlayControl) {
                if (overlayChoiceListener != null) {
                    overlayChoiceListener.overlaySourceChanged(isOverlaySourceRawSelected());
                }
                return;
            }
            refreshObjectPreviewImage();
        });
    }

    private boolean isOverlaySourceRawSelected() {
        Object selected = overlaySourceChoice.getSelectedItem();
        return selected != null && "Raw image".equals(selected.toString());
    }

    private void wireDisplayActionControls() {
        displayControlsButton.addActionListener(e -> {
            if (displayActionListener != null) {
                displayActionListener.adjustBrightnessContrastRequested();
            }
        });
        lutToggleButton.addActionListener(e -> {
            if (displayActionListener != null) {
                displayActionListener.lutToggleRequested();
            }
        });
    }

    private void refreshObjectPreviewImage() {
        if (!extraPreviewVisible || extraDisplayImage == null) {
            extraPreview.setImage(null);
            closeGeneratedOverlayImage();
            return;
        }

        ImagePlus displayImage = extraDisplayImage;
        ImagePlus oldOverlay = generatedOverlayImage;
        generatedOverlayImage = null;
        if (overlayCheck.isSelected() && !extraDisplayReady()) {
            ImagePlus source = selectedOverlaySourceImage();
            ImagePlus overlay = ObjectOverlayRenderer.renderOverlay(source, objectLabelImage,
                    selectedOverlaySourceDisplaySettings());
            if (overlay != null) {
                displayImage = overlay;
                generatedOverlayImage = overlay;
            }
        }
        extraPreview.setImage(displayImage);
        if (oldOverlay != null && oldOverlay != generatedOverlayImage) {
            oldOverlay.flush();
        }
    }

    private ImagePlus selectedOverlaySourceImage() {
        Object selected = overlaySourceChoice.getSelectedItem();
        boolean raw = selected != null && "Raw image".equals(selected.toString());
        if (hasSourceChoiceImages()) {
            ImagePlus source = raw ? sourceChoiceOriginalImage : sourceChoiceFilteredImage;
            if (source != null) return source;
            return raw ? sourceChoiceFilteredImage : sourceChoiceOriginalImage;
        }
        ImagePlus preferred = raw ? originalImage : adjustedImage;
        if (preferred != null) return preferred;
        return raw ? adjustedImage : originalImage;
    }

    private PreviewDisplaySettings selectedOverlaySourceDisplaySettings() {
        Object selected = overlaySourceChoice.getSelectedItem();
        boolean raw = selected != null && "Raw image".equals(selected.toString());
        if (hasSourceChoiceImages()) {
            if (raw) {
                return sourceChoiceOriginalImage != null
                        ? sourceChoiceOriginalDisplaySettings
                        : sourceChoiceFilteredDisplaySettings;
            }
            return sourceChoiceFilteredImage != null
                    ? sourceChoiceFilteredDisplaySettings
                    : sourceChoiceOriginalDisplaySettings;
        }
        if (raw) {
            return originalImage != null ? originalDisplaySettings : adjustedDisplaySettings;
        }
        return adjustedImage != null ? adjustedDisplaySettings : originalDisplaySettings;
    }

    private void updateSourceControls() {
        boolean visible = sourceChoiceOriginalImage != null && sourceChoiceFilteredImage != null;
        sourceControls.setVisible(visible);
        sourceChoice.setEnabled(visible);
        updatingSourceChoice = true;
        try {
            sourceChoice.setSelectedIndex(sourceChoiceMode == PreviewPairPanel.SourceMode.FILTERED
                    ? 1
                    : 0);
        } finally {
            updatingSourceChoice = false;
        }
        sourceControls.revalidate();
        sourceControls.repaint();
    }

    private PreviewPairPanel.SourceMode availableSourceChoiceMode(
            PreviewPairPanel.SourceMode requestedMode) {
        PreviewPairPanel.SourceMode safeMode =
                requestedMode == PreviewPairPanel.SourceMode.FILTERED
                        ? PreviewPairPanel.SourceMode.FILTERED
                        : PreviewPairPanel.SourceMode.RAW;
        if (safeMode == PreviewPairPanel.SourceMode.FILTERED
                && sourceChoiceFilteredImage != null) {
            return PreviewPairPanel.SourceMode.FILTERED;
        }
        if (safeMode == PreviewPairPanel.SourceMode.RAW
                && sourceChoiceOriginalImage != null) {
            return PreviewPairPanel.SourceMode.RAW;
        }
        if (sourceChoiceOriginalImage != null) return PreviewPairPanel.SourceMode.RAW;
        if (sourceChoiceFilteredImage != null) return PreviewPairPanel.SourceMode.FILTERED;
        return safeMode;
    }

    private boolean hasSourceChoiceImages() {
        return sourceChoiceOriginalImage != null || sourceChoiceFilteredImage != null;
    }

    private static PreviewDisplaySettings safeDisplaySettings(PreviewDisplaySettings settings) {
        return settings == null ? PreviewDisplaySettings.defaultFor("Grays") : settings;
    }

    private void updateOverlayControls() {
        if (externalOverlayControl) return;
        boolean hasObjectMap = extraPreviewVisible && extraDisplayImage != null;
        boolean hasSource = originalImage != null || adjustedImage != null
                || hasSourceChoiceImages();
        overlayControls.setVisible(hasObjectMap);
        overlayCheck.setEnabled(hasObjectMap && hasSource && !extraDisplayReady());
        overlaySourceChoice.setEnabled(hasObjectMap && hasSource && overlayCheck.isSelected()
                && !extraDisplayReady());
        overlayControls.revalidate();
        overlayControls.repaint();
    }

    private boolean extraDisplayReady() {
        return extraDisplayImage != null && extraDisplayImage != objectLabelImage;
    }

    private void sizeNearDesktop() {
        if (GraphicsEnvironment.isHeadless()) return;
        Rectangle bounds = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
        Dimension minimum = getMinimumSize();
        Dimension packed = getSize();
        int targetWidth = (int) Math.round(bounds.width * INITIAL_DESKTOP_WIDTH_FRACTION);
        int targetHeight = (int) Math.round(bounds.height * INITIAL_DESKTOP_HEIGHT_FRACTION);
        int width = Math.min(bounds.width, Math.max(Math.max(minimum.width, packed.width), targetWidth));
        int height = Math.min(bounds.height, Math.max(Math.max(minimum.height, packed.height), targetHeight));
        int x = bounds.x + Math.max(0, (bounds.width - width) / 2);
        int y = bounds.y + Math.max(0, (bounds.height - height) / 2);
        setBounds(x, y, width, height);
    }

    private void installModalExclusion() {
        try {
            setModalExclusionType(Dialog.ModalExclusionType.APPLICATION_EXCLUDE);
        } catch (SecurityException ignored) {
            // Some desktops disallow this; the dialog still remains modeless.
        }
    }

    private void closeGeneratedOverlayImage() {
        if (generatedOverlayImage != null) {
            generatedOverlayImage.flush();
            generatedOverlayImage = null;
        }
    }

    @Override public void dispose() {
        sliceListener = null;
        sourceChoiceListener = null;
        displayActionListener = null;
        objectClickListener = null;
        overlayChoiceListener = null;
        originalPreview.setZSliceChangeListener(null);
        adjustedPreview.setZSliceChangeListener(null);
        extraPreview.setZSliceChangeListener(null);
        originalPreview.setPixelClickListener(null);
        adjustedPreview.setPixelClickListener(null);
        extraPreview.setPixelClickListener(null);
        closeGeneratedOverlayImage();
        super.dispose();
    }
}
