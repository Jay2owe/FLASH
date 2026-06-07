package flash.pipeline.representative;

import flash.pipeline.presentation.PresentationTileConfig;
import flash.pipeline.ui.ToggleSwitch;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dialog;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Window;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Interactive single-tile editor: drag the label or scale bar to move it, drag
 * a corner handle to resize (label font / bar thickness). Placement is stored
 * as a fraction of the tile so one adjustment applies uniformly to every tile.
 * Opened from the layout dialog's "Preview / adjust tile..." button.
 */
public final class TileAnnotationEditor extends JDialog {

    /** What a mouse press grabbed on the canvas. */
    public enum Grab {
        NONE,
        LABEL_BODY,
        LABEL_RESIZE,
        BAR_BODY,
        BAR_RESIZE
    }

    private static final double SNAP_THRESHOLD = 0.06;
    private static final int HANDLE = 12;

    private final RepresentativeSelection selection;
    private final PresentationTileConfig baseConfig;
    private final int cellSizePx;
    private final boolean labelEnabled;
    private final boolean barEnabled;

    private final EditorCanvas canvas;
    private final JComboBox<String> conditionBox;
    private final JComboBox<String> channelBox;
    private final JTextField labelFontField;
    private final JTextField barUmField;
    private final JTextField barThickField;
    private final JComboBox<String> colorBox;
    private final ToggleSwitch snapToggle;

    private double labelFracX;
    private double labelFracY;
    private int labelFontSizePx;
    private double barFracX;
    private double barFracY;
    private int barThicknessPx;
    private double barLengthUm;
    private Color annotationColor;
    private boolean snapEnabled = true;
    private boolean syncing;

    private PresentationTileConfig result;

    private TileAnnotationEditor(Window owner,
                                 RepresentativeSelection selection,
                                 PresentationTileConfig tileConfig) {
        super(owner, "Preview & adjust tile", Dialog.ModalityType.APPLICATION_MODAL);
        this.selection = selection;
        this.baseConfig = tileConfig;
        this.cellSizePx = Math.max(1, tileConfig.cellSizePx());
        this.labelEnabled = tileConfig.labelMode() != PresentationTileConfig.LabelMode.NONE;
        this.barEnabled = tileConfig.scaleBarEnabled();

        initWorkingState(tileConfig);

        this.canvas = new EditorCanvas();
        this.conditionBox = new JComboBox<String>(
                selection.conditionNames().toArray(new String[0]));
        this.channelBox = new JComboBox<String>(outputNames().toArray(new String[0]));
        this.channelBox.setSelectedItem("Merge");
        this.labelFontField = field(String.valueOf(labelFontSizePx));
        this.barUmField = field(trimNumber(barLengthUm));
        this.barThickField = field(String.valueOf(barThicknessPx));
        this.colorBox = new JComboBox<String>(new String[]{"White", "Black"});
        this.colorBox.setSelectedItem(Color.BLACK.equals(annotationColor) ? "Black" : "White");
        this.snapToggle = new ToggleSwitch(snapEnabled);

        buildUi();
        pack();
        setLocationRelativeTo(owner);
    }

    /**
     * Show the editor and return the updated config, or {@code null} if
     * cancelled. In headless mode returns the input config unchanged.
     */
    public static PresentationTileConfig edit(Window owner,
                                              RepresentativeSelection selection,
                                              PresentationTileConfig tileConfig) {
        if (tileConfig == null || selection == null || !selection.isComplete()
                || GraphicsEnvironment.isHeadless()) {
            return tileConfig;
        }
        TileAnnotationEditor editor = new TileAnnotationEditor(owner, selection, tileConfig);
        editor.setVisible(true);
        return editor.result;
    }

    /** Hit priority: handles before bodies, label before bar. Null rects skipped. */
    public static Grab pickTarget(Point p,
                                  Rectangle labelBox,
                                  Rectangle labelHandle,
                                  Rectangle barBox,
                                  Rectangle barHandle) {
        if (p == null) {
            return Grab.NONE;
        }
        if (labelHandle != null && labelHandle.contains(p)) {
            return Grab.LABEL_RESIZE;
        }
        if (barHandle != null && barHandle.contains(p)) {
            return Grab.BAR_RESIZE;
        }
        if (labelBox != null && labelBox.contains(p)) {
            return Grab.LABEL_BODY;
        }
        if (barBox != null && barBox.contains(p)) {
            return Grab.BAR_BODY;
        }
        return Grab.NONE;
    }

