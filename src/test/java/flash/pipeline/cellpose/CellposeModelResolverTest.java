package flash.pipeline.cellpose;

import flash.pipeline.segmentation.SegmentationMethod;
import flash.pipeline.segmentation.SegmentationTokenParser;
import flash.pipeline.segmentation.catalog.ModelCatalog;
import flash.pipeline.segmentation.catalog.ModelCatalogIO;
import flash.pipeline.segmentation.catalog.ModelEntry;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Optional;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class CellposeModelResolverTest {
    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void resolvesStockBuiltinReturnsPretrainedName() throws Exception {
        ModelCatalog catalog = ModelCatalogIO.read(temp.newFolder("stock-root").toPath());

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
        ModelCatalog catalog = ModelCatalogIO.read(root);
        ModelEntry saved = catalog.add(userCellpose("user_microglia_iba1_v3"), source);

        Optional<CellposeModelResolver.Resolved> resolved =
                new CellposeModelResolver().resolve(saved.modelKey, catalog);

        assertTrue(resolved.isPresent());
        assertFalse(resolved.get().built_in);
        assertEquals(catalog.resolve(saved).toAbsolutePath().normalize().toString(),
                resolved.get().absolutePath);
    }

    @Test
    public void unknownKeyReturnsEmpty() throws Exception {
        ModelCatalog catalog = ModelCatalogIO.read(temp.newFolder("unknown-root").toPath());

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

        ModelCatalog catalog = ModelCatalogIO.read(temp.newFolder("legacy-root").toPath());
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
}
