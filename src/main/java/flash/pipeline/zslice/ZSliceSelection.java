package flash.pipeline.zslice;

/**
 * Final resolved Z-slice selection for a single series.
 */
public final class ZSliceSelection {
    public final int seriesIndex;
    public final String seriesName;
    public final int totalSlices;
    public final ZSliceRange range;

    public ZSliceSelection(int seriesIndex, String seriesName, int totalSlices, ZSliceRange range) {
        if (seriesIndex < 0) {
            throw new IllegalArgumentException("seriesIndex must be >= 0");
        }
        if (totalSlices < 1) {
            throw new IllegalArgumentException("totalSlices must be >= 1");
        }
        if (range == null || !range.isValidFor(totalSlices)) {
            throw new IllegalArgumentException("range must be valid for totalSlices");
        }
        this.seriesIndex = seriesIndex;
        this.seriesName = seriesName == null ? "" : seriesName;
        this.totalSlices = totalSlices;
        this.range = range;
    }

    public int sliceCount() {
        return range.count();
    }

    public boolean isFullStack() {
        return range.coversFullStack(totalSlices);
    }

    public String displayName() {
        return seriesName == null || seriesName.trim().isEmpty()
                ? "Series " + (seriesIndex + 1)
                : seriesName;
    }
}
