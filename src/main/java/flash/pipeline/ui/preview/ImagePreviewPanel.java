package flash.pipeline.ui.preview;

import flash.pipeline.ui.FlashTheme;

import ij.ImagePlus;
import ij.ImageStack;
import ij.gui.Overlay;
import ij.gui.Roi;
import ij.process.FloatPolygon;
import ij.process.ColorProcessor;
import ij.process.ImageProcessor;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSlider;
import javax.swing.SwingConstants;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.Stroke;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.ColorModel;
import java.awt.image.IndexColorModel;

public final class ImagePreviewPanel extends JPanel {
    private static final Dimension DEFAULT_CANVAS_SIZE = new Dimension(240, 240);
    private static final Dimension SLIM_CANVAS_SIZE = new Dimension(280, 280);
    private static final int DEFAULT_PANEL_GAP = 6;
    private static final int SLIM_PANEL_GAP = 2;

    public interface ZSliceChangeListener {
        void zSliceChanged(ImagePreviewPanel source, int zSlice);
    }

    public interface PixelClickListener {
        void pixelClicked(ImagePreviewPanel src,
                          double imageX, double imageY,
                          int z, int button, int modifiers);
    }

    private final JLabel titleLabel = new JLabel("No image selected.");
    private final JLabel detailLabel = new JLabel(" ");
    private final JLabel statusLabel = new JLabel(" ");
    private final JLabel sliceLabel = new JLabel(" ");
    private final JSlider zSlider = new JSlider(1, 1, 1);
    private final CanvasPanel canvas = new CanvasPanel();
    private final JPanel labels = new JPanel();
    private final JPanel zRow = new JPanel(new BorderLayout(6, 0));

    private ImagePlus image;
    private String previewTitle;
    private int currentC = 1;
    private int currentZ = 1;
    private int currentT = 1;
    private boolean updatingSlider;
    private boolean metadataHeaderVisible = true;
    private boolean slim;
    private boolean chromeless;
    private JLabel slimTitleLabel;
    private ZSliceChangeListener zSliceChangeListener;
    private PixelClickListener pixelClickListener;
    private final MouseAdapter canvasClickAdapter = new MouseAdapter() {
        @Override public void mouseClicked(MouseEvent e) {
            handleCanvasClick(e);
        }
    };
    private boolean canvasClickAdapterInstalled;
    private PreviewDisplaySettings displaySettings = PreviewDisplaySettings.defaultFor("Grays");
    private boolean displaySettingsEnabled = true;
    private ObjectSizeFilterPreview.Summary objectSizeGuide;
    private double drawScale = 1.0;
    private int canvasPadding = 6;
    private int drawOriginX;
    private int drawOriginY;
    private int imageW;
    private int imageH;

    public ImagePreviewPanel(String title) {
        super(new BorderLayout(DEFAULT_PANEL_GAP, DEFAULT_PANEL_GAP));
        this.previewTitle = normalizePreviewTitle(title);
        refreshBorder();

        labels.setLayout(new BoxLayout(labels, BoxLayout.Y_AXIS));
        labels.setOpaque(false);
        titleLabel.setAlignmentX(LEFT_ALIGNMENT);
        detailLabel.setAlignmentX(LEFT_ALIGNMENT);
        statusLabel.setAlignmentX(LEFT_ALIGNMENT);
        detailLabel.setForeground(FlashTheme.TEXT_HELP);
        statusLabel.setForeground(FlashTheme.TEXT_HELP);
        labels.add(titleLabel);
        labels.add(Box.createVerticalStrut(2));
        labels.add(detailLabel);
        labels.add(Box.createVerticalStrut(2));
        labels.add(statusLabel);
        add(labels, BorderLayout.NORTH);

        setCanvasPreferredSize(DEFAULT_CANVAS_SIZE);
        add(canvas, BorderLayout.CENTER);

        zRow.setOpaque(false);
        sliceLabel.setHorizontalAlignment(SwingConstants.RIGHT);
        zRow.add(new JLabel("Z:"), BorderLayout.WEST);
        zRow.add(zSlider, BorderLayout.CENTER);
        zRow.add(sliceLabel, BorderLayout.EAST);
        add(zRow, BorderLayout.SOUTH);

        zSlider.addChangeListener(new ChangeListener() {
            @Override public void stateChanged(ChangeEvent e) {
                if (updatingSlider) return;
                currentZ = zSlider.getValue();
                updateSliceLabel();
                canvas.repaint();
                if (zSliceChangeListener != null) {
                    zSliceChangeListener.zSliceChanged(ImagePreviewPanel.this, currentZ);
                }
            }
        });
        applyEmptyState(false);
    }

