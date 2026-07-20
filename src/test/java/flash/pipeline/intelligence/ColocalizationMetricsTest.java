package flash.pipeline.intelligence;

import org.junit.Test;

import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ColocalizationMetricsTest {

    @Test
    public void perfectColocalizationHasHighMandersAndSmallCostesP() {
        int width = 20;
        int height = 20;
        double[] a = new double[width * height];
        double[] b = new double[width * height];
        Random random = new Random(11L);
        for (int i = 0; i < a.length; i++) {
            a[i] = 1.0 + random.nextDouble() * 100.0;
            b[i] = a[i];
        }

        ColocalizationMetrics.Result result =
                ColocalizationMetrics.compute(a, b, width, height, 1);

        assertEquals(1.0, result.pearson, 1.0e-9);
        assertEquals(1.0, result.mandersM1, 0.05);
        assertEquals(1.0, result.mandersM2, 0.05);
        assertEquals(1.0, result.pearsonThresholded, 1.0e-9);
        assertEquals(1.0 / 101.0, result.costesP, 0.02);
    }

    @Test
    public void antiCorrelatedDisjointSignalsHaveLowMandersAndLargeCostesP() {
        int width = 20;
        int height = 20;
        double[] a = new double[width * height];
        double[] b = new double[width * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int i = y * width + x;
                if (x < width / 2) {
                    a[i] = 100.0;
                    b[i] = 0.0;
                } else {
                    a[i] = 0.0;
                    b[i] = 100.0;
                }
            }
        }

        ColocalizationMetrics.Result result =
                ColocalizationMetrics.compute(a, b, width, height, 1);

        assertEquals(-1.0, result.pearson, 1.0e-9);
        assertEquals(0.0, result.mandersM1, 0.05);
        assertEquals(0.0, result.mandersM2, 0.05);
        assertTrue(result.costesP > 0.9);
    }

    @Test
    public void independentSparseSignalsTrackSignalDensity() {
        int width = 40;
        int height = 40;
        double[] a = new double[width * height];
        double[] b = new double[width * height];
        Random random = new Random(23L);
        for (int i = 0; i < a.length; i++) {
            a[i] = random.nextDouble() < 0.25 ? 100.0 : 0.0;
            b[i] = random.nextDouble() < 0.25 ? 100.0 : 0.0;
        }

        ColocalizationMetrics.Result result =
                ColocalizationMetrics.compute(a, b, width, height, 1);

        assertEquals(0.0, result.pearson, 0.1);
        assertEquals(0.25, result.mandersM1, 0.1);
        assertEquals(0.25, result.mandersM2, 0.1);
        assertTrue(result.costesP > 0.3);
    }

    @Test
    public void sharedBackgroundCanInflatePearsonWhileMandersStaysLow() {
        int width = 50;
        int height = 50;
        double[] a = new double[width * height];
        double[] b = new double[width * height];
        Random random = new Random(37L);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int i = y * width + x;
                a[i] = 10.0 + random.nextDouble();
                b[i] = 10.0 + random.nextDouble();
                if (x >= 16 && x < 28 && y >= 16 && y < 28) {
                    a[i] = 80.0;
                    b[i] = 80.0;
                }
                if (x >= 3 && x < 10 && y >= 3 && y < 10) {
                    a[i] = 200.0;
                }
                if (x >= 40 && x < 47 && y >= 40 && y < 47) {
                    b[i] = 200.0;
                }
            }
        }

        ColocalizationMetrics.Result result =
                ColocalizationMetrics.compute(a, b, width, height, 1);

        String values = "pearson=" + result.pearson
                + ", m1=" + result.mandersM1
                + ", m2=" + result.mandersM2
                + ", ta=" + result.costesTa
                + ", tb=" + result.costesTb;
        assertTrue(values, result.pearson > 0.2);
        assertTrue(values, result.mandersM1 < 0.35);
        assertTrue(values, result.mandersM2 < 0.1);
    }
}
