package flash.pipeline.ui.preview;

import flash.pipeline.click.ClickStore;
import flash.pipeline.click.ClicksConfigIO;
import ij.IJ;
import ij.ImagePlus;
import ij.gui.Overlay;
import ij.gui.PointRoi;
import ij.gui.Roi;

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
import javax.swing.SwingWorker;
import javax.swing.SwingUtilities;
import java.awt.Color;
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
import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

public final class PreviewPairPanel extends JPanel {
    private static final String CLICK_ROI_PREFIX = "flash-click:";
    private static final Color CLICK_POSITIVE_COLOR = new Color(25, 180, 80);
    private static final Color CLICK_NEGATIVE_COLOR = new Color(220, 55, 55);
    private static final long CLICK_WRITE_FLUSH_TIMEOUT_MILLIS = 2000L;
    private static final Object CLICK_WRITE_EXECUTOR_LOCK = new Object();
    private static ExecutorService clickWriteExecutor = newClickWriteExecutor();

    private static ExecutorService newClickWriteExecutor() {
        return Executors.newSingleThreadExecutor(new ThreadFactory() {
            @Override public Thread newThread(Runnable r) {
                Thread thread = new Thread(r, "flash-click-config-writer");
                thread.setDaemon(true);
                return thread;
            }
        });
    }

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
    private final JButton comparePreviewButton = new JButton("Compare previews");
    private final JButton displayControlsButton = new JButton("Adjust Brightness/Contrast");
    private final JButton lutToggleButton = new JButton("Grey LUT");
    private final JCheckBox otsuOverlayCheckBox = new JCheckBox("Show Otsu overlay");
    private final JPanel objectOverlayControls = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
    private final JCheckBox objectOverlayCheck = new JCheckBox();
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
    private ImagePlus largePreviewOriginalSourceImage;
    private ImagePlus largePreviewFilteredSourceImage;
    private SourceMode largePreviewSourceMode = SourceMode.RAW;
    private ImagePlus comparisonPreviousImage;
    private String comparisonPreviousStatus = "";
    private Runnable comparisonRestoreAction;
    private ImagePlus generatedObjectOverlayImage;
    private ImagePlus generatedOtsuOverlayImage;
    private ImagePlus objectTrueLabelMap;
    private Set<Integer> objectRemovedLabels = Collections.emptySet();
    private boolean showRemovedObjects;
    private int objectFilterObjectCount;
    private String channelLutName = "Grays";
    private PreviewDisplaySettings displaySettings = PreviewDisplaySettings.defaultFor(channelLutName);
    private ObjectSizeFilterPreview.Summary objectSizeGuide;
    private final Map<ImagePlus, PreviewDisplaySettings> displaySettingsByImage =
            new IdentityHashMap<ImagePlus, PreviewDisplaySettings>();
    private ImagePlus displayControlImage;
    private PreviewState adjustedState = PreviewState.EMPTY;
    private String adjustedMessage = "";
    private long adjustedPreviewGeneration;
    private int currentZ = 1;
    private boolean syncingSlices;
    private boolean updatingDisplayControls;
    private boolean displayControlsAvailable = true;
    private boolean lutToggleAvailable = true;
    private boolean largeDisplayControlsActivated;
    private boolean displayRangeInitialized;
    private LargePreviewDialog largePreviewDialog;
    private ComparisonPreviewDialog comparisonPreviewDialog;
    private JDialog displayControlsDialog;
    private File clickBinFolder;
    private ClickStore clickStore;
    private String clickImageName = "";
    private int clickChannelOneBased;
    private final Object clickFlushStateLock = new Object();
    private int clickWriteGeneration;
    private int clickFlushedGeneration;
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
    private boolean objectOverlayEnabled = true;
    private boolean otsuOverlayAvailable;
    private boolean comparisonPreviewVisible;
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
        otsuOverlayCheckBox.setOpaque(false);
        otsuOverlayCheckBox.setVisible(false);
        setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 0));
        add(buildPreviewArea(), BorderLayout.CENTER);
        if (this.layout == PreviewLayout.HORIZONTAL_SLIM) {
            originalPreview.setSlim(true);
            adjustedPreview.setSlim(true);
        }
        wireLargeViewButton();
        wireComparePreviewButton();
        wireDisplayControlsButton();
        wireObjectOverlayControls();
        wireSliceSync();
        wireDisplayControls();
        setAdjustedState(PreviewState.EMPTY, null);
    }

    public void setOriginal(final ImagePlus image) {
        if (invokeOnEdtIfNeeded(new Runnable() {
            @Override public void run() {
                setOriginal(image);
            }
        })) return;
        this.originalImage = image;
        originalPreview.setImage(image);
        updateAdjustedPreviewImage();
        applyCurrentZ(currentZ);
        refreshDisplayControlImage();
        updateLargeImages();
    }

    public void setAdjusted(final ImagePlus image) {
        if (invokeOnEdtIfNeeded(new Runnable() {
            @Override public void run() {
                setAdjusted(image);
            }
        })) return;
        if (objectTrueLabelMap != null && image != objectTrueLabelMap) {
            clearObjectFilterPreviewState();
        }
        closeGeneratedOtsuOverlayImage();
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
        if (invokeOnEdtIfNeeded(new Runnable() {
            @Override public void run() {
                clearImages();
            }
        })) return;
        closeGeneratedObjectOverlayImage();
        closeGeneratedOtsuOverlayImage();
        clearObjectFilterPreviewFields();
        originalImage = null;
        adjustedImage = null;
        usingCustomLargePreviewImages = false;
        largePreviewFirstImage = null;
        largePreviewSecondImage = null;
        largePreviewThirdImage = null;
        clearLargePreviewSourceChoiceFields();
        comparisonPreviousImage = null;
        comparisonPreviousStatus = "";
        comparisonRestoreAction = null;
        displayControlImage = null;
        displaySettingsByImage.clear();
        displayRangeInitialized = false;
        currentZ = 1;
        objectOverlayCheck.setSelected(false);
        updateObjectOverlayControls();
        applyOtsuOverlayControlState();
        setObjectSizeGuide(null);

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
        updateComparisonImages();
        updateComparisonButtonState();
        refreshSharedZRow();
    }

    public void setAdjustedState(final PreviewState state, final String message) {
        if (invokeOnEdtIfNeeded(new Runnable() {
            @Override public void run() {
                setAdjustedState(state, message);
            }
        })) return;
        if (state == PreviewState.RUNNING) {
            adjustedPreviewGeneration++;
        }
        setAdjustedStateOnEdt(state, message);
    }

    public long beginAdjustedPreviewRequest(final String message) {
        if (SwingUtilities.isEventDispatchThread()) {
            adjustedPreviewGeneration++;
            setAdjustedStateOnEdt(PreviewState.RUNNING, message);
            return adjustedPreviewGeneration;
        }
        final long[] generation = new long[] {0L};
        invokeOnEdtIfNeeded(new Runnable() {
            @Override public void run() {
                generation[0] = beginAdjustedPreviewRequest(message);
            }
        });
        return generation[0];
    }

    public boolean applyAdjustedPreviewResult(final long generation,
                                              final ImagePlus image,
                                              final PreviewState state,
                                              final String message) {
        if (SwingUtilities.isEventDispatchThread()) {
            if (generation != adjustedPreviewGeneration) {
                return false;
            }
            setAdjusted(image);
            setAdjustedStateOnEdt(state, message);
            return true;
        }
        final boolean[] applied = new boolean[] {false};
        invokeOnEdtIfNeeded(new Runnable() {
            @Override public void run() {
                applied[0] = applyAdjustedPreviewResult(generation, image, state, message);
            }
        });
        return applied[0];
    }

    public int getCurrentZ() {
        return currentZ;
    }

    public void setCurrentZ(final int zSlice) {
        if (invokeOnEdtIfNeeded(new Runnable() {
            @Override public void run() {
                setCurrentZ(zSlice);
            }
        })) return;
        applyCurrentZ(zSlice);
    }

    public void resetZ() {
        if (invokeOnEdtIfNeeded(new Runnable() {
            @Override public void run() {
                resetZ();
            }
        })) return;
        applyCurrentZ(1);
    }

    public void setChannelLutName(final String channelLutName) {
        if (invokeOnEdtIfNeeded(new Runnable() {
            @Override public void run() {
                setChannelLutName(channelLutName);
            }
        })) return;
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

    public void setObjectSizeGuide(final ObjectSizeFilterPreview.Summary summary) {
        if (invokeOnEdtIfNeeded(new Runnable() {
            @Override public void run() {
                setObjectSizeGuide(summary);
            }
        })) return;
        objectSizeGuide = summary;
        originalPreview.setObjectSizeGuide(summary);
        adjustedPreview.setObjectSizeGuide(summary);
        if (largePreviewDialog != null) {
            largePreviewDialog.setObjectSizeGuide(summary);
        }
        if (comparisonPreviewDialog != null) {
            comparisonPreviewDialog.setObjectSizeGuide(summary);
        }
    }

    public void setObjectFilterPreview(final ImagePlus trueLabelMap,
                                       final Set<Integer> removedLabels,
                                       final ObjectSizeFilterPreview.Summary summary,
                                       final int objectCount) {
        if (invokeOnEdtIfNeeded(new Runnable() {
            @Override public void run() {
                setObjectFilterPreview(trueLabelMap, removedLabels, summary, objectCount);
            }
        })) return;
        closeGeneratedObjectOverlayImage();
        closeGeneratedOtsuOverlayImage();
        objectTrueLabelMap = trueLabelMap;
        objectRemovedLabels = defensiveRemovedLabels(removedLabels, summary);
        objectFilterObjectCount = Math.max(0, objectCount);
        adjustedImage = trueLabelMap;
        if (usingCustomLargePreviewImages) {
            largePreviewThirdImage = trueLabelMap;
        }
        setObjectSizeGuide(summary);
        updateObjectOverlayControls();
        if (trueLabelMap == null) {
            updateAdjustedPreviewImageLegacy();
        } else {
            requestObjectPreviewRender();
        }
        applyCurrentZ(currentZ);
        updateLargeImages();
        updateComparisonImages();
        updateComparisonButtonState();
    }

    public void setShowRemovedObjects(final boolean show) {
        if (invokeOnEdtIfNeeded(new Runnable() {
            @Override public void run() {
                setShowRemovedObjects(show);
            }
        })) return;
        if (showRemovedObjects == show) return;
        showRemovedObjects = show;
        requestObjectPreviewRender();
    }

    public boolean showRemovedObjects() {
        return showRemovedObjects;
    }

    public ImagePlus duplicateCurrentObjectPreviewForComparison(String title) {
        if (objectTrueLabelMap != null) {
            ImagePlus rendered = generatedObjectOverlayImage;
            if (rendered == null) {
                rendered = renderObjectPreviewNowForTest();
            }
            return duplicateForComparison(rendered, title);
        }
        return duplicateForComparison(adjustedImage, title);
    }

    public void setComparisonPreviewVisible(final boolean visible) {
        if (invokeOnEdtIfNeeded(new Runnable() {
            @Override public void run() {
                setComparisonPreviewVisible(visible);
            }
        })) return;
        comparisonPreviewVisible = visible;
        comparePreviewButton.setVisible(visible);
        if (!visible && comparisonPreviewDialog != null) {
            comparisonPreviewDialog.setVisible(false);
        }
        updateComparisonButtonState();
        if (previewToolstripComponent != null) {
            previewToolstripComponent.revalidate();
            previewToolstripComponent.repaint();
        }
    }

    public void setPreviousComparisonPreview(final ImagePlus previousImage,
                                             final String previousStatus) {
        if (invokeOnEdtIfNeeded(new Runnable() {
            @Override public void run() {
                setPreviousComparisonPreview(previousImage, previousStatus);
            }
        })) return;
        comparisonPreviousImage = previousImage;
        comparisonPreviousStatus = previousStatus == null ? "" : previousStatus.trim();
        updateComparisonImages();
        updateComparisonButtonState();
        updateComparisonRestoreActionState();
    }

    public void setComparisonRestoreAction(Runnable restoreAction) {
        comparisonRestoreAction = restoreAction;
        updateComparisonRestoreActionState();
    }

    public void clearComparisonPreview() {
        if (invokeOnEdtIfNeeded(new Runnable() {
            @Override public void run() {
                clearComparisonPreview();
            }
        })) return;
        comparisonPreviousImage = null;
        comparisonPreviousStatus = "";
        comparisonRestoreAction = null;
        if (comparisonPreviewDialog != null) {
            comparisonPreviewDialog.setVisible(false);
            comparisonPreviewDialog.clearSourceChoices();
            comparisonPreviewDialog.setImages(null, null, currentZ);
            comparisonPreviewDialog.setPreviewStatus(null, null);
            comparisonPreviewDialog.setRestoreActionState(false, null);
        }
        setComparisonPreviewVisible(false);
    }

    public void setLargePreviewImages(final ImagePlus firstImage, final ImagePlus secondImage,
                                      final ImagePlus thirdImage) {
        if (invokeOnEdtIfNeeded(new Runnable() {
            @Override public void run() {
                setLargePreviewImages(firstImage, secondImage, thirdImage);
            }
        })) return;
        rememberCurrentDisplaySettings();
        if (objectTrueLabelMap != null && objectTrueLabelMap != thirdImage) {
            clearObjectFilterPreviewState();
        }
        usingCustomLargePreviewImages = true;
        largePreviewFirstImage = firstImage;
        largePreviewSecondImage = secondImage;
        largePreviewThirdImage = thirdImage;
        updateObjectOverlayControls();
        updateAdjustedPreviewImage();
        applyDisplaySettings();
        applyCurrentZ(currentZ);
        updateLargeImages();
        updateComparisonImages();
        updateComparisonButtonState();
    }

    public void setLargePreviewSourceChoices(final ImagePlus originalSource,
                                             final ImagePlus filteredSource) {
        if (invokeOnEdtIfNeeded(new Runnable() {
            @Override public void run() {
                setLargePreviewSourceChoices(originalSource, filteredSource);
            }
        })) return;
        rememberCurrentDisplaySettings();
        largePreviewOriginalSourceImage = originalSource;
        largePreviewFilteredSourceImage = filteredSource;
        largePreviewSourceMode = availableLargePreviewSourceMode(largePreviewSourceMode);
        updateObjectOverlayControls();
        applyDisplaySettings();
        applyCurrentZ(currentZ);
        updateLargeImages();
        updateComparisonImages();
    }

    public void clearLargePreviewSourceChoices() {
        if (invokeOnEdtIfNeeded(new Runnable() {
            @Override public void run() {
                clearLargePreviewSourceChoices();
            }
        })) return;
        rememberCurrentDisplaySettings();
        forgetDisplaySettings(largePreviewOriginalSourceImage);
        forgetDisplaySettings(largePreviewFilteredSourceImage);
        clearLargePreviewSourceChoiceFields();
        updateObjectOverlayControls();
        applyDisplaySettings();
        applyCurrentZ(currentZ);
        updateLargeImages();
        updateComparisonImages();
    }

    public void setLargePreviewSourceMode(final SourceMode mode) {
        if (invokeOnEdtIfNeeded(new Runnable() {
            @Override public void run() {
                setLargePreviewSourceMode(mode);
            }
        })) return;
        SourceMode next = availableLargePreviewSourceMode(
                mode == SourceMode.FILTERED ? SourceMode.FILTERED : SourceMode.RAW);
        if (largePreviewSourceMode == next) return;
        rememberCurrentDisplaySettings();
        largePreviewSourceMode = next;
        applyDisplaySettings();
        applyCurrentZ(currentZ);
        updateLargeImages();
        updateComparisonImages();
    }

    public void clearLargePreviewImages() {
        if (invokeOnEdtIfNeeded(new Runnable() {
            @Override public void run() {
                clearLargePreviewImages();
            }
        })) return;
        clearObjectFilterPreviewState();
        forgetDisplaySettings(largePreviewFirstImage);
        forgetDisplaySettings(largePreviewSecondImage);
        forgetDisplaySettings(largePreviewThirdImage);
        forgetDisplaySettings(largePreviewOriginalSourceImage);
        forgetDisplaySettings(largePreviewFilteredSourceImage);
        usingCustomLargePreviewImages = false;
        largePreviewFirstImage = null;
        largePreviewSecondImage = null;
        largePreviewThirdImage = null;
        clearLargePreviewSourceChoiceFields();
        updateObjectOverlayControls();
        updateAdjustedPreviewImage();
        applyDisplaySettings();
        applyCurrentZ(currentZ);
        updateLargeImages();
        updateComparisonImages();
        updateComparisonButtonState();
    }

    public JButton largeViewButton() {
        return largeViewButton;
    }

    public JButton comparePreviewButton() {
        return comparePreviewButton;
    }

    public JButton displayControlsButton() {
        return displayControlsButton;
    }

    public JButton lutToggleButton() {
        return lutToggleButton;
    }

    public JCheckBox otsuOverlayCheckBox() {
        return otsuOverlayCheckBox;
    }

    public void setOtsuOverlayAvailable(final boolean available) {
        if (invokeOnEdtIfNeeded(new Runnable() {
            @Override public void run() {
                setOtsuOverlayAvailable(available);
            }
        })) return;
        if (otsuOverlayAvailable == available) {
            applyOtsuOverlayControlState();
            return;
        }
        otsuOverlayAvailable = available;
        applyOtsuOverlayControlState();
        updateAdjustedPreviewImage();
        updateLargeImages();
        updateComparisonImages();
    }

    public void setDisplayControlsAvailable(boolean available) {
        setDisplayControlsAvailable(available, available);
    }

    public void setDisplayControlsAvailable(boolean available,
                                            boolean lutToggleAvailable) {
        displayControlsAvailable = available;
        this.lutToggleAvailable = lutToggleAvailable;
        displayControlsButton.setVisible(available);
        displayControlsButton.setEnabled(available);
        lutToggleButton.setVisible(lutToggleAvailable);
        lutToggleButton.setEnabled(lutToggleAvailable);
        applyOtsuOverlayControlState();
        if (!available && !lutToggleAvailable && !largeDisplayControlsActivated) {
            hideDisplayControlsDialog();
            displaySettings = PreviewDisplaySettings.defaultFor(channelLutName);
            displaySettingsByImage.clear();
            displayControlImage = null;
            applyDisplaySettings();
        } else {
            refreshDisplayControlImage();
        }
        updateLutToggleButton();
        updateLargeDialogDisplayActionState();
    }

    public void requestBrightnessContrastControls() {
        requestBrightnessContrastControls(currentOwner());
    }

    public void requestBrightnessContrastControls(Window controlsOwner) {
        activateLargeDisplayControls();
        showDisplayControlsDialog(controlsOwner == null ? currentOwner() : controlsOwner);
    }

    public void requestGreyLutToggle() {
        if (!displayControlsAvailable && !lutToggleAvailable) {
            activateLargeDisplayControls();
        }
        togglePreviewLutMode();
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
        right.add(comparePreviewButton);
        right.add(displayControlsButton);
        right.add(lutToggleButton);
        right.add(otsuOverlayCheckBox);

        previewToolstripComponent = new JPanel(new BorderLayout(8, 0));
        previewToolstripComponent.setOpaque(false);
        previewToolstripComponent.add(left, BorderLayout.WEST);
        previewToolstripComponent.add(right, BorderLayout.EAST);
        applyOtsuOverlayControlState();
        applySourceControlsState();
        updateComparisonButtonState();
        return previewToolstripComponent;
    }

    public void setSourceToggleVisible(boolean visible) {
        sourceToggleVisible = visible;
        applySourceControlsState();
    }

    public void setSourceMode(SourceMode mode) {
        SourceMode next = mode == SourceMode.RAW ? SourceMode.RAW : SourceMode.FILTERED;
        boolean changed = sourceMode != next;
        sourceMode = next;
        applySourceControlsState();
        if (changed) {
            updateAdjustedPreviewImage();
        }
    }

    public void setSourceModeEnabled(boolean enabled) {
        sourceModeEnabled = enabled;
        applySourceControlsState();
    }

    public void setSourceModeChangeListener(SourceModeChangeListener listener) {
        sourceModeListener = listener;
    }

    public void setClickCapture(final File binFolder, final ClickStore store,
                                final String imageName, final int channelOneBased) {
        if (invokeOnEdtIfNeeded(new Runnable() {
            @Override public void run() {
                setClickCapture(binFolder, store, imageName, channelOneBased);
            }
        })) return;
        clickBinFolder = binFolder;
        clickStore = store;
        clickImageName = imageName == null ? "" : imageName;
        clickChannelOneBased = Math.max(0, channelOneBased);
        resetClickFlushTracking();
        wireLargeObjectClickListener();
        wireInlineObjectClickListener();
        applyClickOverlayMarkers();
    }

    public void clearClickCapture() {
        if (invokeOnEdtIfNeeded(new Runnable() {
            @Override public void run() {
                clearClickCapture();
            }
        })) return;
        flushClicksSync();
        clearClickOverlaysFromKnownImages();
        clickBinFolder = null;
        clickStore = null;
        clickImageName = "";
        clickChannelOneBased = 0;
        resetClickFlushTracking();
        wireLargeObjectClickListener();
        clearInlineObjectClickListener();
    }

    public void flushClicksSync() {
        drainPendingClickWrites();
        final File binFolder;
        final ClickStore store;
        final int generation;
        synchronized (clickFlushStateLock) {
            binFolder = clickBinFolder;
            store = clickStore;
            generation = clickWriteGeneration;
            if (binFolder == null || store == null || clickFlushedGeneration >= generation) {
                return;
            }
        }
        try {
            ClicksConfigIO.write(binFolder, store);
            markClickFlushed(binFolder, store, generation);
        } catch (IOException e) {
            IJ.log("[FLASH] Warning: could not flush click selections to "
                    + binFolder.getAbsolutePath() + ": " + e.getMessage());
        }
    }

    public boolean objectOverlaySelected() {
        return isObjectOverlaySelected();
    }

    public void setObjectOverlaySelected(boolean selected) {
        objectOverlayCheck.setSelected(selected);
        updateObjectOverlayControls();
        updateAdjustedPreviewImage();
    }

    public void setObjectOverlayEnabled(boolean enabled) {
        objectOverlayEnabled = enabled;
        updateObjectOverlayControls();
    }

    public void resetStageToolstripState() {
        sourceModeListener = null;
        setSourceToggleVisible(false);
        setSourceMode(SourceMode.FILTERED);
        setSourceModeEnabled(true);
        setObjectOverlayEnabled(true);
        setOtsuOverlayAvailable(false);
        setObjectSizeGuide(null);
        clearComparisonPreview();
        largeDisplayControlsActivated = false;
        if (!displayControlsAvailable) {
            hideDisplayControlsDialog();
            displaySettings = PreviewDisplaySettings.defaultFor(channelLutName);
            displaySettingsByImage.clear();
            displayControlImage = null;
            selectChannelLutMode();
            applyDisplaySettings();
        }
        updateLargeDialogDisplayActionState();
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

    ImagePlus largePreviewFirstImageForTest() {
        return effectiveLargePreviewFirstImage();
    }

    SourceMode largePreviewSourceModeForTest() {
        return availableLargePreviewSourceMode(largePreviewSourceMode);
    }

    boolean comparisonPreviewVisibleForTest() {
        return comparePreviewButton.isVisible();
    }

    boolean comparisonPreviewEnabledForTest() {
        return comparePreviewButton.isEnabled();
    }

    ImagePlus comparisonPreviousImageForTest() {
        return comparisonPreviousImage;
    }

    boolean comparisonRestoreActionAvailableForTest() {
        return comparisonRestoreAction != null;
    }

    void setComparisonPreviewDialogForTest(ComparisonPreviewDialog dialog) {
        comparisonPreviewDialog = dialog;
        wireComparisonDialog();
        updateComparisonImages();
        updateComparisonDialogDisplayActionState();
    }

    Window comparisonPreviewOwnerForTest() {
        return comparisonPreviewDialog == null ? null : comparisonPreviewDialog.ownerForTest();
    }

    void disposeComparisonPreviewForTest() {
        if (comparisonPreviewDialog != null) {
            comparisonPreviewDialog.dispose();
            comparisonPreviewDialog = null;
        }
    }

    void setDisplayRangeForTest(double min, double max) {
        displayControls.setRange(min, max);
        updateDisplaySettingsFromControls();
    }

    void setDisplayRangeForTest(double min, double max, boolean adjusting) {
        displayControls.setRange(min, max);
        updateDisplaySettingsFromControls(!adjusting, !adjusting);
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

    ObjectSizeFilterPreview.Summary objectSizeGuideForTest() {
        return objectSizeGuide;
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
        setObjectOverlaySelected(selected);
    }

    void setObjectOverlaySourceForTest(String sourceLabel) {
        objectOverlaySourceChoice.setSelectedItem(sourceLabel);
        updateAdjustedPreviewImage();
    }

    String adjustedImageTitleForTest() {
        return adjustedPreview.titleTextForTest();
    }

    ImagePlus renderObjectPreviewNowForTest() {
        return renderObjectPreviewSynchronously();
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

    public static ImagePlus duplicateForComparison(ImagePlus image, String title) {
        if (image == null) return null;
        try {
            ImagePlus copy = image.duplicate();
            String safeTitle = title == null || title.trim().isEmpty()
                    ? image.getTitle()
                    : title.trim();
            copy.setTitle(safeTitle);
            copy.setCalibration(image.getCalibration());
            copy.setOpenAsHyperStack(image.isHyperStack());
            return copy;
        } catch (RuntimeException e) {
            return null;
        }
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
        objectOverlayCheck.setText(layout == PreviewLayout.HORIZONTAL_SLIM
                ? "Overlay"
                : "Overlay objects");
        objectOverlayCheck.setOpaque(false);
        objectOverlayControls.add(objectOverlayCheck);
        if (layout != PreviewLayout.HORIZONTAL_SLIM) {
            objectOverlayControls.add(new JLabel("over"));
            objectOverlayControls.add(objectOverlaySourceChoice);
        }
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
                setSourceMode(selected);
                if (sourceModeListener != null) {
                    sourceModeListener.sourceModeChanged(sourceMode);
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

    private void applyOtsuOverlayControlState() {
        boolean visible = otsuOverlayAvailable && lutToggleAvailable;
        otsuOverlayCheckBox.setVisible(visible);
        otsuOverlayCheckBox.setEnabled(visible);
        otsuOverlayCheckBox.setToolTipText(
                "Show the filtered preview with an Otsu threshold red overlay.");
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

    private void wireComparePreviewButton() {
        comparePreviewButton.addActionListener(new ActionListener() {
            @Override public void actionPerformed(ActionEvent e) {
                showComparisonPreview();
            }
        });
        updateComparisonButtonState();
    }

    private void wireDisplayControlsButton() {
        displayControlsButton.addActionListener(new ActionListener() {
            @Override public void actionPerformed(ActionEvent e) {
                if (!displayControlsButton.isEnabled()) return;
                showDisplayControlsDialog(currentOwner());
            }
        });
        lutToggleButton.addActionListener(new ActionListener() {
            @Override public void actionPerformed(ActionEvent e) {
                togglePreviewLutMode();
            }
        });
        otsuOverlayCheckBox.addActionListener(new ActionListener() {
            @Override public void actionPerformed(ActionEvent e) {
                updateAdjustedPreviewImage();
                updateLargeImages();
                updateComparisonImages();
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
                updateDisplaySettingsFromControls(!adjusting, !adjusting);
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

    private void showComparisonPreview() {
        if (GraphicsEnvironment.isHeadless() || !comparisonPreviewAvailable()) return;
        Window currentOwner = currentOwner();
        if (comparisonPreviewDialog == null
                || comparisonPreviewDialog.ownerForTest() != currentOwner) {
            if (comparisonPreviewDialog != null) {
                comparisonPreviewDialog.dispose();
            }
            comparisonPreviewDialog = new ComparisonPreviewDialog(currentOwner);
            wireComparisonDialog();
        }
        updateComparisonImages();
        if (!comparisonPreviewDialog.isVisible()) {
            comparisonPreviewDialog.setLocationRelativeTo(currentOwner);
        }
        comparisonPreviewDialog.raiseForUser();
    }

    private void showDisplayControlsDialog(Window currentOwner) {
        if (GraphicsEnvironment.isHeadless()) return;
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
        largePreviewDialog.setSourceChoiceListener(new LargePreviewDialog.SourceChoiceListener() {
            @Override public void sourceChoiceChanged(SourceMode mode) {
                setLargePreviewSourceMode(mode);
            }
        });
        largePreviewDialog.setOverlayChoiceListener(new LargePreviewDialog.OverlayChoiceListener() {
            @Override public void overlayToggleChanged(boolean selected) {
                setObjectOverlaySelected(selected);
            }

            @Override public void overlaySourceChanged(boolean rawSource) {
                setObjectOverlaySourceRaw(rawSource);
            }
        });
        largePreviewDialog.setDisplayActionListener(new LargePreviewDialog.DisplayActionListener() {
            @Override public void adjustBrightnessContrastRequested() {
                activateLargeDisplayControls();
                showDisplayControlsDialog(largePreviewDialog);
            }

            @Override public void lutToggleRequested() {
                togglePreviewLutMode();
            }
        });
        wireLargeObjectClickListener();
        updateLargeDialogDisplayActionState();
    }

    private void wireLargeObjectClickListener() {
        if (largePreviewDialog == null) return;
        if (!clickCaptureAvailable()) {
            largePreviewDialog.setObjectClickListener(null);
            return;
        }
        largePreviewDialog.setObjectClickListener(new LargePreviewDialog.ObjectClickListener() {
            @Override public void objectClicked(int label, int z, double x, double y,
                                                boolean positive, boolean clear) {
                handleLargeObjectClick(label, z, x, y, positive, clear);
            }
        });
    }

    private void wireInlineObjectClickListener() {
        if (!clickCaptureAvailable()) {
            clearInlineObjectClickListener();
            return;
        }
        ImagePreviewPanel.PixelClickListener listener = new ImagePreviewPanel.PixelClickListener() {
            @Override public void pixelClicked(ImagePreviewPanel src, double imageX, double imageY,
                                               int z, int button, int modifiers) {
                dispatchInlineObjectClick(src, imageX, imageY, z, button, modifiers);
            }
        };
        originalPreview.setPixelClickListener(listener);
        adjustedPreview.setPixelClickListener(listener);
    }

    private void clearInlineObjectClickListener() {
        originalPreview.setPixelClickListener(null);
        adjustedPreview.setPixelClickListener(null);
    }

    private void dispatchInlineObjectClick(ImagePreviewPanel source,
                                           double x, double y, int z,
                                           int button, int modifiers) {
        if (!clickCaptureAvailable()) return;
        ObjectClickDispatcher.dispatch(inlineObjectLabelImage(source), x, y, z, button, modifiers,
                new ObjectClickDispatcher.Handler() {
                    @Override public void objectClicked(int label, int clickZ,
                                                        double clickX, double clickY,
                                                        boolean positive, boolean clear) {
                        handleLargeObjectClick(label, clickZ, clickX, clickY, positive, clear);
                    }
                });
    }

    private ImagePlus inlineObjectLabelImage(ImagePreviewPanel source) {
        if (objectTrueLabelMap != null) return objectTrueLabelMap;
        if (largePreviewThirdImage != null) return largePreviewThirdImage;
        if (source == adjustedPreview && isLikelyObjectLabelImage(adjustedImage)) {
            return adjustedImage;
        }
        if (isLikelyObjectLabelImage(adjustedImage)) {
            return adjustedImage;
        }
        return null;
    }

    private static boolean isLikelyObjectLabelImage(ImagePlus image) {
        if (image == null) return false;
        String title;
        try {
            title = image.getTitle();
        } catch (RuntimeException e) {
            return false;
        }
        String lower = title == null ? "" : title.trim().toLowerCase(java.util.Locale.ROOT);
        return lower.contains("object label")
                || lower.contains("label preview")
                || lower.contains("label map")
                || lower.contains("stardist label")
                || lower.contains("cellpose label");
    }

    private void wireComparisonDialog() {
        if (comparisonPreviewDialog == null) return;
        comparisonPreviewDialog.setSliceListener(new ComparisonPreviewDialog.SliceListener() {
            @Override public void zSliceChanged(int zSlice) {
                applyCurrentZ(zSlice);
            }
        });
        comparisonPreviewDialog.setDisplayActionListener(new ComparisonPreviewDialog.DisplayActionListener() {
            @Override public void adjustBrightnessContrastRequested() {
                activateLargeDisplayControls();
                showDisplayControlsDialog(comparisonPreviewDialog);
            }

            @Override public void lutToggleRequested() {
                togglePreviewLutMode();
            }
        });
        updateComparisonDialogDisplayActionState();
        updateComparisonRestoreActionState();
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
            if (comparisonPreviewDialog != null) {
                comparisonPreviewDialog.setCurrentZ(currentZ);
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
            ImagePlus firstImage = effectiveLargePreviewFirstImage();
            if (firstImage != null) {
                int count = effectiveSliceCount(firstImage);
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
        applyClickOverlayMarkers();
        if (usingCustomLargePreviewImages) {
            if (hasLargePreviewSourceChoices()) {
                largePreviewDialog.setSourceChoices(
                        largePreviewOriginalSourceImage,
                        displaySettingsForImage(largePreviewOriginalSourceImage),
                        largePreviewFilteredSourceImage,
                        displaySettingsForImage(largePreviewFilteredSourceImage),
                        availableLargePreviewSourceMode(largePreviewSourceMode));
            } else {
                largePreviewDialog.clearSourceChoices();
            }
            ImagePlus extraDisplayImage = objectTrueLabelMap != null
                    ? generatedObjectOverlayImage
                    : largePreviewThirdImage;
            ImagePlus objectClickLabelImage = objectTrueLabelMap != null
                    ? objectTrueLabelMap
                    : largePreviewThirdImage;
            largePreviewDialog.setImages(
                    effectiveLargePreviewFirstImage(),
                    largePreviewSecondImage,
                    extraDisplayImage,
                    objectClickLabelImage,
                    currentZ);
        } else {
            largePreviewDialog.clearSourceChoices();
            largePreviewDialog.setImages(originalImage, adjustedImageForLargePreview(), currentZ);
        }
        largePreviewDialog.setObjectSizeGuide(objectSizeGuide);
        largePreviewDialog.setAdjustedStatusText(adjustedPreview.statusTextForTest());
        largePreviewDialog.setDisplaySettings(largeFirstDisplaySettings(),
                largeSecondDisplaySettings());
        pushObjectOverlayStateToLargeDialog();
        applyClickOverlayMarkers();
    }

    private void updateComparisonImages() {
        if (comparisonPreviewDialog == null) return;
        ImagePlus rawSource = comparisonRawSourceImage();
        ImagePlus filteredSource = comparisonFilteredSourceImage();
        if (rawSource != null || filteredSource != null) {
            comparisonPreviewDialog.setSourceChoices(
                    rawSource,
                    displaySettingsForImage(rawSource),
                    filteredSource,
                    displaySettingsForImage(filteredSource));
        } else {
            comparisonPreviewDialog.clearSourceChoices();
        }
        ImagePlus currentDisplayImage = objectTrueLabelMap != null
                ? generatedObjectOverlayImage
                : largePreviewThirdImage;
        ImagePlus currentClickLabelImage = objectTrueLabelMap != null
                ? objectTrueLabelMap
                : largePreviewThirdImage;
        comparisonPreviewDialog.setImages(currentDisplayImage, currentClickLabelImage,
                comparisonPreviousImage, comparisonPreviousImage, currentZ);
        comparisonPreviewDialog.setObjectSizeGuide(objectSizeGuide);
        comparisonPreviewDialog.setPreviewStatus(
                adjustedPreview.statusTextForTest(),
                comparisonPreviousStatus);
        updateComparisonDialogDisplayActionState();
        updateComparisonRestoreActionState();
    }

    private ImagePlus adjustedImageForLargePreview() {
        return generatedOtsuOverlayImage != null ? generatedOtsuOverlayImage : adjustedImage;
    }

    private void updateComparisonButtonState() {
        comparePreviewButton.setVisible(comparisonPreviewVisible);
        comparePreviewButton.setEnabled(comparisonPreviewAvailable());
    }

    private boolean comparisonPreviewAvailable() {
        return comparisonPreviewVisible
                && largePreviewThirdImage != null
                && comparisonPreviousImage != null;
    }

    private ImagePlus comparisonRawSourceImage() {
        return largePreviewOriginalSourceImage != null
                ? largePreviewOriginalSourceImage
                : largePreviewFirstImage;
    }

    private ImagePlus comparisonFilteredSourceImage() {
        return largePreviewFilteredSourceImage != null
                ? largePreviewFilteredSourceImage
                : largePreviewSecondImage;
    }

    private void updateAdjustedPreviewImage() {
        if (objectTrueLabelMap != null) {
            requestObjectPreviewRender();
            return;
        }
        updateAdjustedPreviewImageLegacy();
    }

    private void updateAdjustedPreviewImageLegacy() {
        ImagePlus displayImage = adjustedImage;
        ImagePlus oldOverlay = generatedObjectOverlayImage;
        ImagePlus oldOtsuOverlay = generatedOtsuOverlayImage;
        generatedObjectOverlayImage = null;
        generatedOtsuOverlayImage = null;
        if (isObjectOverlaySelected()) {
            ImagePlus sourceImage = selectedObjectOverlaySourceImage();
            ImagePlus overlay = ObjectOverlayRenderer.renderOverlay(
                    sourceImage, largePreviewThirdImage, displaySettingsForImage(sourceImage));
            if (overlay != null) {
                displayImage = overlay;
                generatedObjectOverlayImage = overlay;
            }
        } else if (isOtsuOverlaySelected()) {
            ImagePlus overlay = ThresholdOverlayRenderer.renderOtsuRedOverlay(adjustedImage);
            if (overlay != null) {
                displayImage = overlay;
                generatedOtsuOverlayImage = overlay;
            }
        }
        adjustedPreview.setImage(displayImage);
        if (oldOverlay != null && oldOverlay != generatedObjectOverlayImage) {
            oldOverlay.flush();
        }
        if (oldOtsuOverlay != null && oldOtsuOverlay != generatedOtsuOverlayImage) {
            oldOtsuOverlay.flush();
        }
        applyClickOverlayMarkers();
    }

    private void requestObjectPreviewRender() {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(new Runnable() {
                @Override public void run() {
                    requestObjectPreviewRender();
                }
            });
            return;
        }
        if (objectTrueLabelMap == null) {
            updateAdjustedPreviewImageLegacy();
            return;
        }
        final ImagePlus labelMap = objectTrueLabelMap;
        final Set<Integer> removed = objectRemovedLabels;
        final boolean show = showRemovedObjects;
        final ImagePlus source = isObjectOverlaySelected()
                ? selectedObjectOverlaySourceImage()
                : null;
        final PreviewDisplaySettings settings = displaySettingsForImage(source);
        final long generation = beginAdjustedPreviewRequest(adjustedMessage);

        new SwingWorker<ImagePlus, Void>() {
            @Override protected ImagePlus doInBackground() {
                ImagePlus rendered = ObjectOverlayRenderer.renderFiltered(
                        source, labelMap, removed, show, settings);
                applyObjectPreviewTitle(rendered, source != null);
                return rendered;
            }

            @Override protected void done() {
                ImagePlus rendered = null;
                try {
                    rendered = get();
                    if (!applyObjectPreviewRenderResult(generation, rendered)
                            && rendered != null) {
                        rendered.flush();
                    }
                } catch (Exception e) {
                    if (rendered != null) {
                        rendered.flush();
                    }
                    if (generation == adjustedPreviewGeneration) {
                        setAdjustedStateOnEdt(PreviewState.ERROR, e.getMessage());
                    }
                }
            }
        }.execute();
    }

    private ImagePlus renderObjectPreviewSynchronously() {
        final long generation = beginAdjustedPreviewRequest(adjustedMessage);
        final ImagePlus labelMap = objectTrueLabelMap;
        if (labelMap == null) {
            return null;
        }
        final Set<Integer> removed = objectRemovedLabels;
        final boolean show = showRemovedObjects;
        final ImagePlus source = isObjectOverlaySelected()
                ? selectedObjectOverlaySourceImage()
                : null;
        final PreviewDisplaySettings settings = displaySettingsForImage(source);
        final ImagePlus rendered = ObjectOverlayRenderer.renderFiltered(
                source, labelMap, removed, show, settings);
        applyObjectPreviewTitle(rendered, source != null);
        final boolean[] applied = new boolean[] {false};
        if (SwingUtilities.isEventDispatchThread()) {
            applied[0] = applyObjectPreviewRenderResult(generation, rendered);
        } else {
            invokeOnEdtIfNeeded(new Runnable() {
                @Override public void run() {
                    applied[0] = applyObjectPreviewRenderResult(generation, rendered);
                }
            });
        }
        if (!applied[0] && rendered != null) {
            rendered.flush();
            return null;
        }
        return rendered;
    }

    private boolean applyObjectPreviewRenderResult(long generation, ImagePlus rendered) {
        if (!SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException("Object render result must be applied on EDT.");
        }
        if (generation != adjustedPreviewGeneration) {
            return false;
        }
        ImagePlus old = generatedObjectOverlayImage;
        generatedObjectOverlayImage = rendered;
        adjustedPreview.setImage(rendered);
        if (old != null && old != rendered) {
            old.flush();
        }
        if (adjustedState == PreviewState.RUNNING) {
            setAdjustedStateOnEdt(PreviewState.READY, adjustedMessage);
        }
        applyCurrentZ(currentZ);
        updateLargeImages();
        updateComparisonImages();
        applyClickOverlayMarkers();
        return true;
    }

    private void applyObjectPreviewTitle(ImagePlus rendered, boolean overlay) {
        if (rendered == null) return;
        if (overlay) return;
        rendered.setTitle(objectFilterObjectCount > 0
                ? "Object label preview"
                : "Object label preview (no objects)");
    }

    private void handleLargeObjectClick(int label, int z, double x, double y,
                                        boolean positive, boolean clear) {
        if (!clickCaptureAvailable() || label <= 0) return;
        if (clear) {
            clickStore.clearForObject(clickImageName, clickChannelOneBased, label);
        } else {
            ClickStore.Verdict verdict = positive
                    ? ClickStore.Verdict.POSITIVE
                    : ClickStore.Verdict.NEGATIVE;
            clickStore.add(new ClickStore.Click(
                    clickImageName,
                    clickChannelOneBased,
                    label,
                    z,
                    x,
                    y,
                    verdict,
                    System.currentTimeMillis()));
        }
        scheduleClickWrite(markClickWriteNeeded());
        applyClickOverlayMarkers();
        updateAdjustedPreviewImage();
        updateLargeImages();
        updateComparisonImages();
    }

    private boolean clickCaptureAvailable() {
        return clickBinFolder != null
                && clickStore != null
                && clickChannelOneBased > 0
                && clickImageName != null
                && !clickImageName.trim().isEmpty();
    }

    private void scheduleClickWrite(final int generation) {
        final File binFolder = clickBinFolder;
        final ClickStore store = clickStore;
        if (binFolder == null || store == null || generation <= 0) return;
        executeClickWriteTask(new Runnable() {
            @Override public void run() {
                try {
                    ClicksConfigIO.write(binFolder, store);
                } catch (IOException e) {
                    IJ.log("[FLASH] Warning: could not write click selections to "
                            + binFolder.getAbsolutePath() + ": " + e.getMessage());
                }
            }
        });
    }

    private int markClickWriteNeeded() {
        synchronized (clickFlushStateLock) {
            clickWriteGeneration++;
            return clickWriteGeneration;
        }
    }

    private void markClickFlushed(File binFolder, ClickStore store, int generation) {
        synchronized (clickFlushStateLock) {
            if (clickBinFolder == binFolder && clickStore == store
                    && clickFlushedGeneration < generation) {
                clickFlushedGeneration = generation;
            }
        }
    }

    private void resetClickFlushTracking() {
        synchronized (clickFlushStateLock) {
            clickWriteGeneration = 0;
            clickFlushedGeneration = 0;
        }
    }

    private static void executeClickWriteTask(Runnable task) {
        if (task == null) return;
        synchronized (CLICK_WRITE_EXECUTOR_LOCK) {
            if (clickWriteExecutor == null
                    || clickWriteExecutor.isShutdown()
                    || clickWriteExecutor.isTerminated()) {
                clickWriteExecutor = newClickWriteExecutor();
            }
            clickWriteExecutor.execute(task);
        }
    }

    private static void drainPendingClickWrites() {
        final ExecutorService executor;
        synchronized (CLICK_WRITE_EXECUTOR_LOCK) {
            executor = clickWriteExecutor;
            clickWriteExecutor = null;
        }
        if (executor == null) return;
        executor.shutdown();
        try {
            if (!executor.awaitTermination(CLICK_WRITE_FLUSH_TIMEOUT_MILLIS,
                    TimeUnit.MILLISECONDS)) {
                IJ.log("[FLASH] Warning: timed out waiting for click selections writer.");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            IJ.log("[FLASH] Warning: interrupted while flushing click selections.");
        }
    }

    static void drainPendingClickWritesForTest() {
        drainPendingClickWrites();
    }

    private void applyClickOverlayMarkers() {
        if (!clickCaptureAvailable()) return;
        List<ClickStore.Click> clicks =
                clickStore.forImageAndChannel(clickImageName, clickChannelOneBased);
        Map<ImagePlus, Boolean> visited = new IdentityHashMap<ImagePlus, Boolean>();
        applyClickOverlay(originalImage, clicks, visited);
        applyClickOverlay(adjustedImage, clicks, visited);
        applyClickOverlay(largePreviewFirstImage, clicks, visited);
        applyClickOverlay(largePreviewSecondImage, clicks, visited);
        applyClickOverlay(largePreviewThirdImage, clicks, visited);
        applyClickOverlay(largePreviewOriginalSourceImage, clicks, visited);
        applyClickOverlay(largePreviewFilteredSourceImage, clicks, visited);
        applyClickOverlay(comparisonPreviousImage, clicks, visited);
        applyClickOverlay(generatedObjectOverlayImage, clicks, visited);
        repaintPreviews();
    }

    private void clearClickOverlaysFromKnownImages() {
        Map<ImagePlus, Boolean> visited = new IdentityHashMap<ImagePlus, Boolean>();
        applyClickOverlay(originalImage, null, visited);
        applyClickOverlay(adjustedImage, null, visited);
        applyClickOverlay(largePreviewFirstImage, null, visited);
        applyClickOverlay(largePreviewSecondImage, null, visited);
        applyClickOverlay(largePreviewThirdImage, null, visited);
        applyClickOverlay(largePreviewOriginalSourceImage, null, visited);
        applyClickOverlay(largePreviewFilteredSourceImage, null, visited);
        applyClickOverlay(comparisonPreviousImage, null, visited);
        applyClickOverlay(generatedObjectOverlayImage, null, visited);
        repaintPreviews();
    }

    private void applyClickOverlay(ImagePlus image, List<ClickStore.Click> clicks,
                                   Map<ImagePlus, Boolean> visited) {
        if (image == null || visited.containsKey(image)) return;
        visited.put(image, Boolean.TRUE);
        Overlay overlay = image.getOverlay();
        boolean hadOverlay = overlay != null;
        if (overlay == null) {
            overlay = new Overlay();
        }
        removeClickRois(overlay);
        if (clicks != null) {
            for (ClickStore.Click click : clicks) {
                if (click == null) continue;
                PointRoi roi = new PointRoi(click.x, click.y);
                roi.setName(CLICK_ROI_PREFIX + click.channelOneBased + ":"
                        + click.label + ":" + click.timestampMs);
                roi.setStrokeColor(click.verdict == ClickStore.Verdict.POSITIVE
                        ? CLICK_POSITIVE_COLOR
                        : CLICK_NEGATIVE_COLOR);
                roi.setPosition(click.channelOneBased, Math.max(1, click.z), 1);
                overlay.add(roi);
            }
        }
        if (overlay.size() == 0) {
            image.setOverlay(null);
        } else if (hadOverlay || clicks != null) {
            image.setOverlay(overlay);
        }
    }

    private void removeClickRois(Overlay overlay) {
        if (overlay == null) return;
        for (int i = overlay.size() - 1; i >= 0; i--) {
            Roi roi = overlay.get(i);
            String name = roi == null ? null : roi.getName();
            if (name != null && name.startsWith(CLICK_ROI_PREFIX)) {
                overlay.remove(i);
            }
        }
    }

    private void repaintPreviews() {
        originalPreview.repaint();
        adjustedPreview.repaint();
        if (largePreviewDialog != null) largePreviewDialog.repaint();
        if (comparisonPreviewDialog != null) comparisonPreviewDialog.repaint();
    }

    private void updateObjectOverlayControls() {
        boolean available = objectOverlayAvailable();
        boolean hasSource = largePreviewFirstImage != null || largePreviewSecondImage != null;
        objectOverlayControls.setVisible(available);
        objectOverlayCheck.setEnabled(available && hasSource && objectOverlayEnabled);
        objectOverlaySourceChoice.setEnabled(available && hasSource && objectOverlayEnabled
                && objectOverlayCheck.isSelected());
        if (!available && objectTrueLabelMap == null) {
            closeGeneratedObjectOverlayImage();
        }
        objectOverlayControls.revalidate();
        objectOverlayControls.repaint();
        pushObjectOverlayStateToLargeDialog();
    }

    /**
     * Mirrors the panel's object-overlay state onto the Large preview dialog so its overlay
     * controls drive this renderer instead of sitting greyed out. The dialog cannot render the
     * filtered overlay itself (it lacks the removed-label data), so it forwards control changes
     * back here through {@link LargePreviewDialog.OverlayChoiceListener}.
     */
    private void pushObjectOverlayStateToLargeDialog() {
        if (largePreviewDialog == null) return;
        if (objectTrueLabelMap == null) {
            largePreviewDialog.setExternalOverlayState(false, false, false, false, false);
            return;
        }
        boolean hasSource = largePreviewFirstImage != null || largePreviewSecondImage != null;
        boolean toggleEnabled = objectOverlayEnabled && hasSource;
        boolean selected = objectOverlayCheck.isSelected();
        largePreviewDialog.setExternalOverlayState(
                true,
                toggleEnabled,
                selected,
                toggleEnabled && selected,
                overlaySourceIsRaw());
    }

    private boolean overlaySourceIsRaw() {
        if (sourceToggleVisible) {
            return sourceMode == SourceMode.RAW;
        }
        Object selected = objectOverlaySourceChoice.getSelectedItem();
        return selected != null && "Raw image".equals(selected.toString());
    }

    private void setObjectOverlaySourceRaw(boolean raw) {
        if (sourceToggleVisible) {
            SourceMode mode = raw ? SourceMode.RAW : SourceMode.FILTERED;
            if (sourceMode != mode) {
                setSourceMode(mode);
                if (sourceModeListener != null) {
                    sourceModeListener.sourceModeChanged(sourceMode);
                }
            }
            return;
        }
        objectOverlaySourceChoice.setSelectedItem(raw ? "Raw image" : "Filtered image");
        updateAdjustedPreviewImage();
    }

    private boolean objectOverlayAvailable() {
        return usingCustomLargePreviewImages
                && largePreviewThirdImage != null
                && (adjustedImage == largePreviewThirdImage
                    || (objectTrueLabelMap != null && objectTrueLabelMap == largePreviewThirdImage));
    }

    private boolean isObjectOverlaySelected() {
        return objectOverlayAvailable()
                && objectOverlayCheck.isSelected()
                && (largePreviewFirstImage != null || largePreviewSecondImage != null);
    }

    private boolean isOtsuOverlaySelected() {
        return otsuOverlayAvailable
                && lutToggleAvailable
                && otsuOverlayCheckBox.isSelected()
                && adjustedImage != null
                && objectTrueLabelMap == null;
    }

    private ImagePlus selectedObjectOverlaySourceImage() {
        Object selected = objectOverlaySourceChoice.getSelectedItem();
        boolean raw = sourceToggleVisible
                ? sourceMode == SourceMode.RAW
                : selected != null && "Raw image".equals(selected.toString());
        if (hasLargePreviewSourceChoices()) {
            ImagePlus source = largePreviewSourceImage(raw ? SourceMode.RAW : SourceMode.FILTERED);
            if (source != null) return source;
        }
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
        if (comparisonPreviewDialog != null) {
            comparisonPreviewDialog.setPreviewStatus(status, comparisonPreviousStatus);
        }
    }

    private void setAdjustedStateOnEdt(PreviewState state, String message) {
        adjustedState = state == null ? PreviewState.EMPTY : state;
        adjustedMessage = message == null ? "" : message.trim();
        applyAdjustedStatus();
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
        if (!displayRangeControlsActive()) {
            displayControlImage = null;
            displayRangeInitialized = false;
            displaySettingsByImage.clear();
            displaySettings = displaySettingsWithoutRange();
            applyDisplaySettings();
            return;
        }
        if (!displaySettingsAvailable()) {
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
        updateDisplaySettingsFromControls(notifyListener, true);
    }

    private void updateDisplaySettingsFromControls(boolean notifyListener,
                                                   boolean renderObjectOverlay) {
        PreviewDisplaySettings.LutMode mode = selectedLutMode();
        if (displayRangeControlsActive()) {
            displaySettings = PreviewDisplaySettings.of(
                    displayControls.getMinValue(),
                    displayControls.getMaxValue(),
                    mode,
                    channelLutName);
            rememberCurrentDisplaySettings();
        } else {
            displayControlImage = null;
            displayRangeInitialized = false;
            displaySettingsByImage.clear();
            displaySettings = PreviewDisplaySettings.of(
                    Double.NaN,
                    Double.NaN,
                    mode,
                    channelLutName);
        }
        applyDisplaySettings(renderObjectOverlay);
        updateLutToggleButton();
        if (notifyListener && displaySettingsChangeListener != null) {
            displaySettingsChangeListener.displaySettingsChanged(displaySettings);
        }
    }

    private void togglePreviewLutMode() {
        if (!displaySettingsAvailable()) return;
        int nextIndex = lutModeChoice.getSelectedIndex() == 0 ? 1 : 0;
        lutModeChoice.setSelectedIndex(nextIndex);
        updateLutToggleButton();
    }

    private void applyDisplaySettings() {
        applyDisplaySettings(true);
    }

    private void applyDisplaySettings(boolean renderObjectOverlay) {
        boolean adjustedIsObjectPreview = usingCustomLargePreviewImages
                && largePreviewThirdImage != null;
        originalPreview.setDisplaySettingsEnabled(true);
        adjustedPreview.setDisplaySettingsEnabled(!adjustedIsObjectPreview);
        originalPreview.setDisplaySettings(displaySettingsForImage(originalImage));
        adjustedPreview.setDisplaySettings(displaySettingsForImage(adjustedImage));
        if (renderObjectOverlay && isObjectOverlaySelected()) {
            updateAdjustedPreviewImage();
        }
        if (largePreviewDialog != null && (renderObjectOverlay
                || !usingCustomLargePreviewImages
                || largePreviewThirdImage == null)) {
            largePreviewDialog.setDisplaySettings(largeFirstDisplaySettings(),
                    largeSecondDisplaySettings());
        }
        if (comparisonPreviewDialog != null) {
            updateComparisonImages();
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
        if (!displaySettingsAvailable()) {
            return PreviewDisplaySettings.defaultFor(channelLutName);
        }
        if (!roleAwareDisplaySettings()) {
            return displaySettings;
        }
        PreviewDisplaySettings settings = image == null ? null : displaySettingsByImage.get(image);
        return displaySettingsWithCurrentLut(settings);
    }

    private PreviewDisplaySettings displaySettingsWithCurrentLut(PreviewDisplaySettings settings) {
        PreviewDisplaySettings rangeSource = settings == null
                ? PreviewDisplaySettings.defaultFor(channelLutName)
                : settings;
        PreviewDisplaySettings.LutMode mode = displaySettings == null
                ? PreviewDisplaySettings.LutMode.CHANNEL
                : displaySettings.getLutMode();
        return PreviewDisplaySettings.of(
                rangeSource.getDisplayMin(),
                rangeSource.getDisplayMax(),
                mode,
                channelLutName);
    }

    private PreviewDisplaySettings largeFirstDisplaySettings() {
        return usingCustomLargePreviewImages
                ? displaySettingsForImage(effectiveLargePreviewFirstImage())
                : displaySettingsForImage(originalImage);
    }

    private PreviewDisplaySettings largeSecondDisplaySettings() {
        return usingCustomLargePreviewImages
                ? displaySettingsForImage(largePreviewSecondImage)
                : displaySettingsForImage(adjustedImage);
    }

    private boolean roleAwareDisplaySettings() {
        return usingCustomLargePreviewImages
                && (effectiveLargePreviewFirstImage() != null || largePreviewSecondImage != null);
    }

    private ImagePlus effectiveLargePreviewFirstImage() {
        if (hasLargePreviewSourceChoices()) {
            ImagePlus source = largePreviewSourceImage(largePreviewSourceMode);
            if (source != null) return source;
        }
        return largePreviewFirstImage;
    }

    private ImagePlus largePreviewSourceImage(SourceMode mode) {
        SourceMode safeMode = mode == SourceMode.FILTERED ? SourceMode.FILTERED : SourceMode.RAW;
        ImagePlus preferred = safeMode == SourceMode.FILTERED
                ? largePreviewFilteredSourceImage
                : largePreviewOriginalSourceImage;
        if (preferred != null) return preferred;
        return safeMode == SourceMode.FILTERED
                ? largePreviewOriginalSourceImage
                : largePreviewFilteredSourceImage;
    }

    private SourceMode availableLargePreviewSourceMode(SourceMode requestedMode) {
        SourceMode safeMode = requestedMode == SourceMode.FILTERED
                ? SourceMode.FILTERED
                : SourceMode.RAW;
        if (safeMode == SourceMode.FILTERED && largePreviewFilteredSourceImage != null) {
            return SourceMode.FILTERED;
        }
        if (safeMode == SourceMode.RAW && largePreviewOriginalSourceImage != null) {
            return SourceMode.RAW;
        }
        if (largePreviewOriginalSourceImage != null) return SourceMode.RAW;
        if (largePreviewFilteredSourceImage != null) return SourceMode.FILTERED;
        return safeMode;
    }

    private boolean hasLargePreviewSourceChoices() {
        return largePreviewOriginalSourceImage != null || largePreviewFilteredSourceImage != null;
    }

    private void clearLargePreviewSourceChoiceFields() {
        largePreviewOriginalSourceImage = null;
        largePreviewFilteredSourceImage = null;
        largePreviewSourceMode = SourceMode.RAW;
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

    private static boolean invokeOnEdtIfNeeded(Runnable task) {
        if (SwingUtilities.isEventDispatchThread()) {
            return false;
        }
        try {
            SwingUtilities.invokeAndWait(task);
        } catch (Exception e) {
            Throwable cause = e instanceof java.lang.reflect.InvocationTargetException
                    ? ((java.lang.reflect.InvocationTargetException) e).getCause()
                    : e;
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }
            if (cause instanceof Error) {
                throw (Error) cause;
            }
            throw new IllegalStateException("Could not update preview on the Swing event thread.", cause);
        }
        return true;
    }

    private static Set<Integer> defensiveRemovedLabels(Set<Integer> removedLabels,
                                                       ObjectSizeFilterPreview.Summary summary) {
        Set<Integer> source = removedLabels;
        if (source == null && summary != null) {
            source = summary.removedLabels();
        }
        if (source == null || source.isEmpty()) {
            return Collections.emptySet();
        }
        Set<Integer> copy = new HashSet<Integer>();
        for (Integer label : source) {
            if (label != null && label.intValue() > 0) {
                copy.add(label);
            }
        }
        if (copy.isEmpty()) {
            return Collections.emptySet();
        }
        return Collections.unmodifiableSet(copy);
    }

    private void clearObjectFilterPreviewState() {
        closeGeneratedObjectOverlayImage();
        clearObjectFilterPreviewFields();
    }

    private void clearObjectFilterPreviewFields() {
        objectTrueLabelMap = null;
        objectRemovedLabels = Collections.emptySet();
        showRemovedObjects = false;
        objectFilterObjectCount = 0;
    }

    private void closeGeneratedObjectOverlayImage() {
        if (generatedObjectOverlayImage != null) {
            generatedObjectOverlayImage.flush();
            generatedObjectOverlayImage = null;
        }
    }

    private void closeGeneratedOtsuOverlayImage() {
        if (generatedOtsuOverlayImage != null) {
            generatedOtsuOverlayImage.flush();
            generatedOtsuOverlayImage = null;
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
        updateLutToggleButton();
    }

    private void selectChannelLutMode() {
        updatingDisplayControls = true;
        try {
            if (lutModeChoice.getItemCount() > 1) {
                lutModeChoice.setSelectedIndex(1);
            }
        } finally {
            updatingDisplayControls = false;
        }
        updateLutToggleButton();
    }

    private String channelLutChoiceText() {
        return "Channel LUT (" + channelLutName + ")";
    }

    private void updateLutToggleButton() {
        boolean greySelected = lutModeChoice.getSelectedIndex() == 0;
        lutToggleButton.setText(greySelected ? channelLutName + " LUT" : "Grey LUT");
        lutToggleButton.setToolTipText(greySelected
                ? "Show previews with the selected channel LUT."
                : "Show previews in grey.");
        updateLargeDialogDisplayActionState();
        updateComparisonDialogDisplayActionState();
    }

    private boolean displaySettingsAvailable() {
        return displayControlsAvailable || lutToggleAvailable || largeDisplayControlsActivated;
    }

    private boolean displayRangeControlsActive() {
        return displayControlsAvailable || largeDisplayControlsActivated;
    }

    private PreviewDisplaySettings displaySettingsWithoutRange() {
        return PreviewDisplaySettings.of(
                Double.NaN,
                Double.NaN,
                selectedLutMode(),
                channelLutName);
    }

    private PreviewDisplaySettings.LutMode selectedLutMode() {
        return lutModeChoice.getSelectedIndex() == 0
                ? PreviewDisplaySettings.LutMode.GREY
                : PreviewDisplaySettings.LutMode.CHANNEL;
    }

    private void activateLargeDisplayControls() {
        if (largeDisplayControlsActivated) return;
        largeDisplayControlsActivated = true;
        refreshDisplayControlImage();
        updateLargeDialogDisplayActionState();
    }

    private void updateLargeDialogDisplayActionState() {
        if (largePreviewDialog == null) return;
        largePreviewDialog.setDisplayActionState(
                displayControlsAvailable || largeDisplayControlsActivated,
                lutToggleAvailable || largeDisplayControlsActivated,
                lutToggleButton.getText(),
                lutToggleButton.getToolTipText());
    }

    private void updateComparisonDialogDisplayActionState() {
        if (comparisonPreviewDialog == null) return;
        comparisonPreviewDialog.setDisplayActionState(
                displayControlsAvailable || largeDisplayControlsActivated,
                lutToggleAvailable || largeDisplayControlsActivated,
                lutToggleButton.getText(),
                lutToggleButton.getToolTipText());
    }

    private void updateComparisonRestoreActionState() {
        if (comparisonPreviewDialog == null) return;
        comparisonPreviewDialog.setRestoreActionListener(
                comparisonRestoreAction == null
                        ? null
                        : new ComparisonPreviewDialog.RestoreActionListener() {
                            @Override public void restorePreviousRequested() {
                                comparisonRestoreAction.run();
                            }
                        });
        boolean available = comparisonRestoreAction != null && comparisonPreviousImage != null;
        comparisonPreviewDialog.setRestoreActionState(
                available,
                "Restore the settings that produced the previous preview.");
    }
}
