package flash.pipeline.ui.preview;

import ij.ImagePlus;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.GraphicsEnvironment;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;

public final class PreviewPairPanel extends JPanel {

    public enum PreviewState {
        EMPTY,
        READY,
        STALE,
        RUNNING,
        ERROR
    }

    private final ImagePreviewPanel originalPreview;
    private final ImagePreviewPanel adjustedPreview;
    private final JButton largeViewButton = new JButton("Large view");
    private final Window owner;

    private ImagePlus originalImage;
    private ImagePlus adjustedImage;
    private PreviewState adjustedState = PreviewState.EMPTY;
    private String adjustedMessage = "";
    private int currentZ = 1;
    private boolean syncingSlices;
    private LargePreviewDialog largePreviewDialog;

    public PreviewPairPanel(String originalTitle, String adjustedTitle) {
        this(null, originalTitle, adjustedTitle);
    }

    public PreviewPairPanel(Window owner, String originalTitle, String adjustedTitle) {
        super(new BorderLayout(0, 6));
        this.owner = owner;
        this.originalPreview = new ImagePreviewPanel(originalTitle);
        this.adjustedPreview = new ImagePreviewPanel(adjustedTitle);
        setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 0));
        add(buildStackedPreviews(), BorderLayout.CENTER);
        add(buildActions(), BorderLayout.SOUTH);
        wireSliceSync();
        setAdjustedState(PreviewState.EMPTY, null);
    }

    public void setOriginal(ImagePlus image) {
        this.originalImage = image;
        originalPreview.setImage(image);
        applyCurrentZ(currentZ);
        updateLargeImages();
    }

    public void setAdjusted(ImagePlus image) {
        this.adjustedImage = image;
        adjustedPreview.setImage(image);
        if (image != null && adjustedState == PreviewState.EMPTY) {
            setAdjustedState(PreviewState.READY, null);
        } else {
            applyAdjustedStatus();
        }
        applyCurrentZ(currentZ);
        updateLargeImages();
    }

    public void setAdjustedState(PreviewState state, String message) {
        adjustedState = state == null ? PreviewState.EMPTY : state;
        adjustedMessage = message == null ? "" : message.trim();
        applyAdjustedStatus();
    }

    public int getCurrentZ() {
        return currentZ;
    }

    public void setCurrentZ(int zSlice) {
        applyCurrentZ(zSlice);
    }

    public JButton largeViewButton() {
        return largeViewButton;
    }

    PreviewState adjustedStateForTest() {
        return adjustedState;
    }

    String adjustedStatusTextForTest() {
        return adjustedPreview.statusTextForTest();
    }

    int originalZForTest() {
        return originalPreview.getCurrentZ();
    }

    int adjustedZForTest() {
        return adjustedPreview.getCurrentZ();
    }

    void setLargePreviewDialogForTest(LargePreviewDialog dialog) {
        largePreviewDialog = dialog;
        wireLargeDialog();
        updateLargeImages();
    }

    static int clampSharedZ(int requestedZ, int originalSlices, int adjustedSlices) {
        int originalCount = Math.max(1, originalSlices);
        int adjustedCount = Math.max(1, adjustedSlices);
        int sharedMax = Math.min(originalCount, adjustedCount);
        return ImagePreviewPanel.clamp(requestedZ, 1, sharedMax);
    }

    private JPanel buildStackedPreviews() {
        JPanel previews = new JPanel();
        previews.setLayout(new BoxLayout(previews, BoxLayout.Y_AXIS));
        previews.add(originalPreview);
        previews.add(adjustedPreview);
        return previews;
    }

    private JPanel buildActions() {
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        largeViewButton.addActionListener(new ActionListener() {
            @Override public void actionPerformed(ActionEvent e) {
                showLargePreview();
            }
        });
        actions.add(largeViewButton);
        return actions;
    }

    private void wireSliceSync() {
        ImagePreviewPanel.ZSliceChangeListener listener = new ImagePreviewPanel.ZSliceChangeListener() {
            @Override public void zSliceChanged(ImagePreviewPanel source, int zSlice) {
                applyCurrentZ(zSlice);
            }
        };
        originalPreview.setZSliceChangeListener(listener);
        adjustedPreview.setZSliceChangeListener(listener);
    }

    private void showLargePreview() {
        if (GraphicsEnvironment.isHeadless()) return;
        if (largePreviewDialog == null) {
            largePreviewDialog = new LargePreviewDialog(owner);
            wireLargeDialog();
        }
        updateLargeImages();
        largePreviewDialog.setLocationRelativeTo(owner);
        largePreviewDialog.setVisible(true);
    }

    private void wireLargeDialog() {
        if (largePreviewDialog == null) return;
        largePreviewDialog.setSliceListener(new LargePreviewDialog.SliceListener() {
            @Override public void zSliceChanged(int zSlice) {
                applyCurrentZ(zSlice);
            }
        });
    }

    private void applyCurrentZ(int requestedZ) {
        if (syncingSlices) return;
        syncingSlices = true;
        try {
            int originalSlices = originalImage == null ? adjustedPreview.getSliceCount() : originalPreview.getSliceCount();
            int adjustedSlices = adjustedImage == null ? originalPreview.getSliceCount() : adjustedPreview.getSliceCount();
            currentZ = clampSharedZ(requestedZ, originalSlices, adjustedSlices);
            originalPreview.setCurrentZ(currentZ);
            adjustedPreview.setCurrentZ(currentZ);
            if (largePreviewDialog != null) {
                largePreviewDialog.setCurrentZ(currentZ);
            }
        } finally {
            syncingSlices = false;
        }
    }

    private void updateLargeImages() {
        if (largePreviewDialog == null) return;
        largePreviewDialog.setImages(originalImage, adjustedImage, currentZ);
        largePreviewDialog.setAdjustedStatusText(adjustedPreview.statusTextForTest());
    }

    private void applyAdjustedStatus() {
        String status = statusText(adjustedState, adjustedMessage);
        adjustedPreview.setStatusText(status);
        if (largePreviewDialog != null) {
            largePreviewDialog.setAdjustedStatusText(status);
        }
    }

    static String statusText(PreviewState state, String message) {
        String text = message == null ? "" : message.trim();
        PreviewState safeState = state == null ? PreviewState.EMPTY : state;
        switch (safeState) {
            case READY:
                return text.isEmpty() ? "Preview ready." : text;
            case STALE:
                return text.isEmpty() ? "Preview is out of date. Press the preview button to update." : text;
            case RUNNING:
                return text.isEmpty() ? "Generating preview..." : text;
            case ERROR:
                return text.isEmpty() ? "Preview failed." : "Preview failed: " + text;
            case EMPTY:
            default:
                return text.isEmpty() ? "No output preview yet." : text;
        }
    }
}
