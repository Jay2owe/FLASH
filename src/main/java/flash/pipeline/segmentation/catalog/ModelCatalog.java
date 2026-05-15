package flash.pipeline.segmentation.catalog;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Project-scoped in-memory catalog of stock and user segmentation models.
 */
public final class ModelCatalog {
    private static final Map<String, Path> STOCK_RESOURCE_CACHE =
            new HashMap<String, Path>();

    private final Path projectRoot;
    private final Path catalogDir;
    private final LinkedHashMap<String, ModelEntry> entries;

    public ModelCatalog(Path projectRoot) {
        this(projectRoot, Collections.<ModelEntry>emptyList());
    }

    public ModelCatalog(Path projectRoot, List<ModelEntry> entries) {
        if (projectRoot == null) {
            throw new IllegalArgumentException("Project root must not be null.");
        }
        this.projectRoot = projectRoot;
        this.catalogDir = ModelCatalogIO.catalogDirectory(projectRoot);
        this.entries = new LinkedHashMap<String, ModelEntry>();
        if (entries != null) {
            for (ModelEntry entry : entries) {
                if (entry != null) {
                    this.entries.put(entry.modelKey, entry);
                }
            }
        }
    }

    public Path projectRoot() {
        return projectRoot;
    }

    public Path catalogDirectory() {
        return catalogDir;
    }

    public Path filesDirectory() {
        return catalogDir.resolve(ModelCatalogIO.FILES_DIR);
    }

    public synchronized List<ModelEntry> all() {
        return Collections.unmodifiableList(new ArrayList<ModelEntry>(entries.values()));
    }

    public synchronized List<ModelEntry> forEngine(ModelEntry.Engine engine) {
        List<ModelEntry> out = new ArrayList<ModelEntry>();
        for (ModelEntry entry : entries.values()) {
            if (entry.engine == engine) {
                out.add(entry);
            }
        }
        return Collections.unmodifiableList(out);
    }

    public synchronized Optional<ModelEntry> get(String modelKey) {
        return Optional.ofNullable(entries.get(modelKey));
    }

    public synchronized boolean isStockEntry(String modelKey) {
        ModelEntry entry = entries.get(modelKey);
        return entry != null && entry.isStock();
    }

    public synchronized ModelEntry add(ModelEntry entry, Path sourceFile) throws IOException {
        if (entry == null) {
            throw new IOException("Cannot add a null model entry.");
        }
        ModelCatalogIO.validateEntry(entry, false);
        if (sourceFile == null || !Files.isRegularFile(sourceFile)) {
            throw new IOException("Model file does not exist: " + sourceFile);
        }

        String fileName = sourceFile.getFileName().toString();
        requireSafeFileName(fileName);

        Path filesRoot = filesDirectory().toAbsolutePath().normalize();
        Path targetDir = filesRoot.resolve(entry.modelKey).normalize();
        if (!targetDir.startsWith(filesRoot)) {
            throw new IOException("Model key resolves outside catalog files directory: " + entry.modelKey);
        }
        Files.createDirectories(targetDir);

        Path target = targetDir.resolve(fileName).normalize();
        if (!target.startsWith(targetDir)) {
            throw new IOException("Model file resolves outside catalog model directory: " + fileName);
        }
        Files.copy(sourceFile, target, StandardCopyOption.REPLACE_EXISTING);

        String relativePath = catalogDir.toAbsolutePath().normalize()
                .relativize(target.toAbsolutePath().normalize())
                .toString()
                .replace(File.separatorChar, '/');
        ModelEntry saved = entry.withFilePath(relativePath);
        ModelCatalogIO.validateEntry(saved, true);
        entries.put(saved.modelKey, saved);
        return saved;
    }

    public synchronized void remove(String modelKey) throws IOException {
        entries.remove(modelKey);
        if (!ModelCatalogIO.isSafeModelKey(modelKey)) {
            return;
        }
        Path filesRoot = filesDirectory().toAbsolutePath().normalize();
        Path modelDir = filesRoot.resolve(modelKey).normalize();
        if (modelDir.startsWith(filesRoot)) {
            deleteRecursively(modelDir);
        }
    }

    public Path resolve(ModelEntry entry) throws IOException {
        if (entry == null || entry.source == null) {
            return null;
        }
        if (entry.source == ModelEntry.Source.STOCK_BUILTIN) {
            return null;
        }
        if (entry.source == ModelEntry.Source.STOCK_RESOURCE) {
            return resolveStockResource(entry);
        }
        return resolveUserPath(entry);
    }

    private Path resolveUserPath(ModelEntry entry) {
        if (!entry.filePath.isPresent()) {
            return null;
        }
        String normalized = entry.filePath.get().replace('\\', '/');
        Path raw = Paths.get(normalized);
        if (raw.isAbsolute()) {
            return null;
        }
        Path base = normalized.equals(ModelCatalogIO.FILES_DIR)
                || normalized.startsWith(ModelCatalogIO.FILES_DIR + "/")
                ? catalogDir
                : filesDirectory();
        return base.resolve(raw).toAbsolutePath().normalize();
    }

