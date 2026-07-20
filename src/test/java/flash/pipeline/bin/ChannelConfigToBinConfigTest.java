package flash.pipeline.bin;

import flash.pipeline.click.ClickStore;
import flash.pipeline.click.ClicksConfigIO;
import flash.pipeline.zslice.ZSliceMode;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

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
    public void subsetModeWithoutSelectionsStaysExplicitAndRequiresReview() {
        ChannelConfig cfg = new ChannelConfig();
        cfg.zSliceMode = ZSliceMode.PER_IMAGE;
        cfg.channels.add(channel(0, "DAPI", "Blue", "default", "100-Infinity",
                "None", "default", "classical", "Default"));

        BinConfig bin = ChannelConfigIO.toBinConfig(cfg);

        assertEquals(ZSliceMode.PER_IMAGE, bin.zSliceMode);
        assertTrue(bin.zSliceConfigPresent);
        assertTrue(bin.zSliceSelections.isEmpty());
        ChannelConfigIO.ValidationResult validation = ChannelConfigIO.validateForCompletion(cfg);
        assertFalse(validation.isValid());
        assertTrue(validation.diagnostic().contains("zSliceSelections' is empty"));
    }

    @Test
    public void asymmetricPendingFirstChannelKeepsSecondChannelAtPositionTwo() {
        ChannelConfig cfg = new ChannelConfig();
        ChannelConfig.Channel first = channel(0, "DAPI", "Blue", "111", "10-100",
                "0-255", "222", "classical", "Default");
        ChannelConfig.Channel second = channel(1, "IBA1", "Green", "999", "20-200",
                "10-500", "888", "classical", "Clustered Large");
        first.status.put(ChannelConfig.P_THRESHOLD, ChannelConfig.PropertyStatus.PENDING);
        first.status.put(ChannelConfig.P_FILTER, ChannelConfig.PropertyStatus.PENDING);
        first.status.put(ChannelConfig.P_MARKER, ChannelConfig.PropertyStatus.PENDING);
        cfg.channels.add(first);
        cfg.channels.add(second);

        BinConfig partial = ChannelConfigIO.toPartialBinConfig(cfg, temp.getRoot());

        assertEquals(2, partial.channelThresholds.size());
        assertEquals("111", partial.channelThresholds.get(0));
        assertEquals("999", partial.channelThresholds.get(1));
        assertEquals(2, partial.channelFilterPresets.size());
        assertEquals("Default", partial.channelFilterPresets.get(0));
        assertEquals("Clustered Large", partial.channelFilterPresets.get(1));
        assertEquals(ChannelConfig.PropertyStatus.PENDING,
                partial.channelPropertyStatus(0, ChannelConfig.P_THRESHOLD));
        assertEquals(ChannelConfig.PropertyStatus.COMMITTED,
                partial.channelPropertyStatus(1, ChannelConfig.P_THRESHOLD));
        assertTrue(partial.hasChannelNames());
        assertFalse(partial.hasChannelThresholds());
        assertFalse(partial.hasChannelFilterPresets());

        ChannelConfig roundTrip = ChannelConfigIO.fromBinConfig(partial);
        assertEquals("DAPI", roundTrip.channels.get(0).name);
        assertEquals("IBA1", roundTrip.channels.get(1).name);
        assertEquals("111", roundTrip.channels.get(0).threshold);
        assertEquals("999", roundTrip.channels.get(1).threshold);
        assertEquals("Default", roundTrip.channels.get(0).filterPreset);
        assertEquals("Clustered Large", roundTrip.channels.get(1).filterPreset);
        assertEquals(ChannelConfig.PropertyStatus.PENDING,
                roundTrip.channels.get(0).statusOf(ChannelConfig.P_THRESHOLD));
        assertEquals(ChannelConfig.PropertyStatus.COMMITTED,
                roundTrip.channels.get(1).statusOf(ChannelConfig.P_THRESHOLD));
        assertEquals(ChannelConfig.PropertyStatus.PENDING,
                roundTrip.channels.get(0).statusOf(ChannelConfig.P_MARKER));
        assertFalse(ChannelConfigIO.isComplete(roundTrip));

        BinConfig normal = ChannelConfigIO.toBinConfig(cfg);
        assertEquals(2, normal.channelNames.size());
        assertEquals("default", normal.channelThresholds.get(0));
        assertEquals("999", normal.channelThresholds.get(1));
        ChannelConfig normalRoundTrip = ChannelConfigIO.fromBinConfig(normal);
        assertEquals(ChannelConfig.PropertyStatus.PENDING,
                normalRoundTrip.channels.get(0).statusOf(ChannelConfig.P_THRESHOLD));
        assertEquals(ChannelConfig.PropertyStatus.COMMITTED,
                normalRoundTrip.channels.get(1).statusOf(ChannelConfig.P_THRESHOLD));
        assertEquals("IBA1", normalRoundTrip.channels.get(1).name);
        assertFalse(ChannelConfigIO.isComplete(normalRoundTrip));
    }

    @Test
    public void legacyCompressedFieldIsMarkedUnknownInsteadOfAssignedToChannelOne() {
        BinConfig legacy = new BinConfig();
        legacy.channelNames.add("DAPI");
        legacy.channelNames.add("IBA1");
        legacy.channelColors.add("Blue");
        legacy.channelColors.add("Green");
        legacy.channelThresholds.add("777");

        ChannelConfig migrated = ChannelConfigIO.fromBinConfig(legacy);

        assertEquals("default", migrated.channels.get(0).threshold);
        assertEquals("default", migrated.channels.get(1).threshold);
        assertEquals(ChannelConfig.PropertyStatus.PENDING,
                migrated.channels.get(0).statusOf(ChannelConfig.P_THRESHOLD));
        assertEquals(ChannelConfig.PropertyStatus.PENDING,
                migrated.channels.get(1).statusOf(ChannelConfig.P_THRESHOLD));
        assertFalse(ChannelConfigIO.isComplete(migrated));
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
