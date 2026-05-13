package flash.pipeline.ui.sandbox.variation;

import flash.pipeline.image.variation.VariantPlan;

public interface TileActionListener {
    void onPromote(VariantPlan plan);
    void onSavePreset(VariantPlan plan);
}
