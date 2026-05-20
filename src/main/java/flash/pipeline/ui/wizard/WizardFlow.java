package flash.pipeline.ui.wizard;

import flash.pipeline.ui.PipelineDialog;

import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JTextField;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Small screen-by-screen framework on top of {@link PipelineDialog}.
 */
public abstract class WizardFlow {

    private final String title;
    private final MainPanelBinding panel;
    private final boolean headless;
    private final List<Screen> screens = new ArrayList<Screen>();
    private final AnswerMap answers = new AnswerMap();
    private boolean finished;
    private boolean cancelled;

    protected WizardFlow(String title, MainPanelBinding panel, boolean headless) {
        this.title = title == null ? "Setup Helper" : title;
        this.panel = panel == null ? MainPanelBinding.NULL : panel;
        this.headless = headless;
    }

    protected final void register(Screen screen) {
        if (screen == null) {
            throw new IllegalArgumentException("screen is required.");
        }
        screens.add(screen);
        answers.putDefaults(screen.defaultAnswers());
    }

    public final AnswerMap run() {
        finished = false;
        cancelled = false;
        if (headless) {
            finished = true;
            return answers.copy();
        }

        int screenIndex = firstApplicableIndex(0);
        if (screenIndex < 0) {
            finish();
            return answers.copy();
        }

        while (screenIndex >= 0 && screenIndex < screens.size()) {
            Screen screen = screens.get(screenIndex);
            if (!screen.isApplicable(answers)) {
                screenIndex = nextApplicableIndex(screenIndex + 1);
                continue;
            }

            ScreenResult result = showScreen(screen, screenIndex, isLastApplicable(screenIndex));
            if (result == ScreenResult.CANCEL) {
                cancelled = true;
                return answers.copy();
            }
            if (result == ScreenResult.BACK) {
                int previous = previousApplicableIndex(screenIndex - 1);
                screenIndex = previous < 0 ? screenIndex : previous;
                continue;
            }
            if (isLastApplicable(screenIndex)) {
                finish();
                return answers.copy();
            }
            screenIndex = nextApplicableIndex(screenIndex + 1);
        }

        finish();
        return answers.copy();
    }

    public final boolean wasFinished() {
        return finished;
    }

    public final boolean wasCancelled() {
        return cancelled;
    }

    protected ScreenResult showScreen(Screen screen, int screenIndex, boolean finish) {
        PipelineDialog dialog = createDialog(screen, screenIndex, finish);
        screen.build(dialog, answers);
        boolean ok = dialog.showDialog();
        if (!ok) {
            if (dialog.wasBackPressed()) {
                screen.read(dialog, answers);
                return ScreenResult.BACK;
            }
            return ScreenResult.CANCEL;
        }
        screen.read(dialog, answers);
        return ScreenResult.NEXT;
    }

    protected PipelineDialog createDialog(Screen screen, int screenIndex, boolean finish) {
        PipelineDialog dialog = new PipelineDialog(title + " - " + screen.title());
        if (previousApplicableIndex(screenIndex - 1) >= 0) {
            dialog.enableBackButton();
        }
        return dialog;
    }

    protected final void finish() {
        for (Screen screen : screens) {
            if (!screen.isApplicable(answers)) {
                continue;
            }
            screen.writeTo(panel, answers);
            for (String fieldId : screen.writebackFieldIds()) {
                panel.markRecommended(fieldId);
            }
        }
        finished = true;
        cancelled = false;
    }

    public final AnswerMap currentAnswers() {
        return answers.copy();
    }

    protected final void putAnswer(String fieldId, Object value) {
        answers.put(fieldId, value);
    }

    private int firstApplicableIndex(int start) {
        return nextApplicableIndex(start);
    }

    private int nextApplicableIndex(int start) {
        for (int i = Math.max(0, start); i < screens.size(); i++) {
            if (screens.get(i).isApplicable(answers)) {
                return i;
            }
        }
        return -1;
    }

    private int previousApplicableIndex(int start) {
        for (int i = Math.min(start, screens.size() - 1); i >= 0; i--) {
            if (screens.get(i).isApplicable(answers)) {
                return i;
            }
        }
        return -1;
    }

