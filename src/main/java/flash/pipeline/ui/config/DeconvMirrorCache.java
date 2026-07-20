package flash.pipeline.ui.config;

import flash.pipeline.deconv.DeconvManifest;
import flash.pipeline.deconv.DeconvolutionIO;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

/**
 * Thread-safe shared cache of the full-Z deconvolution mirror produced for each
 * {@code (seriesIndex, channelIndex)} during the Set&nbsp;Up&nbsp;Configuration QC pass (Stage&nbsp;12).
 *
 * <p>When a channel's deconvolution settings are confirmed, its QC images are deconvolved once
 * (full-Z, from disk) and the resulting flat mirror files are registered here. Stage&nbsp;13 threads
 * this same instance into the downstream setup steps' {@link ConfigQcContext} so those previews show
 * the exact routed pixels (never raw while routed to deconvolved) — see {@code 12_setup_qc_deconvolution.md}
 * and {@code 13_setup_per_group_preview_seam.md}.</p>
 *
 * <p>A <b>raw-fallback</b> entry ({@link Mirror#rawFallback} = {@code true}, {@link Mirror#mirrorFile}
 * = {@code null}) records that the QC pass could <em>not</em> deconvolve that channel (memory shortage,
 * PSF-synthesis failure, or user cancel). It is deliberately distinct from a missing entry so a
 * consumer can tell "not computed" from "computed to raw on purpose" and surface a visible warning
 * instead of silently previewing raw.</p>
 */
public final class DeconvMirrorCache {

    /** Immutable per-(series,channel) mirror record. */
    public static final class Mirror {
        /** The flat full-Z deconvolution mirror on disk, or {@code null} for a raw fallback. */
        public final File mirrorFile;
        /** The params hash the mirror was produced with (empty for a raw fallback). */
        public final String paramsHash;
        /** True when deconvolution was skipped and downstream must use raw pixels. */
        public final boolean rawFallback;
        /** Project root and exact family identity required for lock-scoped revalidation/open. */
        public final File rootDir;
        public final DeconvolutionIO.ArtifactIdentity artifactIdentity;
        public final DeconvManifest.SourceFingerprint sourceFingerprint;
        public final int channelIndex;

        public Mirror(File mirrorFile, String paramsHash, boolean rawFallback) {
            this(mirrorFile, paramsHash, rawFallback, null, null, null, -1);
        }

        public Mirror(File mirrorFile, String paramsHash, boolean rawFallback,
                      File rootDir, DeconvolutionIO.ArtifactIdentity artifactIdentity,
                      DeconvManifest.SourceFingerprint sourceFingerprint, int channelIndex) {
            this.mirrorFile = mirrorFile;
            this.paramsHash = paramsHash == null ? "" : paramsHash;
            this.rawFallback = rawFallback;
            this.rootDir = rootDir;
            this.artifactIdentity = artifactIdentity;
            this.sourceFingerprint = sourceFingerprint;
            this.channelIndex = channelIndex;
        }

        public static Mirror deconvolved(File mirrorFile, String paramsHash) {
            return new Mirror(mirrorFile, paramsHash, false);
        }

        public static Mirror deconvolved(File mirrorFile, String paramsHash, File rootDir,
                                         DeconvolutionIO.ArtifactIdentity artifactIdentity,
                                         DeconvManifest.SourceFingerprint sourceFingerprint,
                                         int channelIndex) {
            return new Mirror(mirrorFile, paramsHash, false, rootDir, artifactIdentity,
                    sourceFingerprint, channelIndex);
        }

        public static Mirror rawFallback() {
            return new Mirror(null, "", true);
        }
    }

    private final Map<Long, Mirror> mirrors = new HashMap<Long, Mirror>();

    /** Register (or, with a {@code null} mirror, clear) the record for one series/channel. */
    public synchronized void put(int seriesIndex, int channelIndex, Mirror mirror) {
        Long key = key(seriesIndex, channelIndex);
        if (mirror == null) {
            mirrors.remove(key);
        } else {
            mirrors.put(key, mirror);
        }
    }

    /** The mirror record for a series/channel, or {@code null} when none was computed. */
    public synchronized Mirror get(int seriesIndex, int channelIndex) {
        return mirrors.get(key(seriesIndex, channelIndex));
    }

    public synchronized void clear() {
        mirrors.clear();
    }

    public synchronized int sizeForTest() {
        return mirrors.size();
    }

    private static Long key(int seriesIndex, int channelIndex) {
        return Long.valueOf(((long) seriesIndex << 32) ^ (channelIndex & 0xffffffffL));
    }
}