    public void setPreviewTitle(String title) {
        this.previewTitle = normalizePreviewTitle(title);
        if (slimTitleLabel != null) {
            slimTitleLabel.setText(previewTitle);
        }
        refreshBorder();
    }

    public void setMetadataHeaderVisible(boolean visible) {
        if (metadataHeaderVisible == visible) return;
        metadataHeaderVisible = visible;
        if (visible) {
            add(labels, BorderLayout.NORTH);
        } else {
            remove(labels);
        }
        revalidate();
        repaint();
    }

    public void setZRowVisible(boolean visible) {
        zRow.setVisible(visible);
        revalidate();
        repaint();
    }

    public void setSlim(boolean slim) {
        if (this.slim == slim) return;
        this.slim = slim;
        if (slim) {
            setPanelGap(SLIM_PANEL_GAP);
            setCanvasPreferredSize(SLIM_CANVAS_SIZE);
            setMetadataHeaderVisible(false);
            setZRowVisible(false);
            installSlimTitleLabel();
        } else {
            removeSlimTitleLabel();
            setPanelGap(DEFAULT_PANEL_GAP);
            setCanvasPreferredSize(DEFAULT_CANVAS_SIZE);
            setMetadataHeaderVisible(true);
            setZRowVisible(true);
        }
        refreshBorder();
    }

    /**
     * Strips every scrap of chrome (title label, borders, panel gap and the
     * canvas padding) so the image fills the whole panel edge-to-edge. Intended
     * for tiled grids where each cell should be the size of the image itself.
     */
    public void setChromeless(boolean chromeless) {
        if (this.chromeless == chromeless) {
            return;
        }
        this.chromeless = chromeless;
        if (chromeless) {
            canvasPadding = 0;
            setPanelGap(0);
            removeSlimTitleLabel();
            setMetadataHeaderVisible(false);
            setZRowVisible(false);
        } else {
            canvasPadding = 6;
            setPanelGap(slim ? SLIM_PANEL_GAP : DEFAULT_PANEL_GAP);
            if (slim) {
                installSlimTitleLabel();
            } else {
                setMetadataHeaderVisible(true);
                setZRowVisible(true);
            }
        }
        refreshBorder();
        revalidate();
        repaint();
    }

    public void setImage(ImagePlus image) {
        int previousZ = currentZ;
        this.image = image;
        if (hasUsableImage()) {
            currentC = clamp(image.getC(), 1, Math.max(1, image.getNChannels()));
            currentZ = clamp(previousZ, 1, effectiveZCount(image));
            currentT = clamp(image.getT(), 1, Math.max(1, image.getNFrames()));
        } else {
            currentC = 1;
            currentZ = 1;
            currentT = 1;
        }
        refresh();
    }

    public void setCurrentZ(int zSlice) {
        if (!hasUsableImage()) {
            currentZ = 1;
            setSliderState(1, 1, 1);
            zSlider.setEnabled(false);
            updateSliceLabel();
            canvas.repaint();
            return;
        }

        int slices = effectiveZCount(image);
        currentZ = clamp(zSlice, 1, slices);
        setSliderState(1, slices, currentZ);
        zSlider.setEnabled(slices > 1);
        updateSliceLabel();
        canvas.repaint();
    }

    public int getCurrentZ() {
        return currentZ;
    }

