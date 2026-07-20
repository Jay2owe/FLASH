package flash.pipeline.roi;

import ij.gui.Roi;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class RoiSetImageBindingTest {

    @Test
    public void tokenNamesRoundTripAndAreZipSafe() {
        String imageKey = "lif|C:/data/animal 1.lif|2|Experiment-A1_LH_SCN";
        String token = RoiSetImageBinding.token(imageKey);

        assertEquals(token, RoiSetImageBinding.token(imageKey));
        assertTrue(RoiSetImageBinding.isToken(token));
        assertFalse(token.contains("|"));
        assertFalse(token.contains(" "));
        assertFalse(token.contains("/"));
        assertEquals(token, RoiSetImageBinding.drawnRoiName(imageKey));
        assertEquals(token + RoiSetImageBinding.CROPPED_SUFFIX,
                RoiSetImageBinding.croppedRoiName(imageKey));
        assertEquals(token, RoiSetImageBinding.tokenOf(RoiSetImageBinding.drawnRoiName(imageKey)));
        assertEquals(token, RoiSetImageBinding.tokenOf(RoiSetImageBinding.croppedRoiName(imageKey)));
        assertFalse(RoiSetImageBinding.isCropped(RoiSetImageBinding.drawnRoiName(imageKey)));
        assertTrue(RoiSetImageBinding.isCropped(RoiSetImageBinding.croppedRoiName(imageKey)));
    }

    @Test
    public void distinctContainerIdentitiesProduceDistinctTokens() {
        String a = RoiSetImageBinding.token("lif|fileA.lif|1|same");
        String b = RoiSetImageBinding.token("lif|fileA.lif|2|same");
        String c = RoiSetImageBinding.token("lif|fileB.lif|1|same");

        assertFalse(a.equals(b));
        assertFalse(a.equals(c));
        assertFalse(b.equals(c));
    }

    @Test
    public void blankImageKeyIsRejected() {
        try {
            RoiSetImageBinding.token("  ");
            fail("blank image key should be rejected");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("imageKey"));
        }
    }

    @Test
    public void indexByTokenGroupsShuffledDrawnAndCroppedPairs() {
        String keyA = "tif|a.tif|1|A";
        String keyB = "tif|b.tif|1|B";
        Roi aDrawn = roi(RoiSetImageBinding.drawnRoiName(keyA));
        Roi aCropped = roi(RoiSetImageBinding.croppedRoiName(keyA));
        Roi bDrawn = roi(RoiSetImageBinding.drawnRoiName(keyB));
        Roi bCropped = roi(RoiSetImageBinding.croppedRoiName(keyB));

        Map<String, RoiSetImageBinding.RoiPair> byToken = RoiSetImageBinding.indexByToken(
                Arrays.asList(bCropped, aDrawn, bDrawn, aCropped));

        assertEquals(2, byToken.size());
        assertSame(aDrawn, byToken.get(RoiSetImageBinding.token(keyA)).drawn);
        assertSame(aCropped, byToken.get(RoiSetImageBinding.token(keyA)).cropped);
        assertSame(bDrawn, byToken.get(RoiSetImageBinding.token(keyB)).drawn);
        assertSame(bCropped, byToken.get(RoiSetImageBinding.token(keyB)).cropped);
    }

    @Test
    public void structuralValidationAllowsSubsetCoverage() {
        String key = "tif|subset.tif|1|OnlyImageInThisRegion";

        RoiSetValidator.validateStructural(Arrays.asList(
                roi(RoiSetImageBinding.croppedRoiName(key)),
                roi(RoiSetImageBinding.drawnRoiName(key))));
    }

    @Test
    public void structuralValidationRejectsMissingPairDuplicateAndForeignToken() {
        String key = "tif|subset.tif|1|OnlyImageInThisRegion";
        String other = "tif|other.tif|1|OutsideProject";

        expectInvalid(Arrays.asList(roi(RoiSetImageBinding.drawnRoiName(key))), "cropped");
        expectInvalid(Arrays.asList(
                roi(RoiSetImageBinding.drawnRoiName(key)),
                roi(RoiSetImageBinding.drawnRoiName(key)),
                roi(RoiSetImageBinding.croppedRoiName(key))), "expected exactly 2 per token");
        expectInvalid(Arrays.asList(
                roi("legacy"),
                roi("legacy_Cropped")), "no recognisable");

        try {
            RoiSetValidator.validateStructural(Arrays.asList(
                    roi(RoiSetImageBinding.drawnRoiName(other)),
                    roi(RoiSetImageBinding.croppedRoiName(other))),
                    new HashSet<String>(Collections.singleton(RoiSetImageBinding.token(key))));
            fail("foreign token should be rejected");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("not part of the current project"));
        }
    }

    private static void expectInvalid(java.util.List<Roi> rois, String expectedMessage) {
        try {
            RoiSetValidator.validateStructural(rois);
            fail("invalid ROI set should fail");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains(expectedMessage));
        }
    }

    private static Roi roi(String name) {
        Roi roi = new Roi(1, 2, 10, 11);
        roi.setName(name);
        return roi;
    }
}
