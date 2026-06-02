package flash.pipeline.atlas;

import ij.measure.ResultsTable;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Shared atlas metadata column names and output helpers. */
public final class AtlasRegionColumns {
    public static final String ATLAS_KEY = "Atlas Key";
    public static final String REGION_ID = "Region ID";
    public static final String REGION_ACRONYM = "Region Acronym";
    public static final String REGION_NAME = "Region Name";
    public static final List<String> COLUMNS = Collections.unmodifiableList(Arrays.asList(
            ATLAS_KEY,
            REGION_ID,
            REGION_ACRONYM,
            REGION_NAME));

    private AtlasRegionColumns() {
    }

    public static void writeTo(ResultsTable table, int row, String region, String roi) {
        if (table == null) return;
        Metadata metadata = metadataFor(region, roi);
        table.setValue(ATLAS_KEY, row, metadata.getAtlasKey());
        table.setValue(REGION_ID, row, metadata.getRegionId());
        table.setValue(REGION_ACRONYM, row, metadata.getRegionAcronym());
        table.setValue(REGION_NAME, row, metadata.getRegionName());
    }

    public static Metadata metadataFor(String region, String roi) {
        AtlasRegionResolver.Resolved resolved =
                AtlasRegionResolver.resolveFromRegionAndRoi(region, roi,
                        AtlasRegionLibraryIO.loadBundledQuietly());
        if (resolved != null && resolved.isResolved()) {
            AtlasRegion atlasRegion = resolved.getRegion();
            return new Metadata(
                    atlasRegion.getAtlasKey(),
                    String.valueOf(atlasRegion.getId()),
                    atlasRegion.getAcronym(),
                    atlasRegion.getName());
        }
        return Metadata.empty();
    }

    public static Metadata metadataForExistingOrResolved(String atlasKey,
                                                         String regionId,
                                                         String regionAcronym,
                                                         String regionName,
                                                         String region,
                                                         String roi) {
        Metadata resolved = metadataFor(region, roi);
        return new Metadata(
                firstNonEmpty(atlasKey, resolved.getAtlasKey()),
                firstNonEmpty(regionId, resolved.getRegionId()),
                firstNonEmpty(regionAcronym, resolved.getRegionAcronym()),
                firstNonEmpty(regionName, resolved.getRegionName()));
    }

    public static boolean isAtlasColumn(String heading) {
        return ATLAS_KEY.equals(heading)
                || REGION_ID.equals(heading)
                || REGION_ACRONYM.equals(heading)
                || REGION_NAME.equals(heading);
    }

    public static void addHeaders(List<String> header) {
        if (header == null) return;
        for (String column : COLUMNS) {
            if (!header.contains(column)) {
                header.add(column);
            }
        }
    }

    private static String firstNonEmpty(String first, String second) {
        String a = safe(first);
        return a.isEmpty() ? safe(second) : a;
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }

    public static final class Metadata {
        private static final Metadata EMPTY = new Metadata("", "", "", "");

        private final String atlasKey;
        private final String regionId;
        private final String regionAcronym;
        private final String regionName;

        private Metadata(String atlasKey, String regionId, String regionAcronym, String regionName) {
            this.atlasKey = safe(atlasKey);
            this.regionId = safe(regionId);
            this.regionAcronym = safe(regionAcronym);
            this.regionName = safe(regionName);
        }

        static Metadata empty() {
            return EMPTY;
        }

        public String getAtlasKey() {
            return atlasKey;
        }

        public String getRegionId() {
            return regionId;
        }

        public String getRegionAcronym() {
            return regionAcronym;
        }

        public String getRegionName() {
            return regionName;
        }
    }
}
