package flash.pipeline.ui.variations;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;

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
        assertEquals("Gaussian Blur", result.readablePreset("Default")
                .chainSummary());
        assertEquals("Median", result.readablePreset("Clustered Large")
                .chainSummary());
    }

    private static String macroFor(String presetName) {
        if ("Clustered Large".equals(presetName)) {
            return "run(\"Median...\", \"radius=3 stack\");";
        }
        return "run(\"Gaussian Blur...\", \"sigma=1 stack\");";
    }
}
