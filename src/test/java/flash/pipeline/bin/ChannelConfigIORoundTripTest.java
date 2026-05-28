package flash.pipeline.bin;

import flash.pipeline.zslice.ZSliceMode;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class ChannelConfigIORoundTripTest {
    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void writeReadRoundTripPreservesAllFields() throws Exception {
        File settingsDir = temp.newFolder("settings");
        ChannelConfig cfg = committedConfig("DAPI", "Blue");
        cfg.writerId = "test-writer";
        cfg.writtenAtMillis = 123L;
        cfg.zSliceMode = ZSliceMode.SAME_COUNT;
        cfg.clickCaptureUsed = true;
        cfg.extras.put("futureRoot", "keep");
        cfg.channels.get(0).extras.put("futureChannel", "keep");

        ChannelConfigIO.write(settingsDir, cfg);
        ChannelConfig back = ChannelConfigIO.read(settingsDir);

        assertEquals("test-writer", back.writerId);
        assertEquals(123L, back.writtenAtMillis);
        assertEquals(ZSliceMode.SAME_COUNT, back.zSliceMode);
        assertTrue(back.clickCaptureUsed);
        assertEquals("DAPI", back.channels.get(0).name);
        assertEquals("Blue", back.channels.get(0).color);
        assertEquals(ChannelConfig.PropertyStatus.COMMITTED,
                back.channels.get(0).statusOf(ChannelConfig.P_THRESHOLD));
        assertEquals("keep", back.extras.get("futureRoot"));
        assertEquals("keep", back.channels.get(0).extras.get("futureChannel"));
    }

    @Test
    public void writeIsAtomicTmpFileDoesNotRemain() throws Exception {
        File settingsDir = temp.newFolder("atomic");

        ChannelConfigIO.write(settingsDir, committedConfig("IBA1", "Green"));

        assertTrue(new File(settingsDir, ChannelConfigIO.FILE_NAME).isFile());
        assertFalse(new File(settingsDir, ChannelConfigIO.FILE_NAME + ".tmp").exists());
    }

    @Test
    public void readMissingReturnsNull() throws Exception {
        assertNull(ChannelConfigIO.read(temp.newFolder("missing")));
    }

    @Test
    public void readCorruptReturnsNullWithoutThrowing() throws Exception {
        File settingsDir = temp.newFolder("corrupt");
        Files.write(new File(settingsDir, ChannelConfigIO.FILE_NAME).toPath(),
                "{not json".getBytes(StandardCharsets.UTF_8));

        assertNull(ChannelConfigIO.read(settingsDir));
    }

    static ChannelConfig committedConfig(String name, String color) {
        ChannelConfig cfg = new ChannelConfig();
        ChannelConfig.Channel channel = new ChannelConfig.Channel();
        channel.index = 0;
        channel.name = name;
        channel.color = color;
        channel.markerId = "";
        channel.markerShape = "";
        channel.threshold = "default";
        channel.size = "100-Infinity";
        channel.minmax = "None";
        channel.intensityThreshold = "default";
        channel.segmentationMethod = "classical";
        channel.filterPreset = "Default";
        markCommitted(channel);
        cfg.channels.add(channel);
        return cfg;
    }

    static void markCommitted(ChannelConfig.Channel channel) {
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
