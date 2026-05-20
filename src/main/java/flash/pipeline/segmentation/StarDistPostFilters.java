package flash.pipeline.segmentation;

public final class StarDistPostFilters {
    public final double areaMin;
    public final double areaMax;
    public final double qualityMin;
    public final double intensityMin;

    public StarDistPostFilters(double areaMin,
                               double areaMax,
                               double qualityMin,
                               double intensityMin) {
        this.areaMin = Math.max(0, areaMin);
        this.areaMax = areaMax <= 0 ? Double.POSITIVE_INFINITY : areaMax;
        this.qualityMin = Math.max(0, qualityMin);
        this.intensityMin = Math.max(0, intensityMin);
    }
}
