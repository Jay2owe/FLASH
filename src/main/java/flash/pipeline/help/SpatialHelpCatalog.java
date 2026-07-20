package flash.pipeline.help;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Per-section helper topics for the Spatial Analysis dialog. Each constant is
 * attached to one section heading via PipelineDialog's
 * {@code addSetupHelpHeader}, {@code addSetupHelpSubHeader}, or
 * {@code beginCollapsibleSection(title, expanded, topic)}.
 */
public final class SpatialHelpCatalog {

    public static final SetupHelpTopic DISTANCES = topic(
            "spatial-distances",
            "Spatial Distances",
            "Distance measurements for each object. Three sub-analyses run independently; each writes its own columns or files.",
            section("Sub-analyses",
                    "Nearest neighbor distances: 3D distance and ID of the closest object in each other channel. Adds <channel>_DistToClosest_<partner> and <channel>_ClosestTo_<partner>.",
                    "Line distance: perpendicular distance from each centroid to drawn line ROIs (cortical surface, vessel). Off when no line set exists.",
                    "Ripley's K/L/G: per-channel point-pattern stats across radii - clustered versus dispersed. K counts neighbours within radius r; L stabilises K; G is the nearest-neighbour distance distribution."),
            section("When to use",
                    "When the question is 'how close to what' - proximity between markers, distance from a tissue feature, or clustered-versus-dispersed patterns."),
            section("Requires",
                    "Object CSVs from 3D Object Analysis.",
                    "Calibration for micron units; Ripley's needs calibrated centroids (XM_um, YM_um)."),
            section("Watch out",
                    "Distances are computed per ROI or region - objects in different ROIs are not compared.",
                    "Bad segmentation gives misleading distances; Ripley's is unstable for small ROIs and sparse channels."));

    public static final SetupHelpTopic COLOCALIZATION = topic(
            "spatial-colocalization",
            "Colocalization",
            "Marks each object as colocalised with partners in other channels and counts them. Two methods, chosen independently.",
            section("Sub-analyses",
                    "Volumetric overlap: reuses the percent-overlap 3D Object Analysis already saved. Colocalised when the nearest partner's overlap exceeds the per-channel Coloc Threshold (%).",
                    "CPC centroid coincidence: colocalised when an object's centroid falls inside a partner's label volume. Reads saved label images; skipped when CPC columns exist unless Force re-run is on."),
            section("When to use",
                    "Volumetric for soft 'how much overlaps' thresholding.",
                    "CPC for stricter 'is one inside another' geometry (nuclei within soma, puncta inside processes)."),
            section("Requires",
                    "Volumetric: saved overlap percentages from 3D Object Analysis.",
                    "CPC: object label images under FLASH/Results/Analysis Images/Segmentation/. Both need matching object CSVs."),
            section("Watch out",
                    "Thresholds are channel-specific - 10% overlap means different things for nuclei versus processes.",
                    "CPC is asymmetric ('A's centroid in B' is not 'B's centroid in A') and sensitive to one-voxel centroid noise."));

    public static final SetupHelpTopic BB_COLOCALIZATION = topic(
            "spatial-bb-colocalization",
            "Bounding-Box Colocalization",
            "Bounding-box (BB) colocalization from 3D Object Analysis: box-vs-box overlap, centroid-in-box, and box volume fill. Reuses saved BB columns.",
            section("Sub-analyses",
                    "Bounding-box overlap: max box intersection as a percent of the source box (BBColoc).",
                    "BB centroid coincidence (BB-CPC): the object's centroid lies inside a partner object's bounding box.",
                    "Bounding-box volume fill: partner voxels filling the object's box - best single partner and total of all partners (BBVolColoc / BBVolColocTotal)."),
            section("When to use",
                    "As a fast, permissive proximity screen - box overlap is cheap and tolerant of small gaps that defeat voxel overlap.",
                    "BB-CPC for containment when the box is a forgiving stand-in for the partner volume.",
                    "Volume fill for how densely partner signal packs the box - one dominant partner (best) versus crowding from many (total)."),
            section("Requires",
                    "Saved BB columns from 3D Object Analysis, or label images to recompute.",
                    "A per-channel BB Coloc Threshold (%), separate from the volumetric threshold."),
            section("Watch out",
                    "BB metrics use boxes, not voxels, so they are more permissive than volumetric/CPC colocalization.",
                    "Threshold fields are active only while a BB toggle is on and not locked from 3D Object Analysis."));

    public static final SetupHelpTopic VORONOI = topic(
            "spatial-voronoi",
            "Voronoi Tessellation",
            "2D Voronoi tessellation from calibrated centroids: each object claims the space closest to it. Reports per-object territory area, neighbour count, and an inter-channel interaction matrix with a permutation test.",
            section("Sub-analyses",
                    "Voronoi territory analysis: writes per-channel Voronoi_<channel>.csv and Interaction_Matrix.csv."),
            section("When to use",
                    "Whether cells of two channels neighbour each other more or less than chance.",
                    "How evenly a population tiles tissue."),
            section("Requires",
                    "Object CSVs with calibrated centroids.",
                    "Enough objects per ROI for tessellation to be meaningful."),
            section("Watch out",
                    "Territories are clipped to a derived rectangular window, not the drawn ROI, so edge cells stay window-sensitive.",
                    "Small ROIs and sparse channels give noisy areas; under-segmentation reduces apparent interaction."));

