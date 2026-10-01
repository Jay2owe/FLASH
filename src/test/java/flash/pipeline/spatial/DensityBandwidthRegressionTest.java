package flash.pipeline.spatial;

import org.junit.Test;
import ij.ImagePlus;
import static org.junit.Assert.assertEquals;

public class DensityBandwidthRegressionTest {
    @Test
    public void anisotropicAxesKeepKernelCircularInPhysicalSpaceAndCentreInCorrectPixel() {
        ImagePlus result = DensityHeatmapGenerator.generate(new double[][]{{10, 20}},
                21, 21, 1.0, 2.0, 4.0);
        // The centre is pixel (10,10), and +4um on either axis gives the same density.
        assertEquals(result.getProcessor().getf(14, 10),
                result.getProcessor().getf(10, 12), 1e-10);
        assertEquals(1.0, result.getCalibration().pixelWidth, 0.0);
        assertEquals(2.0, result.getCalibration().pixelHeight, 0.0);
    }
    @Test
    public void scottBandwidthUsesTwoDimensionalExponent() {
        // X and Y sample standard deviations are sqrt(2), n=2, d=2.
        assertEquals(Math.sqrt(2.0) * Math.pow(2.0, -1.0 / 6.0),
                DensityHeatmapGenerator.scottsRule(new double[][]{{0, 0}, {2, 2}}), 1e-12);
    }

    @Test(expected = IllegalArgumentException.class)
    public void missingPhysicalScaleCannotBecomeOneMicron() {
        DensityHeatmapGenerator.generate(new double[][]{{0, 0}}, 2, 2,
                Double.NaN, 1.0, 1.0);
    }
}
