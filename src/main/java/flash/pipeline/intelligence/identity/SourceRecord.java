package flash.pipeline.intelligence.identity;

/**
 * One unit of input to identity detection: a loose file (TIFF), or one series
 * of a multi-series container (LIF/CZI/ND2). Immutable.
 *
 * <p>{@link #seed} is the best string for filename-style parsing. For a
 * container series it is the Bio-Formats-style {@code "container - seriesName"}
 * title that {@code ImageNameParser} understands; for a loose file it is the
 * file name. {@link #parentFolder} / {@link #grandparentFolder} feed
 * folder-structure inference.
 */
public final class SourceRecord {

    /** Container file name, or {@code ""} for a loose file. */
    public final String containerFileName;
    /** 1-based series index; {@code <= 0} when this is not a container series. */
    public final int seriesIndex;
    /** Series/scene name from the container, or {@code ""}. */
    public final String seriesName;
    /** Immediate parent folder name, or {@code ""}. */
    public final String parentFolder;
    /** Grandparent folder name, or {@code ""}. */
    public final String grandparentFolder;
    /** Identity seed used for filename-style parsing. */
    public final String seed;

    private SourceRecord(String containerFileName, int seriesIndex, String seriesName,
                         String parentFolder, String grandparentFolder, String seed) {
        this.containerFileName = nz(containerFileName);
        this.seriesIndex = seriesIndex;
        this.seriesName = nz(seriesName);
        this.parentFolder = nz(parentFolder);
        this.grandparentFolder = nz(grandparentFolder);
        this.seed = nz(seed);
    }

    /** A loose TIFF (or any single-file source). */
    public static SourceRecord looseFile(String fileName, String parentFolder, String grandparentFolder) {
        return new SourceRecord("", 0, "", parentFolder, grandparentFolder, nz(fileName));
    }

    /** One series of a multi-series container. */
    public static SourceRecord containerSeries(String containerFileName, int oneBasedSeriesIndex,
                                               String seriesName, String parentFolder,
                                               String grandparentFolder) {
        String c = nz(containerFileName);
        String s = nz(seriesName);
        // Bio-Formats style title (" - " separator) so the legacy parser can
        // split container vs series exactly as it does for live ImagePlus titles.
        String seed = s.isEmpty() ? c : (c.isEmpty() ? s : c + " - " + s);
        return new SourceRecord(c, oneBasedSeriesIndex, s, parentFolder, grandparentFolder, seed);
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
