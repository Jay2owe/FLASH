package flash.pipeline.help;

import flash.pipeline.FLASH_Pipeline;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** In-depth per-control manual for Combine results per condition / animal (aggregation). */
public final class AggregationControlsHelp {

    private AggregationControlsHelp() {
    }

    static ControlHelpTopic topic() {
        List<ControlHelpTopic.Group> groups = new ArrayList<ControlHelpTopic.Group>();

        groups.add(g("Preset", null,
                c("Preset", "Dropdown",
                        "Apply a saved aggregation preset (grouping granularity and output options).")));

        groups.add(g("Conditions",
                "Assign each animal to an experimental condition before grouping.",
                c("Apply current conditions to existing master tables (no re-run)", "Button",
                        "Persists the condition assignments and refreshes existing master tables in place, without recomputing anything.")));

        groups.add(g("Grouping Granularity",
                "How per-image rows are combined.",
                c("Granularity", "Dropdown - default Per-animal average",
                        "Per-animal average, per-animal per-hemisphere, per-animal per-region, or per-section raw.",
                        "Coarser granularity averages more rows together per group.")));

        groups.add(g("Output",
                "Which value columns the master tables carry.",
                c("Raw + per-mm^3 (default)", "Radio - default",
                        "Writes both raw counts and per-mm^3 densities.",
                        "Requires ROI volume data - run Draw ROIs & Orientate or 3D Object Analysis first."),
                c("Raw only", "Radio",
                        "Writes raw counts only.",
                        "Forced when no ROI volume data is available."),
                c("Per-mm^3 only", "Radio",
                        "Writes per-mm^3 densities only.",
                        "Requires ROI volume data.")));

        return new ControlHelpTopic(FLASH_Pipeline.IDX_AGGREGATION, "aggregation-controls",
                "Combine results per condition / animal", groups);
    }

    private static ControlHelpTopic.Group g(String heading, String intro, ControlHelpTopic.Control... controls) {
        return new ControlHelpTopic.Group(heading, intro, Arrays.asList(controls));
    }

    private static ControlHelpTopic.Control c(String label, String badge, String summary, String... details) {
        return new ControlHelpTopic.Control(label, badge, summary, Arrays.asList(details));
    }
}
