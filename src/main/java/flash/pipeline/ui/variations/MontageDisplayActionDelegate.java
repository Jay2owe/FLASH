package flash.pipeline.ui.variations;

public interface MontageDisplayActionDelegate {

    void adjustBrightnessContrast();

    void toggleGreyLut();

    default String lutButtonText() {
        return "Grey LUT";
    }

    default String lutButtonTooltip() {
        return null;
    }
}
