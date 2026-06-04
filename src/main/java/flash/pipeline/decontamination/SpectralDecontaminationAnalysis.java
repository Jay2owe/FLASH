package flash.pipeline.decontamination;

import flash.pipeline.analyses.Analysis;
import flash.pipeline.bin.BinConfig;
import flash.pipeline.bin.BinConfigIO;
import flash.pipeline.cli.CLIConfig;
import flash.pipeline.decontamination.wizard.SpectralDecontamPreset;
import flash.pipeline.decontamination.wizard.SpectralDecontaminationSetup;
import flash.pipeline.decontamination.features.EnvelopeCorrectionFeature;
import flash.pipeline.decontamination.features.FullForwardModelFeature;
import flash.pipeline.decontamination.features.RocThresholdSearchFeature;
import flash.pipeline.io.ConditionManifestIO;
import flash.pipeline.io.DeferredImageSupplier;
import flash.pipeline.io.ImageCache;
import flash.pipeline.io.ImageSourceDispatcher;
import flash.pipeline.io.SeriesMeta;
import flash.pipeline.report.QualityReport;
import flash.pipeline.results.RunIdCsv;
import flash.pipeline.runrecord.AnalysisRunContext;
import flash.pipeline.runrecord.ParameterSnapshot;
import flash.pipeline.runrecord.RunRecordAware;
import flash.pipeline.runtime.DependencyId;
import flash.pipeline.runtime.FeatureDependencyGate;
import flash.pipeline.ui.PipelineDialog;
import flash.pipeline.ui.ToggleSwitch;
import flash.pipeline.zslice.ZSliceRange;
import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.process.ImageProcessor;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import javax.swing.JComboBox;
import javax.swing.JLabel;

/**
 * Placeholder entry point for the planned Spectral Decontamination module.
 */
public class SpectralDecontaminationAnalysis implements Analysis, RunRecordAware {

    private static final String TITLE = "Spectral Decontamination";
    private static final String CUSTOM_PRESET_LABEL = "Custom";
    private static final String NONE_FEATURE_LABEL = "(None)";

    private boolean headless = false;
    private boolean suppressDialogs = false;
    private boolean verboseLogging = false;
    private boolean skipExisting = false;
    private int parallelThreads = 1;
    private int loaderThreads = 1;
    private int loaderPercent = 0;
    private boolean useTifCache = false;
    private ImageCache imageCache = null;
    private QualityReport qualityReport = null;
    private CLIConfig cliConfig = null;
    private SpectralDecontamPreset commandPreset = null;
    private AnalysisRunContext runRecordContext = null;

    @Override
    public void setHeadless(boolean headless) {
        this.headless = headless;
    }

    @Override
    public boolean requiresHeadedMode() {
        return true;
    }

    @Override
    public void setSuppressDialogs(boolean suppress) {
        this.suppressDialogs = suppress;
    }

    @Override
    public void setVerboseLogging(boolean verbose) {
        this.verboseLogging = verbose;
    }

    @Override
    public void setSkipExisting(boolean skip) {
        this.skipExisting = skip;
    }

    @Override
    public void setParallelThreads(int threads) {
        this.parallelThreads = Math.max(1, threads);
    }

    @Override
    public void setLoaderThreads(int threads) {
        this.loaderThreads = Math.max(1, threads);
    }

    @Override
    public void setLoaderPercent(int percent) {
        this.loaderPercent = Math.max(0, Math.min(percent, 100));
    }

    @Override
    public void setUseTifCache(boolean use) {
        this.useTifCache = use;
    }

    @Override
    public void setImageCache(ImageCache cache) {
        this.imageCache = cache;
    }

    @Override
    public void setQualityReport(QualityReport report) {
        this.qualityReport = report;
    }

    @Override
    public void setCliConfig(CLIConfig config) {
        this.cliConfig = config;
    }

    public void setCommandPreset(SpectralDecontamPreset preset) {
        this.commandPreset = preset;
    }

    @Override
    public void setRunRecordContext(AnalysisRunContext context) {
        this.runRecordContext = context;
    }

    @Override
    public void execute(String directory) {
        if (!FeatureDependencyGate.gate(DependencyId.BIO_FORMATS_RUNTIME,
                TITLE, "Bio-Formats image loading")) {
            recordWarn("Spectral Decontamination requires Bio-Formats image loading.");
            return;
        }

        IJ.log("==========================================================");
        IJ.log("SPECTRAL DECONTAMINATION (EXPERIMENTAL)");
        IJ.log("==========================================================");
        IJ.log("Directory: " + directory);
        if (verboseLogging) {
            IJ.log("Headless: " + headless);
            IJ.log("Skip existing: " + skipExisting);
            IJ.log("Parallel threads: " + parallelThreads);
            IJ.log("Loader threads: " + loaderThreads + " (" + loaderPercent + "%)");
            IJ.log("TIF cache: " + useTifCache);
            IJ.log("Shared image cache: " + (imageCache != null));
            IJ.log("QC report enabled: " + (qualityReport != null && qualityReport.isEnabled()));
        }

        BinConfig binConfig;
        try {
            binConfig = loadBinConfig(directory);
        } catch (IOException e) {
            String message = "Could not read channel names from channel_config.json. "
                    + "Run Set Up Configuration before Spectral Decontamination. " + e.getMessage();
            IJ.log("Spectral Decontamination: " + message);
            recordWarn(message);
            if (!headless && !suppressDialogs) {
                IJ.showMessage("Spectral Decontamination", message);
            }
            return;
        }

        SpectralDecontaminationConfig config;
        try {
            config = SpectralDecontaminationConfigIO.readOrDefault(directory, binConfig.numChannels());
        } catch (IOException e) {
            String message = "Could not read existing config. Starting from defaults. "
                    + e.getMessage();
            IJ.log("Spectral Decontamination: " + message);
            recordWarn(message);
            config = SpectralDecontaminationConfig.defaults(binConfig.numChannels());
        }
        if (commandPreset != null) {
            config = commandPreset.getPayload();
        }

        if (headless) {
            SpectralDecontaminationConfig headlessConfig = config.copy();
            headlessConfig = applyCliSpectralConfiguration(directory, headlessConfig);
            List<String> errors = validateBatchConfig(headlessConfig, binConfig);
            if (!errors.isEmpty()) {
                IJ.log("Spectral Decontamination config is incomplete or invalid:");
                for (String error : errors) {
                    IJ.log("  - " + error);
                    recordWarn("Spectral Decontamination config invalid: " + error);
                }
                String message = "Run Spectral Decontamination interactively to choose a runnable correction stack.";
                IJ.log(message);
                recordWarn(message);
                return;
            }

            List<SeriesMeta> metas;
            List<SpectralPreviewSelector.PreviewCandidate> candidates;
            try {
                metas = readSeriesMetadata(directory);
                candidates = resolveCandidatesForBatch(directory, headlessConfig, metas);
            } catch (Exception e) {
                String message = "Could not resolve image metadata. " + e.getMessage();
                IJ.log("Spectral Decontamination: " + message);
                recordWarn(message);
                return;
            }

            logFeatureStackStatus(headlessConfig, binConfig);
            IJ.log("Spectral Decontamination config loaded: " + describeConfig(headlessConfig, binConfig));
            PreviewRunResult previewResult = runPreviewSelection(directory, headlessConfig, candidates);
            if (!previewResult.success) {
                recordWarn("Spectral Decontamination: continuing without preview selection output.");
                IJ.log("Spectral Decontamination: continuing without preview selection output.");
            }
            if (containsRocThresholdSearch(headlessConfig)) {
                RocCalibrationResult rocCalibration = calibrateRocThresholdSearchOnCandidates(
                        directory,
                        binConfig,
                        headlessConfig,
                        candidates,
                        "full_dataset");
                if (!rocCalibration.success) {
                    IJ.log("Spectral Decontamination: " + rocCalibration.message);
                    recordWarn(rocCalibration.message);
                    return;
                }
                headlessConfig = rocCalibration.config;
            }
            previewResult = rewritePreviewSelectionFile(directory, headlessConfig, previewResult);
            List<SpectralPreviewRenderer.RenderedPreview> reportPreviews =
                    renderPreviewSelectionsForReport(directory, binConfig, headlessConfig, previewResult);
            runBatch(directory, binConfig, headlessConfig, metas, candidates,
                    previewResult.outputFile, reportPreviews);
            return;
        }

        SpectralDecontaminationDialog dialog = new SpectralDecontaminationDialog(new File(directory), binConfig, config);
        SpectralDecontaminationConfig selected = dialog.showDialog();
        if (selected == null) {
            IJ.log("Spectral Decontamination configuration cancelled.");
            return;
        }

        InteractiveSetupResult setup = completeInteractiveSetup(directory, binConfig, selected);
        if (setup == null) return;
        selected = setup.config;

        List<String> configErrors = validateBatchConfig(selected, binConfig);
        if (!configErrors.isEmpty()) {
            IJ.showMessage(TITLE, formatErrors(configErrors));
            return;
        }

        List<SeriesMeta> metas;
        try {
            metas = readSeriesMetadata(directory);
        } catch (Exception e) {
            String message = "Could not read image metadata for batch processing: " + e.getMessage();
            IJ.log("Spectral Decontamination: " + message);
            recordWarn(message);
            IJ.showMessage(TITLE, message);
            return;
        }

        List<SpectralPreviewSelector.PreviewCandidate> candidates = setup.candidates;
        if (candidates == null || candidates.isEmpty()) {
            try {
                candidates = resolveCandidatesForBatch(directory, selected, metas);
            } catch (Exception e) {
                String message = "Could not resolve conditions for batch processing: " + e.getMessage();
                IJ.log("Spectral Decontamination: " + message);
                recordWarn(message);
                IJ.showMessage(TITLE, message);
                return;
            }
        }

        try {
            SpectralDecontaminationConfigIO.writeToDirectory(directory, selected);
            recordOutput(SpectralDecontaminationConfigIO.configFile(directory), "json");
        } catch (IOException e) {
            String message = "Could not save Spectral Decontamination config: " + e.getMessage();
            IJ.log("Spectral Decontamination: " + message);
            recordWarn(message);
            IJ.showMessage(TITLE, message);
            return;
        }

        IJ.log("Spectral Decontamination config saved: " + describeConfig(selected, binConfig));
        recordSpectralRunParameters(selected);
        PreviewRunResult previewResult = setup.previewResult;
        if (previewResult == null) {
            previewResult = PreviewRunResult.failure("Preview was not rendered.");
        }

        previewResult = rewritePreviewSelectionFile(directory, selected, previewResult);
        BatchRunResult batchResult =
                runBatch(directory, binConfig, selected, metas, candidates,
                        previewResult.outputFile, setup.renderedPreviews);
        showInteractiveBatchResult(directory, batchResult, previewResult);
    }

    /**
     * Capture the confirmed interactive dialog settings into the run record so a
     * later "Load settings from previous run" can restore them. GUI runs open the
     * record with an empty parameter map; without this the loader has nothing to
     * apply and falls back to defaults. Keys mirror
     * {@link SpectralDecontamPreset#toJsonObject()} so
     * {@code LoadedRunParameters.SPECTRAL_KEYS} recognises them.
     */
    private void recordSpectralRunParameters(SpectralDecontaminationConfig selected) {
        if (runRecordContext == null) {
            return;
        }
        try {
            SpectralDecontamPreset preset = new SpectralDecontamPreset(
                    "GUI Spectral Decontamination run",
                    "Captured from the Spectral Decontamination dialog",
                    SpectralDecontamPreset.CURRENT_LIBRARY_VERSION,
                    selected,
                    "",
                    "",
                    "");
            runRecordContext.recordParameters(ParameterSnapshot.fromAnalysisPresetMap(
                    "SpectralDecontaminationAnalysis", preset.toJsonObject()));
        } catch (RuntimeException e) {
            IJ.log("[FLASH] Could not capture Spectral Decontamination run parameters: " + e.getMessage());
        }
    }

    BinConfig loadBinConfig(String directory) throws IOException {
        return BinConfigIO.readFromDirectory(directory);
    }

    private InteractiveSetupResult completeInteractiveSetup(String directory,
                                                            BinConfig binConfig,
                                                            SpectralDecontaminationConfig startingConfig) {
        SpectralDecontaminationConfig workingConfig = startingConfig == null
                ? new SpectralDecontaminationConfig()
                : startingConfig.copy();

        while (true) {
            ConditionSetup conditionSetup = showConditionSetup(directory, workingConfig);
            if (conditionSetup == null) {
                IJ.log("Spectral Decontamination condition setup cancelled.");
                return null;
            }
            workingConfig = conditionSetup.config;

            while (true) {
                FeatureSetupResult featureSetup = showFeatureStackSetup(workingConfig);
                if (featureSetup == null) {
                    IJ.log("Spectral Decontamination feature stack setup cancelled.");
                    return null;
                }
                if (featureSetup.backPressed) {
                    workingConfig = conditionSetup.config;
                    break;
                }

                workingConfig = featureSetup.config;
                PreviewRunResult previewResult =
                        runPreviewSelection(directory, workingConfig, conditionSetup.candidates);
                if (!previewResult.success) {
                    IJ.showMessage(TITLE, previewResult.message);
                    continue;
                }
                if (previewResult.selections.isEmpty()) {
                    IJ.showMessage(TITLE,
                            "No preview images were selected. Review the condition assignments and try again.");
                    continue;
                }

                if (containsRocThresholdSearch(workingConfig)) {
                    RocCalibrationResult rocCalibration = calibrateRocThresholdSearchOnSelections(
                            directory,
                            binConfig,
                            workingConfig,
                            previewResult.selections,
                            "preview_subset");
                    if (!rocCalibration.success) {
                        IJ.showMessage(TITLE, rocCalibration.message);
                        continue;
                    }
                    workingConfig = rocCalibration.config;
                }

                List<SpectralPreviewRenderer.RenderedPreview> renderedPreviews;
                try {
                    renderedPreviews = SpectralPreviewRenderer.render(
                            directory,
                            binConfig,
                            workingConfig,
                            previewResult.selections);
                } catch (Exception e) {
                    String message = "Preview rendering failed: " + e.getMessage();
                    IJ.log("Spectral Decontamination: " + message);
                    IJ.showMessage(TITLE, message);
                    continue;
                }

                SpectralDecontaminationDialog previewDialog =
                        new SpectralDecontaminationDialog(binConfig, workingConfig);
                SpectralDecontaminationDialog.PreviewDecision decision =
                        previewDialog.showPreviewDialog(workingConfig, renderedPreviews);
                if (decision == SpectralDecontaminationDialog.PreviewDecision.ACCEPT) {
                    return new InteractiveSetupResult(
                            workingConfig,
                            conditionSetup.candidates,
                            previewResult,
                            renderedPreviews);
                }
                if (decision == SpectralDecontaminationDialog.PreviewDecision.CANCEL) {
                    IJ.log("Spectral Decontamination preview cancelled.");
                    return null;
                }
            }
        }
    }

