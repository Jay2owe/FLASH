package flash.pipeline.ui.wizard;

import flash.pipeline.io.FlashProjectLayout;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
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
 * Generic atomic file IO for wizard presets.
 */
public abstract class PresetIO<T extends Preset<?>> {

    private final File projectRoot;

    protected PresetIO(File projectRoot) {
        if (projectRoot == null) {
            throw new IllegalArgumentException("projectRoot is required.");
        }
        this.projectRoot = projectRoot;
    }

    public List<T> listAll() throws IOException {
        bootstrapStockPresets();
        List<T> presets = new ArrayList<T>();
        List<String> seen = new ArrayList<String>();
        List<File> dirs = presetReadDirectories();
        for (File dir : dirs) {
            if (!dir.isDirectory()) {
                continue;
            }
            File[] files = dir.listFiles((parent, name) -> isJson(name));
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
                if (!isPresetFileInsideDirectory(file, dir)) {
                    continue;
                }
                T preset = readPreset(file, dir);
                String key = sanitizeFileToken(preset.getName());
                if (seen.contains(key)) {
                    continue;
                }
                seen.add(key);
                presets.add(preset);
            }
        }
        return presets;
    }

    public T load(String name) throws IOException {
        bootstrapStockPresets();
        File file = resolvePresetFile(name);
        if (file == null || !file.isFile()) {
            throw new FileNotFoundException("Preset not found: " + name);
        }
        return readPreset(file);
    }

    public void save(T preset) throws IOException {
        if (preset == null) {
            throw new IllegalArgumentException("preset is required.");
        }
        File dir = presetDirectory();
        ensureDirectory(dir);
        File target = new File(dir, sanitizeFileToken(preset.getName()) + ".json");
        guardPresetFile(target, dir);
        File temp = File.createTempFile(stripExtension(target.getName()) + "-", ".tmp", dir);
        boolean moved = false;
        try {
            byte[] content = JsonIO.write(preset.toJsonObject()).getBytes(StandardCharsets.UTF_8);
            Files.write(temp.toPath(), content);
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
            throw new FileNotFoundException("Preset not found: " + name);
        }
        Files.delete(file.toPath());
    }

    public void bootstrapStockPresets() throws IOException {
        File dir = presetDirectory();
        File[] existing = dir.listFiles((parent, name) -> isJson(name));
        if (existing != null && existing.length > 0) {
            return;
        }
        ensureDirectory(dir);
        for (String resourceName : stockResourceFiles()) {
            byte[] content = readResource(resourceName);
            if (content == null) {
                continue;
            }
            File target = new File(dir, new File(resourceName).getName());
            if (!target.exists()) {
                Files.write(target.toPath(), content);
            }
        }
    }

    public File presetDirectory() {
        return FlashProjectLayout.forDirectory(projectRoot.getPath()).presetWriteDir(presetDirectoryName());
    }

    protected abstract String presetDirectoryName();

    protected final File projectRootDirectory() {
        return projectRoot;
    }

    protected List<File> legacyPresetDirectories() {
        return Collections.singletonList(new File(projectRoot, presetDirectoryName()));
    }

    protected abstract T parsePreset(String json) throws IOException;

    protected List<String> stockResourceFiles() {
        return Collections.emptyList();
    }

    protected String stockResourceDirectory() {
        return "";
    }

    protected InputStream openStockResource(String resourceName) {
        String directory = stockResourceDirectory();
        String path = directory == null || directory.length() == 0
                ? "/" + resourceName
                : "/" + trimSlashes(directory) + "/" + resourceName;
        InputStream stream = getClass().getResourceAsStream(path);
        if (stream != null) {
            return stream;
        }
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        return loader == null ? null : loader.getResourceAsStream(path.substring(1));
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

    private T readPreset(File file) throws IOException {
        return readPreset(file, presetDirectoryForFile(file));
    }

    private T readPreset(File file, File dir) throws IOException {
        File safeFile = guardPresetFile(file, dir);
        byte[] content = Files.readAllBytes(safeFile.toPath());
        return parsePreset(new String(content, StandardCharsets.UTF_8));
    }

    private File resolvePresetFile(String name) throws IOException {
        String requested = sanitizeLookup(name);
        if (requested == null) {
            return null;
        }
        List<File> dirs = presetReadDirectories();
        for (File dir : dirs) {
            if (!dir.isDirectory()) {
                continue;
            }
            File[] files = dir.listFiles((parent, fileName) -> isJson(fileName));
            if (files == null) {
                continue;
            }
            Arrays.sort(files, new Comparator<File>() {
                @Override
                public int compare(File left, File right) {
                    return left.getName().compareToIgnoreCase(right.getName());
                }
            });
            for (File file : files) {
                if (!isPresetFileInsideDirectory(file, dir)) {
                    continue;
                }
                if (requested.equals(sanitizeLookup(stripExtension(file.getName())))) {
                    return file;
                }
            }
            for (File file : files) {
                if (!isPresetFileInsideDirectory(file, dir)) {
                    continue;
                }
                T preset = readPreset(file, dir);
                if (requested.equals(sanitizeLookup(preset.getName()))) {
                    return file;
                }
            }
        }
        return null;
    }

    private File guardPresetFile(File file, File directory) throws IOException {
        File dir = directory.getCanonicalFile();
        File target = file.getCanonicalFile();
        if (!isInside(dir, target)) {
            throw new IOException("Preset path escapes preset directory: " + file.getPath());
        }
        return target;
    }

    private boolean isPresetFileInsideDirectory(File file, File directory) throws IOException {
        File dir = directory.getCanonicalFile();
        File target = file.getCanonicalFile();
        return isInside(dir, target);
    }

    private List<File> presetReadDirectories() throws IOException {
        List<File> dirs = new ArrayList<File>();
        FlashProjectLayout layout = FlashProjectLayout.forDirectory(projectRoot.getPath());
        for (File dir : layout.presetReadDirs(presetDirectoryName())) {
            addUniqueDirectory(dirs, dir);
        }
        for (File legacy : legacyPresetDirectories()) {
            addUniqueDirectory(dirs, legacy);
        }
        return dirs;
    }

    private void addUniqueDirectory(List<File> dirs, File dir) throws IOException {
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

    private File presetDirectoryForFile(File file) throws IOException {
        for (File dir : presetReadDirectories()) {
            if (isPresetFileInsideDirectory(file, dir)) {
                return dir;
            }
        }
        return presetDirectory();
    }

    private static boolean isInside(File dir, File target) throws IOException {
        String dirPath = dir.getCanonicalPath();
        String targetPath = target.getCanonicalPath();
        return targetPath.equals(dirPath) || targetPath.startsWith(dirPath + File.separator);
    }

    private byte[] readResource(String resourceName) throws IOException {
        InputStream stream = openStockResource(resourceName);
        if (stream == null) {
            return null;
        }
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[2048];
            int read;
            while ((read = stream.read(buffer)) >= 0) {
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        } finally {
            try {
                stream.close();
            } catch (IOException ignored) {
            }
        }
    }

    public static String sanitizeFileToken(String raw) {
        String normalized = sanitizeLookup(raw);
        return normalized == null ? "preset" : normalized;
    }

    private static String sanitizeLookup(String raw) {
        if (raw == null) {
            return null;
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        if (normalized.length() == 0) {
            return null;
        }
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
        return normalized.length() == 0 ? null : normalized;
    }

    private static boolean isJson(String name) {
        return name != null && name.toLowerCase(Locale.ROOT).endsWith(".json");
    }

    private static String stripExtension(String filename) {
        if (filename == null) {
            return "";
        }
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? filename : filename.substring(0, dot);
    }

    private static String trimSlashes(String value) {
        String out = value == null ? "" : value;
        while (out.startsWith("/")) {
            out = out.substring(1);
        }
        while (out.endsWith("/")) {
            out = out.substring(0, out.length() - 1);
        }
        return out;
    }

    private static void ensureDirectory(File dir) throws IOException {
        if (dir.isDirectory()) {
            return;
        }
        if (!dir.mkdirs() && !dir.isDirectory()) {
            throw new IOException("Could not create directory " + dir.getAbsolutePath());
        }
    }
}
