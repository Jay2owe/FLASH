package flash.pipeline.help;

import flash.pipeline.FLASH_Pipeline;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * In-depth per-control manual for Draw ROIs and Orientate Images. The dialog is
 * a short multi-screen flow (create/append entry, drawing settings, or import);
 * this manual collects the controls across those screens under the single "?".
 */
public final class DrawRoisControlsHelp {

    private DrawRoisControlsHelp() {
    }

    static ControlHelpTopic topic() {
        List<ControlHelpTopic.Group> groups = new ArrayList<ControlHelpTopic.Group>();

        groups.add(g("Create ROIs", null,
                c("How do you want to create ROIs?", "Card choice - default Create new",
                        "Draw a fresh set, append to a saved set, or import an ImageJ ROI zip.",
                        "Append is offered only when saved ROI sets already exist.",
                        "Imported zips need one ROI per image, in image-series order.")));

        groups.add(g("Drawing Settings",
                "How regions are drawn and displayed.",
                c("Append to existing", "Dropdown (append mode)",
                        "The saved ROI set that newly drawn ROIs are added to."),
                c("ROI Channel", "Dropdown",
                        "The channel the ROIs are drawn on.",
                        "In create mode the per-region table sets each region's channel, so this is disabled."),
                c("Image Adjustment", "Card choice - default Automatic",
                        "Display while drawing: as stored (None), auto Brightness & Contrast per channel (Automatic), or set yourself (Manual).",
                        "Display only - it does not change measured pixels."),
                c("Draw ROIs on", "Card choice - default Full image",
                        "Draw on the full stack, or only the configured analysis z-slice subset.",
                        "Shown only when a z-slice subset is configured.")));

        groups.add(g("Line Set (Advanced)",
                "Optionally draw a reference line alongside the regions.",
                c("Draw Line Set", "Toggle - Advanced - default Off",
                        "Also draw a named reference line per image (e.g. a ventricle boundary).",
                        "Lines are saved to FLASH/Results/Tables/Line Distance/Line Sets/ for line-distance analysis."),
                c("Line Set Name", "Text field - Advanced - default Ventricle",
                        "Name for the drawn line set.",
                        "Active only when Draw Line Set is on.")));

        groups.add(g("Import ROI Zip",
                "Shown when importing an existing ROI zip instead of drawing.",
                c("ROI Set Name", "Text field",
                        "Name for the imported ROI set."),
                c("Preview ROIs before saving", "Toggle - default On",
                        "Step through each image to check the imported ROIs before saving.",
                        "The import also checks ROI count and bounds against the prepared image series.")));

        return new ControlHelpTopic(FLASH_Pipeline.IDX_DRAW_ROIS, "draw-rois-controls",
                "Draw ROIs and Orientate Images", groups);
    }

    private static ControlHelpTopic.Group g(String heading, String intro, ControlHelpTopic.Control... controls) {
        return new ControlHelpTopic.Group(heading, intro, Arrays.asList(controls));
    }

    private static ControlHelpTopic.Control c(String label, String badge, String summary, String... details) {
        return new ControlHelpTopic.Control(label, badge, summary, Arrays.asList(details));
    }
}
