package flash.pipeline.results;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Stage 01 foundations: bounding-box volume columns sort immediately after the existing
 * bounding-box columns (after {@code B-depth}, before any later section).
 */
public class ObjectCsvColumnOrderTest {

    private static final List<String> CHANNELS = Arrays.asList("C1", "C2");

    @Test
    public void boundingBoxVolumeSortsRightAfterBDepth() {
        // Deliberately shuffled input.
        List<String> input = Arrays.asList(
                "B-volume (micron^3)",
                "Label",
                "B-depth",
                "BX",
                "B-volume (voxels)",
                "XM",
                "B-height",
                "BZ",
                "B-width",
                "BY");

        List<String> ordered = ObjectCsvColumnOrder.orderedColumns("C1", input, CHANNELS);

        // Full bounding-box block must be contiguous and in canonical order.
        List<String> expectedBoxBlock = Arrays.asList(
                "BX", "BY", "BZ", "B-width", "B-height", "B-depth",
                "B-volume (voxels)", "B-volume (micron^3)");
        int start = ordered.indexOf("BX");
        assertTrue("BX should be present", start >= 0);
        assertEquals(expectedBoxBlock, ordered.subList(start, start + expectedBoxBlock.size()));

        // Specifically: voxel volume directly follows B-depth; micron volume directly follows voxel.
        assertEquals(ordered.indexOf("B-depth") + 1, ordered.indexOf("B-volume (voxels)"));
        assertEquals(ordered.indexOf("B-volume (voxels)") + 1, ordered.indexOf("B-volume (micron^3)"));
    }

    @Test
    public void bbColocContinuousSortsBeforeThresholdFlag() {
        List<String> input = Arrays.asList(
                "Label",
                "C1_BBColoc30_C2",      // flag
                "C1_CPCColoc_C2",       // CPC section (earlier)
                "Voronoi_NumNeighbors", // later section
                "C1_BBColoc_C2",        // continuous overlap
                "Morph_Area_um2");      // later section

        List<String> ordered = ObjectCsvColumnOrder.orderedColumns("C1", input, CHANNELS);

        int continuous = ordered.indexOf("C1_BBColoc_C2");
        int flag = ordered.indexOf("C1_BBColoc30_C2");
        int cpc = ordered.indexOf("C1_CPCColoc_C2");
        int voronoi = ordered.indexOf("Voronoi_NumNeighbors");

        // Continuous overlap immediately precedes its threshold flag (digit-aware classifier).
        assertEquals(continuous + 1, flag);
        // BB coloc section sits after CPC and before Voronoi/Morph.
        assertTrue("CPC before BBColoc", cpc < continuous);
        assertTrue("BBColoc before Voronoi", flag < voronoi);
    }

    @Test
    public void bbCpcColumnsClassifyDistinctlyFromBBOverlap() {
        List<String> input = Arrays.asList(
                "Label",
                "C1_BBCPCPattern",
                "C1_BBCPCContains_C2",
                "C1_BBColoc_C2",
                "C1_BBCPCColoc_C2",
                "C1_BBColoc30_C2",
                "C1_BBCPCTargetsHit",
                "Voronoi_NumNeighbors");

        List<String> ordered = ObjectCsvColumnOrder.orderedColumns("C1", input, CHANNELS);

        // Within a partner: overlap continuous, overlap flag, then BBCPC coloc, BBCPC contains.
        int overlap = ordered.indexOf("C1_BBColoc_C2");
        int overlapFlag = ordered.indexOf("C1_BBColoc30_C2");
        int cpcColoc = ordered.indexOf("C1_BBCPCColoc_C2");
        int cpcContains = ordered.indexOf("C1_BBCPCContains_C2");
        int targetsHit = ordered.indexOf("C1_BBCPCTargetsHit");
        int pattern = ordered.indexOf("C1_BBCPCPattern");
        int voronoi = ordered.indexOf("Voronoi_NumNeighbors");

        assertTrue(overlap < overlapFlag);
        assertTrue(overlapFlag < cpcColoc);
        assertTrue(cpcColoc < cpcContains);
        // Whole-object roll-ups terminate the BB section, still before Voronoi.
        assertTrue(cpcContains < targetsHit);
        assertTrue(targetsHit < pattern);
        assertTrue(pattern < voronoi);
    }
}
