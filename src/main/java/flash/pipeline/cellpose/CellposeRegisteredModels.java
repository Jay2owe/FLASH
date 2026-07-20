package flash.pipeline.cellpose;

import flash.pipeline.segmentation.SegmentationMethod;
import flash.pipeline.segmentation.catalog.ModelCatalogIO;
import flash.pipeline.segmentation.catalog.ModelEntry;
import flash.pipeline.ui.wizard.JsonIO;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Environment-scoped bridge from Cellpose's authoritative model registry into
 * FLASH's project model catalog.
 */
public final class CellposeRegisteredModels {
    private static final Logger LOGGER =
            Logger.getLogger(CellposeRegisteredModels.class.getName());
    static final long DISCOVERY_TIMEOUT_SECONDS = 45L;
    static final long SUCCESS_TTL_MS = 5L * 60_000L;
    static final long FAILURE_RETRY_MS = 2_000L;
    private static final int PROTOCOL_VERSION = 1;
    private static final int MAX_MODELS = 4096;
    private static final int MAX_MODEL_NAME_CHARACTERS = 512;
    private static final String VALIDATED_METADATA_KEY = "cellposeValidated";
    private static final String VALIDATED_VERSION_METADATA_KEY = "cellposeVersion";
    private static final String VALIDATED_ENVIRONMENT_METADATA_KEY = "cellposeEnvironment";
    private static final Set<String> SUPPORTED_BUILTIN_NAMES =
            Collections.unmodifiableSet(new HashSet<String>(Arrays.asList(
                    "cyto3", "cyto2", "cyto", "nuclei", "tissuenet_cp3",
                    "livecell_cp3", "yeast_phc_cp3", "yeast_bf_cp3",
                    "bact_phase_cp3", "bact_fluor_cp3", "deepbacs_cp3",
                    "cyto2_cp3")));
    private static final Object CACHE_LOCK = new Object();

    static final class DiscoveryEnvironment {
        final String pythonPath;
        final String cellposeVersion;
        final String cacheKey;

        DiscoveryEnvironment(String pythonPath, String cellposeVersion, String cacheKey) {
            this.pythonPath = cleanRequired(pythonPath, "Python executable");
            this.cellposeVersion = cleanRequired(cellposeVersion, "Cellpose version");
            this.cacheKey = cleanRequired(cacheKey, "Cellpose environment key");
        }
    }

    interface EnvironmentProvider {
        DiscoveryEnvironment current() throws Exception;
    }

    interface DiscoveryBackend {
        Map<String, Object> discover(DiscoveryEnvironment environment,
                                     long timeout,
                                     TimeUnit unit) throws Exception;
    }

    private static final class CacheEntry {
        final String environmentKey;
        final long completedAtMs;
        final List<ModelEntry> entries;

        CacheEntry(String environmentKey, long completedAtMs, List<ModelEntry> entries) {
            this.environmentKey = environmentKey;
            this.completedAtMs = completedAtMs;
            this.entries = entries;
        }
    }

    private static final class FailedAttempt {
        final String environmentKey;
        final long failedAtMs;

        FailedAttempt(String environmentKey, long failedAtMs) {
            this.environmentKey = environmentKey;
            this.failedAtMs = failedAtMs;
        }
    }

    private static final EnvironmentProvider DEFAULT_ENVIRONMENT_PROVIDER =
            new EnvironmentProvider() {
                @Override public DiscoveryEnvironment current() throws Exception {
                    CellposeRuntime.Status status = CellposeRuntime.probeConfigured();
                    if (status == null || !status.ready) {
                        throw new IllegalStateException(status == null
                                ? "Cellpose is not configured."
                                : status.message);
                    }
                    if (!CellposeRuntime.SUPPORTED_CELLPOSE_VERSION.equals(
                            status.cellposeVersion)) {
                        throw new IllegalStateException("Unsupported Cellpose version: "
                                + status.cellposeVersion);
                    }
                    String key = CellposeRuntime.registeredModelsEnvironmentKey(status);
                    return new DiscoveryEnvironment(status.pythonPath,
                            status.cellposeVersion, key);
                }
            };
    private static final DiscoveryBackend DEFAULT_DISCOVERY_BACKEND =
            new DiscoveryBackend() {
                @Override public Map<String, Object> discover(
                        DiscoveryEnvironment environment,
                        long timeout,
                        TimeUnit unit) throws Exception {
                    return CellposePersistentWorker.listRegisteredModels(
                            "catalog_discovery", environment.pythonPath,
                            environment.cellposeVersion, environment.cacheKey,
                            timeout, unit);
                }
            };
    private static final LongSupplier SYSTEM_CLOCK = new LongSupplier() {
        @Override public long getAsLong() {
            return System.currentTimeMillis();
        }
    };

