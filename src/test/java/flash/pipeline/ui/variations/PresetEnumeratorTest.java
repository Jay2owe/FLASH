package flash.pipeline.ui.variations;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class PresetEnumeratorTest {

    @Test
    public void filtersCustomAndDuplicatesWhilePreservingReadableOrder() {
        PresetEnumerator.Result result = new PresetEnumerator(
                Arrays.asList("Default", "Clustered Large", "Custom", "Default"),
                new FilterVariationEngineContext.PresetMacroLoader() {
                    @Override public String loadPresetMacro(String presetName) {
                        return macroFor(presetName);
                    }
                }).enumerate();

        List<String> names = result.readableNames();
        assertEquals(Arrays.asList("Default", "Clustered Large"), names);
        assertTrue(result.readablePreset("Default").numericParamKeys()
                .contains("sigma"));
        assertTrue(result.readablePreset("Clustered Large").numericParamKeys()
                .contains("radius"));
    }

    private static String macroFor(String presetName) {
        if ("Clustered Large".equals(presetName)) {
            return "run(\"Median...\", \"radius=3 stack\");";
        }
        return "run(\"Gaussian Blur...\", \"sigma=1 stack\");";
    }
}
