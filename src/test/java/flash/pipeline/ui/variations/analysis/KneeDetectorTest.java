package flash.pipeline.ui.variations.analysis;

import org.junit.Test;

import java.util.OptionalInt;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class KneeDetectorTest {

    @Test
    public void clearElbowReturnsKneeIndex() {
        OptionalInt knee = KneeDetector.findKneeIndex(
                new double[] {0, 1, 2, 3, 4, 5, 6},
                new double[] {100, 95, 80, 30, 10, 8, 7});

        assertTrue(knee.isPresent());
        assertEquals(3, knee.getAsInt());
    }

    @Test
    public void linearCurveReturnsEmpty() {
        OptionalInt knee = KneeDetector.findKneeIndex(
                new double[] {0, 1, 2, 3, 4, 5, 6},
                new double[] {100, 85, 70, 55, 40, 25, 10});

        assertFalse(knee.isPresent());
    }

    @Test
    public void constantCurveReturnsEmpty() {
        OptionalInt knee = KneeDetector.findKneeIndex(
                new double[] {0, 1, 2, 3, 4, 5, 6},
                new double[] {42, 42, 42, 42, 42, 42, 42});

        assertFalse(knee.isPresent());
    }

    @Test
    public void threePointCurveReturnsEmpty() {
        OptionalInt knee = KneeDetector.findKneeIndex(
                new double[] {0, 1, 2},
                new double[] {100, 20, 10});

        assertFalse(knee.isPresent());
    }

    @Test
    public void reverseDirectionCurveStillDetectsElbow() {
        OptionalInt knee = KneeDetector.findKneeIndex(
                new double[] {0, 1, 2, 3, 4, 5, 6},
                new double[] {7, 8, 10, 30, 80, 95, 100});

        assertTrue(knee.isPresent());
        assertEquals(3, knee.getAsInt());
    }

    @Test
    public void nonFiniteValuesAreIgnored() {
        OptionalInt knee = KneeDetector.findKneeIndex(
                new double[] {0, 1, 2, 3, 4, 5, 6, 7},
                new double[] {100, 95, 80, 30, 10, 8, 7, Double.NaN});

        assertTrue(knee.isPresent());
        assertEquals(3, knee.getAsInt());
    }
}
