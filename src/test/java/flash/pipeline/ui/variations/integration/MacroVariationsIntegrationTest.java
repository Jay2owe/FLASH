package flash.pipeline.ui.variations.integration;

import flash.pipeline.image.ImageOps;
import flash.pipeline.ui.config.CellposeParameterStage;
import flash.pipeline.ui.config.ConfigQcContext;
import flash.pipeline.ui.config.StarDistParameterStage;
import flash.pipeline.ui.variations.AxisGutterPanel;
import flash.pipeline.ui.variations.CountCurveStrip;
import flash.pipeline.ui.variations.CropSpec;
import flash.pipeline.ui.variations.FacetChipRow;
import flash.pipeline.ui.variations.MacroPreprocessor;
import flash.pipeline.ui.variations.MacroToken;
import flash.pipeline.ui.variations.MacroVariation;
import flash.pipeline.ui.variations.MacroVariationSet;
import flash.pipeline.ui.variations.ParameterCombo;
import flash.pipeline.ui.variations.ParameterId;
import flash.pipeline.ui.variations.ParameterSweep;
import flash.pipeline.ui.variations.ParameterValueList;
import flash.pipeline.ui.variations.PrimaryAxisPicker;
import flash.pipeline.ui.variations.VariationCache;
import flash.pipeline.ui.variations.VariationCellPanel;
import flash.pipeline.ui.variations.VariationEngineContext;
import flash.pipeline.ui.variations.VariationGridPanel;
import flash.pipeline.ui.variations.VariationResult;
import flash.pipeline.ui.variations.VariationStrategy;
import flash.pipeline.ui.variations.VariationsDialog;
import flash.pipeline.ui.variations.strategy.CellposeOneShot;
import flash.pipeline.ui.variations.strategy.CellposePersistent;
import flash.pipeline.ui.variations.strategy.ClassicalSweep;
import flash.pipeline.ui.variations.strategy.StarDistPerCell;

import ij.ImagePlus;
import ij.ImageStack;
import ij.measure.ResultsTable;
import ij.process.ByteProcessor;
import ij.process.ImageProcessor;
import ij.process.ShortProcessor;

import org.junit.After;
import org.junit.Assume;
import org.junit.Test;

