package flash.pipeline.help;

import flash.pipeline.FLASH_Pipeline;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** In-depth per-control manual for Excel Summary Export. */
public final class ExcelControlsHelp {

    private ExcelControlsHelp() {
    }

    static ControlHelpTopic topic() {
        List<ControlHelpTopic.Group> groups = new ArrayList<ControlHelpTopic.Group>();

        groups.add(g("Preset", null,
                c("Preset", "Card list - default exploratory",
                        "Pick a stock or saved export preset; selecting one sets every sheet toggle below.",
                        "A live preview shows which sheets and highlighting the current settings produce.")));

        groups.add(g("Conditions",
                "Condition assignments feed the Experimental Conditions sheet.",
                c("Review conditions...", "Button",
                        "Opens the shared condition-assignment review dialog.",
                        "If no animals are found, run Combine results / aggregation first.")));

        groups.add(g("Sheets (Advanced)",
                "Which sheets and annotations the workbook contains. Each toggle updates the preview.",
                c("Include 'Experimental Conditions' sheet", "Toggle",
                        "Adds the experimental conditions sheet."),
                c("Include 'Data Summary' sheet", "Toggle",
                        "Adds the data summary sheet."),
                c("Include per-metric sheets", "Toggle",
                        "Adds one sheet per metric."),
                c("Include 'Statistics' sheet", "Toggle",
                        "Adds the statistics sheet.",
                        "Stale or absent if Statistics was not run after the latest aggregation."),
                c("Significance highlight", "Card choice",
                        "Cell highlighting for significant results: Off, flat Yellow, or a P-gradient shade."),
                c("Significance stars (e.g. *, **, ***)", "Toggle",
                        "Adds significance star annotations."),
                c("Methods appendix sheet", "Toggle",
                        "Adds a methods appendix sheet describing the pipeline."),
                c("Include raw object texture feature-vector columns", "Toggle",
                        "Includes the raw per-object texture feature-vector columns (large).")));

        return new ControlHelpTopic(FLASH_Pipeline.IDX_EXCEL_EXPORT, "excel-controls",
                "Excel Summary Export", groups);
    }

    private static ControlHelpTopic.Group g(String heading, String intro, ControlHelpTopic.Control... controls) {
        return new ControlHelpTopic.Group(heading, intro, Arrays.asList(controls));
    }

    private static ControlHelpTopic.Control c(String label, String badge, String summary, String... details) {
        return new ControlHelpTopic.Control(label, badge, summary, Arrays.asList(details));
    }
}
