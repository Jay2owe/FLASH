package flash.pipeline.segmentation.catalog;

import flash.pipeline.bin.ChannelConfig;
import flash.pipeline.bin.ChannelConfigCodec;
import flash.pipeline.bin.ChannelConfigIO;
import flash.pipeline.io.FlashProjectLayout;
import flash.pipeline.segmentation.SegmentationMethod;
import flash.pipeline.segmentation.SegmentationTokenParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Renames a project model key and rewrites saved bin segmentation references.
 */
public final class ModelKeyRewriter {
    private final WriteHook writeHook;

    public ModelKeyRewriter() {
        this(null);
    }

    ModelKeyRewriter(WriteHook writeHook) {
        this.writeHook = writeHook;
    }

    public static RenameResult rename(String oldKey, String newKey, Path projectRoot)
            throws IOException {
        return new ModelKeyRewriter().renameProject(oldKey, newKey, projectRoot);
    }

    public RenameResult renameProject(String oldKey, String newKey, Path projectRoot)
            throws IOException {
        validateKey("oldKey", oldKey);
        validateKey("newKey", newKey);
        if (oldKey.equals(newKey)) {
            return new RenameResult(0, 0, false, false);
        }
        if (projectRoot == null) {
            throw new IOException("Project root must not be null.");
        }

        Path root = projectRoot.toAbsolutePath().normalize();
        ModelCatalog catalog = ModelCatalogIO.read(root);
        Optional<ModelEntry> existing = catalog.get(oldKey);
        if (!existing.isPresent()) {
            throw new IOException("Model entry not found: " + oldKey);
        }
        if (!ModelCatalogIO.isProjectWritableEntry(existing.get())) {
            throw new IOException("Only project model entries can be renamed.");
        }
        if (catalog.get(newKey).isPresent()) {
            throw new IOException("Model key already exists: " + newKey);
        }

        List<RewritePlan> plans = planBinRewrites(root, oldKey, newKey);
        List<RewritePlan> applied = new ArrayList<RewritePlan>();
        boolean filesRenamed = false;
        try {
            for (RewritePlan plan : plans) {
                if (writeHook != null) {
                    writeHook.beforeWrite(plan.path);
                }
                ChannelConfigIO.write(plan.path.getParent().toFile(), plan.updatedConfig);
                applied.add(plan);
            }
            filesRenamed = catalog.rename(oldKey, newKey);
            ModelCatalogIO.writeProject(root, catalog);
            return new RenameResult(plans.size(), channelCount(plans), filesRenamed, false);
        } catch (IOException e) {
            rollback(applied);
            if (filesRenamed) {
                try {
                    catalog.rename(newKey, oldKey);
                    ModelCatalogIO.writeProject(root, catalog);
                } catch (IOException ignored) {
                    // Preserve the original failure; the caller needs the actionable cause.
                }
            }
            throw e;
        }
    }

    static String rewriteTokenForTest(String token, String oldKey, String newKey) {
        return rewriteToken(token, oldKey, newKey).token;
    }

    private static List<RewritePlan> planBinRewrites(Path projectRoot,
                                                     String oldKey,
                                                     String newKey) throws IOException {
        List<Path> channelConfigFiles = findChannelConfigFiles(projectRoot);
        List<RewritePlan> plans = new ArrayList<RewritePlan>();
        for (Path file : channelConfigFiles) {
            String originalJson = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
            ChannelConfig cfg = ChannelConfigCodec.decode(originalJson);
            if (cfg.channels == null || cfg.channels.isEmpty()) {
                continue;
            }
            int changed = 0;
            for (ChannelConfig.Channel channel : cfg.channels) {
                if (channel == null) {
                    continue;
                }
                TokenRewrite rewrite = rewriteToken(channel.segmentationMethod, oldKey, newKey);
                if (rewrite.changed) {
                    channel.segmentationMethod = rewrite.token;
                    changed++;
                }
            }
            if (changed > 0) {
                plans.add(new RewritePlan(file, originalJson, cfg, changed));
            }
        }
        return plans;
    }

    private static List<Path> findChannelConfigFiles(Path projectRoot) throws IOException {
        List<Path> out = new ArrayList<Path>();
        if (!Files.exists(projectRoot)) {
            return out;
        }
        try (Stream<Path> stream = Files.walk(projectRoot)) {
            stream.forEach(path -> {
                if (Files.isRegularFile(path)
                        && ChannelConfigIO.FILE_NAME.equals(path.getFileName().toString())
                        && looksLikeConfigPath(path)) {
                    out.add(path.toAbsolutePath().normalize());
                }
            });
        }
        Collections.sort(out);
        return out;
    }

    private static boolean looksLikeConfigPath(Path path) {
        String normalized = path.toString().replace('\\', '/');
        return normalized.contains("/" + FlashProjectLayout.FLASH_DIR
                + "/" + FlashProjectLayout.CONFIGURATION_DIR
                + "/" + FlashProjectLayout.SETTINGS_DIR + "/");
    }

