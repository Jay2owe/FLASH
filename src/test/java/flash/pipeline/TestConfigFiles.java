package flash.pipeline;

import flash.pipeline.bin.BinConfig;
import flash.pipeline.bin.ChannelConfig;
import flash.pipeline.bin.ChannelConfigIO;
import flash.pipeline.bin.ChannelIdentities;
import flash.pipeline.io.FlashProjectLayout;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;

public final class TestConfigFiles {
    private TestConfigFiles() {}

    public static File settingsDir(File projectRoot) {
        return FlashProjectLayout.forDirectory(projectRoot.getAbsolutePath()).configurationWriteDir();
    }

    public static File settingsDir(Path projectRoot) {
        return settingsDir(projectRoot.toFile());
    }

    public static void writeChannelConfig(File projectRoot, BinConfig cfg) throws IOException {
        ChannelConfigIO.write(settingsDir(projectRoot), ChannelConfigIO.fromBinConfig(cfg));
    }

    public static void writeChannelConfig(Path projectRoot, BinConfig cfg) throws IOException {
        writeChannelConfig(projectRoot.toFile(), cfg);
    }

    public static void writeChannelConfig(File projectRoot,
                                          BinConfig cfg,
                                          ChannelIdentities identities) throws IOException {
        ChannelConfig channelConfig = ChannelConfigIO.fromBinConfig(cfg);
        if (identities != null) {
            for (ChannelIdentities.Entry entry : identities.getEntries()) {
                int index = entry.getChannelIndex();
                if (index >= 0 && index < channelConfig.channels.size()) {
                    ChannelConfig.Channel channel = channelConfig.channels.get(index);
                    channel.markerId = entry.getMarkerId();
                    channel.markerShape = entry.getShape();
                    channel.markerCrowdingSensitive = entry.isCrowdingSensitive();
                }
            }
        }
        ChannelConfigIO.write(settingsDir(projectRoot), channelConfig);
    }

    public static void writeChannelConfig(Path projectRoot,
                                          BinConfig cfg,
                                          ChannelIdentities identities) throws IOException {
        writeChannelConfig(projectRoot.toFile(), cfg, identities);
    }

    public static BinConfig basicBinConfig(String... channelNames) {
        BinConfig cfg = new BinConfig();
        if (channelNames == null || channelNames.length == 0) {
            channelNames = new String[]{"DAPI"};
        }
        cfg.channelNames.addAll(Arrays.asList(channelNames));
        for (int i = 0; i < channelNames.length; i++) {
            cfg.channelColors.add(i == 0 ? "Blue" : "Green");
            cfg.channelThresholds.add("default");
            cfg.channelSizes.add("100-Infinity");
            cfg.channelMinMax.add("None");
            cfg.channelIntensityThresholds.add("default");
            cfg.addSegmentationMethodToken("classical");
            cfg.channelFilterPresets.add("Default");
        }
        cfg.zSliceConfigPresent = true;
        return cfg;
    }
}
