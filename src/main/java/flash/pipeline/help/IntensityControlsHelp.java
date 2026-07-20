package flash.pipeline.help;

import flash.pipeline.FLASH_Pipeline;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * In-depth per-control manual for the Fluorescence Intensity Analysis dialogs
 * (the primary options dialog, the ROI &amp; threshold dialog, and the
 * Intensity-Spatial metric dialog). The Intensity-Spatial metric summaries reuse
 * the dialog's own "what it measures / when to use it" help text, which now also
 * appears as a hover tooltip on each metric instead of a per-metric "?" button.
 */
public final class IntensityControlsHelp {

    private IntensityControlsHelp() {
    }

    static ControlHelpTopic topic() {
        List<ControlHelpTopic.Group> groups = new ArrayList<ControlHelpTopic.Group>();

        groups.add(g("Analysis Options", null,
                c("Use deconvolved stacks if available", "Toggle - default On",
                        "Measures the deconvolved image when a deconvolved version exists, else the raw stack."),
                c("ROI Analysis", "Toggle - default On when ROI sets exist",
                        "Restricts measurements to drawn region ROIs instead of the whole image.",
                        "Turning it on reveals the Regions and Channel ROI Mask sections in the next dialog."),
                c("Intensity-spatial analysis", "Toggle - default Off",
                        "Opens a further dialog of spatial metrics (patchiness, hotspots, texture, cross-channel) after the main setup.",
                        "Each metric runs per measurement mode; see the Intensity-Spatial section below.")));

        groups.add(g("Filter & Binarise (per channel)",
                "Binarise builds a binary mask from the filtered image at a threshold, then ANDs it with the raw image.",
                c("Filter source", "Dropdown per channel",
                        "Chooses the saved bin-file filter macro for this channel or a basic background-and-noise removal.",
                        "The bin filter reuses your Set Up Configuration preset; Basic applies a generic clean-up."),
                c("Binarise", "Toggle per channel",
                        "Builds a binary mask for this channel and measures only above-threshold pixels.",
                        "Turning any on routes to a threshold-settings dialog for the binarised channels.")));

        groups.add(g("ROI & Threshold Settings",
                "Shown when any channel is binarised or ROI Analysis is on.",
                c("Channel threshold", "Text field per binarised channel",
                        "Lower threshold for a binarised channel; pixels below it are masked out.",
                        "Only channels without a stored bin threshold show an input field - others display the saved value."),
                c("Regions", "Toggles per ROI set",
                        "Selects which drawn region ROI sets to include in this run.",
                        "Select all / Clear set every region at once."),
                c("Channel ROI", "Dropdown - Advanced - default None",
                        "Picks one channel whose filter+threshold mask is ANDed with every measurement channel.",
                        "The chosen channel must have binarisation enabled, or the run is blocked with an error.")));

        groups.add(g("Intensity-Spatial Analysis",
                "A separate dialog (opened by the toggle above) with independent metrics per measurement mode.",
                c("Measurement mode", "Tabs: Per-Slice / MIP / 3D",
                        "Per-Slice measures each z-slice on its own (best for dense or depth-varying signal); MIP collapses the stack to its brightest pixels then measures once (best for sparse, bright structures); 3D measures natively across the volume (slower, off by default).",
                        "Each tab runs independently; native-3D metrics need enough z-slices and MIP needs more than one slice."),
                c("Write verification overlays", "Toggle - default On",
                        "Writes overlay images that show where each selected metric was measured, for visual QC.")));

        groups.add(g("Intensity-Spatial: Same-Channel Metrics",
                "Describe the spatial pattern of one channel within the region. Hover a metric in the dialog for the same text.",
                c("Patchiness / tile variation", "Toggle",
                        "Splits the ROI into physical tiles and reports how uneven the signal is (tile coefficient of variation, Gini inequality, lacunarity).",
                        "The default \"is this signal diffuse or clumped?\" metric; good for comparing heterogeneity between samples.",
                        "Parameter: tile scales (um)."),
                c("Hotspot scan", "Toggle",
                        "Runs a local hotspot scan on a downsampled grid and reports hotspot fraction, Moran's I clustering, and a permutation p-value.",
                        "Use when bright signal may form focal enriched zones; slower because it uses random permutations.",
                        "Parameters: hotspot permutations, random seed."),
                c("Random null model", "Toggle",
                        "Compares raw pixel variance with a simple random shot-noise model and reports p-value, z-score, and pass/fail.",
                        "A sanity check for whether intensity variation exceeds random noise; not a pattern classifier."),
                c("Granularity scale", "Toggle",
                        "Tests the configured physical scales and reports the scale of strongest variation plus energy at each scale.",
                        "Distinguishes fine puncta from coarse blobs; adjust the scales for much smaller or larger structures.",
                        "Parameter: granularity scales (um)."),
                c("Depth profile / rim-core", "Toggle",
                        "Measures mean signal in distance bands from the ROI edge and reports rim-core ratio, depth slope, peak depth, and edge coupling.",
                        "Use when the question is whether signal sits near a boundary, in the core, or varies with distance from the edge.",
                        "Parameters: depth bin width (um), rim depth (um)."),
                c("2D anisotropy", "Toggle",
                        "Uses a 2D structure tensor to estimate directional organization, dominant angle, and orientation entropy.",
                        "Use for fibres, stripes, elongated processes, or other aligned structures per slice or MIP."),
                c("Periodicity", "Toggle",
                        "Uses a 2D frequency spectrum to estimate repeating wavelength, stripe angle, stripiness, and peak spectral power.",
                        "Use when the image may contain regular bands; avoid for irregular texture with no repeat."),
                c("GLCM texture", "Toggle - default On (Per-Slice, MIP)",
                        "Grey-level co-occurrence texture: contrast, entropy, homogeneity, energy, correlation between neighbouring pixels.",
                        "General local roughness/smoothness when you do not expect a direction, hotspot, or period."),
                c("Texture classes", "Toggle",
                        "Clusters per-pixel intensity and texture features into the configured number of classes and reports the fraction in each.",
                        "Exploratory comparison of mixed texture states; class numbers are relative labels.",
                        "Parameter: texture class count."),
                c("Scale divergence", "Toggle",
                        "Summarises how intensity mass changes across box sizes using bounded multifractal-style delta-alpha and asymmetry.",
                        "Exploratory multi-scale heterogeneity when one tile size is not enough."),
                c("Native 3D anisotropy", "Toggle - 3D tab",
                        "Native 3D directional organisation through the z-stack via volumetric structure-tensor coherency, angle, and entropy.",
                        "Use when orientation through depth matters and the stack has enough z-slices; slower than 2D anisotropy.")));

        groups.add(g("Intensity-Spatial: Cross-Channel Metrics",
                "Relate two channels (need >=2 channels; distance shells need a binarised partner).",
                c("Fast cross-channel correlation", "Toggle",
                        "Direct Pearson correlation plus the strongest shifted cross-correlation peak between two channels.",
                        "A quick screen for co-variation or small spatial offsets; avoids the heavier Coloc 2 maths."),
                c("Full CrossMark / Coloc2", "Toggle",
                        "Full 2D cross-channel association: Pearson, shifted cross-correlation, mark correlation, Costes randomisation, and Manders overlap.",
                        "For formal colocalisation questions; slower and benefits from meaningful thresholds.",
                        "Parameter: Costes permutations."),
                c("Cross-channel mutual information", "Toggle",
                        "Normalised mutual information and the strongest shifted mutual-information peak between two channels.",
                        "Use when channels may be related non-linearly, not by a simple straight-line correlation."),
                c("Distance shells around binarised partner", "Toggle",
                        "Source-channel mean intensity in 2D distance shells around a partner's binarised mask, plus shell slope and area under the curve.",
                        "For halo, rim, exclusion, or proximity questions; requires at least one binarised channel.",
                        "Parameters: distance shell width (um), distance shell count."),
                c("Native 3D cross-mark", "Toggle - 3D tab",
                        "Native 3D cross-channel colocalisation across the volume: Pearson, Costes randomisation, and Manders overlap.",
                        "For true volumetric colocalisation when a MIP could hide depth separation.",
                        "Parameter: Costes permutations."),
                c("Native 3D distance shells", "Toggle - 3D tab",
                        "Source-channel mean intensity in 3D distance shells around a partner's binarised volume, plus shell slope and area under the curve.",
                        "For volumetric halo/rim/exclusion questions; requires a binarised partner and enough z-slices.",
                        "Parameters: distance shell width (um), distance shell count.")));

        return new ControlHelpTopic(FLASH_Pipeline.IDX_INTENSITY, "intensity-controls",
                "Fluorescence Intensity Analysis", groups);
    }

    private static ControlHelpTopic.Group g(String heading, String intro, ControlHelpTopic.Control... controls) {
        return new ControlHelpTopic.Group(heading, intro, Arrays.asList(controls));
    }

    private static ControlHelpTopic.Control c(String label, String badge, String summary, String... details) {
        return new ControlHelpTopic.Control(label, badge, summary, Arrays.asList(details));
    }
}
