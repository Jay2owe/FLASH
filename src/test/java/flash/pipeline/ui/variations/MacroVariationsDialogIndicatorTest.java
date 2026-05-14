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
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class MacroVariationsDialogIndicatorTest {

    @Test
    public void oneAxisSweepShowsStableShapeRibbonAndSuggestion()
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
                    assertEquals(5, d.completedCountForTest());
                    assertNotNull(d.histogramShapeStripForTest());
                    assertTrue(d.histogramShapeStripForTest().isVisible());
                    assertTrue(d.suggestionLabelForTest().getText()
                            .contains("Most stable shape"));

                    VariationCellPanel winner = stableShapeCell(d.cellsForTest());
                    assertNotNull(winner);
                    assertEquals("STABLE SHAPE", winner.ribbonLabelForTest());
                    assertEquals(Double.valueOf(3.0d), sigmaValue(winner.combo()));
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
            if (key instanceof FilterParameterId
                    && "sigma".equals(((FilterParameterId) key).paramKey())) {
                editor.setSweptForTest(key, true);
                editor.setParameterValuesForTest(key, Arrays.<Object>asList(
                        Double.valueOf(0.0d),
                        Double.valueOf(1.0d),
                        Double.valueOf(2.0d),
                        Double.valueOf(3.0d),
                        Double.valueOf(4.0d)));
            }
        }
    }

    private static VariationCellPanel stableShapeCell(List<VariationCellPanel> cells) {
        for (int i = 0; i < cells.size(); i++) {
            VariationCellPanel cell = cells.get(i);
            if ("STABLE SHAPE".equals(cell.ribbonLabelForTest())) {
                return cell;
            }
        }
        return null;
    }

    private static Double sigmaValue(ParameterCombo combo) {
        for (Map.Entry<ParameterKey, Object> entry : combo.values().entrySet()) {
            if (entry.getKey() instanceof FilterParameterId
                    && "sigma".equals(((FilterParameterId) entry.getKey()).paramKey())
                    && entry.getValue() instanceof Number) {
                return Double.valueOf(((Number) entry.getValue()).doubleValue());
            }
        }
        return null;
    }

    private static FilterVariationEngineContext context() {
        ImagePlus source = imageForCluster(0);
        FilterMacroEditorModel.MacroDefinition macro =
                FilterMacroEditorModel.parse(macroText());
        ConfigQcContext config = ConfigQcContext.fromImages(new File("."),
                new File("target/macro-variations-dialog-indicator-test-bin"),
                null, Collections.singletonList(source),
                Collections.singletonList("DAPI"), 0);
        return new FilterVariationEngineContext(macro, source, CropSpec.full(),
                "DAPI", config, new StableShapePreviewAdapter());
    }

    private static String macroText() {
        return "run(\"Gaussian Blur...\", \"sigma=0 stack\");";
    }

    private static ImagePlus imageForCluster(int cluster) {
        ByteProcessor processor = new ByteProcessor(32, 32);
        for (int y = 0; y < 32; y++) {
            for (int x = 0; x < 32; x++) {
                int value;
                if (x == 0 && y == 0) {
                    value = 0;
                } else if (x == 1 && y == 0) {
                    value = 255;
                } else {
                    value = cluster + ((x + y) % 6);
                }
                processor.set(x, y, value);
            }
        }
        return new ImagePlus("stable-shape", processor);
    }

    private static double optionValue(String macro, String key) {
        Pattern pattern = Pattern.compile(key + "=([^\\s\";)]+)");
        Matcher matcher = pattern.matcher(macro);
        return matcher.find() ? Double.parseDouble(matcher.group(1)) : 0.0d;
    }

    private static final class StableShapePreviewAdapter
            implements FilterParameterStage.PreviewAdapter {
        @Override public ImagePlus createSource(ConfigQcContext context) {
            return imageForCluster(0);
        }

        @Override public ImagePlus createFilteredPreview(ImagePlus source,
                                                         String macroContent) {
            double sigma = optionValue(macroContent, "sigma");
            int cluster;
            if (sigma < 0.5d) {
                cluster = 0;
            } else if (sigma < 1.5d) {
                cluster = 80;
            } else {
                cluster = 200;
            }
            return imageForCluster(cluster);
        }

        @Override public void close(ImagePlus image) {
        }
    }
}
