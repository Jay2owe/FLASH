package flash.pipeline.ui.variations;

import flash.pipeline.image.FilterMacroEditorModel;
import flash.pipeline.ui.config.ConfigQcContext;
import flash.pipeline.ui.config.FilterParameterStage;

import ij.ImagePlus;
import ij.process.ByteProcessor;
import org.junit.Assume;
import org.junit.Test;

import javax.swing.SwingUtilities;
import java.awt.GraphicsEnvironment;
import java.awt.GridLayout;
import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;

public class MacroVariationsDialogGridWindowTest {

    @Test
    public void runOpensGridWindowWithExpectedLayoutAndReplacesPrevious()
            throws Exception {
        Assume.assumeFalse("PipelineDialog creates a JDialog in this codebase.",
                GraphicsEnvironment.isHeadless());
        final AtomicReference<MacroVariationsDialog> ref =
                new AtomicReference<MacroVariationsDialog>();
        final AtomicReference<VariationGridWindow> firstWindow =
                new AtomicReference<VariationGridWindow>();

        SwingUtilities.invokeAndWait(new Runnable() {
            @Override public void run() {
                MacroVariationsDialog dialog = new MacroVariationsDialog(null,
                        context(), new Consumer<String>() {
                    @Override public void accept(String macro) {
                    }
                });
                dialog.setMode(MacroVariationsDialog.Mode.FULL_SWEEP);
                configureFourCellSweep(dialog.editorForTest());
                dialog.runButtonForTest().doClick();
                VariationGridWindow window = dialog.gridWindowForTest();
                assertNotNull(window);
                assertEquals(4, window.cellsForTest().size());
                GridLayout layout =
                        (GridLayout) window.gridPanelForTest().getLayout();
                assertEquals(2, layout.getRows());
                assertEquals(2, layout.getColumns());
                firstWindow.set(window);
                ref.set(dialog);
            }
        });

        final MacroVariationsDialog dialog = ref.get();
        try {
            dialog.waitForDoneForTest(5000L);
            SwingUtilities.invokeAndWait(new Runnable() {
                @Override public void run() {
                    dialog.runButtonForTest().doClick();
                    VariationGridWindow second = dialog.gridWindowForTest();
                    assertNotNull(second);
                    assertNotSame(firstWindow.get(), second);
                    assertFalse(firstWindow.get().isDisplayable());
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

    @Test
    public void gridToolbarOtsuCheckboxRoutesThroughDialogHandler()
            throws Exception {
        Assume.assumeFalse("PipelineDialog creates a JDialog in this codebase.",
                GraphicsEnvironment.isHeadless());
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
                configureFourCellSweep(dialog.editorForTest());
                dialog.runButtonForTest().doClick();
                ref.set(dialog);
            }
        });

        final MacroVariationsDialog dialog = ref.get();
        try {
            dialog.waitForDoneForTest(5000L);
            SwingUtilities.invokeAndWait(new Runnable() {
                @Override public void run() {
                    dialog.gridWindowForTest().otsuOverlayCheckBoxForTest().doClick();
                    for (VariationCellPanel cell : dialog.cellsForTest()) {
                        assertEquals(VariationCellPanel.OverlayMode.OTSU_MASK,
                                cell.overlayModeForTest());
                    }
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

    private static void configureFourCellSweep(ParameterSweepEditor editor) {
        List<ParameterKey> keys = editor.parameterKeysForTest();
        for (int i = 0; i < keys.size(); i++) {
            ParameterKey key = keys.get(i);
            if (key instanceof FilterParameterId) {
                FilterParameterId id = (FilterParameterId) key;
                if ("sigma".equals(id.paramKey())) {
                    editor.setSweptForTest(id, true);
                    editor.setParameterValuesForTest(id, Arrays.<Object>asList(
                            Double.valueOf(1.0d), Double.valueOf(2.0d)));
                } else if ("rolling".equals(id.paramKey())) {
                    editor.setSweptForTest(id, true);
                    editor.setParameterValuesForTest(id, Arrays.<Object>asList(
                            Integer.valueOf(25), Integer.valueOf(50)));
                }
            }
        }
    }

    private static FilterVariationEngineContext context() {
        ImagePlus source = image(0.0d);
        FilterMacroEditorModel.MacroDefinition macro =
                FilterMacroEditorModel.parse(macroText());
        ConfigQcContext config = ConfigQcContext.fromImages(new File("."),
                new File("target/macro-variations-grid-window-test-bin"),
                null, Collections.singletonList(source),
                Collections.singletonList("DAPI"), 0);
        return new FilterVariationEngineContext(macro, source, CropSpec.full(),
                "DAPI", config, new PreviewAdapter());
    }

    private static String macroText() {
        return "run(\"Gaussian Blur...\", \"sigma=1 stack\");\n"
                + "run(\"Subtract Background...\", \"rolling=25 stack\");\n";
    }

    private static ImagePlus image(double offset) {
        ByteProcessor processor = new ByteProcessor(16, 16);
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                processor.set(x, y, y < 8 ? 20 : (int) (180 + offset));
            }
        }
        return new ImagePlus("source", processor);
    }

    private static final class PreviewAdapter
            implements FilterParameterStage.PreviewAdapter {
        @Override public ImagePlus createSource(ConfigQcContext context) {
            return image(0.0d);
        }

        @Override public ImagePlus createFilteredPreview(ImagePlus source,
                                                         String macroContent) {
            double offset = macroContent != null && macroContent.contains("sigma=2.0")
                    ? 20.0d
                    : 0.0d;
            return image(offset);
        }

        @Override public void close(ImagePlus image) {
            if (image != null) {
                image.flush();
            }
        }
    }
}
