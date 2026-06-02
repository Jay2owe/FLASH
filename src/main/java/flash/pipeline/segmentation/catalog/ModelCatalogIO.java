package flash.pipeline.segmentation.catalog;

import flash.pipeline.bin.BinConfigIO;
import flash.pipeline.cellpose.CellposeRegisteredModels;
import flash.pipeline.io.FlashProjectLayout;
import flash.pipeline.ui.wizard.JsonIO;
import ij.IJ;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Reads and writes the project-scoped segmentation model catalog.
 */
public final class ModelCatalogIO {
    private static final Logger LOGGER = Logger.getLogger(ModelCatalogIO.class.getName());
    public static final int CATALOG_VERSION = 1;
    static final String CONFIGURATION_DIR = FlashProjectLayout.CONFIGURATION_DIR;
    static final String CATALOG_DIR = FlashProjectLayout.SEGMENTATION_MODELS_DIR;
    static final String LEGACY_CONFIGURATION_DIR = "Configuration";
    static final String LEGACY_CATALOG_DIR = "Segmentation Models";
    static final String FILES_DIR = "files";
    public static final String CATALOG_FILENAME = "catalog.json";
    static final String STOCK_CATALOG_RESOURCE = "segmentation_models/stock_catalog.json";
    public static final String PROJECT_REGISTERED_METADATA_KEY = "projectRegistered";
    public static final String DISCOVERED_FROM_METADATA_KEY = "discovered_from";
    public static final String CELLPOSE_DISCOVERY_SOURCE = "cellpose";

    public interface WarningSink {
        void warn(String message);
    }

    interface DiscoveryProvider {
        List<ModelEntry> fetch(List<String> existingModelKeys);
    }

    private static volatile WarningSink warningSink = defaultWarningSink();
    private static volatile DiscoveryProvider discoveryProvider = new DiscoveryProvider() {
        @Override
        public List<ModelEntry> fetch(List<String> existingModelKeys) {
            return CellposeRegisteredModels.fetch(existingModelKeys);
        }
    };

    private ModelCatalogIO() {
    }

    public static ModelCatalog read(Path projectRoot) {
        if (projectRoot == null) {
            throw new IllegalArgumentException("Project root must not be null.");
        }
        LinkedHashMap<String, ModelEntry> merged = new LinkedHashMap<String, ModelEntry>();
        for (ModelEntry stock : readStockResources()) {
            putMerged(merged, stock, false);
        }
        ProjectEntries projectEntries = readProjectEntries(projectRoot);
        for (ModelEntry project : projectEntries.entries) {
            putMerged(merged, project, true);
        }
        mergeDiscoveredEntries(merged);
        return new ModelCatalog(projectRoot, new ArrayList<ModelEntry>(merged.values()),
                projectEntries.catalogDir);
    }

    public static void writeProject(Path projectRoot, ModelCatalog catalog) throws IOException {
        if (projectRoot == null) {
            throw new IOException("Project root must not be null.");
        }
        if (catalog == null) {
            throw new IOException("Model catalog must not be null.");
        }

        List<ModelEntry> projectEntries = new ArrayList<ModelEntry>();
        for (ModelEntry entry : catalog.all()) {
            if (isProjectWritableEntry(entry)) {
                validateEntry(entry, true);
                projectEntries.add(entry);
            }
        }

        Map<String, Object> root = new LinkedHashMap<String, Object>();
        root.put("version", Integer.valueOf(CATALOG_VERSION));
        List<Object> models = new ArrayList<Object>();
        for (ModelEntry entry : projectEntries) {
            models.add(entry.toJsonObject());
        }
        root.put("models", models);

        String json = JsonIO.write(root);
        BinConfigIO.writeAtomic(catalogFile(projectRoot), Collections.singletonList(json));
    }

    public static List<ModelEntry> readStockResources() {
        InputStream in = openClasspathResource(STOCK_CATALOG_RESOURCE);
        if (in == null) {
            warn("Segmentation stock catalog resource not found: " + STOCK_CATALOG_RESOURCE);
            return Collections.emptyList();
        }
        try {
            String json = readUtf8(in);
            return parseCatalog(json, "stock catalog", true);
        } catch (IOException e) {
            warn("Could not read segmentation stock catalog: " + e.getMessage());
            return Collections.emptyList();
        }
    }

    public static List<ModelEntry> readProjectCatalogFile(Path file) throws IOException {
        if (file == null || !Files.isRegularFile(file)) {
            throw new IOException("Catalog file does not exist: " + file);
        }
        String json = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        return parseCatalog(json, file.toString(), false);
    }

