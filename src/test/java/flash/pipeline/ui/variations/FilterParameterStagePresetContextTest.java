package flash.pipeline.ui.variations;

import flash.pipeline.ui.config.ConfigQcActions;
import flash.pipeline.ui.config.ConfigQcContext;
import flash.pipeline.ui.config.FilterParameterStage;
import flash.pipeline.ui.preview.PreviewPairPanel;

import ij.ImagePlus;
import ij.process.ByteProcessor;

import org.junit.Test;

import javax.swing.JButton;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;

public class FilterParameterStagePresetContextTest {

    @Test
    public void variationContextReceivesCurrentPresetOptionsAndLoader()
            throws Exception {
        Map<String, String> macros = new LinkedHashMap<String, String>();
        macros.put("Default", macro("sigma=1"));
        macros.put("Saved Custom", macro("sigma=3"));
        FilterParameterStage stage = new FilterParameterStage(
                Arrays.asList("Default", "Custom", "Saved Custom"),
                new MacroStore(macros),
                new PreviewAdapter(),
                null,
                null);
        ConfigQcContext context = ConfigQcContext.fromImages(null, null, null,
                Collections.singletonList(image()),
                Collections.singletonList("DAPI"), 0);

        stage.buildControls(context, new RecordingActions());
        stage.onEnter(context, new PreviewPairPanel("Original", "Adjusted"));
        FilterVariationEngineContext variationContext =
                createVariationContext(stage);

        List<String> expected = Arrays.asList("Default", "Custom", "Saved Custom");
        assertEquals(expected, variationContext.presetOptions());
        assertEquals(macro("sigma=3"),
                variationContext.presetMacroLoader()
                        .loadPresetMacro("Saved Custom"));
    }

    private static FilterVariationEngineContext createVariationContext(
            FilterParameterStage stage) throws Exception {
        Method method = FilterParameterStage.class.getDeclaredMethod(
                "createVariationContextForTest");
        method.setAccessible(true);
        return (FilterVariationEngineContext) method.invoke(stage);
    }

    private static String macro(String args) {
        return "run(\"Gaussian Blur...\", \"" + args + " stack\");";
    }

    private static ImagePlus image() {
        ByteProcessor processor = new ByteProcessor(8, 8);
        processor.setValue(4);
        processor.fill();
        return new ImagePlus("source", processor);
    }

    private static final class MacroStore implements FilterParameterStage.MacroStore {
        private final Map<String, String> macros;

        MacroStore(Map<String, String> macros) {
            this.macros = macros;
        }

        @Override public String getInitialPreset() {
            return "Default";
        }

        @Override public String loadInitialMacro() {
            return macros.get("Default");
        }

        @Override public String loadPresetMacro(String presetName) {
            return macros.get(presetName);
        }

        @Override public void save(String presetName, String macroContent) {
        }

        @Override public void saveAsPreset(String presetName, String macroContent) {
        }
    }

    private static final class PreviewAdapter implements FilterParameterStage.PreviewAdapter {
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

    private static final class RecordingActions implements ConfigQcActions {
        @Override public void setStatus(String text) {
        }

        @Override public void markPreviewStale(String text) {
        }

        @Override public void setAdjustedPreview(ImagePlus image, String text) {
        }

        @Override public void registerPreviewButton(JButton button) {
        }

        @Override public void setPreviewButtonStale(boolean stale) {
        }

        @Override public void nextImage() {
        }

        @Override public void skipCurrentImage() {
        }

        @Override public void restartStage() {
        }

        @Override public void cancel() {
        }
    }
}
