package flash.pipeline.ui.variations;

import flash.pipeline.bin.BinConfig;
import flash.pipeline.image.FilterMacroEditorModel;
import flash.pipeline.ui.FlashTheme;
import flash.pipeline.ui.config.CellposeParameterStage;
import flash.pipeline.ui.config.StarDistParameterStage;

import ij.ImagePlus;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;
import java.awt.Dimension;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.prefs.Preferences;

public final class ParameterSweepEditor extends JPanel {

    private final ParameterSweep.Method method;
    private final String channelName;
    private final String sourceImageHash;
    private final String cacheNamespace;
    private final int noUpperBoundMaxSize;
    private final MacroVariationCatalog macroCatalog;
    private final List<Row> rows = new ArrayList<Row>();
    private final List<ChangeListener> listeners = new ArrayList<ChangeListener>();
    private final Preferences preferences =
            Preferences.userNodeForPackage(ParameterSweepEditor.class);
    private JPanel advancedPanel;
    private JButton advancedToggle;
    private boolean advancedExpanded;
    private CropSpec cropSpec = CropSpec.full();
    private boolean chainStepFilterActive;
    private Set<Integer> selectedChainStepIndexes =
            Collections.<Integer>emptySet();

    public ParameterSweepEditor(VariationEngineContext context) {
        this(context == null ? ParameterSweep.Method.CLASSICAL : context.method(),
                baseComboFor(context),
                context == null ? "" : context.channelName(),
                sourceHash(context == null ? null : context.filteredSource()),
                maxPossibleVoxels(context == null ? null : context.filteredSource()),
                MacroVariationCatalog.forContext(context));
    }

    public static ParameterSweepEditor forFilter(FilterVariationEngineContext context) {
        if (context == null) {
            throw new IllegalArgumentException("context must not be null");
        }
        return new ParameterSweepEditor(ParameterSweep.Method.FILTER,
                context.channelName(),
                context.sourceImageHash(),
                Integer.MAX_VALUE,
                filterSectionsFor(context),
                context.cacheNamespace());
    }

    public ParameterSweepEditor(ParameterSweep.Method method,
                                ParameterCombo baseParameters,
                                String channelName,
                                String sourceImageHash) {
        this(method, baseParameters, channelName, sourceImageHash, Integer.MAX_VALUE,
                MacroVariationCatalog.empty());
    }

    private ParameterSweepEditor(ParameterSweep.Method method,
                                 ParameterCombo baseParameters,
                                 String channelName,
                                 String sourceImageHash,
                                 int noUpperBoundMaxSize) {
        this(method, baseParameters, channelName, sourceImageHash, noUpperBoundMaxSize,
                MacroVariationCatalog.empty());
    }

    private ParameterSweepEditor(ParameterSweep.Method method,
                                 ParameterCombo baseParameters,
                                 String channelName,
                                 String sourceImageHash,
                                 int noUpperBoundMaxSize,
                                 MacroVariationCatalog macroCatalog) {
        this(method, channelName, sourceImageHash, noUpperBoundMaxSize,
                sectionsFor(method, baseParameters), "", macroCatalog);
    }

    private ParameterSweepEditor(ParameterSweep.Method method,
                                 String channelName,
                                 String sourceImageHash,
                                 int noUpperBoundMaxSize,
                                 ParameterSections sections,
                                 String cacheNamespace) {
        this(method, channelName, sourceImageHash, noUpperBoundMaxSize, sections,
                cacheNamespace, MacroVariationCatalog.empty());
    }