    private static volatile EnvironmentProvider environmentProvider =
            DEFAULT_ENVIRONMENT_PROVIDER;
    private static volatile DiscoveryBackend discoveryBackend =
            DEFAULT_DISCOVERY_BACKEND;
    private static volatile LongSupplier clock = SYSTEM_CLOCK;
    private static volatile CacheEntry cached;
    private static volatile FailedAttempt failedAttempt;

    private CellposeRegisteredModels() {
    }

    public static List<ModelEntry> fetch(Collection<String> existingModelKeys) {
        List<ModelEntry> entries = discoveredEntries();
        return skipExisting(entries, existingModelKeys);
    }

    public static List<ModelEntry> entriesFromResponse(String json,
                                                       Collection<String> existingModelKeys)
            throws Exception {
        return entriesFromResponse(JsonIO.parseObject(json), existingModelKeys);
    }

    public static List<ModelEntry> entriesFromResponse(Map<String, Object> response,
                                                       Collection<String> existingModelKeys) {
        List<ModelEntry> parsed = parseEntries(response, null);
        return skipExisting(parsed, existingModelKeys);
    }

    public static boolean isDiscoveredCellposeEntry(ModelEntry entry) {
        if (entry == null) {
            return false;
        }
        Object value = entry.metadata.get(ModelCatalogIO.DISCOVERED_FROM_METADATA_KEY);
        return ModelCatalogIO.CELLPOSE_DISCOVERY_SOURCE.equalsIgnoreCase(
                String.valueOf(value));
    }

    public static boolean isValidatedDiscoveredCellposeEntry(ModelEntry entry) {
        if (!isDiscoveredCellposeEntry(entry)) {
            return false;
        }
        return Boolean.TRUE.equals(entry.metadata.get(VALIDATED_METADATA_KEY))
                && CellposeRuntime.SUPPORTED_CELLPOSE_VERSION.equals(
                        stringValue(entry.metadata.get(VALIDATED_VERSION_METADATA_KEY)));
    }

    static boolean isValidatedForCurrentEnvironment(ModelEntry entry) {
        if (!isValidatedDiscoveredCellposeEntry(entry)) {
            return false;
        }
        String expected = stringValue(
                entry.metadata.get(VALIDATED_ENVIRONMENT_METADATA_KEY));
        if (expected == null || expected.isEmpty()) {
            return false;
        }
        try {
            DiscoveryEnvironment current = environmentProvider.current();
            return expected.equals(current.cacheKey);
        } catch (Exception e) {
            return false;
        }
    }

    static boolean isUnsupportedModelName(String name) {
        String normalized = name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
        return "cpsam".equals(normalized) || normalized.startsWith("cpsam_");
    }

    static boolean isSupportedBuiltinName(String name) {
        return name != null && SUPPORTED_BUILTIN_NAMES.contains(
                name.trim().toLowerCase(Locale.ROOT));
    }

    static void invalidateCache() {
        synchronized (CACHE_LOCK) {
            cached = null;
            failedAttempt = null;
        }
    }

    static void resetCacheForTests() {
        invalidateCache();
    }

    static void setEnvironmentProviderForTests(EnvironmentProvider provider) {
        synchronized (CACHE_LOCK) {
            environmentProvider = provider == null
                    ? DEFAULT_ENVIRONMENT_PROVIDER : provider;
            cached = null;
            failedAttempt = null;
        }
    }

    static void setDiscoveryBackendForTests(DiscoveryBackend backend) {
        synchronized (CACHE_LOCK) {
            discoveryBackend = backend == null ? DEFAULT_DISCOVERY_BACKEND : backend;
            cached = null;
            failedAttempt = null;
        }
    }

    static void setClockForTests(LongSupplier testClock) {
        synchronized (CACHE_LOCK) {
            clock = testClock == null ? SYSTEM_CLOCK : testClock;
            cached = null;
            failedAttempt = null;
        }
    }

    static void resetTestHooks() {
        synchronized (CACHE_LOCK) {
            environmentProvider = DEFAULT_ENVIRONMENT_PROVIDER;
            discoveryBackend = DEFAULT_DISCOVERY_BACKEND;
            clock = SYSTEM_CLOCK;
            cached = null;
            failedAttempt = null;
        }
    }

