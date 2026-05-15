package flash.pipeline.segmentation.catalog;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class ModelCatalogTest {
    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void addCopiesFileIntoModelDirectoryAndReturnsRelativePathEntry() throws Exception {
        Path root = temp.newFolder("add").toPath();
        Path source = temp.newFile("TF_SavedModel.zip").toPath();
        Files.write(source, "zip-bytes".getBytes(StandardCharsets.UTF_8));
        ModelCatalog catalog = new ModelCatalog(root);

        ModelEntry added = catalog.add(userStarDistWithoutFile("user_microglia"), source);

        assertEquals("files/user_microglia/TF_SavedModel.zip", added.filePath.get());
        Path copied = ModelCatalogIO.catalogDirectory(root).resolve(added.filePath.get());
        assertTrue(Files.isRegularFile(copied));
        assertEquals("zip-bytes", new String(Files.readAllBytes(copied), StandardCharsets.UTF_8));
        assertEquals(added, catalog.get("user_microglia").get());
    }

    @Test
    public void removeDeletesModelDirectoryAndCatalogEntry() throws Exception {
        Path root = temp.newFolder("remove").toPath();
        Path source = temp.newFile("remove-model.zip").toPath();
        Files.write(source, "model".getBytes(StandardCharsets.UTF_8));
        ModelCatalog catalog = new ModelCatalog(root);
        catalog.add(userStarDistWithoutFile("remove_me"), source);
        Path modelDir = ModelCatalogIO.catalogDirectory(root)
                .resolve("files").resolve("remove_me");

        catalog.remove("remove_me");

        assertFalse(catalog.get("remove_me").isPresent());
        assertFalse(Files.exists(modelDir));
    }

    @Test
    public void forEngineReturnsOnlyRequestedEngineAcrossStockAndUserEntries() throws Exception {
        Path root = temp.newFolder("engine").toPath();
        Path source = temp.newFile("engine-model.zip").toPath();
        Files.write(source, "model".getBytes(StandardCharsets.UTF_8));
        ModelCatalog catalog = ModelCatalogIO.read(root);
        catalog.add(userStarDistWithoutFile("user_engine"), source);

        List<ModelEntry> starDistEntries = catalog.forEngine(ModelEntry.Engine.STARDIST);

        assertTrue(containsKey(starDistEntries, "stardist_versatile_fluo"));
        assertTrue(containsKey(starDistEntries, "user_engine"));
        for (ModelEntry entry : starDistEntries) {
            assertEquals(ModelEntry.Engine.STARDIST, entry.engine);
        }
    }

    @Test
    public void isStockEntryDistinguishesStockFromUserModels() throws Exception {
        Path root = temp.newFolder("stock").toPath();
        Path source = temp.newFile("user-stock-check.zip").toPath();
        Files.write(source, "model".getBytes(StandardCharsets.UTF_8));
        ModelCatalog catalog = ModelCatalogIO.read(root);

        catalog.add(userStarDistWithoutFile("user_stock_check"), source);

        assertTrue(catalog.isStockEntry("cellpose_cyto3"));
        assertTrue(catalog.isStockEntry("stardist_versatile_fluo"));
        assertFalse(catalog.isStockEntry("user_stock_check"));
        assertFalse(catalog.isStockEntry("missing"));
    }

    @Test
    public void resolveHandlesStockResourceStockBuiltinAndUserEntries() throws Exception {
        Path root = temp.newFolder("resolve").toPath();
        Path source = temp.newFile("resolve-model.zip").toPath();
        Files.write(source, "model".getBytes(StandardCharsets.UTF_8));
        ModelCatalog catalog = ModelCatalogIO.read(root);
        ModelEntry user = catalog.add(userStarDistWithoutFile("user_resolve"), source);

        Path stockResource = catalog.resolve(catalog.get("stardist_versatile_fluo").get());
        if (stockResource != null) {
            assertTrue(stockResource.isAbsolute());
            assertTrue(Files.isRegularFile(stockResource));
        }
        assertNull(catalog.resolve(catalog.get("cellpose_cyto3").get()));

        Path userPath = catalog.resolve(user);
        assertTrue(userPath.isAbsolute());
        assertTrue(Files.isRegularFile(userPath));
        assertEquals(ModelCatalogIO.catalogDirectory(root).resolve(user.filePath.get())
                .toAbsolutePath().normalize(), userPath);
    }

    private static ModelEntry userStarDistWithoutFile(String key) {
        return new ModelEntry(key, "User StarDist", null,
                ModelEntry.Engine.STARDIST, ModelEntry.Source.USER_IMPORTED,
                null, null, null, null, null,
                defaults(), null, false);
    }

    private static Map<String, Object> defaults() {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("probThresh", Double.valueOf(0.5));
        out.put("nmsThresh", Double.valueOf(0.3));
        return out;
    }

    private static boolean containsKey(List<ModelEntry> entries, String key) {
        for (ModelEntry entry : entries) {
            if (key.equals(entry.modelKey)) {
                return true;
            }
        }
        return false;
    }
}
