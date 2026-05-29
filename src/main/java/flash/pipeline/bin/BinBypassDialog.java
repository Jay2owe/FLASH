package flash.pipeline.bin;

import flash.pipeline.ui.PipelineDialog;
import flash.pipeline.io.FlashProjectLayout;
import flash.pipeline.zslice.ZSliceMode;
import ij.IJ;

import javax.swing.JComboBox;
import javax.swing.JTextField;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/** Plain-input expert path for writing missing bin parameters without previews. */
public final class BinBypassDialog {
    private static final String DEFAULT_COLOR = "Grays";
    private static final String DEFAULT_OBJECT_THRESHOLD = "default";
    private static final String DEFAULT_PARTICLE_SIZE = "100-Infinity";
    private static final String DEFAULT_MIN_MAX = "None";
    private static final String DEFAULT_INTENSITY_THRESHOLD = "default";
    private static final String DEFAULT_SEGMENTATION = "classical";
    private static final String DEFAULT_FILTER_PRESET = "Default";

    private BinBypassDialog() {}

    public static boolean show(String directory, Set<BinField> fields) {
        EnumSet<BinField> requested = normalize(fields);
        BinConfig existing = BinConfigIO.readPartialFromDirectory(directory);

        while (true) {
            DialogState state = buildDialog(existing, requested);
            if (!state.dialog.showDialog()) return false;

            BinConfig cfg = copyConfig(existing);
            try {
                applyDialogValues(state, cfg);
                File settingsDir = FlashProjectLayout.forDirectory(directory).configurationWriteDir();
                ChannelConfigIO.write(settingsDir, ChannelConfigIO.fromBinConfig(cfg));
                BinConfigIO.writeFilterMacrosFromConfig(settingsDir, cfg);
                return true;
            } catch (IllegalArgumentException e) {
                IJ.showMessage("Direct Entry", e.getMessage());
            } catch (IOException e) {
                IJ.handleException(e);
                return false;
            }
        }
    }

    private static DialogState buildDialog(BinConfig existing, EnumSet<BinField> requested) {
        DialogState state = new DialogState(new PipelineDialog("Direct parameter entry"));
        PipelineDialog dialog = state.dialog;
        int channelCount = defaultChannelCount(existing, requested);

        dialog.addHeader("Direct parameter entry");
        dialog.addMessage("Enter known-good FLASH settings directly. This path has no image previews.");

        dialog.addHeader("Low-risk to set blind");
        if (requested.contains(BinField.CHANNEL_NAMES)) {
            state.channelNames = dialog.addStringField("Channel names", joinOrDefault(
                    existing.channelNames, defaultChannelNames(channelCount)), 24);
            dialog.addHelpText("Use one name per channel, separated by spaces.");
        }
        if (requested.contains(BinField.CHANNEL_COLORS)) {
            state.channelColors = dialog.addStringField("LUT colours", joinOrDefault(
                    existing.channelColors, repeated(DEFAULT_COLOR, channelCount)), 24);
            dialog.addHelpText("Use ImageJ LUT names such as Grays, Red, Green, Blue, Cyan, Magenta, Yellow.");
        }
        if (requested.contains(BinField.Z_SLICE)) {
            state.zSliceMode = dialog.addChoice("Z-slice mode", zSliceLabels(),
                    labelForZSliceMode(existing.zSliceMode));
        }

        if (containsImageDependentField(requested)) {
            dialog.addHeader("Image-dependent - preview recommended");
            dialog.addMessage("Particle size limits typically benefit from preview tuning. Consider option 2 if you are unsure.");
        }
        if (requested.contains(BinField.OBJECT_THRESHOLDS)) {
            state.objectThresholds = dialog.addStringField("Object thresholds", joinOrDefault(
                    existing.channelThresholds, repeated(DEFAULT_OBJECT_THRESHOLD, channelCount)), 24);
        }
        if (requested.contains(BinField.PARTICLE_SIZES)) {
            state.particleSizes = dialog.addStringField("Particle sizes", joinOrDefault(
                    existing.channelSizes, repeated(DEFAULT_PARTICLE_SIZE, channelCount)), 24);
        }
        if (requested.contains(BinField.DISPLAY_MIN_MAX)) {
            state.displayMinMax = dialog.addStringField("Display min-max", joinOrDefault(
                    existing.channelMinMax, repeated(DEFAULT_MIN_MAX, channelCount)), 24);
        }
        if (requested.contains(BinField.INTENSITY_THRESHOLDS)) {
            state.intensityThresholds = dialog.addStringField("Intensity thresholds", joinOrDefault(
                    existing.channelIntensityThresholds, repeated(DEFAULT_INTENSITY_THRESHOLD, channelCount)), 24);
        }
        if (requested.contains(BinField.SEGMENTATION_METHODS)) {
            state.segmentationMethods = dialog.addStringField("Segmentation methods", joinOrDefault(
                    existing.segmentationMethods, repeated(DEFAULT_SEGMENTATION, channelCount)), 24);
        }
        if (requested.contains(BinField.FILTER_PRESETS)) {
            state.filterPresets = dialog.addStringField("Filter presets", joinOrDefault(
                    existing.channelFilterPresets, repeated(DEFAULT_FILTER_PRESET, channelCount)), 24);
        }
        return state;
    }

