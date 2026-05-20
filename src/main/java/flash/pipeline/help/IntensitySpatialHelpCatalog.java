package flash.pipeline.help;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Helper text for the question-mark buttons on the Intensity-Spatial wizard.
 * One overview topic for the screen header and one per visible subsection.
 */
public final class IntensitySpatialHelpCatalog {

    public static final SetupHelpTopic OVERVIEW = topic(
            "intensity-spatial-overview",
            "Intensity-Spatial Analysis",
            "Adds spatial-statistics measurements on top of standard intensity analysis so you can ask where signal sits inside each ROI, not just how bright it is. "
                    + "Each subsection groups a family of metrics; turn on only the families that match your question. "
                    + "Click the question mark next to a subsection title for a per-toggle breakdown.",
            section("What each subsection gives you",
                    "Output and source: how multi-slice data is flattened for 2D analyses, whether to add native 3D outputs, and whether to request optional quality-control overlays.",
                    "Single-channel distribution: tells you whether the signal in one channel is uneven, locally clustered, or compatible with a simple Poisson intensity null model.",
                    "Depth and structure: scale-response spectrum, rim-versus-interior gradients, and 2D directional alignment.",
                    "Cross-channel: colocalisation, non-linear coupling, and how intensity stratifies around another channel's mask.",
                    "Native 3D: volumetric versions of cross-channel correlation, distance shells, and anisotropy.",
                    "Advanced analysis families: periodicity, GLCM texture, k-means texture classes, and multifractal scale divergence.",
                    "Parameters: tile scales, shell sizes, granularity ladder, depth-bin and rim widths, k for texture classes, and the permutation count plus seed."),
            section("When to use intensity-spatial at all",
                    "Use when the spatial pattern of intensity matters: clusters versus even spread, layered structure, fibre orientation, or co-localisation between channels.",
                    "Use alongside (not instead of) standard intensity analysis. These metrics describe distribution, not amount.",
                    "Skip if every ROI is small relative to your features. The spatial statistics need a meaningful neighbourhood."),
            section("What you need before turning toggles on",
                    "Raw (un-binarised) intensity for the Poisson null model, anisotropy, periodicity, GLCM texture, texture classes, and scale divergence.",
                    "At least two selected channels for cross-channel toggles. Distance-shell toggles additionally need at least one binarised partner channel.",
                    "A z-stack with at least 5 slices for Native 3D. Native 3D cross-channel still needs at least two channels; Native 3D distance shells still need a binarised partner channel."));

    public static final SetupHelpTopic OUTPUT_SOURCE = topic(
            "intensity-spatial-output-source",
            "Output and Source",
            "Controls how multi-slice images are flattened for the 2D analyses, whether volumetric (3D) versions of cross-channel, distance-shell, and anisotropy metrics can be added, "
                    + "and whether optional visual quality-control outputs are requested alongside the numeric outputs.",
            section("2D source (MIP vs full z-stack)",
                    "What it does: chooses how a multi-slice image is flattened for the 2D analyses. MIP (maximum-intensity projection) collapses the z-stack into one image and is fast and noise-tolerant. Full z-stack runs each slice independently and pools the per-slice numbers, which preserves z-axis context but takes longer.",
                    "When to turn it on: use MIP for routine 2D summaries or for thin sections where the signal lives in one focal plane. Use full z-stack when slice-to-slice variation matters or when projection would superimpose anatomically separate structures.",
                    "Skip if: the image is a single slice. The choice doesn't apply."),
            section("Native 3D spatial measurements",
                    "What it does: unlocks the volumetric cross-channel correlation, distance-shell, and anisotropy toggles. Those selected analyses write native-3D columns alongside the 2D outputs.",
                    "When to turn it on: confocal or light-sheet stacks where the biology is genuinely 3D (vasculature, dendritic arbors, plaque shells) and you want z-aware numbers rather than projections.",
                    "Skip if: you have fewer than 5 z-slices, the z-step is much coarser than the xy-pixel size, or the section is essentially planar. Cross-channel native 3D also needs at least two channels; native 3D distance shells need a binarised partner channel."),
            section("Write visual overlays",
                    "What it does: records a request for optional spatial quality-control images in the Spatial Overlays output area when an analysis provides them. Numeric measurements are unchanged.",
                    "When to turn it on: during method development, when reviewing a new dataset for the first time, or when a number looks suspicious and you want visual checks where available.",
                    "Skip if: production batch runs where disk space matters, or if you only need the numeric CSV outputs."));

