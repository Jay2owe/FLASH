package flash.pipeline.ui.wizard;

import flash.pipeline.io.ConditionManifestIO;
import flash.pipeline.naming.ConditionAssignments;
import flash.pipeline.naming.ConditionAxis;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import javax.swing.table.DefaultTableModel;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Axis-aware review behaviour for {@link ConditionManifestPanel}: the matrix table,
 * the derived Combined group column, and the per-axis persistence that fixes the
 * dropped-edit bug on multi-axis projects.
 */
public class ConditionManifestPanelMultiAxisTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    private static ConditionAssignments twoAxis(String... animals) {
        ConditionAssignments ca = new ConditionAssignments();
        ca.addAxis(ConditionAxis.of("Genotype"));
        ca.addAxis(ConditionAxis.of("Timepoint"));
        for (String a : animals) {
            ca.put(a, "genotype", "hAPP");
            ca.put(a, "timepoint", "WeekFour");
        }
        return ca;
    }

    private static Set<String> set(String... values) {
        LinkedHashSet<String> s = new LinkedHashSet<String>();
        for (String v : values) s.add(v);
        return s;
    }

    @Test
    public void matrixTable_hasAxisColumnsAndDerivedCombined() {
        ConditionManifestPanel panel =
                ConditionManifestPanel.forMatrixModel(set("M1"), twoAxis("M1"));
        assertTrue(panel.isMultiAxis());
        DefaultTableModel m = panel.getModel();
        assertEquals(4, m.getColumnCount());           // Animal | Genotype | Timepoint | Combined group
        assertEquals("Animal", m.getColumnName(0));
        assertEquals("Genotype", m.getColumnName(1));
        assertEquals("Timepoint", m.getColumnName(2));
        assertEquals("Combined group", m.getColumnName(3));
        assertEquals("hAPP", m.getValueAt(0, 1));
        assertEquals("WeekFour", m.getValueAt(0, 2));
        assertEquals("hAPP_WeekFour", m.getValueAt(0, 3));
    }

    @Test
    public void editingAnAxisCell_updatesCombinedGroup() {
        ConditionManifestPanel panel =
                ConditionManifestPanel.forMatrixModel(set("M1"), twoAxis("M1"));
        DefaultTableModel m = panel.getModel();
        m.setValueAt("Syn", 0, 1);                      // Genotype -> Syn
        assertEquals("Syn_WeekFour", m.getValueAt(0, 3));
        m.setValueAt("WeekTwo", 0, 2);                  // Timepoint -> WeekTwo
        assertEquals("Syn_WeekTwo", m.getValueAt(0, 3));
    }

    @Test
    public void collectAssignmentsModel_returnsPerAxisValues() {
        ConditionManifestPanel panel =
                ConditionManifestPanel.forMatrixModel(set("M1"), twoAxis("M1"));
        panel.getModel().setValueAt("NLGF", 0, 1);
        ConditionAssignments out = panel.collectAssignmentsModel();
        assertEquals(2, out.axes().size());
        assertEquals("NLGF", out.get("M1", "genotype"));
        assertEquals("WeekFour", out.get("M1", "timepoint"));
    }

    @Test
    public void collectAssignmentsModel_preservesAnimalsNotInReviewSet() {
        // Model carries M1 and M2; only M1 is shown in the review set.
        ConditionAssignments model = twoAxis("M1", "M2");
        ConditionManifestPanel panel = ConditionManifestPanel.forMatrixModel(set("M1"), model);
        panel.getModel().setValueAt("Syn", 0, 1);       // edit M1's genotype
        ConditionAssignments out = panel.collectAssignmentsModel();
        assertEquals("Syn", out.get("M1", "genotype"));
        // M2 was not displayed but must survive the write-back.
        assertEquals("hAPP", out.get("M2", "genotype"));
        assertEquals("WeekFour", out.get("M2", "timepoint"));
    }

    @Test
    public void persist_writesPerAxisColumns_soEditsSurvive() throws Exception {
        // The dropped-edit regression: a matrix edit must reach Conditions.csv.
        String dir = temp.getRoot().getAbsolutePath();
        ConditionManifestIO.saveAssignments(dir, twoAxis("M1"));   // seed a matrix manifest

        ConditionManifestPanel panel =
                ConditionManifestPanel.forProject(dir, set("M1"), null, null, -1);
        assertTrue("expected matrix layout", panel.isMultiAxis());
        panel.getModel().setValueAt("Syn", 0, 1);                 // change Genotype
        assertTrue(ConditionManifestPanel.persist(dir, panel));

        ConditionAssignments back = ConditionManifestIO.readAssignmentsModel(dir);
        assertEquals(2, back.axes().size());
        assertEquals("Syn", back.get("M1", "genotype"));
        assertEquals("WeekFour", back.get("M1", "timepoint"));
    }

    @Test
    public void singleAxis_layoutAndPersistUnchanged() throws Exception {
        LinkedHashMap<String, String> prefill = new LinkedHashMap<String, String>();
        prefill.put("M1", "Control");
        ConditionManifestPanel panel = new ConditionManifestPanel(set("M1"), prefill);
        assertFalse(panel.isMultiAxis());
        DefaultTableModel m = panel.getModel();
        assertEquals(2, m.getColumnCount());
        assertEquals("Animal Name", m.getColumnName(0));
        assertEquals("Condition", m.getColumnName(1));

        String dir = temp.getRoot().getAbsolutePath();
        assertTrue(ConditionManifestPanel.persist(dir, panel));
        ConditionAssignments back = ConditionManifestIO.readAssignmentsModel(dir);
        assertEquals(1, back.axes().size());
        assertEquals("condition", back.axes().get(0).id);
        assertEquals("Control", back.get("M1", "condition"));
    }
}
