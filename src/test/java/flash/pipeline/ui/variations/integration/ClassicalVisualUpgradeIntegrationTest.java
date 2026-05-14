package flash.pipeline.ui.variations.integration;

import flash.pipeline.analyses.CreateBinFileAnalysis;
import flash.pipeline.ui.config.ClassicalSegmentationStage;
import flash.pipeline.ui.config.ConfigQcContext;
import flash.pipeline.ui.preview.VariationMontageDialog;
import flash.pipeline.ui.variations.AxisGutterPanel;
import flash.pipeline.ui.variations.CountCurveMini;
import flash.pipeline.ui.variations.CountCurveStrip;
import flash.pipeline.ui.variations.CropSpec;
import flash.pipeline.ui.variations.ParameterCombo;
import flash.pipeline.ui.variations.ParameterId;
import flash.pipeline.ui.variations.ParameterSweep;
import flash.pipeline.ui.variations.ParameterValueList;
import flash.pipeline.ui.variations.VariationCellPanel;
import flash.pipeline.ui.variations.VariationEngineContext;
import flash.pipeline.ui.variations.VariationGridPanel;
import flash.pipeline.ui.variations.VariationResult;
import flash.pipeline.ui.variations.VariationsDialog;
import flash.pipeline.ui.variations.strategy.ClassicalSweep;

import ij.ImagePlus;
import ij.ImageStack;
import ij.process.ByteProcessor;
import ij.process.ImageProcessor;

import org.junit.Assume;
import org.junit.Test;

