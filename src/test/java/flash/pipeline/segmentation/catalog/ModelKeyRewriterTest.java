package flash.pipeline.segmentation.catalog;

import flash.pipeline.bin.BinConfigIO;
import flash.pipeline.io.FlashProjectLayout;
import flash.pipeline.segmentation.SegmentationMethod;
import flash.pipeline.segmentation.SegmentationTokenCodec;
import flash.pipeline.segmentation.SegmentationTokenParser;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class ModelKeyRewriterTest {
    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void renamesReferencesAcrossTouchedBinsOnly() throws Exception {
        Path root = temp.newFolder("rename").toPath();
        createCatalogEntry(root, "old_model");
        Path bin1 = writeBin(root.resolve("A/Configuration/.bin/Channel_Data.txt"),
                "stardist:0.5:0.4:model=old_model\tclassical");
        Path bin2 = writeBin(root.resolve("B/Configuration/.bin/Channel_Data.txt"),
                "cellpose:30:0.4:0.0:model=old_model");
        Path bin3 = writeBin(root.resolve("C/Configuration/.bin/Channel_Data.txt"),
                "stardist:0.5:0.4:model=other_model");

        ModelKeyRewriter.RenameResult result =
                ModelKeyRewriter.rename("old_model", "new_model", root);

        assertEquals(2, result.binsTouched);
        assertEquals(2, result.channelsTouched);
        assertTrue(result.filesRenamed);
        assertLine7Contains(bin1, "model=new_model");
        assertLine7Contains(bin2, "model=new_model");
        assertLine7Contains(bin3, "model=other_model");
        assertFalse(line7(bin1).contains("old_model"));
        assertFalse(ModelCatalogIO.read(root).get("old_model").isPresent());
        assertTrue(ModelCatalogIO.read(root).get("new_model").isPresent());
        assertTrue(Files.isRegularFile(ModelCatalogIO.catalogDirectory(root)
                .resolve("files/new_model/model.zip")));
    }

    @Test
    public void trainedRfModelKeyAndNestedBaseAreRewritten() throws Exception {
        Path root = temp.newFolder("trained-rf").toPath();
        createCatalogEntry(root, "rf_old");
        String base = SegmentationTokenCodec.percentEncodeToken(
                "stardist:0.5:0.4:model=rf_old");
        Path bin = writeBin(root.resolve("A/Configuration/.bin/Channel_Data.txt"),
                "trained_rf:rf_old:base=" + base);

        ModelKeyRewriter.rename("rf_old", "rf_new", root);

        SegmentationMethod method = SegmentationTokenParser.parse(line7(bin));
        assertEquals("rf_new", SegmentationMethod.trainedRfModelKey(method));
        assertEquals("rf_new", SegmentationMethod.starDistModelKey(
                SegmentationMethod.trainedRfBase(method)));
    }

    @Test
    public void collisionOnNewKeyIsRejected() throws Exception {
        Path root = temp.newFolder("collision").toPath();
        createCatalogEntry(root, "old_model");
        createCatalogEntry(root, "new_model");

        try {
            ModelKeyRewriter.rename("old_model", "new_model", root);
            fail("Expected collision to be rejected.");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("already exists"));
        }
    }

    @Test
    public void writeFailureRollsBackPreviouslyRewrittenBins() throws Exception {
        Path root = temp.newFolder("rollback").toPath();
        createCatalogEntry(root, "old_model");
        Path bin1 = writeBin(root.resolve("A/Configuration/.bin/Channel_Data.txt"),
                "stardist:0.5:0.4:model=old_model");
        Path bin2 = writeBin(root.resolve("B/Configuration/.bin/Channel_Data.txt"),
                "cellpose:30:0.4:0.0:model=old_model");

        ModelKeyRewriter rewriter = new ModelKeyRewriter(new ModelKeyRewriter.WriteHook() {
            int writes;

            @Override
            public void beforeWrite(Path path) throws IOException {
                writes++;
                if (writes == 2) {
                    throw new IOException("Injected mid-write failure.");
                }
            }
        });

        try {
            rewriter.renameProject("old_model", "new_model", root);
            fail("Expected injected write failure.");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("Injected"));
        }

        assertLine7Contains(bin1, "old_model");
        assertLine7Contains(bin2, "old_model");
        assertFalse(line7(bin1).contains("new_model"));
        assertFalse(line7(bin2).contains("new_model"));
        assertTrue(ModelCatalogIO.read(root).get("old_model").isPresent());
        assertFalse(ModelCatalogIO.read(root).get("new_model").isPresent());
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

        List<ModelEntry> entries = ModelCatalogIO.read(root).all();
        java.util.ArrayList<ModelEntry> updated = new java.util.ArrayList<ModelEntry>(entries);
        updated.add(entry);
        ModelCatalogIO.writeProject(root, new ModelCatalog(root, updated));
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

    private static String line7(Path path) throws Exception {
        return Files.readAllLines(path, StandardCharsets.UTF_8).get(6);
    }

    private static void assertLine7Contains(Path path, String text) throws Exception {
        assertTrue(line7(path).contains(text));
    }

    private static Map<String, Object> defaults() {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("probThresh", Double.valueOf(0.5));
        out.put("nmsThresh", Double.valueOf(0.3));
        return out;
    }
}
