package flash.pipeline.representative;

import flash.pipeline.presentation.PresentationTileConfig;
import flash.pipeline.ui.ToggleSwitch;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSlider;
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

/**
 * Interactive layout arranger: conditions are labelled squares in a grid.
 * Drag one onto another slot to swap/move it; change the row count to reshape
 * (e.g. 2x2 -> 1x4); and set the uniform row / column / inner / margin spacing
 * with sliders. Optionally renders the real merge thumbnail in each square.
 * Opened from the layout dialog's "Arrange full layout..." button.
 */
public final class FigureLayoutEditor extends JDialog {

    /** Updated layout plus the spacing folded into the tile config. */
    public static final class Result {
        private final RepresentativeLayout layout;
        private final PresentationTileConfig tileConfig;

        Result(RepresentativeLayout layout, PresentationTileConfig tileConfig) {
            this.layout = layout;
            this.tileConfig = tileConfig;
        }

        public RepresentativeLayout layout() {
            return layout;
        }

        public PresentationTileConfig tileConfig() {
            return tileConfig;
        }
    }

    private final RepresentativeSelection selection;
    private final PresentationTileConfig baseConfig;
    private final List<String> allConditions;

    private final GridCanvas canvas;
    private final JLabel gridLabel = new JLabel();
    private String[][] grid;
    private int rowCount;
    private int rowGap;
    private int colGap;
    private int innerGap;
    private int margin;
    private boolean showImages;

    private Result result;

    private FigureLayoutEditor(Window owner,
                               RepresentativeSelection selection,
                               RepresentativeLayout layout,
                               PresentationTileConfig tileConfig) {
        super(owner, "Arrange layout", Dialog.ModalityType.APPLICATION_MODAL);
        this.selection = selection;
        this.baseConfig = tileConfig;
        this.allConditions = layout.flattenedConditions();
        this.rowCount = Math.max(1, layout.rowCount());
        this.grid = distribute(allConditions, rowCount);
        this.rowGap = tileConfig.rowGapPx();
        this.colGap = tileConfig.conditionGapPx();
        this.innerGap = tileConfig.innerColGapPx();
        this.margin = tileConfig.marginPx();
        this.canvas = new GridCanvas();

        buildUi();
        pack();
        setLocationRelativeTo(owner);
    }

    /**
     * Show the arranger and return the new layout + spacing, or {@code null} if
     * cancelled or unavailable (headless / invalid input).
     */
    public static Result arrange(Window owner,
                                 RepresentativeSelection selection,
                                 RepresentativeLayout layout,
                                 PresentationTileConfig tileConfig) {
        if (selection == null || !selection.isComplete() || layout == null
                || tileConfig == null || GraphicsEnvironment.isHeadless()) {
            return null;
        }
        FigureLayoutEditor editor =
                new FigureLayoutEditor(owner, selection, layout, tileConfig);
        editor.setVisible(true);
        return editor.result;
    }

    // ---- pure, testable helpers -------------------------------------------

    /**
     * Flattened conditions balanced across {@code rowCount} rows (the first
     * {@code total % rows} rows get one extra). Every one of the {@code rows}
     * rows is non-empty when {@code rows <= total}, so requesting N rows yields
     * exactly N rows after {@link #fromGrid} drops trailing empties.
     */
    static String[][] distribute(List<String> conditions, int rowCount) {
        int total = conditions.size();
        int rows = Math.max(1, Math.min(rowCount, Math.max(1, total)));
        int base = total / rows;
        int remainder = total % rows;
        int cols = Math.max(1, base + (remainder > 0 ? 1 : 0));
        String[][] g = new String[rows][cols];
        int i = 0;
        for (int r = 0; r < rows; r++) {
            int size = base + (r < remainder ? 1 : 0);
            for (int c = 0; c < size && i < total; c++) {
                g[r][c] = conditions.get(i++);
            }
        }
        return g;
    }

    /** Non-null cells in row-major order. */
    static List<String> flatten(String[][] g) {
        List<String> out = new ArrayList<String>();
        for (String[] row : g) {
            for (String cell : row) {
                if (cell != null && !cell.isEmpty()) {
                    out.add(cell);
                }
            }
        }
        return out;
    }

