package flash.pipeline.ui.config;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ParticleSizeStageValidationTest {

    @Test
    public void rawSizeFieldsAcceptCurrentLockInRules() {
        assertTrue(ParticleSizeStage.isValidSizeFields("100", "Infinity", null));
        assertTrue(ParticleSizeStage.isValidSizeFields("0", "500", null));
        assertTrue(ParticleSizeStage.isValidSizeFields("", "", null));
    }

    @Test
    public void rawSizeFieldsRejectUnparseableOrInvertedRanges() {
        assertFalse(ParticleSizeStage.isValidSizeFields("small", "Infinity", null));
        assertFalse(ParticleSizeStage.isValidSizeFields("100", "many", null));
        assertFalse(ParticleSizeStage.isValidSizeFields("200", "100", null));
    }

    @Test
    public void sizeRangeTokenRequiresMinMaxShape() {
        assertTrue(ParticleSizeStage.isValidSizeRangeToken("100-Infinity"));
        assertTrue(ParticleSizeStage.isValidSizeRangeToken("0-500"));

        assertFalse(ParticleSizeStage.isValidSizeRangeToken("100"));
        assertFalse(ParticleSizeStage.isValidSizeRangeToken("100-many"));
    }
}
