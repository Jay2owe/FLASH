package flash.pipeline.deconv;

import flash.pipeline.intelligence.MiniJson;
import flash.pipeline.io.IoUtils;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * Content-stamped freshness record for deconvolution outputs, written atomically as a
 * per-image sidecar ({@code <base>_deconv.manifest.json}) next to the flat mirror files.
 *
 * <p>Historically freshness was decided purely from {@code mirror.lastModified() >=
 * source.lastModified()}. On a synced Dropbox/OneDrive tree that is wrong in both
 * directions (a re-hydrated source looks newer than a valid mirror; a touched stale
 * mirror looks fresh; clock skew across machines defeats it entirely). The manifest
 * records, per channel, the parameter hash the mirror was produced with plus a full streaming
 * SHA-256 fingerprint of the raw source, so freshness becomes content-based rather than
 * clock-based. The flat mirror plus this sidecar are the authority; the params-hash cache
 * directory remains a compute accelerator only.</p>
 *
 * <p>The class is deliberately ImageJ-free so it can be unit-tested without booting Fiji.</p>
 */
public final class DeconvManifest {

    public static final int SCHEMA_VERSION = 2;

    /** Bumped only if the manifest-writing engine contract changes; informational. */
    public static final String ENGINE_STAMP_VERSION = "1";

    private static final String K_SCHEMA = "schemaVersion";
    private static final String K_CHANNELS = "channels";
    private static final String K_PARAMS_HASH = "paramsHash";
    private static final String K_HASH_PARAMS = "hashParams";
    private static final String K_ENGINE_KEY = "engineKey";
    private static final String K_ENGINE_VERSION = "engineVersion";
    private static final String K_DEPTH = "depth";
    private static final String K_SOURCE = "source";
    private static final String K_SIZE = "size";
    private static final String K_MTIME = "mtime";
    private static final String K_CONTENT = "contentHash";
    private static final String K_MERGED = "merged";
    private static final String K_CHANNEL_HASHES = "channelParamsHashes";
    private static final String K_ARTIFACT_IDENTITY = "artifactIdentity";
    private static final String K_IDENTITY_VERSION = "version";
    private static final String K_SOURCE_SERIES_INDEX = "sourceSeriesIndex";
    private static final String K_DISPLAY_SUFFIX = "displaySuffix";

    private static final int DIGEST_BUFFER_BYTES = 1024 * 1024;
    /** Manifests are compact authority metadata, never pixel/cache payloads. */
    static final int MAX_MANIFEST_UTF8_BYTES = 4 * 1024 * 1024;
    private static final MiniJson.Limits MANIFEST_JSON_LIMITS = new MiniJson.Limits(
            MAX_MANIFEST_UTF8_BYTES,
            MAX_MANIFEST_UTF8_BYTES,
            32,
            65536L,
            64 * 1024,
            8192,
            64);
    private static volatile ContentHashTestHook contentHashTestHook;
    private static volatile ExactReadTestHook exactReadTestHook;

    interface ContentHashTestHook {
        void beforeHash(File file) throws IOException;
    }

    static void setContentHashTestHook(ContentHashTestHook hook) {
        contentHashTestHook = hook;
    }

    interface ExactReadTestHook {
        void beforeRead(int requestedBytes);
    }

    static void setExactReadTestHook(ExactReadTestHook hook) {
        exactReadTestHook = hook;
    }

    private final int schemaVersion;
    private final TreeMap<Integer, ChannelEntry> channels;
    /** Content record for the merged {@code _deconv.tif}, or {@code null} if none was written. */
    private final MergedRecord mergedRecord;
    /** Immutable source-container + source-local-series identity for this artifact family. */
    private final DeconvolutionIO.ArtifactIdentity artifactIdentity;

    private DeconvManifest(int schemaVersion, TreeMap<Integer, ChannelEntry> channels,
                          MergedRecord mergedRecord,
                          DeconvolutionIO.ArtifactIdentity artifactIdentity) {
        this.schemaVersion = schemaVersion;
        this.channels = channels;
        this.mergedRecord = mergedRecord;
        this.artifactIdentity = artifactIdentity;
    }

    public static DeconvManifest empty() {
        return new DeconvManifest(SCHEMA_VERSION, new TreeMap<Integer, ChannelEntry>(), null, null);
    }

    public static DeconvManifest forArtifact(DeconvolutionIO.ArtifactIdentity identity) {
        if (identity == null) {
            throw new IllegalArgumentException("Deconvolution artifact identity must not be null.");
        }
        return new DeconvManifest(SCHEMA_VERSION, new TreeMap<Integer, ChannelEntry>(), null, identity);
    }

