package flash.pipeline.ui.wizard;

import org.junit.Test;

import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.Component;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class SetupHelperButtonTest {

    @Test
    public void createsConsistentStyledButtonAndLaunchesWizard() {
        final boolean[] launched = new boolean[1];
        JButton button = SetupHelperButton.create("Channel Setup", new SetupHelperButton.WizardLauncher() {
            @Override public void run() { launched[0] = true; }
        });

        assertEquals("[Channel Setup Helper]", button.getText());
        assertSame(WizardTheme.BUTTON_BORDER, button.getBorder());
        button.doClick();
        assertTrue(launched[0]);
    }

    @Test
    public void headerRowPlacesPresetControlsBesideRightAlignedHelper() {
        JComboBox<String> presets = new JComboBox<String>(new String[]{"Default"});
        JButton save = new JButton("Save as preset...");

        JPanel row = SetupHelperButton.createHeaderRow("3D Object Setup", presets, save, null);

        assertTrue(row.getLayout() instanceof BorderLayout);
        Component east = ((BorderLayout) row.getLayout()).getLayoutComponent(BorderLayout.EAST);
        Component center = ((BorderLayout) row.getLayout()).getLayoutComponent(BorderLayout.CENTER);
        assertTrue(east instanceof JPanel);
        assertTrue(center instanceof JPanel);
        assertEquals("[3D Object Setup Helper]", ((JButton) ((JPanel) east).getComponent(0)).getText());
        assertSame(presets, ((JPanel) center).getComponent(0));
        assertSame(save, ((JPanel) center).getComponent(1));
    }

    @Test
    public void helperRowBoundsLongPresetDropdownPreferredWidth() {
        String longName = "This is a very long preset name from a saved workflow "
                + "that should not make the setup helper row enormous";
        JComboBox<String> combo = new JComboBox<String>(
                new String[]{"(choose preset)", longName});
        JButton save = new JButton("Save as preset...");

        JPanel row = SetupHelperButton.createHeaderRow(
                "Spatial & Morphometry", combo, save, null);

        assertTrue("Preset combo should not keep the longest item's full width",
                combo.getPreferredSize().width <= 260);
        assertTrue("Helper row should stay compact enough for small screens",
                row.getPreferredSize().width < 700);
    }
}
