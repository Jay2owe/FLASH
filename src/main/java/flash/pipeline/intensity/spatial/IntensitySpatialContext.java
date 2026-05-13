package flash.pipeline.intensity.spatial;

import flash.pipeline.analyses.wizard.IntensitySpatialConfig;
import ij.IJ;
import ij.ImagePlus;
import ij.gui.Roi;

/**
 * Per-measurement image context passed to same-channel spatial algorithms.
 */
public final class IntensitySpatialContext {
    public interface WarningSink {
        void warn(String message);
    }

    private final IntensitySpatialConfig config;
    private final ImagePlus image;
    private final ImagePlus binarizedImage;
    private final int sliceIndex;
    private final Roi roi;
    private final IntensitySpatialOutputMode outputMode;
    private final String imageId;
    private final String channelName;
    private final String roiLabel;
    private final WarningSink warningSink;

    public IntensitySpatialContext(IntensitySpatialConfig config,
                                   ImagePlus image,
                                   ImagePlus binarizedImage,
                                   int sliceIndex,
                                   Roi roi,
                                   IntensitySpatialOutputMode outputMode,
                                   String imageId,
                                   String channelName,
                                   String roiLabel,
                                   WarningSink warningSink) {
        this.config = config == null ? IntensitySpatialConfig.disabled() : config;
        this.image = image;
        this.binarizedImage = binarizedImage;
        this.sliceIndex = Math.max(1, sliceIndex);
        this.roi = roi == null ? null : (Roi) roi.clone();
        this.outputMode = outputMode == null ? IntensitySpatialOutputMode.BASE : outputMode;
        this.imageId = blankToUnknown(imageId);
        this.channelName = blankToUnknown(channelName);
        this.roiLabel = roiLabel == null ? "" : roiLabel;
        this.warningSink = warningSink;
    }

    public IntensitySpatialConfig config() {
        return config;
    }

    public ImagePlus image() {
        return image;
    }

    public ImagePlus binarizedImage() {
        return binarizedImage;
    }

    public boolean hasBinarizedImage() {
        return binarizedImage != null;
    }

    public int sliceIndex() {
        return sliceIndex;
    }

    public Roi roi() {
        return roi == null ? null : (Roi) roi.clone();
    }

    public IntensitySpatialOutputMode outputMode() {
        return outputMode;
    }

    public String imageId() {
        return imageId;
    }

    public String channelName() {
        return channelName;
    }

    public String roiLabel() {
        return roiLabel;
    }

    public IntensitySpatialContext withoutBinarizedImage() {
        return new IntensitySpatialContext(config, image, null, sliceIndex, roi, outputMode,
                imageId, channelName, roiLabel, warningSink);
    }

    public void warn(String message) {
        String text = "[FLASH] Intensity-spatial warning for " + imageId
                + " channel " + channelName
                + (roiLabel.isEmpty() ? "" : " ROI " + roiLabel)
                + ": " + message;
        if (warningSink != null) {
            warningSink.warn(text);
        } else {
            IJ.log(text);
        }
    }

    private static String blankToUnknown(String value) {
        return value == null || value.trim().isEmpty() ? "unknown" : value.trim();
    }
}
