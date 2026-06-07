package flash.pipeline.representative;

import flash.pipeline.presentation.PresentationTileConfig;
import flash.pipeline.ui.PipelineDialog;
import flash.pipeline.ui.ToggleSwitch;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/**
 * Step-5 chooser for representative condition layout and tile annotations.
 */
public final class ConditionLayoutChooser {

    public Result show(RepresentativeFigureConfig config) {
        if (config == null) {
            throw new IllegalArgumentException("Representative figure config is required.");
        }
        return show(config.selection, config.layout, config.tileConfig);
    }

    public Result show(RepresentativeSelection selection,
                       RepresentativeLayout rememberedLayout,
                       PresentationTileConfig rememberedTileConfig) {
        if (selection == null || !selection.isComplete()) {
            throw new IllegalStateException("Representative selection is not complete.");
        }
        if (GraphicsEnvironment.isHeadless()) {
            return null;
        }

        List<String> conditions = selection.conditionNames();
        List<String> defaultOrder = defaultTileOrder(selection);
        RepresentativeLayout initialLayout = initialLayout(conditions, rememberedLayout);
        PresentationTileConfig initialTile =
                rememberedTileConfig == null
                        ? defaultTileConfig(defaultOrder)
                        : rememberedTileConfig;

        PipelineDialog dialog = new PipelineDialog(
                "Representative Image Figure - Layout", PipelineDialog.Phase.EXPORT);
        dialog.addHeader("Condition Layout");
        LayoutAssignmentPanel layoutPanel =
                new LayoutAssignmentPanel(conditions, initialLayout);
        dialog.addComponent(layoutPanel.panel);

        dialog.addHeader("Tile and Annotations");
        TileOptionsPanel tileOptions =
                new TileOptionsPanel(defaultOrder, initialTile);
        dialog.addComponent(tileOptions.panel);

        dialog.setPrimaryButtonText("Continue");
        if (!dialog.showDialog()) {
            return null;
        }
        return new Result(layoutPanel.createLayout(), tileOptions.buildConfig());
    }

    static RepresentativeLayout initialLayout(List<String> conditions,
                                              RepresentativeLayout rememberedLayout) {
        if (rememberedLayout != null && rememberedLayout.containsExactlyConditions(conditions)) {
            return rememberedLayout;
        }
        return RepresentativeLayout.allInOneRow(conditions);
    }

    static PresentationTileConfig defaultTileConfig(List<String> channelOrder) {
        return PresentationTileConfig.builder()
                .createOverviewTile(true)
                .annotateOverviewTile(true)
                .annotateIndividualImages(false)
                .groupRowsBy(PresentationTileConfig.GroupRowsBy.CONDITION)
                .channelOrder(channelOrder)
                .build();
    }

    static List<String> defaultTileOrder(RepresentativeSelection selection) {
        LinkedHashSet<String> order = new LinkedHashSet<String>();
        if (selection != null) {
            List<RepresentativeSeries> series = selection.series();
            for (int s = 0; s < series.size(); s++) {
                RepresentativeSeries representative = series.get(s);
                if (representative == null) continue;
                List<RepresentativeSeries.ChannelThumbnail> thumbnails =
                        representative.channelThumbnails();
                for (int i = 0; i < thumbnails.size(); i++) {
                    RepresentativeSeries.ChannelThumbnail thumbnail = thumbnails.get(i);
                    if (thumbnail == null) continue;
                    String label = clean(thumbnail.channelName());
                    if (label.isEmpty()) {
                        label = "C" + (Math.max(0, thumbnail.channelIndex()) + 1);
                    }
                    order.add(label);
                }
            }
        }
        order.add("Merge");
        return Collections.unmodifiableList(new ArrayList<String>(order));
    }

    /**
     * User-confirmed representative layout and tile annotation settings.
     */
    public static final class Result {
        private final RepresentativeLayout layout;
        private final PresentationTileConfig tileConfig;

