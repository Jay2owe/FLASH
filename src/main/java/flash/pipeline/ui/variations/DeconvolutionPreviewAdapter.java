package flash.pipeline.ui.variations;

import flash.pipeline.deconv.engine.DeconvSettings;

import ij.ImagePlus;

/**
 * Runs a single-channel deconvolution preview for one parameter combination, used by
 * {@link flash.pipeline.ui.variations.strategy.DeconvolutionSweep} to fill the grid.
 *
 * <p>Implementations MUST NOT mutate {@code rawCrop}; the same cropped source is passed
 * for every combination, so the implementation should duplicate before any in-place
 * processing (e.g. sanitization).
 */
public interface DeconvolutionPreviewAdapter {

    /**
     * Deconvolve the already-cropped raw stack with the given settings.
     *
     * @return a newly created stack (caller takes ownership), never {@code null}.
     */
    ImagePlus deconvolvePreview(ImagePlus rawCrop, DeconvSettings settings) throws Exception;

    /** Release a preview image produced by this adapter; a no-op for unowned images. */
    void close(ImagePlus image);
}
