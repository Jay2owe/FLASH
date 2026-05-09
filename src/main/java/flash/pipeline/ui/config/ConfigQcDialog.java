package flash.pipeline.ui.config;

import flash.pipeline.ui.preview.PreviewPairPanel;
import ij.ImagePlus;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dialog;
import java.awt.Dimension;
import java.awt.Frame;
import java.awt.GraphicsEnvironment;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.SecondaryLoop;
import java.awt.Toolkit;
import java.awt.Window;
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
    private static final Dimension MINIMUM_DIALOG_SIZE = new Dimension(980, 680);

    private final Window owner;
    private final ConfigQcContext context;
    private final List<ConfigQcStage> stages;
    private final PreviewPairPanel previewPair;
    private final JPanel rootPanel = new JPanel(new BorderLayout(8, 8));
    private final JPanel controlsPanel = new JPanel(new BorderLayout());
    private final JLabel titleLabel = new JLabel("Set Up Configuration QC");
    private final JLabel stageLabel = new JLabel(" ");
    private final JLabel channelLabel = new JLabel(" ");
    private final JLabel progressLabel = new JLabel(" ");
    private final JLabel imageLabel = new JLabel(" ");
    private final JLabel statusLabel = new JLabel(" ");
    private final JButton backButton = new JButton("Back");
    private final JButton restartButton = new JButton("Restart stage");
    private final JButton skipButton = new JButton("Skip image");
    private final JButton cancelButton = new JButton("Cancel");
    private final JButton lockInButton = new JButton("Lock in & Next");
    private final JDialog dialog;
    private final CountDownLatch closed = new CountDownLatch(1);
    private final ConfigQcActions actions = new DialogActions();

    private SecondaryLoop loop;
    private int stageIndex = -1;
    private boolean enteredStage;
    private String pendingNavigationStatus;
    private ConfigQcResult result = ConfigQcResult.CANCEL;

    public ConfigQcDialog(Window owner, ConfigQcContext context, List<ConfigQcStage> stages, boolean modal) {
        this.owner = owner;
        this.context = context;
        this.stages = Collections.unmodifiableList(copyStages(stages));
        this.previewPair = new PreviewPairPanel(owner, "Original image", "Adjusted / output preview");
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
        enterFirstApplicableStage();
    }

    public static ConfigQcDialog createModal(Window owner, ConfigQcContext context, List<ConfigQcStage> stages) {
        return new ConfigQcDialog(owner, context, stages, true);
    }

    public static ConfigQcDialog createModeless(Window owner, ConfigQcContext context, List<ConfigQcStage> stages) {
        return new ConfigQcDialog(owner, context, stages, false);
    }

    static ConfigQcDialog createForTest(ConfigQcContext context, List<ConfigQcStage> stages) {
        return new ConfigQcDialog(null, context, stages, true);
    }

    public ConfigQcResult showDialog() {
        if (dialog == null) {
            return result;
        }
        dialog.pack();
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
        titleLabel.setForeground(HEADER_COLOR);
        titleLabel.setFont(titleLabel.getFont().deriveFont(java.awt.Font.BOLD, 18f));
        stageLabel.setForeground(HEADER_COLOR);
        stageLabel.setFont(stageLabel.getFont().deriveFont(java.awt.Font.BOLD, 14f));
        channelLabel.setForeground(HEADER_COLOR);
        progressLabel.setForeground(HELP_COLOR);
        imageLabel.setForeground(HELP_COLOR);

        JPanel left = new JPanel();
        left.setOpaque(false);
        left.setLayout(new BoxLayout(left, BoxLayout.Y_AXIS));
        left.add(titleLabel);
        left.add(stageLabel);

        JPanel right = new JPanel();
        right.setOpaque(false);
        right.setLayout(new BoxLayout(right, BoxLayout.Y_AXIS));
        channelLabel.setAlignmentX(JComponent.RIGHT_ALIGNMENT);
        progressLabel.setAlignmentX(JComponent.RIGHT_ALIGNMENT);
        imageLabel.setAlignmentX(JComponent.RIGHT_ALIGNMENT);
        right.add(channelLabel);
        right.add(progressLabel);
        right.add(imageLabel);

        header.add(left, BorderLayout.WEST);
        header.add(right, BorderLayout.EAST);
        return header;
    }

    private JComponent buildMain() {
        JPanel previewColumn = new JPanel(new BorderLayout());
        previewColumn.setOpaque(false);
        previewColumn.setMinimumSize(new Dimension(330, 1));
        previewColumn.add(previewPair, BorderLayout.CENTER);

        controlsPanel.setOpaque(false);
        controlsPanel.setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 0));
        JScrollPane controlsScroll = new JScrollPane(controlsPanel);
        controlsScroll.setBorder(BorderFactory.createEmptyBorder());
        controlsScroll.getViewport().setBackground(BG_COLOR);

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, previewColumn, controlsScroll);
        split.setResizeWeight(0.42);
        split.setDividerLocation(390);
        split.setBorder(BorderFactory.createEmptyBorder());
        return split;
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
        footer.add(restartButton, gbc);
        gbc.gridx++;
        footer.add(skipButton, gbc);
        gbc.gridx++;
        footer.add(previewPair.largeViewButton(), gbc);

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
        backButton.addActionListener(e -> goBack());
        restartButton.addActionListener(e -> restartCurrentStage());
        skipButton.addActionListener(e -> skipCurrentImage());
        cancelButton.addActionListener(e -> closeWithResult(ConfigQcResult.CANCEL));
        lockInButton.addActionListener(e -> lockInAndAdvance());
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
        enteredStage = true;
        stageLabel.setText(stage.title());
        refreshHeader();
        refreshButtons();
        String navigationStatus = pendingNavigationStatus;
        pendingNavigationStatus = null;
        setStatus(" ");
        previewPair.setChannelLutName(context.getChannelLutName());
        previewPair.resetZ();
        previewPair.setOriginal(context.getCurrentImagePlus());
        previewPair.setAdjusted(null);
        previewPair.setAdjustedState(PreviewPairPanel.PreviewState.EMPTY, null);

        controlsPanel.removeAll();
        JComponent controls = stage.buildControls(context, actions);
        if (controls != null) {
            controlsPanel.add(controls, BorderLayout.CENTER);
        }
        stage.onEnter(context, previewPair);
        if (navigationStatus != null && !navigationStatus.trim().isEmpty()) {
            setStatus(navigationStatus);
        }
        rootPanel.revalidate();
        rootPanel.repaint();
    }

    private void refreshHeader() {
        channelLabel.setText(context.getChannelLabel());
        progressLabel.setText(stageProgressText() + "    " + context.getImageProgressText());
        imageLabel.setText(context.getCurrentImageDisplayName());
    }

    private void refreshButtons() {
        backButton.setEnabled(findPreviousApplicable(stageIndex - 1) >= 0 || stageIndex == firstApplicableIndex());
        restartButton.setEnabled(currentStage() != null);
        skipButton.setEnabled(context.hasImages());
        lockInButton.setText(isLastApplicableStage() && isLastImage()
                ? "Lock in & Done"
                : "Lock in & Next");
    }

    private void setStatus(String text) {
        statusLabel.setText(text == null || text.trim().isEmpty() ? " " : text);
    }

    private void markPreviewStale(String text) {
        previewPair.setAdjustedState(PreviewPairPanel.PreviewState.STALE, text);
        setStatus(text);
    }

    private void setAdjustedPreview(ImagePlus image, String text) {
        previewPair.setAdjusted(image);
        previewPair.setAdjustedState(PreviewPairPanel.PreviewState.READY, text);
        setStatus(text);
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

    private void restartCurrentStage() {
        ConfigQcStage stage = currentStage();
        if (stage == null) return;
        stage.restartStage(context);
        context.resetCurrentImage();
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
        if (stage != null) {
            stage.onLeave(context);
        }
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

    private int firstApplicableIndex() {
        return findNextApplicable(0);
    }

    private boolean isLastApplicableStage() {
        return findNextApplicable(stageIndex + 1) < 0;
    }

    private boolean isLastImage() {
        return !context.hasImages() || context.getCurrentImageIndex() + 1 >= context.getImageCount();
    }

    private String stageProgressText() {
        int applicableCount = 0;
        int currentPosition = 0;
        for (int i = 0; i < stages.size(); i++) {
            if (!stages.get(i).isApplicable(context)) continue;
            applicableCount++;
            if (i == stageIndex) {
                currentPosition = applicableCount;
            }
        }
        if (applicableCount == 0) {
            return "No stages";
        }
        return "Stage " + currentPosition + " / " + applicableCount;
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

    ConfigQcResult resultForTest() {
        return result;
    }

    int stageIndexForTest() {
        return stageIndex;
    }

    String titleTextForTest() {
        return titleLabel.getText();
    }

    String stageTextForTest() {
        return stageLabel.getText();
    }

    String channelTextForTest() {
        return channelLabel.getText();
    }

    String progressTextForTest() {
        return progressLabel.getText();
    }

    String imageTextForTest() {
        return imageLabel.getText();
    }

    String statusTextForTest() {
        return statusLabel.getText();
    }

    PreviewPairPanel previewForTest() {
        return previewPair;
    }

    JButton largeViewButtonForTest() {
        return previewPair.largeViewButton();
    }

    void lockInForTest() {
        lockInAndAdvance();
    }

    void skipForTest() {
        skipCurrentImage();
    }

    void restartForTest() {
        restartCurrentStage();
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
    }
}
