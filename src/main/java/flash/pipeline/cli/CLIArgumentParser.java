package flash.pipeline.cli;

import flash.pipeline.FlashCommandNames;
import flash.pipeline.analyses.StatisticsConfig;
import flash.pipeline.bin.BinField;
import flash.pipeline.analyses.wizard.AggregationConfig;
import flash.pipeline.analyses.wizard.IntensitySpatialConfig;
import flash.pipeline.deconv.wizard.DeconvPreset;
import flash.pipeline.deconv.wizard.DeconvPresetIO;
import flash.pipeline.deconv.engine.Algorithm;
import flash.pipeline.deconv.engine.DeconvParams;
import flash.pipeline.deconv.psf.PsfModel;
import flash.pipeline.deconv.psf.ScopeModality;
import flash.pipeline.presentation.PresentationTileConfig;
import ij.IJ;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

/**
 * Parses ImageJ macro option strings for CLI/headless/PyImageJ execution
 * of the FLASH pipeline.
 */
public final class CLIArgumentParser {

    private CLIArgumentParser() {}

    private static final class CliFatalException extends RuntimeException {
        CliFatalException(String msg) { super(msg); }
    }

    /**
     * Returns true if the macro options string contains CLI-mode indicators
     * (dir= or config_dir=).
     */
    public static boolean hasCliOptions(String macroOptions) {
        if (macroOptions == null || macroOptions.trim().isEmpty()) return false;
        return hasTopLevelAssignment(macroOptions, "dir")
                || hasTopLevelAssignment(macroOptions, "config_dir");
    }

    /*
     * Launch-mode detection must not invoke the strict value tokenizer: callers
     * use this method before parse(), which is the boundary that logs malformed
     * field diagnostics. This lenient probe therefore recognizes the key as
     * soon as its top-level assignment is seen, even if its value never closes.
     */
    private static boolean hasTopLevelAssignment(String options, String key) {
        int i = 0;
        while (i < options.length()) {
            while (i < options.length() && Character.isWhitespace(options.charAt(i))) i++;
            if (i >= options.length()) break;

            int fieldStart = i;
            while (i < options.length() && options.charAt(i) != '='
                    && !Character.isWhitespace(options.charAt(i))) i++;
            if (i >= options.length() || Character.isWhitespace(options.charAt(i))) {
                continue;
            }

            String field = options.substring(fieldStart, i);
            i++; // '='
            if (field.equalsIgnoreCase(key)) return true;
            if (i >= options.length() || Character.isWhitespace(options.charAt(i))) continue;

            char first = options.charAt(i);
            if (first == '[') {
                i = skipLenientBracketedValue(options, i);
            } else if (first == '"' || first == '\'') {
                i = skipLenientQuotedValue(options, i, first);
            } else {
                while (i < options.length() && !Character.isWhitespace(options.charAt(i))) i++;
            }

            // Strict parsing will diagnose any characters attached after a
            // closing delimiter. Keep them inside this token for detection.
            while (i < options.length() && !Character.isWhitespace(options.charAt(i))) i++;
        }
        return false;
    }

    private static int skipLenientQuotedValue(String options, int start, char quote) {
        for (int i = start + 1; i < options.length(); i++) {
            char ch = options.charAt(i);
            if (ch == '\\' && i + 1 < options.length() && options.charAt(i + 1) == quote
                    && hasLaterQuoteClose(options, i + 2, quote)) {
                i++;
            } else if (ch == quote) {
                return i + 1;
            }
        }
        return options.length();
    }

    private static int skipLenientBracketedValue(String options, int start) {
        boolean tagged = options.startsWith(VALUE_CODEC_MARKER, start + 1);
        int depth = 1;
        for (int i = start + 1; i < options.length(); i++) {
            char ch = options.charAt(i);
            if (tagged && ch == '~' && i + 1 < options.length()) {
                i++;
            } else if (!tagged && ch == '\\' && i + 1 < options.length()
                    && options.charAt(i + 1) == ']'
                    && hasLaterLegacyClose(options, i + 2, depth)) {
                i++;
            } else if (ch == '[') {
                depth++;
            } else if (ch == ']' && --depth == 0) {
                return i + 1;
            }
        }
        return options.length();
    }

    /**
     * Parses an ImageJ macro options string into a CLIConfig.
     * Returns null if the directory is missing.
     */
    public static CLIConfig parse(String macroOptions) {
        return parse(macroOptions, null);
    }

    static CLIConfig parse(String macroOptions, DeconvPresetIO presetIO) {
        try {
            return parseInternal(macroOptions, presetIO);
        } catch (CliFatalException e) {
            IJ.log("[CLI FATAL] " + e.getMessage());
            return null;
        }
    }

    private static CLIConfig parseInternal(String macroOptions, DeconvPresetIO presetIO) {
        if (macroOptions == null || macroOptions.trim().isEmpty()) {
            IJ.log("[CLI] Error: No macro options provided.");
            return null;
        }

        CLIConfig cfg = new CLIConfig();

        rejectMalformedBracketedValue(macroOptions, "dir");
        rejectMalformedBracketedValue(macroOptions, "config_dir");

        cfg.directory = getValue(macroOptions, "dir");
        if (cfg.directory == null) {
            cfg.directory = getValue(macroOptions, "config_dir");
        }
        if (cfg.directory == null) {
            IJ.log("[CLI] Error: 'dir' parameter is required. Use dir=[/path/to/data]");
            return null;
        }
        cfg.directory = canonicalizeExistingDirectoryArgument(cfg.directory);
        File dirFile = new File(cfg.directory);
        if (!dirFile.exists()) {
            IJ.log("[CLI] Warning: Directory does not exist: " + cfg.directory);
        } else if (!dirFile.isDirectory()) {
            IJ.log("[CLI] Warning: Path is not a directory: " + cfg.directory);
        }
        if (presetIO == null) {
            presetIO = new DeconvPresetIO(dirFile);
        }

        String[] flagNames = {
                "run_bin", "run_roi", "run_deconv", "run_split", "run_3d", "run_spatial",
                "run_distance", "run_intensity", "run_aggregate",
                "run_statistics", "run_excel", "run_spectral_decontamination", "run_repfig"
        };
        for (int i = 0; i < flagNames.length; i++) {
            if (hasBooleanFlag(macroOptions, flagNames[i])) {
                cfg.selectedAnalyses[i] = true;
            }
        }

        applyAnalysisIndex(getValue(macroOptions, "analysisIndex"), cfg);
        applyAnalysesList(getValue(macroOptions, "analyses"), cfg);

        cfg.headless = getBooleanOption(macroOptions, "headless", true);
        cfg.parallel = getBooleanOption(macroOptions, "parallel", true);
        cfg.verbose = getBooleanOption(macroOptions, "verbose", false);
        cfg.tifCache = getBooleanOption(macroOptions, "tif_cache", false);
        cfg.autoAggregate = !getBooleanOption(macroOptions, "no_aggregate", false);
        cfg.qcReport = !getBooleanOption(macroOptions, "no_qc", false);

        String overwrite = getValue(macroOptions, "overwrite");
        if (overwrite != null) {
            String lower = overwrite.toLowerCase(Locale.ROOT).trim();
            if ("skip".equals(lower)) {
                cfg.overwriteBehavior = "Skip Existing";
            } else if ("auto".equals(lower)) {
                cfg.overwriteBehavior = "Auto-Overwrite";
            } else {
                throw new CliFatalException("Unknown overwrite mode '" + overwrite
                        + "'. Expected 'auto' or 'skip'.");
            }
        }

        int maxCores = Runtime.getRuntime().availableProcessors();
        cfg.threads = parseIntOption(macroOptions, "threads", cfg.threads, 1, maxCores);
        cfg.loaderThreads = parseIntOption(macroOptions, "loader_threads", cfg.loaderThreads, 1, Integer.MAX_VALUE);
        cfg.loaderPercent = parseIntOption(macroOptions, "loader_percent", cfg.loaderPercent, 1, 100);
        cfg.gpuPermits = parseIntOption(macroOptions, "gpu_permits", cfg.gpuPermits, 0, Integer.MAX_VALUE);
        if (getValue(macroOptions, "gpu_permits") == null) {
            cfg.gpuPermits = parseIntOption(macroOptions, "gpuPermits", cfg.gpuPermits, 0, Integer.MAX_VALUE);
        }

        parseDeconvolutionOptions(macroOptions, cfg, presetIO);
        parseBinOptions(macroOptions, cfg);
        parseThreeDObjectOptions(macroOptions, cfg);
        parseSpatialOptions(macroOptions, cfg);
        parseIntensityOptions(macroOptions, cfg);
        parseSpectralOptions(macroOptions, cfg);
        parseAggregateOptions(macroOptions, cfg);
        parseExcelOptions(macroOptions, cfg);
        parseStatsOptions(macroOptions, cfg);
        parseRepfigOptions(macroOptions, cfg);
        parseDownstreamUseDeconvOptions(macroOptions, cfg);
        if (cfg.deconv.enabled || containsDeconvolutionOverrides(cfg.deconv)) {
            cfg.selectedAnalyses[2] = true;
        }
        if (cfg.bin.hasConfiguration()) {
            cfg.selectedAnalyses[0] = true;
        }
        if (cfg.object.hasConfiguration()) {
            cfg.selectedAnalyses[4] = true;
        }
        if (cfg.spatial.hasConfiguration()) {
            cfg.selectedAnalyses[5] = true;
        }
        if (cfg.intensity.hasConfiguration()) {
            cfg.selectedAnalyses[7] = true;
        }
        if (cfg.aggregate.hasConfiguration()) {
            cfg.selectedAnalyses[8] = true;
        }
        if (cfg.excel.hasConfiguration()) {
            cfg.selectedAnalyses[10] = true;
        }
        if (cfg.stats.hasConfiguration()) {
            cfg.selectedAnalyses[9] = true;
        }
        if (cfg.spectral.hasConfiguration()) {
            cfg.selectedAnalyses[11] = true;
        }
        if (cfg.repfig.hasConfiguration()) {
            cfg.selectedAnalyses[12] = true;
        }

        return cfg;
    }

    /**
     * Serializes a config back into a macro options string.
     */
    public static String serialize(CLIConfig config) {
        return config == null ? "" : config.toMacroOptions();
    }

