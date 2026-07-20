package flash.pipeline.analyses.wizard;

import flash.pipeline.analyses.CreateBinFileAnalysis;
import flash.pipeline.bin.BinConfig;
import flash.pipeline.bin.ChannelConfig;
import flash.pipeline.bin.ChannelConfigIO;
import flash.pipeline.io.SeriesMeta;
import flash.pipeline.zslice.ZSliceMode;
import flash.pipeline.zslice.ZSliceOps;
import flash.pipeline.zslice.ZSliceRange;
import flash.pipeline.zslice.ZSliceSelection;
import ij.ImagePlus;
import ij.ImageStack;
import ij.io.FileSaver;
import ij.process.ByteProcessor;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class BinPresetIOTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void roundTripSaveLoadAndDelete() throws Exception {
        File root = temp.newFolder("bin-preset");
        BinPresetIO io = new BinPresetIO(root);
        BinPreset preset = preset("My Bin Preset", "12");

        io.save(preset);

        assertTrue(new File(root,
                "FLASH/.settings/Presets/Channel Configuration/my_bin_preset.json").isFile());
        BinPreset loaded = io.load("my_bin_preset");
        assertEquals("My Bin Preset", loaded.getName());
        assertEquals("12", loaded.getPayload().channelThresholds.get(0));
        assertEquals("microglia_iba1", loaded.getMarkerIds().get(0));

        io.delete("My Bin Preset");
        assertFalse(new File(io.presetDirectory(), "my_bin_preset.json").exists());
    }

    @Test
    public void stockPresetsBootstrapWhenDirectoryIsEmpty() throws Exception {
        File root = temp.newFolder("stock");
        BinPresetIO io = new BinPresetIO(root);

        List<BinPreset> presets = io.listAll();

        assertEquals(4, presets.size());
        assertTrue(new File(io.presetDirectory(), "dapi_iba1_gfap.json").isFile());
        assertTrue(new File(io.presetDirectory(), "synaptic_puncta.json").isFile());
    }

    @Test
    public void atomicWriteLeavesOriginalOnFailedMove() throws Exception {
        File root = temp.newFolder("atomic");
        CrashyBinPresetIO io = new CrashyBinPresetIO(root);
        io.save(preset("Crash Test", "10"));

        io.crashOnMove = true;
        try {
            io.save(preset("Crash Test", "20"));
        } catch (IOException expected) {
            // expected
        }

        assertEquals("10", io.load("Crash Test").getPayload().channelThresholds.get(0));
        File[] leftovers = io.presetDirectory().listFiles((dir, name) -> name.endsWith(".tmp"));
        assertTrue(leftovers == null || leftovers.length == 0);
        String persisted = new String(Files.readAllBytes(new File(io.presetDirectory(), "crash_test.json").toPath()),
                StandardCharsets.UTF_8);
        assertTrue(persisted.contains("\"objectThreshold\":\"10\""));
    }

    @Test
    public void importChannelConfigFilePreservesWizardFields() throws Exception {
        File projectRoot = temp.newFolder("source-project");
        File settingsDir = new File(projectRoot, "FLASH/Config/.settings");
        assertTrue(settingsDir.mkdirs());
        ChannelConfig source = channelConfig();
        ChannelConfigIO.write(settingsDir, source);

        CreateBinFileAnalysis.BinUserConfig loadedFromFile = CreateBinFileAnalysis.importBinUserConfigFromSettingsDir(
                new File(settingsDir, ChannelConfigIO.FILE_NAME));
        CreateBinFileAnalysis.BinUserConfig loadedFromProject =
                CreateBinFileAnalysis.importBinUserConfigFromSettingsDir(projectRoot);

        assertNotNull(loadedFromFile);
        assertNotNull(loadedFromProject);
        assertImportedConfigMatchesSource(loadedFromFile);
        assertImportedConfigMatchesSource(loadedFromProject);
    }

    @Test
    public void saveAsPresetRoundTripReproducesReviewConfig() throws Exception {
        File root = temp.newFolder("review-preset");
        CreateBinFileAnalysis.BinUserConfig reviewConfig = wizardConfig();
        BinPresetIO io = new BinPresetIO(root);

        io.save(CreateBinFileAnalysis.binPresetFromUserConfig(
                "Review Panel", "Saved from Set Up Configuration review.", reviewConfig));

        List<BinPreset> presets = io.listAll();
        assertEquals(5, presets.size());
        boolean foundUserPreset = false;
        for (BinPreset preset : presets) {
            if ("Review Panel".equals(preset.getName())) {
                foundUserPreset = true;
            }
        }
        assertTrue(foundUserPreset);
        assertTrue(new File(io.presetDirectory(), "dapi_iba1_gfap.json").isFile());
        assertTrue(new File(io.presetDirectory(), "dapi_only.json").isFile());

        BinPreset loadedPreset = io.load("Review Panel");
        CreateBinFileAnalysis.BinUserConfig roundTrip =
                CreateBinFileAnalysis.binUserConfigFromPreset(loadedPreset);
        assertUserConfigEquals(reviewConfig, roundTrip);
    }

    @Test
    public void currentSchemaRoundTripsEveryModeAndSortsNonconsecutiveSeries() throws Exception {
        for (ZSliceMode mode : ZSliceMode.values()) {
            BinPreset original = presetWithZMode(mode);
            String first = original.toJson();
            BinPreset loaded = BinPreset.fromJson(first);
            String second = loaded.toJson();

            assertEquals(BinPreset.CURRENT_SCHEMA_VERSION, loaded.getSchemaVersion());
            assertEquals(first, second);
            assertEquals(mode, loaded.getPayload().zSliceMode);
            assertSelectionsEqual(original.getPayload().zSliceSelections,
                    loaded.getPayload().zSliceSelections);
            assertTrue(first.contains("\"schemaVersion\":2"));
            assertTrue(first.contains("\"zSliceSelections\":"));
            if (mode.usesSubset()) {
                assertTrue(first.indexOf("\"seriesIndex\":2")
                        < first.indexOf("\"seriesIndex\":7"));
            }
        }
    }

    @Test
    public void malformedOutOfRangeAndNewerPayloadsFailClosed() throws Exception {
        assertPresetReadFails(
                "{\"name\":\"newer\",\"schemaVersion\":3,\"zSliceMode\":\"FULL\","
                        + "\"zSliceSelections\":[],\"channels\":[]}",
                "newer");
        assertPresetReadFails(
                "{\"name\":\"bad array\",\"schemaVersion\":2,\"zSliceMode\":\"PER_IMAGE\","
                        + "\"zSliceSelections\":{},\"channels\":[]}",
                "array");
        assertPresetReadFails(
                "{\"name\":\"bad range\",\"schemaVersion\":2,\"zSliceMode\":\"PER_IMAGE\","
                        + "\"zSliceSelections\":[{\"seriesIndex\":0,\"displayName\":\"A\","
                        + "\"totalSlices\":5,\"startSlice\":2,\"endSlice\":6}],\"channels\":[]}",
                "range");
        assertPresetReadFails(
                "{\"name\":\"duplicate\",\"schemaVersion\":2,\"zSliceMode\":\"PER_IMAGE\","
                        + "\"zSliceSelections\":[{\"seriesIndex\":1,\"displayName\":\"A\","
                        + "\"totalSlices\":5,\"startSlice\":1,\"endSlice\":2},"
                        + "{\"seriesIndex\":1,\"displayName\":\"B\",\"totalSlices\":5,"
                        + "\"startSlice\":2,\"endSlice\":3}],\"channels\":[]}",
                "duplicate");
        assertPresetReadFails(
                "{\"name\":\"missing\",\"schemaVersion\":2,\"zSliceMode\":\"SAME_COUNT\","
                        + "\"zSliceSelections\":[],\"channels\":[]}",
                "requires");
        assertPresetReadFails(
                "{\"name\":\"fractional\",\"schemaVersion\":2,\"zSliceMode\":\"PER_IMAGE\","
                        + "\"zSliceSelections\":[{\"seriesIndex\":0.5,\"displayName\":\"A\","
                        + "\"totalSlices\":5,\"startSlice\":1,\"endSlice\":2}],\"channels\":[]}",
                "integer");
    }

    @Test
    public void legacyModeOnlySubsetRequiresReviewAndNeverBecomesFullStack() throws Exception {
        String legacy = "{\"name\":\"legacy subset\",\"libraryVersion\":\"1\","
                + "\"zSliceMode\":\"PER_IMAGE\",\"channels\":[]}";
        BinPreset loaded = BinPreset.fromJson(legacy);

        assertEquals(1, loaded.getSchemaVersion());
        assertEquals(ZSliceMode.PER_IMAGE, loaded.getPayload().zSliceMode);
        assertTrue(loaded.getPayload().zSliceSelections.isEmpty());
        assertTrue(loaded.requiresZSliceReview());
        assertTrue(loaded.getZSliceReviewWarning().contains("will not invent a full-stack"));
        try {
            CreateBinFileAnalysis.validateZSlicePresetForSource(loaded,
                    Collections.singletonList(series(0, "A", 10)));
            fail("Expected legacy subset review failure");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("Review"));
        }
        try {
            CreateBinFileAnalysis.binUserConfigFromPreset(loaded);
            fail("Expected legacy subset apply failure");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("will not invent a full-stack"));
        }

        ChannelConfig legacyProjection = new ChannelConfig();
        legacyProjection.zSliceMode = ZSliceMode.PER_IMAGE;
        BinConfig projected = ChannelConfigIO.toBinConfig(legacyProjection);
        assertEquals(ZSliceMode.PER_IMAGE, projected.zSliceMode);
        assertTrue(projected.zSliceSelections.isEmpty());
        assertTrue(projected.zSliceConfigPresent);
    }

    @Test
    public void sourceIdentityValidationRejectsReorderRenameTotalAndMissingSeries() throws Exception {
        BinPreset preset = presetWithZMode(ZSliceMode.PER_IMAGE);
        List<SeriesMeta> matching = Arrays.asList(
                series(2, "Third series", 10),
                series(7, "Eighth series", 12));
        CreateBinFileAnalysis.validateZSlicePresetForSource(preset, matching);

        assertSourceValidationFails(preset, Arrays.asList(
                series(2, "Eighth series", 10),
                series(7, "Third series", 12)), "order/name changed");
        assertSourceValidationFails(preset, Arrays.asList(
                series(2, "Third series renamed", 10),
                series(7, "Eighth series", 12)), "order/name changed");
        assertSourceValidationFails(preset, Arrays.asList(
                series(2, "Third series", 11),
                series(7, "Eighth series", 12)), "total slices");
        assertSourceValidationFails(preset,
                Collections.singletonList(series(2, "Third series", 10)),
                "absent");
    }

    @Test
    public void headlessPresetAppliesExactRangeAndChangedStackPublishesNothing() throws Exception {
        File project = temp.newFolder("headless-z-preset");
        File input = new File(project, "input");
        assertTrue(input.mkdirs());
        File sourceFile = new File(input, "source.tif");
        assertTrue(new FileSaver(stack("source", 6)).saveAsTiffStack(sourceFile.getAbsolutePath()));

        BinPreset exact = oneChannelPreset(ZSliceMode.PER_IMAGE,
                new ZSliceSelection(0, "input - source", 6, new ZSliceRange(2, 4)));
        CreateBinFileAnalysis analysis = new CreateBinFileAnalysis();
        analysis.setHeadless(true);
        analysis.setCommandPreset(exact);
        analysis.execute(project.getAbsolutePath());

        File settings = new File(project, "FLASH/Config/.settings");
        ChannelConfig written = ChannelConfigIO.read(settings);
        assertNotNull(written);
        assertEquals(ZSliceMode.PER_IMAGE, written.zSliceMode);
        assertEquals(new ZSliceRange(2, 4), written.zSliceSelections.get("0"));
        ImagePlus applied = ZSliceOps.applyConfiguredRange(stack("runtime", 6),
                ChannelConfigIO.toBinConfig(written), 0, "headless preset test");
        assertEquals(3, applied.getNSlices());
        applied.close();

        File changedProject = temp.newFolder("headless-z-changed-stack");
        File changedInput = new File(changedProject, "input");
        assertTrue(changedInput.mkdirs());
        assertTrue(new FileSaver(stack("source", 7)).saveAsTiffStack(
                new File(changedInput, "source.tif").getAbsolutePath()));
        CreateBinFileAnalysis changed = new CreateBinFileAnalysis();
        changed.setHeadless(true);
        changed.setCommandPreset(exact);
        changed.execute(changedProject.getAbsolutePath());
        assertFalse(new File(changedProject,
                "FLASH/Config/.settings/" + ChannelConfigIO.FILE_NAME).exists());
    }

    private static BinPreset preset(String name, String threshold) {
        BinConfig config = oneChannelConfig(threshold);
        return new BinPreset(name, "test", "1", config,
                Arrays.asList("microglia_iba1"),
                Arrays.asList("complex"),
                Arrays.asList(Boolean.TRUE));
    }

    private static BinConfig oneChannelConfig(String threshold) {
        BinConfig config = new BinConfig();
        config.channelNames.add("IBA1");
        config.channelColors.add("Green");
        config.channelThresholds.add(threshold);
        config.channelSizes.add("100-50000");
        config.channelMinMax.add("100-65535");
        config.channelIntensityThresholds.add("10");
        config.segmentationMethods.add("classical");
        config.channelFilterPresets.add("Ramified Cells (Microglia/Astrocytes)");
        return config;
    }

    private static BinPreset presetWithZMode(ZSliceMode mode) {
        BinConfig config = oneChannelConfig("12");
        config.zSliceMode = mode;
        if (mode == ZSliceMode.PER_IMAGE) {
            config.zSliceSelections.put(Integer.valueOf(7),
                    new ZSliceSelection(7, "Eighth series", 12, new ZSliceRange(4, 8)));
            config.zSliceSelections.put(Integer.valueOf(2),
                    new ZSliceSelection(2, "Third series", 10, new ZSliceRange(2, 4)));
        } else if (mode == ZSliceMode.SAME_COUNT) {
            config.zSliceSelections.put(Integer.valueOf(7),
                    new ZSliceSelection(7, "Eighth series", 12, new ZSliceRange(4, 6)));
            config.zSliceSelections.put(Integer.valueOf(2),
                    new ZSliceSelection(2, "Third series", 10, new ZSliceRange(2, 4)));
        } else if (mode == ZSliceMode.SAME_ABSOLUTE) {
            config.zSliceSelections.put(Integer.valueOf(7),
                    new ZSliceSelection(7, "Eighth series", 12, new ZSliceRange(2, 4)));
            config.zSliceSelections.put(Integer.valueOf(2),
                    new ZSliceSelection(2, "Third series", 10, new ZSliceRange(2, 4)));
        }
        return new BinPreset("Z mode " + mode.name(), "literal z-slice test", "1", config,
                Arrays.asList("microglia_iba1"),
                Arrays.asList("complex"),
                Arrays.asList(Boolean.TRUE));
    }

    private static BinPreset oneChannelPreset(ZSliceMode mode, ZSliceSelection selection) {
        BinConfig config = oneChannelConfig("12");
        config.zSliceMode = mode;
        if (selection != null) {
            config.zSliceSelections.put(Integer.valueOf(selection.seriesIndex), selection);
        }
        return new BinPreset("Headless z preset", "literal headless test", "1", config,
                Arrays.asList("microglia_iba1"),
                Arrays.asList("complex"),
                Arrays.asList(Boolean.TRUE));
    }

    private static void assertPresetReadFails(String json, String messageFragment) throws Exception {
        try {
            BinPreset.fromJson(json);
            fail("Expected preset read failure containing: " + messageFragment);
        } catch (IOException expected) {
            assertTrue(expected.getMessage().toLowerCase().contains(
                    messageFragment.toLowerCase()));
        }
    }

    private static void assertSourceValidationFails(BinPreset preset,
                                                    List<SeriesMeta> metadata,
                                                    String messageFragment) throws Exception {
        try {
            CreateBinFileAnalysis.validateZSlicePresetForSource(preset, metadata);
            fail("Expected source validation failure containing: " + messageFragment);
        } catch (IOException expected) {
            assertTrue(expected.getMessage().toLowerCase().contains(
                    messageFragment.toLowerCase()));
        }
    }

    private static SeriesMeta series(int index, String name, int zSlices) {
        return new SeriesMeta(index, name, zSlices, 1.0, 1.0, 1.0, "pixel");
    }

    private static ImagePlus stack(String title, int slices) {
        ImageStack stack = new ImageStack(2, 2);
        for (int z = 0; z < slices; z++) {
            stack.addSlice(new ByteProcessor(2, 2));
        }
        ImagePlus image = new ImagePlus(title, stack);
        image.setDimensions(1, slices, 1);
        return image;
    }

    private static ChannelConfig channelConfig() {
        ChannelConfig cfg = new ChannelConfig();
        cfg.writerId = "FLASH";
        addChannel(cfg, 0, "DAPI", "Cyan", "110", "75-2000", "20-4096", "12",
                "classical:otsu", "Default", "nuclei_dapi", "round", false);
        addChannel(cfg, 1, "IBA1", "Green", "220", "100-50000", "100-65535", "24",
                "stardist:prob=0.7;nms=0.3", "Ramified Cells (Microglia/Astrocytes)",
                "microglia_iba1", "complex", true);
        return cfg;
    }

    private static void addChannel(ChannelConfig cfg, int index, String name, String color,
                                   String threshold, String size, String minmax,
                                   String intensity, String segmentation, String filter,
                                   String markerId, String markerShape, boolean crowding) {
        ChannelConfig.Channel channel = new ChannelConfig.Channel();
        channel.index = index;
        channel.name = name;
        channel.color = color;
        channel.threshold = threshold;
        channel.size = size;
        channel.minmax = minmax;
        channel.intensityThreshold = intensity;
        channel.segmentationMethod = segmentation;
        channel.filterPreset = filter;
        channel.markerId = markerId;
        channel.markerShape = markerShape;
        channel.markerCrowdingSensitive = crowding;
        markCommitted(channel, ChannelConfig.P_NAME);
        markCommitted(channel, ChannelConfig.P_COLOR);
        markCommitted(channel, ChannelConfig.P_MARKER);
        markCommitted(channel, ChannelConfig.P_THRESHOLD);
        markCommitted(channel, ChannelConfig.P_SIZE);
        markCommitted(channel, ChannelConfig.P_MINMAX);
        markCommitted(channel, ChannelConfig.P_INTENSITY);
        markCommitted(channel, ChannelConfig.P_SEGMENTATION);
        markCommitted(channel, ChannelConfig.P_FILTER);
        cfg.channels.add(channel);
    }

    private static void markCommitted(ChannelConfig.Channel channel, String property) {
        channel.status.put(property, ChannelConfig.PropertyStatus.COMMITTED);
    }

    private static CreateBinFileAnalysis.BinUserConfig wizardConfig() {
        CreateBinFileAnalysis.BinUserConfig cfg = new CreateBinFileAnalysis.BinUserConfig(
                list("DAPI", "IBA1"),
                list("Cyan", "Green"),
                list("110", "220"),
                list("75-2000", "100-50000"),
                list("20-4096", "100-65535"),
                list("Default", "Ramified Cells (Microglia/Astrocytes)"),
                list("12", "24"));
        cfg.segmentationMethods.clear();
        cfg.segmentationMethods.addAll(list("classical:otsu", "stardist:prob=0.7;nms=0.3"));
        cfg.markerIds.clear();
        cfg.markerIds.addAll(list("nuclei_dapi", "microglia_iba1"));
        cfg.markerShapes.clear();
        cfg.markerShapes.addAll(list("round", "complex"));
        cfg.markerCrowdingSensitive.clear();
        cfg.markerCrowdingSensitive.addAll(Arrays.asList(Boolean.FALSE, Boolean.TRUE));
        cfg.zSliceMode = ZSliceMode.PER_IMAGE;
        cfg.zSliceSelections.put(Integer.valueOf(4),
                new ZSliceSelection(4, "Fifth series", 20, new ZSliceRange(6, 11)));
        cfg.zSliceSelections.put(Integer.valueOf(1),
                new ZSliceSelection(1, "Second series", 15, new ZSliceRange(3, 9)));
        return cfg;
    }

    private static ArrayList<String> list(String first, String second) {
        return new ArrayList<String>(Arrays.asList(first, second));
    }

    private static void assertImportedConfigMatchesSource(CreateBinFileAnalysis.BinUserConfig cfg) {
        assertEquals(Arrays.asList("DAPI", "IBA1"), cfg.names);
        assertEquals(Arrays.asList("Cyan", "Green"), cfg.colors);
        assertEquals(Arrays.asList("110", "220"), cfg.objectThresholds);
        assertEquals(Arrays.asList("75-2000", "100-50000"), cfg.sizes);
        assertEquals(Arrays.asList("20-4096", "100-65535"), cfg.minmax);
        assertEquals(Arrays.asList("12", "24"), cfg.intensityThresholds);
        assertEquals(Arrays.asList("classical:otsu", "stardist:prob=0.7;nms=0.3"),
                cfg.segmentationMethods);
        assertEquals(Arrays.asList("Default", "Ramified Cells (Microglia/Astrocytes)"),
                cfg.filterPresets);
        assertEquals(Arrays.asList("nuclei_dapi", "microglia_iba1"), cfg.markerIds);
        assertEquals(Arrays.asList("round", "complex"), cfg.markerShapes);
        assertEquals(Arrays.asList(Boolean.FALSE, Boolean.TRUE), cfg.markerCrowdingSensitive);
    }

    private static void assertUserConfigEquals(CreateBinFileAnalysis.BinUserConfig expected,
                                               CreateBinFileAnalysis.BinUserConfig actual) {
        assertEquals(expected.names, actual.names);
        assertEquals(expected.colors, actual.colors);
        assertEquals(expected.objectThresholds, actual.objectThresholds);
        assertEquals(expected.sizes, actual.sizes);
        assertEquals(expected.minmax, actual.minmax);
        assertEquals(expected.intensityThresholds, actual.intensityThresholds);
        assertEquals(expected.segmentationMethods, actual.segmentationMethods);
        assertEquals(expected.filterPresets, actual.filterPresets);
        assertEquals(expected.markerIds, actual.markerIds);
        assertEquals(expected.markerShapes, actual.markerShapes);
        assertEquals(expected.markerCrowdingSensitive, actual.markerCrowdingSensitive);
        assertEquals(expected.zSliceMode, actual.zSliceMode);
        assertSelectionsEqual(expected.zSliceSelections, actual.zSliceSelections);
    }

    private static void assertSelectionsEqual(Map<Integer, ZSliceSelection> expected,
                                              Map<Integer, ZSliceSelection> actual) {
        assertEquals(expected.keySet(), actual.keySet());
        for (Integer key : expected.keySet()) {
            ZSliceSelection left = expected.get(key);
            ZSliceSelection right = actual.get(key);
            assertNotNull(right);
            assertEquals(left.seriesIndex, right.seriesIndex);
            assertEquals(left.seriesName, right.seriesName);
            assertEquals(left.totalSlices, right.totalSlices);
            assertEquals(left.range, right.range);
        }
    }

    private static final class CrashyBinPresetIO extends BinPresetIO {
        boolean crashOnMove = false;

        private CrashyBinPresetIO(File projectRoot) {
            super(projectRoot);
        }

        @Override
        protected void moveAtomically(File source, File target) throws IOException {
            if (crashOnMove) {
                throw new IOException("simulated crash");
            }
            super.moveAtomically(source, target);
        }
    }
}
