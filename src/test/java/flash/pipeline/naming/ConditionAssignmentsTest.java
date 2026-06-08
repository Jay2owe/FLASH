package flash.pipeline.naming;

import org.junit.Test;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Unit tests for {@link ConditionAssignments}. */
public class ConditionAssignmentsTest {

    @Test
    public void putGet_roundTrips() {
        ConditionAssignments ca = new ConditionAssignments();
        ca.addAxis(ConditionAxis.of("Genotype"));
        ca.put("M14", "genotype", "hAPP");
        assertEquals("hAPP", ca.get("M14", "genotype"));
        assertEquals("", ca.get("M14", "timepoint"));
        assertEquals("", ca.get("unknown", "genotype"));
    }

    @Test
    public void twoAxes_areIndependent() {
        ConditionAssignments ca = new ConditionAssignments();
        ca.addAxis(ConditionAxis.of("Genotype"));
        ca.addAxis(ConditionAxis.of("Timepoint"));
        ca.put("M14", "genotype", "hAPP");
        ca.put("M14", "timepoint", "WeekFour");
        assertEquals("hAPP", ca.get("M14", "genotype"));
        assertEquals("WeekFour", ca.get("M14", "timepoint"));
    }

    @Test
    public void composite_joinsInAxisOrderSkippingBlanks() {
        ConditionAssignments ca = new ConditionAssignments();
        ca.addAxis(ConditionAxis.of("Genotype"));
        ca.addAxis(ConditionAxis.of("Timepoint"));
        ca.put("M14", "genotype", "hAPP");
        ca.put("M14", "timepoint", "WeekFour");
        assertEquals("hAPP_WeekFour", ca.composite("M14", "_"));

        ca.put("M15", "timepoint", "WeekTwo"); // genotype left blank
        assertEquals("WeekTwo", ca.composite("M15", "_"));
    }

    @Test
    public void distinctValues_orderedAndDeduped() {
        ConditionAssignments ca = new ConditionAssignments();
        ca.addAxis(ConditionAxis.of("Genotype"));
        ca.put("M1", "genotype", "Syn");
        ca.put("M2", "genotype", "hAPP");
        ca.put("M3", "genotype", "Syn");  // duplicate
        ca.put("M4", "genotype", "");     // blank ignored
        Set<String> vals = ca.distinctValues("genotype");
        assertEquals(2, vals.size());
        Iterator<String> it = vals.iterator();
        assertEquals("Syn", it.next());
        assertEquals("hAPP", it.next());
    }

    @Test
    public void ofLegacy_buildsSingleConditionAxis() {
        Map<String, String> legacy = new LinkedHashMap<String, String>();
        legacy.put("M14", "WT");
        legacy.put("M15", "KO");
        ConditionAssignments ca = ConditionAssignments.ofLegacy(legacy);
        assertEquals(1, ca.axes().size());
        assertEquals("condition", ca.axes().get(0).id);
        assertEquals("WT", ca.get("M14", "condition"));
        assertEquals("KO", ca.get("M15", "condition"));
        assertEquals("WT", ca.composite("M14", "_"));
    }

    @Test
    public void accessorsAreDefensiveCopies() {
        ConditionAssignments ca = new ConditionAssignments();
        ca.addAxis(ConditionAxis.of("Genotype"));
        ca.put("M14", "genotype", "hAPP");

        ca.axes().clear();                          // must not wipe internal axes
        ca.animals().clear();                       // must not wipe internal animals
        ca.valuesFor("M14").put("genotype", "X");   // must not mutate state

        assertEquals(1, ca.axes().size());
        assertEquals(1, ca.animals().size());
        assertEquals("hAPP", ca.get("M14", "genotype"));
    }

    @Test
    public void size_countsAnimalsWithAssignments() {
        ConditionAssignments ca = new ConditionAssignments();
        ca.addAxis(ConditionAxis.of("Genotype"));
        assertTrue(ca.isEmpty());
        ca.put("M14", "genotype", "hAPP");
        ca.put("M15", "genotype", "Syn");
        assertFalse(ca.isEmpty());
        assertEquals(2, ca.size());
    }
}
