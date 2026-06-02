package flash.pipeline.ui.variations;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JSlider;
import javax.swing.JViewport;
import javax.swing.Scrollable;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingConstants;
import javax.swing.JToolBar;
import javax.swing.WindowConstants;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dialog;
import java.awt.Dimension;
import java.awt.GraphicsEnvironment;
import java.awt.GridLayout;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Window;
import java.awt.event.ActionListener;
import java.awt.event.MouseWheelEvent;
import java.awt.event.MouseWheelListener;
import java.util.ArrayList;
import java.util.List;

public final class VariationGridWindow extends JDialog {

    /** Gutter between tiles and the grid's outer margin, in pixels. */
    private static final int CELL_GAP = 2;
    private static final int GRID_BORDER = 2;
    /** Conservative allowances for the window's title bar and side borders so
     * the packed window stays within the monitor without distorting tiles. */
    private static final int TOP_DECORATION = 72;
    private static final int SIDE_DECORATION = 24;
    /** Ctrl+wheel zoom: 1.0 is fit-to-window; the grid can grow up to this. */
    private static final double MAX_ZOOM = 10.0;
    private static final double ZOOM_STEP = 1.15;
    private static final Color CANVAS_BACKGROUND = new Color(0x1E, 0x20, 0x24);

    private final SyncedSliceController controller = new SyncedSliceController();
    private final List<VariationCellPanel> cells =
            new ArrayList<VariationCellPanel>();
    private final String baseTitle;
    private final JToolBar toolBar = new JToolBar();
    private final JCheckBox otsuOverlayCheckBox =
            new JCheckBox("Show Otsu overlay");
    private final JCheckBox downstreamVerdictCheckBox =
            new JCheckBox("Show downstream verdict");
    private final JButton stopDownstreamButton = new JButton("Stop downstream");
    private final JButton saveCacheButton = new JButton("Save variations cache");
    private final JButton pickSelectedButton = new JButton("Pick selected");
    private final ZoomableGrid gridPanel;
    private final JScrollPane gridScroll;
    private final JSlider zSlider = new JSlider(1, 1, 1);
    private final JLabel zSliceLabel = new JLabel(" ");
    private final JLabel statusLabel = new JLabel(" ");
    private final JProgressBar progressBar = new JProgressBar();

    private int completed;
    private int total;
    private int failed;
    private boolean updatingSlider;
    /** Grid preferred size at zoom 1.0 (fit-to-window); the zoom base. */
    private Dimension fitGridSize;
    private double zoom = 1.0;

    public VariationGridWindow(Window owner,
                               String title,
                               List<VariationCellPanel> sourceCells) {
        super(owner, Dialog.ModalityType.MODELESS);
        setTitle(title == null || title.trim().length() == 0
                ? "FLASH variations"
                : title.trim());
        this.baseTitle = getTitle();
        if (sourceCells != null) {
            for (int i = 0; i < sourceCells.size(); i++) {
                VariationCellPanel cell = sourceCells.get(i);
                if (cell != null) {
                    cells.add(cell);
                    controller.register(cell);
                }
            }
        }

        configureToolBar();
        configureSlider();
        configureFooter();
        JPanel south = southPanel();

        Dimension imageSize = imageDimsFromCells();
        Rectangle desktop = GraphicsEnvironment
                .getLocalGraphicsEnvironment()
                .getMaximumWindowBounds();
        int toolBarHeight = toolBar.getPreferredSize().height;
        int southHeight = south.getPreferredSize().height;
        int availW = Math.max(1, desktop.width - SIDE_DECORATION);
        int availH = Math.max(1, desktop.height - toolBarHeight - southHeight
                - TOP_DECORATION);

        int[] dims = imageSize == null
                ? gridDimensions(cells.size())
                : optimalGrid(cells.size(), availW, availH,
                        imageSize.width / (double) imageSize.height,
                        CELL_GAP, GRID_BORDER);
        gridPanel = new ZoomableGrid(new GridLayout(dims[0], dims[1], CELL_GAP, CELL_GAP));
        gridPanel.setBackground(CANVAS_BACKGROUND);
        gridPanel.setBorder(BorderFactory.createEmptyBorder(
                GRID_BORDER, GRID_BORDER, GRID_BORDER, GRID_BORDER));
        for (int i = 0; i < cells.size(); i++) {
            gridPanel.add(cells.get(i));
        }
        padGrid();
        // The grid lives in a scrollable viewport so Ctrl+wheel can zoom the
        // whole collage and the user can pan around it like a light table.
        gridScroll = new JScrollPane(gridPanel,
                ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        gridScroll.setBorder(BorderFactory.createEmptyBorder());
        gridScroll.getViewport().setBackground(CANVAS_BACKGROUND);
        gridScroll.setWheelScrollingEnabled(false);
        gridScroll.getVerticalScrollBar().setUnitIncrement(24);
        gridScroll.getHorizontalScrollBar().setUnitIncrement(24);
        installMouseWheelHandler();

        setLayout(new BorderLayout());
        add(toolBar, BorderLayout.NORTH);
        add(gridScroll, BorderLayout.CENTER);
        add(south, BorderLayout.SOUTH);
        setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);

        total = cells.size();
        setCompletedCount(0, total, 0);
        setSliceMax(controller.maxSlice());
        applySizeAndLocation(desktop, dims[0], dims[1], imageSize,
                toolBarHeight, southHeight, availW, availH);
    }

