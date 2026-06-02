package flash.pipeline.click.training.stardist;

import flash.pipeline.TestConfigFiles;
import flash.pipeline.bin.BinConfig;
import flash.pipeline.click.ClickStore;
import flash.pipeline.click.ClicksConfigIO;
import flash.pipeline.click.training.ImagePlusProvider;
import flash.pipeline.io.FlashProjectLayout;
import flash.pipeline.ui.wizard.JsonIO;
import ij.ImagePlus;
import ij.ImageStack;
import ij.io.Opener;
import ij.process.ShortProcessor;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.*;

public class StarDistDatasetPackagerTest {
    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void negativeClicksRemoveLabelsFromExportedMask() throws Exception {
        Path root = temp.newFolder("negative").toPath();
        Map<String, ImagePlus> raw = map("Image1", constantImage("raw", 4, 3, 1, 100));
        Map<String, ImagePlus> labels = map("Image1", labelImage("labels", new int[][][] {
                {{1, 1, 0, 2}, {1, 0, 2, 2}, {0, 0, 0, 0}}
        }));
        ClickStore clicks = new ClickStore();
        clicks.add(click("Image1", 2, 2, ClickStore.Verdict.NEGATIVE));

        StarDistDatasetPackager.PackagingResult result = new StarDistDatasetPackager()
                .packageDataset(root, "session", 2, clicks,
                        provider(raw), provider(labels));

        assertEquals(root.resolve("FLASH").resolve("Config").resolve("Training Datasets")
                        .resolve("StarDist").resolve("session").toAbsolutePath().normalize(),
                result.outputDir.toAbsolutePath().normalize());
        assertFalse(Files.exists(root.resolve("Configuration")));
        assertEquals(1, result.imagesWritten);
        assertEquals(1, result.negativeLabelsRemoved);
        ImagePlus exported = open(result.outputDir.resolve("labels")
                .resolve("Image1_C2_z001.tif"));
        assertEquals(1, pixel(exported, 0, 0));
        assertEquals(0, pixel(exported, 2, 1));
        assertEquals(0, pixel(exported, 3, 1));
    }

    @Test
    public void positiveClicksDoNotForceInclusionWhenAlreadyPresent() throws Exception {
        Path root = temp.newFolder("positive").toPath();
        Map<String, ImagePlus> raw = map("Image1", constantImage("raw", 4, 3, 1, 100));
        Map<String, ImagePlus> labels = map("Image1", labelImage("labels", new int[][][] {
                {{1, 1, 0, 2}, {1, 0, 2, 2}, {0, 0, 0, 0}}
        }));
        ClickStore clicks = new ClickStore();
        clicks.add(click("Image1", 2, 2, ClickStore.Verdict.POSITIVE));

        StarDistDatasetPackager.PackagingResult result = new StarDistDatasetPackager()
                .packageDataset(root, "session", 2, clicks,
                        provider(raw), provider(labels));

        assertEquals(1, result.positiveLabelsRetained);
        ImagePlus exported = open(result.outputDir.resolve("labels")
                .resolve("Image1_C2_z001.tif"));
        assertEquals(2, pixel(exported, 2, 1));
        assertEquals(2, pixel(exported, 3, 1));
    }

    @Test
    public void metadataJsonHasExpectedFields() throws Exception {
        Path root = temp.newFolder("metadata").toPath();
        writeChannelConfig(root, "DAPI", "Iba1");
        Path clicksJson = modernClicksJson(root);
        writeClicksJson(clicksJson);
        Map<String, ImagePlus> raw = map("Image1", constantImage("raw", 4, 3, 2, 100));
        Map<String, ImagePlus> labels = map("Image1", labelImage("labels", new int[][][] {
                {{1, 1, 0, 0}, {0, 0, 0, 0}, {0, 0, 0, 0}},
                {{2, 2, 0, 0}, {2, 0, 0, 0}, {0, 0, 0, 0}}
        }));
        ClickStore clicks = new ClickStore();
        clicks.add(click("Image1", 2, 1, ClickStore.Verdict.POSITIVE));
        clicks.add(click("Image1", 2, 2, ClickStore.Verdict.NEGATIVE));

        Path output = new StarDistDatasetPackager()
                .packageDataset(root, "session", 2, clicks,
                        provider(raw), provider(labels))
                .outputDir;

        Map<String, Object> json = JsonIO.parseObject(new String(
                Files.readAllBytes(output.resolve("metadata.json")), StandardCharsets.UTF_8));
        assertEquals(1, JsonIO.intValue(json.get("version"), -1));
        assertEquals(2, JsonIO.intValue(json.get("channel"), -1));
        assertEquals("Iba1", JsonIO.stringValue(json.get("channelName")));
        assertTrue(((Number) json.get("createdAt")).longValue() > 0L);
        assertEquals(1, JsonIO.intValue(json.get("imageCount"), -1));
        assertEquals(2, JsonIO.intValue(json.get("sliceCount"), -1));
        Map<String, Object> objectCount = JsonIO.asObject(json.get("objectCount"));
        assertEquals(1, JsonIO.intValue(objectCount.get("positive"), -1));
        assertEquals(1, JsonIO.intValue(objectCount.get("negative"), -1));
        assertEquals(relativePath(output, clicksJson),
                JsonIO.stringValue(json.get("sourceClicksJsonPath")));
        assertEquals(StarDistDatasetPackager.RECOMMENDED_NOTEBOOK,
                JsonIO.stringValue(json.get("recommendedNotebook")));
        assertEquals("whole", JsonIO.stringValue(json.get("tileMode")));
        assertFalse(json.containsKey("tileSize"));
        assertFalse(json.containsKey("tileCount"));
    }

