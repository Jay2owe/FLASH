package flash.pipeline.stardist;

import ij.ImagePlus;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

public class StarDist3DRunnerVariationsTest {

    @Test
    public void thresholdPairHasStableEqualityAndHashCode() {
        StarDistVariationRunner.ThresholdPair first =
                new StarDistVariationRunner.ThresholdPair(0.3d, 0.5d);
        StarDistVariationRunner.ThresholdPair second =
                new StarDistVariationRunner.ThresholdPair(0.3d, 0.5d);

        assertEquals(first, second);
        assertEquals(first.hashCode(), second.hashCode());
        assertEquals("ThresholdPair{probThresh=0.3, nmsThresh=0.5}",
                first.toString());
    }

    @Test(expected = IllegalArgumentException.class)
    public void thresholdPairRejectsOutOfRangeThresholds() {
        new StarDistVariationRunner.ThresholdPair(1.1d, 0.5d);
    }

    @Test(expected = UnsupportedOperationException.class)
    public void runVariationsRemainsDisabledUntilParityIsProven() {
        assertFalse(StarDistVariationRunner.isFastNmsParityVerified());
        StarDistVariationRunner.runVariations(new ImagePlus(),
                "DAPI",
                Arrays.asList(Double.valueOf(0.3d), Double.valueOf(0.5d)),
                Collections.singletonList(Double.valueOf(0.4d)),
                null);
    }
}
