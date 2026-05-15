package flash.pipeline.ui.config;

import flash.pipeline.help.SetupHelpDialog;
import flash.pipeline.help.SetupHelpTopic;
import flash.pipeline.ui.HelpButton;
import flash.pipeline.ui.preview.PreviewPairPanel;
import ij.ImagePlus;

import javax.swing.AbstractAction;
import javax.swing.ActionMap;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.InputMap;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSplitPane;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dialog;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GraphicsConfiguration;
import java.awt.Frame;
import java.awt.GraphicsEnvironment;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.SecondaryLoop;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;

public final class ConfigQcDialog {

    private static final Color BG_COLOR = new Color(245, 245, 245);
    private static final Color HEADER_COLOR = new Color(55, 71, 79);
    private static final Color HELP_COLOR = new Color(90, 90, 90);
    private static final Color PRIMARY_ACTION_BG = new Color(235, 248, 239);
    private static final Color PRIMARY_ACTION_FG = new Color(37, 103, 62);
    private static final Color PRIMARY_ACTION_BORDER = new Color(111, 173, 130);
    private static final Color CANCEL_ACTION_BG = new Color(252, 240, 240);
    private static final Color CANCEL_ACTION_FG = new Color(137, 44, 44);
    private static final Color CANCEL_ACTION_BORDER = new Color(196, 108, 108);
    private static final Color PREVIEW_ACTION_BG = new Color(232, 245, 253);
    private static final Color PREVIEW_ACTION_FG = new Color(15, 87, 140);
    private static final Color PREVIEW_ACTION_BORDER = new Color(71, 145, 196);
    private static final Color ACTIVE_STAGE_BG = new Color(222, 239, 231);
    private static final Color ACTIVE_STAGE_BORDER = new Color(111, 173, 130);
    private static final String PREVIEW_BUTTON_LABEL = "Run Preview";
    private static final String STALE_PREFIX = "\u25CF ";
    private static final Dimension MINIMUM_DIALOG_SIZE = new Dimension(1180, 820);
    private static final double DEFAULT_SCREEN_HEIGHT_FRACTION = 0.70;
    private static final double DIALOG_WIDTH_HEIGHT_RATIO =
            MINIMUM_DIALOG_SIZE.getWidth() / MINIMUM_DIALOG_SIZE.getHeight();
    private static final int EXPANDABLE_PREVIEW_MIN_HEIGHT = 300;
    private static final int EXPANDABLE_CONTROLS_MIN_HEIGHT = 220;

    private final Window owner;
    private final ConfigQcContext context;
    private final List<ConfigQcStage> stages;
    private final List<String> stagePathOverride;
    private final int stagePathCurrentIndexOverride;
    private final PreviewPairPanel previewPair;
    private final JPanel rootPanel = new JPanel(new BorderLayout(8, 8));
    private final JPanel mainBodyPanel = new JPanel(new BorderLayout(0, 2));
    private final JPanel stackedPreviewControlsPanel = new JPanel(new BorderLayout(0, 2));
    private final JPanel stageControlsPanel = new JPanel(new BorderLayout(0, 4));
    private final JPanel controlsPanel = new JPanel(new BorderLayout());
    private final JPanel stageBreadcrumbPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
    private final JLabel stageLabel = new JLabel(" ");
    private final JLabel channelLabel = new JLabel(" ");
    private final JLabel progressLabel = new JLabel(" ");
    private final JLabel statusLabel = new JLabel(" ");
    private final JButton stageHelpButton = HelpButton.question("Stage help is not available yet.");
    private final JButton backButton = new JButton("Back");
    private final JButton previousImageButton = new JButton("Previous image");
    private final JButton restartButton = new JButton("Restart");
    private final JButton skipImageButton = new JButton("Skip image");
    private final JButton skipStageButton = new JButton("Skip stage");
    private final JButton cancelButton = new JButton("Cancel");
    private final JButton lockInButton = new JButton("Lock in & Next");
    private final JDialog dialog;
    private final CountDownLatch closed = new CountDownLatch(1);
    private final ConfigQcActions actions = new DialogActions();

    private SecondaryLoop loop;
    private int stageIndex = -1;
    private boolean enteredStage;
    private JButton activePreviewButton;
    private boolean activePreviewButtonStale = true;
    private String currentImageDisplayName = " ";
    private String pendingNavigationStatus;
    private ConfigQcResult result = ConfigQcResult.CANCEL;
    private boolean controlsExpandable;
    private JSplitPane previewControlsSplit;
    private String stagePathText = " ";
    private String activeStagePathText = " ";
    private JLabel activeStagePathLabel;

    public ConfigQcDialog(Window owner, ConfigQcContext context, List<ConfigQcStage> stages, boolean modal) {
        this(owner, context, stages, modal, null, -1);
    }

