package flash.pipeline.roi;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class RegionSelectionFilterTest {

    @Test
    public void includeSelectsOnlyMatchingRegionsCaseAndSuffixInsensitive() {
        boolean[] selected = new boolean[]{true, true, true};

        RegionSelectionFilter.apply(
                Arrays.asList(" scn rois.zip ", "hippo-campus"),
                null,
                new String[]{"SCN", "Cortex", "Hippo Campus"},
                selected);

        assertArrayEquals(new boolean[]{true, false, true}, selected);
    }

    @Test
    public void excludeClearsFromExistingSelectionWithoutSelectingOthers() {
        boolean[] selected = new boolean[]{true, false, true};

        RegionSelectionFilter.apply(
                null,
                Arrays.asList("SCN"),
                new String[]{"SCN", "Cortex", "PVN"},
                selected);

        assertArrayEquals(new boolean[]{false, false, true}, selected);
    }

    @Test
    public void includeThenExcludeLetsExcludeWin() {
        boolean[] selected = new boolean[]{false, false, false};

        RegionSelectionFilter.apply(
                Arrays.asList("SCN", "Cortex"),
                Arrays.asList("cortex"),
                new String[]{"SCN", "Cortex", "PVN"},
                selected);

        assertArrayEquals(new boolean[]{true, false, false}, selected);
    }

    @Test
    public void normalizeHandlesZipSuffixSeparatorsAndWhitespace() {
        assertEquals("hippo campus", RegionSelectionFilter.normalize(" Hippo_Campus ROIs.zip "));
        assertTrue(RegionSelectionFilter.hasFilter(Arrays.asList("SCN"), null));
    }
}
