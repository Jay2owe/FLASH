package flash.pipeline.ui.variations;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JSlider;
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
    private final JButton pickSelectedButton = new JButton("Pick selected");
    private final JPanel gridPanel;
    private final JSlider zSlider = new JSlider(1, 1, 1);
    private final JLabel zSliceLabel = new JLabel(" ");
    private final JLabel statusLabel = new JLabel(" ");
    private final JProgressBar progressBar = new JProgressBar();

    private int completed;
    private int total;
    private int failed;
    private boolean updatingSlider;

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

        int[] dims = gridDimensions(cells.size());
        gridPanel = new JPanel(new GridLayout(dims[0], dims[1], 4, 4));
        gridPanel.setBackground(new Color(0x1E, 0x20, 0x24));
        gridPanel.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        for (int i = 0; i < cells.size(); i++) {
            gridPanel.add(cells.get(i));
        }
        padGrid();
        installMouseWheelHandler();

        configureToolBar();
        configureSlider();
        configureFooter();

        setLayout(new BorderLayout());
        add(toolBar, BorderLayout.NORTH);
        add(gridPanel, BorderLayout.CENTER);
        add(southPanel(), BorderLayout.SOUTH);
        setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);

        total = cells.size();
        setCompletedCount(0, total, 0);
        setSliceMax(controller.maxSlice());
        applyInitialSizeAndLocation(owner);
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

    public void attachPickSelectedActionListener(ActionListener listener) {
        pickSelectedButton.addActionListener(listener);
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
        pickSelectedButton.setEnabled(false);
        pickSelectedButton.setToolTipText(
                "Use the currently selected variation as the result.");
        toolBar.add(otsuOverlayCheckBox);
        toolBar.addSeparator();
        toolBar.add(downstreamVerdictCheckBox);
        toolBar.add(stopDownstreamButton);
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
                if (!zSlider.isEnabled()) {
                    return;
                }
                int next = zSlider.getValue() + e.getWheelRotation();
                next = clamp(next, zSlider.getMinimum(), zSlider.getMaximum());
                zSlider.setValue(next);
            }
        });
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

    private void applyInitialSizeAndLocation(Window owner) {
        setMinimumSize(new Dimension(640, 480));
        Rectangle desktop = GraphicsEnvironment
                .getLocalGraphicsEnvironment()
                .getMaximumWindowBounds();
        Rectangle ownerBounds = owner == null ? null : owner.getBounds();
        Dimension size;
        Point location;
        if (ownerBounds != null && ownerBounds.width > 0
                && ownerBounds.x + ownerBounds.width + 660 <= desktop.x + desktop.width) {
            // Place beside the dialog, occupying the remaining horizontal space.
            int leftEdge = ownerBounds.x + ownerBounds.width + 8;
            int rightEdge = desktop.x + desktop.width - 8;
            int width = clamp(rightEdge - leftEdge, 640, 1600);
            int height = clamp((int) Math.round(desktop.height * 0.9d), 480, 1200);
            int top = clamp(ownerBounds.y, desktop.y, desktop.y + desktop.height - height);
            size = new Dimension(width, height);
            location = new Point(leftEdge, top);
        } else {
            // No room beside the dialog. Fall back to a large centred window.
            int width = clamp((int) Math.round(desktop.width * 0.85d), 640, 1600);
            int height = clamp((int) Math.round(desktop.height * 0.85d), 480, 1200);
            size = new Dimension(width, height);
            location = new Point(
                    desktop.x + (desktop.width - width) / 2,
                    desktop.y + (desktop.height - height) / 2);
        }
        setSize(size);
        setLocation(location);
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
}
