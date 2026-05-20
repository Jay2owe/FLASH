package flash.pipeline.orientation;

import flash.pipeline.io.OrientationManifestIO;
import flash.pipeline.naming.OrientationManifestRow;
import flash.pipeline.naming.ResolvedImageMetadata;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * Upserts ROI-driven orientation decisions into Image Orientation.csv.
 */
public final class RoiOrientationManifestService {
    private final String directory;

    public RoiOrientationManifestService(String directory) {
        this.directory = trimToEmpty(directory);
    }

    public OrientationManifestRow upsertDecision(
            OrientationImageIdentity identity,
            ResolvedImageMetadata seedMetadata,
            OrientationTransformState state,
            String animalName,
            OrientationManifestRow.Hemisphere hemisphere,
            String region,
            String notes) throws IOException {
        if (identity == null) {
            throw new IllegalArgumentException("identity must not be null");
        }

        OrientationTransformState effectiveState = state == null
                ? OrientationTransformState.fromMetadata(seedMetadata)
                : state;
        String resolvedAnimalName = firstNonBlank(
                animalName,
                seedMetadata == null ? "" : seedMetadata.animalName,
                identity.displayName,
                identity.originalName);
        OrientationManifestRow.Hemisphere resolvedHemisphere =
                resolveHemisphere(hemisphere, seedMetadata);
        String resolvedRegion = firstNonBlank(
                region,
                seedMetadata == null ? "" : seedMetadata.region);

        List<OrientationManifestRow> rows = OrientationManifestIO.readIfExists(directory);
        LinkedHashMap<String, OrientationManifestRow> byKey =
                OrientationManifestIO.indexByImageKey(rows);

        OrientationManifestRow row = new OrientationManifestRow(
                identity.imageKey,
                identity.sourceFile,
                identity.seriesIndex,
                identity.originalName,
                identity.displayName,
                resolvedAnimalName,
                resolvedHemisphere,
                resolvedRegion,
                effectiveState.rotateDegrees,
                effectiveState.flipHorizontal,
                effectiveState.flipVertical,
                OrientationManifestRow.ViewPolicy.MANUAL_ONLY,
                OrientationManifestRow.DecisionSource.MANUAL,
                OrientationManifestRow.ConfirmationState.YES,
                notes);

        byKey.put(identity.imageKey, row);
        OrientationManifestIO.saveRows(
                directory,
                new ArrayList<OrientationManifestRow>(byKey.values()));
        return row;
    }

    private static OrientationManifestRow.Hemisphere resolveHemisphere(
            OrientationManifestRow.Hemisphere hemisphere,
            ResolvedImageMetadata seedMetadata) {
        if (hemisphere != null && hemisphere != OrientationManifestRow.Hemisphere.UNKNOWN) {
            return hemisphere;
        }
        if (seedMetadata == null) return OrientationManifestRow.Hemisphere.UNKNOWN;
        return OrientationManifestRow.Hemisphere.fromCsv(seedMetadata.hemisphere);
    }

    private static String firstNonBlank(String... values) {
        if (values == null) return "";
        for (String value : values) {
            String trimmed = trimToEmpty(value);
            if (!trimmed.isEmpty()) return trimmed;
        }
        return "";
    }

    private static String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }
}