    @Test
    public void imagesWithoutClicksAreSkipped() throws Exception {
        Path root = temp.newFolder("skipped").toPath();
        Map<String, ImagePlus> raw = new HashMap<String, ImagePlus>();
        raw.put("Image1", constantImage("raw1", 4, 3, 1, 100));
        raw.put("Image2", constantImage("raw2", 4, 3, 1, 200));
        Map<String, ImagePlus> labels = new HashMap<String, ImagePlus>();
        labels.put("Image1", labelImage("labels1", new int[][][] {
                {{1, 1, 0, 0}, {0, 0, 0, 0}, {0, 0, 0, 0}}
        }));
        labels.put("Image2", labelImage("labels2", new int[][][] {
                {{2, 2, 0, 0}, {0, 0, 0, 0}, {0, 0, 0, 0}}
        }));
        ClickStore clicks = new ClickStore();
        clicks.add(click("Image1", 2, 1, ClickStore.Verdict.POSITIVE));
        clicks.add(click("Image2", 1, 2, ClickStore.Verdict.NEGATIVE));

        StarDistDatasetPackager.PackagingResult result = new StarDistDatasetPackager()
                .packageDataset(root, "session", 2, clicks,
                        provider(raw), provider(labels));

        assertEquals(1, result.imagesWritten);
        assertTrue(Files.isRegularFile(result.outputDir.resolve("raw")
                .resolve("Image1_C2_z001.tif")));
        assertFalse(Files.exists(result.outputDir.resolve("raw")
                .resolve("Image2_C2_z001.tif")));
    }

    @Test
    public void outputDirIsAtomic() throws Exception {
        Path root = temp.newFolder("atomic").toPath();
        Path existing = FlashProjectLayout.forDirectory(root.toString()).trainingDatasetsRoot().toPath()
                .resolve("StarDist")
                .resolve("session");
        Files.createDirectories(existing);
        Files.write(existing.resolve("sentinel.txt"),
                "keep".getBytes(StandardCharsets.UTF_8));

        Map<String, ImagePlus> raw = map("Image1", constantImage("raw", 4, 3, 1, 100));
        ClickStore clicks = new ClickStore();
        clicks.add(click("Image1", 2, 1, ClickStore.Verdict.POSITIVE));

        try {
            new StarDistDatasetPackager().packageDataset(root, "session", 2, clicks,
                    provider(raw),
                    new ImagePlusProvider() {
                        @Override
                        public ImagePlus get(String imageName) {
                            return null;
                        }
                    });
            fail("Expected missing label image to fail.");
        } catch (Exception expected) {
            assertTrue(expected.getMessage().contains("StarDist label"));
        }

        assertEquals("keep", new String(Files.readAllBytes(existing.resolve("sentinel.txt")),
                StandardCharsets.UTF_8));
        assertFalse(Files.exists(existing.resolve("metadata.json")));
    }

