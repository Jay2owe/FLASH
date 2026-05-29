package flash.pipeline.analyses;

import ij.IJ;
import ij.ImagePlus;
import ij.measure.Calibration;
import ij.process.ImageProcessor;

import flash.pipeline.FLASH_Pipeline;
import flash.pipeline.analyses.spatial.DiskLabelImageProvider;
import flash.pipeline.analyses.spatial.InMemoryLabelImageProvider;
import flash.pipeline.analyses.spatial.LabelImageProvider;
import flash.pipeline.analyses.spatial.SectionKey;
import flash.pipeline.analyses.spatial.SpatialArtifactScanner;
import flash.pipeline.analyses.spatial.SpatialArtifactStatus;
import flash.pipeline.analyses.spatial.SubAnalysis;
import flash.pipeline.analyses.wizard.SpatialAnalysisWizard;
import flash.pipeline.analyses.wizard.SpatialPreset;
import flash.pipeline.analyses.wizard.SpatialPresetIO;
import flash.pipeline.bin.ChannelConfigIO;
import flash.pipeline.bin.ChannelIdentities;
import flash.pipeline.cli.CLIConfig;
import flash.pipeline.intelligence.JunkFileFilter;
import flash.pipeline.intelligence.MetadataDiagnostics;
import flash.pipeline.naming.ChannelFilenameCodec;
import flash.pipeline.objects.CpcUtils;

import flash.pipeline.help.SpatialHelpCatalog;
import flash.pipeline.image.AdaptiveParallelism;
import flash.pipeline.io.CalibrationIO;
import flash.pipeline.io.CsvTableIO;
import flash.pipeline.io.DeferredImageSupplier;
import flash.pipeline.io.FlashProjectLayout;
import flash.pipeline.io.ImageSourceDispatcher;
import flash.pipeline.io.IoUtils;
import flash.pipeline.io.CsvSupport;
import flash.pipeline.io.CsvTableIO.ChannelData;
import flash.pipeline.morphometry.ObjectFractal;
import flash.pipeline.morphometry.ObjectPatch;
import flash.pipeline.morphometry.ObjectPatch3D;
import flash.pipeline.morphometry.ObjectPatchBuilder;
import flash.pipeline.morphometry.ObjectTextureFeatures;
import flash.pipeline.morphometry.ObjectTextureFeatures3D;
import flash.pipeline.morphometry.ObjectTextureGLCM;
import flash.pipeline.morphometry.ObjectTextureGLCM3D;
import flash.pipeline.results.MorphometryDetailsWriter;
import flash.pipeline.results.ObjectCsvColumnOrder;
import flash.pipeline.runtime.DependencyId;
import flash.pipeline.runtime.FeatureDependencyGate;
import flash.pipeline.spatial.CellClustering;
import flash.pipeline.spatial.DensityHeatmapGenerator;
import flash.pipeline.spatial.MorphologyExtractor;
import flash.pipeline.spatial.SpatialStatistics;
import flash.pipeline.spatial.VoronoiAnalysis;
import flash.pipeline.ui.PipelineDialog;
import flash.pipeline.ui.ToggleSwitch;
import flash.pipeline.ui.wizard.SetupHelperButton;

import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTextField;

import java.awt.Dimension;
import java.awt.GraphicsEnvironment;
import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Spatial distance analysis step for the FLASH pipeline.
 *
 * <p>Computes inter-marker nearest neighbor distances (3D Euclidean) between
 * every pair of object channels.
 *
 * <p>Input CSVs are read from {@code Results/Tables/Objects/} and updated
 * in-place with new distance columns. Pixel-space centroids ({@code XM/YM/ZM})
 * are preserved, while calibrated centroid columns ({@code XM_um/YM_um/ZM_um})
 * are appended when calibration is available.
 */
public class SpatialAnalysis implements Analysis {

    private static final double DEFAULT_COLOC_THRESHOLD = 30.0;
    private static final int DEFAULT_STATS_BINS = 10;
    private static final int DEFAULT_CSR_SIMULATIONS = 25;
    private static final int DEFAULT_VORONOI_PERMUTATIONS = 1000;
    private static final int DEFAULT_CLUSTER_MIN_K = 2;
    private static final int DEFAULT_CLUSTER_MAX_K = 10;
    private static final String DEFAULT_HEATMAP_LUT = "Fire";
    private static final String SPATIAL_PRESET_PLACEHOLDER = "(choose preset)";
    private static final String WINDOW_SOURCE_DERIVED = "derived_from_centroids";
    private static final String XM_UM = "XM_um";
    private static final String YM_UM = "YM_um";
    private static final String ZM_UM = "ZM_um";
    private static final long CPC_MEMORY_SAFETY_MULTIPLIER = 3L;
    private static final String[] MORPH_2D_COLUMNS = {
            "Morph_Area_um2",
            "Morph_Perimeter_um",
            "Morph_Circularity",
            "Morph_Solidity",
            "Morph_AspectRatio",
            "Morph_Feret_um",
            "Morph_Extent",
            "Morph_ConvexHullArea_um2"
    };
    private static final String[] MORPH_3D_COLUMNS = {
            "Morph_Sphericity",
            "Morph_Compactness",
            "Morph_Elongation",
            "Morph_Flatness",
            "Morph_Spareness",
            "Morph_MajorRadius_um",
            "Morph_Feret3D_um",
            "Morph_Moment1",
            "Morph_Moment2",
            "Morph_Moment3",
            "Morph_Moment4",
            "Morph_Moment5",
            "Morph_DistCenter_Min_um",
            "Morph_DistCenter_Max_um",
            "Morph_DistCenter_Mean_um",
            "Morph_DistCenter_SD_um"
    };
    private static final String[] MORPH_COMPOSITE_COLUMNS = {
            "Morph_RI",
            "Morph_SRI",
            "Morph_PB",
            "Morph_MP",
            "Morph_VSD"
    };
    private static final String[] MORPH_POPULATION_COLUMNS = {
            "Morph_CMS",
            "Morph_SMSD",
            "Morph_IMDI"
    };
    private static final String[] MORPH_SPATIAL_COLUMNS = {
            "Morph_TDR",
            "Morph_FEV_Mag"
    };
    private static final String[] MORPH_TEXTURE_FRACTAL_COLUMNS = {
            "MorphTexture_FractalDim",
            "MorphTexture_FractalDim_R2",
            "MorphTexture_LacunarityMean",
            "MorphTexture_LacunaritySpread"
    };
    private static final String[] MORPH_TEXTURE_GLCM_COLUMNS = {
            "MorphTexture_GLCMContrast",
            "MorphTexture_GLCMASM",
            "MorphTexture_GLCMCorrelation",
            "MorphTexture_GLCMEntropy",
            "MorphTexture_GLCMHomogeneity"
    };
    private static final String[] MORPH_TEXTURE_GLCM3D_COLUMNS = {
            "MorphTexture_GLCM3DContrast",
            "MorphTexture_GLCM3DASM",
            "MorphTexture_GLCM3DCorrelation",
            "MorphTexture_GLCM3DEntropy",
            "MorphTexture_GLCM3DHomogeneity"
    };
    private static final String[] MORPH_TEXTURE_CLASS_COLUMNS = {
            "MorphTexture_ClassLabel",
            "MorphTexture_ClassDistance",
            "MorphTexture_F1",
            "MorphTexture_F2",
            "MorphTexture_F3",
            "MorphTexture_F4",
            "MorphTexture_F5",
            "MorphTexture_F6",
            "MorphTexture_F7",
            "MorphTexture_F8"
    };
    private static final String[] MORPH_TEXTURE_CLASS3D_COLUMNS = {
            "MorphTexture_Class3DLabel",
            "MorphTexture_Class3DDistance",
            "MorphTexture_F3D1",
            "MorphTexture_F3D2",
            "MorphTexture_F3D3",
            "MorphTexture_F3D4",
            "MorphTexture_F3D5",
            "MorphTexture_F3D6",
            "MorphTexture_F3D7",
            "MorphTexture_F3D8"
    };

    private Map<String, Double> markerThresholds = new LinkedHashMap<String, Double>();
    private final Map<String, ScnParityFallbackSummary> scnParityFallbackCache =
            new HashMap<String, ScnParityFallbackSummary>();
    private final Set<String> scnParityFallbackWarnings = new LinkedHashSet<String>();
    private final Set<String> scnParityFallbackInfoLogs = new LinkedHashSet<String>();
    private final Map<String, Map<SectionKey, List<Integer>>> cpcSectionGroupsCache =
            new HashMap<String, Map<SectionKey, List<Integer>>>();
    private final Map<String, File> cpcLabelFileCache = new HashMap<String, File>();
    private final Map<String, File> imageAnalysisAnimalDirCache = new HashMap<String, File>();
    private SpatialExecutionContext activeExecution = null;
    private boolean headless = false;
    private boolean suppressDialogs = false;
    private boolean verboseLogging = false;
    private int parallelThreads = Math.max(1, Runtime.getRuntime().availableProcessors());
    private CLIConfig cliConfig = null;
    private SpatialAnalysisWizard.DerivedConfig configuredOptions = null;
    private SpatialArtifactStatus spatialArtifactStatus = null;
    private boolean forceRerun = false;

    private enum RuntimeDependencyAction {
        PROCEED,
        RECONFIGURE,
        ABORT
    }

    private static final class SpatialExecutionContext {
        private File objectsDir;
        private LinkedHashMap<String, ChannelData> channels;
        private List<String> channelNames;
        private SpatialObjectDataAvailability existingObjectData;
        private CalibrationIO.PixelCalibration calibration;
        private File linesDir;
        private List<String> availableLineSets;
        private boolean hasLineRoiSets;
        private boolean doDistances;
        private boolean doLineDistance;
        private boolean doSpatialStats;
        private boolean doCpc;
        private boolean doVolumetric;
        private boolean doVoronoi;
        private boolean doHeatmaps;
        private boolean doPhenotyping;
        private boolean doMorphology;
        private boolean do3DShapeFeatures;
        private boolean doCompositeIndices;
        private boolean doPopMorphometrics;
        private boolean doSpatialMorphometrics;
        private boolean doObjectGLCM;
        private boolean doObjectFractal;
        private boolean doObjectTextureClass;
        private boolean doNative3DTexture;
        private boolean forceRerun;
        private SpatialArtifactStatus artifactStatus;
        private double heatmapBandwidth;
        private String heatmapLut;
        private int clusterK;
        private int textureClassK;
    }

    static final class SpatialObjectDataAvailability {
        private final Map<String, ChannelData> channels;
        private final List<String> channelNames;
        private final Map<String, Integer> objectRows = new LinkedHashMap<String, Integer>();
        private final Set<String> objectSizeChannels = new LinkedHashSet<String>();
        private final Set<String> processLengthChannels = new LinkedHashSet<String>();
        private final Set<String> morph2DChannels = new LinkedHashSet<String>();
        private final Set<String> morph3DChannels = new LinkedHashSet<String>();
        private final Set<String> morphCompositeChannels = new LinkedHashSet<String>();
        private final Set<String> morphPopulationChannels = new LinkedHashSet<String>();
        private final Set<String> morphSpatialChannels = new LinkedHashSet<String>();
        private final Set<String> morphTextureGlcmChannels = new LinkedHashSet<String>();
        private final Set<String> morphTextureFractalChannels = new LinkedHashSet<String>();
        private final Set<String> morphTextureClassChannels = new LinkedHashSet<String>();
        private final Set<String> morphTextureGlcm3DChannels = new LinkedHashSet<String>();
        private final Set<String> morphTextureClass3DChannels = new LinkedHashSet<String>();
        private final Set<String> distancePairs = new LinkedHashSet<String>();
        private final Set<String> volumetricOverlapPairs = new LinkedHashSet<String>();
        private final Set<String> volumetricFlagPairs = new LinkedHashSet<String>();
        private final Set<String> volumetricContainsPairs = new LinkedHashSet<String>();
        private final Set<String> cpcPairs = new LinkedHashSet<String>();

        private SpatialObjectDataAvailability(Map<String, ChannelData> channels, List<String> channelNames) {
            this.channels = channels;
            this.channelNames = channelNames == null
                    ? new ArrayList<String>()
                    : new ArrayList<String>(channelNames);
        }

        static SpatialObjectDataAvailability detect(Map<String, ChannelData> channels,
                                                    List<String> channelNames) {
            SpatialObjectDataAvailability detected =
                    new SpatialObjectDataAvailability(channels, channelNames);
            detected.scan();
            return detected;
        }

        private void scan() {
            if (channels == null) return;
            for (String channelName : channelNames) {
                ChannelData cd = channels.get(channelName);
                if (cd == null) continue;
                objectRows.put(channelName, Integer.valueOf(cd.rows.size()));
                if (hasAnyVolumeColumn(cd) && hasAnySurfaceColumn(cd)) objectSizeChannels.add(channelName);
                if (hasProcessLengthColumn(cd)) processLengthChannels.add(channelName);
                if (hasUsableColumns(cd, MORPH_2D_COLUMNS)) morph2DChannels.add(channelName);
                if (hasUsableColumns(cd, MORPH_3D_COLUMNS)) morph3DChannels.add(channelName);
                if (hasUsableColumns(cd, MORPH_COMPOSITE_COLUMNS)) morphCompositeChannels.add(channelName);
                if (hasUsableColumns(cd, MORPH_POPULATION_COLUMNS)) morphPopulationChannels.add(channelName);
                if (hasUsableColumns(cd, MORPH_SPATIAL_COLUMNS)) morphSpatialChannels.add(channelName);
                if (hasUsableColumns(cd, MORPH_TEXTURE_GLCM_COLUMNS)) morphTextureGlcmChannels.add(channelName);
                if (hasUsableColumns(cd, MORPH_TEXTURE_FRACTAL_COLUMNS)) morphTextureFractalChannels.add(channelName);
                if (hasUsableColumns(cd, MORPH_TEXTURE_CLASS_COLUMNS)) morphTextureClassChannels.add(channelName);
                if (hasUsableColumns(cd, MORPH_TEXTURE_GLCM3D_COLUMNS)) morphTextureGlcm3DChannels.add(channelName);
                if (hasUsableColumns(cd, MORPH_TEXTURE_CLASS3D_COLUMNS)) morphTextureClass3DChannels.add(channelName);
            }

            for (String source : channelNames) {
                ChannelData cd = channels.get(source);
                if (cd == null) continue;
                for (String partner : channelNames) {
                    if (source.equals(partner)) continue;
                    String key = directedKey(source, partner);
                    if (hasDirectedDistance(source, partner)) distancePairs.add(key);
                    if (findVolOverlapColumn(cd, source, partner) != null) volumetricOverlapPairs.add(key);
                    if (hasAnyUsableColumnWithPrefixSuffix(cd, source + "_VolColoc", "_" + partner)) {
                        volumetricFlagPairs.add(key);
                    }
                    if (hasAnyUsableColumnWithPrefixSuffix(cd, source + "_VolContains", "_" + partner)) {
                        volumetricContainsPairs.add(key);
                    }
                    if (hasDirectedCpc(source, partner)) cpcPairs.add(key);
                }
            }
        }

        void logDetectedData() {
            IJ.log("Detected reusable 3D object output data: " + detectedSummary());
        }

        String distanceHelperText() {
            if (!distancePairs.isEmpty()) {
                return "Detected existing nearest-neighbor distance columns for "
                        + pairCount(distancePairs) + " channel pair(s). Matching pairs will be reused.";
            }
            return "Loaded 3D Object Analysis object rows (" + objectCountSummary()
                    + "). Selected missing distance columns will be computed.";
        }

        String colocalizationHelperText() {
            List<String> parts = new ArrayList<String>();
            if (!volumetricOverlapPairs.isEmpty()) {
                parts.add("volumetric overlap percentages");
            }
            if (!volumetricFlagPairs.isEmpty() || !volumetricContainsPairs.isEmpty()) {
                parts.add("thresholded volumetric columns");
            }
            if (!cpcPairs.isEmpty()) {
                parts.add("CPC contact columns");
            }
            if (parts.isEmpty()) {
                return "No saved colocalization/contact columns were detected. Selected outputs will be computed.";
            }
            return "Detected saved " + joinSummary(parts)
                    + ". Existing matching columns will be reused; missing selected outputs will be computed.";
        }

        String morphometryHelperText() {
            List<String> parts = new ArrayList<String>();
            if (!objectSizeChannels.isEmpty()) parts.add("3D object volume/surface data");
            if (!morph2DChannels.isEmpty()) parts.add("2D morphology columns");
            if (!morph3DChannels.isEmpty()) parts.add("3D shape columns");
            if (!morphCompositeChannels.isEmpty()) parts.add("complex shape columns");
            if (!morphPopulationChannels.isEmpty()) parts.add("population morphometric columns");
            if (!morphSpatialChannels.isEmpty()) parts.add("spatial-morphometric columns");
            if (!morphTextureGlcmChannels.isEmpty()) parts.add("object GLCM texture columns");
            if (!morphTextureFractalChannels.isEmpty()) parts.add("object fractal texture columns");
            if (!morphTextureClassChannels.isEmpty()) parts.add("object texture-class columns");
            if (!morphTextureGlcm3DChannels.isEmpty()) parts.add("native-3D object GLCM texture columns");
            if (!morphTextureClass3DChannels.isEmpty()) parts.add("native-3D object texture-class columns");
            if (!processLengthChannels.isEmpty()) parts.add("process length data");
            if (parts.isEmpty()) {
                return "No reusable morphometric columns were detected. Selected morphology outputs will be computed.";
            }
            return "Detected " + joinSummary(parts)
                    + ". Existing columns will be reused; selected missing morphometry will be computed.";
        }

        boolean hasObjectSizeDataForAllChannels(List<String> names) {
            return allChannelsHave(objectSizeChannels, names);
        }

        boolean hasProcessLengthData() {
            return !processLengthChannels.isEmpty();
        }

        boolean hasDirectedVolumetricOverlap(String source, String partner) {
            return volumetricOverlapPairs.contains(directedKey(source, partner));
        }

        boolean hasVolumetricPair(String a, String b, int thresholdA, int thresholdB) {
            return hasDirectedVolumetricFlag(a, b, thresholdA)
                    && hasDirectedVolumetricFlag(b, a, thresholdB)
                    && hasDirectedVolumetricContains(a, b, thresholdB)
                    && hasDirectedVolumetricContains(b, a, thresholdA);
        }

        boolean hasDistancePair(String a, String b) {
            return distancePairs.contains(directedKey(a, b))
                    && distancePairs.contains(directedKey(b, a));
        }

        boolean hasCompleteCpcForAllChannels(List<String> names) {
            if (names == null || names.size() < 2) return false;
            for (String source : names) {
                ChannelData cd = channels.get(source);
                if (!hasUsableColumn(cd, source + "_CPCTargetsHit")
                        || !hasUsableColumn(cd, source + "_CPCPattern")) {
                    return false;
                }
                for (String partner : names) {
                    if (source.equals(partner)) continue;
                    if (!cpcPairs.contains(directedKey(source, partner))) return false;
                }
            }
            return true;
        }

        boolean has2DMorphologyForAllChannels(List<String> names) {
            return allChannelsHave(morph2DChannels, names);
        }

        boolean has3DMorphologyForAllChannels(List<String> names) {
            return allChannelsHave(morph3DChannels, names);
        }

        boolean hasCompositeMorphologyForAllChannels(List<String> names) {
            return allChannelsHave(morphCompositeChannels, names);
        }

        boolean hasPopulationMorphometricsForAllChannels(List<String> names) {
            return allChannelsHave(morphPopulationChannels, names);
        }

        boolean hasSpatialMorphometricsForAllChannels(List<String> names) {
            return allChannelsHave(morphSpatialChannels, names);
        }

        boolean hasObjectGLCMForAllChannels(List<String> names) {
            return allChannelsHave(morphTextureGlcmChannels, names);
        }

        boolean hasObjectFractalForAllChannels(List<String> names) {
            return allChannelsHave(morphTextureFractalChannels, names);
        }

        boolean hasObjectTextureClassForAllChannels(List<String> names) {
            return allChannelsHave(morphTextureClassChannels, names);
        }

        boolean hasObjectGLCM3DForAllChannels(List<String> names) {
            return allChannelsHave(morphTextureGlcm3DChannels, names);
        }

        boolean hasObjectTextureClass3DForAllChannels(List<String> names) {
            return allChannelsHave(morphTextureClass3DChannels, names);
        }

        private boolean hasDirectedDistance(String source, String partner) {
            ChannelData cd = channels.get(source);
            return hasUsableColumn(cd, source + "_DistToClosest_" + partner)
                    && hasUsableColumn(cd, source + "_ClosestTo_" + partner);
        }

        private boolean hasDirectedCpc(String source, String partner) {
            ChannelData cd = channels.get(source);
            return hasUsableColumn(cd, source + "_CPCColoc_" + partner)
                    && hasUsableColumn(cd, source + "_CPCContains_" + partner);
        }

        private boolean hasDirectedVolumetricFlag(String source, String partner, int threshold) {
            ChannelData cd = channels.get(source);
            return hasUsableColumn(cd, source + "_VolColoc" + threshold + "_" + partner);
        }

        private boolean hasDirectedVolumetricContains(String source, String partner, int threshold) {
            ChannelData cd = channels.get(source);
            return hasUsableColumn(cd, source + "_VolContains" + threshold + "_" + partner);
        }

        private static boolean hasAnyVolumeColumn(ChannelData cd) {
            if (cd == null) return false;
            for (String h : cd.header) {
                if (h.startsWith("Volume (") && h.contains("^3") && hasUsableColumn(cd, h)) return true;
            }
            return false;
        }

        private static boolean hasAnySurfaceColumn(ChannelData cd) {
            if (cd == null) return false;
            for (String h : cd.header) {
                if (h.startsWith("Surface (") && h.contains("^2") && hasUsableColumn(cd, h)) return true;
            }
            return false;
        }

        private static boolean hasProcessLengthColumn(ChannelData cd) {
            return hasUsableColumn(cd, "Length")
                    || hasUsableColumn(cd, "Process Length")
                    || hasUsableColumn(cd, "Process_Length");
        }

        private static boolean hasAnyUsableColumnWithPrefixSuffix(ChannelData cd,
                                                                  String prefix,
                                                                  String suffix) {
            if (cd == null) return false;
            for (String col : cd.header) {
                if (col.startsWith(prefix) && col.endsWith(suffix)
                        && hasUsableColumn(cd, col)) {
                    return true;
                }
            }
            return false;
        }

        private static boolean allChannelsHave(Set<String> detectedChannels, List<String> names) {
            if (names == null || names.isEmpty()) return false;
            for (String name : names) {
                if (!detectedChannels.contains(name)) return false;
            }
            return true;
        }

        private String detectedSummary() {
            List<String> parts = new ArrayList<String>();
            parts.add("object rows " + objectCountSummary());
            if (hasObjectSizeDataForAllChannels(channelNames)) parts.add("volume/surface columns");
            if (!volumetricOverlapPairs.isEmpty()) parts.add("volumetric overlap");
            if (!cpcPairs.isEmpty()) parts.add("CPC");
            if (!morph2DChannels.isEmpty()) parts.add("2D morphology");
            if (!morph3DChannels.isEmpty()) parts.add("3D shape");
            if (!morphCompositeChannels.isEmpty()) parts.add("complex shape");
            if (!morphPopulationChannels.isEmpty()) parts.add("population morphometrics");
            if (!morphSpatialChannels.isEmpty()) parts.add("spatial morphometrics");
            if (!morphTextureGlcmChannels.isEmpty()) parts.add("object GLCM texture");
            if (!morphTextureFractalChannels.isEmpty()) parts.add("object fractal texture");
            if (!morphTextureClassChannels.isEmpty()) parts.add("object texture class");
            if (!morphTextureGlcm3DChannels.isEmpty()) parts.add("native-3D object GLCM texture");
            if (!morphTextureClass3DChannels.isEmpty()) parts.add("native-3D object texture class");
            if (!processLengthChannels.isEmpty()) parts.add("process length");
            return joinSummary(parts);
        }

        private String objectCountSummary() {
            if (objectRows.isEmpty()) return "none";
            List<String> parts = new ArrayList<String>();
            for (Map.Entry<String, Integer> entry : objectRows.entrySet()) {
                parts.add(entry.getKey() + "=" + entry.getValue());
            }
            return joinSummary(parts);
        }

        private static int pairCount(Set<String> directedPairs) {
            Set<String> unique = new LinkedHashSet<String>();
            for (String key : directedPairs) {
                int idx = key.indexOf("->");
                if (idx < 0) continue;
                String a = key.substring(0, idx);
                String b = key.substring(idx + 2);
                unique.add(a.compareTo(b) <= 0 ? a + "<->" + b : b + "<->" + a);
            }
            return unique.size();
        }

        private static String directedKey(String source, String partner) {
            return source + "->" + partner;
        }

