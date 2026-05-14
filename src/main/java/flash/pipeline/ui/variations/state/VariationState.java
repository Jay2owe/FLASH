package flash.pipeline.ui.variations.state;

import flash.pipeline.ui.variations.ParameterCombo;
import flash.pipeline.ui.variations.ParameterId;
import flash.pipeline.ui.variations.ParameterSweep;
import flash.pipeline.ui.variations.ParameterValueList;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class VariationState {

    public static final int CURRENT_VERSION = 1;

    private final int version;
    private final ParameterSweep sweep;
    private final List<CompletedCell> completed;
    private final String startedAt;
    private final String updatedAt;

    public VariationState(ParameterSweep sweep,
                          List<CompletedCell> completed,
                          String startedAt,
                          String updatedAt) {
        this(CURRENT_VERSION, sweep, completed, startedAt, updatedAt);
    }

    VariationState(int version,
                   ParameterSweep sweep,
                   List<CompletedCell> completed,
                   String startedAt,
                   String updatedAt) {
        if (sweep == null) {
            throw new IllegalArgumentException("sweep must not be null");
        }
        this.version = version;
        this.sweep = sweep;
        this.completed = Collections.unmodifiableList(copyCompleted(completed));
        this.startedAt = safeTimestamp(startedAt);
        this.updatedAt = safeTimestamp(updatedAt);
    }

    public static VariationState started(ParameterSweep sweep) {
        String now = Instant.now().toString();
        return new VariationState(sweep, Collections.<CompletedCell>emptyList(), now, now);
    }

    public int version() {
        return version;
    }

    public int getVersion() {
        return version;
    }

    public String imageHash() {
        return sweep.sourceImageHash();
    }

    public String getImageHash() {
        return imageHash();
    }

    public String channel() {
        return sweep.channelName();
    }

    public String getChannel() {
        return channel();
    }

    public ParameterSweep.Method method() {
        return sweep.method();
    }

    public ParameterSweep.Method getMethod() {
        return method();
    }

    public String methodLabel() {
        return sweep.method().label();
    }

    public ParameterSweep sweep() {
        return sweep;
    }

    public ParameterSweep getSweep() {
        return sweep;
    }

    public List<CompletedCell> completed() {
        return completed;
    }

    public List<CompletedCell> getCompleted() {
        return completed;
    }

    public String startedAt() {
        return startedAt;
    }

    public String getStartedAt() {
        return startedAt;
    }

    public String updatedAt() {
        return updatedAt;
    }

    public String getUpdatedAt() {
        return updatedAt;
    }

    public boolean isCompatible(ParameterSweep.Method method,
                                String channel,
                                String imageHash) {
        return this.method() == method
                && safe(this.channel()).equals(safe(channel))
                && safe(this.imageHash()).equals(safe(imageHash));
    }

    public Map<String, CompletedCell> completedByComboId() {
        LinkedHashMap<String, CompletedCell> out =
                new LinkedHashMap<String, CompletedCell>();
        for (int i = 0; i < completed.size(); i++) {
            CompletedCell cell = completed.get(i);
            out.put(cell.comboId(), cell);
        }
        return out;
    }

    public CompletedCell completedFor(ParameterCombo combo) {
        String comboId = comboIdFor(sweep, combo);
        if (comboId == null) {
            return null;
        }
        return completedByComboId().get(comboId);
    }

    public VariationState withCompletion(CompletedCell completion, String updatedAt) {
        if (completion == null || completion.comboId().trim().isEmpty()) {
            return this;
        }
        List<CompletedCell> copy = new ArrayList<CompletedCell>(completed.size() + 1);
        boolean replaced = false;
        for (int i = 0; i < completed.size(); i++) {
            CompletedCell existing = completed.get(i);
            if (existing.comboId().equals(completion.comboId())) {
                copy.add(completion);
                replaced = true;
            } else {
                copy.add(existing);
            }
        }
        if (!replaced) {
            copy.add(completion);
        }
        return new VariationState(version, sweep, copy, startedAt, updatedAt);
    }

    public static String comboIdFor(ParameterSweep sweep, ParameterCombo combo) {
        if (sweep == null || combo == null) {
            return null;
        }
        StringBuilder out = new StringBuilder();
        for (Map.Entry<ParameterId, ParameterValueList> entry
                : sweep.valueLists().entrySet()) {
            int index = indexOf(entry.getValue(), combo.get(entry.getKey()));
            if (index < 0) {
                return null;
            }
            if (out.length() > 0) {
                out.append('_');
            }
            out.append(index);
        }
        return out.toString();
    }

    private static int indexOf(ParameterValueList values, Object value) {
        if (values == null) {
            return -1;
        }
        for (int i = 0; i < values.size(); i++) {
            Object candidate = values.get(i);
            if (candidate == null ? value == null : candidate.equals(value)) {
                return i;
            }
            if (candidate instanceof Number && value instanceof Number) {
                double a = ((Number) candidate).doubleValue();
                double b = ((Number) value).doubleValue();
                if (Double.compare(a, b) == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static List<CompletedCell> copyCompleted(List<CompletedCell> source) {
        if (source == null || source.isEmpty()) {
            return new ArrayList<CompletedCell>();
        }
        return new ArrayList<CompletedCell>(source);
    }

    private static String safeTimestamp(String value) {
        String trimmed = value == null ? "" : value.trim();
        return trimmed.isEmpty() ? Instant.now().toString() : trimmed;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    public static final class CompletedCell {
        private final String comboId;
        private final String labelCacheKey;
        private final int nObjects;
        private final long durationMs;

        public CompletedCell(String comboId,
                             String labelCacheKey,
                             int nObjects,
                             long durationMs) {
            this.comboId = comboId == null ? "" : comboId;
            this.labelCacheKey = labelCacheKey == null ? "" : labelCacheKey;
            this.nObjects = nObjects;
            this.durationMs = durationMs;
        }

        public String comboId() {
            return comboId;
        }

        public String getComboId() {
            return comboId;
        }

        public String labelCacheKey() {
            return labelCacheKey;
        }

        public String getLabelCacheKey() {
            return labelCacheKey;
        }

        public int nObjects() {
            return nObjects;
        }

        public int getNObjects() {
            return nObjects;
        }

        public long durationMs() {
            return durationMs;
        }

        public long getDurationMs() {
            return durationMs;
        }
    }
}
