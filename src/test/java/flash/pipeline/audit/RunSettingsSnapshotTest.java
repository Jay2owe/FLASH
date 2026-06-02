package flash.pipeline.audit;

import flash.pipeline.TestConfigFiles;
import flash.pipeline.bin.BinConfig;
import flash.pipeline.bin.BinField;
import flash.pipeline.bin.BinSetupDispatcher;
import flash.pipeline.io.FlashProjectLayout;
import flash.pipeline.ui.wizard.JsonIO;
import flash.pipeline.zslice.ZSliceMode;
import flash.pipeline.zslice.ZSliceRange;
import flash.pipeline.zslice.ZSliceSelection;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

public class RunSettingsSnapshotTest {
    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void jsonRoundTripPreservesBinConfigAndFieldSources() throws Exception {
        File dir = temp.newFolder("run");
        BinConfig cfg = representativeConfig();
        TestConfigFiles.writeChannelConfig(dir, cfg);

        EnumMap<BinField, String> sources = new EnumMap<BinField, String>(BinField.class);
        sources.put(BinField.CHANNEL_NAMES, BinSetupDispatcher.SOURCE_LOADED);
        sources.put(BinField.INTENSITY_THRESHOLDS, BinSetupDispatcher.SOURCE_PROMPTED_PARTIAL);
        sources.put(BinField.Z_SLICE, BinSetupDispatcher.SOURCE_CLI_ARGUMENT);

        RunSettingsSnapshot snapshot = RunSettingsSnapshot.create(
                dir.getAbsolutePath(),
                "Intensity Analysis",
                7,
                EnumSet.of(BinField.CHANNEL_NAMES, BinField.INTENSITY_THRESHOLDS, BinField.Z_SLICE),
                sources,
                null);

        RunSettingsSnapshot parsed = RunSettingsSnapshot.fromJson(snapshot.toJson());
        assertEquals(snapshot.analysis, parsed.analysis);
        assertEquals(snapshot.analysisIndex, parsed.analysisIndex);
        assertEquals(snapshot.directory, parsed.directory);
        assertEquals(snapshot.fieldSources, parsed.fieldSources);
        assertBinConfigEquals(cfg, parsed.binConfig);
        assertEquals(BinSetupDispatcher.SOURCE_PROMPTED_PARTIAL,
                parsed.fieldSources.get("intensity_thresholds"));
        assertEquals(BinSetupDispatcher.SOURCE_CLI_ARGUMENT,
                parsed.fieldSources.get("z_slice_mode"));
    }

    @Test
    public void writerPlacesSnapshotAndReplayFilesUnderRunRecords() throws Exception {
        File dir = temp.newFolder("write");
        TestConfigFiles.writeChannelConfig(dir, representativeConfig());

        RunSettingsSnapshot.writeForAnalysis(
                dir.getAbsolutePath(),
                "3D Object Analysis",
                4,
                EnumSet.of(BinField.CHANNEL_NAMES, BinField.OBJECT_THRESHOLDS),
                null,
                null);

        FlashProjectLayout layout = FlashProjectLayout.forDirectory(dir.getAbsolutePath());
        String prefix = "3D Object Analysis";
        File snapshotFile = new File(layout.settingsSnapshotsWriteDir(),
                prefix + RunSettingsSnapshot.SETTINGS_EXTENSION);
        File replayFile = new File(layout.replayCommandsWriteDir(),
                prefix + RunSettingsSnapshot.REPLAY_EXTENSION);
        assertTrue(snapshotFile.isFile());
        assertTrue(replayFile.isFile());
        assertFalse(new File(dir, "Data Analysis").exists());

        String replay = new String(Files.readAllBytes(replayFile.toPath()),
                StandardCharsets.UTF_8);
        assertTrue(replay.contains("run_3d"));
        assertTrue(replay.contains("dir=["));

        File runHistory = layout.runHistoryWriteFile();
        assertTrue(runHistory.isFile());
        String runHistoryText = new String(Files.readAllBytes(runHistory.toPath()),
                StandardCharsets.UTF_8);
        assertTrue(runHistoryText.contains("timestamp,analysisIndex,analysisName"));
        assertTrue(runHistoryText.contains("3D Object Analysis"));
    }

    @Test
    public void writerEmitsOneSnapshotPerAnalysisInRunRecords() throws Exception {
        File dir = temp.newFolder("indexFolders");
        TestConfigFiles.writeChannelConfig(dir, representativeConfig());

        RunSettingsSnapshot.writeForAnalysis(dir.getAbsolutePath(), "Aggregation", 8,
                EnumSet.of(BinField.CHANNEL_NAMES), null, null);
        RunSettingsSnapshot.writeForAnalysis(dir.getAbsolutePath(), "Statistics", 9,
                EnumSet.of(BinField.CHANNEL_NAMES), null, null);
        RunSettingsSnapshot.writeForAnalysis(dir.getAbsolutePath(), "Excel", 10,
                EnumSet.of(BinField.CHANNEL_NAMES), null, null);
        RunSettingsSnapshot.writeForAnalysis(dir.getAbsolutePath(), "Spectral", 11,
                EnumSet.of(BinField.CHANNEL_NAMES), null, null);

        FlashProjectLayout layout = FlashProjectLayout.forDirectory(dir.getAbsolutePath());
        assertSnapshotPair(layout, 8, "Aggregation");
        assertSnapshotPair(layout, 9, "Statistics");
        assertSnapshotPair(layout, 10, "Excel");
        assertSnapshotPair(layout, 11, "Spectral");
    }

