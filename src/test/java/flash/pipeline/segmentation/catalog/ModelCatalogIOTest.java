package flash.pipeline.segmentation.catalog;

import flash.pipeline.ui.wizard.JsonIO;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class ModelCatalogIOTest {
    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void projectCatalogUsesFlashConfigSegmentationModelsDir() throws Exception {
        Path root = temp.newFolder("layout").toPath();

        assertEquals(new File(root.toFile(), "FLASH/Config/Segmentation models").toPath(),
                ModelCatalogIO.catalogDirectory(root));

        ModelCatalogIO.writeProject(root,
                new ModelCatalog(root, Arrays.asList(userStarDist("user_layout"))));

        assertTrue(Files.isRegularFile(ModelCatalogIO.catalogFile(root)));
        assertFalse("new catalog writes must not create top-level Configuration",
                Files.exists(root.resolve("Configuration")));
    }

    @Test
    public void readMigratesLegacyTopLevelCatalogToFlashConfig() throws Exception {
        Path root = temp.newFolder("legacy").toPath();
        ModelEntry legacy = userStarDist("legacy_model");
        Path legacyCatalogDir = ModelCatalogIO.legacyCatalogDirectory(root);
        Path legacyModelFile = legacyCatalogDir.resolve(legacy.filePath.get());
        Files.createDirectories(legacyModelFile.getParent());
        Files.write(legacyModelFile, "legacy-model".getBytes(StandardCharsets.UTF_8));

        Map<String, Object> json = JsonIO.object();
        json.put("version", Integer.valueOf(ModelCatalogIO.CATALOG_VERSION));
        json.put("models", Collections.<Object>singletonList(legacy.toJsonObject()));
        Files.write(legacyCatalogDir.resolve(ModelCatalogIO.CATALOG_FILENAME),
                Collections.singletonList(JsonIO.write(json)), StandardCharsets.UTF_8);

        ModelCatalog loaded = ModelCatalogIO.read(root);
        ModelEntry loadedEntry = loaded.get("legacy_model").get();

        Path migratedModelFile = ModelCatalogIO.catalogDirectory(root).resolve(legacy.filePath.get());
        assertTrue(Files.isRegularFile(migratedModelFile));
        assertEquals(migratedModelFile.toAbsolutePath().normalize(),
                loaded.resolve(loadedEntry).toAbsolutePath().normalize());
    }

    @Test
    public void readFallsBackToLegacyCatalogWhenMigrationCannotWrite() throws Exception {
        Path root = temp.newFolder("legacy-blocked").toPath();
        Files.write(root.resolve("FLASH"), "not-a-directory".getBytes(StandardCharsets.UTF_8));
        ModelEntry legacy = userStarDist("legacy_unmigrated");
        Path legacyCatalogDir = ModelCatalogIO.legacyCatalogDirectory(root);
        Path legacyModelFile = legacyCatalogDir.resolve(legacy.filePath.get());
        Files.createDirectories(legacyModelFile.getParent());
        Files.write(legacyModelFile, "legacy-model".getBytes(StandardCharsets.UTF_8));

        Map<String, Object> json = JsonIO.object();
        json.put("version", Integer.valueOf(ModelCatalogIO.CATALOG_VERSION));
        json.put("models", Collections.<Object>singletonList(legacy.toJsonObject()));
        Files.write(legacyCatalogDir.resolve(ModelCatalogIO.CATALOG_FILENAME),
                Collections.singletonList(JsonIO.write(json)), StandardCharsets.UTF_8);

        ModelCatalog loaded = ModelCatalogIO.read(root);
        ModelEntry loadedEntry = loaded.get("legacy_unmigrated").get();

        assertEquals(legacyCatalogDir.toAbsolutePath().normalize(),
                loaded.catalogDirectory().toAbsolutePath().normalize());
        assertEquals(legacyModelFile.toAbsolutePath().normalize(),
                loaded.resolve(loadedEntry).toAbsolutePath().normalize());
        assertFalse(Files.exists(root.resolve("FLASH").resolve("Config")));
    }

    @Test
    public void roundTripProjectCatalogWithStarDistAndSmileRfEntries() throws Exception {
        Path root = temp.newFolder("roundtrip").toPath();
        ModelEntry starDist = userStarDist("user_microglia_iba1_v3");
        ModelEntry smileRf = userSmileRf("trained_rf_microglia_v1");
        ModelCatalog catalog = new ModelCatalog(root, Arrays.asList(starDist, smileRf));

        ModelCatalogIO.writeProject(root, catalog);
        ModelCatalog loaded = ModelCatalogIO.read(root);

        assertEquals(starDist, loaded.get(starDist.modelKey).get());
        ModelEntry loadedSmile = loaded.get(smileRf.modelKey).get();
        assertEquals(smileRf.modelKey, loadedSmile.modelKey);
        assertEquals(smileRf.name, loadedSmile.name);
        assertEquals(smileRf.engine, loadedSmile.engine);
        assertEquals(smileRf.source, loadedSmile.source);
        assertEquals(smileRf.filePath, loadedSmile.filePath);
        assertEquals(smileRf.base, loadedSmile.base);
        assertEquals("smile-2.6.0", loadedSmile.metadata.get("engineVersion"));
        assertEquals(28, ((Number) loadedSmile.metadata.get("positiveExamples")).intValue());
        assertEquals(41, ((Number) loadedSmile.metadata.get("negativeExamples")).intValue());
        assertTrue(loaded.get("cellpose_cyto3").isPresent());
        assertTrue(loaded.get("stardist_versatile_fluo").isPresent());
    }

    @Test
    public void missingProjectCatalogReturnsStockOnlyCatalog() throws Exception {
        Path root = temp.newFolder("missing").toPath();

        ModelCatalog loaded = ModelCatalogIO.read(root);

        assertTrue(loaded.get("cellpose_cyto3").isPresent());
        assertTrue(loaded.get("stardist_versatile_fluo").isPresent());
        for (ModelEntry entry : loaded.all()) {
            assertTrue("Expected stock entry: " + entry.modelKey, entry.isStock());
        }
    }

    @Test
    public void writeProjectValidatesBeforeWriteAndCleansSuccessfulTempFile() throws Exception {
        Path root = temp.newFolder("atomic").toPath();
        ModelEntry original = userStarDist("user_valid");
        ModelCatalogIO.writeProject(root, new ModelCatalog(root, Arrays.asList(original)));
        Path catalogFile = ModelCatalogIO.catalogFile(root);
        String before = new String(Files.readAllBytes(catalogFile), StandardCharsets.UTF_8);

        assertFalse(new File(ModelCatalogIO.catalogDirectory(root).toFile(),
                ModelCatalogIO.CATALOG_FILENAME + ".tmp").exists());

        ModelEntry invalid = new ModelEntry("", "Broken", null,
                ModelEntry.Engine.STARDIST, ModelEntry.Source.USER_IMPORTED,
                "files/broken/TF_SavedModel.zip", null, null, null, null,
                defaults("probThresh", Double.valueOf(0.5), "nmsThresh", Double.valueOf(0.3)),
                null, false);
        try {
            ModelCatalogIO.writeProject(root, new ModelCatalog(root, Arrays.asList(invalid)));
            fail("Expected invalid model entry to fail before write.");
        } catch (Exception expected) {
            // expected
        }

        String after = new String(Files.readAllBytes(catalogFile), StandardCharsets.UTF_8);
        assertEquals(before, after);
        assertFalse(new File(ModelCatalogIO.catalogDirectory(root).toFile(),
                ModelCatalogIO.CATALOG_FILENAME + ".tmp").exists());
    }

    @Test
    public void stockAndProjectMergeLogsConflictAndProjectWins() throws Exception {
        Path root = temp.newFolder("merge").toPath();
        final List<String> warnings = new ArrayList<String>();
        ModelEntry override = new ModelEntry("cellpose_cyto3", "User cyto3 override",
                "Project override", ModelEntry.Engine.CELLPOSE,
                ModelEntry.Source.USER_IMPORTED,
                "files/cellpose_cyto3/custom_model", null, null, null, null,
                defaults("diameter", Double.valueOf(22.0), "flowThreshold", Double.valueOf(0.3)),
                null, true);
        ModelCatalogIO.writeProject(root, new ModelCatalog(root, Arrays.asList(override)));

        ModelCatalogIO.setWarningSinkForTests(new ModelCatalogIO.WarningSink() {
            @Override
            public void warn(String message) {
                warnings.add(message);
            }
        });
        try {
            ModelCatalog loaded = ModelCatalogIO.read(root);

            assertEquals("User cyto3 override", loaded.get("cellpose_cyto3").get().name);
            assertFalse(loaded.isStockEntry("cellpose_cyto3"));
            assertTrue(contains(warnings, "overrides a stock model"));
        } finally {
            ModelCatalogIO.setWarningSinkForTests(null);
        }
    }

    @Test
    public void projectCatalogJsonShapeUsesVersionAndModelsArray() throws Exception {
        Path root = temp.newFolder("shape").toPath();
        ModelCatalogIO.writeProject(root,
                new ModelCatalog(root, Arrays.asList(userStarDist("user_shape"))));

        Map<String, Object> rootJson = JsonIO.parseObject(new String(
                Files.readAllBytes(ModelCatalogIO.catalogFile(root)), StandardCharsets.UTF_8));

        assertEquals(1, JsonIO.intValue(rootJson.get("version"), -1));
        assertEquals(1, JsonIO.asList(rootJson.get("models")).size());
    }

    private static ModelEntry userStarDist(String key) {
        return new ModelEntry(key, "Iba1 microglia custom",
                "Trained from project images.", ModelEntry.Engine.STARDIST,
                ModelEntry.Source.USER_IMPORTED,
                "files/" + key + "/TF_SavedModel.zip", null, null, null, null,
                defaults("probThresh", Double.valueOf(0.5), "nmsThresh", Double.valueOf(0.3)),
                metadata("qualityFlag", "USER_PROVIDED"), false);
    }

    private static ModelEntry userSmileRf(String key) {
        Map<String, Object> metadata = metadata("engineVersion", "smile-2.6.0");
        metadata.put("positiveExamples", Long.valueOf(28));
        metadata.put("negativeExamples", Long.valueOf(41));
        metadata.put("crossValAccuracy", Double.valueOf(0.83));
        return new ModelEntry(key, "Microglia RF",
                "Smile random forest post-filter.", ModelEntry.Engine.SMILE_RF,
                ModelEntry.Source.USER_TRAINED,
                "files/" + key + "/model.smile", null, null, null, "classical",
                null, metadata, false);
    }

    private static Map<String, Object> defaults(String k1, Object v1, String k2, Object v2) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put(k1, v1);
        out.put(k2, v2);
        return out;
    }

    private static Map<String, Object> metadata(String key, Object value) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put(key, value);
        return out;
    }

    private static boolean contains(List<String> warnings, String fragment) {
        for (String warning : warnings) {
            if (warning != null && warning.contains(fragment)) {
                return true;
            }
        }
        return false;
    }
}
