package flash.pipeline.ui;

import flash.pipeline.bin.BinConfigIO;
import flash.pipeline.segmentation.catalog.ModelCatalog;
import flash.pipeline.segmentation.catalog.ModelCatalogIO;
import flash.pipeline.segmentation.catalog.ModelEntry;
import flash.pipeline.segmentation.catalog.ModelKeyRewriter;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ModelKeyRewriterControllerTest {
    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void cancellationSurfacesWarningAndAbortsWithoutChanges() throws Exception {
        Path root = temp.newFolder("cancel").toPath();
        createCatalogEntry(root, "old_model");
        Path bin = writeBin(root.resolve("A/Configuration/.bin/Channel_Data.txt"),
                "stardist:0.5:0.4:model=old_model");
        final String[] warning = new String[1];

        ModelKeyRewriter.RenameResult result = new ModelKeyRewriterController().rename(
                "old_model",
                "new_model",
                root,
                new ModelKeyRewriterController.Confirmation() {
                    @Override
                    public boolean confirm(String warningMessage) {
                        warning[0] = warningMessage;
                        return false;
                    }
                });

        assertTrue(result.cancelled);
        assertTrue(warning[0].contains("rewrites every bin"));
        assertTrue(ModelCatalogIO.read(root).get("old_model").isPresent());
        assertFalse(ModelCatalogIO.read(root).get("new_model").isPresent());
        assertTrue(Files.readAllLines(bin, StandardCharsets.UTF_8).get(6)
                .contains("old_model"));
    }

    private static void createCatalogEntry(Path root, String key) throws Exception {
        Path model = ModelCatalogIO.catalogDirectory(root)
                .resolve("files").resolve(key).resolve("model.zip");
        Files.createDirectories(model.getParent());
        Files.write(model, "model".getBytes(StandardCharsets.UTF_8));
        ModelEntry entry = new ModelEntry(key, key, null,
                ModelEntry.Engine.STARDIST, ModelEntry.Source.USER_IMPORTED,
                "files/" + key + "/model.zip", null, null, null, null,
                defaults(), null, false);
        ModelCatalogIO.writeProject(root, new ModelCatalog(root, Arrays.asList(entry)));
    }

    private static Path writeBin(Path path, String line7) throws Exception {
        Files.createDirectories(path.getParent());
        BinConfigIO.writeAtomic(path, Arrays.asList(
                "C1",
                "Red",
                "default",
                "100-Infinity",
                "None",
                "default",
                line7));
        return path;
    }

    private static Map<String, Object> defaults() {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("probThresh", Double.valueOf(0.5));
        out.put("nmsThresh", Double.valueOf(0.3));
        return out;
    }
}
