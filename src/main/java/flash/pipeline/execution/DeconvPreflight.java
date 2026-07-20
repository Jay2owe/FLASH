package flash.pipeline.execution;

import flash.pipeline.bin.ChannelConfig;
import flash.pipeline.deconv.DeconvManifest;
import flash.pipeline.deconv.DeconvolutionIO;
import flash.pipeline.deconv.ExpectedDeconvParams;
import flash.pipeline.deconv.routing.DeconvConfigBridge;
import flash.pipeline.deconv.routing.DeconvRouting;
import flash.pipeline.deconv.routing.DeconvRoutingGroup;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Pure decision core for the Stage-17 deconvolution autorun preflight.
 *
 * <p>Before a pixel-consuming analysis runs, {@link AnalysisRunCoordinator} asks this class whether
 * the routed channels for that analysis's {@link DeconvRoutingGroup} have fresh deconvolved mirrors
 * on disk. When one is missing or stale a consumer would silently measure/render on raw; the
 * coordinator turns that into a loud, layered response (headed offer / headless warn / strict
 * hard-fail).</p>
 *
 * <p>The whole class is deliberately ImageJ-free: freshness is read from the flat mirror files plus
 * the Stage-01 {@link DeconvManifest} sidecar (both ImageJ-free), and the exempt gate delegates to
 * {@link DeconvRoutingGroup#groupFor(int)} — the single authoritative analysis&rarr;group source, so
 * no exempt list is duplicated here. This makes the decision unit-testable without booting Fiji,
 * showing a dialog, or opening Bio-Formats: the caller enumerates the project's series (base name +
 * raw source container) and passes them in.</p>
 *
 * <p><b>Freshness criterion.</b> A routed channel is fresh iff its {@code _C{idx}.tif} mirror exists
 * and the manifest records a matching source fingerprint AND (when the caller supplies the per-channel
 * {@link ExpectedDeconvParams}) the mirror's recorded deconvolution parameters still match the
 * persisted config. The coordinator threads the same {@link ExpectedDeconvParams} it derives from the
 * config into both this preflight and the routed consumers (via {@code resolveRoutedInput}), so the
 * preflight and the consumer never disagree about whether a mirror is fresh — including after a
 * parameter change that left the source bytes unchanged.</p>
 */
public final class DeconvPreflight {

    private DeconvPreflight() {}

    /** What the coordinator should do about a routed analysis's deconvolution mirrors. */
    public enum Decision {
        /** Exempt / no routing keys / group routes nothing to deconv / all mirrors fresh. */
        NO_OP,
        /** Headless (or suppressed): missing/stale mirror + {@code requireFresh=false} — warn, run raw. */
        PROCEED_ON_RAW,
        /** Headless (or suppressed): missing/stale mirror + {@code requireFresh=true} — hard-fail. */
        HARD_FAIL,
        /** Headed: missing/stale mirror — the caller must prompt (deconvolve now / use raw / cancel). */
        PROMPT
    }

    /** One project series: the base name the mirror files are keyed on + the raw source container. */
    public static final class SeriesRef {
        public final String baseName;
        public final File sourceFile;
        public final DeconvolutionIO.ArtifactIdentity artifactIdentity;

        public SeriesRef(String baseName, File sourceFile) {
            this(baseName, sourceFile, null);
        }

        public SeriesRef(String baseName, File sourceFile,
                         DeconvolutionIO.ArtifactIdentity artifactIdentity) {
            this.baseName = baseName;
            this.sourceFile = sourceFile;
            this.artifactIdentity = artifactIdentity;
        }
    }

    /** One routed channel whose deconvolved mirror is missing or stale. */
    public static final class MissingMirror {
        public final String baseName;
        public final int channel;
        /** {@code true} when a mirror exists but is stale; {@code false} when it is absent. */
        public final boolean stale;

        MissingMirror(String baseName, int channel, boolean stale) {
            this.baseName = baseName;
            this.channel = channel;
            this.stale = stale;
        }

        @Override
        public String toString() {
            return baseName + " ch" + channel + (stale ? " (stale)" : " (missing)");
        }
    }

    /**
     * The effective routing for a group iff the analysis is routed AND the config carries per-channel
     * routing keys AND the group reads a deconvolved channel; otherwise {@code null} (the preflight
     * stays out of the way). Cheap: no series enumeration, no disk scan.
     */
    public static DeconvRouting routedGroupRouting(Optional<DeconvRoutingGroup> group,
                                                   ChannelConfig config) {
        if (group == null || !group.isPresent()) {
            return null;
        }
        // Legacy projects (no per-channel routing keys) keep their existing whole-image behaviour;
        // the preflight only engages for the new deconv-into-setup routing model.
        if (!DeconvConfigBridge.hasRoutingKeys(config)) {
            return null;
        }
        DeconvRouting routing = DeconvConfigBridge.routingFrom(config);
        return routing.groupUsesDeconv(group.get()) ? routing : null;
    }