    private FeatureSetupResult showFeatureStackSetup(SpectralDecontaminationConfig config) {
        final CorrectionFeatureRegistry registry = CorrectionFeatureRegistry.getDefault();
        final boolean existingObjectMapsAvailable = false;
        CorrectionPipeline defaults = config.getCorrectionPipeline();
        if (defaults.isEmpty()) {
            defaults = registry.recommendedPipeline(config);
        }

        while (true) {
            final CorrectionPipeline remembered = defaults.copy();
            final PipelineDialog dialog = new PipelineDialog(TITLE, PipelineDialog.Phase.ANALYSE);
            dialog.enableBackButton();
            dialog.addHeader("Correction Stack");
            dialog.addMessage("Choose a preset or build a custom ordered stack. "
                    + "Later slots only show features that remain valid after the earlier ones.");
            final ToggleSwitch expertToggle = dialog.addToggle("Expert mode", remembered.isExpertMode());
            dialog.addHelpText("Expert mode reveals advanced features and allows more than one "
                    + "threshold-based mask feature.");

            final JComboBox<String> presetChoice = dialog.addChoice("Preset",
                    presetLabelsWithCustom(registry, true), presetLabelForId(registry, remembered.getPresetId()));
            final JLabel presetHelp = dialog.addHelpText("");

            dialog.addHeader("Ordered Features");
            final int maxSlots = Math.max(1, registry.getMaxPresetSize());
            @SuppressWarnings("unchecked")
            final JComboBox<String>[] featureChoices = (JComboBox<String>[]) new JComboBox<?>[maxSlots];
            final JLabel[] featureHelps = new JLabel[maxSlots];
            for (int i = 0; i < maxSlots; i++) {
                String initialFeature = i < remembered.getFeatureIds().size()
                        ? featureDisplayName(registry, remembered.getFeatureIds().get(i))
                        : NONE_FEATURE_LABEL;
                featureChoices[i] = dialog.addChoice("Feature " + (i + 1),
                        new String[]{initialFeature}, initialFeature);
                featureHelps[i] = dialog.addHelpText("");
            }
            final JLabel summaryLabel = dialog.addMessage("");
            final boolean[] updating = new boolean[]{false};

            Runnable refresh = new Runnable() {
                @Override
                public void run() {
                    if (updating[0]) return;
                    updating[0] = true;
                    try {
                        refreshFeatureDialog(
                                registry,
                                config,
                                existingObjectMapsAvailable,
                                expertToggle,
                                presetChoice,
                                presetHelp,
                                featureChoices,
                                featureHelps,
                                summaryLabel);
                    } finally {
                        updating[0] = false;
                    }
                }
            };

            expertToggle.addChangeListener(refresh);
            presetChoice.addActionListener(e -> refresh.run());
            for (JComboBox<String> featureChoice : featureChoices) {
                featureChoice.addActionListener(e -> refresh.run());
            }
            refresh.run();

            if (!dialog.showDialog()) {
                if (dialog.wasBackPressed()) {
                    return FeatureSetupResult.back(config);
                }
                return null;
            }

            CorrectionPipeline selectedPipeline = buildPipelineFromSelections(
                    registry,
                    expertToggle.isSelected(),
                    (String) presetChoice.getSelectedItem(),
                    featureChoices);

            List<String> errors;
            if (selectedPipeline.isEmpty()
                    && config.getGoal() == SpectralDecontaminationConfig.Goal.SCORE_EXISTING_OBJECTS) {
                errors = new ArrayList<String>();
            } else {
                errors = selectedPipeline.validate(registry, config, existingObjectMapsAvailable);
                if (errors.isEmpty()) {
                    errors.addAll(validateExecutableStack(config, selectedPipeline, registry));
                }
            }
            if (errors.isEmpty()) {
                SpectralDecontaminationConfig updated = config.copy();
                updated.setCorrectionPipeline(selectedPipeline);
                ExpertSettingsDialogResult expertSettings = showExpertFeatureSettingsDialogs(updated);
                if (expertSettings == null) {
                    return null;
                }
                if (expertSettings.backPressed) {
                    defaults = selectedPipeline;
                    continue;
                }
                updated = expertSettings.config;
                if (containsRocThresholdSearch(updated)) {
                    RocSettingsDialogResult rocSettings = showRocThresholdSettingsDialog(updated);
                    if (rocSettings == null) {
                        return null;
                    }
                    if (rocSettings.backPressed) {
                        defaults = selectedPipeline;
                        continue;
                    }
                    updated = rocSettings.config;
                }
                return FeatureSetupResult.success(updated);
            }

            defaults = selectedPipeline;
            IJ.showMessage(TITLE, formatErrors(errors));
        }
    }

    private void refreshFeatureDialog(CorrectionFeatureRegistry registry,
                                      SpectralDecontaminationConfig config,
                                      boolean existingObjectMapsAvailable,
                                      ToggleSwitch expertToggle,
                                      JComboBox<String> presetChoice,
                                      JLabel presetHelp,
                                      JComboBox<String>[] featureChoices,
                                      JLabel[] featureHelps,
                                      JLabel summaryLabel) {
        boolean expertMode = expertToggle.isSelected();
        List<CorrectionFeatureRegistry.PresetDefinition> availablePresets =
                registry.getAvailablePresets(config, expertMode, existingObjectMapsAvailable);

        List<String> presetLabels = new ArrayList<String>();
        presetLabels.add(CUSTOM_PRESET_LABEL);
        for (CorrectionFeatureRegistry.PresetDefinition preset : availablePresets) {
            presetLabels.add(preset.getDisplayName());
        }

        String selectedPreset = safeText((String) presetChoice.getSelectedItem());
        if (!presetLabels.contains(selectedPreset)) {
            selectedPreset = presetLabels.get(0);
        }
        setComboItems(presetChoice, presetLabels, selectedPreset);

        String presetId = presetIdForLabel(registry, (String) presetChoice.getSelectedItem());
        if (!CorrectionPipeline.CUSTOM_PRESET_ID.equals(presetId)) {
            CorrectionFeatureRegistry.PresetDefinition preset = registry.getPreset(presetId);
            presetHelp.setText(helpText(preset == null ? "" : preset.getDescription()));
            List<String> featureIds = preset == null
                    ? new ArrayList<String>()
                    : preset.getFeatureIds();
            for (int i = 0; i < featureChoices.length; i++) {
                if (i < featureIds.size()) {
                    String featureLabel = featureDisplayName(registry, featureIds.get(i));
                    setComboItems(featureChoices[i], singleItem(featureLabel), featureLabel);
                    featureChoices[i].setEnabled(false);
                    featureHelps[i].setText(helpText(featureDescription(registry, featureLabel)));
                } else {
                    setComboItems(featureChoices[i], singleItem(NONE_FEATURE_LABEL), NONE_FEATURE_LABEL);
                    featureChoices[i].setEnabled(false);
                    featureHelps[i].setText(helpText(""));
                }
            }
        } else {
            presetHelp.setText(helpText("Custom mode only shows compatible next features."));
            List<String> currentSelections = currentFeatureSelections(featureChoices);
            List<String> prefixIds = new ArrayList<String>();
            boolean earlierSlotSelected = true;

            for (int i = 0; i < featureChoices.length; i++) {
                if (!earlierSlotSelected) {
                    setComboItems(featureChoices[i], singleItem(NONE_FEATURE_LABEL), NONE_FEATURE_LABEL);
                    featureChoices[i].setEnabled(false);
                    featureHelps[i].setText(helpText("Add an earlier feature first."));
                    continue;
                }

                List<CorrectionFeature> availableNext = registry.getAvailableNextFeatures(
                        prefixIds, config, expertMode, existingObjectMapsAvailable);
                List<String> items = new ArrayList<String>();
                items.add(NONE_FEATURE_LABEL);
                for (CorrectionFeature feature : availableNext) {
                    items.add(feature.getDisplayName());
                }

                String selectedFeature = i < currentSelections.size()
                        ? currentSelections.get(i)
                        : NONE_FEATURE_LABEL;
                if (!items.contains(selectedFeature)) {
                    selectedFeature = NONE_FEATURE_LABEL;
                }
                setComboItems(featureChoices[i], items, selectedFeature);
                featureChoices[i].setEnabled(true);

                String selectedId = featureIdForDisplayName(registry, selectedFeature);
                if (selectedId.isEmpty()) {
                    featureHelps[i].setText(helpText(availableNext.isEmpty()
                            ? "No more compatible features are available."
                            : "Optional."));
                    earlierSlotSelected = false;
                } else {
                    featureHelps[i].setText(helpText(featureDescription(registry, selectedFeature)));
                    prefixIds.add(selectedId);
                }
            }
        }

        CorrectionPipeline previewPipeline = buildPipelineFromSelections(
                registry,
                expertMode,
                (String) presetChoice.getSelectedItem(),
                featureChoices);
        if (previewPipeline.isEmpty()) {
            summaryLabel.setText(messageText("Pick a preset or start with Feature 1."));
            return;
        }

        List<String> errors = previewPipeline.validate(registry, config, existingObjectMapsAvailable);
        if (previewPipeline.isEmpty()
                && config.getGoal() == SpectralDecontaminationConfig.Goal.SCORE_EXISTING_OBJECTS) {
            errors.clear();
        }
        if (errors.isEmpty()) {
            errors.addAll(validateExecutableStack(config, previewPipeline, registry));
        }
        if (errors.isEmpty()) {
            summaryLabel.setText(messageText("Stack: " + previewPipeline.describe(registry)));
        } else {
            summaryLabel.setText(messageText("Stack issue: " + errors.get(0)));
        }
    }

    private RocSettingsDialogResult showRocThresholdSettingsDialog(SpectralDecontaminationConfig config) {
        RocThresholdSearchFeature.Settings defaults = RocThresholdSearchFeature.Settings.from(
                config.getFeatureSettings(RocThresholdSearchFeature.ID));
        while (true) {
            PipelineDialog dialog = new PipelineDialog(TITLE);
            dialog.enableBackButton();
            dialog.addHeader("ROC Threshold Search");
            dialog.addMessage("Choose the metric and control false-positive limit for the threshold grid. "
                    + "The operating point is fitted on the preview subset before batch processing.");
            dialog.addChoice("Optimisation metric",
                    RocThresholdSearchFeature.Metric.labels(),
                    defaults.getMetric().getLabel());
            dialog.addNumericField("Allowed control false-positive rate",
                    defaults.getAllowedFalsePositiveRate(),
                    3);
            dialog.addNumericField("Threshold grid minimum", defaults.getThresholdMin(), 2);
            dialog.addNumericField("Threshold grid maximum", defaults.getThresholdMax(), 2);
            dialog.addNumericField("Threshold grid step", defaults.getThresholdStep(), 2);
            dialog.addNumericField("Ratio target minimum", defaults.getTargetMinThreshold(), 2);
            dialog.addHelpText("Ratio target minimum is only used by the target-to-contaminant ratio metric.");

            if (!dialog.showDialog()) {
                if (dialog.wasBackPressed()) {
                    return RocSettingsDialogResult.back(config);
                }
                return null;
            }

            RocThresholdSearchFeature.Settings selected = defaults.copy();
            selected.setMetric(RocThresholdSearchFeature.Metric.fromKeyOrLabel(dialog.getNextChoice()));
            selected.setAllowedFalsePositiveRate(dialog.getNextNumber());
            selected.setThresholdMin(dialog.getNextNumber());
            selected.setThresholdMax(dialog.getNextNumber());
            selected.setThresholdStep(dialog.getNextNumber());
            selected.setTargetMinThreshold(dialog.getNextNumber());
            selected.applyMetricDefaultsIfNeeded();
            selected.normalizeGrid();
            selected.clearSelectedThreshold();

            List<String> errors = validateRocSettings(selected);
            if (errors.isEmpty()) {
                SpectralDecontaminationConfig updated = config.copy();
                updated.setFeatureSettings(RocThresholdSearchFeature.ID, selected.toPipelineSettings());
                return RocSettingsDialogResult.success(updated);
            }
            defaults = selected;
            IJ.showMessage(TITLE, formatErrors(errors));
        }
    }

    private ExpertSettingsDialogResult showExpertFeatureSettingsDialogs(SpectralDecontaminationConfig config) {
        SpectralDecontaminationConfig working = config == null
                ? null
                : config.copy();
        if (working == null || !working.hasCorrectionPipeline()) {
            return ExpertSettingsDialogResult.success(working);
        }

        if (containsFeature(working, FullForwardModelFeature.ID)) {
            ExpertSettingsDialogResult fullModelSettings = showFullForwardModelSettingsDialog(working);
            if (fullModelSettings == null || fullModelSettings.backPressed) {
                return fullModelSettings;
            }
            working = fullModelSettings.config;
        }

        if (containsFeature(working, EnvelopeCorrectionFeature.ID)) {
            ExpertSettingsDialogResult envelopeSettings = showEnvelopeSettingsDialog(working);
            if (envelopeSettings == null || envelopeSettings.backPressed) {
                return envelopeSettings;
            }
            working = envelopeSettings.config;
        }

        return ExpertSettingsDialogResult.success(working);
    }

    private ExpertSettingsDialogResult showFullForwardModelSettingsDialog(SpectralDecontaminationConfig config) {
        FullForwardModelFeature.Settings defaults = FullForwardModelFeature.Settings.from(
                config.getFeatureSettings(FullForwardModelFeature.ID));
        while (true) {
            PipelineDialog dialog = new PipelineDialog(TITLE);
            dialog.enableBackButton();
            dialog.addHeader("Full Forward Model");
            dialog.addMessage("These expert settings control the local autofluorescence fit, bleed-through source purification, and per-image bleed-through coefficient pool.");
            dialog.addNumericField("Local window radius", defaults.getWindowRadius(), 0);
            dialog.addNumericField("Quiet target percentile", defaults.getQuietTargetPercentile(), 1);
            dialog.addNumericField("Source quiet percentile", defaults.getSourceQuietPercentile(), 1);
            dialog.addNumericField("Source bright percentile", defaults.getSourceBrightPercentile(), 1);
            dialog.addNumericField("Minimum local fit pixels", defaults.getMinLocalFitPixels(), 0);
            dialog.addNumericField("Minimum bleed fit pixels", defaults.getMinBleedFitPixels(), 0);
            dialog.addToggle("Write local coefficient maps", defaults.isWriteParameterMaps());
            dialog.addHelpText("Leave coefficient maps off unless you need per-pixel troubleshooting images.");

            if (!dialog.showDialog()) {
                if (dialog.wasBackPressed()) {
                    return ExpertSettingsDialogResult.back(config);
                }
                return null;
            }

            FullForwardModelFeature.Settings selected = defaults.copy();
            selected.setWindowRadius((int) Math.round(dialog.getNextNumber()));
            selected.setQuietTargetPercentile(dialog.getNextNumber());
            selected.setSourceQuietPercentile(dialog.getNextNumber());
            selected.setSourceBrightPercentile(dialog.getNextNumber());
            selected.setMinLocalFitPixels((int) Math.round(dialog.getNextNumber()));
            selected.setMinBleedFitPixels((int) Math.round(dialog.getNextNumber()));
            selected.setWriteParameterMaps(dialog.getNextBoolean());
            selected.normalize();

            List<String> errors = validateFullForwardModelSettings(selected);
            if (errors.isEmpty()) {
                SpectralDecontaminationConfig updated = config.copy();
                updated.setFeatureSettings(FullForwardModelFeature.ID, selected.toPipelineSettings());
                return ExpertSettingsDialogResult.success(updated);
            }
            defaults = selected;
            IJ.showMessage(TITLE, formatErrors(errors));
        }
    }

    private List<String> validateFullForwardModelSettings(FullForwardModelFeature.Settings settings) {
        List<String> errors = new ArrayList<String>();
        if (settings == null) {
            errors.add("Full forward model settings are missing.");
            return errors;
        }
        if (settings.getWindowRadius() < 1) {
            errors.add("Local window radius must be at least 1.");
        }
        if (settings.getQuietTargetPercentile() < 0.0 || settings.getQuietTargetPercentile() > 100.0) {
            errors.add("Quiet target percentile must be between 0 and 100.");
        }
        if (settings.getSourceQuietPercentile() < 0.0 || settings.getSourceQuietPercentile() > 100.0) {
            errors.add("Source quiet percentile must be between 0 and 100.");
        }
        if (settings.getSourceBrightPercentile() < 0.0 || settings.getSourceBrightPercentile() > 100.0) {
            errors.add("Source bright percentile must be between 0 and 100.");
        }
        if (settings.getSourceBrightPercentile() < settings.getSourceQuietPercentile()) {
            errors.add("Source bright percentile should be greater than or equal to the source quiet percentile.");
        }
        if (settings.getMinLocalFitPixels() < 3) {
            errors.add("Minimum local fit pixels must be at least 3.");
        }
        if (settings.getMinBleedFitPixels() < 3) {
            errors.add("Minimum bleed fit pixels must be at least 3.");
        }
        return errors;
    }

