package flash.pipeline.bin;

import flash.pipeline.click.ClickStore;
import flash.pipeline.click.ClicksConfigIO;
import flash.pipeline.zslice.ZSliceMode;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

import static org.junit.Assert.assertEquals;

public class ChannelConfigToBinConfigTest {
    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void committedChannelProducesIdenticalBinConfigToLegacyWriter() throws Exception {
        ChannelConfig cfg = new ChannelConfig();
        cfg.channels.add(channel(0, "DAPI", "Blue", "default", "100-Infinity", "None",
                "default", "classical", "Default"));
        cfg.channels.add(channel(1, "Iba1 nuclear", "Green", "500", "50-5000", "0-4095",
                "750", "stardist:0.5:0.4", "Clustered Large"));
        cfg.channels.add(channel(2, "GFAP", "Magenta", "650", "60-6000", "100-3000",
                "850", "cellpose:30:0.4:0.0:gpu=true:chan2=0:model=cellpose_cyto3", "IBA1 cleanup filter"));

        BinConfig legacy = new BinConfig();
        legacy.channelNames.add("DAPI");
        legacy.channelNames.add("Iba1 nuclear");
        legacy.channelNames.add("GFAP");
        legacy.channelColors.add("Blue");
        legacy.channelColors.add("Green");
        legacy.channelColors.add("Magenta");
        legacy.channelThresholds.add("default");
        legacy.channelThresholds.add("500");
        legacy.channelThresholds.add("650");
        legacy.channelSizes.add("100-Infinity");
        legacy.channelSizes.add("50-5000");
        legacy.channelSizes.add("60-6000");
        legacy.channelMinMax.add("None");
        legacy.channelMinMax.add("0-4095");
        legacy.channelMinMax.add("100-3000");
        legacy.channelIntensityThresholds.add("default");
        legacy.channelIntensityThresholds.add("750");
        legacy.channelIntensityThresholds.add("850");
        legacy.segmentationMethods.add("classical");
        legacy.segmentationMethods.add("stardist:0.5:0.4");
        legacy.segmentationMethods.add("cellpose:30:0.4:0.0:gpu=true:chan2=0:model=cellpose_cyto3");
        legacy.channelFilterPresets.add("Default");
        legacy.channelFilterPresets.add("Clustered Large");
        legacy.channelFilterPresets.add("IBA1 cleanup filter");

        File legacyDir = temp.newFolder("legacy");
        File derivedDir = temp.newFolder("derived");
        BinConfigIO.writeFromConfig(legacyDir.getAbsolutePath(), legacy);
        BinConfigIO.writeFromConfig(derivedDir.getAbsolutePath(), ChannelConfigIO.toBinConfig(cfg));

        assertEquals(readChannelData(legacyDir), readChannelData(derivedDir));
    }

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
        ChannelConfigIORoundTripTest.markCommitted(channel);
        return channel;
    }

    private static List<String> readChannelData(File dir) throws Exception {
        return Files.readAllLines(new File(dir, "FLASH/Config/.settings/Channel_Data.txt").toPath(),
                StandardCharsets.UTF_8);
    }
}
