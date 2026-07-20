package flash.pipeline.deconv.routing;

import flash.pipeline.bin.ChannelConfig;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Stage 15 — §C3 precedence contract tests for {@link DeconvRoutingResolver}. The precedence bugs
 * are silent (they change the pixels every consumer measures on), so each rule is pinned here.
 */
public class DeconvRoutingResolverTest {

    private static boolean[] allTrue(int n) {
        boolean[] mask = new boolean[n];
        Arrays.fill(mask, true);
        return mask;
    }

    /** A config whose channels are all opted in to deconvolution with explicit route tokens. */
    private static ChannelConfig deconvConfig(int count, String routeAnalysis, String routeDisplay) {
        ChannelConfig cfg = new ChannelConfig();
        for (int i = 0; i < count; i++) {
            ChannelConfig.Channel channel = new ChannelConfig.Channel();
            channel.index = i;
            channel.name = "C" + i;
            channel.deconvEngineKey = "clij2fft";
            channel.routeAnalysis = routeAnalysis;
            channel.routeDisplay = routeDisplay;
            cfg.channels.add(channel);
        }
        return cfg;
    }

    // ---- REQUIRED #1: explicit CLI flag overrides persisted routing ----

    @Test
    public void explicitCliFlagFalseBeatsPersistedAllDeconv() {
        ChannelConfig persistedAllDeconv = deconvConfig(2, "deconv", "deconv");

        DeconvRoutingResolver.Result result = DeconvRoutingResolver.resolve(
                new DeconvRoutingResolver.Inputs()
                        .sizeC(2)
                        .selectedChannels(allTrue(2))
                        .group(DeconvRoutingGroup.ANALYSIS)
                        .channelConfig(persistedAllDeconv)
                        .cliFlag(Boolean.FALSE)
                        .interactivePresent(false));

        // CLI false is a hard override -> every channel RAW, beating the persisted all-deconv config.
        assertFalse(result.routing.groupUsesDeconv(DeconvRoutingGroup.ANALYSIS));
        assertEquals(SourceKind.RAW, result.routing.sourceFor(DeconvRoutingGroup.ANALYSIS, 0));
        assertEquals(SourceKind.RAW, result.routing.sourceFor(DeconvRoutingGroup.ANALYSIS, 1));
        assertFalse(result.warnTunedFlip);
    }

    // ---- REQUIRED #2: absent CLI flag + persisted routing wins over default ----

    @Test
    public void absentCliFlagWithPersistedRoutingPreservesPerChannelVector() {
        // ch0 opted in, analysis->deconv, display->raw; ch1 not opted in (raw both).
        ChannelConfig cfg = new ChannelConfig();
        ChannelConfig.Channel c0 = new ChannelConfig.Channel();
        c0.index = 0;
        c0.deconvEngineKey = "clij2fft";
        c0.routeAnalysis = "deconv";
        c0.routeDisplay = "raw";
        cfg.channels.add(c0);
        ChannelConfig.Channel c1 = new ChannelConfig.Channel(); // not opted in
        c1.index = 1;
        cfg.channels.add(c1);

        DeconvRoutingResolver.Result analysis = DeconvRoutingResolver.resolve(
                new DeconvRoutingResolver.Inputs()
                        .sizeC(2)
                        .selectedChannels(allTrue(2))
                        .group(DeconvRoutingGroup.ANALYSIS)
                        .channelConfig(cfg)
                        .cliFlag(null)
                        .interactivePresent(false));

        // Persisted per-channel vector is preserved (wins over the whole-image default).
        assertEquals(SourceKind.DECONV, analysis.routing.sourceFor(DeconvRoutingGroup.ANALYSIS, 0));
        assertEquals(SourceKind.RAW, analysis.routing.sourceFor(DeconvRoutingGroup.DISPLAY, 0));
        assertEquals(SourceKind.RAW, analysis.routing.sourceFor(DeconvRoutingGroup.ANALYSIS, 1));
        assertFalse(analysis.routing.isOptedIn(1));
        assertFalse(analysis.warnTunedFlip);
    }