import javax.swing.AbstractButton;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.awt.GraphicsEnvironment;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class MacroVariationsIntegrationTest {

    private static final long DIALOG_TIMEOUT_MS = 5000L;
    private final List<VariationsDialog> dialogs = new ArrayList<VariationsDialog>();

    @After
    public void disposeDialogs() throws Exception {
        for (int i = dialogs.size() - 1; i >= 0; i--) {
            dispose(dialogs.get(i));
        }
        dialogs.clear();
    }

    @Test
    public void twoMacroByThreeThresholdClassicalDialogUsesMacroFacetAndThresholdSparkline()
            throws Exception {
        assumeDialogAvailable();
        MacroFixture fixture = macroFixture();
        ParameterSweep sweep = macroThresholdSweep(fixture);
        VariationsDialog dialog = runSyntheticClassicalDialog(sweep);
        VariationGridPanel grid = gridPanel(dialog);

        assertEquals(6, cellCount(dialog));
        assertEquals(6, completedCount(dialog));
        assertEquals(ParameterId.THRESHOLD,
                PrimaryAxisPicker.pickCountCurveDriver(sweep));
        assertFalse(PrimaryAxisPicker.sweptNumericAxes(sweep)
                .contains(ParameterId.MACRO));

        AxisGutterPanel top = singleGutter(grid, AxisGutterPanel.Mode.TOP);
        assertEquals(ParameterId.THRESHOLD, gutterAxis(top));
        assertEquals(Arrays.<Object>asList(Integer.valueOf(80),
                        Integer.valueOf(120), Integer.valueOf(160)),
                gutterValues(top));
        assertNull(gutter(grid, AxisGutterPanel.Mode.LEFT));

        FacetChipRow row = singleFacetRow(grid);
        assertTrue(row.selectedValues().containsKey(ParameterId.MACRO));
        assertChipDisplayNames(row, fixture.none, fixture.median);

        CountCurveStrip strip = mainCountCurve(dialog);
        assertNotNull("Expected threshold count sparkline.", strip);
        assertTrue(strip.isVisible());

        List<VariationCellPanel> cells = cellsForTest(grid);
        assertTrue("STABLE COUNT should mark at most one threshold tile.",
                countBooleanField(cells, "kneeWinner") <= 1);
        assertTrue("STABLE MASKS should mark at most one tile.",
                countBooleanField(cells, "stabilityWinner") <= 1);
    }

    @Test
    public void macroOnlyClassicalDialogUsesFacetChipsWithoutSpatialSignals()
            throws Exception {
        assumeDialogAvailable();
        MacroFixture fixture = macroFixture();
        ParameterSweep sweep = macroOnlySweep(fixture);
        VariationsDialog dialog = runSyntheticClassicalDialog(sweep);
        VariationGridPanel grid = gridPanel(dialog);

        assertEquals(3, cellCount(dialog));
        assertEquals(3, completedCount(dialog));
        assertTrue(findAll(grid, AxisGutterPanel.class).isEmpty());

        FacetChipRow row = singleFacetRow(grid);
        assertTrue(row.selectedValues().containsKey(ParameterId.MACRO));
        assertChipDisplayNames(row, fixture.none, fixture.blur, fixture.median);

        assertNull(PrimaryAxisPicker.pickCountCurveDriver(sweep));
        assertNull("Macro-only sweeps must not create a count sparkline.",
                mainCountCurve(dialog));

        List<VariationCellPanel> cells = cellsForTest(grid);
        assertEquals(0, countBooleanField(cells, "kneeWinner"));
        assertTrue("Three completed macro-only tiles may run STABLE MASKS.",
                countBooleanField(cells, "stabilityWinner") <= 1);
    }

    @Test
    public void classicalMacroPreprocessingChangesCountsBeforeThresholding()
            throws Exception {
        ImagePlus source = lowSignalBlobStack();
        MacroVariation boost = MacroVariation.pasted("Boost +100",
                "run(\"Add...\", \"value=100 stack\");");
        ParameterSweep sweep = classicalExecutionSweep(boost);
        ClassicalSweep strategy = new ClassicalSweep(source, CropSpec.full(), null,
                new VariationIntegrationTestSupport.ThresholdingPreviewAdapter(),
                1);
        List<VariationResult> results =
                Collections.synchronizedList(new ArrayList<VariationResult>());

        try {
            strategy.dispatch(sweep, new Consumer<VariationResult>() {
                @Override public void accept(VariationResult result) {
                    results.add(result);
                }
            }, new BooleanSupplier() {
                @Override public boolean getAsBoolean() {
                    return false;
                }
            });

            assertEquals(6, results.size());
            Map<String, Integer> counts = countsByMacroAndThreshold(results);
            assertEquals(Integer.valueOf(0), counts.get(key(MacroToken.NONE_VALUE, 80)));
            assertEquals(Integer.valueOf(0), counts.get(key(MacroToken.NONE_VALUE, 120)));
            assertEquals(Integer.valueOf(0), counts.get(key(MacroToken.NONE_VALUE, 160)));
            assertEquals(Integer.valueOf(1), counts.get(key(boost.token(), 80)));
            assertEquals(Integer.valueOf(1), counts.get(key(boost.token(), 120)));
            assertEquals(Integer.valueOf(0), counts.get(key(boost.token(), 160)));
            assertEquals(50.0f, source.getStack().getProcessor(1).getf(8, 8),
                    0.001f);
        } finally {
            source.flush();
        }
    }

    @Test
    public void starDistAdapterReceivesMacroPreprocessedInput()
            throws Exception {
        MacroVariation boost = MacroVariation.pasted("Boost +7",
                "run(\"Add...\", \"value=7 stack\");");
        RecordingStarDistAdapter adapter = new RecordingStarDistAdapter();
        StarDistPerCell strategy = new StarDistPerCell(uniformImage("stardist", 3),
                CropSpec.full(), null, adapter,
                VariationIntegrationTestSupport.starDistBaseParameters());
        List<VariationResult> results = new ArrayList<VariationResult>();

        strategy.dispatch(starDistMacroSweep(boost),
                new Consumer<VariationResult>() {
                    @Override public void accept(VariationResult result) {
                        results.add(result);
                    }
                },
                new BooleanSupplier() {
                    @Override public boolean getAsBoolean() {
                        return false;
                    }
                });

        assertEquals(2, results.size());
        assertEquals(Arrays.asList(Float.valueOf(3.0f), Float.valueOf(10.0f)),
                adapter.firstPixels);
        assertAllSuccessful(results);
    }

    @Test
    public void cellposeAdapterReceivesMacroPreprocessedInput() {
        MacroVariation boost = MacroVariation.pasted("Boost +7",
                "run(\"Add...\", \"value=7 stack\");");
        RecordingCellposeAdapter adapter = new RecordingCellposeAdapter();
        CellposeOneShot strategy = new CellposeOneShot(uniformImage("cellpose", 3),
                CropSpec.full(), null, adapter,
                VariationIntegrationTestSupport.cellposeBaseParameters(), null);
        List<VariationResult> results = new ArrayList<VariationResult>();

        strategy.dispatch(cellposeMacroSweep(boost),
                new Consumer<VariationResult>() {
                    @Override public void accept(VariationResult result) {
                        results.add(result);
                    }
                },
                new BooleanSupplier() {
                    @Override public boolean getAsBoolean() {
                        return false;
                    }
                });

        assertEquals(2, results.size());
        assertEquals(Arrays.asList(Float.valueOf(3.0f), Float.valueOf(10.0f)),
                adapter.firstPixels);
        assertAllSuccessful(results);
    }

    @Test
    public void unchangedMacroScriptReusesCachedLabel() {
        String script = "run(\"Median...\", \"radius=1 stack\");";
        MacroVariation first = MacroVariation.pasted("Median r=1", script);
        MacroVariation renamed = MacroVariation.pasted("Renamed median",
                script + "\r\n");
        ParameterCombo combo = comboFor(first.token(), 120);
        VariationCache cache = new VariationCache(uniqueTargetDir("macro-cache-reuse"));
        ImagePlus label = singleLabelImage("cached-label", 1);

        String firstKey = VariationCache.keyFor(singleMacroThresholdSweep(first),
                combo);
        cache.put(firstKey, label);
        String renamedKey = VariationCache.keyFor(
                singleMacroThresholdSweep(renamed), combo);

        assertEquals(first.token(), renamed.token());
        assertEquals(firstKey, renamedKey);
        assertSame(label, cache.get(renamedKey));
    }

    @Test
    public void changedMacroScriptInvalidatesCachedLabel() {
        MacroVariation sigmaOne = MacroVariation.pasted("Median r=1",
                "run(\"Median...\", \"radius=1 stack\");");
        MacroVariation sigmaTwo = MacroVariation.pasted("Median r=2",
                "run(\"Median...\", \"radius=2 stack\");");
        VariationCache cache = new VariationCache(uniqueTargetDir("macro-cache-invalid"));
        String firstKey = VariationCache.keyFor(singleMacroThresholdSweep(sigmaOne),
                comboFor(sigmaOne.token(), 120));
        cache.put(firstKey, singleLabelImage("cached-label", 1));

        String editedKey = VariationCache.keyFor(singleMacroThresholdSweep(sigmaTwo),
                comboFor(sigmaTwo.token(), 120));

        assertNotEquals(firstKey, editedKey);
        assertNull(cache.get(editedKey));
    }

    @Test
    public void macroVariationExecutionClassesDoNotReferenceImageJDuplicator()
            throws Exception {
        assertClassDoesNotReferenceDuplicator(MacroPreprocessor.class);
        assertClassDoesNotReferenceDuplicator(ImageOps.class);
        assertClassDoesNotReferenceDuplicator(ClassicalSweep.class);
        assertClassDoesNotReferenceDuplicator(StarDistPerCell.class);
        assertClassDoesNotReferenceDuplicator(CellposeOneShot.class);
        assertClassDoesNotReferenceDuplicator(CellposePersistent.class);
    }

    private VariationsDialog runSyntheticClassicalDialog(final ParameterSweep sweep)
            throws Exception {
        final AtomicReference<VariationsDialog> ref =
                new AtomicReference<VariationsDialog>();
        SwingUtilities.invokeAndWait(new Runnable() {
            @Override public void run() {
                try {
                    VariationsDialog dialog = new VariationsDialog(null,
                            classicalDialogContext(), null);
                    invoke(dialog, "setSweepForTest",
                            new Class<?>[] { ParameterSweep.class }, sweep);
                    invoke(dialog, "setStrategyForTest",
                            new Class<?>[] { VariationStrategy.class },
                            new SyntheticClassicalStrategy());
                    dialog.start();
                    dialogs.add(dialog);
                    ref.set(dialog);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        });
        VariationsDialog dialog = ref.get();
        invoke(dialog, "waitForDoneForTest",
                new Class<?>[] { long.class }, Long.valueOf(DIALOG_TIMEOUT_MS));
        return dialog;
    }

    private static VariationEngineContext classicalDialogContext() {
        ImagePlus source = lowSignalBlobStack();
        ConfigQcContext config = ConfigQcContext.fromImages(new File("."),
                uniqueTargetDir("macro-dialog-bin"),
                null,
                Collections.singletonList(source),
                Collections.singletonList("DAPI"),
                0);
        ParameterCombo base = ParameterCombo.builder()
                .put(ParameterId.THRESHOLD, Integer.valueOf(120))
                .put(ParameterId.MIN_SIZE, Integer.valueOf(1))
                .put(ParameterId.MAX_SIZE, Integer.valueOf(Integer.MAX_VALUE))
                .build();
        return VariationEngineContext.forClassical("DAPI", source, source,
                config, base,
                new VariationIntegrationTestSupport.ThresholdingPreviewAdapter());
    }

    private static ParameterSweep macroThresholdSweep(MacroFixture fixture) {
        Map<ParameterId, ParameterValueList> values =
                new LinkedHashMap<ParameterId, ParameterValueList>();
        values.put(ParameterId.THRESHOLD,
                ParameterValueList.ofInts(80, 120, 160));
        values.put(ParameterId.MIN_SIZE, ParameterValueList.ofInts(1));
        values.put(ParameterId.MAX_SIZE,
                ParameterValueList.ofInts(Integer.MAX_VALUE));
        values.put(ParameterId.MACRO,
                ParameterValueList.ofStrings(fixture.none.token(),
                        fixture.median.token()));
        return new ParameterSweep(ParameterSweep.Method.CLASSICAL, values,
                CropSpec.full(), "DAPI", "macro-2x3",
                MacroVariationSet.of(fixture.median));
    }

    private static ParameterSweep macroOnlySweep(MacroFixture fixture) {
        Map<ParameterId, ParameterValueList> values =
                new LinkedHashMap<ParameterId, ParameterValueList>();
        values.put(ParameterId.THRESHOLD, ParameterValueList.ofInts(120));
        values.put(ParameterId.MIN_SIZE, ParameterValueList.ofInts(1));
        values.put(ParameterId.MAX_SIZE,
                ParameterValueList.ofInts(Integer.MAX_VALUE));
        values.put(ParameterId.MACRO,
                ParameterValueList.ofStrings(fixture.none.token(),
                        fixture.blur.token(), fixture.median.token()));
        return new ParameterSweep(ParameterSweep.Method.CLASSICAL, values,
                CropSpec.full(), "DAPI", "macro-only",
                MacroVariationSet.of(fixture.blur, fixture.median));
    }

    private static ParameterSweep classicalExecutionSweep(MacroVariation boost) {
        Map<ParameterId, ParameterValueList> values =
                new LinkedHashMap<ParameterId, ParameterValueList>();
        values.put(ParameterId.THRESHOLD,
                ParameterValueList.ofInts(80, 120, 160));
        values.put(ParameterId.MIN_SIZE, ParameterValueList.ofInts(1));
        values.put(ParameterId.MAX_SIZE,
                ParameterValueList.ofInts(Integer.MAX_VALUE));
        values.put(ParameterId.MACRO,
                ParameterValueList.ofStrings(MacroToken.NONE_VALUE,
                        boost.token()));
        return new ParameterSweep(ParameterSweep.Method.CLASSICAL, values,
                CropSpec.full(), "DAPI", "macro-classical-execution",
                MacroVariationSet.of(boost));
    }

    private static ParameterSweep starDistMacroSweep(MacroVariation boost) {
        Map<ParameterId, ParameterValueList> values =
                new LinkedHashMap<ParameterId, ParameterValueList>();
        values.put(ParameterId.PROB_THRESH, ParameterValueList.ofDoubles(0.5d));
        values.put(ParameterId.NMS_THRESH, ParameterValueList.ofDoubles(0.3d));
        values.put(ParameterId.MACRO,
                ParameterValueList.ofStrings(MacroToken.NONE_VALUE,
                        boost.token()));
        return new ParameterSweep(ParameterSweep.Method.STARDIST, values,
                CropSpec.full(), "DAPI", "macro-stardist-input",
                MacroVariationSet.of(boost));
    }

    private static ParameterSweep cellposeMacroSweep(MacroVariation boost) {
        Map<ParameterId, ParameterValueList> values =
                new LinkedHashMap<ParameterId, ParameterValueList>();
        values.put(ParameterId.DIAMETER, ParameterValueList.ofDoubles(12.0d));
        values.put(ParameterId.FLOW_THRESHOLD, ParameterValueList.ofDoubles(0.4d));
        values.put(ParameterId.CELLPROB_THRESHOLD,
                ParameterValueList.ofDoubles(-1.0d));
        values.put(ParameterId.MACRO,
                ParameterValueList.ofStrings(MacroToken.NONE_VALUE,
                        boost.token()));
        return new ParameterSweep(ParameterSweep.Method.CELLPOSE, values,
                CropSpec.full(), "DAPI", "macro-cellpose-input",
                MacroVariationSet.of(boost));
    }

    private static ParameterSweep singleMacroThresholdSweep(MacroVariation macro) {
        Map<ParameterId, ParameterValueList> values =
                new LinkedHashMap<ParameterId, ParameterValueList>();
        values.put(ParameterId.THRESHOLD, ParameterValueList.ofInts(120));
        values.put(ParameterId.MACRO,
                ParameterValueList.ofStrings(macro.token()));
        return new ParameterSweep(ParameterSweep.Method.CLASSICAL, values,
                CropSpec.full(), "DAPI", "macro-cache-image",
                MacroVariationSet.of(macro));
    }

    private static ParameterCombo comboFor(String macroToken, int threshold) {
        return ParameterCombo.builder()
                .put(ParameterId.THRESHOLD, Integer.valueOf(threshold))
                .put(ParameterId.MACRO, macroToken)
                .build();
    }

    private static ImagePlus lowSignalBlobStack() {
        ImageStack stack = new ImageStack(24, 24);
        for (int z = 0; z < 3; z++) {
            ShortProcessor processor = new ShortProcessor(24, 24);
            for (int y = 6; y < 18; y++) {
                for (int x = 6; x < 18; x++) {
                    processor.set(x, y, 50);
                }
            }
            stack.addSlice("z" + (z + 1), processor);
        }
        ImagePlus image = new ImagePlus("low-signal-macro-fixture", stack);
        image.setDimensions(1, 3, 1);
        return image;
    }

    private static ImagePlus uniformImage(String title, int value) {
        ByteProcessor processor = new ByteProcessor(8, 8);
        processor.setValue(value);
        processor.fill();
        return new ImagePlus(title, processor);
    }

    private static ImagePlus singleLabelImage(String title, int label) {
        ShortProcessor processor = new ShortProcessor(8, 8);
        for (int y = 2; y < 6; y++) {
            for (int x = 2; x < 6; x++) {
                processor.set(x, y, label);
            }
        }
        return new ImagePlus(title, processor);
    }

    private static Map<String, Integer> countsByMacroAndThreshold(
            List<VariationResult> results) {
        Map<String, Integer> counts = new LinkedHashMap<String, Integer>();
        for (int i = 0; i < results.size(); i++) {
            VariationResult result = results.get(i);
            assertFalse(result.hasError());
            String macro = String.valueOf(result.combo().get(ParameterId.MACRO));
            int threshold = ((Number) result.combo()
                    .get(ParameterId.THRESHOLD)).intValue();
            counts.put(key(macro, threshold), Integer.valueOf(result.nObjects()));
        }
        return counts;
    }

    private static String key(String macroToken, int threshold) {
        return macroToken + "|" + threshold;
    }

    private static void assertAllSuccessful(List<VariationResult> results) {
        for (int i = 0; i < results.size(); i++) {
            assertFalse("cell " + i + ": " + results.get(i).error(),
                    results.get(i).hasError());
            assertNotNull(results.get(i).label());
        }
    }

    private static VariationGridPanel gridPanel(VariationsDialog dialog)
            throws Exception {
        return (VariationGridPanel) invoke(dialog, "gridPanelForTest",
                new Class<?>[0]);
    }

    private static CountCurveStrip mainCountCurve(VariationsDialog dialog)
            throws Exception {
        return (CountCurveStrip) invoke(dialog, "mainCountCurveForTest",
                new Class<?>[0]);
    }

    private static int cellCount(VariationsDialog dialog) throws Exception {
        return ((Integer) invoke(dialog, "cellCountForTest",
                new Class<?>[0])).intValue();
    }

    private static int completedCount(VariationsDialog dialog) throws Exception {
        return ((Integer) invoke(dialog, "completedCountForTest",
                new Class<?>[0])).intValue();
    }

    @SuppressWarnings("unchecked")
    private static List<VariationCellPanel> cellsForTest(VariationGridPanel grid)
            throws Exception {
        return (List<VariationCellPanel>) invoke(grid, "cellsForTest",
                new Class<?>[0]);
    }

    private static AxisGutterPanel singleGutter(Container root,
                                               AxisGutterPanel.Mode mode)
            throws Exception {
        AxisGutterPanel gutter = gutter(root, mode);
        assertNotNull("Expected " + mode + " gutter.", gutter);
        return gutter;
    }

    private static AxisGutterPanel gutter(Container root,
                                          AxisGutterPanel.Mode mode)
            throws Exception {
        List<AxisGutterPanel> gutters = findAll(root, AxisGutterPanel.class);
        for (int i = 0; i < gutters.size(); i++) {
            AxisGutterPanel gutter = gutters.get(i);
            if (mode == fieldValue(gutter, "mode")) {
                return gutter;
            }
        }
        return null;
    }

    private static ParameterId gutterAxis(AxisGutterPanel gutter)
            throws Exception {
        return (ParameterId) fieldValue(gutter, "axis");
    }

    @SuppressWarnings("unchecked")
    private static List<Object> gutterValues(AxisGutterPanel gutter)
            throws Exception {
        return new ArrayList<Object>((List<Object>) fieldValue(gutter, "values"));
    }

    private static FacetChipRow singleFacetRow(Container root) {
        List<FacetChipRow> rows = findAll(root, FacetChipRow.class);
        assertEquals("Expected one facet chip row.", 1, rows.size());
        return rows.get(0);
    }

    private static void assertChipDisplayNames(FacetChipRow row,
                                               MacroVariation... expected) {
        List<AbstractButton> buttons = findAll(row, AbstractButton.class);
        List<String> labels = new ArrayList<String>();
        for (int i = 0; i < buttons.size(); i++) {
            String text = buttons.get(i).getText();
            if (text != null && text.length() > 0) {
                labels.add(text);
            }
        }
        List<String> expectedLabels = new ArrayList<String>();
        for (int i = 0; i < expected.length; i++) {
            expectedLabels.add(expected[i].displayName());
        }
        assertEquals(expectedLabels, labels);

        for (int i = 0; i < expected.length; i++) {
            MacroVariation macro = expected[i];
            AbstractButton button = buttons.get(i);
            String text = button.getText();
            assertFalse(text, text.contains("macro:"));
            assertFalse(text, text.contains(shortHash(macro)));
            String tooltip = button.getToolTipText();
            assertNotNull("Expected identity tooltip for " + macro.displayName(),
                    tooltip);
            assertTrue("Tooltip should include normalized script hash for "
                            + macro.displayName() + ": " + tooltip,
                    tooltip.contains(macro.normalizedScriptHash()));
        }
    }

    private static String shortHash(MacroVariation macro) {
        String hash = macro.normalizedScriptHash();
        return hash == null || hash.length() < 16 ? "" : hash.substring(0, 16);
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

    private static Object fieldValue(Object target, String name)
            throws Exception {
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

    private static void dispose(final VariationsDialog dialog) throws Exception {
        if (dialog == null) {
            return;
        }
        if (SwingUtilities.isEventDispatchThread()) {
            dialog.dispose();
            return;
        }
        SwingUtilities.invokeAndWait(new Runnable() {
            @Override public void run() {
                dialog.dispose();
            }
        });
    }

    private static void assumeDialogAvailable() {
        Assume.assumeFalse("PipelineDialog creates a JDialog in this codebase.",
                GraphicsEnvironment.isHeadless());
    }

    private static File uniqueTargetDir(String prefix) {
        return new File("target/" + prefix + "-" + System.nanoTime());
    }

    private static void assertClassDoesNotReferenceDuplicator(Class<?> type)
            throws Exception {
        String resource = "/" + type.getName().replace('.', '/') + ".class";
        InputStream stream = type.getResourceAsStream(resource);
        assertNotNull("Missing class bytes for " + type.getName(), stream);
        String bytes;
        try {
            bytes = new String(readFully(stream), StandardCharsets.ISO_8859_1);
        } finally {
            stream.close();
        }
        assertFalse(type.getName() + " must not reference ij.plugin.Duplicator",
                bytes.contains("ij/plugin/Duplicator"));
        assertFalse(type.getName() + " must not reference ij.plugin.Duplicator",
                bytes.contains("ij.plugin.Duplicator"));
    }

    private static byte[] readFully(InputStream stream) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int read;
        while ((read = stream.read(buffer)) >= 0) {
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    private static MacroFixture macroFixture() {
        return new MacroFixture(
                MacroVariation.none(),
                MacroVariation.pasted("Blur sigma=1",
                        "run(\"Gaussian Blur...\", \"sigma=1 stack\");"),
                MacroVariation.pasted("Median r=1",
                        "run(\"Median...\", \"radius=1 stack\");"));
    }

    private static final class MacroFixture {
        final MacroVariation none;
        final MacroVariation blur;
        final MacroVariation median;

        MacroFixture(MacroVariation none,
                     MacroVariation blur,
                     MacroVariation median) {
            this.none = none;
            this.blur = blur;
            this.median = median;
        }
    }

    private static final class SyntheticClassicalStrategy
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
                int count = syntheticCount(combo);
                publisher.accept(VariationResult.success(combo,
                        syntheticLabel("macro-label-" + i, i + 1),
                        count,
                        1L,
                        stats(count)));
            }
        }

        private static int syntheticCount(ParameterCombo combo) {
            Object threshold = combo.get(ParameterId.THRESHOLD);
            int value = threshold instanceof Number
                    ? ((Number) threshold).intValue()
                    : 120;
            if (value <= 80) {
                return 9;
            }
            if (value <= 120) {
                return 5;
            }
            return 5;
        }
    }

    private static ImagePlus syntheticLabel(String title, int seed) {
        ShortProcessor processor = new ShortProcessor(16, 16);
        int offset = seed % 3;
        for (int y = 4 + offset; y < 10 + offset; y++) {
            for (int x = 4; x < 10; x++) {
                processor.set(x, y, 1);
            }
        }
        return new ImagePlus(title, processor);
    }

    private static ResultsTable stats(int count) {
        ResultsTable table = new ResultsTable();
        for (int i = 0; i < count; i++) {
            table.incrementCounter();
            table.setValue("Label", i, i + 1);
        }
        return table;
    }

    private static final class RecordingStarDistAdapter
            implements StarDistParameterStage.PreviewAdapter {
        final List<Float> firstPixels = new ArrayList<Float>();

        @Override public ImagePlus createRawSource(ConfigQcContext context) {
            return null;
        }

        @Override public ImagePlus createFilteredSource(ConfigQcContext context) {
            return null;
        }

        @Override public ImagePlus runPreview(ImagePlus filteredSource,
                                              StarDistParameterStage.Parameters parameters) {
            firstPixels.add(Float.valueOf(filteredSource.getProcessor().getf(0)));
            return singleLabelImage("stardist-label-" + firstPixels.size(), 1);
        }

        @Override public int countLabels(ImagePlus labelImage) {
            return maxLabel(labelImage);
        }

        @Override public void close(ImagePlus image) {
            if (image != null) {
                image.flush();
            }
        }
    }

    private static final class RecordingCellposeAdapter
            implements CellposeParameterStage.PreviewAdapter {
        final List<Float> firstPixels = new ArrayList<Float>();

        @Override public ImagePlus createRawSource(ConfigQcContext context) {
            return null;
        }

        @Override public ImagePlus createFilteredSource(ConfigQcContext context) {
            return null;
        }

        @Override public ImagePlus createFilteredCompanionSource(
                ConfigQcContext context, int channelIndex) {
            return null;
        }

        @Override public ImagePlus runPreview(ImagePlus filteredSource,
                                              ImagePlus filteredCompanionSource,
                                              CellposeParameterStage.Parameters parameters) {
            firstPixels.add(Float.valueOf(filteredSource.getProcessor().getf(0)));
            return singleLabelImage("cellpose-label-" + firstPixels.size(), 1);
        }

        @Override public int countLabels(ImagePlus labelImage) {
            return maxLabel(labelImage);
        }

        @Override public void close(ImagePlus image) {
            if (image != null) {
                image.flush();
            }
        }
    }

    private static int maxLabel(ImagePlus labelImage) {
        if (labelImage == null || labelImage.getProcessor() == null) {
            return 0;
        }
        ImageProcessor processor = labelImage.getProcessor();
        int max = 0;
        for (int i = 0; i < processor.getPixelCount(); i++) {
            int value = Math.round(processor.getf(i));
            if (value > max) {
                max = value;
            }
        }
        return max;
    }
}
