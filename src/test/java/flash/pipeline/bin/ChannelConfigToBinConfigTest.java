package flash.pipeline.bin;

import flash.pipeline.click.ClickStore;
import flash.pipeline.click.ClicksConfigIO;
import flash.pipeline.zslice.ZSliceMode;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;

import static org.junit.Assert.assertEquals;

public class ChannelConfigToBinConfigTest {
    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void pendingThresholdYieldsDefaultToken() {
        ChannelConfig cfg = new ChannelConfig();
        ChannelConfig.Channel channel = channel(0, "DAPI", "Blue", "900", "20-200", "0-255",
                "500", "classical", "Clustered Large");
        channel.status.put(ChannelConfig.P_THRESHOLD, ChannelConfig.PropertyStatus.PENDING);
        cfg.channels.add(channel);

        BinConfig bin = ChannelConfigIO.toBinConfig(cfg);

        assertEquals("default", bin.channelThresholds.get(0));
        assertEquals("20-200", bin.channelSizes.get(0));
    }

    @Test
    public void pendingSegmentationYieldsClassicalOtsuToken() {
        ChannelConfig cfg = new ChannelConfig();
        ChannelConfig.Channel channel = channel(0, "DAPI", "Blue", "900", "20-200", "0-255",
                "500", "stardist:0.5:0.4", "Clustered Large");
        channel.status.put(ChannelConfig.P_SEGMENTATION, ChannelConfig.PropertyStatus.PENDING);
        cfg.channels.add(channel);

        assertEquals("classical:otsu", ChannelConfigIO.toBinConfig(cfg).segmentationMethods.get(0));
    }

    @Test
    public void extrasOnChannelAreIgnoredByToBinConfig() {
        ChannelConfig cfg = new ChannelConfig();
        ChannelConfig.Channel channel = channel(0, "DAPI", "Blue", "default", "100-Infinity",
                "None", "default", "classical", "Default");
        channel.extras.put("future", "ignored");
        cfg.channels.add(channel);

        BinConfig bin = ChannelConfigIO.toBinConfig(cfg);

        assertEquals(1, bin.numChannels());
        assertEquals("DAPI", bin.channelNames.get(0));
    }

    @Test
    public void clickCaptureRequiresClicksFileWhenReadingFromDirectory() throws Exception {
        File settingsDir = temp.newFolder("clicks");
        ChannelConfig cfg = new ChannelConfig();
        cfg.clickCaptureUsed = true;
        cfg.channels.add(channel(0, "DAPI", "Blue", "default", "100-Infinity",
                "None", "default", "classical", "Default"));

        assertEquals(false, ChannelConfigIO.toBinConfig(cfg, settingsDir).clickConfigPresent);

        ClickStore store = new ClickStore();
        store.add(new ClickStore.Click("Image1", 1, 1, 1, 5.0, 6.0,
                ClickStore.Verdict.POSITIVE, 123L));
        ClicksConfigIO.write(settingsDir, store);

        assertEquals(true, ChannelConfigIO.toBinConfig(cfg, settingsDir).clickConfigPresent);
    }

    @Test
    public void subsetModeWithoutSelectionsFallsBackToFullStack() {
        ChannelConfig cfg = new ChannelConfig();
        cfg.zSliceMode = ZSliceMode.PER_IMAGE;
        cfg.channels.add(channel(0, "DAPI", "Blue", "default", "100-Infinity",
                "None", "default", "classical", "Default"));

        BinConfig bin = ChannelConfigIO.toBinConfig(cfg);

        assertEquals(ZSliceMode.FULL, bin.zSliceMode);
        assertEquals(false, bin.zSliceConfigPresent);
    }

    private static ChannelConfig.Channel channel(int index, String name, String color,
                                                 String threshold, String size, String minmax,
                                                 String intensity, String segmentation,
                                                 String filter) {
        ChannelConfig.Channel channel = new ChannelConfig.Channel();
        channel.index = index;
        channel.name = name;
        channel.color = color;
        channel.markerId = "";
        channel.markerShape = "";
        channel.threshold = threshold;
        channel.size = size;
        channel.minmax = minmax;
        channel.intensityThreshold = intensity;
        channel.segmentationMethod = segmentation;
        channel.filterPreset = filter;
        markCommitted(channel);
        return channel;
    }

    private static void markCommitted(ChannelConfig.Channel channel) {
        channel.status.put(ChannelConfig.P_NAME, ChannelConfig.PropertyStatus.COMMITTED);
        channel.status.put(ChannelConfig.P_COLOR, ChannelConfig.PropertyStatus.COMMITTED);
        channel.status.put(ChannelConfig.P_MARKER, ChannelConfig.PropertyStatus.COMMITTED);
        channel.status.put(ChannelConfig.P_THRESHOLD, ChannelConfig.PropertyStatus.COMMITTED);
        channel.status.put(ChannelConfig.P_SIZE, ChannelConfig.PropertyStatus.COMMITTED);
        channel.status.put(ChannelConfig.P_MINMAX, ChannelConfig.PropertyStatus.COMMITTED);
        channel.status.put(ChannelConfig.P_INTENSITY, ChannelConfig.PropertyStatus.COMMITTED);
        channel.status.put(ChannelConfig.P_SEGMENTATION, ChannelConfig.PropertyStatus.COMMITTED);
        channel.status.put(ChannelConfig.P_FILTER, ChannelConfig.PropertyStatus.COMMITTED);
    }
}
