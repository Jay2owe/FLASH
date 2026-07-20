package flash.pipeline.deconv.routing;

import flash.pipeline.bin.ChannelConfig;
import flash.pipeline.bin.ChannelConfigIO;
import flash.pipeline.deconv.engine.Algorithm;
import flash.pipeline.deconv.engine.DeconvSettings;
import flash.pipeline.deconv.psf.PsfSpec;
import flash.pipeline.deconv.psf.ScopeModality;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class DeconvConfigBridgeTest {

    private static ChannelConfig.Channel deconvChannel(int index, String routeAnalysis, String routeDisplay) {
        ChannelConfig.Channel channel = new ChannelConfig.Channel();
        channel.index = index;
        channel.name = "C" + index;
        channel.deconvEngineKey = "clij2fft";
        channel.deconvAlgorithm = "RL_TV";
        channel.deconvPsfModel = "GIBSON_LANNI";
        channel.deconvIterations = Integer.valueOf(30);
        channel.deconvRegularization = Double.valueOf(0.003);
        channel.emissionWavelengthNm = Double.valueOf(520.0);
        channel.routeAnalysis = routeAnalysis;
        channel.routeDisplay = routeDisplay;
        return channel;
    }

    private static ChannelConfig.DeconvOptics optics(double na, double immersion, double sample, String modality) {
        ChannelConfig.DeconvOptics optics = new ChannelConfig.DeconvOptics();
        optics.na = Double.valueOf(na);
        optics.immersionRi = Double.valueOf(immersion);
        optics.sampleRi = Double.valueOf(sample);
        optics.scopeModality = modality;
        return optics;
    }

    @Test
    public void routingFromBuildsPerChannelTwoGroupVectors() {
        ChannelConfig cfg = new ChannelConfig();
        cfg.channels.add(deconvChannel(0, "deconv", "raw")); // opted in, display->raw
        ChannelConfig.Channel plain = new ChannelConfig.Channel(); // not opted in
        plain.index = 1;
        cfg.channels.add(plain);

        DeconvRouting routing = DeconvConfigBridge.routingFrom(cfg);

        assertTrue(routing.isOptedIn(0));
        assertFalse(routing.isOptedIn(1));
        assertEquals(SourceKind.DECONV, routing.sourceFor(DeconvRoutingGroup.ANALYSIS, 0));
        assertEquals(SourceKind.RAW, routing.sourceFor(DeconvRoutingGroup.DISPLAY, 0));
        assertEquals(SourceKind.RAW, routing.sourceFor(DeconvRoutingGroup.ANALYSIS, 1));
        assertTrue(DeconvConfigBridge.isDeconvConfigured(cfg));
        assertTrue(DeconvConfigBridge.hasRoutingKeys(cfg));
    }

    @Test
    public void optedInChannelDefaultsMissingRouteToDeconv() {
        ChannelConfig cfg = new ChannelConfig();
        cfg.channels.add(deconvChannel(0, null, null)); // opted in, no explicit route tokens

        DeconvRouting routing = DeconvConfigBridge.routingFrom(cfg);
        assertEquals(SourceKind.DECONV, routing.sourceFor(DeconvRoutingGroup.ANALYSIS, 0));
        assertEquals(SourceKind.DECONV, routing.sourceFor(DeconvRoutingGroup.DISPLAY, 0));
        assertFalse(DeconvConfigBridge.hasRoutingKeys(cfg));
    }

    @Test
    public void settingsForBuildsDeconvSettingsWithDefaultsForMissing() {
        ChannelConfig cfg = new ChannelConfig();
        ChannelConfig.Channel channel = deconvChannel(0, "deconv", "deconv");
        channel.deconvAlgorithm = null;   // -> default RL
        channel.deconvIterations = null;  // -> default 20
        cfg.channels.add(channel);

        DeconvSettings settings = DeconvConfigBridge.settingsFor(cfg, 0);
        assertEquals("clij2fft", settings.engineKey());
        assertEquals(Algorithm.RL, settings.algorithm());
        assertEquals(20, settings.iterations());
    }

    @Test
    public void settingsForReturnsNullWhenChannelNotOptedIn() {
        ChannelConfig cfg = new ChannelConfig();
        cfg.channels.add(new ChannelConfig.Channel());
        assertNull(DeconvConfigBridge.settingsFor(cfg, 0));
        assertFalse(DeconvConfigBridge.isDeconvConfigured(cfg));
    }

    @Test
    public void psfSpecForBuildsValidSpecFromOptics() {
        ChannelConfig cfg = new ChannelConfig();
        cfg.deconvOptics = optics(1.4, 1.515, 1.47, "WIDEFIELD");
        cfg.channels.add(deconvChannel(0, "deconv", "deconv"));

        PsfSpec spec = DeconvConfigBridge.psfSpecFor(cfg, 0, 100.0, 300.0, 64, 64, 32);
        assertEquals(1.4, spec.getNumericalAperture(), 1e-9);
        assertEquals(520.0, spec.getEmissionWavelengthNm(), 1e-9);
        assertEquals(ScopeModality.WIDEFIELD, spec.getScopeModality());
    }

    @Test
    public void psfSpecForThrowsOnInvalidOptics() {
        ChannelConfig cfg = new ChannelConfig();
        cfg.deconvOptics = optics(1.6, 1.515, 1.47, "WIDEFIELD"); // NA >= immersion RI
        cfg.channels.add(deconvChannel(0, "deconv", "deconv"));
        try {
            DeconvConfigBridge.psfSpecFor(cfg, 0, 100.0, 300.0, 64, 64, 32);
            fail("expected IllegalArgumentException for NA >= immersionRI");
        } catch (IllegalArgumentException expected) {
            // surfaced at the use point, never inside the codec
        }
    }

    @Test
    public void psfSpecForThrowsWhenOpticsMissing() {
        ChannelConfig cfg = new ChannelConfig();
        cfg.channels.add(deconvChannel(0, "deconv", "deconv"));
        try {
            DeconvConfigBridge.psfSpecFor(cfg, 0, 100.0, 300.0, 64, 64, 32);
            fail("expected IllegalArgumentException when optics absent");
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }

    @Test
    public void preserveDeconvCarriesForwardWhenTargetHasNone() {
        ChannelConfig source = new ChannelConfig();
        source.deconvOptics = optics(1.4, 1.515, 1.47, "CONFOCAL");
        source.channels.add(deconvChannel(0, "deconv", "raw"));

        ChannelConfig target = new ChannelConfig();
        ChannelConfig.Channel bare = new ChannelConfig.Channel();
        bare.index = 0;
        bare.name = "C0";
        target.channels.add(bare);

        DeconvConfigBridge.preserveDeconv(target, source);

        assertEquals("clij2fft", target.channels.get(0).deconvEngineKey);
        assertEquals("raw", target.channels.get(0).routeDisplay);
        assertEquals(Double.valueOf(1.4), target.deconvOptics.na);
    }

    @Test
    public void preserveDeconvDoesNotClobberFreshlyEditedValues() {
        ChannelConfig source = new ChannelConfig();
        source.channels.add(deconvChannel(0, "deconv", "deconv"));

        ChannelConfig target = new ChannelConfig();
        ChannelConfig.Channel edited = new ChannelConfig.Channel();
        edited.index = 0;
        edited.deconvEngineKey = "DL2";        // freshly chosen engine
        edited.routeDisplay = "raw";           // freshly chosen route
        target.channels.add(edited);

        DeconvConfigBridge.preserveDeconv(target, source);

        assertEquals("DL2", target.channels.get(0).deconvEngineKey);   // kept
        assertEquals("raw", target.channels.get(0).routeDisplay);       // kept
        // gap fields still filled from source
        assertEquals("deconv", target.channels.get(0).routeAnalysis);
    }

    @Test
    public void deconvDoesNotAffectConfigCompleteness() {
        // A quick, complete, non-deconv config stays complete; adding deconv does not change that
        // (deconv is intentionally out of the completeness PROPERTIES set).
        ChannelConfig cfg = new ChannelConfig();
        cfg.complete = Boolean.TRUE;
        ChannelConfig.Channel channel = new ChannelConfig.Channel();
        channel.index = 0;
        channel.name = "DAPI";
        channel.color = "Blue";
        channel.markerId = "";
        channel.markerShape = "";
        channel.threshold = "default";
        channel.size = "100-Infinity";
        channel.minmax = "None";
        channel.intensityThreshold = "default";
        channel.segmentationMethod = "classical";
        channel.filterPreset = "Default";
        channel.status.put(ChannelConfig.P_NAME, ChannelConfig.PropertyStatus.COMMITTED);
        channel.status.put(ChannelConfig.P_COLOR, ChannelConfig.PropertyStatus.COMMITTED);
        channel.status.put(ChannelConfig.P_MARKER, ChannelConfig.PropertyStatus.COMMITTED);
        channel.status.put(ChannelConfig.P_THRESHOLD, ChannelConfig.PropertyStatus.COMMITTED);
        channel.status.put(ChannelConfig.P_SIZE, ChannelConfig.PropertyStatus.COMMITTED);
        channel.status.put(ChannelConfig.P_MINMAX, ChannelConfig.PropertyStatus.COMMITTED);
        channel.status.put(ChannelConfig.P_INTENSITY, ChannelConfig.PropertyStatus.COMMITTED);
        channel.status.put(ChannelConfig.P_SEGMENTATION, ChannelConfig.PropertyStatus.COMMITTED);
        channel.status.put(ChannelConfig.P_FILTER, ChannelConfig.PropertyStatus.COMMITTED);
        cfg.channels.add(channel);
        assertTrue(ChannelConfigIO.isComplete(cfg));

        cfg.channels.get(0).deconvEngineKey = "clij2fft";
        cfg.channels.get(0).routeAnalysis = "deconv";
        assertTrue("deconv must not change completeness", ChannelConfigIO.isComplete(cfg));
    }
}