    private static TokenRewrite rewriteToken(String token, String oldKey, String newKey) {
        String raw = token == null ? "" : token.trim();
        if (raw.isEmpty()) {
            return new TokenRewrite(token == null ? "" : token, false);
        }
        try {
            SegmentationMethod method = SegmentationTokenParser.parse(raw);
            if (method.isStarDist()) {
                return rewriteModelParam(method, raw, oldKey, newKey);
            }
            if (method.isCellpose()) {
                return rewriteModelParam(method, raw, oldKey, newKey);
            }
            if (method.isTrainedRf()) {
                return rewriteTrainedRf(method, raw, oldKey, newKey);
            }
        } catch (IllegalArgumentException ignored) {
            return rewriteRawToken(raw, oldKey, newKey);
        }
        return new TokenRewrite(raw, false);
    }

    private static TokenRewrite rewriteModelParam(SegmentationMethod method,
                                                  String raw,
                                                  String oldKey,
                                                  String newKey) {
        String current = method.params.get("model");
        if (current == null || !current.equals(oldKey)) {
            return new TokenRewrite(raw, false);
        }
        Map<String, String> params = new LinkedHashMap<String, String>(method.params);
        params.put("model", newKey);
        return new TokenRewrite(SegmentationTokenParser.format(
                new SegmentationMethod(method.engine, params, raw)), true);
    }

    private static TokenRewrite rewriteTrainedRf(SegmentationMethod method,
                                                 String raw,
                                                 String oldKey,
                                                 String newKey) {
        Map<String, String> params = new LinkedHashMap<String, String>(method.params);
        boolean changed = false;
        if (oldKey.equals(params.get("modelKey"))) {
            params.put("modelKey", newKey);
            changed = true;
        }
        String base = params.get("base");
        if (base != null && !base.trim().isEmpty()) {
            TokenRewrite nested = rewriteToken(base, oldKey, newKey);
            if (nested.changed) {
                params.put("base", nested.token);
                changed = true;
            }
        }
        if (!changed) {
            return new TokenRewrite(raw, false);
        }
        return new TokenRewrite(SegmentationTokenParser.format(
                new SegmentationMethod(method.engine, params, raw)), true);
    }

    private static TokenRewrite rewriteRawToken(String raw, String oldKey, String newKey) {
        String rewritten = raw;
        String modelSegment = "model=" + oldKey;
        if (containsColonSegment(rewritten, modelSegment)) {
            rewritten = replaceColonSegment(rewritten, modelSegment, "model=" + newKey);
        }
        String trainedPrefix = "trained_rf:" + oldKey + ":";
        if (rewritten.startsWith(trainedPrefix)) {
            rewritten = "trained_rf:" + newKey + ":"
                    + rewritten.substring(trainedPrefix.length());
        }
        return new TokenRewrite(rewritten, !rewritten.equals(raw));
    }

    private static boolean containsColonSegment(String token, String segment) {
        String[] parts = token.split(":", -1);
        for (String part : parts) {
            if (segment.equals(part)) {
                return true;
            }
        }
        return false;
    }

    private static String replaceColonSegment(String token, String oldSegment, String newSegment) {
        String[] parts = token.split(":", -1);
        for (int i = 0; i < parts.length; i++) {
            if (oldSegment.equals(parts[i])) {
                parts[i] = newSegment;
            }
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                sb.append(':');
            }
            sb.append(parts[i]);
        }
        return sb.toString();
    }

    private static void rollback(List<RewritePlan> applied) {
        for (int i = applied.size() - 1; i >= 0; i--) {
            RewritePlan plan = applied.get(i);
            try {
                Files.write(plan.path, plan.originalJson.getBytes(StandardCharsets.UTF_8));
            } catch (IOException ignored) {
            }
        }
    }

    private static int channelCount(List<RewritePlan> plans) {
        int count = 0;
        for (RewritePlan plan : plans) {
            count += plan.changedChannels;
        }
        return count;
    }

    private static void validateKey(String label, String key) throws IOException {
        if (key == null || !key.matches("[A-Za-z0-9_]+")) {
            throw new IOException(label + " must contain only letters, numbers, and underscores.");
        }
    }

    interface WriteHook {
        void beforeWrite(Path path) throws IOException;
    }

    public static final class RenameResult {
        public final int binsTouched;
        public final int channelsTouched;
        public final boolean filesRenamed;
        public final boolean cancelled;

        public RenameResult(int binsTouched,
                            int channelsTouched,
                            boolean filesRenamed,
                            boolean cancelled) {
            this.binsTouched = binsTouched;
            this.channelsTouched = channelsTouched;
            this.filesRenamed = filesRenamed;
            this.cancelled = cancelled;
        }
    }

    private static final class RewritePlan {
        final Path path;
        final String originalJson;
        final ChannelConfig updatedConfig;
        final int changedChannels;

        RewritePlan(Path path,
                    String originalJson,
                    ChannelConfig updatedConfig,
                    int changedChannels) {
            this.path = path;
            this.originalJson = originalJson;
            this.updatedConfig = updatedConfig;
            this.changedChannels = changedChannels;
        }
    }

    private static final class TokenRewrite {
        final String token;
        final boolean changed;

        TokenRewrite(String token, boolean changed) {
            this.token = token;
            this.changed = changed;
        }
    }
}
