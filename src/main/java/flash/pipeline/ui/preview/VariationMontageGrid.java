package flash.pipeline.ui.preview;

import flash.pipeline.ui.variations.ParameterCombo;
import flash.pipeline.ui.variations.FacetChipRow;
import flash.pipeline.ui.variations.ParameterId;
import flash.pipeline.ui.variations.ParameterKey;
import flash.pipeline.ui.variations.ParameterSweep;
import flash.pipeline.ui.variations.ParameterValueList;
import flash.pipeline.ui.variations.VariationGridPanel;
import ij.ImagePlus;
import net.miginfocom.swing.MigLayout;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.Scrollable;
import javax.swing.SwingConstants;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class VariationMontageGrid extends JPanel implements Scrollable {

    private enum AxisMode {
        TOP,
        LEFT
    }

    private static final int GAP = 8;
    private static final int FALLBACK_TOP_HEIGHT = 42;
    private static final int FALLBACK_LEFT_WIDTH = 92;
    private static final int CELL_WIDTH = 360;
    private static final int CELL_HEIGHT = 330;

    private final List<VariationMontageDialog.MontageTile> tiles =
            new ArrayList<VariationMontageDialog.MontageTile>();
    private final List<TileView> tileViews = new ArrayList<TileView>();
    private final LinkedHashMap<ParameterId, Object> activeFacets =
            new LinkedHashMap<ParameterId, Object>();
    private final JPanel tilePanel = new JPanel();

    private ParameterSweep sweep;
    private int currentZ = 1;
    private boolean syncingSlices;
    private ImagePlus sourceImage;
    private PreviewDisplaySettings sourceDisplaySettings = PreviewDisplaySettings.defaultFor("Grays");
    private boolean overlaySelected;
    private ImagePlus overlaySourceImage;
    private PreviewDisplaySettings overlaySourceDisplaySettings =
            PreviewDisplaySettings.defaultFor("Grays");

    public VariationMontageGrid() {
        setOpaque(false);
        tilePanel.setOpaque(false);
        refreshLayout();
    }

    public void setTiles(List<VariationMontageDialog.MontageTile> newTiles,
                         ParameterSweep sweep,
                         int initialZ) {
        disposeGeneratedImages();
        tiles.clear();
        tileViews.clear();
        this.sweep = sweep;
        currentZ = Math.max(1, initialZ);
        if (newTiles != null) {
            for (int i = 0; i < newTiles.size(); i++) {
                VariationMontageDialog.MontageTile tile = newTiles.get(i);
                if (tile != null) {
                    tiles.add(tile);
                    tileViews.add(new TileView(tile, tileViews.size()));
                }
            }
        }
        refreshLayout();
        refreshImages();
        setCurrentZ(currentZ);
    }

    public void setDisplayImages(ImagePlus sourceImage,
                                 PreviewDisplaySettings sourceDisplaySettings,
                                 boolean overlaySelected,
                                 ImagePlus overlaySourceImage,
                                 PreviewDisplaySettings overlaySourceDisplaySettings) {
        this.sourceImage = sourceImage;
        this.sourceDisplaySettings = safeDisplaySettings(sourceDisplaySettings);
        this.overlaySelected = overlaySelected;
        this.overlaySourceImage = overlaySourceImage;
        this.overlaySourceDisplaySettings = safeDisplaySettings(overlaySourceDisplaySettings);
        refreshImages();
        setCurrentZ(currentZ);
    }

    public void setCurrentZ(int zSlice) {
        if (syncingSlices) return;
        syncingSlices = true;
        try {
            int clamped = ImagePreviewPanel.clamp(zSlice, 1, sharedSliceCount());
            currentZ = clamped;
            for (int i = 0; i < tileViews.size(); i++) {
                tileViews.get(i).preview.setCurrentZ(clamped);
            }
        } finally {
            syncingSlices = false;
        }
    }

    public int getCurrentZ() {
        return currentZ;
    }

    public int tileCount() {
        return tileViews.size();
    }

    public boolean hasTilesWithLabels() {
        for (int i = 0; i < tiles.size(); i++) {
            if (tiles.get(i).labelImage != null) {
                return true;
            }
        }
        return false;
    }

    List<ImagePreviewPanel> previewPanelsForTest() {
        List<ImagePreviewPanel> out = new ArrayList<ImagePreviewPanel>();
        for (int i = 0; i < tileViews.size(); i++) {
            out.add(tileViews.get(i).preview);
        }
        return out;
    }

    void disposeGeneratedImages() {
        for (int i = 0; i < tileViews.size(); i++) {
            tileViews.get(i).disposeGeneratedImage();
        }
    }

    private void refreshLayout() {
        removeAll();
        tilePanel.removeAll();

        List<ParameterId> sweptAxes = sweptAxes();
        List<ParameterId> spatialAxes = firstSpatialAxes(pickSpatialAxes(sweep));
        List<ParameterId> facetAxes = facetAxes(sweptAxes, spatialAxes);
        updateActiveFacets(facetAxes);

        ParameterId xAxis = spatialAxes.size() > 0 ? spatialAxes.get(0) : null;
        ParameterId yAxis = spatialAxes.size() > 1 ? spatialAxes.get(1) : null;
        boolean hasLeft = yAxis != null;
        setLayout(new MigLayout("insets " + GAP + ", gap " + GAP + " " + GAP
                + ", fillx",
                hasLeft
                        ? "[" + axisLeftWidth() + "!][grow,fill]"
                        : "[grow,fill]",
                "[]"));

        if (!facetAxes.isEmpty()) {
            add(createFacetRow(facetValues(facetAxes)),
                    hasLeft ? "span 2, growx, wrap" : "growx, wrap");
        }

        if (xAxis != null) {
            JComponent top = createAxisGutter(AxisMode.TOP, xAxis, valuesForAxis(xAxis));
            if (hasLeft) {
                add(spacer(), "w " + axisLeftWidth() + "!, h 1!");
                add(top, "growx, wrap");
            } else {
                add(top, "growx, wrap");
            }
        }

        configureTilePanel(xAxis, yAxis, facetAxes);
        if (hasLeft) {
            JComponent left = createAxisGutter(AxisMode.LEFT, yAxis, valuesForAxis(yAxis));
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
        List<TileView> ordered = orderedViews(xAxis, yAxis, facetAxes);
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

    private List<TileView> orderedViews(ParameterId xAxis,
                                        ParameterId yAxis,
                                        List<ParameterId> facetAxes) {
        if (xAxis == null) {
            return viewsMatchingFacets(facetAxes);
        }
        List<TileView> ordered = new ArrayList<TileView>();
        List<Object> xValues = valuesForAxis(xAxis);
        if (yAxis == null) {
            for (int x = 0; x < xValues.size(); x++) {
                TileView view = findView(xAxis, xValues.get(x), null, null, facetAxes);
                if (view != null) {
                    ordered.add(view);
                }
            }
            return ordered;
        }

        List<Object> yValues = valuesForAxis(yAxis);
        for (int y = 0; y < yValues.size(); y++) {
            for (int x = 0; x < xValues.size(); x++) {
                TileView view = findView(xAxis, xValues.get(x), yAxis, yValues.get(y),
                        facetAxes);
                if (view != null) {
                    ordered.add(view);
                }
            }
        }
        return ordered;
    }

    private TileView findView(ParameterId xAxis,
                              Object xValue,
                              ParameterId yAxis,
                              Object yValue,
                              List<ParameterId> facetAxes) {
        for (int i = 0; i < tileViews.size(); i++) {
            TileView view = tileViews.get(i);
            ParameterCombo combo = view.combo();
            if (xAxis != null && !valueEquals(combo.get(xAxis), xValue)) {
                continue;
            }
            if (yAxis != null && !valueEquals(combo.get(yAxis), yValue)) {
                continue;
            }
            if (!matchesFacets(combo, facetAxes)) {
                continue;
            }
            return view;
        }
        return null;
    }

    private List<TileView> viewsMatchingFacets(List<ParameterId> facetAxes) {
        List<TileView> out = new ArrayList<TileView>();
        for (int i = 0; i < tileViews.size(); i++) {
            TileView view = tileViews.get(i);
            if (matchesFacets(view.combo(), facetAxes)) {
                out.add(view);
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

    private void refreshImages() {
        for (int i = 0; i < tileViews.size(); i++) {
            TileView view = tileViews.get(i);
            view.setDisplayImage(displayImageFor(view.tile));
        }
    }

    private DisplayImage displayImageFor(VariationMontageDialog.MontageTile tile) {
        if (tile == null) {
            return new DisplayImage(null, PreviewDisplaySettings.defaultFor("Grays"),
                    false, false);
        }
        if (overlaySelected && overlaySourceImage != null && tile.labelImage != null) {
            ImagePlus overlay = ObjectOverlayRenderer.renderOverlay(
                    overlaySourceImage, tile.labelImage, overlaySourceDisplaySettings);
            if (overlay != null) {
                return new DisplayImage(overlay, PreviewDisplaySettings.defaultFor("Grays"),
                        false, true);
            }
        }
        if (!overlaySelected && sourceImage != null) {
            return new DisplayImage(sourceImage, sourceDisplaySettings, true, false);
        }
        ImagePlus labelMap = ObjectOverlayRenderer.renderLabelMap(
                tile.labelImage, tile.objectCount);
        if (labelMap != null) {
            return new DisplayImage(labelMap, PreviewDisplaySettings.defaultFor("Grays"),
                    false, true);
        }
        return new DisplayImage(tile.labelImage, PreviewDisplaySettings.defaultFor("Grays"),
                false, false);
    }

    private int sharedSliceCount() {
        if (tileViews.isEmpty()) {
            return 1;
        }
        int sharedMax = Math.max(1, tileViews.get(0).preview.getSliceCount());
        for (int i = 1; i < tileViews.size(); i++) {
            sharedMax = Math.min(sharedMax,
                    Math.max(1, tileViews.get(i).preview.getSliceCount()));
        }
        return Math.max(1, sharedMax);
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
            if (candidates.get(i) != null && !out.contains(candidates.get(i))) {
                out.add(candidates.get(i));
            }
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
        for (Map.Entry<?, ParameterValueList> entry : sweep.valueLists().entrySet()) {
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
        ParameterValueList valueList = null;
        try {
            valueList = sweep.valueLists().get(axis);
        } catch (RuntimeException ignored) {
            valueList = null;
        }
        if (valueList == null) {
            for (Map.Entry<?, ParameterValueList> entry : sweep.valueLists().entrySet()) {
                if (axis.equals(entry.getKey())) {
                    valueList = entry.getValue();
                    break;
                }
            }
        }
        if (valueList != null) {
            values.addAll(valueList.values());
        }
        return values;
    }

    private static List<ParameterId> pickSpatialAxes(ParameterSweep sweep) {
        List<ParameterId> shared = pickSharedSpatialAxes(sweep);
        if (!shared.isEmpty()) {
            return shared;
        }
        return pickLocalSpatialAxes(sweep);
    }

    private static List<ParameterId> pickSharedSpatialAxes(ParameterSweep sweep) {
        try {
            Method method;
            try {
                method = VariationGridPanel.class.getMethod(
                        "pickSpatialAxes", ParameterSweep.Method.class, ParameterSweep.class);
            } catch (NoSuchMethodException e) {
                method = VariationGridPanel.class.getDeclaredMethod(
                        "pickSpatialAxes", ParameterSweep.Method.class, ParameterSweep.class);
                method.setAccessible(true);
            }
            Object methodArg = sweep == null ? null : sweep.method();
            Object result = method.invoke(null, methodArg, sweep);
            return parameterIdList(result);
        } catch (ReflectiveOperationException e) {
            return Collections.emptyList();
        } catch (RuntimeException e) {
            return Collections.emptyList();
        }
    }

    private static List<ParameterId> parameterIdList(Object result) {
        List<ParameterId> out = new ArrayList<ParameterId>();
        if (!(result instanceof Iterable<?>)) {
            return out;
        }
        for (Object value : (Iterable<?>) result) {
            if (value instanceof ParameterId) {
                out.add((ParameterId) value);
            }
        }
        return out;
    }

    private static List<ParameterId> pickLocalSpatialAxes(final ParameterSweep sweep) {
        List<ParameterId> axes = new ArrayList<ParameterId>();
        if (sweep == null) {
            return axes;
        }
        for (Map.Entry<?, ParameterValueList> entry : sweep.valueLists().entrySet()) {
            if (!(entry.getKey() instanceof ParameterId)) {
                continue;
            }
            ParameterId axis = (ParameterId) entry.getKey();
            ParameterValueList values = entry.getValue();
            if (isOrderable(axis) && values != null && values.size() > 1) {
                axes.add(axis);
            }
        }
        Collections.sort(axes, new Comparator<ParameterId>() {
            @Override public int compare(ParameterId a, ParameterId b) {
                int bySize = Integer.valueOf(axisSize(sweep, b)).compareTo(
                        Integer.valueOf(axisSize(sweep, a)));
                if (bySize != 0) {
                    return bySize;
                }
                int byPriority = Integer.valueOf(spatialPriority(a)).compareTo(
                        Integer.valueOf(spatialPriority(b)));
                if (byPriority != 0) {
                    return byPriority;
                }
                return a.name().compareTo(b.name());
            }
        });
        return axes;
    }

    private static boolean isOrderable(ParameterId axis) {
        if (axis == null) {
            return false;
        }
        try {
            Method method = axis.getClass().getMethod("orderable");
            Object result = method.invoke(axis);
            if (result instanceof Boolean) {
                return ((Boolean) result).booleanValue();
            }
        } catch (ReflectiveOperationException ignored) {
            // Fall through to known categorical names for older ParameterId versions.
        } catch (RuntimeException ignored) {
            // Fall through to known categorical names for older ParameterId versions.
        }
        String name = axis.name();
        return !"MODEL".equals(name) && !"MACRO".equals(name);
    }

    private static int axisSize(ParameterSweep sweep, ParameterId axis) {
        if (sweep == null || axis == null) {
            return 0;
        }
        try {
            ParameterValueList values = sweep.valueLists().get(axis);
            return values == null ? 0 : values.size();
        } catch (RuntimeException e) {
            return 0;
        }
    }

    private static int spatialPriority(ParameterId axis) {
        if (axis == null) return Integer.MAX_VALUE;
        String name = axis.name();
        String[] priority = new String[] {
                "PROB_THRESH",
                "NMS_THRESH",
                "LINKING_MAX",
                "GAP_CLOSING_MAX",
                "FRAME_GAP",
                "AREA_MIN",
                "AREA_MAX",
                "QUALITY_MIN",
                "INTENSITY_MIN",
                "CELLPROB_THRESHOLD",
                "DIAMETER",
                "FLOW_THRESHOLD",
                "THRESHOLD",
                "MIN_SIZE",
                "MAX_SIZE"
        };
        for (int i = 0; i < priority.length; i++) {
            if (priority[i].equals(name)) {
                return i;
            }
        }
        return 1000 + axis.ordinal();
    }

    private JComponent createFacetRow(final Map<ParameterId, ? extends List<?>> facetValues) {
        return new FacetChipRow(facetValues, activeFacets,
                new FacetChipRow.FacetSelectionListener() {
                    @Override public void facetSelected(
                            ParameterKey axis, Object value) {
                        if (axis instanceof ParameterId) {
                            activeFacets.put((ParameterId) axis, value);
                            refreshLayout();
                        }
                    }
                },
                VariationGridPanel.valueLabelProviderFor(sweep));
    }

    private static JComponent createAxisGutter(AxisMode mode,
                                               ParameterId axis,
                                               List<Object> values) {
        try {
            Class<?> gutterClass = Class.forName(
                    "flash.pipeline.ui.variations.AxisGutterPanel");
            Class<?> modeClass = Class.forName(
                    "flash.pipeline.ui.variations.AxisGutterPanel$Mode");
            Object gutterMode = enumValue(modeClass, mode.name());
            Constructor<?> ctor = gutterClass.getConstructor(
                    modeClass, ParameterId.class, List.class);
            Object gutter = ctor.newInstance(gutterMode, axis, values);
            if (gutter instanceof JComponent) {
                return (JComponent) gutter;
            }
        } catch (ReflectiveOperationException e) {
            // Use the lightweight fallback below when the shared gutter is absent.
        } catch (RuntimeException e) {
            // Use the lightweight fallback below when the shared gutter is absent.
        }
        return new SimpleAxisGutter(mode, axis, values);
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private static Object enumValue(Class<?> enumClass, String name) {
        return Enum.valueOf((Class) enumClass.asSubclass(Enum.class), name);
    }

    private static int axisLeftWidth() {
        return sharedGutterConstant("LEFT_WIDTH", FALLBACK_LEFT_WIDTH);
    }

    private static int axisTopHeight() {
        return sharedGutterConstant("TOP_HEIGHT", FALLBACK_TOP_HEIGHT);
    }

    private static int sharedGutterConstant(String fieldName, int fallback) {
        try {
            Class<?> gutterClass = Class.forName(
                    "flash.pipeline.ui.variations.AxisGutterPanel");
            Object value = gutterClass.getField(fieldName).get(null);
            if (value instanceof Number) {
                return ((Number) value).intValue();
            }
        } catch (ReflectiveOperationException e) {
            return fallback;
        } catch (RuntimeException e) {
            return fallback;
        }
        return fallback;
    }

    private static String parameterLabel(ParameterId axis) {
        if (axis == null) {
            return "";
        }
        try {
            Class<?> labelsClass = Class.forName("flash.pipeline.ui.variations.ParameterLabels");
            Method method = labelsClass.getMethod("labelFor", ParameterId.class);
            Object label = method.invoke(null, axis);
            if (label != null) {
                return String.valueOf(label);
            }
        } catch (ReflectiveOperationException ignored) {
            // Fall through to enum names in older trees.
        } catch (RuntimeException ignored) {
            // Fall through to enum names in older trees.
        }
        return axis.name().toLowerCase(Locale.ROOT).replace('_', ' ');
    }

    private static String valueText(Object value) {
        return value == null ? "" : String.valueOf(value);
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

    private static PreviewDisplaySettings safeDisplaySettings(PreviewDisplaySettings settings) {
        return settings == null ? PreviewDisplaySettings.defaultFor("Grays") : settings;
    }

    @Override public Dimension getPreferredScrollableViewportSize() {
        return new Dimension(1100, 620);
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
            return 180;
        }
        return orientation == SwingConstants.VERTICAL
                ? Math.max(24, visibleRect.height - 32)
                : Math.max(24, visibleRect.width - 32);
    }

    @Override public boolean getScrollableTracksViewportWidth() {
        return getParent() == null || getPreferredSize().width <= getParent().getWidth();
    }

    @Override public boolean getScrollableTracksViewportHeight() {
        return false;
    }

    private final class TileView extends JPanel {
        private final VariationMontageDialog.MontageTile tile;
        private final ImagePreviewPanel preview = new ImagePreviewPanel("Variation");
        private final JLabel footer = new JLabel(" ", SwingConstants.CENTER);
        private ImagePlus generatedImage;

        TileView(VariationMontageDialog.MontageTile tile, int index) {
            super(new BorderLayout(4, 4));
            this.tile = tile;
            setOpaque(false);
            setPreferredSize(new Dimension(CELL_WIDTH, CELL_HEIGHT));
            setMinimumSize(new Dimension(260, 250));
            setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 0));

            preview.setPreviewTitle("Variation " + (index + 1));
            preview.setMetadataHeaderVisible(false);
            preview.setPreferredSize(new Dimension(340, 296));
            preview.setZSliceChangeListener(new ImagePreviewPanel.ZSliceChangeListener() {
                @Override public void zSliceChanged(ImagePreviewPanel source, int zSlice) {
                    if (!syncingSlices) {
                        setCurrentZ(zSlice);
                    }
                }
            });
            add(preview, BorderLayout.CENTER);

            footer.setFont(footer.getFont().deriveFont(11f));
            footer.setForeground(new Color(65, 70, 75));
            footer.setOpaque(false);
            footer.setText(metricText(tile));
            add(footer, BorderLayout.SOUTH);
            setToolTipText(tooltipText(tile));
            preview.setToolTipText(tooltipText(tile));
            footer.setToolTipText(tooltipText(tile));
        }

        ParameterCombo combo() {
            return tile.combo == null ? ParameterCombo.builder().build() : tile.combo;
        }

        void setDisplayImage(DisplayImage displayImage) {
            ImagePlus oldGenerated = generatedImage;
            generatedImage = displayImage.generated ? displayImage.image : null;
            preview.setDisplaySettingsEnabled(displayImage.displaySettingsEnabled);
            preview.setDisplaySettings(displayImage.displaySettings);
            preview.setImage(displayImage.image);
            if (oldGenerated != null && oldGenerated != generatedImage) {
                oldGenerated.flush();
            }
        }

        void disposeGeneratedImage() {
            if (generatedImage != null) {
                generatedImage.flush();
                generatedImage = null;
            }
        }
    }

    private static final class DisplayImage {
        private final ImagePlus image;
        private final PreviewDisplaySettings displaySettings;
        private final boolean displaySettingsEnabled;
        private final boolean generated;

        private DisplayImage(ImagePlus image,
                             PreviewDisplaySettings displaySettings,
                             boolean displaySettingsEnabled,
                             boolean generated) {
            this.image = image;
            this.displaySettings = safeDisplaySettings(displaySettings);
            this.displaySettingsEnabled = displaySettingsEnabled;
            this.generated = generated;
        }
    }

    private static String metricText(VariationMontageDialog.MontageTile tile) {
        if (tile == null) {
            return " ";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("N ").append(Math.max(0, tile.objectCount));
        if (Double.isFinite(tile.iouToNeighbours)) {
            sb.append("    IoU ");
            sb.append(String.format(Locale.ROOT, "%.2f",
                    Double.valueOf(tile.iouToNeighbours)));
        }
        return sb.toString();
    }

    private static String tooltipText(VariationMontageDialog.MontageTile tile) {
        if (tile == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder("<html>");
        if (tile.combo != null) {
            sb.append(html(tile.combo.toCanonicalJson()));
            sb.append("<br>");
        }
        sb.append("Objects: ").append(Math.max(0, tile.objectCount));
        if (Double.isFinite(tile.iouToNeighbours)) {
            sb.append("<br>Mean IoU with neighbours: ");
            sb.append(String.format(Locale.ROOT, "%.2f",
                    Double.valueOf(tile.iouToNeighbours)));
        }
        sb.append("</html>");
        return sb.toString();
    }

    private static String html(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }

    private final class SimpleFacetRow extends JPanel {
        SimpleFacetRow(Map<ParameterId, ? extends List<?>> valuesByAxis) {
            super(new FlowLayout(FlowLayout.LEFT, 8, 2));
            setOpaque(false);
            if (valuesByAxis == null) {
                return;
            }
            for (Map.Entry<ParameterId, ? extends List<?>> entry : valuesByAxis.entrySet()) {
                add(groupFor(entry.getKey(), entry.getValue()));
            }
        }

        private JPanel groupFor(final ParameterId axis, List<?> values) {
            JPanel group = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
            group.setOpaque(false);
            JLabel label = new JLabel(parameterLabel(axis));
            label.setForeground(new Color(78, 93, 101));
            group.add(label);
            if (values != null) {
                for (int i = 0; i < values.size(); i++) {
                    final Object value = values.get(i);
                    JButton button = new JButton(valueText(value));
                    button.setFont(button.getFont().deriveFont(11f));
                    button.setFocusPainted(false);
                    button.setEnabled(!valueEquals(activeFacets.get(axis), value));
                    button.addActionListener(e -> {
                        activeFacets.put(axis, value);
                        refreshLayout();
                    });
                    group.add(button);
                }
            }
            return group;
        }
    }

    private static final class SimpleAxisGutter extends JComponent {
        private final AxisMode mode;
        private final ParameterId axis;
        private final List<Object> values = new ArrayList<Object>();

        SimpleAxisGutter(AxisMode mode, ParameterId axis, List<?> values) {
            this.mode = mode == null ? AxisMode.TOP : mode;
            this.axis = axis;
            setOpaque(false);
            if (values != null) {
                for (int i = 0; i < values.size(); i++) {
                    this.values.add(values.get(i));
                }
            }
        }

        @Override public Dimension getPreferredSize() {
            int count = Math.max(1, values.size());
            if (mode == AxisMode.TOP) {
                return new Dimension(count * CELL_WIDTH + Math.max(0, count - 1) * GAP,
                        axisTopHeight());
            }
            return new Dimension(axisLeftWidth(),
                    count * CELL_HEIGHT + Math.max(0, count - 1) * GAP);
        }

        @Override protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                        RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                if (mode == AxisMode.TOP) {
                    paintTop(g2);
                } else {
                    paintLeft(g2);
                }
            } finally {
                g2.dispose();
            }
        }

        private void paintTop(Graphics2D g2) {
            g2.setColor(new Color(78, 93, 101));
            g2.drawString(parameterLabel(axis) + " ->", 4, 13);
            int count = Math.max(1, values.size());
            int slotWidth = Math.max(1, (getWidth() - Math.max(0, count - 1) * GAP) / count);
            FontMetrics metrics = g2.getFontMetrics();
            g2.setColor(new Color(33, 33, 33));
            int x = 0;
            for (int i = 0; i < values.size(); i++) {
                String text = valueText(values.get(i));
                int center = x + slotWidth / 2;
                g2.drawString(text, center - metrics.stringWidth(text) / 2, 34);
                x += slotWidth + GAP;
            }
        }

        private void paintLeft(Graphics2D g2) {
            drawRotated(g2, "v " + parameterLabel(axis), 13, getHeight() / 2);
            int count = Math.max(1, values.size());
            int slotHeight = Math.max(1, (getHeight() - Math.max(0, count - 1) * GAP) / count);
            int y = 0;
            g2.setColor(new Color(33, 33, 33));
            for (int i = 0; i < values.size(); i++) {
                drawRotated(g2, valueText(values.get(i)),
                        Math.max(40, getWidth() / 2 + 16),
                        y + slotHeight / 2);
                y += slotHeight + GAP;
            }
        }

        private static void drawRotated(Graphics2D g2, String text, int centerX, int centerY) {
            String safeText = text == null ? "" : text;
            FontMetrics metrics = g2.getFontMetrics();
            AffineTransform old = g2.getTransform();
            g2.rotate(-Math.PI / 2.0d, centerX, centerY);
            g2.drawString(safeText,
                    centerX - metrics.stringWidth(safeText) / 2,
                    centerY + metrics.getAscent() / 2 - 2);
            g2.setTransform(old);
        }
    }
}
