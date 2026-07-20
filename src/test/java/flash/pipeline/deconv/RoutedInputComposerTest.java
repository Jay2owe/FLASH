package flash.pipeline.deconv;

import flash.pipeline.deconv.routing.DeconvRouting;
import flash.pipeline.deconv.routing.DeconvRoutingGroup;
import flash.pipeline.deconv.routing.SourceKind;

import ij.ImagePlus;
import ij.ImageStack;
import ij.measure.Calibration;
import ij.process.FloatProcessor;
import ij.process.ShortProcessor;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Stage 14 — §C1 composition-assembly contract tests.
 *
 * <p>Exercises the pure {@link RoutedInputComposer#compose} core with synthetic in-memory stacks
 * (no ImageJ boot) plus the {@link DeconvolvedInputResolver#fastPathMergedFile} decision on disk.</p>
 */
public class RoutedInputComposerTest {

    private static final int W = 3;
    private static final int H = 2;

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @After
    public void tearDown() {
        RoutedInputComposer.clearCache();
    }

    // ---- REQUIRED #1: mixed 16-bit raw + 32-bit float mirror, subset composition ----

    @Test
    public void subsetCompositionMixesFloatMirrorAndShortRawWithoutCorruption() {
        // ch0 = deconvolved mirror (32-bit float), ch1 = raw (16-bit), depth 4.
        ImagePlus mirror = floatStack("mirror", 4, new PixelFn() {
            public float at(int x, int y, int z) { return 100f * z + (y * W + x) + 0.25f; }
        });
        // Plant a NaN and a negative on slice 2 to prove sanitize clamps them to 0.
        ((FloatProcessor) mirror.getStack().getProcessor(2)).setf(0, 0, Float.NaN);
        ((FloatProcessor) mirror.getStack().getProcessor(2)).setf(1, 0, -5.0f);

        ImagePlus raw = shortStack("raw", 4, new PixelFn() {
            public float at(int x, int y, int z) { return 10 * z + (y * W + x); }
        });

        Calibration cal = new Calibration();
        cal.pixelWidth = 0.32;
        cal.pixelHeight = 0.32;
        cal.pixelDepth = 1.5;

        // Z-subset [2,3] on a depth-4 assembly -> 2 output slices from source z 2 and 3.
        ImagePlus composed = RoutedInputComposer.compose("case1",
                new ImagePlus[]{mirror, raw}, 2, cal, 2, 3);
        try {
            assertEquals("assembled stack is 32-bit float", 32, composed.getBitDepth());
            assertEquals("channel count preserved", 2, composed.getNChannels());
            assertEquals("z-subset applied", 2, composed.getNSlices());

            for (int outZ = 1; outZ <= 2; outZ++) {
                int srcZ = outZ + 1; // subset started at 2
                for (int y = 0; y < H; y++) {
                    for (int x = 0; x < W; x++) {
                        float ch1 = pixel(composed, 1, outZ, x, y); // channel index 0 == mirror
                        float ch2 = pixel(composed, 2, outZ, x, y); // channel index 1 == raw
                        float expectedMirror = 100f * srcZ + (y * W + x) + 0.25f;
                        if (srcZ == 2 && x == 0 && y == 0) expectedMirror = 0f; // NaN sanitized
                        if (srcZ == 2 && x == 1 && y == 0) expectedMirror = 0f; // negative sanitized
                        assertEquals("mirror value preserved on channel 1 (order)",
                                expectedMirror, ch1, 1e-4f);
                        assertEquals("raw value preserved on channel 2 (order)",
                                (float) (10 * srcZ + (y * W + x)), ch2, 1e-4f);
                    }
                }
            }

            assertNotNull(composed.getCalibration());
            assertEquals(0.32, composed.getCalibration().pixelWidth, 1e-9);
            assertEquals(1.5, composed.getCalibration().pixelDepth, 1e-9);
        } finally {
            close(composed);
            close(mirror);
            close(raw);
        }
    }

    // ---- REQUIRED #2: depth mismatch reconciles to min then subsets within bounds ----

    @Test
    public void depthMismatchReconcilesToMinThenSubsetsWithinBounds() {
        // Mirror depth = raw depth - 1 (the "mirror = depth-1" case). min() = 4.
        ImagePlus mirror = floatStack("mirror", 4, new PixelFn() {
            public float at(int x, int y, int z) { return z + 0.5f; }
        });
        ImagePlus raw = shortStack("raw", 5, new PixelFn() {
            public float at(int x, int y, int z) { return 200 + z; }
        });

        // Request z-subset [3,10]; 10 is past the reconciled depth (4) so it clamps to [3,4].
        ImagePlus composed = RoutedInputComposer.compose("case2",
                new ImagePlus[]{mirror, raw}, 2, null, 3, 10);
        try {
            assertEquals(32, composed.getBitDepth());
            assertEquals(2, composed.getNChannels());
            assertEquals("clamped subset within reconciled depth", 2, composed.getNSlices());

            // outZ 1 -> srcZ 3, outZ 2 -> srcZ 4; raw slice 5 must never be read (no out-of-range).
            assertEquals(3f + 0.5f, pixel(composed, 1, 1, 0, 0), 1e-4f);
            assertEquals(4f + 0.5f, pixel(composed, 1, 2, 0, 0), 1e-4f);
            assertEquals(203f, pixel(composed, 2, 1, 0, 0), 1e-4f);
            assertEquals(204f, pixel(composed, 2, 2, 0, 0), 1e-4f);
        } finally {
            close(composed);
            close(mirror);
            close(raw);
        }
    }

    // ---- REQUIRED #3: config/source channel-count mismatch fails loudly ----

    @Test
    public void channelCountMismatchFailsLoudlyRatherThanMinningAway() {
        ImagePlus c0 = floatStack("c0", 3, CONST);
        ImagePlus c1 = shortStack("c1", 3, CONST);
        try {
            RoutedInputComposer.compose("mismatch", new ImagePlus[]{c0, c1}, 3, null);
            fail("expected an IllegalArgumentException on config(3) != source(2) channel count");
        } catch (IllegalArgumentException expected) {
            assertTrue("message names the channel-count mismatch",
                    expected.getMessage().toLowerCase().contains("channel count mismatch"));
        } finally {
            close(c0);
            close(c1);
        }
    }

    // ---- REQUIRED #4: default routing + fresh merged file uses the fast path ----

    @Test
    public void defaultRoutingWithFreshMergedFileTakesFastPath() throws Exception {
        File root = temp.newFolder("proj");
        String base = "img";

        File source = new File(root, base + ".lif");
        Files.write(source.toPath(), "raw-container".getBytes(StandardCharsets.UTF_8));

        File merged = DeconvolutionIO.mergedDeconvFile(root, base);
        Files.createDirectories(merged.getParentFile().toPath());
        Files.write(merged.toPath(), "merged-deconv".getBytes(StandardCharsets.UTF_8));

        // Source older than the merged mirror -> mtime fallback (no manifest) reports fresh.
        assertTrue(source.setLastModified(1_000L));
        assertTrue(merged.setLastModified(2_000L));

        DeconvRouting defaultRouting = DeconvRouting.defaultFor(new boolean[]{true, true});

        // No manifest on disk here, so freshness uses the mtime fallback; expected params is null
        // (params check skipped) — the merged fast-path decision is unaffected.
        File chosen = DeconvolvedInputResolver.fastPathMergedFile(
                root, base, source, defaultRouting, DeconvRoutingGroup.ANALYSIS, null);
        assertNotNull("default routing + fresh merged must pick the merged fast path", chosen);
        assertEquals(merged, chosen);

        // Divergence: force channel 0 to raw for ANALYSIS -> no longer the group default -> compose.
        DeconvRouting divergent = defaultRouting.withSource(DeconvRoutingGroup.ANALYSIS, 0, SourceKind.RAW);
        assertNull("divergent routing must NOT use the merged fast path",
                DeconvolvedInputResolver.fastPathMergedFile(
                        root, base, source, divergent, DeconvRoutingGroup.ANALYSIS, null));

        // Stale merged (source newer than mirror) must also drop the fast path.
        assertTrue(merged.setLastModified(500L));
        assertNull("stale merged must NOT use the fast path",
                DeconvolvedInputResolver.fastPathMergedFile(
                        root, base, source, defaultRouting, DeconvRoutingGroup.ANALYSIS, null));
    }

    // ---- Merged fast path is anchored to the manifest's content merged record (Stage 18 findings) ----

    private static DeconvManifest.ChannelEntry entry(String hash, DeconvManifest.SourceFingerprint fp) {
        return new DeconvManifest.ChannelEntry(hash, null, fp, "CLIJ2",
                DeconvManifest.ENGINE_STAMP_VERSION, 8);
    }

    /** Two-channel project: source + merged file + both per-channel mirror files on disk. */
    private File[] twoChannelProjectFiles(File root, String base) throws Exception {
        File source = new File(root, base + ".lif");
        Files.write(source.toPath(), "raw-container".getBytes(StandardCharsets.UTF_8));
        File merged = DeconvolutionIO.mergedDeconvFile(root, base);
        Files.createDirectories(merged.getParentFile().toPath());
        Files.write(merged.toPath(), "merged".getBytes(StandardCharsets.UTF_8));
        File c0 = DeconvolutionIO.deconvFile(root, base, 0);
        File c1 = DeconvolutionIO.deconvFile(root, base, 1);
        Files.write(c0.toPath(), "c0".getBytes(StandardCharsets.UTF_8));
        Files.write(c1.toPath(), "c1".getBytes(StandardCharsets.UTF_8));
        return new File[]{source, merged, c0, c1};
    }

    private static final DeconvRouting BOTH = DeconvRouting.defaultFor(new boolean[]{true, true});

    @Test
    public void mergedFastPathAcceptedWhenRecordMatchesEveryRoutedChannel() throws Exception {
        File root = temp.newFolder("proj-fresh");
        String base = "img";
        File[] f = twoChannelProjectFiles(root, base);
        File source = f[0];
        File merged = f[1];

        DeconvManifest.SourceFingerprint fp = DeconvManifest.SourceFingerprint.of(source);
        java.util.Map<Integer, String> hashes = new java.util.HashMap<Integer, String>();
        hashes.put(0, "H0");
        hashes.put(1, "H1");
        DeconvManifest manifest = DeconvManifest.empty()
                .withChannel(0, entry("H0", fp))
                .withChannel(1, entry("H1", fp))
                .withMerged(new DeconvManifest.MergedRecord(fp, hashes));
        DeconvManifest.writeAtomic(DeconvolutionIO.manifestFile(root, base), manifest);

        File chosen = DeconvolvedInputResolver.fastPathMergedFile(
                root, base, source, BOTH, DeconvRoutingGroup.ANALYSIS, null);
        assertNotNull("merged record matches all routed channels -> fast path", chosen);
        assertEquals(merged, chosen);
    }

    @Test
    public void mergedFastPathRejectedWhenMergedRecordIsStaleForAChannel() throws Exception {
        File root = temp.newFolder("proj-stale-rec");
        String base = "img";
        File source = twoChannelProjectFiles(root, base)[0];

        // The per-channel mirror advanced (entry H1_NEW) but the merged record still points at the old
        // hash (H1_OLD) -> the merged file does NOT reflect the current channel 1 mirror.
        DeconvManifest.SourceFingerprint fp = DeconvManifest.SourceFingerprint.of(source);
        java.util.Map<Integer, String> hashes = new java.util.HashMap<Integer, String>();
        hashes.put(0, "H0");
        hashes.put(1, "H1_OLD");
        DeconvManifest manifest = DeconvManifest.empty()
                .withChannel(0, entry("H0", fp))
                .withChannel(1, entry("H1_NEW", fp))
                .withMerged(new DeconvManifest.MergedRecord(fp, hashes));
        DeconvManifest.writeAtomic(DeconvolutionIO.manifestFile(root, base), manifest);

        assertNull("stale merged record for a channel must drop the fast path",
                DeconvolvedInputResolver.fastPathMergedFile(
                        root, base, source, BOTH, DeconvRoutingGroup.ANALYSIS, null));
    }

    @Test
    public void mergedFastPathRejectedWhenNoMergedRecord() throws Exception {
        File root = temp.newFolder("proj-no-rec");
        String base = "img";
        File source = twoChannelProjectFiles(root, base)[0];

        // Per-channel entries present + fresh, but NO merged record (e.g. a build before the record, or
        // a failed merge rewrite): the merged file cannot be proven current -> per-channel composition.
        DeconvManifest.SourceFingerprint fp = DeconvManifest.SourceFingerprint.of(source);
        DeconvManifest manifest = DeconvManifest.empty()
                .withChannel(0, entry("H0", fp))
                .withChannel(1, entry("H1", fp));
        DeconvManifest.writeAtomic(DeconvolutionIO.manifestFile(root, base), manifest);

        assertNull("no merged record -> merged fast path must not be used",
                DeconvolvedInputResolver.fastPathMergedFile(
                        root, base, source, BOTH, DeconvRoutingGroup.ANALYSIS, null));
    }

    @Test
    public void mergedFastPathRejectedWhenARoutedMirrorFileIsMissing() throws Exception {
        File root = temp.newFolder("proj-missing-mirror");
        String base = "img";
        File source = twoChannelProjectFiles(root, base)[0];
        // Delete channel 1's mirror file (present in manifest + record, but gone/unsynced on disk).
        assertTrue(DeconvolutionIO.deconvFile(root, base, 1).delete());

        DeconvManifest.SourceFingerprint fp = DeconvManifest.SourceFingerprint.of(source);
        java.util.Map<Integer, String> hashes = new java.util.HashMap<Integer, String>();
        hashes.put(0, "H0");
        hashes.put(1, "H1");
        DeconvManifest manifest = DeconvManifest.empty()
                .withChannel(0, entry("H0", fp))
                .withChannel(1, entry("H1", fp))
                .withMerged(new DeconvManifest.MergedRecord(fp, hashes));
        DeconvManifest.writeAtomic(DeconvolutionIO.manifestFile(root, base), manifest);

        assertNull("a routed channel whose mirror file is missing must drop the fast path (parity "
                        + "with the preflight)",
                DeconvolvedInputResolver.fastPathMergedFile(
                        root, base, source, BOTH, DeconvRoutingGroup.ANALYSIS, null));
    }

    @Test
    public void presentButMalformedManifestDoesNotFallBackToMtime() throws Exception {
        File root = temp.newFolder("proj-malformed");
        String base = "img";
        File source = twoChannelProjectFiles(root, base)[0];
        // A PRESENT but corrupt manifest sidecar must NOT be treated as "legacy no-manifest" (which
        // would optimistically trust the merged file by mtime). It must earn the fast path through the
        // content merged record, which it cannot -> per-channel composition.
        File manifestFile = DeconvolutionIO.manifestFile(root, base);
        Files.createDirectories(manifestFile.getParentFile().toPath());
        Files.write(manifestFile.toPath(), "}{ this is not json".getBytes(StandardCharsets.UTF_8));
        // Make the merged file mtime-fresh vs source, so a stray mtime fallback WOULD wrongly accept it.
        assertTrue(source.setLastModified(1_000L));
        assertTrue(DeconvolutionIO.mergedDeconvFile(root, base).setLastModified(9_000L));

        assertNull("a present-but-malformed manifest must not serve the merged file by mtime",
                DeconvolvedInputResolver.fastPathMergedFile(
                        root, base, source, BOTH, DeconvRoutingGroup.ANALYSIS, null));
    }

    // ---- Cache round-trip (keyed value object) ----

    @Test
    public void cacheStoresAndReturnsPrivateDeepCopies() {
        ImagePlus mirror = floatStack("m", 2, CONST);
        ImagePlus raw = shortStack("r", 2, CONST);
        ImagePlus assembled = RoutedInputComposer.compose("cached", new ImagePlus[]{mirror, raw}, 2, null);
        try {
            RoutedInputComposer.ComposeKey key = new RoutedInputComposer.ComposeKey(
                    temp.getRoot(), "img", "ANALYSIS", 0, 123L, 456L,
                    new boolean[]{true, false}, new long[]{999L, 0L}, 0, 0);
            assertNull(RoutedInputComposer.getCached(key));
            RoutedInputComposer.putCached(key, assembled);

            ImagePlus hit = RoutedInputComposer.getCached(key);
            assertNotNull("cache hit expected", hit);
            assertTrue("cache returns a private copy, not the stored instance", hit != assembled);
            assertEquals(assembled.getNChannels(), hit.getNChannels());
            assertEquals(assembled.getNSlices(), hit.getNSlices());
            assertEquals(pixel(assembled, 1, 1, 0, 0), pixel(hit, 1, 1, 0, 0), 1e-6f);
            close(hit);
        } finally {
            close(assembled);
            close(mirror);
            close(raw);
        }
    }

    // ---- helpers ----

    private interface PixelFn {
        float at(int x, int y, int z);
    }

    private static final PixelFn CONST = new PixelFn() {
        public float at(int x, int y, int z) { return 7f; }
    };

    private static ImagePlus floatStack(String title, int depth, PixelFn fn) {
        ImageStack stack = new ImageStack(W, H);
        for (int z = 1; z <= depth; z++) {
            float[] px = new float[W * H];
            for (int y = 0; y < H; y++) {
                for (int x = 0; x < W; x++) {
                    px[y * W + x] = fn.at(x, y, z);
                }
            }
            stack.addSlice(new FloatProcessor(W, H, px, null));
        }
        ImagePlus imp = new ImagePlus(title, stack);
        imp.setDimensions(1, depth, 1);
        return imp;
    }

    private static ImagePlus shortStack(String title, int depth, PixelFn fn) {
        ImageStack stack = new ImageStack(W, H);
        for (int z = 1; z <= depth; z++) {
            short[] px = new short[W * H];
            for (int y = 0; y < H; y++) {
                for (int x = 0; x < W; x++) {
                    px[y * W + x] = (short) fn.at(x, y, z);
                }
            }
            stack.addSlice(new ShortProcessor(W, H, px, null));
        }
        ImagePlus imp = new ImagePlus(title, stack);
        imp.setDimensions(1, depth, 1);
        return imp;
    }

    private static float pixel(ImagePlus img, int channel1Based, int slice1Based, int x, int y) {
        int idx = img.getStackIndex(channel1Based, slice1Based, 1);
        return img.getStack().getProcessor(idx).getf(x, y);
    }

    private static void close(ImagePlus img) {
        if (img == null) return;
        img.changes = false;
        img.close();
    }
}
