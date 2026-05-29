package flash.pipeline.cli;

import flash.pipeline.analyses.StatisticsConfig;
import flash.pipeline.bin.BinField;
import flash.pipeline.analyses.wizard.AggregationConfig;
import flash.pipeline.analyses.wizard.IntensitySpatialConfig;
import flash.pipeline.deconv.engine.Algorithm;
import flash.pipeline.deconv.engine.DeconvParams;
import flash.pipeline.deconv.psf.PsfModel;
import flash.pipeline.deconv.psf.ScopeModality;
import flash.pipeline.segmentation.SegmentationTokenParser;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Parsed CLI state for one FLASH invocation.
 */
public class CLIConfig {

    static final int ANALYSIS_COUNT = 12;

    String directory = null;
    boolean[] selectedAnalyses = new boolean[ANALYSIS_COUNT];
    boolean headless = true;
    boolean parallel = true;
    int threads = Math.max(1, Runtime.getRuntime().availableProcessors() / 2);
    boolean verbose = false;
    String overwriteBehavior = "Auto-Overwrite";
    boolean autoAggregate = true;
    boolean qcReport = true;
    boolean tifCache = false;
    int loaderThreads = 1;
    int loaderPercent = 50;
    int gpuPermits = 0;
    boolean splitMergeUseDeconv = true;
    boolean threeDUseDeconv = true;
    boolean intensityV2UseDeconv = true;
    final DeconvConfig deconv = new DeconvConfig();
    final EnumMap<BinField, String> headlessBinFields = new EnumMap<BinField, String>(BinField.class);
    final CreateBinConfig bin = new CreateBinConfig();
    final ThreeDObjectConfig object = new ThreeDObjectConfig();
    final SpatialConfig spatial = new SpatialConfig();
    final IntensityConfig intensity = new IntensityConfig();
    final SpectralConfig spectral = new SpectralConfig();
    final AggregateConfig aggregate = new AggregateConfig();
    final ExcelConfig excel = new ExcelConfig();
    final StatsConfig stats = new StatsConfig();

    public String getDirectory() { return directory; }
    public boolean[] getSelectedAnalyses() { return selectedAnalyses; }
    public boolean isHeadless() { return headless; }
    public boolean isParallel() { return parallel; }
    public int getThreads() { return threads; }
    public boolean isVerbose() { return verbose; }
    public String getOverwriteBehavior() { return overwriteBehavior; }
    public boolean isAutoAggregate() { return autoAggregate; }
    public boolean isQcReport() { return qcReport; }
    public boolean isTifCache() { return tifCache; }
    public int getLoaderThreads() { return loaderThreads; }
    public int getLoaderPercent() { return loaderPercent; }
    public int getGpuPermits() { return gpuPermits; }
    public boolean isSplitMergeUseDeconv() { return splitMergeUseDeconv; }
    public boolean isThreeDUseDeconv() { return threeDUseDeconv; }
    public boolean isIntensityV2UseDeconv() { return intensityV2UseDeconv; }
    public DeconvConfig getDeconv() { return deconv; }
    public boolean hasBinField(BinField field) {
        String value = getBinFieldValue(field);
        return value != null && !value.trim().isEmpty();
    }
    public String getBinFieldValue(BinField field) {
        return field == null ? null : headlessBinFields.get(field);
    }
    public CreateBinConfig getBin() { return bin; }
    public ThreeDObjectConfig getObject() { return object; }
    public SpatialConfig getSpatial() { return spatial; }
    public IntensityConfig getIntensity() { return intensity; }
    public SpectralConfig getSpectral() { return spectral; }
    public AggregateConfig getAggregate() { return aggregate; }
    public ExcelConfig getExcel() { return excel; }
    public StatsConfig getStats() { return stats; }

