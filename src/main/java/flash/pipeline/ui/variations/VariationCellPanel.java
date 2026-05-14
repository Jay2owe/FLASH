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
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.RadialGradientPaint;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.util.Locale;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

public final class VariationCellPanel extends JPanel {

    private static final Color CARD_BACKGROUND = new Color(0x26, 0x2A, 0x2E);
    private static final Color DEFAULT_BORDER = new Color(0x5B, 0x62, 0x69);
    private static final Color HOVER_BORDER = new Color(0, 0, 0, 20);
    private static final Color KNEE_BORDER = new Color(0xF0, 0xE4, 0x42);
    private static final Color STABILITY_BORDER = new Color(0x56, 0xB4, 0xE9);
    private static final Color COMPARE_BORDER = Color.WHITE;
    private static final Color FOOTER_COLOR = new Color(0xC0, 0xC5, 0xCA);
    private static final Color ERROR_COLOR = new Color(0xE6, 0x9F, 0x00);
    private static final Color RIBBON_RIM = new Color(0, 0, 0, 170);
    private static final int CARD_RADIUS = 8;
    private static final int UNKNOWN_DELTA = Integer.MIN_VALUE;
    private static final int PEEK_DELAY_MS = 120;
    private static final int PEEK_DRAG_CANCEL_PX = 4;
    private static final int OTSU_HISTOGRAM_BINS = 256;
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
    private final JPanel footerPanel = new JPanel();
    private final JPanel segmentationFooterPanel = new JPanel();
    private final JPanel filterFooterPanel = new JPanel();
    private final JLabel countLabel = new JLabel("pending", SwingConstants.CENTER);
    private final JLabel deltaLabel = new JLabel("", SwingConstants.CENTER);
    private final JLabel iouLabel = new JLabel("", SwingConstants.CENTER);
    private final JLabel filterChipLabel = new JLabel("", SwingConstants.CENTER);
    private final JLabel filterSnrLabel = new JLabel("", SwingConstants.CENTER);
    private final JLabel filterBgSigmaLabel = new JLabel("", SwingConstants.CENTER);
    private final Timer haloTimer;
    private final Timer peekDelayTimer;

    private ImagePlus cachedLabel;
    private ResultsTable cachedStats;
    private ImagePlus rawSourceImage;
    private ImagePlus filteredImage;
    private ImagePlus cachedOverlayImage;
    private ImagePlus displayedPreviewImage;
    private ImagePlus currentPreviewImage;
    private OverlayMode overlayMode = OverlayMode.NONE;
    private double cachedOtsuLower = Double.NaN;
    private long durationMs = -1L;
    private int objectCount = -1;
    private int deltaN = UNKNOWN_DELTA;
    private double iouToNeighbours = Double.NaN;
    private double filterSnr = Double.NaN;
    private double filterBgSigma = Double.NaN;
    private String errorText = "";
    private String ribbonLabelOverride;
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
        setPreferredSize(new Dimension(360, 330));
        setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
        preview.setSlim(true);
        preview.setZRowVisible(false);
        add(preview, BorderLayout.CENTER);

