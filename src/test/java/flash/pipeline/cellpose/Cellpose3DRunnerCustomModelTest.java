package flash.pipeline.cellpose;

import flash.pipeline.segmentation.catalog.ModelCatalog;
import flash.pipeline.segmentation.catalog.ModelCatalogIO;
import flash.pipeline.segmentation.catalog.ModelEntry;
import ij.ImagePlus;
import ij.process.ByteProcessor;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class Cellpose3DRunnerCustomModelTest {
    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void commandUsesCustomFilePath() throws Exception {
        Path root = temp.newFolder("custom-root").toPath();
        Path source = temp.newFile("custom-cellpose-model").toPath();
        Files.write(source, "model".getBytes(StandardCharsets.UTF_8));
        ModelCatalog catalog = ModelCatalogIO.read(root);
        ModelEntry saved = catalog.add(userCellpose("user_microglia_iba1_v3"), source);

        List<String> command = commandFor(saved.modelKey, catalog);

        assertEquals(catalog.resolve(saved).toAbsolutePath().normalize().toString(),
                command.get(command.indexOf("--pretrained_model") + 1));
    }

    @Test
    public void commandUsesBuiltinName() throws Exception {
        ModelCatalog catalog = ModelCatalogIO.read(temp.newFolder("builtin-root").toPath());

        List<String> command = commandFor("cellpose_cyto3", catalog);

        assertEquals("cyto3", command.get(command.indexOf("--pretrained_model") + 1));
    }

    @Test
    public void missingModelBlocksRunWithClearError() throws Exception {
        ModelCatalog catalog = ModelCatalogIO.read(temp.newFolder("missing-root").toPath());

        try {
            commandFor("missing_model", catalog);
            fail("Expected missing model to block command creation.");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("missing_model"));
            assertTrue(expected.getMessage().contains("not found in catalog"));
            assertTrue(expected.getMessage().contains("select a different model"));
        }
    }

    private List<String> commandFor(String modelKey, ModelCatalog catalog) throws Exception {
        Path dir = temp.newFolder("cmd").toPath();
        return Cellpose3DRunner.buildCellposeCommand(
                "C:\\python.exe",
                dir.resolve("cellpose_input.tif"),
                dir,
                modelKey,
                catalog,
                new ImagePlus("input", new ByteProcessor(2, 2)),
                30.0,
                0.4,
                0.0,
                false);
    }

    private static ModelEntry userCellpose(String key) {
        return new ModelEntry(key, "User Cellpose", null,
                ModelEntry.Engine.CELLPOSE, ModelEntry.Source.USER_IMPORTED,
                null, null, null, null, null,
                new LinkedHashMap<String, Object>(), null, false);
    }
}