    private static void applyDialogValues(DialogState state, BinConfig cfg) {
        int channelCount = defaultChannelCount(cfg, state.requestedFields());

        if (state.channelNames != null) {
            List<String> names = parseRequiredTokens(state.channelNames.getText(), "Channel names");
            cfg.channelNames.clear();
            cfg.channelNames.addAll(names);
            channelCount = names.size();
        }
        if (state.channelColors != null) replaceTokens(cfg.channelColors,
                state.channelColors.getText(), DEFAULT_COLOR, channelCount);
        if (state.objectThresholds != null) replaceTokens(cfg.channelThresholds,
                state.objectThresholds.getText(), DEFAULT_OBJECT_THRESHOLD, channelCount);
        if (state.particleSizes != null) replaceTokens(cfg.channelSizes,
                state.particleSizes.getText(), DEFAULT_PARTICLE_SIZE, channelCount);
        if (state.displayMinMax != null) replaceTokens(cfg.channelMinMax,
                state.displayMinMax.getText(), DEFAULT_MIN_MAX, channelCount);
        if (state.intensityThresholds != null) replaceTokens(cfg.channelIntensityThresholds,
                state.intensityThresholds.getText(), DEFAULT_INTENSITY_THRESHOLD, channelCount);
        if (state.segmentationMethods != null) replaceTokens(cfg.segmentationMethods,
                state.segmentationMethods.getText(), DEFAULT_SEGMENTATION, channelCount);
        if (state.filterPresets != null) replaceTokens(cfg.channelFilterPresets,
                state.filterPresets.getText(), DEFAULT_FILTER_PRESET, channelCount);
        if (state.zSliceMode != null) {
            cfg.zSliceMode = modeForLabel(String.valueOf(state.zSliceMode.getSelectedItem()));
            cfg.zSliceConfigPresent = true;
        }
    }

    private static EnumSet<BinField> normalize(Set<BinField> fields) {
        if (fields == null || fields.isEmpty()) return EnumSet.noneOf(BinField.class);
        return EnumSet.copyOf(fields);
    }

    private static boolean containsImageDependentField(Set<BinField> fields) {
        return fields.contains(BinField.OBJECT_THRESHOLDS)
                || fields.contains(BinField.PARTICLE_SIZES)
                || fields.contains(BinField.DISPLAY_MIN_MAX)
                || fields.contains(BinField.INTENSITY_THRESHOLDS)
                || fields.contains(BinField.SEGMENTATION_METHODS)
                || fields.contains(BinField.FILTER_PRESETS);
    }

    private static int defaultChannelCount(BinConfig cfg, Set<BinField> requested) {
        int n = maxSize(cfg.channelNames, cfg.channelColors, cfg.channelThresholds,
                cfg.channelSizes, cfg.channelMinMax, cfg.channelIntensityThresholds,
                cfg.segmentationMethods, cfg.channelFilterPresets);
        if (n > 0) return n;
        return requested != null && requested.contains(BinField.CHANNEL_NAMES) ? 3 : 1;
    }

    private static List<String> defaultChannelNames(int n) {
        List<String> names = new ArrayList<String>();
        for (int i = 0; i < n; i++) {
            names.add("Channel" + (i + 1));
        }
        return names;
    }

    private static List<String> repeated(String value, int n) {
        List<String> values = new ArrayList<String>();
        for (int i = 0; i < n; i++) values.add(value);
        return values;
    }

    private static String joinOrDefault(List<String> values, List<String> fallback) {
        if (values == null || values.isEmpty()) return join(fallback);
        return join(values);
    }

