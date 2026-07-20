package flash.pipeline.ui.wizard;

import flash.pipeline.io.FlashProjectLayout;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Generic atomic file IO for wizard presets.
 */
public abstract class PresetIO<T extends Preset<?>> {

    private static final int STOCK_MANIFEST_SCHEMA = 1;
    private static final String STOCK_MANIFEST_FILE = ".flash-managed-stock";
    private static final int USER_FILENAME_STEM_CODE_POINTS = 40;
    private static final ConcurrentMap<String, Object> STOCK_LOCKS =
            new ConcurrentHashMap<String, Object>();

    private final File projectRoot;

    protected PresetIO(File projectRoot) {
        if (projectRoot == null) {
            throw new IllegalArgumentException("projectRoot is required.");
        }
        this.projectRoot = projectRoot;
    }

    public List<T> listAll() throws IOException {
        bootstrapStockPresets();
        Map<String, PresetRecord> selected = new LinkedHashMap<String, PresetRecord>();
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
                String identity = requireCanonicalName(preset.getName(), file);
                PresetRecord candidate = new PresetRecord(
                        preset, isCurrentUserFile(file, preset));
                PresetRecord existing = selected.get(identity);
                if (existing == null || (candidate.currentUserFile
                        && !existing.currentUserFile)) {
                    selected.put(identity, candidate);
                }
            }
        }
        List<T> presets = new ArrayList<T>(selected.size());
        for (PresetRecord record : selected.values()) {
            presets.add(record.preset);
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
        if (canonicalName(preset.getName()) == null) {
            throw new IllegalArgumentException("preset name is required.");
        }
        File dir = presetDirectory();
        ensureDirectory(dir);
        synchronized (stockLock(dir)) {
            File target = collisionSafeUserTarget(preset, dir);
            guardPresetFile(target, dir);
            File temp = File.createTempFile(
                    stripExtension(target.getName()) + "-", ".tmp", dir);
            boolean moved = false;
            try {
                byte[] content = JsonIO.write(preset.toJsonObject())
                        .getBytes(StandardCharsets.UTF_8);
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
    }

    public void delete(String name) throws IOException {
        bootstrapStockPresets();
        File dir = presetDirectory();
        synchronized (stockLock(dir)) {
            File file = resolvePresetFile(name);
            if (file == null || !file.isFile()) {
                throw new FileNotFoundException("Preset not found: " + name);
            }

            String identity = requireCanonicalName(readPreset(file).getName(), file);
            List<File> files = resolveExactPresetFiles(identity);
            if (files.isEmpty()) {
                files.add(file);
            }
            List<StockDefinition> definitions = readStockDefinitions(dir);
            Set<String> stockKeys = new LinkedHashSet<String>();
            for (File exactFile : files) {
                stockKeys.addAll(stockKeysForFile(exactFile, definitions));
            }

            StockManifest manifest = readStockManifest(dir);
            StockManifest updated = manifest.copy();
            updated.tombstones.addAll(stockKeys);
            deletePresetFiles(files, dir, manifest, updated);
        }
    }

    public void bootstrapStockPresets() throws IOException {
        File dir = presetDirectory();
        ensureDirectory(dir);
        synchronized (stockLock(dir)) {
            List<StockDefinition> definitions = readStockDefinitions(dir);
            StockManifest existingManifest = readStockManifest(dir);
            StockManifest updatedManifest = existingManifest.copy();
            List<PendingReplacement> replacements = new ArrayList<PendingReplacement>();
            Throwable failure = null;
            try {
                for (StockDefinition stock : definitions) {
                    ManagedStock managed = updatedManifest.managed.get(stock.key);
                    if (updatedManifest.tombstones.contains(stock.key)) {
                        continue;
                    }

                    File target = stock.target;
                    if (managed == null) {
                        if (target.isFile() || hasUserPresetIdentity(stock, target)) {
                            // An untracked legacy or custom file is user-owned, even if
                            // its bytes happen to match a bundled generation.
                            continue;
                        }
                        replacements.add(stageReplacement(dir, target, stock.content));
                        updatedManifest.managed.put(stock.key, stock.asManaged());
                        continue;
                    }

                    if (!target.isFile()) {
                        if (hasUserPresetIdentity(stock, target)) {
                            continue;
                        }
                        replacements.add(stageReplacement(dir, target, stock.content));
                        updatedManifest.managed.put(stock.key, stock.asManaged());
                        continue;
                    }

                    byte[] current = Files.readAllBytes(guardPresetFile(target, dir).toPath());
                    String currentFingerprint = fingerprint(current);
                    if (!currentFingerprint.equals(managed.fingerprint)) {
                        // A changed managed file is now a user override. Retain the old
                        // fingerprint so future runs continue to recognise it as changed.
                        continue;
                    }
                    if (compareVersions(stock.version, managed.version) <= 0) {
                        continue;
                    }
                    if (!currentFingerprint.equals(stock.fingerprint)) {
                        replacements.add(stageReplacement(dir, target, stock.content));
                    }
                    updatedManifest.managed.put(stock.key, stock.asManaged());
                }

                if (!updatedManifest.equals(existingManifest)) {
                    replacements.add(stageReplacement(dir, stockManifestFile(dir),
                            serializeStockManifest(updatedManifest)));
                }

                commitReplacements(replacements);
            } catch (Throwable primaryFailure) {
                failure = primaryFailure;
            } finally {
                failure = cleanupStagedReplacements(replacements, failure);
            }
            if (failure != null) {
                rethrowTransactionFailure(failure);
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

    protected abstract T parsePreset(String json) throws IOException;

    protected List<String> stockResourceFiles() {
        return Collections.emptyList();
    }

    protected String stockResourceDirectory() {
        return "";
    }

    /** Stable family identity used only for managed bundled stock metadata. */
    protected String stockFamilyKey() {
        return trimSlashes(stockResourceDirectory());
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
        // Retry/backoff move, then in-place rewrite if the destination stays
        // locked against rename (Windows + Dropbox/OneDrive). Safe: small preset.
        flash.pipeline.io.IoUtils.commitReplacingSmallFile(source.toPath(), target.toPath());
    }

    private Object stockLock(File directory) throws IOException {
        String key = directory.getCanonicalPath();
        Object created = new Object();
        Object existing = STOCK_LOCKS.putIfAbsent(key, created);
        return existing == null ? created : existing;
    }

    private List<StockDefinition> readStockDefinitions(File directory) throws IOException {
        List<StockDefinition> definitions = new ArrayList<StockDefinition>();
        Set<String> keys = new LinkedHashSet<String>();
        Set<String> targets = new LinkedHashSet<String>();
        for (String resourceName : stockResourceFiles()) {
            byte[] content = readResource(resourceName);
            if (content == null) {
                continue;
            }
            T bundled = parsePreset(new String(content, StandardCharsets.UTF_8));
            String userIdentity = sanitizeLookup(bundled.getName());
            if (userIdentity == null) {
                throw new IOException("Bundled stock preset has no usable name: " + resourceName);
            }
            String targetName = new File(resourceName).getName();
            if (!isJson(targetName)) {
                throw new IOException("Bundled stock preset is not JSON: " + resourceName);
            }
            String key = stockIdentityKey(resourceName);
            String targetKey = targetName.toLowerCase(Locale.ROOT);
            if (!keys.add(key)) {
                throw new IOException("Duplicate bundled stock key: " + key);
            }
            if (!targets.add(targetKey)) {
                throw new IOException("Duplicate bundled stock target: " + targetName);
            }
            File target = guardPresetFile(new File(directory, targetName), directory);
            definitions.add(new StockDefinition(key, targetName, target,
                    bundled.getLibraryVersion(), userIdentity, content));
        }
        return definitions;
    }

    private String stockIdentityKey(String resourceName) throws IOException {
        String family = stockFamilyKey();
        if (family == null || family.trim().length() == 0) {
            throw new IOException("Stock preset family key is required for " + presetDirectoryName());
        }
        String resource = resourceName == null ? "" : resourceName.trim().replace('\\', '/');
        while (resource.startsWith("/")) {
            resource = resource.substring(1);
        }
        if (resource.length() == 0 || resource.contains("../") || resource.equals("..")) {
            throw new IOException("Invalid bundled stock resource name: " + resourceName);
        }
        return family.trim().toLowerCase(Locale.ROOT) + ":"
                + resource.toLowerCase(Locale.ROOT);
    }

    private boolean hasUserPresetIdentity(StockDefinition stock, File excludedTarget)
            throws IOException {
        File excluded = excludedTarget.getCanonicalFile();
        for (File readDirectory : presetReadDirectories()) {
            if (!readDirectory.isDirectory()) {
                continue;
            }
            File[] files = readDirectory.listFiles((parent, name) -> isJson(name));
            if (files == null) {
                continue;
            }
            for (File file : files) {
                if (!isPresetFileInsideDirectory(file, readDirectory)
                        || file.getCanonicalFile().equals(excluded)) {
                    continue;
                }
                T candidate = readPreset(file, readDirectory);
                if (stock.userIdentity.equals(sanitizeLookup(candidate.getName()))) {
                    return true;
                }
            }
        }
        return false;
    }

    private Set<String> stockKeysForFile(File file, List<StockDefinition> definitions)
            throws IOException {
        Set<String> keys = new LinkedHashSet<String>();
        String filename = file.getCanonicalFile().getName();
        for (StockDefinition definition : definitions) {
            if (filename.equalsIgnoreCase(definition.targetName)) {
                keys.add(definition.key);
            }
        }
        return keys;
    }

    private File stockManifestFile(File directory) {
        return new File(directory, STOCK_MANIFEST_FILE);
    }

    private StockManifest readStockManifest(File directory) throws IOException {
        File manifestFile = guardPresetFile(stockManifestFile(directory), directory);
        if (!manifestFile.exists()) {
            return new StockManifest();
        }
        if (!manifestFile.isFile()) {
            throw new IOException("Managed stock manifest is not a file: "
                    + manifestFile.getAbsolutePath());
        }
        Map<String, Object> root = JsonIO.parseObject(new String(
                Files.readAllBytes(manifestFile.toPath()), StandardCharsets.UTF_8));
        int schema = JsonIO.intValue(root.get("schemaVersion"), -1);
        if (schema != STOCK_MANIFEST_SCHEMA) {
            throw new IOException("Unsupported managed stock manifest schema " + schema
                    + " in " + manifestFile.getAbsolutePath());
        }

        StockManifest manifest = new StockManifest();
        Map<String, Object> managed = JsonIO.asObject(root.get("managed"));
        for (Map.Entry<String, Object> entry : managed.entrySet()) {
            String key = entry.getKey();
            Map<String, Object> record = JsonIO.asObject(entry.getValue());
            String target = JsonIO.stringValue(record.get("target"));
            String version = JsonIO.stringValue(record.get("version"));
            String storedFingerprint = JsonIO.stringValue(record.get("fingerprint"));
            if (key == null || key.trim().length() == 0 || target == null
                    || target.length() == 0 || storedFingerprint == null
                    || storedFingerprint.length() == 0) {
                throw new IOException("Invalid managed stock record in "
                        + manifestFile.getAbsolutePath());
            }
            if (!new File(target).getName().equals(target) || !isJson(target)) {
                throw new IOException("Unsafe managed stock target: " + target);
            }
            manifest.managed.put(key, new ManagedStock(target, version, storedFingerprint));
        }
        for (Object value : JsonIO.asList(root.get("tombstones"))) {
            String key = JsonIO.stringValue(value);
            if (key != null && key.trim().length() > 0) {
                manifest.tombstones.add(key);
            }
        }
        return manifest;
    }

    private byte[] serializeStockManifest(StockManifest manifest) {
        Map<String, Object> root = new LinkedHashMap<String, Object>();
        root.put("schemaVersion", Integer.valueOf(STOCK_MANIFEST_SCHEMA));
        Map<String, Object> managed = new LinkedHashMap<String, Object>();
        for (Map.Entry<String, ManagedStock> entry : manifest.managed.entrySet()) {
            ManagedStock stock = entry.getValue();
            Map<String, Object> record = new LinkedHashMap<String, Object>();
            record.put("target", stock.targetName);
            record.put("version", stock.version);
            record.put("fingerprint", stock.fingerprint);
            managed.put(entry.getKey(), record);
        }
        root.put("managed", managed);
        root.put("tombstones", new ArrayList<String>(manifest.tombstones));
        return JsonIO.write(root).getBytes(StandardCharsets.UTF_8);
    }

    private PendingReplacement stageReplacement(File directory, File target, byte[] content)
            throws IOException {
        File safeTarget = guardPresetFile(target, directory);
        if (safeTarget.exists() && !safeTarget.isFile()) {
            throw new IOException("Preset publication target is not a file: "
                    + safeTarget.getAbsolutePath());
        }
        byte[] previous = safeTarget.isFile()
                ? Files.readAllBytes(safeTarget.toPath()) : null;
        File staged = File.createTempFile("stock-reconcile-", ".tmp", directory);
        boolean complete = false;
        try {
            Files.write(staged.toPath(), content);
            byte[] reopened = Files.readAllBytes(staged.toPath());
            if (!Arrays.equals(content, reopened)) {
                throw new IOException("Could not verify staged preset publication for "
                        + safeTarget.getAbsolutePath());
            }
            complete = true;
            return new PendingReplacement(safeTarget, staged, previous, content);
        } finally {
            if (!complete) {
                Files.deleteIfExists(staged.toPath());
            }
        }
    }

    private void commitReplacements(List<PendingReplacement> replacements) throws IOException {
        for (int index = 0; index < replacements.size(); index++) {
            PendingReplacement replacement = replacements.get(index);
            try {
                beforeAtomicReplace(replacement.staged, replacement.target);
                replacement.commitAttempted = true;
                commitStockReplacement(replacement.staged, replacement.target);
                replacement.committed = true;
                byte[] reopened = Files.readAllBytes(replacement.target.toPath());
                if (!Arrays.equals(replacement.content, reopened)) {
                    throw new IOException("Could not verify preset publication for "
                            + replacement.target.getAbsolutePath());
                }
            } catch (Throwable primaryFailure) {
                Throwable failure = rollbackReplacements(
                        replacements, index, primaryFailure);
                rethrowTransactionFailure(failure);
            }
        }
    }

    private void commitStockReplacement(File staged, File target) throws IOException {
        // Stock reconciliation is a multi-file transaction with its own fault
        // boundary. Keep it independent of subclasses that override the normal
        // single-preset move hook for save() tests or custom storage behavior.
        flash.pipeline.io.IoUtils.commitReplacingSmallFile(staged.toPath(), target.toPath());
    }

    private Throwable rollbackReplacements(List<PendingReplacement> replacements, int last,
                                           Throwable originalFailure) {
        boolean restoreInterrupt = Thread.interrupted();
        Throwable failure = originalFailure;
        try {
            for (int index = last; index >= 0; index--) {
                PendingReplacement replacement = replacements.get(index);
                if (!replacement.committed && !replacement.commitAttempted) {
                    continue;
                }
                Path rollback = null;
                Throwable rollbackFailure = null;
                try {
                    if (replacement.previous == null) {
                        Files.deleteIfExists(replacement.target.toPath());
                    } else {
                        rollback = Files.createTempFile(
                                replacement.target.toPath().getParent(),
                                "stock-rollback-", ".tmp");
                        Files.write(rollback, replacement.previous);
                        flash.pipeline.io.IoUtils.commitReplacingSmallFile(
                                rollback, replacement.target.toPath());
                    }
                    replacement.committed = false;
                } catch (Throwable currentFailure) {
                    rollbackFailure = currentFailure;
                } finally {
                    if (rollback != null) {
                        try {
                            Files.deleteIfExists(rollback);
                        } catch (Throwable cleanupFailure) {
                            rollbackFailure = combineTransactionFailures(
                                    rollbackFailure, cleanupFailure);
                        }
                    }
                }
                if (rollbackFailure != null) {
                    failure = combineTransactionFailures(failure, rollbackFailure);
                }
            }
        } finally {
            if (restoreInterrupt || Thread.currentThread().isInterrupted()) {
                Thread.currentThread().interrupt();
            }
        }
        return failure;
    }

    private Throwable cleanupStagedReplacements(
            List<PendingReplacement> replacements, Throwable primaryFailure) {
        boolean restoreInterrupt = Thread.interrupted();
        Throwable failure = primaryFailure;
        try {
            for (PendingReplacement replacement : replacements) {
                try {
                    Files.deleteIfExists(replacement.staged.toPath());
                } catch (Throwable cleanupFailure) {
                    // A non-JSON sibling temp cannot become a visible preset, so
                    // retain the historical best-effort behavior after success.
                    // During failure propagation, preserve every cleanup detail.
                    if (!(cleanupFailure instanceof IOException) || failure != null) {
                        failure = combineTransactionFailures(failure, cleanupFailure);
                    }
                }
            }
        } finally {
            if (restoreInterrupt || Thread.currentThread().isInterrupted()) {
                Thread.currentThread().interrupt();
            }
        }
        return failure;
    }

    private Throwable restoreDeletedPresets(
            List<PendingDeletion> deletions, Throwable primaryFailure) {
        boolean restoreInterrupt = Thread.interrupted();
        Throwable failure = primaryFailure;
        try {
            for (int index = deletions.size() - 1; index >= 0; index--) {
                PendingDeletion deletion = deletions.get(index);
                if (!deletion.removed) {
                    continue;
                }
                try {
                    flash.pipeline.io.IoUtils.commitReplacingSmallFile(
                            deletion.quarantine, deletion.source);
                    deletion.removed = false;
                } catch (Throwable restoreFailure) {
                    failure = combineTransactionFailures(failure, restoreFailure);
                }
            }
        } finally {
            if (restoreInterrupt || Thread.currentThread().isInterrupted()) {
                Thread.currentThread().interrupt();
            }
        }
        return failure;
    }

    private Throwable cleanupCommittedDeletions(
            List<PendingDeletion> deletions, Throwable primaryFailure) {
        boolean restoreInterrupt = Thread.interrupted();
        Throwable failure = primaryFailure;
        try {
            for (PendingDeletion deletion : deletions) {
                if (!deletion.removed) {
                    continue;
                }
                try {
                    Files.deleteIfExists(deletion.quarantine);
                } catch (Throwable cleanupFailure) {
                    // The presets are no longer visible. A retained non-JSON
                    // quarantine file is safer than reporting an ordinary failed
                    // delete after the durable state already committed.
                    if (!(cleanupFailure instanceof IOException) || failure != null) {
                        failure = combineTransactionFailures(failure, cleanupFailure);
                    }
                }
            }
        } finally {
            if (restoreInterrupt || Thread.currentThread().isInterrupted()) {
                Thread.currentThread().interrupt();
            }
        }
        return failure;
    }

    private static Throwable combineTransactionFailures(
            Throwable primary, Throwable additional) {
        if (primary == null) {
            return additional;
        }
        if (additional == null || additional == primary) {
            return primary;
        }
        if (isVmFatal(additional) && !isVmFatal(primary)) {
            additional.addSuppressed(primary);
            return additional;
        }
        primary.addSuppressed(additional);
        return primary;
    }

    private static boolean isVmFatal(Throwable failure) {
        return failure instanceof VirtualMachineError || failure instanceof ThreadDeath;
    }

    private static void rethrowTransactionFailure(Throwable failure) throws IOException {
        if (failure instanceof IOException) {
            throw (IOException) failure;
        }
        if (failure instanceof RuntimeException) {
            throw (RuntimeException) failure;
        }
        if (failure instanceof Error) {
            throw (Error) failure;
        }
        throw new IOException("Unexpected preset transaction failure", failure);
    }

    private void deletePresetFiles(List<File> files, File directory,
                                   StockManifest current, StockManifest updated)
            throws IOException {
        List<PendingReplacement> manifestReplacement =
                new ArrayList<PendingReplacement>();
        if (!updated.equals(current)) {
            manifestReplacement.add(stageReplacement(directory, stockManifestFile(directory),
                    serializeStockManifest(updated)));
        }

        List<PendingDeletion> deletions = new ArrayList<PendingDeletion>();
        boolean deletionCommitted = false;
        Throwable failure = null;
        try {
            for (File file : files) {
                Path source = file.toPath();
                Path quarantine = Files.createTempFile(
                        source.getParent(), ".preset-delete-", ".tmp");
                Files.deleteIfExists(quarantine);
                PendingDeletion deletion = new PendingDeletion(source, quarantine);
                deletions.add(deletion);
                try {
                    Files.move(source, quarantine, StandardCopyOption.ATOMIC_MOVE);
                } catch (IOException atomicFailure) {
                    try {
                        Files.move(source, quarantine);
                    } catch (IOException moveFailure) {
                        moveFailure.addSuppressed(atomicFailure);
                        throw moveFailure;
                    }
                }
                deletion.removed = true;
            }
            commitReplacements(manifestReplacement);
            deletionCommitted = true;
        } catch (Throwable primaryFailure) {
            failure = restoreDeletedPresets(deletions, primaryFailure);
        } finally {
            failure = cleanupStagedReplacements(manifestReplacement, failure);
            if (deletionCommitted) {
                failure = cleanupCommittedDeletions(deletions, failure);
            }
        }
        if (failure != null) {
            rethrowTransactionFailure(failure);
        }
    }

    private static String fingerprint(byte[] content) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(content);
            StringBuilder out = new StringBuilder(hashed.length * 2);
            for (byte value : hashed) {
                out.append(String.format(Locale.ROOT, "%02x", value & 0xff));
            }
            return out.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IOException("SHA-256 is unavailable", impossible);
        }
    }

    private static int compareVersions(String left, String right) {
        if (left == null || left.trim().length() == 0) {
            return 0;
        }
        if (right == null || right.trim().length() == 0) {
            return 1;
        }
        String[] leftParts = left.trim().split("[._-]");
        String[] rightParts = right.trim().split("[._-]");
        int length = Math.max(leftParts.length, rightParts.length);
        for (int i = 0; i < length; i++) {
            String leftPart = i < leftParts.length ? leftParts[i] : "0";
            String rightPart = i < rightParts.length ? rightParts[i] : "0";
            int comparison;
            if (leftPart.matches("[0-9]+") && rightPart.matches("[0-9]+")) {
                comparison = new BigInteger(leftPart).compareTo(new BigInteger(rightPart));
            } else {
                comparison = leftPart.compareToIgnoreCase(rightPart);
            }
            if (comparison != 0) {
                return comparison;
            }
        }
        return 0;
    }

    private static final class StockDefinition {
        final String key;
        final String targetName;
        final File target;
        final String version;
        final String userIdentity;
        final byte[] content;
        final String fingerprint;

        StockDefinition(String key, String targetName, File target, String version,
                        String userIdentity, byte[] content) throws IOException {
            this.key = key;
            this.targetName = targetName;
            this.target = target;
            this.version = version;
            this.userIdentity = userIdentity;
            this.content = content;
            this.fingerprint = PresetIO.fingerprint(content);
        }

        ManagedStock asManaged() {
            return new ManagedStock(targetName, version, fingerprint);
        }
    }

    private static final class ManagedStock {
        final String targetName;
        final String version;
        final String fingerprint;

        ManagedStock(String targetName, String version, String fingerprint) {
            this.targetName = targetName;
            this.version = version;
            this.fingerprint = fingerprint;
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof ManagedStock)) {
                return false;
            }
            ManagedStock that = (ManagedStock) other;
            return equal(targetName, that.targetName) && equal(version, that.version)
                    && equal(fingerprint, that.fingerprint);
        }

        @Override
        public int hashCode() {
            return targetName.hashCode();
        }
    }

    private static final class StockManifest {
        final Map<String, ManagedStock> managed =
                new LinkedHashMap<String, ManagedStock>();
        final Set<String> tombstones = new LinkedHashSet<String>();

        StockManifest copy() {
            StockManifest copy = new StockManifest();
            copy.managed.putAll(managed);
            copy.tombstones.addAll(tombstones);
            return copy;
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof StockManifest)) {
                return false;
            }
            StockManifest that = (StockManifest) other;
            return managed.equals(that.managed) && tombstones.equals(that.tombstones);
        }

        @Override
        public int hashCode() {
            return managed.hashCode() * 31 + tombstones.hashCode();
        }
    }

    private static final class PendingReplacement {
        final File target;
        final File staged;
        final byte[] previous;
        final byte[] content;
        boolean commitAttempted;
        boolean committed;

        PendingReplacement(File target, File staged, byte[] previous, byte[] content) {
            this.target = target;
            this.staged = staged;
            this.previous = previous;
            this.content = content;
        }
    }

    private static final class PendingDeletion {
        final Path source;
        final Path quarantine;
        boolean removed;

        PendingDeletion(Path source, Path quarantine) {
            this.source = source;
            this.quarantine = quarantine;
        }
    }

    private final class PresetRecord {
        final T preset;
        final boolean currentUserFile;

        PresetRecord(T preset, boolean currentUserFile) {
            this.preset = preset;
            this.currentUserFile = currentUserFile;
        }
    }

    private static boolean equal(Object left, Object right) {
        return left == null ? right == null : left.equals(right);
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
        String requested = canonicalName(name);
        if (requested == null) {
            return null;
        }
        File legacyMatch = null;
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
                T preset = readPreset(file, dir);
                if (!requested.equals(requireCanonicalName(preset.getName(), file))) {
                    continue;
                }
                if (isCurrentUserFile(file, preset)) {
                    return file;
                }
                if (legacyMatch == null) {
                    legacyMatch = file;
                }
            }
        }
        if (legacyMatch != null) {
            return legacyMatch;
        }
        File stockAlias = resolveStockFilenameAlias(name, dirs);
        return stockAlias != null ? stockAlias : resolveLegacyFilenameAlias(name, dirs);
    }

    private List<File> resolveExactPresetFiles(String identity) throws IOException {
        List<File> matches = new ArrayList<File>();
        for (File dir : presetReadDirectories()) {
            if (!dir.isDirectory()) {
                continue;
            }
            File[] files = dir.listFiles((parent, fileName) -> isJson(fileName));
            if (files == null) {
                throw new IOException("Could not list preset directory "
                        + dir.getAbsolutePath());
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
                if (identity.equals(requireCanonicalName(preset.getName(), file))) {
                    matches.add(file);
                }
            }
        }
        return matches;
    }

    private File resolveLegacyFilenameAlias(String requestedName, List<File> dirs)
            throws IOException {
        String requestedToken = legacyFileToken(requestedName);
        File selected = null;
        String selectedIdentity = null;
        boolean selectedIsCurrent = false;
        for (File dir : dirs) {
            if (!dir.isDirectory()) {
                continue;
            }
            File[] files = dir.listFiles((parent, fileName) -> isJson(fileName));
            if (files == null) {
                throw new IOException("Could not list preset directory "
                        + dir.getAbsolutePath());
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
                if (!requestedToken.equals(legacyFileToken(preset.getName()))) {
                    continue;
                }
                String identity = requireCanonicalName(preset.getName(), file);
                if (selectedIdentity != null && !selectedIdentity.equals(identity)) {
                    throw new IOException("Ambiguous legacy preset token '"
                            + requestedName + "' matches distinct embedded names");
                }
                boolean current = isCurrentUserFile(file, preset);
                if (selected == null || (current && !selectedIsCurrent)) {
                    selected = file;
                    selectedIdentity = identity;
                    selectedIsCurrent = current;
                }
            }
        }
        return selected;
    }

    private File resolveStockFilenameAlias(String requestedName, List<File> dirs)
            throws IOException {
        String alias = requestedName == null ? "" : requestedName.trim();
        if (isJson(alias)) {
            alias = stripExtension(alias);
        }
        if (alias.length() == 0) {
            return null;
        }
        List<StockDefinition> definitions = readStockDefinitions(presetDirectory());
        for (StockDefinition stock : definitions) {
            if (!alias.equalsIgnoreCase(stripExtension(stock.targetName))) {
                continue;
            }
            for (File dir : dirs) {
                File candidate = findWindowsEquivalent(dir, stock.targetName);
                if (candidate == null || !candidate.isFile()
                        || !isPresetFileInsideDirectory(candidate, dir)) {
                    continue;
                }
                T preset = readPreset(candidate, dir);
                if (stock.userIdentity.equals(
                        requireCanonicalName(preset.getName(), candidate))) {
                    return candidate;
                }
            }
        }
        return null;
    }

    private File collisionSafeUserTarget(T preset, File directory) throws IOException {
        String requestedIdentity = requireCanonicalName(preset.getName(), null);
        String legacyBase = compatibleLegacyWriteBase(preset.getName());
        if (legacyBase != null) {
            File legacy = findWindowsEquivalent(directory, legacyBase + ".json");
            if (legacy == null) {
                return guardPresetFile(new File(directory, legacyBase + ".json"), directory);
            }
            if (hasEmbeddedIdentity(legacy, directory, requestedIdentity)) {
                return legacy;
            }
        }

        String base = sanitizeFileToken(preset.getName());
        for (int ordinal = 1; ordinal < Integer.MAX_VALUE; ordinal++) {
            String suffix = ordinal == 1 ? "" : "-" + ordinal;
            String fileName = base + suffix + ".json";
            File existing = findWindowsEquivalent(directory, fileName);
            if (existing == null) {
                return guardPresetFile(new File(directory, fileName), directory);
            }
            if (hasEmbeddedIdentity(existing, directory, requestedIdentity)) {
                return existing;
            }
        }
        throw new IOException("Could not allocate a collision-safe preset filename.");
    }

    private boolean hasEmbeddedIdentity(File file, File directory, String requestedIdentity)
            throws IOException {
        if (file == null || !file.isFile()
                || !isPresetFileInsideDirectory(file, directory)) {
            return false;
        }
        try {
            T saved = readPreset(file, directory);
            return requestedIdentity.equals(requireCanonicalName(saved.getName(), file));
        } catch (IOException ignored) {
            // Malformed or incompatible look-alikes remain user-owned.
            return false;
        }
    }

    private File findWindowsEquivalent(File directory, String fileName) throws IOException {
        if (directory == null || !directory.isDirectory()) {
            return null;
        }
        String wanted = windowsFilenameKey(fileName);
        File[] entries = directory.listFiles();
        if (entries == null) {
            throw new IOException("Could not list preset directory "
                    + directory.getAbsolutePath());
        }
        for (File entry : entries) {
            if (wanted.equals(windowsFilenameKey(entry.getName()))) {
                return entry;
            }
        }
        return null;
    }

    private boolean isCurrentUserFile(File file, T preset) {
        if (file == null || preset == null || !isJson(file.getName())) {
            return false;
        }
        String base = sanitizeFileToken(preset.getName());
        String stem = stripExtension(file.getName());
        String baseKey = windowsFilenameKey(base);
        String stemKey = windowsFilenameKey(stem);
        if (baseKey.equals(stemKey)) {
            return true;
        }
        if (!stemKey.startsWith(baseKey + "-")) {
            return false;
        }
        String suffix = stemKey.substring(baseKey.length() + 1);
        return suffix.matches("[2-9][0-9]*|1[0-9]+");
    }

    private static String windowsFilenameKey(String value) {
        return Normalizer.normalize(value == null ? "" : value,
                Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
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
        String canonical = canonicalName(raw);
        String stem = readableUserStem(canonical);
        return "user-" + stem + "--" + identityDigest(canonical == null ? "" : canonical);
    }

    private static String sanitizeLookup(String raw) {
        return canonicalName(raw);
    }

    private static String legacyFileToken(String raw) {
        if (raw == null) {
            return "preset";
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        if (normalized.length() == 0) {
            return "preset";
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
        return normalized.length() == 0 ? "preset" : normalized;
    }

    private static String compatibleLegacyWriteBase(String raw) {
        String canonical = canonicalName(raw);
        if (canonical == null || canonical.length() > 80) {
            return null;
        }
        for (int i = 0; i < canonical.length(); i++) {
            if (canonical.charAt(i) > 0x7f) {
                return null;
            }
        }
        String token = legacyFileToken(canonical);
        String upper = token.toUpperCase(Locale.ROOT);
        if (upper.equals("CON") || upper.equals("PRN") || upper.equals("AUX")
                || upper.equals("NUL") || upper.matches("COM[1-9]")
                || upper.matches("LPT[1-9]")) {
            return null;
        }
        return token;
    }

    /**
     * User identity policy: trim surrounding whitespace, normalize to NFC,
     * and preserve case. Canonically equivalent composed/decomposed spelling
     * is one logical name; case and punctuation remain identity-bearing.
     */
    private static String canonicalName(String raw) {
        if (raw == null) {
            return null;
        }
        String normalized = Normalizer.normalize(raw.trim(), Normalizer.Form.NFC);
        if (normalized.length() == 0) {
            return null;
        }
        return normalized;
    }

    private static String requireCanonicalName(String raw, File source) throws IOException {
        String canonical = canonicalName(raw);
        if (canonical != null) {
            return canonical;
        }
        throw new IOException("Preset has no usable embedded name"
                + (source == null ? "" : ": " + source.getAbsolutePath()));
    }

    private static String readableUserStem(String canonical) {
        if (canonical == null) {
            return "preset";
        }
        String lower = canonical.toLowerCase(Locale.ROOT);
        StringBuilder stem = new StringBuilder();
        boolean pendingSeparator = false;
        int kept = 0;
        for (int offset = 0; offset < lower.length()
                && kept < USER_FILENAME_STEM_CODE_POINTS;) {
            int codePoint = lower.codePointAt(offset);
            if (Character.isLetterOrDigit(codePoint)) {
                if (pendingSeparator && stem.length() > 0) {
                    stem.append('_');
                }
                stem.appendCodePoint(codePoint);
                pendingSeparator = false;
                kept++;
            } else {
                pendingSeparator = true;
            }
            offset += Character.charCount(codePoint);
        }
        return stem.length() == 0 ? "preset" : stem.toString();
    }

    private static String identityDigest(String canonical) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(canonical.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(hashed.length * 2);
            for (byte value : hashed) {
                out.append(String.format(Locale.ROOT, "%02x", value & 0xff));
            }
            return out.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
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
