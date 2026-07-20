package flash.pipeline.ui;

import flash.pipeline.segmentation.catalog.ModelCatalogIO;
import flash.pipeline.segmentation.catalog.ModelEntry;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ModelManagerConservativeDeleteTest {
    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void defaultDeleteLeavesCopiedFileAndRemovesCatalogEntry() throws Exception {
        Path root = temp.newFolder("default-delete").toPath();
        SegmentationModelManagerController controller = new SegmentationModelManagerController(root);
        ModelEntry added = controller.addStarDistModel(
                validZip(root.resolve("imports").resolve("default-delete.zip")),
                "Default delete", null, null);
        Path copied = controller.catalog().resolve(added);

        controller.delete(added.modelKey);

        assertTrue("Default delete should keep copied model file.",
                Files.isRegularFile(copied));
        assertFalse(controller.get(added.modelKey).isPresent());
        assertFalse(ModelCatalogIO.read(root).get(added.modelKey).isPresent());
        assertFalse("Saved catalog must not reference removed entry.",
                catalogJson(root).contains(added.modelKey));
    }

    @Test
    public void optInDeleteRemovesCopiedFileAndCatalogEntry() throws Exception {
        Path root = temp.newFolder("full-delete").toPath();
        SegmentationModelManagerController controller = new SegmentationModelManagerController(root);
        ModelEntry added = controller.addStarDistModel(
                validZip(root.resolve("imports").resolve("full-delete.zip")),
                "Full delete", null, null);
        Path copied = controller.catalog().resolve(added);

        controller.delete(added.modelKey, true);

        assertFalse("Opt-in delete should remove copied model file.",
                Files.exists(copied));
        assertFalse(controller.get(added.modelKey).isPresent());
        assertFalse(ModelCatalogIO.read(root).get(added.modelKey).isPresent());
        assertFalse("Saved catalog must not reference removed entry.",
                catalogJson(root).contains(added.modelKey));
    }

    private static String catalogJson(Path root) throws Exception {
        Path file = ModelCatalogIO.catalogDirectory(root)
                .resolve(ModelCatalogIO.CATALOG_FILENAME);
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
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
