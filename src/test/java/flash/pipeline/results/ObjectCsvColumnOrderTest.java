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
}
