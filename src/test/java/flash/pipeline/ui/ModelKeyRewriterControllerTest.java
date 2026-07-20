package flash.pipeline.ui;

import flash.pipeline.TestConfigFiles;
import flash.pipeline.bin.BinConfig;
import flash.pipeline.bin.ChannelConfigIO;
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
        Path bin = writeBin(root.resolve("A"),
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
        assertTrue(segmentation(bin).contains("old_model"));
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

    private static Path writeBin(Path projectRoot, String segmentationMethod) throws Exception {
        BinConfig cfg = TestConfigFiles.basicBinConfig("C1");
        cfg.segmentationMethods.clear();
        cfg.addSegmentationMethodToken(segmentationMethod);
        TestConfigFiles.writeChannelConfig(projectRoot, cfg);
        return TestConfigFiles.settingsDir(projectRoot).toPath().resolve(ChannelConfigIO.FILE_NAME);
    }

    private static String segmentation(Path path) {
        return ChannelConfigIO.read(path.getParent().toFile()).channels.get(0).segmentationMethod;
    }

    private static Map<String, Object> defaults() {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("probThresh", Double.valueOf(0.5));
        out.put("nmsThresh", Double.valueOf(0.3));
        return out;
    }
}
