package flash.pipeline.deconv;

import flash.pipeline.bin.ChannelConfig;
import flash.pipeline.deconv.engine.DeconvSettings;
import flash.pipeline.deconv.psf.ScopeModality;
import flash.pipeline.deconv.routing.DeconvConfigBridge;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * End-to-end params-staleness: the shared producer ({@link DeconvParamsHash}) writes a mirror's
 * recorded params, {@link DeconvConfigBridge#expectedParamsFor} derives the consumer/preflight's
 * expected config params from the SAME persisted config, and the manifest overlay
 * ({@link DeconvManifest#isChannelFreshForParams}) recomputes the expected hash from the config
 * subset + the mirror's recorded geometry.
 *
 * <p>The two invariants this guards (Stage 18, Limitation 1):</p>
 * <ol>
 *   <li>a fresh mirror written from a config is USED (not spuriously re-flagged) — the round-trip,
 *       so consumers do not silently fall back to raw for every mirror;</li>
 *   <li>changing a deconvolution parameter in the config (with the source bytes unchanged) makes the
 *       mirror read as stale — the gap the stage closes.</li>
 * </ol>
 */
public class DeconvParamsStalenessTest {

    @Rule
    public final TemporaryFolder tmp = new TemporaryFolder();

    private static final int GEOM_X = 512;
    private static final int GEOM_Y = 512;
    private static final int GEOM_Z = 40;
    private static final double PX_XY = 0.09;
    private static final double PX_Z = 0.30;

    /** One channel opted in to deconvolution with complete optics. */
    private static ChannelConfig opticsConfig() {
        ChannelConfig cfg = new ChannelConfig();
        ChannelConfig.DeconvOptics optics = new ChannelConfig.DeconvOptics();
        optics.na = Double.valueOf(1.40);
        optics.immersionRi = Double.valueOf(1.515);
        optics.sampleRi = Double.valueOf(1.44);
        optics.scopeModality = "WIDEFIELD";
        cfg.deconvOptics = optics;

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
        return cfg;
    }

    /** Simulate the mirror writer: the full recorded params + hash the writer would stamp. */
    private static DeconvManifest.ChannelEntry writtenEntry(ChannelConfig cfg,
                                                            DeconvManifest.SourceFingerprint fp) {
        DeconvSettings settings = DeconvConfigBridge.settingsFor(cfg, 0);
        assertNotNull(settings);
        ChannelConfig.DeconvOptics optics = cfg.deconvOptics;
        ScopeModality modality = DeconvConfigBridge.modalityFrom(cfg);
        Map<String, String> hashParams = DeconvParamsHash.buildParams(
                settings, modality,
                optics.na.doubleValue(), optics.immersionRi.doubleValue(), optics.sampleRi.doubleValue(),
                optics.pinholeAiryUnits, cfg.channels.get(0).emissionWavelengthNm.doubleValue(),
                PX_XY, PX_Z, GEOM_X, GEOM_Y, GEOM_Z);
        String paramsHash = DeconvolutionIO.paramsHash(hashParams);
        return new DeconvManifest.ChannelEntry(paramsHash, hashParams, fp, "CLIJ2",
                DeconvManifest.ENGINE_STAMP_VERSION, GEOM_Z);
    }

    @Test
    public void freshMirrorFromConfigIsUsed() throws Exception {
        File source = tmp.newFile("raw.lif");
        Files.write(source.toPath(), "pixels".getBytes(StandardCharsets.UTF_8));
        DeconvManifest.SourceFingerprint fp = DeconvManifest.SourceFingerprint.of(source);

        ChannelConfig cfg = opticsConfig();
        DeconvManifest manifest = DeconvManifest.empty().withChannel(0, writtenEntry(cfg, fp));
        ExpectedDeconvParams expected = DeconvConfigBridge.expectedParamsFor(cfg);
        assertFalse("optics complete -> params expected", expected.isEmpty());

        assertTrue("a fresh mirror written from this config must be USED, not re-flagged",
                manifest.isChannelFreshForParams(0, expected.forChannel(0), fp));
    }

    @Test
    public void changingAConfigParamMakesTheMirrorStale() throws Exception {
        File source = tmp.newFile("raw.lif");
        Files.write(source.toPath(), "pixels".getBytes(StandardCharsets.UTF_8));
        DeconvManifest.SourceFingerprint fp = DeconvManifest.SourceFingerprint.of(source);

        // Mirror written with the original config.
        DeconvManifest manifest = DeconvManifest.empty().withChannel(0, writtenEntry(opticsConfig(), fp));

        // Iterations changed in the config, source bytes unchanged: SOURCE-only freshness would (wrongly)
        // call this fresh; the params overlay must catch it.
        ChannelConfig changed = opticsConfig();
        changed.channels.get(0).deconvIterations = Integer.valueOf(30);
        ExpectedDeconvParams expectedChanged = DeconvConfigBridge.expectedParamsFor(changed);
        assertFalse("changed iterations -> stale",
                manifest.isChannelFreshForParams(0, expectedChanged.forChannel(0), fp));

        // Optics changed (NA).
        ChannelConfig changedNa = opticsConfig();
        changedNa.deconvOptics.na = Double.valueOf(1.45);
        assertFalse("changed NA -> stale", manifest.isChannelFreshForParams(
                0, DeconvConfigBridge.expectedParamsFor(changedNa).forChannel(0), fp));

        // Wavelength changed.
        ChannelConfig changedWl = opticsConfig();
        changedWl.channels.get(0).emissionWavelengthNm = Double.valueOf(488.0);
        assertFalse("changed wavelength -> stale", manifest.isChannelFreshForParams(
                0, DeconvConfigBridge.expectedParamsFor(changedWl).forChannel(0), fp));
    }

    @Test
    public void changingSourceContentMakesTheMirrorStaleEvenWithMatchingParams() throws Exception {
        File source = tmp.newFile("raw.lif");
        Files.write(source.toPath(), "pixels".getBytes(StandardCharsets.UTF_8));
        DeconvManifest.SourceFingerprint fp = DeconvManifest.SourceFingerprint.of(source);

        ChannelConfig cfg = opticsConfig();
        DeconvManifest manifest = DeconvManifest.empty().withChannel(0, writtenEntry(cfg, fp));
        ExpectedDeconvParams expected = DeconvConfigBridge.expectedParamsFor(cfg);

        DeconvManifest.SourceFingerprint changedSource = new DeconvManifest.SourceFingerprint(
                fp.size + 7, fp.mtimeMillis, fp.contentHash + "z");
        assertFalse("changed source content -> stale even with matching params",
                manifest.isChannelFreshForParams(0, expected.forChannel(0), changedSource));
    }

    @Test
    public void incompleteOpticsSkipsParamsCheckSourceOnly() throws Exception {
        File source = tmp.newFile("raw.lif");
        Files.write(source.toPath(), "pixels".getBytes(StandardCharsets.UTF_8));
        DeconvManifest.SourceFingerprint fp = DeconvManifest.SourceFingerprint.of(source);

        DeconvManifest manifest = DeconvManifest.empty().withChannel(0, writtenEntry(opticsConfig(), fp));

        // A config with no optics block cannot compute comparable params -> none() -> source-only,
        // so a source-matching mirror is still fresh (degrades gracefully, never crashes).
        ChannelConfig noOptics = opticsConfig();
        noOptics.deconvOptics = null;
        ExpectedDeconvParams none = DeconvConfigBridge.expectedParamsFor(noOptics);
        assertTrue("no optics -> none()", none.isEmpty());
        assertTrue("null per-channel params -> source-only freshness",
                manifest.isChannelFreshForParams(0, none.forChannel(0), fp));
    }
}
