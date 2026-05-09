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

    private final ImagePreviewPanel originalPreview = new ImagePreviewPanel("Original image");
    private final ImagePreviewPanel adjustedPreview = new ImagePreviewPanel("Adjusted preview");
    private final ImagePreviewPanel extraPreview = new ImagePreviewPanel("Object map");
    private final JPanel previewsPanel = new JPanel();
    private final JPanel overlayControls = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
    private final JCheckBox overlayCheck = new JCheckBox("Overlay objects");
    private final JComboBox<String> overlaySourceChoice = new JComboBox<String>();
    private SliceListener sliceListener;
    private boolean syncingSlices;
    private boolean extraPreviewVisible;
    private PreviewDisplaySettings displaySettings = PreviewDisplaySettings.defaultFor("Grays");
    private ImagePlus originalImage;
    private ImagePlus adjustedImage;
    private ImagePlus objectLabelImage;
    private ImagePlus generatedOverlayImage;

    public LargePreviewDialog(Window owner) {
        super(owner, "Large preview", ModalityType.MODELESS);
        installModalExclusion();
        setDefaultCloseOperation(HIDE_ON_CLOSE);
        setLayout(new BorderLayout(8, 8));
        add(buildPreviews(), BorderLayout.CENTER);
        add(buildFooter(), BorderLayout.SOUTH);
        wireSliceSync();
        wireOverlayControls();
        pack();
        setMinimumSize(new Dimension(820, 520));
        sizeNearDesktop();
    }

    void setSliceListener(SliceListener sliceListener) {
        this.sliceListener = sliceListener;
    }

    public void setImages(ImagePlus originalImage, ImagePlus adjustedImage, int zSlice) {
        setImages(originalImage, adjustedImage, null, zSlice);
    }

    public void setImages(ImagePlus originalImage, ImagePlus adjustedImage,
                          ImagePlus extraImage, int zSlice) {
        this.originalImage = originalImage;
        this.adjustedImage = adjustedImage;
        this.objectLabelImage = extraImage;
        configurePreviewPanel(extraImage != null);
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
        displaySettings = settings == null ? PreviewDisplaySettings.defaultFor("Grays") : settings;
        originalPreview.setDisplaySettingsEnabled(true);
        adjustedPreview.setDisplaySettingsEnabled(true);
        extraPreview.setDisplaySettingsEnabled(!extraPreviewVisible);
        originalPreview.setDisplaySettings(displaySettings);
        adjustedPreview.setDisplaySettings(displaySettings);
        extraPreview.setDisplaySettings(displaySettings);
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

        overlaySourceChoice.addItem("Filtered image");
        overlaySourceChoice.addItem("Raw image");
        overlayControls.setOpaque(false);
        overlayCheck.setOpaque(false);
        overlayControls.add(overlayCheck);
        overlayControls.add(new JLabel("over"));
        overlayControls.add(overlaySourceChoice);
        overlayControls.setVisible(false);
        footer.add(overlayControls, BorderLayout.WEST);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        buttons.setOpaque(false);
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

    private void wireOverlayControls() {
        overlayCheck.addActionListener(e -> {
            refreshObjectPreviewImage();
            updateOverlayControls();
        });
        overlaySourceChoice.addActionListener(e -> refreshObjectPreviewImage());
    }

    private void refreshObjectPreviewImage() {
        if (!extraPreviewVisible || objectLabelImage == null) {
            extraPreview.setImage(null);
            closeGeneratedOverlayImage();
            return;
        }

        ImagePlus displayImage = objectLabelImage;
        ImagePlus oldOverlay = generatedOverlayImage;
        generatedOverlayImage = null;
        if (overlayCheck.isSelected()) {
            ImagePlus source = selectedOverlaySourceImage();
            ImagePlus overlay = ObjectOverlayRenderer.renderOverlay(source, objectLabelImage);
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
        ImagePlus preferred = raw ? originalImage : adjustedImage;
        if (preferred != null) return preferred;
        return raw ? adjustedImage : originalImage;
    }

    private void updateOverlayControls() {
        boolean hasObjectMap = extraPreviewVisible && objectLabelImage != null;
        boolean hasSource = originalImage != null || adjustedImage != null;
        overlayControls.setVisible(hasObjectMap);
        overlayCheck.setEnabled(hasObjectMap && hasSource);
        overlaySourceChoice.setEnabled(hasObjectMap && hasSource && overlayCheck.isSelected());
        overlayControls.revalidate();
        overlayControls.repaint();
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
        closeGeneratedOverlayImage();
        super.dispose();
    }
}