    public static Path catalogDirectory(Path projectRoot) {
        return FlashProjectLayout.forDirectory(projectRoot.toString())
                .segmentationModelsRoot()
                .toPath();
    }

    static Path legacyCatalogDirectory(Path projectRoot) {
        return projectRoot.resolve(LEGACY_CONFIGURATION_DIR).resolve(LEGACY_CATALOG_DIR);
    }

    static Path catalogFile(Path projectRoot) {
        return catalogDirectory(projectRoot).resolve(CATALOG_FILENAME);
    }

    static void setWarningSinkForTests(WarningSink sink) {
        warningSink = sink == null ? defaultWarningSink() : sink;
    }

    static void setDiscoveryProviderForTests(DiscoveryProvider provider) {
        discoveryProvider = provider == null ? new DiscoveryProvider() {
            @Override
            public List<ModelEntry> fetch(List<String> existingModelKeys) {
                return CellposeRegisteredModels.fetch(existingModelKeys);
            }
        } : provider;
    }

    static void validateEntry(ModelEntry entry, boolean requireFileForUserEntries) throws IOException {
        if (entry == null) {
            throw new IOException("Model entry is null.");
        }
        if (!isSafeModelKey(entry.modelKey)) {
            throw new IOException("Model entry has an invalid modelKey: " + entry.modelKey);
        }
        if (entry.name == null || entry.name.trim().isEmpty()) {
            throw new IOException("Model entry " + entry.modelKey + " is missing name.");
        }
        if (entry.engine == null) {
            throw new IOException("Model entry " + entry.modelKey + " is missing engine.");
        }
        if (entry.source == null) {
            throw new IOException("Model entry " + entry.modelKey + " is missing source.");
        }
        if (entry.source == ModelEntry.Source.STOCK_RESOURCE) {
            requireSafeRelativePath(entry.resourcePath.isPresent() ? entry.resourcePath.get() : null,
                    "resourcePath", entry.modelKey);
        } else if (entry.source == ModelEntry.Source.STOCK_BUILTIN) {
            if (!entry.pretrainedModel.isPresent()) {
                throw new IOException("Stock builtin model " + entry.modelKey + " is missing pretrainedModel.");
            }
        } else if (requireFileForUserEntries) {
            requireSafeRelativePath(entry.filePath.isPresent() ? entry.filePath.get() : null,
                    "filePath", entry.modelKey);
        }
    }

    static boolean isSafeModelKey(String modelKey) {
        return modelKey != null && modelKey.matches("[A-Za-z0-9._-]+");
    }

    public static boolean isProjectWritableEntry(ModelEntry entry) {
        return entry != null
                && !isDiscoveredEntry(entry)
                && (!entry.isStock() || isProjectRegisteredBuiltin(entry));
    }

    public static boolean isProjectRegisteredBuiltin(ModelEntry entry) {
        if (entry == null || entry.source != ModelEntry.Source.STOCK_BUILTIN) {
            return false;
        }
        Object marker = entry.metadata.get(PROJECT_REGISTERED_METADATA_KEY);
        return Boolean.TRUE.equals(marker) || "true".equalsIgnoreCase(String.valueOf(marker));
    }

    public static boolean isDiscoveredEntry(ModelEntry entry) {
        if (entry == null) {
            return false;
        }
        Object marker = entry.metadata.get(DISCOVERED_FROM_METADATA_KEY);
        return marker != null && !String.valueOf(marker).trim().isEmpty();
    }

    private static ProjectEntries readProjectEntries(Path projectRoot) {
        Path canonicalDir = catalogDirectory(projectRoot);
        migrateLegacyCatalogIfNeeded(projectRoot);
        Path file = canonicalDir.resolve(CATALOG_FILENAME);
        if (!Files.isRegularFile(file)) {
            Path legacyDir = legacyCatalogDirectory(projectRoot);
            Path legacyFile = legacyDir.resolve(CATALOG_FILENAME);
            if (Files.isRegularFile(legacyFile)) {
                return new ProjectEntries(legacyDir, readProjectEntriesFile(legacyFile));
            }
        }
        return new ProjectEntries(canonicalDir, readProjectEntriesFile(file));
    }

    private static List<ModelEntry> readProjectEntriesFile(Path file) {
        if (!Files.isRegularFile(file)) {
            return Collections.emptyList();
        }
        try {
            String json = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
            return parseCatalog(json, file.toString(), false);
        } catch (IOException e) {
            warn("Could not read segmentation project catalog " + file + ": " + e.getMessage());
            return Collections.emptyList();
        }
    }

