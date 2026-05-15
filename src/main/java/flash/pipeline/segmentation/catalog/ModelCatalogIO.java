package flash.pipeline.segmentation.catalog;

import flash.pipeline.bin.BinConfigIO;
import flash.pipeline.ui.wizard.JsonIO;
import ij.IJ;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads and writes the project-scoped segmentation model catalog.
 */
public final class ModelCatalogIO {
    public static final int CATALOG_VERSION = 1;
    static final String CONFIGURATION_DIR = "Configuration";
    static final String CATALOG_DIR = "Segmentation Models";
    static final String FILES_DIR = "files";
    static final String CATALOG_FILENAME = "catalog.json";
    static final String STOCK_CATALOG_RESOURCE = "segmentation_models/stock_catalog.json";
    public static final String PROJECT_REGISTERED_METADATA_KEY = "projectRegistered";

    public interface WarningSink {
        void warn(String message);
    }

    private static volatile WarningSink warningSink = new WarningSink() {
        @Override
        public void warn(String message) {
            try {
                IJ.log("[FLASH] " + message);
            } catch (Throwable ignored) {
                System.err.println("[FLASH] " + message);
            }
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
        for (ModelEntry project : readProjectEntries(projectRoot)) {
            putMerged(merged, project, true);
        }
        return new ModelCatalog(projectRoot, new ArrayList<ModelEntry>(merged.values()));
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

    static Path catalogDirectory(Path projectRoot) {
        return projectRoot.resolve(CONFIGURATION_DIR).resolve(CATALOG_DIR);
    }

    static Path catalogFile(Path projectRoot) {
        return catalogDirectory(projectRoot).resolve(CATALOG_FILENAME);
    }

    static void setWarningSinkForTests(WarningSink sink) {
        warningSink = sink == null ? new WarningSink() {
            @Override
            public void warn(String message) {
                try {
                    IJ.log("[FLASH] " + message);
                } catch (Throwable ignored) {
                    System.err.println("[FLASH] " + message);
                }
            }
        } : sink;
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
        return entry != null && (!entry.isStock() || isProjectRegisteredBuiltin(entry));
    }

    public static boolean isProjectRegisteredBuiltin(ModelEntry entry) {
        if (entry == null || entry.source != ModelEntry.Source.STOCK_BUILTIN) {
            return false;
        }
        Object marker = entry.metadata.get(PROJECT_REGISTERED_METADATA_KEY);
        return Boolean.TRUE.equals(marker) || "true".equalsIgnoreCase(String.valueOf(marker));
    }

    private static List<ModelEntry> readProjectEntries(Path projectRoot) {
        Path file = catalogFile(projectRoot);
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
            throw new IOException(e.getMessage(), e);
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
                copyObjectMap(JsonIO.asObject(map.get("defaults"))),
                copyObjectMap(JsonIO.asObject(map.get("metadata"))),
                JsonIO.booleanValue(map.get("supportsSecondChannel"), false));
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
