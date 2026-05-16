package flash.pipeline.ui;

import flash.pipeline.segmentation.catalog.ModelCatalog;
import flash.pipeline.segmentation.catalog.ModelCatalogIO;
import flash.pipeline.segmentation.catalog.ModelEntry;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class CatalogExportControllerTest {
    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void exportContainsCatalogJsonAndFilesThenImportMerges() throws Exception {
        Path sourceRoot = temp.newFolder("source").toPath();
        Path sourceModel = validZip(sourceRoot.resolve("imports").resolve("model.zip"));
        SegmentationModelManagerController source =
                new SegmentationModelManagerController(sourceRoot);
        ModelEntry added = source.addStarDistModel(sourceModel,
                "Shared model", "For export", null);
        Path zip = temp.newFolder("export").toPath().resolve("catalog.zip");

        new CatalogExportController(sourceRoot).exportCatalog(zip);

        assertZipContains(zip, "catalog.json");
        assertZipContains(zip, "files/" + added.modelKey + "/model.zip");

        Path destRoot = temp.newFolder("dest").toPath();
        CatalogExportController.ImportSummary summary =
                new CatalogExportController(destRoot).importCatalog(zip,
                        CatalogExportController.ConflictPolicy.KEEP_PROJECT);

        assertEquals(1, summary.importedCount);
        ModelEntry imported = ModelCatalogIO.read(destRoot).get(added.modelKey).get();
        assertEquals("Shared model", imported.name);
        assertTrue(Files.isRegularFile(ModelCatalogIO.catalogDirectory(destRoot)
                .resolve(imported.filePath.get())));
    }

    @Test
    public void importKeepsProjectEntryOnConflict() throws Exception {
        Path sourceRoot = temp.newFolder("conflict-source").toPath();
        Path sourceModel = validZip(sourceRoot.resolve("imports").resolve("model.zip"));
        SegmentationModelManagerController source =
                new SegmentationModelManagerController(sourceRoot);
        ModelEntry added = source.addStarDistModel(sourceModel,
                "Shared model", "For export", null);
        Path zip = temp.newFolder("conflict-export").toPath().resolve("catalog.zip");
        new CatalogExportController(sourceRoot).exportCatalog(zip);

        Path destRoot = temp.newFolder("conflict-dest").toPath();
        ModelEntry project = new ModelEntry(added.modelKey, "Project wins", null,
                ModelEntry.Engine.STARDIST, ModelEntry.Source.USER_IMPORTED,
                "files/" + added.modelKey + "/local.zip", null, null, null, null,
                defaults(), null, false);
        ModelCatalogIO.writeProject(destRoot, new ModelCatalog(destRoot, Arrays.asList(project)));

        CatalogExportController.ImportSummary summary =
                new CatalogExportController(destRoot).importCatalog(zip,
                        CatalogExportController.ConflictPolicy.KEEP_PROJECT);

        assertEquals(0, summary.importedCount);
        assertEquals(1, summary.conflictCount);
        assertEquals("Project wins", ModelCatalogIO.read(destRoot)
                .get(added.modelKey).get().name);
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

    private static void assertZipContains(Path zipPath, String expected) throws Exception {
        try (ZipFile zip = new ZipFile(zipPath.toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                if (expected.equals(entries.nextElement().getName())) {
                    return;
                }
            }
        }
        throw new AssertionError("Zip missing entry: " + expected);
    }

    private static Map<String, Object> defaults() {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("probThresh", Double.valueOf(0.5));
        out.put("nmsThresh", Double.valueOf(0.3));
        return out;
    }
}
