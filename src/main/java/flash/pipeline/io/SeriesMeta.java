package flash.pipeline.io;

/**
 * Lightweight per-series metadata snapshot (calibration + Z-slices).
 * Source-agnostic — works for LIF series and TIFF-folder files alike.
 */
public final class SeriesMeta {
    public final int index;
    /** Series name from the source metadata (e.g. image title). May be null. */
    public final String name;
    public final int width;
    public final int height;
    public final int nSlices;
    public final int nChannels;
    public final double pixelWidth;
    public final double pixelHeight;
    public final double pixelDepth;
    public final String unit;

    public SeriesMeta(int index, String name, int nSlices,
                      double pixelWidth, double pixelHeight, double pixelDepth,
                      String unit) {
        this(index, name, 0, 0, nSlices, 0, pixelWidth, pixelHeight, pixelDepth, unit);
    }

    public SeriesMeta(int index, String name, int width, int height, int nSlices, int nChannels,
                      double pixelWidth, double pixelHeight, double pixelDepth,
                      String unit) {
        this.index = index;
        this.name = name;
        this.width = width;
        this.height = height;
        this.nSlices = nSlices;
        this.nChannels = nChannels;
        this.pixelWidth = pixelWidth;
        this.pixelHeight = pixelHeight;
        this.pixelDepth = pixelDepth;
        this.unit = unit;
    }

    public boolean isCalibrated() {
        return unit != null
                && !"pixel".equalsIgnoreCase(unit)
                && !"pixels".equalsIgnoreCase(unit)
                && pixelWidth != 0 && pixelHeight != 0;
    }
}
