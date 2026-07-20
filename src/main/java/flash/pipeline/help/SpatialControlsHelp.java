package flash.pipeline.help;

import flash.pipeline.FLASH_Pipeline;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * In-depth per-control manual for the Spatial Analysis options dialog.
 *
 * <p>Content is grouped to match the on-screen sections. Summaries reuse the
 * dialog's own hover strings so the manual and the tooltips stay in sync;
 * details add the "when to use / requires / watch out" notes previously reached
 * only through the per-section "?" buttons (now consolidated into the single
 * analysis "?").
 */
public final class SpatialControlsHelp {

    private SpatialControlsHelp() {
    }

    static ControlHelpTopic topic() {
        List<ControlHelpTopic.Group> groups = new ArrayList<ControlHelpTopic.Group>();

        groups.add(g("General", null,
                c("Force re-run all sub-analyses (ignore existing outputs)", "Toggle - default Off",
                        "Recomputes selected spatial outputs even when matching files or columns already exist.",
                        "Each sub-analysis normally skips when its output is already present; this bypasses that skip for every one.",
                        "Turn on after changing settings or re-running upstream segmentation; leave off to resume a run cheaply.")));

        groups.add(g("Spatial Distances",
                "Distance relationships between objects. Each method runs independently and writes its own columns or files.",
                c("Nearest neighbor distances", "Toggle - default On",
                        "Computes 3D nearest neighbor distance between every channel pair.",
                        "Adds <channel>_DistToClosest_<partner> and <channel>_ClosestTo_<partner> columns.",
                        "Distances are computed within each ROI or region - objects in different ROIs are not compared."),
                c("Line distance to drawn line ROI sets", "Toggle - default Off",
                        "Perpendicular distance from each object centroid to drawn line ROI sets (cortical surface, vessel, etc.).",
                        "Disabled until a line set exists under FLASH/Results/Tables/Line Distance/Line Sets - draw one first, then rerun.",
                        "Uses all line sets found in that folder."),
                c("Spatial statistics (Ripley's K/L/G)", "Toggle - Advanced - default Off",
                        "Point pattern analysis per channel (requires calibrated centroids).",
                        "Reports clustered-versus-dispersed patterns across radii: K counts neighbours within radius r, L stabilises K, G is the nearest-neighbour distance distribution.",
                        "Unstable for small ROIs and sparse channels.")));

        groups.add(g("Colocalization",
                "Marks each object as colocalised with partners in other channels and counts them. Choose any combination; set the per-channel cutoffs below.",
                c("Volumetric overlap", "Toggle - default Off",
                        "Counts nearest-neighbor objects exceeding the colocalization threshold, reusing the percent-overlap 3D Object Analysis already saved.",
                        "Colocalised when the nearest partner's voxel overlap exceeds this channel's Coloc % below.",
                        "May be locked on when it was already selected in 3D Object Analysis."),
                c("CPC centroid coincidence", "Toggle - default On",
                        "Centroid-in-object colocalization from saved label images.",
                        "Colocalised when an object's centroid falls inside a partner's label volume; asymmetric (A-in-B is not B-in-A) and sensitive to one-voxel centroid noise.",
                        "Reads saved label images; skipped when CPC columns already exist unless Force re-run is on."),
                c("Bounding-box overlap", "Toggle - default Off",
                        "Box-versus-box overlap percentage. Reuses saved BBColoc columns from 3D Object Analysis.",
                        "Uses bounding boxes rather than voxels, so it is more permissive than volumetric or CPC colocalization.",
                        "Uses this channel's BB Coloc % cutoff below."),
                c("Bounding-box centroid coincidence (BB-CPC)", "Toggle - default Off",
                        "Centroid-in-partner-box colocalization. Reuses saved BBCPC columns when present.",
                        "A forgiving stand-in for containment when the box approximates the partner volume."),
                c("Bounding-box volume fill", "Toggle - default Off",
                        "Partner voxels filling the bounding box. Reuses saved BBVolColoc columns when present.",
                        "Reports the single best partner and the total of all partners - one dominant partner versus crowding from many."),
                c("Coloc %", "Number per channel - default 30",
                        "Per-channel voxel-overlap cutoff used by Volumetric overlap.",
                        "Greyed out unless Volumetric overlap is on and not locked from 3D Object Analysis.",
                        "Channel-specific: 10% overlap means different things for nuclei versus processes."),
                c("BB Coloc %", "Number per channel - default 30",
                        "Per-channel bounding-box cutoff used by the box overlap and volume-fill methods.",
                        "Greyed out unless a bounding-box method is on.",
                        "Independent of the voxel Coloc % cutoff.")));

        groups.add(g("Resolve Fused Objects",
                "Touching objects are segmented as one blob. A marker channel flags fused/clustered objects by counting marker centroids inside each object.",
                c("Marker channel", "Dropdown - default None",
                        "Choose a nuclear or cellular marker; FLASH counts how many marker centroids land inside each object.",
                        "Writes OverlapCount, HasMarker, and IsCluster columns and enables the per-channel target toggles.",
                        "The marker cannot be its own target; leave on None to disable.")));

        groups.add(g("Voronoi Tessellation",
                "2D Voronoi tessellation from calibrated centroids: each object claims the space closest to it.",
                c("Voronoi territory analysis", "Toggle - Advanced - default Off",
                        "Computes Voronoi territories per object: territory area, neighbor count, and inter-channel interaction matrix with permutation test.",
                        "Writes Voronoi_<channel>.csv and Interaction_Matrix.csv; needs JTS and enough objects per ROI to be meaningful.",
                        "Territories are clipped to a derived rectangular window, not the drawn ROI, so edge cells stay window-sensitive.")));

        groups.add(g("Morphometric Analysis",
                "Per-object shape descriptors and derived indices from saved label images. Later toggles depend on earlier ones.",
                c("Extract 2D morphology from label images", "Toggle - default Off",
                        "Loads saved object label images and extracts 2D shape features (area, circularity, solidity, Feret diameter, etc.).",
                        "Use when 3D is unstable or unnecessary. Adds Morph_ columns to the object CSVs."),
                c("3D shape features", "Toggle - default Off",
                        "Extracts per-object 3D shape descriptors via mcib3d: sphericity, compactness, elongation, flatness, spareness, 3D Feret, moments, and centroid-to-surface statistics.",
                        "Gates Complex shape analysis, Population morphometric scoring, and Spatial-morphometric analysis.",
                        "Anisotropic Z stacks bias 3D shape values - check Z calibration first."),
                c("Complex shape analysis", "Toggle - default Off",
                        "Derives composite indices from the 3D features: Ramification Index, Surface Roughness, Process Burden, Morphological Polarity, Volume-Span Discrepancy, 3D Sholl, and skeleton counts.",
                        "Requires 3D shape features; gates Population morphometric scoring.",
                        "Inspect a few cells per condition before treating composite indices as quantitative."),
                c("Population morphometric scoring", "Toggle - Advanced - default Off",
                        "Population-normalised composites: Composite Morphological Score, Shape Moment Signature Distance, Intensity-Morphology Dissociation Index, Morphological Diversity Score.",
                        "Requires Complex shape analysis and enough objects per group to be stable."),
                c("Spatial-morphometric analysis", "Toggle - Advanced - default Off",
                        "Shape with spatial context: Territorial Dominance Ratio, Feret Eccentricity Vector, Pathology Proximity Response Profile.",
                        "Requires 3D shape features plus distances and/or Voronoi.")));

        groups.add(g("Object Texture and Complexity",
                "Per-object texture and complexity features. These passes are slow.",
                c("Object texture (GLCM; slow)", "Toggle - default Off",
                        "Per-object grey-level co-occurrence (GLCM) texture features."),
                c("Object complexity (fractal + lacunarity; slow)", "Toggle - default Off",
                        "Per-object fractal dimension and lacunarity - how rough and how gappy the signal is."),
                c("Object texture classes (slow)", "Toggle - default Off",
                        "Clusters objects into texture classes.",
                        "Enables the Texture classes (k) field below."),
                c("Native-3D texture (GLCM + texture classes; very slow)", "Toggle - Advanced - default Off",
                        "Computes GLCM texture and texture classes natively in 3D rather than per slice. Very slow."),
                c("Texture classes (k)", "Number - Advanced - default auto",
                        "Number of texture clusters to form (clamped 2-10).",
                        "Active only when Object texture classes is on.")));

        groups.add(g("Cell Phenotyping",
                "Groups objects into unsupervised clusters from their existing size, intensity, and colocalisation columns.",
                c("K-means clustering", "Toggle - default Off",
                        "Clusters objects by multi-channel feature profile (volume, intensity, colocalization).",
                        "Writes a Cluster column per object and Phenotyping/Clusters_<channel>.csv per channel.",
                        "Cluster labels are not stable identities across runs - read them by feature profile, not by index."),
                c("Clusters (k, 0=auto)", "Number - default 0",
                        "Number of clusters. 0 auto-detects the best k between 2 and 10 by silhouette score.")));

        groups.add(g("Density Heatmaps",
                "Per-channel object-density maps by smoothing centroids with a Gaussian kernel. Display outputs, not statistical evidence.",
                c("Generate density heatmaps", "Toggle - default Off",
                        "Gaussian KDE density maps per channel, saved as TIFF and PNG to FLASH/Results/Analysis Images/Spatial Heatmaps/."),
                c("KDE bandwidth (um, 0=auto)", "Number - default 0",
                        "Kernel width in microns. 0 uses Scott's rule, a data-driven default.",
                        "Bandwidth dominates the look - too small looks like dots, too large washes out structure."),
                c("Heatmap LUT", "Dropdown - default Fire",
                        "Colour palette for the heatmap (Fire, Grays, Cyan, Green, Magenta, Red).")));

        return new ControlHelpTopic(FLASH_Pipeline.IDX_SPATIAL, "spatial-controls", "Spatial Analysis", groups);
    }

    private static ControlHelpTopic.Group g(String heading, String intro, ControlHelpTopic.Control... controls) {
        return new ControlHelpTopic.Group(heading, intro, Arrays.asList(controls));
    }

    private static ControlHelpTopic.Control c(String label, String badge, String summary, String... details) {
        return new ControlHelpTopic.Control(label, badge, summary, Arrays.asList(details));
    }
}