    private void initWorkingState(PresentationTileConfig config) {
        if (config.hasLabelFraction()) {
            labelFracX = config.labelFracX();
            labelFracY = config.labelFracY();
        } else {
            double[] corner = AnnotationPlacement.cornerFraction(config.labelPosition());
            labelFracX = corner[0];
            labelFracY = corner[1];
        }
        if (config.hasScaleBarFraction()) {
            barFracX = config.scaleBarFracX();
            barFracY = config.scaleBarFracY();
        } else {
            double[] corner = AnnotationPlacement.cornerFraction(config.scaleBarPosition());
            barFracX = corner[0];
            barFracY = corner[1];
        }
        labelFontSizePx = config.labelFontSizePx();
        barThicknessPx = config.scaleBarThicknessPx();
        barLengthUm = config.scaleBarLengthUm();
        annotationColor = config.annotationColor();
    }

    private void buildUi() {
        JPanel root = new JPanel(new BorderLayout(10, 10));
        root.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        canvas.setPreferredSize(new Dimension(520, 420));
        canvas.setBackground(new Color(40, 40, 40));
        canvas.setBorder(BorderFactory.createLineBorder(new Color(85, 91, 96)));
        root.add(canvas, BorderLayout.CENTER);
        root.add(buildSidePanel(), BorderLayout.EAST);

        JLabel hint = new JLabel("Drag to move, drag a corner handle to resize. "
                + "Changes apply uniformly to every tile.");
        hint.setFont(hint.getFont().deriveFont(Font.PLAIN, 11f));
        hint.setForeground(new Color(90, 90, 90));

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        JButton cancel = new JButton("Cancel");
        cancel.addActionListener(e -> {
            result = null;
            dispose();
        });
        JButton ok = new JButton("OK");
        ok.addActionListener(e -> {
            result = buildResult();
            dispose();
        });
        buttons.add(cancel);
        buttons.add(ok);

        JPanel south = new JPanel(new BorderLayout());
        south.add(hint, BorderLayout.WEST);
        south.add(buttons, BorderLayout.EAST);
        root.add(south, BorderLayout.SOUTH);
        setContentPane(root);
    }

    private JPanel buildSidePanel() {
        JPanel side = new JPanel();
        side.setLayout(new BoxLayout(side, BoxLayout.Y_AXIS));
        side.setBorder(BorderFactory.createEmptyBorder(0, 4, 0, 0));

        side.add(labelledRow("Condition", conditionBox));
        side.add(labelledRow("Channel", channelBox));
        side.add(Box.createVerticalStrut(8));
        side.add(sectionLabel("Label"));
        side.add(labelledRow("Font px", labelFontField));
        side.add(labelledRow("Colour", colorBox));
        side.add(Box.createVerticalStrut(8));
        side.add(sectionLabel("Scale bar"));
        side.add(labelledRow("Length um", barUmField));
        side.add(labelledRow("Thickness", barThickField));
        side.add(Box.createVerticalStrut(8));
        side.add(labelledRow("Snap to edges", snapToggle));

        JButton reset = new JButton("Reset");
        reset.addActionListener(e -> {
            initWorkingState(baseConfig);
            snapEnabled = true;
            syncFieldsFromState();
            canvas.repaint();
        });
        side.add(Box.createVerticalStrut(10));
        side.add(reset);
        side.add(Box.createVerticalGlue());

        conditionBox.addActionListener(e -> canvas.repaint());
        channelBox.addActionListener(e -> canvas.repaint());
        colorBox.addActionListener(e -> {
            if (syncing) {
                return;
            }
            annotationColor = "Black".equals(colorBox.getSelectedItem()) ? Color.BLACK : Color.WHITE;
            canvas.repaint();
        });
        snapToggle.addChangeListener(() -> snapEnabled = snapToggle.isSelected());
        onNumber(labelFontField, value -> {
            labelFontSizePx = clamp(value, 8, 96);
            canvas.repaint();
        });
        onNumber(barThickField, value -> {
            barThicknessPx = clamp(value, 1, 30);
            canvas.repaint();
        });
        onDecimal(barUmField, value -> {
            barLengthUm = value > 0 ? value : barLengthUm;
            canvas.repaint();
        });
        return side;
    }

    private PresentationTileConfig buildResult() {
        return baseConfig.toBuilder()
                .labelFracX(labelFracX)
                .labelFracY(labelFracY)
                .labelFontSizePx(labelFontSizePx)
                .scaleBarFracX(barFracX)
                .scaleBarFracY(barFracY)
                .scaleBarThicknessPx(barThicknessPx)
                .scaleBarLengthUm(barLengthUm)
                .annotationColor(annotationColor)
                .build();
    }

