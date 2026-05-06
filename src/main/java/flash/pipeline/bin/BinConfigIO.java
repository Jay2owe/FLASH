package flash.pipeline.bin;

import flash.pipeline.image.NamedFilterLoader;
import flash.pipeline.io.FlashProjectLayout;
import flash.pipeline.zslice.ZSliceConfig;
import flash.pipeline.zslice.ZSliceConfigIO;
import flash.pipeline.zslice.ZSliceMode;
import ij.IJ;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Base64;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class BinConfigIO {
    private static final String CUSTOM_FILTER_PRESET_DIR = FlashProjectLayout.CUSTOM_FILTER_PRESET_DIR;
    private static final String CUSTOM_FILTER_TOKEN_PREFIX = "custom_filter:";
    private static final String TOKEN_DELIMITER = "\t";
    /** UTF-8 BOM as a Java char (U+FEFF). */
    static final char UTF8_BOM = '﻿';

    /**
     * Reads the macro-created file:
     *   Directory/Configuration folder/Channel_Data.txt
     *
     * Expected lines:
     * 1) name_info              (space-separated)
     * 2) color_info             (space-separated)
     * 3) object_threshold_info  (space-separated)
     * 4) size_info              (space-separated)
     * 5) minmax_info            (space-separated; optional)
     * 6) intensity_threshold    (space-separated; optional, defaults to "default")
     * 7) segmentation_methods   (space-separated; optional, defaults to "classical")
     * 8) filter_presets         (space-separated; optional, inferred from C*_Filters.ijm for legacy bins)
     * 9) z-slice mode           (optional; e.g. zslice:full, zslice:per_image)
     */
    public static BinConfig readFromDirectory(String Directory) throws IOException {
        FlashProjectLayout layout = FlashProjectLayout.forDirectory(Directory);
        File channelData = layout.channelDataReadFile();
        File binFolder = channelData.getParentFile();
        if (!channelData.exists()) {
            throw new IOException("Missing " + channelData.getAbsolutePath() + ". Run Set Up Configuration first.");
        }

        List<String> lines = Files.readAllLines(channelData.toPath(), StandardCharsets.UTF_8);
        stripLeadingBom(lines);
        if (lines.size() < 4) {
            throw new IOException("Channel_Data.txt has too few lines: " + lines.size());
        }
        String[] names = splitTokens(lines.get(0));
        String[] colors = (lines.size() > 1) ? splitTokens(lines.get(1)) : new String[0];
        String[] thresholds = (lines.size() > 2) ? splitTokens(lines.get(2)) : new String[0];
        String[] sizes = (lines.size() > 3) ? splitTokens(lines.get(3)) : new String[0];
        String[] minmax = (lines.size() > 4) ? splitTokens(lines.get(4)) : new String[names.length];
        String[] intensityThresholds = (lines.size() > 5) ? splitTokens(lines.get(5)) : new String[0];
        String[] segmentationMethods = (lines.size() > 6) ? splitTokens(lines.get(6)) : new String[0];
        String[] storedFilterPresets = (lines.size() > 7) ? splitTokens(lines.get(7)) : new String[0];
        ZSliceMode zSliceMode = (lines.size() > 8) ? ZSliceConfigIO.parseModeLine(lines.get(8)) : ZSliceMode.FULL;
        // Skip up-front inference when every channel has a stored preset token; resolveFilterPresetForChannel
        // will lazy-call inferFilterPreset only for channels whose stored token resolves to "Custom".
        String[] inferredFilterPresets = (storedFilterPresets.length >= names.length)
                ? new String[names.length]
                : inferFilterPresets(binFolder, names.length);

        BinConfig cfg = new BinConfig();

        for (int i = 0; i < names.length; i++) {
            cfg.channelNames.add(names[i]);
            cfg.channelColors.add(i < colors.length ? colors[i] : "Grays");
            cfg.channelThresholds.add(i < thresholds.length ? thresholds[i] : "default");
            cfg.channelSizes.add(i < sizes.length ? sizes[i] : "100-Infinity");
            cfg.channelMinMax.add(i < minmax.length ? minmax[i] : "None");
            cfg.channelIntensityThresholds.add(i < intensityThresholds.length ? intensityThresholds[i] : "default");
            String preset = resolveFilterPresetForChannel(binFolder, i, storedFilterPresets, inferredFilterPresets);
            cfg.channelFilterPresets.add(preset);
        }

        // Line 7: segmentation methods (optional, defaults to "classical")
        for (String m : segmentationMethods) {
            cfg.segmentationMethods.add(m);
        }
        // Pad to match channel count with defaults
        while (cfg.segmentationMethods.size() < cfg.numChannels()) {
            cfg.segmentationMethods.add("classical");
        }

        cfg.zSliceMode = zSliceMode;
        cfg.zSliceConfigPresent = lines.size() > 8;
        try {
            cfg.zSliceSelections.putAll(ZSliceConfigIO.readSelections(binFolder));
            if (!cfg.zSliceSelections.isEmpty()) {
                cfg.zSliceConfigPresent = true;
            }
            if (cfg.zSliceMode == ZSliceMode.FULL && !cfg.zSliceSelections.isEmpty()) {
                cfg.zSliceMode = ZSliceMode.PER_IMAGE;
            }
        } catch (IOException e) {
            IJ.log("Warning: could not read z-slice selections from " + binFolder.getAbsolutePath()
                    + ": " + e.getMessage() + ". Falling back to full-stack analysis.");
            cfg.zSliceMode = ZSliceMode.FULL;
            cfg.zSliceConfigPresent = false;
            cfg.zSliceSelections.clear();
        }

        IJ.log("Loaded BinConfig: " + cfg.numChannels() + " channels from " + channelData.getAbsolutePath());

        return cfg;
    }

    /**
     * Reads whatever configuration is present without requiring a complete
     * Channel_Data.txt. Missing parameters remain empty so callers can ask what
     * still needs to be collected.
     */
    public static BinConfig readPartialFromDirectory(String directory) {
        BinConfig cfg = new BinConfig();
        FlashProjectLayout layout = FlashProjectLayout.forDirectory(directory);
        File channelData = layout.channelDataReadFile();
        File binFolder = channelData.getParentFile();
        if (!channelData.exists()) {
            return cfg;
        }

        try {
            List<String> lines = Files.readAllLines(channelData.toPath(), StandardCharsets.UTF_8);
            stripLeadingBom(lines);
            String[] names = (lines.size() > 0) ? splitTokens(lines.get(0)) : new String[0];
            String[] colors = (lines.size() > 1) ? splitTokens(lines.get(1)) : new String[0];
            String[] thresholds = (lines.size() > 2) ? splitTokens(lines.get(2)) : new String[0];
            String[] sizes = (lines.size() > 3) ? splitTokens(lines.get(3)) : new String[0];
            String[] minmax = (lines.size() > 4) ? splitTokens(lines.get(4)) : new String[0];
            String[] intensityThresholds = (lines.size() > 5) ? splitTokens(lines.get(5)) : new String[0];
            String[] segmentationMethods = (lines.size() > 6) ? splitTokens(lines.get(6)) : new String[0];
            boolean hasFilterPresetLine = lines.size() > 7;
            String[] storedFilterPresets = hasFilterPresetLine ? splitTokens(lines.get(7)) : new String[0];
            // Skip up-front inference only when every channel already has a stored preset token; in that
            // case resolveFilterPresetForChannel lazy-calls inferFilterPreset for the channels that need it.
            String[] inferredFilterPresets = (hasFilterPresetLine && storedFilterPresets.length >= names.length)
                    ? new String[names.length]
                    : inferFilterPresets(binFolder, names.length);

            addTokens(cfg.channelNames, names);
            addTokens(cfg.channelColors, colors);
            addTokens(cfg.channelThresholds, thresholds);
            addTokens(cfg.channelSizes, sizes);
            addTokens(cfg.channelMinMax, minmax);
            addTokens(cfg.channelIntensityThresholds, intensityThresholds);
            addTokens(cfg.segmentationMethods, segmentationMethods);
            if (hasFilterPresetLine) {
                for (int i = 0; i < storedFilterPresets.length; i++) {
                    cfg.channelFilterPresets.add(resolveFilterPresetForChannel(
                            binFolder, i, storedFilterPresets, inferredFilterPresets));
                }
            } else {
                for (int i = 0; i < inferredFilterPresets.length; i++) {
                    cfg.channelFilterPresets.add(inferredFilterPresets[i]);
                }
            }

            if (lines.size() > 8) {
                cfg.zSliceMode = ZSliceConfigIO.parseModeLine(lines.get(8));
                cfg.zSliceConfigPresent = true;
            }
            try {
                cfg.zSliceSelections.putAll(ZSliceConfigIO.readSelections(binFolder));
                if (!cfg.zSliceSelections.isEmpty()) {
                    if (cfg.zSliceMode == ZSliceMode.FULL) {
                        cfg.zSliceMode = ZSliceMode.PER_IMAGE;
                    }
                    cfg.zSliceConfigPresent = true;
                }
            } catch (IOException e) {
                IJ.log("Warning: could not read z-slice selections from " + binFolder.getAbsolutePath()
                        + ": " + e.getMessage() + ". Falling back to full-stack analysis.");
                cfg.zSliceMode = ZSliceMode.FULL;
                cfg.zSliceConfigPresent = false;
                cfg.zSliceSelections.clear();
            }
        } catch (IOException e) {
            IJ.log("[FLASH] Could not read partial bin: " + e.getMessage());
        }
        return cfg;
    }

    /**
     * Writes the standard Configuration folder/Channel_Data.txt format from a BinConfig.
     * Empty lists are preserved as blank lines so partial configurations can
     * keep later-stage parameters genuinely missing for the soft reader.
     */
    public static void writeFromConfig(String directory, BinConfig cfg) throws IOException {
        if (directory == null || directory.trim().isEmpty()) {
            throw new IOException("Cannot write configuration without a directory.");
        }
        BinConfig safe = cfg == null ? new BinConfig() : cfg;
        File binFolder = FlashProjectLayout.forDirectory(directory).configurationWriteDir();
        if (!binFolder.isDirectory() && !binFolder.mkdirs() && !binFolder.isDirectory()) {
            throw new IOException("Failed to create " + binFolder.getAbsolutePath());
        }

        List<String> lines = new ArrayList<String>();
        lines.add(joinTokens(safe.channelNames));
        lines.add(joinTokens(safe.channelColors));
        lines.add(joinTokens(safe.channelThresholds));
        lines.add(joinTokens(safe.channelSizes));
        lines.add(joinTokens(safe.channelMinMax));
        lines.add(joinTokens(safe.channelIntensityThresholds));
        lines.add(joinTokens(safe.segmentationMethods));

        List<String> filterPresetTokens = new ArrayList<String>();
        for (int i = 0; i < safe.channelFilterPresets.size(); i++) {
            filterPresetTokens.add(encodeFilterPresetToken(safe.channelFilterPresets.get(i)));
        }
        lines.add(joinTokens(filterPresetTokens));
        lines.add(ZSliceConfigIO.modeLine(safe.zSliceMode));

        File channelData = new File(binFolder, "Channel_Data.txt");
        writeAtomic(channelData.toPath(), lines);
        ZSliceConfigIO.writeSelections(binFolder, new ZSliceConfig(safe.zSliceMode, safe.zSliceSelections));
        writeFilterMacrosFromConfig(binFolder, safe);
    }

    /**
     * Writes per-channel filter macros matching the filter preset list in a
     * {@link BinConfig}. Direct-entry and headless CLI setup both route through
     * {@link #writeFromConfig}, so this keeps their FILTER_PRESETS side effects
     * equivalent to the preview wizard.
     */
    public static void writeFilterMacrosFromConfig(File binFolder, BinConfig cfg) throws IOException {
        if (binFolder == null || cfg == null || cfg.channelFilterPresets.isEmpty()) return;
        if (!binFolder.isDirectory() && !binFolder.mkdirs() && !binFolder.isDirectory()) {
            throw new IOException("Failed to create " + binFolder.getAbsolutePath());
        }

        String defaultMacro = NamedFilterLoader.loadDefaultFilter();
        if (defaultMacro != null) {
            Files.write(new File(binFolder, "defaultFilter.ijm").toPath(),
                    defaultMacro.getBytes(StandardCharsets.UTF_8));
        }

        for (int i = 0; i < cfg.channelFilterPresets.size(); i++) {
            String preset = decodeFilterPresetToken(cfg.channelFilterPresets.get(i));
            String content = NamedFilterLoader.loadFilterContent(preset);
            if (content == null) {
                content = loadSavedCustomFilterPreset(binFolder, preset);
            }
            if (content == null) {
                content = defaultMacro;
            }
            if (content != null) {
                Files.write(new File(binFolder, "C" + (i + 1) + "_Filters.ijm").toPath(),
                        content.getBytes(StandardCharsets.UTF_8));
            }
        }
    }

    /**
     * Updates line 5 (minmax_info) of Channel_Data.txt with new per-channel display range values.
     * Preserves all other lines.
     */
    public static void updateMinMax(String directory, String[] minMaxPerCh) throws IOException {
        FlashProjectLayout layout = FlashProjectLayout.forDirectory(directory);
        File channelData = layout.channelDataReadFile();
        if (!channelData.exists()) return;

        List<String> lines = Files.readAllLines(channelData.toPath(), StandardCharsets.UTF_8);
        stripLeadingBom(lines);
        // Ensure at least 5 lines exist
        while (lines.size() < 5) {
            lines.add("");
        }

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < minMaxPerCh.length; i++) {
            if (i > 0) sb.append(TOKEN_DELIMITER);
            sb.append(minMaxPerCh[i] == null ? "None" : minMaxPerCh[i]);
        }
        lines.set(4, sb.toString()); // line 5 (0-indexed = 4)

        writeAtomic(layout.channelDataWriteFile().toPath(), lines);
    }

    /**
     * Updates line 8 (filter_presets) of Channel_Data.txt with the resolved
     * per-channel filter preset names. Used after loading older bins where the
     * macro file proves that a saved custom filter is present.
     */
    public static void updateFilterPresets(String directory, List<String> filterPresets) throws IOException {
        FlashProjectLayout layout = FlashProjectLayout.forDirectory(directory);
        File channelData = layout.channelDataReadFile();
        if (!channelData.exists() || filterPresets == null) return;

        List<String> lines = Files.readAllLines(channelData.toPath(), StandardCharsets.UTF_8);
        stripLeadingBom(lines);
        while (lines.size() < 8) {
            lines.add("");
        }

        List<String> tokens = new ArrayList<String>();
        for (int i = 0; i < filterPresets.size(); i++) {
            tokens.add(encodeFilterPresetToken(filterPresets.get(i)));
        }
        lines.set(7, joinTokens(tokens));

        writeAtomic(layout.channelDataWriteFile().toPath(), lines);
    }

    static String[] splitTokens(String line) {
        if (line == null) return new String[0];
        // Primary: tab. Falls back to any whitespace for legacy files written
        // before the tab-delimiter migration.
        if (line.indexOf('\t') >= 0) {
            String[] tabs = line.split("\t", -1);
            return trimAll(tabs);
        }
        String s = line.trim();
        if (s.isEmpty()) return new String[0];
        return s.split("\\s+");
    }

    private static String[] trimAll(String[] tokens) {
        String[] out = new String[tokens.length];
        for (int i = 0; i < tokens.length; i++) {
            out[i] = tokens[i] == null ? "" : tokens[i].trim();
        }
        return out;
    }

    static void stripLeadingBom(List<String> lines) {
        if (lines == null || lines.isEmpty()) return;
        String first = lines.get(0);
        if (first != null && !first.isEmpty() && first.charAt(0) == UTF8_BOM) {
            lines.set(0, first.substring(1));
        }
    }

    static boolean anyLineContainsTab(List<String> lines) {
        if (lines == null) return false;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line != null && line.indexOf('\t') >= 0) return true;
        }
        return false;
    }

    /**
     * Writes lines to {@code target} atomically: write to a sibling .tmp file
     * then move it into place. Falls back to a non-atomic replace on filesystems
     * that don't support {@link StandardCopyOption#ATOMIC_MOVE} (older NFS, etc.).
     */
    public static void writeAtomic(Path target, List<String> lines) throws IOException {
        if (target == null) throw new IOException("writeAtomic: target path is null");
        StringBuilder content = new StringBuilder();
        if (lines != null) {
            for (int i = 0; i < lines.size(); i++) {
                content.append(lines.get(i) == null ? "" : lines.get(i));
                content.append("\n");
            }
        }
        Path parent = target.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path tmp = target.resolveSibling(target.getFileName().toString() + ".tmp");
        Files.write(tmp, content.toString().getBytes(StandardCharsets.UTF_8));
        try {
            Files.move(tmp, target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ame) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void addTokens(List<String> target, String[] tokens) {
        for (int i = 0; i < tokens.length; i++) {
            target.add(tokens[i]);
        }
    }

    private static String[] inferFilterPresets(File binFolder, int channelCount) {
        String[] presets = new String[channelCount];
        for (int i = 0; i < channelCount; i++) {
            File macroFile = new File(binFolder, "C" + (i + 1) + "_Filters.ijm");
            presets[i] = inferFilterPreset(macroFile);
        }
        return presets;
    }

    private static String inferFilterPreset(File macroFile) {
        if (macroFile == null || !macroFile.exists()) return "Default";
        try {
            String actual = normalizeMacroContent(new String(Files.readAllBytes(macroFile.toPath()), StandardCharsets.UTF_8));
            for (String presetName : NamedFilterLoader.FILTER_NAMES) {
                String bundled = NamedFilterLoader.loadFilterContent(presetName);
                if (bundled != null && actual.equals(normalizeMacroContent(bundled))) {
                    return presetName;
                }
            }
            File[] savedCustomPresets = savedCustomPresetFiles(macroFile.getParentFile());
            for (int i = 0; i < savedCustomPresets.length; i++) {
                File saved = savedCustomPresets[i];
                String custom = normalizeMacroContent(new String(Files.readAllBytes(saved.toPath()), StandardCharsets.UTF_8));
                if (actual.equals(custom)) {
                    String fileName = saved.getName();
                    return fileName.substring(0, fileName.length() - 4);
                }
            }
            return "Custom";
        } catch (IOException e) {
            return "Default";
        }
    }

    private static String resolveFilterPresetForChannel(File binFolder,
                                                        int channelIndex,
                                                        String[] storedFilterPresets,
                                                        String[] inferredFilterPresets) {
        if (channelIndex >= storedFilterPresets.length) {
            return lazyInferred(binFolder, channelIndex, inferredFilterPresets);
        }

        String stored = decodeFilterPresetToken(storedFilterPresets[channelIndex]);
        File macroFile = new File(binFolder, "C" + (channelIndex + 1) + "_Filters.ijm");
        if (!macroFile.exists()) return stored;

        if ("Custom".equals(stored)) {
            String inferred = lazyInferred(binFolder, channelIndex, inferredFilterPresets);
            return "Default".equals(inferred) ? stored : inferred;
        }

        String storedMacro = NamedFilterLoader.loadFilterContent(stored);
        if (storedMacro == null) {
            return stored;
        }

        try {
            String actual = normalizeMacroContent(new String(Files.readAllBytes(macroFile.toPath()), StandardCharsets.UTF_8));
            if (!actual.equals(normalizeMacroContent(storedMacro))) {
                return lazyInferred(binFolder, channelIndex, inferredFilterPresets);
            }
        } catch (IOException e) {
            return stored;
        }
        return stored;
    }

    /**
     * Returns inferredFilterPresets[channelIndex] if it has been populated, otherwise infers
     * just that channel's preset from its C{n}_Filters.ijm and caches the result back into
     * the array. Pairs with the readFromDirectory / readPartialFromDirectory early-skip:
     * when every channel has a stored token the array is created empty and only the channels
     * whose stored token resolves to "Custom" (or fails the macro round-trip) trigger inference.
     */
    private static String lazyInferred(File binFolder, int channelIndex, String[] inferredFilterPresets) {
        if (channelIndex < inferredFilterPresets.length && inferredFilterPresets[channelIndex] != null) {
            return inferredFilterPresets[channelIndex];
        }
        File macroFile = new File(binFolder, "C" + (channelIndex + 1) + "_Filters.ijm");
        String result = inferFilterPreset(macroFile);
        if (channelIndex < inferredFilterPresets.length) {
            inferredFilterPresets[channelIndex] = result;
        }
        return result;
    }

    private static File[] savedCustomPresetFiles(File binFolder) {
        List<File> out = new ArrayList<File>();
        List<File> dirs = layoutForBinFolder(binFolder).customFilterPresetReadDirs();
        for (int i = 0; i < dirs.size(); i++) {
            File dir = dirs.get(i);
            File[] files = dir.listFiles((parent, name) -> name != null && name.toLowerCase(Locale.ROOT).endsWith(".ijm"));
            if (files == null) continue;
            for (int f = 0; f < files.length; f++) {
                out.add(files[f]);
            }
        }
        return out.toArray(new File[out.size()]);
    }

    private static String loadSavedCustomFilterPreset(File binFolder, String presetName) {
        if (binFolder == null || presetName == null || presetName.trim().isEmpty()) return null;
        String fileName = sanitizeCustomFilterPresetName(presetName) + ".ijm";
        List<File> dirs = layoutForBinFolder(binFolder).customFilterPresetReadDirs();
        for (int i = 0; i < dirs.size(); i++) {
            File preset = resolveCustomFilterPresetFile(dirs.get(i), fileName);
            if (preset == null || !preset.isFile()) continue;
            try {
                return new String(Files.readAllBytes(preset.toPath()), StandardCharsets.UTF_8);
            } catch (IOException e) {
                return null;
            }
        }
        return null;
    }

    private static File resolveCustomFilterPresetFile(File dir, String fileName) {
        if (dir == null || fileName == null || fileName.trim().isEmpty()) return null;
        File target = new File(dir, fileName);
        try {
            File canonicalDir = dir.getCanonicalFile();
            File canonicalTarget = target.getCanonicalFile();
            String dirPath = canonicalDir.getPath();
            String targetPath = canonicalTarget.getPath();
            if (!targetPath.equals(dirPath) && !targetPath.startsWith(dirPath + File.separator)) return null;
            return canonicalTarget;
        } catch (IOException e) {
            return null;
        }
    }

    private static String sanitizeCustomFilterPresetName(String raw) {
        String trimmed = raw == null ? "" : raw.trim();
        String sanitized = trimmed.replaceAll("[<>:\"/\\\\|?*\\p{Cntrl}]+", "_").trim();
        while (sanitized.endsWith(".")) {
            sanitized = sanitized.substring(0, sanitized.length() - 1).trim();
        }
        return sanitized.length() == 0 ? "Custom Filter" : sanitized;
    }

    private static FlashProjectLayout layoutForBinFolder(File binFolder) {
        return FlashProjectLayout.forDirectory(projectRootForBinFolder(binFolder).getPath());
    }

    private static File projectRootForBinFolder(File binFolder) {
        if (binFolder == null) return new File(".");
        File parent = binFolder.getParentFile();
        if (FlashProjectLayout.LEGACY_BIN_DIR.equals(binFolder.getName()) && parent != null) {
            return parent;
        }
        if (FlashProjectLayout.CONFIGURATION_DIR.equals(binFolder.getName())
                && parent != null
                && FlashProjectLayout.FLASH_DIR.equals(parent.getName())
                && parent.getParentFile() != null) {
            return parent.getParentFile();
        }
        return binFolder;
    }

    public static String encodeFilterPresetToken(String presetName) {
        String normalized = decodeFilterPresetToken(presetName);
        if ("Default".equals(normalized)) return "default";
        if ("Punctate Signal / High Background".equals(normalized)) return "punctate_signal_high_background";
        if ("Ramified Cells (Microglia/Astrocytes)".equals(normalized)) return "ramified_cells_microglia_astrocytes";
        if ("Clustered Small".equals(normalized)) return "clustered_small";
        if ("Clustered Large".equals(normalized)) return "clustered_large";
        if ("Overlapping Cellular Marker".equals(normalized)) return "overlapping_cellular_marker";
        if ("Puncta Resolve".equals(normalized)) return "puncta_resolve";
        if ("Diffuse Object".equals(normalized)) return "diffuse_object";
        if ("Custom".equals(normalized)) return "custom";
        return CUSTOM_FILTER_TOKEN_PREFIX + percentEncodeToken(normalized);
    }

    private static String decodeFilterPresetToken(String presetName) {
        if (presetName == null || presetName.trim().isEmpty()) return "Default";
        String normalized = presetName.trim();
        if ("default".equalsIgnoreCase(normalized)) return "Default";
        if ("punctate_signal_high_background".equalsIgnoreCase(normalized)) return "Punctate Signal / High Background";
        if ("ramified_cells_microglia_astrocytes".equalsIgnoreCase(normalized)) return "Ramified Cells (Microglia/Astrocytes)";
        if ("clustered_small".equalsIgnoreCase(normalized)) return "Clustered Small";
        if ("clustered_large".equalsIgnoreCase(normalized)) return "Clustered Large";
        if ("overlapping_cellular_marker".equalsIgnoreCase(normalized)) return "Overlapping Cellular Marker";
        if ("puncta_resolve".equalsIgnoreCase(normalized)) return "Puncta Resolve";
        if ("diffuse_object".equalsIgnoreCase(normalized)) return "Diffuse Object";
        if ("custom".equalsIgnoreCase(normalized)) return "Custom";
        if (normalized.toLowerCase(Locale.ROOT).startsWith(CUSTOM_FILTER_TOKEN_PREFIX)) {
            String value = percentDecodeToken(normalized.substring(CUSTOM_FILTER_TOKEN_PREFIX.length())).trim();
            if (!value.isEmpty()) return value;
        }
        if (normalized.toLowerCase(Locale.ROOT).startsWith("userfilter_")) {
            try {
                byte[] decoded = Base64.getUrlDecoder().decode(normalized.substring("userfilter_".length()));
                String value = new String(decoded, StandardCharsets.UTF_8).trim();
                if (!value.isEmpty()) return value;
            } catch (IllegalArgumentException ignored) {
                // Fall through to legacy decoding.
            }
        }
        for (String candidate : NamedFilterLoader.FILTER_NAMES) {
            if (candidate.equalsIgnoreCase(normalized)) return candidate;
        }
        if ("Custom".equalsIgnoreCase(normalized)) return "Custom";
        if ("Default Filter".equalsIgnoreCase(normalized)) return "Default";
        if ("High Signal-Noise Particle Filter".equalsIgnoreCase(normalized)) return "Punctate Signal / High Background";
        if ("Microglia Filter".equalsIgnoreCase(normalized)) return "Ramified Cells (Microglia/Astrocytes)";
        if ("Clustered Small Particle Filter".equalsIgnoreCase(normalized)) return "Clustered Small";
        if ("Clustered Large Particle Filter".equalsIgnoreCase(normalized)) return "Clustered Large";
        if ("Overlapping Cellular Marker Filter".equalsIgnoreCase(normalized)) return "Overlapping Cellular Marker";
        return normalized;
    }

    private static String joinTokens(List<String> tokens) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < tokens.size(); i++) {
            if (i > 0) sb.append(TOKEN_DELIMITER);
            sb.append(tokens.get(i));
        }
        return sb.toString();
    }

    private static String percentEncodeToken(String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < bytes.length; i++) {
            int b = bytes[i] & 0xff;
            if ((b >= 'A' && b <= 'Z')
                    || (b >= 'a' && b <= 'z')
                    || (b >= '0' && b <= '9')
                    || b == '-' || b == '_' || b == '.' || b == '~') {
                sb.append((char) b);
            } else {
                sb.append('%');
                char high = Character.toUpperCase(Character.forDigit((b >> 4) & 0x0f, 16));
                char low = Character.toUpperCase(Character.forDigit(b & 0x0f, 16));
                sb.append(high).append(low);
            }
        }
        return sb.toString();
    }

    private static String percentDecodeToken(String encoded) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int i = 0; i < encoded.length(); i++) {
            char ch = encoded.charAt(i);
            if (ch == '%' && i + 2 < encoded.length()) {
                int high = Character.digit(encoded.charAt(i + 1), 16);
                int low = Character.digit(encoded.charAt(i + 2), 16);
                if (high >= 0 && low >= 0) {
                    out.write((high << 4) + low);
                    i += 2;
                    continue;
                }
            }
            byte[] raw = String.valueOf(ch).getBytes(StandardCharsets.UTF_8);
            out.write(raw, 0, raw.length);
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    private static String normalizeMacroContent(String content) {
        if (content == null) return "";
        return content.replace("\r\n", "\n").replace('\r', '\n').trim();
    }
}
