package flash.pipeline.ui.wizard;

import org.junit.Test;

import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.JTextField;
import java.awt.Component;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class FieldTagRendererTest {

    @Test
    public void recommendedTagClearsOnFirstUserEdit() {
        FieldTagRenderer renderer = new FieldTagRenderer();
        JPanel row = new JPanel();
        JTextField field = new JTextField("initial", 12);
        row.add(field);
        renderer.register("marker", field);

        renderer.markRecommended("marker");
        assertEquals("[Recommended]", renderer.currentTag("marker"));

        field.setText("changed");

        assertNull(renderer.currentTag("marker"));
    }

    @Test
    public void autoDetectedOverrideEnablesFieldAndClearsTag() {
        FieldTagRenderer renderer = new FieldTagRenderer();
        JPanel row = new JPanel();
        JTextField field = new JTextField("DAPI", 12);
        row.add(field);
        renderer.register("marker", field);
        final boolean[] overrideCalled = new boolean[1];

        renderer.markAutoDetected("marker", new Runnable() {
            @Override public void run() { overrideCalled[0] = true; }
        });

        assertFalse(field.isEnabled());
        JButton override = findButton(row);
        assertTrue(override != null);
        override.doClick();

        assertTrue(field.isEnabled());
        assertTrue(overrideCalled[0]);
        assertNull(renderer.currentTag("marker"));
    }

    private static JButton findButton(JPanel panel) {
        for (Component component : panel.getComponents()) {
            if (component instanceof JButton) {
                return (JButton) component;
            }
        }
        return null;
    }
}
