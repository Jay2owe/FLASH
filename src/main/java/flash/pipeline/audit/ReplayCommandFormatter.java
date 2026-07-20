package flash.pipeline.audit;

import flash.pipeline.FlashCommandNames;
import flash.pipeline.bin.BinConfig;
import flash.pipeline.bin.BinConfigIO;
import flash.pipeline.cli.CLIConfig;

import java.io.File;
import java.util.List;
import java.util.Map;

/** Formats a copy-pasteable ImageJ macro command for replaying one FLASH run. */
public final class ReplayCommandFormatter {
    private ReplayCommandFormatter() {
    }

    public static String format(String directory, int analysisIndex, BinConfig cfg) {
        return format(directory, analysisIndex, cfg, null, null);
    }

    public static String format(String directory, int analysisIndex, BinConfig cfg,
                                CLIConfig cliConfig) {
        return format(directory, analysisIndex, cfg, cliConfig, null);
    }

    public static String format(String directory, int analysisIndex, BinConfig cfg,
                                CLIConfig cliConfig,
                                Map<String, Object> analysisParameters) {
        StringBuilder options = new StringBuilder();
        appendOption(options, "dir", bracketedPath(directory));
        appendRunFlag(options, analysisIndex);
        appendList(options, "channel_names", cfg == null ? null : cfg.channelNames);
        appendList(options, "channel_colors", cfg == null ? null : cfg.channelColors);
        appendList(options, "object_thresholds", cfg == null ? null : cfg.channelThresholds);
        appendList(options, "particle_sizes", cfg == null ? null : cfg.channelSizes);
        appendList(options, "display_min_max", cfg == null ? null : cfg.channelMinMax);
        appendList(options, "intensity_thresholds", cfg == null ? null : cfg.channelIntensityThresholds);
        appendList(options, "segmentation_methods", cfg == null ? null : cfg.segmentationMethods);
        appendFilterPresets(options, cfg == null ? null : cfg.channelFilterPresets);
        if (cfg != null && cfg.zSliceConfigPresent) {
            appendOption(options, CLIConfig.binFieldCliKey(flash.pipeline.bin.BinField.Z_SLICE),
                    zSliceCliToken(cfg));
        }
        if (analysisIndex == 4) {
            if (hasThreeDObjectParameterMap(analysisParameters)) {
                appendThreeDObjectParameters(options, analysisParameters);
            } else {
                appendThreeDObjectCliOptions(options, cliConfig);
            }
        }
        if (analysisIndex == 2) {
            appendDeconvCliOptions(options, cliConfig);
        }
        return "IJ.run(\"" + escapeMacroString(FlashCommandNames.MACRO_COMMAND) + "\", \""
                + escapeMacroString(options.toString()) + "\");";
    }

    static String zSliceCliToken(BinConfig cfg) {
        if (cfg == null || cfg.zSliceMode == null) return "full";
        String token = cfg.zSliceMode.configToken;
        return token != null && token.startsWith("zslice:")
                ? token.substring("zslice:".length()) : token;
    }

    private static void appendRunFlag(StringBuilder options, int analysisIndex) {
        String flag = runFlag(analysisIndex);
        if (flag != null && !flag.isEmpty()) {
            appendToken(options, flag);
        } else if (analysisIndex >= 0) {
            appendOption(options, "analyses", String.valueOf(analysisIndex));
        }
    }

    private static String runFlag(int analysisIndex) {
        switch (analysisIndex) {
            case 0: return "run_bin";
            case 1: return "run_roi";
            case 2: return "run_deconv";
            case 3: return "run_split";
            case 4: return "run_3d";
            case 5: return "run_spatial";
            case 6: return "run_distance";
            case 7: return "run_intensity";
            case 8: return "run_aggregate";
            case 9: return "run_statistics";
            case 10: return "run_excel";
            case 11: return "run_spectral_decontamination";
            case 12: return "run_repfig";
            default: return "";
        }
    }

