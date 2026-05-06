package flash.pipeline.export;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ExcelPGradientTest {

    @Test
    public void pEqualToOneReturnsPureWhite() {
        byte[] rgb = ExcelSummaryExportAnalysis.pGradientRgb(1.0);
        assertEquals(-1, rgb[0]); // 0xFF
        assertEquals(-1, rgb[1]);
        assertEquals(-1, rgb[2]);
    }

    @Test
    public void pEqualToOneTenthIsLightPink() {
        byte[] rgb = ExcelSummaryExportAnalysis.pGradientRgb(0.1);
        // intensity = log10(0.1)/log10(0.001) = (-1)/(-3) = 0.333
        // greenBlue = 255 - 0.333*(255-30) = 255 - 75 = 180
        assertEquals((byte) 0xFF, rgb[0]);
        int gb = rgb[1] & 0xFF;
        assertTrue("Expected mid-pink (~180), got " + gb, gb >= 170 && gb <= 190);
        assertEquals(rgb[1], rgb[2]);
    }

    @Test
    public void pValueNearZeroReachesDeepRed() {
        byte[] rgb = ExcelSummaryExportAnalysis.pGradientRgb(0.0001);
        // intensity saturates to 1.0 at p <= 0.001
        assertEquals((byte) 0xFF, rgb[0]);
        int gb = rgb[1] & 0xFF;
        assertTrue("Expected deep red (~30), got " + gb, gb <= 40);
    }

    @Test
    public void pHighValueStaysNearWhite() {
        byte[] rgb = ExcelSummaryExportAnalysis.pGradientRgb(0.9);
        int gb = rgb[1] & 0xFF;
        assertTrue("Expected near-white (>=230), got " + gb, gb >= 230);
    }

    @Test
    public void nanReturnsWhite() {
        byte[] rgb = ExcelSummaryExportAnalysis.pGradientRgb(Double.NaN);
        assertEquals((byte) 0xFF, rgb[0]);
        assertEquals((byte) 0xFF, rgb[1]);
        assertEquals((byte) 0xFF, rgb[2]);
    }

    @Test
    public void significanceStarsAreNumericallyOrdered() {
        assertEquals("****", ExcelSummaryExportAnalysis.starsFor(0.00001));
        assertEquals("***", ExcelSummaryExportAnalysis.starsFor(0.0005));
        assertEquals("**", ExcelSummaryExportAnalysis.starsFor(0.005));
        assertEquals("*", ExcelSummaryExportAnalysis.starsFor(0.04));
        assertEquals("ns", ExcelSummaryExportAnalysis.starsFor(0.2));
        assertEquals("", ExcelSummaryExportAnalysis.starsFor(Double.NaN));
    }
}
