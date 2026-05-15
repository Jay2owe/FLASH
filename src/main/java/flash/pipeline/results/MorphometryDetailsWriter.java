package flash.pipeline.results;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;

/**
 * Writes analysis details for 3D morphometric features and composite indices
 * to {@code FLASH/Image Analysis/Spatial Analysis/Morphometry/Morphometry_Details.txt}.
 */
public final class MorphometryDetailsWriter {

    private MorphometryDetailsWriter() {}

    /**
     * Writes the morphometry analysis details file.
     *
     * @param morphometryDir  the spatial-owned morphometry directory
     * @param do3DShape       whether raw 3D shape features were extracted
     * @param doComposites    whether Tier 1 composite indices were computed
     * @param doPopulation    whether Tier 2 population scoring was computed
     * @param doSpatial       whether Tier 3 spatial-morphometric features were computed
     */
    public static void write(File morphometryDir, boolean do3DShape, boolean doComposites,
                             boolean doPopulation, boolean doSpatial) {
        try {
            flash.pipeline.io.IoUtils.mustMkdirs(morphometryDir);
        } catch (java.io.IOException e) {
            ij.IJ.log("Failed to create morphometry directory " + morphometryDir + ": " + e.getMessage());
            return;
        }
        File out = new File(morphometryDir, "Morphometry_Details.txt");

        try (Writer w = new OutputStreamWriter(new FileOutputStream(out), StandardCharsets.UTF_8)) {
            w.write("=== 3D Morphometric Analysis Details ===\n\n");

            if (do3DShape) {
                w.write("<3D Morphometric Features>\n");
                w.write("Computed per object from saved label images using mcib3d-core.\n");
                w.write("All _um features use calibrated coordinates.\n\n");
                w.write("Raw features:\n");
                w.write("  Morph_Sphericity      Corrected sphericity. pi^(1/3) x (6V)^(2/3) / SA.\n");
                w.write("                        Range 0-1, where 1 = perfect sphere.\n");
                w.write("  Morph_Compactness     Corrected compactness. (36pi x V^2) / SA^3.\n");
                w.write("                        Range 0-1. Compactness = Sphericity^3.\n");
                w.write("  Morph_Elongation      Ellipsoid axis ratio R1/R2. >=1, 1 = equiaxial.\n");
                w.write("  Morph_Flatness        Ellipsoid axis ratio R2/R3. >=1, 1 = equiaxial.\n");
                w.write("  Morph_Spareness       Object Volume / Ellipsoid Volume. Range 0-1.\n");
                w.write("  Morph_MajorRadius_um  Largest ellipsoid semi-axis radius (um).\n");
                w.write("  Morph_Feret3D_um      Maximum surface-to-surface distance in 3D (um).\n");
                w.write("  Morph_Moment1..5      Rotation/scale-invariant 3D shape moments\n");
                w.write("                        (Sadjadi & Hall 1980). 5 independent descriptors.\n");
                w.write("  Morph_DistCenter_Min_um   Minimum centroid-to-surface distance (um).\n");
                w.write("  Morph_DistCenter_Max_um   Maximum centroid-to-surface distance (um).\n");
                w.write("  Morph_DistCenter_Mean_um  Mean centroid-to-surface distance (um).\n");
                w.write("  Morph_DistCenter_SD_um    SD of centroid-to-surface distances (um).\n");
                w.write("</3D Morphometric Features>\n\n");
            }

            if (doComposites) {
                w.write("<Complex Shape Analysis>\n");
                w.write("Derived per object from raw 3D features (Composite Indices).\n\n");
                w.write("  Morph_RI   Ramification Index = 1 / Sphericity.\n");
                w.write("             Equivalently SA / (pi^(1/3) x (6V)^(2/3)).\n");
                w.write("             >=1, where 1 = sphere. Higher = more complex surface.\n\n");
                w.write("  Morph_SRI  Surface Roughness Index = DistCenter_SD / DistCenter_Mean.\n");
                w.write("             Coefficient of variation of centroid-to-surface distances.\n");
                w.write("             Dimensionless, scale-independent. ~0 = smooth, >0.5 = rough.\n\n");
                w.write("  Morph_PB   Process Burden = 1 - Spareness.\n");
                w.write("             Range 0-1. 0 = fills ellipsoid, ~1 = mostly empty envelope.\n\n");
                w.write("  Morph_MP   Morphological Polarity = (Elong-1)/(Elong+Flat-2+eps).\n");
                w.write("             Range 0-1. 0 = disc/oblate, 0.5 = isotropic, 1 = rod/prolate.\n\n");
                w.write("  Morph_VSD  Volume-Span Discrepancy = log10(Feret^3 / Volume).\n");
                w.write("             ~0.28 for sphere. Higher = span disproportionate to volume.\n");
                w.write("</Complex Shape Analysis>\n\n");
            }

            if (doPopulation) {
                w.write("<Population Morphometric Indices>\n");
                w.write("Computed in Spatial Analysis using population-level normalisation.\n");
                w.write("Normalisation: percentile 2-98 min-max scaling per dataset.\n\n");
                w.write("  Morph_CMS   Composite Morphological Score.\n");
                w.write("              0.35 x norm(1-Sphericity) + 0.30 x norm(PB)\n");
                w.write("              + 0.20 x norm(SRI) + 0.15 x norm(Volume).\n");
                w.write("              Range 0-1. Higher = more complex morphology.\n\n");
                w.write("  Morph_SMSD  Shape Moment Signature Distance.\n");
                w.write("              Variance-weighted Euclidean distance from population mean\n");
                w.write("              in 5D moment space. Larger = more morphologically unusual.\n\n");
                w.write("  Morph_IMDI  Intensity-Morphology Dissociation Index.\n");
                w.write("              |z(MeanIntensity) - z(CMS)|.\n");
                w.write("              Higher = intensity and morphology disagree.\n\n");
                w.write("  MDS_Entropy Morphological Diversity Score.\n");
                w.write("              Shannon entropy of K-means cluster distribution.\n");
                w.write("              0 = monomorphic, log2(K) = maximum diversity.\n");
                w.write("              Reported per channel in PopulationSummary CSV.\n");
                w.write("</Population Morphometric Indices>\n\n");
            }

            if (doSpatial) {
                w.write("<Spatial-Morphometric Indices>\n");
                w.write("Cross-analysis features combining morphometry with spatial data.\n\n");
                w.write("  Morph_TDR     Territorial Dominance Ratio.\n");
                w.write("                VoronoiTerritory x N / TotalArea.\n");
                w.write("                1 = fair share, >1 = sparse region, <1 = crowded.\n");
                w.write("                Requires Voronoi analysis.\n\n");
                w.write("  Morph_FEV_Mag Feret Eccentricity Vector magnitude.\n");
                w.write("                Feret3D / (2 x MajorRadius).\n");
                w.write("                ~1 = smooth elongation, >>1 = single dominant extension.\n\n");
                w.write("  PPRP          Pathology Proximity Response Profile.\n");
                w.write("                Linear regression of CMS vs distance-to-nearest pathology.\n");
                w.write("                Gradient (score/um), R-squared, N reported per image pair.\n");
                w.write("                Requires nearest-neighbor distances + CMS.\n");
                w.write("</Spatial-Morphometric Indices>\n");
            }

            w.write("\n<Per-Object Texture and Complexity>\n");
            w.write("Optional Spatial Analysis metrics written under the MorphTexture_* prefix.\n");
            w.write("These features are computed per segmented object, then aggregated by Master Aggregation.\n\n");
            w.write("  MorphTexture_GLCMContrast      Grey-level co-occurrence matrix contrast.\n");
            w.write("  MorphTexture_GLCMASM           Angular second moment, also called GLCM energy.\n");
            w.write("  MorphTexture_GLCMCorrelation   GLCM correlation across neighbouring pixel values.\n");
            w.write("  MorphTexture_GLCMEntropy       Texture disorder from the GLCM probability matrix.\n");
            w.write("  MorphTexture_GLCMHomogeneity   Local grey-level similarity.\n");
            w.write("                                 GLCM values use 2D per-slice ObjectTextureGLCM results averaged across slices.\n\n");
            w.write("  MorphTexture_FractalDim        Box-counting fractal dimension from an XY object-mask projection.\n");
            w.write("  MorphTexture_FractalDim_R2     Goodness-of-fit for the log-log box-counting regression.\n");
            w.write("  MorphTexture_LacunarityMean    Mean gliding-box lacunarity across valid box scales.\n");
            w.write("  MorphTexture_LacunaritySpread  Spread of lacunarity across valid box scales.\n");
            w.write("                                 Fractal and lacunarity values are computed by ObjectFractal.\n\n");
            w.write("  MorphTexture_ClassLabel        Integer texture class assigned by Gabor + wavelet k-means.\n");
            w.write("  MorphTexture_ClassDistance     Distance to the assigned texture-class centroid.\n");
            w.write("  MorphTexture_F1..F8            Gabor and wavelet feature-vector values used for texture classes.\n");
            w.write("                                 Texture-class values use 2D per-slice ObjectTextureFeatures results averaged across slices.\n");
            w.write("Native-3D texture metrics are not part of v1; the current implementation uses 2D slice/projection summaries.\n");
            w.write("</Per-Object Texture and Complexity>\n");
        } catch (Exception e) {
            ij.IJ.log("Failed to write morphometry details: " + e.getMessage());
        }
    }
}
