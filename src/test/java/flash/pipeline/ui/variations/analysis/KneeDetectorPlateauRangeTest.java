package flash.pipeline.ui.variations.analysis;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNull;

public class KneeDetectorPlateauRangeTest {

    @Test
    public void tailPlateauIncludesFlatEnd() {
        int[] range = KneeDetector.findPlateauRange(
                new double[] { 0, 1, 2, 3, 4, 5 },
                new double[] { 8, 10, 18, 19, 19, 19 });

        assertArrayEquals(new int[] { 2, 5 }, range);
    }

    @Test
    public void linearSeriesReturnsNull() {
        int[] range = KneeDetector.findPlateauRange(
                new double[] { 0, 1, 2, 3, 4 },
                new double[] { 1, 2, 3, 4, 5 });

        assertNull(range);
    }
}
