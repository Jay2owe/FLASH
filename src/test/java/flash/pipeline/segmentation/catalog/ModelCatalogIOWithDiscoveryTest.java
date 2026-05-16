package flash.pipeline.segmentation.catalog;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ModelCatalogIOWithDiscoveryTest {
    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void readMergesStubbedCellposeDiscoveryIntoCatalog() throws Exception {
        Path root = temp.newFolder("discovery").toPath();
        final ModelEntry discovered = discovered("cellpose_auto_microglia",
                "Auto microglia");

        ModelCatalogIO.setDiscoveryProviderForTests(new ModelCatalogIO.DiscoveryProvider() {
            @Override
            public List<ModelEntry> fetch(List<String> existingModelKeys) {
                assertTrue(existingModelKeys.contains("cellpose_cyto3"));
                return Arrays.asList(discovered);
            }
        });
        try {
            ModelCatalog catalog = ModelCatalogIO.read(root);

            ModelEntry loaded = catalog.get(discovered.modelKey).get();
            assertEquals("Auto microglia", loaded.name);
            assertEquals(ModelCatalogIO.CELLPOSE_DISCOVERY_SOURCE,
                    loaded.metadata.get(ModelCatalogIO.DISCOVERED_FROM_METADATA_KEY));
        } finally {
            ModelCatalogIO.setDiscoveryProviderForTests(null);
        }
    }

    @Test
    public void projectCatalogWinsOverDiscoveredModelKey() throws Exception {
        Path root = temp.newFolder("project-wins").toPath();
        ModelEntry project = projectEntry("cellpose_auto_microglia", "Project row");
        ModelCatalogIO.writeProject(root, new ModelCatalog(root, Arrays.asList(project)));

        ModelCatalogIO.setDiscoveryProviderForTests(new ModelCatalogIO.DiscoveryProvider() {
            @Override
            public List<ModelEntry> fetch(List<String> existingModelKeys) {
                return Arrays.asList(discovered("cellpose_auto_microglia",
                        "Discovered row"));
            }
        });
        try {
            ModelCatalog catalog = ModelCatalogIO.read(root);

            assertEquals("Project row", catalog.get("cellpose_auto_microglia").get().name);
        } finally {
            ModelCatalogIO.setDiscoveryProviderForTests(null);
        }
    }

    private static ModelEntry discovered(String key, String name) {
        Map<String, Object> metadata = new LinkedHashMap<String, Object>();
        metadata.put(ModelCatalogIO.DISCOVERED_FROM_METADATA_KEY,
                ModelCatalogIO.CELLPOSE_DISCOVERY_SOURCE);
        return new ModelEntry(key, name, null,
                ModelEntry.Engine.CELLPOSE, ModelEntry.Source.USER_IMPORTED,
                "C:/cellpose/" + key, null, key, null, null,
                defaults(), metadata, true);
    }

    private static ModelEntry projectEntry(String key, String name) {
        return new ModelEntry(key, name, null,
                ModelEntry.Engine.CELLPOSE, ModelEntry.Source.USER_IMPORTED,
                "files/" + key + "/model.pth", null, null, null, null,
                defaults(), null, true);
    }

    private static Map<String, Object> defaults() {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("diameter", Double.valueOf(30.0));
        out.put("flowThreshold", Double.valueOf(0.4));
        out.put("cellprobThreshold", Double.valueOf(0.0));
        return out;
    }
}