    private ExpertSettingsDialogResult showEnvelopeSettingsDialog(SpectralDecontaminationConfig config) {
        EnvelopeCorrectionFeature.Settings defaults = EnvelopeCorrectionFeature.Settings.from(
                config.getFeatureSettings(EnvelopeCorrectionFeature.ID));
        while (true) {
            PipelineDialog dialog = new PipelineDialog(TITLE);
            dialog.enableBackButton();
            dialog.addHeader("Envelope Correction");
            dialog.addMessage("Use these expert settings when linear subtraction still leaves a bright contaminant-driven tail in the corrected target.");
            dialog.addNumericField("Dominant contaminant percentile", defaults.getDominantContaminantPercentile(), 1);
            dialog.addNumericField("Envelope percentile", defaults.getEnvelopePercentile(), 1);
            dialog.addNumericField("Envelope bin count", defaults.getBinCount(), 0);
            dialog.addNumericField("Minimum pixels per bin", defaults.getMinBinPixels(), 0);

            if (!dialog.showDialog()) {
                if (dialog.wasBackPressed()) {
                    return ExpertSettingsDialogResult.back(config);
                }
                return null;
            }

            EnvelopeCorrectionFeature.Settings selected = defaults.copy();
            selected.setDominantContaminantPercentile(dialog.getNextNumber());
            selected.setEnvelopePercentile(dialog.getNextNumber());
            selected.setBinCount((int) Math.round(dialog.getNextNumber()));
            selected.setMinBinPixels((int) Math.round(dialog.getNextNumber()));
            selected.normalize();

            List<String> errors = validateEnvelopeSettings(selected);
            if (errors.isEmpty()) {
                SpectralDecontaminationConfig updated = config.copy();
                updated.setFeatureSettings(EnvelopeCorrectionFeature.ID, selected.toPipelineSettings());
                return ExpertSettingsDialogResult.success(updated);
            }
            defaults = selected;
            IJ.showMessage(TITLE, formatErrors(errors));
        }
    }

    private List<String> validateEnvelopeSettings(EnvelopeCorrectionFeature.Settings settings) {
        List<String> errors = new ArrayList<String>();
        if (settings == null) {
            errors.add("Envelope correction settings are missing.");
            return errors;
        }
        if (settings.getDominantContaminantPercentile() < 0.0
                || settings.getDominantContaminantPercentile() > 100.0) {
            errors.add("Dominant contaminant percentile must be between 0 and 100.");
        }
        if (settings.getEnvelopePercentile() < 0.0 || settings.getEnvelopePercentile() > 100.0) {
            errors.add("Envelope percentile must be between 0 and 100.");
        }
        if (settings.getBinCount() < 4) {
            errors.add("Envelope bin count must be at least 4.");
        }
        if (settings.getMinBinPixels() < 2) {
            errors.add("Minimum pixels per envelope bin must be at least 2.");
        }
        return errors;
    }

    private List<String> validateRocSettings(RocThresholdSearchFeature.Settings settings) {
        List<String> errors = new ArrayList<String>();
        if (settings == null) {
            errors.add("ROC threshold settings are missing.");
            return errors;
        }
        if (settings.getAllowedFalsePositiveRate() < 0.0 || settings.getAllowedFalsePositiveRate() > 1.0) {
            errors.add("Allowed control false-positive rate must be between 0 and 1.");
        }
        if (settings.getThresholdStep() <= 0.0) {
            errors.add("Threshold grid step must be greater than 0.");
        }
        if (settings.getThresholdMax() < settings.getThresholdMin()) {
            errors.add("Threshold grid maximum must be greater than or equal to the minimum.");
        }
        if (RocThresholdSearchFeature.buildThresholdGrid(settings).size() > 10000) {
            errors.add("Threshold grid is too dense. Increase the step size.");
        }
        return errors;
    }

    private CorrectionPipeline buildPipelineFromSelections(CorrectionFeatureRegistry registry,
                                                           boolean expertMode,
                                                           String presetLabel,
                                                           JComboBox<String>[] featureChoices) {
        CorrectionPipeline pipeline = new CorrectionPipeline();
        pipeline.setExpertMode(expertMode);
        pipeline.setPresetId(presetIdForLabel(registry, presetLabel));

        List<String> featureIds = new ArrayList<String>();
        if (!CorrectionPipeline.CUSTOM_PRESET_ID.equals(pipeline.getPresetId())) {
            CorrectionFeatureRegistry.PresetDefinition preset = registry.getPreset(pipeline.getPresetId());
            if (preset != null) {
                featureIds.addAll(preset.getFeatureIds());
            }
        } else {
            for (JComboBox<String> featureChoice : featureChoices) {
                String featureId = featureIdForDisplayName(registry,
                        safeText((String) featureChoice.getSelectedItem()));
                if (featureId.isEmpty()) break;
                featureIds.add(featureId);
            }
        }

        pipeline.setFeatureIds(featureIds);
        return pipeline;
    }

    private List<String> validateBatchConfig(SpectralDecontaminationConfig config, BinConfig binConfig) {
        List<String> errors = new ArrayList<String>();
        if (config == null) {
            errors.add("Spectral Decontamination config is missing.");
            return errors;
        }
        if (binConfig == null) {
            errors.add("Bin config is missing.");
            return errors;
        }

        errors.addAll(config.validate(binConfig.numChannels()));
        if (!config.hasCorrectionPipeline()
                && config.getGoal() != SpectralDecontaminationConfig.Goal.SCORE_EXISTING_OBJECTS) {
            errors.add("Select at least one correction feature.");
        }

        CorrectionFeatureRegistry registry = CorrectionFeatureRegistry.getDefault();
        if (config.hasCorrectionPipeline()) {
            errors.addAll(config.getCorrectionPipeline().validate(registry, config, false));
            if (errors.isEmpty()) {
                errors.addAll(validateExecutableStack(config, config.getCorrectionPipeline(), registry));
            }
        }
        return errors;
    }

    private List<String> validateExecutableStack(SpectralDecontaminationConfig config,
                                                 CorrectionPipeline pipeline,
                                                 CorrectionFeatureRegistry registry) {
        List<String> errors = new ArrayList<String>();
        if (config == null) {
            errors.add("Spectral Decontamination config is missing.");
            return errors;
        }
        if (pipeline == null || pipeline.isEmpty()) {
            if (config.getGoal() == SpectralDecontaminationConfig.Goal.SCORE_EXISTING_OBJECTS) {
                return errors;
            }
            errors.add("Select at least one correction feature.");
            return errors;
        }
        if (registry == null) {
            errors.add("Correction feature registry is not available.");
            return errors;
        }

        for (String featureId : pipeline.getFeatureIds()) {
            CorrectionFeature feature = registry.getFeature(featureId);
            if (feature == null) {
                errors.add("Unknown correction feature: " + featureId);
                continue;
            }
            if (!(feature instanceof CorrectionPipeline.ExecutableFeature)) {
                errors.add(feature.getDisplayName() + " is not runnable yet.");
            }
        }
        if (!errors.isEmpty()) {
            return errors;
        }

        List<String> pipelineErrors = new ArrayList<String>();
        CorrectionResult finalResult = pipeline.evaluate(registry, config, false, pipelineErrors);
        if (!pipelineErrors.isEmpty()) {
            errors.addAll(pipelineErrors);
            return errors;
        }

        boolean hasCorrectedImage = finalResult.hasInput(CorrectionFeature.InputType.CORRECTED_IMAGE);
        boolean hasMask = finalResult.hasInput(CorrectionFeature.InputType.MASK);
        if (config.getGoal() == SpectralDecontaminationConfig.Goal.SCORE_EXISTING_OBJECTS) {
            return errors;
        }
        if (!hasCorrectedImage && !hasMask) {
            errors.add("The selected stack does not produce a corrected image or a final mask.");
        }
        if ((config.getGoal() == SpectralDecontaminationConfig.Goal.CREATE_CLEANED_IMAGE
                || config.getGoal() == SpectralDecontaminationConfig.Goal.MEASURE_CLEANED_SIGNAL_ONLY)
                && !hasCorrectedImage) {
            errors.add("The selected goal requires a corrected image, but the current stack does not create one.");
        }
        if (config.getGoal() == SpectralDecontaminationConfig.Goal.CREATE_CLEANED_MASK && !hasMask) {
            errors.add("The selected goal requires a final mask, but the current stack does not create one.");
        }
        return errors;
    }

    private SpectralDecontaminationConfig applyCliSpectralConfiguration(String directory,
                                                                        SpectralDecontaminationConfig config) {
        if (cliConfig == null || cliConfig.getSpectral() == null
                || !cliConfig.getSpectral().hasConfiguration()) {
            return config;
        }
        CLIConfig.SpectralConfig spectral = cliConfig.getSpectral();
        try {
            SpectralDecontaminationConfig updated = SpectralDecontaminationSetup.applyCliOverrides(
                    config,
                    spectral.getPresetName(),
                    spectral.getGoal(),
                    spectral.getTargetChannelIndex(),
                    listFromArray(spectral.getBleedThroughChannelIndexes()),
                    listFromArray(spectral.getAutofluorescenceChannelIndexes()),
                    spectral.getContaminationType(),
                    spectral.getCalibration(),
                    spectral.getStrength(),
                    new File(directory));
            IJ.log("[CLI] Spectral Decontamination configured using CLI spectral.* options.");
            return updated;
        } catch (IOException e) {
            IJ.log("[CLI] Warning: Could not apply spectral.* options: " + e.getMessage());
            return config;
        }
    }

    private static List<Integer> listFromArray(int[] values) {
        if (values == null) return null;
        List<Integer> out = new ArrayList<Integer>();
        for (int value : values) {
            out.add(Integer.valueOf(value));
        }
        return out;
    }

    private List<SeriesMeta> readSeriesMetadata(String directory) throws Exception {
        return ImageSourceDispatcher.readAllMetadata(directory);
    }

    private List<SpectralPreviewSelector.PreviewCandidate> resolveCandidatesForBatch(
            String directory,
            SpectralDecontaminationConfig config,
            List<SeriesMeta> metas) {
        LinkedHashMap<String, String> assignments =
                resolveConditionAssignments(directory, metas, config.getConditionSource());
        List<SpectralPreviewSelector.PreviewCandidate> candidates =
                SpectralPreviewSelector.buildCandidatesFromAssignments(metas, assignments);
        applyDefaultConditionRoles(config, SpectralPreviewSelector.conditionNames(candidates));
        return candidates;
    }

