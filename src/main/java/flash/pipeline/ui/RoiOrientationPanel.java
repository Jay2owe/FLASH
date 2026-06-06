package flash.pipeline.ui;

import flash.pipeline.naming.OrientationManifestRow;
import flash.pipeline.orientation.BroadcastScope;
import flash.pipeline.orientation.BroadcastRule;
import flash.pipeline.orientation.OrientationBatchController;
import flash.pipeline.orientation.OrientationPreset;
import flash.pipeline.orientation.OrientationTransformState;
import ij.ImagePlus;
import ij.gui.ImageWindow;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;
import java.awt.Color;
import java.awt.Dialog;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Frame;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsEnvironment;
import java.awt.Insets;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.SecondaryLoop;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Modeless draw confirmation and rotate/flip controls shown beside each ROI
 * drawing image.
 */
public final class RoiOrientationPanel {
    private static final Color BG_COLOR = FlashTheme.SURFACE;
    private static final Color BORDER_COLOR = FlashTheme.BORDER_STRONG;
    private static final Color STATUS_COLOR = FlashTheme.TEXT_SUBHEADER;

    private final JDialog dialog;
    private final OrientationActionTarget target;
    private final OrientationBatchController controller;
    private final PresetNamePrompt presetNamePrompt;
    private final JLabel statusLabel;
    private JLabel ruleStatusLabel;
    private JPanel ruleStatusRow;
    private final JButton lutToggleButton;
    private final JButton brightnessContrastButton;
    private JButton repeatLastButton;
    private JButton savePresetButton;
    private JButton thisImageButton;
    private JButton hemisphereRuleButton;
    private JButton allImagesButton;
    private JButton mirrorRuleButton;
    private JButton clearRuleButton;
    private JPanel reuseRow;
    private final Map<String, JButton> presetButtons =
            new LinkedHashMap<String, JButton>();
    private final Object resultLock = new Object();
    private DrawDialogResult result;
    private SecondaryLoop waitLoop;

    public enum OrientationAction {
        ROTATE_LEFT,
        ROTATE_RIGHT,
        FLIP_HORIZONTAL,
        FLIP_VERTICAL,
        RESET
    }

    public enum DrawDialogResult {
        CONFIRMED,
        CANCELLED
    }

    public interface OrientationActionTarget {
        OrientationTransformState getState();
        void setState(OrientationTransformState state);
        void redrawFromState();
        void clearUnsavedRoiAfterOrientationChange();
        String statusText();
        default boolean displayControlsAvailable() { return false; }
        default String lutToggleButtonText() { return "Grey LUT"; }
        default String lutToggleButtonToolTipText() {
            return "Toggle between grey and the selected channel LUT.";
        }
        default void toggleDisplayLut() {}
        default void adjustBrightnessContrast() {}
    }

    interface PresetNamePrompt {
        String askPresetName(String suggestion);
    }

    public RoiOrientationPanel(Window owner, OrientationActionTarget target) {
        this(owner, target, "", "", null);
    }

    public RoiOrientationPanel(Window owner, OrientationActionTarget target,
                               OrientationBatchController controller) {
        this(owner, target, "", "", controller);
    }

    public RoiOrientationPanel(Window owner, OrientationActionTarget target,
                               String imageProgress, String imageTitle) {
        this(owner, target, imageProgress, imageTitle, null);
    }

    public RoiOrientationPanel(Window owner, OrientationActionTarget target,
                               String imageProgress, String imageTitle,
                               OrientationBatchController controller) {
        this(owner, target, imageProgress, imageTitle, controller, null);
    }

