package flash.pipeline.decontamination.features;

import flash.pipeline.decontamination.CorrectionFeature;
import flash.pipeline.decontamination.CorrectionPipeline;
import ij.ImagePlus;

import java.util.Collections;
import java.util.Set;

/**
 * Records that saturated pixels should be excluded by downstream fitters.
 */
public class SaturationExclusionFeature implements CorrectionPipeline.ExecutableFeature {

    public static final String ID = "saturation_exclusion";

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public String getDisplayName() {
        return "Saturation exclusion";
    }

    @Override
    public String getDescription() {
        return "Flags saturated pixels for correction fitting safeguards.";
    }

    @Override
    public InputType getRequiredInputType() {
        return InputType.SOURCE_IMAGE;
    }

    @Override
    public OutputType getOutputType() {
        return OutputType.METRIC;
    }

    @Override
    public Set<RequiredChannel> getRequiredChannels() {
        return Collections.emptySet();
    }

    @Override
    public boolean requiresConditions() {
        return false;
    }

    @Override
    public boolean requiresControls() {
        return false;
    }

    @Override
    public boolean requiresExistingObjectMaps() {
        return false;
    }

    @Override
    public boolean canPreviewCheaply() {
        return true;
    }

    @Override
    public boolean isExpertOnly() {
        return false;
    }

    @Override
    public boolean isThresholdFeature() {
        return false;
    }

    @Override
    public boolean requiresVetoMask() {
        return false;
    }

    @Override
    public void apply(CorrectionPipeline.ExecutionState state) {
        ImagePlus source = state == null ? null : state.getSourceImage();
        long saturated = 0L;
        long total = 0L;
        if (source != null) {
            int bitDepth = source.getBitDepth();
            double max = bitDepth <= 8 ? 255.0 : 65535.0;
            for (int i = 1; i <= source.getStackSize(); i++) {
                Object pixels = source.getStack().getProcessor(i).getPixels();
                if (pixels instanceof byte[]) {
                    byte[] values = (byte[]) pixels;
                    total += values.length;
                    for (byte value : values) {
                        if ((value & 0xff) >= max) saturated++;
                    }
                } else if (pixels instanceof short[]) {
                    short[] values = (short[]) pixels;
                    total += values.length;
                    for (short value : values) {
                        if ((value & 0xffff) >= max) saturated++;
                    }
                }
            }
        }
        state.addSummary(new CorrectionPipeline.FeatureSummary(ID, getDisplayName())
                .putInt("saturated_pixels", saturated)
                .putInt("total_pixels_checked", total));
    }
}