        Result(RepresentativeLayout layout, PresentationTileConfig tileConfig) {
            if (layout == null) {
                throw new IllegalArgumentException("Representative layout is required.");
            }
            if (tileConfig == null) {
                throw new IllegalArgumentException("Presentation tile config is required.");
            }
            this.layout = layout;
            this.tileConfig = tileConfig;
        }

        public RepresentativeLayout layout() {
            return layout;
        }

        public PresentationTileConfig tileConfig() {
            return tileConfig;
        }
    }

    static final class LayoutAssignmentPanel {
        final JPanel panel;
        private final DefaultListModel<ConditionRow> model =
                new DefaultListModel<ConditionRow>();
        private final JList<ConditionRow> list = new JList<ConditionRow>(model);

        LayoutAssignmentPanel(List<String> conditionNames, RepresentativeLayout layout) {
            panel = new JPanel(new BorderLayout(8, 4));
            panel.setOpaque(false);
            panel.setBorder(BorderFactory.createEmptyBorder(2, 16, 8, 4));

            applyLayout(conditionNames, layout);
            list.setVisibleRowCount(Math.min(8, Math.max(3, model.getSize())));
            list.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
            list.setCellRenderer(new ConditionRowRenderer());
            if (model.getSize() > 0) {
                list.setSelectedIndex(0);
            }

            JScrollPane scroll = new JScrollPane(list);
            scroll.setPreferredSize(new Dimension(360, 128));
            panel.add(scroll, BorderLayout.CENTER);
            panel.add(buttonPanel(), BorderLayout.EAST);
        }

        RepresentativeLayout createLayout() {
            List<String> ordered = new ArrayList<String>();
            List<Integer> rows = new ArrayList<Integer>();
            for (int i = 0; i < model.getSize(); i++) {
                ConditionRow row = model.getElementAt(i);
                ordered.add(row.condition);
                rows.add(Integer.valueOf(row.rowNumber));
            }
            return RepresentativeLayout.fromRowAssignments(ordered, rows);
        }

        private JPanel buttonPanel() {
            JPanel buttons = new JPanel();
            buttons.setOpaque(false);
            buttons.setLayout(new BoxLayout(buttons, BoxLayout.Y_AXIS));

            JButton up = compactButton("Move up");
            JButton down = compactButton("Move down");
            JButton rowUp = compactButton("Row +");
            JButton rowDown = compactButton("Row -");
            JButton allColumns = compactButton("All columns");
            JButton allRows = compactButton("All rows");

            buttons.add(up);
            buttons.add(Box.createVerticalStrut(4));
            buttons.add(down);
            buttons.add(Box.createVerticalStrut(10));
            buttons.add(rowUp);
            buttons.add(Box.createVerticalStrut(4));
            buttons.add(rowDown);
            buttons.add(Box.createVerticalStrut(10));
            buttons.add(allColumns);
            buttons.add(Box.createVerticalStrut(4));
            buttons.add(allRows);

            up.addActionListener(new java.awt.event.ActionListener() {
                @Override public void actionPerformed(java.awt.event.ActionEvent e) {
                    moveSelected(-1);
                }
            });
            down.addActionListener(new java.awt.event.ActionListener() {
                @Override public void actionPerformed(java.awt.event.ActionEvent e) {
                    moveSelected(1);
                }
            });
            rowUp.addActionListener(new java.awt.event.ActionListener() {
                @Override public void actionPerformed(java.awt.event.ActionEvent e) {
                    adjustSelectedRow(1);
                }
            });
            rowDown.addActionListener(new java.awt.event.ActionListener() {
                @Override public void actionPerformed(java.awt.event.ActionEvent e) {
                    adjustSelectedRow(-1);
                }
            });
            allColumns.addActionListener(new java.awt.event.ActionListener() {
                @Override public void actionPerformed(java.awt.event.ActionEvent e) {
                    setAllInOneRow();
                }
            });
            allRows.addActionListener(new java.awt.event.ActionListener() {
                @Override public void actionPerformed(java.awt.event.ActionEvent e) {
                    setOneConditionPerRow();
                }
            });
            return buttons;
        }

