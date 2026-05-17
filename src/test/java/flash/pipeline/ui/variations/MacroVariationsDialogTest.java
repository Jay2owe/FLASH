package flash.pipeline.ui.variations;

import flash.pipeline.image.FilterMacroEditorModel;
import flash.pipeline.ui.config.ConfigQcContext;
import flash.pipeline.ui.config.FilterParameterStage;

import ij.ImagePlus;
import ij.ImageStack;
import ij.process.ByteProcessor;
import org.junit.Assume;
import org.junit.Test;

import javax.swing.SwingUtilities;
import java.awt.GraphicsEnvironment;
import java.io.File;
import java.util.Collections;
import java.util.function.Consumer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class MacroVariationsDialogTest {

    @Test
    public void constructsWithParamsModeAndEmptyGrid() throws Exception {
        Assume.assumeFalse("PipelineDialog creates a JDialog in this codebase.",
                GraphicsEnvironment.isHeadless());
        final MacroVariationsDialog[] holder = new MacroVariationsDialog[1];

        SwingUtilities.invokeAndWait(new Runnable() {
            @Override public void run() {
                holder[0] = new MacroVariationsDialog(null, context(),
                        new Consumer<String>() {
                            @Override public void accept(String macro) {
                            }
                        });
            }
        });

        try {
            SwingUtilities.invokeAndWait(new Runnable() {
                @Override public void run() {
                    MacroVariationsDialog dialog = holder[0];
                    assertNotNull(dialog);
                    assertEquals(MacroVariationsDialog.Mode.SWEEP_PARAMETER,
                            dialog.modeForTest());
                    assertTrue(dialog.sweepParamButtonForTest().isEnabled());
                    assertTrue(dialog.sweepParamButtonForTest().isSelected());
                    assertTrue(dialog.sweepStepButtonForTest().isEnabled());
                    assertTrue(dialog.sweepPresetsButtonForTest().isEnabled());
                    assertTrue(dialog.fullSweepButtonForTest().isEnabled());
                    assertTrue(dialog.sweepStepButtonForTest()
                            .getToolTipText().contains("alternatives"));
                    assertEquals("Compare readable filter presets",
                            dialog.sweepPresetsButtonForTest().getToolTipText());
                    assertNull(dialog.gridWindowForTest());
                    assertFalse(dialog.useComboButtonForTest().isEnabled());
                    assertTrue(dialog.chainRibbonLabelForTest().getText()
                            .contains("Gaussian Blur"));
                    assertEquals(ParameterSweep.Method.FILTER,
                            dialog.editorForTest().currentSweep().method());
                }
            });
        } finally {
            SwingUtilities.invokeAndWait(new Runnable() {
                @Override public void run() {
                    if (holder[0] != null) {
                        holder[0].dispose();
                    }
                }
            });
        }
    }

    private static FilterVariationEngineContext context() {
        ImagePlus source = stack("source", 3);
        FilterMacroEditorModel.MacroDefinition macro =
                FilterMacroEditorModel.parse(macroText());
        ConfigQcContext config = ConfigQcContext.fromImages(new File("."),
                new File("target/macro-variations-dialog-test-bin"),
                null, Collections.singletonList(source),
                Collections.singletonList("DAPI"), 0);
        return new FilterVariationEngineContext(macro, source, CropSpec.centre256(),
                "DAPI", config, new StubPreviewAdapter());
    }

    private static String macroText() {
        return "run(\"Gaussian Blur...\", \"sigma=2 stack\");\n"
                + "run(\"Subtract Background...\", \"rolling=20 stack\");";
    }

    private static ImagePlus stack(String title, int slices) {
        ImageStack stack = new ImageStack(16, 16);
        for (int z = 0; z < slices; z++) {
            ByteProcessor processor = new ByteProcessor(16, 16);
            processor.setValue(z + 1);
            processor.fill();
            stack.addSlice("z" + (z + 1), processor);
        }
        ImagePlus image = new ImagePlus(title, stack);
        image.setDimensions(1, slices, 1);
        return image;
    }

    private static final class StubPreviewAdapter
            implements FilterParameterStage.PreviewAdapter {
        @Override public ImagePlus createSource(ConfigQcContext context) {
            return stack("source", 1);
        }

        @Override public ImagePlus createFilteredPreview(ImagePlus source,
                                                         String macroContent) {
            return source == null ? null : source.duplicate();
        }

        @Override public void close(ImagePlus image) {
        }
    }
}
