package flash.pipeline.help;

import flash.pipeline.FLASH_Pipeline;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * In-depth per-control manual for the Set Up Configuration wizard. Set Up
 * Configuration spans several stages; this manual collects the controls the user
 * meets across them, grouped by stage, and is shown under the single "?" that
 * every stage header carries.
 */
public final class SetupControlsHelp {

    private SetupControlsHelp() {
    }

    static ControlHelpTopic topic() {
        List<ControlHelpTopic.Group> groups = new ArrayList<ControlHelpTopic.Group>();

        groups.add(g("Channel Setup", null,
                c("Number of channels", "Number - default 3",
                        "How many channels each image has.",
                        "Sets how many channel rows the rest of the wizard configures.")));

        groups.add(g("Analysis Scope",
                "Whether analysis uses the whole z-stack or a contiguous z-slice subset.",
                c("Restrict analysis to selected z-slices", "Toggle - default Off",
                        "OFF analyses the full stack. ON reviews every image series to pick a z-range before the other QC stages.",
                        "Use ON when only part of the stack is in focus or on-tissue.")));

        groups.add(g("Settings Mode",
                "Tick the settings you want to adjust interactively per channel. Status icons: tick = full saved data, ! = partial, blank = none.",
                c("All Settings Mode Options", "Master toggle - default Off",
                        "Master switch that ticks every per-channel setting below at once."),
                c("Set Filter and Parameters", "Section toggle - default Off",
                        "Choose a filter preset, preview it on the current z-stack, and adjust detected key=value parameters per channel."),
                c("Display Ranges", "Section toggle - default Off",
                        "Set Min-Max display ranges via Brightness & Contrast on a max projection."),
                c("Channel Thresholds", "Section toggle - default Off",
                        "Set each channel's threshold (after its filter). The same value feeds classical object detection and ROI intensity measurements."),
                c("Segmentation Method", "Section toggle - default Off",
                        "Choose which channels open object segmentation setup; the method (Classical, StarDist, Cellpose) is chosen in that preview stage.")));

        groups.add(g("Segmentation Methods (per channel)",
                "Pick how each channel's objects are segmented.",
                c("Segmentation method", "Dropdown - default Classical",
                        "Classical thresholding, StarDist, Cellpose, or any imported catalog model.",
                        "Choosing StarDist or Cellpose triggers a dependency check and opens that method's parameter stage later.")));

        groups.add(g("Quality Check - Image Selection",
                "Which images get opened for the visual quality check.",
                c("Which images get the quality check?", "Card choice - default Random",
                        "Random sample, a manual pick from the list, dimmest+brightest overall, or extremes within each condition."),
                c("Number of random images", "Number - default 3",
                        "How many images to sample in Random mode.",
                        "Active only in Random mode."),
                c("Recompute cached min/max selection", "Toggle - default Off",
                        "Recomputes which images are dimmest/brightest instead of reusing the cached choice.",
                        "Active only in the min/max modes.")));

        groups.add(g("Z-Slice Subset (per image)",
                "Shown for each image when a z-slice subset was requested.",
                c("Slices to keep", "Text field",
                        "A contiguous inclusive z-range such as 11-30.",
                        "If no per-image suggestion exists, the previous accepted range is remembered for the next image."),
                c("Action", "Dropdown",
                        "Accept this selection, move to the next image, restart from the first image, or apply the current range to all remaining images.")));

        groups.add(g("StarDist 3D Parameters (per channel)",
                "Detection and 3D-linking settings for StarDist channels.",
                c("Probability Threshold", "Number - default 0.5",
                        "Minimum StarDist confidence to keep a detection; higher rejects weaker objects."),
                c("NMS Threshold", "Number - default 0.4",
                        "Overlap tolerance when detections compete for the same object; lower suppresses duplicates."),
                c("Linking Max Distance", "Number - default 5.0",
                        "Maximum centroid movement allowed between neighbouring z-slices when linking a 3D object."),
                c("Gap-Closing Max Distance", "Number - default 5.0",
                        "Maximum centroid movement when reconnecting an object across a missing slice."),
                c("Max Frame Gap", "Number - default 1",
                        "How many missing z-slices may be skipped while linking (0 = adjacent slices only)."),
                c("Min Area", "Number - default 0",
                        "Removes tiny linked objects; increase to discard specks and debris (0 disables)."),
                c("Max Area (0 = no limit)", "Number - default 0",
                        "Removes oversized merged objects (0 = no upper limit)."),
                c("Min Quality", "Number - default 0",
                        "Removes low StarDist/TrackMate quality detections; higher is stricter (0 disables)."),
                c("Min Mean Intensity", "Number - default 0",
                        "Removes dim objects by mean signal in this channel (0 disables).")));

        groups.add(g("Cellpose Parameters (per channel)",
                "Model and detection settings for Cellpose channels.",
                c("Cellpose Model", "Dropdown",
                        "The built-in Cellpose model (cyto/cyto2/cyto3, nuclei, etc.) or an imported model.",
                        "Some models use a companion channel for nuclei guidance."),
                c("Companion Channel", "Dropdown",
                        "Optional nuclei guidance for cyto/cyto2/cyto3; filtered with its own preset before analysis.",
                        "Hidden for models that do not use it."),
                c("Expected Diameter", "Number - default 30.0",
                        "Approximate object diameter in pixels; 0 lets Cellpose estimate it (less reproducible)."),
                c("Flow Threshold", "Number - default 0.4",
                        "Flow-error cutoff; 0 disables, higher is more permissive."),
                c("Cell Probability Threshold", "Number - default 0.0",
                        "Minimum mask probability; higher returns fewer, more confident objects."),
                c("Use GPU", "Toggle - default On",
                        "Runs on a compatible GPU if available; off forces CPU.",
                        "This does not install GPU support - use the Install GPU Support button for that.")));

        groups.add(g("Particle Sizes (Classical, per channel)",
                "Voxel-count cutoffs for classical object detection.",
                c("Min Size (n Voxels)", "Number",
                        "Lower voxel-count cutoff; objects smaller than this are discarded."),
                c("Max Size (n Voxels)", "Text field - default Infinity",
                        "Upper voxel-count cutoff; \"Infinity\" means no upper limit.")));

        groups.add(g("Channel Threshold (per image/channel)",
                "Set when a channel's threshold cannot be read automatically.",
                c("Threshold", "Text field",
                        "A numeric lower threshold, or 'default' for automatic thresholding.",
                        "A suggested automatic threshold is shown when one can be computed.")));

        return new ControlHelpTopic(FLASH_Pipeline.IDX_CREATE_BIN, "setup-controls",
                "Set Up Configuration", groups);
    }

    private static ControlHelpTopic.Group g(String heading, String intro, ControlHelpTopic.Control... controls) {
        return new ControlHelpTopic.Group(heading, intro, Arrays.asList(controls));
    }

    private static ControlHelpTopic.Control c(String label, String badge, String summary, String... details) {
        return new ControlHelpTopic.Control(label, badge, summary, Arrays.asList(details));
    }
}