        private void applyLayout(List<String> conditionNames, RepresentativeLayout layout) {
            List<String> names = normalizeNames(conditionNames);
            RepresentativeLayout safeLayout =
                    layout != null && layout.containsExactlyConditions(names)
                            ? layout
                            : RepresentativeLayout.allInOneRow(names);
            model.clear();
            List<List<String>> rows = safeLayout.rows();
            for (int r = 0; r < rows.size(); r++) {
                List<String> row = rows.get(r);
                for (int c = 0; c < row.size(); c++) {
                    model.addElement(new ConditionRow(row.get(c), r + 1));
                }
            }
        }

        private void moveSelected(int delta) {
            int[] selected = list.getSelectedIndices();
            if (selected.length == 0 || delta == 0) return;
            List<ConditionRow> selectedRows = selectedRows(selected);
            if (delta < 0) {
                moveSelectedUp(selected);
            } else {
                moveSelectedDown(selected);
            }
            restoreSelection(selectedRows);
        }

        private void adjustSelectedRow(int delta) {
            int[] selected = list.getSelectedIndices();
            if (selected.length == 0) return;
            for (int i = 0; i < selected.length; i++) {
                int index = selected[i];
                if (index < 0 || index >= model.getSize()) continue;
                ConditionRow current = model.getElementAt(index);
                int rowNumber = Math.max(1, current.rowNumber + delta);
                model.setElementAt(new ConditionRow(current.condition, rowNumber), index);
            }
            list.setSelectedIndices(selected);
            list.repaint();
        }

        private void moveSelectedUp(int[] selected) {
            int intervalStart = -1;
            int previous = -2;
            for (int i = 0; i <= selected.length; i++) {
                int current = i < selected.length ? selected[i] : -1;
                boolean continueInterval = i < selected.length && current == previous + 1;
                if (intervalStart >= 0 && !continueInterval) {
                    moveIntervalUp(intervalStart, previous);
                }
                if (i < selected.length && !continueInterval) {
                    intervalStart = current;
                }
                previous = current;
            }
        }

        private void moveSelectedDown(int[] selected) {
            int intervalEnd = -1;
            int previous = -2;
            for (int i = selected.length - 1; i >= -1; i--) {
                int current = i >= 0 ? selected[i] : -1;
                boolean continueInterval = i >= 0 && current == previous - 1;
                if (intervalEnd >= 0 && !continueInterval) {
                    moveIntervalDown(previous, intervalEnd);
                }
                if (i >= 0 && !continueInterval) {
                    intervalEnd = current;
                }
                previous = current;
            }
        }

        private void moveIntervalUp(int start, int end) {
            if (start <= 0 || start > end || end >= model.getSize()) return;
            ConditionRow displaced = model.getElementAt(start - 1);
            model.removeElementAt(start - 1);
            model.add(end, displaced);
        }

        private void moveIntervalDown(int start, int end) {
            if (start < 0 || start > end || end >= model.getSize() - 1) return;
            ConditionRow displaced = model.getElementAt(end + 1);
            model.removeElementAt(end + 1);
            model.add(start, displaced);
        }

        private List<ConditionRow> selectedRows(int[] selected) {
            List<ConditionRow> rows = new ArrayList<ConditionRow>();
            for (int i = 0; i < selected.length; i++) {
                int index = selected[i];
                if (index >= 0 && index < model.getSize()) {
                    rows.add(model.getElementAt(index));
                }
            }
            return rows;
        }

        private void restoreSelection(List<ConditionRow> selectedRows) {
            list.clearSelection();
            for (int i = 0; i < model.getSize(); i++) {
                ConditionRow row = model.getElementAt(i);
                for (int s = 0; s < selectedRows.size(); s++) {
                    if (row == selectedRows.get(s)) {
                        list.addSelectionInterval(i, i);
                        break;
                    }
                }
            }
        }

