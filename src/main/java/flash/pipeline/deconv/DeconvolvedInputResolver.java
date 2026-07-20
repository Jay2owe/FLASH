package flash.pipeline.deconv;

import flash.pipeline.deconv.routing.DeconvRouting;
import flash.pipeline.deconv.routing.DeconvRoutingGroup;
import flash.pipeline.io.DeferredImageSupplier;
import flash.pipeline.naming.ImageNameParser;
import ij.IJ;
import ij.ImagePlus;
import ij.io.Opener;
import ij.measure.Calibration;

import java.io.File;
import java.io.IOException;
import java.util.List;

/**
 * Resolves a raw input image to its deconvolved mirror when one exists and is
 * newer than the source image.
 */
public final class DeconvolvedInputResolver {

    private static volatile LogSink logSink = new LogSink() {
        @Override
        public void log(String message) {
            IJ.log(message);
        }
    };

    private DeconvolvedInputResolver() {}

    public static File resolveInput(File originalImage, boolean useDeconvIfAvailable) {
        if (originalImage == null) return null;
        String baseName = ImageNameParser.stripExtension(originalImage.getName());
        return resolveInput(originalImage.getParentFile(), originalImage, baseName, useDeconvIfAvailable);
    }

    public static File resolveInput(File rootDir,
                                    File originalSource,
                                    String imageBaseName,
                                    boolean useDeconvIfAvailable) {
        if (originalSource == null) return null;

        String label = labelFor(originalSource, imageBaseName);
        if (!useDeconvIfAvailable) {
            log("[Deconv] " + label + ": useDeconv disabled - using raw");
            return originalSource;
        }

        List<File> mirrors = mirrorReadCandidates(rootDir, imageBaseName);
        boolean foundMirror = false;
        for (int i = 0; i < mirrors.size(); i++) {
            File mirror = mirrors.get(i);
            if (mirror == null || !mirror.isFile()) {
                continue;
            }
            foundMirror = true;
            if (mergedMirrorFresh(rootDir, imageBaseName, originalSource, mirror)) {
                log("[Deconv] " + label + ": using deconvolved stack");
                return mirror;
            }
        }

        if (!foundMirror) {
            log("[Deconv] " + label + ": no deconvolved mirror - using raw");
            return originalSource;
        }

        log("[Deconv] " + label + ": deconvolved stale - using raw");
        return originalSource;
    }

    public static File mirrorPathFor(File originalImage) {
        if (originalImage == null) return null;
        String baseName = ImageNameParser.stripExtension(originalImage.getName());
        return mirrorPathFor(originalImage.getParentFile(), baseName);
    }

    public static File mirrorPathFor(File rootDir, String imageBaseName) {
        if (rootDir == null) return null;
        String baseName = imageBaseName == null ? "" : imageBaseName.trim();
        if (baseName.isEmpty()) return null;
        List<File> candidates = DeconvolutionIO.mergedDeconvFileReadCandidates(rootDir, baseName);
        File existing = DeconvolutionIO.firstExistingFile(candidates);
        return existing == null ? DeconvolutionIO.mergedDeconvFile(rootDir, baseName) : existing;
    }

    // ------------------------------------------------------------------
    // Stage 14 — per-channel/per-group routed composition (Option 2)
    // ------------------------------------------------------------------

