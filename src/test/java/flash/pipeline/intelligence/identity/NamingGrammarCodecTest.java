package flash.pipeline.intelligence.identity;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Round-trip tests for {@link NamingGrammarCodec}. */
public class NamingGrammarCodecTest {

    private static NamingGrammar grammar() {
        List<FieldRule> rules = new ArrayList<FieldRule>();
        rules.add(FieldRule.capture(FieldRule.Type.ANIMAL, "", "(?<=_)M\\d+"));
        rules.add(FieldRule.alias(FieldRule.Type.HEMISPHERE, "", Arrays.asList(
                new ValuePattern("LH", Collections.singletonList("(?<![A-Za-z0-9])1(?![A-Za-z0-9])")),
                new ValuePattern("RH", Collections.singletonList("(?<![A-Za-z0-9])2(?![A-Za-z0-9])")))));
        rules.add(FieldRule.alias(FieldRule.Type.CONDITION, "Genotype", Arrays.asList(
                new ValuePattern("hAPP", Collections.singletonList("hAPP")),
                new ValuePattern("NLGF", Collections.singletonList("NLGF")))));
        rules.add(FieldRule.alias(FieldRule.Type.CONDITION, "Timepoint", Arrays.asList(
                new ValuePattern("WeekFour", Arrays.asList("Week[_ ]?Four", "W4")))));
        return new NamingGrammar("Brancaccio", rules);
    }

    @Test
    public void roundTripPreservesBehaviour() throws Exception {
        String json = NamingGrammarCodec.toJson(grammar());
        NamingGrammar restored = NamingGrammarCodec.fromJson(json);

        assertEquals("Brancaccio", restored.name);
        assertEquals(4, restored.rules.size());

        // The restored grammar resolves a seed identically to the original.
        PartialIdentity p = new GrammarInterpreter().apply(restored, "hAPP_M14_2_SCN_WeekFour");
        assertEquals("M14", p.animal().value);
        assertEquals("RH", p.hemisphere().value);
        assertEquals("hAPP", p.conditions().get("genotype").value);
        assertEquals("WeekFour", p.conditions().get("timepoint").value);
    }

    @Test
    public void jsonContainsExpectedStructure() {
        String json = NamingGrammarCodec.toJson(grammar());
        assertTrue(json.contains("\"name\":\"Brancaccio\""));
        assertTrue(json.contains("\"axisLabel\":\"Genotype\""));
        assertTrue(json.contains("\"mode\":\"capture\""));
        assertTrue(json.contains("\"mode\":\"alias\""));
    }
}
