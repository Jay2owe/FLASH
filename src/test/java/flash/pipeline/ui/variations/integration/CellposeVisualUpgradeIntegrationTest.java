package flash.pipeline.ui.variations.integration;

import flash.pipeline.ui.config.CellposeParameterStage;
import flash.pipeline.ui.preview.VariationMontageDialog;
import flash.pipeline.ui.variations.AxisGutterPanel;
import flash.pipeline.ui.variations.CountCurveStrip;
import flash.pipeline.ui.variations.CropSpec;
import flash.pipeline.ui.variations.FacetChipRow;
import flash.pipeline.ui.variations.ParameterCombo;
import flash.pipeline.ui.variations.ParameterId;
import flash.pipeline.ui.variations.ParameterSweep;
import flash.pipeline.ui.variations.ParameterValueList;
import flash.pipeline.ui.variations.PrimaryAxisPicker;
import flash.pipeline.ui.variations.VariationCellPanel;
import flash.pipeline.ui.variations.VariationEngineContext;
import flash.pipeline.ui.variations.VariationGridPanel;
import flash.pipeline.ui.variations.VariationResult;
import flash.pipeline.ui.variations.VariationStrategy;
import flash.pipeline.ui.variations.VariationsDialog;

import ij.ImagePlus;
import ij.process.ByteProcessor;
import ij.process.ShortProcessor;

import org.junit.After;
import org.junit.Assume;
import org.junit.Test;

