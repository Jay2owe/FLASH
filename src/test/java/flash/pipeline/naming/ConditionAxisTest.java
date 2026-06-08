package flash.pipeline.naming;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

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
    public void csvColumnName_prefixesAndStripsWhitespace() {
        assertEquals("Condition_Genotype", ConditionAxis.of("Genotype").csvColumnName());
        assertEquals("Condition_TimePoint", ConditionAxis.of("Time Point").csvColumnName());
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
}