    public boolean isEmpty() {
        return channels.isEmpty();
    }

    public ChannelEntry channel(int channelIndex) {
        return channels.get(Integer.valueOf(channelIndex));
    }

    public Map<Integer, ChannelEntry> channels() {
        return Collections.unmodifiableMap(channels);
    }

    public MergedRecord merged() {
        return mergedRecord;
    }

    public DeconvolutionIO.ArtifactIdentity artifactIdentity() {
        return artifactIdentity;
    }

    public boolean matchesArtifact(DeconvolutionIO.ArtifactIdentity expected) {
        return expected != null && artifactIdentity != null && artifactIdentity.matches(expected);
    }

    /** Bind to an identity, discarding records if an existing manifest belongs to another series. */
    public DeconvManifest withArtifactIdentity(DeconvolutionIO.ArtifactIdentity identity) {
        if (identity == null) {
            throw new IllegalArgumentException("Deconvolution artifact identity must not be null.");
        }
        if (artifactIdentity == null && (!channels.isEmpty() || mergedRecord != null)) {
            return forArtifact(identity);
        }
        if (artifactIdentity != null && !artifactIdentity.matches(identity)) {
            return forArtifact(identity);
        }
        return new DeconvManifest(SCHEMA_VERSION,
                new TreeMap<Integer, ChannelEntry>(channels), mergedRecord, identity);
    }

    /** Immutable copier: returns a new manifest with the given channel entry set. */
    public DeconvManifest withChannel(int channelIndex, ChannelEntry entry) {
        TreeMap<Integer, ChannelEntry> next = new TreeMap<Integer, ChannelEntry>(channels);
        if (entry == null) {
            next.remove(Integer.valueOf(channelIndex));
        } else {
            next.put(Integer.valueOf(channelIndex), entry);
        }
        return new DeconvManifest(SCHEMA_VERSION, next, mergedRecord, artifactIdentity);
    }

    /** Immutable copier: returns a new manifest with the merged record set (or cleared when null). */
    public DeconvManifest withMerged(MergedRecord record) {
        return new DeconvManifest(SCHEMA_VERSION,
                new TreeMap<Integer, ChannelEntry>(channels), record, artifactIdentity);
    }

    /**
     * True when the recorded channel was produced with {@code expectedParamsHash} and the
     * raw source still matches the recorded fingerprint (content-based, mtime-independent).
     */
    public boolean isChannelFresh(int channelIndex, String expectedParamsHash, SourceFingerprint currentSource) {
        ChannelEntry entry = channel(channelIndex);
        if (entry == null || entry.paramsHash == null) return false;
        if (expectedParamsHash != null && !expectedParamsHash.equals(entry.paramsHash)) return false;
        // When a source is supplied it MUST be verifiable: a recorded entry with no source fingerprint
        // (malformed/partial manifest) cannot prove the source is unchanged, so treat it as stale.
        if (currentSource != null && (entry.source == null || !entry.source.matches(currentSource))) {
            return false;
        }
        return true;
    }

    public boolean isChannelFresh(int channelIndex,
                                  String expectedParamsHash,
                                  SourceFingerprint currentSource,
                                  DeconvolutionIO.ArtifactIdentity expectedIdentity) {
        return matchesArtifact(expectedIdentity)
                && isChannelFresh(channelIndex, expectedParamsHash, currentSource);
    }

    /**
     * True when the manifest has at least one recorded channel and every recorded channel's
     * source fingerprint matches {@code currentSource}. Used for the merged mirror, whose
     * freshness depends on all deconvolved channels sharing the current raw source.
     */
    public boolean sourceMatchesAll(SourceFingerprint currentSource) {
        if (channels.isEmpty()) return false;
        for (ChannelEntry entry : channels.values()) {
            if (entry == null || entry.source == null) return false;
            if (currentSource != null && !entry.source.matches(currentSource)) return false;
        }
        return true;
    }

    // ---- params-staleness (config-param overlay) ------------------------

