package flash.pipeline.deconv.routing;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

public class DeconvRoutingTest {

    @Test
    public void defaultForIsAllDeconvForOptedInChannels() {
        boolean[] selected = {true, false, true};
        DeconvRouting routing = DeconvRouting.defaultFor(selected);

        assertEquals(3, routing.channelCount());
        assertEquals(SourceKind.DECONV, routing.sourceFor(DeconvRoutingGroup.ANALYSIS, 0));
        assertEquals(SourceKind.DECONV, routing.sourceFor(DeconvRoutingGroup.DISPLAY, 0));
        assertEquals(SourceKind.RAW, routing.sourceFor(DeconvRoutingGroup.ANALYSIS, 1));
        assertEquals(SourceKind.RAW, routing.sourceFor(DeconvRoutingGroup.DISPLAY, 1));
        assertEquals(SourceKind.DECONV, routing.sourceFor(DeconvRoutingGroup.ANALYSIS, 2));
        assertTrue(routing.isOptedIn(0));
        assertFalse(routing.isOptedIn(1));
        assertTrue(routing.isGroupDefault(DeconvRoutingGroup.ANALYSIS));
        assertTrue(routing.isGroupDefault(DeconvRoutingGroup.DISPLAY));
        assertFalse(routing.isDivergent());
    }

    @Test
    public void normalizeForcesRawForNonSelectedChannelsInBothGroups() {
        // Channel 2 is routed DECONV but not selected this run -> must be forced RAW + not opted in.
        DeconvRouting routing = DeconvRouting.of(
                new boolean[] {true, true, true},
                new SourceKind[] {SourceKind.DECONV, SourceKind.DECONV, SourceKind.DECONV},
                new SourceKind[] {SourceKind.DECONV, SourceKind.DECONV, SourceKind.DECONV});

        DeconvRouting normalized = routing.normalize(new boolean[] {true, false, true});

        assertTrue(normalized.isOptedIn(0));
        assertFalse(normalized.isOptedIn(1));
        assertEquals(SourceKind.RAW, normalized.sourceFor(DeconvRoutingGroup.ANALYSIS, 1));
        assertEquals(SourceKind.RAW, normalized.sourceFor(DeconvRoutingGroup.DISPLAY, 1));
        assertEquals(SourceKind.DECONV, normalized.sourceFor(DeconvRoutingGroup.ANALYSIS, 2));
    }

    @Test
    public void optInInvariantForcesRawEvenWhenVectorSaysDeconv() {
        // A DECONV token for a channel that is not opted in must never leak through.
        DeconvRouting routing = DeconvRouting.of(
                new boolean[] {false},
                new SourceKind[] {SourceKind.DECONV},
                new SourceKind[] {SourceKind.DECONV});
        assertEquals(SourceKind.RAW, routing.sourceFor(DeconvRoutingGroup.ANALYSIS, 0));
        assertEquals(SourceKind.RAW, routing.sourceFor(DeconvRoutingGroup.DISPLAY, 0));
        assertFalse(routing.anyOptedIn());
    }

    @Test
    public void legacyFromToggleMapsWholeImageOnOff() {
        DeconvRouting on = DeconvRouting.legacyFromToggle(true, 2);
        assertEquals(SourceKind.DECONV, on.sourceFor(DeconvRoutingGroup.ANALYSIS, 0));
        assertEquals(SourceKind.DECONV, on.sourceFor(DeconvRoutingGroup.DISPLAY, 1));
        assertTrue(on.isOptedIn(0));
        assertTrue(on.groupUsesDeconv(DeconvRoutingGroup.ANALYSIS));

        DeconvRouting off = DeconvRouting.legacyFromToggle(false, 2);
        assertEquals(SourceKind.RAW, off.sourceFor(DeconvRoutingGroup.ANALYSIS, 0));
        assertEquals(SourceKind.RAW, off.sourceFor(DeconvRoutingGroup.DISPLAY, 1));
        assertFalse(off.anyOptedIn());
        assertFalse(off.groupUsesDeconv(DeconvRoutingGroup.DISPLAY));
    }

    @Test
    public void divergentRoutingIsDetectedAndBreaksGroupDefault() {
        DeconvRouting routing = DeconvRouting.defaultFor(new boolean[] {true, true})
                .withSource(DeconvRoutingGroup.DISPLAY, 0, SourceKind.RAW);

        assertTrue(routing.isDivergent());
        assertTrue("analysis still matches merged-file encoding", routing.isGroupDefault(DeconvRoutingGroup.ANALYSIS));
        assertFalse("display diverges -> needs composition", routing.isGroupDefault(DeconvRoutingGroup.DISPLAY));
    }

    @Test
    public void groupForMapsConsumersAndExemptsTheRest() {
        assertEquals(DeconvRoutingGroup.DISPLAY, DeconvRoutingGroup.groupFor(3).orElse(null));   // SplitMerge
        assertEquals(DeconvRoutingGroup.ANALYSIS, DeconvRoutingGroup.groupFor(4).orElse(null));  // 3D Object
        assertEquals(DeconvRoutingGroup.ANALYSIS, DeconvRoutingGroup.groupFor(7).orElse(null));  // Intensity
        assertEquals(DeconvRoutingGroup.DISPLAY, DeconvRoutingGroup.groupFor(12).orElse(null));  // Rep-Figure

        int[] exempt = {0, 1, 2, 5, 6, 8, 9, 10, 11};
        for (int idx : exempt) {
            assertFalse("index " + idx + " must be exempt", DeconvRoutingGroup.groupFor(idx).isPresent());
            assertFalse(DeconvRoutingGroup.isRouted(idx));
        }
    }

    @Test
    public void fingerprintChangesIffARelevantParameterChanges() {
        String base = DeconvParamsFingerprint.of(1.4, 1.515, 1.47, 0.1, 0.3, 520.0,
                "clij2fft", "RL", 20, 0.002, "GIBSON_LANNI", "WIDEFIELD", null);
        String same = DeconvParamsFingerprint.of(1.4, 1.515, 1.47, 0.1, 0.3, 520.0,
                "clij2fft", "RL", 20, 0.002, "GIBSON_LANNI", "WIDEFIELD", null);
        assertEquals(base, same);

        String moreIters = DeconvParamsFingerprint.of(1.4, 1.515, 1.47, 0.1, 0.3, 520.0,
                "clij2fft", "RL", 21, 0.002, "GIBSON_LANNI", "WIDEFIELD", null);
        assertNotEquals(base, moreIters);

        String otherWavelength = DeconvParamsFingerprint.of(1.4, 1.515, 1.47, 0.1, 0.3, 610.0,
                "clij2fft", "RL", 20, 0.002, "GIBSON_LANNI", "WIDEFIELD", null);
        assertNotEquals(base, otherWavelength);

        String confocal = DeconvParamsFingerprint.of(1.4, 1.515, 1.47, 0.1, 0.3, 520.0,
                "clij2fft", "RL", 20, 0.002, "GIBSON_LANNI", "CONFOCAL", Double.valueOf(1.0));
        assertNotEquals(base, confocal);
    }

    @Test
    public void equalityIsValueBased() {
        assertEquals(DeconvRouting.defaultFor(new boolean[] {true, false}),
                DeconvRouting.defaultFor(new boolean[] {true, false}));
        assertNotEquals(DeconvRouting.defaultFor(new boolean[] {true, false}),
                DeconvRouting.defaultFor(new boolean[] {true, true}));
    }
}