    public int getSliceCount() {
        if (!hasUsableImage()) return 1;
        return effectiveZCount(image);
    }

    public void setStatusText(String text) {
        statusLabel.setText(text == null || text.trim().isEmpty() ? " " : text);
        canvas.repaint();
    }

    public void setDisplaySettings(PreviewDisplaySettings settings) {
        displaySettings = settings == null ? PreviewDisplaySettings.defaultFor("Grays") : settings;
        canvas.repaint();
    }

    public void setObjectSizeGuide(ObjectSizeFilterPreview.Summary summary) {
        objectSizeGuide = summary;
        canvas.repaint();
    }

    void setDisplaySettingsEnabled(boolean enabled) {
        displaySettingsEnabled = enabled;
        canvas.repaint();
    }

    public void setZSliceChangeListener(ZSliceChangeListener listener) {
        this.zSliceChangeListener = listener;
    }

    public void setPixelClickListener(PixelClickListener listener) {
        this.pixelClickListener = listener;
        // The canvas should only intercept mouse events when something actually
        // consumes pixel clicks. Otherwise it would swallow clicks meant for an
        // ancestor (e.g. a variation tile's pick/select handler), since Swing
        // delivers a mouse event to the deepest component that has a listener.
        boolean shouldInstall = listener != null;
        if (shouldInstall && !canvasClickAdapterInstalled) {
            canvas.addMouseListener(canvasClickAdapter);
            canvasClickAdapterInstalled = true;
        } else if (!shouldInstall && canvasClickAdapterInstalled) {
            canvas.removeMouseListener(canvasClickAdapter);
            canvasClickAdapterInstalled = false;
        }
    }

    public void refresh() {
        if (!hasUsableImage()) {
            applyEmptyState(true);
            return;
        }

        currentC = clamp(currentC, 1, Math.max(1, image.getNChannels()));
        currentZ = clamp(currentZ, 1, effectiveZCount(image));
        currentT = clamp(currentT, 1, Math.max(1, image.getNFrames()));

        String title = image.getTitle() == null || image.getTitle().trim().isEmpty()
                ? "Untitled"
                : image.getTitle();
        int effectiveZ = effectiveZCount(image);
        boolean framesAsZ = usesFramesAsZ(image);
        titleLabel.setText(title);
        detailLabel.setText(image.getWidth() + " x " + image.getHeight()
                + ", C=" + Math.max(1, image.getNChannels())
                + ", Z=" + effectiveZ
                + (framesAsZ ? " (from T)" : "")
                + ", T=" + (framesAsZ ? 1 : Math.max(1, image.getNFrames())));

        int slices = effectiveZ;
        setSliderState(1, slices, currentZ);
        zSlider.setEnabled(slices > 1);
        updateSliceLabel();
        canvas.repaint();
    }

    boolean hasImageForTest() {
        return hasUsableImage();
    }

    boolean isZSliderEnabledForTest() {
        return zSlider.isEnabled();
    }

    String statusTextForTest() {
        return statusLabel.getText();
    }

    String titleTextForTest() {
        return titleLabel.getText();
    }

    String detailTextForTest() {
        return detailLabel.getText();
    }

    String sliceTextForTest() {
        return sliceLabel.getText();
    }

    String previewTitleForTest() {
        return previewTitle;
    }

    boolean metadataHeaderVisibleForTest() {
        return metadataHeaderVisible;
    }

    boolean zRowVisibleForTest() {
        return zRow.isVisible();
    }

    JLabel slimTitleLabelForTest() {
        return slimTitleLabel;
    }

    Dimension canvasPreferredSizeForTest() {
        return canvas.getPreferredSize();
    }

    JPanel canvasForTest() {
        return canvas;
    }

    double drawScaleForTest() {
        return drawScale;
    }

    int drawOriginXForTest() {
        return drawOriginX;
    }

    int drawOriginYForTest() {
        return drawOriginY;
    }

    int renderedImageWidthForTest() {
        return imageW;
    }

    int renderedImageHeightForTest() {
        return imageH;
    }

