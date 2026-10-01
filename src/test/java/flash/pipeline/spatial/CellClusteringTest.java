package flash.pipeline.spatial;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class CellClusteringTest {
    @Test
    public void singletonContributesZeroRatherThanPerfectSeparation() {
        // The two paired samples have silhouettes 0.9 and 8/9; singleton = 0.
        assertEquals((0.9 + 8.0 / 9.0) / 3.0,
                CellClustering.silhouetteScore(new double[][]{{0}, {1}, {10}},
                        new int[]{0, 0, 1}, 2), 1e-12);
    }

    @Test
    public void identicalPointsWithEmptyRequestedClustersHaveNoSeparation() {
        CellClustering.ClusterResult result = CellClustering.cluster(
                new double[][]{{1}, {1}, {1}, {1}}, 3, 42L);
        assertEquals(0.0, result.silhouetteScore, 0.0);
    }

    @Test
    public void automaticClusterCountPreservesTwoClearlySeparatedGroups() {
        CellClustering.ClusterResult result = CellClustering.autoCluster(
                new double[][]{{0}, {0.1}, {10}, {10.1}}, 2, 4, 42L);
        assertEquals(2, result.k);
    }

    @Test
    public void allSingletonsHaveNoEstimatedSilhouette() {
        assertEquals(0.0, CellClustering.silhouetteScore(
                new double[][]{{0}, {5}, {10}}, new int[]{0, 1, 2}, 3), 0.0);
    }

    @Test
    public void featureVarianceAndClusterCentresSurviveLargeOffsets() {
        // Population variance of 0,2,10,12 is 26; the two group means are 1 and 11.
        // Adding an offset must retain normalized group means +/-5/sqrt(26).
        for (double offset : new double[]{0.0, 1e12}) {
            CellClustering.ClusterResult result = CellClustering.cluster(
                    new double[][]{{offset}, {offset + 2}, {offset + 10}, {offset + 12}}, 2, 42);
            assertEquals(5.0 / Math.sqrt(26.0), Math.abs(result.centroids[0][0]), 1e-12);
            assertEquals(5.0 / Math.sqrt(26.0), Math.abs(result.centroids[1][0]), 1e-12);
        }
    }
}
