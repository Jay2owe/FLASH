package flash.pipeline.project;

import java.util.Locale;

/**
 * Locates and edits the per-image "region" slot inside a {@link ProjectFile}, keyed by the
 * same durable image identity (source-file basename + within-file series index) that
 * {@code OrientationImageIdentity} produces. Used by the Draw ROIs image picker to write a
 * corrected anatomical region back to {@code project.json}.
 *
 * <p>Matching rules (deliberately conservative — never guess, to avoid mis-assigning a region
 * to the wrong series in a multi-file project):
 * <ul>
 *   <li>Match the {@link ProjectFile.Item} whose path basename equals the source-file basename
 *       (case-insensitive). If two items share a basename, the match is ambiguous and
 *       {@link #locate} returns {@code null} (no write).</li>
 *   <li>If that item has expanded {@link ProjectFile.SeriesItem}s, the region lives on the
 *       <em>position</em>-th included series (in project order), where position =
 *       {@code oneBasedSeriesIndex - 1}. {@code oneBasedSeriesIndex} is the global enumeration
 *       index produced by the identity layer (matching {@link ProjectMetadataSeeder}'s walk),
 *       NOT the Bio-Formats container index — these differ for a partially-included container.
 *       If the position is out of range, return {@code null} rather than guess.</li>
 *   <li>Otherwise (single-series TIF or an unexpanded container) the region lives on the Item.</li>
 * </ul>
 *
 * <p>The identity layer throws for projects with more than one container file, so the
 * series-position branch is only reached for a single container, where the global enumeration
 * index equals the within-item position.
 */
public final class ProjectRegionEditor {

    private ProjectRegionEditor() {
    }

    /** A located region slot: either a {@link ProjectFile.SeriesItem} or, failing that, an Item. */
    public static final class Target {
        public final ProjectFile.Item item;
        public final ProjectFile.SeriesItem series; // null => region lives on the item

        Target(ProjectFile.Item item, ProjectFile.SeriesItem series) {
            this.item = item;
            this.series = series;
        }

        public String region() {
            return series != null ? series.region : item.region;
        }

        public void setRegion(String region) {
            if (series != null) {
                series.region = region;
            } else {
                item.region = region;
            }
        }
    }

    /**
     * @param sourceFileBasename source file name as produced by the identity layer (may carry a
     *                           folder prefix such as {@code "input/img.tif"} — only the basename
     *                           is used)
     * @param oneBasedSeriesIndex 1-based series index within its container
     * @return the region slot, or {@code null} when no unambiguous match exists
     */
    public static Target locate(ProjectFile project, String sourceFileBasename, int oneBasedSeriesIndex) {
        if (project == null || project.items == null || sourceFileBasename == null) {
            return null;
        }
        String wanted = basename(sourceFileBasename);
        if (wanted.isEmpty()) {
            return null;
        }
        ProjectFile.Item match = null;
        for (ProjectFile.Item item : project.items) {
            if (item == null || item.path == null) continue;
            if (basename(item.path).equalsIgnoreCase(wanted)) {
                if (match != null) {
                    return null; // ambiguous basename across items -> safe no-op
                }
                match = item;
            }
        }
        if (match == null) {
            return null;
        }
        if (match.seriesMeta != null && !match.seriesMeta.isEmpty()) {
            // Map the global enumeration index to the position-th INCLUDED series (project order),
            // mirroring ProjectMetadataSeeder so it stays correct when the container is partially
            // included (Bio-Formats index != enumeration position).
            java.util.List<ProjectFile.SeriesItem> ordered =
                    ProjectMetadataSeeder.includedSeriesInProjectOrder(match);
            int position = oneBasedSeriesIndex - 1;
            if (position >= 0 && position < ordered.size() && ordered.get(position) != null) {
                return new Target(match, ordered.get(position));
            }
            return null; // out of range -> do not guess
        }
        return new Target(match, null);
    }

    static String basename(String path) {
        if (path == null) return "";
        String normalized = path.replace('\\', '/').trim();
        int slash = normalized.lastIndexOf('/');
        return slash >= 0 ? normalized.substring(slash + 1) : normalized;
    }

    /** Case-insensitive, trimmed region equality (treats null as empty). */
    public static boolean sameRegion(String a, String b) {
        String x = a == null ? "" : a.trim();
        String y = b == null ? "" : b.trim();
        return x.toLowerCase(Locale.ROOT).equals(y.toLowerCase(Locale.ROOT));
    }
}
