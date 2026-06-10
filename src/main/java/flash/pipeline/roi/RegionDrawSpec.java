package flash.pipeline.roi;

/**
 * One region to draw in a Draw ROIs session. Carries the region name plus the channel and
 * display adjustment used while its ROIs are drawn. Multiple specs let a single Draw ROIs
 * pass cover several regions, so an image containing more than one region is opened once.
 */
public final class RegionDrawSpec {

    public final String regionName;
    /** 1-based channel index shown while drawing this region. */
    public final int drawChannel;
    /** Display adjustment: {@code "None"}, {@code "Automatic"}, or {@code "Manual"}. */
    public final String displayMode;

    public RegionDrawSpec(String regionName, int drawChannel, String displayMode) {
        this.regionName = regionName == null ? "" : regionName.trim();
        this.drawChannel = drawChannel;
        this.displayMode = (displayMode == null || displayMode.trim().isEmpty())
                ? "None" : displayMode.trim();
    }

    public boolean hasName() {
        return !regionName.isEmpty();
    }

    @Override
    public String toString() {
        return "RegionDrawSpec{" + regionName + ", ch=" + drawChannel + ", " + displayMode + "}";
    }
}