    private void syncFieldsFromState() {
        syncing = true;
        try {
            labelFontField.setText(String.valueOf(labelFontSizePx));
            barThickField.setText(String.valueOf(barThicknessPx));
            barUmField.setText(trimNumber(barLengthUm));
            colorBox.setSelectedItem(Color.BLACK.equals(annotationColor) ? "Black" : "White");
            snapToggle.setSelected(snapEnabled);
        } finally {
            syncing = false;
        }
    }

    private List<String> outputNames() {
        List<String> names = new ArrayList<String>();
        RepresentativeSeries first = selection.series().isEmpty()
                ? null : selection.series().get(0);
        if (first != null) {
            for (RepresentativeSeries.ChannelThumbnail thumbnail : first.channelThumbnails()) {
                if (thumbnail != null && !thumbnail.channelName().isEmpty()) {
                    names.add(thumbnail.channelName());
                }
            }
        }
        names.add("Merge");
        return names;
    }

    private BufferedImage currentTileImage() {
        String condition = String.valueOf(conditionBox.getSelectedItem());
        String output = String.valueOf(channelBox.getSelectedItem());
        RepresentativeSeries series = selection.seriesForCondition(condition);
        if (series == null) {
            return null;
        }
        if ("Merge".equals(output)) {
            return series.mergeThumbnail();
        }
        for (RepresentativeSeries.ChannelThumbnail thumbnail : series.channelThumbnails()) {
            if (thumbnail != null && output.equals(thumbnail.channelName())) {
                return thumbnail.image();
            }
        }
        return series.mergeThumbnail();
    }

    private String currentLabelText() {
        String condition = String.valueOf(conditionBox.getSelectedItem());
        String output = String.valueOf(channelBox.getSelectedItem());
        RepresentativeSeries series = selection.seriesForCondition(condition);
        String image = series == null ? "" : series.seriesName();
        switch (baseConfig.labelMode()) {
            case IMAGE_NAME:
                return image;
            case CONDITION_IMAGE:
                return (condition + " " + image).trim();
            case CUSTOM:
                return baseConfig.customLabelTemplate()
                        .replace("{stain}", output)
                        .replace("{image}", image)
                        .replace("{condition}", condition);
            case STAIN_NAME:
            default:
                return output;
        }
    }

    private final class EditorCanvas extends JPanel {

        private int drawX;
        private int drawY;
        private int drawW;
        private int drawH;
        private Rectangle labelBox;
        private Rectangle labelHandle;
        private Rectangle barBox;
        private Rectangle barHandle;

        private Grab grab = Grab.NONE;
        private int grabOffsetX;
        private int grabOffsetY;
        private int dragStartY;
        private int dragStartValue;

        EditorCanvas() {
            MouseAdapter mouse = new MouseAdapter() {
                @Override public void mousePressed(MouseEvent e) {
                    onPress(e.getPoint());
                }

                @Override public void mouseDragged(MouseEvent e) {
                    onDrag(e.getPoint());
                }
            };
            addMouseListener(mouse);
            addMouseMotionListener(mouse);
        }

        private void onPress(Point p) {
            grab = pickTarget(p, labelBox, labelHandle, barBox, barHandle);
            if (grab == Grab.LABEL_BODY && labelBox != null) {
                grabOffsetX = p.x - labelBox.x;
                grabOffsetY = p.y - labelBox.y;
            } else if (grab == Grab.BAR_BODY && barBox != null) {
                grabOffsetX = p.x - barBox.x;
                grabOffsetY = p.y - barBox.y;
            } else if (grab == Grab.LABEL_RESIZE) {
                dragStartY = p.y;
                dragStartValue = labelFontSizePx;
            } else if (grab == Grab.BAR_RESIZE) {
                dragStartY = p.y;
                dragStartValue = barThicknessPx;
            }
        }

