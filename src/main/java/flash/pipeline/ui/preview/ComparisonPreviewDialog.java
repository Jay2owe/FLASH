package flash.pipeline.ui.preview;

import flash.pipeline.objects.LabelIndex;
import ij.ImagePlus;

import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Dialog;
import java.awt.FlowLayout;
import java.awt.GraphicsEnvironment;
import java.awt.GridLayout;
import java.awt.Rectangle;
import java.awt.Window;
import java.awt.event.MouseEvent;

public final class ComparisonPreviewDialog extends JDialog {

    private static final double INITIAL_DESKTOP_WIDTH_FRACTION = 0.78;
    private static final double INITIAL_DESKTOP_HEIGHT_FRACTION = 0.76;

    interface SliceListener {
        void zSliceChanged(int zSlice);
    }

    interface DisplayActionListener {
        void adjustBrightnessContrastRequested();
        void lutToggleRequested();
    }

    interface RestoreActionListener {
        void restorePreviousRequested();
    }

    public interface ObjectClickListener {
        void objectClicked(int label, int z, double x, double y,
                           boolean positive, boolean clear);
    }

    private final ImagePreviewPanel currentPreview = new ImagePreviewPanel("Current preview");
    private final ImagePreviewPanel previousPreview = new ImagePreviewPanel("Previous preview");
    private final JPanel sourceControls = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
    private final JRadioButton sourceFilteredRadio = new JRadioButton("Filtered", true);
    private final JRadioButton sourceRawRadio = new JRadioButton("Raw");
    private final JCheckBox overlayCheck = new JCheckBox("Overlay objects");
    private final JButton restorePreviousButton = new JButton("Use previous settings");
    private final JButton displayControlsButton = new JButton("Adjust Brightness/Contrast");
    private final JButton lutToggleButton = new JButton("Grey LUT");

    private SliceListener sliceListener;
    private DisplayActionListener displayActionListener;
    private RestoreActionListener restoreActionListener;
    private ObjectClickListener objectClickListener;
    private boolean syncingSlices;
    private boolean updatingSourceControls;
    private ImagePlus rawSourceImage;
    private ImagePlus filteredSourceImage;
    private PreviewDisplaySettings rawDisplaySettings = PreviewDisplaySettings.defaultFor("Grays");
    private PreviewDisplaySettings filteredDisplaySettings = PreviewDisplaySettings.defaultFor("Grays");
    private PreviewPairPanel.SourceMode sourceMode = PreviewPairPanel.SourceMode.FILTERED;
    private ImagePlus currentLabelImage;
    private ImagePlus previousLabelImage;
    private ImagePlus generatedCurrentOverlayImage;
    private ImagePlus generatedPreviousOverlayImage;

    public ComparisonPreviewDialog(Window owner) {
        super(owner, "Compare previews", ModalityType.MODELESS);
        installModalExclusion();
        setDefaultCloseOperation(HIDE_ON_CLOSE);
        setLayout(new BorderLayout(8, 8));
        add(buildPreviews(), BorderLayout.CENTER);
        add(buildFooter(), BorderLayout.SOUTH);
        wireSliceSync();
        wireObjectClicks();
        wireSourceControls();
        wireDisplayActionControls();
        wireRestoreActionControl();
        pack();
        setMinimumSize(new Dimension(820, 500));
        sizeNearDesktop();
    }

    void setSliceListener(SliceListener sliceListener) {
        this.sliceListener = sliceListener;
    }

    void setDisplayActionListener(DisplayActionListener displayActionListener) {
        this.displayActionListener = displayActionListener;
    }

    void setRestoreActionListener(RestoreActionListener restoreActionListener) {
        this.restoreActionListener = restoreActionListener;
        updateRestoreButtonState();
    }

    public void setObjectClickListener(ObjectClickListener objectClickListener) {
        this.objectClickListener = objectClickListener;
    }

