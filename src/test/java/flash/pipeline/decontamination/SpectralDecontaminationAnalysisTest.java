package flash.pipeline.decontamination;

import flash.pipeline.TestConfigFiles;
import flash.pipeline.bin.BinConfig;
import flash.pipeline.bin.BinField;
import flash.pipeline.bin.ChannelConfig;
import flash.pipeline.bin.ChannelConfigIO;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.Arrays;
import java.util.EnumSet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SpectralDecontaminationAnalysisTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void spectralDecontaminationDeclaresHeadedModeForMainGui() {
        assertTrue(new SpectralDecontaminationAnalysis().requiresHeadedMode());
    }

    @Test
    public void spectralDecontaminationRequiresOnlyChannelNamesFromSetup() {
        assertEquals(EnumSet.of(BinField.CHANNEL_NAMES),
                new SpectralDecontaminationAnalysis().requiredBinFields());
    }

    @Test
    public void loadBinConfigAcceptsPartialSetupWithConfiguredChannelNames() throws Exception {
        File project = temp.newFolder("partial-spectral-config");
        ChannelConfigIO.write(TestConfigFiles.settingsDir(project), partialNamesConfig());

        BinConfig cfg = new SpectralDecontaminationAnalysis().loadBinConfig(project.getAbsolutePath());

        assertEquals(Arrays.asList("IgG", "IBA1", "mCherry", "Cleaved Caspase-3"), cfg.channelNames);
        assertTrue(cfg.hasChannelNames());
        assertFalse(cfg.hasChannelThresholds());
        assertFalse(cfg.hasChannelSizes());
        assertFalse(cfg.hasSegmentationMethods());
    }

    private static ChannelConfig partialNamesConfig() {
        ChannelConfig cfg = new ChannelConfig();
        cfg.complete = Boolean.FALSE;
        String[] names = {"IgG", "IBA1", "mCherry", "Cleaved Caspase-3"};
        String[] colors = {"Magenta", "Cyan", "Red", "Green"};
        for (int i = 0; i < names.length; i++) {
            ChannelConfig.Channel channel = new ChannelConfig.Channel();
            channel.index = i;
            channel.name = names[i];
            channel.color = colors[i];
            channel.threshold = "default";
            channel.size = "100-Infinity";
            channel.minmax = "None";
            channel.intensityThreshold = "default";
            channel.segmentationMethod = "classical";
            channel.filterPreset = "Default";
            channel.status.put(ChannelConfig.P_NAME, ChannelConfig.PropertyStatus.CONFIGURED);
            channel.status.put(ChannelConfig.P_COLOR, ChannelConfig.PropertyStatus.CONFIGURED);
            channel.status.put(ChannelConfig.P_MARKER, ChannelConfig.PropertyStatus.CONFIGURED);
            channel.status.put(ChannelConfig.P_THRESHOLD, ChannelConfig.PropertyStatus.PENDING);
            channel.status.put(ChannelConfig.P_SIZE, ChannelConfig.PropertyStatus.PENDING);
            channel.status.put(ChannelConfig.P_MINMAX, ChannelConfig.PropertyStatus.COMMITTED);
            channel.status.put(ChannelConfig.P_INTENSITY, ChannelConfig.PropertyStatus.CONFIGURED);
            channel.status.put(ChannelConfig.P_SEGMENTATION, ChannelConfig.PropertyStatus.PENDING);
            channel.status.put(ChannelConfig.P_FILTER, ChannelConfig.PropertyStatus.CONFIGURED);
            cfg.channels.add(channel);
        }
        return cfg;
    }
}
