package flash.pipeline.segmentation;

public final class StarDistLinkingParams {
    public final double linkingMaxDistance;
    public final double gapClosingMaxDistance;
    public final int maxFrameGap;

    public StarDistLinkingParams(double linkingMaxDistance,
                                 double gapClosingMaxDistance,
                                 int maxFrameGap) {
        this.linkingMaxDistance = Math.max(0, linkingMaxDistance);
        this.gapClosingMaxDistance = Math.max(0, gapClosingMaxDistance);
        this.maxFrameGap = Math.max(0, maxFrameGap);
    }
}
