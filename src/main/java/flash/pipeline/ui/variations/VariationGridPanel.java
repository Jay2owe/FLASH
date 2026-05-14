package flash.pipeline.ui.variations;

import javax.swing.JPanel;
import javax.swing.Scrollable;
import javax.swing.SwingConstants;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.LayoutManager;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class VariationGridPanel extends JPanel implements Scrollable {

    private static final int GAP = 8;

    private final List<VariationCellPanel> cells = new ArrayList<VariationCellPanel>();
    private ParameterSweep sweep;

    public VariationGridPanel() {
        setOpaque(false);
        setLayout(new WrapLayout(FlowLayout.LEFT, GAP, GAP));
    }

    public void setSweep(ParameterSweep sweep) {
        this.sweep = sweep;
        setLayout(layoutForCurrentState());
        revalidate();
    }

    public void setCells(List<VariationCellPanel> newCells) {
        cells.clear();
        removeAll();
        if (newCells != null) {
            for (int i = 0; i < newCells.size(); i++) {
                VariationCellPanel cell = newCells.get(i);
                if (cell != null) {
                    cells.add(cell);
                    add(cell);
                }
            }
        }
        setLayout(layoutForCurrentState());
        revalidate();
        repaint();
    }

    public void broadcastZ(int z) {
        for (int i = 0; i < cells.size(); i++) {
            cells.get(i).setZ(z);
        }
    }

    int cellCountForTest() {
        return cells.size();
    }

    LayoutManager layoutForTest() {
        return getLayout();
    }

    List<VariationCellPanel> cellsForTest() {
        return new ArrayList<VariationCellPanel>(cells);
    }

    private LayoutManager layoutForCurrentState() {
        List<Integer> axisSizes = sweptAxisSizes();
        int count = Math.max(1, cells.size());
        if (axisSizes.size() == 0) {
            return new GridLayout(1, count, GAP, GAP);
        }
        if (axisSizes.size() == 1) {
            return new GridLayout(1, count, GAP, GAP);
        }
        if (axisSizes.size() == 2) {
            int rows = Math.max(1, axisSizes.get(0).intValue());
            int cols = Math.max(1, axisSizes.get(1).intValue());
            return new GridLayout(rows, cols, GAP, GAP);
        }
        return new WrapLayout(FlowLayout.LEFT, GAP, GAP);
    }

    private List<Integer> sweptAxisSizes() {
        List<Integer> sizes = new ArrayList<Integer>();
        if (sweep == null) {
            return sizes;
        }
        for (Map.Entry<ParameterId, ParameterValueList> entry : sweep.valueLists().entrySet()) {
            int size = entry.getValue() == null ? 0 : entry.getValue().size();
            if (size > 1) {
                sizes.add(Integer.valueOf(size));
            }
        }
        return sizes;
    }

    @Override public Dimension getPreferredScrollableViewportSize() {
        return new Dimension(760, 420);
    }

    @Override public int getScrollableUnitIncrement(Rectangle visibleRect,
                                                    int orientation,
                                                    int direction) {
        return 24;
    }

    @Override public int getScrollableBlockIncrement(Rectangle visibleRect,
                                                     int orientation,
                                                     int direction) {
        if (visibleRect == null) {
            return 160;
        }
        return orientation == SwingConstants.VERTICAL
                ? Math.max(24, visibleRect.height - 32)
                : Math.max(24, visibleRect.width - 32);
    }

    @Override public boolean getScrollableTracksViewportWidth() {
        return true;
    }

    @Override public boolean getScrollableTracksViewportHeight() {
        return false;
    }

    static final class WrapLayout extends FlowLayout {
        WrapLayout(int align, int hgap, int vgap) {
            super(align, hgap, vgap);
        }

        @Override public Dimension preferredLayoutSize(Container target) {
            return layoutSize(target, true);
        }

        @Override public Dimension minimumLayoutSize(Container target) {
            Dimension minimum = layoutSize(target, false);
            minimum.width -= getHgap() + 1;
            return minimum;
        }

        private Dimension layoutSize(Container target, boolean preferred) {
            synchronized (target.getTreeLock()) {
                int targetWidth = target.getWidth();
                if (targetWidth <= 0) {
                    targetWidth = 760;
                }
                Insets insets = target.getInsets();
                int horizontalInsets = insets.left + insets.right + getHgap() * 2;
                int maxWidth = Math.max(1, targetWidth - horizontalInsets);
                int rowWidth = 0;
                int rowHeight = 0;
                int totalWidth = 0;
                int totalHeight = 0;

                int count = target.getComponentCount();
                for (int i = 0; i < count; i++) {
                    Component component = target.getComponent(i);
                    if (!component.isVisible()) {
                        continue;
                    }
                    Dimension size = preferred
                            ? component.getPreferredSize()
                            : component.getMinimumSize();
                    if (rowWidth > 0 && rowWidth + getHgap() + size.width > maxWidth) {
                        totalWidth = Math.max(totalWidth, rowWidth);
                        totalHeight += rowHeight + getVgap();
                        rowWidth = 0;
                        rowHeight = 0;
                    }
                    if (rowWidth > 0) {
                        rowWidth += getHgap();
                    }
                    rowWidth += size.width;
                    rowHeight = Math.max(rowHeight, size.height);
                }
                totalWidth = Math.max(totalWidth, rowWidth);
                totalHeight += rowHeight;
                totalWidth += horizontalInsets;
                totalHeight += insets.top + insets.bottom + getVgap() * 2;
                return new Dimension(totalWidth, totalHeight);
            }
        }
    }
}