    RoiOrientationPanel(Window owner, OrientationActionTarget target,
                        String imageProgress, String imageTitle,
                        OrientationBatchController controller,
                        PresetNamePrompt presetNamePrompt) {
        this.target = target;
        this.controller = controller;
        this.presetNamePrompt = presetNamePrompt;
        if (GraphicsEnvironment.isHeadless()) {
            this.dialog = null;
            this.statusLabel = null;
            this.lutToggleButton = null;
            this.brightnessContrastButton = null;
            return;
        }

        this.dialog = owner == null
                ? new JDialog((Frame) null, "Draw ROI and Orientation", false)
                : new JDialog(owner, "Draw ROI and Orientation",
                        Dialog.ModalityType.MODELESS);
        this.dialog.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        this.dialog.setAlwaysOnTop(false);
        this.dialog.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                finish(DrawDialogResult.CANCELLED);
            }
        });

        JPanel body = new JPanel();
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
        body.setBackground(BG_COLOR);
        body.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER_COLOR),
                FlashTheme.pad(8, 10, 8, 10)));

        JLabel instructionLabel = new JLabel(instructionHtml(imageProgress, imageTitle));
        instructionLabel.setAlignmentX(JLabel.LEFT_ALIGNMENT);

        JPanel buttonRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        buttonRow.setAlignmentX(JPanel.LEFT_ALIGNMENT);
        buttonRow.setOpaque(false);
        buttonRow.add(button("Rotate left", OrientationAction.ROTATE_LEFT));
        buttonRow.add(button("Rotate right", OrientationAction.ROTATE_RIGHT));
        buttonRow.add(button("Flip horizontal", OrientationAction.FLIP_HORIZONTAL));
        buttonRow.add(button("Flip vertical", OrientationAction.FLIP_VERTICAL));
        buttonRow.add(button("Reset", OrientationAction.RESET));

        JLabel reuseHeader = null;
        JScrollPane reuseScroll = null;
        JLabel applyHeader = null;
        JPanel applyToRow = null;
        JPanel nextRuleStatusRow = null;
        JLabel nextRuleStatusLabel = null;
        JButton nextClearRuleButton = null;
        if (controller != null) {
            reuseHeader = sectionHeader("Reuse");
            reuseScroll = buildReuseScrollPane();
            applyHeader = sectionHeader(
                    "Apply current orientation to (auto-applies to later images)");
            applyToRow = buildApplyToRow();

            nextRuleStatusLabel = new JLabel();
            nextRuleStatusLabel.setForeground(STATUS_COLOR);
            nextRuleStatusLabel.setFont(nextRuleStatusLabel.getFont().deriveFont(11f));

            nextClearRuleButton = new JButton("clear");
            nextClearRuleButton.setFocusPainted(false);
            nextClearRuleButton.setMargin(new Insets(1, 6, 1, 6));
            nextClearRuleButton.addActionListener(e -> {
                controller.clearRule();
                refreshStatus();
            });

            nextRuleStatusRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
            nextRuleStatusRow.setAlignmentX(JPanel.LEFT_ALIGNMENT);
            nextRuleStatusRow.setOpaque(false);
            nextRuleStatusRow.add(nextRuleStatusLabel);
            nextRuleStatusRow.add(nextClearRuleButton);
            nextRuleStatusRow.setVisible(false);
        }
        this.ruleStatusLabel = nextRuleStatusLabel;
        this.clearRuleButton = nextClearRuleButton;
        this.ruleStatusRow = nextRuleStatusRow;

        JPanel displayRow = null;
        JButton nextLutToggleButton = null;
        JButton nextBrightnessContrastButton = null;
        if (target != null && target.displayControlsAvailable()) {
            displayRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
            displayRow.setAlignmentX(JPanel.LEFT_ALIGNMENT);
            displayRow.setOpaque(false);

            nextLutToggleButton = new JButton(displayLutToggleText());
            nextLutToggleButton.setFocusPainted(false);
            nextLutToggleButton.setToolTipText(displayLutToggleToolTipText());
            nextLutToggleButton.addActionListener(e -> performDisplayLutToggle());
            displayRow.add(nextLutToggleButton);

            nextBrightnessContrastButton = new JButton("Adjust Brightness/Contrast");
            nextBrightnessContrastButton.setFocusPainted(false);
            nextBrightnessContrastButton.addActionListener(e -> performBrightnessContrastAction());
            displayRow.add(nextBrightnessContrastButton);
        }
        this.lutToggleButton = nextLutToggleButton;
        this.brightnessContrastButton = nextBrightnessContrastButton;

        statusLabel = new JLabel(statusText());
        statusLabel.setForeground(STATUS_COLOR);
        statusLabel.setFont(statusLabel.getFont().deriveFont(11f));
        statusLabel.setAlignmentX(JLabel.LEFT_ALIGNMENT);

        JButton cancelButton = new JButton("Cancel ROI run");
        cancelButton.setFocusPainted(false);
        cancelButton.addActionListener(e -> finish(DrawDialogResult.CANCELLED));

        JButton confirmButton = new JButton("Finish drawing");
        confirmButton.setFocusPainted(false);
        confirmButton.addActionListener(e -> finish(DrawDialogResult.CONFIRMED));

        JPanel confirmationRow = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        confirmationRow.setAlignmentX(JPanel.LEFT_ALIGNMENT);
        confirmationRow.setOpaque(false);
        confirmationRow.add(cancelButton);
        confirmationRow.add(confirmButton);

        body.add(instructionLabel);
        body.add(Box.createVerticalStrut(8));
        body.add(buttonRow);
        if (reuseHeader != null && reuseScroll != null
                && applyHeader != null && applyToRow != null) {
            body.add(Box.createVerticalStrut(8));
            body.add(reuseHeader);
            body.add(Box.createVerticalStrut(3));
            body.add(reuseScroll);
            body.add(Box.createVerticalStrut(7));
            body.add(applyHeader);
            body.add(Box.createVerticalStrut(3));
            body.add(applyToRow);
        }
        if (displayRow != null) {
            body.add(Box.createVerticalStrut(6));
            body.add(displayRow);
        }
        body.add(Box.createVerticalStrut(6));
        body.add(statusLabel);
        if (nextRuleStatusRow != null) {
            body.add(Box.createVerticalStrut(2));
            body.add(nextRuleStatusRow);
        }
        body.add(Box.createVerticalStrut(10));
        body.add(confirmationRow);
        dialog.getContentPane().add(body);
        dialog.getRootPane().setDefaultButton(confirmButton);
        refreshBatchUi();
    }

    public void showNear(ImagePlus image) {
        if (dialog == null) return;
        dialog.pack();
        Dimension packedSize = dialog.getSize();
        Dimension displaySize = new Dimension(
                Math.max(560, packedSize.width),
                packedSize.height);
        dialog.setMinimumSize(displaySize);
        dialog.setSize(displaySize);
        ImageWindow window = image == null ? null : image.getWindow();
        if (window != null) {
            Rectangle bounds = window.getBounds();
            Rectangle screenBounds = usableScreenBounds(window.getGraphicsConfiguration());
            dialog.setLocation(dialogLocationNearImage(bounds, displaySize, screenBounds));
        } else if (dialog.getOwner() != null) {
            dialog.setLocationRelativeTo(dialog.getOwner());
        } else {
            dialog.setLocationByPlatform(true);
        }
        refreshStatus();
        refreshDisplayButtons();
        dialog.setVisible(true);
        dialog.toFront();
    }

    public DrawDialogResult showNearAndWait(ImagePlus image) {
        if (dialog == null) return DrawDialogResult.CANCELLED;
        showNear(image);
        waitForResult();
        return resultOrCancelled();
    }

    public void close() {
        finish(DrawDialogResult.CANCELLED);
    }

    void performOrientationAction(OrientationAction action) {
        applyAction(target, action);
        if (controller != null && target != null) {
            controller.noteManualState(target.getState());
        }
        refreshStatus();
    }

    void performDisplayLutToggle() {
        if (target != null) {
            target.toggleDisplayLut();
        }
        refreshDisplayButtons();
    }

    void performBrightnessContrastAction() {
        if (target != null) {
            target.adjustBrightnessContrast();
        }
    }

    static String instructionHtml(String imageProgress, String imageTitle) {
        String progress = safeTrim(imageProgress);
        String title = safeTrim(imageTitle);
        StringBuilder html = new StringBuilder("<html><body style='width:430px'>");
        if (!progress.isEmpty()) {
            html.append("<b>").append(escapeHtml(progress)).append("</b><br>");
        }
        if (!title.isEmpty()) {
            html.append("Draw ROI for: ")
                    .append(escapeHtml(title))
                    .append("<br><br>");
        }
        html.append("Draw or edit the ROI in ImageJ. Use orientation buttons ")
                .append("if needed, then click <b>Finish drawing</b> to lock ")
                .append("in this image's ROI.");
        html.append("</body></html>");
        return html.toString();
    }

    public static void applyAction(OrientationActionTarget target,
                                   OrientationAction action) {
        if (target == null || action == null) return;
        OrientationTransformState current = target.getState();
        if (current == null) current = OrientationTransformState.identity();
        OrientationTransformState next = stateAfter(current, action);
        if (sameState(current, next)) return;
        target.clearUnsavedRoiAfterOrientationChange();
        target.setState(next);
        target.redrawFromState();
    }

    public static OrientationTransformState stateAfter(OrientationTransformState current,
                                                       OrientationAction action) {
        OrientationTransformState state = current == null
                ? OrientationTransformState.identity()
                : current;
        if (action == null) return state;
        switch (action) {
            case ROTATE_LEFT:
                return state.rotateLeft();
            case ROTATE_RIGHT:
                return state.rotateRight();
            case FLIP_HORIZONTAL:
                return state.flipHorizontal();
            case FLIP_VERTICAL:
                return state.flipVertical();
            case RESET:
                return state.reset();
            default:
                return state;
        }
    }

    private JButton button(String label, final OrientationAction action) {
        JButton button = new JButton(label);
        button.setFocusPainted(false);
        button.addActionListener(e -> performOrientationAction(action));
        return button;
    }

    private JLabel sectionHeader(String label) {
        JLabel header = new JLabel(label);
        header.setForeground(FlashTheme.TEXT_HEADER);
        header.setFont(header.getFont().deriveFont(Font.BOLD));
        header.setAlignmentX(JLabel.LEFT_ALIGNMENT);
        return header;
    }

    private JScrollPane buildReuseScrollPane() {
        repeatLastButton = new JButton("Repeat last");
        repeatLastButton.setFocusPainted(false);
        repeatLastButton.addActionListener(e -> {
            controller.repeatLast();
            refreshStatus();
        });

        savePresetButton = new JButton("+ Save...");
        savePresetButton.setFocusPainted(false);
        savePresetButton.addActionListener(e -> savePresetFromPrompt());

        reuseRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        reuseRow.setOpaque(false);
        rebuildReuseRow();

        JScrollPane scrollPane = new JScrollPane(reuseRow);
        scrollPane.setAlignmentX(JPanel.LEFT_ALIGNMENT);
        scrollPane.setBorder(null);
        scrollPane.setOpaque(false);
        scrollPane.getViewport().setOpaque(false);
        scrollPane.setHorizontalScrollBarPolicy(
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        scrollPane.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_NEVER);
        scrollPane.setPreferredSize(new Dimension(520, 42));
        return scrollPane;
    }

    private JPanel buildApplyToRow() {
        thisImageButton = new JButton("This image");
        thisImageButton.setFocusPainted(false);
        thisImageButton.addActionListener(e -> {
            controller.clearRule();
            refreshStatus();
        });

        hemisphereRuleButton = new JButton("All LH/RH images");
        hemisphereRuleButton.setFocusPainted(false);
        hemisphereRuleButton.addActionListener(e -> {
            controller.setRule(BroadcastScope.SAME_HEMISPHERE);
            refreshStatus();
        });

        allImagesButton = new JButton("All images");
        allImagesButton.setFocusPainted(false);
        allImagesButton.addActionListener(e -> {
            controller.setRule(BroadcastScope.ALL_LITERAL);
            refreshStatus();
        });

        mirrorRuleButton = new JButton("All, mirror L<->R");
        mirrorRuleButton.setFocusPainted(false);
        mirrorRuleButton.addActionListener(e -> {
            controller.setRule(BroadcastScope.ALL_MIRRORED);
            refreshStatus();
        });

        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        row.setAlignmentX(JPanel.LEFT_ALIGNMENT);
        row.setOpaque(false);
        row.add(thisImageButton);
        row.add(hemisphereRuleButton);
        row.add(allImagesButton);
        row.add(mirrorRuleButton);
        return row;
    }

    private void rebuildReuseRow() {
        if (reuseRow == null) return;
        reuseRow.removeAll();
        presetButtons.clear();

        reuseRow.add(repeatLastButton);
        for (OrientationPreset preset : controller.presets()) {
            JButton presetButton = new JButton(preset.name);
            presetButton.setFocusPainted(false);
            presetButton.setToolTipText(transformDescription(preset.transform));
            presetButton.addActionListener(e -> {
                controller.applyPreset(preset);
                refreshStatus();
            });
            installPresetDeleteMenu(presetButton, preset.name);
            presetButtons.put(preset.name, presetButton);
            reuseRow.add(presetButton);
        }
        reuseRow.add(savePresetButton);
        refreshBatchUi();
        reuseRow.revalidate();
        reuseRow.repaint();
    }

    private void installPresetDeleteMenu(JButton presetButton, final String presetName) {
        presetButton.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                maybeShowDeleteMenu(e);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                maybeShowDeleteMenu(e);
            }

            private void maybeShowDeleteMenu(MouseEvent e) {
                if (!e.isPopupTrigger()) return;
                JPopupMenu menu = new JPopupMenu();
                JMenuItem delete = new JMenuItem("Delete preset");
                delete.addActionListener(event -> deletePreset(presetName));
                menu.add(delete);
                menu.show(e.getComponent(), e.getX(), e.getY());
            }
        });
    }

    private void savePresetFromPrompt() {
        String suggestion = defaultPresetName(target == null ? null : target.getState());
        String name;
        if (presetNamePrompt != null) {
            name = presetNamePrompt.askPresetName(suggestion);
        } else {
            Object answer = JOptionPane.showInputDialog(dialog,
                    "Preset name:",
                    "Save orientation preset",
                    JOptionPane.PLAIN_MESSAGE,
                    null,
                    null,
                    suggestion);
            name = answer == null ? null : answer.toString();
        }
        if (name == null || name.trim().isEmpty()) {
            return;
        }
        try {
            controller.savePreset(name.trim());
            rebuildReuseRow();
        } catch (IOException | IllegalArgumentException ex) {
            showBatchError("Could not save orientation preset", ex);
        }
    }

    private void deletePreset(String name) {
        try {
            controller.deletePreset(name);
            rebuildReuseRow();
        } catch (IOException ex) {
            showBatchError("Could not delete orientation preset", ex);
        }
    }

    private void refreshStatus() {
        if (statusLabel != null) {
            statusLabel.setText(statusText());
        }
        refreshBatchUi();
    }

    private void refreshBatchUi() {
        if (controller == null) return;

        if (repeatLastButton != null) {
            repeatLastButton.setEnabled(controller.hasLastApplied());
        }

        OrientationManifestRow.Hemisphere hemisphere = controller.currentHemisphere();
        boolean knownHemisphere = isKnownHemisphere(hemisphere);
        if (hemisphereRuleButton != null) {
            hemisphereRuleButton.setText("All " + hemisphereLabel(hemisphere) + " images");
            hemisphereRuleButton.setEnabled(knownHemisphere);
        }
        if (mirrorRuleButton != null) {
            mirrorRuleButton.setEnabled(knownHemisphere);
        }
        if (allImagesButton != null) {
            allImagesButton.setEnabled(true);
        }

        BroadcastRule activeRule = controller.activeRule();
        BroadcastScope activeScope = activeRule == null ? BroadcastScope.THIS_IMAGE : activeRule.scope;
        if (thisImageButton != null) {
            thisImageButton.setEnabled(activeRule != null);
        }
        setRuleButtonFont(thisImageButton, activeScope == BroadcastScope.THIS_IMAGE);
        setRuleButtonFont(hemisphereRuleButton, activeScope == BroadcastScope.SAME_HEMISPHERE);
        setRuleButtonFont(allImagesButton, activeScope == BroadcastScope.ALL_LITERAL);
        setRuleButtonFont(mirrorRuleButton, activeScope == BroadcastScope.ALL_MIRRORED);

        String ruleText = controller.ruleStatusText();
        boolean active = ruleText != null && !ruleText.trim().isEmpty();
        if (ruleStatusLabel != null) {
            ruleStatusLabel.setText(active ? ruleText : "");
        }
        if (ruleStatusRow != null) {
            ruleStatusRow.setVisible(active);
            ruleStatusRow.revalidate();
            ruleStatusRow.repaint();
        }
    }

    private void refreshDisplayButtons() {
        if (lutToggleButton != null) {
            lutToggleButton.setText(displayLutToggleText());
            lutToggleButton.setToolTipText(displayLutToggleToolTipText());
        }
        if (brightnessContrastButton != null) {
            brightnessContrastButton.setEnabled(target == null || target.displayControlsAvailable());
        }
    }

    private String displayLutToggleText() {
        String text = target == null ? null : target.lutToggleButtonText();
        return text == null || text.trim().isEmpty() ? "Grey LUT" : text;
    }

    private String displayLutToggleToolTipText() {
        String text = target == null ? null : target.lutToggleButtonToolTipText();
        return text == null || text.trim().isEmpty()
                ? "Toggle between grey and the selected channel LUT."
                : text;
    }

    private String statusText() {
        String text = target == null ? "" : target.statusText();
        return text == null || text.trim().isEmpty() ? "Orientation ready" : text;
    }

    private void showBatchError(String title, Exception ex) {
        String message = ex == null ? "Unknown error" : ex.getMessage();
        JOptionPane.showMessageDialog(dialog,
                message == null || message.trim().isEmpty() ? title : message,
                title,
                JOptionPane.WARNING_MESSAGE);
    }

    private void waitForResult() {
        synchronized (resultLock) {
            if (result != null) return;
        }

        if (SwingUtilities.isEventDispatchThread()) {
            SecondaryLoop loop =
                    Toolkit.getDefaultToolkit().getSystemEventQueue().createSecondaryLoop();
            synchronized (resultLock) {
                if (result != null) return;
                waitLoop = loop;
            }
            loop.enter();
            return;
        }

        synchronized (resultLock) {
            while (result == null) {
                try {
                    resultLock.wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    finish(DrawDialogResult.CANCELLED);
                    return;
                }
            }
        }
    }

    private DrawDialogResult resultOrCancelled() {
        synchronized (resultLock) {
            return result == null ? DrawDialogResult.CANCELLED : result;
        }
    }

    private void finish(DrawDialogResult next) {
        DrawDialogResult safeNext = next == null
                ? DrawDialogResult.CANCELLED
                : next;
        SecondaryLoop loopToExit;
        synchronized (resultLock) {
            if (result != null) return;
            result = safeNext;
            loopToExit = waitLoop;
            resultLock.notifyAll();
        }
        disposeDialog();
        if (loopToExit != null) {
            loopToExit.exit();
        }
    }

    private void disposeDialog() {
        if (dialog == null) return;
        if (SwingUtilities.isEventDispatchThread()) {
            dialog.dispose();
        } else {
            SwingUtilities.invokeLater(dialog::dispose);
        }
    }

    private static String safeTrim(String value) {
        return value == null ? "" : value.trim();
    }

    private static String escapeHtml(String value) {
        String text = value == null ? "" : value;
        StringBuilder escaped = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            switch (ch) {
                case '&':
                    escaped.append("&amp;");
                    break;
                case '<':
                    escaped.append("&lt;");
                    break;
                case '>':
                    escaped.append("&gt;");
                    break;
                case '"':
                    escaped.append("&quot;");
                    break;
                case '\'':
                    escaped.append("&#39;");
                    break;
                default:
                    escaped.append(ch);
                    break;
            }
        }
        return escaped.toString();
    }

    private static boolean sameState(OrientationTransformState a,
                                     OrientationTransformState b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        return a.rotateDegrees == b.rotateDegrees
                && a.flipHorizontal == b.flipHorizontal
                && a.flipVertical == b.flipVertical;
    }

    private static void setRuleButtonFont(JButton button, boolean selected) {
        if (button == null) return;
        Font font = button.getFont();
        if (font != null) {
            button.setFont(font.deriveFont(selected ? Font.BOLD : Font.PLAIN));
        }
    }

    private static boolean isKnownHemisphere(OrientationManifestRow.Hemisphere hemisphere) {
        return hemisphere == OrientationManifestRow.Hemisphere.LH
                || hemisphere == OrientationManifestRow.Hemisphere.RH;
    }

    private static String hemisphereLabel(OrientationManifestRow.Hemisphere hemisphere) {
        if (hemisphere == OrientationManifestRow.Hemisphere.LH) return "LH";
        if (hemisphere == OrientationManifestRow.Hemisphere.RH) return "RH";
        return "LH/RH";
    }

    private static String defaultPresetName(OrientationTransformState state) {
        OrientationTransformState safe = state == null
                ? OrientationTransformState.identity()
                : state;
        StringBuilder text = new StringBuilder();
        int degrees = safe.rotateDegrees.degrees();
        if (degrees != 0) {
            text.append("Rot").append(degrees);
        }
        if (safe.flipHorizontal) {
            appendWithSpace(text, "FlipH");
        }
        if (safe.flipVertical) {
            appendWithSpace(text, "FlipV");
        }
        return text.length() == 0 ? "Identity" : text.toString();
    }

    private static String transformDescription(OrientationTransformState state) {
        OrientationTransformState safe = state == null
                ? OrientationTransformState.identity()
                : state;
        StringBuilder text = new StringBuilder("Orientation: ");
        boolean any = false;
        int degrees = safe.rotateDegrees.degrees();
        if (degrees != 0) {
            text.append("rotated ").append(degrees).append(" deg");
            any = true;
        }
        if (safe.flipHorizontal) {
            if (any) text.append(", ");
            text.append("flipped H");
            any = true;
        }
        if (safe.flipVertical) {
            if (any) text.append(", ");
            text.append("flipped V");
            any = true;
        }
        if (!any) {
            text.append("original");
        }
        return text.toString();
    }

    private static void appendWithSpace(StringBuilder text, String value) {
        if (text.length() > 0) {
            text.append(' ');
        }
        text.append(value);
    }

    JButton repeatLastButtonForTests() {
        return repeatLastButton;
    }

    JButton presetButtonForTests(String name) {
        return presetButtons.get(name);
    }

    JButton savePresetButtonForTests() {
        return savePresetButton;
    }

    JButton thisImageButtonForTests() {
        return thisImageButton;
    }

    JButton hemisphereRuleButtonForTests() {
        return hemisphereRuleButton;
    }

    JButton allImagesButtonForTests() {
        return allImagesButton;
    }

    JButton mirrorRuleButtonForTests() {
        return mirrorRuleButton;
    }

    JButton clearRuleButtonForTests() {
        return clearRuleButton;
    }

    JLabel ruleStatusLabelForTests() {
        return ruleStatusLabel;
    }

    static Point dialogLocationNearImage(Rectangle imageBounds,
                                         Dimension dialogSize,
                                         Rectangle screenBounds) {
        Rectangle safeScreen = screenBounds == null
                ? new Rectangle(Toolkit.getDefaultToolkit().getScreenSize())
                : screenBounds;
        Dimension safeSize = dialogSize == null
                ? new Dimension(1, 1)
                : dialogSize;
        if (imageBounds == null) {
            return new Point(safeScreen.x, safeScreen.y);
        }

        int gap = 12;
        int x = imageBounds.x + imageBounds.width + gap;
        if (x + safeSize.width > safeScreen.x + safeScreen.width) {
            x = imageBounds.x - safeSize.width - gap;
        }
        if (x < safeScreen.x) {
            x = imageBounds.x;
        }

        int y = imageBounds.y;
        return new Point(
                clamp(x, safeScreen.x, safeScreen.x + safeScreen.width - safeSize.width),
                clamp(y, safeScreen.y, safeScreen.y + safeScreen.height - safeSize.height));
    }

    private static Rectangle usableScreenBounds(GraphicsConfiguration gc) {
        if (gc == null) {
            return new Rectangle(Toolkit.getDefaultToolkit().getScreenSize());
        }
        Rectangle bounds = gc.getBounds();
        Insets insets = Toolkit.getDefaultToolkit().getScreenInsets(gc);
        return new Rectangle(
                bounds.x + insets.left,
                bounds.y + insets.top,
                bounds.width - insets.left - insets.right,
                bounds.height - insets.top - insets.bottom);
    }

    private static int clamp(int value, int min, int max) {
        if (max < min) return min;
        return Math.max(min, Math.min(max, value));
    }
}
