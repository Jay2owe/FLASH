package flash.pipeline.project;

import org.junit.Test;

import java.io.IOException;
import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class ProjectFileCodecRoundTripTest {

    @Test
    public void emptyProjectRoundTrip() throws Exception {
        ProjectFile project = new ProjectFile();

        ProjectFile back = ProjectFileCodec.decode(ProjectFileCodec.encode(project));

        assertEquals(1, back.schemaVersion);
        assertEquals(0, back.items.size());
        assertNull(back.name);
        assertNull(back.outputRoot);
    }

    @Test
    public void singleItemRoundTrip() throws Exception {
        ProjectFile project = new ProjectFile();
        project.writerId = "FLASH-test";
        project.writtenAtMillis = 1716912000000L;
        project.name = "Cohort A";
        project.outputRoot = "D:/FLASH-projects/cohort-A";

        ProjectFile.Item item = new ProjectFile.Item();
        item.path = "D:/raw/batch1/Exp1-03_LH_Hb.lif";
        item.series.addAll(Arrays.asList(Integer.valueOf(0), Integer.valueOf(1)));
        item.animalId = "03";
        item.hemisphere = "LH";
        item.region = "Hb";
        item.condition = "WT";
        item.notes = "checked";
        project.items.add(item);

        ProjectFile back = ProjectFileCodec.decode(ProjectFileCodec.encode(project));

        assertEquals("FLASH-test", back.writerId);
        assertEquals(1716912000000L, back.writtenAtMillis);
        assertEquals("Cohort A", back.name);
        assertEquals("D:/FLASH-projects/cohort-A", back.outputRoot);
        assertEquals(1, back.items.size());

        ProjectFile.Item decoded = back.items.get(0);
        assertEquals("D:/raw/batch1/Exp1-03_LH_Hb.lif", decoded.path);
        assertEquals(Arrays.asList(Integer.valueOf(0), Integer.valueOf(1)), decoded.series);
        assertTrue(decoded.include);
        assertEquals("03", decoded.animalId);
        assertEquals("LH", decoded.hemisphere);
        assertEquals("Hb", decoded.region);
        assertEquals("WT", decoded.condition);
        assertEquals("checked", decoded.notes);
    }

    @Test
    public void multiItemMixedConditionRoundTrip() throws Exception {
        ProjectFile project = exampleProject();

        String json = ProjectFileCodec.encode(project);
        ProjectFile back = ProjectFileCodec.decode(json);

        assertTrue(json.contains("\"condition\": \"WT\""));
        assertTrue(json.contains("\"condition\": \"KO\""));
        assertEquals(2, back.items.size());
        assertItemEquals(project.items.get(0), back.items.get(0));
        assertItemEquals(project.items.get(1), back.items.get(1));
    }

    @Test
    public void emptySeriesListMeansAllSeries() throws Exception {
        ProjectFile project = new ProjectFile();
        ProjectFile.Item item = new ProjectFile.Item();
        item.path = "X.lif";
        project.items.add(item);

        ProjectFile back = ProjectFileCodec.decode(ProjectFileCodec.encode(project));

        assertNotNull(back.items.get(0).series);
        assertTrue(back.items.get(0).series.isEmpty());
    }

    @Test
    public void includeDefaultsToTrueOnDecode() throws Exception {
        String json = "{\"schemaVersion\":1,\"items\":[{\"path\":\"X.lif\"}]}";

        ProjectFile decoded = ProjectFileCodec.decode(json);

        assertTrue(decoded.items.get(0).include);
    }

    @Test
    public void extrasArePreservedOnRoundTrip() throws Exception {
        String json = "{"
                + "\"schemaVersion\":1,"
                + "\"writerId\":\"FLASH-test\","
                + "\"writtenAtMillis\":1,"
                + "\"name\":\"P\","
                + "\"outputRoot\":\"D:/o\","
                + "\"items\":[{\"path\":\"X.lif\",\"series\":[],\"include\":true,"
                + "\"animalId\":null,\"hemisphere\":null,\"region\":null,"
                + "\"condition\":null,\"notes\":null,\"futureItemKey\":\"keep-me\"}],"
                + "\"futureRootKey\":{\"nested\":true}"
                + "}";

        ProjectFile decoded = ProjectFileCodec.decode(json);
        String encoded = ProjectFileCodec.encode(decoded);
        ProjectFile back = ProjectFileCodec.decode(encoded);

        assertEquals("keep-me", back.items.get(0).extras.get("futureItemKey"));
        assertTrue(back.extras.containsKey("futureRootKey"));
        assertTrue(encoded.contains("\"futureRootKey\""));
        assertTrue(encoded.contains("\"futureItemKey\""));
    }

    @Test
    public void unknownSeriesTokensAreSkipped() throws Exception {
        String json = "{\"schemaVersion\":1,\"items\":[{\"path\":\"X.lif\",\"series\":[0,\"abc\",2]}]}";

        ProjectFile decoded = ProjectFileCodec.decode(json);

        assertEquals(Arrays.asList(Integer.valueOf(0), Integer.valueOf(2)),
                decoded.items.get(0).series);
    }

    @Test
    public void decodeOrNullReturnsNullOnGarbage() {
        assertNull(ProjectFileCodec.decodeOrNull("{not json"));
    }

    @Test
    public void decodeRejectsWrongSchemaVersion() throws Exception {
        try {
            ProjectFileCodec.decode("{\"schemaVersion\":2,\"items\":[]}");
            fail("Expected IOException");
        } catch (IOException e) {
            assertTrue(e.getMessage().contains("schemaVersion"));
            assertTrue(e.getMessage().contains("2"));
        }
    }

    private static ProjectFile exampleProject() {
        ProjectFile project = new ProjectFile();
        project.writerId = "FLASH-1.5.0";
        project.writtenAtMillis = 1716912000000L;
        project.name = "Cohort A";
        project.outputRoot = "D:/FLASH-projects/cohort-A";
        project.items.add(item("D:/raw/wt/Exp1-03_LH_Hb.lif",
                Arrays.asList(Integer.valueOf(0)), true, "03", "LH", "Hb", "WT", ""));
        project.items.add(item("D:/raw/ko/Exp1-07_RH_Hb.lif",
                Arrays.asList(Integer.valueOf(0), Integer.valueOf(1)), true, "07", "RH", "Hb", "KO", "tile-stitched"));
        return project;
    }

    private static ProjectFile.Item item(String path, java.util.List<Integer> series, boolean include,
                                         String animalId, String hemisphere, String region,
                                         String condition, String notes) {
        ProjectFile.Item item = new ProjectFile.Item();
        item.path = path;
        item.series.addAll(series);
        item.include = include;
        item.animalId = animalId;
        item.hemisphere = hemisphere;
        item.region = region;
        item.condition = condition;
        item.notes = notes;
        return item;
    }

    private static void assertItemEquals(ProjectFile.Item expected, ProjectFile.Item actual) {
        assertNotNull(actual);
        assertEquals(expected.path, actual.path);
        assertEquals(expected.series, actual.series);
        assertEquals(expected.include, actual.include);
        assertEquals(expected.animalId, actual.animalId);
        assertEquals(expected.hemisphere, actual.hemisphere);
        assertEquals(expected.region, actual.region);
        assertEquals(expected.condition, actual.condition);
        assertEquals(expected.notes, actual.notes);
    }
}
