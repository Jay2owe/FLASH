package flash.pipeline.ui;

import flash.pipeline.segmentation.catalog.ModelEntry;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class BulkImportControllerTest {
    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void folderImportSkipsInvalidAndDuplicateFiles() throws Exception {
        Path root = temp.newFolder("bulk").toPath();
        Path imports = root.resolve("imports");
        Path nested = imports.resolve("nested");
        validZip(imports.resolve("astro.zip"));
        Files.write(imports.resolve("microglia.pth"),
                "cellpose".getBytes(StandardCharsets.UTF_8));
        Files.write(imports.resolve("broken.zip"),
                "not-a-zip".getBytes(StandardCharsets.UTF_8));
        validZip(nested.resolve("astro.zip"));

        BulkImportController controller = new BulkImportController(root);
        BulkImportController.Summary summary = controller.importFolder(imports);

        assertEquals(2, summary.importedCount);
        assertEquals(2, summary.skippedCount);
        assertEquals(Integer.valueOf(1), summary.skippedByReason.get("invalid zip"));
        assertEquals(Integer.valueOf(1), summary.skippedByReason.get("duplicate modelKey"));
        assertTrue(summary.message().contains("Imported 2 models, skipped 2"));
        assertEquals(ModelEntry.Engine.STARDIST,
                controller.catalog().get("stardist_astro").get().engine);
        assertEquals(ModelEntry.Engine.CELLPOSE,
                controller.catalog().get("cellpose_microglia").get().engine);
    }

    private static void validZip(Path path) throws Exception {
        Files.createDirectories(path.getParent());
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(path))) {
            zip.putNextEntry(new ZipEntry("saved_model.pb"));
            zip.write("model".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
    }
}
