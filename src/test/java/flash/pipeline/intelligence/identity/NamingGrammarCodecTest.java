package flash.pipeline.intelligence.identity;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

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
        assertTrue(json.contains("\"schemaVersion\":1"));
        assertTrue(json.contains("\"name\":\"Brancaccio\""));
        assertTrue(json.contains("\"axisLabel\":\"Genotype\""));
        assertTrue(json.contains("\"mode\":\"capture\""));
        assertTrue(json.contains("\"mode\":\"alias\""));
    }

    @Test
    public void versionedUnicodeSaveLoadSaveIsByteStable() throws Exception {
        NamingGrammar unicode = new NamingGrammar("Étude 🧠", Arrays.asList(
                FieldRule.capture(FieldRule.Type.ANIMAL, "", "動物-(\\d+)"),
                FieldRule.alias(FieldRule.Type.CONDITION, "Génotype", Collections.singletonList(
                        new ValuePattern("βアミロイド",
                                Collections.singletonList("β[_ ]?アミロイド"))))));

        String first = NamingGrammarCodec.toJson(unicode);
        NamingGrammar restored = NamingGrammarCodec.fromJson(first);
        String second = NamingGrammarCodec.toJson(restored);

        assertEquals(first, second);
        assertEquals("Étude 🧠", restored.name);
        assertEquals("Génotype", restored.rules.get(1).axisLabel);
        assertEquals("βアミロイド", restored.rules.get(1).values.get(0).canonical);
    }

    @Test
    public void legacyDocumentMigratesToCurrentVersionDeterministically() throws Exception {
        String legacy = "{\"name\":\"legacy\",\"fields\":[]}";
        NamingGrammar restored = NamingGrammarCodec.fromJson(legacy);
        String migrated = NamingGrammarCodec.toJson(restored);
        assertTrue(migrated.startsWith("{\"schemaVersion\":1,"));
        assertEquals(migrated, NamingGrammarCodec.toJson(NamingGrammarCodec.fromJson(migrated)));
    }

    @Test
    public void futureSchemaIsReportedSeparately() throws Exception {
        try {
            NamingGrammarCodec.fromJson("{\"schemaVersion\":2,\"name\":\"future\",\"fields\":[]}");
            fail("Expected future schema rejection.");
        } catch (NamingGrammarCodec.UnsupportedVersionException expected) {
            assertEquals(2, expected.version());
        }
    }

    @Test
    public void malformedJsonIsClassifiedAsCorrupt() throws Exception {
        try {
            NamingGrammarCodec.fromJson("{\"schemaVersion\":1,");
            fail("Expected corrupt JSON rejection.");
        } catch (NamingGrammarCodec.CorruptGrammarException expected) {
            assertTrue(expected.getMessage().contains("Could not parse"));
        }
    }

    @Test
    public void catastrophicPatternsAndBackreferencesAreRejectedBeforeCompilation() throws Exception {
        assertUnsafe("^(a+)+$");
        assertUnsafe("(a|aa)+$");
        assertUnsafe("(a)\\1");
        assertUnsafe(".*suffix");
    }

    @Test
    public void overlappingAliasMatchesAreRejectedAsAmbiguous() {
        FieldRule rule = FieldRule.alias(FieldRule.Type.HEMISPHERE, "", Arrays.asList(
                new ValuePattern("LH", Collections.singletonList("side")),
                new ValuePattern("RH", Collections.singletonList("side"))));
        try {
            new GrammarInterpreter().apply(
                    new NamingGrammar("ambiguous", Collections.singletonList(rule)), "animal_side_region");
            fail("Expected ambiguous alias rejection.");
        } catch (GrammarInterpreter.AmbiguousMatchException expected) {
            assertTrue(expected.getMessage().contains("both 'LH' and 'RH'"));
        }
    }

    @Test
    public void documentedLookbehindBoundariesRemainAccepted() {
        ValuePattern.compileSafe("(?<=_)M\\d+");
        ValuePattern.compileSafe("(?<![A-Za-z0-9])2(?![A-Za-z0-9])");
    }

    private static void assertUnsafe(String expression) throws Exception {
        String json = "{\"schemaVersion\":1,\"name\":\"unsafe\",\"fields\":["
                + "{\"type\":\"ANIMAL\",\"mode\":\"capture\",\"pattern\":"
                + flash.pipeline.intelligence.MiniJson.write(expression) + "}]}";
        try {
            NamingGrammarCodec.fromJson(json);
            fail("Expected unsafe rejection for " + expression);
        } catch (NamingGrammarCodec.UnsafeGrammarException expected) {
            assertTrue(expected.getMessage().contains("Unsafe naming pattern"));
        }
    }
}
