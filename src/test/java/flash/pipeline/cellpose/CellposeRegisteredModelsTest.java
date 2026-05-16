package flash.pipeline.cellpose;

import flash.pipeline.segmentation.catalog.ModelCatalogIO;
import flash.pipeline.segmentation.catalog.ModelEntry;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class CellposeRegisteredModelsTest {

    @Test
    public void parsesListModelsResponseIntoCatalogEntries() throws Exception {
        Path customPath = Files.createTempFile("microglia_cp", ".pth");
        String json = "{\"id\":\"x\",\"models\":["
                + "{\"name\":\"cyto3\",\"builtin\":true},"
                + "{\"name\":\"microglia_cp\",\"builtin\":false,\"path\":\""
                + escaped(customPath.toString()) + "\"}"
                + "]}";

        List<ModelEntry> entries = CellposeRegisteredModels.entriesFromResponse(
                json, Collections.<String>emptyList());

        ModelEntry builtin = find(entries, "cellpose_cyto3");
        assertEquals(ModelEntry.Engine.CELLPOSE, builtin.engine);
        assertEquals(ModelEntry.Source.STOCK_BUILTIN, builtin.source);
        assertEquals("cyto3", builtin.pretrainedModel.get());

        ModelEntry custom = find(entries, "microglia_cp");
        assertEquals(ModelEntry.Engine.CELLPOSE, custom.engine);
        assertEquals(ModelEntry.Source.USER_IMPORTED, custom.source);
        assertEquals(customPath.toAbsolutePath().normalize().toString(),
                custom.filePath.get());
        assertEquals("microglia_cp", custom.pretrainedModel.get());
    }

    @Test
    public void skipsAlreadyPresentModelKeys() throws Exception {
        String json = "{\"id\":\"x\",\"models\":["
                + "{\"name\":\"cyto3\",\"builtin\":true},"
                + "{\"name\":\"microglia_cp\",\"builtin\":false}"
                + "]}";

        List<ModelEntry> entries = CellposeRegisteredModels.entriesFromResponse(
                json, Arrays.asList("cellpose_cyto3", "microglia_cp"));

        assertTrue(entries.isEmpty());
    }

    @Test
    public void marksDiscoveredEntriesWithMetadataFlag() throws Exception {
        String json = "{\"id\":\"x\",\"models\":["
                + "{\"name\":\"microglia_cp\",\"builtin\":false}"
                + "]}";

        List<ModelEntry> entries = CellposeRegisteredModels.entriesFromResponse(
                json, Collections.<String>emptyList());

        assertFalse(entries.isEmpty());
        assertEquals(ModelCatalogIO.CELLPOSE_DISCOVERY_SOURCE,
                entries.get(0).metadata.get(ModelCatalogIO.DISCOVERED_FROM_METADATA_KEY));
        assertTrue(CellposeRegisteredModels.isDiscoveredCellposeEntry(entries.get(0)));
    }

    private static ModelEntry find(List<ModelEntry> entries, String key) {
        for (ModelEntry entry : entries) {
            if (key.equals(entry.modelKey)) {
                return entry;
            }
        }
        throw new AssertionError("Missing entry: " + key);
    }

    private static String escaped(String text) {
        return text.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
