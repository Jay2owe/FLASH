package flash.pipeline.help;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Immutable content contract for one per-analysis help topic.
 *
 * <p>The card is deliberately terse: a one or two sentence {@code whatItDoes}
 * lead, the concept {@code images} under it, then compact {@code needsFirst}
 * and {@code produces} lines, the auto {@code workflow} flow-diagram, and one
 * or two {@code watchOut} pitfalls. The older verbose When-to-use / Inputs /
 * Setup / Outputs / Pitfalls walls were retired in favour of this layout.
 */
public final class AnalysisHelpTopic {

    public final int analysisIndex;
    public final String key;
    public final String title;
    public final String whatItDoes;
    public final List<String> needsFirst;
    public final List<String> produces;
    public final List<String> workflow;
    public final List<String> watchOut;
    public final List<HelpImage> images;

    public AnalysisHelpTopic(int analysisIndex,
                             String key,
                             String title,
                             String whatItDoes,
                             List<String> needsFirst,
                             List<String> produces,
                             List<String> workflow,
                             List<String> watchOut,
                             List<HelpImage> images) {
        this.analysisIndex = analysisIndex;
        this.key = requireText(key, "key");
        this.title = requireText(title, "title");
        this.whatItDoes = requireText(whatItDoes, "whatItDoes");
        this.needsFirst = immutableStrings(needsFirst, "needsFirst");
        this.produces = immutableStrings(produces, "produces");
        this.workflow = immutableStrings(workflow, "workflow");
        this.watchOut = immutableStrings(watchOut, "watchOut");
        this.images = immutableImages(images);
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value;
    }

    private static List<String> immutableStrings(List<String> values, String fieldName) {
        if (values == null) {
            return Collections.emptyList();
        }
        List<String> copy = new ArrayList<String>();
        for (String value : values) {
            if (value == null || value.trim().isEmpty()) {
                throw new IllegalArgumentException(fieldName + " must not contain blank entries");
            }
            copy.add(value);
        }
        return Collections.unmodifiableList(copy);
    }

    private static List<HelpImage> immutableImages(List<HelpImage> values) {
        if (values == null) {
            return Collections.emptyList();
        }
        List<HelpImage> copy = new ArrayList<HelpImage>();
        for (HelpImage value : values) {
            if (value == null) {
                throw new IllegalArgumentException("images must not contain null entries");
            }
            copy.add(value);
        }
        return Collections.unmodifiableList(copy);
    }

    public static final class HelpImage {
        public final String resourcePath;
        public final String title;
        public final String caption;
        public final boolean optional;

        public HelpImage(String resourcePath, String title, String caption) {
            this(resourcePath, title, caption, false);
        }

        public HelpImage(String resourcePath, String title, String caption, boolean optional) {
            this.resourcePath = requireText(resourcePath, "resourcePath");
            this.title = requireText(title, "title");
            this.caption = requireText(caption, "caption");
            this.optional = optional;
        }

        public boolean isOptional() {
            return optional;
        }
    }
}
