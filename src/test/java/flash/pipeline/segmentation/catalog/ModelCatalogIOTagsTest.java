package flash.pipeline.segmentation.catalog;

import flash.pipeline.ui.wizard.JsonIO;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ModelCatalogIOTagsTest {
    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void tagsRoundTripThroughCatalogJson() throws Exception {
        Path root = temp.newFolder("tags").toPath();
        LinkedHashSet<String> tags = new LinkedHashSet<String>(
                Arrays.asList("microglia", "trained"));
        ModelEntry entry = new ModelEntry("stardist_tagged", "Tagged model", null,
                ModelEntry.Engine.STARDIST, ModelEntry.Source.USER_IMPORTED,
                "files/stardist_tagged/model.zip", null, null, null, null,
                tags, defaults(), null, false);

        ModelCatalogIO.writeProject(root, new ModelCatalog(root, Arrays.asList(entry)));
        ModelEntry loaded = ModelCatalogIO.read(root).get("stardist_tagged").get();

        assertEquals(tags, loaded.tags);

        Map<String, Object> json = JsonIO.parseObject(new String(
                Files.readAllBytes(ModelCatalogIO.catalogFile(root)), StandardCharsets.UTF_8));
        Map<String, Object> first = JsonIO.asObject(JsonIO.asList(json.get("models")).get(0));
        assertEquals(2, JsonIO.asList(first.get("tags")).size());
        assertTrue(JsonIO.asList(first.get("tags")).contains("microglia"));
    }

    private static Map<String, Object> defaults() {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("probThresh", Double.valueOf(0.5));
        out.put("nmsThresh", Double.valueOf(0.3));
        return out;
    }
}