    @Test
    public void tileModeProducesNonOverlappingTiles() throws Exception {
        Path root = temp.newFolder("tiles").toPath();
        String imageName = "SlideA.tif";
        Map<String, ImagePlus> raw = map(imageName,
                constantImage("raw", 256, 256, 1, 100));
        Map<String, ImagePlus> labels = map(imageName,
                labelImageWithPoints("labels", 256, 256, new int[][] {
                        {1, 80, 80},
                        {2, 160, 80},
                        {3, 80, 160}
                }));
        ClickStore clicks = new ClickStore();
        clicks.add(clickAt(imageName, 2, 1, 1, 80.0, 80.0,
                ClickStore.Verdict.POSITIVE));
        clicks.add(clickAt(imageName, 2, 2, 1, 160.0, 80.0,
                ClickStore.Verdict.POSITIVE));
        clicks.add(clickAt(imageName, 2, 3, 1, 80.0, 160.0,
                ClickStore.Verdict.POSITIVE));

        StarDistDatasetPackager.PackagingResult result = new StarDistDatasetPackager()
                .packageDataset(root, "session", 2, clicks,
                        provider(raw), provider(labels), 64);

        assertEquals(3, result.imagesWritten);
        assertEquals(3, result.positiveLabelsRetained);
        assertEquals(3, result.tileCount);
        assertEquals(3, countTifs(result.outputDir.resolve("raw")));
        assertEquals(3, countTifs(result.outputDir.resolve("labels")));
        assertCenteredTile(result.outputDir, "SlideA_C2_z001_tile001.tif", 1);
        assertCenteredTile(result.outputDir, "SlideA_C2_z001_tile002.tif", 2);
        assertCenteredTile(result.outputDir, "SlideA_C2_z001_tile003.tif", 3);

        Map<String, Object> json = JsonIO.parseObject(new String(
                Files.readAllBytes(result.outputDir.resolve("metadata.json")),
                StandardCharsets.UTF_8));
        assertEquals("tiled", JsonIO.stringValue(json.get("tileMode")));
        assertEquals(64, JsonIO.intValue(json.get("tileSize"), -1));
        assertEquals(3, JsonIO.intValue(json.get("tileCount"), -1));
        String readme = new String(Files.readAllBytes(result.outputDir.resolve("README.txt")),
                StandardCharsets.UTF_8);
        assertTrue(readme.contains("pre-tiled"));
    }

    @Test
    public void tileNearEdgeIsShiftedNotPadded() throws Exception {
        Path root = temp.newFolder("edge-tile").toPath();
        String imageName = "Edge";
        Map<String, ImagePlus> raw = map(imageName,
                constantImage("raw", 256, 256, 1, 500));
        Map<String, ImagePlus> labels = map(imageName,
                labelImageWithPoints("labels", 256, 256, new int[][] {
                        {1, 5, 5}
                }));
        ClickStore clicks = new ClickStore();
        clicks.add(clickAt(imageName, 2, 1, 1, 5.0, 5.0,
                ClickStore.Verdict.POSITIVE));

        StarDistDatasetPackager.PackagingResult result = new StarDistDatasetPackager()
                .packageDataset(root, "session", 2, clicks,
                        provider(raw), provider(labels), 64);

        assertEquals(1, result.tileCount);
        ImagePlus labelTile = open(result.outputDir.resolve("labels")
                .resolve("Edge_C2_z001_tile001.tif"));
        assertEquals(64, labelTile.getWidth());
        assertEquals(64, labelTile.getHeight());
        assertEquals(1, pixel(labelTile, 5, 5));
        assertEquals(0, pixel(labelTile, 32, 32));

        ImagePlus rawTile = open(result.outputDir.resolve("raw")
                .resolve("Edge_C2_z001_tile001.tif"));
        assertEquals(500, pixel(rawTile, 0, 0));
        assertEquals(500, pixel(rawTile, 63, 63));
    }

    @Test
    public void wholeImageModeUnchanged() throws Exception {
        Path root = temp.newFolder("whole-explicit").toPath();
        Map<String, ImagePlus> raw = map("Image1",
                constantImage("raw", 4, 3, 1, 100));
        Map<String, ImagePlus> labels = map("Image1", labelImage("labels", new int[][][] {
                {{1, 1, 0, 0}, {1, 0, 0, 0}, {0, 0, 0, 0}}
        }));
        ClickStore clicks = new ClickStore();
        clicks.add(click("Image1", 2, 1, ClickStore.Verdict.POSITIVE));

        StarDistDatasetPackager.PackagingResult result = new StarDistDatasetPackager()
                .packageDataset(root, "session", 2, clicks,
                        provider(raw), provider(labels), 0);

        assertEquals(1, result.imagesWritten);
        assertEquals(-1, result.tileCount);
        assertTrue(Files.isRegularFile(result.outputDir.resolve("raw")
                .resolve("Image1_C2_z001.tif")));
        assertFalse(Files.exists(result.outputDir.resolve("raw")
                .resolve("Image1_C2_z001_tile001.tif")));
        ImagePlus exported = open(result.outputDir.resolve("labels")
                .resolve("Image1_C2_z001.tif"));
        assertEquals(4, exported.getWidth());
        assertEquals(3, exported.getHeight());
        assertEquals(1, pixel(exported, 0, 0));

        Map<String, Object> json = JsonIO.parseObject(new String(
                Files.readAllBytes(result.outputDir.resolve("metadata.json")),
                StandardCharsets.UTF_8));
        assertEquals("whole", JsonIO.stringValue(json.get("tileMode")));
        assertFalse(json.containsKey("tileSize"));
        assertFalse(json.containsKey("tileCount"));
    }

