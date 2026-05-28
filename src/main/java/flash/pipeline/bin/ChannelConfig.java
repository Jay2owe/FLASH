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