    /**
     * Returns a multi-line usage string showing all supported parameters.
     */
    public static String usage() {
        return "FLASH CLI Usage:\n"
                + "  IJ.run(\"" + FlashCommandNames.MACRO_COMMAND + "\", \"dir=[/path/to/data] run_deconv run_3d run_intensity threads=4\")\n"
                + "\n"
                + "Required:\n"
                + "  dir=[path]            Data directory (or config_dir=)\n"
                + "\n"
                + "Outputs:\n"
                + "  Results are written under FLASH/Results/. Open FLASH/Results/START_HERE.html after a run.\n"
                + "\n"
                + "Analysis Selection (pick any):\n"
                + "  run_bin               Set Up Configuration (0)\n"
                + "  run_roi               Draw ROIs and Orientate Images (1)\n"
                + "  run_deconv            3D Deconvolution (2)\n"
                + "  run_split             Split and Merge Channels (3)\n"
                + "  run_3d                3D Object Analysis (4)\n"
                + "  run_spatial           Spatial Analysis (5)\n"
                + "  run_distance          Line Distance (6)\n"
                + "  run_intensity         Fluorescence Intensity (7)\n"
                + "  run_aggregate         Master Data Aggregation (8)\n"
                + "  run_statistics        Statistical Analysis (9)\n"
                + "  run_excel             Excel Summary Export (10)\n"
                + "  run_spectral_decontamination  Spectral Decontamination (Experimental) (11)\n"
                + "  run_repfig            Make Representative Image Figure (12)\n"
                + "\n"
                + "  Or use: analyses=0,2,4,7  or analysisIndex=2\n"
                + "\n"
                + "Options:\n"
                + "  headless[=true|false] Hide image windows (default: on)\n"
                + "  parallel[=true|false] Enable parallel processing (default: on)\n"
                + "  threads=N             Thread count (default: cores/2)\n"
                + "  verbose               Verbose logging\n"
                + "  overwrite=auto|skip   Overwrite behavior (default: auto)\n"
                + "  no_aggregate          Disable auto-aggregation (on by default)\n"
                + "  no_qc                 Disable QC report (on by default)\n"
                + "  tif_cache             Enable TIF caching\n"
                + "  loader_threads=N      Loader thread count (default: 1)\n"
                + "  loader_percent=N      Loader memory percent (default: 50)\n"
                + "  gpu_permits=N         GPU inference permits (0 = auto-detect)\n"
                + "\n"
                + "Deconvolution Options:\n"
                + "  deconv.enabled=true|false\n"
                + "  deconv.preset=confocal_puncta\n"
                + "  deconv.engine=CLIJ2|DL2|IterativeDeconvolve3D\n"
                + "  deconv.algorithm=RL|RL_TV|TIKHONOV|WIENER|LANDWEBER\n"
                + "  deconv.psf=GibsonLanni|BornWolf|Dougherty\n"
                + "  deconv.iterations=15\n"
                + "  deconv.regularization=0.01\n"
                + "  deconv.scopeModality=widefield|confocal|spinningDisk\n"
                + "  deconv.pinholeAU=1.0\n"
                + "  deconv.sampleRI=1.33\n"
                + "  deconv.mountingMedium=vectashield|prolong|cfm3|glycerol|aqueous|clarity\n"
                + "  deconv.channels=0,1,3\n"
                + "  deconv.ch0.engine=DL2  deconv.ch0.algorithm=TIKHONOV  deconv.ch1.iterations=30  (per-channel overrides)\n"
                + "  deconv.strictNyquist=true|false\n"
                + "  deconv.useCache=true|false\n"
                + "  deconv.skipPreview=true|false\n"
                + "  deconv.requireFresh=true|false  (hard-fail a headless consumer on a missing/stale routed mirror)\n"
                + "\n"
                + "Downstream Input Options:\n"
                + "  channel_names=DAPI,GFP,Iba1\n"
                + "  channel_colors=Cyan,Green,Red\n"
                + "  object_thresholds=default,1500,default\n"
                + "  particle_sizes=100-Infinity,200-1000,30-500\n"
                + "  display_min_max=None,0-4095,None\n"
                + "  intensity_thresholds=default,500,default\n"
                + "  segmentation_methods=classical,cellpose:30.0:0.4:0.0:model=cellpose_cyto3,classical\n"
                + "  segmentation token examples:\n"
                + "    enhanced_classical:thresh=120:minSize=200:maxSize=10000:morph=sphericity%3E%3D0.6\n"
                + "    stardist:<prob>:<nms>  (legacy; accepted and rewritten to canonical model= form internally)\n"
                + "    stardist:0.5:0.3:linking=5.0:gapClosing=5.0:area=20-2000:model=user_model_key\n"
                + "    cellpose:30.0:0.4:0.0:gpu=true:chan2=0:model=user_model_key\n"
                + "    legacy Cellpose positional tokens are accepted and rewritten to canonical model= form internally\n"
                + "    trained_rf:projectModel_microglia_v1:base=classical\n"
                + "  filter_presets=Default,ramified_cells_microglia_astrocytes,Default\n"
                + "  z_slice_mode=full|per_image|same_count|same_absolute\n"
                + "  bin.preset=synaptic_puncta\n"
                + "  bin.channel2_threshold=20\n"
                + "  object.preset=microglia_processes\n"
                + "  object.nuclear_marker=2\n"
                + "  spatial.preset=microglia_plaque_contact\n"
                + "  spatial.heatmaps=true\n"
                + "  spatial.texture.classfractions=true\n"
                + "  intensity.preset=threshold_puncta\n"
                + "  intensity.threshold_channel2=45\n"
                + "  intensity.spatial=true|false\n"
                + "  intensity.spatial.perslice=nullmodel,depth,glcm,scaledivergence   (per-mode, preferred)\n"
                + "  intensity.spatial.mip_analyses=patchiness,hotspots,glcm,anisotropy\n"
                + "  intensity.spatial.native3d_analyses=anisotropy3d,crossmark3d\n"
                + "  intensity.spatial.analyses=patchiness,hotspots,depth,anisotropy,crosscorr,crossmark,mi,distance_shell   (legacy)\n"
                + "  intensity.spatial.source=mip|full_stack   (legacy; ignored when per-mode tokens are set)\n"
                + "  intensity.spatial.mip=true|false\n"
                + "  intensity.spatial.native3d=true|false\n"
                + "  intensity.spatial.overlays=true|false\n"
                + "  intensity.spatial.shell_width_um=10\n"
                + "  intensity.spatial.shell_count=5\n"
                + "  intensity.spatial.tile_um=50,100,250\n"
                + "  intensity.spatial.granularity_um=2,4,8,16,32,64\n"
                + "  intensity.spatial.texture_k=4\n"
                + "  intensity.spatial.permutations=199\n"
                + "  intensity.spatial.costes_permutations=199\n"
                + "  intensity.spatial.seed=1\n"
                + "  spectral.preset=patchy_autofluorescence\n"
                + "  spectral.goal=cleaned_image|cleaned_mask|score_objects|measure_only\n"
                + "  spectral.target=0\n"
                + "  spectral.bleedthrough=1,2\n"
                + "  spectral.autofluorescence=3\n"
                + "  spectral.contamination_type=bleedthrough|broad_af|patchy_af|both|score_existing\n"
                + "  spectral.calibration=roc|manual\n"
                + "  spectral.strength=standard|aggressive\n"
                + "  aggregate.preset=Per-animal standard (raw + per-mm3)\n"
                + "  aggregate.granularity=animal|hemisphere|region|section\n"
                + "  aggregate.output=raw|normalized|both\n"
                + "  excel.preset=Figure-ready supplement\n"
                + "  excel.texture.features=true|false\n"
                + "  excel.stats_sheet=true|false   excel.per_metric_sheets=true|false\n"
                + "  excel.methods_appendix=true|false   excel.significance_stars=true|false\n"
                + "  excel.metric_detail=raw_values|summary_statistics|both\n"
                + "  excel.significance_highlight=off|yellow|p_gradient\n"
                + "  excel.header_style=standard|figure_ready\n"
                + "  stats.preset=multi_group_tukey\n"
                + "  stats.paired=off|within\n"
                + "  stats.distribution=auto|parametric|non_parametric\n"
                + "  stats.posthoc=bonferroni|tukey|dunns|none\n"
                + "  stats.metrics=Col1,Col2,Col3\n"
                + "  stats.metric_aggregation=Col1:sum,Col2:mean\n"
                + "  stats.sum_metrics=CountLikeCol   stats.mean_metrics=MeanLikeCol\n"
                + "  splitmerge.useDeconv=true|false\n"
                + "  splitmerge.applyOrientationTransforms=true|false\n"
                + "  threeD.useDeconv=true|false\n"
                + "  intensityV2.useDeconv=true|false\n"
                + "\n"
                + "Representative Figure Options (re-render a saved selection; selection itself needs headed mode):\n"
                + "  repfig.cell_size=260            repfig.dpi=300\n"
                + "  repfig.row_gap=8                repfig.column_gap=12   repfig.inner_gap=4   repfig.margin=6\n"
                + "  repfig.condition_font=15        repfig.channel_font=16\n"
                + "  repfig.scalebar=true|false      repfig.scalebar_um=100   repfig.scalebar_thickness=6\n"
                + "  repfig.scalebar_position=tl|tr|bl|br   repfig.scalebar_frac=0.9,0.9\n"
                + "  repfig.label_mode=none|stain|image|condition_image|custom\n"
                + "  repfig.label_text=[{stain}]     repfig.label_font=18   repfig.label_position=tl|tr|bl|br\n"
                + "  repfig.label_frac=0.05,0.05     repfig.color=white|black\n"
                + "  repfig.annotate_tile=true|false repfig.annotate_individual=true|false\n"
                + "  repfig.group_by=condition|animal\n"
                + "  repfig.channel_order=[DAPI,GFP,Iba1]\n"
                + "  repfig.rows=2                   (reshape the saved conditions into N rows)\n"
                + "  repfig.save_name=[Mean intensity]  (select/write a named saved representative figure)\n"
                + "\n"
                + "PyImageJ Example:\n"
                + "  ij.py.run_plugin(\"" + FlashCommandNames.MACRO_COMMAND + "\", args={'dir': '/data', 'run_deconv': True, 'threads': 4})\n"
                + "\n"
                + "Bash Example:\n"
                + "  ImageJ --headless --run \"" + FlashCommandNames.MACRO_COMMAND + "\" \"dir=[/data] run_deconv deconv.engine=CLIJ2\"\n";
    }

    private static void parseDeconvolutionOptions(String options, CLIConfig cfg, DeconvPresetIO presetIO) {
        CLIConfig.DeconvConfig deconv = cfg.deconv;

        boolean explicitEnabled = getValue(options, "deconv.enabled") != null;
        String enabled = getValue(options, "deconv.enabled");
        if (enabled != null) {
            deconv.enabled = parseBooleanValue("deconv.enabled", enabled, deconv.enabled);
        }

        String presetName = getValue(options, "deconv.preset");
        if (presetName != null && !presetName.trim().isEmpty()) {
            deconv.presetName = presetName.trim();
            applyPreset(deconv, presetName.trim(), presetIO);
            if (!explicitEnabled) {
                deconv.enabled = true;
            }
        }

        String engine = getValue(options, "deconv.engine");
        if (engine != null && !engine.trim().isEmpty()) {
            String normalized = normalizeEngine(engine);
            if (normalized != null) {
                deconv.engine = normalized;
                if (!explicitEnabled) {
                    deconv.enabled = true;
                }
            }
        }

        String algorithm = getValue(options, "deconv.algorithm");
        if (algorithm != null && !algorithm.trim().isEmpty()) {
            Algorithm parsed = parseAlgorithm(algorithm);
            if (parsed != null) {
                deconv.algorithm = parsed;
                if (!explicitEnabled) {
                    deconv.enabled = true;
                }
            }
        }

        String psf = getValue(options, "deconv.psf");
        if (psf != null && !psf.trim().isEmpty()) {
            deconv.psfModel = parsePsfModel(psf);
            if (!explicitEnabled) {
                deconv.enabled = true;
            }
        }

        String iterations = getValue(options, "deconv.iterations");
        if (iterations != null) {
            deconv.iterations = parseIntValue("deconv.iterations", iterations,
                    DeconvParams.DEFAULT_ITERATIONS, 1, 100);
            if (!explicitEnabled) {
                deconv.enabled = true;
            }
        }

        String regularization = getValue(options, "deconv.regularization");
        if (regularization != null) {
            deconv.regularization = parseDoubleValue("deconv.regularization", regularization,
                    DeconvParams.DEFAULT_REGULARIZATION, 0.0, 0.1);
            if (!explicitEnabled) {
                deconv.enabled = true;
            }
        }

        String modality = getValue(options, "deconv.scopeModality");
        if (modality != null && !modality.trim().isEmpty()) {
            deconv.scopeModality = parseScopeModality(modality);
            if (!explicitEnabled) {
                deconv.enabled = true;
            }
        }

        String pinhole = getValue(options, "deconv.pinholeAU");
        if (pinhole != null) {
            deconv.pinholeAiryUnits = Double.valueOf(parseDoubleValue(
                    "deconv.pinholeAU", pinhole, 1.0, 0.0, Double.MAX_VALUE));
            if (!explicitEnabled) {
                deconv.enabled = true;
            }
        }

        String sampleRi = getValue(options, "deconv.sampleRI");
        if (sampleRi != null) {
            deconv.sampleRI = Double.valueOf(parseDoubleValue(
                    "deconv.sampleRI", sampleRi, Double.NaN, 0.0, Double.MAX_VALUE));
            if (!explicitEnabled) {
                deconv.enabled = true;
            }
        }

        String mountingMedium = getValue(options, "deconv.mountingMedium");
        if (mountingMedium != null && !mountingMedium.trim().isEmpty()) {
            deconv.mountingMedium = mountingMedium.trim();
            if (!explicitEnabled) {
                deconv.enabled = true;
            }
        }

        String channels = getValue(options, "deconv.channels");
        if (channels != null && !channels.trim().isEmpty()) {
            deconv.channels = parseIntList("deconv.channels", channels);
            if (!explicitEnabled) {
                deconv.enabled = true;
            }
        }

        String strictNyquist = getValue(options, "deconv.strictNyquist");
        if (strictNyquist != null) {
            deconv.strictNyquist = parseBooleanValue("deconv.strictNyquist", strictNyquist, false);
            if (!explicitEnabled) {
                deconv.enabled = true;
            }
        }

        String useCache = getValue(options, "deconv.useCache");
        if (useCache != null) {
            deconv.useCache = parseBooleanValue("deconv.useCache", useCache, true);
            if (!explicitEnabled) {
                deconv.enabled = true;
            }
        }

        String skipPreview = getValue(options, "deconv.skipPreview");
        if (skipPreview != null) {
            deconv.skipPreview = parseBooleanValue("deconv.skipPreview", skipPreview, false);
            if (!explicitEnabled) {
                deconv.enabled = true;
            }
        }

        // Stage 17 preflight strictness. Independent of deconv.enabled: it governs how a downstream
        // consumer reacts to a missing/stale routed mirror, so it must NOT flip deconv.enabled on.
        String requireFresh = getValue(options, "deconv.requireFresh");
        if (requireFresh != null) {
            deconv.requireFresh = parseBooleanValue("deconv.requireFresh", requireFresh, false);
        }

        // Per-channel overrides: deconv.ch<N>.engine|algorithm|psf|iterations|regularization.
        // Anything not set per channel falls back to the global value above.
        for (int ch = 0; ch < MAX_PER_CHANNEL_DECONV; ch++) {
            String prefix = "deconv.ch" + ch + ".";
            String chEngine = getValue(options, prefix + "engine");
            String chAlgorithm = getValue(options, prefix + "algorithm");
            String chPsf = getValue(options, prefix + "psf");
            String chIterations = getValue(options, prefix + "iterations");
            String chRegularization = getValue(options, prefix + "regularization");
            if (chEngine == null && chAlgorithm == null && chPsf == null
                    && chIterations == null && chRegularization == null) {
                continue;
            }
            CLIConfig.DeconvConfig.ChannelOverride override =
                    new CLIConfig.DeconvConfig.ChannelOverride();
            if (chEngine != null && !chEngine.trim().isEmpty()) {
                override.engine = normalizeEngine(chEngine);
            }
            if (chAlgorithm != null && !chAlgorithm.trim().isEmpty()) {
                override.algorithm = parseAlgorithm(chAlgorithm);
            }
            if (chPsf != null && !chPsf.trim().isEmpty()) {
                override.psfModel = parsePsfModel(chPsf);
            }
            if (chIterations != null) {
                override.iterations = Integer.valueOf(parseIntValue(prefix + "iterations",
                        chIterations, DeconvParams.DEFAULT_ITERATIONS, 1, 100));
            }
            if (chRegularization != null) {
                override.regularization = Double.valueOf(parseDoubleValue(prefix + "regularization",
                        chRegularization, DeconvParams.DEFAULT_REGULARIZATION, 0.0, 0.1));
            }
            deconv.perChannel.put(Integer.valueOf(ch), override);
            if (!explicitEnabled) {
                deconv.enabled = true;
            }
        }
    }

