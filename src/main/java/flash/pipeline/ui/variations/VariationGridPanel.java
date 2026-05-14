package flash.pipeline.ui.variations;

import net.miginfocom.swing.MigLayout;

import javax.swing.JPanel;
import javax.swing.Scrollable;
import javax.swing.SwingConstants;
import java.awt.Dimension;
import java.awt.LayoutManager;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class VariationGridPanel extends JPanel implements Scrollable {

    private static final int GAP = 8;
    private static final List<ParameterId> STARDIST_SPATIAL_PRECEDENCE =
            java.util.Arrays.asList(
                    ParameterId.PROB_THRESH,
                    ParameterId.NMS_THRESH,
                    ParameterId.LINKING_MAX,
                    ParameterId.GAP_CLOSING_MAX,
                    ParameterId.AREA_MIN,
                    ParameterId.AREA_MAX,
                    ParameterId.QUALITY_MIN,
                    ParameterId.INTENSITY_MIN,
                    ParameterId.FRAME_GAP);
    private static final List<ParameterId> CELLPOSE_SPATIAL_PRECEDENCE =
            java.util.Arrays.asList(
                    ParameterId.CELLPROB_THRESHOLD,
                    ParameterId.DIAMETER,
                    ParameterId.FLOW_THRESHOLD);
    private static final List<ParameterId> CLASSICAL_SPATIAL_PRECEDENCE =
            java.util.Arrays.asList(
                    ParameterId.THRESHOLD,
                    ParameterId.MIN_SIZE,
                    ParameterId.MAX_SIZE);

    private final List<VariationCellPanel> cells = new ArrayList<VariationCellPanel>();
    private final LinkedHashMap<ParameterId, Object> activeFacets =
            new LinkedHashMap<ParameterId, Object>();
    private final JPanel tilePanel = new JPanel();

    private ParameterSweep sweep;
    private FacetChipRow.FacetSelectionListener facetSelectionListener;

    public VariationGridPanel() {
        setOpaque(false);
        tilePanel.setOpaque(false);
        refreshLayout();
    }

    public void setSweep(ParameterSweep sweep) {
        this.sweep = sweep;
        refreshLayout();
    }

    public void setCells(List<VariationCellPanel> newCells) {
        cells.clear();
        if (newCells != null) {
            for (int i = 0; i < newCells.size(); i++) {
                VariationCellPanel cell = newCells.get(i);
                if (cell != null) {
                    cells.add(cell);
                }
            }
        }
        refreshLayout();
    }

    public void setFacetSelectionListener(FacetChipRow.FacetSelectionListener listener) {
        this.facetSelectionListener = listener;
    }

    public Map<ParameterId, Object> activeFacetValues() {
        return new LinkedHashMap<ParameterId, Object>(activeFacets);
    }

    public static List<ParameterId> pickSpatialAxes(ParameterSweep.Method method,
                                                    ParameterSweep sweep) {
        ParameterSweep.Method resolvedMethod = method;
        if (resolvedMethod == null && sweep != null) {
            resolvedMethod = sweep.method();
        }
        if (resolvedMethod == ParameterSweep.Method.STARDIST) {
            return sweptOrderableAxesByPrecedence(sweep,
                    STARDIST_SPATIAL_PRECEDENCE);
        }
        if (resolvedMethod == ParameterSweep.Method.CELLPOSE) {
            return sweptOrderableAxesByPrecedence(sweep,
                    CELLPOSE_SPATIAL_PRECEDENCE);
        }
        if (resolvedMethod == ParameterSweep.Method.CLASSICAL) {
            return sweptOrderableAxesByPrecedence(sweep,
                    CLASSICAL_SPATIAL_PRECEDENCE);
        }
        return sweptOrderableAxes(sweep);
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

    private void refreshLayout() {
        removeAll();
        tilePanel.removeAll();

        List<ParameterId> sweptAxes = sweptAxes();
        List<ParameterId> spatialCandidates = pickSpatialAxes(
                sweep == null ? null : sweep.method(), sweep);
        List<ParameterId> spatialAxes = firstSpatialAxes(spatialCandidates);
        List<ParameterId> facetAxes = facetAxes(sweptAxes, spatialAxes);
        updateActiveFacets(facetAxes);

        ParameterId xAxis = spatialAxes.size() > 0 ? spatialAxes.get(0) : null;
        ParameterId yAxis = spatialAxes.size() > 1 ? spatialAxes.get(1) : null;
        boolean hasLeft = yAxis != null;
        setLayout(new MigLayout("insets " + GAP + ", gap " + GAP + " " + GAP
                + ", fillx",
                hasLeft
                        ? "[" + AxisGutterPanel.LEFT_WIDTH + "!][grow,fill]"
                        : "[grow,fill]",
                "[]"));

        if (!facetAxes.isEmpty()) {
            FacetChipRow row = new FacetChipRow(facetValues(facetAxes),
                    activeFacets,
                    new FacetChipRow.FacetSelectionListener() {
                        @Override public void facetSelected(ParameterId axis, Object value) {
                            activeFacets.put(axis, value);
                            if (facetSelectionListener != null) {
                                facetSelectionListener.facetSelected(axis, value);
                            }
                            refreshLayout();
                        }
                    });
            add(row, hasLeft ? "span 2, growx, wrap" : "growx, wrap");
        }

        if (xAxis != null) {
            AxisGutterPanel top = new AxisGutterPanel(AxisGutterPanel.Mode.TOP,
                    xAxis, valuesForAxis(xAxis));
            if (hasLeft) {
                add(spacer(), "w " + AxisGutterPanel.LEFT_WIDTH + "!, h 1!");
                add(top, "growx, wrap");
            } else {
                add(top, "growx, wrap");
            }
        }

        configureTilePanel(xAxis, yAxis, facetAxes);
        if (hasLeft) {
            AxisGutterPanel left = new AxisGutterPanel(AxisGutterPanel.Mode.LEFT,
                    yAxis, valuesForAxis(yAxis));
            add(left, "growy, aligny top");
            add(tilePanel, "growx, aligny top, wrap");
        } else {
            add(tilePanel, "growx, aligny top, wrap");
        }
        revalidate();
        repaint();
    }

    private void configureTilePanel(ParameterId xAxis,
                                    ParameterId yAxis,
                                    List<ParameterId> facetAxes) {
        List<VariationCellPanel> ordered = orderedCells(xAxis, yAxis, facetAxes);
        int columns = xAxis == null
                ? Math.max(1, ordered.size())
                : Math.max(1, valuesForAxis(xAxis).size());
        tilePanel.setLayout(new MigLayout("insets 0, gap " + GAP + " " + GAP
                + ", fillx, wrap " + columns,
                columnConstraints(columns),
                "[]"));
        for (int i = 0; i < ordered.size(); i++) {
            tilePanel.add(ordered.get(i), "growx");
        }
    }

    private List<VariationCellPanel> orderedCells(ParameterId xAxis,
                                                  ParameterId yAxis,
                                                  List<ParameterId> facetAxes) {
        if (xAxis == null) {
            return cellsMatchingFacets(facetAxes);
        }
        List<VariationCellPanel> ordered = new ArrayList<VariationCellPanel>();
        List<Object> xValues = valuesForAxis(xAxis);
        if (yAxis == null) {
            for (int x = 0; x < xValues.size(); x++) {
                VariationCellPanel cell = findCell(xAxis, xValues.get(x),
                        null, null, facetAxes);
                if (cell != null) {
                    ordered.add(cell);
                }
            }
            return ordered;
        }

        List<Object> yValues = valuesForAxis(yAxis);
        for (int y = 0; y < yValues.size(); y++) {
            for (int x = 0; x < xValues.size(); x++) {
                VariationCellPanel cell = findCell(xAxis, xValues.get(x),
                        yAxis, yValues.get(y), facetAxes);
                if (cell != null) {
                    ordered.add(cell);
                }
            }
        }
        return ordered;
    }

    private VariationCellPanel findCell(ParameterId xAxis,
                                        Object xValue,
                                        ParameterId yAxis,
                                        Object yValue,
                                        List<ParameterId> facetAxes) {
        for (int i = 0; i < cells.size(); i++) {
            VariationCellPanel cell = cells.get(i);
            ParameterCombo combo = cell.combo();
            if (xAxis != null && !valueEquals(combo.get(xAxis), xValue)) {
                continue;
            }
            if (yAxis != null && !valueEquals(combo.get(yAxis), yValue)) {
                continue;
            }
            if (!matchesFacets(combo, facetAxes)) {
                continue;
            }
            return cell;
        }
        return null;
    }

    private List<VariationCellPanel> cellsMatchingFacets(List<ParameterId> facetAxes) {
        List<VariationCellPanel> out = new ArrayList<VariationCellPanel>();
        for (int i = 0; i < cells.size(); i++) {
            VariationCellPanel cell = cells.get(i);
            if (matchesFacets(cell.combo(), facetAxes)) {
                out.add(cell);
            }
        }
        return out;
    }

    private boolean matchesFacets(ParameterCombo combo, List<ParameterId> facetAxes) {
        for (int i = 0; i < facetAxes.size(); i++) {
            ParameterId axis = facetAxes.get(i);
            Object expected = activeFacets.get(axis);
            if (expected != null && !valueEquals(combo.get(axis), expected)) {
                return false;
            }
        }
        return true;
    }

    private void updateActiveFacets(List<ParameterId> facetAxes) {
        LinkedHashMap<ParameterId, Object> next =
                new LinkedHashMap<ParameterId, Object>();
        for (int i = 0; i < facetAxes.size(); i++) {
            ParameterId axis = facetAxes.get(i);
            List<Object> values = valuesForAxis(axis);
            if (values.isEmpty()) {
                continue;
            }
            Object current = activeFacets.get(axis);
            next.put(axis, values.contains(current) ? current : values.get(0));
        }
        activeFacets.clear();
        activeFacets.putAll(next);
    }

    private List<ParameterId> firstSpatialAxes(List<ParameterId> candidates) {
        List<ParameterId> out = new ArrayList<ParameterId>();
        for (int i = 0; i < candidates.size() && out.size() < 2; i++) {
            out.add(candidates.get(i));
        }
        return out;
    }

    private List<ParameterId> facetAxes(List<ParameterId> sweptAxes,
                                        List<ParameterId> spatialAxes) {
        List<ParameterId> out = new ArrayList<ParameterId>();
        for (int i = 0; i < sweptAxes.size(); i++) {
            ParameterId axis = sweptAxes.get(i);
            if (!spatialAxes.contains(axis)) {
                out.add(axis);
            }
        }
        return out;
    }

    private LinkedHashMap<ParameterId, List<?>> facetValues(List<ParameterId> facetAxes) {
        LinkedHashMap<ParameterId, List<?>> out =
                new LinkedHashMap<ParameterId, List<?>>();
        for (int i = 0; i < facetAxes.size(); i++) {
            ParameterId axis = facetAxes.get(i);
            out.put(axis, valuesForAxis(axis));
        }
        return out;
    }

    private List<ParameterId> sweptAxes() {
        List<ParameterId> axes = new ArrayList<ParameterId>();
        if (sweep == null) {
            return axes;
        }
        for (Map.Entry<ParameterKey, ParameterValueList> entry
                : sweep.valueLists().entrySet()) {
            if (!(entry.getKey() instanceof ParameterId)) {
                continue;
            }
            ParameterValueList values = entry.getValue();
            if (values != null && values.size() > 1) {
                axes.add((ParameterId) entry.getKey());
            }
        }
        return axes;
    }

    private List<Object> valuesForAxis(ParameterId axis) {
        List<Object> values = new ArrayList<Object>();
        if (sweep == null || axis == null) {
            return values;
        }
        ParameterValueList valueList = sweep.valueLists().get(axis);
        if (valueList == null) {
            return values;
        }
        values.addAll(valueList.values());
        return values;
    }

    private static List<ParameterId> sweptOrderableAxes(ParameterSweep sweep) {
        List<ParameterId> axes = new ArrayList<ParameterId>();
        if (sweep == null) {
            return axes;
        }
        for (Map.Entry<ParameterKey, ParameterValueList> entry
                : sweep.valueLists().entrySet()) {
            if (!(entry.getKey() instanceof ParameterId)) {
                continue;
            }
            ParameterId id = (ParameterId) entry.getKey();
            ParameterValueList values = entry.getValue();
            if (id.orderable() && values != null && values.size() > 1) {
                axes.add(id);
            }
        }
        return axes;
    }

    private static List<ParameterId> sweptOrderableAxesByPrecedence(
            ParameterSweep sweep,
            List<ParameterId> precedence) {
        List<ParameterId> axes = new ArrayList<ParameterId>();
        if (sweep == null || precedence == null) {
            return axes;
        }
        for (int i = 0; i < precedence.size(); i++) {
            ParameterId id = precedence.get(i);
            ParameterValueList values = sweep.valueLists().get(id);
            if (id.orderable() && values != null && values.size() > 1) {
                axes.add(id);
            }
        }
        return axes;
    }

    private static boolean valueEquals(Object left, Object right) {
        return left == null ? right == null : left.equals(right);
    }

    private static String columnConstraints(int columns) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < columns; i++) {
            sb.append("[grow,fill]");
        }
        return sb.toString();
    }

    private static JPanel spacer() {
        JPanel spacer = new JPanel();
        spacer.setOpaque(false);
        return spacer;
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
}
