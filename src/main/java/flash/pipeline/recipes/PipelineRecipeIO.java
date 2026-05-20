package flash.pipeline.recipes;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Locale;

/**
 * Reads shipped, user, and project-level pipeline recipe JSON files.
 */
public final class PipelineRecipeIO {

    private static final String RESOURCE_DIR = "pipeline_recipes";

    private PipelineRecipeIO() {
    }

    public static PipelineRecipe loadFromResources(String recipeId) throws IOException {
        String fileName = ensureJsonExtension(recipeId);
        InputStream stream = openResource(fileName);
        if (stream == null) {
            throw new FileNotFoundException("Pipeline recipe resource not found: " + fileName);
        }
        try {
            return PipelineRecipe.fromJson(new String(readFully(stream), StandardCharsets.UTF_8));
        } finally {
            try {
                stream.close();
            } catch (IOException ignored) {
            }
        }
    }

    public static PipelineRecipe loadFromFile(File file) throws IOException {
        if (file == null || !file.isFile()) {
            throw new FileNotFoundException("Pipeline recipe file not found: " + file);
        }
        byte[] content = Files.readAllBytes(file.toPath());
        return PipelineRecipe.fromJson(new String(content, StandardCharsets.UTF_8));
    }

    public static File saveToUserDir(PipelineRecipe recipe) throws IOException {
        if (recipe == null) {
            throw new IllegalArgumentException("recipe is required.");
        }
        Path dir = Paths.get(System.getProperty("user.home"), ".flash", "recipes");
        Files.createDirectories(dir);
        File target = dir.resolve(sanitizeFileToken(recipe.getName()) + ".json").toFile();
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
            Files.write(temp.toPath(), (recipe.toJson() + "\n").getBytes(StandardCharsets.UTF_8));
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

    private static byte[] readFully(InputStream stream) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[2048];
        int read;
        while ((read = stream.read(buffer)) >= 0) {
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    private static void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String sanitizeFileToken(String raw) {
        if (raw == null) {
            return "recipe";
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT)
                .replace('\u2014', ' ')
                .replace('\u2013', ' ')
                .replace('&', ' ')
                .replace('/', ' ')
                .replace('\\', ' ')
                .replace(',', ' ')
                .replace(':', ' ')
                .replace(';', ' ')
                .replace('-', ' ')
                .replaceAll("[^a-z0-9]+", "_")
                .replaceAll("^_+", "")
                .replaceAll("_+$", "")
                .replaceAll("_+", "_");
        return normalized.length() == 0 ? "recipe" : normalized;
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
