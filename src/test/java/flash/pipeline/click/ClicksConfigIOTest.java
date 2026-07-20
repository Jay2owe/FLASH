package flash.pipeline.click;

import flash.pipeline.bin.ChannelConfig;
import flash.pipeline.bin.ChannelConfigIO;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ClicksConfigIOTest {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    @Test
    public void roundTripWritesJsonAndMarksChannelConfigPresence() throws Exception {
        File bin = tempFolder.newFolder("bin");
        ChannelConfigIO.write(bin, oneChannelConfig());
        ClickStore store = new ClickStore();
        store.add(new ClickStore.Click(
                "WT-Slide12_LH_CA1.lif - Series 3",
                2,
                47,
                12,
                421.5,
                138.0,
                ClickStore.Verdict.NEGATIVE,
                1747850000000L));

        ClicksConfigIO.write(bin, store);
        ClickStore roundTrip = ClicksConfigIO.read(bin);

        assertTrue(ClicksConfigIO.exists(bin));
        assertEquals(1, roundTrip.all().size());
        ClickStore.Click click = roundTrip.all().get(0);
        assertEquals("WT-Slide12_LH_CA1.lif - Series 3", click.imageName);
        assertEquals(2, click.channelOneBased);
        assertEquals(47, click.label);
        assertEquals(12, click.z);
        assertEquals(421.5, click.x, 0.0001);
        assertEquals(ClickStore.Verdict.NEGATIVE, click.verdict);

        assertTrue(ChannelConfigIO.read(bin).clickCaptureUsed);
    }

    @Test
    public void missingFileReadsAsEmptyStore() throws Exception {
        File bin = tempFolder.newFolder("missing");

        ClickStore store = ClicksConfigIO.read(bin);

        assertFalse(ClicksConfigIO.exists(bin));
        assertEquals(0, store.all().size());
    }

    @Test
    public void malformedJsonReadsAsEmptyStore() throws Exception {
        File bin = tempFolder.newFolder("malformed");
        Files.write(new File(bin, ClicksConfigIO.FILE_NAME).toPath(),
                "{not-json".getBytes(StandardCharsets.UTF_8));

        ClickStore store = ClicksConfigIO.read(bin);

        assertEquals(0, store.all().size());
    }

    private static ChannelConfig oneChannelConfig() {
        ChannelConfig cfg = new ChannelConfig();
        ChannelConfig.Channel channel = new ChannelConfig.Channel();
        channel.index = 0;
        channel.name = "DAPI";
        channel.color = "Blue";
        channel.threshold = "default";
        channel.size = "100-Infinity";
        channel.minmax = "None";
        channel.intensityThreshold = "default";
        channel.segmentationMethod = "classical";
        channel.filterPreset = "Default";
        channel.status.put(ChannelConfig.P_NAME, ChannelConfig.PropertyStatus.COMMITTED);
        channel.status.put(ChannelConfig.P_COLOR, ChannelConfig.PropertyStatus.COMMITTED);
        channel.status.put(ChannelConfig.P_MARKER, ChannelConfig.PropertyStatus.COMMITTED);
        channel.status.put(ChannelConfig.P_THRESHOLD, ChannelConfig.PropertyStatus.COMMITTED);
        channel.status.put(ChannelConfig.P_SIZE, ChannelConfig.PropertyStatus.COMMITTED);
        channel.status.put(ChannelConfig.P_MINMAX, ChannelConfig.PropertyStatus.COMMITTED);
        channel.status.put(ChannelConfig.P_INTENSITY, ChannelConfig.PropertyStatus.COMMITTED);
        channel.status.put(ChannelConfig.P_SEGMENTATION, ChannelConfig.PropertyStatus.COMMITTED);
        channel.status.put(ChannelConfig.P_FILTER, ChannelConfig.PropertyStatus.COMMITTED);
        cfg.channels.add(channel);
        return cfg;
    }
}