    private BatchRunResult runBatch(String directory,
                                    BinConfig binConfig,
                                    SpectralDecontaminationConfig config,
                                    List<SeriesMeta> metas,
                                    List<SpectralPreviewSelector.PreviewCandidate> candidates,
                                    File previewSelectionFile,
                                    List<SpectralPreviewRenderer.RenderedPreview> renderedPreviews) {
        long startTimeMs = System.currentTimeMillis();
        BatchRunResult batchResult = new BatchRunResult();
        batchResult.previewSelectionFile = previewSelectionFile;
        batchResult.totalImages = metas == null ? 0 : metas.size();

        CorrectionFeatureRegistry registry = CorrectionFeatureRegistry.getDefault();
        CorrectionPipeline pipeline = config.getCorrectionPipeline();
        SpectralOutputWriter.RunMetadata runMetadata =
                SpectralOutputWriter.RunMetadata.fromConfig(config, registry);
        String pipelinePresetLabel = runMetadata.pipelinePresetLabel;
        String pipelineDescription = runMetadata.pipelineDescription;
        String targetChannelName = binConfig.channelNames.isEmpty()
                ? "target"
                : binConfig.channelNames.get(Math.max(0, config.getTargetChannelIndex()));
        boolean objectScoringGoal =
                config.getGoal() == SpectralDecontaminationConfig.Goal.SCORE_EXISTING_OBJECTS;

        List<String> pipelineErrors = new ArrayList<String>();
        CorrectionResult pipelineResult = pipeline.evaluate(registry, config, false, pipelineErrors);
        if (!pipelineErrors.isEmpty()) {
            batchResult.message = joinLines(pipelineErrors);
            IJ.log("Spectral Decontamination: " + batchResult.message);
            recordWarn(batchResult.message);
            return batchResult;
        }

        boolean needsCorrectedImage = pipelineResult.hasInput(CorrectionFeature.InputType.CORRECTED_IMAGE);
        boolean needsMaskImage = pipelineResult.hasInput(CorrectionFeature.InputType.MASK);

        Map<Integer, Map<String, String>> existingSummaryRows =
                new LinkedHashMap<Integer, Map<String, String>>();
        Map<Integer, List<Map<String, String>>> existingCoefficientRows =
                new LinkedHashMap<Integer, List<Map<String, String>>>();
        Map<Integer, List<Map<String, String>>> existingObjectRows =
                new LinkedHashMap<Integer, List<Map<String, String>>>();
        if (skipExisting) {
            try {
                existingSummaryRows = SpectralOutputWriter.readPerImageSummaryRows(directory);
                existingCoefficientRows = SpectralOutputWriter.readCoefficientRows(directory);
                if (objectScoringGoal) {
                    existingObjectRows = ObjectScoreWriter.readObjectRowsBySeriesIndex(directory);
                }
            } catch (IOException e) {
                String message = "Could not reuse prior CSV rows for Skip Existing. "
                        + e.getMessage();
                IJ.log("Spectral Decontamination: " + message);
                recordWarn(message);
            }
        }

        LinkedHashMap<Integer, SpectralPreviewSelector.PreviewCandidate> candidateBySeries =
                new LinkedHashMap<Integer, SpectralPreviewSelector.PreviewCandidate>();
        if (candidates != null) {
            for (SpectralPreviewSelector.PreviewCandidate candidate : candidates) {
                if (candidate != null) {
                    candidateBySeries.put(Integer.valueOf(candidate.seriesIndex), candidate);
                }
            }
        }

        final List<Map<String, String>> summaryRows =
                Collections.synchronizedList(new ArrayList<Map<String, String>>());
        final List<Map<String, String>> coefficientRows =
                Collections.synchronizedList(new ArrayList<Map<String, String>>());
        final List<Map<String, String>> objectScoreRows =
                Collections.synchronizedList(new ArrayList<Map<String, String>>());
        List<Integer> seriesToProcess = new ArrayList<Integer>();

        for (int i = 0; metas != null && i < metas.size(); i++) {
            SeriesMeta meta = metas.get(i);
            SpectralPreviewSelector.PreviewCandidate candidate = candidateBySeries.get(Integer.valueOf(meta.index));
            if (candidate == null) {
                candidate = fallbackCandidate(meta);
                candidateBySeries.put(Integer.valueOf(meta.index), candidate);
            }

            SpectralOutputWriter.ExpectedOutputs expectedOutputs =
                    SpectralOutputWriter.expectedOutputs(directory, meta.index, candidate.seriesName, targetChannelName);
            Integer seriesKey = Integer.valueOf(meta.index);
            boolean outputsExist = SpectralOutputWriter.expectedOutputsExist(
                    expectedOutputs, needsCorrectedImage, needsMaskImage);
            if (objectScoringGoal) {
                outputsExist = outputsExist && ObjectScoreWriter.objectRowsReusable(
                        directory,
                        existingObjectRows.get(seriesKey));
            }
            boolean canReuseExisting =
                    skipExisting
                            && outputsExist
                            && existingSummaryRows.containsKey(seriesKey);
            if (canReuseExisting) {
                summaryRows.add(SpectralOutputWriter.copySummaryRow(
                        existingSummaryRows.get(seriesKey),
                        "skipped_existing",
                        "Output files already existed."));
                coefficientRows.addAll(SpectralOutputWriter.copyCoefficientRows(
                        existingCoefficientRows.get(seriesKey),
                        "skipped_existing"));
                objectScoreRows.addAll(ObjectScoreWriter.copyObjectRows(
                        existingObjectRows.get(seriesKey),
                        "skipped_existing"));
                batchResult.skippedCount++;
                continue;
            }

            seriesToProcess.add(Integer.valueOf(meta.index));
        }

        if (batchResult.skippedCount > 0 && batchResult.totalImages > 0) {
            IJ.showProgress(batchResult.skippedCount, batchResult.totalImages);
        }

        List<ImagePlus> cachedImages = null;
        DeferredImageSupplier supplier = null;
        boolean useSharedCache = imageCache != null && parallelThreads <= 1 && !seriesToProcess.isEmpty();
        try {
            if (useSharedCache) {
                cachedImages = imageCache.getImages(directory);
                int highestSeriesIndex = -1;
                for (Integer seriesIndex : seriesToProcess) {
                    if (seriesIndex != null) {
                        highestSeriesIndex = Math.max(highestSeriesIndex, seriesIndex.intValue());
                    }
                }
                if (cachedImages == null || cachedImages.size() <= highestSeriesIndex) {
                    String message = "Falling back to deferred loading because the shared cache was unavailable.";
                    IJ.log("Spectral Decontamination: " + message);
                    recordWarn(message);
                    cachedImages = null;
                    useSharedCache = false;
                }
            }
            if (!seriesToProcess.isEmpty() && !useSharedCache) {
                supplier = wrapRunRecordSupplier(ImageSourceDispatcher.createSupplier(directory));
            }

            final List<ImagePlus> finalCachedImages = cachedImages;
            final DeferredImageSupplier finalSupplier = supplier;
            final AtomicInteger completed = new AtomicInteger(batchResult.skippedCount);

            if (parallelThreads > 1 && seriesToProcess.size() > 1 && finalCachedImages == null) {
                int workerCount = Math.max(1, Math.min(parallelThreads, seriesToProcess.size()));
                ExecutorService executor = Executors.newFixedThreadPool(workerCount);
                try {
                    List<Future<ProcessedSeriesResult>> futures =
                            new ArrayList<Future<ProcessedSeriesResult>>();
                    for (Integer seriesIndex : seriesToProcess) {
                        final int index = seriesIndex.intValue();
                        futures.add(executor.submit(new Callable<ProcessedSeriesResult>() {
                            @Override
                            public ProcessedSeriesResult call() {
                                try {
                                    return processSeries(directory, binConfig, config, registry,
                                            metas.get(index), candidateBySeries.get(Integer.valueOf(index)),
                                            needsCorrectedImage, needsMaskImage,
                                            objectScoringGoal,
                                            finalCachedImages, finalSupplier,
                                            runMetadata);
                                } finally {
                                    updateBatchProgress(completed.incrementAndGet(), batchResult.totalImages);
                                }
                            }
                        }));
                    }
                    for (Future<ProcessedSeriesResult> future : futures) {
                        try {
                            mergeProcessedResult(
                                    batchResult,
                                    summaryRows,
                                     coefficientRows,
                                     objectScoreRows,
                                     future.get());
                        } catch (Exception e) {
                            batchResult.failedCount++;
                            String message = "Spectral Decontamination worker failure: " + e.getMessage();
                            IJ.log(message);
                            recordError(message, e);
                        }
                    }
                } finally {
                    executor.shutdown();
                }
            } else {
                for (Integer seriesIndex : seriesToProcess) {
                    ProcessedSeriesResult processed = processSeries(directory, binConfig, config, registry,
                            metas.get(seriesIndex.intValue()),
                            candidateBySeries.get(seriesIndex),
                            needsCorrectedImage, needsMaskImage,
                            objectScoringGoal,
                            finalCachedImages, finalSupplier,
                            runMetadata);
                    mergeProcessedResult(
                            batchResult,
                            summaryRows,
                            coefficientRows,
                            objectScoreRows,
                            processed);
                    updateBatchProgress(completed.incrementAndGet(), batchResult.totalImages);
                }
            }
        } catch (Exception e) {
            batchResult.message = "Batch setup failed: " + e.getMessage();
            IJ.log("Spectral Decontamination: " + batchResult.message);
            recordError(batchResult.message, e);
        } finally {
            if (supplier != null) {
                supplier.shutdownPrefetch();
            }
        }

        try {
            SpectralOutputWriter.writePerImageSummary(directory, summaryRows, currentRunId());
            batchResult.perImageSummaryFile = SpectralOutputWriter.perImageSummaryFile(directory);
            recordOutput(batchResult.perImageSummaryFile, "csv");
            SpectralOutputWriter.writeCorrectionCoefficients(directory, coefficientRows, currentRunId());
            batchResult.correctionCoefficientsFile = SpectralOutputWriter.correctionCoefficientsFile(directory);
            recordOutput(batchResult.correctionCoefficientsFile, "csv");
            if (objectScoringGoal) {
                ObjectScoreWriter.writePerObjectScores(directory, objectScoreRows, currentRunId());
                batchResult.perObjectScoresFile = ObjectScoreWriter.perObjectScoresFile(directory);
                recordOutput(batchResult.perObjectScoresFile, "csv");
            }

            SpectralOutputWriter.AnalysisDetails details = new SpectralOutputWriter.AnalysisDetails();
            details.goalLabel = config.getGoal().getLabel();
            details.configVersion = runMetadata.configVersion;
            details.configId = runMetadata.configId;
            details.pipelinePresetId = runMetadata.pipelinePresetId;
            details.pipelineStackId = runMetadata.pipelineStackId;
            details.targetChannelName = channelName(config.getTargetChannelIndex(), binConfig);
            details.bleedThroughChannels = channelNames(config.getBleedThroughChannelIndexes(), binConfig);
            details.autofluorescenceChannels = channelNames(config.getAutofluorescenceChannelIndexes(), binConfig);
            details.excludedChannels = channelNames(config.getExcludedChannelIndexes(), binConfig);
            details.conditionSourceLabel = config.getConditionSource().getLabel();
            details.controlConditions = conditionNames(config.getControlConditionNames());
            details.experimentalConditions = conditionNames(config.getExperimentalConditionNames());
            details.pipelinePresetLabel = pipelinePresetLabel;
            details.pipelineDescription = pipelineDescription;
            details.zSliceSummary = binConfig.getZSliceConfig().summary();
            details.skipExisting = skipExisting;
            details.parallelThreads = parallelThreads;
            details.totalImages = batchResult.totalImages;
            details.processedImages = batchResult.processedCount;
            details.skippedImages = batchResult.skippedCount;
            details.failedImages = batchResult.failedCount;
            details.correctedImagesWritten = batchResult.correctedCount;
            details.maskImagesWritten = batchResult.maskCount;
            details.objectScoreRows = batchResult.objectScoreCount;
            details.cleanedObjectMapsWritten = batchResult.cleanedObjectMapCount;
            details.objectsKept = batchResult.objectsKeptCount;
            details.objectsRejected = batchResult.objectsRejectedCount;
            details.perImageSummaryPath = relativePath(directory, SpectralOutputWriter.perImageSummaryFile(directory));
            details.correctionCoefficientsPath =
                    relativePath(directory, SpectralOutputWriter.correctionCoefficientsFile(directory));
            details.perObjectScoresPath = batchResult.perObjectScoresFile == null
                    ? ""
                    : relativePath(directory, batchResult.perObjectScoresFile);
            details.previewSelectionPath = previewSelectionFile == null
                    ? ""
                    : relativePath(directory, previewSelectionFile);
            details.runtimeMs = System.currentTimeMillis() - startTimeMs;
            batchResult.analysisDetailsFile =
                    SpectralOutputWriter.writeAnalysisDetails(directory, details);
            recordOutput(batchResult.analysisDetailsFile, "txt");
            if (previewSelectionFile != null) {
                recordOutput(previewSelectionFile, "csv");
            }
            if (qualityReport != null && qualityReport.isEnabled()) {
                addSpectralQcReportSection(directory,
                        binConfig,
                        config,
                        runMetadata,
                        batchResult,
                        summaryRows,
                        renderedPreviews);
            }
            batchResult.success = batchResult.failedCount == 0;
        } catch (IOException e) {
            batchResult.message = "Failed writing Spectral Decontamination outputs: " + e.getMessage();
            batchResult.success = false;
            IJ.log("Spectral Decontamination: " + batchResult.message);
            recordError(batchResult.message, e);
        }

        IJ.log("Spectral Decontamination batch summary: processed=" + batchResult.processedCount
                + ", skipped=" + batchResult.skippedCount
                + ", failed=" + batchResult.failedCount
                + ", corrected images=" + batchResult.correctedCount
                + ", masks=" + batchResult.maskCount
                + ", object score rows=" + batchResult.objectScoreCount
                + ", cleaned object maps=" + batchResult.cleanedObjectMapCount
                + ", objects kept=" + batchResult.objectsKeptCount
                + ", objects removed=" + batchResult.objectsRejectedCount);
        return batchResult;
    }

    private ProcessedSeriesResult processSeries(String directory,
                                                BinConfig binConfig,
                                                SpectralDecontaminationConfig config,
                                                CorrectionFeatureRegistry registry,
                                                SeriesMeta meta,
                                                SpectralPreviewSelector.PreviewCandidate candidate,
                                                boolean needsCorrectedImage,
                                                boolean needsMaskImage,
                                                boolean objectScoringGoal,
                                                List<ImagePlus> cachedImages,
                                                DeferredImageSupplier supplier,
                                                SpectralOutputWriter.RunMetadata runMetadata) {
        SpectralPreviewSelector.PreviewCandidate resolvedCandidate =
                candidate == null ? fallbackCandidate(meta) : candidate;
        String targetChannelName = binConfig.channelNames.isEmpty()
                ? "target"
                : binConfig.channelNames.get(Math.max(0, config.getTargetChannelIndex()));
        SpectralOutputWriter.ExpectedOutputs expectedOutputs =
                SpectralOutputWriter.expectedOutputs(directory, meta.index, resolvedCandidate.seriesName, targetChannelName);
        String imageFolderRelativePath = imageFolderRelativePath(expectedOutputs);
        String zSliceRange = configuredZSliceRange(binConfig, meta.index);

        ImagePlus source = null;
        CorrectionPipeline.ExecutionState state = null;
        try {
            source = openSourceImage(meta.index, cachedImages, supplier);
            if (source == null) {
                throw new IllegalStateException("Series " + (meta.index + 1) + " could not be opened.");
            }
            source = applyConfiguredZSliceSubset(binConfig, meta.index, source, "Spectral Decontamination");

            state = CorrectionPipeline.ExecutionState.create(source, config);
            config.getCorrectionPipeline().execute(registry, state);

            File correctedImageFile = null;
            if (needsCorrectedImage) {
                ImagePlus correctedImage = state.getCorrectedImage();
                if (correctedImage == null) {
                    throw new IllegalStateException("The configured stack did not create a corrected image.");
                }
                correctedImage.setTitle("corrected");
                SpectralOutputWriter.saveCorrectedImage(correctedImage, expectedOutputs.correctedImageFile);
                correctedImageFile = expectedOutputs.correctedImageFile;
                recordOutput(correctedImageFile, "tif");
            }

            File maskImageFile = null;
            if (needsMaskImage) {
                ImagePlus maskImage = state.getMaskImage();
                if (maskImage == null) {
                    throw new IllegalStateException("The configured stack did not create a final mask.");
                }
                maskImage.setTitle("final_mask");
                SpectralOutputWriter.saveMaskImage(maskImage, expectedOutputs.maskImageFile);
                maskImageFile = expectedOutputs.maskImageFile;
                recordOutput(maskImageFile, "tif");
            }

            List<String> parameterMapPaths = new ArrayList<String>();
            for (Map.Entry<String, ImagePlus> entry : state.getParameterMaps().entrySet()) {
                if (entry.getValue() == null) {
                    continue;
                }
                File parameterMapFile = SpectralOutputWriter.parameterMapFile(expectedOutputs, entry.getKey());
                entry.getValue().setTitle(entry.getKey());
                SpectralOutputWriter.saveParameterMap(entry.getValue(), parameterMapFile);
                recordOutput(parameterMapFile, "tif");
                parameterMapPaths.add(relativePath(directory, parameterMapFile));
            }

            String conditionRole = conditionRoleForCandidate(resolvedCandidate, config);
            List<Map<String, String>> objectRows = new ArrayList<Map<String, String>>();
            List<String> cleanedObjectMapPaths = new ArrayList<String>();
            int objectsKept = 0;
            int objectsRejected = 0;
            int cleanedObjectMapCount = 0;
            if (objectScoringGoal) {
                List<ObjectScoreWriter.ObjectMapDescriptor> objectMaps =
                        ObjectScoreWriter.locateObjectLabelMaps(
                                directory,
                                meta.index,
                                resolvedCandidate.seriesName,
                                targetChannelName);
                if (objectMaps.isEmpty()) {
                    throw new IllegalStateException(
                            "No 3D Object Analysis label maps were found for " + targetChannelName
                                    + ". Run 3D Object Analysis first.");
                }

                for (ObjectScoreWriter.ObjectMapDescriptor objectMap : objectMaps) {
                    ImagePlus labelMap = null;
                    ImagePlus cleanedMap = null;
                    try {
                        recordInputFile(objectMap.getFile(), meta.index);
                        labelMap = IJ.openImage(objectMap.getFile().getAbsolutePath());
                        if (labelMap == null) {
                            throw new IllegalStateException(
                                    "Could not open object label map: " + objectMap.getFile().getAbsolutePath());
                        }
                        ObjectDecontaminationScorer.ScoreResult scoreResult =
                                ObjectDecontaminationScorer.score(
                                        labelMap,
                                        source,
                                        config,
                                        state.getCorrectedImage(),
                                        new ObjectDecontaminationScorer.Settings());
                        File cleanedObjectMapFile =
                                ObjectScoreWriter.cleanedObjectMapFile(
                                        expectedOutputs,
                                        targetChannelName,
                                        objectMap);
                        cleanedMap = scoreResult.getCleanedObjectMap();
                        ObjectScoreWriter.saveCleanedObjectMap(cleanedMap, cleanedObjectMapFile);
                        recordOutput(cleanedObjectMapFile, "tif");
                        cleanedObjectMapCount++;
                        cleanedObjectMapPaths.add(relativePath(directory, cleanedObjectMapFile));
                        objectsKept += scoreResult.getKeptCount();
                        objectsRejected += scoreResult.getRejectedCount();
                        objectRows.addAll(ObjectScoreWriter.buildRows(
                                directory,
                                meta.index,
                                resolvedCandidate.seriesName,
                                resolvedCandidate.conditionName,
                                conditionRole,
                                runMetadata,
                                targetChannelName,
                                objectMap,
                                scoreResult,
                                cleanedObjectMapFile,
                                "processed"));
                    } finally {
                        closeImages(labelMap, cleanedMap);
                    }
                }
            }

            Map<String, String> summaryRow = SpectralOutputWriter.buildPerImageSummaryRow(
                    meta.index,
                    resolvedCandidate.seriesName,
                    imageFolderRelativePath,
                    resolvedCandidate.conditionName,
                    conditionRole,
                    config.getGoal().getLabel(),
                    runMetadata,
                    runMetadata == null ? "" : runMetadata.pipelinePresetLabel,
                    runMetadata == null ? "" : runMetadata.pipelineDescription,
                    "processed",
                    correctedImageFile,
                    maskImageFile,
                    zSliceRange,
                    state.getFeatureSummaries(),
                    "",
                    directory);
            if (!parameterMapPaths.isEmpty()) {
                summaryRow.put("ParameterMapCount", String.valueOf(parameterMapPaths.size()));
                summaryRow.put("ParameterMapPaths", joinValues(parameterMapPaths, ";"));
            }
            if (objectScoringGoal) {
                summaryRow.put("ObjectScoreRows", String.valueOf(objectRows.size()));
                summaryRow.put("ObjectsKept", String.valueOf(objectsKept));
                summaryRow.put("ObjectsRejected", String.valueOf(objectsRejected));
                summaryRow.put("CleanedObjectMapsWritten", String.valueOf(cleanedObjectMapCount));
                summaryRow.put("PerObjectScoresPath",
                        relativePath(directory, ObjectScoreWriter.perObjectScoresFile(directory)));
                summaryRow.put("CleanedObjectMapPaths", joinValues(cleanedObjectMapPaths, ";"));
            }
            List<Map<String, String>> coefficients = SpectralOutputWriter.buildCoefficientRows(
                    meta.index,
                    resolvedCandidate.seriesName,
                    resolvedCandidate.conditionName,
                    conditionRole,
                    runMetadata,
                    "processed",
                    state.getFeatureSummaries());
            return ProcessedSeriesResult.success(
                    summaryRow,
                    coefficients,
                    objectRows,
                    objectsKept,
                    objectsRejected,
                    correctedImageFile != null,
                    maskImageFile != null,
                    cleanedObjectMapCount);
        } catch (Exception e) {
            String conditionRole = conditionRoleForCandidate(resolvedCandidate, config);
            Map<String, String> summaryRow = SpectralOutputWriter.buildPerImageSummaryRow(
                    meta.index,
                    resolvedCandidate.seriesName,
                    imageFolderRelativePath,
                    resolvedCandidate.conditionName,
                    conditionRole,
                    config.getGoal().getLabel(),
                    runMetadata,
                    runMetadata == null ? "" : runMetadata.pipelinePresetLabel,
                    runMetadata == null ? "" : runMetadata.pipelineDescription,
                    "error",
                    null,
                    null,
                    zSliceRange,
                    state == null ? new ArrayList<CorrectionPipeline.FeatureSummary>() : state.getFeatureSummaries(),
                    e.getMessage(),
                    directory);
            String message = "Spectral Decontamination: series "
                    + (meta.index + 1) + " failed: " + e.getMessage();
            IJ.log(message);
            recordError(message, e);
            return ProcessedSeriesResult.failure(summaryRow);
        } finally {
            closeImages(
                    source,
                    state == null ? null : state.getCorrectedImage(),
                    state == null ? null : state.getMaskImage(),
                    state == null ? null : state.getVetoMaskImage());
            closeParameterMaps(state == null ? null : state.getParameterMaps());
        }
    }