    static ClickStore.Click click(String image,
                                  int channel,
                                  int label,
                                  ClickStore.Verdict verdict) {
        return clickAt(image, channel, label, 1, 1.0, 1.0, verdict);
    }

    static ClickStore.Click clickAt(String image,
                                    int channel,
                                    int label,
                                    int z,
                                    double x,
                                    double y,
                                    ClickStore.Verdict verdict) {
        return new ClickStore.Click(image, channel, label, z, x, y, verdict, 123L);
    }

    static ImagePlus constantImage(String title, int width, int height, int slices, int value) {
        ImageStack stack = new ImageStack(width, height);
        for (int z = 0; z < slices; z++) {
            ShortProcessor sp = new ShortProcessor(width, height);
            for (int i = 0; i < sp.getPixelCount(); i++) {
                sp.set(i, value + z);
            }
            stack.addSlice(sp);
        }
        ImagePlus image = new ImagePlus(title, stack);
        image.setDimensions(1, slices, 1);
        return image;
    }

    static ImagePlus labelImage(String title, int[][][] values) {
        int slices = values.length;
        int height = values[0].length;
        int width = values[0][0].length;
        ImageStack stack = new ImageStack(width, height);
        for (int z = 0; z < slices; z++) {
            ShortProcessor sp = new ShortProcessor(width, height);
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    sp.set(x, y, values[z][y][x]);
                }
            }
            stack.addSlice(sp);
        }
        ImagePlus image = new ImagePlus(title, stack);
        image.setDimensions(1, slices, 1);
        return image;
    }

    static ImagePlus labelImageWithPoints(String title,
                                          int width,
                                          int height,
                                          int[][] labelXY) {
        ImageStack stack = new ImageStack(width, height);
        ShortProcessor sp = new ShortProcessor(width, height);
        for (int i = 0; i < labelXY.length; i++) {
            int[] point = labelXY[i];
            sp.set(point[1], point[2], point[0]);
        }
        stack.addSlice(sp);
        ImagePlus image = new ImagePlus(title, stack);
        image.setDimensions(1, 1, 1);
        return image;
    }

    static ImagePlus open(Path path) {
        ImagePlus image = new Opener().openImage(path.toString());
        assertNotNull("Could not open " + path, image);
        return image;
    }

    static int pixel(ImagePlus image, int x, int y) {
        return (int) Math.round(image.getProcessor().getPixelValue(x, y));
    }

    static ImagePlusProvider provider(final Map<String, ImagePlus> images) {
        return new ImagePlusProvider() {
            @Override
            public ImagePlus get(String imageName) {
                return images.get(imageName);
            }
        };
    }

    static Map<String, ImagePlus> map(String name, ImagePlus image) {
        Map<String, ImagePlus> out = new HashMap<String, ImagePlus>();
        out.put(name, image);
        return out;
    }

    private static void assertCenteredTile(Path outputDir, String fileName, int label) {
        ImagePlus tile = open(outputDir.resolve("labels").resolve(fileName));
        assertEquals(64, tile.getWidth());
        assertEquals(64, tile.getHeight());
        assertEquals(label, pixel(tile, 32, 32));
    }

    private static int countTifs(Path dir) throws Exception {
        int count = 0;
        DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.tif");
        try {
            for (Path ignored : stream) {
                count++;
            }
        } finally {
            stream.close();
        }
        return count;
    }

    private static void writeChannelConfig(Path root, String... channelNames) throws Exception {
        BinConfig cfg = TestConfigFiles.basicBinConfig(channelNames);
        TestConfigFiles.writeChannelConfig(root, cfg);
    }

    private static Path modernClicksJson(Path root) {
        return FlashProjectLayout.forDirectory(root.toString())
                .configurationWriteDir()
                .toPath()
                .resolve(ClicksConfigIO.FILE_NAME);
    }

    private static void writeClicksJson(Path clicksJson) throws Exception {
        Files.createDirectories(clicksJson.getParent());
        Files.write(clicksJson, "{}\n".getBytes(StandardCharsets.UTF_8));
    }

    private static String relativePath(Path from, Path to) {
        return from.toAbsolutePath().normalize()
                .relativize(to.toAbsolutePath().normalize())
                .toString()
                .replace('\\', '/');
    }
}
