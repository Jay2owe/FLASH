package flash.pipeline.qc;

/**
 * Channel metadata passed into QC selection logic.
 */
public final class QcSelectionChannel {

    public final int channelIndex;
    public final int channelNumber;
    public final String channelName;
    public final boolean useCustomMinMax;
    public final boolean useCustomThreshold;
    public final boolean useCustomParticleSize;

    public QcSelectionChannel(int channelIndex, String channelName,
                              boolean useCustomMinMax,
                              boolean useCustomThreshold,
                              boolean useCustomParticleSize) {
        this.channelIndex = channelIndex;
        this.channelNumber = channelIndex + 1;
        this.channelName = channelName == null ? "" : channelName;
        this.useCustomMinMax = useCustomMinMax;
        this.useCustomThreshold = useCustomThreshold;
        this.useCustomParticleSize = useCustomParticleSize;
    }

    @Override
    public String toString() {
        return "C" + channelNumber + " (" + channelName + ")";
    }
}
