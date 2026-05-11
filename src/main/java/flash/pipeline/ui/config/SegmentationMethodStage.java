package flash.pipeline.ui.config;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.ArrayList;
import java.util.List;

public final class SegmentationMethodStage implements ConfigQcStage {

    public static final String CLASSICAL = "Classical";
    public static final String STARDIST = "StarDist 3D";
    public static final String CELLPOSE = "Cellpose";
    public static final String[] OPTIONS = new String[]{CLASSICAL, STARDIST, CELLPOSE};

    public interface MethodStore {
        String getChoice();
        boolean selectChoice(String choice);
    }

    private static final Color HELP_COLOR = new Color(90, 90, 90);

    private final MethodStore methodStore;
    private ConfigQcActions actions;
    private MethodChoicePanel choicePanel;

    public SegmentationMethodStage(MethodStore methodStore) {
        if (methodStore == null) {
            throw new IllegalArgumentException("methodStore must not be null");
        }
        this.methodStore = methodStore;
    }

    @Override
    public String title() {
        return "Segmentation Method";
    }

    @Override
    public JComponent buildControls(ConfigQcContext context, ConfigQcActions actions) {
        this.actions = actions;
        JPanel panel = new JPanel(new BorderLayout(0, 10));
        panel.setOpaque(false);

        JPanel summary = new JPanel();
        summary.setOpaque(false);
        summary.setLayout(new BoxLayout(summary, BoxLayout.Y_AXIS));
        JLabel channel = new JLabel(context == null ? "Channel" : context.getChannelLabel());
        JLabel help = new JLabel("Choose how this channel should be segmented. You can change this later from any segmentation preview screen.");
        help.setForeground(HELP_COLOR);
        channel.setAlignmentX(Component.LEFT_ALIGNMENT);
        help.setAlignmentX(Component.LEFT_ALIGNMENT);
        summary.add(channel);
        summary.add(Box.createVerticalStrut(4));
        summary.add(help);
        panel.add(summary, BorderLayout.NORTH);

        choicePanel = new MethodChoicePanel(methodStore.getChoice(), false);
        panel.add(choicePanel, BorderLayout.CENTER);
        return panel;
    }

    @Override
    public boolean lockIn(ConfigQcContext context) {
        String choice = choicePanel == null ? methodStore.getChoice() : choicePanel.selectedChoice();
        if (!methodStore.selectChoice(choice)) {
            setStatus("Segmentation method was not changed.");
            return false;
        }
        if (context != null) {
            context.requestNextImageIndex(Integer.MAX_VALUE);
        }
        setStatus("Segmentation method: " + choice + ".");
        return true;
    }

