package flash.pipeline.ui.variations;

import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

public class ParameterComboTest {

    @Test
    public void canonicalJsonIsStableAcrossEquivalentMapInsertionOrder() {
        Map<ParameterId, Object> firstValues = new LinkedHashMap<ParameterId, Object>();
        firstValues.put(ParameterId.THRESHOLD, Double.valueOf(42.5d));
        firstValues.put(ParameterId.MIN_SIZE, Integer.valueOf(10));
        firstValues.put(ParameterId.MAX_SIZE, Integer.valueOf(100));

        Map<ParameterId, Object> secondValues = new LinkedHashMap<ParameterId, Object>();
        secondValues.put(ParameterId.MAX_SIZE, Integer.valueOf(100));
        secondValues.put(ParameterId.THRESHOLD, Double.valueOf(42.5d));
        secondValues.put(ParameterId.MIN_SIZE, Integer.valueOf(10));

        ParameterCombo first = new ParameterCombo(firstValues);
        ParameterCombo second = new ParameterCombo(secondValues);

        assertEquals(first.toCanonicalJson(), second.toCanonicalJson());
        assertEquals("{\"MAX_SIZE\":100,\"MIN_SIZE\":10,\"THRESHOLD\":42.5}",
                first.toCanonicalJson());
    }

    @Test
    public void macroValueSerializesAsTokenNotRawScript() {
        String script = "run(\"Gaussian Blur...\", \"sigma=2 stack\");";
        String expectedToken = MacroToken.forScript(
                MacroToken.SOURCE_PASTED, "", script).value();

        ParameterCombo combo = ParameterCombo.builder()
                .put(ParameterId.MACRO, script)
                .build();

        assertEquals("{\"MACRO\":\"" + expectedToken + "\"}",
                combo.toCanonicalJson());
        assertFalse(combo.toCanonicalJson().contains("Gaussian Blur"));
    }
}
