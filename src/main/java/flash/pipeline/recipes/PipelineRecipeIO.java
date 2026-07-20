package flash.pipeline.recipes;

import flash.pipeline.intelligence.MiniJson;
import flash.pipeline.ui.wizard.JsonIO;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Reads shipped, user, and project-level pipeline recipe JSON files.
 */
public final class PipelineRecipeIO {

    private static final String RESOURCE_DIR = "pipeline_recipes";
    private static final String IDENTITY_VERSION_KEY = "identityVersion";
    private static final String CANONICAL_IDENTITY_KEY = "canonicalIdentity";
    private static final int IDENTITY_VERSION = 1;

    private PipelineRecipeIO() {
    }

    public static PipelineRecipe loadFromResources(String recipeId) throws IOException {
        String fileName = ensureJsonExtension(recipeId);
        InputStream stream = openResource(fileName);
        if (stream == null) {
            throw new FileNotFoundException("Pipeline recipe resource not found: " + fileName);
        }
        try {
            return decodeRecipe(MiniJson.parseUtf8(stream, MiniJson.DEFAULT_LIMITS,
                    "pipeline recipe resource " + fileName));
        } finally {
            try {
                stream.close();
            } catch (IOException ignored) {
            }
        }
    }

    public static PipelineRecipe loadFromFile(File file) throws IOException {
        return loadFromFile(file, MiniJson.DEFAULT_LIMITS);
    }

    /** Package-visible bounded-read seam used by persistence contract tests. */
    static PipelineRecipe loadFromFile(File file, MiniJson.Limits limits) throws IOException {
        if (file == null || !file.isFile()) {
            throw new FileNotFoundException("Pipeline recipe file not found: " + file);
        }
        try (InputStream input = Files.newInputStream(file.toPath())) {
            return decodeRecipe(MiniJson.parseUtf8(input, limits, file.getAbsolutePath()));
        }
    }

    public static File saveToUserDir(PipelineRecipe recipe) throws IOException {
        if (recipe == null) {
            throw new IllegalArgumentException("recipe is required.");
        }
        Path dir = Paths.get(System.getProperty("user.home"), ".flash", "recipes");
        return saveToDirectory(recipe, dir);
    }

    /** Package-visible seam for deterministic collision and migration tests. */
    static synchronized File saveToDirectory(PipelineRecipe recipe, Path dir) throws IOException {
        if (recipe == null) {
            throw new IllegalArgumentException("recipe is required.");
        }
        if (dir == null) {
            throw new IllegalArgumentException("directory is required.");
        }
        Files.createDirectories(dir);
        File target = collisionSafeTarget(recipe, dir).toFile();
        saveToFile(recipe, target);
        return target;
    }

    public static void saveToFile(PipelineRecipe recipe, File file) throws IOException {
        if (recipe == null) {
            throw new IllegalArgumentException("recipe is required.");
        }
        if (file == null) {
            throw new IllegalArgumentException("file is required.");
        }
        File dir = file.getParentFile();
        if (dir != null) {
            Files.createDirectories(dir.toPath());
        }
        File temp = File.createTempFile(stripExtension(file.getName()) + "-", ".tmp",
                dir == null ? new File(".") : dir);
        boolean moved = false;
        try {
            Files.write(temp.toPath(), (encodeRecipe(recipe) + "\n")
                    .getBytes(StandardCharsets.UTF_8));
            moveAtomically(temp.toPath(), file.toPath());
            moved = true;
        } finally {
            if (!moved) {
                Files.deleteIfExists(temp.toPath());
            }
        }
    }

    private static InputStream openResource(String fileName) {
        String path = "/" + RESOURCE_DIR + "/" + fileName;
        InputStream stream = PipelineRecipeIO.class.getResourceAsStream(path);
        if (stream != null) {
            return stream;
        }
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        return loader == null ? null : loader.getResourceAsStream(path.substring(1));
    }

    private static void moveAtomically(Path source, Path target) throws IOException {
        // Retry/backoff move, then in-place rewrite if the destination stays
        // locked against rename (Windows + Dropbox/OneDrive). Safe: small recipe.
        flash.pipeline.io.IoUtils.commitReplacingSmallFile(source, target);
    }

    private static String encodeRecipe(PipelineRecipe recipe) {
        Map<String, Object> root = new LinkedHashMap<String, Object>(recipe.toJsonObject());
        root.put(IDENTITY_VERSION_KEY, Integer.valueOf(IDENTITY_VERSION));
        root.put(CANONICAL_IDENTITY_KEY, canonicalIdentity(recipe.getName()));
        return JsonIO.write(root);
    }