    int layoutVerticalGapForTest() {
        return ((BorderLayout) getLayout()).getVgap();
    }

    ImageProcessor renderedProcessorForTest() {
        return currentProcessor();
    }

    ObjectSizeFilterPreview.Summary objectSizeGuideForTest() {
        return objectSizeGuide;
    }

    boolean hasPixelClickListenerForTest() {
        return pixelClickListener != null;
    }

    void firePixelClickForTest(double imageX, double imageY,
                               int z, int button, int modifiers) {
        PixelClickListener listener = pixelClickListener;
        if (listener != null) {
            listener.pixelClicked(this, imageX, imageY, z, button, modifiers);
        }
    }

    private boolean hasUsableImage() {
        return hasUsableImage(image);
    }

    private static boolean hasUsableImage(ImagePlus candidate) {
        try {
            return candidate != null
                    && candidate.getStack() != null
                    && candidate.getStackSize() > 0;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private void applyEmptyState(boolean repaintCanvas) {
        currentC = 1;
        currentZ = 1;
        currentT = 1;
        resetDrawMetrics();
        titleLabel.setText("No image selected.");
        detailLabel.setText(" ");
        statusLabel.setText(" ");
        setSliderState(1, 1, 1);
        zSlider.setEnabled(false);
        sliceLabel.setText(" ");
        if (repaintCanvas) {
            canvas.repaint();
        }
    }

    private void refreshBorder() {
        if (chromeless) {
            setBorder(BorderFactory.createEmptyBorder());
        } else if (slim) {
            setBorder(BorderFactory.createEmptyBorder(2, 2, 2, 2));
        } else {
            setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createTitledBorder(previewTitle),
                    BorderFactory.createEmptyBorder(6, 6, 6, 6)));
        }
        revalidate();
        repaint();
    }

    private void installSlimTitleLabel() {
        if (slimTitleLabel == null) {
            slimTitleLabel = new JLabel(previewTitle);
            Font font = slimTitleLabel.getFont();
            if (font != null) {
                slimTitleLabel.setFont(font.deriveFont(Font.BOLD));
            }
            slimTitleLabel.setBorder(BorderFactory.createEmptyBorder(0, 4, 2, 4));
        } else {
            slimTitleLabel.setText(previewTitle);
        }
        if (slimTitleLabel.getParent() != this) {
            add(slimTitleLabel, BorderLayout.NORTH);
        }
    }

    private void removeSlimTitleLabel() {
        if (slimTitleLabel != null && slimTitleLabel.getParent() == this) {
            remove(slimTitleLabel);
        }
    }

    private void setCanvasPreferredSize(Dimension size) {
        canvas.setPreferredSize(new Dimension(size));
    }

    private void setPanelGap(int gap) {
        BorderLayout layout = (BorderLayout) getLayout();
        layout.setHgap(gap);
        layout.setVgap(gap);
    }

    private static String normalizePreviewTitle(String title) {
        String text = title == null ? "" : title.trim();
        return text.isEmpty() ? "Image preview" : text;
    }

    private void setSliderState(int minimum, int maximum, int value) {
        updatingSlider = true;
        try {
            zSlider.setMinimum(minimum);
            zSlider.setMaximum(maximum);
            zSlider.setValue(clamp(value, minimum, maximum));
        } finally {
            updatingSlider = false;
        }
    }

    private void updateSliceLabel() {
        if (!hasUsableImage()) {
            sliceLabel.setText(" ");
            return;
        }
        int slices = effectiveZCount(image);
        sliceLabel.setText(currentZ + "/" + slices);
    }

