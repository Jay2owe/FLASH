package flash.pipeline.bin;

import flash.pipeline.TestConfigFiles;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;

import static org.junit.Assert.assertEquals;

public class BinConfigIOJsonTest {
    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void readFromDirectoryLoadsChannelConfigJson() throws Exception {
        File project = temp.newFolder("project");
        BinConfig cfg = twoChannelConfig();
        TestConfigFiles.writeChannelConfig(project, cfg);

        BinConfig read = BinConfigIO.readFromDirectory(project.getAbsolutePath());

        assertEquals(cfg.channelNames, read.channelNames);
        assertEquals(cfg.channelColors, read.channelColors);
        assertEquals(cfg.segmentationMethods, read.segmentationMethods);
    }

    @Test
    public void updateMinMaxMutatesChannelConfigJson() throws Exception {
        File project = temp.newFolder("project");
        TestConfigFiles.writeChannelConfig(project, twoChannelConfig());

        BinConfigIO.updateMinMax(project.getAbsolutePath(), new String[]{"10-200", null});
        BinConfig read = BinConfigIO.readFromDirectory(project.getAbsolutePath());

        assertEquals("10-200", read.channelMinMax.get(0));
        assertEquals("None", read.channelMinMax.get(1));
    }

    @Test
    public void customFilterPresetTokenRoundTripsForReplayCommand() {
        assertEquals("custom_filter:IBA1%20cleanup%20filter",
                BinConfigIO.encodeFilterPresetToken("IBA1 cleanup filter"));
    }

    private static BinConfig twoChannelConfig() {
        BinConfig cfg = new BinConfig();
        cfg.channelNames.add("DAPI");
        cfg.channelNames.add("IBA1");
        cfg.channelColors.add("Blue");
        cfg.channelColors.add("Green");
        cfg.channelThresholds.add("default");
        cfg.channelThresholds.add("500");
        cfg.channelSizes.add("100-Infinity");
        cfg.channelSizes.add("50-5000");
        cfg.channelMinMax.add("None");
        cfg.channelMinMax.add("0-4095");
        cfg.channelIntensityThresholds.add("default");
        cfg.channelIntensityThresholds.add("750");
        cfg.segmentationMethods.add("classical");
        cfg.segmentationMethods.add("stardist:0.5:0.4");
        cfg.channelFilterPresets.add("Default");
        cfg.channelFilterPresets.add("Clustered Large");
        return cfg;
    }
}
