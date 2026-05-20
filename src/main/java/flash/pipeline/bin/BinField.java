package flash.pipeline.bin;

import java.util.EnumSet;
import java.util.Set;

public enum BinField {
    CHANNEL_NAMES,
    CHANNEL_COLORS,
    OBJECT_THRESHOLDS,
    PARTICLE_SIZES,
    DISPLAY_MIN_MAX,
    INTENSITY_THRESHOLDS,
    SEGMENTATION_METHODS,
    FILTER_PRESETS,
    Z_SLICE;

    public static Set<BinField> all() {
        return EnumSet.allOf(BinField.class);
    }
}
