package flash.pipeline.ui.variations;

import flash.pipeline.image.FilterMacroEditorModel;
import flash.pipeline.testutil.UiTestAssumptions;
import flash.pipeline.ui.config.ConfigQcContext;
import flash.pipeline.ui.config.FilterParameterStage;
import ij.ImagePlus;
import ij.process.ByteProcessor;
import org.junit.Test;

import javax.swing.SwingUtilities;
import java.io.File;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class MacroVariationsDialogAcceptCallbackTest {

    @Test(timeout = 10000)
    public void cellClickAcceptsRenderedSelectedFilterMacro() throws Exception {
        UiTestAssumptions.assumeInteractiveUiTestsEnabled();
        final AtomicReference<MacroVariationsDialog> ref =
                new AtomicReference<MacroVariationsDialog>();
        final AtomicReference<String> accepted =
                new AtomicReference<String>();
        final AtomicInteger acceptedCount = new AtomicInteger();

        SwingUtilities.invokeAndWait(new Runnable() {
            @Override public void run() {
                MacroVariationsDialog dialog = new MacroVariationsDialog(null,
                        context(), new Consumer<String>() {
                    @Override public void accept(String macro) {
                        acceptedCount.incrementAndGet();
                        accepted.set(macro);
                    }
                });
                dialog.setMode(MacroVariationsDialog.Mode.FULL_SWEEP);
                configureOneComboSweep(dialog.editorForTest());
                assertFalse(dialog.useComboButtonForTest().isEnabled());
                dialog.runButtonForTest().doClick();
                ref.set(dialog);
            }
        });

        final MacroVariationsDialog dialog = ref.get();
        try {
            dialog.waitForDoneForTest(5000L);
            SwingUtilities.invokeAndWait(new Runnable() {
                @Override public void run() {
                    assertEquals(2, dialog.cellsForTest().size());
                    assertTrue(dialog.cellsForTest().get(0).isBaselineForTest());
                    assertFalse(dialog.useComboButtonForTest().isEnabled());
                    // Click selects the tile; committing is an explicit button.
                    dialog.cellsForTest().get(1).clickForTest(false);
                    assertTrue(dialog.useComboButtonForTest().isEnabled());
                    dialog.useComboButtonForTest().doClick();
                }
            });

            assertNotNull(accepted.get());
            assertEquals(1, acceptedCount.get());
            assertTrue(accepted.get().contains("sigma=2.0"));
        } finally {
            SwingUtilities.invokeAndWait(new Runnable() {
                @Override public void run() {
                    if (ref.get() != null) {
                        ref.get().dispose();
                    }
                }
            });
        }
    }

    @Test
    public void completedFilterCellClickInvokesAcceptCallbackOnce() throws Exception {
        final ParameterCombo combo = ParameterCombo.builder()
                .put(ParameterId.THRESHOLD, Integer.valueOf(128))
                .build();
        final ImagePlus source = image();
        final ImagePlus filtered = image();
        final AtomicReference<ParameterCombo> accepted =
                new AtomicReference<ParameterCombo>();
        final AtomicInteger acceptedCount = new AtomicInteger();
        final VariationCellPanel cell = new VariationCellPanel(combo, source,
                new Consumer<ParameterCombo>() {
                    @Override public void accept(ParameterCombo value) {
                        acceptedCount.incrementAndGet();
                        accepted.set(value);
                    }
                },
                null);
        final VariationResult result = VariationResult.filterSuccess(combo,
                filtered, 12L, new int[256], 6.8d, 14.3d);

        SwingUtilities.invokeAndWait(new Runnable() {
            @Override public void run() {
                cell.setFilterResult(result);
                cell.clickForTest(false);
            }
        });

        assertEquals(1, acceptedCount.get());
        assertEquals(combo, accepted.get());
    }

    private static void configureOneComboSweep(ParameterSweepEditor editor) {
        List<ParameterKey> keys = editor.parameterKeysForTest();
        for (int i = 0; i < keys.size(); i++) {
            ParameterKey key = keys.get(i);
            if (key instanceof FilterParameterId
                    && "sigma".equals(((FilterParameterId) key).paramKey())) {
                editor.setSweptForTest(key, true);
                editor.setParameterValuesForTest(key,
                        Collections.<Object>singletonList(Double.valueOf(2.0d)));
            }
        }
    }

    private static FilterVariationEngineContext context() {
        ImagePlus source = image();
        FilterMacroEditorModel.MacroDefinition macro =
                FilterMacroEditorModel.parse(macroText());
        ConfigQcContext config = ConfigQcContext.fromImages(new File("."),
                new File("target/macro-variations-accept-test-bin"),
                null, Collections.singletonList(source),
                Collections.singletonList("DAPI"), 0);
        return new FilterVariationEngineContext(macro, source, CropSpec.full(),
                "DAPI", config, new PreviewAdapter());
    }

    private static String macroText() {
        return "run(\"Gaussian Blur...\", \"sigma=1 stack\");\n";
    }

    private static ImagePlus image() {
        ByteProcessor processor = new ByteProcessor(16, 16);
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                processor.set(x, y, y < 8 ? 10 : 20);
            }
        }
        return new ImagePlus("source", processor);
    }

    private static final class PreviewAdapter
            implements FilterParameterStage.PreviewAdapter {
        @Override public ImagePlus createSource(ConfigQcContext context) {
            return image();
        }

        @Override public ImagePlus createFilteredPreview(ImagePlus source,
                                                         String macroContent) {
            return source == null ? null : source.duplicate();
        }

        @Override public void close(ImagePlus image) {
            if (image != null) image.flush();
        }
    }
}
