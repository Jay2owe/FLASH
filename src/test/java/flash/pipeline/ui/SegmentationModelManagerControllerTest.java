package flash.pipeline.ui;

import flash.pipeline.segmentation.catalog.ModelCatalogIO;
import flash.pipeline.segmentation.catalog.ModelEntry;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class SegmentationModelManagerControllerTest {
    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void stockEntriesCannotBeDeletedOrEdited() throws Exception {
        Path root = temp.newFolder("stock").toPath();
        SegmentationModelManagerController controller = new SegmentationModelManagerController(root);
        ModelEntry stock = controller.get("cellpose_cyto3").get();

        assertFalse(controller.canEdit(stock));
        assertFalse(controller.canDelete(stock));

        try {
            controller.edit(stock.modelKey, "Renamed", stock.description, stock.defaults);
            fail("Expected stock edit to be rejected.");
        } catch (Exception expected) {
            assertTrue(expected.getMessage().contains("read-only"));
        }

        try {
            controller.delete(stock.modelKey);
            fail("Expected stock delete to be rejected.");
        } catch (Exception expected) {
            assertTrue(expected.getMessage().contains("read-only"));
        }
    }

    @Test
    public void addStarDistFileCopiesIntoCatalogDirAndReturnsRelativePath() throws Exception {
        Path root = temp.newFolder("stardist").toPath();
        Path source = validZip(root.resolve("imports").resolve("TF_SavedModel.zip"));
        SegmentationModelManagerController controller = new SegmentationModelManagerController(root);

        ModelEntry added = controller.addStarDistModel(source, "Iba1 microglia", "Custom model", null);

        assertEquals(ModelEntry.Engine.STARDIST, added.engine);
        assertEquals(ModelEntry.Source.USER_IMPORTED, added.source);
        assertTrue(added.filePath.isPresent());
        assertFalse(added.filePath.get().contains(":"));
        assertTrue(added.filePath.get().startsWith("files/" + added.modelKey + "/"));
        Path expectedCatalogDir = root.resolve("FLASH").resolve("Config").resolve("Segmentation models");
        assertEquals(expectedCatalogDir.toAbsolutePath().normalize(),
                ModelCatalogIO.catalogDirectory(root).toAbsolutePath().normalize());
        assertTrue(Files.isRegularFile(expectedCatalogDir.resolve(added.filePath.get())));
        assertFalse(Files.exists(root.resolve("Configuration")));
    }

    @Test
    public void addCellposeRegisteredNamePersistsAsStockBuiltinSource() throws Exception {
        Path root = temp.newFolder("registered").toPath();
        SegmentationModelManagerController controller = new SegmentationModelManagerController(root);

        ModelEntry added = controller.addCellposeRegisteredName("microglia_cp",
                "Microglia Cellpose", "Registered model", null);
        ModelEntry loaded = ModelCatalogIO.read(root).get(added.modelKey).get();

        assertEquals(ModelEntry.Engine.CELLPOSE, loaded.engine);
        assertEquals(ModelEntry.Source.STOCK_BUILTIN, loaded.source);
        assertEquals("microglia_cp", loaded.pretrainedModel.get());
        assertTrue(ModelCatalogIO.isProjectRegisteredBuiltin(loaded));
    }

    @Test
    public void deleteUserEntryRemovesRowAndKeepsFileByDefault() throws Exception {
        Path root = temp.newFolder("delete").toPath();
        Path source = validZip(root.resolve("imports").resolve("delete.zip"));
        SegmentationModelManagerController controller = new SegmentationModelManagerController(root);
        ModelEntry added = controller.addStarDistModel(source, "Delete me", null, null);
        Path copied = controller.catalog().resolve(added);

        assertTrue(Files.isRegularFile(copied));

        controller.delete(added.modelKey);

        assertTrue(Files.isRegularFile(copied));
        assertFalse(controller.get(added.modelKey).isPresent());
        assertFalse(ModelCatalogIO.read(root).get(added.modelKey).isPresent());
    }

    @Test
    public void renamingNameDoesNotChangeModelKey() throws Exception {
        Path root = temp.newFolder("rename").toPath();
        Path source = validZip(root.resolve("imports").resolve("rename.zip"));
        SegmentationModelManagerController controller = new SegmentationModelManagerController(root);
        ModelEntry added = controller.addStarDistModel(source, "Original name", null, null);

        ModelEntry edited = controller.edit(added.modelKey, "Better name", "Updated", added.defaults);

        assertEquals(added.modelKey, edited.modelKey);
        assertEquals("Better name", edited.name);
        assertEquals("Better name", ModelCatalogIO.read(root).get(added.modelKey).get().name);
    }

    @Test
    public void duplicateModelKeyGetsNumericSuffix() throws Exception {
        Path root = temp.newFolder("duplicate").toPath();
        Path first = validZip(root.resolve("imports").resolve("one.zip"));
        Path second = validZip(root.resolve("imports").resolve("two.zip"));
        SegmentationModelManagerController controller = new SegmentationModelManagerController(root);

        ModelEntry one = controller.addStarDistModel(first, "Iba1 model", null, null);
        ModelEntry two = controller.addStarDistModel(second, "Iba1 model", null, null);

        assertEquals("stardist_iba1_model", one.modelKey);
        assertEquals("stardist_iba1_model_2", two.modelKey);
    }

    @Test
    public void absolutePathOutsideProjectRootIsRejected() throws Exception {
        Path root = temp.newFolder("project").toPath();
        Path outside = validZip(temp.newFolder("outside").toPath().resolve("outside.zip"));
        SegmentationModelManagerController controller = new SegmentationModelManagerController(root);

        try {
            controller.addStarDistModel(outside, "Outside", null, null);
            fail("Expected outside path to be rejected.");
        } catch (Exception expected) {
            assertTrue(expected.getMessage().contains("inside the project folder"));
        }
    }

    private static Path validZip(Path path) throws Exception {
        Files.createDirectories(path.getParent());
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(path))) {
            zip.putNextEntry(new ZipEntry("saved_model.pb"));
            zip.write("model".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return path;
    }
}
