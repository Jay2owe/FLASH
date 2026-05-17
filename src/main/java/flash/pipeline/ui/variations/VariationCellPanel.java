package flash.pipeline.ui.variations;

import flash.pipeline.ui.FlashTheme;
import flash.pipeline.ui.preview.ImagePreviewPanel;
import flash.pipeline.ui.preview.ObjectOverlayRenderer;
import flash.pipeline.ui.preview.ThresholdOverlayRenderer;

import ij.ImagePlus;
import ij.ImageStack;
import ij.measure.ResultsTable;
import ij.process.AutoThresholder;
import ij.process.ByteProcessor;
import ij.process.ImageProcessor;

import javax.swing.BorderFactory;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.RadialGradientPaint;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

public final class VariationCellPanel extends JPanel {

    private static final Color CARD_BACKGROUND = new Color(0x26, 0x2A, 0x2E);
    private static final Color DEFAULT_BORDER = new Color(0x5B, 0x62, 0x69);
    private static final Color HOVER_BORDER = new Color(0, 0, 0, 20);
    private static final Color KNEE_BORDER = new Color(0xF0, 0xE4, 0x42);
    private static final Color STABILITY_BORDER = new Color(0x56, 0xB4, 0xE9);
    private static final Color COMPARE_BORDER = STABILITY_BORDER;
    private static final Color FOOTER_COLOR = new Color(0xC0, 0xC5, 0xCA);
    private static final Color ERROR_COLOR = new Color(0xE6, 0x9F, 0x00);
    private static final Color RIBBON_RIM = new Color(0, 0, 0, 170);
    private static final Color DOWNSTREAM_RIBBON = new Color(0x56, 0xB4, 0xE9);
    private static final Color DOWNSTREAM_HELP = new Color(0xF0, 0xE4, 0x42);
    private static final Color DOWNSTREAM_HURT = new Color(0xE6, 0x9F, 0x00);
    private static final Color DOWNSTREAM_NEUTRAL = new Color(0x7A, 0x82, 0x89);
    private static final Color CHIP_TEXT = new Color(0x22, 0x22, 0x22);
    private static final int CARD_RADIUS = 8;
    private static final float DEFAULT_OUTLINE_WIDTH = 1.5f;
    private static final float COMPARE_OUTLINE_WIDTH = 4f;
    private static final float HALO_ALPHA_BASE = 0.14f;
    private static final float HALO_ALPHA_AMPLITUDE = 0.08f;
    private static final int UNKNOWN_DELTA = Integer.MIN_VALUE;
    private static final int PEEK_DELAY_MS = 120;
    private static final int PEEK_DRAG_CANCEL_PX = 4;
    private static final int OTSU_HISTOGRAM_BINS = 256;
    private static final int FILTER_PARAM_LABEL_MAX_CHARS = 56;
    private static final int CELL_SIZE = 260;
    private static final int METRIC_STRIP_HEIGHT = 24;
    private static final int FILTER_PARAM_STRIP_HEIGHT = 22;
    private static final int METRIC_TEXT_INSET = 8;
    private static final int OVERLAY_PILL_HEIGHT = 18;
    private static final int OVERLAY_PILL_RADIUS = 6;
    private static final int OVERLAY_PILL_X_PAD = 7;
    private static final int PICK_PILL_WIDTH = 50;
    private static final int PICK_PILL_HEIGHT = 22;
    private static final int PICK_PILL_RADIUS = 8;
    private static final int PICK_PILL_INSET = 10;
    private static final Color OVERLAY_STRIP = new Color(0, 0, 0, 140);
    private static final Color OVERLAY_TEXT = Color.WHITE;
    private static final String ERROR_BADGE = "\u26a0";

    public enum BorderHint {
        NONE,
        KNEE,
        STABLE,
        STABILITY
    }

    public enum OverlayMode {
        NONE,
        OTSU_MASK
    }

    private final ParameterCombo combo;
    private final ImagePlus croppedSource;
    private final Consumer<ParameterCombo> onAccept;
    private final BiConsumer<ParameterCombo, VariationCellPanel> onCompare;
    private final int placeholderIndex;
    private final ImagePreviewPanel preview = new ImagePreviewPanel("Variation");
    private final List<ParameterKey> footerParameterKeys =
            new ArrayList<ParameterKey>();
    private final Timer haloTimer;
    private final Timer peekDelayTimer;

    private ImagePlus cachedLabel;
    private ResultsTable cachedStats;
    private ImagePlus rawSourceImage;
    private ImagePlus filteredImage;
    private ImagePlus cachedOverlayImage;
    private ImagePlus displayedPreviewImage;
    private ImagePlus currentPreviewImage;
    private ImagePlus ownedCachedLabel;
    private ImagePlus ownedDisplayedPreviewImage;
    private OverlayMode overlayMode = OverlayMode.NONE;
    private double cachedOtsuLower = Double.NaN;
    private long durationMs = -1L;
    private int objectCount = -1;
    private int deltaN = UNKNOWN_DELTA;
    private double iouToNeighbours = Double.NaN;
    private double filterSnr = Double.NaN;
    private double filterBgSigma = Double.NaN;
    private int downstreamDeltaN = UNKNOWN_DELTA;
    private String errorText = "";
    private String filterParameterText = "";
    private String countLabelText = "pending";
    private String deltaLabelText = "";
    private String iouLabelText = "";
    private String filterChipText = "";
    private String filterDownstreamDeltaText = "";
    private String filterSnrText = "";
    private String filterBgSigmaText = "";
    private String ribbonLabelOverride;
    private String downstreamRibbonLabel;
    private Color countLabelColor = FOOTER_COLOR;
    private Color filterDownstreamDeltaFill = DOWNSTREAM_NEUTRAL;
    private boolean deltaLabelVisible;
    private boolean iouLabelVisible;
    private boolean filterChipVisible;
    private boolean filterDownstreamDeltaVisible;
    private boolean filterFooterActive;
    private boolean hover;
    private boolean kneeWinner;
    private boolean stabilityWinner;
    private boolean selectedForCompare;
    private boolean acceptEnabled;
    private boolean errorState;
    private boolean showHalo;
    private boolean peeking;
    private boolean suppressNextClick;
    private Point pressPoint;
    private long haloStartNanos;
    private float haloPhase;
    private Color haloColor = KNEE_BORDER;

    public VariationCellPanel(ParameterCombo combo,
                              ImagePlus croppedSource,
                              Consumer<ParameterCombo> onAccept,
                              BiConsumer<ParameterCombo, VariationCellPanel> onCompare) {
        this(combo, croppedSource, onAccept, onCompare, 0);
    }

    public VariationCellPanel(ParameterCombo combo,
                              ImagePlus croppedSource,
                              Consumer<ParameterCombo> onAccept,
                              BiConsumer<ParameterCombo, VariationCellPanel> onCompare,
                              int placeholderIndex) {
        super(new BorderLayout(4, 4));
        this.combo = combo == null ? ParameterCombo.builder().build() : combo;
        this.croppedSource = croppedSource;
        this.onAccept = onAccept;
        this.onCompare = onCompare;
        this.placeholderIndex = Math.max(0, placeholderIndex);
        this.haloTimer = new Timer(33, e -> advanceHalo());
        this.haloTimer.setInitialDelay(0);
        this.peekDelayTimer = new Timer(PEEK_DELAY_MS, e -> beginPeek());
        this.peekDelayTimer.setRepeats(false);

        setOpaque(false);
        setBackground(CARD_BACKGROUND);
        setPreferredSize(new Dimension(CELL_SIZE, CELL_SIZE));
        setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
        preview.setSlim(true);
        preview.setZRowVisible(false);
        add(preview, BorderLayout.CENTER);
        installMouseHandlers();
        refreshFooter();
        refreshBorder();
        refreshTooltip();
    }

    public ParameterCombo combo() {
        return combo;
    }

