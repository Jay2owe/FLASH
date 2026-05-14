package flash.pipeline.ui.variations;

public final class ParameterLabels {

    private ParameterLabels() {
    }

    public static String labelFor(ParameterKey key) {
        return editorLabel(key);
    }

    public static String labelFor(ParameterId id) {
        return editorLabel(id);
    }

    public static String shortKey(ParameterKey key) {
        if (key instanceof ParameterId) {
            return shortKey((ParameterId) key);
        }
        if (key == null) {
            return "";
        }
        String stable = key.stableKey();
        return stable == null || stable.trim().isEmpty()
                ? key.displayLabel()
                : stable;
    }

    public static String shortKey(ParameterId id) {
        if (id == ParameterId.THRESHOLD) return "threshold";
        if (id == ParameterId.MIN_SIZE) return "minSize";
        if (id == ParameterId.MAX_SIZE) return "maxSize";
        if (id == ParameterId.PROB_THRESH) return "probThresh";
        if (id == ParameterId.NMS_THRESH) return "nms";
        if (id == ParameterId.LINKING_MAX) return "linkingMax";
        if (id == ParameterId.GAP_CLOSING_MAX) return "gapClosingMax";
        if (id == ParameterId.FRAME_GAP) return "frameGap";
        if (id == ParameterId.AREA_MIN) return "areaMin";
        if (id == ParameterId.AREA_MAX) return "areaMax";
        if (id == ParameterId.QUALITY_MIN) return "qualityMin";
        if (id == ParameterId.INTENSITY_MIN) return "intensityMin";
        if (id == ParameterId.DIAMETER) return "diameter";
        if (id == ParameterId.FLOW_THRESHOLD) return "flow";
        if (id == ParameterId.CELLPROB_THRESHOLD) return "cellprob";
        if (id == ParameterId.MODEL) return "model";
        if (id == ParameterId.MACRO) return "macro";
        return id == null ? "" : id.name();
    }

    public static String editorLabel(ParameterKey key) {
        if (key instanceof ParameterId) {
            return editorLabel((ParameterId) key);
        }
        return key == null ? "" : key.displayLabel();
    }

    public static String editorLabel(ParameterId id) {
        if (id == ParameterId.THRESHOLD) return "threshold";
        if (id == ParameterId.MIN_SIZE) return "minimum size";
        if (id == ParameterId.MAX_SIZE) return "maximum size";
        if (id == ParameterId.PROB_THRESH) return "probability threshold";
        if (id == ParameterId.NMS_THRESH) return "nms threshold";
        if (id == ParameterId.LINKING_MAX) return "linking max distance";
        if (id == ParameterId.GAP_CLOSING_MAX) return "gap closing max distance";
        if (id == ParameterId.FRAME_GAP) return "frame gap";
        if (id == ParameterId.AREA_MIN) return "area minimum";
        if (id == ParameterId.AREA_MAX) return "area maximum";
        if (id == ParameterId.QUALITY_MIN) return "quality minimum";
        if (id == ParameterId.INTENSITY_MIN) return "intensity minimum";
        if (id == ParameterId.DIAMETER) return "diameter";
        if (id == ParameterId.FLOW_THRESHOLD) return "flow threshold";
        if (id == ParameterId.CELLPROB_THRESHOLD) return "cellprob threshold";
        if (id == ParameterId.MODEL) return "model";
        if (id == ParameterId.MACRO) return "macro";
        return id == null ? "" : id.name();
    }
}
