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

public class ModelManagerStatusColumnTest {
    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void statusColumnShowsOkForValidEntryAndMissingForDeletedFile() throws Exception {
        Path root = temp.newFolder("status").toPath();
        SegmentationModelManagerController controller = new SegmentationModelManagerController(root);
        ModelEntry added = controller.addStarDistModel(
                validZip(root.resolve("imports").resolve("status.zip")),
                "Status model", null, null);
        Path copied = controller.catalog().resolve(added);

        assertEquals(SegmentationModelManagerController.ModelStatus.State.OK,
                controller.status(added).state());
        assertEquals("OK", controller.status(added).label());

        Files.delete(copied);

        assertEquals(SegmentationModelManagerController.ModelStatus.State.MISSING,
                controller.status(added).state());
        assertEquals("Missing", controller.status(added).label());
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
