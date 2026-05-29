package flash.pipeline.decontamination;

import flash.pipeline.TestConfigFiles;
import flash.pipeline.bin.ChannelConfigIO;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SpectralDecontaminationConfigIOTest {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    @Test
    public void roundTripPersistsChannelRolesAndGoal() throws Exception {
        File dir = tempFolder.newFolder("data");

        SpectralDecontaminationConfig config = new SpectralDecontaminationConfig();
        config.setTargetChannelIndex(2);
        config.setGoal(SpectralDecontaminationConfig.Goal.CREATE_CLEANED_MASK);
        config.setConditionSource(SpectralDecontaminationConfig.ConditionSource.ASSIGN_MANUALLY);
        config.setBleedThroughChannelIndexes(indexes(0, 1));
        config.setAutofluorescenceChannelIndexes(indexes(1));
        config.setExcludedChannelIndexes(indexes(3));
        config.setControlConditionNames(strings("Control"));
        config.setExperimentalConditionNames(strings("Treatment A", "Treatment B"));
        CorrectionPipeline pipeline = new CorrectionPipeline();
        pipeline.setPresetId(CorrectionFeatureRegistry.PRESET_BASIC);
        pipeline.setExpertMode(false);
        pipeline.setFeatureIds(strings(
                "linear_unmixing",
                "threshold_corrected_target",
                "size_filter"));
        config.setCorrectionPipeline(pipeline);
        config.setFeatureSettings("threshold_corrected_target",
                new CorrectionPipeline.Settings()
                        .put("threshold_mode", "fixed")
                        .putDouble("threshold_value", 123.0));

        SpectralDecontaminationConfigIO.writeToDirectory(dir.getAbsolutePath(), config);
        SpectralDecontaminationConfig read = SpectralDecontaminationConfigIO.readFromDirectory(dir.getAbsolutePath());

        assertTrue(new File(dir, "FLASH/Config/.settings/"
                + SpectralDecontaminationConfigIO.CONFIG_FILENAME).isFile());
        assertFalse(new File(new File(dir, ".bin"),
                SpectralDecontaminationConfigIO.CONFIG_FILENAME).exists());
        assertEquals(SpectralDecontaminationConfig.CURRENT_VERSION, read.getVersion());
        assertEquals(2, read.getTargetChannelIndex());
        assertEquals(SpectralDecontaminationConfig.Goal.CREATE_CLEANED_MASK, read.getGoal());
        assertEquals(SpectralDecontaminationConfig.ConditionSource.ASSIGN_MANUALLY, read.getConditionSource());
        assertEquals(indexes(0, 1), read.getBleedThroughChannelIndexes());
        assertEquals(indexes(1), read.getAutofluorescenceChannelIndexes());
        assertEquals(indexes(3), read.getExcludedChannelIndexes());
        assertEquals(strings("Control"), read.getControlConditionNames());
        assertEquals(strings("Treatment A", "Treatment B"), read.getExperimentalConditionNames());
        assertEquals(CorrectionFeatureRegistry.PRESET_BASIC, read.getCorrectionPipeline().getPresetId());
        assertFalse(read.getCorrectionPipeline().isExpertMode());
        assertEquals(strings("linear_unmixing", "threshold_corrected_target", "size_filter"),
                read.getCorrectionPipeline().getFeatureIds());
        assertEquals("fixed", read.getFeatureSettings("threshold_corrected_target").get("threshold_mode", ""));
        assertEquals(123.0,
                read.getFeatureSettings("threshold_corrected_target").getDouble("threshold_value", 0.0),
                0.0);
    }

    @Test
    public void writeDoesNotModifyChannelConfig() throws Exception {
        File dir = tempFolder.newFolder("data");
        TestConfigFiles.writeChannelConfig(dir, TestConfigFiles.basicBinConfig("DAPI", "GFP", "RFP", "AF"));
        File channelConfig = new File(TestConfigFiles.settingsDir(dir), ChannelConfigIO.FILE_NAME);
        String before = new String(Files.readAllBytes(channelConfig.toPath()), StandardCharsets.UTF_8);

        SpectralDecontaminationConfig config = new SpectralDecontaminationConfig();
        config.setTargetChannelIndex(0);
        config.setGoal(SpectralDecontaminationConfig.Goal.SCORE_EXISTING_OBJECTS);
        SpectralDecontaminationConfigIO.writeToDirectory(dir.getAbsolutePath(), config);

        String after = new String(Files.readAllBytes(channelConfig.toPath()), StandardCharsets.UTF_8);
        assertEquals(before, after);
    }

    @Test
    public void writeCreatesParentDirectoryBeforeAtomicReplace() throws Exception {
        File file = new File(tempFolder.getRoot(), "new-settings/"
                + SpectralDecontaminationConfigIO.CONFIG_FILENAME);
        SpectralDecontaminationConfig config = new SpectralDecontaminationConfig();
        config.setTargetChannelIndex(1);
        config.setGoal(SpectralDecontaminationConfig.Goal.SCORE_EXISTING_OBJECTS);

        SpectralDecontaminationConfigIO.write(file, config);
        SpectralDecontaminationConfig read = SpectralDecontaminationConfigIO.read(file);

        assertTrue(file.isFile());
        assertEquals(1, read.getTargetChannelIndex());
        assertEquals(SpectralDecontaminationConfig.Goal.SCORE_EXISTING_OBJECTS, read.getGoal());
    }

    @Test
    public void readOrDefaultUsesChannelCountWhenFileIsMissing() throws Exception {
        File dir = tempFolder.newFolder("data");

        assertFalse(SpectralDecontaminationConfigIO.exists(dir.getAbsolutePath()));
        SpectralDecontaminationConfig config =
                SpectralDecontaminationConfigIO.readOrDefault(dir.getAbsolutePath(), 4);

        assertEquals(0, config.getTargetChannelIndex());
        assertEquals(SpectralDecontaminationConfig.Goal.CREATE_CLEANED_IMAGE, config.getGoal());
        assertTrue(config.getBleedThroughChannelIndexes().isEmpty());
        assertTrue(config.getAutofluorescenceChannelIndexes().isEmpty());
    }

    @Test
    public void readAcceptsGoalLabelForManualEdits() throws Exception {
        File file = tempFolder.newFile("Spectral_Decontamination_Config.json");
        String json = "{\n"
                + "  \"version\": 1,\n"
                + "  \"goal\": \"Score existing objects\",\n"
                + "  \"targetChannelIndex\": 1,\n"
                + "  \"bleedThroughChannelIndexes\": [],\n"
                + "  \"autofluorescenceChannelIndexes\": [],\n"
                + "  \"excludedChannelIndexes\": [2]\n"
                + "}\n";
        Files.write(file.toPath(), json.getBytes(StandardCharsets.UTF_8));

        SpectralDecontaminationConfig config = SpectralDecontaminationConfigIO.read(file);

        assertEquals(SpectralDecontaminationConfig.Goal.SCORE_EXISTING_OBJECTS, config.getGoal());
        assertEquals(1, config.getTargetChannelIndex());
        assertEquals(indexes(2), config.getExcludedChannelIndexes());
    }

    private static List<Integer> indexes(int... values) {
        List<Integer> indexes = new ArrayList<Integer>();
        for (int value : values) {
            indexes.add(Integer.valueOf(value));
        }
        return indexes;
    }

    private static List<String> strings(String... values) {
        List<String> strings = new ArrayList<String>();
        for (String value : values) {
            strings.add(value);
        }
        return strings;
    }
}
