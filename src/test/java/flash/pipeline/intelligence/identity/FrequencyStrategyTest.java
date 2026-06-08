package flash.pipeline.intelligence.identity;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Unit tests for {@link FrequencyStrategy} (pure, set-aware). */
public class FrequencyStrategyTest {

    @Test
    public void variesAnimalRepeatsCondition() {
        // APP_{KO|WT}_m{n}_SCN: pos0 constant, pos1 condition (2), pos2 animal (24), pos3 constant
        List<SourceRecord> batch = new ArrayList<SourceRecord>();
        for (int i = 1; i <= 12; i++) {
            batch.add(SourceRecord.looseFile("APP_KO_m" + i + "_SCN.tif", null, null));
        }
        for (int i = 13; i <= 24; i++) {
            batch.add(SourceRecord.looseFile("APP_WT_m" + i + "_SCN.tif", null, null));
        }
        Map<SourceRecord, PartialIdentity> r = new FrequencyStrategy().detect(batch);

        PartialIdentity ko = r.get(batch.get(0));   // APP_KO_m1_SCN
        assertEquals("m1", ko.animal().value);
        assertEquals("KO", ko.conditions().get("condition").value);
        assertEquals(Confidence.MEDIUM, ko.animal().confidence);

        PartialIdentity wt = r.get(batch.get(12));  // APP_WT_m13_SCN
        assertEquals("m13", wt.animal().value);
        assertEquals("WT", wt.conditions().get("condition").value);
    }

    @Test
    public void smallBatchIsNoOp() {
        List<SourceRecord> batch = Arrays.asList(
                SourceRecord.looseFile("APP_KO_m1_SCN.tif", null, null),
                SourceRecord.looseFile("APP_WT_m2_SCN.tif", null, null),
                SourceRecord.looseFile("APP_KO_m3_SCN.tif", null, null));
        Map<SourceRecord, PartialIdentity> r = new FrequencyStrategy().detect(batch);
        assertNull(r.get(batch.get(0)).animal());
        assertTrue(r.get(batch.get(0)).conditions().isEmpty());
    }

    @Test
    public void containerSeriesSeedTokenisesSeriesName() {
        List<SourceRecord> batch = new ArrayList<SourceRecord>();
        for (int i = 1; i <= 8; i++) {
            batch.add(SourceRecord.containerSeries("study.lif", i, "KO_m" + i, null, null));
        }
        for (int i = 9; i <= 16; i++) {
            batch.add(SourceRecord.containerSeries("study.lif", i, "WT_m" + i, null, null));
        }
        Map<SourceRecord, PartialIdentity> r = new FrequencyStrategy().detect(batch);
        PartialIdentity p = r.get(batch.get(0));   // series "KO_m1"
        assertEquals("m1", p.animal().value);
        assertEquals("KO", p.conditions().get("condition").value);
    }
}