    private ImageProcessor currentProcessor() {
        ImagePlus imp = image;
        if (!hasUsableImage(imp)) return null;
        ImageProcessor processor;
        try {
            ImageStack stack = imp.getStack();
            int stackSize = stack == null ? 0 : stack.getSize();
            if (stackSize < 1) return null;
            boolean framesAsZ = usesFramesAsZ(imp);
            int stackIndex = imp.getStackIndex(
                    clamp(currentC, 1, Math.max(1, imp.getNChannels())),
                    framesAsZ ? 1 : clamp(currentZ, 1, Math.max(1, imp.getNSlices())),
                    framesAsZ ? clamp(currentZ, 1, Math.max(1, imp.getNFrames()))
                            : clamp(currentT, 1, Math.max(1, imp.getNFrames())));
            if (stackIndex < 1 || stackIndex > stackSize) {
                stackIndex = clamp(currentZ, 1, stackSize);
            }
            processor = stack.getProcessor(stackIndex);
        } catch (RuntimeException e) {
            return null;
        }
        if (processor == null) return null;
        ImageProcessor copy;
        try {
            copy = processor.duplicate();
        } catch (RuntimeException e) {
            return null;
        }
        boolean colorProcessor = copy instanceof ColorProcessor;
        if (!displaySettingsEnabled) {
            if (!colorProcessor) {
                double min = safeDisplayRangeMin(imp, copy.getMin());
                double max = safeDisplayRangeMax(imp, copy.getMax());
                if (max > min) {
                    copy.setMinAndMax(min, max);
                }
            }
            return copy;
        }
        double min = displaySettings.hasDisplayRange()
                ? displaySettings.getDisplayMin()
                : safeDisplayRangeMin(imp, copy.getMin());
        double max = displaySettings.hasDisplayRange()
                ? displaySettings.getDisplayMax()
                : safeDisplayRangeMax(imp, copy.getMax());
        if (!colorProcessor && max > min) {
            copy.setMinAndMax(min, max);
        }
        ColorModel colorModel = colorProcessor
                ? null
                : colorModelFor(displaySettings.effectiveLutName());
        if (colorModel != null) {
            copy.setColorModel(colorModel);
        }
        return copy;
    }

    private static int effectiveZCount(ImagePlus image) {
        if (image == null) return 1;
        try {
            if (usesFramesAsZ(image)) {
                return Math.max(1, image.getNFrames());
            }
            return Math.max(1, image.getNSlices());
        } catch (RuntimeException e) {
            return 1;
        }
    }