    private static void migrateLegacyCatalogIfNeeded(Path projectRoot) {
        Path targetDir = catalogDirectory(projectRoot);
        Path targetFile = targetDir.resolve(CATALOG_FILENAME);
        if (Files.isRegularFile(targetFile)) {
            return;
        }
        Path legacyDir = legacyCatalogDirectory(projectRoot);
        Path legacyFile = legacyDir.resolve(CATALOG_FILENAME);
        if (!Files.isRegularFile(legacyFile)) {
            return;
        }
        try {
            copyDirectory(legacyDir, targetDir);
        } catch (IOException e) {
            warn("Could not migrate legacy segmentation model catalog from "
                    + legacyDir + " to " + targetDir + ": " + e.getMessage());
        }
    }

    private static void copyDirectory(Path sourceDir, Path targetDir) throws IOException {
        final Path sourceRoot = sourceDir.toAbsolutePath().normalize();
        final Path targetRoot = targetDir.toAbsolutePath().normalize();
        Files.walkFileTree(sourceRoot, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
                    throws IOException {
                Path target = targetRoot.resolve(sourceRoot.relativize(dir)).normalize();
                if (!target.startsWith(targetRoot)) {
                    throw new IOException("Legacy catalog directory escapes target: " + dir);
                }
                Files.createDirectories(target);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                    throws IOException {
                Path target = targetRoot.resolve(sourceRoot.relativize(file)).normalize();
                if (!target.startsWith(targetRoot)) {
                    throw new IOException("Legacy catalog file escapes target: " + file);
                }
                Path parent = target.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static final class ProjectEntries {
        final Path catalogDir;
        final List<ModelEntry> entries;

        ProjectEntries(Path catalogDir, List<ModelEntry> entries) {
            this.catalogDir = catalogDir;
            this.entries = entries == null ? Collections.<ModelEntry>emptyList() : entries;
        }
    }

    private static void putMerged(Map<String, ModelEntry> merged, ModelEntry entry, boolean projectEntry) {
        if (entry == null) {
            return;
        }
        if (projectEntry && merged.containsKey(entry.modelKey)) {
            warn("Project segmentation model '" + entry.modelKey
                    + "' overrides a stock model with the same key.");
        }
        merged.put(entry.modelKey, entry);
    }

    private static void mergeDiscoveredEntries(LinkedHashMap<String, ModelEntry> merged) {
        DiscoveryProvider provider = discoveryProvider;
        if (provider == null) {
            return;
        }
        try {
            List<ModelEntry> discovered = provider.fetch(
                    new ArrayList<String>(merged.keySet()));
            if (discovered == null || discovered.isEmpty()) {
                return;
            }
            for (ModelEntry entry : discovered) {
                if (entry == null || merged.containsKey(entry.modelKey)) {
                    continue;
                }
                merged.put(entry.modelKey, markDiscovered(entry));
            }
        } catch (Throwable t) {
            warn("Cellpose registered model discovery skipped: " + t.getMessage());
        }
    }

    private static ModelEntry markDiscovered(ModelEntry entry) {
        Object marker = entry.metadata.get(DISCOVERED_FROM_METADATA_KEY);
        if (marker != null && !String.valueOf(marker).trim().isEmpty()) {
            return entry;
        }
        Map<String, Object> metadata = copyObjectMap(entry.metadata);
        metadata.put(DISCOVERED_FROM_METADATA_KEY, CELLPOSE_DISCOVERY_SOURCE);
        return new ModelEntry(entry.modelKey, entry.name, entry.description,
                entry.engine, entry.source,
                entry.filePath.isPresent() ? entry.filePath.get() : null,
                entry.resourcePath.isPresent() ? entry.resourcePath.get() : null,
                entry.pretrainedModel.isPresent() ? entry.pretrainedModel.get() : null,
                entry.fijiModelChoice.isPresent() ? entry.fijiModelChoice.get() : null,
                entry.base.isPresent() ? entry.base.get() : null,
                entry.tags, entry.defaults, metadata, entry.supportsSecondChannel);
    }

    private static List<ModelEntry> parseCatalog(String json, String sourceName, boolean stockCatalog)
            throws IOException {
        Map<String, Object> root = JsonIO.parseObject(json);
        int version = JsonIO.intValue(root.get("version"), CATALOG_VERSION);
        if (version != CATALOG_VERSION) {
            throw new IOException("Unsupported model catalog version " + version + ".");
        }

        List<ModelEntry> out = new ArrayList<ModelEntry>();
        List<Object> models = JsonIO.asList(root.get("models"));
        for (int i = 0; i < models.size(); i++) {
            Object raw = models.get(i);
            if (!(raw instanceof Map)) {
                warn("Skipping malformed model entry " + i + " in " + sourceName + ".");
                continue;
            }
            try {
                ModelEntry entry = parseEntry(JsonIO.asObject(raw));
                validateEntry(entry, true);
                if (!entryBelongsInCatalogLayer(entry, stockCatalog)) {
                    warn("Skipping model '" + entry.modelKey + "' in " + sourceName
                            + " because its source does not match the catalog layer.");
                    continue;
                }
                out.add(entry);
            } catch (IOException e) {
                warn("Skipping malformed model entry " + i + " in " + sourceName + ": "
                        + e.getMessage());
            }
        }
        return out;
    }

    private static boolean entryBelongsInCatalogLayer(ModelEntry entry, boolean stockCatalog) {
        if (stockCatalog) {
            return entry.isStock() && !isProjectRegisteredBuiltin(entry);
        }
        return isProjectWritableEntry(entry);
    }

    private static ModelEntry parseEntry(Map<String, Object> map) throws IOException {
        ModelEntry.Engine engine;
        ModelEntry.Source source;
        try {
            engine = ModelEntry.Engine.fromJson(JsonIO.stringValue(map.get("engine")));
            source = ModelEntry.Source.fromJson(JsonIO.stringValue(map.get("source")));
        } catch (IllegalArgumentException e) {
            throw new IOException("Malformed model catalog entry: engine='"
                    + JsonIO.stringValue(map.get("engine")) + "', source='"
                    + JsonIO.stringValue(map.get("source")) + "': " + e.getMessage(), e);
        }
        return new ModelEntry(
                JsonIO.stringValue(map.get("modelKey")),
                JsonIO.stringValue(map.get("name")),
                JsonIO.stringValue(map.get("description")),
                engine,
                source,
                JsonIO.stringValue(map.get("filePath")),
                JsonIO.stringValue(map.get("resourcePath")),
                JsonIO.stringValue(map.get("pretrainedModel")),
                JsonIO.stringValue(map.get("fijiModelChoice")),
                JsonIO.stringValue(map.get("base")),
                copyTags(JsonIO.asList(map.get("tags"))),
                copyObjectMap(JsonIO.asObject(map.get("defaults"))),
                copyObjectMap(JsonIO.asObject(map.get("metadata"))),
                JsonIO.booleanValue(map.get("supportsSecondChannel"), false));
    }

    private static WarningSink defaultWarningSink() {
        return new WarningSink() {
            @Override
            public void warn(String message) {
                try {
                    IJ.log("[FLASH] " + message);
                } catch (Throwable t) {
                    LOGGER.log(Level.WARNING, "[FLASH] " + message, t);
                }
            }
        };
    }

    private static Set<String> copyTags(List<Object> input) {
        LinkedHashSet<String> out = new LinkedHashSet<String>();
        if (input != null) {
            for (Object item : input) {
                String tag = item == null ? "" : String.valueOf(item).trim();
                if (!tag.isEmpty()) {
                    out.add(tag);
                }
            }
        }
        return out;
    }

    private static Map<String, Object> copyObjectMap(Map<String, Object> input) {
        LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
        if (input != null) {
            out.putAll(input);
        }
        return out;
    }

    private static void requireSafeRelativePath(String value, String fieldName, String modelKey)
            throws IOException {
        if (value == null || value.trim().isEmpty()) {
            throw new IOException("Model entry " + modelKey + " is missing " + fieldName + ".");
        }
        String normalized = value.replace('\\', '/');
        Path path = Paths.get(normalized);
        if (path.isAbsolute() || normalized.startsWith("/") || normalized.indexOf(':') >= 0) {
            throw new IOException("Model entry " + modelKey + " has non-project-relative "
                    + fieldName + ": " + value);
        }
        String[] segments = normalized.split("/");
        for (int i = 0; i < segments.length; i++) {
            if (segments[i].isEmpty() || ".".equals(segments[i]) || "..".equals(segments[i])) {
                throw new IOException("Model entry " + modelKey + " has unsafe "
                        + fieldName + ": " + value);
            }
        }
    }

    private static InputStream openClasspathResource(String resourcePath) {
        String path = resourcePath;
        while (path.startsWith("/")) {
            path = path.substring(1);
        }
        ClassLoader context = Thread.currentThread().getContextClassLoader();
        InputStream in = context == null ? null : context.getResourceAsStream(path);
        if (in != null) return in;
        ClassLoader own = ModelCatalogIO.class.getClassLoader();
        in = own == null ? null : own.getResourceAsStream(path);
        if (in != null) return in;
        return ClassLoader.getSystemResourceAsStream(path);
    }

    private static String readUtf8(InputStream in) throws IOException {
        try (InputStream closeable = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = closeable.read(buffer)) >= 0) {
                out.write(buffer, 0, read);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static void warn(String message) {
        warningSink.warn(message);
    }
}
