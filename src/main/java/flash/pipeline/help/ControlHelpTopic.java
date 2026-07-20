package flash.pipeline.help;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Immutable content contract for the in-depth "controls manual" shown beside a
 * single analysis.
 *
 * <p>This is the manual-style companion to {@link AnalysisHelpTopic}: where the
 * overview card answers "what is this analysis and what does it produce", this
 * type answers "what does every individual toggle, choice, and field on the
 * dialog actually do". Controls are grouped to mirror the on-screen section
 * headers, and each control is rendered as one collapsible entry.
 */
public final class ControlHelpTopic {

    public final int analysisIndex;
    public final String key;
    public final String title;
    public final List<Group> groups;

    public ControlHelpTopic(int analysisIndex, String key, String title, List<Group> groups) {
        this.analysisIndex = analysisIndex;
        this.key = requireText(key, "key");
        this.title = requireText(title, "title");
        this.groups = immutableGroups(groups);
    }

    /** Total number of documented controls across every group. */
    public int controlCount() {
        int count = 0;
        for (Group group : groups) {
            count += group.controls.size();
        }
        return count;
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value;
    }

    private static List<Group> immutableGroups(List<Group> values) {
        if (values == null || values.isEmpty()) {
            throw new IllegalArgumentException("groups must not be empty");
        }
        List<Group> copy = new ArrayList<Group>();
        for (Group value : values) {
            if (value == null) {
                throw new IllegalArgumentException("groups must not contain null entries");
            }
            copy.add(value);
        }
        return Collections.unmodifiableList(copy);
    }

    /**
     * One on-screen section of the dialog. The {@code heading} mirrors the bold
     * section header the user sees; use a short synthetic heading (e.g.
     * "General") for controls that sit above the first real header.
     */
    public static final class Group {
        public final String heading;
        public final String intro;
        public final List<Control> controls;

        public Group(String heading, List<Control> controls) {
            this(heading, null, controls);
        }

        public Group(String heading, String intro, List<Control> controls) {
            this.heading = requireText(heading, "heading");
            this.intro = intro == null || intro.trim().isEmpty() ? null : intro.trim();
            this.controls = immutableControls(controls);
        }

        private static List<Control> immutableControls(List<Control> values) {
            if (values == null || values.isEmpty()) {
                throw new IllegalArgumentException("a group must contain at least one control");
            }
            List<Control> copy = new ArrayList<Control>();
            for (Control value : values) {
                if (value == null) {
                    throw new IllegalArgumentException("controls must not contain null entries");
                }
                copy.add(value);
            }
            return Collections.unmodifiableList(copy);
        }
    }

    /**
     * One documented control. {@code label} must match the on-screen label
     * verbatim so users can map the manual entry to the widget in front of them.
     * {@code badge} is an optional short descriptor such as "Toggle - default
     * On" or "Dropdown". {@code summary} is a one-line lead; {@code details} are
     * the manual bullets (what it does, when to enable it, what it costs, which
     * outputs it changes).
     */
    public static final class Control {
        public final String label;
        public final String badge;
        public final String summary;
        public final List<String> details;

        public Control(String label, String badge, String summary, List<String> details) {
            this.label = requireText(label, "label");
            this.badge = badge == null || badge.trim().isEmpty() ? null : badge.trim();
            this.summary = requireText(summary, "summary");
            this.details = immutableStrings(details);
        }

        private static List<String> immutableStrings(List<String> values) {
            if (values == null) {
                return Collections.emptyList();
            }
            List<String> copy = new ArrayList<String>();
            for (String value : values) {
                if (value == null || value.trim().isEmpty()) {
                    throw new IllegalArgumentException("control details must not contain blank entries");
                }
                copy.add(value);
            }
            return Collections.unmodifiableList(copy);
        }
    }
}