    /**
     * Re-serializes the config into macro-style CLI arguments.
     */
    public String toMacroOptions() {
        List<String> parts = new ArrayList<String>();
        if (directory != null && !directory.trim().isEmpty()) {
            parts.add("dir=[" + directory + "]");
        }

        String analyses = joinSelectedAnalyses(selectedAnalyses);
        if (!analyses.isEmpty()) {
            parts.add("analyses=" + analyses);
        }

        if (headless) parts.add("headless");
        if (parallel) parts.add("parallel");
        if (verbose) parts.add("verbose");
        if (tifCache) parts.add("tif_cache");
        if (!autoAggregate) parts.add("no_aggregate");
        if (!qcReport) parts.add("no_qc");
        if (!"Auto-Overwrite".equals(overwriteBehavior)) {
            parts.add("overwrite=" + ("Skip Existing".equals(overwriteBehavior) ? "skip" : "auto"));
        }
        parts.add("threads=" + threads);
        parts.add("loader_threads=" + loaderThreads);
        parts.add("loader_percent=" + loaderPercent);
        parts.add("gpu_permits=" + gpuPermits);

        boolean deconvCustomized = containsDeconvOverrides();
        if (deconv.enabled) {
            parts.add("deconv.enabled=true");
        } else if (deconvCustomized) {
            parts.add("deconv.enabled=false");
        }
        if (deconv.presetName != null && !deconv.presetName.trim().isEmpty()) {
            parts.add("deconv.preset=" + deconv.presetName.trim());
        }
        if (deconv.engine != null) parts.add("deconv.engine=" + deconv.engine);
        if (deconv.algorithm != null) parts.add("deconv.algorithm=" + deconv.algorithm.name());
        if (deconv.presetName != null || (deconv.psfModel != null && deconv.psfModel != PsfModel.GIBSON_LANNI)) {
            parts.add("deconv.psf=" + canonicalPsfName(deconv.psfModel));
        }
        if (deconv.presetName != null || deconv.iterations != DeconvParams.DEFAULT_ITERATIONS) {
            parts.add("deconv.iterations=" + deconv.iterations);
        }
        if (deconv.presetName != null
                || Double.compare(deconv.regularization, DeconvParams.DEFAULT_REGULARIZATION) != 0) {
            parts.add("deconv.regularization=" + formatDouble(deconv.regularization));
        }
        if (deconv.scopeModality != null) {
            parts.add("deconv.scopeModality=" + canonicalScopeName(deconv.scopeModality));
        }
        if (deconv.pinholeAiryUnits != null) {
            parts.add("deconv.pinholeAU=" + formatDouble(deconv.pinholeAiryUnits.doubleValue()));
        }
        if (deconv.sampleRI != null) {
            parts.add("deconv.sampleRI=" + formatDouble(deconv.sampleRI.doubleValue()));
        }
        if (deconv.mountingMedium != null && !deconv.mountingMedium.trim().isEmpty()) {
            parts.add("deconv.mountingMedium=" + deconv.mountingMedium.trim());
        }
        if (deconv.channels != null && deconv.channels.length > 0) {
            parts.add("deconv.channels=" + joinInts(deconv.channels));
        }
        if (deconv.strictNyquist) {
            parts.add("deconv.strictNyquist=true");
        } else if (deconvCustomized || deconv.presetName != null) {
            parts.add("deconv.strictNyquist=false");
        }
        if (!deconv.useCache) {
            parts.add("deconv.useCache=false");
        } else if (deconvCustomized || deconv.presetName != null) {
            parts.add("deconv.useCache=true");
        }
        if (deconv.skipPreview) {
            parts.add("deconv.skipPreview=true");
        }

        if (bin.presetName != null && !bin.presetName.trim().isEmpty()) {
            parts.add("bin.preset=" + bin.presetName.trim());
        }
        appendHeadlessBinField(parts, BinField.CHANNEL_NAMES);
        appendHeadlessBinField(parts, BinField.CHANNEL_COLORS);
        appendHeadlessBinField(parts, BinField.OBJECT_THRESHOLDS);
        appendHeadlessBinField(parts, BinField.PARTICLE_SIZES);
        appendHeadlessBinField(parts, BinField.DISPLAY_MIN_MAX);
        appendHeadlessBinField(parts, BinField.INTENSITY_THRESHOLDS);
        appendHeadlessBinField(parts, BinField.SEGMENTATION_METHODS);
        appendHeadlessBinField(parts, BinField.FILTER_PRESETS);
        appendHeadlessBinField(parts, BinField.Z_SLICE);
        appendChannelOverrides(parts, "bin.channel", "_name", bin.names);
        appendChannelOverrides(parts, "bin.channel", "_color", bin.colors);
        appendChannelOverrides(parts, "bin.channel", "_threshold", bin.objectThresholds);
        appendChannelOverrides(parts, "bin.channel", "_size", bin.sizes);
        appendChannelOverrides(parts, "bin.channel", "_minmax", bin.minmax);
        appendChannelOverrides(parts, "bin.channel", "_intensity_threshold", bin.intensityThresholds);
        appendChannelOverrides(parts, "bin.channel", "_filter_preset", bin.filterPresets);
        appendChannelOverrides(parts, "bin.channel", "_segmentation", bin.segmentationMethods);

        if (object.presetName != null && !object.presetName.trim().isEmpty()) {
            parts.add("object.preset=" + object.presetName.trim());
        }
        if (object.doVolumetric != null) {
            parts.add("object.doVolumetric=" + object.doVolumetric);
        }
        if (object.doCpc != null) {
            parts.add("object.doCpc=" + object.doCpc);
        }
        if (object.doIntensityColoc != null) {
            parts.add("object.doIntensityColoc=" + object.doIntensityColoc);
        }
        if (object.extractProcessLength != null) {
            parts.add("object.extractProcessLength=" + object.extractProcessLength);
        }
        if (object.runSpatial != null) {
            parts.add("object.runSpatial=" + object.runSpatial);
        }
        if (object.classicalCentroidFiltering != null) {
            parts.add("object.classicalCentroidFiltering=" + object.classicalCentroidFiltering);
        }
        if (object.colocThresholdPercent != null) {
            parts.add("object.colocThreshold=" + formatDouble(object.colocThresholdPercent.doubleValue()));
        }
        if (object.nuclearMarkerIndex != null) {
            parts.add("object.nuclear_marker=" + (object.nuclearMarkerIndex.intValue() + 1));
        }

        if (spatial.presetName != null && !spatial.presetName.trim().isEmpty()) {
            parts.add("spatial.preset=" + spatial.presetName.trim());
        }
        appendBoolean(parts, "spatial.distances", spatial.doDistances);
        appendBoolean(parts, "spatial.stats", spatial.doSpatialStats);
        appendBoolean(parts, "spatial.volumetric", spatial.doVolColoc);
        appendBoolean(parts, "spatial.cpc", spatial.doCpc);
        appendBoolean(parts, "spatial.voronoi", spatial.doVoronoi);
        appendBoolean(parts, "spatial.heatmaps", spatial.doHeatmaps);
        appendBoolean(parts, "spatial.phenotyping", spatial.doPhenotyping);
        appendBoolean(parts, "spatial.2d", spatial.do2DMorphology);
        appendBoolean(parts, "spatial.3d", spatial.do3DMorphology);
        appendBoolean(parts, "spatial.complex", spatial.doCompositeIndices);
        appendBoolean(parts, "spatial.population", spatial.doPopMorphometrics);
        appendBoolean(parts, "spatial.spatialMorph", spatial.doSpatialMorphometrics);
        appendBoolean(parts, "spatial.texture.glcm", spatial.textureGlcm);
        appendBoolean(parts, "spatial.texture.fractal", spatial.textureFractal);
        appendBoolean(parts, "spatial.texture.class", spatial.textureClass);
        appendBoolean(parts, "spatial.texture.classfractions", spatial.textureClassFractions);
        appendBoolean(parts, "spatial.texture.native3d", spatial.textureNative3D);
        if (spatial.textureClassK != null) {
            parts.add("spatial.texture.k=" + spatial.textureClassK);
        }
        if (spatial.kdeBandwidth != null) {
            parts.add("spatial.kdeBandwidth=" + formatDouble(spatial.kdeBandwidth.doubleValue()));
        }
        if (spatial.heatmapLut != null && !spatial.heatmapLut.trim().isEmpty()) {
            parts.add("spatial.heatmapLut=" + spatial.heatmapLut.trim());
        }
        if (spatial.clusterK != null) {
            parts.add("spatial.clusterK=" + spatial.clusterK);
        }

        if (intensity.presetName != null && !intensity.presetName.trim().isEmpty()) {
            parts.add("intensity.preset=" + intensity.presetName.trim());
        }
        appendChannelOverrides(parts, "intensity.threshold_channel", "", intensity.thresholds);
        appendBoolean(parts, "intensity.spatial", intensity.spatialEnabled);
        if (intensity.spatialAnalyses != null) {
            parts.add("intensity.spatial.analyses="
                    + IntensitySpatialConfig.joinAnalysisTokens(intensity.spatialAnalyses));
        }
        if (intensity.spatialSourceMode != null) {
            parts.add("intensity.spatial.source=" + intensity.spatialSourceMode.token());
        }
        appendBoolean(parts, "intensity.spatial.mip", intensity.spatialMipEnabled);
        appendBoolean(parts, "intensity.spatial.native3d", intensity.spatialNative3dEnabled);
        appendBoolean(parts, "intensity.spatial.overlays", intensity.spatialOverlaysEnabled);
        if (intensity.spatialShellWidthUm != null) {
            parts.add("intensity.spatial.shell_width_um="
                    + formatDouble(intensity.spatialShellWidthUm.doubleValue()));
        }
        if (intensity.spatialShellCount != null) {
            parts.add("intensity.spatial.shell_count=" + intensity.spatialShellCount);
        }
        if (intensity.spatialTileScalesUm != null) {
            parts.add("intensity.spatial.tile_um="
                    + IntensitySpatialConfig.joinDoubles(intensity.spatialTileScalesUm));
        }
        if (intensity.spatialGranularityScalesUm != null) {
            parts.add("intensity.spatial.granularity_um="
                    + IntensitySpatialConfig.joinDoubles(intensity.spatialGranularityScalesUm));
        }
        if (intensity.spatialDepthBinWidthUm != null) {
            parts.add("intensity.spatial.depth_bin_um="
                    + formatDouble(intensity.spatialDepthBinWidthUm.doubleValue()));
        }
        if (intensity.spatialRimDepthUm != null) {
            parts.add("intensity.spatial.rim_depth_um="
                    + formatDouble(intensity.spatialRimDepthUm.doubleValue()));
        }
        if (intensity.spatialTextureClassCount != null) {
            parts.add("intensity.spatial.texture_k=" + intensity.spatialTextureClassCount);
        }
        if (intensity.spatialPermutations != null) {
            parts.add("intensity.spatial.permutations=" + intensity.spatialPermutations);
        }
        if (intensity.spatialSeed != null) {
            parts.add("intensity.spatial.seed=" + intensity.spatialSeed);
        }
        if (intensity.spatialFailurePolicy != null) {
            parts.add("intensity.spatial.failure_policy="
                    + intensity.spatialFailurePolicy.name().toLowerCase(Locale.ROOT));
        }

        if (spectral.presetName != null && !spectral.presetName.trim().isEmpty()) {
            parts.add("spectral.preset=" + spectral.presetName.trim());
        }
        if (spectral.goal != null && !spectral.goal.trim().isEmpty()) {
            parts.add("spectral.goal=" + spectral.goal.trim());
        }
        if (spectral.targetChannelIndex != null) {
            parts.add("spectral.target=" + spectral.targetChannelIndex.intValue());
        }
        if (spectral.bleedThroughChannelIndexes != null) {
            parts.add("spectral.bleedthrough=" + joinInts(spectral.bleedThroughChannelIndexes));
        }
        if (spectral.autofluorescenceChannelIndexes != null) {
            parts.add("spectral.autofluorescence=" + joinInts(spectral.autofluorescenceChannelIndexes));
        }
        if (spectral.contaminationType != null && !spectral.contaminationType.trim().isEmpty()) {
            parts.add("spectral.contamination_type=" + spectral.contaminationType.trim());
        }
        if (spectral.calibration != null && !spectral.calibration.trim().isEmpty()) {
            parts.add("spectral.calibration=" + spectral.calibration.trim());
        }
        if (spectral.strength != null && !spectral.strength.trim().isEmpty()) {
            parts.add("spectral.strength=" + spectral.strength.trim());
        }

        if (aggregate.presetName != null && !aggregate.presetName.trim().isEmpty()) {
            String trimmed = aggregate.presetName.trim();
            parts.add("aggregate.preset="
                    + (trimmed.contains(" ") ? "[" + trimmed + "]" : trimmed));
        }
        if (aggregate.granularity != null) {
            parts.add("aggregate.granularity=" + aggregate.granularity.token());
        }
        if (aggregate.outputMode != null) {
            parts.add("aggregate.output=" + aggregate.outputMode.token());
        }

        if (excel.presetName != null && !excel.presetName.trim().isEmpty()) {
            String trimmed = excel.presetName.trim();
            parts.add("excel.preset="
                    + (trimmed.contains(" ") ? "[" + trimmed + "]" : trimmed));
        }
        appendBoolean(parts, "excel.texture.features", excel.includeTextureFeatures);
        for (Map.Entry<String, String> override : excel.fieldOverrides.entrySet()) {
            String key = override.getKey();
            String value = override.getValue();
            if (key == null || value == null) continue;
            parts.add("excel." + key + "=" + value);
        }

        if (stats.presetName != null && !stats.presetName.trim().isEmpty()) {
            String trimmed = stats.presetName.trim();
            parts.add("stats.preset="
                    + (trimmed.contains(" ") ? "[" + trimmed + "]" : trimmed));
        }
        if (stats.pairedMode != null) {
            parts.add("stats.paired=" + stats.pairedMode.name().toLowerCase(Locale.ROOT));
        }
        if (stats.distMode != null) {
            parts.add("stats.distribution=" + canonicalDistribution(stats.distMode));
        }
        if (stats.postHoc != null) {
            parts.add("stats.posthoc=" + stats.postHoc.name().toLowerCase(Locale.ROOT));
        }
        if (stats.metrics != null && !stats.metrics.isEmpty()) {
            StringBuilder mb = new StringBuilder();
            for (int i = 0; i < stats.metrics.size(); i++) {
                if (i > 0) mb.append(',');
                mb.append(stats.metrics.get(i));
            }
            String joined = mb.toString();
            parts.add("stats.metrics="
                    + (joined.contains(" ") ? "[" + joined + "]" : joined));
        }

        if (!splitMergeUseDeconv) parts.add("splitmerge.useDeconv=false");
        if (!threeDUseDeconv) parts.add("threeD.useDeconv=false");
        if (!intensityV2UseDeconv) parts.add("intensityV2.useDeconv=false");

        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (part == null || part.trim().isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(part);
        }
        return sb.toString();
    }