    /**
     * Params-staleness-aware per-channel freshness: like {@link #isChannelFresh} but the expected
     * params are supplied as the config-derived subset ({@link DeconvParamsHash#buildConfigParams}),
     * NOT a precomputed hash. The recorded channel's geometry keys are trusted (the source fingerprint
     * is verified separately, and geometry is a pure function of the source), so the check overlays the
     * config params onto the recorded {@code hashParams} and recomputes the expected hash. A change to
     * any deconvolution parameter that leaves the source bytes unchanged therefore flips the hash and
     * reads as stale, while a Dropbox re-hydration does not.
     *
     * @param channelIndex          the channel to check
     * @param expectedConfigParams  config-derived params for this channel, or {@code null}/empty to
     *                              skip the params check (source-fingerprint-only, pre-existing behaviour)
     * @param currentSource         the raw source fingerprint to compare against
     */
    public boolean isChannelFreshForParams(int channelIndex,
                                           Map<String, String> expectedConfigParams,
                                           SourceFingerprint currentSource) {
        ChannelEntry entry = channel(channelIndex);
        if (entry == null || entry.paramsHash == null) return false;
        // A supplied source must be verifiable: a recorded entry with no source fingerprint cannot prove
        // the source is unchanged, so treat it as stale rather than skipping the check.
        if (currentSource != null && (entry.source == null || !entry.source.matches(currentSource))) {
            return false;
        }
        return paramsAgree(entry, expectedConfigParams);
    }

    public boolean isChannelFreshForParams(int channelIndex,
                                           Map<String, String> expectedConfigParams,
                                           SourceFingerprint currentSource,
                                           DeconvolutionIO.ArtifactIdentity expectedIdentity) {
        return matchesArtifact(expectedIdentity)
                && isChannelFreshForParams(channelIndex, expectedConfigParams, currentSource);
    }

    /**
     * Content-based freshness for the merged {@code _deconv.tif} — the ONLY sound way to decide the
     * routed merged fast path, because the merged file has no fingerprint of its own and cannot be
     * trusted from the per-channel manifest or mtimes alone (a failed/partial merge rewrite would leave
     * a stale merged file while the per-channel entries advanced).
     *
     * <p>The merged file is consumable iff it was recorded ({@link #merged()} non-null), its recorded
     * source fingerprint matches, and for EVERY required deconvolved channel: (a) the channel has a
     * per-channel entry that is fresh for the current params, and (b) the merged record's recorded
     * params-hash for that channel equals the channel's CURRENT recorded params-hash — i.e. the merged
     * file was actually composed from the mirror the manifest now describes. A change to any channel's
     * params (mirror recomputed, new per-channel hash) that did not also rewrite the merged file leaves
     * the merged record pointing at the old hash, so this returns false and the consumer composes
     * per-channel instead.</p>
     *
     * @param currentSource   the raw source fingerprint to compare against
     * @param requiredChannels the channels the consuming group routes to DECONV (all must be present)
     * @param expectedParams  per-channel config params for the staleness overlay (nullable = source-only)
     */
    public boolean isMergedFresh(SourceFingerprint currentSource,
                                 Iterable<Integer> requiredChannels,
                                 ExpectedDeconvParams expectedParams) {
        if (mergedRecord == null || mergedRecord.source == null) return false;
        if (currentSource != null && !mergedRecord.source.matches(currentSource)) return false;
        if (requiredChannels == null) return false;
        for (Integer channelIndex : requiredChannels) {
            if (channelIndex == null) return false;
            int c = channelIndex.intValue();
            ChannelEntry entry = channel(c);
            if (entry == null || entry.paramsHash == null) return false;
            Map<String, String> cfgParams =
                    expectedParams == null ? null : expectedParams.forChannel(c);
            if (!isChannelFreshForParams(c, cfgParams, currentSource)) return false;
            String mergedHash = mergedRecord.channelParamsHashes.get(Integer.valueOf(c));
            if (mergedHash == null || !mergedHash.equals(entry.paramsHash)) return false;
        }
        return true;
    }

    public boolean isMergedFresh(SourceFingerprint currentSource,
                                 Iterable<Integer> requiredChannels,
                                 ExpectedDeconvParams expectedParams,
                                 DeconvolutionIO.ArtifactIdentity expectedIdentity) {
        return matchesArtifact(expectedIdentity)
                && isMergedFresh(currentSource, requiredChannels, expectedParams);
    }

    /**
     * The recorded channel's params agree with {@code expectedConfigParams} iff overlaying those
     * config keys onto the recorded full param map reproduces the recorded params hash. When
     * {@code expectedConfigParams} is null/empty, the params check is skipped (returns true).
     */
    private static boolean paramsAgree(ChannelEntry entry, Map<String, String> expectedConfigParams) {
        if (expectedConfigParams == null || expectedConfigParams.isEmpty()) return true;
        if (entry == null || entry.paramsHash == null) return false;
        TreeMap<String, String> overlay = new TreeMap<String, String>(entry.hashParams);
        overlay.putAll(expectedConfigParams);
        String expectedHash = DeconvolutionIO.paramsHash(overlay);
        return expectedHash.equals(entry.paramsHash);
    }

