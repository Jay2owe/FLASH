package flash.pipeline.decontamination;

import flash.pipeline.bin.BinConfig;
import flash.pipeline.decontamination.features.EnvelopeCorrectionFeature;
import flash.pipeline.decontamination.features.FullForwardModelFeature;
import flash.pipeline.decontamination.features.RocThresholdSearchFeature;
import flash.pipeline.decontamination.wizard.SpectralDecontamPreset;
import flash.pipeline.decontamination.wizard.SpectralDecontamPresetIO;
import flash.pipeline.decontamination.wizard.SpectralDecontaminationSetup;
import flash.pipeline.runrecord.LoadedRunParameterApplier;
import flash.pipeline.runrecord.LoadedRunParameters;
import flash.pipeline.runrecord.ui.LoadFromRunButton;
import flash.pipeline.ui.NextStepLabels;
import flash.pipeline.ui.CardChoice;
import flash.pipeline.ui.PipelineDialog;
import ij.IJ;

import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JPanel;
import java.awt.FlowLayout;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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
        boolean expertSettings = hasExpertSettings(config);
        dialog.setWorkflowTracker(spectralWorkflow(expertSettings), expertSettings ? 4 : 3);
        dialog.enableBackButton();
        dialog.addHeader("Preview");
        dialog.addMessage("Inspect the same correction stack that batch processing will run on a balanced subset.");
        dialog.addMessage("Controls and experimental conditions are grouped separately. "
                + "Use Back to adjust the correction stack before continuing.");
        dialog.addComponent(new SpectralPreviewPanel(previews));
        dialog.setPrimaryButtonText(NextStepLabels.RUN_SPECTRAL_BATCH);

        if (!dialog.showDialog()) {
            return dialog.wasBackPressed() ? PreviewDecision.BACK : PreviewDecision.CANCEL;
        }
        return PreviewDecision.ACCEPT;
    }

    private DialogState showOnce(SpectralDecontaminationConfig config) {
        PipelineDialog dialog = new PipelineDialog(TITLE);
        dialog.setWorkflowTracker(spectralWorkflow(false), 0);
        String[] channelChoices = channelChoices();
        final SpectralDecontaminationConfig[] loadedFromRun =
                new SpectralDecontaminationConfig[1];
        LoadFromRunButton.install(dialog, "SpectralDecontaminationAnalysis", projectRootOrUserDir(),
                new LoadedRunParameterApplier() {
                    @Override public LoadedRunParameters.Result applyLoadedParameters(
                            Map<String, Object> parameters) {
                        LoadedRunParameters.PresetLoad<SpectralDecontaminationConfig> loaded =
                                SpectralDecontaminationSetup.configFromLoadedParameters(parameters);
                        loadedFromRun[0] = loaded.payload;
                        LoadedRunParameters.rememberLastResult(loaded.result);
                        dialog.closeWithAction("loaded_run");
                        return loaded.result;
                    }
                });
        JComboBox<String> presetDropdown = new JComboBox<String>(presetLabels());
        JButton saveAsPreset = new JButton("[Save as preset...]");
        JPanel presetRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        presetRow.setOpaque(false);
        presetRow.add(presetDropdown);
        presetRow.add(saveAsPreset);
        saveAsPreset.addActionListener(e -> dialog.closeWithAction("save_preset"));
        dialog.addComponent(presetRow);

        dialog.addHeader("Goal");
        final String goalDefault = config.getGoal().getLabel();
        final String goalImage = SpectralDecontaminationConfig.Goal.CREATE_CLEANED_IMAGE.getLabel();
        final String goalMask = SpectralDecontaminationConfig.Goal.CREATE_CLEANED_MASK.getLabel();
        final String goalScore = SpectralDecontaminationConfig.Goal.SCORE_EXISTING_OBJECTS.getLabel();
        final String goalMeasure = SpectralDecontaminationConfig.Goal.MEASURE_CLEANED_SIGNAL_ONLY.getLabel();
        dialog.addCardChoice(null,
                new CardChoice.Option[]{
                        new CardChoice.Option(goalImage, "Cleaned image", "Write a corrected stack",
                                "microscope", goalImage.equals(goalDefault) ? "Default" : null),
                        new CardChoice.Option(goalMask, "Cleaned mask", "Write a binary cleaned mask",
                                "stack-2", goalMask.equals(goalDefault) ? "Default" : null),
                        new CardChoice.Option(goalScore, "Score objects", "Score existing object maps",
                                "chart-bar", goalScore.equals(goalDefault) ? "Default" : null),
                        new CardChoice.Option(goalMeasure, "Measure only", "Measure cleaned signal only",
                                "ruler-2", goalMeasure.equals(goalDefault) ? "Default" : null),
                },
                goalDefault);

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
        dialog.setPrimaryButtonText(NextStepLabels.CONDITION_SOURCE);

        if (!dialog.showDialog()) {
            if ("loaded_run".equals(dialog.getActionCommand()) && loadedFromRun[0] != null) {
                return new DialogState(loadedFromRun[0]);
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

    private static String[] spectralWorkflow(boolean expertSettings) {
        return expertSettings
                ? new String[]{"Setup", "Conditions", "Correction Stack", "Expert Settings", "Preview", "Run"}
                : new String[]{"Setup", "Conditions", "Correction Stack", "Preview", "Run"};
    }

    private static boolean hasExpertSettings(SpectralDecontaminationConfig config) {
        if (config == null || !config.hasCorrectionPipeline()) {
            return false;
        }
        CorrectionPipeline pipeline = config.getCorrectionPipeline();
        return pipeline.getFeatureIds().contains(FullForwardModelFeature.ID)
                || pipeline.getFeatureIds().contains(EnvelopeCorrectionFeature.ID)
                || pipeline.getFeatureIds().contains(RocThresholdSearchFeature.ID);
    }
}
