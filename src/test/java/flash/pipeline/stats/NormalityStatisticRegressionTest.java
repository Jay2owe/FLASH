package flash.pipeline.stats;

import org.junit.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.assertEquals;

public class NormalityStatisticRegressionTest {
    // Independent reference values: scipy.stats.normaltest, SciPy 1.14.1.
    @Test
    public void dagostinoPearsonMatchesPublishedSciPyWeightExample() {
        assertEquals(13.034263121192582, statistic(
                148, 154, 158, 160, 161, 162, 166, 170, 182, 195, 236), 1e-10);
    }

    @Test
    public void transformedStatisticDetectsSkewMissedByRawMomentApproximation() {
        assertEquals(6.9050123510816555, statistic(
                0, 1, 1, 2, 3, 4, 6, 8, 9, 10, 11, 12, 13, 15, 18, 19, 23, 29, 30, 44), 1e-10);
    }

    @Test
    public void nearSymmetricSampleAndLinearRescalingMatchReference() {
        double[] sample = new double[25];
        for (int i = 0; i < sample.length; i++) sample[i] = i + 1;
        sample[24] = 25.1;
        assertEquals(4.044388333946298, statistic(sample), 1e-10);
        for (int i = 0; i < sample.length; i++) sample[i] = sample[i] * 10 + 100;
        assertEquals(4.044388333946298, statistic(sample), 1e-10);
    }

    private static double statistic(double... values) {
        List<Double> data = new ArrayList<Double>();
        for (double value : values) data.add(value);
        return MetricStatisticsEngine.dagostinoPearsonK2(data);
    }
}