    public static final SetupHelpTopic SINGLE_CHANNEL = topic(
            "intensity-spatial-single-channel",
            "Single-Channel Distribution",
            "Quantifies how intensity is distributed within one channel: uneven versus uniform, local hotspots, and whether raw dispersion is compatible with random shot-noise. "
                    + "Patchiness and hotspot scan can also emit binarised companion outputs when available; the Poisson null model uses raw intensity only.",
            section("Patchiness and lacunarity",
                    "What it does: tiles each ROI at several scales (default 50, 100, 250 um) and measures how unevenly intensity is distributed between tiles. Reports tile coefficient of variation, whole-ROI Gini inequality, and excess lacunarity (tile variance divided by mean squared; high values mean a few bright clusters separated by dim space).",
                    "When to turn it on: when you suspect signal clumps into clusters rather than spreading uniformly (plaque deposits, microglial nodules, cell aggregates).",
                    "Skip if: the ROI is smaller than your smallest tile scale, or the signal is known to be diffuse and homogeneous."),
            section("Hotspot scan",
                    "What it does: runs local and global Moran's I (spatial-statistics tests for neighbouring pixels with unusually similar values) on a bounded grid without requiring an intensity threshold. Outputs hotspot fraction, global Moran's I, and a permutation p-value.",
                    "When to turn it on: when you want to locate and quantify intensity clusters but don't want to commit to a binarisation threshold. Useful when intensity varies gradually rather than crossing a clear on/off boundary.",
                    "Skip if: you already work from a binary mask (use object-based analyses instead), or the ROI is too small to contain meaningful neighbourhoods."),
            section("Poisson intensity null model",
                    "What it does: compares raw pixel-intensity variance against a mean-field Poisson shot-noise reference using a chi-square normal approximation. Reports p-value, z-score, and pass/fail at p >= 0.05.",
                    "When to turn it on: when you need a compact check for whether raw intensity dispersion is compatible with simple shot noise, especially when comparing experimental versus control conditions.",
                    "Skip if: you only need descriptive spatial numbers, the image has been binarised, or the raw intensity scale is not meaningful for a Poisson-style variance check."));

    public static final SetupHelpTopic DEPTH_STRUCTURE = topic(
            "intensity-spatial-depth-structure",
            "Depth and Structure",
            "Characterises feature size, distance-from-edge gradients, and 2D directional alignment for a single channel. "
                    + "These analyses tell you about the geometry of intensity rather than its colocalisation with anything else.",
            section("Granularity across scales",
                    "What it does: probes a ladder of scales (default 2, 4, 8, 16, 32, 64 um) using lagged anti-correlation energy, with a Haar-like contrast fallback when needed. Reports energy at each scale plus peak and centroid scale.",
                    "When to turn it on: when 'how big is the typical feature?' is the question. Distinguishing puncta from cell bodies from larger aggregates, or characterising texture at unknown scales.",
                    "Skip if: feature size is already known and uniform, or the ROI is smaller than the largest granularity scale."),
            section("Depth profile from ROI boundary",
                    "What it does: bins intensity by distance from the ROI edge and reports the first three depth bins (default 0-10, 10-20, 20-30 um), depth slope, peak-depth bin, rim/core ratio, and edge-coupling index using the separate Rim depth parameter.",
                    "When to turn it on: when the biology has a clear inside/outside (cortical layers, tumour margins, plaque cores versus halos, vessel walls versus lumen).",
                    "Skip if: the ROI is roughly the same shape and size as the features inside it, or the boundary is arbitrary (e.g. a rectangular field of view)."),
            section("2D alignment / anisotropy",
                    "What it does: uses the structure tensor (a local-gradient matrix that captures directionality) to measure how aligned the signal is and in which direction. Outputs a coherency score (0 = isotropic, 1 = perfectly aligned) and a dominant angle.",
                    "When to turn it on: fibrous, striated, or directional structures (myelinated tracts, vasculature, cytoskeleton, collagen).",
                    "Skip if: signal is dot-like or blob-like with no expected orientation. Coherency will be near zero and uninformative."));

