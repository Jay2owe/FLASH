package flash.pipeline.ui;

import flash.pipeline.image.NamedFilterLoader;
import org.junit.Test;

import static org.junit.Assert.*;

public class PresetMatcherTest {

    @Test
    public void match_exactBundledPreset() {
        String macro = NamedFilterLoader.loadFilterContent("Default");

        PresetMatcher.Match match = PresetMatcher.match(macro);

        assertNotNull(match);
        assertEquals("Default", match.presetName);
        assertFalse(match.structural);
        assertFalse(match.hasParameterOverrides());
    }

    @Test
    public void match_normalizedWhitespacePreset() {
        String macro = "  // === STANDARD CLEANUP ===  \r\n"
                + "  run(\"Gaussian Blur...\", \"sigma=2 stack\");  \r\n"
                + "run(\"Subtract Background...\", \"rolling=20 stack\");\r\n"
                + "  run(\"Median...\", \"radius=2 stack\");  \r\n\r\n";

        PresetMatcher.Match match = PresetMatcher.match(macro);

        assertNotNull(match);
        assertEquals("Default", match.presetName);
        assertFalse(match.structural);
    }

    @Test
    public void match_structuralPresetWithParameterOverrides() {
        String macro = "// === STANDARD CLEANUP ===\n"
                + "run(\"Gaussian Blur...\", \"sigma=4 stack\");\n"
                + "run(\"Subtract Background...\", \"rolling=35 stack\");\n"
                + "run(\"Median...\", \"radius=2 stack\");\n";

        PresetMatcher.Match match = PresetMatcher.match(macro);

        assertNotNull(match);
        assertEquals("Default", match.presetName);
        assertTrue(match.structural);
        assertTrue(match.hasParameterOverrides());
        assertEquals("4", match.parameterOverrides.get(Integer.valueOf(0)).get("sigma"));
        assertEquals("35", match.parameterOverrides.get(Integer.valueOf(1)).get("rolling"));
    }

    @Test
    public void match_returnsNullForNoMatch() {
        String macro = "run(\"Gaussian Blur...\", \"sigma=2 stack\");\n"
                + "selectWindow(\"Something Else\");\n";

        assertNull(PresetMatcher.match(macro));
    }
}