    public static JComponent buildChangeMethodPanel(final MethodStore methodStore,
                                                    final ConfigQcActions actions) {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setOpaque(false);
        panel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createTitledBorder("Segmentation Method"),
                BorderFactory.createEmptyBorder(4, 6, 6, 6)));

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridy = 0;
        gbc.insets = new Insets(0, 0, 0, 8);
        gbc.anchor = GridBagConstraints.WEST;

        final JLabel current = new JLabel("Current: " + methodStore.getChoice());
        gbc.gridx = 0;
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        panel.add(current, gbc);

        JButton change = new JButton("Change Segmentation Method");
        gbc.gridx = 1;
        gbc.weightx = 0.0;
        gbc.fill = GridBagConstraints.NONE;
        panel.add(change, gbc);

        change.addActionListener(e -> {
            if (actions != null) {
                actions.setStatus("Choose a segmentation method for this channel.");
                actions.jumpToStage(SegmentationMethodStage.class.getName());
            }
        });
        return panel;
    }

    public static String stageKeyForChoice(String choice) {
        if (STARDIST.equals(choice)) return StarDistParameterStage.class.getName();
        if (CELLPOSE.equals(choice)) return CellposeParameterStage.class.getName();
        return ChannelThresholdStage.class.getName();
    }

    private void setStatus(String text) {
        if (actions != null) {
            actions.setStatus(text);
        }
    }

    private static final class MethodChoicePanel extends JPanel {
        private final List<JRadioButton> buttons = new ArrayList<JRadioButton>();

        MethodChoicePanel(String selectedChoice, boolean includeSessionNote) {
            setOpaque(false);
            setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
            ButtonGroup group = new ButtonGroup();

            addCard(group, CLASSICAL,
                    "Classical: 3D Objects Counter",
                    "Threshold-based segmentation using Fiji's 3D Objects Counter.<br>"
                            + "Next steps: set the signal threshold, then set the object size filter.<br>"
                            + "Best for: bright, clean stains where objects stand out clearly from background and a consistent threshold can be applied across images. Works well for puncta, plaques, or well-separated labelled structures.<br>"
                            + "Watch out for: touching objects may merge, and uneven background can make one threshold unreliable.");
            add(Box.createVerticalStrut(6));
            addCard(group, STARDIST,
                    "StarDist",
                    "AI-powered segmentation designed for round or star-convex objects, such as nuclei. It detects object shapes directly rather than relying only on pixel intensity.<br>"
                            + "Next step: tune StarDist detection settings and preview the label map.<br>"
                            + "Best for: crowded round objects, especially nuclei, where classical thresholding merges neighbouring objects.<br>"
                            + "Watch out for: irregular, branching, or very non-round structures may be poorly matched to the model.");
            add(Box.createVerticalStrut(6));
            addCard(group, CELLPOSE,
                    "Cellpose",
                    "AI-powered segmentation for cells and cell-like objects. It is more flexible than StarDist for irregular shapes and can use an optional companion channel.<br>"
                            + "Next step: choose the Cellpose model, expected diameter, detection settings, and GPU option.<br>"
                            + "Best for: whole cells, irregular cell bodies, complex morphology, or markers where thresholding does not separate objects cleanly.<br>"
                            + "Watch out for: very small puncta or simple bright objects may be faster and more reproducible with Classical.");

            select(firstKnownChoice(selectedChoice));
            if (includeSessionNote) {
                add(Box.createVerticalStrut(8));
                JLabel note = new JLabel("<html><body style='width:520px;color:#5a5a5a;'>Changing method keeps your previous settings for each method during this session. Only the final selected method is saved.</body></html>");
                note.setAlignmentX(Component.LEFT_ALIGNMENT);
                note.setOpaque(false);
                add(note);
            }
        }

        String selectedChoice() {
            for (JRadioButton button : buttons) {
                if (button.isSelected()) return button.getActionCommand();
            }
            return CLASSICAL;
        }

        private void select(String choice) {
            for (JRadioButton button : buttons) {
                if (button.getActionCommand().equals(choice)) {
                    button.setSelected(true);
                    return;
                }
            }
            if (!buttons.isEmpty()) {
                buttons.get(0).setSelected(true);
            }
        }

        private void addCard(ButtonGroup group, String choice, String title, String body) {
            JPanel card = new JPanel(new BorderLayout(4, 4));
            card.setOpaque(false);
            card.setAlignmentX(Component.LEFT_ALIGNMENT);
            card.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(new Color(210, 210, 210)),
                    BorderFactory.createEmptyBorder(8, 8, 8, 8)));

            JRadioButton radio = new JRadioButton(title);
            radio.setOpaque(false);
            radio.setActionCommand(choice);
            radio.setFont(radio.getFont().deriveFont(java.awt.Font.BOLD));
            group.add(radio);
            buttons.add(radio);
            card.add(radio, BorderLayout.NORTH);

            JLabel description = new JLabel("<html><body style='width:520px;color:#5a5a5a;'>"
                    + body + "</body></html>");
            description.setOpaque(false);
            card.add(description, BorderLayout.CENTER);
            add(card);
        }

        private static String firstKnownChoice(String choice) {
            if (STARDIST.equals(choice)) return STARDIST;
            if (CELLPOSE.equals(choice)) return CELLPOSE;
            return CLASSICAL;
        }
    }
}