    public static final SetupHelpTopic CROSS_CHANNEL = topic(
            "intensity-spatial-cross-channel",
            "Cross-Channel",
            "Spatial and intensity relationships between pairs of channels. "
                    + "All cross-channel toggles need at least two channels; distance-shell additionally needs a binarised partner channel.",
            section("Cross-channel correlation and mark correlation",
                    "What it does: runs the standard colocalisation suite (Pearson correlation, Costes significance and thresholds, Manders' M1/M2 overlap coefficients) plus a spatial cross-correlation peak (intensity correlation as one channel is shifted relative to the other) and a mark correlation peak (how brightness in one channel relates to brightness in nearby pixels of the other).",
                    "When to turn it on: when you need to know whether two channels overlap, are offset by a small distance, or vary together without overlapping perfectly (e.g. receptor near but not on a vesicle).",
                    "Skip if: you have only one channel selected, or the two channels are spectrally bled into each other (artefactual correlation)."),
            section("Cross-channel mutual information",
                    "What it does: computes mutual information (a statistic that captures any kind of dependence between two variables, including non-linear ones) between two channels, plus a shifted version that finds the offset at which dependence peaks. Pearson misses non-linear relationships; MI catches them.",
                    "When to turn it on: when you suspect the two channels are coupled in a non-proportional way (one saturates while the other rises, shared on/off pattern without shared magnitude).",
                    "Skip if: you only have one channel, or you already have strong evidence the coupling is linear. Correlation will be enough and is cheaper to compute."),
            section("Intensity around partner-channel mask shells",
                    "What it does: treats one channel's mask as a reference object and measures the mean intensity of the other channel in concentric distance shells around it (default 5 shells, 10 um each). Outputs a stratification curve (how intensity decays with distance from the partner structure) plus a slope and area-under-curve.",
                    "When to turn it on: when one channel defines a structure (vessel, plaque, nucleus) and you want to know whether the other channel is enriched at, near, or far from it.",
                    "Skip if: the partner channel can't be reliably binarised, or the structures are so dense that shells overlap immediately."));

    public static final SetupHelpTopic NATIVE_3D = topic(
            "intensity-spatial-native-3d",
            "Native 3D Analyses",
            "Volumetric versions of cross-channel correlation, distance shells, and anisotropy that work directly on the voxel volume instead of per slice. "
                    + "All three need 'Native 3D spatial measurements' on and at least 5 z-slices; the cross-channel native-3D toggles also keep their normal channel and mask requirements.",
            section("Native 3D cross-channel correlation",
                    "What it does: runs native-3D Pearson, Costes significance/thresholds, and Manders M1/M2 on the full voxel volume rather than slice-by-slice. Captures co-localisation in z as well as xy.",
                    "When to turn it on: when channels can be offset axially (e.g. receptor on apical versus basal cell surface) and a 2D projection would either falsely overlap them or falsely separate them.",
                    "Skip if: you have fewer than two channels, z-resolution is much coarser than xy (the 3D number will be dominated by the in-plane component), or you have fewer than 5 z-slices."),
            section("Native 3D distance shells",
                    "What it does: volumetric version of the partner-channel shell analysis. Measures intensity in concentric 3D shells (spherical-ish layers) around the partner channel's 3D mask.",
                    "When to turn it on: when the reference structure is genuinely 3D (vessel network, plaque sphere, nucleus) and 2D shells would mix near-in-z with far-in-z voxels.",
                    "Skip if: there is no reliably binarised partner channel, the partner structure is essentially planar in your slice, or z-sampling is too sparse to define meaningful 3D distance."),
            section("Native 3D anisotropy",
                    "What it does: computes a native-3D structure tensor to find directionality in the full volume. Outputs 3D coherency, dominant projected orientation angle, and orientation entropy.",
                    "When to turn it on: truly 3D oriented structures (vasculature with z-component, dendrites running through the slice, axon tracts at an oblique angle).",
                    "Skip if: z-step is much coarser than xy (direction will be biased toward in-plane), the structure is flat, or you have fewer than 5 slices."));

