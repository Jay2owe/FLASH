package flash.pipeline.help;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Immutable content contract for one per-analysis help topic.
 */
public final class AnalysisHelpTopic {

    public final int analysisIndex;
    public final String key;
    public final String title;
    public final String summary;
    public final List<String> whenToUse;
    public final List<String> inputs;
    public final List<String> setup;
    public final List<String> workflow;
    public final List<String> outputs;
    public final List<String> pitfalls;
    public final List<HelpImage> images;

    public AnalysisHelpTopic(int analysisIndex,
                             String key,
                             String title,
                             String summary,
                             List<String> whenToUse,
                             List<String> inputs,
                             List<String> setup,
                             List<String> workflow,
                             List<String> outputs,
                             List<String> pitfalls,
                             List<HelpImage> images) {
        this.analysisIndex = analysisIndex;
        this.key = requireText(key, "key");
        this.title = requireText(title, "title");
        this.summary = requireText(summary, "summary");
        this.whenToUse = immutableStrings(whenToUse, "whenToUse");
        this.inputs = immutableStrings(inputs, "inputs");
        this.setup = immutableStrings(setup, "setup");
        this.workflow = immutableStrings(workflow, "workflow");
        this.outputs = immutableStrings(outputs, "outputs");
        this.pitfalls = immutableStrings(pitfalls, "pitfalls");
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