    public ConfigQcDialog(Window owner, ConfigQcContext context, List<ConfigQcStage> stages,
                          boolean modal, List<String> stagePathOverride,
                          int stagePathCurrentIndexOverride) {
        this.owner = owner;
        this.context = context;
        this.stages = Collections.unmodifiableList(copyStages(stages));
        this.stagePathOverride = Collections.unmodifiableList(copyStagePath(stagePathOverride));
        this.stagePathCurrentIndexOverride = stagePathCurrentIndexOverride;
        this.previewPair = new PreviewPairPanel(owner, "Original Image", "Adjusted / output preview",
                PreviewPairPanel.PreviewLayout.HORIZONTAL_SLIM);
        this.dialog = GraphicsEnvironment.isHeadless() ? null : createDialog(owner, modal);
        buildContent();
        wireButtons();
        if (dialog != null) {
            dialog.setContentPane(rootPanel);
            dialog.setMinimumSize(MINIMUM_DIALOG_SIZE);
            dialog.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
            dialog.addWindowListener(new WindowAdapter() {
                @Override public void windowClosing(WindowEvent e) {
                    closeWithResult(ConfigQcResult.CANCEL);
                }

                @Override public void windowClosed(WindowEvent e) {
                    closed.countDown();
                    if (loop != null) {
                        loop.exit();
                    }
                }
            });
        }
        installKeyboardShortcuts();
        enterFirstApplicableStage();
    }

    public static ConfigQcDialog createModal(Window owner, ConfigQcContext context, List<ConfigQcStage> stages) {
        return new ConfigQcDialog(owner, context, stages, true);
    }

    public static ConfigQcDialog createModal(Window owner, ConfigQcContext context, List<ConfigQcStage> stages,
                                             List<String> stagePath, int currentStagePathIndex) {
        return new ConfigQcDialog(owner, context, stages, true, stagePath, currentStagePathIndex);
    }

    public static ConfigQcDialog createModeless(Window owner, ConfigQcContext context, List<ConfigQcStage> stages) {
        return new ConfigQcDialog(owner, context, stages, false);
    }

    public static ConfigQcDialog createModeless(Window owner, ConfigQcContext context, List<ConfigQcStage> stages,
                                                List<String> stagePath, int currentStagePathIndex) {
        return new ConfigQcDialog(owner, context, stages, false, stagePath, currentStagePathIndex);
    }

    static ConfigQcDialog createForTest(ConfigQcContext context, List<ConfigQcStage> stages) {
        return new ConfigQcDialog(null, context, stages, true);
    }

    static ConfigQcDialog createForTest(ConfigQcContext context, List<ConfigQcStage> stages,
                                        List<String> stagePath, int currentStagePathIndex) {
        return new ConfigQcDialog(null, context, stages, true, stagePath, currentStagePathIndex);
    }

    public ConfigQcResult showDialog() {
        if (dialog == null || closed.getCount() == 0) {
            return result;
        }
        dialog.pack();
        sizeDialogForScreen();
        dialog.setLocationRelativeTo(owner);
        dialog.setVisible(true);
        if (!dialog.isModal()) {
            waitForModelessClose();
        }
        return result;
    }

    public JComponent getContent() {
        return rootPanel;
    }

    private JDialog createDialog(Window owner, boolean modal) {
        if (owner == null) {
            return new JDialog((Frame) null, "Set Up Configuration QC", modal);
        }
        Dialog.ModalityType modality = modal
                ? Dialog.ModalityType.APPLICATION_MODAL
                : Dialog.ModalityType.MODELESS;
        return new JDialog(owner, "Set Up Configuration QC", modality);
    }

    private void buildContent() {
        rootPanel.setBackground(BG_COLOR);
        rootPanel.setBorder(BorderFactory.createEmptyBorder(10, 12, 8, 12));
        rootPanel.add(buildHeader(), BorderLayout.NORTH);
        rootPanel.add(buildMain(), BorderLayout.CENTER);
        rootPanel.add(buildFooter(), BorderLayout.SOUTH);
    }

    private JComponent buildHeader() {
        JPanel header = new JPanel(new BorderLayout(8, 4));
        header.setOpaque(false);
        stageLabel.setForeground(HEADER_COLOR);
        stageLabel.setFont(stageLabel.getFont().deriveFont(java.awt.Font.BOLD, 18f));
        stageBreadcrumbPanel.setOpaque(false);
        channelLabel.setForeground(HEADER_COLOR);
        progressLabel.setForeground(HELP_COLOR);

        JPanel left = new JPanel();
        left.setOpaque(false);
        left.setLayout(new BoxLayout(left, BoxLayout.Y_AXIS));
        stageBreadcrumbPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
        left.add(stageBreadcrumbPanel);

        JPanel right = new JPanel();
        right.setOpaque(false);
        right.setLayout(new BoxLayout(right, BoxLayout.Y_AXIS));
        channelLabel.setAlignmentX(JComponent.RIGHT_ALIGNMENT);
        progressLabel.setAlignmentX(JComponent.RIGHT_ALIGNMENT);
        right.add(channelLabel);
        right.add(progressLabel);

        header.add(left, BorderLayout.CENTER);
        header.add(right, BorderLayout.EAST);
        return header;
    }

