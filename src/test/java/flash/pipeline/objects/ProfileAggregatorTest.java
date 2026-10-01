package flash.pipeline.objects;

import org.junit.Test;

import static org.junit.Assert.*;

public class ProfileAggregatorTest {
    @Test
    public void sampleSemIsOneForValuesOneAndThree() {
        ProfileAggregator aggregator = new ProfileAggregator();
        add(aggregator, 1.0);
        add(aggregator, 3.0);
        ProfileAggregator.AggregatedProfile result = aggregator.results().get(0);
        assertEquals(2.0, result.mean[0], 0.0);
        assertEquals(1.0, result.sem[0], 1e-12);
        assertEquals(2, result.n[0]);
    }

    @Test
    public void singletonAndNonFiniteBinsCannotClaimEstimatedUncertainty() {
        ProfileAggregator aggregator = new ProfileAggregator();
        aggregator.add("source", "partner", ProfileAggregator.RADIAL, "group",
                new double[]{1.0, Double.NaN, Double.POSITIVE_INFINITY});
        ProfileAggregator.AggregatedProfile result = aggregator.results().get(0);
        assertTrue(Double.isNaN(result.sem[0]));
        assertEquals(0, result.n[1]);
        assertEquals(0, result.n[2]);
    }

    @Test
    public void mergePreservesSampleSemAndSmallVarianceOnLargeOffsets() {
        ProfileAggregator first = new ProfileAggregator();
        ProfileAggregator second = new ProfileAggregator();
        add(first, 1e12 + 1.0);
        add(second, 1e12 + 3.0);
        first.merge(second);
        ProfileAggregator.AggregatedProfile result = first.results().get(0);
        assertEquals(1e12 + 2.0, result.mean[0], 0.0);
        assertEquals(1.0, result.sem[0], 1e-12);
    }

    private static void add(ProfileAggregator aggregator, double value) {
        aggregator.add("source", "partner", ProfileAggregator.RADIAL, "group",
                new double[]{value});
    }

    @Test
    public void mergedGroupAndReturnedCountsAreIndependentSnapshots() {
        ProfileAggregator source = new ProfileAggregator();
        add(source, 1.0);
        ProfileAggregator destination = new ProfileAggregator();
        destination.merge(source);
        ProfileAggregator.AggregatedProfile snapshot = destination.results().get(0);
        add(source, 3.0);
        assertEquals(1, destination.results().get(0).n[0]);
        add(destination, 5.0);
        assertEquals(1, snapshot.n[0]);
        assertEquals(1.0, snapshot.mean[0], 0.0);
    }

    @Test(expected = IllegalArgumentException.class)
    public void differentBinAxesCannotBeSilentlyCombined() {
        ProfileAggregator aggregator = new ProfileAggregator();
        add(aggregator, 1.0);
        aggregator.add("source", "partner", ProfileAggregator.RADIAL, "group",
                new double[]{1.0, 2.0});
    }

    @Test(expected = IllegalArgumentException.class)
    public void differentBinAxesCannotBeSilentlyMerged() {
        ProfileAggregator first = new ProfileAggregator();
        ProfileAggregator second = new ProfileAggregator();
        add(first, 1.0);
        second.add("source", "partner", ProfileAggregator.RADIAL, "group",
                new double[]{1.0, 2.0});
        first.merge(second);
    }
}