    public void setSliceMax(int sliceMax) {
        int max = Math.max(1, sliceMax);
        int value = clamp(zSlider.getValue(), 1, max);
        setSliderState(1, max, value);
        zSlider.setEnabled(max > 1);
        controller.setSlice(value);
        refreshStatus();
    }

    public void setCompletedCount(int completed, int total, int failed) {
        this.completed = Math.max(0, completed);
        this.total = Math.max(0, total);
        this.failed = Math.max(0, failed);
        progressBar.setMaximum(Math.max(1, this.total));
        progressBar.setValue(Math.min(this.completed, Math.max(1, this.total)));
        progressBar.setString(progressText());
        setTitle(baseTitle + " \u2014 " + cells.size()
                + " cells, " + this.completed + " complete");
        refreshStatus();
    }

    public void attachOtsuOverlayActionListener(ActionListener listener) {
        otsuOverlayCheckBox.addActionListener(listener);
    }

    public void attachDownstreamVerdictActionListener(ActionListener listener) {
        downstreamVerdictCheckBox.addActionListener(listener);
    }

    public void attachStopDownstreamActionListener(ActionListener listener) {
        stopDownstreamButton.addActionListener(listener);
    }

    public void attachSaveCacheActionListener(ActionListener listener) {
        saveCacheButton.addActionListener(listener);
    }

    public void attachPickSelectedActionListener(ActionListener listener) {
        pickSelectedButton.addActionListener(listener);
    }

    /** Shows a one-off message in the footer (e.g. the save-cache outcome). */
    public void setActionStatus(String text) {
        statusLabel.setText(text == null ? " " : text);
    }

    public void setDownstreamControlsEnabled(boolean checkBoxEnabled,
                                             boolean stopEnabled,
                                             String checkBoxTooltip) {
        downstreamVerdictCheckBox.setEnabled(checkBoxEnabled);
        stopDownstreamButton.setEnabled(stopEnabled);
        downstreamVerdictCheckBox.setToolTipText(checkBoxTooltip);
    }

    public void setPickSelectedEnabled(boolean enabled) {
        pickSelectedButton.setEnabled(enabled);
    }

    public void setOtsuOverlaySelected(boolean selected) {
        otsuOverlayCheckBox.setSelected(selected);
    }

    public void setDownstreamVerdictSelected(boolean selected) {
        downstreamVerdictCheckBox.setSelected(selected);
    }

    public JToolBar toolBarForTest() {
        return toolBar;
    }

    public JCheckBox otsuOverlayCheckBoxForTest() {
        return otsuOverlayCheckBox;
    }

    public JCheckBox downstreamVerdictCheckBoxForTest() {
        return downstreamVerdictCheckBox;
    }

    public JButton stopDownstreamButtonForTest() {
        return stopDownstreamButton;
    }

    public JButton saveCacheButtonForTest() {
        return saveCacheButton;
    }

    public JButton pickSelectedButtonForTest() {
        return pickSelectedButton;
    }

    public JSlider zSliderForTest() {
        return zSlider;
    }

    public JLabel zSliceLabelForTest() {
        return zSliceLabel;
    }

    public JProgressBar progressBarForTest() {
        return progressBar;
    }

