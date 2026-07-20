package flash.pipeline.naming;

import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Multi-axis {@code conditions} map on {@link NameParts} (Stage 03). */
public class NamePartsConditionsTest {

    @Test
    public void existingConstructors_haveEmptyConditionsMap() {
        NameParts np = new NameParts("Exp", "M5", "LH", "Cortex", true);
        assertNotNull(np.conditions);
        assertTrue(np.conditions.isEmpty());
    }

    @Test
    public void mapOverload_populatesConditions() {
        Map<String, String> axes = new LinkedHashMap<String, String>();
        axes.put("genotype", "hAPP");
        axes.put("timepoint", "WeekFour");
        NameParts np = new NameParts("Exp", "M5", "LH", "SCN", "", axes, true, "raw");
        assertEquals("hAPP", np.conditions.get("genotype"));
        assertEquals("WeekFour", np.conditions.get("timepoint"));
    }

    @Test
    public void mapOverload_derivesCompositeWhenConditionBlank() {
        Map<String, String> axes = new LinkedHashMap<String, String>();
        axes.put("genotype", "hAPP");
        axes.put("timepoint", "WeekFour");
        NameParts np = new NameParts("Exp", "M5", "LH", "SCN", "", axes, true, "raw");
        assertEquals("hAPP_WeekFour", np.condition);
    }

    @Test
    public void mapOverload_compositeSkipsBlankAxisValues() {
        Map<String, String> axes = new LinkedHashMap<String, String>();
        axes.put("genotype", "hAPP");
        axes.put("timepoint", "");
        axes.put("sex", "F");
        NameParts np = new NameParts("Exp", "M5", "LH", "SCN", "", axes, true, "raw");
        assertEquals("hAPP_F", np.condition);
    }

    @Test
    public void mapOverload_explicitConditionWins() {
        Map<String, String> axes = new LinkedHashMap<String, String>();
        axes.put("genotype", "hAPP");
        NameParts np = new NameParts("Exp", "M5", "LH", "SCN", "Custom", axes, true, "raw");
        assertEquals("Custom", np.condition);
        // map is still carried verbatim
        assertEquals("hAPP", np.conditions.get("genotype"));
    }

    @Test
    public void nullMap_isEmptyNotNull() {
        NameParts np = new NameParts("Exp", "M5", "LH", "SCN", "WT", null, true, "raw");
        assertNotNull(np.conditions);
        assertTrue(np.conditions.isEmpty());
        assertEquals("WT", np.condition);
    }

    @Test
    public void conditionsMap_isUnmodifiable() {
        Map<String, String> axes = new LinkedHashMap<String, String>();
        axes.put("genotype", "hAPP");
        NameParts np = new NameParts("Exp", "M5", "LH", "SCN", "", axes, true, "raw");
        try {
            np.conditions.put("sex", "F");
            fail("conditions map should be unmodifiable");
        } catch (UnsupportedOperationException expected) {
            // expected
        }
    }

    @Test
    public void conditionsMap_isDefensiveCopy() {
        Map<String, String> axes = new LinkedHashMap<String, String>();
        axes.put("genotype", "hAPP");
        NameParts np = new NameParts("Exp", "M5", "LH", "SCN", "", axes, true, "raw");
        axes.put("timepoint", "WeekFour");          // mutate source after construction
        assertTrue(np.conditions.size() == 1);      // internal state unaffected
        assertEquals("hAPP", np.conditions.get("genotype"));
    }
}
