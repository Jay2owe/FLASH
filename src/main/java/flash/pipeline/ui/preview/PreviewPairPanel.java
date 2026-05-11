package flash.pipeline.ui.preview;

import ij.ImagePlus;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JSlider;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Dialog;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GraphicsEnvironment;
import java.awt.GridLayout;
import java.awt.Rectangle;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.IdentityHashMap;
import java.util.Map;

public final class PreviewPairPanel extends JPanel {

    public interface SharedZChangeListener {
        void zSliceChanged(int zSlice);
    }

    public interface DisplaySettingsChangeListener {
        void displaySettingsChanged(PreviewDisplaySettings settings);
    }

    public interface SourceModeChangeListener {
        void sourceModeChanged(SourceMode mode);
    }

    public enum PreviewLayout {
        STACKED_LEGACY,
        HORIZONTAL_SLIM
    }

    public enum SourceMode {
        FILTERED,
        RAW
    }

    public enum PreviewState {
        EMPTY,
        READY,
        STALE,
        RUNNING,
        ERROR
    }

    private final ImagePreviewPanel originalPreview;
    private final ImagePreviewPanel adjustedPreview;
    private final MinMaxControlPanel displayControls = new MinMaxControlPanel(false);
    private final JComboBox<String> lutModeChoice = new JComboBox<String>();
    private final JButton largeViewButton = new JButton("Large view");
    private final JButton displayControlsButton = new JButton("Adjust Brightness/Contrast");
    private final JPanel objectOverlayControls = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
    private final JCheckBox objectOverlayCheck = new JCheckBox("Overlay objects");
    private final JComboBox<String> objectOverlaySourceChoice = new JComboBox<String>();
    private final Window owner;
    private final JPanel displayControlsPanel;
    private final PreviewLayout layout;

    private ImagePlus originalImage;
    private ImagePlus adjustedImage;
    private boolean usingCustomLargePreviewImages;
    private ImagePlus largePreviewFirstImage;
    private ImagePlus largePreviewSecondImage;
    private ImagePlus largePreviewThirdImage;
    private ImagePlus generatedObjectOverlayImage;
    private String channelLutName = "Grays";
    private PreviewDisplaySettings displaySettings = PreviewDisplaySettings.defaultFor(channelLutName);
    private final Map<ImagePlus, PreviewDisplaySettings> displaySettingsByImage =
            new IdentityHashMap<ImagePlus, PreviewDisplaySettings>();
    private ImagePlus displayControlImage;
    private PreviewState adjustedState = PreviewState.EMPTY;
    private String adjustedMessage = "";
    private int currentZ = 1;
    private boolean syncingSlices;
    private boolean updatingDisplayControls;
    private boolean displayControlsAvailable = true;
    private boolean displayRangeInitialized;
    private LargePreviewDialog largePreviewDialog;
    private JDialog displayControlsDialog;
    private SharedZChangeListener sharedZChangeListener;
    private DisplaySettingsChangeListener displaySettingsChangeListener;
    private SourceModeChangeListener sourceModeListener;
    private JPanel previewPairContainer;
    private JPanel previewToolstripComponent;
    private JLabel sourceLabel;
    private JRadioButton sourceFilteredRadio;
    private JRadioButton sourceRawRadio;
    private JComponent sharedZRowComponent;
    private JSlider sharedZSlider;
    private JLabel sharedZCountLabel;
    private boolean updatingSharedZSlider;
    private boolean objectOverlayControlsBuilt;
    private boolean sourceToggleVisible;
    private boolean sourceModeEnabled = true;
    private SourceMode sourceMode = SourceMode.FILTERED;

    public PreviewPairPanel(String originalTitle, String adjustedTitle) {
        this(null, originalTitle, adjustedTitle);
    }

    public PreviewPairPanel(String originalTitle, String adjustedTitle, PreviewLayout layout) {
        this(null, originalTitle, adjustedTitle, layout);
    }

    public PreviewPairPanel(Window owner, String originalTitle, String adjustedTitle) {
        this(owner, originalTitle, adjustedTitle, PreviewLayout.STACKED_LEGACY);
    }

