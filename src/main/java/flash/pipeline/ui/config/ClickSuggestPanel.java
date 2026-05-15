package flash.pipeline.ui.config;

import flash.pipeline.help.AnalysisHelpCatalog;
import flash.pipeline.help.AnalysisHelpDialog;
import flash.pipeline.ui.FlashTheme;
import flash.pipeline.ui.HelpButton;
import flash.pipeline.ui.ToggleSwitch;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.HierarchyEvent;
import java.awt.event.HierarchyListener;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

final class ClickSuggestPanel extends JPanel {
    interface CountsProvider {
        Counts counts();
    }

    interface SuggestionProvider {
        Suggestion suggest();
    }

    interface ToggleListener {
        void selectedChanged(boolean selected);
    }

    static final class Counts {
        final int negative;
        final int positive;

        Counts(int negative, int positive) {
            this.negative = Math.max(0, negative);
            this.positive = Math.max(0, positive);
        }
    }

    abstract static class ValueBinding {
        final String label;
        final JComponent component;

        ValueBinding(String label, JComponent component) {
            this.label = label == null ? "" : label;
            this.component = component;
        }

        abstract String get();
        abstract void set(String value);

        static ValueBinding text(final String label, final JTextField field) {
            return new ValueBinding(label, field) {
                @Override String get() {
                    return field == null ? "" : field.getText();
                }

                @Override void set(String value) {
                    if (field != null) field.setText(value == null ? "" : value);
                }
            };
        }
    }

    static final class FieldSuggestion {
        final ValueBinding binding;
        final String value;

        FieldSuggestion(ValueBinding binding, String value) {
            this.binding = binding;
            this.value = value == null ? "" : value;
        }
    }

    static final class Suggestion {
        final List<FieldSuggestion> fields;
        final String message;
        final Runnable applyAction;
        final Runnable revertAction;

        Suggestion(List<FieldSuggestion> fields,
                   String message,
                   Runnable applyAction,
                   Runnable revertAction) {
            this.fields = fields == null
                    ? Collections.<FieldSuggestion>emptyList()
                    : Collections.unmodifiableList(new ArrayList<FieldSuggestion>(fields));
            this.message = message == null ? "" : message;
            this.applyAction = applyAction;
            this.revertAction = revertAction;
        }
    }

    private static final Color SUGGESTION_BACKGROUND = new Color(255, 246, 170);

    private final CountsProvider countsProvider;
    private final SuggestionProvider suggestionProvider;
    private final ToggleListener toggleListener;
    private final ToggleSwitch toggle;
    private final JLabel statusLabel;
    private final JButton suggestButton;
    private final JButton applyButton;
    private final JButton revertButton;
    private final javax.swing.Timer refreshTimer;
    private final Map<JComponent, Color> originalBackgrounds =
            new IdentityHashMap<JComponent, Color>();

    private Suggestion pendingSuggestion;
    private List<OriginalValue> originalValues = Collections.emptyList();

