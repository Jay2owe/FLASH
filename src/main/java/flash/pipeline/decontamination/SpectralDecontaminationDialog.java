package flash.pipeline.decontamination;

import flash.pipeline.bin.BinConfig;
import flash.pipeline.decontamination.wizard.SpectralDecontamPreset;
import flash.pipeline.decontamination.wizard.SpectralDecontamPresetIO;
import flash.pipeline.decontamination.wizard.SpectralDecontaminationWizard;
import flash.pipeline.ui.PipelineDialog;
import flash.pipeline.ui.wizard.SetupHelperButton;
import ij.IJ;

import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JPanel;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * First Spectral Decontamination screen: goal and channel-role selection.
 */
public class SpectralDecontaminationDialog {

    private static final String TITLE = "Spectral Decontamination";
    private static final String CURRENT_SETTINGS_LABEL = "Preset: current settings";

    private final File projectRoot;
    private final BinConfig binConfig;
    private SpectralDecontaminationConfig defaults;

    public SpectralDecontaminationDialog(BinConfig binConfig, SpectralDecontaminationConfig defaults) {
        this(null, binConfig, defaults);
    }

    public SpectralDecontaminationDialog(File projectRoot,
                                         BinConfig binConfig,
                                         SpectralDecontaminationConfig defaults) {
        if (binConfig == null) {
            throw new IllegalArgumentException("binConfig must not be null");
        }
        this.projectRoot = projectRoot;
        this.binConfig = binConfig;
        this.defaults = defaults == null
                ? SpectralDecontaminationConfig.defaults(binConfig.numChannels())
                : defaults.copy();
    }

    public SpectralDecontaminationConfig showDialog() {
        if (binConfig.numChannels() <= 0) {
            IJ.showMessage(TITLE, "No channels are available. Run Set Up Configuration first.");
            return null;
        }

        while (true) {
            DialogState state = showOnce(defaults);
            if (state == null) return null;

            List<String> errors = state.config.validate(binConfig.numChannels());
            if (errors.isEmpty()) {
                return state.config;
            }

            defaults = state.config;
            IJ.showMessage(TITLE, formatErrors(errors));
        }
    }

    public PreviewDecision showPreviewDialog(SpectralDecontaminationConfig config,
                                             List<SpectralPreviewRenderer.RenderedPreview> previews) {
        if (config == null) {
            throw new IllegalArgumentException("config must not be null");
        }

        PipelineDialog dialog = new PipelineDialog(TITLE);
        dialog.enableBackButton();
        dialog.addHeader("Preview");
        dialog.addMessage("Inspect the same correction stack that batch processing will run on a balanced subset.");
        dialog.addMessage("Controls and experimental conditions are grouped separately. "
                + "Use Back to adjust the correction stack before continuing.");
        dialog.addComponent(new SpectralPreviewPanel(previews));

        if (!dialog.showDialog()) {
            return dialog.wasBackPressed() ? PreviewDecision.BACK : PreviewDecision.CANCEL;
        }
        return PreviewDecision.ACCEPT;
    }

    private DialogState showOnce(SpectralDecontaminationConfig config) {
        PipelineDialog dialog = new PipelineDialog(TITLE);
        String[] channelChoices = channelChoices();
        JComboBox<String> presetDropdown = new JComboBox<String>(presetLabels());
        JButton saveAsPreset = new JButton("[Save as preset...]");
        JPanel helperRow = SetupHelperButton.createHeaderRow(
                "Decontamination",
                presetDropdown,
                saveAsPreset,
                new SetupHelperButton.WizardLauncher() {
                    @Override
                    public void run() {
                        dialog.closeWithAction("wizard");
                    }
                });
        saveAsPreset.addActionListener(e -> dialog.closeWithAction("save_preset"));
        dialog.addComponent(helperRow);

        dialog.addHeader("Goal");
        dialog.addChoice("Goal", SpectralDecontaminationConfig.Goal.labels(),
                config.getGoal().getLabel());

        dialog.addHeader("Target Channel");
        dialog.addChoice("Target channel", channelChoices,
                channelChoiceForIndex(clampTarget(config.getTargetChannelIndex()), channelChoices));

        dialog.addHeader("Bleed-through Channels");
        dialog.addMessage("Select channels that can leak into the target channel.");
        for (int i = 0; i < binConfig.numChannels(); i++) {
            dialog.addToggle(channelLabel(i), config.getBleedThroughChannelIndexes().contains(Integer.valueOf(i)));
        }

        dialog.addHeader("Autofluorescence Channels");
        dialog.addMessage("A non-target channel may be selected as both bleed-through and autofluorescence.");
        for (int i = 0; i < binConfig.numChannels(); i++) {
            dialog.addToggle(channelLabel(i), config.getAutofluorescenceChannelIndexes().contains(Integer.valueOf(i)));
        }

        dialog.addHeader("Excluded Channels");
        dialog.addMessage("Optional channels to ignore in later Spectral Decontamination steps.");
        for (int i = 0; i < binConfig.numChannels(); i++) {
            dialog.addToggle(channelLabel(i), config.getExcludedChannelIndexes().contains(Integer.valueOf(i)));
        }

        if (!dialog.showDialog()) {
            if ("wizard".equals(dialog.getActionCommand())) {
                SpectralDecontaminationConfig wizardConfig =
                        new SpectralDecontaminationWizard(projectRoot, binConfig, config).showDialog();
                return wizardConfig == null ? null : new DialogState(wizardConfig);
            }
            if ("save_preset".equals(dialog.getActionCommand())) {
                SpectralDecontaminationConfig selected = readSelectedConfig(dialog, config, channelChoices);
                savePreset(selected);
                return new DialogState(selected);
            }
            return null;
        }

        String selectedPreset = (String) presetDropdown.getSelectedItem();
        if (selectedPreset != null && !CURRENT_SETTINGS_LABEL.equals(selectedPreset)) {
            SpectralDecontaminationConfig presetConfig = loadPreset(selectedPreset);
            if (presetConfig != null) {
                return new DialogState(presetConfig);
            }
        }

        return new DialogState(readSelectedConfig(dialog, config, channelChoices));
    }

