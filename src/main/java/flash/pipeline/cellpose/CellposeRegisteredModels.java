package flash.pipeline.cellpose;

import flash.pipeline.segmentation.SegmentationMethod;
import flash.pipeline.segmentation.catalog.ModelCatalogIO;
import flash.pipeline.segmentation.catalog.ModelEntry;
import flash.pipeline.ui.wizard.JsonIO;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Best-effort bridge from Cellpose's registered model store into FLASH's
 * project model catalog.
 */
public final class CellposeRegisteredModels {
    private static final Logger LOGGER =
            Logger.getLogger(CellposeRegisteredModels.class.getName());
    private static final long FETCH_TIMEOUT_SECONDS = 3L;
    private static final Object CACHE_LOCK = new Object();
    private static volatile boolean attempted;
    private static volatile List<ModelEntry> cachedEntries;

    private CellposeRegisteredModels() {
    }

    public static List<ModelEntry> fetch(Collection<String> existingModelKeys) {
        List<ModelEntry> entries = cachedDiscoveredEntries();
        return skipExisting(entries, existingModelKeys);
    }

    public static List<ModelEntry> entriesFromResponse(String json,
                                                       Collection<String> existingModelKeys)
            throws Exception {
        return entriesFromResponse(JsonIO.parseObject(json), existingModelKeys);
    }

    public static List<ModelEntry> entriesFromResponse(Map<String, Object> response,
                                                       Collection<String> existingModelKeys) {
        List<ModelEntry> parsed = parseEntries(response);
        return skipExisting(parsed, existingModelKeys);
    }

    public static boolean isDiscoveredCellposeEntry(ModelEntry entry) {
        if (entry == null) {
            return false;
        }
        Object value = entry.metadata.get(ModelCatalogIO.DISCOVERED_FROM_METADATA_KEY);
        return ModelCatalogIO.CELLPOSE_DISCOVERY_SOURCE.equalsIgnoreCase(String.valueOf(value));
    }

    static void resetCacheForTests() {
        synchronized (CACHE_LOCK) {
            attempted = false;
            cachedEntries = null;
        }
    }

    private static List<ModelEntry> cachedDiscoveredEntries() {
        List<ModelEntry> cached = cachedEntries;
        if (attempted && cached != null) {
            return cached;
        }
        synchronized (CACHE_LOCK) {
            if (attempted && cachedEntries != null) {
                return cachedEntries;
            }
            attempted = true;
            try {
                Map<String, Object> response = CellposePersistentWorker.listRegisteredModels(
                        "catalog_discovery", FETCH_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                cachedEntries = Collections.unmodifiableList(parseEntries(response));
            } catch (Throwable t) {
                LOGGER.log(Level.FINE, "Cellpose registered model discovery skipped.", t);
                cachedEntries = Collections.emptyList();
            }
            return cachedEntries;
        }
    }

    private static List<ModelEntry> parseEntries(Map<String, Object> response) {
        if (response == null) {
            return Collections.emptyList();
        }
        Object error = response.get("error");
        if (error != null && !String.valueOf(error).trim().isEmpty()) {
            return Collections.emptyList();
        }

        List<Object> rawModels = JsonIO.asList(response.get("models"));
        List<ModelEntry> out = new ArrayList<ModelEntry>();
        Set<String> seen = new HashSet<String>();
        for (Object raw : rawModels) {
            if (!(raw instanceof Map)) {
                continue;
            }
            Map<String, Object> model = JsonIO.asObject(raw);
            String name = JsonIO.stringValue(model.get("name"));
            if (name == null || name.trim().isEmpty()) {
                continue;
            }
            boolean builtin = JsonIO.booleanValue(model.get("builtin"), false);
            String key = modelKey(name, builtin);
            if (key == null || !seen.add(key)) {
                continue;
            }
            out.add(entryFor(name.trim(), key, builtin,
                    JsonIO.stringValue(model.get("path"))));
        }
        return Collections.unmodifiableList(out);
    }

    private static List<ModelEntry> skipExisting(List<ModelEntry> entries,
                                                 Collection<String> existingModelKeys) {
        if (entries == null || entries.isEmpty()) {
            return Collections.emptyList();
        }
        Set<String> existing = new HashSet<String>();
        if (existingModelKeys != null) {
            for (String key : existingModelKeys) {
                if (key != null) {
                    existing.add(key);
                }
            }
        }
        List<ModelEntry> out = new ArrayList<ModelEntry>();
        for (ModelEntry entry : entries) {
            if (entry != null && !existing.contains(entry.modelKey)) {
                out.add(entry);
                existing.add(entry.modelKey);
            }
        }
        return Collections.unmodifiableList(out);
    }

    private static ModelEntry entryFor(String name,
                                       String key,
                                       boolean builtin,
                                       String path) {
        Map<String, Object> metadata = new LinkedHashMap<String, Object>();
        metadata.put(ModelCatalogIO.DISCOVERED_FROM_METADATA_KEY,
                ModelCatalogIO.CELLPOSE_DISCOVERY_SOURCE);
        metadata.put("registeredName", name);
        if (path != null && !path.trim().isEmpty()) {
            metadata.put("registeredPath", path.trim());
        }

        return new ModelEntry(
                key,
                "Cellpose - " + name,
                builtin
                        ? "Cellpose built-in model discovered from the configured runtime."
                        : "Cellpose model discovered from the configured runtime.",
                ModelEntry.Engine.CELLPOSE,
                builtin ? ModelEntry.Source.STOCK_BUILTIN : ModelEntry.Source.USER_IMPORTED,
                builtin ? null : cleanPath(path),
                null,
                name,
                null,
                null,
                defaultCellposeDefaults(),
                metadata,
                supportsSecondChannel(name, key, builtin));
    }

    private static String modelKey(String name, boolean builtin) {
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (builtin) {
            return SegmentationMethod.canonicalCellposeModelKey(trimmed);
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                    || (c >= '0' && c <= '9') || c == '_' || c == '-' || c == '.') {
                sb.append(c);
            } else if (sb.length() > 0 && sb.charAt(sb.length() - 1) != '_') {
                sb.append('_');
            }
        }
        while (sb.length() > 0 && sb.charAt(sb.length() - 1) == '_') {
            sb.deleteCharAt(sb.length() - 1);
        }
        if (sb.length() == 0) {
            sb.append("cellpose_model");
        }
        return sb.toString();
    }

    private static String cleanPath(String path) {
        if (path == null || path.trim().isEmpty()) {
            return null;
        }
        try {
            return Paths.get(path.trim()).toAbsolutePath().normalize().toString();
        } catch (Exception ignored) {
            return path.trim();
        }
    }

    private static boolean supportsSecondChannel(String name, String key, boolean builtin) {
        if (!builtin) {
            return true;
        }
        java.util.Optional<Boolean> byName = CellposeModel.supportsSecondChannelFor(name);
        if (byName.isPresent()) {
            return byName.get().booleanValue();
        }
        java.util.Optional<Boolean> byKey = CellposeModel.supportsSecondChannelFor(key);
        return byKey.isPresent() && byKey.get().booleanValue();
    }

    private static Map<String, Object> defaultCellposeDefaults() {
        Map<String, Object> defaults = new LinkedHashMap<String, Object>();
        defaults.put("diameter", Double.valueOf(30.0));
        defaults.put("flowThreshold", Double.valueOf(0.4));
        defaults.put("cellprobThreshold", Double.valueOf(0.0));
        return defaults;
    }
}