    public static final SetupHelpTopic ADVANCED = topic(
            "intensity-spatial-advanced",
            "Advanced Analysis Families",
            "Texture, frequency, and multifractal descriptors that complement the simpler distribution and structure metrics. "
                    + "Hidden behind the Advanced panel because they are rarely needed for routine work.",
            section("Periodicity",
                    "What it does: runs a 2D Fast Fourier Transform (a frequency-decomposition that exposes repeating spatial patterns) and looks for peaks in the power spectrum. Reports wavelength, stripe orientation, stripiness, and peak-power fraction.",
                    "When to turn it on: when the tissue has a regular pattern (cortical columns, sarcomere banding, repeating layers, striations).",
                    "Skip if: signal is patchy or random with no expected periodicity. The FFT peak will be noise."),
            section("GLCM texture metrics",
                    "What it does: builds a grey-level co-occurrence matrix (counts how often each pair of intensity values appears next to each other) and reports the five Haralick metrics (contrast, entropy, homogeneity, ASM, correlation).",
                    "When to turn it on: when you want a compact texture fingerprint per ROI, especially for downstream classification or comparing texture between groups.",
                    "Skip if: images are very low-bit-depth or saturated (GLCM degenerates), or the ROI is too small for stable neighbour statistics."),
            section("Texture classes",
                    "What it does: runs deterministic k-means clustering (an unsupervised grouping algorithm) on per-pixel intensity, band-pass, gradient, and local-difference features, partitioning the ROI into k texture types (default k=4). Outputs the fractional area covered by each class. Classes are sorted by standardised intensity centroid, so class numbers are reproducible low-to-high intensity ranks for the same image and settings.",
                    "When to turn it on: when you want a tissue-compartment breakdown (e.g. '30% fibrous, 50% diffuse, 20% punctate') without pre-defining what each compartment looks like.",
                    "Skip if: you need class labels that mean exactly the same biological compartment across images. Features are standardised per image, so absolute texture meaning still drifts even though the intensity rank ordering is deterministic."),
            section("Scale divergence",
                    "What it does: estimates multifractal-style alpha slopes at three statistical moments (q = -2, 0, +2). Reports delta-alpha (the spread of slopes; how heterogeneous the scale behaviour is) and asymmetry (bounded to [-1, 1]; positive means the dim/sparse side is broader than the bright/high-mass side, negative means the bright/high-mass side is broader, zero is balanced).",
                    "When to turn it on: when you suspect the texture has a mix of regimes (some regions smooth, some rough) and a single GLCM number can't capture it. Useful for distinguishing healthy from degenerating tissue.",
                    "Skip if: the ROI is too small to span several box scales, signal-to-noise is low, or you don't have downstream interpretation for multifractal numbers."));