    private static final int MAX_PER_CHANNEL_DECONV = 64;

    private static boolean containsDeconvolutionOverrides(CLIConfig.DeconvConfig deconv) {
        return (deconv.presetName != null && !deconv.presetName.trim().isEmpty())
                || deconv.engine != null
                || deconv.algorithm != null
                || deconv.psfModel != PsfModel.GIBSON_LANNI
                || deconv.scopeModality != null
                || deconv.pinholeAiryUnits != null
                || deconv.sampleRI != null
                || (deconv.mountingMedium != null && !deconv.mountingMedium.trim().isEmpty())
                || (deconv.channels != null && deconv.channels.length > 0)
                || deconv.iterations != DeconvParams.DEFAULT_ITERATIONS
                || Double.compare(deconv.regularization, DeconvParams.DEFAULT_REGULARIZATION) != 0
                || deconv.strictNyquist
                || !deconv.useCache
                || deconv.skipPreview
                || !deconv.perChannel.isEmpty();
    }

    private static void parseBinOptions(String options, CLIConfig cfg) {
        CLIConfig.CreateBinConfig bin = cfg.bin;

        String presetName = getValue(options, "bin.preset");
        if (presetName != null && !presetName.trim().isEmpty()) {
            bin.presetName = presetName.trim();
        }

        putBinFieldIfPresent(cfg, BinField.CHANNEL_NAMES, getValue(options, "channel_names"));
        putBinFieldIfPresent(cfg, BinField.CHANNEL_COLORS, getValue(options, "channel_colors"));
        putBinFieldIfPresent(cfg, BinField.OBJECT_THRESHOLDS, getValue(options, "object_thresholds"));
        putBinFieldIfPresent(cfg, BinField.PARTICLE_SIZES, getValue(options, "particle_sizes"));
        putBinFieldIfPresent(cfg, BinField.DISPLAY_MIN_MAX, getValue(options, "display_min_max"));
        putBinFieldIfPresent(cfg, BinField.INTENSITY_THRESHOLDS, getValue(options, "intensity_thresholds"));
        putBinFieldIfPresent(cfg, BinField.SEGMENTATION_METHODS, getValue(options, "segmentation_methods"));
        putBinFieldIfPresent(cfg, BinField.FILTER_PRESETS, getValue(options, "filter_presets"));
        putBinFieldIfPresent(cfg, BinField.Z_SLICE, getValue(options, "z_slice_mode"));

        for (int channel = 1; channel <= 32; channel++) {
            putIfPresent(bin.names, channel, getValue(options, "bin.channel" + channel + "_name"));
            putIfPresent(bin.colors, channel, getValue(options, "bin.channel" + channel + "_color"));
            putIfPresent(bin.objectThresholds, channel, getValue(options, "bin.channel" + channel + "_threshold"));
            putIfPresent(bin.objectThresholds, channel, getValue(options, "bin.channel" + channel + "_object_threshold"));
            putIfPresent(bin.sizes, channel, getValue(options, "bin.channel" + channel + "_size"));
            putIfPresent(bin.minmax, channel, getValue(options, "bin.channel" + channel + "_minmax"));
            putIfPresent(bin.intensityThresholds, channel, getValue(options, "bin.channel" + channel + "_intensity_threshold"));
            putIfPresent(bin.filterPresets, channel, getValue(options, "bin.channel" + channel + "_filter"));
            putIfPresent(bin.filterPresets, channel, getValue(options, "bin.channel" + channel + "_filter_preset"));
            putIfPresent(bin.segmentationMethods, channel, getValue(options, "bin.channel" + channel + "_segmentation"));
        }
    }

    private static void parseThreeDObjectOptions(String options, CLIConfig cfg) {
        CLIConfig.ThreeDObjectConfig object = cfg.object;

        String presetName = getValue(options, "object.preset");
        if (presetName != null && !presetName.trim().isEmpty()) {
            object.presetName = presetName.trim();
        }

        object.includeRegions = parseStringList(firstValue(options,
                "object.regions", "object.region", "object.include_regions", "object.includeRegions",
                "threeD.regions", "threeD.region", "threeD.include_regions", "threeD.includeRegions"));
        object.excludeRegions = parseStringList(firstValue(options,
                "object.exclude_regions", "object.excludeRegions",
                "threeD.exclude_regions", "threeD.excludeRegions"));

        String doVolumetric = getValue(options, "object.doVolumetric");
        if (doVolumetric == null) {
            doVolumetric = getValue(options, "object.do_volumetric");
        }
        if (doVolumetric != null) {
            object.doVolumetric = Boolean.valueOf(parseBooleanValue("object.doVolumetric", doVolumetric, false));
        }

        String doCpc = getValue(options, "object.doCpc");
        if (doCpc == null) {
            doCpc = getValue(options, "object.do_cpc");
        }
        if (doCpc != null) {
            object.doCpc = Boolean.valueOf(parseBooleanValue("object.doCpc", doCpc, false));
        }

        String doIntensityColoc = getValue(options, "object.doIntensityColoc");
        if (doIntensityColoc == null) {
            doIntensityColoc = getValue(options, "object.do_intensity_coloc");
        }
        if (doIntensityColoc == null) {
            doIntensityColoc = getValue(options, "object.intensity_colocalization");
        }
        if (doIntensityColoc != null) {
            object.doIntensityColoc = Boolean.valueOf(parseBooleanValue(
                    "object.doIntensityColoc", doIntensityColoc, false));
        }

        String doBBOverlap = getValue(options, "object.doBBOverlap");
        if (doBBOverlap == null) {
            doBBOverlap = getValue(options, "object.do_bb_overlap");
        }
        if (doBBOverlap != null) {
            object.doBBOverlap = Boolean.valueOf(parseBooleanValue(
                    "object.doBBOverlap", doBBOverlap, false));
        }

        String doBBCpc = getValue(options, "object.doBBCpc");
        if (doBBCpc == null) {
            doBBCpc = getValue(options, "object.do_bb_cpc");
        }
        if (doBBCpc != null) {
            object.doBBCpc = Boolean.valueOf(parseBooleanValue(
                    "object.doBBCpc", doBBCpc, false));
        }

        String doBBVol = getValue(options, "object.doBBVol");
        if (doBBVol == null) {
            doBBVol = getValue(options, "object.do_bb_vol");
        }
        if (doBBVol != null) {
            object.doBBVol = Boolean.valueOf(parseBooleanValue(
                    "object.doBBVol", doBBVol, false));
        }

        object.doRadialProfile = parseNullableBoolean(options, object.doRadialProfile,
                "object.oip.radial", "object.oip_radial", "object.oip.do_radial",
                "object.doRadialProfile", "object.do_radial_profile");
        object.doMarginalProfile = parseNullableBoolean(options, object.doMarginalProfile,
                "object.oip.marginal", "object.oip_marginal", "object.oip.do_marginal",
                "object.doMarginalProfile", "object.do_marginal_profile");
        object.doPrincipalAxisProfile = parseNullableBoolean(options, object.doPrincipalAxisProfile,
                "object.oip.principal_axis", "object.oip.principalAxis",
                "object.oip_principal_axis", "object.doPrincipalAxisProfile",
                "object.do_principal_axis_profile");
        object.doAngularProfile = parseNullableBoolean(options, object.doAngularProfile,
                "object.oip.angular", "object.oip_angular", "object.oip.do_angular",
                "object.doAngularProfile", "object.do_angular_profile");
        object.doShellColoc = parseNullableBoolean(options, object.doShellColoc,
                "object.oip.shell", "object.oip_shell", "object.oip.do_shell",
                "object.doShellColoc", "object.do_shell_coloc");
        object.doWithinBoxCorr = parseNullableBoolean(options, object.doWithinBoxCorr,
                "object.oip.within_box", "object.oip.withinBox", "object.oip_within_box",
                "object.doWithinBoxCorr", "object.do_within_box_corr");
        object.oipGenerateFigures = parseNullableBoolean(options, object.oipGenerateFigures,
                "object.oip.figures", "object.oip.generate_figures", "object.oipGenerateFigures",
                "object.oip_generate_figures");

        String oipObjectOnly = firstValue(options,
                "object.oip.object_only", "object.oip.objectOnly", "object.oip_only_object",
                "object.oipObjectOnly");
        if (oipObjectOnly != null) {
            object.oipRegion = parseBooleanValue("object.oip.object_only", oipObjectOnly, false)
                    ? "OBJECT_VOXELS" : "WHOLE_BOX";
        }
        String oipRegion = firstValue(options,
                "object.oip.region", "object.oip_region", "object.oipRegion");
        if (oipRegion != null) {
            object.oipRegion = normalizeOipRegion(oipRegion);
        }
        String oipNorm = firstValue(options,
                "object.oip.norm", "object.oip.intensity_norm", "object.oipIntensityNorm",
                "object.oip_intensity_norm");
        if (oipNorm != null) {
            object.oipIntensityNorm = normalizeOipIntensityNorm(oipNorm);
        }
        String radialBins = firstValue(options,
                "object.oip.radial_bins", "object.oip.radialBins", "object.oip_radial_bins");
        if (radialBins != null) {
            object.oipRadialBins = Integer.valueOf(parseIntValue(
                    "object.oip.radial_bins", radialBins, 20, 1, Integer.MAX_VALUE));
        }
        String angularBins = firstValue(options,
                "object.oip.angular_bins", "object.oip.angularBins", "object.oip_angular_bins");
        if (angularBins != null) {
            object.oipAngularBins = Integer.valueOf(parseIntValue(
                    "object.oip.angular_bins", angularBins, 12, 1, Integer.MAX_VALUE));
        }
        String shells = firstValue(options,
                "object.oip.shells", "object.oip_shells");
        if (shells != null) {
            object.oipShells = Integer.valueOf(parseIntValue(
                    "object.oip.shells", shells, 3, 1, Integer.MAX_VALUE));
        }
        String resampleN = firstValue(options,
                "object.oip.resample_n", "object.oip.resampleN", "object.oip_resample_n");
        if (resampleN != null) {
            object.oipResampleN = Integer.valueOf(parseIntValue(
                    "object.oip.resample_n", resampleN, 50, 2, Integer.MAX_VALUE));
        }
        String boxPadPct = firstValue(options,
                "object.oip.box_pad_pct", "object.oip.boxPadPct", "object.oip_box_pad_pct");
        if (boxPadPct != null) {
            object.oipBoxPadPct = Double.valueOf(parseDoubleValue(
                    "object.oip.box_pad_pct", boxPadPct, 0.0, 0.0, Double.MAX_VALUE));
        }
        String ringThresholdPct = firstValue(options,
                "object.oip.ring_threshold_pct", "object.oip.ringThresholdPct",
                "object.oip_ring_threshold_pct");
        if (ringThresholdPct != null) {
            object.oipRingThresholdPct = Double.valueOf(parseDoubleValue(
                    "object.oip.ring_threshold_pct", ringThresholdPct, 50.0, 0.0, 100.0));
        }

        String extract = getValue(options, "object.extractProcessLength");
        if (extract == null) {
            extract = getValue(options, "object.extract_process_length");
        }
        if (extract != null) {
            object.extractProcessLength = Boolean.valueOf(parseBooleanValue("object.extractProcessLength", extract, false));
        }

        String spatial = getValue(options, "object.runSpatial");
        if (spatial == null) {
            spatial = getValue(options, "object.run_spatial");
        }
        if (spatial != null) {
            object.runSpatial = Boolean.valueOf(parseBooleanValue("object.runSpatial", spatial, false));
        }

        String centroid = getValue(options, "object.classicalCentroidFiltering");
        if (centroid == null) {
            centroid = getValue(options, "object.classical_centroid_filtering");
        }
        if (centroid != null) {
            object.classicalCentroidFiltering = Boolean.valueOf(parseBooleanValue(
                    "object.classicalCentroidFiltering", centroid, false));
        }

        String threshold = getValue(options, "object.colocThreshold");
        if (threshold == null) {
            threshold = getValue(options, "object.coloc_threshold");
        }
        if (threshold != null) {
            object.colocThresholdPercent = Double.valueOf(parseDoubleValue(
                    "object.colocThreshold", threshold, 30.0, 0.0, 100.0));
        }

        String bbThreshold = getValue(options, "object.bbColocThreshold");
        if (bbThreshold == null) {
            bbThreshold = getValue(options, "object.bb_coloc_threshold");
        }
        if (bbThreshold != null) {
            object.bbColocThresholdPercent = Double.valueOf(parseDoubleValue(
                    "object.bbColocThreshold", bbThreshold, 30.0, 0.0, 100.0));
        }

        String nuclearMarker = getValue(options, "object.nuclear_marker");
        if (nuclearMarker == null) {
            nuclearMarker = getValue(options, "object.nuclearMarker");
        }
        if (nuclearMarker != null) {
            int oneBased = parseIntValue("object.nuclear_marker", nuclearMarker, 1, 1, 32);
            object.nuclearMarkerIndex = Integer.valueOf(oneBased - 1);
        }
    }

