package flash.pipeline.ui.config;

import flash.pipeline.segmentation.catalog.ModelEntry;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class MissingModelController {
    public static final class State {
        public final boolean missing;
        public final boolean previewBlocked;
        public final String modelKey;
        public final String statusMessage;
        public final List<ModelEntry> replacementChoices;

        State(boolean missing,
              String modelKey,
              String statusMessage,
              List<ModelEntry> replacementChoices) {
            this.missing = missing;
            this.previewBlocked = missing;
            this.modelKey = modelKey == null ? "" : modelKey;
            this.statusMessage = statusMessage == null ? "" : statusMessage;
            this.replacementChoices = replacementChoices == null
                    ? Collections.<ModelEntry>emptyList()
                    : Collections.unmodifiableList(new ArrayList<ModelEntry>(replacementChoices));
        }
    }

    public static final class ValidationResult {
        public final boolean ok;
        public final int exitCode;
        public final String message;

        ValidationResult(boolean ok, int exitCode, String message) {
            this.ok = ok;
            this.exitCode = exitCode;
            this.message = message == null ? "" : message;
        }
    }

    private final ModelEntry.Engine engine;
    private final List<ModelEntry> entries;
    private String selectedModelKey;

    public MissingModelController(ModelEntry.Engine engine,
                                  List<ModelEntry> entries,
                                  String selectedModelKey) {
        this.engine = engine;
        this.entries = entries == null
                ? Collections.<ModelEntry>emptyList()
                : new ArrayList<ModelEntry>(entries);
        this.selectedModelKey = selectedModelKey == null ? "" : selectedModelKey;
    }

    public State state() {
        boolean found = false;
        for (int i = 0; i < entries.size(); i++) {
            ModelEntry entry = entries.get(i);
            if (entry != null && entry.engine == engine
                    && selectedModelKey.equals(entry.modelKey)) {
                found = true;
                break;
            }
        }
        if (found || selectedModelKey.trim().isEmpty()) {
            return new State(false, selectedModelKey, "", replacementChoices());
        }
        return new State(true, selectedModelKey,
                "Cannot run segmentation: model missing.",
                replacementChoices());
    }

    public State pickReplacement(String replacementKey) {
        if (replacementKey != null) {
            for (int i = 0; i < entries.size(); i++) {
                ModelEntry entry = entries.get(i);
                if (entry != null && entry.engine == engine
                        && replacementKey.equals(entry.modelKey)) {
                    selectedModelKey = replacementKey;
                    break;
                }
            }
        }
        return state();
    }

    public ValidationResult validateHeadless() {
        State state = state();
        if (!state.missing) {
            return new ValidationResult(true, 0, "");
        }
        return new ValidationResult(false, 1,
                "Cannot run segmentation: model missing (" + state.modelKey + ").");
    }

    private List<ModelEntry> replacementChoices() {
        List<ModelEntry> out = new ArrayList<ModelEntry>();
        for (int i = 0; i < entries.size(); i++) {
            ModelEntry entry = entries.get(i);
            if (entry != null && entry.engine == engine) {
                out.add(entry);
            }
        }
        return out;
    }
}
