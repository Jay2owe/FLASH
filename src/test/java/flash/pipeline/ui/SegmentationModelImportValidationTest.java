package flash.pipeline.ui;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class SegmentationModelImportValidationTest {
    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void starDistNonZipRejected() throws Exception {
        Path root = temp.newFolder("stardist-nonzip").toPath();
        Path source = root.resolve("imports").resolve("model.txt");
        Files.createDirectories(source.getParent());
        Files.write(source, "not a zip".getBytes(StandardCharsets.UTF_8));
        SegmentationModelManagerController controller = new SegmentationModelManagerController(root);

        try {
            controller.addStarDistModel(source, "Bad StarDist", null, null);
            fail("Expected non-zip StarDist import to fail.");
        } catch (Exception expected) {
            assertTrue(expected.getMessage().contains(".zip"));
        }
    }

    @Test
    public void cellposeMissingFileRejected() throws Exception {
        Path root = temp.newFolder("cellpose-missing").toPath();
        SegmentationModelManagerController controller = new SegmentationModelManagerController(root);

        try {
            controller.addCellposeFileModel(root.resolve("missing.model"), "Missing", null, null);
            fail("Expected missing Cellpose file to fail.");
        } catch (Exception expected) {
            assertTrue(expected.getMessage().contains("does not exist"));
        }
    }

    @Test
    public void cellposeRegisteredNameWithSpacesRejected() throws Exception {
        Path root = temp.newFolder("cellpose-registered").toPath();
        SegmentationModelManagerController controller = new SegmentationModelManagerController(root);

        try {
            controller.addCellposeRegisteredName("bad model", "Bad", null, null);
            fail("Expected registered model name with spaces to fail.");
        } catch (Exception expected) {
            assertTrue(expected.getMessage().contains("spaces"));
        }
    }
}
