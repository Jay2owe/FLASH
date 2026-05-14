package flash.pipeline.ui.variations;

import flash.pipeline.cellpose.CellposeModel;
import flash.pipeline.ui.config.CellposeParameterStage;
import flash.pipeline.ui.config.StarDistParameterStage;

import ij.ImagePlus;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;
import java.awt.Color;
import java.awt.Dimension;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.prefs.Preferences;

public final class ParameterSweepEditor extends JPanel {

    private final ParameterSweep.Method method;
    private final String channelName;
    private final String sourceImageHash;
    private final int noUpperBoundMaxSize;
    private final List<Row> rows = new ArrayList<Row>();
    private final List<ChangeListener> listeners = new ArrayList<ChangeListener>();
    private final Preferences preferences =
            Preferences.userNodeForPackage(ParameterSweepEditor.class);
    private JPanel advancedPanel;
    private JButton advancedToggle;
    private boolean advancedExpanded;
    private CropSpec cropSpec = CropSpec.full();

    public ParameterSweepEditor(VariationEngineContext context) {
        this(context == null ? ParameterSweep.Method.CLASSICAL : context.method(),
                baseComboFor(context),
                context == null ? "" : context.channelName(),
                sourceHash(context == null ? null : context.filteredSource()),
                maxPossibleVoxels(context == null ? null : context.filteredSource()));
    }

    public ParameterSweepEditor(ParameterSweep.Method method,
                                ParameterCombo baseParameters,
                                String channelName,
                                String sourceImageHash) {
        this(method, baseParameters, channelName, sourceImageHash, Integer.MAX_VALUE);
    }

    private ParameterSweepEditor(ParameterSweep.Method method,
                                 ParameterCombo baseParameters,
                                 String channelName,
                                 String sourceImageHash,
                                 int noUpperBoundMaxSize) {
        super();
        this.method = method == null ? ParameterSweep.Method.CLASSICAL : method;
        this.channelName = channelName == null ? "" : channelName;
        this.sourceImageHash = sourceImageHash == null ? "" : sourceImageHash;
        this.noUpperBoundMaxSize = noUpperBoundMaxSize <= 0
                ? Integer.MAX_VALUE
                : noUpperBoundMaxSize;
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setOpaque(false);
        setBorder(BorderFactory.createLineBorder(new Color(214, 220, 224)));
        build(baseParameters == null ? ParameterCombo.builder().build() : baseParameters);
    }

