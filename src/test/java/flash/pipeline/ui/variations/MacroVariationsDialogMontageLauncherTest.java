package flash.pipeline.ui.variations;

import flash.pipeline.image.FilterMacroEditorModel;
import flash.pipeline.ui.config.ConfigQcContext;
import flash.pipeline.ui.config.FilterParameterStage;
import flash.pipeline.ui.preview.VariationMontageDialog;

import ij.ImagePlus;
import ij.process.ByteProcessor;

import org.junit.Assume;
import org.junit.Test;

import javax.swing.SwingUtilities;
import java.awt.GraphicsEnvironment;
import java.io.File;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class MacroVariationsDialogMontageLauncherTest {

    @Test
    public void buttonEnablesAfterCompletedTileAndOpensMontage()
            throws Exception {
        Assume.assumeFalse("Variation montage creates a JDialog.",
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
                assertFalse(dialog.openLargeMontageButtonForTest().isEnabled());
                configureOneComboSweep(dialog.editorForTest());
                dialog.runButtonForTest().doClick();
                ref.set(dialog);
            }
        });

        final MacroVariationsDialog dialog = ref.get();
        try {
            dialog.waitForDoneForTest(5000L);
            SwingUtilities.invokeAndWait(new Runnable() {
                @Override public void run() {
                    assertTrue(dialog.openLargeMontageButtonForTest().isEnabled());
                    dialog.openLargeMontageButtonForTest().doClick();

                    VariationMontageDialog montage = dialog.montageDialogForTest();
                    assertNotNull(montage);
                    assertEquals(1, tileCount(montage));
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

    private static int tileCount(VariationMontageDialog montage) {
        try {
            Method method =
                    VariationMontageDialog.class.getDeclaredMethod("tileCountForTest");
            method.setAccessible(true);
            return ((Integer) method.invoke(montage)).intValue();
        } catch (Exception e) {
            throw new AssertionError("Could not read montage tile count", e);
        }
    }

    private static FilterVariationEngineContext context() {
        ImagePlus source = image("source", 30);
        FilterMacroEditorModel.MacroDefinition macro =
                FilterMacroEditorModel.parse(macroText());
        ConfigQcContext config = ConfigQcContext.fromImages(new File("."),
                new File("target/macro-variations-dialog-montage-test-bin"),
                null, Collections.singletonList(source),
                Collections.singletonList("DAPI"), 0);
        return new FilterVariationEngineContext(macro, source, CropSpec.full(),
                "DAPI", config, new PreviewAdapter());
    }

    private static String macroText() {
        return "run(\"Gaussian Blur...\", \"sigma=1 stack\");";
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

    private static final class PreviewAdapter
            implements FilterParameterStage.PreviewAdapter {
        @Override public ImagePlus createSource(ConfigQcContext context) {
            return image("source", 30);
        }

        @Override public ImagePlus createFilteredPreview(ImagePlus source,
                                                         String macroContent) {
            return image("filtered", 80);
        }

        @Override public void close(ImagePlus image) {
            if (image != null) {
                image.flush();
            }
        }
    }
}
