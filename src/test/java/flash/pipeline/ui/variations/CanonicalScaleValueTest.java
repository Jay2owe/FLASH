package flash.pipeline.ui.variations;

import flash.pipeline.image.FilterMacroParser.OpType;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class CanonicalScaleValueTest {

    @Test
    public void gaussianMediumUsesSigma() {
        CanonicalScale.ScaleValue value = CanonicalScale.valueFor(
                OpType.GAUSSIAN_BLUR, CanonicalScale.MEDIUM);

        assertEquals("sigma", value.paramKey());
        assertEquals(1.5d, value.value(), 0.0001d);
    }

    @Test
    public void medianLargeUsesRadiusEight() {
        CanonicalScale.ScaleValue value = CanonicalScale.valueFor(
                OpType.MEDIAN, CanonicalScale.LARGE);

        assertEquals("radius", value.paramKey());
        assertEquals(8.0d, value.value(), 0.0001d);
    }

    @Test
    public void morphologyHasNoFakeScaleParameter() {
        CanonicalScale.ScaleValue value = CanonicalScale.valueFor(
                OpType.DILATE, CanonicalScale.SMALL);

        assertTrue(value.isNone());
    }
}
