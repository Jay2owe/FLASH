package flash.pipeline.ui.wizard;

import flash.pipeline.io.ConditionManifestIO;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Verifies the shared condition health classification and the no-create
 * contract without launching any modal review UI.
 */
public class ConditionReviewSupportTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void noAnimalsIsBlocking() throws Exception {
        ConditionReviewSupport.Health h =
                ConditionReviewSupport.evaluate(dir(), animals());
        assertEquals(ConditionReviewSupport.Severity.BLOCKING, h.severity);
        assertTrue(h.isBlocking());
        assertEquals(0, h.animalCount);
    }

    @Test
    public void noManifestAutoDetectsButStillWarns() throws Exception {
        ConditionReviewSupport.Health h = ConditionReviewSupport.evaluate(
                dir(), animals("Control1", "Treated1"));
        assertEquals(0, h.explicitCount);
        assertEquals(2, h.autoDetectedCount);
        assertEquals(2, h.conditionCount);
        assertEquals(ConditionReviewSupport.Severity.WARNING, h.severity);
        assertTrue(h.needsReview());
        assertEquals("Control", h.resolvedAssignments.get("Control1"));
        assertEquals("Treated", h.resolvedAssignments.get("Treated1"));
    }

    @Test
    public void partialManifestMixesExplicitAndAutoDetected() throws Exception {
        String dir = dir();
        save(dir, "Control1", "Control");

        ConditionReviewSupport.Health h = ConditionReviewSupport.evaluate(
                dir, animals("Control1", "Treated1"));
        assertEquals(1, h.explicitCount);
        assertEquals(1, h.autoDetectedCount);
        assertEquals(2, h.conditionCount);
        assertEquals(ConditionReviewSupport.Severity.WARNING, h.severity);
        assertEquals("Control", h.resolvedAssignments.get("Control1"));
        assertEquals("Treated", h.resolvedAssignments.get("Treated1"));
    }

    @Test
    public void singleConditionWarnsAndBlocksWhenGroupingRequired() throws Exception {
        String dir = dir();
        save(dir, "A1", "Control", "A2", "Control");

        ConditionReviewSupport.Health warn =
                ConditionReviewSupport.evaluate(dir, animals("A1", "A2"));
        assertEquals(2, warn.explicitCount);
        assertEquals(0, warn.autoDetectedCount);
        assertEquals(1, warn.conditionCount);
        assertEquals(ConditionReviewSupport.Severity.WARNING, warn.severity);

        ConditionReviewSupport.Health block =
                ConditionReviewSupport.evaluate(dir, animals("A1", "A2"), true);
        assertEquals(ConditionReviewSupport.Severity.BLOCKING, block.severity);
    }

    @Test
    public void unresolvableAnimalCountsAsBlank() throws Exception {
        String dir = dir();
        save(dir, "Control1", "Control", "Treated1", "Treated");

        // A blank/whitespace name has no detectable condition: blank effective
        // assignment that escalates to BLOCKING when grouping is required.
        ConditionReviewSupport.Health warn = ConditionReviewSupport.evaluate(
                dir, animals("Control1", "Treated1", " "));
        assertEquals(1, warn.blankCount);
        assertTrue(warn.needsReview());

        ConditionReviewSupport.Health block = ConditionReviewSupport.evaluate(
                dir, animals("Control1", "Treated1", " "), true);
        assertEquals(ConditionReviewSupport.Severity.BLOCKING, block.severity);
    }

    @Test
    public void validMultiConditionIsOk() throws Exception {
        String dir = dir();
        save(dir, "A1", "Control", "A2", "Treated");

        ConditionReviewSupport.Health h =
                ConditionReviewSupport.evaluate(dir, animals("A1", "A2"));
        assertEquals(ConditionReviewSupport.Severity.OK, h.severity);
        assertFalse(h.needsReview());
        assertEquals(2, h.explicitCount);
        assertEquals(0, h.autoDetectedCount);
        assertEquals(0, h.blankCount);
        assertEquals(2, h.conditionCount);
    }

    @Test
    public void evaluateDoesNotCreateConditionsFile() throws Exception {
        String dir = dir();
        ConditionReviewSupport.evaluate(dir, animals("Control1", "Treated1"));
        assertNull("evaluate must not create Conditions.csv",
                ConditionManifestIO.getExistingFile(dir));
    }

    private String dir() throws Exception {
        return temp.newFolder("project").getAbsolutePath();
    }

    private static void save(String dir, String... animalCondition) throws Exception {
        LinkedHashMap<String, String> map = new LinkedHashMap<String, String>();
        for (int i = 0; i + 1 < animalCondition.length; i += 2) {
            map.put(animalCondition[i], animalCondition[i + 1]);
        }
        ConditionManifestIO.saveAssignments(dir, map);
    }

    private static Set<String> animals(String... names) {
        LinkedHashSet<String> out = new LinkedHashSet<String>();
        for (String n : names) out.add(n);
        return out;
    }
}
