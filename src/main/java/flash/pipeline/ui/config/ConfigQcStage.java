package flash.pipeline.ui.config;

import flash.pipeline.ui.preview.PreviewPairPanel;

import javax.swing.JComponent;

public interface ConfigQcStage {

    String title();

    default boolean isApplicable(ConfigQcContext context) {
        return true;
    }

    default void onEnter(ConfigQcContext context, PreviewPairPanel preview) {
    }

    JComponent buildControls(ConfigQcContext context, ConfigQcActions actions);

    boolean lockIn(ConfigQcContext context);

    default void skipCurrentImage(ConfigQcContext context) {
    }

    default void restartStage(ConfigQcContext context) {
    }

    default void onLeave(ConfigQcContext context) {
    }
}