        private void setAllInOneRow() {
            for (int i = 0; i < model.getSize(); i++) {
                ConditionRow current = model.getElementAt(i);
                model.setElementAt(new ConditionRow(current.condition, 1), i);
            }
            list.repaint();
        }

        private void setOneConditionPerRow() {
            for (int i = 0; i < model.getSize(); i++) {
                ConditionRow current = model.getElementAt(i);
                model.setElementAt(new ConditionRow(current.condition, i + 1), i);
            }
            list.repaint();
        }

        int selectionModeForTest() {
            return list.getSelectionMode();
        }

        void selectRangeForTest(int start, int end) {
            list.setSelectionInterval(start, end);
        }

        void selectIndicesForTest(int... indices) {
            list.clearSelection();
            if (indices == null) return;
            for (int i = 0; i < indices.length; i++) {
                int index = indices[i];
                if (index >= 0 && index < model.getSize()) {
                    list.addSelectionInterval(index, index);
                }
            }
        }

        void moveSelectedForTest(int delta) {
            moveSelected(delta);
        }

        void adjustSelectedRowForTest(int delta) {
            adjustSelectedRow(delta);
        }
    }

    static final class TileOptionsPanel {
        final JPanel panel;
        private final JComboBox<String> tileGroupBox;
        private final TileOrderPanel tileOrderPanel;
        private final JTextField tileCellSizeField;
        private final ToggleSwitch annotateOverviewToggle;
        private final ToggleSwitch annotateIndividualToggle;
        private final ToggleSwitch scaleBarToggle;
        private final JTextField scaleBarLengthField;
        private final JTextField scaleBarThicknessField;
        private final JComboBox<String> scaleBarPositionBox;
        private final JComboBox<String> annotationColorBox;
        private final JComboBox<String> labelModeBox;
        private final JTextField customLabelField;
        private final JTextField labelFontSizeField;
        private final JComboBox<String> labelPositionBox;

        TileOptionsPanel(List<String> defaultOrder, PresentationTileConfig initialConfig) {
            PresentationTileConfig initial =
                    initialConfig == null ? defaultTileConfig(defaultOrder) : initialConfig;
            List<String> initialOrder = mergeChannelOrder(defaultOrder, initial.channelOrder());

            panel = new JPanel();
            panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
            panel.setOpaque(false);
            panel.setBorder(BorderFactory.createEmptyBorder(0, 16, 6, 4));

            tileGroupBox = new JComboBox<String>(new String[]{"Condition", "Animal"});
            tileGroupBox.setSelectedItem(groupLabel(initial.groupRowsBy()));
            tileOrderPanel = new TileOrderPanel(initialOrder);
            tileCellSizeField = compactField(String.valueOf(initial.cellSizePx()), 5);
            annotateOverviewToggle = new ToggleSwitch(initial.annotateOverviewTile());
            annotateIndividualToggle = new ToggleSwitch(initial.annotateIndividualImages());
            scaleBarToggle = new ToggleSwitch(initial.scaleBarEnabled());
            scaleBarLengthField = compactField(formatNumber(initial.scaleBarLengthUm()), 5);
            scaleBarThicknessField = compactField(String.valueOf(initial.scaleBarThicknessPx()), 4);
            scaleBarPositionBox = new JComboBox<String>(positionChoices());
            scaleBarPositionBox.setSelectedItem(positionLabel(initial.scaleBarPosition()));
            annotationColorBox = new JComboBox<String>(new String[]{"White", "Black"});
            annotationColorBox.setSelectedItem(Color.BLACK.equals(initial.annotationColor())
                    ? "Black" : "White");
            labelModeBox = new JComboBox<String>(new String[]{"None", "Stain name",
                    "Image name", "Condition + image", "Custom text"});
            labelModeBox.setSelectedItem(labelModeLabel(initial.labelMode()));
            customLabelField = compactField(initial.customLabelTemplate(), 10);
            labelFontSizeField = compactField(String.valueOf(initial.labelFontSizePx()), 4);
            labelPositionBox = new JComboBox<String>(positionChoices());
            labelPositionBox.setSelectedItem(positionLabel(initial.labelPosition()));

            panel.add(compactRow(
                    labelled("Rows by", tileGroupBox),
                    labelled("Cell px", tileCellSizeField)));
            panel.add(compactRow(
                    labelledToggle("Annotate tile", annotateOverviewToggle),
                    labelledToggle("Annotated copies", annotateIndividualToggle),
                    labelledToggle("Scale bar", scaleBarToggle)));
            panel.add(compactRow(
                    labelled("Bar um", scaleBarLengthField),
                    labelled("Bar px", scaleBarThicknessField),
                    labelled("Bar position", scaleBarPositionBox),
                    labelled("Colour", annotationColorBox)));
            panel.add(compactRow(
                    labelled("Label", labelModeBox),
                    labelled("Custom", customLabelField),
                    labelled("Font px", labelFontSizeField),
                    labelled("Label position", labelPositionBox)));
            panel.add(tileOrderPanel.panel);

            annotateIndividualToggle.addChangeListener(new Runnable() {
                @Override public void run() {
                    updateForcedAnnotationState();
                }
            });
            updateForcedAnnotationState();
        }

