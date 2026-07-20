package flash.pipeline.ui.variations;

import flash.pipeline.decontamination.SpectralDecontaminationConfig;

import ij.ImagePlus;

/**
 * Runs a single spectral-decontamination preview for one resolved config, used by
 * {@code flash.pipeline.ui.variations.strategy.SpectralDecontaminationSweep} to fill the grid.
 *
 * <p>Implementations MUST NOT mutate {@code rawCropMultiChannel}; the same cropped
 * multi-channel source is passed for every combination, so the correction pipeline (which
 * creates new output images and never mutates its source) can safely share it.</p>
 *
 * <p>The result carries three images: {@code mergeRgb} is the colored "after" merge shown in
 * the grid cell (target vs residual contaminants), {@code correctedGray} is the grayscale
 * corrected target used for quality metrics, and {@code maskOrNull} is the final mask when the
 * stack produces one. The sweep strategy publishes {@code mergeRgb} and releases the others.</p>
 */
public interface SpectralPreviewAdapter {

    /** Result of one preview run. Any field may be closed by the caller once consumed. */
    final class Result {
        private final ImagePlus mergeRgb;
        private final ImagePlus correctedGray;
        private final ImagePlus maskOrNull;

        public Result(ImagePlus mergeRgb, ImagePlus correctedGray, ImagePlus maskOrNull) {
            this.mergeRgb = mergeRgb;
            this.correctedGray = correctedGray;
            this.maskOrNull = maskOrNull;
        }

        public ImagePlus mergeRgb() {
            return mergeRgb;
        }

        public ImagePlus correctedGray() {
            return correctedGray;
        }

        public ImagePlus maskOrNull() {
            return maskOrNull;
        }
    }

    /**
     * Decontaminate the already-cropped multi-channel raw stack with the given resolved config.
     *
     * @return a result whose {@code mergeRgb} is a newly created image (caller takes ownership),
     *         never {@code null}.
     */
    Result decontaminatePreview(ImagePlus rawCropMultiChannel,
                                SpectralDecontaminationConfig resolvedConfig) throws Exception;

    /** Release all images held by a preview result; a no-op for unowned images. */
    void close(Result result);
}