    /**
     * Resolve the pixel stack a routed consumer should measure/render on for one series and one
     * {@link DeconvRoutingGroup}, composing per-channel from raw + {@code _C{idx}.tif} mirrors as
     * dictated by {@code routing}.
     *
     * <p>Three cases, cheapest first:</p>
     * <ol>
     *   <li><b>Raw-only group</b> — the group reads no deconvolved channel; the native raw series is
     *       returned unchanged (original bit depth preserved), only Z-subset cropped.</li>
     *   <li><b>Fast path</b> — the group's vector equals the plain opt-in default and a fresh merged
     *       {@code _deconv.tif} exists; that merged stack is opened directly (today's cheap
     *       behaviour), only Z-subset cropped.</li>
     *   <li><b>Per-channel composition</b> — otherwise the stack is assembled channel-by-channel via
     *       {@link RoutedInputComposer#compose} honouring the §C1 contract (32-bit float promotion,
     *       depth reconcile to {@code min} + trim, channel-count fail-loud, calibration stamp,
     *       NaN/negative sanitize, channel order == config index).</li>
     * </ol>
     *
     * <p>Per-channel freshness: a channel takes its mirror only when {@code routing.usesDeconv} AND
     * the mirror is fresh in the Stage-01 manifest (params-hash + source fingerprint) AND the mirror
     * is not deeper than its raw sibling (a longer mirror is rejected as stale); otherwise that one
     * channel falls back to raw without invalidating the rest of the assembly.</p>
     *
     * @param rootDir          project root (locates mirror + manifest sidecars)
     * @param supplier         source-series supplier (opens raw channels + calibration + sizeC)
     * @param sourceContainer  the raw container file, for the source freshness fingerprint + cache key
     * @param imageBaseName    base name used for mirror/manifest sidecar lookup
     * @param seriesIndex      zero-based series index within the supplier
     * @param routing          the per-channel two-group routing (already normalized by the caller)
     * @param group            which group (ANALYSIS/DISPLAY) this consumer belongs to
     * @param expectedParams   the per-channel config-derived params the caller derived from the
     *                          persisted optics config (the composer never re-derives optics), used to
     *                          detect a deconvolution-parameter change against each mirror's manifest;
     *                          {@code null}/{@link ExpectedDeconvParams#none()} skips the params check
     *                          and verifies the source fingerprint only
     * @param zStart           1-based inclusive first slice of the consumption-time Z-subset (&le;0 = first)
     * @param zEnd             1-based inclusive last slice of the Z-subset (&le;0 = last reconciled slice)
     * @return the resolved {@link ImagePlus} (caller owns it and must close it), or {@code null} when
     *         the group routes to raw for every channel with no fresh mirror to compose — the caller
     *         then falls back to its native raw open (byte-identical, no float promotion)
     * @throws IllegalStateException if the config channel count disagrees with the source series sizeC
     */
    public static ImagePlus resolveRoutedInput(File rootDir,
                                               DeferredImageSupplier supplier,
                                               File sourceContainer,
                                               String imageBaseName,
                                               int seriesIndex,
                                               DeconvRouting routing,
                                               DeconvRoutingGroup group,
                                               ExpectedDeconvParams expectedParams,
                                               int zStart,
                                               int zEnd) throws Exception {
        if (supplier == null) throw new IllegalArgumentException("supplier must not be null");
        if (routing == null) throw new IllegalArgumentException("routing must not be null");
        if (group == null) throw new IllegalArgumentException("group must not be null");
        if (!routing.groupUsesDeconv(group)) {
            return resolveRoutedInputLocked(rootDir, supplier, sourceContainer, imageBaseName,
                    seriesIndex, routing, group, expectedParams, zStart, zEnd, null);
        }
        String baseName = imageBaseName == null ? "" : imageBaseName.trim();
        DeconvManifest.SourceFingerprint fingerprint = DeconvManifest.SourceFingerprint.of(sourceContainer);
        int sourceSeriesIndex = supplier.getLocalSeriesIndexForSeries(seriesIndex);
        DeconvolutionIO.ArtifactIdentity identity = DeconvolutionIO.ArtifactIdentity.of(
                rootDir, sourceContainer, fingerprint, sourceSeriesIndex, baseName);
        try (DeconvolutionFamilyLock.Handle ignored =
                     DeconvolutionIO.lockFamilyForAccess(rootDir, identity)) {
            return resolveRoutedInputLocked(rootDir, supplier, sourceContainer, imageBaseName,
                    seriesIndex, routing, group, expectedParams, zStart, zEnd, identity);
        }
    }

