package flash.pipeline.ui.config;

import flash.pipeline.ui.wizard.Preset;
import flash.pipeline.ui.wizard.PresetIO;

import javax.swing.JComboBox;
import javax.swing.JOptionPane;
import java.awt.Component;
import java.awt.GraphicsEnvironment;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Small reusable dialog for managing (currently: deleting) saved presets.
 * Shared by the Intensity, Spatial, and 3D Object setup helpers so the
 * "(choose preset)" rows behave the same way across analyses.
 */
public final class PresetManagerDialog {

    private PresetManagerDialog() {
    }

    /**
     * Shows a modal manager that lets the user delete saved presets one at a
     * time. Returns {@code true} if at least one preset was deleted, so the
     * caller can refresh its preset dropdown.
     */
    public static boolean manage(Component parent, PresetIO<?> io, String title) {
        if (io == null || GraphicsEnvironment.isHeadless()) {
            return false;
        }
        boolean changed = false;
        try {
            while (true) {
                List<String> names = collectNames(io);
                if (names.isEmpty()) {
                    if (!changed) {
                        JOptionPane.showMessageDialog(parent,
                                "There are no saved presets to manage.",
                                title, JOptionPane.INFORMATION_MESSAGE);
                    }
                    break;
                }
                JComboBox<String> combo = new JComboBox<String>(names.toArray(new String[names.size()]));
                Object[] message = {"Select a preset to delete:", combo};
                Object[] options = {"Delete", "Close"};
                int choice = JOptionPane.showOptionDialog(parent, message, title,
                        JOptionPane.DEFAULT_OPTION, JOptionPane.PLAIN_MESSAGE, null,
                        options, options[1]);
                if (choice != 0) {
                    break;
                }
                Object selected = combo.getSelectedItem();
                if (selected == null) {
                    continue;
                }
                String name = String.valueOf(selected);
                int confirm = JOptionPane.showConfirmDialog(parent,
                        "Delete preset \"" + name + "\"?\n"
                                + "Built-in presets may reappear the next time the project is opened.",
                        title, JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
                if (confirm == JOptionPane.YES_OPTION) {
                    io.delete(name);
                    changed = true;
                }
            }
        } catch (IOException e) {
            JOptionPane.showMessageDialog(parent,
                    "Could not manage presets: " + e.getMessage(),
                    title, JOptionPane.ERROR_MESSAGE);
        }
        return changed;
    }

    private static List<String> collectNames(PresetIO<?> io) throws IOException {
        List<String> names = new ArrayList<String>();
        for (Preset<?> preset : io.listAll()) {
            if (preset != null && preset.getName() != null) {
                names.add(preset.getName());
            }
        }
        return names;
    }
}
