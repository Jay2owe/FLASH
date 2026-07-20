package flash.pipeline.intensity.spatial;

import flash.pipeline.analyses.wizard.IntensitySpatialConfig;
import ij.ImagePlus;
import ij.measure.Calibration;
import ij.process.FloatProcessor;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DistanceShell2DAnalysisTest {
    @Test
    public void sourceIntensityIncreasingAwayFromPartnerMaskReportsPositiveSlope() {
        Fixture fixture = shellGradientFixture(48, 48);

        IntensitySpatialResult result = new DistanceShell2DAnalysis().measure(context(
                fixture.source, fixture.partner, fixture.partnerMask));

        assertTrue(result.value("DAPI_DistShellSlope_mCherry") > 0.0);
        assertTrue(result.value("DAPI_DistShell0to4_mCherry")
                < result.value("DAPI_DistShell12to16_mCherry"));
        assertTrue(Double.isFinite(result.value("DAPI_DistShellAUC_mCherry")));
    }

    @Test
    public void distanceShellColumnsRequirePartnerBinarization() {
        assertTrue(new DistanceShell2DAnalysis().columns(config(), "DAPI", "mCherry",
                false, true).contains("DAPI_DistShell0to4_mCherry"));
        assertTrue(new DistanceShell2DAnalysis().columns(config(), "DAPI", "mCherry",
                false, false).isEmpty());
    }

    @Test
    public void noPartnerMaskReturnsNoValues() {
        IntensitySpatialResult result = new DistanceShell2DAnalysis().measure(context(
                shellGradientFixture(24, 24).source,
                shellGradientFixture(24, 24).partner,
                null));

        assertFalse(result.values().containsKey("DAPI_DistShellSlope_mCherry"));
    }

    private static IntensitySpatialPairContext context(ImagePlus source,
                                                       ImagePlus partner,
                                                       ImagePlus partnerMask) {
        return new IntensitySpatialPairContext(config(), source, null, null,
                partner, null, partnerMask, 1, null, IntensitySpatialOutputMode.BASE,
                "synthetic", "DAPI", "mCherry", "", null);
    }

    private static IntensitySpatialConfig config() {
        return IntensitySpatialConfig.builder()
                .enabled(true)
                .addAnalysis(IntensitySpatialConfig.AnalysisKey.DISTANCE_SHELL)
                .shellWidthUm(4.0)
                .shellCount(4)
                .build();
    }

    private static Fixture shellGradientFixture(int width, int height) {
        float[] source = new float[width * height];
        float[] partner = new float[width * height];
        float[] mask = new float[width * height];
        double cx = (width - 1) / 2.0;
        double cy = (height - 1) / 2.0;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                double distance = Math.sqrt((x - cx) * (x - cx) + (y - cy) * (y - cy));
                int index = y * width + x;
                source[index] = (float) (5.0 + distance);
                if (distance <= 2.0) {
                    partner[index] = 255.0f;
                    mask[index] = 255.0f;
                }
            }
        }
        return new Fixture(image(width, height, source), image(width, height, partner),
                image(width, height, mask));
    }

    private static ImagePlus image(int width, int height, float[] pixels) {
        ImagePlus image = new ImagePlus("synthetic",
                new FloatProcessor(width, height, pixels, null));
        Calibration calibration = new Calibration();
        calibration.pixelWidth = 1.0;
        calibration.pixelHeight = 1.0;
        calibration.setUnit("um");
        image.setCalibration(calibration);
        return image;
    }

    private static final class Fixture {
        final ImagePlus source;
        final ImagePlus partner;
        final ImagePlus partnerMask;

        private Fixture(ImagePlus source, ImagePlus partner, ImagePlus partnerMask) {
            this.source = source;
            this.partner = partner;
            this.partnerMask = partnerMask;
        }
    }
}
