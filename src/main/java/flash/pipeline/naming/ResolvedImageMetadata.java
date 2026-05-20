package flash.pipeline.naming;

/**
 * Orientation metadata resolved for downstream analysis.
 */
public final class ResolvedImageMetadata {

    public final String imageKey;
    public final String originalName;
    public final String displayName;
    public final String animalName;
    public final String hemisphere;
    public final String region;
    public final OrientationManifestRow.RotationDegrees rotateDegrees;
    public final boolean flipHorizontal;
    public final boolean flipVertical;
    public final OrientationManifestRow.ViewPolicy viewPolicy;
    public final Source source;

    public ResolvedImageMetadata(String imageKey,
                                 String originalName,
                                 String displayName,
                                 String animalName,
                                 String hemisphere,
                                 String region,
                                 OrientationManifestRow.RotationDegrees rotateDegrees,
                                 boolean flipHorizontal,
                                 boolean flipVertical,
                                 OrientationManifestRow.ViewPolicy viewPolicy,
                                 Source source) {
        this.imageKey = trimToEmpty(imageKey);
        this.originalName = trimToEmpty(originalName);
        this.displayName = trimToEmpty(displayName);
        this.animalName = trimToEmpty(animalName);
        this.hemisphere = trimToEmpty(hemisphere);
        this.region = trimToEmpty(region);
        this.rotateDegrees = rotateDegrees == null
                ? OrientationManifestRow.RotationDegrees.DEG_0 : rotateDegrees;
        this.flipHorizontal = flipHorizontal;
        this.flipVertical = flipVertical;
        this.viewPolicy = viewPolicy == null
                ? OrientationManifestRow.ViewPolicy.MANUAL_ONLY : viewPolicy;
        this.source = source == null ? Source.FILENAME_FALLBACK : source;
    }

    public static ResolvedImageMetadata fromManifest(OrientationManifestRow row) {
        if (row == null) {
            return fromNameParts(new NameParts("", "", "", "", false), Source.FILENAME_FALLBACK);
        }
        return new ResolvedImageMetadata(
                row.imageKey,
                row.originalName,
                row.displayName,
                row.animalName,
                row.hemisphere == OrientationManifestRow.Hemisphere.UNKNOWN
                        ? "" : row.hemisphere.toCsv(),
                row.region,
                row.rotateDegrees,
                row.flipHorizontal,
                row.flipVertical,
                row.viewPolicy,
                Source.SAVED_MANIFEST);
    }

    public static ResolvedImageMetadata fromNameParts(NameParts parts, Source source) {
        NameParts safe = parts == null ? new NameParts("", "", "", "", false) : parts;
        boolean legacyOrientation = source == Source.STRICT_FILENAME && safe.hasKnownHemisphere();
        return new ResolvedImageMetadata(
                "",
                "",
                safe.displayLabel(),
                safe.animal,
                safe.hemisphere,
                safe.region,
                legacyOrientation
                        ? OrientationManifestRow.RotationDegrees.DEG_270
                        : OrientationManifestRow.RotationDegrees.DEG_0,
                legacyOrientation && "RH".equalsIgnoreCase(safe.hemisphere),
                false,
                OrientationManifestRow.ViewPolicy.MANUAL_ONLY,
                source);
    }

    public boolean hasKnownHemisphere() {
        return "LH".equalsIgnoreCase(hemisphere) || "RH".equalsIgnoreCase(hemisphere);
    }

    public NameParts toNameParts() {
        boolean strict = source == Source.SAVED_MANIFEST || source == Source.STRICT_FILENAME;
        return new NameParts("", animalName, hemisphere, region, strict, displayName);
    }

    public boolean hasTransform() {
        return rotateDegrees != OrientationManifestRow.RotationDegrees.DEG_0
                || flipHorizontal
                || flipVertical
                || (viewPolicy == OrientationManifestRow.ViewPolicy.STANDARDIZE_TO_LEFT
                    && "RH".equalsIgnoreCase(hemisphere))
                || (viewPolicy == OrientationManifestRow.ViewPolicy.STANDARDIZE_TO_RIGHT
                    && "LH".equalsIgnoreCase(hemisphere));
    }

    public String sourceLabel() {
        if (source == Source.SAVED_MANIFEST) return "saved manifest";
        if (source == Source.STRICT_FILENAME) return "strict filename";
        return "no-orientation fallback";
    }

    public enum Source {
        SAVED_MANIFEST,
        STRICT_FILENAME,
        FILENAME_FALLBACK
    }

    private static String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }
}
