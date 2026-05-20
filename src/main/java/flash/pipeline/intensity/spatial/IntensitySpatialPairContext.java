package flash.pipeline.intensity.spatial;

import flash.pipeline.analyses.wizard.IntensitySpatialConfig;
import ij.IJ;
import ij.ImagePlus;
import ij.gui.Roi;

/**
 * Per-measurement source/partner image context for cross-channel spatial metrics.
 */
public final class IntensitySpatialPairContext {
    private final IntensitySpatialConfig config;
    private final ImagePlus sourceImage;
    private final ImagePlus sourceBinarizedImage;
    private final ImagePlus sourceMaskImage;
    private final ImagePlus partnerImage;
    private final ImagePlus partnerBinarizedImage;
    private final ImagePlus partnerMaskImage;
    private final int sliceIndex;
    private final Roi roi;
    private final IntensitySpatialOutputMode outputMode;
    private final String imageId;
    private final String sourceChannelName;
    private final String partnerChannelName;
    private final String roiLabel;
    private final IntensitySpatialContext.WarningSink warningSink;

    public IntensitySpatialPairContext(IntensitySpatialConfig config,
                                       ImagePlus sourceImage,
                                       ImagePlus sourceBinarizedImage,
                                       ImagePlus sourceMaskImage,
                                       ImagePlus partnerImage,
                                       ImagePlus partnerBinarizedImage,
                                       ImagePlus partnerMaskImage,
                                       int sliceIndex,
                                       Roi roi,
                                       IntensitySpatialOutputMode outputMode,
                                       String imageId,
                                       String sourceChannelName,
                                       String partnerChannelName,
                                       String roiLabel,
                                       IntensitySpatialContext.WarningSink warningSink) {
        this.config = config == null ? IntensitySpatialConfig.disabled() : config;
        this.sourceImage = sourceImage;
        this.sourceBinarizedImage = sourceBinarizedImage;
        this.sourceMaskImage = sourceMaskImage;
        this.partnerImage = partnerImage;
        this.partnerBinarizedImage = partnerBinarizedImage;
        this.partnerMaskImage = partnerMaskImage;
        this.sliceIndex = Math.max(1, sliceIndex);
        this.roi = roi == null ? null : (Roi) roi.clone();
        this.outputMode = outputMode == null ? IntensitySpatialOutputMode.BASE : outputMode;
        this.imageId = blankToUnknown(imageId);
        this.sourceChannelName = blankToUnknown(sourceChannelName);
        this.partnerChannelName = blankToUnknown(partnerChannelName);
        this.roiLabel = roiLabel == null ? "" : roiLabel;
        this.warningSink = warningSink;
    }

    public IntensitySpatialConfig config() { return config; }

    public ImagePlus sourceImage() { return sourceImage; }

    public ImagePlus sourceBinarizedImage() { return sourceBinarizedImage; }

    public ImagePlus sourceMaskImage() { return sourceMaskImage; }

    public ImagePlus partnerImage() { return partnerImage; }

    public ImagePlus partnerBinarizedImage() { return partnerBinarizedImage; }

    public ImagePlus partnerMaskImage() { return partnerMaskImage; }

    public boolean hasSourceBinarizedImage() { return sourceBinarizedImage != null; }

    public boolean hasSourceMaskImage() { return sourceMaskImage != null; }

    public boolean hasPartnerBinarizedImage() { return partnerBinarizedImage != null; }

    public boolean hasPartnerMaskImage() { return partnerMaskImage != null; }

    public int sliceIndex() { return sliceIndex; }

    public Roi roi() { return roi == null ? null : (Roi) roi.clone(); }

    public IntensitySpatialOutputMode outputMode() { return outputMode; }

    public String imageId() { return imageId; }

    public String sourceChannelName() { return sourceChannelName; }

    public String partnerChannelName() { return partnerChannelName; }

    public String roiLabel() { return roiLabel; }

    public void warn(String message) {
        String text = "[FLASH] Intensity-spatial warning for " + imageId
                + " source " + sourceChannelName
                + " partner " + partnerChannelName
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
