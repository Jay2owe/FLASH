package flash.pipeline.ui.variations;

import flash.pipeline.image.FilterMacroEditorModel;
import flash.pipeline.ui.config.ConfigQcContext;
import flash.pipeline.ui.config.FilterParameterStage;

import ij.ImagePlus;
import ij.process.ByteProcessor;
import ij.process.FloatProcessor;

import org.junit.Assume;
import org.junit.Test;

import javax.swing.SwingUtilities;
import java.awt.GraphicsEnvironment;
import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MacroVariationsDialogRunTest {

    @Test
    public void runButtonPopulatesFilterSweepCells() throws Exception {
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
                configureSweep(dialog.editorForTest());
                dialog.runButtonForTest().doClick();
                ref.set(dialog);
            }
        });

        MacroVariationsDialog dialog = ref.get();
        try {
            dialog.waitForDoneForTest(5000L);
            SwingUtilities.invokeAndWait(new Runnable() {
                @Override public void run() {
                    MacroVariationsDialog d = ref.get();
                    assertEquals(6, d.gridPanelForTest().cellCountForTest());
                    assertEquals(6, d.completedCountForTest());
                    assertEquals(0, d.failedCountForTest());
                    List<VariationResult> results = d.resultsForTest();
                    assertEquals(6, results.size());
                    for (int i = 0; i < results.size(); i++) {
                        VariationResult result = results.get(i);
                        assertFalse(result.hasError());
                        assertEquals(VariationResult.Kind.FILTER, result.kind());
                        assertTrue("Expected positive SNR for result " + i,
                                result.snr() > 0.0d);
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

    private static void configureSweep(ParameterSweepEditor editor) {
        List<ParameterKey> keys = editor.parameterKeysForTest();
        for (int i = 0; i < keys.size(); i++) {
            ParameterKey key = keys.get(i);
            if (key instanceof FilterParameterId) {
                FilterParameterId id = (FilterParameterId) key;
                if ("sigma".equals(id.paramKey())) {
                    editor.setSweptForTest(id, true);
                    editor.setParameterValuesForTest(id, Arrays.<Object>asList(
                            Double.valueOf(0.5d),
                            Double.valueOf(1.0d),
                            Double.valueOf(1.5d)));
                } else if ("rolling".equals(id.paramKey())) {
                    editor.setSweptForTest(id, true);
                    editor.setParameterValuesForTest(id, Arrays.<Object>asList(
                            Integer.valueOf(25),
                            Integer.valueOf(50)));
                }
            }
        }
    }

    private static FilterVariationEngineContext context() {
        ImagePlus source = image();
        FilterMacroEditorModel.MacroDefinition macro =
                FilterMacroEditorModel.parse(macroText());
        ConfigQcContext config = ConfigQcContext.fromImages(new File("."),
                new File("target/macro-variations-dialog-run-test-bin"),
                null, Collections.singletonList(source),
                Collections.singletonList("DAPI"), 0);
        return new FilterVariationEngineContext(macro, source, CropSpec.full(),
                "DAPI", config, new SyntheticPreviewAdapter());
    }

    private static String macroText() {
        return "run(\"Gaussian Blur...\", \"sigma=1 stack\");\n"
                + "run(\"Subtract Background...\", \"rolling=25 stack\");\n"
                + "run(\"Median...\", \"radius=2 stack\");";
    }

    private static ImagePlus image() {
        ByteProcessor processor = new ByteProcessor(16, 16);
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                processor.set(x, y, y < 8 ? (x % 4) + 1 : 20 + (x % 3));
            }
        }
        return new ImagePlus("source", processor);
    }

    private static String optionValue(String macro, String key) {
        Pattern pattern = Pattern.compile(key + "=([^\\s\";)]+)");
        Matcher matcher = pattern.matcher(macro);
        return matcher.find() ? matcher.group(1) : "0";
    }

    private static final class SyntheticPreviewAdapter
            implements FilterParameterStage.PreviewAdapter {
        @Override public ImagePlus createSource(ConfigQcContext context) {
            return image();
        }

        @Override public ImagePlus createFilteredPreview(ImagePlus source,
                                                         String macroContent) {
            double sigma = Double.parseDouble(optionValue(macroContent, "sigma"));
            double rolling = Double.parseDouble(optionValue(macroContent, "rolling"));
            FloatProcessor processor = new FloatProcessor(16, 16);
            for (int y = 0; y < 16; y++) {
                for (int x = 0; x < 16; x++) {
                    float value = y < 8
                            ? (float) ((x % 5) + 1)
                            : (float) (25.0d + sigma * 5.0d
                            + rolling * 0.1d + (x % 3));
                    processor.setf(x, y, value);
                }
            }
            return new ImagePlus("filtered", processor);
        }

        @Override public void close(ImagePlus image) {
        }
    }
}