    private boolean containsDeconvOverrides() {
        return deconv.engine != null
                || (deconv.presetName != null && !deconv.presetName.trim().isEmpty())
                || deconv.algorithm != null
                || (deconv.psfModel != null && deconv.psfModel != PsfModel.GIBSON_LANNI)
                || deconv.scopeModality != null
                || deconv.pinholeAiryUnits != null
                || deconv.sampleRI != null
                || (deconv.mountingMedium != null && !deconv.mountingMedium.trim().isEmpty())
                || (deconv.channels != null && deconv.channels.length > 0)
                || deconv.iterations != DeconvParams.DEFAULT_ITERATIONS
                || Double.compare(deconv.regularization, DeconvParams.DEFAULT_REGULARIZATION) != 0
                || deconv.strictNyquist
                || !deconv.useCache
                || deconv.skipPreview;
    }

    private void appendHeadlessBinField(List<String> parts, BinField field) {
        String value = getBinFieldValue(field);
        if (value == null || value.trim().isEmpty()) return;
        if (field == BinField.SEGMENTATION_METHODS) {
            value = canonicalSegmentationMethods(value);
        }
        parts.add(binFieldCliKey(field) + "=" + value.trim());
    }

    public static String binFieldCliKey(BinField field) {
        if (field == null) return "";
        switch (field) {
            case CHANNEL_NAMES: return "channel_names";
            case CHANNEL_COLORS: return "channel_colors";
            case OBJECT_THRESHOLDS: return "object_thresholds";
            case PARTICLE_SIZES: return "particle_sizes";
            case DISPLAY_MIN_MAX: return "display_min_max";
            case INTENSITY_THRESHOLDS: return "intensity_thresholds";
            case SEGMENTATION_METHODS: return "segmentation_methods";
            case FILTER_PRESETS: return "filter_presets";
            case Z_SLICE: return "z_slice_mode";
            default: return "";
        }
    }

