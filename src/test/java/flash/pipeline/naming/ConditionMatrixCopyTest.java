package flash.pipeline.naming;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Unit tests for {@link ConditionMatrixCopy}. */
public class ConditionMatrixCopyTest {

    private static final String X = ConditionMatrixCopy.CROSS;

    private static ConditionAssignments twoAxisFixture() {
        // 3 genotypes x 3 timepoints across 12 animals; one matrix cell left empty
        // (no Syn / WeekEight) so capacity (9) != populated (8).
        ConditionAssignments ca = new ConditionAssignments();
        ca.addAxis(ConditionAxis.of("Genotype"));
        ca.addAxis(ConditionAxis.of("Timepoint"));
        String[] genos = {"Syn", "hAPP", "NLGF"};
        String[] times = {"WeekTwo", "WeekFour", "WeekEight"};
        int animal = 0;
        for (int g = 0; g < genos.length; g++) {
            for (int t = 0; t < times.length; t++) {
                if (g == 0 && t == 2) continue;            // skip Syn/WeekEight -> 8 populated
                String id = "M" + (animal++);
                ca.put(id, "genotype", genos[g]);
                ca.put(id, "timepoint", times[t]);
            }
        }
        // 8 so far; add 4 duplicates to reach 12 animals without new groups.
        for (int i = 0; i < 4; i++) {
            String id = "D" + i;
            ca.put(id, "genotype", "hAPP");
            ca.put(id, "timepoint", "WeekFour");
        }
        return ca;
    }

    @Test
    public void emptyAxes_fallBackToCondition() {
        List<ConditionAxis> none = new ArrayList<ConditionAxis>();
        assertFalse(ConditionMatrixCopy.isMulti(none));
        assertEquals("Condition", ConditionMatrixCopy.axesLabel(none));
        assertEquals("Condition", ConditionMatrixCopy.matrixGroupingLabel(none));
    }

    @Test
    public void singleAxis_hasNoMatrixWording() {
        ConditionAssignments ca = ConditionAssignments.ofLegacy(null);
        ca.put("M1", "condition", "hAPP");
        assertFalse(ConditionMatrixCopy.isMulti(ca.axes()));
        assertEquals("Condition", ConditionMatrixCopy.matrixGroupingLabel(ca.axes()));
        String status = ConditionMatrixCopy.statusLine(ca);
        assertTrue(status.startsWith("Condition:"));
        assertFalse(status.contains(X));
        assertFalse(status.toLowerCase(java.util.Locale.ROOT).contains("matrix"));
        assertFalse(status.toLowerCase(java.util.Locale.ROOT).contains("axes"));
    }

    @Test
    public void twoAxes_labels() {
        List<ConditionAxis> axes = twoAxisFixture().axes();
        assertTrue(ConditionMatrixCopy.isMulti(axes));
        assertEquals("Genotype " + X + " Timepoint", ConditionMatrixCopy.axesLabel(axes));
        assertEquals("Full condition matrix (Genotype " + X + " Timepoint)",
                ConditionMatrixCopy.matrixGroupingLabel(axes));
        assertEquals("Genotype only", ConditionMatrixCopy.axisOnlyLabel(axes.get(0)));
        assertEquals("Timepoint only", ConditionMatrixCopy.axisOnlyLabel(axes.get(1)));
    }

    @Test
    public void twoAxes_statusLine_usesCapacityAndAnimalCount() {
        String status = ConditionMatrixCopy.statusLine(twoAxisFixture());
        assertTrue(status, status.contains("Condition axes: Genotype " + X + " Timepoint"));
        assertTrue(status, status.contains("3 " + X + " 3 = 9 groups"));
        assertTrue(status, status.contains("12 animals"));
    }

    @Test
    public void populatedGroupCount_countsOccupiedCellsOnly() {
        assertEquals(8, ConditionMatrixCopy.populatedGroupCount(twoAxisFixture()));
    }

    @Test
    public void axisLabel_fallsBackToIdThenCondition() {
        assertEquals("Condition", ConditionMatrixCopy.axisLabel(null));
        ConditionAxis blankLabel = new ConditionAxis("genotype", "", 0);
        assertEquals("genotype", ConditionMatrixCopy.axisLabel(blankLabel));
    }
}
