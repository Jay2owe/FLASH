package flash.pipeline.ui.variations;

import java.util.Locale;

public enum ParameterId implements ParameterKey {
    THRESHOLD,
    MIN_SIZE,
    MAX_SIZE,

    PROB_THRESH,
    NMS_THRESH,
    LINKING_MAX,
    GAP_CLOSING_MAX,
    FRAME_GAP,
    AREA_MIN,
    AREA_MAX,
    QUALITY_MIN,
    INTENSITY_MIN,

    DIAMETER,
    FLOW_THRESHOLD,
    CELLPROB_THRESHOLD,
    MODEL {
        @Override
        public boolean orderable() {
            return false;
        }
    };

    @Override
    public String stableKey() {
        if (this == THRESHOLD) return "threshold";
        if (this == MIN_SIZE) return "min_size";
        if (this == MAX_SIZE) return "max_size";
        if (this == PROB_THRESH) return "probability_threshold";
        if (this == NMS_THRESH) return "nms_threshold";
        if (this == LINKING_MAX) return "linking_max_distance";
        if (this == GAP_CLOSING_MAX) return "gap_closing_max_distance";
        if (this == FRAME_GAP) return "frame_gap";
        if (this == AREA_MIN) return "area_min";
        if (this == AREA_MAX) return "area_max";
        if (this == QUALITY_MIN) return "quality_min";
        if (this == INTENSITY_MIN) return "intensity_min";
        if (this == DIAMETER) return "diameter";
        if (this == FLOW_THRESHOLD) return "flow_threshold";
        if (this == CELLPROB_THRESHOLD) return "cellprob_threshold";
        if (this == MODEL) return "model";
        return name().toLowerCase(Locale.ROOT);
    }

    @Override
    public String displayLabel() {
        if (this == THRESHOLD) return "threshold";
        if (this == MIN_SIZE) return "minimum size";
        if (this == MAX_SIZE) return "maximum size";
        if (this == PROB_THRESH) return "probability threshold";
        if (this == NMS_THRESH) return "nms threshold";
        if (this == LINKING_MAX) return "linking max distance";
        if (this == GAP_CLOSING_MAX) return "gap closing max distance";
        if (this == FRAME_GAP) return "frame gap";
        if (this == AREA_MIN) return "area minimum";
        if (this == AREA_MAX) return "area maximum";
        if (this == QUALITY_MIN) return "quality minimum";
        if (this == INTENSITY_MIN) return "intensity minimum";
        if (this == DIAMETER) return "diameter";
        if (this == FLOW_THRESHOLD) return "flow threshold";
        if (this == CELLPROB_THRESHOLD) return "cellprob threshold";
        if (this == MODEL) return "model";
        return name();
    }

    @Override
    public ValueKind valueKind() {
        return this == MODEL ? ValueKind.STRING : ValueKind.NUMBER;
    }

    public boolean orderable() {
        return true;
    }

    public static ParameterId fromStableKey(String key) {
        if (key == null) {
            return null;
        }
        String trimmed = key.trim();
        String normalized = trimmed.toLowerCase(Locale.ROOT);
        for (ParameterId id : values()) {
            if (id.name().equals(trimmed)
                    || id.name().toLowerCase(Locale.ROOT).equals(normalized)
                    || id.stableKey().equals(normalized)) {
                return id;
            }
        }
        if ("prob_thresh".equals(normalized)) return PROB_THRESH;
        if ("nms_thresh".equals(normalized)) return NMS_THRESH;
        if ("linking_max".equals(normalized)) return LINKING_MAX;
        if ("gap_closing_max".equals(normalized)) return GAP_CLOSING_MAX;
        return null;
    }
}
