package flash.pipeline.ui.variations;

import ij.ImagePlus;

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
    private final LinkedHashMap<ParameterKey, Object> activeFacets =
            new LinkedHashMap<ParameterKey, Object>();
    private final LinkedHashMap<String, String> presetRowCaptions =
            new LinkedHashMap<String, String>();
    private final JPanel tilePanel = new JPanel();
    private final List<CountCurveMini> rowCountCurves =
            new ArrayList<CountCurveMini>();

    private ParameterSweep sweep;
    private ImagePlus rawSource;
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
                    cell.setRawSource(rawSource);
                    cells.add(cell);
                }
            }
        }
        refreshLayout();
    }

    public void setRowCountCurves(List<CountCurveMini> curves) {
        rowCountCurves.clear();
        if (curves != null) {
            for (int i = 0; i < curves.size(); i++) {
                CountCurveMini curve = curves.get(i);
                if (curve != null) {
                    rowCountCurves.add(curve);
                }
            }
        }
        refreshLayout();
    }

    public void setRawSource(ImagePlus rawSource) {
        this.rawSource = rawSource;
        for (int i = 0; i < cells.size(); i++) {
            cells.get(i).setRawSource(rawSource);
        }
    }

    public void setPresetRowCaptions(Map<String, String> captions) {
        presetRowCaptions.clear();
        if (captions != null) {
            presetRowCaptions.putAll(captions);
        }
        refreshLayout();
    }

    public void setFacetSelectionListener(FacetChipRow.FacetSelectionListener listener) {
        this.facetSelectionListener = listener;
    }

    public Map<ParameterKey, Object> activeFacetValues() {
        return new LinkedHashMap<ParameterKey, Object>(activeFacets);
    }

    public static List<ParameterKey> pickSpatialAxes(ParameterSweep.Method method,
                                                     ParameterSweep sweep) {
        ParameterSweep.Method resolvedMethod = method;
        if (resolvedMethod == null && sweep != null) {
            resolvedMethod = sweep.method();
        }
        List<ParameterKey> candidates;
        if (resolvedMethod == null) {
            candidates = sweptOrderableAxes(sweep);
        } else {
            switch (resolvedMethod) {
                case STARDIST:
                    candidates = sweptOrderableAxesByPrecedence(sweep,
                            STARDIST_SPATIAL_PRECEDENCE);
                    break;
                case CELLPOSE:
                    candidates = sweptOrderableAxesByPrecedence(sweep,
                            CELLPOSE_SPATIAL_PRECEDENCE);
                    break;
                case CLASSICAL:
                    candidates = sweptOrderableAxesByPrecedence(sweep,
                            CLASSICAL_SPATIAL_PRECEDENCE);
                    break;
                case FILTER:
                    candidates = hasPresetSweepAxes(sweep)
                            ? presetSpatialAxes(sweep)
                            : sweptOrderableAxes(sweep);
                    break;
                default:
                    candidates = sweptOrderableAxes(sweep);
                    break;
            }
        }
        return firstSpatialAxes(candidates);
    }

    public static FacetChipRow.ValueLabelProvider valueLabelProviderFor(
            final ParameterSweep sweep) {
        return new FacetChipRow.ValueLabelProvider() {
            @Override public String labelFor(ParameterKey axis, Object value) {
                if (axis == ParameterId.MACRO) {
                    return macroLabel(sweep, value);
                }
                return value == null ? "" : String.valueOf(value);
            }

            @Override public String tooltipFor(ParameterKey axis, Object value) {
                if (axis == ParameterId.MACRO) {
                    return macroTooltip(sweep, value);
                }
                return null;
            }
        };
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

    List<CountCurveMini> rowCountCurvesForTest() {
        return new ArrayList<CountCurveMini>(rowCountCurves);
    }

    Map<String, String> presetRowCaptionsForTest() {
        return new LinkedHashMap<String, String>(presetRowCaptions);
    }

    private void refreshLayout() {
        removeAll();
        tilePanel.removeAll();

        List<ParameterKey> sweptAxes = sweptAxes();
        List<ParameterKey> spatialCandidates = pickSpatialAxes(
                sweep == null ? null : sweep.method(), sweep);
        List<ParameterKey> spatialAxes = firstSpatialAxes(spatialCandidates);
        List<ParameterKey> facetAxes = facetAxes(sweptAxes, spatialAxes);
        updateActiveFacets(facetAxes);

        ParameterKey xAxis = spatialAxes.size() > 0 ? spatialAxes.get(0) : null;
        ParameterKey yAxis = spatialAxes.size() > 1 ? spatialAxes.get(1) : null;
        boolean hasLeft = yAxis != null;
        boolean hasRight = hasLeft && !rowCountCurves.isEmpty();
        int leftWidth = hasLeft ? AxisGutterPanel.leftWidthFor(yAxis) : 0;
        int columnCount = 1 + (hasLeft ? 1 : 0) + (hasRight ? 1 : 0);
        setLayout(new MigLayout("insets " + GAP + ", gap " + GAP + " " + GAP
                + ", fillx",
                gridColumnConstraints(hasLeft, hasRight, leftWidth),
                "[]"));

        if (!facetAxes.isEmpty()) {
            FacetChipRow row = new FacetChipRow(facetValues(facetAxes),
                    activeFacets,
                    new FacetChipRow.FacetSelectionListener() {
                        @Override public void facetSelected(ParameterKey axis, Object value) {
                            activeFacets.put(axis, value);
                            if (facetSelectionListener != null) {
                                facetSelectionListener.facetSelected(axis, value);
                            }
                            refreshLayout();
                        }
                    },
                    valueLabelProviderFor(sweep));
            add(row, "span " + columnCount + ", growx, wrap");
        }

        if (xAxis != null) {
            AxisGutterPanel top = new AxisGutterPanel(AxisGutterPanel.Mode.TOP,
                    xAxis, valuesForAxis(xAxis));
            if (hasLeft) {
                add(spacer(), "w " + leftWidth + "!, h 1!");
                add(top, hasRight ? "growx" : "growx, wrap");
                if (hasRight) {
                    add(spacer(), "w " + CountCurveStrip.miniPreferredSize().width
                            + "!, h 1!, wrap");
                }
            } else {
                add(top, "growx, wrap");
            }
        }

        configureTilePanel(xAxis, yAxis, facetAxes);
        if (hasLeft) {
            AxisGutterPanel left = new AxisGutterPanel(AxisGutterPanel.Mode.LEFT,
                    yAxis, valuesForAxis(yAxis));
            if (isPresetNameAxis(yAxis)) {
                left.setValueCaptions(presetRowCaptions);
            }
            add(left, "growy, aligny top");
            add(tilePanel, hasRight ? "growx, aligny top" : "growx, aligny top, wrap");
            if (hasRight) {
                add(rowCountCurvePanel(), "aligny top, wrap");
            }
        } else {
            add(tilePanel, "growx, aligny top, wrap");
        }
        revalidate();
        repaint();
    }

    private void configureTilePanel(ParameterKey xAxis,
                                    ParameterKey yAxis,
                                    List<ParameterKey> facetAxes) {
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

    private List<VariationCellPanel> orderedCells(ParameterKey xAxis,
                                                  ParameterKey yAxis,
                                                  List<ParameterKey> facetAxes) {
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

    private VariationCellPanel findCell(ParameterKey xAxis,
                                        Object xValue,
                                        ParameterKey yAxis,
                                        Object yValue,
                                        List<ParameterKey> facetAxes) {
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

    private List<VariationCellPanel> cellsMatchingFacets(List<ParameterKey> facetAxes) {
        List<VariationCellPanel> out = new ArrayList<VariationCellPanel>();
        for (int i = 0; i < cells.size(); i++) {
            VariationCellPanel cell = cells.get(i);
            if (matchesFacets(cell.combo(), facetAxes)) {
                out.add(cell);
            }
        }
        return out;
    }

    private boolean matchesFacets(ParameterCombo combo, List<ParameterKey> facetAxes) {
        for (int i = 0; i < facetAxes.size(); i++) {
            ParameterKey axis = facetAxes.get(i);
            Object expected = activeFacets.get(axis);
            if (expected != null && !valueEquals(combo.get(axis), expected)) {
                return false;
            }
        }
        return true;
    }

    private void updateActiveFacets(List<ParameterKey> facetAxes) {
        LinkedHashMap<ParameterKey, Object> next =
                new LinkedHashMap<ParameterKey, Object>();
        for (int i = 0; i < facetAxes.size(); i++) {
            ParameterKey axis = facetAxes.get(i);
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

    private static List<ParameterKey> firstSpatialAxes(List<ParameterKey> candidates) {
        List<ParameterKey> out = new ArrayList<ParameterKey>();
        for (int i = 0; i < candidates.size() && out.size() < 2; i++) {
            ParameterKey candidate = candidates.get(i);
            if (candidate != null && !out.contains(candidate)) {
                out.add(candidate);
            }
        }
        return out;
    }

    private List<ParameterKey> facetAxes(List<ParameterKey> sweptAxes,
                                         List<ParameterKey> spatialAxes) {
        List<ParameterKey> out = new ArrayList<ParameterKey>();
        for (int i = 0; i < sweptAxes.size(); i++) {
            ParameterKey axis = sweptAxes.get(i);
            if (!spatialAxes.contains(axis)) {
                out.add(axis);
            }
        }
        return out;
    }

    private LinkedHashMap<ParameterKey, List<?>> facetValues(List<ParameterKey> facetAxes) {
        LinkedHashMap<ParameterKey, List<?>> out =
                new LinkedHashMap<ParameterKey, List<?>>();
        for (int i = 0; i < facetAxes.size(); i++) {
            ParameterKey axis = facetAxes.get(i);
            out.put(axis, valuesForAxis(axis));
        }
        return out;
    }

    private List<ParameterKey> sweptAxes() {
        List<ParameterKey> axes = new ArrayList<ParameterKey>();
        if (sweep == null) {
            return axes;
        }
        for (Map.Entry<ParameterKey, ParameterValueList> entry
                : sweep.valueLists().entrySet()) {
            ParameterValueList values = entry.getValue();
            if (values != null && values.size() > 1) {
                axes.add(entry.getKey());
            }
        }
        return axes;
    }

    private List<Object> valuesForAxis(ParameterKey axis) {
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

    private static List<ParameterKey> sweptOrderableAxes(ParameterSweep sweep) {
        List<ParameterKey> axes = new ArrayList<ParameterKey>();
        if (sweep == null) {
            return axes;
        }
        for (Map.Entry<ParameterKey, ParameterValueList> entry
                : sweep.valueLists().entrySet()) {
            ParameterKey id = entry.getKey();
            ParameterValueList values = entry.getValue();
            if (isOrderable(id) && values != null && values.size() > 1) {
                axes.add(id);
            }
        }
        return axes;
    }

    private static List<ParameterKey> sweptOrderableAxesByPrecedence(
            ParameterSweep sweep,
            List<ParameterId> precedence) {
        List<ParameterKey> axes = new ArrayList<ParameterKey>();
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

    private static boolean isOrderable(ParameterKey key) {
        if (key instanceof ParameterId) {
            // MODEL and MACRO are categorical, so orderable() keeps them in
            // facet chips instead of top/left spatial gutters.
            return ((ParameterId) key).orderable();
        }
        if (key instanceof SlotSubstitutionKey) {
            return ((SlotSubstitutionKey) key).orderable();
        }
        if (key instanceof PresetSweepKey) {
            return ((PresetSweepKey) key).orderable();
        }
        return key != null && key.valueKind() == ParameterKey.ValueKind.NUMBER;
    }

    private static boolean hasPresetSweepAxes(ParameterSweep sweep) {
        if (sweep == null) {
            return false;
        }
        boolean hasPreset = false;
        boolean hasXValue = false;
        for (ParameterKey key : sweep.valueLists().keySet()) {
            if (!(key instanceof PresetSweepKey)) {
                continue;
            }
            PresetSweepKey presetKey = (PresetSweepKey) key;
            hasPreset |= presetKey.role() == PresetSweepKey.Role.PRESET_NAME;
            hasXValue |= presetKey.role() == PresetSweepKey.Role.X_VALUE;
        }
        return hasPreset && hasXValue;
    }

    private static List<ParameterKey> presetSpatialAxes(ParameterSweep sweep) {
        List<ParameterKey> axes = new ArrayList<ParameterKey>();
        ParameterKey x = null;
        ParameterKey preset = null;
        for (Map.Entry<ParameterKey, ParameterValueList> entry
                : sweep.valueLists().entrySet()) {
            if (!(entry.getKey() instanceof PresetSweepKey)
                    || entry.getValue() == null
                    || entry.getValue().size() <= 1) {
                continue;
            }
            PresetSweepKey key = (PresetSweepKey) entry.getKey();
            if (key.role() == PresetSweepKey.Role.X_VALUE) {
                x = key;
            } else if (key.role() == PresetSweepKey.Role.PRESET_NAME) {
                preset = key;
            }
        }
        if (x != null) {
            axes.add(x);
        }
        if (preset != null) {
            axes.add(preset);
        }
        return axes;
    }

    private static String macroLabel(ParameterSweep sweep, Object value) {
        String token = value == null ? "" : String.valueOf(value);
        MacroVariationSet macros = sweep == null
                ? MacroVariationSet.none()
                : sweep.macroVariations();
        String label = macros.displayNameFor(token);
        return abbreviate(label == null ? token : label, 28);
    }

    private static String macroTooltip(ParameterSweep sweep, Object value) {
        String token = value == null ? "" : String.valueOf(value);
        MacroVariationSet macros = sweep == null
                ? MacroVariationSet.none()
                : sweep.macroVariations();
        MacroVariation variation = macros.resolve(token);
        if (variation == null) {
            return token;
        }
        String hash = variation.normalizedScriptHash();
        if (hash == null || hash.trim().isEmpty()) {
            return variation.displayName();
        }
        return variation.displayName() + " (" + hash + ")";
    }

    private static String abbreviate(String text, int maxLength) {
        String safe = text == null ? "" : text;
        if (maxLength < 4 || safe.length() <= maxLength) {
            return safe;
        }
        return safe.substring(0, maxLength - 3) + "...";
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

    private static String gridColumnConstraints(boolean hasLeft,
                                                boolean hasRight,
                                                int leftWidth) {
        StringBuilder out = new StringBuilder();
        if (hasLeft) {
            out.append("[").append(leftWidth).append("!]");
        }
        out.append("[grow,fill]");
        if (hasRight) {
            out.append("[")
                    .append(CountCurveStrip.miniPreferredSize().width)
                    .append("!,fill]");
        }
        return out.toString();
    }

    private JPanel rowCountCurvePanel() {
        JPanel panel = new JPanel(new MigLayout("insets 0, gap 0 " + GAP,
                "[fill]", "[]"));
        panel.setOpaque(false);
        for (int i = 0; i < rowCountCurves.size(); i++) {
            panel.add(rowCountCurves.get(i), "growx, wrap");
        }
        return panel;
    }

    private static JPanel spacer() {
        JPanel spacer = new JPanel();
        spacer.setOpaque(false);
        return spacer;
    }

    private static boolean isPresetNameAxis(ParameterKey axis) {
        return axis instanceof PresetSweepKey
                && ((PresetSweepKey) axis).role() == PresetSweepKey.Role.PRESET_NAME;
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