    private static void parseSpatialOptions(String options, CLIConfig cfg) {
        CLIConfig.SpatialConfig spatial = cfg.spatial;

        String presetName = getValue(options, "spatial.preset");
        if (presetName != null && !presetName.trim().isEmpty()) {
            spatial.presetName = presetName.trim();
        }

        spatial.doDistances = parseNullableBoolean(options, "spatial.distances", spatial.doDistances);
        spatial.doSpatialStats = parseNullableBoolean(options, "spatial.spatialStats", spatial.doSpatialStats);
        spatial.doSpatialStats = parseNullableBoolean(options, "spatial.stats", spatial.doSpatialStats);
        spatial.doVolColoc = parseNullableBoolean(options, "spatial.volColoc", spatial.doVolColoc);
        spatial.doVolColoc = parseNullableBoolean(options, "spatial.volumetric", spatial.doVolColoc);
        spatial.doVolColoc = parseNullableBoolean(options, "spatial.vol_coloc", spatial.doVolColoc);
        spatial.doCpc = parseNullableBoolean(options, "spatial.cpc", spatial.doCpc);
        spatial.doBBOverlap = parseNullableBoolean(options, "spatial.bb_overlap", spatial.doBBOverlap);
        spatial.doBBOverlap = parseNullableBoolean(options, "spatial.bbOverlap", spatial.doBBOverlap);
        spatial.doBBCpc = parseNullableBoolean(options, "spatial.bb_cpc", spatial.doBBCpc);
        spatial.doBBCpc = parseNullableBoolean(options, "spatial.bbCpc", spatial.doBBCpc);
        spatial.doBBVol = parseNullableBoolean(options, "spatial.bb_vol", spatial.doBBVol);
        spatial.doBBVol = parseNullableBoolean(options, "spatial.bbVol", spatial.doBBVol);
        String bbThreshold = getValue(options, "spatial.bb_threshold");
        if (bbThreshold == null) {
            bbThreshold = getValue(options, "spatial.bbThreshold");
        }
        if (bbThreshold != null) {
            spatial.bbColocThresholdPercent = Double.valueOf(parseDoubleValue(
                    "spatial.bb_threshold", bbThreshold, 30.0, 0.0, 100.0));
        }
        spatial.doVoronoi = parseNullableBoolean(options, "spatial.voronoi", spatial.doVoronoi);
        spatial.doHeatmaps = parseNullableBoolean(options, "spatial.heatmaps", spatial.doHeatmaps);
        spatial.doPhenotyping = parseNullableBoolean(options, "spatial.phenotyping", spatial.doPhenotyping);
        spatial.doPhenotyping = parseNullableBoolean(options, "spatial.clustering", spatial.doPhenotyping);
        spatial.do2DMorphology = parseNullableBoolean(options, "spatial.2d", spatial.do2DMorphology);
        spatial.do2DMorphology = parseNullableBoolean(options, "spatial.morphology2d", spatial.do2DMorphology);
        spatial.do3DMorphology = parseNullableBoolean(options, "spatial.3d", spatial.do3DMorphology);
        spatial.do3DMorphology = parseNullableBoolean(options, "spatial.morphology3d", spatial.do3DMorphology);
        spatial.doCompositeIndices = parseNullableBoolean(options, "spatial.complex", spatial.doCompositeIndices);
        spatial.doPopMorphometrics = parseNullableBoolean(options, "spatial.population", spatial.doPopMorphometrics);
        spatial.doSpatialMorphometrics = parseNullableBoolean(options, "spatial.spatialMorph", spatial.doSpatialMorphometrics);
        spatial.doSpatialMorphometrics = parseNullableBoolean(options, "spatial.spatial_morph", spatial.doSpatialMorphometrics);
        spatial.textureGlcm = parseNullableBoolean(options, "spatial.texture.glcm", spatial.textureGlcm);
        spatial.textureFractal = parseNullableBoolean(options, "spatial.texture.fractal", spatial.textureFractal);
        spatial.textureClass = parseNullableBoolean(options, "spatial.texture.class", spatial.textureClass);
        spatial.textureClassFractions = parseNullableBoolean(
                options, "spatial.texture.classfractions", spatial.textureClassFractions);
        spatial.textureNative3D = parseNullableBoolean(options, "spatial.texture.native3d", spatial.textureNative3D);

        String textureK = getValue(options, "spatial.texture.k");
        if (textureK != null) {
            spatial.textureClassK = Integer.valueOf(parseIntValue("spatial.texture.k", textureK, 4, 2, 10));
        }

        String bandwidth = getValue(options, "spatial.kdeBandwidth");
        if (bandwidth == null) {
            bandwidth = getValue(options, "spatial.kde_bandwidth");
        }
        if (bandwidth != null) {
            spatial.kdeBandwidth = Double.valueOf(parseDoubleValue("spatial.kdeBandwidth", bandwidth,
                    0.0, 0.0, Double.MAX_VALUE));
        }
        String heatmapLut = getValue(options, "spatial.heatmapLut");
        if (heatmapLut == null) {
            heatmapLut = getValue(options, "spatial.heatmap_lut");
        }
        if (heatmapLut != null && !heatmapLut.trim().isEmpty()) {
            spatial.heatmapLut = heatmapLut.trim();
        }
        String clusterK = getValue(options, "spatial.clusterK");
        if (clusterK == null) {
            clusterK = getValue(options, "spatial.cluster_k");
        }
        if (clusterK != null) {
            spatial.clusterK = Integer.valueOf(parseIntValue("spatial.clusterK", clusterK, 0, 0, 10));
        }

        enforceSpatialDependencies(spatial);
    }