    private ParameterSweepEditor(ParameterSweep.Method method,
                                 String channelName,
                                 String sourceImageHash,
                                 int noUpperBoundMaxSize,
                                 ParameterSections sections,
                                 String cacheNamespace,
                                 MacroVariationCatalog macroCatalog) {
        super();
        this.method = method == null ? ParameterSweep.Method.CLASSICAL : method;
        this.channelName = channelName == null ? "" : channelName;
        this.sourceImageHash = sourceImageHash == null ? "" : sourceImageHash;
        this.cacheNamespace = cacheNamespace == null ? "" : cacheNamespace.trim();
        this.noUpperBoundMaxSize = noUpperBoundMaxSize <= 0
                ? Integer.MAX_VALUE
                : noUpperBoundMaxSize;
        this.macroCatalog = macroCatalog == null
                ? MacroVariationCatalog.empty()
                : macroCatalog;
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setOpaque(false);
        setBorder(BorderFactory.createLineBorder(FlashTheme.BORDER));
        build(sections == null
                ? new ParameterSections(Collections.<ParameterDefinition>emptyList(),
                Collections.<ParameterDefinition>emptyList())
                : sections);
    }

    public ParameterSweep currentSweep() {
        LinkedHashMap<ParameterKey, ParameterValueList> values =
                new LinkedHashMap<ParameterKey, ParameterValueList>();
        for (int i = 0; i < rows.size(); i++) {
            Row row = rows.get(i);
            if (!row.visibleForSweep()) {
                continue;
            }
            ParameterValueList current = row.values.currentValueList();
            if (row.sweepBox.isSelected()) {
                values.put(row.id, current);
            } else {
                values.put(row.id, new ParameterValueList(
                        Collections.singletonList(current.get(0))));
            }
        }
        return new ParameterSweep(method, values, cropSpec, channelName,
                sourceImageHash, cacheNamespace, selectedMacroVariations());
    }

    public void setCropSpec(CropSpec cropSpec) {
        this.cropSpec = cropSpec == null ? CropSpec.full() : cropSpec;
        fireChanged();
    }

    public void setSweep(ParameterSweep sweep) {
        if (sweep == null) {
            return;
        }
        cropSpec = sweep.cropSpec();
        Map<ParameterKey, ParameterValueList> valueLists = sweep.valueLists();
        for (int i = 0; i < rows.size(); i++) {
            Row row = rows.get(i);
            ParameterValueList values = valueLists.get(row.id);
            if (values != null) {
                if (row.id == ParameterId.MACRO) {
                    row.values.setMacroVariationSet(sweep.macroVariations());
                }
                row.values.setValues(values.values());
                row.sweepBox.setSelected(values.size() > 1);
            }
        }
        autoExpandAdvancedIfActive();
        fireChanged();
    }

    public void setSelectedChainStepIndexes(Set<Integer> selectedStepIndexes) {
        chainStepFilterActive = true;
        selectedChainStepIndexes = copyNonNegative(selectedStepIndexes);
        applyChainStepFilter();
        fireChanged();
    }

    public void applySuggestedValues(Map<? extends ParameterKey, ParameterValueList> suggestions) {
        if (suggestions == null || suggestions.isEmpty()) {
            return;
        }
        for (int i = 0; i < rows.size(); i++) {
            Row row = rows.get(i);
            ParameterValueList values = suggestions.get(row.id);
            if (values != null && values.size() > 0) {
                row.values.setValues(values.values());
            }
        }
        autoExpandAdvancedIfActive();
        fireChanged();
    }

