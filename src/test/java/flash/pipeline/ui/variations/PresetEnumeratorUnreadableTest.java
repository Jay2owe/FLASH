package flash.pipeline.ui.variations;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class PresetEnumeratorUnreadableTest {

    @Test
    public void recordsUnreadablePresetsWithoutCrashing() {
        PresetEnumerator.Result result = new PresetEnumerator(
                Arrays.asList("Default", "Broken", "Empty"),
                new FilterVariationEngineContext.PresetMacroLoader() {
                    @Override public String loadPresetMacro(String presetName)
                            throws Exception {
                        if ("Broken".equals(presetName)) {
                            throw new IllegalStateException("missing preset file");
                        }
                        if ("Empty".equals(presetName)) {
                            return null;
                        }
                        return "run(\"Gaussian Blur...\", \"sigma=1 stack\");";
                    }
                }).enumerate();

        assertEquals(Arrays.asList("Default"), result.readableNames());
        assertEquals(2, result.skippedPresets().size());
        assertEquals("Broken", result.skippedPresets().get(0).name());
        assertTrue(result.skippedPresets().get(0).reason()
                .contains("missing preset file"));
        assertEquals("Empty", result.skippedPresets().get(1).name());
    }
}
