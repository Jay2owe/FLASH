package flash.pipeline.click.training;

import ij.ImagePlus;

/**
 * Supplies images lazily by preview/source image name.
 */
public interface ImagePlusProvider {
    ImagePlus get(String imageName);
}
