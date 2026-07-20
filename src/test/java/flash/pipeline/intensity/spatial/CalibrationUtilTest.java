package flash.pipeline.intensity.spatial;

import flash.pipeline.io.CalibrationIO;
import ij.ImagePlus;
import ij.measure.Calibration;
import ij.process.FloatProcessor;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class CalibrationUtilTest {
    private static final double EPSILON = 1.0e-12;

    @Test
    public void canonicalCalibrationConvertsEveryAxisFromIndependentGoldenValues() {
        // Each row was dimensionally calculated from the SI definition of a micron.
        Object[][] fixtures = {
                {"um", 0.25, 0.75, 2.5},
                {"nm", 250.0, 750.0, 2500.0},
                {"mm", 0.00025, 0.00075, 0.0025},
                {"cm", 0.000025, 0.000075, 0.00025},
                {"m", 0.00000025, 0.00000075, 0.0000025}
        };

        for (Object[] fixture : fixtures) {
            String unit = (String) fixture[0];
            double rawX = (Double) fixture[1];
            double rawY = (Double) fixture[2];
            double rawZ = (Double) fixture[3];
            CalibrationUtil.CanonicalCalibration result = CalibrationUtil.canonicalize(
                    rawX, rawY, rawZ, unit);

            assertScale(result.x(), rawX, unit, 0.25);
            assertScale(result.y(), rawY, unit, 0.75);
            assertScale(result.z(), rawZ, unit, 2.5);
            assertTrue(result.isFullyPhysical());
            // Independent dimensional invariant: voxel volume = 0.25 * 0.75 * 2.5 um^3.
            assertEquals(0.46875,
                    result.x().microns() * result.y().microns() * result.z().microns(),
                    EPSILON);
        }
    }

    @Test
    public void canonicalCalibrationPreservesAnisotropyAndOriginalUnitText() {
        ImagePlus image = imageWithCalibration(125.0, 375.0, 1500.0, " nm / pixel ");

        CalibrationUtil.CanonicalCalibration result = CalibrationUtil.canonicalize(image);

        assertScale(result.x(), 125.0, " nm / pixel ", 0.125);
        assertScale(result.y(), 375.0, " nm / pixel ", 0.375);
        assertScale(result.z(), 1500.0, " nm / pixel ", 1.5);
        assertEquals("nm", result.x().normalizedUnit());
    }

    @Test
    public void eachAxisRetainsItsOwnValidityState() {
        CalibrationUtil.CanonicalCalibration result = CalibrationUtil.canonicalize(
                500.0, Double.NaN, -3.0, "nm");

        assertEquals(0.5, result.x().microns(), EPSILON);
        assertEquals(CalibrationUtil.State.PHYSICAL, result.x().state());
        assertMissing(result.y(), CalibrationUtil.State.NONFINITE_SCALE);
        assertMissing(result.z(), CalibrationUtil.State.NONPOSITIVE_SCALE);
        assertFalse(result.isFullyPhysical());
    }

    @Test
    public void missingPixelAndUnknownUnitsHaveDistinctTypedStates() {
        assertMissing(CalibrationUtil.canonicalize(1.0, null),
                CalibrationUtil.State.MISSING_UNIT);
        assertMissing(CalibrationUtil.canonicalize(1.0, "  "),
                CalibrationUtil.State.MISSING_UNIT);

        CalibrationUtil.CanonicalScale pixels = CalibrationUtil.canonicalize(1.0, "pixels");
        assertMissing(pixels, CalibrationUtil.State.PIXEL_UNIT);
        assertTrue(pixels.isPixelUnit());

        assertMissing(CalibrationUtil.canonicalize(1.0, "furlong"),
                CalibrationUtil.State.UNKNOWN_UNIT);
        CalibrationUtil.CanonicalCalibration absent = CalibrationUtil.canonicalize((ImagePlus) null);
        assertMissing(absent.x(), CalibrationUtil.State.MISSING_CALIBRATION);
        assertMissing(absent.y(), CalibrationUtil.State.MISSING_CALIBRATION);
        assertMissing(absent.z(), CalibrationUtil.State.MISSING_CALIBRATION);
    }

    @Test
    public void nonfiniteNonpositiveAndOverflowingScalesNeverBecomeMicrons() {
        assertMissing(CalibrationUtil.canonicalize(Double.NaN, "um"),
                CalibrationUtil.State.NONFINITE_SCALE);
        assertMissing(CalibrationUtil.canonicalize(Double.POSITIVE_INFINITY, "um"),
                CalibrationUtil.State.NONFINITE_SCALE);
        assertMissing(CalibrationUtil.canonicalize(Double.NEGATIVE_INFINITY, "um"),
                CalibrationUtil.State.NONFINITE_SCALE);
        assertMissing(CalibrationUtil.canonicalize(0.0, "um"),
                CalibrationUtil.State.NONPOSITIVE_SCALE);
        assertMissing(CalibrationUtil.canonicalize(-0.5, "um"),
                CalibrationUtil.State.NONPOSITIVE_SCALE);
        assertMissing(CalibrationUtil.canonicalize(Double.MAX_VALUE, "m"),
                CalibrationUtil.State.NONFINITE_SCALE);
    }

    @Test
    public void legacyNumericAdapterReturnsNaNInsteadOfFabricatingOneMicron() {
        ImagePlus invalid = imageWithCalibration(
                Double.NaN, -2.0, Double.POSITIVE_INFINITY, "um");

        assertTrue(Double.isNaN(CalibrationUtil.pixelSizeUm(invalid, CalibrationUtil.Axis.X)));
        assertTrue(Double.isNaN(CalibrationUtil.pixelSizeUm(invalid, CalibrationUtil.Axis.Y)));
        assertTrue(Double.isNaN(CalibrationUtil.pixelSizeUm(invalid, CalibrationUtil.Axis.Z)));
        assertTrue(Double.isNaN(CalibrationUtil.pixelSizeUm(null, CalibrationUtil.Axis.X)));
    }

    @Test
    public void strictConversionRejectsEveryNonphysicalResult() {
        assertEquals(0.5, CalibrationUtil.toMicrons(500.0, "nm"), EPSILON);
        assertEquals(4.0, CalibrationUtil.toMicrons(4.0, "micro-meters"), EPSILON);
        expectIllegalArgument(1.0, "pixel");
        expectIllegalArgument(1.0, "furlong");
        expectIllegalArgument(Double.NaN, "um");
        expectIllegalArgument(0.0, "um");
    }

    @Test
    public void canonicalizationIsIdempotentAndCannotDoubleConvertNanometers() {
        CalibrationUtil.CanonicalCalibration calibration = CalibrationUtil.canonicalize(
                500.0, 750.0, 2000.0, "nm");
        CalibrationUtil.CanonicalScale scale = calibration.x();

        assertSame(calibration, CalibrationUtil.canonicalize(calibration));
        assertSame(scale, CalibrationUtil.canonicalize(scale));
        assertEquals(0.5, CalibrationUtil.canonicalize(calibration).x().microns(), EPSILON);
        assertEquals(500.0, CalibrationUtil.canonicalize(calibration).x().rawValue(), 0.0);
    }

    @Test
    public void calibrationIoExposesCanonicalValuesWithoutOverwritingRawProvenance() {
        CalibrationIO.PixelCalibration stored = new CalibrationIO.PixelCalibration(
                250.0, 500.0, 1250.0, 5000.0, "Nanometres");

        assertEquals(250.0, stored.pixelWidth, 0.0);
        assertEquals(500.0, stored.pixelHeight, 0.0);
        assertEquals(1250.0, stored.pixelDepth, 0.0);
        assertEquals(5000.0, stored.stackDepth, 0.0);
        assertEquals("Nanometres", stored.unit);
        assertEquals(0.25, stored.canonical().x().microns(), EPSILON);
        assertEquals(0.5, stored.canonical().y().microns(), EPSILON);
        assertEquals(1.25, stored.canonical().z().microns(), EPSILON);
        assertEquals("Nanometres", stored.canonical().z().originalUnit());
        assertTrue(stored.isCalibrated());
    }

    private static void assertScale(CalibrationUtil.CanonicalScale scale,
                                    double expectedRaw, String expectedUnit,
                                    double expectedMicrons) {
        assertEquals(CalibrationUtil.State.PHYSICAL, scale.state());
        assertEquals(expectedRaw, scale.rawValue(), 0.0);
        assertEquals(expectedUnit, scale.originalUnit());
        assertEquals(expectedMicrons, scale.microns(), EPSILON);
        assertTrue(scale.hasMicrons());
    }

    private static void assertMissing(CalibrationUtil.CanonicalScale scale,
                                      CalibrationUtil.State expectedState) {
        assertEquals(expectedState, scale.state());
        assertFalse(scale.hasMicrons());
        assertTrue(Double.isNaN(scale.microns()));
    }

    private static ImagePlus imageWithCalibration(double pixelWidth,
                                                  double pixelHeight,
                                                  double pixelDepth,
                                                  String unit) {
        ImagePlus image = new ImagePlus("synthetic",
                new FloatProcessor(1, 1, new float[]{1.0f}, null));
        Calibration calibration = new Calibration();
        calibration.pixelWidth = pixelWidth;
        calibration.pixelHeight = pixelHeight;
        calibration.pixelDepth = pixelDepth;
        calibration.setUnit(unit);
        image.setCalibration(calibration);
        return image;
    }

    private static void expectIllegalArgument(final double value, final String unit) {
        try {
            CalibrationUtil.toMicrons(value, unit);
            fail("Expected IllegalArgumentException for " + value + " " + unit);
        } catch (IllegalArgumentException expected) {
            assertFalse(expected.getMessage().isEmpty());
        }
    }
}
