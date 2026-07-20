package flash.pipeline.representative;

import flash.pipeline.deconv.routing.DeconvRouting;
import flash.pipeline.deconv.routing.SourceKind;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * Stage 16: the DISPLAY-routing cache-key fragment must stay empty for a project with no deconvolution
 * (so cached thumbnails/figures render byte-identical to today) and must change when the routing flips
 * (so flipping the routing invalidates the preview/figure cache).
 */
public class RepresentativePreviewRendererDeconvCacheTest {

    private static final String SERIES = "Exp-Mouse1_LH_SCN";

    @Test
    public void nullRoutingProducesEmptyToken() {
        assertEquals("", RepresentativePreviewRenderer.deconvCacheTokenFor(
                null, null, SERIES, 0));
    }

    @Test
    public void allRawRoutingKeepsCacheKeyUnchanged() {
        DeconvRouting raw = DeconvRouting.legacyFromToggle(false, 2);
        assertEquals("", RepresentativePreviewRenderer.deconvCacheTokenFor(
                raw, null, SERIES, 0));
    }

    @Test
    public void deconvRoutingProducesNonEmptyToken() {
        DeconvRouting deconv = DeconvRouting.legacyFromToggle(true, 2);
        String token = RepresentativePreviewRenderer.deconvCacheTokenFor(
                deconv, null, SERIES, 0);
        assertFalse(token.isEmpty());
        assertTrue(token.contains("display-deconv"));
    }

    @Test
    public void flippingRoutingInvalidatesCacheKey() {
        DeconvRouting deconv = DeconvRouting.legacyFromToggle(true, 2);
        DeconvRouting raw = DeconvRouting.legacyFromToggle(false, 2);
        String deconvToken = RepresentativePreviewRenderer.deconvCacheTokenFor(
                deconv, null, SERIES, 0);
        String rawToken = RepresentativePreviewRenderer.deconvCacheTokenFor(
                raw, null, SERIES, 0);
        assertNotEquals(deconvToken, rawToken);
    }

    @Test
    public void divergentDisplayVectorChangesToken() {
        boolean[] opted = {true, true};
        DeconvRouting bothDeconv = DeconvRouting.of(opted,
                new SourceKind[]{SourceKind.DECONV, SourceKind.DECONV},
                new SourceKind[]{SourceKind.DECONV, SourceKind.DECONV});
        DeconvRouting mixedDisplay = DeconvRouting.of(opted,
                new SourceKind[]{SourceKind.DECONV, SourceKind.DECONV},
                new SourceKind[]{SourceKind.DECONV, SourceKind.RAW});
        String bothToken = RepresentativePreviewRenderer.deconvCacheTokenFor(
                bothDeconv, null, SERIES, 0);
        String mixedToken = RepresentativePreviewRenderer.deconvCacheTokenFor(
                mixedDisplay, null, SERIES, 0);
        assertNotEquals(bothToken, mixedToken);
    }
}
