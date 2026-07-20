package flash.pipeline.help;

import flash.pipeline.FLASH_Pipeline;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** In-depth per-control manual for Statistical Analysis (condition assignment + test settings). */
public final class StatisticsControlsHelp {

    private StatisticsControlsHelp() {
    }

    static ControlHelpTopic topic() {
        List<ControlHelpTopic.Group> groups = new ArrayList<ControlHelpTopic.Group>();

        groups.add(g("Preset", null,
                c("Preset", "Dropdown",
                        "Apply a saved Statistics preset to populate the paired / distribution / post-hoc choices below.")));

        groups.add(g("Test Settings",
                "How the comparison for each metric is chosen.",
                c("Paired design", "Card choice - default Unpaired",
                        "Unpaired treats groups as independent; Within animal pairs repeated sections/images from the same animal."),
                c("Distribution", "Card choice - default Auto",
                        "Auto gates on a normality test; Parametric assumes normal; Non-parametric assumes skewed.",
                        "Auto is safest when you are unsure of the distribution."),
                c("Post-hoc (3+ groups)", "Card choice - default Bonferroni",
                        "Multiple-comparison correction for 3+ groups: Bonferroni (conservative), Tukey HSD (all pairwise), Dunn's (non-parametric), or None.")));

        groups.add(g("Grouping",
                "Which condition axis the comparison uses.",
                c("Compare groups by:", "Dropdown",
                        "The full condition matrix, or a single condition axis.",
                        "Shown only for multi-axis (matrix) projects.")));

        return new ControlHelpTopic(FLASH_Pipeline.IDX_STATISTICS, "statistics-controls",
                "Statistical Analysis", groups);
    }

    private static ControlHelpTopic.Group g(String heading, String intro, ControlHelpTopic.Control... controls) {
        return new ControlHelpTopic.Group(heading, intro, Arrays.asList(controls));
    }

    private static ControlHelpTopic.Control c(String label, String badge, String summary, String... details) {
        return new ControlHelpTopic.Control(label, badge, summary, Arrays.asList(details));
    }
}