    public static final SetupHelpTopic PARAMETERS = topic(
            "intensity-spatial-parameters",
            "Parameters",
            "Numeric inputs that shape the analyses above. Defaults work well for typical tissue sections; change them when feature sizes or ROI scale are unusual.",
            section("Tile scales (um), default 50, 100, 250",
                    "What it controls: the tile sizes used by Patchiness/lacunarity. Each scale is processed independently.",
                    "When to change the default: make the smallest scale similar to the size of your expected features and the largest similar to a major sub-region of the ROI. Smaller ROIs need smaller scales."),
            section("Shell width (um), default 10.0",
                    "What it controls: the thickness of each concentric ring in partner-channel shell analyses (2D and 3D).",
                    "When to change the default: set it to roughly your xy pixel size times 5 to 10. Use narrower shells (e.g. 5 um) when the partner structure is small or stratification is sharp; wider shells when the partner is large or signal is noisy."),
            section("Shell count, default 5",
                    "What it controls: how many shells extend out from the partner mask. Total radial coverage = shell width times shell count.",
                    "When to change the default: increase if your ROIs are large and you expect long-range gradients; decrease if shells start hitting the ROI edge or overlapping with other partner objects."),
            section("Granularity scales (um), default 2, 4, 8, 16, 32, 64",
                    "What it controls: the ladder of feature sizes probed by the granularity-across-scales analysis.",
                    "When to change the default: add a smaller scale (e.g. 1 um) for fine puncta; add a larger scale (e.g. 128 um) for tissue-level structures. Keep doublings; the analysis assumes a log-scale ladder."),
            section("Depth bin width (um), default 10.0",
                    "What it controls: the bin width for the radial intensity profile from the ROI boundary.",
                    "When to change the default: narrow bins (5 um) for thin layered structures; wide bins (20+ um) for noisy data or large ROIs where fine bins would be empty."),
            section("Rim depth (um), default 10.0",
                    "What it controls: the thickness of the rim zone used for the single rim-versus-interior edge-coupling summary metric. Independent of depth bin width.",
                    "When to change the default: match it to the biological rim you care about (e.g. about 20 um for a cortical molecular layer, about 50 um for a tumour edge)."),
            section("Texture classes (k), default 4",
                    "What it controls: number of clusters in the texture-classes k-means.",
                    "When to change the default: increase to 6 to 8 if you expect several distinct tissue compartments; decrease to 2 to 3 for simple foreground/background partitioning."),
            section("Permutation count, default 199",
                    "What it controls: number of shuffled re-runs used by hotspot Moran's I and Costes colocalisation significance. Costes randomisations are capped internally for runtime.",
                    "When to change the default: raise for tighter empirical p-values in hotspot scans; cost scales linearly. Lower values are fine for exploratory work."),
            section("Random seed, default 1",
                    "What it controls: seeds the random number generator used by hotspot-scan permutations. The k-means initialisation is deterministic, and the Poisson null model does not use permutations.",
                    "When to change the default: change only to confirm a result isn't an artefact of a single seed. Keep the same seed within a study so results are comparable across images."));

    private static final Map<String, SetupHelpTopic> TOPICS = buildTopics();

    private IntensitySpatialHelpCatalog() {
    }

    public static SetupHelpTopic forKey(String key) {
        return TOPICS.get(key);
    }

    public static Map<String, SetupHelpTopic> all() {
        return TOPICS;
    }

    private static Map<String, SetupHelpTopic> buildTopics() {
        Map<String, SetupHelpTopic> topics = new LinkedHashMap<String, SetupHelpTopic>();
        put(topics, OVERVIEW);
        put(topics, OUTPUT_SOURCE);
        put(topics, SINGLE_CHANNEL);
        put(topics, DEPTH_STRUCTURE);
        put(topics, CROSS_CHANNEL);
        put(topics, NATIVE_3D);
        put(topics, ADVANCED);
        put(topics, PARAMETERS);
        return Collections.unmodifiableMap(topics);
    }

    private static void put(Map<String, SetupHelpTopic> topics, SetupHelpTopic topic) {
        if (topics.put(topic.key, topic) != null) {
            throw new IllegalStateException("Duplicate intensity-spatial helper key: " + topic.key);
        }
    }

    private static SetupHelpTopic topic(String key, String title, String summary,
                                        SetupHelpTopic.Section... sections) {
        return new SetupHelpTopic(key, title, summary, Arrays.asList(sections));
    }

    private static SetupHelpTopic.Section section(String heading, String... items) {
        return new SetupHelpTopic.Section(heading, list(items));
    }

    private static List<String> list(String... items) {
        return Arrays.asList(items);
    }
}
