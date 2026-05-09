package flash.pipeline.ui.preview;

import ij.ImagePlus;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Dialog;
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
    private SliceListener sliceListener;
    private boolean syncingSlices;
    private boolean extraPreviewVisible;
    private PreviewDisplaySettings displaySettings = PreviewDisplaySettings.defaultFor("Grays");

    public LargePreviewDialog(Window owner) {
        super(owner, "Large preview", ModalityType.MODELESS);
        installModalExclusion();
        setDefaultCloseOperation(HIDE_ON_CLOSE);
        setLayout(new BorderLayout(8, 8));
        add(buildPreviews(), BorderLayout.CENTER);
        add(buildFooter(), BorderLayout.SOUTH);
        wireSliceSync();
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
        configurePreviewPanel(extraImage != null);
        originalPreview.setImage(originalImage);
        adjustedPreview.setImage(adjustedImage);
        extraPreview.setImage(extraImage);
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
        JPanel footer = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 8, 8));
        JButton close = new JButton("Close");
        close.addActionListener(e -> setVisible(false));
        footer.add(close);
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
}
