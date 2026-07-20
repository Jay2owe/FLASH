package flash.pipeline.ui.variations;

import flash.pipeline.decontamination.CorrectionFeatureRegistry;
import flash.pipeline.decontamination.CorrectionPipeline;
import flash.pipeline.decontamination.SpectralDecontaminationConfig;
import flash.pipeline.decontamination.SpectralMergeRenderer;

import ij.ImagePlus;

import java.util.Collection;

/**
 * Default {@link SpectralPreviewAdapter}: runs the existing {@link CorrectionPipeline} on the
 * shared cropped multi-channel source and builds a colored "after" merge via
 * {@link SpectralMergeRenderer}. Display scales are computed once (from the raw crop) and reused
 * for every combination so cells are visually comparable to each other and to the baseline.
 */
public final class SpectralPreviewAdapterImpl implements SpectralPreviewAdapter {

    private final CorrectionFeatureRegistry registry;
    private final SpectralMergeRenderer.DisplayScales scales;

    public SpectralPreviewAdapterImpl(ImagePlus rawCropMultiChannel,
                                      SpectralDecontaminationConfig base) {
        this(rawCropMultiChannel, base, CorrectionFeatureRegistry.getDefault());
    }

    public SpectralPreviewAdapterImpl(ImagePlus rawCropMultiChannel,
                                      SpectralDecontaminationConfig base,
                                      CorrectionFeatureRegistry registry) {
        if (rawCropMultiChannel == null) {
            throw new IllegalArgumentException("rawCropMultiChannel must not be null");
        }
        if (base == null) {
            throw new IllegalArgumentException("base must not be null");
        }
        this.registry = registry == null ? CorrectionFeatureRegistry.getDefault() : registry;
        this.scales = SpectralMergeRenderer.computeScales(rawCropMultiChannel, base);
    }

    @Override
    public Result decontaminatePreview(ImagePlus rawCropMultiChannel,
                                       SpectralDecontaminationConfig resolvedConfig) throws Exception {
        CorrectionPipeline.ExecutionState state =
                CorrectionPipeline.ExecutionState.create(rawCropMultiChannel, resolvedConfig);
        ImagePlus corrected = null;
        ImagePlus mask = null;
        ImagePlus merge = null;
        boolean ok = false;
        try {
            resolvedConfig.getCorrectionPipeline().execute(registry, state);
            corrected = state.getCorrectedImage();
            mask = state.getMaskImage();
            // Release intermediate images that are not part of the result.
            closeQuietly(state.getVetoMaskImage());
            closeAll(state.getParameterMaps().values());

            merge = SpectralMergeRenderer.buildAfterMerge(
                    rawCropMultiChannel, corrected, resolvedConfig, scales, "spectral_after_merge");
            Result result = new Result(merge, corrected, mask);
            ok = true;
            return result;
        } finally {
            // On any failure the caller never receives a Result and cannot close these, so release
            // every image the pipeline produced. Read corrected/mask from `state` (not the locals):
            // if execute() throws after an earlier feature populated them, the locals are still null.
            if (!ok) {
                closeQuietly(merge);
                closeQuietly(state.getCorrectedImage());
                closeQuietly(state.getMaskImage());
                closeQuietly(state.getVetoMaskImage());
                closeAll(state.getParameterMaps().values());
            }
        }
    }

    @Override
    public void close(Result result) {
        if (result == null) {
            return;
        }
        closeQuietly(result.mergeRgb());
        closeQuietly(result.correctedGray());
        closeQuietly(result.maskOrNull());
    }

    private static void closeAll(Collection<ImagePlus> images) {
        if (images == null) {
            return;
        }
        for (ImagePlus image : images) {
            closeQuietly(image);
        }
    }

    private static void closeQuietly(ImagePlus image) {
        if (image == null) {
            return;
        }
        try {
            image.changes = false;
        } catch (Throwable ignored) {
        }
        try {
            image.close();
        } catch (Throwable ignored) {
        }
        try {
            image.flush();
        } catch (Throwable ignored) {
        }
    }
}