    void setRestoreActionState(boolean available, String tooltip) {
        restorePreviousButton.setVisible(available);
        restorePreviousButton.setEnabled(available && restoreActionListener != null);
        restorePreviousButton.setToolTipText(tooltip);
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

    void setSourceChoices(ImagePlus rawSource,
                          PreviewDisplaySettings rawSettings,
                          ImagePlus filteredSource,
                          PreviewDisplaySettings filteredSettings) {
        rawSourceImage = rawSource;
        filteredSourceImage = filteredSource;
        rawDisplaySettings = safeDisplaySettings(rawSettings);
        filteredDisplaySettings = safeDisplaySettings(filteredSettings);
        sourceMode = availableSourceMode(sourceMode);
        updateSourceControls();
        refreshPreviewImages();
    }

    void clearSourceChoices() {
        rawSourceImage = null;
        filteredSourceImage = null;
        rawDisplaySettings = PreviewDisplaySettings.defaultFor("Grays");
        filteredDisplaySettings = PreviewDisplaySettings.defaultFor("Grays");
        sourceMode = PreviewPairPanel.SourceMode.FILTERED;
        updateSourceControls();
        refreshPreviewImages();
    }

    void setImages(ImagePlus currentImage, ImagePlus previousImage, int zSlice) {
        currentLabelImage = currentImage;
        previousLabelImage = previousImage;
        refreshPreviewImages();
        updateOverlayControls();
        setCurrentZ(zSlice);
    }

    void setPreviewStatus(String currentStatus, String previousStatus) {
        currentPreview.setStatusText(currentStatus);
        previousPreview.setStatusText(previousStatus);
    }

    void setObjectSizeGuide(ObjectSizeFilterPreview.Summary summary) {
        currentPreview.setObjectSizeGuide(summary);
        previousPreview.setObjectSizeGuide(summary);
    }

    void setCurrentZ(int zSlice) {
        if (syncingSlices) return;
        syncingSlices = true;
        try {
            int clamped = PreviewPairPanel.clampSharedZ(
                    zSlice,
                    currentPreview.getSliceCount(),
                    previousPreview.getSliceCount());
            currentPreview.setCurrentZ(clamped);
            previousPreview.setCurrentZ(clamped);
        } finally {
            syncingSlices = false;
        }
    }

    int getCurrentZForTest() {
        return currentPreview.getCurrentZ();
    }

    Window ownerForTest() {
        return getOwner();
    }

    boolean sourceControlsVisibleForTest() {
        return sourceControls.isVisible();
    }

    int sourceRadioButtonCountForTest() {
        return 2;
    }

    PreviewPairPanel.SourceMode sourceModeForTest() {
        return sourceMode;
    }

    void setSourceModeForTest(PreviewPairPanel.SourceMode mode) {
        sourceMode = availableSourceMode(mode);
        updateSourceControls();
        refreshPreviewImages();
    }

    void setOverlaySelectedForTest(boolean selected) {
        overlayCheck.setSelected(selected);
        refreshPreviewImages();
        updateOverlayControls();
    }

    JButton displayControlsButtonForTest() {
        return displayControlsButton;
    }

    JButton lutToggleButtonForTest() {
        return lutToggleButton;
    }

    JButton restorePreviousButtonForTest() {
        return restorePreviousButton;
    }

    ij.process.ImageProcessor currentRenderedProcessorForTest() {
        return currentPreview.renderedProcessorForTest();
    }

    ij.process.ImageProcessor previousRenderedProcessorForTest() {
        return previousPreview.renderedProcessorForTest();
    }

    void raiseForUser() {
        setVisible(true);
        boolean previousAlwaysOnTop = isAlwaysOnTop();
        try {
            setAlwaysOnTop(true);
        } catch (SecurityException ignored) {
            // Best effort only; toFront still handles normal desktop cases.
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

    @Override
    public void dispose() {
        sliceListener = null;
        displayActionListener = null;
        restoreActionListener = null;
        objectClickListener = null;
        currentPreview.setZSliceChangeListener(null);
        previousPreview.setZSliceChangeListener(null);
        currentPreview.setPixelClickListener(null);
        previousPreview.setPixelClickListener(null);
        closeGeneratedOverlayImages();
        super.dispose();
    }

    private JPanel buildPreviews() {
        JPanel previews = new JPanel(new GridLayout(1, 2, 8, 0));
        previews.setBorder(BorderFactory.createEmptyBorder(8, 8, 0, 8));
        previews.setPreferredSize(new Dimension(1100, 620));
        previews.add(currentPreview);
        previews.add(previousPreview);
        return previews;
    }

    private JPanel buildFooter() {
        JPanel footer = new JPanel(new BorderLayout(8, 8));
        footer.setBorder(BorderFactory.createEmptyBorder(0, 8, 8, 8));

        JPanel leftControls = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        leftControls.setOpaque(false);

        sourceControls.setOpaque(false);
        sourceFilteredRadio.setOpaque(false);
        sourceRawRadio.setOpaque(false);
        ButtonGroup group = new ButtonGroup();
        group.add(sourceFilteredRadio);
        group.add(sourceRawRadio);
        sourceControls.add(new JLabel("Source:"));
        sourceControls.add(sourceFilteredRadio);
        sourceControls.add(sourceRawRadio);
        leftControls.add(sourceControls);

        overlayCheck.setOpaque(false);
        leftControls.add(overlayCheck);
        footer.add(leftControls, BorderLayout.WEST);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        buttons.setOpaque(false);
        restorePreviousButton.setVisible(false);
        buttons.add(restorePreviousButton);
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
        currentPreview.setZSliceChangeListener(listener);
        previousPreview.setZSliceChangeListener(listener);
    }

    private void wireObjectClicks() {
        currentPreview.setPixelClickListener(new ImagePreviewPanel.PixelClickListener() {
            @Override public void pixelClicked(ImagePreviewPanel src, double imageX, double imageY,
                                               int z, int button, int modifiers) {
                dispatchObjectClick(currentLabelImage, imageX, imageY, z, button, modifiers);
            }
        });
        previousPreview.setPixelClickListener(new ImagePreviewPanel.PixelClickListener() {
            @Override public void pixelClicked(ImagePreviewPanel src, double imageX, double imageY,
                                               int z, int button, int modifiers) {
                dispatchObjectClick(previousLabelImage, imageX, imageY, z, button, modifiers);
            }
        });
    }

    private void dispatchObjectClick(ImagePlus labelImage, double x, double y,
                                     int z, int button, int modifiers) {
        if (objectClickListener == null || labelImage == null) return;
        int label = LabelIndex.getLabelAt(labelImage, (int) x, (int) y, z);
        if (label <= 0) return;
        boolean clear = button == MouseEvent.BUTTON3;
        boolean left = button == MouseEvent.BUTTON1;
        if (!clear && !left) return;
        boolean positive = left && (modifiers & MouseEvent.SHIFT_DOWN_MASK) != 0;
        objectClickListener.objectClicked(label, z, x, y, positive, clear);
    }

    private void wireSourceControls() {
        sourceFilteredRadio.addActionListener(e -> sourceModeChanged(PreviewPairPanel.SourceMode.FILTERED));
        sourceRawRadio.addActionListener(e -> sourceModeChanged(PreviewPairPanel.SourceMode.RAW));
        overlayCheck.addActionListener(e -> {
            refreshPreviewImages();
            updateOverlayControls();
        });
    }

    private void sourceModeChanged(PreviewPairPanel.SourceMode requestedMode) {
        if (updatingSourceControls) return;
        sourceMode = availableSourceMode(requestedMode);
        updateSourceControls();
        refreshPreviewImages();
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

    private void wireRestoreActionControl() {
        restorePreviousButton.addActionListener(e -> {
            if (restoreActionListener != null) {
                restoreActionListener.restorePreviousRequested();
            }
        });
        updateRestoreButtonState();
    }

    private void updateRestoreButtonState() {
        restorePreviousButton.setEnabled(restorePreviousButton.isVisible()
                && restoreActionListener != null);
    }

    private void refreshPreviewImages() {
        ImagePlus oldCurrent = generatedCurrentOverlayImage;
        ImagePlus oldPrevious = generatedPreviousOverlayImage;
        generatedCurrentOverlayImage = null;
        generatedPreviousOverlayImage = null;

        ImagePlus currentDisplay = currentLabelImage;
        ImagePlus previousDisplay = previousLabelImage;
        if (overlayCheck.isSelected()) {
            ImagePlus source = selectedSourceImage();
            PreviewDisplaySettings settings = selectedSourceDisplaySettings();
            ImagePlus currentOverlay = ObjectOverlayRenderer.renderOverlay(
                    source, currentLabelImage, settings);
            ImagePlus previousOverlay = ObjectOverlayRenderer.renderOverlay(
                    source, previousLabelImage, settings);
            if (currentOverlay != null) {
                currentDisplay = currentOverlay;
                generatedCurrentOverlayImage = currentOverlay;
            }
            if (previousOverlay != null) {
                previousDisplay = previousOverlay;
                generatedPreviousOverlayImage = previousOverlay;
            }
        }

        currentPreview.setDisplaySettingsEnabled(false);
        previousPreview.setDisplaySettingsEnabled(false);
        currentPreview.setImage(currentDisplay);
        previousPreview.setImage(previousDisplay);
        if (oldCurrent != null && oldCurrent != generatedCurrentOverlayImage) {
            oldCurrent.flush();
        }
        if (oldPrevious != null && oldPrevious != generatedPreviousOverlayImage) {
            oldPrevious.flush();
        }
    }

    private void updateSourceControls() {
        boolean hasRaw = rawSourceImage != null;
        boolean hasFiltered = filteredSourceImage != null;
        boolean visible = hasRaw || hasFiltered;
        sourceControls.setVisible(visible);
        updatingSourceControls = true;
        try {
            sourceMode = availableSourceMode(sourceMode);
            sourceFilteredRadio.setEnabled(hasFiltered);
            sourceRawRadio.setEnabled(hasRaw);
            sourceFilteredRadio.setSelected(sourceMode == PreviewPairPanel.SourceMode.FILTERED);
            sourceRawRadio.setSelected(sourceMode == PreviewPairPanel.SourceMode.RAW);
        } finally {
            updatingSourceControls = false;
        }
        sourceControls.revalidate();
        sourceControls.repaint();
    }

    private void updateOverlayControls() {
        boolean hasLabels = currentLabelImage != null && previousLabelImage != null;
        boolean hasSource = rawSourceImage != null || filteredSourceImage != null;
        overlayCheck.setEnabled(hasLabels && hasSource);
        repaint();
    }

    private ImagePlus selectedSourceImage() {
        if (sourceMode == PreviewPairPanel.SourceMode.RAW) {
            return rawSourceImage != null ? rawSourceImage : filteredSourceImage;
        }
        return filteredSourceImage != null ? filteredSourceImage : rawSourceImage;
    }

    private PreviewDisplaySettings selectedSourceDisplaySettings() {
        if (sourceMode == PreviewPairPanel.SourceMode.RAW) {
            return rawSourceImage != null ? rawDisplaySettings : filteredDisplaySettings;
        }
        return filteredSourceImage != null ? filteredDisplaySettings : rawDisplaySettings;
    }

    private PreviewPairPanel.SourceMode availableSourceMode(PreviewPairPanel.SourceMode requestedMode) {
        PreviewPairPanel.SourceMode safeMode =
                requestedMode == PreviewPairPanel.SourceMode.RAW
                        ? PreviewPairPanel.SourceMode.RAW
                        : PreviewPairPanel.SourceMode.FILTERED;
        if (safeMode == PreviewPairPanel.SourceMode.FILTERED && filteredSourceImage != null) {
            return PreviewPairPanel.SourceMode.FILTERED;
        }
        if (safeMode == PreviewPairPanel.SourceMode.RAW && rawSourceImage != null) {
            return PreviewPairPanel.SourceMode.RAW;
        }
        if (filteredSourceImage != null) return PreviewPairPanel.SourceMode.FILTERED;
        if (rawSourceImage != null) return PreviewPairPanel.SourceMode.RAW;
        return safeMode;
    }

    private static PreviewDisplaySettings safeDisplaySettings(PreviewDisplaySettings settings) {
        return settings == null ? PreviewDisplaySettings.defaultFor("Grays") : settings;
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

    private void closeGeneratedOverlayImages() {
        if (generatedCurrentOverlayImage != null) {
            generatedCurrentOverlayImage.flush();
            generatedCurrentOverlayImage = null;
        }
        if (generatedPreviousOverlayImage != null) {
            generatedPreviousOverlayImage.flush();
            generatedPreviousOverlayImage = null;
        }
    }
}
