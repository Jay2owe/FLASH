package flash.pipeline.cellpose;

import flash.pipeline.segmentation.SegmentationMethod;
import flash.pipeline.segmentation.SegmentationTokenParser;
import flash.pipeline.segmentation.catalog.ModelCatalog;
import flash.pipeline.segmentation.catalog.ModelCatalogIO;
import flash.pipeline.segmentation.catalog.ModelEntry;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class CellposeModelResolverTest {
    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @After
    public void tearDown() {
        CellposeRegisteredModels.resetTestHooks();
    }

    @Test
    public void resolvesStockBuiltinReturnsPretrainedName() throws Exception {
        ModelCatalog catalog = stockCatalog(temp.newFolder("stock-root").toPath());

        Optional<CellposeModelResolver.Resolved> resolved =
                new CellposeModelResolver().resolve("cellpose_cyto3", catalog);

        assertTrue(resolved.isPresent());
        assertTrue(resolved.get().built_in);
        assertEquals("cyto3", resolved.get().pretrainedName);
    }

    @Test
    public void resolvesUserImportedReturnsAbsolutePath() throws Exception {
        Path root = temp.newFolder("user-root").toPath();
        Path source = temp.newFile("iba1_cellpose_model").toPath();
        Files.write(source, "model".getBytes(StandardCharsets.UTF_8));
        ModelCatalog catalog = stockCatalog(root);
        ModelEntry saved = catalog.add(userCellpose("user_microglia_iba1_v3"), source);

        Optional<CellposeModelResolver.Resolved> resolved =
                new CellposeModelResolver().resolve(saved.modelKey, catalog);

        assertTrue(resolved.isPresent());
        assertFalse(resolved.get().built_in);
        assertEquals(catalog.resolve(saved).toAbsolutePath().normalize().toString(),
                resolved.get().absolutePath);
    }

    @Test
    public void missingUserModelFileIsRejectedAtResolution() throws Exception {
        Path root = temp.newFolder("missing-user-root").toPath();
        Path source = temp.newFile("missing-user-model").toPath();
        Files.write(source, "model".getBytes(StandardCharsets.UTF_8));
        ModelCatalog catalog = stockCatalog(root);
        ModelEntry saved = catalog.add(userCellpose("user_missing"), source);
        Files.delete(catalog.resolve(saved));

        assertFalse(new CellposeModelResolver().resolve(saved.modelKey, catalog).isPresent());
    }

    @Test
    public void validatedDiscoveredFileResolvesOnlyInItsCurrentEnvironment()
            throws Exception {
        Path root = temp.newFolder("discovered-root").toPath();
        Path source = temp.newFile("registered_microglia").toPath();
        Files.write(source, "model".getBytes(StandardCharsets.UTF_8));
        final CellposeRegisteredModels.DiscoveryEnvironment current =
                environment("environment-current");
        CellposeRegisteredModels.setEnvironmentProviderForTests(
                fixedEnvironment(current));
        List<ModelEntry> entries = CellposeRegisteredModels.entriesFromResponse(
                response(current.cacheKey, Arrays.asList(
                        model("registered_microglia", false,
                                source.toString(), true, true))),
                Collections.<String>emptyList());
        ModelCatalog catalog = new ModelCatalog(root, entries);

        Optional<CellposeModelResolver.Resolved> resolved =
                new CellposeModelResolver().resolve("registered_microglia", catalog);

        assertTrue(resolved.isPresent());
        assertEquals(source.toAbsolutePath().normalize().toString(),
                resolved.get().absolutePath);

        CellposeRegisteredModels.setEnvironmentProviderForTests(
                fixedEnvironment(environment("environment-changed")));
        assertFalse(new CellposeModelResolver().resolve(
                "registered_microglia", catalog).isPresent());
    }

    @Test
    public void deletedDiscoveredCustomFileDoesNotFallBackToRegisteredName()
            throws Exception {
        Path root = temp.newFolder("deleted-discovered-root").toPath();
        Path source = temp.newFile("deleted_registered_model").toPath();
        Files.write(source, "model".getBytes(StandardCharsets.UTF_8));
        final CellposeRegisteredModels.DiscoveryEnvironment current =
                environment("deleted-file-environment");
        CellposeRegisteredModels.setEnvironmentProviderForTests(
                fixedEnvironment(current));
        List<ModelEntry> entries = CellposeRegisteredModels.entriesFromResponse(
                response(current.cacheKey, Arrays.asList(
                        model("deleted_registered_model", false,
                                source.toString(), true, true))),
                Collections.<String>emptyList());
        ModelCatalog catalog = new ModelCatalog(root, entries);
        Files.delete(source);

        assertFalse(new CellposeModelResolver().resolve(
                "deleted_registered_model", catalog).isPresent());
    }

    @Test
    public void unvalidatedDiscoveredEntryIsRejected() throws Exception {
        Path root = temp.newFolder("unvalidated-root").toPath();
        Path source = temp.newFile("unvalidated-model").toPath();
        Map<String, Object> metadata = new LinkedHashMap<String, Object>();
        metadata.put(ModelCatalogIO.DISCOVERED_FROM_METADATA_KEY,
                ModelCatalogIO.CELLPOSE_DISCOVERY_SOURCE);
        ModelEntry unvalidated = new ModelEntry("unvalidated", "Unvalidated", null,
                ModelEntry.Engine.CELLPOSE, ModelEntry.Source.USER_IMPORTED,
                source.toAbsolutePath().toString(), null, "unvalidated", null, null,
                new LinkedHashMap<String, Object>(), metadata, false);
        ModelCatalog catalog = new ModelCatalog(root, Arrays.asList(unvalidated));

        assertFalse(new CellposeModelResolver().resolve("unvalidated", catalog).isPresent());
    }

    @Test
    public void unsupportedCellposeSamBuiltinIsRejected() throws Exception {
        Path root = temp.newFolder("unsupported-root").toPath();
        ModelEntry unsupported = new ModelEntry("cellpose_cpsam", "Cellpose SAM", null,
                ModelEntry.Engine.CELLPOSE, ModelEntry.Source.STOCK_BUILTIN,
                null, null, "cpsam", null, null,
                new LinkedHashMap<String, Object>(), null, false);
        ModelCatalog catalog = new ModelCatalog(root, Arrays.asList(unsupported));

        assertFalse(new CellposeModelResolver().resolve(
                unsupported.modelKey, catalog).isPresent());
    }

    @Test
    public void unknownKeyReturnsEmpty() throws Exception {
        ModelCatalog catalog = stockCatalog(temp.newFolder("unknown-root").toPath());

        assertFalse(new CellposeModelResolver().resolve("missing_model", catalog).isPresent());
    }

    @Test
    public void enumLookupDoesNotFallbackForUnknownToken() {
        try {
            CellposeModel.fromToken("missing_model");
            org.junit.Assert.fail("Expected unknown Cellpose model to throw");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("missing_model"));
        }
    }

    @Test
    public void resolvesLegacyTokenWithoutModelKey() throws Exception {
        SegmentationMethod method = SegmentationTokenParser.parse(
                "cellpose:30:cyto3:0.4:0.0:gpu=true:chan2=0");
        assertEquals("cellpose_cyto3", SegmentationMethod.cellposeModelKey(method));

        ModelCatalog catalog = stockCatalog(temp.newFolder("legacy-root").toPath());
        Optional<CellposeModelResolver.Resolved> resolved =
                new CellposeModelResolver().resolve(SegmentationMethod.cellposeModelKey(method), catalog);

        assertTrue(resolved.isPresent());
        assertEquals("cyto3", resolved.get().pretrainedName);
    }

    private static ModelEntry userCellpose(String key) {
        return new ModelEntry(key, "User Cellpose", null,
                ModelEntry.Engine.CELLPOSE, ModelEntry.Source.USER_IMPORTED,
                null, null, null, null, null,
                new LinkedHashMap<String, Object>(), null, false);
    }

    private static ModelCatalog stockCatalog(Path root) {
        return new ModelCatalog(root, ModelCatalogIO.readStockResources());
    }

    private static CellposeRegisteredModels.EnvironmentProvider fixedEnvironment(
            final CellposeRegisteredModels.DiscoveryEnvironment environment) {
        return new CellposeRegisteredModels.EnvironmentProvider() {
            @Override public CellposeRegisteredModels.DiscoveryEnvironment current() {
                return environment;
            }
        };
    }

    private static CellposeRegisteredModels.DiscoveryEnvironment environment(String key) {
        return new CellposeRegisteredModels.DiscoveryEnvironment(
                "python", CellposeRuntime.SUPPORTED_CELLPOSE_VERSION, key);
    }

    private static Map<String, Object> response(String environmentKey,
                                                 List<? extends Map<String, Object>> models) {
        Map<String, Object> response = new LinkedHashMap<String, Object>();
        response.put("id", "test");
        response.put("protocol", Integer.valueOf(1));
        response.put("success", Boolean.TRUE);
        response.put("cellpose_version", CellposeRuntime.SUPPORTED_CELLPOSE_VERSION);
        response.put("environment_key", environmentKey);
        response.put("models", new ArrayList<Map<String, Object>>(models));
        return response;
    }

    private static Map<String, Object> model(String name,
                                              boolean builtin,
                                              String path,
                                              boolean registered,
                                              boolean runnable) {
        Map<String, Object> model = new LinkedHashMap<String, Object>();
        model.put("name", name);
        model.put("builtin", Boolean.valueOf(builtin));
        model.put("registered", Boolean.valueOf(registered));
        model.put("runnable", Boolean.valueOf(runnable));
        if (path != null) {
            model.put("path", path);
        }
        return model;
    }
}
