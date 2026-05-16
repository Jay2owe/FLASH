package flash.pipeline.segmentation.catalog;

import flash.pipeline.bin.BinConfigIO;
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
                BinConfigIO.writeAtomic(plan.path, plan.updatedLines);
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
        List<Path> channelDataFiles = findChannelDataFiles(projectRoot);
        List<RewritePlan> plans = new ArrayList<RewritePlan>();
        for (Path file : channelDataFiles) {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            if (lines.size() < 7) {
                continue;
            }
            String[] tokens = splitTokens(lines.get(6));
            List<String> rewritten = new ArrayList<String>();
            int changed = 0;
            for (String token : tokens) {
                TokenRewrite rewrite = rewriteToken(token, oldKey, newKey);
                rewritten.add(rewrite.token);
                if (rewrite.changed) {
                    changed++;
                }
            }
            if (changed > 0) {
                List<String> updatedLines = new ArrayList<String>(lines);
                updatedLines.set(6, joinTokens(rewritten));
                plans.add(new RewritePlan(file, lines, updatedLines, changed));
            }
        }
        return plans;
    }

    private static List<Path> findChannelDataFiles(Path projectRoot) throws IOException {
        List<Path> out = new ArrayList<Path>();
        if (!Files.exists(projectRoot)) {
            return out;
        }
        try (Stream<Path> stream = Files.walk(projectRoot)) {
            stream.forEach(path -> {
                if (Files.isRegularFile(path)
                        && FlashProjectLayout.CHANNEL_DATA_FILENAME.equals(path.getFileName().toString())
                        && looksLikeBinPath(path)) {
                    out.add(path.toAbsolutePath().normalize());
                }
            });
        }
        Collections.sort(out);
        return out;
    }

    private static boolean looksLikeBinPath(Path path) {
        String normalized = path.toString().replace('\\', '/');
        return normalized.contains("/Configuration/")
                || normalized.contains("/" + FlashProjectLayout.LEGACY_BIN_DIR + "/")
                || normalized.contains("/" + FlashProjectLayout.LEGACY_CONFIGURATION_DIR + "/");
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
                BinConfigIO.writeAtomic(plan.path, plan.originalLines);
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

    private static String[] splitTokens(String line) {
        if (line == null) {
            return new String[0];
        }
        if (line.indexOf('\t') >= 0) {
            String[] tabs = line.split("\t", -1);
            for (int i = 0; i < tabs.length; i++) {
                tabs[i] = tabs[i] == null ? "" : tabs[i].trim();
            }
            return tabs;
        }
        String trimmed = line.trim();
        return trimmed.isEmpty() ? new String[0] : trimmed.split("\\s+");
    }

    private static String joinTokens(List<String> tokens) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < tokens.size(); i++) {
            if (i > 0) {
                sb.append('\t');
            }
            sb.append(tokens.get(i) == null ? "" : tokens.get(i));
        }
        return sb.toString();
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
        final List<String> originalLines;
        final List<String> updatedLines;
        final int changedChannels;

        RewritePlan(Path path,
                    List<String> originalLines,
                    List<String> updatedLines,
                    int changedChannels) {
            this.path = path;
            this.originalLines = originalLines;
            this.updatedLines = updatedLines;
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
