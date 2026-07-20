package flash.pipeline.ui.variations;

import flash.pipeline.image.FilterMacroEditorModel;
import flash.pipeline.testutil.UiTestAssumptions;
import flash.pipeline.ui.config.ConfigQcContext;
import flash.pipeline.ui.config.FilterParameterStage;

import ij.ImagePlus;
import ij.process.ByteProcessor;

import org.junit.Test;

import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class MacroVariationsDialogIntegrationTest {

    @Test(timeout = 10000)
    public void selectedTileCanExportPipelineFigureThenAcceptMacro()
            throws Exception {
        UiTestAssumptions.assumeInteractiveUiTestsEnabled();
        final AtomicReference<MacroVariationsDialog> ref =
                new AtomicReference<MacroVariationsDialog>();
        final AtomicReference<String> accepted =
                new AtomicReference<String>();

        SwingUtilities.invokeAndWait(new Runnable() {
            @Override public void run() {
                MacroVariationsDialog dialog = new MacroVariationsDialog(null,
                        context(), new Consumer<String>() {
                    @Override public void accept(String macro) {
                        accepted.set(macro);
                    }
                });
                dialog.setMode(MacroVariationsDialog.Mode.FULL_SWEEP);
                configureOneComboSweep(dialog.editorForTest());
                assertFalse(dialog.exportPipelineFigureButtonForTest().isEnabled());
                assertFalse(dialog.useComboButtonForTest().isEnabled());
                dialog.runButtonForTest().doClick();
                ref.set(dialog);
            }
        });

        final MacroVariationsDialog dialog = ref.get();
        final File requested = new File(
                "target/macro-variations-dialog-integration/exported-pipeline");
        final File exported = new File(requested.getPath() + ".png");
        try {
            dialog.waitForDoneForTest(5000L);
            SwingUtilities.invokeAndWait(new Runnable() {
                @Override public void run() {
                    assertTrue(dialog.cellsForTest().size() > 0);
                    assertFalse(dialog.exportPipelineFigureButtonForTest().isEnabled());
                    dialog.cellsForTest().get(1).clickForTest(false);
                    assertTrue(dialog.exportPipelineFigureButtonForTest().isEnabled());
                    assertTrue(dialog.useComboButtonForTest().isEnabled());
                    dialog.setPipelineFigureExportFileForTest(requested);
                    dialog.exportPipelineFigureButtonForTest().doClick();
                    dialog.useComboButtonForTest().doClick();
                }
            });

            BufferedImage image = ImageIO.read(exported);
            assertNotNull(image);
            assertTrue(image.getWidth() > image.getHeight());
            assertNotNull(accepted.get());
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
        ImagePlus source = image("source", 30);
        FilterMacroEditorModel.MacroDefinition macro =
                FilterMacroEditorModel.parse(macroText());
        ConfigQcContext config = ConfigQcContext.fromImages(new File("."),
                new File("target/macro-variations-dialog-integration-bin"),
                null, Collections.singletonList(source),
                Collections.singletonList("DAPI"), 0);
        return new FilterVariationEngineContext(macro, source, CropSpec.full(),
                "DAPI", config, new PreviewAdapter());
    }

    private static String macroText() {
        return "run(\"Gaussian Blur...\", \"sigma=1 stack\");\n"
                + "run(\"Subtract Background...\", \"rolling=25 stack\");\n"
                + "run(\"Median...\", \"radius=2 stack\");";
    }

    private static ImagePlus image(String title, int centreValue) {
        ByteProcessor processor = new ByteProcessor(16, 16);
        processor.set(15, 15, 255);
        for (int y = 6; y <= 9; y++) {
            for (int x = 6; x <= 9; x++) {
                processor.set(x, y, centreValue);
            }
        }
        return new ImagePlus(title, processor);
    }

    private static int countRuns(String macroContent) {
        int count = 0;
        String safe = macroContent == null ? "" : macroContent;
        int index = -1;
        while ((index = safe.indexOf("run(", index + 1)) >= 0) {
            count++;
        }
        return count;
    }

    private static final class PreviewAdapter
            implements FilterParameterStage.PreviewAdapter {
        @Override public ImagePlus createSource(ConfigQcContext context) {
            return image("source", 30);
        }

        @Override public ImagePlus createFilteredPreview(ImagePlus source,
                                                         String macroContent) {
            int steps = countRuns(macroContent);
            int centre = 40 + steps * 40;
            if (macroContent != null && macroContent.contains("sigma=2.0")) {
                centre += 15;
            }
            return image("filtered-" + steps, centre);
        }

        @Override public void close(ImagePlus image) {
            if (image != null) {
                image.flush();
            }
        }
    }
}