    ClickSuggestPanel(CountsProvider countsProvider,
                      SuggestionProvider suggestionProvider,
                      ToggleListener toggleListener) {
        this.countsProvider = countsProvider;
        this.suggestionProvider = suggestionProvider;
        this.toggleListener = toggleListener;

        setOpaque(false);
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setAlignmentX(Component.LEFT_ALIGNMENT);
        setBorder(FlashTheme.pad(4, 0, 4, 0));

        toggle = new ToggleSwitch(false);
        JLabel toggleLabel = new JLabel("Click bad objects to suggest filters");
        toggleLabel.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        toggleLabel.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mouseClicked(java.awt.event.MouseEvent e) {
                toggle.setSelected(!toggle.isSelected());
            }
        });
        toggle.addChangeListener(new Runnable() {
            @Override public void run() {
                if (!toggle.isSelected()) {
                    clearPending(false);
                }
                if (toggleListener != null) {
                    toggleListener.selectedChanged(toggle.isSelected());
                }
                refreshCounts();
                updateTimerState();
            }
        });

        JPanel top = new JPanel(new GridBagLayout());
        top.setOpaque(false);
        top.setAlignmentX(Component.LEFT_ALIGNMENT);
        GridBagConstraints gbc = rowConstraints();
        top.add(toggle, gbc);
        gbc.gridx++;
        top.add(toggleLabel, gbc);
        gbc.gridx++;
        JButton helpButton = HelpButton.question("About click-to-suggest filters.");
        helpButton.addActionListener(e -> AnalysisHelpDialog.show(
                ClickSuggestPanel.this, AnalysisHelpCatalog.CLICK_TO_SUGGEST_FILTERS));
        top.add(helpButton, gbc);
        gbc.gridx++;
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        top.add(Box.createHorizontalGlue(), gbc);
        add(top);

        JPanel row = new JPanel(new GridBagLayout());
        row.setOpaque(false);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        statusLabel = new JLabel(" ");
        statusLabel.setForeground(FlashTheme.TEXT_HELP);
        suggestButton = new JButton("Suggest");
        suggestButton.setEnabled(false);
        suggestButton.addActionListener(e -> runSuggestion());
        applyButton = new JButton("Apply");
        applyButton.setVisible(false);
        applyButton.addActionListener(e -> applyPending());
        revertButton = new JButton("Revert");
        revertButton.setVisible(false);
        revertButton.addActionListener(e -> revertPending());

        gbc = rowConstraints();
        row.add(statusLabel, gbc);
        gbc.gridx++;
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        row.add(Box.createHorizontalGlue(), gbc);
        gbc.gridx++;
        gbc.weightx = 0.0;
        gbc.fill = GridBagConstraints.NONE;
        row.add(suggestButton, gbc);
        gbc.gridx++;
        row.add(applyButton, gbc);
        gbc.gridx++;
        row.add(revertButton, gbc);
        add(row);

        refreshTimer = new javax.swing.Timer(1000, e -> refreshCounts());
        refreshTimer.setRepeats(true);
        addHierarchyListener(new HierarchyListener() {
            @Override public void hierarchyChanged(HierarchyEvent e) {
                if ((e.getChangeFlags() & HierarchyEvent.DISPLAYABILITY_CHANGED) != 0) {
                    updateTimerState();
                }
            }
        });
        refreshCounts();
    }

    boolean isSelected() {
        return toggle.isSelected();
    }

    void dispose() {
        refreshTimer.stop();
        clearPending(false);
    }

    private void runSuggestion() {
        refreshCounts();
        if (!toggle.isSelected()) return;
        clearPending(true);
        Counts counts = safeCounts();
        if (counts.negative < 3) {
            statusLabel.setText("clicked: " + counts.negative + " negative, "
                    + counts.positive + " positive. Need 3 negatives.");
            return;
        }
        Suggestion suggestion = suggestionProvider == null ? null : suggestionProvider.suggest();
        if (suggestion == null || suggestion.fields.isEmpty()) {
            statusLabel.setText("No clear suggestion - try clicking more bad objects.");
            return;
        }
        pendingSuggestion = suggestion;
        originalValues = captureOriginalValues(suggestion.fields);
        for (int i = 0; i < suggestion.fields.size(); i++) {
            FieldSuggestion field = suggestion.fields.get(i);
            if (field == null || field.binding == null) continue;
            field.binding.set(field.value);
            highlight(field.binding.component);
        }
        statusLabel.setText(suggestion.message);
        applyButton.setVisible(true);
        revertButton.setVisible(true);
        revalidate();
        repaint();
    }

    private void applyPending() {
        Suggestion suggestion = pendingSuggestion;
        if (suggestion != null && suggestion.applyAction != null) {
            suggestion.applyAction.run();
        }
        clearPending(false);
    }

    private void revertPending() {
        revertOriginalValues();
        Suggestion suggestion = pendingSuggestion;
        if (suggestion != null && suggestion.revertAction != null) {
            suggestion.revertAction.run();
        }
        clearPending(false);
        refreshCounts();
    }

    private void clearPending(boolean revert) {
        if (revert) {
            revertOriginalValues();
        }
        restoreBackgrounds();
        pendingSuggestion = null;
        originalValues = Collections.emptyList();
        applyButton.setVisible(false);
        revertButton.setVisible(false);
        revalidate();
        repaint();
    }

    private void revertOriginalValues() {
        for (int i = 0; i < originalValues.size(); i++) {
            OriginalValue original = originalValues.get(i);
            if (original != null && original.binding != null) {
                original.binding.set(original.value);
            }
        }
    }

    private List<OriginalValue> captureOriginalValues(List<FieldSuggestion> fields) {
        List<OriginalValue> out = new ArrayList<OriginalValue>();
        for (int i = 0; i < fields.size(); i++) {
            FieldSuggestion field = fields.get(i);
            if (field == null || field.binding == null) continue;
            out.add(new OriginalValue(field.binding, field.binding.get()));
        }
        return out;
    }

    private void highlight(JComponent component) {
        if (component == null) return;
        if (!originalBackgrounds.containsKey(component)) {
            originalBackgrounds.put(component, component.getBackground());
        }
        component.setOpaque(true);
        component.setBackground(SUGGESTION_BACKGROUND);
    }

    private void restoreBackgrounds() {
        for (Map.Entry<JComponent, Color> entry : originalBackgrounds.entrySet()) {
            JComponent component = entry.getKey();
            if (component != null) {
                component.setBackground(entry.getValue());
            }
        }
        originalBackgrounds.clear();
    }

    private void refreshCounts() {
        Counts counts = safeCounts();
        boolean active = toggle.isSelected();
        suggestButton.setEnabled(active && counts.negative >= 3);
        if (active) {
            String suffix = counts.negative < 3 ? ". Need 3 negatives." : ".";
            statusLabel.setText("clicked: " + counts.negative + " negative, "
                    + counts.positive + " positive" + suffix);
        } else {
            statusLabel.setText(" ");
        }
    }

    private Counts safeCounts() {
        Counts counts = countsProvider == null ? null : countsProvider.counts();
        return counts == null ? new Counts(0, 0) : counts;
    }

    private void updateTimerState() {
        if (toggle.isSelected() && isDisplayable()) {
            if (!refreshTimer.isRunning()) refreshTimer.start();
        } else {
            refreshTimer.stop();
        }
    }

    private static GridBagConstraints rowConstraints() {
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridy = 0;
        gbc.gridx = 0;
        gbc.insets = new Insets(0, 0, 0, 6);
        gbc.anchor = GridBagConstraints.WEST;
        gbc.fill = GridBagConstraints.NONE;
        gbc.weightx = 0.0;
        return gbc;
    }

    private static final class OriginalValue {
        final ValueBinding binding;
        final String value;

        OriginalValue(ValueBinding binding, String value) {
            this.binding = binding;
            this.value = value == null ? "" : value;
        }
    }
}
