package flash.pipeline.ui.config;

import flash.pipeline.ui.preview.PreviewPairPanel;

import javax.swing.JComponent;

public interface ConfigQcStage {

    default String key() {
        return getClass().getName();
    }

    String title();

    default boolean isApplicable(ConfigQcContext context) {
        return true;
    }

    default boolean showPreviewDisplayControls() {
        return true;
    }

    default boolean controlsCanExpand() {
        return false;
    }

    default void onEnter(ConfigQcContext context, PreviewPairPanel preview) {
    }

    JComponent buildControls(ConfigQcContext context, ConfigQcActions actions);

    boolean lockIn(ConfigQcContext context);

    default void skipCurrentImage(ConfigQcContext context) {
    }

    default void restartStage(ConfigQcContext context) {
    }

    default void previousImage(ConfigQcContext context) {
        restartStage(context);
    }

    default void onLeave(ConfigQcContext context) {
    }
}
