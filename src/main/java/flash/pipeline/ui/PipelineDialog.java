package flash.pipeline.ui;

import flash.pipeline.execution.AnalysisCancellation;
import flash.pipeline.help.AnalysisHelpCatalog;
import flash.pipeline.help.AnalysisHelpDialog;
import flash.pipeline.help.AnalysisHelpTopic;
import flash.pipeline.help.SetupHelpDialog;
import flash.pipeline.help.SetupHelpTopic;
import ij.IJ;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.awt.SecondaryLoop;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Custom dialog with modern toggle switches instead of plain AWT checkboxes.
 * Provides section headers, labeled toggles, text fields, dropdowns, and help text.
 */
public class PipelineDialog {

    private static final Color BG_COLOR = FlashTheme.SURFACE;
    private static final Color HEADER_COLOR = FlashTheme.TEXT_HEADER;
    private static final Color SUBHEADER_COLOR = FlashTheme.TEXT_SUBHEADER;
    private static final Color LABEL_COLOR = FlashTheme.TEXT_PRIMARY;
    private static final Color HELP_COLOR = FlashTheme.TEXT_MUTED;
    private static final Color PRIMARY_ACTION_BG = FlashTheme.PRIMARY_BG;
    private static final Color PRIMARY_ACTION_FG = FlashTheme.PRIMARY_FG;
    private static final Color PRIMARY_ACTION_BORDER = FlashTheme.PRIMARY_BORDER;
    private static final Color CANCEL_ACTION_BG = FlashTheme.DANGER_BG;
    private static final Color CANCEL_ACTION_FG = FlashTheme.DANGER_FG;
    private static final Color CANCEL_ACTION_BORDER = FlashTheme.DANGER_BORDER;
    private static final int MAX_BODY_VIEWPORT_WIDTH = 860;
    private static final int MAX_DIALOG_WIDTH = 1100;
    private static final int MIN_DIALOG_WIDTH = 320;
    private static final int MIN_DIALOG_HEIGHT = 260;
    private static final int SCROLLBAR_WIDTH_ALLOWANCE = 30;
    private static final double MAX_SCREEN_WIDTH_FRACTION = 0.92;
    private static final double MAX_SCREEN_HEIGHT_FRACTION = 0.80;
    private static final int MAX_COMBO_WIDTH = 280;
    private static final int CHANNEL_NAME_COL_WIDTH = 150;
    private static final int CHANNEL_FILTER_COL_WIDTH = 230;
    private static final int CHANNEL_NUM_COL_WIDTH = 96;
    private static final int HELP_WRAP_WIDTH = 460;
    private static final int DEFAULT_FOOTER_BUTTON_MIN_WIDTH = 80;
    private static final int UTILITY_FOOTER_BUTTON_MIN_WIDTH = 90;
    private static final int FOOTER_BUTTON_HEIGHT = 28;

    public enum Phase {
        SETUP("Setup"),
        ANALYSE("Analyse"),
        EXPORT("Export & Compare");

        public final String label;

        Phase(String label) {
            this.label = label;
        }
    }

    private final JDialog dialog;
    private final JPanel contentPanel;
    private final JPanel northContainer;
    private final JPanel breadcrumbPanel;
    private boolean wasCanceled = true;
    private boolean wasBackPressed = false;
    private boolean backEnabled = false;
    private String actionCommand = "";
    private boolean customLocation = false;
    private Phase currentPhase;
    private String breadcrumbStepText;
    private final List<String> workflowSteps = new ArrayList<String>();
    private int workflowActiveIndex = -1;
    private boolean workflowBreadcrumbEnabled = false;
    private Supplier<Boolean> cancelConfirmation;

    // Ordered lists for retrieval
    private final List<ToggleSwitch> toggles = new ArrayList<ToggleSwitch>();
    private final List<JTextField> textFields = new ArrayList<JTextField>();
    private final List<JComboBox<String>> combos = new ArrayList<JComboBox<String>>();
    private final List<JTextField> numericFields = new ArrayList<JTextField>();
    private final List<JLabel> statusIconLabels = new ArrayList<JLabel>();
    private final List<HelpLabel> helpLabels = new ArrayList<HelpLabel>();

    private final JPanel leftButtonPanel;
    private final JPanel rightButtonPanel;
    private final JPanel buttonBar;
    private final JButton backButton;
    private final JButton okButton;
    private final JButton cancelButton;
    private JLabel statusLabel;
    private JPanel southWrapper;

    private int toggleIndex = 0;
    private int textFieldIndex = 0;
    private int comboIndex = 0;
    private int numericFieldIndex = 0;

    // When non-null, body helpers add rows to this child panel instead of
    // contentPanel. The child panel is collapsed/expanded by an arrow row.
    // Sequential retrieval (toggles, numericFields, etc.) is unaffected —
    // components are still appended to the retrieval lists in order.
    private JPanel currentAdvancedPanel;
    private JPanel currentCollapsiblePanel;
    private String currentModuleId;

    public PipelineDialog(String title) {
        this(title, null, null);
    }

    public PipelineDialog(String title, Phase phase) {
        this(title, phase, null);
    }

    public PipelineDialog(Window owner, String title) {
        this(title, null, owner);
    }

    public PipelineDialog(Window owner, String title, Phase phase) {
        this(title, phase, owner);
    }

