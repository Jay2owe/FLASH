package flash.pipeline.ui.variations;

import org.junit.Test;

import javax.swing.AbstractButton;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class VariationGridPanelAxisLayoutTest {

    @Test
    public void oneAxisSweepRendersNoLeftGutter() throws Exception {
        final VariationGridPanel grid = gridFor(sweep(sigma()));

        SwingUtilities.invokeAndWait(() -> {
            assertNotNull(gutter(grid, AxisGutterPanel.Mode.TOP));
            assertEquals(sigma(), axis(gutter(grid, AxisGutterPanel.Mode.TOP)));
            assertEquals(null, gutter(grid, AxisGutterPanel.Mode.LEFT));
        });
    }

    @Test
    public void twoAxisSweepRendersTopAndLeftGutters() throws Exception {
        final VariationGridPanel grid = gridFor(sweep(sigma(), rolling()));

        SwingUtilities.invokeAndWait(() -> {
            assertEquals(sigma(), axis(gutter(grid, AxisGutterPanel.Mode.TOP)));
            assertEquals(rolling(), axis(gutter(grid, AxisGutterPanel.Mode.LEFT)));
        });
    }

    @Test
    public void threeAxisSweepRendersFacetChipsForThirdAxis() throws Exception {
        final VariationGridPanel grid = gridFor(sweep(sigma(), rolling(), radius()));

        SwingUtilities.invokeAndWait(() -> {
            List<FacetChipRow> rows = descendants(grid, FacetChipRow.class);
            assertEquals(1, rows.size());
            assertTrue(rows.get(0).selectedValues().containsKey(radius()));
            assertFalse(rows.get(0).selectedValues().containsKey(sigma()));
            assertFalse(rows.get(0).selectedValues().containsKey(rolling()));
        });
    }

    @Test
    public void macroOnlySweepRendersFacetChipsWithoutSpatialGutters()
            throws Exception {
        final MacroFixture fixture = macroFixture();
        final VariationGridPanel grid = gridFor(macroSweep(fixture, false));

        SwingUtilities.invokeAndWait(() -> {
            assertTrue(descendants(grid, AxisGutterPanel.class).isEmpty());
            List<FacetChipRow> rows = descendants(grid, FacetChipRow.class);
            assertEquals(1, rows.size());
            assertTrue(rows.get(0).selectedValues().containsKey(ParameterId.MACRO));
            assertChipTexts(rows.get(0), fixture.blur.displayName(),
                    fixture.median.displayName());
        });
    }

    @Test
    public void macroPlusThresholdUsesThresholdSpatialAxisAndMacroFacet()
            throws Exception {
        final MacroFixture fixture = macroFixture();
        final ParameterSweep sweep = macroSweep(fixture, true);
        final VariationGridPanel grid = gridFor(sweep);

        SwingUtilities.invokeAndWait(() -> {
            assertFalse(VariationGridPanel.pickSpatialAxes(
                    sweep.method(), sweep).contains(ParameterId.MACRO));
            assertEquals(ParameterId.THRESHOLD,
                    axis(gutter(grid, AxisGutterPanel.Mode.TOP)));
            assertEquals(null, gutter(grid, AxisGutterPanel.Mode.LEFT));
            List<FacetChipRow> rows = descendants(grid, FacetChipRow.class);
            assertEquals(1, rows.size());
            assertTrue(rows.get(0).selectedValues().containsKey(ParameterId.MACRO));
            assertChipTexts(rows.get(0), fixture.blur.displayName(),
                    fixture.median.displayName());
        });
    }

    private static VariationGridPanel gridFor(final ParameterSweep sweep)
            throws Exception {
        final VariationGridPanel[] holder = new VariationGridPanel[1];
        SwingUtilities.invokeAndWait(() -> {
            VariationGridPanel grid = new VariationGridPanel();
            grid.setSweep(sweep);
            grid.setCells(cellsFor(sweep));
            holder[0] = grid;
        });
        return holder[0];
    }

    private static ParameterSweep sweep(FilterParameterId... swept) {
        Map<ParameterKey, ParameterValueList> values =
                new LinkedHashMap<ParameterKey, ParameterValueList>();
        FilterParameterId sigma = sigma();
        FilterParameterId rolling = rolling();
        FilterParameterId radius = radius();
        values.put(sigma, contains(swept, sigma)
                ? ParameterValueList.ofDoubles(0.5d, 1.0d, 1.5d)
                : new ParameterValueList(java.util.Collections.singletonList(
                        Double.valueOf(1.0d))));
        values.put(rolling, contains(swept, rolling)
                ? new ParameterValueList(java.util.Arrays.<Object>asList(
                        Integer.valueOf(25), Integer.valueOf(50)))
                : new ParameterValueList(java.util.Collections.singletonList(
                        Integer.valueOf(25))));
        values.put(radius, contains(swept, radius)
                ? new ParameterValueList(java.util.Arrays.<Object>asList(
                        Integer.valueOf(2), Integer.valueOf(3)))
                : new ParameterValueList(java.util.Collections.singletonList(
                        Integer.valueOf(2))));
        return new ParameterSweep(ParameterSweep.Method.FILTER, values,
                CropSpec.full(), "DAPI", "grid-axis-test", "filter:grid-axis");
    }

    private static ParameterSweep macroSweep(MacroFixture fixture,
                                             boolean includeThreshold) {
        Map<ParameterId, ParameterValueList> values =
                new LinkedHashMap<ParameterId, ParameterValueList>();
        if (includeThreshold) {
            values.put(ParameterId.THRESHOLD,
                    ParameterValueList.ofDoubles(10.0d, 20.0d, 30.0d));
        }
        values.put(ParameterId.MACRO,
                ParameterValueList.ofStrings(fixture.blur.token(),
                        fixture.median.token()));
        return new ParameterSweep(ParameterSweep.Method.CLASSICAL, values,
                CropSpec.full(), "DAPI", "grid-axis-macro-test",
                MacroVariationSet.of(fixture.blur, fixture.median));
    }

    private static boolean contains(FilterParameterId[] ids, FilterParameterId target) {
        for (int i = 0; i < ids.length; i++) {
            if (target.equals(ids[i])) {
                return true;
            }
        }
        return false;
    }

    private static List<VariationCellPanel> cellsFor(ParameterSweep sweep) {
        List<VariationCellPanel> cells = new ArrayList<VariationCellPanel>();
        List<ParameterCombo> combos = sweep.combos();
        for (int i = 0; i < combos.size(); i++) {
            cells.add(new VariationCellPanel(combos.get(i), null, null, null, i));
        }
        return cells;
    }

    private static FilterParameterId sigma() {
        return new FilterParameterId(0, 0, 0, "Gaussian Blur", "sigma");
    }

    private static FilterParameterId rolling() {
        return new FilterParameterId(0, 1, 0, "Subtract Background", "rolling");
    }

    private static FilterParameterId radius() {
        return new FilterParameterId(0, 2, 0, "Median", "radius");
    }

    private static AxisGutterPanel gutter(Container root, AxisGutterPanel.Mode mode) {
        List<AxisGutterPanel> gutters = descendants(root, AxisGutterPanel.class);
        for (int i = 0; i < gutters.size(); i++) {
            AxisGutterPanel gutter = gutters.get(i);
            if (mode == field(gutter, "mode")) {
                return gutter;
            }
        }
        return null;
    }

    private static ParameterKey axis(AxisGutterPanel gutter) {
        return (ParameterKey) field(gutter, "axis");
    }

    private static Object field(Object target, String name) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(target);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static void assertChipTexts(Container root, String... expected) {
        List<AbstractButton> buttons = descendants(root, AbstractButton.class);
        assertEquals(Arrays.asList(expected), buttonTexts(buttons));
        for (int i = 0; i < buttons.size(); i++) {
            assertFalse(buttons.get(i).getText().startsWith("macro:"));
            assertNotNull(buttons.get(i).getToolTipText());
        }
    }

    private static List<String> buttonTexts(List<AbstractButton> buttons) {
        List<String> out = new ArrayList<String>();
        for (int i = 0; i < buttons.size(); i++) {
            out.add(buttons.get(i).getText());
        }
        return out;
    }

    private static <T> List<T> descendants(Component root, Class<T> type) {
        List<T> out = new ArrayList<T>();
        collect(root, type, out);
        return out;
    }

    private static <T> void collect(Component component, Class<T> type, List<T> out) {
        if (component == null) {
            return;
        }
        if (type.isInstance(component)) {
            out.add(type.cast(component));
        }
        if (component instanceof Container) {
            Component[] children = ((Container) component).getComponents();
            for (int i = 0; i < children.length; i++) {
                collect(children[i], type, out);
            }
        }
    }

    private static MacroFixture macroFixture() {
        return new MacroFixture(
                MacroVariation.pasted("Blur macro",
                        "run(\"Gaussian Blur...\", \"sigma=1 stack\");"),
                MacroVariation.pasted("Median r=2",
                        "run(\"Median...\", \"radius=2 stack\");"));
    }

    private static final class MacroFixture {
        final MacroVariation blur;
        final MacroVariation median;

        MacroFixture(MacroVariation blur, MacroVariation median) {
            this.blur = blur;
            this.median = median;
        }
    }
}