        PresentationTileConfig buildConfig() {
            boolean annotateIndividual = annotateIndividualToggle.isSelected();
            boolean annotateOverview = annotateOverviewToggle.isSelected() || annotateIndividual;
            return PresentationTileConfig.builder()
                    .createOverviewTile(true)
                    .annotateOverviewTile(annotateOverview)
                    .annotateIndividualImages(annotateIndividual)
                    .groupRowsBy("Animal".equals(selectedText(tileGroupBox))
                            ? PresentationTileConfig.GroupRowsBy.ANIMAL
                            : PresentationTileConfig.GroupRowsBy.CONDITION)
                    .channelOrder(tileOrderPanel.orderedItems())
                    .cellSizePx(parseInt(tileCellSizeField, 260))
                    .scaleBarEnabled(scaleBarToggle.isSelected())
                    .scaleBarLengthUm(parseDouble(scaleBarLengthField, 100.0))
                    .scaleBarThicknessPx(parseInt(scaleBarThicknessField, 6))
                    .scaleBarPosition(parsePosition(selectedText(scaleBarPositionBox)))
                    .annotationColor("Black".equals(selectedText(annotationColorBox))
                            ? Color.BLACK : Color.WHITE)
                    .labelMode(parseLabelMode(selectedText(labelModeBox)))
                    .customLabelTemplate(textValue(customLabelField, "{stain}"))
                    .labelFontSizePx(parseInt(labelFontSizeField, 18))
                    .labelPosition(parsePosition(selectedText(labelPositionBox)))
                    .build();
        }

        private void updateForcedAnnotationState() {
            boolean forceTileAnnotations = annotateIndividualToggle.isSelected();
            if (forceTileAnnotations) {
                annotateOverviewToggle.setSelected(true);
            }
            annotateOverviewToggle.setEnabled(!forceTileAnnotations);
        }
    }

    static final class TileOrderPanel {
        final JPanel panel;
        private final DefaultListModel<String> model = new DefaultListModel<String>();
        private final JList<String> list = new JList<String>(model);

