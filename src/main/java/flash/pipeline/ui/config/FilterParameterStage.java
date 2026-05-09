package flash.pipeline.ui.config;

import flash.pipeline.image.FilterMacroEditorModel;
import flash.pipeline.image.dag.DagIR;
import flash.pipeline.image.dag.IjmToDagLoader;
import flash.pipeline.ui.preview.PreviewPairPanel;
import flash.pipeline.ui.sandbox.FilterBuilderPanel;
import flash.pipeline.ui.sandbox.FilterCatalog;
import ij.ImagePlus;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.GraphicsEnvironment;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Window;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class FilterParameterStage implements ConfigQcStage {

    public interface MacroStore {
        String getInitialPreset();
        String loadInitialMacro() throws Exception;
        String loadPresetMacro(String presetName) throws Exception;
        void save(String presetName, String macroContent) throws Exception;
        void saveAsPreset(String presetName, String macroContent) throws Exception;
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
    private static final String BRANCHED_BANNER_TEXT =
            "⚠ This pipeline has branches — open in canvas to edit structure.";

    private static final Pattern RUN_LINE_PATTERN = Pattern.compile(
            "run\\s*\\(\\s*\"[^\"]+\"\\s*,\\s*\"([^\"]*)\"\\s*\\)");

    /**
     * Curated map of "default-visible" parameters per ImageJ command. Anything
     * absent is hidden behind an Advanced... link. Intentional curation, not
     * heuristic - extend on user feedback rather than deriving programmatically.
     */
    private static final Map<String, List<String>> DEFAULT_VISIBLE_KEYS;
    static {
        Map<String, List<String>> m = new HashMap<String, List<String>>();
        m.put(normalizeCommandKey("Gaussian Blur..."),
                Collections.singletonList("sigma"));
        m.put(normalizeCommandKey("Subtract Background..."),
                Collections.singletonList("rolling"));
        m.put(normalizeCommandKey("Auto Local Threshold"),
                Arrays.asList("method", "radius"));
        m.put(normalizeCommandKey("Median..."),
                Collections.singletonList("radius"));
        DEFAULT_VISIBLE_KEYS = Collections.unmodifiableMap(m);
    }

    private final List<String> presetOptions;
    private final Set<String> bundledPresetNames;
    private final MacroStore macroStore;
    private final PreviewAdapter previewAdapter;
    private final CustomFilterBuilder customFilterBuilder;
    private final PresetDescriptionProvider descriptionProvider;

    private final List<FilterFieldBinding> fieldBindings = new ArrayList<FilterFieldBinding>();
    private final List<CollapsibleSection> sectionPanels = new ArrayList<CollapsibleSection>();
    /** Per-row metadata aligned to {@link #sectionPanels}. */
    private final List<RowHandle> rowHandles = new ArrayList<RowHandle>();

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
    private JButton saveAsButton;
    private JButton addFilterButton;
    private JLabel branchedBannerLabel;

    private String savedPreset;
    private String savedMacro;
    private String selectedPreset;
    private String currentMacro;
    /**
     * Stage 04: parallel "all-nodes" view. After a structural mutation, the
     * accordion is built from this (so disabled rows still render greyed
     * out). For pure parameter edits or fresh preset loads, equals
     * {@code currentMacro}. Updated through {@link #syncDisplayMacroFromHidden()}.
     */
    private String currentDisplayMacro;
    private String baseMacro;
    private boolean dirty;
    private boolean readOnlyBase;
    /** True when the loaded preset's DAG IR is non-linear (combiners or multi-line). */
    private boolean linear = true;
    private FilterMacroEditorModel.MacroDefinition definition;
    private boolean previewStale;
    private boolean updatingControls;
    /**
     * Hidden host for the SandboxModel. Composed only for its DAG model;
     * never added to the layout. Created lazily on first need so the
     * stage 03 tests that don't call {@code onEnter} still pass.
     */
    private FilterBuilderPanel hiddenBuilder;
    /**
     * True once at least one structural mutation has happened. Some flows
     * derive {@code currentMacro} from {@code hiddenBuilder.currentIjm()}
     * after this point, which adds an embedded DAG JSON header.
     */
    private boolean structurallyMutated;
    /** Cached fast-only catalog for the {@code + Add filter…} popover. */
    private FilterCatalog popoverCatalog;

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
        this.bundledPresetNames = new HashSet<String>();
        for (int i = 0; i < this.presetOptions.size(); i++) {
            String name = this.presetOptions.get(i);
            if (!CUSTOM_PRESET.equalsIgnoreCase(name)) {
                this.bundledPresetNames.add(name);
            }
        }
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
        rebuildAccordion();
        updateBranchedBannerVisibility();
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

    boolean isDirtyForTest() {
        return dirty;
    }

    boolean isReadOnlyBaseForTest() {
        return readOnlyBase;
    }

    boolean isSaveAsEnabledForTest() {
        return saveAsButton != null && saveAsButton.isEnabled();
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    String renderedComboLabelForTest() {
        if (presetCombo == null) return "";
        javax.swing.ListCellRenderer renderer = presetCombo.getRenderer();
        if (renderer == null) {
            Object v = presetCombo.getSelectedItem();
            return v == null ? "" : v.toString();
        }
        JList list = new JList();
        Component cell = renderer.getListCellRendererComponent(
                list, presetCombo.getSelectedItem(), -1, false, false);
        if (cell instanceof JLabel) return ((JLabel) cell).getText();
        Object value = presetCombo.getSelectedItem();
        return value == null ? "" : value.toString();
    }

    int sectionCountForTest() {
        return sectionPanels.size();
    }

    boolean isSectionExpandedForTest(int index) {
        if (index < 0 || index >= sectionPanels.size()) return false;
        return sectionPanels.get(index).isExpanded();
    }

    boolean hasFieldBindingForTest(String parameterKey) {
        for (int i = 0; i < fieldBindings.size(); i++) {
            if (fieldBindings.get(i).parameter.key.equalsIgnoreCase(parameterKey)) return true;
        }
        return false;
    }

    String customBuilderButtonTextForTest() {
        return customBuilderButton == null ? "" : customBuilderButton.getText();
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

    void simulateSaveAsForTest(String presetName) {
        saveAsWithName(presetName);
    }

    void simulateAddFilterForTest(String catalogLabel) {
        FilterCatalog.Entry entry = popoverCatalog().findEntryByLabel(catalogLabel);
        if (entry == null) {
            throw new IllegalArgumentException("No catalog entry named " + catalogLabel);
        }
        applyAddFilter(entry);
    }

    void simulateEyeToggleForTest(int sectionIndex) {
        if (sectionIndex < 0 || sectionIndex >= rowHandles.size()) {
            throw new IllegalArgumentException("Section index out of bounds: " + sectionIndex);
        }
        applyEyeToggle(rowHandles.get(sectionIndex).nodeId);
    }

    void simulateReorderForTest(int fromIndex, int toIndex) {
        applyReorder(fromIndex, toIndex);
    }

    void simulateDeleteForTest(int sectionIndex) {
        if (sectionIndex < 0 || sectionIndex >= rowHandles.size()) {
            throw new IllegalArgumentException("Section index out of bounds: " + sectionIndex);
        }
        applyDelete(rowHandles.get(sectionIndex).nodeId, /*confirmIfModified=*/false);
    }

    boolean isBranchedBannerVisibleForTest() {
        return branchedBannerLabel != null && branchedBannerLabel.isVisible();
    }

    boolean isAddFilterEnabledForTest() {
        return addFilterButton != null && addFilterButton.isEnabled();
    }

    boolean isCustomBuilderButtonVisibleForTest() {
        return customBuilderButton != null && customBuilderButton.isVisible();
    }

    boolean isLinearForTest() {
        return linear;
    }

    void simulateOpenCustomFilterBuilderForTest() {
        openCustomFilterBuilder();
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
        installComboRenderer();
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

        customBuilderButton = new JButton("Open in canvas…");
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

        // Branched-preset banner (visible only when DAG is non-linear).
        branchedBannerLabel = new JLabel(BRANCHED_BANNER_TEXT);
        branchedBannerLabel.setOpaque(true);
        branchedBannerLabel.setBackground(new Color(255, 244, 204));
        branchedBannerLabel.setForeground(new Color(90, 60, 0));
        branchedBannerLabel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 1, 1, 1, new Color(220, 200, 130)),
                BorderFactory.createEmptyBorder(4, 8, 4, 8)));
        branchedBannerLabel.setAlignmentX(JComponent.LEFT_ALIGNMENT);
        branchedBannerLabel.setVisible(false);
        panel.add(Box.createVerticalStrut(4));
        panel.add(branchedBannerLabel);
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
        buttons.add(Box.createHorizontalStrut(6));

        saveAsButton = new JButton("Save as preset…");
        saveAsButton.setEnabled(false);
        saveAsButton.addActionListener(e -> onSaveAsClicked());
        buttons.add(saveAsButton);
        buttons.add(Box.createHorizontalGlue());

        feedbackLabel = new JLabel(" ");
        feedbackLabel.setForeground(new Color(90, 90, 90));

        panel.add(buttons, BorderLayout.NORTH);
        panel.add(feedbackLabel, BorderLayout.CENTER);
        refreshActionState();
        return panel;
    }

    private void installComboRenderer() {
        if (presetCombo == null) return;
        presetCombo.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value,
                                                          int index, boolean selected,
                                                          boolean focused) {
                String raw = value == null ? "" : value.toString();
                boolean isSelectionCell = (index == -1);
                String label = (isSelectionCell && dirty
                        && raw.equals(selectedPreset))
                        ? raw + " (modified)"
                        : raw;
                return super.getListCellRendererComponent(list, label, index, selected, focused);
            }
        });
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
        baseMacro = currentMacro;
        currentDisplayMacro = currentMacro;
        dirty = false;
        readOnlyBase = isBundledPreset(selectedPreset);
        structurallyMutated = false;
        refreshLinearityFlag();
    }

    private void presetChanged() {
        if (updatingControls || presetCombo == null) return;
        Object selected = presetCombo.getSelectedItem();
        if (selected == null) return;
        String name = selected.toString();
        try {
            String macro = safe(macroStore.loadPresetMacro(name));
            loadPreset(name, macro);
        } catch (Exception e) {
            currentMacro = "";
            currentDisplayMacro = "";
            rebuildAccordion();
            setError("Could not load preset '" + name + "': " + e.getMessage());
        }
        refreshActionState();
    }

    private void loadPreset(String name, String macro) {
        selectedPreset = name;
        baseMacro = safe(macro);
        currentMacro = baseMacro;
        currentDisplayMacro = currentMacro;
        dirty = false;
        readOnlyBase = isBundledPreset(name);
        structurallyMutated = false;
        if (saveAsButton != null) saveAsButton.setEnabled(false);
        // Discard any prior hidden builder so it gets re-seeded from the new
        // macro on demand; otherwise its SandboxModel is stale.
        hiddenBuilder = null;
        refreshLinearityFlag();
        definition = FilterMacroEditorModel.parse(currentDisplayMacro);
        rebuildAccordion();
        updateBranchedBannerVisibility();
        if (presetDescriptionLabel != null) {
            presetDescriptionLabel.setText(descriptionProvider.describe(name));
        }
        clearAdjustedPreview();
        markPreviewStale(hasMacro() ? STALE_TEXT : EMPTY_TEXT);
        if (presetCombo != null) presetCombo.repaint();
    }

    private void rebuildAccordion() {
        if (parameterPanel == null) return;
        updatingControls = true;
        try {
            fieldBindings.clear();
            sectionPanels.clear();
            rowHandles.clear();
            parameterPanel.removeAll();
            String accordionMacro = currentDisplayMacro != null ? currentDisplayMacro : currentMacro;
            definition = FilterMacroEditorModel.parse(accordionMacro);
            if (!hasMacro()) {
                addParameterMessage("No filter macro is available for this preset.");
            } else if (definition == null || !definition.hasEditableParameters()) {
                addParameterMessage("No editable key=value parameters were detected in this macro.");
            } else {
                parameterPanel.add(buildAccordionTopBar());
                parameterPanel.add(Box.createVerticalStrut(2));
                List<FilterMacroEditorModel.Section> sections = definition.getSections();
                List<FilterBuilderPanel.NodeSummary> summaries = nodeSummariesIfLinear();
                int rowCursor = 0;
                for (int i = 0; i < sections.size(); i++) {
                    FilterMacroEditorModel.Section section = sections.get(i);
                    for (int j = 0; j < section.entries.size(); j++) {
                        FilterMacroEditorModel.Entry entry = section.entries.get(j);
                        if (!shouldShowEntry(entry)) continue;
                        rowCursor = addAccordionRow(entry,
                                rowHandles.size(), summaries, rowCursor);
                    }
                }
                parameterPanel.add(buildAddFilterRow());
            }
        } finally {
            updatingControls = false;
        }
        finalizeRowControls();
        parameterPanel.revalidate();
        parameterPanel.repaint();
        refreshActionState();
    }

    private JComponent buildAccordionTopBar() {
        JPanel bar = new JPanel(new BorderLayout());
        bar.setOpaque(false);
        bar.setAlignmentX(JComponent.LEFT_ALIGNMENT);

        JPanel right = new JPanel();
        right.setOpaque(false);
        right.setLayout(new BoxLayout(right, BoxLayout.X_AXIS));

        JButton expandAll = makeLinkButton("Expand all");
        expandAll.addActionListener(e -> setAllSectionsExpanded(true));
        JButton collapseAll = makeLinkButton("Collapse all");
        collapseAll.addActionListener(e -> setAllSectionsExpanded(false));

        right.add(expandAll);
        right.add(Box.createHorizontalStrut(4));
        right.add(new JLabel("|"));
        right.add(Box.createHorizontalStrut(4));
        right.add(collapseAll);

        bar.add(right, BorderLayout.EAST);
        return bar;
    }

    private JComponent buildAddFilterRow() {
        JPanel row = new JPanel();
        row.setOpaque(false);
        row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
        row.setAlignmentX(JComponent.LEFT_ALIGNMENT);
        row.setBorder(BorderFactory.createEmptyBorder(4, 4, 0, 4));

        addFilterButton = new JButton("+ Add filter…");
        addFilterButton.addActionListener(e -> onAddFilterClicked());
        addFilterButton.setEnabled(linear);
        row.add(addFilterButton);
        row.add(Box.createHorizontalGlue());
        return row;
    }

    private void setAllSectionsExpanded(boolean expanded) {
        for (int i = 0; i < sectionPanels.size(); i++) {
            sectionPanels.get(i).setExpanded(expanded);
        }
        if (parameterPanel != null) {
            parameterPanel.revalidate();
            parameterPanel.repaint();
        }
    }

    /**
     * Stage 04: one collapsible row per {@link FilterMacroEditorModel.Entry}.
     * Each entry that is run-style (a {@code run("...")} step) consumes one
     * slot from {@code summaries}; assignment-only entries don't.
     */
    private int addAccordionRow(FilterMacroEditorModel.Entry entry,
                                int rowOrdinal,
                                List<FilterBuilderPanel.NodeSummary> summaries,
                                int rowCursor) {
        String header = (rowOrdinal + 1) + ". " + entry.label;
        if (entry.summaryLabel != null && !entry.summaryLabel.isEmpty()
                && !entry.summaryLabel.equalsIgnoreCase(entry.label)) {
            header += " — " + entry.summaryLabel;
        }
        boolean expanded = (rowOrdinal == 0);
        CollapsibleSection collap = new CollapsibleSection(header, expanded);
        collap.setAlignmentX(JComponent.LEFT_ALIGNMENT);

        FilterBuilderPanel.NodeSummary summary = null;
        boolean entryIsRun = entryIsRunStyle(entry);
        if (entryIsRun && summaries != null && rowCursor < summaries.size()) {
            summary = summaries.get(rowCursor);
            rowCursor++;
        }
        String headerNodeId = summary == null ? "" : summary.id;
        boolean headerDisabled = summary != null && summary.disabled;

        addEntryToBody(collap.getBody(), entry, headerNodeId);

        sectionPanels.add(collap);
        RowHandle handle = new RowHandle(headerNodeId, rowOrdinal);
        rowHandles.add(handle);
        installRowHeaderControls(collap, rowOrdinal, headerNodeId, headerDisabled, handle);
        if (headerDisabled) {
            collap.setRowEnabled(false);
            collap.setHeaderStrikethrough(true);
        }
        parameterPanel.add(collap);
        parameterPanel.add(Box.createVerticalStrut(4));
        return rowCursor;
    }

    private void installRowHeaderControls(CollapsibleSection collap, int sectionIndex,
                                           String nodeId, boolean disabled, RowHandle handle) {
        JPanel controls = collap.getHeaderControls();
        controls.removeAll();
        if (nodeId == null || nodeId.isEmpty()) return;

        JButton up = makeRowButton("▲", "Move step up");
        up.addActionListener(e -> applyReorder(sectionIndex, sectionIndex - 1));
        up.setEnabled(linear && sectionIndex > 0);

        JButton down = makeRowButton("▼", "Move step down");
        down.addActionListener(e -> applyReorder(sectionIndex, sectionIndex + 1));
        // Final enabled state set in finalizeRowControls() once total row
        // count is known.
        down.setEnabled(linear);

        JButton eye = makeRowButton(disabled ? "✕" : "◉",
                disabled ? "Re-enable this step" : "Disable this step (skip in preview)");
        eye.addActionListener(e -> applyEyeToggle(nodeId));
        eye.setEnabled(linear);

        JButton delete = makeRowButton("×", "Remove this step");
        delete.addActionListener(e -> applyDelete(nodeId, /*confirmIfModified=*/true));
        delete.setEnabled(linear);

        controls.add(up);
        controls.add(Box.createHorizontalStrut(2));
        controls.add(down);
        controls.add(Box.createHorizontalStrut(2));
        controls.add(eye);
        controls.add(Box.createHorizontalStrut(2));
        controls.add(delete);

        handle.upButton = up;
        handle.downButton = down;
        handle.eyeButton = eye;
        handle.deleteButton = delete;
    }

    private void finalizeRowControls() {
        int total = rowHandles.size();
        for (int i = 0; i < total; i++) {
            RowHandle h = rowHandles.get(i);
            if (h.upButton != null) h.upButton.setEnabled(linear && i > 0);
            if (h.downButton != null) h.downButton.setEnabled(linear && i < total - 1);
        }
    }

    private static JButton makeRowButton(String glyph, String tooltip) {
        JButton button = new JButton(glyph);
        button.setMargin(new Insets(0, 4, 0, 4));
        button.setFocusPainted(false);
        button.setToolTipText(tooltip);
        button.putClientProperty("JButton.buttonType", "toolBarButton");
        return button;
    }

    /**
     * Skip the {@code run("Duplicate...", ...)} setup line that
     * {@link flash.pipeline.image.dag.DagToIjmEmitter} prepends per line and
     * any other emit-only boilerplate. These entries are an implementation
     * detail of how the DAG is serialized to IJM and shouldn't surface as
     * user-visible accordion rows.
     */
    private static boolean shouldShowEntry(FilterMacroEditorModel.Entry entry) {
        if (entry == null) return false;
        String label = entry.label == null ? "" : entry.label.trim();
        if (label.isEmpty()) return false;
        if ("Duplicate".equalsIgnoreCase(label)) return false;
        return true;
    }

    /**
     * Run-style entries map 1:1 to SandboxModel nodes; assignment-only entries
     * (rare in our bundled presets) do not. We approximate the distinction by
     * checking that the entry has at least one parameter and isn't a one-token
     * assignment whose label exactly humanizes its single parameter key — that
     * is the signature {@link FilterMacroEditorModel#parseAssignmentEntry}
     * produces. False positives just consume a node slot and may misalign the
     * row controls; bundled presets contain only run() entries today.
     */
    private static boolean entryIsRunStyle(FilterMacroEditorModel.Entry entry) {
        if (entry == null || entry.parameters == null || entry.parameters.isEmpty()) {
            return false;
        }
        // Heuristic: assignment entries always have exactly one parameter and
        // a label that humanizes that parameter's key.
        if (entry.parameters.size() != 1) return true;
        String key = entry.parameters.get(0).key;
        return !humanizeIdentifierApprox(key).equalsIgnoreCase(entry.label == null ? "" : entry.label);
    }

    private static String humanizeIdentifierApprox(String token) {
        if (token == null || token.trim().isEmpty()) return "";
        String[] parts = token.trim().split("_+");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i];
            if (part.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            if (part.length() <= 2 && part.equals(part.toLowerCase(Locale.ROOT))) {
                sb.append(part.toUpperCase(Locale.ROOT));
            } else {
                sb.append(Character.toUpperCase(part.charAt(0)));
                if (part.length() > 1) sb.append(part.substring(1));
            }
        }
        return sb.toString();
    }

    private void addEntryToBody(JComponent body, FilterMacroEditorModel.Entry entry, String nodeId) {
        JLabel name = new JLabel(entry.label);
        name.setFont(name.getFont().deriveFont(java.awt.Font.BOLD));
        name.setAlignmentX(Component.LEFT_ALIGNMENT);
        body.add(name);

        List<String> visibleKeys = DEFAULT_VISIBLE_KEYS.get(normalizeCommandKey(entry.label));
        List<FilterMacroEditorModel.Parameter> visible = new ArrayList<FilterMacroEditorModel.Parameter>();
        List<FilterMacroEditorModel.Parameter> hidden = new ArrayList<FilterMacroEditorModel.Parameter>();
        for (int i = 0; i < entry.parameters.size(); i++) {
            FilterMacroEditorModel.Parameter p = entry.parameters.get(i);
            if (visibleKeys == null || keysContain(visibleKeys, p.key)) {
                visible.add(p);
            } else {
                hidden.add(p);
            }
        }

        JPanel visiblePanel = buildParameterRows(visible, nodeId);
        visiblePanel.setAlignmentX(Component.LEFT_ALIGNMENT);
        body.add(visiblePanel);

        if (!hidden.isEmpty()) {
            final JPanel hiddenContainer = new JPanel();
            hiddenContainer.setOpaque(false);
            hiddenContainer.setLayout(new BoxLayout(hiddenContainer, BoxLayout.Y_AXIS));
            hiddenContainer.setAlignmentX(Component.LEFT_ALIGNMENT);
            hiddenContainer.setVisible(false);
            body.add(hiddenContainer);

            final List<FilterMacroEditorModel.Parameter> hiddenParams = hidden;
            final boolean[] populated = new boolean[]{false};
            final JButton advancedLink = makeLinkButton("Advanced…");
            advancedLink.setAlignmentX(Component.LEFT_ALIGNMENT);
            final String advancedNodeId = nodeId;
            advancedLink.addActionListener(e -> {
                if (!populated[0]) {
                    JPanel rows = buildParameterRows(hiddenParams, advancedNodeId);
                    rows.setAlignmentX(Component.LEFT_ALIGNMENT);
                    hiddenContainer.add(rows);
                    populated[0] = true;
                }
                boolean nowVisible = !hiddenContainer.isVisible();
                hiddenContainer.setVisible(nowVisible);
                advancedLink.setText(nowVisible ? "Hide advanced" : "Advanced…");
                body.revalidate();
                body.repaint();
            });
            body.add(advancedLink);
        }

        body.add(Box.createVerticalStrut(6));
    }

    private JPanel buildParameterRows(List<FilterMacroEditorModel.Parameter> parameters, String nodeId) {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setOpaque(false);
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(2, 0, 2, 6);
        gbc.anchor = GridBagConstraints.WEST;

        for (int i = 0; i < parameters.size(); i++) {
            FilterMacroEditorModel.Parameter parameter = parameters.get(i);

            gbc.gridy = i;
            gbc.gridx = 0;
            gbc.weightx = 0.0;
            gbc.fill = GridBagConstraints.NONE;
            panel.add(new JLabel(parameter.key), gbc);

            String value = parameter.getValue() == null ? "" : parameter.getValue();
            JTextField field = new JTextField(value, Math.max(5, Math.min(12, value.length() + 2)));
            installFieldListener(field);
            fieldBindings.add(new FilterFieldBinding(parameter, field, nodeId));

            gbc.gridx = 1;
            gbc.weightx = 1.0;
            gbc.fill = GridBagConstraints.HORIZONTAL;
            panel.add(field, gbc);
        }
        return panel;
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
        syncFieldBindings();
        recomputeDirty();
        markPreviewStale(STALE_TEXT);
    }

    private void recomputeDirty() {
        boolean nowDirty = !safe(currentMacro).equals(safe(baseMacro));
        if (nowDirty == dirty) return;
        dirty = nowDirty;
        if (saveAsButton != null) saveAsButton.setEnabled(dirty);
        if (presetCombo != null) presetCombo.repaint();
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
            String newName = firstNonBlank(result.presetName, CUSTOM_PRESET);
            String newMacro = safe(result.macroContent);
            savedPreset = newName;
            savedMacro = newMacro;
            addPresetOption(newName);
            updatingControls = true;
            try {
                presetCombo.setSelectedItem(newName);
            } finally {
                updatingControls = false;
            }
            loadPreset(newName, newMacro);
            markPreviewStale(hasMacro() ? "Custom filter saved. Press Preview Filter." : EMPTY_TEXT);
        } catch (Exception e) {
            setError("Custom filter builder failed: " + e.getMessage());
        }
    }

    private void onSaveAsClicked() {
        Window owner = saveAsButton == null ? null
                : SwingUtilities.getWindowAncestor(saveAsButton);
        String suggested = sanitizePresetName(safe(selectedPreset) + "_modified");
        String name = SaveAsPresetPopover.prompt(owner, suggested);
        if (name == null) return;
        saveAsWithName(name);
    }

    private void saveAsWithName(String rawName) {
        if (rawName == null) return;
        String name = rawName.trim();
        if (name.isEmpty()) return;
        try {
            macroStore.saveAsPreset(name, currentMacro);
            addPresetOption(name);
            updatingControls = true;
            try {
                if (presetCombo != null) presetCombo.setSelectedItem(name);
            } finally {
                updatingControls = false;
            }
            savedPreset = name;
            savedMacro = currentMacro;
            loadPreset(name, currentMacro);
        } catch (Exception e) {
            setError("Could not save preset: " + e.getMessage());
        }
    }

    private void resetToSaved() {
        String name = firstNonBlank(savedPreset, firstPresetOption());
        String macro = safe(savedMacro);
        updatingControls = true;
        try {
            addPresetOption(name);
            if (presetCombo != null) presetCombo.setSelectedItem(name);
        } finally {
            updatingControls = false;
        }
        loadPreset(name, macro);
    }

    // ── Stage 04 structural-edit handlers ────────────────────────────────

    private void onAddFilterClicked() {
        if (!linear) return;
        if (GraphicsEnvironment.isHeadless() || addFilterButton == null) return;
        AddFilterPopover.show(addFilterButton, popoverCatalog(), new AddFilterPopover.Selection() {
            @Override public void onSelected(FilterCatalog.Entry entry) {
                applyAddFilter(entry);
            }
        });
    }

    private void applyAddFilter(FilterCatalog.Entry entry) {
        if (entry == null || !linear) return;
        ensureHiddenBuilderInSyncWithCurrentMacro();
        try {
            hiddenBuilder.appendNode(entry);
        } catch (RuntimeException ex) {
            setError("Could not add filter: " + ex.getMessage());
            return;
        }
        afterStructuralMutation(/*expandLast=*/true);
    }

    private void applyEyeToggle(String nodeId) {
        if (!linear || nodeId == null || nodeId.isEmpty()) return;
        ensureHiddenBuilderInSyncWithCurrentMacro();
        boolean currentlyDisabled = false;
        List<FilterBuilderPanel.NodeSummary> summaries = hiddenBuilder.nodeSummaries();
        for (int i = 0; i < summaries.size(); i++) {
            if (nodeId.equals(summaries.get(i).id)) {
                currentlyDisabled = summaries.get(i).disabled;
                break;
            }
        }
        try {
            hiddenBuilder.setNodeDisabled(nodeId, !currentlyDisabled);
        } catch (RuntimeException ex) {
            setError("Could not toggle step: " + ex.getMessage());
            return;
        }
        afterStructuralMutation(/*expandLast=*/false);
    }

    private void applyReorder(int fromIndex, int toIndex) {
        if (!linear) return;
        if (fromIndex == toIndex) return;
        if (fromIndex < 0 || fromIndex >= rowHandles.size()) return;
        ensureHiddenBuilderInSyncWithCurrentMacro();
        try {
            hiddenBuilder.reorder(fromIndex, toIndex);
        } catch (RuntimeException ex) {
            setError("Could not reorder steps: " + ex.getMessage());
            return;
        }
        afterStructuralMutation(/*expandLast=*/false);
    }

    private void applyDelete(String nodeId, boolean confirmIfModified) {
        if (!linear || nodeId == null || nodeId.isEmpty()) return;
        ensureHiddenBuilderInSyncWithCurrentMacro();
        if (confirmIfModified && rowIsModifiedFromBaseline(nodeId)) {
            if (!confirmDeleteModified()) return;
        }
        try {
            hiddenBuilder.removeNode(nodeId);
        } catch (RuntimeException ex) {
            setError("Could not delete step: " + ex.getMessage());
            return;
        }
        afterStructuralMutation(/*expandLast=*/false);
    }

    private boolean confirmDeleteModified() {
        if (GraphicsEnvironment.isHeadless()) return true;
        Window owner = parameterPanel == null ? null : SwingUtilities.getWindowAncestor(parameterPanel);
        int choice = JOptionPane.showConfirmDialog(owner,
                "Delete this step? Your changes to it will be lost.",
                "Delete step?",
                JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.WARNING_MESSAGE);
        return choice == JOptionPane.OK_OPTION;
    }

    private boolean rowIsModifiedFromBaseline(String nodeId) {
        try {
            DagIR baseDag = IjmToDagLoader.load(safe(baseMacro));
            if (baseDag == null || baseDag.lines.isEmpty()) return false;
            String baseArgs = null;
            for (int i = 0; i < baseDag.lines.size(); i++) {
                for (int j = 0; j < baseDag.lines.get(i).ops.size(); j++) {
                    if (nodeId.equals(baseDag.lines.get(i).ops.get(j).id)) {
                        baseArgs = baseDag.lines.get(i).ops.get(j).args;
                        break;
                    }
                }
                if (baseArgs != null) break;
            }
            if (baseArgs == null) {
                // Node was added after baseline; treat as unmodified for delete confirm.
                return false;
            }
            List<FilterBuilderPanel.NodeSummary> summaries = hiddenBuilder.nodeSummaries();
            // We can't read SandboxModel.Node.args from outside the panel — but
            // we synced args from currentMacro before this call, so the IJM
            // reflects up-to-date values. Compare baseArgs to the matching
            // run() args extracted from currentMacro / currentDisplayMacro.
            String currentArgs = extractArgsForNodeIndex(safe(currentDisplayMacro != null ? currentDisplayMacro : currentMacro),
                    summaries, nodeId);
            return currentArgs != null && !currentArgs.equals(baseArgs);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static String extractArgsForNodeIndex(String macro,
                                                   List<FilterBuilderPanel.NodeSummary> summaries,
                                                   String nodeId) {
        if (macro == null || summaries == null) return null;
        int targetIndex = -1;
        for (int i = 0; i < summaries.size(); i++) {
            if (nodeId.equals(summaries.get(i).id)) {
                targetIndex = i;
                break;
            }
        }
        if (targetIndex < 0) return null;
        Matcher m = RUN_LINE_PATTERN.matcher(macro);
        int idx = 0;
        while (m.find()) {
            if (idx == targetIndex) return m.group(1);
            idx++;
        }
        return null;
    }

    private void afterStructuralMutation(boolean expandLast) {
        structurallyMutated = true;
        currentMacro = hiddenBuilder.currentIjm();
        currentDisplayMacro = hiddenBuilder.currentDisplayIjm();
        definition = FilterMacroEditorModel.parse(currentDisplayMacro);
        rebuildAccordion();
        if (expandLast && !sectionPanels.isEmpty()) {
            sectionPanels.get(sectionPanels.size() - 1).setExpanded(true);
        }
        recomputeDirty();
        refreshLinearityFlag();
        updateBranchedBannerVisibility();
        markPreviewStale(STALE_TEXT);
    }

    private void ensureHiddenBuilderInSyncWithCurrentMacro() {
        if (hiddenBuilder == null) {
            DagIR seed = IjmToDagLoader.load(safe(currentDisplayMacro));
            hiddenBuilder = new FilterBuilderPanel(seed, /*sharedPreview=*/null,
                    /*runner=*/null, null);
            return;
        }
        // Re-seed only when the macro text indicates parameter edits since
        // the last sync. We can't byte-compare cheaply; just push current
        // run-line args into the model so any field edits since the last
        // structural op are preserved.
        try {
            List<FilterBuilderPanel.NodeSummary> summaries = hiddenBuilder.nodeSummaries();
            String macroForArgs = currentDisplayMacro != null ? currentDisplayMacro : currentMacro;
            Matcher m = RUN_LINE_PATTERN.matcher(safe(macroForArgs));
            int idx = 0;
            while (m.find() && idx < summaries.size()) {
                hiddenBuilder.updateNodeArgs(summaries.get(idx).id, m.group(1));
                idx++;
            }
        } catch (IllegalStateException nonLinear) {
            // Branched DAG slipped through; rebuild from scratch on the
            // current macro to keep going.
            DagIR seed = IjmToDagLoader.load(safe(currentDisplayMacro));
            hiddenBuilder = new FilterBuilderPanel(seed, null, null, null);
        }
    }

    private void refreshLinearityFlag() {
        try {
            DagIR dag = IjmToDagLoader.load(safe(currentMacro));
            linear = dag.isLinear();
        } catch (RuntimeException e) {
            linear = true;
        }
    }

    private void updateBranchedBannerVisibility() {
        boolean showBanner = !linear;
        if (branchedBannerLabel != null) branchedBannerLabel.setVisible(showBanner);
        // Per stage 04: hide the canvas button on linear presets and show it
        // only when the DAG is branched. Reserved for branched pipelines —
        // linear edits are inline.
        if (customBuilderButton != null) customBuilderButton.setVisible(showBanner);
        setStructuralControlsEnabled(linear);
    }

    private void setStructuralControlsEnabled(boolean enabled) {
        if (addFilterButton != null) addFilterButton.setEnabled(enabled);
        for (int i = 0; i < sectionPanels.size(); i++) {
            CollapsibleSection section = sectionPanels.get(i);
            JPanel controls = section.getHeaderControls();
            if (controls == null) continue;
            for (int j = 0; j < controls.getComponentCount(); j++) {
                Component c = controls.getComponent(j);
                if (c instanceof JButton) {
                    c.setEnabled(enabled);
                }
            }
        }
    }

    private List<FilterBuilderPanel.NodeSummary> nodeSummariesIfLinear() {
        if (!linear) return null;
        ensureHiddenBuilderInSyncWithCurrentMacro();
        try {
            return hiddenBuilder.nodeSummaries();
        } catch (IllegalStateException nonLinear) {
            return null;
        }
    }

    private FilterCatalog popoverCatalog() {
        if (popoverCatalog == null) {
            popoverCatalog = new FilterCatalog();
        }
        return popoverCatalog;
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
        String rendered = definition.render();
        currentDisplayMacro = rendered;
        if (structurallyMutated && hiddenBuilder != null) {
            // Push args back into the SandboxModel so the next structural op
            // reflects these edits, then re-emit the filtered IJM.
            ensureHiddenBuilderInSyncWithCurrentMacro();
            currentMacro = hiddenBuilder.currentIjm();
            currentDisplayMacro = hiddenBuilder.currentDisplayIjm();
        } else {
            currentMacro = rendered;
        }
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
        if (saveAsButton != null) saveAsButton.setEnabled(dirty);
        if (addFilterButton != null) addFilterButton.setEnabled(linear);
    }

    private void setButtonsEnabled(boolean enabled) {
        if (previewButton != null) previewButton.setEnabled(enabled && canPreview());
        if (resetButton != null) resetButton.setEnabled(enabled);
        if (customBuilderButton != null) customBuilderButton.setEnabled(enabled && customFilterBuilder != null);
        if (saveAsButton != null) saveAsButton.setEnabled(enabled && dirty);
        if (presetCombo != null) presetCombo.setEnabled(enabled);
        if (addFilterButton != null) addFilterButton.setEnabled(enabled && linear);
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

    private boolean isBundledPreset(String name) {
        return name != null && bundledPresetNames.contains(name);
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

    private static JButton makeLinkButton(String text) {
        JButton button = new JButton(text);
        button.setBorderPainted(false);
        button.setContentAreaFilled(false);
        button.setFocusPainted(false);
        button.setMargin(new Insets(0, 4, 0, 4));
        button.setForeground(new Color(50, 110, 200));
        button.setHorizontalAlignment(SwingConstants.LEFT);
        button.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
        return button;
    }

    private static String normalizeCommandKey(String raw) {
        if (raw == null) return "";
        String s = raw.trim();
        while (s.endsWith(".")) s = s.substring(0, s.length() - 1).trim();
        s = s.replaceAll("\\s*\\([^)]*\\)\\s*$", "").trim();
        return s.toLowerCase(Locale.ROOT);
    }

    private static boolean keysContain(List<String> keys, String key) {
        if (keys == null || key == null) return false;
        for (int i = 0; i < keys.size(); i++) {
            if (key.equalsIgnoreCase(keys.get(i))) return true;
        }
        return false;
    }

    private static String sanitizePresetName(String raw) {
        if (raw == null) return "";
        String trimmed = raw.trim();
        String sanitized = trimmed.replaceAll("[<>:\"/\\\\|?*\\p{Cntrl}]+", "_").trim();
        while (sanitized.endsWith(".")) {
            sanitized = sanitized.substring(0, sanitized.length() - 1).trim();
        }
        return sanitized;
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
        final String nodeId;

        FilterFieldBinding(FilterMacroEditorModel.Parameter parameter, JTextField field, String nodeId) {
            this.parameter = parameter;
            this.field = field;
            this.nodeId = nodeId == null ? "" : nodeId;
        }
    }

    private static final class RowHandle {
        final String nodeId;
        final int sectionIndex;
        JButton upButton;
        JButton downButton;
        JButton eyeButton;
        JButton deleteButton;

        RowHandle(String nodeId, int sectionIndex) {
            this.nodeId = nodeId == null ? "" : nodeId;
            this.sectionIndex = sectionIndex;
        }
    }
}
