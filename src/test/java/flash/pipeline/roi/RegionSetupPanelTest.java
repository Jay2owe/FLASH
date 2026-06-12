package flash.pipeline.roi;

import flash.pipeline.ui.wizard.RegionTableCellEditor;
import org.junit.Test;

import java.awt.Component;
import java.awt.Container;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.swing.JTable;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Tests for the multi-region setup table conversion and shared editor wiring.
 */
public class RegionSetupPanelTest {

    /** Parser mirroring DrawAndSaveROIsAnalysis.parseRoiChannelChoice: leading digits, default 1. */
    private static final RegionSetupPanel.ChannelParser LEADING_DIGITS = choice -> {
        if (choice == null) return 1;
        StringBuilder digits = new StringBuilder();
        for (int i = 0; i < choice.trim().length(); i++) {
            char c = choice.trim().charAt(i);
            if (!Character.isDigit(c)) break;
            digits.append(c);
        }
        if (digits.length() == 0) return 1;
        return Integer.parseInt(digits.toString());
    };

    @Test
    public void buildsOneSpecPerNamedRowWithParsedChannelAndSessionMode() {
        List<String[]> rows = Arrays.asList(
                new String[]{"SCN", "1 (DAPI)"},
                new String[]{"Cortex", "3 (GFP)"});

        List<RegionDrawSpec> specs = RegionSetupPanel.toSpecs(rows, LEADING_DIGITS, "Automatic");

        assertEquals(2, specs.size());
        assertEquals("SCN", specs.get(0).regionName);
        assertEquals(1, specs.get(0).drawChannel);
        assertEquals("Automatic", specs.get(0).displayMode);
        assertEquals("Cortex", specs.get(1).regionName);
        assertEquals(3, specs.get(1).drawChannel);
        assertEquals("Automatic", specs.get(1).displayMode);
    }

    @Test
    public void dropsBlankNameRows() {
        List<String[]> rows = Arrays.asList(
                new String[]{"SCN", "1"},
                new String[]{"   ", "2"},
                new String[]{"", "2"});

        List<RegionDrawSpec> specs = RegionSetupPanel.toSpecs(rows, LEADING_DIGITS, "None");

        assertEquals(1, specs.size());
        assertEquals("SCN", specs.get(0).regionName);
    }

    @Test
    public void dedupesRegionNamesCaseInsensitivelyKeepingFirst() {
        List<String[]> rows = Arrays.asList(
                new String[]{"SCN", "1"},
                new String[]{"scn", "4"},   // duplicate (different case + channel) -> dropped
                new String[]{"Cortex", "2"});

        List<RegionDrawSpec> specs = RegionSetupPanel.toSpecs(rows, LEADING_DIGITS, "None");

        assertEquals(2, specs.size());
        assertEquals("SCN", specs.get(0).regionName);
        assertEquals(1, specs.get(0).drawChannel); // first occurrence's channel wins
        assertEquals("Cortex", specs.get(1).regionName);
    }

    @Test
    public void emptyOrNullInputYieldsEmpty() {
        assertTrue(RegionSetupPanel.toSpecs(null, LEADING_DIGITS, "None").isEmpty());
        assertTrue(RegionSetupPanel.toSpecs(new ArrayList<String[]>(), LEADING_DIGITS, "None").isEmpty());
    }

    @Test
    public void nullParserDefaultsChannelToOne() {
        List<String[]> rows = new ArrayList<String[]>();
        rows.add(new String[]{"SCN", "3 (GFP)"});
        List<RegionDrawSpec> specs = RegionSetupPanel.toSpecs(rows, null, "Manual");
        assertEquals(1, specs.size());
        assertEquals(1, specs.get(0).drawChannel);
        assertEquals("Manual", specs.get(0).displayMode);
    }

    @Test
    public void trimsRegionNames() {
        List<String[]> rows = new ArrayList<String[]>();
        rows.add(new String[]{"  Hippocampus  ", "2"});
        List<RegionDrawSpec> specs = RegionSetupPanel.toSpecs(rows, LEADING_DIGITS, "None");
        assertEquals(1, specs.size());
        assertEquals("Hippocampus", specs.get(0).regionName);
    }

    @Test
    public void regionColumnUsesAtlasAutocompleteEditor() {
        RegionSetupPanel panel = new RegionSetupPanel(
                new String[]{"1 (DAPI)", "2 (GFP)"}, "1 (DAPI)", "SCN");

        JTable table = findTable(panel);

        assertNotNull(table);
        assertTrue(table.getColumnModel().getColumn(0).getCellEditor()
                instanceof RegionTableCellEditor);
    }

    private static JTable findTable(Component component) {
        if (component instanceof JTable) {
            return (JTable) component;
        }
        if (component instanceof Container) {
            Component[] children = ((Container) component).getComponents();
            for (Component child : children) {
                JTable found = findTable(child);
                if (found != null) return found;
            }
        }
        return null;
    }
}
