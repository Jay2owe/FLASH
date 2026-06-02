package flash.pipeline.bin;

import flash.pipeline.image.NamedFilterLoader;
import flash.pipeline.io.FlashProjectLayout;
import flash.pipeline.io.IoUtils;
import flash.pipeline.segmentation.SegmentationTokenCodec;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

public class BinConfigIO {
    private static final String CUSTOM_FILTER_TOKEN_PREFIX = "custom_filter:";

    public static BinConfig readFromDirectory(String directory) throws IOException {
        FlashProjectLayout layout = FlashProjectLayout.forDirectory(directory);
        File settingsDir = layout.configurationWriteDir();
        ChannelConfig channelConfig = ChannelConfigIO.read(settingsDir);
        if (channelConfig == null) {
            throw new IOException("Missing " + new File(settingsDir, ChannelConfigIO.FILE_NAME).getAbsolutePath()
                    + ". Run Set Up Configuration first.");
        }
        if (!ChannelConfigIO.allChannelsCommitted(channelConfig)) {
            throw new IOException("Incomplete " + new File(settingsDir, ChannelConfigIO.FILE_NAME).getAbsolutePath()
                    + ". Finish Set Up Configuration first.");
        }
        return ChannelConfigIO.toBinConfig(channelConfig, settingsDir);
    }

    public static BinConfig readPartialFromDirectory(String directory) {
        FlashProjectLayout layout = FlashProjectLayout.forDirectory(directory);
        File settingsDir = layout.configurationWriteDir();
        ChannelConfig channelConfig = ChannelConfigIO.read(settingsDir);
        return channelConfig == null ? new BinConfig() : ChannelConfigIO.toPartialBinConfig(channelConfig, settingsDir);
    }

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

    public static void updateMinMax(String directory, String[] minMaxPerCh) throws IOException {
        if (minMaxPerCh == null) return;
        FlashProjectLayout layout = FlashProjectLayout.forDirectory(directory);
        File settingsDir = layout.configurationWriteDir();
        ChannelConfig cfg = ChannelConfigIO.read(settingsDir);
        if (cfg == null || cfg.channels == null) return;

        for (int i = 0; i < minMaxPerCh.length && i < cfg.channels.size(); i++) {
            ChannelConfig.Channel channel = cfg.channels.get(i);
            if (channel != null) {
                channel.minmax = minMaxPerCh[i] == null ? "None" : minMaxPerCh[i];
                channel.status.put(ChannelConfig.P_MINMAX, ChannelConfig.PropertyStatus.COMMITTED);
            }
        }
        cfg.writtenAtMillis = System.currentTimeMillis();
        ChannelConfigIO.write(settingsDir, cfg);
    }

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
        Path tempDir = parent == null ? new File(".").toPath() : parent;
        Path tmp = Files.createTempFile(tempDir, "." + target.getFileName().toString() + ".", ".tmp");
        byte[] bytes = content.toString().getBytes(StandardCharsets.UTF_8);
        try (FileChannel channel = FileChannel.open(tmp, StandardOpenOption.WRITE)) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
            channel.force(true);
        }
        // Atomic temp-and-move with retry/backoff, then in-place rewrite if the
        // destination stays locked against rename (Windows + Dropbox/OneDrive).
        IoUtils.commitReplacingSmallFile(tmp, target);
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
        String folderName = binFolder.getName();
        if (FlashProjectLayout.SETTINGS_DIR.equals(folderName)
                && parent != null
                && FlashProjectLayout.CONFIGURATION_DIR.equals(parent.getName())
                && parent.getParentFile() != null
                && FlashProjectLayout.FLASH_DIR.equals(parent.getParentFile().getName())
                && parent.getParentFile().getParentFile() != null) {
            return parent.getParentFile().getParentFile();
        }
        if (FlashProjectLayout.CONFIGURATION_DIR.equals(folderName)
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
        return CUSTOM_FILTER_TOKEN_PREFIX + SegmentationTokenCodec.percentEncodeToken(normalized);
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
            String value = SegmentationTokenCodec.percentDecodeToken(
                    normalized.substring(CUSTOM_FILTER_TOKEN_PREFIX.length())).trim();
            if (!value.isEmpty()) return value;
        }
        if (normalized.toLowerCase(Locale.ROOT).startsWith("userfilter_")) {
            try {
                byte[] decoded = Base64.getUrlDecoder().decode(normalized.substring("userfilter_".length()));
                String value = new String(decoded, StandardCharsets.UTF_8).trim();
                if (!value.isEmpty()) return value;
            } catch (IllegalArgumentException ignored) {
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
}
