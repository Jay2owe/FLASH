package flash.pipeline.intelligence.identity;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;

/** Unit tests for the naming-grammar DSL (capture + alias + multi-axis). */
public class GrammarInterpreterTest {

    /** The design-doc worked example grammar: Syn/hAPP/NLGF x Week2/4/8. */
    private static NamingGrammar brancaccioGrammar() {
        List<FieldRule> rules = new ArrayList<FieldRule>();
        rules.add(FieldRule.capture(FieldRule.Type.ANIMAL, "", "(?<=_)M\\d+"));
        rules.add(FieldRule.alias(FieldRule.Type.HEMISPHERE, "", Arrays.asList(
                new ValuePattern("LH", Collections.singletonList("(?<![A-Za-z0-9])1(?![A-Za-z0-9])")),
                new ValuePattern("RH", Collections.singletonList("(?<![A-Za-z0-9])2(?![A-Za-z0-9])")))));
        rules.add(FieldRule.alias(FieldRule.Type.REGION, "", Arrays.asList(
                new ValuePattern("SCN", Collections.singletonList("SCN")),
                new ValuePattern("PVN", Collections.singletonList("PVN")))));
        rules.add(FieldRule.alias(FieldRule.Type.CONDITION, "Genotype", Arrays.asList(
                new ValuePattern("Syn", Collections.singletonList("Syn")),
                new ValuePattern("hAPP", Collections.singletonList("hAPP")),
                new ValuePattern("NLGF", Collections.singletonList("NLGF")))));
        rules.add(FieldRule.alias(FieldRule.Type.CONDITION, "Timepoint", Arrays.asList(
                new ValuePattern("WeekTwo", Arrays.asList("Week[_ ]?Two", "W2", "2wk")),
                new ValuePattern("WeekFour", Arrays.asList("Week[_ ]?Four", "W4", "4wk")),
                new ValuePattern("WeekEight", Arrays.asList("Week[_ ]?Eight", "W8", "8wk")))));
        return new NamingGrammar("Brancaccio", rules);
    }

    @Test
    public void workedExampleResolvesAllFields() {
        PartialIdentity p = new GrammarInterpreter().apply(brancaccioGrammar(), "hAPP_M14_2_SCN_WeekFour");
        assertEquals("M14", p.animal().value);
        assertEquals("RH", p.hemisphere().value);            // alias _2 -> RH
        assertEquals("SCN", p.region().value);
        assertEquals("hAPP", p.conditions().get("genotype").value);
        assertEquals("WeekFour", p.conditions().get("timepoint").value);
        assertEquals(Confidence.HIGH, p.animal().confidence);
        assertEquals(2, p.conditions().size());
    }

    @Test
    public void aliasFirstMatchWinsAndLhMaps() {
        PartialIdentity p = new GrammarInterpreter().apply(brancaccioGrammar(), "Syn_hAPP_M3_1_PVN");
        assertEquals("Syn", p.conditions().get("genotype").value);   // Syn rule precedes hAPP
        assertEquals("PVN", p.region().value);
        assertEquals("LH", p.hemisphere().value);                    // alias _1 -> LH
        assertEquals("M3", p.animal().value);
    }

    @Test
    public void captureUsesGroupOneWhenPresent() {
        List<FieldRule> rules = Collections.singletonList(
                FieldRule.capture(FieldRule.Type.ANIMAL, "", "animal-(\\w+)-end"));
        PartialIdentity p = new GrammarInterpreter().apply(new NamingGrammar("g", rules), "animal-XYZ-end");
        assertEquals("XYZ", p.animal().value);
    }

    @Test
    public void blankConditionAxisDefaultsToConditionInsteadOfDropping() {
        // A CONDITION rule with a blank axis label must map to the implicit primary
        // "condition" axis, not be silently discarded (matches NthTokenFieldStrategy).
        List<FieldRule> rules = Collections.singletonList(
                FieldRule.alias(FieldRule.Type.CONDITION, "",
                        Collections.singletonList(new ValuePattern("WT", Collections.singletonList("WT")))));
        PartialIdentity p = new GrammarInterpreter().apply(new NamingGrammar("g", rules), "M1_WT_SCN");
        assertEquals(1, p.conditions().size());
        assertEquals("WT", p.conditions().get("condition").value);
    }

    @Test
    public void whitespaceConditionAxisYieldsConditionAxisAndCleanProvenance() {
        // A whitespace-only axis label must behave like a blank one: value under the
        // "condition" axis, and provenance must not echo the raw whitespace.
        List<FieldRule> rules = Collections.singletonList(
                FieldRule.alias(FieldRule.Type.CONDITION, "   ",
                        Collections.singletonList(new ValuePattern("WT", Collections.singletonList("WT")))));
        PartialIdentity p = new GrammarInterpreter().apply(new NamingGrammar("g", rules), "M1_WT_SCN");
        assertEquals("WT", p.conditions().get("condition").value);
        assertEquals("your pattern (condition)", p.conditions().get("condition").provenance);
    }

    @Test
    public void grammarBeatsVocabularyInResolver() {
        SourceRecord r = SourceRecord.looseFile("hAPP_M14_2_SCN_ZT06", null, null);
        List<SourceRecord> batch = Collections.singletonList(r);
        IdentityResolver resolver = new IdentityResolver(Arrays.<IdentityStrategy>asList(
                new GrammarStrategy(brancaccioGrammar()),
                new VocabularyStrategy()));
        IdentityCandidate c = resolver.resolve(batch).get(r);
        // Genotype: both say hAPP, but grammar wins precedence -> "your pattern" provenance.
        assertEquals("hAPP", c.conditionValue("Genotype"));
        assertEquals("your pattern (Genotype)", c.getCondition("Genotype").provenance);
        // Grammar's Timepoint is Week-based and didn't match ZT06; vocabulary fills it.
        assertEquals("ZT6", c.conditionValue("Timepoint"));
        assertEquals("M14", c.animalValue());
    }
}