    public void setFooterParameterKeys(final List<ParameterKey> keys) {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(new Runnable() {
                @Override public void run() {
                    setFooterParameterKeys(keys);
                }
            });
            return;
        }
        footerParameterKeys.clear();
        if (keys != null) {
            for (int i = 0; i < keys.size(); i++) {
                ParameterKey key = keys.get(i);
                if (key != null && !footerParameterKeys.contains(key)) {
                    footerParameterKeys.add(key);
                }
            }
        }
        if (filterFooterActive) {
            refreshFilterParameterLabel(combo);
            refreshTooltip();
        }
    }

    public ImagePreviewPanel preview() {
        return preview;
    }

    public void setRawSource(final ImagePlus src) {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(new Runnable() {
                @Override public void run() {
                    setRawSource(src);
                }
            });
            return;
        }
        this.rawSourceImage = src;
        if (src == null) {
            cancelPeek(true);
        }
    }

    public void setState(final String state) {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(new Runnable() {
                @Override public void run() {
                    setState(state);
                }
            });
            return;
        }
        showSegmentationFooter();
        clearRibbonLabelOverride();
        filteredImage = null;
        setCachedLabel(null, false);
        cachedStats = null;
        invalidateOverlayCache();
        errorState = false;
        errorText = "";
        acceptEnabled = false;
        objectCount = -1;
        deltaN = UNKNOWN_DELTA;
        iouToNeighbours = Double.NaN;
        filterSnr = Double.NaN;
        filterBgSigma = Double.NaN;
        clearDownstreamVerdictState();
        filterParameterText = "";
        filterChipText = "";
        filterChipVisible = false;
        filterSnrText = "";
        filterBgSigmaText = "";
        setDisplayedPreviewImage(null);
        setStateText(state == null || state.trim().isEmpty() ? "pending" : state,
                FOOTER_COLOR);
        refreshTooltip();
    }

    public void setLabel(ImagePlus label, ResultsTable stats) {
        setLabel(label, stats, stats == null ? -1 : stats.size(), -1L);
    }

    public void setResult(final VariationResult result) {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(new Runnable() {
                @Override public void run() {
                    setResult(result);
                }
            });
            return;
        }
        if (result == null) {
            return;
        }
        invalidateOverlayCache();
        if (result.hasError()) {
            setError(result.error());
            return;
        }
        if (result.kind() == VariationResult.Kind.FILTER) {
            setFilterResult(result);
            return;
        }
        setLabel(result.label(), result.stats(), result.nObjects(), result.durationMs());
        if (!Double.isNaN(result.meanNeighbourIou())) {
            setIouToNeighbours(result.meanNeighbourIou());
        }
    }

    public void setFilterResult(final VariationResult result) {
        if (result == null) {
            return;
        }
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(new Runnable() {
                @Override public void run() {
                    setFilterResult(result);
                }
            });
            return;
        }
        if (result.hasError()) {
            setError(result.error());
            return;
        }
        clearRibbonLabelOverride();
        clearDownstreamVerdictState();
        ImagePlus filtered = result.previewImage() == null
                ? result.label()
                : result.previewImage();
        invalidateOverlayCache();
        filteredImage = filtered;
        setCachedLabel(null, false);
        cachedStats = null;
        objectCount = -1;
        deltaN = UNKNOWN_DELTA;
        iouToNeighbours = Double.NaN;
        durationMs = result.durationMs();
        errorState = false;
        errorText = "";
        acceptEnabled = true;
        filterSnr = result.snr();
        filterBgSigma = result.bgSigma();
        refreshFilterParameterLabel(result.combo());
        filterSnrText = "SNR " + formatOneDecimal(filterSnr);
        filterBgSigmaText = "bg \u03c3 " + formatInteger(filterBgSigma);
        showFilterFooter();
        setDisplayedPreviewImage(previewImageForOverlayMode());
        refreshTooltip();
    }

    public void setError(final Throwable error) {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(new Runnable() {
                @Override public void run() {
                    setError(error);
                }
            });
            return;
        }
        clearRibbonLabelOverride();
        clearDownstreamVerdictState();
        filteredImage = null;
        invalidateOverlayCache();
        errorState = true;
        acceptEnabled = false;
        errorText = errorDetails(error);
        showSegmentationFooter();
        setCachedLabel(createEmptyLabel(), true);
        cachedStats = null;
        objectCount = -1;
        deltaN = UNKNOWN_DELTA;
        iouToNeighbours = Double.NaN;
        filterSnr = Double.NaN;
        filterBgSigma = Double.NaN;
        filterParameterText = "";
        filterChipText = "";
        filterChipVisible = false;
        filterSnrText = "";
        filterBgSigmaText = "";
        durationMs = -1L;
        setDisplayedPreviewImage(null);
        if (PresetSweepCombo.isIncompatible(error)) {
            setStateText("N/A", DOWNSTREAM_NEUTRAL);
        } else {
            setStateText(ERROR_BADGE, ERROR_COLOR);
        }
        refreshTooltip();
    }

    public void setLabel(final ImagePlus label,
                         final ResultsTable stats,
                         final int nObjects,
                         final long durationMs) {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(new Runnable() {
                @Override public void run() {
                    setLabel(label, stats, nObjects, durationMs);
                }
            });
            return;
        }
        clearRibbonLabelOverride();
        clearDownstreamVerdictState();
        filteredImage = null;
        invalidateOverlayCache();
        setCachedLabel(label == null ? createPlaceholderLabel() : label,
                label == null);
        this.cachedStats = stats;
        this.objectCount = Math.max(0, nObjects);
        this.durationMs = durationMs;
        this.errorState = false;
        this.errorText = "";
        this.acceptEnabled = true;
        this.filterSnr = Double.NaN;
        this.filterBgSigma = Double.NaN;
        this.filterParameterText = "";
        this.filterChipText = "";
        this.filterChipVisible = false;
        this.filterSnrText = "";
        this.filterBgSigmaText = "";
        clearDownstreamVerdictState();
        showSegmentationFooter();

        ImagePlus rendered = null;
        if (croppedSource != null && dimensionsMatch(croppedSource, cachedLabel)) {
            rendered = ObjectOverlayRenderer.renderOverlay(croppedSource, cachedLabel);
        }
        if (rendered == null) {
            rendered = ObjectOverlayRenderer.renderLabelMap(cachedLabel, objectCount);
        }
        if (rendered == null) {
            rendered = cachedLabel;
        }
        setDisplayedPreviewImage(rendered, rendered != cachedLabel);
        refreshFooter();
        refreshTooltip();
    }

    public void setOverlayMode(final OverlayMode mode) {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(new Runnable() {
                @Override public void run() {
                    setOverlayMode(mode);
                }
            });
            return;
        }
        OverlayMode next = mode == null ? OverlayMode.NONE : mode;
        if (overlayMode == next && cachedOverlayImage != null) {
            return;
        }
        overlayMode = next;
        setDisplayedPreviewImage(previewImageForOverlayMode());
        refreshTooltip();
    }

    public void setZ(final int z) {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(new Runnable() {
                @Override public void run() {
                    setZ(z);
                }
            });
            return;
        }
        preview.setCurrentZ(z);
    }

    public void setDeltaN(final int deltaN) {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(new Runnable() {
                @Override public void run() {
                    setDeltaN(deltaN);
                }
            });
            return;
        }
        this.deltaN = deltaN;
        refreshFooter();
        refreshTooltip();
    }

    public void clearDeltaN() {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(new Runnable() {
                @Override public void run() {
                    clearDeltaN();
                }
            });
            return;
        }
        this.deltaN = UNKNOWN_DELTA;
        refreshFooter();
        refreshTooltip();
    }

    public void setDownstreamDelta(final int deltaCells) {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(new Runnable() {
                @Override public void run() {
                    setDownstreamDelta(deltaCells);
                }
            });
            return;
        }
        downstreamDeltaN = deltaCells;
        refreshDownstreamChip();
        refreshTooltip();
    }

    public void clearDownstreamVerdict() {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(new Runnable() {
                @Override public void run() {
                    clearDownstreamVerdict();
                }
            });
            return;
        }
        clearDownstreamVerdictState();
        refreshTooltip();
        repaint();
    }

    public void setIouToNeighbours(final double iouToNeighbours) {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(new Runnable() {
                @Override public void run() {
                    setIouToNeighbours(iouToNeighbours);
                }
            });
            return;
        }
        this.iouToNeighbours = iouToNeighbours;
        refreshFooter();
        refreshTooltip();
    }

    public void setKneeWinner(final boolean kneeWinner) {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(new Runnable() {
                @Override public void run() {
                    setKneeWinner(kneeWinner);
                }
            });
            return;
        }
        boolean start = kneeWinner && !this.kneeWinner;
        this.kneeWinner = kneeWinner;
        if (start) {
            startHalo(KNEE_BORDER);
        } else if (!this.kneeWinner && !stabilityWinner) {
            resetHalo();
        }
        refreshFooter();
        refreshBorder();
        refreshTooltip();
    }

    public void setStabilityWinner(boolean stabilityWinner) {
        setStabilityWinner(stabilityWinner, Double.NaN);
    }

    public void setStabilityWinner(final boolean stabilityWinner,
                                   final double meanNeighbourIou) {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(new Runnable() {
                @Override public void run() {
                    setStabilityWinner(stabilityWinner, meanNeighbourIou);
                }
            });
            return;
        }
        boolean start = stabilityWinner && !this.stabilityWinner;
        this.stabilityWinner = stabilityWinner;
        if (!Double.isNaN(meanNeighbourIou)) {
            this.iouToNeighbours = meanNeighbourIou;
        }
        if (start) {
            startHalo(STABILITY_BORDER);
        } else if (!kneeWinner && !this.stabilityWinner) {
            resetHalo();
        }
        refreshFooter();
        refreshBorder();
        refreshTooltip();
    }

    public void setBorderHint(final BorderHint hint) {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(new Runnable() {
                @Override public void run() {
                    setBorderHint(hint);
                }
            });
            return;
        }
        if (hint == null || hint == BorderHint.NONE) {
            kneeWinner = false;
            stabilityWinner = false;
            clearRibbonLabelOverride();
            resetHalo();
        } else if (hint == BorderHint.KNEE) {
            setKneeWinner(true);
            return;
        } else if (hint == BorderHint.STABLE || hint == BorderHint.STABILITY) {
            setStabilityWinner(true);
            return;
        }
        refreshFooter();
        refreshBorder();
        refreshTooltip();
    }

    public void setRibbonLabel(final String label) {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(new Runnable() {
                @Override public void run() {
                    setRibbonLabel(label);
                }
            });
            return;
        }
        String safeLabel = label == null ? "" : label.trim();
        if (safeLabel.toUpperCase(Locale.ROOT).contains("DOWNSTREAM")) {
            setDownstreamRibbonLabel(safeLabel);
            return;
        }
        ribbonLabelOverride = safeLabel.length() == 0 ? null : safeLabel;
        repaint();
    }

    public void setDownstreamRibbonLabel(final String label) {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(new Runnable() {
                @Override public void run() {
                    setDownstreamRibbonLabel(label);
                }
            });
            return;
        }
        String safeLabel = label == null ? "" : label.trim();
        downstreamRibbonLabel = safeLabel.length() == 0 ? null : safeLabel;
        repaint();
        refreshTooltip();
    }

    void setSelectedForCompare(final boolean selectedForCompare) {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(new Runnable() {
                @Override public void run() {
                    setSelectedForCompare(selectedForCompare);
                }
            });
            return;
        }
        this.selectedForCompare = selectedForCompare;
        refreshBorder();
    }

    ImagePlus cachedLabel() {
        return cachedLabel;
    }

    boolean hasCachedLabel() {
        return cachedLabel != null;
    }

    boolean isSelectedForCompareForTest() {
        return selectedForCompare;
    }

    String footerTextForTest() {
        return badgeText();
    }

    String[] footerLinesForTest() {
        if (filterFooterActive) {
            if (filterChipVisible) {
                return filterLines(true);
            }
            return filterLines(false);
        }
        return new String[] { badgeText() };
    }

    String badgeText() {
        if (filterFooterActive) {
            String prefix = filterChipVisible
                    ? filterChipText + " "
                    : "";
            String downstream = filterDownstreamDeltaVisible
                    ? filterDownstreamDeltaText + " "
                    : "";
            return prefix + downstream + filterSnrText + " "
                    + filterBgSigmaText;
        }
        StringBuilder out = new StringBuilder(countLabelText);
        if (deltaLabelVisible && deltaLabelText.length() > 0) {
            out.append(' ').append(deltaLabelText);
        }
        if (iouLabelVisible && iouLabelText.length() > 0) {
            out.append(' ').append(iouLabelText);
        }
        return out.toString();
    }

    int currentZForTest() {
        return preview.getCurrentZ();
    }

    ImagePlus cachedLabelForTest() {
        return cachedLabel;
    }

    ImagePlus currentPreviewImageForTest() {
        return currentPreviewImage;
    }

    OverlayMode overlayModeForTest() {
        return overlayMode;
    }

    double cachedOtsuLowerForTest() {
        return cachedOtsuLower;
    }

    boolean isPeekingForTest() {
        return peeking;
    }

    boolean isPeekDelayRunningForTest() {
        return peekDelayTimer.isRunning();
    }

    boolean suppressNextClickForTest() {
        return suppressNextClick;
    }

    boolean isHaloTimerRunningForTest() {
        return haloTimer.isRunning();
    }

    boolean isHaloVisibleForTest() {
        return showHalo;
    }

    String ribbonLabelForTest() {
        return ribbonLabelOverride;
    }

    String downstreamRibbonLabelForTest() {
        return downstreamRibbonLabel;
    }

    String countLabelTextForTest() {
        return countLabelText;
    }

    String deltaLabelTextForTest() {
        return deltaLabelText;
    }

    boolean deltaLabelVisibleForTest() {
        return deltaLabelVisible;
    }

    String iouLabelTextForTest() {
        return iouLabelText;
    }

    boolean iouLabelVisibleForTest() {
        return iouLabelVisible;
    }

    String filterChipTextForTest() {
        return filterChipText;
    }

    boolean filterChipVisibleForTest() {
        return filterChipVisible;
    }

    String filterDownstreamDeltaTextForTest() {
        return filterDownstreamDeltaText;
    }

    boolean filterDownstreamDeltaVisibleForTest() {
        return filterDownstreamDeltaVisible;
    }

    String filterSnrTextForTest() {
        return filterSnrText;
    }

    String filterBgSigmaTextForTest() {
        return filterBgSigmaText;
    }

    void firePeekDelayForTest() {
        beginPeek();
    }

    void clickForTest(boolean shift) {
        if (suppressNextClick) {
            suppressNextClick = false;
            return;
        }
        if (shift) {
            if (onCompare != null) {
                onCompare.accept(combo, this);
            }
        } else if (acceptEnabled && onAccept != null) {
            onAccept.accept(combo);
        }
    }

    boolean isPickPillVisibleForTest() {
        return acceptEnabled;
    }

    @Override protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                RenderingHints.VALUE_ANTIALIAS_ON);
        RoundRectangle2D card = cardShape();
        g2.setColor(CARD_BACKGROUND);
        g2.fill(card);
        float outlineWidth = selectedForCompare
                ? COMPARE_OUTLINE_WIDTH
                : DEFAULT_OUTLINE_WIDTH;
        g2.setStroke(new BasicStroke(outlineWidth));
        g2.setColor(selectedForCompare ? COMPARE_BORDER : DEFAULT_BORDER);
        g2.draw(cardShape(outlineWidth));
        g2.dispose();
    }

    @Override protected void paintChildren(Graphics g) {
        super.paintChildren(g);
        paintHoverTint(g);
        if (!peeking) {
            paintHalo(g);
            paintCompareBadge(g);
            paintRibbons(g);
            paintMetricOverlays(g);
            paintPickPill(g);
        }
    }

    @Override public void removeNotify() {
        cancelPeek(true);
        haloTimer.stop();
        super.removeNotify();
    }

    void disposeImages() {
        cancelPeek(false);
        resetHalo();
        ImagePlus oldOwnedLabel = ownedCachedLabel;
        ImagePlus oldOwnedPreview = ownedDisplayedPreviewImage;
        ImagePlus oldOverlay = cachedOverlayImage;
        ImagePlus oldCached = cachedLabel;
        ImagePlus oldFiltered = filteredImage;
        ImagePlus oldDisplayed = displayedPreviewImage;
        ImagePlus oldCurrent = currentPreviewImage;

        cachedLabel = null;
        cachedStats = null;
        filteredImage = null;
        displayedPreviewImage = null;
        currentPreviewImage = null;
        cachedOverlayImage = null;
        ownedCachedLabel = null;
        ownedDisplayedPreviewImage = null;
        preview.setImage(null);

        disposeOwnedImage(oldOwnedLabel);
        disposeOwnedImage(oldOwnedPreview);
        disposeOwnedImage(oldOverlay);
        disposeIfDistinctCellResultImage(oldCached,
                oldOwnedLabel, oldOwnedPreview, oldOverlay);
        disposeIfDistinctCellResultImage(oldFiltered,
                oldOwnedLabel, oldOwnedPreview, oldOverlay, oldCached);
        disposeIfDistinctCellResultImage(oldDisplayed,
                oldOwnedLabel, oldOwnedPreview, oldOverlay, oldCached, oldFiltered);
        disposeIfDistinctCellResultImage(oldCurrent,
                oldOwnedLabel, oldOwnedPreview, oldOverlay, oldCached,
                oldFiltered, oldDisplayed);
    }

    private void installMouseHandlers() {
        MouseAdapter listener = new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) {
                handleMousePressed(e);
                if (suppressNextClick) {
                    suppressNextClick = false;
                    if (e != null) {
                        e.consume();
                    }
                    return;
                }
                if (e == null || !SwingUtilities.isLeftMouseButton(e)) {
                    return;
                }
                if (e.isShiftDown()) {
                    if (onCompare != null) {
                        onCompare.accept(combo, VariationCellPanel.this);
                    }
                } else if (acceptEnabled && onAccept != null) {
                    onAccept.accept(combo);
                }
            }

            @Override public void mouseReleased(MouseEvent e) {
                handleMouseReleased();
            }

            @Override public void mouseEntered(MouseEvent e) {
                hover = true;
                refreshTooltip();
                refreshBorder();
            }

            @Override public void mouseExited(MouseEvent e) {
                hover = false;
                cancelPeek(true);
                pressPoint = null;
                refreshTooltip();
                refreshBorder();
            }

            @Override public void mouseDragged(MouseEvent e) {
                handleMouseDragged(e);
            }
        };
        installMouseHandler(this, listener);
        installMouseHandler(preview, listener);
    }

    private void installMouseHandler(Component component, MouseAdapter listener) {
        component.addMouseListener(listener);
        component.addMouseMotionListener(listener);
    }

    private void handleMousePressed(MouseEvent e) {
        cancelPeek(true);
        pressPoint = null;
        if (e == null || !SwingUtilities.isLeftMouseButton(e) || !canPeek()) {
            return;
        }
        pressPoint = pointInCell(e);
        peekDelayTimer.restart();
    }

    private void handleMouseReleased() {
        cancelPeek(true);
        pressPoint = null;
    }

    private void handleMouseDragged(MouseEvent e) {
        if (pressPoint == null || e == null) {
            return;
        }
        Point current = pointInCell(e);
        if (current == null) {
            return;
        }
        int dx = current.x - pressPoint.x;
        int dy = current.y - pressPoint.y;
        if (dx * dx + dy * dy > PEEK_DRAG_CANCEL_PX * PEEK_DRAG_CANCEL_PX) {
            cancelPeek(true);
            pressPoint = null;
        }
    }

    private Point pointInCell(MouseEvent e) {
        Object source = e.getSource();
        if (source instanceof Component) {
            return SwingUtilities.convertPoint((Component) source,
                    e.getPoint(), this);
        }
        return e.getPoint();
    }

    private boolean canPeek() {
        return rawSourceImage != null && displayedPreviewImage != null;
    }

    private void beginPeek() {
        peekDelayTimer.stop();
        if (pressPoint == null || !canPeek()) {
            return;
        }
        peeking = true;
        suppressNextClick = true;
        showPreviewImage(rawSourceImage);
        repaint();
    }

    private void cancelPeek(boolean restoreImage) {
        peekDelayTimer.stop();
        if (restoreImage && peeking) {
            restorePeekImage();
        }
    }

    private void restorePeekImage() {
        peeking = false;
        showPreviewImage(displayedPreviewImage);
        repaint();
    }

    private ImagePlus previewImageForOverlayMode() {
        ImagePlus filtered = filteredImage;
        if (overlayMode != OverlayMode.OTSU_MASK || filtered == null) {
            return filtered;
        }
        if (cachedOverlayImage != null) {
            return cachedOverlayImage;
        }
        Histogram histogram = histogramFor(filtered);
        if (!histogram.hasValues()) {
            return filtered;
        }
        int thresholdBin = new AutoThresholder().getThreshold(
                AutoThresholder.Method.Otsu, histogram.counts);
        double lower = histogram.foregroundLowerFor(thresholdBin);
        ImagePlus rendered = ThresholdOverlayRenderer.render(filtered,
                lower,
                histogram.max,
                ThresholdOverlayRenderer.MODE_RED_OVERLAY);
        if (rendered == null) {
            return filtered;
        }
        cachedOtsuLower = lower;
        cachedOverlayImage = rendered;
        return rendered;
    }

    private void invalidateOverlayCache() {
        disposeOwnedImage(cachedOverlayImage);
        cachedOverlayImage = null;
        cachedOtsuLower = Double.NaN;
    }

    private void setDisplayedPreviewImage(ImagePlus image) {
        setDisplayedPreviewImage(image, false);
    }

    private void setDisplayedPreviewImage(ImagePlus image, boolean owned) {
        if (ownedDisplayedPreviewImage != null
                && ownedDisplayedPreviewImage != image
                && ownedDisplayedPreviewImage != cachedOverlayImage) {
            disposeOwnedImage(ownedDisplayedPreviewImage);
        }
        displayedPreviewImage = image;
        ownedDisplayedPreviewImage = owned ? image : null;
        if (!peeking) {
            showPreviewImage(image);
        }
    }

    private void showPreviewImage(ImagePlus image) {
        currentPreviewImage = image;
        preview.setImage(image);
    }

    private void refreshBorder() {
        repaint();
    }

    private void refreshFooter() {
        if (objectCount < 0) {
            return;
        }
        countLabelText = String.valueOf(objectCount);
        countLabelColor = OVERLAY_TEXT;

        deltaLabelText = deltaN == UNKNOWN_DELTA ? "" : formatDelta(deltaN);
        deltaLabelVisible = deltaN != UNKNOWN_DELTA;

        boolean hasIou = !Double.isNaN(iouToNeighbours);
        iouLabelText = hasIou ? "IoU "
                + String.format(Locale.ROOT, "%.2f",
                Double.valueOf(iouToNeighbours)) : "";
        iouLabelVisible = hasIou;
        repaint();
    }

    private void refreshTooltip() {
        StringBuilder sb = new StringBuilder();
        sb.append("<html>");
        sb.append(html(combo.toCanonicalJson()));
        if (errorState) {
            sb.append("<br><b>Failed:</b> ")
                    .append(html(errorText).replace("\n", "<br>"));
            appendPickInstruction(sb);
            sb.append("</html>");
            setTooltips(sb.toString());
            return;
        }
        if (filterFooterActive) {
            if (filterParameterText.length() > 0) {
                sb.append("<br>").append(html(filterParameterText));
            }
            if (filterDownstreamDeltaVisible) {
                sb.append("<br>").append(html(filterDownstreamDeltaText))
                        .append(" vs no-filter downstream baseline");
            }
            sb.append("<br>").append(html(filterSnrText));
            sb.append("<br>").append(html(filterBgSigmaText));
            if (durationMs >= 0L) {
                sb.append("<br>durationMs: ").append(durationMs).append(" ms");
            }
            appendPickInstruction(sb);
            sb.append("</html>");
            setTooltips(sb.toString());
            return;
        }
        if (deltaN != UNKNOWN_DELTA) {
            sb.append("<br>").append("\u0394n vs neighbour: ")
                    .append(formatSigned(deltaN));
        }
        if (!Double.isNaN(iouToNeighbours)) {
            sb.append("<br>Mean IoU with neighbours: ")
                    .append(String.format(Locale.ROOT, "%.2f",
                            Double.valueOf(iouToNeighbours)));
            if (stabilityWinner) {
                sb.append(" (most stable object masks)");
            }
        } else if (stabilityWinner) {
            sb.append("<br>Most stable object masks");
        }
        if (kneeWinner) {
            sb.append("<br>Most stable count");
        }
        if (durationMs >= 0L) {
            sb.append("<br>durationMs: ").append(durationMs).append(" ms");
        }
        if (cachedStats != null) {
            sb.append("<br>").append(cachedStats.size()).append(" stats rows");
        }
        appendPickInstruction(sb);
        sb.append("</html>");
        setTooltips(sb.toString());
    }

    private void appendPickInstruction(StringBuilder sb) {
        if (hover && acceptEnabled) {
            sb.append("<br>Click to pick this combo");
        }
    }

    private void setTooltips(String text) {
        setToolTipText(text);
        preview.setToolTipText(text);
    }

    private void setStateText(String text, Color color) {
        countLabelText = text;
        countLabelColor = color == null ? FOOTER_COLOR : color;
        deltaLabelText = "";
        deltaLabelVisible = false;
        iouLabelText = "";
        iouLabelVisible = false;
        repaint();
    }

    private void showSegmentationFooter() {
        filterFooterActive = false;
        repaint();
    }

    private void showFilterFooter() {
        filterFooterActive = true;
        repaint();
    }

    private void paintHoverTint(Graphics g) {
        if (!hover || selectedForCompare) {
            return;
        }
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setClip(cardShape());
        g2.setColor(HOVER_BORDER);
        g2.fillRect(0, 0, getWidth(), getHeight());
        g2.dispose();
    }

    private void paintHalo(Graphics g) {
        if (!showHalo) {
            return;
        }
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setClip(cardShape());
        float alpha = HALO_ALPHA_BASE
                + HALO_ALPHA_AMPLITUDE * (float) Math.sin(haloPhase);
        int radius = Math.max(getWidth(), getHeight());
        Color core = haloColor == null ? KNEE_BORDER : haloColor;
        g2.setPaint(new RadialGradientPaint(
                getWidth() / 2f,
                getHeight() / 2f,
                radius,
                new float[] { 0f, 1f },
                new Color[] {
                        new Color(core.getRed(), core.getGreen(), core.getBlue(),
                                Math.max(0, Math.min(255, (int) (alpha * 255f)))),
                        new Color(core.getRed(), core.getGreen(), core.getBlue(), 0)
                }));
        g2.fillRect(0, 0, getWidth(), getHeight());
        g2.dispose();
    }

    private void paintCompareBadge(Graphics g) {
        if (!selectedForCompare) {
            return;
        }
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                RenderingHints.VALUE_ANTIALIAS_ON);
        int diameter = 10;
        int x = Math.max(6, getWidth() - diameter - 10);
        int y = Math.max(6, getHeight() - diameter - 10);
        g2.setColor(STABILITY_BORDER);
        g2.fillOval(x, y, diameter, diameter);
        g2.setColor(RIBBON_RIM);
        g2.setStroke(new BasicStroke(1.5f));
        g2.drawOval(x, y, diameter, diameter);
        g2.dispose();
    }

    private void paintRibbons(Graphics g) {
        if (kneeWinner) {
            String text = ribbonLabelOverride == null
                    ? "STABLE COUNT"
                    : ribbonLabelOverride;
            paintRibbon(g, text, KNEE_BORDER, new Color(0x22, 0x22, 0x22),
                    true);
        }
        if (stabilityWinner && downstreamRibbonLabel == null) {
            paintRibbon(g, "STABLE MASKS", STABILITY_BORDER, Color.WHITE,
                    !kneeWinner);
        }
        if (downstreamRibbonLabel != null) {
            paintRibbon(g, downstreamRibbonLabel, DOWNSTREAM_RIBBON, Color.WHITE,
                    false);
        }
    }

    private void paintRibbon(Graphics g, String text, Color fill, Color textColor,
                             boolean left) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                RenderingHints.VALUE_ANTIALIAS_ON);
        int side = 86;
        int band = 18;
        Path2D.Double path = new Path2D.Double();
        if (left) {
            path.moveTo(0, band);
            path.lineTo(band, 0);
            path.lineTo(side, 0);
            path.lineTo(0, side);
        } else {
            int w = getWidth();
            path.moveTo(w - band, 0);
            path.lineTo(w, band);
            path.lineTo(w, side);
            path.lineTo(w - side, 0);
        }
        path.closePath();
        g2.setColor(fill);
        g2.fill(path);
        g2.setColor(RIBBON_RIM);
        g2.setStroke(new BasicStroke(1.5f));
        g2.draw(path);
        g2.setClip(path);
        g2.setFont(FlashTheme.bodyMedium().deriveFont(8.5f));
        g2.setColor(textColor);
        if (left) {
            g2.translate(8, 55);
            g2.rotate(-Math.PI / 4.0d);
            g2.drawString(text, 0, 0);
        } else {
            g2.translate(getWidth() - 70, 6);
            g2.rotate(Math.PI / 4.0d);
            g2.drawString(text, 0, 0);
        }
        g2.dispose();
    }

    private void paintMetricOverlays(Graphics g) {
        int width = getWidth();
        int height = getHeight();
        if (width <= 0 || height <= 0) {
            return;
        }
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setClip(cardShape());
            if (filterFooterActive) {
                paintFilterOverlays(g2, width, height);
            } else {
                paintSegmentationOverlay(g2, width, height);
            }
        } finally {
            g2.dispose();
        }
    }

    private void paintPickPill(Graphics g) {
        if (!isPickPillVisibleForTest()) {
            return;
        }
        int width = getWidth();
        int height = getHeight();
        if (width <= 0 || height <= 0) {
            return;
        }
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setClip(cardShape());
            int x = Math.max(PICK_PILL_INSET,
                    width - PICK_PILL_WIDTH - PICK_PILL_INSET);
            int y = Math.min(Math.max(PICK_PILL_INSET, 0),
                    Math.max(0, height - PICK_PILL_HEIGHT - PICK_PILL_INSET));
            g2.setColor(hover ? STABILITY_BORDER.brighter() : STABILITY_BORDER);
            g2.fillRoundRect(x, y, PICK_PILL_WIDTH, PICK_PILL_HEIGHT,
                    PICK_PILL_RADIUS, PICK_PILL_RADIUS);
            g2.setColor(new Color(0, 0, 0, 90));
            g2.drawRoundRect(x, y, PICK_PILL_WIDTH - 1, PICK_PILL_HEIGHT - 1,
                    PICK_PILL_RADIUS, PICK_PILL_RADIUS);
            g2.setFont(FlashTheme.bodyMedium().deriveFont(Font.BOLD, 11f));
            FontMetrics fm = g2.getFontMetrics();
            String text = "Pick";
            int textX = x + (PICK_PILL_WIDTH - fm.stringWidth(text)) / 2;
            int baseline = y + (PICK_PILL_HEIGHT - fm.getHeight()) / 2
                    + fm.getAscent();
            g2.setColor(Color.WHITE);
            g2.drawString(text, textX, baseline);
        } finally {
            g2.dispose();
        }
    }

    private void paintFilterOverlays(Graphics2D g2, int width, int height) {
        if (filterChipVisible || filterDownstreamDeltaVisible) {
            paintFilterTopStrip(g2, width);
        }
        paintBottomStrip(g2, width, height, filterMetricsOverlayText(),
                OVERLAY_TEXT);
    }

    private void paintFilterTopStrip(Graphics2D g2, int width) {
        int stripHeight = Math.min(FILTER_PARAM_STRIP_HEIGHT,
                Math.max(1, getHeight()));
        g2.setColor(OVERLAY_STRIP);
        g2.fillRect(0, 0, width, stripHeight);

        g2.setFont(FlashTheme.mono(10f).deriveFont(Font.BOLD));
        FontMetrics fm = g2.getFontMetrics();
        int right = width - METRIC_TEXT_INSET;
        if (filterDownstreamDeltaVisible) {
            int pillWidth = pillWidth(fm, filterDownstreamDeltaText);
            int x = Math.max(METRIC_TEXT_INSET, width - METRIC_TEXT_INSET
                    - pillWidth);
            int y = Math.max(2, (stripHeight - OVERLAY_PILL_HEIGHT) / 2);
            paintPill(g2, x, y, pillWidth, OVERLAY_PILL_HEIGHT,
                    filterDownstreamDeltaFill, CHIP_TEXT,
                    filterDownstreamDeltaText);
            right = Math.max(METRIC_TEXT_INSET, x - 6);
        }
        if (filterChipVisible && right > METRIC_TEXT_INSET) {
            drawOverlayText(g2, filterParameterText, METRIC_TEXT_INSET,
                    right, 0, stripHeight, OVERLAY_TEXT);
        }
    }

    private void paintSegmentationOverlay(Graphics2D g2, int width, int height) {
        paintBottomStrip(g2, width, height, segmentationMetricsOverlayText(),
                countLabelColor == null ? OVERLAY_TEXT : countLabelColor);
    }

    private void paintBottomStrip(Graphics2D g2, int width, int height,
                                  String text, Color textColor) {
        int stripHeight = Math.min(METRIC_STRIP_HEIGHT, Math.max(1, height));
        int y = Math.max(0, height - stripHeight);
        g2.setColor(OVERLAY_STRIP);
        g2.fillRect(0, y, width, stripHeight);
        g2.setFont(FlashTheme.mono(11f).deriveFont(Font.BOLD));
        drawOverlayText(g2, text, METRIC_TEXT_INSET,
                Math.max(METRIC_TEXT_INSET, width - METRIC_TEXT_INSET),
                y, stripHeight, textColor);
    }

    private void drawOverlayText(Graphics2D g2, String text, int left, int right,
                                 int y, int height, Color color) {
        FontMetrics fm = g2.getFontMetrics();
        int maxWidth = Math.max(0, right - left);
        String visibleText = ellipsizeToWidth(text, fm, maxWidth);
        if (visibleText.length() == 0) {
            return;
        }
        int baseline = y + (height - fm.getHeight()) / 2 + fm.getAscent();
        g2.setColor(color == null ? OVERLAY_TEXT : color);
        g2.drawString(visibleText, left, baseline);
    }

    private void paintPill(Graphics2D g2, int x, int y, int width, int height,
                           Color fill, Color textColor, String text) {
        g2.setColor(fill == null ? DOWNSTREAM_NEUTRAL : fill);
        g2.fillRoundRect(x, y, width, height, OVERLAY_PILL_RADIUS,
                OVERLAY_PILL_RADIUS);
        g2.setColor(new Color(0, 0, 0, 80));
        g2.drawRoundRect(x, y, width - 1, height - 1, OVERLAY_PILL_RADIUS,
                OVERLAY_PILL_RADIUS);
        FontMetrics fm = g2.getFontMetrics();
        int baseline = y + (height - fm.getHeight()) / 2 + fm.getAscent();
        g2.setColor(textColor == null ? CHIP_TEXT : textColor);
        g2.drawString(text == null ? "" : text, x + OVERLAY_PILL_X_PAD,
                baseline);
    }

    private int pillWidth(FontMetrics fm, String text) {
        int textWidth = fm == null ? 0 : fm.stringWidth(text == null ? "" : text);
        return textWidth + OVERLAY_PILL_X_PAD * 2;
    }

    private String filterMetricsOverlayText() {
        return "SNR " + formatOneDecimal(filterSnr)
                + "   bg \u03c3 " + formatInteger(filterBgSigma);
    }

    private String segmentationMetricsOverlayText() {
        StringBuilder out = new StringBuilder(countLabelText);
        if (deltaLabelVisible && deltaLabelText.length() > 0) {
            out.append("   ").append(deltaLabelText);
        }
        if (iouLabelVisible && iouLabelText.length() > 0) {
            out.append("   ").append(iouLabelText);
        }
        return out.toString();
    }

    private void clearRibbonLabelOverride() {
        ribbonLabelOverride = null;
    }

    private void clearDownstreamVerdictState() {
        downstreamRibbonLabel = null;
        downstreamDeltaN = UNKNOWN_DELTA;
        filterDownstreamDeltaText = "";
        filterDownstreamDeltaVisible = false;
        filterDownstreamDeltaFill = DOWNSTREAM_NEUTRAL;
    }

    private void refreshDownstreamChip() {
        if (downstreamDeltaN == UNKNOWN_DELTA) {
            filterDownstreamDeltaText = "";
            filterDownstreamDeltaVisible = false;
            filterDownstreamDeltaFill = DOWNSTREAM_NEUTRAL;
        } else {
            filterDownstreamDeltaText = "delta "
                    + formatSignedCompact(downstreamDeltaN);
            filterDownstreamDeltaFill = downstreamDeltaN > 0
                    ? DOWNSTREAM_HELP
                    : downstreamDeltaN < 0 ? DOWNSTREAM_HURT : DOWNSTREAM_NEUTRAL;
            filterDownstreamDeltaVisible = true;
        }
        repaint();
    }

    private void refreshFilterParameterLabel(ParameterCombo sourceCombo) {
        filterParameterText = filterComboLabel(sourceCombo, footerParameterKeys);
        filterChipText = abbreviate(filterParameterText, FILTER_PARAM_LABEL_MAX_CHARS);
        filterChipVisible = filterParameterText.length() > 0;
        repaint();
    }

    private String[] filterLines(boolean hasFilterChip) {
        java.util.List<String> lines = new java.util.ArrayList<String>();
        if (hasFilterChip) {
            lines.add(filterChipText);
        }
        if (filterDownstreamDeltaVisible) {
            lines.add(filterDownstreamDeltaText);
        }
        lines.add(filterSnrText);
        lines.add(filterBgSigmaText);
        return lines.toArray(new String[lines.size()]);
    }

    private void startHalo(Color color) {
        haloColor = color;
        haloStartNanos = System.nanoTime();
        haloPhase = 0f;
        showHalo = true;
        if (!haloTimer.isRunning()) {
            haloTimer.start();
        }
        repaint();
    }

    private void resetHalo() {
        showHalo = false;
        haloPhase = 0f;
        haloTimer.stop();
        repaint();
    }

    private void advanceHalo() {
        long elapsedNanos = System.nanoTime() - haloStartNanos;
        haloPhase = (elapsedNanos % 2_000_000_000L) / 2_000_000_000f
                * (float) (Math.PI * 2.0d);
        repaint();
    }

    private RoundRectangle2D cardShape() {
        return cardShape(DEFAULT_OUTLINE_WIDTH);
    }

    private RoundRectangle2D cardShape(float outlineWidth) {
        double inset = Math.max(0.5d, outlineWidth / 2.0d);
        return new RoundRectangle2D.Double(inset, inset,
                Math.max(1.0d, getWidth() - 2.0d * inset),
                Math.max(1.0d, getHeight() - 2.0d * inset),
                CARD_RADIUS, CARD_RADIUS);
    }

    private static String formatDelta(int value) {
        return "\u0394" + formatSignedCompact(value);
    }

    private static String formatOneDecimal(double value) {
        double safeValue = Double.isFinite(value) ? value : 0.0d;
        return String.format(Locale.ROOT, "%.1f", Double.valueOf(safeValue));
    }

    private static String formatInteger(double value) {
        double safeValue = Double.isFinite(value) ? value : 0.0d;
        return String.format(Locale.ROOT, "%.0f", Double.valueOf(safeValue));
    }

    private static String filterComboLabel(ParameterCombo combo,
                                           List<ParameterKey> preferredKeys) {
        if (combo == null || combo.values().isEmpty()) {
            return "";
        }
        PresetSweepCombo presetCombo = PresetSweepCombo.from(combo);
        if (presetCombo != null && shouldUsePresetSummary(preferredKeys)) {
            return presetComboLabel(presetCombo, preferredKeys);
        }
        SlotSubstitutionCombo substitution = SlotSubstitutionCombo.from(combo);
        if (substitution != null && shouldUseSlotSummary(preferredKeys)) {
            return substitution.displayLabel();
        }

        List<ParameterKey> keys = keysForSummary(combo, preferredKeys);
        if (keys.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < keys.size(); i++) {
            ParameterKey key = keys.get(i);
            if (key instanceof PresetSweepKey
                    && ((PresetSweepKey) key).role()
                    == PresetSweepKey.Role.X_PARAM_KEY) {
                continue;
            }
            if (!combo.contains(key)) {
                continue;
            }
            if (out.length() > 0) {
                out.append(", ");
            }
            out.append(keyLabelForFooter(key))
                    .append("=")
                    .append(formatFooterValue(combo.get(key)));
        }
        return out.toString();
    }

    private static boolean shouldUsePresetSummary(List<ParameterKey> keys) {
        if (keys == null || keys.isEmpty()) {
            return true;
        }
        for (int i = 0; i < keys.size(); i++) {
            ParameterKey key = keys.get(i);
            if (key instanceof PresetSweepKey) {
                PresetSweepKey presetKey = (PresetSweepKey) key;
                if (presetKey.role() == PresetSweepKey.Role.X_VALUE
                        || presetKey.role() == PresetSweepKey.Role.PRESET_NAME) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean shouldUseSlotSummary(List<ParameterKey> keys) {
        if (keys == null || keys.isEmpty()) {
            return true;
        }
        for (int i = 0; i < keys.size(); i++) {
            if (keys.get(i) instanceof SlotSubstitutionKey) {
                return true;
            }
        }
        return false;
    }

    private static String presetComboLabel(PresetSweepCombo presetCombo,
                                           List<ParameterKey> keys) {
        String param = presetCombo.xParamKey();
        if ((param == null || param.trim().isEmpty())
                && presetCombo.xValue() == null) {
            String preset = presetCombo.presetName();
            return preset == null ? "" : preset.trim();
        }
        String xPart = (param == null || param.trim().isEmpty()
                ? "value"
                : param.trim())
                + "=" + formatFooterValue(presetCombo.xValue());
        boolean includePreset = false;
        if (keys != null) {
            for (int i = 0; i < keys.size(); i++) {
                ParameterKey key = keys.get(i);
                if (key instanceof PresetSweepKey
                        && ((PresetSweepKey) key).role()
                        == PresetSweepKey.Role.PRESET_NAME) {
                    includePreset = true;
                    break;
                }
            }
        }
        if (!includePreset) {
            return xPart;
        }
        String preset = presetCombo.presetName();
        return preset == null || preset.trim().isEmpty()
                ? xPart
                : preset.trim() + " | " + xPart;
    }

    private static List<ParameterKey> keysForSummary(
            ParameterCombo combo,
            List<ParameterKey> preferredKeys) {
        List<ParameterKey> out = new ArrayList<ParameterKey>();
        if (preferredKeys != null && !preferredKeys.isEmpty()) {
            for (int i = 0; i < preferredKeys.size(); i++) {
                ParameterKey key = preferredKeys.get(i);
                if (key != null && combo.contains(key) && !out.contains(key)) {
                    out.add(key);
                }
            }
            return out;
        }
        for (Map.Entry<ParameterKey, Object> entry : combo.values().entrySet()) {
            ParameterKey key = entry.getKey();
            if (key != null && !out.contains(key)) {
                out.add(key);
            }
        }
        return out;
    }

    private static String keyLabelForFooter(ParameterKey key) {
        if (key instanceof FilterParameterId) {
            FilterParameterId filterKey = (FilterParameterId) key;
            String command = compactCommandLabel(filterKey.commandLabel());
            String param = filterKey.paramKey();
            if (command.length() == 0) {
                return param;
            }
            return command + " " + param;
        }
        return key == null ? "" : key.displayLabel();
    }

    private static String compactCommandLabel(String commandLabel) {
        String text = commandLabel == null ? "" : commandLabel.trim();
        if (text.endsWith("...")) {
            text = text.substring(0, text.length() - 3).trim();
        }
        while (text.endsWith(".")) {
            text = text.substring(0, text.length() - 1).trim();
        }
        return text;
    }

    private static String formatFooterValue(Object value) {
        if (value instanceof Number) {
            double number = ((Number) value).doubleValue();
            if (Double.isFinite(number)
                    && Math.abs(number - Math.rint(number)) < 0.0000001d
                    && Math.abs(number) < 1000000000.0d) {
                return String.valueOf((long) Math.rint(number));
            }
            String text = String.format(Locale.ROOT, "%.3f", Double.valueOf(number));
            return text.replaceAll("0+$", "").replaceAll("\\.$", "");
        }
        return value == null ? "" : String.valueOf(value);
    }

    private static String abbreviate(String text, int maxLength) {
        String safe = text == null ? "" : text;
        if (maxLength < 4 || safe.length() <= maxLength) {
            return safe;
        }
        return safe.substring(0, maxLength - 3) + "...";
    }

    private static String ellipsizeToWidth(String text, FontMetrics fm,
                                           int maxWidth) {
        String safe = text == null ? "" : text;
        if (fm == null || maxWidth <= 0 || safe.length() == 0) {
            return "";
        }
        if (fm.stringWidth(safe) <= maxWidth) {
            return safe;
        }
        String ellipsis = "...";
        int ellipsisWidth = fm.stringWidth(ellipsis);
        if (ellipsisWidth > maxWidth) {
            return "";
        }
        int end = safe.length();
        while (end > 0
                && fm.stringWidth(safe.substring(0, end)) + ellipsisWidth
                > maxWidth) {
            end--;
        }
        return end <= 0 ? "" : safe.substring(0, end) + ellipsis;
    }

    private static String formatSigned(int value) {
        return (value > 0 ? "+" : "") + value;
    }

    private static String formatSignedCompact(int value) {
        String sign = value > 0 ? "+" : "";
        int magnitude = Math.abs(value);
        if (magnitude > 999) {
            return sign + String.format(Locale.ROOT, "%.1fk",
                    Double.valueOf(value / 1000.0d));
        }
        return sign + value;
    }

    private ImagePlus createPlaceholderLabel() {
        int width = croppedSource == null ? 96 : Math.max(1, croppedSource.getWidth());
        int height = croppedSource == null ? 96 : Math.max(1, croppedSource.getHeight());
        int slices = croppedSource == null ? 1 : Math.max(1, croppedSource.getStackSize());
        int labelValue = (placeholderIndex % 250) + 1;
        ImageStack stack = new ImageStack(width, height);
        for (int z = 0; z < slices; z++) {
            ByteProcessor bp = new ByteProcessor(width, height);
            bp.setValue(labelValue);
            bp.fill();
            stack.addSlice("z" + (z + 1), bp);
        }
        ImagePlus label = new ImagePlus("placeholder-" + placeholderIndex, stack);
        if (croppedSource != null) {
            int channels = Math.max(1, croppedSource.getNChannels());
            int imageSlices = Math.max(1, croppedSource.getNSlices());
            int frames = Math.max(1, croppedSource.getNFrames());
            if (channels * imageSlices * frames == stack.getSize()) {
                label.setDimensions(channels, imageSlices, frames);
                label.setOpenAsHyperStack(croppedSource.isHyperStack());
            }
        }
        return label;
    }

    private ImagePlus createEmptyLabel() {
        int width = croppedSource == null ? 96 : Math.max(1, croppedSource.getWidth());
        int height = croppedSource == null ? 96 : Math.max(1, croppedSource.getHeight());
        int slices = croppedSource == null ? 1 : Math.max(1, croppedSource.getStackSize());
        ImageStack stack = new ImageStack(width, height);
        for (int z = 0; z < slices; z++) {
            stack.addSlice("z" + (z + 1), new ByteProcessor(width, height));
        }
        ImagePlus label = new ImagePlus("failed-variation", stack);
        if (croppedSource != null) {
            int channels = Math.max(1, croppedSource.getNChannels());
            int imageSlices = Math.max(1, croppedSource.getNSlices());
            int frames = Math.max(1, croppedSource.getNFrames());
            if (channels * imageSlices * frames == stack.getSize()) {
                label.setDimensions(channels, imageSlices, frames);
                label.setOpenAsHyperStack(croppedSource.isHyperStack());
            }
        }
        return label;
    }

    private void setCachedLabel(ImagePlus label, boolean owned) {
        if (ownedCachedLabel != null && ownedCachedLabel != label) {
            disposeOwnedImage(ownedCachedLabel);
        }
        cachedLabel = label;
        ownedCachedLabel = owned ? label : null;
    }

    private void disposeIfCellResultImage(ImagePlus image) {
        if (image == null
                || image == rawSourceImage
                || image == croppedSource
                || image == ownedCachedLabel
                || image == ownedDisplayedPreviewImage
                || image == cachedOverlayImage) {
            return;
        }
        disposeOwnedImage(image);
    }

    private void disposeIfDistinctCellResultImage(ImagePlus image,
                                                  ImagePlus... alreadyDisposed) {
        if (image == null) {
            return;
        }
        if (alreadyDisposed != null) {
            for (int i = 0; i < alreadyDisposed.length; i++) {
                if (image == alreadyDisposed[i]) {
                    return;
                }
            }
        }
        disposeIfCellResultImage(image);
    }

    private static void disposeOwnedImage(ImagePlus image) {
        if (image == null) {
            return;
        }
        try {
            image.changes = false;
        } catch (Throwable ignored) {
        }
        try {
            image.close();
        } catch (Throwable ignored) {
        }
        try {
            image.flush();
        } catch (Throwable ignored) {
        }
    }

    private static boolean dimensionsMatch(ImagePlus source, ImagePlus label) {
        if (source == null || label == null) {
            return false;
        }
        if (source.getWidth() != label.getWidth()
                || source.getHeight() != label.getHeight()) {
            return false;
        }
        ImageProcessor sourceProcessor = source.getProcessor();
        ImageProcessor labelProcessor = label.getProcessor();
        return sourceProcessor != null && labelProcessor != null;
    }

    private static Histogram histogramFor(ImagePlus image) {
        if (image == null) {
            return Histogram.empty();
        }
        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;
        boolean integral = true;
        ImageStack stack = image.getStack();
        int size = stack == null ? 1 : Math.max(1, stack.getSize());
        for (int slice = 1; slice <= size; slice++) {
            ImageProcessor processor = stack == null
                    ? image.getProcessor()
                    : stack.getProcessor(slice);
            if (processor == null) {
                continue;
            }
            for (int y = 0; y < processor.getHeight(); y++) {
                for (int x = 0; x < processor.getWidth(); x++) {
                    double value = processor.getPixelValue(x, y);
                    if (!Double.isFinite(value)) {
                        continue;
                    }
                    if (value < min) min = value;
                    if (value > max) max = value;
                    if (Math.abs(value - Math.rint(value)) > 0.000001d) {
                        integral = false;
                    }
                }
            }
        }
        if (!Double.isFinite(min) || !Double.isFinite(max)) {
            return Histogram.empty();
        }
        boolean direct = integral && min >= 0.0d && max <= 65535.0d;
        int bins = direct
                ? (max <= 255.0d ? 256 : 65536)
                : OTSU_HISTOGRAM_BINS;
        int[] counts = new int[bins];
        if (max <= min) {
            int bin = direct ? (int) Math.round(min) : 0;
            counts[Math.max(0, Math.min(counts.length - 1, bin))] = pixelCount(image);
            return new Histogram(counts, min, max, direct, true);
        }
        for (int slice = 1; slice <= size; slice++) {
            ImageProcessor processor = stack == null
                    ? image.getProcessor()
                    : stack.getProcessor(slice);
            if (processor == null) {
                continue;
            }
            for (int y = 0; y < processor.getHeight(); y++) {
                for (int x = 0; x < processor.getWidth(); x++) {
                    double value = processor.getPixelValue(x, y);
                    if (!Double.isFinite(value)) {
                        continue;
                    }
                    int bin = direct
                            ? (int) Math.round(value)
                            : (int) Math.floor(((value - min) / (max - min))
                            * (bins - 1));
                    counts[Math.max(0, Math.min(counts.length - 1, bin))]++;
                }
            }
        }
        return new Histogram(counts, min, max, direct, true);
    }

    private static int pixelCount(ImagePlus image) {
        if (image == null) {
            return 0;
        }
        int width = Math.max(1, image.getWidth());
        int height = Math.max(1, image.getHeight());
        int slices = Math.max(1, image.getStackSize());
        return width * height * slices;
    }

    private static String errorDetails(Throwable error) {
        if (error == null) {
            return "Unknown error";
        }
        StringBuilder sb = new StringBuilder();
        String message = error.getMessage();
        sb.append(message == null || message.trim().isEmpty()
                ? error.getClass().getSimpleName()
                : message.trim());
        Throwable cause = error.getCause();
        if (cause != null && cause != error) {
            String causeMessage = cause.getMessage();
            sb.append("\nCaused by: ");
            sb.append(causeMessage == null || causeMessage.trim().isEmpty()
                    ? cause.getClass().getSimpleName()
                    : causeMessage.trim());
        }
        return sb.toString();
    }

    private static String html(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }

    private static final class Histogram {
        final int[] counts;
        final double min;
        final double max;
        final boolean direct;
        final boolean hasValues;

        Histogram(int[] counts, double min, double max, boolean direct,
                  boolean hasValues) {
            this.counts = counts == null ? new int[0] : counts;
            this.min = min;
            this.max = max;
            this.direct = direct;
            this.hasValues = hasValues;
        }

        static Histogram empty() {
            return new Histogram(new int[OTSU_HISTOGRAM_BINS], 0.0d, 0.0d,
                    false, false);
        }

        boolean hasValues() {
            return hasValues && counts.length > 0;
        }

        double foregroundLowerFor(int thresholdBin) {
            if (max <= min) {
                return max;
            }
            int clamped = Math.max(0, Math.min(counts.length - 1, thresholdBin));
            int next = Math.min(counts.length - 1, clamped + 1);
            return valueFor(next);
        }

        private double valueFor(int bin) {
            int clamped = Math.max(0, Math.min(counts.length - 1, bin));
            if (max <= min) {
                return min;
            }
            if (direct) {
                return clamped;
            }
            return min + ((double) clamped / (double) Math.max(1, counts.length - 1))
                    * (max - min);
        }
    }
}
