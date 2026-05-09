package flash.pipeline.ui.config;

import flash.pipeline.image.FilterMacroEditorModel;
import flash.pipeline.ui.preview.PreviewPairPanel;
import ij.ImagePlus;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingWorker;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class FilterParameterStage implements ConfigQcStage {

    public interface MacroStore {
        String getInitialPreset();
        String loadInitialMacro() throws Exception;
        String loadPresetMacro(String presetName) throws Exception;
        void save(String presetName, String macroContent) throws Exception;
    }

    public interface PreviewAdapter {
        ImagePlus createSource(ConfigQcContext context) throws Exception;
        ImagePlus createFilteredPreview(ImagePlus source, String macroContent) throws Exception;
        void close(ImagePlus image);
    }

    public interface CustomFilterBuilder {
        CustomFilterResult open(ConfigQcContext context, String currentPreset,
                                String currentMacro) throws Exception;
    }

    public interface PresetDescriptionProvider {
        String describe(String presetName);
    }

    public static final class CustomFilterResult {
        public final boolean applied;
        public final String presetName;
        public final String macroContent;

        public CustomFilterResult(boolean applied, String presetName, String macroContent) {
            this.applied = applied;
            this.presetName = presetName;
            this.macroContent = macroContent;
        }
    }

    private static final String CUSTOM_PRESET = "Custom";
    private static final String DEFAULT_PRESET = "Default";
    private static final String STALE_TEXT = "Preview is stale. Press Preview Filter.";
    private static final String EMPTY_TEXT = "Choose a filter preset or open the custom filter builder.";

    private final List<String> presetOptions;
    private final MacroStore macroStore;
    private final PreviewAdapter previewAdapter;
    private final CustomFilterBuilder customFilterBuilder;
    private final PresetDescriptionProvider descriptionProvider;

    private final List<FilterFieldBinding> fieldBindings = new ArrayList<FilterFieldBinding>();

    private ConfigQcActions actions;
    private PreviewPairPanel preview;
    private ConfigQcContext activeContext;
    private ImagePlus sourceImage;
    private ImagePlus adjustedPreview;
    private SwingWorker<ImagePlus, Void> previewWorker;

    private JPanel parameterPanel;
    private JLabel presetDescriptionLabel;
    private JLabel feedbackLabel;
    private JComboBox<String> presetCombo;
    private JButton previewButton;
    private JButton customBuilderButton;
    private JButton resetButton;

    private String savedPreset;
    private String savedMacro;
    private String selectedPreset;
    private String currentMacro;
    private FilterMacroEditorModel.MacroDefinition definition;
    private boolean previewStale;
    private boolean updatingControls;

    public FilterParameterStage(List<String> presetOptions,
                                MacroStore macroStore,
                                PreviewAdapter previewAdapter,
                                CustomFilterBuilder customFilterBuilder,
                                PresetDescriptionProvider descriptionProvider) {
        if (macroStore == null) {
            throw new IllegalArgumentException("macroStore must not be null");
        }
        if (previewAdapter == null) {
            throw new IllegalArgumentException("previewAdapter must not be null");
        }
        this.presetOptions = Collections.unmodifiableList(copyOptions(presetOptions));
        this.macroStore = macroStore;
        this.previewAdapter = previewAdapter;
        this.customFilterBuilder = customFilterBuilder;
        this.descriptionProvider = descriptionProvider == null
                ? new PresetDescriptionProvider() {
                    @Override public String describe(String presetName) {
                        return "";
                    }
                }
                : descriptionProvider;
    }

    @Override
    public String title() {
        return "Set Filter and Parameters";
    }

    @Override
    public JComponent buildControls(ConfigQcContext context, ConfigQcActions actions) {
        this.actions = actions;
        this.activeContext = context;
        loadSavedState();

        JPanel panel = new JPanel(new BorderLayout(0, 10));
        panel.setOpaque(false);
        panel.add(buildPresetPanel(), BorderLayout.NORTH);
        panel.add(buildParameterPanel(), BorderLayout.CENTER);
        panel.add(buildActionPanel(), BorderLayout.SOUTH);
        rebuildParameterEditor();
        markPreviewStale(STALE_TEXT);
        return panel;
    }

    @Override
    public void onEnter(ConfigQcContext context, PreviewPairPanel preview) {
        closePreviewWorker();
        closeImages();
        this.activeContext = context;
        this.preview = preview;
        try {
            sourceImage = previewAdapter.createSource(context);
            if (preview != null) {
                preview.setOriginal(sourceImage);
                preview.setAdjusted(null);
                preview.setAdjustedState(hasMacro()
                        ? PreviewPairPanel.PreviewState.STALE
                        : PreviewPairPanel.PreviewState.EMPTY,
                        hasMacro() ? STALE_TEXT : EMPTY_TEXT);
            }
            setStatus(hasMacro() ? STALE_TEXT : EMPTY_TEXT);
            refreshActionState();
        } catch (Exception e) {
            setError("Could not prepare filter preview source: " + e.getMessage());
            refreshActionState();
        }
    }

    @Override
    public boolean lockIn(ConfigQcContext context) {
        if (!hasMacro()) {
            setError("No filter macro is available to lock in.");
            return false;
        }
        syncFieldBindings();
        try {
            macroStore.save(selectedPreset, currentMacro);
            savedPreset = selectedPreset;
            savedMacro = currentMacro;
            previewStale = false;
            setStatus(lockInSummary());
            return true;
        } catch (Exception e) {
            setError("Could not save filter macro: " + e.getMessage());
            return false;
        }
    }

    @Override
    public void skipCurrentImage(ConfigQcContext context) {
        setStatus("Skipped this image.");
    }

    @Override
    public void restartStage(ConfigQcContext context) {
        setStatus("Restarting filter review from the first image.");
    }

    @Override
    public void onLeave(ConfigQcContext context) {
        closePreviewWorker();
        closeImages();
        preview = null;
        activeContext = null;
    }

    boolean isPreviewStaleForTest() {
        return previewStale;
    }

    String selectedPresetForTest() {
        return selectedPreset;
    }

    String currentMacroForTest() {
        return currentMacro;
    }

    void setParameterForTest(String key, String value) {
        for (int i = 0; i < fieldBindings.size(); i++) {
            FilterFieldBinding binding = fieldBindings.get(i);
            if (binding.parameter.key.equals(key)) {
                binding.field.setText(value);
                return;
            }
        }
        throw new IllegalArgumentException("No parameter field named " + key);
    }

    void setPresetForTest(String presetName) {
        if (presetCombo == null) return;
        addPresetOption(presetName);
        presetCombo.setSelectedItem(presetName);
    }

    void runPreviewNowForTest() throws Exception {
        runPreviewNow();
    }

    boolean previewButtonEnabledForTest() {
        return previewButton != null && previewButton.isEnabled();
    }

    private JComponent buildPresetPanel() {
        JPanel panel = new JPanel();
        panel.setOpaque(false);
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));

        JPanel row = new JPanel(new GridBagLayout());
        row.setOpaque(false);
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridy = 0;
        gbc.insets = new Insets(0, 0, 4, 6);
        gbc.anchor = GridBagConstraints.WEST;

        gbc.gridx = 0;
        gbc.weightx = 0.0;
        gbc.fill = GridBagConstraints.NONE;
        row.add(new JLabel("Filter"), gbc);

        presetCombo = new JComboBox<String>();
        updatingControls = true;
        try {
            for (int i = 0; i < presetOptions.size(); i++) {
                presetCombo.addItem(presetOptions.get(i));
            }
            addPresetOption(selectedPreset);
            presetCombo.setSelectedItem(selectedPreset);
        } finally {
            updatingControls = false;
        }
        presetCombo.addActionListener(e -> presetChanged());
        gbc.gridx = 1;
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        row.add(presetCombo, gbc);

        customBuilderButton = new JButton("Open Custom Filter Builder");
        customBuilderButton.addActionListener(e -> openCustomFilterBuilder());
        gbc.gridx = 2;
        gbc.weightx = 0.0;
        gbc.fill = GridBagConstraints.NONE;
        row.add(customBuilderButton, gbc);

        panel.add(row);
        presetDescriptionLabel = new JLabel(descriptionProvider.describe(selectedPreset));
        presetDescriptionLabel.setForeground(new Color(90, 90, 90));
        presetDescriptionLabel.setAlignmentX(JComponent.LEFT_ALIGNMENT);
        panel.add(presetDescriptionLabel);
        return panel;
    }

    private JComponent buildParameterPanel() {
        parameterPanel = new JPanel();
        parameterPanel.setOpaque(false);
        parameterPanel.setLayout(new BoxLayout(parameterPanel, BoxLayout.Y_AXIS));
        parameterPanel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createTitledBorder("Editable parameters"),
                BorderFactory.createEmptyBorder(6, 6, 6, 6)));
        return parameterPanel;
    }

    private JComponent buildActionPanel() {
        JPanel panel = new JPanel(new BorderLayout(0, 8));
        panel.setOpaque(false);

        JPanel buttons = new JPanel();
        buttons.setOpaque(false);
        buttons.setLayout(new BoxLayout(buttons, BoxLayout.X_AXIS));

        previewButton = new JButton("Preview Filter");
        previewButton.addActionListener(e -> runPreviewOnWorker());
        buttons.add(previewButton);
        buttons.add(Box.createHorizontalStrut(6));

        resetButton = new JButton("Reset to saved");
        resetButton.addActionListener(e -> resetToSaved());
        buttons.add(resetButton);
        buttons.add(Box.createHorizontalGlue());

        feedbackLabel = new JLabel(" ");
        feedbackLabel.setForeground(new Color(90, 90, 90));

        panel.add(buttons, BorderLayout.NORTH);
        panel.add(feedbackLabel, BorderLayout.CENTER);
        refreshActionState();
        return panel;
    }

    private void loadSavedState() {
        String initialPreset = trimToNull(macroStore.getInitialPreset());
        savedPreset = initialPreset == null ? preferredPresetWithoutSavedState() : initialPreset;
        selectedPreset = savedPreset;
        try {
            savedMacro = initialPreset == null
                    ? safe(macroStore.loadPresetMacro(selectedPreset))
                    : safe(macroStore.loadInitialMacro());
            if (!hasMacro(savedMacro)) {
                savedMacro = safe(macroStore.loadPresetMacro(selectedPreset));
            }
            currentMacro = savedMacro;
        } catch (Exception e) {
            savedMacro = "";
            currentMacro = "";
            setError("Could not load filter macro: " + e.getMessage());
        }
    }

    private void presetChanged() {
        if (updatingControls || presetCombo == null) return;
        Object selected = presetCombo.getSelectedItem();
        if (selected == null) return;
        selectedPreset = selected.toString();
        try {
            currentMacro = safe(macroStore.loadPresetMacro(selectedPreset));
            rebuildParameterEditor();
            if (presetDescriptionLabel != null) {
                presetDescriptionLabel.setText(descriptionProvider.describe(selectedPreset));
            }
            clearAdjustedPreview();
            markPreviewStale(hasMacro() ? STALE_TEXT : EMPTY_TEXT);
        } catch (Exception e) {
            currentMacro = "";
            rebuildParameterEditor();
            setError("Could not load preset '" + selectedPreset + "': " + e.getMessage());
        }
        refreshActionState();
    }

    private void rebuildParameterEditor() {
        if (parameterPanel == null) return;
        updatingControls = true;
        try {
            fieldBindings.clear();
            parameterPanel.removeAll();
            definition = FilterMacroEditorModel.parse(currentMacro);
            if (!hasMacro()) {
                addParameterMessage("No filter macro is available for this preset.");
            } else if (definition == null || !definition.hasEditableParameters()) {
                addParameterMessage("No editable key=value parameters were detected in this macro.");
            } else {
                List<FilterMacroEditorModel.Section> sections = definition.getSections();
                for (int i = 0; i < sections.size(); i++) {
                    addSection(sections.get(i));
                }
            }
        } finally {
            updatingControls = false;
        }
        parameterPanel.revalidate();
        parameterPanel.repaint();
        refreshActionState();
    }

    private void addSection(FilterMacroEditorModel.Section section) {
        JLabel header = new JLabel(section.headerText());
        header.setAlignmentX(JComponent.LEFT_ALIGNMENT);
        header.setFont(header.getFont().deriveFont(java.awt.Font.BOLD));
        parameterPanel.add(header);
        parameterPanel.add(Box.createVerticalStrut(4));

        List<FilterMacroEditorModel.Entry> entries = section.entries;
        for (int i = 0; i < entries.size(); i++) {
            parameterPanel.add(buildEntryRow(entries.get(i)));
            parameterPanel.add(Box.createVerticalStrut(3));
        }
        parameterPanel.add(Box.createVerticalStrut(8));
    }

    private JComponent buildEntryRow(FilterMacroEditorModel.Entry entry) {
        JPanel row = new JPanel(new GridBagLayout());
        row.setOpaque(false);
        row.setAlignmentX(JComponent.LEFT_ALIGNMENT);

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridy = 0;
        gbc.insets = new Insets(2, 0, 2, 6);
        gbc.anchor = GridBagConstraints.WEST;

        gbc.gridx = 0;
        gbc.weightx = 0.35;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        row.add(new JLabel(entry.label), gbc);

        List<FilterMacroEditorModel.Parameter> parameters = entry.parameters;
        for (int i = 0; i < parameters.size(); i++) {
            FilterMacroEditorModel.Parameter parameter = parameters.get(i);
            gbc.gridx++;
            gbc.weightx = 0.0;
            gbc.fill = GridBagConstraints.NONE;
            row.add(new JLabel(parameter.key), gbc);

            JTextField field = new JTextField(parameter.getValue(), Math.max(5,
                    Math.min(12, parameter.getValue().length() + 2)));
            installFieldListener(field);
            fieldBindings.add(new FilterFieldBinding(parameter, field));

            gbc.gridx++;
            gbc.weightx = 0.2;
            gbc.fill = GridBagConstraints.HORIZONTAL;
            row.add(field, gbc);
        }
        return row;
    }

    private void installFieldListener(JTextField field) {
        field.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) {
                fieldChanged();
            }

            @Override public void removeUpdate(DocumentEvent e) {
                fieldChanged();
            }

            @Override public void changedUpdate(DocumentEvent e) {
                fieldChanged();
            }
        });
    }

    private void addParameterMessage(String text) {
        JLabel label = new JLabel(text == null ? "" : text);
        label.setForeground(new Color(90, 90, 90));
        label.setAlignmentX(JComponent.LEFT_ALIGNMENT);
        parameterPanel.add(label);
    }

    private void fieldChanged() {
        if (updatingControls) return;
        markPreviewStale(STALE_TEXT);
    }

    private void runPreviewOnWorker() {
        if (previewWorker != null && !previewWorker.isDone()) return;
        if (!canPreview()) {
            setError("No filter macro or source image is available for preview.");
            return;
        }
        syncFieldBindings();
        final String macro = currentMacro;
        setPreviewState(PreviewPairPanel.PreviewState.RUNNING, "Running filter preview...");
        setButtonsEnabled(false);
        previewWorker = new SwingWorker<ImagePlus, Void>() {
            @Override protected ImagePlus doInBackground() throws Exception {
                return previewAdapter.createFilteredPreview(sourceImage, macro);
            }

            @Override protected void done() {
                try {
                    installAdjustedPreview(get());
                } catch (Exception e) {
                    setPreviewState(PreviewPairPanel.PreviewState.ERROR, e.getMessage());
                    setStatus("Filter preview failed: " + e.getMessage());
                } finally {
                    setButtonsEnabled(true);
                }
            }
        };
        previewWorker.execute();
    }

    private void runPreviewNow() throws Exception {
        if (!canPreview()) {
            throw new IllegalStateException("No filter macro or source image is available for preview.");
        }
        syncFieldBindings();
        setPreviewState(PreviewPairPanel.PreviewState.RUNNING, "Running filter preview...");
        ImagePlus rendered = previewAdapter.createFilteredPreview(sourceImage, currentMacro);
        installAdjustedPreview(rendered);
    }

    private void installAdjustedPreview(ImagePlus image) {
        ImagePlus old = adjustedPreview;
        adjustedPreview = image;
        previewStale = false;
        if (actions != null) {
            actions.setAdjustedPreview(image, "Filter preview complete.");
        } else if (preview != null) {
            preview.setAdjusted(image);
            preview.setAdjustedState(PreviewPairPanel.PreviewState.READY, "Filter preview complete.");
        }
        if (old != null && old != image) {
            previewAdapter.close(old);
        }
        setStatus("Filter preview complete.");
    }

    private void openCustomFilterBuilder() {
        if (customFilterBuilder == null) {
            setError("Custom filter builder is not available here.");
            return;
        }
        try {
            syncFieldBindings();
            CustomFilterResult result = customFilterBuilder.open(activeContext, selectedPreset, currentMacro);
            if (result == null || !result.applied) {
                setStatus("Custom filter was not changed.");
                return;
            }
            selectedPreset = firstNonBlank(result.presetName, CUSTOM_PRESET);
            savedPreset = selectedPreset;
            currentMacro = safe(result.macroContent);
            savedMacro = currentMacro;
            addPresetOption(selectedPreset);
            updatingControls = true;
            try {
                presetCombo.setSelectedItem(selectedPreset);
            } finally {
                updatingControls = false;
            }
            if (presetDescriptionLabel != null) {
                presetDescriptionLabel.setText(descriptionProvider.describe(selectedPreset));
            }
            rebuildParameterEditor();
            clearAdjustedPreview();
            markPreviewStale(hasMacro() ? "Custom filter saved. Press Preview Filter." : EMPTY_TEXT);
        } catch (Exception e) {
            setError("Custom filter builder failed: " + e.getMessage());
        }
    }

    private void resetToSaved() {
        selectedPreset = firstNonBlank(savedPreset, firstPresetOption());
        currentMacro = safe(savedMacro);
        updatingControls = true;
        try {
            addPresetOption(selectedPreset);
            if (presetCombo != null) presetCombo.setSelectedItem(selectedPreset);
        } finally {
            updatingControls = false;
        }
        if (presetDescriptionLabel != null) {
            presetDescriptionLabel.setText(descriptionProvider.describe(selectedPreset));
        }
        rebuildParameterEditor();
        clearAdjustedPreview();
        markPreviewStale(hasMacro() ? STALE_TEXT : EMPTY_TEXT);
    }

    private void syncFieldBindings() {
        if (definition == null) {
            currentMacro = safe(currentMacro);
            return;
        }
        for (int i = 0; i < fieldBindings.size(); i++) {
            FilterFieldBinding binding = fieldBindings.get(i);
            binding.parameter.setValue(binding.field.getText().trim());
        }
        currentMacro = definition.render();
    }

    private void markPreviewStale(String text) {
        previewStale = true;
        setPreviewState(hasMacro()
                ? PreviewPairPanel.PreviewState.STALE
                : PreviewPairPanel.PreviewState.EMPTY,
                text);
    }

    private void setPreviewState(PreviewPairPanel.PreviewState state, String text) {
        if (preview != null) {
            preview.setAdjustedState(state, text);
        }
        if (actions != null) {
            if (state == PreviewPairPanel.PreviewState.STALE) {
                actions.markPreviewStale(text);
            } else {
                actions.setStatus(text);
            }
        }
        if (feedbackLabel != null) {
            feedbackLabel.setText(text == null || text.trim().isEmpty() ? " " : text);
        }
    }

    private void clearAdjustedPreview() {
        ImagePlus old = adjustedPreview;
        adjustedPreview = null;
        if (preview != null) {
            preview.setAdjusted(null);
        }
        if (old != null) {
            previewAdapter.close(old);
        }
    }

    private void setStatus(String text) {
        if (feedbackLabel != null) {
            feedbackLabel.setText(text == null || text.trim().isEmpty() ? " " : text);
        }
        if (actions != null) {
            actions.setStatus(text);
        }
    }

    private void setError(String text) {
        setPreviewState(PreviewPairPanel.PreviewState.ERROR, text);
        setStatus(text);
    }

    private void refreshActionState() {
        if (previewButton != null) previewButton.setEnabled(canPreview());
        if (resetButton != null) resetButton.setEnabled(true);
        if (customBuilderButton != null) customBuilderButton.setEnabled(customFilterBuilder != null);
    }

    private void setButtonsEnabled(boolean enabled) {
        if (previewButton != null) previewButton.setEnabled(enabled && canPreview());
        if (resetButton != null) resetButton.setEnabled(enabled);
        if (customBuilderButton != null) customBuilderButton.setEnabled(enabled && customFilterBuilder != null);
        if (presetCombo != null) presetCombo.setEnabled(enabled);
    }

    private boolean canPreview() {
        return hasMacro() && sourceImage != null;
    }

    private boolean hasMacro() {
        return hasMacro(currentMacro);
    }

    private boolean hasMacro(String macro) {
        return macro != null && macro.trim().length() > 0;
    }

    private String lockInSummary() {
        if (definition == null || !definition.hasEditableParameters()) {
            return "Locked in " + firstNonBlank(selectedPreset, "filter") + ".";
        }
        String summary = definition.summarizeValues(6);
        if (summary == null || summary.trim().isEmpty()) {
            return "Locked in " + definition.editableParameterCount() + " filter parameters.";
        }
        return "Locked in filter parameters: " + summary.replace('\n', ';');
    }

    private void addPresetOption(String option) {
        if (presetCombo == null || option == null || option.trim().isEmpty()) return;
        String trimmed = option.trim();
        for (int i = 0; i < presetCombo.getItemCount(); i++) {
            if (trimmed.equalsIgnoreCase(presetCombo.getItemAt(i))) return;
        }
        presetCombo.addItem(trimmed);
    }

    private String firstPresetOption() {
        return presetOptions.isEmpty() ? DEFAULT_PRESET : presetOptions.get(0);
    }

    private String preferredPresetWithoutSavedState() {
        for (int i = 0; i < presetOptions.size(); i++) {
            if (DEFAULT_PRESET.equals(presetOptions.get(i))) {
                return DEFAULT_PRESET;
            }
        }
        return firstPresetOption();
    }

    private void closePreviewWorker() {
        if (previewWorker != null && !previewWorker.isDone()) {
            previewWorker.cancel(true);
        }
        previewWorker = null;
    }

    private void closeImages() {
        ImagePlus adjusted = adjustedPreview;
        ImagePlus source = sourceImage;
        adjustedPreview = null;
        sourceImage = null;
        if (adjusted != null) previewAdapter.close(adjusted);
        if (source != null) previewAdapter.close(source);
    }

    private static List<String> copyOptions(List<String> source) {
        List<String> copy = new ArrayList<String>();
        if (source != null) {
            for (int i = 0; i < source.size(); i++) {
                String value = source.get(i);
                if (value != null && value.trim().length() > 0) {
                    addUnique(copy, value.trim());
                }
            }
        }
        if (copy.isEmpty()) copy.add(DEFAULT_PRESET);
        addUnique(copy, CUSTOM_PRESET);
        return copy;
    }

    private static void addUnique(List<String> values, String value) {
        for (int i = 0; i < values.size(); i++) {
            if (values.get(i).equalsIgnoreCase(value)) return;
        }
        values.add(value);
    }

    private static String firstNonBlank(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }

    private static String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static final class FilterFieldBinding {
        final FilterMacroEditorModel.Parameter parameter;
        final JTextField field;

        FilterFieldBinding(FilterMacroEditorModel.Parameter parameter, JTextField field) {
            this.parameter = parameter;
            this.field = field;
        }
    }
}
