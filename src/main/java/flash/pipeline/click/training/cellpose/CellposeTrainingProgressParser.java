package flash.pipeline.click.training.cellpose;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses lightweight epoch progress emitted by Cellpose training logs.
 */
public final class CellposeTrainingProgressParser {
    private static final Pattern FLASH_EPOCH = Pattern.compile(
            "(?i).*\\bFLASH_CELLPOSE_EPOCH\\s+([0-9]+)\\s*/\\s*([0-9]+).*");
    private static final Pattern EPOCH_SLASH = Pattern.compile(
            "(?i).*\\bEpoch\\s+([0-9]+)\\s*/\\s*([0-9]+).*");
    private static final Pattern EPOCH_OF = Pattern.compile(
            "(?i).*\\bEpoch\\s+([0-9]+)\\s+of\\s+([0-9]+).*");
    private static final Pattern CELLPOSE_EPOCH_SLASH = Pattern.compile(
            "(?i).*\\bcellpose\\b.*\\b([0-9]+)\\s*/\\s*([0-9]+).*\\bepochs?\\b.*");

    private CellposeTrainingProgressParser() {
    }

    public static Progress parse(String line) {
        String text = line == null ? "" : line.trim();
        Progress progress = parseWith(FLASH_EPOCH, text);
        if (progress != null) return progress;
        progress = parseWith(EPOCH_SLASH, text);
        if (progress != null) return progress;
        progress = parseWith(EPOCH_OF, text);
        if (progress != null) return progress;
        return parseWith(CELLPOSE_EPOCH_SLASH, text);
    }

    private static Progress parseWith(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        if (!matcher.matches()) {
            return null;
        }
        int epoch;
        int total;
        try {
            epoch = Integer.parseInt(matcher.group(1));
            total = Integer.parseInt(matcher.group(2));
        } catch (NumberFormatException e) {
            return null;
        }
        if (total <= 0 || epoch < 0) {
            return null;
        }
        int clampedEpoch = Math.min(epoch, total);
        return new Progress(clampedEpoch, total,
                clampedEpoch / (double) total,
                "Cellpose epoch " + clampedEpoch + "/" + total);
    }

    public static final class Progress {
        public final int epoch;
        public final int totalEpochs;
        public final double fraction;
        public final String message;

        Progress(int epoch, int totalEpochs, double fraction, String message) {
            this.epoch = epoch;
            this.totalEpochs = totalEpochs;
            this.fraction = fraction;
            this.message = message == null ? "" : message;
        }
    }
}
