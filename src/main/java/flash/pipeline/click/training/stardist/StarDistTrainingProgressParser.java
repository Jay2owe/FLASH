package flash.pipeline.click.training.stardist;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses lightweight epoch progress emitted by StarDist/Keras training logs.
 */
public final class StarDistTrainingProgressParser {
    private static final Pattern FLASH_EPOCH = Pattern.compile(
            "(?i).*\\bFLASH_EPOCH\\s+([0-9]+)\\s*/\\s*([0-9]+).*");
    private static final Pattern KERAS_EPOCH = Pattern.compile(
            "(?i).*\\bEpoch\\s+([0-9]+)\\s*/\\s*([0-9]+).*");
    private static final Pattern EPOCH_OF = Pattern.compile(
            "(?i).*\\bEpoch\\s+([0-9]+)\\s+of\\s+([0-9]+).*");

    private StarDistTrainingProgressParser() {
    }

    public static Progress parse(String line) {
        String text = line == null ? "" : line.trim();
        Progress progress = parseWith(FLASH_EPOCH, text);
        if (progress != null) return progress;
        progress = parseWith(KERAS_EPOCH, text);
        if (progress != null) return progress;
        return parseWith(EPOCH_OF, text);
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
                "StarDist epoch " + clampedEpoch + "/" + total);
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
