package flash.pipeline.results;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertTrue;

/**
 * Stage 09: Object Intensity Profiling scalar columns sort into the bounding-box section (after
 * BB-coloc, before Voronoi) and group per partner.
 */
public class ObjectCsvColumnOrderOipTest {

    @Test
    public void oipColumnsSortAfterBBAndBeforeVoronoi() {
        List<String> channels = Arrays.asList("DAPI", "MOAB2");
        List<String> cols = Arrays.asList(
                "Voronoi_NumNeighbors",
                "DAPI_OIPRadialCoreEdge_MOAB2",
                "DAPI_BBColoc_MOAB2",
                "Label");

        List<String> ordered = ObjectCsvColumnOrder.orderedColumns("DAPI", cols, channels);

        int label = ordered.indexOf("Label");
        int bb = ordered.indexOf("DAPI_BBColoc_MOAB2");
        int oip = ordered.indexOf("DAPI_OIPRadialCoreEdge_MOAB2");
        int voronoi = ordered.indexOf("Voronoi_NumNeighbors");

        assertTrue("Label first", label < bb);
        assertTrue("BB before OIP", bb < oip);
        assertTrue("OIP before Voronoi", oip < voronoi);
    }

    @Test
    public void oipMetricsGroupPerPartnerInOrder() {
        List<String> channels = Arrays.asList("DAPI", "MOAB2", "GFAP");
        List<String> cols = Arrays.asList(
                "DAPI_OIPManders_GFAP",
                "DAPI_OIPRadialCoreEdge_GFAP",
                "DAPI_OIPRadialCoreEdge_MOAB2");
        List<String> ordered = ObjectCsvColumnOrder.orderedColumns("DAPI", cols, channels);
        // MOAB2 (partner rank 0) before GFAP (rank 1); within GFAP, RadialCoreEdge (item 10)
        // before Manders (item 20).
        assertTrue(ordered.indexOf("DAPI_OIPRadialCoreEdge_MOAB2") < ordered.indexOf("DAPI_OIPRadialCoreEdge_GFAP"));
        assertTrue(ordered.indexOf("DAPI_OIPRadialCoreEdge_GFAP") < ordered.indexOf("DAPI_OIPManders_GFAP"));
    }
}