    private static void parseIntensityOptions(String options, CLIConfig cfg) {
        CLIConfig.IntensityConfig intensity = cfg.intensity;

        String presetName = getValue(options, "intensity.preset");
        if (presetName != null && !presetName.trim().isEmpty()) {
            intensity.presetName = presetName.trim();
        }

        intensity.includeRegions = parseStringList(firstValue(options,
                "intensity.regions", "intensity.region", "intensity.include_regions", "intensity.includeRegions"));
        intensity.excludeRegions = parseStringList(firstValue(options,
                "intensity.exclude_regions", "intensity.excludeRegions"));

        for (int channel = 1; channel <= 32; channel++) {
            putIfPresent(intensity.thresholds, channel,
                    getValue(options, "intensity.threshold_channel" + channel));
            putIfPresent(intensity.thresholds, channel,
                    getValue(options, "intensity.channel" + channel + "_threshold"));
        }

        String spatialEnabled = getValue(options, "intensity.spatial");
        if (spatialEnabled != null) {
            intensity.spatialEnabled = Boolean.valueOf(parseBooleanValue(
                    "intensity.spatial", spatialEnabled, false));
        }

        String analyses = getValue(options, "intensity.spatial.analyses");
        if (analyses != null) {
            intensity.spatialAnalyses = IntensitySpatialConfig.parseAnalysisList(analyses);
        }

        // Per-mode selection tokens (preferred). When any is present it wins over the
        // legacy analyses+source+native3d flags during merge.
        String perslice = getValue(options, "intensity.spatial.perslice");
        if (perslice != null) {
            intensity.spatialPerSliceAnalyses = IntensitySpatialConfig.parseAnalysisList(perslice);
        }
        String mipAnalyses = getValue(options, "intensity.spatial.mip_analyses");
        if (mipAnalyses != null) {
            intensity.spatialMipAnalyses = IntensitySpatialConfig.parseAnalysisList(mipAnalyses);
        }
        String native3dAnalyses = getValue(options, "intensity.spatial.native3d_analyses");
        if (native3dAnalyses != null) {
            intensity.spatial3dAnalyses = IntensitySpatialConfig.parseAnalysisList(native3dAnalyses);
        }

        intensity.spatialMipEnabled = parseNullableBoolean(
                options, "intensity.spatial.mip", intensity.spatialMipEnabled);
        String sourceMode = getValue(options, "intensity.spatial.source");
        if (sourceMode == null) {
            sourceMode = getValue(options, "intensity.spatial.source_mode");
        }
        if (sourceMode != null) {
            intensity.spatialSourceMode = IntensitySpatialConfig.SpatialSourceMode.parse(
                    sourceMode, intensity.spatialSourceMode);
        }
        intensity.spatialNative3dEnabled = parseNullableBoolean(
                options, "intensity.spatial.native3d", intensity.spatialNative3dEnabled);
        intensity.spatialOverlaysEnabled = parseNullableBoolean(
                options, "intensity.spatial.overlays", intensity.spatialOverlaysEnabled);

        String shellWidth = getValue(options, "intensity.spatial.shell_width_um");
        if (shellWidth == null) {
            shellWidth = getValue(options, "intensity.spatial.shellWidthUm");
        }
        if (shellWidth != null) {
            intensity.spatialShellWidthUm = Double.valueOf(parseDoubleValue(
                    "intensity.spatial.shell_width_um", shellWidth,
                    IntensitySpatialConfig.DEFAULT_SHELL_WIDTH_UM, 0.000001, Double.MAX_VALUE));
        }

        String shellCount = getValue(options, "intensity.spatial.shell_count");
        if (shellCount == null) {
            shellCount = getValue(options, "intensity.spatial.shellCount");
        }
        if (shellCount != null) {
            intensity.spatialShellCount = Integer.valueOf(parseIntValue(
                    "intensity.spatial.shell_count", shellCount,
                    IntensitySpatialConfig.DEFAULT_SHELL_COUNT, 1, Integer.MAX_VALUE));
        }

        String tileUm = getValue(options, "intensity.spatial.tile_um");
        if (tileUm == null) {
            tileUm = getValue(options, "intensity.spatial.tileScalesUm");
        }
        if (tileUm != null) {
            intensity.spatialTileScalesUm = IntensitySpatialConfig.parseDoubleList(
                    tileUm, IntensitySpatialConfig.DEFAULT_TILE_SCALES_UM);
        }

        String granularity = getValue(options, "intensity.spatial.granularity_um");
        if (granularity == null) {
            granularity = getValue(options, "intensity.spatial.granularityScalesUm");
        }
        if (granularity != null) {
            intensity.spatialGranularityScalesUm = IntensitySpatialConfig.parseDoubleList(
                    granularity, IntensitySpatialConfig.DEFAULT_GRANULARITY_SCALES_UM);
        }

        String depthBin = getValue(options, "intensity.spatial.depth_bin_um");
        if (depthBin == null) {
            depthBin = getValue(options, "intensity.spatial.depthBinWidthUm");
        }
        if (depthBin != null) {
            intensity.spatialDepthBinWidthUm = Double.valueOf(parseDoubleValue(
                    "intensity.spatial.depth_bin_um", depthBin,
                    IntensitySpatialConfig.DEFAULT_DEPTH_BIN_WIDTH_UM, 0.000001, Double.MAX_VALUE));
        }

        String rimDepth = getValue(options, "intensity.spatial.rim_depth_um");
        if (rimDepth == null) {
            rimDepth = getValue(options, "intensity.spatial.rimDepthUm");
        }
        if (rimDepth != null) {
            intensity.spatialRimDepthUm = Double.valueOf(parseDoubleValue(
                    "intensity.spatial.rim_depth_um", rimDepth,
                    IntensitySpatialConfig.DEFAULT_RIM_DEPTH_UM, 0.000001, Double.MAX_VALUE));
        }

        String textureK = getValue(options, "intensity.spatial.texture_k");
        if (textureK == null) {
            textureK = getValue(options, "intensity.spatial.textureClassCount");
        }
        if (textureK != null) {
            intensity.spatialTextureClassCount = Integer.valueOf(parseIntValue(
                    "intensity.spatial.texture_k", textureK,
                    IntensitySpatialConfig.DEFAULT_TEXTURE_CLASS_COUNT, 1, Integer.MAX_VALUE));
        }

        String permutations = getValue(options, "intensity.spatial.permutations");
        if (permutations != null) {
            intensity.spatialPermutations = Integer.valueOf(parseIntValue(
                    "intensity.spatial.permutations", permutations,
                    IntensitySpatialConfig.DEFAULT_PERMUTATIONS, 0, Integer.MAX_VALUE));
        }

        String costesPermutations = getValue(options, "intensity.spatial.costes_permutations");
        if (costesPermutations == null) {
            costesPermutations = getValue(options, "intensity.spatial.costesPermutations");
        }
        if (costesPermutations != null) {
            intensity.spatialCostesPermutations = Integer.valueOf(parseIntValue(
                    "intensity.spatial.costes_permutations", costesPermutations,
                    IntensitySpatialConfig.DEFAULT_COSTES_PERMUTATIONS, 0,
                    IntensitySpatialConfig.MAX_COSTES_PERMUTATIONS));
        }

        String seed = getValue(options, "intensity.spatial.seed");
        if (seed != null) {
            intensity.spatialSeed = Long.valueOf(parseLongValue(
                    "intensity.spatial.seed", seed, IntensitySpatialConfig.DEFAULT_SEED));
        }

        String failurePolicy = getValue(options, "intensity.spatial.failure_policy");
        if (failurePolicy == null) {
            failurePolicy = getValue(options, "intensity.spatial.failurePolicy");
        }
        if (failurePolicy != null) {
            intensity.spatialFailurePolicy = IntensitySpatialConfig.FailurePolicy.parse(failurePolicy);
        }
    }

    private static void parseSpectralOptions(String options, CLIConfig cfg) {
        CLIConfig.SpectralConfig spectral = cfg.spectral;

        String presetName = getValue(options, "spectral.preset");
        if (presetName != null && !presetName.trim().isEmpty()) {
            spectral.presetName = presetName.trim();
        }

        String goal = getValue(options, "spectral.goal");
        if (goal != null && !goal.trim().isEmpty()) {
            spectral.goal = goal.trim();
        }

        String target = getValue(options, "spectral.target");
        if (target != null && !target.trim().isEmpty()) {
            spectral.targetChannelIndex = Integer.valueOf(parseIntValue("spectral.target", target, 0, 0, 32));
        }

        String bleed = getValue(options, "spectral.bleedthrough");
        if (bleed == null) {
            bleed = getValue(options, "spectral.bleed_through");
        }
        if (bleed != null && !bleed.trim().isEmpty()) {
            spectral.bleedThroughChannelIndexes = parseIntList("spectral.bleedthrough", bleed);
        }

        String autofluorescence = getValue(options, "spectral.autofluorescence");
        if (autofluorescence != null && !autofluorescence.trim().isEmpty()) {
            spectral.autofluorescenceChannelIndexes =
                    parseIntList("spectral.autofluorescence", autofluorescence);
        }

        String contaminationType = getValue(options, "spectral.contamination_type");
        if (contaminationType == null) {
            contaminationType = getValue(options, "spectral.contaminationType");
        }
        if (contaminationType != null && !contaminationType.trim().isEmpty()) {
            spectral.contaminationType = contaminationType.trim();
        }

        String calibration = getValue(options, "spectral.calibration");
        if (calibration != null && !calibration.trim().isEmpty()) {
            spectral.calibration = calibration.trim();
        }

        String strength = getValue(options, "spectral.strength");
        if (strength != null && !strength.trim().isEmpty()) {
            spectral.strength = strength.trim();
        }
    }

    private static void parseAggregateOptions(String options, CLIConfig cfg) {
        CLIConfig.AggregateConfig aggregate = cfg.aggregate;

        String presetName = getValue(options, "aggregate.preset");
        if (presetName != null && !presetName.trim().isEmpty()) {
            aggregate.presetName = presetName.trim();
        }

        String granularity = getValue(options, "aggregate.granularity");
        if (granularity != null && !granularity.trim().isEmpty()) {
            aggregate.granularity = AggregationConfig.Granularity.parse(
                    granularity, AggregationConfig.Granularity.ANIMAL);
        }

        String output = getValue(options, "aggregate.output");
        if (output != null && !output.trim().isEmpty()) {
            aggregate.outputMode = AggregationConfig.OutputMode.parse(
                    output, AggregationConfig.OutputMode.RAW_AND_PERMM3);
        }
    }

    private static final String[] EXCEL_FIELD_KEYS = {
            "conditions_sheet",
            "data_summary_sheet",
            "per_metric_sheets",
            "metric_sheets",
            "stats_sheet",
            "statistics_sheet",
            "metric_detail",
            "metric_sheet_detail",
            "methods_appendix",
            "methods",
            "significance_highlight",
            "highlight",
            "header_style",
            "significance_stars",
            "stars"
    };

    private static void parseExcelOptions(String options, CLIConfig cfg) {
        CLIConfig.ExcelConfig excel = cfg.excel;

        String presetName = getValue(options, "excel.preset");
        if (presetName != null && !presetName.trim().isEmpty()) {
            excel.presetName = presetName.trim();
        }

        String textureFeatures = getValue(options, "excel.texture.features");
        if (textureFeatures != null) {
            excel.includeTextureFeatures = Boolean.valueOf(parseBooleanValue(
                    "excel.texture.features", textureFeatures, false));
        }

        for (String key : EXCEL_FIELD_KEYS) {
            String value = getValue(options, "excel." + key);
            if (value != null && !value.trim().isEmpty()) {
                excel.fieldOverrides.put(key, value.trim());
            }
        }
    }

    /**
     * Parses Statistics Wizard options:
     * <ul>
     *   <li>{@code stats.preset=&lt;name&gt;} — load a saved {@code StatisticsPreset}.</li>
     *   <li>{@code stats.paired=off|within} ({@code region}/{@code session} accepted as synonyms).</li>
     *   <li>{@code stats.distribution=auto|parametric|non_parametric}
     *       (alias of {@code AUTO} / {@code ASSUME_NORMAL} / {@code ASSUME_SKEWED}).</li>
     *   <li>{@code stats.posthoc=bonferroni|tukey|dunns|none}.</li>
     *   <li>{@code stats.metrics=col1,col2,col3} — comma-separated metric column allow-list.</li>
     *   <li>{@code stats.metric_aggregation=col1:sum,col2:mean} — explicit nested-row collapse modes.</li>
     * </ul>
     * Explicit overrides apply on top of any preset; bad enum tokens fall back to defaults.
     */
    private static void parseStatsOptions(String options, CLIConfig cfg) {
        CLIConfig.StatsConfig stats = cfg.stats;

        String preset = getValue(options, "stats.preset");
        if (preset != null && !preset.trim().isEmpty()) {
            stats.presetName = preset.trim();
        }

        String paired = getValue(options, "stats.paired");
        if (paired != null && !paired.trim().isEmpty()) {
            stats.pairedMode = StatisticsConfig.PairedMode.parse(
                    paired, StatisticsConfig.PairedMode.OFF);
        }

        String dist = getValue(options, "stats.distribution");
        if (dist != null && !dist.trim().isEmpty()) {
            stats.distMode = StatisticsConfig.DistributionMode.parse(
                    dist, StatisticsConfig.DistributionMode.AUTO);
        }

        String posthoc = getValue(options, "stats.posthoc");
        if (posthoc != null && !posthoc.trim().isEmpty()) {
            stats.postHoc = StatisticsConfig.PostHocMethod.parse(
                    posthoc, StatisticsConfig.PostHocMethod.BONFERRONI);
        }

        String metrics = getValue(options, "stats.metrics");
        if (metrics != null && !metrics.trim().isEmpty()) {
            String[] parts = metrics.split(",");
            List<String> values = new ArrayList<String>();
            for (String part : parts) {
                String trimmed = part.trim();
                if (!trimmed.isEmpty()) values.add(trimmed);
            }
            if (!values.isEmpty()) {
                stats.metrics = values;
            }
        }

        String metricAggregation = firstValue(options,
                "stats.metric_aggregation", "stats.metricAggregation", "stats.aggregation");
        if (metricAggregation != null && !metricAggregation.trim().isEmpty()) {
            parseMetricAggregationPairs(metricAggregation, stats);
        }

        String sumMetrics = firstValue(options, "stats.sum_metrics", "stats.sumMetrics");
        if (sumMetrics != null && !sumMetrics.trim().isEmpty()) {
            parseMetricAggregationList(sumMetrics, StatisticsConfig.MetricAggregation.SUM, stats);
        }

        String meanMetrics = firstValue(options, "stats.mean_metrics", "stats.meanMetrics");
        if (meanMetrics != null && !meanMetrics.trim().isEmpty()) {
            parseMetricAggregationList(meanMetrics, StatisticsConfig.MetricAggregation.MEAN, stats);
        }
    }