import javax.swing.AbstractButton;
import javax.swing.JButton;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.awt.GraphicsEnvironment;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class CellposeVisualUpgradeIntegrationTest {

    private final List<VariationsDialog> dialogs = new ArrayList<VariationsDialog>();

    @After
    public void disposeDialogs() throws Exception {
        for (int i = dialogs.size() - 1; i >= 0; i--) {
            final VariationsDialog dialog = dialogs.get(i);
            if (dialog != null) {
                SwingUtilities.invokeAndWait(new Runnable() {
                    @Override public void run() {
                        dialog.dispose();
                    }
                });
            }
        }
        dialogs.clear();
    }

    @Test
    public void twoAxisCellposeSweepUsesCellprobTopGutterAndSparklineDriver()
            throws Exception {
        ParameterSweep sweep = diameterByCellprobSweep();
        VariationsDialog dialog = runSyntheticDialog(sweep);
        VariationGridPanel grid = gridPanel(dialog);

        assertEquals(6, cellsForTest(grid).size());
        assertEquals(ParameterId.CELLPROB_THRESHOLD,
                PrimaryAxisPicker.pickCountCurveDriver(sweep));

        AxisGutterPanel top = singleGutter(grid, AxisGutterPanel.Mode.TOP);
        assertEquals(ParameterId.CELLPROB_THRESHOLD, gutterAxis(top));
        assertEquals(Arrays.<Object>asList(Double.valueOf(-1.0d), Double.valueOf(0.0d)),
                gutterValues(top));

        AxisGutterPanel left = singleGutter(grid, AxisGutterPanel.Mode.LEFT);
        assertEquals(ParameterId.DIAMETER, gutterAxis(left));
        assertEquals(Arrays.<Object>asList(Double.valueOf(8.0d), Double.valueOf(12.0d),
                        Double.valueOf(16.0d)),
                gutterValues(left));

        assertFalse("Expected a visible count sparkline strip.",
                visibleComponents(dialogWindow(dialog), CountCurveStrip.class).isEmpty());
    }

    @Test
    public void modelOnlyCellposeSweepUsesFacetChipsWithoutSparklineOrStableCount()
            throws Exception {
        ParameterSweep sweep = modelOnlySweep();
        VariationsDialog dialog = runSyntheticDialog(sweep);
        VariationGridPanel grid = gridPanel(dialog);

        assertTrue(findAll(grid, AxisGutterPanel.class).isEmpty());
        assertEquals(3, modelChipValues(singleFacetRow(grid)).size());
        assertEquals(ParameterId.MODEL, modelChipAxis(singleFacetRow(grid)));
        assertNull(PrimaryAxisPicker.pickCountCurveDriver(sweep));
        assertTrue("MODEL-only sweeps must not render a count sparkline.",
                findAll(dialogWindow(dialog), CountCurveStrip.class).isEmpty());

        List<VariationCellPanel> cells = cellsForTest(grid);
        assertEquals(3, cells.size());
        assertEquals(0, countBooleanField(cells, "kneeWinner"));
        assertTrue("At most one tile may carry STABLE MASKS.",
                countBooleanField(cells, "stabilityWinner") <= 1);
    }

    @Test
    public void montageLauncherForModelOnlyCellposeSweepShowsModelChips()
            throws Exception {
        VariationsDialog dialog = runSyntheticDialog(modelOnlySweep());

        SwingUtilities.invokeAndWait(new Runnable() {
            @Override public void run() {
                JButton launcher = button(dialogWindow(dialog), "Open in large montage");
                assertNotNull(launcher);
                assertTrue(launcher.isEnabled());
                launcher.doClick();
            }
        });

        VariationMontageDialog montage =
                (VariationMontageDialog) fieldValue(dialog, "montageDialog");
        assertNotNull(montage);
        assertEquals(3, ((Integer) invoke(montage, "tileCountForTest",
                new Class<?>[0])).intValue());

        assertTrue(findAll(montage.getContentPane(), AxisGutterPanel.class).isEmpty());
        FacetChipRow row = singleFacetRow(montage.getContentPane());
        assertEquals(ParameterId.MODEL, modelChipAxis(row));
        assertEquals(Arrays.<Object>asList("cyto3", "nuclei", "cyto2"),
                modelChipValues(row));
    }

    private VariationsDialog runSyntheticDialog(final ParameterSweep sweep)
            throws Exception {
        Assume.assumeFalse("PipelineDialog creates Swing dialogs in these tests.",
                GraphicsEnvironment.isHeadless());

        final VariationsDialog[] holder = new VariationsDialog[1];
        SwingUtilities.invokeAndWait(new Runnable() {
            @Override public void run() {
                try {
                    VariationsDialog dialog = new VariationsDialog(null,
                            cellposeContext(), null);
                    invoke(dialog, "setSweepForTest",
                            new Class<?>[] { ParameterSweep.class }, sweep);
                    invoke(dialog, "setStrategyForTest",
                            new Class<?>[] { VariationStrategy.class },
                            new SyntheticCellposeStrategy());
                    dialog.start();
                    holder[0] = dialog;
                    dialogs.add(dialog);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        });
        invoke(holder[0], "waitForDoneForTest",
                new Class<?>[] { long.class }, Long.valueOf(5000L));
        return holder[0];
    }

    private static VariationEngineContext cellposeContext() {
        ImagePlus source = new ImagePlus("synthetic-cellpose-source",
                new ByteProcessor(16, 16));
        CellposeParameterStage.Parameters parameters =
                new CellposeParameterStage.Parameters("cyto3", -1,
                        12.0d, 0.4d, -1.0d, false);
        return VariationEngineContext.forCellpose("DAPI", source, source,
                null, parameters, null);
    }

    private static ParameterSweep diameterByCellprobSweep() {
        Map<ParameterId, ParameterValueList> values =
                new LinkedHashMap<ParameterId, ParameterValueList>();
        values.put(ParameterId.DIAMETER,
                ParameterValueList.ofDoubles(8.0d, 12.0d, 16.0d));
        values.put(ParameterId.CELLPROB_THRESHOLD,
                ParameterValueList.ofDoubles(-1.0d, 0.0d));
        return new ParameterSweep(ParameterSweep.Method.CELLPOSE, values,
                CropSpec.full(), "DAPI", "synthetic-cellpose-2-axis");
    }

    private static ParameterSweep modelOnlySweep() {
        Map<ParameterId, ParameterValueList> values =
                new LinkedHashMap<ParameterId, ParameterValueList>();
        values.put(ParameterId.DIAMETER, ParameterValueList.ofDoubles(12.0d));
        values.put(ParameterId.FLOW_THRESHOLD, ParameterValueList.ofDoubles(0.4d));
        values.put(ParameterId.CELLPROB_THRESHOLD,
                ParameterValueList.ofDoubles(-1.0d));
        values.put(ParameterId.MODEL,
                ParameterValueList.ofStrings("cyto3", "nuclei", "cyto2"));
        return new ParameterSweep(ParameterSweep.Method.CELLPOSE, values,
                CropSpec.full(), "DAPI", "synthetic-cellpose-model-only");
    }

    private static VariationGridPanel gridPanel(VariationsDialog dialog)
            throws Exception {
        return (VariationGridPanel) invoke(dialog, "gridPanelForTest",
                new Class<?>[0]);
    }

    @SuppressWarnings("unchecked")
    private static List<VariationCellPanel> cellsForTest(VariationGridPanel grid)
            throws Exception {
        return (List<VariationCellPanel>) invoke(grid, "cellsForTest",
                new Class<?>[0]);
    }

    private static Container dialogWindow(VariationsDialog dialog) {
        return (Container) dialog.getWindow();
    }

    private static AxisGutterPanel singleGutter(Container root,
                                               AxisGutterPanel.Mode mode)
            throws Exception {
        List<AxisGutterPanel> gutters = findAll(root, AxisGutterPanel.class);
        AxisGutterPanel found = null;
        for (int i = 0; i < gutters.size(); i++) {
            AxisGutterPanel gutter = gutters.get(i);
            if (mode == fieldValue(gutter, "mode")) {
                if (found != null) {
                    throw new AssertionError("Expected one " + mode + " gutter.");
                }
                found = gutter;
            }
        }
        assertNotNull("Expected " + mode + " gutter.", found);
        return found;
    }

    private static ParameterId gutterAxis(AxisGutterPanel gutter) throws Exception {
        return (ParameterId) fieldValue(gutter, "axis");
    }

    @SuppressWarnings("unchecked")
    private static List<Object> gutterValues(AxisGutterPanel gutter) throws Exception {
        return new ArrayList<Object>((List<Object>) fieldValue(gutter, "values"));
    }

    private static FacetChipRow singleFacetRow(Container root) {
        List<FacetChipRow> rows = findAll(root, FacetChipRow.class);
        assertEquals("Expected one facet chip row.", 1, rows.size());
        return rows.get(0);
    }

    @SuppressWarnings("unchecked")
    private static ParameterId modelChipAxis(FacetChipRow row) throws Exception {
        Map<ParameterId, List<Object>> values =
                (Map<ParameterId, List<Object>>) fieldValue(row, "valuesByAxis");
        assertEquals(1, values.size());
        assertTrue(values.containsKey(ParameterId.MODEL));
        return new ArrayList<ParameterId>(values.keySet()).get(0);
    }

    @SuppressWarnings("unchecked")
    private static List<Object> modelChipValues(FacetChipRow row) throws Exception {
        Map<ParameterId, List<Object>> values =
                (Map<ParameterId, List<Object>>) fieldValue(row, "valuesByAxis");
        return new ArrayList<Object>(values.get(ParameterId.MODEL));
    }

    private static int countBooleanField(List<VariationCellPanel> cells,
                                         String fieldName) throws Exception {
        int count = 0;
        for (int i = 0; i < cells.size(); i++) {
            if (((Boolean) fieldValue(cells.get(i), fieldName)).booleanValue()) {
                count++;
            }
        }
        return count;
    }

    private static JButton button(Container root, String text) {
        List<AbstractButton> buttons = findAll(root, AbstractButton.class);
        for (int i = 0; i < buttons.size(); i++) {
            AbstractButton button = buttons.get(i);
            if (text.equals(button.getText()) && button instanceof JButton) {
                return (JButton) button;
            }
        }
        return null;
    }

    private static <T> List<T> visibleComponents(Component root, Class<T> type) {
        List<T> all = findAll(root, type);
        List<T> visible = new ArrayList<T>();
        for (int i = 0; i < all.size(); i++) {
            Component component = (Component) all.get(i);
            if (component.isVisible()) {
                visible.add(all.get(i));
            }
        }
        return visible;
    }

    private static <T> List<T> findAll(Component root, Class<T> type) {
        List<T> out = new ArrayList<T>();
        collect(root, type, out);
        return out;
    }

    private static <T> void collect(Component component, Class<T> type,
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
                collect(children[i], type, out);
            }
        }
    }

    private static Object invoke(Object target, String name, Class<?>[] types,
                                 Object... args) throws Exception {
        Method method = target.getClass().getDeclaredMethod(name, types);
        method.setAccessible(true);
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception) {
                throw (Exception) cause;
            }
            if (cause instanceof Error) {
                throw (Error) cause;
            }
            throw e;
        }
    }

    private static Object fieldValue(Object target, String name) throws Exception {
        Field field = field(target.getClass(), name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static Field field(Class<?> type, String name)
            throws NoSuchFieldException {
        Class<?> current = type;
        while (current != null) {
            try {
                return current.getDeclaredField(name);
            } catch (NoSuchFieldException e) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }

    private static final class SyntheticCellposeStrategy
            implements VariationStrategy {
        @Override public void dispatch(ParameterSweep sweep,
                                       Consumer<VariationResult> publisher,
                                       BooleanSupplier cancelCheck) {
            List<ParameterCombo> combos = sweep.combos();
            for (int i = 0; i < combos.size(); i++) {
                if (cancelCheck != null && cancelCheck.getAsBoolean()) {
                    return;
                }
                ParameterCombo combo = combos.get(i);
                int count = objectCount(combo, i);
                publisher.accept(VariationResult.success(combo,
                        syntheticLabel(i), count, 1L, null));
            }
        }

        private static int objectCount(ParameterCombo combo, int index) {
            Object cellprob = combo.get(ParameterId.CELLPROB_THRESHOLD);
            Object diameter = combo.get(ParameterId.DIAMETER);
            if (cellprob instanceof Number && diameter instanceof Number) {
                int byCellprob = ((Number) cellprob).doubleValue() < -0.5d ? 24 : 18;
                int byDiameter = (int) Math.round(
                        (((Number) diameter).doubleValue() - 8.0d) / 4.0d);
                return byCellprob - byDiameter;
            }
            return 12 + index;
        }

        private static ImagePlus syntheticLabel(int index) {
            ShortProcessor processor = new ShortProcessor(16, 16);
            int offset = index % 2;
            for (int y = 4 + offset; y < 12 + offset && y < 16; y++) {
                for (int x = 4; x < 12; x++) {
                    processor.set(x, y, 1);
                }
            }
            return new ImagePlus("synthetic-cellpose-label-" + index, processor);
        }
    }
}
