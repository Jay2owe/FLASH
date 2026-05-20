package flash.pipeline.deconv.engine;

import ij.ImagePlus;

import java.util.List;

public interface DeconvolutionEngine {
    String key();
    String displayName();
    String description();
    boolean isAvailable();
    List<Algorithm> supportedAlgorithms();
    ImagePlus deconvolve(ImagePlus stack, ImagePlus psf, DeconvParams params)
            throws DeconvolutionException;
}