    @Test
    public void snapshotMentionsEveryPublicListFieldOnBinConfig() throws Exception {
        RunSettingsSnapshot snapshot = new RunSettingsSnapshotTestHarness().snapshot();
        Map<String, Object> root = JsonIO.parseObject(snapshot.toJson());
        Map<String, Object> bin = JsonIO.asObject(root.get("bin_config"));

        for (Field field : BinConfig.class.getFields()) {
            if (List.class.isAssignableFrom(field.getType())) {
                assertTrue("Missing JSON entry for BinConfig." + field.getName(),
                        bin.containsKey(expectedJsonKey(field.getName())));
            }
        }
    }

    private static String expectedJsonKey(String fieldName) {
        if ("channelNames".equals(fieldName)) return "channel_names";
        if ("channelColors".equals(fieldName)) return "channel_colors";
        if ("channelThresholds".equals(fieldName)) return "object_thresholds";
        if ("channelSizes".equals(fieldName)) return "particle_sizes";
        if ("channelMinMax".equals(fieldName)) return "display_min_max";
        if ("channelIntensityThresholds".equals(fieldName)) return "intensity_thresholds";
        if ("segmentationMethods".equals(fieldName)) return "segmentation_methods";
        if ("channelFilterPresets".equals(fieldName)) return "filter_presets";
        return fieldName;
    }

    private BinConfig representativeConfig() {
        BinConfig cfg = new BinConfig();
        cfg.channelNames.add("DAPI");
        cfg.channelNames.add("Iba1");
        cfg.channelColors.add("Cyan");
        cfg.channelColors.add("Green");
        cfg.channelThresholds.add("default");
        cfg.channelThresholds.add("1500");
        cfg.channelSizes.add("100-Infinity");
        cfg.channelSizes.add("200-1000");
        cfg.channelMinMax.add("None");
        cfg.channelMinMax.add("0-4095");
        cfg.channelIntensityThresholds.add("default");
        cfg.channelIntensityThresholds.add("500");
        cfg.segmentationMethods.add("classical");
        cfg.segmentationMethods.add("cellpose:30:nuclei");
        cfg.channelFilterPresets.add("Default");
        cfg.channelFilterPresets.add("Ramified Cells (Microglia/Astrocytes)");
        cfg.zSliceMode = ZSliceMode.PER_IMAGE;
        cfg.zSliceConfigPresent = true;
        cfg.zSliceSelections.put(Integer.valueOf(0),
                new ZSliceSelection(0, "Series 1", 10, new ZSliceRange(2, 8)));
        return cfg;
    }

    private static void assertBinConfigEquals(BinConfig expected, BinConfig actual) {
        assertEquals(expected.channelNames, actual.channelNames);
        assertEquals(expected.channelColors, actual.channelColors);
        assertEquals(expected.channelThresholds, actual.channelThresholds);
        assertEquals(expected.channelSizes, actual.channelSizes);
        assertEquals(expected.channelMinMax, actual.channelMinMax);
        assertEquals(expected.channelIntensityThresholds, actual.channelIntensityThresholds);
        assertEquals(expected.segmentationMethods, actual.segmentationMethods);
        assertEquals(expected.channelFilterPresets, actual.channelFilterPresets);
        assertEquals(expected.zSliceMode, actual.zSliceMode);
        assertEquals(expected.zSliceConfigPresent, actual.zSliceConfigPresent);
        assertEquals(expected.zSliceSelections.size(), actual.zSliceSelections.size());
        ZSliceSelection expectedSelection = expected.zSliceSelections.get(Integer.valueOf(0));
        ZSliceSelection actualSelection = actual.zSliceSelections.get(Integer.valueOf(0));
        assertNotNull(actualSelection);
        assertEquals(expectedSelection.seriesIndex, actualSelection.seriesIndex);
        assertEquals(expectedSelection.range, actualSelection.range);
    }

    private static void assertSnapshotPair(FlashProjectLayout layout, int analysisIndex, String analysisName) {
        String prefix = RunSettingsSnapshot.safeRecordName(analysisIndex, analysisName);
        assertTrue(new File(layout.settingsSnapshotsWriteDir(),
                prefix + RunSettingsSnapshot.SETTINGS_EXTENSION).isFile());
        assertTrue(new File(layout.replayCommandsWriteDir(),
                prefix + RunSettingsSnapshot.REPLAY_EXTENSION).isFile());
    }

    private final class RunSettingsSnapshotTestHarness {
        RunSettingsSnapshot snapshot() throws Exception {
            File dir = temp.newFolder("reflection");
            TestConfigFiles.writeChannelConfig(dir, representativeConfig());
            return RunSettingsSnapshot.create(dir.getAbsolutePath(), "Test", 7,
                    EnumSet.of(BinField.CHANNEL_NAMES), null, null);
        }
    }
}
