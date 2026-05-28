package flash.pipeline.project;

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
        public String notes;
        public Map<String, Object> extras = new LinkedHashMap<String, Object>();
    }
}