    public ParameterSweep currentSweep() {
        LinkedHashMap<ParameterId, ParameterValueList> values =
                new LinkedHashMap<ParameterId, ParameterValueList>();
        for (int i = 0; i < rows.size(); i++) {
            Row row = rows.get(i);
            ParameterValueList current = row.values.currentValueList();
            if (row.sweepBox.isSelected()) {
                values.put(row.id, current);
            } else {
                values.put(row.id, new ParameterValueList(
                        Collections.singletonList(current.get(0))));
            }
        }
        return new ParameterSweep(method, values, cropSpec, channelName, sourceImageHash);
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
                row.values.setValues(values.values());
                row.sweepBox.setSelected(values.size() > 1);
            }
        }
        autoExpandAdvancedIfActive();
        fireChanged();
    }

    public void applySuggestedValues(Map<ParameterId, ParameterValueList> suggestions) {
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

    void setParameterValuesForTest(ParameterId id, List<?> values) {
        Row row = rowFor(id);
        if (row != null) {
            row.values.setValues(values);
        }
    }

    void setSweptForTest(ParameterId id, boolean swept) {
        Row row = rowFor(id);
        if (row != null) {
            row.sweepBox.setSelected(swept);
        }
    }

    boolean isSweptForTest(ParameterId id) {
        Row row = rowFor(id);
        return row != null && row.sweepBox.isSelected();
    }

    int valueCountForTest(ParameterId id) {
        Row row = rowFor(id);
        return row == null ? 0 : row.values.currentValueList().size();
    }

    boolean advancedExpandedForTest() {
        return advancedExpanded;
    }

    private void build(ParameterCombo baseParameters) {
        add(headerRow());
        ParameterSections sections = sectionsFor(method);
        for (int i = 0; i < sections.primary.size(); i++) {
            add(rowPanel(sections.primary.get(i), baseParameters, false));
        }
        if (!sections.advanced.isEmpty()) {
            advancedExpanded = loadAdvancedExpanded();
            add(advancedToggleRow(sections.advanced.size()));
            advancedPanel = new JPanel();
            advancedPanel.setOpaque(false);
            advancedPanel.setLayout(new BoxLayout(advancedPanel, BoxLayout.Y_AXIS));
            advancedPanel.setAlignmentX(LEFT_ALIGNMENT);
            for (int i = 0; i < sections.advanced.size(); i++) {
                advancedPanel.add(rowPanel(sections.advanced.get(i), baseParameters, true));
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
                BorderFactory.createMatteBorder(1, 0, 0, 0, new Color(230, 234, 238)),
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

    private JPanel rowPanel(final ParameterId id,
                            ParameterCombo baseParameters,
                            final boolean advanced) {
        JPanel row = new JPanel();
        row.setOpaque(false);
        row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
        row.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, new Color(230, 234, 238)),
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
                Collections.singletonList(baseValueFor(id, baseParameters)));
        ValueChipPanel chips = new ValueChipPanel(initial, parserFor(id));
        chips.setAlignmentX(LEFT_ALIGNMENT);
        row.add(chips);
        row.add(Box.createHorizontalGlue());

        final Row rowState = new Row(id, sweepBox, chips, advanced);
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
                if (advanced && isAdvancedRowActive(rowState)) {
                    advancedExpanded = true;
                    updateAdvancedVisibility();
                }
                fireChanged();
            }
        });
        return row;
    }

    private Row rowFor(ParameterId id) {
        for (int i = 0; i < rows.size(); i++) {
            Row row = rows.get(i);
            if (row.id == id) {
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
        label.setForeground(new Color(78, 93, 101));
        return label;
    }

    private static ParameterSections sectionsFor(ParameterSweep.Method method) {
        List<ParameterId> primary = new ArrayList<ParameterId>();
        List<ParameterId> advanced = new ArrayList<ParameterId>();
        if (method == ParameterSweep.Method.STARDIST) {
            primary.add(ParameterId.PROB_THRESH);
            primary.add(ParameterId.NMS_THRESH);
            advanced.add(ParameterId.LINKING_MAX);
            advanced.add(ParameterId.GAP_CLOSING_MAX);
            advanced.add(ParameterId.FRAME_GAP);
            advanced.add(ParameterId.AREA_MIN);
            advanced.add(ParameterId.AREA_MAX);
            advanced.add(ParameterId.QUALITY_MIN);
            advanced.add(ParameterId.INTENSITY_MIN);
        } else if (method == ParameterSweep.Method.CELLPOSE) {
            primary.add(ParameterId.DIAMETER);
            primary.add(ParameterId.FLOW_THRESHOLD);
            primary.add(ParameterId.CELLPROB_THRESHOLD);
            advanced.add(ParameterId.MODEL);
        } else {
            primary.add(ParameterId.THRESHOLD);
            primary.add(ParameterId.MIN_SIZE);
            advanced.add(ParameterId.MAX_SIZE);
        }
        return new ParameterSections(primary, advanced);
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
        if (id == ParameterId.MODEL) return CellposeModel.CYTO3.token();
        return Integer.valueOf(0);
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
        if (image == null) {
            return "";
        }
        String raw = safe(image.getTitle()) + ":"
                + image.getWidth() + "x"
                + image.getHeight() + "x"
                + image.getStackSize();
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(bytes.length * 2);
            for (int i = 0; i < bytes.length; i++) {
                out.append(String.format("%02x", Integer.valueOf(bytes[i] & 0xff)));
            }
            return out.toString();
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString(raw.hashCode());
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value;
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

    private boolean isAdvancedRowActive(Row row) {
        if (row == null) {
            return false;
        }
        ParameterValueList values = row.values.currentValueList();
        return row.sweepBox.isSelected()
                || values.size() > 1
                || hasFiniteClassicalMaxCap(row.id, values);
    }

    private boolean hasFiniteClassicalMaxCap(ParameterId id, ParameterValueList values) {
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
        advancedToggle.setText((advancedExpanded ? "Hide " : "Show ")
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
        final List<ParameterId> primary;
        final List<ParameterId> advanced;

        ParameterSections(List<ParameterId> primary, List<ParameterId> advanced) {
            this.primary = primary;
            this.advanced = advanced;
        }
    }

    private static final class Row {
        final ParameterId id;
        final JCheckBox sweepBox;
        final ValueChipPanel values;
        final boolean advanced;

        Row(ParameterId id,
            JCheckBox sweepBox,
            ValueChipPanel values,
            boolean advanced) {
            this.id = id;
            this.sweepBox = sweepBox;
            this.values = values;
            this.advanced = advanced;
        }
    }
}
