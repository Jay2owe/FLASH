package flash.pipeline.ui.variations;

import flash.pipeline.image.FilterMacroEditorModel;
import flash.pipeline.ui.config.ConfigQcContext;
import flash.pipeline.ui.config.FilterParameterStage;

import ij.ImagePlus;
import ij.process.ByteProcessor;

import org.junit.Assume;
import org.junit.Test;

import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.awt.GraphicsEnvironment;
import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class MacroVariationsDialogPresetsRowLabelsTest {

    @Test
    public void presetsModeProvidesPresetNamesAndChainCaptionsToLeftGutter()
            throws Exception {
        Assume.assumeFalse("PipelineDialog creates a JDialog in this codebase.",
                GraphicsEnvironment.isHeadless());
        final MacroVariationsDialog[] holder = new MacroVariationsDialog[1];

        SwingUtilities.invokeAndWait(new Runnable() {
            @Override public void run() {
                MacroVariationsDialog dialog = new MacroVariationsDialog(null,
                        context(), new Consumer<String>() {
                            @Override public void accept(String macro) {
                            }
                        });
                dialog.setMode(MacroVariationsDialog.Mode.PRESETS);
                dialog.configurePresetsForTest(Arrays.asList("Default", "Median"),
                        "sigma",
                        Arrays.<Object>asList(Double.valueOf(0.5d),
                                Double.valueOf(1.0d)));
                holder[0] = dialog;
            }
        });

        try {
            SwingUtilities.invokeAndWait(new Runnable() {
                @Override public void run() {
                    MacroVariationsDialog dialog = holder[0];
                    Map<String, String> captions =
                            dialog.gridPanelForTest().presetRowCaptionsForTest();
                    assertEquals(2, captions.size());
                    assertTrue(captions.get("Default").contains("Gaussian"));
                    assertTrue(captions.get("Median").contains("Median"));

                    AxisGutterPanel left = leftGutter(dialog.gridPanelForTest());
                    assertNotNull(left);
                    assertFalse(left.valueCaptionsForTest().isEmpty());
                    assertTrue(left.valueCaptionsForTest().get("Default")
                            .contains("Gaussian"));
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

    private static AxisGutterPanel leftGutter(Container root) {
        List<AxisGutterPanel> gutters = descendants(root, AxisGutterPanel.class);
        for (int i = 0; i < gutters.size(); i++) {
            AxisGutterPanel gutter = gutters.get(i);
            if (gutter.getPreferredSize().width == AxisGutterPanel.PRESET_LEFT_WIDTH) {
                return gutter;
            }
        }
        return null;
    }

    private static <T> List<T> descendants(Component root, Class<T> type) {
        List<T> out = new java.util.ArrayList<T>();
        collect(root, type, out);
        return out;
    }

    private static <T> void collect(Component component, Class<T> type, List<T> out) {
        if (component == null) {
            return;
        }
        if (type.isInstance(component)) {
            out.add(type.cast(component));
        }
        if (component instanceof Container) {
            Component[] children = ((Container) component).getComponents();
            for (int i = 0; i < children.length; i++) {
                collect(children[i], type, out);
            }
        }
    }

    private static FilterVariationEngineContext context() {
        ImagePlus source = image();
        ConfigQcContext config = ConfigQcContext.fromImages(new File("."),
                new File("target/macro-variations-dialog-presets-labels-bin"),
                null, Collections.singletonList(source),
                Collections.singletonList("DAPI"), 0);
        return new FilterVariationEngineContext(
                FilterMacroEditorModel.parse(macro("Default")),
                source,
                CropSpec.full(),
                "DAPI",
                config,
                new PreviewAdapter(),
                Arrays.asList("Default", "Median", "Custom"),
                new FilterVariationEngineContext.PresetMacroLoader() {
                    @Override public String loadPresetMacro(String presetName) {
                        return macro(presetName);
                    }
                });
    }

    private static String macro(String presetName) {
        if ("Median".equals(presetName)) {
            return "run(\"Median...\", \"radius=2 sigma=1 stack\");";
        }
        return "run(\"Gaussian Blur...\", \"sigma=1 stack\");";
    }

    private static ImagePlus image() {
        ByteProcessor processor = new ByteProcessor(16, 16);
        processor.setValue(1);
        processor.fill();
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
        }
    }
}
