package flash.pipeline.deconv;

import flash.pipeline.io.FlashProjectLayout;
import flash.pipeline.io.IoUtils;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.DirectoryStream;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Filesystem helpers for standalone deconvolution outputs and cache layout.
 */
public final class DeconvolutionIO {

    private static final String CACHE_SUBDIR = "3D Deconvolution";
    private static final String LEGACY_ARTIFACT_KEY_PREFIX = "dcv2-";
    private static final String CURRENT_ARTIFACT_KEY_PREFIX = "dcv3-";
    private static final String V3_SOURCE_TOKEN_PREFIX = "v3$";
    private static final int DISPLAY_SUFFIX_LIMIT = 48;
    static final int MAX_RECOVERY_JOURNAL_BYTES = 4 * 1024 * 1024;
    static final int MAX_RECOVERY_JOURNAL_RECORDS = 4096;
    static final int MAX_RECOVERY_JOURNAL_LINE_CHARS = 64 * 1024;
    static final int MAX_RECOVERY_JOURNAL_FIELD_CHARS = 48 * 1024;
    private static final int MAX_RECOVERY_JOURNAL_FIELDS = 10;
    static final String DEFERRED_CLEANUP_DIRECTORY = ".cleanup";
    static final String DEFERRED_CLEANUP_MARKER = "NON_AUTHORITATIVE-CLEANUP";
    static final String DEFERRED_CLEANUP_MARKER_CONTENT =
            "deconv-preauthority-cleanup-v1\n";
    static final int MAX_DEFERRED_CLEANUP_PER_ACCESS = 32;
    private static final String DEFERRED_CLEANUP_QUEUE = ".queue";
    private static final String CLEANUP_TICKETS = ".tickets";
    private static final String CLEANUP_PAYLOADS = ".payloads";
    private static final String CLEANUP_RETAINED = ".retained";
    private static final String MIGRATION_RETAINED_DIRECTORY = ".migration-retained";
    private static final String MIGRATION_V4_DIRECTORY = ".migration-v4";
    private static final String MIGRATION_V4_ACTIVE = "active";
    private static final String CLEANUP_STATE_PREFIX = ".state-";
    private static final int MAX_CLEANUP_STATE_BYTES = 512;
    private static final int MAX_CLEANUP_TICKET_BYTES = 2048;
    private static final long MAX_CLEANUP_QUEUE_SPAN = 1000000L;
    private static final char[] BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".toCharArray();
    private static volatile Boolean directoryDurabilityOverrideForTest;
    private static volatile DirectoryForceTestHook directoryForceHookForTest;
    private static volatile CleanupTraversalTestHook cleanupTraversalHookForTest;
    private static volatile ExactFileActionTestHook exactFileActionHookForTest;
    private static volatile FinalExactClassificationTestHook finalExactClassificationHookForTest;
    private static volatile DeleteBindingTestHook deleteBindingHookForTest;
    private static volatile TreeRetentionMoveTestHook treeRetentionMoveHookForTest;
    private static volatile OpaqueRetentionMoveTestHook opaqueRetentionMoveHookForTest;
    private static volatile QueueDirectoryTestHook queueDirectoryHookForTest;
    private static volatile RecoverySnapshotIoTestHook recoverySnapshotIoHookForTest;
    private static volatile RecoveryDirectoryTestHook recoveryDirectoryHookForTest;
    private static volatile Boolean stableFileIdentityOverrideForTest;

    private DeconvolutionIO() {}

    static void setDirectoryDurabilityOverrideForTest(Boolean supported) {
        directoryDurabilityOverrideForTest = supported;
    }

    interface DirectoryForceTestHook {
        boolean force(File directory) throws IOException;
    }

    static void setDirectoryForceHookForTest(DirectoryForceTestHook hook) {
        directoryForceHookForTest = hook;
    }

    interface CleanupTraversalTestHook {
        void beforeTraversal(File directory) throws IOException;
    }

    static void setCleanupTraversalHookForTest(CleanupTraversalTestHook hook) {
        cleanupTraversalHookForTest = hook;
    }

    interface ExactFileActionTestHook {
        void afterValidation(File file) throws IOException;
    }

    static void setExactFileActionHookForTest(ExactFileActionTestHook hook) {
        exactFileActionHookForTest = hook;
    }

    interface FinalExactClassificationTestHook {
        void afterFinalValidation(File file) throws IOException;
    }

    static void setFinalExactClassificationHookForTest(
            FinalExactClassificationTestHook hook) {
        finalExactClassificationHookForTest = hook;
    }

    interface DeleteBindingTestHook {
        void beforeAtomicBinding(File file) throws IOException;
    }

    static void setDeleteBindingHookForTest(DeleteBindingTestHook hook) {
        deleteBindingHookForTest = hook;
    }

    interface TreeRetentionMoveTestHook {
        void beforeMove(File source, File destination) throws IOException;
    }

    static void setTreeRetentionMoveHookForTest(TreeRetentionMoveTestHook hook) {
        treeRetentionMoveHookForTest = hook;
    }

    interface OpaqueRetentionMoveTestHook {
        void afterValidationBeforeRetention(File retainedRoot, File retainedFamily)
                throws IOException;
    }

    static void setOpaqueRetentionMoveHookForTest(OpaqueRetentionMoveTestHook hook) {
        opaqueRetentionMoveHookForTest = hook;
    }

    interface QueueDirectoryTestHook {
        void beforeOperation(String operation, File directory) throws IOException;
    }

    static void setQueueDirectoryHookForTest(QueueDirectoryTestHook hook) {
        queueDirectoryHookForTest = hook;
    }

    interface RecoverySnapshotIoTestHook {
        void beforeRead(File source, int requestedBytes) throws IOException;
        void afterWrite(File snapshot, int writtenBytes) throws IOException;
    }

    static void setRecoverySnapshotIoHookForTest(RecoverySnapshotIoTestHook hook) {
        recoverySnapshotIoHookForTest = hook;
    }

    interface RecoveryDirectoryTestHook {
        void beforeOperation(String operation, File directory) throws IOException;
    }

    static void setRecoveryDirectoryHookForTest(RecoveryDirectoryTestHook hook) {
        recoveryDirectoryHookForTest = hook;
    }

    static void setStableFileIdentityOverrideForTest(Boolean available) {
        stableFileIdentityOverrideForTest = available;
    }

    static boolean stableFileIdentityAvailableForTest(File file) throws IOException {
        if (Boolean.TRUE.equals(stableFileIdentityOverrideForTest)) return true;
        if (Boolean.FALSE.equals(stableFileIdentityOverrideForTest)) return false;
        return Files.readAttributes(file.toPath(), BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS).fileKey() != null;
    }

    static File createAuthenticatedRecoverySnapshotForTest(
            File source, long size, String contentHash, File privateDirectory) throws IOException {
        return createAuthenticatedRecoverySnapshot(source,
                new RecoveryFingerprint(size, contentHash), privateDirectory, "test");
    }

    static void publishAuthenticatedRecoveryFileForTest(
            File desiredSource, long desiredSize, String desiredHash, File target,
            File priorSource, long priorSize, String priorHash, File retainedDirectory)
            throws IOException {
        publishAuthenticatedRecoveryFile(desiredSource,
                new RecoveryFingerprint(desiredSize, desiredHash), target, true, priorSource,
                new RecoveryFingerprint(priorSize, priorHash), retainedDirectory, false, "test");
    }

    static void deleteIfExactForTest(File file, long size, String contentHash)
            throws IOException {
        File root = file.getAbsoluteFile().getParentFile();
        File migration = new File(cacheDir(root), ".migration");
        IoUtils.mustMkdirs(migration);
        MigrationTrustAnchor anchor = MigrationTrustAnchor.capture(root, migration);
        String family = sha256Hex(root.getCanonicalPath());
        deleteIfExact(anchor, family, file, new RecoveryFingerprint(size, contentHash));
    }

    static void scavengeExactCleanupForTest(File directory) throws IOException {
        File root = directory.getAbsoluteFile();
        File migration = new File(cacheDir(root), ".migration");
        if (!Files.isDirectory(migration.toPath(), LinkOption.NOFOLLOW_LINKS)) return;
        MigrationTrustAnchor anchor = MigrationTrustAnchor.capture(root, migration);
        processCleanupTickets(anchor, sha256Hex(root.getCanonicalPath()),
                MAX_DEFERRED_CLEANUP_PER_ACCESS);
    }

    static void scavengeDeferredCleanupForTest(File rootDir, ArtifactIdentity current)
            throws IOException {
        File migrationRoot = new File(cacheDir(rootDir), ".migration");
        if (!isContainedPlainDirectory(cacheDir(rootDir), migrationRoot)) {
            throw new IOException("Unsafe deconvolution migration recovery directory: "
                    + migrationRoot);
        }
        scavengeDeferredCleanup(MigrationTrustAnchor.capture(rootDir, migrationRoot), current);
    }

    static File createV4RecoveryBlockerForTest(File rootDir, ArtifactIdentity identity)
            throws IOException {
        File active = v4ActiveRecoveryDirectory(rootDir, requireIdentity(identity));
        IoUtils.mustMkdirs(active);
        if (!isContainedPlainDirectory(cacheDir(rootDir), active)) {
            throw new IOException("Could not create a confined v4 recovery blocker.");
        }
        return active;
    }

    static boolean hasProductionRecoveryBlockerForTest(
            File rootDir, ArtifactIdentity identity) throws IOException {
        return hasProductionRecoveryBlocker(rootDir, requireIdentity(identity));
    }

    public static File deconvOutDir(File rootDir) {
        return layout(rootDir).analysisImagesDeconvolutionDir();
    }

    public static File cacheDir(File rootDir) {
        return new File(layout(rootDir).cacheRoot(), CACHE_SUBDIR);
    }

    private static File v4ActiveRecoveryDirectory(File rootDir, ArtifactIdentity identity) {
        return new File(new File(new File(cacheDir(rootDir), MIGRATION_V4_DIRECTORY),
                requireIdentity(identity).identityHash), MIGRATION_V4_ACTIVE);
    }

    public static File cacheParamsDir(File rootDir, String paramsHash) {
        return new File(cacheDir(rootDir), safeToken(paramsHash));
    }

    public static File deconvFile(File rootDir, String imageBaseName, int channelIndex) {
        File directory = deconvOutDir(rootDir);
        String base = baseName(imageBaseName);
        String suffix = "_C" + channelIndex + ".tif";
        return isArtifactKey(base)
                ? new File(directory, base + suffix)
                : uniqueQualifiedOrLegacy(directory, base, suffix);
    }

    public static File deconvFile(File rootDir, ArtifactIdentity identity, int channelIndex) {
        return deconvFile(rootDir, requireIdentity(identity).artifactKey, channelIndex);
    }

    public static List<File> deconvFileReadCandidates(File rootDir, String imageBaseName, int channelIndex) {
        return Collections.singletonList(deconvFile(rootDir, imageBaseName, channelIndex));
    }

    public static List<File> deconvFileReadCandidates(File rootDir,
                                                       ArtifactIdentity identity,
                                                       int channelIndex,
                                                       String legacyBaseName,
                                                       LegacyBasenamePolicy legacyPolicy,
                                                       int matchingLegacySeries) {
        ArtifactIdentity required = requireIdentity(identity);
        migratePriorWindowsV3Family(rootDir, required);
        List<File> candidates = new ArrayList<File>();
        candidates.add(deconvFile(rootDir, required, channelIndex));
        addValidatedPriorV3Candidate(candidates, rootDir, required,
                ArtifactKind.CHANNEL, null, channelIndex);
        addUniqueLegacyCandidate(candidates,
                rawLegacyDeconvFile(rootDir, legacyBaseName, channelIndex),
                legacyPolicy, matchingLegacySeries);
        return Collections.unmodifiableList(candidates);
    }

    public static File mergedDeconvFile(File rootDir, String imageBaseName) {
        File directory = deconvOutDir(rootDir);
        String base = baseName(imageBaseName);
        return isArtifactKey(base)
                ? new File(directory, base + "_deconv.tif")
                : uniqueQualifiedOrLegacy(directory, base, "_deconv.tif");
    }

    public static File mergedDeconvFile(File rootDir, ArtifactIdentity identity) {
        return mergedDeconvFile(rootDir, requireIdentity(identity).artifactKey);
    }

    public static List<File> mergedDeconvFileReadCandidates(File rootDir, String imageBaseName) {
        return Collections.singletonList(mergedDeconvFile(rootDir, imageBaseName));
    }

    public static List<File> mergedDeconvFileReadCandidates(File rootDir,
                                                             ArtifactIdentity identity,
                                                             String legacyBaseName,
                                                             LegacyBasenamePolicy legacyPolicy,
                                                             int matchingLegacySeries) {
        ArtifactIdentity required = requireIdentity(identity);
        migratePriorWindowsV3Family(rootDir, required);
        List<File> candidates = new ArrayList<File>();
        candidates.add(mergedDeconvFile(rootDir, required));
        addValidatedPriorV3Candidate(candidates, rootDir, required,
                ArtifactKind.MERGED, null, -1);
        addUniqueLegacyCandidate(candidates,
                rawLegacyMergedDeconvFile(rootDir, legacyBaseName),
                legacyPolicy, matchingLegacySeries);
        return Collections.unmodifiableList(candidates);
    }

    public static File cacheFile(File rootDir, String paramsHash, String imageBaseName, int channelIndex) {
        return new File(cacheParamsDir(rootDir, paramsHash), baseName(imageBaseName) + "_C" + channelIndex + ".tif");
    }

    public static File cacheFile(File rootDir, String paramsHash,
                                 ArtifactIdentity identity, int channelIndex) {
        ArtifactIdentity required = requireIdentity(identity);
        // Keep the readable suffix as the leaf while the identity-qualified parent prevents two
        // same-named series from sharing a cache even if a caller accidentally reuses a params hash.
        File identityDir = new File(cacheParamsDir(rootDir, paramsHash), required.artifactKey);
        return new File(identityDir, required.displaySuffix + "_C" + channelIndex + ".tif");
    }

    public static List<File> cacheFileReadCandidates(File rootDir,
                                                     String paramsHash,
                                                     String imageBaseName,
                                                     int channelIndex) {
        return Collections.singletonList(cacheFile(rootDir, paramsHash, imageBaseName, channelIndex));
    }

    public static List<File> cacheFileReadCandidates(File rootDir,
                                                     String paramsHash,
                                                     ArtifactIdentity identity,
                                                     int channelIndex,
                                                     String legacyBaseName,
                                                     LegacyBasenamePolicy legacyPolicy,
                                                     int matchingLegacySeries) {
        ArtifactIdentity required = requireIdentity(identity);
        migratePriorWindowsV3Family(rootDir, required);
        List<File> candidates = new ArrayList<File>();
        candidates.add(cacheFile(rootDir, paramsHash, required, channelIndex));
        addValidatedPriorV3Candidate(candidates, rootDir, required,
                ArtifactKind.CACHE, paramsHash, channelIndex);
        addUniqueLegacyCandidate(candidates,
                rawLegacyCacheFile(rootDir, paramsHash, legacyBaseName, channelIndex),
                legacyPolicy, matchingLegacySeries);
        return Collections.unmodifiableList(candidates);
    }

    public static File detailsFile(File rootDir, String imageBaseName) {
        File directory = deconvOutDir(rootDir);
        String base = baseName(imageBaseName);
        return isArtifactKey(base)
                ? new File(directory, base + "_deconv_details.txt")
                : uniqueQualifiedOrLegacy(directory, base, "_deconv_details.txt");
    }

    public static File detailsFile(File rootDir, ArtifactIdentity identity) {
        return detailsFile(rootDir, requireIdentity(identity).artifactKey);
    }

    /**
     * Content-stamped freshness sidecar written next to the flat mirror outputs. One
     * per image, holding a per-channel {@link DeconvManifest.ChannelEntry}.
     */
    public static File manifestFile(File rootDir, String imageBaseName) {
        File directory = deconvOutDir(rootDir);
        String base = baseName(imageBaseName);
        return isArtifactKey(base)
                ? new File(directory, base + "_deconv.manifest.json")
                : uniqueQualifiedOrLegacy(directory, base, "_deconv.manifest.json");
    }

    public static File manifestFile(File rootDir, ArtifactIdentity identity) {
        return manifestFile(rootDir, requireIdentity(identity).artifactKey);
    }

    /**
     * Basename-only artifacts predate durable source/series identity. They are never consulted by
     * identity-aware readers unless a caller explicitly selects migration and proves that exactly
     * one source series has that basename.
     */
    public enum LegacyBasenamePolicy {
        REJECT,
        MIGRATE_IF_UNIQUE
    }

    /**
     * Immutable, versioned identity for every deconvolution artifact belonging to one source series.
     * The display suffix is deliberately excluded from equality and from the identity digest.
     */
    public static final class ArtifactIdentity {
        public static final int LEGACY_VERSION = 2;
        public static final int VERSION = 3;

        public final int version;
        public final long sourceSize;
        /**
         * Persisted identity token. Version 2 contains the raw content hash. Version 3 contains
         * the source-discriminator hash and raw content hash in a deterministic parseable token,
         * allowing existing manifest serializers to round-trip the stronger identity.
         */
        public final String sourceContentHash;
        /** Raw, verified full-file content hash, never the version-3 persistence envelope. */
        public final String verifiedSourceContentHash;
        /** SHA-256 of the normalized project-relative or standalone source discriminator. */
        public final String sourceIdentityHash;
        public final int sourceSeriesIndex;
        public final String displaySuffix;
        public final String identityHash;
        public final String artifactKey;
        private final String priorWindowsV3SourceContentHash;

        public ArtifactIdentity(int version,
                                long sourceSize,
                                String sourceContentHash,
                                int sourceSeriesIndex,
                                String displaySuffix) {
            this(version, sourceSize, sourceContentHash, sourceSeriesIndex, displaySuffix, null);
        }

        private ArtifactIdentity(int version,
                                 long sourceSize,
                                 String sourceContentHash,
                                 int sourceSeriesIndex,
                                 String displaySuffix,
                                 String priorWindowsV3SourceContentHash) {
            if (version != LEGACY_VERSION && version != VERSION) {
                throw new IllegalArgumentException("Unsupported deconvolution artifact identity version: " + version);
            }
            if (sourceSize < 0L || sourceContentHash == null || sourceContentHash.trim().isEmpty()) {
                throw new IllegalArgumentException("A verified source fingerprint is required.");
            }
            if (sourceSeriesIndex < 0) {
                throw new IllegalArgumentException("Source-local series index must be non-negative.");
            }
            this.version = version;
            this.sourceSize = sourceSize;
            String persisted = sourceContentHash.trim().toLowerCase(Locale.ROOT);
            String[] v3Parts = version == VERSION ? parseV3SourceToken(persisted) : null;
            this.sourceContentHash = persisted;
            this.sourceIdentityHash = v3Parts == null ? "" : v3Parts[0];
            this.verifiedSourceContentHash = v3Parts == null ? persisted : v3Parts[1];
            this.sourceSeriesIndex = sourceSeriesIndex;
            this.displaySuffix = displaySuffix(displaySuffix);
            this.identityHash = sha256Hex(canonicalIdentity(version, sourceSize,
                    this.verifiedSourceContentHash, this.sourceIdentityHash, sourceSeriesIndex));
            this.artifactKey = artifactKeyPrefix(version) + identityHash + "-s" + sourceSeriesIndex
                    + "-" + this.displaySuffix;
            this.priorWindowsV3SourceContentHash = priorWindowsV3SourceContentHash;
        }

        public ArtifactIdentity(int version,
                                long sourceSize,
                                String sourceContentHash,
                                String sourceIdentity,
                                int sourceSeriesIndex,
                                String displaySuffix) {
            this(version, sourceSize, version == VERSION
                            ? v3SourceToken(sourceIdentity, sourceContentHash) : sourceContentHash,
                    sourceSeriesIndex, displaySuffix, version == VERSION
                            ? priorWindowsV3SourceToken(sourceIdentity, sourceContentHash) : null);
            if (version != VERSION) {
                throw new IllegalArgumentException(
                        "A source discriminator is supported only by identity version " + VERSION + ".");
            }
        }

        public static ArtifactIdentity of(File source,
                                          int sourceSeriesIndex,
                                          String displayName) throws IOException {
            DeconvManifest.SourceFingerprint fingerprint = DeconvManifest.SourceFingerprint.of(source);
            return of(null, source, fingerprint, sourceSeriesIndex, displayName);
        }

        /**
         * Build the current identity. Sources within {@code projectRoot} use their resolved
         * on-disk relative spelling so moving the complete project between operating systems
         * preserves identity. External/standalone sources use their resolved absolute key, so
         * byte-identical copies remain distinct.
         */
        public static ArtifactIdentity of(File projectRoot,
                                          File sourceFile,
                                          DeconvManifest.SourceFingerprint source,
                                          int sourceSeriesIndex,
                                          String displayName) throws IOException {
            if (source == null) {
                throw new IllegalArgumentException("A verified source fingerprint is required.");
            }
            String sourceIdentity = canonicalSourceIdentity(projectRoot, sourceFile);
            return new ArtifactIdentity(VERSION, source.size, source.contentHash,
                    sourceIdentity, sourceSeriesIndex, displayName);
        }

        /** Build a deterministic version-2 legacy identity with no container discriminator. */
        public static ArtifactIdentity of(DeconvManifest.SourceFingerprint source,
                                          int sourceSeriesIndex,
                                          String displayName) {
            if (source == null) {
                throw new IllegalArgumentException("A verified source fingerprint is required.");
            }
            return new ArtifactIdentity(LEGACY_VERSION, source.size, source.contentHash,
                    sourceSeriesIndex, displayName);
        }

        public boolean isPublishable() {
            return version == LEGACY_VERSION
                    || (version == VERSION && isSha256(sourceIdentityHash)
                    && isSha256(verifiedSourceContentHash));
        }

        public boolean matches(ArtifactIdentity other) {
            return other != null
                    && version == other.version
                    && sourceSize == other.sourceSize
                    && sourceSeriesIndex == other.sourceSeriesIndex
                    && sourceContentHash.equals(other.sourceContentHash)
                    && identityHash.equals(other.identityHash);
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof ArtifactIdentity && matches((ArtifactIdentity) other);
        }

        @Override
        public int hashCode() {
            int result = version;
            result = 31 * result + (int) (sourceSize ^ (sourceSize >>> 32));
            result = 31 * result + sourceContentHash.hashCode();
            result = 31 * result + sourceSeriesIndex;
            return result;
        }

        ArtifactIdentity priorWindowsV3Identity() {
            if (version != VERSION || priorWindowsV3SourceContentHash == null) {
                return null;
            }
            ArtifactIdentity prior = new ArtifactIdentity(VERSION, sourceSize,
                    priorWindowsV3SourceContentHash, sourceSeriesIndex, displaySuffix);
            return artifactKey.equals(prior.artifactKey) ? null : prior;
        }

        String familyLockToken() {
            return sha256Hex("deconv-family-lock-v1\n" + sourceSize + "\n"
                    + verifiedSourceContentHash + "\n" + sourceSeriesIndex);
        }
    }

    public static File firstExistingFile(List<File> candidates) {
        if (candidates == null) return null;
        for (int i = 0; i < candidates.size(); i++) {
            File candidate = candidates.get(i);
            if (candidate != null && candidate.isFile()) {
                return candidate;
            }
        }
        return null;
    }

    public static File firstFreshFile(File sourceFile, List<File> candidates) {
        if (candidates == null) return null;
        for (int i = 0; i < candidates.size(); i++) {
            File candidate = candidates.get(i);
            if (isCacheFresh(sourceFile, candidate)) {
                return candidate;
            }
        }
        return null;
    }

    public static boolean isCacheFresh(File sourceFile, File cacheFile) {
        return sourceFile != null
                && cacheFile != null
                && cacheFile.isFile()
                && cacheFile.lastModified() >= sourceFile.lastModified();
    }

