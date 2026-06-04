package flash.pipeline.bin;

import flash.pipeline.zslice.ZSliceMode;
import flash.pipeline.zslice.ZSliceRange;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ChannelConfig {
    public int schemaVersion = 1;
    public String writerId;
    public long writtenAtMillis;
    public List<Channel> channels = new ArrayList<Channel>();
    public ZSliceMode zSliceMode = ZSliceMode.FULL;
    public Map<String, ZSliceRange> zSliceSelections = new LinkedHashMap<String, ZSliceRange>();
    public boolean clickCaptureUsed;
    // Explicit "configuration finished" signal. true only after the final commit
    // write; false when explicitly written incomplete; null when absent (files
    // written before this flag existed). Null falls back to the per-property
    // COMMITTED check so old projects keep loading unchanged.
    public Boolean complete;
    // Runtime-only (never serialized): the schemaVersion that was on disk before
    // decode, and whether a migration ran. Used by ChannelConfigIO.readResult to
    // persist an upgraded file exactly once.
    public int originalSchemaVersion;
    public boolean migrated;
    public Map<String, Object> extras = new LinkedHashMap<String, Object>();

    public static final class Channel {
        public int index;
        public String name;
        public String color;
        public String markerId;
        public String markerShape;
        public boolean markerCrowdingSensitive;
        public String threshold;
        public String size;
        public String minmax;
        public String intensityThreshold;
        public String segmentationMethod;
        public String filterPreset;
        public Map<String, PropertyStatus> status = new LinkedHashMap<String, PropertyStatus>();
        public Map<String, Object> extras = new LinkedHashMap<String, Object>();

        public PropertyStatus statusOf(String prop) {
            PropertyStatus value = status.get(prop);
            return value == null ? PropertyStatus.PENDING : value;
        }
    }

    public enum PropertyStatus {
        PENDING,
        CONFIGURED,
        COMMITTED
    }

    public static final String P_NAME = "name";
    public static final String P_COLOR = "color";
    public static final String P_MARKER = "marker";
    public static final String P_THRESHOLD = "threshold";
    public static final String P_SIZE = "size";
    public static final String P_MINMAX = "minmax";
    public static final String P_INTENSITY = "intensityThreshold";
    public static final String P_SEGMENTATION = "segmentation";
    public static final String P_FILTER = "filter";
}
