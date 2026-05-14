package flash.pipeline.ui.variations.integration;

import flash.pipeline.ui.config.ConfigQcContext;
import flash.pipeline.ui.config.StarDistParameterStage;
import flash.pipeline.ui.preview.VariationMontageDialog;
import flash.pipeline.ui.variations.AxisGutterPanel;
import flash.pipeline.ui.variations.CountCurveStrip;
import flash.pipeline.ui.variations.CropSpec;
import flash.pipeline.ui.variations.FacetChipRow;
import flash.pipeline.ui.variations.ParameterCombo;
import flash.pipeline.ui.variations.ParameterId;
import flash.pipeline.ui.variations.ParameterLabels;
import flash.pipeline.ui.variations.ParameterSweep;
import flash.pipeline.ui.variations.ParameterValueList;
import flash.pipeline.ui.variations.VariationCellPanel;
import flash.pipeline.ui.variations.VariationEngineContext;
import flash.pipeline.ui.variations.VariationGridPanel;
import flash.pipeline.ui.variations.VariationResult;
import flash.pipeline.ui.variations.VariationStrategy;
import flash.pipeline.ui.variations.VariationsDialog;

import ij.ImagePlus;
import ij.ImageStack;
import ij.measure.ResultsTable;
import ij.process.ShortProcessor;

import org.junit.Assume;
import org.junit.Test;

