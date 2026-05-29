package flash.pipeline.naming;

import flash.pipeline.io.OrientationManifestIO;

import java.util.List;
import java.util.Optional;

/**
 * Resolves image orientation metadata from the optional manifest first, then
 * falls back to the existing filename convention.
 */
public final class ImageOrientationResolver {

    private ImageOrientationResolver() {}

    public static ResolvedImageMetadata resolve(String directory,
                                                String imageTitle,
                                                int seriesIndex) {
        Optional<OrientationManifestRow> manifestRow =
                findConfirmed(directory, imageTitle, seriesIndex);
        if (manifestRow.isPresent()) {
            return ResolvedImageMetadata.fromManifest(manifestRow.get());
        }

        NameParts parts = ImageNameParser.parse(imageTitle);
        ResolvedImageMetadata.Source source = parts.strictMatch
                ? ResolvedImageMetadata.Source.STRICT_FILENAME
                : ResolvedImageMetadata.Source.FILENAME_FALLBACK;
        return ResolvedImageMetadata.fromNameParts(parts, source);
    }

    public static Optional<OrientationManifestRow> findConfirmed(String directory,
                                                                 String imageTitle,
                                                                 int seriesIndex) {
        if (directory == null || directory.trim().isEmpty()) {
            return Optional.empty();
        }

        List<OrientationManifestRow> rows = OrientationManifestIO.readIfExists(directory);
        int normalizedSeriesIndex = normalizeSeriesIndex(seriesIndex);
        for (OrientationManifestRow row : rows) {
            if (isUsable(row) && matches(row, imageTitle, normalizedSeriesIndex)) {
                return Optional.of(row);
            }
        }
        return Optional.empty();
    }

    private static boolean isUsable(OrientationManifestRow row) {
        return row != null
                && row.isConfirmed()
                && !row.animalName.isEmpty()
                && (row.hemisphere == OrientationManifestRow.Hemisphere.LH
                    || row.hemisphere == OrientationManifestRow.Hemisphere.RH
                    || hasManualTransform(row));
    }

    private static boolean hasManualTransform(OrientationManifestRow row) {
        return row.rotateDegrees != OrientationManifestRow.RotationDegrees.DEG_0
                || row.flipHorizontal
                || row.flipVertical;
    }

    private static boolean matches(OrientationManifestRow row,
                                   String imageTitle,
                                   int seriesIndex) {
        if (row.seriesIndex != seriesIndex) return false;
        String title = trimToEmpty(imageTitle);
        if (title.isEmpty()) return false;

        if (title.equals(row.originalName)) return true;
        if (title.equals(row.displayName)) return true;
        if (title.equals(row.sourceFile)) return true;

        String keySuffix = "|" + seriesIndex + "|" + title;
        return row.imageKey.endsWith(keySuffix);
    }

    private static int normalizeSeriesIndex(int seriesIndex) {
        return seriesIndex < 1 ? 1 : seriesIndex;
    }

    private static String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }
}