    private RocCalibrationResult calibrateRocThresholdSearchOnSelections(
            String directory,
            BinConfig binConfig,
            SpectralDecontaminationConfig config,
            List<SpectralPreviewSelector.PreviewSelection> selections,
            String searchScope) {
        List<SpectralPreviewSelector.PreviewCandidate> candidates =
                new ArrayList<SpectralPreviewSelector.PreviewCandidate>();
        if (selections != null) {
            for (SpectralPreviewSelector.PreviewSelection selection : selections) {
                if (selection != null && selection.candidate != null) {
                    candidates.add(selection.candidate);
                }
            }
        }
        return calibrateRocThresholdSearchOnCandidates(directory, binConfig, config, candidates, searchScope);
    }

    private RocCalibrationResult calibrateRocThresholdSearchOnCandidates(
            String directory,
            BinConfig binConfig,
            SpectralDecontaminationConfig config,
            List<SpectralPreviewSelector.PreviewCandidate> candidates,
            String searchScope) {
        if (!containsRocThresholdSearch(config)) {
            return RocCalibrationResult.success(config, null);
        }
        if (candidates == null || candidates.isEmpty()) {
            return RocCalibrationResult.failure("ROC threshold search requires condition-labelled images.");
        }

        CorrectionPipeline fullPipeline = config.getCorrectionPipeline();
        CorrectionPipeline prefixPipeline = prefixBeforeRocThresholdSearch(fullPipeline);
        if (prefixPipeline == null || prefixPipeline.isEmpty()) {
            return RocCalibrationResult.failure(
                    "ROC threshold search must come after a feature that creates a corrected image.");
        }

        SpectralDecontaminationConfig prefixConfig = config.copy();
        prefixConfig.setCorrectionPipeline(prefixPipeline);
        RocThresholdSearchFeature.Settings settings = RocThresholdSearchFeature.Settings.from(
                config.getFeatureSettings(RocThresholdSearchFeature.ID));
        settings.normalizeGrid();
        List<Double> thresholds = RocThresholdSearchFeature.buildThresholdGrid(settings);
        List<RocThresholdSearchFeature.MeasuredSample> measured =
                new ArrayList<RocThresholdSearchFeature.MeasuredSample>();

        DeferredImageSupplier supplier = null;
        try {
            supplier = wrapRunRecordSupplier(ImageSourceDispatcher.createSupplier(directory));
            CorrectionFeatureRegistry registry = CorrectionFeatureRegistry.getDefault();
            for (int i = 0; i < candidates.size(); i++) {
                SpectralPreviewSelector.PreviewCandidate candidate = candidates.get(i);
                if (candidate == null) continue;
                IJ.showStatus("Calibrating ROC threshold search " + (i + 1) + "/" + candidates.size());
                IJ.showProgress(i, candidates.size());

                ImagePlus source = null;
                CorrectionPipeline.ExecutionState state = null;
                try {
                    source = supplier.openSeriesMaterialized(candidate.seriesIndex);
                    source = applyConfiguredZSliceSubset(binConfig, candidate.seriesIndex, source,
                            "Spectral Decontamination ROC search");
                    state = CorrectionPipeline.ExecutionState.create(source, prefixConfig);
                    prefixPipeline.execute(registry, state);
                    if (state.getCorrectedImage() == null) {
                        return RocCalibrationResult.failure(
                                "ROC threshold search prefix did not create a corrected image.");
                    }

                    RocThresholdSearchFeature.SearchSample sample =
                            new RocThresholdSearchFeature.SearchSample(
                                    candidate.seriesName,
                                    candidate.conditionName,
                                    config.getControlConditionNames().contains(candidate.conditionName),
                                    config.getExperimentalConditionNames().contains(candidate.conditionName),
                                    source,
                                    state.getCorrectedImage());
                    measured.add(RocThresholdSearchFeature.measureSample(
                            sample,
                            config,
                            settings,
                            thresholds));
                } finally {
                    closeImages(
                            source,
                            state == null ? null : state.getCorrectedImage(),
                            state == null ? null : state.getMaskImage(),
                            state == null ? null : state.getVetoMaskImage());
                    closeParameterMaps(state == null ? null : state.getParameterMaps());
                }
            }

            RocSearchResult result = RocThresholdSearchFeature.searchMeasured(
                    measured,
                    settings,
                    thresholds,
                    searchScope);
            SpectralDecontaminationConfig calibrated = config.copy();
            RocThresholdSearchFeature.Settings calibratedSettings =
                    RocThresholdSearchFeature.settingsWithResult(settings, result);
            calibrated.setFeatureSettings(RocThresholdSearchFeature.ID,
                    calibratedSettings.toPipelineSettings());

            IJ.log("Spectral Decontamination ROC threshold search: metric=" + result.getMetricLabel()
                    + ", threshold=" + result.getSelectedThreshold()
                    + ", control false-positive rate=" + result.getSelectedFalsePositiveRate()
                    + ", experimental retention=" + result.getSelectedExperimentalRetention()
                    + ", scope=" + result.getSearchScope());
            return RocCalibrationResult.success(calibrated, result);
        } catch (Exception e) {
            return RocCalibrationResult.failure("ROC threshold search failed: " + e.getMessage());
        } finally {
            if (supplier != null) {
                supplier.shutdownPrefetch();
            }
            IJ.showProgress(1.0);
            IJ.showStatus("ROC threshold search calibrated");
        }
    }

    private boolean containsRocThresholdSearch(SpectralDecontaminationConfig config) {
        return containsFeature(config, RocThresholdSearchFeature.ID);
    }

    private boolean containsFeature(SpectralDecontaminationConfig config, String featureId) {
        if (config == null || !config.hasCorrectionPipeline() || featureId == null) {
            return false;
        }
        return config.getCorrectionPipeline().getFeatureIds().contains(featureId);
    }

    private CorrectionPipeline prefixBeforeRocThresholdSearch(CorrectionPipeline pipeline) {
        if (pipeline == null) {
            return CorrectionPipeline.empty();
        }
        List<String> prefixIds = new ArrayList<String>();
        for (String featureId : pipeline.getFeatureIds()) {
            if (RocThresholdSearchFeature.ID.equals(featureId)) {
                CorrectionPipeline prefix = new CorrectionPipeline();
                prefix.setExpertMode(pipeline.isExpertMode());
                prefix.setPresetId(CorrectionPipeline.CUSTOM_PRESET_ID);
                prefix.setFeatureIds(prefixIds);
                return prefix;
            }
            prefixIds.add(featureId);
        }
        return CorrectionPipeline.empty();
    }

    private void mergeProcessedResult(BatchRunResult batchResult,
                                      List<Map<String, String>> summaryRows,
                                      List<Map<String, String>> coefficientRows,
                                      List<Map<String, String>> objectScoreRows,
                                      ProcessedSeriesResult processed) {
        if (processed == null) {
            return;
        }
        if (processed.summaryRow != null) {
            summaryRows.add(processed.summaryRow);
        }
        if (processed.coefficientRows != null && !processed.coefficientRows.isEmpty()) {
            coefficientRows.addAll(processed.coefficientRows);
        }
        if (processed.objectScoreRows != null && !processed.objectScoreRows.isEmpty()) {
            objectScoreRows.addAll(processed.objectScoreRows);
        }
        if (processed.failed) {
            batchResult.failedCount++;
            return;
        }
        batchResult.processedCount++;
        if (processed.correctedImageWritten) {
            batchResult.correctedCount++;
        }
        if (processed.maskImageWritten) {
            batchResult.maskCount++;
        }
        batchResult.cleanedObjectMapCount += processed.cleanedObjectMapCount;
        batchResult.objectScoreCount += processed.objectScoreRows == null
                ? 0
                : processed.objectScoreRows.size();
        batchResult.objectsKeptCount += processed.objectsKeptCount;
        batchResult.objectsRejectedCount += processed.objectsRejectedCount;
    }

    private ImagePlus openSourceImage(int seriesIndex,
                                      List<ImagePlus> cachedImages,
                                      DeferredImageSupplier supplier) throws Exception {
        if (cachedImages != null) {
            if (seriesIndex < 0 || seriesIndex >= cachedImages.size()) {
                throw new IllegalArgumentException("Series index " + seriesIndex + " is outside the shared cache.");
            }
            ImagePlus cached = cachedImages.get(seriesIndex);
            return cached == null ? null : cached.duplicate();
        }
        if (supplier == null) {
            return null;
        }
        return supplier.openSeriesMaterialized(seriesIndex);
    }

    private DeferredImageSupplier wrapRunRecordSupplier(final DeferredImageSupplier delegate) {
        if (delegate == null || runRecordContext == null) {
            return delegate;
        }
        return new DeferredImageSupplier(delegate) {
            @Override
            public ImagePlus openSeries(int seriesIndex) throws Exception {
                return recordOpen(seriesIndex, false);
            }

            @Override
            public ImagePlus openSeriesMaterialized(int seriesIndex) throws Exception {
                return recordOpen(seriesIndex, true);
            }

            private ImagePlus recordOpen(int seriesIndex, boolean materialized) throws Exception {
                File source = sourceFileForSeries(delegate, seriesIndex);
                AnalysisRunContext.InputHandle inputHandle =
                        runRecordContext.recordInputStart(source, seriesIndex, null);
                long started = System.currentTimeMillis();
                try {
                    ImagePlus image = materialized
                            ? delegate.openSeriesMaterialized(seriesIndex)
                            : delegate.openSeries(seriesIndex);
                    runRecordContext.recordInputEnd(inputHandle,
                            image == null ? "failed" : "processed",
                            Math.max(0L, System.currentTimeMillis() - started));
                    return image;
                } catch (Exception e) {
                    runRecordContext.recordInputEnd(inputHandle, "failed",
                            Math.max(0L, System.currentTimeMillis() - started));
                    throw e;
                }
            }
        };
    }

    private static File sourceFileForSeries(DeferredImageSupplier supplier, int seriesIndex) {
        if (supplier == null) {
            return null;
        }
        try {
            return supplier.getContainerFileForSeries(seriesIndex);
        } catch (Exception e) {
            try {
                return supplier.getContainerFile();
            } catch (Exception ignored) {
                return null;
            }
        }
    }

    private void recordInputFile(File file, int seriesIndex) {
        if (runRecordContext == null || file == null) {
            return;
        }
        AnalysisRunContext.InputHandle inputHandle =
                runRecordContext.recordInputStart(file, seriesIndex, null);
        runRecordContext.recordInputEnd(inputHandle,
                file.isFile() ? "processed" : "failed", 0L);
    }

    private void recordOutput(File file, String kind) {
        if (runRecordContext != null && file != null) {
            runRecordContext.recordOutput(file, kind);
        }
    }

    private String currentRunId() {
        return RunIdCsv.runId(runRecordContext);
    }

    private void recordWarn(String message) {
        if (runRecordContext != null) {
            runRecordContext.warn(message);
        }
    }

    private void recordError(String message, Throwable t) {
        if (runRecordContext != null) {
            runRecordContext.error(message, t);
        }
    }

    private ImagePlus applyConfiguredZSliceSubset(BinConfig cfg,
                                                  int seriesIndex,
                                                  ImagePlus source,
                                                  String contextLabel) {
        if (source == null || cfg == null || !cfg.usesZSliceSubset()) {
            return source;
        }
        ZSliceRange range = cfg.getZSliceRange(seriesIndex);
        if (range == null) {
            recordWarn(contextLabel + ": no saved z-slice range for series "
                    + (seriesIndex + 1) + ". Using full stack.");
            IJ.log("WARNING: " + contextLabel + ": no saved z-slice range for series " + (seriesIndex + 1)
                    + ". Using full stack.");
            return source;
        }

        int slices = Math.max(1, source.getNSlices());
        if (!range.isValidFor(slices) || range.coversFullStack(slices)) {
            return source;
        }

        int channels = Math.max(1, source.getNChannels());
        int frames = Math.max(1, source.getNFrames());
        ImageStack subsetStack = new ImageStack(source.getWidth(), source.getHeight());

        for (int frame = 1; frame <= frames; frame++) {
            for (int slice = range.startSlice; slice <= range.endSlice; slice++) {
                for (int channel = 1; channel <= channels; channel++) {
                    int stackIndex = source.getStackIndex(channel, slice, frame);
                    ImageProcessor duplicateProcessor = source.getStack().getProcessor(stackIndex).duplicate();
                    subsetStack.addSlice(duplicateProcessor);
                }
            }
        }

        ImagePlus subset = new ImagePlus(source.getTitle(), subsetStack);
        subset.setDimensions(channels, range.count(), frames);
        if (channels > 1 || range.count() > 1 || frames > 1) {
            subset.setOpenAsHyperStack(true);
        }
        if (source.getCalibration() != null) {
            subset.setCalibration(source.getCalibration().copy());
        }

        source.changes = false;
        source.close();
        source.flush();
        return subset;
    }

