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
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;

public class MacroVariationsDialogOverlayToggleTest {

    @Test(timeout = 15000)
    public void toggleAppliesToExistingCellsAndNewRuns() throws Exception {
        UiTestAssumptions.assumeInteractiveUiTestsEnabled();
        final AtomicReference<MacroVariationsDialog> ref =
                new AtomicReference<MacroVariationsDialog>();

        SwingUtilities.invokeAndWait(new Runnable() {
            @Override public void run() {
                MacroVariationsDialog dialog = new MacroVariationsDialog(null,
                        context(), new Consumer<String>() {
                            @Override public void accept(String macro) {
                            }
                        });
                dialog.setMode(MacroVariationsDialog.Mode.FULL_SWEEP);
                configureSweep(dialog.editorForTest());
                dialog.runButtonForTest().doClick();
                ref.set(dialog);
            }
        });

        final MacroVariationsDialog dialog = ref.get();
        try {
            dialog.waitForDoneForTest(5000L);
            SwingUtilities.invokeAndWait(new Runnable() {
                @Override public void run() {
                    assertFalse(dialog.gridWindowForTest()
                            .otsuOverlayCheckBoxForTest().isSelected());
                    assertCellsHaveMode(dialog.cellsForTest(),
                            VariationCellPanel.OverlayMode.NONE);

                    dialog.gridWindowForTest().otsuOverlayCheckBoxForTest().doClick();
                    assertTrue(dialog.gridWindowForTest()
                            .otsuOverlayCheckBoxForTest().isSelected());
                    assertCellsHaveMode(dialog.cellsForTest(),
                            VariationCellPanel.OverlayMode.OTSU_MASK);
                    assertOverlayImages(dialog);

                    dialog.gridWindowForTest().otsuOverlayCheckBoxForTest().doClick();
                    assertFalse(dialog.gridWindowForTest()
                            .otsuOverlayCheckBoxForTest().isSelected());
                    assertCellsHaveMode(dialog.cellsForTest(),
                            VariationCellPanel.OverlayMode.NONE);
                    assertPlainImages(dialog);

                    dialog.gridWindowForTest().otsuOverlayCheckBoxForTest().doClick();
                    dialog.runButtonForTest().doClick();
                    assertCellsHaveMode(dialog.cellsForTest(),
                            VariationCellPanel.OverlayMode.OTSU_MASK);
                }
            });

            dialog.waitForDoneForTest(5000L);
            SwingUtilities.invokeAndWait(new Runnable() {
                @Override public void run() {
                    assertCellsHaveMode(dialog.cellsForTest(),
                            VariationCellPanel.OverlayMode.OTSU_MASK);
                    assertOverlayImages(dialog);
                }
            });
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

    private static void assertCellsHaveMode(List<VariationCellPanel> cells,
                                            VariationCellPanel.OverlayMode mode) {
        assertEquals(3, cells.size());
        for (int i = 0; i < cells.size(); i++) {
            if (cells.get(i).isBaselineForTest()) {
                assertEquals(VariationCellPanel.OverlayMode.NONE,
                        cells.get(i).overlayModeForTest());
            } else {
                assertEquals(mode, cells.get(i).overlayModeForTest());
            }
        }
    }

    private static void assertOverlayImages(MacroVariationsDialog dialog) {
        List<VariationCellPanel> cells = dialog.cellsForTest();
        List<VariationResult> results = dialog.resultsForTest();
        assertEquals(results.size() + 1, cells.size());
        for (int i = 0; i < results.size(); i++) {
            assertNotSame(results.get(i).previewImage(),
                    cells.get(i + 1).currentPreviewImageForTest());
            assertEquals("Threshold red overlay",
                    cells.get(i + 1).currentPreviewImageForTest().getTitle());
        }
    }

    private static void assertPlainImages(MacroVariationsDialog dialog) {
        List<VariationCellPanel> cells = dialog.cellsForTest();
        List<VariationResult> results = dialog.resultsForTest();
        assertEquals(results.size() + 1, cells.size());
        for (int i = 0; i < results.size(); i++) {
            assertEquals(results.get(i).previewImage(),
                    cells.get(i + 1).currentPreviewImageForTest());
        }
    }

    private static void configureSweep(ParameterSweepEditor editor) {
        List<ParameterKey> keys = editor.parameterKeysForTest();
        for (int i = 0; i < keys.size(); i++) {
            ParameterKey key = keys.get(i);
            if (key instanceof FilterParameterId
                    && "sigma".equals(((FilterParameterId) key).paramKey())) {
                editor.setSweptForTest(key, true);
                editor.setParameterValuesForTest(key, Arrays.<Object>asList(
                        Double.valueOf(1.0d),
                        Double.valueOf(2.0d)));
            }
        }
    }

    private static FilterVariationEngineContext context() {
        ImagePlus source = image(0.0d);
        FilterMacroEditorModel.MacroDefinition macro =
                FilterMacroEditorModel.parse(macroText());
        ConfigQcContext config = ConfigQcContext.fromImages(new File("."),
                new File("target/macro-variations-overlay-toggle-test-bin"),
                null, Collections.singletonList(source),
                Collections.singletonList("DAPI"), 0);
        return new FilterVariationEngineContext(macro, source, CropSpec.full(),
                "DAPI", config, new PreviewAdapter());
    }

    private static String macroText() {
        return "run(\"Gaussian Blur...\", \"sigma=1 stack\");\n";
    }

    private static ImagePlus image(double offset) {
        ByteProcessor processor = new ByteProcessor(16, 16);
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                int value = y < 8 ? 20 : (int) Math.round(210.0d + offset);
                processor.set(x, y, value);
            }
        }
        return new ImagePlus("filtered", processor);
    }

    private static final class PreviewAdapter
            implements FilterParameterStage.PreviewAdapter {
        @Override public ImagePlus createSource(ConfigQcContext context) {
            return image(0.0d);
        }

        @Override public ImagePlus createFilteredPreview(ImagePlus source,
                                                         String macroContent) {
            double offset = macroContent.contains("sigma=2.0") ? 8.0d : 0.0d;
            return image(offset);
        }

        @Override public void close(ImagePlus image) {
        }
    }
}
