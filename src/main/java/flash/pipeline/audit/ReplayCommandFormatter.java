package flash.pipeline.audit;

import flash.pipeline.bin.BinConfig;
import flash.pipeline.bin.BinConfigIO;
import flash.pipeline.cli.CLIConfig;

import java.io.File;
import java.util.List;

/** Formats a copy-pasteable ImageJ macro command for replaying one FLASH run. */
public final class ReplayCommandFormatter {
    private static final String PLUGIN_COMMAND =
            "FLASH - The Pipeline for Fluorescence Automated Spatial Histology";

    private ReplayCommandFormatter() {
    }

    public static String format(String directory, int analysisIndex, BinConfig cfg) {
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
        return "IJ.run(\"" + escapeMacroString(PLUGIN_COMMAND) + "\", \""
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
