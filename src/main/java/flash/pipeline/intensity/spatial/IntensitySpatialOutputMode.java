package flash.pipeline.intensity.spatial;

/**
 * Output families owned by Fluorescence Intensity Analysis.
 */
public enum IntensitySpatialOutputMode {
    BASE(""),
    MIP("_MIP"),
    NATIVE_3D("_3D");

    private final String fileSuffix;

    IntensitySpatialOutputMode(String fileSuffix) {
        this.fileSuffix = fileSuffix;
    }

    public String fileSuffix() {
        return fileSuffix;
    }
}
