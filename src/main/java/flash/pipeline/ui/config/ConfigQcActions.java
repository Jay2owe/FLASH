package flash.pipeline.ui.config;

import ij.ImagePlus;

import javax.swing.JButton;

public interface ConfigQcActions {

    void setStatus(String text);

    void markPreviewStale(String text);

    void setAdjustedPreview(ImagePlus image, String text);

    void nextImage();

    void skipCurrentImage();

    void restartStage();

    void cancel();

    default void jumpToStage(String stageKey) {
    }

    default void registerPreviewButton(JButton button) {
    }

    default void setPreviewButtonStale(boolean stale) {
    }

    default void setPreviewButtonRunning(boolean running) {
    }
}
