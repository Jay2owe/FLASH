package flash.pipeline.ui.config;

import ij.ImagePlus;

public interface ConfigQcActions {

    void setStatus(String text);

    void markPreviewStale(String text);

    void setAdjustedPreview(ImagePlus image, String text);

    void nextImage();

    void skipCurrentImage();

    void restartStage();

    void cancel();
}