    // ---- IO -------------------------------------------------------------

    public String toJson() {
        LinkedHashMap<String, Object> root = new LinkedHashMap<String, Object>();
        root.put(K_SCHEMA, Integer.valueOf(schemaVersion));
        if (artifactIdentity != null) {
            root.put(K_ARTIFACT_IDENTITY, artifactIdentityToJsonMap(artifactIdentity));
        }
        LinkedHashMap<String, Object> channelObj = new LinkedHashMap<String, Object>();
        for (Map.Entry<Integer, ChannelEntry> e : channels.entrySet()) {
            channelObj.put(String.valueOf(e.getKey()), e.getValue().toJsonMap());
        }
        root.put(K_CHANNELS, channelObj);
        if (mergedRecord != null) {
            root.put(K_MERGED, mergedRecord.toJsonMap());
        }
        return MiniJson.write(root);
    }

    /** Parse tolerantly. Any malformed content yields an empty manifest, never an exception. */
    public static DeconvManifest fromJson(String json) {
        if (json == null || json.trim().isEmpty()) return empty();
        try {
            return fromParsed(MiniJson.parse(json, MANIFEST_JSON_LIMITS,
                    "deconvolution manifest"));
        } catch (Exception e) {
            return empty();
        }
    }

    private static DeconvManifest fromParsed(Object parsed) {
        try {
            if (!(parsed instanceof Map)) return empty();
            Map<?, ?> root = (Map<?, ?>) parsed;
            int parsedSchema = asInt(root.get(K_SCHEMA), 1);
            if (parsedSchema < 1 || parsedSchema > SCHEMA_VERSION) return empty();
            DeconvolutionIO.ArtifactIdentity artifactIdentity = null;
            Object identityObj = root.get(K_ARTIFACT_IDENTITY);
            if (identityObj instanceof Map) {
                artifactIdentity = artifactIdentityFromJsonMap((Map<?, ?>) identityObj);
                if (artifactIdentity == null) return empty();
            }
            TreeMap<Integer, ChannelEntry> channels = new TreeMap<Integer, ChannelEntry>();
            Object channelsObj = root.get(K_CHANNELS);
            if (channelsObj instanceof Map) {
                for (Map.Entry<?, ?> e : ((Map<?, ?>) channelsObj).entrySet()) {
                    Integer idx = parseIntKey(String.valueOf(e.getKey()));
                    if (idx == null || !(e.getValue() instanceof Map)) continue;
                    ChannelEntry entry = ChannelEntry.fromJsonMap((Map<?, ?>) e.getValue());
                    if (entry != null) channels.put(idx, entry);
                }
            }
            MergedRecord merged = null;
            Object mergedObj = root.get(K_MERGED);
            if (mergedObj instanceof Map) {
                merged = MergedRecord.fromJsonMap((Map<?, ?>) mergedObj);
            }
            return new DeconvManifest(parsedSchema, channels, merged, artifactIdentity);
        } catch (Exception e) {
            return empty();
        }
    }