    // ---- REQUIRED #3: absent everything -> legacy-from-toggle(defaultTrue) ----

    @Test
    public void absentEverythingFallsBackToLegacyDefaultTrue() {
        DeconvRoutingResolver.Result result = DeconvRoutingResolver.resolve(
                new DeconvRoutingResolver.Inputs()
                        .sizeC(3)
                        .selectedChannels(allTrue(3))
                        .group(DeconvRoutingGroup.ANALYSIS)
                        .channelConfig(null)
                        .cliFlag(null)
                        .interactivePresent(false)
                        .legacyDefaultToggle(true));

        DeconvRouting expected = DeconvRouting.legacyFromToggle(true, 3).normalize(allTrue(3));
        assertEquals(expected, result.routing);
        assertTrue(result.routing.groupUsesDeconv(DeconvRoutingGroup.ANALYSIS));
        assertFalse(result.warnTunedFlip);
    }

    // ---- REQUIRED #4: interactive flip away from tuned source warns (Analysis, not Display) ----

    @Test
    public void interactiveFlipAwayFromTunedSourceWarnsForAnalysisNotDisplay() {
        ChannelConfig tunedOnDeconv = deconvConfig(2, "deconv", "deconv");

        boolean analysisDefault = DeconvRoutingResolver.toggleDefaultFor(
                tunedOnDeconv, DeconvRoutingGroup.ANALYSIS, true);
        assertTrue("persisted routing uses deconv -> toggle default ON", analysisDefault);

        DeconvRoutingResolver.Result analysisFlip = DeconvRoutingResolver.resolve(
                new DeconvRoutingResolver.Inputs()
                        .sizeC(2)
                        .selectedChannels(allTrue(2))
                        .group(DeconvRoutingGroup.ANALYSIS)
                        .channelConfig(tunedOnDeconv)
                        .cliFlag(null)
                        .interactivePresent(true)
                        .toggleDefault(analysisDefault)   // ON (tuned on deconv)
                        .toggleValue(false));             // flipped OFF -> away from deconv

        assertTrue(analysisFlip.warnTunedFlip);
        assertEquals("deconv", analysisFlip.tunedSourceLabel);
        assertFalse(analysisFlip.routing.groupUsesDeconv(DeconvRoutingGroup.ANALYSIS));

        DeconvRoutingResolver.Result displayFlip = DeconvRoutingResolver.resolve(
                new DeconvRoutingResolver.Inputs()
                        .sizeC(2)
                        .selectedChannels(allTrue(2))
                        .group(DeconvRoutingGroup.DISPLAY)
                        .channelConfig(tunedOnDeconv)
                        .cliFlag(null)
                        .interactivePresent(true)
                        .toggleDefault(true)
                        .toggleValue(false));

        assertFalse("Display flips are cosmetic - never warn", displayFlip.warnTunedFlip);
        assertNull(displayFlip.tunedSourceLabel);
    }

    // ---- REQUIRED #5: normalize forces raw for a non-selected channel ----

    @Test
    public void normalizeForcesRawForNonSelectedChannel() {
        DeconvRoutingResolver.Result result = DeconvRoutingResolver.resolve(
                new DeconvRoutingResolver.Inputs()
                        .sizeC(3)
                        .selectedChannels(new boolean[] {true, false, true})
                        .group(DeconvRoutingGroup.ANALYSIS)
                        .channelConfig(null)
                        .cliFlag(null)
                        .interactivePresent(false)
                        .legacyDefaultToggle(true));

        // Legacy default routed all channels DECONV, but channel 1 is not selected -> forced RAW.
        assertEquals(SourceKind.DECONV, result.routing.sourceFor(DeconvRoutingGroup.ANALYSIS, 0));
        assertEquals(SourceKind.RAW, result.routing.sourceFor(DeconvRoutingGroup.ANALYSIS, 1));
        assertEquals(SourceKind.DECONV, result.routing.sourceFor(DeconvRoutingGroup.ANALYSIS, 2));
        assertFalse(result.routing.isOptedIn(1));
    }
}