        private void onDrag(Point p) {
            if (grab == Grab.NONE || drawW <= 0 || drawH <= 0) {
                return;
            }
            double tileToCanvas = drawW / (double) cellSizePx;
            switch (grab) {
                case LABEL_BODY:
                    labelFracX = AnnotationPlacement.clampFraction(
                            (p.x - grabOffsetX - drawX) / (double) drawW);
                    labelFracY = AnnotationPlacement.clampFraction(
                            (p.y - grabOffsetY - drawY) / (double) drawH);
                    maybeSnapLabel();
                    break;
                case BAR_BODY:
                    barFracX = AnnotationPlacement.clampFraction(
                            (p.x - grabOffsetX - drawX) / (double) drawW);
                    barFracY = AnnotationPlacement.clampFraction(
                            (p.y - grabOffsetY - drawY) / (double) drawH);
                    maybeSnapBar();
                    break;
                case LABEL_RESIZE:
                    labelFontSizePx = clamp(dragStartValue
                            + (int) Math.round((p.y - dragStartY) / Math.max(0.5, tileToCanvas)),
                            8, 96);
                    break;
                case BAR_RESIZE:
                    barThicknessPx = clamp(dragStartValue
                            + (int) Math.round((p.y - dragStartY) / Math.max(0.5, tileToCanvas)),
                            1, 30);
                    break;
                default:
                    break;
            }
            syncFieldsFromState();
            repaint();
        }

        private void maybeSnapLabel() {
            if (!snapEnabled) {
                return;
            }
            PresentationTileConfig.Position corner =
                    AnnotationPlacement.snapToNearestCorner(labelFracX, labelFracY, SNAP_THRESHOLD);
            if (corner != null) {
                double[] anchor = AnnotationPlacement.cornerFraction(corner);
                labelFracX = anchor[0];
                labelFracY = anchor[1];
            }
        }

        private void maybeSnapBar() {
            if (!snapEnabled) {
                return;
            }
            PresentationTileConfig.Position corner =
                    AnnotationPlacement.snapToNearestCorner(barFracX, barFracY, SNAP_THRESHOLD);
            if (corner != null) {
                double[] anchor = AnnotationPlacement.cornerFraction(corner);
                barFracX = anchor[0];
                barFracY = anchor[1];
            }
        }

        @Override protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            labelBox = null;
            labelHandle = null;
            barBox = null;
            barHandle = null;

