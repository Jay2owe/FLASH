package flash.pipeline.deconv.preview;

import ij.ImagePlus;

/**
 * Supplies the raw, full-Z, single-channel {@link ImagePlus} that a deconvolution preview or QC
 * compute runs against, decoupled from where those pixels come from.
 * <p>
 * The standalone {@code DeconvolutionAnalysis} implements this over
 * {@code openSeriesChannel(directory, seriesIndex, channelIndex)}; the setup QC step (Stage 12)
 * supplies its own implementation over its own directory/series. Either way, {@link #openRawChannel}
 * returns a freshly opened channel the caller owns, and {@link #close} disposes of it (and of any
 * intermediate stack) with the source's own close semantics.
 */
public interface PreviewImageSource {

    /**
     * Open the raw, full-Z stack for a single channel. Returns {@code null} if the channel cannot
     * be opened (the caller treats that as a preview failure). The returned image is owned by the
     * caller and must be released through {@link #close}.
     */
    ImagePlus openRawChannel(int channelIndex) throws Exception;

    /** Release an image obtained from this source (no-op for {@code null}). */
    void close(ImagePlus image);
}
