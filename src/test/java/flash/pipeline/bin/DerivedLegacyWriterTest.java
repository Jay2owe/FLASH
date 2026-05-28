package flash.pipeline.bin;

import flash.pipeline.zslice.ZSliceMode;
import flash.pipeline.zslice.ZSliceRange;
import flash.pipeline.zslice.ZSliceConfigIO;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DerivedLegacyWriterTest {
    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void writingFullyCommittedConfigProducesIdenticalChannelDataAsLegacyPath() throws Exception {
        File legacyProject = temp.newFolder("legacy");
        File jsonProject = temp.newFolder("json");
        BinConfig legacy = twoChannelBinConfig();
        ChannelConfig cfg = twoChannelConfig();

        BinConfigIO.writeFromConfig(legacyProject.getAbsolutePath(), legacy);
        ChannelConfigIO.writeWithDerivedLegacy(settingsDir(jsonProject), cfg);

        assertEquals(readChannelData(legacyProject), readChannelData(jsonProject));
    }

    @Test
    public void writingPartialConfigOmitsChannelDataIfNoChannelComplete() throws Exception {
        File project = temp.newFolder("partial");
        ChannelConfig cfg = twoChannelConfig();
        for (ChannelConfig.Channel channel : cfg.channels) {
            channel.status.clear();
            channel.status.put(ChannelConfig.P_NAME, ChannelConfig.PropertyStatus.CONFIGURED);
            channel.status.put(ChannelConfig.P_COLOR, ChannelConfig.PropertyStatus.CONFIGURED);
        }

        ChannelConfigIO.writeWithDerivedLegacy(settingsDir(project), cfg);

        assertTrue(new File(settingsDir(project), ChannelConfigIO.FILE_NAME).isFile());
        assertFalse(channelData(project).exists());
    }

    @Test
    public void derivedWriteIsAtomicChannelDataTmpDoesNotRemain() throws Exception {
        File project = temp.newFolder("atomic");

        ChannelConfigIO.writeWithDerivedLegacy(settingsDir(project), twoChannelConfig());

        assertTrue(channelData(project).isFile());
        assertFalse(new File(settingsDir(project), "Channel_Data.txt.tmp").exists());
    }

    @Test
    public void zsliceSidecarOnlyWrittenWhenSelectionsNonEmpty() throws Exception {
        File project = temp.newFolder("zslice");
        ChannelConfig cfg = twoChannelConfig();

        ChannelConfigIO.writeWithDerivedLegacy(settingsDir(project), cfg);
        assertFalse(new File(settingsDir(project), ZSliceConfigIO.FILE_NAME).exists());

        cfg.zSliceMode = ZSliceMode.PER_IMAGE;
        cfg.zSliceSelections.put("0", new ZSliceRange(3, 8));
        ChannelConfigIO.writeWithDerivedLegacy(settingsDir(project), cfg);

        assertTrue(new File(settingsDir(project), ZSliceConfigIO.FILE_NAME).isFile());
    }

    @Test
    public void channelIdentitiesSidecarOnlyWrittenWhenMarkersPresent() throws Exception {
        File project = temp.newFolder("identities");
        ChannelConfig cfg = twoChannelConfig();

        ChannelConfigIO.writeWithDerivedLegacy(settingsDir(project), cfg);
        assertFalse(new File(settingsDir(project), ChannelIdentitiesIO.FILE_NAME).exists());

        cfg.channels.get(1).markerId = "microglia_iba1";
        cfg.channels.get(1).markerShape = "complex";
        cfg.channels.get(1).markerCrowdingSensitive = true;
        ChannelConfigIO.writeWithDerivedLegacy(settingsDir(project), cfg);

        ChannelIdentities identities = ChannelIdentitiesIO.read(settingsDir(project));
        assertEquals("microglia_iba1", identities.findByChannelIndex(1).getMarkerId());
        assertTrue(new File(settingsDir(project), ChannelIdentitiesIO.FILE_NAME).isFile());
    }

    @Test
    public void legacyWriteThenJsonWriteThenJsonWriteRoundtrip() throws Exception {
        File project = temp.newFolder("roundtrip");
        BinConfigIO.writeFromConfig(project.getAbsolutePath(), twoChannelBinConfig());
        String legacyBytes = readChannelData(project);

        ChannelConfigIO.writeWithDerivedLegacy(settingsDir(project), twoChannelConfig());
        ChannelConfigIO.writeWithDerivedLegacy(settingsDir(project), twoChannelConfig());

        assertEquals(legacyBytes, readChannelData(project));
        BinConfig readBack = BinConfigIO.readFromDirectory(project.getAbsolutePath());
        assertEquals(Arrays.asList("DAPI", "Iba1 nuclear"), readBack.channelNames);
        assertEquals(Arrays.asList("Default", "Clustered Large"), readBack.channelFilterPresets);
    }

    private static ChannelConfig twoChannelConfig() {
        ChannelConfig cfg = new ChannelConfig();
        cfg.writerId = "test";
        cfg.writtenAtMillis = 123L;
        cfg.channels.add(channel(0, "DAPI", "Blue", "default", "100-Infinity",
                "None", "default", "classical", "Default"));
        cfg.channels.add(channel(1, "Iba1 nuclear", "Green", "500", "50-5000",
                "0-4095", "750", "stardist:0.5:0.4", "Clustered Large"));
        return cfg;
    }

    private static ChannelConfig.Channel channel(int index, String name, String color,
                                                 String threshold, String size, String minmax,
                                                 String intensity, String segmentation,
                                                 String filter) {
        ChannelConfig.Channel channel = new ChannelConfig.Channel();
        channel.index = index;
        channel.name = name;
        channel.color = color;
        channel.threshold = threshold;
        channel.size = size;
        channel.minmax = minmax;
        channel.intensityThreshold = intensity;
        channel.segmentationMethod = segmentation;
        channel.filterPreset = filter;
        ChannelConfigIORoundTripTest.markCommitted(channel);
        return channel;
    }

    private static BinConfig twoChannelBinConfig() {
        BinConfig cfg = new BinConfig();
        cfg.channelNames.addAll(Arrays.asList("DAPI", "Iba1 nuclear"));
        cfg.channelColors.addAll(Arrays.asList("Blue", "Green"));
        cfg.channelThresholds.addAll(Arrays.asList("default", "500"));
        cfg.channelSizes.addAll(Arrays.asList("100-Infinity", "50-5000"));
        cfg.channelMinMax.addAll(Arrays.asList("None", "0-4095"));
        cfg.channelIntensityThresholds.addAll(Arrays.asList("default", "750"));
        cfg.segmentationMethods.addAll(Arrays.asList("classical", "stardist:0.5:0.4"));
        cfg.channelFilterPresets.addAll(Arrays.asList("Default", "Clustered Large"));
        cfg.zSliceMode = ZSliceMode.FULL;
        return cfg;
    }

    private static File settingsDir(File project) {
        return new File(project, "FLASH/Config/.settings");
    }

    private static File channelData(File project) {
        return new File(settingsDir(project), "Channel_Data.txt");
    }

    private static String readChannelData(File project) throws Exception {
        return new String(Files.readAllBytes(channelData(project).toPath()), StandardCharsets.UTF_8);
    }
}
