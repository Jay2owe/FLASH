package flash.pipeline.deconv;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class DeconvManifestTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    private static Map<String, String> params() {
        Map<String, String> params = new LinkedHashMap<String, String>();
        params.put("engine", "clij2fft");
        params.put("iterations", "20");
        params.put("sampleRi", "1.450000");
        return params;
    }

    @Test
    public void channelFreshnessRequiresMatchingParamsHashAndSource() throws Exception {
        File source = temp.newFile("source.lif");
        Files.write(source.toPath(), "raw-pixels".getBytes(StandardCharsets.UTF_8));
        DeconvManifest.SourceFingerprint fp = DeconvManifest.SourceFingerprint.of(source);

        DeconvManifest manifest = DeconvManifest.empty().withChannel(0,
                new DeconvManifest.ChannelEntry("H1", params(), fp, "clij2fft",
                        DeconvManifest.ENGINE_STAMP_VERSION, 40));

        assertTrue("matching params + source is fresh", manifest.isChannelFresh(0, "H1", fp));
        assertFalse("changed params is stale", manifest.isChannelFresh(0, "H2", fp));
        assertFalse("no entry for channel 1", manifest.isChannelFresh(1, "H1", fp));
    }

    @Test
    public void changingSourceContentWithIdenticalMtimeIsDetected() throws Exception {
        File source = temp.newFile("source.bin");
        Files.write(source.toPath(), "AAAA".getBytes(StandardCharsets.UTF_8));
        DeconvManifest.SourceFingerprint before = DeconvManifest.SourceFingerprint.of(source);

        // Same byte length, different content, and the mtime forced back to the original value:
        // a pure mtime check would (wrongly) call this fresh; the content hash catches it.
        Files.write(source.toPath(), "BBBB".getBytes(StandardCharsets.UTF_8));
        assertTrue(source.setLastModified(before.mtimeMillis));
        DeconvManifest.SourceFingerprint after = DeconvManifest.SourceFingerprint.of(source);

        assertEquals(before.size, after.size);
        assertEquals(before.mtimeMillis, after.mtimeMillis);
        assertFalse("content change must break the fingerprint match", before.matches(after));

        DeconvManifest manifest = DeconvManifest.empty().withChannel(0,
                new DeconvManifest.ChannelEntry("H1", params(), before, "clij2fft",
                        DeconvManifest.ENGINE_STAMP_VERSION, 40));
        assertFalse(manifest.isChannelFresh(0, "H1", after));
    }

    @Test
    public void unchangedContentWithNewMtimeStaysFresh() throws Exception {
        // Dropbox re-hydration bumps mtime without changing content; that must NOT invalidate.
        File source = temp.newFile("source.bin");
        Files.write(source.toPath(), "stable-content".getBytes(StandardCharsets.UTF_8));
        DeconvManifest.SourceFingerprint before = DeconvManifest.SourceFingerprint.of(source);

        assertTrue(source.setLastModified(before.mtimeMillis + 5_000_000L));
        DeconvManifest.SourceFingerprint after = DeconvManifest.SourceFingerprint.of(source);

        assertTrue("mtime change alone must not break freshness", before.matches(after));
    }

    @Test
    public void writeAtomicRoundTripsThroughDisk() throws Exception {
        File source = temp.newFile("source.lif");
        Files.write(source.toPath(), "raw".getBytes(StandardCharsets.UTF_8));
        DeconvManifest.SourceFingerprint fp = DeconvManifest.SourceFingerprint.of(source);

        File manifestFile = new File(temp.getRoot(), "img_deconv.manifest.json");
        DeconvManifest manifest = DeconvManifest.empty().withChannel(2,
                new DeconvManifest.ChannelEntry("HASH2", params(), fp, "DL2",
                        DeconvManifest.ENGINE_STAMP_VERSION, 31));
        DeconvManifest.writeAtomic(manifestFile, manifest);

        DeconvManifest reloaded = DeconvManifest.load(manifestFile);
        DeconvManifest.ChannelEntry entry = reloaded.channel(2);
        assertEquals("HASH2", entry.paramsHash);
        assertEquals("DL2", entry.engineKey);
        assertEquals(31, entry.depth);
        assertEquals("20", entry.hashParams.get("iterations"));
        assertTrue(reloaded.isChannelFresh(2, "HASH2", fp));
        assertTrue(DeconvManifest.isFresh(manifestFile, 2, "HASH2", fp));
        assertFalse(DeconvManifest.isFresh(manifestFile, 2, "OTHER", fp));
    }

    @Test
    public void oversizedLiveManifestIsRejectedByTheStreamingBoundary() throws Exception {
        File manifestFile = temp.newFile("oversized_deconv.manifest.json");
        try (RandomAccessFile sparse = new RandomAccessFile(manifestFile, "rw")) {
            sparse.setLength((long) DeconvManifest.MAX_MANIFEST_UTF8_BYTES + 1L);
        }

        DeconvManifest loaded = DeconvManifest.load(manifestFile);

        assertTrue(loaded.isEmpty());
        assertNull(loaded.artifactIdentity());
    }

    @Test
    public void malformedUtf8ManifestIsRejectedStrictly() throws Exception {
        File manifestFile = temp.newFile("malformed_deconv.manifest.json");
        Files.write(manifestFile.toPath(),
                new byte[]{'{', '"', (byte) 0xc3, 0x28, '"', ':'});

        DeconvManifest loaded = DeconvManifest.load(manifestFile);
        assertTrue(loaded.isEmpty());
        assertNull(loaded.artifactIdentity());
    }

    @Test
    public void sourceMatchesAllRequiresEveryRecordedChannel() throws Exception {
        File source = temp.newFile("source.lif");
        Files.write(source.toPath(), "raw".getBytes(StandardCharsets.UTF_8));
        DeconvManifest.SourceFingerprint fp = DeconvManifest.SourceFingerprint.of(source);

        DeconvManifest.SourceFingerprint stale = new DeconvManifest.SourceFingerprint(
                fp.size + 1, fp.mtimeMillis, fp.contentHash + "x");

        DeconvManifest twoFresh = DeconvManifest.empty()
                .withChannel(0, new DeconvManifest.ChannelEntry("H", params(), fp, "e",
                        DeconvManifest.ENGINE_STAMP_VERSION, 1))
                .withChannel(1, new DeconvManifest.ChannelEntry("H", params(), fp, "e",
                        DeconvManifest.ENGINE_STAMP_VERSION, 1));
        assertTrue(twoFresh.sourceMatchesAll(fp));

        DeconvManifest oneStale = twoFresh.withChannel(1,
                new DeconvManifest.ChannelEntry("H", params(), stale, "e",
                        DeconvManifest.ENGINE_STAMP_VERSION, 1));
        assertFalse(oneStale.sourceMatchesAll(fp));

        assertFalse("empty manifest never matches", DeconvManifest.empty().sourceMatchesAll(fp));
    }

    @Test
    public void mergedRecordRoundTripsThroughDiskAndGatesConsumption() throws Exception {
        File source = temp.newFile("source.lif");
        Files.write(source.toPath(), "raw".getBytes(StandardCharsets.UTF_8));
        DeconvManifest.SourceFingerprint fp = DeconvManifest.SourceFingerprint.of(source);

        java.util.Map<Integer, String> hashes = new java.util.LinkedHashMap<Integer, String>();
        hashes.put(0, "H0");
        hashes.put(1, "H1");
        DeconvManifest manifest = DeconvManifest.empty()
                .withChannel(0, new DeconvManifest.ChannelEntry("H0", params(), fp, "CLIJ2",
                        DeconvManifest.ENGINE_STAMP_VERSION, 8))
                .withChannel(1, new DeconvManifest.ChannelEntry("H1", params(), fp, "CLIJ2",
                        DeconvManifest.ENGINE_STAMP_VERSION, 8))
                .withMerged(new DeconvManifest.MergedRecord(fp, hashes));

        File manifestFile = new File(temp.getRoot(), "img_deconv.manifest.json");
        DeconvManifest.writeAtomic(manifestFile, manifest);
        DeconvManifest reloaded = DeconvManifest.load(manifestFile);

        assertNotNull("merged record survives the JSON round-trip", reloaded.merged());
        assertEquals("H1", reloaded.merged().channelParamsHashes.get(Integer.valueOf(1)));
        // Fresh when the record's per-channel hashes match the current per-channel entries + source.
        assertTrue(reloaded.isMergedFresh(fp, java.util.Arrays.asList(0, 1), null));
    }

    @Test
    public void isMergedFreshRejectsMissingRecordStaleHashAndMissingChannel() throws Exception {
        File source = temp.newFile("source.lif");
        Files.write(source.toPath(), "raw".getBytes(StandardCharsets.UTF_8));
        DeconvManifest.SourceFingerprint fp = DeconvManifest.SourceFingerprint.of(source);

        DeconvManifest base = DeconvManifest.empty()
                .withChannel(0, new DeconvManifest.ChannelEntry("H0", params(), fp, "CLIJ2",
                        DeconvManifest.ENGINE_STAMP_VERSION, 8))
                .withChannel(1, new DeconvManifest.ChannelEntry("H1", params(), fp, "CLIJ2",
                        DeconvManifest.ENGINE_STAMP_VERSION, 8));

        // No merged record at all.
        assertFalse("no merged record -> not consumable",
                base.isMergedFresh(fp, java.util.Arrays.asList(0, 1), null));

        // Merged record points at an OLD hash for channel 1 (mirror advanced without a merge rewrite).
        java.util.Map<Integer, String> stale = new java.util.LinkedHashMap<Integer, String>();
        stale.put(0, "H0");
        stale.put(1, "H1_OLD");
        assertFalse("stale per-channel hash in the merged record -> not consumable",
                base.withMerged(new DeconvManifest.MergedRecord(fp, stale))
                        .isMergedFresh(fp, java.util.Arrays.asList(0, 1), null));

        // A required routed channel (2) has no per-channel entry at all.
        java.util.Map<Integer, String> ok = new java.util.LinkedHashMap<Integer, String>();
        ok.put(0, "H0");
        ok.put(1, "H1");
        assertFalse("a required channel with no entry -> not consumable",
                base.withMerged(new DeconvManifest.MergedRecord(fp, ok))
                        .isMergedFresh(fp, java.util.Arrays.asList(0, 1, 2), null));

        // Source changed.
        DeconvManifest.SourceFingerprint changed = new DeconvManifest.SourceFingerprint(
                fp.size + 3, fp.mtimeMillis, fp.contentHash + "q");
        assertFalse("changed source -> not consumable",
                base.withMerged(new DeconvManifest.MergedRecord(fp, ok))
                        .isMergedFresh(changed, java.util.Arrays.asList(0, 1), null));
    }

    @Test
    public void entryWithNoRecordedSourceIsStaleWhenASourceIsSupplied() throws Exception {
        File source = temp.newFile("source.lif");
        Files.write(source.toPath(), "raw".getBytes(StandardCharsets.UTF_8));
        DeconvManifest.SourceFingerprint fp = DeconvManifest.SourceFingerprint.of(source);

        // A malformed/partial entry with a null recorded source cannot prove the source is unchanged.
        DeconvManifest manifest = DeconvManifest.empty().withChannel(0,
                new DeconvManifest.ChannelEntry("H1", params(), null, "CLIJ2",
                        DeconvManifest.ENGINE_STAMP_VERSION, 8));

        assertFalse("null recorded source + supplied source -> not fresh (string-hash)",
                manifest.isChannelFresh(0, "H1", fp));
        assertFalse("null recorded source + supplied source -> not fresh (params overlay)",
                manifest.isChannelFreshForParams(0, null, fp));
        // With NO source supplied (currentSource == null), the source check is intentionally skipped.
        assertTrue("no source supplied -> source check skipped",
                manifest.isChannelFresh(0, "H1", null));
    }

    @Test
    public void loadIsToleranteOfMissingOrMalformedInput() throws Exception {
        assertTrue(DeconvManifest.load(new File(temp.getRoot(), "absent.json")).isEmpty());
        assertTrue(DeconvManifest.fromJson("this is not json").isEmpty());
        assertTrue(DeconvManifest.fromJson("").isEmpty());
        assertTrue(DeconvManifest.fromJson("[1,2,3]").isEmpty());
        assertNull(DeconvManifest.empty().channel(0));
    }

    @Test
    public void arbitraryMiddleMutationWithPreservedMetadataIsStale() throws Exception {
        File source = temp.newFile("large-container.lif");
        byte[] bytes = new byte[512 * 1024];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) (i * 31 + 7);
        }
        Files.write(source.toPath(), bytes);
        long fixedMtime = 1_700_000_000_000L;
        assertTrue(source.setLastModified(fixedMtime));
        DeconvManifest.SourceFingerprint before = DeconvManifest.SourceFingerprint.of(source);

        RandomAccessFile raf = new RandomAccessFile(source, "rw");
        try {
            long middle = source.length() / 2L + 137L;
            raf.seek(middle);
            int old = raf.read();
            raf.seek(middle);
            raf.write(old ^ 0x5a);
        } finally {
            raf.close();
        }
        assertTrue(source.setLastModified(fixedMtime));
        DeconvManifest.SourceFingerprint after = DeconvManifest.SourceFingerprint.of(source);

        assertEquals(before.size, after.size);
        assertEquals(before.mtimeMillis, after.mtimeMillis);
        assertFalse("full streaming digest must detect arbitrary interior edits", before.matches(after));
    }

    @Test
    public void manifestRoundTripBindsSameNamedSeriesToExactArtifactIdentity() throws Exception {
        File source = temp.newFile("same-names.lif");
        Files.write(source.toPath(), "container-content".getBytes(StandardCharsets.UTF_8));
        DeconvManifest.SourceFingerprint fingerprint = DeconvManifest.SourceFingerprint.of(source);
        DeconvolutionIO.ArtifactIdentity first =
                DeconvolutionIO.ArtifactIdentity.of(fingerprint, 0, "Region");
        DeconvolutionIO.ArtifactIdentity second =
                DeconvolutionIO.ArtifactIdentity.of(fingerprint, 1, "Region");

        DeconvManifest manifest = DeconvManifest.forArtifact(first).withChannel(0,
                new DeconvManifest.ChannelEntry("H1", params(), fingerprint, "CLIJ2",
                        DeconvManifest.ENGINE_STAMP_VERSION, 12));
        File manifestFile = DeconvolutionIO.manifestFile(temp.getRoot(), first);
        DeconvManifest.writeAtomic(manifestFile, manifest);
        DeconvManifest loaded = DeconvManifest.load(manifestFile);

        assertTrue(loaded.matchesArtifact(first));
        assertFalse("same-name sibling series must be stale", loaded.matchesArtifact(second));
        assertTrue(loaded.isChannelFresh(0, "H1", fingerprint, first));
        assertFalse(loaded.isChannelFresh(0, "H1", fingerprint, second));
        assertFalse(DeconvManifest.isFresh(manifestFile, 0, "H1", fingerprint, second));
    }

    @Test
    public void identityManifestCannotBeMovedUnderAnotherArtifactName() throws Exception {
        File source = temp.newFile("source-for-move.lif");
        Files.write(source.toPath(), "verified".getBytes(StandardCharsets.UTF_8));
        DeconvManifest.SourceFingerprint fingerprint = DeconvManifest.SourceFingerprint.of(source);
        DeconvolutionIO.ArtifactIdentity identity =
                DeconvolutionIO.ArtifactIdentity.of(fingerprint, 0, "Region");
        DeconvManifest manifest = DeconvManifest.forArtifact(identity).withChannel(0,
                new DeconvManifest.ChannelEntry("H", params(), fingerprint, "CLIJ2",
                        DeconvManifest.ENGINE_STAMP_VERSION, 1));
        File correct = DeconvolutionIO.manifestFile(temp.getRoot(), identity);
        DeconvManifest.writeAtomic(correct, manifest);

        File wrong = new File(correct.getParentFile(), "guessed_deconv.manifest.json");
        Files.copy(correct.toPath(), wrong.toPath());
        assertTrue("misnamed identity manifest must be rejected", DeconvManifest.load(wrong).isEmpty());
    }

    @Test
    public void bindingIdentityDoesNotLaunderLegacyBasenameRecords() throws Exception {
        File source = temp.newFile("legacy-source.lif");
        Files.write(source.toPath(), "current".getBytes(StandardCharsets.UTF_8));
        DeconvManifest.SourceFingerprint fingerprint = DeconvManifest.SourceFingerprint.of(source);
        DeconvolutionIO.ArtifactIdentity identity =
                DeconvolutionIO.ArtifactIdentity.of(fingerprint, 0, "Region");
        DeconvManifest legacy = DeconvManifest.empty().withChannel(0,
                new DeconvManifest.ChannelEntry("OLD", params(), fingerprint, "legacy",
                        DeconvManifest.ENGINE_STAMP_VERSION, 1));

        DeconvManifest bound = legacy.withArtifactIdentity(identity);
        assertTrue(bound.matchesArtifact(identity));
        assertNull("unattributed basename-only records must be recomputed", bound.channel(0));
    }
}
