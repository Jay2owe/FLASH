package flash.pipeline.audit;

import flash.pipeline.bin.BinConfig;
import flash.pipeline.bin.BinConfigIO;
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
        BinConfigIO.writeFromConfig(dir.getAbsolutePath(), cfg);

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
    public void writerCreatesSnapshotAndReplayFilesInFlashOutputAndAuditFolders() throws Exception {
        File dir = temp.newFolder("write");
        BinConfigIO.writeFromConfig(dir.getAbsolutePath(), representativeConfig());

        RunSettingsSnapshot.writeForAnalysis(
                dir.getAbsolutePath(),
                "3D Object Analysis",
                4,
                EnumSet.of(BinField.CHANNEL_NAMES, BinField.OBJECT_THRESHOLDS),
                null,
                null);

        FlashProjectLayout layout = FlashProjectLayout.forDirectory(dir.getAbsolutePath());
        File objectOutput = layout.analysisWriteDir(FlashProjectLayout.AnalysisFolder.OBJECTS);
        File auditOutput = new File(layout.auditRoot(), "04 - 3D Object Analysis");
        File objectSettings = FlashProjectLayout.settingsDir(objectOutput);
        File auditSettings = FlashProjectLayout.settingsDir(auditOutput);
        assertTrue(new File(objectSettings, RunSettingsSnapshot.SETTINGS_FILENAME).isFile());
        assertTrue(new File(objectSettings, RunSettingsSnapshot.REPLAY_FILENAME).isFile());
        assertTrue(new File(auditSettings, RunSettingsSnapshot.SETTINGS_FILENAME).isFile());
        assertTrue(new File(auditSettings, RunSettingsSnapshot.REPLAY_FILENAME).isFile());
        assertFalse(new File(dir, "Data Analysis").exists());

        String replay = new String(Files.readAllBytes(
                new File(objectSettings, RunSettingsSnapshot.REPLAY_FILENAME).toPath()),
                StandardCharsets.UTF_8);
        assertTrue(replay.contains("run_3d"));
        assertTrue(replay.contains("dir=["));
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
        assertEquals(expectedSelection.seriesName, actualSelection.seriesName);
        assertEquals(expectedSelection.totalSlices, actualSelection.totalSlices);
        assertEquals(expectedSelection.range, actualSelection.range);
    }

    private final class RunSettingsSnapshotTestHarness {
        RunSettingsSnapshot snapshot() throws Exception {
            File dir = temp.newFolder("reflection");
            BinConfigIO.writeFromConfig(dir.getAbsolutePath(), representativeConfig());
            return RunSettingsSnapshot.create(dir.getAbsolutePath(), "Test", 7,
                    EnumSet.of(BinField.CHANNEL_NAMES), null, null);
        }
    }
}