    public JLabel statusLabelForTest() {
        return statusLabel;
    }

    public JPanel gridPanelForTest() {
        return gridPanel;
    }

    public JScrollPane gridScrollForTest() {
        return gridScroll;
    }

    public double zoomForTest() {
        return zoom;
    }

    public List<VariationCellPanel> cellsForTest() {
        return new ArrayList<VariationCellPanel>(cells);
    }

    public SyncedSliceController controllerForTest() {
        return controller;
    }

    private void configureToolBar() {
        toolBar.setFloatable(false);
        otsuOverlayCheckBox.setOpaque(false);
        downstreamVerdictCheckBox.setOpaque(false);
        stopDownstreamButton.setEnabled(false);
        saveCacheButton.setToolTipText(
                "Variations are not written to disk by default. Click to save the "
                + "current variations to the disk cache so a later run can reuse "
                + "them.");
        pickSelectedButton.setEnabled(false);
        pickSelectedButton.setToolTipText(
                "Use the currently selected variation as the result.");
        toolBar.add(otsuOverlayCheckBox);
        toolBar.addSeparator();
        toolBar.add(downstreamVerdictCheckBox);
        toolBar.add(stopDownstreamButton);
        toolBar.addSeparator();
        toolBar.add(saveCacheButton);
        toolBar.addSeparator();
        toolBar.add(pickSelectedButton);
    }

    private void configureSlider() {
        zSlider.addChangeListener(new ChangeListener() {
            @Override public void stateChanged(ChangeEvent e) {
                if (updatingSlider) {
                    return;
                }
                controller.setSlice(zSlider.getValue());
                refreshStatus();
            }
        });
    }

    private void configureFooter() {
        statusLabel.setBorder(BorderFactory.createEmptyBorder(2, 8, 2, 8));
        progressBar.setStringPainted(true);
        progressBar.setMinimum(0);
    }

    private JPanel southPanel() {
        JPanel south = new JPanel(new BorderLayout());
        south.add(zRowPanel(), BorderLayout.NORTH);
        south.add(footerPanel(), BorderLayout.CENTER);
        return south;
    }

    private JPanel zRowPanel() {
        JPanel zRow = new JPanel(new BorderLayout(6, 0));
        zRow.setOpaque(false);
        zSliceLabel.setHorizontalAlignment(SwingConstants.RIGHT);
        zRow.add(new JLabel("Z:"), BorderLayout.WEST);
        zRow.add(zSlider, BorderLayout.CENTER);
        zRow.add(zSliceLabel, BorderLayout.EAST);
        return zRow;
    }

