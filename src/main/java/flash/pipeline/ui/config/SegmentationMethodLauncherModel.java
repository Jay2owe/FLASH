package flash.pipeline.ui.config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Separates persisted segmentation-method choices from UI-only launchers.
 */
public final class SegmentationMethodLauncherModel {
    public static final String TRAIN_CUSTOM_ENGINE = "Train Custom Engine...";
    public static final String TRAIN_CUSTOM_ENGINE_DISPLAY = "+ " + TRAIN_CUSTOM_ENGINE;

    private final List<Entry> entries;

    public SegmentationMethodLauncherModel() {
        List<Entry> out = new ArrayList<Entry>();
        out.add(Entry.method(SegmentationMethodStage.CLASSICAL));
        out.add(Entry.method(SegmentationMethodStage.ENHANCED_CLASSICAL));
        out.add(Entry.method(SegmentationMethodStage.STARDIST));
        out.add(Entry.method(SegmentationMethodStage.CELLPOSE));
        out.add(Entry.launcher(TRAIN_CUSTOM_ENGINE, TRAIN_CUSTOM_ENGINE_DISPLAY));
        this.entries = Collections.unmodifiableList(out);
    }

    public List<Entry> entries() {
        return entries;
    }

    public boolean isLauncher(String value) {
        String normalized = normalize(value);
        for (Entry entry : entries) {
            if (entry.launcher && entry.matches(normalized)) {
                return true;
            }
        }
        return false;
    }

    public boolean isKnownMethod(String value) {
        String normalized = normalize(value);
        for (Entry entry : entries) {
            if (!entry.launcher && entry.matches(normalized)) {
                return true;
            }
        }
        return false;
    }

    public String firstKnownMethod(String value) {
        String normalized = normalize(value);
        for (Entry entry : entries) {
            if (!entry.launcher && entry.matches(normalized)) {
                return entry.value;
            }
        }
        return SegmentationMethodStage.CLASSICAL;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    public static final class Entry {
        public final String value;
        public final String displayText;
        public final boolean launcher;

        private Entry(String value, String displayText, boolean launcher) {
            this.value = value == null ? "" : value;
            this.displayText = displayText == null ? this.value : displayText;
            this.launcher = launcher;
        }

        static Entry method(String value) {
            return new Entry(value, value, false);
        }

        static Entry launcher(String value, String displayText) {
            return new Entry(value, displayText, true);
        }

        boolean matches(String normalized) {
            return value.equals(normalized) || displayText.equals(normalized);
        }
    }
}
