package flash.pipeline.analyses.wizard;

import flash.pipeline.analyses.CreateBinFileAnalysis;
import flash.pipeline.bin.BinConfig;
import flash.pipeline.bin.ChannelConfig;
import flash.pipeline.bin.ChannelConfigIO;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

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
        assertEquals(1, presets.size());
        assertEquals("Review Panel", presets.get(0).getName());

        BinPreset loadedPreset = io.load("Review Panel");
        CreateBinFileAnalysis.BinUserConfig roundTrip =
                CreateBinFileAnalysis.binUserConfigFromPreset(loadedPreset);
        assertUserConfigEquals(reviewConfig, roundTrip);
    }

    private static BinPreset preset(String name, String threshold) {
        BinConfig config = new BinConfig();
        config.channelNames.add("IBA1");
        config.channelColors.add("Green");
        config.channelThresholds.add(threshold);
        config.channelSizes.add("100-50000");
        config.channelMinMax.add("100-65535");
        config.channelIntensityThresholds.add("10");
        config.segmentationMethods.add("classical");
        config.channelFilterPresets.add("Ramified Cells (Microglia/Astrocytes)");
        return new BinPreset(name, "test", "1", config,
                Arrays.asList("microglia_iba1"),
                Arrays.asList("complex"),
                Arrays.asList(Boolean.TRUE));
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