    private static ImagePlus resolveRoutedInputLocked(File rootDir,
                                               DeferredImageSupplier supplier,
                                               File sourceContainer,
                                               String imageBaseName,
                                               int seriesIndex,
                                               DeconvRouting routing,
                                               DeconvRoutingGroup group,
                                               ExpectedDeconvParams expectedParams,
                                               int zStart,
                                               int zEnd,
                                               DeconvolutionIO.ArtifactIdentity identity) throws Exception {
        if (supplier == null) throw new IllegalArgumentException("supplier must not be null");
        if (routing == null) throw new IllegalArgumentException("routing must not be null");
        if (group == null) throw new IllegalArgumentException("group must not be null");

        String baseName = imageBaseName == null ? "" : imageBaseName.trim();
        String label = labelFor(sourceContainer, baseName);

        // Case 1: the group reads no deconvolved channel at all -> native raw series (no float
        // promotion), Z-subset only. Preserves today's raw behaviour for a raw-routed group.
        if (!routing.groupUsesDeconv(group)) {
            log("[Deconv] " + label + " (" + group + "): raw routing - using raw series");
            ImagePlus raw = supplier.openSeriesMaterialized(seriesIndex);
            if (raw == null) {
                throw new IOException("Could not open source series " + seriesIndex);
            }
            return cropZClosingOriginal(raw, zStart, zEnd);
        }

        // Case 2: default routing + fresh merged mirror -> cheap whole-file open.
        File mergedFast = fastPathMergedFile(rootDir, baseName, sourceContainer, routing, group,
                expectedParams, identity);
        if (mergedFast != null) {
            ImagePlus merged = new Opener().openImage(mergedFast.getAbsolutePath());
            if (merged != null) {
                log("[Deconv] " + label + " (" + group + "): default routing - using merged deconvolved stack");
                return cropZClosingOriginal(merged, zStart, zEnd);
            }
            log("[Deconv] " + label + " (" + group + "): merged open failed - composing per channel");
        }

        // Case 3: per-channel composition. Decide the per-channel mirror-vs-raw vector from the
        // CONFIG channel count + manifest FIRST — without materializing the source — so a group that
        // routes to DECONV but has no fresh mirror on disk costs nothing to discover.
        int configChannels = routing.channelCount();
        DeconvManifest.SourceFingerprint fingerprint = fingerprintQuietly(sourceContainer);
        File manifestFile = identity == null
                ? DeconvolutionIO.manifestFile(rootDir, baseName)
                : DeconvolutionIO.manifestFile(rootDir, identity);

        boolean[] usedDeconv = new boolean[configChannels];
        long[] mirrorMtimes = new long[configChannels];
        File[] mirrorFiles = new File[configChannels];
        boolean anyDeconv = false;
        for (int c = 0; c < configChannels; c++) {
            if (routing.usesDeconv(group, c)) {
                File mirror = DeconvolutionIO.firstExistingFile(
                        identity == null
                                ? DeconvolutionIO.deconvFileReadCandidates(rootDir, baseName, c)
                                : DeconvolutionIO.deconvFileReadCandidates(rootDir, identity, c,
                                        baseName, DeconvolutionIO.LegacyBasenamePolicy.MIGRATE_IF_UNIQUE, 1));
                if (mirror != null
                        && (identity == null
                        ? DeconvManifest.isFreshForParams(manifestFile, c,
                                expectedParams == null ? null : expectedParams.forChannel(c), fingerprint)
                        : DeconvManifest.load(manifestFile).isChannelFreshForParams(c,
                                expectedParams == null ? null : expectedParams.forChannel(c),
                                fingerprint, identity))) {
                    usedDeconv[c] = true;
                    mirrorFiles[c] = mirror;
                    mirrorMtimes[c] = mirror.lastModified();
                    anyDeconv = true;
                } else {
                    log("[Deconv] " + label + " (" + group + ") ch" + c + ": mirror "
                            + (mirror == null ? "missing" : "stale") + " - raw fallback");
                }
            }
        }

        // Byte-identity guard (Stage 15): no routed channel has a fresh mirror, so there is nothing
        // to compose. Return null so the caller falls back to its native raw open — preserving the
        // raw bit depth (no 32-bit float promotion) and its virtual/materialized choice. This keeps a
        // legacy / fully-raw run byte-identical even when the whole-image legacy toggle routed every
        // channel to DECONV.
        if (!anyDeconv) {
            log("[Deconv] " + label + " (" + group
                    + "): no fresh mirror for any routed channel - raw fallback");
            return null;
        }

        // At least one fresh mirror -> assemble. Materialize the source now (raw channels + sizeC +
        // calibration) and validate the config/source channel count agree (fail loud, never min()).
        RoutedInputComposer.RawSeriesSource source = RoutedInputComposer.forSupplier(supplier, seriesIndex);
        try {
            int sizeC = source.sizeC();
            if (configChannels != sizeC) {
                throw new IllegalStateException("Channel count mismatch: project config declares "
                        + configChannels + " channel(s) but source series " + seriesIndex + " has "
                        + sizeC + ". Re-run Set Up Configuration for this dataset.");
            }
            int rawDepth = source.sizeZ();

            long srcSize = sourceContainer == null ? -1L : sourceContainer.length();
            long srcMtime = sourceContainer == null ? -1L : sourceContainer.lastModified();
            RoutedInputComposer.ComposeKey lookupKey = new RoutedInputComposer.ComposeKey(
                    rootDir, baseName, group.name(), seriesIndex, srcSize, srcMtime,
                    usedDeconv, mirrorMtimes, zStart, zEnd);
            ImagePlus cached = RoutedInputComposer.getCached(lookupKey);
            if (cached != null) {
                log("[Deconv] " + label + " (" + group + "): composed (cache hit) " + describe(usedDeconv));
                return cached;
            }

            ImagePlus[] channels = new ImagePlus[sizeC];
            try {
                for (int c = 0; c < sizeC; c++) {
                    if (usedDeconv[c]) {
                        ImagePlus mirror = new Opener().openImage(mirrorFiles[c].getAbsolutePath());
                        // Reject a mirror longer than its raw sibling as stale -> raw fallback for
                        // this one channel only.
                        if (mirror == null || mirror.getStackSize() > rawDepth) {
                            if (mirror != null) {
                                log("[Deconv] " + label + " (" + group + ") ch" + c
                                        + ": mirror deeper than raw (" + mirror.getStackSize() + " > "
                                        + rawDepth + ") - stale, raw fallback");
                            }
                            RoutedInputComposer.closeQuietly(mirror);
                            usedDeconv[c] = false;
                            mirrorMtimes[c] = 0L;
                            channels[c] = source.openRawChannel(c);
                        } else {
                            channels[c] = mirror;
                        }
                    } else {
                        channels[c] = source.openRawChannel(c);
                    }
                    if (channels[c] == null) {
                        throw new IOException("Could not open channel " + c + " for composition");
                    }
                }

                String title = baseName.isEmpty() ? "composed" : baseName + "_composed";
                Calibration cal = source.calibration();
                ImagePlus assembled = RoutedInputComposer.compose(title, channels, configChannels, cal,
                        zStart, zEnd);

                // Recompute the key: a rejected mirror flips a decision, so cache under what was
                // actually assembled.
                RoutedInputComposer.ComposeKey storeKey = new RoutedInputComposer.ComposeKey(
                        rootDir, baseName, group.name(), seriesIndex, srcSize, srcMtime,
                        usedDeconv, mirrorMtimes, zStart, zEnd);
                RoutedInputComposer.putCached(storeKey, assembled);
                log("[Deconv] " + label + " (" + group + "): composed " + describe(usedDeconv));
                return assembled;
            } finally {
                for (int c = 0; c < channels.length; c++) {
                    RoutedInputComposer.closeQuietly(channels[c]);
                }
            }
        } finally {
            source.close();
        }
    }

