package flash.pipeline.deconv;

import org.junit.Rule;
import org.junit.Test;
import org.junit.Before;
import org.junit.After;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Base64;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeTrue;

public class DeconvolutionIOTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Before
    public void assumeDirectoryDurabilityForStateMachineTests() {
        DeconvolutionIO.setDirectoryDurabilityOverrideForTest(Boolean.TRUE);
        // Exercise the keyed-provider state machine deterministically on Windows JDKs that do
        // not expose directory file keys. Dedicated tests below force the keyless branch.
        DeconvolutionIO.setStableFileIdentityOverrideForTest(Boolean.TRUE);
    }

    @After
    public void clearDirectoryDurabilityOverride() {
        DeconvolutionIO.setDirectoryDurabilityOverrideForTest(null);
        DeconvolutionIO.setDirectoryForceHookForTest(null);
        DeconvolutionIO.setCleanupTraversalHookForTest(null);
        DeconvolutionIO.setExactFileActionHookForTest(null);
        DeconvolutionIO.setFinalExactClassificationHookForTest(null);
        DeconvolutionIO.setDeleteBindingHookForTest(null);
        DeconvolutionIO.setTreeRetentionMoveHookForTest(null);
        DeconvolutionIO.setOpaqueRetentionMoveHookForTest(null);
        DeconvolutionIO.setQueueDirectoryHookForTest(null);
        DeconvolutionIO.setRecoverySnapshotIoHookForTest(null);
        DeconvolutionIO.setRecoveryDirectoryHookForTest(null);
        DeconvolutionIO.setStableFileIdentityOverrideForTest(null);
        DeconvManifest.setContentHashTestHook(null);
        DeconvManifest.setExactReadTestHook(null);
    }

    @Test
    public void paramsHashIsStableAcrossMapOrdering() {
        Map<String, String> first = new LinkedHashMap<String, String>();
        first.put("engine", "DL2");
        first.put("algorithm", "RL_TV");
        first.put("iterations", "15");
        first.put("sampleRi", "1.450000");

        Map<String, String> reordered = new LinkedHashMap<String, String>();
        reordered.put("sampleRi", "1.450000");
        reordered.put("iterations", "15");
        reordered.put("algorithm", "RL_TV");
        reordered.put("engine", "DL2");

        assertEquals(DeconvolutionIO.paramsHash(first), DeconvolutionIO.paramsHash(reordered));
    }

    @Test
    public void paramsHashChangesWhenAnyRelevantParameterChanges() {
        Map<String, String> base = new LinkedHashMap<String, String>();
        base.put("engine", "DL2");
        base.put("algorithm", "RL_TV");
        base.put("iterations", "15");
        base.put("sampleRi", "1.450000");

        Map<String, String> changed = new LinkedHashMap<String, String>(base);
        changed.put("iterations", "16");

        assertNotEquals(DeconvolutionIO.paramsHash(base), DeconvolutionIO.paramsHash(changed));
    }

    @Test
    public void cacheFreshDependsOnSourceAndCacheModificationTimes() throws Exception {
        File source = temp.newFile("source.lif");
        File cache = temp.newFile("cached.tif");
        Files.write(source.toPath(), "src".getBytes(StandardCharsets.UTF_8));
        Files.write(cache.toPath(), "cache".getBytes(StandardCharsets.UTF_8));

        assertTrue(source.setLastModified(1_000L));
        assertTrue(cache.setLastModified(2_000L));
        assertTrue(DeconvolutionIO.isCacheFresh(source, cache));

        assertTrue(cache.setLastModified(500L));
        assertFalse(DeconvolutionIO.isCacheFresh(source, cache));
    }

    @Test
    public void mergedDeconvolvedOutputLandsUnderAnalysisImagesDeconvolution() {
        File root = temp.getRoot();
        File expectedDir = new File(new File(new File(new File(root, "FLASH"), "Results"),
                "Analysis Images"), "Deconvolution");
        File merged = DeconvolutionIO.mergedDeconvFile(root, "My Image");
        assertEquals(new File(expectedDir, "My Image_deconv.tif"), merged);
    }

    @Test
    public void deconvolutionOutputsUseAnalysisImagesDeconvolutionDir() {
        File root = temp.getRoot();
        File expectedDir = new File(new File(new File(new File(root, "FLASH"), "Results"),
                "Analysis Images"), "Deconvolution");

        assertEquals(expectedDir, DeconvolutionIO.deconvOutDir(root));

        List<File> candidates = DeconvolutionIO.mergedDeconvFileReadCandidates(root, "My Image");
        assertEquals(1, candidates.size());
        assertEquals(new File(expectedDir, "My Image_deconv.tif"), candidates.get(0));
    }

    @Test
    public void cacheOutputsUseFlashCacheFolder() {
        File root = temp.getRoot();
        String paramsHash = "ABC123";

        assertEquals(new File(new File(new File(root, "FLASH"), "Cache"), "3D Deconvolution"),
                DeconvolutionIO.cacheDir(root));
        assertEquals(new File(new File(new File(new File(root, "FLASH"), "Cache"), "3D Deconvolution"), paramsHash),
                DeconvolutionIO.cacheParamsDir(root, paramsHash));

        List<File> candidates = DeconvolutionIO.cacheFileReadCandidates(root, paramsHash, "My Image", 1);
        assertEquals(1, candidates.size());
        assertEquals(new File(new File(new File(new File(new File(root, "FLASH"), "Cache"), "3D Deconvolution"),
                        paramsHash), "My Image_C1.tif"),
                candidates.get(0));
    }

    @Test
    public void sameNamedSeriesUseOneDistinctIdentityAcrossEveryArtifactFamily() throws Exception {
        File source = temp.newFile("container.lif");
        Files.write(source.toPath(), "one-container-two-series".getBytes(StandardCharsets.UTF_8));
        DeconvManifest.SourceFingerprint fingerprint = DeconvManifest.SourceFingerprint.of(source);
        DeconvolutionIO.ArtifactIdentity first =
                DeconvolutionIO.ArtifactIdentity.of(fingerprint, 0, "Region");
        DeconvolutionIO.ArtifactIdentity second =
                DeconvolutionIO.ArtifactIdentity.of(fingerprint, 1, "Region");

        assertNotEquals(first.artifactKey, second.artifactKey);
        assertNotEquals(DeconvolutionIO.deconvFile(temp.getRoot(), first, 0),
                DeconvolutionIO.deconvFile(temp.getRoot(), second, 0));
        assertNotEquals(DeconvolutionIO.cacheFile(temp.getRoot(), "PARAMS", first, 0),
                DeconvolutionIO.cacheFile(temp.getRoot(), "PARAMS", second, 0));
        assertNotEquals(DeconvolutionIO.mergedDeconvFile(temp.getRoot(), first),
                DeconvolutionIO.mergedDeconvFile(temp.getRoot(), second));
        assertNotEquals(DeconvolutionIO.manifestFile(temp.getRoot(), first),
                DeconvolutionIO.manifestFile(temp.getRoot(), second));
        assertNotEquals(DeconvolutionIO.detailsFile(temp.getRoot(), first),
                DeconvolutionIO.detailsFile(temp.getRoot(), second));

        assertEquals(DeconvolutionIO.deconvFile(temp.getRoot(), first.artifactKey, 0),
                DeconvolutionIO.deconvFile(temp.getRoot(), first, 0));
        assertTrue(DeconvolutionIO.isArtifactKey(first.artifactKey));
    }

    @Test
    public void displaySuffixIsSanitizedButCannotChangeScientificIdentity() {
        String digest = repeat("ab", 32);
        DeconvolutionIO.ArtifactIdentity unsafe = new DeconvolutionIO.ArtifactIdentity(
                DeconvolutionIO.ArtifactIdentity.VERSION, 42L, digest,
                "project:input/container.lif", 3, "Region:/\\*?<>|");
        DeconvolutionIO.ArtifactIdentity renamed = new DeconvolutionIO.ArtifactIdentity(
                DeconvolutionIO.ArtifactIdentity.VERSION, 42L, digest,
                "project:input/container.lif", 3, "Renamed region");

        assertTrue(unsafe.matches(renamed));
        assertEquals(unsafe.identityHash, renamed.identityHash);
        assertNotEquals("display suffix remains human-readable only",
                unsafe.artifactKey, renamed.artifactKey);
        assertFalse(unsafe.artifactKey.matches(".*[\\\\/:*?\"<>|].*"));
        DeconvolutionIO.ArtifactIdentity reserved = new DeconvolutionIO.ArtifactIdentity(
                DeconvolutionIO.ArtifactIdentity.VERSION, 42L, digest,
                "project:input/container.lif", 3, "CON. ");
        assertEquals("_CON", reserved.displaySuffix);
        assertEquals(DeconvolutionIO.legacyBaseNameToken("A/B"),
                DeconvolutionIO.legacyBaseNameToken("A:B"));
    }

    @Test
    public void legacyBasenameRequiresExplicitUniqueMigrationPolicy() {
        DeconvolutionIO.ArtifactIdentity identity = new DeconvolutionIO.ArtifactIdentity(
                DeconvolutionIO.ArtifactIdentity.LEGACY_VERSION,
                7L, repeat("cd", 32), 0, "Region");
        assertEquals("v2 canonical bytes must remain stable for deterministic legacy discovery",
                "dad3c3719ae46367404f1b6896772bee0c278bccee8b62251572760595ce34e3",
                identity.identityHash);

        List<File> rejected = DeconvolutionIO.deconvFileReadCandidates(temp.getRoot(), identity,
                0, "Region", DeconvolutionIO.LegacyBasenamePolicy.REJECT, 1);
        assertEquals(1, rejected.size());

        List<File> ambiguous = DeconvolutionIO.deconvFileReadCandidates(temp.getRoot(), identity,
                0, "Region", DeconvolutionIO.LegacyBasenamePolicy.MIGRATE_IF_UNIQUE, 2);
        assertEquals("duplicate names must never be guessed", 1, ambiguous.size());

        List<File> explicitUnique = DeconvolutionIO.deconvFileReadCandidates(temp.getRoot(), identity,
                0, "Region", DeconvolutionIO.LegacyBasenamePolicy.MIGRATE_IF_UNIQUE, 1);
        assertEquals(2, explicitUnique.size());
        assertEquals(DeconvolutionIO.deconvFile(temp.getRoot(), identity, 0), explicitUnique.get(0));
        assertEquals(DeconvolutionIO.deconvFile(temp.getRoot(), "Region", 0), explicitUnique.get(1));
    }

    @Test
    public void middleByteMutationMovesEveryIdentityQualifiedPath() throws Exception {
        File source = temp.newFile("mutable-container.lif");
        byte[] content = new byte[256 * 1024];
        for (int i = 0; i < content.length; i++) content[i] = (byte) i;
        Files.write(source.toPath(), content);
        long mtime = 1_700_000_000_000L;
        assertTrue(source.setLastModified(mtime));
        DeconvolutionIO.ArtifactIdentity before =
                DeconvolutionIO.ArtifactIdentity.of(source, 0, "Region");

        RandomAccessFile raf = new RandomAccessFile(source, "rw");
        try {
            raf.seek(content.length / 2L);
            int old = raf.read();
            raf.seek(content.length / 2L);
            raf.write(old ^ 0xff);
        } finally {
            raf.close();
        }
        assertTrue(source.setLastModified(mtime));
        DeconvolutionIO.ArtifactIdentity after =
                DeconvolutionIO.ArtifactIdentity.of(source, 0, "Region");

        assertNotEquals(before.artifactKey, after.artifactKey);
        assertNotEquals(DeconvolutionIO.deconvFile(temp.getRoot(), before, 0),
                DeconvolutionIO.deconvFile(temp.getRoot(), after, 0));
        assertNotEquals(DeconvolutionIO.cacheFile(temp.getRoot(), "P", before, 0),
                DeconvolutionIO.cacheFile(temp.getRoot(), "P", after, 0));
        assertNotEquals(DeconvolutionIO.manifestFile(temp.getRoot(), before),
                DeconvolutionIO.manifestFile(temp.getRoot(), after));
    }

    @Test
    public void identityAwareLegacyCandidatesNeverResolveAnotherQualifiedIdentity() throws Exception {
        File root = temp.getRoot();
        DeconvolutionIO.ArtifactIdentity intended = new DeconvolutionIO.ArtifactIdentity(
                DeconvolutionIO.ArtifactIdentity.LEGACY_VERSION,
                100L, repeat("11", 32), 0, "Region");
        DeconvolutionIO.ArtifactIdentity other = new DeconvolutionIO.ArtifactIdentity(
                DeconvolutionIO.ArtifactIdentity.LEGACY_VERSION,
                100L, repeat("22", 32), 0, "Region");

        File otherChannel = DeconvolutionIO.deconvFile(root, other, 0);
        File otherMerged = DeconvolutionIO.mergedDeconvFile(root, other);
        File otherCache = DeconvolutionIO.cacheFile(root, "PARAMS", other, 0);
        Files.createDirectories(otherChannel.getParentFile().toPath());
        Files.createDirectories(otherCache.getParentFile().toPath());
        Files.write(otherChannel.toPath(), "other-channel".getBytes(StandardCharsets.UTF_8));
        Files.write(otherMerged.toPath(), "other-merged".getBytes(StandardCharsets.UTF_8));
        Files.write(otherCache.toPath(), "other-cache".getBytes(StandardCharsets.UTF_8));

        List<File> channelCandidates = DeconvolutionIO.deconvFileReadCandidates(root, intended,
                0, "Region", DeconvolutionIO.LegacyBasenamePolicy.MIGRATE_IF_UNIQUE, 1);
        assertEquals(2, channelCandidates.size());
        assertEquals(DeconvolutionIO.deconvFile(root, intended, 0), channelCandidates.get(0));
        assertEquals(new File(DeconvolutionIO.deconvOutDir(root), "Region_C0.tif"),
                channelCandidates.get(1));
        assertFalse(channelCandidates.contains(otherChannel));

        List<File> mergedCandidates = DeconvolutionIO.mergedDeconvFileReadCandidates(root, intended,
                "Region", DeconvolutionIO.LegacyBasenamePolicy.MIGRATE_IF_UNIQUE, 1);
        assertEquals(2, mergedCandidates.size());
        assertEquals(DeconvolutionIO.mergedDeconvFile(root, intended), mergedCandidates.get(0));
        assertEquals(new File(DeconvolutionIO.deconvOutDir(root), "Region_deconv.tif"),
                mergedCandidates.get(1));
        assertFalse(mergedCandidates.contains(otherMerged));

        List<File> cacheCandidates = DeconvolutionIO.cacheFileReadCandidates(root, "PARAMS", intended,
                0, "Region", DeconvolutionIO.LegacyBasenamePolicy.MIGRATE_IF_UNIQUE, 1);
        assertEquals(2, cacheCandidates.size());
        assertEquals(DeconvolutionIO.cacheFile(root, "PARAMS", intended, 0), cacheCandidates.get(0));
        assertEquals(new File(DeconvolutionIO.cacheParamsDir(root, "PARAMS"), "Region_C0.tif"),
                cacheCandidates.get(1));
        assertFalse(cacheCandidates.contains(otherCache));

        // The analysis failure cleanup deletes exactly these candidate lists. Prove it cannot
        // reach the other identity-qualified artifacts through the legacy compatibility resolver.
        for (File candidate : channelCandidates) Files.deleteIfExists(candidate.toPath());
        for (File candidate : mergedCandidates) Files.deleteIfExists(candidate.toPath());
        assertTrue(otherChannel.isFile());
        assertTrue(otherMerged.isFile());
        assertTrue(otherCache.isFile());
    }

    @Test
    public void byteIdenticalCopiedContainersAtDifferentProjectPathsNeverShareIdentity()
            throws Exception {
        File project = temp.newFolder("copied-containers-project");
        File first = new File(project, "input/first/container.lif");
        File second = new File(project, "input/second/container.lif");
        Files.createDirectories(first.getParentFile().toPath());
        Files.createDirectories(second.getParentFile().toPath());
        byte[] identical = "byte-identical-container".getBytes(StandardCharsets.UTF_8);
        Files.write(first.toPath(), identical);
        Files.write(second.toPath(), identical);
        DeconvManifest.SourceFingerprint firstFingerprint =
                DeconvManifest.SourceFingerprint.of(first);
        DeconvManifest.SourceFingerprint secondFingerprint =
                DeconvManifest.SourceFingerprint.of(second);

        DeconvolutionIO.ArtifactIdentity firstIdentity =
                DeconvolutionIO.ArtifactIdentity.of(project, first, firstFingerprint, 0, "Region");
        DeconvolutionIO.ArtifactIdentity repeated =
                DeconvolutionIO.ArtifactIdentity.of(project, first, firstFingerprint, 0, "Region");
        DeconvolutionIO.ArtifactIdentity secondIdentity =
                DeconvolutionIO.ArtifactIdentity.of(project, second, secondFingerprint, 0, "Region");

        assertEquals("same container must be repeatable", firstIdentity, repeated);
        assertEquals(firstIdentity.artifactKey, repeated.artifactKey);
        assertNotEquals(firstIdentity.sourceIdentityHash, secondIdentity.sourceIdentityHash);
        assertNotEquals(firstIdentity.artifactKey, secondIdentity.artifactKey);
        assertNotEquals(DeconvolutionIO.deconvFile(project, firstIdentity, 0),
                DeconvolutionIO.deconvFile(project, secondIdentity, 0));
        assertNotEquals(DeconvolutionIO.cacheFile(project, "P", firstIdentity, 0),
                DeconvolutionIO.cacheFile(project, "P", secondIdentity, 0));
    }

    @Test
    public void completeProjectRelocationPreservesProjectRelativeIdentity() throws Exception {
        File firstProject = temp.newFolder("relocation-before");
        File secondProject = temp.newFolder("relocation-after");
        File firstSource = new File(firstProject, "input/nested/sample.lif");
        File secondSource = new File(secondProject, "input/nested/sample.lif");
        Files.createDirectories(firstSource.getParentFile().toPath());
        Files.createDirectories(secondSource.getParentFile().toPath());
        byte[] content = "relocated-project-container".getBytes(StandardCharsets.UTF_8);
        Files.write(firstSource.toPath(), content);
        Files.write(secondSource.toPath(), content);

        DeconvolutionIO.ArtifactIdentity before = DeconvolutionIO.ArtifactIdentity.of(
                firstProject, firstSource, DeconvManifest.SourceFingerprint.of(firstSource),
                2, "Region");
        DeconvolutionIO.ArtifactIdentity after = DeconvolutionIO.ArtifactIdentity.of(
                secondProject, secondSource, DeconvManifest.SourceFingerprint.of(secondSource),
                2, "Region");

        assertEquals("moving a complete project preserves its relative source identity",
                before, after);
        assertEquals(before.artifactKey, after.artifactKey);
    }

    @Test
    public void standaloneIdenticalTiffsRemainDistinctByCanonicalSource() throws Exception {
        File first = temp.newFile("standalone-one.tif");
        File second = temp.newFile("standalone-two.tif");
        byte[] content = "same-standalone-tiff".getBytes(StandardCharsets.UTF_8);
        Files.write(first.toPath(), content);
        Files.write(second.toPath(), content);

        DeconvolutionIO.ArtifactIdentity firstIdentity =
                DeconvolutionIO.ArtifactIdentity.of(first, 0, "Image");
        DeconvolutionIO.ArtifactIdentity secondIdentity =
                DeconvolutionIO.ArtifactIdentity.of(second, 0, "Image");

        assertNotEquals(firstIdentity.sourceIdentityHash, secondIdentity.sourceIdentityHash);
        assertNotEquals(firstIdentity.artifactKey, secondIdentity.artifactKey);
    }

    @Test
    public void sourcePathNormalizationFoldsWindowsCaseWithoutChangingUnicodeComposition() {
        String composed = "Input\\CAF\u00c9\\Container.LIF";
        String decomposed = "input/cafe\u0301/container.lif";

        assertNotEquals("NTFS permits both literal names in one directory",
                DeconvolutionIO.normalizeSourcePath(composed, true),
                DeconvolutionIO.normalizeSourcePath(decomposed, true));
        assertEquals("Windows case variants of the same literal path must still alias",
                DeconvolutionIO.normalizeSourcePath("Input\\CAF\u00c9\\Container.LIF", true),
                DeconvolutionIO.normalizeSourcePath("input/caf\u00e9/container.lif", true));
        assertNotEquals("case-sensitive filesystems must not alias legal distinct paths",
                DeconvolutionIO.normalizeSourcePath(composed, false),
                DeconvolutionIO.normalizeSourcePath(decomposed, false));
        assertEquals("input/CAF\u00c9/container.lif",
                DeconvolutionIO.normalizeSourcePath("input\\CAF\u00c9//container.lif", false));
    }

    @Test
    public void windowsCaseFoldingNeverExpandsDottedCapitalIIntoAnotherLiteralName() {
        String dottedCapitalI = "input/\u0130mage.lif";
        String lowercaseIWithCombiningDot = "input/i\u0307mage.lif";

        assertNotEquals("non-expanding NTFS case handling must preserve distinct names",
                DeconvolutionIO.normalizeSourcePath(dottedCapitalI, true),
                DeconvolutionIO.normalizeSourcePath(lowercaseIWithCombiningDot, true));
        assertNotEquals("simple NTFS uppercase must not collapse dotted I into ASCII i",
                DeconvolutionIO.normalizeSourcePath(dottedCapitalI, true),
                DeconvolutionIO.normalizeSourcePath("input/image.lif", true));
        assertEquals("ordinary Windows ASCII case variants must still alias",
                DeconvolutionIO.normalizeSourcePath("INPUT/SAMPLE.LIF", true),
                DeconvolutionIO.normalizeSourcePath("input/sample.lif", true));
    }

    @Test
    public void resolvedProjectIdentityIsStableAcrossWindowsAndPosixSeparators() {
        String windowsResolvedSpelling = DeconvolutionIO.resolvedProjectSourceIdentity(
                "Input\\Nested\\Caf\u00e9.LIF");
        String posixResolvedSpelling = DeconvolutionIO.resolvedProjectSourceIdentity(
                "Input/Nested/Caf\u00e9.LIF");
        String windowsDecomposedSpelling = DeconvolutionIO.resolvedProjectSourceIdentity(
                "Input\\Nested\\Cafe\u0301.LIF");
        String posixDecomposedSpelling = DeconvolutionIO.resolvedProjectSourceIdentity(
                "Input/Nested/Cafe\u0301.LIF");
        String contentHash = repeat("ef", 32);

        assertEquals("project:Input/Nested/Caf\u00e9.LIF", windowsResolvedSpelling);
        assertEquals("the persisted project-relative key must be platform-neutral",
                windowsResolvedSpelling, posixResolvedSpelling);
        assertEquals("decomposed spelling must also survive cross-platform relocation",
                windowsDecomposedSpelling, posixDecomposedSpelling);
        assertNotEquals("separator normalization must never normalize Unicode composition",
                windowsResolvedSpelling, windowsDecomposedSpelling);
        DeconvolutionIO.ArtifactIdentity windows =
                new DeconvolutionIO.ArtifactIdentity(
                        DeconvolutionIO.ArtifactIdentity.VERSION,
                        1024L, contentHash, windowsResolvedSpelling, 1, "Region");
        DeconvolutionIO.ArtifactIdentity posix =
                new DeconvolutionIO.ArtifactIdentity(
                        DeconvolutionIO.ArtifactIdentity.VERSION,
                        1024L, contentHash, posixResolvedSpelling, 1, "Region");
        DeconvolutionIO.ArtifactIdentity windowsDecomposed =
                new DeconvolutionIO.ArtifactIdentity(
                        DeconvolutionIO.ArtifactIdentity.VERSION,
                        1024L, contentHash, windowsDecomposedSpelling, 1, "Region");
        DeconvolutionIO.ArtifactIdentity posixDecomposed =
                new DeconvolutionIO.ArtifactIdentity(
                        DeconvolutionIO.ArtifactIdentity.VERSION,
                        1024L, contentHash, posixDecomposedSpelling, 1, "Region");
        assertEquals("Windows-to-POSIX relocation must preserve the artifact key",
                windows, posix);
        assertEquals(windows.artifactKey, posix.artifactKey);
        assertEquals(windowsDecomposed, posixDecomposed);
        assertEquals(windowsDecomposed.artifactKey, posixDecomposed.artifactKey);
        assertNotEquals(windows, windowsDecomposed);
    }

    @Test
    public void posixAsciiCaseVariantsKeepDistinctV3Identities() {
        String contentHash = repeat("f0", 32);
        String uppercasePath = DeconvolutionIO.resolvedProjectSourceIdentity(
                "input/Sample.lif");
        String lowercasePath = DeconvolutionIO.resolvedProjectSourceIdentity(
                "input/sample.lif");

        DeconvolutionIO.ArtifactIdentity uppercase =
                new DeconvolutionIO.ArtifactIdentity(
                        DeconvolutionIO.ArtifactIdentity.VERSION,
                        2048L, contentHash, uppercasePath, 2, "Region");
        DeconvolutionIO.ArtifactIdentity lowercase =
                new DeconvolutionIO.ArtifactIdentity(
                        DeconvolutionIO.ArtifactIdentity.VERSION,
                        2048L, contentHash, lowercasePath, 2, "Region");

        assertNotEquals("POSIX files differing only by case must never share artifacts",
                uppercase, lowercase);
        assertNotEquals(uppercase.artifactKey, lowercase.artifactKey);
    }

    @Test
    public void posixUnicodeCompositionVariantsKeepDistinctV3IdentitiesForSameBytesAndSeries() {
        String contentHash = repeat("ab", 32);
        String composedPath = "project:input/Caf\u00e9/container.lif";
        String decomposedPath = "project:input/Cafe\u0301/container.lif";

        assertNotEquals("POSIX permits both literal names in the same directory",
                DeconvolutionIO.normalizeSourcePath(composedPath, false),
                DeconvolutionIO.normalizeSourcePath(decomposedPath, false));

        DeconvolutionIO.ArtifactIdentity composed =
                new DeconvolutionIO.ArtifactIdentity(
                        DeconvolutionIO.ArtifactIdentity.VERSION,
                        4096L, contentHash, composedPath, 3, "Region");
        DeconvolutionIO.ArtifactIdentity decomposed =
                new DeconvolutionIO.ArtifactIdentity(
                        DeconvolutionIO.ArtifactIdentity.VERSION,
                        4096L, contentHash, decomposedPath, 3, "Region");

        assertEquals("the source bytes are deliberately identical",
                composed.verifiedSourceContentHash, decomposed.verifiedSourceContentHash);
        assertEquals("the source-local series is deliberately identical",
                composed.sourceSeriesIndex, decomposed.sourceSeriesIndex);
        assertNotEquals(composed.sourceIdentityHash, decomposed.sourceIdentityHash);
        assertNotEquals(composed.identityHash, decomposed.identityHash);
        assertNotEquals(composed.artifactKey, decomposed.artifactKey);
    }

    @Test
    public void windowsCaseVariantsKeepOneV3IdentityWithoutFoldingComposition() {
        String contentHash = repeat("cd", 32);
        String uppercasePath = "project:" + DeconvolutionIO.normalizeSourcePath(
                "Input\\CAF\u00c9\\Container.LIF", true);
        String lowercasePath = "project:" + DeconvolutionIO.normalizeSourcePath(
                "input/caf\u00e9/container.lif", true);

        assertEquals("Windows path identity folds case for the same code-point composition",
                uppercasePath, lowercasePath);

        DeconvolutionIO.ArtifactIdentity uppercase =
                new DeconvolutionIO.ArtifactIdentity(
                        DeconvolutionIO.ArtifactIdentity.VERSION,
                        8192L, contentHash, uppercasePath, 5, "Region");
        DeconvolutionIO.ArtifactIdentity lowercase =
                new DeconvolutionIO.ArtifactIdentity(
                        DeconvolutionIO.ArtifactIdentity.VERSION,
                        8192L, contentHash, lowercasePath, 5, "Region");

        assertEquals(uppercase.sourceIdentityHash, lowercase.sourceIdentityHash);
        assertEquals(uppercase.identityHash, lowercase.identityHash);
        assertEquals(uppercase.artifactKey, lowercase.artifactKey);
        assertEquals(uppercase, lowercase);
    }

    @Test
    public void windowsLiteralCompositionVariantsRemainDistinctAcrossProjectRelocation()
            throws Exception {
        assumeTrue("literal NTFS filename regression is Windows-specific",
                File.separatorChar == '\\');
        File firstProject = temp.newFolder("unicode-relocation-before");
        File secondProject = temp.newFolder("unicode-relocation-after");
        File firstDirectory = new File(firstProject, "input");
        File secondDirectory = new File(secondProject, "input");
        Files.createDirectories(firstDirectory.toPath());
        Files.createDirectories(secondDirectory.toPath());

        File firstComposed = new File(firstDirectory, "Caf\u00e9.lif");
        File firstDecomposed = new File(firstDirectory, "Cafe\u0301.lif");
        File secondComposed = new File(secondDirectory, "Caf\u00e9.lif");
        File secondDecomposed = new File(secondDirectory, "Cafe\u0301.lif");
        byte[] content = "same-unicode-container".getBytes(StandardCharsets.UTF_8);
        Files.write(firstComposed.toPath(), content);
        Files.write(firstDecomposed.toPath(), content);
        Files.write(secondComposed.toPath(), content);
        Files.write(secondDecomposed.toPath(), content);

        DeconvolutionIO.ArtifactIdentity composedBefore =
                DeconvolutionIO.ArtifactIdentity.of(firstProject, firstComposed,
                        DeconvManifest.SourceFingerprint.of(firstComposed), 4, "Region");
        DeconvolutionIO.ArtifactIdentity decomposedBefore =
                DeconvolutionIO.ArtifactIdentity.of(firstProject, firstDecomposed,
                        DeconvManifest.SourceFingerprint.of(firstDecomposed), 4, "Region");
        DeconvolutionIO.ArtifactIdentity composedAfter =
                DeconvolutionIO.ArtifactIdentity.of(secondProject, secondComposed,
                        DeconvManifest.SourceFingerprint.of(secondComposed), 4, "Region");
        DeconvolutionIO.ArtifactIdentity decomposedAfter =
                DeconvolutionIO.ArtifactIdentity.of(secondProject, secondDecomposed,
                        DeconvManifest.SourceFingerprint.of(secondDecomposed), 4, "Region");

        assertNotEquals("same-directory NTFS names must not share deconvolution artifacts",
                composedBefore, decomposedBefore);
        assertNotEquals(composedBefore.artifactKey, decomposedBefore.artifactKey);
        assertEquals("relocation preserves the composed project-relative identity",
                composedBefore, composedAfter);
        assertEquals("relocation preserves the decomposed project-relative identity",
                decomposedBefore, decomposedAfter);
    }

    @Test
    public void windowsLiteralExpandingFoldVariantsRemainDistinctAndAsciiAliasesConverge()
            throws Exception {
        assumeTrue("literal NTFS filename regression is Windows-specific",
                File.separatorChar == '\\');
        File project = temp.newFolder("windows-case-resolution");
        File input = new File(project, "Input");
        Files.createDirectories(input.toPath());
        byte[] content = "same-windows-container".getBytes(StandardCharsets.UTF_8);
        File dottedCapitalI = new File(input, "\u0130mage.lif");
        File lowercaseIWithCombiningDot = new File(input, "i\u0307mage.lif");
        File asciiSpelling = new File(input, "Sample.LIF");
        Files.write(dottedCapitalI.toPath(), content);
        Files.write(lowercaseIWithCombiningDot.toPath(), content);
        Files.write(asciiSpelling.toPath(), content);

        DeconvManifest.SourceFingerprint fingerprint =
                DeconvManifest.SourceFingerprint.of(dottedCapitalI);
        DeconvolutionIO.ArtifactIdentity dottedCapitalIdentity =
                DeconvolutionIO.ArtifactIdentity.of(project, dottedCapitalI,
                        fingerprint, 6, "Region");
        DeconvolutionIO.ArtifactIdentity combiningDotIdentity =
                DeconvolutionIO.ArtifactIdentity.of(project, lowercaseIWithCombiningDot,
                        fingerprint, 6, "Region");
        DeconvolutionIO.ArtifactIdentity asciiOriginal =
                DeconvolutionIO.ArtifactIdentity.of(project, asciiSpelling,
                        fingerprint, 6, "Region");
        File asciiCaseAlias = new File(project, "input/sample.lif");
        DeconvolutionIO.ArtifactIdentity asciiAlias =
                DeconvolutionIO.ArtifactIdentity.of(project, asciiCaseAlias,
                        fingerprint, 6, "Region");

        assertNotEquals("U+0130 and i-plus-combining-dot are distinct NTFS files",
                dottedCapitalIdentity, combiningDotIdentity);
        assertNotEquals(dottedCapitalIdentity.artifactKey, combiningDotIdentity.artifactKey);
        assertEquals("resolved on-disk spelling must converge ordinary Windows case aliases",
                asciiOriginal, asciiAlias);
        assertEquals(asciiOriginal.artifactKey, asciiAlias.artifactKey);
    }

    @Test
    public void priorWindowsV3KeysAreDeterministicForProjectAndStandaloneAsciiPaths() {
        String contentHash = repeat("a1", 32);
        DeconvolutionIO.ArtifactIdentity projectCurrent =
                new DeconvolutionIO.ArtifactIdentity(
                        DeconvolutionIO.ArtifactIdentity.VERSION, 500L, contentHash,
                        "project:Input/Nested/Container.LIF", 2, "Region");
        DeconvolutionIO.ArtifactIdentity projectExpectedPrior =
                new DeconvolutionIO.ArtifactIdentity(
                        DeconvolutionIO.ArtifactIdentity.VERSION, 500L, contentHash,
                        "project:input/nested/container.lif", 2, "Region");
        DeconvolutionIO.ArtifactIdentity standaloneCurrent =
                new DeconvolutionIO.ArtifactIdentity(
                        DeconvolutionIO.ArtifactIdentity.VERSION, 500L, contentHash,
                        "absolute:C:/Data/Experiment/Container.LIF", 2, "Region");
        DeconvolutionIO.ArtifactIdentity standaloneExpectedPrior =
                new DeconvolutionIO.ArtifactIdentity(
                        DeconvolutionIO.ArtifactIdentity.VERSION, 500L, contentHash,
                        "absolute:c:/data/experiment/container.lif", 2, "Region");

        assertEquals(projectExpectedPrior.artifactKey,
                projectCurrent.priorWindowsV3Identity().artifactKey);
        assertEquals(standaloneExpectedPrior.artifactKey,
                standaloneCurrent.priorWindowsV3Identity().artifactKey);
        assertNotEquals(projectCurrent.artifactKey, projectExpectedPrior.artifactKey);
        assertNotEquals(standaloneCurrent.artifactKey, standaloneExpectedPrior.artifactKey);
    }

    @Test
    public void oldOnlyProjectV3FamilyMigratesToExactKeyAndRemainsConsumable()
            throws Exception {
        assumeTrue("prior Windows v3 migration is Windows-specific", File.separatorChar == '\\');
        File root = temp.newFolder("prior-v3-project-migration");
        String contentHash = repeat("b2", 32);
        DeconvolutionIO.ArtifactIdentity current = new DeconvolutionIO.ArtifactIdentity(
                DeconvolutionIO.ArtifactIdentity.VERSION, 700L, contentHash,
                "project:Input/Container.LIF", 3, "Region");
        final DeconvolutionIO.ArtifactIdentity prior = current.priorWindowsV3Identity();
        writePriorChannelFamily(root, prior, 1, "PARAMS", "prior-project");

        List<File> candidates = DeconvolutionIO.deconvFileReadCandidates(root, current,
                1, "Region", DeconvolutionIO.LegacyBasenamePolicy.REJECT, 0);
        File currentChannel = DeconvolutionIO.deconvFile(root, current, 1);

        assertEquals(currentChannel, candidates.get(0));
        assertTrue(currentChannel.isFile());
        assertEquals("prior-project", readUtf8(currentChannel));
        assertFalse(DeconvolutionIO.deconvFile(root, prior, 1).exists());
        assertFalse(DeconvolutionIO.manifestFile(root, prior).exists());
        assertTrue(DeconvManifest.load(DeconvolutionIO.manifestFile(root, current))
                .isChannelFresh(1, "PARAMS",
                        new DeconvManifest.SourceFingerprint(700L, 0L, contentHash), current));
        assertEquals("basename discovery must see one exact family after migration",
                currentChannel, DeconvolutionIO.deconvFile(root, "Region", 1));
        assertFalse("optional caches are regenerated under the current identity",
                DeconvolutionIO.cacheFile(root, "PARAMS", current, 1).exists());
        assertTrue("the identity-keyed prior cache remains isolated and harmless",
                DeconvolutionIO.cacheFile(root, "PARAMS", prior, 1).isFile());
    }

    @Test
    public void oldOnlyStandaloneWindowsV3FamilyMigratesToExactKey() throws Exception {
        assumeTrue("prior Windows v3 migration is Windows-specific", File.separatorChar == '\\');
        File root = temp.newFolder("prior-v3-standalone-migration");
        String contentHash = repeat("c3", 32);
        DeconvolutionIO.ArtifactIdentity current = new DeconvolutionIO.ArtifactIdentity(
                DeconvolutionIO.ArtifactIdentity.VERSION, 800L, contentHash,
                "absolute:C:/Data/Container.LIF", 0, "Image");
        DeconvolutionIO.ArtifactIdentity prior = current.priorWindowsV3Identity();
        writePriorChannelFamily(root, prior, 0, "STANDALONE", "prior-standalone");

        File selected = DeconvolutionIO.firstExistingFile(
                DeconvolutionIO.deconvFileReadCandidates(root, current, 0, "Image",
                        DeconvolutionIO.LegacyBasenamePolicy.REJECT, 0));

        assertEquals(DeconvolutionIO.deconvFile(root, current, 0), selected);
        assertEquals("prior-standalone", readUtf8(selected));
        assertFalse(DeconvolutionIO.manifestFile(root, prior).exists());
        assertTrue(DeconvManifest.load(DeconvolutionIO.manifestFile(root, current))
                .matchesArtifact(current));
    }

    @Test
    public void exactNewGenerationWinsAndRetiresCoexistingPriorV3Family() throws Exception {
        assumeTrue("prior Windows v3 migration is Windows-specific", File.separatorChar == '\\');
        File root = temp.newFolder("coexisting-v3-generations");
        String contentHash = repeat("d4", 32);
        DeconvolutionIO.ArtifactIdentity current = new DeconvolutionIO.ArtifactIdentity(
                DeconvolutionIO.ArtifactIdentity.VERSION, 900L, contentHash,
                "project:Input/Container.LIF", 4, "Region");
        DeconvolutionIO.ArtifactIdentity prior = current.priorWindowsV3Identity();
        writePriorChannelFamily(root, prior, 2, "OLD", "old-bytes");
        writePriorChannelFamily(root, current, 2, "NEW", "new-bytes");

        List<File> selected = DeconvolutionIO.deconvFileReadCandidates(root, current,
                2, "Region", DeconvolutionIO.LegacyBasenamePolicy.REJECT, 0);
        File currentChannel = DeconvolutionIO.deconvFile(root, current, 2);

        assertEquals(currentChannel, selected.get(0));
        assertEquals("new-bytes", readUtf8(currentChannel));
        assertFalse(DeconvolutionIO.deconvFile(root, prior, 2).exists());
        assertFalse(DeconvolutionIO.manifestFile(root, prior).exists());
        assertEquals(currentChannel, DeconvolutionIO.deconvFile(root, "Region", 2));
        DeconvManifest retained = DeconvManifest.load(DeconvolutionIO.manifestFile(root, current));
        assertEquals("NEW", retained.channel(2).paramsHash);
    }

    @Test
    public void publishFaultRollsBackCurrentOrphansAndLeavesPriorRetryable() throws Exception {
        assumeTrue("prior Windows v3 migration is Windows-specific", File.separatorChar == '\\');
        File root = temp.newFolder("v3-publish-rollback");
        String contentHash = repeat("f6", 32);
        DeconvolutionIO.ArtifactIdentity current = new DeconvolutionIO.ArtifactIdentity(
                DeconvolutionIO.ArtifactIdentity.VERSION, 1100L, contentHash,
                "project:Input/Container.LIF", 1, "Region");
        DeconvolutionIO.ArtifactIdentity prior = current.priorWindowsV3Identity();
        writePriorChannelFamily(root, prior, 0, "OLD", "prior-channel");
        writeUtf8(DeconvolutionIO.detailsFile(root, prior), "prior-details");
        writeUtf8(DeconvolutionIO.deconvFile(root, current, 0), "current-orphan");
        writeUtf8(DeconvolutionIO.detailsFile(root, current), "current-details-orphan");

        DeconvolutionIO.MigrationResult failed =
                DeconvolutionIO.migratePriorWindowsV3FamilyForTest(root, current,
                        failMigrationAt(DeconvolutionIO.MigrationFaultPoint.AFTER_FIRST_CURRENT_PUBLISH));

        assertFalse(failed.migrated);
        assertTrue("rollback must leave at least one complete readable generation", failed.safe);
        assertTrue(failed.failure != null);
        assertEquals("current-orphan", readUtf8(DeconvolutionIO.deconvFile(root, current, 0)));
        assertEquals("current-details-orphan", readUtf8(DeconvolutionIO.detailsFile(root, current)));
        assertFalse(DeconvolutionIO.manifestFile(root, current).exists());
        assertEquals("prior-channel", readUtf8(DeconvolutionIO.deconvFile(root, prior, 0)));
        assertTrue(DeconvolutionIO.manifestFile(root, prior).isFile());

        DeconvolutionIO.MigrationResult retried =
                DeconvolutionIO.migratePriorWindowsV3FamilyForTest(root, current, null);
        assertTrue(retried.migrated);
        assertEquals("prior-channel", readUtf8(DeconvolutionIO.deconvFile(root, current, 0)));
        assertEquals("prior-details", readUtf8(DeconvolutionIO.detailsFile(root, current)));
        assertFalse(DeconvolutionIO.manifestFile(root, prior).exists());
    }

    @Test
    public void manifestPublishFaultNeverExposesPriorPixelsUnderNewManifest() throws Exception {
        assumeTrue("prior Windows v3 migration is Windows-specific", File.separatorChar == '\\');
        File root = temp.newFolder("v3-manifest-publish-rollback");
        String contentHash = repeat("07", 32);
        final DeconvolutionIO.ArtifactIdentity current = new DeconvolutionIO.ArtifactIdentity(
                DeconvolutionIO.ArtifactIdentity.VERSION, 1200L, contentHash,
                "project:Input/Container.LIF", 2, "Region");
        DeconvolutionIO.ArtifactIdentity prior = current.priorWindowsV3Identity();
        writePriorChannelFamily(root, prior, 0, "OLD0", "prior-zero");
        appendPriorChannel(root, prior, 1, "OLD1", "prior-one");

        final File currentZero = DeconvolutionIO.deconvFile(root, current, 0);
        final File currentOne = DeconvolutionIO.deconvFile(root, current, 1);
        DeconvolutionIO.MigrationResult failed =
                DeconvolutionIO.migratePriorWindowsV3FamilyForTest(root, current,
                        new DeconvolutionIO.MigrationFaultInjector() {
                            @Override
                            public void checkpoint(DeconvolutionIO.MigrationFaultPoint point)
                                    throws java.io.IOException {
                                if (point != DeconvolutionIO.MigrationFaultPoint.AFTER_MANIFEST_PUBLISH) return;
                                assertTrue(DeconvManifest.load(
                                        DeconvolutionIO.manifestFile(root, current)).matchesArtifact(current));
                                try {
                                    assertEquals("prior-zero", readUtf8(currentZero));
                                    assertEquals("prior-one", readUtf8(currentOne));
                                } catch (Exception e) {
                                    throw new java.io.IOException(e);
                                }
                                throw new java.io.IOException("deterministic manifest fault");
                            }
                        });

        assertFalse(failed.migrated);
        assertTrue(failed.safe);
        assertFalse("rolled-back manifest must not vouch for rolled-back files",
                DeconvolutionIO.manifestFile(root, current).exists());
        assertFalse(currentZero.exists());
        assertFalse(currentOne.exists());
        assertTrue(DeconvolutionIO.manifestFile(root, prior).isFile());
    }

    @Test
    public void retirementFaultRestoresEveryPriorFileAndCanRetry() throws Exception {
        assumeTrue("prior Windows v3 migration is Windows-specific", File.separatorChar == '\\');
        File root = temp.newFolder("v3-retirement-rollback");
        String contentHash = repeat("18", 32);
        DeconvolutionIO.ArtifactIdentity current = new DeconvolutionIO.ArtifactIdentity(
                DeconvolutionIO.ArtifactIdentity.VERSION, 1300L, contentHash,
                "project:Input/Container.LIF", 3, "Region");
        DeconvolutionIO.ArtifactIdentity prior = current.priorWindowsV3Identity();
        writePriorChannelFamily(root, prior, 0, "PRIMARY", "prior-primary");
        File secondPriorCache = writeCacheVariant(root, "SECONDARY", prior,
                "nested/alternate.bin", "prior-secondary");

        DeconvolutionIO.MigrationResult failed =
                DeconvolutionIO.migratePriorWindowsV3FamilyForTest(root, current,
                        failMigrationAt(DeconvolutionIO.MigrationFaultPoint.AFTER_FIRST_PRIOR_RETIRE));

        assertFalse(failed.migrated);
        assertTrue("the fully published current generation is safe", failed.safe);
        assertTrue(DeconvolutionIO.manifestFile(root, current).isFile());
        assertTrue("prior manifest must be restored for a deterministic retry",
                DeconvolutionIO.manifestFile(root, prior).isFile());
        assertTrue(DeconvolutionIO.deconvFile(root, prior, 0).isFile());
        assertTrue(secondPriorCache.isFile());

        DeconvolutionIO.MigrationResult retried =
                DeconvolutionIO.migratePriorWindowsV3FamilyForTest(root, current, null);
        assertTrue(retried.migrated);
        assertFalse(DeconvolutionIO.manifestFile(root, prior).exists());
        assertTrue("optional prior caches are not part of atomic family retirement",
                secondPriorCache.exists());
    }

    @Test
    public void migrationLeavesOptionalCacheVariantsUnderTheirPriorIdentity() throws Exception {
        assumeTrue("prior Windows v3 migration is Windows-specific", File.separatorChar == '\\');
        File root = temp.newFolder("v3-all-cache-variants");
        String contentHash = repeat("29", 32);
        DeconvolutionIO.ArtifactIdentity current = new DeconvolutionIO.ArtifactIdentity(
                DeconvolutionIO.ArtifactIdentity.VERSION, 1400L, contentHash,
                "project:Input/Container.LIF", 4, "Region");
        DeconvolutionIO.ArtifactIdentity prior = current.priorWindowsV3Identity();
        writePriorChannelFamily(root, prior, 0, "PRIMARY", "primary-cache");
        File alternate = writeCacheVariant(root, "ALTERNATE", prior,
                "subdir/variant.dat", "alternate-cache");
        File third = writeCacheVariant(root, "THIRD", prior,
                "another.tif", "third-cache");

        DeconvolutionIO.MigrationResult result =
                DeconvolutionIO.migratePriorWindowsV3FamilyForTest(root, current, null);

        assertTrue(result.migrated);
        assertFalse(DeconvolutionIO.cacheFile(root, "PRIMARY", current, 0).exists());
        assertEquals("primary-cache", readUtf8(
                DeconvolutionIO.cacheFile(root, "PRIMARY", prior, 0)));
        assertEquals("alternate-cache", readUtf8(alternate));
        assertEquals("third-cache", readUtf8(third));
        assertTrue(new File(DeconvolutionIO.cacheParamsDir(root, "ALTERNATE"),
                prior.artifactKey).isDirectory());
    }

    @Test
    public void optionalCacheInventoryBeyondJournalRecordLimitCannotBlockMigration()
            throws Exception {
        assumeTrue("prior Windows v3 migration is Windows-specific", File.separatorChar == '\\');
        File root = temp.newFolder("v3-large-optional-cache-inventory");
        DeconvolutionIO.ArtifactIdentity current = identityForRecovery("2a", 1450L, 45);
        DeconvolutionIO.ArtifactIdentity prior = current.priorWindowsV3Identity();
        writePriorChannelFamily(root, prior, 0, "OLD", "prior-large-inventory");
        File priorCacheDir = new File(DeconvolutionIO.cacheParamsDir(root, "BULK"),
                prior.artifactKey);
        Files.createDirectories(priorCacheDir.toPath());
        int optionalFiles = DeconvolutionIO.MAX_RECOVERY_JOURNAL_RECORDS + 32;
        for (int i = 0; i < optionalFiles; i++) {
            Files.createFile(new File(priorCacheDir, "cache-" + i + ".bin").toPath());
        }

        DeconvolutionIO.MigrationResult result =
                DeconvolutionIO.migratePriorWindowsV3FamilyForTest(root, current, null);

        assertTrue(result.failure == null);
        assertTrue(result.migrated);
        assertEquals("prior-large-inventory",
                readUtf8(DeconvolutionIO.deconvFile(root, current, 0)));
        assertTrue(new File(priorCacheDir, "cache-0.bin").isFile());
        assertTrue(new File(priorCacheDir,
                "cache-" + (optionalFiles - 1) + ".bin").isFile());
        assertFalse(new File(DeconvolutionIO.cacheParamsDir(root, "BULK"),
                current.artifactKey).exists());
        assertFalse(DeconvolutionIO.cacheFileReadCandidates(root, "OLD", current, 0,
                "Region", DeconvolutionIO.LegacyBasenamePolicy.REJECT, 0)
                .contains(DeconvolutionIO.cacheFile(root, "OLD", prior, 0)));
        assertEquals(0, countFamilyTransactions(root, current));
        assertEquals(0, countFamilyQuarantines(root, current));
    }

    @Test
    public void currentOrphansNeverOutrankValidatedPriorFamily() throws Exception {
        assumeTrue("prior Windows v3 migration is Windows-specific", File.separatorChar == '\\');
        File root = temp.newFolder("v3-orphan-precedence");
        String contentHash = repeat("3a", 32);
        DeconvolutionIO.ArtifactIdentity current = new DeconvolutionIO.ArtifactIdentity(
                DeconvolutionIO.ArtifactIdentity.VERSION, 1500L, contentHash,
                "project:Input/Container.LIF", 5, "Region");
        DeconvolutionIO.ArtifactIdentity prior = current.priorWindowsV3Identity();
        writePriorChannelFamily(root, prior, 0, "OLD", "prior-channel");
        addPriorMergedRecord(root, prior, 0, "OLD", "prior-merged");
        writeUtf8(DeconvolutionIO.detailsFile(root, prior), "prior-details");
        writeUtf8(DeconvolutionIO.deconvFile(root, current, 0), "orphan-channel");
        writeUtf8(DeconvolutionIO.mergedDeconvFile(root, current), "orphan-merged");
        writeUtf8(DeconvolutionIO.detailsFile(root, current), "orphan-details");
        writeUtf8(DeconvolutionIO.cacheFile(root, "OLD", current, 0), "orphan-cache");

        DeconvolutionIO.MigrationResult result =
                DeconvolutionIO.migratePriorWindowsV3FamilyForTest(root, current, null);

        assertTrue(result.migrated);
        assertEquals("prior-channel", readUtf8(DeconvolutionIO.deconvFile(root, current, 0)));
        assertEquals("prior-merged", readUtf8(DeconvolutionIO.mergedDeconvFile(root, current)));
        assertEquals("prior-details", readUtf8(DeconvolutionIO.detailsFile(root, current)));
        assertEquals("orphan-cache", readUtf8(
                DeconvolutionIO.cacheFile(root, "OLD", current, 0)));
        DeconvManifest migrated = DeconvManifest.load(DeconvolutionIO.manifestFile(root, current));
        assertTrue(migrated.matchesArtifact(current));
        assertEquals("OLD", migrated.channel(0).paramsHash);
        assertTrue(migrated.merged() != null);
    }

    @Test
    public void ambiguousManifestCommitRestoresExactPriorGeneration() throws Exception {
        assumeTrue("prior Windows v3 migration is Windows-specific", File.separatorChar == '\\');
        File root = temp.newFolder("v3-ambiguous-manifest-commit");
        String contentHash = repeat("5c", 32);
        DeconvolutionIO.ArtifactIdentity current = new DeconvolutionIO.ArtifactIdentity(
                DeconvolutionIO.ArtifactIdentity.VERSION, 1700L, contentHash,
                "project:Input/Container.LIF", 7, "Region");
        DeconvolutionIO.ArtifactIdentity prior = current.priorWindowsV3Identity();
        writePriorChannelFamily(root, prior, 0, "OLD", "prior-channel");

        DeconvolutionIO.MigrationResult failed =
                DeconvolutionIO.migratePriorWindowsV3FamilyForTest(root, current,
                        failMigrationAt(DeconvolutionIO.MigrationFaultPoint
                                .AFTER_MANIFEST_COMMIT_BEFORE_ACK));

        assertFalse(failed.migrated);
        assertTrue(failed.safe);
        assertTrue(failed.failure != null);
        assertFalse(DeconvolutionIO.manifestFile(root, current).exists());
        assertFalse(DeconvolutionIO.deconvFile(root, current, 0).exists());
        assertEquals("prior-channel", readUtf8(DeconvolutionIO.deconvFile(root, prior, 0)));
        assertTrue(DeconvolutionIO.manifestFile(root, prior).isFile());
    }

    @Test
    public void abandonedUncheckedTransactionIsRecoveredOnNextLockedAccess() throws Exception {
        assumeTrue("prior Windows v3 migration is Windows-specific", File.separatorChar == '\\');
        File root = temp.newFolder("v3-abandoned-transaction");
        String contentHash = repeat("6d", 32);
        DeconvolutionIO.ArtifactIdentity current = new DeconvolutionIO.ArtifactIdentity(
                DeconvolutionIO.ArtifactIdentity.VERSION, 1800L, contentHash,
                "project:Input/Container.LIF", 8, "Region");
        DeconvolutionIO.ArtifactIdentity prior = current.priorWindowsV3Identity();
        writePriorChannelFamily(root, prior, 0, "OLD", "prior-channel");
        try {
            DeconvolutionIO.migratePriorWindowsV3FamilyForTest(root, current,
                    new DeconvolutionIO.MigrationFaultInjector() {
                        @Override
                        public void checkpoint(DeconvolutionIO.MigrationFaultPoint point) {
                            if (point == DeconvolutionIO.MigrationFaultPoint.AFTER_FIRST_CURRENT_PUBLISH) {
                                throw new IllegalStateException("simulated process interruption");
                            }
                        }
                    });
            fail("expected unchecked interruption");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("simulated"));
        }
        assertTrue(countFamilyTransactions(root, current) > 0);

        DeconvolutionIO.MigrationResult recovered =
                DeconvolutionIO.migratePriorWindowsV3FamilyForTest(root, current, null);
        assertFalse("scavenging completed the journaled migration", recovered.migrated);
        assertEquals("prior-channel", readUtf8(DeconvolutionIO.deconvFile(root, current, 0)));
        assertEquals(0, countFamilyTransactions(root, current));
    }

    @Test
    public void cleanupFailureIsSurfacedAndRetriedOnNextAccess() throws Exception {
        assumeTrue("prior Windows v3 migration is Windows-specific", File.separatorChar == '\\');
        File root = temp.newFolder("v3-cleanup-retry");
        String contentHash = repeat("7e", 32);
        DeconvolutionIO.ArtifactIdentity current = new DeconvolutionIO.ArtifactIdentity(
                DeconvolutionIO.ArtifactIdentity.VERSION, 1900L, contentHash,
                "project:Input/Container.LIF", 9, "Region");
        writePriorChannelFamily(root, current.priorWindowsV3Identity(), 0,
                "OLD", "prior-channel");

        DeconvolutionIO.MigrationResult failed =
                DeconvolutionIO.migratePriorWindowsV3FamilyForTest(root, current,
                        failMigrationAt(DeconvolutionIO.MigrationFaultPoint.BEFORE_TRANSACTION_CLEANUP));
        assertFalse(failed.migrated);
        assertTrue(failed.safe);
        assertTrue(failed.failure != null);
        assertTrue(countFamilyTransactions(root, current) > 0);

        DeconvolutionIO.MigrationResult retried =
                DeconvolutionIO.migratePriorWindowsV3FamilyForTest(root, current, null);
        assertFalse("scavenging completed the already-published migration", retried.migrated);
        assertEquals(0, countFamilyTransactions(root, current));
        assertEquals("prior-channel", readUtf8(DeconvolutionIO.deconvFile(root, current, 0)));
    }

    @Test
    public void keylessValidRecoveredGenerationRemainsReadableAndIsNeverQuarantined()
            throws Exception {
        assumeTrue("prior Windows v3 migration is Windows-specific", File.separatorChar == '\\');
        File root = temp.newFolder("v3-keyless-valid-cleanup");
        DeconvolutionIO.ArtifactIdentity current = identityForRecovery("7f", 1901L, 10);
        writePriorChannelFamily(root, current.priorWindowsV3Identity(), 0,
                "OLD", "current-readable");
        DeconvolutionIO.MigrationResult interrupted =
                DeconvolutionIO.migratePriorWindowsV3FamilyForTest(root, current,
                        failMigrationAt(DeconvolutionIO.MigrationFaultPoint
                                .BEFORE_JOURNAL_CLEANUP));
        File transaction = firstFamilyTransaction(root, current);
        assertFalse(interrupted.migrated);
        assertTrue(transaction != null && new File(transaction, "recovery.journal").isFile());
        assertEquals("current-readable", readUtf8(DeconvolutionIO.deconvFile(root, current, 0)));
        DeconvolutionIO.setStableFileIdentityOverrideForTest(Boolean.FALSE);

        for (int access = 0; access < 3; access++) {
            try (DeconvolutionFamilyLock.Handle ignored =
                         DeconvolutionIO.lockFamilyForAccess(root, current)) {
                assertEquals("current-readable",
                        readUtf8(DeconvolutionIO.deconvFile(root, current, 0)));
            }
            assertEquals(1, countFamilyTransactions(root, current));
            assertEquals(transaction.getCanonicalFile(),
                    firstFamilyTransaction(root, current).getCanonicalFile());
            assertEquals(0, countFamilyQuarantines(root, current));
        }

        DeconvolutionIO.MigrationResult retry =
                DeconvolutionIO.migratePriorWindowsV3FamilyForTest(root, current, null);
        assertFalse(retry.migrated);
        assertEquals(1, countFamilyTransactions(root, current));
        assertEquals(0, countFamilyQuarantines(root, current));
    }

    @Test
    public void truncatedJournalQuarantinesAndLeavesSolePriorReadableAcrossRetries() throws Exception {
        assumeTrue("prior Windows v3 migration is Windows-specific", File.separatorChar == '\\');
        File root = temp.newFolder("v3-truncated-journal");
        DeconvolutionIO.ArtifactIdentity current = identityForRecovery("91", 2100L, 11);
        DeconvolutionIO.ArtifactIdentity prior = current.priorWindowsV3Identity();
        writePriorChannelFamily(root, prior, 0, "OLD", "prior-channel");
        File transaction = manualRecoveryTransaction(root, current, "truncated");
        writeUtf8(new File(transaction, "recovery.journal"),
                "deconv-migration-v3\nmanifest|truncated");

        try (DeconvolutionFamilyLock.Handle ignored =
                     DeconvolutionIO.lockFamilyForAccess(root, current)) {
            assertEquals("prior-channel", readUtf8(DeconvolutionIO.deconvFile(root, prior, 0)));
        }
        assertEquals(0, countFamilyTransactions(root, current));
        assertEquals(1, countFamilyQuarantines(root, current));

        // Quarantine is durable but does not permanently block the one proven prior generation.
        try (DeconvolutionFamilyLock.Handle ignored =
                     DeconvolutionIO.lockFamilyForAccess(root, current)) {
            assertEquals("prior-channel", readUtf8(DeconvolutionIO.deconvFile(root, prior, 0)));
        }
        assertFalse(DeconvolutionIO.manifestFile(root, current).exists());
    }

    @Test
    public void invalidRecordKeyQuarantinesAndLeavesSoleCurrentReadable() throws Exception {
        assumeTrue("prior Windows v3 migration is Windows-specific", File.separatorChar == '\\');
        File root = temp.newFolder("v3-invalid-journal-key");
        DeconvolutionIO.ArtifactIdentity current = identityForRecovery("92", 2200L, 12);
        writePriorChannelFamily(root, current, 0, "NEW", "current-channel");
        File transaction = manualRecoveryTransaction(root, current, "invalid-key");
        writeChecksummedTestJournal(transaction, "not-a-record|value\n");

        try (DeconvolutionFamilyLock.Handle ignored =
                     DeconvolutionIO.lockFamilyForAccess(root, current)) {
            assertEquals("current-channel", readUtf8(
                    DeconvolutionIO.deconvFile(root, current, 0)));
        }

        assertEquals(1, countFamilyQuarantines(root, current));
        assertTrue(DeconvManifest.load(DeconvolutionIO.manifestFile(root, current))
                .matchesArtifact(current));
    }

    @Test
    public void unknownJournalVersionWithTwoGenerationsFailsOnlyItsFamily() throws Exception {
        assumeTrue("prior Windows v3 migration is Windows-specific", File.separatorChar == '\\');
        File root = temp.newFolder("v3-unknown-journal-version");
        DeconvolutionIO.ArtifactIdentity current = identityForRecovery("93", 2300L, 13);
        DeconvolutionIO.ArtifactIdentity prior = current.priorWindowsV3Identity();
        writePriorChannelFamily(root, current, 0, "NEW", "current-channel");
        writePriorChannelFamily(root, prior, 0, "OLD", "prior-channel");
        File transaction = manualRecoveryTransaction(root, current, "unknown-version");
        writeUtf8(new File(transaction, "recovery.journal"),
                "deconv-migration-v99\nunknown\n");

        assertUnsafeFamilyAccess(root, current, "Retain exactly one complete");
        assertEquals("current-channel", readUtf8(DeconvolutionIO.deconvFile(root, current, 0)));
        assertEquals("prior-channel", readUtf8(DeconvolutionIO.deconvFile(root, prior, 0)));
        assertEquals(1, countFamilyQuarantines(root, current));
        // Retry remains conservative until the ambiguity is resolved.
        assertUnsafeFamilyAccess(root, current, "Retain exactly one complete");

        DeconvolutionIO.ArtifactIdentity unrelated = identityForRecovery("94", 2400L, 14);
        writePriorChannelFamily(root, unrelated, 0, "OTHER", "unrelated-channel");
        try (DeconvolutionFamilyLock.Handle ignored =
                     DeconvolutionIO.lockFamilyForAccess(root, unrelated)) {
            assertEquals("unrelated-channel", readUtf8(
                    DeconvolutionIO.deconvFile(root, unrelated, 0)));
        }
    }

    @Test
    public void validV2WithSoleCurrentIsQuarantinedWithoutChangingCurrent() throws Exception {
        assumeTrue("prior Windows v3 migration is Windows-specific", File.separatorChar == '\\');
        File root = temp.newFolder("v2-sole-current");
        DeconvolutionIO.ArtifactIdentity current = identityForRecovery("c1", 2350L, 41);
        writePriorChannelFamily(root, current, 0, "NEW", "current-channel");
        File transaction = manualRecoveryTransaction(root, current, "v2-current");
        writeLegacyV2ManifestOnlyJournal(transaction, root, current, true, false);

        try (DeconvolutionFamilyLock.Handle ignored =
                     DeconvolutionIO.lockFamilyForAccess(root, current)) {
            assertEquals("current-channel", readUtf8(
                    DeconvolutionIO.deconvFile(root, current, 0)));
        }
        assertEquals(1, countFamilyQuarantines(root, current));
        assertEquals(0, countFamilyTransactions(root, current));
    }

    @Test
    public void validV2WithSolePriorRemainsBlockedByUnauthenticatedQuarantine()
            throws Exception {
        assumeTrue("prior Windows v3 migration is Windows-specific", File.separatorChar == '\\');
        File root = temp.newFolder("v2-sole-prior");
        DeconvolutionIO.ArtifactIdentity current = identityForRecovery("c2", 2360L, 42);
        DeconvolutionIO.ArtifactIdentity prior = current.priorWindowsV3Identity();
        writePriorChannelFamily(root, prior, 0, "OLD", "prior-channel");
        File transaction = manualRecoveryTransaction(root, current, "v2-prior");
        writeLegacyV2ManifestOnlyJournal(transaction, root, current, false, false);

        try (DeconvolutionFamilyLock.Handle ignored =
                     DeconvolutionIO.lockFamilyForAccess(root, current)) {
            assertEquals("prior-channel", readUtf8(
                    DeconvolutionIO.deconvFile(root, prior, 0)));
        }
        assertEquals(1, countFamilyQuarantines(root, current));
        assertFalse("quarantine diagnostic text is not authority to promote",
                DeconvolutionIO.manifestFile(root, current).exists());
    }

    @Test
    public void validV2WithTwoCompleteGenerationsIsAmbiguous() throws Exception {
        assumeTrue("prior Windows v3 migration is Windows-specific", File.separatorChar == '\\');
        File root = temp.newFolder("v2-a");
        DeconvolutionIO.ArtifactIdentity current = identityForRecovery("c3", 2370L, 43);
        writePriorChannelFamily(root, current, 0, "NEW", "current-channel");
        writePriorChannelFamily(root, current.priorWindowsV3Identity(), 0,
                "OLD", "prior-channel");
        File transaction = manualRecoveryTransaction(root, current, "a");
        writeLegacyV2ManifestOnlyJournal(transaction, root, current, true, false);

        try (DeconvolutionFamilyLock.Handle ignored =
                     DeconvolutionIO.lockFamilyForAccess(root, current)) {
            fail("expected ambiguous v2 recovery to fail");
        } catch (java.io.IOException expected) {
            // The family must remain inaccessible; the preserved quarantine is the diagnostic.
        }
        assertEquals(1, countFamilyQuarantines(root, current));
    }

    @Test
    public void corruptV2WithSolePriorIsQuarantinedWithoutUnauthenticatedPromotion()
            throws Exception {
        assumeTrue("prior Windows v3 migration is Windows-specific", File.separatorChar == '\\');
        File root = temp.newFolder("v2-corrupt-prior");
        DeconvolutionIO.ArtifactIdentity current = identityForRecovery("c4", 2380L, 44);
        writePriorChannelFamily(root, current.priorWindowsV3Identity(), 0,
                "OLD", "prior-channel");
        File transaction = manualRecoveryTransaction(root, current, "v2-corrupt");
        writeLegacyV2ManifestOnlyJournal(transaction, root, current, false, true);

        try (DeconvolutionFamilyLock.Handle ignored =
                     DeconvolutionIO.lockFamilyForAccess(root, current)) {
            assertEquals("prior-channel", readUtf8(
                    DeconvolutionIO.deconvFile(root, current.priorWindowsV3Identity(), 0)));
        }
        assertEquals(1, countFamilyQuarantines(root, current));
        assertFalse(DeconvolutionIO.manifestFile(root, current).exists());
    }

    @Test
    public void orphanJournalTempIsQuarantinedWithoutTouchingPrior() throws Exception {
        assumeTrue("prior Windows v3 migration is Windows-specific", File.separatorChar == '\\');
        File root = temp.newFolder("v3-orphan-journal-temp");
        DeconvolutionIO.ArtifactIdentity current = identityForRecovery("95", 2500L, 15);
        DeconvolutionIO.ArtifactIdentity prior = current.priorWindowsV3Identity();
        writePriorChannelFamily(root, prior, 0, "OLD", "prior-channel");
        File transaction = manualRecoveryTransaction(root, current, "orphan-temp");
        writeUtf8(new File(transaction, "recovery.journal.tmp"), "partial journal bytes");

        try (DeconvolutionFamilyLock.Handle ignored =
                     DeconvolutionIO.lockFamilyForAccess(root, current)) {
            assertEquals("prior-channel", readUtf8(DeconvolutionIO.deconvFile(root, prior, 0)));
        }

        assertEquals(1, countFamilyQuarantines(root, current));
        assertFalse(DeconvolutionIO.manifestFile(root, current).exists());
    }

    @Test
    public void boundedJournalReaderQuarantinesOversizedRecordAndEncodingAttacks()
            throws Exception {
        assumeTrue("prior Windows v3 migration is Windows-specific", File.separatorChar == '\\');

        assertRejectedJournalBytes("oversized", "e1", new byte[
                DeconvolutionIO.MAX_RECOVERY_JOURNAL_BYTES + 1]);

        StringBuilder tooMany = new StringBuilder("deconv-migration-v3\n");
        for (int i = 0; i <= DeconvolutionIO.MAX_RECOVERY_JOURNAL_RECORDS; i++) {
            tooMany.append("retain\n");
        }
        tooMany.append("commit|0|").append(repeat("0", 64)).append('\n');
        assertRejectedJournalBytes("too-many", "e2",
                tooMany.toString().getBytes(StandardCharsets.UTF_8));

        String overlong = "deconv-migration-v3\nretain|"
                + repeat("x", DeconvolutionIO.MAX_RECOVERY_JOURNAL_FIELD_CHARS + 1)
                + "\ncommit|1|" + repeat("0", 64) + "\n";
        assertRejectedJournalBytes("overlong", "e3",
                overlong.getBytes(StandardCharsets.UTF_8));

        byte[] prefix = "deconv-migration-v3\n".getBytes(StandardCharsets.UTF_8);
        byte[] malformedUtf8 = new byte[prefix.length + 2];
        System.arraycopy(prefix, 0, malformedUtf8, 0, prefix.length);
        malformedUtf8[prefix.length] = (byte) 0xc3;
        malformedUtf8[prefix.length + 1] = (byte) 0x28;
        assertRejectedJournalBytes("malformed-utf8", "e4", malformedUtf8);
    }

    @Test
    public void oversizedAuthenticatedDesiredManifestIsQuarantinedWithoutRepeatReadFailure()
            throws Exception {
        assumeTrue("prior Windows v3 migration is Windows-specific", File.separatorChar == '\\');
        File root = temp.newFolder("bounded-desired-manifest");
        DeconvolutionIO.ArtifactIdentity current = identityForRecovery("e5", 2810L, 65);
        DeconvolutionIO.ArtifactIdentity prior = current.priorWindowsV3Identity();
        writePriorChannelFamily(root, prior, 0, "OLD", "prior-bounded-desired");
        File transaction = manualRecoveryTransaction(root, current, "oversized-desired");
        File desired = new File(transaction, "desired-manifest.json");
        try (RandomAccessFile sparse = new RandomAccessFile(desired, "rw")) {
            sparse.setLength((long) DeconvManifest.MAX_MANIFEST_UTF8_BYTES + 1L);
        }
        DeconvManifest.SourceFingerprint fingerprint =
                DeconvManifest.SourceFingerprint.of(desired);
        String records = "manifest|manifest|"
                + encodedPathForTest(DeconvolutionIO.manifestFile(root, current)) + '|'
                + encodedPathForTest(desired) + '|' + fingerprint.size + '|'
                + fingerprint.contentHash + "|0|-|-|-\n";
        writeChecksummedTestJournal(transaction, records);
        final String desiredPath = desired.getCanonicalPath();
        final AtomicInteger desiredHashCalls = new AtomicInteger();
        DeconvManifest.setContentHashTestHook(new DeconvManifest.ContentHashTestHook() {
            @Override
            public void beforeHash(File file) throws java.io.IOException {
                if (desiredPath.equals(file.getCanonicalPath())) desiredHashCalls.incrementAndGet();
            }
        });

        DeconvolutionIO.MigrationResult first =
                DeconvolutionIO.migratePriorWindowsV3FamilyForTest(root, current, null);

        assertFalse(first.migrated);
        assertEquals("oversized desired manifest must be rejected before SHA", 0,
                desiredHashCalls.get());
        assertEquals(0, countFamilyTransactions(root, current));
        assertEquals(1, countFamilyQuarantines(root, current));
        try (DeconvolutionFamilyLock.Handle ignored =
                     DeconvolutionIO.lockFamilyForAccess(root, current)) {
            assertEquals("prior-bounded-desired",
                    readUtf8(DeconvolutionIO.deconvFile(root, prior, 0)));
        }
    }

    @Test
    public void oversizedAuthenticatedManifestBackupIsRejectedBeforeHashing() throws Exception {
        assumeTrue("prior Windows v3 migration is Windows-specific", File.separatorChar == '\\');
        File root = temp.newFolder("bounded-manifest-backup-journal");
        DeconvolutionIO.ArtifactIdentity current = identityForRecovery("ea", 2815L, 68);
        DeconvolutionIO.ArtifactIdentity prior = current.priorWindowsV3Identity();
        writePriorChannelFamily(root, prior, 0, "OLD", "prior-bounded-backup-journal");
        File transaction = manualRecoveryTransaction(root, current, "oversized-backup-journal");
        File desired = new File(transaction, "desired-manifest.json");
        writeUtf8(desired, DeconvManifest.forArtifact(current).toJson());
        DeconvManifest.SourceFingerprint desiredFingerprint =
                DeconvManifest.SourceFingerprint.of(desired);
        File backup = new File(transaction, "manifest-backup.json");
        try (RandomAccessFile sparse = new RandomAccessFile(backup, "rw")) {
            sparse.setLength((long) DeconvManifest.MAX_MANIFEST_UTF8_BYTES + 1L);
        }
        String records = "manifest|manifest|"
                + encodedPathForTest(DeconvolutionIO.manifestFile(root, current)) + '|'
                + encodedPathForTest(desired) + '|' + desiredFingerprint.size + '|'
                + desiredFingerprint.contentHash + "|1|" + encodedPathForTest(backup) + '|'
                + backup.length() + '|' + repeat("0", 64) + "\n";
        writeChecksummedTestJournal(transaction, records);
        final String backupPath = backup.getCanonicalPath();
        final AtomicInteger backupHashCalls = new AtomicInteger();
        DeconvManifest.setContentHashTestHook(new DeconvManifest.ContentHashTestHook() {
            @Override
            public void beforeHash(File file) throws java.io.IOException {
                if (backupPath.equals(file.getCanonicalPath())) backupHashCalls.incrementAndGet();
            }
        });

        DeconvolutionIO.MigrationResult result =
                DeconvolutionIO.migratePriorWindowsV3FamilyForTest(root, current, null);

        assertFalse(result.migrated);
        assertEquals("oversized manifest backup must be rejected before SHA", 0,
                backupHashCalls.get());
        assertEquals(0, countFamilyTransactions(root, current));
        assertEquals(1, countFamilyQuarantines(root, current));
    }

    @Test
    public void exactBoundManifestFingerprintsRemainValidAndAreHashed() throws Exception {
        assumeTrue("prior Windows v3 migration is Windows-specific", File.separatorChar == '\\');
        File root = temp.newFolder("exact-bound-manifest-journal");
        DeconvolutionIO.ArtifactIdentity current = identityForRecovery("eb", 2816L, 69);
        DeconvolutionIO.ArtifactIdentity prior = current.priorWindowsV3Identity();
        writePriorChannelFamily(root, prior, 0, "OLD", "prior-exact-bound-manifest");
        File transaction = manualRecoveryTransaction(root, current, "exact-bound-manifests");
        File desired = new File(transaction, "desired-manifest.json");
        writeJsonPaddedToManifestLimit(desired, DeconvManifest.forArtifact(current).toJson());
        File backup = new File(transaction, "manifest-backup.json");
        Files.copy(desired.toPath(), backup.toPath());
        DeconvManifest.SourceFingerprint desiredFingerprint =
                DeconvManifest.SourceFingerprint.of(desired);
        DeconvManifest.SourceFingerprint backupFingerprint =
                DeconvManifest.SourceFingerprint.of(backup);
        String records = "manifest|manifest|"
                + encodedPathForTest(DeconvolutionIO.manifestFile(root, current)) + '|'
                + encodedPathForTest(desired) + '|' + desiredFingerprint.size + '|'
                + desiredFingerprint.contentHash + "|1|" + encodedPathForTest(backup) + '|'
                + backupFingerprint.size + '|' + backupFingerprint.contentHash + "\n";
        writeChecksummedTestJournal(transaction, records);
        final String desiredPath = desired.getCanonicalPath();
        final String backupPath = backup.getCanonicalPath();
        final AtomicInteger desiredHashCalls = new AtomicInteger();
        final AtomicInteger backupHashCalls = new AtomicInteger();
        DeconvManifest.setContentHashTestHook(new DeconvManifest.ContentHashTestHook() {
            @Override
            public void beforeHash(File file) throws java.io.IOException {
                String path = file.getCanonicalPath();
                if (desiredPath.equals(path)) desiredHashCalls.incrementAndGet();
                if (backupPath.equals(path)) backupHashCalls.incrementAndGet();
            }
        });

        DeconvolutionIO.MigrationResult result =
                DeconvolutionIO.migratePriorWindowsV3FamilyForTest(root, current, null);

        assertFalse(result.migrated);
        assertTrue(result.failure != null);
        assertTrue("exact-bound desired manifest should reach SHA validation",
                desiredHashCalls.get() > 0);
        assertTrue("exact-bound manifest backup should reach SHA validation",
                backupHashCalls.get() > 0);
    }

    @Test
    public void oversizedStagedAndBackupManifestsUseBoundedQuarantineDescriptions()
            throws Exception {
        assumeTrue("prior Windows v3 migration is Windows-specific", File.separatorChar == '\\');
        File root = temp.newFolder("bounded-quarantine-manifests");
        DeconvolutionIO.ArtifactIdentity current = identityForRecovery("e6", 2820L, 66);
        DeconvolutionIO.ArtifactIdentity prior = current.priorWindowsV3Identity();
        writePriorChannelFamily(root, prior, 0, "OLD", "prior-bounded-backup");
        File transaction = manualRecoveryTransaction(root, current, "oversized-staging");
        File desired = new File(transaction, "desired-manifest.json");
        File backup = new File(transaction, "manifest-backup.json");
        try (RandomAccessFile sparse = new RandomAccessFile(desired, "rw")) {
            sparse.setLength((long) DeconvManifest.MAX_MANIFEST_UTF8_BYTES + 1L);
        }
        try (RandomAccessFile sparse = new RandomAccessFile(backup, "rw")) {
            sparse.setLength((long) DeconvManifest.MAX_MANIFEST_UTF8_BYTES + 1L);
        }

        try (DeconvolutionFamilyLock.Handle ignored =
                     DeconvolutionIO.lockFamilyForAccess(root, current)) {
            assertEquals("prior-bounded-backup",
                    readUtf8(DeconvolutionIO.deconvFile(root, prior, 0)));
        }
        assertEquals(1, countFamilyQuarantines(root, current));
    }

    @Test
    public void oversizedQuarantineDiagnosticNeverAllocatesOrBlocksTheLock() throws Exception {
        assumeTrue("prior Windows v3 migration is Windows-specific", File.separatorChar == '\\');
        File root = temp.newFolder("bounded-quarantine-diagnostic");
        DeconvolutionIO.ArtifactIdentity current = identityForRecovery("e7", 2830L, 67);
        DeconvolutionIO.ArtifactIdentity prior = current.priorWindowsV3Identity();
        writePriorChannelFamily(root, prior, 0, "OLD", "prior-bounded-diagnostic");
        File quarantine = new File(new File(new File(DeconvolutionIO.cacheDir(root),
                ".migration"), ".quarantine"), current.familyLockToken() + "-oversized");
        Files.createDirectories(quarantine.toPath());
        try (RandomAccessFile sparse = new RandomAccessFile(
                new File(quarantine, "QUARANTINE.txt"), "rw")) {
            sparse.setLength(64L * 1024L + 1L);
        }

        for (int attempt = 0; attempt < 2; attempt++) {
            try (DeconvolutionFamilyLock.Handle ignored =
                         DeconvolutionIO.lockFamilyForAccess(root, current)) {
                assertEquals("prior-bounded-diagnostic",
                        readUtf8(DeconvolutionIO.deconvFile(root, prior, 0)));
            }
        }
        assertFalse(DeconvolutionIO.manifestFile(root, current).exists());
    }

    @Test
    public void boundedJournalReaderAcceptsExactLimitsAndBothSupportedVersions()
            throws Exception {
        File exactBytes = temp.newFile("exact-recovery-journal-bytes");
        StringBuilder bounded = new StringBuilder(
                DeconvolutionIO.MAX_RECOVERY_JOURNAL_BYTES);
        bounded.append("deconv-migration-v3\n");
        String fullField = repeat("x", DeconvolutionIO.MAX_RECOVERY_JOURNAL_FIELD_CHARS);
        while (bounded.length() + fullField.length() + 1
                <= DeconvolutionIO.MAX_RECOVERY_JOURNAL_BYTES) {
            bounded.append(fullField).append('\n');
        }
        bounded.append(repeat("x",
                DeconvolutionIO.MAX_RECOVERY_JOURNAL_BYTES - bounded.length()));
        byte[] exactPayload = bounded.toString().getBytes(StandardCharsets.UTF_8);
        assertEquals(DeconvolutionIO.MAX_RECOVERY_JOURNAL_BYTES, exactPayload.length);
        Files.write(exactBytes.toPath(), exactPayload);
        assertTrue(DeconvolutionIO.JournalDocument.read(exactBytes)
                .isVersion("deconv-migration-v3"));

        File exactRecords = temp.newFile("exact-recovery-journal-records");
        StringBuilder records = new StringBuilder("deconv-migration-v2\n");
        for (int i = 0; i < DeconvolutionIO.MAX_RECOVERY_JOURNAL_RECORDS; i++) {
            records.append("record\n");
        }
        records.append("commit\n");
        Files.write(exactRecords.toPath(), records.toString().getBytes(StandardCharsets.UTF_8));
        DeconvolutionIO.JournalDocument legacy =
                DeconvolutionIO.JournalDocument.read(exactRecords);
        assertTrue(legacy.isVersion("deconv-migration-v2"));
        assertEquals(DeconvolutionIO.MAX_RECOVERY_JOURNAL_RECORDS + 2,
                legacy.lines.size());
    }

    @Test
    public void journalCommitFailureLeavesNoAuthorityAndMigrationRetries() throws Exception {
        assumeTrue("prior Windows v3 migration is Windows-specific", File.separatorChar == '\\');
        final File root = temp.newFolder("v3-journal-atomic-failure");
        final DeconvolutionIO.ArtifactIdentity current = identityForRecovery("96", 2600L, 16);
        DeconvolutionIO.ArtifactIdentity prior = current.priorWindowsV3Identity();
        writePriorChannelFamily(root, prior, 0, "OLD", "prior-channel");

        DeconvolutionIO.MigrationResult failed =
                DeconvolutionIO.migratePriorWindowsV3FamilyForTest(root, current,
                        new DeconvolutionIO.MigrationFaultInjector() {
                            @Override
                            public void checkpoint(DeconvolutionIO.MigrationFaultPoint point)
                                    throws java.io.IOException {
                                if (point != DeconvolutionIO.MigrationFaultPoint
                                        .BEFORE_JOURNAL_COMMIT) return;
                                File active = firstFamilyTransaction(root, current);
                                assertTrue(active != null);
                                assertTrue(new File(active, "recovery.journal.tmp").isFile());
                                assertFalse(new File(active, "recovery.journal").exists());
                                throw new java.io.IOException("deterministic journal commit failure");
                            }
                        });

        assertFalse(failed.migrated);
        assertTrue(failed.safe);
        assertTrue(failed.failure != null);
        assertEquals(0, countFamilyTransactions(root, current));
        assertEquals(0, countFamilyQuarantines(root, current));
        assertEquals("prior-channel", readUtf8(DeconvolutionIO.deconvFile(root, prior, 0)));

        DeconvolutionIO.MigrationResult retried =
                DeconvolutionIO.migratePriorWindowsV3FamilyForTest(root, current, null);
        assertTrue(retried.migrated);
        assertEquals("prior-channel", readUtf8(DeconvolutionIO.deconvFile(root, current, 0)));
    }

    @Test
    public void preAuthorityDiskAccessAndForceFailuresDiscardOnlyStagingAndRetry()
            throws Exception {
        assumeTrue("prior Windows v3 migration is Windows-specific", File.separatorChar == '\\');
        DeconvolutionIO.MigrationFaultPoint[] preparationFailures = {
                DeconvolutionIO.MigrationFaultPoint.AFTER_TRANSACTION_CREATED,
                DeconvolutionIO.MigrationFaultPoint.BEFORE_STAGE_PUBLICATION_COPY,
                DeconvolutionIO.MigrationFaultPoint.BEFORE_STAGE_PUBLICATION_FORCE,
                DeconvolutionIO.MigrationFaultPoint.BEFORE_STAGED_MANIFEST_WRITE
        };
        String[] pairs = {"d1", "d2", "d3", "d4"};
        for (int i = 0; i < preparationFailures.length; i++) {
            File root = temp.newFolder("v3-pre-authority-" + i);
            DeconvolutionIO.ArtifactIdentity current = identityForRecovery(
                    pairs[i], 2610L + i, 50 + i);
            DeconvolutionIO.ArtifactIdentity prior = current.priorWindowsV3Identity();
            writePriorChannelFamily(root, prior, 0, "OLD", "prior-channel-" + i);

            DeconvolutionIO.MigrationResult failed =
                    DeconvolutionIO.migratePriorWindowsV3FamilyForTest(root, current,
                            failMigrationAt(preparationFailures[i]));

            assertFalse(preparationFailures[i].name(), failed.migrated);
            assertTrue(preparationFailures[i].name(), failed.safe);
            assertTrue(preparationFailures[i].name(), failed.failure != null);
            assertEquals(preparationFailures[i].name(), 0,
                    countFamilyTransactions(root, current));
            assertEquals(preparationFailures[i].name(), 0,
                    countFamilyQuarantines(root, current));
            assertEquals("prior-channel-" + i,
                    readUtf8(DeconvolutionIO.deconvFile(root, prior, 0)));
            assertTrue(DeconvolutionIO.manifestFile(root, prior).isFile());
            assertFalse(DeconvolutionIO.manifestFile(root, current).exists());

            DeconvolutionIO.MigrationResult retry =
                    DeconvolutionIO.migratePriorWindowsV3FamilyForTest(root, current, null);
            assertTrue(preparationFailures[i].name(), retry.migrated);
            assertEquals("prior-channel-" + i,
                    readUtf8(DeconvolutionIO.deconvFile(root, current, 0)));
        }
    }

    @Test
    public void failedPreAuthorityDeleteIsRelocatedAndCannotBlockRetry() throws Exception {
        assumeTrue("prior Windows v3 migration is Windows-specific", File.separatorChar == '\\');
        final File root = temp.newFolder("v3-pre-authority-delete-failure");
        final DeconvolutionIO.ArtifactIdentity current = identityForRecovery("d5", 2620L, 54);
        DeconvolutionIO.ArtifactIdentity prior = current.priorWindowsV3Identity();
        writePriorChannelFamily(root, prior, 0, "OLD", "prior-delete-failure");

        DeconvolutionIO.MigrationResult failed =
                DeconvolutionIO.migratePriorWindowsV3FamilyForTest(root, current,
                        new DeconvolutionIO.MigrationFaultInjector() {
                            @Override
                            public void checkpoint(DeconvolutionIO.MigrationFaultPoint point)
                                    throws java.io.IOException {
                                if (point == DeconvolutionIO.MigrationFaultPoint
                                        .BEFORE_STAGED_MANIFEST_WRITE
                                        || point == DeconvolutionIO.MigrationFaultPoint
                                        .BEFORE_UNCOMMITTED_STAGING_DELETE) {
                                    throw new java.io.IOException("injected " + point);
                                }
                            }
                        });

        assertFalse(failed.migrated);
        assertTrue(failed.safe);
        assertEquals(0, countFamilyTransactions(root, current));
        assertEquals(1, countDeferredCleanupTransactions(root, current));
        assertEquals(0, countFamilyQuarantines(root, current));

        try (DeconvolutionFamilyLock.Handle ignored =
                     DeconvolutionIO.lockFamilyForAccess(root, current)) {
            assertEquals("prior-delete-failure",
                    readUtf8(DeconvolutionIO.deconvFile(root, current, 0)));
        }
        if (hasStableFileKey(root)) {
            assertEquals(0, countDeferredCleanupTransactions(root, current));
        } else {
            assertEquals(0, countDeferredCleanupTransactions(root, current));
            assertTrue(new File(DeconvolutionIO.cacheDir(root), ".migration-retained").isDirectory());
        }
        assertEquals(0, countFamilyQuarantines(root, current));
    }

    @Test
    public void cleanupNamespaceRemainsNonAuthoritativeAfterMarkerDeletionFaultAndRestart()
            throws Exception {
        assumeTrue("prior Windows v3 migration is Windows-specific", File.separatorChar == '\\');
        final File root = temp.newFolder("v3-cleanup-marker-delete-crash");
        final DeconvolutionIO.ArtifactIdentity current = identityForRecovery("ec", 2625L, 70);
        DeconvolutionIO.ArtifactIdentity prior = current.priorWindowsV3Identity();
        writePriorChannelFamily(root, prior, 0, "OLD", "prior-marker-delete-crash");

        DeconvolutionIO.MigrationResult failed =
                DeconvolutionIO.migratePriorWindowsV3FamilyForTest(root, current,
                        new DeconvolutionIO.MigrationFaultInjector() {
                            @Override
                            public void checkpoint(DeconvolutionIO.MigrationFaultPoint point)
                                    throws java.io.IOException {
                                if (point == DeconvolutionIO.MigrationFaultPoint
                                        .BEFORE_STAGED_MANIFEST_WRITE
                                        || point == DeconvolutionIO.MigrationFaultPoint
                                        .AFTER_DEFERRED_CLEANUP_MARKER_DELETE) {
                                    throw new java.io.IOException("injected " + point);
                                }
                            }
                        });

        assertFalse(failed.migrated);
        assertTrue(failed.safe);
        assertEquals(0, countFamilyTransactions(root, current));
        assertEquals(1, countDeferredCleanupTransactions(root, current));
        File cleanup = firstDeferredCleanupTransaction(root, current);
        assertTrue(cleanup != null);
        assertFalse(new File(cleanup, DeconvolutionIO.DEFERRED_CLEANUP_MARKER).exists());

        try (DeconvolutionFamilyLock.Handle ignored =
                     DeconvolutionIO.lockFamilyForAccess(root, current)) {
            assertEquals("prior-marker-delete-crash",
                    readUtf8(DeconvolutionIO.deconvFile(root, current, 0)));
        }
        if (hasStableFileKey(root)) {
            assertEquals(0, countDeferredCleanupTransactions(root, current));
        } else {
            assertEquals(0, countDeferredCleanupTransactions(root, current));
            assertTrue(new File(DeconvolutionIO.cacheDir(root), ".migration-retained").isDirectory());
        }
        assertEquals(0, countFamilyQuarantines(root, current));
    }

    @Test
    public void nonDurableCleanupMarkerNeverAllowsDeletionOrQuarantine() throws Exception {
        assumeTrue("prior Windows v3 migration is Windows-specific", File.separatorChar == '\\');
        final File root = temp.newFolder("v3-cleanup-marker-force-failure");
        final DeconvolutionIO.ArtifactIdentity current = identityForRecovery("ed", 2626L, 71);
        DeconvolutionIO.ArtifactIdentity prior = current.priorWindowsV3Identity();
        writePriorChannelFamily(root, prior, 0, "OLD", "prior-marker-force-failure");
        DeconvolutionIO.setDirectoryForceHookForTest(
                new DeconvolutionIO.DirectoryForceTestHook() {
                    @Override
                    public boolean force(File directory) {
                        return !new File(directory,
                                DeconvolutionIO.DEFERRED_CLEANUP_MARKER).isFile();
                    }
                });

        DeconvolutionIO.MigrationResult failed =
                DeconvolutionIO.migratePriorWindowsV3FamilyForTest(root, current,
                        failMigrationAt(DeconvolutionIO.MigrationFaultPoint
                                .BEFORE_STAGED_MANIFEST_WRITE));

        assertFalse(failed.migrated);
        assertTrue(failed.safe);
        assertEquals(1, countFamilyTransactions(root, current));
        assertEquals(0, countDeferredCleanupTransactions(root, current));
        assertEquals(0, countFamilyQuarantines(root, current));
        DeconvolutionIO.setDirectoryForceHookForTest(null);

        try (DeconvolutionFamilyLock.Handle ignored =
                     DeconvolutionIO.lockFamilyForAccess(root, current)) {
            assertEquals("prior-marker-force-failure",
                    readUtf8(DeconvolutionIO.deconvFile(root, current, 0)));
        }
        assertEquals(0, countFamilyTransactions(root, current));
        assertEquals(0, countFamilyQuarantines(root, current));
    }

    @Test
    public void deferredCleanupIsFamilyFilteredBoundedAndEventuallyComplete() throws Exception {
        File root = temp.newFolder("bounded-family-cleanup");
        DeconvolutionIO.ArtifactIdentity current = identityForRecovery("ee", 2627L, 72);
        DeconvolutionIO.ArtifactIdentity other = identityForRecovery("ed", 2626L, 71);
        int otherCount = 256;
        for (int i = 0; i < otherCount; i++) {
            File payload = DeconvolutionIO.enqueueDeferredCleanupForTest(root, other,
                    "other-family-" + i);
            writeUtf8(new File(payload, "residue.bin"), "other");
        }
        int currentCount = DeconvolutionIO.MAX_DEFERRED_CLEANUP_PER_ACCESS + 5;
        for (int i = 0; i < currentCount; i++) {
            File candidate = DeconvolutionIO.enqueueDeferredCleanupForTest(root, current,
                    "cleanup-" + i);
            writeUtf8(new File(candidate, "residue.bin"), "current");
        }

        DeconvolutionIO.scavengeDeferredCleanupForTest(root, current);

        assertTrue(countDeferredCleanupTransactions(root, current) > 0);
        assertTrue(countDeferredCleanupTransactions(root, current) < currentCount);
        assertEquals(otherCount, countDeferredCleanupTransactions(root, other));
        for (int i = 0; i < 8 && countDeferredCleanupTransactions(root, current) > 0; i++) {
            DeconvolutionIO.scavengeDeferredCleanupForTest(root, current);
        }
        assertEquals(0, countDeferredCleanupTransactions(root, current));
        assertEquals(otherCount, countDeferredCleanupTransactions(root, other));
    }

    @Test
    public void deferredCleanupDeletesLinkedFamilyChildWithoutFollowingIt() throws Exception {
        assumeTrue("junction regression is Windows-specific", File.separatorChar == '\\');
        File root = temp.newFolder("linked-family-cleanup-child");
        DeconvolutionIO.ArtifactIdentity current = identityForRecovery("ef", 2628L, 73);
        File candidate = DeconvolutionIO.enqueueDeferredCleanupForTest(root, current,
                "linked-payload");
        File outside = temp.newFolder("linked-family-cleanup-outside");
        File outsideFile = new File(outside, "must-remain.bin");
        writeUtf8(outsideFile, "outside");
        File link = new File(candidate, "linked");
        assumeTrue(createJunction(link, outside));
        try {
            DeconvolutionIO.scavengeDeferredCleanupForTest(root, current);
            assertEquals("outside", readUtf8(outsideFile));
            assertFalse("the direct opaque payload is rebound without traversal", link.exists());
        } finally {
            Files.deleteIfExists(link.toPath());
        }
    }

    @Test
    public void cleanupTraversalSwapNeverEscapesFixedMigrationRoot() throws Exception {
        assumeTrue("junction regression is Windows-specific", File.separatorChar == '\\');
        for (int depth = 0; depth < 2; depth++) {
            File root = temp.newFolder("cleanup-swap-project-" + depth);
            final DeconvolutionIO.ArtifactIdentity current =
                    identityForRecovery(depth == 0 ? "a1" : "a2", 2900L + depth, 80 + depth);
            final File candidate = DeconvolutionIO.enqueueDeferredCleanupForTest(root, current,
                    "swap-race");
            final File swapPoint = depth == 0 ? candidate : new File(candidate, "nested");
            Files.createDirectories(swapPoint.toPath());
            File outsideAncestor = temp.newFolder("outside-swap-" + depth);
            final File outside = new File(new File(outsideAncestor, ".migration"), "external");
            Files.createDirectories(outside.toPath());
            final File sentinel = new File(outside, "must-survive.txt");
            writeUtf8(sentinel, "outside");
            final AtomicInteger swaps = new AtomicInteger();
            DeconvolutionIO.setCleanupTraversalHookForTest(
                    new DeconvolutionIO.CleanupTraversalTestHook() {
                        @Override
                        public void beforeTraversal(File directory) throws java.io.IOException {
                            try {
                                if (!directory.getCanonicalPath().equals(swapPoint.getCanonicalPath())
                                        || !swaps.compareAndSet(0, 1)) return;
                                Files.delete(swapPoint.toPath());
                                if (!createJunction(swapPoint, outside)) {
                                    throw new java.io.IOException("could not create deterministic junction");
                                }
                            } catch (java.io.IOException e) {
                                throw e;
                            } catch (Exception e) {
                                throw new java.io.IOException(e);
                            }
                        }
                    });

            DeconvolutionIO.scavengeDeferredCleanupForTest(root, current);

            assertEquals("opaque TREE payloads are never traversed", 0, swaps.get());
            assertEquals("outside", readUtf8(sentinel));
            assertFalse("the direct opaque payload is rebound without traversal", swapPoint.exists());
            DeconvolutionIO.setCleanupTraversalHookForTest(null);
        }
    }

    @Test
    public void exactRecoveryHashRejectsGrowthAndTruncationWithBoundedRead() throws Exception {
        final File file = temp.newFile("exact-recovery-hash.bin");
        final byte[] authenticated = "authenticated".getBytes(StandardCharsets.UTF_8);
        Files.write(file.toPath(), authenticated);
        String expected = hexDigest(authenticated);
        assertEquals(expected, DeconvManifest.SourceFingerprint
                .exactContentHash(file, authenticated.length));

        try {
            DeconvManifest.SourceFingerprint.exactContentHash(file, authenticated.length + 1L);
            fail("truncation must be rejected");
        } catch (java.io.IOException expectedFailure) {
            assertTrue(expectedFailure.getMessage().contains("truncated"));
        }

        DeconvManifest.setContentHashTestHook(new DeconvManifest.ContentHashTestHook() {
            @Override
            public void beforeHash(File ignored) throws java.io.IOException {
                try (RandomAccessFile sparse = new RandomAccessFile(file, "rw")) {
                    sparse.setLength(1024L * 1024L * 1024L);
                }
            }
        });
        long started = System.nanoTime();
        try {
            DeconvManifest.SourceFingerprint.exactContentHash(file, authenticated.length);
            fail("growth must be rejected");
        } catch (java.io.IOException expectedFailure) {
            assertTrue(expectedFailure.getMessage().contains("grew"));
        }
        assertTrue("the unauthenticated 1 GiB tail must not be read",
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 2000L);
    }

    @Test
    public void exactRecoveryHashAcceptsLargeNonManifestArtifact() throws Exception {
        File file = temp.newFile("large-recovery-artifact.tif");
        byte[] bytes = new byte[5 * 1024 * 1024 + 17];
        for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) (i * 31);
        Files.write(file.toPath(), bytes);
        assertEquals(hexDigest(bytes), DeconvManifest.SourceFingerprint
                .exactContentHash(file, bytes.length));
    }

    @Test
    public void exactRecoveryHashPhysicallyRequestsOnlyAuthenticatedBytesPlusProbe()
            throws Exception {
        final AtomicInteger requested = new AtomicInteger();
        DeconvManifest.setExactReadTestHook(new DeconvManifest.ExactReadTestHook() {
            @Override
            public void beforeRead(int requestedBytes) {
                requested.addAndGet(requestedBytes);
            }
        });
        File exact = temp.newFile("exact-read-count.bin");
        byte[] bytes = new byte[2 * 1024 * 1024 + 7];
        Files.write(exact.toPath(), bytes);
        DeconvManifest.SourceFingerprint.exactContentHash(exact, bytes.length);
        assertEquals("only authenticated bytes and one EOF probe may be requested",
                bytes.length + 1, requested.get());

        requested.set(0);
        File empty = temp.newFile("exact-read-zero.bin");
        DeconvManifest.SourceFingerprint.exactContentHash(empty, 0L);
        assertEquals("zero-length authentication performs only its EOF probe", 1,
                requested.get());
    }

    @Test
    public void recoverySnapshotGrowthIsBoundedToAuthenticatedBytesPlusProbe()
            throws Exception {
        final File source = temp.newFile("bounded-recovery-snapshot.bin");
        final byte[] bytes = new byte[2 * 1024 * 1024 + 7];
        for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) (i * 17);
        Files.write(source.toPath(), bytes);
        final File actions = temp.newFolder("bounded-recovery-actions");
        final AtomicInteger requested = new AtomicInteger();
        final AtomicInteger written = new AtomicInteger();
        final AtomicInteger maximumOnDisk = new AtomicInteger();
        final AtomicInteger grew = new AtomicInteger();
        DeconvolutionIO.setRecoverySnapshotIoHookForTest(
                new DeconvolutionIO.RecoverySnapshotIoTestHook() {
                    @Override
                    public void beforeRead(File readSource, int requestedBytes)
                            throws java.io.IOException {
                        requested.addAndGet(requestedBytes);
                        if (readSource.equals(source) && grew.compareAndSet(0, 1)) {
                            try (RandomAccessFile sparse = new RandomAccessFile(source, "rw")) {
                                sparse.setLength(1024L * 1024L * 1024L);
                            }
                        }
                    }

                    @Override
                    public void afterWrite(File snapshot, int writtenBytes)
                            throws java.io.IOException {
                        written.addAndGet(writtenBytes);
                        maximumOnDisk.set(Math.max(maximumOnDisk.get(),
                                (int) Files.size(snapshot.toPath())));
                    }
                });
        long started = System.nanoTime();
        try {
            DeconvolutionIO.createAuthenticatedRecoverySnapshotForTest(source, bytes.length,
                    hexDigest(bytes), actions);
            fail("a growing snapshot source must be rejected");
        } catch (java.io.IOException expected) {
            assertTrue(expected.getMessage().contains("grew beyond"));
        }
        assertTrue("a sparse unauthenticated tail must be rejected immediately",
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 2000L);
        assertEquals(bytes.length + 1, requested.get());
        assertEquals(bytes.length, written.get());
        assertTrue(maximumOnDisk.get() <= bytes.length);
        assertEquals("private partial snapshots must be removed", 0,
                actions.listFiles() == null ? 0 : actions.listFiles().length);
    }

    @Test
    public void recoverySnapshotTruncationDeletesItsBoundedPartial() throws Exception {
        final File source = temp.newFile("short-recovery-snapshot.bin");
        final byte[] bytes = new byte[16384];
        Files.write(source.toPath(), bytes);
        final File actions = temp.newFolder("short-recovery-actions");
        final AtomicInteger requested = new AtomicInteger();
        final AtomicInteger written = new AtomicInteger();
        final AtomicInteger truncated = new AtomicInteger();
        DeconvolutionIO.setRecoverySnapshotIoHookForTest(
                new DeconvolutionIO.RecoverySnapshotIoTestHook() {
                    @Override
                    public void beforeRead(File readSource, int requestedBytes)
                            throws java.io.IOException {
                        requested.addAndGet(requestedBytes);
                        if (truncated.compareAndSet(0, 1)) {
                            try (RandomAccessFile shortFile = new RandomAccessFile(readSource, "rw")) {
                                shortFile.setLength(17L);
                            }
                        }
                    }

                    @Override
                    public void afterWrite(File snapshot, int writtenBytes) {
                        written.addAndGet(writtenBytes);
                    }
                });
        try {
            DeconvolutionIO.createAuthenticatedRecoverySnapshotForTest(source, bytes.length,
                    hexDigest(bytes), actions);
            fail("a truncated snapshot source must be rejected");
        } catch (java.io.IOException expected) {
            assertTrue(expected.getMessage().contains("truncated"));
        }
        assertTrue(requested.get() <= bytes.length + 1);
        assertTrue(written.get() <= 17);
        assertEquals(0, actions.listFiles() == null ? 0 : actions.listFiles().length);
    }

    @Test
    public void priorSnapshotPreparationFailureLeavesHealthyTargetUntouched()
            throws Exception {
        final byte[] healthy = "healthy".getBytes(StandardCharsets.UTF_8);
        byte[] desired = "desired".getBytes(StandardCharsets.UTF_8);
        final File target = temp.newFile("healthy-live-target.bin");
        final File priorSource = temp.newFile("prior-recovery-source.bin");
        File desiredSource = temp.newFile("desired-recovery-source.bin");
        File retained = temp.newFolder("rejected-recovery-targets");
        Files.write(target.toPath(), healthy);
        Files.write(priorSource.toPath(), healthy);
        Files.write(desiredSource.toPath(), desired);
        final AtomicInteger damaged = new AtomicInteger();
        DeconvolutionIO.setRecoverySnapshotIoHookForTest(
                new DeconvolutionIO.RecoverySnapshotIoTestHook() {
                    @Override
                    public void beforeRead(File source, int requestedBytes)
                            throws java.io.IOException {
                        if (source.equals(priorSource) && damaged.compareAndSet(0, 1)) {
                            Files.write(priorSource.toPath(),
                                    "damaged".getBytes(StandardCharsets.UTF_8));
                        }
                    }

                    @Override
                    public void afterWrite(File snapshot, int writtenBytes) {
                    }
                });
        try {
            DeconvolutionIO.publishAuthenticatedRecoveryFileForTest(desiredSource,
                    desired.length, hexDigest(desired), target, priorSource, healthy.length,
                    hexDigest(healthy), retained);
            fail("invalid prior snapshot preparation must abort publication");
        } catch (java.io.IOException expected) {
            assertTrue(expected.getMessage().contains("fingerprint mismatch"));
        }
        assertEquals("healthy", readUtf8(target));
        assertEquals(0, retained.listFiles() == null ? 0 : retained.listFiles().length);
    }

    @Test
    public void exactDeleteNeverDeletesSameLengthRenameReplacement() throws Exception {
        final File artifact = temp.newFile("exact-delete-race.bin");
        final byte[] original = "original".getBytes(StandardCharsets.UTF_8);
        final byte[] replacement = "replaced".getBytes(StandardCharsets.UTF_8);
        assertEquals(original.length, replacement.length);
        Files.write(artifact.toPath(), original);
        final File authenticatedHandle = new File(artifact.getParentFile(), "authenticated-old.bin");
        final AtomicInteger swaps = new AtomicInteger();
        DeconvolutionIO.setDeleteBindingHookForTest(
                new DeconvolutionIO.DeleteBindingTestHook() {
                    @Override
                    public void beforeAtomicBinding(File file) throws java.io.IOException {
                        if (!file.equals(artifact) || !swaps.compareAndSet(0, 1)) return;
                        Files.move(artifact.toPath(), authenticatedHandle.toPath());
                        Files.write(artifact.toPath(), replacement);
                    }
                });
        try {
            DeconvolutionIO.deleteIfExactForTest(artifact, original.length,
                    hexDigest(original));
            fail("a rename replacement after hashing must abort deletion");
        } catch (java.io.IOException expected) {
            assertTrue(expected.getMessage().contains("changed before"));
        }
        assertEquals(1, swaps.get());
        assertFalse("the replacement must be removed from the live deletion name", artifact.exists());
        assertTrue("the replacement must be preserved in the retained namespace",
                treeContainsUtf8(DeconvolutionIO.cacheDir(artifact.getParentFile()), "replaced"));
        assertEquals("original", readUtf8(authenticatedHandle));
    }

    @Test
    public void exactDeleteRetainsSameInodeRewriteAfterInitialValidation() throws Exception {
        final File artifact = temp.newFile("exact-inode-before-bind.bin");
        final byte[] original = "original".getBytes(StandardCharsets.UTF_8);
        final byte[] changed = "changed!".getBytes(StandardCharsets.UTF_8);
        Files.write(artifact.toPath(), original);
        final AtomicInteger rewrites = new AtomicInteger();
        DeconvolutionIO.setExactFileActionHookForTest(
                new DeconvolutionIO.ExactFileActionTestHook() {
                    @Override
                    public void afterValidation(File file) throws java.io.IOException {
                        if (file.equals(artifact) && rewrites.compareAndSet(0, 1)) {
                            Files.write(file.toPath(), changed);
                        }
                    }
                });
        try {
            DeconvolutionIO.deleteIfExactForTest(artifact, original.length,
                    hexDigest(original));
            fail("a same-inode rewrite before private binding must be rejected");
        } catch (java.io.IOException expected) {
            assertTrue(expected.getMessage().contains("changed before"));
        }
        if (artifact.exists()) {
            assertEquals("changed!", readUtf8(artifact));
        } else {
            assertTrue(treeContainsUtf8(DeconvolutionIO.cacheDir(artifact.getParentFile()),
                    "changed!"));
        }
    }

    @Test
    public void exactDeleteRetainsSameInodeRewriteAfterPrivateBinding() throws Exception {
        final File artifact = temp.newFile("exact-inode-after-bind.bin");
        final byte[] original = "original".getBytes(StandardCharsets.UTF_8);
        final byte[] changed = "changed!".getBytes(StandardCharsets.UTF_8);
        Files.write(artifact.toPath(), original);
        final AtomicInteger rewrites = new AtomicInteger();
        DeconvolutionIO.setExactFileActionHookForTest(
                new DeconvolutionIO.ExactFileActionTestHook() {
                    @Override
                    public void afterValidation(File file) throws java.io.IOException {
                        if (file.getName().startsWith("action-")
                                && rewrites.compareAndSet(0, 1)) {
                            Files.write(file.toPath(), changed);
                        }
                    }
                });
        DeconvolutionIO.deleteIfExactForTest(artifact, original.length,
                hexDigest(original));
        assertFalse(artifact.exists());
        assertEquals(1, rewrites.get());
        assertTrue("changed bytes bound to the private action must never be deleted",
                treeContainsUtf8(DeconvolutionIO.cacheDir(artifact.getParentFile()), "changed!"));
    }

    @Test
    public void keylessExactDeleteLeavesLiveFileWithoutCreatingUnsafeQueue() throws Exception {
        File artifact = temp.newFile("keyless-exact-delete.bin");
        byte[] original = "keyless-exact".getBytes(StandardCharsets.UTF_8);
        Files.write(artifact.toPath(), original);
        DeconvolutionIO.setStableFileIdentityOverrideForTest(Boolean.FALSE);

        try {
            DeconvolutionIO.deleteIfExactForTest(artifact, original.length,
                    hexDigest(original));
            fail("keyless queue creation must fail closed");
        } catch (DeconvolutionIO.RetryableCleanupException expected) {
            assertTrue(expected.getMessage().contains("handle-relative directory creation"));
        }

        assertEquals("keyless-exact", readUtf8(artifact));
        assertFalse(treeContainsNamedFile(DeconvolutionIO.cacheDir(artifact.getParentFile()),
                "00000000000000000000.ticket"));
    }

    @Test
    public void keyedExactDeleteRetainsClassifiedActionWithoutPathUnlinkOrChurn()
            throws Exception {
        File root = temp.newFolder("keyed-exact-retained");
        File artifact = new File(root, "keyed-exact.bin");
        byte[] original = "keyed-exact".getBytes(StandardCharsets.UTF_8);
        Files.write(artifact.toPath(), original);
        DeconvolutionIO.setStableFileIdentityOverrideForTest(Boolean.TRUE);

        DeconvolutionIO.deleteIfExactForTest(artifact, original.length,
                hexDigest(original));

        File familyQueue = exactCleanupFamilyQueue(root);
        File retained = new File(familyQueue, ".retained");
        File[] actions = retained.listFiles((dir, name) -> name.startsWith("action-"));
        assertFalse(artifact.exists());
        assertEquals(1, actions == null ? 0 : actions.length);
        assertEquals("keyed-exact", readUtf8(actions[0]));
        assertFalse(treeContainsNamedFile(familyQueue, "00000000000000000000.ticket"));
        File state = latestCleanupStateFile(new File(new File(
                DeconvolutionIO.cacheDir(root), ".migration"),
                DeconvolutionIO.DEFERRED_CLEANUP_DIRECTORY),
                sha256ForTest(root.getCanonicalPath()));
        String stableState = readUtf8(state);
        int stableCount = countFilesRecursively(familyQueue);
        for (int access = 0; access < 100; access++) {
            DeconvolutionIO.scavengeExactCleanupForTest(root);
        }
        assertEquals(stableState, readUtf8(state));
        assertEquals(stableCount, countFilesRecursively(familyQueue));
        assertEquals("keyed-exact", readUtf8(actions[0]));
    }

    @Test
    public void exactActionRewriteAfterFinalClassificationIsNeverUnlinked() throws Exception {
        final File root = temp.newFolder("exact-final-rewrite");
        final File artifact = new File(root, "rewrite.bin");
        final byte[] original = "original".getBytes(StandardCharsets.UTF_8);
        final byte[] changed = "changed!".getBytes(StandardCharsets.UTF_8);
        Files.write(artifact.toPath(), original);
        final AtomicInteger rewrites = new AtomicInteger();
        DeconvolutionIO.setStableFileIdentityOverrideForTest(Boolean.TRUE);
        DeconvolutionIO.setFinalExactClassificationHookForTest(
                new DeconvolutionIO.FinalExactClassificationTestHook() {
                    @Override
                    public void afterFinalValidation(File action) throws java.io.IOException {
                        if (rewrites.compareAndSet(0, 1)) Files.write(action.toPath(), changed);
                    }
                });

        DeconvolutionIO.deleteIfExactForTest(artifact, original.length,
                hexDigest(original));

        File retained = new File(exactCleanupFamilyQueue(root), ".retained");
        File[] actions = retained.listFiles((dir, name) -> name.startsWith("action-"));
        assertEquals(1, rewrites.get());
        assertEquals(1, actions == null ? 0 : actions.length);
        assertEquals("changed!", readUtf8(actions[0]));
    }

    @Test
    public void exactActionReplacementAfterFinalClassificationPreservesBothInodes()
            throws Exception {
        final File root = temp.newFolder("exact-final-replacement");
        final File artifact = new File(root, "replacement.bin");
        final byte[] original = "original".getBytes(StandardCharsets.UTF_8);
        final byte[] replacement = "replaced".getBytes(StandardCharsets.UTF_8);
        Files.write(artifact.toPath(), original);
        final AtomicReference<File> parked = new AtomicReference<File>();
        DeconvolutionIO.setStableFileIdentityOverrideForTest(Boolean.TRUE);
        DeconvolutionIO.setFinalExactClassificationHookForTest(
                new DeconvolutionIO.FinalExactClassificationTestHook() {
                    @Override
                    public void afterFinalValidation(File action) throws java.io.IOException {
                        File originalAction = new File(action.getParentFile(),
                                "classified-original.bin");
                        Files.move(action.toPath(), originalAction.toPath());
                        Files.write(action.toPath(), replacement);
                        parked.set(originalAction);
                    }
                });

        DeconvolutionIO.deleteIfExactForTest(artifact, original.length,
                hexDigest(original));

        File retained = new File(exactCleanupFamilyQueue(root), ".retained");
        File[] actions = retained.listFiles((dir, name) -> name.startsWith("action-"));
        assertTrue(parked.get() != null && parked.get().isFile());
        assertEquals("original", readUtf8(parked.get()));
        assertEquals(1, actions == null ? 0 : actions.length);
        assertEquals("replaced", readUtf8(actions[0]));
    }

    @Test
    public void opaqueTransactionRetriesKeepOneStableConfinedSourceWithoutChurn()
            throws Exception {
        File root = temp.newFolder("stable-opaque-source");
        DeconvolutionIO.ArtifactIdentity identity = identityForRecovery("c8", 2970L, 98);
        File cache = DeconvolutionIO.cacheDir(root);
        File migration = new File(cache, ".migration");
        Files.createDirectories(migration.toPath());
        File transaction = new File(migration, identity.familyLockToken() + "-opaque");
        writeUtf8(new File(transaction, "unknown/nested.bin"), "opaque");
        int stableEntries = migration.listFiles() == null ? 0 : migration.listFiles().length;
        for (int attempt = 0; attempt < 100; attempt++) {
            try {
                DeconvolutionIO.retainOpaqueTransactionForTest(root, transaction,
                        identity.familyLockToken());
                fail("Java 8 cannot conditionally rename the source child");
            } catch (DeconvolutionIO.RetryableCleanupException expected) {
                assertTrue(expected.getMessage().contains("conditionally rename"));
            }
            assertEquals("opaque", readUtf8(new File(transaction, "unknown/nested.bin")));
            assertEquals(stableEntries,
                    migration.listFiles() == null ? 0 : migration.listFiles().length);
        }
        assertFalse(new File(cache, ".migration-retained").exists());
    }

    @Test
    public void keylessOpaqueRetentionLeavesTransactionAtConfinedSourceName() throws Exception {
        File root = temp.newFolder("keyless-opaque-retention");
        DeconvolutionIO.ArtifactIdentity identity = identityForRecovery("ca", 2972L, 100);
        File migration = new File(DeconvolutionIO.cacheDir(root), ".migration");
        File transaction = new File(migration, identity.familyLockToken() + "-keyless");
        writeUtf8(new File(transaction, "unknown.bin"), "must-remain");
        DeconvolutionIO.setStableFileIdentityOverrideForTest(Boolean.FALSE);

        try {
            DeconvolutionIO.retainOpaqueTransactionForTest(root, transaction,
                    identity.familyLockToken());
            fail("keyless retained ancestry must fail closed");
        } catch (DeconvolutionIO.RetryableCleanupException expected) {
            assertTrue(expected.getMessage().contains("conditionally rename"));
        }

        assertTrue(transaction.isDirectory());
        assertEquals("must-remain", readUtf8(new File(transaction, "unknown.bin")));
        File family = new File(new File(DeconvolutionIO.cacheDir(root),
                ".migration-retained"), identity.familyLockToken());
        assertEquals(0, family.listFiles() == null ? 0 : family.listFiles().length);
    }

    @Test
    public void opaqueRetentionNeverMovesThroughPostValidationDestinationSwaps() throws Exception {
        assumeTrue("junction regression is Windows-specific", File.separatorChar == '\\');
        for (int level = 0; level < 2; level++) {
            final int swapLevel = level;
            final File root = temp.newFolder("opaque-anchor-swap-" + level);
            final DeconvolutionIO.ArtifactIdentity identity = identityForRecovery(
                    level == 0 ? "cb" : "cc", 2973L + level, 101 + level);
            final File migration = new File(DeconvolutionIO.cacheDir(root), ".migration");
            final File transaction = new File(migration,
                    identity.familyLockToken() + "-anchor-swap");
            writeUtf8(new File(transaction, "unknown.bin"), "transaction");
            final File retainedRoot = new File(DeconvolutionIO.cacheDir(root),
                    ".migration-retained");
            final File retainedFamily = new File(retainedRoot, identity.familyLockToken());
            final File outside = temp.newFolder("opaque-anchor-outside-" + level);
            final File sentinel = new File(outside, "must-survive.txt");
            writeUtf8(sentinel, "outside");
            final AtomicReference<File> swapPoint = new AtomicReference<File>();
            DeconvolutionIO.setOpaqueRetentionMoveHookForTest(
                    new DeconvolutionIO.OpaqueRetentionMoveTestHook() {
                        @Override
                        public void afterValidationBeforeRetention(
                                File rootDirectory, File familyDirectory)
                                throws java.io.IOException {
                            File selected = swapLevel == 0 ? rootDirectory : familyDirectory;
                            try {
                                Files.createDirectories(selected.getParentFile().toPath());
                                if (!createJunction(selected, outside)) {
                                    throw new java.io.IOException(
                                            "could not replace retained anchor with junction");
                                }
                                swapPoint.set(selected);
                            } catch (java.io.IOException failure) {
                                throw failure;
                            } catch (Exception failure) {
                                throw new java.io.IOException(failure);
                            }
                        }
                    });
            try {
                DeconvolutionIO.retainOpaqueTransactionForTest(root, transaction,
                        identity.familyLockToken());
                fail("opaque transaction must remain at source");
            } catch (DeconvolutionIO.RetryableCleanupException expected) {
                assertTrue(expected.getMessage().contains("conditionally rename"));
            } finally {
                DeconvolutionIO.setOpaqueRetentionMoveHookForTest(null);
                if (swapPoint.get() != null) {
                    Files.deleteIfExists(swapPoint.get().toPath());
                }
            }
            assertEquals("outside", readUtf8(sentinel));
            assertTrue(transaction.isDirectory());
            assertEquals("transaction", readUtf8(new File(transaction, "unknown.bin")));
            assertEquals(0, outside.listFiles((dir, name) -> name.startsWith("transaction-")).length);
        }
    }

    @Test
    public void opaqueRetentionNeverMovesAPostValidationSourceReplacement() throws Exception {
        File root = temp.newFolder("opaque-source-replacement");
        DeconvolutionIO.ArtifactIdentity identity = identityForRecovery("cf", 2977L, 105);
        final File migration = new File(DeconvolutionIO.cacheDir(root), ".migration");
        final File transaction = new File(migration,
                identity.familyLockToken() + "-source-replacement");
        writeUtf8(new File(transaction, "original.bin"), "original");
        final File parked = new File(migration, transaction.getName() + ".parked");
        final AtomicInteger swaps = new AtomicInteger();
        DeconvolutionIO.setOpaqueRetentionMoveHookForTest(
                new DeconvolutionIO.OpaqueRetentionMoveTestHook() {
                    @Override
                    public void afterValidationBeforeRetention(
                            File ignoredRoot, File ignoredFamily)
                            throws java.io.IOException {
                        if (!swaps.compareAndSet(0, 1)) return;
                        Files.move(transaction.toPath(), parked.toPath());
                        Files.createDirectory(transaction.toPath());
                        Files.write(new File(transaction, "replacement.bin").toPath(),
                                "replacement".getBytes(StandardCharsets.UTF_8));
                    }
                });

        try {
            DeconvolutionIO.retainOpaqueTransactionForTest(root, transaction,
                    identity.familyLockToken());
            fail("source replacement must not be moved");
        } catch (DeconvolutionIO.RetryableCleanupException expected) {
            assertTrue(expected.getMessage().contains("conditionally rename"));
        }

        assertEquals(1, swaps.get());
        assertEquals("original", readUtf8(new File(parked, "original.bin")));
        assertEquals("replacement", readUtf8(new File(transaction, "replacement.bin")));
        assertFalse(new File(DeconvolutionIO.cacheDir(root), ".migration-retained").exists());
    }

    @Test
    public void keylessTreeTicketStaysAtStableHeadWithoutMutationOrChurn() throws Exception {
        File root = temp.newFolder("keyless-tree-no-churn");
        DeconvolutionIO.ArtifactIdentity identity = identityForRecovery("c9", 2971L, 99);
        File payload = DeconvolutionIO.enqueueDeferredCleanupForTest(root, identity, "tree");
        writeUtf8(new File(payload, "nested/unknown.bin"), "opaque-tree");
        File familyQueue = cleanupFamilyQueue(root, identity);
        File retained = new File(familyQueue, ".retained");
        File migration = new File(DeconvolutionIO.cacheDir(root), ".migration");
        int migrationEntries = migration.listFiles() == null ? 0 : migration.listFiles().length;
        final AtomicInteger traversals = new AtomicInteger();
        DeconvolutionIO.setStableFileIdentityOverrideForTest(Boolean.FALSE);
        DeconvolutionIO.setCleanupTraversalHookForTest(
                new DeconvolutionIO.CleanupTraversalTestHook() {
                    @Override
                    public void beforeTraversal(File directory) {
                        traversals.incrementAndGet();
                    }
                });

        DeconvolutionIO.scavengeDeferredCleanupForTest(root, identity);

        assertTrue(payload.exists());
        assertEquals(0, retained.listFiles() == null ? 0 : retained.listFiles().length);
        assertEquals(0, traversals.get());
        assertTrue(treeContainsNamedFile(familyQueue, "00000000000000000000.ticket"));
        File stableState = latestCleanupStateFile(new File(migration,
                DeconvolutionIO.DEFERRED_CLEANUP_DIRECTORY), identity.familyLockToken());
        String stableStateContent = readUtf8(stableState);
        int stableFileCount = countFilesRecursively(familyQueue);

        for (int access = 0; access < 100; access++) {
            DeconvolutionIO.scavengeDeferredCleanupForTest(root, identity);
        }

        assertEquals(stableStateContent, readUtf8(stableState));
        assertEquals(stableFileCount, countFilesRecursively(familyQueue));
        assertTrue(payload.exists());
        assertTrue(treeContainsUtf8(payload, "opaque-tree"));
        assertEquals(migrationEntries,
                migration.listFiles() == null ? 0 : migration.listFiles().length);
        assertEquals(0, traversals.get());
    }

    @Test
    public void failedTreeRetentionMoveKeepsStableHeadUntilLaterSuccess() throws Exception {
        for (int mode = 0; mode < 2; mode++) {
            File root = temp.newFolder("tree-retain-move-failure-" + mode);
            DeconvolutionIO.ArtifactIdentity identity = identityForRecovery(
                    mode == 0 ? "cd" : "ce", 2975L + mode, 103 + mode);
            final File payload = DeconvolutionIO.enqueueDeferredCleanupForTest(root, identity,
                    "tree-failure");
            writeUtf8(new File(payload, "nested/unknown.bin"), "tracked-tree-" + mode);
            final File familyQueue = cleanupFamilyQueue(root, identity);
            final File cleanupRoot = new File(new File(DeconvolutionIO.cacheDir(root),
                    ".migration"), DeconvolutionIO.DEFERRED_CLEANUP_DIRECTORY);
            final File ticket = new File(new File(familyQueue, ".tickets"),
                    "00000000000000000000.ticket");
            File stateBefore = latestCleanupStateFile(cleanupRoot, identity.familyLockToken());
            String stateContentBefore = readUtf8(stateBefore);
            int fileCountBefore = countFilesRecursively(familyQueue);
            final AtomicInteger attempts = new AtomicInteger();
            final int failureMode = mode;
            DeconvolutionIO.setTreeRetentionMoveHookForTest(
                    new DeconvolutionIO.TreeRetentionMoveTestHook() {
                        @Override
                        public void beforeMove(File source, File destination)
                                throws java.io.IOException {
                            attempts.incrementAndGet();
                            if (failureMode == 0) {
                                throw new java.nio.file.AtomicMoveNotSupportedException(
                                        source.getPath(), destination.getPath(), "injected");
                            }
                            throw new java.io.IOException("injected retained move I/O failure");
                        }
                    });

            DeconvolutionIO.scavengeDeferredCleanupForTest(root, identity);

            assertEquals(1, attempts.get());
            assertTrue(payload.isDirectory());
            assertTrue(ticket.isFile());
            assertEquals(stateContentBefore, readUtf8(stateBefore));
            assertEquals(fileCountBefore, countFilesRecursively(familyQueue));

            DeconvolutionIO.setTreeRetentionMoveHookForTest(null);
            DeconvolutionIO.scavengeDeferredCleanupForTest(root, identity);

            File retained = new File(familyQueue, ".retained");
            File[] trees = retained.listFiles((dir, name) -> name.startsWith("tree-"));
            assertFalse(payload.exists());
            assertFalse(ticket.exists());
            assertEquals(1, trees == null ? 0 : trees.length);
            assertTrue(treeContainsUtf8(trees[0], "tracked-tree-" + mode));
            File stableState = latestCleanupStateFile(cleanupRoot, identity.familyLockToken());
            String stableContent = readUtf8(stableState);
            int stableCount = countFilesRecursively(familyQueue);
            for (int access = 0; access < 100; access++) {
                DeconvolutionIO.scavengeDeferredCleanupForTest(root, identity);
            }
            assertEquals(stableContent, readUtf8(stableState));
            assertEquals(stableCount, countFilesRecursively(familyQueue));
            assertTrue(treeContainsUtf8(trees[0], "tracked-tree-" + mode));
        }
    }

    @Test
    public void retainedTreeForcesDestinationBeforeSourceAndRetriesSameTicket()
            throws Exception {
        File root = temp.newFolder("tree-retain-barrier-order");
        DeconvolutionIO.ArtifactIdentity identity = identityForRecovery("d0", 2978L, 106);
        File payload = DeconvolutionIO.enqueueDeferredCleanupForTest(root, identity, "tree");
        writeUtf8(new File(payload, "opaque.bin"), "opaque");
        final File familyQueue = cleanupFamilyQueue(root, identity);
        final File retained = new File(familyQueue, ".retained");
        final File payloads = new File(familyQueue, ".payloads");
        final File ticket = new File(new File(familyQueue, ".tickets"),
                "00000000000000000000.ticket");
        final List<String> forced = new ArrayList<String>();
        final AtomicInteger sourceFailures = new AtomicInteger();
        DeconvolutionIO.setDirectoryForceHookForTest(
                new DeconvolutionIO.DirectoryForceTestHook() {
                    @Override
                    public boolean force(File directory) throws java.io.IOException {
                        forced.add(directory.getCanonicalPath());
                        return !directory.getCanonicalFile().equals(payloads.getCanonicalFile())
                                || sourceFailures.getAndIncrement() > 0;
                    }
                });

        DeconvolutionIO.scavengeDeferredCleanupForTest(root, identity);

        assertFalse(payload.exists());
        assertTrue(ticket.isFile());
        File[] trees = retained.listFiles((dir, name) -> name.startsWith("tree-"));
        assertEquals(1, trees == null ? 0 : trees.length);
        assertTrue(indexOfPathAfter(forced, retained, 0)
                < indexOfPathAfter(forced, payloads, 0));
        int stableCount = countFilesRecursively(familyQueue);

        DeconvolutionIO.setDirectoryForceHookForTest(null);
        DeconvolutionIO.scavengeDeferredCleanupForTest(root, identity);

        assertFalse(ticket.exists());
        assertTrue(countFilesRecursively(familyQueue) <= stableCount);
        assertEquals(1, retained.listFiles((dir, name) -> name.startsWith("tree-")).length);
        assertEquals("opaque", readUtf8(new File(trees[0], "opaque.bin")));
    }

    @Test
    public void exactRetainedActionUsesStableNameAndRetriesFailedSourceBarrier()
            throws Exception {
        File root = temp.newFolder("exact-retain-barrier-order");
        File artifact = new File(root, "exact.bin");
        byte[] bytes = "exact-retained".getBytes(StandardCharsets.UTF_8);
        Files.write(artifact.toPath(), bytes);
        final File familyQueue = exactCleanupFamilyQueue(root);
        final File retained = new File(familyQueue, ".retained");
        final File payloads = new File(familyQueue, ".payloads");
        final List<String> forced = new ArrayList<String>();
        final AtomicInteger payloadForces = new AtomicInteger();
        DeconvolutionIO.setDirectoryForceHookForTest(
                new DeconvolutionIO.DirectoryForceTestHook() {
                    @Override
                    public boolean force(File directory) throws java.io.IOException {
                        forced.add(directory.getCanonicalPath());
                        if (!directory.getCanonicalFile().equals(payloads.getCanonicalFile())) {
                            return true;
                        }
                        // First payload force binds the live source. Fail the later retained-move
                        // source barrier so the exact head ticket must remain stable.
                        return payloadForces.incrementAndGet() != 2;
                    }
                });

        DeconvolutionIO.deleteIfExactForTest(artifact, bytes.length, hexDigest(bytes));

        File tickets = new File(familyQueue, ".tickets");
        File ticket = new File(tickets, "00000000000000000000.ticket");
        File[] actions = retained.listFiles((dir, name) -> name.startsWith("action-"));
        assertFalse(artifact.exists());
        assertTrue(ticket.isFile());
        assertEquals(1, actions == null ? 0 : actions.length);
        int retainedBarrier = indexOfPathAfter(forced, retained, 0);
        assertTrue(retainedBarrier
                < indexOfPathAfter(forced, payloads, retainedBarrier + 1));
        String stableName = actions[0].getName();

        DeconvolutionIO.setDirectoryForceHookForTest(null);
        DeconvolutionIO.scavengeExactCleanupForTest(root);

        assertFalse(ticket.exists());
        actions = retained.listFiles((dir, name) -> name.startsWith("action-"));
        assertEquals(1, actions == null ? 0 : actions.length);
        assertEquals(stableName, actions[0].getName());
        assertEquals("exact-retained", readUtf8(actions[0]));
    }

    @Test
    public void cleanupAnchorRejectsProjectCacheAndMigrationJunctionReplacement()
            throws Exception {
        assumeTrue("junction regression is Windows-specific", File.separatorChar == '\\');
        for (int level = 0; level < 3; level++) {
            final File root = temp.newFolder("anchor-swap-project-" + level);
            final DeconvolutionIO.ArtifactIdentity current = identityForRecovery(
                    level == 0 ? "b1" : level == 1 ? "b2" : "b3",
                    2940L + level, 90 + level);
            final File migration = new File(DeconvolutionIO.cacheDir(root), ".migration");
            final File candidate = DeconvolutionIO.enqueueDeferredCleanupForTest(root, current,
                    "anchor-victim");
            writeUtf8(new File(candidate, "inside.txt"), "inside");
            final File swapPoint = level == 0 ? root
                    : level == 1 ? DeconvolutionIO.cacheDir(root) : migration;
            final File parked = new File(swapPoint.getParentFile(),
                    swapPoint.getName() + ".parked-" + level);
            final File outside = temp.newFolder("anchor-swap-outside-" + level);
            String relative = swapPoint.toPath().toAbsolutePath().normalize().relativize(
                    candidate.toPath().toAbsolutePath().normalize()).toString();
            final File outsideCandidate = new File(outside, relative);
            final File sentinel = new File(outsideCandidate, "must-survive.txt");
            writeUtf8(sentinel, "outside");
            final AtomicInteger swaps = new AtomicInteger();
            DeconvolutionIO.setCleanupTraversalHookForTest(
                    new DeconvolutionIO.CleanupTraversalTestHook() {
                        @Override
                        public void beforeTraversal(File ignored) throws java.io.IOException {
                            if (!swaps.compareAndSet(0, 1)) return;
                            Files.move(swapPoint.toPath(), parked.toPath());
                            try {
                                if (!createJunction(swapPoint, outside)) {
                                    throw new java.io.IOException("could not replace anchor with junction");
                                }
                            } catch (java.io.IOException e) {
                                throw e;
                            } catch (Exception e) {
                                throw new java.io.IOException(e);
                            }
                        }
                    });
            try {
                DeconvolutionIO.scavengeDeferredCleanupForTest(root, current);
                assertEquals("opaque TREE payloads are never traversed", 0, swaps.get());
                assertEquals("outside", readUtf8(sentinel));
            } finally {
                DeconvolutionIO.setCleanupTraversalHookForTest(null);
                if (swaps.get() != 0) {
                    Files.deleteIfExists(swapPoint.toPath());
                    Files.move(parked.toPath(), swapPoint.toPath());
                }
            }
        }
    }

    @Test
    public void cleanupQueueDirectorySwapsAfterOpenNeverTouchOutsideNames() throws Exception {
        assumeTrue("junction regression is Windows-specific", File.separatorChar == '\\');
        final String[] names = {".tickets", ".payloads", ".retained"};
        final String[] operations = {"read-ticket", "read-tree-source",
                "read-tree-destination"};
        for (int level = 0; level < names.length; level++) {
            final int selectedLevel = level;
            File root = temp.newFolder("queue-subdir-swap-" + level);
            final DeconvolutionIO.ArtifactIdentity identity = identityForRecovery(
                    level == 0 ? "d1" : level == 1 ? "d2" : "d3",
                    2979L + level, 107 + level);
            final File payload = DeconvolutionIO.enqueueDeferredCleanupForTest(root, identity,
                    "queue-swap");
            writeUtf8(new File(payload, "inside.bin"), "inside");
            final File familyQueue = cleanupFamilyQueue(root, identity);
            final File selected = new File(familyQueue, names[level]);
            final File parked = new File(familyQueue, names[level] + ".parked");
            final File outside = temp.newFolder("queue-subdir-outside-" + level);
            final File sentinel = new File(outside, "must-survive.txt");
            writeUtf8(sentinel, "outside");
            if (level == 0) {
                writeUtf8(new File(outside, "00000000000000000000.ticket"),
                        "outside-ticket");
            } else if (level == 1) {
                Files.createDirectory(new File(outside, payload.getName()).toPath());
                writeUtf8(new File(new File(outside, payload.getName()), "outside.bin"),
                        "outside-payload");
            }
            final AtomicInteger swaps = new AtomicInteger();
            DeconvolutionIO.setQueueDirectoryHookForTest(
                    new DeconvolutionIO.QueueDirectoryTestHook() {
                        @Override
                        public void beforeOperation(String operation, File directory)
                                throws java.io.IOException {
                            if (!operations[selectedLevel].equals(operation)
                                    || !directory.equals(selected)
                                    || !swaps.compareAndSet(0, 1)) return;
                            try {
                                Files.move(selected.toPath(), parked.toPath());
                                if (!createJunction(selected, outside)) {
                                    throw new java.io.IOException("could not swap queue directory");
                                }
                            } catch (java.io.IOException failure) {
                                throw failure;
                            } catch (Exception failure) {
                                throw new java.io.IOException(failure);
                            }
                        }
                    });

            try {
                DeconvolutionIO.scavengeDeferredCleanupForTest(root, identity);
                assertEquals(1, swaps.get());
                assertEquals("outside", readUtf8(sentinel));
                if (level == 0) {
                    assertEquals("outside-ticket", readUtf8(new File(outside,
                            "00000000000000000000.ticket")));
                } else if (level == 1) {
                    assertEquals("outside-payload", readUtf8(new File(new File(outside,
                            payload.getName()), "outside.bin")));
                }
            } finally {
                DeconvolutionIO.setQueueDirectoryHookForTest(null);
                if (Files.exists(selected.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                    Files.delete(selected.toPath());
                }
                if (parked.exists()) Files.move(parked.toPath(), selected.toPath());
            }
            assertTrue(payload.exists());
            assertTrue(new File(new File(familyQueue, ".tickets"),
                    "00000000000000000000.ticket").isFile());
        }
    }

    @Test
    public void transientQueueReadIoKeepsTicketStateAndPayloadForRetry() throws Exception {
        File root = temp.newFolder("queue-read-io-retry");
        final DeconvolutionIO.ArtifactIdentity identity = identityForRecovery("d4", 2982L, 110);
        File payload = DeconvolutionIO.enqueueDeferredCleanupForTest(root, identity, "io-retry");
        writeUtf8(new File(payload, "opaque.bin"), "opaque");
        final File familyQueue = cleanupFamilyQueue(root, identity);
        final File tickets = new File(familyQueue, ".tickets");
        File ticket = new File(tickets, "00000000000000000000.ticket");
        File state = latestCleanupStateFile(new File(new File(DeconvolutionIO.cacheDir(root),
                ".migration"), DeconvolutionIO.DEFERRED_CLEANUP_DIRECTORY),
                identity.familyLockToken());
        String stateBefore = readUtf8(state);
        final AtomicInteger failures = new AtomicInteger();
        DeconvolutionIO.setQueueDirectoryHookForTest(
                new DeconvolutionIO.QueueDirectoryTestHook() {
                    @Override
                    public void beforeOperation(String operation, File directory)
                            throws java.io.IOException {
                        if ("read-ticket".equals(operation) && directory.equals(tickets)
                                && failures.compareAndSet(0, 1)) {
                            throw new java.io.IOException("injected transient ticket read I/O");
                        }
                    }
                });

        DeconvolutionIO.scavengeDeferredCleanupForTest(root, identity);

        assertEquals(1, failures.get());
        assertTrue(ticket.isFile());
        assertTrue(payload.isDirectory());
        assertEquals(stateBefore, readUtf8(state));

        DeconvolutionIO.setQueueDirectoryHookForTest(null);
        DeconvolutionIO.scavengeDeferredCleanupForTest(root, identity);
        assertFalse(ticket.exists());
        assertFalse(payload.exists());
        assertTrue(treeContainsUtf8(new File(familyQueue, ".retained"), "opaque"));
    }

    @Test
    public void deferredCleanupCursorPreventsPersistentPoisonStarvation() throws Exception {
        File root = temp.newFolder("persistent-cleanup-fairness");
        final DeconvolutionIO.ArtifactIdentity current = identityForRecovery("a3", 2910L, 82);
        File cleanupRoot = new File(new File(DeconvolutionIO.cacheDir(root), ".migration"),
                DeconvolutionIO.DEFERRED_CLEANUP_DIRECTORY);
        for (int i = 0; i < DeconvolutionIO.MAX_DEFERRED_CLEANUP_PER_ACCESS + 5; i++) {
            File poison = DeconvolutionIO.enqueueDeferredCleanupForTest(root, current,
                    "poison-" + i);
            writeUtf8(new File(poison, "POISON"), "retry forever");
        }
        final File valid = DeconvolutionIO.enqueueDeferredCleanupForTest(root, current, "valid");
        final AtomicInteger examinedThisAccess = new AtomicInteger();
        DeconvolutionIO.setCleanupTraversalHookForTest(
                new DeconvolutionIO.CleanupTraversalTestHook() {
                    @Override
                    public void beforeTraversal(File directory) throws java.io.IOException {
                        examinedThisAccess.incrementAndGet();
                        if (new File(directory, "POISON").isFile()) {
                            throw new java.io.IOException("persistent poison");
                        }
                    }
                });

        if (!hasStableFileKey(root)) {
            DeconvolutionIO.scavengeDeferredCleanupForTest(root, current);
            assertTrue(valid.exists());
            assertEquals(0, examinedThisAccess.get());
            return;
        }

        for (int access = 0; access < 40 && valid.exists(); access++) {
            examinedThisAccess.set(0);
            DeconvolutionIO.scavengeDeferredCleanupForTest(root, current);
            assertTrue("each access must keep a strict examination/deletion cap",
                    examinedThisAccess.get() <= DeconvolutionIO.MAX_DEFERRED_CLEANUP_PER_ACCESS);
        }
        assertFalse("fixed poison must not starve a later valid cleanup entry", valid.exists());

        File afterCorruption = DeconvolutionIO.enqueueDeferredCleanupForTest(root, current,
                "valid-after-corrupt-state");
        File latestState = latestCleanupStateFile(cleanupRoot, current.familyLockToken());
        writeUtf8(latestState, "corrupt\n");
        for (int access = 0; access < 40 && afterCorruption.exists(); access++) {
            examinedThisAccess.set(0);
            DeconvolutionIO.scavengeDeferredCleanupForTest(root, current);
            assertTrue(examinedThisAccess.get()
                    <= DeconvolutionIO.MAX_DEFERRED_CLEANUP_PER_ACCESS);
        }
        assertFalse("one corrupt persisted generation must fail open for cleanup",
                afterCorruption.exists());
    }

    @Test
    public void unknownFlatCleanupResidueIsIgnoredAndNeverBecomesAuthority()
            throws Exception {
        File root = temp.newFolder("legacy-cleanup-routing");
        final DeconvolutionIO.ArtifactIdentity current = identityForRecovery("b4", 2950L, 94);
        File migration = new File(DeconvolutionIO.cacheDir(root), ".migration");
        File cleanupRoot = new File(migration, DeconvolutionIO.DEFERRED_CLEANUP_DIRECTORY);
        final File routed = new File(cleanupRoot, current.familyLockToken() + "-legacy");
        final File unroutable = new File(cleanupRoot, "old-layout-without-family");
        writeUtf8(new File(routed, "POISON"), "routed");
        writeUtf8(new File(unroutable, "POISON"), "global");
        DeconvolutionIO.scavengeDeferredCleanupForTest(root, current);

        assertFalse(new File(new File(cleanupRoot, ".queue"), ".legacy-poison").exists());
        assertEquals("routed", readUtf8(new File(routed, "POISON")));
        assertEquals("global", readUtf8(new File(unroutable, "POISON")));
        assertEquals(0, countFamilyQuarantines(root, current));
    }

    @Test
    public void malformedNestedPayloadTicketAdvancesWithoutTouchingUnknownPayload()
            throws Exception {
        File root = temp.newFolder("malformed-direct-cleanup-ticket");
        DeconvolutionIO.ArtifactIdentity current = identityForRecovery("b5", 2951L, 95);
        File firstPayload = DeconvolutionIO.enqueueDeferredCleanupForTest(root, current, "first");
        File laterPayload = DeconvolutionIO.enqueueDeferredCleanupForTest(root, current, "later");
        File unknown = new File(firstPayload, "nested/unknown.bin");
        writeUtf8(unknown, "must-remain");
        File familyQueue = cleanupFamilyQueue(root, current);
        File firstTicket = new File(new File(familyQueue, ".tickets"),
                "00000000000000000000.ticket");
        File nested = new File(firstPayload, "nested");
        String records = "family|" + current.familyLockToken() + "\npayload|"
                + Base64.getUrlEncoder().withoutPadding().encodeToString(
                        nested.getAbsolutePath().getBytes(StandardCharsets.UTF_8))
                + "\nkind|TREE\nsize|-\nhash|-\n";
        writeUtf8(firstTicket, "deconv-cleanup-ticket-v2\n" + records
                + "checksum|" + sha256ForTest(records) + "\n");

        DeconvolutionIO.scavengeDeferredCleanupForTest(root, current);

        assertFalse("malformed metadata only must be discarded", firstTicket.exists());
        assertEquals("must-remain", readUtf8(unknown));
        assertTrue("a later ticket must still be considered", cleanupStateCounter(
                latestCleanupStateFile(new File(new File(DeconvolutionIO.cacheDir(root),
                        ".migration"), DeconvolutionIO.DEFERRED_CLEANUP_DIRECTORY),
                        current.familyLockToken()), "head") >= 1L);
        assertFalse("the later direct TREE payload is rebound once", laterPayload.exists());
    }

    @Test
    public void invalidCleanupStateBoundsFailClosedWithoutReusingTicketIds() throws Exception {
        File root = temp.newFolder("bounded-cleanup-state");
        DeconvolutionIO.ArtifactIdentity current = identityForRecovery("b6", 2952L, 96);
        File payload = DeconvolutionIO.enqueueDeferredCleanupForTest(root, current, "payload");
        File familyQueue = cleanupFamilyQueue(root, current);
        String records = "generation|9223372036854775806\nhead|0\n"
                + "tail|9223372036854775807\n";
        String invalid = "deconv-cleanup-state-v1\n" + records
                + "checksum|" + sha256ForTest(records) + "\n";
        writeUtf8(new File(familyQueue, ".state-0"), invalid);
        writeUtf8(new File(familyQueue, ".state-1"), invalid);

        DeconvolutionIO.scavengeDeferredCleanupForTest(root, current);
        assertTrue(payload.exists());
        try {
            DeconvolutionIO.enqueueDeferredCleanupForTest(root, current, "must-not-reuse");
            fail("invalid queue bounds must fail closed");
        } catch (java.io.IOException expected) {
            assertTrue(expected.getMessage().contains("No valid bounded"));
        }
        assertEquals(1, countDeferredCleanupTransactions(root, current));
    }

    @Test
    public void exactCleanupCrashBeforeBindingRetainsTicketAndConvergesOnRetry()
            throws Exception {
        final File file = temp.newFile("exact-cleanup-crash.bin");
        final byte[] bytes = "journalled-exact".getBytes(StandardCharsets.UTF_8);
        Files.write(file.toPath(), bytes);
        final AtomicInteger crashes = new AtomicInteger();
        DeconvolutionIO.setDeleteBindingHookForTest(
                new DeconvolutionIO.DeleteBindingTestHook() {
                    @Override
                    public void beforeAtomicBinding(File ignored) throws java.io.IOException {
                        if (crashes.compareAndSet(0, 1)) {
                            throw new java.io.IOException("crash before atomic bind");
                        }
                    }
                });
        try {
            DeconvolutionIO.deleteIfExactForTest(file, bytes.length, hexDigest(bytes));
            fail("injected binding crash must escape");
        } catch (java.io.IOException expected) {
            assertTrue(expected.getMessage().contains("crash before atomic bind"));
        }
        assertEquals("journalled-exact", readUtf8(file));
        assertTrue(treeContainsNamedFile(DeconvolutionIO.cacheDir(file.getParentFile()),
                "00000000000000000000.ticket"));

        DeconvolutionIO.setDeleteBindingHookForTest(null);
        DeconvolutionIO.deleteIfExactForTest(file, bytes.length, hexDigest(bytes));
        DeconvolutionIO.scavengeExactCleanupForTest(file.getParentFile());
        assertFalse(file.exists());
        assertTrue(treeContainsUtf8(DeconvolutionIO.cacheDir(file.getParentFile()),
                "journalled-exact"));
    }

    @Test
    public void restartDiscoversMarkedPreAuthorityStagingAsCleanupOnly() throws Exception {
        assumeTrue("prior Windows v3 migration is Windows-specific", File.separatorChar == '\\');
        File root = temp.newFolder("v3-pre-authority-restart-cleanup");
        DeconvolutionIO.ArtifactIdentity current = identityForRecovery("d6", 2630L, 55);
        DeconvolutionIO.ArtifactIdentity prior = current.priorWindowsV3Identity();
        writePriorChannelFamily(root, prior, 0, "OLD", "prior-restart-cleanup");
        File abandoned = manualRecoveryTransaction(root, current, "marked-cleanup");
        writeUtf8(new File(abandoned, DeconvolutionIO.DEFERRED_CLEANUP_MARKER),
                DeconvolutionIO.DEFERRED_CLEANUP_MARKER_CONTENT);
        writeUtf8(new File(abandoned, "partial-stage.bin"), "not authority");

        try (DeconvolutionFamilyLock.Handle ignored =
                     DeconvolutionIO.lockFamilyForAccess(root, current)) {
            assertEquals("prior-restart-cleanup",
                    readUtf8(DeconvolutionIO.deconvFile(root, current, 0)));
        }

        assertEquals(0, countFamilyTransactions(root, current));
        if (hasStableFileKey(root)) {
            assertEquals(0, countDeferredCleanupTransactions(root, current));
        } else {
            assertTrue(countDeferredCleanupTransactions(root, current) >= 1);
        }
        assertEquals(0, countFamilyQuarantines(root, current));
    }

    @Test
    public void cleanupMarkerCannotOverrideAJournalAuthorityAttempt() throws Exception {
        assumeTrue("prior Windows v3 migration is Windows-specific", File.separatorChar == '\\');
        File root = temp.newFolder("v3-marker-with-journal");
        DeconvolutionIO.ArtifactIdentity current = identityForRecovery("d8", 2635L, 57);
        DeconvolutionIO.ArtifactIdentity prior = current.priorWindowsV3Identity();
        writePriorChannelFamily(root, prior, 0, "OLD", "prior-marker-journal");
        File transaction = manualRecoveryTransaction(root, current, "marker-journal");
        writeUtf8(new File(transaction, DeconvolutionIO.DEFERRED_CLEANUP_MARKER),
                DeconvolutionIO.DEFERRED_CLEANUP_MARKER_CONTENT);
        writeUtf8(new File(transaction, "recovery.journal"),
                "deconv-migration-v99\nunknown\n");

        try (DeconvolutionFamilyLock.Handle ignored =
                     DeconvolutionIO.lockFamilyForAccess(root, current)) {
            assertEquals("prior-marker-journal",
                    readUtf8(DeconvolutionIO.deconvFile(root, prior, 0)));
        }

        assertEquals(0, countFamilyTransactions(root, current));
        assertEquals(1, countFamilyQuarantines(root, current));
    }

    @Test
    public void linkedCleanupNamespaceIsNeverFollowedAndCannotBlockMarkedRetry()
            throws Exception {
        assumeTrue("junction regression is Windows-specific", File.separatorChar == '\\');
        final File root = temp.newFolder("v3-linked-cleanup-namespace");
        File outside = temp.newFolder("v3-linked-cleanup-outside");
        File outsideFile = new File(outside, "must-remain.txt");
        writeUtf8(outsideFile, "outside");
        final DeconvolutionIO.ArtifactIdentity current = identityForRecovery("d7", 2640L, 56);
        DeconvolutionIO.ArtifactIdentity prior = current.priorWindowsV3Identity();
        writePriorChannelFamily(root, prior, 0, "OLD", "prior-linked-cleanup");
        File migrationRoot = new File(DeconvolutionIO.cacheDir(root), ".migration");
        Files.createDirectories(migrationRoot.toPath());
        File cleanupLink = new File(migrationRoot,
                DeconvolutionIO.DEFERRED_CLEANUP_DIRECTORY);
        assumeTrue(createJunction(cleanupLink, outside));
        try {
            DeconvolutionIO.MigrationResult failed =
                    DeconvolutionIO.migratePriorWindowsV3FamilyForTest(root, current,
                            new DeconvolutionIO.MigrationFaultInjector() {
                                @Override
                                public void checkpoint(DeconvolutionIO.MigrationFaultPoint point)
                                        throws java.io.IOException {
                                    if (point == DeconvolutionIO.MigrationFaultPoint
                                            .BEFORE_STAGED_MANIFEST_WRITE
                                            || point == DeconvolutionIO.MigrationFaultPoint
                                            .BEFORE_UNCOMMITTED_STAGING_DELETE) {
                                        throw new java.io.IOException("injected " + point);
                                    }
                                }
                            });

            assertFalse(failed.migrated);
            assertTrue(failed.safe);
            assertEquals(1, countFamilyTransactions(root, current));
            assertEquals("outside", readUtf8(outsideFile));

            try (DeconvolutionFamilyLock.Handle ignored =
                         DeconvolutionIO.lockFamilyForAccess(root, current)) {
                assertEquals("prior-linked-cleanup",
                        readUtf8(DeconvolutionIO.deconvFile(root, current, 0)));
            }
            // The unsafe cleanup namespace prevents the required relocate-before-delete step.
            // The durable marked source remains non-authoritative and does not block access.
            assertEquals(1, countFamilyTransactions(root, current));
            assertEquals(0, countFamilyQuarantines(root, current));
            assertEquals("outside", readUtf8(outsideFile));
        } finally {
            Files.deleteIfExists(cleanupLink.toPath());
        }
        try (DeconvolutionFamilyLock.Handle ignored =
                     DeconvolutionIO.lockFamilyForAccess(root, current)) {
            assertEquals("prior-linked-cleanup",
                    readUtf8(DeconvolutionIO.deconvFile(root, current, 0)));
        }
        assertEquals(0, countFamilyTransactions(root, current));
    }

    @Test
    public void journalRenameIsNotAuthorityUntilItsDirectoryBarrierCompletes() throws Exception {
        assumeTrue("prior Windows v3 migration is Windows-specific", File.separatorChar == '\\');
        final File root = temp.newFolder("v3-journal-directory-force");
        final DeconvolutionIO.ArtifactIdentity current = identityForRecovery("b1", 2650L, 31);
        final DeconvolutionIO.ArtifactIdentity prior = current.priorWindowsV3Identity();
        writePriorChannelFamily(root, prior, 0, "OLD", "prior-channel");

        DeconvolutionIO.MigrationResult failed =
                DeconvolutionIO.migratePriorWindowsV3FamilyForTest(root, current,
                        new DeconvolutionIO.MigrationFaultInjector() {
                            @Override
                            public void checkpoint(DeconvolutionIO.MigrationFaultPoint point)
                                    throws java.io.IOException {
                                if (point != DeconvolutionIO.MigrationFaultPoint
                                        .AFTER_JOURNAL_RENAME_BEFORE_DIRECTORY_FORCE) return;
                                assertFalse(DeconvolutionIO.manifestFile(root, current).exists());
                                assertFalse(DeconvolutionIO.deconvFile(root, current, 0).exists());
                                assertTrue(DeconvolutionIO.manifestFile(root, prior).isFile());
                                throw new java.io.IOException("directory force fault");
                            }
                        });

        assertFalse(failed.migrated);
        assertTrue(failed.safe);
        assertEquals("prior-channel", readUtf8(DeconvolutionIO.deconvFile(root, prior, 0)));
    }

    @Test
    public void currentDurabilityBarrierPrecedesAnyPriorRetirement() throws Exception {
        assumeTrue("prior Windows v3 migration is Windows-specific", File.separatorChar == '\\');
        final File root = temp.newFolder("v3-current-before-retire");
        final DeconvolutionIO.ArtifactIdentity current = identityForRecovery("b2", 2660L, 32);
        final DeconvolutionIO.ArtifactIdentity prior = current.priorWindowsV3Identity();
        writePriorChannelFamily(root, prior, 0, "OLD", "prior-channel");

        DeconvolutionIO.MigrationResult failed =
                DeconvolutionIO.migratePriorWindowsV3FamilyForTest(root, current,
                        new DeconvolutionIO.MigrationFaultInjector() {
                            @Override
                            public void checkpoint(DeconvolutionIO.MigrationFaultPoint point)
                                    throws java.io.IOException {
                                if (point != DeconvolutionIO.MigrationFaultPoint
                                        .AFTER_CURRENT_GENERATION_DURABLE) return;
                                assertTrue(DeconvolutionIO.manifestFile(root, prior).isFile());
                                assertTrue(DeconvolutionIO.deconvFile(root, prior, 0).isFile());
                                throw new java.io.IOException("pre-retirement fault");
                            }
                        });

        assertFalse(failed.migrated);
        assertTrue(failed.safe);
        assertTrue(DeconvolutionIO.manifestFile(root, prior).isFile());
    }

    @Test
    public void cleanupFaultLeavesAuthoritativeJournalForRetry() throws Exception {
        assumeTrue("prior Windows v3 migration is Windows-specific", File.separatorChar == '\\');
        File root = temp.newFolder("v3-journal-last-cleanup");
        DeconvolutionIO.ArtifactIdentity current = identityForRecovery("b3", 2670L, 33);
        writePriorChannelFamily(root, current.priorWindowsV3Identity(), 0,
                "OLD", "prior-channel");

        DeconvolutionIO.MigrationResult failed =
                DeconvolutionIO.migratePriorWindowsV3FamilyForTest(root, current,
                        failMigrationAt(DeconvolutionIO.MigrationFaultPoint.BEFORE_JOURNAL_CLEANUP));
        File transaction = firstFamilyTransaction(root, current);
        assertFalse(failed.migrated);
        assertTrue(failed.safe);
        assertTrue(transaction != null);
        assertTrue(new File(transaction, "recovery.journal").isFile());

        DeconvolutionIO.MigrationResult retry =
                DeconvolutionIO.migratePriorWindowsV3FamilyForTest(root, current, null);
        assertFalse(retry.migrated);
        assertEquals(0, countFamilyTransactions(root, current));
    }

    @Test
    public void unsupportedDirectoryForcePreservesPriorAndDoesNotRepromote() throws Exception {
        assumeTrue("prior Windows v3 migration is Windows-specific", File.separatorChar == '\\');
        DeconvolutionIO.setDirectoryDurabilityOverrideForTest(Boolean.FALSE);
        File root = temp.newFolder("v3-directory-force-fallback");
        DeconvolutionIO.ArtifactIdentity current = identityForRecovery("b4", 2680L, 34);
        DeconvolutionIO.ArtifactIdentity prior = current.priorWindowsV3Identity();
        writePriorChannelFamily(root, prior, 0, "OLD", "prior-channel");
        writeUtf8(DeconvolutionIO.deconvFile(root, current, 0), "stale-baseline");

        DeconvolutionIO.MigrationResult migrated =
                DeconvolutionIO.migratePriorWindowsV3FamilyForTest(root, current, null);
        assertTrue(migrated.migrated);
        assertTrue(DeconvolutionIO.manifestFile(root, current).isFile());
        assertTrue("prior is the crash fallback when directory fsync is unavailable",
                DeconvolutionIO.manifestFile(root, prior).isFile());
        File authoritative = firstFamilyTransaction(root, current);
        assertTrue(authoritative != null);
        assertTrue(new File(authoritative, "recovery.journal").isFile());

        // Simulate a crash that retains the manifest rename but loses/reverts a pixel rename.
        Files.copy(new File(authoritative, "backup/0.bin").toPath(),
                DeconvolutionIO.deconvFile(root, current, 0).toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);

        DeconvolutionIO.MigrationResult second =
                DeconvolutionIO.migratePriorWindowsV3FamilyForTest(root, current, null);
        assertFalse(second.migrated);
        assertEquals("prior-channel", readUtf8(DeconvolutionIO.deconvFile(root, current, 0)));
        assertEquals(1, countFamilyTransactions(root, current));
        assertEquals(authoritative.getCanonicalFile(),
                firstFamilyTransaction(root, current).getCanonicalFile());
        assertTrue(DeconvolutionIO.manifestFile(root, prior).isFile());

        // A later capable provider may finalize this exact authority, then and only then clean it.
        DeconvolutionIO.setDirectoryDurabilityOverrideForTest(Boolean.TRUE);
        DeconvolutionIO.MigrationResult finalized =
                DeconvolutionIO.migratePriorWindowsV3FamilyForTest(root, current, null);
        assertFalse(finalized.migrated);
        assertEquals(0, countFamilyTransactions(root, current));
        assertFalse(DeconvolutionIO.manifestFile(root, prior).exists());
    }

    @Test
    public void capableMigrationForcesNewDirectoryAncestryBottomUpBeforeCleanup() throws Exception {
        assumeTrue("prior Windows v3 migration is Windows-specific", File.separatorChar == '\\');
        final File root = temp.newFolder("v3-directory-ancestry");
        final DeconvolutionIO.ArtifactIdentity current = identityForRecovery("b5", 2690L, 35);
        DeconvolutionIO.ArtifactIdentity prior = current.priorWindowsV3Identity();
        writePriorChannelFamily(root, prior, 0, "OLD", "prior-channel");
        final File priorOptionalCache = writeCacheVariant(root, "NESTED", prior,
                "deep/branch/value.bin", "nested");
        final List<String> forced = new ArrayList<String>();
        DeconvolutionIO.setDirectoryForceHookForTest(
                new DeconvolutionIO.DirectoryForceTestHook() {
                    @Override
                    public boolean force(File directory) throws java.io.IOException {
                        forced.add(directory.getCanonicalPath());
                        return true;
                    }
                });

        DeconvolutionIO.MigrationResult result =
                DeconvolutionIO.migratePriorWindowsV3FamilyForTest(root, current,
                        new DeconvolutionIO.MigrationFaultInjector() {
                            @Override
                            public void checkpoint(DeconvolutionIO.MigrationFaultPoint point)
                                    throws java.io.IOException {
                                if (point != DeconvolutionIO.MigrationFaultPoint
                                        .AFTER_CURRENT_GENERATION_DURABLE) return;
                                assertTrue(DeconvolutionIO.manifestFile(root, prior).isFile());
                                File transaction = firstFamilyTransaction(root, current);
                                assertTrue(transaction != null);
                                int desired = indexOfPathAfter(forced,
                                        new File(transaction, "desired"), 0);
                                int transactionIndex = indexOfPathAfter(forced, transaction,
                                        desired + 1);
                                int migration = indexOfPathAfter(forced,
                                        transaction.getParentFile(), transactionIndex + 1);
                                indexOfPathAfter(forced, DeconvolutionIO.cacheDir(root), migration + 1);

                                assertTrue(priorOptionalCache.isFile());
                                assertFalse(new File(new File(
                                        DeconvolutionIO.cacheParamsDir(root, "NESTED"),
                                        current.artifactKey), "deep/branch/value.bin").exists());
                            }
                        });

        assertTrue(result.migrated);
        assertEquals(0, countFamilyTransactions(root, current));
    }

    @Test
    public void desiredManifestMutationAfterJournalQuarantinesAndPreservesPrior() throws Exception {
        assumeTrue("prior Windows v3 migration is Windows-specific", File.separatorChar == '\\');
        final File root = temp.newFolder("v3-mutated-desired-manifest");
        final DeconvolutionIO.ArtifactIdentity current = identityForRecovery("a1", 2700L, 17);
        final DeconvolutionIO.ArtifactIdentity prior = current.priorWindowsV3Identity();
        writePriorChannelFamily(root, prior, 0, "OLD", "prior-channel");

        DeconvolutionIO.MigrationResult result = mutateTransactionAt(root, current,
                DeconvolutionIO.MigrationFaultPoint.AFTER_JOURNAL,
                "desired-manifest.json", "forged desired manifest");

        assertFalse(result.migrated);
        assertTrue(result.failure != null);
        assertEquals("prior-channel", readUtf8(DeconvolutionIO.deconvFile(root, prior, 0)));
        assertFalse(DeconvolutionIO.manifestFile(root, current).exists());
        assertEquals(1, countFamilyQuarantines(root, current));
    }

    @Test
    public void pixelBackupMutationAfterJournalNeverRestoresOrDeletesIt() throws Exception {
        assumeTrue("prior Windows v3 migration is Windows-specific", File.separatorChar == '\\');
        final File root = temp.newFolder("v3-mutated-pixel-backup");
        final DeconvolutionIO.ArtifactIdentity current = identityForRecovery("a2", 2800L, 18);
        final DeconvolutionIO.ArtifactIdentity prior = current.priorWindowsV3Identity();
        writePriorChannelFamily(root, prior, 0, "OLD", "prior-channel");
        writeUtf8(DeconvolutionIO.deconvFile(root, current, 0), "current-baseline");

        DeconvolutionIO.MigrationResult result = mutateTransactionAt(root, current,
                DeconvolutionIO.MigrationFaultPoint.AFTER_JOURNAL,
                "backup/0.bin", "forged pixel backup");

        assertFalse(result.migrated);
        assertEquals("current-baseline", readUtf8(DeconvolutionIO.deconvFile(root, current, 0)));
        assertEquals("prior-channel", readUtf8(DeconvolutionIO.deconvFile(root, prior, 0)));
        assertEquals(1, countFamilyQuarantines(root, current));
    }

    @Test
    public void manifestBackupMutationAfterJournalNeverRestoresIt() throws Exception {
        assumeTrue("prior Windows v3 migration is Windows-specific", File.separatorChar == '\\');
        final File root = temp.newFolder("v3-mutated-manifest-backup");
        final DeconvolutionIO.ArtifactIdentity current = identityForRecovery("a3", 2900L, 19);
        final DeconvolutionIO.ArtifactIdentity prior = current.priorWindowsV3Identity();
        writePriorChannelFamily(root, prior, 0, "OLD", "prior-channel");
        writeUtf8(DeconvolutionIO.manifestFile(root, current), "current baseline manifest");

        DeconvolutionIO.MigrationResult result = mutateTransactionAt(root, current,
                DeconvolutionIO.MigrationFaultPoint.AFTER_JOURNAL,
                "manifest-backup.json", "forged manifest backup");

        assertFalse(result.migrated);
        assertEquals("current baseline manifest", readUtf8(
                DeconvolutionIO.manifestFile(root, current)));
        assertEquals("prior-channel", readUtf8(DeconvolutionIO.deconvFile(root, prior, 0)));
        assertEquals(1, countFamilyQuarantines(root, current));
    }

    @Test
    public void publishTargetMutationIsQuarantinedWithoutDeletingUnknownBytes() throws Exception {
        assumeTrue("prior Windows v3 migration is Windows-specific", File.separatorChar == '\\');
        final File root = temp.newFolder("v3-mutated-publish-target");
        final DeconvolutionIO.ArtifactIdentity current = identityForRecovery("a4", 3000L, 20);
        final DeconvolutionIO.ArtifactIdentity prior = current.priorWindowsV3Identity();
        writePriorChannelFamily(root, prior, 0, "OLD", "prior-channel");

        DeconvolutionIO.MigrationResult result =
                DeconvolutionIO.migratePriorWindowsV3FamilyForTest(root, current,
                        new DeconvolutionIO.MigrationFaultInjector() {
                            @Override
                            public void checkpoint(DeconvolutionIO.MigrationFaultPoint point)
                                    throws java.io.IOException {
                                if (point != DeconvolutionIO.MigrationFaultPoint
                                        .AFTER_FIRST_CURRENT_PUBLISH) return;
                                try {
                                    writeUtf8(DeconvolutionIO.deconvFile(root, current, 0),
                                            "unknown external target bytes");
                                } catch (Exception failure) {
                                    throw new java.io.IOException(failure);
                                }
                            }
                        });

        assertFalse(result.migrated);
        assertEquals("unknown external target bytes", readUtf8(
                DeconvolutionIO.deconvFile(root, current, 0)));
        assertEquals("prior-channel", readUtf8(DeconvolutionIO.deconvFile(root, prior, 0)));
        assertEquals(1, countFamilyQuarantines(root, current));
    }

    @Test
    public void cacheJunctionAndCycleAreNeverTraversedOrRetired() throws Exception {
        assumeTrue("junction regression is Windows-specific", File.separatorChar == '\\');
        File root = temp.newFolder("v3-junction-containment");
        File outside = temp.newFolder("v3-junction-outside");
        File outsideFile = new File(outside, "must-remain.txt");
        writeUtf8(outsideFile, "outside");
        String contentHash = repeat("8f", 32);
        DeconvolutionIO.ArtifactIdentity current = new DeconvolutionIO.ArtifactIdentity(
                DeconvolutionIO.ArtifactIdentity.VERSION, 2000L, contentHash,
                "project:Input/Container.LIF", 10, "Region");
        DeconvolutionIO.ArtifactIdentity prior = current.priorWindowsV3Identity();
        writePriorChannelFamily(root, prior, 0, "OLD", "prior-channel");
        File priorCacheDir = DeconvolutionIO.cacheFile(root, "OLD", prior, 0).getParentFile();
        File outsideLink = new File(priorCacheDir, "outside-link");
        File cycleLink = new File(priorCacheDir, "cycle-link");
        boolean outsideCreated = createJunction(outsideLink, outside);
        boolean cycleCreated = outsideCreated && createJunction(cycleLink, priorCacheDir);
        if (!outsideCreated || !cycleCreated) {
            Files.deleteIfExists(cycleLink.toPath());
            Files.deleteIfExists(outsideLink.toPath());
        }
        assumeTrue(outsideCreated && cycleCreated);
        try {
            DeconvolutionIO.MigrationResult result =
                    DeconvolutionIO.migratePriorWindowsV3FamilyForTest(root, current, null);

            assertTrue(result.migrated);
            assertEquals("outside", readUtf8(outsideFile));
            assertTrue(outsideLink.exists());
            assertTrue(cycleLink.exists());
            assertEquals("prior-channel", readUtf8(DeconvolutionIO.deconvFile(root, current, 0)));
        } finally {
            Files.deleteIfExists(cycleLink.toPath());
            Files.deleteIfExists(outsideLink.toPath());
        }
    }

    @Test
    public void ordinaryWriterWaitsForMigrationFamilyTransaction() throws Exception {
        assumeTrue("prior Windows v3 migration is Windows-specific", File.separatorChar == '\\');
        File root = temp.newFolder("v3-concurrent-writer");
        String contentHash = repeat("4b", 32);
        final DeconvolutionIO.ArtifactIdentity current = new DeconvolutionIO.ArtifactIdentity(
                DeconvolutionIO.ArtifactIdentity.VERSION, 1600L, contentHash,
                "project:Input/Container.LIF", 6, "Region");
        DeconvolutionIO.ArtifactIdentity prior = current.priorWindowsV3Identity();
        writePriorChannelFamily(root, prior, 0, "OLD", "prior-channel");

        final CountDownLatch staged = new CountDownLatch(1);
        final CountDownLatch releaseMigration = new CountDownLatch(1);
        final CountDownLatch writerAttempted = new CountDownLatch(1);
        final CountDownLatch writerAcquired = new CountDownLatch(1);
        final AtomicReference<Throwable> threadFailure = new AtomicReference<Throwable>();
        final AtomicReference<DeconvolutionIO.MigrationResult> migrationResult =
                new AtomicReference<DeconvolutionIO.MigrationResult>();
        Thread migration = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    migrationResult.set(DeconvolutionIO.migratePriorWindowsV3FamilyForTest(root, current,
                            new DeconvolutionIO.MigrationFaultInjector() {
                                @Override
                                public void checkpoint(DeconvolutionIO.MigrationFaultPoint point)
                                        throws java.io.IOException {
                                    if (point != DeconvolutionIO.MigrationFaultPoint.AFTER_STAGE) return;
                                    staged.countDown();
                                    try {
                                        if (!releaseMigration.await(5, TimeUnit.SECONDS)) {
                                            throw new java.io.IOException("writer serialization timeout");
                                        }
                                    } catch (InterruptedException e) {
                                        Thread.currentThread().interrupt();
                                        throw new java.io.IOException(e);
                                    }
                                }
                            }));
                } catch (Throwable failure) {
                    threadFailure.set(failure);
                }
            }
        }, "deconv-migration-test");
        Thread writer = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    writerAttempted.countDown();
                    try (DeconvolutionFamilyLock.Handle ignored =
                                 DeconvolutionFamilyLock.acquire(root, current)) {
                        writerAcquired.countDown();
                        writePriorChannelFamily(root, current, 0, "WRITER", "writer-channel");
                    }
                } catch (Throwable failure) {
                    threadFailure.set(failure);
                }
            }
        }, "deconv-writer-test");

        migration.start();
        assertTrue(staged.await(5, TimeUnit.SECONDS));
        writer.start();
        assertTrue(writerAttempted.await(5, TimeUnit.SECONDS));
        assertFalse("ordinary publication must wait for the migration family lock",
                writerAcquired.await(250, TimeUnit.MILLISECONDS));
        releaseMigration.countDown();
        migration.join(5000L);
        writer.join(5000L);

        assertFalse(migration.isAlive());
        assertFalse(writer.isAlive());
        assertEquals(null, threadFailure.get());
        assertTrue(migrationResult.get().migrated);
        assertEquals("writer-channel", readUtf8(DeconvolutionIO.deconvFile(root, current, 0)));
        assertEquals("WRITER", DeconvManifest.load(
                DeconvolutionIO.manifestFile(root, current)).channel(0).paramsHash);
    }

    @Test
    public void unicodeFoldCollisionsNeverBecomePriorV3Candidates() throws Exception {
        File root = temp.newFolder("unicode-v3-collision");
        String contentHash = repeat("e5", 32);
        DeconvolutionIO.ArtifactIdentity dottedCapital = new DeconvolutionIO.ArtifactIdentity(
                DeconvolutionIO.ArtifactIdentity.VERSION, 1000L, contentHash,
                "project:input/\u0130mage.lif", 5, "Region");
        DeconvolutionIO.ArtifactIdentity combiningDot = new DeconvolutionIO.ArtifactIdentity(
                DeconvolutionIO.ArtifactIdentity.VERSION, 1000L, contentHash,
                "project:input/i\u0307mage.lif", 5, "Region");
        DeconvolutionIO.ArtifactIdentity composed = new DeconvolutionIO.ArtifactIdentity(
                DeconvolutionIO.ArtifactIdentity.VERSION, 1000L, contentHash,
                "project:input/Caf\u00e9.lif", 5, "Region");
        assertEquals(null, dottedCapital.priorWindowsV3Identity());
        assertEquals(null, combiningDot.priorWindowsV3Identity());
        assertEquals(null, composed.priorWindowsV3Identity());

        writePriorChannelFamily(root, combiningDot, 0, "COLLISION", "wrong-source");
        List<File> candidates = DeconvolutionIO.deconvFileReadCandidates(root, dottedCapital,
                0, "Region", DeconvolutionIO.LegacyBasenamePolicy.REJECT, 0);

        assertEquals(1, candidates.size());
        assertEquals(DeconvolutionIO.deconvFile(root, dottedCapital, 0), candidates.get(0));
        assertEquals(null, DeconvolutionIO.firstExistingFile(candidates));
        assertTrue("the unrelated colliding artifact must remain untouched",
                DeconvolutionIO.deconvFile(root, combiningDot, 0).isFile());
    }

    @Test
    public void v2LegacyAndV3ContainerIdentitiesAreDeterministicAndNeverConfused()
            throws Exception {
        File project = temp.newFolder("identity-versions");
        File source = new File(project, "input/container.lif");
        Files.createDirectories(source.getParentFile().toPath());
        Files.write(source.toPath(), "versioned".getBytes(StandardCharsets.UTF_8));
        DeconvManifest.SourceFingerprint fingerprint = DeconvManifest.SourceFingerprint.of(source);
        DeconvolutionIO.ArtifactIdentity legacy =
                DeconvolutionIO.ArtifactIdentity.of(fingerprint, 0, "Region");
        DeconvolutionIO.ArtifactIdentity current =
                DeconvolutionIO.ArtifactIdentity.of(project, source, fingerprint, 0, "Region");

        assertEquals(DeconvolutionIO.ArtifactIdentity.LEGACY_VERSION, legacy.version);
        assertEquals(DeconvolutionIO.ArtifactIdentity.VERSION, current.version);
        assertTrue(legacy.artifactKey.startsWith("dcv2-"));
        assertTrue(current.artifactKey.startsWith("dcv3-"));
        assertTrue(DeconvolutionIO.isArtifactKey(legacy.artifactKey));
        assertTrue(DeconvolutionIO.isArtifactKey(current.artifactKey));
        assertNotEquals(legacy, current);
        assertEquals(fingerprint.contentHash, current.verifiedSourceContentHash);
        DeconvolutionIO.ArtifactIdentity reconstructed =
                new DeconvolutionIO.ArtifactIdentity(current.version, current.sourceSize,
                        current.sourceContentHash, current.sourceSeriesIndex,
                        current.displaySuffix);
        assertEquals("existing manifest fields must reconstruct the complete v3 identity",
                current, reconstructed);
        assertTrue("v3 identity must survive the existing manifest codec",
                DeconvManifest.fromJson(DeconvManifest.forArtifact(current).toJson())
                        .matchesArtifact(current));

        File legacyQualified = DeconvolutionIO.deconvFile(project, legacy, 0);
        Files.createDirectories(legacyQualified.getParentFile().toPath());
        Files.write(legacyQualified.toPath(), "legacy".getBytes(StandardCharsets.UTF_8));
        List<File> currentCandidates = DeconvolutionIO.deconvFileReadCandidates(project,
                current, 0, "Region", DeconvolutionIO.LegacyBasenamePolicy.MIGRATE_IF_UNIQUE, 1);
        assertFalse("a v3 reader must not reuse an unrelated v2 qualified artifact",
                currentCandidates.contains(legacyQualified));
    }

    @Test
    public void unboundOrMalformedV3IdentityIsRejectedBeforePathResolution() {
        DeconvolutionIO.ArtifactIdentity unbound = new DeconvolutionIO.ArtifactIdentity(
                DeconvolutionIO.ArtifactIdentity.VERSION, 5L, repeat("ab", 32), 0, "Region");
        assertFalse(unbound.isPublishable());
        try {
            DeconvolutionIO.deconvFile(temp.getRoot(), unbound, 0);
            fail("expected unbound v3 identity rejection");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("source/container"));
        }
        try {
            new DeconvolutionIO.ArtifactIdentity(DeconvolutionIO.ArtifactIdentity.VERSION,
                    5L, repeat("ab", 32), "", 0, "Region");
            fail("expected blank source discriminator rejection");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("source discriminator"));
        }
    }

    @Test
    public void migrationSwapAfterAnchorCaptureNeverEnumeratesOutsideTransactions()
            throws Exception {
        assumeTrue("junction regression is Windows-specific", File.separatorChar == '\\');
        final File root = temp.newFolder("recovery-migration-anchor-swap");
        final DeconvolutionIO.ArtifactIdentity current = identityForRecovery("e1", 3410L, 71);
        writePriorChannelFamily(root, current.priorWindowsV3Identity(), 0,
                "OLD", "prior-anchor-swap");
        manualRecoveryTransaction(root, current, "local");
        final File migration = new File(DeconvolutionIO.cacheDir(root), ".migration");
        final File parked = new File(migration.getParentFile(), ".migration-v63-parked");
        final File outside = temp.newFolder("recovery-migration-anchor-outside");
        File external = new File(outside, current.familyLockToken() + "-external");
        writeUtf8(new File(external, "outside.txt"), "outside-sentinel");
        final AtomicInteger swaps = new AtomicInteger();
        DeconvolutionIO.setRecoveryDirectoryHookForTest(
                new DeconvolutionIO.RecoveryDirectoryTestHook() {
                    @Override
                    public void beforeOperation(String operation, File directory)
                            throws java.io.IOException {
                        if (!"enumerate-migration".equals(operation)
                                || !swaps.compareAndSet(0, 1)) return;
                        Files.move(migration.toPath(), parked.toPath());
                        try {
                            if (!createJunction(migration, outside)) {
                                throw new java.io.IOException(
                                        "could not create migration junction");
                            }
                        } catch (java.io.IOException failure) {
                            throw failure;
                        } catch (Exception failure) {
                            throw new java.io.IOException(failure);
                        }
                    }
                });
        try {
            try (DeconvolutionFamilyLock.Handle ignored =
                         DeconvolutionIO.lockFamilyForAccess(root, current)) {
                fail("changed migration anchor must fail closed");
            } catch (java.io.IOException expected) {
                assertTrue(expected.getMessage().contains("trust anchor")
                        || expected.getMessage().contains("changed"));
            }
        } finally {
            DeconvolutionIO.setRecoveryDirectoryHookForTest(null);
            if (swaps.get() != 0) {
                Files.deleteIfExists(migration.toPath());
                Files.move(parked.toPath(), migration.toPath());
            }
        }
        assertEquals(1, swaps.get());
        assertEquals("outside-sentinel", readUtf8(new File(external, "outside.txt")));
        assertTrue(firstFamilyTransaction(root, current).isDirectory());
    }

    @Test
    public void transactionSwapBeforeJournalReadRetainsSourceAndOutsideSentinel()
            throws Exception {
        assumeTrue("junction regression is Windows-specific", File.separatorChar == '\\');
        final File root = temp.newFolder("recovery-transaction-read-swap");
        final DeconvolutionIO.ArtifactIdentity current = identityForRecovery("e2", 3420L, 72);
        writePriorChannelFamily(root, current.priorWindowsV3Identity(), 0,
                "OLD", "prior-read-swap");
        final File transaction = manualRecoveryTransaction(root, current, "read-swap");
        writeUtf8(new File(transaction, "recovery.journal"), "deconv-migration-v99\n");
        final File parked = new File(transaction.getParentFile(),
                transaction.getName() + ".v63-parked");
        final File outside = temp.newFolder("recovery-transaction-read-outside");
        writeUtf8(new File(outside, "outside.txt"), "outside-sentinel");
        final AtomicInteger swaps = new AtomicInteger();
        DeconvolutionIO.setRecoveryDirectoryHookForTest(
                new DeconvolutionIO.RecoveryDirectoryTestHook() {
                    @Override
                    public void beforeOperation(String operation, File directory)
                            throws java.io.IOException {
                        if (!"read-transaction".equals(operation)
                                || !directory.getAbsoluteFile().equals(transaction.getAbsoluteFile())
                                || !swaps.compareAndSet(0, 1)) return;
                        Files.move(transaction.toPath(), parked.toPath());
                        try {
                            if (!createJunction(transaction, outside)) {
                                throw new java.io.IOException(
                                        "could not create transaction junction");
                            }
                        } catch (java.io.IOException failure) {
                            throw failure;
                        } catch (Exception failure) {
                            throw new java.io.IOException(failure);
                        }
                    }
                });
        try {
            try (DeconvolutionFamilyLock.Handle ignored =
                         DeconvolutionIO.lockFamilyForAccess(root, current)) {
                fail("changed transaction identity must fail closed");
            } catch (java.io.IOException expected) {
                assertTrue(expected.getMessage().contains("changed"));
            }
        } finally {
            DeconvolutionIO.setRecoveryDirectoryHookForTest(null);
            if (swaps.get() != 0) {
                Files.deleteIfExists(transaction.toPath());
                Files.move(parked.toPath(), transaction.toPath());
            }
        }
        assertEquals(1, swaps.get());
        assertEquals("outside-sentinel", readUtf8(new File(outside, "outside.txt")));
        assertTrue(new File(transaction, "recovery.journal").isFile());
    }

    @Test
    public void quarantineSourceSwapIsRetainedInsteadOfMovingOutsideBytes()
            throws Exception {
        assumeTrue("junction regression is Windows-specific", File.separatorChar == '\\');
        final File root = temp.newFolder("recovery-quarantine-swap");
        final DeconvolutionIO.ArtifactIdentity current = identityForRecovery("e3", 3430L, 73);
        writePriorChannelFamily(root, current.priorWindowsV3Identity(), 0,
                "OLD", "prior-quarantine-swap");
        final File transaction = manualRecoveryTransaction(root, current, "quarantine-swap");
        writeUtf8(new File(transaction, "recovery.journal"), "deconv-migration-v99\n");
        final File parked = new File(transaction.getParentFile(),
                transaction.getName() + ".v63-parked");
        final File outside = temp.newFolder("recovery-quarantine-outside");
        writeUtf8(new File(outside, "outside.txt"), "outside-sentinel");
        final AtomicInteger swaps = new AtomicInteger();
        DeconvolutionIO.setRecoveryDirectoryHookForTest(
                new DeconvolutionIO.RecoveryDirectoryTestHook() {
                    @Override
                    public void beforeOperation(String operation, File directory)
                            throws java.io.IOException {
                        if (!"quarantine-malformed".equals(operation)
                                || !swaps.compareAndSet(0, 1)) return;
                        Files.move(transaction.toPath(), parked.toPath());
                        try {
                            if (!createJunction(transaction, outside)) {
                                throw new java.io.IOException(
                                        "could not create quarantine junction");
                            }
                        } catch (java.io.IOException failure) {
                            throw failure;
                        } catch (Exception failure) {
                            throw new java.io.IOException(failure);
                        }
                    }
                });
        try {
            try (DeconvolutionFamilyLock.Handle ignored =
                         DeconvolutionIO.lockFamilyForAccess(root, current)) {
                fail("changed quarantine source must fail closed");
            } catch (java.io.IOException expected) {
                assertTrue(expected.getMessage().contains("changed"));
            }
        } finally {
            DeconvolutionIO.setRecoveryDirectoryHookForTest(null);
            if (swaps.get() != 0) {
                Files.deleteIfExists(transaction.toPath());
                Files.move(parked.toPath(), transaction.toPath());
            }
        }
        assertEquals(1, swaps.get());
        assertEquals("outside-sentinel", readUtf8(new File(outside, "outside.txt")));
        assertTrue(new File(transaction, "recovery.journal").isFile());
        assertEquals(0, countFamilyQuarantines(root, current));
    }

    @Test
    public void recoveredCleanupDeletesJournalThenTransactionLast() throws Exception {
        assumeTrue("prior Windows v3 migration is Windows-specific", File.separatorChar == '\\');
        File root = temp.newFolder("recovery-journal-root-order");
        final DeconvolutionIO.ArtifactIdentity current = identityForRecovery("e4", 3440L, 74);
        writePriorChannelFamily(root, current.priorWindowsV3Identity(), 0,
                "OLD", "prior-cleanup-order");
        DeconvolutionIO.MigrationResult interrupted =
                DeconvolutionIO.migratePriorWindowsV3FamilyForTest(root, current,
                        failMigrationAt(DeconvolutionIO.MigrationFaultPoint
                                .BEFORE_JOURNAL_CLEANUP));
        final File transaction = firstFamilyTransaction(root, current);
        assertFalse(interrupted.migrated);
        assertTrue(transaction != null);
        final List<String> operations = new ArrayList<String>();
        DeconvolutionIO.setRecoveryDirectoryHookForTest(
                new DeconvolutionIO.RecoveryDirectoryTestHook() {
                    @Override
                    public void beforeOperation(String operation, File directory) {
                        if (operation.startsWith("delete-")) {
                            operations.add(operation + ":" + directory.getName());
                        }
                    }
                });

        DeconvolutionIO.MigrationResult retry =
                DeconvolutionIO.migratePriorWindowsV3FamilyForTest(root, current, null);

        assertFalse(retry.migrated);
        int journal = operations.indexOf("delete-file:recovery.journal");
        int transactionDelete = operations.indexOf("delete-directory:" + transaction.getName());
        assertTrue("journal deletion must be observed", journal >= 0);
        assertTrue("transaction deletion must follow its journal",
                transactionDelete > journal);
        assertEquals(0, countFamilyTransactions(root, current));
    }

    @Test
    public void siblingInsertedAtJournalBarrierRetainsJournalForRetry() throws Exception {
        assumeTrue("prior Windows v3 migration is Windows-specific", File.separatorChar == '\\');
        File root = temp.newFolder("recovery-journal-late-sibling");
        final DeconvolutionIO.ArtifactIdentity current = identityForRecovery("e5", 3450L, 75);
        writePriorChannelFamily(root, current.priorWindowsV3Identity(), 0,
                "OLD", "prior-journal-sibling");
        DeconvolutionIO.MigrationResult interrupted =
                DeconvolutionIO.migratePriorWindowsV3FamilyForTest(root, current,
                        failMigrationAt(DeconvolutionIO.MigrationFaultPoint
                                .BEFORE_JOURNAL_CLEANUP));
        final File transaction = firstFamilyTransaction(root, current);
        assertFalse(interrupted.migrated);
        assertTrue(transaction != null);
        final File inserted = new File(transaction, "late-sibling.bin");
        final AtomicInteger insertions = new AtomicInteger();
        DeconvolutionIO.setRecoveryDirectoryHookForTest(
                new DeconvolutionIO.RecoveryDirectoryTestHook() {
                    @Override
                    public void beforeOperation(String operation, File directory)
                            throws java.io.IOException {
                        if ("delete-file".equals(operation)
                                && "recovery.journal".equals(directory.getName())
                                && insertions.compareAndSet(0, 1)) {
                            Files.write(inserted.toPath(),
                                    "late".getBytes(StandardCharsets.UTF_8));
                        }
                    }
                });

        DeconvolutionIO.MigrationResult retry =
                DeconvolutionIO.migratePriorWindowsV3FamilyForTest(root, current, null);

        assertFalse(retry.migrated);
        assertEquals(1, insertions.get());
        assertTrue("journal authority must survive a late sibling",
                new File(transaction, "recovery.journal").isFile());
        assertTrue(inserted.isFile());
        assertEquals(1, countFamilyTransactions(root, current));
    }

    @Test
    public void plainTransactionReplacementBeforeCleanupIsNeverDeleted() throws Exception {
        assumeTrue("prior Windows v3 migration is Windows-specific", File.separatorChar == '\\');
        final File root = temp.newFolder("recovery-plain-transaction-swap");
        final DeconvolutionIO.ArtifactIdentity current = identityForRecovery("e6", 3460L, 76);
        writePriorChannelFamily(root, current.priorWindowsV3Identity(), 0,
                "OLD", "prior-plain-swap");
        final AtomicReference<File> original = new AtomicReference<File>();
        final AtomicReference<File> parked = new AtomicReference<File>();
        final AtomicInteger swaps = new AtomicInteger();

        DeconvolutionIO.MigrationResult result =
                DeconvolutionIO.migratePriorWindowsV3FamilyForTest(root, current,
                        new DeconvolutionIO.MigrationFaultInjector() {
                            @Override
                            public void checkpoint(DeconvolutionIO.MigrationFaultPoint point)
                                    throws java.io.IOException {
                                if (point != DeconvolutionIO.MigrationFaultPoint
                                        .BEFORE_JOURNAL_CLEANUP
                                        || !swaps.compareAndSet(0, 1)) return;
                                File transaction = firstFamilyTransaction(root, current);
                                if (transaction == null) {
                                    throw new java.io.IOException("missing transaction to swap");
                                }
                                File detached = new File(transaction.getParentFile(),
                                        transaction.getName() + ".original");
                                Files.move(transaction.toPath(), detached.toPath());
                                Files.createDirectory(transaction.toPath());
                                Files.write(new File(transaction, "replacement.txt").toPath(),
                                        "replacement".getBytes(StandardCharsets.UTF_8));
                                original.set(transaction);
                                parked.set(detached);
                            }
                        });

        assertFalse(result.migrated);
        assertTrue(result.safe);
        assertEquals(1, swaps.get());
        assertEquals("replacement", readUtf8(new File(original.get(), "replacement.txt")));
        assertTrue("the originally created transaction remains retained",
                new File(parked.get(), "recovery.journal").isFile());
    }

    @Test
    public void v4BlockerClassificationUsesCompleteContainerIdentity() throws Exception {
        File root = temp.newFolder("v4-portable-identity-scope");
        String contentHash = repeat("6b", 32);
        DeconvolutionIO.ArtifactIdentity first = new DeconvolutionIO.ArtifactIdentity(
                DeconvolutionIO.ArtifactIdentity.VERSION, 5000L, contentHash,
                "project:portable/A.lif", 2, "Region");
        DeconvolutionIO.ArtifactIdentity second = new DeconvolutionIO.ArtifactIdentity(
                DeconvolutionIO.ArtifactIdentity.VERSION, 5000L, contentHash,
                "project:portable/B.lif", 2, "Region");
        DeconvolutionIO.createV4RecoveryBlockerForTest(root, first);

        assertEquals(first.familyLockToken(), second.familyLockToken());
        assertNotEquals(first.identityHash, second.identityHash);
        assertTrue(DeconvolutionIO.hasProductionRecoveryBlockerForTest(root, first));
        assertFalse(DeconvolutionIO.hasProductionRecoveryBlockerForTest(root, second));
    }

    @Test
    public void noSdsV4BlockerIsIdentityScopedAcrossByteIdenticalContainers()
            throws Exception {
        assumeTrue("prior Windows v3 fallback is Windows-specific", File.separatorChar == '\\');
        File root = temp.newFolder("v4-identity-scoped-blocker");
        String contentHash = repeat("7a", 32);
        DeconvolutionIO.ArtifactIdentity first = new DeconvolutionIO.ArtifactIdentity(
                DeconvolutionIO.ArtifactIdentity.VERSION, 5010L, contentHash,
                "project:container/A.lif", 3, "Region");
        DeconvolutionIO.ArtifactIdentity second = new DeconvolutionIO.ArtifactIdentity(
                DeconvolutionIO.ArtifactIdentity.VERSION, 5010L, contentHash,
                "project:container/B.lif", 3, "Region");
        DeconvolutionIO.ArtifactIdentity priorOnly = new DeconvolutionIO.ArtifactIdentity(
                DeconvolutionIO.ArtifactIdentity.VERSION, 5010L, contentHash,
                "project:container/C.lif", 3, "Region");
        assertEquals("byte-identical sources share the old family token",
                first.familyLockToken(), second.familyLockToken());
        assertNotEquals("v4 recovery keys include the container discriminator",
                first.identityHash, second.identityHash);
        writePriorChannelFamily(root, first, 0, "CURRENT", "first-current");
        File firstBlocker = DeconvolutionIO.createV4RecoveryBlockerForTest(root, first);
        assertTrue(DeconvolutionIO.hasProductionRecoveryBlockerForTest(root, first));
        assertFalse(DeconvolutionIO.hasProductionRecoveryBlockerForTest(root, second));
        writePriorChannelFamily(root, second.priorWindowsV3Identity(), 0,
                "OLD", "second-prior");
        writePriorChannelFamily(root, priorOnly.priorWindowsV3Identity(), 0,
                "OLD", "blocked-prior");
        DeconvolutionIO.createV4RecoveryBlockerForTest(root, priorOnly);
        assertTrue(DeconvolutionIO.hasProductionRecoveryBlockerForTest(root, priorOnly));
        DeconvolutionIO.setStableFileIdentityOverrideForTest(Boolean.FALSE);

        try (DeconvolutionFamilyLock.Handle ignored =
                     DeconvolutionIO.lockFamilyForAccess(root, first)) {
            assertEquals("first-current",
                    readUtf8(DeconvolutionIO.deconvFile(root, first, 0)));
        }
        try (DeconvolutionFamilyLock.Handle ignored =
                     DeconvolutionIO.lockFamilyForAccess(root, second)) {
            assertEquals("second-prior", readUtf8(DeconvolutionIO.deconvFile(root,
                    second.priorWindowsV3Identity(), 0)));
        }
        try (DeconvolutionFamilyLock.Handle ignored =
                     DeconvolutionIO.lockFamilyForAccess(root, priorOnly)) {
            assertEquals("blocked-prior", readUtf8(DeconvolutionIO.deconvFile(root,
                    priorOnly.priorWindowsV3Identity(), 0)));
            List<File> candidates = DeconvolutionIO.deconvFileReadCandidates(root, priorOnly,
                    0, "Region", DeconvolutionIO.LegacyBasenamePolicy.REJECT, 0);
            assertEquals("the validated prior stays selected while v4 recovery is retained",
                    DeconvolutionIO.deconvFile(root, priorOnly.priorWindowsV3Identity(), 0),
                    DeconvolutionIO.firstExistingFile(candidates));
        }

        assertTrue(firstBlocker.isDirectory());
        assertFalse("first identity's blocker must not trigger second promotion or mutation",
                DeconvolutionIO.manifestFile(root, second).exists());
        assertFalse(v4ActiveForTest(root, second).exists());
    }

    @Test
    public void productionBranchLegacyMigrationRootRemainsExplicitGlobalGate()
            throws Exception {
        File root = temp.newFolder("legacy-migration-global-gate");
        DeconvolutionIO.ArtifactIdentity current = identityForRecovery("e7", 5020L, 77);
        DeconvolutionIO.ArtifactIdentity prior = current.priorWindowsV3Identity();
        writePriorChannelFamily(root, prior, 0, "OLD", "legacy-prior");
        Files.createDirectories(new File(DeconvolutionIO.cacheDir(root), ".migration").toPath());
        DeconvolutionIO.setStableFileIdentityOverrideForTest(Boolean.FALSE);

        try (DeconvolutionFamilyLock.Handle ignored =
                     DeconvolutionIO.lockFamilyForAccess(root, current)) {
            assertEquals("legacy-prior",
                    readUtf8(DeconvolutionIO.deconvFile(root, prior, 0)));
        }

        assertFalse("unclassifiable legacy recovery must suppress automatic promotion",
                DeconvolutionIO.manifestFile(root, current).exists());
    }

    private static String repeat(String value, int count) {
        StringBuilder out = new StringBuilder(value.length() * count);
        for (int i = 0; i < count; i++) out.append(value);
        return out.toString();
    }

    private static File v4ActiveForTest(File root,
                                        DeconvolutionIO.ArtifactIdentity identity) {
        return new File(new File(new File(DeconvolutionIO.cacheDir(root), ".migration-v4"),
                identity.identityHash), "active");
    }

    private static void writePriorChannelFamily(File root,
                                                DeconvolutionIO.ArtifactIdentity identity,
                                                int channelIndex,
                                                String paramsHash,
                                                String payload) throws Exception {
        File channel = DeconvolutionIO.deconvFile(root, identity, channelIndex);
        Files.createDirectories(channel.getParentFile().toPath());
        Files.write(channel.toPath(), payload.getBytes(StandardCharsets.UTF_8));
        DeconvManifest.SourceFingerprint source = new DeconvManifest.SourceFingerprint(
                identity.sourceSize, 0L, identity.verifiedSourceContentHash);
        DeconvManifest.ChannelEntry entry = new DeconvManifest.ChannelEntry(paramsHash,
                new LinkedHashMap<String, String>(), source, "test", "1", 1);
        DeconvManifest manifest = DeconvManifest.forArtifact(identity)
                .withChannel(channelIndex, entry);
        DeconvManifest.writeAtomic(DeconvolutionIO.manifestFile(root, identity), manifest);
        File cache = DeconvolutionIO.cacheFile(root, paramsHash, identity, channelIndex);
        Files.createDirectories(cache.getParentFile().toPath());
        Files.write(cache.toPath(), payload.getBytes(StandardCharsets.UTF_8));
    }

    private static void appendPriorChannel(File root,
                                           DeconvolutionIO.ArtifactIdentity identity,
                                           int channelIndex,
                                           String paramsHash,
                                           String payload) throws Exception {
        File channel = DeconvolutionIO.deconvFile(root, identity, channelIndex);
        writeUtf8(channel, payload);
        DeconvManifest.SourceFingerprint source = new DeconvManifest.SourceFingerprint(
                identity.sourceSize, 0L, identity.verifiedSourceContentHash);
        DeconvManifest.ChannelEntry entry = new DeconvManifest.ChannelEntry(paramsHash,
                new LinkedHashMap<String, String>(), source, "test", "1", 1);
        DeconvManifest manifest = DeconvManifest.load(DeconvolutionIO.manifestFile(root, identity))
                .withArtifactIdentity(identity).withChannel(channelIndex, entry);
        DeconvManifest.writeAtomic(DeconvolutionIO.manifestFile(root, identity), manifest);
    }

    private static void addPriorMergedRecord(File root,
                                             DeconvolutionIO.ArtifactIdentity identity,
                                             int channelIndex,
                                             String paramsHash,
                                             String payload) throws Exception {
        writeUtf8(DeconvolutionIO.mergedDeconvFile(root, identity), payload);
        Map<Integer, String> hashes = new LinkedHashMap<Integer, String>();
        hashes.put(Integer.valueOf(channelIndex), paramsHash);
        DeconvManifest.SourceFingerprint source = new DeconvManifest.SourceFingerprint(
                identity.sourceSize, 0L, identity.verifiedSourceContentHash);
        DeconvManifest manifest = DeconvManifest.load(DeconvolutionIO.manifestFile(root, identity))
                .withMerged(new DeconvManifest.MergedRecord(source, hashes));
        DeconvManifest.writeAtomic(DeconvolutionIO.manifestFile(root, identity), manifest);
    }

    private static File writeCacheVariant(File root,
                                          String paramsHash,
                                          DeconvolutionIO.ArtifactIdentity identity,
                                          String relativePath,
                                          String payload) throws Exception {
        File identityDir = new File(DeconvolutionIO.cacheParamsDir(root, paramsHash),
                identity.artifactKey);
        File cache = new File(identityDir, relativePath.replace('/', File.separatorChar));
        writeUtf8(cache, payload);
        return cache;
    }

    private static int countFamilyTransactions(File root,
                                               DeconvolutionIO.ArtifactIdentity identity) {
        File migrationRoot = new File(DeconvolutionIO.cacheDir(root), ".migration");
        File[] transactions = migrationRoot.listFiles((dir, name) -> name != null
                && name.startsWith(identity.familyLockToken() + "-"));
        return transactions == null ? 0 : transactions.length;
    }

    private static int countDeferredCleanupTransactions(
            File root, DeconvolutionIO.ArtifactIdentity identity) {
        File cleanupRoot = new File(new File(DeconvolutionIO.cacheDir(root), ".migration"),
                DeconvolutionIO.DEFERRED_CLEANUP_DIRECTORY);
        File[] transactions = cleanupRoot.listFiles((dir, name) -> name != null
                && name.startsWith(identity.familyLockToken() + "-"));
        int count = transactions == null ? 0 : transactions.length;
        File familyQueue = new File(new File(cleanupRoot, ".queue"), identity.familyLockToken());
        File[] payloads = new File(familyQueue, ".payloads").listFiles();
        if (payloads != null) count += payloads.length;
        return count;
    }

    private static File firstDeferredCleanupTransaction(
            File root, DeconvolutionIO.ArtifactIdentity identity) {
        File cleanupRoot = new File(new File(DeconvolutionIO.cacheDir(root), ".migration"),
                DeconvolutionIO.DEFERRED_CLEANUP_DIRECTORY);
        File[] transactions = cleanupRoot.listFiles((dir, name) -> name != null
                && name.startsWith(identity.familyLockToken() + "-"));
        if (transactions != null && transactions.length > 0) return transactions[0];
        File familyQueue = new File(new File(cleanupRoot, ".queue"), identity.familyLockToken());
        File[] payloads = new File(familyQueue, ".payloads").listFiles();
        if (payloads != null && payloads.length > 0) return payloads[0];
        return null;
    }

    private static File cleanupShard(File cleanupRoot, String familyToken, int shard)
            throws Exception {
        File directory = new File(new File(new File(cleanupRoot, ".queue"), familyToken),
                Integer.toString(shard));
        Files.createDirectories(directory.toPath());
        return directory;
    }

    private static File latestCleanupStateFile(File cleanupRoot, String familyToken)
            throws Exception {
        File family = new File(new File(cleanupRoot, ".queue"), familyToken);
        File first = new File(family, ".state-0");
        File second = new File(family, ".state-1");
        if (!first.isFile()) return second;
        if (!second.isFile()) return first;
        return cleanupStateGeneration(first) >= cleanupStateGeneration(second) ? first : second;
    }

    private static File cleanupFamilyQueue(File root,
                                           DeconvolutionIO.ArtifactIdentity identity) {
        return new File(new File(new File(new File(DeconvolutionIO.cacheDir(root), ".migration"),
                DeconvolutionIO.DEFERRED_CLEANUP_DIRECTORY), ".queue"),
                identity.familyLockToken());
    }

    private static File exactCleanupFamilyQueue(File root) throws Exception {
        return new File(new File(new File(new File(DeconvolutionIO.cacheDir(root), ".migration"),
                DeconvolutionIO.DEFERRED_CLEANUP_DIRECTORY), ".queue"),
                sha256ForTest(root.getCanonicalPath()));
    }

    private static long cleanupStateCounter(File state, String key) throws Exception {
        String prefix = key + "|";
        for (String line : readUtf8(state).split("\n")) {
            if (line.startsWith(prefix)) return Long.parseLong(line.substring(prefix.length()));
        }
        fail("missing cleanup state counter " + key);
        return -1L;
    }

    private static long cleanupStateGeneration(File state) throws Exception {
        String[] lines = readUtf8(state).split("\\n");
        assertTrue(lines.length >= 2);
        assertEquals("deconv-cleanup-state-v1", lines[0]);
        assertTrue(lines[1].startsWith("generation|"));
        return Long.parseLong(lines[1].substring("generation|".length()));
    }

    private static int countDirectoriesWithPrefix(File root, String prefix) {
        File[] matches = root.listFiles((dir, name) -> name != null && name.startsWith(prefix));
        return matches == null ? 0 : matches.length;
    }

    private static int countFilesRecursively(File root) {
        if (root == null || !root.exists()) return 0;
        if (root.isFile()) return 1;
        File[] children = root.listFiles();
        if (children == null) return 0;
        int count = 0;
        for (File child : children) count += countFilesRecursively(child);
        return count;
    }

    private static int indexOfPathAfter(List<String> paths, File wanted, int start)
            throws java.io.IOException {
        String canonical = wanted.getCanonicalPath();
        for (int i = Math.max(0, start); i < paths.size(); i++) {
            if (canonical.equals(paths.get(i))) return i;
        }
        fail("missing ordered directory force for " + canonical + " after index " + start);
        return -1;
    }

    private static int countFamilyQuarantines(File root,
                                              DeconvolutionIO.ArtifactIdentity identity) {
        File quarantineRoot = new File(new File(DeconvolutionIO.cacheDir(root), ".migration"),
                ".quarantine");
        File[] transactions = quarantineRoot.listFiles((dir, name) -> name != null
                && name.startsWith(identity.familyLockToken() + "-"));
        return transactions == null ? 0 : transactions.length;
    }

    private static File firstFamilyTransaction(File root,
                                               DeconvolutionIO.ArtifactIdentity identity) {
        File migrationRoot = new File(DeconvolutionIO.cacheDir(root), ".migration");
        File[] transactions = migrationRoot.listFiles((dir, name) -> name != null
                && name.startsWith(identity.familyLockToken() + "-"));
        return transactions == null || transactions.length == 0 ? null : transactions[0];
    }

    private static DeconvolutionIO.MigrationResult mutateTransactionAt(
            final File root, final DeconvolutionIO.ArtifactIdentity identity,
            final DeconvolutionIO.MigrationFaultPoint faultPoint,
            final String relativePath, final String payload) {
        return DeconvolutionIO.migratePriorWindowsV3FamilyForTest(root, identity,
                new DeconvolutionIO.MigrationFaultInjector() {
                    @Override
                    public void checkpoint(DeconvolutionIO.MigrationFaultPoint point)
                            throws java.io.IOException {
                        if (point != faultPoint) return;
                        File transaction = firstFamilyTransaction(root, identity);
                        if (transaction == null) {
                            throw new java.io.IOException("missing active migration transaction");
                        }
                        try {
                            writeUtf8(new File(transaction,
                                    relativePath.replace('/', File.separatorChar)), payload);
                        } catch (Exception failure) {
                            throw new java.io.IOException(failure);
                        }
                    }
                });
    }

    private static File manualRecoveryTransaction(File root,
                                                  DeconvolutionIO.ArtifactIdentity identity,
                                                  String suffix) throws Exception {
        File transaction = new File(new File(DeconvolutionIO.cacheDir(root), ".migration"),
                identity.familyLockToken() + "-manual-" + suffix);
        Files.createDirectories(transaction.toPath());
        return transaction;
    }

    private void assertRejectedJournalBytes(String suffix, String pair, byte[] payload)
            throws Exception {
        File root = temp.newFolder("bounded-journal-" + suffix);
        DeconvolutionIO.ArtifactIdentity current = identityForRecovery(
                pair, 2800L + payload.length, 60 + suffix.length());
        DeconvolutionIO.ArtifactIdentity prior = current.priorWindowsV3Identity();
        writePriorChannelFamily(root, prior, 0, "OLD", "prior-" + suffix);
        File transaction = manualRecoveryTransaction(root, current, suffix);
        Files.write(new File(transaction, "recovery.journal").toPath(), payload);

        try (DeconvolutionFamilyLock.Handle ignored =
                     DeconvolutionIO.lockFamilyForAccess(root, current)) {
            assertEquals("prior-" + suffix,
                    readUtf8(DeconvolutionIO.deconvFile(root, prior, 0)));
        }
        assertEquals(0, countFamilyTransactions(root, current));
        assertEquals(1, countFamilyQuarantines(root, current));
        assertFalse(DeconvolutionIO.manifestFile(root, current).exists());
    }

    private static void writeChecksummedTestJournal(File transaction, String records) throws Exception {
        int count = 0;
        for (int i = 0; i < records.length(); i++) {
            if (records.charAt(i) == '\n') count++;
        }
        String journal = "deconv-migration-v3\n" + records
                + "commit|" + count + '|' + sha256ForTest(records) + "\n";
        writeUtf8(new File(transaction, "recovery.journal"), journal);
    }

    private static String encodedPathForTest(File file) throws Exception {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                file.getCanonicalPath().getBytes(StandardCharsets.UTF_8));
    }

    private static void writeLegacyV2ManifestOnlyJournal(
            File transaction, File root, DeconvolutionIO.ArtifactIdentity current,
            boolean copyLiveManifest, boolean corruptChecksum) throws Exception {
        File desired = new File(transaction, "desired-manifest.json");
        if (copyLiveManifest) {
            Files.copy(DeconvolutionIO.manifestFile(root, current).toPath(), desired.toPath());
        } else {
            writeUtf8(desired, DeconvManifest.forArtifact(current).toJson());
        }
        String target = Base64.getUrlEncoder().encodeToString(
                DeconvolutionIO.manifestFile(root, current).getCanonicalPath()
                        .getBytes(StandardCharsets.UTF_8));
        String records = "manifest|" + target + "|0|-\n";
        String checksum = corruptChecksum ? repeat("0", 64) : sha256ForTest(records);
        writeUtf8(new File(transaction, "recovery.journal"),
                "deconv-migration-v2\n" + records + "commit|1|" + checksum + "\n");
    }

    private static String sha256ForTest(String value) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder(digest.length * 2);
        for (byte b : digest) {
            hex.append(Character.forDigit((b >>> 4) & 0xf, 16));
            hex.append(Character.forDigit(b & 0xf, 16));
        }
        return hex.toString();
    }

    private static String hexDigest(byte[] value) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(value);
        StringBuilder hex = new StringBuilder(digest.length * 2);
        for (byte b : digest) {
            hex.append(Character.forDigit((b >>> 4) & 0xf, 16));
            hex.append(Character.forDigit(b & 0xf, 16));
        }
        return hex.toString();
    }

    private static DeconvolutionIO.ArtifactIdentity identityForRecovery(
            String pair, long sourceSize, int sourceSeriesIndex) {
        return new DeconvolutionIO.ArtifactIdentity(DeconvolutionIO.ArtifactIdentity.VERSION,
                sourceSize, repeat(pair, 32), "project:Input/Recovery.LIF",
                sourceSeriesIndex, "Region");
    }

    private static void assertUnsafeFamilyAccess(File root,
                                                 DeconvolutionIO.ArtifactIdentity identity,
                                                 String expectedMessage) throws Exception {
        try (DeconvolutionFamilyLock.Handle ignored =
                     DeconvolutionIO.lockFamilyForAccess(root, identity)) {
            fail("expected unsafe family recovery to fail");
        } catch (java.io.IOException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains(expectedMessage));
        }
    }

    private static boolean createJunction(File link, File target) throws Exception {
        Process process = new ProcessBuilder("cmd", "/c", "mklink", "/J",
                link.getAbsolutePath(), target.getAbsolutePath()).redirectErrorStream(true).start();
        byte[] buffer = new byte[1024];
        while (process.getInputStream().read(buffer) >= 0) {
            // Drain mklink output so the process cannot block on its pipe.
        }
        return process.waitFor() == 0 && link.exists();
    }

    private static DeconvolutionIO.MigrationFaultInjector failMigrationAt(
            final DeconvolutionIO.MigrationFaultPoint expected) {
        return new DeconvolutionIO.MigrationFaultInjector() {
            @Override
            public void checkpoint(DeconvolutionIO.MigrationFaultPoint point)
                    throws java.io.IOException {
                if (point == expected) throw new java.io.IOException("deterministic " + point + " fault");
            }
        };
    }

    private static void writeUtf8(File file, String value) throws Exception {
        Files.createDirectories(file.getParentFile().toPath());
        Files.write(file.toPath(), value.getBytes(StandardCharsets.UTF_8));
    }

    private static boolean hasStableFileKey(File directory) throws Exception {
        return DeconvolutionIO.stableFileIdentityAvailableForTest(directory);
    }

    private static boolean treeContainsUtf8(File root, String expected) throws Exception {
        if (root == null || !Files.exists(root.toPath(), LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(root.toPath())) return false;
        if (root.isFile()) return expected.equals(readUtf8(root));
        File[] children = root.listFiles();
        if (children == null) return false;
        for (File child : children) {
            if (treeContainsUtf8(child, expected)) return true;
        }
        return false;
    }

    private static boolean treeContainsNamedFile(File root, String name) {
        if (root == null || !root.exists() || Files.isSymbolicLink(root.toPath())) return false;
        if (root.isFile()) return name.equals(root.getName());
        File[] children = root.listFiles();
        if (children == null) return false;
        for (File child : children) {
            if (treeContainsNamedFile(child, name)) return true;
        }
        return false;
    }

    private static void writeJsonPaddedToManifestLimit(File file, String json) throws Exception {
        byte[] jsonBytes = json.getBytes(StandardCharsets.UTF_8);
        assertTrue(jsonBytes.length <= DeconvManifest.MAX_MANIFEST_UTF8_BYTES);
        byte[] padded = new byte[DeconvManifest.MAX_MANIFEST_UTF8_BYTES];
        java.util.Arrays.fill(padded, (byte) ' ');
        System.arraycopy(jsonBytes, 0, padded, 0, jsonBytes.length);
        Files.createDirectories(file.getParentFile().toPath());
        Files.write(file.toPath(), padded);
    }

    private static String readUtf8(File file) throws Exception {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }
}