        TileOrderPanel(List<String> items) {
            panel = new JPanel(new BorderLayout(8, 4));
            panel.setOpaque(false);
            panel.setBorder(BorderFactory.createEmptyBorder(2, 4, 8, 4));

            JLabel label = new JLabel("Tile column order");
            label.setFont(label.getFont().deriveFont(Font.BOLD, 12f));
            panel.add(label, BorderLayout.NORTH);

            if (items != null) {
                for (String item : items) {
                    String clean = clean(item);
                    if (!clean.isEmpty()) model.addElement(clean);
                }
            }
            list.setVisibleRowCount(Math.min(6, Math.max(3, model.size())));
            list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
            if (model.size() > 0) list.setSelectedIndex(0);
            JScrollPane scroll = new JScrollPane(list);
            scroll.setPreferredSize(new Dimension(240, 96));
            panel.add(scroll, BorderLayout.CENTER);

            JPanel buttons = new JPanel();
            buttons.setOpaque(false);
            buttons.setLayout(new BoxLayout(buttons, BoxLayout.Y_AXIS));
            JButton up = compactButton("Up");
            JButton down = compactButton("Down");
            buttons.add(up);
            buttons.add(Box.createVerticalStrut(4));
            buttons.add(down);
            panel.add(buttons, BorderLayout.EAST);

            up.addActionListener(new java.awt.event.ActionListener() {
                @Override public void actionPerformed(java.awt.event.ActionEvent e) {
                    moveSelected(-1);
                }
            });
            down.addActionListener(new java.awt.event.ActionListener() {
                @Override public void actionPerformed(java.awt.event.ActionEvent e) {
                    moveSelected(1);
                }
            });
        }

        List<String> orderedItems() {
            List<String> out = new ArrayList<String>();
            for (int i = 0; i < model.getSize(); i++) {
                out.add(model.getElementAt(i));
            }
            return out;
        }

        private void moveSelected(int delta) {
            int index = list.getSelectedIndex();
            int next = index + delta;
            if (index < 0 || next < 0 || next >= model.getSize()) return;
            String value = model.getElementAt(index);
            model.removeElementAt(index);
            model.add(next, value);
            list.setSelectedIndex(next);
        }
    }

    private static final class ConditionRow {
        final String condition;
        final int rowNumber;

        ConditionRow(String condition, int rowNumber) {
            this.condition = clean(condition);
            this.rowNumber = Math.max(1, rowNumber);
        }
    }

