package flash.pipeline.ui.wizard;

import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.FlowLayout;
import java.awt.event.ItemEvent;
import java.awt.event.ItemListener;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Renders source tags next to fields and clears them on user edits.
 */
public final class FieldTagRenderer {

    private final Map<String, Binding> bindings = new LinkedHashMap<String, Binding>();

    public void register(String componentId, JComponent component) {
        if (componentId == null || component == null) {
            throw new IllegalArgumentException("componentId and component are required.");
        }
        bindings.put(componentId, new Binding(componentId, component));
    }

    public void markRecommended(String componentId) {
        Binding binding = binding(componentId);
        binding.setTag("[Recommended]", false, null);
    }

    public void markAutoDetected(String componentId, Runnable onOverride) {
        Binding binding = binding(componentId);
        binding.component.setEnabled(false);
        binding.setTag("(auto-detected)", true, onOverride);
    }

    public void markFromPreset(String componentId, String presetName) {
        Binding binding = binding(componentId);
        String name = presetName == null ? "" : presetName.trim();
        binding.setTag("[From preset: " + name + "]", false, null);
    }

    public void clear(String componentId) {
        Binding binding = bindings.get(componentId);
        if (binding != null) {
            binding.clear();
        }
    }

    public String currentTag(String componentId) {
        Binding binding = bindings.get(componentId);
        return binding == null ? null : binding.tagText;
    }

    private Binding binding(String componentId) {
        Binding binding = bindings.get(componentId);
        if (binding == null) {
            throw new IllegalArgumentException("Unknown wizard field: " + componentId);
        }
        return binding;
    }

    private static final class Binding {
        final String id;
        final JComponent component;
        JLabel tagLabel;
        JButton overrideButton;
        String tagText;
        boolean listenerInstalled;
        boolean clearing;

        Binding(String id, JComponent component) {
            this.id = id;
            this.component = component;
        }

        void setTag(String text, boolean withOverride, final Runnable onOverride) {
            tagText = text;
            ensureContainer();
            tagLabel.setText(text);
            tagLabel.setVisible(true);
            if (withOverride) {
                if (overrideButton == null) {
                    overrideButton = new JButton("[Override]");
                    overrideButton.setFocusPainted(false);
                    overrideButton.setForeground(WizardTheme.ACTION_BLUE);
                    overrideButton.setBorder(WizardTheme.BUTTON_BORDER);
                    overrideButton.setMargin(WizardTheme.BUTTON_INSETS);
                    Container parent = tagLabel.getParent();
                    if (parent != null) {
                        parent.add(overrideButton);
                    }
                }
                for (java.awt.event.ActionListener listener : overrideButton.getActionListeners()) {
                    overrideButton.removeActionListener(listener);
                }
                overrideButton.addActionListener(e -> {
                    component.setEnabled(true);
                    clear();
                    if (onOverride != null) {
                        onOverride.run();
                    }
                });
                overrideButton.setVisible(true);
            } else if (overrideButton != null) {
                overrideButton.setVisible(false);
            }
            installEditListener();
            revalidate(component);
        }

        void clear() {
            clearing = true;
            tagText = null;
            if (tagLabel != null) {
                tagLabel.setText("");
                tagLabel.setVisible(false);
            }
            if (overrideButton != null) {
                overrideButton.setVisible(false);
            }
            clearing = false;
            revalidate(component);
        }

        private void ensureContainer() {
            if (tagLabel != null) return;
            tagLabel = new JLabel();
            tagLabel.setFont(WizardTheme.TAG_FONT);
            tagLabel.setForeground(WizardTheme.TAG_FOREGROUND);
            tagLabel.setOpaque(true);
            tagLabel.setBackground(WizardTheme.TAG_BACKGROUND);

            Container parent = component.getParent();
            if (parent instanceof JPanel) {
                ((JPanel) parent).add(tagLabel);
            } else {
                JPanel wrapper = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
                wrapper.setOpaque(false);
                wrapper.add(tagLabel);
            }
        }

        private void installEditListener() {
            if (listenerInstalled) return;
            listenerInstalled = true;
            if (component instanceof JTextField) {
                ((JTextField) component).getDocument().addDocumentListener(new DocumentListener() {
                    @Override public void insertUpdate(DocumentEvent e) { edited(); }
                    @Override public void removeUpdate(DocumentEvent e) { edited(); }
                    @Override public void changedUpdate(DocumentEvent e) { edited(); }
                });
            } else if (component instanceof JComboBox) {
                ((JComboBox<?>) component).addItemListener(new ItemListener() {
                    @Override public void itemStateChanged(ItemEvent e) {
                        if (e.getStateChange() == ItemEvent.SELECTED) edited();
                    }
                });
            } else {
                component.addPropertyChangeListener("value", evt -> edited());
            }
        }

        private void edited() {
            if (!clearing && tagText != null && component.isEnabled()) {
                clear();
            }
        }

        private static void revalidate(Component component) {
            if (component == null) return;
            SwingUtilities.invokeLater(new Runnable() {
                @Override public void run() {
                    component.revalidate();
                    component.repaint();
                }
            });
        }
    }
}
