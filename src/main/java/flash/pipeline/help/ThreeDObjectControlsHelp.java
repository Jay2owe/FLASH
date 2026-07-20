package flash.pipeline.help;

import flash.pipeline.FLASH_Pipeline;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * In-depth per-control manual for the 3D Object Analysis options dialogs
 * (the main options dialog and the follow-up Process Analysis dialog).
 * Summaries reuse the dialog's own inline help strings so the manual and the
 * hover tooltips stay in sync.
 */
public final class ThreeDObjectControlsHelp {

    private ThreeDObjectControlsHelp() {
    }

    static ControlHelpTopic topic() {
        List<ControlHelpTopic.Group> groups = new ArrayList<ControlHelpTopic.Group>();

        groups.add(g("Input", null,
                c("Use deconvolved stacks if available", "Toggle - default On",
                        "Prefers deconvolved input stacks when a deconvolved version exists.",
                        "Falls back to the raw stack when no deconvolved image is found.")));

        groups.add(g("Colocalization Method",
                "Per-object cross-channel colocalization. Choose any combination; voxel cutoffs are set in the thresholds table below.",
                c("Volumetric overlap (%)", "Toggle - default On",
                        "Percentage of object voxels overlapping with the partner channel.",
                        "Uses this channel's Coloc % cutoff below."),
                c("Centroid coincidence (CPC)", "Toggle - default On",
                        "Whether each object's centroid falls inside a partner object.",
                        "Asymmetric: A-in-B is not the same as B-in-A."),
                c("Intensity Colocalization", "Toggle - default Off",
                        "Per-object Pearson and Manders, plus Costes thresholds and significance at both object and image level.")));

        groups.add(g("Bounding-Box Colocalisation",
                "Faster, more permissive proximity measures using object bounding boxes rather than voxels.",
                c("Bounding-box overlap (% / BBColoc)", "Toggle - default Off",
                        "Box overlap: the maximum intersection of the object's box with a partner object's box, as a percentage of the source box volume.",
                        "Uses this channel's BB Coloc % cutoff below."),
                c("Bounding-box centroid coincidence (BB-CPC)", "Toggle - default Off",
                        "Centroid-in-box: whether each object's centroid falls inside a partner object's bounding box, plus a count of partner centroids inside this object's box."),
                c("Bounding-box volume fill (BBVolColoc)", "Toggle - default Off",
                        "Box fill: partner object voxels filling this object's bounding box, as a percentage of the box volume - single best partner and total of all partners.")));

        groups.add(g("Colocalisation Thresholds (%)",
                "Per-channel cutoffs. Each column greys out when no method that uses it is selected.",
                c("Coloc %", "Number per channel - default 30",
                        "Voxel-overlap cutoff used by Volumetric overlap.",
                        "Active only when Volumetric overlap is on."),
                c("BB Coloc %", "Number per channel - default 30",
                        "Bounding-box cutoff used by the box overlap and volume-fill methods.",
                        "Active only when a bounding-box method is on.")));

        groups.add(g("Object Intensity Profiling",
                "Per-object intensity profiles describing how signal is distributed within each object and relative to partner channels.",
                c("Radial profile (distance from centre)", "Toggle - default On",
                        "Mean intensity versus normalised distance from the object centroid (0 = centre, 1 = edge). Rotation-invariant; best for ring-versus-core.",
                        "Adds core/edge ratio, peak-radius and polarity columns."),
                c("Marginal X / Y / Z profile (image axes)", "Toggle - default On",
                        "Mean intensity projected onto each image axis (the original XY Profile macro).",
                        "Contributes curves and figures only; no scalar columns."),
                c("Principal-axis profile (object's own axes)", "Toggle - default On",
                        "Profiles along the object's PCA axes; orientation-invariant.",
                        "Adds a major-axis polarity column (signal pushed toward one pole)."),
                c("Angular profile (ring completeness)", "Toggle - default Off",
                        "Bins the partner signal by angle around the centroid to tell a complete ring from a crescent.",
                        "Adds ring-completeness and uniformity columns."),
                c("Concentric-shell coloc (inner/mid/outer)", "Toggle - default Off",
                        "Mean partner intensity in inner/mid/outer shells; distinguishes a ring from a uniform fill."),
                c("Within-box correlation (Pearson / overlap)", "Toggle - default Off",
                        "Voxel-wise Pearson and Manders' overlap of the source versus each partner inside the box; a ring anti-correlates with the core."),
                c("Restrict to object voxels only", "Toggle - default Off",
                        "OFF = the whole bounding box, including signal around the object; ON = only this object's voxels.",
                        "Enabled only when a profile above is on."),
                c("Generate aggregate OIP figures", "Toggle - default On",
                        "Averaged radial/marginal figures per channel pair, written to FLASH/Results/Analysis Images/Object Intensity Profiling/.",
                        "Enabled only when a profile above is on.")));

        groups.add(g("Resolve Fused Objects",
                "Touching objects are segmented as one blob. A marker channel flags fused/clustered objects.",
                c("Marker channel", "Dropdown - default None",
                        "Choose a nuclear or cellular marker; FLASH counts how many marker centroids land inside each object.",
                        "Writes OverlapCount, HasMarker and IsCluster columns.",
                        "The marker cannot be its own target; leave on None to disable.")));

        groups.add(g("Advanced",
                "Optional extra passes and classical-segmentation behaviour, hidden under the Advanced disclosure.",
                c("Extract Process Length", "Toggle - Advanced - default Off",
                        "Skeletonizes process channels, subtracts the nuclear marker, and measures skeleton length via 3D Objects Counter.",
                        "Opens a follow-up Process Analysis dialog to pick the nuclear marker and which channels contain processes."),
                c("Run Spatial Analysis", "Toggle - Advanced - default Off",
                        "Opens the full Spatial Analysis options next, then runs the selected spatial and morphometric outputs after object counting."),
                c("Use Centroid ROI Filtering (Classical)", "Toggle - Advanced - default On",
                        "Classical channels only. ON counts objects on the full image then filters by centroid inside the ROI (like StarDist); OFF crops to the ROI before counting.")));

        groups.add(g("Process Analysis (when Extract Process Length is on)",
                "A second dialog shown only when Extract Process Length is enabled.",
                c("Nuclear Marker Channel", "Dropdown",
                        "The channel subtracted from process channels before skeletonization.",
                        "Pick the nuclear/cellular marker so processes are measured cleanly.")));

        return new ControlHelpTopic(FLASH_Pipeline.IDX_3D_OBJECT, "three-d-object-controls",
                "3D Object Analysis", groups);
    }

    private static ControlHelpTopic.Group g(String heading, String intro, ControlHelpTopic.Control... controls) {
        return new ControlHelpTopic.Group(heading, intro, Arrays.asList(controls));
    }

    private static ControlHelpTopic.Control c(String label, String badge, String summary, String... details) {
        return new ControlHelpTopic.Control(label, badge, summary, Arrays.asList(details));
    }
}
