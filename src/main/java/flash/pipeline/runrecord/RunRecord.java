package flash.pipeline.runrecord;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One logical run record per analysis invocation. Captures everything needed
 * to reproduce or audit a FLASH analysis run.
 *
 * <p>Mutable public fields mirror {@code ChannelConfig} / {@code ProjectFile}:
 * the {@link RunRecordCodec} reads and writes these directly. Persisted to a
 * JSON Lines file under {@code FlashProjectLayout.runJsonlWriteDir()} where the
 * last complete line is the current record and earlier lines are progress
 * snapshots retained for crash recovery.
 */
public final class RunRecord {

    public static final int SCHEMA_VERSION = 1;

    public static final String STATUS_OK = "ok";
    public static final String STATUS_WARN = "warn";
    public static final String STATUS_FAILED = "failed";

    public int schemaVersion = SCHEMA_VERSION;
    public String runId = "";
    public String parentRunId = "";
    public long startedAtMillis;
    public long finishedAtMillis;
    public String status = STATUS_OK;
    public String analysis = "";
    public int analysisIndex = -1;
    public String analysisLabel = "";
    public String flashVersion = "";
    public String fijiBuild = "";
    public String jdkVersion = "";
    public String osName = "";
    public String biofVersion = "";
    public String projectFileHash = "";
    public String projectRoot = "";
    public String outputRoot = "";
    public Map<String, Object> parameters = new LinkedHashMap<String, Object>();
    public List<InputItem> inputs = new ArrayList<InputItem>();
    public List<OutputItem> outputs = new ArrayList<OutputItem>();
    public List<Message> messages = new ArrayList<Message>();
    public Map<String, Object> extras = new LinkedHashMap<String, Object>();

    /** A single input source the analysis consumed. */
    public static final class InputItem {
        public String path = "";
        public int seriesIndex = -1;
        public String animalId = "";
        public String hemisphere = "";
        public String region = "";
        public String condition = "";
        public String fingerprintMode = "fast"; // "fast" | "full"
        public String fingerprint = "";
        public long sizeBytes = -1L;
        public long lastModifiedMillis = -1L;
        public String status = ""; // "processed" | "skipped" | "failed"
        public long durationMillis = 0L;
        public Map<String, Object> extras = new LinkedHashMap<String, Object>();
    }

    /** A single user-visible output the analysis produced. */
    public static final class OutputItem {
        public String path = "";
        public String kind = ""; // "csv" | "xlsx" | "html" | "tif" | "png" | ...
        public long sizeBytes = -1L;
        public String fingerprint = "";
        public Map<String, Object> extras = new LinkedHashMap<String, Object>();
    }

    /** A warning or error emitted during the run. */
    public static final class Message {
        public String level = "info"; // "info" | "warn" | "error"
        public long atMillis;
        public String text = "";

        public Message() {
        }

        public Message(String level, long atMillis, String text) {
            this.level = level;
            this.atMillis = atMillis;
            this.text = text;
        }
    }
}
