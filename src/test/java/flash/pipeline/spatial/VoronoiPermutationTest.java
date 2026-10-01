package flash.pipeline.spatial;

import org.junit.Test;
import static org.junit.Assert.*;

public class VoronoiPermutationTest {
    @Test
    public void disabledPermutationTestHasMissingPValues() {
        VoronoiAnalysis.InteractionMatrix result = VoronoiAnalysis.computeInteractionMatrix(
                ring(12), alternatingTypes(12), 0, 42);
        assertTrue(Double.isNaN(result.pValues[0][0]));
        assertTrue(Double.isNaN(result.pValues[0][1]));
    }

    @Test
    public void detectsDepletionAndEnrichmentWithNonzeroRepeatableTwoSidedPValues() {
        VoronoiAnalysis.InteractionMatrix first = VoronoiAnalysis.computeInteractionMatrix(
                ring(12), alternatingTypes(12), 2000, 42);
        VoronoiAnalysis.InteractionMatrix second = VoronoiAnalysis.computeInteractionMatrix(
                ring(12), alternatingTypes(12), 2000, 42);
        assertEquals(0, first.counts[0][0]);
        assertEquals(12, first.counts[0][1]);
        assertTrue(first.pValues[0][0] > 0.0);
        assertTrue(first.pValues[0][0] < 0.05);
        assertEquals(first.pValues[0][0], first.pValues[0][1], 0.0);
        assertEquals(first.pValues[0][1], first.pValues[1][0], 0.0);
        assertEquals(first.pValues[0][0], second.pValues[0][0], 0.0);
    }

    @Test
    public void invariantSingleTypeEdgesAreNeutral() {
        VoronoiAnalysis.InteractionMatrix result = VoronoiAnalysis.computeInteractionMatrix(
                ring(3), new String[]{"A", "A", "A"}, 10, 42);
        assertEquals(1.0, result.pValues[0][0], 0.0);
    }

    @Test
    public void closeCentroidsReceiveTheirOwnGeneratingTerritory() {
        VoronoiAnalysis.VoronoiResult[] results = VoronoiAnalysis.compute(
                new double[][]{{2e-7, 0}, {0, 0}},
                new SpatialStatistics.RectangularWindow(-1, -1, 2, 1));
        assertEquals(4.0 - 2e-7, results[0].territoryArea, 1e-10);
        assertEquals(2.0 + 2e-7, results[1].territoryArea, 1e-10);
        assertEquals(1, results[0].numNeighbors);
        assertEquals(1, results[1].numNeighbors);
    }

    @Test(expected = IllegalArgumentException.class)
    public void coincidentCentroidsCannotFabricateZeroTerritories() {
        VoronoiAnalysis.compute(new double[][]{{0, 0}, {0, 0}},
                new SpatialStatistics.RectangularWindow(-1, -1, 1, 1));
    }

    private static VoronoiAnalysis.VoronoiResult[] ring(int size) {
        VoronoiAnalysis.VoronoiResult[] results = new VoronoiAnalysis.VoronoiResult[size];
        for (int i = 0; i < size; i++) {
            results[i] = new VoronoiAnalysis.VoronoiResult(i, 1.0, 2,
                    new int[]{(i + size - 1) % size, (i + 1) % size});
        }
        return results;
    }

    private static String[] alternatingTypes(int size) {
        String[] types = new String[size];
        for (int i = 0; i < size; i++) types[i] = i % 2 == 0 ? "A" : "B";
        return types;
    }
}
