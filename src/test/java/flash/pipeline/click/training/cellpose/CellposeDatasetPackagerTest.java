package flash.pipeline.click.training.cellpose;

import flash.pipeline.TestConfigFiles;
import flash.pipeline.bin.BinConfig;
import flash.pipeline.click.ClickStore;
import flash.pipeline.click.ClicksConfigIO;
import flash.pipeline.click.training.ImagePlusProvider;
import flash.pipeline.io.FlashProjectLayout;
import flash.pipeline.ui.wizard.JsonIO;
import ij.IJ;
import ij.ImagePlus;
import ij.Prefs;
import ij.ImageStack;
import ij.process.ShortProcessor;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class CellposeDatasetPackagerTest {
    private static final String PYTHON_PREF = "flash.pipeline.cellpose.pythonPath";

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    private String previousPythonPref;

    @Before
    public void rememberPrefs() {
        previousPythonPref = Prefs.get(PYTHON_PREF, "");
        Prefs.set(PYTHON_PREF, "");
    }

    @After
    public void restorePrefs() {
        Prefs.set(PYTHON_PREF, previousPythonPref == null ? "" : previousPythonPref);
    }

    @Test
    public void negativeClicksRemoveLabels() throws Exception {
        Path root = projectRoot();
        ClickStore store = new ClickStore();
        store.add(click("Image1", 2, 1, ClickStore.Verdict.POSITIVE));
        store.add(click("Image1", 2, 2, ClickStore.Verdict.NEGATIVE));

        CellposeDatasetPackager.PackagingResult result = new CellposeDatasetPackager().packageDataset(
                root, "negative", 2, store,
                provider("Image1", rawStack(3, 2, 1)),
                provider("Image1", labelStack(3, 2, new int[][][] {
                        { {0, 0, 1}, {1, 0, 2}, {2, 1, 2} }
                })),
                "cyto3");

        ImagePlus mask = IJ.openImage(result.outputDir.resolve("Image1_C2_z001_masks.tif").toString());
        try {
            assertEquals(1, mask.getProcessor().get(0, 0));
            assertEquals(0, mask.getProcessor().get(1, 0));
            assertEquals(0, mask.getProcessor().get(2, 1));
        } finally {
            close(mask);
        }
        assertEquals(1, result.positiveLabelsRetained);
        assertEquals(1, result.negativeLabelsRemoved);
    }

    @Test
    public void metadataJsonAndTrainCommandWritten() throws Exception {
        Path root = projectRoot();
        writeChannelConfig(root);
        Path clicksJson = modernClicksJson(root);
        writeClicksJson(clicksJson);
        ClickStore store = new ClickStore();
        store.add(click("Image1", 2, 1, ClickStore.Verdict.POSITIVE));
        store.add(click("Image1", 2, 2, ClickStore.Verdict.NEGATIVE));

        CellposeDatasetPackager.PackagingResult result = packageOneImage(root, "metadata", store);

        Path metadataPath = result.outputDir.resolve("metadata.json");
        Path commandPath = result.outputDir.resolve("train_command.txt");
        assertTrue(Files.isRegularFile(metadataPath));
        assertTrue(Files.isRegularFile(commandPath));

        String command = text(commandPath).trim();
        assertTrue(command.startsWith("python -m cellpose --train --dir \""));
        assertTrue(command.contains(result.outputDir.toAbsolutePath().normalize().toString()));
        assertTrue(command.contains("--pretrained_model cyto3"));

        Map<String, Object> metadata = JsonIO.parseObject(text(metadataPath));
        assertEquals(1, JsonIO.intValue(metadata.get("version"), -1));
        assertEquals(2, JsonIO.intValue(metadata.get("channel"), -1));
        assertEquals("Iba1", JsonIO.stringValue(metadata.get("channelName")));
        assertEquals("cyto3", JsonIO.stringValue(metadata.get("baseModel")));
        assertEquals(command, JsonIO.stringValue(metadata.get("trainCommand")));
        assertEquals(1, JsonIO.intValue(metadata.get("imageCount"), -1));
        assertEquals(1, JsonIO.intValue(metadata.get("sliceCount"), -1));
        assertEquals(CellposeDatasetPackager.EXPORT_MODE_PER_Z_SLICES,
                JsonIO.stringValue(metadata.get("exportMode")));
        assertFalse(JsonIO.booleanValue(metadata.get("sourceHad3D"), true));
        assertEquals(relativePath(result.outputDir, clicksJson),
                JsonIO.stringValue(metadata.get("sourceClicksJsonPath")));

        Map<String, Object> objectCount = JsonIO.asObject(metadata.get("objectCount"));
        assertEquals(1, JsonIO.intValue(objectCount.get("positive"), -1));
        assertEquals(1, JsonIO.intValue(objectCount.get("negative"), -1));
    }

    @Test
    public void metadataClicksPathUsesLegacyBinWhenOnlyLegacyClicksJsonExists() throws Exception {
        Path root = projectRoot();
        Path clicksJson = root.resolve(".bin").resolve(ClicksConfigIO.FILE_NAME);
        writeClicksJson(clicksJson);
        ClickStore store = new ClickStore();
        store.add(click("Image1", 2, 1, ClickStore.Verdict.POSITIVE));

        CellposeDatasetPackager.PackagingResult result =
                packageOneImage(root, "legacy-clicks", store);

        Map<String, Object> metadata = JsonIO.parseObject(
                text(result.outputDir.resolve("metadata.json")));
        assertEquals(relativePath(result.outputDir, clicksJson),
                JsonIO.stringValue(metadata.get("sourceClicksJsonPath")));
    }

    @Test
    public void trainCommandUsesProjectPythonPath() throws Exception {
        Prefs.set(PYTHON_PREF, "C:\\Cellpose Env\\python.exe");
        Path root = projectRoot();
        ClickStore store = new ClickStore();
        store.add(click("Image1", 2, 1, ClickStore.Verdict.POSITIVE));

        CellposeDatasetPackager.PackagingResult result = packageOneImage(root, "python-pref", store);

        String command = text(result.trainCommandFile).trim();
        assertTrue(command.startsWith("\"C:\\Cellpose Env\\python.exe\" -m cellpose --train"));
    }

    @Test
    public void perZWritesEachSliceAsAPair() throws Exception {
        Path root = projectRoot();
        ClickStore store = new ClickStore();
        store.add(click("Image1", 2, 1, ClickStore.Verdict.POSITIVE));

        CellposeDatasetPackager.PackagingResult result = new CellposeDatasetPackager().packageDataset(
                root, "per-z", 2, store,
                provider("Image1", rawStack(2, 2, 3)),
                provider("Image1", labelStack(2, 2, new int[][][] {
                        { {0, 0, 1} },
                        { {0, 0, 1} },
                        { {0, 0, 1} }
                })),
                "cyto3");

        assertEquals(1, result.imagesWritten);
        assertEquals(3, result.slicesWritten);
        assertEquals(6L, tiffCount(result.outputDir));
        assertTrue(Files.isRegularFile(result.outputDir.resolve("Image1_C2_z001.tif")));
        assertTrue(Files.isRegularFile(result.outputDir.resolve("Image1_C2_z001_masks.tif")));
        assertTrue(Files.isRegularFile(result.outputDir.resolve("Image1_C2_z003.tif")));
        assertTrue(Files.isRegularFile(result.outputDir.resolve("Image1_C2_z003_masks.tif")));
        assertTrue(result.sourceHad3D);
        assertTrue(result.trainingWarning.contains("2D-oriented"));

        Map<String, Object> metadata = JsonIO.parseObject(
                text(result.outputDir.resolve("metadata.json")));
        assertEquals(CellposeDatasetPackager.EXPORT_MODE_PER_Z_SLICES,
                JsonIO.stringValue(metadata.get("exportMode")));
        assertTrue(JsonIO.booleanValue(metadata.get("sourceHad3D"), false));
        assertEquals(1, JsonIO.intValue(metadata.get("source3DImageCount"), -1));
        assertTrue(JsonIO.stringValue(metadata.get("trainingWarning")).contains("per-Z"));
    }

    @Test
    public void rawHyperstackExportHonorsSelectedChannel() throws Exception {
        Path root = projectRoot();
        ImagePlus raw = twoChannelRawHyperstack(3, 2, 17, 211);
        ImagePlus labels = labelStack(3, 2, new int[][][] {
                { {0, 0, 1} }
        });

        ClickStore channelTwoStore = new ClickStore();
        channelTwoStore.add(click("Image1", 2, 1, ClickStore.Verdict.POSITIVE));
        CellposeDatasetPackager.PackagingResult channelTwoResult =
                new CellposeDatasetPackager().packageDataset(
                        root, "channel-two", 2, channelTwoStore,
                        provider("Image1", raw),
                        provider("Image1", labels),
                        "cyto3");
        assertExportedRawIntensity(channelTwoResult.outputDir.resolve("Image1_C2_z001.tif"), 211);

        ClickStore channelOneStore = new ClickStore();
        channelOneStore.add(click("Image1", 1, 1, ClickStore.Verdict.POSITIVE));
        CellposeDatasetPackager.PackagingResult channelOneResult =
                new CellposeDatasetPackager().packageDataset(
                        root, "channel-one", 1, channelOneStore,
                        provider("Image1", raw),
                        provider("Image1", labels),
                        "cyto3");
        assertExportedRawIntensity(channelOneResult.outputDir.resolve("Image1_C1_z001.tif"), 17);
    }

    @Test
    public void outputDirIsAtomic() throws Exception {
        Path root = projectRoot();
        Path existing = root.resolve("Configuration")
                .resolve("Training Datasets")
                .resolve("Cellpose")
                .resolve("atomic");
        Files.createDirectories(existing);
        Path sentinel = existing.resolve("sentinel.txt");
        Files.write(sentinel, Arrays.asList("keep"), StandardCharsets.UTF_8);

        ClickStore store = new ClickStore();
        store.add(click("Image1", 2, 1, ClickStore.Verdict.POSITIVE));

        try {
            new CellposeDatasetPackager().packageDataset(
                    root, "atomic", 2, store,
                    provider("Image1", rawStack(2, 2, 1)),
                    new ImagePlusProvider() {
                        @Override
                        public ImagePlus get(String imageName) {
                            return null;
                        }
                    },
                    "cyto3");
            fail("Expected packaging failure.");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("Label image provider returned null"));
        }

        assertTrue(Files.isRegularFile(sentinel));
        assertEquals("keep", text(sentinel).trim());
        assertFalse(hasTemporaryDatasetDir(existing.getParent(), "atomic.tmp-"));
    }

    private CellposeDatasetPackager.PackagingResult packageOneImage(Path root,
                                                                   String session,
                                                                   ClickStore store) throws IOException {
        return new CellposeDatasetPackager().packageDataset(
                root, session, 2, store,
                provider("Image1", rawStack(3, 2, 1)),
                provider("Image1", labelStack(3, 2, new int[][][] {
                        { {0, 0, 1}, {1, 0, 2} }
                })),
                "cellpose_cyto3");
    }

    private static ClickStore.Click click(String image,
                                          int channel,
                                          int label,
                                          ClickStore.Verdict verdict) {
        return new ClickStore.Click(image, channel, label, 1,
                0.0, 0.0, verdict, System.currentTimeMillis());
    }

    private static ImagePlusProvider provider(final String imageName, final ImagePlus image) {
        return new ImagePlusProvider() {
            @Override
            public ImagePlus get(String requested) {
                return imageName.equals(requested) ? image : null;
            }
        };
    }

    private Path projectRoot() throws IOException {
        return temp.newFolder("project").toPath();
    }

    private static void writeChannelConfig(Path root) throws IOException {
        BinConfig cfg = TestConfigFiles.basicBinConfig("DAPI", "Iba1");
        cfg.segmentationMethods.clear();
        cfg.addSegmentationMethodToken("classical");
        cfg.addSegmentationMethodToken("cellpose:30:0.4:0.0:gpu=false:model=cellpose_cyto3");
        cfg.clickConfigPresent = true;
        TestConfigFiles.writeChannelConfig(root, cfg);
    }

    private static Path modernClicksJson(Path root) {
        return FlashProjectLayout.forDirectory(root.toString())
                .configurationWriteDir()
                .toPath()
                .resolve(ClicksConfigIO.FILE_NAME);
    }

    private static void writeClicksJson(Path clicksJson) throws IOException {
        Files.createDirectories(clicksJson.getParent());
        Files.write(clicksJson, Arrays.asList("{}"), StandardCharsets.UTF_8);
    }

    private static String relativePath(Path from, Path to) {
        return from.toAbsolutePath().normalize()
                .relativize(to.toAbsolutePath().normalize())
                .toString()
                .replace('\\', '/');
    }

    private static ImagePlus rawStack(int width, int height, int slices) {
        ImageStack stack = new ImageStack(width, height);
        for (int s = 1; s <= slices; s++) {
            ShortProcessor processor = new ShortProcessor(width, height);
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    processor.set(x, y, s * 100 + y * width + x);
                }
            }
            stack.addSlice(processor);
        }
        ImagePlus image = new ImagePlus("raw", stack);
        image.setDimensions(1, slices, 1);
        return image;
    }

    private static ImagePlus twoChannelRawHyperstack(int width,
                                                     int height,
                                                     int channelOneValue,
                                                     int channelTwoValue) {
        ImageStack stack = new ImageStack(width, height);
        stack.addSlice(constantProcessor(width, height, channelOneValue));
        stack.addSlice(constantProcessor(width, height, channelTwoValue));
        ImagePlus image = new ImagePlus("raw", stack);
        image.setDimensions(2, 1, 1);
        image.setOpenAsHyperStack(true);
        return image;
    }

    private static ShortProcessor constantProcessor(int width, int height, int value) {
        ShortProcessor processor = new ShortProcessor(width, height);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                processor.set(x, y, value);
            }
        }
        return processor;
    }

    private static ImagePlus labelStack(int width, int height, int[][][] labeledPixelsPerSlice) {
        ImageStack stack = new ImageStack(width, height);
        for (int[][] slicePixels : labeledPixelsPerSlice) {
            ShortProcessor processor = new ShortProcessor(width, height);
            for (int[] pixel : slicePixels) {
                processor.set(pixel[0], pixel[1], pixel[2]);
            }
            stack.addSlice(processor);
        }
        ImagePlus image = new ImagePlus("labels", stack);
        image.setDimensions(1, labeledPixelsPerSlice.length, 1);
        return image;
    }

    private static String text(Path path) throws IOException {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    private static long tiffCount(Path dir) throws IOException {
        try (Stream<Path> stream = Files.list(dir)) {
            return stream.filter(path -> path.getFileName().toString().endsWith(".tif")).count();
        }
    }

    private static void assertExportedRawIntensity(Path path, int expected) {
        ImagePlus exported = IJ.openImage(path.toString());
        try {
            assertTrue("Expected exported TIFF to load: " + path, exported != null);
            assertEquals(expected, exported.getProcessor().get(0, 0));
            assertEquals(expected, exported.getProcessor().get(2, 1));
        } finally {
            close(exported);
        }
    }

    private static boolean hasTemporaryDatasetDir(Path parent, String prefix) throws IOException {
        try (DirectoryStream<Path> dirs = Files.newDirectoryStream(parent)) {
            for (Path dir : dirs) {
                if (dir.getFileName().toString().startsWith(prefix)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void close(ImagePlus image) {
        if (image != null) {
            image.changes = false;
            image.close();
            image.flush();
        }
    }
}
