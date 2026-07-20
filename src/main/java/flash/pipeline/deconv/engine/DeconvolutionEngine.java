package flash.pipeline.deconv.engine;

import flash.pipeline.deconv.psf.PsfModel;

import ij.ImagePlus;

import java.util.Arrays;
import java.util.List;

public interface DeconvolutionEngine {
    String key();
    String displayName();
    String description();
    boolean isAvailable();
    List<Algorithm> supportedAlgorithms();

    /**
     * PSF models this engine can consume. Existing engines take a generated PSF
     * image and therefore accept every built-in model unless they declare a narrower set.
     */
    default List<PsfModel> supportedPsfModels() {
        return Arrays.asList(PsfModel.values());
    }

    default boolean supportsPsfModel(PsfModel model) {
        return model != null && supportedPsfModels().contains(model);
    }

    /**
     * Whether this engine/algorithm combination actually consumes the shared iteration count.
     * Defaults to the historical UI behaviour for third-party/test engines that have not yet
     * declared a narrower parameter set.
     */
    default boolean usesIterations(Algorithm algorithm) {
        return algorithm != null && supportedAlgorithms().contains(algorithm);
    }

    /**
     * Whether this engine/algorithm combination actually consumes the shared regularization value.
     * Defaults to the historical UI behaviour for third-party/test engines that have not yet
     * declared a narrower parameter set.
     */
    default boolean usesRegularization(Algorithm algorithm) {
        return algorithm != null && supportedAlgorithms().contains(algorithm);
    }

    ImagePlus deconvolve(ImagePlus stack, ImagePlus psf, DeconvParams params)
            throws DeconvolutionException;
}
