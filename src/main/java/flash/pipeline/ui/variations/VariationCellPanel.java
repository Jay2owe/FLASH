package flash.pipeline.ui.variations;

import flash.pipeline.ui.preview.ImagePreviewPanel;
import flash.pipeline.ui.preview.ObjectOverlayRenderer;

import ij.ImagePlus;
import ij.ImageStack;
import ij.measure.ResultsTable;
import ij.process.ByteProcessor;
import ij.process.ImageProcessor;

import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.border.Border;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

public final class VariationCellPanel extends JPanel {

    private static final Color DEFAULT_BORDER = new Color(170, 176, 182);
    private static final Color HOVER_BORDER = new Color(190, 64, 64);
    private static final Color KNEE_BORDER = new Color(58, 150, 86);
    private static final Color STABILITY_BORDER = new Color(210, 160, 30);

    private final ParameterCombo combo;
    private final ImagePlus croppedSource;
    private final Consumer<ParameterCombo> onAccept;
    private final BiConsumer<ParameterCombo, VariationCellPanel> onCompare;
    private final int placeholderIndex;
    private final ImagePreviewPanel preview = new ImagePreviewPanel("Variation");
    private final JLabel footer = new JLabel("pending", SwingConstants.CENTER);

    private ImagePlus cachedLabel;
    private ResultsTable cachedStats;
    private long durationMs = -1L;
    private int objectCount = -1;
    private boolean hover;
    private boolean kneeWinner;
    private boolean stabilityWinner;
    private boolean selectedForCompare;

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

        setOpaque(true);
        setBackground(Color.WHITE);
        setPreferredSize(new Dimension(360, 330));
        preview.setSlim(true);
        preview.setZRowVisible(false);
        add(preview, BorderLayout.CENTER);

        footer.setFont(footer.getFont().deriveFont(java.awt.Font.PLAIN, 11f));
        footer.setForeground(new Color(65, 70, 75));
        add(footer, BorderLayout.SOUTH);
        installMouseHandlers();
        refreshBorder();
        refreshTooltip();
    }

    public ParameterCombo combo() {
        return combo;
    }

    public ImagePreviewPanel preview() {
        return preview;
    }

    public void setState(String state) {
        footer.setText(state == null || state.trim().isEmpty() ? "pending" : state);
        refreshTooltip();
    }

    public void setLabel(ImagePlus label, ResultsTable stats) {
        setLabel(label, stats, stats == null ? -1 : stats.size(), -1L);
    }

    public void setResult(VariationResult result) {
        if (result == null) {
            return;
        }
        if (result.hasError()) {
            footer.setText("error");
            setToolTipText(result.error().getMessage());
            return;
        }
        setLabel(result.label(), result.stats(), result.nObjects(), result.durationMs());
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
        this.cachedLabel = label == null ? createPlaceholderLabel() : label;
        this.cachedStats = stats;
        this.objectCount = Math.max(0, nObjects);
        this.durationMs = durationMs;

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
        preview.setImage(rendered);
        footer.setText(String.valueOf(objectCount));
        refreshTooltip();
    }

    public void setZ(int z) {
        preview.setCurrentZ(z);
    }

    public void setKneeWinner(boolean kneeWinner) {
        this.kneeWinner = kneeWinner;
        refreshBorder();
    }

    public void setStabilityWinner(boolean stabilityWinner) {
        this.stabilityWinner = stabilityWinner;
        refreshBorder();
    }

    void setSelectedForCompare(boolean selectedForCompare) {
        this.selectedForCompare = selectedForCompare;
        refreshBorder();
    }

    String footerTextForTest() {
        return badgeText();
    }

    String badgeText() {
        return footer.getText();
    }

    int currentZForTest() {
        return preview.getCurrentZ();
    }

    ImagePlus cachedLabelForTest() {
        return cachedLabel;
    }

    void clickForTest(boolean shift) {
        if (shift) {
            if (onCompare != null) {
                onCompare.accept(combo, this);
            }
        } else if (onAccept != null) {
            onAccept.accept(combo);
        }
    }

    private void installMouseHandlers() {
        MouseAdapter listener = new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                if (e != null && e.isShiftDown()) {
                    if (onCompare != null) {
                        onCompare.accept(combo, VariationCellPanel.this);
                    }
                } else if (onAccept != null) {
                    onAccept.accept(combo);
                }
            }

            @Override public void mouseEntered(MouseEvent e) {
                hover = true;
                refreshBorder();
            }

            @Override public void mouseExited(MouseEvent e) {
                hover = false;
                refreshBorder();
            }
        };
        addMouseListener(listener);
        preview.addMouseListener(listener);
        footer.addMouseListener(listener);
    }

    private void refreshBorder() {
        Color color = DEFAULT_BORDER;
        int thickness = 1;
        if (stabilityWinner) {
            color = STABILITY_BORDER;
            thickness = 2;
        } else if (kneeWinner) {
            color = KNEE_BORDER;
            thickness = 2;
        }
        if (selectedForCompare) {
            thickness = 3;
        }
        if (hover) {
            color = HOVER_BORDER;
            thickness = 2;
        }
        Border outer = BorderFactory.createLineBorder(color, thickness);
        Border inner = BorderFactory.createEmptyBorder(5, 5, 5, 5);
        setBorder(BorderFactory.createCompoundBorder(outer, inner));
    }

    private void refreshTooltip() {
        StringBuilder sb = new StringBuilder();
        sb.append("<html>");
        sb.append(combo.toCanonicalJson());
        if (durationMs >= 0L) {
            sb.append("<br>").append(durationMs).append(" ms");
        }
        if (cachedStats != null) {
            sb.append("<br>").append(cachedStats.size()).append(" stats rows");
        }
        sb.append("</html>");
        setToolTipText(sb.toString());
        preview.setToolTipText(sb.toString());
        footer.setToolTipText(sb.toString());
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
}