    public PreviewPairPanel(Window owner, String originalTitle, String adjustedTitle,
                            PreviewLayout layout) {
        super(new BorderLayout(0, 6));
        this.owner = owner;
        this.layout = layout == null ? PreviewLayout.STACKED_LEGACY : layout;
        this.originalPreview = new ImagePreviewPanel(originalTitle);
        this.adjustedPreview = new ImagePreviewPanel(adjustedTitle);
        this.displayControlsPanel = buildDisplayControls();
        buildObjectOverlayControls();
        setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 0));
        add(buildPreviewArea(), BorderLayout.CENTER);
        if (this.layout == PreviewLayout.HORIZONTAL_SLIM) {
            originalPreview.setSlim(true);
            adjustedPreview.setSlim(true);
        }
        wireLargeViewButton();
        wireDisplayControlsButton();
        wireObjectOverlayControls();
        wireSliceSync();
        wireDisplayControls();
        setAdjustedState(PreviewState.EMPTY, null);
    }

    public void setOriginal(ImagePlus image) {
        this.originalImage = image;
        originalPreview.setImage(image);
        updateAdjustedPreviewImage();
        applyCurrentZ(currentZ);
        refreshDisplayControlImage();
        updateLargeImages();
    }

    public void setAdjusted(ImagePlus image) {
        this.adjustedImage = image;
        updateObjectOverlayControls();
        updateAdjustedPreviewImage();
        if (image != null && adjustedState == PreviewState.EMPTY) {
            setAdjustedState(PreviewState.READY, null);
        } else {
            applyAdjustedStatus();
        }
        applyCurrentZ(currentZ);
        refreshDisplayControlImage();
        updateLargeImages();
    }

    public void clearImages() {
        closeGeneratedObjectOverlayImage();
        originalImage = null;
        adjustedImage = null;
        usingCustomLargePreviewImages = false;
        largePreviewFirstImage = null;
        largePreviewSecondImage = null;
        largePreviewThirdImage = null;
        displayControlImage = null;
        displaySettingsByImage.clear();
        displayRangeInitialized = false;
        currentZ = 1;
        objectOverlayCheck.setSelected(false);
        updateObjectOverlayControls();

        originalPreview.setImage(null);
        adjustedPreview.setImage(null);
        setAdjustedState(PreviewState.EMPTY, null);

        updatingDisplayControls = true;
        try {
            displayControls.setImage(null);
        } finally {
            updatingDisplayControls = false;
        }
        displaySettings = PreviewDisplaySettings.defaultFor(channelLutName);
        applyDisplaySettings();
        updateLargeImages();
        refreshSharedZRow();
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

    public void resetZ() {
        applyCurrentZ(1);
    }

    public void setChannelLutName(String channelLutName) {
        this.channelLutName = PreviewDisplaySettings.normalizeLutName(channelLutName);
        updateLutModeLabels();
        displaySettings = displaySettings.withChannelLutName(this.channelLutName);
        updateStoredDisplaySettingsLut();
        applyDisplaySettings();
    }

    public void setSharedZChangeListener(SharedZChangeListener listener) {
        this.sharedZChangeListener = listener;
    }

    public void setDisplaySettingsChangeListener(DisplaySettingsChangeListener listener) {
        this.displaySettingsChangeListener = listener;
    }

    public PreviewDisplaySettings getDisplaySettings() {
        return displaySettings;
    }

    public void setLargePreviewImages(ImagePlus firstImage, ImagePlus secondImage,
                                      ImagePlus thirdImage) {
        rememberCurrentDisplaySettings();
        usingCustomLargePreviewImages = true;
        largePreviewFirstImage = firstImage;
        largePreviewSecondImage = secondImage;
        largePreviewThirdImage = thirdImage;
        updateObjectOverlayControls();
        updateAdjustedPreviewImage();
        applyDisplaySettings();
        applyCurrentZ(currentZ);
        updateLargeImages();
    }

    public void clearLargePreviewImages() {
        forgetDisplaySettings(largePreviewFirstImage);
        forgetDisplaySettings(largePreviewSecondImage);
        forgetDisplaySettings(largePreviewThirdImage);
        usingCustomLargePreviewImages = false;
        largePreviewFirstImage = null;
        largePreviewSecondImage = null;
        largePreviewThirdImage = null;
        updateObjectOverlayControls();
        updateAdjustedPreviewImage();
        applyDisplaySettings();
        applyCurrentZ(currentZ);
        updateLargeImages();
    }

    public JButton largeViewButton() {
        return largeViewButton;
    }

    public JButton displayControlsButton() {
        return displayControlsButton;
    }

    public void setDisplayControlsAvailable(boolean available) {
        displayControlsAvailable = available;
        displayControlsButton.setVisible(available);
        displayControlsButton.setEnabled(available);
        if (!available) {
            hideDisplayControlsDialog();
            displaySettings = PreviewDisplaySettings.defaultFor(channelLutName);
            displaySettingsByImage.clear();
            displayControlImage = null;
            applyDisplaySettings();
        } else {
            refreshDisplayControlImage();
        }
    }

    public void setCompactPreviewHeaders(boolean compact) {
        originalPreview.setMetadataHeaderVisible(!compact);
        adjustedPreview.setMetadataHeaderVisible(!compact);
    }

    public JComponent sharedZRow() {
        if (sharedZRowComponent != null) return sharedZRowComponent;
        sharedZSlider = new JSlider(1, 1, 1);
        sharedZCountLabel = new JLabel("1 / 1");
        JPanel row = new JPanel(new BorderLayout(6, 0));
        row.setOpaque(false);
        sharedZCountLabel.setHorizontalAlignment(JLabel.RIGHT);
        row.add(new JLabel("Z:"), BorderLayout.WEST);
        row.add(sharedZSlider, BorderLayout.CENTER);
        row.add(sharedZCountLabel, BorderLayout.EAST);
        sharedZSlider.addChangeListener(e -> {
            if (updatingSharedZSlider) return;
            applyCurrentZ(sharedZSlider.getValue());
        });
        sharedZRowComponent = row;
        refreshSharedZRow();
        return sharedZRowComponent;
    }

    public JPanel previewToolstrip() {
        if (previewToolstripComponent != null) return previewToolstripComponent;
        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        left.setOpaque(false);
        left.add(buildSourceControls());
        left.add(buildObjectOverlayControls());

        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        right.setOpaque(false);
        right.add(largeViewButton);
        right.add(displayControlsButton);

        previewToolstripComponent = new JPanel(new BorderLayout(8, 0));
        previewToolstripComponent.setOpaque(false);
        previewToolstripComponent.add(left, BorderLayout.WEST);
        previewToolstripComponent.add(right, BorderLayout.EAST);
        applySourceControlsState();
        return previewToolstripComponent;
    }

    public void setSourceToggleVisible(boolean visible) {
        sourceToggleVisible = visible;
        applySourceControlsState();
    }

    public void setSourceMode(SourceMode mode) {
        sourceMode = mode == SourceMode.RAW ? SourceMode.RAW : SourceMode.FILTERED;
        applySourceControlsState();
    }

    public void setSourceModeEnabled(boolean enabled) {
        sourceModeEnabled = enabled;
        applySourceControlsState();
    }

    public void setSourceModeChangeListener(SourceModeChangeListener listener) {
        sourceModeListener = listener;
    }

    public void resetStageToolstripState() {
        sourceModeListener = null;
        setSourceToggleVisible(false);
        setSourceMode(SourceMode.FILTERED);
        setSourceModeEnabled(true);
        // Overlay choice is intentionally preserved across related object-preview stages.
    }

    public void setOriginalPreviewTitle(String title) {
        originalPreview.setPreviewTitle(title);
    }

    public void setAdjustedPreviewTitle(String title) {
        adjustedPreview.setPreviewTitle(title);
    }

    public void hideDisplayControlsDialog() {
        if (displayControlsDialog != null) {
            displayControlsDialog.setVisible(false);
        }
    }

    public void disposeDisplayControlsDialog() {
        if (displayControlsDialog != null) {
            displayControlsDialog.dispose();
            displayControlsDialog = null;
        }
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
        if (largePreviewDialog != null) {
            largePreviewDialog.setDisplaySettings(largeFirstDisplaySettings(),
                    largeSecondDisplaySettings());
        }
        updateLargeImages();
    }

    Window largePreviewOwnerForTest() {
        return largePreviewDialog == null ? null : largePreviewDialog.ownerForTest();
    }

    int largePreviewImageCountForTest() {
        if (!usingCustomLargePreviewImages) return 2;
        return largePreviewThirdImage == null ? 2 : 3;
    }

    void setDisplayRangeForTest(double min, double max) {
        displayControls.setRange(min, max);
        updateDisplaySettingsFromControls();
    }

    PreviewDisplaySettings displaySettingsForTest() {
        return displaySettings;
    }

    PreviewDisplaySettings displaySettingsForImageForTest(ImagePlus image) {
        return displaySettingsForImage(image);
    }

    ImagePreviewPanel originalPreviewForTest() {
        return originalPreview;
    }

    ImagePreviewPanel adjustedPreviewForTest() {
        return adjustedPreview;
    }

    JPanel previewPairContainerForTest() {
        return previewPairContainer;
    }

    JSlider sharedZSliderForTest() {
        sharedZRow();
        return sharedZSlider;
    }

    String sharedZTextForTest() {
        sharedZRow();
        return sharedZCountLabel.getText();
    }

    JRadioButton sourceRawRadioForTest() {
        previewToolstrip();
        return sourceRawRadio;
    }

    boolean sourceToggleVisibleForTest() {
        previewToolstrip();
        return sourceLabel.isVisible()
                && sourceFilteredRadio.isVisible()
                && sourceRawRadio.isVisible();
    }

    boolean sourceModeEnabledForTest() {
        previewToolstrip();
        return sourceFilteredRadio.isEnabled() && sourceRawRadio.isEnabled();
    }

    SourceMode sourceModeForTest() {
        return sourceMode;
    }

    String originalPreviewTitleForTest() {
        return originalPreview.previewTitleForTest();
    }

    String adjustedPreviewTitleForTest() {
        return adjustedPreview.previewTitleForTest();
    }

    boolean originalPreviewMetadataHeaderVisibleForTest() {
        return originalPreview.metadataHeaderVisibleForTest();
    }

    boolean objectOverlayControlsVisibleForTest() {
        return objectOverlayControls.isVisible();
    }

    void setObjectOverlaySelectedForTest(boolean selected) {
        objectOverlayCheck.setSelected(selected);
        updateObjectOverlayControls();
        updateAdjustedPreviewImage();
    }

    void setObjectOverlaySourceForTest(String sourceLabel) {
        objectOverlaySourceChoice.setSelectedItem(sourceLabel);
        updateAdjustedPreviewImage();
    }

    String adjustedImageTitleForTest() {
        return adjustedPreview.titleTextForTest();
    }

    void disposeLargePreviewForTest() {
        if (largePreviewDialog != null) {
            largePreviewDialog.dispose();
            largePreviewDialog = null;
        }
    }

    void disposeDisplayControlsDialogForTest() {
        disposeDisplayControlsDialog();
    }

    Window displayControlsOwnerForTest() {
        return displayControlsDialog == null ? null : displayControlsDialog.getOwner();
    }

    static int clampSharedZ(int requestedZ, int originalSlices, int adjustedSlices) {
        return clampSharedZ(requestedZ, new int[]{originalSlices, adjustedSlices});
    }

    static int clampSharedZ(int requestedZ, int originalSlices, int adjustedSlices,
                            int extraSlices) {
        return clampSharedZ(requestedZ, new int[]{originalSlices, adjustedSlices, extraSlices});
    }

    private static int clampSharedZ(int requestedZ, int[] sliceCounts) {
        int sharedMax = sliceCounts.length == 0 ? 1 : Math.max(1, sliceCounts[0]);
        for (int i = 1; i < sliceCounts.length; i++) {
            int count = sliceCounts[i] == Integer.MAX_VALUE
                    ? Integer.MAX_VALUE
                    : Math.max(1, sliceCounts[i]);
            sharedMax = Math.min(sharedMax, count);
        }
        return ImagePreviewPanel.clamp(requestedZ, 1, sharedMax);
    }

    private JPanel buildPreviewPair(boolean horizontal) {
        JPanel previews = horizontal
                ? new JPanel(new GridLayout(1, 2, 8, 0))
                : new JPanel();
        if (!horizontal) {
            previews.setLayout(new BoxLayout(previews, BoxLayout.Y_AXIS));
        }
        previews.setOpaque(false);
        previews.add(originalPreview);
        previews.add(adjustedPreview);
        previewPairContainer = previews;
        return previews;
    }

    private JPanel buildPreviewArea() {
        JPanel panel = new JPanel(new BorderLayout(0, 4));
        panel.setOpaque(false);
        boolean horizontal = layout == PreviewLayout.HORIZONTAL_SLIM;
        panel.add(buildPreviewPair(horizontal), BorderLayout.CENTER);
        if (!horizontal) {
            panel.add(buildObjectOverlayControls(), BorderLayout.SOUTH);
        }
        return panel;
    }

    private JPanel buildObjectOverlayControls() {
        if (objectOverlayControlsBuilt) return objectOverlayControls;
        objectOverlaySourceChoice.addItem("Filtered image");
        objectOverlaySourceChoice.addItem("Raw image");
        objectOverlayControls.setOpaque(false);
        objectOverlayCheck.setOpaque(false);
        objectOverlayControls.add(objectOverlayCheck);
        objectOverlayControls.add(new JLabel("over"));
        objectOverlayControls.add(objectOverlaySourceChoice);
        objectOverlayControls.setVisible(false);
        objectOverlayControlsBuilt = true;
        return objectOverlayControls;
    }

    private JPanel buildSourceControls() {
        JPanel sourceControls = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        sourceControls.setOpaque(false);
        sourceLabel = new JLabel("Source:");
        sourceFilteredRadio = new JRadioButton("Filtered", true);
        sourceRawRadio = new JRadioButton("Raw");
        sourceFilteredRadio.setOpaque(false);
        sourceRawRadio.setOpaque(false);
        ButtonGroup group = new ButtonGroup();
        group.add(sourceFilteredRadio);
        group.add(sourceRawRadio);
        ActionListener listener = new ActionListener() {
            @Override public void actionPerformed(ActionEvent e) {
                if (!sourceModeEnabled) {
                    applySourceControlsState();
                    return;
                }
                SourceMode selected = sourceRawRadio.isSelected()
                        ? SourceMode.RAW
                        : SourceMode.FILTERED;
                sourceMode = selected;
                if (sourceModeListener != null) {
                    sourceModeListener.sourceModeChanged(selected);
                }
            }
        };
        sourceFilteredRadio.addActionListener(listener);
        sourceRawRadio.addActionListener(listener);
        sourceControls.add(sourceLabel);
        sourceControls.add(sourceFilteredRadio);
        sourceControls.add(sourceRawRadio);
        return sourceControls;
    }

    private void applySourceControlsState() {
        if (sourceLabel == null) return;
        sourceLabel.setVisible(sourceToggleVisible);
        sourceFilteredRadio.setVisible(sourceToggleVisible);
        sourceRawRadio.setVisible(sourceToggleVisible);
        sourceLabel.setEnabled(sourceModeEnabled);
        sourceFilteredRadio.setEnabled(sourceModeEnabled);
        sourceRawRadio.setEnabled(sourceModeEnabled);
        if (sourceMode == SourceMode.RAW) {
            sourceRawRadio.setSelected(true);
        } else {
            sourceFilteredRadio.setSelected(true);
        }
        if (previewToolstripComponent != null) {
            previewToolstripComponent.revalidate();
            previewToolstripComponent.repaint();
        }
    }

    private JPanel buildDisplayControls() {
        JPanel controls = new JPanel(new BorderLayout(0, 4));
        controls.setOpaque(false);
        controls.setBorder(BorderFactory.createTitledBorder("Preview display"));

        lutModeChoice.addItem("Grey");
        lutModeChoice.addItem(channelLutChoiceText());
        lutModeChoice.setSelectedIndex(1);
        JPanel lutRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        lutRow.setOpaque(false);
        lutRow.add(new JLabel("Temporary LUT:"));
        lutRow.add(lutModeChoice);

        controls.add(lutRow, BorderLayout.NORTH);
        controls.add(displayControls, BorderLayout.CENTER);
        return controls;
    }

    private void wireLargeViewButton() {
        largeViewButton.addActionListener(new ActionListener() {
            @Override public void actionPerformed(ActionEvent e) {
                showLargePreview();
            }
        });
    }

    private void wireDisplayControlsButton() {
        displayControlsButton.addActionListener(new ActionListener() {
            @Override public void actionPerformed(ActionEvent e) {
                showDisplayControlsDialog();
            }
        });
    }

    private void wireObjectOverlayControls() {
        objectOverlayCheck.addActionListener(new ActionListener() {
            @Override public void actionPerformed(ActionEvent e) {
                updateObjectOverlayControls();
                updateAdjustedPreviewImage();
            }
        });
        objectOverlaySourceChoice.addActionListener(new ActionListener() {
            @Override public void actionPerformed(ActionEvent e) {
                updateAdjustedPreviewImage();
            }
        });
    }

    private void wireDisplayControls() {
        displayControls.setListener(new MinMaxControlPanel.Listener() {
            @Override public void rangeChanged(double min, double max, boolean adjusting) {
                if (updatingDisplayControls) return;
                updateDisplaySettingsFromControls();
            }

            @Override public void autoRequested() {
                updateDisplaySettingsFromControls();
            }

            @Override public void resetRequested() {
                updateDisplaySettingsFromControls();
            }

            @Override public void setRequested() {
                updateDisplaySettingsFromControls();
            }
        });
        lutModeChoice.addActionListener(new ActionListener() {
            @Override public void actionPerformed(ActionEvent e) {
                if (updatingDisplayControls) return;
                updateDisplaySettingsFromControls();
            }
        });
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
        Window currentOwner = currentOwner();
        if (largePreviewDialog == null || largePreviewDialog.ownerForTest() != currentOwner) {
            if (largePreviewDialog != null) {
                largePreviewDialog.dispose();
            }
            largePreviewDialog = new LargePreviewDialog(currentOwner);
            wireLargeDialog();
            largePreviewDialog.setDisplaySettings(largeFirstDisplaySettings(),
                    largeSecondDisplaySettings());
        }
        updateLargeImages();
        if (!largePreviewDialog.isVisible()) {
            largePreviewDialog.setLocationRelativeTo(currentOwner);
        }
        largePreviewDialog.raiseForUser();
    }

    private void showDisplayControlsDialog() {
        if (GraphicsEnvironment.isHeadless() || !displayControlsButton.isEnabled()) return;
        Window currentOwner = currentOwner();
        if (displayControlsDialog == null || displayControlsDialog.getOwner() != currentOwner) {
            disposeDisplayControlsDialog();
            displayControlsDialog = new JDialog(currentOwner, "Preview Brightness/Contrast",
                    Dialog.ModalityType.MODELESS);
            installModalExclusion(displayControlsDialog);
            displayControlsDialog.setDefaultCloseOperation(JDialog.HIDE_ON_CLOSE);
            displayControlsDialog.setContentPane(displayControlsPanel);
            displayControlsDialog.setMinimumSize(new Dimension(360, 420));
        }
        displayControlsDialog.pack();
        positionDisplayControlsDialog(currentOwner);
        displayControlsDialog.setVisible(true);
        displayControlsDialog.toFront();
        displayControlsDialog.requestFocus();
    }

    private void positionDisplayControlsDialog(Window currentOwner) {
        if (displayControlsDialog == null) return;
        if (currentOwner == null || !currentOwner.isShowing()) {
            displayControlsDialog.setLocationRelativeTo(this);
            return;
        }
        Rectangle ownerBounds = currentOwner.getBounds();
        Rectangle screen = GraphicsEnvironment
                .getLocalGraphicsEnvironment()
                .getMaximumWindowBounds();
        Dimension size = displayControlsDialog.getSize();
        int gap = 8;
        int x = ownerBounds.x + ownerBounds.width + gap;
        if (x + size.width > screen.x + screen.width) {
            x = ownerBounds.x - size.width - gap;
        }
        if (x < screen.x) {
            x = Math.max(screen.x, Math.min(ownerBounds.x, screen.x + screen.width - size.width));
        }
        int y = ownerBounds.y;
        if (y + size.height > screen.y + screen.height) {
            y = screen.y + Math.max(0, screen.height - size.height);
        }
        y = Math.max(screen.y, y);
        displayControlsDialog.setLocation(x, y);
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
            int previousZ = currentZ;
            currentZ = ImagePreviewPanel.clamp(requestedZ, 1, effectiveSharedSliceCount());
            originalPreview.setCurrentZ(currentZ);
            adjustedPreview.setCurrentZ(currentZ);
            if (largePreviewDialog != null) {
                largePreviewDialog.setCurrentZ(currentZ);
            }
            if (sharedZChangeListener != null && currentZ != previousZ) {
                sharedZChangeListener.zSliceChanged(currentZ);
            }
            refreshSharedZRow();
        } finally {
            syncingSlices = false;
        }
    }

    private void refreshSharedZRow() {
        if (sharedZSlider == null) return;
        int maxZ = effectiveSharedSliceCount();
        int value = ImagePreviewPanel.clamp(currentZ, 1, maxZ);
        updatingSharedZSlider = true;
        try {
            sharedZSlider.setMinimum(1);
            sharedZSlider.setMaximum(maxZ);
            sharedZSlider.setValue(value);
            sharedZSlider.setEnabled(maxZ > 1);
            sharedZCountLabel.setText(value + " / " + maxZ);
        } finally {
            updatingSharedZSlider = false;
        }
    }

    private int effectiveSharedSliceCount() {
        int sharedMax = 1;
        boolean hasImage = false;
        if (originalImage != null) {
            sharedMax = originalPreview.getSliceCount();
            hasImage = true;
        }
        if (adjustedImage != null) {
            int count = adjustedPreview.getSliceCount();
            sharedMax = hasImage ? Math.min(sharedMax, count) : count;
            hasImage = true;
        }
        if (usingCustomLargePreviewImages) {
            if (largePreviewFirstImage != null) {
                int count = effectiveSliceCount(largePreviewFirstImage);
                sharedMax = hasImage ? Math.min(sharedMax, count) : count;
                hasImage = true;
            }
            if (largePreviewSecondImage != null) {
                int count = effectiveSliceCount(largePreviewSecondImage);
                sharedMax = hasImage ? Math.min(sharedMax, count) : count;
                hasImage = true;
            }
            if (largePreviewThirdImage != null) {
                int count = effectiveSliceCount(largePreviewThirdImage);
                sharedMax = hasImage ? Math.min(sharedMax, count) : count;
                hasImage = true;
            }
        }
        return Math.max(1, sharedMax);
    }

    private static int effectiveSliceCount(ImagePlus image) {
        if (image == null) return 1;
        try {
            int channels = Math.max(1, image.getNChannels());
            int slices = Math.max(1, image.getNSlices());
            int frames = Math.max(1, image.getNFrames());
            int stackSize = Math.max(1, image.getStackSize());
            if (channels == 1 && slices == 1 && frames > 1
                    && stackSize == channels * frames) {
                return frames;
            }
            return slices;
        } catch (RuntimeException e) {
            return 1;
        }
    }

    private void updateLargeImages() {
        if (largePreviewDialog == null) return;
        if (usingCustomLargePreviewImages) {
            largePreviewDialog.setImages(
                    largePreviewFirstImage,
                    largePreviewSecondImage,
                    largePreviewThirdImage,
                    currentZ);
        } else {
            largePreviewDialog.setImages(originalImage, adjustedImage, currentZ);
        }
        largePreviewDialog.setAdjustedStatusText(adjustedPreview.statusTextForTest());
        largePreviewDialog.setDisplaySettings(largeFirstDisplaySettings(),
                largeSecondDisplaySettings());
    }

    private void updateAdjustedPreviewImage() {
        ImagePlus displayImage = adjustedImage;
        ImagePlus oldOverlay = generatedObjectOverlayImage;
        generatedObjectOverlayImage = null;
        if (objectOverlaySelected()) {
            ImagePlus sourceImage = selectedObjectOverlaySourceImage();
            ImagePlus overlay = ObjectOverlayRenderer.renderOverlay(
                    sourceImage, largePreviewThirdImage, displaySettingsForImage(sourceImage));
            if (overlay != null) {
                displayImage = overlay;
                generatedObjectOverlayImage = overlay;
            }
        }
        adjustedPreview.setImage(displayImage);
        if (oldOverlay != null && oldOverlay != generatedObjectOverlayImage) {
            oldOverlay.flush();
        }
    }

    private void updateObjectOverlayControls() {
        boolean available = objectOverlayAvailable();
        boolean hasSource = largePreviewFirstImage != null || largePreviewSecondImage != null;
        objectOverlayControls.setVisible(available);
        objectOverlayCheck.setEnabled(available && hasSource);
        objectOverlaySourceChoice.setEnabled(available && hasSource && objectOverlayCheck.isSelected());
        if (!available) {
            closeGeneratedObjectOverlayImage();
        }
        objectOverlayControls.revalidate();
        objectOverlayControls.repaint();
    }

    private boolean objectOverlayAvailable() {
        return usingCustomLargePreviewImages
                && largePreviewThirdImage != null
                && adjustedImage == largePreviewThirdImage;
    }

    private boolean objectOverlaySelected() {
        return objectOverlayAvailable()
                && objectOverlayCheck.isSelected()
                && (largePreviewFirstImage != null || largePreviewSecondImage != null);
    }

    private ImagePlus selectedObjectOverlaySourceImage() {
        Object selected = objectOverlaySourceChoice.getSelectedItem();
        boolean raw = selected != null && "Raw image".equals(selected.toString());
        ImagePlus preferred = raw ? largePreviewFirstImage : largePreviewSecondImage;
        if (preferred != null) return preferred;
        return raw ? largePreviewSecondImage : largePreviewFirstImage;
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

    private void refreshDisplayControlImage() {
        if (!displayControlsAvailable) {
            displaySettings = PreviewDisplaySettings.defaultFor(channelLutName);
            applyDisplaySettings();
            return;
        }
        ImagePlus displayImage = originalImage == null ? adjustedImage : originalImage;
        if (displayImage == null) {
            updatingDisplayControls = true;
            try {
                displayControls.setImage(null);
                displayRangeInitialized = false;
                displayControlImage = null;
            } finally {
                updatingDisplayControls = false;
            }
            displaySettings = PreviewDisplaySettings.defaultFor(channelLutName);
            applyDisplaySettings();
            return;
        }

        double previousMin = displayControls.getMinValue();
        double previousMax = displayControls.getMaxValue();
        PreviewDisplaySettings savedSettings = roleAwareDisplaySettings()
                ? displaySettingsByImage.get(displayImage)
                : null;
        boolean preserveRange = savedSettings == null
                && shouldPreserveDisplayRange(displayImage, previousMin, previousMax);
        updatingDisplayControls = true;
        try {
            displayControls.setImage(displayImage);
            displayControlImage = displayImage;
            if (savedSettings != null && savedSettings.hasDisplayRange()) {
                displayControls.setRange(savedSettings.getDisplayMin(), savedSettings.getDisplayMax());
            } else if (preserveRange) {
                displayControls.setRange(previousMin, previousMax);
            }
            displayRangeInitialized = true;
        } finally {
            updatingDisplayControls = false;
        }
        updateDisplaySettingsFromControls(false);
    }

    private void updateDisplaySettingsFromControls() {
        updateDisplaySettingsFromControls(true);
    }

    private void updateDisplaySettingsFromControls(boolean notifyListener) {
        PreviewDisplaySettings.LutMode mode = lutModeChoice.getSelectedIndex() == 0
                ? PreviewDisplaySettings.LutMode.GREY
                : PreviewDisplaySettings.LutMode.CHANNEL;
        displaySettings = PreviewDisplaySettings.of(
                displayControls.getMinValue(),
                displayControls.getMaxValue(),
                mode,
                channelLutName);
        rememberCurrentDisplaySettings();
        applyDisplaySettings();
        if (notifyListener && displaySettingsChangeListener != null) {
            displaySettingsChangeListener.displaySettingsChanged(displaySettings);
        }
    }

    private void applyDisplaySettings() {
        boolean adjustedIsObjectPreview = usingCustomLargePreviewImages
                && largePreviewThirdImage != null;
        originalPreview.setDisplaySettingsEnabled(true);
        adjustedPreview.setDisplaySettingsEnabled(!adjustedIsObjectPreview);
        originalPreview.setDisplaySettings(displaySettingsForImage(originalImage));
        adjustedPreview.setDisplaySettings(displaySettingsForImage(adjustedImage));
        if (objectOverlaySelected()) {
            updateAdjustedPreviewImage();
        }
        if (largePreviewDialog != null) {
            largePreviewDialog.setDisplaySettings(largeFirstDisplaySettings(),
                    largeSecondDisplaySettings());
        }
    }

    private boolean shouldPreserveDisplayRange(ImagePlus displayImage, double previousMin,
                                               double previousMax) {
        if (!displayRangeInitialized || displayImage == null) return false;
        if (displayControlImage == displayImage) return true;
        if (roleAwareDisplaySettings()) return false;
        if (displayControlImage != null
                && displayControlImage.getBitDepth() != displayImage.getBitDepth()) {
            return false;
        }
        HistogramPanel.Histogram histogram = HistogramPanel.calculateHistogram(
                displayImage, HistogramPanel.DEFAULT_BIN_COUNT);
        if (histogram.isEmpty()) return true;
        return previousMin >= histogram.getMinimum() && previousMax <= histogram.getMaximum();
    }

    private void rememberCurrentDisplaySettings() {
        if (displayControlImage != null) {
            displaySettingsByImage.put(displayControlImage, displaySettings);
        }
    }

    private void forgetDisplaySettings(ImagePlus image) {
        if (image != null) {
            displaySettingsByImage.remove(image);
        }
    }

    private void updateStoredDisplaySettingsLut() {
        for (Map.Entry<ImagePlus, PreviewDisplaySettings> entry : displaySettingsByImage.entrySet()) {
            PreviewDisplaySettings settings = entry.getValue();
            if (settings != null) {
                entry.setValue(settings.withChannelLutName(channelLutName));
            }
        }
    }

    private PreviewDisplaySettings displaySettingsForImage(ImagePlus image) {
        if (!displayControlsAvailable) {
            return PreviewDisplaySettings.defaultFor(channelLutName);
        }
        if (!roleAwareDisplaySettings()) {
            return displaySettings;
        }
        PreviewDisplaySettings settings = image == null ? null : displaySettingsByImage.get(image);
        return settings == null ? PreviewDisplaySettings.defaultFor(channelLutName) : settings;
    }

    private PreviewDisplaySettings largeFirstDisplaySettings() {
        return usingCustomLargePreviewImages
                ? displaySettingsForImage(largePreviewFirstImage)
                : displaySettingsForImage(originalImage);
    }

    private PreviewDisplaySettings largeSecondDisplaySettings() {
        return usingCustomLargePreviewImages
                ? displaySettingsForImage(largePreviewSecondImage)
                : displaySettingsForImage(adjustedImage);
    }

    private boolean roleAwareDisplaySettings() {
        return usingCustomLargePreviewImages
                && (largePreviewFirstImage != null || largePreviewSecondImage != null);
    }

    private Window currentOwner() {
        Window ancestor = SwingUtilities.getWindowAncestor(this);
        return ancestor == null ? owner : ancestor;
    }

    private static void installModalExclusion(Window window) {
        if (window == null) return;
        try {
            window.setModalExclusionType(Dialog.ModalExclusionType.APPLICATION_EXCLUDE);
        } catch (SecurityException ignored) {
            // Best-effort only; the dialog still remains modeless.
        }
    }

    private void closeGeneratedObjectOverlayImage() {
        if (generatedObjectOverlayImage != null) {
            generatedObjectOverlayImage.flush();
            generatedObjectOverlayImage = null;
        }
    }

    private void updateLutModeLabels() {
        updatingDisplayControls = true;
        try {
            Object selected = lutModeChoice.getSelectedItem();
            lutModeChoice.removeAllItems();
            lutModeChoice.addItem("Grey");
            lutModeChoice.addItem(channelLutChoiceText());
            if (selected != null && selected.toString().startsWith("Grey")) {
                lutModeChoice.setSelectedIndex(0);
            } else {
                lutModeChoice.setSelectedIndex(1);
            }
        } finally {
            updatingDisplayControls = false;
        }
    }

    private String channelLutChoiceText() {
        return "Channel LUT (" + channelLutName + ")";
    }
}