    private static boolean usesFramesAsZ(ImagePlus image) {
        if (image == null) return false;
        try {
            int channels = Math.max(1, image.getNChannels());
            int slices = Math.max(1, image.getNSlices());
            int frames = Math.max(1, image.getNFrames());
            int stackSize = Math.max(1, image.getStackSize());
            return channels == 1
                    && slices == 1
                    && frames > 1
                    && stackSize == channels * frames;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static double safeDisplayRangeMin(ImagePlus image, double fallback) {
        if (!hasUsableImage(image)) return fallback;
        try {
            double value = image.getDisplayRangeMin();
            return Double.isFinite(value) ? value : fallback;
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private static double safeDisplayRangeMax(ImagePlus image, double fallback) {
        if (!hasUsableImage(image)) return fallback;
        try {
            double value = image.getDisplayRangeMax();
            return Double.isFinite(value) ? value : fallback;
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private static ColorModel colorModelFor(String lutName) {
        String normalized = PreviewDisplaySettings.normalizeLutName(lutName);
        byte[] red = new byte[256];
        byte[] green = new byte[256];
        byte[] blue = new byte[256];
        for (int i = 0; i < 256; i++) {
            int r = i;
            int g = i;
            int b = i;
            if ("Red".equals(normalized)) {
                g = 0;
                b = 0;
            } else if ("Green".equals(normalized)) {
                r = 0;
                b = 0;
            } else if ("Blue".equals(normalized)) {
                r = 0;
                g = 0;
            } else if ("Cyan".equals(normalized)) {
                r = 0;
            } else if ("Magenta".equals(normalized)) {
                g = 0;
            } else if ("Yellow".equals(normalized)) {
                b = 0;
            }
            red[i] = (byte) r;
            green[i] = (byte) g;
            blue[i] = (byte) b;
        }
        return new IndexColorModel(8, 256, red, green, blue);
    }

    private void resetDrawMetrics() {
        drawScale = 1.0;
        drawOriginX = 0;
        drawOriginY = 0;
        imageW = 0;
        imageH = 0;
    }

    private void handleCanvasClick(MouseEvent e) {
        if (e == null || imageW <= 0 || imageH <= 0 || drawScale <= 0.0) return;
        double imgX = (e.getX() - drawOriginX) / drawScale;
        double imgY = (e.getY() - drawOriginY) / drawScale;
        if (imgX < 0.0 || imgY < 0.0 || imgX >= imageW || imgY >= imageH) return;
        PixelClickListener listener = pixelClickListener;
        if (listener != null) {
            listener.pixelClicked(ImagePreviewPanel.this,
                    imgX, imgY, currentZ, e.getButton(), e.getModifiersEx());
        }
    }

    static int clamp(int value, int minimum, int maximum) {
        if (maximum < minimum) return minimum;
        if (value < minimum) return minimum;
        if (value > maximum) return maximum;
        return value;
    }

    private final class CanvasPanel extends JPanel {
        CanvasPanel() {
            setBackground(Color.BLACK);
            setOpaque(true);
            setMinimumSize(new Dimension(180, 160));
            // The pixel-click listener is attached lazily by
            // setPixelClickListener so the canvas does not steal clicks from
            // ancestors when no consumer is registered.
        }

        @Override protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                ImageProcessor processor = currentProcessor();
                if (processor == null) {
                    resetDrawMetrics();
                    drawCenteredText(g2, "No image selected");
                    return;
                }
                Image awtImage = processor.createImage();
                if (awtImage == null) {
                    resetDrawMetrics();
                    drawCenteredText(g2, "Preview unavailable");
                    return;
                }

                int imageWidth = processor.getWidth();
                int imageHeight = processor.getHeight();
                int pad = 2 * canvasPadding;
                int availableWidth = Math.max(1, getWidth() - pad);
                int availableHeight = Math.max(1, getHeight() - pad);
                double scale = Math.min(
                        availableWidth / (double) imageWidth,
                        availableHeight / (double) imageHeight);
                int drawWidth = Math.max(1, (int) Math.round(imageWidth * scale));
                int drawHeight = Math.max(1, (int) Math.round(imageHeight * scale));
                int x = (getWidth() - drawWidth) / 2;
                int y = (getHeight() - drawHeight) / 2;
                imageW = imageWidth;
                imageH = imageHeight;
                drawScale = scale;
                drawOriginX = x;
                drawOriginY = y;

                g2.setRenderingHint(
                        RenderingHints.KEY_INTERPOLATION,
                        RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g2.drawImage(awtImage, x, y, drawWidth, drawHeight, null);
                if (!chromeless) {
                    g2.setColor(new Color(120, 120, 120));
                    g2.drawRect(x, y, drawWidth - 1, drawHeight - 1);
                }
                drawImageOverlay(g2, x, y, scale);
                drawObjectSizeGuide(g2, x, y, drawWidth, drawHeight, scale);
            } finally {
                g2.dispose();
            }
        }

        private void drawImageOverlay(Graphics2D g2, int imageX, int imageY,
                                      double imageScale) {
            ImagePlus imp = image;
            if (imp == null) return;
            Overlay overlay;
            try {
                overlay = imp.getOverlay();
            } catch (RuntimeException e) {
                return;
            }
            if (overlay == null || overlay.size() == 0) return;
            Stroke oldStroke = g2.getStroke();
            g2.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            for (int i = 0; i < overlay.size(); i++) {
                Roi roi = overlay.get(i);
                if (!matchesCurrentPosition(roi)) continue;
                Color color = roi.getStrokeColor();
                g2.setColor(color == null ? Color.YELLOW : color);
                FloatPolygon points = roi.getFloatPolygon();
                if (points == null || points.npoints == 0) {
                    java.awt.Rectangle bounds = roi.getBounds();
                    drawOverlayPoint(g2, imageX, imageY, imageScale,
                            bounds.getCenterX(), bounds.getCenterY());
                } else {
                    for (int p = 0; p < points.npoints; p++) {
                        drawOverlayPoint(g2, imageX, imageY, imageScale,
                                points.xpoints[p], points.ypoints[p]);
                    }
                }
            }
            g2.setStroke(oldStroke);
        }

        private boolean matchesCurrentPosition(Roi roi) {
            if (roi == null) return false;
            int c = roi.getCPosition();
            int z = roi.getZPosition();
            int t = roi.getTPosition();
            int pos = roi.getPosition();
            int channelCount = 1;
            try {
                channelCount = image == null ? 1 : Math.max(1, image.getNChannels());
            } catch (RuntimeException ignored) {
                channelCount = 1;
            }
            return (c == 0 || c == currentC || channelCount == 1)
                    && (z == 0 || z == currentZ)
                    && (t == 0 || t == currentT)
                    && (pos == 0 || pos == currentZ);
        }

        private void drawOverlayPoint(Graphics2D g2, int imageX, int imageY,
                                      double imageScale, double px, double py) {
            if (!Double.isFinite(px) || !Double.isFinite(py)) return;
            Color markerColor = g2.getColor();
            int sx = imageX + (int) Math.round(px * imageScale);
            int sy = imageY + (int) Math.round(py * imageScale);
            int radius = 5;
            g2.setColor(new Color(0, 0, 0, 180));
            g2.fillOval(sx - radius - 1, sy - radius - 1,
                    (radius + 1) * 2, (radius + 1) * 2);
            g2.setColor(markerColor);
            g2.drawOval(sx - radius, sy - radius, radius * 2, radius * 2);
        }

        private void drawObjectSizeGuide(Graphics2D g2, int imageX, int imageY,
                                         int imageWidth, int imageHeight,
                                         double imageScale) {
            ObjectSizeFilterPreview.Summary summary = objectSizeGuide;
            if (summary == null || imageWidth < 24 || imageHeight < 24) return;
            int baseline = imageY + imageHeight - 12;
            int left = imageX + 10;
            int maxLength = Math.max(8, imageWidth - 20);
            drawGuideLine(g2, left, baseline, maxLength, summary.minDiameterPixels,
                    ObjectSizeFilterPreview.belowMinColor(), "min", imageScale);
            if (summary.maxFinite) {
                drawGuideLine(g2, left, baseline - 18, maxLength, summary.maxDiameterPixels,
                        ObjectSizeFilterPreview.aboveMaxColor(), "max", imageScale);
            }
        }

        private void drawGuideLine(Graphics2D g2, int x, int y, int maxLength,
                                   double diameterPixels, Color color, String label,
                                   double imageScale) {
            if (!Double.isFinite(diameterPixels) || diameterPixels <= 0.0) return;
            int length = (int) Math.round(diameterPixels * imageScale);
            length = Math.max(8, Math.min(maxLength, length));
            Stroke oldStroke = g2.getStroke();
            g2.setStroke(new BasicStroke(4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g2.setColor(new Color(0, 0, 0, 180));
            g2.drawLine(x, y + 1, x + length, y + 1);
            g2.setColor(color == null ? FlashTheme.DANGER_FG : color);
            g2.drawLine(x, y, x + length, y);
            g2.setStroke(oldStroke);
            g2.setFont(g2.getFont().deriveFont(Font.BOLD, 11f));
            g2.setColor(new Color(0, 0, 0, 190));
            g2.drawString(label, x + length + 5, y + 4);
            g2.setColor(Color.WHITE);
            g2.drawString(label, x + length + 4, y + 3);
        }

        private void drawCenteredText(Graphics2D g2, String text) {
            g2.setColor(new Color(150, 150, 150));
            int width = g2.getFontMetrics().stringWidth(text);
            int x = Math.max(0, (getWidth() - width) / 2);
            int y = Math.max(g2.getFontMetrics().getAscent(),
                    (getHeight() + g2.getFontMetrics().getAscent()) / 2);
            g2.drawString(text, x, y);
        }
    }
}