    private static final class ConditionRowRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> list,
                                                      Object value,
                                                      int index,
                                                      boolean isSelected,
                                                      boolean cellHasFocus) {
            JLabel label = (JLabel) super.getListCellRendererComponent(
                    list, value, index, isSelected, cellHasFocus);
            if (value instanceof ConditionRow) {
                ConditionRow row = (ConditionRow) value;
                label.setText("Row " + row.rowNumber + "    " + row.condition);
            }
            return label;
        }
    }

    private static List<String> normalizeNames(List<String> conditionNames) {
        List<String> out = new ArrayList<String>();
        if (conditionNames != null) {
            for (String conditionName : conditionNames) {
                String clean = clean(conditionName);
                if (!clean.isEmpty()) out.add(clean);
            }
        }
        return out;
    }

    private static List<String> mergeChannelOrder(List<String> defaults, List<String> remembered) {
        LinkedHashSet<String> order = new LinkedHashSet<String>();
        if (remembered != null) {
            for (String value : remembered) {
                String clean = clean(value);
                if (!clean.isEmpty()) order.add(clean);
            }
        }
        if (defaults != null) {
            for (String value : defaults) {
                String clean = clean(value);
                if (!clean.isEmpty()) order.add(clean);
            }
        }
        return Collections.unmodifiableList(new ArrayList<String>(order));
    }

    private static JPanel compactRow(Component... components) {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 3));
        row.setOpaque(false);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        if (components != null) {
            for (int i = 0; i < components.length; i++) {
                if (components[i] != null) row.add(components[i]);
            }
        }
        return row;
    }

    private static JPanel labelled(String label, JComponent component) {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        panel.setOpaque(false);
        JLabel text = new JLabel(label);
        text.setFont(text.getFont().deriveFont(Font.PLAIN, 11f));
        panel.add(text);
        panel.add(component);
        return panel;
    }

    private static JPanel labelledToggle(String label, ToggleSwitch toggle) {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        panel.setOpaque(false);
        panel.add(toggle);
        JLabel text = new JLabel(label);
        text.setFont(text.getFont().deriveFont(Font.PLAIN, 11f));
        panel.add(text);
        return panel;
    }

    private static JTextField compactField(String value, int columns) {
        JTextField field = new JTextField(value == null ? "" : value, columns);
        field.setMaximumSize(new Dimension(Math.max(48, columns * 12), 24));
        return field;
    }

    private static JButton compactButton(String label) {
        JButton button = new JButton(label);
        Dimension preferred = button.getPreferredSize();
        button.setMaximumSize(new Dimension(Math.max(96, preferred.width), 26));
        button.setAlignmentX(Component.LEFT_ALIGNMENT);
        return button;
    }

    private static String[] positionChoices() {
        return new String[]{"Top left", "Top right", "Bottom left", "Bottom right"};
    }

    private static String groupLabel(PresentationTileConfig.GroupRowsBy groupRowsBy) {
        return groupRowsBy == PresentationTileConfig.GroupRowsBy.ANIMAL
                ? "Animal" : "Condition";
    }

    private static String positionLabel(PresentationTileConfig.Position position) {
        if (position == PresentationTileConfig.Position.TOP_RIGHT) return "Top right";
        if (position == PresentationTileConfig.Position.BOTTOM_LEFT) return "Bottom left";
        if (position == PresentationTileConfig.Position.BOTTOM_RIGHT) return "Bottom right";
        return "Top left";
    }

    private static String labelModeLabel(PresentationTileConfig.LabelMode mode) {
        if (mode == PresentationTileConfig.LabelMode.NONE) return "None";
        if (mode == PresentationTileConfig.LabelMode.IMAGE_NAME) return "Image name";
        if (mode == PresentationTileConfig.LabelMode.CONDITION_IMAGE) return "Condition + image";
        if (mode == PresentationTileConfig.LabelMode.CUSTOM) return "Custom text";
        return "Stain name";
    }

    private static PresentationTileConfig.Position parsePosition(String value) {
        String text = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if ("top right".equals(text)) return PresentationTileConfig.Position.TOP_RIGHT;
        if ("bottom left".equals(text)) return PresentationTileConfig.Position.BOTTOM_LEFT;
        if ("bottom right".equals(text)) return PresentationTileConfig.Position.BOTTOM_RIGHT;
        return PresentationTileConfig.Position.TOP_LEFT;
    }

    private static PresentationTileConfig.LabelMode parseLabelMode(String value) {
        String text = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if ("none".equals(text)) return PresentationTileConfig.LabelMode.NONE;
        if ("image name".equals(text)) return PresentationTileConfig.LabelMode.IMAGE_NAME;
        if ("condition + image".equals(text)) return PresentationTileConfig.LabelMode.CONDITION_IMAGE;
        if ("custom text".equals(text)) return PresentationTileConfig.LabelMode.CUSTOM;
        return PresentationTileConfig.LabelMode.STAIN_NAME;
    }

    private static String selectedText(JComboBox<String> combo) {
        Object value = combo == null ? null : combo.getSelectedItem();
        return value == null ? "" : value.toString();
    }

    private static String textValue(JTextField field, String fallback) {
        String value = field == null ? null : field.getText();
        if (value == null || value.trim().isEmpty()) return fallback;
        return value.trim();
    }

    private static int parseInt(JTextField field, int fallback) {
        try {
            return Integer.parseInt(textValue(field, String.valueOf(fallback)));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static double parseDouble(JTextField field, double fallback) {
        try {
            return Double.parseDouble(textValue(field, String.valueOf(fallback)));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String formatNumber(double value) {
        if (Math.rint(value) == value) {
            return String.valueOf((int) value);
        }
        return String.valueOf(value);
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
