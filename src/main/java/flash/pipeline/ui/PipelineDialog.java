package flash.pipeline.ui;

import flash.pipeline.help.AnalysisHelpCatalog;
import flash.pipeline.help.AnalysisHelpDialog;
import flash.pipeline.help.AnalysisHelpTopic;
import ij.IJ;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.SecondaryLoop;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;

/**
 * Custom dialog with modern toggle switches instead of plain AWT checkboxes.
 * Provides section headers, labeled toggles, text fields, dropdowns, and help text.
 */
public class PipelineDialog {

    private static final Color BG_COLOR = new Color(245, 245, 245);
    private static final Color HEADER_COLOR = new Color(55, 71, 79);
    private static final Color SUBHEADER_COLOR = new Color(78, 93, 101);
    private static final Color LABEL_COLOR = new Color(33, 33, 33);
    private static final Color HELP_COLOR = new Color(117, 117, 117);
    private static final Color PRIMARY_ACTION_BG = new Color(235, 248, 239);
    private static final Color PRIMARY_ACTION_FG = new Color(37, 103, 62);
    private static final Color PRIMARY_ACTION_BORDER = new Color(111, 173, 130);
    private static final Color CANCEL_ACTION_BG = new Color(252, 240, 240);
    private static final Color CANCEL_ACTION_FG = new Color(137, 44, 44);
    private static final Color CANCEL_ACTION_BORDER = new Color(196, 108, 108);
    private static final int MAX_BODY_VIEWPORT_WIDTH = 860;
    private static final int MAX_DIALOG_WIDTH = 1100;
    private static final int MIN_DIALOG_WIDTH = 320;
    private static final int MIN_DIALOG_HEIGHT = 260;
    private static final int SCROLLBAR_WIDTH_ALLOWANCE = 30;
    private static final double MAX_SCREEN_WIDTH_FRACTION = 0.92;
    private static final double MAX_SCREEN_HEIGHT_FRACTION = 0.80;
    private static final int MAX_COMBO_WIDTH = 280;
    private static final int HELP_WRAP_WIDTH = 460;

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
        dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);

        contentPanel = new BodyPanel();
        contentPanel.setLayout(new BoxLayout(contentPanel, BoxLayout.Y_AXIS));
        contentPanel.setBorder(new EmptyBorder(15, 20, 10, 20));
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
        breadcrumbPanel.setBorder(new EmptyBorder(4, 12, 2, 12));
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
        backButton.setPreferredSize(new Dimension(80, 28));
        okButton.setPreferredSize(new Dimension(80, 28));
        cancelButton.setPreferredSize(new Dimension(80, 28));
        styleActionButton(okButton, PRIMARY_ACTION_BG, PRIMARY_ACTION_FG, PRIMARY_ACTION_BORDER);
        styleActionButton(cancelButton, CANCEL_ACTION_BG, CANCEL_ACTION_FG, CANCEL_ACTION_BORDER);
        backButton.addActionListener(e -> { wasBackPressed = true; wasCanceled = true; dialog.dispose(); });
        okButton.addActionListener(e -> { wasCanceled = false; dialog.dispose(); });
        cancelButton.addActionListener(e -> { wasCanceled = true; dialog.dispose(); });
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

    private void repaintBreadcrumb() {
        breadcrumbPanel.removeAll();
        breadcrumbPanel.setLayout(new BoxLayout(breadcrumbPanel, BoxLayout.Y_AXIS));

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

    private JLabel makePhaseChip(Phase phase) {
        JLabel chip = new JLabel(" " + phase.label + " ");
        chip.setOpaque(true);
        chip.setFont(chip.getFont().deriveFont(Font.PLAIN, 11f));
        chip.setBorder(BorderFactory.createLineBorder(HEADER_COLOR, 1, true));
        chip.setBackground(phase == currentPhase ? HEADER_COLOR : BG_COLOR);
        chip.setForeground(phase == currentPhase ? Color.WHITE : HEADER_COLOR);
        return chip;
    }

    /** Adds a bold section header. */
    public void addHeader(String text) {
        addToBody(Box.createVerticalStrut(10));
        JLabel label = new JLabel(text);
        label.setFont(label.getFont().deriveFont(Font.BOLD, 13f));
        label.setForeground(HEADER_COLOR);
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
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
        label.setFont(label.getFont().deriveFont(Font.BOLD, 13f));
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
        label.setFont(label.getFont().deriveFont(Font.BOLD, 12f));
        label.setForeground(SUBHEADER_COLOR);
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        label.setBorder(new EmptyBorder(0, 16, 0, 0));
        addToBody(label);
        addToBody(Box.createVerticalStrut(3));
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
        help.setBorder(new EmptyBorder(0, 24, 2, 0));
        helpLabels.add(help);
        addToBody(help);
        addToBody(Box.createVerticalStrut(2));
        return help;
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
        JComboBox<String> combo = new JComboBox<String>(items);
        if (defaultItem != null) combo.setSelectedItem(defaultItem);
        combo.setMaximumSize(new Dimension(280, 24));
        constrainComboBox(combo);
        row.add(combo);

        combos.add(combo);
        addToBody(row);
        addToBody(Box.createVerticalStrut(4));
        return combo;
    }

    /** Adds a plain text message. Returns the JLabel for later updates. */
    public JLabel addMessage(String text) {
        JLabel label = new JLabel("<html><body width='280'>" + text + "</body></html>");
        label.setFont(label.getFont().deriveFont(Font.PLAIN, 11f));
        label.setForeground(LABEL_COLOR);
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        label.setBorder(new EmptyBorder(0, 4, 2, 0));
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

        // Sticky-pref checkbox lives inside the advanced panel.
        final JCheckBox sticky = new JCheckBox("Always show advanced options for this module",
                ij.Prefs.get("flash.advanced." + moduleId, false));
        sticky.setForeground(HELP_COLOR);
        sticky.setFont(sticky.getFont().deriveFont(Font.PLAIN, 11f));
        sticky.setOpaque(false);
        sticky.setAlignmentX(Component.LEFT_ALIGNMENT);
        final String stickyKey = "flash.advanced." + moduleId;
        sticky.addActionListener(new java.awt.event.ActionListener() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) {
                ij.Prefs.set(stickyKey, sticky.isSelected());
                ij.Prefs.savePreferences();
            }
        });
        advancedPanel.add(sticky);

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
     * Routes a body-row component to the active advanced panel if one is
     * open, otherwise to the default {@code contentPanel}. Sequential
     * retrieval lists are populated independently by the public {@code add*}
     * helpers, so visibility changes never affect retrieval order.
     */
    private void addToBody(Component c) {
        if (currentAdvancedPanel != null) {
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
        }
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
        Dimension pref = btn.getPreferredSize();
        btn.setPreferredSize(new Dimension(Math.max(90, pref.width + 12), 28));
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
        Dimension pref = btn.getPreferredSize();
        btn.setPreferredSize(new Dimension(Math.max(90, pref.width + 12), 28));
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
            statusLabel.setForeground(new Color(40, 110, 70));
            statusLabel.setFont(statusLabel.getFont().deriveFont(Font.PLAIN, 12f));
            statusLabel.setBorder(new EmptyBorder(2, 12, 4, 12));
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
        return toggles.get(toggleIndex++).isSelected();
    }

    public String getNextString() {
        return textFields.get(textFieldIndex++).getText();
    }

    public String getNextChoice() {
        return (String) combos.get(comboIndex++).getSelectedItem();
    }

    public double getNextNumber() {
        String text = numericFields.get(numericFieldIndex++).getText().trim();
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
        row.setBorder(new EmptyBorder(0, 4, 0, 4));
        return row;
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
                BorderFactory.createEmptyBorder(3, 10, 3, 10)));
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