import javax.swing.AbstractButton;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.awt.GraphicsEnvironment;
import java.awt.Window;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class StarDistVisualUpgradeIntegrationTest {

    @Test
    public void twoAxisStarDistSweepShowsGuttersRibbonsAndCountCurve() throws Exception {
        Assume.assumeFalse("VariationsDialog creates Swing dialogs.",
                GraphicsEnvironment.isHeadless());

        final VariationsDialog dialog = openStartedDialog(twoAxisStarDistSweep());
        try {
            runOnEdt(new CheckedRunnable() {
                @Override public void run() throws Exception {
                    VariationGridPanel grid = gridPanel(dialog);
                    assertEquals(6, cellCount(grid));

                    assertAxisGutter(axisGutter(grid, AxisGutterPanel.Mode.TOP),
                            ParameterId.PROB_THRESH, "probThresh",
                            Arrays.<Object>asList(0.3d, 0.5d, 0.7d));
                    assertAxisGutter(axisGutter(grid, AxisGutterPanel.Mode.LEFT),
                            ParameterId.NMS_THRESH, "nms",
                            Arrays.<Object>asList(0.3d, 0.5d));

                    List<VariationCellPanel> cells = cellsForTest(grid);
                    assertEquals(0, ribbonCount(cells, "kneeWinner"));
                    assertTrue(ribbonCount(cells, "stabilityWinner") <= 1);

                    List<CountCurveStrip> strips = descendants(dialog.getWindow(),
                            CountCurveStrip.class);
                    assertFalse("Expected a count-curve strip above the StarDist grid.",
                            strips.isEmpty());
                    assertTrue(strips.get(0).isVisible());
                }
            });
        } finally {
            dispose(dialog);
        }
    }

    @Test
    public void threeAxisStarDistSweepFacetsQualityAndSwapsVisibleTiles()
            throws Exception {
        Assume.assumeFalse("VariationsDialog creates Swing dialogs.",
                GraphicsEnvironment.isHeadless());

        final VariationsDialog dialog = openStartedDialog(threeAxisStarDistSweep());
        try {
            runOnEdt(new CheckedRunnable() {
                @Override public void run() throws Exception {
                    VariationGridPanel grid = gridPanel(dialog);

                    AxisGutterPanel top = axisGutter(grid, AxisGutterPanel.Mode.TOP);
                    AxisGutterPanel left = axisGutter(grid, AxisGutterPanel.Mode.LEFT);
                    assertEquals(ParameterId.PROB_THRESH, gutterAxis(top));
                    assertEquals(ParameterId.NMS_THRESH, gutterAxis(left));
                    assertFalse(Arrays.asList(gutterAxis(top), gutterAxis(left))
                            .contains(ParameterId.CELLPROB_THRESHOLD));

                    List<FacetChipRow> rows = descendants(grid, FacetChipRow.class);
                    assertEquals(1, rows.size());
                    assertTrue(rows.get(0).selectedValues()
                            .containsKey(ParameterId.QUALITY_MIN));
                    assertChipTexts(rows.get(0), Arrays.asList("0.0", "0.5"));

                    List<VariationCellPanel> before = visibleCells(grid);
                    assertEquals(6, before.size());
                    Set<String> beforeIds = comboIds(before);
                    assertVisibleFacetValue(before, 0.0d);

                    JButton qualityChip = buttonWithText(rows.get(0), "0.5");
                    assertNotNull(qualityChip);
                    qualityChip.doClick();

                    List<VariationCellPanel> after = visibleCells(grid);
                    assertEquals(6, after.size());
                    assertVisibleFacetValue(after, 0.5d);
                    assertFalse(beforeIds.equals(comboIds(after)));
                }
            });
        } finally {
            dispose(dialog);
        }
    }

    @Test
    public void montageLauncherOpensDialogWithExpectedFooterOrder()
            throws Exception {
        Assume.assumeFalse("VariationMontageDialog creates Swing dialogs.",
                GraphicsEnvironment.isHeadless());

        final VariationsDialog dialog = openStartedDialog(twoAxisStarDistSweep());
        try {
            runOnEdt(new CheckedRunnable() {
                @Override public void run() throws Exception {
                    JButton launcher = buttonWithText(dialog.getWindow(),
                            "Open in large montage");
                    assertNotNull(launcher);
                    assertTrue(launcher.isEnabled());
                    launcher.doClick();

                    VariationMontageDialog montage = montageDialog(dialog);
                    assertNotNull(montage);
                    assertFooterOrder(montage, Arrays.asList(
                            "Source:",
                            "Original",
                            "Overlay objects",
                            "over",
                            "Filtered image",
                            "Adjust Brightness/Contrast",
                            "Grey LUT",
                            "Close"));
                }
            });
        } finally {
            dispose(dialog);
        }
    }

    private static VariationsDialog openStartedDialog(ParameterSweep sweep)
            throws Exception {
        final AtomicReference<VariationsDialog> ref =
                new AtomicReference<VariationsDialog>();
        runOnEdt(new CheckedRunnable() {
            @Override public void run() throws Exception {
                VariationsDialog dialog = new VariationsDialog(null,
                        starDistContext(),
                        new Consumer<ParameterCombo>() {
                            @Override public void accept(ParameterCombo combo) {
                            }
                        });
                invoke(dialog, "setSweepForTest",
                        new Class<?>[] { ParameterSweep.class }, sweep);
                invoke(dialog, "setStrategyForTest",
                        new Class<?>[] { VariationStrategy.class },
                        new SyntheticStarDistStrategy());
                dialog.start();
                ref.set(dialog);
            }
        });

        VariationsDialog dialog = ref.get();
        invoke(dialog, "waitForDoneForTest",
                new Class<?>[] { long.class }, Long.valueOf(5000L));
        runOnEdt(new CheckedRunnable() {
            @Override public void run() {
            }
        });
        return dialog;
    }

    private static ParameterSweep twoAxisStarDistSweep() {
        Map<ParameterId, ParameterValueList> values =
                new LinkedHashMap<ParameterId, ParameterValueList>();
        values.put(ParameterId.PROB_THRESH,
                ParameterValueList.ofDoubles(0.3d, 0.5d, 0.7d));
        values.put(ParameterId.NMS_THRESH,
                ParameterValueList.ofDoubles(0.3d, 0.5d));
        return new ParameterSweep(ParameterSweep.Method.STARDIST, values,
                CropSpec.full(), "DAPI", "synthetic-stardist-2axis");
    }

    private static ParameterSweep threeAxisStarDistSweep() {
        Map<ParameterId, ParameterValueList> values =
                new LinkedHashMap<ParameterId, ParameterValueList>();
        values.put(ParameterId.PROB_THRESH,
                ParameterValueList.ofDoubles(0.3d, 0.5d, 0.7d));
        values.put(ParameterId.NMS_THRESH,
                ParameterValueList.ofDoubles(0.3d, 0.5d));
        values.put(ParameterId.QUALITY_MIN,
                ParameterValueList.ofDoubles(0.0d, 0.5d));
        return new ParameterSweep(ParameterSweep.Method.STARDIST, values,
                CropSpec.full(), "DAPI", "synthetic-stardist-3axis");
    }

    private static VariationEngineContext starDistContext() {
        ImagePlus raw = sourceImage("raw source", 12);
        ImagePlus filtered = sourceImage("filtered source", 36);
        ConfigQcContext config = ConfigQcContext.fromImages(new File("."),
                new File("target/stardist-visual-upgrade-test-bin"),
                null,
                Arrays.asList(filtered),
                Arrays.asList("DAPI"),
                0);
        StarDistParameterStage.Parameters base =
                new StarDistParameterStage.Parameters(
                        0.5d, 0.3d, 5.0d, 5.0d, 1,
                        0.0d, Double.POSITIVE_INFINITY, 0.0d, 0.0d);
        return VariationEngineContext.forStarDist("DAPI", raw, filtered,
                config, base, null);
    }

    private static ImagePlus sourceImage(String title, int baseValue) {
        ImageStack stack = new ImageStack(24, 24);
        for (int z = 0; z < 3; z++) {
            ShortProcessor processor = new ShortProcessor(24, 24);
            processor.setValue(baseValue + z);
            processor.fill();
            stack.addSlice("z" + (z + 1), processor);
        }
        ImagePlus image = new ImagePlus(title, stack);
        image.setDimensions(1, 3, 1);
        return image;
    }

    private static final class SyntheticStarDistStrategy implements VariationStrategy {
        @Override public void dispatch(ParameterSweep sweep,
                                       Consumer<VariationResult> publisher,
                                       BooleanSupplier cancelCheck) {
            List<ParameterCombo> combos = sweep.combos();
            for (int i = 0; i < combos.size(); i++) {
                if (cancelCheck != null && cancelCheck.getAsBoolean()) {
                    return;
                }
                ParameterCombo combo = combos.get(i);
                int count = syntheticObjectCount(combo);
                publisher.accept(VariationResult.success(combo,
                        labelImage("labels-" + i, count),
                        count,
                        1L,
                        stats(count)));
            }
        }
    }

    private static int syntheticObjectCount(ParameterCombo combo) {
        double prob = number(combo, ParameterId.PROB_THRESH, 0.5d);
        double nms = number(combo, ParameterId.NMS_THRESH, 0.3d);
        double quality = number(combo, ParameterId.QUALITY_MIN, 0.0d);
        int count = (int) Math.round(11.0d - prob * 7.0d
                + nms * 5.0d - quality * 4.0d);
        return Math.max(2, Math.min(10, count));
    }

    private static double number(ParameterCombo combo, ParameterId id,
                                 double fallback) {
        Object value = combo.get(id);
        return value instanceof Number ? ((Number) value).doubleValue() : fallback;
    }

    private static ImagePlus labelImage(String title, int count) {
        ImageStack stack = new ImageStack(24, 24);
        for (int z = 0; z < 3; z++) {
            ShortProcessor processor = new ShortProcessor(24, 24);
            for (int i = 0; i < count; i++) {
                int x = 2 + (i * 5) % 20;
                int y = 2 + ((i * 7) % 20);
                processor.set(x, y, i + 1);
            }
            stack.addSlice("z" + (z + 1), processor);
        }
        ImagePlus image = new ImagePlus(title, stack);
        image.setDimensions(1, 3, 1);
        return image;
    }

    private static ResultsTable stats(int count) {
        ResultsTable table = new ResultsTable();
        for (int i = 0; i < count; i++) {
            table.incrementCounter();
            table.setValue("Label", i, i + 1);
        }
        return table;
    }

    private static VariationGridPanel gridPanel(VariationsDialog dialog)
            throws Exception {
        return (VariationGridPanel) invoke(dialog, "gridPanelForTest",
                new Class<?>[0]);
    }

    private static int cellCount(VariationGridPanel grid) throws Exception {
        return ((Integer) invoke(grid, "cellCountForTest",
                new Class<?>[0])).intValue();
    }

    @SuppressWarnings("unchecked")
    private static List<VariationCellPanel> cellsForTest(VariationGridPanel grid)
            throws Exception {
        return (List<VariationCellPanel>) invoke(grid, "cellsForTest",
                new Class<?>[0]);
    }

    private static AxisGutterPanel axisGutter(VariationGridPanel grid,
                                              AxisGutterPanel.Mode mode)
            throws Exception {
        List<AxisGutterPanel> gutters = descendants(grid, AxisGutterPanel.class);
        for (int i = 0; i < gutters.size(); i++) {
            AxisGutterPanel gutter = gutters.get(i);
            if (mode == field(gutter, "mode")) {
                return gutter;
            }
        }
        return null;
    }

    private static void assertAxisGutter(AxisGutterPanel gutter,
                                         ParameterId expectedAxis,
                                         String expectedShortName,
                                         List<Object> expectedValues)
            throws Exception {
        assertNotNull(gutter);
        assertEquals(expectedAxis, gutterAxis(gutter));
        assertEquals(expectedShortName, ParameterLabels.shortKey(gutterAxis(gutter)));
        assertEquals(expectedValues, gutterValues(gutter));
    }

    private static ParameterId gutterAxis(AxisGutterPanel gutter) throws Exception {
        return (ParameterId) field(gutter, "axis");
    }

    @SuppressWarnings("unchecked")
    private static List<Object> gutterValues(AxisGutterPanel gutter) throws Exception {
        return new ArrayList<Object>((List<Object>) field(gutter, "values"));
    }

    private static int ribbonCount(List<VariationCellPanel> cells, String fieldName)
            throws Exception {
        int count = 0;
        for (int i = 0; i < cells.size(); i++) {
            if (((Boolean) field(cells.get(i), fieldName)).booleanValue()) {
                count++;
            }
        }
        return count;
    }

    private static List<VariationCellPanel> visibleCells(VariationGridPanel grid) {
        return descendants(grid, VariationCellPanel.class);
    }

    private static Set<String> comboIds(List<VariationCellPanel> cells) {
        Set<String> ids = new LinkedHashSet<String>();
        for (int i = 0; i < cells.size(); i++) {
            ids.add(cells.get(i).combo().toCanonicalJson());
        }
        return ids;
    }

    private static void assertVisibleFacetValue(List<VariationCellPanel> cells,
                                                double expected) {
        for (int i = 0; i < cells.size(); i++) {
            Object value = cells.get(i).combo().get(ParameterId.QUALITY_MIN);
            assertEquals(Double.valueOf(expected), value);
        }
    }

    private static void assertChipTexts(FacetChipRow row, List<String> expected) {
        List<JButton> buttons = descendants(row, JButton.class);
        List<String> actual = new ArrayList<String>();
        for (int i = 0; i < buttons.size(); i++) {
            String text = buttons.get(i).getText();
            if (text != null && text.length() > 0) {
                actual.add(text);
            }
        }
        assertEquals(expected, actual);
    }

    private static JButton buttonWithText(Component root, String text) {
        List<JButton> buttons = descendants(root, JButton.class);
        for (int i = 0; i < buttons.size(); i++) {
            JButton button = buttons.get(i);
            if (text.equals(button.getText())) {
                return button;
            }
        }
        return null;
    }

    private static VariationMontageDialog montageDialog(VariationsDialog dialog)
            throws Exception {
        return (VariationMontageDialog) field(dialog, "montageDialog");
    }

    private static void assertFooterOrder(VariationMontageDialog montage,
                                          List<String> expectedLabels) {
        List<String> actual = visibleControlLabels(montage);
        int cursor = 0;
        for (int i = 0; i < actual.size() && cursor < expectedLabels.size(); i++) {
            if (expectedLabels.get(cursor).equals(actual.get(i))) {
                cursor++;
            }
        }
        assertEquals("Footer labels in visual order: " + actual,
                expectedLabels.size(), cursor);
    }

    private static List<String> visibleControlLabels(Component root) {
        List<String> labels = new ArrayList<String>();
        collectVisibleControlLabels(root, labels);
        return labels;
    }

    private static void collectVisibleControlLabels(Component component,
                                                    List<String> labels) {
        if (component == null || !component.isVisible()) {
            return;
        }
        if (component instanceof JComboBox) {
            Object selected = ((JComboBox<?>) component).getSelectedItem();
            if (selected != null) {
                labels.add(selected.toString());
            }
            return;
        }
        if (component instanceof AbstractButton) {
            String text = ((AbstractButton) component).getText();
            if (text != null && text.length() > 0) {
                labels.add(text);
            }
        } else if (component instanceof JLabel) {
            String text = ((JLabel) component).getText();
            if (text != null && text.length() > 0) {
                labels.add(text);
            }
        }
        if (component instanceof Container) {
            Component[] children = ((Container) component).getComponents();
            for (int i = 0; i < children.length; i++) {
                collectVisibleControlLabels(children[i], labels);
            }
        }
    }

    private static <T> List<T> descendants(Component root, Class<T> type) {
        List<T> out = new ArrayList<T>();
        collectDescendants(root, type, out);
        return out;
    }

    private static <T> void collectDescendants(Component component,
                                               Class<T> type,
                                               List<T> out) {
        if (component == null) {
            return;
        }
        if (type.isInstance(component)) {
            out.add(type.cast(component));
        }
        if (component instanceof Container) {
            Component[] children = ((Container) component).getComponents();
            for (int i = 0; i < children.length; i++) {
                collectDescendants(children[i], type, out);
            }
        }
    }

    private static Object invoke(Object target, String methodName,
                                 Class<?>[] parameterTypes,
                                 Object... args) throws Exception {
        Method method = target.getClass().getDeclaredMethod(methodName,
                parameterTypes);
        method.setAccessible(true);
        return method.invoke(target, args);
    }

    private static Object field(Object target, String fieldName) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.get(target);
    }

    private static void dispose(final VariationsDialog dialog) throws Exception {
        if (dialog == null) {
            return;
        }
        runOnEdt(new CheckedRunnable() {
            @Override public void run() {
                dialog.dispose();
            }
        });
    }

    private interface CheckedRunnable {
        void run() throws Exception;
    }

    private static void runOnEdt(final CheckedRunnable runnable) throws Exception {
        if (SwingUtilities.isEventDispatchThread()) {
            runnable.run();
            return;
        }
        final AtomicReference<Throwable> error = new AtomicReference<Throwable>();
        SwingUtilities.invokeAndWait(new Runnable() {
            @Override public void run() {
                try {
                    runnable.run();
                } catch (Throwable t) {
                    error.set(t);
                }
            }
        });
        Throwable thrown = error.get();
        if (thrown instanceof Exception) {
            throw (Exception) thrown;
        }
        if (thrown instanceof Error) {
            throw (Error) thrown;
        }
    }
}