    /** Rows of non-empty cells; empty rows dropped. */
    static List<List<String>> fromGrid(String[][] g) {
        List<List<String>> rows = new ArrayList<List<String>>();
        for (String[] row : g) {
            List<String> out = new ArrayList<String>();
            for (String cell : row) {
                if (cell != null && !cell.isEmpty()) {
                    out.add(cell);
                }
            }
            if (!out.isEmpty()) {
                rows.add(out);
            }
        }
        return rows;
    }

    static void swapCells(String[][] g, int r1, int c1, int r2, int c2) {
        if (!inBounds(g, r1, c1) || !inBounds(g, r2, c2)) {
            return;
        }
        String tmp = g[r1][c1];
        g[r1][c1] = g[r2][c2];
        g[r2][c2] = tmp;
    }

    static Rectangle[][] cellRects(int rowCount, int cols, int square,
                                   int colGap, int rowGap, int margin,
                                   int originX, int originY) {
        Rectangle[][] rects = new Rectangle[rowCount][cols];
        for (int r = 0; r < rowCount; r++) {
            int y = originY + margin + r * (square + rowGap);
            for (int c = 0; c < cols; c++) {
                int x = originX + margin + c * (square + colGap);
                rects[r][c] = new Rectangle(x, y, square, square);
            }
        }
        return rects;
    }

    /** {row, col} of the cell containing {@code p}, or {@code null}. */
    static int[] cellAt(Point p, Rectangle[][] rects) {
        if (p == null || rects == null) {
            return null;
        }
        for (int r = 0; r < rects.length; r++) {
            for (int c = 0; c < rects[r].length; c++) {
                if (rects[r][c] != null && rects[r][c].contains(p)) {
                    return new int[]{r, c};
                }
            }
        }
        return null;
    }

    private static boolean inBounds(String[][] g, int r, int c) {
        return g != null && r >= 0 && r < g.length && c >= 0 && c < g[r].length;
    }

    // ---- UI ----------------------------------------------------------------

    private void buildUi() {
        JPanel root = new JPanel(new BorderLayout(10, 10));
        root.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        canvas.setPreferredSize(new Dimension(520, 420));
        canvas.setBackground(new Color(245, 245, 245));
        canvas.setBorder(BorderFactory.createLineBorder(new Color(210, 210, 210)));
        root.add(canvas, BorderLayout.CENTER);
        root.add(buildSidePanel(), BorderLayout.EAST);

        JLabel hint = new JLabel("Drag a condition onto another slot to swap it. "
                + "Spacing changes apply uniformly.");
        hint.setFont(hint.getFont().deriveFont(Font.PLAIN, 11f));
        hint.setForeground(new Color(90, 90, 90));

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
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        buttons.add(cancel);
        buttons.add(ok);

        JPanel south = new JPanel(new BorderLayout());
        south.add(hint, BorderLayout.WEST);
        south.add(buttons, BorderLayout.EAST);
        root.add(south, BorderLayout.SOUTH);
        setContentPane(root);
        updateGridLabel();
    }

    private JPanel buildSidePanel() {
        JPanel side = new JPanel();
        side.setLayout(new BoxLayout(side, BoxLayout.Y_AXIS));
        side.setBorder(BorderFactory.createEmptyBorder(0, 4, 0, 0));

        side.add(section("Rows"));
        JPanel rowButtons = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        JButton addRow = new JButton("+ row");
        JButton removeRow = new JButton("- row");
        rowButtons.add(addRow);
        rowButtons.add(removeRow);
        rowButtons.setAlignmentX(Component.LEFT_ALIGNMENT);
        side.add(rowButtons);
        gridLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        side.add(gridLabel);

        side.add(Box.createVerticalStrut(8));
        side.add(section("Spacing (uniform)"));
        side.add(slider("Row gap", rowGap, value -> {
            rowGap = value;
            canvas.repaint();
        }));
        side.add(slider("Column gap", colGap, value -> {
            colGap = value;
            canvas.repaint();
        }));
        side.add(slider("Inner gap", innerGap, value -> {
            innerGap = value;
            canvas.repaint();
        }));
        side.add(slider("Margin", margin, value -> {
            margin = value;
            canvas.repaint();
        }));

        side.add(Box.createVerticalStrut(8));
        JPanel imagesRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        imagesRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        ToggleSwitch imagesToggle = new ToggleSwitch(showImages);
        imagesToggle.addChangeListener(() -> {
            showImages = imagesToggle.isSelected();
            canvas.repaint();
        });
        imagesRow.add(imagesToggle);
        imagesRow.add(new JLabel("Show real images"));
        side.add(imagesRow);

        JButton reset = new JButton("Reset");
        reset.setAlignmentX(Component.LEFT_ALIGNMENT);
        reset.addActionListener(e -> {
            rowCount = Math.max(1, baseConfig == null ? 1 : rowCount);
            grid = distribute(allConditions, rowCount);
            rowGap = baseConfig.rowGapPx();
            colGap = baseConfig.conditionGapPx();
            innerGap = baseConfig.innerColGapPx();
            margin = baseConfig.marginPx();
            updateGridLabel();
            canvas.repaint();
        });
        side.add(Box.createVerticalStrut(10));
        side.add(reset);
        side.add(Box.createVerticalGlue());

        addRow.addActionListener(e -> changeRowCount(1));
        removeRow.addActionListener(e -> changeRowCount(-1));
        return side;
    }

