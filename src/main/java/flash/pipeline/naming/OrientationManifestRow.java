package flash.pipeline.naming;

import java.util.Locale;

/**
 * One saved image-orientation decision from Image Orientation.csv.
 */
public final class OrientationManifestRow {

    public final String imageKey;
    public final String sourceFile;
    public final int seriesIndex;
    public final String originalName;
    public final String displayName;
    public final String animalName;
    public final Hemisphere hemisphere;
    public final String region;
    public final RotationDegrees rotateDegrees;
    public final boolean flipHorizontal;
    public final boolean flipVertical;
    public final ViewPolicy viewPolicy;
    public final DecisionSource decisionSource;
    public final ConfirmationState confirmed;
    public final String notes;

    public OrientationManifestRow(String imageKey,
                                  String sourceFile,
                                  int seriesIndex,
                                  String originalName,
                                  String displayName,
                                  String animalName,
                                  Hemisphere hemisphere,
                                  String region,
                                  RotationDegrees rotateDegrees,
                                  boolean flipHorizontal,
                                  boolean flipVertical,
                                  ViewPolicy viewPolicy,
                                  DecisionSource decisionSource,
                                  ConfirmationState confirmed,
                                  String notes) {
        this.imageKey = trimToEmpty(imageKey);
        this.sourceFile = trimToEmpty(sourceFile);
        this.seriesIndex = seriesIndex < 1 ? 1 : seriesIndex;
        this.originalName = trimToEmpty(originalName);
        this.displayName = trimToEmpty(displayName);
        this.animalName = trimToEmpty(animalName);
        this.hemisphere = hemisphere == null ? Hemisphere.UNKNOWN : hemisphere;
        this.region = trimToEmpty(region);
        this.rotateDegrees = rotateDegrees == null ? RotationDegrees.DEG_0 : rotateDegrees;
        this.flipHorizontal = flipHorizontal;
        this.flipVertical = flipVertical;
        this.viewPolicy = viewPolicy == null ? ViewPolicy.MANUAL_ONLY : viewPolicy;
        this.decisionSource = decisionSource == null ? DecisionSource.UNKNOWN : decisionSource;
        this.confirmed = confirmed == null ? ConfirmationState.NO : confirmed;
        this.notes = trimToEmpty(notes);
    }

    public boolean isConfirmed() {
        return confirmed == ConfirmationState.YES;
    }

    public static String buildImageKey(String sourceKind,
                                       String sourceFile,
                                       int seriesIndex,
                                       String originalName) {
        return trimToEmpty(sourceKind)
                + "|" + trimToEmpty(sourceFile)
                + "|" + (seriesIndex < 1 ? 1 : seriesIndex)
                + "|" + trimToEmpty(originalName);
    }

    public enum Hemisphere {
        LH("LH"),
        RH("RH"),
        UNKNOWN("Unknown");

        private final String csvValue;

        Hemisphere(String csvValue) {
            this.csvValue = csvValue;
        }

        public String toCsv() {
            return csvValue;
        }

        public static Hemisphere fromCsv(String value) {
            String token = normalized(value);
            if ("LH".equals(token)) return LH;
            if ("RH".equals(token)) return RH;
            return UNKNOWN;
        }
    }

    public enum RotationDegrees {
        DEG_0(0),
        DEG_90(90),
        DEG_180(180),
        DEG_270(270);

        private final int degrees;

        RotationDegrees(int degrees) {
            this.degrees = degrees;
        }

        public int degrees() {
            return degrees;
        }

        public String toCsv() {
            return String.valueOf(degrees);
        }

        public static RotationDegrees fromCsv(String value) {
            try {
                return fromDegrees(Integer.parseInt(trimToEmpty(value)));
            } catch (NumberFormatException e) {
                return DEG_0;
            }
        }

        public static RotationDegrees fromDegrees(int degrees) {
            if (degrees == 90) return DEG_90;
            if (degrees == 180) return DEG_180;
            if (degrees == 270) return DEG_270;
            return DEG_0;
        }
    }

    public enum ViewPolicy {
        KEEP_AS_ACQUIRED("KeepAsAcquired"),
        STANDARDIZE_TO_LEFT("StandardizeToLeft"),
        STANDARDIZE_TO_RIGHT("StandardizeToRight"),
        MANUAL_ONLY("ManualOnly");

        private final String csvValue;

        ViewPolicy(String csvValue) {
            this.csvValue = csvValue;
        }

        public String toCsv() {
            return csvValue;
        }

        public static ViewPolicy fromCsv(String value) {
            String token = normalized(value);
            if ("KEEPASACQUIRED".equals(token)) return KEEP_AS_ACQUIRED;
            if ("STANDARDIZETOLEFT".equals(token)) return STANDARDIZE_TO_LEFT;
            if ("STANDARDIZETORIGHT".equals(token)) return STANDARDIZE_TO_RIGHT;
            if ("MANUALONLY".equals(token)) return MANUAL_ONLY;
            return MANUAL_ONLY;
        }
    }

    public enum DecisionSource {
        SAVED_MANIFEST("SavedManifest"),
        STRICT_FILENAME("StrictFilename"),
        FILENAME_ALIAS("FilenameAlias"),
        FOLDER_ALIAS("FolderAlias"),
        MANUAL("Manual"),
        UNKNOWN("Unknown");

        private final String csvValue;

        DecisionSource(String csvValue) {
            this.csvValue = csvValue;
        }

        public String toCsv() {
            return csvValue;
        }

        public static DecisionSource fromCsv(String value) {
            String token = normalized(value);
            if ("SAVEDMANIFEST".equals(token)) return SAVED_MANIFEST;
            if ("STRICTFILENAME".equals(token)) return STRICT_FILENAME;
            if ("FILENAMEALIAS".equals(token)) return FILENAME_ALIAS;
            if ("FOLDERALIAS".equals(token)) return FOLDER_ALIAS;
            if ("MANUAL".equals(token)) return MANUAL;
            return UNKNOWN;
        }
    }

    public enum ConfirmationState {
        YES("Yes"),
        NO("No");

        private final String csvValue;

        ConfirmationState(String csvValue) {
            this.csvValue = csvValue;
        }

        public String toCsv() {
            return csvValue;
        }

        public static ConfirmationState fromCsv(String value) {
            return parseYesNo(value) ? YES : NO;
        }
    }

    public static boolean parseYesNo(String value) {
        String token = normalized(value);
        return "YES".equals(token) || "TRUE".equals(token) || "1".equals(token);
    }

    public static String yesNo(boolean value) {
        return value ? "Yes" : "No";
    }

    private static String normalized(String value) {
        return trimToEmpty(value).replace("_", "").replace("-", "")
                .replace(" ", "").toUpperCase(Locale.ROOT);
    }

    private static String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }
}