    public static String paramsHash(Map<String, String> params) {
        TreeMap<String, String> sorted = new TreeMap<String, String>();
        if (params != null) {
            sorted.putAll(params);
        }

        StringBuilder canonical = new StringBuilder();
        for (Map.Entry<String, String> entry : sorted.entrySet()) {
            if (canonical.length() > 0) canonical.append('\n');
            canonical.append(entry.getKey()).append('=').append(entry.getValue() == null ? "" : entry.getValue());
        }

        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] bytes = digest.digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            return encodeBase32(bytes, 10);
        } catch (Exception e) {
            throw new IllegalStateException("Could not compute deconvolution parameter hash.", e);
        }
    }

    public static String formatDouble(double value) {
        return String.format(Locale.ROOT, "%.6f", value);
    }

    private static String encodeBase32(byte[] bytes, int length) {
        StringBuilder sb = new StringBuilder(length);
        int buffer = 0;
        int bitsLeft = 0;
        int i = 0;
        while (sb.length() < length) {
            if (bitsLeft < 5) {
                if (i < bytes.length) {
                    buffer = (buffer << 8) | (bytes[i++] & 0xff);
                    bitsLeft += 8;
                } else {
                    buffer <<= (5 - bitsLeft);
                    bitsLeft = 5;
                }
            }
            int index = (buffer >> (bitsLeft - 5)) & 31;
            bitsLeft -= 5;
            sb.append(BASE32[index]);
        }
        return sb.toString();
    }

    private static ArtifactIdentity requireIdentity(ArtifactIdentity identity) {
        if (identity == null) {
            throw new IllegalArgumentException("Deconvolution artifact identity must not be null.");
        }
        if (!identity.isPublishable()) {
            throw new IllegalArgumentException(
                    "Deconvolution artifact identity is missing a valid source/container discriminator.");
        }
        return identity;
    }

    private enum ArtifactKind {
        CHANNEL,
        MERGED,
        CACHE
    }

    private static void addValidatedPriorV3Candidate(List<File> candidates,
                                                     File rootDir,
                                                     ArtifactIdentity current,
                                                     ArtifactKind kind,
                                                     String paramsHash,
                                                     int channelIndex) {
        ArtifactIdentity prior = validatedPriorWindowsV3Identity(rootDir, current);
        if (prior == null) return;
        DeconvManifest manifest = DeconvManifest.load(manifestFile(rootDir, prior));
        DeconvManifest.SourceFingerprint source = sourceFingerprint(prior);
        File candidate;
        if (kind == ArtifactKind.CHANNEL) {
            if (!manifest.isChannelFresh(channelIndex, null, source, prior)) return;
            candidate = deconvFile(rootDir, prior, channelIndex);
        } else if (kind == ArtifactKind.MERGED) {
            if (!mergedSourceMatches(manifest, source)) return;
            candidate = mergedDeconvFile(rootDir, prior);
        } else {
            if (paramsHash == null
                    || !manifest.isChannelFresh(channelIndex, paramsHash, source, prior)) return;
            candidate = cacheFile(rootDir, paramsHash, prior, channelIndex);
        }
        if (candidate.isFile() && !candidates.contains(candidate)) {
            if (currentCandidateIsValidated(rootDir, current, kind, paramsHash, channelIndex)) {
                candidates.add(candidate);
            } else {
                // A failed/interrupted promotion may leave an orphan exact-key file. Never let
                // that unvalidated file outrank the still-valid prior generation.
                candidates.add(0, candidate);
            }
        }
    }

    private static boolean currentCandidateIsValidated(File rootDir,
                                                       ArtifactIdentity current,
                                                       ArtifactKind kind,
                                                       String paramsHash,
                                                       int channelIndex) {
        DeconvManifest manifest = DeconvManifest.load(manifestFile(rootDir, current));
        if (!manifest.matchesArtifact(current)) return false;
        DeconvManifest.SourceFingerprint source = sourceFingerprint(current);
        if (kind == ArtifactKind.MERGED) return mergedSourceMatches(manifest, source);
        return manifest.isChannelFresh(channelIndex,
                kind == ArtifactKind.CACHE ? paramsHash : null, source, current);
    }

    private static DeconvManifest.SourceFingerprint sourceFingerprint(ArtifactIdentity identity) {
        return new DeconvManifest.SourceFingerprint(identity.sourceSize, -1L,
                identity.verifiedSourceContentHash);
    }

    private static boolean mergedSourceMatches(DeconvManifest manifest,
                                               DeconvManifest.SourceFingerprint source) {
        if (manifest == null || manifest.merged() == null
                || manifest.merged().source == null
                || !manifest.merged().source.matches(source)
                || !manifest.sourceMatchesAll(source)
                || manifest.merged().channelParamsHashes.isEmpty()) {
            return false;
        }
        for (Map.Entry<Integer, String> mergedChannel
                : manifest.merged().channelParamsHashes.entrySet()) {
            DeconvManifest.ChannelEntry channel = manifest.channel(mergedChannel.getKey().intValue());
            if (channel == null || channel.paramsHash == null
                    || !channel.paramsHash.equals(mergedChannel.getValue())) return false;
        }
        return true;
    }

    private static ArtifactIdentity validatedPriorWindowsV3Identity(File rootDir,
                                                                     ArtifactIdentity current) {
        if (!isWindows() || current == null) return null;
        ArtifactIdentity prior = current.priorWindowsV3Identity();
        if (prior == null) return null;
        DeconvManifest manifest = DeconvManifest.load(manifestFile(rootDir, prior));
        return manifest.matchesArtifact(prior) ? prior : null;
    }

    private static boolean hasValidatedCurrentPublication(
            File rootDir,
            ArtifactIdentity current,
            DeconvManifest manifest,
            DeconvManifest.SourceFingerprint source) {
        for (Map.Entry<Integer, DeconvManifest.ChannelEntry> entry : manifest.channels().entrySet()) {
            if (entry.getKey() != null
                    && manifest.isChannelFresh(entry.getKey().intValue(), null, source, current)
                    && deconvFile(rootDir, current, entry.getKey().intValue()).isFile()) {
                return true;
            }
        }
        return mergedDeconvFile(rootDir, current).isFile()
                && mergedSourceMatches(manifest, source);
    }

    private static boolean mergedRecordFits(
            DeconvManifest.MergedRecord record,
            DeconvManifest manifest,
            DeconvManifest.SourceFingerprint source,
            ArtifactIdentity identity) {
        return record != null && !record.channelParamsHashes.isEmpty()
                && manifest.withMerged(record).isMergedFresh(source,
                        record.channelParamsHashes.keySet(), null, identity);
    }

    private static void migratePriorWindowsV3Family(File rootDir, ArtifactIdentity current) {
        MigrationResult result = migratePriorWindowsV3Family(rootDir, current, NO_MIGRATION_FAULTS);
        if (!result.safe || result.failure != null) {
            throw new IllegalStateException("Deconvolution v3 migration did not complete cleanly.",
                    result.failure);
        }
    }

    /**
     * Acquire the family lock after classifying retained recovery state. Prior-v3 promotion is
     * attempted only when a safely bound transaction creator is available; otherwise the
     * independently validated prior generation remains readable. Callers must keep the returned
     * handle open through candidate selection, manifest validation, and pixel opening/publication.
     */
    public static DeconvolutionFamilyLock.Handle lockFamilyForAccess(
            File rootDir, ArtifactIdentity current) throws IOException {
        ArtifactIdentity required = requireIdentity(current);
        DeconvolutionFamilyLock.Handle handle = DeconvolutionFamilyLock.acquire(rootDir, required);
        boolean success = false;
        try {
            boolean quarantinedRecovery = scavengeAbandonedTransactions(rootDir, required);
            ArtifactIdentity prior = quarantinedRecovery
                    ? null : validatedPriorWindowsV3Identity(rootDir, required);
            if (prior != null && !handleBoundMigrationCreationAvailable()) {
                // Java 8 cannot create and bind a new child directory through
                // SecureDirectoryStream.  Keep the readable prior generation and skip automatic
                // promotion rather than opening a path-bound legacy transaction.
                prior = null;
            }
            if (prior != null) {
                MigrationResult result = new MigrationTransaction(rootDir, required, prior,
                        NO_MIGRATION_FAULTS).execute();
                if (!result.safe || result.failure != null) {
                    throw new IOException("Deconvolution family migration did not complete cleanly.",
                            result.failure);
                }
            }
            success = true;
            return handle;
        } finally {
            if (!success) handle.close();
        }
    }

    static MigrationResult migratePriorWindowsV3FamilyForTest(File rootDir,
                                                               ArtifactIdentity current,
                                                               MigrationFaultInjector faults) {
        return migratePriorWindowsV3Family(rootDir, current,
                faults == null ? NO_MIGRATION_FAULTS : faults);
    }

    private static MigrationResult migratePriorWindowsV3Family(File rootDir,
                                                                ArtifactIdentity current,
                                                                MigrationFaultInjector faults) {
        try (DeconvolutionFamilyLock.Handle ignored =
                     DeconvolutionFamilyLock.acquire(rootDir, current)) {
            if (scavengeAbandonedTransactions(rootDir, current)) {
                // A malformed transaction was quarantined. Preserve the one proven readable
                // generation and defer all promotion until an operator resolves the quarantine.
                return MigrationResult.noOp();
            }
            // Re-evaluate only after both the in-process and OS locks are held.
            ArtifactIdentity prior = validatedPriorWindowsV3Identity(rootDir, current);
            if (prior == null) return MigrationResult.noOp();
            if (!handleBoundMigrationCreationAvailable()) return MigrationResult.noOp();
            MigrationTransaction transaction = new MigrationTransaction(
                    rootDir, current, prior, faults);
            return transaction.execute();
        } catch (IOException failure) {
            return MigrationResult.failed(failure, false);
        }
    }

    private static boolean handleBoundMigrationCreationAvailable() {
        // Explicit test mode exercises the legacy state machine on controlled temporary trees.
        // Java 8 production has no handle-relative mkdir primitive, so automatic migration is
        // disabled until the v4 exact-slot lifecycle can be created by a stronger filesystem API.
        return testOnlyPathOperationsAllowed();
    }

    enum MigrationFaultPoint {
        AFTER_TRANSACTION_CREATED,
        BEFORE_STAGE_PUBLICATION_COPY,
        BEFORE_STAGE_PUBLICATION_FORCE,
        BEFORE_STAGED_MANIFEST_WRITE,
        BEFORE_JOURNAL_TEMP_FORCE,
        BEFORE_JOURNAL_COMMIT,
        AFTER_JOURNAL_RENAME_BEFORE_DIRECTORY_FORCE,
        AFTER_JOURNAL,
        AFTER_STAGE,
        AFTER_FIRST_CURRENT_PUBLISH,
        AFTER_MANIFEST_COMMIT_BEFORE_ACK,
        AFTER_MANIFEST_PUBLISH,
        AFTER_CURRENT_GENERATION_DURABLE,
        AFTER_FIRST_PRIOR_RETIRE,
        BEFORE_UNCOMMITTED_STAGING_DELETE,
        AFTER_DEFERRED_CLEANUP_MARKER_DELETE,
        BEFORE_TRANSACTION_CLEANUP,
        BEFORE_JOURNAL_CLEANUP
    }

    interface MigrationFaultInjector {
        void checkpoint(MigrationFaultPoint point) throws IOException;
    }

    private static final MigrationFaultInjector NO_MIGRATION_FAULTS =
            new MigrationFaultInjector() {
                @Override
                public void checkpoint(MigrationFaultPoint point) {}
            };

    static final class MigrationResult {
        final boolean migrated;
        final boolean safe;
        final IOException failure;

        private MigrationResult(boolean migrated, boolean safe, IOException failure) {
            this.migrated = migrated;
            this.safe = safe;
            this.failure = failure;
        }

        static MigrationResult noOp() {
            return new MigrationResult(false, true, null);
        }

        static MigrationResult migrated() {
            return new MigrationResult(true, true, null);
        }

        static MigrationResult failed(IOException failure, boolean safe) {
            return new MigrationResult(false, safe, failure);
        }
    }

    private static final class MigrationTransaction {
        private final File rootDir;
        private final ArtifactIdentity current;
        private final ArtifactIdentity prior;
        private final MigrationFaultInjector faults;
        private final File migrationRoot;
        private final List<MigrationFile> publications = new ArrayList<MigrationFile>();
        private final List<RetainedFile> retained = new ArrayList<RetainedFile>();
        private final List<RetiredFile> retired = new ArrayList<RetiredFile>();
        private final Set<String> createdDirectoryPaths = new HashSet<String>();
        private final Set<String> ancestryBoundaryPaths = new HashSet<String>();
        private File transactionDir;
        private File stagedManifest;
        private File currentManifestFile;
        private File manifestBackup;
        private File journalFile;
        private RecoveryFingerprint journalFingerprint;
        private RecoveryFingerprint desiredManifestFingerprint;
        private RecoveryFingerprint manifestBackupFingerprint;
        private MigrationTrustAnchor cleanupAnchor;
        private AnchoredDirectory transactionCreationAnchor;
        private boolean manifestExisted;
        private boolean journalCommitAttempted;
        private boolean livePublicationAttempted;
        private boolean manifestCommitAttempted;
        private boolean manifestPublished;
        private boolean directoryDurabilitySupported = true;
        private DeconvManifest migratedManifest;

        MigrationTransaction(File rootDir, ArtifactIdentity current,
                             ArtifactIdentity prior, MigrationFaultInjector faults) {
            this.rootDir = rootDir;
            this.current = current;
            this.prior = prior;
            this.faults = faults;
            this.migrationRoot = new File(cacheDir(rootDir), ".migration").getAbsoluteFile();
        }

        MigrationResult execute() {
            try {
                prepare();
            } catch (IOException preparationFailure) {
                if (isDiscardableUncommittedStaging()) {
                    IOException cleanupFailure = cleanupUncommittedStaging();
                    if (cleanupFailure != null) preparationFailure.addSuppressed(cleanupFailure);
                    return MigrationResult.failed(preparationFailure, true);
                }
                return failAfterAuthorityOrLiveMutation(preparationFailure);
            }
            try {
                publishCurrentFamily();
            } catch (IOException publicationFailure) {
                return failAfterAuthorityOrLiveMutation(publicationFailure);
            }
            try {
                validateCompleteCurrentGenerationForRetirement();
                boolean mayRetirePrior = forceCurrentGenerationForRetirement();
                faults.checkpoint(MigrationFaultPoint.AFTER_CURRENT_GENERATION_DURABLE);
                if (!mayRetirePrior) {
                    // COMPLETE_UNDURABLE: the journal, desired bytes, backups, and intact prior
                    // generation are the crash authority. Never erase them merely because current
                    // file contents happen to match while directory entries cannot be forced.
                    return MigrationResult.migrated();
                }
            } catch (IOException incompleteCurrent) {
                boolean restored = rollbackCurrentFamily(incompleteCurrent);
                IOException quarantined = quarantineAuthoritativeTransaction(rootDir, current,
                        transactionDir, "current generation failed pre-retirement validation",
                        incompleteCurrent);
                return MigrationResult.failed(quarantined, restored);
            }
            try {
                retirePriorFamily();
                IOException cleanupFailure = cleanupTransaction();
                return cleanupFailure == null
                        ? MigrationResult.migrated()
                        : MigrationResult.failed(cleanupFailure, manifestPublished);
            } catch (IOException retirementFailure) {
                boolean restored = rollbackPriorRetirement(retirementFailure);
                if (restored && directoryDurabilitySupported) {
                    IOException cleanupFailure = cleanupTransaction();
                    if (cleanupFailure != null) retirementFailure.addSuppressed(cleanupFailure);
                }
                // Even if prior restoration failed, the current manifest and family are complete.
                return MigrationResult.failed(retirementFailure, manifestPublished);
            }
        }

        private boolean isDiscardableUncommittedStaging() {
            return !journalCommitAttempted && journalFile == null
                    && !livePublicationAttempted && !manifestCommitAttempted;
        }

        private IOException cleanupUncommittedStaging() {
            try {
                // Before the journal commit or any live-family replacement, every byte belongs
                // solely to this transaction. Persist that classification, then move the intact
                // tree into the intrinsically non-authoritative cleanup namespace before deleting
                // any child. A crash after that rename needs no surviving marker to classify it.
                if (!testOnlyPathOperationsAllowed()) {
                    // Java 8 cannot bind marker publication or conditionally rename the validated
                    // transaction leaf.  A replacement could otherwise receive the marker or be
                    // rebound into the cleanup queue.  Keep the whole source transaction intact.
                    throw new RetryableCleanupException(
                            "Pre-authority transaction remains at its confined source; "
                                    + "handle-bound marker/rebinding is unavailable.");
                }
                writeDeferredCleanupMarker(transactionDir);
                transactionDir = relocateDeferredCleanup(transactionDir, cleanupAnchor);
                faults.checkpoint(MigrationFaultPoint.BEFORE_UNCOMMITTED_STAGING_DELETE);
                deleteRecoveryPath(cleanupAnchor,
                        new File(transactionDir, DEFERRED_CLEANUP_MARKER));
                faults.checkpoint(MigrationFaultPoint.AFTER_DEFERRED_CLEANUP_MARKER_DELETE);
                deleteTree(cleanupAnchor, transactionDir);
                return null;
            } catch (IOException failure) {
                return failure;
            }
        }

        private MigrationResult failAfterAuthorityOrLiveMutation(IOException failure) {
            boolean safe = rollbackCurrentFamily(failure);
            if (!safe) {
                failure = quarantineAuthoritativeTransaction(rootDir, current,
                        transactionDir, "journaled migration bytes or publication target changed",
                        failure);
            } else if (directoryDurabilitySupported || journalFile == null) {
                IOException cleanupFailure = cleanupTransaction();
                if (cleanupFailure != null) failure.addSuppressed(cleanupFailure);
            }
            return MigrationResult.failed(failure, safe);
        }

        private void prepare() throws IOException {
            mustMkdirsTracked(migrationRoot);
            cleanupAnchor = MigrationTrustAnchor.capture(rootDir, migrationRoot);
            transactionDir = new File(migrationRoot,
                    current.familyLockToken() + "-" + UUID.randomUUID().toString());
            mustMkdirsTracked(transactionDir);
            Path createdTransaction = transactionDir.toPath().toAbsolutePath().normalize();
            transactionCreationAnchor = AnchoredDirectory.capturePlain(createdTransaction,
                    createdTransaction.toRealPath());
            faults.checkpoint(MigrationFaultPoint.AFTER_TRANSACTION_CREATED);

            DeconvManifest priorManifest = DeconvManifest.load(manifestFile(rootDir, prior));
            if (!priorManifest.matchesArtifact(prior)) {
                throw new IOException("Prior deconvolution manifest changed before migration.");
            }
            currentManifestFile = manifestFile(rootDir, current);
            DeconvManifest currentManifest = DeconvManifest.load(currentManifestFile);
            boolean currentValid = currentManifest.matchesArtifact(current);
            migratedManifest = currentValid
                    ? currentManifest : DeconvManifest.forArtifact(current);
            DeconvManifest.SourceFingerprint priorSource = sourceFingerprint(prior);
            DeconvManifest.SourceFingerprint currentSource = sourceFingerprint(current);
            boolean currentGenerationValid = currentValid
                    && hasValidatedCurrentPublication(rootDir, current, currentManifest, currentSource);

            for (Map.Entry<Integer, DeconvManifest.ChannelEntry> entry
                    : priorManifest.channels().entrySet()) {
                if (entry.getKey() == null || entry.getValue() == null) continue;
                int channelIndex = entry.getKey().intValue();
                if (!priorManifest.isChannelFresh(channelIndex, null, priorSource, prior)) continue;
                File source = deconvFile(rootDir, prior, channelIndex);
                File target = deconvFile(rootDir, current, channelIndex);
                boolean currentWins = currentValid && target.isFile()
                        && currentManifest.isChannelFresh(channelIndex, null,
                                currentSource, current);
                if (!currentWins && source.isFile()) {
                    addPublication(source, target);
                    migratedManifest = migratedManifest.withChannel(channelIndex, entry.getValue());
                }
            }

            File priorMerged = mergedDeconvFile(rootDir, prior);
            File currentMerged = mergedDeconvFile(rootDir, current);
            DeconvManifest.MergedRecord currentMergedRecord = currentManifest.merged();
            DeconvManifest.MergedRecord priorMergedRecord = priorManifest.merged();
            boolean currentMergedWins = currentValid && currentMerged.isFile()
                    && mergedSourceMatches(currentManifest, currentSource)
                    && mergedRecordFits(currentMergedRecord, migratedManifest,
                            currentSource, current);
            boolean priorMergedWins = !currentMergedWins && priorMerged.isFile()
                    && mergedSourceMatches(priorManifest, priorSource)
                    && mergedRecordFits(priorMergedRecord, migratedManifest,
                            currentSource, current);
            if (priorMergedWins) {
                addPublication(priorMerged, currentMerged);
                migratedManifest = migratedManifest.withMerged(priorMergedRecord);
            } else if (currentMergedWins) {
                migratedManifest = migratedManifest.withMerged(currentMergedRecord);
            } else {
                // Keep any on-disk current TIFF as an unvouched orphan; consumers will compose from
                // the validated per-channel files rather than accept a mixed-generation merge.
                migratedManifest = migratedManifest.withMerged(null);
            }

            File priorDetails = detailsFile(rootDir, prior);
            File currentDetails = detailsFile(rootDir, current);
            if (priorDetails.isFile() && !(currentGenerationValid && currentDetails.isFile())) {
                addPublication(priorDetails, currentDetails);
            }

            planRetainedCurrentArtifacts();
            stagePublications();
            stagedManifest = new File(transactionDir, "desired-manifest.json");
            faults.checkpoint(MigrationFaultPoint.BEFORE_STAGED_MANIFEST_WRITE);
            Files.write(stagedManifest.toPath(), migratedManifest.toJson()
                    .getBytes(StandardCharsets.UTF_8));
            forceRegularFile(stagedManifest);
            desiredManifestFingerprint = RecoveryFingerprint.ofManifest(stagedManifest,
                    "staged desired manifest");
            DeconvManifest staged = DeconvManifest.readBounded(stagedManifest);
            if (!staged.matchesArtifact(current)) {
                throw new IOException("Staged migrated manifest failed identity validation.");
            }
            backupManifest();
            planPriorRetirement();
            writeRecoveryJournal();
            faults.checkpoint(MigrationFaultPoint.AFTER_JOURNAL);
            faults.checkpoint(MigrationFaultPoint.AFTER_STAGE);
        }

        private void addPublication(File source, File target) {
            publications.add(new MigrationFile(publicationRole(rootDir, current, target),
                    source, target));
        }

        private void addRetained(File target) {
            if (target == null) return;
            for (RetainedFile item : retained) {
                if (item.target.equals(target)) return;
            }
            for (MigrationFile operation : publications) {
                if (operation.target.equals(target)) return;
            }
            retained.add(new RetainedFile(publicationRole(rootDir, current, target), target));
        }

        private void planRetainedCurrentArtifacts() {
            for (Integer channel : migratedManifest.channels().keySet()) {
                if (channel != null) addRetained(deconvFile(rootDir, current, channel.intValue()));
            }
            if (migratedManifest.merged() != null) {
                addRetained(mergedDeconvFile(rootDir, current));
            }
            File details = detailsFile(rootDir, current);
            if (details.isFile()) addRetained(details);
        }

        private void stagePublications() throws IOException {
            File desiredDir = new File(transactionDir, "desired");
            File backupDir = new File(transactionDir, "backup");
            mustMkdirsTracked(desiredDir);
            mustMkdirsTracked(backupDir);
            for (int i = 0; i < publications.size(); i++) {
                MigrationFile operation = publications.get(i);
                operation.staged = new File(desiredDir, i + ".bin");
                faults.checkpoint(MigrationFaultPoint.BEFORE_STAGE_PUBLICATION_COPY);
                Files.copy(operation.source.toPath(), operation.staged.toPath(),
                        StandardCopyOption.REPLACE_EXISTING);
                verifySameContent(operation.source, operation.staged);
                faults.checkpoint(MigrationFaultPoint.BEFORE_STAGE_PUBLICATION_FORCE);
                forceRegularFile(operation.staged);
                operation.desiredFingerprint = RecoveryFingerprint.of(operation.staged);
                if (operation.target.isFile()) {
                    operation.targetExisted = true;
                    operation.backup = new File(backupDir, i + ".bin");
                    Files.copy(operation.target.toPath(), operation.backup.toPath(),
                            StandardCopyOption.REPLACE_EXISTING);
                    verifySameContent(operation.target, operation.backup);
                    forceRegularFile(operation.backup);
                    operation.backupFingerprint = RecoveryFingerprint.of(operation.backup);
                } else if (operation.target.exists()) {
                    throw new IOException("Migration target is not a regular file: " + operation.target);
                }
            }
        }

        private void backupManifest() throws IOException {
            manifestExisted = currentManifestFile.isFile();
            if (manifestExisted) {
                requireBoundedManifestFile(currentManifestFile, "current manifest backup source");
                manifestBackup = new File(transactionDir, "manifest-backup.json");
                Files.copy(currentManifestFile.toPath(), manifestBackup.toPath(),
                        StandardCopyOption.REPLACE_EXISTING);
                verifySameContent(currentManifestFile, manifestBackup);
                forceRegularFile(manifestBackup);
                manifestBackupFingerprint = RecoveryFingerprint.ofManifest(manifestBackup,
                        "staged manifest backup");
            } else if (currentManifestFile.exists()) {
                throw new IOException("Current manifest path is not a regular file.");
            }
        }

        private void publishCurrentFamily() throws IOException {
            validatePreJournalSnapshot();
            for (int i = 0; i < publications.size(); i++) {
                MigrationFile operation = publications.get(i);
                mustMkdirsTracked(operation.target.getParentFile());
                directoryDurabilitySupported &= forceCreatedDirectoryAncestry();
                File publish = File.createTempFile(".deconv-v3-publish-", ".tmp",
                        operation.target.getParentFile());
                boolean published = false;
                try {
                    Files.copy(operation.staged.toPath(), publish.toPath(),
                            StandardCopyOption.REPLACE_EXISTING);
                    requireExactFingerprint(publish, operation.desiredFingerprint,
                            operation.role + " publication temp");
                    forceRegularFile(publish);
                    livePublicationAttempted = true;
                    // A replacement may report failure after changing the directory entry. Mark
                    // the operation before the call so rollback classifies either exact outcome.
                    operation.published = true;
                    IoUtils.moveReplacing(publish.toPath(), operation.target.toPath());
                    published = true;
                    directoryDurabilitySupported &= forceFileAndParent(operation.target);
                    if (i == 0) {
                        faults.checkpoint(MigrationFaultPoint.AFTER_FIRST_CURRENT_PUBLISH);
                    }
                    requireExactFingerprint(operation.target, operation.desiredFingerprint,
                            operation.role + " publication target");
                } finally {
                    if (!published) Files.deleteIfExists(publish.toPath());
                }
            }
            mustMkdirsTracked(currentManifestFile.getParentFile());
            directoryDurabilitySupported &= forceCreatedDirectoryAncestry();
            // Keep desired-manifest.json in the transaction as the durable recovery authority.
            File manifestPublish = new File(transactionDir, "manifest-publish.json");
            Files.copy(stagedManifest.toPath(), manifestPublish.toPath(),
                    StandardCopyOption.REPLACE_EXISTING);
            requireExactFingerprint(manifestPublish, desiredManifestFingerprint,
                    "manifest publication temp");
            forceRegularFile(manifestPublish);
            manifestCommitAttempted = true;
            IoUtils.commitReplacingSmallFile(manifestPublish.toPath(), currentManifestFile.toPath());
            directoryDurabilitySupported &= forceFileAndParent(currentManifestFile);
            faults.checkpoint(MigrationFaultPoint.AFTER_MANIFEST_COMMIT_BEFORE_ACK);
            requireExactFingerprint(currentManifestFile, desiredManifestFingerprint,
                    "manifest publication target");
            if (!DeconvManifest.load(currentManifestFile).matchesArtifact(current)) {
                throw new IOException("Published migration manifest has the wrong artifact identity.");
            }
            manifestPublished = true;
            faults.checkpoint(MigrationFaultPoint.AFTER_MANIFEST_PUBLISH);
        }

        private boolean rollbackCurrentFamily(IOException primary) {
            try {
                validateRollbackInputs();
            } catch (IOException integrityFailure) {
                primary.addSuppressed(integrityFailure);
                return false;
            }
            // A manifest commit can throw after replacing its target. Once attempted, always restore
            // the prior manifest and validate bytes; the return/throw outcome is not proof of state.
            if (manifestCommitAttempted) {
                try {
                    if (manifestExisted) {
                        File restore = new File(transactionDir, "manifest-restore.json");
                        Files.copy(manifestBackup.toPath(), restore.toPath(),
                                StandardCopyOption.REPLACE_EXISTING);
                        requireExactFingerprint(restore, manifestBackupFingerprint,
                                "manifest rollback temp");
                        forceRegularFile(restore);
                        IoUtils.commitReplacingSmallFile(restore.toPath(), currentManifestFile.toPath());
                        directoryDurabilitySupported &= forceFileAndParent(currentManifestFile);
                        requireExactFingerprint(currentManifestFile, manifestBackupFingerprint,
                                "manifest rollback target");
                    } else {
                        deleteAndForceParent(currentManifestFile);
                    }
                } catch (IOException rollbackFailure) {
                    primary.addSuppressed(rollbackFailure);
                }
            }
            for (int i = publications.size() - 1; i >= 0; i--) {
                MigrationFile operation = publications.get(i);
                if (!operation.published) continue;
                try {
                    if (operation.targetExisted) {
                        File restore = File.createTempFile(".deconv-v3-restore-", ".tmp",
                                operation.target.getParentFile());
                        boolean restored = false;
                        try {
                            Files.copy(operation.backup.toPath(), restore.toPath(),
                                    StandardCopyOption.REPLACE_EXISTING);
                            requireExactFingerprint(restore, operation.backupFingerprint,
                                    operation.role + " rollback temp");
                            forceRegularFile(restore);
                            IoUtils.moveReplacing(restore.toPath(), operation.target.toPath());
                            directoryDurabilitySupported &= forceFileAndParent(operation.target);
                            requireExactFingerprint(operation.target, operation.backupFingerprint,
                                    operation.role + " rollback target");
                            restored = true;
                        } finally {
                            if (!restored) Files.deleteIfExists(restore.toPath());
                        }
                    } else {
                        deleteAndForceParent(operation.target);
                    }
                } catch (IOException rollbackFailure) {
                    primary.addSuppressed(rollbackFailure);
                }
            }
            if (preTransactionCurrentFamilyRestored()) return true;

            // Partial/ambiguous rollback: recover forward, manifest-last, and prove the exact desired
            // generation. Anything else is unsafe and the journal remains for the next locked access.
            try {
                recoverCompleteCurrentFamily();
                if (desiredCurrentFamilyComplete()) return true;
                primary.addSuppressed(new IOException(
                        "Forward deconvolution recovery did not match the staged generation."));
                return false;
            } catch (IOException recoveryFailure) {
                primary.addSuppressed(recoveryFailure);
                return false;
            }
        }

        private void validateRollbackInputs() throws IOException {
            validateImmutableRecoveryArtifacts();
            if (manifestCommitAttempted
                    && !matchesOneOf(currentManifestFile, desiredManifestFingerprint,
                            manifestExisted ? manifestBackupFingerprint : null,
                            !manifestExisted)) {
                throw new IOException("Manifest target changed outside the migration transaction.");
            }
            for (MigrationFile operation : publications) {
                if (!operation.published) continue;
                if (!matchesOneOf(operation.target, operation.desiredFingerprint,
                        operation.targetExisted ? operation.backupFingerprint : null,
                        !operation.targetExisted)) {
                    throw new IOException(operation.role
                            + " target changed outside the migration transaction.");
                }
            }
            for (RetainedFile item : retained) {
                requireExactFingerprint(item.target, item.fingerprint,
                        item.role + " retained rollback target");
            }
            for (RetiredFile item : retired) {
                requireExactFingerprint(item.original, item.fingerprint,
                        item.role + " prior rollback source");
            }
        }

        private void recoverCompleteCurrentFamily() throws IOException {
            validateImmutableRecoveryArtifacts();
            for (MigrationFile operation : publications) {
                IoUtils.mustMkdirs(operation.target.getParentFile());
                File recovery = File.createTempFile(".deconv-v3-recover-", ".tmp",
                        operation.target.getParentFile());
                boolean published = false;
                try {
                    Files.copy(operation.staged.toPath(), recovery.toPath(),
                            StandardCopyOption.REPLACE_EXISTING);
                    requireExactFingerprint(recovery, operation.desiredFingerprint,
                            operation.role + " forward-recovery temp");
                    forceRegularFile(recovery);
                    IoUtils.moveReplacing(recovery.toPath(), operation.target.toPath());
                    directoryDurabilitySupported &= forceFileAndParent(operation.target);
                    requireExactFingerprint(operation.target, operation.desiredFingerprint,
                            operation.role + " forward-recovery target");
                    published = true;
                } finally {
                    if (!published) Files.deleteIfExists(recovery.toPath());
                }
            }
            File manifestPublish = new File(transactionDir, "manifest-forward.json");
            Files.copy(stagedManifest.toPath(), manifestPublish.toPath(),
                    StandardCopyOption.REPLACE_EXISTING);
            requireExactFingerprint(manifestPublish, desiredManifestFingerprint,
                    "manifest forward-recovery temp");
            forceRegularFile(manifestPublish);
            IoUtils.commitReplacingSmallFile(manifestPublish.toPath(), currentManifestFile.toPath());
            directoryDurabilitySupported &= forceFileAndParent(currentManifestFile);
            requireExactFingerprint(currentManifestFile, desiredManifestFingerprint,
                    "manifest forward-recovery target");
        }

        private boolean preTransactionCurrentFamilyRestored() {
            try {
                if (manifestExisted) {
                    if (!manifestBackupFingerprint.matches(currentManifestFile)) return false;
                } else if (Files.exists(currentManifestFile.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                    return false;
                }
                for (MigrationFile operation : publications) {
                    if (operation.targetExisted) {
                        if (!operation.backupFingerprint.matches(operation.target)) return false;
                    } else if (Files.exists(operation.target.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                        return false;
                    }
                }
                return true;
            } catch (IOException failure) {
                return false;
            }
        }

        private boolean desiredCurrentFamilyComplete() {
            try {
                if (!desiredManifestFingerprint.matches(currentManifestFile)) return false;
                for (MigrationFile operation : publications) {
                    if (!operation.desiredFingerprint.matches(operation.target)) return false;
                }
                for (RetainedFile item : retained) {
                    if (!item.fingerprint.matches(item.target)) return false;
                }
                return true;
            } catch (IOException failure) {
                return false;
            }
        }

        private void validateCompleteCurrentGenerationForRetirement() throws IOException {
            validateImmutableRecoveryArtifacts();
            requireExactFingerprint(currentManifestFile, desiredManifestFingerprint,
                    "final current manifest");
            DeconvManifest manifest = DeconvManifest.load(currentManifestFile);
            if (!manifest.matchesArtifact(current)) {
                throw new IOException("Final current manifest has the wrong artifact identity.");
            }
            DeconvManifest.SourceFingerprint source = sourceFingerprint(current);
            boolean hasManifestPixels = false;
            for (Map.Entry<Integer, DeconvManifest.ChannelEntry> channel
                    : manifest.channels().entrySet()) {
                if (channel.getKey() == null || channel.getValue() == null) {
                    throw new IOException("Final current manifest contains an invalid channel record.");
                }
                int index = channel.getKey().intValue();
                File pixels = deconvFile(rootDir, current, index);
                if (!Files.isRegularFile(pixels.toPath(), LinkOption.NOFOLLOW_LINKS)
                        || !manifest.isChannelFresh(index, null, source, current)) {
                    throw new IOException("Final current channel C" + index
                            + " is missing or not vouched by the exact manifest.");
                }
                hasManifestPixels = true;
            }
            if (manifest.merged() != null) {
                File merged = mergedDeconvFile(rootDir, current);
                if (!Files.isRegularFile(merged.toPath(), LinkOption.NOFOLLOW_LINKS)
                        || !manifest.isMergedFresh(source,
                                manifest.merged().channelParamsHashes.keySet(), null, current)) {
                    throw new IOException("Final merged current artifact is incomplete or stale.");
                }
                hasManifestPixels = true;
            }
            if (!hasManifestPixels) {
                throw new IOException("Final current manifest vouches for no readable pixels.");
            }
            for (MigrationFile operation : publications) {
                if (!operation.role.equals(publicationRole(rootDir, current, operation.target))) {
                    throw new IOException("Journaled publication role/target mapping changed.");
                }
                requireExactFingerprint(operation.target, operation.desiredFingerprint,
                        operation.role + " final current target");
            }
            for (RetainedFile item : retained) {
                if (!item.role.equals(publicationRole(rootDir, current, item.target))) {
                    throw new IOException("Retained role/target mapping changed.");
                }
                requireExactFingerprint(item.target, item.fingerprint,
                        item.role + " final retained target");
            }
            for (RetiredFile item : retired) {
                requireExactFingerprint(item.original, item.fingerprint,
                        item.role + " prior retirement source");
            }
        }

        private void planPriorRetirement() throws IOException {
            List<File> sources = priorFamilyFiles();
            File retirementDir = new File(transactionDir, "retired");
            mustMkdirsTracked(retirementDir);
            for (int i = 0; i < sources.size(); i++) {
                retired.add(new RetiredFile(publicationRole(rootDir, prior, sources.get(i)),
                        sources.get(i),
                        new File(retirementDir, i + ".bin")));
            }
        }

        private void writeRecoveryJournal() throws IOException {
            for (RetainedFile item : retained) {
                forceRegularFile(item.target);
                item.fingerprint = RecoveryFingerprint.of(item.target);
            }
            for (RetiredFile item : retired) {
                forceRegularFile(item.original);
                item.fingerprint = RecoveryFingerprint.of(item.original);
            }
            validatePreJournalSnapshot();
            directoryDurabilitySupported &= forceCreatedDirectoryAncestry();
            StringBuilder records = new StringBuilder();
            int recordCount = 1 + publications.size() + retained.size() + retired.size();
            if (recordCount > MAX_RECOVERY_JOURNAL_RECORDS) {
                throw new IOException("Deconvolution migration requires too many recovery records.");
            }
            records.append("manifest|manifest|").append(encoded(currentManifestFile)).append('|')
                    .append(encoded(stagedManifest)).append('|')
                    .append(desiredManifestFingerprint.size).append('|')
                    .append(desiredManifestFingerprint.contentHash).append('|')
                    .append(manifestExisted ? "1" : "0").append('|')
                    .append(manifestBackup == null ? "-" : encoded(manifestBackup)).append('|')
                    .append(manifestBackupFingerprint == null
                            ? "-" : String.valueOf(manifestBackupFingerprint.size)).append('|')
                    .append(manifestBackupFingerprint == null
                            ? "-" : manifestBackupFingerprint.contentHash).append('\n');
            for (MigrationFile operation : publications) {
                records.append("publish|").append(operation.role).append('|')
                        .append(encoded(operation.target)).append('|')
                        .append(encoded(operation.staged)).append('|')
                        .append(operation.desiredFingerprint.size).append('|')
                        .append(operation.desiredFingerprint.contentHash).append('|')
                        .append(operation.targetExisted ? "1" : "0").append('|')
                        .append(operation.backup == null ? "-" : encoded(operation.backup)).append('|')
                        .append(operation.backupFingerprint == null
                                ? "-" : String.valueOf(operation.backupFingerprint.size)).append('|')
                        .append(operation.backupFingerprint == null
                                ? "-" : operation.backupFingerprint.contentHash).append('\n');
            }
            for (RetainedFile item : retained) {
                records.append("retain|").append(item.role).append('|')
                        .append(encoded(item.target)).append('|').append(item.fingerprint.size)
                        .append('|').append(item.fingerprint.contentHash).append('\n');
            }
            for (RetiredFile item : retired) {
                records.append("retire|").append(item.role).append('|')
                        .append(encoded(item.original)).append('|')
                        .append(encoded(item.hidden)).append('|').append(item.fingerprint.size).append('|')
                        .append(item.fingerprint.contentHash).append('\n');
            }
            String journal = "deconv-migration-v3\n" + records
                    + "commit|" + recordCount + '|' + sha256Hex(records.toString()) + "\n";
            byte[] journalBytes = journal.getBytes(StandardCharsets.UTF_8);
            // Reject a transaction that cannot later be parsed within recovery's fixed budget
            // before attempting to install any authoritative journal.
            JournalDocument.fromBytes(journalBytes);
            File authoritative = new File(transactionDir, "recovery.journal");
            File journalTemp = new File(transactionDir, "recovery.journal.tmp");
            boolean committed = false;
            try {
                Files.write(journalTemp.toPath(), journalBytes);
                faults.checkpoint(MigrationFaultPoint.BEFORE_JOURNAL_TEMP_FORCE);
                forceRegularFile(journalTemp);
                directoryDurabilitySupported &= forceDirectoryMetadata(transactionDir);
                faults.checkpoint(MigrationFaultPoint.BEFORE_JOURNAL_COMMIT);
                // The journal becomes authoritative in one durable replacement only after every
                // desired byte and backup it names has been staged. No family path is changed before
                // this commit returns and the committed bytes are re-read below.
                journalCommitAttempted = true;
                IoUtils.commitReplacingSmallFile(journalTemp.toPath(), authoritative.toPath());
                forceRegularFile(authoritative);
                faults.checkpoint(MigrationFaultPoint.AFTER_JOURNAL_RENAME_BEFORE_DIRECTORY_FORCE);
                directoryDurabilitySupported &= forceDirectoryMetadata(transactionDir);
                byte[] committedBytes = JournalDocument.readBoundedBytes(authoritative);
                if (!java.util.Arrays.equals(journalBytes, committedBytes)) {
                    throw new IOException("Committed deconvolution recovery journal changed bytes.");
                }
                RecoveryJournal.read(rootDir, current, transactionDir, authoritative,
                        JournalDocument.fromBytes(committedBytes));
                journalFile = authoritative;
                journalFingerprint = RecoveryFingerprint.of(authoritative);
                committed = true;
            } finally {
                Files.deleteIfExists(journalTemp.toPath());
                if (!committed) {
                    // A failed/ambiguous small-file commit is not permission to publish pixels.
                    // Remove only an exact complete journal; a partial target is left inside the
                    // still non-destructive transaction for conservative scavenging/quarantine.
                    if (authoritative.isFile()
                            && java.util.Arrays.equals(journalBytes,
                                    JournalDocument.readBoundedBytes(authoritative))) {
                        deleteAndForceParent(authoritative);
                    }
                }
            }
        }

        private void validateImmutableRecoveryArtifacts() throws IOException {
            if (journalFile != null) {
                requireExactFingerprint(journalFile, journalFingerprint,
                        "authoritative recovery journal");
            }
            requireExactFingerprint(stagedManifest, desiredManifestFingerprint,
                    "desired manifest");
            if (manifestExisted) {
                requireExactFingerprint(manifestBackup, manifestBackupFingerprint,
                        "manifest backup");
            } else if (manifestBackup != null || manifestBackupFingerprint != null) {
                throw new IOException("Unexpected manifest backup in migration transaction.");
            }
            for (MigrationFile operation : publications) {
                requireExactFingerprint(operation.staged, operation.desiredFingerprint,
                        operation.role + " desired artifact");
                if (operation.targetExisted) {
                    requireExactFingerprint(operation.backup, operation.backupFingerprint,
                            operation.role + " backup");
                } else if (operation.backup != null || operation.backupFingerprint != null) {
                    throw new IOException("Unexpected " + operation.role + " backup.");
                }
            }
        }

        private void validatePreJournalSnapshot() throws IOException {
            validateImmutableRecoveryArtifacts();
            if (manifestExisted) {
                requireExactFingerprint(currentManifestFile, manifestBackupFingerprint,
                        "live manifest baseline");
            } else if (Files.exists(currentManifestFile.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Manifest baseline appeared after transaction staging.");
            }
            for (MigrationFile operation : publications) {
                if (operation.targetExisted) {
                    requireExactFingerprint(operation.target, operation.backupFingerprint,
                            operation.role + " live baseline");
                } else if (Files.exists(operation.target.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException(operation.role
                            + " baseline appeared after transaction staging.");
                }
            }
            for (RetainedFile item : retained) {
                requireExactFingerprint(item.target, item.fingerprint,
                        item.role + " retained baseline");
            }
            for (RetiredFile item : retired) {
                requireExactFingerprint(item.original, item.fingerprint,
                        item.role + " prior baseline");
                if (Files.exists(item.hidden.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException(item.role + " retirement slot already exists.");
                }
            }
        }

        private void retirePriorFamily() throws IOException {
            for (int i = 0; i < retired.size(); i++) {
                RetiredFile item = retired.get(i);
                requireExactFingerprint(item.original, item.fingerprint,
                        item.role + " prior retirement source");
                IoUtils.moveReplacing(item.original.toPath(), item.hidden.toPath());
                item.moved = true;
                directoryDurabilitySupported &= forceFileAndParent(item.hidden);
                directoryDurabilitySupported &= forceDirectoryMetadata(item.original.getParentFile());
                if (!directoryDurabilitySupported) {
                    throw new IOException("Prior retirement directory entries are not durable.");
                }
                requireExactFingerprint(item.hidden, item.fingerprint,
                        item.role + " retired recovery artifact");
                if (i == 0) faults.checkpoint(MigrationFaultPoint.AFTER_FIRST_PRIOR_RETIRE);
            }
        }

        private List<File> priorFamilyFiles() throws IOException {
            List<File> files = new ArrayList<File>();
            File outputDir = deconvOutDir(rootDir);
            File[] flat = outputDir.listFiles((dir, name) -> name != null
                    && name.startsWith(prior.artifactKey + "_"));
            if (flat == null && outputDir.isDirectory()) {
                throw new IOException("Could not enumerate prior deconvolution outputs.");
            }
            if (flat != null) {
                for (File file : flat) if (file != null && file.isFile()) files.add(file);
            }
            // Params-hash cache trees are identity-keyed, optional, and regenerable. Keeping the
            // prior-key tree is safe (current readers never select it) and prevents legitimate
            // cache accumulation from exhausting the fixed recovery journal budget.
            Collections.sort(files, new Comparator<File>() {
                @Override
                public int compare(File left, File right) {
                    boolean leftManifest = left.getName().endsWith("_deconv.manifest.json");
                    boolean rightManifest = right.getName().endsWith("_deconv.manifest.json");
                    if (leftManifest == rightManifest) return left.getPath().compareTo(right.getPath());
                    return leftManifest ? 1 : -1;
                }
            });
            return files;
        }

        private boolean rollbackPriorRetirement(IOException primary) {
            boolean restored = true;
            for (int i = retired.size() - 1; i >= 0; i--) {
                RetiredFile item = retired.get(i);
                if (!item.moved) continue;
                try {
                    requireExactFingerprint(item.hidden, item.fingerprint,
                            item.role + " retired rollback source");
                    IoUtils.mustMkdirs(item.original.getParentFile());
                    IoUtils.moveReplacing(item.hidden.toPath(), item.original.toPath());
                    directoryDurabilitySupported &= forceFileAndParent(item.original);
                    directoryDurabilitySupported &= forceDirectoryMetadata(item.hidden.getParentFile());
                    requireExactFingerprint(item.original, item.fingerprint,
                            item.role + " restored prior target");
                } catch (IOException rollbackFailure) {
                    primary.addSuppressed(rollbackFailure);
                    restored = false;
                }
            }
            return restored;
        }

        private IOException cleanupTransaction() {
            try {
                faults.checkpoint(MigrationFaultPoint.BEFORE_TRANSACTION_CLEANUP);
                if (testOnlyPathOperationsAllowed() && transactionCreationAnchor != null) {
                    transactionCreationAnchor.revalidate();
                }
                if (journalFile != null) {
                    faults.checkpoint(MigrationFaultPoint.BEFORE_JOURNAL_CLEANUP);
                    if (testOnlyPathOperationsAllowed() && transactionCreationAnchor != null) {
                        transactionCreationAnchor.revalidate();
                    }
                    cleanupRecoveredTransaction(cleanupAnchor, transactionDir, journalFile);
                } else {
                    deleteTree(cleanupAnchor, transactionDir);
                }
                return null;
            } catch (RetryableCleanupException keyless) {
                // The current generation is already durable. Java 8 cannot conditionally rename
                // the validated transaction child, so the safest deferred-cleanup state is the
                // same confined source name. Housekeeping capability must not make publication
                // report failure or cause a second promotion.
                return null;
            } catch (IOException failure) {
                // Current or prior keyed families remain authoritative; report the hidden recovery
                // bytes instead of treating incomplete cleanup as a fully successful migration.
                return failure;
            }
        }

        private boolean forceCurrentGenerationForRetirement() throws IOException {
            directoryDurabilitySupported &= forceFileAndParent(currentManifestFile);
            for (MigrationFile operation : publications) {
                directoryDurabilitySupported &= forceFileAndParent(operation.target);
            }
            for (RetainedFile item : retained) {
                directoryDurabilitySupported &= forceFileAndParent(item.target);
            }
            directoryDurabilitySupported &= forceDirectoryMetadata(deconvOutDir(rootDir));
            directoryDurabilitySupported &= forceCreatedDirectoryAncestry();
            return directoryDurabilitySupported;
        }

        private void mustMkdirsTracked(File directory) throws IOException {
            if (directory == null) throw new IOException("Migration directory is null.");
            List<File> missing = new ArrayList<File>();
            File cursor = directory.getCanonicalFile();
            while (cursor != null
                    && !Files.exists(cursor.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                missing.add(cursor);
                cursor = cursor.getParentFile();
            }
            if (cursor == null || !Files.isDirectory(cursor.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Migration directory ancestry is unsafe: " + directory);
            }
            while (cursor != null && createdDirectoryPaths.contains(cursor.getCanonicalPath())) {
                cursor = cursor.getParentFile();
            }
            if (cursor == null || !Files.isDirectory(cursor.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Migration has no pre-existing directory boundary: " + directory);
            }
            IoUtils.mustMkdirs(directory);
            for (File created : missing) {
                createdDirectoryPaths.add(created.getCanonicalPath());
            }
            ancestryBoundaryPaths.add(cursor.getCanonicalPath());
        }

        private boolean forceCreatedDirectoryAncestry() throws IOException {
            List<File> created = filesDeepestFirst(createdDirectoryPaths);
            List<File> boundaries = filesDeepestFirst(ancestryBoundaryPaths);
            boolean supported = true;
            for (File directory : created) supported &= forceDirectoryMetadata(directory);
            for (File boundary : boundaries) supported &= forceDirectoryMetadata(boundary);
            return supported;
        }
    }

    /**
     * Recover authoritative transactions. Returns true when untrusted recovery bytes have been
     * quarantined and migration must remain suppressed, even though one family generation is safe
     * to read.
     */
    private static boolean scavengeAbandonedTransactions(File rootDir, ArtifactIdentity current)
            throws IOException {
        File migrationRoot = new File(cacheDir(rootDir), ".migration");
        if (!testOnlyPathOperationsAllowed()) {
            return hasProductionRecoveryBlocker(rootDir, current);
        }
        if (!migrationRoot.exists()) return false;
        if (!isContainedPlainDirectory(cacheDir(rootDir), migrationRoot)) {
            throw new IOException("Unsafe deconvolution migration recovery directory: " + migrationRoot);
        }
        MigrationTrustAnchor cleanupAnchor = MigrationTrustAnchor.capture(rootDir, migrationRoot);
        scavengeDeferredCleanup(cleanupAnchor, current);
        return scavengeAbandonedTransactionsTestOnly(rootDir, current, migrationRoot,
                cleanupAnchor);
    }

    private static boolean hasProductionRecoveryBlocker(
            File rootDir, ArtifactIdentity current) throws IOException {
        File migrationRoot = new File(cacheDir(rootDir), ".migration");
        // Legacy recovery was stored in one shared directory. On providers without secure
        // enumeration there is no safe way to attribute those names to one identity, so any
        // legacy namespace remains an explicit global compatibility gate.
        if (Files.exists(migrationRoot.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            if (!isContainedPlainDirectory(cacheDir(rootDir), migrationRoot)) {
                throw new IOException("Unsafe legacy deconvolution migration recovery directory: "
                        + migrationRoot);
            }
            return true;
        }
        File active = v4ActiveRecoveryDirectory(rootDir, current);
        // Files.exists cannot distinguish absence from an access-denied probe. This read-only
        // fallback is safe only while handleBoundMigrationCreationAvailable() is hard-disabled in
        // production, so a false result cannot authorize publication. Any future v4 creator must
        // replace this with a tri-state, fail-closed exact-slot open before enabling migration.
        if (!Files.exists(active.toPath(), LinkOption.NOFOLLOW_LINKS)) return false;
        // v4 is an exact bounded slot keyed by the complete artifact identity (including the
        // source container discriminator), never a directory that recovery enumerates.
        MigrationTrustAnchor.capture(rootDir, active).revalidate();
        return true;
    }

    private static boolean scavengeAbandonedTransactionsTestOnly(
            File rootDir, ArtifactIdentity current, File migrationRoot,
            MigrationTrustAnchor cleanupAnchor) throws IOException {
        beforeRecoveryDirectoryOperation("enumerate-migration", migrationRoot);
        cleanupAnchor.revalidate();
        File quarantineRoot = new File(migrationRoot, ".quarantine");
        if (quarantineRoot.exists()) {
            if (!isContainedPlainDirectory(migrationRoot, quarantineRoot)) {
                throw new IOException("Unsafe deconvolution migration quarantine: " + quarantineRoot);
            }
            if (inspectQuarantinedTransactions(rootDir, current, quarantineRoot)) {
                return true;
            }
        }
        File[] candidates = migrationRoot.listFiles();
        if (candidates == null) {
            throw new IOException("Could not enumerate deconvolution migration recovery state.");
        }
        String prefix = current.familyLockToken() + "-";
        for (File transaction : candidates) {
            if (transaction == null || !transaction.getName().startsWith(prefix)) continue;
            if (!isContainedPlainDirectory(migrationRoot, transaction)) {
                throw new IOException("Unsafe linked deconvolution recovery transaction: " + transaction);
            }
            if (isDeferredCleanupTransaction(transaction)) {
                try {
                    File relocated = relocateDeferredCleanup(transaction, cleanupAnchor);
                    deleteTree(cleanupAnchor, relocated);
                } catch (IOException retryLater) {
                    // The marked source or intrinsically cleanup-only destination has no authority
                    // over live files. Leave it for a later retry without blocking family access.
                }
                continue;
            }
            if (recoverAbandonedTransaction(rootDir, current, transaction, quarantineRoot,
                    cleanupAnchor)) {
                return true;
            }
        }
        return false;
    }

    private static void scavengeDeferredCleanup(MigrationTrustAnchor cleanupAnchor,
                                                 ArtifactIdentity current) {
        try {
            if (current != null) processCleanupTickets(cleanupAnchor,
                    current.familyLockToken(), MAX_DEFERRED_CLEANUP_PER_ACCESS);
        } catch (IOException retryLater) {
            // Never follow an unsafe namespace and never promote it to recovery authority.
        }
    }

    /**
     * Drain direct fixed-width tickets only.  No hostile directory is enumerated: two state slots
     * and at most {@code maximum} ticket paths are read directly by monotonically increasing id.
     */
    private static void processCleanupTickets(MigrationTrustAnchor anchor, String family,
                                              int maximum) throws IOException {
        CleanupTicketQueue queue = CleanupTicketQueue.openExisting(anchor, family);
        if (queue == null) return;
        CleanupQueueState state = queue.readState();
        int processed = 0;
        while (processed < maximum && queue.ticketExists(state.tail)) {
            File orphan = queue.ticket(state.tail);
            try {
                if (queue.readTicket(orphan) == null) break;
            } catch (RetryableCleanupException unsafeNamespace) {
                throw unsafeNamespace;
            } catch (IOException malformed) {
                queue.deleteTicketMetadata(orphan);
                state.tail = incrementCleanupTicketId(state.tail);
                processed++;
                if (!queue.writeState(state)) return;
                continue;
            }
            state.tail = incrementCleanupTicketId(state.tail);
            processed++;
            if (!queue.writeState(state)) return;
        }
        while (processed < maximum && state.head < state.tail) {
            File ticketFile = queue.ticket(state.head);
            CleanupTicket ticket;
            try {
                ticket = queue.readTicket(ticketFile);
            } catch (RetryableCleanupException unsafeNamespace) {
                throw unsafeNamespace;
            } catch (IOException malformed) {
                // A corrupt bounded metadata file has no cleanup authority. Remove only that
                // exact ticket name, advance durably, and leave every payload byte untouched.
                queue.deleteTicketMetadata(ticketFile);
                state.head = incrementCleanupTicketId(state.head);
                processed++;
                if (!queue.writeState(state)) return;
                continue;
            }
            if (ticket != null) {
                if (ticket.isExactFile()) {
                    // Exact data is retained rather than deleted. Keep its original head ticket
                    // stable until the deterministic retained name and both directory barriers
                    // have converged; rewriting at the tail would create churn on every access.
                    if (!queue.processExactPayloadOnce(ticket)) break;
                } else {
                    // A TREE ticket has no authenticated descendants and therefore never grants
                    // traversal or deletion authority. Keep the head and its original ticket
                    // stable until the direct opaque payload has actually been rebound. A failed
                    // move therefore costs one bounded attempt per explicit access, not 32 tail
                    // rewrites in the same drain.
                    if (!queue.retainTreePayloadOnce(ticket)) break;
                }
                queue.deleteTicketMetadata(ticketFile);
            }
            state.head = incrementCleanupTicketId(state.head);
            processed++;
            if (!queue.writeState(state)) return;
        }
    }

    private static long incrementCleanupTicketId(long value) throws IOException {
        if (value < 0L || value == Long.MAX_VALUE) {
            throw new IOException("Cleanup ticket id overflow.");
        }
        return value + 1L;
    }

    private static File enqueueDeferredCleanup(MigrationTrustAnchor anchor, File transaction,
                                               String family) throws IOException {
        CleanupTicketQueue queue = CleanupTicketQueue.create(anchor, family);
        CleanupQueueState state = queue.readState();
        // Adopt a crash-orphaned tail ticket before assigning that exact id to a new payload.
        File orphan = queue.ticket(state.tail);
        if (queue.ticketExists(state.tail)) {
            try {
                if (queue.readTicket(orphan) == null) {
                    throw new IOException("Absent deferred cleanup tail ticket.");
                }
            } catch (RetryableCleanupException unsafeNamespace) {
                throw unsafeNamespace;
            } catch (IOException malformed) {
                queue.deleteTicketMetadata(orphan);
            }
            state.tail = incrementCleanupTicketId(state.tail);
            if (!queue.writeState(state)) {
                throw new IOException("Could not adopt deferred cleanup tail ticket durably.");
            }
        }
        File destination = queue.newPayload();
        CleanupTicket ticket = new CleanupTicket(family, destination);
        if (!queue.writeTicket(state.tail, ticket)) {
            throw new IOException("Could not publish deferred cleanup ticket atomically.");
        }
        queue.moveIntoPayload(transaction, destination);
        state.tail = incrementCleanupTicketId(state.tail);
        // If publication cannot become durable, the orphan tail ticket is intentionally retained;
        // the next access adopts it before issuing another id.
        queue.writeState(state);
        return destination;
    }

    static File enqueueDeferredCleanupForTest(File rootDir, ArtifactIdentity identity,
                                              String name) throws IOException {
        File migration = new File(cacheDir(rootDir), ".migration");
        IoUtils.mustMkdirs(migration);
        MigrationTrustAnchor anchor = MigrationTrustAnchor.capture(rootDir, migration);
        CleanupTicketQueue queue = CleanupTicketQueue.create(anchor, identity.familyLockToken());
        CleanupQueueState state = queue.readState();
        if (queue.ticketExists(state.tail)) {
            File orphan = queue.ticket(state.tail);
            try {
                if (queue.readTicket(orphan) == null) {
                    throw new IOException("Absent orphan cleanup test ticket.");
                }
            } catch (RetryableCleanupException unsafeNamespace) {
                throw unsafeNamespace;
            } catch (IOException malformed) {
                queue.deleteTicketMetadata(orphan);
            }
            state.tail = incrementCleanupTicketId(state.tail);
            if (!queue.writeState(state)) throw new IOException("Could not adopt cleanup test ticket.");
        }
        File payload = queue.newPayload();
        queue.createPayloadDirectory(payload);
        CleanupTicket ticket = new CleanupTicket(identity.familyLockToken(), payload);
        if (!queue.writeTicket(state.tail, ticket)) {
            throw new IOException("Could not enqueue cleanup test payload.");
        }
        state.tail = incrementCleanupTicketId(state.tail);
        if (!queue.writeState(state)) throw new IOException("Could not publish cleanup test state.");
        return payload;
    }

    private static final class CleanupTicketQueue {
        final MigrationTrustAnchor anchor;
        final String family;
        final File cleanupRoot;
        final File familyQueue;
        final File tickets;
        final File payloads;
        final File retained;
        final AnchoredDirectory cleanupDirectory;
        final AnchoredDirectory queueDirectory;
        final AnchoredDirectory familyDirectory;
        final AnchoredDirectory ticketsDirectory;
        final AnchoredDirectory payloadsDirectory;
        final AnchoredDirectory retainedDirectory;

        private CleanupTicketQueue(MigrationTrustAnchor anchor, String family, File cleanupRoot,
                                   File familyQueue, File tickets, File payloads, File retained,
                                   AnchoredDirectory cleanupDirectory,
                                   AnchoredDirectory queueDirectory,
                                   AnchoredDirectory familyDirectory,
                                   AnchoredDirectory ticketsDirectory,
                                   AnchoredDirectory payloadsDirectory,
                                   AnchoredDirectory retainedDirectory) {
            this.anchor = anchor;
            this.family = family;
            this.cleanupRoot = cleanupRoot;
            this.familyQueue = familyQueue;
            this.tickets = tickets;
            this.payloads = payloads;
            this.retained = retained;
            this.cleanupDirectory = cleanupDirectory;
            this.queueDirectory = queueDirectory;
            this.familyDirectory = familyDirectory;
            this.ticketsDirectory = ticketsDirectory;
            this.payloadsDirectory = payloadsDirectory;
            this.retainedDirectory = retainedDirectory;
        }

        static CleanupTicketQueue openExisting(MigrationTrustAnchor anchor, String family)
                throws IOException {
            File migration = anchor.migrationRoot.toFile();
            File cleanup = new File(migration, DEFERRED_CLEANUP_DIRECTORY);
            if (!Files.exists(cleanup.toPath(), LinkOption.NOFOLLOW_LINKS)) return null;
            File queueRoot = new File(cleanup, DEFERRED_CLEANUP_QUEUE);
            File familyQueue = new File(queueRoot, family);
            File tickets = new File(familyQueue, CLEANUP_TICKETS);
            File payloads = new File(familyQueue, CLEANUP_PAYLOADS);
            File retained = new File(familyQueue, CLEANUP_RETAINED);
            if (!isSha256(family)
                    || !Files.isDirectory(cleanup.toPath(), LinkOption.NOFOLLOW_LINKS)
                    || !Files.isDirectory(queueRoot.toPath(), LinkOption.NOFOLLOW_LINKS)
                    || !Files.isDirectory(familyQueue.toPath(), LinkOption.NOFOLLOW_LINKS)
                    || !Files.isDirectory(tickets.toPath(), LinkOption.NOFOLLOW_LINKS)
                    || !Files.isDirectory(payloads.toPath(), LinkOption.NOFOLLOW_LINKS)
                    || !Files.isDirectory(retained.toPath(), LinkOption.NOFOLLOW_LINKS)) return null;
            return capture(anchor, family, cleanup, queueRoot, familyQueue, tickets,
                    payloads, retained);
        }

        static CleanupTicketQueue create(MigrationTrustAnchor anchor, String family)
                throws IOException {
            if (!isSha256(family)) throw new IOException("Invalid deferred cleanup family.");
            try {
                File migration = anchor.migrationRoot.toFile();
                File cleanup = new File(migration, DEFERRED_CLEANUP_DIRECTORY);
                ensurePlainCleanupDirectory(anchor, migration, cleanup);
                File queueRoot = new File(cleanup, DEFERRED_CLEANUP_QUEUE);
                ensurePlainCleanupDirectory(anchor, cleanup, queueRoot);
                File familyQueue = new File(queueRoot, family);
                ensurePlainCleanupDirectory(anchor, queueRoot, familyQueue);
                File tickets = new File(familyQueue, CLEANUP_TICKETS);
                ensurePlainCleanupDirectory(anchor, familyQueue, tickets);
                File payloads = new File(familyQueue, CLEANUP_PAYLOADS);
                ensurePlainCleanupDirectory(anchor, familyQueue, payloads);
                File retained = new File(familyQueue, CLEANUP_RETAINED);
                ensurePlainCleanupDirectory(anchor, familyQueue, retained);
                return capture(anchor, family, cleanup, queueRoot, familyQueue, tickets,
                        payloads, retained);
            } catch (RetryableCleanupException retryable) {
                throw retryable;
            } catch (IOException unsafeOrUnavailable) {
                throw new RetryableCleanupException(
                        "Cleanup queue namespace cannot be safely anchored for mutation.",
                        unsafeOrUnavailable);
            }
        }

        private static CleanupTicketQueue capture(MigrationTrustAnchor anchor, String family,
                                                  File cleanup, File queueRoot, File familyQueue,
                                                  File tickets, File payloads, File retained)
                throws IOException {
            anchor.revalidate();
            AnchoredDirectory migration = anchor.migrationDirectory();
            AnchoredDirectory cleanupAnchor = AnchoredDirectory.capturePlain(
                    cleanup.toPath().toAbsolutePath().normalize(),
                    migration.real.resolve(DEFERRED_CLEANUP_DIRECTORY).normalize());
            AnchoredDirectory queueAnchor = AnchoredDirectory.capturePlain(
                    queueRoot.toPath().toAbsolutePath().normalize(),
                    cleanupAnchor.real.resolve(DEFERRED_CLEANUP_QUEUE).normalize());
            AnchoredDirectory familyAnchor = AnchoredDirectory.capturePlain(
                    familyQueue.toPath().toAbsolutePath().normalize(),
                    queueAnchor.real.resolve(family).normalize());
            AnchoredDirectory ticketsAnchor = AnchoredDirectory.capturePlain(
                    tickets.toPath().toAbsolutePath().normalize(),
                    familyAnchor.real.resolve(CLEANUP_TICKETS).normalize());
            AnchoredDirectory payloadsAnchor = AnchoredDirectory.capturePlain(
                    payloads.toPath().toAbsolutePath().normalize(),
                    familyAnchor.real.resolve(CLEANUP_PAYLOADS).normalize());
            AnchoredDirectory retainedAnchor = AnchoredDirectory.capturePlain(
                    retained.toPath().toAbsolutePath().normalize(),
                    familyAnchor.real.resolve(CLEANUP_RETAINED).normalize());
            return new CleanupTicketQueue(anchor, family, cleanup, familyQueue, tickets,
                    payloads, retained, cleanupAnchor, queueAnchor, familyAnchor,
                    ticketsAnchor, payloadsAnchor, retainedAnchor);
        }

        void revalidateDirectories() throws IOException {
            try {
                anchor.revalidate();
                cleanupDirectory.revalidate();
                queueDirectory.revalidate();
                familyDirectory.revalidate();
                ticketsDirectory.revalidate();
                payloadsDirectory.revalidate();
                retainedDirectory.revalidate();
            } catch (RetryableCleanupException retryable) {
                throw retryable;
            } catch (IOException changed) {
                throw new RetryableCleanupException(
                        "Cleanup queue directory identity changed; retaining state for retry.",
                        changed);
            }
        }

        CleanupQueueState readState() throws IOException {
            revalidateDirectories();
            CleanupQueueState best = null;
            boolean sawState = false;
            for (int slot = 0; slot < 2; slot++) {
                File file = new File(familyQueue, CLEANUP_STATE_PREFIX + slot);
                try {
                    if (!anchoredChildExists(familyDirectory, file.getName(),
                            "read-state")) continue;
                    sawState = true;
                    CleanupQueueState candidate = CleanupQueueState.parse(
                            readAnchoredBoundedStrictUtf8(familyDirectory, file.getName(),
                                    MAX_CLEANUP_STATE_BYTES, "deferred cleanup queue state"));
                    if (best == null || candidate.generation > best.generation) best = candidate;
                } catch (RetryableCleanupException unsafeNamespace) {
                    throw unsafeNamespace;
                } catch (IOException corrupt) {
                    // The other generation remains independently usable.
                }
            }
            if (best == null && sawState) {
                throw new RetryableCleanupException(
                        "No valid bounded deferred cleanup queue state remains.");
            }
            revalidateDirectories();
            return best == null ? new CleanupQueueState() : best;
        }

        boolean writeState(CleanupQueueState state) throws IOException {
            try {
                revalidateDirectories();
                CleanupQueueState next = state.nextGeneration();
                File slot = new File(familyQueue,
                        CLEANUP_STATE_PREFIX + (next.generation & 1L));
                if (!writeForcedAtomic(familyDirectory, slot,
                        next.encode().getBytes(StandardCharsets.UTF_8),
                        MAX_CLEANUP_STATE_BYTES)) return false;
                revalidateDirectories();
                state.generation = next.generation;
                return true;
            } catch (RetryableCleanupException retryable) {
                throw retryable;
            } catch (IOException unavailable) {
                throw new RetryableCleanupException(
                        "Cleanup queue state could not be published safely.", unavailable);
            }
        }

        File ticket(long id) throws IOException {
            if (id < 0L || id == Long.MAX_VALUE) throw new IOException("Cleanup ticket overflow.");
            return new File(tickets, String.format(Locale.ROOT, "%020d.ticket", id));
        }

        boolean ticketExists(long id) throws IOException {
            revalidateDirectories();
            return anchoredChildExists(ticketsDirectory, ticket(id).getName(), "read-ticket");
        }

        CleanupTicket readTicket(File file) throws IOException {
            revalidateDirectories();
            Path ticketPath = file.toPath().toAbsolutePath().normalize();
            if (!ticketPath.getParent().equals(tickets.toPath().toAbsolutePath().normalize())
                    || !file.getName().matches("[0-9]{20}\\.ticket")) {
                throw new IOException("Cleanup ticket is not an exact generated ticket path.");
            }
            if (!anchoredChildExists(ticketsDirectory, file.getName(), "read-ticket")) return null;
            CleanupTicket ticket = CleanupTicket.parse(readAnchoredBoundedStrictUtf8(
                    ticketsDirectory, file.getName(), MAX_CLEANUP_TICKET_BYTES,
                    "deferred cleanup ticket"));
            Path payloadPath = ticket.payload.toPath().toAbsolutePath().normalize();
            if (!family.equals(ticket.family) || payloadPath.getParent() == null
                    || !payloadPath.getParent().equals(
                            payloads.toPath().toAbsolutePath().normalize())
                    || !isGeneratedCleanupPayloadName(ticket.payload.getName())) {
                throw new IOException("Cleanup ticket escapes its family payload root.");
            }
            revalidateDirectories();
            return ticket;
        }

        boolean writeTicket(long id, CleanupTicket ticket) throws IOException {
            try {
                revalidateDirectories();
                boolean written = writeForcedAtomic(ticketsDirectory, ticket(id),
                        ticket.encode().getBytes(StandardCharsets.UTF_8),
                        MAX_CLEANUP_TICKET_BYTES);
                revalidateDirectories();
                return written;
            } catch (RetryableCleanupException retryable) {
                throw retryable;
            } catch (IOException unavailable) {
                throw new RetryableCleanupException(
                        "Cleanup ticket could not be published safely.", unavailable);
            }
        }

        File newPayload() {
            return new File(payloads, "payload-" + UUID.randomUUID().toString());
        }

        void createPayloadDirectory(File payload) throws IOException {
            revalidateDirectories();
            requireGeneratedDirectChild(payloadsDirectory, payload, "cleanup payload");
            if (!testOnlyPathOperationsAllowed()) {
                throw new RetryableCleanupException(
                        "Java 8 exposes no handle-relative directory creation; cleanup remains "
                                + "at its confined source name.");
            }
            beforeQueueDirectoryOperation("create-payload", payloadsDirectory);
            payloadsDirectory.revalidate();
            Files.createDirectory(payload.toPath());
            revalidateDirectories();
        }

        void moveIntoPayload(File transaction, File destination) throws IOException {
            try {
                revalidateDirectories();
                requireGeneratedDirectChild(payloadsDirectory, destination, "cleanup payload");
                Path parent = transaction.toPath().toAbsolutePath().normalize().getParent();
                if (parent == null || !parent.equals(anchor.migrationRoot)) {
                    throw new IOException("Deferred cleanup source escaped the migration root.");
                }
                moveAnchoredChild(anchor.migrationDirectory(), transaction.getName(),
                        payloadsDirectory, destination.getName(), "bind-tree-payload");
                if (!forceAnchoredDirectory(payloadsDirectory, "force-payload-destination")
                        || !forceAnchoredDirectory(anchor.migrationDirectory(),
                        "force-payload-source")) {
                    throw new RetryableCleanupException(
                            "Deferred cleanup binding directory entries are not durable.");
                }
                revalidateDirectories();
            } catch (RetryableCleanupException retryable) {
                throw retryable;
            } catch (IOException unavailable) {
                throw new RetryableCleanupException(
                        "Deferred cleanup payload could not be rebound safely.", unavailable);
            }
        }

        void deleteTicketMetadata(File ticket) throws IOException {
            try {
                revalidateDirectories();
                Path parent = ticket.toPath().toAbsolutePath().normalize().getParent();
                if (parent == null
                        || !parent.equals(tickets.toPath().toAbsolutePath().normalize())
                        || !ticket.getName().matches("[0-9]{20}\\.ticket")) {
                    throw new IOException("Refusing to remove non-ticket cleanup metadata.");
                }
                deleteAnchoredChild(ticketsDirectory, ticket.getName(), "delete-ticket");
                if (!forceAnchoredDirectory(ticketsDirectory, "force-ticket-delete")) {
                    throw new RetryableCleanupException(
                            "Cleanup ticket removal is not durably bound.");
                }
                revalidateDirectories();
            } catch (RetryableCleanupException retryable) {
                throw retryable;
            } catch (IOException unavailable) {
                throw new RetryableCleanupException(
                        "Cleanup ticket could not be removed safely.", unavailable);
            }
        }

        boolean processExactPayloadOnce(CleanupTicket ticket) {
            String payloadName = ticket.payload.getName();
            File action = new File(retained, "action-"
                    + payloadName.substring("payload-".length()));
            try {
                revalidateDirectories();
                boolean sourceExists = anchoredChildExists(payloadsDirectory, payloadName,
                        "read-exact-source");
                boolean destinationExists = anchoredChildExists(retainedDirectory,
                        action.getName(), "read-exact-destination");
                if (sourceExists && destinationExists) return false;
                if (sourceExists) {
                    moveAnchoredChild(payloadsDirectory, payloadName, retainedDirectory,
                            action.getName(), "retain-exact");
                    destinationExists = true;
                }
                if (!destinationExists) return true;
                // The destination entry must be durable before removal from the source parent.
                // If the second barrier fails, the same head ticket and deterministic name retry.
                if (!forceAnchoredDirectory(retainedDirectory, "force-exact-destination")
                        || !forceAnchoredDirectory(payloadsDirectory,
                        "force-exact-source")) return false;
                revalidateDirectories();

                ValidatedRecoveryFile exact = ticket.expected.validate(action);
                if (exact == null) return true;
                beforeExactFileAction(action, exact,
                        "queued exact cleanup payload changed after private binding");
                // A same-inode rewrite is invisible to identity-only checks. Re-authenticate all
                // journalled bytes after the final deterministic action hook. The classified data
                // remains retained; Java has no conditional unlink bound to the validated inode.
                ValidatedRecoveryFile finalExact = ticket.expected.validate(action);
                if (finalExact != null) {
                    FinalExactClassificationTestHook hook = finalExactClassificationHookForTest;
                    if (hook != null) hook.afterFinalValidation(action);
                }
                revalidateDirectories();
                return true;
            } catch (IOException retainFailure) {
                return false;
            }
        }

        boolean retainTreePayloadOnce(CleanupTicket ticket) {
            String payloadName = ticket.payload.getName();
            File destination = new File(retained, "tree-"
                    + payloadName.substring("payload-".length()));
            try {
                revalidateDirectories();
                boolean sourceExists = anchoredChildExists(payloadsDirectory, payloadName,
                        "read-tree-source");
                boolean destinationExists = anchoredChildExists(retainedDirectory,
                        destination.getName(), "read-tree-destination");
                if (sourceExists && destinationExists) return false;
                if (sourceExists) {
                    TreeRetentionMoveTestHook hook = treeRetentionMoveHookForTest;
                    if (hook != null) hook.beforeMove(ticket.payload, destination);
                    moveAnchoredChild(payloadsDirectory, payloadName, retainedDirectory,
                            destination.getName(), "retain-tree");
                    destinationExists = true;
                }
                // A prior attempt can have completed the rename but failed while forcing its
                // parents. The deterministic destination lets this retry establish durability
                // without scanning or creating another retained name.
                if (destinationExists) {
                    if (!forceAnchoredDirectory(retainedDirectory,
                            "force-tree-destination")
                            || !forceAnchoredDirectory(payloadsDirectory,
                            "force-tree-source")) return false;
                }
                revalidateDirectories();
                return true;
            } catch (IOException retainFailure) {
                return false;
            }
        }
    }

    private static boolean isGeneratedCleanupPayloadName(String name) {
        return name != null && name.matches("payload-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-"
                + "[0-9a-f]{4}-[0-9a-f]{12}");
    }

    private static boolean plainAnchoredDirectory(MigrationTrustAnchor anchor, File directory)
            throws IOException {
        SafeNode node = safeNode(anchor, directory);
        return node != null && node.attributes.isDirectory();
    }

    private static boolean writeForcedAtomic(AnchoredDirectory parent, File target,
                                             byte[] content, int maximum) throws IOException {
        if (content == null || content.length > maximum) {
            throw new IOException("Invalid bounded cleanup metadata publication.");
        }
        requireGeneratedDirectChild(parent, target, "cleanup metadata");
        String tempName = target.getName() + ".tmp-" + UUID.randomUUID();
        if (testOnlyPathOperationsAllowed()) {
            File temp = new File(parent.lexical.toFile(), tempName);
            try {
                beforeQueueDirectoryOperation("write-metadata", parent);
                revalidateCleanupDirectory(parent);
                Files.write(temp.toPath(), content, StandardOpenOption.CREATE_NEW,
                        StandardOpenOption.WRITE);
                forceRegularFile(temp);
                revalidateCleanupDirectory(parent);
                try {
                    Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING);
                } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
                    throw new RetryableCleanupException(
                            "Atomic cleanup metadata publication is unavailable.", unsupported);
                }
                revalidateCleanupDirectory(parent);
                forceRegularFile(target);
                if (!forceAnchoredDirectory(parent, "force-metadata-parent")) {
                    throw new IOException("Cleanup metadata directory entry is not durable.");
                }
                return true;
            } finally {
                revalidateCleanupDirectory(parent);
                Files.deleteIfExists(temp.toPath());
            }
        }

        SecureDirectoryStream<Path> secure = openVerifiedSecureDirectory(parent,
                "write-metadata");
        Path temp = Paths.get(tempName);
        try {
            Set<StandardOpenOption> options = new HashSet<StandardOpenOption>();
            options.add(StandardOpenOption.CREATE_NEW);
            options.add(StandardOpenOption.WRITE);
            try (SeekableByteChannel channel = secure.newByteChannel(temp, options)) {
                ByteBuffer bytes = ByteBuffer.wrap(content);
                while (bytes.hasRemaining()) channel.write(bytes);
                if (!(channel instanceof FileChannel)) {
                    throw new RetryableCleanupException(
                            "Cleanup metadata channel cannot establish a durability barrier.");
                }
                ((FileChannel) channel).force(true);
            }
            try {
                secure.deleteFile(Paths.get(target.getName()));
            } catch (NoSuchFileException absent) {
                // First publication of this fixed metadata name.
            }
            secure.move(temp, secure, Paths.get(target.getName()));
            if (!forceSecureDirectoryHandle(secure)) {
                throw new RetryableCleanupException(
                        "Cleanup metadata directory handle cannot be forced.");
            }
            revalidateCleanupDirectory(parent);
            return true;
        } finally {
            try {
                secure.deleteFile(temp);
            } catch (NoSuchFileException absent) {
                // Rename completed.
            } finally {
                try {
                    closeCleanupDirectory(secure);
                } finally {
                    revalidateCleanupDirectory(parent);
                }
            }
        }
    }

    private static boolean testOnlyPathOperationsAllowed() {
        return Boolean.TRUE.equals(stableFileIdentityOverrideForTest);
    }

    private static void revalidateCleanupDirectory(AnchoredDirectory directory)
            throws RetryableCleanupException {
        try {
            directory.revalidate();
        } catch (IOException changed) {
            throw new RetryableCleanupException(
                    "Cleanup directory identity changed; state remains retained for retry.",
                    changed);
        }
    }

    private static void closeCleanupDirectory(DirectoryStream<Path> directory)
            throws RetryableCleanupException {
        if (directory == null) return;
        try {
            directory.close();
        } catch (IOException unavailable) {
            throw new RetryableCleanupException(
                    "Anchored cleanup directory handle could not be closed cleanly.",
                    unavailable);
        }
    }

    private static void beforeQueueDirectoryOperation(String operation,
                                                      AnchoredDirectory directory)
            throws IOException {
        QueueDirectoryTestHook hook = queueDirectoryHookForTest;
        if (hook != null) hook.beforeOperation(operation, directory.lexical.toFile());
    }

    private static void requireGeneratedDirectChild(AnchoredDirectory parent, File child,
                                                    String role) throws IOException {
        Path lexical = child.toPath().toAbsolutePath().normalize();
        if (lexical.getParent() == null || !lexical.getParent().equals(parent.lexical)
                || child.getName().indexOf('/') >= 0 || child.getName().indexOf('\\') >= 0
                || ".".equals(child.getName()) || "..".equals(child.getName())) {
            throw new IOException("Unsafe " + role + " direct child: " + child);
        }
    }

    private static boolean anchoredChildExists(AnchoredDirectory parent, String name,
                                               String operation) throws IOException {
        File child = new File(parent.lexical.toFile(), name);
        requireGeneratedDirectChild(parent, child, "anchored lookup");
        if (testOnlyPathOperationsAllowed()) {
            try {
                beforeQueueDirectoryOperation(operation, parent);
                revalidateCleanupDirectory(parent);
                boolean exists = Files.exists(child.toPath(), LinkOption.NOFOLLOW_LINKS);
                revalidateCleanupDirectory(parent);
                return exists;
            } catch (RetryableCleanupException retryable) {
                throw retryable;
            } catch (IOException unavailable) {
                throw new RetryableCleanupException(
                        "Cleanup child existence cannot be established from its anchored parent.",
                        unavailable);
            }
        }
        SecureDirectoryStream<Path> secure = openVerifiedSecureDirectory(parent, operation);
        try {
            BasicFileAttributeView view = secure.getFileAttributeView(Paths.get(name),
                    BasicFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
            if (view == null) throw new IOException("Filesystem cannot inspect anchored child.");
            try {
                view.readAttributes();
                return true;
            } catch (NoSuchFileException absent) {
                return false;
            }
        } catch (RetryableCleanupException retryable) {
            throw retryable;
        } catch (IOException unavailable) {
            throw new RetryableCleanupException(
                    "Cleanup child existence cannot be established from its anchored parent.",
                    unavailable);
        } finally {
            try {
                closeCleanupDirectory(secure);
            } finally {
                revalidateCleanupDirectory(parent);
            }
        }
    }

    private static String readAnchoredBoundedStrictUtf8(AnchoredDirectory parent, String name,
                                                        int maxBytes, String role)
            throws IOException {
        File child = new File(parent.lexical.toFile(), name);
        requireGeneratedDirectChild(parent, child, role);
        byte[] bytes;
        if (testOnlyPathOperationsAllowed()) {
            try {
                beforeQueueDirectoryOperation("read-metadata", parent);
                revalidateCleanupDirectory(parent);
                BasicFileAttributes attributes = Files.readAttributes(child.toPath(),
                        BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (!attributes.isRegularFile()) {
                    throw new IOException("Unsafe or absent " + role + ": " + child);
                }
                Set<OpenOption> options = new HashSet<OpenOption>();
                options.add(StandardOpenOption.READ);
                options.add(LinkOption.NOFOLLOW_LINKS);
                try (SeekableByteChannel channel = Files.newByteChannel(child.toPath(), options)) {
                    bytes = readBoundedChannel(channel, maxBytes, role);
                }
                revalidateCleanupDirectory(parent);
            } catch (MalformedCleanupMetadataException malformed) {
                throw malformed;
            } catch (RetryableCleanupException retryable) {
                throw retryable;
            } catch (IOException unavailable) {
                throw new RetryableCleanupException(
                        "Cleanup metadata could not be read from its anchored directory.",
                        unavailable);
            }
            return decodeStrictUtf8(bytes, role);
        }
        SecureDirectoryStream<Path> secure = openVerifiedSecureDirectory(parent,
                "read-metadata");
        try {
            BasicFileAttributeView view = secure.getFileAttributeView(Paths.get(name),
                    BasicFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
            if (view == null || !view.readAttributes().isRegularFile()) {
                throw new IOException("Unsafe or absent " + role + ": " + child);
            }
            Set<OpenOption> options = new HashSet<OpenOption>();
            options.add(StandardOpenOption.READ);
            options.add(LinkOption.NOFOLLOW_LINKS);
            try (SeekableByteChannel channel = secure.newByteChannel(Paths.get(name), options)) {
                bytes = readBoundedChannel(channel, maxBytes, role);
            }
        } catch (MalformedCleanupMetadataException malformed) {
            throw malformed;
        } catch (RetryableCleanupException retryable) {
            throw retryable;
        } catch (IOException unavailable) {
            throw new RetryableCleanupException(
                    "Cleanup metadata could not be read from its anchored directory.",
                    unavailable);
        } finally {
            try {
                closeCleanupDirectory(secure);
            } finally {
                revalidateCleanupDirectory(parent);
            }
        }
        return decodeStrictUtf8(bytes, role);
    }

    private static byte[] readBoundedChannel(SeekableByteChannel channel, int maxBytes,
                                             String role) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(8192, maxBytes));
        ByteBuffer buffer = ByteBuffer.allocate(Math.min(8192, maxBytes + 1));
        int total = 0;
        while (true) {
            buffer.clear();
            int remaining = maxBytes - total + 1;
            if (remaining <= 0) {
                throw new MalformedCleanupMetadataException(
                        role + " exceeds the byte limit.", null);
            }
            if (buffer.capacity() > remaining) buffer.limit(remaining);
            int count = channel.read(buffer);
            if (count < 0) break;
            if (count == 0) continue;
            if (total > maxBytes - count) {
                throw new MalformedCleanupMetadataException(
                        role + " exceeds the byte limit.", null);
            }
            output.write(buffer.array(), 0, count);
            total += count;
        }
        return output.toByteArray();
    }

    private static String decodeStrictUtf8(byte[] bytes, String role) throws IOException {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException malformedUtf8) {
            throw new MalformedCleanupMetadataException(
                    role + " is not strict UTF-8.", malformedUtf8);
        }
    }

    private static final class MalformedCleanupMetadataException extends IOException {
        MalformedCleanupMetadataException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private static void deleteAnchoredChild(AnchoredDirectory parent, String name,
                                            String operation) throws IOException {
        File child = new File(parent.lexical.toFile(), name);
        requireGeneratedDirectChild(parent, child, "anchored delete");
        if (testOnlyPathOperationsAllowed()) {
            beforeQueueDirectoryOperation(operation, parent);
            revalidateCleanupDirectory(parent);
            Files.deleteIfExists(child.toPath());
            revalidateCleanupDirectory(parent);
            return;
        }
        SecureDirectoryStream<Path> secure = openVerifiedSecureDirectory(parent, operation);
        try {
            try {
                secure.deleteFile(Paths.get(name));
            } catch (NoSuchFileException absent) {
                // Idempotent deletion of the exact fixed ticket name.
            }
        } finally {
            try {
                closeCleanupDirectory(secure);
            } finally {
                revalidateCleanupDirectory(parent);
            }
        }
    }

    private static void moveAnchoredChild(AnchoredDirectory sourceParent, String sourceName,
                                          AnchoredDirectory targetParent, String targetName,
                                          String operation) throws IOException {
        try {
            requireGeneratedDirectChild(sourceParent,
                    new File(sourceParent.lexical.toFile(), sourceName), "anchored move source");
            requireGeneratedDirectChild(targetParent,
                    new File(targetParent.lexical.toFile(), targetName), "anchored move target");
            if (testOnlyPathOperationsAllowed()) {
                beforeQueueDirectoryOperation(operation + "-source", sourceParent);
                beforeQueueDirectoryOperation(operation + "-target", targetParent);
                revalidateCleanupDirectory(sourceParent);
                revalidateCleanupDirectory(targetParent);
                try {
                    Files.move(sourceParent.lexical.resolve(sourceName),
                            targetParent.lexical.resolve(targetName), StandardCopyOption.ATOMIC_MOVE);
                } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
                    throw new RetryableCleanupException(
                            "Atomic anchored cleanup move is unavailable.", unsupported);
                }
                revalidateCleanupDirectory(sourceParent);
                revalidateCleanupDirectory(targetParent);
                return;
            }
            SecureDirectoryStream<Path> source = openVerifiedSecureDirectory(sourceParent,
                    operation + "-source");
            try {
                SecureDirectoryStream<Path> target = openVerifiedSecureDirectory(targetParent,
                        operation + "-target");
                try {
                    source.move(Paths.get(sourceName), target, Paths.get(targetName));
                } finally {
                    closeCleanupDirectory(target);
                }
            } finally {
                try {
                    closeCleanupDirectory(source);
                } finally {
                    try {
                        revalidateCleanupDirectory(sourceParent);
                    } finally {
                        revalidateCleanupDirectory(targetParent);
                    }
                }
            }
        } catch (RetryableCleanupException retryable) {
            throw retryable;
        } catch (IOException unavailable) {
            throw new RetryableCleanupException(
                    "Anchored cleanup move could not be completed safely.", unavailable);
        }
    }

    @SuppressWarnings("unchecked")
    private static SecureDirectoryStream<Path> openVerifiedSecureDirectory(
            AnchoredDirectory directory, String operation) throws IOException {
        if (Boolean.FALSE.equals(stableFileIdentityOverrideForTest)) {
            throw new RetryableCleanupException(
                    "Stable handle-relative cleanup is unavailable; state is retained for retry.");
        }
        beforeQueueDirectoryOperation(operation, directory);
        revalidateCleanupDirectory(directory);
        DirectoryStream<Path> opened;
        try {
            opened = Files.newDirectoryStream(directory.lexical);
        } catch (IOException unavailable) {
            throw new RetryableCleanupException(
                    "Anchored cleanup directory handle cannot be opened.", unavailable);
        }
        if (!(opened instanceof SecureDirectoryStream)) {
            closeCleanupDirectory(opened);
            throw new RetryableCleanupException(
                    "Filesystem has no handle-relative directory operations; cleanup is retained.");
        }
        SecureDirectoryStream<Path> secure = (SecureDirectoryStream<Path>) opened;
        boolean verified = false;
        try {
            BasicFileAttributeView view = secure.getFileAttributeView(BasicFileAttributeView.class);
            BasicFileAttributes attributes = view == null ? null : view.readAttributes();
            if (attributes == null || directory.fileKey == null || attributes.fileKey() == null
                    || !directory.fileKey.equals(attributes.fileKey())
                    || attributes.creationTime().toMillis() != directory.creationMillis
                    || !attributes.isDirectory()) {
                throw new RetryableCleanupException(
                        "Opened directory handle does not match its immutable cleanup anchor.");
            }
            verified = true;
            return secure;
        } catch (RetryableCleanupException retryable) {
            throw retryable;
        } catch (IOException unavailable) {
            throw new RetryableCleanupException(
                    "Anchored cleanup directory handle cannot be verified.", unavailable);
        } finally {
            if (!verified) closeCleanupDirectory(secure);
        }
    }

    private static boolean forceSecureDirectoryHandle(SecureDirectoryStream<Path> secure)
            throws IOException {
        Set<StandardOpenOption> options = new HashSet<StandardOpenOption>();
        options.add(StandardOpenOption.READ);
        try (SeekableByteChannel channel = secure.newByteChannel(Paths.get("."), options)) {
            if (!(channel instanceof FileChannel)) return false;
            ((FileChannel) channel).force(true);
            return true;
        } catch (UnsupportedOperationException unsupported) {
            return false;
        } catch (IOException unavailable) {
            return false;
        }
    }

    private static boolean forceAnchoredDirectory(AnchoredDirectory directory, String operation)
            throws IOException {
        try {
            if (testOnlyPathOperationsAllowed()) {
                beforeQueueDirectoryOperation(operation, directory);
                revalidateCleanupDirectory(directory);
                boolean durable = forceDirectoryMetadata(directory.lexical.toFile());
                revalidateCleanupDirectory(directory);
                return durable;
            }
            SecureDirectoryStream<Path> secure = openVerifiedSecureDirectory(directory, operation);
            try {
                return forceSecureDirectoryHandle(secure);
            } finally {
                try {
                    closeCleanupDirectory(secure);
                } finally {
                    revalidateCleanupDirectory(directory);
                }
            }
        } catch (RetryableCleanupException retryable) {
            throw retryable;
        } catch (IOException unavailable) {
            throw new RetryableCleanupException(
                    "Cleanup directory durability barrier is unavailable.", unavailable);
        }
    }

    private static final class CleanupQueueState {
        long generation;
        long head;
        long tail;

        CleanupQueueState nextGeneration() throws IOException {
            if (generation == Long.MAX_VALUE || head < 0L || tail < head
                    || tail == Long.MAX_VALUE || tail - head > MAX_CLEANUP_QUEUE_SPAN) {
                throw new IOException("Deferred cleanup queue state overflow.");
            }
            CleanupQueueState next = new CleanupQueueState();
            next.generation = generation + 1L;
            next.head = head;
            next.tail = tail;
            return next;
        }

        String encode() {
            String records = "generation|" + generation + "\nhead|" + head
                    + "\ntail|" + tail + "\n";
            return "deconv-cleanup-state-v1\n" + records
                    + "checksum|" + sha256Hex(records) + "\n";
        }

        static CleanupQueueState parse(String value) throws IOException {
            String[] lines = value == null ? new String[0] : value.split("\\n", -1);
            if (lines.length != 6 || !"deconv-cleanup-state-v1".equals(lines[0])
                    || !lines[5].isEmpty()) throw new IOException("Malformed cleanup state.");
            String records = lines[1] + "\n" + lines[2] + "\n" + lines[3] + "\n";
            String[] checksum = lines[4].split("\\|", -1);
            if (checksum.length != 2 || !isSha256(checksum[1])
                    || !checksum[1].equals(sha256Hex(records))) {
                throw new IOException("Cleanup state checksum mismatch.");
            }
            CleanupQueueState state = new CleanupQueueState();
            try {
                state.generation = parseStateNumber(lines[1], "generation");
                state.head = parseStateNumber(lines[2], "head");
                state.tail = parseStateNumber(lines[3], "tail");
            } catch (NumberFormatException malformed) {
                throw new IOException("Malformed cleanup state number.", malformed);
            }
            if (state.generation < 0L || state.head < 0L || state.tail < state.head
                    || state.tail == Long.MAX_VALUE
                    || state.tail - state.head > MAX_CLEANUP_QUEUE_SPAN) {
                throw new IOException("Cleanup state is out of range.");
            }
            return state;
        }

        private static long parseStateNumber(String line, String key) {
            String prefix = key + "|";
            if (!line.startsWith(prefix)) throw new NumberFormatException(key);
            return Long.parseLong(line.substring(prefix.length()));
        }
    }

    private static final class CleanupTicket {
        final String family;
        final File payload;
        final RecoveryFingerprint expected;

        CleanupTicket(String family, File payload) {
            this(family, payload, null);
        }

        CleanupTicket(String family, File payload, RecoveryFingerprint expected) {
            this.family = family;
            this.payload = payload;
            this.expected = expected;
        }

        boolean isExactFile() {
            return expected != null;
        }

        String encode() {
            String records = "family|" + family + "\npayload|"
                    + Base64.getUrlEncoder().withoutPadding().encodeToString(
                            payload.toPath().toAbsolutePath().normalize().toString()
                                    .getBytes(StandardCharsets.UTF_8)) + "\n"
                    + "kind|" + (expected == null ? "TREE" : "EXACT") + "\n"
                    + "size|" + (expected == null ? "-" : String.valueOf(expected.size)) + "\n"
                    + "hash|" + (expected == null ? "-" : expected.contentHash) + "\n";
            return "deconv-cleanup-ticket-v2\n" + records
                    + "checksum|" + sha256Hex(records) + "\n";
        }

        static CleanupTicket parse(String value) throws IOException {
            String[] lines = value == null ? new String[0] : value.split("\\n", -1);
            boolean legacy = lines.length == 5
                    && "deconv-cleanup-ticket-v1".equals(lines[0]);
            boolean current = lines.length == 8
                    && "deconv-cleanup-ticket-v2".equals(lines[0]);
            if ((!legacy && !current) || !lines[lines.length - 1].isEmpty()) {
                throw new IOException("Malformed cleanup ticket.");
            }
            String records = lines[1] + "\n" + lines[2] + "\n"
                    + (current ? lines[3] + "\n" + lines[4] + "\n" + lines[5] + "\n" : "");
            String[] checksum = lines[current ? 6 : 3].split("\\|", -1);
            if (checksum.length != 2 || !isSha256(checksum[1])
                    || !checksum[1].equals(sha256Hex(records))) {
                throw new IOException("Cleanup ticket checksum mismatch.");
            }
            if (!lines[1].startsWith("family|") || !lines[2].startsWith("payload|")) {
                throw new IOException("Malformed cleanup ticket fields.");
            }
            String family = lines[1].substring("family|".length());
            if (!isSha256(family)) throw new IOException("Invalid cleanup ticket family.");
            try {
                String payload = new String(Base64.getUrlDecoder().decode(
                        lines[2].substring("payload|".length())), StandardCharsets.UTF_8);
                RecoveryFingerprint expected = null;
                if (current) {
                    if (!lines[3].startsWith("kind|") || !lines[4].startsWith("size|")
                            || !lines[5].startsWith("hash|")) {
                        throw new IOException("Malformed cleanup ticket fingerprint fields.");
                    }
                    String kind = lines[3].substring("kind|".length());
                    String size = lines[4].substring("size|".length());
                    String hash = lines[5].substring("hash|".length());
                    if ("TREE".equals(kind)) {
                        if (!"-".equals(size) || !"-".equals(hash)) {
                            throw new IOException("Tree cleanup ticket has a fingerprint.");
                        }
                    } else if ("EXACT".equals(kind)) {
                        if (!isSha256(hash)) {
                            throw new IOException("Invalid cleanup ticket fingerprint.");
                        }
                        expected = new RecoveryFingerprint(parseNonNegativeLong(size), hash);
                    } else {
                        throw new IOException("Invalid cleanup ticket kind.");
                    }
                }
                return new CleanupTicket(family, new File(payload), expected);
            } catch (IllegalArgumentException malformed) {
                throw new IOException("Malformed cleanup ticket payload.", malformed);
            }
        }
    }

    private static void ensurePlainCleanupDirectory(MigrationTrustAnchor cleanupAnchor,
                                                    File parent, File directory)
            throws IOException {
        cleanupAnchor.revalidate();
        if (safeNode(cleanupAnchor, parent) == null
                || !Files.isDirectory(parent.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Unsafe deferred cleanup queue parent.");
        }
        if (Files.exists(directory.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            if (safeNode(cleanupAnchor, directory) == null
                    || !Files.isDirectory(directory.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Unsafe deferred cleanup queue path.");
            }
            return;
        }
        if (!testOnlyPathOperationsAllowed()) {
            throw new RetryableCleanupException(
                    "Java 8 exposes no handle-relative directory creation; cleanup queue "
                            + "namespace is retained for a capable retry.");
        }
        cleanupAnchor.revalidate();
        Files.createDirectory(directory.toPath());
        if (safeNode(cleanupAnchor, directory) == null
                || !Files.isDirectory(directory.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Could not create confined deferred cleanup queue.");
        }
    }

    private static void writeDeferredCleanupMarker(File transaction) throws IOException {
        if (transaction == null || !isContainedPlainDirectory(transaction.getParentFile(),
                transaction)) {
            throw new IOException("Unsafe pre-authority cleanup transaction: " + transaction);
        }
        File marker = new File(transaction, DEFERRED_CLEANUP_MARKER);
        File temp = new File(transaction, DEFERRED_CLEANUP_MARKER + ".tmp");
        try {
            Files.write(temp.toPath(),
                    DEFERRED_CLEANUP_MARKER_CONTENT.getBytes(StandardCharsets.UTF_8));
            forceRegularFile(temp);
            IoUtils.commitReplacingSmallFile(temp.toPath(), marker.toPath());
            forceRegularFile(marker);
            if (!forceDirectoryMetadata(transaction)) {
                throw new IOException("Pre-authority cleanup marker directory entry is not durable.");
            }
            if (!DEFERRED_CLEANUP_MARKER_CONTENT.equals(
                    readBoundedStrictUtf8(marker, 128, "pre-authority cleanup marker"))) {
                throw new IOException("Pre-authority cleanup marker verification failed.");
            }
        } finally {
            Files.deleteIfExists(temp.toPath());
        }
    }

    private static boolean isDeferredCleanupTransaction(File transaction) {
        if (transaction == null) return false;
        // A marker can only classify state that never reached an authority attempt. If either
        // journal path exists, preserve the conservative recovery/quarantine path even if an
        // unrelated or externally written marker is also present.
        if (Files.exists(new File(transaction, "recovery.journal").toPath(),
                LinkOption.NOFOLLOW_LINKS)
                || Files.exists(new File(transaction, "recovery.journal.tmp").toPath(),
                        LinkOption.NOFOLLOW_LINKS)) return false;
        File marker = new File(transaction, DEFERRED_CLEANUP_MARKER);
        try {
            return DEFERRED_CLEANUP_MARKER_CONTENT.equals(
                    readBoundedStrictUtf8(marker, 128, "pre-authority cleanup marker"));
        } catch (IOException absentOrMalformed) {
            return false;
        }
    }

    private static File relocateDeferredCleanup(File transaction,
                                                MigrationTrustAnchor cleanupAnchor)
            throws IOException {
        cleanupAnchor.revalidate();
        if (transaction == null || !Files.exists(transaction.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            return transaction;
        }
        if (!isDeferredCleanupTransaction(transaction)) {
            throw new IOException("Pre-authority cleanup source lacks its durable marker: "
                    + transaction);
        }
        File migrationRoot = transaction.getParentFile();
        if (migrationRoot == null || safeNode(cleanupAnchor, transaction) == null) {
            throw new IOException("Unsafe pre-authority cleanup source: " + transaction);
        }
        File cleanupRoot = new File(migrationRoot, DEFERRED_CLEANUP_DIRECTORY);
        boolean cleanupRootCreated = !Files.exists(cleanupRoot.toPath(), LinkOption.NOFOLLOW_LINKS);
        ensurePlainCleanupDirectory(cleanupAnchor, migrationRoot, cleanupRoot);
        if (cleanupRootCreated && (!forceDirectoryMetadata(cleanupRoot)
                || !forceDirectoryMetadata(migrationRoot))) {
            throw new IOException("Deferred cleanup namespace is not durable.");
        }
        String transactionName = transaction.getName();
        int separator = transactionName.indexOf('-');
        String familyToken = separator < 0 ? "" : transactionName.substring(0, separator);
        if (!isSha256(familyToken)) {
            throw new IOException("Deferred cleanup transaction has no valid family identity.");
        }
        return enqueueDeferredCleanup(cleanupAnchor, transaction, familyToken);
    }

    private static File retainOpaqueTransaction(MigrationTrustAnchor anchor, File transaction,
                                                 String family) throws IOException {
        anchor.revalidate();
        if (transaction == null || !Files.exists(transaction.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            return transaction;
        }
        File migration = anchor.migrationRoot.toFile();
        requireLexicallyBelow(migration, transaction);
        if (!transaction.toPath().toAbsolutePath().normalize().getParent().equals(
                migration.toPath().toAbsolutePath().normalize())
                || safeNode(anchor, transaction) == null || !isSha256(family)) {
            throw new IOException("Unsafe opaque recovery retention source.");
        }
        File cache = migration.getAbsoluteFile().getParentFile();
        if (cache == null || !anchor.migrationRoot.getParent().equals(
                cache.toPath().toAbsolutePath().normalize())) {
            throw new IOException("Opaque retention escapes the fixed cache root.");
        }
        File retainedRoot = new File(cache, MIGRATION_RETAINED_DIRECTORY);
        File retainedFamily = new File(retainedRoot, family);
        OpaqueRetentionMoveTestHook hook = opaqueRetentionMoveHookForTest;
        if (hook != null) hook.afterValidationBeforeRetention(retainedRoot, retainedFamily);
        anchor.revalidate();
        // SecureDirectoryStream binds the source parent, but Java 8 has no conditional
        // compare-inode-and-rename operation for the direct child. A replacement can therefore
        // win between validation and either Files.move or SecureDirectoryStream.move. The only
        // truthful, race-free retention action is no rename: the authenticated transaction stays
        // under its original confined .migration name and blocks another promotion until a
        // capable cleanup implementation is available.
        throw new RetryableCleanupException(
                "Opaque recovery transaction remains at its confined migration source; Java 8 "
                        + "cannot conditionally rename the validated source child.");
    }

    static File retainOpaqueTransactionForTest(File rootDir, File transaction, String family)
            throws IOException {
        File migration = new File(cacheDir(rootDir), ".migration");
        MigrationTrustAnchor anchor = MigrationTrustAnchor.capture(rootDir, migration);
        return retainOpaqueTransaction(anchor, transaction, family);
    }

    private static boolean recoverAbandonedTransaction(File rootDir, ArtifactIdentity current,
                                                       File transaction, File quarantineRoot,
                                                       MigrationTrustAnchor cleanupAnchor)
            throws IOException {
        if (!testOnlyPathOperationsAllowed()) {
            throw new RetryableCleanupException(
                    "Lexical recovery readers cannot consume a handle-bound transaction.");
        }
        Path transactionPath = transaction.toPath().toAbsolutePath().normalize();
        AnchoredDirectory transactionAnchor = AnchoredDirectory.capturePlain(transactionPath,
                transactionPath.toRealPath());
        beforeRecoveryDirectoryOperation("read-transaction", transaction);
        cleanupAnchor.revalidate();
        transactionAnchor.revalidate();
        SafeNode boundTransaction = safeNode(cleanupAnchor, transaction);
        if (boundTransaction == null || !boundTransaction.attributes.isDirectory()) {
            throw new RetryableCleanupException(
                    "Recovery transaction identity changed before it could be read.");
        }
        File journalFile = new File(transaction, "recovery.journal");
        File orphanTemp = new File(transaction, "recovery.journal.tmp");
        if (!Files.isRegularFile(journalFile.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            File[] contents = transaction.listFiles();
            if (contents != null && contents.length == 0) {
                Files.deleteIfExists(transaction.toPath());
                forceDirectoryMetadata(transaction.getParentFile());
                return false;
            }
            String reason = Files.exists(orphanTemp.toPath(), LinkOption.NOFOLLOW_LINKS)
                    ? "orphan recovery.journal.tmp without an authoritative journal"
                    : "missing authoritative recovery.journal";
            quarantineMalformedTransaction(rootDir, current, transaction, quarantineRoot,
                    reason, null);
            return true;
        }
        JournalDocument document;
        try {
            document = JournalDocument.read(journalFile);
        } catch (IOException malformed) {
            quarantineMalformedTransaction(rootDir, current, transaction, quarantineRoot,
                    "malformed or oversized authoritative recovery journal", malformed);
            return true;
        }
        if (document.isVersion("deconv-migration-v2")) {
            try {
                LegacyV2Journal.validate(rootDir, current, transaction, document.lines);
                // V2 authenticates desired publication bytes and retirement sources, but not the
                // manifest or backup bytes that rollback would consume. Those fingerprints cannot
                // be reconstructed after a crash without trusting mutable transaction files. Keep
                // the one independently manifest-proven live generation and quarantine v2 rather
                // than guessing forward or backward. Consequently no v3 journal is synthesized.
                quarantineMalformedTransaction(rootDir, current, transaction, quarantineRoot,
                        "valid legacy v2 journal lacks authenticated manifest/backup fingerprints",
                        null);
            } catch (IOException corruptV2) {
                quarantineMalformedTransaction(rootDir, current, transaction, quarantineRoot,
                        "corrupt legacy v2 recovery journal", corruptV2);
            }
            // The quarantine diagnostic is not authenticated authority.  Even a sole intact prior
            // generation must remain blocked after detaching v2 bytes; an operator (or a future
            // authenticated recovery format) must resolve the quarantine before promotion.
            return true;
        }
        RecoveryJournal journal;
        try {
            journal = RecoveryJournal.read(rootDir, current, transaction, journalFile, document);
        } catch (IOException malformed) {
            quarantineMalformedTransaction(rootDir, current, transaction, quarantineRoot,
                    "malformed authoritative recovery journal", malformed);
            return true;
        }
        try {
            return recoverValidatedTransaction(rootDir, current, transaction, journal,
                    cleanupAnchor);
        } catch (RetryableCleanupException cleanupPending) {
            // Authenticated recovery succeeded, but safe cleanup cannot be expressed by this
            // provider. Leave the exact transaction source in place, keep family reads available,
            // and block another promotion until a future capable retry.
            return true;
        } catch (IOException integrityFailure) {
            throw quarantineAuthoritativeTransaction(rootDir, current, transaction,
                    "journaled recovery artifact or live target failed fingerprint validation",
                    integrityFailure);
        }
    }

    private static boolean recoverValidatedTransaction(File rootDir, ArtifactIdentity current,
                                                       File transaction, RecoveryJournal journal,
                                                       MigrationTrustAnchor cleanupAnchor)
            throws IOException {
        if (!journal.desiredCurrentGenerationComplete(rootDir, current)) {
            journal.validatePresentImmutableArtifacts();
            journal.validateImmutableArtifacts(current);
            journal.validateRollbackTargetClassification();
            journal.validateRetirementClassification(true);
            restorePriorFallback(rootDir, journal);
            recoverDesiredCurrentFamily(rootDir, transaction, journal);
            if (!journal.desiredCurrentGenerationComplete(rootDir, current)) {
                throw new IOException("Forward recovery did not reproduce the journaled generation.");
            }
        }

        journal.validateRetirementClassification(true);
        if (!forceRecoveryAuthority(rootDir, transaction, journal)) {
            // COMPLETE_UNDURABLE is deliberately stable. Keep the exact v3 journal, desired bytes,
            // backups, and prior family so a crash/reversion can be repaired on the next lock.
            restorePriorFallback(rootDir, journal);
            if (!journal.desiredCurrentGenerationComplete(rootDir, current)) {
                throw new IOException("Undurable current generation changed during validation.");
            }
            return true;
        }

        for (RecoveryRetirement retirement : journal.retirements) {
            deleteIfExact(cleanupAnchor, current.familyLockToken(),
                    retirement.original, retirement);
            deleteIfExact(cleanupAnchor, current.familyLockToken(),
                    retirement.hidden, retirement);
        }
        cleanupRecoveredTransaction(cleanupAnchor, transaction, journalFileFor(transaction));
        return false;
    }

    private static void restorePriorFallback(File rootDir, RecoveryJournal journal)
            throws IOException {
        for (RecoveryRetirement retirement : journal.retirements) {
            if (retirement.matches(retirement.original)) continue;
            if (retirement.validate(retirement.hidden) == null) continue;
            IoUtils.mustMkdirs(retirement.original.getParentFile());
            forceArtifactDirectoryChain(rootDir, retirement.original);
            publishAuthenticatedRecoveryFile(retirement.hidden, retirement,
                    retirement.original, false, null, null,
                    retirement.hidden.getParentFile(), false,
                    retirement.role + " fallback restore");
        }
    }

    private static void recoverDesiredCurrentFamily(File rootDir, File transaction,
                                                    RecoveryJournal journal) throws IOException {
        for (RecoveryPublication publication : journal.publications) {
            if (publication.matches(publication.target)) continue;
            IoUtils.mustMkdirs(publication.target.getParentFile());
            forceArtifactDirectoryChain(rootDir, publication.target);
            publishAuthenticatedRecoveryFile(publication.desired, publication,
                    publication.target, publication.targetExisted, publication.backup,
                    publication.backupFingerprint, transaction,
                    false, publication.role + " forward recovery");
        }
        forceArtifactDirectoryChain(rootDir, journal.manifestTarget);
        publishAuthenticatedRecoveryFile(journal.desiredManifest,
                journal.desiredManifestFingerprint, journal.manifestTarget,
                journal.manifestExisted, journal.manifestBackup,
                journal.manifestBackupFingerprint, transaction,
                true, "manifest forward recovery");
    }

    /**
     * Publish only a privately-created, journal-authenticated snapshot.  The previous generation
     * is independently snapshotted before the first target mutation, so every failed or ambiguous
     * replacement can put the live name back on its authenticated prior bytes.  The journal's
     * desired/backup files remain untouched until the new target has itself been authenticated.
     */
    private static void publishAuthenticatedRecoveryFile(
            File desiredSource, RecoveryFingerprint desiredFingerprint, File target,
            boolean priorExisted, File priorSource, RecoveryFingerprint priorFingerprint,
            File retainedDirectory, boolean smallFile, String role) throws IOException {
        File actionDirectory = target.getAbsoluteFile().getParentFile();
        File desiredSnapshot = null;
        File priorSnapshot = null;
        boolean published = false;
        boolean targetMutationStarted = false;
        try {
            desiredSnapshot = createAuthenticatedRecoverySnapshot(desiredSource,
                    desiredFingerprint, actionDirectory, role + " desired");
            if (priorExisted) {
                if (priorSource == null || priorFingerprint == null) {
                    throw new IOException("Missing authenticated prior source for " + role + '.');
                }
                priorSnapshot = createAuthenticatedRecoverySnapshot(priorSource,
                        priorFingerprint, actionDirectory, role + " prior");
            }

            targetMutationStarted = true;
            if (smallFile) {
                IoUtils.commitReplacingSmallFile(desiredSnapshot.toPath(), target.toPath());
            } else {
                IoUtils.moveReplacing(desiredSnapshot.toPath(), target.toPath());
            }
            desiredSnapshot = null;
            forceFileAndParent(target);
            ValidatedRecoveryFile targetIdentity = validateExactFingerprint(target,
                    desiredFingerprint, role + " target");
            beforeExactFileAction(target, targetIdentity,
                    role + " target changed after publication");
            published = true;
        } catch (IOException publicationFailure) {
            if (targetMutationStarted) {
                try {
                    if (priorExisted) {
                        // A failure after mutation may have left either the intact prior target or
                        // ambiguous new bytes. Never disturb an already-authenticated prior target.
                        if (priorFingerprint.validate(target) == null) {
                            if (priorSnapshot == null
                                    || priorFingerprint.validate(priorSnapshot) == null) {
                                publicationFailure.addSuppressed(new IOException(
                                        "No prepared authenticated prior snapshot remains for "
                                                + role + "; ambiguous target was retained."));
                            } else {
                                parkFailedRecoveryTarget(target, retainedDirectory, role);
                                restoreAuthenticatedRecoveryTarget(priorSnapshot,
                                        priorFingerprint, target, role);
                                priorSnapshot = null;
                            }
                        }
                    } else {
                        parkFailedRecoveryTarget(target, retainedDirectory, role);
                    }
                } catch (IOException restoreFailure) {
                    publicationFailure.addSuppressed(restoreFailure);
                }
            }
            throw publicationFailure;
        } finally {
            // These are private CREATE_NEW snapshots, never journal authority or external bytes.
            if (desiredSnapshot != null) Files.deleteIfExists(desiredSnapshot.toPath());
            if (priorSnapshot != null) Files.deleteIfExists(priorSnapshot.toPath());
        }
        if (!published) throw new IOException("Recovery publication did not complete for " + role);
    }

    private static File createAuthenticatedRecoverySnapshot(
            File source, RecoveryFingerprint expected, File privateDirectory, String role)
            throws IOException {
        if (privateDirectory == null
                || !Files.isDirectory(privateDirectory.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Missing private recovery action directory for " + role + '.');
        }
        ValidatedRecoveryFile sourceIdentity = validateExactFingerprint(source, expected,
                role + " source");
        beforeExactFileAction(source, sourceIdentity, role + " source changed before snapshot");
        File snapshot = new File(privateDirectory, ".deconv-action-"
                + UUID.randomUUID().toString() + ".tmp");
        boolean complete = false;
        try {
            try (FileChannel input = FileChannel.open(source.toPath(), StandardOpenOption.READ,
                         LinkOption.NOFOLLOW_LINKS);
                 FileChannel output = FileChannel.open(snapshot.toPath(),
                         StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE,
                         LinkOption.NOFOLLOW_LINKS)) {
                byte[] buffer = new byte[8192];
                long remaining = expected.size;
                while (remaining > 0L) {
                    int requested = (int) Math.min((long) buffer.length, remaining);
                    RecoverySnapshotIoTestHook hook = recoverySnapshotIoHookForTest;
                    if (hook != null) hook.beforeRead(source, requested);
                    ByteBuffer bytes = ByteBuffer.wrap(buffer, 0, requested);
                    int count = input.read(bytes);
                    if (count < 0) {
                        throw new IOException("Recovery snapshot source was truncated for "
                                + role + '.');
                    }
                    if (count == 0) continue;
                    bytes.flip();
                    bytes.limit(count);
                    while (bytes.hasRemaining()) {
                        int written = output.write(bytes);
                        if (written < 0) throw new IOException(
                                "Recovery snapshot output closed for " + role + '.');
                        if (written == 0) continue;
                        hook = recoverySnapshotIoHookForTest;
                        if (hook != null) hook.afterWrite(snapshot, written);
                    }
                    remaining -= count;
                }
                RecoverySnapshotIoTestHook hook = recoverySnapshotIoHookForTest;
                if (hook != null) hook.beforeRead(source, 1);
                if (input.read(ByteBuffer.allocate(1)) >= 0 || input.size() != expected.size) {
                    throw new IOException("Recovery snapshot source grew beyond its authenticated "
                            + "length for " + role + '.');
                }
                if (output.size() != expected.size) {
                    throw new IOException("Recovery snapshot output length mismatch for " + role + '.');
                }
                output.force(true);
            }
            forceRegularFile(snapshot);
            ValidatedRecoveryFile snapshotIdentity = validateExactFingerprint(snapshot, expected,
                    role + " private snapshot");
            beforeExactFileAction(snapshot, snapshotIdentity,
                    role + " private snapshot changed before binding");
            complete = true;
            return snapshot;
        } finally {
            if (!complete) Files.deleteIfExists(snapshot.toPath());
        }
    }

    private static void restoreAuthenticatedRecoveryTarget(
            File preparedPrior, RecoveryFingerprint priorFingerprint,
            File target, String role) throws IOException {
        if (preparedPrior == null || priorFingerprint.validate(preparedPrior) == null) {
            throw new IOException("Prepared authenticated prior snapshot is unavailable for "
                    + role + '.');
        }
        File restoration = preparedPrior;
        boolean restored = false;
        try {
            IoUtils.moveReplacing(restoration.toPath(), target.toPath());
            forceFileAndParent(target);
            requireExactFingerprint(target, priorFingerprint, role + " restored prior target");
            restored = true;
        } finally {
            if (!restored) Files.deleteIfExists(restoration.toPath());
        }
    }

    /** Restore a previously absent target without deleting unrecognised bytes. */
    private static void parkFailedRecoveryTarget(File target, File privateDirectory, String role)
            throws IOException {
        if (!Files.exists(target.toPath(), LinkOption.NOFOLLOW_LINKS)) return;
        File retained = new File(privateDirectory, ".deconv-rejected-"
                + UUID.randomUUID().toString());
        try {
            Files.move(target.toPath(), retained.toPath(), StandardCopyOption.ATOMIC_MOVE);
            forceDirectoryMetadata(target.getParentFile());
            forceDirectoryMetadata(privateDirectory);
        } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
            throw new IOException("Cannot safely restore absent recovery target for " + role,
                    unsupported);
        }
    }

    private static boolean forceRecoveryAuthority(File rootDir, File transaction,
                                                  RecoveryJournal journal) throws IOException {
        File cacheRoot = cacheDir(rootDir);
        boolean supported = forceRecoveryFile(journalFileFor(transaction), cacheRoot);
        supported &= forceRecoveryFile(journal.desiredManifest, cacheRoot);
        supported &= forceRecoveryFile(journal.manifestBackup, cacheRoot);
        for (RecoveryPublication publication : journal.publications) {
            supported &= forceRecoveryFile(publication.desired, cacheRoot);
            supported &= forceRecoveryFile(publication.backup, cacheRoot);
            forceRegularFile(publication.target);
            supported &= forceArtifactDirectoryChain(rootDir, publication.target);
        }
        for (RecoveryRetained retained : journal.retained) {
            forceRegularFile(retained.target);
            supported &= forceArtifactDirectoryChain(rootDir, retained.target);
        }
        forceRegularFile(journal.manifestTarget);
        supported &= forceArtifactDirectoryChain(rootDir, journal.manifestTarget);
        for (RecoveryRetirement retirement : journal.retirements) {
            forceIfPresent(retirement.original);
            supported &= forceRecoveryFile(retirement.hidden, cacheRoot);
            if (retirement.original.getParentFile().isDirectory()) {
                supported &= forceArtifactDirectoryChain(rootDir, retirement.original);
            }
        }
        return supported;
    }

    private static boolean forceRecoveryFile(File file, File recoveryRoot) throws IOException {
        if (file == null || !Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)) return true;
        forceRegularFile(file);
        return forceDirectoryChain(file.getParentFile(), recoveryRoot);
    }

    private static boolean inspectQuarantinedTransactions(File rootDir, ArtifactIdentity current,
                                                           File quarantineRoot) throws IOException {
        File[] quarantined = quarantineRoot.listFiles();
        if (quarantined == null) {
            throw new IOException("Could not enumerate deconvolution migration quarantine: "
                    + quarantineRoot);
        }
        String prefix = current.familyLockToken() + "-";
        boolean found = false;
        for (File transaction : quarantined) {
            if (transaction == null || !transaction.getName().startsWith(prefix)) continue;
            if (!isContainedPlainDirectory(quarantineRoot, transaction)) {
                throw new IOException("Unsafe linked deconvolution quarantine transaction: "
                        + transaction);
            }
            FamilyGenerationState state = inspectFamilyGenerations(rootDir, current);
            if (!state.hasExactlyOneReadableGeneration()) {
                throw unsafeQuarantineFailure(transaction, state, null);
            }
            // QUARANTINE.txt is a human-readable diagnostic, not authenticated recovery
            // authority.  Its text must never re-enable promotion of a quarantined family.
            found = true;
        }
        return found;
    }

    private static void quarantineMalformedTransaction(File rootDir, ArtifactIdentity current,
                                                       File transaction, File quarantineRoot,
                                                       String reason, IOException parseFailure)
            throws IOException {
        if (!testOnlyPathOperationsAllowed()) {
            throw new RetryableCleanupException(
                    "Malformed recovery transaction remains at its handle-bound source; "
                            + "lexical quarantine is unavailable.", parseFailure);
        }
        Path transactionPath = transaction.toPath().toAbsolutePath().normalize();
        AnchoredDirectory transactionAnchor = AnchoredDirectory.capturePlain(transactionPath,
                transactionPath.toRealPath());
        beforeRecoveryDirectoryOperation("quarantine-malformed", transaction);
        transactionAnchor.revalidate();
        FamilyGenerationState state = inspectFamilyGenerations(rootDir, current);
        String stagedState = manifestIdentityDescription(
                new File(transaction, "desired-manifest.json"), current);
        String backupState = manifestIdentityDescription(
                new File(transaction, "manifest-backup.json"), current);
        IoUtils.mustMkdirs(quarantineRoot);
        if (!isContainedPlainDirectory(new File(cacheDir(rootDir), ".migration"), quarantineRoot)) {
            throw new IOException("Could not create a safe deconvolution migration quarantine.");
        }
        File quarantined = new File(quarantineRoot,
                transaction.getName() + "-" + UUID.randomUUID().toString());
        moveTransactionDirectory(transaction, quarantined);
        String diagnostic = "Deconvolution migration transaction quarantined.\n"
                + "Reason: " + reason + "\n"
                + "Current generation: " + state.currentDescription + "\n"
                + "Prior generation: " + state.priorDescription + "\n"
                + "Staged manifest: " + stagedState + "\n"
                + "Manifest backup: " + backupState + "\n"
                + "Action: retain exactly one complete manifest-backed current/prior generation, "
                + "then retry access; do not combine transaction files manually.\n";
        writeQuarantineDiagnostic(quarantined, diagnostic);
        if (!state.hasExactlyOneReadableGeneration()) {
            throw unsafeQuarantineFailure(quarantined, state, parseFailure);
        }
    }

    private static IOException quarantineAuthoritativeTransaction(
            File rootDir, ArtifactIdentity current, File transaction,
            String reason, IOException cause) {
        if (!testOnlyPathOperationsAllowed()) {
            return new IOException("Authoritative deconvolution recovery bytes remain at their "
                    + "confined source because handle-bound quarantine is unavailable. "
                    + "No recovery path was moved or deleted.", cause);
        }
        try {
            Path transactionPath = transaction.toPath().toAbsolutePath().normalize();
            AnchoredDirectory transactionAnchor = AnchoredDirectory.capturePlain(transactionPath,
                    transactionPath.toRealPath());
            beforeRecoveryDirectoryOperation("quarantine-authoritative", transaction);
            transactionAnchor.revalidate();
            FamilyGenerationState state = inspectFamilyGenerations(rootDir, current);
            File quarantineRoot = new File(new File(cacheDir(rootDir), ".migration"), ".quarantine");
            IoUtils.mustMkdirs(quarantineRoot);
            File quarantined = new File(quarantineRoot,
                    transaction.getName() + "-" + UUID.randomUUID().toString());
            moveTransactionDirectory(transaction, quarantined);
            writeQuarantineDiagnostic(quarantined,
                    "Deconvolution migration transaction quarantined.\nReason: " + reason
                            + "\nCurrent generation: " + state.currentDescription
                            + "\nPrior generation: " + state.priorDescription
                            + "\nAction: do not edit recovery bytes; retain exactly one complete "
                            + "manifest-backed generation and retry.\n");
            return unsafeQuarantineFailure(quarantined, state, cause);
        } catch (IOException quarantineFailure) {
            cause.addSuppressed(quarantineFailure);
            return cause;
        }
    }

    private static IOException unsafeQuarantineFailure(File quarantined,
                                                        FamilyGenerationState state,
                                                        IOException cause) {
        String message = "Unsafe deconvolution migration recovery was quarantined at "
                + quarantined.getAbsolutePath() + ". Current generation: "
                + state.currentDescription + "; prior generation: " + state.priorDescription
                + ". Retain exactly one complete manifest-backed generation and retry; "
                + "no pixels or manifests were mixed or deleted.";
        return cause == null ? new IOException(message) : new IOException(message, cause);
    }

    private static void moveTransactionDirectory(File transaction, File quarantined)
            throws IOException {
        if (!testOnlyPathOperationsAllowed()) {
            throw new RetryableCleanupException(
                    "Path-based recovery transaction moves are disabled.");
        }
        try {
            Files.move(transaction.toPath(), quarantined.toPath(), StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicFailure) {
            // Some Windows providers report an error after the directory rename already became
            // visible. Treat only the exact source-absent/destination-directory postcondition as
            // the committed move; otherwise make the documented ordinary same-volume attempt.
            if (Files.exists(transaction.toPath(), LinkOption.NOFOLLOW_LINKS)
                    || !Files.isDirectory(quarantined.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                try {
                    Files.move(transaction.toPath(), quarantined.toPath());
                } catch (IOException ordinaryFailure) {
                    ordinaryFailure.addSuppressed(atomicFailure);
                    throw ordinaryFailure;
                }
            }
        }
        forceDirectoryMetadata(quarantined.getParentFile());
        forceDirectoryMetadata(transaction.getParentFile());
    }

    private static void writeQuarantineDiagnostic(File quarantined, String diagnostic)
            throws IOException {
        File target = new File(quarantined, "QUARANTINE.txt");
        File temp = new File(quarantined, "QUARANTINE.txt.tmp");
        try {
            Files.write(temp.toPath(), diagnostic.getBytes(StandardCharsets.UTF_8));
            IoUtils.commitReplacingSmallFile(temp.toPath(), target.toPath());
        } finally {
            Files.deleteIfExists(temp.toPath());
        }
    }

    private static FamilyGenerationState inspectFamilyGenerations(File rootDir,
                                                                  ArtifactIdentity current) {
        ArtifactIdentity prior = current == null ? null : current.priorWindowsV3Identity();
        boolean currentReadable = isCompleteReadableGeneration(rootDir, current);
        boolean priorReadable = isCompleteReadableGeneration(rootDir, prior);
        return new FamilyGenerationState(currentReadable, priorReadable,
                generationDescription(rootDir, current, currentReadable),
                generationDescription(rootDir, prior, priorReadable));
    }

    private static boolean isCompleteReadableGeneration(File rootDir, ArtifactIdentity identity) {
        if (rootDir == null || identity == null) return false;
        File manifestFile = manifestFile(rootDir, identity);
        if (!Files.isRegularFile(manifestFile.toPath(), LinkOption.NOFOLLOW_LINKS)) return false;
        DeconvManifest manifest = DeconvManifest.load(manifestFile);
        if (!manifest.matchesArtifact(identity)) return false;
        DeconvManifest.SourceFingerprint source = sourceFingerprint(identity);
        boolean hasPixels = false;
        for (Map.Entry<Integer, DeconvManifest.ChannelEntry> channel
                : manifest.channels().entrySet()) {
            if (channel.getKey() == null || channel.getValue() == null) return false;
            int index = channel.getKey().intValue();
            File pixels = deconvFile(rootDir, identity, index);
            if (!Files.isRegularFile(pixels.toPath(), LinkOption.NOFOLLOW_LINKS)
                    || !manifest.isChannelFresh(index, null, source, identity)) return false;
            hasPixels = true;
        }
        if (manifest.merged() != null) {
            File merged = mergedDeconvFile(rootDir, identity);
            if (!Files.isRegularFile(merged.toPath(), LinkOption.NOFOLLOW_LINKS)
                    || !manifest.isMergedFresh(source,
                            manifest.merged().channelParamsHashes.keySet(), null, identity)) {
                return false;
            }
            hasPixels = true;
        }
        return hasPixels;
    }

    private static boolean isCompleteReadableGeneration(File rootDir, ArtifactIdentity identity,
                                                        RecoveryJournal authority)
            throws IOException {
        if (authority == null || !isCompleteReadableGeneration(rootDir, identity)
                || !authority.desiredManifestFingerprint.matches(
                        manifestFile(rootDir, identity))) return false;
        DeconvManifest manifest = DeconvManifest.load(manifestFile(rootDir, identity));
        for (Integer channel : manifest.channels().keySet()) {
            if (channel == null || !authority.desiredTargetMatches(
                    deconvFile(rootDir, identity, channel.intValue()))) return false;
        }
        return manifest.merged() == null || authority.desiredTargetMatches(
                mergedDeconvFile(rootDir, identity));
    }

    private static String generationDescription(File rootDir, ArtifactIdentity identity,
                                                boolean readable) {
        if (identity == null) return "not applicable";
        File manifest = manifestFile(rootDir, identity);
        if (readable) return "complete " + identity.artifactKey;
        return (manifest.isFile() ? "incomplete/unvouched " : "absent ") + identity.artifactKey;
    }

    private static String manifestIdentityDescription(File manifestFile,
                                                      ArtifactIdentity expectedCurrent) {
        if (!Files.isRegularFile(manifestFile.toPath(), LinkOption.NOFOLLOW_LINKS)) return "absent";
        DeconvManifest manifest;
        try {
            // Transaction manifests intentionally have staging/backup names, so use the parser
            // directly instead of DeconvManifest.load's final-filename identity guard.
            manifest = DeconvManifest.readBounded(manifestFile);
        } catch (IOException unreadable) {
            return "unreadable/unrecognised identity";
        }
        if (manifest.matchesArtifact(expectedCurrent)) return "current identity";
        ArtifactIdentity prior = expectedCurrent == null
                ? null : expectedCurrent.priorWindowsV3Identity();
        return prior != null && manifest.matchesArtifact(prior)
                ? "prior identity" : "unreadable/unrecognised identity";
    }

    private static final class FamilyGenerationState {
        final boolean currentReadable;
        final boolean priorReadable;
        final String currentDescription;
        final String priorDescription;

        FamilyGenerationState(boolean currentReadable, boolean priorReadable,
                              String currentDescription, String priorDescription) {
            this.currentReadable = currentReadable;
            this.priorReadable = priorReadable;
            this.currentDescription = currentDescription;
            this.priorDescription = priorDescription;
        }

        boolean hasExactlyOneReadableGeneration() {
            return currentReadable != priorReadable;
        }
    }

    private static void deleteIfExact(MigrationTrustAnchor anchor, String family, File file,
                                      RecoveryFingerprint expected) throws IOException {
        if (!Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)) return;
        ValidatedRecoveryFile validated = expected.validate(file);
        if (validated == null) {
            throw new IOException("Refusing to delete changed deconvolution recovery file: " + file);
        }
        beforeExactFileAction(file, validated,
                "deconvolution recovery file changed before delete");

        CleanupTicketQueue queue = CleanupTicketQueue.create(anchor, family);
        CleanupQueueState state = queue.readState();
        File orphan = queue.ticket(state.tail);
        if (queue.ticketExists(state.tail)) {
            try {
                if (queue.readTicket(orphan) == null) {
                    throw new IOException("Absent orphan cleanup ticket.");
                }
            } catch (RetryableCleanupException unsafeNamespace) {
                throw unsafeNamespace;
            } catch (IOException malformed) {
                // Never reuse an id whose durable metadata is ambiguous. Remove only that exact
                // metadata name and advance the tail before publishing this action.
                queue.deleteTicketMetadata(orphan);
            }
            state.tail = incrementCleanupTicketId(state.tail);
            if (!queue.writeState(state)) {
                throw new IOException("Could not adopt exact cleanup tail durably.");
            }
        }
        File payload = queue.newPayload();
        CleanupTicket ticket = new CleanupTicket(family, payload, expected);
        if (!queue.writeTicket(state.tail, ticket)) {
            throw new IOException("Could not publish exact cleanup ticket atomically.");
        }
        state.tail = incrementCleanupTicketId(state.tail);
        if (!queue.writeState(state)) {
            throw new IOException("Could not publish exact cleanup queue state durably.");
        }

        anchor.revalidate();
        DeleteBindingTestHook bindingHook = deleteBindingHookForTest;
        if (bindingHook != null) bindingHook.beforeAtomicBinding(file);
        AnchoredDirectory sourceParent;
        try {
            sourceParent = AnchoredDirectory.capturePlain(
                    file.getParentFile().toPath().toAbsolutePath().normalize(),
                    file.getParentFile().toPath().toRealPath());
        } catch (IOException unavailable) {
            throw new RetryableCleanupException(
                    "Exact cleanup source parent cannot be safely anchored.", unavailable);
        }
        // The durable ticket names this exact direct private payload before the live source entry
        // is atomically rebound. A later replacement is retained, never unlinked. Destination
        // durability is established before source-parent removal durability.
        moveAnchoredChild(sourceParent, file.getName(), queue.payloadsDirectory,
                payload.getName(), "bind-exact-payload");
        if (!forceAnchoredDirectory(queue.payloadsDirectory, "force-exact-binding-destination")
                || !forceAnchoredDirectory(sourceParent, "force-exact-binding-source")) {
            throw new RetryableCleanupException(
                    "Exact cleanup binding directory entries are not durable.");
        }
        ValidatedRecoveryFile bound;
        try {
            bound = expected.validate(payload);
        } catch (IOException unavailable) {
            throw new RetryableCleanupException(
                    "Exact cleanup payload could not be revalidated after binding.", unavailable);
        }
        if (bound == null) {
            queue.processExactPayloadOnce(ticket);
            throw new IOException("Refusing to delete file changed before cleanup binding: "
                    + file);
        }
        // Best-effort bounded drain. The source is already absent and its exact payload remains
        // durably ticketed if the delete cannot safely complete now.
        processCleanupTickets(anchor, family, 1);
    }

    private static String readBoundedStrictUtf8(File file, int maxBytes, String role)
            throws IOException {
        if (file == null || maxBytes < 0 || !Files.isRegularFile(file.toPath(),
                LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Unsafe or absent " + role + ": " + file);
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(8192, maxBytes));
        try (InputStream input = Files.newInputStream(file.toPath())) {
            byte[] buffer = new byte[8192];
            int total = 0;
            while (true) {
                int requested = Math.min(buffer.length, maxBytes - total + 1);
                int count = input.read(buffer, 0, requested);
                if (count < 0) break;
                if (count == 0) continue;
                if (total > maxBytes - count) {
                    throw new IOException(role + " exceeds the byte limit.");
                }
                output.write(buffer, 0, count);
                total += count;
            }
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(output.toByteArray())).toString();
        } catch (CharacterCodingException malformedUtf8) {
            throw new IOException(role + " is not strict UTF-8.", malformedUtf8);
        }
    }

    /** A single, strictly decoded, bounded read shared by v2 classification and v3 parsing. */
    static final class JournalDocument {
        final List<String> lines;

        private JournalDocument(List<String> lines) {
            this.lines = lines;
        }

        static JournalDocument read(File journalFile) throws IOException {
            if (journalFile == null || !Files.isRegularFile(journalFile.toPath(),
                    LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Recovery journal is absent or unsafe: " + journalFile);
            }
            return fromBytes(readBoundedBytes(journalFile));
        }

        static JournalDocument fromBytes(byte[] bytes) throws IOException {
            if (bytes == null || bytes.length > MAX_RECOVERY_JOURNAL_BYTES) {
                throw new IOException("Recovery journal exceeds the byte limit.");
            }
            String text;
            try {
                text = StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(bytes)).toString();
            } catch (CharacterCodingException malformedUtf8) {
                throw new IOException("Recovery journal is not strict UTF-8.", malformedUtf8);
            }

            List<String> lines = new ArrayList<String>();
            if (text.length() == 0) {
                lines.add("");
            } else {
                int start = 0;
                while (start < text.length()) {
                    int newline = text.indexOf('\n', start);
                    boolean finalLine = newline < 0;
                    int end = finalLine ? text.length() : newline;
                    if (end > start && text.charAt(end - 1) == '\r') end--;
                    String line = text.substring(start, end);
                    if (line.indexOf('\r') >= 0 || line.indexOf('\0') >= 0) {
                        throw new IOException("Recovery journal contains invalid control bytes.");
                    }
                    validateBoundedJournalLine(line);
                    lines.add(line);
                    if (lines.size() > MAX_RECOVERY_JOURNAL_RECORDS + 2) {
                        throw new IOException("Recovery journal contains too many records.");
                    }
                    if (finalLine) break;
                    start = newline + 1;
                }
            }
            return new JournalDocument(lines);
        }

        boolean isVersion(String version) {
            return !lines.isEmpty() && version.equals(lines.get(0));
        }

        private static byte[] readBoundedBytes(File journalFile) throws IOException {
            ByteArrayOutputStream output = new ByteArrayOutputStream(
                    Math.min(8192, MAX_RECOVERY_JOURNAL_BYTES));
            try (InputStream input = Files.newInputStream(journalFile.toPath())) {
                byte[] buffer = new byte[8192];
                int total = 0;
                int count;
                while ((count = input.read(buffer)) >= 0) {
                    if (count == 0) continue;
                    if (total > MAX_RECOVERY_JOURNAL_BYTES - count) {
                        throw new IOException("Recovery journal exceeds the byte limit.");
                    }
                    output.write(buffer, 0, count);
                    total += count;
                }
            }
            return output.toByteArray();
        }

        private static void validateBoundedJournalLine(String line) throws IOException {
            if (line.length() > MAX_RECOVERY_JOURNAL_LINE_CHARS) {
                throw new IOException("Recovery journal line exceeds the length limit.");
            }
            int fields = 1;
            int fieldStart = 0;
            for (int i = 0; i < line.length(); i++) {
                if (line.charAt(i) != '|') continue;
                if (i - fieldStart > MAX_RECOVERY_JOURNAL_FIELD_CHARS) {
                    throw new IOException("Recovery journal field exceeds the length limit.");
                }
                fields++;
                if (fields > MAX_RECOVERY_JOURNAL_FIELDS) {
                    throw new IOException("Recovery journal record has too many fields.");
                }
                fieldStart = i + 1;
            }
            if (line.length() - fieldStart > MAX_RECOVERY_JOURNAL_FIELD_CHARS) {
                throw new IOException("Recovery journal field exceeds the length limit.");
            }
        }
    }

    /** Strictly classifies the checksummed V37 format without trusting it for recovery. */
    private static final class LegacyV2Journal {
        static void validate(File rootDir, ArtifactIdentity current, File transaction,
                             List<String> lines) throws IOException {
            if (lines.size() < 3 || !"deconv-migration-v2".equals(lines.get(0))) {
                throw new IOException("Unsupported legacy recovery journal.");
            }
            String[] commit = lines.get(lines.size() - 1).split("\\|", -1);
            if (commit.length != 3 || !"commit".equals(commit[0])) {
                throw new IOException("Truncated legacy recovery journal.");
            }
            int recordCount = parseInt(commit[1]);
            if (recordCount != lines.size() - 2 || !isSha256(commit[2])) {
                throw new IOException("Invalid legacy journal record count/checksum.");
            }
            StringBuilder records = new StringBuilder();
            for (int i = 1; i < lines.size() - 1; i++) {
                records.append(lines.get(i)).append('\n');
            }
            if (!commit[2].equals(sha256Hex(records.toString()))) {
                throw new IOException("Legacy recovery journal checksum mismatch.");
            }

            ArtifactIdentity prior = current.priorWindowsV3Identity();
            if (prior == null) throw new IOException("Legacy journal has no prior identity.");
            boolean manifestSeen = false;
            boolean retirementSeen = false;
            int publicationIndex = 0;
            int retirementIndex = 0;
            Set<String> targets = new HashSet<String>();
            Set<String> originals = new HashSet<String>();
            Set<String> recoverySlots = new HashSet<String>();
            File desiredManifest = new File(transaction, "desired-manifest.json");
            requireExactRecoverySlot(transaction, desiredManifest, "desired-manifest.json");
            if (!Files.isRegularFile(desiredManifest.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Legacy desired manifest is absent or unsafe.");
            }
            DeconvManifest desiredManifestValue = DeconvManifest.readBounded(desiredManifest);
            if (!desiredManifestValue.matchesArtifact(current)) {
                throw new IOException("Legacy desired manifest has the wrong identity.");
            }
            for (int i = 1; i < lines.size() - 1; i++) {
                String[] fields = lines.get(i).split("\\|", -1);
                if ("manifest".equals(fields[0]) && fields.length == 4) {
                    if (manifestSeen || i != 1 || !isBooleanToken(fields[2])) {
                        throw new IOException("Duplicate/out-of-order legacy manifest record.");
                    }
                    manifestSeen = true;
                    File target = decoded(fields[1]);
                    if (!target.getCanonicalFile().equals(
                            manifestFile(rootDir, current).getCanonicalFile())) {
                        throw new IOException("Legacy journal targets an unexpected manifest.");
                    }
                    boolean existed = "1".equals(fields[2]);
                    File backup = "-".equals(fields[3]) ? null : decoded(fields[3]);
                    if (existed != (backup != null)) {
                        throw new IOException("Incomplete legacy manifest backup record.");
                    }
                    if (backup != null) {
                        requireExactRecoverySlot(transaction, backup, "manifest-backup.json");
                        addUniqueRecoveryPath(recoverySlots, backup);
                        requireBoundedManifestFile(backup, "legacy manifest backup");
                    }
                    if (Files.exists(target.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                        requireBoundedManifestFile(target, "legacy live manifest");
                        if (!sameContent(desiredManifest, target)
                                && (backup == null || !sameContent(backup, target))) {
                            throw new IOException("Legacy live manifest contains unknown bytes.");
                        }
                    }
                } else if ("publish".equals(fields[0]) && fields.length == 6) {
                    if (!manifestSeen || retirementSeen || !isBooleanToken(fields[2])
                            || !isSha256(fields[5])) {
                        throw new IOException("Invalid legacy publication record.");
                    }
                    File target = decoded(fields[1]);
                    ensureArtifactPath(rootDir, target);
                    String role = publicationRole(rootDir, current, target);
                    if (!isArtifactRoleForIdentity(rootDir, current, target, role)
                            || "manifest".equals(role)
                            || !targets.add(target.getCanonicalPath())) {
                        throw new IOException("Legacy publication target/role is invalid.");
                    }
                    boolean existed = "1".equals(fields[2]);
                    File backup = "-".equals(fields[3]) ? null : decoded(fields[3]);
                    if (existed != (backup != null)) {
                        throw new IOException("Incomplete legacy publication backup record.");
                    }
                    if (backup != null) {
                        requireExactRecoverySlot(transaction, backup,
                                "backup/" + publicationIndex + ".bin");
                        addUniqueRecoveryPath(recoverySlots, backup);
                        if (!Files.isRegularFile(backup.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                            throw new IOException("Legacy publication backup is absent or unsafe.");
                        }
                    }
                    File desired = new File(transaction,
                            "desired/" + publicationIndex + ".bin");
                    requireExactRecoverySlot(transaction, desired,
                            "desired/" + publicationIndex + ".bin");
                    RecoveryFingerprint desiredFingerprint = new RecoveryFingerprint(
                            parseNonNegativeLong(fields[4]), fields[5]);
                    boolean desiredAvailable = desiredFingerprint.matches(desired);
                    boolean liveDesired = desiredFingerprint.matches(target);
                    boolean liveBaseline = backup != null && sameContent(backup, target);
                    boolean liveAbsent = !Files.exists(target.toPath(), LinkOption.NOFOLLOW_LINKS);
                    if (!desiredAvailable && !liveDesired) {
                        throw new IOException("Legacy desired publication bytes are unavailable.");
                    }
                    if (!liveDesired && !liveBaseline && !(liveAbsent && !existed)) {
                        throw new IOException("Legacy live publication target contains unknown bytes.");
                    }
                    publicationIndex++;
                } else if ("retire".equals(fields[0]) && fields.length == 5) {
                    if (!manifestSeen || !isSha256(fields[4])) {
                        throw new IOException("Invalid legacy retirement record.");
                    }
                    retirementSeen = true;
                    File original = decoded(fields[1]);
                    File hidden = decoded(fields[2]);
                    ensureArtifactPath(rootDir, original);
                    String role = publicationRole(rootDir, prior, original);
                    if (!isArtifactRoleForIdentity(rootDir, prior, original, role)
                            || !originals.add(original.getCanonicalPath())) {
                        throw new IOException("Legacy retirement source/role is invalid.");
                    }
                    requireExactRecoverySlot(transaction, hidden,
                            "retired/" + retirementIndex + ".bin");
                    addUniqueRecoveryPath(recoverySlots, hidden);
                    RecoveryFingerprint retirementFingerprint = new RecoveryFingerprint(
                            parseNonNegativeLong(fields[3]), fields[4]);
                    boolean originalExists = Files.exists(original.toPath(), LinkOption.NOFOLLOW_LINKS);
                    boolean hiddenExists = Files.exists(hidden.toPath(), LinkOption.NOFOLLOW_LINKS);
                    if (originalExists && !retirementFingerprint.matches(original)) {
                        throw new IOException("Legacy retirement source contains unknown bytes.");
                    }
                    if (hiddenExists && !retirementFingerprint.matches(hidden)) {
                        throw new IOException("Legacy retirement slot contains unknown bytes.");
                    }
                    retirementIndex++;
                } else {
                    throw new IOException("Malformed legacy journal line " + (i + 1));
                }
            }
            if (!manifestSeen) throw new IOException("Missing legacy manifest record.");
        }
    }

    private static final class RecoveryJournal {
        File manifestTarget;
        File desiredManifest;
        RecoveryFingerprint desiredManifestFingerprint;
        boolean manifestExisted;
        File manifestBackup;
        RecoveryFingerprint manifestBackupFingerprint;
        final List<RecoveryPublication> publications = new ArrayList<RecoveryPublication>();
        final List<RecoveryRetained> retained = new ArrayList<RecoveryRetained>();
        final List<RecoveryRetirement> retirements = new ArrayList<RecoveryRetirement>();

        static RecoveryJournal read(File rootDir, ArtifactIdentity current, File transaction,
                                    File journalFile, JournalDocument document) throws IOException {
            List<String> lines = document.lines;
            if (lines.size() < 3 || !"deconv-migration-v3".equals(lines.get(0))) {
                throw new IOException("Unsupported deconvolution recovery journal: " + journalFile);
            }
            String[] commit = lines.get(lines.size() - 1).split("\\|", -1);
            if (commit.length != 3 || !"commit".equals(commit[0])) {
                throw new IOException("Truncated deconvolution recovery journal: " + journalFile);
            }
            int expectedRecords = parseInt(commit[1]);
            if (expectedRecords != lines.size() - 2 || !isSha256(commit[2])) {
                throw new IOException("Invalid deconvolution recovery journal record count/checksum.");
            }
            StringBuilder records = new StringBuilder();
            for (int i = 1; i < lines.size() - 1; i++) {
                records.append(lines.get(i)).append('\n');
            }
            if (!commit[2].equals(sha256Hex(records.toString()))) {
                throw new IOException("Deconvolution recovery journal checksum mismatch.");
            }
            RecoveryJournal journal = new RecoveryJournal();
            boolean manifestSeen = false;
            Set<String> publicationTargets = new HashSet<String>();
            Set<String> retirementOriginals = new HashSet<String>();
            Set<String> recoveryArtifacts = new HashSet<String>();
            for (int i = 1; i < lines.size() - 1; i++) {
                String[] fields = lines.get(i).split("\\|", -1);
                if (fields.length == 0) continue;
                if ("manifest".equals(fields[0]) && fields.length == 10) {
                    if (manifestSeen || !"manifest".equals(fields[1])
                            || !isBooleanToken(fields[6]) || !isSha256(fields[5])) {
                        throw new IOException("Duplicate/invalid recovery manifest record.");
                    }
                    manifestSeen = true;
                    journal.manifestTarget = decoded(fields[2]);
                    journal.desiredManifest = decoded(fields[3]);
                    journal.desiredManifestFingerprint = new RecoveryFingerprint(
                            parseManifestLength(fields[4], "desired manifest"), fields[5]);
                    journal.manifestExisted = "1".equals(fields[6]);
                    journal.manifestBackup = "-".equals(fields[7]) ? null : decoded(fields[7]);
                    journal.manifestBackupFingerprint = parseOptionalManifestFingerprint(
                            fields[8], fields[9], journal.manifestExisted,
                            "manifest backup");
                    requireExactRecoverySlot(transaction, journal.desiredManifest,
                            "desired-manifest.json");
                    if (journal.manifestBackup != null) {
                        requireExactRecoverySlot(transaction, journal.manifestBackup,
                                "manifest-backup.json");
                    }
                    addUniqueRecoveryPath(recoveryArtifacts, journal.desiredManifest);
                    if (journal.manifestBackup != null) {
                        addUniqueRecoveryPath(recoveryArtifacts, journal.manifestBackup);
                    }
                } else if ("publish".equals(fields[0]) && fields.length == 10) {
                    if (!isPublicationRole(fields[1]) || !isBooleanToken(fields[6])
                            || !isSha256(fields[5])) {
                        throw new IOException("Invalid recovery publication record.");
                    }
                    File target = decoded(fields[2]);
                    if (!publicationTargets.add(target.getCanonicalPath())) {
                        throw new IOException("Duplicate recovery publication target.");
                    }
                    File desired = decoded(fields[3]);
                    int publicationIndex = journal.publications.size();
                    requireExactRecoverySlot(transaction, desired,
                            "desired/" + publicationIndex + ".bin");
                    boolean targetExisted = "1".equals(fields[6]);
                    File backup = "-".equals(fields[7]) ? null : decoded(fields[7]);
                    if (backup != null) {
                        requireExactRecoverySlot(transaction, backup,
                                "backup/" + publicationIndex + ".bin");
                    }
                    RecoveryFingerprint backupFingerprint = parseOptionalFingerprint(
                            fields[8], fields[9], targetExisted, fields[1] + " backup");
                    addUniqueRecoveryPath(recoveryArtifacts, desired);
                    if (backup != null) addUniqueRecoveryPath(recoveryArtifacts, backup);
                    journal.publications.add(new RecoveryPublication(fields[1], target, desired,
                            targetExisted, backup, parseNonNegativeLong(fields[4]), fields[5],
                            backupFingerprint));
                } else if ("retain".equals(fields[0]) && fields.length == 5) {
                    if (!isPublicationRole(fields[1]) || !isSha256(fields[4])) {
                        throw new IOException("Invalid retained publication record.");
                    }
                    File target = decoded(fields[2]);
                    if (!publicationTargets.add(target.getCanonicalPath())) {
                        throw new IOException("Duplicate retained/publication target.");
                    }
                    journal.retained.add(new RecoveryRetained(fields[1], target,
                            parseNonNegativeLong(fields[3]), fields[4]));
                } else if ("retire".equals(fields[0]) && fields.length == 6) {
                    if (!isPublicationRole(fields[1]) || !isSha256(fields[5])) {
                        throw new IOException("Invalid recovery retirement fingerprint.");
                    }
                    File original = decoded(fields[2]);
                    if (!retirementOriginals.add(original.getCanonicalPath())) {
                        throw new IOException("Duplicate recovery retirement path.");
                    }
                    File hidden = decoded(fields[3]);
                    requireExactRecoverySlot(transaction, hidden,
                            "retired/" + journal.retirements.size() + ".bin");
                    addUniqueRecoveryPath(recoveryArtifacts, hidden);
                    journal.retirements.add(new RecoveryRetirement(fields[1], original,
                            hidden, parseNonNegativeLong(fields[4]), fields[5]));
                } else {
                    throw new IOException("Malformed deconvolution recovery journal line " + (i + 1));
                }
            }
            if (!manifestSeen || journal.desiredManifestFingerprint == null
                    || journal.manifestExisted != (journal.manifestBackup != null)
                    || journal.manifestExisted != (journal.manifestBackupFingerprint != null)) {
                throw new IOException("Incomplete recovery manifest backup record.");
            }
            for (RecoveryPublication publication : journal.publications) {
                if (publication.targetExisted != (publication.backup != null)
                        || publication.targetExisted != (publication.backupFingerprint != null)) {
                    throw new IOException("Incomplete recovery publication backup record.");
                }
            }
            for (RecoveryRetained retained : journal.retained) {
                ensureArtifactPath(rootDir, retained.target);
                if (!retained.role.equals(publicationRole(rootDir, current, retained.target))
                        || !isArtifactRoleForIdentity(rootDir, current, retained.target,
                                retained.role)) {
                    throw new IOException("Retained publication role/target mismatch.");
                }
            }
            File expectedManifest = manifestFile(rootDir, current).getCanonicalFile();
            if (journal.manifestTarget == null
                    || !journal.manifestTarget.getCanonicalFile().equals(expectedManifest)) {
                throw new IOException("Recovery journal targets an unexpected manifest.");
            }
            ensureRecoveryPath(transaction, journal.desiredManifest);
            ensureRecoveryPath(transaction, journal.manifestBackup);
            for (RecoveryPublication publication : journal.publications) {
                ensureArtifactPath(rootDir, publication.target);
                ensureRecoveryPath(transaction, publication.desired);
                ensureRecoveryPath(transaction, publication.backup);
                if (!publication.role.equals(publicationRole(rootDir, current,
                        publication.target)) || !isArtifactRoleForIdentity(rootDir, current,
                                publication.target, publication.role)) {
                    throw new IOException("Recovery publication role/target mismatch.");
                }
            }
            for (RecoveryRetirement retirement : journal.retirements) {
                ensureArtifactPath(rootDir, retirement.original);
                ensureRecoveryPath(transaction, retirement.hidden);
                ArtifactIdentity prior = current.priorWindowsV3Identity();
                if (prior == null || !retirement.role.equals(publicationRole(rootDir, prior,
                        retirement.original)) || !isArtifactRoleForIdentity(rootDir, prior,
                                retirement.original, retirement.role)) {
                    throw new IOException("Recovery retirement role/target mismatch.");
                }
            }
            return journal;
        }

        void validateImmutableArtifacts(ArtifactIdentity current) throws IOException {
            requireExactFingerprint(desiredManifest, desiredManifestFingerprint,
                    "journaled desired manifest");
            DeconvManifest desired = DeconvManifest.readBounded(desiredManifest);
            if (!desired.matchesArtifact(current)) {
                throw new IOException("Journaled desired manifest has the wrong artifact identity.");
            }
            if (manifestExisted) {
                requireExactFingerprint(manifestBackup, manifestBackupFingerprint,
                        "journaled manifest backup");
            }
            for (RecoveryPublication publication : publications) {
                requireExactFingerprint(publication.desired, publication,
                        publication.role + " journaled desired artifact");
                if (publication.targetExisted) {
                    requireExactFingerprint(publication.backup, publication.backupFingerprint,
                            publication.role + " journaled backup");
                }
            }
        }

        void validatePresentImmutableArtifacts() throws IOException {
            requireIfPresent(desiredManifest, desiredManifestFingerprint,
                    "journaled desired manifest");
            requireIfPresent(manifestBackup, manifestBackupFingerprint,
                    "journaled manifest backup");
            for (RecoveryPublication publication : publications) {
                requireIfPresent(publication.desired, publication,
                        publication.role + " journaled desired artifact");
                requireIfPresent(publication.backup, publication.backupFingerprint,
                        publication.role + " journaled backup");
            }
        }

        boolean desiredCurrentGenerationComplete(File rootDir, ArtifactIdentity current)
                throws IOException {
            if (!desiredManifestFingerprint.matches(manifestTarget)) return false;
            DeconvManifest manifest = DeconvManifest.load(manifestTarget);
            if (!manifest.matchesArtifact(current)
                    || !isCompleteReadableGeneration(rootDir, current, this)) return false;
            for (RecoveryPublication publication : publications) {
                if (!publication.matches(publication.target)) return false;
            }
            for (RecoveryRetained item : retained) {
                if (!item.matches(item.target)) return false;
            }
            return true;
        }

        private boolean desiredTargetMatches(File target) throws IOException {
            File canonical = target.getCanonicalFile();
            for (RecoveryPublication publication : publications) {
                if (publication.target.getCanonicalFile().equals(canonical)) {
                    return publication.matches(target);
                }
            }
            for (RecoveryRetained item : retained) {
                if (item.target.getCanonicalFile().equals(canonical)) return item.matches(target);
            }
            return false;
        }

        void validateRollbackTargetClassification() throws IOException {
            if (!matchesOneOf(manifestTarget, desiredManifestFingerprint,
                    manifestExisted ? manifestBackupFingerprint : null, !manifestExisted)) {
                throw new IOException("Live manifest target is neither desired, prior, nor absent.");
            }
            for (RecoveryPublication publication : publications) {
                if (!matchesOneOf(publication.target, publication,
                        publication.targetExisted ? publication.backupFingerprint : null,
                        !publication.targetExisted)) {
                    throw new IOException("Live " + publication.role
                            + " target is neither desired, prior, nor absent.");
                }
            }
            for (RecoveryRetained item : retained) {
                requireExactFingerprint(item.target, item,
                        item.role + " retained recovery target");
            }
        }

        void validateRetirementClassification(boolean allowBothAbsent) throws IOException {
            for (RecoveryRetirement retirement : retirements) {
                boolean originalExists = Files.exists(retirement.original.toPath(),
                        LinkOption.NOFOLLOW_LINKS);
                boolean hiddenExists = Files.exists(retirement.hidden.toPath(),
                        LinkOption.NOFOLLOW_LINKS);
                if (originalExists && !retirement.matches(retirement.original)) {
                    throw new IOException("Prior " + retirement.role
                            + " path contains unjournaled bytes.");
                }
                if (hiddenExists && !retirement.matches(retirement.hidden)) {
                    throw new IOException("Retired " + retirement.role
                            + " slot contains unjournaled bytes.");
                }
                if (!allowBothAbsent && !originalExists && !hiddenExists) {
                    throw new IOException("Both prior and retired " + retirement.role
                            + " recovery artifacts are missing.");
                }
            }
        }
    }

    private static class RecoveryFingerprint {
        final long size;
        final String contentHash;

        RecoveryFingerprint(long size, String contentHash) {
            this.size = size;
            this.contentHash = contentHash == null ? "" : contentHash;
        }

        static RecoveryFingerprint of(File file) throws IOException {
            DeconvManifest.SourceFingerprint fingerprint =
                    DeconvManifest.SourceFingerprint.of(file);
            if (fingerprint.size < 0L || !isSha256(fingerprint.contentHash)) {
                throw new IOException("Could not fingerprint recovery artifact: " + file);
            }
            return new RecoveryFingerprint(fingerprint.size, fingerprint.contentHash);
        }

        static RecoveryFingerprint ofManifest(File file, String role) throws IOException {
            requireBoundedManifestFile(file, role);
            return of(file);
        }

        boolean matches(File file) throws IOException {
            return validate(file) != null;
        }

        ValidatedRecoveryFile validate(File file) throws IOException {
            if (file == null
                    || !Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)) return null;
            // Size is journal-authenticated and cheap to inspect. Reject mismatches before opening
            // or digesting a potentially huge sparse/untrusted recovery artifact.
            if (Files.size(file.toPath()) != size) return null;
            try {
                DeconvManifest.SourceFingerprint.ExactContent exact =
                        DeconvManifest.SourceFingerprint.exactContent(file, size);
                return contentHash.equals(exact.contentHash)
                        ? new ValidatedRecoveryFile(exact) : null;
            } catch (IOException changedOrUnreadable) {
                return null;
            }
        }
    }

    private static final class ValidatedRecoveryFile {
        private final DeconvManifest.SourceFingerprint.ExactContent exact;

        ValidatedRecoveryFile(DeconvManifest.SourceFingerprint.ExactContent exact) {
            this.exact = exact;
        }

        boolean stillNames(File file) {
            return exact != null && exact.stillNames(file);
        }

    }

    private static final class RecoveryPublication extends RecoveryFingerprint {
        final String role;
        final File target;
        final File desired;
        final boolean targetExisted;
        final File backup;
        final RecoveryFingerprint backupFingerprint;

        RecoveryPublication(String role, File target, File desired,
                            boolean targetExisted, File backup,
                            long size, String contentHash,
                            RecoveryFingerprint backupFingerprint) {
            super(size, contentHash);
            this.role = role;
            this.target = target;
            this.desired = desired;
            this.targetExisted = targetExisted;
            this.backup = backup;
            this.backupFingerprint = backupFingerprint;
        }
    }

    private static final class RecoveryRetirement extends RecoveryFingerprint {
        final String role;
        final File original;
        final File hidden;

        RecoveryRetirement(String role, File original, File hidden,
                           long size, String contentHash) {
            super(size, contentHash);
            this.role = role;
            this.original = original;
            this.hidden = hidden;
        }
    }

    private static final class RecoveryRetained extends RecoveryFingerprint {
        final String role;
        final File target;

        RecoveryRetained(String role, File target, long size, String contentHash) {
            super(size, contentHash);
            this.role = role;
            this.target = target;
        }
    }

    private static File decoded(String value) throws IOException {
        try {
            return new File(new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8));
        } catch (IllegalArgumentException failure) {
            throw new IOException("Malformed base64 path in recovery journal.", failure);
        }
    }

    private static int parseInt(String value) throws IOException {
        try {
            int parsed = Integer.parseInt(value);
            if (parsed < 1) throw new NumberFormatException("record count must be positive");
            return parsed;
        } catch (NumberFormatException failure) {
            throw new IOException("Malformed record count in recovery journal.", failure);
        }
    }

    private static long parseNonNegativeLong(String value) throws IOException {
        try {
            long parsed = Long.parseLong(value);
            if (parsed < 0L) throw new NumberFormatException("length must be non-negative");
            return parsed;
        } catch (NumberFormatException failure) {
            throw new IOException("Malformed length in recovery journal.", failure);
        }
    }

    private static boolean isBooleanToken(String value) {
        return "0".equals(value) || "1".equals(value);
    }

    private static RecoveryFingerprint parseOptionalFingerprint(String size, String hash,
                                                                boolean required, String role)
            throws IOException {
        if (!required) {
            if (!"-".equals(size) || !"-".equals(hash)) {
                throw new IOException("Unexpected " + role + " fingerprint.");
            }
            return null;
        }
        if (!isSha256(hash)) throw new IOException("Invalid " + role + " fingerprint.");
        return new RecoveryFingerprint(parseNonNegativeLong(size), hash);
    }

    private static RecoveryFingerprint parseOptionalManifestFingerprint(
            String size, String hash, boolean required, String role) throws IOException {
        if (!required) return parseOptionalFingerprint(size, hash, false, role);
        if (!isSha256(hash)) throw new IOException("Invalid " + role + " fingerprint.");
        return new RecoveryFingerprint(parseManifestLength(size, role), hash);
    }

    private static long parseManifestLength(String value, String role) throws IOException {
        long parsed = parseNonNegativeLong(value);
        if (parsed > DeconvManifest.MAX_MANIFEST_UTF8_BYTES) {
            throw new IOException("Oversized " + role + " fingerprint length.");
        }
        return parsed;
    }

    private static void requireBoundedManifestFile(File file, String role) throws IOException {
        if (file == null || !Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Missing or unsafe " + role + ": " + file);
        }
        if (Files.size(file.toPath()) > DeconvManifest.MAX_MANIFEST_UTF8_BYTES) {
            throw new IOException("Oversized " + role + ": " + file);
        }
    }

    private static void addUniqueRecoveryPath(Set<String> paths, File file) throws IOException {
        if (file == null || !paths.add(file.getAbsoluteFile().toPath().normalize().toString())) {
            throw new IOException("Duplicate recovery artifact path.");
        }
    }

    private static void requireExactRecoverySlot(File transaction, File candidate,
                                                 String relative) throws IOException {
        File expected = new File(transaction, relative.replace('/', File.separatorChar));
        Path expectedPath = expected.toPath().toAbsolutePath().normalize();
        Path candidatePath = candidate.toPath().toAbsolutePath().normalize();
        if (!expectedPath.equals(candidatePath)) {
            throw new IOException("Recovery artifact is not in its deterministic slot: " + candidate);
        }
        if (Files.exists(candidatePath, LinkOption.NOFOLLOW_LINKS)
                && (safeNode(transaction, candidate) == null
                || !Files.isRegularFile(candidatePath, LinkOption.NOFOLLOW_LINKS))) {
            throw new IOException("Unsafe linked recovery artifact slot: " + candidate);
        }
    }

    private static boolean isPublicationRole(String role) {
        return role != null && !role.isEmpty() && !"invalid".equals(role)
                && role.indexOf('|') < 0;
    }

    private static boolean isArtifactRoleForIdentity(File rootDir, ArtifactIdentity identity,
                                                     File target, String role) throws IOException {
        if (!isPublicationRole(role) || identity == null || target == null) return false;
        if ("manifest".equals(role) || "merged".equals(role) || "details".equals(role)
                || role.startsWith("channel:")) return true;
        File canonical = target.getCanonicalFile();
        if (role.startsWith("cache:")) {
            Path cacheRoot = cacheDir(rootDir).getCanonicalFile().toPath();
            Path relative = cacheRoot.relativize(canonical.toPath());
            return relative.getNameCount() >= 3
                    && identity.artifactKey.equals(relative.getName(1).toString());
        }
        return role.startsWith("output:")
                && canonical.getName().startsWith(identity.artifactKey + "_");
    }

    private static String publicationRole(File rootDir, ArtifactIdentity identity, File target) {
        if (target == null || identity == null) return "invalid";
        try {
            File canonical = target.getCanonicalFile();
            if (canonical.equals(manifestFile(rootDir, identity).getCanonicalFile())) return "manifest";
            if (canonical.equals(mergedDeconvFile(rootDir, identity).getCanonicalFile())) return "merged";
            if (canonical.equals(detailsFile(rootDir, identity).getCanonicalFile())) return "details";
            String channelPrefix = identity.artifactKey + "_C";
            String name = canonical.getName();
            if (name.startsWith(channelPrefix) && name.endsWith(".tif")) {
                String indexText = name.substring(channelPrefix.length(), name.length() - 4);
                try {
                    int channel = Integer.parseInt(indexText);
                    if (channel >= 0 && canonical.equals(
                            deconvFile(rootDir, identity, channel).getCanonicalFile())) {
                        return "channel:" + channel;
                    }
                } catch (NumberFormatException ignored) {
                    // Fall through to the exact identity-relative output role.
                }
            }
            File cacheRoot = cacheDir(rootDir).getCanonicalFile();
            if (canonical.toPath().startsWith(cacheRoot.toPath())) {
                return "cache:" + encodedText(cacheRoot.toPath().relativize(
                        canonical.toPath()).toString());
            }
            File outputRoot = deconvOutDir(rootDir).getCanonicalFile();
            if (canonical.toPath().startsWith(outputRoot.toPath())) {
                return "output:" + encodedText(outputRoot.toPath().relativize(
                        canonical.toPath()).toString());
            }
        } catch (IOException ignored) {
            return "invalid";
        }
        return "invalid";
    }

    private static String encodedText(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                value.getBytes(StandardCharsets.UTF_8));
    }

    private static void requireExactFingerprint(File file, RecoveryFingerprint expected,
                                                String role) throws IOException {
        validateExactFingerprint(file, expected, role);
    }

    private static ValidatedRecoveryFile validateExactFingerprint(
            File file, RecoveryFingerprint expected, String role) throws IOException {
        ValidatedRecoveryFile validated = expected == null ? null : expected.validate(file);
        if (validated == null) {
            throw new IOException("Recovery fingerprint mismatch for " + role + ": " + file);
        }
        return validated;
    }

    private static void beforeExactFileAction(File file, ValidatedRecoveryFile validated,
                                              String role) throws IOException {
        ExactFileActionTestHook hook = exactFileActionHookForTest;
        if (hook != null) hook.afterValidation(file);
        if (validated == null || !validated.stillNames(file)) {
            throw new IOException(role + ": " + file);
        }
    }

    private static void requireIfPresent(File file, RecoveryFingerprint expected, String role)
            throws IOException {
        if (file != null && Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            requireExactFingerprint(file, expected, role);
        }
    }

    private static boolean matchesOneOf(File file, RecoveryFingerprint desired,
                                        RecoveryFingerprint prior, boolean absentAllowed)
            throws IOException {
        if (!Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)) return absentAllowed;
        return (desired != null && desired.matches(file)) || (prior != null && prior.matches(file));
    }

    private static void ensureArtifactPath(File rootDir, File file) throws IOException {
        if (file == null || !(isWithin(deconvOutDir(rootDir), file) || isWithin(cacheDir(rootDir), file))) {
            throw new IOException("Recovery journal artifact escapes project storage: " + file);
        }
    }

    private static void ensureRecoveryPath(File transaction, File file) throws IOException {
        if (file != null) {
            Path root = transaction.toPath().toAbsolutePath().normalize();
            Path candidate = file.toPath().toAbsolutePath().normalize();
            if (!candidate.startsWith(root)) {
                throw new IOException("Recovery journal backup escapes its transaction: " + file);
            }
            if (Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)
                    && safeNode(transaction, file) == null) {
                throw new IOException("Recovery journal artifact is linked or unsafe: " + file);
            }
        }
    }

    private static boolean isWithin(File root, File candidate) throws IOException {
        Path rootPath = root.getCanonicalFile().toPath();
        Path candidatePath = candidate.getCanonicalFile().toPath();
        return candidatePath.startsWith(rootPath);
    }

    private static final class MigrationFile {
        final String role;
        final File source;
        final File target;
        File staged;
        File backup;
        RecoveryFingerprint desiredFingerprint;
        RecoveryFingerprint backupFingerprint;
        boolean targetExisted;
        boolean published;

        MigrationFile(String role, File source, File target) {
            this.role = role;
            this.source = source;
            this.target = target;
        }
    }

    private static final class RetiredFile {
        final String role;
        final File original;
        final File hidden;
        RecoveryFingerprint fingerprint;
        boolean moved;

        RetiredFile(String role, File original, File hidden) {
            this.role = role;
            this.original = original;
            this.hidden = hidden;
        }
    }

    private static final class RetainedFile {
        final String role;
        final File target;
        RecoveryFingerprint fingerprint;

        RetainedFile(String role, File target) {
            this.role = role;
            this.target = target;
        }
    }

    private static String encoded(File file) throws IOException {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                file.getCanonicalPath().getBytes(StandardCharsets.UTF_8));
    }

    private static boolean sameContent(File expected, File actual) throws IOException {
        if (expected == null || actual == null
                || !Files.isRegularFile(expected.toPath(), LinkOption.NOFOLLOW_LINKS)
                || !Files.isRegularFile(actual.toPath(), LinkOption.NOFOLLOW_LINKS)
                || Files.size(expected.toPath()) != Files.size(actual.toPath())) return false;
        return DeconvManifest.SourceFingerprint.of(expected)
                .matches(DeconvManifest.SourceFingerprint.of(actual));
    }

    private static void verifySameContent(File expected, File actual) throws IOException {
        DeconvManifest.SourceFingerprint first = DeconvManifest.SourceFingerprint.of(expected);
        DeconvManifest.SourceFingerprint second = DeconvManifest.SourceFingerprint.of(actual);
        if (!first.matches(second)) {
            throw new IOException("Staged migration file failed content validation: " + expected);
        }
    }

    private static void forceRegularFile(File file) throws IOException {
        if (file == null || !Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Cannot make migration recovery artifact durable: " + file);
        }
        try (FileChannel channel = FileChannel.open(file.toPath(), StandardOpenOption.WRITE)) {
            channel.force(true);
        }
    }

    /**
     * Best Java-8 directory-entry barrier. Unix-like providers generally support forcing a read-only
     * directory channel. Windows generally does not; there we perform and force a same-directory
     * sentinel create/delete barrier, explicitly treating it as a conservative fallback rather than
     * claiming a directory fsync occurred.
     */
    private static boolean forceDirectoryMetadata(File directory) throws IOException {
        if (directory == null || !directory.isDirectory()) {
            throw new IOException("Cannot force directory metadata: " + directory);
        }
        DirectoryForceTestHook testHook = directoryForceHookForTest;
        if (testHook != null) return testHook.force(directory.getCanonicalFile());
        try (FileChannel channel = FileChannel.open(directory.toPath(), StandardOpenOption.READ)) {
            channel.force(true);
            return directoryDurabilityOverrideForTest == null
                    || directoryDurabilityOverrideForTest.booleanValue();
        } catch (UnsupportedOperationException unsupportedProvider) {
            forceDirectoryBarrierFile(directory);
            return directoryDurabilityOverrideForTest != null
                    && directoryDurabilityOverrideForTest.booleanValue();
        } catch (IOException unsupportedOnWindows) {
            if (!isWindows()) throw unsupportedOnWindows;
            forceDirectoryBarrierFile(directory);
            return directoryDurabilityOverrideForTest != null
                    && directoryDurabilityOverrideForTest.booleanValue();
        }
    }

    private static void forceDirectoryBarrierFile(File directory) throws IOException {
        File barrier = File.createTempFile(".deconv-directory-barrier-", ".tmp", directory);
        try {
            Files.write(barrier.toPath(), new byte[]{0x44, 0x43, 0x56});
            forceRegularFile(barrier);
        } finally {
            Files.deleteIfExists(barrier.toPath());
        }
    }

    private static boolean forceFileAndParent(File file) throws IOException {
        forceRegularFile(file);
        return forceDirectoryMetadata(file.getParentFile());
    }

    private static void forceIfPresent(File file) throws IOException {
        if (file != null && Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            forceRegularFile(file);
        }
    }

    private static boolean forceArtifactDirectoryChain(File rootDir, File artifact)
            throws IOException {
        File parent = artifact.getCanonicalFile().getParentFile();
        File cacheRoot = cacheDir(rootDir).getCanonicalFile();
        if (isWithin(cacheRoot, artifact)) return forceDirectoryChain(parent, cacheRoot);
        File outputRoot = deconvOutDir(rootDir).getCanonicalFile();
        if (isWithin(outputRoot, artifact)) return forceDirectoryChain(parent, outputRoot);
        throw new IOException("Artifact has no forceable storage ancestry: " + artifact);
    }

    private static boolean forceDirectoryChain(File leaf, File inclusiveRoot) throws IOException {
        File current = leaf == null ? null : leaf.getCanonicalFile();
        File root = inclusiveRoot == null ? null : inclusiveRoot.getCanonicalFile();
        if (current == null || root == null || !current.toPath().startsWith(root.toPath())) {
            throw new IOException("Directory force ancestry escapes its root: " + leaf);
        }
        boolean supported = true;
        while (current != null) {
            supported &= forceDirectoryMetadata(current);
            if (current.equals(root)) return supported;
            current = current.getParentFile();
        }
        throw new IOException("Directory force ancestry did not reach its root: " + leaf);
    }

    private static List<File> filesDeepestFirst(Set<String> paths) {
        List<File> files = new ArrayList<File>();
        if (paths != null) {
            for (String path : paths) if (path != null) files.add(new File(path));
        }
        Collections.sort(files, new Comparator<File>() {
            @Override
            public int compare(File left, File right) {
                int depth = right.toPath().getNameCount() - left.toPath().getNameCount();
                return depth != 0 ? depth : left.getPath().compareTo(right.getPath());
            }
        });
        return files;
    }

    private static void deleteAndForceParent(File file) throws IOException {
        if (file == null) return;
        if (Files.deleteIfExists(file.toPath())) forceDirectoryMetadata(file.getParentFile());
    }

    private static File journalFileFor(File transaction) {
        return new File(transaction, "recovery.journal");
    }

    private static void cleanupRecoveredTransaction(MigrationTrustAnchor cleanupAnchor,
                                                    File transaction, File journal)
            throws IOException {
        if (journal == null || !"recovery.journal".equals(journal.getName())
                || !journal.getAbsoluteFile().getParentFile().equals(
                        transaction.getAbsoluteFile())) {
            throw new IOException("Recovery journal is not the transaction's direct child.");
        }
        if (!testOnlyPathOperationsAllowed()) {
            secureDeleteRecoveryPath(cleanupAnchor, transaction);
            return;
        }
        cleanupAnchor.revalidate();
        deleteTransactionExceptJournalTestOnly(cleanupAnchor, transaction);
        forceDirectoryMetadata(transaction);
        cleanupAnchor.revalidate();
        deleteTreeUncheckedTestOnly(cleanupAnchor, journal, new HashSet<Path>());
        forceDirectoryMetadata(transaction);
        cleanupAnchor.revalidate();
        deleteTreeUncheckedTestOnly(cleanupAnchor, transaction, new HashSet<Path>());
        forceDirectoryMetadata(transaction.getParentFile());
    }

    private static void deleteTransactionExceptJournal(MigrationTrustAnchor cleanupAnchor,
                                                       File transaction)
            throws IOException {
        if (!testOnlyPathOperationsAllowed()) {
            secureDeleteRecoveryPath(cleanupAnchor, transaction);
            return;
        }
        deleteTransactionExceptJournalTestOnly(cleanupAnchor, transaction);
    }

    private static void deleteTransactionExceptJournalTestOnly(
            MigrationTrustAnchor cleanupAnchor, File transaction) throws IOException {
        if (transaction == null || !Files.exists(transaction.toPath(), LinkOption.NOFOLLOW_LINKS)) return;
        cleanupAnchor.requireDestructiveAuthority();
        cleanupAnchor.revalidate();
        File migrationRoot = cleanupAnchor.migrationRoot.toFile();
        requireLexicallyBelow(migrationRoot, transaction);
        SafeNode transactionNode = safeNode(cleanupAnchor, transaction);
        if (transactionNode == null || !transactionNode.attributes.isDirectory()) {
            throw new IOException("Refusing to traverse an unsafe migration transaction: " + transaction);
        }
        cleanupAnchor.revalidate();
        beforeCleanupTraversal(transaction);
        cleanupAnchor.revalidate();
        transactionNode = safeNode(cleanupAnchor, transaction);
        if (transactionNode == null || !transactionNode.attributes.isDirectory()) {
            deleteLinkedNodeOnly(cleanupAnchor, transaction);
            throw new IOException("Migration transaction changed before cleanup traversal.");
        }
        File[] children = transaction.listFiles();
        if (children == null) throw new IOException("Could not enumerate cleanup path: " + transaction);
        Set<Path> visited = new HashSet<Path>();
        for (File child : children) {
            if ("recovery.journal".equals(child.getName())) continue;
            deleteTreeUncheckedTestOnly(cleanupAnchor, child, visited);
        }
    }

    private static void deleteTree(MigrationTrustAnchor cleanupAnchor, File root) throws IOException {
        if (!testOnlyPathOperationsAllowed()) {
            secureDeleteRecoveryPath(cleanupAnchor, root);
            return;
        }
        if (root == null || !Files.exists(root.toPath(), LinkOption.NOFOLLOW_LINKS)) return;
        cleanupAnchor.requireDestructiveAuthority();
        cleanupAnchor.revalidate();
        File migrationRoot = cleanupAnchor.migrationRoot.toFile();
        requireLexicallyBelow(migrationRoot, root);
        deleteTreeUncheckedTestOnly(cleanupAnchor, root, new HashSet<Path>());
    }

    private static void deleteTreeUncheckedTestOnly(MigrationTrustAnchor cleanupAnchor, File file,
                                                    Set<Path> visited)
            throws IOException {
        cleanupAnchor.requireDestructiveAuthority();
        cleanupAnchor.revalidate();
        SafeNode node = safeNode(cleanupAnchor, file);
        if (node == null) {
            deleteLinkedNodeOnly(cleanupAnchor, file);
            return;
        }
        if (node.attributes.isDirectory() && visited.add(node.realPath)) {
            cleanupAnchor.revalidate();
            beforeCleanupTraversal(file);
            cleanupAnchor.revalidate();
            node = safeNode(cleanupAnchor, file);
            if (node == null || !node.attributes.isDirectory()) {
                deleteLinkedNodeOnly(cleanupAnchor, file);
                return;
            }
            File[] children = file.listFiles();
            if (children == null) throw new IOException("Could not enumerate cleanup path: " + file);
            java.util.Arrays.sort(children, new Comparator<File>() {
                @Override
                public int compare(File left, File right) {
                    boolean leftJournal = "recovery.journal".equals(left.getName());
                    boolean rightJournal = "recovery.journal".equals(right.getName());
                    if (leftJournal != rightJournal) return leftJournal ? 1 : -1;
                    return left.getName().compareTo(right.getName());
                }
            });
            for (File child : children) {
                deleteTreeUncheckedTestOnly(cleanupAnchor, child, visited);
            }
        }
        cleanupAnchor.revalidate();
        SafeNode finalNode = safeNode(cleanupAnchor, file);
        if (finalNode == null) {
            throw new RetryableCleanupException(
                    "Recovery cleanup node changed before test-only deletion.");
        }
        beforeRecoveryDirectoryOperation(node.attributes.isDirectory()
                ? "delete-directory" : "delete-file", file);
        cleanupAnchor.revalidate();
        SafeNode rebound = safeNode(cleanupAnchor, file);
        if (!sameSafeNode(finalNode, rebound)) {
            throw new RetryableCleanupException(
                    "Recovery cleanup node changed before test-only deletion.");
        }
        if ("recovery.journal".equals(file.getName())) {
            File parent = file.getAbsoluteFile().getParentFile();
            File[] remaining = parent.listFiles();
            if (remaining == null || remaining.length != 1
                    || !"recovery.journal".equals(remaining[0].getName())) {
                throw new RetryableCleanupException(
                        "Transaction contents changed at the test-only journal barrier.");
            }
            forceDirectoryMetadata(parent);
        }
        Files.deleteIfExists(file.toPath());
    }

    /** Delete one recovery node through handles rooted in the immutable migration anchor. */
    private static void deleteRecoveryPath(MigrationTrustAnchor cleanupAnchor, File target)
            throws IOException {
        if (testOnlyPathOperationsAllowed()) {
            deleteTree(cleanupAnchor, target);
        } else {
            secureDeleteRecoveryPath(cleanupAnchor, target);
        }
    }

    private static void secureDeleteRecoveryPath(MigrationTrustAnchor anchor, File target)
            throws IOException {
        // SecureDirectoryStream binds the parent handle, but deleteFile/deleteDirectory still
        // unconditionally unlink the name currently occupying that slot.  Java 8 exposes no
        // compare-fileKey-and-unlink primitive, so production recovery cleanup must retain.
        throw new RetryableCleanupException(
                "Java 8 has no unlink-if-fileKey operation; recovery state is retained.");
    }
    private static void beforeCleanupTraversal(File directory) throws IOException {
        CleanupTraversalTestHook hook = cleanupTraversalHookForTest;
        if (hook != null) hook.beforeTraversal(directory);
    }

    private static void beforeRecoveryDirectoryOperation(String operation, File directory)
            throws IOException {
        RecoveryDirectoryTestHook hook = recoveryDirectoryHookForTest;
        if (hook != null) hook.beforeOperation(operation, directory);
    }

    private static void requireLexicallyBelow(File root, File candidate) throws IOException {
        if (root == null || candidate == null) throw new IOException("Missing cleanup path.");
        Path rootPath = root.toPath().toAbsolutePath().normalize();
        Path candidatePath = candidate.toPath().toAbsolutePath().normalize();
        if (candidatePath.equals(rootPath) || !candidatePath.startsWith(rootPath)) {
            throw new IOException("Cleanup path escapes the project migration root: " + candidate);
        }
    }

    private static void deleteLinkedNodeOnly(MigrationTrustAnchor cleanupAnchor, File candidate)
            throws IOException {
        cleanupAnchor.requireDestructiveAuthority();
        cleanupAnchor.revalidate();
        File containmentRoot = cleanupAnchor.migrationRoot.toFile();
        requireLexicallyBelow(containmentRoot, candidate);
        File parent = candidate.getAbsoluteFile().getParentFile();
        SafeNode parentNode = safeNode(cleanupAnchor, parent);
        if (parentNode == null || !parentNode.attributes.isDirectory()) {
            throw new IOException("Refusing cleanup below an unsafe parent: " + candidate);
        }
        beforeRecoveryDirectoryOperation("delete-linked-node", candidate);
        cleanupAnchor.revalidate();
        SafeNode reboundParent = safeNode(cleanupAnchor, parent);
        if (!sameSafeNode(parentNode, reboundParent)) {
            throw new RetryableCleanupException(
                    "Recovery cleanup parent changed before linked-node deletion.");
        }
        Files.deleteIfExists(candidate.toPath());
    }

    private static boolean sameSafeNode(SafeNode expected, SafeNode current) {
        if (expected == null || current == null
                || !expected.realPath.equals(current.realPath)) return false;
        Object expectedKey = expected.attributes.fileKey();
        Object currentKey = current.attributes.fileKey();
        return (expectedKey == null ? currentKey == null : expectedKey.equals(currentKey))
                && expected.attributes.creationTime().toMillis()
                == current.attributes.creationTime().toMillis()
                && expected.attributes.isDirectory() == current.attributes.isDirectory()
                && expected.attributes.isRegularFile() == current.attributes.isRegularFile();
    }

    private static boolean isContainedPlainDirectory(File containmentRoot, File candidate)
            throws IOException {
        SafeNode node = safeNode(containmentRoot, candidate);
        return node != null && node.attributes.isDirectory();
    }

    /** Resolve a cleanup node against the immutable migration-root identity, never the path's
     * current real target. */
    private static SafeNode safeNode(MigrationTrustAnchor anchor, File candidate)
            throws IOException {
        anchor.revalidate();
        if (candidate == null) return null;
        Path candidateLexical = candidate.toPath().toAbsolutePath().normalize();
        if (!candidateLexical.startsWith(anchor.migrationRoot)
                || Files.isSymbolicLink(candidateLexical)) return null;
        BasicFileAttributes attributes;
        try {
            attributes = Files.readAttributes(candidateLexical, BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
        } catch (java.nio.file.NoSuchFileException absent) {
            return null;
        }
        Path candidateReal = candidateLexical.toRealPath();
        Path expectedReal = anchor.migrationReal.resolve(
                anchor.migrationRoot.relativize(candidateLexical)).normalize();
        if (!candidateReal.startsWith(anchor.migrationReal)
                || !candidateReal.equals(expectedReal)) return null;
        return new SafeNode(attributes, candidateReal);
    }

    /** Resolve one node without following a link below {@code containmentRoot}. */
    private static SafeNode safeNode(File containmentRoot, File candidate) throws IOException {
        if (containmentRoot == null || candidate == null) return null;
        Path rootLexical = containmentRoot.toPath().toAbsolutePath().normalize();
        Path candidateLexical = candidate.toPath().toAbsolutePath().normalize();
        if (!candidateLexical.startsWith(rootLexical)
                || Files.isSymbolicLink(candidateLexical)) return null;
        BasicFileAttributes attributes;
        try {
            attributes = Files.readAttributes(candidateLexical, BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
        } catch (java.nio.file.NoSuchFileException absent) {
            return null;
        }
        Path rootReal = rootLexical.toRealPath();
        Path candidateReal = candidateLexical.toRealPath();
        Path expectedReal = rootReal.resolve(rootLexical.relativize(candidateLexical)).normalize();
        // A junction/reparse point resolves to a path other than its lexical position. Never enter it,
        // even when its target happens to remain inside the cache tree.
        if (!candidateReal.startsWith(rootReal) || !candidateReal.equals(expectedReal)) return null;
        return new SafeNode(attributes, candidateReal);
    }

    private static final class SafeNode {
        final BasicFileAttributes attributes;
        final Path realPath;

        SafeNode(BasicFileAttributes attributes, Path realPath) {
            this.attributes = attributes;
            this.realPath = realPath;
        }
    }

    /**
     * Immutable chain of directory identities captured while the family lock is held.  Every
     * cleanup mutation revalidates the project/cache/migration chain, so replacing .migration or
     * any ancestor with a junction cannot redefine the containment root.
     */
    private static final class MigrationTrustAnchor {
        final Path migrationRoot;
        final Path migrationReal;
        final List<AnchoredDirectory> chain;

        private MigrationTrustAnchor(Path migrationRoot, Path migrationReal,
                                     List<AnchoredDirectory> chain) {
            this.migrationRoot = migrationRoot;
            this.migrationReal = migrationReal;
            this.chain = chain;
        }

        static MigrationTrustAnchor capture(File projectRoot, File migrationRoot)
                throws IOException {
            if (projectRoot == null || migrationRoot == null) {
                throw new IOException("Missing migration cleanup trust anchor.");
            }
            Path project = projectRoot.toPath().toAbsolutePath().normalize();
            Path cache = cacheDir(projectRoot).toPath().toAbsolutePath().normalize();
            Path migration = migrationRoot.toPath().toAbsolutePath().normalize();
            if (!cache.startsWith(project) || !migration.startsWith(cache)
                    || Files.isSymbolicLink(project)) {
                throw new IOException("Migration cleanup storage escapes its project root.");
            }
            Path projectReal = project.toRealPath();
            List<AnchoredDirectory> chain = new ArrayList<AnchoredDirectory>();
            Path cursor = project;
            Path relative = project.relativize(migration);
            for (int component = -1; component < relative.getNameCount(); component++) {
                if (component >= 0) cursor = cursor.resolve(relative.getName(component));
                BasicFileAttributes attributes = Files.readAttributes(cursor,
                        BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (!attributes.isDirectory() || Files.isSymbolicLink(cursor)) {
                    throw new IOException("Unsafe migration cleanup ancestor: " + cursor);
                }
                Path real = cursor.toRealPath();
                Path expected = projectReal.resolve(project.relativize(cursor)).normalize();
                if (!real.equals(expected)) {
                    throw new IOException("Linked migration cleanup ancestor: " + cursor);
                }
                chain.add(new AnchoredDirectory(cursor, real, attributes.fileKey(),
                        attributes.creationTime().toMillis()));
            }
            if (!cursor.equals(migration)) throw new IOException(
                    "Migration cleanup ancestry is incomplete.");
            return new MigrationTrustAnchor(migration, migration.toRealPath(), chain);
        }

        void revalidate() throws IOException {
            for (AnchoredDirectory anchored : chain) anchored.revalidate();
        }

        AnchoredDirectory migrationDirectory() throws IOException {
            if (chain.isEmpty()) throw new IOException("Migration cleanup anchor is empty.");
            AnchoredDirectory migration = chain.get(chain.size() - 1);
            if (!migration.lexical.equals(migrationRoot)) {
                throw new IOException("Migration cleanup anchor does not end at its root.");
            }
            return migration;
        }

        void requireDestructiveAuthority() throws RetryableCleanupException {
            for (AnchoredDirectory anchored : chain) {
                if ((anchored.fileKey == null
                        && !Boolean.TRUE.equals(stableFileIdentityOverrideForTest))
                        || Boolean.FALSE.equals(stableFileIdentityOverrideForTest)) {
                    throw new RetryableCleanupException(
                            "Stable filesystem identity is unavailable; cleanup is retained for retry: "
                                    + anchored.lexical);
                }
            }
        }

        boolean hasStableAuthority() {
            if (Boolean.FALSE.equals(stableFileIdentityOverrideForTest)) return false;
            if (Boolean.TRUE.equals(stableFileIdentityOverrideForTest)) return true;
            for (AnchoredDirectory anchored : chain) {
                if (anchored.fileKey == null) return false;
            }
            return true;
        }
    }

    /** A fail-closed cleanup result: retained state is authoritative and may be retried later. */
    static final class RetryableCleanupException extends IOException {
        RetryableCleanupException(String message) {
            super(message);
        }

        RetryableCleanupException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private static final class AnchoredDirectory {
        final Path lexical;
        final Path real;
        final Object fileKey;
        final long creationMillis;

        AnchoredDirectory(Path lexical, Path real, Object fileKey, long creationMillis) {
            this.lexical = lexical;
            this.real = real;
            this.fileKey = fileKey;
            this.creationMillis = creationMillis;
        }

        static AnchoredDirectory capturePlain(Path lexical, Path expectedReal)
                throws IOException {
            if (lexical == null || expectedReal == null || Files.isSymbolicLink(lexical)) {
                throw new IOException("Unsafe retained trust anchor: " + lexical);
            }
            BasicFileAttributes attributes = Files.readAttributes(lexical,
                    BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            Path real = lexical.toRealPath();
            if (!attributes.isDirectory() || !real.equals(expectedReal)) {
                throw new IOException("Linked retained trust anchor: " + lexical);
            }
            return new AnchoredDirectory(lexical, real, attributes.fileKey(),
                    attributes.creationTime().toMillis());
        }

        void revalidate() throws IOException {
            if (Files.isSymbolicLink(lexical)) {
                throw new IOException("Migration cleanup trust anchor became linked: " + lexical);
            }
            BasicFileAttributes attributes = Files.readAttributes(lexical,
                    BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            Object currentKey = attributes.fileKey();
            boolean sameIdentity = fileKey == null
                    ? currentKey == null
                    : fileKey.equals(currentKey);
            sameIdentity &= attributes.creationTime().toMillis() == creationMillis;
            if (!attributes.isDirectory() || !sameIdentity || !lexical.toRealPath().equals(real)) {
                throw new IOException("Migration cleanup trust anchor changed: " + lexical);
            }
        }

    }

    private static void addUniqueLegacyCandidate(List<File> candidates,
                                                 File legacyCandidate,
                                                 LegacyBasenamePolicy policy,
                                                 int matchingLegacySeries) {
        if (policy != LegacyBasenamePolicy.MIGRATE_IF_UNIQUE || matchingLegacySeries != 1) {
            return;
        }
        if (legacyCandidate != null && !candidates.contains(legacyCandidate)) {
            candidates.add(legacyCandidate);
        }
    }

    /** Literal pre-identity paths. These must never invoke qualified-artifact discovery. */
    private static File rawLegacyDeconvFile(File rootDir, String legacyBaseName, int channelIndex) {
        return new File(deconvOutDir(rootDir),
                baseName(legacyBaseName) + "_C" + channelIndex + ".tif");
    }

    private static File rawLegacyMergedDeconvFile(File rootDir, String legacyBaseName) {
        return new File(deconvOutDir(rootDir), baseName(legacyBaseName) + "_deconv.tif");
    }

    private static File rawLegacyCacheFile(File rootDir, String paramsHash,
                                           String legacyBaseName, int channelIndex) {
        return new File(cacheParamsDir(rootDir, paramsHash),
                baseName(legacyBaseName) + "_C" + channelIndex + ".tif");
    }

    private static String canonicalIdentity(int version, long sourceSize,
                                            String sourceContentHash,
                                            String sourceIdentityHash,
                                            int sourceSeriesIndex) {
        if (version == ArtifactIdentity.LEGACY_VERSION) {
            // Preserve every existing v2 key byte-for-byte.
            return "deconv-artifact-identity-v" + version + "\n"
                    + sourceSize + "\n" + sourceContentHash + "\n" + sourceSeriesIndex;
        }
        return "deconv-artifact-identity-v" + version + "\n"
                + "source=" + sourceIdentityHash.length() + ":" + sourceIdentityHash + "\n"
                + "size=" + sourceSize + "\n"
                + "content=" + sourceContentHash.length() + ":" + sourceContentHash + "\n"
                + "series=" + sourceSeriesIndex;
    }

    private static String canonicalSourceIdentity(File projectRoot, File sourceFile)
            throws IOException {
        if (sourceFile == null) {
            throw new IOException("Deconvolution source file is missing.");
        }
        Path sourcePath = sourceFile.toPath().toRealPath();
        if (!Files.isRegularFile(sourcePath)) {
            throw new IOException("Deconvolution source file does not exist: " + sourcePath);
        }
        if (projectRoot != null) {
            Path rootPath = projectRoot.toPath().toRealPath();
            if (sourcePath.startsWith(rootPath)) {
                String relative = rootPath.relativize(sourcePath).toString();
                if (!relative.isEmpty()) {
                    return resolvedProjectSourceIdentity(relative);
                }
            }
        }
        return "absolute:" + normalizeSourcePath(sourcePath.toString(), false);
    }

    /** Build a platform-neutral project key from spelling already resolved by the filesystem. */
    static String resolvedProjectSourceIdentity(String resolvedRelativePath) {
        return "project:" + normalizeSourcePath(resolvedRelativePath, false);
    }

    static String normalizeSourcePath(String value, boolean caseInsensitive) {
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException("Source identity path must not be blank.");
        }
        String normalized = value.replace('\\', '/');
        boolean unc = normalized.startsWith("//");
        String body = unc ? normalized.substring(2) : normalized;
        while (body.contains("//")) {
            body = body.replace("//", "/");
        }
        normalized = unc ? "//" + body : body;
        if (caseInsensitive) {
            normalized = foldWindowsCaseWithoutExpansion(normalized);
        }
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("Source identity path must not be blank.");
        }
        return normalized;
    }

    private static String foldWindowsCaseWithoutExpansion(String value) {
        // NTFS compares names with a one-UTF-16-unit uppercase table. Apply the same non-expanding
        // shape here: full-string U+0130 -> "i" plus U+0307 would alias two distinct legal names.
        char[] folded = value.toCharArray();
        for (int i = 0; i < folded.length; i++) {
            folded[i] = Character.toUpperCase(folded[i]);
        }
        return new String(folded);
    }

    private static boolean isWindows() {
        return File.separatorChar == '\\';
    }

    private static String priorWindowsV3SourceToken(String sourceIdentity,
                                                     String sourceContentHash) {
        if (sourceIdentity == null || !isAscii(sourceIdentity)) return null;
        // The immediately preceding v3 implementation normalized separators and then applied the
        // full Locale.ROOT lowercase mapping. Restrict compatibility to ASCII, where that mapping
        // is one-to-one with Windows case aliases; Unicode folds are deliberately never guessed.
        String priorIdentity = normalizeSourcePath(sourceIdentity, false)
                .toLowerCase(Locale.ROOT);
        return v3SourceToken(priorIdentity, sourceContentHash);
    }

    private static boolean isAscii(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) > 0x7f) return false;
        }
        return true;
    }

    private static String v3SourceToken(String sourceIdentity, String sourceContentHash) {
        if (sourceIdentity == null || sourceIdentity.isEmpty()) {
            throw new IllegalArgumentException(
                    "Version-3 deconvolution identity requires a source discriminator and verified SHA-256 content hash.");
        }
        String identity = normalizeSourcePath(sourceIdentity, false);
        String content = sourceContentHash == null
                ? "" : sourceContentHash.trim().toLowerCase(Locale.ROOT);
        if (!(identity.startsWith("project:") || identity.startsWith("absolute:"))
                || !isSha256(content)) {
            throw new IllegalArgumentException(
                    "Version-3 deconvolution identity requires a source discriminator and verified SHA-256 content hash.");
        }
        return V3_SOURCE_TOKEN_PREFIX + sha256Hex(identity) + "$" + content;
    }

    private static String[] parseV3SourceToken(String value) {
        if (value == null || !value.startsWith(V3_SOURCE_TOKEN_PREFIX)) {
            return null;
        }
        String[] parts = value.substring(V3_SOURCE_TOKEN_PREFIX.length()).split("\\$", -1);
        if (parts.length != 2 || !isSha256(parts[0]) || !isSha256(parts[1])) {
            throw new IllegalArgumentException("Invalid version-3 deconvolution source identity token.");
        }
        return parts;
    }

    private static boolean isSha256(String value) {
        return value != null && value.matches("[0-9a-f]{64}");
    }

    private static String artifactKeyPrefix(int version) {
        return version == ArtifactIdentity.LEGACY_VERSION
                ? LEGACY_ARTIFACT_KEY_PREFIX : CURRENT_ARTIFACT_KEY_PREFIX;
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                sb.append(Character.forDigit((b >>> 4) & 0xf, 16));
                sb.append(Character.forDigit(b & 0xf, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("Could not compute deconvolution artifact identity.", e);
        }
    }

    private static String displaySuffix(String value) {
        String safe = safeToken(value);
        safe = safe.replaceAll("[\\p{Cntrl}]", "_").replaceAll("[ .]+$", "");
        if (safe.matches("(?i)^(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(?:\\..*)?$")) {
            safe = "_" + safe;
        }
        if (safe.length() > DISPLAY_SUFFIX_LIMIT) {
            safe = safe.substring(0, DISPLAY_SUFFIX_LIMIT).trim();
            safe = safe.replaceAll("[ .]+$", "");
        }
        return safe.isEmpty() ? "series" : safe;
    }

    private static File uniqueQualifiedOrLegacy(File directory, String legacyBase, String suffix) {
        File legacy = new File(directory, legacyBase + suffix);
        if (directory == null || !directory.isDirectory()) {
            return legacy;
        }
        File[] matches = directory.listFiles((dir, name) -> name != null
                && (name.startsWith(LEGACY_ARTIFACT_KEY_PREFIX)
                || name.startsWith(CURRENT_ARTIFACT_KEY_PREFIX))
                && name.endsWith("-" + displaySuffix(legacyBase) + suffix));
        if (matches != null && matches.length == 1) return matches[0];
        if (matches != null && matches.length > 1) {
            // A basename-only caller did not supply a source-local series index. Return a
            // deliberately absent path rather than silently selecting one same-named series.
            return new File(directory, ".ambiguous-deconv-artifact-" + legacyBase + suffix);
        }
        return legacy;
    }

    public static boolean isArtifactKey(String value) {
        if (value == null) return false;
        String token = value.trim();
        return token.matches("dcv[23]-[0-9a-f]{64}-s[0-9]+-.+");
    }

    public static String legacyBaseNameToken(String value) {
        return baseName(value);
    }

    private static FlashProjectLayout layout(File rootDir) {
        if (rootDir == null) {
            throw new IllegalArgumentException("Project directory must not be null.");
        }
        return FlashProjectLayout.forDirectory(rootDir.getAbsolutePath());
    }

    private static String baseName(String value) {
        String raw = value == null ? "" : value.trim();
        if (raw.isEmpty()) return "series";
        return safeToken(raw);
    }

    private static String safeToken(String value) {
        String raw = value == null ? "" : value.trim();
        if (raw.isEmpty()) return "series";
        return raw.replaceAll("[\\\\/:*?\"<>|]", "_").replaceAll("\\s+", " ").trim();
    }
}
