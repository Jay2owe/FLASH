package flash.pipeline.analyses;

import flash.pipeline.TestConfigFiles;
import flash.pipeline.bin.BinConfig;
import flash.pipeline.bin.ChannelIdentities;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

public class SplitAndMergeWizardBehaviorTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void detectsAutofluorescenceMarkerIdentityAsBackgroundHint() {
        ChannelIdentities identities = new ChannelIdentities(Arrays.asList(
                new ChannelIdentities.Entry(0, "nuclei_dapi", "round", false),
                new ChannelIdentities.Entry(1, "autofluorescence", "background", false),
                new ChannelIdentities.Entry(2, "microglia_iba1", "complex", true)));

        assertEquals(1, SplitAndMergeImageChannelsAnalysis.detectAutofluorescenceChannel(identities, 3));
    }

    @Test
    public void readsAutofluorescenceHintFromProjectBinDirectory() throws Exception {
        File root = temp.newFolder("project");
        TestConfigFiles.writeChannelConfig(root, twoChannelConfig(), new ChannelIdentities(Arrays.asList(
                new ChannelIdentities.Entry(0, "nuclei_dapi", "round", false),
                new ChannelIdentities.Entry(1, "my_autofluorescence_control", "", false))));

        assertEquals(1, SplitAndMergeImageChannelsAnalysis.detectAutofluorescenceChannel(root, 2));
    }

    @Test
    public void ignoresOutOfRangeAutofluorescenceHint() {
        ChannelIdentities identities = new ChannelIdentities(Arrays.asList(
                new ChannelIdentities.Entry(4, "autofluorescence", "background", false)));

        assertEquals(-1, SplitAndMergeImageChannelsAnalysis.detectAutofluorescenceChannel(identities, 3));
    }

    @Test
    public void presentationImagePresetsAreAbsent() {
        assertFalse(new File("src/main/resources/split_merge_presets").exists());
        assertFalse(new File("src/main/java/flash/pipeline/analyses/wizard/SplitMergePreset.java").exists());
        assertFalse(new File("src/main/java/flash/pipeline/analyses/wizard/SplitMergePresetIO.java").exists());
    }

    private static BinConfig twoChannelConfig() {
        BinConfig cfg = new BinConfig();
        cfg.channelNames.add("DAPI");
        cfg.channelNames.add("405");
        cfg.channelColors.add("Blue");
        cfg.channelColors.add("Cyan");
        cfg.channelThresholds.add("default");
        cfg.channelThresholds.add("default");
        cfg.channelSizes.add("100-Infinity");
        cfg.channelSizes.add("100-Infinity");
        cfg.channelMinMax.add("None");
        cfg.channelMinMax.add("None");
        cfg.channelIntensityThresholds.add("default");
        cfg.channelIntensityThresholds.add("default");
        cfg.segmentationMethods.add("classical");
        cfg.segmentationMethods.add("classical");
        cfg.channelFilterPresets.add("Default");
        cfg.channelFilterPresets.add("Default");
        return cfg;
    }
}
