package flash.pipeline.representative;

import org.junit.Test;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class RepresentativeSelectionTest {

    @Test
    public void completeSelectionKeepsConditionOrderAndIsImmutable() {
        RepresentativeSeries control = series("0", "Control", "Control-1");
        RepresentativeSeries treatment = series("1", "Treatment", "Treatment-1");
        Map<String, RepresentativeSeries> selected =
                new LinkedHashMap<String, RepresentativeSeries>();
        selected.put("Treatment", treatment);
        selected.put("Control", control);

        RepresentativeSelection selection = new RepresentativeSelection(
                Arrays.asList("Control", "Treatment"), selected);

        assertTrue(selection.isComplete());
        assertEquals(2, selection.size());
        assertEquals(Arrays.asList("Control", "Treatment"), selection.conditionNames());
        assertEquals(control, selection.seriesForCondition("Control"));
        assertEquals(treatment, selection.seriesForCondition("Treatment"));
        assertEquals(Arrays.asList(control, treatment), selection.series());

        try {
            selection.asMap().put("Extra", control);
            fail("Selection map should be immutable.");
        } catch (UnsupportedOperationException expected) {
            // expected
        }
    }

    @Test
    public void missingConditionIsRejected() {
        RepresentativeSeries control = series("0", "Control", "Control-1");
        Map<String, RepresentativeSeries> selected =
                new LinkedHashMap<String, RepresentativeSeries>();
        selected.put("Control", control);

        try {
            new RepresentativeSelection(Arrays.asList("Control", "Treatment"), selected);
            fail("Incomplete selections should be rejected.");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("Treatment"));
        }
    }

    @Test
    public void mismatchedSeriesConditionIsRejected() {
        RepresentativeSeries treatment = series("1", "Treatment", "Treatment-1");
        Map<String, RepresentativeSeries> selected =
                new LinkedHashMap<String, RepresentativeSeries>();
        selected.put("Control", treatment);

        try {
            new RepresentativeSelection(Collections.singletonList("Control"), selected);
            fail("Condition mismatches should be rejected.");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("Treatment"));
        }
    }

    private static RepresentativeSeries series(String id,
                                               String condition,
                                               String name) {
        BufferedImage image = new BufferedImage(16, 12, BufferedImage.TYPE_INT_RGB);
        return new RepresentativeSeries(id, Integer.parseInt(id),
                Integer.parseInt(id) + 1, name, name, condition,
                "LH", "SCN", new File(name + ".lif"),
                Collections.singletonList(new RepresentativeSeries.ChannelThumbnail(
                        0, "DAPI", image, null)),
                image, null, RepresentativeSeries.PreviewSource.GENERATED, false);
    }
}