    /**
     * The merged {@code _deconv.tif} to use for the cheap fast path, or {@code null} if per-channel
     * composition is required. Applies only when the group's vector equals the plain opt-in default AND
     * the merged file is provably current.
     *
     * <p>Freshness is decided from the manifest's dedicated <b>merged record</b> (content-based), not
     * from mtimes or from the per-channel entries alone. The merged file has no fingerprint of its own,
     * so a failed/partial merge rewrite, a preserved cache-copy timestamp, or a Dropbox mtime quirk
     * could otherwise leave a stale {@code _deconv.tif} that the per-channel manifest would wrongly
     * vouch for. The merged record ties the merged file to the exact per-channel params-hashes it was
     * composed from; if any routed channel's mirror advanced without the merge being rewritten, the
     * record no longer matches and this returns {@code null} so the consumer composes per-channel from
     * the individually-verified mirrors. Every routed-DECONV channel's {@code _C*.tif} must also be
     * present (matching the preflight's file-presence check, so the two never disagree). Legacy
     * pre-manifest mirrors (empty manifest) keep the content-fingerprint / mtime fallback.</p>
     */
    static File fastPathMergedFile(File rootDir,
                                   String imageBaseName,
                                   File sourceContainer,
                                   DeconvRouting routing,
                                   DeconvRoutingGroup group,
                                   ExpectedDeconvParams expectedParams) {
        return fastPathMergedFile(rootDir, imageBaseName, sourceContainer, routing, group,
                expectedParams, null);
    }

