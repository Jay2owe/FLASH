package flash.pipeline.execution;

import flash.pipeline.bin.ChannelConfig;
import flash.pipeline.deconv.DeconvManifest;
import flash.pipeline.deconv.DeconvParamsHash;
import flash.pipeline.deconv.DeconvolutionIO;
import flash.pipeline.deconv.ExpectedDeconvParams;
import flash.pipeline.deconv.engine.DeconvSettings;
import flash.pipeline.deconv.psf.ScopeModality;
import flash.pipeline.deconv.routing.DeconvConfigBridge;
import flash.pipeline.deconv.routing.DeconvRoutingGroup;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.Assert.assertEquals;

/**
 * Unit tests for the Stage-17 deconvolution preflight decision core. These drive
 * {@link DeconvPreflight#decide} directly — no ImageJ, no Bio-Formats, no dialog — by fabricating a
 * lenient {@link ChannelConfig} in memory and (for the fresh case) writing a mirror + manifest sidecar
 * to a temp project tree.
 */
public class DeconvPreflightTest {

    @Rule
    public final TemporaryFolder tmp = new TemporaryFolder();

    private static final int IDX_3D_OBJECT = 4;   // routed -> ANALYSIS group
    private static final int IDX_AGGREGATION = 8;  // exempt

    /** Two channels, ch0 opted in to deconvolution + routed DECONV for both groups; ch1 plain. */
    private static ChannelConfig routedConfig() {
        ChannelConfig cfg = new ChannelConfig();
        ChannelConfig.Channel c0 = new ChannelConfig.Channel();
        c0.index = 0;
        c0.name = "C0";
        c0.deconvEngineKey = "CLIJ2";
        c0.deconvAlgorithm = "RL_TV";
        c0.deconvPsfModel = "GIBSON_LANNI";
        c0.deconvIterations = Integer.valueOf(20);
        c0.deconvRegularization = Double.valueOf(0.002);
        c0.emissionWavelengthNm = Double.valueOf(520.0);
        c0.routeAnalysis = "deconv";
        c0.routeDisplay = "deconv";
        cfg.channels.add(c0);
        ChannelConfig.Channel c1 = new ChannelConfig.Channel();
        c1.index = 1;
        c1.name = "C1";
        cfg.channels.add(c1);
        return cfg;
    }

    private File writeSource(String name) throws Exception {
        File src = tmp.newFile(name);
        Files.write(src.toPath(), "raw-pixels".getBytes(StandardCharsets.UTF_8));
        return src;
    }

    private List<DeconvPreflight.SeriesRef> oneSeries(String baseName, File source) {
        return Collections.singletonList(new DeconvPreflight.SeriesRef(baseName, source));
    }

    @Test
    public void exemptAnalysisIsAlwaysNoOp() throws Exception {
        File root = tmp.getRoot();
        File source = writeSource("img.tif");
        // Even with a fully-routed config and no mirrors on disk, an exempt group short-circuits.
        DeconvPreflight.Decision decision = DeconvPreflight.decide(
                DeconvRoutingGroup.groupFor(IDX_AGGREGATION),
                routedConfig(), root, oneSeries("img", source),
                /*expectedParamsHash*/ null, /*headless*/ true, /*requireFresh*/ false);
        assertEquals(DeconvPreflight.Decision.NO_OP, decision);
    }

    @Test
    public void routedMissingMirrorHeadlessProceedsOnRaw() throws Exception {
        File root = tmp.getRoot();
        File source = writeSource("img.tif");
        // No mirror written -> the routed ANALYSIS channel is missing.
        DeconvPreflight.Decision decision = DeconvPreflight.decide(
                DeconvRoutingGroup.groupFor(IDX_3D_OBJECT),
                routedConfig(), root, oneSeries("img", source),
                null, /*headless*/ true, /*requireFresh*/ false);
        assertEquals(DeconvPreflight.Decision.PROCEED_ON_RAW, decision);
    }