    private static String join(List<String> values) {
        StringBuilder sb = new StringBuilder();
        if (values != null) {
            for (int i = 0; i < values.size(); i++) {
                if (i > 0) sb.append(' ');
                sb.append(values.get(i) == null ? "" : values.get(i));
            }
        }
        return sb.toString();
    }

    private static List<String> parseRequiredTokens(String text, String label) {
        List<String> values = parseTokens(text);
        if (values.isEmpty()) {
            throw new IllegalArgumentException(label + " cannot be blank.");
        }
        return values;
    }

    private static void replaceTokens(List<String> target, String text, String fallback, int channelCount) {
        List<String> values = parseTokens(text);
        while (values.size() < channelCount) values.add(fallback);
        while (values.size() > channelCount) values.remove(values.size() - 1);
        target.clear();
        target.addAll(values);
    }

    private static List<String> parseTokens(String text) {
        List<String> values = new ArrayList<String>();
        if (text == null || text.trim().isEmpty()) return values;
        String[] parts = text.trim().split("\\s+");
        for (int i = 0; i < parts.length; i++) {
            if (!parts[i].trim().isEmpty()) values.add(parts[i].trim());
        }
        return values;
    }

    private static String[] zSliceLabels() {
        ZSliceMode[] modes = ZSliceMode.values();
        String[] labels = new String[modes.length];
        for (int i = 0; i < modes.length; i++) labels[i] = modes[i].displayName;
        return labels;
    }

    private static String labelForZSliceMode(ZSliceMode mode) {
        return (mode == null ? ZSliceMode.FULL : mode).displayName;
    }

    private static ZSliceMode modeForLabel(String label) {
        ZSliceMode[] modes = ZSliceMode.values();
        for (int i = 0; i < modes.length; i++) {
            if (modes[i].displayName.equals(label)) return modes[i];
        }
        return ZSliceMode.FULL;
    }

    @SafeVarargs
    private static int maxSize(List<String>... lists) {
        int max = 0;
        if (lists == null) return max;
        for (int i = 0; i < lists.length; i++) {
            if (lists[i] != null && lists[i].size() > max) max = lists[i].size();
        }
        return max;
    }

    private static BinConfig copyConfig(BinConfig source) {
        BinConfig copy = new BinConfig();
        if (source == null) return copy;
        copy.channelNames.addAll(source.channelNames);
        copy.channelColors.addAll(source.channelColors);
        copy.channelThresholds.addAll(source.channelThresholds);
        copy.channelSizes.addAll(source.channelSizes);
        copy.channelMinMax.addAll(source.channelMinMax);
        copy.channelIntensityThresholds.addAll(source.channelIntensityThresholds);
        copy.segmentationMethods.addAll(source.segmentationMethods);
        copy.channelFilterPresets.addAll(source.channelFilterPresets);
        copy.zSliceMode = source.zSliceMode;
        copy.zSliceConfigPresent = source.zSliceConfigPresent;
        copy.zSliceSelections.putAll(source.zSliceSelections);
        return copy;
    }

    private static final class DialogState {
        final PipelineDialog dialog;
        JTextField channelNames;
        JTextField channelColors;
        JTextField objectThresholds;
        JTextField particleSizes;
        JTextField displayMinMax;
        JTextField intensityThresholds;
        JTextField segmentationMethods;
        JTextField filterPresets;
        JComboBox<String> zSliceMode;

        DialogState(PipelineDialog dialog) {
            this.dialog = dialog;
        }

        Set<BinField> requestedFields() {
            EnumSet<BinField> fields = EnumSet.noneOf(BinField.class);
            if (channelNames != null) fields.add(BinField.CHANNEL_NAMES);
            if (channelColors != null) fields.add(BinField.CHANNEL_COLORS);
            if (objectThresholds != null) fields.add(BinField.OBJECT_THRESHOLDS);
            if (particleSizes != null) fields.add(BinField.PARTICLE_SIZES);
            if (displayMinMax != null) fields.add(BinField.DISPLAY_MIN_MAX);
            if (intensityThresholds != null) fields.add(BinField.INTENSITY_THRESHOLDS);
            if (segmentationMethods != null) fields.add(BinField.SEGMENTATION_METHODS);
            if (filterPresets != null) fields.add(BinField.FILTER_PRESETS);
            if (zSliceMode != null) fields.add(BinField.Z_SLICE);
            return fields;
        }
    }
}