    private static void appendChannelOverrides(List<String> parts,
                                               String prefix,
                                               String suffix,
                                               Map<Integer, String> values) {
        if (values == null || values.isEmpty()) return;
        for (Map.Entry<Integer, String> entry : values.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) continue;
            String value = "_segmentation".equals(suffix)
                    ? canonicalSegmentationToken(entry.getValue())
                    : entry.getValue();
            parts.add(prefix + (entry.getKey().intValue() + 1) + suffix + "=" + value);
        }
    }

    private static String canonicalSegmentationMethods(String value) {
        String safe = value == null ? "" : value.trim();
        if (safe.isEmpty()) return safe;
        String[] tokens = safe.split(",", -1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < tokens.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(canonicalSegmentationToken(tokens[i]));
        }
        return sb.toString();
    }

    private static String canonicalSegmentationToken(String value) {
        String safe = value == null ? "" : value.trim();
        if (safe.isEmpty()) return safe;
        try {
            return SegmentationTokenParser.format(SegmentationTokenParser.parse(safe));
        } catch (IllegalArgumentException e) {
            return safe;
        }
    }

    private static void appendBoolean(List<String> parts, String key, Boolean value) {
        if (value != null) {
            parts.add(key + "=" + value.booleanValue());
        }
    }

    private static String joinSelectedAnalyses(boolean[] selections) {
        if (selections == null || selections.length == 0) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < selections.length; i++) {
            if (!selections[i]) continue;
            if (sb.length() > 0) sb.append(',');
            sb.append(i);
        }
        return sb.toString();
    }

    private static String joinInts(int[] values) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(values[i]);
        }
        return sb.toString();
    }

    private static String canonicalPsfName(PsfModel model) {
        if (model == PsfModel.GIBSON_LANNI) return "GibsonLanni";
        if (model == PsfModel.BORN_WOLF) return "BornWolf";
        return "Dougherty";
    }

    private static String canonicalScopeName(ScopeModality modality) {
        if (modality == ScopeModality.SPINNING_DISK) return "spinningDisk";
        return modality.name().toLowerCase(Locale.ROOT);
    }

    private static String canonicalDistribution(StatisticsConfig.DistributionMode mode) {
        if (mode == StatisticsConfig.DistributionMode.ASSUME_NORMAL) return "parametric";
        if (mode == StatisticsConfig.DistributionMode.ASSUME_SKEWED) return "non_parametric";
        return "auto";
    }

    private static String formatDouble(double value) {
        return String.format(Locale.ROOT, "%.6f", value);
    }

    public static final class DeconvConfig {
        boolean enabled = false;
        String presetName = null;
        String engine = null;
        Algorithm algorithm = null;
        PsfModel psfModel = PsfModel.GIBSON_LANNI;
        int iterations = DeconvParams.DEFAULT_ITERATIONS;
        double regularization = DeconvParams.DEFAULT_REGULARIZATION;
        ScopeModality scopeModality = null;
        Double pinholeAiryUnits = null;
        Double sampleRI = null;
        String mountingMedium = null;
        int[] channels = null;
        boolean strictNyquist = false;
        boolean useCache = true;
        boolean skipPreview = false;

        public boolean isEnabled() { return enabled; }
        public String getPresetName() { return presetName; }
        public String getEngine() { return engine; }
        public Algorithm getAlgorithm() { return algorithm; }
        public PsfModel getPsfModel() { return psfModel; }
        public int getIterations() { return iterations; }
        public double getRegularization() { return regularization; }
        public ScopeModality getScopeModality() { return scopeModality; }
        public Double getPinholeAiryUnits() { return pinholeAiryUnits; }
        public Double getSampleRI() { return sampleRI; }
        public String getMountingMedium() { return mountingMedium; }
        public int[] getChannels() { return channels; }
        public boolean isStrictNyquist() { return strictNyquist; }
        public boolean isUseCache() { return useCache; }
        public boolean isSkipPreview() { return skipPreview; }
    }

    public static final class CreateBinConfig {
        String presetName = null;
        final Map<Integer, String> names = new LinkedHashMap<Integer, String>();
        final Map<Integer, String> colors = new LinkedHashMap<Integer, String>();
        final Map<Integer, String> objectThresholds = new LinkedHashMap<Integer, String>();
        final Map<Integer, String> sizes = new LinkedHashMap<Integer, String>();
        final Map<Integer, String> minmax = new LinkedHashMap<Integer, String>();
        final Map<Integer, String> intensityThresholds = new LinkedHashMap<Integer, String>();
        final Map<Integer, String> filterPresets = new LinkedHashMap<Integer, String>();
        final Map<Integer, String> segmentationMethods = new LinkedHashMap<Integer, String>();

        public String getPresetName() { return presetName; }
        public Map<Integer, String> getNames() { return names; }
        public Map<Integer, String> getColors() { return colors; }
        public Map<Integer, String> getObjectThresholds() { return objectThresholds; }
        public Map<Integer, String> getSizes() { return sizes; }
        public Map<Integer, String> getMinmax() { return minmax; }
        public Map<Integer, String> getIntensityThresholds() { return intensityThresholds; }
        public Map<Integer, String> getFilterPresets() { return filterPresets; }
        public Map<Integer, String> getSegmentationMethods() { return segmentationMethods; }

        public boolean hasConfiguration() {
            return (presetName != null && !presetName.trim().isEmpty())
                    || !names.isEmpty()
                    || !colors.isEmpty()
                    || !objectThresholds.isEmpty()
                    || !sizes.isEmpty()
                    || !minmax.isEmpty()
                    || !intensityThresholds.isEmpty()
                    || !filterPresets.isEmpty()
                    || !segmentationMethods.isEmpty();
        }
    }

    public static final class ThreeDObjectConfig {
        String presetName = null;
        Boolean doVolumetric = null;
        Boolean doCpc = null;
        Boolean doIntensityColoc = null;
        Boolean extractProcessLength = null;
        Boolean runSpatial = null;
        Boolean classicalCentroidFiltering = null;
        Double colocThresholdPercent = null;
        Integer nuclearMarkerIndex = null;

        public String getPresetName() { return presetName; }
        public Boolean getDoVolumetric() { return doVolumetric; }
        public Boolean getDoCpc() { return doCpc; }
        public Boolean getDoIntensityColoc() { return doIntensityColoc; }
        public Boolean getExtractProcessLength() { return extractProcessLength; }
        public Boolean getRunSpatial() { return runSpatial; }
        public Boolean getClassicalCentroidFiltering() { return classicalCentroidFiltering; }
        public Double getColocThresholdPercent() { return colocThresholdPercent; }
        public Integer getNuclearMarkerIndex() { return nuclearMarkerIndex; }

        public boolean hasConfiguration() {
            return (presetName != null && !presetName.trim().isEmpty())
                    || doVolumetric != null
                    || doCpc != null
                    || doIntensityColoc != null
                    || extractProcessLength != null
                    || runSpatial != null
                    || classicalCentroidFiltering != null
                    || colocThresholdPercent != null
                    || nuclearMarkerIndex != null;
        }
    }

    public static final class SpatialConfig {
        String presetName = null;
        Boolean doDistances = null;
        Boolean doSpatialStats = null;
        Boolean doVolColoc = null;
        Boolean doCpc = null;
        Boolean doVoronoi = null;
        Boolean doHeatmaps = null;
        Boolean doPhenotyping = null;
        Boolean do2DMorphology = null;
        Boolean do3DMorphology = null;
        Boolean doCompositeIndices = null;
        Boolean doPopMorphometrics = null;
        Boolean doSpatialMorphometrics = null;
        Boolean textureGlcm = null;
        Boolean textureFractal = null;
        Boolean textureClass = null;
        Boolean textureClassFractions = null;
        Boolean textureNative3D = null;
        Integer textureClassK = null;
        Double kdeBandwidth = null;
        String heatmapLut = null;
        Integer clusterK = null;

        public String getPresetName() { return presetName; }
        public Boolean getDoDistances() { return doDistances; }
        public Boolean getDoSpatialStats() { return doSpatialStats; }
        public Boolean getDoVolColoc() { return doVolColoc; }
        public Boolean getDoCpc() { return doCpc; }
        public Boolean getDoVoronoi() { return doVoronoi; }
        public Boolean getDoHeatmaps() { return doHeatmaps; }
        public Boolean getDoPhenotyping() { return doPhenotyping; }
        public Boolean getDo2DMorphology() { return do2DMorphology; }
        public Boolean getDo3DMorphology() { return do3DMorphology; }
        public Boolean getDoCompositeIndices() { return doCompositeIndices; }
        public Boolean getDoPopMorphometrics() { return doPopMorphometrics; }
        public Boolean getDoSpatialMorphometrics() { return doSpatialMorphometrics; }
        public Boolean getTextureGlcm() { return textureGlcm; }
        public Boolean getTextureFractal() { return textureFractal; }
        public Boolean getTextureClass() { return textureClass; }
        public Boolean getTextureClassFractions() { return textureClassFractions; }
        public void setTextureClassFractions(Boolean textureClassFractions) {
            this.textureClassFractions = textureClassFractions;
        }
        public Boolean getTextureNative3D() { return textureNative3D; }
        public void setTextureNative3D(Boolean textureNative3D) { this.textureNative3D = textureNative3D; }
        public Integer getTextureClassK() { return textureClassK; }
        public Double getKdeBandwidth() { return kdeBandwidth; }
        public String getHeatmapLut() { return heatmapLut; }
        public Integer getClusterK() { return clusterK; }

        public boolean hasConfiguration() {
            return (presetName != null && !presetName.trim().isEmpty())
                    || doDistances != null
                    || doSpatialStats != null
                    || doVolColoc != null
                    || doCpc != null
                    || doVoronoi != null
                    || doHeatmaps != null
                    || doPhenotyping != null
                    || do2DMorphology != null
                    || do3DMorphology != null
                    || doCompositeIndices != null
                    || doPopMorphometrics != null
                    || doSpatialMorphometrics != null
                    || textureGlcm != null
                    || textureFractal != null
                    || textureClass != null
                    || textureClassFractions != null
                    || textureNative3D != null
                    || textureClassK != null
                    || kdeBandwidth != null
                    || heatmapLut != null
                    || clusterK != null;
        }
    }

    public static final class IntensityConfig {
        String presetName = null;
        final Map<Integer, String> thresholds = new LinkedHashMap<Integer, String>();
        Boolean spatialEnabled = null;
        Set<IntensitySpatialConfig.AnalysisKey> spatialAnalyses = null;
        IntensitySpatialConfig.SpatialSourceMode spatialSourceMode = null;
        Boolean spatialMipEnabled = null;
        Boolean spatialNative3dEnabled = null;
        Boolean spatialOverlaysEnabled = null;
        Double spatialShellWidthUm = null;
        Integer spatialShellCount = null;
        double[] spatialTileScalesUm = null;
        double[] spatialGranularityScalesUm = null;
        Double spatialDepthBinWidthUm = null;
        Double spatialRimDepthUm = null;
        Integer spatialTextureClassCount = null;
        Integer spatialPermutations = null;
        Long spatialSeed = null;
        IntensitySpatialConfig.FailurePolicy spatialFailurePolicy = null;

        public String getPresetName() { return presetName; }
        public Map<Integer, String> getThresholds() { return thresholds; }
        public Boolean getSpatialEnabled() { return spatialEnabled; }
        public Set<IntensitySpatialConfig.AnalysisKey> getSpatialAnalyses() {
            return spatialAnalyses == null
                    ? Collections.<IntensitySpatialConfig.AnalysisKey>emptySet()
                    : Collections.unmodifiableSet(spatialAnalyses);
        }
        public Boolean getSpatialMipEnabled() { return spatialMipEnabled; }
        public IntensitySpatialConfig.SpatialSourceMode getSpatialSourceMode() { return spatialSourceMode; }
        public Boolean getSpatialNative3dEnabled() { return spatialNative3dEnabled; }
        public Boolean getSpatialOverlaysEnabled() { return spatialOverlaysEnabled; }
        public Double getSpatialShellWidthUm() { return spatialShellWidthUm; }
        public Integer getSpatialShellCount() { return spatialShellCount; }
        public double[] getSpatialTileScalesUm() {
            return spatialTileScalesUm == null ? null : spatialTileScalesUm.clone();
        }
        public double[] getSpatialGranularityScalesUm() {
            return spatialGranularityScalesUm == null ? null : spatialGranularityScalesUm.clone();
        }
        public Double getSpatialDepthBinWidthUm() { return spatialDepthBinWidthUm; }
        public Double getSpatialRimDepthUm() { return spatialRimDepthUm; }
        public Integer getSpatialTextureClassCount() { return spatialTextureClassCount; }
        public Integer getSpatialPermutations() { return spatialPermutations; }
        public Long getSpatialSeed() { return spatialSeed; }
        public IntensitySpatialConfig.FailurePolicy getSpatialFailurePolicy() { return spatialFailurePolicy; }

        public boolean hasConfiguration() {
            return (presetName != null && !presetName.trim().isEmpty())
                    || !thresholds.isEmpty()
                    || hasSpatialConfiguration();
        }

        public boolean hasSpatialConfiguration() {
            return spatialEnabled != null
                    || spatialAnalyses != null
                    || spatialSourceMode != null
                    || spatialMipEnabled != null
                    || spatialNative3dEnabled != null
                    || spatialOverlaysEnabled != null
                    || spatialShellWidthUm != null
                    || spatialShellCount != null
                    || spatialTileScalesUm != null
                    || spatialGranularityScalesUm != null
                    || spatialDepthBinWidthUm != null
                    || spatialRimDepthUm != null
                    || spatialTextureClassCount != null
                    || spatialPermutations != null
                    || spatialSeed != null
                    || spatialFailurePolicy != null;
        }

        public IntensitySpatialConfig getSpatialConfig() {
            return mergeSpatialConfig(IntensitySpatialConfig.disabled(), -1, null, null);
        }

        public IntensitySpatialConfig mergeSpatialConfig(IntensitySpatialConfig base,
                                                         int channelCount,
                                                         boolean[] channelBinarization,
                                                         IntensitySpatialConfig.LockLogger logger) {
            IntensitySpatialConfig safeBase = base == null ? IntensitySpatialConfig.disabled() : base;
            IntensitySpatialConfig.Builder builder = IntensitySpatialConfig.builder(safeBase);
            boolean impliedEnabled = false;
            if (spatialAnalyses != null) {
                builder.enabledAnalyses(spatialAnalyses);
                impliedEnabled = impliedEnabled || !spatialAnalyses.isEmpty();
            }
            if (spatialMipEnabled != null) {
                builder.mipEnabled(spatialMipEnabled.booleanValue());
                impliedEnabled = impliedEnabled || spatialMipEnabled.booleanValue();
            }
            if (spatialSourceMode != null) {
                builder.spatialSourceMode(spatialSourceMode);
                impliedEnabled = impliedEnabled
                        || spatialSourceMode == IntensitySpatialConfig.SpatialSourceMode.MIP;
            }
            if (spatialNative3dEnabled != null) {
                builder.native3dEnabled(spatialNative3dEnabled.booleanValue());
                impliedEnabled = impliedEnabled || spatialNative3dEnabled.booleanValue();
            }
            if (spatialOverlaysEnabled != null) {
                builder.overlaysEnabled(spatialOverlaysEnabled.booleanValue());
                impliedEnabled = impliedEnabled || spatialOverlaysEnabled.booleanValue();
            }
            if (spatialShellWidthUm != null) builder.shellWidthUm(spatialShellWidthUm.doubleValue());
            if (spatialShellCount != null) builder.shellCount(spatialShellCount.intValue());
            if (spatialTileScalesUm != null) builder.tileScalesUm(spatialTileScalesUm);
            if (spatialGranularityScalesUm != null) builder.granularityScalesUm(spatialGranularityScalesUm);
            if (spatialDepthBinWidthUm != null) builder.depthBinWidthUm(spatialDepthBinWidthUm.doubleValue());
            if (spatialRimDepthUm != null) builder.rimDepthUm(spatialRimDepthUm.doubleValue());
            if (spatialTextureClassCount != null) {
                builder.textureClassCount(spatialTextureClassCount.intValue());
            }
            if (spatialPermutations != null) builder.permutations(spatialPermutations.intValue());
            if (spatialSeed != null) builder.seed(spatialSeed.longValue());
            if (spatialFailurePolicy != null) builder.failurePolicy(spatialFailurePolicy);
            if (spatialEnabled != null) {
                builder.enabled(spatialEnabled.booleanValue());
            } else if (!safeBase.isEnabled() && impliedEnabled) {
                builder.enabled(true);
            }

            IntensitySpatialConfig merged = builder.build();
            if (channelCount >= 0) {
                return merged.validateForChannelSetup(channelCount, channelBinarization, null, logger);
            }
            return merged;
        }
    }

    public static final class SpectralConfig {
        String presetName = null;
        String goal = null;
        Integer targetChannelIndex = null;
        int[] bleedThroughChannelIndexes = null;
        int[] autofluorescenceChannelIndexes = null;
        String contaminationType = null;
        String calibration = null;
        String strength = null;

        public String getPresetName() { return presetName; }
        public String getGoal() { return goal; }
        public Integer getTargetChannelIndex() { return targetChannelIndex; }
        public int[] getBleedThroughChannelIndexes() { return bleedThroughChannelIndexes; }
        public int[] getAutofluorescenceChannelIndexes() { return autofluorescenceChannelIndexes; }
        public String getContaminationType() { return contaminationType; }
        public String getCalibration() { return calibration; }
        public String getStrength() { return strength; }

        public boolean hasConfiguration() {
            return (presetName != null && !presetName.trim().isEmpty())
                    || (goal != null && !goal.trim().isEmpty())
                    || targetChannelIndex != null
                    || bleedThroughChannelIndexes != null
                    || autofluorescenceChannelIndexes != null
                    || (contaminationType != null && !contaminationType.trim().isEmpty())
                    || (calibration != null && !calibration.trim().isEmpty())
                    || (strength != null && !strength.trim().isEmpty());
        }
    }

    public static final class AggregateConfig {
        String presetName = null;
        AggregationConfig.Granularity granularity = null;
        AggregationConfig.OutputMode outputMode = null;

        public String getPresetName() { return presetName; }
        public AggregationConfig.Granularity getGranularity() { return granularity; }
        public AggregationConfig.OutputMode getOutputMode() { return outputMode; }

        public boolean hasConfiguration() {
            return (presetName != null && !presetName.trim().isEmpty())
                    || granularity != null
                    || outputMode != null;
        }
    }

    public static final class ExcelConfig {
        String presetName = null;
        Boolean includeTextureFeatures = null;
        final LinkedHashMap<String, String> fieldOverrides = new LinkedHashMap<String, String>();

        public String getPresetName() { return presetName; }
        public Boolean getIncludeTextureFeatures() { return includeTextureFeatures; }
        public void setIncludeTextureFeatures(Boolean includeTextureFeatures) {
            this.includeTextureFeatures = includeTextureFeatures;
        }
        public Map<String, String> getFieldOverrides() { return fieldOverrides; }

        public boolean hasConfiguration() {
            return (presetName != null && !presetName.trim().isEmpty())
                    || includeTextureFeatures != null
                    || !fieldOverrides.isEmpty();
        }
    }

    public static final class StatsConfig {
        String presetName = null;
        StatisticsConfig.PairedMode pairedMode = null;
        StatisticsConfig.DistributionMode distMode = null;
        StatisticsConfig.PostHocMethod postHoc = null;
        List<String> metrics = null;

        public String getPresetName() { return presetName; }
        public StatisticsConfig.PairedMode getPairedMode() { return pairedMode; }
        public StatisticsConfig.DistributionMode getDistMode() { return distMode; }
        public StatisticsConfig.PostHocMethod getPostHoc() { return postHoc; }
        public List<String> getMetrics() { return metrics; }

        public boolean hasConfiguration() {
            return (presetName != null && !presetName.trim().isEmpty())
                    || pairedMode != null
                    || distMode != null
                    || postHoc != null
                    || (metrics != null && !metrics.isEmpty());
        }
    }
}