    @Test
    public void routedMissingMirrorHeadlessRequireFreshHardFails() throws Exception {
        File root = tmp.getRoot();
        File source = writeSource("img.tif");
        DeconvPreflight.Decision decision = DeconvPreflight.decide(
                DeconvRoutingGroup.groupFor(IDX_3D_OBJECT),
                routedConfig(), root, oneSeries("img", source),
                null, /*headless*/ true, /*requireFresh*/ true);
        assertEquals(DeconvPreflight.Decision.HARD_FAIL, decision);
    }

    @Test
    public void routedMissingMirrorHeadedPrompts() throws Exception {
        File root = tmp.getRoot();
        File source = writeSource("img.tif");
        DeconvPreflight.Decision decision = DeconvPreflight.decide(
                DeconvRoutingGroup.groupFor(IDX_3D_OBJECT),
                routedConfig(), root, oneSeries("img", source),
                null, /*headless*/ false, /*requireFresh*/ false);
        assertEquals(DeconvPreflight.Decision.PROMPT, decision);
    }

    @Test
    public void freshMirrorIsNoOpEvenWhenRequireFresh() throws Exception {
        File root = tmp.getRoot();
        File source = writeSource("img.tif");
        String baseName = "img";

        // Write a per-channel mirror + a manifest whose source fingerprint matches the raw source.
        File mirror = DeconvolutionIO.deconvFile(root, baseName, 0);
        Files.createDirectories(mirror.getParentFile().toPath());
        Files.write(mirror.toPath(), "deconvolved".getBytes(StandardCharsets.UTF_8));

        DeconvManifest.SourceFingerprint fingerprint = DeconvManifest.SourceFingerprint.of(source);
        DeconvManifest manifest = DeconvManifest.empty().withChannel(0,
                new DeconvManifest.ChannelEntry("HASH", null, fingerprint, "CLIJ2", "1", 12));
        DeconvManifest.writeAtomic(DeconvolutionIO.manifestFile(root, baseName), manifest);

        // Source-fingerprint-only (expectedParamsHash == null): a matching fingerprint is fresh, so
        // even a strict requireFresh run is a no-op.
        DeconvPreflight.Decision decision = DeconvPreflight.decide(
                DeconvRoutingGroup.groupFor(IDX_3D_OBJECT),
                routedConfig(), root, oneSeries(baseName, source),
                null, /*headless*/ true, /*requireFresh*/ true);
        assertEquals(DeconvPreflight.Decision.NO_OP, decision);
    }

    @Test
    public void noRoutingKeysIsNoOp() throws Exception {
        File root = tmp.getRoot();
        File source = writeSource("img.tif");
        // A legacy config with deconv opt-in stripped of routing keys -> preflight stays out.
        ChannelConfig legacy = new ChannelConfig();
        ChannelConfig.Channel c0 = new ChannelConfig.Channel();
        c0.index = 0;
        c0.name = "C0";
        legacy.channels.add(c0);
        DeconvPreflight.Decision decision = DeconvPreflight.decide(
                DeconvRoutingGroup.groupFor(IDX_3D_OBJECT),
                legacy, root, oneSeries("img", source),
                null, /*headless*/ true, /*requireFresh*/ true);
        assertEquals(DeconvPreflight.Decision.NO_OP, decision);
    }

    @Test
    public void nullGroupIsNoOp() throws Exception {
        File root = tmp.getRoot();
        File source = writeSource("img.tif");
        DeconvPreflight.Decision decision = DeconvPreflight.decide(
                Optional.<DeconvRoutingGroup>empty(),
                routedConfig(), root, oneSeries("img", source),
                null, true, true);
        assertEquals(DeconvPreflight.Decision.NO_OP, decision);
    }

    // ---- params-staleness (Stage 18, Limitation 1) ---------------------

    private static final int GEOM_X = 256;
    private static final int GEOM_Y = 256;
    private static final int GEOM_Z = 24;

