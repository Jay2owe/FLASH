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
import java.io.File;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class MacroVariationsDialogAcceptCallbackTest {

    @Test
    public void useComboAcceptsRenderedSelectedFilterMacro() throws Exception {
        Assume.assumeFalse("PipelineDialog creates a JDialog in this codebase.",
                GraphicsEnvironment.isHeadless());
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
                    assertEquals(1, dialog.cellsForTest().size());
                    assertFalse(dialog.useComboButtonForTest().isEnabled());
                    dialog.cellsForTest().get(0).clickForTest(false);
                    assertTrue(dialog.useComboButtonForTest().isEnabled());
                    dialog.useComboButtonForTest().doClick();
                }
            });

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
