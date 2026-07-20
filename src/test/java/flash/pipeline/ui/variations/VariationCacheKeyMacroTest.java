package flash.pipeline.ui.variations;

import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

public class VariationCacheKeyMacroTest {

    @Test
    public void unchangedMacroScriptReusesCacheKeyAcrossRename() {
        String script = "run(\"Gaussian Blur...\", \"sigma=2 stack\");";
        MacroVariation first = MacroVariation.pasted("Blur", script);
        MacroVariation renamed = MacroVariation.pasted("Renamed blur", script);
        ParameterCombo combo = comboFor(first);

        ParameterSweep firstSweep = sweepFor(first);
        ParameterSweep renamedSweep = sweepFor(renamed);

        assertEquals(first.token(), renamed.token());
        assertEquals(VariationCache.keyFor(firstSweep, combo),
                VariationCache.keyFor(renamedSweep, combo));
    }

    @Test
    public void editedMacroScriptProducesDifferentCacheKey() {
        MacroVariation sigmaTwo = MacroVariation.pasted("Blur 2",
                "run(\"Gaussian Blur...\", \"sigma=2 stack\");");
        MacroVariation sigmaThree = MacroVariation.pasted("Blur 3",
                "run(\"Gaussian Blur...\", \"sigma=3 stack\");");

        assertNotEquals(VariationCache.keyFor(sweepFor(sigmaTwo), comboFor(sigmaTwo)),
                VariationCache.keyFor(sweepFor(sigmaThree), comboFor(sigmaThree)));
    }

    @Test
    public void defensiveMetadataHashSeparatesSameTokenCollision() {
        String token = "macro:adhoc:aaaaaaaaaaaaaaaa";
        MacroVariation first = MacroVariation.identityOnly(token, "Legacy",
                MacroToken.SOURCE_PASTED, "", "aaaaaaaaaaaaaaaabbbbbbbbbbbbbbbb");
        MacroVariation second = MacroVariation.identityOnly(token, "Legacy",
                MacroToken.SOURCE_PASTED, "", "aaaaaaaaaaaaaaaacccccccccccccccc");
        ParameterCombo combo = comboForToken(token);

        assertNotEquals(VariationCache.keyFor(sweepFor(first), combo),
                VariationCache.keyFor(sweepFor(second), combo));
    }

    private static ParameterSweep sweepFor(MacroVariation macro) {
        Map<ParameterId, ParameterValueList> values =
                new LinkedHashMap<ParameterId, ParameterValueList>();
        values.put(ParameterId.THRESHOLD, ParameterValueList.ofInts(128));
        values.put(ParameterId.MACRO, ParameterValueList.ofStrings(macro.token()));
        return new ParameterSweep(ParameterSweep.Method.CLASSICAL,
                values, CropSpec.full(), "DAPI", "hash",
                MacroVariationSet.of(macro));
    }

    private static ParameterCombo comboFor(MacroVariation macro) {
        return comboForToken(macro.token());
    }

    private static ParameterCombo comboForToken(String token) {
        return ParameterCombo.builder()
                .put(ParameterId.THRESHOLD, Integer.valueOf(128))
                .put(ParameterId.MACRO, token)
                .build();
    }
}