        footerPanel.setOpaque(false);
        footerPanel.setLayout(new CardLayout());
        footerPanel.setBorder(BorderFactory.createEmptyBorder(0, 2, 0, 2));
        segmentationFooterPanel.setOpaque(false);
        segmentationFooterPanel.setLayout(new BoxLayout(segmentationFooterPanel,
                BoxLayout.X_AXIS));
        filterFooterPanel.setOpaque(false);
        filterFooterPanel.setLayout(new BoxLayout(filterFooterPanel, BoxLayout.Y_AXIS));
        configureFooterLabel(countLabel, FlashTheme.mono(11f));
        configureFooterLabel(deltaLabel, FlashTheme.mono(11f));
        configureFooterLabel(iouLabel, FlashTheme.mono(11f));
        configureFooterLabel(filterChipLabel, FlashTheme.mono(10f).deriveFont(Font.BOLD));
        configureFooterLabel(filterSnrLabel, FlashTheme.mono(11f).deriveFont(Font.BOLD));
        configureFooterLabel(filterBgSigmaLabel, FlashTheme.mono(10f));
        filterChipLabel.setAlignmentX(CENTER_ALIGNMENT);
        filterSnrLabel.setAlignmentX(CENTER_ALIGNMENT);
        filterBgSigmaLabel.setAlignmentX(CENTER_ALIGNMENT);
        segmentationFooterPanel.add(Box.createHorizontalGlue());
        segmentationFooterPanel.add(countLabel);
        segmentationFooterPanel.add(Box.createHorizontalStrut(12));
        segmentationFooterPanel.add(deltaLabel);
        segmentationFooterPanel.add(Box.createHorizontalStrut(12));
        segmentationFooterPanel.add(iouLabel);
        segmentationFooterPanel.add(Box.createHorizontalGlue());
        filterFooterPanel.add(filterChipLabel);
        filterFooterPanel.add(filterSnrLabel);
        filterFooterPanel.add(filterBgSigmaLabel);
        footerPanel.add(segmentationFooterPanel, "segmentation");
        footerPanel.add(filterFooterPanel, "filter");
        add(footerPanel, BorderLayout.SOUTH);
        installMouseHandlers();
        refreshFooter();
        refreshBorder();
        refreshTooltip();
    }

    public ParameterCombo combo() {
        return combo;
    }

    public ImagePreviewPanel preview() {
        return preview;
    }

    public void setRawSource(ImagePlus src) {
        this.rawSourceImage = src;
        if (src == null) {
            cancelPeek(true);
        }
    }

    public void setState(String state) {
        showSegmentationFooter();
        clearRibbonLabelOverride();
        filteredImage = null;
        invalidateOverlayCache();
        errorState = false;
        errorText = "";
        acceptEnabled = false;
        objectCount = -1;
        deltaN = UNKNOWN_DELTA;
        iouToNeighbours = Double.NaN;
        filterSnr = Double.NaN;
        filterBgSigma = Double.NaN;
        filterChipLabel.setText("");
        filterChipLabel.setVisible(false);
        setDisplayedPreviewImage(null);
        setStateText(state == null || state.trim().isEmpty() ? "pending" : state,
                FOOTER_COLOR);
        refreshTooltip();
    }

    public void setLabel(ImagePlus label, ResultsTable stats) {
        setLabel(label, stats, stats == null ? -1 : stats.size(), -1L);
    }

    public void setResult(VariationResult result) {
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
        ImagePlus filtered = result.previewImage() == null
                ? result.label()
                : result.previewImage();
        invalidateOverlayCache();
        filteredImage = filtered;
        cachedLabel = null;
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
        SlotSubstitutionCombo substitution = SlotSubstitutionCombo.from(result.combo());
        String chip = substitution == null ? "" : substitution.displayLabel();
        filterChipLabel.setText(chip);
        filterChipLabel.setVisible(chip.length() > 0);
        filterSnrLabel.setText("SNR " + formatOneDecimal(filterSnr));
        filterBgSigmaLabel.setText("bg \u03c3 " + formatInteger(filterBgSigma));
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
        filteredImage = null;
        invalidateOverlayCache();
        errorState = true;
        acceptEnabled = false;
        errorText = errorDetails(error);
        showSegmentationFooter();
        cachedLabel = createEmptyLabel();
        cachedStats = null;
        objectCount = -1;
        deltaN = UNKNOWN_DELTA;
        iouToNeighbours = Double.NaN;
        filterSnr = Double.NaN;
        filterBgSigma = Double.NaN;
        filterChipLabel.setText("");
        filterChipLabel.setVisible(false);
        durationMs = -1L;
        setDisplayedPreviewImage(null);
        setStateText(ERROR_BADGE, ERROR_COLOR);
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
        filteredImage = null;
        invalidateOverlayCache();
        this.cachedLabel = label == null ? createPlaceholderLabel() : label;
        this.cachedStats = stats;
        this.objectCount = Math.max(0, nObjects);
        this.durationMs = durationMs;
        this.errorState = false;
        this.errorText = "";
        this.acceptEnabled = true;
        this.filterSnr = Double.NaN;
        this.filterBgSigma = Double.NaN;
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
        setDisplayedPreviewImage(rendered);
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

    public void setZ(int z) {
        preview.setCurrentZ(z);
    }

    public void setDeltaN(int deltaN) {
        this.deltaN = deltaN;
        refreshFooter();
        refreshTooltip();
    }

    public void clearDeltaN() {
        this.deltaN = UNKNOWN_DELTA;
        refreshFooter();
        refreshTooltip();
    }

    public void setIouToNeighbours(double iouToNeighbours) {
        this.iouToNeighbours = iouToNeighbours;
        refreshFooter();
        refreshTooltip();
    }

    public void setKneeWinner(boolean kneeWinner) {
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

    public void setStabilityWinner(boolean stabilityWinner, double meanNeighbourIou) {
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

    public void setBorderHint(BorderHint hint) {
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

    public void setRibbonLabel(String label) {
        String safeLabel = label == null ? "" : label.trim();
        ribbonLabelOverride = safeLabel.length() == 0 ? null : safeLabel;
        repaint();
    }

    void setSelectedForCompare(boolean selectedForCompare) {
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
            if (filterChipLabel.isVisible()) {
                return new String[] {
                        filterChipLabel.getText(),
                        filterSnrLabel.getText(),
                        filterBgSigmaLabel.getText()
                };
            }
            return new String[] {
                    filterSnrLabel.getText(),
                    filterBgSigmaLabel.getText()
            };
        }
        return new String[] { badgeText() };
    }

    String badgeText() {
        if (filterFooterActive) {
            String prefix = filterChipLabel.isVisible()
                    ? filterChipLabel.getText() + " "
                    : "";
            return prefix + filterSnrLabel.getText() + " "
                    + filterBgSigmaLabel.getText();
        }
        StringBuilder out = new StringBuilder(countLabel.getText());
        if (deltaLabel.isVisible() && deltaLabel.getText().length() > 0) {
            out.append(' ').append(deltaLabel.getText());
        }
        if (iouLabel.isVisible() && iouLabel.getText().length() > 0) {
            out.append(' ').append(iouLabel.getText());
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

    String ribbonLabelForTest() {
        return ribbonLabelOverride;
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

    @Override protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                RenderingHints.VALUE_ANTIALIAS_ON);
        RoundRectangle2D card = cardShape();
        g2.setColor(CARD_BACKGROUND);
        g2.fill(card);
        g2.setStroke(new BasicStroke(1f));
        g2.setColor(selectedForCompare ? COMPARE_BORDER : DEFAULT_BORDER);
        g2.draw(card);
        g2.dispose();
    }

    @Override protected void paintChildren(Graphics g) {
        super.paintChildren(g);
        paintHoverTint(g);
        if (!peeking) {
            paintHalo(g);
            paintCompareBadge(g);
            paintRibbons(g);
        }
    }

    @Override public void removeNotify() {
        cancelPeek(true);
        haloTimer.stop();
        super.removeNotify();
    }

    private void installMouseHandlers() {
        MouseAdapter listener = new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) {
                handleMousePressed(e);
            }

            @Override public void mouseReleased(MouseEvent e) {
                handleMouseReleased();
            }

            @Override public void mouseClicked(MouseEvent e) {
                if (suppressNextClick) {
                    suppressNextClick = false;
                    if (e != null) {
                        e.consume();
                    }
                    return;
                }
                if (e != null && e.isShiftDown()) {
                    if (onCompare != null) {
                        onCompare.accept(combo, VariationCellPanel.this);
                    }
                } else if (acceptEnabled && onAccept != null) {
                    onAccept.accept(combo);
                }
            }

            @Override public void mouseEntered(MouseEvent e) {
                hover = true;
                refreshBorder();
            }

            @Override public void mouseExited(MouseEvent e) {
                hover = false;
                cancelPeek(true);
                pressPoint = null;
                refreshBorder();
            }

            @Override public void mouseDragged(MouseEvent e) {
                handleMouseDragged(e);
            }
        };
        installMouseHandler(this, listener);
        installMouseHandler(preview, listener);
        installMouseHandler(footerPanel, listener);
        installMouseHandler(segmentationFooterPanel, listener);
        installMouseHandler(filterFooterPanel, listener);
        installMouseHandler(countLabel, listener);
        installMouseHandler(deltaLabel, listener);
        installMouseHandler(iouLabel, listener);
        installMouseHandler(filterSnrLabel, listener);
        installMouseHandler(filterBgSigmaLabel, listener);
    }

    private void installMouseHandler(Component component, MouseAdapter listener) {
        component.addMouseListener(listener);
        component.addMouseMotionListener(listener);
    }

    private void handleMousePressed(MouseEvent e) {
        cancelPeek(true);
        suppressNextClick = false;
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
        cachedOverlayImage = null;
        cachedOtsuLower = Double.NaN;
    }

    private void setDisplayedPreviewImage(ImagePlus image) {
        displayedPreviewImage = image;
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
        countLabel.setText(String.valueOf(objectCount));
        countLabel.setForeground(FOOTER_COLOR);
        countLabel.setFont(FlashTheme.mono(11f));

        deltaLabel.setText(deltaN == UNKNOWN_DELTA ? "" : formatDelta(deltaN));
        deltaLabel.setVisible(deltaN != UNKNOWN_DELTA);
        deltaLabel.setForeground(FOOTER_COLOR);
        deltaLabel.setFont(FlashTheme.mono(11f).deriveFont(
                kneeWinner ? Font.BOLD : Font.PLAIN));

        boolean hasIou = !Double.isNaN(iouToNeighbours);
        iouLabel.setText(hasIou ? "IoU "
                + String.format(Locale.ROOT, "%.2f", Double.valueOf(iouToNeighbours)) : "");
        iouLabel.setVisible(hasIou);
        iouLabel.setForeground(FOOTER_COLOR);
        iouLabel.setFont(FlashTheme.mono(11f).deriveFont(
                stabilityWinner ? Font.BOLD : Font.PLAIN));
        footerPanel.revalidate();
        footerPanel.repaint();
    }

    private void refreshTooltip() {
        StringBuilder sb = new StringBuilder();
        sb.append("<html>");
        sb.append(html(combo.toCanonicalJson()));
        if (errorState) {
            sb.append("<br><b>Failed:</b> ")
                    .append(html(errorText).replace("\n", "<br>"));
            sb.append("</html>");
            setTooltips(sb.toString());
            return;
        }
        if (filterFooterActive) {
            if (filterChipLabel.isVisible()) {
                sb.append("<br>").append(html(filterChipLabel.getText()));
            }
            sb.append("<br>").append(html(filterSnrLabel.getText()));
            sb.append("<br>").append(html(filterBgSigmaLabel.getText()));
            if (durationMs >= 0L) {
                sb.append("<br>durationMs: ").append(durationMs).append(" ms");
            }
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
        sb.append("</html>");
        setTooltips(sb.toString());
    }

    private void setTooltips(String text) {
        setToolTipText(text);
        preview.setToolTipText(text);
        footerPanel.setToolTipText(text);
        segmentationFooterPanel.setToolTipText(text);
        filterFooterPanel.setToolTipText(text);
        countLabel.setToolTipText(text);
        deltaLabel.setToolTipText(text);
        iouLabel.setToolTipText(text);
        filterChipLabel.setToolTipText(text);
        filterSnrLabel.setToolTipText(text);
        filterBgSigmaLabel.setToolTipText(text);
    }

    private void setStateText(String text, Color color) {
        countLabel.setText(text);
        countLabel.setForeground(color);
        countLabel.setFont(FlashTheme.caption());
        deltaLabel.setText("");
        deltaLabel.setVisible(false);
        iouLabel.setText("");
        iouLabel.setVisible(false);
        footerPanel.revalidate();
        footerPanel.repaint();
    }

    private void showSegmentationFooter() {
        filterFooterActive = false;
        CardLayout layout = (CardLayout) footerPanel.getLayout();
        layout.show(footerPanel, "segmentation");
    }

    private void showFilterFooter() {
        filterFooterActive = true;
        CardLayout layout = (CardLayout) footerPanel.getLayout();
        layout.show(footerPanel, "filter");
        footerPanel.revalidate();
        footerPanel.repaint();
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
        float alpha = 0.18f + 0.10f * (float) Math.sin(haloPhase);
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
        g2.setStroke(new BasicStroke(1f));
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
        if (stabilityWinner) {
            paintRibbon(g, "STABLE MASKS", STABILITY_BORDER, Color.WHITE,
                    !kneeWinner);
        }
    }

    private void paintRibbon(Graphics g, String text, Color fill, Color textColor,
                             boolean left) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                RenderingHints.VALUE_ANTIALIAS_ON);
        int side = 86;
        Path2D.Double path = new Path2D.Double();
        if (left) {
            path.moveTo(0, 0);
            path.lineTo(side, 0);
            path.lineTo(0, side);
        } else {
            int w = getWidth();
            path.moveTo(w, 0);
            path.lineTo(w - side, 0);
            path.lineTo(w, side);
        }
        path.closePath();
        g2.setColor(fill);
        g2.fill(path);
        g2.setColor(RIBBON_RIM);
        g2.setStroke(new BasicStroke(1f));
        g2.draw(path);
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

    private void clearRibbonLabelOverride() {
        ribbonLabelOverride = null;
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
        return new RoundRectangle2D.Double(0.5d, 0.5d,
                Math.max(1, getWidth() - 1),
                Math.max(1, getHeight() - 1),
                CARD_RADIUS, CARD_RADIUS);
    }

    private static void configureFooterLabel(JLabel label, Font font) {
        label.setFont(font);
        label.setForeground(FOOTER_COLOR);
        label.setHorizontalAlignment(SwingConstants.CENTER);
        label.setOpaque(false);
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
