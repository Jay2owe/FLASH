package flash.pipeline.bin;

import flash.pipeline.zslice.ZSliceConfig;
import flash.pipeline.zslice.ZSliceMode;
import flash.pipeline.zslice.ZSliceRange;
import flash.pipeline.zslice.ZSliceSelection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class BinConfig {
    public static final double DEFAULT_STARDIST_PROB_THRESH = 0.5;
    public static final double DEFAULT_STARDIST_NMS_THRESH = 0.4;
    public static final double DEFAULT_STARDIST_LINKING_MAX_DISTANCE = 5.0;
    public static final double DEFAULT_STARDIST_GAP_CLOSING_MAX_DISTANCE = 5.0;
    public static final int DEFAULT_STARDIST_MAX_FRAME_GAP = 1;
    public static final double DEFAULT_CELLPOSE_DIAMETER = 30.0;
    public static final String DEFAULT_CELLPOSE_MODEL = "cyto3";
    public static final double DEFAULT_CELLPOSE_FLOW_THRESHOLD = 0.4;
    public static final double DEFAULT_CELLPOSE_CELLPROB_THRESHOLD = 0.0;
    public static final boolean DEFAULT_CELLPOSE_USE_GPU = true;

    public final List<String> channelNames = new ArrayList<>();
    public final List<String> channelColors = new ArrayList<>();
    public final List<String> channelThresholds = new ArrayList<>();       // object threshold: "default" or number
    public final List<String> channelSizes = new ArrayList<>();            // particle size: "min-max"
    public final List<String> channelMinMax = new ArrayList<>();           // display range: "None" or "min-max"
    public final List<String> channelIntensityThresholds = new ArrayList<>(); // intensity threshold: "default" or number
    public final List<String> segmentationMethods = new ArrayList<>();
    public final List<String> channelFilterPresets = new ArrayList<>();
    public ZSliceMode zSliceMode = ZSliceMode.FULL;
    public boolean zSliceConfigPresent = false;
    public final Map<Integer, ZSliceSelection> zSliceSelections = new LinkedHashMap<Integer, ZSliceSelection>();

    public int numChannels() {
        return channelNames.size();
    }

    public boolean hasChannelNames() {
        return !channelNames.isEmpty();
    }

    public boolean hasChannelColors() {
        return hasCompleteNonEmptyChannelList(channelColors);
    }

    public boolean hasChannelThresholds() {
        return hasCompleteNonEmptyChannelList(channelThresholds);
    }

    public boolean hasChannelSizes() {
        return hasCompleteNonEmptyChannelList(channelSizes);
    }

    public boolean hasChannelMinMax() {
        return hasCompleteNonEmptyChannelList(channelMinMax);
    }

    public boolean hasChannelIntensityThresholds() {
        return hasCompleteNonEmptyChannelList(channelIntensityThresholds);
    }

    public boolean hasSegmentationMethods() {
        return hasCompleteNonEmptyChannelList(segmentationMethods);
    }

    public boolean hasChannelFilterPresets() {
        return hasCompleteNonEmptyChannelList(channelFilterPresets);
    }

    public boolean hasZSliceConfig() {
        return zSliceConfigPresent;
    }

    private boolean hasCompleteNonEmptyChannelList(List<String> values) {
        if (values == null || values.size() != numChannels() || values.isEmpty()) return false;
        for (String value : values) {
            if (value == null || value.trim().isEmpty()) return false;
        }
        return true;
    }

    /** Macro uses 1-based C1_Filters.ijm, C2_Filters.ijm ... */
    public String filterMacroFilenameForChannelIndex(int channelIndex0) {
        return "C" + (channelIndex0 + 1) + "_Filters.ijm";
    }

    /** Returns true if the channel at the given 0-based index uses StarDist 3D segmentation. */
    public boolean isStarDist(int channelIndex) {
        if (channelIndex < 0 || channelIndex >= segmentationMethods.size()) return false;
        return segmentationMethods.get(channelIndex).startsWith("stardist");
    }

    /** Returns true if the channel at the given 0-based index uses Cellpose segmentation. */
    public boolean isCellpose(int channelIndex) {
        if (channelIndex < 0 || channelIndex >= segmentationMethods.size()) return false;
        return segmentationMethods.get(channelIndex).startsWith("cellpose");
    }

    /** Returns true if the channel produces a label image directly (StarDist or Cellpose). */
    public boolean usesLabelImageSegmentation(int channelIndex) {
        return isStarDist(channelIndex) || isCellpose(channelIndex);
    }

    /** Returns the StarDist probability threshold for the given channel, or 0.5 if not StarDist. */
    public double getStarDistProbThresh(int channelIndex) {
        if (!isStarDist(channelIndex)) return DEFAULT_STARDIST_PROB_THRESH;
        String[] parts = segmentationMethods.get(channelIndex).split(":");
        if (parts.length >= 2) {
            try { return Double.parseDouble(parts[1]); } catch (NumberFormatException e) { /* fall through */ }
        }
        return DEFAULT_STARDIST_PROB_THRESH;
    }

    /** Returns the StarDist NMS threshold for the given channel, or 0.4 if not StarDist. */
    public double getStarDistNmsThresh(int channelIndex) {
        if (!isStarDist(channelIndex)) return DEFAULT_STARDIST_NMS_THRESH;
        String[] parts = segmentationMethods.get(channelIndex).split(":");
        if (parts.length >= 3) {
            try { return Double.parseDouble(parts[2]); } catch (NumberFormatException e) { /* fall through */ }
        }
        return DEFAULT_STARDIST_NMS_THRESH;
    }

    /** Returns the TrackMate linking max distance for the given StarDist channel, or 5.0 by default. */
    public double getStarDistLinkingMaxDistance(int channelIndex) {
        return Math.max(0, getStarDistKeyValue(channelIndex, "linking",
                DEFAULT_STARDIST_LINKING_MAX_DISTANCE, true));
    }

    /** Returns the TrackMate gap-closing max distance for the given StarDist channel, or 5.0 by default. */
    public double getStarDistGapClosingMaxDistance(int channelIndex) {
        return Math.max(0, getStarDistKeyValue(channelIndex, "gapClosing",
                DEFAULT_STARDIST_GAP_CLOSING_MAX_DISTANCE, true));
    }

    /** Returns the TrackMate max frame gap for the given StarDist channel, or 1 by default. */
    public int getStarDistMaxFrameGap(int channelIndex) {
        double value = getStarDistKeyValue(channelIndex, "frameGap", DEFAULT_STARDIST_MAX_FRAME_GAP, true);
        return (int) Math.max(0, Math.round(value));
    }

    /** Returns the minimum area filter for the given StarDist channel, or 0 (no filter). */
    public double getStarDistAreaMin(int channelIndex) {
        return getStarDistKeyValue(channelIndex, "area", 0, true);
    }

    /** Returns the maximum area filter for the given StarDist channel, or Infinity (no filter). */
    public double getStarDistAreaMax(int channelIndex) {
        return getStarDistKeyValue(channelIndex, "area", Double.POSITIVE_INFINITY, false);
    }

    /** Returns the minimum quality filter for the given StarDist channel, or 0 (no filter). */
    public double getStarDistQualityMin(int channelIndex) {
        return getStarDistKeyValue(channelIndex, "quality", 0, true);
    }

    /** Returns the minimum mean-intensity filter for the given StarDist channel, or 0 (no filter). */
    public double getStarDistIntensityMin(int channelIndex) {
        return getStarDistKeyValue(channelIndex, "intensity", 0, true);
    }

    /** Returns the Cellpose diameter for the given channel, or 30.0 if not Cellpose. */
    public double getCellposeDiameter(int channelIndex) {
        if (!isCellpose(channelIndex)) return DEFAULT_CELLPOSE_DIAMETER;
        String[] parts = segmentationMethods.get(channelIndex).split(":");
        if (parts.length >= 2) {
            try { return Double.parseDouble(parts[1]); } catch (NumberFormatException e) { /* fall through */ }
        }
        return DEFAULT_CELLPOSE_DIAMETER;
    }

    /** Returns the Cellpose model for the given channel, or cyto3 if not Cellpose. */
    public String getCellposeModel(int channelIndex) {
        if (!isCellpose(channelIndex)) return DEFAULT_CELLPOSE_MODEL;
        String[] parts = segmentationMethods.get(channelIndex).split(":");
        if (parts.length >= 3 && parts[2] != null && !parts[2].trim().isEmpty()) {
            return parts[2].trim();
        }
        return DEFAULT_CELLPOSE_MODEL;
    }

    /** Returns the Cellpose flow threshold for the given channel, or 0.4 if not Cellpose. */
    public double getCellposeFlowThreshold(int channelIndex) {
        if (!isCellpose(channelIndex)) return DEFAULT_CELLPOSE_FLOW_THRESHOLD;
        String[] parts = segmentationMethods.get(channelIndex).split(":");
        if (parts.length >= 4) {
            try { return Double.parseDouble(parts[3]); } catch (NumberFormatException e) { /* fall through */ }
        }
        return DEFAULT_CELLPOSE_FLOW_THRESHOLD;
    }

    /** Returns the Cellpose cell probability threshold for the given channel, or 0.0 if not Cellpose. */
    public double getCellposeCellprobThreshold(int channelIndex) {
        if (!isCellpose(channelIndex)) return DEFAULT_CELLPOSE_CELLPROB_THRESHOLD;
        String[] parts = segmentationMethods.get(channelIndex).split(":");
        if (parts.length >= 5) {
            try { return Double.parseDouble(parts[4]); } catch (NumberFormatException e) { /* fall through */ }
        }
        return DEFAULT_CELLPOSE_CELLPROB_THRESHOLD;
    }

    /** Returns whether GPU should be used for this Cellpose channel. */
    public boolean getCellposeUseGpu(int channelIndex) {
        if (!isCellpose(channelIndex)) return DEFAULT_CELLPOSE_USE_GPU;
        String value = getCellposeKeyValue(channelIndex, "gpu");
        if (value != null) return !"false".equalsIgnoreCase(value);
        return DEFAULT_CELLPOSE_USE_GPU;
    }

    /** Returns the optional 0-based Cellpose companion channel index, or -1 when not configured. */
    public int getCellposeSecondChannel(int channelIndex) {
        if (!isCellpose(channelIndex)) return -1;
        String value = getCellposeKeyValue(channelIndex, "chan2");
        if (value == null || value.trim().isEmpty()) return -1;
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * Parses a key=value pair from the segmentation method string.
     * Format:
     * stardist:prob:nms:linking=d:gapClosing=d:frameGap=n:area=min-max:quality=min:intensity=min
     *
     * @param channelIndex 0-based channel index
     * @param key          the key to look for (e.g. "linking", "gapClosing", "frameGap", "area")
     * @param defaultValue value to return if key is not found
     * @param firstHalf    for range values like "min-max", true returns min, false returns max
     */
    private double getStarDistKeyValue(int channelIndex, String key, double defaultValue, boolean firstHalf) {
        if (!isStarDist(channelIndex)) return defaultValue;
        String method = segmentationMethods.get(channelIndex);
        // Split on ':' — first 3 parts are stardist:prob:nms, rest are key=value pairs
        String[] parts = method.split(":");
        for (int i = 3; i < parts.length; i++) {
            if (parts[i].startsWith(key + "=")) {
                String val = parts[i].substring(key.length() + 1);
                if (val.contains("-")) {
                    String[] range = val.split("-", 2);
                    try {
                        if (firstHalf) return Double.parseDouble(range[0]);
                        else {
                            if ("Infinity".equalsIgnoreCase(range[1])) return Double.POSITIVE_INFINITY;
                            return Double.parseDouble(range[1]);
                        }
                    } catch (NumberFormatException e) { return defaultValue; }
                } else {
                    try { return Double.parseDouble(val); } catch (NumberFormatException e) { return defaultValue; }
                }
            }
        }
        return defaultValue;
    }

    private String getCellposeKeyValue(int channelIndex, String key) {
        if (!isCellpose(channelIndex) || key == null || key.trim().isEmpty()) return null;
        String method = segmentationMethods.get(channelIndex);
        String[] parts = method.split(":");
        for (int i = 5; i < parts.length; i++) {
            if (parts[i].startsWith(key + "=")) {
                return parts[i].substring(key.length() + 1).trim();
            }
        }
        return null;
    }

    public boolean usesZSliceSubset() {
        return zSliceMode.usesSubset() && !zSliceSelections.isEmpty();
    }

    public ZSliceSelection getZSliceSelection(int seriesIndex) {
        return zSliceSelections.get(seriesIndex);
    }

    public ZSliceRange getZSliceRange(int seriesIndex) {
        ZSliceSelection selection = getZSliceSelection(seriesIndex);
        return selection == null ? null : selection.range;
    }

    public ZSliceConfig getZSliceConfig() {
        return new ZSliceConfig(zSliceMode, zSliceSelections);
    }
}