    private static File fastPathMergedFile(File rootDir,
                                   String imageBaseName,
                                   File sourceContainer,
                                   DeconvRouting routing,
                                   DeconvRoutingGroup group,
                                   ExpectedDeconvParams expectedParams,
                                   DeconvolutionIO.ArtifactIdentity identity) {
        if (rootDir == null || routing == null || group == null) return null;
        if (!routing.isGroupDefault(group)) return null;
        String baseName = imageBaseName == null ? "" : imageBaseName.trim();
        if (baseName.isEmpty()) return null;
        File merged = DeconvolutionIO.firstExistingFile(
                identity == null
                        ? DeconvolutionIO.mergedDeconvFileReadCandidates(rootDir, baseName)
                        : DeconvolutionIO.mergedDeconvFileReadCandidates(rootDir, identity, baseName,
                                DeconvolutionIO.LegacyBasenamePolicy.MIGRATE_IF_UNIQUE, 1));
        if (merged == null) return null;
        if (sourceContainer == null) {
            // No source to verify against; existence is the best we can do.
            return merged.isFile() ? merged : null;
        }

        File manifestFile = identity == null
                ? DeconvolutionIO.manifestFile(rootDir, baseName)
                : DeconvolutionIO.manifestFile(rootDir, identity);
        if (!manifestFile.isFile()) {
            // Truly legacy pre-manifest mirror (no sidecar at all): content-fingerprint / mtime fallback.
            // A present-but-empty/malformed manifest is deliberately NOT treated as legacy — it must earn
            // the fast path through the content merged record below, else per-channel composition (which
            // raw-falls-back safely) — so a corrupt sidecar can never serve a stale merged file by mtime.
            return mergedMirrorFresh(rootDir, baseName, sourceContainer, merged) ? merged : null;
        }
        DeconvManifest manifest = DeconvManifest.load(manifestFile);

        // Require every routed-DECONV channel's mirror file to be present (parity with the preflight),
        // and collect the required-channel set for the content check.
        List<Integer> requiredChannels = new java.util.ArrayList<Integer>();
        int channelCount = routing.channelCount();
        for (int c = 0; c < channelCount; c++) {
            if (routing.usesDeconv(group, c)) {
                File mirror = DeconvolutionIO.firstExistingFile(
                        identity == null
                                ? DeconvolutionIO.deconvFileReadCandidates(rootDir, baseName, c)
                                : DeconvolutionIO.deconvFileReadCandidates(rootDir, identity, c,
                                        baseName, DeconvolutionIO.LegacyBasenamePolicy.MIGRATE_IF_UNIQUE, 1));
                if (mirror == null) {
                    return null; // a routed channel mirror is missing -> compose per channel
                }
                requiredChannels.add(Integer.valueOf(c));
            }
        }

        DeconvManifest.SourceFingerprint fingerprint = fingerprintQuietly(sourceContainer);
        return (identity == null
                ? manifest.isMergedFresh(fingerprint, requiredChannels, expectedParams)
                : manifest.isMergedFresh(fingerprint, requiredChannels, expectedParams, identity))
                ? merged : null;
    }