        private static String joinSummary(List<String> parts) {
            if (parts == null || parts.isEmpty()) return "none";
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < parts.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(parts.get(i));
            }
            return sb.toString();
        }
    }

    @Override
    public void setHeadless(boolean headless) {
        this.headless = headless;
    }

    @Override
    public void setSuppressDialogs(boolean suppress) {
        this.suppressDialogs = suppress;
    }

    @Override
    public void setVerboseLogging(boolean verbose) {
        this.verboseLogging = verbose;
    }

    @Override
    public void setParallelThreads(int threads) {
        this.parallelThreads = Math.max(1, threads);
    }

    @Override
    public void setCliConfig(CLIConfig config) {
        this.cliConfig = config;
    }

    public void setWizardConfig(SpatialAnalysisWizard.DerivedConfig config) {
        this.configuredOptions = config;
    }

    /**
     * Sets per-marker colocalisation thresholds.
     *
     * @param thresholds map of marker name to threshold percentage
     */
    public void setMarkerThresholds(Map<String, Double> thresholds) {
        if (thresholds != null) {
            this.markerThresholds = new LinkedHashMap<String, Double>(thresholds);
        }
    }

    private double getThreshold(String markerName) {
        Double t = markerThresholds.get(markerName);
        return (t != null) ? t : DEFAULT_COLOC_THRESHOLD;
    }

    @Override
    public void execute(String directory) {
        IJ.log("=== Spatial Analysis ===");
        if (!beginPhasedRun(directory)) {
            return;
        }

        try {
            computeInterMarkerDistancePhase(activeExecution);
            LabelImageProvider provider = new DiskLabelImageProvider(this, directory);
            runEarlyPhase(directory, provider);
            runLatePhase(directory);
            IJ.log("=== Spatial Analysis Complete ===");
        } finally {
            endPhasedRun();
        }
    }

    boolean beginPhasedRun(String directory) {
        scnParityFallbackCache.clear();
        scnParityFallbackWarnings.clear();
        scnParityFallbackInfoLogs.clear();
        cpcSectionGroupsCache.clear();
        cpcLabelFileCache.clear();
        imageAnalysisAnimalDirCache.clear();
        spatialArtifactStatus = null;
        forceRerun = false;
        activeExecution = null;

        SpatialExecutionContext context = prepareExecutionContext(directory);
        if (context == null) {
            return false;
        }

        activeExecution = context;
        return true;
    }

    void endPhasedRun() {
        activeExecution = null;
    }

    private SpatialExecutionContext prepareExecutionContext(String directory) {
        FlashProjectLayout layout = FlashProjectLayout.forDirectory(directory);
        File objectsDir = layout.tablesObjectsWriteDir();
        if (!objectsDir.isDirectory()) {
            warnUser("Spatial Analysis",
                    "Objects directory not found:\n" + objectsDir.getAbsolutePath()
                            + "\n\nRun 3D Object Analysis first.");
            return null;
        }

        CalibrationIO.PixelCalibration cal = CalibrationIO.read(objectsDir);
        if (cal != null && cal.isCalibrated()) {
            IJ.log("Using calibration: " + cal.pixelWidth + " x " + cal.pixelHeight
                    + " x " + cal.pixelDepth + " " + cal.unit + "/pixel");
        } else {
            IJ.log("Warning: no calibrated spatial metadata found. Distances will fall back to pixel units "
                    + "unless " + XM_UM + "/" + YM_UM + "/" + ZM_UM + " are already present.");
        }

        File[] csvFiles = objectsDir.listFiles(new java.io.FilenameFilter() {
            @Override
            public boolean accept(File dir, String name) {
                if (!name.toLowerCase(Locale.ROOT).endsWith(".csv")) return false;
                if (name.startsWith("temp_")) return false;
                if (name.contains("Analysis Details")) return false;
                if (name.equals("Spatial_Distances.csv")) return false;
                return true;
            }
        });

        if (csvFiles == null || csvFiles.length == 0) {
            warnUser("Spatial Analysis",
                    "No object CSV files found in:\n" + objectsDir.getAbsolutePath()
                            + "\n\nRun 3D Object Analysis first.");
            return null;
        }

        Arrays.sort(csvFiles);

        LinkedHashMap<String, ChannelData> channels = new LinkedHashMap<String, ChannelData>();
        for (File csvFile : csvFiles) {
            String safeChannelName = csvFile.getName();
            if (safeChannelName.toLowerCase(Locale.ROOT).endsWith(".csv")) {
                safeChannelName = safeChannelName.substring(0, safeChannelName.length() - 4);
            }
            String channelName = ChannelFilenameCodec.toRaw(safeChannelName);
            ChannelData cd = CsvTableIO.loadChannelCsv(csvFile, channelName);
            if (cd != null) {
                channels.put(channelName, cd);
                IJ.log("Loaded channel: " + channelName + " (" + cd.rows.size() + " objects)");
            }
        }

        if (channels.isEmpty()) {
            warnUser("Spatial Analysis",
                    "No valid object CSV data could be loaded from:\n" + objectsDir.getAbsolutePath());
            return null;
        }

        // Normalize legacy volumetric overlap columns before any downstream analysis.
        migrateLegacyVolumetricColumns(channels);

        appendCalibratedCentroids(channels, cal);

        List<String> channelNames = new ArrayList<String>(channels.keySet());
        SpatialObjectDataAvailability existingObjectData =
                SpatialObjectDataAvailability.detect(channels, channelNames);
        existingObjectData.logDetectedData();
        List<SectionKey> artifactSections = collectArtifactSections(directory, channels, channelNames);
        SpatialArtifactStatus artifactStatus =
                new SpatialArtifactScanner().scan(directory, channelNames, artifactSections);
        spatialArtifactStatus = artifactStatus;
        File linesDir = LineDistanceAnalysis.lineSetWriteDir(directory);
        List<String> availableLineSets = LineDistanceAnalysis.lineSetNames(directory);
        boolean hasLineRoiSets = !availableLineSets.isEmpty();

        // Options dialog
        boolean doDistances = true;
        boolean doLineDistance = false;
        boolean doSpatialStats = false;
        boolean doCpc = true;
        boolean doVolumetric = false;
        boolean doVoronoi = false;
        boolean doHeatmaps = false;
        boolean doPhenotyping = false;
        boolean doMorphology = false;
        boolean do3DShapeFeatures = false;
        boolean doCompositeIndices = false;
        boolean doPopMorphometrics = false;
        boolean doSpatialMorphometrics = false;
        boolean doObjectGLCM = false;
        boolean doObjectFractal = false;
        boolean doObjectTextureClass = false;
        boolean doNative3DTexture = false;
        boolean forceRerun = false;
        double heatmapBandwidth = 0; // 0 = auto (Scott's rule)
        String heatmapLut = DEFAULT_HEATMAP_LUT;
        int clusterK = 0; // 0 = auto-detect
        int textureClassK = ObjectTextureFeatures.DEFAULT_K;

        SpatialAnalysisWizard.DerivedConfig effectiveOptions = configuredOptions;
        SpatialAnalysisWizard.DerivedConfig cliDerived = loadCliSpatialConfig(directory);
        if (cliDerived != null) {
            effectiveOptions = cliDerived;
        }
        if (!suppressDialogs && effectiveOptions == null) {
            effectiveOptions = showSpatialOptionsDialog(directory, channelNames, existingObjectData,
                    availableLineSets, artifactStatus, null);
            if (effectiveOptions == null) {
                return null;
            }
        }
        if (effectiveOptions != null) {
            SpatialAnalysisWizard.enforceDependencies(effectiveOptions,
                    firstSeriesInfoOrNull(directory), calibrationIsAvailable(directory));
            doDistances = effectiveOptions.doDistances;
            doLineDistance = effectiveOptions.doLineDistance && hasLineRoiSets;
            doSpatialStats = effectiveOptions.doSpatialStats;
            doVolumetric = effectiveOptions.doVolColoc;
            doCpc = effectiveOptions.doCpc;
            doVoronoi = effectiveOptions.doVoronoi;
            doHeatmaps = effectiveOptions.doHeatmaps;
            doPhenotyping = effectiveOptions.doPhenotyping;
            doMorphology = effectiveOptions.do2DMorphology;
            do3DShapeFeatures = effectiveOptions.do3DMorphology;
            doCompositeIndices = effectiveOptions.doCompositeIndices;
            doPopMorphometrics = effectiveOptions.doPopMorphometrics;
            doSpatialMorphometrics = effectiveOptions.doSpatialMorphometrics;
            doObjectGLCM = effectiveOptions.doObjectGLCM;
            doObjectFractal = effectiveOptions.doObjectFractal;
            doObjectTextureClass = effectiveOptions.doObjectTextureClass;
            doNative3DTexture = effectiveOptions.doNative3DTexture;
            forceRerun = effectiveOptions.forceRerun;
            heatmapBandwidth = effectiveOptions.kdeBandwidth;
            heatmapLut = effectiveOptions.heatmapLut;
            clusterK = effectiveOptions.clusterK;
            textureClassK = Math.max(1, effectiveOptions.textureClassK);
            applyConfiguredMarkerThresholds(effectiveOptions, channelNames);
        }

        if (forceRerun) {
            IJ.log("--- Force re-run enabled: existing spatial outputs will be ignored ---");
            doDistances = true;
            doLineDistance = hasLineRoiSets;
            doSpatialStats = true;
            doCpc = true;
            doVoronoi = true;
            doHeatmaps = true;
            doPhenotyping = true;
            doMorphology = true;
            do3DShapeFeatures = true;
            doCompositeIndices = true;
            doPopMorphometrics = true;
            doSpatialMorphometrics = true;
        }
        this.forceRerun = forceRerun;

        if (!forceRerun && doPopMorphometrics
                && existingObjectData.hasPopulationMorphometricsForAllChannels(channelNames)) {
            IJ.log("--- Reusing existing population morphometric columns; skipping population scoring ---");
            doPopMorphometrics = false;
        }
        if (!forceRerun && doSpatialMorphometrics
                && existingObjectData.hasSpatialMorphometricsForAllChannels(channelNames)) {
            IJ.log("--- Reusing existing spatial-morphometric columns; skipping spatial morphometrics ---");
            doSpatialMorphometrics = false;
        }
        if (doPopMorphometrics) {
            doCompositeIndices = true;
        }
        if (doCompositeIndices || doSpatialMorphometrics) {
            do3DShapeFeatures = true;
        }
        if (!forceRerun && doMorphology && existingObjectData.has2DMorphologyForAllChannels(channelNames)) {
            IJ.log("--- Reusing existing 2D morphology columns; skipping 2D morphology extraction ---");
            doMorphology = false;
        }
        if (!forceRerun && do3DShapeFeatures
                && existingObjectData.has3DMorphologyForAllChannels(channelNames)
                && (!doCompositeIndices
                || existingObjectData.hasCompositeMorphologyForAllChannels(channelNames))) {
            IJ.log("--- Reusing existing 3D morphometry columns; skipping 3D shape extraction ---");
            if (doCompositeIndices) {
                IJ.log("--- Reusing existing complex shape columns; skipping composite shape derivation ---");
            }
            do3DShapeFeatures = false;
            doCompositeIndices = false;
        }
        if (!forceRerun && doObjectGLCM
                && existingObjectData.hasObjectGLCMForAllChannels(channelNames)) {
            IJ.log("--- Reusing existing object GLCM texture columns; skipping object GLCM texture ---");
            doObjectGLCM = false;
        }
        if (!forceRerun && doObjectFractal
                && existingObjectData.hasObjectFractalForAllChannels(channelNames)) {
            IJ.log("--- Reusing existing object fractal texture columns; skipping object fractal texture ---");
            doObjectFractal = false;
        }
        if (!forceRerun && doObjectTextureClass
                && existingObjectData.hasObjectTextureClassForAllChannels(channelNames)) {
            IJ.log("--- Reusing existing object texture-class columns; skipping object texture class ---");
            doObjectTextureClass = false;
        }
        if (!forceRerun && doNative3DTexture
                && existingObjectData.hasObjectGLCM3DForAllChannels(channelNames)
                && existingObjectData.hasObjectTextureClass3DForAllChannels(channelNames)) {
            IJ.log("--- Reusing existing native-3D object texture columns; skipping native-3D object texture ---");
            doNative3DTexture = false;
        }

        RuntimeDependencyAction dependencyAction =
                checkRuntimeDependencies(doVoronoi, do3DShapeFeatures);
        if (dependencyAction != RuntimeDependencyAction.PROCEED) {
            return null;
        }

        SpatialExecutionContext context = new SpatialExecutionContext();
        context.objectsDir = objectsDir;
        context.channels = channels;
        context.channelNames = channelNames;
        context.existingObjectData = existingObjectData;
        context.calibration = cal;
        context.linesDir = linesDir;
        context.availableLineSets = availableLineSets;
        context.hasLineRoiSets = hasLineRoiSets;
        context.doDistances = doDistances;
        context.doLineDistance = doLineDistance;
        context.doSpatialStats = doSpatialStats;
        context.doCpc = doCpc;
        context.doVolumetric = doVolumetric;
        context.doVoronoi = doVoronoi;
        context.doHeatmaps = doHeatmaps;
        context.doPhenotyping = doPhenotyping;
        context.doMorphology = doMorphology;
        context.do3DShapeFeatures = do3DShapeFeatures;
        context.doCompositeIndices = doCompositeIndices;
        context.doPopMorphometrics = doPopMorphometrics;
        context.doSpatialMorphometrics = doSpatialMorphometrics;
        context.doObjectGLCM = doObjectGLCM;
        context.doObjectFractal = doObjectFractal;
        context.doObjectTextureClass = doObjectTextureClass;
        context.doNative3DTexture = doNative3DTexture;
        context.forceRerun = forceRerun;
        context.artifactStatus = artifactStatus;
        context.heatmapBandwidth = heatmapBandwidth;
        context.heatmapLut = heatmapLut;
        context.clusterK = clusterK;
        context.textureClassK = textureClassK;

        if (doVolumetric) {
            refreshVolumetricColocFlags(channels, channelNames, context.existingObjectData);
            context.existingObjectData = SpatialObjectDataAvailability.detect(channels, channelNames);
        }

        return context;
    }

    private void computeInterMarkerDistancePhase(SpatialExecutionContext context) {
        if (context.doDistances || context.doVolumetric) {
            IJ.log("--- Computing/reusing inter-marker nearest neighbor distances ---");
            if (context.doDistances) {
                logArtifactRunPlan("Nearest neighbor distances", SubAnalysis.INTER_MARKER_DISTANCES);
            }
            int totalPairs = (context.channelNames.size() * (context.channelNames.size() - 1)) / 2;
            int pairCount = 1;
            for (int a = 0; a < context.channelNames.size(); a++) {
                for (int b = a + 1; b < context.channelNames.size(); b++) {
                    String nameA = context.channelNames.get(a);
                    String nameB = context.channelNames.get(b);
                    int currentPair = pairCount++;
                    boolean distancesAlreadyPresent = !context.forceRerun
                            && (context.existingObjectData.hasDistancePair(nameA, nameB)
                            || (isArtifactDoneForChannel(SubAnalysis.INTER_MARKER_DISTANCES, nameA)
                            && isArtifactDoneForChannel(SubAnalysis.INTER_MARKER_DISTANCES, nameB)));
                    boolean needsDistances = context.doDistances && !distancesAlreadyPresent;
                    boolean needsVolumetric = context.doVolumetric
                            && (context.forceRerun
                            || !context.existingObjectData.hasVolumetricPair(nameA, nameB,
                            (int) getThreshold(nameA), (int) getThreshold(nameB)));
                    if (!needsDistances && !needsVolumetric) {
                        String reuseReason = context.doDistances && context.doVolumetric
                                ? "reusing existing distance/volumetric columns"
                                : (context.doDistances ? "reusing existing distance columns"
                                : "reusing existing volumetric columns");
                        IJ.log("  Pair [" + currentPair + "/" + totalPairs + "]: " + nameA + " <-> " + nameB
                                + " (" + reuseReason + ")");
                        continue;
                    }
                    String reuseNote = "";
                    if (context.doDistances && !needsDistances) {
                        reuseNote += " (distance columns already present)";
                    }
                    if (context.doVolumetric && !needsVolumetric) {
                        reuseNote += " (volumetric columns already present)";
                    }
                    IJ.log("  Pair [" + currentPair + "/" + totalPairs + "]: " + nameA + " <-> " + nameB
                            + reuseNote);
                    computeInterMarkerDistances(context.channels.get(nameA), context.channels.get(nameB),
                            context.calibration,
                            needsVolumetric);
                }
            }
        }
    }

    void runEarlyPhase(String directory, LabelImageProvider provider) {
        SpatialExecutionContext context = requireActiveExecution();
        logEarlyPhaseProviderPlan(directory, context, provider);

        if (context.doCpc) {
            runCpcIfNeeded(directory, context.channels, context.channelNames,
                    context.existingObjectData, provider);
        }

        if (context.doHeatmaps) {
            IJ.log("--- Density Heatmaps ---");
            runDensityHeatmaps(directory, context.channels, context.calibration,
                    context.heatmapBandwidth, context.heatmapLut, provider);
        }

        if (context.doMorphology) {
            IJ.log("--- Morphology Extraction ---");
            runMorphologyExtraction(directory, context.channels, context.calibration, provider);
        }

        if (context.do3DShapeFeatures) {
            IJ.log("--- 3D Shape Features ---");
            run3DMorphometry(directory, context.channels, context.calibration,
                    context.doCompositeIndices, provider);
        }

        if (context.doObjectGLCM || context.doObjectFractal
                || context.doObjectTextureClass || context.doNative3DTexture) {
            IJ.log("--- Object Texture and Complexity ---");
            runObjectTexture(directory, context, provider);
        }
    }

    private void logEarlyPhaseProviderPlan(String directory,
                                           SpatialExecutionContext context,
                                           LabelImageProvider provider) {
        if (!context.doCpc && !context.doHeatmaps
                && !context.doMorphology && !context.do3DShapeFeatures
                && !context.doObjectGLCM && !context.doObjectFractal
                && !context.doObjectTextureClass && !context.doNative3DTexture) {
            IJ.log("--- Spatial early phase: no object-dependent work selected ---");
            return;
        }

        if (!(provider instanceof InMemoryLabelImageProvider)) {
            IJ.log("--- Spatial early phase label provider: disk-backed; labels will load on demand ---");
            return;
        }

        int total = 0;
        int inMemory = 0;
        InMemoryLabelImageProvider inMemoryProvider = (InMemoryLabelImageProvider) provider;
        for (Map.Entry<String, ChannelData> entry : context.channels.entrySet()) {
            String channelName = entry.getKey();
            Map<SectionKey, List<Integer>> sections =
                    groupByCpcSection(directory, channelName, entry.getValue());
            for (SectionKey section : sections.keySet()) {
                total++;
                if (inMemoryProvider != null && inMemoryProvider.hasCached(channelName, section)) {
                    inMemory++;
                }
            }
        }

        IJ.log("--- Spatial early phase label provider: " + inMemory + "/" + total
                + " section/channel labels available in memory; "
                + (total - inMemory) + " will fall back to disk if needed ---");
    }

    void runLatePhase(String directory) {
        SpatialExecutionContext context = requireActiveExecution();

        reorderManagedSpatialColumns(context.channels, context.channelNames);
        writeUpdatedCsvs(context.objectsDir, context.channels, "--- Writing updated CSVs ---");

        if (context.doSpatialStats) writeSpatialStatisticsOutputs(directory, context.channels);
        if (context.doCpc) writeCpcSummaries(directory, context.channels, context.channelNames);

        if (context.doVoronoi) {
            IJ.log("--- Voronoi Tessellation ---");
            runVoronoiAnalysis(directory, context.channels, context.calibration);
        }

        if (context.doPhenotyping) {
            IJ.log("--- Cell Phenotyping (k-means) ---");
            runCellClustering(directory, context.channels, context.clusterK);
        }

        if (context.doPopMorphometrics) {
            if (!context.do3DShapeFeatures && !hasColumn(context.channels, "Morph_RI")) {
                IJ.log("WARNING: Population morphometric scoring requires 3D shape features. Skipping.");
            } else {
                IJ.log("--- Population Morphometric Scoring ---");
                runPopulationMorphometrics(directory, context.channels);
            }
        }

        if (context.doSpatialMorphometrics) {
            if (!context.do3DShapeFeatures && !hasColumn(context.channels, "Morph_Feret3D_um")) {
                IJ.log("WARNING: Spatial-morphometric analysis requires 3D shape features. Skipping.");
            } else {
                IJ.log("--- Spatial-Morphometric Analysis ---");
                runSpatialMorphometrics(directory, context.channels, context.calibration);
            }
        }

        // Write morphometry analysis details
        if (context.do3DShapeFeatures || context.doPopMorphometrics || context.doSpatialMorphometrics
                || context.doObjectGLCM || context.doObjectFractal
                || context.doObjectTextureClass || context.doNative3DTexture) {
            File morphDir = spatialMorphometryOutputDir(directory);
            MorphometryDetailsWriter.write(morphDir, context.do3DShapeFeatures, context.doCompositeIndices,
                    context.doPopMorphometrics, context.doSpatialMorphometrics);
        }

        // Write updated CSVs again if new columns were added
        if (context.doVoronoi || context.doPhenotyping || context.doMorphology
                || context.do3DShapeFeatures || context.doPopMorphometrics
                || context.doSpatialMorphometrics
                || context.doObjectGLCM || context.doObjectFractal
                || context.doObjectTextureClass || context.doNative3DTexture) {
            reorderManagedSpatialColumns(context.channels, context.channelNames);
            writeUpdatedCsvs(context.objectsDir, context.channels, "--- Writing final updated CSVs ---");
        }

        if (context.doLineDistance && context.hasLineRoiSets) {
            runLineDistanceIfNeeded(directory, context);
        }
    }

    private void runLineDistanceIfNeeded(String directory, SpatialExecutionContext context) {
        IJ.log("--- Computing line distances from Spatial Analysis ---");
        logArtifactRunPlan("Line distance", SubAnalysis.LINE_DISTANCE);
        SpatialArtifactStatus status = currentArtifactStatus();
        if (!context.forceRerun
                && status != null
                && status.isFullyDone(SubAnalysis.LINE_DISTANCE)) {
            IJ.log("    Skipping line distance - already present.");
            return;
        }
        LineDistanceAnalysis lineAnalysis = new LineDistanceAnalysis();
        lineAnalysis.setVerboseLogging(verboseLogging);
        lineAnalysis.computeDistances(directory, context.linesDir, context.availableLineSets);
    }

    private SpatialExecutionContext requireActiveExecution() {
        if (activeExecution == null) {
            throw new IllegalStateException("SpatialAnalysis execution context has not been prepared");
        }
        return activeExecution;
    }

    private SpatialArtifactStatus currentArtifactStatus() {
        if (activeExecution != null && activeExecution.artifactStatus != null) {
            return activeExecution.artifactStatus;
        }
        return spatialArtifactStatus;
    }

    private boolean isForceRerunActive() {
        return activeExecution != null ? activeExecution.forceRerun : forceRerun;
    }

    private void logArtifactRunPlan(String label, SubAnalysis subAnalysis) {
        SpatialArtifactStatus status = currentArtifactStatus();
        if (isForceRerunActive()) {
            IJ.log("  " + label + ": force re-run enabled; existing outputs will be ignored.");
            return;
        }
        if (status == null || status.totalPairs() == 0) {
            IJ.log("  " + label + ": no artifact status available; all selected work will run.");
            return;
        }
        int done = status.countDone(subAnalysis);
        int total = status.totalPairs();
        IJ.log("  " + label + ": " + done + "/" + total
                + " section/channel pairs already present; "
                + (total - done) + " pairs to run.");
    }

    private boolean shouldSkipPair(SubAnalysis subAnalysis,
                                   SectionKey section,
                                   String channelName,
                                   String label) {
        SpatialArtifactStatus status = currentArtifactStatus();
        if (isForceRerunActive() || status == null) {
            return false;
        }
        if (status.isDone(subAnalysis, section, channelName)) {
            IJ.log("    Skipping " + label + " for " + channelName + " / " + section
                    + " - already present.");
            return true;
        }
        return false;
    }

    private boolean shouldSkipChannel(SubAnalysis subAnalysis,
                                      String channelName,
                                      String label) {
        SpatialArtifactStatus status = currentArtifactStatus();
        if (isForceRerunActive() || status == null) {
            return false;
        }
        if (status.isFullyDoneForChannel(subAnalysis, channelName)) {
            IJ.log("    Skipping " + label + " for " + channelName + " - already present.");
            return true;
        }
        return false;
    }

    private boolean isArtifactDoneForChannel(SubAnalysis subAnalysis, String channelName) {
        SpatialArtifactStatus status = currentArtifactStatus();
        return !isForceRerunActive()
                && status != null
                && status.isFullyDoneForChannel(subAnalysis, channelName);
    }

    private boolean hasPairArtifactStatus() {
        SpatialArtifactStatus status = currentArtifactStatus();
        return status != null && status.totalPairs() > 0;
    }

    private boolean shouldSkipCpcTask(CpcSectionTask task, String nameA, String nameB) {
        SpatialArtifactStatus status = currentArtifactStatus();
        if (isForceRerunActive() || status == null || task == null) {
            return false;
        }
        if (status.isDone(SubAnalysis.CPC, task.sectionKey, nameA)
                && status.isDone(SubAnalysis.CPC, task.sectionKey, nameB)) {
            IJ.log("    Skipping CPC for " + nameA + " <-> " + nameB
                    + " / " + task.sectionKey + " - already present.");
            return true;
        }
        return false;
    }

    private void writeUpdatedCsvs(File objectsDir, Map<String, ChannelData> channels, String title) {
        IJ.log(title);
        int writeCount = 1;
        int totalToWrite = channels.size();
        for (Map.Entry<String, ChannelData> entry : channels.entrySet()) {
            String channelName = entry.getKey();
            ChannelData cd = entry.getValue();
            File outFile = new File(objectsDir, ChannelFilenameCodec.toSafe(channelName) + ".csv");
            CsvTableIO.writeChannelCsv(outFile, cd);
            IJ.log("  [" + (writeCount++) + "/" + totalToWrite + "] Updated: " + outFile.getName());
        }
    }

    /**
     * Backwards-compatible entry point used by ThreeDObjectAnalysis.
     */
    public void run(String directory) {
        execute(directory);
    }

    SpatialAnalysisWizard.DerivedConfig showOptionsDialogForChainedRun(String directory,
                                                                       List<String> channelNames) {
        return showOptionsDialogForChainedRun(directory, channelNames, false, false);
    }

    SpatialAnalysisWizard.DerivedConfig showOptionsDialogForChainedRun(String directory,
                                                                       List<String> channelNames,
                                                                       boolean lockVolumetricColoc,
                                                                       boolean lockCpcColoc) {
        List<String> safeChannelNames = channelNames == null
                ? new ArrayList<String>()
                : new ArrayList<String>(channelNames);
        File linesDir = LineDistanceAnalysis.lineSetWriteDir(directory);
        SpatialObjectDataAvailability existingObjectData =
                SpatialObjectDataAvailability.detect(null, safeChannelNames);
        LinkedHashMap<String, ChannelData> existingChannels =
                loadExistingObjectDataForDialog(directory, safeChannelNames);
        if (!existingChannels.isEmpty()) {
            existingObjectData = SpatialObjectDataAvailability.detect(existingChannels, safeChannelNames);
        }
        List<SectionKey> artifactSections = collectArtifactSections(directory, existingChannels, safeChannelNames);
        SpatialArtifactStatus artifactStatus =
                new SpatialArtifactScanner().scan(directory, safeChannelNames, artifactSections);
        spatialArtifactStatus = artifactStatus;
        return showSpatialOptionsDialog(directory, safeChannelNames, existingObjectData,
                LineDistanceAnalysis.lineSetNames(directory), artifactStatus, configuredOptions,
                lockVolumetricColoc, lockCpcColoc);
    }

    private SpatialAnalysisWizard.DerivedConfig showSpatialOptionsDialog(
            String directory,
            final List<String> channelNames,
            SpatialObjectDataAvailability existingObjectData,
            List<String> availableLineSets,
            SpatialArtifactStatus artifactStatus,
            SpatialAnalysisWizard.DerivedConfig initialOptions) {
        return showSpatialOptionsDialog(directory, channelNames, existingObjectData,
                availableLineSets, artifactStatus, initialOptions, false, false);
    }

    private SpatialAnalysisWizard.DerivedConfig showSpatialOptionsDialog(
            String directory,
            final List<String> channelNames,
            SpatialObjectDataAvailability existingObjectData,
            List<String> availableLineSets,
            SpatialArtifactStatus artifactStatus,
            SpatialAnalysisWizard.DerivedConfig initialOptions,
            boolean lockVolumetricColoc,
            boolean lockCpcColoc) {
        if (channelNames == null || channelNames.isEmpty()) {
            return null;
        }
        if (initialOptions != null) {
            applyConfiguredMarkerThresholds(initialOptions, channelNames);
        }

        boolean hasLineRoiSets = availableLineSets != null && !availableLineSets.isEmpty();
        boolean doDistances = initialOptions == null ? true : initialOptions.doDistances;
        boolean doLineDistance = initialOptions != null && initialOptions.doLineDistance;
        boolean doSpatialStats = initialOptions != null && initialOptions.doSpatialStats;
        boolean doCpc = initialOptions == null ? true : initialOptions.doCpc;
        boolean doVolumetric = initialOptions != null && initialOptions.doVolColoc;
        if (lockVolumetricColoc) {
            doVolumetric = true;
        }
        if (lockCpcColoc) {
            doCpc = true;
        }
        boolean doVoronoi = initialOptions != null && initialOptions.doVoronoi;
        boolean doHeatmaps = initialOptions != null && initialOptions.doHeatmaps;
        boolean doPhenotyping = initialOptions != null && initialOptions.doPhenotyping;
        boolean doMorphology = initialOptions != null && initialOptions.do2DMorphology;
        boolean do3DShapeFeatures = initialOptions != null && initialOptions.do3DMorphology;
        boolean doCompositeIndices = initialOptions != null && initialOptions.doCompositeIndices;
        boolean doPopMorphometrics = initialOptions != null && initialOptions.doPopMorphometrics;
        boolean doSpatialMorphometrics = initialOptions != null && initialOptions.doSpatialMorphometrics;
        boolean doObjectGLCM = initialOptions != null && initialOptions.doObjectGLCM;
        boolean doObjectFractal = initialOptions != null && initialOptions.doObjectFractal;
        boolean doObjectTextureClass = initialOptions != null && initialOptions.doObjectTextureClass;
        boolean doNative3DTexture = initialOptions != null && initialOptions.doNative3DTexture;
        boolean forceRerun = initialOptions != null && initialOptions.forceRerun;
        double heatmapBandwidth = initialOptions == null ? 0.0 : initialOptions.kdeBandwidth;
        String heatmapLut = initialOptions == null ? DEFAULT_HEATMAP_LUT : initialOptions.heatmapLut;
        int clusterK = initialOptions == null ? 0 : initialOptions.clusterK;
        int textureClassK = initialOptions == null
                ? ObjectTextureFeatures.DEFAULT_K
                : clampObjectTextureClassK(initialOptions.textureClassK);

        boolean dialogDone = false;
        while (!dialogDone) {
            PipelineDialog opts = new PipelineDialog("Spatial Analysis Options", PipelineDialog.Phase.ANALYSE);
            opts.addAnalysisHelpHeader("Spatial Analysis", FLASH_Pipeline.IDX_SPATIAL);
            final SpatialDialogBindings spatialBindings = new SpatialDialogBindings();
            spatialBindings.lockVolColocFromObjectAnalysis = lockVolumetricColoc;
            spatialBindings.lockCpcFromObjectAnalysis = lockCpcColoc;
            spatialBindings.forceRerunToggle = opts.addToggle(
                    "Force re-run all sub-analyses (ignore existing outputs)", false);
            opts.addHelpText("Recomputes selected spatial outputs even when matching files or columns already exist.");
            addSpatialSetupControls(opts, directory, spatialBindings,
                    new SpatialConfigApplier() {
                        @Override
                        public void apply(String selectedPresetName,
                                          SpatialAnalysisWizard.DerivedConfig derived) {
                            applySpatialConfigToDialog(derived, channelNames,
                                    spatialBindings, selectedPresetName);
                        }
                    });
            opts.addSetupHelpSubHeader("Spatial Distances", SpatialHelpCatalog.DISTANCES);
            spatialBindings.doDistancesToggle = opts.addToggle(
                    decorateArtifactLabel("Nearest neighbor distances", artifactStatus,
                            SubAnalysis.INTER_MARKER_DISTANCES),
                    defaultForArtifactStatus(artifactStatus, SubAnalysis.INTER_MARKER_DISTANCES, doDistances));
            opts.addHelpText("Computes 3D nearest neighbor distance between every channel pair.");
            opts.addHelpText(existingObjectData.distanceHelperText());
            spatialBindings.lineDistanceToggle = opts.addToggle(
                    decorateArtifactLabel("Line distance to drawn line ROI sets", artifactStatus,
                            SubAnalysis.LINE_DISTANCE),
                    defaultForArtifactStatus(artifactStatus, SubAnalysis.LINE_DISTANCE,
                            doLineDistance && hasLineRoiSets));
            if (!hasLineRoiSets) {
                spatialBindings.lineDistanceToggle.setEnabled(false);
                opts.addHelpText("No line ROI set found in FLASH/Results/Tables/Line Distance/Line Sets. Draw a line set first, "
                        + "then rerun Spatial Analysis.");
            } else {
                opts.addHelpText("Uses all line ROI sets found in FLASH/Results/Tables/Line Distance/Line Sets: "
                        + lineSetSummary(availableLineSets) + ".");
            }
            opts.beginAdvancedSection("spatial");
            spatialBindings.doSpatialStatsToggle = opts.addToggle(
                    decorateArtifactLabel("Spatial statistics (Ripley's K/L/G)", artifactStatus,
                            SubAnalysis.RIPLEY),
                    defaultForArtifactStatus(artifactStatus, SubAnalysis.RIPLEY, doSpatialStats));
            opts.addHelpText("Point pattern analysis per channel (requires calibrated centroids).");
            opts.endAdvancedSection();

            opts.addSetupHelpHeader("Colocalization", SpatialHelpCatalog.COLOCALIZATION);
            final ToggleSwitch volToggle = opts.addToggle("Volumetric overlap", doVolumetric);
            spatialBindings.doVolColocToggle = volToggle;
            opts.addHelpText("Counts nearest-neighbor objects exceeding the colocalization "
                    + "threshold. Uses saved Colocalisation with percentages from 3D Object Analysis.");
            if (lockVolumetricColoc) {
                opts.addHelpText("Already selected in 3D Object Analysis. The previous setting is locked here.");
            }
            final List<JTextField> thresholdFields = new ArrayList<JTextField>();
            for (String chName : channelNames) {
                JTextField tf = opts.addNumericField(chName + " Coloc Threshold (%)",
                        getThreshold(chName), 0);
                tf.setEnabled(doVolumetric && !lockVolumetricColoc);
                thresholdFields.add(tf);
            }
            spatialBindings.thresholdFields = thresholdFields;
            volToggle.addChangeListener(new Runnable() {
                @Override
                public void run() {
                    updateVolumetricThresholdEnablement(spatialBindings);
                }
            });
            spatialBindings.doCpcToggle = opts.addToggle(
                    decorateArtifactLabel("CPC centroid coincidence", artifactStatus, SubAnalysis.CPC),
                    defaultForArtifactStatus(artifactStatus, SubAnalysis.CPC, doCpc));
            opts.addHelpText("Centroid-in-object colocalization from saved label images. "
                    + "Skips computation if CPC columns already exist from 3D Object Analysis.");
            if (lockCpcColoc) {
                opts.addHelpText("Already selected in 3D Object Analysis. The previous setting is locked here.");
            }
            opts.addHelpText(existingObjectData.colocalizationHelperText());
            applyLockedColocalizationControls(spatialBindings);

            opts.beginAdvancedSection("spatial");
            opts.addSetupHelpHeader("Voronoi Tessellation", SpatialHelpCatalog.VORONOI);
            spatialBindings.doVoronoiToggle = opts.addToggle(
                    decorateArtifactLabel("Voronoi territory analysis", artifactStatus, SubAnalysis.VORONOI),
                    defaultForArtifactStatus(artifactStatus, SubAnalysis.VORONOI, doVoronoi));
            opts.addHelpText("Computes Voronoi territories per object: territory area, "
                    + "neighbor count, and inter-channel interaction matrix with permutation test.");
            opts.endAdvancedSection();

            addMorphometricControls(opts, spatialBindings, doMorphology, do3DShapeFeatures,
                    doCompositeIndices, doPopMorphometrics, doSpatialMorphometrics,
                    existingObjectData, artifactStatus);

            addObjectTextureControls(opts, spatialBindings, doObjectGLCM,
                    doObjectFractal, doObjectTextureClass, doNative3DTexture, textureClassK);

            addAdvancedPhenotypingAndHeatmapControls(opts, spatialBindings,
                    doPhenotyping, clusterK, doHeatmaps, heatmapBandwidth, heatmapLut,
                    artifactStatus);

            opts.addMessage("Toggles marked \"already present\" will be skipped. "
                    + "Tick \"Force re-run\" to recompute everything.");

            updateVolumetricThresholdEnablement(spatialBindings);
            updateMorphometricDependencyControls(spatialBindings);

            if (!opts.showDialog()) {
                return null;
            }
            forceRerun = opts.getNextBoolean();
            doDistances = opts.getNextBoolean();
            doLineDistance = opts.getNextBoolean() && hasLineRoiSets;
            doSpatialStats = opts.getNextBoolean();
            doVolumetric = opts.getNextBoolean();
            doCpc = opts.getNextBoolean();
            if (lockVolumetricColoc) {
                doVolumetric = true;
            }
            if (lockCpcColoc) {
                doCpc = true;
            }
            doVoronoi = opts.getNextBoolean();
            doMorphology = opts.getNextBoolean();
            do3DShapeFeatures = opts.getNextBoolean();
            doCompositeIndices = opts.getNextBoolean();
            doPopMorphometrics = opts.getNextBoolean();
            doSpatialMorphometrics = opts.getNextBoolean();
            doObjectGLCM = opts.getNextBoolean();
            doObjectFractal = opts.getNextBoolean();
            doObjectTextureClass = opts.getNextBoolean();
            doNative3DTexture = opts.getNextBoolean();
            doPhenotyping = opts.getNextBoolean();
            doHeatmaps = opts.getNextBoolean();
            for (String chName : channelNames) {
                markerThresholds.put(chName, opts.getNextNumber());
            }
            textureClassK = clampObjectTextureClassK((int) Math.round(opts.getNextNumber()));
            clusterK = (int) opts.getNextNumber();
            heatmapBandwidth = opts.getNextNumber();
            heatmapLut = opts.getNextChoice();

            boolean effective3DShapeFeatures = do3DShapeFeatures
                    || doCompositeIndices || doPopMorphometrics || doSpatialMorphometrics;
            RuntimeDependencyAction dependencyAction =
                    checkRuntimeDependencies(doVoronoi, effective3DShapeFeatures);
            if (dependencyAction == RuntimeDependencyAction.RECONFIGURE) {
                continue;
            }
            if (dependencyAction == RuntimeDependencyAction.ABORT) {
                return null;
            }
            dialogDone = true;
        }

        SpatialAnalysisWizard.DerivedConfig config = new SpatialAnalysisWizard.DerivedConfig();
        config.doDistances = doDistances;
        config.doLineDistance = doLineDistance && hasLineRoiSets;
        config.doSpatialStats = doSpatialStats;
        config.doVolColoc = doVolumetric;
        config.doCpc = doCpc;
        config.doVoronoi = doVoronoi;
        config.doHeatmaps = doHeatmaps;
        config.doPhenotyping = doPhenotyping;
        config.do2DMorphology = doMorphology;
        config.do3DMorphology = do3DShapeFeatures;
        config.doCompositeIndices = doCompositeIndices;
        config.doPopMorphometrics = doPopMorphometrics;
        config.doSpatialMorphometrics = doSpatialMorphometrics;
        config.doObjectGLCM = doObjectGLCM;
        config.doObjectFractal = doObjectFractal;
        config.doObjectTextureClass = doObjectTextureClass;
        config.doNative3DTexture = doNative3DTexture;
        config.forceRerun = forceRerun;
        config.kdeBandwidth = heatmapBandwidth;
        config.heatmapLut = heatmapLut;
        config.clusterK = clusterK;
        config.textureClassK = textureClassK;
        config.colocThresholdPercent = firstConfiguredThreshold(channelNames);
        config.markerThresholds.putAll(markerThresholds);
        return config;
    }

    private void applyConfiguredMarkerThresholds(SpatialAnalysisWizard.DerivedConfig config,
                                                 List<String> channelNames) {
        if (config == null || channelNames == null) {
            return;
        }
        for (String chName : channelNames) {
            Double threshold = config.markerThresholds.get(chName);
            markerThresholds.put(chName, threshold == null
                    ? Double.valueOf(config.colocThresholdPercent)
                    : threshold);
        }
    }

    private double firstConfiguredThreshold(List<String> channelNames) {
        if (channelNames != null) {
            for (String chName : channelNames) {
                Double value = markerThresholds.get(chName);
                if (value != null) {
                    return value.doubleValue();
                }
            }
        }
        return DEFAULT_COLOC_THRESHOLD;
    }

    private static String decorateArtifactLabel(String base,
                                                SpatialArtifactStatus status,
                                                SubAnalysis subAnalysis) {
        if (status == null || subAnalysis == null) {
            return base;
        }
        if (status.isFullyDone(subAnalysis)) {
            return base + " \u2014 already present";
        }
        if (status.isPartiallyDone(subAnalysis)) {
            return base + " \u2014 partially present";
        }
        return base;
    }

    private static boolean defaultForArtifactStatus(SpatialArtifactStatus status,
                                                    SubAnalysis subAnalysis,
                                                    boolean originalDefault) {
        if (status == null || subAnalysis == null) {
            return originalDefault;
        }
        return status.isFullyDone(subAnalysis) || status.isPartiallyDone(subAnalysis)
                ? false
                : originalDefault;
    }

    private RuntimeDependencyAction checkRuntimeDependencies(boolean doVoronoi,
                                                             boolean do3DShapeFeatures) {
        if (doVoronoi) {
            FeatureDependencyGate.GateDecision decision = FeatureDependencyGate.check(
                    DependencyId.JTS_CORE,
                    "Spatial Analysis",
                    "Voronoi territory analysis");
            RuntimeDependencyAction action = actionFromGateDecision(decision);
            if (action != RuntimeDependencyAction.PROCEED) {
                return action;
            }
        }
        if (do3DShapeFeatures) {
            FeatureDependencyGate.GateDecision decision = FeatureDependencyGate.check(
                    DependencyId.MCIB3D_CORE,
                    "Spatial Analysis",
                    "3D shape features and spatial morphometry");
            RuntimeDependencyAction action = actionFromGateDecision(decision);
            if (action != RuntimeDependencyAction.PROCEED) {
                return action;
            }
        }
        return RuntimeDependencyAction.PROCEED;
    }

    private static RuntimeDependencyAction actionFromGateDecision(FeatureDependencyGate.GateDecision decision) {
        if (decision != null && decision.isAllowed()) {
            return RuntimeDependencyAction.PROCEED;
        }
        if (decision != null && decision.isChangeSetupRequested()) {
            return RuntimeDependencyAction.RECONFIGURE;
        }
        return RuntimeDependencyAction.ABORT;
    }

    private static File firstExistingDirectory(List<File> dirs) {
        if (dirs == null) return null;
        for (File dir : dirs) {
            if (dir != null && dir.isDirectory()) {
                return dir;
            }
        }
        return null;
    }

    private LinkedHashMap<String, ChannelData> loadExistingObjectDataForDialog(String directory,
                                                                               List<String> channelNames) {
        LinkedHashMap<String, ChannelData> channels = new LinkedHashMap<String, ChannelData>();
        if (channelNames == null || channelNames.isEmpty()) {
            return channels;
        }
        FlashProjectLayout layout = FlashProjectLayout.forDirectory(directory);
        File objectsDir = layout.tablesObjectsWriteDir();
        if (!objectsDir.isDirectory()) {
            return channels;
        }
        for (String channelName : channelNames) {
            File csvFile = new File(objectsDir, ChannelFilenameCodec.toSafe(channelName) + ".csv");
            if (!csvFile.isFile()) {
                csvFile = new File(objectsDir, channelName + ".csv");
            }
            if (!csvFile.isFile()) {
                continue;
            }
            ChannelData cd = CsvTableIO.loadChannelCsv(csvFile, channelName);
            if (cd != null) {
                channels.put(channelName, cd);
            }
        }
        return channels;
    }

    private List<SectionKey> collectArtifactSections(String directory,
                                                     Map<String, ChannelData> channels,
                                                     List<String> channelNames) {
        LinkedHashSet<SectionKey> sections = new LinkedHashSet<SectionKey>();
        if (channels == null || channels.isEmpty()) {
            return new ArrayList<SectionKey>(sections);
        }

        List<String> names = channelNames == null
                ? new ArrayList<String>(channels.keySet())
                : channelNames;
        for (String channelName : names) {
            ChannelData cd = channels.get(channelName);
            if (cd == null) {
                continue;
            }
            sections.addAll(groupByCpcSection(directory, channelName, cd).keySet());
        }

        if (sections.isEmpty()) {
            for (ChannelData cd : channels.values()) {
                for (int row = 0; row < cd.rows.size(); row++) {
                    if (isPlaceholderRow(cd, row)) {
                        continue;
                    }
                    sections.add(sectionKeyFromRow(cd, row));
                }
            }
        }
        return new ArrayList<SectionKey>(sections);
    }

    private SectionKey sectionKeyFromRow(ChannelData cd, int row) {
        return SectionKey.of(safeValue(cd, row, "Animal Name"),
                sectionLabelSuffix(safeValue(cd, row, "Hemisphere"),
                        safeValue(cd, row, "Region")));
    }

    private static SectionKey sectionKeyFromSpatialGroup(SpatialGroupKey key) {
        if (key == null) {
            return null;
        }
        return SectionKey.of(key.animal, sectionLabelSuffix(key.hemisphere, key.region));
    }

    private static String sectionLabelSuffix(String hemisphere, String region) {
        String safeHemisphere = hemisphere == null ? "" : hemisphere.trim();
        String safeRegion = region == null ? "" : region.trim();
        if (safeHemisphere.isEmpty()) {
            return safeRegion;
        }
        if (safeRegion.isEmpty()) {
            return safeHemisphere;
        }
        return safeHemisphere + "_" + safeRegion;
    }

    private static File objectImageOutputReadRoot(String directory) {
        return FlashProjectLayout.forDirectory(directory).analysisImagesObjectsMasksDir();
    }

    private static File spatialDataOutputDir(String directory) {
        return FlashProjectLayout.forDirectory(directory).tablesSpatialWriteDir();
    }

    private static File spatialMorphometryOutputDir(String directory) {
        return FlashProjectLayout.forDirectory(directory).tablesMorphometryWriteDir();
    }

    private static File spatialImageOutputDir(String directory) {
        return FlashProjectLayout.forDirectory(directory).analysisImagesSpatialHeatmapsDir();
    }

    static List<String> lineSetNames(File linesDir) {
        List<String> names = new ArrayList<String>();
        if (linesDir == null || !linesDir.isDirectory()) {
            return names;
        }
        File[] zipFiles = linesDir.listFiles(new java.io.FilenameFilter() {
            @Override
            public boolean accept(File dir, String name) {
                return name != null && name.toLowerCase(Locale.ROOT).endsWith(".zip");
            }
        });
        if (zipFiles == null) {
            return names;
        }
        Arrays.sort(zipFiles);
        for (File file : zipFiles) {
            String base = file.getName();
            if (base.toLowerCase(Locale.ROOT).endsWith(".zip")) {
                base = base.substring(0, base.length() - 4);
            }
            names.add(base);
        }
        return names;
    }

    private static String lineSetSummary(List<String> lineSets) {
        if (lineSets == null || lineSets.isEmpty()) {
            return "none";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lineSets.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(lineSets.get(i));
        }
        return sb.toString();
    }

    /**
     * Computes nearest neighbor distances between two channels.
     *
     * <p>For each section (SCN), finds the closest object in channel B for
     * each object in channel A, and vice versa. Appends distance columns,
     * closest-to counts, colocalisation counts, and contains flags to both
     * channel data sets.
     */
    private void computeInterMarkerDistances(ChannelData chA, ChannelData chB,
                                             CalibrationIO.PixelCalibration cal,
                                             boolean doVolumetric) {
        String nameA = chA.name;
        String nameB = chB.name;

        String colDistAtoB = nameA + "_DistToClosest_" + nameB;
        String colDistBtoA = nameB + "_DistToClosest_" + nameA;
        String colBClosestToA = nameB + "_ClosestTo_" + nameA;
        String colAClosestToB = nameA + "_ClosestTo_" + nameB;

        chA.addColumn(colDistAtoB);
        chA.addColumn(colAClosestToB);

        chB.addColumn(colDistBtoA);
        chB.addColumn(colBClosestToA);

        // Volumetric contains columns — only when volumetric colocalization is enabled
        String colBVolContainsA = null;
        String colAVolContainsB = null;
        String colocColAWithB = null;
        String colocColBWithA = null;
        if (doVolumetric) {
            int thrA = (int) getThreshold(nameA);
            int thrB = (int) getThreshold(nameB);
            colBVolContainsA = nameB + "_VolContains" + thrA + "_" + nameA;
            colAVolContainsB = nameA + "_VolContains" + thrB + "_" + nameB;
            chA.addColumn(colAVolContainsB);
            chB.addColumn(colBVolContainsA);
            colocColAWithB = volColocCol(nameA, nameB);
            colocColBWithA = volColocCol(nameB, nameA);
        }

        if (!hasPixelCentroids(chA)) {
            IJ.log("    Channel " + nameA + " missing required coordinate columns (XM/YM/ZM).");
            return;
        }
        if (!hasPixelCentroids(chB)) {
            IJ.log("    Channel " + nameB + " missing required coordinate columns (XM/YM/ZM).");
            return;
        }
        if (!hasAnalysisUnitColumn(chA)) {
            IJ.log("    Channel " + nameA + " missing Region/ROI grouping columns.");
            return;
        }
        if (!hasAnalysisUnitColumn(chB)) {
            IJ.log("    Channel " + nameB + " missing Region/ROI grouping columns.");
            return;
        }

        Map<String, List<Integer>> scnGroupAAll = groupBySCN(chA, false);
        Map<String, List<Integer>> scnGroupBAll = groupBySCN(chB, false);
        Map<String, List<Integer>> scnGroupAValid = groupBySCN(chA, true);
        Map<String, List<Integer>> scnGroupBValid = groupBySCN(chB, true);

        Set<String> allSCNs = new LinkedHashSet<String>();
        allSCNs.addAll(scnGroupAAll.keySet());
        allSCNs.addAll(scnGroupBAll.keySet());

        for (String scn : allSCNs) {
            List<Integer> rowsAAll = scnGroupAAll.get(scn);
            List<Integer> rowsBAll = scnGroupBAll.get(scn);
            List<Integer> idxA = scnGroupAValid.get(scn);
            List<Integer> idxB = scnGroupBValid.get(scn);

            initializeSpatialDefaults(chA, rowsAAll, colDistAtoB, colAClosestToB, colAVolContainsB);
            initializeSpatialDefaults(chB, rowsBAll, colDistBtoA, colBClosestToA, colBVolContainsA);

            if (idxA == null || idxA.isEmpty() || idxB == null || idxB.isEmpty()) {
                continue;
            }

            CentroidSet centroidsA = buildFiniteCentroids(chA, idxA, cal);
            CentroidSet centroidsB = buildFiniteCentroids(chB, idxB, cal);
            idxA = centroidsA.rows;
            idxB = centroidsB.rows;

            if (idxA.isEmpty() || idxB.isEmpty()) {
                continue;
            }

            int[] closestBForA = new int[idxA.size()];
            double[] distAtoB = new double[idxA.size()];
            for (int i = 0; i < idxA.size(); i++) {
                double minDist = Double.MAX_VALUE;
                int minIdx = 0;
                for (int j = 0; j < idxB.size(); j++) {
                    double dx = centroidsA.x[i] - centroidsB.x[j];
                    double dy = centroidsA.y[i] - centroidsB.y[j];
                    double dz = centroidsA.z[i] - centroidsB.z[j];
                    double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
                    if (dist < minDist) {
                        minDist = dist;
                        minIdx = j;
                    }
                }
                closestBForA[i] = minIdx;
                distAtoB[i] = minDist;
            }

            int[] closestAForB = new int[idxB.size()];
            double[] distBtoA = new double[idxB.size()];
            for (int j = 0; j < idxB.size(); j++) {
                double minDist = Double.MAX_VALUE;
                int minIdx = 0;
                for (int i = 0; i < idxA.size(); i++) {
                    double dx = centroidsB.x[j] - centroidsA.x[i];
                    double dy = centroidsB.y[j] - centroidsA.y[i];
                    double dz = centroidsB.z[j] - centroidsA.z[i];
                    double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
                    if (dist < minDist) {
                        minDist = dist;
                        minIdx = i;
                    }
                }
                closestAForB[j] = minIdx;
                distBtoA[j] = minDist;
            }

            for (int i = 0; i < idxA.size(); i++) {
                chA.set(idxA.get(i), colDistAtoB, CsvTableIO.formatDist(distAtoB[i]));
            }

            for (int j = 0; j < idxB.size(); j++) {
                chB.set(idxB.get(j), colDistBtoA, CsvTableIO.formatDist(distBtoA[j]));
            }

            // ClosestTo counts
            int[] countAMappingToB = new int[idxB.size()];
            for (int i = 0; i < idxA.size(); i++) {
                countAMappingToB[closestBForA[i]]++;
            }
            for (int j = 0; j < idxB.size(); j++) {
                chB.set(idxB.get(j), colBClosestToA, String.valueOf(countAMappingToB[j]));
            }

            int[] countBMappingToA = new int[idxA.size()];
            for (int j = 0; j < idxB.size(); j++) {
                countBMappingToA[closestAForB[j]]++;
            }
            for (int i = 0; i < idxA.size(); i++) {
                chA.set(idxA.get(i), colAClosestToB, String.valueOf(countBMappingToA[i]));
            }

            // VolContains counts — only when volumetric colocalization is enabled
            if (doVolumetric) {
                int[] colocAMappingToB = new int[idxB.size()];
                for (int i = 0; i < idxA.size(); i++) {
                    int bIdx = closestBForA[i];
                    if (chA.colIdx.containsKey(colocColAWithB)) {
                        double colocVal = chA.getDouble(idxA.get(i), colocColAWithB);
                        if (colocVal > getThreshold(nameA)) {
                            colocAMappingToB[bIdx]++;
                        }
                    }
                }
                for (int j = 0; j < idxB.size(); j++) {
                    chB.set(idxB.get(j), colBVolContainsA, String.valueOf(colocAMappingToB[j]));
                }

                int[] colocBMappingToA = new int[idxA.size()];
                for (int j = 0; j < idxB.size(); j++) {
                    int aIdx = closestAForB[j];
                    if (chB.colIdx.containsKey(colocColBWithA)) {
                        double colocVal = chB.getDouble(idxB.get(j), colocColBWithA);
                        if (colocVal > getThreshold(nameB)) {
                            colocBMappingToA[aIdx]++;
                        }
                    }
                }
                for (int i = 0; i < idxA.size(); i++) {
                    chA.set(idxA.get(i), colAVolContainsB, String.valueOf(colocBMappingToA[i]));
                }
            }
        }

        IJ.log("    Distances computed for " + nameA + " <-> " + nameB);
    }

    private CentroidSet buildFiniteCentroids(ChannelData cd,
                                             List<Integer> rowIndices,
                                             CalibrationIO.PixelCalibration cal) {
        List<Integer> rows = new ArrayList<Integer>();
        List<Double> xs = new ArrayList<Double>();
        List<Double> ys = new ArrayList<Double>();
        List<Double> zs = new ArrayList<Double>();
        if (rowIndices != null) {
            for (int i = 0; i < rowIndices.size(); i++) {
                int row = rowIndices.get(i);
                double x = resolveAxis(cd, row, "XM", XM_UM, calibratedScale(cal, 'x'));
                double y = resolveAxis(cd, row, "YM", YM_UM, calibratedScale(cal, 'y'));
                double z = resolveAxis(cd, row, "ZM", ZM_UM, calibratedScale(cal, 'z'));
                if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
                    continue;
                }
                rows.add(Integer.valueOf(row));
                xs.add(Double.valueOf(x));
                ys.add(Double.valueOf(y));
                zs.add(Double.valueOf(z));
            }
        }
        return new CentroidSet(rows, toPrimitive(xs), toPrimitive(ys), toPrimitive(zs));
    }

    private static double[] toPrimitive(List<Double> values) {
        double[] out = new double[values.size()];
        for (int i = 0; i < values.size(); i++) {
            out[i] = values.get(i).doubleValue();
        }
        return out;
    }

    private static final class CentroidSet {
        private final List<Integer> rows;
        private final double[] x;
        private final double[] y;
        private final double[] z;

        private CentroidSet(List<Integer> rows, double[] x, double[] y, double[] z) {
            this.rows = rows;
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }

    private Map<String, List<Integer>> groupBySCN(ChannelData cd, boolean validObjectsOnly) {
        Map<String, List<Integer>> groups = new LinkedHashMap<String, List<Integer>>();
        for (int i = 0; i < cd.rows.size(); i++) {
            if (validObjectsOnly && isPlaceholderRow(cd, i)) continue;
            String key = analysisUnitKey(cd, i);
            List<Integer> list = groups.get(key);
            if (list == null) {
                list = new ArrayList<Integer>();
                groups.put(key, list);
            }
            list.add(i);
        }
        return groups;
    }

    private String analysisUnitKey(ChannelData cd, int row) {
        String roi = safeValue(cd, row, "ROI");
        if (!roi.isEmpty()) return roi;

        String region = safeValue(cd, row, "Region");
        if (!region.isEmpty()) return region;

        return safeValue(cd, row, "SCN");
    }

    private void initializeSpatialDefaults(ChannelData cd, List<Integer> rows, String distColumn,
                                           String closestColumn, String volContainsColumn) {
        if (rows == null) return;
        for (int row : rows) {
            cd.set(row, distColumn, CsvTableIO.formatDist(Double.MAX_VALUE));
            cd.set(row, closestColumn, "0");
            if (volContainsColumn != null) cd.set(row, volContainsColumn, "0");
        }
    }

    private void appendCalibratedCentroids(Map<String, ChannelData> channels,
                                           CalibrationIO.PixelCalibration cal) {
        if (cal == null || !cal.isCalibrated()) return;

        for (ChannelData cd : channels.values()) {
            if (!hasPixelCentroids(cd)) continue;

            cd.addColumn(XM_UM);
            cd.addColumn(YM_UM);
            cd.addColumn(ZM_UM);

            for (int row = 0; row < cd.rows.size(); row++) {
                fillIfBlank(cd, row, XM_UM, cd.getDouble(row, "XM") * cal.pixelWidth);
                fillIfBlank(cd, row, YM_UM, cd.getDouble(row, "YM") * cal.pixelHeight);
                fillIfBlank(cd, row, ZM_UM, cd.getDouble(row, "ZM") * cal.pixelDepth);
            }
        }
    }

    private boolean hasPixelCentroids(ChannelData cd) {
        return cd.colIdx.containsKey("XM")
                && cd.colIdx.containsKey("YM")
                && cd.colIdx.containsKey("ZM");
    }

    private boolean hasAnalysisUnitColumn(ChannelData cd) {
        return cd.colIdx.containsKey("ROI")
                || cd.colIdx.containsKey("Region")
                || cd.colIdx.containsKey("SCN");
    }

    private boolean hasCalibrated2DCentroids(ChannelData cd) {
        return cd.colIdx.containsKey(XM_UM) && cd.colIdx.containsKey(YM_UM);
    }

    /**
     * Normalizes legacy volumetric overlap columns so percentage overlap always remains in the
     * original "Colocalisation with X" columns, leaving SOURCE_VolColocN_PARTNER for binary flags.
     */
    private void migrateLegacyVolumetricColumns(Map<String, ChannelData> channels) {
        for (Map.Entry<String, ChannelData> entry : channels.entrySet()) {
            String channelName = entry.getKey();
            ChannelData cd = entry.getValue();
            List<String> headerCopy = new ArrayList<String>(cd.header);
            for (String col : headerCopy) {
                if (col.startsWith("Colocalisation with ")) {
                    continue;
                }

                String overlapPartner = extractVolumetricPartner(col, channelName, "_VolOverlap");
                if (overlapPartner != null) {
                    renameColumn(cd, col, "Colocalisation with " + overlapPartner);
                    continue;
                }

                String legacyPartner = extractVolumetricPartner(col, channelName, "_VolColoc");
                if (legacyPartner != null) {
                    if (looksBinaryNumericColumn(cd, col)) {
                        continue;
                    }
                    renameColumn(cd, col, "Colocalisation with " + legacyPartner);
                }
            }
        }
    }

    private void reorderManagedSpatialColumns(Map<String, ChannelData> channels, List<String> channelNames) {
        for (Map.Entry<String, ChannelData> entry : channels.entrySet()) {
            reorderManagedSpatialColumns(entry.getValue(), channelNames);
        }
    }

    static void reorderManagedSpatialColumns(ChannelData cd, List<String> channelNames) {
        ObjectCsvColumnOrder.reorder(cd, channelNames);
    }

    private void renameColumn(ChannelData cd, String oldCol, String newCol) {
        Integer idx = cd.colIdx.get(oldCol);
        if (idx == null || oldCol.equals(newCol) || cd.colIdx.containsKey(newCol)) return;
        cd.header.set(idx, newCol);
        cd.colIdx.remove(oldCol);
        cd.colIdx.put(newCol, idx);
        IJ.log("  Migrated column: " + oldCol + " -> " + newCol);
    }

    private void refreshVolumetricColocFlags(Map<String, ChannelData> channels, List<String> channelNames,
                                             SpatialObjectDataAvailability detectedData) {
        for (String source : channelNames) {
            ChannelData cd = channels.get(source);
            if (cd == null) continue;
            double threshold = getThreshold(source);
            for (String partner : channelNames) {
                if (source.equals(partner)) continue;
                String colocCol = volColocCol(source, partner);
                if (detectedData != null && hasUsableColumn(cd, colocCol)) {
                    IJ.log("  Reusing existing volumetric colocalization flag: " + colocCol);
                    continue;
                }
                String overlapCol = findVolOverlapColumn(cd, source, partner);
                if (overlapCol == null) continue;
                cd.addColumn(colocCol);
                for (int row = 0; row < cd.rows.size(); row++) {
                    double overlap = cd.getDouble(row, overlapCol);
                    cd.set(row, colocCol, overlap > threshold ? "1" : "0");
                }
            }
        }
    }

    private static String findVolOverlapColumn(ChannelData cd, String source, String partner) {
        if (cd == null) return null;
        String legacy = "Colocalisation with " + partner;
        if (hasUsableColumn(cd, legacy)) {
            return legacy;
        }
        String prefix = source + "_VolOverlap";
        String suffix = "_" + partner;
        for (String col : cd.header) {
            if (col.startsWith(prefix) && col.endsWith(suffix) && hasUsableColumn(cd, col)) {
                return col;
            }
        }
        prefix = source + "_VolColoc";
        for (String col : cd.header) {
            if (col.startsWith(prefix) && col.endsWith(suffix) && hasUsableColumn(cd, col)
                    && !looksBinaryNumericColumn(cd, col)) {
                return col;
            }
        }
        return null;
    }

    private String volColocCol(String source, String partner) {
        return source + "_VolColoc" + (int) getThreshold(source) + "_" + partner;
    }

    private static String extractVolumetricPartner(String col, String source, String token) {
        String prefix = source + token;
        if (!col.startsWith(prefix)) return null;
        int rest = prefix.length();
        while (rest < col.length() && Character.isDigit(col.charAt(rest))) rest++;
        if (rest >= col.length() || col.charAt(rest) != '_') return null;
        return col.substring(rest + 1);
    }

    private static boolean looksBinaryNumericColumn(ChannelData cd, String colName) {
        if (!cd.colIdx.containsKey(colName)) return false;
        for (int row = 0; row < cd.rows.size(); row++) {
            String raw = cd.get(row, colName);
            if (raw == null) continue;
            raw = raw.trim();
            if (raw.isEmpty()) continue;
            if ("0".equals(raw) || "1".equals(raw) || "0.0".equals(raw) || "1.0".equals(raw)) continue;
            try {
                double value = Double.parseDouble(raw);
                if (value == 0.0 || value == 1.0) continue;
            } catch (NumberFormatException e) {
                return false;
            }
            return false;
        }
        return true;
    }

    private void writeSpatialStatisticsOutputs(String directory, Map<String, ChannelData> channels) {
        logArtifactRunPlan("Spatial statistics", SubAnalysis.RIPLEY);
        File spatialDir = spatialDataOutputDir(directory);
        if (!spatialDir.exists() && !spatialDir.mkdirs()) {
            IJ.log("Warning: could not create spatial output directory: " + spatialDir.getAbsolutePath());
            return;
        }

        int channelCounter = 1;
        int totalChannels = channels.size();
        for (Map.Entry<String, ChannelData> entry : channels.entrySet()) {
            String channelName = entry.getKey();
            ChannelData cd = entry.getValue();
            IJ.log("  [" + (channelCounter++) + "/" + totalChannels + "] Spatial Statistics: " + channelName);
            if (shouldSkipChannel(SubAnalysis.RIPLEY, channelName, "spatial statistics")) {
                continue;
            }

            if (!hasCalibrated2DCentroids(cd)) {
                IJ.log("    Skipping spatial statistics for " + channelName
                        + ": calibrated centroid columns (" + XM_UM + ", " + YM_UM + ") are unavailable.");
                continue;
            }

            List<List<String>> rows = buildSpatialStatisticsRows(cd);
            File outFile = new File(spatialDir, "Spatial_Statistics_" + ChannelFilenameCodec.toSafe(channelName) + ".csv");
            writeCsv(outFile, spatialStatisticsHeader(), rows);
            IJ.log("  Spatial statistics written: " + outFile.getName() + " (" + rows.size() + " rows)");
        }
    }

    private List<List<String>> buildSpatialStatisticsRows(ChannelData cd) {
        List<List<String>> rows = new ArrayList<List<String>>();
        Map<SpatialGroupKey, List<Integer>> groups = groupForSpatialStatistics(cd);

        for (Map.Entry<SpatialGroupKey, List<Integer>> entry : groups.entrySet()) {
            SpatialGroupKey key = entry.getKey();
            List<Integer> indices = entry.getValue();
            double[][] points = extract2DPoints(cd, indices);
            if (points.length < 2) {
                continue;
            }

            SpatialStatistics.RectangularWindow window = deriveWindow(points);
            double[] radii = deriveRadii(window);
            if (radii.length == 0) {
                continue;
            }

            double[] k = SpatialStatistics.computeRipleysK(points, window, radii);
            double[] l = SpatialStatistics.computeLFunction(k, radii);
            double[] g = SpatialStatistics.computeGFunction(points, radii);
            SpatialStatistics.MonteCarloEnvelope env =
                    SpatialStatistics.monteCarloEnvelopes(DEFAULT_CSR_SIMULATIONS, window, points.length, radii, key.seed());

            for (int i = 0; i < radii.length; i++) {
                List<String> row = new ArrayList<String>();
                row.add(key.animal);
                row.add(key.hemisphere);
                row.add(key.region);
                row.add(key.roi);
                row.add(String.valueOf(points.length));
                row.add(WINDOW_SOURCE_DERIVED);
                row.add(formatStat(window.minX));
                row.add(formatStat(window.minY));
                row.add(formatStat(window.maxX));
                row.add(formatStat(window.maxY));
                row.add(formatStat(radii[i]));
                row.add(formatStat(k[i]));
                row.add(formatStat(l[i]));
                row.add(formatStat(env.lower[i]));
                row.add(formatStat(env.upper[i]));
                row.add(formatStat(g[i]));
                rows.add(row);
            }
        }

        return rows;
    }

    private Map<SpatialGroupKey, List<Integer>> groupForSpatialStatistics(ChannelData cd) {
        Map<SpatialGroupKey, List<Integer>> groups = new LinkedHashMap<SpatialGroupKey, List<Integer>>();
        for (int i = 0; i < cd.rows.size(); i++) {
            if (isPlaceholderRow(cd, i)) continue;

            SpatialGroupKey key = new SpatialGroupKey(
                    safeValue(cd, i, "Animal Name"),
                    safeValue(cd, i, "Hemisphere"),
                    safeValue(cd, i, "Region"),
                    spatialStatsRoiLabel(cd, i)
            );
            List<Integer> list = groups.get(key);
            if (list == null) {
                list = new ArrayList<Integer>();
                groups.put(key, list);
            }
            list.add(i);
        }
        return groups;
    }

    private boolean isPlaceholderRow(ChannelData cd, int row) {
        if (cd.colIdx.containsKey("Volume (micron^3)")) {
            return cd.getDouble(row, "Volume (micron^3)") <= 0.0;
        }

        boolean hasSurface = cd.colIdx.containsKey("Surface (micron^2)");
        if (!hasSurface || !hasPixelCentroids(cd)) {
            return false;
        }
        return cd.getDouble(row, "Surface (micron^2)") == 0.0
                && cd.getDouble(row, "XM") == 0.0
                && cd.getDouble(row, "YM") == 0.0
                && cd.getDouble(row, "ZM") == 0.0;
    }

    private String safeValue(ChannelData cd, int row, String column) {
        if (!cd.colIdx.containsKey(column)) return "";
        String value = cd.get(row, column);
        return value == null ? "" : value.trim();
    }

    private String spatialStatsRoiLabel(ChannelData cd, int row) {
        String roi = safeValue(cd, row, "ROI");
        if (!roi.isEmpty()) return roi;
        return safeValue(cd, row, "SCN");
    }

    private double[][] extract2DPoints(ChannelData cd, List<Integer> indices) {
        List<double[]> points = new ArrayList<double[]>();
        for (int i = 0; i < indices.size(); i++) {
            int row = indices.get(i);
            String xRaw = cd.get(row, XM_UM);
            String yRaw = cd.get(row, YM_UM);
            if (xRaw == null || xRaw.trim().isEmpty() || yRaw == null || yRaw.trim().isEmpty()) {
                continue;
            }
            double x = CsvTableIO.parseDoubleSafe(xRaw);
            double y = CsvTableIO.parseDoubleSafe(yRaw);
            if (!Double.isFinite(x) || !Double.isFinite(y)) {
                continue;
            }
            points.add(new double[]{x, y});
        }
        double[][] result = new double[points.size()][2];
        for (int i = 0; i < points.size(); i++) {
            result[i] = points.get(i);
        }
        return result;
    }

    private SpatialStatistics.RectangularWindow deriveWindow(double[][] points) {
        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        for (double[] point : points) {
            if (point[0] < minX) minX = point[0];
            if (point[0] > maxX) maxX = point[0];
            if (point[1] < minY) minY = point[1];
            if (point[1] > maxY) maxY = point[1];
        }

        double padding = deriveWindowPadding(points, minX, maxX, minY, maxY);
        return new SpatialStatistics.RectangularWindow(
                minX - padding,
                minY - padding,
                maxX + padding,
                maxY + padding
        );
    }

    private double deriveWindowPadding(double[][] points, double minX, double maxX, double minY, double maxY) {
        double padding = medianPositive(SpatialStatistics.computeNearestNeighborDistances(points)) * 0.5;
        if (padding > 0.0) {
            return padding;
        }

        double span = Math.max(maxX - minX, maxY - minY);
        if (span > 0.0) {
            return span * 0.1;
        }
        return 1.0;
    }

    private double[] deriveRadii(SpatialStatistics.RectangularWindow window) {
        double maxRadius = Math.min(window.width(), window.height()) / 4.0;
        if (maxRadius <= 0.0) {
            return new double[0];
        }
        double[] radii = new double[DEFAULT_STATS_BINS];
        for (int i = 0; i < radii.length; i++) {
            radii[i] = maxRadius * (i + 1) / radii.length;
        }
        return radii;
    }

    private double medianPositive(double[] values) {
        List<Double> positive = new ArrayList<Double>();
        for (double value : values) {
            if (value > 0.0 && !Double.isNaN(value) && !Double.isInfinite(value)) {
                positive.add(value);
            }
        }
        if (positive.isEmpty()) {
            return 0.0;
        }
        double[] sorted = new double[positive.size()];
        for (int i = 0; i < positive.size(); i++) {
            sorted[i] = positive.get(i);
        }
        Arrays.sort(sorted);
        int mid = sorted.length / 2;
        if ((sorted.length % 2) == 1) {
            return sorted[mid];
        }
        return (sorted[mid - 1] + sorted[mid]) / 2.0;
    }

    private List<String> spatialStatisticsHeader() {
        return Arrays.asList(
                "Animal Name",
                "Hemisphere",
                "Region",
                "ROI",
                "NumPoints",
                "WindowSource",
                "WindowMinX_um",
                "WindowMinY_um",
                "WindowMaxX_um",
                "WindowMaxY_um",
                "Radius_um",
                "K",
                "L",
                "L_lower",
                "L_upper",
                "G"
        );
    }

    private void writeCsv(File outFile, List<String> header, List<List<String>> rows) {
        try {
            CsvSupport.writeAtomically(outFile, new CsvSupport.WriterAction() {
                @Override
                public void write(PrintWriter pw) {
                pw.println(CsvTableIO.joinCsv(header));
                for (List<String> row : rows) {
                    pw.println(CsvTableIO.joinCsv(row));
                }
                }
            });
        } catch (IOException e) {
            IJ.log("Warning: could not write spatial statistics file " + outFile.getName() + ": " + e.getMessage());
        }
    }

    private void fillIfBlank(ChannelData cd, int row, String column, double value) {
        String existing = cd.get(row, column);
        if (existing != null && !existing.trim().isEmpty()) return;
        cd.set(row, column, formatNumber(value));
    }

    private double resolveAxis(ChannelData cd, int row, String pixelColumn, String umColumn, double scale) {
        String calibrated = cd.get(row, umColumn);
        if (calibrated != null && !calibrated.trim().isEmpty()) {
            return CsvTableIO.parseDoubleSafe(calibrated);
        }
        double pixelValue = cd.getDouble(row, pixelColumn);
        if (scale > 0.0) {
            return pixelValue * scale;
        }
        return pixelValue;
    }

    private double calibratedScale(CalibrationIO.PixelCalibration cal, char axis) {
        if (cal == null || !cal.isCalibrated()) return -1.0;
        switch (axis) {
            case 'x':
                return cal.pixelWidth;
            case 'y':
                return cal.pixelHeight;
            case 'z':
                return cal.pixelDepth;
            default:
                return -1.0;
        }
    }

    private String formatStat(double value) {
        if (Double.isNaN(value)) return "NaN";
        if (Double.isInfinite(value)) return value > 0.0 ? "Inf" : "-Inf";
        return formatNumber(value);
    }

    private String formatNumber(double value) {
        return String.format(Locale.US, "%.6f", value);
    }

    private void warnUser(String title, String message) {
        IJ.log(message.replace('\n', ' '));
        if (canShowGuiDecisionDialog(suppressDialogs, cliConfig, GraphicsEnvironment.isHeadless())) {
            IJ.showMessage(title, message);
        }
    }

    private void closeLabelImage(ImagePlus image, LabelImageProvider provider,
                                 String channelName, SectionKey section) {
        if (image == null) {
            return;
        }
        if (provider != null && section != null) {
            provider.release(channelName, section, image);
        } else {
            image.changes = false;
            image.close();
            image.flush();
        }
    }

    // ── CPC retroactive colocalization ─────────────────────────────

    /**
     * If CPC columns are missing from the object CSVs, compute them retroactively
     * by loading saved label images from the object image-output folders and running CPC.
     */
    private void runCpcIfNeeded(String directory, Map<String, ChannelData> channels, List<String> channelNames,
                                SpatialObjectDataAvailability detectedData, LabelImageProvider provider) {
        if (channelNames.size() < 2) return;

        logArtifactRunPlan("CPC centroid coincidence", SubAnalysis.CPC);

        if (!isForceRerunActive()
                && !hasPairArtifactStatus()
                && detectedData != null
                && detectedData.hasCompleteCpcForAllChannels(channelNames)) {
            IJ.log("--- Reusing existing CPC columns from 3D Object Analysis; skipping retroactive computation ---");
            return;
        }

        IJ.log("--- Computing CPC colocalization from saved label images ---");

        // Add CPC columns to all channels
        for (String ch : channelNames) {
            ChannelData cd = channels.get(ch);
            for (String other : channelNames) {
                if (other.equals(ch)) continue;
                cd.addColumn(ch + "_CPCColoc_" + other);

                cd.addColumn(ch + "_CPCContains_" + other);
            }
            cd.addColumn(ch + "_CPCTargetsHit");
            cd.addColumn(ch + "_CPCPattern");
        }

        // Process each channel pair — sections parallelized within each pair.
        // Each section writes to distinct ChannelData rows, so no contention.
        Map<String, Map<SectionKey, List<Integer>>> sectionsByChannel =
                buildCpcSectionGroups(directory, channels, channelNames, "CPC");

        int totalPairs = channelNames.size() * (channelNames.size() - 1) / 2;
        int pairNum = 0;
        for (int a = 0; a < channelNames.size(); a++) {
            for (int b = a + 1; b < channelNames.size(); b++) {
                pairNum++;
                final String nameA = channelNames.get(a);
                final String nameB = channelNames.get(b);
                final ChannelData cdA = channels.get(nameA);
                final ChannelData cdB = channels.get(nameB);

                // Group rows by section (same label image)
                Map<SectionKey, List<Integer>> sectionsA = sectionsByChannel.get(nameA);
                Map<SectionKey, List<Integer>> sectionsB = sectionsByChannel.get(nameB);

                Set<SectionKey> allSections = new LinkedHashSet<SectionKey>();
                allSections.addAll(sectionsA.keySet());
                allSections.addAll(sectionsB.keySet());

                List<CpcSectionTask> sectionTasks =
                        buildCpcSectionTasks(directory, nameA, nameB, cdA, cdB, sectionsA, sectionsB, allSections);
                List<CpcSectionTask> runnableTasks = new ArrayList<CpcSectionTask>();
                for (CpcSectionTask task : sectionTasks) {
                    if (!shouldSkipCpcTask(task, nameA, nameB)) {
                        runnableTasks.add(task);
                    }
                }
                sectionTasks = runnableTasks;
                if (sectionTasks.isEmpty()) {
                    IJ.log("  CPC pair [" + pairNum + "/" + totalPairs + "]: " + nameA + " <-> " + nameB
                            + " (no sections needing processing)");
                    continue;
                }

                // Memory-safety invariant: each CPC worker opens two full label images,
                // so worker count must go through AdaptiveParallelism rather than
                // availableProcessors().
                long estimatedWorkerBytes = estimateCpcWorkerBytes(sectionTasks);
                int requestedThreads = Math.max(1, Math.min(parallelThreads, sectionTasks.size()));
                int safeThreads = chooseCpcThreads(estimatedWorkerBytes, requestedThreads, sectionTasks.size());
                IJ.log("  CPC pair [" + pairNum + "/" + totalPairs + "]: " + nameA + " <-> " + nameB
                        + " | sections=" + sectionTasks.size()
                        + " | threads=" + safeThreads
                        + (safeThreads < requestedThreads
                        ? " (reduced from " + requestedThreads + " for memory safety)"
                        : "")
                        + (estimatedWorkerBytes > 0L
                        ? " | est. " + formatMegabytes(estimatedWorkerBytes) + " MB/worker"
                        : ""));

                final int totalSections = sectionTasks.size();
                final AtomicInteger sectionCounter = new AtomicInteger(0);
                if (safeThreads <= 1) {
                    for (CpcSectionTask task : sectionTasks) {
                        processCpcSection(task, nameA, nameB, cdA, cdB, sectionCounter, totalSections, provider);
                    }
                } else {
                    ExecutorService pool = Executors.newFixedThreadPool(safeThreads);
                    try {
                        List<Future<?>> futures = new ArrayList<Future<?>>();
                        for (final CpcSectionTask task : sectionTasks) {
                            futures.add(pool.submit(new Runnable() {
                                @Override
                                public void run() {
                                    processCpcSection(task, nameA, nameB, cdA, cdB,
                                            sectionCounter, totalSections, provider);
                                }
                            }));
                        }

                        for (Future<?> f : futures) {
                            try {
                                f.get();
                            } catch (Exception e) {
                                IJ.log("    Warning: CPC section task failed: " + e.getMessage());
                            }
                        }
                    } finally {
                        pool.shutdown();
                    }
                }

            }
        }

        // Multi-target columns
        for (String ch : channelNames) {
            ChannelData cd = channels.get(ch);
            List<String> partners = new ArrayList<String>();
            for (String other : channelNames) {
                if (!other.equals(ch)) partners.add(other);
            }
            for (int r = 0; r < cd.rows.size(); r++) {
                int hits = 0;
                StringBuilder pattern = new StringBuilder();
                for (String partner : partners) {
                    String val = cd.get(r, ch + "_CPCColoc_" + partner);
                    if ("1".equals(val)) {
                        hits++;
                        if (pattern.length() > 0) pattern.append(" + ");
                        pattern.append(partner);
                    }
                }
                cd.set(r, ch + "_CPCTargetsHit", String.valueOf(hits));
                cd.set(r, ch + "_CPCPattern", hits > 0 ? pattern.toString() : "None");
            }
        }

        IJ.log("--- CPC computation complete ---");
    }

    /**
     * Process one CPC section (one animal/region) for a single channel pair.
     * Thread-safe: each section writes to distinct ChannelData row indices.
     */
    private void processCpcSection(CpcSectionTask task,
                                   String nameA, String nameB,
                                   ChannelData cdA, ChannelData cdB,
                                   AtomicInteger sectionCounter, int totalSections,
                                   LabelImageProvider provider) {
        int sectionNum = sectionCounter.incrementAndGet();
        ImagePlus aLabelImg = provider.get(nameA, task.sectionKey);
        ImagePlus bLabelImg = provider.get(nameB, task.sectionKey);

        if (aLabelImg == null || bLabelImg == null) {
            IJ.log("    Warning: label image not found for section " + task.sectionKey);
            closeLabelImage(aLabelImg, provider, nameA, task.sectionKey);
            closeLabelImage(bLabelImg, provider, nameB, task.sectionKey);
            return;
        }

        try {
            List<CpcUtils.ObjectInfo> objectsA = CpcUtils.extractObjects(aLabelImg);
            List<CpcUtils.ObjectInfo> objectsB = CpcUtils.extractObjects(bLabelImg);

            List<CpcUtils.ObjectInfo> fwdA = CpcUtils.copyObjects(objectsA);
            CpcUtils.testCoincidence(fwdA, bLabelImg);

            List<CpcUtils.ObjectInfo> revB = CpcUtils.copyObjects(objectsB);
            CpcUtils.testCoincidence(revB, aLabelImg);

            Map<Integer, CpcUtils.ObjectInfo> fwdMapA = new LinkedHashMap<Integer, CpcUtils.ObjectInfo>();
            for (CpcUtils.ObjectInfo obj : fwdA) fwdMapA.put(obj.label, obj);
            Map<Integer, CpcUtils.ObjectInfo> revMapB = new LinkedHashMap<Integer, CpcUtils.ObjectInfo>();
            for (CpcUtils.ObjectInfo obj : revB) revMapB.put(obj.label, obj);

            Map<Integer, Integer> containsA = new LinkedHashMap<Integer, Integer>();
            for (CpcUtils.ObjectInfo obj : revB) {
                if (obj.partnerLabel > 0) {
                    Integer prev = containsA.get(obj.partnerLabel);
                    containsA.put(obj.partnerLabel, (prev != null ? prev : 0) + 1);
                }
            }
            Map<Integer, Integer> containsB = new LinkedHashMap<Integer, Integer>();
            for (CpcUtils.ObjectInfo obj : fwdA) {
                if (obj.partnerLabel > 0) {
                    Integer prev = containsB.get(obj.partnerLabel);
                    containsB.put(obj.partnerLabel, (prev != null ? prev : 0) + 1);
                }
            }

            boolean hasLabelA = cdA.colIdx.containsKey("Label");
            for (int rowIdx : task.rowsA) {
                int label = resolveRowLabel(cdA, rowIdx, hasLabelA, aLabelImg);
                if (label <= 0) continue;
                CpcUtils.ObjectInfo info = fwdMapA.get(label);
                cdA.set(rowIdx, nameA + "_CPCColoc_" + nameB, info != null && info.isColocalized() ? "1" : "0");
                Integer cc = containsA.get(label);
                cdA.set(rowIdx, nameA + "_CPCContains_" + nameB, String.valueOf(cc != null ? cc : 0));
            }

            boolean hasLabelB = cdB.colIdx.containsKey("Label");
            for (int rowIdx : task.rowsB) {
                int label = resolveRowLabel(cdB, rowIdx, hasLabelB, bLabelImg);
                if (label <= 0) continue;
                CpcUtils.ObjectInfo info = revMapB.get(label);
                cdB.set(rowIdx, nameB + "_CPCColoc_" + nameA, info != null && info.isColocalized() ? "1" : "0");
                Integer cc = containsB.get(label);
                cdB.set(rowIdx, nameB + "_CPCContains_" + nameA, String.valueOf(cc != null ? cc : 0));
            }

            int cpcAB = 0;
            for (CpcUtils.ObjectInfo obj : fwdA) if (obj.isColocalized()) cpcAB++;
            int cpcBA = 0;
            for (CpcUtils.ObjectInfo obj : revB) if (obj.isColocalized()) cpcBA++;
            IJ.log("    [" + sectionNum + "/" + totalSections + "] " + task.sectionKey
                    + ": " + cpcAB + "/" + objectsA.size() + " fwd, "
                    + cpcBA + "/" + objectsB.size() + " rev");

        } catch (Exception e) {
            IJ.log("    Warning: CPC failed for " + task.sectionKey + ": " + e.getMessage());
        } finally {
            closeLabelImage(aLabelImg, provider, nameA, task.sectionKey);
            closeLabelImage(bLabelImg, provider, nameB, task.sectionKey);
        }
    }

    /**
     * Group CSV rows by section key (Animal Name + Hemisphere + ROI/Region)
     * so each group corresponds to one saved label image.
     */
    /**
     * Group rows by the label-image section they belong to.
     * All rows sharing the same Animal Name + label image file suffix
     * (e.g. "NGF11|RH_SCN") are grouped together.
     */
    private Map<SectionKey, List<Integer>> groupByCpcSection(String directory, String channelName, ChannelData cd) {
        String cacheKey = directory + "|" + channelName;
        Map<SectionKey, List<Integer>> cached = cpcSectionGroupsCache.get(cacheKey);
        if (cached != null) {
            return cached;
        }

        Map<String, List<Integer>> candidateGroups = new LinkedHashMap<String, List<Integer>>();
        Map<String, Integer> representativeRows = new LinkedHashMap<String, Integer>();
        Map<String, String> animals = new LinkedHashMap<String, String>();
        Map<SectionKey, List<Integer>> groups = new LinkedHashMap<SectionKey, List<Integer>>();
        for (int i = 0; i < cd.rows.size(); i++) {
            if (isPlaceholderRow(cd, i)) continue;
            String metadataKey = cpcSectionMetadataKey(cd, i);
            List<Integer> rows = candidateGroups.get(metadataKey);
            if (rows == null) {
                rows = new ArrayList<Integer>();
                candidateGroups.put(metadataKey, rows);
                representativeRows.put(metadataKey, i);
                animals.put(metadataKey, safeValue(cd, i, "Animal Name"));
            }
            rows.add(i);
        }

        for (Map.Entry<String, List<Integer>> entry : candidateGroups.entrySet()) {
            int representativeRow = representativeRows.get(entry.getKey());
            File labelFile = resolveCpcLabelFile(directory, channelName, cd, representativeRow);
            if (labelFile == null) {
                continue;
            }
            SectionKey key = SectionKey.of(animals.get(entry.getKey()),
                    extractResolvedLabelSuffix(channelName, labelFile));
            List<Integer> list = groups.get(key);
            if (list == null) {
                list = new ArrayList<Integer>();
                groups.put(key, list);
            }
            list.addAll(entry.getValue());
        }

        cpcSectionGroupsCache.put(cacheKey, groups);
        return groups;
    }

    private Map<String, Map<SectionKey, List<Integer>>> buildCpcSectionGroups(String directory,
                                                                              Map<String, ChannelData> channels,
                                                                              List<String> channelNames,
                                                                              String phaseLabel) {
        Map<String, Map<SectionKey, List<Integer>>> grouped = new LinkedHashMap<String, Map<SectionKey, List<Integer>>>();
        for (String channelName : channelNames) {
            ChannelData cd = channels.get(channelName);
            if (cd == null) continue;
            Map<SectionKey, List<Integer>> sections = groupByCpcSection(directory, channelName, cd);
            grouped.put(channelName, sections);
            IJ.log("  " + phaseLabel + " sections: " + channelName + " -> " + sections.size());
        }
        return grouped;
    }

    private String cpcSectionMetadataKey(ChannelData cd, int row) {
        return safeValue(cd, row, "Animal Name")
                + '\u0001' + safeValue(cd, row, "Hemisphere")
                + '\u0001' + safeValue(cd, row, "ROI")
                + '\u0001' + safeValue(cd, row, "Region")
                + '\u0001' + safeValue(cd, row, "SCN");
    }

    private List<CpcSectionTask> buildCpcSectionTasks(String directory,
                                                      String nameA,
                                                      String nameB,
                                                      ChannelData cdA,
                                                      ChannelData cdB,
                                                      Map<SectionKey, List<Integer>> sectionsA,
                                                      Map<SectionKey, List<Integer>> sectionsB,
                                                      Set<SectionKey> allSections) {
        List<CpcSectionTask> tasks = new ArrayList<CpcSectionTask>();
        for (SectionKey sectionKey : allSections) {
            List<Integer> rowsA = sectionsA.get(sectionKey);
            List<Integer> rowsB = sectionsB.get(sectionKey);
            if (rowsA == null || rowsA.isEmpty() || rowsB == null || rowsB.isEmpty()) {
                continue;
            }
            File aLabelFile = resolveCpcLabelFile(directory, nameA, sectionKey);
            File bLabelFile = resolveCpcLabelFile(directory, nameB, sectionKey);
            if (aLabelFile == null || bLabelFile == null) {
                continue;
            }
            tasks.add(new CpcSectionTask(sectionKey, rowsA, rowsB,
                    aLabelFile,
                    bLabelFile));
        }
        return tasks;
    }

    private File resolveCpcLabelFile(String directory, String channelName, ChannelData cd, List<Integer> rows) {
        return rows == null || rows.isEmpty() ? null : resolveCpcLabelFile(directory, channelName, cd, rows.get(0));
    }

    File resolveCpcLabelFile(String directory, String channelName, ChannelData cd, int row) {
        String animalName = safeValue(cd, row, "Animal Name");
        String hemisphere = safeValue(cd, row, "Hemisphere");
        String roi = safeValue(cd, row, "ROI");
        String region = safeValue(cd, row, "Region");
        String scn = safeValue(cd, row, "SCN");
        String cacheKey = directory + "|" + channelName + "|"
                + animalName + '\u0001' + hemisphere + '\u0001' + roi + '\u0001' + region + '\u0001' + scn;
        if (cpcLabelFileCache.containsKey(cacheKey)) {
            return cpcLabelFileCache.get(cacheKey);
        }

        File imgDir = resolveImageAnalysisAnimalDirCached(
                objectImageOutputReadRoot(directory), animalName);
        boolean verifyParityFallback = requiresScnParityFallback(hemisphere, roi, region, scn);
        ScnParityFallbackSummary summary = verifyParityFallback
                ? getScnParityFallbackSummary(imgDir, channelName, cd, animalName)
                : null;
        String safeChannelName = ChannelFilenameCodec.toSafe(channelName);
        for (String suffix : resolveCpcLabelSuffixCandidates(
                hemisphere, roi, region, scn, verifyParityFallback, summary)) {
            String fileName = safeChannelName + "_objects" + (suffix.isEmpty() ? "" : "_" + suffix) + ".tif";
            File candidate = new File(imgDir, fileName);
            if (candidate.isFile()
                    && isAcceptedCpcCandidate(suffix, scn, verifyParityFallback, summary,
                    imgDir, channelName)) {
                cpcLabelFileCache.put(cacheKey, candidate);
                return candidate;
            }
        }

        if (verifyParityFallback && summary != null && summary.failureReason != null) {
            logScnParityFallbackWarning(imgDir, channelName,
                    "    Warning: " + summary.failureReason + " for "
                            + channelName + " in " + imgDir.getName()
                            + "; skipping SCN-based LH/RH fallback.");
        }
        cpcLabelFileCache.put(cacheKey, null);
        return null;
    }

    public File resolveCpcLabelFile(String directory, String channelName, SectionKey section) {
        if (section == null) return null;
        File imgDir = resolveImageAnalysisAnimalDirCached(
                objectImageOutputReadRoot(directory), section.animalName());
        File candidate = new File(imgDir, section.labelFileName(channelName));
        return candidate.isFile() ? candidate : null;
    }

    private File resolveImageAnalysisAnimalDirCached(File imageAnalysisRoot, String animalName) {
        String cacheKey = imageAnalysisRoot.getAbsolutePath() + "|" + normalizeAnimalDirectoryKey(animalName);
        if (imageAnalysisAnimalDirCache.containsKey(cacheKey)) {
            return imageAnalysisAnimalDirCache.get(cacheKey);
        }
        File resolved = resolveImageAnalysisAnimalDir(imageAnalysisRoot, animalName);
        imageAnalysisAnimalDirCache.put(cacheKey, resolved);
        return resolved;
    }

    static File resolveImageAnalysisAnimalDir(File imageAnalysisRoot, String animalName) {
        File exact = new File(imageAnalysisRoot, animalName != null ? animalName : "");
        if (exact.isDirectory()) return exact;
        if (imageAnalysisRoot == null || !imageAnalysisRoot.isDirectory()) return exact;

        String targetKey = normalizeAnimalDirectoryKey(animalName);
        if (targetKey.isEmpty()) return exact;

        File[] dirs = JunkFileFilter.listCleanDirectories(imageAnalysisRoot);
        for (File dir : dirs) {
            if (dir != null && dir.isDirectory()
                    && targetKey.equals(normalizeAnimalDirectoryKey(dir.getName()))) {
                return dir;
            }
        }
        return exact;
    }

    static String normalizeAnimalDirectoryKey(String value) {
        if (value == null) return "";
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        normalized = normalized.replaceAll("[\\s_\\-]+", "");
        normalized = normalized.replace("weekone", "week1");
        normalized = normalized.replace("weektwo", "week2");
        normalized = normalized.replace("weekthree", "week3");
        normalized = normalized.replace("weekfour", "week4");
        normalized = normalized.replace("weekfive", "week5");
        normalized = normalized.replace("weeksix", "week6");
        normalized = normalized.replace("weekseven", "week7");
        normalized = normalized.replace("weekeight", "week8");
        normalized = normalized.replace("weeknine", "week9");
        normalized = normalized.replace("weekten", "week10");
        normalized = normalized.replace("weekeleven", "week11");
        normalized = normalized.replace("weektwelve", "week12");
        return normalized;
    }

    private String resolveCpcGroupSuffix(ChannelData cd, int row) {
        return resolveCpcGroupSuffix(
                safeValue(cd, row, "Hemisphere"),
                safeValue(cd, row, "ROI"),
                safeValue(cd, row, "Region"),
                safeValue(cd, row, "SCN"));
    }

    static String resolveCpcGroupSuffix(String hemisphere, String roi, String region, String scn) {
        String suffix = cpcLabelSuffix(hemisphere, roi, region);
        if (!suffix.isEmpty()) return suffix;
        String fallback = inferHemiScnSuffix(scn);
        return fallback != null ? fallback : "";
    }

    static boolean requiresScnParityFallback(String hemisphere, String roi, String region, String scn) {
        return cpcLabelSuffix(hemisphere, roi, region).isEmpty() && parseScnInteger(scn) != null;
    }

    static List<String> cpcLabelSuffixCandidates(String hemisphere, String roi, String region, String scn) {
        List<String> out = new ArrayList<String>();
        addUnique(out, cpcLabelSuffix(hemisphere, roi, region));

        Integer scnNum = parseScnInteger(scn);
        if (scnNum != null) {
            String hemi = (scnNum % 2 == 0) ? "RH" : "LH";
            addUnique(out, hemi + "_SCN" + scnNum);
            addUnique(out, hemi + "_SCN");
            addUnique(out, "SCN" + scnNum);
            addUnique(out, "SCN");
        }

        addUnique(out, "");
        return out;
    }

    private List<String> resolveCpcLabelSuffixCandidates(String hemisphere,
                                                         String roi,
                                                         String region,
                                                         String scn,
                                                         boolean verifyParityFallback,
                                                         ScnParityFallbackSummary summary) {
        if (!verifyParityFallback) {
            return cpcLabelSuffixCandidates(hemisphere, roi, region, scn);
        }

        List<String> out = new ArrayList<String>();
        Integer scnNum = parseScnInteger(scn);
        if (scnNum != null) {
            String hemi = resolveScnFallbackHemisphere(summary, scnNum);
            if (hemi != null) {
                addUnique(out, hemi + "_SCN" + scnNum);
                addUnique(out, hemi + "_SCN");
            }
            addUnique(out, "SCN" + scnNum);
            addUnique(out, "SCN");
        }
        addUnique(out, "");
        return out;
    }

    static boolean candidateNeedsScnParityVerification(String suffix, String scn) {
        Integer scnNum = parseScnInteger(scn);
        if (scnNum == null || suffix == null || suffix.isEmpty()) return false;
        if (suffix.equals("LH_SCN") || suffix.equals("RH_SCN")) return true;
        return suffix.equals("LH_SCN" + scnNum) || suffix.equals("RH_SCN" + scnNum);
    }

    private static void addUnique(List<String> list, String value) {
        if (value == null || value.isEmpty() || list.contains(value)) return;
        list.add(value);
    }

    private static String inferHemiScnSuffix(String scn) {
        Integer scnNum = parseScnInteger(scn);
        if (scnNum == null) return null;
        return (scnNum % 2 == 0 ? "RH" : "LH") + "_SCN";
    }

    private static Integer parseScnInteger(String scn) {
        if (scn == null || scn.trim().isEmpty()) return null;
        try {
            return (int) Math.round(Double.parseDouble(scn.trim()));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private boolean isAcceptedCpcCandidate(String suffix,
                                           String scn,
                                           boolean verifyParityFallback,
                                           ScnParityFallbackSummary summary,
                                           File imgDir,
                                           String channelName) {
        if (!verifyParityFallback || !candidateNeedsScnParityVerification(suffix, scn)) {
            return true;
        }

        Integer scnNum = parseScnInteger(scn);
        if (scnNum == null) return false;
        String hemi = resolveScnFallbackHemisphere(summary, scnNum);
        if (hemi == null) return false;
        return suffix.equals(hemi + "_SCN") || suffix.equals(hemi + "_SCN" + scnNum);
    }

    private ScnParityFallbackSummary getScnParityFallbackSummary(File imgDir,
                                                                 String channelName,
                                                                 ChannelData cd,
                                                                 String animalName) {
        if (imgDir == null) return null;
        String key = imgDir.getAbsolutePath() + "|" + channelName;
        ScnParityFallbackSummary cached = scnParityFallbackCache.get(key);
        if (cached != null) return cached;

        logScnParityFallbackInfo(imgDir, channelName,
                "    Verifying LH/RH mapping from SCN rows for "
                        + channelName + " in " + imgDir.getName() + "...");

        ScnParityFallbackSummary summary = new ScnParityFallbackSummary();
        if (imgDir.isDirectory()) {
            File[] files = JunkFileFilter.listCleanFiles(imgDir);
            if (files.length > 0) {
                String prefix = ChannelFilenameCodec.toSafe(channelName) + "_objects_";
                for (File file : files) {
                    String name = file.getName();
                    if (!name.startsWith(prefix) || !name.endsWith(".tif")) continue;

                    String suffix = name.substring(prefix.length(), name.length() - 4);
                    if ("LH_SCN".equals(suffix)) {
                        summary.hasBaseLh = true;
                    } else if ("RH_SCN".equals(suffix)) {
                        summary.hasBaseRh = true;
                    } else if (suffix.startsWith("LH_SCN")) {
                        Integer n = parseScnInteger(suffix.substring("LH_SCN".length()));
                        if (n != null && (n % 2) == 0) {
                            summary.parityConsistent = false;
                        } else if (n != null) {
                            summary.hasNumberedParityEvidence = true;
                        }
                    } else if (suffix.startsWith("RH_SCN")) {
                        Integer n = parseScnInteger(suffix.substring("RH_SCN".length()));
                        if (n != null && (n % 2) != 0) {
                            summary.parityConsistent = false;
                        } else if (n != null) {
                            summary.hasNumberedParityEvidence = true;
                        }
                    }
                }
            }
        }

        summary.oddRowCount = countRowsForScnParity(cd, animalName, true);
        summary.evenRowCount = countRowsForScnParity(cd, animalName, false);
        if (summary.hasBaseLh) {
            summary.lhObjectCount = countObjectsInLabelImage(new File(imgDir,
                    ChannelFilenameCodec.toSafe(channelName) + "_objects_LH_SCN.tif"));
        }
        if (summary.hasBaseRh) {
            summary.rhObjectCount = countObjectsInLabelImage(new File(imgDir,
                    ChannelFilenameCodec.toSafe(channelName) + "_objects_RH_SCN.tif"));
        }

        if (!summary.parityConsistent) {
            summary.failureReason = "conflicting LH/RH SCN-numbered label files";
        } else if (summary.hasNumberedParityEvidence) {
            summary.oddMapsToLh = Boolean.TRUE;
            summary.successMessage = "    Verified LH/RH mapping from numbered SCN label filenames for "
                    + channelName + " in " + imgDir.getName() + ".";
        } else if (summary.hasBaseLh && summary.hasBaseRh) {
            boolean oddPresent = summary.oddRowCount > 0;
            boolean evenPresent = summary.evenRowCount > 0;
            boolean oddToLh = (!oddPresent || summary.oddRowCount == summary.lhObjectCount)
                    && (!evenPresent || summary.evenRowCount == summary.rhObjectCount);
            boolean oddToRh = (!oddPresent || summary.oddRowCount == summary.rhObjectCount)
                    && (!evenPresent || summary.evenRowCount == summary.lhObjectCount);

            if (oddToLh ^ oddToRh) {
                summary.oddMapsToLh = oddToLh ? Boolean.TRUE : Boolean.FALSE;
                summary.successMessage = "    Verified LH/RH mapping from non-placeholder SCN row counts and label-object counts for "
                        + channelName + " in " + imgDir.getName() + ".";
            } else if (!oddPresent && !evenPresent) {
                summary.failureReason = "no SCN rows available to verify LH/RH fallback";
            } else {
                summary.failureReason = "could not verify LH/RH fallback from CSV row counts and label-object counts";
            }
        } else if (summary.hasBaseLh) {
            boolean oddOnlyMatches = summary.oddRowCount > 0
                    && summary.evenRowCount == 0
                    && summary.oddRowCount == summary.lhObjectCount;
            boolean evenOnlyMatches = summary.evenRowCount > 0
                    && summary.oddRowCount == 0
                    && summary.evenRowCount == summary.lhObjectCount;
            if (oddOnlyMatches ^ evenOnlyMatches) {
                summary.oddMapsToLh = oddOnlyMatches ? Boolean.TRUE : Boolean.FALSE;
                summary.successMessage = "    Verified single-sided LH_SCN fallback for "
                        + channelName + " in " + imgDir.getName() + ".";
            } else {
                summary.failureReason = "single LH_SCN label image did not match the non-placeholder SCN rows";
            }
        } else if (summary.hasBaseRh) {
            boolean oddOnlyMatches = summary.oddRowCount > 0
                    && summary.evenRowCount == 0
                    && summary.oddRowCount == summary.rhObjectCount;
            boolean evenOnlyMatches = summary.evenRowCount > 0
                    && summary.oddRowCount == 0
                    && summary.evenRowCount == summary.rhObjectCount;
            if (oddOnlyMatches ^ evenOnlyMatches) {
                summary.oddMapsToLh = oddOnlyMatches ? Boolean.FALSE : Boolean.TRUE;
                summary.successMessage = "    Verified single-sided RH_SCN fallback for "
                        + channelName + " in " + imgDir.getName() + ".";
            } else {
                summary.failureReason = "single RH_SCN label image did not match the non-placeholder SCN rows";
            }
        } else {
            summary.failureReason = "missing LH_SCN/RH_SCN base label image pair";
        }

        if (summary.successMessage != null) {
            logScnParityFallbackInfo(imgDir, channelName, summary.successMessage);
        }

        scnParityFallbackCache.put(key, summary);
        return summary;
    }

    private int countRowsForScnParity(ChannelData cd, String animalName, boolean oddRows) {
        if (cd == null) return 0;
        String animalKey = normalizeAnimalDirectoryKey(animalName);
        int count = 0;
        for (int i = 0; i < cd.rows.size(); i++) {
            if (isPlaceholderRow(cd, i)) continue;
            if (!animalKey.equals(normalizeAnimalDirectoryKey(safeValue(cd, i, "Animal Name")))) continue;
            Integer scnNum = parseScnInteger(safeValue(cd, i, "SCN"));
            if (scnNum == null) continue;
            if (((scnNum % 2) != 0) == oddRows) {
                count++;
            }
        }
        return count;
    }

    private int countObjectsInLabelImage(File file) {
        if (file == null || !file.isFile()) {
            return -1;
        }

        ImagePlus imp = IJ.openImage(file.getAbsolutePath());
        if (imp == null) {
            return -1;
        }

        try {
            Set<Integer> labels = new LinkedHashSet<Integer>();
            for (int z = 1; z <= imp.getStackSize(); z++) {
                ImageProcessor ip = imp.getStack().getProcessor(z);
                for (int y = 0; y < ip.getHeight(); y++) {
                    for (int x = 0; x < ip.getWidth(); x++) {
                        int label = (int) ip.getf(x, y);
                        if (label > 0) {
                            labels.add(label);
                        }
                    }
                }
            }
            return labels.size();
        } finally {
            imp.close();
            imp.flush();
        }
    }

    private String resolveScnFallbackHemisphere(ScnParityFallbackSummary summary, int scnNum) {
        if (summary == null || summary.oddMapsToLh == null) {
            return null;
        }
        boolean odd = (scnNum % 2) != 0;
        if (odd) {
            return summary.oddMapsToLh.booleanValue() ? "LH" : "RH";
        }
        return summary.oddMapsToLh.booleanValue() ? "RH" : "LH";
    }

    private static String extractResolvedLabelSuffix(String channelName, File labelFile) {
        if (labelFile == null) return "";
        String name = labelFile.getName();
        String prefix = ChannelFilenameCodec.toSafe(channelName) + "_objects";
        if (!name.startsWith(prefix) || !name.endsWith(".tif")) {
            return name;
        }
        String suffix = name.substring(prefix.length(), name.length() - 4);
        return suffix.startsWith("_") ? suffix.substring(1) : suffix;
    }

    private void logScnParityFallbackWarning(File imgDir, String channelName, String message) {
        String dirKey = imgDir != null ? imgDir.getAbsolutePath() : "missing_dir";
        String key = dirKey + "|" + channelName + "|" + message;
        if (scnParityFallbackWarnings.add(key)) {
            IJ.log(message);
        }
    }

    private void logScnParityFallbackInfo(File imgDir, String channelName, String message) {
        String dirKey = imgDir != null ? imgDir.getAbsolutePath() : "missing_dir";
        String key = dirKey + "|" + channelName + "|" + message;
        if (scnParityFallbackInfoLogs.add(key)) {
            IJ.log(message);
        }
    }

    private static final class ScnParityFallbackSummary {
        private boolean parityConsistent = true;
        private boolean hasBaseLh;
        private boolean hasBaseRh;
        private boolean hasNumberedParityEvidence;
        private Boolean oddMapsToLh;
        private int oddRowCount;
        private int evenRowCount;
        private int lhObjectCount = -1;
        private int rhObjectCount = -1;
        private String failureReason;
        private String successMessage;
    }

    private long estimateCpcWorkerBytes(List<CpcSectionTask> tasks) {
        for (CpcSectionTask task : tasks) {
            long aBytes = estimateImageBytes(task.aLabelFile);
            long bBytes = estimateImageBytes(task.bLabelFile);
            if (aBytes <= 0L || bBytes <= 0L) {
                continue;
            }
            long pairBytes = aBytes + bBytes;
            if (pairBytes > Long.MAX_VALUE / CPC_MEMORY_SAFETY_MULTIPLIER) {
                return Long.MAX_VALUE;
            }
            return pairBytes * CPC_MEMORY_SAFETY_MULTIPLIER;
        }
        return 0L;
    }

    static int chooseCpcThreads(long estimatedWorkerBytes, int requestedThreads, int sectionCount) {
        return AdaptiveParallelism.computeSafeThreadsForInMemoryTasks(
                estimatedWorkerBytes, requestedThreads, sectionCount);
    }

    private long estimateImageBytes(File file) {
        if (file == null || !file.isFile()) {
            return 0L;
        }

        ImagePlus imp = ij.IJ.openImage(file.getAbsolutePath());
        if (imp == null) {
            if (verboseLogging) {
                IJ.log("    [DEBUG] Unable to open CPC label image for memory estimate: " + file.getAbsolutePath());
            }
            return 0L;
        }

        try {
            int bytesPerPixel = Math.max(1, imp.getBitDepth() / 8);
            return AdaptiveParallelism.estimateImageBytes(
                    imp.getWidth(),
                    imp.getHeight(),
                    Math.max(1, imp.getNSlices()),
                    Math.max(1, imp.getNChannels()),
                    bytesPerPixel);
        } finally {
            imp.close();
            imp.flush();
        }
    }

    private String formatMegabytes(long bytes) {
        return String.valueOf(bytes / (1024L * 1024L));
    }

    private static final class CpcSectionTask {
        private final SectionKey sectionKey;
        private final List<Integer> rowsA;
        private final List<Integer> rowsB;
        private final File aLabelFile;
        private final File bLabelFile;

        private CpcSectionTask(SectionKey sectionKey, List<Integer> rowsA, List<Integer> rowsB,
                               File aLabelFile, File bLabelFile) {
            this.sectionKey = sectionKey;
            this.rowsA = rowsA;
            this.rowsB = rowsB;
            this.aLabelFile = aLabelFile;
            this.bLabelFile = bLabelFile;
        }
    }

    /**
     * Construct the file suffix used by ThreeDObjectAnalysis.saveObjectsImages.
     * The saved filename uses the base ROI set name (e.g. "SCN"), not the
     * per-section Region value (e.g. "SCN144"). If no ROI column exists,
     * strip trailing digits from Region to recover the base name.
     */
    private static String cpcLabelSuffix(String hemisphere, String roi, String region) {
        boolean hasH = hemisphere != null && !hemisphere.isEmpty();
        // ROI column = base name directly; Region column = base + scn index digits
        String r;
        if (roi != null && !roi.isEmpty()) {
            r = roi;
        } else if (region != null && !region.isEmpty()) {
            r = region.replaceAll("\\d+$", "");
        } else {
            r = null;
        }
        boolean hasR = r != null && !r.isEmpty();
        if (hasH && hasR) return hemisphere + "_" + r;
        if (hasH) return hemisphere;
        if (hasR) return r;
        return "";
    }

    /**
     * Resolve a CSV row's object label from Label column or self-lookup.
     */
    private int resolveRowLabel(ChannelData cd, int row, boolean hasLabelCol, ImagePlus labelImg) {
        if (hasLabelCol) {
            return (int) cd.getDouble(row, "Label");
        }

        // Self-lookup: read pixel at centroid position from own label image
        double x = cd.getDouble(row, "XM");
        double y = cd.getDouble(row, "YM");
        double z = cd.getDouble(row, "ZM");
        int px = (int) Math.round(x);
        int py = (int) Math.round(y);
        int pz = (int) Math.round(z);

        int w = labelImg.getWidth();
        int h = labelImg.getHeight();
        int nSlices = labelImg.getStack().getSize();

        // Detect calibrated coordinates (much larger than image dimensions)
        if (px > w * 2 || py > h * 2) {
            Calibration cal = labelImg.getCalibration();
            if (cal != null && cal.pixelWidth > 0) {
                px = (int) Math.round(x / cal.pixelWidth);
                py = (int) Math.round(y / cal.pixelHeight);
                pz = cal.pixelDepth > 0 ? (int) Math.round(z / cal.pixelDepth) : pz;
            }
        }

        if (px < 0 || px >= w || py < 0 || py >= h) return 0;
        pz = Math.max(0, Math.min(pz, nSlices - 1));

        int label = (int) labelImg.getStack().getProcessor(pz + 1).getf(px, py);

        // Concave object fallback: search 3x3x3 neighborhood
        if (label <= 0) {
            Map<Integer, Integer> neighbors = new LinkedHashMap<Integer, Integer>();
            for (int dz = -1; dz <= 1; dz++) {
                int sz = pz + dz;
                if (sz < 0 || sz >= nSlices) continue;
                ImageProcessor ip = labelImg.getStack().getProcessor(sz + 1);
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        int nx = px + dx;
                        int ny = py + dy;
                        if (nx < 0 || nx >= w || ny < 0 || ny >= h) continue;
                        int nl = (int) ip.getf(nx, ny);
                        if (nl > 0) {
                            Integer count = neighbors.get(nl);
                            neighbors.put(nl, (count != null ? count : 0) + 1);
                        }
                    }
                }
            }
            int maxCount = 0;
            for (Map.Entry<Integer, Integer> entry : neighbors.entrySet()) {
                if (entry.getValue() > maxCount) {
                    maxCount = entry.getValue();
                    label = entry.getKey();
                }
            }
        }

        return label;
    }

    // ── Voronoi tessellation ─────────────────────────────────────────

    private void runVoronoiAnalysis(String directory, Map<String, ChannelData> channels,
                                     CalibrationIO.PixelCalibration cal) {
        logArtifactRunPlan("Voronoi territory analysis", SubAnalysis.VORONOI);
        File spatialDir = spatialDataOutputDir(directory);
        try {
            IoUtils.mustMkdirs(spatialDir);
        } catch (IOException e) {
            IJ.log("[FLASH] Could not create Spatial directory: " + e.getMessage()
                    + " — skipping Voronoi analysis.");
            return;
        }

        // Collect all channel types per analysis unit for interaction matrix
        Map<SpatialGroupKey, Map<String, List<Integer>>> groupsByUnit =
                new LinkedHashMap<SpatialGroupKey, Map<String, List<Integer>>>();

        int channelCounter = 1;
        int totalChannels = channels.size();
        for (Map.Entry<String, ChannelData> entry : channels.entrySet()) {
            String channelName = entry.getKey();
            ChannelData cd = entry.getValue();
            IJ.log("  [" + (channelCounter++) + "/" + totalChannels + "] Voronoi: " + channelName);
            if (shouldSkipChannel(SubAnalysis.VORONOI, channelName, "Voronoi")) {
                continue;
            }
            if (!hasCalibrated2DCentroids(cd)) {
                IJ.log("    Skipping Voronoi for " + channelName + ": no calibrated centroids.");
                continue;
            }

            cd.addColumn("Voronoi_TerritoryArea_um2");
            cd.addColumn("Voronoi_NumNeighbors");

            Map<SpatialGroupKey, List<Integer>> groups = groupForSpatialStatistics(cd);

            // Per-channel Voronoi output
            List<String> voronoiHeader = Arrays.asList(
                    "Animal Name", "Hemisphere", "Region", "ROI", "Label",
                    "TerritoryArea_um2", "NumNeighbors", "Channel");
            List<List<String>> voronoiRows = new ArrayList<List<String>>();

            for (Map.Entry<SpatialGroupKey, List<Integer>> ge : groups.entrySet()) {
                SpatialGroupKey key = ge.getKey();
                List<Integer> indices = ge.getValue();
                double[][] points = extract2DPoints(cd, indices);
                if (points.length < 3) continue;

                SpatialStatistics.RectangularWindow window = deriveWindow(points);
                VoronoiAnalysis.VoronoiResult[] results = VoronoiAnalysis.compute(points, window);

                for (int i = 0; i < results.length && i < indices.size(); i++) {
                    int rowIdx = indices.get(i);
                    cd.set(rowIdx, "Voronoi_TerritoryArea_um2", formatStat(results[i].territoryArea));
                    cd.set(rowIdx, "Voronoi_NumNeighbors", String.valueOf(results[i].numNeighbors));

                    List<String> row = new ArrayList<String>();
                    row.add(key.animal);
                    row.add(key.hemisphere);
                    row.add(key.region);
                    row.add(key.roi);
                    row.add(safeValue(cd, rowIdx, "Label"));
                    row.add(formatStat(results[i].territoryArea));
                    row.add(String.valueOf(results[i].numNeighbors));
                    row.add(channelName);
                    voronoiRows.add(row);
                }

                // Accumulate for interaction matrix
                if (!groupsByUnit.containsKey(key)) {
                    groupsByUnit.put(key, new LinkedHashMap<String, List<Integer>>());
                }
                Map<String, List<Integer>> channelMap = groupsByUnit.get(key);
                channelMap.put(channelName, indices);
            }

            if (!voronoiRows.isEmpty()) {
                File outFile = new File(spatialDir, "Voronoi_" + ChannelFilenameCodec.toSafe(channelName) + ".csv");
                writeCsv(outFile, voronoiHeader, voronoiRows);
                IJ.log("  Voronoi written: " + outFile.getName() + " (" + voronoiRows.size() + " cells)");
            }
        }

        // Interaction matrix across all channel types
        writeInteractionMatrix(directory, channels, groupsByUnit);
    }

    private void writeInteractionMatrix(String directory, Map<String, ChannelData> channels,
                                         Map<SpatialGroupKey, Map<String, List<Integer>>> groupsByUnit) {
        File spatialDir = spatialDataOutputDir(directory);
        List<String> channelNames = new ArrayList<String>(channels.keySet());
        if (channelNames.size() < 2) return;

        List<String> header = new ArrayList<String>();
        header.add("Animal Name");
        header.add("Hemisphere");
        header.add("Region");
        header.add("ROI");
        header.add("TypeA");
        header.add("TypeB");
        header.add("ObservedAdjacency");
        header.add("PermutationP");
        List<List<String>> rows = new ArrayList<List<String>>();

        for (Map.Entry<SpatialGroupKey, Map<String, List<Integer>>> ge : groupsByUnit.entrySet()) {
            SpatialGroupKey key = ge.getKey();
            Map<String, List<Integer>> chMap = ge.getValue();

            // Build combined point set with type labels for this analysis unit
            List<double[]> allPoints = new ArrayList<double[]>();
            List<String> allTypes = new ArrayList<String>();
            for (Map.Entry<String, List<Integer>> ce : chMap.entrySet()) {
                String chName = ce.getKey();
                ChannelData cd = channels.get(chName);
                for (int idx : ce.getValue()) {
                    String xRaw = cd.get(idx, XM_UM);
                    String yRaw = cd.get(idx, YM_UM);
                    if (xRaw == null || xRaw.trim().isEmpty()) continue;
                    if (yRaw == null || yRaw.trim().isEmpty()) continue;
                    allPoints.add(new double[]{
                            CsvTableIO.parseDoubleSafe(xRaw),
                            CsvTableIO.parseDoubleSafe(yRaw)
                    });
                    allTypes.add(chName);
                }
            }

            if (allPoints.size() < 3) continue;

            double[][] pts = allPoints.toArray(new double[0][]);
            String[] types = allTypes.toArray(new String[0]);
            SpatialStatistics.RectangularWindow window = deriveWindow(pts);
            VoronoiAnalysis.VoronoiResult[] vr = VoronoiAnalysis.compute(pts, window);
            if (vr.length == 0) continue;

            VoronoiAnalysis.InteractionMatrix im = VoronoiAnalysis.computeInteractionMatrix(
                    vr, types, DEFAULT_VORONOI_PERMUTATIONS, key.seed());

            for (int a = 0; a < im.types.length; a++) {
                for (int b = a; b < im.types.length; b++) {
                    List<String> row = new ArrayList<String>();
                    row.add(key.animal);
                    row.add(key.hemisphere);
                    row.add(key.region);
                    row.add(key.roi);
                    row.add(im.types[a]);
                    row.add(im.types[b]);
                    row.add(String.valueOf(im.counts[a][b]));
                    row.add(formatStat(im.pValues[a][b]));
                    rows.add(row);
                }
            }
        }

        if (!rows.isEmpty()) {
            File outFile = new File(spatialDir, "Interaction_Matrix.csv");
            writeCsv(outFile, header, rows);
            IJ.log("  Interaction matrix written: " + outFile.getName() + " (" + rows.size() + " pairs)");
        }
    }

    // ── Cell phenotyping (k-means) ────────────────────────────────────

    private void runCellClustering(String directory, Map<String, ChannelData> channels,
                                    int requestedK) {
        logArtifactRunPlan("K-means clustering", SubAnalysis.PHENOTYPING);
        File phenotypeDir = new File(spatialDataOutputDir(directory), "Phenotyping");
        try {
            IoUtils.mustMkdirs(phenotypeDir);
        } catch (IOException e) {
            IJ.log("[FLASH] Could not create Phenotyping directory: " + e.getMessage()
                    + " — skipping cell phenotyping.");
            return;
        }

        // Build feature matrix across all channels: for each object, collect
        // numeric values from all available measurement columns
        List<String> featureColumns = Arrays.asList(
                "Volume (micron^3)", "Surface (micron^2)", "IntDen", "Mean");

        int channelCounter = 1;
        int totalChannels = channels.size();
        for (Map.Entry<String, ChannelData> entry : channels.entrySet()) {
            String channelName = entry.getKey();
            IJ.log("  [" + (channelCounter++) + "/" + totalChannels + "] Phenotyping: " + channelName);
            ChannelData cd = entry.getValue();
            if (shouldSkipChannel(SubAnalysis.PHENOTYPING, channelName, "phenotyping")) {
                continue;
            }

            // Identify usable feature columns in this channel
            List<String> usable = new ArrayList<String>();
            for (String col : featureColumns) {
                if (cd.colIdx.containsKey(col)) usable.add(col);
            }
            // Also include any colocalization columns (new or old format)
            for (String col : cd.header) {
                if (col.startsWith("Colocalisation with ")
                        || col.contains("_VolOverlap")
                        || col.contains("_ObjPearson_")
                        || col.contains("_ObjMandersM")
                        || col.contains("_ObjCostes")
                        || col.contains("_Pearson_")
                        || col.contains("_Manders_M")
                        || col.contains("_Costes_")) {
                    usable.add(col);
                }
            }

            if (usable.size() < 2) {
                IJ.log("  Skipping clustering for " + channelName + ": too few feature columns.");
                continue;
            }

            // Build feature matrix (non-placeholder rows only)
            List<Integer> validRows = new ArrayList<Integer>();
            for (int i = 0; i < cd.rows.size(); i++) {
                if (!isPlaceholderRow(cd, i)) validRows.add(i);
            }
            if (validRows.size() < 4) {
                IJ.log("  Skipping clustering for " + channelName + ": too few objects (" + validRows.size() + ").");
                continue;
            }

            double[][] features = new double[validRows.size()][usable.size()];
            for (int i = 0; i < validRows.size(); i++) {
                for (int j = 0; j < usable.size(); j++) {
                    features[i][j] = cd.getDouble(validRows.get(i), usable.get(j));
                }
            }

            // Run clustering
            CellClustering.ClusterResult result;
            long seed = channelName.hashCode();
            if (requestedK > 0) {
                result = CellClustering.cluster(features, requestedK, seed);
            } else {
                result = CellClustering.autoCluster(features,
                        DEFAULT_CLUSTER_MIN_K, DEFAULT_CLUSTER_MAX_K, seed);
            }

            IJ.log("  " + channelName + ": k=" + result.k
                    + " (silhouette=" + formatStat(result.silhouetteScore)
                    + ", n=" + validRows.size() + ")");

            // Add Cluster column
            cd.addColumn("Cluster");
            for (int i = 0; i < validRows.size(); i++) {
                cd.set(validRows.get(i), "Cluster", String.valueOf(result.assignments[i]));
            }

            // Write cluster summary
            List<String> clusterHeader = new ArrayList<String>();
            clusterHeader.add("Cluster");
            clusterHeader.add("Count");
            for (String col : usable) clusterHeader.add("Mean_" + col);

            List<List<String>> clusterRows = new ArrayList<List<String>>();
            for (int c = 0; c < result.k; c++) {
                int count = 0;
                double[] sums = new double[usable.size()];
                for (int i = 0; i < result.assignments.length; i++) {
                    if (result.assignments[i] == c) {
                        count++;
                        for (int j = 0; j < usable.size(); j++) sums[j] += features[i][j];
                    }
                }
                List<String> row = new ArrayList<String>();
                row.add(String.valueOf(c));
                row.add(String.valueOf(count));
                for (int j = 0; j < usable.size(); j++) {
                    row.add(formatStat(count > 0 ? sums[j] / count : 0));
                }
                clusterRows.add(row);
            }

            File outFile = new File(phenotypeDir, "Clusters_" + ChannelFilenameCodec.toSafe(channelName) + ".csv");
            writeCsv(outFile, clusterHeader, clusterRows);
            IJ.log("  Cluster summary: " + outFile.getName());
        }
    }

    // ── Density heatmaps ──────────────────────────────────────────────

    private void runDensityHeatmaps(String directory, Map<String, ChannelData> channels,
                                     CalibrationIO.PixelCalibration cal,
                                      double bandwidth, String lutName,
                                      LabelImageProvider provider) {
        logArtifactRunPlan("Density heatmaps", SubAnalysis.DENSITY_HEATMAPS);
        if (cal == null || !cal.isCalibrated()) {
            IJ.log("  Heatmaps require calibration metadata. Skipping.");
            return;
        }

        // Determine image dimensions from the first available label image
        int imgWidth = 0;
        int imgHeight = 0;
        double pixelSize = cal.pixelWidth;

        LabelImageDimensions dimensions = probeFirstLabelDimensions(directory, channels, provider);
        if (dimensions != null) {
            imgWidth = dimensions.width;
            imgHeight = dimensions.height;
        }

        if (imgWidth == 0 || imgHeight == 0) {
            IJ.log("  Could not determine image dimensions for heatmaps. Skipping.");
            return;
        }

        int channelCounter = 1;
        int totalChannels = channels.size();
        for (Map.Entry<String, ChannelData> entry : channels.entrySet()) {
            String channelName = entry.getKey();
            ChannelData cd = entry.getValue();
            IJ.log("  [" + (channelCounter++) + "/" + totalChannels + "] Density Heatmap: " + channelName);
            if (!hasCalibrated2DCentroids(cd)) continue;

            Map<SpatialGroupKey, List<Integer>> groups = groupForSpatialStatistics(cd);
            for (Map.Entry<SpatialGroupKey, List<Integer>> ge : groups.entrySet()) {
                SpatialGroupKey key = ge.getKey();
                SectionKey section = sectionKeyFromSpatialGroup(key);
                if (shouldSkipPair(SubAnalysis.DENSITY_HEATMAPS, section, channelName, "density heatmap")) {
                    continue;
                }
                List<Integer> indices = ge.getValue();
                double[][] points = extract2DPoints(cd, indices);
                if (points.length < 2) continue;

                // Convert micron centroids back to pixel coordinates for heatmap generation
                // (the heatmap is in image pixel space)
                double[][] pixelPts = new double[points.length][2];
                for (int i = 0; i < points.length; i++) {
                    pixelPts[i][0] = points[i][0]; // already in um
                    pixelPts[i][1] = points[i][1];
                }

                ImagePlus heatmap = DensityHeatmapGenerator.generate(
                        pixelPts, imgWidth, imgHeight, pixelSize, bandwidth);
                if (heatmap == null) continue;

                DensityHeatmapGenerator.applyLut(heatmap, lutName);

                // Preserve the legacy per-animal Heatmaps subfolder under the spatial image-output root.
                String animalName = key.animal.isEmpty() ? "unknown" : key.animal;
                File heatmapDir = new File(spatialImageOutputDir(directory),
                        animalName + File.separator + "Heatmaps");
                String suffix = key.hemisphere.isEmpty() && key.region.isEmpty() ? "" :
                        "_" + (key.hemisphere.isEmpty() ? "" : key.hemisphere)
                                + (key.region.isEmpty() ? "" : "_" + key.region);
                String baseName = "Density_" + ChannelFilenameCodec.toSafe(channelName) + suffix;

                DensityHeatmapGenerator.saveHeatmap(heatmap, heatmapDir, baseName);
                IJ.log("  Heatmap: " + baseName + " (" + points.length + " objects)");

                heatmap.close();
                heatmap.flush();
            }
        }
    }

    private LabelImageDimensions probeFirstLabelDimensions(String directory,
                                                           Map<String, ChannelData> channels,
                                                           LabelImageProvider provider) {
        for (Map.Entry<String, ChannelData> entry : channels.entrySet()) {
            String channelName = entry.getKey();
            Map<SectionKey, List<Integer>> sections = groupByCpcSection(directory, channelName, entry.getValue());
            for (SectionKey section : sections.keySet()) {
                ImagePlus probe = provider.get(channelName, section);
                if (probe == null) {
                    continue;
                }
                try {
                    return new LabelImageDimensions(probe.getWidth(), probe.getHeight());
                } finally {
                    closeLabelImage(probe, provider, channelName, section);
                }
            }
        }
        return null;
    }

    private static final class LabelImageDimensions {
        private final int width;
        private final int height;

        private LabelImageDimensions(int width, int height) {
            this.width = width;
            this.height = height;
        }
    }

    // ── Morphology extraction ─────────────────────────────────────────

    private void runMorphologyExtraction(String directory, Map<String, ChannelData> channels,
                                          CalibrationIO.PixelCalibration cal,
                                          LabelImageProvider provider) {
        logArtifactRunPlan("2D morphology extraction", SubAnalysis.MORPHOLOGY_2D);
        File imageAnalysisRoot = objectImageOutputReadRoot(directory);
        if (!imageAnalysisRoot.isDirectory()) {
            IJ.log("  Object image-output directory not found. Cannot extract morphology.");
            return;
        }

        double pixelSize = (cal != null && cal.isCalibrated()) ? cal.pixelWidth : 1.0;

        File morphDir = spatialMorphometryOutputDir(directory);
        try {
            IoUtils.mustMkdirs(morphDir);
        } catch (IOException e) {
            IJ.log("[FLASH] Could not create Morphology directory: " + e.getMessage()
                    + " — skipping morphology extraction.");
            return;
        }

        int channelCounter = 1;
        int totalChannels = channels.size();
        for (Map.Entry<String, ChannelData> entry : channels.entrySet()) {
            String channelName = entry.getKey();
            IJ.log("  [" + (channelCounter++) + "/" + totalChannels + "] Morphology: " + channelName);
            ChannelData cd = entry.getValue();
            if (!isForceRerunActive()
                    && !hasPairArtifactStatus()
                    && hasUsableColumns(cd, MORPH_2D_COLUMNS)) {
                IJ.log("  " + channelName + ": reusing existing 2D morphology columns");
                continue;
            }

            // Add morphology columns
            for (String column : MORPH_2D_COLUMNS) cd.addColumn(column);

            // Per-channel morphology CSV
            List<String> morphHeader = Arrays.asList(
                    "Animal Name", "Hemisphere", "Region", "ROI", "Label",
                    "Area_um2", "Perimeter_um", "Circularity", "Solidity",
                    "AspectRatio", "Feret_um", "Extent", "ConvexHullArea_um2");
            List<List<String>> morphRows = new ArrayList<List<String>>();

            // Group by section (same as CPC) to match label images
            Map<SectionKey, List<Integer>> sections = groupByCpcSection(directory, channelName, cd);

            for (Map.Entry<SectionKey, List<Integer>> se : sections.entrySet()) {
                SectionKey section = se.getKey();
                List<Integer> rowIndices = se.getValue();
                if (rowIndices.isEmpty()) continue;
                if (shouldSkipPair(SubAnalysis.MORPHOLOGY_2D, section, channelName, "2D morphology")) {
                    continue;
                }

                int firstRow = rowIndices.get(0);
                String animalName = safeValue(cd, firstRow, "Animal Name");
                String hemisphere = safeValue(cd, firstRow, "Hemisphere");
                String roi = safeValue(cd, firstRow, "ROI");
                String region = safeValue(cd, firstRow, "Region");

                String labelFileName = section.labelFileName(channelName);

                ImagePlus labelImg = provider.get(channelName, section);
                if (labelImg == null) {
                    IJ.log("    Label image not found: " + labelFileName);
                    continue;
                }

                try {
                    List<MorphologyExtractor.ObjectMorphology> morphList =
                            MorphologyExtractor.extract(labelImg, pixelSize);

                    // Build label → morphology lookup
                    Map<Integer, MorphologyExtractor.ObjectMorphology> morphMap =
                            new LinkedHashMap<Integer, MorphologyExtractor.ObjectMorphology>();
                    for (MorphologyExtractor.ObjectMorphology m : morphList) {
                        morphMap.put(m.label, m);
                    }

                    // Match to CSV rows by label
                    boolean hasLabelCol = cd.colIdx.containsKey("Label");
                    for (int rowIdx : rowIndices) {
                        int label = resolveRowLabel(cd, rowIdx, hasLabelCol, labelImg);
                        MorphologyExtractor.ObjectMorphology m = morphMap.get(label);
                        if (m == null) continue;

                        cd.set(rowIdx, "Morph_Area_um2", formatStat(m.areaUm2));
                        cd.set(rowIdx, "Morph_Perimeter_um", formatStat(m.perimeter));
                        cd.set(rowIdx, "Morph_Circularity", formatStat(m.circularity));
                        cd.set(rowIdx, "Morph_Solidity", formatStat(m.solidity));
                        cd.set(rowIdx, "Morph_AspectRatio", formatStat(m.aspectRatio));
                        cd.set(rowIdx, "Morph_Feret_um", formatStat(m.feretDiameter));
                        cd.set(rowIdx, "Morph_Extent", formatStat(m.extent));
                        cd.set(rowIdx, "Morph_ConvexHullArea_um2", formatStat(m.convexHullArea));

                        List<String> row = new ArrayList<String>();
                        row.add(animalName);
                        row.add(hemisphere);
                        row.add(region);
                        row.add(roi);
                        row.add(String.valueOf(label));
                        row.add(formatStat(m.areaUm2));
                        row.add(formatStat(m.perimeter));
                        row.add(formatStat(m.circularity));
                        row.add(formatStat(m.solidity));
                        row.add(formatStat(m.aspectRatio));
                        row.add(formatStat(m.feretDiameter));
                        row.add(formatStat(m.extent));
                        row.add(formatStat(m.convexHullArea));
                        morphRows.add(row);
                    }

                    IJ.log("    " + animalName + "/" + labelFileName + ": "
                            + morphList.size() + " objects measured");
                } catch (Exception e) {
                    IJ.log("    Morphology extraction failed for " + labelFileName
                            + ": " + e.getClass().getSimpleName() + " - " + e.getMessage());
                } finally {
                    closeLabelImage(labelImg, provider, channelName, section);
                }
            }

            if (!morphRows.isEmpty()) {
                File outFile = new File(morphDir, ChannelFilenameCodec.toSafe(channelName) + "_Morphology.csv");
                writeCsv(outFile, morphHeader, morphRows);
                IJ.log("  Morphology written: " + outFile.getName() + " (" + morphRows.size() + " objects)");
            }
        }
    }

    // ── 3D Morphometric Analysis ─────────────────────────────────────

    /** Check whether any channel in the map has a given column. */
    private static boolean hasColumn(Map<String, ChannelData> channels, String colName) {
        for (ChannelData cd : channels.values()) {
            if (cd.colIdx.containsKey(colName)) return true;
        }
        return false;
    }

    private static boolean hasUsableColumns(ChannelData cd, String[] colNames) {
        if (colNames == null || colNames.length == 0) return false;
        for (String colName : colNames) {
            if (!hasUsableColumn(cd, colName)) return false;
        }
        return true;
    }

    private static boolean hasUsableColumn(ChannelData cd, String colName) {
        if (cd == null || colName == null || !cd.colIdx.containsKey(colName)) return false;
        if (cd.rows.isEmpty()) return true;
        for (int row = 0; row < cd.rows.size(); row++) {
            String value = cd.get(row, colName);
            if (value != null && !value.trim().isEmpty()) return true;
        }
        return false;
    }

    /**
     * Extracts 3D morphometric features from saved label images using mcib3d.
     * Follows the same label-image-loading pattern as {@link #runMorphologyExtraction}.
     */
    private void run3DMorphometry(String directory, Map<String, ChannelData> channels,
                                  CalibrationIO.PixelCalibration cal, boolean doComposites,
                                  LabelImageProvider provider) {
        logArtifactRunPlan("3D shape features", SubAnalysis.SHAPE_FEATURES_3D);
        File imageAnalysisRoot = objectImageOutputReadRoot(directory);
        if (!imageAnalysisRoot.isDirectory()) {
            IJ.log("  Object image-output directory not found. Cannot extract 3D morphometry.");
            return;
        }

        File morphometryDir = spatialMorphometryOutputDir(directory);
        try {
            IoUtils.mustMkdirs(morphometryDir);
        } catch (IOException e) {
            IJ.log("[FLASH] Could not create Morphometry directory: " + e.getMessage()
                    + " — skipping 3D morphometry.");
            return;
        }

        int channelCounter = 1;
        int totalChannels = channels.size();
        for (Map.Entry<String, ChannelData> entry : channels.entrySet()) {
            String channelName = entry.getKey();
            IJ.log("  [" + (channelCounter++) + "/" + totalChannels + "] 3D Morphometry: " + channelName);
            ChannelData cd = entry.getValue();
            boolean hasRequired3DData = hasUsableColumns(cd, MORPH_3D_COLUMNS)
                    && (!doComposites || hasUsableColumns(cd, MORPH_COMPOSITE_COLUMNS));
            if (!isForceRerunActive()
                    && !hasPairArtifactStatus()
                    && hasRequired3DData) {
                IJ.log("  " + channelName + ": reusing existing 3D morphometry columns");
                continue;
            }

            // Add raw 3D feature columns
            for (String column : MORPH_3D_COLUMNS) cd.addColumn(column);

            if (doComposites) {
                for (String column : MORPH_COMPOSITE_COLUMNS) cd.addColumn(column);
            }

            // Group by section (same as CPC/2D morphology) to match label images
            Map<SectionKey, List<Integer>> sections = groupByCpcSection(directory, channelName, cd);
            int totalMeasured = 0;

            for (Map.Entry<SectionKey, List<Integer>> se : sections.entrySet()) {
                SectionKey section = se.getKey();
                List<Integer> rowIndices = se.getValue();
                if (rowIndices.isEmpty()) continue;
                if (hasRequired3DData
                        && shouldSkipPair(SubAnalysis.SHAPE_FEATURES_3D, section, channelName,
                        "3D shape features")) {
                    continue;
                }

                int firstRow = rowIndices.get(0);
                String animalName = safeValue(cd, firstRow, "Animal Name");

                String labelFileName = section.labelFileName(channelName);
                ImagePlus labelImg = provider.get(channelName, section);
                if (labelImg == null) {
                    IJ.log("    Label image not found for " + animalName + "/" + channelName);
                    continue;
                }

                try {
                    // Apply calibration so _UNIT measurements return µm
                    if (cal != null && cal.isCalibrated()) {
                        Calibration ijCal = new Calibration(labelImg);
                        ijCal.pixelWidth = cal.pixelWidth;
                        ijCal.pixelHeight = cal.pixelHeight;
                        ijCal.pixelDepth = cal.pixelDepth;
                        ijCal.setUnit(cal.unit);
                        labelImg.setCalibration(ijCal);
                    }

                    mcib3d.image3d.ImageHandler labelIH =
                            mcib3d.image3d.ImageHandler.wrap(labelImg);
                    mcib3d.geom2.Objects3DIntPopulation population =
                            new mcib3d.geom2.Objects3DIntPopulation(labelIH);

                    // Build label → Object3DInt lookup
                    Map<Integer, mcib3d.geom2.Object3DInt> objMap =
                            new LinkedHashMap<Integer, mcib3d.geom2.Object3DInt>();
                    for (mcib3d.geom2.Object3DInt obj : population.getObjects3DInt()) {
                        objMap.put((int) obj.getLabel(), obj);
                    }

                    boolean hasLabelCol = cd.colIdx.containsKey("Label");

                    // Also need Volume and Surface for composite RI computation
                    String volCol = findVolumeColumn(cd);
                    String surfCol = findSurfaceColumn(cd);

                    for (int rowIdx : rowIndices) {
                        int label = resolveRowLabel(cd, rowIdx, hasLabelCol, labelImg);
                        mcib3d.geom2.Object3DInt obj = objMap.get(label);
                        if (obj == null) continue;

                        // Raw features
                        mcib3d.geom2.measurements.MeasureCompactness mComp =
                                new mcib3d.geom2.measurements.MeasureCompactness(obj);
                        mcib3d.geom2.measurements.MeasureEllipsoid mEll =
                                new mcib3d.geom2.measurements.MeasureEllipsoid(obj);
                        mcib3d.geom2.measurements.MeasureFeret mFer =
                                new mcib3d.geom2.measurements.MeasureFeret(obj);
                        mcib3d.geom2.measurements.MeasureMoments mMom =
                                new mcib3d.geom2.measurements.MeasureMoments(obj);
                        mcib3d.geom2.measurements.MeasureDistancesCenter mDist =
                                new mcib3d.geom2.measurements.MeasureDistancesCenter(obj);

                        double sphericity = safeM(mComp, mcib3d.geom2.measurements.MeasureCompactness.SPHER_CORRECTED);
                        double compactness = safeM(mComp, mcib3d.geom2.measurements.MeasureCompactness.COMP_CORRECTED);
                        double elongation = safeM(mEll, mcib3d.geom2.measurements.MeasureEllipsoid.ELL_ELONGATION);
                        double flatness = safeM(mEll, mcib3d.geom2.measurements.MeasureEllipsoid.ELL_FLATNESS);
                        double spareness = safeM(mEll, mcib3d.geom2.measurements.MeasureEllipsoid.ELL_SPARENESS);
                        double majorRadius = safeM(mEll, mcib3d.geom2.measurements.MeasureEllipsoid.ELL_MAJOR_RADIUS_UNIT);
                        double feret3D = safeM(mFer, mcib3d.geom2.measurements.MeasureFeret.FERET_UNIT);
                        double dcMin = safeM(mDist, mcib3d.geom2.measurements.MeasureDistancesCenter.DIST_CENTER_MIN_UNIT);
                        double dcMax = safeM(mDist, mcib3d.geom2.measurements.MeasureDistancesCenter.DIST_CENTER_MAX_UNIT);
                        double dcMean = safeM(mDist, mcib3d.geom2.measurements.MeasureDistancesCenter.DIST_CENTER_AVG_UNIT);
                        double dcSD = safeM(mDist, mcib3d.geom2.measurements.MeasureDistancesCenter.DIST_CENTER_SD_UNIT);

                        cd.set(rowIdx, "Morph_Sphericity", formatStat(sphericity));
                        cd.set(rowIdx, "Morph_Compactness", formatStat(compactness));
                        cd.set(rowIdx, "Morph_Elongation", formatStat(elongation));
                        cd.set(rowIdx, "Morph_Flatness", formatStat(flatness));
                        cd.set(rowIdx, "Morph_Spareness", formatStat(spareness));
                        cd.set(rowIdx, "Morph_MajorRadius_um", formatStat(majorRadius));
                        cd.set(rowIdx, "Morph_Feret3D_um", formatStat(feret3D));
                        for (int m = 1; m <= 5; m++) {
                            double moment = safeM(mMom, "Moment" + m);
                            cd.set(rowIdx, "Morph_Moment" + m, formatStat(moment));
                        }
                        cd.set(rowIdx, "Morph_DistCenter_Min_um", formatStat(dcMin));
                        cd.set(rowIdx, "Morph_DistCenter_Max_um", formatStat(dcMax));
                        cd.set(rowIdx, "Morph_DistCenter_Mean_um", formatStat(dcMean));
                        cd.set(rowIdx, "Morph_DistCenter_SD_um", formatStat(dcSD));

                        if (doComposites) {
                            // RI = 1/Sphericity (surface-based ramification index)
                            double ri = (sphericity > 0 && Double.isFinite(sphericity))
                                    ? 1.0 / sphericity : Double.NaN;
                            cd.set(rowIdx, "Morph_RI", formatStat(ri));

                            // SRI = CV of centroid-to-surface distances
                            double sri = safeDivide(dcSD, dcMean);
                            cd.set(rowIdx, "Morph_SRI", formatStat(sri));

                            // PB = 1 - Spareness (process burden)
                            double pb = Double.isFinite(spareness) ? 1.0 - spareness : Double.NaN;
                            cd.set(rowIdx, "Morph_PB", formatStat(pb));

                            // MP = morphological polarity
                            double mp = Double.NaN;
                            if (Double.isFinite(elongation) && Double.isFinite(flatness)) {
                                double denom = elongation + flatness - 2.0 + 0.001;
                                mp = (elongation - 1.0) / denom;
                            }
                            cd.set(rowIdx, "Morph_MP", formatStat(mp));

                            // VSD = log10(Feret^3 / Volume)
                            double vol = parseDoubleOr(cd, rowIdx, volCol, Double.NaN);
                            double vsd = Double.NaN;
                            if (feret3D > 0 && vol > 0 && Double.isFinite(feret3D) && Double.isFinite(vol)) {
                                vsd = Math.log10(feret3D * feret3D * feret3D / vol);
                            }
                            cd.set(rowIdx, "Morph_VSD", formatStat(vsd));
                        }

                        totalMeasured++;
                    }

                    IJ.log("    " + animalName + "/" + labelFileName + ": "
                            + objMap.size() + " objects measured (3D morphometry)");
                } catch (Exception e) {
                    IJ.log("    3D morphometry failed for " + labelFileName
                            + ": " + e.getClass().getSimpleName() + " - " + e.getMessage());
                } finally {
                    closeLabelImage(labelImg, provider, channelName, section);
                }
            }

            IJ.log("  " + channelName + ": " + totalMeasured + " objects with 3D morphometry");
        }
    }

    private void runObjectTexture(String directory,
                                  SpatialExecutionContext context,
                                  LabelImageProvider provider) {
        if (context == null
                || (!context.doObjectGLCM && !context.doObjectFractal
                && !context.doObjectTextureClass && !context.doNative3DTexture)) {
            return;
        }
        IJ.log("  object texture and complexity: selected outputs will run unless complete columns are reused.");
        File imageAnalysisRoot = objectImageOutputReadRoot(directory);
        if (!imageAnalysisRoot.isDirectory()) {
            IJ.log("  Object image-output directory not found. Cannot extract object texture.");
            return;
        }

        RawTextureImageResolver rawResolver;
        try {
            rawResolver = new RawTextureImageResolver(directory, context.channelNames);
        } catch (Exception e) {
            IJ.log("  Raw intensity images could not be opened for object texture: "
                    + e.getClass().getSimpleName() + " - " + e.getMessage());
            return;
        }

        int channelCounter = 1;
        int totalChannels = context.channels.size();
        for (Map.Entry<String, ChannelData> entry : context.channels.entrySet()) {
            String channelName = entry.getKey();
            ChannelData cd = entry.getValue();
            IJ.log("  [" + (channelCounter++) + "/" + totalChannels + "] Object texture: " + channelName);
            if (!isForceRerunActive() && hasAllSelectedTextureColumns(cd, context)) {
                IJ.log("  " + channelName + ": reusing existing object texture columns");
                continue;
            }

            addSelectedTextureColumns(cd, context);
            Map<SectionKey, List<Integer>> sections = groupByCpcSection(directory, channelName, cd);
            int totalObjectRows = countSectionRows(sections);
            IJ.log("  " + channelName + ": " + sections.size() + " sections, "
                    + totalObjectRows + " object rows queued for object texture");
            Map<Integer, ObjectTextureFeatures.FeatureVector> vectorsByRow =
                    context.doObjectTextureClass
                            ? collectTextureFeatureVectors(channelName, cd, sections, rawResolver, provider)
                            : null;
            Map<Integer, ObjectTextureFeatures3D.FeatureVector> vectors3DByRow =
                    context.doNative3DTexture
                            ? collectTextureFeatureVectors3D(channelName, cd, sections, rawResolver, provider,
                            context.calibration)
                            : null;
            double[][] centroids = null;
            if (context.doObjectTextureClass) {
                centroids = loadOrFitTextureCentroids(directory, channelName,
                        vectorsByRow == null
                                ? new ArrayList<ObjectTextureFeatures.FeatureVector>()
                                : new ArrayList<ObjectTextureFeatures.FeatureVector>(vectorsByRow.values()),
                        context.textureClassK);
            }
            double[][] centroids3D = null;
            if (context.doNative3DTexture) {
                centroids3D = loadOrFitTextureCentroids3D(directory, channelName,
                        vectors3DByRow == null
                                ? new ArrayList<ObjectTextureFeatures3D.FeatureVector>()
                                : new ArrayList<ObjectTextureFeatures3D.FeatureVector>(vectors3DByRow.values()),
                        context.textureClassK);
            }

            int totalMeasured = 0;
            int invalidGlcm = 0;
            int unreliableGlcm = 0;
            int invalidFractal = 0;
            int unreliableFractal = 0;
            int invalidClass = 0;
            int unreliableClass = 0;
            int invalidGlcm3D = 0;
            int unreliableGlcm3D = 0;
            int invalidClass3D = 0;
            int unreliableClass3D = 0;
            int skippedSingleSlice3D = 0;
            int sectionCounter = 0;
            int processedRows = 0;
            long measurementStart = System.nanoTime();

            for (Map.Entry<SectionKey, List<Integer>> se : sections.entrySet()) {
                SectionKey section = se.getKey();
                List<Integer> rowIndices = se.getValue();
                if (rowIndices.isEmpty()) continue;
                sectionCounter++;
                IJ.log("    Object texture measurement [" + sectionCounter + "/"
                        + sections.size() + "]: " + section.labelFileName(channelName)
                        + " (" + rowIndices.size() + " rows)");

                ImagePlus labelImg = provider.get(channelName, section);
                if (labelImg == null) {
                    IJ.log("    Label image not found: " + section.labelFileName(channelName));
                    continue;
                }
                ImagePlus rawStack = null;
                try {
                    rawStack = rawResolver.open(channelName, cd, rowIndices);
                    if (rawStack == null) {
                        IJ.log("    Raw intensity stack not found for " + channelName + " / " + section);
                        continue;
                    }
                    applyCalibration(rawStack, context.calibration);
                    Map<Integer, mcib3d.geom2.Object3DInt> objMap = loadObjectMap(labelImg);
                    boolean hasLabelCol = cd.colIdx.containsKey("Label");

                    for (int rowIdx : rowIndices) {
                        processedRows++;
                        if (shouldLogObjectTextureProgress(processedRows, totalObjectRows)) {
                            IJ.log("      Object texture measurement: " + processedRows
                                    + "/" + totalObjectRows + " rows processed ("
                                    + elapsedSeconds(measurementStart) + " s)");
                        }
                        int label = resolveRowLabel(cd, rowIdx, hasLabelCol, labelImg);
                        mcib3d.geom2.Object3DInt obj = objMap.get(Integer.valueOf(label));
                        if (obj == null) continue;

                        if (context.doObjectGLCM) {
                            AveragedGLCMResult glcm = computeAverageSliceGLCM(obj, labelImg, rawStack);
                            writeGLCM(cd, rowIdx, glcm);
                            if (!glcm.valid) invalidGlcm++;
                            else if (!glcm.reliable) unreliableGlcm++;
                        }
                        if (context.doObjectFractal) {
                            ObjectFractal.Result fractal =
                                    ObjectFractal.compute(ObjectPatchBuilder.buildMIP(obj, labelImg, rawStack));
                            writeFractal(cd, rowIdx, fractal);
                            if (!fractal.valid) invalidFractal++;
                            else if (!fractal.reliable) unreliableFractal++;
                        }
                        if (context.doObjectTextureClass) {
                            ObjectTextureFeatures.FeatureVector vector =
                                    vectorsByRow == null ? null : vectorsByRow.get(Integer.valueOf(rowIdx));
                            writeTextureClass(cd, rowIdx, vector, centroids);
                            if (vector == null || !vector.valid || centroids == null || centroids.length == 0) {
                                invalidClass++;
                            } else if (!vector.reliable) {
                                unreliableClass++;
                            }
                        }
                        if (context.doNative3DTexture) {
                            ObjectPatch3D patch3D = ObjectPatchBuilder.buildVolumetric(obj, rawStack);
                            if (patch3D == null) {
                                writeNaN(cd, rowIdx, MORPH_TEXTURE_GLCM3D_COLUMNS);
                                writeNaN(cd, rowIdx, MORPH_TEXTURE_CLASS3D_COLUMNS);
                                skippedSingleSlice3D++;
                            } else {
                                ObjectTextureGLCM3D.Result glcm3D = ObjectTextureGLCM3D.compute(patch3D);
                                writeGLCM3D(cd, rowIdx, glcm3D);
                                if (!glcm3D.valid) invalidGlcm3D++;
                                else if (!glcm3D.reliable) unreliableGlcm3D++;

                                ObjectTextureFeatures3D.FeatureVector vector3D =
                                        vectors3DByRow == null ? null : vectors3DByRow.get(Integer.valueOf(rowIdx));
                                if (vector3D == null) {
                                    vector3D = ObjectTextureFeatures3D.computeFeatures(patch3D);
                                }
                                writeTextureClass3D(cd, rowIdx, vector3D, centroids3D);
                                if (vector3D == null || !vector3D.valid
                                        || centroids3D == null || centroids3D.length == 0) {
                                    invalidClass3D++;
                                } else if (!vector3D.reliable) {
                                    unreliableClass3D++;
                                }
                            }
                        }
                        totalMeasured++;
                    }
                    IJ.log("    Object texture measurement complete for "
                            + section.labelFileName(channelName) + ": "
                            + totalMeasured + " objects measured so far");
                } catch (Exception e) {
                    IJ.log("    Object texture failed for " + section.labelFileName(channelName)
                            + ": " + e.getClass().getSimpleName() + " - " + e.getMessage());
                } finally {
                    closeImage(rawStack);
                    closeLabelImage(labelImg, provider, channelName, section);
                }
            }

            IJ.log("  " + channelName + ": " + totalMeasured + " objects with object texture");
            logTextureReliability(channelName, invalidGlcm, unreliableGlcm,
                    invalidFractal, unreliableFractal, invalidClass, unreliableClass);
            logTexture3DReliability(channelName, skippedSingleSlice3D,
                    invalidGlcm3D, unreliableGlcm3D, invalidClass3D, unreliableClass3D);
        }
    }

    private Map<Integer, ObjectTextureFeatures.FeatureVector> collectTextureFeatureVectors(
            String channelName,
            ChannelData cd,
            Map<SectionKey, List<Integer>> sections,
            RawTextureImageResolver rawResolver,
            LabelImageProvider provider) {
        Map<Integer, ObjectTextureFeatures.FeatureVector> vectors =
                new LinkedHashMap<Integer, ObjectTextureFeatures.FeatureVector>();
        int totalRows = countSectionRows(sections);
        int sectionCounter = 0;
        int processedRows = 0;
        long start = System.nanoTime();
        IJ.log("    Collecting texture-class features for " + channelName + ": "
                + totalRows + " rows across " + sections.size() + " sections");
        for (Map.Entry<SectionKey, List<Integer>> se : sections.entrySet()) {
            SectionKey section = se.getKey();
            List<Integer> rowIndices = se.getValue();
            if (rowIndices.isEmpty()) continue;
            sectionCounter++;
            IJ.log("      Texture-class feature collection [" + sectionCounter + "/"
                    + sections.size() + "]: " + section.labelFileName(channelName)
                    + " (" + rowIndices.size() + " rows)");

            ImagePlus labelImg = provider.get(channelName, section);
            if (labelImg == null) continue;
            ImagePlus rawStack = null;
            try {
                rawStack = rawResolver.open(channelName, cd, rowIndices);
                if (rawStack == null) continue;
                Map<Integer, mcib3d.geom2.Object3DInt> objMap = loadObjectMap(labelImg);
                boolean hasLabelCol = cd.colIdx.containsKey("Label");
                for (int rowIdx : rowIndices) {
                    processedRows++;
                    if (shouldLogObjectTextureProgress(processedRows, totalRows)) {
                        IJ.log("        Texture-class features: " + processedRows
                                + "/" + totalRows + " rows processed ("
                                + elapsedSeconds(start) + " s)");
                    }
                    int label = resolveRowLabel(cd, rowIdx, hasLabelCol, labelImg);
                    mcib3d.geom2.Object3DInt obj = objMap.get(Integer.valueOf(label));
                    if (obj == null) continue;
                    vectors.put(Integer.valueOf(rowIdx),
                            computeAverageSliceFeatures(obj, labelImg, rawStack));
                }
            } catch (Exception e) {
                IJ.log("    Texture feature collection failed for " + section.labelFileName(channelName)
                        + ": " + e.getClass().getSimpleName() + " - " + e.getMessage());
            } finally {
                closeImage(rawStack);
                closeLabelImage(labelImg, provider, channelName, section);
            }
        }
        IJ.log("    Texture-class feature collection complete for " + channelName
                + ": " + vectors.size() + "/" + totalRows + " rows with vectors ("
                + elapsedSeconds(start) + " s)");
        return vectors;
    }

    private Map<Integer, ObjectTextureFeatures3D.FeatureVector> collectTextureFeatureVectors3D(
            String channelName,
            ChannelData cd,
            Map<SectionKey, List<Integer>> sections,
            RawTextureImageResolver rawResolver,
            LabelImageProvider provider,
            CalibrationIO.PixelCalibration calibration) {
        Map<Integer, ObjectTextureFeatures3D.FeatureVector> vectors =
                new LinkedHashMap<Integer, ObjectTextureFeatures3D.FeatureVector>();
        int totalRows = countSectionRows(sections);
        int sectionCounter = 0;
        int processedRows = 0;
        long start = System.nanoTime();
        IJ.log("    Collecting native-3D texture-class features for " + channelName + ": "
                + totalRows + " rows across " + sections.size() + " sections");
        for (Map.Entry<SectionKey, List<Integer>> se : sections.entrySet()) {
            SectionKey section = se.getKey();
            List<Integer> rowIndices = se.getValue();
            if (rowIndices.isEmpty()) continue;
            sectionCounter++;
            IJ.log("      Native-3D texture feature collection [" + sectionCounter
                    + "/" + sections.size() + "]: "
                    + section.labelFileName(channelName) + " ("
                    + rowIndices.size() + " rows)");

            ImagePlus labelImg = provider.get(channelName, section);
            if (labelImg == null) continue;
            ImagePlus rawStack = null;
            try {
                rawStack = rawResolver.open(channelName, cd, rowIndices);
                if (rawStack == null) continue;
                applyCalibration(rawStack, calibration);
                Map<Integer, mcib3d.geom2.Object3DInt> objMap = loadObjectMap(labelImg);
                boolean hasLabelCol = cd.colIdx.containsKey("Label");
                for (int rowIdx : rowIndices) {
                    processedRows++;
                    if (shouldLogObjectTextureProgress(processedRows, totalRows)) {
                        IJ.log("        Native-3D texture features: " + processedRows
                                + "/" + totalRows + " rows processed ("
                                + elapsedSeconds(start) + " s)");
                    }
                    int label = resolveRowLabel(cd, rowIdx, hasLabelCol, labelImg);
                    mcib3d.geom2.Object3DInt obj = objMap.get(Integer.valueOf(label));
                    if (obj == null) continue;
                    ObjectPatch3D patch = ObjectPatchBuilder.buildVolumetric(obj, rawStack);
                    if (patch == null) continue;
                    vectors.put(Integer.valueOf(rowIdx),
                            ObjectTextureFeatures3D.computeFeatures(patch));
                }
            } catch (Exception e) {
                IJ.log("    Native-3D texture feature collection failed for "
                        + section.labelFileName(channelName)
                        + ": " + e.getClass().getSimpleName() + " - " + e.getMessage());
            } finally {
                closeImage(rawStack);
                closeLabelImage(labelImg, provider, channelName, section);
            }
        }
        IJ.log("    Native-3D texture feature collection complete for " + channelName
                + ": " + vectors.size() + "/" + totalRows + " rows with vectors ("
                + elapsedSeconds(start) + " s)");
        return vectors;
    }

    private static int countSectionRows(Map<SectionKey, List<Integer>> sections) {
        if (sections == null || sections.isEmpty()) return 0;
        int total = 0;
        for (List<Integer> rows : sections.values()) {
            if (rows != null) total += rows.size();
        }
        return total;
    }

    private static boolean shouldLogObjectTextureProgress(int processedRows, int totalRows) {
        if (processedRows <= 0 || totalRows <= 0) return false;
        return processedRows == 1 || processedRows == totalRows || processedRows % 100 == 0;
    }

    private static String elapsedSeconds(long startNanos) {
        double seconds = (System.nanoTime() - startNanos) / 1000000000.0;
        return String.format(Locale.ROOT, "%.1f", seconds);
    }

    private double[][] loadOrFitTextureCentroids(String directory,
                                                 String channelName,
                                                 List<ObjectTextureFeatures.FeatureVector> vectors,
                                                 int k) {
        File writeFile = textureCentroidsFile(directory, channelName);
        double[][] centroids = loadTextureCentroids(directory, channelName);
        if (centroids != null && centroids.length == k) {
            IJ.log("    Loaded object texture centroids: " + writeFile.getName());
            return centroids;
        }

        double[][] fitted = ObjectTextureFeatures.fitCentroids(vectors, k);
        if (fitted.length == 0) {
            IJ.log("    No valid texture feature vectors for " + channelName
                    + "; texture class columns will be NaN.");
            return fitted;
        }
        try {
            ObjectTextureFeatures.CentroidsIO.save(writeFile, fitted);
            IJ.log("    Fitted object texture centroids: " + writeFile.getName());
        } catch (IOException e) {
            IJ.log("    Could not save texture centroids for " + channelName + ": " + e.getMessage());
        }
        return fitted;
    }

    private double[][] loadOrFitTextureCentroids3D(String directory,
                                                   String channelName,
                                                   List<ObjectTextureFeatures3D.FeatureVector> vectors,
                                                   int k) {
        File writeFile = textureCentroids3DFile(directory, channelName);
        double[][] centroids = loadTextureCentroids3D(directory, channelName);
        if (centroids != null && centroids.length == k) {
            IJ.log("    Loaded native-3D object texture centroids: " + writeFile.getName());
            return centroids;
        }

        double[][] fitted = ObjectTextureFeatures3D.fitCentroids(vectors, k);
        if (fitted.length == 0) {
            IJ.log("    No valid native-3D texture feature vectors for " + channelName
                    + "; native-3D texture class columns will be NaN.");
            return fitted;
        }
        try {
            ObjectTextureFeatures3D.CentroidsIO.save(writeFile, fitted);
            IJ.log("    Fitted native-3D object texture centroids: " + writeFile.getName());
        } catch (IOException e) {
            IJ.log("    Could not save native-3D texture centroids for "
                    + channelName + ": " + e.getMessage());
        }
        return fitted;
    }

    private double[][] loadTextureCentroids(String directory, String channelName) {
        for (File candidate : textureCentroidReadFiles(directory, channelName)) {
            try {
                double[][] centroids = ObjectTextureFeatures.CentroidsIO.load(candidate,
                        ObjectTextureFeatures.DEFAULT_FEATURE_DIM);
                if (centroids != null) {
                    return centroids;
                }
            } catch (Exception e) {
                IJ.log("    Ignoring unreadable texture centroids "
                        + candidate.getName() + ": " + e.getMessage());
            }
        }
        return null;
    }

    private double[][] loadTextureCentroids3D(String directory, String channelName) {
        for (File candidate : textureCentroid3DReadFiles(directory, channelName)) {
            try {
                double[][] centroids = ObjectTextureFeatures3D.CentroidsIO.load(candidate,
                        ObjectTextureFeatures3D.DEFAULT_FEATURE_DIM);
                if (centroids != null) {
                    return centroids;
                }
            } catch (Exception e) {
                IJ.log("    Ignoring unreadable native-3D texture centroids "
                        + candidate.getName() + ": " + e.getMessage());
            }
        }
        return null;
    }

    private static File textureCentroidsFile(String directory, String channelName) {
        FlashProjectLayout layout = FlashProjectLayout.forDirectory(directory);
        return new File(layout.configurationWriteDir(), textureCentroidsFileName(channelName, ".txt"));
    }

    private static File textureCentroids3DFile(String directory, String channelName) {
        FlashProjectLayout layout = FlashProjectLayout.forDirectory(directory);
        return new File(layout.configurationWriteDir(), textureCentroids3DFileName(channelName, ".txt"));
    }

    private static List<File> textureCentroidReadFiles(String directory, String channelName) {
        FlashProjectLayout layout = FlashProjectLayout.forDirectory(directory);
        List<File> out = new ArrayList<File>();
        Set<String> seen = new LinkedHashSet<String>();
        addUniqueFile(out, seen, textureCentroidsFile(directory, channelName));
        for (File dir : layout.configurationReadDirs()) {
            addUniqueFile(out, seen, new File(dir, textureCentroidsFileName(channelName, ".txt")));
            addUniqueFile(out, seen, new File(dir, textureCentroidsFileName(channelName, ".bin")));
        }
        return out;
    }

    private static List<File> textureCentroid3DReadFiles(String directory, String channelName) {
        FlashProjectLayout layout = FlashProjectLayout.forDirectory(directory);
        List<File> out = new ArrayList<File>();
        Set<String> seen = new LinkedHashSet<String>();
        addUniqueFile(out, seen, textureCentroids3DFile(directory, channelName));
        for (File dir : layout.configurationReadDirs()) {
            addUniqueFile(out, seen, new File(dir, textureCentroids3DFileName(channelName, ".txt")));
            addUniqueFile(out, seen, new File(dir, textureCentroids3DFileName(channelName, ".bin")));
        }
        return out;
    }

    private static String textureCentroidsFileName(String channelName, String extension) {
        return "morph_texture_centroids_" + ChannelFilenameCodec.toSafe(channelName) + extension;
    }

    private static String textureCentroids3DFileName(String channelName, String extension) {
        return "morph_texture_centroids_3D_" + ChannelFilenameCodec.toSafe(channelName) + extension;
    }

    private static void addUniqueFile(List<File> out, Set<String> seen, File file) {
        if (file == null) return;
        String path = file.getAbsolutePath();
        if (seen.add(path)) {
            out.add(file);
        }
    }

    private boolean hasAllSelectedTextureColumns(ChannelData cd, SpatialExecutionContext context) {
        if (cd == null || context == null) return false;
        if (context.doObjectGLCM && !hasUsableColumns(cd, MORPH_TEXTURE_GLCM_COLUMNS)) return false;
        if (context.doObjectFractal && !hasUsableColumns(cd, MORPH_TEXTURE_FRACTAL_COLUMNS)) return false;
        if (context.doObjectTextureClass && !hasUsableColumns(cd, MORPH_TEXTURE_CLASS_COLUMNS)) return false;
        if (context.doNative3DTexture
                && (!hasUsableColumns(cd, MORPH_TEXTURE_GLCM3D_COLUMNS)
                || !hasUsableColumns(cd, MORPH_TEXTURE_CLASS3D_COLUMNS))) {
            return false;
        }
        return context.doObjectGLCM || context.doObjectFractal
                || context.doObjectTextureClass || context.doNative3DTexture;
    }

    private void addSelectedTextureColumns(ChannelData cd, SpatialExecutionContext context) {
        if (context.doObjectGLCM) {
            for (String column : MORPH_TEXTURE_GLCM_COLUMNS) cd.addColumn(column);
        }
        if (context.doObjectFractal) {
            for (String column : MORPH_TEXTURE_FRACTAL_COLUMNS) cd.addColumn(column);
        }
        if (context.doObjectTextureClass) {
            for (String column : MORPH_TEXTURE_CLASS_COLUMNS) cd.addColumn(column);
        }
        if (context.doNative3DTexture) {
            for (String column : MORPH_TEXTURE_GLCM3D_COLUMNS) cd.addColumn(column);
            for (String column : MORPH_TEXTURE_CLASS3D_COLUMNS) cd.addColumn(column);
        }
    }

    private Map<Integer, mcib3d.geom2.Object3DInt> loadObjectMap(ImagePlus labelImg) {
        mcib3d.image3d.ImageHandler labelIH = mcib3d.image3d.ImageHandler.wrap(labelImg);
        mcib3d.geom2.Objects3DIntPopulation population =
                new mcib3d.geom2.Objects3DIntPopulation(labelIH);
        Map<Integer, mcib3d.geom2.Object3DInt> objMap =
                new LinkedHashMap<Integer, mcib3d.geom2.Object3DInt>();
        for (mcib3d.geom2.Object3DInt obj : population.getObjects3DInt()) {
            objMap.put(Integer.valueOf((int) obj.getLabel()), obj);
        }
        return objMap;
    }

    private AveragedGLCMResult computeAverageSliceGLCM(mcib3d.geom2.Object3DInt obj,
                                                       ImagePlus labelImg,
                                                       ImagePlus rawStack) {
        int zMin = ObjectPatchBuilder.zMin(obj, labelImg);
        int zMax = ObjectPatchBuilder.zMax(obj, labelImg);
        double contrast = 0.0;
        double asm = 0.0;
        double correlation = 0.0;
        double entropy = 0.0;
        double homogeneity = 0.0;
        int validSlices = 0;
        boolean reliable = true;
        for (int z = zMin; z <= zMax; z++) {
            ObjectPatch patch = ObjectPatchBuilder.buildSlice(obj, labelImg, rawStack, z);
            if (patch.objectPixelCount() == 0) continue;
            ObjectTextureGLCM.Result result = ObjectTextureGLCM.compute(patch);
            if (!result.valid) {
                reliable = false;
                continue;
            }
            contrast += finiteOrZero(result.contrast);
            asm += finiteOrZero(result.asm);
            correlation += finiteOrZero(result.correlation);
            entropy += finiteOrZero(result.entropy);
            homogeneity += finiteOrZero(result.homogeneity);
            reliable = reliable && result.reliable;
            validSlices++;
        }
        if (validSlices == 0) {
            return AveragedGLCMResult.invalid();
        }
        double n = validSlices;
        return new AveragedGLCMResult(contrast / n, asm / n, correlation / n,
                entropy / n, homogeneity / n, true, reliable);
    }

    private ObjectTextureFeatures.FeatureVector computeAverageSliceFeatures(mcib3d.geom2.Object3DInt obj,
                                                                            ImagePlus labelImg,
                                                                            ImagePlus rawStack) {
        int zMin = ObjectPatchBuilder.zMin(obj, labelImg);
        int zMax = ObjectPatchBuilder.zMax(obj, labelImg);
        float[] sums = new float[ObjectTextureFeatures.DEFAULT_FEATURE_DIM];
        int validSlices = 0;
        boolean reliable = true;
        for (int z = zMin; z <= zMax; z++) {
            ObjectPatch patch = ObjectPatchBuilder.buildSlice(obj, labelImg, rawStack, z);
            if (patch.objectPixelCount() == 0) continue;
            ObjectTextureFeatures.FeatureVector vector = ObjectTextureFeatures.computeFeatures(patch);
            if (!vector.valid || vector.features == null
                    || vector.features.length != ObjectTextureFeatures.DEFAULT_FEATURE_DIM) {
                reliable = false;
                continue;
            }
            for (int i = 0; i < sums.length; i++) {
                sums[i] += finiteOrZero(vector.features[i]);
            }
            reliable = reliable && vector.reliable;
            validSlices++;
        }
        if (validSlices == 0) {
            return new ObjectTextureFeatures.FeatureVector(
                    new float[ObjectTextureFeatures.DEFAULT_FEATURE_DIM], false, false);
        }
        for (int i = 0; i < sums.length; i++) {
            sums[i] /= validSlices;
        }
        return new ObjectTextureFeatures.FeatureVector(sums, true, reliable);
    }

    private void writeGLCM(ChannelData cd, int rowIdx, AveragedGLCMResult result) {
        if (result == null || !result.valid) {
            writeNaN(cd, rowIdx, MORPH_TEXTURE_GLCM_COLUMNS);
            return;
        }
        cd.set(rowIdx, "MorphTexture_GLCMContrast", formatStat(result.contrast));
        cd.set(rowIdx, "MorphTexture_GLCMASM", formatStat(result.asm));
        cd.set(rowIdx, "MorphTexture_GLCMCorrelation", formatStat(result.correlation));
        cd.set(rowIdx, "MorphTexture_GLCMEntropy", formatStat(result.entropy));
        cd.set(rowIdx, "MorphTexture_GLCMHomogeneity", formatStat(result.homogeneity));
    }

    private void writeGLCM3D(ChannelData cd, int rowIdx, ObjectTextureGLCM3D.Result result) {
        if (result == null || !result.valid) {
            writeNaN(cd, rowIdx, MORPH_TEXTURE_GLCM3D_COLUMNS);
            return;
        }
        cd.set(rowIdx, "MorphTexture_GLCM3DContrast", formatStat(result.contrast));
        cd.set(rowIdx, "MorphTexture_GLCM3DASM", formatStat(result.asm));
        cd.set(rowIdx, "MorphTexture_GLCM3DCorrelation", formatStat(result.correlation));
        cd.set(rowIdx, "MorphTexture_GLCM3DEntropy", formatStat(result.entropy));
        cd.set(rowIdx, "MorphTexture_GLCM3DHomogeneity", formatStat(result.homogeneity));
    }

    private void writeFractal(ChannelData cd, int rowIdx, ObjectFractal.Result result) {
        if (result == null || !result.valid) {
            writeNaN(cd, rowIdx, MORPH_TEXTURE_FRACTAL_COLUMNS);
            return;
        }
        cd.set(rowIdx, "MorphTexture_FractalDim", formatStat(finiteOrZero(result.fractalDim)));
        cd.set(rowIdx, "MorphTexture_FractalDim_R2", formatStat(finiteOrZero(result.fractalDim_R2)));
        cd.set(rowIdx, "MorphTexture_LacunarityMean", formatStat(finiteOrZero(result.lacunarityMean)));
        cd.set(rowIdx, "MorphTexture_LacunaritySpread", formatStat(finiteOrZero(result.lacunaritySpread)));
    }

    private void writeTextureClass(ChannelData cd,
                                   int rowIdx,
                                   ObjectTextureFeatures.FeatureVector vector,
                                   double[][] centroids) {
        if (vector == null || !vector.valid || vector.features == null
                || vector.features.length != ObjectTextureFeatures.DEFAULT_FEATURE_DIM
                || centroids == null || centroids.length == 0) {
            writeNaN(cd, rowIdx, MORPH_TEXTURE_CLASS_COLUMNS);
            return;
        }
        ObjectTextureFeatures.ClassAssignment assignment =
                ObjectTextureFeatures.assignToCentroids(vector, centroids);
        cd.set(rowIdx, "MorphTexture_ClassLabel", String.valueOf(assignment.classLabel));
        cd.set(rowIdx, "MorphTexture_ClassDistance",
                formatStat(vector.reliable ? assignment.classDistance : Double.POSITIVE_INFINITY));
        for (int i = 0; i < vector.features.length; i++) {
            cd.set(rowIdx, "MorphTexture_F" + (i + 1), formatStat(finiteOrZero(vector.features[i])));
        }
    }

    private void writeTextureClass3D(ChannelData cd,
                                     int rowIdx,
                                     ObjectTextureFeatures3D.FeatureVector vector,
                                     double[][] centroids) {
        if (vector == null || !vector.valid || vector.features == null
                || vector.features.length != ObjectTextureFeatures3D.DEFAULT_FEATURE_DIM
                || centroids == null || centroids.length == 0) {
            writeNaN(cd, rowIdx, MORPH_TEXTURE_CLASS3D_COLUMNS);
            return;
        }
        ObjectTextureFeatures3D.ClassAssignment assignment =
                ObjectTextureFeatures3D.assignToCentroids(vector, centroids);
        cd.set(rowIdx, "MorphTexture_Class3DLabel", String.valueOf(assignment.classLabel));
        cd.set(rowIdx, "MorphTexture_Class3DDistance",
                formatStat(vector.reliable ? assignment.classDistance : Double.POSITIVE_INFINITY));
        for (int i = 0; i < vector.features.length; i++) {
            cd.set(rowIdx, "MorphTexture_F3D" + (i + 1), formatStat(finiteOrZero(vector.features[i])));
        }
    }

    private void writeNaN(ChannelData cd, int rowIdx, String[] columns) {
        for (String column : columns) {
            cd.set(rowIdx, column, "NaN");
        }
    }

    private void logTextureReliability(String channelName,
                                       int invalidGlcm,
                                       int unreliableGlcm,
                                       int invalidFractal,
                                       int unreliableFractal,
                                       int invalidClass,
                                       int unreliableClass) {
        if (invalidGlcm + unreliableGlcm + invalidFractal + unreliableFractal
                + invalidClass + unreliableClass == 0) {
            return;
        }
        IJ.log("    " + channelName + " texture quality flags: "
                + "GLCM invalid=" + invalidGlcm + ", GLCM unreliable=" + unreliableGlcm
                + ", fractal invalid=" + invalidFractal + ", fractal unreliable=" + unreliableFractal
                + ", class invalid=" + invalidClass + ", class unreliable=" + unreliableClass);
    }

    private void logTexture3DReliability(String channelName,
                                         int skippedSingleSlice,
                                         int invalidGlcm3D,
                                         int unreliableGlcm3D,
                                         int invalidClass3D,
                                         int unreliableClass3D) {
        if (skippedSingleSlice > 0) {
            IJ.log("    " + channelName + ": skipped " + skippedSingleSlice
                    + " single-slice objects for native-3D texture");
        }
        if (invalidGlcm3D + unreliableGlcm3D + invalidClass3D + unreliableClass3D == 0) {
            return;
        }
        IJ.log("    " + channelName + " native-3D texture quality flags: "
                + "GLCM3D invalid=" + invalidGlcm3D + ", GLCM3D unreliable=" + unreliableGlcm3D
                + ", class3D invalid=" + invalidClass3D + ", class3D unreliable=" + unreliableClass3D);
    }

    private static double finiteOrZero(double value) {
        return Double.isNaN(value) || Double.isInfinite(value) ? 0.0 : value;
    }

    private static void applyCalibration(ImagePlus image, CalibrationIO.PixelCalibration cal) {
        if (image == null || cal == null || !cal.isCalibrated()) {
            return;
        }
        Calibration ijCal = new Calibration(image);
        ijCal.pixelWidth = cal.pixelWidth;
        ijCal.pixelHeight = cal.pixelHeight;
        ijCal.pixelDepth = cal.pixelDepth;
        ijCal.setUnit(cal.unit);
        image.setCalibration(ijCal);
    }

    private static void closeImage(ImagePlus image) {
        if (image == null) return;
        image.changes = false;
        image.close();
        image.flush();
    }

    private static final class AveragedGLCMResult {
        final double contrast;
        final double asm;
        final double correlation;
        final double entropy;
        final double homogeneity;
        final boolean valid;
        final boolean reliable;

        private AveragedGLCMResult(double contrast,
                                   double asm,
                                   double correlation,
                                   double entropy,
                                   double homogeneity,
                                   boolean valid,
                                   boolean reliable) {
            this.contrast = contrast;
            this.asm = asm;
            this.correlation = correlation;
            this.entropy = entropy;
            this.homogeneity = homogeneity;
            this.valid = valid;
            this.reliable = reliable;
        }

        static AveragedGLCMResult invalid() {
            return new AveragedGLCMResult(Double.NaN, Double.NaN, Double.NaN,
                    Double.NaN, Double.NaN, false, false);
        }
    }

    private static final class RawTextureImageResolver {
        private final DeferredImageSupplier supplier;
        private final List<String> channelNames;
        private final ChannelIdentities identities;

        private RawTextureImageResolver(String directory, List<String> channelNames) throws Exception {
            this.supplier = ImageSourceDispatcher.createSupplier(directory);
            this.channelNames = channelNames == null
                    ? new ArrayList<String>()
                    : new ArrayList<String>(channelNames);
            this.identities = ChannelConfigIO.readChannelIdentities(
                    FlashProjectLayout.forDirectory(directory).configurationWriteDir());
        }

        ImagePlus open(String channelName, ChannelData cd, List<Integer> rowIndices) throws Exception {
            int seriesIndex = seriesIndex(cd, rowIndices);
            int channelIndex = channelIndex(channelName);
            return supplier.openSeriesMaterializedChannel(seriesIndex, channelIndex);
        }

        private int seriesIndex(ChannelData cd, List<Integer> rowIndices) {
            if (cd == null || rowIndices == null || rowIndices.isEmpty()) {
                return 0;
            }
            Integer scn = parseScnInteger(cell(cd, rowIndices.get(0).intValue(), "SCN"));
            return scn == null ? 0 : Math.max(0, scn.intValue() - 1);
        }

        private int channelIndex(String channelName) {
            if (identities != null) {
                for (ChannelIdentities.Entry entry : identities.getEntries()) {
                    String marker = entry.getMarkerId();
                    if (channelNameMatches(channelName, marker)) {
                        return Math.max(0, entry.getChannelIndex());
                    }
                }
            }
            for (int i = 0; i < channelNames.size(); i++) {
                if (channelNameMatches(channelName, channelNames.get(i))) {
                    return i;
                }
            }
            return 0;
        }

        private boolean channelNameMatches(String channelName, String candidate) {
            if (channelName == null || candidate == null) return false;
            return channelName.equals(candidate)
                    || channelName.equals(ChannelFilenameCodec.toRaw(candidate))
                    || ChannelFilenameCodec.toSafe(channelName).equals(candidate);
        }

        private static String cell(ChannelData cd, int row, String column) {
            if (cd == null || column == null || !cd.colIdx.containsKey(column)) return "";
            String value = cd.get(row, column);
            return value == null ? "" : value.trim();
        }
    }

    /** Safe extraction of a measurement value from an mcib3d Measure object. */
    private static double safeM(mcib3d.geom2.measurements.MeasureAbstract measure, String name) {
        try {
            Double v = measure.getValueMeasurement(name);
            return (v != null && Double.isFinite(v)) ? v : Double.NaN;
        } catch (Exception e) {
            return Double.NaN;
        }
    }

    /** Safe division returning NaN when denominator is near zero or inputs are non-finite. */
    private static double safeDivide(double num, double den) {
        if (den < 1e-10 || !Double.isFinite(num) || !Double.isFinite(den)) return Double.NaN;
        double r = num / den;
        return Double.isFinite(r) ? r : Double.NaN;
    }

    /** Parse a double from a ChannelData cell, returning fallback on failure. */
    private static double parseDoubleOr(ChannelData cd, int row, String col, double fallback) {
        if (col == null || !cd.colIdx.containsKey(col)) return fallback;
        String raw = cd.get(row, col);
        if (raw == null || raw.isEmpty()) return fallback;
        try {
            double v = Double.parseDouble(raw.trim());
            return Double.isFinite(v) ? v : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** Find the Volume column by checking common naming patterns. */
    private static String findVolumeColumn(ChannelData cd) {
        for (String h : cd.header) {
            if (h.startsWith("Volume") && h.contains("^3")) return h;
        }
        return null;
    }

    /** Find the Surface column by checking common naming patterns. */
    private static String findSurfaceColumn(ChannelData cd) {
        for (String h : cd.header) {
            if (h.startsWith("Surface") && h.contains("^2")) return h;
        }
        return null;
    }

    // ── Population Morphometric Scoring ──────────────────────────────

    /**
     * Computes population-normalised composite morphometric indices:
     * CMS (Composite Morphological Score), SMSD (Shape Moment Signature Distance),
     * IMDI (Intensity-Morphology Dissociation Index), and MDS (Morphological Diversity).
     */
    private void runPopulationMorphometrics(String directory, Map<String, ChannelData> channels) {
        logArtifactRunPlan("Population morphometric scoring", SubAnalysis.POPULATION_MORPHO);
        File morphometryDir = spatialMorphometryOutputDir(directory);
        try {
            IoUtils.mustMkdirs(morphometryDir);
        } catch (IOException e) {
            IJ.log("[FLASH] Could not create Morphometry directory: " + e.getMessage()
                    + " — skipping population morphometrics.");
            return;
        }

        int channelCounter = 1;
        int totalChannels = channels.size();
        for (Map.Entry<String, ChannelData> entry : channels.entrySet()) {
            String channelName = entry.getKey();
            ChannelData cd = entry.getValue();
            IJ.log("  [" + (channelCounter++) + "/" + totalChannels + "] Population Morphometrics: " + channelName);
            if (shouldSkipChannel(SubAnalysis.POPULATION_MORPHO, channelName,
                    "population morphometrics")) {
                continue;
            }

            // Verify all prerequisite columns exist
            String[] popRequired = {"Morph_Sphericity", "Morph_PB", "Morph_SRI",
                    "Morph_Moment1", "Morph_Moment2", "Morph_Moment3",
                    "Morph_Moment4", "Morph_Moment5", "Mean"};
            boolean popMissing = false;
            for (String req : popRequired) {
                if (!cd.colIdx.containsKey(req)) {
                    IJ.log("  " + channelName + ": Missing column " + req
                            + ". Run 3D shape features + composites first. Skipping.");
                    popMissing = true;
                    break;
                }
            }
            if (popMissing) continue;

            int n = cd.rows.size();
            if (n < 3) {
                IJ.log("  " + channelName + ": Too few objects (" + n + ") for population scoring.");
                continue;
            }

            // Collect raw values for normalization
            double[] sphVals = collectColumn(cd, "Morph_Sphericity");
            double[] pbVals = collectColumn(cd, "Morph_PB");
            double[] sriVals = collectColumn(cd, "Morph_SRI");
            String volCol = findVolumeColumn(cd);
            double[] volVals = volCol != null ? collectColumn(cd, volCol) : new double[n];
            double[] intVals = collectColumn(cd, "Mean");
            double[][] momentVals = new double[5][n];
            for (int m = 0; m < 5; m++) {
                momentVals[m] = collectColumn(cd, "Morph_Moment" + (m + 1));
            }

            // Percentile 2-98 normalization bounds
            double[] sphBounds = percentileBounds(sphVals);
            double[] pbBounds = percentileBounds(pbVals);
            double[] sriBounds = percentileBounds(sriVals);
            double[] volBounds = percentileBounds(volVals);

            // Population mean and SD for moments and intensity
            double[] momentMean = new double[5];
            double[] momentSD = new double[5];
            for (int m = 0; m < 5; m++) {
                momentMean[m] = nanMean(momentVals[m]);
                momentSD[m] = nanSD(momentVals[m], momentMean[m]);
            }
            double intMean = nanMean(intVals);
            double intSD = nanSD(intVals, intMean);

            // Add population composite columns
            cd.addColumn("Morph_CMS");
            cd.addColumn("Morph_SMSD");
            cd.addColumn("Morph_IMDI");

            // First pass: compute CMS per object
            double[] cmsVals = new double[n];
            for (int i = 0; i < n; i++) {
                double sphNorm = normalize(1.0 - sphVals[i], 1.0 - sphBounds[1], 1.0 - sphBounds[0]);
                double pbNorm = normalize(pbVals[i], pbBounds[0], pbBounds[1]);
                double sriNorm = normalize(sriVals[i], sriBounds[0], sriBounds[1]);
                double volNorm = normalize(volVals[i], volBounds[0], volBounds[1]);

                double cms = 0.35 * sphNorm + 0.30 * pbNorm + 0.20 * sriNorm + 0.15 * volNorm;
                cms = Math.max(0.0, Math.min(1.0, cms));
                cmsVals[i] = cms;
                cd.set(i, "Morph_CMS", formatStat(cms));
            }

            // SMSD: Mahalanobis-like distance in 5D moment space
            for (int i = 0; i < n; i++) {
                double sumSq = 0;
                int validDims = 0;
                for (int m = 0; m < 5; m++) {
                    if (momentSD[m] > 1e-10 && Double.isFinite(momentVals[m][i])) {
                        double diff = (momentVals[m][i] - momentMean[m]) / momentSD[m];
                        sumSq += diff * diff;
                        validDims++;
                    }
                }
                double smsd = validDims > 0 ? Math.sqrt(sumSq) : Double.NaN;
                cd.set(i, "Morph_SMSD", formatStat(smsd));
            }

            // IMDI: |z_intensity - z_CMS|
            double cmsMean = nanMean(cmsVals);
            double cmsSD = nanSD(cmsVals, cmsMean);
            for (int i = 0; i < n; i++) {
                double zInt = (intSD > 1e-10 && Double.isFinite(intVals[i]))
                        ? (intVals[i] - intMean) / intSD : Double.NaN;
                double zCms = (cmsSD > 1e-10 && Double.isFinite(cmsVals[i]))
                        ? (cmsVals[i] - cmsMean) / cmsSD : Double.NaN;
                double imdi = (Double.isFinite(zInt) && Double.isFinite(zCms))
                        ? Math.abs(zInt - zCms) : Double.NaN;
                cd.set(i, "Morph_IMDI", formatStat(imdi));
            }

            // Population summary
            double mdsEntropy = computeMDSEntropy(cd);
            writeMorphometryPopSummary(morphometryDir, channelName, cd, mdsEntropy);

            IJ.log("  " + channelName + ": population morphometric scoring complete"
                    + (Double.isFinite(mdsEntropy) ? " (MDS entropy=" + formatStat(mdsEntropy) + ")" : ""));
        }
    }

    // ── Spatial-Morphometric Analysis ────────────────────────────────

    /**
     * Computes spatial-morphometric features: TDR, FEV, and PPRP.
     */
    private void runSpatialMorphometrics(String directory, Map<String, ChannelData> channels,
                                          CalibrationIO.PixelCalibration cal) {
        logArtifactRunPlan("Spatial-morphometric analysis", SubAnalysis.SPATIAL_MORPHO);
        File morphometryDir = spatialMorphometryOutputDir(directory);
        try {
            IoUtils.mustMkdirs(morphometryDir);
        } catch (IOException e) {
            IJ.log("[FLASH] Could not create Morphometry directory: " + e.getMessage()
                    + " — skipping spatial morphometrics.");
            return;
        }

        List<String> channelNames = new ArrayList<String>(channels.keySet());

        int channelCounter = 1;
        int totalChannels = channels.size();
        for (Map.Entry<String, ChannelData> entry : channels.entrySet()) {
            String channelName = entry.getKey();
            ChannelData cd = entry.getValue();
            IJ.log("  [" + (channelCounter++) + "/" + totalChannels + "] Spatial Morphometrics: " + channelName);
            if (shouldSkipChannel(SubAnalysis.SPATIAL_MORPHO, channelName,
                    "spatial morphometrics")) {
                continue;
            }
            int n = cd.rows.size();

            // TDR: Territorial Dominance Ratio
            if (cd.colIdx.containsKey("Voronoi_TerritoryArea_um2")) {
                cd.addColumn("Morph_TDR");
                double totalArea = 0;
                int validCount = 0;
                for (int i = 0; i < n; i++) {
                    double t = parseDoubleOr(cd, i, "Voronoi_TerritoryArea_um2", Double.NaN);
                    if (Double.isFinite(t) && t > 0) {
                        totalArea += t;
                        validCount++;
                    }
                }
                if (validCount > 0 && totalArea > 0) {
                    for (int i = 0; i < n; i++) {
                        double t = parseDoubleOr(cd, i, "Voronoi_TerritoryArea_um2", Double.NaN);
                        double tdr = (Double.isFinite(t) && t > 0)
                                ? (t * validCount / totalArea) : Double.NaN;
                        cd.set(i, "Morph_TDR", formatStat(tdr));
                    }
                    IJ.log("  " + channelName + ": TDR computed (" + validCount + " objects)");
                }
            }

            // FEV: Feret Eccentricity Vector (magnitude only — angle needs eigenvectors)
            if (cd.colIdx.containsKey("Morph_Feret3D_um") && cd.colIdx.containsKey("Morph_MajorRadius_um")) {
                cd.addColumn("Morph_FEV_Mag");
                for (int i = 0; i < n; i++) {
                    double feret = parseDoubleOr(cd, i, "Morph_Feret3D_um", Double.NaN);
                    double majorR = parseDoubleOr(cd, i, "Morph_MajorRadius_um", Double.NaN);
                    double fevMag = (majorR > 0 && Double.isFinite(feret) && Double.isFinite(majorR))
                            ? feret / (2.0 * majorR) : Double.NaN;
                    cd.set(i, "Morph_FEV_Mag", formatStat(fevMag));
                }
            }

            // PPRP: Pathology Proximity Response Profile
            if (cd.colIdx.containsKey("Morph_CMS")) {
                for (String otherChannel : channelNames) {
                    if (otherChannel.equals(channelName)) continue;
                    String distCol = channelName + "_DistToClosest_" + otherChannel;
                    if (!cd.colIdx.containsKey(distCol)) continue;

                    double[] dists = collectColumn(cd, distCol);
                    double[] cms = collectColumn(cd, "Morph_CMS");

                    // Linear regression: CMS = a + b * distance
                    double sumX = 0, sumY = 0, sumXY = 0, sumXX = 0;
                    int count = 0;
                    for (int i = 0; i < n; i++) {
                        if (Double.isFinite(dists[i]) && Double.isFinite(cms[i])) {
                            sumX += dists[i];
                            sumY += cms[i];
                            sumXY += dists[i] * cms[i];
                            sumXX += dists[i] * dists[i];
                            count++;
                        }
                    }

                    if (count >= 10) {
                        double meanX = sumX / count;
                        double meanY = sumY / count;
                        double sxx = sumXX - count * meanX * meanX;
                        double sxy = sumXY - count * meanX * meanY;
                        double gradient = sxx > 1e-10 ? sxy / sxx : 0;

                        // R-squared
                        double ssRes = 0, ssTot = 0;
                        for (int i = 0; i < n; i++) {
                            if (Double.isFinite(dists[i]) && Double.isFinite(cms[i])
                                    && !Double.isInfinite(dists[i])) {
                                double predicted = meanY + gradient * (dists[i] - meanX);
                                ssRes += (cms[i] - predicted) * (cms[i] - predicted);
                                ssTot += (cms[i] - meanY) * (cms[i] - meanY);
                            }
                        }
                        double rSquared = ssTot > 1e-10 ? 1.0 - ssRes / ssTot : 0;

                        // Write PPRP summary CSV (append mode)
                        writePprpRow(morphometryDir, channelName, otherChannel,
                                cd, gradient, rSquared, count);

                        IJ.log("  PPRP " + channelName + " vs " + otherChannel
                                + ": gradient=" + formatStat(gradient)
                                + " R²=" + formatStat(rSquared)
                                + " (n=" + count + ")");
                    }
                }
            }
        }
    }

    // ── Morphometry helpers ──────────────────────────────────────────

    /** Collect all values of a column as a double array (NaN for missing/invalid). */
    private static double[] collectColumn(ChannelData cd, String colName) {
        int n = cd.rows.size();
        double[] vals = new double[n];
        if (!cd.colIdx.containsKey(colName)) {
            Arrays.fill(vals, Double.NaN);
            return vals;
        }
        for (int i = 0; i < n; i++) {
            vals[i] = parseDoubleOr(cd, i, colName, Double.NaN);
        }
        return vals;
    }

    /**
     * Compute percentile 2 and 98 bounds for normalization, ignoring NaN.
     * Uses R-7 linear interpolation (numpy/Excel default) so small samples
     * don't snap to discrete order statistics — the prior int-truncation
     * placed both bounds on the same value for n &lt; 50 and degenerated
     * the [lo,hi] window for tiny populations.
     */
    private static double[] percentileBounds(double[] vals) {
        List<Double> valid = new ArrayList<Double>();
        for (double v : vals) {
            if (Double.isFinite(v)) valid.add(v);
        }
        if (valid.size() < 2) return new double[]{0, 1};
        java.util.Collections.sort(valid);
        double lo = r7Percentile(valid, 0.02);
        double hi = r7Percentile(valid, 0.98);
        if (hi <= lo) hi = lo + 1.0;
        return new double[]{lo, hi};
    }

    /** R-7 percentile (linear interpolation between order statistics) on a sorted list. */
    private static double r7Percentile(List<Double> sorted, double p) {
        int n = sorted.size();
        if (n == 0) return Double.NaN;
        if (n == 1) return sorted.get(0);
        double idx = (n - 1) * p;
        int lo = (int) Math.floor(idx);
        int hi = Math.min(lo + 1, n - 1);
        double frac = idx - lo;
        return sorted.get(lo) + frac * (sorted.get(hi) - sorted.get(lo));
    }

    /** Min-max normalize a value to 0-1 given bounds. */
    private static double normalize(double val, double lo, double hi) {
        if (!Double.isFinite(val)) return Double.NaN;
        if (hi <= lo) return 0.5;
        return Math.max(0.0, Math.min(1.0, (val - lo) / (hi - lo)));
    }

    /** Mean of an array ignoring NaN. */
    private static double nanMean(double[] vals) {
        double sum = 0;
        int count = 0;
        for (double v : vals) {
            if (Double.isFinite(v)) { sum += v; count++; }
        }
        return count > 0 ? sum / count : Double.NaN;
    }

    /**
     * Sample SD of an array ignoring NaN, given precomputed mean.
     * Uses Bessel's correction (n-1) since per-image populations are
     * subsamples of a larger biological cohort and downstream z-scoring
     * (Morph_SMSD, Morph_IMDI) needs an unbiased estimator. Returns NaN
     * when fewer than 2 finite values are present.
     */
    private static double nanSD(double[] vals, double mean) {
        if (!Double.isFinite(mean)) return Double.NaN;
        double sumSq = 0;
        int count = 0;
        for (double v : vals) {
            if (Double.isFinite(v)) {
                double d = v - mean;
                sumSq += d * d;
                count++;
            }
        }
        return count >= 2 ? Math.sqrt(sumSq / (count - 1)) : Double.NaN;
    }

    /** Compute MDS entropy from the Cluster column if present. */
    private double computeMDSEntropy(ChannelData cd) {
        if (!cd.colIdx.containsKey("Cluster")) return Double.NaN;
        Map<String, Integer> counts = new LinkedHashMap<String, Integer>();
        int total = 0;
        for (int i = 0; i < cd.rows.size(); i++) {
            String cluster = cd.get(i, "Cluster");
            if (cluster == null || cluster.trim().isEmpty()) continue;
            Integer c = counts.get(cluster);
            counts.put(cluster, c != null ? c + 1 : 1);
            total++;
        }
        if (total < 2 || counts.size() < 2) return 0.0;
        double entropy = 0;
        for (int c : counts.values()) {
            double p = (double) c / total;
            if (p > 0) entropy -= p * Math.log(p) / Math.log(2);
        }
        return entropy;
    }

    /** Write population morphometry summary CSV. */
    private void writeMorphometryPopSummary(File dir, String channelName, ChannelData cd,
                                             double mdsEntropy) {
        String[] features = {"Morph_Sphericity", "Morph_Compactness", "Morph_Elongation",
                "Morph_Flatness", "Morph_Spareness", "Morph_Feret3D_um", "Morph_RI",
                "Morph_SRI", "Morph_PB", "Morph_MP", "Morph_VSD", "Morph_CMS",
                "Morph_SMSD", "Morph_IMDI"};

        List<String> header = Arrays.asList("Feature", "Mean", "SD", "Min", "Max", "N");
        List<List<String>> rows = new ArrayList<List<String>>();

        for (String feat : features) {
            if (!cd.colIdx.containsKey(feat)) continue;
            double[] vals = collectColumn(cd, feat);
            double mean = nanMean(vals);
            double sd = nanSD(vals, mean);
            double min = Double.MAX_VALUE;
            double max = -Double.MAX_VALUE;
            int count = 0;
            for (double v : vals) {
                if (Double.isFinite(v)) {
                    if (v < min) min = v;
                    if (v > max) max = v;
                    count++;
                }
            }
            if (count == 0) { min = Double.NaN; max = Double.NaN; }
            List<String> row = new ArrayList<String>();
            row.add(feat);
            row.add(formatStat(mean));
            row.add(formatStat(sd));
            row.add(formatStat(min));
            row.add(formatStat(max));
            row.add(String.valueOf(count));
            rows.add(row);
        }

        if (Double.isFinite(mdsEntropy)) {
            List<String> row = new ArrayList<String>();
            row.add("MDS_Entropy");
            row.add(formatStat(mdsEntropy));
            row.add(""); row.add(""); row.add("");
            row.add(String.valueOf(cd.rows.size()));
            rows.add(row);
        }

        File outFile = new File(dir, ChannelFilenameCodec.toSafe(channelName) + "_PopulationSummary.csv");
        writeCsv(outFile, header, rows);
    }

    /** Write a single PPRP row to the summary CSV (creates file with header if first row). */
    private void writePprpRow(File dir, String channelA, String channelB,
                               ChannelData cd, double gradient, double rSquared, int nObjects) {
        // Group PPRP rows by image section
        Map<String, List<Integer>> sections = new LinkedHashMap<String, List<Integer>>();
        for (int i = 0; i < cd.rows.size(); i++) {
            String key = safeValue(cd, i, "Animal Name") + "|"
                    + safeValue(cd, i, "Hemisphere") + "|" + safeValue(cd, i, "Region");
            List<Integer> list = sections.get(key);
            if (list == null) { list = new ArrayList<Integer>(); sections.put(key, list); }
            list.add(i);
        }

        String safeName = ChannelFilenameCodec.toSafe(channelA) + "_vs_"
                + ChannelFilenameCodec.toSafe(channelB) + "_PPRP.csv";
        File outFile = new File(dir, safeName);

        List<String> header = Arrays.asList("Animal Name", "Hemisphere", "Region",
                "PPRP_Gradient", "PPRP_R2", "N_Objects");
        List<List<String>> rows = new ArrayList<List<String>>();

        // Use first row of first section for metadata (overall summary)
        if (!cd.rows.isEmpty()) {
            List<String> row = new ArrayList<String>();
            row.add(safeValue(cd, 0, "Animal Name"));
            row.add(safeValue(cd, 0, "Hemisphere"));
            row.add(safeValue(cd, 0, "Region"));
            row.add(formatStat(gradient));
            row.add(formatStat(rSquared));
            row.add(String.valueOf(nObjects));
            rows.add(row);
        }

        writeCsv(outFile, header, rows);
    }

    // ── CPC summary CSVs ───────────────────────────────────────────

    /**
     * Write CPC summary CSVs to the spatial output folder:
     * - CPC_Spatial_Summary.csv (pairwise per-section)
     * - CPC_Multi_Target_Summary.csv (pattern counts per-section)
     */
    private void writeCpcSummaries(String directory, Map<String, ChannelData> channels, List<String> channelNames) {
        if (channelNames.size() < 2) return;

        // Check if CPC columns exist
        ChannelData first = channels.get(channelNames.get(0));
        if (!first.colIdx.containsKey(channelNames.get(0) + "_CPCColoc_" + channelNames.get(1))) return;

        File spatialDir = spatialDataOutputDir(directory);
        if (!spatialDir.exists() && !spatialDir.mkdirs()) return;

        IJ.log("--- Writing CPC summary CSVs ---");
        Map<String, Map<SectionKey, List<Integer>>> sectionsByChannel =
                buildCpcSectionGroups(directory, channels, channelNames, "summary");

        // Pairwise summary
        List<String> pairHeader = Arrays.asList(
                "Animal Name", "Hemisphere", "Region", "ROI", "Source", "vs",
                "Objects", "CPC Colocalized", "CPC %", "CPC Contains", "CPC Contains %");
        List<List<String>> pairRows = new ArrayList<List<String>>();

        for (int a = 0; a < channelNames.size(); a++) {
            for (int b = 0; b < channelNames.size(); b++) {
                if (a == b) continue;
                String nameA = channelNames.get(a);
                String nameB = channelNames.get(b);
                ChannelData cd = channels.get(nameA);
                String colocCol = nameA + "_CPCColoc_" + nameB;
                String containsCol = nameA + "_CPCContains_" + nameB;
                if (!cd.colIdx.containsKey(colocCol)) continue;

                // Group by section
                Map<SectionKey, List<Integer>> sections = sectionsByChannel.get(nameA);
                for (Map.Entry<SectionKey, List<Integer>> entry : sections.entrySet()) {
                    List<Integer> rows = entry.getValue();
                    if (rows.isEmpty()) continue;
                    int firstRow = rows.get(0);
                    int total = rows.size();
                    int colocCount = 0;
                    int containsCount = 0;
                    for (int r : rows) {
                        if ("1".equals(cd.get(r, colocCol))) colocCount++;
                        double cc = cd.getDouble(r, containsCol);
                        if (cc > 0) containsCount++;
                    }
                    double colocPct = total > 0 ? (colocCount * 100.0 / total) : 0;
                    double containsPct = total > 0 ? (containsCount * 100.0 / total) : 0;

                    List<String> row = new ArrayList<String>();
                    row.add(safeValue(cd, firstRow, "Animal Name"));
                    row.add(safeValue(cd, firstRow, "Hemisphere"));
                    row.add(safeValue(cd, firstRow, "Region"));
                    row.add(safeValue(cd, firstRow, "ROI"));
                    row.add(nameA);
                    row.add(nameB);
                    row.add(String.valueOf(total));
                    row.add(String.valueOf(colocCount));
                    row.add(formatNumber(colocPct));
                    row.add(String.valueOf(containsCount));
                    row.add(formatNumber(containsPct));
                    pairRows.add(row);
                }
            }
        }

        if (!pairRows.isEmpty()) {
            writeCsv(new File(spatialDir, "CPC_Spatial_Summary.csv"), pairHeader, pairRows);
            IJ.log("  Written: CPC_Spatial_Summary.csv (" + pairRows.size() + " rows)");
        }

        // Multi-target summary
        List<String> multiHeader = Arrays.asList(
                "Animal Name", "Hemisphere", "Region", "ROI", "Source", "Pattern", "Count", "%");
        List<List<String>> multiRows = new ArrayList<List<String>>();

        for (String ch : channelNames) {
            ChannelData cd = channels.get(ch);
            if (!cd.colIdx.containsKey(ch + "_CPCPattern")) continue;

            Map<SectionKey, List<Integer>> sections = sectionsByChannel.get(ch);
            for (Map.Entry<SectionKey, List<Integer>> entry : sections.entrySet()) {
                List<Integer> rows = entry.getValue();
                if (rows.isEmpty()) continue;
                int firstRow = rows.get(0);
                int total = rows.size();

                // Count patterns
                Map<String, Integer> patternCounts = new LinkedHashMap<String, Integer>();
                for (int r : rows) {
                    String pattern = cd.get(r, ch + "_CPCPattern");
                    if (pattern == null || pattern.isEmpty()) pattern = "None";
                    Integer count = patternCounts.get(pattern);
                    patternCounts.put(pattern, (count != null ? count : 0) + 1);
                }

                for (Map.Entry<String, Integer> pe : patternCounts.entrySet()) {
                    double pct = total > 0 ? (pe.getValue() * 100.0 / total) : 0;
                    List<String> row = new ArrayList<String>();
                    row.add(safeValue(cd, firstRow, "Animal Name"));
                    row.add(safeValue(cd, firstRow, "Hemisphere"));
                    row.add(safeValue(cd, firstRow, "Region"));
                    row.add(safeValue(cd, firstRow, "ROI"));
                    row.add(ch);
                    row.add(pe.getKey());
                    row.add(String.valueOf(pe.getValue()));
                    row.add(formatNumber(pct));
                    multiRows.add(row);
                }
            }
        }

        if (!multiRows.isEmpty()) {
            writeCsv(new File(spatialDir, "CPC_Multi_Target_Summary.csv"), multiHeader, multiRows);
            IJ.log("  Written: CPC_Multi_Target_Summary.csv (" + multiRows.size() + " rows)");
        }
    }

    private void applySpatialConfigToDialog(SpatialAnalysisWizard.DerivedConfig config,
                                            List<String> channelNames,
                                            SpatialDialogBindings bindings,
                                            String selectedPresetName) {
        if (config == null || bindings == null) {
            return;
        }
        bindings.programmaticChange = true;
        try {
            if (bindings.presetCombo != null) {
                bindings.presetCombo.setSelectedItem(
                        selectedPresetName == null ? SPATIAL_PRESET_PLACEHOLDER : selectedPresetName);
            }
            setToggle(bindings.forceRerunToggle, config.forceRerun);
            setToggle(bindings.doDistancesToggle, config.doDistances);
            setToggle(bindings.lineDistanceToggle, config.doLineDistance);
            setToggle(bindings.doSpatialStatsToggle, config.doSpatialStats);
            if (!bindings.lockVolColocFromObjectAnalysis) {
                setToggle(bindings.doVolColocToggle, config.doVolColoc);
            }
            if (!bindings.lockCpcFromObjectAnalysis) {
                setToggle(bindings.doCpcToggle, config.doCpc);
            }
            setToggle(bindings.doVoronoiToggle, config.doVoronoi);
            setToggle(bindings.doHeatmapsToggle, config.doHeatmaps);
            setToggle(bindings.doPhenotypingToggle, config.doPhenotyping);
            setToggle(bindings.do2DMorphologyToggle, config.do2DMorphology);
            setToggle(bindings.do3DMorphologyToggle, config.do3DMorphology);
            setToggle(bindings.doCompositeIndicesToggle, config.doCompositeIndices);
            setToggle(bindings.doPopMorphometricsToggle, config.doPopMorphometrics);
            setToggle(bindings.doSpatialMorphometricsToggle, config.doSpatialMorphometrics);
            setToggle(bindings.doObjectGLCMToggle, config.doObjectGLCM);
            setToggle(bindings.doObjectFractalToggle, config.doObjectFractal);
            setToggle(bindings.doObjectTextureClassToggle, config.doObjectTextureClass);
            setToggle(bindings.doNative3DTextureToggle, config.doNative3DTexture);

            if (bindings.kdeBandwidthField != null) {
                bindings.kdeBandwidthField.setText(numericText(config.kdeBandwidth, 1));
            }
            if (bindings.textureClassKField != null) {
                bindings.textureClassKField.setText(numericText(
                        clampObjectTextureClassK(config.textureClassK), 0));
                bindings.textureClassKField.setEnabled(config.doObjectTextureClass);
            }
            if (bindings.heatmapLutChoice != null && config.heatmapLut != null) {
                bindings.heatmapLutChoice.setSelectedItem(config.heatmapLut);
            }
            if (bindings.clusterKField != null) {
                bindings.clusterKField.setText(numericText(config.clusterK, 0));
            }
            if (bindings.thresholdFields != null && !bindings.lockVolColocFromObjectAnalysis) {
                for (int i = 0; i < bindings.thresholdFields.size(); i++) {
                    JTextField field = bindings.thresholdFields.get(i);
                    if (field != null) {
                        String channelName = channelNames != null && i < channelNames.size()
                                ? channelNames.get(i)
                                : null;
                        Double threshold = channelName == null ? null : config.markerThresholds.get(channelName);
                        field.setText(numericText(threshold == null
                                ? config.colocThresholdPercent
                                : threshold.doubleValue(), 0));
                    }
                }
            }
        } finally {
            bindings.programmaticChange = false;
        }
        applyLockedColocalizationControls(bindings);
        updateVolumetricThresholdEnablement(bindings);
        updateMorphometricDependencyControls(bindings);
    }

    private static void setToggle(ToggleSwitch toggle, boolean selected) {
        if (toggle != null) {
            toggle.setSelected(selected);
        }
    }

    private static void updateVolumetricThresholdEnablement(SpatialDialogBindings bindings) {
        if (bindings == null || bindings.thresholdFields == null) {
            return;
        }
        boolean on = !bindings.lockVolColocFromObjectAnalysis
                && bindings.doVolColocToggle != null
                && bindings.doVolColocToggle.isSelected();
        for (JTextField field : bindings.thresholdFields) {
            if (field != null) {
                field.setEnabled(on);
            }
        }
    }

    private static void applyLockedColocalizationControls(SpatialDialogBindings bindings) {
        if (bindings == null) {
            return;
        }
        if (bindings.lockVolColocFromObjectAnalysis && bindings.doVolColocToggle != null) {
            bindings.doVolColocToggle.setSelected(true);
            bindings.doVolColocToggle.setEnabled(false);
        }
        if (bindings.lockCpcFromObjectAnalysis && bindings.doCpcToggle != null) {
            bindings.doCpcToggle.setSelected(true);
            bindings.doCpcToggle.setEnabled(false);
        }
    }

    private void addMorphometricControls(PipelineDialog opts,
                                         final SpatialDialogBindings spatialBindings,
                                         boolean doMorphology,
                                         boolean do3DShapeFeatures,
                                         boolean doCompositeIndices,
                                         boolean doPopMorphometrics,
                                         boolean doSpatialMorphometrics) {
        addMorphometricControls(opts, spatialBindings, doMorphology, do3DShapeFeatures,
                doCompositeIndices, doPopMorphometrics, doSpatialMorphometrics, null, null);
    }

    private void addMorphometricControls(PipelineDialog opts,
                                         final SpatialDialogBindings spatialBindings,
                                         boolean doMorphology,
                                         boolean do3DShapeFeatures,
                                         boolean doCompositeIndices,
                                         boolean doPopMorphometrics,
                                         boolean doSpatialMorphometrics,
                                         SpatialObjectDataAvailability detectedData,
                                         SpatialArtifactStatus artifactStatus) {
        opts.addSetupHelpHeader("Morphometric Analysis", SpatialHelpCatalog.MORPHOMETRY);
        if (detectedData != null) {
            opts.addHelpText(detectedData.morphometryHelperText());
        }
        spatialBindings.do2DMorphologyToggle = opts.addToggle(
                decorateArtifactLabel("Extract 2D morphology from label images", artifactStatus,
                        SubAnalysis.MORPHOLOGY_2D),
                defaultForArtifactStatus(artifactStatus, SubAnalysis.MORPHOLOGY_2D, doMorphology));
        opts.addHelpText("Loads saved object label images and extracts 2D shape features "
                + "(area, circularity, solidity, Feret diameter, etc.).");
        final ToggleSwitch raw3DToggle = opts.addToggle(
                decorateArtifactLabel("3D shape features", artifactStatus, SubAnalysis.SHAPE_FEATURES_3D),
                defaultForArtifactStatus(artifactStatus, SubAnalysis.SHAPE_FEATURES_3D, do3DShapeFeatures));
        spatialBindings.do3DMorphologyToggle = raw3DToggle;
        opts.addHelpText("Extracts per-object 3D shape descriptors from label images using "
                + "mcib3d: sphericity, compactness, elongation, flatness, spareness, 3D Feret, "
                + "3D moments, and centroid-to-surface distance statistics.");
        final ToggleSwitch complexToggle = opts.addToggle("Complex shape analysis", doCompositeIndices);
        spatialBindings.doCompositeIndicesToggle = complexToggle;
        opts.addHelpText("Derives composite indices from 3D features: "
                + "Ramification Index (RI), Surface Roughness (SRI), "
                + "Process Burden (PB), Morphological Polarity (MP), "
                + "Volume-Span Discrepancy (VSD). Requires 3D shape features.");
        opts.beginAdvancedSection("spatial");
        final ToggleSwitch popToggle = opts.addToggle(
                decorateArtifactLabel("Population morphometric scoring", artifactStatus,
                        SubAnalysis.POPULATION_MORPHO),
                defaultForArtifactStatus(artifactStatus, SubAnalysis.POPULATION_MORPHO, doPopMorphometrics));
        spatialBindings.doPopMorphometricsToggle = popToggle;
        opts.addHelpText("Population-normalised composites: "
                + "Composite Morphological Score (CMS), Shape Moment Signature Distance (SMSD), "
                + "Intensity-Morphology Dissociation Index (IMDI), Morphological Diversity Score. "
                + "Requires complex shape analysis.");
        final ToggleSwitch spatialMorphToggle = opts.addToggle(
                decorateArtifactLabel("Spatial-morphometric analysis", artifactStatus,
                        SubAnalysis.SPATIAL_MORPHO),
                defaultForArtifactStatus(artifactStatus, SubAnalysis.SPATIAL_MORPHO, doSpatialMorphometrics));
        spatialBindings.doSpatialMorphometricsToggle = spatialMorphToggle;
        opts.addHelpText("Territorial Dominance Ratio (TDR), Feret Eccentricity Vector (FEV), "
                + "Pathology Proximity Response Profile (PPRP). "
                + "Requires distances and/or Voronoi + 3D shape features.");
        opts.endAdvancedSection();

        raw3DToggle.addChangeListener(new Runnable() {
            @Override
            public void run() {
                updateMorphometricDependencyControls(spatialBindings);
            }
        });

        complexToggle.addChangeListener(new Runnable() {
            @Override
            public void run() {
                updateMorphometricDependencyControls(spatialBindings);
            }
        });
    }

    private void addObjectTextureControls(PipelineDialog opts,
                                          final SpatialDialogBindings spatialBindings,
                                          boolean doObjectGLCM,
                                          boolean doObjectFractal,
                                          boolean doObjectTextureClass,
                                          boolean doNative3DTexture,
                                          int textureClassK) {
        opts.addHeader("Object Texture and Complexity");
        spatialBindings.doObjectGLCMToggle = opts.addToggle("Object texture (GLCM; slow)", doObjectGLCM);
        spatialBindings.doObjectFractalToggle = opts.addToggle(
                "Object complexity (fractal + lacunarity; slow)", doObjectFractal);
        final ToggleSwitch textureClassToggle = opts.addToggle(
                "Object texture classes (slow)", doObjectTextureClass);
        spatialBindings.doObjectTextureClassToggle = textureClassToggle;
        opts.beginAdvancedSection("spatial.texture.advanced");
        spatialBindings.doNative3DTextureToggle = opts.addToggle(
                "Native-3D texture (GLCM + texture classes; very slow)", doNative3DTexture);
        spatialBindings.textureClassKField = opts.addNumericField(
                "Texture classes (k)", clampObjectTextureClassK(textureClassK), 0);
        opts.endAdvancedSection();
        spatialBindings.textureClassKField.setEnabled(textureClassToggle.isSelected());
        textureClassToggle.addChangeListener(new Runnable() {
            @Override
            public void run() {
                if (spatialBindings.textureClassKField != null) {
                    spatialBindings.textureClassKField.setEnabled(textureClassToggle.isSelected());
                }
            }
        });
    }

    private void addAdvancedPhenotypingAndHeatmapControls(PipelineDialog opts,
                                                          SpatialDialogBindings spatialBindings,
                                                          boolean doPhenotyping,
                                                          int clusterK,
                                                          boolean doHeatmaps,
                                                          double heatmapBandwidth,
                                                          String heatmapLut) {
        addAdvancedPhenotypingAndHeatmapControls(opts, spatialBindings,
                doPhenotyping, clusterK, doHeatmaps, heatmapBandwidth, heatmapLut, null);
    }

    private void addAdvancedPhenotypingAndHeatmapControls(PipelineDialog opts,
                                                          SpatialDialogBindings spatialBindings,
                                                          boolean doPhenotyping,
                                                          int clusterK,
                                                          boolean doHeatmaps,
                                                          double heatmapBandwidth,
                                                          String heatmapLut,
                                                          SpatialArtifactStatus artifactStatus) {
        boolean phenotypingDefault = defaultForArtifactStatus(artifactStatus,
                SubAnalysis.PHENOTYPING, doPhenotyping);
        boolean heatmapsDefault = defaultForArtifactStatus(artifactStatus,
                SubAnalysis.DENSITY_HEATMAPS, doHeatmaps);

        opts.beginCollapsibleSection("Cell Phenotyping",
                spatialDisclosureOpenByDefault(phenotypingDefault),
                SpatialHelpCatalog.PHENOTYPING);
        spatialBindings.doPhenotypingToggle = opts.addToggle(
                decorateArtifactLabel("K-means clustering", artifactStatus, SubAnalysis.PHENOTYPING),
                phenotypingDefault);
        opts.addHelpText("Clusters objects by multi-channel feature profile "
                + "(volume, intensity, colocalization). Auto-detects optimal k via silhouette score.");
        spatialBindings.clusterKField = opts.addNumericField("Clusters (k, 0=auto)", clusterK, 0);
        opts.addHelpText("Number of clusters. 0 auto-detects optimal k (2-10).");
        opts.endCollapsibleSection();

        opts.beginCollapsibleSection("Density Heatmaps",
                spatialDisclosureOpenByDefault(heatmapsDefault),
                SpatialHelpCatalog.HEATMAPS);
        spatialBindings.doHeatmapsToggle = opts.addToggle(
                decorateArtifactLabel("Generate density heatmaps", artifactStatus,
                        SubAnalysis.DENSITY_HEATMAPS),
                heatmapsDefault);
        opts.addHelpText("Gaussian KDE density maps per channel. "
                + "Saved as TIFF + PNG to FLASH/Results/Analysis Images/Spatial Heatmaps/.");
        spatialBindings.kdeBandwidthField = opts.addNumericField("KDE bandwidth (um, 0=auto)", heatmapBandwidth, 1);
        opts.addHelpText("Kernel bandwidth in microns. 0 uses Scott's rule automatically.");
        String[] lutOptions = {"Fire", "Grays", "Cyan", "Green", "Magenta", "Red"};
        spatialBindings.heatmapLutChoice = opts.addChoice("Heatmap LUT", lutOptions, heatmapLut);
        opts.endCollapsibleSection();
    }

    private static boolean spatialDisclosureOpenByDefault(boolean selectedByDefault) {
        return selectedByDefault
                || ij.Prefs.get("flash.advanced.global", false)
                || ij.Prefs.get("flash.advanced.spatial", false);
    }

    private static int clampObjectTextureClassK(int value) {
        return Math.max(2, Math.min(10, value));
    }

    private static void updateMorphometricDependencyControls(SpatialDialogBindings bindings) {
        if (bindings == null) {
            return;
        }
        boolean raw3DOn = bindings.do3DMorphologyToggle != null
                && bindings.do3DMorphologyToggle.isSelected();
        if (bindings.doCompositeIndicesToggle != null) {
            bindings.doCompositeIndicesToggle.setEnabled(raw3DOn);
            if (!raw3DOn) {
                bindings.doCompositeIndicesToggle.setSelected(false);
            }
        }
        if (bindings.doSpatialMorphometricsToggle != null) {
            bindings.doSpatialMorphometricsToggle.setEnabled(raw3DOn);
            if (!raw3DOn) {
                bindings.doSpatialMorphometricsToggle.setSelected(false);
            }
        }
        boolean complexOn = bindings.doCompositeIndicesToggle != null
                && bindings.doCompositeIndicesToggle.isEnabled()
                && bindings.doCompositeIndicesToggle.isSelected();
        if (bindings.doPopMorphometricsToggle != null) {
            bindings.doPopMorphometricsToggle.setEnabled(complexOn);
            if (!complexOn) {
                bindings.doPopMorphometricsToggle.setSelected(false);
            }
        }
    }

    private static String numericText(double value, int decimals) {
        if (decimals <= 0) {
            return String.valueOf((int) Math.round(value));
        }
        return String.format(Locale.US, "%." + decimals + "f", value);
    }

    private void addSpatialSetupControls(final PipelineDialog dialog,
                                         final String directory,
                                         final SpatialDialogBindings bindings,
                                         final SpatialConfigApplier applier) {
        final JComboBox<String> presetCombo = new JComboBox<String>(listSpatialPresetNames(directory));
        presetCombo.setMaximumSize(new Dimension(260, 24));
        if (bindings != null) {
            bindings.presetCombo = presetCombo;
        }
        JButton savePreset = new JButton("Save as preset...");
        flash.pipeline.ui.FlashIcons.apply(savePreset, flash.pipeline.ui.FlashIcons.save());
        savePreset.setToolTipText("Save the current Spatial Analysis options as a named preset.");
        savePreset.addActionListener(e -> handleSaveSpatialPreset(directory, bindings));
        JPanel row = SetupHelperButton.createHeaderRow("Spatial & Morphometry", presetCombo, savePreset,
                new SetupHelperButton.WizardLauncher() {
                    @Override public void run() {
                        final SpatialAnalysisWizard.DerivedConfig[] selected =
                                new SpatialAnalysisWizard.DerivedConfig[1];
                        dialog.runChildWorkflow(new Runnable() {
                            @Override public void run() {
                                selected[0] = runSpatialSetupHelper(directory);
                            }
                        });
                        if (selected[0] != null && applier != null) {
                            applier.apply(null, selected[0]);
                        }
                    }
                });
        presetCombo.addActionListener(e -> {
            if (bindings != null && bindings.programmaticChange) {
                return;
            }
            Object selected = presetCombo.getSelectedItem();
            if (selected != null && !SPATIAL_PRESET_PLACEHOLDER.equals(String.valueOf(selected))) {
                SpatialAnalysisWizard.DerivedConfig derived =
                        loadSpatialPresetConfig(directory, String.valueOf(selected));
                if (derived != null && applier != null) {
                    applier.apply(String.valueOf(selected), derived);
                }
            }
        });
        dialog.addComponent(row);
    }

    private void handleSaveSpatialPreset(String directory, SpatialDialogBindings bindings) {
        if (!canShowGuiDecisionDialog(suppressDialogs, cliConfig, GraphicsEnvironment.isHeadless())) return;
        if (bindings == null) {
            IJ.showMessage("Spatial Analysis", "Could not save preset: dialog options are not available.");
            return;
        }
        String name = JOptionPane.showInputDialog(
                bindings.presetCombo,
                "Preset name:",
                "Save Spatial Preset",
                JOptionPane.PLAIN_MESSAGE);
        if (name == null) {
            return;
        }
        String trimmed = name.trim();
        if (trimmed.isEmpty()) {
            IJ.showMessage("Spatial Analysis", "Preset name cannot be empty.");
            return;
        }

        try {
            SpatialPreset preset = buildSpatialPresetFromBindings(trimmed, bindings);
            new SpatialPresetIO(new File(directory)).save(preset);
            IJ.log("Saved Spatial preset: " + trimmed);
            refreshSpatialPresetChoice(directory, bindings, preset.getName());
        } catch (IOException e) {
            IJ.showMessage("Spatial Analysis", "Could not save preset: " + e.getMessage());
        } catch (IllegalArgumentException e) {
            IJ.showMessage("Spatial Analysis", "Could not save preset: " + e.getMessage());
        }
    }

    static boolean canShowGuiDecisionDialog(boolean suppressDialogs,
                                            CLIConfig cliConfig,
                                            boolean runtimeHeadless) {
        return !suppressDialogs && cliConfig == null && !runtimeHeadless;
    }

    private SpatialPreset buildSpatialPresetFromBindings(String name, SpatialDialogBindings bindings) {
        return new SpatialPreset(
                name,
                "Saved from Spatial Analysis dialog",
                SpatialPreset.CURRENT_LIBRARY_VERSION,
                isSelected(bindings.doDistancesToggle),
                isSelected(bindings.doSpatialStatsToggle),
                isSelected(bindings.doVolColocToggle),
                isSelected(bindings.doCpcToggle),
                isSelected(bindings.doVoronoiToggle),
                isSelected(bindings.doHeatmapsToggle),
                isSelected(bindings.doPhenotypingToggle),
                isSelected(bindings.do2DMorphologyToggle),
                isSelected(bindings.do3DMorphologyToggle),
                isSelected(bindings.doCompositeIndicesToggle),
                isSelected(bindings.doPopMorphometricsToggle),
                isSelected(bindings.doSpatialMorphometricsToggle),
                isSelected(bindings.doObjectGLCMToggle),
                isSelected(bindings.doObjectFractalToggle),
                isSelected(bindings.doObjectTextureClassToggle),
                isSelected(bindings.doNative3DTextureToggle),
                clampObjectTextureClassK((int) Math.round(readNumericField(
                        bindings.textureClassKField, ObjectTextureFeatures.DEFAULT_K,
                        "Texture classes (k)"))),
                readNumericField(bindings.kdeBandwidthField, 0.0, "KDE bandwidth"),
                selectedText(bindings.heatmapLutChoice, DEFAULT_HEATMAP_LUT),
                (int) Math.round(readNumericField(bindings.clusterKField, 0.0, "Clusters (k)")),
                readFirstThreshold(bindings.thresholdFields));
    }

    private void refreshSpatialPresetChoice(String directory,
                                            SpatialDialogBindings bindings,
                                            String selectedName) {
        if (bindings == null || bindings.presetCombo == null) {
            return;
        }
        bindings.programmaticChange = true;
        try {
            bindings.presetCombo.removeAllItems();
            String[] names = listSpatialPresetNames(directory);
            for (String presetName : names) {
                bindings.presetCombo.addItem(presetName);
            }
            bindings.presetCombo.setSelectedItem(selectedName == null
                    ? SPATIAL_PRESET_PLACEHOLDER
                    : selectedName);
        } finally {
            bindings.programmaticChange = false;
        }
    }

    private static boolean isSelected(ToggleSwitch toggle) {
        return toggle != null && toggle.isSelected();
    }

    private static String selectedText(JComboBox<String> combo, String fallback) {
        if (combo == null || combo.getSelectedItem() == null) {
            return fallback;
        }
        String text = String.valueOf(combo.getSelectedItem()).trim();
        return text.isEmpty() ? fallback : text;
    }

    private static double readFirstThreshold(List<JTextField> thresholdFields) {
        if (thresholdFields == null || thresholdFields.isEmpty()) {
            return 30.0;
        }
        return readNumericField(thresholdFields.get(0), 30.0, "Colocalization threshold");
    }

    private static double readNumericField(JTextField field, double fallback, String label) {
        if (field == null) {
            return fallback;
        }
        String text = field.getText();
        if (text == null || text.trim().isEmpty()) {
            return fallback;
        }
        try {
            return Double.parseDouble(text.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(label + " must be a number.");
        }
    }

    private String[] listSpatialPresetNames(String directory) {
        List<String> labels = new ArrayList<String>();
        labels.add(SPATIAL_PRESET_PLACEHOLDER);
        try {
            List<SpatialPreset> presets = new SpatialPresetIO(new File(directory)).listAll();
            for (SpatialPreset preset : presets) {
                labels.add(preset.getName());
            }
        } catch (IOException e) {
            IJ.log("WARNING: Could not list Spatial presets: " + e.getMessage());
        }
        return labels.toArray(new String[labels.size()]);
    }

    private SpatialAnalysisWizard.DerivedConfig runSpatialSetupHelper(String directory) {
        try {
            ChannelIdentities identities = ChannelConfigIO.readChannelIdentities(
                    FlashProjectLayout.forDirectory(directory).configurationWriteDir());
            MetadataDiagnostics.SeriesInfo info = firstSeriesInfoOrNull(directory);
            SpatialAnalysisWizard wizard = new SpatialAnalysisWizard(
                    flash.pipeline.ui.wizard.WizardFlow.MainPanelBinding.NULL,
                    identities,
                    info,
                    calibrationIsAvailable(directory),
                    !markerThresholds.isEmpty(),
                    false);
            wizard.run();
            if (!wizard.wasFinished()) {
                return null;
            }
            return wizard.deriveCurrentConfig();
        } catch (Exception e) {
            IJ.handleException(e);
            return null;
        }
    }

    private SpatialAnalysisWizard.DerivedConfig loadSpatialPresetConfig(String directory, String presetName) {
        try {
            SpatialPreset preset = new SpatialPresetIO(new File(directory)).load(presetName);
            SpatialAnalysisWizard.DerivedConfig config = SpatialAnalysisWizard.fromPreset(preset);
            SpatialAnalysisWizard.enforceDependencies(config, firstSeriesInfoOrNull(directory),
                    calibrationIsAvailable(directory));
            return config;
        } catch (IOException e) {
            IJ.showMessage("Spatial Analysis", "Could not load preset: " + e.getMessage());
            return null;
        }
    }

    private SpatialAnalysisWizard.DerivedConfig loadCliSpatialConfig(String directory) {
        if (cliConfig == null || cliConfig.getSpatial() == null || !cliConfig.getSpatial().hasConfiguration()) {
            return null;
        }
        CLIConfig.SpatialConfig spatial = cliConfig.getSpatial();
        SpatialAnalysisWizard.DerivedConfig config = null;
        if (spatial.getPresetName() != null && !spatial.getPresetName().trim().isEmpty()) {
            config = loadSpatialPresetConfig(directory, spatial.getPresetName());
        }
        if (config == null) {
            config = new SpatialAnalysisWizard.DerivedConfig();
        }
        applyCliSpatialOverrides(config, spatial);
        SpatialAnalysisWizard.enforceDependencies(config, firstSeriesInfoOrNull(directory),
                calibrationIsAvailable(directory));
        return config;
    }

    private static void applyCliSpatialOverrides(SpatialAnalysisWizard.DerivedConfig config,
                                                 CLIConfig.SpatialConfig spatial) {
        if (config == null || spatial == null) return;
        if (spatial.getDoDistances() != null) config.doDistances = spatial.getDoDistances().booleanValue();
        if (spatial.getDoSpatialStats() != null) config.doSpatialStats = spatial.getDoSpatialStats().booleanValue();
        if (spatial.getDoVolColoc() != null) config.doVolColoc = spatial.getDoVolColoc().booleanValue();
        if (spatial.getDoCpc() != null) config.doCpc = spatial.getDoCpc().booleanValue();
        if (spatial.getDoVoronoi() != null) config.doVoronoi = spatial.getDoVoronoi().booleanValue();
        if (spatial.getDoHeatmaps() != null) config.doHeatmaps = spatial.getDoHeatmaps().booleanValue();
        if (spatial.getDoPhenotyping() != null) config.doPhenotyping = spatial.getDoPhenotyping().booleanValue();
        if (spatial.getDo2DMorphology() != null) config.do2DMorphology = spatial.getDo2DMorphology().booleanValue();
        if (spatial.getDo3DMorphology() != null) config.do3DMorphology = spatial.getDo3DMorphology().booleanValue();
        if (spatial.getDoCompositeIndices() != null) config.doCompositeIndices = spatial.getDoCompositeIndices().booleanValue();
        if (spatial.getDoPopMorphometrics() != null) config.doPopMorphometrics = spatial.getDoPopMorphometrics().booleanValue();
        if (spatial.getDoSpatialMorphometrics() != null) config.doSpatialMorphometrics = spatial.getDoSpatialMorphometrics().booleanValue();
        if (spatial.getTextureGlcm() != null) config.doObjectGLCM = spatial.getTextureGlcm().booleanValue();
        if (spatial.getTextureFractal() != null) config.doObjectFractal = spatial.getTextureFractal().booleanValue();
        if (spatial.getTextureClass() != null) config.doObjectTextureClass = spatial.getTextureClass().booleanValue();
        if (spatial.getTextureClassFractions() != null) {
            config.doObjectTextureClassFractions = spatial.getTextureClassFractions().booleanValue();
        }
        if (spatial.getTextureNative3D() != null) config.doNative3DTexture = spatial.getTextureNative3D().booleanValue();
        if (spatial.getTextureClassK() != null) config.textureClassK = clampObjectTextureClassK(spatial.getTextureClassK().intValue());
        if (spatial.getKdeBandwidth() != null) config.kdeBandwidth = spatial.getKdeBandwidth().doubleValue();
        if (spatial.getHeatmapLut() != null && !spatial.getHeatmapLut().trim().isEmpty()) {
            config.heatmapLut = spatial.getHeatmapLut().trim();
        }
        if (spatial.getClusterK() != null) config.clusterK = spatial.getClusterK().intValue();
    }

    private static MetadataDiagnostics.SeriesInfo firstSeriesInfoOrNull(String directory) {
        try {
            List<MetadataDiagnostics.SeriesInfo> infos = MetadataDiagnostics.scanDirectory(directory);
            return infos.isEmpty() ? null : infos.get(0);
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean calibrationIsAvailable(String directory) {
        CalibrationIO.PixelCalibration cal = CalibrationIO.readFromDirectory(directory);
        return cal != null && cal.isCalibrated();
    }

    private interface SpatialConfigApplier {
        void apply(String selectedPresetName, SpatialAnalysisWizard.DerivedConfig derived);
    }

    private static final class SpatialDialogBindings {
        boolean programmaticChange;
        boolean lockVolColocFromObjectAnalysis;
        boolean lockCpcFromObjectAnalysis;
        JComboBox<String> presetCombo;
        ToggleSwitch forceRerunToggle;
        ToggleSwitch doDistancesToggle;
        ToggleSwitch lineDistanceToggle;
        ToggleSwitch doSpatialStatsToggle;
        ToggleSwitch doVolColocToggle;
        ToggleSwitch doCpcToggle;
        ToggleSwitch doVoronoiToggle;
        ToggleSwitch doHeatmapsToggle;
        ToggleSwitch doPhenotypingToggle;
        ToggleSwitch do2DMorphologyToggle;
        ToggleSwitch do3DMorphologyToggle;
        ToggleSwitch doCompositeIndicesToggle;
        ToggleSwitch doPopMorphometricsToggle;
        ToggleSwitch doSpatialMorphometricsToggle;
        ToggleSwitch doObjectGLCMToggle;
        ToggleSwitch doObjectFractalToggle;
        ToggleSwitch doObjectTextureClassToggle;
        ToggleSwitch doNative3DTextureToggle;
        List<JTextField> thresholdFields = new ArrayList<JTextField>();
        JTextField kdeBandwidthField;
        JTextField textureClassKField;
        JComboBox<String> heatmapLutChoice;
        JTextField clusterKField;
    }

    private static final class SpatialGroupKey {
        final String animal;
        final String hemisphere;
        final String region;
        final String roi;

        SpatialGroupKey(String animal, String hemisphere, String region, String roi) {
            this.animal = animal == null ? "" : animal;
            this.hemisphere = hemisphere == null ? "" : hemisphere;
            this.region = region == null ? "" : region;
            this.roi = roi == null ? "" : roi;
        }

        long seed() {
            long hash = 17L;
            hash = 31L * hash + animal.hashCode();
            hash = 31L * hash + hemisphere.hashCode();
            hash = 31L * hash + region.hashCode();
            hash = 31L * hash + roi.hashCode();
            return hash;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (!(obj instanceof SpatialGroupKey)) return false;
            SpatialGroupKey other = (SpatialGroupKey) obj;
            return animal.equals(other.animal)
                    && hemisphere.equals(other.hemisphere)
                    && region.equals(other.region)
                    && roi.equals(other.roi);
        }

        @Override
        public int hashCode() {
            int result = animal.hashCode();
            result = 31 * result + hemisphere.hashCode();
            result = 31 * result + region.hashCode();
            result = 31 * result + roi.hashCode();
            return result;
        }
    }
}
