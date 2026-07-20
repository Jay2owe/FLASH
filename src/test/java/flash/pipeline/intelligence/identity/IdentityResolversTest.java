package flash.pipeline.intelligence.identity;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** End-to-end test of the standard resolver stack on a realistic mixed batch. */
public class IdentityResolversTest {

    @Test
    public void standardStackResolvesMultiSourceIdentity() {
        // 8 loose TIFFs: two genotypes x two timepoints x two hemispheres/regions,
        // unique animal per file. No user grammar -> vocabulary + frequency + legacy.
        List<SourceRecord> batch = new ArrayList<SourceRecord>();
        for (int i = 1; i <= 4; i++) {
            batch.add(SourceRecord.looseFile("hAPP_M" + i + "_LH_SCN_ZT06.tif", null, null));
        }
        for (int i = 5; i <= 8; i++) {
            batch.add(SourceRecord.looseFile("WT_M" + i + "_RH_PVN_ZT12.tif", null, null));
        }

        Map<SourceRecord, IdentityCandidate> resolved = IdentityResolvers.standard().resolve(batch);

        IdentityCandidate c0 = resolved.get(batch.get(0));   // hAPP_M1_LH_SCN_ZT06
        assertEquals("M1", c0.animalValue());                          // frequency (varying token)
        assertTrue(c0.getAnimal().provenance.contains("frequency"));
        assertEquals("LH", c0.hemisphereValue());                      // vocabulary
        assertEquals("SCN", c0.regionValue());                         // vocabulary
        assertEquals("hAPP", c0.conditionValue("Genotype"));           // vocabulary
        assertEquals("ZT6", c0.conditionValue("Timepoint"));           // vocabulary (canonicalised)

        IdentityCandidate c7 = resolved.get(batch.get(7));   // WT_M8_RH_PVN_ZT12
        assertEquals("M8", c7.animalValue());
        assertEquals("RH", c7.hemisphereValue());
        assertEquals("PVN", c7.regionValue());
        assertEquals("WT", c7.conditionValue("Genotype"));
        assertEquals("ZT12", c7.conditionValue("Timepoint"));
    }

    @Test
    public void userGrammarOverridesVocabulary() {
        List<FieldRule> rules = new ArrayList<FieldRule>();
        rules.add(FieldRule.alias(FieldRule.Type.REGION, "",
                java.util.Collections.singletonList(
                        new ValuePattern("Suprachiasmatic", java.util.Collections.singletonList("SCN")))));
        NamingGrammar grammar = new NamingGrammar("custom", rules);

        SourceRecord r = SourceRecord.looseFile("hAPP_M1_LH_SCN_ZT06.tif", null, null);
        IdentityCandidate c = IdentityResolvers.standard(grammar)
                .resolve(java.util.Collections.singletonList(r)).get(r);

        // Grammar maps SCN -> "Suprachiasmatic" and beats the vocabulary's "SCN".
        assertEquals("Suprachiasmatic", c.regionValue());
        assertEquals("your pattern (region)", c.getRegion().provenance);
    }
}
