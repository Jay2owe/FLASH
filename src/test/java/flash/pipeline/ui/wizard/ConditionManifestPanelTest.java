package flash.pipeline.ui.wizard;

import org.junit.Test;

import javax.swing.table.DefaultTableModel;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class ConditionManifestPanelTest {

    @Test
    public void panelPopulatesAnimalsFromPrefill() {
        Set<String> animals = ordered("Syn1", "Syn2", "hAPP1");
        Map<String, String> prefill = new LinkedHashMap<String, String>();
        prefill.put("Syn1", "Syn");
        prefill.put("hAPP1", "hAPP");

        ConditionManifestPanel panel = new ConditionManifestPanel(animals, prefill);
        DefaultTableModel model = panel.getModel();

        assertEquals(3, model.getRowCount());
        assertEquals("Syn1", model.getValueAt(0, 0));
        assertEquals("Syn", model.getValueAt(0, 1));
        assertEquals("Syn2", model.getValueAt(1, 0));
        // Syn2 not in prefill -> empty
        assertEquals("", model.getValueAt(1, 1));
        assertEquals("hAPP1", model.getValueAt(2, 0));
        assertEquals("hAPP", model.getValueAt(2, 1));
    }

    @Test
    public void collectAssignmentsReflectsEdits() {
        Set<String> animals = ordered("A1", "A2");
        ConditionManifestPanel panel = new ConditionManifestPanel(animals, null);
        panel.getModel().setValueAt("Control", 0, 1);
        panel.getModel().setValueAt("Treated", 1, 1);
        LinkedHashMap<String, String> result = panel.collectAssignments();
        assertEquals(2, result.size());
        assertEquals("Control", result.get("A1"));
        assertEquals("Treated", result.get("A2"));
    }

    @Test
    public void collectAssignmentsTrimsWhitespace() {
        Set<String> animals = ordered("A1");
        ConditionManifestPanel panel = new ConditionManifestPanel(animals, null);
        panel.getModel().setValueAt("   Control   ", 0, 1);
        assertEquals("Control", panel.collectAssignments().get("A1"));
    }

    @Test
    public void blankPrefillProducesEmptyConditionRatherThanNull() {
        Set<String> animals = ordered("A1");
        ConditionManifestPanel panel = new ConditionManifestPanel(animals, null);
        Object value = panel.getModel().getValueAt(0, 1);
        assertNotNull(value);
        assertEquals("", value);
    }

    @Test
    public void componentIsNotNull() {
        Set<String> animals = ordered("A1");
        ConditionManifestPanel panel = new ConditionManifestPanel(animals, null);
        assertNotNull(panel.getComponent());
        assertTrue(panel.getTable().getRowCount() == 1);
    }

    private static Set<String> ordered(String... names) {
        Set<String> out = new LinkedHashSet<String>();
        for (String n : names) out.add(n);
        return out;
    }
}
