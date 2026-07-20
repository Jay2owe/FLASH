package flash.pipeline.ui.variations;

import flash.pipeline.bin.BinMacroIndex;
import flash.pipeline.image.FilterMacroParser;
import flash.pipeline.image.NamedFilterLoader;
import flash.pipeline.ui.config.ConfigQcContext;
import flash.pipeline.ui.config.FilterParameterStage;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class MacroVariationCatalog {

    private static final MacroVariationCatalog EMPTY =
            new MacroVariationCatalog(null, 0, "", null);

    private final File binFolder;
    private final int channelIndex;
    private final String channelLabel;
    private final FilterParameterStage.MacroStore macroStore;

    public MacroVariationCatalog(File binFolder,
                                 int channelIndex,
                                 String channelLabel,
                                 FilterParameterStage.MacroStore macroStore) {
        this.binFolder = binFolder;
        this.channelIndex = Math.max(0, channelIndex);
        this.channelLabel = channelLabel == null ? "" : channelLabel.trim();
        this.macroStore = macroStore;
    }

    public static MacroVariationCatalog empty() {
        return EMPTY;
    }

    public static MacroVariationCatalog forContext(VariationEngineContext context) {
        if (context == null) {
            return empty();
        }
        ConfigQcContext qc = context.configContext();
        int channelIndex = qc == null ? parseChannelIndex(context.channelName())
                : qc.getChannelIndex();
        String label = qc == null ? context.channelName() : qc.getChannelLabel();
        Object store = qc == null ? null : qc.getAttribute("filterMacroStore");
        FilterParameterStage.MacroStore macroStore =
                store instanceof FilterParameterStage.MacroStore
                        ? (FilterParameterStage.MacroStore) store
                        : null;
        return new MacroVariationCatalog(context.binFolder(), channelIndex, label, macroStore);
    }

    public List<MacroVariation> choices() {
        LinkedHashMap<String, MacroVariation> out =
                new LinkedHashMap<String, MacroVariation>();
        put(out, MacroVariation.none());
        put(out, currentChannelFromBin());
        put(out, currentChannelFromStore());
        addBundled(out);
        addSavedCustomPresets(out);
        return new ArrayList<MacroVariation>(out.values());
    }

    public String channelLabel() {
        if (!channelLabel.isEmpty()) {
            return channelLabel;
        }
        return "C" + (channelIndex + 1);
    }

    public static String sourceLabel(MacroVariation variation) {
        if (variation == null) {
            return "";
        }
        String kind = variation.sourceKind();
        if (MacroVariation.SOURCE_NONE.equals(kind)) return "None";
        if (MacroVariation.SOURCE_CURRENT_CHANNEL.equals(kind)) return "Current channel";
        if (MacroVariation.SOURCE_BUNDLED_PRESET.equals(kind)) return "Built-in filter";
        if (MacroVariation.SOURCE_SAVED_PRESET.equals(kind)) return "Saved custom filter";
        if (MacroVariation.SOURCE_RECORDED.equals(kind)) return "Recorded macro";
        return "Script";
    }

    public static String summaryFor(String scriptText) {
        String normalized = MacroToken.normalizeScriptText(scriptText);
        if (normalized.isEmpty()) {
            return "No macro preprocessing.";
        }
        List<FilterMacroParser.Op> ops = FilterMacroParser.parseString(normalized);
        if (ops.isEmpty()) {
            return "Script has no ImageJ run commands.";
        }
        int unknown = 0;
        List<String> names = new ArrayList<String>();
        for (int i = 0; i < ops.size(); i++) {
            FilterMacroParser.Op op = ops.get(i);
            if (op.type == FilterMacroParser.OpType.UNKNOWN) {
                unknown++;
            }
            if (names.size() < 3) {
                names.add(friendlyName(op.type));
            }
        }
        StringBuilder out = new StringBuilder();
        out.append(ops.size()).append(ops.size() == 1 ? " filter step" : " filter steps");
        if (!names.isEmpty()) {
            out.append(": ");
            for (int i = 0; i < names.size(); i++) {
                if (i > 0) {
                    out.append(", ");
                }
                out.append(names.get(i));
            }
            if (ops.size() > names.size()) {
                out.append(", plus ").append(ops.size() - names.size()).append(" more");
            }
        }
        if (unknown > 0) {
            out.append(". ").append(unknown)
                    .append(unknown == 1 ? " step needs" : " steps need")
                    .append(" ImageJ macro execution.");
        }
        return out.toString();
    }

    public static String scriptPreview(String scriptText) {
        String normalized = MacroToken.normalizeScriptText(scriptText);
        if (normalized.isEmpty()) {
            return "";
        }
        String oneLine = normalized.replace('\n', ' ').trim();
        return oneLine.length() <= 180 ? oneLine : oneLine.substring(0, 177) + "...";
    }

    private MacroVariation currentChannelFromBin() {
        File macroFile = activeChannelMacroFile();
        if (macroFile == null || !macroFile.isFile()) {
            return null;
        }
        try {
            String script = new String(Files.readAllBytes(macroFile.toPath()),
                    StandardCharsets.UTF_8);
            if (MacroToken.normalizeScriptText(script).isEmpty()) {
                return null;
            }
            String sourceName = macroFile.getName();
            return MacroVariation.currentChannel(
                    "Current C" + (channelIndex + 1) + " filter",
                    sourceName,
                    script);
        } catch (IOException e) {
            return null;
        }
    }

    private MacroVariation currentChannelFromStore() {
        if (macroStore == null) {
            return null;
        }
        try {
            String script = macroStore.loadInitialMacro();
            if (MacroToken.normalizeScriptText(script).isEmpty()) {
                return null;
            }
            String preset = macroStore.getInitialPreset();
            String sourceName = preset == null || preset.trim().isEmpty()
                    ? "MacroStore"
                    : preset.trim();
            return MacroVariation.currentChannel(
                    "Current C" + (channelIndex + 1) + " filter",
                    sourceName,
                    script);
        } catch (Exception e) {
            return null;
        }
    }

    private void addBundled(Map<String, MacroVariation> out) {
        for (int i = 0; i < NamedFilterLoader.FILTER_NAMES.length; i++) {
            String name = NamedFilterLoader.FILTER_NAMES[i];
            String script = NamedFilterLoader.loadFilterContent(name);
            if (MacroToken.normalizeScriptText(script).isEmpty()) {
                continue;
            }
            put(out, MacroVariation.bundledPreset(name, name, script));
        }
    }

    private void addSavedCustomPresets(Map<String, MacroVariation> out) {
        File[] files = BinMacroIndex.listSavedCustomFilterPresetFiles(binFolder);
        for (int i = 0; i < files.length; i++) {
            File file = files[i];
            if (file == null || !file.isFile()) {
                continue;
            }
            try {
                String script = new String(Files.readAllBytes(file.toPath()),
                        StandardCharsets.UTF_8);
                if (MacroToken.normalizeScriptText(script).isEmpty()) {
                    continue;
                }
                String name = stripIjmExtension(file.getName());
                put(out, MacroVariation.savedPreset(name, name, script));
            } catch (IOException e) {
                // Unreadable presets are skipped so the picker can still open.
            }
        }
    }

    private File activeChannelMacroFile() {
        if (binFolder == null) {
            return null;
        }
        return new File(binFolder, "C" + (channelIndex + 1) + "_Filters.ijm");
    }

    private static void put(Map<String, MacroVariation> out,
                            MacroVariation variation) {
        if (out == null || variation == null) {
            return;
        }
        String token = variation.token();
        if (token == null || token.trim().isEmpty()) {
            return;
        }
        if (!out.containsKey(token.trim())) {
            out.put(token.trim(), variation);
        }
    }

    private static String stripIjmExtension(String fileName) {
        String name = fileName == null ? "" : fileName.trim();
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".ijm") ? name.substring(0, name.length() - 4) : name;
    }

    private static int parseChannelIndex(String channelName) {
        String text = channelName == null ? "" : channelName.trim();
        if (text.length() >= 2 && (text.charAt(0) == 'C' || text.charAt(0) == 'c')) {
            int i = 1;
            while (i < text.length() && Character.isDigit(text.charAt(i))) {
                i++;
            }
            if (i > 1) {
                try {
                    return Math.max(0, Integer.parseInt(text.substring(1, i)) - 1);
                } catch (NumberFormatException ignored) {
                    return 0;
                }
            }
        }
        return 0;
    }

    private static String friendlyName(FilterMacroParser.OpType type) {
        if (type == null) return "Unknown";
        switch (type) {
            case GAUSSIAN_BLUR: return "Gaussian Blur";
            case SUBTRACT_BACKGROUND: return "Subtract Background";
            case MEDIAN: return "Median";
            case MEAN: return "Mean";
            case UNSHARP_MASK: return "Unsharp Mask";
            case MINIMUM: return "Minimum";
            case MAXIMUM: return "Maximum";
            case VARIANCE: return "Variance";
            case DILATE: return "Dilate";
            case ERODE: return "Erode";
            case OPEN: return "Open";
            case CLOSE_: return "Close";
            case FILL_HOLES: return "Fill Holes";
            case SKELETONIZE: return "Skeletonize";
            case INVERT: return "Invert";
            case ADD: return "Add";
            case SUBTRACT: return "Subtract";
            case MULTIPLY: return "Multiply";
            case DIVIDE: return "Divide";
            case AUTO_LOCAL_THRESHOLD: return "Auto Local Threshold";
            case CONVERT_8BIT: return "8-bit";
            case CONVERT_16BIT: return "16-bit";
            case CONVERT_32BIT: return "32-bit";
            case ENHANCE_CONTRAST: return "Enhance Contrast";
            case GAUSSIAN_BLUR_3D: return "Gaussian Blur 3D";
            case MEDIAN_3D: return "Median 3D";
            case MINIMUM_3D: return "Minimum 3D";
            case UNKNOWN: return "Unknown";
            default: return type.name();
        }
    }
}
