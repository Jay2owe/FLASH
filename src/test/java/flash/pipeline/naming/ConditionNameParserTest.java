package flash.pipeline.naming;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class ConditionNameParserTest {

    @Test
    public void detectCondition_singleConditionTrailingNumber() {
        assertEquals("NLGF", ConditionNameParser.detectCondition("NLGF11"));
        assertEquals("WT", ConditionNameParser.detectCondition("WT3"));
    }

    @Test
    public void detectCondition_embeddedAnimalNumberKeepsSuffixCondition() {
        assertEquals("SynWeekTwo", ConditionNameParser.detectCondition("Syn1WeekTwo"));
        assertEquals("SynWeekTwo", ConditionNameParser.detectCondition("Syn2WeekTwo"));
        assertEquals("hAPPWeekEight", ConditionNameParser.detectCondition("hAPP1WeekEight"));
        assertEquals("hAPPWeekEight", ConditionNameParser.detectCondition("hAPP2WeekEight"));
    }

    @Test
    public void detectCondition_preservesExplicitSeparatorsAroundConditionSuffix() {
        assertEquals("Syn_WeekTwo", ConditionNameParser.detectCondition("Syn_1_WeekTwo"));
        assertEquals("Syn-WeekEight", ConditionNameParser.detectCondition("Syn-2-WeekEight"));
        assertEquals("Syn_WeekTwo", ConditionNameParser.detectCondition("Syn1_WeekTwo"));
    }

    @Test
    public void detectCondition_supportsNamesThatStartWithDigits() {
        assertEquals("5xFADWeekTwo", ConditionNameParser.detectCondition("5xFAD1WeekTwo"));
        assertEquals("5xFAD", ConditionNameParser.detectCondition("5xFAD12"));
    }

    @Test
    public void detectCondition_returnsWholeNameWhenNoNumericAnimalIdExists() {
        assertEquals("SynWeekTwo", ConditionNameParser.detectCondition("SynWeekTwo"));
        assertEquals("Condition_A", ConditionNameParser.detectCondition("Condition_A"));
    }

    @Test
    public void detectCondition_fallsBackSafelyForBlankOrUnstructuredNames() {
        assertEquals("", ConditionNameParser.detectCondition(null));
        assertEquals("", ConditionNameParser.detectCondition("  "));
        assertEquals("123", ConditionNameParser.detectCondition("123"));
    }
}
