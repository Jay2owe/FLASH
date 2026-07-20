package flash.pipeline.intelligence;

import flash.pipeline.bin.BinConfig;
import flash.pipeline.bin.ChannelConfig;
import flash.pipeline.bin.ChannelConfigIO;
import flash.pipeline.io.FlashProjectLayout;

import java.io.File;
import java.util.Map;
import java.util.TreeMap;

/**
 * B-07 Configuration Structural Check.
 */
public final class BinValidator {

    private BinValidator() {}

    public static final class Result {
        public final boolean ok;
        public final boolean missing;
        public final int expectedChannels;
        public final int actualChannels;
        public final String message;
        public Result(boolean ok, boolean missing, int expected, int actual, String msg) {
            this.ok = ok;
            this.missing = missing;
            this.expectedChannels = expected;
            this.actualChannels = actual;
            this.message = msg;
        }
    }

    private static final class DirectoryChannelSummary {
        final int commonChannelCount;
        final String description;
        final boolean mixed;

        DirectoryChannelSummary(int commonChannelCount, String description, boolean mixed) {
            this.commonChannelCount = commonChannelCount;
            this.description = description == null ? "" : description;
            this.mixed = mixed;
        }
    }

    public static Result check(String directory, int expectedChannels) {
        if (directory == null) {
            return new Result(false, true, expectedChannels, 0,
                    "No directory provided.");
        }
        File settingsDir = FlashProjectLayout.forDirectory(directory).configurationWriteDir();
        ChannelConfig channelConfig = ChannelConfigIO.read(settingsDir);
        if (channelConfig == null) {
            return new Result(false, true, expectedChannels, 0,
                    "No channel_config.json in this folder.");
        }
        BinConfig cfg = ChannelConfigIO.toBinConfig(channelConfig, settingsDir);
        int actual = cfg.numChannels();
        if (actual == 0) {
            return new Result(false, false, expectedChannels, 0,
                    "Configuration has no channel names.");
        }
        String structuralError = validatePerChannelLists(cfg, actual);
        if (structuralError != null) {
            return new Result(false, false, expectedChannels, actual, structuralError);
        }
        if (expectedChannels > 0 && actual != expectedChannels) {
            return new Result(false, false, expectedChannels, actual,
                    "Your configuration has " + actual + " channel(s) but these images have "
                    + expectedChannels + ". Re-run Set Up Configuration to fix.");
        }
        return new Result(true, false, expectedChannels, actual,
                "Configuration OK (" + actual + " channels).");
    }

    public static Result checkAgainstDirectory(String directory) {
        DirectoryChannelSummary summary = scanDirectoryChannels(directory);
        if (summary.mixed) {
            return new Result(false, false, 0, 0,
                    "Images in this folder do not all have the same channel count: "
                            + summary.description + ".");
        }

        Result structural = check(directory, summary.commonChannelCount);
        if (structural.ok && summary.commonChannelCount <= 0 && !summary.description.isEmpty()) {
            return new Result(true, structural.missing, structural.expectedChannels,
                    structural.actualChannels,
                    structural.message + " " + summary.description);
        }
        return structural;
    }

    private static DirectoryChannelSummary scanDirectoryChannels(String directory) {
        java.util.List<MetadataDiagnostics.SeriesInfo> series = MetadataDiagnostics.scanDirectory(directory);
        if (series == null || series.isEmpty()) {
            return new DirectoryChannelSummary(0,
                    "Image metadata was not available, so only the configuration structure was checked.",
                    false);
        }

        Map<Integer, Integer> counts = new TreeMap<Integer, Integer>();
        for (MetadataDiagnostics.SeriesInfo info : series) {
            if (info == null || info.sizeC <= 0) continue;
            Integer seen = counts.get(info.sizeC);
            counts.put(info.sizeC, seen == null ? 1 : seen + 1);
        }
        if (counts.isEmpty()) {
            return new DirectoryChannelSummary(0,
                    "Image metadata did not report any channel counts, so only the configuration structure was checked.",
                    false);
        }
        if (counts.size() == 1) {
            Map.Entry<Integer, Integer> only = counts.entrySet().iterator().next();
            return new DirectoryChannelSummary(only.getKey(),
                    only.getValue() + " image series detected at " + only.getKey() + " channel(s)",
                    false);
        }

        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (Map.Entry<Integer, Integer> entry : counts.entrySet()) {
            if (!first) sb.append("; ");
            sb.append(entry.getValue()).append(" series at ").append(entry.getKey()).append(" channel(s)");
            first = false;
        }
        return new DirectoryChannelSummary(0, sb.toString(), true);
    }

    private static String validatePerChannelLists(BinConfig cfg, int channelCount) {
        String error = validateSize("channel colours", cfg.channelColors.size(), channelCount);
        if (error != null) return error;
        error = validateSize("object thresholds", cfg.channelThresholds.size(), channelCount);
        if (error != null) return error;
        error = validateSize("particle sizes", cfg.channelSizes.size(), channelCount);
        if (error != null) return error;
        error = validateSize("display ranges", cfg.channelMinMax.size(), channelCount);
        if (error != null) return error;
        error = validateSize("intensity thresholds", cfg.channelIntensityThresholds.size(), channelCount);
        if (error != null) return error;
        error = validateSize("segmentation methods", cfg.segmentationMethods.size(), channelCount);
        if (error != null) return error;
        return validateSize("filter presets", cfg.channelFilterPresets.size(), channelCount);
    }

    private static String validateSize(String label, int actual, int expected) {
        if (actual == expected) return null;
        return "Configuration field " + label + " has " + actual
                + " value(s) but channel names have " + expected + ".";
    }
}
