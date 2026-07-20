package flash.pipeline.click.training.stardist;

import flash.pipeline.TestConfigFiles;
import flash.pipeline.bin.BinConfig;
import flash.pipeline.click.ClickStore;
import flash.pipeline.click.ClicksConfigIO;
import flash.pipeline.click.training.ImagePlusProvider;
import flash.pipeline.io.FlashProjectLayout;
import flash.pipeline.intelligence.MiniJson;
import flash.pipeline.ui.wizard.JsonIO;
import ij.ImagePlus;
import ij.ImageStack;
import ij.io.FileSaver;
import ij.io.Opener;
import ij.process.FloatProcessor;
import ij.process.ShortProcessor;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

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

        List<Object> samples = sampleManifest(output);
        assertEquals(2, samples.size());
        Set<String> groups = new HashSet<String>();
        for (Object value : samples) {
            Map<String, Object> sample = JsonIO.asObject(value);
            assertEquals("Image1", JsonIO.stringValue(sample.get("sourceImage")));
            assertEquals("session", JsonIO.stringValue(sample.get("sessionId")));
            groups.add(JsonIO.stringValue(sample.get("groupId")));
        }
        assertEquals("All Z slices from one source must retain one durable group", 1,
                groups.size());
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
    public void wideCanonicalLabelsRemainExactThroughMaskAndServiceHandoff()
            throws Exception {
        Path root = temp.newFolder("wide-labels").toPath();
        final int[] oracle = new int[] {65_535, 65_536, 70_000};
        ImagePlus sourceLabels = wideLabelImage("wide", oracle);
        Map<String, ImagePlus> raw = map("Wide",
                constantImage("raw", oracle.length, 1, 1, 100));
        Map<String, ImagePlus> labels = map("Wide", sourceLabels);
        ClickStore clicks = new ClickStore();
        for (int label : oracle) {
            clicks.add(click("Wide", 1, label, ClickStore.Verdict.POSITIVE));
        }

        StarDistDatasetPackager.PackagingResult result = new StarDistDatasetPackager()
                .packageDataset(root, "wide", 1, clicks, provider(raw), provider(labels));
        ImagePlus exported = open(result.outputDir.resolve("labels")
                .resolve("Wide_C1_z001.tif"));
        try {
            assertEquals("Labels above 65,535 require exact 32-bit TIFF storage",
                    32, exported.getBitDepth());
            for (int x = 0; x < oracle.length; x++) {
                assertEquals(oracle[x], pixel(exported, x, 0));
            }
        } finally {
            exported.changes = false;
            exported.close();
            exported.flush();
        }
        assertEquals(3, result.positiveLabelsRetained);
        assertNotNull("Packaging must not close the provider-owned source image",
                sourceLabels.getProcessor());

        StarDistLocalTrainingService.TrainingArtifacts artifacts =
                StarDistLocalTrainingService.prepareTrainingArtifacts(
                        result.outputDir, "wide-model", starDistConfig(42));
        assertTrue(artifacts.scriptText.contains("def canonical_labels"));
        assertTrue(artifacts.scriptText.contains("astype(np.int32, copy=False)"));
        assertFalse(artifacts.scriptText.contains("astype(np.uint16"));
    }

    @Test
    public void lateInvalidLabelFailsBeforeAnyDatasetDirectoryOrTiffExists()
            throws Exception {
        Path root = temp.newFolder("late-invalid-label").toPath();
        Map<String, ImagePlus> raw = new HashMap<String, ImagePlus>();
        raw.put("First", constantImage("raw-first", 2, 1, 1, 10));
        raw.put("Second", constantImage("raw-second", 2, 1, 1, 20));
        Map<String, ImagePlus> labels = new HashMap<String, ImagePlus>();
        labels.put("First", wideLabelImage("first", new int[] {65_535, 0}));
        FloatProcessor invalid = new FloatProcessor(2, 1);
        invalid.setf(0, 0, 70_000.5f);
        ImagePlus invalidImage = new ImagePlus("Second", invalid);
        invalidImage.setDimensions(1, 1, 1);
        labels.put("Second", invalidImage);
        ClickStore clicks = new ClickStore();
        clicks.add(click("First", 1, 65_535, ClickStore.Verdict.POSITIVE));
        clicks.add(click("Second", 1, 70_000, ClickStore.Verdict.POSITIVE));
        Path engineRoot = FlashProjectLayout.forDirectory(root.toString())
                .trainingDatasetsRoot().toPath().resolve("StarDist");

        try {
            new StarDistDatasetPackager().packageDataset(root, "late", 1, clicks,
                    provider(raw), provider(labels));
            fail("Expected fractional label rejection.");
        } catch (java.io.IOException expected) {
            assertTrue(expected.getMessage(),
                    expected.getMessage().contains("LABEL_IDENTITY_UNSUPPORTED"));
            assertTrue(expected.getMessage(), expected.getMessage().contains("70000.5"));
        }
        assertFalse("Preflight failure must not create the engine dataset directory",
                Files.exists(engineRoot));
    }

    @Test
    public void unrepresentableClickLabelFailsBeforeProviderOrDirectoryUse() throws Exception {
        Path root = temp.newFolder("too-wide-click").toPath();
        ClickStore clicks = new ClickStore();
        clicks.add(click("TooWide", 1, 16_777_217, ClickStore.Verdict.POSITIVE));
        final boolean[] providerCalled = new boolean[] {false};
        ImagePlusProvider provider = new ImagePlusProvider() {
            @Override
            public ImagePlus get(String imageName) {
                providerCalled[0] = true;
                return null;
            }
        };
        Path engineRoot = FlashProjectLayout.forDirectory(root.toString())
                .trainingDatasetsRoot().toPath().resolve("StarDist");

        try {
            new StarDistDatasetPackager().packageDataset(root, "too-wide", 1, clicks,
                    provider, provider);
            fail("Expected unrepresentable click label rejection.");
        } catch (java.io.IOException expected) {
            assertTrue(expected.getMessage(),
                    expected.getMessage().contains("LABEL_IDENTITY_UNSUPPORTED"));
            assertTrue(expected.getMessage(), expected.getMessage().contains("16777217"));
        }
        assertFalse(providerCalled[0]);
        assertFalse(Files.exists(engineRoot));
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

        Set<String> tileGroups = new HashSet<String>();
        for (Object value : sampleManifest(result.outputDir)) {
            Map<String, Object> sample = JsonIO.asObject(value);
            tileGroups.add(JsonIO.stringValue(sample.get("groupId")));
            assertEquals("SlideA.tif", JsonIO.stringValue(sample.get("sourceImage")));
        }
        assertEquals("Overlapping tiles from one source must never become independent groups",
                1, tileGroups.size());
    }

    @Test
    public void groupedSplitIsDeterministicLeakFreeAndHonestForOneGroup() throws Exception {
        Path root = temp.newFolder("grouped-split").toPath();
        Map<String, ImagePlus> raw = new HashMap<String, ImagePlus>();
        Map<String, ImagePlus> labels = new HashMap<String, ImagePlus>();
        ClickStore clicks = new ClickStore();
        for (int source = 1; source <= 4; source++) {
            String name = "Source" + source;
            raw.put(name, constantImage("raw-" + source, 4, 3, 2, 100 + source));
            labels.put(name, labelImage("labels-" + source, new int[][][] {
                    {{source, 0, 0, 0}, {0, 0, 0, 0}, {0, 0, 0, 0}},
                    {{source, source, 0, 0}, {0, 0, 0, 0}, {0, 0, 0, 0}}
            }));
            clicks.add(click(name, 2, source, ClickStore.Verdict.POSITIVE));
        }
        Path dataset = new StarDistDatasetPackager().packageDataset(root, "split-session",
                2, clicks, provider(raw), provider(labels)).outputDir;

        StarDistLocalTrainingService.TrainingArtifacts first =
                StarDistLocalTrainingService.prepareTrainingArtifacts(dataset, "model",
                        starDistConfig(314159));
        String firstJson = readUtf8(first.splitManifest);
        Map<String, String> firstPartitions = groupPartitions(first.splitManifest);
        assertEquals(4, firstPartitions.size());
        assertNoGroupLeakage(first.splitManifest);

        StarDistLocalTrainingService.TrainingArtifacts repeated =
                StarDistLocalTrainingService.prepareTrainingArtifacts(dataset, "model",
                        starDistConfig(314159));
        assertEquals("The exact persisted assignment must repeat for the same seed",
                firstJson, readUtf8(repeated.splitManifest));

        boolean changed = false;
        for (int seed = 314160; seed < 314260 && !changed; seed++) {
            StarDistLocalTrainingService.TrainingArtifacts candidate =
                    StarDistLocalTrainingService.prepareTrainingArtifacts(dataset, "model",
                            starDistConfig(seed));
            assertNoGroupLeakage(candidate.splitManifest);
            changed = !firstPartitions.equals(groupPartitions(candidate.splitManifest));
        }
        assertTrue("A different seed should be able to move whole groups", changed);

        Path oneRoot = temp.newFolder("one-group-split").toPath();
        Map<String, ImagePlus> oneRaw = map("OnlySource",
                constantImage("raw", 4, 3, 2, 100));
        Map<String, ImagePlus> oneLabels = map("OnlySource",
                labelImage("labels", new int[][][] {
                        {{1, 0, 0, 0}, {0, 0, 0, 0}, {0, 0, 0, 0}},
                        {{1, 1, 0, 0}, {0, 0, 0, 0}, {0, 0, 0, 0}}
                }));
        ClickStore oneClicks = new ClickStore();
        oneClicks.add(click("OnlySource", 2, 1, ClickStore.Verdict.POSITIVE));
        Path oneDataset = new StarDistDatasetPackager().packageDataset(oneRoot,
                "one-session", 2, oneClicks, provider(oneRaw), provider(oneLabels)).outputDir;
        StarDistLocalTrainingService.TrainingArtifacts one =
                StarDistLocalTrainingService.prepareTrainingArtifacts(oneDataset, "model",
                        starDistConfig(17));
        Map<String, Object> oneSplit = JsonIO.parseObject(readUtf8(one.splitManifest));
        assertFalse("One source must not validate against itself",
                JsonIO.booleanValue(oneSplit.get("validationEnabled"), true));
        assertTrue(JsonIO.stringValue(oneSplit.get("validationDisabledReason"))
                .contains("Fewer than two"));
        for (Object value : JsonIO.asList(oneSplit.get("assignments"))) {
            assertEquals("train", JsonIO.stringValue(
                    JsonIO.asObject(value).get("partition")));
        }
        assertTrue(one.scriptText.contains("if validation_enabled:"));
        assertTrue(one.scriptText.contains("FLASH_VALIDATION_DISABLED="));
    }

    @Test
    public void starDistConfigurationRejectsNonFiniteValuesAndPropertiesFallBack() {
        double[] invalid = new double[] {
                Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY
        };
        for (final double value : invalid) {
            expectIllegalArgument(new Runnable() {
                @Override public void run() {
                    new StarDistLocalTrainingService.Config(true, "python", "", "conda",
                            1, 1, 1, value, 32, 2, 0.2, 1, false);
                }
            }, "learning rate");
            expectIllegalArgument(new Runnable() {
                @Override public void run() {
                    new StarDistLocalTrainingService.Config(true, "python", "", "conda",
                            1, 1, 1, 0.001, 32, 2, value, 1, false);
                }
            }, "validation fraction");
        }
        StarDistLocalTrainingService.Config edges =
                new StarDistLocalTrainingService.Config(true, "python", "", "conda",
                        1, 1, 1, 0.0, 32, 2, 0.9, 1, false);
        assertEquals(0.0, edges.learningRate, 0.0);
        assertEquals(0.9, edges.validationFraction, 0.0);

        String learning = System.getProperty(
                StarDistLocalTrainingService.LEARNING_RATE_PROPERTY);
        String validation = System.getProperty(
                StarDistLocalTrainingService.VALIDATION_FRACTION_PROPERTY);
        try {
            System.setProperty(StarDistLocalTrainingService.LEARNING_RATE_PROPERTY, "NaN");
            System.setProperty(StarDistLocalTrainingService.VALIDATION_FRACTION_PROPERTY,
                    "-Infinity");
            StarDistLocalTrainingService.Config fallback =
                    StarDistLocalTrainingService.Config.fromSystemProperties();
            assertEquals(0.0003, fallback.learningRate, 0.0);
            assertEquals(0.2, fallback.validationFraction, 0.0);
        } finally {
            restoreProperty(StarDistLocalTrainingService.LEARNING_RATE_PROPERTY, learning);
            restoreProperty(StarDistLocalTrainingService.VALIDATION_FRACTION_PROPERTY,
                    validation);
        }
    }

    @Test
    public void generatedScriptRunsSqueezeWithPrestartSeedEnvironment() throws Exception {
        Path dataset = temp.newFolder("generated-script-runtime").toPath();
        Files.createDirectories(dataset.resolve("raw"));
        Files.createDirectories(dataset.resolve("labels"));
        saveTestTiff(dataset.resolve("raw").resolve("sample.tif"),
                constantImage("raw", 2, 1, 1, 10));
        saveTestTiff(dataset.resolve("labels").resolve("sample.tif"),
                labelImage("labels", new int[][][] {{{1, 0}}}));
        StarDistLocalTrainingService.TrainingArtifacts artifacts =
                StarDistLocalTrainingService.prepareTrainingArtifacts(dataset, "model",
                        starDistConfig(2468));
        assertEquals("2468", artifacts.environment.get("PYTHONHASHSEED"));
        assertEquals("1", artifacts.environment.get("TF_DETERMINISTIC_OPS"));

        String exercise = "import importlib.util, os, sys\n"
                + "spec=importlib.util.spec_from_file_location('flash_train', sys.argv[1])\n"
                + "module=importlib.util.module_from_spec(spec)\n"
                + "spec.loader.exec_module(module)\n"
                + "class Array:\n"
                + "    ndim=2\n"
                + "    shape=(2, 3)\n"
                + "class NP:\n"
                + "    @staticmethod\n"
                + "    def squeeze(value): return Array()\n"
                + "result=module.squeeze_2d(object(), NP)\n"
                + "print('{}|{}'.format(result.ndim, os.environ.get('PYTHONHASHSEED')))\n";
        ProcessBuilder builder = new ProcessBuilder(artifacts.command.get(0), "-c",
                exercise, artifacts.scriptFile.toString());
        builder.directory(dataset.toFile());
        builder.redirectErrorStream(true);
        builder.environment().putAll(artifacts.environment);
        Process process = builder.start();
        StringBuilder output = new StringBuilder();
        BufferedReader reader = new BufferedReader(new InputStreamReader(
                process.getInputStream(), StandardCharsets.UTF_8));
        try {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append('\n');
            }
        } finally {
            reader.close();
        }
        assertTrue("Generated-script runtime did not finish",
                process.waitFor(10, TimeUnit.SECONDS));
        assertEquals(output.toString(), 0, process.exitValue());
        assertTrue(output.toString(), output.toString().contains("2|2468"));
    }

    @Test
    public void rejectsUnboundedOrMalformedProvenanceBeforeArtifactMutation()
            throws Exception {
        byte[] oversized = new byte[(int) MiniJson.DEFAULT_MAX_UTF8_BYTES + 1];
        java.util.Arrays.fill(oversized, (byte) ' ');
        assertManifestRejectedBeforeArtifacts("oversized-manifest", oversized,
                "INPUT_UTF8_BYTES");

        byte[] invalidUtf8 = new byte[] {
                '{', '"', 's', 'a', 'm', 'p', 'l', 'e', 's', '"', ':', '[', '"',
                (byte) 0xC3, (byte) 0x28, '"', ']', '}'
        };
        assertManifestRejectedBeforeArtifacts("invalid-utf8-manifest", invalidUtf8,
                "Malformed UTF-8");

        StringBuilder deep = new StringBuilder("{\"samples\":");
        for (int i = 0; i < MiniJson.DEFAULT_MAX_NESTING_DEPTH + 2; i++) {
            deep.append('[');
        }
        deep.append('0');
        for (int i = 0; i < MiniJson.DEFAULT_MAX_NESTING_DEPTH + 2; i++) {
            deep.append(']');
        }
        deep.append('}');
        assertManifestRejectedBeforeArtifacts("deep-manifest",
                deep.toString().getBytes(StandardCharsets.UTF_8), "NESTING_DEPTH");
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

    private static ImagePlus wideLabelImage(String title, int[] values) {
        FloatProcessor processor = new FloatProcessor(values.length, 1);
        for (int x = 0; x < values.length; x++) {
            processor.setf(x, 0, values[x]);
        }
        ImagePlus image = new ImagePlus(title, processor);
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

    private static List<Object> sampleManifest(Path output) throws Exception {
        Map<String, Object> manifest = JsonIO.parseObject(readUtf8(
                output.resolve(StarDistDatasetPackager.SAMPLE_MANIFEST_FILENAME)));
        return JsonIO.asList(manifest.get("samples"));
    }

    private static String readUtf8(Path path) throws Exception {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    private static StarDistLocalTrainingService.Config starDistConfig(int seed) {
        return new StarDistLocalTrainingService.Config(true, "python", "", "conda",
                2, 1, 2, 0.0003, 32, 2, 0.25, seed, false);
    }

    private static Map<String, String> groupPartitions(Path splitManifest) throws Exception {
        Map<String, String> partitions = new HashMap<String, String>();
        Map<String, Object> split = JsonIO.parseObject(readUtf8(splitManifest));
        for (Object value : JsonIO.asList(split.get("assignments"))) {
            Map<String, Object> assignment = JsonIO.asObject(value);
            String group = JsonIO.stringValue(assignment.get("groupId"));
            String partition = JsonIO.stringValue(assignment.get("partition"));
            String previous = partitions.put(group, partition);
            if (previous != null) {
                assertEquals("One source/session group leaked across partitions",
                        previous, partition);
            }
        }
        return partitions;
    }

    private static void assertNoGroupLeakage(Path splitManifest) throws Exception {
        groupPartitions(splitManifest);
    }

    private static void expectIllegalArgument(Runnable action, String messagePart) {
        try {
            action.run();
            fail("Expected IllegalArgumentException containing " + messagePart);
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains(messagePart));
        }
    }

    private static void restoreProperty(String name, String value) {
        if (value == null) {
            System.clearProperty(name);
        } else {
            System.setProperty(name, value);
        }
    }

    private void assertManifestRejectedBeforeArtifacts(String folder,
                                                       byte[] manifestBytes,
                                                       String messagePart) throws Exception {
        Path dataset = temp.newFolder(folder).toPath();
        Files.createDirectories(dataset.resolve("raw"));
        Files.createDirectories(dataset.resolve("labels"));
        saveTestTiff(dataset.resolve("raw").resolve("sample.tif"),
                constantImage("raw", 2, 1, 1, 10));
        saveTestTiff(dataset.resolve("labels").resolve("sample.tif"),
                labelImage("labels", new int[][][] {{{1, 0}}}));
        Files.write(dataset.resolve(StarDistDatasetPackager.SAMPLE_MANIFEST_FILENAME),
                manifestBytes);

        try {
            StarDistLocalTrainingService.prepareTrainingArtifacts(dataset, "model",
                    starDistConfig(42));
            fail("Expected rejected sample manifest: " + folder);
        } catch (java.io.IOException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains(messagePart));
        }

        assertFalse(Files.exists(dataset.resolve(
                StarDistLocalTrainingService.SPLIT_MANIFEST_FILENAME)));
        assertFalse(Files.exists(dataset.resolve(
                StarDistLocalTrainingService.REPRODUCIBILITY_FILENAME)));
        assertFalse(Files.exists(dataset.resolve("train_stardist_flash.py")));
        assertFalse(Files.exists(dataset.resolve("train_stardist_command.txt")));
    }

    private static void saveTestTiff(Path path, ImagePlus image) throws Exception {
        try {
            assertTrue("Could not write test TIFF " + path,
                    new FileSaver(image).saveAsTiff(path.toString()));
        } finally {
            image.changes = false;
            image.close();
            image.flush();
        }
    }
}
