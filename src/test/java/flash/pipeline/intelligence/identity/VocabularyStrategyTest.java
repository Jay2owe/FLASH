package flash.pipeline.intelligence.identity;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/** Unit tests for {@link VocabularyStrategy} (pure, no ImageJ). */
public class VocabularyStrategyTest {

    private final VocabularyStrategy v = new VocabularyStrategy();

    @Test
    public void detectsZtTimepointGenotypeAndRegion() {
        PartialIdentity p = v.detectOne("WT_ZT06_M14_SCN.tif");
        assertEquals("ZT6", p.conditions().get("timepoint").value);   // canonicalised
        assertEquals(Confidence.HIGH, p.conditions().get("timepoint").confidence);
        assertEquals("WT", p.conditions().get("genotype").value);
        assertEquals("SCN", p.region().value);
        assertNull(p.hemisphere());
        assertNull(p.animal());                                       // never guesses animal
    }

    @Test
    public void ztMatchesAfterUnderscoreAndWithSeparator() {
        assertEquals("ZT6", v.detectOne("x_ZT06_y").conditions().get("timepoint").value);
        assertEquals("CT12", v.detectOne("exp_CT_12_a").conditions().get("timepoint").value);
        // glued to trailing letters is NOT a timepoint (avoids false positives)
        assertNull(v.detectOne("ZT8only").conditions().get("timepoint"));
    }

    @Test
    public void detectsHemisphereAndRegionAfterSeparators() {
        PartialIdentity p = v.detectOne("Mouse5_LH_SCN");
        assertEquals("LH", p.hemisphere().value);
        assertEquals("SCN", p.region().value);
        assertNull(p.conditions().get("genotype"));
        assertNull(p.conditions().get("timepoint"));
    }

    @Test
    public void multiAxisGenotypeAndTimepointTogether() {
        PartialIdentity p = v.detectOne("hAPP_ZT08_RH_SCN");
        assertEquals("hAPP", p.conditions().get("genotype").value);
        assertEquals("ZT8", p.conditions().get("timepoint").value);
        assertEquals("RH", p.hemisphere().value);
        assertEquals("SCN", p.region().value);
        assertEquals(2, p.conditions().size());
    }

    @Test
    public void dateIsNotATimepointOrCondition() {
        PartialIdentity p = v.detectOne("20231104_KO_M14_SCN");
        assertNull(p.conditions().get("timepoint"));
        assertEquals("KO", p.conditions().get("genotype").value);
        assertEquals("SCN", p.region().value);
    }

    @Test
    public void noFalsePositiveZtMidWord() {
        PartialIdentity p = v.detectOne("REACT12_M14");   // "CT12" is mid-word
        assertNull(p.conditions().get("timepoint"));
    }
}
