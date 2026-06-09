package flash.pipeline.intensity.spatial;

import ij.ImagePlus;
import ij.gui.Roi;
import ij.measure.Calibration;
import ij.process.FloatProcessor;
import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class PairPlane2DTest {
    @After
    public void clearResourceGuardProperties() {
        System.clearProperty(SpatialResourceGuards.MAX_MIP_PIXELS_PROPERTY);
        System.clearProperty(SpatialResourceGuards.MAX_PAIR_PLANE_PIXELS_PROPERTY);
        System.clearProperty(SpatialResourceGuards.MAX_COLOC_IMAGE_PIXELS_PROPERTY);
    }

    @Test
    public void fromConvertsNanometerCalibrationToMicrons() {
        ImagePlus source = image(2, 2, 500.0, 250.0, "nm");
        ImagePlus partner = image(2, 2, 1.0, 1.0, "um");

        PairPlane2D plane = PairPlane2D.from(source, partner, null, null, 1, null);

        assertEquals(0.5, plane.pixelWidthUm, 0.0);
        assertEquals(0.25, plane.pixelHeightUm, 0.0);
    }

    @Test
    public void smallerMaskImageDoesNotThrowOrMarkOutOfBoundsPixels() {
        ImagePlus source = image(3, 3, 1.0, 1.0, "um");
        ImagePlus partner = image(3, 3, 1.0, 1.0, "um");
        ImagePlus partnerMask = image(3, 1, 1.0, 1.0, "um");

        PairPlane2D plane = PairPlane2D.from(source, partner, null, partnerMask, 1, null);

        assertEquals(9, plane.count);
        assertFalse(plane.partnerMask[2 * plane.width + 1]);
    }

    @Test
    public void fromRejectsOversizedPlaneBeforeAllocatingFullBuffers() {
        System.setProperty(SpatialResourceGuards.MAX_PAIR_PLANE_PIXELS_PROPERTY, "4");
        ImagePlus source = image(3, 3, 1.0, 1.0, "um");
        ImagePlus partner = image(3, 3, 1.0, 1.0, "um");

        try {
            PairPlane2D.from(source, partner, null, null, 1, null);
            fail("Expected pair-plane guard to reject a 9-pixel plane when the limit is 4.");
        } catch (IllegalArgumentException ex) {
            assertTrue(ex.getMessage().contains("guard limit is 4"));
        }
    }

    @Test
    public void fromUsesClippedRoiBoundsForPlaneBudget() {
        System.setProperty(SpatialResourceGuards.MAX_PAIR_PLANE_PIXELS_PROPERTY, "4");
        ImagePlus source = image(10, 10, 1.0, 1.0, "um");
        ImagePlus partner = image(10, 10, 1.0, 1.0, "um");

        PairPlane2D plane = PairPlane2D.from(source, partner, null, null, 1,
                new Roi(0, 0, 2, 2));

        assertEquals(2, plane.width);
        assertEquals(2, plane.height);
        assertEquals(4, plane.count);
    }

    @Test
    public void maxIntensityProjectionRejectsOversizedOutputBeforeAllocation() {
        System.setProperty(SpatialResourceGuards.MAX_MIP_PIXELS_PROPERTY, "4");
        ImagePlus source = image(3, 3, 1.0, 1.0, "um");

        try {
            IntensitySpatialRunner.maxIntensityProjection(source, "mip");
            fail("Expected MIP guard to reject a 9-pixel projection when the limit is 4.");
        } catch (IllegalArgumentException ex) {
            assertTrue(ex.getMessage().contains("guard limit is 4"));
        }
    }

    private static ImagePlus image(int width,
                                   int height,
                                   double pixelWidth,
                                   double pixelHeight,
                                   String unit) {
        float[] pixels = new float[width * height];
        for (int i = 0; i < pixels.length; i++) {
            pixels[i] = 1.0f;
        }
        ImagePlus image = new ImagePlus("synthetic",
                new FloatProcessor(width, height, pixels, null));
        Calibration calibration = new Calibration();
        calibration.pixelWidth = pixelWidth;
        calibration.pixelHeight = pixelHeight;
        calibration.pixelDepth = 1.0;
        calibration.setUnit(unit);
        image.setCalibration(calibration);
        return image;
    }
}