    /**
     * Parses {@code repfig.*} options into {@link CLIConfig.RepfigConfig}.
     * Every key is optional; an unset key leaves the corresponding override
     * null so the saved tile styling is preserved.
     */
    private static void parseRepfigOptions(String options, CLIConfig cfg) {
        CLIConfig.RepfigConfig repfig = cfg.repfig;

        String cellSize = getValue(options, "repfig.cell_size");
        if (cellSize != null) {
            repfig.cellSizePx = Integer.valueOf(parseIntValue(
                    "repfig.cell_size", cellSize, 260, 80, 1200));
        }

        String rowGap = getValue(options, "repfig.row_gap");
        if (rowGap != null) {
            repfig.rowGapPx = Integer.valueOf(parseIntValue(
                    "repfig.row_gap", rowGap, 8, 0, 400));
        }

        String columnGap = getValue(options, "repfig.column_gap");
        if (columnGap != null) {
            repfig.conditionGapPx = Integer.valueOf(parseIntValue(
                    "repfig.column_gap", columnGap, 12, 0, 400));
        }

        String innerGap = getValue(options, "repfig.inner_gap");
        if (innerGap != null) {
            repfig.innerColGapPx = Integer.valueOf(parseIntValue(
                    "repfig.inner_gap", innerGap, 4, 0, 200));
        }

        String margin = getValue(options, "repfig.margin");
        if (margin != null) {
            repfig.marginPx = Integer.valueOf(parseIntValue(
                    "repfig.margin", margin, 6, 0, 200));
        }

        String conditionFont = getValue(options, "repfig.condition_font");
        if (conditionFont != null) {
            repfig.conditionFontSizePx = Integer.valueOf(parseIntValue(
                    "repfig.condition_font", conditionFont, 15, 6, 96));
        }

        String channelFont = getValue(options, "repfig.channel_font");
        if (channelFont != null) {
            repfig.channelFontSizePx = Integer.valueOf(parseIntValue(
                    "repfig.channel_font", channelFont, 16, 6, 96));
        }

        String outputDpi = getValue(options, "repfig.dpi");
        if (outputDpi == null) {
            outputDpi = getValue(options, "repfig.output_dpi");
        }
        if (outputDpi != null) {
            repfig.outputDpi = Integer.valueOf(parseIntValue(
                    "repfig.dpi", outputDpi, 300, 72, 2400));
        }

        String exportScale = getValue(options, "repfig.export_scale");
        if (exportScale != null) {
            repfig.exportScale = Integer.valueOf(parseIntValue(
                    "repfig.export_scale", exportScale, 1, 1, 4));
        }

        String scaleBar = getValue(options, "repfig.scalebar");
        if (scaleBar != null) {
            repfig.scaleBarEnabled = Boolean.valueOf(parseBooleanValue(
                    "repfig.scalebar", scaleBar, true));
        }

        String scaleBarUm = getValue(options, "repfig.scalebar_um");
        if (scaleBarUm != null) {
            repfig.scaleBarLengthUm = Double.valueOf(parseDoubleValue(
                    "repfig.scalebar_um", scaleBarUm, 100.0, 0.000001, Double.MAX_VALUE));
        }

        String scaleBarThickness = getValue(options, "repfig.scalebar_thickness");
        if (scaleBarThickness != null) {
            repfig.scaleBarThicknessPx = Integer.valueOf(parseIntValue(
                    "repfig.scalebar_thickness", scaleBarThickness, 6, 1, 30));
        }

        String scaleBarPosition = getValue(options, "repfig.scalebar_position");
        if (scaleBarPosition != null && !scaleBarPosition.trim().isEmpty()) {
            PresentationTileConfig.Position parsed = parseTilePosition(scaleBarPosition);
            if (parsed != null) {
                repfig.scaleBarPosition = parsed;
            }
        }

        double[] scaleBarFrac = parseFracPair("repfig.scalebar_frac",
                getValue(options, "repfig.scalebar_frac"));
        if (scaleBarFrac != null) {
            repfig.scaleBarFracX = Double.valueOf(scaleBarFrac[0]);
            repfig.scaleBarFracY = Double.valueOf(scaleBarFrac[1]);
        }

        String labelMode = getValue(options, "repfig.label_mode");
        if (labelMode != null && !labelMode.trim().isEmpty()) {
            PresentationTileConfig.LabelMode parsed = parseLabelMode(labelMode);
            if (parsed != null) {
                repfig.labelMode = parsed;
            }
        }

        String labelText = getValue(options, "repfig.label_text");
        if (labelText != null) {
            repfig.customLabelTemplate = labelText;
        }

        String labelFont = getValue(options, "repfig.label_font");
        if (labelFont != null) {
            repfig.labelFontSizePx = Integer.valueOf(parseIntValue(
                    "repfig.label_font", labelFont, 18, 8, 96));
        }

        String labelPosition = getValue(options, "repfig.label_position");
        if (labelPosition != null && !labelPosition.trim().isEmpty()) {
            PresentationTileConfig.Position parsed = parseTilePosition(labelPosition);
            if (parsed != null) {
                repfig.labelPosition = parsed;
            }
        }

        double[] labelFrac = parseFracPair("repfig.label_frac",
                getValue(options, "repfig.label_frac"));
        if (labelFrac != null) {
            repfig.labelFracX = Double.valueOf(labelFrac[0]);
            repfig.labelFracY = Double.valueOf(labelFrac[1]);
        }

        String color = getValue(options, "repfig.color");
        if (color != null && !color.trim().isEmpty()) {
            java.awt.Color parsed = parseAnnotationColor(color);
            if (parsed != null) {
                repfig.annotationColor = parsed;
            }
        }

        String annotateTile = getValue(options, "repfig.annotate_tile");
        if (annotateTile != null) {
            repfig.annotateOverviewTile = Boolean.valueOf(parseBooleanValue(
                    "repfig.annotate_tile", annotateTile, true));
        }

        String annotateIndividual = getValue(options, "repfig.annotate_individual");
        if (annotateIndividual != null) {
            repfig.annotateIndividualImages = Boolean.valueOf(parseBooleanValue(
                    "repfig.annotate_individual", annotateIndividual, false));
        }

        String groupBy = getValue(options, "repfig.group_by");
        if (groupBy != null && !groupBy.trim().isEmpty()) {
            PresentationTileConfig.GroupRowsBy parsed = parseGroupRowsBy(groupBy);
            if (parsed != null) {
                repfig.groupRowsBy = parsed;
            }
        }

        String channelOrder = getValue(options, "repfig.channel_order");
        if (channelOrder != null) {
            repfig.channelOrder = parseStringList(channelOrder);
        }

        String rows = getValue(options, "repfig.rows");
        if (rows != null) {
            repfig.rows = Integer.valueOf(parseIntValue(
                    "repfig.rows", rows, 1, 1, Integer.MAX_VALUE));
        }

        String saveName = firstValue(options,
                "repfig.save_name", "repfig.savename", "repfig.name");
        if (saveName != null && !saveName.trim().isEmpty()) {
            repfig.saveName = saveName.trim();
        }
    }

    private static PresentationTileConfig.Position parseTilePosition(String raw) {
        String normalized = raw.trim().toLowerCase(Locale.ROOT).replace("-", "").replace("_", "");
        if ("tl".equals(normalized) || "topleft".equals(normalized)) {
            return PresentationTileConfig.Position.TOP_LEFT;
        }
        if ("tr".equals(normalized) || "topright".equals(normalized)) {
            return PresentationTileConfig.Position.TOP_RIGHT;
        }
        if ("bl".equals(normalized) || "bottomleft".equals(normalized)) {
            return PresentationTileConfig.Position.BOTTOM_LEFT;
        }
        if ("br".equals(normalized) || "bottomright".equals(normalized)) {
            return PresentationTileConfig.Position.BOTTOM_RIGHT;
        }
        IJ.log("[CLI] Warning: Unknown repfig position '" + raw + "'.");
        return null;
    }

    private static PresentationTileConfig.LabelMode parseLabelMode(String raw) {
        String normalized = raw.trim().toLowerCase(Locale.ROOT).replace("-", "_");
        if ("none".equals(normalized)) return PresentationTileConfig.LabelMode.NONE;
        if ("stain".equals(normalized) || "stain_name".equals(normalized)) {
            return PresentationTileConfig.LabelMode.STAIN_NAME;
        }
        if ("image".equals(normalized) || "image_name".equals(normalized)) {
            return PresentationTileConfig.LabelMode.IMAGE_NAME;
        }
        if ("condition_image".equals(normalized) || "conditionimage".equals(normalized)) {
            return PresentationTileConfig.LabelMode.CONDITION_IMAGE;
        }
        if ("custom".equals(normalized)) return PresentationTileConfig.LabelMode.CUSTOM;
        IJ.log("[CLI] Warning: Unknown repfig.label_mode '" + raw + "'.");
        return null;
    }

    private static PresentationTileConfig.GroupRowsBy parseGroupRowsBy(String raw) {
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        if ("condition".equals(normalized)) return PresentationTileConfig.GroupRowsBy.CONDITION;
        if ("animal".equals(normalized)) return PresentationTileConfig.GroupRowsBy.ANIMAL;
        IJ.log("[CLI] Warning: Unknown repfig.group_by '" + raw + "'.");
        return null;
    }

    private static java.awt.Color parseAnnotationColor(String raw) {
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        if ("white".equals(normalized)) return java.awt.Color.WHITE;
        if ("black".equals(normalized)) return java.awt.Color.BLACK;
        IJ.log("[CLI] Warning: Unknown repfig.color '" + raw + "'. Expected white or black.");
        return null;
    }