    private void changeRowCount(int delta) {
        int max = Math.max(1, allConditions.size());
        int next = Math.max(1, Math.min(max, rowCount + delta));
        if (next == rowCount) {
            return;
        }
        rowCount = next;
        grid = distribute(flatten(grid), rowCount);
        updateGridLabel();
        canvas.repaint();
    }

    private void updateGridLabel() {
        int cols = grid.length == 0 ? 0 : grid[0].length;
        gridLabel.setText(rowCount + " row" + (rowCount == 1 ? "" : "s")
                + " x up to " + cols + " column" + (cols == 1 ? "" : "s"));
    }

    private Result buildResult() {
        List<List<String>> rows = fromGrid(grid);
        RepresentativeLayout layout;
        try {
            layout = new RepresentativeLayout(rows);
        } catch (RuntimeException e) {
            layout = RepresentativeLayout.allInOneRow(allConditions);
        }
        if (!layout.containsExactlyConditions(allConditions)) {
            layout = RepresentativeLayout.allInOneRow(allConditions);
        }
        PresentationTileConfig tile = baseConfig.toBuilder()
                .rowGapPx(rowGap)
                .conditionGapPx(colGap)
                .innerColGapPx(innerGap)
                .marginPx(margin)
                .build();
        return new Result(layout, tile);
    }

    private BufferedImage mergeImageFor(String condition) {
        RepresentativeSeries series = selection.seriesForCondition(condition);
        return series == null ? null : series.mergeThumbnail();
    }

    private final class GridCanvas extends JPanel {

        private static final int BASE_SQUARE = 72;

        private Rectangle[][] rects;
        private double fitScale = 1.0;
        private int offsetX;
        private int offsetY;

        private int[] dragFrom;
        private Point dragPoint;

        GridCanvas() {
            MouseAdapter mouse = new MouseAdapter() {
                @Override public void mousePressed(MouseEvent e) {
                    int[] cell = cellAt(e.getPoint(), rects);
                    if (cell != null && grid[cell[0]][cell[1]] != null) {
                        dragFrom = cell;
                        dragPoint = e.getPoint();
                        repaint();
                    }
                }

                @Override public void mouseDragged(MouseEvent e) {
                    if (dragFrom != null) {
                        dragPoint = e.getPoint();
                        repaint();
                    }
                }

                @Override public void mouseReleased(MouseEvent e) {
                    if (dragFrom != null) {
                        int[] target = cellAt(e.getPoint(), rects);
                        if (target != null
                                && (target[0] != dragFrom[0] || target[1] != dragFrom[1])) {
                            swapCells(grid, dragFrom[0], dragFrom[1], target[0], target[1]);
                        }
                        dragFrom = null;
                        dragPoint = null;
                        repaint();
                    }
                }
            };
            addMouseListener(mouse);
            addMouseMotionListener(mouse);
        }

