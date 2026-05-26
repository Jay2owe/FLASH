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
            "Computes distance-based measurements for each object. Three sub-analyses run independently; each writes its own columns or summary files.",
            section("Sub-analyses",
                    "Nearest neighbor distances: for every object, the 3D distance and ID of the closest object in each other channel. Adds columns <channel>_DistToClosest_<partner> and <channel>_ClosestTo_<partner>.",
                    "Line distance: perpendicular distance from each object centroid to drawn line ROI sets (cortical surface, white-matter border, vessel). Disabled when no line set exists in FLASH/Results/Tables/Line Distance/Line Sets.",
                    "Ripley's K/L/G: per-channel point-pattern statistics across radii. K(r) counts the expected number of additional objects within radius r of a given object; L(r) is a variance-stabilised K; G(r) is the cumulative nearest-neighbour distance distribution."),
            section("When to use",
                    "Whenever the question is 'how close to what' - proximity between marker types, distance from a tissue feature, or aggregated-versus-dispersed patterns."),
            section("Requires",
                    "Object CSVs from 3D Object Analysis.",
                    "Calibration metadata for micron-scaled output.",
                    "Ripley's needs calibrated centroids (XM_um, YM_um)."),
            section("Watch out",
                    "Distances are computed per ROI or region - objects in different ROIs are not compared.",
                    "Bad segmentation produces misleading distances.",
                    "Ripley's K/L at large r is biased by edge effects; small ROIs and sparse channels give unstable results."));

    public static final SetupHelpTopic COLOCALIZATION = topic(
            "spatial-colocalization",
            "Colocalization",
            "Marks each object as colocalised with partners in other channels and counts the colocalised partners. Two methods, picked independently.",
            section("Sub-analyses",
                    "Volumetric overlap: uses the percent-overlap values 3D Object Analysis already saved. An object is colocalised when its nearest partner's overlap exceeds the per-channel Coloc Threshold (%). Threshold fields are enabled only while the toggle is on.",
                    "CPC centroid coincidence: geometry-based. An object is colocalised when its centroid falls inside a partner object's label volume. Reads saved label images. Skipped when CPC columns already exist (unless Force re-run is on)."),
            section("When to use",
                    "Volumetric for soft 'how much overlaps' thresholding.",
                    "CPC for stricter 'is one inside another' geometry (engulfment, nuclei within cell bodies, puncta inside processes)."),
            section("Requires",
                    "Volumetric needs saved colocalisation percentages from 3D Object Analysis.",
                    "CPC needs object label images under FLASH/Results/Analysis Images/Objects/Masks and Label Maps/.",
                    "Both need matching object CSVs."),
            section("Watch out",
                    "Thresholds are channel-specific - 10% overlap means different things for nuclei versus processes.",
                    "CPC is asymmetric: 'A's centroid in B' is not the same as 'B's centroid in A'.",
                    "CPC is sensitive to one-voxel segmentation noise at the centroid location."));

    public static final SetupHelpTopic VORONOI = topic(
            "spatial-voronoi",
            "Voronoi Tessellation",
            "Builds a 2D Voronoi tessellation from calibrated object centroids: each object claims the region of space closer to it than to any other object. Reports per-object territory area, neighbour count, and an inter-channel interaction matrix with a permutation test for significance.",
            section("Sub-analyses",
                    "Voronoi territory analysis: produces per-channel Voronoi_<channel>.csv and Interaction_Matrix.csv."),
            section("When to use",
                    "When the question is whether cells of two channels neighbour each other more or less than chance.",
                    "When you need to quantify how evenly a population tiles tissue."),
            section("Requires",
                    "Object CSVs with calibrated centroids.",
                    "Enough objects per ROI for tessellation to be meaningful."),
            section("Watch out",
                    "Territories are clipped to a derived rectangular observation window, not the drawn ROI outline, so edge cells remain sensitive to that window.",
                    "Small ROIs and sparse channels give noisy territory areas.",
                    "The permutation test reflects only the objects present - under-segmentation reduces apparent interaction."));

    public static final SetupHelpTopic MORPHOMETRY = topic(
            "spatial-morphometry",
            "Morphometric Analysis",
            "Re-reads saved object label images and computes per-object shape features, appended to the object CSVs as Morph_ columns. The later composite, population, and spatial-morphometric toggles depend on prerequisite 3D, complex, or spatial columns.",
            section("Sub-analyses",
                    "Extract 2D morphology: area, circularity, solidity, Feret diameter, and similar planar descriptors. Useful when 3D is unstable or unnecessary.",
                    "3D shape features: sphericity, compactness, elongation, flatness, spareness, 3D Feret, 3D moments, centroid-to-surface distance statistics via mcib3d.",
                    "Complex shape analysis: composite indices from the 3D features - Ramification Index (RI), Surface Roughness Index (SRI), Process Burden (PB), Morphological Polarity (MP), Volume-Span Discrepancy (VSD). Requires 3D shape features.",
                    "Population morphometric scoring: population-normalised composites - Composite Morphological Score (CMS), Shape Moment Signature Distance (SMSD), Intensity-Morphology Dissociation Index (IMDI), Morphological Diversity Score (MDS). Requires Complex shape analysis.",
                    "Spatial-morphometric analysis: combines shape with spatial context - Territorial Dominance Ratio (TDR), Feret Eccentricity Vector (FEV), Pathology Proximity Response Profile (PPRP). Requires 3D shape features plus distances and/or Voronoi for the corresponding columns."),
            section("When to use",
                    "Whenever the question is about cell shape - microglial activation, astrocyte ramification, neurite extension.",
                    "When shape varies with spatial context (proximity to pathology, neighbourhood density)."),
            section("Requires",
                    "Saved object label images from 3D Object Analysis.",
                    "Reasonable Z calibration for 3D features.",
                    "The 3D -> Complex -> Population dependency chain, plus 3D shape prerequisites for spatial morphometrics, is enforced by the dialog."),
            section("Watch out",
                    "Anisotropic stacks (large Z-step relative to XY pixel size) bias 3D shape values.",
                    "Population statistics need enough objects per group.",
                    "PPRP needs a meaningful reference channel and adequate object counts per distance bin.",
                    "Inspect a few cells per condition before treating composite indices as quantitative."));

    public static final SetupHelpTopic PHENOTYPING = topic(
            "spatial-phenotyping",
            "Cell Phenotyping",
            "Groups objects into unsupervised clusters using available volume, surface, intensity, and colocalisation feature columns. Writes a Cluster column per object and a Phenotyping/Clusters_<channel>.csv summary per channel.",
            section("Sub-analyses",
                    "K-means clustering: standard k-means in feature space.",
                    "Clusters (k): number of clusters. k = 0 auto-selects the optimum between 2 and 10 by silhouette score."),
            section("When to use",
                    "When you want data-driven groupings from existing object size, intensity, and colocalisation measurements without hand-picked thresholds."),
            section("Requires",
                    "Object CSVs with at least two usable feature columns, such as volume, surface, intensity, or colocalisation measurements.",
                    "Morph_ columns from morphometric analysis are not currently used by this clustering step."),
            section("Watch out",
                    "K-means assumes roughly spherical clusters in feature space.",
                    "Results depend strongly on which features are present and on feature scaling.",
                    "Cluster labels are not stable identities across runs - interpret by feature profile, not by cluster index."));

    public static final SetupHelpTopic HEATMAPS = topic(
            "spatial-heatmaps",
            "Density Heatmaps",
            "Builds per-channel object-density maps by smoothing centroids with a Gaussian kernel (KDE, kernel density estimation). Saved as TIFF and PNG to FLASH/Results/Analysis Images/Spatial Heatmaps/.",
            section("Sub-analyses",
                    "Generate density heatmaps: turn on the export.",
                    "KDE bandwidth (um, 0=auto): kernel width in microns. 0 uses Scott's rule, a data-driven automatic choice.",
                    "Heatmap LUT: colour palette - Fire, Grays, Cyan, Green, Magenta, or Red."),
            section("When to use",
                    "When you want a visual map of marker density across a section - for figures, hotspot spotting, or descriptive review before quantitative analysis."),
            section("Requires",
                    "Object CSVs with calibrated centroids.",
                    "Image dimensions from the label images."),
            section("Watch out",
                    "Bandwidth dominates the visual result - too small looks like dots, too large washes out structure.",
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
                DISTANCES, COLOCALIZATION, VORONOI, MORPHOMETRY, PHENOTYPING, HEATMAPS };
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
