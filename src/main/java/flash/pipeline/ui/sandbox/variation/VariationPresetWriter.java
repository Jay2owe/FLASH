package flash.pipeline.ui.sandbox.variation;

import flash.pipeline.image.variation.VariantPlan;

/**
 * Saves a generated variant as a reusable custom-filter preset.
 *
 * The concrete FLASH binding is supplied later by the caller that knows the
 * channel and bin-folder context. Implementations should be able to persist
 * only the IJM emitted from {@link VariantPlan#dag}; no sidecar DAG file is
 * required by this abstraction.
 */
public interface VariationPresetWriter {
    void savePreset(String presetName, VariantPlan plan) throws Exception;
}
