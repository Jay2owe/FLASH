package flash.pipeline.ui;

import flash.pipeline.segmentation.catalog.ModelEntry;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.assertEquals;

public class ModelManagerValidateAllTest {
    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void validateAllRefreshesStatusForEveryEntry() throws Exception {
        Path root = temp.newFolder("validate-all").toPath();
        SegmentationModelManagerController controller = new SegmentationModelManagerController(root);
        ModelEntry kept = controller.addStarDistModel(
                validZip(root.resolve("imports").resolve("kept.zip")),
                "Kept model", null, null);
        ModelEntry deleted = controller.addStarDistModel(
                validZip(root.resolve("imports").resolve("deleted.zip")),
                "Deleted model", null, null);
        Files.delete(controller.catalog().resolve(deleted));

        Map<String, SegmentationModelManagerController.ModelStatus> statuses =
                controller.validateAll();

        assertEquals(controller.catalog().all().size(), statuses.size());
        assertEquals(SegmentationModelManagerController.ModelStatus.State.OK,
                statuses.get(kept.modelKey).state());
        assertEquals(SegmentationModelManagerController.ModelStatus.State.MISSING,
                statuses.get(deleted.modelKey).state());
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