        @Override protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            int cols = grid.length == 0 ? 0 : grid[0].length;
            Rectangle[][] natural = cellRects(grid.length, cols, BASE_SQUARE,
                    colGap, rowGap, margin, 0, 0);
            int naturalW = margin * 2 + cols * BASE_SQUARE + Math.max(0, cols - 1) * colGap;
            int naturalH = margin * 2 + grid.length * BASE_SQUARE
                    + Math.max(0, grid.length - 1) * rowGap;
            fitScale = Math.min(1.0, Math.min(
                    (getWidth() - 24) / (double) Math.max(1, naturalW),
                    (getHeight() - 24) / (double) Math.max(1, naturalH)));
            int scaledW = (int) Math.round(naturalW * fitScale);
            int scaledH = (int) Math.round(naturalH * fitScale);
            offsetX = (getWidth() - scaledW) / 2;
            offsetY = (getHeight() - scaledH) / 2;
            rects = scaleRects(natural, fitScale, offsetX, offsetY);

            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                        RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                        RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                for (int r = 0; r < grid.length; r++) {
                    for (int c = 0; c < cols; c++) {
                        String condition = grid[r][c];
                        if (condition == null) {
                            continue;
                        }
                        if (dragFrom != null && dragFrom[0] == r && dragFrom[1] == c) {
                            continue;
                        }
                        drawCell(g2, rects[r][c], condition);
                    }
                }
                if (dragFrom != null && dragPoint != null) {
                    String condition = grid[dragFrom[0]][dragFrom[1]];
                    Rectangle src = rects[dragFrom[0]][dragFrom[1]];
                    Rectangle ghost = new Rectangle(
                            dragPoint.x - src.width / 2, dragPoint.y - src.height / 2,
                            src.width, src.height);
                    drawCell(g2, ghost, condition);
                }
            } finally {
                g2.dispose();
            }
        }

        private Rectangle[][] scaleRects(Rectangle[][] natural, double scale,
                                         int ox, int oy) {
            Rectangle[][] scaled = new Rectangle[natural.length][];
            for (int r = 0; r < natural.length; r++) {
                scaled[r] = new Rectangle[natural[r].length];
                for (int c = 0; c < natural[r].length; c++) {
                    Rectangle n = natural[r][c];
                    scaled[r][c] = new Rectangle(
                            ox + (int) Math.round(n.x * scale),
                            oy + (int) Math.round(n.y * scale),
                            Math.max(1, (int) Math.round(n.width * scale)),
                            Math.max(1, (int) Math.round(n.height * scale)));
                }
            }
            return scaled;
        }

        private void drawCell(Graphics2D g2, Rectangle box, String condition) {
            BufferedImage image = showImages ? mergeImageFor(condition) : null;
            if (image != null) {
                g2.drawImage(image, box.x, box.y, box.width, box.height, null);
                g2.setColor(new Color(0, 0, 0, 120));
                g2.fillRect(box.x, box.y + box.height - 18, box.width, 18);
            } else {
                g2.setColor(new Color(225, 230, 236));
                g2.fillRect(box.x, box.y, box.width, box.height);
            }
            g2.setColor(new Color(120, 130, 140));
            g2.drawRect(box.x, box.y, box.width, box.height);

            g2.setColor(image != null ? Color.WHITE : new Color(40, 50, 60));
            g2.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12));
            FontMetrics fm = g2.getFontMetrics();
            String text = fit(condition, fm, box.width - 6);
            int tx = box.x + Math.max(2, (box.width - fm.stringWidth(text)) / 2);
            int ty = image != null
                    ? box.y + box.height - 5
                    : box.y + box.height / 2 + fm.getAscent() / 2;
            g2.drawString(text, tx, ty);
        }

        private String fit(String text, FontMetrics fm, int width) {
            if (text == null) {
                return "";
            }
            if (fm.stringWidth(text) <= width || text.length() <= 1) {
                return text;
            }
            String shortened = text;
            while (shortened.length() > 1 && fm.stringWidth(shortened + "...") > width) {
                shortened = shortened.substring(0, shortened.length() - 1);
            }
            return shortened + "...";
        }
    }

    private interface IntConsumer {
        void accept(int value);
    }

    private JPanel slider(String label, int value, IntConsumer consumer) {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 1));
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        JLabel name = new JLabel(label);
        name.setPreferredSize(new Dimension(78, 18));
        final JLabel valueLabel = new JLabel(String.valueOf(value));
        // Expand the max so a project saved with a large gap (config allows up to
        // 400) shows the true value instead of pinning the thumb at 60 and
        // overwriting it on the first drag.
        int sliderMax = Math.max(60, Math.max(0, value));
        JSlider slider = new JSlider(0, sliderMax, Math.max(0, value));
        slider.setPreferredSize(new Dimension(120, 22));
        slider.addChangeListener(e -> {
            int v = slider.getValue();
            valueLabel.setText(String.valueOf(v));
            consumer.accept(v);
        });
        row.add(name);
        row.add(slider);
        row.add(valueLabel);
        return row;
    }

    private static JLabel section(String text) {
        JLabel label = new JLabel(text);
        label.setFont(label.getFont().deriveFont(Font.BOLD, 12f));
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        return label;
    }
}