    private static double[] parseFracPair(String key, String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return null;
        }
        String[] parts = raw.split(",");
        if (parts.length != 2) {
            IJ.log("[CLI] Warning: Invalid " + key + " value: " + raw
                    + ". Expected 'x,y'.");
            return null;
        }
        try {
            double x = Double.parseDouble(parts[0].trim());
            double y = Double.parseDouble(parts[1].trim());
            if (Double.isNaN(x) || Double.isNaN(y)
                    || Double.isInfinite(x) || Double.isInfinite(y)) {
                throw new NumberFormatException("non-finite");
            }
            return new double[]{x, y};
        } catch (NumberFormatException e) {
            IJ.log("[CLI] Warning: Invalid " + key + " value: " + raw);
            return null;
        }
    }

    private static List<String> parseStringList(String raw) {
        List<String> values = new ArrayList<String>();
        if (raw == null) {
            return values;
        }
        String[] parts = raw.split(",");
        for (String part : parts) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                values.add(trimmed);
            }
        }
        return values;
    }

    private static String firstValue(String options, String... keys) {
        if (keys == null) return null;
        for (String key : keys) {
            String value = getValue(options, key);
            if (value != null) return value;
        }
        return null;
    }

    private static void parseMetricAggregationPairs(String value, CLIConfig.StatsConfig stats) {
        if (value == null || stats == null) return;
        String[] parts = value.split(",");
        for (String part : parts) {
            if (part == null) continue;
            String trimmed = part.trim();
            if (trimmed.isEmpty()) continue;
            int sep = trimmed.lastIndexOf(':');
            if (sep < 0) sep = trimmed.lastIndexOf('=');
            if (sep <= 0 || sep + 1 >= trimmed.length()) continue;
            String metric = trimmed.substring(0, sep).trim();
            String mode = trimmed.substring(sep + 1).trim();
            putMetricAggregation(stats, metric,
                    StatisticsConfig.MetricAggregation.parse(
                            mode, StatisticsConfig.MetricAggregation.AUTO));
        }
    }

    private static void parseMetricAggregationList(String value,
                                                   StatisticsConfig.MetricAggregation aggregation,
                                                   CLIConfig.StatsConfig stats) {
        if (value == null || stats == null || aggregation == null) return;
        String[] parts = value.split(",");
        for (String part : parts) {
            if (part == null) continue;
            putMetricAggregation(stats, part.trim(), aggregation);
        }
    }

    private static void putMetricAggregation(CLIConfig.StatsConfig stats,
                                             String metric,
                                             StatisticsConfig.MetricAggregation aggregation) {
        if (stats == null || metric == null || aggregation == null
                || aggregation == StatisticsConfig.MetricAggregation.AUTO) {
            return;
        }
        String trimmed = metric.trim();
        if (trimmed.isEmpty()) return;
        if (stats.metricAggregations == null) {
            stats.metricAggregations =
                    new LinkedHashMap<String, StatisticsConfig.MetricAggregation>();
        }
        String existing = existingMetricAggregationKey(stats, trimmed);
        if (existing != null) {
            stats.metricAggregations.remove(existing);
        }
        stats.metricAggregations.put(trimmed, aggregation);
    }

    private static String existingMetricAggregationKey(CLIConfig.StatsConfig stats,
                                                       String metric) {
        if (stats == null || stats.metricAggregations == null || metric == null) {
            return null;
        }
        String trimmed = metric.trim();
        for (String key : stats.metricAggregations.keySet()) {
            if (key != null && key.trim().equalsIgnoreCase(trimmed)) {
                return key;
            }
        }
        return null;
    }

    private static Boolean parseNullableBoolean(String options, String key, Boolean current) {
        String value = getValue(options, key);
        if (value == null) {
            return current;
        }
        return Boolean.valueOf(parseBooleanValue(key, value, current == null ? false : current.booleanValue()));
    }

    private static Boolean parseNullableBoolean(String options, Boolean current, String... keys) {
        if (keys == null) return current;
        for (String key : keys) {
            String value = getValue(options, key);
            if (value != null) {
                return Boolean.valueOf(parseBooleanValue(
                        key, value, current == null ? false : current.booleanValue()));
            }
        }
        return current;
    }

    private static String normalizeOipRegion(String raw) {
        String normalized = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT)
                .replace("_", "").replace("-", "").replace(" ", "");
        if ("objectvoxels".equals(normalized) || "objectonly".equals(normalized)
                || "onlyobject".equals(normalized)) {
            return "OBJECT_VOXELS";
        }
        if ("wholebox".equals(normalized) || "box".equals(normalized)
                || "boundingbox".equals(normalized)) {
            return "WHOLE_BOX";
        }
        IJ.log("[CLI] Warning: Unknown object.oip.region '" + raw + "'. Using WHOLE_BOX.");
        return "WHOLE_BOX";
    }

    private static String normalizeOipIntensityNorm(String raw) {
        String normalized = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT)
                .replace("_", "").replace("-", "").replace(" ", "");
        if ("perobjectminmax".equals(normalized) || "minmax".equals(normalized)
                || "perobject".equals(normalized)) {
            return "PER_OBJECT_MINMAX";
        }
        if ("dividebymean".equals(normalized) || "mean".equals(normalized)) {
            return "DIVIDE_BY_MEAN";
        }
        if ("zscore".equals(normalized) || "z".equals(normalized)) {
            return "ZSCORE";
        }
        IJ.log("[CLI] Warning: Unknown object.oip.norm '" + raw + "'. Using PER_OBJECT_MINMAX.");
        return "PER_OBJECT_MINMAX";
    }

    private static void enforceSpatialDependencies(CLIConfig.SpatialConfig spatial) {
        if (spatial == null) return;
        if (Boolean.TRUE.equals(spatial.doPopMorphometrics)) {
            spatial.doCompositeIndices = Boolean.TRUE;
        }
        if (Boolean.TRUE.equals(spatial.doCompositeIndices)
                || Boolean.TRUE.equals(spatial.doSpatialMorphometrics)) {
            spatial.do3DMorphology = Boolean.TRUE;
        }
    }

    private static void putIfPresent(java.util.Map<Integer, String> target, int oneBasedChannel, String value) {
        if (value == null || value.trim().isEmpty()) return;
        target.put(Integer.valueOf(oneBasedChannel - 1), value.trim());
    }

    private static void putBinFieldIfPresent(CLIConfig cfg, BinField field, String value) {
        if (cfg == null || field == null || value == null || value.trim().isEmpty()) return;
        cfg.headlessBinFields.put(field, value.trim());
    }

    private static void parseDownstreamUseDeconvOptions(String options, CLIConfig cfg) {
        String splitMergeUseDeconv = getValue(options, "splitmerge.useDeconv");
        if (splitMergeUseDeconv != null) {
            cfg.splitMergeUseDeconv = parseBooleanValue("splitmerge.useDeconv", splitMergeUseDeconv, true);
            cfg.splitMergeUseDeconvExplicit = Boolean.valueOf(cfg.splitMergeUseDeconv);
        }

        String splitMergeApplyOrientation = firstValue(options,
                "splitmerge.applyOrientationTransforms",
                "splitmerge.applyOrientation",
                "splitmerge.orientationTransforms");
        if (splitMergeApplyOrientation != null) {
            cfg.splitMergeApplyOrientationTransforms = parseBooleanValue(
                    "splitmerge.applyOrientationTransforms", splitMergeApplyOrientation, true);
        }

        String threeDUseDeconv = getValue(options, "threeD.useDeconv");
        if (threeDUseDeconv != null) {
            cfg.threeDUseDeconv = parseBooleanValue("threeD.useDeconv", threeDUseDeconv, true);
            cfg.threeDUseDeconvExplicit = Boolean.valueOf(cfg.threeDUseDeconv);
        }

        String intensityUseDeconv = getValue(options, "intensityV2.useDeconv");
        if (intensityUseDeconv != null) {
            cfg.intensityV2UseDeconv = parseBooleanValue("intensityV2.useDeconv", intensityUseDeconv, true);
            cfg.intensityV2UseDeconvExplicit = Boolean.valueOf(cfg.intensityV2UseDeconv);
        }
    }

    private static void applyPreset(CLIConfig.DeconvConfig deconv, String presetName, DeconvPresetIO presetIO) {
        if (deconv == null || presetIO == null || presetName == null || presetName.trim().isEmpty()) {
            return;
        }
        try {
            DeconvPreset preset = presetIO.load(presetName);
            deconv.engine = preset.getEngineKey();
            deconv.algorithm = preset.getAlgorithm();
            deconv.psfModel = preset.getPsfModel();
            deconv.iterations = preset.getIterations();
            deconv.regularization = preset.getRegularization();
            deconv.scopeModality = preset.getScopeModality();
            deconv.pinholeAiryUnits = preset.getPinholeAU();
            deconv.sampleRI = preset.getSampleRI();
        } catch (IOException e) {
            IJ.log("[CLI] Warning: Could not load deconv.preset '" + presetName + "': " + e.getMessage());
        }
    }

    private static void applyAnalysisIndex(String analysisIndex, CLIConfig cfg) {
        if (analysisIndex == null || analysisIndex.trim().isEmpty()) return;
        int idx;
        try {
            idx = Integer.parseInt(analysisIndex.trim());
        } catch (NumberFormatException e) {
            throw new CliFatalException("Invalid analysisIndex: " + analysisIndex);
        }
        if (idx < 0 || idx >= cfg.selectedAnalyses.length) {
            throw new CliFatalException("analysisIndex out of range: " + idx
                    + " (valid: 0.." + (cfg.selectedAnalyses.length - 1) + ")");
        }
        cfg.selectedAnalyses[idx] = true;
    }

    private static void applyAnalysesList(String analysesStr, CLIConfig cfg) {
        if (analysesStr == null || analysesStr.trim().isEmpty()) return;
        String[] parts = analysesStr.split(",");
        for (String part : parts) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) continue;
            int idx;
            try {
                idx = Integer.parseInt(trimmed);
            } catch (NumberFormatException e) {
                throw new CliFatalException("Invalid analysis index in analyses=: '" + trimmed + "'");
            }
            if (idx < 0 || idx >= cfg.selectedAnalyses.length) {
                throw new CliFatalException("Analysis index out of range in analyses=: " + idx
                        + " (valid: 0.." + (cfg.selectedAnalyses.length - 1) + ")");
            }
            cfg.selectedAnalyses[idx] = true;
        }
    }

    private static int parseIntOption(String options, String key, int defaultValue, int min, int max) {
        String value = getValue(options, key);
        if (value == null) return defaultValue;
        return parseIntValue(key, value, defaultValue, min, max);
    }

    private static int parseIntValue(String key, String raw, int defaultValue, int min, int max) {
        try {
            int parsed = Integer.parseInt(raw.trim());
            return Math.max(min, Math.min(max, parsed));
        } catch (NumberFormatException e) {
            IJ.log("[CLI] Warning: Invalid " + key + " value: " + raw);
            return defaultValue;
        }
    }

    private static double parseDoubleValue(String key, String raw, double defaultValue, double min, double max) {
        try {
            double parsed = Double.parseDouble(raw.trim());
            if (Double.isNaN(parsed) || Double.isInfinite(parsed)) {
                throw new NumberFormatException("non-finite");
            }
            if (parsed < min) return min;
            if (parsed > max) return max;
            return parsed;
        } catch (NumberFormatException e) {
            IJ.log("[CLI] Warning: Invalid " + key + " value: " + raw);
            return defaultValue;
        }
    }

    private static long parseLongValue(String key, String raw, long defaultValue) {
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            IJ.log("[CLI] Warning: Invalid " + key + " value: " + raw);
            return defaultValue;
        }
    }

    private static boolean getBooleanOption(String options, String key, boolean defaultValue) {
        String value = getValue(options, key);
        if (value != null) {
            return parseBooleanValue(key, value, defaultValue);
        }
        return hasBooleanFlag(options, key) ? true : defaultValue;
    }

    private static boolean parseBooleanValue(String key, String raw, boolean defaultValue) {
        String normalized = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if ("true".equals(normalized) || "1".equals(normalized) || "yes".equals(normalized)) return true;
        if ("false".equals(normalized) || "0".equals(normalized) || "no".equals(normalized)) return false;
        IJ.log("[CLI] Warning: Invalid " + key + " boolean value: " + raw);
        return defaultValue;
    }

    private static int[] parseIntList(String key, String raw) {
        String[] parts = raw.split(",");
        java.util.LinkedHashSet<Integer> values = new java.util.LinkedHashSet<Integer>();
        for (String part : parts) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) continue;
            try {
                values.add(Integer.valueOf(Integer.parseInt(trimmed)));
            } catch (NumberFormatException e) {
                IJ.log("[CLI] Warning: Invalid integer in " + key + ": " + trimmed);
            }
        }
        int[] result = new int[values.size()];
        int i = 0;
        for (Integer value : values) {
            result[i++] = value.intValue();
        }
        return result;
    }

    private static String normalizeEngine(String raw) {
        String normalized = raw.trim();
        if ("clij2".equalsIgnoreCase(normalized)) return "CLIJ2";
        if ("dl2".equalsIgnoreCase(normalized)) return "DL2";
        if ("iterativedeconvolve3d".equalsIgnoreCase(normalized)
                || "iterative_deconvolve_3d".equalsIgnoreCase(normalized)) {
            return "IterativeDeconvolve3D";
        }
        throw new CliFatalException("Unknown deconv.engine '" + raw
                + "'. Expected one of: CLIJ2, DL2, IterativeDeconvolve3D.");
    }

    private static Algorithm parseAlgorithm(String raw) {
        String normalized = raw.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        if ("RLTV".equals(normalized)) normalized = "RL_TV";
        try {
            return Algorithm.valueOf(normalized);
        } catch (IllegalArgumentException e) {
            IJ.log("[CLI] Warning: Unknown deconv.algorithm '" + raw + "'.");
            return null;
        }
    }

    private static PsfModel parsePsfModel(String raw) {
        String normalized = raw.trim().toLowerCase(Locale.ROOT).replace("-", "").replace("_", "");
        if ("gibsonlanni".equals(normalized)) return PsfModel.GIBSON_LANNI;
        if ("bornwolf".equals(normalized)) return PsfModel.BORN_WOLF;
        if ("dougherty".equals(normalized) || "doughertystheoretical".equals(normalized)) {
            return PsfModel.DOUGHERTY_THEORETICAL;
        }
        IJ.log("[CLI] Warning: Unknown deconv.psf '" + raw + "'. Using GibsonLanni.");
        return PsfModel.GIBSON_LANNI;
    }

    private static ScopeModality parseScopeModality(String raw) {
        String normalized = raw.trim().toLowerCase(Locale.ROOT).replace("_", "");
        if ("widefield".equals(normalized)) return ScopeModality.WIDEFIELD;
        if ("confocal".equals(normalized)) return ScopeModality.CONFOCAL;
        if ("spinningdisk".equals(normalized) || "spinning".equals(normalized)) {
            return ScopeModality.SPINNING_DISK;
        }
        IJ.log("[CLI] Warning: Unknown deconv.scopeModality '" + raw + "'.");
        return null;
    }

    private static String canonicalizeExistingDirectoryArgument(String directory) {
        File dirFile = new File(directory);
        if (dirFile == null || !dirFile.exists()) {
            return directory;
        }
        try {
            return dirFile.getCanonicalFile().getPath();
        } catch (IOException e) {
            return dirFile.getAbsoluteFile().toPath().normalize().toString();
        }
    }

    /*
     * Macro value grammar
     * -------------------
     *   value := bare | '[' legacy-content ']' | quote quoted-content quote
     *          | '[' '!FLASH1!' encoded-content ']'
     *
     * Bare values contain no whitespace or bracket; a leading quote selects
     * quoted form, while later quotes remain literal for legacy compatibility.
     * Legacy bracketed values are retained and preserve every backslash; the
     * sole legacy escape is \] when a later bracket closes the value. The
     * tagged form is emitted for values containing grammar delimiters or
     * controls. Its '~' escape accepts only ~, [, ], n, r, t, or uXXXX.
     * Ordinary backslashes are never escapes in the tagged form, so drive
     * roots, UNC prefixes, and trailing separators survive byte-for-byte.
     */
    private static final String VALUE_CODEC_MARKER = "!FLASH1!";

    /** Encodes one present value. A missing option is represented by omitting its key. */
    static String encodeValue(String value) {
        if (value == null) return null;
        if (isBareValue(value)) return value;
        if (isSafeLegacyBracketValue(value)) return "[" + value + "]";

        StringBuilder out = new StringBuilder(value.length() + VALUE_CODEC_MARKER.length() + 2);
        out.append('[').append(VALUE_CODEC_MARKER);
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            switch (ch) {
                case '~': out.append("~~"); break;
                case '[': out.append("~["); break;
                case ']': out.append("~]"); break;
                case '\n': out.append("~n"); break;
                case '\r': out.append("~r"); break;
                case '\t': out.append("~t"); break;
                default:
                    if (Character.isISOControl(ch)) {
                        out.append("~u");
                        String hex = Integer.toHexString(ch).toUpperCase(Locale.ROOT);
                        for (int pad = hex.length(); pad < 4; pad++) out.append('0');
                        out.append(hex);
                    } else {
                        out.append(ch);
                    }
                    break;
            }
        }
        return out.append(']').toString();
    }

    private static boolean isBareValue(String value) {
        if (value.isEmpty()) return false;
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (Character.isWhitespace(ch) || Character.isISOControl(ch)
                    || ch == '[' || ch == ']'
                    || (i == 0 && (ch == '"' || ch == '\''))) {
                return false;
            }
        }
        return true;
    }

    private static boolean isSafeLegacyBracketValue(String value) {
        if (value.startsWith(VALUE_CODEC_MARKER)) return false;
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch == '[' || ch == ']' || Character.isISOControl(ch)) return false;
        }
        return true;
    }

    /** Extracts a key=value parameter through the same tokenizer used for flags. */
    static String getValue(String options, String key) {
        if (options == null || options.isEmpty() || key == null || key.isEmpty()) {
            return null;
        }
        for (CliToken token : tokenize(options)) {
            if (token.key != null && token.key.equalsIgnoreCase(key)) return token.value;
        }
        return null;
    }

    private static void rejectMalformedBracketedValue(String options, String key) {
        // Tokenization validates every field, not only dir/config_dir. Keep this
        // call site as a compatibility seam for the terminal CLI stages.
        getValue(options, key);
    }

    /** Checks if a boolean flag is present as a standalone token. */
    private static boolean hasBooleanFlag(String options, String key) {
        if (options == null || key == null) return false;
        for (CliToken token : tokenize(options)) {
            if (token.key == null && token.value.equalsIgnoreCase(key)) return true;
        }
        return false;
    }

    private static List<CliToken> tokenize(String options) {
        List<CliToken> tokens = new ArrayList<CliToken>();
        if (options == null || options.isEmpty()) return tokens;

        int i = 0;
        while (i < options.length()) {
            while (i < options.length() && Character.isWhitespace(options.charAt(i))) i++;
            if (i >= options.length()) break;

            int tokenStart = i;
            while (i < options.length() && options.charAt(i) != '='
                    && !Character.isWhitespace(options.charAt(i))) {
                char ch = options.charAt(i);
                if (ch == '[' || ch == ']') {
                    throw malformed(options.substring(tokenStart, i), i,
                            "malformed bracket in option name");
                }
                if (ch == '"' || ch == '\'') {
                    throw malformed(options.substring(tokenStart, i), i,
                            "quote delimiter in option name");
                }
                i++;
            }

            if (i >= options.length() || Character.isWhitespace(options.charAt(i))) {
                String flag = options.substring(tokenStart, i);
                if (!flag.isEmpty()) tokens.add(CliToken.flag(flag));
                continue;
            }

            String field = options.substring(tokenStart, i);
            if (field.isEmpty()) throw malformed("<unknown>", i, "missing field before '='");
            i++; // '='
            if (i >= options.length() || Character.isWhitespace(options.charAt(i))) {
                tokens.add(CliToken.value(field, ""));
                continue;
            }

            ParsedValue parsed;
            char first = options.charAt(i);
            if (first == '[') {
                parsed = parseBracketedValue(options, field, i);
            } else if (first == '"' || first == '\'') {
                parsed = parseQuotedValue(options, field, i, first);
            } else {
                parsed = parseBareValue(options, field, i);
            }
            tokens.add(CliToken.value(field, parsed.value));
            i = parsed.next;
            if (i < options.length() && !Character.isWhitespace(options.charAt(i))) {
                throw malformed(field, i, "unexpected character after closing value delimiter");
            }
        }
        return tokens;
    }

    private static ParsedValue parseBareValue(String options, String field, int start) {
        int end = start;
        while (end < options.length() && !Character.isWhitespace(options.charAt(end))) {
            char ch = options.charAt(end);
            if (ch == '[' || ch == ']') {
                throw malformed(field, end, "malformed bracket in bare value");
            }
            end++;
        }
        return new ParsedValue(options.substring(start, end), end);
    }

    private static ParsedValue parseQuotedValue(String options, String field, int start, char quote) {
        StringBuilder out = new StringBuilder();
        for (int i = start + 1; i < options.length(); i++) {
            char ch = options.charAt(i);
            if (ch == '\\' && i + 1 < options.length() && options.charAt(i + 1) == quote
                    && hasLaterQuoteClose(options, i + 2, quote)) {
                out.append(quote);
                i++;
                continue;
            }
            if (ch == quote) return new ParsedValue(out.toString(), i + 1);
            out.append(ch);
        }
        throw malformed(field, options.length(), "unclosed " + quote + " quote");
    }

    private static boolean hasLaterQuoteClose(String options, int start, char quote) {
        for (int i = start; i < options.length(); i++) {
            char ch = options.charAt(i);
            if (ch == quote) return true;
            if (Character.isWhitespace(ch)) {
                int token = i;
                while (token < options.length() && Character.isWhitespace(options.charAt(token))) token++;
                int end = token;
                while (end < options.length() && !Character.isWhitespace(options.charAt(end))) end++;
                if (options.substring(token, end).indexOf('=') >= 0) return false;
            }
        }
        return false;
    }

    private static ParsedValue parseBracketedValue(String options, String field, int start) {
        int contentStart = start + 1;
        if (options.startsWith(VALUE_CODEC_MARKER, contentStart)) {
            return parseTaggedBracketedValue(options, field,
                    contentStart + VALUE_CODEC_MARKER.length());
        }

        StringBuilder out = new StringBuilder();
        int depth = 1;
        for (int i = contentStart; i < options.length(); i++) {
            char ch = options.charAt(i);
            if (ch == '\\' && i + 1 < options.length() && options.charAt(i + 1) == ']'
                    && hasLaterLegacyClose(options, i + 2, depth)) {
                out.append(']');
                i++;
                continue;
            }
            if (ch == '[') {
                depth++;
                out.append(ch);
            } else if (ch == ']') {
                depth--;
                if (depth == 0) return new ParsedValue(out.toString(), i + 1);
                out.append(ch);
            } else {
                out.append(ch);
            }
        }
        throw malformed(field, options.length(), "missing closing ']' for bracketed value");
    }

    private static boolean hasLaterLegacyClose(String options, int start, int initialDepth) {
        int depth = initialDepth;
        for (int i = start; i < options.length(); i++) {
            char ch = options.charAt(i);
            if (depth == initialDepth && Character.isWhitespace(ch)) {
                int token = i;
                while (token < options.length() && Character.isWhitespace(options.charAt(token))) token++;
                int end = token;
                while (end < options.length() && !Character.isWhitespace(options.charAt(end))
                        && options.charAt(end) != ']') end++;
                if (options.substring(token, end).indexOf('=') >= 0) return false;
            }
            if (ch == '[') depth++;
            else if (ch == ']' && --depth == 0) return true;
        }
        return false;
    }

    private static ParsedValue parseTaggedBracketedValue(String options, String field, int start) {
        StringBuilder out = new StringBuilder();
        for (int i = start; i < options.length(); i++) {
            char ch = options.charAt(i);
            if (ch == ']') return new ParsedValue(out.toString(), i + 1);
            if (ch == '[') throw malformed(field, i, "unescaped '[' in encoded value");
            if (ch != '~') {
                out.append(ch);
                continue;
            }
            if (i + 1 >= options.length()) {
                throw malformed(field, i, "dangling '~' escape");
            }
            char escaped = options.charAt(++i);
            switch (escaped) {
                case '~': out.append('~'); break;
                case '[': out.append('['); break;
                case ']': out.append(']'); break;
                case 'n': out.append('\n'); break;
                case 'r': out.append('\r'); break;
                case 't': out.append('\t'); break;
                case 'u':
                    if (i + 4 >= options.length()) {
                        throw malformed(field, i - 1, "short Unicode escape");
                    }
                    int code = 0;
                    for (int digit = 1; digit <= 4; digit++) {
                        int hex = Character.digit(options.charAt(i + digit), 16);
                        if (hex < 0) throw malformed(field, i + digit, "invalid Unicode escape");
                        code = (code << 4) | hex;
                    }
                    out.append((char) code);
                    i += 4;
                    break;
                default:
                    throw malformed(field, i - 1, "invalid '~" + escaped + "' escape");
            }
        }
        throw malformed(field, options.length(), "missing closing ']' for encoded value");
    }

    private static CliFatalException malformed(String field, int position, String detail) {
        return new CliFatalException("Malformed value for field '" + field
                + "' at position " + position + ": " + detail + ".");
    }

    private static final class ParsedValue {
        final String value;
        final int next;

        ParsedValue(String value, int next) {
            this.value = value;
            this.next = next;
        }
    }

    private static final class CliToken {
        final String key;
        final String value;

        private CliToken(String key, String value) {
            this.key = key;
            this.value = value;
        }

        static CliToken flag(String flag) { return new CliToken(null, flag); }
        static CliToken value(String key, String value) { return new CliToken(key, value); }
    }
}