    /**
     * Scan every series &times; routed-DECONV channel for a fresh mirror. Pure: reads only the flat
     * mirror file + the manifest sidecar (both ImageJ-free); never opens pixels or Bio-Formats.
     */
    public static List<MissingMirror> scan(File rootDir,
                                           DeconvRouting routing,
                                           DeconvRoutingGroup group,
                                           List<SeriesRef> series,
                                           ExpectedDeconvParams expectedParams) {
        List<MissingMirror> missing = new ArrayList<MissingMirror>();
        if (rootDir == null || routing == null || group == null || series == null) {
            return missing;
        }
        int channels = routing.channelCount();
        for (SeriesRef ref : series) {
            if (ref == null || ref.baseName == null || ref.baseName.trim().isEmpty()) {
                continue;
            }
            String baseName = ref.baseName.trim();
            try (flash.pipeline.deconv.DeconvolutionFamilyLock.Handle ignored =
                         ref.artifactIdentity == null ? null
                                 : DeconvolutionIO.lockFamilyForAccess(rootDir, ref.artifactIdentity)) {
                File manifestFile = ref.artifactIdentity == null
                        ? DeconvolutionIO.manifestFile(rootDir, baseName)
                        : DeconvolutionIO.manifestFile(rootDir, ref.artifactIdentity);
                DeconvManifest.SourceFingerprint fingerprint = fingerprintQuietly(ref.sourceFile);
                for (int c = 0; c < channels; c++) {
                    if (!routing.usesDeconv(group, c)) continue;
                    File mirror = DeconvolutionIO.firstExistingFile(ref.artifactIdentity == null
                            ? DeconvolutionIO.deconvFileReadCandidates(rootDir, baseName, c)
                            : DeconvolutionIO.deconvFileReadCandidates(rootDir, ref.artifactIdentity,
                                    c, baseName, DeconvolutionIO.LegacyBasenamePolicy.REJECT, 0));
                    boolean fresh = mirror != null && (ref.artifactIdentity == null
                            ? DeconvManifest.isFreshForParams(manifestFile, c,
                                    expectedParams == null ? null : expectedParams.forChannel(c), fingerprint)
                            : DeconvManifest.load(manifestFile).isChannelFreshForParams(c,
                                    expectedParams == null ? null : expectedParams.forChannel(c),
                                    fingerprint, ref.artifactIdentity));
                    if (!fresh) missing.add(new MissingMirror(baseName, c, mirror != null));
                }
            } catch (IOException lockOrRecoveryFailure) {
                for (int c = 0; c < channels; c++) {
                    if (routing.usesDeconv(group, c)) {
                        missing.add(new MissingMirror(baseName, c, false));
                    }
                }
            }
        }
        return missing;
    }

    /**
     * The full preflight decision for one routed consumer. Pure and ImageJ-free given the enumerated
     * {@code series}, so it is unit-testable without booting Fiji or showing a dialog.
     *
     * @param group             the analysis's routing group (from {@link DeconvRoutingGroup#groupFor})
     * @param config            the persisted lenient channel config (nullable)
     * @param rootDir           project root (locates mirror + manifest sidecars)
     * @param series            the project's enumerated series (base name + raw source)
     * @param expectedParams    per-channel config-derived params to verify, or {@code null} for
     *                          source-fingerprint-only freshness
     * @param headless          {@code true} for CLI/headless/suppressed runs (never blocks)
     * @param requireFresh      {@code true} to hard-fail headless when a mirror is missing/stale
     */
    public static Decision decide(Optional<DeconvRoutingGroup> group,
                                  ChannelConfig config,
                                  File rootDir,
                                  List<SeriesRef> series,
                                  ExpectedDeconvParams expectedParams,
                                  boolean headless,
                                  boolean requireFresh) {
        DeconvRouting routing = routedGroupRouting(group, config);
        if (routing == null) {
            return Decision.NO_OP;
        }
        List<MissingMirror> missing = scan(rootDir, routing, group.get(), series, expectedParams);
        if (missing.isEmpty()) {
            return Decision.NO_OP;
        }
        if (headless) {
            return requireFresh ? Decision.HARD_FAIL : Decision.PROCEED_ON_RAW;
        }
        return Decision.PROMPT;
    }

    private static DeconvManifest.SourceFingerprint fingerprintQuietly(File source) {
        try {
            return DeconvManifest.SourceFingerprint.of(source);
        } catch (IOException e) {
            return null;
        }
    }
}
