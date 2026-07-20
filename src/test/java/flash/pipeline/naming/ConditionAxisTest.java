package flash.pipeline.naming;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/** Unit tests for {@link ConditionAxis}. */
public class ConditionAxisTest {

    @Test
    public void of_derivesIdFromLabel() {
        ConditionAxis a = ConditionAxis.of("Genotype");
        assertEquals("genotype", a.id);
        assertEquals("Genotype", a.label);
        assertEquals(0, a.order);
    }

    @Test
    public void id_normalisesSpacesAndCase() {
        assertEquals("time_point", ConditionAxis.of("Time Point").id);
        assertEquals("zt_timepoint", ConditionAxis.of("ZT / Timepoint").id);
    }

    @Test
    public void equality_isCaseInsensitiveOnId() {
        ConditionAxis a = ConditionAxis.of("genotype", "Genotype", 0);
        ConditionAxis b = ConditionAxis.of("GENOTYPE", "Genotype display", 3);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    public void differentIds_areNotEqual() {
        assertNotEquals(ConditionAxis.of("Genotype"), ConditionAxis.of("Timepoint"));
    }

    @Test
    public void csvColumnName_prefixesAndCollapsesWhitespaceToUnderscore() {
        assertEquals("Condition_Genotype", ConditionAxis.of("Genotype").csvColumnName());
        // whitespace -> "_" so the header normalises back to the same id (time_point)
        assertEquals("Condition_Time_Point", ConditionAxis.of("Time Point").csvColumnName());
    }

    @Test
    public void csvColumnName_usesIdWhenLabelDoesNotNormaliseToId() {
        // explicit id that diverges from the label: the header must encode the id so
        // the CSV round-trips to the canonical id, not the label's normalisation.
        ConditionAxis a = ConditionAxis.of("zt", "Time Point", 0);
        assertEquals("Condition_zt", a.csvColumnName());
        assertEquals("zt", ConditionAxis.normaliseId(
                a.csvColumnName().substring("Condition_".length())));
    }

    @Test
    public void explicitId_overridesLabelDerivation() {
        ConditionAxis a = ConditionAxis.of("geno", "Genotype", 2);
        assertEquals("geno", a.id);
        assertEquals(2, a.order);
    }

    @Test
    public void blankIdFallsBackToLabel() {
        ConditionAxis a = new ConditionAxis("   ", "Genotype", 0);
        assertEquals("genotype", a.id);
    }

    @Test
    public void unicodeLettersAndNumbersProduceVersionedNonemptyIds() {
        assertEquals("u1$基因型", ConditionAxis.of("基因型").id);
        assertEquals("u1$θεραπεία", ConditionAxis.of("Θεραπεία").id);
        assertEquals("u1$café2", ConditionAxis.of("Café2").id);
        assertTrue(!ConditionAxis.of("处理").id.isEmpty());
    }

    @Test
    public void unicodeUsesNfkcAndCanonicalCase() {
        assertEquals(ConditionAxis.of("Cafe\u0301").id, ConditionAxis.of("Café").id);
        assertEquals(ConditionAxis.of("ＡＧＥ").id, ConditionAxis.of("age").id);
        assertEquals("u1$σ", ConditionAxis.of("Σ").id);
    }

    @Test
    public void unsafeUnicodeIdentityCodePointsAreEncodedDeterministically() {
        ConditionAxis axis = ConditionAxis.of("处理/组");
        assertEquals("u1$处理$2F$组", axis.id);
        assertEquals(axis.id, ConditionAxis.normaliseId(axis.id));
    }

    @Test
    public void unicodeDisplayLabelRemainsSeparateAndCsvRoundTripsIdentity() {
        ConditionAxis axis = ConditionAxis.of("基因 型");
        assertEquals("基因 型", axis.label);
        assertEquals("Condition_基因_型", axis.csvColumnName());
        assertEquals(axis.id, ConditionAxis.normaliseId(
                axis.csvColumnName().substring("Condition_".length())));
    }

    @Test
    public void unknownOrMalformedVersionedIdsAreRejected() {
        assertIllegalArgument("u2$基因型");
        assertIllegalArgument("u1$");
        assertIllegalArgument("u1$abc$XYZ$");
        assertIllegalArgument("u1$abc$41$"); // letters/numbers must not hide in escapes
    }

    private static void assertIllegalArgument(String id) {
        try {
            ConditionAxis.normaliseId(id);
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError("Expected invalid condition identity to be rejected: " + id);
    }
}
