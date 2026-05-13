package flash.pipeline.ui.sandbox.variation;

import flash.pipeline.image.variation.VariantPlan;
import ij.ImagePlus;
import ij.gui.ImageCanvas;
import ij.plugin.ZProjector;
import ij.process.ImageProcessor;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FlowLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

/**
 * One raw, variant, or error cell in the variation grid.
 */
public final class TilePanel extends JPanel {

    private static final Color RAW_CAPTION = new Color(0x1f, 0x4f, 0x82);
    private static final Color NORMAL_CAPTION = new Color(0x33, 0x33, 0x33);
    private static final Color ERROR_CAPTION = new Color(0x99, 0x00, 0x00);
    private static final Color APPLIED_GREEN = new Color(0x1f, 0x8f, 0x4f);
    private static final Color APPLIED_BACKGROUND = new Color(0xef, 0xfb, 0xf3);
    private static final Color NORMAL_BORDER = new Color(0xc0, 0xc0, 0xc0);
    private static final Color ERROR_BORDER = new Color(0x99, 0x00, 0x00);

    private final ImagePlus scrubImp;
    private final ImageCanvas scrubCanvas;
    private final JPanel centre;
    private final JLabel captionLabel;
    private final JPanel actionPanel;
    private final String label;
    private final boolean rawTile;
    private final boolean errorTile;
    private final VariantPlan plan;

    private ImagePlus mipImp;
    private ImageCanvas mipCanvas;
    private boolean mipAttempted;
    private boolean mipMode;
    private TileActionListener actionListener;
    private Runnable eliminateAction;
    private boolean allowPromoteSaveActions;
    private boolean allowEliminateAction;
    private boolean appliedToBuilder;

    public TilePanel(ImagePlus imp, String caption, boolean isRaw) {
        this(imp, caption, isRaw, null);
    }

