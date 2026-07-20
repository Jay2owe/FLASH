package flash.pipeline.decontamination.features;

import flash.pipeline.decontamination.CorrectionFeature;
import flash.pipeline.decontamination.CorrectionImageOps;
import flash.pipeline.decontamination.CorrectionPipeline;
import ij.ImagePlus;

import java.util.Collections;
import java.util.Set;

/**
 * Applies the current veto mask to the current candidate mask using AND NOT.
 */
public class VetoMasksFeature implements CorrectionPipeline.ExecutableFeature {

    public static final String ID = "veto_masks";

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public String getDisplayName() {
        return "Veto masks";
    }

    @Override
    public String getDescription() {
        return "Combines the current mask with earlier veto masks using AND NOT.";
    }

    @Override
    public InputType getRequiredInputType() {
        return InputType.MASK;
    }

    @Override
    public OutputType getOutputType() {
        return OutputType.MASK;
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
        return true;
    }

    @Override
    public void apply(CorrectionPipeline.ExecutionState state) {
        if (state == null) {
            throw new IllegalArgumentException("Execution state is required.");
        }

        ImagePlus mask = state.getMaskImage();
        ImagePlus vetoMask = state.getVetoMaskImage();
        CorrectionImageOps.requireSingleChannelMask(mask, "Mask");
        CorrectionImageOps.requireSingleChannelMask(vetoMask, "Veto mask");

        if (mask.getWidth() != vetoMask.getWidth()
                || mask.getHeight() != vetoMask.getHeight()
                || CorrectionImageOps.planeCount(mask) != CorrectionImageOps.planeCount(vetoMask)) {
            throw new IllegalArgumentException("Mask and veto mask dimensions must match.");
        }

        int planeCount = CorrectionImageOps.planeCount(mask);
        byte[][] combinedPlanes = new byte[planeCount][];
        long keptPixels = 0L;
        long vetoedPixels = 0L;
        long candidatePixels = 0L;

        for (int plane = 0; plane < planeCount; plane++) {
            byte[] includePixels = CorrectionImageOps.singleChannelMaskPlanePixels(mask, plane);
            byte[] vetoPixels = CorrectionImageOps.singleChannelMaskPlanePixels(vetoMask, plane);
            byte[] combined = new byte[includePixels.length];
            for (int pixel = 0; pixel < includePixels.length; pixel++) {
                boolean include = (includePixels[pixel] & 0xff) != 0;
                boolean veto = (vetoPixels[pixel] & 0xff) != 0;
                if (include) {
                    candidatePixels++;
                }
                if (include && !veto) {
                    combined[pixel] = (byte) 255;
                    keptPixels++;
                } else if (include) {
                    vetoedPixels++;
                }
            }
            combinedPlanes[plane] = combined;
        }

        state.setMaskImage(CorrectionImageOps.createMaskImageLike(
                mask,
                "veto_applied_mask",
                combinedPlanes));

        state.addSummary(new CorrectionPipeline.FeatureSummary(ID, getDisplayName())
                .putInt("candidate_pixels", candidatePixels)
                .putInt("vetoed_pixels", vetoedPixels)
                .putInt("kept_pixels", keptPixels)
                .putDouble("kept_fraction", candidatePixels <= 0L
                        ? 0.0
                        : (double) keptPixels / (double) candidatePixels));
    }
}
