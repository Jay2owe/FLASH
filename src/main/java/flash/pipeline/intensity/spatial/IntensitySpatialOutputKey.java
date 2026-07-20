package flash.pipeline.intensity.spatial;

import flash.pipeline.naming.ChannelFilenameCodec;

import java.io.File;
import java.util.Objects;

/**
 * Identity for one owned intensity output CSV.
 */
public final class IntensitySpatialOutputKey {
    private final String channelName;
    private final IntensitySpatialOutputMode mode;
    private final String roiMaskName;

    public IntensitySpatialOutputKey(String channelName,
                                     IntensitySpatialOutputMode mode,
                                     String roiMaskName) {
        if (channelName == null || channelName.trim().isEmpty()) {
            throw new IllegalArgumentException("Channel name must not be blank.");
        }
        this.channelName = channelName;
        this.mode = mode == null ? IntensitySpatialOutputMode.BASE : mode;
        String trimmedMask = roiMaskName == null ? null : roiMaskName.trim();
        this.roiMaskName = trimmedMask == null || trimmedMask.isEmpty() ? null : trimmedMask;
        if (this.roiMaskName != null && this.mode != IntensitySpatialOutputMode.BASE) {
            throw new IllegalArgumentException("Channel ROI Mask outputs are base-only.");
        }
    }

    public static IntensitySpatialOutputKey base(String channelName) {
        return new IntensitySpatialOutputKey(channelName, IntensitySpatialOutputMode.BASE, null);
    }

    public static IntensitySpatialOutputKey roiMaskBase(String channelName, String roiMaskName) {
        return new IntensitySpatialOutputKey(channelName, IntensitySpatialOutputMode.BASE, roiMaskName);
    }

    public static IntensitySpatialOutputKey of(String channelName, IntensitySpatialOutputMode mode) {
        return new IntensitySpatialOutputKey(channelName, mode, null);
    }

    public String channelName() {
        return channelName;
    }

    public IntensitySpatialOutputMode mode() {
        return mode;
    }

    public String roiMaskName() {
        return roiMaskName;
    }

    public boolean isChannelRoiMaskOutput() {
        return roiMaskName != null;
    }

    public File csvFile(File saveRoot) {
        return new File(saveRoot, fileStem() + mode.fileSuffix() + ".csv");
    }

    public String fileStem() {
        String stem = ChannelFilenameCodec.toSafe(channelName);
        if (roiMaskName != null) {
            stem = stem + " in " + ChannelFilenameCodec.toSafe(roiMaskName) + " ROI";
        }
        return stem;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof IntensitySpatialOutputKey)) return false;
        IntensitySpatialOutputKey that = (IntensitySpatialOutputKey) other;
        return channelName.equals(that.channelName)
                && mode == that.mode
                && Objects.equals(roiMaskName, that.roiMaskName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(channelName, mode, roiMaskName);
    }

    @Override
    public String toString() {
        return "IntensitySpatialOutputKey{"
                + "channel='" + channelName + '\''
                + ", mode=" + mode
                + (roiMaskName == null ? "" : ", roiMask='" + roiMaskName + '\'')
                + '}';
    }
}