    private void updateBatchProgress(int completed, int totalImages) {
        if (totalImages <= 0) {
            IJ.showProgress(1.0);
            IJ.showStatus("Spectral Decontamination complete");
            return;
        }
        IJ.showProgress(completed, totalImages);
        IJ.showStatus("Spectral Decontamination " + completed + "/" + totalImages);
    }

    private String imageFolderRelativePath(SpectralOutputWriter.ExpectedOutputs expectedOutputs) {
        return expectedOutputs == null ? "" : expectedOutputs.imageOutputRelativePath;
    }

    private String configuredZSliceRange(BinConfig cfg, int seriesIndex) {
        if (cfg == null || !cfg.usesZSliceSubset()) {
            return "Full stack";
        }
        ZSliceRange range = cfg.getZSliceRange(seriesIndex);
        return range == null ? "Full stack" : range.toToken();
    }

    private SpectralPreviewSelector.PreviewCandidate fallbackCandidate(SeriesMeta meta) {
        String seriesName = meta == null || meta.name == null || meta.name.trim().isEmpty()
                ? "Series " + ((meta == null ? 0 : meta.index) + 1)
                : meta.name.trim();
        String animalName = ConditionManifestIO.extractAnimalName(seriesName);
        if (animalName == null || animalName.trim().isEmpty()) {
            animalName = seriesName;
        }
        return new SpectralPreviewSelector.PreviewCandidate(
                meta == null ? 0 : meta.index,
                seriesName,
                animalName,
                animalName);
    }

    private String conditionRoleForCandidate(SpectralPreviewSelector.PreviewCandidate candidate,
                                             SpectralDecontaminationConfig config) {
        if (candidate == null || config == null) {
            return "unassigned";
        }
        boolean isControl = config.getControlConditionNames().contains(candidate.conditionName);
        boolean isExperimental = config.getExperimentalConditionNames().contains(candidate.conditionName);
        if (isControl && isExperimental) {
            return "control+experimental";
        }
        if (isControl) {
            return "control";
        }
        if (isExperimental) {
            return "experimental";
        }
        return "unassigned";
    }

    private void closeImages(ImagePlus... images) {
        Set<ImagePlus> seen = Collections.newSetFromMap(new IdentityHashMap<ImagePlus, Boolean>());
        if (images == null) {
            return;
        }
        for (ImagePlus image : images) {
            if (image == null || !seen.add(image)) {
                continue;
            }
            image.changes = false;
            image.close();
            image.flush();
        }
    }

    private void closeParameterMaps(Map<String, ImagePlus> parameterMaps) {
        if (parameterMaps == null || parameterMaps.isEmpty()) {
            return;
        }
        closeImages(parameterMaps.values().toArray(new ImagePlus[parameterMaps.size()]));
    }

    private String relativePath(String directory, File file) {
        if (directory == null || file == null) {
            return "";
        }
        try {
            return new File(directory).getAbsoluteFile().toPath().normalize()
                    .relativize(file.getAbsoluteFile().toPath().normalize())
                    .toString();
        } catch (Exception e) {
            return file.getPath();
        }
    }