    private static List<ModelEntry> discoveredEntries() {
        final long attemptStartedNanos = System.nanoTime();
        final DiscoveryEnvironment environment;
        try {
            environment = environmentProvider.current();
        } catch (Exception e) {
            LOGGER.log(Level.FINE, "Cellpose registered model discovery unavailable.", e);
            return Collections.emptyList();
        }

        long now = nowMs();
        CacheEntry snapshot = cached;
        if (isFresh(snapshot, environment.cacheKey, now)) {
            return snapshot.entries;
        }
        FailedAttempt failure = failedAttempt;
        if (isRetryDeferred(failure, environment.cacheKey, now)) {
            return Collections.emptyList();
        }

        synchronized (CACHE_LOCK) {
            now = nowMs();
            if (isFresh(cached, environment.cacheKey, now)) {
                return cached.entries;
            }
            if (isRetryDeferred(failedAttempt, environment.cacheKey, now)) {
                return Collections.emptyList();
            }
            try {
                long remainingMs = remainingDiscoveryMillis(attemptStartedNanos);
                Map<String, Object> response = discoveryBackend.discover(environment,
                        remainingMs, TimeUnit.MILLISECONDS);
                List<ModelEntry> parsed = Collections.unmodifiableList(
                        parseEntries(response, environment));
                cached = new CacheEntry(environment.cacheKey, nowMs(), parsed);
                failedAttempt = null;
                return parsed;
            } catch (Exception e) {
                cached = null;
                failedAttempt = new FailedAttempt(environment.cacheKey, nowMs());
                LOGGER.log(Level.FINE,
                        "Cellpose registered model discovery failed for environment "
                                + environment.cacheKey + ".", e);
                return Collections.emptyList();
            }
        }
    }

    private static long remainingDiscoveryMillis(long startedNanos) {
        long timeoutMs = TimeUnit.SECONDS.toMillis(DISCOVERY_TIMEOUT_SECONDS);
        long elapsedNanos = System.nanoTime() - startedNanos;
        long elapsedMs = elapsedNanos <= 0L ? 0L
                : TimeUnit.NANOSECONDS.toMillis(elapsedNanos);
        long remaining = timeoutMs - elapsedMs;
        if (remaining <= 0L) {
            throw new IllegalStateException(
                    "Cellpose model discovery exceeded its overall timeout.");
        }
        return remaining;
    }

