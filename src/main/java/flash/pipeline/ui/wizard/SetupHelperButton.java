package flash.pipeline.ui.wizard;

import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;

/**
 * Factory for consistently placed setup-helper controls.
 */
public final class SetupHelperButton {

    private static final int MAX_PRESET_COMBO_WIDTH = 260;

    public interface WizardLauncher {
        void run();
    }

    private SetupHelperButton() {
    }

    public static JButton create(String wizardName, final WizardLauncher launcher) {
        JButton button = new JButton("[" + safeWizardName(wizardName) + " Helper]");
        button.setFocusPainted(false);
        button.setForeground(WizardTheme.BUTTON_FOREGROUND);
        button.setBackground(WizardTheme.BUTTON_BACKGROUND);
        button.setBorder(WizardTheme.BUTTON_BORDER);
        button.setMargin(WizardTheme.BUTTON_INSETS);
        if (launcher != null) {
            button.addActionListener(e -> launcher.run());
        }
        return button;
    }

    public static JPanel createHeaderRow(String wizardName,
                                         JComboBox<String> presetDropdown,
                                         JButton saveAsPresetButton,
                                         WizardLauncher launcher) {
        JPanel row = new JPanel(new BorderLayout(8, 0));
        row.setOpaque(false);

        JPanel presetPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        presetPanel.setOpaque(false);
        if (presetDropdown != null) {
            constrainPresetDropdown(presetDropdown);
            presetPanel.add(presetDropdown);
        }
        if (saveAsPresetButton != null) {
            presetPanel.add(saveAsPresetButton);
        }

        JPanel helperPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        helperPanel.setOpaque(false);
        helperPanel.add(create(wizardName, launcher));

        row.add(presetPanel, BorderLayout.CENTER);
        row.add(helperPanel, BorderLayout.EAST);
        return row;
    }

    private static String safeWizardName(String wizardName) {
        String value = wizardName == null ? "" : wizardName.trim();
        return value.length() == 0 ? "Setup" : value;
    }

    private static void constrainPresetDropdown(JComboBox<String> presetDropdown) {
        Dimension pref = presetDropdown.getPreferredSize();
        int height = pref == null ? 24 : Math.max(24, pref.height);
        int width = pref == null ? MAX_PRESET_COMBO_WIDTH
                : Math.min(pref.width, MAX_PRESET_COMBO_WIDTH);
        Dimension bounded = new Dimension(width, height);
        presetDropdown.setPreferredSize(bounded);
        presetDropdown.setMaximumSize(new Dimension(MAX_PRESET_COMBO_WIDTH, height));
    }
}
