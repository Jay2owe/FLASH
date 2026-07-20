package flash.pipeline.help;

import flash.pipeline.FLASH_Pipeline;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * In-depth per-control manual for Make Presentation Images (split / merge).
 * These are display-only outputs, not quantitative measurements.
 */
public final class SplitMergeControlsHelp {

    private SplitMergeControlsHelp() {
    }

    static ControlHelpTopic topic() {
        List<ControlHelpTopic.Group> groups = new ArrayList<ControlHelpTopic.Group>();

        groups.add(g("Input", null,
                c("Use deconvolved stacks if available", "Toggle - default On",
                        "Uses deconvolved outputs as the input when a deconvolved version exists.")));

        groups.add(g("Channel Processing",
                "Per-channel display mapping - display only, not quantitative fluorescence.",
                c("Channel Processing", "Panel",
                        "Per channel: display method (Automatic or Custom), saturation, display-range min/max, and LUT colour.",
                        "Do not feed display-enhanced images into intensity or object analysis.")));

        groups.add(g("Merge Options",
                "Composite outputs across channels.",
                c("Create merge of all channels", "Toggle - default On",
                        "Writes an all-channel coloured composite."),
                c("Save OME-TIFF composites", "Toggle - default Off",
                        "Also writes OME-TIFF composites alongside the display images.")));

        groups.add(g("Advanced",
                "Extra merges, orientation, tiling, and background subtraction.",
                c("Additional merges (e.g. 1-2 3-4):", "Text field - Advanced",
                        "Extra channel-subset merges, e.g. \"1-2 3-4\" builds a 1+2 merge and a 3+4 merge."),
                c("Apply saved orientation transforms", "Toggle - Advanced",
                        "Applies the rotate/flip transforms saved during Draw ROIs and Orientate Images."),
                c("Overview tiles / montage", "Panel - Advanced",
                        "Tile montage options: create the montage, annotate overview/individual, group rows by animal or condition, order, cell size, scale bar, and labels."),
                c("Background subtraction", "Panel - Advanced",
                        "Optionally subtract a background channel from selected channels before display.")));

        return new ControlHelpTopic(FLASH_Pipeline.IDX_SPLIT_MERGE, "split-merge-controls",
                "Make Presentation Images", groups);
    }

    private static ControlHelpTopic.Group g(String heading, String intro, ControlHelpTopic.Control... controls) {
        return new ControlHelpTopic.Group(heading, intro, Arrays.asList(controls));
    }

    private static ControlHelpTopic.Control c(String label, String badge, String summary, String... details) {
        return new ControlHelpTopic.Control(label, badge, summary, Arrays.asList(details));
    }
}
