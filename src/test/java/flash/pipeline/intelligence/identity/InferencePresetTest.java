package flash.pipeline.intelligence.identity;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Stage 13: inference preset strategies — parent-folder junk/date rejection + token/prefix. */
public class InferencePresetTest {

    @Test
    public void parentFolder_rejectsJunkAndDateFolders() {
        ParentFolderConditionStrategy s = new ParentFolderConditionStrategy();
        assertTrue(s.isJunkFolder("Images"));
        assertTrue(s.isJunkFolder("raw"));
        assertTrue(s.isJunkFolder("LIF"));
        assertTrue(s.isJunkFolder("export"));
        assertTrue(s.isJunkFolder("2024-01-15"));
        assertTrue(s.isJunkFolder("20240115"));
        assertTrue(s.isJunkFolder("2024"));
        assertTrue(s.isJunkFolder("12"));
        assertTrue(s.isJunkFolder(""));
        // genuine condition folders survive
        assertFalse(s.isJunkFolder("WT"));
        assertFalse(s.isJunkFolder("Control"));
        assertFalse(s.isJunkFolder("hAPP"));
    }

    @Test
    public void parentFolder_emitsConditionFromValidParent() {
        List<SourceRecord> batch = Arrays.asList(
                SourceRecord.looseFile("m1.lif", "WT", "study"),
                SourceRecord.looseFile("m2.lif", "KO", "study"),
                SourceRecord.looseFile("m3.lif", "Images", "study"));   // junk parent -> skipped
        Map<SourceRecord, PartialIdentity> r = new ParentFolderConditionStrategy().detect(batch);

        assertEquals("WT", r.get(batch.get(0)).conditions().get("condition").value);
        assertEquals("KO", r.get(batch.get(1)).conditions().get("condition").value);
        assertNull(r.get(batch.get(2)));   // junk parent yields no opinion
    }

    @Test
    public void parentFolder_fallsBackToGrandparentForBalancedGroups() {
        // parent level is per-slide (too many groups); grandparent is the condition.
        List<SourceRecord> batch = Arrays.asList(
                SourceRecord.looseFile("m1.lif", "slide1", "WT"),
                SourceRecord.looseFile("m2.lif", "slide2", "WT"),
                SourceRecord.looseFile("m3.lif", "slide3", "KO"),
                SourceRecord.looseFile("m4.lif", "slide4", "KO"));
        Map<SourceRecord, PartialIdentity> r = new ParentFolderConditionStrategy().detect(batch);
        assertEquals("WT", r.get(batch.get(0)).conditions().get("condition").value);
        assertEquals("KO", r.get(batch.get(2)).conditions().get("condition").value);
    }

    @Test
    public void nthToken_assignsField() {
        List<SourceRecord> batch = new ArrayList<SourceRecord>();
        batch.add(SourceRecord.looseFile("APP_KO_m1_SCN.tif", null, null));
        Map<SourceRecord, PartialIdentity> r =
                new NthTokenFieldStrategy(2, FieldRule.Type.CONDITION, "Genotype").detect(batch);
        assertEquals("KO", r.get(batch.get(0)).conditions().get("genotype").value);
    }

    @Test
    public void animalPrefix_derivesConditionFromName() {
        List<SourceRecord> batch = Arrays.asList(
                SourceRecord.looseFile("hAPP3Week2_LH_SCN.tif", null, null));
        Map<SourceRecord, PartialIdentity> r = new AnimalPrefixConditionStrategy().detect(batch);
        PartialIdentity p = r.get(batch.get(0));
        assertFalse(p.conditions().get("condition").value.isEmpty());
    }
}