    private JComponent buildMain() {
        JPanel main = new JPanel(new BorderLayout(8, 2));
        main.setOpaque(false);
        main.add(previewPair.previewToolstrip(), BorderLayout.NORTH);

        mainBodyPanel.setOpaque(false);
        stackedPreviewControlsPanel.setOpaque(false);
        stackedPreviewControlsPanel.add(previewPair, BorderLayout.CENTER);
        stageControlsPanel.setOpaque(false);
        stageControlsPanel.add(previewPair.sharedZRow(), BorderLayout.NORTH);
        controlsPanel.setOpaque(false);
        controlsPanel.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 0));
        stageControlsPanel.add(controlsPanel, BorderLayout.CENTER);
        stackedPreviewControlsPanel.add(stageControlsPanel, BorderLayout.SOUTH);
        mainBodyPanel.add(stackedPreviewControlsPanel, BorderLayout.CENTER);
        main.add(mainBodyPanel, BorderLayout.CENTER);
        return main;
    }

    private JComponent buildFooter() {
        JPanel footer = new JPanel(new GridBagLayout());
        footer.setOpaque(false);
        footer.setBorder(BorderFactory.createEmptyBorder(4, 0, 0, 0));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridy = 0;
        gbc.insets = new Insets(0, 0, 0, 6);
        gbc.fill = GridBagConstraints.NONE;

        gbc.gridx = 0;
        footer.add(backButton, gbc);
        gbc.gridx++;
        footer.add(previousImageButton, gbc);
        gbc.gridx++;
        footer.add(restartButton, gbc);
        gbc.gridx++;
        footer.add(skipImageButton, gbc);
        gbc.gridx++;
        footer.add(skipStageButton, gbc);

        gbc.gridx++;
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = new Insets(0, 8, 0, 8);
        statusLabel.setForeground(HELP_COLOR);
        footer.add(statusLabel, gbc);

        gbc.weightx = 0.0;
        gbc.fill = GridBagConstraints.NONE;
        gbc.insets = new Insets(0, 0, 0, 6);
        gbc.gridx++;
        footer.add(cancelButton, gbc);
        gbc.gridx++;
        gbc.insets = new Insets(0, 0, 0, 0);
        footer.add(lockInButton, gbc);
        return footer;
    }

    private void wireButtons() {
        stageHelpButton.addActionListener(e -> showCurrentStageHelp());
        backButton.addActionListener(e -> goBack());
        previousImageButton.addActionListener(e -> previousImage());
        restartButton.addActionListener(e -> restartCurrentStage());
        skipImageButton.addActionListener(e -> skipCurrentImage());
        skipStageButton.addActionListener(e -> skipCurrentStage());
        cancelButton.addActionListener(e -> closeWithResult(ConfigQcResult.CANCEL));
        lockInButton.addActionListener(e -> lockInAndAdvance());
        styleActionButton(lockInButton, PRIMARY_ACTION_BG, PRIMARY_ACTION_FG, PRIMARY_ACTION_BORDER);
        styleActionButton(cancelButton, CANCEL_ACTION_BG, CANCEL_ACTION_FG, CANCEL_ACTION_BORDER);
    }

    private void installKeyboardShortcuts() {
        if (dialog != null) {
            dialog.getRootPane().setDefaultButton(lockInButton);
        }
        InputMap inputMap = rootPanel.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
        ActionMap actionMap = rootPanel.getActionMap();
        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "qc-cancel");
        actionMap.put("qc-cancel", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) {
                cancelButton.doClick();
            }
        });
        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_F5, 0), "qc-run-preview");
        actionMap.put("qc-run-preview", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) {
                JButton button = activePreviewButton;
                if (button != null && button.isEnabled() && button.isShowing()) {
                    button.doClick();
                }
            }
        });
    }

    private void enterFirstApplicableStage() {
        int first = findNextApplicable(0);
        if (first < 0) {
            closeWithResult(ConfigQcResult.DONE);
            return;
        }
        stageIndex = first;
        context.resetCurrentImage();
        rebuildCurrentStage();
    }

    private void rebuildCurrentStage() {
        ConfigQcStage stage = currentStage();
        if (stage == null) {
            closeWithResult(ConfigQcResult.DONE);
            return;
        }
        detachPreviewImages();
        enteredStage = true;
        stageLabel.setText(stage.title());
        refreshStageBreadcrumb();
        refreshHeader();
        String navigationStatus = pendingNavigationStatus;
        pendingNavigationStatus = null;
        setStatus(" ");
        previewPair.setChannelLutName(context.getChannelLutName());
        previewPair.setDisplayControlsAvailable(stage.showPreviewDisplayControls());
        previewPair.resetStageToolstripState();
        previewPair.resetZ();
        previewPair.setOriginal(context.getCurrentImagePlus());
        previewPair.setAdjusted(null);
        previewPair.setAdjustedState(PreviewPairPanel.PreviewState.EMPTY, null);
        refreshButtons();

        controlsPanel.removeAll();
        clearPreviewButtonRegistration();
        JComponent controls = stage.buildControls(context, actions);
        if (controls != null) {
            controlsPanel.add(controls, BorderLayout.CENTER);
        }
        applyControlsExpansionMode(stage.controlsCanExpand());
        refreshStageLayout();
        stage.onEnter(context, previewPair);
        if (controlsExpandable) {
            configureExpandableControlArea();
        }
        if (navigationStatus != null && !navigationStatus.trim().isEmpty()) {
            setStatus(navigationStatus);
        }
        refreshStageLayout();
    }

    private void refreshHeader() {
        channelLabel.setText(context.getChannelLabel());
        currentImageDisplayName = context.getCurrentImageDisplayName();
        String imageName = context.getCurrentImageShortDisplayName();
        progressLabel.setText(imageProgressHeaderText(imageName));
        previewPair.setOriginalPreviewTitle("Original Image - " + imageName);
        previewPair.setAdjustedPreviewTitle("Adjusted / output preview");
    }

    private void refreshButtons() {
        boolean hasPreviousStage = findPreviousApplicable(stageIndex - 1) >= 0;
        backButton.setVisible(hasPreviousStage);
        backButton.setEnabled(hasPreviousStage);
        previousImageButton.setEnabled(currentStage() != null
                && context.hasImages()
                && context.getCurrentImageIndex() > 0);
        restartButton.setEnabled(currentStage() != null);
        skipImageButton.setEnabled(context.hasImages());
        skipStageButton.setEnabled(currentStage() != null);
        lockInButton.setText(isLastApplicableStage() && isLastImage()
                ? "Lock in & Done"
                : "Lock in & Next");
        previewPair.setDisplayControlsAvailable(currentStage() == null
                || currentStage().showPreviewDisplayControls());
    }

    private void refreshStageLayout() {
        controlsPanel.revalidate();
        controlsPanel.repaint();
        stageControlsPanel.revalidate();
        stageControlsPanel.repaint();
        mainBodyPanel.revalidate();
        mainBodyPanel.repaint();
        rootPanel.revalidate();
        rootPanel.repaint();
        SwingUtilities.invokeLater(new Runnable() {
            @Override public void run() {
                controlsPanel.revalidate();
                controlsPanel.repaint();
                stageControlsPanel.revalidate();
                stageControlsPanel.repaint();
                mainBodyPanel.revalidate();
                mainBodyPanel.repaint();
                rootPanel.revalidate();
                rootPanel.repaint();
            }
        });
    }

    private void applyControlsExpansionMode(boolean expandable) {
        boolean modeChanged = controlsExpandable != expandable;
        controlsExpandable = expandable;
        if (modeChanged) {
            mainBodyPanel.removeAll();
        }
        if (expandable) {
            if (previewControlsSplit == null || modeChanged) {
                previewControlsSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT,
                        previewPair, stageControlsPanel);
                previewControlsSplit.setBorder(BorderFactory.createEmptyBorder());
                previewControlsSplit.setContinuousLayout(true);
                previewControlsSplit.setOneTouchExpandable(true);
                mainBodyPanel.add(previewControlsSplit, BorderLayout.CENTER);
            }
            configureExpandableControlArea();
        } else if (modeChanged) {
            previewControlsSplit = null;
            resetExpandableControlArea();
            stackedPreviewControlsPanel.add(previewPair, BorderLayout.CENTER);
            stackedPreviewControlsPanel.add(stageControlsPanel, BorderLayout.SOUTH);
            mainBodyPanel.add(stackedPreviewControlsPanel, BorderLayout.CENTER);
        }
        mainBodyPanel.revalidate();
        mainBodyPanel.repaint();
    }

    private void configureExpandableControlArea() {
        stageControlsPanel.setPreferredSize(null);
        int controlsHeight = expandableControlsInitialHeight();
        previewPair.setMinimumSize(new Dimension(0, EXPANDABLE_PREVIEW_MIN_HEIGHT));
        stageControlsPanel.setMinimumSize(new Dimension(0, EXPANDABLE_CONTROLS_MIN_HEIGHT));
        stageControlsPanel.setPreferredSize(new Dimension(0, controlsHeight));
        if (previewControlsSplit != null) {
            previewControlsSplit.setResizeWeight(1.0);
            previewControlsSplit.resetToPreferredSizes();
            SwingUtilities.invokeLater(new Runnable() {
                @Override public void run() {
                    positionExpandableDivider();
                }
            });
        }
    }

    private void resetExpandableControlArea() {
        previewPair.setMinimumSize(null);
        stageControlsPanel.setMinimumSize(null);
        stageControlsPanel.setPreferredSize(null);
    }

    private int expandableControlsInitialHeight() {
        int preferred = stageControlsPanel.getPreferredSize().height;
        if (preferred <= 0) {
            preferred = controlsPanel.getPreferredSize().height
                    + previewPair.sharedZRow().getPreferredSize().height
                    + 12;
        }
        return Math.max(preferred, EXPANDABLE_CONTROLS_MIN_HEIGHT);
    }

    private void positionExpandableDivider() {
        if (previewControlsSplit == null) return;
        int height = previewControlsSplit.getHeight();
        if (height <= 0) return;
        int controlsHeight = stageControlsPanel.getPreferredSize().height;
        int divider = height - controlsHeight - previewControlsSplit.getDividerSize();
        divider = Math.max(EXPANDABLE_PREVIEW_MIN_HEIGHT, divider);
        previewControlsSplit.setDividerLocation(divider);
    }

    private void setStatus(String text) {
        statusLabel.setText(text == null || text.trim().isEmpty() ? " " : text);
    }

    private void markPreviewStale(String text) {
        previewPair.setAdjustedState(PreviewPairPanel.PreviewState.STALE, text);
        setPreviewButtonStale(true);
        setStatus(text);
    }

    private void setAdjustedPreview(ImagePlus image, String text) {
        previewPair.setAdjusted(image);
        previewPair.setAdjustedState(PreviewPairPanel.PreviewState.READY, text);
        setPreviewButtonStale(false);
        setStatus(text);
    }

    public void registerPreviewButton(JButton button) {
        activePreviewButton = button;
        activePreviewButtonStale = true;
        if (button == null) return;
        button.setText(PREVIEW_BUTTON_LABEL);
        applyPreviewButtonStaleStyle();
    }

    public void setPreviewButtonStale(boolean stale) {
        activePreviewButtonStale = stale;
        applyPreviewButtonStaleStyle();
    }

    public void setPreviewButtonRunning(boolean running) {
        JButton button = activePreviewButton;
        if (button == null) return;
        button.setEnabled(!running);
        styleActionButton(button, PREVIEW_ACTION_BG, PREVIEW_ACTION_FG, PREVIEW_ACTION_BORDER);
    }

    private void applyPreviewButtonStaleStyle() {
        JButton button = activePreviewButton;
        if (button == null) return;
        button.setText(activePreviewButtonStale
                ? STALE_PREFIX + PREVIEW_BUTTON_LABEL
                : PREVIEW_BUTTON_LABEL);
        styleActionButton(button, PREVIEW_ACTION_BG, PREVIEW_ACTION_FG, PREVIEW_ACTION_BORDER);
    }

    private void clearPreviewButtonRegistration() {
        activePreviewButton = null;
        activePreviewButtonStale = true;
    }

    private void lockInAndAdvance() {
        ConfigQcStage stage = currentStage();
        if (stage == null) return;
        if (!stage.lockIn(context)) {
            setStatus("Current settings were not locked in.");
            return;
        }
        advanceImageOrStage(ConfigQcResult.DONE);
    }

    private void skipCurrentImage() {
        ConfigQcStage stage = currentStage();
        if (stage == null) return;
        stage.skipCurrentImage(context);
        advanceImageOrStage(ConfigQcResult.SKIP_CURRENT_IMAGE);
    }

    private void skipCurrentStage() {
        ConfigQcStage stage = currentStage();
        if (stage == null) return;
        String skippedTitle = stage.title();
        stage.skipCurrentStage(context);
        context.consumeRequestedNextImageIndex();
        leaveCurrentStage();
        int next = findNextApplicable(stageIndex + 1);
        if (next < 0) {
            closeWithResult(ConfigQcResult.DONE);
            return;
        }
        stageIndex = next;
        context.resetCurrentImage();
        pendingNavigationStatus = "Skipped stage: " + safeStageTitle(skippedTitle) + ".";
        rebuildCurrentStage();
    }

    private void restartCurrentStage() {
        ConfigQcStage stage = currentStage();
        if (stage == null) return;
        stage.restartStage(context);
        context.resetCurrentImage();
        rebuildCurrentStage();
    }

    private void jumpToStage(String stageKey) {
        int target = findApplicableStageByKey(stageKey);
        if (target < 0) {
            setStatus("The selected segmentation method has no available settings screen.");
            return;
        }
        leaveCurrentStage();
        stageIndex = target;
        pendingNavigationStatus = "Segmentation method changed.";
        rebuildCurrentStage();
    }

    private void previousImage() {
        ConfigQcStage stage = currentStage();
        if (stage == null) return;
        if (!context.hasImages() || context.getCurrentImageIndex() <= 0) {
            setStatus("Already on the first image.");
            refreshButtons();
            return;
        }
        stage.previousImage(context);
        context.moveToPreviousImage();
        pendingNavigationStatus = context.getCurrentImageMovedBackStatusText();
        rebuildCurrentStage();
    }

    private void advanceImageOrStage(ConfigQcResult terminalIfComplete) {
        boolean forceStageComplete = false;
        Integer requestedNextImage = context.consumeRequestedNextImageIndex();
        if (requestedNextImage != null) {
            int target = requestedNextImage.intValue();
            if (target >= 0 && target < context.getImageCount()) {
                context.setCurrentImageIndex(target);
                pendingNavigationStatus = context.getCurrentImageMovedStatusText();
                rebuildCurrentStage();
                return;
            }
            forceStageComplete = true;
        }

        if (!forceStageComplete && context.moveToNextImage()) {
            pendingNavigationStatus = context.getCurrentImageMovedStatusText();
            rebuildCurrentStage();
            return;
        }
        leaveCurrentStage();
        int next = findNextApplicable(stageIndex + 1);
        if (next < 0) {
            closeWithResult(terminalIfComplete == ConfigQcResult.SKIP_CURRENT_IMAGE
                    ? ConfigQcResult.DONE
                    : terminalIfComplete);
            return;
        }
        stageIndex = next;
        context.resetCurrentImage();
        pendingNavigationStatus = context.getCurrentImageMovedStatusText();
        rebuildCurrentStage();
    }

    private void goBack() {
        leaveCurrentStage();
        int previous = findPreviousApplicable(stageIndex - 1);
        if (previous < 0) {
            closeWithResult(ConfigQcResult.BACK);
            return;
        }
        stageIndex = previous;
        context.resetCurrentImage();
        rebuildCurrentStage();
    }

    private void closeWithResult(ConfigQcResult result) {
        this.result = result == null ? ConfigQcResult.CANCEL : result;
        leaveCurrentStage();
        previewPair.disposeDisplayControlsDialog();
        if (dialog != null && dialog.isDisplayable()) {
            dialog.dispose();
        } else {
            closed.countDown();
            if (loop != null) {
                loop.exit();
            }
        }
    }

    private void leaveCurrentStage() {
        if (!enteredStage) return;
        ConfigQcStage stage = currentStage();
        enteredStage = false;
        detachPreviewImages();
        if (stage != null) {
            stage.onLeave(context);
        }
    }

    private void detachPreviewImages() {
        previewPair.clearImages();
    }

    private ConfigQcStage currentStage() {
        if (stageIndex < 0 || stageIndex >= stages.size()) {
            return null;
        }
        return stages.get(stageIndex);
    }

    private int findNextApplicable(int start) {
        for (int i = Math.max(0, start); i < stages.size(); i++) {
            if (stages.get(i).isApplicable(context)) {
                return i;
            }
        }
        return -1;
    }

    private int findPreviousApplicable(int start) {
        for (int i = Math.min(start, stages.size() - 1); i >= 0; i--) {
            if (stages.get(i).isApplicable(context)) {
                return i;
            }
        }
        return -1;
    }

    private int findApplicableStageByKey(String stageKey) {
        if (stageKey == null || stageKey.trim().isEmpty()) return -1;
        for (int i = 0; i < stages.size(); i++) {
            ConfigQcStage stage = stages.get(i);
            if (stage != null && stageKey.equals(stage.key()) && stage.isApplicable(context)) {
                return i;
            }
        }
        return -1;
    }

    private int firstApplicableIndex() {
        return findNextApplicable(0);
    }

    private boolean isLastApplicableStage() {
        return findNextApplicable(stageIndex + 1) < 0;
    }

    private boolean isLastImage() {
        return !context.hasImages() || context.getCurrentImageIndex() + 1 >= context.getImageCount();
    }

    private String imageProgressHeaderText(String imageName) {
        if (!context.hasImages()) {
            return context.getImageProgressText();
        }
        return context.getImageProgressText() + "    - " + imageName;
    }

    private static String safeStageTitle(String title) {
        String text = title == null ? "" : title.trim();
        return text.isEmpty() ? "current stage" : text;
    }

    private void refreshStageBreadcrumb() {
        stageBreadcrumbPanel.removeAll();
        activeStagePathLabel = null;
        List<String> path = currentStagePathLabels();
        int activeIndex = currentStagePathIndex(path);
        StringBuilder plain = new StringBuilder();
        for (int i = 0; i < path.size(); i++) {
            String labelText = path.get(i);
            if (i > 0) {
                JLabel separator = new JLabel(">");
                separator.setForeground(HELP_COLOR);
                separator.setBorder(BorderFactory.createEmptyBorder(0, 1, 0, 1));
                stageBreadcrumbPanel.add(separator);
                plain.append(" > ");
            }
            JLabel label = new JLabel(labelText);
            boolean active = i == activeIndex;
            styleStageBreadcrumbLabel(label, active);
            if (active) {
                activeStagePathLabel = label;
                activeStagePathText = labelText;
            }
            stageBreadcrumbPanel.add(label);
            plain.append(labelText);
        }
        if (path.isEmpty()) {
            activeStagePathText = " ";
            stagePathText = " ";
        } else {
            stagePathText = plain.toString();
        }
        refreshStageHelpButton();
        stageBreadcrumbPanel.add(Box.createHorizontalStrut(4));
        stageBreadcrumbPanel.add(stageHelpButton);
        stageBreadcrumbPanel.revalidate();
        stageBreadcrumbPanel.repaint();
    }

    private void refreshStageHelpButton() {
        SetupHelpTopic topic = currentHelpTopic();
        String tooltip = topic == null
                ? "Stage help is not available yet."
                : "About " + topic.title;
        stageHelpButton.setToolTipText(tooltip);
        stageHelpButton.getAccessibleContext().setAccessibleName(tooltip);
        stageHelpButton.setEnabled(topic != null);
        stageHelpButton.setVisible(topic != null);
    }

    private void showCurrentStageHelp() {
        SetupHelpTopic topic = currentHelpTopic();
        if (topic != null) {
            SetupHelpDialog.show(rootPanel, topic);
        }
    }

    private SetupHelpTopic currentHelpTopic() {
        ConfigQcStage stage = currentStage();
        return stage == null ? null : stage.helpTopic();
    }

    private void styleStageBreadcrumbLabel(JLabel label, boolean active) {
        label.setFont(label.getFont().deriveFont(
                active ? Font.BOLD : Font.PLAIN,
                active ? 18f : 16f));
        label.setForeground(active ? PRIMARY_ACTION_FG : HELP_COLOR);
        label.setOpaque(active);
        if (active) {
            label.setBackground(ACTIVE_STAGE_BG);
            label.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(ACTIVE_STAGE_BORDER),
                    BorderFactory.createEmptyBorder(3, 9, 3, 9)));
        } else {
            label.setBorder(BorderFactory.createEmptyBorder(4, 2, 4, 2));
        }
    }

    private List<String> currentStagePathLabels() {
        if (!stagePathOverride.isEmpty()) {
            return stagePathOverride;
        }
        List<String> labels = new ArrayList<String>();
        for (int i = 0; i < stages.size(); i++) {
            ConfigQcStage stage = stages.get(i);
            if (stage == null || !stage.isApplicable(context)) continue;
            labels.add(stage.title());
        }
        if (labels.isEmpty()) {
            ConfigQcStage stage = currentStage();
            if (stage != null) {
                labels.add(stage.title());
            }
        }
        return labels;
    }

    private int currentStagePathIndex(List<String> path) {
        if (path == null || path.isEmpty()) {
            return -1;
        }
        if (!stagePathOverride.isEmpty()) {
            if (stagePathCurrentIndexOverride < 0) return 0;
            return Math.min(stagePathCurrentIndexOverride, path.size() - 1);
        }
        int currentPosition = 0;
        for (int i = 0; i < stages.size(); i++) {
            ConfigQcStage stage = stages.get(i);
            if (stage == null || !stage.isApplicable(context)) continue;
            if (i == stageIndex) {
                return currentPosition;
            }
            currentPosition++;
        }
        return 0;
    }

    private void waitForModelessClose() {
        if (SwingUtilities.isEventDispatchThread()) {
            loop = Toolkit.getDefaultToolkit().getSystemEventQueue().createSecondaryLoop();
            loop.enter();
            return;
        }
        try {
            closed.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            closeWithResult(ConfigQcResult.CANCEL);
        }
    }

    private void sizeDialogForScreen() {
        if (dialog == null) return;
        Rectangle screen = availableScreenBounds();
        Dimension size = defaultDialogSize(screen, dialog.getSize());
        dialog.setMinimumSize(boundedMinimumDialogSize(screen));
        dialog.setSize(size);
    }

    private Rectangle availableScreenBounds() {
        GraphicsConfiguration configuration = dialog == null ? null : dialog.getGraphicsConfiguration();
        if (configuration == null && owner != null) {
            configuration = owner.getGraphicsConfiguration();
        }
        if (configuration == null) {
            return GraphicsEnvironment
                    .getLocalGraphicsEnvironment()
                    .getMaximumWindowBounds();
        }
        Rectangle bounds = configuration.getBounds();
        Insets insets = Toolkit.getDefaultToolkit().getScreenInsets(configuration);
        int width = Math.max(1, bounds.width - insets.left - insets.right);
        int height = Math.max(1, bounds.height - insets.top - insets.bottom);
        return new Rectangle(bounds.x + insets.left, bounds.y + insets.top, width, height);
    }

    private static Dimension defaultDialogSize(Rectangle screen, Dimension packedSize) {
        Rectangle bounds = screen == null
                ? new Rectangle(0, 0, MINIMUM_DIALOG_SIZE.width, MINIMUM_DIALOG_SIZE.height)
                : screen;
        int screenWidth = Math.max(1, bounds.width);
        int screenHeight = Math.max(1, bounds.height);

        int height = Math.max(1, (int) Math.round(screenHeight * DEFAULT_SCREEN_HEIGHT_FRACTION));
        int width = Math.max(1, (int) Math.round(height * DIALOG_WIDTH_HEIGHT_RATIO));

        int minimumHeight = Math.min(MINIMUM_DIALOG_SIZE.height, screenHeight);
        if (height < minimumHeight) {
            height = minimumHeight;
            width = Math.max(width, (int) Math.round(height * DIALOG_WIDTH_HEIGHT_RATIO));
        }

        if (width > screenWidth) {
            width = screenWidth;
            height = Math.min(height, Math.max(1, (int) Math.round(width / DIALOG_WIDTH_HEIGHT_RATIO)));
        }

        if (packedSize != null) {
            width = Math.max(width, Math.min(packedSize.width, screenWidth));
            height = Math.max(height, Math.min(packedSize.height, screenHeight));
        }
        width = Math.min(width, screenWidth);
        height = Math.min(height, screenHeight);
        return new Dimension(width, height);
    }

    private static Dimension boundedMinimumDialogSize(Rectangle screen) {
        if (screen == null) {
            return new Dimension(MINIMUM_DIALOG_SIZE);
        }
        int width = Math.min(MINIMUM_DIALOG_SIZE.width, Math.max(1, screen.width));
        int height = Math.min(MINIMUM_DIALOG_SIZE.height, Math.max(1, screen.height));
        return new Dimension(width, height);
    }

    private static List<ConfigQcStage> copyStages(List<ConfigQcStage> source) {
        List<ConfigQcStage> copy = new ArrayList<ConfigQcStage>();
        if (source != null) {
            for (int i = 0; i < source.size(); i++) {
                ConfigQcStage stage = source.get(i);
                if (stage != null) {
                    copy.add(stage);
                }
            }
        }
        return copy;
    }

    private static List<String> copyStagePath(List<String> source) {
        List<String> copy = new ArrayList<String>();
        if (source != null) {
            for (int i = 0; i < source.size(); i++) {
                String label = source.get(i);
                if (label != null && !label.trim().isEmpty()) {
                    copy.add(label.trim());
                }
            }
        }
        return copy;
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

    ConfigQcResult resultForTest() {
        return result;
    }

    int stageIndexForTest() {
        return stageIndex;
    }

    String stageTextForTest() {
        return stageLabel.getText();
    }

    String stagePathTextForTest() {
        return stagePathText;
    }

    String activeStagePathTextForTest() {
        return activeStagePathText;
    }

    boolean activeStagePathHighlightedForTest() {
        return activeStagePathLabel != null
                && activeStagePathLabel.isOpaque()
                && ACTIVE_STAGE_BG.equals(activeStagePathLabel.getBackground());
    }

    String channelTextForTest() {
        return channelLabel.getText();
    }

    String progressTextForTest() {
        return progressLabel.getText();
    }

    String imageTextForTest() {
        return currentImageDisplayName;
    }

    String statusTextForTest() {
        return statusLabel.getText();
    }

    JButton defaultButtonForTest() {
        return dialog == null ? null : dialog.getRootPane().getDefaultButton();
    }

    static Dimension minimumDialogSizeForTest() {
        return new Dimension(MINIMUM_DIALOG_SIZE);
    }

    static Dimension defaultDialogSizeForTest(Rectangle screen, Dimension packedSize) {
        return defaultDialogSize(screen, packedSize);
    }

    PreviewPairPanel previewForTest() {
        return previewPair;
    }

    JButton largeViewButtonForTest() {
        return previewPair.largeViewButton();
    }

    JButton displayControlsButtonForTest() {
        return previewPair.displayControlsButton();
    }

    JButton lockInButtonForTest() {
        return lockInButton;
    }

    JButton cancelButtonForTest() {
        return cancelButton;
    }

    JButton backButtonForTest() {
        return backButton;
    }

    JButton stageHelpButtonForTest() {
        return stageHelpButton;
    }

    SetupHelpTopic currentHelpTopicForTest() {
        return currentHelpTopic();
    }

    JButton previousImageButtonForTest() {
        return previousImageButton;
    }

    JButton skipImageButtonForTest() {
        return skipImageButton;
    }

    JButton skipStageButtonForTest() {
        return skipStageButton;
    }

    boolean controlsExpandableForTest() {
        return controlsExpandable;
    }

    JSplitPane previewControlsSplitForTest() {
        return previewControlsSplit;
    }

    int stageControlsPreferredHeightForTest() {
        return stageControlsPanel.getPreferredSize().height;
    }

    void lockInForTest() {
        lockInAndAdvance();
    }

    void skipForTest() {
        skipCurrentImage();
    }

    void skipStageForTest() {
        skipCurrentStage();
    }

    void restartForTest() {
        restartCurrentStage();
    }

    void previousImageForTest() {
        previousImage();
    }

    void backForTest() {
        goBack();
    }

    ConfigQcActions actionsForTest() {
        return actions;
    }

    private final class DialogActions implements ConfigQcActions {
        @Override public void setStatus(String text) {
            ConfigQcDialog.this.setStatus(text);
        }

        @Override public void markPreviewStale(String text) {
            ConfigQcDialog.this.markPreviewStale(text);
        }

        @Override public void setAdjustedPreview(ImagePlus image, String text) {
            ConfigQcDialog.this.setAdjustedPreview(image, text);
        }

        @Override public void nextImage() {
            ConfigQcDialog.this.advanceImageOrStage(ConfigQcResult.DONE);
        }

        @Override public void skipCurrentImage() {
            ConfigQcDialog.this.skipCurrentImage();
        }

        @Override public void restartStage() {
            ConfigQcDialog.this.restartCurrentStage();
        }

        @Override public void cancel() {
            ConfigQcDialog.this.closeWithResult(ConfigQcResult.CANCEL);
        }

        @Override public void jumpToStage(String stageKey) {
            ConfigQcDialog.this.jumpToStage(stageKey);
        }

        @Override public void registerPreviewButton(JButton button) {
            ConfigQcDialog.this.registerPreviewButton(button);
        }

        @Override public void setPreviewButtonStale(boolean stale) {
            ConfigQcDialog.this.setPreviewButtonStale(stale);
        }

        @Override public void setPreviewButtonRunning(boolean running) {
            ConfigQcDialog.this.setPreviewButtonRunning(running);
        }
    }
}
