package flash.pipeline.ui.config;

import flash.pipeline.ui.PipelineDialog;

import javax.swing.JTextField;
import java.awt.GraphicsEnvironment;
import java.awt.Window;

/**
 * Small modal popover that asks the user for a preset name. Returns the
 * trimmed name on Save, or {@code null} if the user cancelled, closed the
 * dialog, or the runtime is headless.
 */
public final class SaveAsPresetPopover {

    private SaveAsPresetPopover() {}

    public static String prompt(Window owner, String suggestedName) {
        if (GraphicsEnvironment.isHeadless()) return null;

        PipelineDialog dialog = new PipelineDialog(owner, "Save as preset");
        dialog.addHeader("Save as preset");
        final JTextField field = new JTextField(
                suggestedName == null ? "" : suggestedName, 24);
        dialog.addComponent(field);
        dialog.setPrimaryButtonText("Save");
        dialog.requestFocusOnShow(field);

        if (!dialog.showDialog()) {
            return null;
        }
        String text = field.getText();
        String result = text == null ? "" : text.trim();
        return result.isEmpty() ? null : result;
    }
}
