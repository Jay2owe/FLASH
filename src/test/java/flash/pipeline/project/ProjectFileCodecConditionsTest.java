package flash.pipeline.project;

import flash.pipeline.naming.ConditionAxis;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Round-trip of the N-axis condition fields through {@link ProjectFileCodec}. */
public class ProjectFileCodecConditionsTest {

    @Test
    public void multiAxisRoundTrip() throws Exception {
        ProjectFile pf = new ProjectFile();
        pf.schemaVersion = 1;
        pf.conditionAxes.add(ConditionAxis.of("Genotype"));
        pf.conditionAxes.add(ConditionAxis.of("Timepoint"));

        ProjectFile.Item item = new ProjectFile.Item();
        item.path = "/data/study.lif";
        item.animalId = "M14";
        item.condition = "hAPP_WeekFour";
        item.conditions.put("genotype", "hAPP");
        item.conditions.put("timepoint", "WeekFour");

        ProjectFile.SeriesItem s = new ProjectFile.SeriesItem();
        s.index = 0;
        s.name = "M14_LH_SCN";
        s.animalId = "M14";
        s.conditions.put("genotype", "hAPP");
        s.conditions.put("timepoint", "WeekFour");
        item.seriesMeta.add(s);
        pf.items.add(item);

        ProjectFile back = ProjectFileCodec.decode(ProjectFileCodec.encode(pf));

        assertEquals(2, back.conditionAxes.size());
        assertEquals("genotype", back.conditionAxes.get(0).id);
        assertEquals("Genotype", back.conditionAxes.get(0).label);

        ProjectFile.Item bi = back.items.get(0);
        assertEquals("hAPP", bi.conditions.get("genotype"));
        assertEquals("WeekFour", bi.conditions.get("timepoint"));
        assertEquals("hAPP", bi.seriesMeta.get(0).conditions.get("genotype"));
        assertEquals("WeekFour", bi.seriesMeta.get(0).conditions.get("timepoint"));
    }

    @Test
    public void legacyProjectWithoutConditionsDecodesEmpty() throws Exception {
        String legacy = "{\n"
                + "  \"schemaVersion\": 1,\n"
                + "  \"name\": \"old\",\n"
                + "  \"items\": [\n"
                + "    { \"path\": \"/x.lif\", \"animalId\": \"M1\", \"condition\": \"WT\" }\n"
                + "  ]\n"
                + "}";
        ProjectFile back = ProjectFileCodec.decode(legacy);
        assertTrue(back.conditionAxes.isEmpty());
        ProjectFile.Item it = back.items.get(0);
        assertEquals("WT", it.condition);          // legacy single condition preserved
        assertTrue(it.conditions.isEmpty());        // no multi-axis map
    }

    @Test
    public void conditionFieldsOmittedFromJsonWhenEmpty() throws Exception {
        ProjectFile pf = new ProjectFile();
        ProjectFile.Item item = new ProjectFile.Item();
        item.path = "/x.lif";
        item.condition = "WT";
        pf.items.add(item);
        String json = ProjectFileCodec.encode(pf);
        assertFalse(json.contains("\"conditions\""));
        assertFalse(json.contains("conditionAxes"));
    }
}
