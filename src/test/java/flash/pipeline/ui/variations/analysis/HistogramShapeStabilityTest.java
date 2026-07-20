package flash.pipeline.ui.variations.analysis;

import flash.pipeline.ui.variations.FilterParameterId;
import flash.pipeline.ui.variations.ParameterCombo;
import flash.pipeline.ui.variations.ParameterKey;
import flash.pipeline.ui.variations.VariationResult;

import org.junit.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class HistogramShapeStabilityTest {

    @Test
    public void migratingHistogramsReturnTailPlateauWinner() {
        FilterParameterId sigma = sigma();
        List<VariationResult> results = new ArrayList<VariationResult>();
        results.add(result(sigma, 0.0d, migratingHistogram(0)));
        results.add(result(sigma, 1.0d, migratingHistogram(80)));
        results.add(result(sigma, 2.0d, migratingHistogram(160)));
        results.add(result(sigma, 3.0d, migratingHistogram(200)));
        results.add(result(sigma, 4.0d, migratingHistogram(200)));
        results.add(result(sigma, 5.0d, migratingHistogram(200)));

        HistogramShapeStability.Result stable =
                HistogramShapeStability.detect(results, sigma);

        assertTrue(stable.hasPlateau());
        assertNotNull(stable.winnerCombo);
        assertEquals(Double.valueOf(3.0d), stable.plateauStartCombo.get(sigma));
        assertEquals(Double.valueOf(5.0d), stable.plateauEndCombo.get(sigma));
        assertEquals(Double.valueOf(4.0d), stable.winnerCombo.get(sigma));
        assertEquals(5, stable.distances.length);
    }

    @Test
    public void randomHistogramsReturnNoWinner() {
        FilterParameterId sigma = sigma();
        int[] peaks = { 10, 200, 30, 220, 40, 180 };
        List<VariationResult> results = new ArrayList<VariationResult>();
        for (int i = 0; i < peaks.length; i++) {
            results.add(result(sigma, i, migratingHistogram(peaks[i])));
        }

        HistogramShapeStability.Result stable =
                HistogramShapeStability.detect(results, sigma);

        assertFalse(stable.hasPlateau());
        assertEquals(null, stable.winnerCombo);
    }

    private static FilterParameterId sigma() {
        return new FilterParameterId(0, 0, 0, "Gaussian Blur", "sigma");
    }

    private static VariationResult result(ParameterKey axis,
                                          double value,
                                          int[] histogram) {
        Map<ParameterKey, Object> values =
                new LinkedHashMap<ParameterKey, Object>();
        values.put(axis, Double.valueOf(value));
        ParameterCombo combo = new ParameterCombo(values);
        return VariationResult.filterSuccess(combo, null, 1L,
                histogram, 1.0d, 1.0d);
    }

    private static int[] migratingHistogram(int startBin) {
        int[] histogram = new int[256];
        int start = Math.max(0, Math.min(250, startBin));
        for (int i = 0; i < 6; i++) {
            histogram[start + i] = 100;
        }
        return histogram;
    }
}