    private static List<ModelEntry> parseEntries(Map<String, Object> response,
                                                  DiscoveryEnvironment expected) {
        if (response == null) {
            throw new IllegalArgumentException("Cellpose model response is null.");
        }
        String error = stringValue(response.get("error"));
        if (error != null && !error.trim().isEmpty()) {
            throw new IllegalArgumentException("Cellpose model discovery failed: " + error);
        }
        if (!Boolean.TRUE.equals(response.get("success"))) {
            throw new IllegalArgumentException("Cellpose model response is not successful.");
        }
        requireProtocolVersion(response.get("protocol"));
        String version = requiredText(response.get("cellpose_version"),
                "Cellpose response version");
        if (!CellposeRuntime.SUPPORTED_CELLPOSE_VERSION.equals(version)) {
            throw new IllegalArgumentException("Cellpose model response used unsupported version "
                    + version + ".");
        }
        String environmentKey = requiredText(
                response.get("environment_key"),
                "Cellpose response environment key");
        if (expected != null) {
            if (!expected.cellposeVersion.equals(version)) {
                throw new IllegalArgumentException("Cellpose model response version changed during discovery.");
            }
            if (!expected.cacheKey.equals(environmentKey)) {
                throw new IllegalArgumentException("Cellpose model response came from a different environment.");
            }
        }

        Object modelsValue = response.get("models");
        if (!(modelsValue instanceof List)) {
            throw new IllegalArgumentException("Cellpose model response is missing its model list.");
        }
        List<Object> rawModels = JsonIO.asList(modelsValue);
        if (rawModels.size() > MAX_MODELS) {
            throw new IllegalArgumentException("Cellpose model response exceeds "
                    + MAX_MODELS + " models.");
        }

        List<ModelEntry> out = new ArrayList<ModelEntry>();
        Set<String> seenNames = new HashSet<String>();
        Set<String> seenKeys = new HashSet<String>();
        for (Object raw : rawModels) {
            if (!(raw instanceof Map)) {
                throw new IllegalArgumentException("Cellpose model response contains a malformed entry.");
            }
            Map<String, Object> model = JsonIO.asObject(raw);
            String name = requiredText(model.get("name"),
                    "Cellpose model name");
            if (name.length() > MAX_MODEL_NAME_CHARACTERS || containsControl(name)) {
                throw new IllegalArgumentException("Cellpose model name is unsafe or too long.");
            }
            Object builtinValue = model.get("builtin");
            Object registeredValue = model.get("registered");
            Object runnableValue = model.get("runnable");
            if (!(builtinValue instanceof Boolean)
                    || !(registeredValue instanceof Boolean)
                    || !(runnableValue instanceof Boolean)) {
                throw new IllegalArgumentException("Cellpose model entry is missing validation flags: "
                        + name);
            }
            boolean builtin = ((Boolean) builtinValue).booleanValue();
            if (!((Boolean) registeredValue).booleanValue()
                    || !((Boolean) runnableValue).booleanValue()
                    || isUnsupportedModelName(name)
                    || (builtin && !isSupportedBuiltinName(name))
                    || isAuxiliaryModel(name)) {
                continue;
            }

            String path = JsonIO.stringValue(model.get("path"));
            if (!builtin && !isRunnableUserPath(path)) {
                continue;
            }
            String foldedName = name.toLowerCase(Locale.ROOT);
            if (!seenNames.add(foldedName)) {
                continue;
            }
            String key = modelKey(name, builtin);
            if (key == null || !seenKeys.add(key.toLowerCase(Locale.ROOT))) {
                continue;
            }
            out.add(entryFor(name, key, builtin, path, version, environmentKey));
        }
        return out;
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
                    existing.add(key.toLowerCase(Locale.ROOT));
                }
            }
        }
        List<ModelEntry> out = new ArrayList<ModelEntry>();
        for (ModelEntry entry : entries) {
            if (entry != null
                    && existing.add(entry.modelKey.toLowerCase(Locale.ROOT))) {
                out.add(entry);
            }
        }
        return Collections.unmodifiableList(out);
    }

    private static ModelEntry entryFor(String name,
                                       String key,
                                       boolean builtin,
                                       String path,
                                       String version,
                                       String environmentKey) {
        Map<String, Object> metadata = new LinkedHashMap<String, Object>();
        metadata.put(ModelCatalogIO.DISCOVERED_FROM_METADATA_KEY,
                ModelCatalogIO.CELLPOSE_DISCOVERY_SOURCE);
        metadata.put("registeredName", name);
        metadata.put(VALIDATED_METADATA_KEY, Boolean.TRUE);
        metadata.put(VALIDATED_VERSION_METADATA_KEY, version);
        metadata.put(VALIDATED_ENVIRONMENT_METADATA_KEY, environmentKey);
        if (path != null && !path.trim().isEmpty()) {
            metadata.put("registeredPath", path.trim());
        }

        return new ModelEntry(
                key,
                "Cellpose - " + name,
                builtin
                        ? "Cellpose built-in model validated in the configured runtime."
                        : "Registered Cellpose model validated in the configured runtime.",
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
        return Paths.get(path.trim()).toAbsolutePath().normalize().toString();
    }

    private static boolean isRunnableUserPath(String path) {
        if (path == null || path.trim().isEmpty()) {
            return false;
        }
        try {
            Path candidate = Paths.get(path.trim());
            return candidate.isAbsolute()
                    && !Files.isSymbolicLink(candidate)
                    && Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS);
        } catch (Exception ignored) {
            return false;
        }
    }

    private static boolean isAuxiliaryModel(String name) {
        String normalized = name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
        return normalized.startsWith("size_") && normalized.endsWith(".npy");
    }

    private static boolean containsControl(String text) {
        for (int i = 0; i < text.length(); i++) {
            if (Character.isISOControl(text.charAt(i))) {
                return true;
            }
        }
        return false;
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

    private static void requireProtocolVersion(Object value) {
        if (!(value instanceof Number)
                || ((Number) value).doubleValue() != PROTOCOL_VERSION) {
            throw new IllegalArgumentException("Unsupported Cellpose model protocol: " + value);
        }
    }

    private static String cleanRequired(String value, String label) {
        String cleaned = value == null ? "" : value.trim();
        if (cleaned.isEmpty()) {
            throw new IllegalArgumentException(label + " must not be empty.");
        }
        return cleaned;
    }

    private static String requiredText(Object value, String label) {
        if (!(value instanceof String)) {
            throw new IllegalArgumentException(label + " must be text.");
        }
        return cleanRequired((String) value, label);
    }

    private static String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static boolean isFresh(CacheEntry entry, String key, long now) {
        return entry != null && entry.environmentKey.equals(key)
                && Math.max(0L, now - entry.completedAtMs) <= SUCCESS_TTL_MS;
    }

    private static boolean isRetryDeferred(FailedAttempt failure, String key, long now) {
        return failure != null && failure.environmentKey.equals(key)
                && Math.max(0L, now - failure.failedAtMs) < FAILURE_RETRY_MS;
    }

    private static long nowMs() {
        LongSupplier supplier = clock;
        return supplier == null ? System.currentTimeMillis() : supplier.getAsLong();
    }
}
