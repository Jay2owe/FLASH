package flash.pipeline.deconv.wizard;

import flash.pipeline.io.FlashProjectLayout;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Reads and writes persisted deconvolution presets from the project root.
 */
public class DeconvPresetIO {

    static final String PRESET_DIR_NAME = "Deconvolution Presets";

    private static final List<String> STOCK_RESOURCE_FILES = Collections.unmodifiableList(
            Arrays.asList(
                    "default.json",
                    "confocal_puncta.json",
                    "widefield_morphology.json",
                    "cleared_tissue.json"
            )
    );

    private final File projectRoot;

    public DeconvPresetIO() {
        this(defaultProjectRoot());
    }

    public DeconvPresetIO(File projectRoot) {
        if (projectRoot == null) {
            throw new IllegalArgumentException("projectRoot is required.");
        }
        this.projectRoot = projectRoot;
    }

    public List<DeconvPreset> listAll() throws IOException {
        ensureStockPresetsIfEmpty();
        List<DeconvPreset> presets = new ArrayList<DeconvPreset>();
        List<String> seen = new ArrayList<String>();
        for (File dir : presetReadDirectories()) {
            if (!dir.isDirectory()) {
                continue;
            }

            File[] files = dir.listFiles((parent, name) -> name != null && name.toLowerCase(Locale.ROOT).endsWith(".json"));
            if (files == null || files.length == 0) {
                continue;
            }
            Arrays.sort(files, new Comparator<File>() {
                @Override
                public int compare(File left, File right) {
                    return left.getName().compareToIgnoreCase(right.getName());
                }
            });

            for (File file : files) {
                DeconvPreset preset = readPreset(file);
                String key = sanitizeLookup(preset.getName());
                if (seen.contains(key)) {
                    continue;
                }
                seen.add(key);
                presets.add(preset);
            }
        }
        return presets;
    }

    public DeconvPreset load(String name) throws IOException {
        ensureStockPresetsIfEmpty();
        File file = resolvePresetFile(name);
        if (file == null || !file.isFile()) {
            throw new FileNotFoundException("Deconvolution preset not found: " + name);
        }
        return readPreset(file);
    }

    public void save(DeconvPreset preset) throws IOException {
        if (preset == null) {
            throw new IllegalArgumentException("preset is required.");
        }

        File dir = presetDirectory();
        ensureDirectory(dir);
        File target = new File(dir, userPresetFilename(preset));
        File temp = File.createTempFile(stripExtension(target.getName()) + "-", ".tmp", dir);
        boolean moved = false;
        try {
            Files.write(temp.toPath(), preset.toJson().getBytes(StandardCharsets.UTF_8));
            beforeAtomicReplace(temp, target);
            moveAtomically(temp, target);
            moved = true;
        } finally {
            if (!moved) {
                Files.deleteIfExists(temp.toPath());
            }
        }
    }

    public void delete(String name) throws IOException {
        File file = resolvePresetFile(name);
        if (file == null || !file.isFile()) {
            throw new FileNotFoundException("Deconvolution preset not found: " + name);
        }
        Files.delete(file.toPath());
    }

    public File presetDirectory() {
        return FlashProjectLayout.forDirectory(projectRoot.getPath()).presetWriteDir(PRESET_DIR_NAME);
    }

    protected void beforeAtomicReplace(File temp, File target) throws IOException {
        // Test hook.
    }