    private SpectralDecontaminationConfig readSelectedConfig(PipelineDialog dialog,
                                                             SpectralDecontaminationConfig config,
                                                             String[] channelChoices) {
        SpectralDecontaminationConfig selected = new SpectralDecontaminationConfig();
        selected.setVersion(SpectralDecontaminationConfig.CURRENT_VERSION);
        selected.setGoal(SpectralDecontaminationConfig.Goal.fromLabel(dialog.getNextChoice()));
        selected.setTargetChannelIndex(indexFromChoice(dialog.getNextChoice(), channelChoices));
        selected.setBleedThroughChannelIndexes(readToggles(dialog));
        selected.setAutofluorescenceChannelIndexes(readToggles(dialog));
        selected.setExcludedChannelIndexes(readToggles(dialog));
        selected.setConditionSource(config.getConditionSource());
        selected.setControlConditionNames(config.getControlConditionNames());
        selected.setExperimentalConditionNames(config.getExperimentalConditionNames());
        selected.setCorrectionPipeline(config.getCorrectionPipeline());
        selected.setFeatureSettings(config.getFeatureSettingsById());

        return selected;
    }

    private String[] presetLabels() {
        List<String> labels = new ArrayList<String>();
        labels.add(CURRENT_SETTINGS_LABEL);
        try {
            for (SpectralDecontamPreset preset : new SpectralDecontamPresetIO(projectRootOrUserDir()).listAll()) {
                labels.add(preset.getName());
            }
        } catch (IOException e) {
            IJ.log("Spectral Decontamination: could not list presets: " + e.getMessage());
        }
        return labels.toArray(new String[labels.size()]);
    }

    private SpectralDecontaminationConfig loadPreset(String name) {
        try {
            return new SpectralDecontamPresetIO(projectRootOrUserDir()).load(name).getPayload();
        } catch (IOException e) {
            IJ.showMessage(TITLE, "Could not load preset '" + name + "': " + e.getMessage());
            return null;
        }
    }

    private void savePreset(SpectralDecontaminationConfig config) {
        String name = IJ.getString("Preset name", "My Spectral Decontamination Preset");
        if (name == null || name.trim().isEmpty()) {
            return;
        }
        try {
            new SpectralDecontamPresetIO(projectRootOrUserDir()).save(new SpectralDecontamPreset(
                    name.trim(),
                    "Saved from Spectral Decontamination dialog.",
                    SpectralDecontamPreset.CURRENT_LIBRARY_VERSION,
                    config,
                    "",
                    "",
                    ""));
            IJ.showMessage(TITLE, "Preset saved: " + name.trim());
        } catch (IOException e) {
            IJ.showMessage(TITLE, "Could not save preset: " + e.getMessage());
        }
    }

    private File projectRootOrUserDir() {
        if (projectRoot != null) return projectRoot;
        String userDir = System.getProperty("user.dir");
        return userDir == null || userDir.trim().isEmpty() ? new File(".") : new File(userDir);
    }

    private List<Integer> readToggles(PipelineDialog dialog) {
        List<Integer> selected = new ArrayList<Integer>();
        for (int i = 0; i < binConfig.numChannels(); i++) {
            if (dialog.getNextBoolean()) {
                selected.add(Integer.valueOf(i));
            }
        }
        return selected;
    }

    private String[] channelChoices() {
        String[] choices = new String[binConfig.numChannels()];
        for (int i = 0; i < choices.length; i++) {
            choices[i] = channelLabel(i);
        }
        return choices;
    }

    private String channelLabel(int index) {
        String name = index < binConfig.channelNames.size() ? binConfig.channelNames.get(index) : "";
        if (name == null || name.trim().isEmpty()) {
            name = "Channel " + (index + 1);
        }
        return (index + 1) + " - " + name;
    }

    private int clampTarget(int index) {
        if (index < 0 || index >= binConfig.numChannels()) return 0;
        return index;
    }

    private static String channelChoiceForIndex(int index, String[] choices) {
        if (choices.length == 0) return null;
        if (index < 0 || index >= choices.length) return choices[0];
        return choices[index];
    }

    private static int indexFromChoice(String choice, String[] choices) {
        if (choice == null) return -1;
        int separator = choice.indexOf(" - ");
        if (separator > 0) {
            try {
                return Integer.parseInt(choice.substring(0, separator).trim()) - 1;
            } catch (NumberFormatException e) {
                // Fall through to exact matching.
            }
        }
        for (int i = 0; i < choices.length; i++) {
            if (choice.equals(choices[i])) return i;
        }
        return -1;
    }

    private static String formatErrors(List<String> errors) {
        StringBuilder sb = new StringBuilder();
        sb.append("Please fix these Spectral Decontamination settings:\n\n");
        for (String error : errors) {
            sb.append("- ").append(error).append("\n");
        }
        return sb.toString();
    }

    private static class DialogState {
        final SpectralDecontaminationConfig config;

        DialogState(SpectralDecontaminationConfig config) {
            this.config = config;
        }
    }

    public enum PreviewDecision {
        ACCEPT,
        BACK,
        CANCEL
    }
}
