package flash.pipeline.atlas;

import ij.measure.ResultsTable;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class AtlasRegionColumnsTest {

    @Test
    public void metadataResolvesFromRegionBeforeRoi() {
        AtlasRegionColumns.Metadata metadata =
                AtlasRegionColumns.metadataForExistingOrResolved("", "", "", "",
                        "CA1", "SCN12");

        assertEquals("allen_mouse_25um", metadata.getAtlasKey());
        assertEquals("382", metadata.getRegionId());
        assertEquals("CA1", metadata.getRegionAcronym());
        assertEquals("Field CA1", metadata.getRegionName());
    }

    @Test
    public void metadataFallsBackToRoiSectionLabel() {
        AtlasRegionColumns.Metadata metadata =
                AtlasRegionColumns.metadataForExistingOrResolved("", "", "", "",
                        "not an atlas region", "SCN12");

        assertEquals("allen_mouse_25um", metadata.getAtlasKey());
        assertEquals("286", metadata.getRegionId());
        assertEquals("SCH", metadata.getRegionAcronym());
        assertEquals("Suprachiasmatic nucleus", metadata.getRegionName());
    }

    @Test
    public void metadataFallsBackToRoiNumericId() {
        AtlasRegionColumns.Metadata metadata =
                AtlasRegionColumns.metadataForExistingOrResolved("", "", "", "",
                        "not an atlas region", "286");

        assertEquals("allen_mouse_25um", metadata.getAtlasKey());
        assertEquals("286", metadata.getRegionId());
        assertEquals("SCH", metadata.getRegionAcronym());
        assertEquals("Suprachiasmatic nucleus", metadata.getRegionName());
    }

    @Test
    public void existingMetadataIsPreservedWhenPresent() {
        AtlasRegionColumns.Metadata metadata =
                AtlasRegionColumns.metadataForExistingOrResolved("custom_atlas", "42", "ABC", "Existing",
                        "CA1", "SCN");

        assertEquals("custom_atlas", metadata.getAtlasKey());
        assertEquals("42", metadata.getRegionId());
        assertEquals("ABC", metadata.getRegionAcronym());
        assertEquals("Existing", metadata.getRegionName());
    }

    @Test
    public void writeToAddsStringAtlasColumns() {
        ResultsTable table = new ResultsTable();
        table.incrementCounter();

        AtlasRegionColumns.writeTo(table, 0, "CA1", "");

        assertEquals("allen_mouse_25um", table.getStringValue(AtlasRegionColumns.ATLAS_KEY, 0));
        assertEquals("382", table.getStringValue(AtlasRegionColumns.REGION_ID, 0));
        assertEquals("CA1", table.getStringValue(AtlasRegionColumns.REGION_ACRONYM, 0));
    }

    @Test
    public void addHeadersIsIdempotent() {
        List<String> headers = new ArrayList<String>();
        headers.add("Region");
        AtlasRegionColumns.addHeaders(headers);
        AtlasRegionColumns.addHeaders(headers);

        assertEquals(5, headers.size());
        assertTrue(headers.contains(AtlasRegionColumns.REGION_NAME));
    }
}