    private Path resolveStockResource(ModelEntry entry) throws IOException {
        if (!entry.resourcePath.isPresent()) {
            return null;
        }
        String resourcePath = entry.resourcePath.get();
        String cacheKey = cacheKey(resourcePath);
        synchronized (STOCK_RESOURCE_CACHE) {
            Path cached = STOCK_RESOURCE_CACHE.get(cacheKey);
            if (cached != null && Files.isRegularFile(cached)) {
                return cached;
            }
        }
        URL resource = findResource(resourcePath);
        if (resource == null) {
            return null;
        }

        if ("file".equalsIgnoreCase(resource.getProtocol())) {
            try {
                Path path = Paths.get(resource.toURI());
                if (Files.isRegularFile(path)) {
                    return rememberStockResource(cacheKey, path.toAbsolutePath().normalize());
                }
            } catch (URISyntaxException e) {
                Path path = Paths.get(resource.getPath());
                if (Files.isRegularFile(path)) {
                    return rememberStockResource(cacheKey, path.toAbsolutePath().normalize());
                }
            }
        }

        String fileName = resourceFileName(resourcePath, entry.modelKey);
        Path cacheRoot = catalogDir.resolve(".cache").resolve("stock").toAbsolutePath().normalize();
        Path modelCache = cacheRoot.resolve(entry.modelKey).normalize();
        if (!modelCache.startsWith(cacheRoot)) {
            throw new IOException("Stock model key resolves outside runtime cache: " + entry.modelKey);
        }
        Files.createDirectories(modelCache);
        Path target = modelCache.resolve(fileName).normalize();
        if (!target.startsWith(modelCache)) {
            throw new IOException("Stock resource resolves outside runtime cache: " + resourcePath);
        }
        synchronized (STOCK_RESOURCE_CACHE) {
            Path cached = STOCK_RESOURCE_CACHE.get(cacheKey);
            if (cached != null && Files.isRegularFile(cached)) {
                return cached;
            }
            if (!Files.isRegularFile(target)) {
                try (InputStream in = resource.openStream()) {
                    Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
            return rememberStockResource(cacheKey, target.toAbsolutePath().normalize());
        }
    }

    static void clearStockResourceCacheForTests() {
        synchronized (STOCK_RESOURCE_CACHE) {
            STOCK_RESOURCE_CACHE.clear();
        }
    }

    private String cacheKey(String resourcePath) {
        return catalogDir.toAbsolutePath().normalize().toString() + "|" + resourcePath;
    }

    private static Path rememberStockResource(String cacheKey, Path path) {
        synchronized (STOCK_RESOURCE_CACHE) {
            STOCK_RESOURCE_CACHE.put(cacheKey, path);
        }
        return path;
    }

    private static URL findResource(String resourcePath) {
        String path = resourcePath == null ? "" : resourcePath.trim();
        while (path.startsWith("/")) {
            path = path.substring(1);
        }
        ClassLoader context = Thread.currentThread().getContextClassLoader();
        if (context != null) {
            URL resource = context.getResource(path);
            if (resource != null) return resource;
        }
        ClassLoader own = ModelCatalog.class.getClassLoader();
        if (own != null) {
            URL resource = own.getResource(path);
            if (resource != null) return resource;
        }
        return ClassLoader.getSystemResource(path);
    }

    private static String resourceFileName(String resourcePath, String modelKey) {
        String normalized = resourcePath.replace('\\', '/');
        int slash = normalized.lastIndexOf('/');
        String fileName = slash >= 0 ? normalized.substring(slash + 1) : normalized;
        if (fileName.trim().isEmpty()) {
            return modelKey + ".model";
        }
        return fileName;
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (root == null || !Files.exists(root)) {
            return;
        }
        List<Path> paths = new ArrayList<Path>();
        try (Stream<Path> stream = Files.walk(root)) {
            stream.forEach(paths::add);
        }
        Collections.sort(paths, new Comparator<Path>() {
            @Override
            public int compare(Path left, Path right) {
                int byDepth = Integer.compare(right.getNameCount(), left.getNameCount());
                return byDepth != 0 ? byDepth : right.compareTo(left);
            }
        });
        for (Path path : paths) {
            Files.deleteIfExists(path);
        }
    }

    private static void requireSafeFileName(String fileName) throws IOException {
        if (fileName == null || fileName.trim().isEmpty()
                || fileName.indexOf('/') >= 0 || fileName.indexOf('\\') >= 0
                || ".".equals(fileName) || "..".equals(fileName)) {
            throw new IOException("Unsafe model file name: " + fileName);
        }
    }
}