    private PipelineDialog(String title, Phase phase, Window owner) {
        dialog = owner == null
                ? new JDialog((Frame) null, title, true)
                : new JDialog(owner, title, Dialog.ModalityType.APPLICATION_MODAL);
        dialog.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        dialog.addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) {
                requestCancelClose();
            }
        });
        java.awt.image.BufferedImage brand = flash.pipeline.ui.FlashIcons.brandImage(32);
        if (brand != null) dialog.setIconImage(brand);

        contentPanel = new BodyPanel();
        contentPanel.setLayout(new BoxLayout(contentPanel, BoxLayout.Y_AXIS));
        contentPanel.setBorder(FlashTheme.pad(15, 20, 10, 20));
        contentPanel.setBackground(BG_COLOR);

        JScrollPane scrollPane = new JScrollPane(contentPanel);
        scrollPane.setBorder(null);
        scrollPane.getViewport().setBackground(BG_COLOR);
        scrollPane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        dialog.getContentPane().setLayout(new BorderLayout());
        northContainer = new JPanel();
        northContainer.setLayout(new BoxLayout(northContainer, BoxLayout.Y_AXIS));
        northContainer.setBackground(BG_COLOR);

        breadcrumbPanel = new JPanel();
        breadcrumbPanel.setBackground(BG_COLOR);
        breadcrumbPanel.setBorder(FlashTheme.pad(4, 12, 2, 12));
        breadcrumbPanel.setPreferredSize(new Dimension(0, 36));
        northContainer.add(breadcrumbPanel);
        dialog.getContentPane().add(northContainer, BorderLayout.NORTH);
        dialog.getContentPane().add(scrollPane, BorderLayout.CENTER);

        // Button panel: utility buttons on the left, primary actions on the right.
        buttonBar = new JPanel(new BorderLayout());
        buttonBar.setBackground(BG_COLOR);

        leftButtonPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 8));
        leftButtonPanel.setBackground(BG_COLOR);
        leftButtonPanel.setVisible(false);

        rightButtonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 8));
        rightButtonPanel.setBackground(BG_COLOR);
        backButton = new JButton("Back");
        okButton = new JButton("OK");
        cancelButton = new JButton("Cancel");
        styleActionButton(okButton, PRIMARY_ACTION_BG, PRIMARY_ACTION_FG, PRIMARY_ACTION_BORDER);
        styleActionButton(cancelButton, CANCEL_ACTION_BG, CANCEL_ACTION_FG, CANCEL_ACTION_BORDER);
        sizeFooterButtonToText(backButton, DEFAULT_FOOTER_BUTTON_MIN_WIDTH);
        sizeFooterButtonToText(okButton, DEFAULT_FOOTER_BUTTON_MIN_WIDTH);
        sizeFooterButtonToText(cancelButton, DEFAULT_FOOTER_BUTTON_MIN_WIDTH);
        backButton.addActionListener(e -> { wasBackPressed = true; wasCanceled = true; dialog.dispose(); });
        okButton.addActionListener(e -> { wasCanceled = false; dialog.dispose(); });
        cancelButton.addActionListener(e -> requestCancelClose());
        dialog.getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(KeyStroke.getKeyStroke("ESCAPE"), "cancelDialog");
        dialog.getRootPane().getActionMap().put("cancelDialog", new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) {
                requestCancelClose();
            }
        });
        backButton.setVisible(false);
        rightButtonPanel.add(backButton);
        rightButtonPanel.add(cancelButton);
        rightButtonPanel.add(okButton);

        buttonBar.add(leftButtonPanel, BorderLayout.WEST);
        buttonBar.add(rightButtonPanel, BorderLayout.EAST);
        dialog.getContentPane().add(buttonBar, BorderLayout.SOUTH);

        setBreadcrumb(phase, null);
    }

    /** Returns the backing Swing window for owned child dialogs. */
    public Window getWindow() {
        return dialog;
    }

    public void setCancelConfirmation(Supplier<Boolean> shouldExitCancelFlow) {
        this.cancelConfirmation = shouldExitCancelFlow;
    }

    /**
     * Sets an optional fixed component below the breadcrumb and above the
     * scroll viewport. Passing null clears the slot without removing the
     * breadcrumb target.
     */
    public void setNorthSlot(JComponent component) {
        while (northContainer.getComponentCount() > 1) {
            northContainer.remove(1);
        }
        if (component != null) {
            constrainNestedComboBoxes(component);
            component.setAlignmentX(Component.LEFT_ALIGNMENT);
            northContainer.add(component);
        }
        northContainer.revalidate();
        northContainer.repaint();
    }

    /** Updates the optional workflow phase breadcrumb shown above the dialog body. */
    public void setBreadcrumb(Phase phase, String stepText) {
        currentPhase = phase;
        breadcrumbStepText = stepText;
        breadcrumbPanel.setVisible(phase != null);
        repaintBreadcrumb();
    }

    /** Shows a compact analysis-local tracker instead of the generic phase breadcrumb. */
    public void setWorkflowTracker(String[] steps, int activeIndex) {
        workflowSteps.clear();
        if (steps != null) {
            for (int i = 0; i < steps.length; i++) {
                String step = steps[i] == null ? "" : steps[i].trim();
                if (!step.isEmpty()) {
                    workflowSteps.add(step);
                }
            }
        }
        workflowBreadcrumbEnabled = !workflowSteps.isEmpty();
        if (!workflowBreadcrumbEnabled) {
            workflowActiveIndex = -1;
        } else if (activeIndex < 0) {
            workflowActiveIndex = -1;
        } else {
            workflowActiveIndex = Math.min(activeIndex, workflowSteps.size() - 1);
        }
        breadcrumbPanel.setVisible(workflowBreadcrumbEnabled || currentPhase != null);
        repaintBreadcrumb();
    }

    /** Clears the analysis-local tracker and returns to the generic phase breadcrumb. */
    public void clearWorkflowTracker() {
        workflowSteps.clear();
        workflowActiveIndex = -1;
        workflowBreadcrumbEnabled = false;
        breadcrumbPanel.setVisible(currentPhase != null);
        repaintBreadcrumb();
    }

    private void repaintBreadcrumb() {
        breadcrumbPanel.removeAll();
        breadcrumbPanel.setLayout(new BoxLayout(breadcrumbPanel, BoxLayout.Y_AXIS));

        if (workflowBreadcrumbEnabled) {
            repaintWorkflowBreadcrumb();
            return;
        }

        if (currentPhase == null) {
            breadcrumbPanel.revalidate();
            breadcrumbPanel.repaint();
            return;
        }

        JPanel chipRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        chipRow.setOpaque(false);
        chipRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        Phase[] phases = Phase.values();
        for (int i = 0; i < phases.length; i++) {
            chipRow.add(makePhaseChip(phases[i]));
            if (i < phases.length - 1) {
                JLabel separator = new JLabel("\u25B8");
                separator.setForeground(HELP_COLOR);
                separator.setFont(separator.getFont().deriveFont(Font.PLAIN, 11f));
                chipRow.add(separator);
            }
        }

        JLabel step = new JLabel(breadcrumbStepText == null || breadcrumbStepText.trim().isEmpty()
                ? " " : breadcrumbStepText);
        step.setForeground(HELP_COLOR);
        step.setFont(step.getFont().deriveFont(Font.PLAIN, 10f));
        step.setAlignmentX(Component.LEFT_ALIGNMENT);

        breadcrumbPanel.add(chipRow);
        breadcrumbPanel.add(step);
        breadcrumbPanel.revalidate();
        breadcrumbPanel.repaint();
    }

    private void repaintWorkflowBreadcrumb() {
        JPanel chipRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        chipRow.setOpaque(false);
        chipRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        for (int i = 0; i < workflowSteps.size(); i++) {
            chipRow.add(makeWorkflowChip(workflowSteps.get(i), i == workflowActiveIndex));
            if (i < workflowSteps.size() - 1) {
                JLabel separator = new JLabel("\u25B8");
                separator.setForeground(HELP_COLOR);
                separator.setFont(separator.getFont().deriveFont(Font.PLAIN, 11f));
                chipRow.add(separator);
            }
        }

        breadcrumbPanel.add(chipRow);
        breadcrumbPanel.revalidate();
        breadcrumbPanel.repaint();
    }

    private JLabel makeWorkflowChip(String text, boolean active) {
        JLabel chip = new JLabel(" " + (text == null ? "" : text.trim()) + " ");
        chip.setOpaque(true);
        chip.setFont(chip.getFont().deriveFont(active ? Font.BOLD : Font.PLAIN, 11f));
        chip.setBorder(BorderFactory.createLineBorder(HEADER_COLOR, 1, true));
        chip.setBackground(active ? HEADER_COLOR : BG_COLOR);
        chip.setForeground(active ? FlashTheme.TEXT_ON_DARK : HEADER_COLOR);
        return chip;
    }

    private JLabel makePhaseChip(Phase phase) {
        JLabel chip = new JLabel(" " + phase.label + " ");
        chip.setOpaque(true);
        chip.setFont(chip.getFont().deriveFont(Font.PLAIN, 11f));
        chip.setBorder(BorderFactory.createLineBorder(HEADER_COLOR, 1, true));
        chip.setBackground(phase == currentPhase ? HEADER_COLOR : BG_COLOR);
        chip.setForeground(phase == currentPhase ? FlashTheme.TEXT_ON_DARK : HEADER_COLOR);
        String iconOp = null;
        if (phase == Phase.SETUP)   iconOp = "settings";
        else if (phase == Phase.ANALYSE) iconOp = "microscope";
        else if (phase == Phase.EXPORT)  iconOp = "file-export";
        if (iconOp != null) {
            javax.swing.Icon icon = flash.pipeline.ui.FlashIcons.phaseChip(iconOp, phase == currentPhase);
            if (icon != null) {
                chip.setIcon(icon);
                chip.setIconTextGap(4);
            }
        }
        return chip;
    }

    /** Adds a bold section header. */
    public void addHeader(String text) {
        addHeader(text, null);
    }

    /** Adds a bold section header with an optional leading icon. */
    public void addHeader(String text, javax.swing.Icon icon) {
        addToBody(Box.createVerticalStrut(10));
        JLabel label = new JLabel(text);
        label.setFont(FlashTheme.h2());
        label.setForeground(HEADER_COLOR);
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        if (icon != null) {
            label.setIcon(icon);
            label.setIconTextGap(8);
        }
        addToBody(label);
        addToBody(Box.createVerticalStrut(4));

        JSeparator sep = new JSeparator(SwingConstants.HORIZONTAL);
        sep.setMaximumSize(new Dimension(Integer.MAX_VALUE, 1));
        sep.setAlignmentX(Component.LEFT_ALIGNMENT);
        addToBody(sep);
        addToBody(Box.createVerticalStrut(6));
    }

    /**
     * Adds a top-level analysis header with the shared question-mark help
     * control. The help button is not registered as an input field, so
     * sequential retrieval order for toggles, choices, numbers, and strings is
     * unchanged.
     */
    public JButton addAnalysisHelpHeader(String text, int analysisIndex) {
        final AnalysisHelpTopic topic = AnalysisHelpCatalog.forAnalysis(analysisIndex);
        String tooltip = topic == null
                ? "Analysis help is not available yet."
                : "About " + topic.title;
        JButton helpButton = HelpButton.question(tooltip);
        helpButton.setEnabled(topic != null);
        if (topic != null) {
            helpButton.addActionListener(e -> AnalysisHelpDialog.show(dialog, topic));
        }

        addToBody(Box.createVerticalStrut(10));

        JPanel row = createRow();
        JLabel label = new JLabel(text);
        label.setFont(FlashTheme.h2());
        label.setForeground(HEADER_COLOR);
        row.add(label);
        row.add(Box.createHorizontalStrut(6));
        row.add(helpButton);
        row.add(Box.createHorizontalGlue());
        addToBody(row);
        addToBody(Box.createVerticalStrut(4));

        JSeparator sep = new JSeparator(SwingConstants.HORIZONTAL);
        sep.setMaximumSize(new Dimension(Integer.MAX_VALUE, 1));
        sep.setAlignmentX(Component.LEFT_ALIGNMENT);
        addToBody(sep);
        addToBody(Box.createVerticalStrut(6));
        return helpButton;
    }

    /**
     * Adds a top-level setup-stage header with a question-mark help control.
     */
    public JButton addSetupHelpHeader(String text, SetupHelpTopic topic) {
        JButton helpButton = createSetupHelpButton(topic);

        addToBody(Box.createVerticalStrut(10));

        JPanel row = createRow();
        JLabel label = new JLabel(text);
        label.setFont(FlashTheme.h2());
        label.setForeground(HEADER_COLOR);
        row.add(label);
        row.add(Box.createHorizontalStrut(6));
        row.add(helpButton);
        row.add(Box.createHorizontalGlue());
        addToBody(row);
        addToBody(Box.createVerticalStrut(4));

        JSeparator sep = new JSeparator(SwingConstants.HORIZONTAL);
        sep.setMaximumSize(new Dimension(Integer.MAX_VALUE, 1));
        sep.setAlignmentX(Component.LEFT_ALIGNMENT);
        addToBody(sep);
        addToBody(Box.createVerticalStrut(6));
        return helpButton;
    }

    /**
     * Adds a section header with a controlling toggle. The returned toggle is not
     * part of the sequential getNextBoolean() list.
     */
    public ToggleSwitch addHeaderToggle(String text, boolean defaultValue) {
        addToBody(Box.createVerticalStrut(10));

        JPanel row = createRow();
        JLabel label = new JLabel(text);
        label.setFont(FlashTheme.h2());
        label.setForeground(HEADER_COLOR);
        row.add(label);
        row.add(Box.createHorizontalStrut(8));
        ToggleSwitch toggle = new ToggleSwitch(defaultValue);
        row.add(toggle);
        row.add(Box.createHorizontalGlue());
        addToBody(row);
        addToBody(Box.createVerticalStrut(4));

        JSeparator sep = new JSeparator(SwingConstants.HORIZONTAL);
        sep.setMaximumSize(new Dimension(Integer.MAX_VALUE, 1));
        sep.setAlignmentX(Component.LEFT_ALIGNMENT);
        addToBody(sep);
        addToBody(Box.createVerticalStrut(6));
        return toggle;
    }

    /**
     * Adds a section header with a leading status icon and controlling toggle.
     * The returned toggle is not part of the sequential getNextBoolean() list.
     */
    public ToggleSwitch addHeaderToggleWithStatus(String text, boolean defaultValue,
                                                  JComponent leadingIcon) {
        addToBody(Box.createVerticalStrut(10));

        JPanel row = createRow();
        JLabel statusLabel = normalizeStatusIcon(leadingIcon);
        row.add(statusLabel);
        row.add(Box.createHorizontalStrut(6));

        JLabel label = new JLabel(text);
        label.setFont(label.getFont().deriveFont(Font.BOLD, 13f));
        label.setForeground(HEADER_COLOR);
        row.add(label);
        row.add(Box.createHorizontalStrut(8));
        ToggleSwitch toggle = new ToggleSwitch(defaultValue);
        row.add(toggle);
        row.add(Box.createHorizontalGlue());
        addToBody(row);
        addToBody(Box.createVerticalStrut(4));

        JSeparator sep = new JSeparator(SwingConstants.HORIZONTAL);
        sep.setMaximumSize(new Dimension(Integer.MAX_VALUE, 1));
        sep.setAlignmentX(Component.LEFT_ALIGNMENT);
        addToBody(sep);
        addToBody(Box.createVerticalStrut(6));
        return toggle;
    }

    /** Adds a smaller subsection label under the current section header. */
    public void addSubHeader(String text) {
        addToBody(Box.createVerticalStrut(6));
        JLabel label = new JLabel(text);
        label.setFont(FlashTheme.bodyMedium());
        label.setForeground(SUBHEADER_COLOR);
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        label.setBorder(FlashTheme.pad(0, 16, 0, 0));
        addToBody(label);
        addToBody(Box.createVerticalStrut(3));
    }

    /**
     * Adds a subsection label with a setup-stage question-mark help control.
     */
    public JButton addSetupHelpSubHeader(String text, SetupHelpTopic topic) {
        addToBody(Box.createVerticalStrut(6));

        JPanel row = createRow();
        row.setBorder(FlashTheme.pad(0, 20, 0, 4));
        JLabel label = new JLabel(text);
        label.setFont(FlashTheme.bodyMedium());
        label.setForeground(SUBHEADER_COLOR);
        row.add(label);
        row.add(Box.createHorizontalStrut(6));
        JButton helpButton = createSetupHelpButton(topic);
        row.add(helpButton);
        row.add(Box.createHorizontalGlue());

        addToBody(row);
        addToBody(Box.createVerticalStrut(3));
        return helpButton;
    }

    /**
     * Adds a subsection label with a controlling toggle. The returned toggle is
     * not part of the sequential getNextBoolean() list.
     */
    public ToggleSwitch addSubHeaderToggle(String text, boolean defaultValue) {
        addToBody(Box.createVerticalStrut(6));

        JPanel row = createRow();
        row.setBorder(FlashTheme.pad(0, 20, 0, 4));
        JLabel label = new JLabel(text);
        label.setFont(FlashTheme.bodyMedium());
        label.setForeground(SUBHEADER_COLOR);
        row.add(label);
        row.add(Box.createHorizontalStrut(8));
        ToggleSwitch toggle = new ToggleSwitch(defaultValue);
        row.add(toggle);
        row.add(Box.createHorizontalGlue());

        addToBody(row);
        addToBody(Box.createVerticalStrut(3));
        return toggle;
    }

    /**
     * Adds a subsection label with a leading status icon and controlling toggle.
     * The returned toggle is not part of the sequential getNextBoolean() list.
     */
    public ToggleSwitch addSubHeaderToggleWithStatus(String text, boolean defaultValue,
                                                     JComponent leadingIcon) {
        addToBody(Box.createVerticalStrut(6));

        JPanel row = createRow();
        row.setBorder(FlashTheme.pad(0, 20, 0, 4));
        JLabel statusLabel = normalizeStatusIcon(leadingIcon);
        row.add(statusLabel);
        row.add(Box.createHorizontalStrut(6));

        JLabel label = new JLabel(text);
        label.setFont(FlashTheme.bodyMedium());
        label.setForeground(SUBHEADER_COLOR);
        row.add(label);
        row.add(Box.createHorizontalStrut(8));
        ToggleSwitch toggle = new ToggleSwitch(defaultValue);
        row.add(toggle);
        row.add(Box.createHorizontalGlue());

        addToBody(row);
        addToBody(Box.createVerticalStrut(3));
        return toggle;
    }

    /** Adds a labeled toggle switch. Returns the ToggleSwitch for listener attachment. */
    public ToggleSwitch addToggle(String label, boolean defaultValue) {
        ToggleSwitch toggle = new ToggleSwitch(defaultValue);
        toggles.add(toggle);

        JPanel row = createRow();
        JLabel lbl = new JLabel(label);
        lbl.setFont(lbl.getFont().deriveFont(Font.PLAIN, 12f));
        lbl.setForeground(LABEL_COLOR);
        row.add(lbl);
        row.add(Box.createHorizontalGlue());
        row.add(toggle);

        addToBody(row);
        addToBody(Box.createVerticalStrut(4));
        return toggle;
    }

    /** Adds a labeled toggle switch with a fixed-size leading status icon. */
    public ToggleSwitch addToggleWithStatus(String label, boolean defaultValue, JComponent leadingIcon) {
        return addToggleWithStatus(label, defaultValue, leadingIcon, null);
    }

    /**
     * Adds a labeled toggle switch with a fixed-size leading status icon and
     * an optional trailing row action. Only the toggle is registered for
     * sequential boolean retrieval.
     */
    public ToggleSwitch addToggleWithStatus(String label, boolean defaultValue,
                                            JComponent leadingIcon, JComponent trailingComponent) {
        ToggleSwitch toggle = new ToggleSwitch(defaultValue);
        toggles.add(toggle);

        JPanel row = createRow();
        JLabel statusLabel = normalizeStatusIcon(leadingIcon);
        statusIconLabels.add(statusLabel);
        row.add(statusLabel);
        row.add(Box.createHorizontalStrut(6));

        JLabel lbl = new JLabel(label);
        lbl.setFont(lbl.getFont().deriveFont(Font.PLAIN, 12f));
        lbl.setForeground(LABEL_COLOR);
        row.add(lbl);
        row.add(Box.createHorizontalGlue());
        row.add(toggle);
        if (trailingComponent != null) {
            row.add(Box.createHorizontalStrut(6));
            row.add(trailingComponent);
        }

        addToBody(row);
        addToBody(Box.createVerticalStrut(4));
        return toggle;
    }

    /** Updates a status icon row added by addToggleWithStatus without changing retrieval order. */
    public void updateRowIcon(int rowIndex, Icon icon, String tooltip) {
        if (rowIndex < 0 || rowIndex >= statusIconLabels.size()) return;
        JLabel label = statusIconLabels.get(rowIndex);
        label.setIcon(icon);
        label.setToolTipText(tooltip);
        label.repaint();
    }

    /** Adds small explanatory text below the previous element. Returns the JLabel for dynamic updates. */
    public JLabel addHelpText(String text) {
        HelpLabel help = new HelpLabel(text);
        help.setFont(help.getFont().deriveFont(Font.ITALIC, 10f));
        help.setForeground(HELP_COLOR);
        help.setAlignmentX(Component.LEFT_ALIGNMENT);
        help.setBorder(FlashTheme.pad(0, 24, 2, 0));
        helpLabels.add(help);
        addToBody(help);
        addToBody(Box.createVerticalStrut(2));
        return help;
    }

    /**
     * Updates the text of a help label created by {@link #addHelpText}, re-wrapping
     * it so inline markup (e.g. {@code <br>}) renders. Falls back to a plain
     * {@code setText} for any other label.
     */
    public void setHelpText(JLabel label, String text) {
        if (label instanceof HelpLabel) {
            HelpLabel help = (HelpLabel) label;
            help.rawText = stripHelpHtml(text);
            int width = dialog.getWidth() > 0
                    ? Math.min(HELP_WRAP_WIDTH, Math.max(240, dialog.getWidth() - 80))
                    : 280;
            help.rerender(width);
        } else if (label != null) {
            label.setText(text);
        }
    }

    /** Adds a labeled text input field. */
    public JTextField addStringField(String label, String defaultValue, int columns) {
        JPanel row = createRow();
        JLabel lbl = new JLabel(label);
        lbl.setFont(lbl.getFont().deriveFont(Font.PLAIN, 12f));
        lbl.setForeground(LABEL_COLOR);
        row.add(lbl);
        row.add(Box.createHorizontalGlue());
        JTextField tf = new JTextField(defaultValue, columns);
        tf.setMaximumSize(new Dimension(columns * 12, 24));
        row.add(tf);

        textFields.add(tf);
        addToBody(row);
        addToBody(Box.createVerticalStrut(4));
        return tf;
    }

    /** Adds a labeled dropdown. */
    public JComboBox<String> addChoice(String label, String[] items, String defaultItem) {
        JPanel row = createRow();
        JLabel lbl = new JLabel(label);
        lbl.setFont(lbl.getFont().deriveFont(Font.PLAIN, 12f));
        lbl.setForeground(LABEL_COLOR);
        row.add(lbl);
        row.add(Box.createHorizontalGlue());
        String[] safeItems = items == null ? new String[0] : items;
        if (safeItems.length == 0 && defaultItem != null) {
            safeItems = new String[]{defaultItem};
        }
        JComboBox<String> combo = new JComboBox<String>(safeItems);
        if (defaultItem != null) combo.setSelectedItem(defaultItem);
        combo.setMaximumSize(new Dimension(MAX_COMBO_WIDTH, 24));
        constrainComboBox(combo);
        row.add(combo);

        combos.add(combo);
        addToBody(row);
        addToBody(Box.createVerticalStrut(4));
        return combo;
    }

    /** Handles to the widgets of one {@link #addChannelRow} line. */
    public static final class ChannelRow {
        public final JLabel nameLabel;
        public final JComboBox<String> filterChoice;
        public final ToggleSwitch binariseToggle;
        public final JPanel rowPanel;

        ChannelRow(JLabel nameLabel, JComboBox<String> filterChoice,
                   ToggleSwitch binariseToggle, JPanel rowPanel) {
            this.nameLabel = nameLabel;
            this.filterChoice = filterChoice;
            this.binariseToggle = binariseToggle;
            this.rowPanel = rowPanel;
        }
    }

    /** Adds a muted column-header row whose widths line up with {@link #addChannelRow}. */
    public void addChannelTableHeader(String channelCol, String filterCol, String toggleCol) {
        JPanel row = createRow();
        row.add(mutedColumnLabel(channelCol, CHANNEL_NAME_COL_WIDTH));
        row.add(Box.createHorizontalStrut(8));
        row.add(mutedColumnLabel(filterCol, CHANNEL_FILTER_COL_WIDTH));
        row.add(Box.createHorizontalGlue());
        JLabel toggleHeader = new JLabel(toggleCol);
        toggleHeader.setFont(FlashTheme.caption());
        toggleHeader.setForeground(HELP_COLOR);
        row.add(toggleHeader);
        addToBody(row);
        addToBody(Box.createVerticalStrut(2));
    }

    private JLabel mutedColumnLabel(String text, int width) {
        JLabel label = new JLabel(text);
        label.setFont(FlashTheme.caption());
        label.setForeground(HELP_COLOR);
        fixWidth(label, width);
        return label;
    }

    /**
     * Adds a single per-channel table row: a fixed-width name label, a filter
     * dropdown, and a binarise toggle on one line. The dropdown and toggle are
     * registered for sequential retrieval in call order, exactly like
     * {@link #addChoice} and {@link #addToggle}, so {@link #getNextChoice()} and
     * {@link #getNextBoolean()} read them in the order rows are added.
     */
    public ChannelRow addChannelRow(String name, String[] items, String defaultItem,
                                    boolean toggleDefault) {
        JPanel row = createRow();

        JLabel nameLbl = new JLabel(name);
        nameLbl.setFont(FlashTheme.body());
        nameLbl.setForeground(LABEL_COLOR);
        fixWidth(nameLbl, CHANNEL_NAME_COL_WIDTH);
        row.add(nameLbl);
        row.add(Box.createHorizontalStrut(8));

        String[] safeItems = items == null ? new String[0] : items;
        if (safeItems.length == 0 && defaultItem != null) {
            safeItems = new String[]{defaultItem};
        }
        JComboBox<String> combo = new JComboBox<String>(safeItems);
        if (defaultItem != null) combo.setSelectedItem(defaultItem);
        Dimension comboSize = new Dimension(CHANNEL_FILTER_COL_WIDTH, 24);
        combo.setPreferredSize(comboSize);
        combo.setMinimumSize(comboSize);
        combo.setMaximumSize(comboSize);
        row.add(combo);

        row.add(Box.createHorizontalGlue());

        ToggleSwitch toggle = new ToggleSwitch(toggleDefault);
        row.add(toggle);

        combos.add(combo);
        toggles.add(toggle);
        addToBody(row);
        addToBody(Box.createVerticalStrut(4));
        return new ChannelRow(nameLbl, combo, toggle, row);
    }

    private static void fixWidth(JComponent comp, int width) {
        Dimension pref = comp.getPreferredSize();
        int height = pref == null ? 22 : pref.height;
        comp.setPreferredSize(new Dimension(width, height));
        comp.setMinimumSize(new Dimension(width, height));
        comp.setMaximumSize(new Dimension(width, height));
    }

    /** Handles to the widgets of one {@link #addChannelDualNumericRow} line. */
    public static final class ChannelNumericRow {
        public final JLabel nameLabel;
        public final JTextField primaryField;
        public final JTextField secondaryField;
        public final JPanel rowPanel;

        ChannelNumericRow(JLabel nameLabel, JTextField primaryField,
                          JTextField secondaryField, JPanel rowPanel) {
            this.nameLabel = nameLabel;
            this.primaryField = primaryField;
            this.secondaryField = secondaryField;
            this.rowPanel = rowPanel;
        }
    }

    /** Adds a muted column-header row whose widths line up with {@link #addChannelDualNumericRow}. */
    public void addChannelNumericTableHeader(String channelCol, String primaryCol, String secondaryCol) {
        JPanel row = createRow();
        row.add(mutedColumnLabel(channelCol, CHANNEL_NAME_COL_WIDTH));
        row.add(Box.createHorizontalStrut(8));
        row.add(mutedColumnLabel(primaryCol, CHANNEL_NUM_COL_WIDTH));
        row.add(Box.createHorizontalStrut(8));
        row.add(mutedColumnLabel(secondaryCol, CHANNEL_NUM_COL_WIDTH));
        row.add(Box.createHorizontalGlue());
        addToBody(row);
        addToBody(Box.createVerticalStrut(2));
    }

    /**
     * Adds a per-channel row with a fixed-width name label and two numeric
     * fields on one line. Both fields are registered for sequential retrieval
     * in call order (primary then secondary), exactly like
     * {@link #addNumericField}, so {@link #getNextNumber()} reads them in the
     * order rows are added.
     */
    public ChannelNumericRow addChannelDualNumericRow(String name,
                                                      double primaryValue, double secondaryValue) {
        JPanel row = createRow();

        JLabel nameLbl = new JLabel(name);
        nameLbl.setFont(FlashTheme.body());
        nameLbl.setForeground(LABEL_COLOR);
        fixWidth(nameLbl, CHANNEL_NAME_COL_WIDTH);
        row.add(nameLbl);
        row.add(Box.createHorizontalStrut(8));

        JTextField primary = channelNumericField(primaryValue);
        row.add(primary);
        row.add(Box.createHorizontalStrut(8));
        JTextField secondary = channelNumericField(secondaryValue);
        row.add(secondary);
        row.add(Box.createHorizontalGlue());

        numericFields.add(primary);
        numericFields.add(secondary);
        addToBody(row);
        addToBody(Box.createVerticalStrut(4));
        return new ChannelNumericRow(nameLbl, primary, secondary, row);
    }

    private JTextField channelNumericField(double value) {
        JTextField tf = new JTextField(channelNumericText(value), 6);
        tf.setFont(tf.getFont().deriveFont(Font.PLAIN, 12f));
        Dimension size = new Dimension(CHANNEL_NUM_COL_WIDTH, 24);
        tf.setPreferredSize(size);
        tf.setMinimumSize(size);
        tf.setMaximumSize(size);
        return tf;
    }

    private static String channelNumericText(double value) {
        if (!Double.isInfinite(value) && !Double.isNaN(value) && value == Math.rint(value)) {
            return String.valueOf((long) value);
        }
        return String.valueOf(value);
    }

    /**
     * Adds a radio-card chooser (visual replacement for {@link #addChoice}).
     * Registers the card's backing combo in the same choice sequence, so
     * {@link #getNextChoice()} reads it in order exactly like a dropdown, and
     * the returned combo supports {@code getSelectedItem}/{@code setSelectedItem}
     * and listeners just like {@link #addChoice} does. Pass {@code header} null
     * to suppress the bold label above the cards.
     */
    public JComboBox<String> addCardChoice(String header, CardChoice.Option[] options, String defaultValue) {
        return addCardChoice(header, options, defaultValue, null);
    }

    /** As {@link #addCardChoice(String, CardChoice.Option[], String)}, returning the panel via the out-array. */
    public JComboBox<String> addCardChoice(String header, CardChoice.Option[] options, String defaultValue,
                                           CardChoice[] panelOut) {
        if (header != null && !header.isEmpty()) {
            addSubHeader(header);
        }
        CardChoice cards = new CardChoice(options, defaultValue);
        combos.add(cards.comboBox());
        if (panelOut != null && panelOut.length > 0) {
            panelOut[0] = cards;
        }
        addToBody(cards);
        addToBody(Box.createVerticalStrut(6));
        return cards.comboBox();
    }

    /** Adds a plain text message. Returns the JLabel for later updates. */
    public JLabel addMessage(String text) {
        JLabel label = new JLabel("<html><body width='280'>" + text + "</body></html>");
        label.setFont(label.getFont().deriveFont(Font.PLAIN, 11f));
        label.setForeground(LABEL_COLOR);
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        label.setBorder(FlashTheme.pad(0, 4, 2, 0));
        addToBody(label);
        addToBody(Box.createVerticalStrut(4));
        return label;
    }

    /** Adds a button. Returns the JButton for listener attachment. */
    public JButton addButton(String label) {
        JPanel row = createRow();
        JButton btn = new JButton(label);
        btn.setFocusPainted(false);
        row.add(btn);
        addToBody(row);
        addToBody(Box.createVerticalStrut(4));
        return btn;
    }

    /** Adds a labeled numeric input field. */
    public JTextField addNumericField(String label, double defaultValue, int decimals) {
        JPanel row = createRow();
        JLabel lbl = new JLabel(label);
        lbl.setFont(lbl.getFont().deriveFont(Font.PLAIN, 12f));
        lbl.setForeground(LABEL_COLOR);
        row.add(lbl);
        row.add(Box.createHorizontalGlue());
        String val = decimals == 0 ? String.valueOf((int) defaultValue) : String.valueOf(defaultValue);
        JTextField tf = new JTextField(val, 8);
        tf.setMaximumSize(new Dimension(96, 24));
        row.add(tf);
        numericFields.add(tf);
        addToBody(row);
        addToBody(Box.createVerticalStrut(4));
        return tf;
    }

    /** Adds vertical spacing. */
    public void addSpacer(int height) {
        addToBody(Box.createVerticalStrut(height));
    }

    /** Adds a custom Swing component row to the dialog body. */
    public void addComponent(JComponent component) {
        if (component == null) return;
        constrainNestedComboBoxes(component);
        component.setAlignmentX(Component.LEFT_ALIGNMENT);
        addToBody(component);
        addToBody(Box.createVerticalStrut(4));
    }

    /** Binds live validation to an existing text field and gates only the primary button. */
    public static Runnable bindValidation(final PipelineDialog dialog,
                                          final JTextField field,
                                          final Predicate<String> predicate,
                                          final JLabel hintLabel,
                                          final String hintText) {
        final Runnable update = new Runnable() {
            @Override public void run() {
                String text = field == null || field.getText() == null ? "" : field.getText();
                boolean valid = predicate != null && predicate.test(text);
                applyValidationState(dialog, hintLabel, valid, hintText);
            }
        };
        if (field != null) {
            field.getDocument().addDocumentListener(new DocumentListener() {
                @Override public void insertUpdate(DocumentEvent e) {
                    update.run();
                }

                @Override public void removeUpdate(DocumentEvent e) {
                    update.run();
                }

                @Override public void changedUpdate(DocumentEvent e) {
                    update.run();
                }
            });
        }
        update.run();
        return update;
    }

    /** Binds live validation to an existing dropdown and gates only the primary button. */
    public static Runnable bindValidation(final PipelineDialog dialog,
                                          final JComboBox<String> combo,
                                          final Predicate<String> predicate,
                                          final JLabel hintLabel,
                                          final String hintText) {
        final Runnable update = new Runnable() {
            @Override public void run() {
                Object selected = combo == null ? null : combo.getSelectedItem();
                String text = selected == null ? "" : selected.toString();
                boolean valid = predicate != null && predicate.test(text);
                applyValidationState(dialog, hintLabel, valid, hintText);
            }
        };
        if (combo != null) {
            combo.addActionListener(e -> update.run());
        }
        update.run();
        return update;
    }

    private static void applyValidationState(PipelineDialog dialog, JLabel hintLabel,
                                             boolean valid, String hintText) {
        if (hintLabel != null) {
            hintLabel.setText(valid ? "" : (hintText == null ? "" : hintText));
        }
        if (dialog != null) {
            dialog.setPrimaryButtonEnabled(valid);
        }
    }

    /**
     * Begins an advanced-options section. Rows added between this call and
     * {@link #endAdvancedSection()} are placed inside a collapsible child
     * panel. Visibility is controlled by an arrow row inserted automatically.
     * The sequential retrieval contract is preserved — toggles, numeric
     * fields, choices, and string fields still register in
     * {@code getNextBoolean()}/etc. order.
     *
     * @param moduleId stable identifier used for the per-module sticky pref
     *                 ({@code flash.advanced.<moduleId>}). Combined with the
     *                 global {@code flash.advanced.global} pref to determine
     *                 whether the section opens by default.
     */
    public void beginAdvancedSection(String moduleId) {
        this.currentModuleId = moduleId;
        final boolean openByDefault = ij.Prefs.get("flash.advanced.global", false)
                || ij.Prefs.get("flash.advanced." + moduleId, false);

        // Toggle row that flips visibility — added to the parent (contentPanel),
        // not the advanced panel, so it always shows.
        JPanel toggleRow = createRow();
        final JLabel arrow = new JLabel(openByDefault ? "▾ Hide advanced options" : "▸ Show advanced options");
        arrow.setFont(arrow.getFont().deriveFont(Font.PLAIN, 12f));
        arrow.setForeground(LABEL_COLOR);
        arrow.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        toggleRow.add(arrow);
        toggleRow.add(Box.createHorizontalGlue());
        contentPanel.add(toggleRow);
        contentPanel.add(Box.createVerticalStrut(4));

        final JPanel advancedPanel = new JPanel();
        advancedPanel.setLayout(new BoxLayout(advancedPanel, BoxLayout.Y_AXIS));
        advancedPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
        advancedPanel.setOpaque(false);
        advancedPanel.setVisible(openByDefault);

        // Sticky preference lives inside the advanced panel and uses the same
        // boolean control as the rest of PipelineDialog.
        final JPanel stickyRow = createRow();
        stickyRow.setBorder(FlashTheme.pad(0, 0, 0, 4));
        final ToggleSwitch sticky = new ToggleSwitch(ij.Prefs.get("flash.advanced." + moduleId, false));
        JLabel stickyLabel = new JLabel("Always show advanced options for this module");
        stickyLabel.setForeground(HELP_COLOR);
        stickyLabel.setFont(FlashTheme.caption());
        stickyLabel.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        stickyRow.add(sticky);
        stickyRow.add(Box.createHorizontalStrut(FlashTheme.SPACE_S));
        stickyRow.add(stickyLabel);
        stickyRow.add(Box.createHorizontalGlue());
        stickyRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        final String stickyKey = "flash.advanced." + moduleId;
        sticky.addChangeListener(new Runnable() {
            @Override public void run() {
                ij.Prefs.set(stickyKey, sticky.isSelected());
                ij.Prefs.savePreferences();
            }
        });
        stickyLabel.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                sticky.setSelected(!sticky.isSelected());
            }
        });
        advancedPanel.add(stickyRow);

        contentPanel.add(advancedPanel);

        arrow.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                boolean show = !advancedPanel.isVisible();
                advancedPanel.setVisible(show);
                arrow.setText(show ? "▾ Hide advanced options" : "▸ Show advanced options");
                dialog.revalidate();
                dialog.repaint();
            }
        });

        this.currentAdvancedPanel = advancedPanel;
    }

    /** Ends the current advanced-options section. */
    public void endAdvancedSection() {
        this.currentAdvancedPanel = null;
        this.currentModuleId = null;
    }

    /**
     * Begins a titled section whose title remains visible while the rows added
     * before {@link #endCollapsibleSection()} can be collapsed or expanded.
     * Input retrieval order is preserved because the regular add* helpers still
     * register their controls in the same lists.
     */
    public void beginCollapsibleSection(String title, boolean initiallyExpanded) {
        beginCollapsibleSection(title, initiallyExpanded, null);
    }

    /**
     * Begins a titled collapsible section with a setup-style question-mark help
     * control. The help button sits to the right of the title and opens the
     * given {@link SetupHelpTopic} without toggling the section's expanded
     * state. Pass {@code null} for {@code topic} to omit the help control.
     */
    public void beginCollapsibleSection(String title, boolean initiallyExpanded,
                                        SetupHelpTopic topic) {
        if (currentCollapsiblePanel != null) {
            throw new IllegalStateException("Nested collapsible sections are not supported");
        }

        addToBody(Box.createVerticalStrut(10));

        final JPanel section = new JPanel();
        section.setLayout(new BoxLayout(section, BoxLayout.Y_AXIS));
        section.setAlignmentX(Component.LEFT_ALIGNMENT);
        section.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));
        section.setOpaque(false);

        final JPanel headerRow = createRow();
        headerRow.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

        final JLabel arrow = new JLabel();
        arrow.setForeground(HEADER_COLOR);
        arrow.setFont(arrow.getFont().deriveFont(Font.PLAIN, 13f));
        arrow.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        headerRow.add(arrow);
        headerRow.add(Box.createHorizontalStrut(6));

        final JLabel label = new JLabel(title == null ? "" : title);
        label.setFont(label.getFont().deriveFont(Font.BOLD, 13f));
        label.setForeground(HEADER_COLOR);
        label.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        headerRow.add(label);
        if (topic != null) {
            headerRow.add(Box.createHorizontalStrut(6));
            JButton helpButton = createSetupHelpButton(topic);
            helpButton.setCursor(Cursor.getPredefinedCursor(Cursor.DEFAULT_CURSOR));
            headerRow.add(helpButton);
        }
        headerRow.add(Box.createHorizontalGlue());
        section.add(headerRow);
        section.add(Box.createVerticalStrut(4));

        JSeparator sep = new JSeparator(SwingConstants.HORIZONTAL);
        sep.setMaximumSize(new Dimension(Integer.MAX_VALUE, 1));
        sep.setAlignmentX(Component.LEFT_ALIGNMENT);
        section.add(sep);
        section.add(Box.createVerticalStrut(6));

        final JPanel body = new JPanel();
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
        body.setAlignmentX(Component.LEFT_ALIGNMENT);
        body.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));
        body.setBorder(FlashTheme.pad(0, 12, 0, 0));
        body.setOpaque(false);
        body.setVisible(initiallyExpanded);
        section.add(body);

        addToBody(section);
        refreshCollapsibleHeader(arrow, initiallyExpanded);

        MouseAdapter toggle = new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                boolean show = !body.isVisible();
                body.setVisible(show);
                refreshCollapsibleHeader(arrow, show);
                dialog.revalidate();
                dialog.repaint();
            }
        };
        headerRow.addMouseListener(toggle);
        arrow.addMouseListener(toggle);
        label.addMouseListener(toggle);

        this.currentCollapsiblePanel = body;
    }

    /** Ends the current titled collapsible section. */
    public void endCollapsibleSection() {
        this.currentCollapsiblePanel = null;
    }

    private void refreshCollapsibleHeader(JLabel arrow, boolean expanded) {
        Icon icon = expanded
                ? FlashIcons.chevronDown(12, HEADER_COLOR)
                : FlashIcons.chevronRight(12, HEADER_COLOR);
        if (icon != null) {
            arrow.setIcon(icon);
            arrow.setText("");
        } else {
            arrow.setIcon(null);
            arrow.setText(expanded ? "[-]" : "[+]");
        }
    }

    /**
     * Routes a body-row component to the active collapsible section, advanced
     * panel, or default {@code contentPanel}. Sequential retrieval lists are
     * populated independently by the public {@code add*} helpers, so visibility
     * changes never affect retrieval order.
     */
    private void addToBody(Component c) {
        if (currentCollapsiblePanel != null) {
            currentCollapsiblePanel.add(c);
        } else if (currentAdvancedPanel != null) {
            currentAdvancedPanel.add(c);
        } else {
            contentPanel.add(c);
        }
    }

    /** Shows the dialog (blocking). Returns true if OK was pressed. False for Cancel or Back. */
    public boolean showDialog() {
        if (GraphicsEnvironment.isHeadless()) {
            IJ.log("[PipelineDialog] showDialog() called in headless mode — "
                    + "returning false (caller should set headless flag).");
            return false;
        }
        wasBackPressed = false;
        actionCommand = "";
        prepareForDisplay();
        if (!customLocation) dialog.setLocationRelativeTo(null);
        if (dialog.isModal()) {
            dialog.setVisible(true);
            return !wasCanceled;
        }

        final CountDownLatch closed = new CountDownLatch(1);
        final SecondaryLoop loop = SwingUtilities.isEventDispatchThread()
                ? Toolkit.getDefaultToolkit().getSystemEventQueue().createSecondaryLoop()
                : null;
        WindowAdapter closeListener = new WindowAdapter() {
            @Override public void windowClosed(WindowEvent e) {
                closed.countDown();
                if (loop != null) loop.exit();
            }
        };
        dialog.addWindowListener(closeListener);
        dialog.setVisible(true);
        if (loop != null) {
            loop.enter();
            dialog.removeWindowListener(closeListener);
            return !wasCanceled;
        }
        try {
            closed.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            wasCanceled = true;
            dialog.dispose();
        } finally {
            dialog.removeWindowListener(closeListener);
        }
        return !wasCanceled;
    }

    public boolean wasCanceled() {
        return wasCanceled;
    }

    private void requestCancelClose() {
        if (cancelConfirmation != null && !cancelConfirmation.get()) return;
        wasCanceled = true;
        AnalysisCancellation.markDialogCancelRequested();
        dialog.dispose();
    }

    /** Enables the Back button on this dialog. */
    public void enableBackButton() {
        backEnabled = true;
        backButton.setVisible(true);
    }

    /** Shows or hides the default Back / Cancel / OK button cluster. */
    public void setDefaultButtonsVisible(boolean visible) {
        okButton.setVisible(visible);
        cancelButton.setVisible(visible);
        backButton.setVisible(visible && backEnabled);
    }

    /** Sets the primary default button label, e.g. "Run" for the main pipeline dialog. */
    public void setPrimaryButtonText(String text) {
        String trimmed = text == null ? "" : text.trim();
        if (!trimmed.isEmpty()) {
            okButton.setText(trimmed);
            refreshFooterButtonSize(okButton, DEFAULT_FOOTER_BUTTON_MIN_WIDTH);
        }
    }

    /** Enables or disables the primary default button. */
    public void setPrimaryButtonEnabled(boolean enabled) {
        okButton.setEnabled(enabled);
    }

    public void focusPrimaryButtonOnShow() {
        dialog.getRootPane().setDefaultButton(okButton);
        requestFocusOnShow(okButton);
    }

    /** Makes this dialog modeless so image windows remain interactive while it is open. */
    public void setModal(boolean modal) {
        dialog.setModal(modal);
    }

    /** Enables or disables the backing window without closing it. */
    public void setEnabled(boolean enabled) {
        dialog.setEnabled(enabled);
    }

    /** Moves the backing window behind other windows. */
    public void toBack() {
        dialog.toBack();
    }

    /** Brings the backing window back after a temporary child workflow finishes. */
    public void toFront() {
        dialog.toFront();
        dialog.requestFocus();
    }

    /**
     * Runs a child dialog workflow without leaving this dialog on top of it.
     * Use for helper/advisor dialogs launched from button callbacks.
     */
    public void runChildWorkflow(Runnable workflow) {
        if (workflow == null) return;
        boolean showing = dialog.isShowing();
        dialog.setEnabled(false);
        dialog.toBack();
        try {
            workflow.run();
        } finally {
            dialog.setEnabled(true);
            if (showing && dialog.isDisplayable()) {
                dialog.toFront();
                dialog.requestFocus();
            }
        }
    }

    /** Positions the backing window before showDialog() packs and displays it. */
    public void setLocation(int x, int y) {
        customLocation = true;
        dialog.setLocation(x, y);
    }

    /** Adds a button to the footer utility row above OK/Cancel. */
    public JButton addFooterButton(String label) {
        JButton btn = new JButton(label);
        sizeFooterButtonToText(btn, UTILITY_FOOTER_BUTTON_MIN_WIDTH);
        leftButtonPanel.setVisible(true);
        leftButtonPanel.add(btn);
        buttonBar.revalidate();
        return btn;
    }

    /**
     * Adds a button to the bottom-right footer cluster, before the default
     * Back/Cancel/OK buttons. Use this to right-cluster Cancel + primary
     * actions per platform HIG when the default buttons are hidden.
     */
    public JButton addRightFooterButton(String label) {
        JButton btn = new JButton(label);
        sizeFooterButtonToText(btn, UTILITY_FOOTER_BUTTON_MIN_WIDTH);
        rightButtonPanel.add(btn, 0);
        buttonBar.revalidate();
        return btn;
    }

    /**
     * Sets a one-off, non-blocking status line below the dialog body and
     * above the footer button bar. Use for "Saved..." style toasts that
     * the user does not need to dismiss. Pass null or an empty string to
     * clear. Lazily creates the label and wraps the existing button bar
     * the first time it's called.
     */
    public void setTransientStatus(String text) {
        if (statusLabel == null) {
            statusLabel = new JLabel(" ");
            statusLabel.setForeground(FlashTheme.SUCCESS_FG);
            statusLabel.setFont(FlashTheme.body());
            statusLabel.setBorder(FlashTheme.pad(2, 12, 4, 12));
            southWrapper = new JPanel(new BorderLayout());
            southWrapper.setBackground(BG_COLOR);
            southWrapper.add(statusLabel, BorderLayout.NORTH);
            dialog.getContentPane().remove(buttonBar);
            southWrapper.add(buttonBar, BorderLayout.SOUTH);
            dialog.getContentPane().add(southWrapper, BorderLayout.SOUTH);
        }
        statusLabel.setText(text == null || text.isEmpty() ? " " : text);
        if (dialog.isShowing()) {
            dialog.getContentPane().revalidate();
            dialog.getContentPane().repaint();
        }
    }

    /** Requests focus for a component after the backing dialog is visible. */
    public void requestFocusOnShow(final JComponent component) {
        if (component == null) return;
        dialog.addWindowListener(new WindowAdapter() {
            @Override public void windowOpened(WindowEvent e) {
                dialog.removeWindowListener(this);
                SwingUtilities.invokeLater(new Runnable() {
                    @Override public void run() {
                        component.requestFocusInWindow();
                    }
                });
            }
        });
    }

    /** Closes the dialog and records a custom action command for the caller. */
    public void closeWithAction(String action) {
        actionCommand = action == null ? "" : action;
        wasCanceled = true;
        if ("cancel".equalsIgnoreCase(actionCommand)) {
            AnalysisCancellation.markDialogCancelRequested();
        }
        dialog.dispose();
    }

    /** Returns the custom action command recorded by closeWithAction(). */
    public String getActionCommand() {
        return actionCommand;
    }

    /** Returns true if the user pressed Back (not Cancel, not OK). */
    public boolean wasBackPressed() {
        return wasBackPressed;
    }

    // Sequential retrieval methods (matching GenericDialog pattern)

    public boolean getNextBoolean() {
        ToggleSwitch toggle = next(toggles, toggleIndex, "boolean");
        toggleIndex++;
        return toggle.isSelected();
    }

    public String getNextString() {
        JTextField field = next(textFields, textFieldIndex, "string");
        textFieldIndex++;
        return field.getText();
    }

    public String getNextChoice() {
        JComboBox<String> combo = next(combos, comboIndex, "choice");
        comboIndex++;
        Object selected = combo.getSelectedItem();
        return selected == null ? "" : selected.toString();
    }

    public double getNextNumber() {
        JTextField field = next(numericFields, numericFieldIndex, "number");
        numericFieldIndex++;
        String text = field.getText().trim();
        try {
            return Double.parseDouble(text);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private JPanel createRow() {
        JPanel row = new JPanel();
        row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.setOpaque(false);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
        row.setBorder(FlashTheme.pad(0, 4, 0, 4));
        return row;
    }

    private JButton createSetupHelpButton(final SetupHelpTopic topic) {
        String tooltip = topic == null
                ? "Setup help is not available yet."
                : "About " + topic.title;
        JButton helpButton = HelpButton.question(tooltip);
        helpButton.setEnabled(topic != null);
        if (topic != null) {
            helpButton.addActionListener(e -> SetupHelpDialog.show(dialog, topic));
        }
        return helpButton;
    }

    private JLabel normalizeStatusIcon(JComponent leadingIcon) {
        JLabel label;
        if (leadingIcon instanceof JLabel) {
            label = (JLabel) leadingIcon;
        } else {
            label = new JLabel();
            if (leadingIcon != null) {
                label.setToolTipText(leadingIcon.getToolTipText());
            }
        }
        Dimension size = new Dimension(14, 14);
        label.setMinimumSize(size);
        label.setPreferredSize(size);
        label.setMaximumSize(size);
        label.setHorizontalAlignment(SwingConstants.CENTER);
        label.setVerticalAlignment(SwingConstants.CENTER);
        return label;
    }

    private void rerenderHelpText() {
        int targetWidth = Math.min(HELP_WRAP_WIDTH, Math.max(240, dialog.getWidth() - 80));
        for (HelpLabel helpLabel : helpLabels) {
            helpLabel.rerender(targetWidth);
        }
        dialog.getContentPane().revalidate();
    }

    private void prepareForDisplay() {
        dialog.pack();
        fitDialogToScreen();
        rerenderHelpText();
        dialog.pack();
        fitDialogToScreen();
        dialog.getContentPane().revalidate();
    }

    private void fitDialogToScreen() {
        Dimension size = computeDisplaySize(dialog.getPreferredSize(),
                Toolkit.getDefaultToolkit().getScreenSize());
        if (!size.equals(dialog.getSize())) {
            dialog.setSize(size);
        }
    }

    static Dimension computeDisplaySize(Dimension preferred, Dimension screen) {
        Dimension pref = preferred == null ? new Dimension(0, 0) : preferred;
        Dimension screenSize = screen == null || screen.width <= 0 || screen.height <= 0
                ? new Dimension(MAX_DIALOG_WIDTH, 900)
                : screen;
        int maxW = Math.max(MIN_DIALOG_WIDTH,
                Math.min(MAX_DIALOG_WIDTH, (int) (screenSize.width * MAX_SCREEN_WIDTH_FRACTION)));
        int maxH = Math.max(MIN_DIALOG_HEIGHT,
                (int) (screenSize.height * MAX_SCREEN_HEIGHT_FRACTION));

        int width = pref.width;
        int height = pref.height;
        if (height > maxH) {
            height = maxH;
            width += SCROLLBAR_WIDTH_ALLOWANCE;
        }
        if (width > maxW) {
            width = maxW;
        }
        return new Dimension(width, height);
    }

    private static void constrainNestedComboBoxes(Component component) {
        if (component instanceof JComboBox) {
            constrainComboBox((JComboBox<?>) component);
        }
        if (component instanceof Container) {
            Component[] children = ((Container) component).getComponents();
            for (int i = 0; i < children.length; i++) {
                constrainNestedComboBoxes(children[i]);
            }
        }
    }

    private static void constrainComboBox(JComboBox<?> combo) {
        if (combo == null) return;
        Dimension pref = combo.getPreferredSize();
        int height = pref == null ? 24 : Math.max(24, pref.height);
        int width = pref == null ? MAX_COMBO_WIDTH : Math.min(pref.width, MAX_COMBO_WIDTH);
        combo.setPreferredSize(new Dimension(width, height));
        combo.setMaximumSize(new Dimension(MAX_COMBO_WIDTH, height));
    }

    private static void styleActionButton(JButton button, Color background, Color foreground, Color border) {
        button.setBackground(background);
        button.setForeground(foreground);
        button.setOpaque(true);
        button.setContentAreaFilled(true);
        button.setFocusPainted(false);
        button.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(border),
                FlashTheme.pad(3, 10, 3, 10)));
    }

    private void refreshFooterButtonSize(JButton button, int minWidth) {
        sizeFooterButtonToText(button, minWidth);
        button.revalidate();
        button.repaint();
        buttonBar.revalidate();
        buttonBar.repaint();
        if (dialog.isShowing()) {
            dialog.pack();
            fitDialogToScreen();
        }
    }

    private static void sizeFooterButtonToText(JButton button, int minWidth) {
        if (button == null) return;
        button.setPreferredSize(null);
        Dimension natural = button.getPreferredSize();
        int width = Math.max(minWidth, natural == null ? minWidth : natural.width);
        Dimension size = new Dimension(width, FOOTER_BUTTON_HEIGHT);
        button.setMinimumSize(size);
        button.setPreferredSize(size);
    }

    private static <T> T next(List<T> values, int index, String type) {
        if (index < 0 || index >= values.size()) {
            throw new IllegalStateException("No " + type + " field is available at retrieval index "
                    + index + "; dialog contains " + values.size() + " " + type
                    + " field(s). Add-order within each field type must match getNext"
                    + retrievalSuffix(type) + "() order.");
        }
        return values.get(index);
    }

    private static String retrievalSuffix(String type) {
        if ("boolean".equals(type)) return "Boolean";
        if ("choice".equals(type)) return "Choice";
        if ("number".equals(type)) return "Number";
        if ("string".equals(type)) return "String";
        return "";
    }

    private static class BodyPanel extends JPanel implements Scrollable {
        @Override public Dimension getPreferredScrollableViewportSize() {
            Dimension pref = getPreferredSize();
            int width = pref == null ? 0 : Math.min(pref.width, MAX_BODY_VIEWPORT_WIDTH);
            int height = pref == null ? 0 : pref.height;
            return new Dimension(width, height);
        }

        @Override public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction) {
            return 16;
        }

        @Override public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction) {
            return orientation == SwingConstants.VERTICAL
                    ? Math.max(16, visibleRect.height - 32)
                    : Math.max(16, visibleRect.width - 32);
        }

        @Override public boolean getScrollableTracksViewportWidth() {
            return true;
        }

        @Override public boolean getScrollableTracksViewportHeight() {
            return false;
        }
    }

    private static class HelpLabel extends JLabel {
        private String rawText;
        private boolean rendering;

        HelpLabel(String text) {
            rawText = stripHelpHtml(text);
            rerender(280);
        }

        @Override public void setText(String text) {
            if (!rendering) {
                rawText = stripHelpHtml(text);
            }
            super.setText(text);
        }

        void rerender(int width) {
            rendering = true;
            try {
                super.setText("<html><body width='" + width + "'>"
                        + (rawText == null ? "" : rawText) + "</body></html>");
            } finally {
                rendering = false;
            }
        }
    }

    private static String stripHelpHtml(String text) {
        if (text == null) return "";
        String lower = text.toLowerCase();
        int bodyStart = lower.indexOf("<body");
        if (bodyStart >= 0) {
            int contentStart = text.indexOf('>', bodyStart);
            int bodyEnd = lower.lastIndexOf("</body>");
            if (contentStart >= 0 && bodyEnd > contentStart) {
                return text.substring(contentStart + 1, bodyEnd);
            }
        }
        if (lower.startsWith("<html>") && lower.endsWith("</html>")) {
            return text.substring(6, text.length() - 7);
        }
        return text;
    }
}