    private static void appendList(StringBuilder options, String key, List<String> values) {
        if (values == null || values.isEmpty()) return;
        StringBuilder joined = new StringBuilder();
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) joined.append(',');
            joined.append(values.get(i) == null ? "" : values.get(i).trim());
        }
        appendOption(options, key, bracketedValue(joined.toString()));
    }

    private static void appendFilterPresets(StringBuilder options, List<String> values) {
        if (values == null || values.isEmpty()) return;
        StringBuilder joined = new StringBuilder();
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) joined.append(',');
            joined.append(BinConfigIO.encodeFilterPresetToken(values.get(i)));
        }
        appendOption(options, "filter_presets", bracketedValue(joined.toString()));
    }

    private static boolean hasThreeDObjectParameterMap(Map<String, Object> parameters) {
        return parameters != null
                && (parameters.containsKey("doVolumetric")
                || parameters.containsKey("doCpc")
                || parameters.containsKey("doIntensityColoc")
                || parameters.containsKey("doBBOverlap")
                || parameters.containsKey("doRadialProfile")
                || parameters.containsKey("doMarginalProfile")
                || parameters.containsKey("doPrincipalAxisProfile")
                || parameters.containsKey("doAngularProfile")
                || parameters.containsKey("doShellColoc")
                || parameters.containsKey("doWithinBoxCorr")
                || parameters.containsKey("oipGenerateFigures")
                || parameters.containsKey("oipRegion")
                || parameters.containsKey("oipIntensityNorm")
                || parameters.containsKey("oipRadialBins")
                || parameters.containsKey("oipAngularBins")
                || parameters.containsKey("oipShells")
                || parameters.containsKey("oipResampleN")
                || parameters.containsKey("oipBoxPadPct")
                || parameters.containsKey("oipRingThresholdPct"));
    }

    private static void appendThreeDObjectParameters(StringBuilder options,
                                                     Map<String, Object> parameters) {
        if (parameters == null || parameters.isEmpty()) return;
        appendObjectBoolean(options, "object.doVolumetric", parameters.get("doVolumetric"));
        appendObjectBoolean(options, "object.doCpc", parameters.get("doCpc"));
        appendObjectBoolean(options, "object.doIntensityColoc", parameters.get("doIntensityColoc"));
        appendObjectBoolean(options, "object.doBBOverlap", parameters.get("doBBOverlap"));
        appendObjectBoolean(options, "object.doBBCpc", parameters.get("doBBCpc"));
        appendObjectBoolean(options, "object.doBBVol", parameters.get("doBBVol"));
        appendObjectNumber(options, "object.colocThreshold", parameters.get("colocThresholdPercent"));
        appendObjectNumber(options, "object.bbColocThreshold", parameters.get("bbColocThresholdPercent"));
        appendObjectBoolean(options, "object.extractProcessLength", parameters.get("extractProcessLength"));
        appendObjectBoolean(options, "object.runSpatial", parameters.get("runSpatial"));
        appendObjectBoolean(options, "object.classicalCentroidFiltering",
                parameters.get("classicalCentroidFiltering"));
        appendObjectBoolean(options, "object.oip.radial", parameters.get("doRadialProfile"));
        appendObjectBoolean(options, "object.oip.marginal", parameters.get("doMarginalProfile"));
        appendObjectBoolean(options, "object.oip.principal_axis",
                parameters.get("doPrincipalAxisProfile"));
        appendObjectBoolean(options, "object.oip.angular", parameters.get("doAngularProfile"));
        appendObjectBoolean(options, "object.oip.shell", parameters.get("doShellColoc"));
        appendObjectBoolean(options, "object.oip.within_box", parameters.get("doWithinBoxCorr"));
        appendObjectBoolean(options, "object.oip.figures", parameters.get("oipGenerateFigures"));
        appendObjectText(options, "object.oip.region", parameters.get("oipRegion"));
        appendObjectText(options, "object.oip.norm", parameters.get("oipIntensityNorm"));
        appendObjectNumber(options, "object.oip.radial_bins", parameters.get("oipRadialBins"));
        appendObjectNumber(options, "object.oip.angular_bins", parameters.get("oipAngularBins"));
        appendObjectNumber(options, "object.oip.shells", parameters.get("oipShells"));
        appendObjectNumber(options, "object.oip.resample_n", parameters.get("oipResampleN"));
        appendObjectNumber(options, "object.oip.box_pad_pct", parameters.get("oipBoxPadPct"));
        appendObjectNumber(options, "object.oip.ring_threshold_pct",
                parameters.get("oipRingThresholdPct"));
    }

    private static void appendThreeDObjectCliOptions(StringBuilder options, CLIConfig cliConfig) {
        if (cliConfig == null || cliConfig.getObject() == null
                || !cliConfig.getObject().hasConfiguration()) {
            return;
        }
        CLIConfig.ThreeDObjectConfig object = cliConfig.getObject();
        appendObjectText(options, "object.preset", object.getPresetName());
        appendObjectBoolean(options, "object.doVolumetric", object.getDoVolumetric());
        appendObjectBoolean(options, "object.doCpc", object.getDoCpc());
        appendObjectBoolean(options, "object.doIntensityColoc", object.getDoIntensityColoc());
        appendObjectBoolean(options, "object.doBBOverlap", object.getDoBBOverlap());
        appendObjectBoolean(options, "object.doBBCpc", object.getDoBBCpc());
        appendObjectBoolean(options, "object.doBBVol", object.getDoBBVol());
        appendObjectNumber(options, "object.colocThreshold", object.getColocThresholdPercent());
        appendObjectNumber(options, "object.bbColocThreshold", object.getBBColocThresholdPercent());
        appendObjectBoolean(options, "object.extractProcessLength", object.getExtractProcessLength());
        appendObjectBoolean(options, "object.runSpatial", object.getRunSpatial());
        appendObjectBoolean(options, "object.classicalCentroidFiltering",
                object.getClassicalCentroidFiltering());
        if (object.getNuclearMarkerIndex() != null) {
            appendOption(options, "object.nuclear_marker",
                    String.valueOf(object.getNuclearMarkerIndex().intValue() + 1));
        }
        appendObjectBoolean(options, "object.oip.radial", object.getDoRadialProfile());
        appendObjectBoolean(options, "object.oip.marginal", object.getDoMarginalProfile());
        appendObjectBoolean(options, "object.oip.principal_axis",
                object.getDoPrincipalAxisProfile());
        appendObjectBoolean(options, "object.oip.angular", object.getDoAngularProfile());
        appendObjectBoolean(options, "object.oip.shell", object.getDoShellColoc());
        appendObjectBoolean(options, "object.oip.within_box", object.getDoWithinBoxCorr());
        appendObjectBoolean(options, "object.oip.figures", object.getOipGenerateFigures());
        appendObjectText(options, "object.oip.region", object.getOipRegion());
        appendObjectText(options, "object.oip.norm", object.getOipIntensityNorm());
        appendObjectNumber(options, "object.oip.radial_bins", object.getOipRadialBins());
        appendObjectNumber(options, "object.oip.angular_bins", object.getOipAngularBins());
        appendObjectNumber(options, "object.oip.shells", object.getOipShells());
        appendObjectNumber(options, "object.oip.resample_n", object.getOipResampleN());
        appendObjectNumber(options, "object.oip.box_pad_pct", object.getOipBoxPadPct());
        appendObjectNumber(options, "object.oip.ring_threshold_pct", object.getOipRingThresholdPct());
    }

    /**
     * Emit the {@code deconv.*} options for a {@code run_deconv} replay, sourced from the CLI deconv
     * config, so a CLI-driven deconvolution run is self-contained. GUI runs pass a null cliConfig and
     * emit nothing; the bare {@code run_deconv dir=[...]} macro then reproduces from the persisted
     * channel_config deconvolution settings.
     */
    private static void appendDeconvCliOptions(StringBuilder options, CLIConfig cliConfig) {
        if (cliConfig == null || cliConfig.getDeconv() == null) return;
        CLIConfig.DeconvConfig deconv = cliConfig.getDeconv();
        boolean present = deconv.isEnabled()
                || deconv.getEngine() != null
                || deconv.getPresetName() != null
                || deconv.getAlgorithm() != null
                || deconv.getChannels() != null
                || (deconv.getPerChannel() != null && !deconv.getPerChannel().isEmpty());
        if (!present) return;

        appendOption(options, "deconv.enabled", "true");
        appendObjectText(options, "deconv.preset", deconv.getPresetName());
        appendObjectText(options, "deconv.engine", deconv.getEngine());
        if (deconv.getAlgorithm() != null) {
            appendOption(options, "deconv.algorithm", deconv.getAlgorithm().name());
        }
        if (deconv.getPsfModel() != null) {
            appendOption(options, "deconv.psf", psfCliToken(deconv.getPsfModel().name()));
        }
        appendOption(options, "deconv.iterations", String.valueOf(deconv.getIterations()));
        appendOption(options, "deconv.regularization", String.valueOf(deconv.getRegularization()));
        if (deconv.getScopeModality() != null) {
            appendOption(options, "deconv.scopeModality", modalityCliToken(deconv.getScopeModality().name()));
        }
        appendObjectNumber(options, "deconv.pinholeAU", deconv.getPinholeAiryUnits());
        appendObjectNumber(options, "deconv.sampleRI", deconv.getSampleRI());
        appendObjectText(options, "deconv.mountingMedium", deconv.getMountingMedium());
        if (deconv.getChannels() != null && deconv.getChannels().length > 0) {
            StringBuilder joined = new StringBuilder();
            for (int i = 0; i < deconv.getChannels().length; i++) {
                if (i > 0) joined.append(',');
                joined.append(deconv.getChannels()[i]);
            }
            appendOption(options, "deconv.channels", bracketedValue(joined.toString()));
        }
        appendOption(options, "deconv.strictNyquist", String.valueOf(deconv.isStrictNyquist()));
        appendOption(options, "deconv.useCache", String.valueOf(deconv.isUseCache()));
        appendDeconvPerChannelOverrides(options, deconv);
    }

    private static void appendDeconvPerChannelOverrides(StringBuilder options,
                                                        CLIConfig.DeconvConfig deconv) {
        if (deconv.getPerChannel() == null) return;
        for (Map.Entry<Integer, CLIConfig.DeconvConfig.ChannelOverride> entry
                : deconv.getPerChannel().entrySet()) {
            int channel = entry.getKey().intValue();
            CLIConfig.DeconvConfig.ChannelOverride override = entry.getValue();
            if (override == null) continue;
            String prefix = "deconv.ch" + channel + ".";
            appendObjectText(options, prefix + "engine", override.getEngine());
            if (override.getAlgorithm() != null) {
                appendOption(options, prefix + "algorithm", override.getAlgorithm().name());
            }
            if (override.getPsfModel() != null) {
                appendOption(options, prefix + "psf", psfCliToken(override.getPsfModel().name()));
            }
            if (override.getIterations() != null) {
                appendOption(options, prefix + "iterations", String.valueOf(override.getIterations()));
            }
            if (override.getRegularization() != null) {
                appendOption(options, prefix + "regularization",
                        String.valueOf(override.getRegularization()));
            }
        }
    }

    private static String psfCliToken(String psfModelName) {
        if ("GIBSON_LANNI".equals(psfModelName)) return "GibsonLanni";
        if ("BORN_WOLF".equals(psfModelName)) return "BornWolf";
        if ("DOUGHERTY_THEORETICAL".equals(psfModelName)) return "Dougherty";
        return psfModelName;
    }

    private static String modalityCliToken(String modalityName) {
        if ("WIDEFIELD".equals(modalityName)) return "widefield";
        if ("CONFOCAL".equals(modalityName)) return "confocal";
        if ("SPINNING_DISK".equals(modalityName)) return "spinningDisk";
        return modalityName == null ? "" : modalityName.toLowerCase(java.util.Locale.ROOT);
    }

    private static void appendObjectBoolean(StringBuilder options, String key, Object value) {
        if (value == null) return;
        appendOption(options, key, String.valueOf(booleanValue(value)));
    }

    private static void appendObjectNumber(StringBuilder options, String key, Object value) {
        if (value == null) return;
        appendOption(options, key, String.valueOf(value));
    }

    private static void appendObjectText(StringBuilder options, String key, Object value) {
        if (value == null) return;
        String text = String.valueOf(value).trim();
        if (!text.isEmpty()) {
            appendOption(options, key, bracketedValue(text));
        }
    }

    private static boolean booleanValue(Object value) {
        if (value instanceof Boolean) return ((Boolean) value).booleanValue();
        return Boolean.parseBoolean(String.valueOf(value));
    }

    private static void appendOption(StringBuilder options, String key, String value) {
        if (key == null || key.trim().isEmpty() || value == null || value.trim().isEmpty()) return;
        appendToken(options, key + "=" + value);
    }

    private static void appendToken(StringBuilder options, String token) {
        if (options.length() > 0) options.append(' ');
        options.append(token);
    }

    private static String bracketedPath(String directory) {
        String path = directory == null ? "" : new File(directory).getAbsolutePath();
        return bracketedValue(path.replace('\\', '/'));
    }

    private static String bracketedValue(String value) {
        return "[" + (value == null ? "" : value.replace("]", "\\]")) + "]";
    }

    private static String escapeMacroString(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
