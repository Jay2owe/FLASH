package flash.pipeline.ui.variations;

import flash.pipeline.cellpose.CellposeModel;
import flash.pipeline.ui.config.CellposeParameterStage;
import flash.pipeline.ui.config.StarDistParameterStage;

import ij.ImagePlus;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
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

public final class ParameterSweepEditor extends JPanel {

    private final ParameterSweep.Method method;
    private final String channelName;
    private final String sourceImageHash;
    private final List<Row> rows = new ArrayList<Row>();
    private final List<ChangeListener> listeners = new ArrayList<ChangeListener>();
    private CropSpec cropSpec = CropSpec.full();

    public ParameterSweepEditor(VariationEngineContext context) {
        this(context == null ? ParameterSweep.Method.CLASSICAL : context.method(),
                baseComboFor(context),
                context == null ? "" : context.channelName(),
                sourceHash(context == null ? null : context.filteredSource()));
    }

    public ParameterSweepEditor(ParameterSweep.Method method,
                                ParameterCombo baseParameters,
                                String channelName,
                                String sourceImageHash) {
        super();
        this.method = method == null ? ParameterSweep.Method.CLASSICAL : method;
        this.channelName = channelName == null ? "" : channelName;
        this.sourceImageHash = sourceImageHash == null ? "" : sourceImageHash;
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
        Map<ParameterId, ParameterValueList> valueLists = sweep.valueLists();
        for (int i = 0; i < rows.size(); i++) {
            Row row = rows.get(i);
            ParameterValueList values = valueLists.get(row.id);
            if (values != null) {
                row.values.setValues(values.values());
                row.sweepBox.setSelected(values.size() > 1);
            }
        }
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

    private void build(ParameterCombo baseParameters) {
        add(headerRow());
        List<ParameterId> ids = idsFor(method);
        for (int i = 0; i < ids.size(); i++) {
            add(rowPanel(ids.get(i), baseParameters));
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

    private JPanel rowPanel(final ParameterId id, ParameterCombo baseParameters) {
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

        JLabel name = new JLabel(labelFor(id));
        name.setPreferredSize(new Dimension(180, 24));
        row.add(name);

        ParameterValueList initial = new ParameterValueList(
                Collections.singletonList(baseValueFor(id, baseParameters)));
        ValueChipPanel chips = new ValueChipPanel(initial, parserFor(id));
        chips.setAlignmentX(LEFT_ALIGNMENT);
        row.add(chips);
        row.add(Box.createHorizontalGlue());

        final Row rowState = new Row(id, sweepBox, chips);
        rows.add(rowState);
        sweepBox.addActionListener(e -> fireChanged());
        chips.addChangeListener(new ChangeListener() {
            @Override public void stateChanged(ChangeEvent e) {
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

    private static List<ParameterId> idsFor(ParameterSweep.Method method) {
        List<ParameterId> ids = new ArrayList<ParameterId>();
        if (method == ParameterSweep.Method.STARDIST) {
            ids.add(ParameterId.PROB_THRESH);
            ids.add(ParameterId.NMS_THRESH);
            ids.add(ParameterId.LINKING_MAX);
            ids.add(ParameterId.GAP_CLOSING_MAX);
            ids.add(ParameterId.FRAME_GAP);
            ids.add(ParameterId.AREA_MIN);
            ids.add(ParameterId.AREA_MAX);
            ids.add(ParameterId.QUALITY_MIN);
            ids.add(ParameterId.INTENSITY_MIN);
        } else if (method == ParameterSweep.Method.CELLPOSE) {
            ids.add(ParameterId.DIAMETER);
            ids.add(ParameterId.FLOW_THRESHOLD);
            ids.add(ParameterId.CELLPROB_THRESHOLD);
            ids.add(ParameterId.MODEL);
        } else {
            ids.add(ParameterId.THRESHOLD);
            ids.add(ParameterId.MIN_SIZE);
            ids.add(ParameterId.MAX_SIZE);
        }
        return ids;
    }

    private static String labelFor(ParameterId id) {
        if (id == ParameterId.THRESHOLD) return "threshold";
        if (id == ParameterId.MIN_SIZE) return "minimum size";
        if (id == ParameterId.MAX_SIZE) return "maximum size";
        if (id == ParameterId.PROB_THRESH) return "probability threshold";
        if (id == ParameterId.NMS_THRESH) return "nms threshold";
        if (id == ParameterId.LINKING_MAX) return "linking max distance";
        if (id == ParameterId.GAP_CLOSING_MAX) return "gap closing max distance";
        if (id == ParameterId.FRAME_GAP) return "frame gap";
        if (id == ParameterId.AREA_MIN) return "area minimum";
        if (id == ParameterId.AREA_MAX) return "area maximum";
        if (id == ParameterId.QUALITY_MIN) return "quality minimum";
        if (id == ParameterId.INTENSITY_MIN) return "intensity minimum";
        if (id == ParameterId.DIAMETER) return "diameter";
        if (id == ParameterId.FLOW_THRESHOLD) return "flow threshold";
        if (id == ParameterId.CELLPROB_THRESHOLD) return "cellprob threshold";
        if (id == ParameterId.MODEL) return "model";
        return id.name();
    }

    private static ValueChipPanel.ValueParser parserFor(ParameterId id) {
        if (id == ParameterId.MODEL) {
            return ValueChipPanel.stringParser();
        }
        if (id == ParameterId.THRESHOLD
                || id == ParameterId.MIN_SIZE
                || id == ParameterId.MAX_SIZE
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
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            updateInt(digest, image.getWidth());
            updateInt(digest, image.getHeight());
            updateInt(digest, image.getStackSize());
            ij.ImageStack stack = image.getStack();
            int slices = stack == null ? 0 : stack.getSize();
            for (int z = 1; z <= slices; z++) {
                ij.process.ImageProcessor processor = stack.getProcessor(z);
                updatePixels(digest, processor == null ? null : processor.getPixels(),
                        processor == null ? 0 : processor.getPixelCount());
            }
            byte[] bytes = digest.digest();
            StringBuilder out = new StringBuilder(bytes.length * 2);
            for (int i = 0; i < bytes.length; i++) {
                out.append(String.format("%02x", Integer.valueOf(bytes[i] & 0xff)));
            }
            return out.toString();
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString((safe(image.getTitle()) + ":"
                    + image.getWidth() + "x"
                    + image.getHeight() + "x"
                    + image.getStackSize()).hashCode());
        }
    }

    private static void updatePixels(MessageDigest digest, Object pixels, int pixelCount) {
        if (pixels instanceof byte[]) {
            digest.update((byte[]) pixels);
            return;
        }
        if (pixels instanceof short[]) {
            short[] values = (short[]) pixels;
            for (int i = 0; i < values.length; i++) {
                updateInt(digest, values[i] & 0xffff);
            }
            return;
        }
        if (pixels instanceof int[]) {
            int[] values = (int[]) pixels;
            for (int i = 0; i < values.length; i++) {
                updateInt(digest, values[i]);
            }
            return;
        }
        if (pixels instanceof float[]) {
            float[] values = (float[]) pixels;
            for (int i = 0; i < values.length; i++) {
                updateInt(digest, Float.floatToIntBits(values[i]));
            }
            return;
        }
        if (pixels instanceof double[]) {
            double[] values = (double[]) pixels;
            for (int i = 0; i < values.length; i++) {
                updateLong(digest, Double.doubleToLongBits(values[i]));
            }
            return;
        }
        updateInt(digest, pixelCount);
    }

    private static void updateInt(MessageDigest digest, int value) {
        digest.update((byte) ((value >>> 24) & 0xff));
        digest.update((byte) ((value >>> 16) & 0xff));
        digest.update((byte) ((value >>> 8) & 0xff));
        digest.update((byte) (value & 0xff));
    }

    private static void updateLong(MessageDigest digest, long value) {
        updateInt(digest, (int) (value >>> 32));
        updateInt(digest, (int) value);
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static final class Row {
        final ParameterId id;
        final JCheckBox sweepBox;
        final ValueChipPanel values;

        Row(ParameterId id, JCheckBox sweepBox, ValueChipPanel values) {
            this.id = id;
            this.sweepBox = sweepBox;
            this.values = values;
        }
    }
}