    /**
     * Strict, bounded transaction-manifest reader. Unlike {@link #load(File)}, resource and UTF-8
     * failures are reported so recovery can quarantine the transaction rather than trust it.
     */
    static DeconvManifest readBounded(File manifestFile) throws IOException {
        if (manifestFile == null || !Files.isRegularFile(manifestFile.toPath(),
                LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Deconvolution manifest is absent or unsafe: " + manifestFile);
        }
        try (InputStream input = Files.newInputStream(manifestFile.toPath())) {
            return fromParsed(MiniJson.parseUtf8(input, MANIFEST_JSON_LIMITS,
                    manifestFile.getPath()));
        }
    }

    /** Load the manifest sidecar, returning an empty manifest when absent or unreadable. */
    public static DeconvManifest load(File manifestFile) {
        try {
            DeconvManifest loaded = readBounded(manifestFile);
            if (loaded.artifactIdentity != null
                    && !manifestNameMatchesIdentity(manifestFile, loaded.artifactIdentity)) {
                return empty();
            }
            return loaded;
        } catch (IOException e) {
            return empty();
        }
    }

    /** Write the manifest atomically (small-file commit; Dropbox/OneDrive-lock safe). */
    public static void writeAtomic(File manifestFile, DeconvManifest manifest) throws IOException {
        if (manifestFile == null) throw new IOException("manifest path is null");
        DeconvManifest toWrite = manifest == null ? empty() : manifest;
        if (toWrite.artifactIdentity != null
                && !manifestNameMatchesIdentity(manifestFile, toWrite.artifactIdentity)) {
            throw new IOException("Manifest path does not match its deconvolution artifact identity.");
        }
        File parent = manifestFile.getParentFile();
        if (parent != null) {
            IoUtils.mustMkdirs(parent);
        }
        File temp = File.createTempFile(manifestFile.getName() + "-", ".tmp", parent);
        try {
            Files.write(temp.toPath(), toWrite.toJson().getBytes(StandardCharsets.UTF_8));
            IoUtils.commitReplacingSmallFile(temp.toPath(), manifestFile.toPath());
        } finally {
            Files.deleteIfExists(temp.toPath());
        }
    }

    /** Load and evaluate per-channel freshness in one call. */
    public static boolean isFresh(File manifestFile, int channelIndex,
                                  String expectedParamsHash, SourceFingerprint currentSource) {
        return load(manifestFile).isChannelFresh(channelIndex, expectedParamsHash, currentSource);
    }

    public static boolean isFresh(File manifestFile, int channelIndex,
                                  String expectedParamsHash, SourceFingerprint currentSource,
                                  DeconvolutionIO.ArtifactIdentity expectedIdentity) {
        return load(manifestFile).isChannelFresh(channelIndex, expectedParamsHash,
                currentSource, expectedIdentity);
    }

    /** Load and evaluate params-staleness-aware per-channel freshness in one call. */
    public static boolean isFreshForParams(File manifestFile, int channelIndex,
                                           Map<String, String> expectedConfigParams,
                                           SourceFingerprint currentSource) {
        return load(manifestFile)
                .isChannelFreshForParams(channelIndex, expectedConfigParams, currentSource);
    }

    public static boolean isFreshForParams(File manifestFile, int channelIndex,
                                           Map<String, String> expectedConfigParams,
                                           SourceFingerprint currentSource,
                                           DeconvolutionIO.ArtifactIdentity expectedIdentity) {
        return load(manifestFile).isChannelFreshForParams(channelIndex, expectedConfigParams,
                currentSource, expectedIdentity);
    }

    private static LinkedHashMap<String, Object> artifactIdentityToJsonMap(
            DeconvolutionIO.ArtifactIdentity identity) {
        LinkedHashMap<String, Object> map = new LinkedHashMap<String, Object>();
        map.put(K_IDENTITY_VERSION, Integer.valueOf(identity.version));
        map.put(K_SIZE, Long.valueOf(identity.sourceSize));
        map.put(K_CONTENT, identity.sourceContentHash);
        map.put(K_SOURCE_SERIES_INDEX, Integer.valueOf(identity.sourceSeriesIndex));
        map.put(K_DISPLAY_SUFFIX, identity.displaySuffix);
        return map;
    }

    private static DeconvolutionIO.ArtifactIdentity artifactIdentityFromJsonMap(Map<?, ?> map) {
        try {
            int version = asInt(map.get(K_IDENTITY_VERSION), -1);
            long size = asLong(map.get(K_SIZE), -1L);
            String contentHash = asString(map.get(K_CONTENT));
            int seriesIndex = asInt(map.get(K_SOURCE_SERIES_INDEX), -1);
            String displaySuffix = asString(map.get(K_DISPLAY_SUFFIX));
            return new DeconvolutionIO.ArtifactIdentity(version, size, contentHash,
                    seriesIndex, displaySuffix);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static boolean manifestNameMatchesIdentity(File file,
                                                       DeconvolutionIO.ArtifactIdentity identity) {
        if (file == null || identity == null) return false;
        return file.getName().equals(identity.artifactKey + "_deconv.manifest.json");
    }

    private static Integer parseIntKey(String key) {
        try {
            return Integer.valueOf(key.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ---- Nested value types --------------------------------------------

    /**
     * Content record for the merged {@code _deconv.tif}: the raw-source fingerprint it was composed
     * against plus, per deconvolved channel, the params-hash of the mirror it baked in. Lets a consumer
     * prove the merged file reflects the CURRENT per-channel mirrors (see {@link #isMergedFresh}) rather
     * than inferring it from mtimes or from the per-channel manifest alone. Immutable.
     */
    public static final class MergedRecord {
        public final SourceFingerprint source;
        public final Map<Integer, String> channelParamsHashes;

        public MergedRecord(SourceFingerprint source, Map<Integer, String> channelParamsHashes) {
            this.source = source;
            this.channelParamsHashes = channelParamsHashes == null
                    ? Collections.<Integer, String>emptyMap()
                    : Collections.unmodifiableMap(new TreeMap<Integer, String>(channelParamsHashes));
        }

        LinkedHashMap<String, Object> toJsonMap() {
            LinkedHashMap<String, Object> map = new LinkedHashMap<String, Object>();
            if (source != null) {
                map.put(K_SOURCE, source.toJsonMap());
            }
            LinkedHashMap<String, Object> hashes = new LinkedHashMap<String, Object>();
            for (Map.Entry<Integer, String> e : channelParamsHashes.entrySet()) {
                hashes.put(String.valueOf(e.getKey()), e.getValue());
            }
            map.put(K_CHANNEL_HASHES, hashes);
            return map;
        }

        static MergedRecord fromJsonMap(Map<?, ?> map) {
            SourceFingerprint source = null;
            Object sourceObj = map.get(K_SOURCE);
            if (sourceObj instanceof Map) {
                source = SourceFingerprint.fromJsonMap((Map<?, ?>) sourceObj);
            }
            TreeMap<Integer, String> hashes = new TreeMap<Integer, String>();
            Object hashesObj = map.get(K_CHANNEL_HASHES);
            if (hashesObj instanceof Map) {
                for (Map.Entry<?, ?> e : ((Map<?, ?>) hashesObj).entrySet()) {
                    Integer idx = parseIntKey(String.valueOf(e.getKey()));
                    if (idx == null) continue;
                    hashes.put(idx, asString(e.getValue()));
                }
            }
            return new MergedRecord(source, hashes);
        }
    }

    /** One channel's freshness record. Immutable. */
    public static final class ChannelEntry {
        public final String paramsHash;
        public final Map<String, String> hashParams;
        public final SourceFingerprint source;
        public final String engineKey;
        public final String engineVersion;
        public final int depth;

        public ChannelEntry(String paramsHash,
                            Map<String, String> hashParams,
                            SourceFingerprint source,
                            String engineKey,
                            String engineVersion,
                            int depth) {
            this.paramsHash = paramsHash;
            this.hashParams = hashParams == null
                    ? Collections.<String, String>emptyMap()
                    : Collections.unmodifiableMap(new TreeMap<String, String>(hashParams));
            this.source = source;
            this.engineKey = engineKey;
            this.engineVersion = engineVersion;
            this.depth = depth;
        }

        LinkedHashMap<String, Object> toJsonMap() {
            LinkedHashMap<String, Object> map = new LinkedHashMap<String, Object>();
            map.put(K_PARAMS_HASH, paramsHash);
            map.put(K_ENGINE_KEY, engineKey);
            map.put(K_ENGINE_VERSION, engineVersion);
            map.put(K_DEPTH, Integer.valueOf(depth));
            if (source != null) {
                map.put(K_SOURCE, source.toJsonMap());
            }
            LinkedHashMap<String, Object> params = new LinkedHashMap<String, Object>();
            for (Map.Entry<String, String> e : hashParams.entrySet()) {
                params.put(e.getKey(), e.getValue());
            }
            map.put(K_HASH_PARAMS, params);
            return map;
        }

        static ChannelEntry fromJsonMap(Map<?, ?> map) {
            String paramsHash = asString(map.get(K_PARAMS_HASH));
            String engineKey = asString(map.get(K_ENGINE_KEY));
            String engineVersion = asString(map.get(K_ENGINE_VERSION));
            int depth = asInt(map.get(K_DEPTH), -1);
            SourceFingerprint source = null;
            Object sourceObj = map.get(K_SOURCE);
            if (sourceObj instanceof Map) {
                source = SourceFingerprint.fromJsonMap((Map<?, ?>) sourceObj);
            }
            TreeMap<String, String> params = new TreeMap<String, String>();
            Object paramsObj = map.get(K_HASH_PARAMS);
            if (paramsObj instanceof Map) {
                for (Map.Entry<?, ?> e : ((Map<?, ?>) paramsObj).entrySet()) {
                    params.put(String.valueOf(e.getKey()), asString(e.getValue()));
                }
            }
            return new ChannelEntry(paramsHash, params, source, engineKey, engineVersion, depth);
        }
    }

    /**
     * Verified content fingerprint of the raw source: total size plus a full streaming SHA-256
     * digest. {@link #matches} compares size + content only (never mtime), so cloud re-hydration
     * remains fresh while an arbitrary mutation anywhere in the container is detected.
     */
    public static final class SourceFingerprint {
        public final long size;
        public final long mtimeMillis;
        public final String contentHash;

        public SourceFingerprint(long size, long mtimeMillis, String contentHash) {
            this.size = size;
            this.mtimeMillis = mtimeMillis;
            this.contentHash = contentHash == null ? "" : contentHash;
        }

        public static SourceFingerprint of(File source) throws IOException {
            if (source == null || !source.isFile()) {
                return new SourceFingerprint(-1L, -1L, "");
            }
            long size = source.length();
            long mtime = source.lastModified();
            return new SourceFingerprint(size, mtime, strongContentHash(source));
        }

        /** Content equality: size + contentHash, ignoring mtime by design. */
        public boolean matches(SourceFingerprint other) {
            if (other == null) return false;
            return size == other.size && contentHash.equals(other.contentHash);
        }

        LinkedHashMap<String, Object> toJsonMap() {
            LinkedHashMap<String, Object> map = new LinkedHashMap<String, Object>();
            map.put(K_SIZE, Long.valueOf(size));
            map.put(K_MTIME, Long.valueOf(mtimeMillis));
            map.put(K_CONTENT, contentHash);
            return map;
        }

        static SourceFingerprint fromJsonMap(Map<?, ?> map) {
            long size = asLong(map.get(K_SIZE), -1L);
            long mtime = asLong(map.get(K_MTIME), -1L);
            String content = asString(map.get(K_CONTENT));
            return new SourceFingerprint(size, mtime, content == null ? "" : content);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof SourceFingerprint)) return false;
            SourceFingerprint that = (SourceFingerprint) o;
            return size == that.size && contentHash.equals(that.contentHash);
        }

        @Override
        public int hashCode() {
            return (int) (size ^ (size >>> 32)) * 31 + contentHash.hashCode();
        }

        private static String strongContentHash(File source) throws IOException {
            ContentHashTestHook testHook = contentHashTestHook;
            if (testHook != null) testHook.beforeHash(source);
            final MessageDigest digest;
            try {
                digest = MessageDigest.getInstance("SHA-256");
            } catch (Exception e) {
                throw new IOException("SHA-256 is unavailable for source verification.", e);
            }
            InputStream in = new BufferedInputStream(Files.newInputStream(source.toPath()),
                    DIGEST_BUFFER_BYTES);
            try {
                byte[] buffer = new byte[DIGEST_BUFFER_BYTES];
                int read;
                while ((read = in.read(buffer)) >= 0) {
                    if (read > 0) {
                        digest.update(buffer, 0, read);
                    }
                }
            } finally {
                in.close();
            }
            return toHex(digest.digest());
        }

        /**
         * Hash exactly the journal-authenticated byte count and reject both truncation and growth.
         * At most {@code expectedSize + 1} bytes are read, even when an untrusted recovery file is
         * sparse or is being appended continuously.
         */
        static String exactContentHash(File source, long expectedSize) throws IOException {
            return exactContent(source, expectedSize).contentHash;
        }

        /**
         * Authenticate one open file and retain enough identity to prove that the lexical path
         * still names that same file immediately before a recovery action.  FileChannel is used
         * deliberately: unlike BufferedInputStream it cannot read ahead beyond the requested
         * authenticated byte count.
         */
        static ExactContent exactContent(File source, long expectedSize) throws IOException {
            if (source == null || expectedSize < 0L) {
                throw new IOException("Invalid exact fingerprint request.");
            }
            ContentHashTestHook testHook = contentHashTestHook;
            if (testHook != null) testHook.beforeHash(source);
            final MessageDigest digest;
            try {
                digest = MessageDigest.getInstance("SHA-256");
            } catch (Exception e) {
                throw new IOException("SHA-256 is unavailable for source verification.", e);
            }
            Path path = source.toPath().toAbsolutePath().normalize();
            BasicFileAttributes before = exactAttributes(path);
            if (!before.isRegularFile()) throw new IOException(
                    "Recovery artifact is not a regular file.");
            if (before.size() < expectedSize) throw new IOException(
                    "Recovery artifact was truncated before hashing.");
            if (before.size() > expectedSize) throw new IOException(
                    "Recovery artifact grew beyond its authenticated length.");
            Path realPath = path.toRealPath(LinkOption.NOFOLLOW_LINKS);
            try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ,
                    LinkOption.NOFOLLOW_LINKS)) {
                if (channel.size() != expectedSize) {
                    throw new IOException("Recovery artifact length changed while opening.");
                }
                BasicFileAttributes afterOpen = exactAttributes(path);
                requireSameIdentity(before, afterOpen, expectedSize);
                byte[] buffer = new byte[DIGEST_BUFFER_BYTES];
                long remaining = expectedSize;
                while (remaining > 0L) {
                    int requested = (int) Math.min((long) buffer.length, remaining);
                    ByteBuffer bytes = ByteBuffer.wrap(buffer, 0, requested);
                    ExactReadTestHook readHook = exactReadTestHook;
                    if (readHook != null) readHook.beforeRead(requested);
                    int read = channel.read(bytes);
                    if (read < 0) throw new IOException("Recovery artifact was truncated while hashing.");
                    if (read == 0) continue;
                    digest.update(buffer, 0, read);
                    remaining -= read;
                }
                ByteBuffer oneByte = ByteBuffer.allocate(1);
                ExactReadTestHook readHook = exactReadTestHook;
                if (readHook != null) readHook.beforeRead(1);
                if (channel.read(oneByte) >= 0) {
                    throw new IOException("Recovery artifact grew beyond its authenticated length.");
                }
                if (channel.size() != expectedSize) {
                    throw new IOException("Recovery artifact length changed while hashing.");
                }
                BasicFileAttributes afterHash = exactAttributes(path);
                requireSameIdentity(before, afterHash, expectedSize);
                return new ExactContent(toHex(digest.digest()), path, realPath, expectedSize,
                        before.fileKey(), before.creationTime().toMillis());
            }
        }

        private static BasicFileAttributes exactAttributes(Path path) throws IOException {
            if (Files.isSymbolicLink(path)) {
                throw new IOException("Recovery artifact is a symbolic link.");
            }
            return Files.readAttributes(path, BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
        }

        private static void requireSameIdentity(BasicFileAttributes expected,
                                                BasicFileAttributes actual,
                                                long expectedSize) throws IOException {
            if (!actual.isRegularFile() || actual.size() != expectedSize
                    || !sameFileKey(expected, actual)) {
                throw new IOException("Recovery artifact identity changed while hashing.");
            }
        }

        private static boolean sameFileKey(BasicFileAttributes first,
                                           BasicFileAttributes second) {
            Object firstKey = first.fileKey();
            Object secondKey = second.fileKey();
            if (firstKey != null || secondKey != null) {
                return firstKey != null && firstKey.equals(secondKey);
            }
            // Some Java-8 providers do not expose fileKey.  Creation time plus the no-follow real
            // path below is the strongest portable conservative fallback; a mismatch only defers
            // recovery and never authorizes a mutation.
            return first.creationTime().equals(second.creationTime());
        }

        static final class ExactContent {
            final String contentHash;
            private final Path lexicalPath;
            private final Path realPath;
            private final long size;
            private final Object fileKey;
            private final long creationMillis;

            ExactContent(String contentHash, Path lexicalPath, Path realPath, long size,
                         Object fileKey, long creationMillis) {
                this.contentHash = contentHash;
                this.lexicalPath = lexicalPath;
                this.realPath = realPath;
                this.size = size;
                this.fileKey = fileKey;
                this.creationMillis = creationMillis;
            }

            boolean stillNames(File file) {
                if (file == null) return false;
                Path current = file.toPath().toAbsolutePath().normalize();
                if (!lexicalPath.equals(current) || Files.isSymbolicLink(current)) return false;
                try {
                    BasicFileAttributes attributes = exactAttributes(current);
                    if (!attributes.isRegularFile() || attributes.size() != size
                            || !current.toRealPath(LinkOption.NOFOLLOW_LINKS).equals(realPath)) {
                        return false;
                    }
                    Object currentKey = attributes.fileKey();
                    if (fileKey != null || currentKey != null) {
                        return fileKey != null && fileKey.equals(currentKey);
                    }
                    if (attributes.creationTime().toMillis() != creationMillis) return false;
                    // The Windows Java-8 provider commonly returns a null fileKey and creation
                    // timestamps are too coarse to distinguish a rapid same-length replacement.
                    // Re-authenticate exact bytes immediately before the action in that fallback.
                    ExactContent currentExact = exactContent(file, size);
                    return contentHash.equals(currentExact.contentHash)
                            && currentExact.realPath.equals(realPath);
                } catch (IOException changedOrMissing) {
                    return false;
                }
            }

            boolean hasStableFileIdentity() {
                return fileKey != null;
            }
        }

        private static String toHex(byte[] bytes) {
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                sb.append(Character.forDigit((b >> 4) & 0xf, 16));
                sb.append(Character.forDigit(b & 0xf, 16));
            }
            return sb.toString();
        }
    }

    private static String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static int asInt(Object value, int fallback) {
        if (value instanceof Number) return ((Number) value).intValue();
        if (value instanceof String) {
            try {
                return Integer.parseInt(((String) value).trim());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private static long asLong(Object value, long fallback) {
        if (value instanceof Number) return ((Number) value).longValue();
        if (value instanceof String) {
            try {
                return Long.parseLong(((String) value).trim());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }
}