import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Container;
import java.awt.GraphicsEnvironment;
import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class ClassicalVisualUpgradeIntegrationTest {

    private static final long DIALOG_TIMEOUT_MS = 10000L;

    @Test
    public void oneAxisThresholdSweepShowsPlateauVisualsAndNonEmptyLabels()
            throws Exception {
        assumeDialogAvailable();
        ImagePlus source = plateauBlobStack();
        DialogRun run = null;
        try {
            run = runDialog(source, oneAxisThresholdPlateauSweep(source),
                    new VariationIntegrationTestSupport.ThresholdingPreviewAdapter());

            final DialogRun finished = run;
            onEdt(new CheckedRunnable() {
                @Override public void run() throws Exception {
                    assertEquals(6, cellCount(finished.dialog));
                    assertEquals(6, completedCount(finished.dialog));
                    assertAllCellsHaveNonEmptyLabels(finished.grid, 6);

                    AxisGutterPanel top = gutter(finished.grid, "TOP");
                    assertNotNull(top);
                    assertEquals(ParameterId.THRESHOLD, gutterAxis(top));
                    assertEquals(6, gutterValues(top).size());
                    assertSame(null, gutter(finished.grid, "LEFT"));

                    CountCurveStrip strip = onlyMainCountCurve(finished.dialog);
                    assertTrue(strip.isVisible());
                    assertNotNull("Expected count plateau band",
                            field(strip, "plateauRange"));
                    assertEquals(1, stableCountRibbonCount(finished.grid));
                }
            });
        } finally {
            dispose(run);
            source.flush();
        }
    }

    @Test
    public void twoAxisThresholdAndMinSizeSweepShowsGuttersAndAllSparklines()
            throws Exception {
        assumeDialogAvailable();
        ImagePlus source = plateauBlobStack();
        DialogRun run = null;
        try {
            run = runDialog(source, twoAxisClassicalSweep(source),
                    new VariationIntegrationTestSupport.ThresholdingPreviewAdapter());

            final DialogRun finished = run;
            onEdt(new CheckedRunnable() {
                @Override public void run() throws Exception {
                    assertEquals(8, cellCount(finished.dialog));
                    assertEquals(8, completedCount(finished.dialog));

                    AxisGutterPanel top = gutter(finished.grid, "TOP");
                    AxisGutterPanel left = gutter(finished.grid, "LEFT");
                    assertNotNull(top);
                    assertNotNull(left);
                    assertEquals(ParameterId.THRESHOLD, gutterAxis(top));
                    assertEquals(4, gutterValues(top).size());
                    assertEquals(ParameterId.MIN_SIZE, gutterAxis(left));
                    assertEquals(2, gutterValues(left).size());

                    assertEquals(1, mainCountCurves(finished.dialog).size());
                    assertEquals(2, visibleComponents(finished.dialog,
                            CountCurveMini.class).size());
                }
            });
        } finally {
            dispose(run);
            source.flush();
        }
    }

    @Test
    public void montageLauncherOpensFooterControlsInExpectedOrder()
            throws Exception {
        assumeDialogAvailable();
        ImagePlus source = plateauBlobStack();
        DialogRun run = null;
        try {
            run = runDialog(source, oneAxisThresholdPlateauSweep(source),
                    new VariationIntegrationTestSupport.ThresholdingPreviewAdapter());

            final DialogRun finished = run;
            onEdt(new CheckedRunnable() {
                @Override public void run() throws Exception {
                    JButton launcher = findButton(finished.dialog,
                            "Open in large montage");
                    assertNotNull(launcher);
                    assertTrue(launcher.isEnabled());
                    launcher.doClick();

                    VariationMontageDialog montage =
                            (VariationMontageDialog) field(finished.dialog,
                                    "montageDialog");
                    assertNotNull(montage);

                    JPanel sourceControls =
                            (JPanel) field(montage, "sourceControls");
                    JPanel overlayControls =
                            (JPanel) field(montage, "overlayControls");
                    JButton bcButton =
                            (JButton) field(montage, "displayControlsButton");
                    JButton lutButton =
                            (JButton) field(montage, "lutToggleButton");

                    assertTrue(sourceControls.isVisible());
                    assertTrue(overlayControls.isVisible());
                    assertTrue(bcButton.isVisible());
                    assertTrue(lutButton.isVisible());
                    assertContainsLabel(sourceControls, "Source:");
                    assertContainsCombo(sourceControls);
                    assertContainsCheckBox(overlayControls, "Overlay objects");
                    assertContainsLabel(overlayControls, "over");
                    assertContainsCombo(overlayControls);
                    assertEquals("Adjust Brightness/Contrast", bcButton.getText());
                    assertEquals("Grey LUT", lutButton.getText());

                    assertBefore(sourceControls.getParent(), sourceControls,
                            overlayControls);
                    assertBefore(bcButton.getParent(), bcButton, lutButton);

                    Container footer = sourceControls.getParent().getParent();
                    BorderLayout layout = (BorderLayout) footer.getLayout();
                    assertSame(sourceControls.getParent(),
                            layout.getLayoutComponent(BorderLayout.WEST));
                    assertSame(bcButton.getParent(),
                            layout.getLayoutComponent(BorderLayout.EAST));
                }
            });
        } finally {
            dispose(run);
            source.flush();
        }
    }

    @Test
    public void realCreateBinPreviewKeepsLabelsAcrossFiveParallelSweeps()
            throws Exception {
        ClassicalSegmentationStage.PreviewAdapter adapter =
                realCreateBinPreviewAdapter();
        ParameterSweep sweep = realPreviewThresholdSweep();

        for (int run = 0; run < 5; run++) {
            ImagePlus source = highIntensityBlobStack();
            ClassicalSweep strategy = new ClassicalSweep(source, CropSpec.full(),
                    null, adapter, 4);
            List<VariationResult> results =
                    Collections.synchronizedList(new ArrayList<VariationResult>());
            try {
                strategy.dispatch(sweep, results::add, () -> false);

                assertEquals("run " + run, 5, results.size());
                for (int i = 0; i < results.size(); i++) {
                    VariationResult result = results.get(i);
                    assertFalse("run " + run + " cell " + i + " failed: "
                            + result.error(), result.hasError());
                    assertNotNull("run " + run + " cell " + i, result.label());
                    assertTrue("run " + run + " threshold "
                                    + result.combo().get(ParameterId.THRESHOLD),
                            labelledPixels(result.label()) > 0);
                    result.label().flush();
                }
            } finally {
                source.flush();
            }
        }
    }

    private static DialogRun runDialog(ImagePlus source,
                                       ParameterSweep sweep,
                                       ClassicalSegmentationStage.PreviewAdapter adapter)
            throws Exception {
        final AtomicReference<VariationsDialog> ref =
                new AtomicReference<VariationsDialog>();
        final VariationEngineContext context = context(source, adapter);
        onEdt(new CheckedRunnable() {
            @Override public void run() throws Exception {
                VariationsDialog dialog = new VariationsDialog(null, context,
                        combo -> { });
                invoke(dialog, "setSweepForTest",
                        new Class<?>[] { ParameterSweep.class }, sweep);
                dialog.start();
                ref.set(dialog);
            }
        });
        VariationsDialog dialog = ref.get();
        invoke(dialog, "waitForDoneForTest",
                new Class<?>[] { long.class }, Long.valueOf(DIALOG_TIMEOUT_MS));
        VariationGridPanel grid = (VariationGridPanel) invoke(dialog,
                "gridPanelForTest", new Class<?>[0]);
        return new DialogRun(dialog, grid);
    }

    private static VariationEngineContext context(
            ImagePlus source,
            ClassicalSegmentationStage.PreviewAdapter adapter) {
        File bin = new File("target/classical-visual-upgrade-"
                + System.nanoTime());
        ConfigQcContext config = ConfigQcContext.fromImages(new File("."), bin,
                null, Collections.singletonList(source),
                Collections.singletonList("DAPI"), 0);
        ParameterCombo base = ParameterCombo.builder()
                .put(ParameterId.THRESHOLD, Integer.valueOf(100))
                .put(ParameterId.MIN_SIZE, Integer.valueOf(1))
                .put(ParameterId.MAX_SIZE, Integer.valueOf(Integer.MAX_VALUE))
                .build();
        return VariationEngineContext.forClassical("DAPI", source, source,
                config, base, adapter);
    }

    private static ParameterSweep oneAxisThresholdPlateauSweep(ImagePlus source) {
        Map<ParameterId, ParameterValueList> values =
                new LinkedHashMap<ParameterId, ParameterValueList>();
        values.put(ParameterId.THRESHOLD,
                ParameterValueList.ofInts(40, 80, 120, 160, 200, 210));
        values.put(ParameterId.MIN_SIZE, ParameterValueList.ofInts(1));
        values.put(ParameterId.MAX_SIZE,
                ParameterValueList.ofInts(Integer.MAX_VALUE));
        return new ParameterSweep(ParameterSweep.Method.CLASSICAL, values,
                CropSpec.full(), "DAPI", source.getTitle());
    }

    private static ParameterSweep twoAxisClassicalSweep(ImagePlus source) {
        Map<ParameterId, ParameterValueList> values =
                new LinkedHashMap<ParameterId, ParameterValueList>();
        values.put(ParameterId.THRESHOLD,
                ParameterValueList.ofInts(80, 120, 160, 200));
        values.put(ParameterId.MIN_SIZE, ParameterValueList.ofInts(1, 80));
        values.put(ParameterId.MAX_SIZE,
                ParameterValueList.ofInts(Integer.MAX_VALUE));
        return new ParameterSweep(ParameterSweep.Method.CLASSICAL, values,
                CropSpec.full(), "DAPI", source.getTitle());
    }

    private static ParameterSweep realPreviewThresholdSweep() {
        Map<ParameterId, ParameterValueList> values =
                new LinkedHashMap<ParameterId, ParameterValueList>();
        values.put(ParameterId.THRESHOLD,
                ParameterValueList.ofInts(40, 80, 120, 160, 200));
        values.put(ParameterId.MIN_SIZE, ParameterValueList.ofInts(1));
        values.put(ParameterId.MAX_SIZE,
                ParameterValueList.ofInts(Integer.MAX_VALUE));
        return new ParameterSweep(ParameterSweep.Method.CLASSICAL, values,
                CropSpec.full(), "DAPI", "real-create-bin-thread-safety");
    }

    private static ClassicalSegmentationStage.PreviewAdapter
    realCreateBinPreviewAdapter() throws Exception {
        CreateBinFileAnalysis analysis = new CreateBinFileAnalysis();
        Object cfg = binUserConfig();
        Method factory = CreateBinFileAnalysis.class.getDeclaredMethod(
                "createClassicalSegmentationStage",
                cfg.getClass(),
                File.class,
                int.class);
        factory.setAccessible(true);
        ClassicalSegmentationStage stage = (ClassicalSegmentationStage)
                factory.invoke(analysis,
                        cfg,
                        new File(System.getProperty("java.io.tmpdir")),
                        Integer.valueOf(0));

        Field previewAdapter = ClassicalSegmentationStage.class
                .getDeclaredField("previewAdapter");
        previewAdapter.setAccessible(true);
        return (ClassicalSegmentationStage.PreviewAdapter)
                previewAdapter.get(stage);
    }

    private static Object binUserConfig() throws Exception {
        Class<?> cfgClass = Class.forName(
                "flash.pipeline.analyses.CreateBinFileAnalysis$BinUserConfig");
        Constructor<?> constructor = cfgClass.getDeclaredConstructor(
                List.class,
                List.class,
                List.class,
                List.class,
                List.class,
                List.class,
                List.class);
        constructor.setAccessible(true);
        return constructor.newInstance(
                list("DAPI"),
                list("Grays"),
                list("40"),
                list("1-Infinity"),
                list("0-255"),
                list("None"),
                list("default"));
    }

    private static ImagePlus plateauBlobStack() {
        int width = 96;
        int height = 96;
        int slices = 3;
        ImageStack stack = new ImageStack(width, height);
        for (int z = 1; z <= slices; z++) {
            ByteProcessor processor = new ByteProcessor(width, height);
            paintDisk(processor, 16, 18, 4, 100);
            paintDisk(processor, 44, 18, 4, 100);
            paintDisk(processor, 72, 22, 4, 140);
            paintDisk(processor, 20, 70, 4, 220);
            paintDisk(processor, 50, 70, 4, 220);
            paintDisk(processor, 78, 70, 4, 220);
            stack.addSlice("z" + z, processor);
        }
        ImagePlus image = new ImagePlus("classical plateau blobs", stack);
        image.setDimensions(1, slices, 1);
        return image;
    }

    private static ImagePlus highIntensityBlobStack() {
        int width = 96;
        int height = 96;
        int slices = 4;
        ImageStack stack = new ImageStack(width, height);
        for (int z = 1; z <= slices; z++) {
            ByteProcessor processor = new ByteProcessor(width, height);
            paintDisk(processor, 16, 16, 4, 220);
            paintDisk(processor, 48, 18, 4, 220);
            paintDisk(processor, 76, 24, 4, 220);
            paintDisk(processor, 28, 68, 4, 220);
            paintDisk(processor, 68, 70, 4, 220);
            stack.addSlice("z" + z, processor);
        }
        ImagePlus image = new ImagePlus("synthetic classical blobs", stack);
        image.setDimensions(1, slices, 1);
        return image;
    }

    private static void paintDisk(ByteProcessor processor,
                                  int centerX,
                                  int centerY,
                                  int radius,
                                  int value) {
        int radiusSquared = radius * radius;
        for (int y = centerY - radius; y <= centerY + radius; y++) {
            for (int x = centerX - radius; x <= centerX + radius; x++) {
                int dx = x - centerX;
                int dy = y - centerY;
                if (dx * dx + dy * dy <= radiusSquared) {
                    processor.set(x, y, value);
                }
            }
        }
    }

    private static void assertAllCellsHaveNonEmptyLabels(VariationGridPanel grid,
                                                         int expectedCells)
            throws Exception {
        List<VariationCellPanel> cells = cells(grid);
        assertEquals(expectedCells, cells.size());
        for (int i = 0; i < cells.size(); i++) {
            ImagePlus label = (ImagePlus) invoke(cells.get(i),
                    "cachedLabelForTest", new Class<?>[0]);
            assertNotNull("cell " + i, label);
            assertTrue("cell " + i + " has an empty label map",
                    labelledPixels(label) > 0);
        }
    }

    private static int labelledPixels(ImagePlus label) {
        int count = 0;
        for (int slice = 1; slice <= label.getStackSize(); slice++) {
            ImageProcessor processor = label.getStack().getProcessor(slice);
            for (int i = 0; i < processor.getPixelCount(); i++) {
                if (processor.getf(i) > 0.0f) {
                    count++;
                }
            }
        }
        return count;
    }

    private static AxisGutterPanel gutter(Container root, String mode)
            throws Exception {
        List<AxisGutterPanel> gutters = components(root, AxisGutterPanel.class);
        for (int i = 0; i < gutters.size(); i++) {
            AxisGutterPanel gutter = gutters.get(i);
            Object gutterMode = field(gutter, "mode");
            if (mode.equals(String.valueOf(gutterMode))) {
                return gutter;
            }
        }
        return null;
    }

    private static ParameterId gutterAxis(AxisGutterPanel gutter) throws Exception {
        return (ParameterId) field(gutter, "axis");
    }

    @SuppressWarnings("unchecked")
    private static List<Object> gutterValues(AxisGutterPanel gutter)
            throws Exception {
        return (List<Object>) field(gutter, "values");
    }

    private static CountCurveStrip onlyMainCountCurve(Container root) {
        List<CountCurveStrip> curves = mainCountCurves(root);
        assertEquals(1, curves.size());
        return curves.get(0);
    }

    private static List<CountCurveStrip> mainCountCurves(Container root) {
        List<CountCurveStrip> curves = components(root, CountCurveStrip.class);
        List<CountCurveStrip> main = new ArrayList<CountCurveStrip>();
        for (int i = 0; i < curves.size(); i++) {
            CountCurveStrip curve = curves.get(i);
            if (!(curve instanceof CountCurveMini) && curve.isVisible()) {
                main.add(curve);
            }
        }
        return main;
    }

    private static int stableCountRibbonCount(VariationGridPanel grid)
            throws Exception {
        int count = 0;
        List<VariationCellPanel> cells = cells(grid);
        for (int i = 0; i < cells.size(); i++) {
            if (Boolean.TRUE.equals(field(cells.get(i), "kneeWinner"))) {
                count++;
            }
        }
        return count;
    }

    @SuppressWarnings("unchecked")
    private static List<VariationCellPanel> cells(VariationGridPanel grid)
            throws Exception {
        return (List<VariationCellPanel>) invoke(grid, "cellsForTest",
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

    private static JButton findButton(Container root, String text) {
        List<JButton> buttons = components(root, JButton.class);
        for (int i = 0; i < buttons.size(); i++) {
            JButton button = buttons.get(i);
            if (text.equals(button.getText())) {
                return button;
            }
        }
        return null;
    }

    private static void assertContainsLabel(Container root, String text) {
        List<JLabel> labels = components(root, JLabel.class);
        for (int i = 0; i < labels.size(); i++) {
            if (text.equals(labels.get(i).getText())) {
                return;
            }
        }
        throw new AssertionError("Missing label: " + text);
    }

    private static void assertContainsCheckBox(Container root, String text) {
        List<JCheckBox> boxes = components(root, JCheckBox.class);
        for (int i = 0; i < boxes.size(); i++) {
            if (text.equals(boxes.get(i).getText())) {
                return;
            }
        }
        throw new AssertionError("Missing checkbox: " + text);
    }

    private static void assertContainsCombo(Container root) {
        assertFalse(components(root, JComboBox.class).isEmpty());
    }

    private static void assertBefore(Container parent,
                                     Component first,
                                     Component second) {
        Component[] children = parent.getComponents();
        int firstIndex = -1;
        int secondIndex = -1;
        for (int i = 0; i < children.length; i++) {
            if (children[i] == first) {
                firstIndex = i;
            }
            if (children[i] == second) {
                secondIndex = i;
            }
        }
        assertTrue(firstIndex >= 0);
        assertTrue(secondIndex >= 0);
        assertTrue(firstIndex < secondIndex);
    }

    private static <T extends Component> List<T> visibleComponents(
            Container root,
            Class<T> type) {
        List<T> out = new ArrayList<T>();
        List<T> all = components(root, type);
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).isVisible()) {
                out.add(all.get(i));
            }
        }
        return out;
    }

    private static <T extends Component> List<T> components(Container root,
                                                            Class<T> type) {
        List<T> out = new ArrayList<T>();
        collectComponents(root, type, out);
        return out;
    }

    private static <T extends Component> void collectComponents(Component component,
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
                collectComponents(children[i], type, out);
            }
        }
    }

    private static Object invoke(Object target,
                                 String name,
                                 Class<?>[] parameterTypes,
                                 Object... args) throws Exception {
        Method method = target.getClass().getDeclaredMethod(name, parameterTypes);
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

    private static Object field(Object target, String name) throws Exception {
        Class<?> type = target.getClass();
        while (type != null) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(target);
            } catch (NoSuchFieldException e) {
                type = type.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }

    private static void onEdt(final CheckedRunnable runnable) throws Exception {
        if (SwingUtilities.isEventDispatchThread()) {
            runnable.run();
            return;
        }
        final AtomicReference<Throwable> failure =
                new AtomicReference<Throwable>();
        SwingUtilities.invokeAndWait(new Runnable() {
            @Override public void run() {
                try {
                    runnable.run();
                } catch (Throwable t) {
                    failure.set(t);
                }
            }
        });
        Throwable thrown = failure.get();
        if (thrown instanceof Exception) {
            throw (Exception) thrown;
        }
        if (thrown instanceof Error) {
            throw (Error) thrown;
        }
        if (thrown != null) {
            throw new RuntimeException(thrown);
        }
    }

    private static void dispose(final DialogRun run) throws Exception {
        if (run == null || run.dialog == null) {
            return;
        }
        onEdt(new CheckedRunnable() {
            @Override public void run() {
                run.dialog.dispose();
            }
        });
    }

    private static void assumeDialogAvailable() {
        Assume.assumeFalse("PipelineDialog creates a JDialog in this codebase.",
                GraphicsEnvironment.isHeadless());
    }

    private static List<String> list(String value) {
        return new ArrayList<String>(Arrays.asList(value));
    }

    private interface CheckedRunnable {
        void run() throws Exception;
    }

    private static final class DialogRun {
        final VariationsDialog dialog;
        final VariationGridPanel grid;

        DialogRun(VariationsDialog dialog, VariationGridPanel grid) {
            this.dialog = dialog;
            this.grid = grid;
        }
    }
}