    /** Two channels, ch0 opted in with COMPLETE optics (so expectedParamsFor produces params). */
    private static ChannelConfig opticsRoutedConfig() {
        ChannelConfig cfg = routedConfig();
        ChannelConfig.DeconvOptics optics = new ChannelConfig.DeconvOptics();
        optics.na = Double.valueOf(1.40);
        optics.immersionRi = Double.valueOf(1.515);
        optics.sampleRi = Double.valueOf(1.44);
        optics.scopeModality = "WIDEFIELD";
        cfg.deconvOptics = optics;
        return cfg;
    }

    /** Write ch0's mirror + a manifest whose recorded params come from {@code cfg}'s optics/settings. */
    private void writeFreshMirror(File root, String baseName, ChannelConfig cfg, File source)
            throws Exception {
        File mirror = DeconvolutionIO.deconvFile(root, baseName, 0);
        Files.createDirectories(mirror.getParentFile().toPath());
        Files.write(mirror.toPath(), "deconvolved".getBytes(StandardCharsets.UTF_8));

        DeconvManifest.SourceFingerprint fp = DeconvManifest.SourceFingerprint.of(source);
        DeconvSettings settings = DeconvConfigBridge.settingsFor(cfg, 0);
        ChannelConfig.DeconvOptics optics = cfg.deconvOptics;
        ScopeModality modality = DeconvConfigBridge.modalityFrom(cfg);
        Map<String, String> hashParams = DeconvParamsHash.buildParams(
                settings, modality,
                optics.na.doubleValue(), optics.immersionRi.doubleValue(), optics.sampleRi.doubleValue(),
                optics.pinholeAiryUnits, cfg.channels.get(0).emissionWavelengthNm.doubleValue(),
                0.09, 0.30, GEOM_X, GEOM_Y, GEOM_Z);
        DeconvManifest manifest = DeconvManifest.empty().withChannel(0,
                new DeconvManifest.ChannelEntry(DeconvolutionIO.paramsHash(hashParams), hashParams, fp,
                        "CLIJ2", DeconvManifest.ENGINE_STAMP_VERSION, GEOM_Z));
        DeconvManifest.writeAtomic(DeconvolutionIO.manifestFile(root, baseName), manifest);
    }

    @Test
    public void freshMirrorWithMatchingParamsIsNoOp() throws Exception {
        File root = tmp.getRoot();
        File source = writeSource("img.tif");
        ChannelConfig cfg = opticsRoutedConfig();
        writeFreshMirror(root, "img", cfg, source);

        DeconvPreflight.Decision decision = DeconvPreflight.decide(
                DeconvRoutingGroup.groupFor(IDX_3D_OBJECT),
                cfg, root, oneSeries("img", source),
                DeconvConfigBridge.expectedParamsFor(cfg), /*headless*/ true, /*requireFresh*/ true);
        assertEquals(DeconvPreflight.Decision.NO_OP, decision);
    }

    @Test
    public void paramsChangeWithUnchangedSourceIsDetectedAsStale() throws Exception {
        File root = tmp.getRoot();
        File source = writeSource("img.tif");
        // Mirror written with the ORIGINAL params.
        writeFreshMirror(root, "img", opticsRoutedConfig(), source);

        // Config's iterations changed; source bytes unchanged. Source-fingerprint-only freshness would
        // (wrongly) pass; the params overlay must flag the mirror stale -> headless proceeds on raw.
        ChannelConfig changed = opticsRoutedConfig();
        changed.channels.get(0).deconvIterations = Integer.valueOf(30);
        ExpectedDeconvParams expectedChanged = DeconvConfigBridge.expectedParamsFor(changed);

        DeconvPreflight.Decision decision = DeconvPreflight.decide(
                DeconvRoutingGroup.groupFor(IDX_3D_OBJECT),
                changed, root, oneSeries("img", source),
                expectedChanged, /*headless*/ true, /*requireFresh*/ false);
        assertEquals(DeconvPreflight.Decision.PROCEED_ON_RAW, decision);
    }
}