            BufferedImage tile = currentTileImage();
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                        RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                        RenderingHints.VALUE_ANTIALIAS_ON);
                computeImageRect(tile);
                if (tile != null) {
                    g2.drawImage(tile, drawX, drawY, drawW, drawH, null);
                } else {
                    g2.setColor(Color.BLACK);
                    g2.fillRect(drawX, drawY, drawW, drawH);
                }
                double tileToCanvas = drawW / (double) cellSizePx;
                if (labelEnabled) {
                    drawLabel(g2, tileToCanvas);
                }
                if (barEnabled) {
                    drawBar(g2, tileToCanvas);
                }
            } finally {
                g2.dispose();
            }
        }

        private void computeImageRect(BufferedImage tile) {
            int availW = Math.max(1, getWidth() - 24);
            int availH = Math.max(1, getHeight() - 24);
            int iw = tile == null ? cellSizePx : Math.max(1, tile.getWidth());
            int ih = tile == null ? cellSizePx : Math.max(1, tile.getHeight());
            double scale = Math.min(availW / (double) iw, availH / (double) ih);
            drawW = Math.max(1, (int) Math.round(iw * scale));
            drawH = Math.max(1, (int) Math.round(ih * scale));
            drawX = (getWidth() - drawW) / 2;
            drawY = (getHeight() - drawH) / 2;
        }

        private void drawLabel(Graphics2D g2, double tileToCanvas) {
            String text = currentLabelText();
            if (text.isEmpty()) {
                return;
            }
            int fontPx = Math.max(8, (int) Math.round(labelFontSizePx * tileToCanvas));
            g2.setFont(new Font(Font.SANS_SERIF, Font.BOLD, fontPx));
            FontMetrics fm = g2.getFontMetrics();
            int textW = fm.stringWidth(text);
            int textH = fm.getAscent() + fm.getDescent();
            int boxX = clampBox(drawX + (int) Math.round(labelFracX * drawW),
                    drawX, drawX + drawW, textW);
            int boxY = clampBox(drawY + (int) Math.round(labelFracY * drawH),
                    drawY, drawY + drawH, textH);
            labelBox = new Rectangle(boxX, boxY, textW, textH);
            g2.setColor(annotationColor);
            g2.drawString(text, boxX, boxY + fm.getAscent());
            labelHandle = drawHandle(g2, boxX + textW, boxY + textH);
            outline(g2, labelBox);
        }

        private void drawBar(Graphics2D g2, double tileToCanvas) {
            int barLen = Math.max(8, (int) Math.round(0.25 * drawW));
            int thick = Math.max(2, (int) Math.round(barThicknessPx * tileToCanvas));
            int boxX = clampBox(drawX + (int) Math.round(barFracX * drawW),
                    drawX, drawX + drawW, barLen);
            int boxY = clampBox(drawY + (int) Math.round(barFracY * drawH),
                    drawY, drawY + drawH, thick);
            barBox = new Rectangle(boxX, boxY, barLen, thick);
            g2.setColor(annotationColor);
            g2.fillRect(boxX, boxY, barLen, thick);
            String caption = trimNumber(barLengthUm) + " um";
            g2.setFont(new Font(Font.SANS_SERIF, Font.BOLD,
                    Math.max(8, (int) Math.round(labelFontSizePx * 0.78 * tileToCanvas))));
            FontMetrics fm = g2.getFontMetrics();
            int capY = boxY - 3;
            if (boxY + thick / 2 < drawY + drawH / 2) {
                capY = boxY + thick + fm.getAscent() + 2;
            }
            g2.drawString(caption, boxX + Math.max(0, (barLen - fm.stringWidth(caption)) / 2), capY);
            barHandle = drawHandle(g2, boxX + barLen, boxY + thick);
            outline(g2, barBox);
        }

        private Rectangle drawHandle(Graphics2D g2, int cx, int cy) {
            Rectangle handle = new Rectangle(cx - HANDLE / 2, cy - HANDLE / 2, HANDLE, HANDLE);
            g2.setColor(new Color(255, 214, 64));
            g2.fillRect(handle.x, handle.y, handle.width, handle.height);
            g2.setColor(new Color(40, 40, 40));
            g2.drawRect(handle.x, handle.y, handle.width, handle.height);
            return handle;
        }

        private void outline(Graphics2D g2, Rectangle box) {
            g2.setColor(new Color(255, 214, 64, 160));
            g2.setStroke(new BasicStroke(1.0f));
            g2.drawRect(box.x - 2, box.y - 2, box.width + 4, box.height + 4);
        }

        private int clampBox(int pos, int min, int max, int size) {
            int hi = max - size;
            if (hi < min) {
                hi = min;
            }
            return Math.max(min, Math.min(hi, pos));
        }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private interface IntConsumer {
        void accept(int value);
    }

    private interface DoubleConsumer {
        void accept(double value);
    }

    private void onNumber(JTextField field, IntConsumer consumer) {
        field.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            @Override public void insertUpdate(javax.swing.event.DocumentEvent e) {
                fire();
            }

            @Override public void removeUpdate(javax.swing.event.DocumentEvent e) {
                fire();
            }

            @Override public void changedUpdate(javax.swing.event.DocumentEvent e) {
                fire();
            }

            private void fire() {
                if (syncing) {
                    return;
                }
                try {
                    consumer.accept(Integer.parseInt(field.getText().trim()));
                } catch (NumberFormatException ignored) {
                    // keep last good value while the user is typing
                }
            }
        });
    }

    private void onDecimal(JTextField field, DoubleConsumer consumer) {
        field.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            @Override public void insertUpdate(javax.swing.event.DocumentEvent e) {
                fire();
            }

            @Override public void removeUpdate(javax.swing.event.DocumentEvent e) {
                fire();
            }

            @Override public void changedUpdate(javax.swing.event.DocumentEvent e) {
                fire();
            }

            private void fire() {
                if (syncing) {
                    return;
                }
                try {
                    consumer.accept(Double.parseDouble(field.getText().trim()));
                } catch (NumberFormatException ignored) {
                    // keep last good value while the user is typing
                }
            }
        });
    }

    private static JTextField field(String value) {
        JTextField field = new JTextField(value == null ? "" : value, 5);
        field.setMaximumSize(new Dimension(80, 24));
        return field;
    }

    private static JPanel labelledRow(String label, Component component) {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        JLabel text = new JLabel(label);
        text.setPreferredSize(new Dimension(96, 18));
        row.add(text);
        row.add(component);
        return row;
    }

    private static JLabel sectionLabel(String text) {
        JLabel label = new JLabel(text);
        label.setFont(label.getFont().deriveFont(Font.BOLD, 12f));
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        return label;
    }

    private static String trimNumber(double value) {
        if (Math.rint(value) == value && !Double.isInfinite(value)) {
            return String.valueOf((long) value);
        }
        return String.format(Locale.ROOT, "%.1f", value);
    }
}