    protected void moveAtomically(File source, File target) throws IOException {
        try {
            Files.move(source.toPath(), target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    protected List<String> stockResourceFiles() {
        return STOCK_RESOURCE_FILES;
    }

    protected InputStream openStockResource(String resourceName) {
        String path = "/deconv_presets/" + resourceName;
        InputStream stream = DeconvPresetIO.class.getResourceAsStream(path);
        if (stream != null) return stream;
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        return loader == null ? null : loader.getResourceAsStream(path.substring(1));
    }

    private void ensureStockPresetsIfEmpty() throws IOException {
        File dir = presetDirectory();
        File[] jsonFiles = dir.listFiles((parent, name) -> name != null && name.toLowerCase(Locale.ROOT).endsWith(".json"));
        if (jsonFiles != null && jsonFiles.length > 0) {
            return;
        }

        ensureDirectory(dir);
        boolean copiedAny = false;
        for (String resourceName : stockResourceFiles()) {
            byte[] content = readResource(resourceName);
            if (content == null) continue;
            File target = new File(dir, resourceName);
            if (!target.exists()) {
                Files.write(target.toPath(), content);
                copiedAny = true;
            }
        }
        if (!copiedAny) {
            File[] after = dir.listFiles((parent, name) -> name != null && name.toLowerCase(Locale.ROOT).endsWith(".json"));
            if (after == null || after.length == 0) {
                return;
            }
        }
    }

    private byte[] readResource(String resourceName) throws IOException {
        InputStream stream = openStockResource(resourceName);
        if (stream == null) return null;
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            int read;
            while ((read = stream.read(buffer)) >= 0) {
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        } finally {
            try {
                stream.close();
            } catch (IOException ignored) {}
        }
    }

    private File resolvePresetFile(String name) throws IOException {
        String requested = sanitizeLookup(name);
        if (requested == null) return null;

        for (File dir : presetReadDirectories()) {
            if (!dir.isDirectory()) {
                continue;
            }

            File[] files = dir.listFiles((parent, fileName) -> fileName != null && fileName.toLowerCase(Locale.ROOT).endsWith(".json"));
            if (files == null) continue;

            for (File file : files) {
                String base = stripExtension(file.getName());
                if (sanitizeLookup(base).equals(requested)) {
                    return file;
                }
            }

            for (File file : files) {
                DeconvPreset preset = readPreset(file);
                if (sanitizeLookup(preset.getName()).equals(requested)) {
                    return file;
                }
            }
        }
        return null;
    }

    private List<File> presetReadDirectories() throws IOException {
        List<File> dirs = new ArrayList<File>();
        FlashProjectLayout layout = FlashProjectLayout.forDirectory(projectRoot.getPath());
        addUniqueDirectory(dirs, presetDirectory());
        for (File dir : layout.presetReadDirs(PRESET_DIR_NAME)) {
            addUniqueDirectory(dirs, dir);
        }
        addUniqueDirectory(dirs, new File(projectRoot, PRESET_DIR_NAME));
        return dirs;
    }

    private static void addUniqueDirectory(List<File> dirs, File dir) throws IOException {
        if (dir == null) {
            return;
        }
        File canonical = dir.getCanonicalFile();
        for (File existing : dirs) {
            if (existing.getCanonicalFile().equals(canonical)) {
                return;
            }
        }
        dirs.add(dir);
    }

    private DeconvPreset readPreset(File file) throws IOException {
        byte[] content = Files.readAllBytes(file.toPath());
        return DeconvPreset.fromJson(new String(content, StandardCharsets.UTF_8));
    }

    private static String userPresetFilename(DeconvPreset preset) {
        String token = sanitizeFileToken(preset.getName());
        if (token == null || token.isEmpty()) {
            token = "preset";
        }
        return token + ".json";
    }

    static String sanitizeFileToken(String raw) {
        String normalized = sanitizeLookup(raw);
        if (normalized == null) return null;
        return normalized;
    }

    private static String sanitizeLookup(String raw) {
        if (raw == null) return null;
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) return null;
        normalized = normalized
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
        return normalized;
    }

    private static void ensureDirectory(File dir) throws IOException {
        if (dir == null) return;
        if (dir.isDirectory()) return;
        if (!dir.mkdirs() && !dir.isDirectory()) {
            throw new IOException("Could not create directory " + dir.getAbsolutePath());
        }
    }

    private static String stripExtension(String filename) {
        if (filename == null) return "";
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? filename : filename.substring(0, dot);
    }

    private static File defaultProjectRoot() {
        String userDir = System.getProperty("user.dir");
        if (userDir == null || userDir.trim().isEmpty()) {
            return new File(".");
        }
        return new File(userDir);
    }
}