    /** Apply a Z-subset crop, closing the full image when the crop produced a distinct instance. */
    private static ImagePlus cropZClosingOriginal(ImagePlus full, int zStart, int zEnd) {
        ImagePlus cropped = RoutedInputComposer.cropZ(full, zStart, zEnd);
        if (cropped != full) {
            RoutedInputComposer.closeQuietly(full);
        }
        return cropped;
    }

    private static DeconvManifest.SourceFingerprint fingerprintQuietly(File source) {
        try {
            return DeconvManifest.SourceFingerprint.of(source);
        } catch (IOException e) {
            return null;
        }
    }

    private static String describe(boolean[] usedDeconv) {
        StringBuilder sb = new StringBuilder("[");
        for (int c = 0; c < usedDeconv.length; c++) {
            if (c > 0) sb.append(',');
            sb.append(usedDeconv[c] ? "deconv" : "raw");
        }
        return sb.append(']').toString();
    }

    static void setLogSinkForTest(LogSink sink) {
        if (sink != null) {
            logSink = sink;
        }
    }

    static void resetForTest() {
        logSink = new LogSink() {
            @Override
            public void log(String message) {
                IJ.log(message);
            }
        };
    }

    /**
     * Content-based freshness for the merged mirror. When a freshness manifest is present
     * (written by {@code DeconvolutionAnalysis} since the manifest feature landed) the mirror
     * is fresh iff every recorded channel's source fingerprint still matches the current raw
     * source — this is Dropbox-safe in both directions, unlike the mtime comparison it replaces.
     * Legacy mirrors written before manifests existed fall back to the mtime check so they are
     * not needlessly invalidated.
     */
    private static boolean mergedMirrorFresh(File rootDir, String imageBaseName, File source, File mirror) {
        DeconvManifest manifest = DeconvManifest.load(DeconvolutionIO.manifestFile(rootDir, imageBaseName));
        if (manifest.isEmpty()) {
            return mirror.lastModified() >= source.lastModified();
        }
        try {
            return manifest.sourceMatchesAll(DeconvManifest.SourceFingerprint.of(source));
        } catch (java.io.IOException e) {
            return mirror.lastModified() >= source.lastModified();
        }
    }

    private static String labelFor(File originalSource, String imageBaseName) {
        String baseName = imageBaseName == null ? "" : imageBaseName.trim();
        if (!baseName.isEmpty()) return baseName;
        return originalSource == null ? "image" : originalSource.getName();
    }

    private static void log(String message) {
        logSink.log(message);
    }

    private static List<File> mirrorReadCandidates(File rootDir, String imageBaseName) {
        String baseName = imageBaseName == null ? "" : imageBaseName.trim();
        if (rootDir == null || baseName.isEmpty()) {
            return java.util.Collections.emptyList();
        }
        return DeconvolutionIO.mergedDeconvFileReadCandidates(rootDir, baseName);
    }

    interface LogSink {
        void log(String message);
    }
}