    private boolean isLastApplicable(int index) {
        return nextApplicableIndex(index + 1) < 0;
    }

    public enum ScreenResult {
        NEXT,
        BACK,
        CANCEL
    }

    public interface MainPanelBinding {
        MainPanelBinding NULL = new MainPanelBinding() {
            @Override public void setValue(String fieldId, Object value) {}
            @Override public Object getValue(String fieldId) { return null; }
            @Override public void markRecommended(String fieldId) {}
        };

        void setValue(String fieldId, Object value);
        Object getValue(String fieldId);
        void markRecommended(String fieldId);
    }

    public static class AnswerMap extends LinkedHashMap<String, Object> {
        public AnswerMap() {
            super();
        }

        public AnswerMap(Map<String, Object> source) {
            super(source == null ? Collections.<String, Object>emptyMap() : source);
        }

        public String getString(String key, String fallback) {
            Object value = get(key);
            return value == null ? fallback : String.valueOf(value);
        }

        public boolean getBoolean(String key, boolean fallback) {
            Object value = get(key);
            if (value instanceof Boolean) return ((Boolean) value).booleanValue();
            if (value == null) return fallback;
            return Boolean.parseBoolean(String.valueOf(value));
        }

        public int getInt(String key, int fallback) {
            Object value = get(key);
            if (value instanceof Number) return ((Number) value).intValue();
            if (value == null) return fallback;
            try {
                return Integer.parseInt(String.valueOf(value));
            } catch (NumberFormatException e) {
                return fallback;
            }
        }

        void putDefaults(Map<String, Object> defaults) {
            if (defaults == null) return;
            for (Map.Entry<String, Object> entry : defaults.entrySet()) {
                if (!containsKey(entry.getKey())) {
                    put(entry.getKey(), entry.getValue());
                }
            }
        }

        public AnswerMap copy() {
            return new AnswerMap(this);
        }
    }

    public abstract static class Screen {
        private final String title;
        private final Map<String, Object> defaults = new LinkedHashMap<String, Object>();
        private final Set<String> writebackFieldIds = new LinkedHashSet<String>();

        protected Screen(String title) {
            this.title = title == null ? "" : title;
        }

        public final String title() {
            return title;
        }

        protected final void defaultAnswer(String fieldId, Object value) {
            defaults.put(fieldId, value);
            writebackFieldIds.add(fieldId);
        }

        protected final void writebackField(String fieldId) {
            if (fieldId != null) {
                writebackFieldIds.add(fieldId);
            }
        }

        public Map<String, Object> defaultAnswers() {
            return Collections.unmodifiableMap(defaults);
        }

        public Collection<String> writebackFieldIds() {
            return Collections.unmodifiableSet(writebackFieldIds);
        }

        public boolean isApplicable(AnswerMap prior) {
            return true;
        }

        public abstract void build(PipelineDialog dialog, AnswerMap answers);

        public abstract void read(PipelineDialog dialog, AnswerMap answers);

        public abstract void writeTo(MainPanelBinding panel, AnswerMap answers);

        protected JTextField addStringField(PipelineDialog dialog,
                                            String fieldId,
                                            String label,
                                            AnswerMap answers,
                                            int columns) {
            Object current = answers.get(fieldId);
            JTextField field = dialog.addStringField(label, current == null ? "" : String.valueOf(current), columns);
            field.setName(fieldId);
            return field;
        }

        protected JComboBox<String> addChoice(PipelineDialog dialog,
                                              String fieldId,
                                              String label,
                                              String[] items,
                                              AnswerMap answers) {
            Object current = answers.get(fieldId);
            JComboBox<String> combo = dialog.addChoice(label, items, current == null ? null : String.valueOf(current));
            combo.setName(fieldId);
            return combo;
        }

        protected void putComponentValue(AnswerMap answers, String fieldId, JComponent component) {
            if (component instanceof JTextField) {
                answers.put(fieldId, ((JTextField) component).getText());
            } else if (component instanceof JComboBox) {
                answers.put(fieldId, ((JComboBox<?>) component).getSelectedItem());
            }
        }
    }
}