    public static final SetupHelpTopic MORPHOMETRY = topic(
            "spatial-morphometry",
            "Morphometric Analysis",
            "Re-reads saved object label images and appends per-object shape features to the object CSVs as Morph_ columns. Later toggles depend on earlier ones.",
            section("Sub-analyses",
                    "2D morphology: area, circularity, solidity, Feret and other planar descriptors. Use when 3D is unstable or unnecessary.",
                    "3D shape features: sphericity, compactness, elongation, flatness, 3D Feret and moments via mcib3d.",
                    "Complex shape analysis: composite indices from the 3D features (Ramification Index, Surface Roughness, Process Burden, 3D Sholl, skeleton counts). Needs 3D shape features.",
                    "Population morphometric scoring: population-normalised composites (CMS, SMSD, IMDI, MDS). Needs complex shape analysis.",
                    "Spatial-morphometric analysis: shape with spatial context (TDR, FEV, PPRP). Needs 3D shape features plus distances and/or Voronoi."),
            section("When to use",
                    "Whenever the question is cell shape - microglial activation, astrocyte ramification, neurite extension.",
                    "When shape varies with spatial context (proximity to pathology, neighbourhood density)."),
            section("Requires",
                    "Saved object label images from 3D Object Analysis.",
                    "Reasonable Z calibration; the dialog enforces the 3D -> complex -> population chain."),
            section("Watch out",
                    "Anisotropic stacks bias 3D shape values; population statistics need enough objects per group.",
                    "Inspect a few cells per condition before treating composite indices as quantitative."));

    public static final SetupHelpTopic PHENOTYPING = topic(
            "spatial-phenotyping",
            "Cell Phenotyping",
            "Groups objects into unsupervised clusters from their volume, surface, intensity, and colocalisation columns. Writes a Cluster column per object and a Phenotyping/Clusters_<channel>.csv per channel.",
            section("Sub-analyses",
                    "K-means clustering: standard k-means in feature space.",
                    "Clusters (k): number of clusters. k = 0 auto-selects the best between 2 and 10 by silhouette score."),
            section("When to use",
                    "For data-driven groupings from existing object size, intensity, and colocalisation measurements, without hand-picked thresholds."),
            section("Requires",
                    "Object CSVs with at least two usable feature columns (volume, surface, intensity, colocalisation).",
                    "Morph_ columns from morphometric analysis are not used by this step."),
            section("Watch out",
                    "K-means assumes roughly spherical clusters and depends on which features are present and their scaling.",
                    "Cluster labels are not stable identities across runs - read them by feature profile, not by index."));

    public static final SetupHelpTopic HEATMAPS = topic(
            "spatial-heatmaps",
            "Density Heatmaps",
            "Per-channel object-density maps by smoothing centroids with a Gaussian kernel (KDE, kernel density estimation). Saved as TIFF and PNG to FLASH/Results/Analysis Images/Spatial Heatmaps/.",
            section("Sub-analyses",
                    "Generate density heatmaps: turn on the export.",
                    "KDE bandwidth (um, 0=auto): kernel width in microns; 0 uses Scott's rule, a data-driven default.",
                    "Heatmap LUT: colour palette (Fire, Grays, Cyan, Green, Magenta, Red)."),
            section("When to use",
                    "For a visual map of marker density across a section - figures, hotspot spotting, descriptive review before quantitative analysis."),
            section("Requires",
                    "Object CSVs with calibrated centroids.",
                    "Image dimensions from the label images."),
            section("Watch out",
                    "Bandwidth dominates the look - too small looks like dots, too large washes out structure.",
                    "Heatmaps are display outputs; treat them as descriptive, not as the statistical evidence."));

    private static final Map<String, SetupHelpTopic> TOPICS = buildIndex();

    private SpatialHelpCatalog() {
    }

    public static SetupHelpTopic forKey(String key) {
        return TOPICS.get(key);
    }

    public static Map<String, SetupHelpTopic> all() {
        return TOPICS;
    }

    private static Map<String, SetupHelpTopic> buildIndex() {
        Map<String, SetupHelpTopic> map = new LinkedHashMap<String, SetupHelpTopic>();
        SetupHelpTopic[] entries = new SetupHelpTopic[] {
                DISTANCES, COLOCALIZATION, BB_COLOCALIZATION, VORONOI, MORPHOMETRY, PHENOTYPING, HEATMAPS };
        for (SetupHelpTopic t : entries) {
            if (map.put(t.key, t) != null) {
                throw new IllegalStateException("Duplicate spatial helper key: " + t.key);
            }
        }
        return Collections.unmodifiableMap(map);
    }

    private static SetupHelpTopic topic(String key, String title, String summary,
                                        SetupHelpTopic.Section... sections) {
        return new SetupHelpTopic(key, title, summary, Arrays.asList(sections));
    }

    private static SetupHelpTopic.Section section(String heading, String... items) {
        return new SetupHelpTopic.Section(heading, Arrays.asList(items));
    }
}