    private String joinLines(List<String> lines) {
        if (lines == null || lines.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) {
                sb.append('\n');
            }
            sb.append(lines.get(i));
        }
        return sb.toString();
    }

    private String joinValues(List<String> values, String separator) {
        if (values == null || values.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                sb.append(separator);
            }
            sb.append(values.get(i));
        }
        return sb.toString();
    }

    private void showInteractiveBatchResult(String directory,
                                            BatchRunResult batchResult,
                                            PreviewRunResult previewResult) {
        if (batchResult == null) {
            IJ.log("Spectral Decontamination: batch run did not produce a result.");
            return;
        }

        StringBuilder message = new StringBuilder();
        message.append("Config:\n")
                .append(SpectralDecontaminationConfigIO.configFile(directory).getAbsolutePath())
                .append("\n\nPer-image summary:\n");
        if (batchResult.perImageSummaryFile != null) {
            message.append(batchResult.perImageSummaryFile.getAbsolutePath());
        } else {
            message.append("(not written)");
        }
        message.append("\n\nCorrection coefficients:\n");
        if (batchResult.correctionCoefficientsFile != null) {
            message.append(batchResult.correctionCoefficientsFile.getAbsolutePath());
        } else {
            message.append("(not written)");
        }
        if (batchResult.perObjectScoresFile != null) {
            message.append("\n\nPer-object scores:\n")
                    .append(batchResult.perObjectScoresFile.getAbsolutePath());
        }
        message.append("\n\nAnalysis details:\n");
        if (batchResult.analysisDetailsFile != null) {
            message.append(batchResult.analysisDetailsFile.getAbsolutePath());
        } else {
            message.append("(not written)");
        }
        if (previewResult != null && previewResult.success && previewResult.outputFile != null) {
            message.append("\n\nPreview selection:\n")
                    .append(previewResult.outputFile.getAbsolutePath());
        }
        message.append("\n\nProcessed: ").append(batchResult.processedCount)
                .append("\nSkipped existing: ").append(batchResult.skippedCount)
                .append("\nFailed: ").append(batchResult.failedCount);
        if (batchResult.objectScoreCount > 0 || batchResult.cleanedObjectMapCount > 0) {
            message.append("\nObject score rows: ").append(batchResult.objectScoreCount)
                    .append("\nCleaned object maps: ").append(batchResult.cleanedObjectMapCount)
                    .append("\nObjects kept: ").append(batchResult.objectsKeptCount)
                    .append("\nObjects removed: ").append(batchResult.objectsRejectedCount);
        }
        if (batchResult.message != null && !batchResult.message.trim().isEmpty()) {
            message.append("\n\n").append(batchResult.message.trim());
        }

        // Non-blocking completion summary. A modal dialog here would stall
        // unattended batch runs until a human clicked OK.
        IJ.log("Spectral Decontamination completed:\n" + message);
        IJ.showStatus("Spectral Decontamination finished.");
    }

    private void logFeatureStackStatus(SpectralDecontaminationConfig config, BinConfig binConfig) {
        CorrectionFeatureRegistry registry = CorrectionFeatureRegistry.getDefault();
        if (config == null || !config.hasCorrectionPipeline()) {
            IJ.log("Spectral Decontamination correction stack is not configured yet.");
            IJ.log("Run Spectral Decontamination interactively to choose a valid correction stack.");
            return;
        }

        List<String> errors = validateBatchConfig(config, binConfig);
        if (errors.isEmpty()) {
            IJ.log("Correction stack is runnable: " + config.getCorrectionPipeline().describe(registry));
        } else {
            IJ.log("Correction stack needs attention:");
            for (String error : errors) {
                IJ.log("  - " + error);
            }
        }
    }

    private String[] presetLabelsWithCustom(CorrectionFeatureRegistry registry, boolean includeExpert) {
        List<String> labels = new ArrayList<String>();
        labels.add(CUSTOM_PRESET_LABEL);
        labels.add(registry.getPreset(CorrectionFeatureRegistry.PRESET_BASIC).getDisplayName());
        labels.add(registry.getPreset(CorrectionFeatureRegistry.PRESET_AUTOFLUORESCENCE).getDisplayName());
        labels.add(registry.getPreset(CorrectionFeatureRegistry.PRESET_BLEED_THROUGH).getDisplayName());
        labels.add(registry.getPreset(CorrectionFeatureRegistry.PRESET_ROC_THRESHOLD).getDisplayName());
        if (includeExpert) {
            labels.add(registry.getPreset(CorrectionFeatureRegistry.PRESET_EXPERT).getDisplayName());
        }
        return labels.toArray(new String[0]);
    }

    private String presetLabelForId(CorrectionFeatureRegistry registry, String presetId) {
        if (presetId == null || CorrectionPipeline.CUSTOM_PRESET_ID.equalsIgnoreCase(presetId)) {
            return CUSTOM_PRESET_LABEL;
        }
        CorrectionFeatureRegistry.PresetDefinition preset = registry.getPreset(presetId);
        return preset == null ? CUSTOM_PRESET_LABEL : preset.getDisplayName();
    }

    private String presetIdForLabel(CorrectionFeatureRegistry registry, String presetLabel) {
        String cleaned = safeText(presetLabel);
        if (cleaned.isEmpty() || CUSTOM_PRESET_LABEL.equals(cleaned)) {
            return CorrectionPipeline.CUSTOM_PRESET_ID;
        }
        for (CorrectionFeatureRegistry.PresetDefinition preset : registry.getAvailablePresets(
                new SpectralDecontaminationConfig(), true, false)) {
            if (preset.getDisplayName().equals(cleaned)) {
                return preset.getId();
            }
        }
        if (registry.getPreset(CorrectionFeatureRegistry.PRESET_BASIC).getDisplayName().equals(cleaned)) {
            return CorrectionFeatureRegistry.PRESET_BASIC;
        }
        if (registry.getPreset(CorrectionFeatureRegistry.PRESET_AUTOFLUORESCENCE).getDisplayName().equals(cleaned)) {
            return CorrectionFeatureRegistry.PRESET_AUTOFLUORESCENCE;
        }
        if (registry.getPreset(CorrectionFeatureRegistry.PRESET_BLEED_THROUGH).getDisplayName().equals(cleaned)) {
            return CorrectionFeatureRegistry.PRESET_BLEED_THROUGH;
        }
        if (registry.getPreset(CorrectionFeatureRegistry.PRESET_ROC_THRESHOLD).getDisplayName().equals(cleaned)) {
            return CorrectionFeatureRegistry.PRESET_ROC_THRESHOLD;
        }
        if (registry.getPreset(CorrectionFeatureRegistry.PRESET_EXPERT).getDisplayName().equals(cleaned)) {
            return CorrectionFeatureRegistry.PRESET_EXPERT;
        }
        return CorrectionPipeline.CUSTOM_PRESET_ID;
    }

    private List<String> currentFeatureSelections(JComboBox<String>[] featureChoices) {
        List<String> selections = new ArrayList<String>();
        for (JComboBox<String> featureChoice : featureChoices) {
            selections.add(safeText((String) featureChoice.getSelectedItem()));
        }
        return selections;
    }

    private List<String> singleItem(String value) {
        List<String> items = new ArrayList<String>();
        items.add(value);
        return items;
    }

    private void setComboItems(JComboBox<String> combo, List<String> items, String selected) {
        combo.removeAllItems();
        if (items == null || items.isEmpty()) {
            combo.addItem(NONE_FEATURE_LABEL);
            combo.setSelectedItem(NONE_FEATURE_LABEL);
            return;
        }
        for (String item : items) {
            combo.addItem(item);
        }
        if (selected != null && items.contains(selected)) {
            combo.setSelectedItem(selected);
        } else {
            combo.setSelectedIndex(0);
        }
    }

    private String featureDisplayName(CorrectionFeatureRegistry registry, String featureId) {
        CorrectionFeature feature = registry.getFeature(featureId);
        return feature == null ? NONE_FEATURE_LABEL : feature.getDisplayName();
    }

    private String featureDescription(CorrectionFeatureRegistry registry, String featureLabel) {
        CorrectionFeature feature = registry.getFeature(featureIdForDisplayName(registry, featureLabel));
        return feature == null ? "" : feature.getDescription();
    }

    private String featureIdForDisplayName(CorrectionFeatureRegistry registry, String featureLabel) {
        String cleaned = safeText(featureLabel);
        if (cleaned.isEmpty() || NONE_FEATURE_LABEL.equals(cleaned)) return "";
        for (CorrectionFeature feature : registry.getFeatures()) {
            if (feature.getDisplayName().equals(cleaned)) {
                return feature.getId();
            }
        }
        return "";
    }

    private String helpText(String text) {
        return "<html><body style='width:280px;'>" + safeText(text) + "</body></html>";
    }

    private String messageText(String text) {
        return "<html><body style='width:320px;'>" + safeText(text) + "</body></html>";
    }

    private String safeText(String value) {
        return value == null ? "" : value.trim();
    }

    private ConditionSetup showConditionSetup(String directory, SpectralDecontaminationConfig config) {
        List<SeriesMeta> metas;
        try {
            metas = ImageSourceDispatcher.readAllMetadata(directory);
        } catch (Exception e) {
            String message = "Could not read image series for preview selection: " + e.getMessage();
            IJ.log("Spectral Decontamination: " + message);
            IJ.showMessage("Spectral Decontamination", message);
            return null;
        }

        SpectralDecontaminationConfig.ConditionSource source = showConditionSourceDialog(directory, config);
        if (source == null) return null;
        config.setConditionSource(source);

        LinkedHashMap<String, String> assignments = resolveConditionAssignments(directory, metas, source);
        if (source == SpectralDecontaminationConfig.ConditionSource.USE_EXISTING_CONDITION_FILE
                && ConditionManifestIO.getExistingFile(directory) == null) {
            IJ.showMessage("Spectral Decontamination",
                    "No existing condition file was found. Assign conditions manually for this preview.");
            assignments = showManualAssignmentDialog(assignments);
            if (assignments == null) return null;
            config.setConditionSource(SpectralDecontaminationConfig.ConditionSource.ASSIGN_MANUALLY);
            saveManualAssignments(directory, assignments);
        } else if (source == SpectralDecontaminationConfig.ConditionSource.ASSIGN_MANUALLY) {
            assignments = showManualAssignmentDialog(assignments);
            if (assignments == null) return null;
            saveManualAssignments(directory, assignments);
        }

        List<SpectralPreviewSelector.PreviewCandidate> candidates =
                SpectralPreviewSelector.buildCandidatesFromAssignments(metas, assignments);
        List<String> conditions = SpectralPreviewSelector.conditionNames(candidates);
        if (conditions.size() < 2 && source != SpectralDecontaminationConfig.ConditionSource.ASSIGN_MANUALLY) {
            IJ.showMessage("Spectral Decontamination",
                    "Fewer than two conditions were resolved. Assign conditions manually for control/experimental preview.");
            assignments = showManualAssignmentDialog(assignments);
            if (assignments == null) return null;
            config.setConditionSource(SpectralDecontaminationConfig.ConditionSource.ASSIGN_MANUALLY);
            saveManualAssignments(directory, assignments);
            candidates = SpectralPreviewSelector.buildCandidatesFromAssignments(metas, assignments);
            conditions = SpectralPreviewSelector.conditionNames(candidates);
        }

        RoleSelection roles = showConditionRoleDialog(config, conditions);
        if (roles == null) return null;
        config.setControlConditionNames(roles.controlConditions);
        config.setExperimentalConditionNames(roles.experimentalConditions);

        return new ConditionSetup(config, candidates);
    }

    private SpectralDecontaminationConfig.ConditionSource showConditionSourceDialog(
            String directory,
            SpectralDecontaminationConfig config) {
        PipelineDialog dialog = new PipelineDialog("Spectral Decontamination");
        dialog.addHeader("Conditions");
        dialog.addMessage("Choose how Spectral Decontamination should assign images to experimental conditions.");
        String defaultLabel = config.getConditionSource().getLabel();
        if (ConditionManifestIO.getExistingFile(directory) == null
                && config.getConditionSource()
                == SpectralDecontaminationConfig.ConditionSource.USE_EXISTING_CONDITION_FILE) {
            defaultLabel = SpectralDecontaminationConfig.ConditionSource.INFER_FROM_IMAGE_NAMES.getLabel();
        }
        dialog.addChoice("Condition source", SpectralDecontaminationConfig.ConditionSource.labels(), defaultLabel);
        if (!dialog.showDialog()) return null;
        return SpectralDecontaminationConfig.ConditionSource.fromLabel(dialog.getNextChoice());
    }

    private LinkedHashMap<String, String> resolveConditionAssignments(
            String directory,
            List<SeriesMeta> metas,
            SpectralDecontaminationConfig.ConditionSource source) {
        LinkedHashSet<String> animals = animalNames(metas);
        if (source == SpectralDecontaminationConfig.ConditionSource.USE_EXISTING_CONDITION_FILE
                && ConditionManifestIO.getExistingFile(directory) != null) {
            return ConditionManifestIO.resolveAssignments(directory, animals);
        }
        if (source == SpectralDecontaminationConfig.ConditionSource.ASSIGN_MANUALLY
                && ConditionManifestIO.getExistingFile(directory) != null) {
            return ConditionManifestIO.resolveAssignments(directory, animals);
        }
        return SpectralPreviewSelector.inferAssignments(animals);
    }

    private LinkedHashMap<String, String> showManualAssignmentDialog(LinkedHashMap<String, String> defaults) {
        PipelineDialog dialog = new PipelineDialog("Spectral Decontamination");
        dialog.addHeader("Manual Condition Assignment");
        dialog.addMessage("Assign each animal or image group to a condition.");
        List<String> animals = new ArrayList<String>(defaults.keySet());
        for (String animal : animals) {
            dialog.addStringField(animal, defaults.get(animal), 18);
        }
        if (!dialog.showDialog()) return null;

        LinkedHashMap<String, String> assignments = new LinkedHashMap<String, String>();
        for (String animal : animals) {
            String condition = dialog.getNextString();
            if (condition == null || condition.trim().isEmpty()) {
                condition = animal;
            }
            assignments.put(animal, condition.trim());
        }
        return assignments;
    }

    private RoleSelection showConditionRoleDialog(SpectralDecontaminationConfig config,
                                                  List<String> conditions) {
        if (conditions == null || conditions.isEmpty()) {
            IJ.showMessage("Spectral Decontamination",
                    "No conditions were available for preview selection.");
            return null;
        }

        List<String> defaultControls = config.getControlConditionNames().isEmpty()
                ? firstCondition(conditions)
                : new ArrayList<String>(config.getControlConditionNames());
        List<String> defaultExperimentals = config.getExperimentalConditionNames().isEmpty()
                ? allExcept(conditions, defaultControls)
                : new ArrayList<String>(config.getExperimentalConditionNames());

        while (true) {
            PipelineDialog dialog = new PipelineDialog("Spectral Decontamination");
            dialog.addHeader("Control Conditions");
            dialog.addMessage("Controls are expected to have little or no true target signal.");
            for (String condition : conditions) {
                dialog.addToggle(condition, defaultControls.contains(condition));
            }
            dialog.addHeader("Experimental Conditions");
            dialog.addMessage("Experimental conditions are expected to contain target signal.");
            for (String condition : conditions) {
                dialog.addToggle(condition, defaultExperimentals.contains(condition));
            }
            if (!dialog.showDialog()) return null;

            List<String> controls = readConditionToggles(dialog, conditions);
            List<String> experimentals = readConditionToggles(dialog, conditions);
            List<String> errors = validateConditionRoles(conditions, controls, experimentals);
            if (errors.isEmpty()) {
                return new RoleSelection(controls, experimentals);
            }
            defaultControls = controls;
            defaultExperimentals = experimentals;
            IJ.showMessage("Spectral Decontamination", formatErrors(errors));
        }
    }

    private PreviewRunResult runPreviewSelection(String directory,
                                                 SpectralDecontaminationConfig config,
                                                 List<SpectralPreviewSelector.PreviewCandidate> candidates) {
        try {
            if (candidates == null) {
                List<SeriesMeta> metas = ImageSourceDispatcher.readAllMetadata(directory);
                LinkedHashMap<String, String> assignments =
                        resolveConditionAssignments(directory, metas, config.getConditionSource());
                candidates = SpectralPreviewSelector.buildCandidatesFromAssignments(metas, assignments);
                applyDefaultConditionRoles(config, SpectralPreviewSelector.conditionNames(candidates));
            }

            List<SpectralPreviewSelector.ScoredImage> scored = scorePreviewCandidates(directory, candidates, config);
            List<SpectralPreviewSelector.PreviewSelection> selections =
                    SpectralPreviewSelector.selectPreviewImages(
                            scored,
                            config.getControlConditionNames(),
                            config.getExperimentalConditionNames(),
                            !config.getBleedThroughChannelIndexes().isEmpty());
            File outputFile = SpectralPreviewSelector.previewSelectionFile(directory);
            SpectralPreviewSelector.writePreviewSelection(outputFile, buildRunMetadata(config),
                    selections, currentRunId());
            recordOutput(outputFile, "csv");
            IJ.log("Spectral Decontamination preview selection saved: " + outputFile.getAbsolutePath());
            logPreviewSelection(selections);
            return PreviewRunResult.success(outputFile, selections);
        } catch (Exception e) {
            String message = "Preview selection failed: " + e.getMessage();
            IJ.log("Spectral Decontamination: " + message);
            recordWarn(message);
            return PreviewRunResult.failure(message);
        }
    }

    private PreviewRunResult rewritePreviewSelectionFile(String directory,
                                                         SpectralDecontaminationConfig config,
                                                         PreviewRunResult previewResult) {
        if (previewResult == null || !previewResult.success || previewResult.selections.isEmpty()) {
            return previewResult;
        }
        try {
            File outputFile = SpectralPreviewSelector.previewSelectionFile(directory);
            SpectralPreviewSelector.writePreviewSelection(outputFile, buildRunMetadata(config),
                    previewResult.selections, currentRunId());
            recordOutput(outputFile, "csv");
            return PreviewRunResult.success(outputFile, previewResult.selections);
        } catch (IOException e) {
            recordWarn("Could not refresh preview selection metadata. " + e.getMessage());
            IJ.log("Spectral Decontamination: could not refresh preview selection metadata. " + e.getMessage());
            return previewResult;
        }
    }

    private List<SpectralPreviewRenderer.RenderedPreview> renderPreviewSelectionsForReport(
            String directory,
            BinConfig binConfig,
            SpectralDecontaminationConfig config,
            PreviewRunResult previewResult) {
        List<SpectralPreviewRenderer.RenderedPreview> rendered =
                new ArrayList<SpectralPreviewRenderer.RenderedPreview>();
        if (qualityReport == null || !qualityReport.isEnabled()) {
            return rendered;
        }
        if (previewResult == null || !previewResult.success || previewResult.selections.isEmpty()) {
            return rendered;
        }
        try {
            rendered.addAll(SpectralPreviewRenderer.render(
                    directory,
                    binConfig,
                    config,
                    previewResult.selections));
        } catch (Exception e) {
            recordWarn("Could not render QC preview images. " + e.getMessage());
            IJ.log("Spectral Decontamination: could not render QC preview images. " + e.getMessage());
        }
        return rendered;
    }

    private SpectralOutputWriter.RunMetadata buildRunMetadata(SpectralDecontaminationConfig config) {
        return SpectralOutputWriter.RunMetadata.fromConfig(
                config,
                CorrectionFeatureRegistry.getDefault());
    }

    private List<SpectralPreviewSelector.ScoredImage> scorePreviewCandidates(
            String directory,
            List<SpectralPreviewSelector.PreviewCandidate> candidates,
            SpectralDecontaminationConfig config) throws Exception {
        List<SpectralPreviewSelector.ScoredImage> scored =
                new ArrayList<SpectralPreviewSelector.ScoredImage>();
        if (candidates == null || candidates.isEmpty()) return scored;

        DeferredImageSupplier supplier = wrapRunRecordSupplier(ImageSourceDispatcher.createSupplier(directory));
        try {
            for (int i = 0; i < candidates.size(); i++) {
                SpectralPreviewSelector.PreviewCandidate candidate = candidates.get(i);
                IJ.showStatus("Scoring preview candidates " + (i + 1) + "/" + candidates.size());
                IJ.showProgress(i, candidates.size());
                ImagePlus image = null;
                try {
                    image = supplier.openSeriesMaterialized(candidate.seriesIndex);
                    SpectralPreviewSelector.ImageScores scores =
                            SpectralPreviewSelector.scoreImage(image, config);
                    scored.add(new SpectralPreviewSelector.ScoredImage(candidate, scores));
                } finally {
                    if (image != null) {
                        image.changes = false;
                        image.close();
                        image.flush();
                    }
                }
            }
        } finally {
            supplier.shutdownPrefetch();
            IJ.showProgress(1.0);
            IJ.showStatus("Preview selection scored");
        }
        return scored;
    }

    private void applyDefaultConditionRoles(SpectralDecontaminationConfig config, List<String> conditions) {
        if (conditions == null || conditions.isEmpty()) return;
        if (!config.getControlConditionNames().isEmpty()
                || !config.getExperimentalConditionNames().isEmpty()) {
            return;
        }
        List<String> controls = firstCondition(conditions);
        List<String> experimentals = allExcept(conditions, controls);
        config.setControlConditionNames(controls);
        config.setExperimentalConditionNames(experimentals);
    }

    private void logPreviewSelection(List<SpectralPreviewSelector.PreviewSelection> selections) {
        if (selections == null || selections.isEmpty()) {
            IJ.log("  No preview images were selected.");
            return;
        }
        IJ.log("  Preview images selected: " + selections.size());
        for (SpectralPreviewSelector.PreviewSelection selection : selections) {
            IJ.log("  - Series " + (selection.candidate.seriesIndex + 1)
                    + " [" + selection.candidate.conditionName + ", "
                    + selection.conditionRole + "]: " + selection.selectionRole);
        }
    }

    private void addSpectralQcReportSection(String directory,
                                            BinConfig binConfig,
                                            SpectralDecontaminationConfig config,
                                            SpectralOutputWriter.RunMetadata runMetadata,
                                            BatchRunResult batchResult,
                                            List<Map<String, String>> summaryRows,
                                            List<SpectralPreviewRenderer.RenderedPreview> renderedPreviews) {
        if (qualityReport == null || !qualityReport.isEnabled()) {
            return;
        }
        Map<String, String> params = buildSpectralQcParams(
                directory,
                binConfig,
                config,
                runMetadata,
                batchResult);
        List<QualityReport.SpectralPreviewQC> previews =
                buildSpectralPreviewQcEntries(summaryRows, renderedPreviews);
        qualityReport.addSpectralDecontaminationSection(params, previews);
    }

    private Map<String, String> buildSpectralQcParams(String directory,
                                                      BinConfig binConfig,
                                                      SpectralDecontaminationConfig config,
                                                      SpectralOutputWriter.RunMetadata runMetadata,
                                                      BatchRunResult batchResult) {
        LinkedHashMap<String, String> params = new LinkedHashMap<String, String>();
        params.put("Goal", config.getGoal().getLabel());
        params.put("Config Version", String.valueOf(runMetadata.configVersion));
        params.put("Config ID", runMetadata.configId);
        params.put("Correction Preset", runMetadata.pipelinePresetLabel);
        params.put("Correction Preset ID", runMetadata.pipelinePresetId);
        params.put("Correction Stack", runMetadata.pipelineDescription);
        params.put("Correction Stack ID", runMetadata.pipelineStackId);
        params.put("Target Channel", channelName(config.getTargetChannelIndex(), binConfig));
        params.put("Bleed-through Channels", channelNames(config.getBleedThroughChannelIndexes(), binConfig));
        params.put("Autofluorescence Channels", channelNames(config.getAutofluorescenceChannelIndexes(), binConfig));
        params.put("Excluded Channels", channelNames(config.getExcludedChannelIndexes(), binConfig));
        params.put("Condition Assignment Source", config.getConditionSource().getLabel());
        params.put("Control Conditions", conditionNames(config.getControlConditionNames()));
        params.put("Experimental Conditions", conditionNames(config.getExperimentalConditionNames()));
        params.put("Z-Slice Subset", binConfig.getZSliceConfig().summary());
        if (batchResult != null) {
            params.put("Preview Selection CSV", relativePath(directory, batchResult.previewSelectionFile));
            params.put("Per-image Summary CSV", relativePath(directory, batchResult.perImageSummaryFile));
            params.put("Correction Coefficients CSV", relativePath(directory, batchResult.correctionCoefficientsFile));
            params.put("Per-object Scores CSV", relativePath(directory, batchResult.perObjectScoresFile));
            params.put("Analysis Details", relativePath(directory, batchResult.analysisDetailsFile));
            params.put("Images Processed", String.valueOf(batchResult.processedCount));
            params.put("Images Skipped Existing", String.valueOf(batchResult.skippedCount));
            params.put("Images Failed", String.valueOf(batchResult.failedCount));
            params.put("Corrected Images Written", String.valueOf(batchResult.correctedCount));
            params.put("Mask Images Written", String.valueOf(batchResult.maskCount));
            params.put("Object Score Rows", String.valueOf(batchResult.objectScoreCount));
            params.put("Cleaned Object Maps Written", String.valueOf(batchResult.cleanedObjectMapCount));
            params.put("Objects Kept", String.valueOf(batchResult.objectsKeptCount));
            params.put("Objects Removed", String.valueOf(batchResult.objectsRejectedCount));
        }
        appendConfiguredFeatureSettings(params, binConfig, config);
        return params;
    }

    private void appendConfiguredFeatureSettings(Map<String, String> params,
                                                BinConfig binConfig,
                                                SpectralDecontaminationConfig config) {
        if (params == null || config == null || !config.hasCorrectionPipeline()) {
            return;
        }
        CorrectionFeatureRegistry registry = CorrectionFeatureRegistry.getDefault();
        for (String featureId : config.getCorrectionPipeline().getFeatureIds()) {
            CorrectionFeature feature = registry.getFeature(featureId);
            if (feature == null) {
                continue;
            }
            String settingsDescription = describeFeatureSettings(
                    config.getFeatureSettings(featureId),
                    binConfig);
            if (!settingsDescription.isEmpty()) {
                params.put("Settings - " + feature.getDisplayName(), settingsDescription);
            }
        }
    }

    private String describeFeatureSettings(CorrectionPipeline.Settings settings, BinConfig binConfig) {
        if (settings == null || settings.getValues().isEmpty()) {
            return "";
        }
        List<String> parts = new ArrayList<String>();
        for (Map.Entry<String, String> entry : settings.getValues().entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            if (key == null || key.trim().isEmpty() || value == null || value.trim().isEmpty()) {
                continue;
            }
            parts.add(prettySettingKey(key, binConfig) + "=" + value.trim());
        }
        return joinValues(parts, "; ");
    }

    private String prettySettingKey(String key, BinConfig binConfig) {
        String cleaned = key == null ? "" : key.trim().toLowerCase(Locale.ROOT);
        if (cleaned.startsWith("manual_weight.")) {
            try {
                int channelIndex = Integer.parseInt(cleaned.substring("manual_weight.".length()));
                return "Manual weight for " + channelName(channelIndex, binConfig);
            } catch (NumberFormatException ignored) {
                // Fall through to generic formatting.
            }
        }
        StringBuilder sb = new StringBuilder();
        boolean capitalize = true;
        for (int i = 0; i < cleaned.length(); i++) {
            char c = cleaned.charAt(i);
            if (c == '_' || c == '.') {
                sb.append(' ');
                capitalize = true;
            } else if (capitalize) {
                sb.append(Character.toUpperCase(c));
                capitalize = false;
            } else {
                sb.append(c);
            }
        }
        return sb.toString().trim();
    }

    private List<QualityReport.SpectralPreviewQC> buildSpectralPreviewQcEntries(
            List<Map<String, String>> summaryRows,
            List<SpectralPreviewRenderer.RenderedPreview> renderedPreviews) {
        List<QualityReport.SpectralPreviewQC> previews =
                new ArrayList<QualityReport.SpectralPreviewQC>();
        if (renderedPreviews == null || renderedPreviews.isEmpty()) {
            return previews;
        }

        LinkedHashMap<Integer, Map<String, String>> summaryBySeries =
                new LinkedHashMap<Integer, Map<String, String>>();
        if (summaryRows != null) {
            for (Map<String, String> summaryRow : summaryRows) {
                Integer seriesIndex = integerValue(summaryRow == null ? null : summaryRow.get("SeriesIndex"));
                if (seriesIndex != null) {
                    summaryBySeries.put(seriesIndex, summaryRow);
                }
            }
        }

        for (SpectralPreviewRenderer.RenderedPreview renderedPreview : renderedPreviews) {
            if (renderedPreview == null || renderedPreview.selection == null
                    || renderedPreview.selection.candidate == null) {
                continue;
            }
            Map<String, String> summaryRow = summaryBySeries.get(
                    Integer.valueOf(renderedPreview.selection.candidate.seriesIndex));
            previews.add(new QualityReport.SpectralPreviewQC(
                    renderedPreview.selection.candidate.conditionName,
                    renderedPreview.selection.conditionRole,
                    renderedPreview.selection.selectionRole,
                    renderedPreview.selection.candidate.seriesName,
                    renderedPreview.rawTarget == null ? null : renderedPreview.rawTarget.image,
                    renderedPreview.correctedTarget == null ? null : renderedPreview.correctedTarget.image,
                    renderedPreview.finalOverlay == null ? null : renderedPreview.finalOverlay.image,
                    spectralMetricLines(renderedPreview.metrics, summaryRow),
                    renderedPreview.metrics == null
                            ? new ArrayList<String>()
                            : renderedPreview.metrics.coefficientLines,
                    renderedPreview.metrics == null
                            ? new ArrayList<String>()
                            : renderedPreview.metrics.warningLines));
        }
        return previews;
    }

    private List<String> spectralMetricLines(SpectralPreviewRenderer.PreviewMetrics metrics,
                                             Map<String, String> summaryRow) {
        List<String> lines = new ArrayList<String>();
        if (metrics != null) {
            if (metrics.targetPositiveLabel != null && !metrics.targetPositiveLabel.trim().isEmpty()) {
                lines.add(metrics.targetPositiveLabel.trim());
            }
            lines.add("Saturated voxel fraction: " + formatFraction(metrics.saturatedFraction));
            if (metrics.objectsKept != null || metrics.objectsRemoved != null) {
                lines.add("Objects kept/removed: "
                        + (metrics.objectsKept == null ? 0 : metrics.objectsKept.intValue())
                        + "/" + (metrics.objectsRemoved == null ? 0 : metrics.objectsRemoved.intValue()));
            }
            lines.addAll(metrics.detailLines);
        }

        Integer objectsKept = integerValue(summaryRow == null ? null : summaryRow.get("ObjectsKept"));
        Integer objectsRejected = integerValue(summaryRow == null ? null : summaryRow.get("ObjectsRejected"));
        if (objectsKept != null || objectsRejected != null) {
            lines.add("Batch objects kept/removed: "
                    + (objectsKept == null ? 0 : objectsKept.intValue())
                    + "/" + (objectsRejected == null ? 0 : objectsRejected.intValue()));
        }
        return lines;
    }

    private Integer integerValue(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        try {
            return Integer.valueOf(Integer.parseInt(value.trim()));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String formatFraction(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return "n/a";
        }
        return String.format(java.util.Locale.US, "%.2f%%", value * 100.0);
    }

    private LinkedHashSet<String> animalNames(List<SeriesMeta> metas) {
        LinkedHashSet<String> animals = new LinkedHashSet<String>();
        if (metas == null) return animals;
        for (SeriesMeta meta : metas) {
            String name = meta == null ? "" : meta.name;
            String animal = ConditionManifestIO.extractAnimalName(name);
            if (animal == null || animal.trim().isEmpty()) {
                animal = meta == null ? "" : "Series " + (meta.index + 1);
            }
            if (!animal.trim().isEmpty()) animals.add(animal.trim());
        }
        return animals;
    }

    private void saveManualAssignments(String directory, LinkedHashMap<String, String> assignments) {
        try {
            ConditionManifestIO.saveAssignments(directory, assignments);
        } catch (IOException e) {
            IJ.log("Warning: could not save manual condition assignments: " + e.getMessage());
        }
    }

    private List<String> readConditionToggles(PipelineDialog dialog, List<String> conditions) {
        List<String> selected = new ArrayList<String>();
        for (String condition : conditions) {
            if (dialog.getNextBoolean()) {
                selected.add(condition);
            }
        }
        return selected;
    }

    private List<String> validateConditionRoles(List<String> conditions,
                                                List<String> controls,
                                                List<String> experimentals) {
        List<String> errors = new ArrayList<String>();
        if (controls.isEmpty()) {
            errors.add("Select at least one control condition.");
        }
        if (conditions.size() > 1 && experimentals.isEmpty()) {
            errors.add("Select at least one experimental condition.");
        }
        for (String control : controls) {
            if (experimentals.contains(control)) {
                errors.add("A condition cannot be both control and experimental: " + control);
                break;
            }
        }
        return errors;
    }

    private List<String> firstCondition(List<String> conditions) {
        List<String> out = new ArrayList<String>();
        if (conditions != null && !conditions.isEmpty()) {
            out.add(conditions.get(0));
        }
        return out;
    }

    private List<String> allExcept(List<String> conditions, List<String> excluded) {
        Set<String> excludedSet = new LinkedHashSet<String>(excluded);
        List<String> out = new ArrayList<String>();
        for (String condition : conditions) {
            if (!excludedSet.contains(condition)) out.add(condition);
        }
        return out;
    }

    private String describeConfig(SpectralDecontaminationConfig config, BinConfig binConfig) {
        CorrectionPipeline pipeline = config.getCorrectionPipeline();
        CorrectionFeatureRegistry registry = CorrectionFeatureRegistry.getDefault();
        return "goal=" + config.getGoal().getLabel()
                + ", target=" + channelName(config.getTargetChannelIndex(), binConfig)
                + ", bleed-through=" + channelNames(config.getBleedThroughChannelIndexes(), binConfig)
                + ", autofluorescence=" + channelNames(config.getAutofluorescenceChannelIndexes(), binConfig)
                + ", excluded=" + channelNames(config.getExcludedChannelIndexes(), binConfig)
                + ", condition source=" + config.getConditionSource().getLabel()
                + ", controls=" + conditionNames(config.getControlConditionNames())
                + ", experimental=" + conditionNames(config.getExperimentalConditionNames())
                + ", preset=" + presetLabelForId(registry, pipeline.getPresetId())
                + ", expert mode=" + pipeline.isExpertMode()
                + ", stack=" + pipeline.describe(registry);
    }

    private String channelNames(List<Integer> indexes, BinConfig binConfig) {
        if (indexes == null || indexes.isEmpty()) return "none";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < indexes.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(channelName(indexes.get(i).intValue(), binConfig));
        }
        return sb.toString();
    }

    private String channelName(int index, BinConfig binConfig) {
        if (index < 0 || index >= binConfig.channelNames.size()) {
            return "channel " + (index + 1);
        }
        String name = binConfig.channelNames.get(index);
        if (name == null || name.trim().isEmpty()) {
            return "channel " + (index + 1);
        }
        return (index + 1) + " - " + name;
    }

    private String conditionNames(List<String> names) {
        if (names == null || names.isEmpty()) return "none";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < names.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(names.get(i));
        }
        return sb.toString();
    }

    private static String formatErrors(List<String> errors) {
        StringBuilder sb = new StringBuilder();
        sb.append("Please fix these Spectral Decontamination settings:\n\n");
        for (String error : errors) {
            sb.append("- ").append(error).append("\n");
        }
        return sb.toString();
    }

    private static class ProcessedSeriesResult {
        final Map<String, String> summaryRow;
        final List<Map<String, String>> coefficientRows;
        final List<Map<String, String>> objectScoreRows;
        final boolean failed;
        final int objectsKeptCount;
        final int objectsRejectedCount;
        final boolean correctedImageWritten;
        final boolean maskImageWritten;
        final int cleanedObjectMapCount;

        private ProcessedSeriesResult(Map<String, String> summaryRow,
                                      List<Map<String, String>> coefficientRows,
                                      List<Map<String, String>> objectScoreRows,
                                      boolean failed,
                                      int objectsKeptCount,
                                      int objectsRejectedCount,
                                      boolean correctedImageWritten,
                                      boolean maskImageWritten,
                                      int cleanedObjectMapCount) {
            this.summaryRow = summaryRow;
            this.coefficientRows = coefficientRows;
            this.objectScoreRows = objectScoreRows;
            this.failed = failed;
            this.objectsKeptCount = objectsKeptCount;
            this.objectsRejectedCount = objectsRejectedCount;
            this.correctedImageWritten = correctedImageWritten;
            this.maskImageWritten = maskImageWritten;
            this.cleanedObjectMapCount = cleanedObjectMapCount;
        }

        static ProcessedSeriesResult success(Map<String, String> summaryRow,
                                             List<Map<String, String>> coefficientRows,
                                             List<Map<String, String>> objectScoreRows,
                                             int objectsKeptCount,
                                             int objectsRejectedCount,
                                             boolean correctedImageWritten,
                                             boolean maskImageWritten,
                                             int cleanedObjectMapCount) {
            return new ProcessedSeriesResult(
                    summaryRow,
                    coefficientRows == null ? new ArrayList<Map<String, String>>() : coefficientRows,
                    objectScoreRows == null ? new ArrayList<Map<String, String>>() : objectScoreRows,
                    false,
                    objectsKeptCount,
                    objectsRejectedCount,
                    correctedImageWritten,
                    maskImageWritten,
                    cleanedObjectMapCount);
        }

        static ProcessedSeriesResult failure(Map<String, String> summaryRow) {
            return new ProcessedSeriesResult(
                    summaryRow,
                    new ArrayList<Map<String, String>>(),
                    new ArrayList<Map<String, String>>(),
                    true,
                    0,
                    0,
                    false,
                    false,
                    0);
        }
    }

    private static class BatchRunResult {
        boolean success = false;
        int totalImages = 0;
        int processedCount = 0;
        int skippedCount = 0;
        int failedCount = 0;
        int correctedCount = 0;
        int maskCount = 0;
        int objectScoreCount = 0;
        int cleanedObjectMapCount = 0;
        int objectsKeptCount = 0;
        int objectsRejectedCount = 0;
        String message = "";
        File perImageSummaryFile = null;
        File correctionCoefficientsFile = null;
        File perObjectScoresFile = null;
        File analysisDetailsFile = null;
        File previewSelectionFile = null;
    }

    private static class ConditionSetup {
        final SpectralDecontaminationConfig config;
        final List<SpectralPreviewSelector.PreviewCandidate> candidates;

        ConditionSetup(SpectralDecontaminationConfig config,
                       List<SpectralPreviewSelector.PreviewCandidate> candidates) {
            this.config = config;
            this.candidates = candidates;
        }
    }

    private static class InteractiveSetupResult {
        final SpectralDecontaminationConfig config;
        final List<SpectralPreviewSelector.PreviewCandidate> candidates;
        final PreviewRunResult previewResult;
        final List<SpectralPreviewRenderer.RenderedPreview> renderedPreviews;

        InteractiveSetupResult(SpectralDecontaminationConfig config,
                               List<SpectralPreviewSelector.PreviewCandidate> candidates,
                               PreviewRunResult previewResult,
                               List<SpectralPreviewRenderer.RenderedPreview> renderedPreviews) {
            this.config = config;
            this.candidates = candidates;
            this.previewResult = previewResult;
            this.renderedPreviews = renderedPreviews == null
                    ? new ArrayList<SpectralPreviewRenderer.RenderedPreview>()
                    : new ArrayList<SpectralPreviewRenderer.RenderedPreview>(renderedPreviews);
        }
    }

    private static class FeatureSetupResult {
        final SpectralDecontaminationConfig config;
        final boolean backPressed;

        private FeatureSetupResult(SpectralDecontaminationConfig config, boolean backPressed) {
            this.config = config;
            this.backPressed = backPressed;
        }

        static FeatureSetupResult success(SpectralDecontaminationConfig config) {
            return new FeatureSetupResult(config, false);
        }

        static FeatureSetupResult back(SpectralDecontaminationConfig config) {
            return new FeatureSetupResult(config, true);
        }
    }

    private static class RocSettingsDialogResult {
        final SpectralDecontaminationConfig config;
        final boolean backPressed;

        private RocSettingsDialogResult(SpectralDecontaminationConfig config, boolean backPressed) {
            this.config = config;
            this.backPressed = backPressed;
        }

        static RocSettingsDialogResult success(SpectralDecontaminationConfig config) {
            return new RocSettingsDialogResult(config, false);
        }

        static RocSettingsDialogResult back(SpectralDecontaminationConfig config) {
            return new RocSettingsDialogResult(config, true);
        }
    }

    private static class ExpertSettingsDialogResult {
        final SpectralDecontaminationConfig config;
        final boolean backPressed;

        private ExpertSettingsDialogResult(SpectralDecontaminationConfig config, boolean backPressed) {
            this.config = config;
            this.backPressed = backPressed;
        }

        static ExpertSettingsDialogResult success(SpectralDecontaminationConfig config) {
            return new ExpertSettingsDialogResult(config, false);
        }

        static ExpertSettingsDialogResult back(SpectralDecontaminationConfig config) {
            return new ExpertSettingsDialogResult(config, true);
        }
    }

    private static class RocCalibrationResult {
        final boolean success;
        final String message;
        final SpectralDecontaminationConfig config;
        final RocSearchResult result;

        private RocCalibrationResult(boolean success,
                                     String message,
                                     SpectralDecontaminationConfig config,
                                     RocSearchResult result) {
            this.success = success;
            this.message = message == null ? "" : message;
            this.config = config;
            this.result = result;
        }

        static RocCalibrationResult success(SpectralDecontaminationConfig config, RocSearchResult result) {
            String message = result == null
                    ? "ROC threshold search was not needed."
                    : result.getMessage();
            return new RocCalibrationResult(true, message, config, result);
        }

        static RocCalibrationResult failure(String message) {
            return new RocCalibrationResult(false, message, null, null);
        }
    }

    private static class RoleSelection {
        final List<String> controlConditions;
        final List<String> experimentalConditions;

        RoleSelection(List<String> controlConditions, List<String> experimentalConditions) {
            this.controlConditions = controlConditions;
            this.experimentalConditions = experimentalConditions;
        }
    }

    private static class PreviewRunResult {
        final boolean success;
        final String message;
        final File outputFile;
        final List<SpectralPreviewSelector.PreviewSelection> selections;

        private PreviewRunResult(boolean success,
                                 String message,
                                 File outputFile,
                                 List<SpectralPreviewSelector.PreviewSelection> selections) {
            this.success = success;
            this.message = message;
            this.outputFile = outputFile;
            this.selections = selections == null
                    ? new ArrayList<SpectralPreviewSelector.PreviewSelection>()
                    : new ArrayList<SpectralPreviewSelector.PreviewSelection>(selections);
        }

        static PreviewRunResult success(File outputFile,
                                        List<SpectralPreviewSelector.PreviewSelection> selections) {
            int selectedCount = selections == null ? 0 : selections.size();
            return new PreviewRunResult(
                    true,
                    selectedCount + " preview image(s) selected.",
                    outputFile,
                    selections);
        }

        static PreviewRunResult failure(String message) {
            return new PreviewRunResult(false, message, null, null);
        }
    }
}