    public TilePanel(ImagePlus imp, String caption, boolean isRaw, VariantPlan plan) {
        super(new BorderLayout());
        if (imp == null) throw new IllegalArgumentException("imp must not be null");
        scrubImp = imp;
        scrubCanvas = new ImageCanvas(imp);
        label = caption == null ? "" : caption;
        rawTile = isRaw;
        errorTile = false;
        this.plan = plan;

        captionLabel = new JLabel(label, SwingConstants.LEFT);
        captionLabel.setFont(captionLabel.getFont().deriveFont(Font.BOLD, 12f));
        captionLabel.setForeground(isRaw ? RAW_CAPTION : NORMAL_CAPTION);
        captionLabel.setBorder(BorderFactory.createEmptyBorder(2, 6, 2, 6));

        actionPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 2, 0));
        actionPanel.setOpaque(false);

        JPanel north = new JPanel(new BorderLayout());
        north.setOpaque(false);
        north.add(captionLabel, BorderLayout.CENTER);
        north.add(actionPanel, BorderLayout.EAST);

        centre = new JPanel(new BorderLayout());
        centre.setBackground(Color.BLACK);
        centre.add(scrubCanvas, BorderLayout.CENTER);

        add(north, BorderLayout.NORTH);
        add(centre, BorderLayout.CENTER);
        setBorder(BorderFactory.createLineBorder(NORMAL_BORDER, 1));
        setBackground(Color.WHITE);
        installFocusRequest();
    }

    public static TilePanel forError(String caption, Throwable error) {
        return new TilePanel(caption, error, null);
    }

    public static TilePanel forError(String caption, Throwable error, VariantPlan plan) {
        return new TilePanel(caption, error, plan);
    }

    private TilePanel(String caption, Throwable error, VariantPlan plan) {
        super(new BorderLayout());
        scrubImp = null;
        scrubCanvas = null;
        label = caption == null ? "(error)" : caption;
        rawTile = false;
        errorTile = true;
        this.plan = plan;

        captionLabel = new JLabel(label, SwingConstants.LEFT);
        captionLabel.setFont(captionLabel.getFont().deriveFont(Font.BOLD, 12f));
        captionLabel.setForeground(ERROR_CAPTION);
        captionLabel.setBorder(BorderFactory.createEmptyBorder(2, 6, 2, 6));
        actionPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 2, 0));
        actionPanel.setOpaque(false);

        String message = error == null ? "unknown error" : String.valueOf(error.getMessage());
        if (message == null || message.isEmpty()) {
            message = error == null ? "unknown error" : error.getClass().getSimpleName();
        }
        JLabel body = new JLabel("<html><body style='width:160px'>"
                + escapeHtml(message)
                + "</body></html>");
        body.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        body.setForeground(new Color(0x66, 0x00, 0x00));

        centre = new JPanel(new BorderLayout());
        centre.setBackground(new Color(0xff, 0xee, 0xee));
        centre.add(body, BorderLayout.CENTER);

        JPanel north = new JPanel(new BorderLayout());
        north.setOpaque(false);
        north.add(captionLabel, BorderLayout.CENTER);
        north.add(actionPanel, BorderLayout.EAST);

        add(north, BorderLayout.NORTH);
        add(centre, BorderLayout.CENTER);
        setBorder(BorderFactory.createLineBorder(ERROR_BORDER, 2));
        setBackground(new Color(0xff, 0xf3, 0xf3));
        installFocusRequest();
    }

    public boolean hasImage() {
        return scrubImp != null;
    }

    public ImagePlus getScrubImp() {
        return scrubImp;
    }

    public String label() {
        return label;
    }

    public boolean isRawTile() {
        return rawTile;
    }

    public boolean isErrorTile() {
        return errorTile;
    }

    public VariantPlan plan() {
        return plan;
    }

    public ImageCanvas getActiveCanvas() {
        if (mipMode && mipCanvas != null) return mipCanvas;
        return scrubCanvas;
    }

    public ImageProcessor currentSliceProcessor() {
        if (scrubImp == null) return null;
        int slice = Math.max(1, Math.min(scrubImp.getCurrentSlice(), scrubImp.getStackSize()));
        ImageProcessor processor = scrubImp.getStack() == null
                ? scrubImp.getProcessor()
                : scrubImp.getStack().getProcessor(slice);
        return processor == null ? null : processor.duplicate();
    }

    public void setActions(TileActionListener listener, Runnable eliminateAction) {
        actionListener = listener;
        this.eliminateAction = eliminateAction;
        allowPromoteSaveActions = true;
        allowEliminateAction = true;
        rebuildActionButtons();
    }

    public void setCompareActions(TileActionListener listener) {
        actionListener = listener;
        eliminateAction = null;
        allowPromoteSaveActions = true;
        allowEliminateAction = false;
        rebuildActionButtons();
    }

    public void setAppliedToBuilder(boolean applied) {
        if (rawTile || errorTile || plan == null) return;
        appliedToBuilder = applied;
        captionLabel.setText(applied ? "Applied to builder: " + label : label);
        captionLabel.setForeground(applied ? APPLIED_GREEN : NORMAL_CAPTION);
        setBorder(BorderFactory.createLineBorder(
                applied ? APPLIED_GREEN : NORMAL_BORDER,
                applied ? 3 : 1));
        setBackground(applied ? APPLIED_BACKGROUND : Color.WHITE);
        rebuildActionButtons();
    }

    public void setMipMode(boolean on) {
        if (scrubImp == null) return;
        if (on == mipMode) return;
        if (on) {
            ensureMipBuilt();
            if (mipCanvas == null) return;
            centre.removeAll();
            centre.add(mipCanvas, BorderLayout.CENTER);
        } else {
            centre.removeAll();
            centre.add(scrubCanvas, BorderLayout.CENTER);
        }
        mipMode = on;
        centre.revalidate();
        centre.repaint();
    }

    boolean mipModeForTest() {
        return mipMode;
    }

    private void ensureMipBuilt() {
        if (mipAttempted) return;
        mipAttempted = true;
        if (scrubImp == null || scrubImp.getNSlices() <= 1) return;
        try {
            ImagePlus projected = ZProjector.run(scrubImp, "max");
            if (projected == null) return;
            if (scrubImp.getCalibration() != null) {
                projected.setCalibration(scrubImp.getCalibration().copy());
            }
            projected.setDisplayRange(scrubImp.getDisplayRangeMin(), scrubImp.getDisplayRangeMax());
            mipImp = projected;
            mipCanvas = new ImageCanvas(projected);
        } catch (Throwable ignored) {
            mipImp = null;
            mipCanvas = null;
        }
    }

    private void rebuildActionButtons() {
        actionPanel.removeAll();
        if (!rawTile) {
            if (allowPromoteSaveActions && !errorTile && plan != null) {
                if (appliedToBuilder) {
                    actionPanel.add(appliedBadge());
                } else {
                    JButton promote = smallButton("Promote",
                            "Promote this variant into the active filter");
                    promote.addActionListener(e -> {
                        if (actionListener != null) actionListener.onPromote(plan);
                        setAppliedToBuilder(true);
                    });
                    actionPanel.add(promote);
                }
                JButton save = smallButton("Save", "Save this variant as a preset");
                save.addActionListener(e -> {
                    if (actionListener != null) actionListener.onSavePreset(plan);
                });
                actionPanel.add(save);
            }
            if (allowEliminateAction && eliminateAction != null) {
                JButton eliminate = smallButton("X", "Eliminate this tile from view");
                eliminate.addActionListener(e -> eliminateAction.run());
                actionPanel.add(eliminate);
            }
        }
        actionPanel.revalidate();
        actionPanel.repaint();
    }

    private static JButton smallButton(String text, String tooltip) {
        JButton button = new JButton(text);
        button.setToolTipText(tooltip);
        button.setFocusable(false);
        button.setMargin(new java.awt.Insets(1, 4, 1, 4));
        button.setFont(button.getFont().deriveFont(10f));
        return button;
    }

    private static JLabel appliedBadge() {
        JLabel badge = new JLabel("Applied");
        badge.setOpaque(true);
        badge.setForeground(Color.WHITE);
        badge.setBackground(APPLIED_GREEN);
        badge.setFont(badge.getFont().deriveFont(Font.BOLD, 10f));
        badge.setBorder(BorderFactory.createEmptyBorder(2, 5, 2, 5));
        badge.setToolTipText("This variant has been applied to the active filter");
        return badge;
    }

    private void installFocusRequest() {
        setFocusable(true);
        MouseAdapter focusOnClick = new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) {
                requestFocusInWindow();
            }
        };
        addMouseListener(focusOnClick);
        if (scrubCanvas != null) scrubCanvas.addMouseListener(focusOnClick);
        centre.addMouseListener(focusOnClick);
    }

    @Override
    public Dimension getPreferredSize() {
        Dimension base = super.getPreferredSize();
        return new Dimension(Math.max(180, base.width), Math.max(180, base.height));
    }

    private static String escapeHtml(String text) {
        StringBuilder builder = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '&': builder.append("&amp;"); break;
                case '<': builder.append("&lt;"); break;
                case '>': builder.append("&gt;"); break;
                case '"': builder.append("&quot;"); break;
                default: builder.append(c);
            }
        }
        return builder.toString();
    }
}
