package flash.pipeline.intelligence;

import flash.pipeline.io.FlashProjectLayout;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * B-07 Configuration Structural Check.
 *
 * Catches Channel_Data.txt when it doesn't line up with the current
 * image set before downstream analyses silently run with a wrong channel
 * map. Reads the file without disturbing BinConfigIO's regular load path.
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

    /**
     * Checks that Channel_Data.txt has a token count on line 1 matching
     * expectedChannels (if > 0). Passing expectedChannels <= 0 disables the
     * count check and only verifies the file's internal structure.
     */
    public static Result check(String directory, int expectedChannels) {
        if (directory == null) {
            return new Result(false, true, expectedChannels, 0,
                    "No directory provided.");
        }
        File channelData = FlashProjectLayout.forDirectory(directory).channelDataReadFile();
        if (!channelData.exists()) {
            return new Result(false, true, expectedChannels, 0,
                    "No Configuration folder in this folder.");
        }
        try {
            List<String> lines = Files.readAllLines(channelData.toPath(), StandardCharsets.UTF_8);
            if (!lines.isEmpty()) {
                String first = lines.get(0);
                if (first != null && !first.isEmpty() && first.charAt(0) == '﻿') {
                    lines.set(0, first.substring(1));
                }
            }
            if (lines.size() < 4) {
                return new Result(false, false, expectedChannels, 0,
                        "Configuration is incomplete: expected at least 4 lines, found "
                                + lines.size() + ".");
            }
            if (lines.isEmpty()) {
                return new Result(false, false, expectedChannels, 0,
                        "Configuration is empty.");
            }
            int actual = countTokens(lines.get(0));
            if (actual == 0) {
                return new Result(false, false, expectedChannels, 0,
                        "Channel names line is empty.");
            }
            String structuralError = validatePerChannelLines(lines, actual);
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
        } catch (IOException e) {
            return new Result(false, false, expectedChannels, 0,
                    "Could not read Channel_Data.txt: " + e.getMessage());
        }
    }

    /**
     * Validates the configuration against the actual image metadata in the working
     * directory. If the image set contains mixed channel counts, this returns
     * a failing result because one configuration cannot safely describe the whole cohort.
     */
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
        List<MetadataDiagnostics.SeriesInfo> series = MetadataDiagnostics.scanDirectory(directory);
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

    private static String validatePerChannelLines(List<String> lines, int channelCount) {
        LinkedHashMap<Integer, String> requiredLines = new LinkedHashMap<Integer, String>();
        requiredLines.put(1, "channel colours");
        requiredLines.put(2, "object thresholds");
        requiredLines.put(3, "particle sizes");

        for (Map.Entry<Integer, String> entry : requiredLines.entrySet()) {
            int lineIndex = entry.getKey();
            int tokens = countTokens(lines.get(lineIndex));
            if (tokens != channelCount) {
                return "Line " + (lineIndex + 1) + " (" + entry.getValue() + ") has "
                        + tokens + " value(s) but line 1 has " + channelCount + ".";
            }
        }

        LinkedHashMap<Integer, String> optionalLines = new LinkedHashMap<Integer, String>();
        optionalLines.put(4, "display ranges");
        optionalLines.put(5, "intensity thresholds");
        optionalLines.put(6, "segmentation methods");
        optionalLines.put(7, "filter presets");

        for (Map.Entry<Integer, String> entry : optionalLines.entrySet()) {
            int lineIndex = entry.getKey();
            if (lineIndex >= lines.size()) continue;
            int tokens = countTokens(lines.get(lineIndex));
            if (tokens != 0 && tokens != channelCount) {
                return "Line " + (lineIndex + 1) + " (" + entry.getValue() + ") has "
                        + tokens + " value(s) but line 1 has " + channelCount + ".";
            }
        }
        return null;
    }

    private static int countTokens(String line) {
        if (line == null) return 0;
        // Primary: tab. Falls back to any whitespace for legacy bins written
        // before the tab-delimiter migration.
        if (line.indexOf('\t') >= 0) {
            return line.split("\t", -1).length;
        }
        String trimmed = line.trim();
        if (trimmed.isEmpty()) return 0;
        return trimmed.split("\\s+").length;
    }
}
