package flash.pipeline.decontamination.wizard;

import flash.pipeline.bin.BinConfig;
import flash.pipeline.bin.ChannelIdentities;
import flash.pipeline.bin.ChannelIdentitiesIO;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class SpectralDecontamAutoDetectTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void iba1Abeta405DatasetDetectsRoles() throws Exception {
        File root = temp.newFolder("project");
        File bin = new File(root, ".bin");
        assertTrue(bin.mkdirs());
        ChannelIdentitiesIO.write(bin, new ChannelIdentities(Arrays.asList(
                new ChannelIdentities.Entry(1, "microglia_iba1", "complex", true),
                new ChannelIdentities.Entry(2, "amyloid_abeta", "plaque", false),
                new ChannelIdentities.Entry(3, "autofluorescence_channel", "", false)
        )));

        SpectralDecontaminationWizard.AutoDetection detection =
                SpectralDecontaminationWizard.autoDetect(root, binConfig());

        assertEquals(1, detection.targetChannelIndex);
        assertTrue(detection.bleedThroughChannels.contains(Integer.valueOf(2)));
        assertTrue(detection.autofluorescenceChannels.contains(Integer.valueOf(3)));
    }

    private static BinConfig binConfig() {
        BinConfig config = new BinConfig();
        config.channelNames.addAll(Arrays.asList("DAPI", "IBA1", "Abeta", "405"));
        config.channelSizes.addAll(Arrays.asList("100-Infinity", "50-Infinity", "25-Infinity", "50-Infinity"));
        return config;
    }
}