    public void addChangeListener(ChangeListener listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    void setParameterValuesForTest(ParameterKey id, List<?> values) {
        Row row = rowFor(id);
        if (row != null) {
            row.values.setValues(values);
        }
    }

    void setSweptForTest(ParameterKey id, boolean swept) {
        Row row = rowFor(id);
        if (row != null) {
            row.sweepBox.setSelected(swept);
        }
    }

    boolean isSweptForTest(ParameterKey id) {
        Row row = rowFor(id);
        return row != null && row.sweepBox.isSelected();
    }

    int valueCountForTest(ParameterKey id) {
        Row row = rowFor(id);
        return row == null ? 0 : row.values.currentValueList().size();
    }

    List<ParameterKey> parameterKeysForTest() {
        List<ParameterKey> keys = new ArrayList<ParameterKey>();
        for (int i = 0; i < rows.size(); i++) {
            Row row = rows.get(i);
            if (row.visibleForSweep()) {
                keys.add(row.id);
            }
        }
        return keys;
    }

    List<ParameterKey> allParameterKeysForTest() {
        List<ParameterKey> keys = new ArrayList<ParameterKey>();
        for (int i = 0; i < rows.size(); i++) {
            keys.add(rows.get(i).id);
        }
        return keys;
    }

    boolean advancedExpandedForTest() {
        return advancedExpanded;
    }

    private void build(ParameterSections sections) {
        add(headerRow());
        for (int i = 0; i < sections.primary.size(); i++) {
            add(rowPanel(sections.primary.get(i), false));
        }
        if (!sections.advanced.isEmpty()) {
            advancedExpanded = loadAdvancedExpanded();
            add(advancedToggleRow(sections.advanced.size()));
            advancedPanel = new JPanel();
            advancedPanel.setOpaque(false);
            advancedPanel.setLayout(new BoxLayout(advancedPanel, BoxLayout.Y_AXIS));
            advancedPanel.setAlignmentX(LEFT_ALIGNMENT);
            for (int i = 0; i < sections.advanced.size(); i++) {
                advancedPanel.add(rowPanel(sections.advanced.get(i), true));
            }
            add(advancedPanel);
            autoExpandAdvancedIfActive();
            updateAdvancedVisibility();
        }
    }

    private JPanel headerRow() {
        JPanel row = new JPanel();
        row.setOpaque(false);
        row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
        row.setBorder(BorderFactory.createEmptyBorder(6, 8, 2, 8));
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));

        JLabel sweep = headerLabel("Sweep");
        sweep.setPreferredSize(new Dimension(72, 20));
        row.add(sweep);

        JLabel parameter = headerLabel("Parameter");
        parameter.setPreferredSize(new Dimension(180, 20));
        row.add(parameter);

