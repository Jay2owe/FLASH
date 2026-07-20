package flash.pipeline.ui;

import flash.pipeline.segmentation.catalog.ModelEntry;
import flash.pipeline.segmentation.catalog.ModelCatalogIO;
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
import static org.junit.Assert.fail;

public class SegmentationModelImportValidationTest {
    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void acceptsValidSavedModelZip() throws Exception {
        Path root = temp.newFolder("stardist-savedmodel").toPath();
        Path source = zip(root.resolve("imports").resolve("savedmodel.zip"),
                "saved_model.pb",
                "variables/variables.index");
        SegmentationModelManagerController controller = new SegmentationModelManagerController(root);

        ModelEntry added = controller.addStarDistModel(source, "SavedModel", null, null);

        assertTrue(added.filePath.isPresent());
        assertTrue(Files.isRegularFile(ModelCatalogIO.catalogDirectory(root)
                .resolve(added.filePath.get())));
        assertFalse(Files.exists(root.resolve("Configuration")));
    }

    @Test
    public void acceptsValidCsbDeepZip() throws Exception {
        Path root = temp.newFolder("stardist-csbdeep").toPath();
        Path source = zip(root.resolve("imports").resolve("csbdeep.zip"),
                "config.json",
                "thresholds.json");
        SegmentationModelManagerController controller = new SegmentationModelManagerController(root);

        ModelEntry added = controller.addStarDistModel(source, "CSBDeep", null, null);

        assertTrue(added.filePath.isPresent());
        assertTrue(Files.isRegularFile(ModelCatalogIO.catalogDirectory(root)
                .resolve(added.filePath.get())));
    }

    @Test
    public void rejectsZipWithoutModelMarkers() throws Exception {
        Path root = temp.newFolder("stardist-no-markers").toPath();
        Path source = zip(root.resolve("imports").resolve("not-a-model.zip"),
                "readme.txt");
        SegmentationModelManagerController controller = new SegmentationModelManagerController(root);

        try {
            controller.addStarDistModel(source, "Not a model", null, null);
            fail("Expected StarDist zip without model markers to fail.");
        } catch (Exception expected) {
            assertTrue(expected.getMessage().contains(SegmentationModelImportService.INVALID_STARDIST_ZIP_MESSAGE));
        }
        assertTrue(controller.list(ModelEntry.Engine.STARDIST,
                ModelEntry.Source.USER_IMPORTED).isEmpty());
        assertFalse(Files.exists(ModelCatalogIO.catalogDirectory(root).resolve("files")));
    }

    @Test
    public void rejectsCorruptZip() throws Exception {
        Path root = temp.newFolder("stardist-corrupt").toPath();
        Path source = root.resolve("imports").resolve("corrupt.zip");
        Files.createDirectories(source.getParent());
        Files.write(source, "not a real zip".getBytes(StandardCharsets.UTF_8));
        SegmentationModelManagerController controller = new SegmentationModelManagerController(root);

        try {
            controller.addStarDistModel(source, "Corrupt", null, null);
            fail("Expected corrupt StarDist zip to fail.");
        } catch (Exception expected) {
            assertTrue(expected.getMessage().contains("could not be read"));
        }
    }

    @Test
    public void rejectsEmptyZip() throws Exception {
        Path root = temp.newFolder("stardist-empty").toPath();
        Path source = zip(root.resolve("imports").resolve("empty.zip"));
        SegmentationModelManagerController controller = new SegmentationModelManagerController(root);

        try {
            controller.addStarDistModel(source, "Empty", null, null);
            fail("Expected empty StarDist zip to fail.");
        } catch (Exception expected) {
            assertTrue(expected.getMessage().contains("empty"));
        }
    }

    @Test
    public void rejectsZipWithTraversalEntry() throws Exception {
        Path root = temp.newFolder("stardist-traversal").toPath();
        Path source = zip(root.resolve("imports").resolve("traversal.zip"),
                "../saved_model.pb");
        SegmentationModelManagerController controller = new SegmentationModelManagerController(root);

        try {
            controller.addStarDistModel(source, "Traversal", null, null);
            fail("Expected unsafe zip entry to fail.");
        } catch (Exception expected) {
            assertTrue(expected.getMessage().contains("unsafe entry path"));
        }
    }

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
    public void starDistSymlinkRejected() throws Exception {
        Path root = temp.newFolder("stardist-symlink").toPath();
        Path real = zip(root.resolve("imports").resolve("real.zip"),
                "saved_model.pb");
        Path link = root.resolve("imports").resolve("linked.zip");
        try {
            Files.createSymbolicLink(link, real);
        } catch (UnsupportedOperationException | SecurityException | java.io.IOException e) {
            return;
        }
        SegmentationModelManagerController controller = new SegmentationModelManagerController(root);

        try {
            controller.addStarDistModel(link, "Linked StarDist", null, null);
            fail("Expected symbolic-link StarDist model to fail.");
        } catch (Exception expected) {
            assertTrue(expected.getMessage().contains("symbolic link"));
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
    public void cellposeSymlinkRejected() throws Exception {
        Path root = temp.newFolder("cellpose-symlink").toPath();
        Path real = root.resolve("imports").resolve("real.model");
        Files.createDirectories(real.getParent());
        Files.write(real, "model".getBytes(StandardCharsets.UTF_8));
        Path link = root.resolve("imports").resolve("linked.model");
        try {
            Files.createSymbolicLink(link, real);
        } catch (UnsupportedOperationException | SecurityException | java.io.IOException e) {
            return;
        }
        SegmentationModelManagerController controller = new SegmentationModelManagerController(root);

        try {
            controller.addCellposeFileModel(link, "Linked Cellpose", null, null);
            fail("Expected symbolic-link Cellpose model to fail.");
        } catch (Exception expected) {
            assertTrue(expected.getMessage().contains("symbolic link"));
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

    private static Path zip(Path path, String... entries) throws Exception {
        Files.createDirectories(path.getParent());
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(path))) {
            for (String entryName : entries) {
                zip.putNextEntry(new ZipEntry(entryName));
                zip.write(entryName.getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return path;
    }
}
