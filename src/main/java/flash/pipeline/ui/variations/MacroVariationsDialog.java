package flash.pipeline.ui.variations;

import flash.pipeline.image.FilterMacroEditorModel;
import flash.pipeline.ui.PipelineDialog;

import ij.ImagePlus;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JScrollPane;
import javax.swing.JSlider;
import javax.swing.JToggleButton;
import javax.swing.ScrollPaneConstants;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Rectangle;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public final class MacroVariationsDialog extends PipelineDialog {

    enum Mode {
        PARAMS,
        STEPS,
        PRESETS
    }

    private final FilterVariationEngineContext context;
    private final Consumer<String> onAccept;
    private final ParameterSweepEditor editor;
    private final VariationGridPanel gridPanel = new VariationGridPanel();
    private final JLabel cellsLabel = new JLabel("Cells: 1");
    private final JLabel zLabel = new JLabel("1/1");
    private final JLabel suggestionLabel = new JLabel("Most stable: pending");
    private final JLabel statusLabel = new JLabel("Idle");
    private final JLabel strategyLabel = new JLabel(" ");
    private final JLabel chainRibbonLabel = new JLabel();
    private final JSlider zSlider = new JSlider(1, 1, 1);
    private final JRadioButton fullCrop = new JRadioButton("full image");
    private final JRadioButton centreCrop = new JRadioButton("centered 256 x 256");
    private final JRadioButton customCrop = new JRadioButton("custom...");
    private final JToggleButton paramsButton = new JToggleButton("Params");
    private final JToggleButton stepsButton = new JToggleButton("Steps");
    private final JToggleButton presetsButton = new JToggleButton("Presets");

    private JButton openLargeMontageButton;
    private JButton useComboButton;
    private Mode mode = Mode.PARAMS;
    private CropSpec currentCropSpec;
    private boolean suppressCropEvents;

    public MacroVariationsDialog(Window owner,
                                 FilterVariationEngineContext context,
                                 Consumer<String> onAccept) {
        super(owner, "Macro Variations");
        if (context == null) {
            throw new IllegalArgumentException("context must not be null");
        }
        this.context = context;
        this.onAccept = onAccept;
        this.editor = ParameterSweepEditor.forFilter(context);
        this.currentCropSpec = context.initialCropSpec();
        setDefaultButtonsVisible(false);
        buildUi();
        refreshCellEstimate();
    }

    public void dispose() {
        Window window = getWindow();
        if (window != null) {
            window.dispose();
        }
    }

    void setMode(Mode mode) {
        if (mode != Mode.PARAMS) {
            paramsButton.setSelected(true);
            return;
        }
        this.mode = Mode.PARAMS;
        paramsButton.setSelected(true);
    }

    Mode modeForTest() {
        return mode;
    }

    ParameterSweepEditor editorForTest() {
        return editor;
    }

    VariationGridPanel gridPanelForTest() {
        return gridPanel;
    }

    JToggleButton paramsButtonForTest() {
        return paramsButton;
    }

    JToggleButton stepsButtonForTest() {
        return stepsButton;
    }

    JToggleButton presetsButtonForTest() {
        return presetsButton;
    }

    JButton openLargeMontageButtonForTest() {
        return openLargeMontageButton;
    }

    JButton useComboButtonForTest() {
        return useComboButton;
    }

    JLabel chainRibbonLabelForTest() {
        return chainRibbonLabel;
    }

    private void buildUi() {
        addComponent(headerPanel());
        addComponent(modeToggleRow());
        addComponent(chainRibbonRow());
        addHeader("Parameters to sweep");
        addComponent(editor);
        addComponent(cropRow());
        addHeader("Preview grid");
        addComponent(gridScrollPane());
        addComponent(zRow());
        addComponent(statusPanel());

        openLargeMontageButton = addFooterButton("Open large montage");
        openLargeMontageButton.setEnabled(false);
        JButton close = addRightFooterButton("Close");
        close.addActionListener(new ActionListener() {
            @Override public void actionPerformed(ActionEvent e) {
                dispose();
            }
        });
        useComboButton = addRightFooterButton("Use this combo");
        useComboButton.setEnabled(false);
        useComboButton.addActionListener(new ActionListener() {
            @Override public void actionPerformed(ActionEvent e) {
                if (onAccept != null) {
                    onAccept.accept(context.baseMacro().render());
                }
                dispose();
            }
        });

        editor.addChangeListener(new ChangeListener() {
            @Override public void stateChanged(ChangeEvent e) {
                refreshCellEstimate();
            }
        });
    }

    private JPanel headerPanel() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.X_AXIS));
        panel.setOpaque(false);
        panel.setBorder(BorderFactory.createEmptyBorder(0, 4, 6, 4));

        JLabel method = new JLabel("Method: Filter");
        method.setFont(method.getFont().deriveFont(java.awt.Font.BOLD, 12f));
        panel.add(method);
        panel.add(Box.createHorizontalStrut(24));
        panel.add(new JLabel("Channel: " + safe(context.channelName())));
        panel.add(Box.createHorizontalGlue());
        panel.add(cellsLabel);
        return panel;
    }

    private JPanel modeToggleRow() {
        JPanel row = new JPanel();
        row.setOpaque(false);
        row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
        row.setBorder(BorderFactory.createEmptyBorder(0, 4, 2, 4));

        ButtonGroup group = new ButtonGroup();
        group.add(paramsButton);
        group.add(stepsButton);
        group.add(presetsButton);
        paramsButton.setSelected(true);
        stepsButton.setEnabled(false);
        presetsButton.setEnabled(false);
        stepsButton.setToolTipText("Coming soon");
        presetsButton.setToolTipText("Coming soon");

        paramsButton.addActionListener(new ActionListener() {
            @Override public void actionPerformed(ActionEvent e) {
                setMode(Mode.PARAMS);
            }
        });
        stepsButton.addActionListener(new ActionListener() {
            @Override public void actionPerformed(ActionEvent e) {
                setMode(Mode.STEPS);
            }
        });
        presetsButton.addActionListener(new ActionListener() {
            @Override public void actionPerformed(ActionEvent e) {
                setMode(Mode.PRESETS);
            }
        });

        row.add(new JLabel("Mode: "));
        row.add(paramsButton);
        row.add(Box.createHorizontalStrut(6));
        row.add(stepsButton);
        row.add(Box.createHorizontalStrut(6));
        row.add(presetsButton);
        row.add(Box.createHorizontalGlue());
        return row;
    }

    private JPanel chainRibbonRow() {
        JPanel row = new JPanel(new BorderLayout());
        row.setOpaque(false);
        row.setBorder(BorderFactory.createEmptyBorder(2, 4, 8, 4));
        chainRibbonLabel.setText("Chain: " + chainSummary());
        chainRibbonLabel.setForeground(new Color(78, 93, 101));
        row.add(chainRibbonLabel, BorderLayout.CENTER);
        return row;
    }

    private JPanel cropRow() {
        JPanel row = new JPanel();
        row.setOpaque(false);
        row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
        row.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        ButtonGroup group = new ButtonGroup();
        group.add(fullCrop);
        group.add(centreCrop);
        group.add(customCrop);
        selectCropButton(currentCropSpec);
        fullCrop.setOpaque(false);
        centreCrop.setOpaque(false);
        customCrop.setOpaque(false);

        row.add(new JLabel("Crop: "));
        row.add(fullCrop);
        row.add(Box.createHorizontalStrut(10));
        row.add(centreCrop);
        row.add(Box.createHorizontalStrut(10));
        row.add(customCrop);
        row.add(Box.createHorizontalGlue());

        ActionListener listener = new ActionListener() {
            @Override public void actionPerformed(ActionEvent e) {
                handleCropSelection((JRadioButton) e.getSource());
            }
        };
        fullCrop.addActionListener(listener);
        centreCrop.addActionListener(listener);
        customCrop.addActionListener(listener);
        editor.setCropSpec(currentCropSpec);
        return row;
    }

    private JScrollPane gridScrollPane() {
        JScrollPane scrollPane = new JScrollPane(gridPanel);
        scrollPane.setBorder(BorderFactory.createLineBorder(new Color(0x3A, 0x40, 0x46)));
        scrollPane.setPreferredSize(new Dimension(780, 440));
        scrollPane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scrollPane.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED);
        scrollPane.getViewport().setBackground(new Color(0x1E, 0x20, 0x24));
        return scrollPane;
    }

    private JPanel zRow() {
        JPanel row = new JPanel(new BorderLayout(8, 0));
        row.setOpaque(false);
        row.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        row.add(new JLabel("z:"), BorderLayout.WEST);
        configureZSlider();
        row.add(zSlider, BorderLayout.CENTER);
        row.add(zLabel, BorderLayout.EAST);
        return row;
    }

    private JPanel statusPanel() {
        JPanel panel = new JPanel();
        panel.setOpaque(false);
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));
        statusLabel.setAlignmentX(JPanel.LEFT_ALIGNMENT);
        suggestionLabel.setAlignmentX(JPanel.LEFT_ALIGNMENT);
        strategyLabel.setAlignmentX(JPanel.LEFT_ALIGNMENT);
        panel.add(statusLabel);
        panel.add(suggestionLabel);
        panel.add(strategyLabel);
        return panel;
    }

    private void configureZSlider() {
        int slices = sourceSliceCount();
        zSlider.setMinimum(1);
        zSlider.setMaximum(Math.max(1, slices));
        zSlider.setValue(1);
        zSlider.setEnabled(slices > 1);
        zLabel.setText("1/" + Math.max(1, slices));
        zSlider.addChangeListener(new ChangeListener() {
            @Override public void stateChanged(ChangeEvent e) {
                int z = zSlider.getValue();
                zLabel.setText(z + "/" + Math.max(1, zSlider.getMaximum()));
                gridPanel.broadcastZ(z);
            }
        });
    }

    private void refreshCellEstimate() {
        try {
            ParameterSweep sweep = editor.currentSweep();
            cellsLabel.setText("Cells: " + sweep.cellCount());
            statusLabel.setText(sweep.cellCount() + " cells, crop "
                    + cropSummary(sweep.cropSpec()));
            strategyLabel.setText(" ");
            gridPanel.setSweep(sweep);
        } catch (RuntimeException e) {
            cellsLabel.setText("Cells: ?");
            statusLabel.setText("Choose at least one value for each selected parameter.");
            strategyLabel.setText(" ");
        }
    }

    private String chainSummary() {
        List<String> names = new ArrayList<String>();
        List<FilterMacroEditorModel.Section> sections = context.baseMacro().getSections();
        for (int i = 0; i < sections.size(); i++) {
            FilterMacroEditorModel.Section section = sections.get(i);
            if (section.entries.isEmpty()) {
                names.add(section.headerText());
                continue;
            }
            for (int j = 0; j < section.entries.size(); j++) {
                String label = section.entries.get(j).label;
                if (label != null && label.trim().length() > 0) {
                    names.add(label.trim());
                }
            }
        }
        if (names.isEmpty()) {
            return "none";
        }
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < names.size(); i++) {
            if (i > 0) {
                out.append(", ");
            }
            out.append(names.get(i));
        }
        return out.toString();
    }

    private String cropSummary(CropSpec spec) {
        ImagePlus source = context.sourceImage();
        if (source == null) {
            return "unknown";
        }
        CropSpec active = spec == null ? CropSpec.full() : spec;
        try {
            Rectangle bounds = active.boundsFor(source);
            return bounds.width + "x" + bounds.height + "x" + sourceSliceCount();
        } catch (RuntimeException e) {
            return "invalid";
        }
    }

    private void handleCropSelection(JRadioButton selected) {
        if (suppressCropEvents) {
            return;
        }
        CropSpec previous = currentCropSpec;
        if (selected == fullCrop) {
            currentCropSpec = CropSpec.full();
        } else if (selected == centreCrop) {
            currentCropSpec = CropSpec.centre256();
        } else if (selected == customCrop) {
            ImagePlus source = context.sourceImage();
            Rectangle initial = previous.mode() == CropSpec.Mode.CUSTOM
                    ? previous.bounds()
                    : null;
            Rectangle chosen = CustomCropPicker.choose(getWindow(), source, initial);
            if (chosen == null) {
                currentCropSpec = previous;
                selectCropButton(previous);
                editor.setCropSpec(currentCropSpec);
                refreshCellEstimate();
                return;
            }
            currentCropSpec = CropSpec.custom(chosen);
        }
        editor.setCropSpec(currentCropSpec);
        refreshCellEstimate();
    }

    private void selectCropButton(CropSpec spec) {
        suppressCropEvents = true;
        try {
            CropSpec.Mode mode = spec == null ? CropSpec.Mode.CENTRE_256 : spec.mode();
            fullCrop.setSelected(mode == CropSpec.Mode.FULL);
            centreCrop.setSelected(mode == CropSpec.Mode.CENTRE_256);
            customCrop.setSelected(mode == CropSpec.Mode.CUSTOM);
        } finally {
            suppressCropEvents = false;
        }
    }

    private int sourceSliceCount() {
        ImagePlus source = context.sourceImage();
        if (source == null) {
            return 1;
        }
        int slices = Math.max(1, source.getNSlices());
        if (slices <= 1) {
            slices = Math.max(1, source.getStackSize());
        }
        return slices;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