    @SuppressWarnings("unchecked")
    private static PipelineRecipe decodeRecipe(Object parsed) throws IOException {
        if (!(parsed instanceof Map)) {
            throw new IOException("Recipe JSON root must be an object.");
        }
        Map<String, Object> root = (Map<String, Object>) parsed;
        Object rawVersion = root.get(IDENTITY_VERSION_KEY);
        if (rawVersion != null) {
            if (!(rawVersion instanceof Number)
                    || ((Number) rawVersion).doubleValue() != IDENTITY_VERSION) {
                throw new IOException("Unsupported or malformed recipe identityVersion: "
                        + String.valueOf(rawVersion));
            }
            String storedIdentity = JsonIO.stringValue(root.get(CANONICAL_IDENTITY_KEY));
            String displayName = JsonIO.stringValue(root.get("name"));
            if (displayName == null || displayName.trim().isEmpty()
                    || storedIdentity == null
                    || !storedIdentity.equals(canonicalIdentity(displayName))) {
                throw new IOException("Recipe canonical identity does not match its display name.");
            }
        } else if (root.containsKey(CANONICAL_IDENTITY_KEY)) {
            throw new IOException("Recipe canonicalIdentity requires identityVersion.");
        }
        return PipelineRecipe.fromJsonObject(root);
    }

    private static Path collisionSafeTarget(PipelineRecipe recipe, Path dir) throws IOException {
        String identity = canonicalIdentity(recipe.getName());
        String digest = identity.substring(identity.indexOf('$') + 1);
        String base = sanitizeFileToken(recipe.getName()) + "--" + digest;
        for (int ordinal = 1; ordinal < Integer.MAX_VALUE; ordinal++) {
            String suffix = ordinal == 1 ? "" : "-" + ordinal;
            String fileName = base + suffix + ".json";
            Path existing = findWindowsEquivalent(dir, fileName);
            if (existing == null) return dir.resolve(fileName);
            try {
                PipelineRecipe saved = loadFromFile(existing.toFile());
                if (recipe.getName().equals(saved.getName())) return existing;
            } catch (IOException ignored) {
                // A malformed or legacy look-alike is user data. Preserve it and select
                // the next deterministic disambiguator instead of overwriting it.
            }
        }
        throw new IOException("Could not allocate a collision-safe recipe filename.");
    }

    private static Path findWindowsEquivalent(Path dir, String fileName) throws IOException {
        String wanted = windowsFilenameKey(fileName);
        DirectoryStream<Path> entries = Files.newDirectoryStream(dir);
        try {
            for (Path entry : entries) {
                Path name = entry.getFileName();
                if (name != null && wanted.equals(windowsFilenameKey(name.toString()))) {
                    return entry;
                }
            }
        } finally {
            entries.close();
        }
        return null;
    }

    private static String windowsFilenameKey(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
    }

    private static String canonicalIdentity(String displayName) {
        String normalized = Normalizer.normalize(displayName == null ? "" : displayName.trim(),
                Normalizer.Form.NFKC);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(normalized.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                hex.append(String.format(Locale.US, "%02x", b & 0xff));
            }
            return "r1$" + hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime.", e);
        }
    }

    private static String sanitizeFileToken(String raw) {
        if (raw == null) {
            return "recipe";
        }
        String normalized = Normalizer.normalize(raw.trim(), Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT);
        StringBuilder token = new StringBuilder(Math.min(normalized.length(), 40));
        boolean pendingSeparator = false;
        int kept = 0;
        for (int offset = 0; offset < normalized.length() && kept < 40;) {
            int cp = normalized.codePointAt(offset);
            if (Character.isLetterOrDigit(cp)) {
                if (pendingSeparator && token.length() > 0) token.append('_');
                token.appendCodePoint(cp);
                pendingSeparator = false;
                kept++;
            } else {
                pendingSeparator = true;
            }
            offset += Character.charCount(cp);
        }
        return token.length() == 0 ? "recipe" : token.toString();
    }

    private static String ensureJsonExtension(String recipeId) {
        String trimmed = recipeId == null ? "" : recipeId.trim();
        if (trimmed.toLowerCase(Locale.ROOT).endsWith(".json")) {
            return trimmed;
        }
        return trimmed + ".json";
    }

    private static String stripExtension(String filename) {
        if (filename == null) {
            return "recipe";
        }
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? filename : filename.substring(0, dot);
    }
}