        row.add(headerLabel("Values"));
        row.add(Box.createHorizontalGlue());
        return row;
    }

    private JPanel advancedToggleRow(int advancedCount) {
        JPanel row = new JPanel();
        row.setOpaque(false);
        row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
        row.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, FlashTheme.BORDER_MUTED),
                BorderFactory.createEmptyBorder(4, 8, 4, 8)));
        row.setAlignmentX(LEFT_ALIGNMENT);
        advancedToggle = new JButton();
        advancedToggle.setFocusPainted(false);
        advancedToggle.setBorder(BorderFactory.createEmptyBorder(3, 6, 3, 6));
        advancedToggle.setContentAreaFilled(false);
        advancedToggle.addActionListener(e -> {
            advancedExpanded = !advancedExpanded;
            storeAdvancedExpanded(advancedExpanded);
            updateAdvancedVisibility();
        });
        row.add(Box.createHorizontalStrut(72));
        row.add(advancedToggle);
        row.add(Box.createHorizontalGlue());
        updateAdvancedToggleText(advancedCount);
        return row;
    }

    private JPanel rowPanel(final ParameterDefinition definition,
                            final boolean advanced) {
        final ParameterKey id = definition.id;
        JPanel row = new JPanel();
        row.setOpaque(false);
        row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
        row.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, FlashTheme.BORDER_MUTED),
                BorderFactory.createEmptyBorder(4, 8, 4, 8)));
        row.setAlignmentX(LEFT_ALIGNMENT);

        JCheckBox sweepBox = new JCheckBox();
        sweepBox.setOpaque(false);
        sweepBox.setPreferredSize(new Dimension(72, 24));
        sweepBox.setToolTipText("Sweep this parameter");
        row.add(sweepBox);

        JLabel name = new JLabel(id.displayLabel());
        name.setPreferredSize(new Dimension(180, 24));
        row.add(name);

        ParameterValueList initial = new ParameterValueList(
                Collections.singletonList(definition.baseValue));
        ValueControl chips = createValueControl(definition, initial);
        chips.component().setAlignmentX(LEFT_ALIGNMENT);
        row.add(chips.component());
        row.add(Box.createHorizontalGlue());

        final Row rowState = new Row(id, row, sweepBox, chips, advanced,
                definition.chainStepIndex);
        rows.add(rowState);
        sweepBox.addActionListener(e -> {
            if (advanced && rowState.sweepBox.isSelected()) {
                advancedExpanded = true;
                updateAdvancedVisibility();
            }
            fireChanged();
        });
        chips.addChangeListener(new ChangeListener() {
            @Override public void stateChanged(ChangeEvent e) {
                if (id == ParameterId.MACRO
                        && rowState.values.currentValueList().size() > 1) {
                    rowState.sweepBox.setSelected(true);
                }
                if (advanced && isAdvancedRowActive(rowState)) {
                    advancedExpanded = true;
                    updateAdvancedVisibility();
                }
                fireChanged();
            }
        });
        return row;
    }

    private void applyChainStepFilter() {
        for (int i = 0; i < rows.size(); i++) {
            Row row = rows.get(i);
            row.panel.setVisible(shouldShowRow(row));
        }
        autoExpandAdvancedIfActive();
        revalidate();
        repaint();
    }

    private boolean shouldShowRow(Row row) {
        if (row == null || !chainStepFilterActive || row.chainStepIndex < 0) {
            return true;
        }
        if (row.id == null || row.id.valueKind() != ParameterKey.ValueKind.NUMBER) {
            return false;
        }
        if (!selectedChainStepIndexes.isEmpty()) {
            return selectedChainStepIndexes.contains(Integer.valueOf(row.chainStepIndex));
        }
        return true;
    }

    private static Set<Integer> copyNonNegative(Set<Integer> source) {
        if (source == null || source.isEmpty()) {
            return Collections.<Integer>emptySet();
        }
        Set<Integer> out = new LinkedHashSet<Integer>();
        for (Integer value : source) {
            if (value != null && value.intValue() >= 0) {
                out.add(value);
            }
        }
        return Collections.unmodifiableSet(out);
    }

    private ValueControl createValueControl(ParameterDefinition definition,
                                            ParameterValueList initial) {
        if (definition.id == ParameterId.MACRO) {
            return new MacroValueControl(new MacroVariationChipPanel(initial, macroCatalog));
        }
        return new StandardValueControl(new ValueChipPanel(initial, definition.parser));
    }

    private Row rowFor(ParameterKey id) {
        for (int i = 0; i < rows.size(); i++) {
            Row row = rows.get(i);
            if (row.id.equals(id)) {
                return row;
            }
        }
        return null;
    }

    private void fireChanged() {
        ChangeEvent event = new ChangeEvent(this);
        for (int i = 0; i < listeners.size(); i++) {
            listeners.get(i).stateChanged(event);
        }
    }

    private static JLabel headerLabel(String text) {
        JLabel label = new JLabel(text);
        label.setFont(label.getFont().deriveFont(java.awt.Font.BOLD, 11f));
        label.setForeground(FlashTheme.TEXT_SUBHEADER);
        return label;
    }

    private static ParameterSections sectionsFor(ParameterSweep.Method method,
                                                 ParameterCombo baseParameters) {
        List<ParameterDefinition> primary = new ArrayList<ParameterDefinition>();
        List<ParameterDefinition> advanced = new ArrayList<ParameterDefinition>();
        if (method == ParameterSweep.Method.STARDIST) {
            primary.add(definitionFor(ParameterId.PROB_THRESH, baseParameters));
            primary.add(definitionFor(ParameterId.NMS_THRESH, baseParameters));
            advanced.add(definitionFor(ParameterId.LINKING_MAX, baseParameters));
            advanced.add(definitionFor(ParameterId.GAP_CLOSING_MAX, baseParameters));
            advanced.add(definitionFor(ParameterId.FRAME_GAP, baseParameters));
            advanced.add(definitionFor(ParameterId.AREA_MIN, baseParameters));
            advanced.add(definitionFor(ParameterId.AREA_MAX, baseParameters));
            advanced.add(definitionFor(ParameterId.QUALITY_MIN, baseParameters));
            advanced.add(definitionFor(ParameterId.INTENSITY_MIN, baseParameters));
            advanced.add(definitionFor(ParameterId.MACRO, baseParameters));
        } else if (method == ParameterSweep.Method.CELLPOSE) {
            primary.add(definitionFor(ParameterId.DIAMETER, baseParameters));
            primary.add(definitionFor(ParameterId.FLOW_THRESHOLD, baseParameters));
            primary.add(definitionFor(ParameterId.CELLPROB_THRESHOLD, baseParameters));
            advanced.add(definitionFor(ParameterId.MODEL, baseParameters));
            advanced.add(definitionFor(ParameterId.MACRO, baseParameters));
        } else {
            primary.add(definitionFor(ParameterId.THRESHOLD, baseParameters));
            primary.add(definitionFor(ParameterId.MIN_SIZE, baseParameters));
            advanced.add(definitionFor(ParameterId.MAX_SIZE, baseParameters));
            advanced.add(definitionFor(ParameterId.MACRO, baseParameters));
        }
        return new ParameterSections(primary, advanced);
    }

    private static ParameterDefinition definitionFor(ParameterId id,
                                                     ParameterCombo baseParameters) {
        return new ParameterDefinition(id, baseValueFor(id, baseParameters),
                parserFor(id), -1);
    }

    private static ParameterSections filterSectionsFor(FilterVariationEngineContext context) {
        List<ParameterDefinition> primary = new ArrayList<ParameterDefinition>();
        FilterMacroEditorModel.MacroDefinition macro = context.baseMacro();
        List<FilterMacroEditorModel.Section> sections = macro.getSections();
        int stepIndex = 0;
        for (int i = 0; i < sections.size(); i++) {
            FilterMacroEditorModel.Section section = sections.get(i);
            for (int j = 0; j < section.entries.size(); j++) {
                FilterMacroEditorModel.Entry entry = section.entries.get(j);
                for (int k = 0; k < entry.parameters.size(); k++) {
                    FilterMacroEditorModel.Parameter parameter =
                            entry.parameters.get(k);
                    Object baseValue = filterBaseValue(parameter);
                    ParameterKey.ValueKind kind = baseValue instanceof String
                            ? ParameterKey.ValueKind.STRING
                            : ParameterKey.ValueKind.NUMBER;
                    FilterParameterId id = new FilterParameterId(i, j, k,
                            entry.label, parameter.key, kind);
                    primary.add(new ParameterDefinition(id, baseValue,
                            parserForFilterValue(baseValue), stepIndex));
                }
                stepIndex++;
            }
        }
        return new ParameterSections(primary,
                Collections.<ParameterDefinition>emptyList());
    }

    private static ValueChipPanel.ValueParser parserFor(ParameterId id) {
        if (id == ParameterId.MODEL) {
            return ValueChipPanel.stringParser();
        }
        if (id == ParameterId.MAX_SIZE) {
            return ValueChipPanel.maxSizeParser();
        }
        if (id == ParameterId.THRESHOLD
                || id == ParameterId.MIN_SIZE
                || id == ParameterId.FRAME_GAP) {
            return ValueChipPanel.intParser();
        }
        return ValueChipPanel.doubleParser();
    }

    private static ValueChipPanel.ValueParser parserForFilterValue(Object value) {
        if (value instanceof String) {
            return ValueChipPanel.stringParser();
        }
        if (value instanceof Integer) {
            return ValueChipPanel.intParser();
        }
        return ValueChipPanel.doubleParser();
    }

    private static Object baseValueFor(ParameterId id, ParameterCombo baseParameters) {
        if (baseParameters != null && baseParameters.contains(id)) {
            return baseParameters.get(id);
        }
        if (id == ParameterId.THRESHOLD) return Integer.valueOf(128);
        if (id == ParameterId.MIN_SIZE) return Integer.valueOf(100);
        if (id == ParameterId.MAX_SIZE) return Integer.valueOf(Integer.MAX_VALUE);
        if (id == ParameterId.PROB_THRESH) return Double.valueOf(0.5d);
        if (id == ParameterId.NMS_THRESH) return Double.valueOf(0.3d);
        if (id == ParameterId.LINKING_MAX) return Double.valueOf(15.0d);
        if (id == ParameterId.GAP_CLOSING_MAX) return Double.valueOf(15.0d);
        if (id == ParameterId.FRAME_GAP) return Integer.valueOf(2);
        if (id == ParameterId.AREA_MIN) return Double.valueOf(0.0d);
        if (id == ParameterId.AREA_MAX) return Double.valueOf(0.0d);
        if (id == ParameterId.QUALITY_MIN) return Double.valueOf(0.0d);
        if (id == ParameterId.INTENSITY_MIN) return Double.valueOf(0.0d);
        if (id == ParameterId.DIAMETER) return Double.valueOf(30.0d);
        if (id == ParameterId.FLOW_THRESHOLD) return Double.valueOf(0.4d);
        if (id == ParameterId.CELLPROB_THRESHOLD) return Double.valueOf(0.0d);
        if (id == ParameterId.MODEL) return BinConfig.DEFAULT_CELLPOSE_MODEL;
        if (id == ParameterId.MACRO) return MacroToken.NONE_VALUE;
        return Integer.valueOf(0);
    }

    private static Object filterBaseValue(FilterMacroEditorModel.Parameter parameter) {
        String value = parameter == null ? "" : firstNonBlank(
                parameter.getValue(), parameter.defaultValue);
        Double parsed = parseFiniteDouble(value);
        if (parsed == null) {
            return value == null || value.trim().isEmpty() ? "" : value.trim();
        }
        double number = parsed.doubleValue();
        if (shouldUseIntegerFilterValue(parameter == null ? "" : parameter.key,
                value, number)) {
            return Integer.valueOf((int) Math.rint(number));
        }
        return Double.valueOf(number);
    }

    private static Double parseFiniteDouble(String value) {
        try {
            double parsed = Double.parseDouble(value == null ? "" : value.trim());
            return Double.isNaN(parsed) || Double.isInfinite(parsed)
                    ? null
                    : Double.valueOf(parsed);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static boolean shouldUseIntegerFilterValue(String key,
                                                       String rawValue,
                                                       double parsed) {
        if (Math.rint(parsed) != parsed
                || parsed < Integer.MIN_VALUE
                || parsed > Integer.MAX_VALUE) {
            return false;
        }
        String lowerKey = key == null ? "" : key.trim().toLowerCase(java.util.Locale.ROOT);
        if (lowerKey.equals("sigma") || lowerKey.equals("x")
                || lowerKey.equals("y") || lowerKey.equals("z")) {
            return false;
        }
        if (lowerKey.equals("radius") || lowerKey.equals("rolling")
                || lowerKey.equals("threshold") || lowerKey.equals("iterations")
                || lowerKey.equals("count") || lowerKey.equals("frame")
                || lowerKey.endsWith("_min") || lowerKey.endsWith("_max")
                || lowerKey.indexOf("size") >= 0) {
            return true;
        }
        String text = rawValue == null ? "" : rawValue.trim();
        return text.matches("-?\\d+");
    }

    private static ParameterCombo baseComboFor(VariationEngineContext context) {
        if (context == null || context.baseParameters() == null) {
            return ParameterCombo.builder().build();
        }
        Object base = context.baseParameters();
        if (base instanceof ParameterCombo) {
            return (ParameterCombo) base;
        }
        ParameterCombo.Builder builder = ParameterCombo.builder();
        if (base instanceof StarDistParameterStage.Parameters) {
            StarDistParameterStage.Parameters p =
                    (StarDistParameterStage.Parameters) base;
            builder.put(ParameterId.PROB_THRESH, Double.valueOf(p.probabilityThreshold));
            builder.put(ParameterId.NMS_THRESH, Double.valueOf(p.nmsThreshold));
            builder.put(ParameterId.LINKING_MAX, Double.valueOf(p.linkingMaxDistance));
            builder.put(ParameterId.GAP_CLOSING_MAX, Double.valueOf(p.gapClosingMaxDistance));
            builder.put(ParameterId.FRAME_GAP, Integer.valueOf(p.maxFrameGap));
            builder.put(ParameterId.AREA_MIN, Double.valueOf(p.areaMin));
            builder.put(ParameterId.AREA_MAX, Double.valueOf(
                    Double.isInfinite(p.areaMax) ? 0.0d : p.areaMax));
            builder.put(ParameterId.QUALITY_MIN, Double.valueOf(p.qualityMin));
            builder.put(ParameterId.INTENSITY_MIN, Double.valueOf(p.intensityMin));
        } else if (base instanceof CellposeParameterStage.Parameters) {
            CellposeParameterStage.Parameters p =
                    (CellposeParameterStage.Parameters) base;
            builder.put(ParameterId.DIAMETER, Double.valueOf(p.diameter));
            builder.put(ParameterId.FLOW_THRESHOLD, Double.valueOf(p.flowThreshold));
            builder.put(ParameterId.CELLPROB_THRESHOLD, Double.valueOf(p.cellprobThreshold));
            builder.put(ParameterId.MODEL, p.modelToken);
        }
        return builder.build();
    }

    private static String sourceHash(ImagePlus image) {
        return FilterVariationEngineContext.sourceImageHash(image);
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static String firstNonBlank(String value, String fallback) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.length() > 0) {
            return trimmed;
        }
        return fallback == null ? "" : fallback.trim();
    }

    private static int maxPossibleVoxels(ImagePlus image) {
        if (image == null) {
            return Integer.MAX_VALUE;
        }
        long voxels = (long) image.getWidth()
                * (long) image.getHeight()
                * (long) image.getNSlices();
        return voxels > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) voxels;
    }

    private void autoExpandAdvancedIfActive() {
        if (hasActiveAdvancedRows()) {
            advancedExpanded = true;
        }
        updateAdvancedVisibility();
    }

    private boolean hasActiveAdvancedRows() {
        for (int i = 0; i < rows.size(); i++) {
            Row row = rows.get(i);
            if (row.advanced && isAdvancedRowActive(row)) {
                return true;
            }
        }
        return false;
    }

    private MacroVariationSet selectedMacroVariations() {
        Row row = rowFor(ParameterId.MACRO);
        if (row == null) {
            return null;
        }
        return row.values.selectedMacroVariationSet();
    }

    private boolean isAdvancedRowActive(Row row) {
        if (row == null) {
            return false;
        }
        ParameterValueList values = row.values.currentValueList();
        return row.sweepBox.isSelected()
                || values.size() > 1
                || hasFiniteClassicalMaxCap(row.id, values);
    }

    private boolean hasFiniteClassicalMaxCap(ParameterKey id, ParameterValueList values) {
        if (method != ParameterSweep.Method.CLASSICAL
                || id != ParameterId.MAX_SIZE
                || values == null) {
            return false;
        }
        for (int i = 0; i < values.size(); i++) {
            Object value = values.get(i);
            if (value instanceof Number
                    && ((Number) value).doubleValue() < noUpperBoundMaxSize) {
                return true;
            }
        }
        return false;
    }

    private void updateAdvancedVisibility() {
        if (advancedPanel == null || advancedToggle == null) {
            return;
        }
        advancedPanel.setVisible(advancedExpanded);
        updateAdvancedToggleText(countAdvancedRows());
        revalidate();
        repaint();
    }

    private void updateAdvancedToggleText(int advancedCount) {
        if (advancedToggle == null) {
            return;
        }
        String noun = advancedCount == 1 ? "parameter" : "parameters";
        advancedToggle.setText((advancedExpanded ? "\u25be Hide " : "\u25b8 Show ")
                + advancedCount + " advanced " + noun);
    }

    private int countAdvancedRows() {
        int count = 0;
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).advanced) {
                count++;
            }
        }
        return count;
    }

    private boolean loadAdvancedExpanded() {
        try {
            return preferences.getBoolean(preferenceKey(), false);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private void storeAdvancedExpanded(boolean expanded) {
        try {
            preferences.putBoolean(preferenceKey(), expanded);
        } catch (RuntimeException e) {
            // UI preference persistence should never block opening the editor.
        }
    }

    private String preferenceKey() {
        return "advancedExpanded." + method.name() + "." + safePreferenceKey(channelName);
    }

    private static String safePreferenceKey(String text) {
        String raw = text == null ? "" : text.trim();
        if (raw.isEmpty()) {
            return "default";
        }
        return raw.replaceAll("[^A-Za-z0-9_.-]", "_");
    }

    private static final class ParameterSections {
        final List<ParameterDefinition> primary;
        final List<ParameterDefinition> advanced;

        ParameterSections(List<ParameterDefinition> primary,
                          List<ParameterDefinition> advanced) {
            this.primary = primary;
            this.advanced = advanced;
        }
    }

    private static final class ParameterDefinition {
        final ParameterKey id;
        final Object baseValue;
        final ValueChipPanel.ValueParser parser;
        final int chainStepIndex;

        ParameterDefinition(ParameterKey id,
                            Object baseValue,
                            ValueChipPanel.ValueParser parser,
                            int chainStepIndex) {
            this.id = id;
            this.baseValue = baseValue;
            this.parser = parser;
            this.chainStepIndex = chainStepIndex;
        }
    }

    private static final class Row {
        final ParameterKey id;
        final JPanel panel;
        final JCheckBox sweepBox;
        final ValueControl values;
        final boolean advanced;
        final int chainStepIndex;

        Row(ParameterKey id,
            JPanel panel,
            JCheckBox sweepBox,
            ValueControl values,
            boolean advanced,
            int chainStepIndex) {
            this.id = id;
            this.panel = panel;
            this.sweepBox = sweepBox;
            this.values = values;
            this.advanced = advanced;
            this.chainStepIndex = chainStepIndex;
        }

        boolean visibleForSweep() {
            return panel == null || panel.isVisible();
        }
    }

    private interface ValueControl {
        JComponent component();
        ParameterValueList currentValueList();
        void setValues(List<?> values);
        void addChangeListener(ChangeListener listener);
        void setMacroVariationSet(MacroVariationSet macroVariationSet);
        MacroVariationSet selectedMacroVariationSet();
    }

    private static final class StandardValueControl implements ValueControl {
        private final ValueChipPanel panel;

        StandardValueControl(ValueChipPanel panel) {
            this.panel = panel;
        }

        @Override public JComponent component() {
            return panel;
        }

        @Override public ParameterValueList currentValueList() {
            return panel.currentValueList();
        }

        @Override public void setValues(List<?> values) {
            panel.setValues(values);
        }

        @Override public void addChangeListener(ChangeListener listener) {
            panel.addChangeListener(listener);
        }

        @Override public void setMacroVariationSet(MacroVariationSet macroVariationSet) {
        }

        @Override public MacroVariationSet selectedMacroVariationSet() {
            return null;
        }
    }

    private static final class MacroValueControl implements ValueControl {
        private final MacroVariationChipPanel panel;

        MacroValueControl(MacroVariationChipPanel panel) {
            this.panel = panel;
        }

        @Override public JComponent component() {
            return panel;
        }

        @Override public ParameterValueList currentValueList() {
            return panel.currentValueList();
        }

        @Override public void setValues(List<?> values) {
            panel.setValues(values);
        }

        @Override public void addChangeListener(ChangeListener listener) {
            panel.addChangeListener(listener);
        }

        @Override public void setMacroVariationSet(MacroVariationSet macroVariationSet) {
            panel.setMacroVariationSet(macroVariationSet);
        }

        @Override public MacroVariationSet selectedMacroVariationSet() {
            return panel.selectedVariationSet();
        }
    }
}
