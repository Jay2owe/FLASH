package flash.pipeline.ui.config;

import flash.pipeline.help.SetupHelpCatalog;
import flash.pipeline.help.SetupHelpTopic;
import flash.pipeline.ui.preview.PreviewPairPanel;
import ij.ImagePlus;
import ij.plugin.Duplicator;

import flash.pipeline.ui.FlashTheme;

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
    public static final String ENHANCED_CLASSICAL = "Enhanced Classical";
    public static final String STARDIST = "StarDist 3D";
    public static final String CELLPOSE = "Cellpose";
    public static final String[] OPTIONS = new String[]{CLASSICAL, ENHANCED_CLASSICAL, STARDIST, CELLPOSE};

    public interface MethodStore {
        String getChoice();
        boolean selectChoice(String choice);
    }

    private static final Color HELP_COLOR = FlashTheme.TEXT_HELP;

    private final MethodStore methodStore;
    private ConfigQcActions actions;
    private MethodChoicePanel choicePanel;
    private ImagePlus previewSource;

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
    public SetupHelpTopic helpTopic() {
        return SetupHelpCatalog.SEGMENTATION_METHOD;
    }

    @Override
    public boolean controlsCanExpand() {
        return true;
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
    public void onEnter(ConfigQcContext context, PreviewPairPanel preview) {
        closePreviewSource();
        previewSource = duplicateSelectedChannel(context);
        if (preview == null || previewSource == null) {
            return;
        }
        preview.setOriginal(previewSource);
        preview.setAdjusted(null);
        preview.setAdjustedState(PreviewPairPanel.PreviewState.EMPTY,
                "Choose a segmentation method.");
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

    @Override
    public void onLeave(ConfigQcContext context) {
        closePreviewSource();
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
        if (ENHANCED_CLASSICAL.equals(choice)) return EnhancedClassicalSegmentationStage.class.getName();
        if (STARDIST.equals(choice)) return StarDistParameterStage.class.getName();
        if (CELLPOSE.equals(choice)) return CellposeParameterStage.class.getName();
        return ClassicalSegmentationStage.class.getName();
    }

    private void setStatus(String text) {
        if (actions != null) {
            actions.setStatus(text);
        }
    }

    private ImagePlus duplicateSelectedChannel(ConfigQcContext context) {
        ImagePlus source = context == null ? null : context.getCurrentImagePlus();
        if (source == null) return null;

        int channel = Math.max(1, context.getChannelNumber());
        try {
            int channels = Math.max(1, source.getNChannels());
            int slices = Math.max(1, source.getNSlices());
            int frames = Math.max(1, source.getNFrames());
            int selected = Math.min(channel, channels);
            ImagePlus duplicate = new Duplicator().run(
                    source,
                    selected,
                    selected,
                    1,
                    slices,
                    1,
                    frames);
            if (duplicate != null) {
                duplicate.setTitle(previewTitle(context, selected));
                return duplicate;
            }
        } catch (RuntimeException e) {
            // Fall through to a normal duplicate so the UI still has a preview.
        }

        try {
            ImagePlus duplicate = source.duplicate();
            if (duplicate != null) {
                int selected = Math.min(channel, Math.max(1, duplicate.getNChannels()));
                duplicate.setPosition(selected, 1, 1);
                duplicate.setTitle(previewTitle(context, selected));
                return duplicate;
            }
        } catch (RuntimeException e) {
            return null;
        }
        return null;
    }

    private String previewTitle(ConfigQcContext context, int channel) {
        String imageName = context == null ? "" : context.getCurrentImageDisplayName();
        if (imageName == null || imageName.trim().isEmpty()) {
            imageName = "Selected image";
        }
        return "Segmentation source C" + channel + " - " + imageName;
    }

    private void closePreviewSource() {
        if (previewSource != null) {
            previewSource.flush();
            previewSource = null;
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
                            + "Next screen: set the signal threshold and object size filter.<br>"
                            + "Best for: bright, clean stains where objects stand out clearly from background and a consistent threshold can be applied across images. Works well for puncta, plaques, or well-separated labelled structures.<br>"
                            + "Watch out for: touching objects may merge, and uneven background can make one threshold unreliable.");
            add(Box.createVerticalStrut(6));
            addCard(group, ENHANCED_CLASSICAL,
                    "Enhanced Classical",
                    "Threshold-based 3D Objects Counter segmentation followed by optional 3D morphology filters.<br>"
                            + "Next screen: set the signal threshold, object size filter, and shape or intensity predicates.<br>"
                            + "Best for: classical segmentations that find the right objects plus debris, elongated fragments, or non-round structures that can be removed by 3D measurements.<br>"
                            + "Watch out for: morphology filters depend on calibration and object shape quality.");
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
                    BorderFactory.createLineBorder(FlashTheme.BORDER),
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
            if (ENHANCED_CLASSICAL.equals(choice)) return ENHANCED_CLASSICAL;
            if (STARDIST.equals(choice)) return STARDIST;
            if (CELLPOSE.equals(choice)) return CELLPOSE;
            return CLASSICAL;
        }
    }
}
