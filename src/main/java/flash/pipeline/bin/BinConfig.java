package flash.pipeline.bin;

import flash.pipeline.segmentation.SegmentationMethod;
import flash.pipeline.segmentation.SegmentationTokenParser;
import flash.pipeline.segmentation.StarDistLinkingParams;
import flash.pipeline.segmentation.StarDistPostFilters;
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
    public static final String DEFAULT_CELLPOSE_MODEL = SegmentationMethod.DEFAULT_CELLPOSE_MODEL_KEY;
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
    public boolean clickConfigPresent = false;
    public final Map<Integer, ZSliceSelection> zSliceSelections = new LinkedHashMap<Integer, ZSliceSelection>();
    private final List<SegmentationMethod> parsedSegmentationMethods = new ArrayList<SegmentationMethod>();

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

    public boolean hasClickConfig() {
        return clickConfigPresent;
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
        return segmentationMethod(channelIndex).isStarDist();
    }

    /** Returns true if the channel at the given 0-based index uses Cellpose segmentation. */
    public boolean isCellpose(int channelIndex) {
        return segmentationMethod(channelIndex).isCellpose();
    }

    /** Returns true if the channel at the given 0-based index uses Enhanced Classical segmentation. */
    public boolean isEnhancedClassical(int channelIndex) {
        return segmentationMethod(channelIndex).isEnhancedClassical();
    }

    /** Returns true if the channel produces a label image directly (StarDist or Cellpose). */
    public boolean usesLabelImageSegmentation(int channelIndex) {
        return isStarDist(channelIndex) || isCellpose(channelIndex);
    }

    public int getEnhancedClassicalThreshold(int channelIndex) {
        return (int) Math.round(SegmentationMethod.threshold(segmentationMethod(channelIndex)));
    }

    public int getEnhancedClassicalMinSize(int channelIndex) {
        return SegmentationMethod.minSize(segmentationMethod(channelIndex));
    }

    public int getEnhancedClassicalMaxSize(int channelIndex) {
        return SegmentationMethod.maxSize(segmentationMethod(channelIndex));
    }

    public List<flash.pipeline.segmentation.MorphPredicate> getEnhancedClassicalMorphPredicates(int channelIndex) {
        return SegmentationMethod.morphPredicates(segmentationMethod(channelIndex));
    }

    /** Returns the StarDist probability threshold for the given channel, or 0.5 if not StarDist. */
    public double getStarDistProbThresh(int channelIndex) {
        return SegmentationMethod.starDistProb(segmentationMethod(channelIndex));
    }

    /** Returns the StarDist NMS threshold for the given channel, or 0.4 if not StarDist. */
    public double getStarDistNmsThresh(int channelIndex) {
        return SegmentationMethod.starDistNms(segmentationMethod(channelIndex));
    }

    /** Returns the TrackMate linking max distance for the given StarDist channel, or 5.0 by default. */
    public double getStarDistLinkingMaxDistance(int channelIndex) {
        StarDistLinkingParams linking = SegmentationMethod.starDistLinking(segmentationMethod(channelIndex));
        return linking.linkingMaxDistance;
    }

    /** Returns the TrackMate gap-closing max distance for the given StarDist channel, or 5.0 by default. */
    public double getStarDistGapClosingMaxDistance(int channelIndex) {
        StarDistLinkingParams linking = SegmentationMethod.starDistLinking(segmentationMethod(channelIndex));
        return linking.gapClosingMaxDistance;
    }

    /** Returns the TrackMate max frame gap for the given StarDist channel, or 1 by default. */
    public int getStarDistMaxFrameGap(int channelIndex) {
        StarDistLinkingParams linking = SegmentationMethod.starDistLinking(segmentationMethod(channelIndex));
        return linking.maxFrameGap;
    }

    /** Returns the minimum area filter for the given StarDist channel, or 0 (no filter). */
    public double getStarDistAreaMin(int channelIndex) {
        StarDistPostFilters filters = SegmentationMethod.starDistPostFilters(segmentationMethod(channelIndex));
        return filters.areaMin;
    }

    /** Returns the maximum area filter for the given StarDist channel, or Infinity (no filter). */
    public double getStarDistAreaMax(int channelIndex) {
        StarDistPostFilters filters = SegmentationMethod.starDistPostFilters(segmentationMethod(channelIndex));
        return filters.areaMax;
    }

    /** Returns the minimum quality filter for the given StarDist channel, or 0 (no filter). */
    public double getStarDistQualityMin(int channelIndex) {
        StarDistPostFilters filters = SegmentationMethod.starDistPostFilters(segmentationMethod(channelIndex));
        return filters.qualityMin;
    }

    /** Returns the minimum mean-intensity filter for the given StarDist channel, or 0 (no filter). */
    public double getStarDistIntensityMin(int channelIndex) {
        StarDistPostFilters filters = SegmentationMethod.starDistPostFilters(segmentationMethod(channelIndex));
        return filters.intensityMin;
    }

    /** Returns the Cellpose diameter for the given channel, or 30.0 if not Cellpose. */
    public double getCellposeDiameter(int channelIndex) {
        return SegmentationMethod.cellposeDiameter(segmentationMethod(channelIndex));
    }

    /** Returns the Cellpose model for the given channel, or cyto3 if not Cellpose. */
    public String getCellposeModel(int channelIndex) {
        return SegmentationMethod.cellposeModelKey(segmentationMethod(channelIndex));
    }

    /** Returns the Cellpose flow threshold for the given channel, or 0.4 if not Cellpose. */
    public double getCellposeFlowThreshold(int channelIndex) {
        return SegmentationMethod.cellposeFlow(segmentationMethod(channelIndex));
    }

    /** Returns the Cellpose cell probability threshold for the given channel, or 0.0 if not Cellpose. */
    public double getCellposeCellprobThreshold(int channelIndex) {
        return SegmentationMethod.cellposeCellprob(segmentationMethod(channelIndex));
    }

    /** Returns whether GPU should be used for this Cellpose channel. */
    public boolean getCellposeUseGpu(int channelIndex) {
        return SegmentationMethod.cellposeUseGpu(segmentationMethod(channelIndex));
    }

    /** Returns the optional 0-based Cellpose companion channel index, or -1 when not configured. */
    public int getCellposeSecondChannel(int channelIndex) {
        return SegmentationMethod.cellposeChan2(segmentationMethod(channelIndex));
    }

    public SegmentationMethod segmentationMethod(int channelIndex) {
        if (channelIndex < 0 || channelIndex >= segmentationMethods.size()) {
            return SegmentationMethod.classical("classical");
        }
        String raw = segmentationMethods.get(channelIndex);
        if (channelIndex < parsedSegmentationMethods.size()) {
            SegmentationMethod parsed = parsedSegmentationMethods.get(channelIndex);
            if (parsed != null && sameToken(raw, parsed.rawToken)) {
                return parsed;
            }
        }
        return SegmentationTokenParser.parseLenient(raw);
    }

    public void addSegmentationMethodToken(String token) {
        segmentationMethods.add(token);
        parsedSegmentationMethods.add(SegmentationTokenParser.parseLenient(token));
    }

    public void addSegmentationMethod(SegmentationMethod method) {
        SegmentationMethod safe = method == null ? SegmentationMethod.classical("classical") : method;
        segmentationMethods.add(SegmentationTokenParser.format(safe));
        parsedSegmentationMethods.add(safe);
    }

    public void setSegmentationMethod(int channelIndex, SegmentationMethod method) {
        if (channelIndex < 0) return;
        while (segmentationMethods.size() <= channelIndex) {
            addSegmentationMethodToken("classical");
        }
        while (parsedSegmentationMethods.size() <= channelIndex) {
            parsedSegmentationMethods.add(null);
        }
        SegmentationMethod safe = method == null ? SegmentationMethod.classical("classical") : method;
        segmentationMethods.set(channelIndex, SegmentationTokenParser.format(safe));
        parsedSegmentationMethods.set(channelIndex, safe);
    }

    public SegmentationMethod parsedSegmentationMethodForWrite(int channelIndex) {
        if (channelIndex < 0 || channelIndex >= parsedSegmentationMethods.size()) return null;
        return parsedSegmentationMethods.get(channelIndex);
    }

    private static boolean sameToken(String a, String b) {
        String left = a == null ? "" : a.trim();
        String right = b == null ? "" : b.trim();
        return left.equals(right);
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
