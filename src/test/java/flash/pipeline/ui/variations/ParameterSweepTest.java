package flash.pipeline.ui.variations;

import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class ParameterSweepTest {

    @Test
    public void combosBuildCartesianProductInDeterministicOrder() {
        ParameterSweep sweep = sweepWithScrambledInputOrder();

        List<ParameterCombo> combos = sweep.combos();

        assertEquals(6L, sweep.cellCount());
        assertEquals(6, combos.size());
        assertEquals(Integer.valueOf(1), combos.get(0).get(ParameterId.THRESHOLD));
        assertEquals(Integer.valueOf(10), combos.get(0).get(ParameterId.MIN_SIZE));
        assertEquals(Integer.valueOf(100), combos.get(0).get(ParameterId.MAX_SIZE));
        assertEquals(Integer.valueOf(1), combos.get(1).get(ParameterId.THRESHOLD));
        assertEquals(Integer.valueOf(20), combos.get(1).get(ParameterId.MIN_SIZE));
        assertEquals(Integer.valueOf(2), combos.get(2).get(ParameterId.THRESHOLD));
        assertEquals(Integer.valueOf(3), combos.get(5).get(ParameterId.THRESHOLD));
        assertEquals(Integer.valueOf(20), combos.get(5).get(ParameterId.MIN_SIZE));
    }

    @Test
    public void combosDoNotDependOnMapInsertionOrder() {
        ParameterSweep first = sweepWithScrambledInputOrder();

        Map<ParameterId, ParameterValueList> values = new LinkedHashMap<ParameterId, ParameterValueList>();
        values.put(ParameterId.THRESHOLD, ParameterValueList.ofInts(1, 2, 3));
        values.put(ParameterId.MIN_SIZE, ParameterValueList.ofInts(10, 20));
        values.put(ParameterId.MAX_SIZE, ParameterValueList.ofInts(100));
        ParameterSweep second = new ParameterSweep(ParameterSweep.Method.CLASSICAL,
                values, CropSpec.full(), "DAPI", "abc");

        assertEquals(first.combos(), second.combos());
    }

    @Test
    public void macroAxisUsesTokenValuesAndCanonicalMetadata() {
        MacroVariation blur = MacroVariation.pasted("Blur",
                "run(\"Gaussian Blur...\", \"sigma=2 stack\");");
        MacroVariation median = MacroVariation.pasted("Median",
                "run(\"Median...\", \"radius=2 stack\");");
        MacroVariationSet macros = MacroVariationSet.of(blur, median);
        Map<ParameterId, ParameterValueList> values =
                new LinkedHashMap<ParameterId, ParameterValueList>();
        values.put(ParameterId.THRESHOLD, ParameterValueList.ofInts(100));
        values.put(ParameterId.MACRO,
                ParameterValueList.ofStrings(blur.token(), median.token()));

        ParameterSweep sweep = new ParameterSweep(ParameterSweep.Method.CLASSICAL,
                values, CropSpec.full(), "DAPI", "abc", macros);

        assertEquals(2L, sweep.cellCount());
        assertEquals(blur.token(), sweep.combos().get(0).get(ParameterId.MACRO));
        String json = sweep.toCanonicalJson();
        assertTrue(json.contains("\"MACRO\""));
        assertTrue(json.contains("\"macroVariations\""));
        assertTrue(json.contains(blur.normalizedScriptHash()));
        assertFalse(json.contains("Gaussian Blur"));
        assertEquals(json, sweep.toCanonicalJson());
    }

    @Test
    public void macroSweepValuesNormalizeRawScriptToToken() {
        String script = "run(\"Gaussian Blur...\", \"sigma=2 stack\");";
        String expectedToken = MacroToken.forScript(
                MacroToken.SOURCE_PASTED, "", script).value();
        Map<ParameterId, ParameterValueList> values =
                new LinkedHashMap<ParameterId, ParameterValueList>();
        values.put(ParameterId.MACRO, ParameterValueList.ofStrings(script));

        ParameterSweep sweep = new ParameterSweep(ParameterSweep.Method.CLASSICAL,
                values, CropSpec.full(), "DAPI", "abc");

        assertEquals(expectedToken,
                sweep.valueLists().get(ParameterId.MACRO).get(0));
        assertFalse(sweep.toCanonicalJson().contains("Gaussian Blur"));
    }

    @Test
    public void cellCountSaturatesWhenCartesianProductWouldOverflowLong() {
        Map<ParameterKey, ParameterValueList> values =
                new LinkedHashMap<ParameterKey, ParameterValueList>();
        ParameterValueList tenValues = ParameterValueList.ofInts(
                0, 1, 2, 3, 4, 5, 6, 7, 8, 9);
        for (int i = 0; i < 19; i++) {
            values.put(new TestKey("k" + i), tenValues);
        }
        ParameterSweep sweep = new ParameterSweep(ParameterSweep.Method.CLASSICAL,
                values, CropSpec.full(), "DAPI", "abc");

        assertEquals(Long.MAX_VALUE, sweep.cellCount());
        try {
            sweep.combos();
            fail("Expected oversized sweep to be rejected before allocation.");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("too many parameter combinations"));
        }
    }

    private static ParameterSweep sweepWithScrambledInputOrder() {
        Map<ParameterId, ParameterValueList> values = new LinkedHashMap<ParameterId, ParameterValueList>();
        values.put(ParameterId.MAX_SIZE, ParameterValueList.ofInts(100));
        values.put(ParameterId.MIN_SIZE, ParameterValueList.ofInts(10, 20));
        values.put(ParameterId.THRESHOLD, ParameterValueList.ofInts(1, 2, 3));
        return new ParameterSweep(ParameterSweep.Method.CLASSICAL,
                values, CropSpec.full(), "DAPI", "abc");
    }

    private static final class TestKey implements ParameterKey {
        private final String key;

        TestKey(String key) {
            this.key = key;
        }

        @Override public String stableKey() {
            return key;
        }

        @Override public String displayLabel() {
            return key;
        }

        @Override public ValueKind valueKind() {
            return ValueKind.NUMBER;
        }
    }
}
