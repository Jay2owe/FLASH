package flash.pipeline.representative;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class RepresentativeLayoutTest {

    @Test
    public void rowAssignmentsCaptureRowsAndColumnOrder() {
        RepresentativeLayout layout = RepresentativeLayout.fromRowAssignments(
                Arrays.asList("Control", "Drug A", "Drug B", "Washout"),
                Arrays.asList(Integer.valueOf(1), Integer.valueOf(2),
                        Integer.valueOf(1), Integer.valueOf(2)));

        assertEquals(2, layout.rowCount());
        assertEquals(4, layout.conditionCount());
        assertEquals(2, layout.maxColumnCount());
        assertEquals(Arrays.asList(
                Arrays.asList("Control", "Drug B"),
                Arrays.asList("Drug A", "Washout")), layout.rows());
        assertEquals(Arrays.asList("Control", "Drug B", "Drug A", "Washout"),
                layout.flattenedConditions());
    }

    @Test
    public void layoutRejectsDuplicateConditionsAndIsImmutable() {
        try {
            new RepresentativeLayout(Arrays.asList(
                    Collections.singletonList("Control"),
                    Collections.singletonList("Control")));
            fail("Duplicate conditions should be rejected.");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("Control"));
        }

        RepresentativeLayout layout = RepresentativeLayout.allInOneRow(
                Arrays.asList("Control", "Treatment"));
        try {
            layout.rows().add(Collections.singletonList("Extra"));
            fail("Rows should be immutable.");
        } catch (UnsupportedOperationException expected) {
            // expected
        }
        try {
            layout.rows().get(0).add("Extra");
            fail("Row contents should be immutable.");
        } catch (UnsupportedOperationException expected) {
            // expected
        }
    }

    @Test
    public void containsExactlyConditionsIgnoresOrderButNotMembership() {
        RepresentativeLayout layout = new RepresentativeLayout(Arrays.asList(
                Arrays.asList("Control", "Drug"),
                Collections.singletonList("Washout")));

        assertTrue(layout.containsExactlyConditions(
                Arrays.asList("Washout", "Control", "Drug")));
    }
}
