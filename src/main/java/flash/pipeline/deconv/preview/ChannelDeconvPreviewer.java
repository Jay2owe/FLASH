package flash.pipeline.deconv.preview;

import flash.pipeline.image.WindowManagerLock;
import ij.ImagePlus;
import ij.macro.Interpreter;

/**
 * Runs the per-channel deconvolution compute for a preview or QC, composed from a
 * {@link PreviewImageSource} (where the raw pixels come from) and a set of {@link DeconvOps}
 * primitives (crop/deconvolve/window bookkeeping) supplied by the owner.
 * <p>
 * This is the reusable seam behind the standalone {@code DeconvolutionAnalysis} preview and the
 * setup deconvolution QC step (Stage 12): both feed it a source and the same engine primitives, so
 * the compute cannot drift between them. The actual PSF synthesis, engine call, and the private
 * crop/sanitize helpers stay in {@code DeconvolutionAnalysis} and are reached only through
 * {@link DeconvOps}, so none of those cross the package boundary as observable API.
 * <p>
 * <b>Global window state.</b> Both compute methods toggle {@link Interpreter#batchMode} and hold
 * {@link WindowManagerLock#LOCK} for the whole render, snapshotting the open windows first and
 * mopping up strays afterwards. This is load-bearing — it is the one place ImageJ's global window
 * state is driven from a background worker — and lives here, in the previewer, not in the caller.
 */
public final class ChannelDeconvPreviewer {

    /**
     * Compute + window-bookkeeping primitives the previewer delegates to the owner. Kept as a
     * callback (rather than exposing engine/optics value objects across packages) so the compute
     * stays byte-for-byte the same as the standalone flow.
     */
    public interface DeconvOps {
        /** Centre-crop a full single-channel stack to the preview tile (caller owns the result). */
        ImagePlus cropForPreview(ImagePlus fullChannel);

        /**
         * Duplicate + sanitize + deconvolve a raw stack without mutating it; returns a new stack the
         * caller owns, or {@code null} when PSF synthesis fails for the current settings.
         */
        ImagePlus deconvolve(ImagePlus rawStack) throws Exception;

        /** Snapshot of the open ImageJ image-window IDs before a render (empty when headless). */
        int[] snapshotWindows();

        /** Close any ImageJ image windows leaked since {@code beforeIds} was captured. */
        void closeStrayWindows(int[] beforeIds);
    }

    private final PreviewImageSource source;
    private final DeconvOps ops;

    public ChannelDeconvPreviewer(PreviewImageSource source, DeconvOps ops) {
        if (source == null) throw new IllegalArgumentException("source is required.");
        if (ops == null) throw new IllegalArgumentException("ops are required.");
        this.source = source;
        this.ops = ops;
    }

    /**
     * Produce the raw|deconvolved preview pair for one channel: crop the centre of the channel,
     * cache that raw crop in {@code rawCropCache[0]} (so repeated renders reuse the same opened
     * crop), and deconvolve a fresh copy of it. Returns {@code {rawCrop, deconvolved}}; the second
     * element is {@code null} when PSF synthesis fails. Must be called off the EDT.
     */
    public ImagePlus[] renderCropPreview(int channelIndex, ImagePlus[] rawCropCache) throws Exception {
        WindowManagerLock.LOCK.lock();
        try {
            int[] windowsBeforeRender = ops.snapshotWindows();
            boolean previousBatchMode = Interpreter.batchMode;
            Interpreter.batchMode = true;
            try {
                ImagePlus rawCrop = rawCropCache[0];
                if (rawCrop == null) {
                    ImagePlus rawChannel = source.openRawChannel(channelIndex);
                    if (rawChannel == null) {
                        throw new IllegalStateException("Could not open channel for preview.");
                    }
                    try {
                        rawCrop = ops.cropForPreview(rawChannel);
                    } finally {
                        source.close(rawChannel);
                    }
                    rawCropCache[0] = rawCrop;
                }
                ImagePlus deconvolved = ops.deconvolve(rawCrop);
                return new ImagePlus[]{rawCrop, deconvolved};
            } finally {
                Interpreter.batchMode = previousBatchMode;
                ops.closeStrayWindows(windowsBeforeRender);
            }
        } finally {
            WindowManagerLock.LOCK.unlock();
        }
    }

    /**
     * Deconvolve the full-Z stack for one channel (no crop) for QC caching (Stage 12). Opens the
     * raw channel through the source, deconvolves a sanitized copy, and returns a new stack the
     * caller owns, or {@code null} when PSF synthesis fails. Uses the same
     * {@code WindowManagerLock}/{@code batchMode}/stray-window handling as the crop preview. Must be
     * called off the EDT.
     */
    public ImagePlus deconvolveFullChannel(int channelIndex) throws Exception {
        WindowManagerLock.LOCK.lock();
        try {
            int[] windowsBeforeRender = ops.snapshotWindows();
            boolean previousBatchMode = Interpreter.batchMode;
            Interpreter.batchMode = true;
            try {
                ImagePlus rawChannel = source.openRawChannel(channelIndex);
                if (rawChannel == null) {
                    throw new IllegalStateException("Could not open channel for deconvolution.");
                }
                try {
                    return ops.deconvolve(rawChannel);
                } finally {
                    source.close(rawChannel);
                }
            } finally {
                Interpreter.batchMode = previousBatchMode;
                ops.closeStrayWindows(windowsBeforeRender);
            }
        } finally {
            WindowManagerLock.LOCK.unlock();
        }
    }
}
