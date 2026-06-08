package flash.pipeline.project;

import flash.pipeline.naming.ConditionAxis;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * In-memory model of a FLASH project file. Mirrors the
 * {@link flash.pipeline.bin.ChannelConfig} seam: pure data, codec lives in
 * {@link ProjectFileCodec}, atomic disk IO lives in {@link ProjectFileIO}.
 *
 * <p>A project spans an explicit list of source files (possibly across
 * multiple folders) plus an {@link #outputRoot} that decouples the FLASH
 * output tree from any single source folder. Per-item metadata (animal,
 * hemisphere, region, condition) drives downstream aggregation and stats.
 */
public final class ProjectFile {
    public int schemaVersion = 1;
    public String writerId;
    public long writtenAtMillis;
    public String name;
    /**
     * Absolute path to the project output root. {@code null} or empty means
     * "derive from the parent of the project file on disk" — leaving an
     * un-anchored {@code ProjectFile} in memory still valid.
     */
    public String outputRoot;
    public List<Item> items = new ArrayList<Item>();
    /**
     * Ordered schema of condition axes for the multi-condition model
     * (e.g. Genotype, Timepoint). Empty for legacy single-condition projects,
     * which behave as one implicit {@code Condition} axis.
     */
    public List<ConditionAxis> conditionAxes = new ArrayList<ConditionAxis>();
    public Map<String, Object> extras = new LinkedHashMap<String, Object>();

    public static final class Item {
        /** Absolute path to the source file (LIF, TIF, CZI, ...). */
        public String path;
        /**
         * Bio-Formats series indices to include. Empty list means "all series
         * in the file" — the common case for single-series TIFs and for LIFs
         * the user has not narrowed.
         */
        public List<Integer> series = new ArrayList<Integer>();
        public boolean include = true;
        public String animalId;
        public String hemisphere;
        public String region;
        public String condition;
        /** Multi-axis condition values (axisId -&gt; value); empty in single-condition mode. */
        public Map<String, String> conditions = new LinkedHashMap<String, String>();
        public String notes;
        /**
         * Per-series metadata overrides for multi-series container files. Empty
         * for single-series sources (TIFs) and for LIFs the user has not
         * expanded — in that case the file-level fields above apply to every
         * included series. When populated, each entry carries the identity
         * (animal/hemisphere/region/condition) the user assigned to one
         * Bio-Formats series; the file-level fields are then only seeds/defaults.
         */
        public List<SeriesItem> seriesMeta = new ArrayList<SeriesItem>();
        public Map<String, Object> extras = new LinkedHashMap<String, Object>();
    }

    /**
     * Per-series identity assigned in the Project Builder. {@link #index} is the
     * 0-based Bio-Formats series index within the parent {@link Item}; {@link #name}
     * is the raw series name (the join key used to seed the orientation manifest so
     * downstream analyses honour these values instead of parsing the series name).
     */
    public static final class SeriesItem {
        public int index;
        public boolean include = true;
        public String name;
        public String animalId;
        public String hemisphere;
        public String region;
        public String condition;
        /** Multi-axis condition values (axisId -&gt; value); empty in single-condition mode. */
        public Map<String, String> conditions = new LinkedHashMap<String, String>();
        public String notes;
        public Map<String, Object> extras = new LinkedHashMap<String, Object>();
    }
}
