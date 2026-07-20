package flash.pipeline.help;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Immutable content contract for one Set Up Configuration helper topic.
 */
public final class SetupHelpTopic {

    public final String key;
    public final String title;
    public final String summary;
    public final List<Section> sections;

    public SetupHelpTopic(String key, String title, String summary, List<Section> sections) {
        this.key = requireText(key, "key");
        this.title = requireText(title, "title");
        this.summary = requireText(summary, "summary");
        this.sections = immutableSections(sections);
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value;
    }

    private static List<Section> immutableSections(List<Section> values) {
        if (values == null) {
            return Collections.emptyList();
        }
        List<Section> copy = new ArrayList<Section>();
        for (Section value : values) {
            if (value == null) {
                throw new IllegalArgumentException("sections must not contain null entries");
            }
            copy.add(value);
        }
        return Collections.unmodifiableList(copy);
    }

    public static final class Section {
        public final String heading;
        public final List<String> items;

        public Section(String heading, List<String> items) {
            this.heading = requireText(heading, "heading");
            this.items = immutableStrings(items);
        }

        private static List<String> immutableStrings(List<String> values) {
            if (values == null) {
                return Collections.emptyList();
            }
            List<String> copy = new ArrayList<String>();
            for (String value : values) {
                if (value == null || value.trim().isEmpty()) {
                    throw new IllegalArgumentException("section items must not contain blank entries");
                }
                copy.add(value);
            }
            return Collections.unmodifiableList(copy);
        }
    }
}