    private JPanel footerPanel() {
        JPanel footer = new JPanel(new BorderLayout(8, 0));
        footer.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0,
                new Color(0xC0, 0xC0, 0xC0)));
        footer.add(statusLabel, BorderLayout.CENTER);
        footer.add(progressBar, BorderLayout.EAST);
        return footer;
    }

    private void installMouseWheelHandler() {
        gridPanel.addMouseWheelListener(new MouseWheelListener() {
            @Override public void mouseWheelMoved(MouseWheelEvent e) {
                if (e.isControlDown()) {
                    // Ctrl+wheel zooms the whole collage around the cursor.
                    double factor = Math.pow(ZOOM_STEP, -e.getWheelRotation());
                    zoomBy(factor, e.getPoint());
                    return;
                }
                if (zoom > 1.0) {
                    // Zoomed in: the wheel pans the light table instead.
                    panBy(e.getWheelRotation(), e.isShiftDown());
                    return;
                }
                // Fit-to-window: the wheel steps through Z, as before.
                if (!zSlider.isEnabled()) {
                    return;
                }
                int next = zSlider.getValue() + e.getWheelRotation();
                next = clamp(next, zSlider.getMinimum(), zSlider.getMaximum());
                zSlider.setValue(next);
            }
        });
    }

    /**
     * Scales the whole grid by {@code factor}, keeping the point under the
     * cursor ({@code cursor}, in grid coordinates) anchored in place. Zoom is
     * clamped to [1.0, MAX_ZOOM]; 1.0 is fit-to-window.
     */
    private void zoomBy(double factor, Point cursor) {
        JViewport viewport = gridScroll.getViewport();
        Dimension extent = viewport.getExtentSize();
        if (fitGridSize == null) {
            fitGridSize = new Dimension(Math.max(1, extent.width),
                    Math.max(1, extent.height));
        }
        double newZoom = Math.max(1.0, Math.min(MAX_ZOOM, zoom * factor));
        if (Math.abs(newZoom - zoom) < 1e-4) {
            return;
        }
        Point view = viewport.getViewPosition();
        Point anchor = cursor == null ? new Point(view.x, view.y) : cursor;
        int cursorVpX = anchor.x - view.x;
        int cursorVpY = anchor.y - view.y;
        double ratio = newZoom / zoom;
        zoom = newZoom;

        Dimension pref = new Dimension(
                Math.max(1, (int) Math.round(fitGridSize.width * zoom)),
                Math.max(1, (int) Math.round(fitGridSize.height * zoom)));
        // At zoom 1.0 the grid tracks the viewport (fills it, no scrollbars);
        // above 1.0 it takes its preferred size so the viewport can pan.
        gridPanel.setPreferredSize(pref);
        gridPanel.revalidate();
        gridScroll.validate();

        int targetX = (int) Math.round(anchor.x * ratio) - cursorVpX;
        int targetY = (int) Math.round(anchor.y * ratio) - cursorVpY;
        int maxX = Math.max(0, gridPanel.getWidth() - extent.width);
        int maxY = Math.max(0, gridPanel.getHeight() - extent.height);
        viewport.setViewPosition(new Point(
                clamp(targetX, 0, maxX), clamp(targetY, 0, maxY)));
    }

    private void panBy(int wheelRotation, boolean horizontal) {
        JViewport viewport = gridScroll.getViewport();
        Dimension extent = viewport.getExtentSize();
        Point view = viewport.getViewPosition();
        int delta = wheelRotation * 48;
        if (horizontal) {
            int maxX = Math.max(0, gridPanel.getWidth() - extent.width);
            view.x = clamp(view.x + delta, 0, maxX);
        } else {
            int maxY = Math.max(0, gridPanel.getHeight() - extent.height);
            view.y = clamp(view.y + delta, 0, maxY);
        }
        viewport.setViewPosition(view);
    }

    private void padGrid() {
        GridLayout layout = (GridLayout) gridPanel.getLayout();
        int capacity = layout.getRows() * layout.getColumns();
        while (gridPanel.getComponentCount() < capacity) {
            JPanel empty = new JPanel();
            empty.setBackground(new Color(0x1E, 0x20, 0x24));
            gridPanel.add(empty);
        }
    }

    private void refreshStatus() {
        statusLabel.setText("Slice " + controller.currentSlice()
                + " / " + Math.max(1, controller.maxSlice())
                + "  |  Variants: " + cells.size()
                + "  |  " + completed + "/" + total + " complete"
                + (failed > 0 ? " (" + failed + " failed)" : ""));
        updateSliceLabel();
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
        int max = Math.max(1, zSlider.getMaximum());
        int current = clamp(controller.currentSlice(), 1, max);
        zSliceLabel.setText(current + " / " + max);
    }

    private String progressText() {
        String text = completed + "/" + total + " complete";
        if (failed > 0) {
            text += " (" + failed + " failed)";
        }
        return text;
    }

    private Dimension imageDimsFromCells() {
        for (int i = 0; i < cells.size(); i++) {
            Dimension size = cells.get(i).sourceImageSize();
            if (size != null && size.width > 0 && size.height > 0) {
                return size;
            }
        }
        return null;
    }

    private void applySizeAndLocation(Rectangle desktop, int rows, int cols,
                                      Dimension imageSize, int toolBarHeight,
                                      int southHeight, int availW, int availH) {
        setMinimumSize(new Dimension(640, 480));
        if (imageSize == null) {
            // Aspect unknown (e.g. no source crop): fall back to a large
            // centred window, as before.
            int width = clamp((int) Math.round(desktop.width * 0.85d), 640, 1600);
            int height = clamp((int) Math.round(desktop.height * 0.85d), 480, 1200);
            setSize(width, height);
            setLocation(
                    desktop.x + (desktop.width - width) / 2,
                    desktop.y + (desktop.height - height) / 2);
            return;
        }

        int[] cell = computeCellSize(rows, cols, availW, availH,
                imageSize.width, imageSize.height);
        int gridW = cols * cell[0] + (cols - 1) * CELL_GAP + 2 * GRID_BORDER;
        int gridH = rows * cell[1] + (rows - 1) * CELL_GAP + 2 * GRID_BORDER;
        // Drive the window size from the grid's preferred size so the centre
        // region ends up the exact aspect ratio of the (uniform) image tiles.
        gridPanel.setPreferredSize(new Dimension(gridW, gridH));
        fitGridSize = new Dimension(gridW, gridH);
        pack();
        Dimension packed = getSize();
        int width = Math.min(Math.max(packed.width, 640), desktop.width);
        int height = Math.min(Math.max(packed.height, 480), desktop.height);
        setSize(width, height);
        setLocation(
                desktop.x + Math.max(0, (desktop.width - width) / 2),
                desktop.y + Math.max(0, (desktop.height - height) / 2));
    }

    private static int[] computeCellSize(int rows, int cols, int availW,
                                         int availH, int imageW, int imageH) {
        double cellAvailW = (availW - 2.0 * GRID_BORDER - (cols - 1) * CELL_GAP)
                / Math.max(1, cols);
        double cellAvailH = (availH - 2.0 * GRID_BORDER - (rows - 1) * CELL_GAP)
                / Math.max(1, rows);
        double scale = Math.min(cellAvailW / imageW, cellAvailH / imageH);
        if (!(scale > 0.0)) {
            scale = 1.0;
        }
        int width = Math.max(1, (int) Math.floor(imageW * scale));
        int height = Math.max(1, (int) Math.floor(imageH * scale));
        return new int[] { width, height };
    }

    /**
     * Chooses the row/column split that makes each (uniform) image tile as
     * large as possible within {@code availW x availH}, given the image's
     * aspect ratio. Falls back to a square-ish split when the inputs are not
     * usable.
     */
    static int[] optimalGrid(int count, int availW, int availH,
                             double imageAspect, int gap, int border) {
        int n = Math.max(1, count);
        if (!(imageAspect > 0.0) || availW <= 0 || availH <= 0) {
            return gridDimensions(n);
        }
        int bestCols = 1;
        double bestTileHeight = -1.0;
        for (int cols = 1; cols <= n; cols++) {
            int rows = (int) Math.ceil(n / (double) cols);
            double cellW = (availW - 2.0 * border - (cols - 1) * gap) / cols;
            double cellH = (availH - 2.0 * border - (rows - 1) * gap) / rows;
            if (cellW <= 1.0 || cellH <= 1.0) {
                continue;
            }
            // Tile height when the image is fitted into the cell preserving
            // aspect; tile area grows monotonically with this, so maximise it.
            double tileHeight = Math.min(cellH, cellW / imageAspect);
            if (tileHeight > bestTileHeight) {
                bestTileHeight = tileHeight;
                bestCols = cols;
            }
        }
        int rows = (int) Math.ceil(n / (double) bestCols);
        return new int[] { rows, bestCols };
    }

    static int[] gridDimensions(int count) {
        int n = Math.max(1, count);
        int cols = (int) Math.ceil(Math.sqrt(n));
        int rows = (int) Math.ceil(n / (double) cols);
        return new int[] { rows, cols };
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    /**
     * The grid panel. At fit-zoom it tracks the viewport size so it fills the
     * window with no scrollbars (preserving the original collage look); once
     * the user Ctrl+wheels past 1.0 it falls back to its (enlarged) preferred
     * size so the viewport can scroll and pan over it.
     */
    private final class ZoomableGrid extends JPanel implements Scrollable {

        ZoomableGrid(GridLayout layout) {
            super(layout);
        }

        @Override public Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        @Override public int getScrollableUnitIncrement(Rectangle visible,
                                                        int orientation,
                                                        int direction) {
            return 24;
        }

        @Override public int getScrollableBlockIncrement(Rectangle visible,
                                                         int orientation,
                                                         int direction) {
            return orientation == SwingConstants.HORIZONTAL
                    ? Math.max(1, visible.width - 24)
                    : Math.max(1, visible.height - 24);
        }

        @Override public boolean getScrollableTracksViewportWidth() {
            return zoom <= 1.0;
        }

        @Override public boolean getScrollableTracksViewportHeight() {
            return zoom <= 1.0;
        }
    }
}
