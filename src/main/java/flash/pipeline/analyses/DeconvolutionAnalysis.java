package flash.pipeline.analyses;

import flash.pipeline.bin.BinConfig;
import flash.pipeline.bin.BinConfigIO;
import flash.pipeline.cli.CLIArgumentParser;
import flash.pipeline.cli.CLIConfig;
import flash.pipeline.deconv.DeconvolutionAvailability;
import flash.pipeline.deconv.DeconvolutionIO;
import flash.pipeline.deconv.RefractiveIndexEstimator;
import flash.pipeline.deconv.qc.DeconvPreviewDialog;
import flash.pipeline.deconv.qc.DeconvSummaryReport;
import flash.pipeline.deconv.engine.Algorithm;
import flash.pipeline.deconv.engine.DeconvParams;
import flash.pipeline.deconv.engine.DeconvolutionEngine;
import flash.pipeline.deconv.engine.DeconvolutionException;
import flash.pipeline.deconv.engine.EdgeHandling;
import flash.pipeline.deconv.engine.EngineRegistry;
import flash.pipeline.deconv.psf.PsfCache;
import flash.pipeline.deconv.psf.PsfModel;
import flash.pipeline.deconv.psf.PsfQcWriter;
import flash.pipeline.deconv.psf.PsfSpec;
import flash.pipeline.deconv.psf.ScopeModality;
import flash.pipeline.deconv.wizard.DeconvPreset;
import flash.pipeline.deconv.wizard.DeconvPresetIO;
import flash.pipeline.deconv.wizard.ImageConsultantWizard;
import flash.pipeline.image.HeapBudget;
import flash.pipeline.intelligence.MetadataDiagnostics;
import flash.pipeline.io.IoUtils;
import flash.pipeline.io.LifIO;
import flash.pipeline.io.SeriesMeta;
import flash.pipeline.naming.ImageNameParser;
import flash.pipeline.report.QualityReport;
import flash.pipeline.runtime.DependencyId;
import flash.pipeline.runtime.FeatureDependencyGate;
import flash.pipeline.ui.PipelineDialog;
import flash.pipeline.ui.ToggleSwitch;
import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.io.FileSaver;
import ij.io.Opener;
import ij.plugin.RGBStackMerge;
import ij.plugin.ZProjector;
import ij.process.ImageProcessor;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JSlider;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;
import javax.swing.JTextField;
import javax.swing.ListCellRenderer;
import javax.swing.UIManager;
import javax.swing.event.ChangeListener;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Standalone 3D deconvolution step that runs before downstream analyses.
 */
public class DeconvolutionAnalysis implements Analysis {

    private static final String TITLE = "3D Deconvolution";
    private static final String CUSTOM_PRESET_LABEL = "-- Custom --";
    private static final Color TAG_GREEN = new Color(46, 125, 50);
    private static final Color TAG_RED = new Color(183, 28, 28);
    private static final Color TAG_BLUE = new Color(21, 101, 192);
    private static final Color TAG_GREY = new Color(97, 97, 97);
    private static final Color LABEL_COLOR = new Color(33, 33, 33);
    private static final Color SOFT_BLUE_BG = new Color(232, 245, 253);
    private static final Color SOFT_BLUE_FG = new Color(15, 87, 140);
    private static final Color SOFT_BLUE_BORDER = new Color(71, 145, 196);
    private static final int LABEL_COLUMN_WIDTH = 152;
    private static final int ROW_GAP = 6;
    private static final int METADATA_FIELD_WIDTH = 240;
    private static final int PINHOLE_FIELD_WIDTH = 104;
    private static final int HELPER_COLUMN_WIDTH = 240;
    private static final int HELP_DIALOG_TEXT_WIDTH = 660;

    private boolean headless = false;
    private boolean suppressDialogs = false;
    private boolean verboseLogging = false;
    private boolean skipExisting = false;
    private QualityReport qualityReport = null;
    private CLIConfig cliConfig = null;

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
    public void setQualityReport(QualityReport report) {
        this.qualityReport = report;
    }

    @Override
    public void setCliConfig(CLIConfig config) {
        this.cliConfig = config;
    }

    @Override
    public void execute(String directory) {
        if (headless && !GraphicsEnvironment.isHeadless() && cliConfig == null
                && !CLIArgumentParser.hasCliOptions(ij.Macro.getOptions())) {
            IJ.log("[" + TITLE
                    + "] needs setup dialogs; overriding Hide Image Windows for this analysis.");
            headless = false;
        }
        if (!isBioFormatsAvailable()) {
            return;
        }

        List<SeriesJob> jobs;
        try {
            jobs = listSeriesJobs(directory);
        } catch (Exception e) {
            showOrLogError("Could not read image metadata: " + e.getMessage());
            return;
        }
        if (jobs.isEmpty()) {
            showOrLogError("No deconvolution-ready image series were found in " + directory);
            return;
        }

        SeriesJob representative = jobs.get(0);
        String[] channelNames = resolveChannelNames(directory, representative.seriesInfo);
        if (channelNames.length == 0) {
            showOrLogError("No channels were detected for deconvolution.");
            return;
        }

        while (true) {
            RunSettings settings = headless
                    ? buildHeadlessSettings(channelNames, representative)
                    : showConfigurationDialog(directory, channelNames, representative);
            if (settings == null) {
                return;
            }
            if (!settings.enabled) {
                IJ.log("3D Deconvolution disabled.");
                return;
            }

            List<String> validationErrors = validateRequiredFields(representative.seriesInfo, settings);
            if (!validationErrors.isEmpty()) {
                showValidationErrors(validationErrors);
                return;
            }

            if (!isSelectedEngineAvailable(settings.engineKey)) {
                promptInstall("Selected deconvolution engine is not available.",
                        installInstructionUrl(settings.engineKey));
                return;
            }
            if (!isPsfGeneratorAvailable()) {
                promptInstall("The EPFL PSF Generator plugin is required for deconvolution PSF synthesis.",
                        installInstructionUrl("PsfGenerator"));
                return;
            }

            DeconvPreviewDialog.Decision previewDecision =
                    showPreviewBeforeBatch(directory, representative, channelNames, settings);
            if (previewDecision == DeconvPreviewDialog.Decision.RECONFIGURE) {
                continue;
            }
            if (previewDecision == DeconvPreviewDialog.Decision.CANCEL) {
                return;
            }

            addReportSection(settings, channelNames);
            runBatch(directory, jobs, channelNames, settings);
            return;
        }
    }

    private RunSettings buildHeadlessSettings(String[] channelNames, SeriesJob representative) {
        CLIConfig config = cliConfig;
        if (config == null) {
            String macroOptions = ij.Macro.getOptions();
            if (!CLIArgumentParser.hasCliOptions(macroOptions)) {
                IJ.log("[" + TITLE + "] Headless deconvolution needs CLI macro options. "
                        + "Run from the FLASH UI for interactive setup, or provide "
                        + "dir=[...] run_deconv deconv.enabled=true ...");
                return null;
            }
            config = CLIArgumentParser.parse(macroOptions);
        }
        if (config == null) return null;

        CLIConfig.DeconvConfig deconv = config.getDeconv();
        RunSettings settings = new RunSettings();
        settings.enabled = deconv.isEnabled();
        settings.engineKey = deconv.getEngine() == null ? defaultEngineKey() : deconv.getEngine();
        DeconvolutionEngine engine = resolveEngine(settings.engineKey);
        settings.algorithm = deconv.getAlgorithm() == null ? defaultAlgorithm(engine) : deconv.getAlgorithm();
        settings.psfModel = deconv.getPsfModel() == null ? PsfModel.GIBSON_LANNI : deconv.getPsfModel();
        settings.scopeModality = deconv.getScopeModality() != null
                ? deconv.getScopeModality()
                : defaultScopeModality(representative.seriesInfo);
        settings.pinholeAiryUnits = deconv.getPinholeAiryUnits();
        settings.sampleRiOverride = deconv.getSampleRI();
        settings.mountingMedium = deconv.getMountingMedium();
        settings.iterations = deconv.getIterations();
        settings.regularization = deconv.getRegularization();
        settings.strictNyquist = deconv.isStrictNyquist();
        settings.useCache = deconv.isUseCache();
        settings.skipPreview = deconv.isSkipPreview();
        settings.channelNames = channelNames;
        settings.selectedChannels = new boolean[channelNames.length];
        if (deconv.getChannels() == null || deconv.getChannels().length == 0) {
            Arrays.fill(settings.selectedChannels, true);
        } else {
            for (int channel : deconv.getChannels()) {
                if (channel >= 0 && channel < settings.selectedChannels.length) {
                    settings.selectedChannels[channel] = true;
                }
            }
        }
        settings.naOverride = representative.seriesInfo.objectiveNA;
        settings.immersionRiOverride = representative.seriesInfo.objectiveImmersion == null
                ? null
                : Double.valueOf(RefractiveIndexEstimator.immersionRI(representative.seriesInfo.objectiveImmersion));
        settings.xyPixelSizeOverrideUm = representative.seriesInfo.pixelSizeXUm;
        settings.zStepOverrideUm = representative.seriesInfo.pixelSizeZUm;
        settings.emissionOverridesNm = representative.seriesInfo.emissionWavelengthNm == null
                ? new double[channelNames.length]
                : copyWavelengths(representative.seriesInfo.emissionWavelengthNm, channelNames.length);
        return settings;
    }

    private RunSettings showConfigurationDialog(String directory, String[] channelNames, SeriesJob representative) {
        PipelineDialog dialog = new PipelineDialog(TITLE, PipelineDialog.Phase.SETUP);
        final DialogBindings bindings = new DialogBindings();
        final DeconvPresetIO presetIO = new DeconvPresetIO();
        JButton helpButton = new JButton("?");
        styleHelpButton(helpButton);
        helpButton.setToolTipText("Explain every 3D Deconvolution option.");
        helpButton.addActionListener(e -> dialog.runChildWorkflow(new Runnable() {
            @Override public void run() {
                showDeconvolutionHelpDialog();
            }
        }));
        dialog.setNorthSlot(topHelpRow(helpButton));

        dialog.addHeader("Guided Setup");
        JButton consultantButton = new JButton("Image Consultant");
        styleSoftBlueButton(consultantButton);
        dialog.addComponent(buttonRow(consultantButton));

        JComboBox<String> presetChoice = new JComboBox<String>(new String[]{CUSTOM_PRESET_LABEL});
        presetChoice.setMaximumSize(new Dimension(220, 24));
        bindings.presetChoice = presetChoice;
        JButton presetButton = new JButton("Save as preset...");
        dialog.addComponent(labelAndTwoComponents("Preset", presetChoice, presetButton));

        dialog.addHeader("Engine & Algorithm");
        final JComboBox<EngineChoice> engineChoice = new JComboBox<EngineChoice>(engineChoices().toArray(new EngineChoice[0]));
        engineChoice.setMaximumSize(new Dimension(260, 24));
        engineChoice.setRenderer(new EngineChoiceRenderer());
        selectEngineChoice(engineChoice, defaultEngineKey());
        bindings.engineChoice = engineChoice;
        final JComboBox<AlgorithmChoice> algorithmChoice = new JComboBox<AlgorithmChoice>();
        algorithmChoice.setMaximumSize(new Dimension(260, 24));
        bindings.algorithmChoice = algorithmChoice;
        final JComboBox<PsfModel> psfChoice = new JComboBox<PsfModel>(PsfModel.values());
        psfChoice.setMaximumSize(new Dimension(260, 24));
        psfChoice.setRenderer(enumRenderer());
        psfChoice.setSelectedItem(PsfModel.GIBSON_LANNI);
        bindings.psfChoice = psfChoice;
        bindings.engineRow = taggedRow("Engine", engineChoice);
        bindings.algorithmRow = taggedRow("Algorithm", algorithmChoice);
        bindings.psfRow = taggedRow("PSF model", psfChoice);
        dialog.addComponent(bindings.engineRow.panel);
        dialog.addComponent(bindings.algorithmRow.panel);
        dialog.addComponent(bindings.psfRow.panel);

        dialog.addHeader("Microscope & Sample");
        final MetadataFieldRow xyPixelRow = metadataField("XY pixel size (um)",
                representative.seriesInfo.pixelSizeXUm == null ? "" : DeconvolutionIO.formatDouble(representative.seriesInfo.pixelSizeXUm.doubleValue()),
                representative.seriesInfo.pixelSizeXUm == null ? "Missing - please enter" : "Auto-detected",
                representative.seriesInfo.pixelSizeXUm == null ? TAG_RED : TAG_GREEN);
        final MetadataFieldRow naRow = metadataField("Numerical Aperture (NA)",
                representative.seriesInfo.objectiveNA == null ? "" : DeconvolutionIO.formatDouble(representative.seriesInfo.objectiveNA.doubleValue()),
                representative.seriesInfo.objectiveNA == null ? "Missing - please enter" : "Auto-detected",
                representative.seriesInfo.objectiveNA == null ? TAG_RED : TAG_GREEN);
        final Double immersionRi = representative.seriesInfo.objectiveImmersion == null
                ? null
                : Double.valueOf(RefractiveIndexEstimator.immersionRI(representative.seriesInfo.objectiveImmersion));
        final MetadataFieldRow immersionRow = metadataField("Immersion RI",
                immersionRi == null ? "" : DeconvolutionIO.formatDouble(immersionRi.doubleValue()),
                immersionRi == null
                        ? "Missing - please enter"
                        : "Auto-detected from immersion: " + representative.seriesInfo.objectiveImmersion,
                immersionRi == null ? TAG_RED : TAG_GREEN);
        final double defaultSampleRi = representative.seriesInfo.sampleRefractiveIndex == null
                ? RefractiveIndexEstimator.inferSampleRI(representative.seriesInfo.objectiveImmersion, null)
                : representative.seriesInfo.sampleRefractiveIndex.doubleValue();
        final MetadataFieldRow sampleRiRow = metadataField("Sample RI",
                Double.isNaN(defaultSampleRi) ? "" : DeconvolutionIO.formatDouble(defaultSampleRi),
                representative.seriesInfo.objectiveImmersion == null
                        ? "Editable override"
                        : "Inferred from immersion",
                TAG_GREEN);
        bindings.sampleRiRow = sampleRiRow;
        final MetadataFieldRow emissionRow = metadataField("Emission wavelength (nm)",
                joinWavelengths(representative.seriesInfo.emissionWavelengthNm, channelNames.length),
                hasAllWavelengths(representative.seriesInfo.emissionWavelengthNm, channelNames.length)
                        ? "Auto-detected"
                        : "Missing - enter one value per channel",
                hasAllWavelengths(representative.seriesInfo.emissionWavelengthNm, channelNames.length) ? TAG_GREEN : TAG_RED);
        final MetadataFieldRow zStepRow = metadataField("Z-step (um)",
                representative.seriesInfo.pixelSizeZUm == null ? "" : DeconvolutionIO.formatDouble(representative.seriesInfo.pixelSizeZUm.doubleValue()),
                representative.seriesInfo.pixelSizeZUm == null ? "Missing - please enter" : "Auto-detected",
                representative.seriesInfo.pixelSizeZUm == null ? TAG_RED : TAG_GREEN);
        dialog.addComponent(xyPixelRow.panel);
        dialog.addComponent(naRow.panel);
        dialog.addComponent(immersionRow.panel);
        dialog.addComponent(sampleRiRow.panel);
        dialog.addComponent(emissionRow.panel);
        dialog.addComponent(zStepRow.panel);

        final JComboBox<ScopeModality> modalityChoice = new JComboBox<ScopeModality>(ScopeModality.values());
        modalityChoice.setMaximumSize(new Dimension(220, 24));
        modalityChoice.setRenderer(enumRenderer());
        ScopeModality guessedModality = defaultScopeModality(representative.seriesInfo);
        if (guessedModality != null) {
            modalityChoice.setSelectedItem(guessedModality);
        }
        bindings.modalityChoice = modalityChoice;
        final JTextField pinholeField = new JTextField("1.0", 6);
        setFixedControlSize(pinholeField, PINHOLE_FIELD_WIDTH);
        bindings.pinholeField = pinholeField;
        bindings.modalityRow = taggedRow("Scope modality", modalityChoice);
        bindings.pinholeRow = taggedRow("Pinhole (Airy units)", pinholeField);
        dialog.addComponent(bindings.modalityRow.panel);
        dialog.addComponent(bindings.pinholeRow.panel);

        dialog.addHeader("Channels");
        final List<ChannelToggleRow> channelRows = new ArrayList<ChannelToggleRow>();
        JPanel channelsPanel = new JPanel();
        channelsPanel.setLayout(new BoxLayout(channelsPanel, BoxLayout.Y_AXIS));
        channelsPanel.setOpaque(false);
        for (String channelName : channelNames) {
            ChannelToggleRow row = new ChannelToggleRow(channelName, true);
            channelRows.add(row);
            channelsPanel.add(row.panel);
        }
        dialog.addComponent(channelsPanel);

        dialog.addHeader("Parameters");
        final JSpinner iterationsSpinner = new JSpinner(new SpinnerNumberModel(15, 1, 100, 1));
        final JSlider regularizationSlider = new JSlider(0, 100, 10);
        final JLabel regularizationLabel = new JLabel("0.010");
        regularizationLabel.setForeground(LABEL_COLOR);
        bindings.iterationsSpinner = iterationsSpinner;
        bindings.regularizationSlider = regularizationSlider;
        bindings.regularizationLabel = regularizationLabel;
        bindings.iterationsRow = taggedRow("Iterations", iterationsSpinner);
        bindings.regularizationRow = taggedRow("Regularization strength",
                groupedComponents(regularizationSlider, regularizationLabel));
        dialog.addComponent(bindings.iterationsRow.panel);
        dialog.addComponent(bindings.regularizationRow.panel);
        final ToggleSwitch strictNyquistToggle = new ToggleSwitch(false);
        dialog.addComponent(labeledRow("Strict Nyquist", strictNyquistToggle));

        dialog.addHeader("Cache");
        final ToggleSwitch useCacheToggle = new ToggleSwitch(true);
        dialog.addComponent(labeledRow("Use deconvolution cache", useCacheToggle));
        JButton clearCacheButton = new JButton("Clear cache");
        clearCacheButton.addActionListener(e -> {
            File cacheRoot = DeconvolutionIO.cacheDir(new File(directory));
            if (!cacheRoot.exists()) {
                JOptionPane.showMessageDialog(null, "No cache directory exists yet.", TITLE, JOptionPane.INFORMATION_MESSAGE);
                return;
            }
            int choice = JOptionPane.showConfirmDialog(null,
                    "Delete " + cacheRoot.getAbsolutePath() + " ?",
                    TITLE,
                    JOptionPane.YES_NO_OPTION,
                    JOptionPane.WARNING_MESSAGE);
            if (choice != JOptionPane.YES_OPTION) return;
            try {
                deleteRecursively(cacheRoot.toPath());
            } catch (IOException ex) {
                IJ.log("Could not clear deconvolution cache: " + ex.getMessage());
            }
        });
        dialog.addComponent(buttonRow(clearCacheButton));

        Runnable refreshAlgorithms = new Runnable() {
            @Override
            public void run() {
                EngineChoice choice = (EngineChoice) engineChoice.getSelectedItem();
                populateAlgorithms(algorithmChoice, choice == null ? null : choice.engine);
            }
        };
        ChangeListener sliderListener = e ->
                regularizationLabel.setText(String.format(Locale.ROOT, "%.3f", regularizationSlider.getValue() / 1000.0));
        regularizationSlider.addChangeListener(sliderListener);
        sliderListener.stateChanged(null);

        Runnable refreshEnablement = new Runnable() {
            @Override
            public void run() {
                boolean confocal = modalityChoice.getSelectedItem() == ScopeModality.CONFOCAL;
                bindings.pinholeRow.panel.setVisible(confocal);
            }
        };

        populatePresetChoice(presetChoice, loadPresets(presetIO), CUSTOM_PRESET_LABEL);

        consultantButton.addActionListener(e -> {
            if (isInteractiveHeadless()) {
                showOrLogError("Image Consultant is unavailable in headless mode.");
                return;
            }
            final ImageConsultantWizard.Recommendation[] selectedRecommendation =
                    new ImageConsultantWizard.Recommendation[1];
            dialog.runChildWorkflow(new Runnable() {
                @Override public void run() {
                    ImageConsultantWizard wizard = new ImageConsultantWizard(
                            representative.seriesInfo,
                            wizardAvailability(),
                            bindings.mountingMedium
                    );
                    selectedRecommendation[0] = wizard.run();
                }
            });
            ImageConsultantWizard.Recommendation recommendation = selectedRecommendation[0];
            if (recommendation == null) {
                return;
            }
            applySourceValues(bindings,
                    recommendation.getEngineKey(),
                    recommendation.getAlgorithm(),
                    recommendation.getPsfModel(),
                    recommendation.getScopeModality(),
                    recommendation.getPinholeAU(),
                    recommendation.getSampleRI(),
                    recommendation.getMountingMediumHint(),
                    recommendation.getIterations(),
                    recommendation.getRegularization(),
                    "Recommended",
                    refreshAlgorithms,
                    refreshEnablement);
            bindings.programmaticChange = true;
            try {
                presetChoice.setSelectedItem(CUSTOM_PRESET_LABEL);
            } finally {
                bindings.programmaticChange = false;
            }
        });

        presetButton.addActionListener(e -> saveCurrentPreset(presetIO, bindings, presetChoice,
                refreshAlgorithms, refreshEnablement));

        presetChoice.addActionListener(e -> {
            if (bindings.programmaticChange) {
                return;
            }
            String selected = (String) presetChoice.getSelectedItem();
            if (selected == null || CUSTOM_PRESET_LABEL.equals(selected)) {
                clearAllSourceTags(bindings);
                return;
            }
            try {
                DeconvPreset preset = presetIO.load(selected);
                applySourceValues(bindings,
                        preset.getEngineKey(),
                        preset.getAlgorithm(),
                        preset.getPsfModel(),
                        preset.getScopeModality(),
                        preset.getPinholeAU(),
                        preset.getSampleRI(),
                        null,
                        preset.getIterations(),
                        preset.getRegularization(),
                        "From preset: " + preset.getName(),
                        refreshAlgorithms,
                        refreshEnablement);
            } catch (IOException ex) {
                showOrLogError("Could not load preset '" + selected + "': " + ex.getMessage());
                populatePresetChoice(presetChoice, loadPresets(presetIO), CUSTOM_PRESET_LABEL);
            }
        });

        modalityChoice.addActionListener(e -> {
            refreshEnablement.run();
            if (!bindings.programmaticChange) {
                clearSourceTag(bindings.modalityRow.sourceTagLabel);
                clearSourceTag(bindings.pinholeRow.sourceTagLabel);
            }
        });
        engineChoice.addActionListener(e -> {
            EngineChoice choice = (EngineChoice) engineChoice.getSelectedItem();
            if (!bindings.programmaticChange && choice != null && !choice.available) {
                browseInstallUrl(choice.installUrl);
            }
            refreshAlgorithms.run();
            if (!bindings.programmaticChange) {
                clearSourceTag(bindings.engineRow.sourceTagLabel);
                clearSourceTag(bindings.algorithmRow.sourceTagLabel);
            }
        });
        algorithmChoice.addActionListener(e -> {
            if (!bindings.programmaticChange) {
                clearSourceTag(bindings.algorithmRow.sourceTagLabel);
            }
        });
        psfChoice.addActionListener(e -> {
            if (!bindings.programmaticChange) {
                clearSourceTag(bindings.psfRow.sourceTagLabel);
            }
        });
        attachDocumentTagClearer(sampleRiRow.field, bindings, sampleRiRow.sourceTagLabel);
        attachDocumentTagClearer(pinholeField, bindings, bindings.pinholeRow.sourceTagLabel);
        attachSpinnerTagClearer(iterationsSpinner, bindings, bindings.iterationsRow.sourceTagLabel);
        attachSliderTagClearer(regularizationSlider, regularizationLabel, bindings, bindings.regularizationRow.sourceTagLabel);
        refreshAlgorithms.run();
        refreshEnablement.run();

        if (!dialog.showDialog()) {
            return null;
        }

        RunSettings settings = new RunSettings();
        settings.enabled = true;
        EngineChoice selectedEngine = (EngineChoice) engineChoice.getSelectedItem();
        settings.engineKey = selectedEngine == null ? defaultEngineKey() : selectedEngine.engine.key();
        AlgorithmChoice selectedAlgorithm = (AlgorithmChoice) algorithmChoice.getSelectedItem();
        settings.algorithm = selectedAlgorithm == null ? defaultAlgorithm(resolveEngine(settings.engineKey))
                : selectedAlgorithm.algorithm;
        settings.psfModel = (PsfModel) psfChoice.getSelectedItem();
        settings.scopeModality = (ScopeModality) modalityChoice.getSelectedItem();
        settings.pinholeAiryUnits = parseNullableDouble(pinholeField.getText());
        settings.sampleRiOverride = parseNullableDouble(sampleRiRow.field.getText());
        settings.mountingMedium = bindings.mountingMedium;
        settings.iterations = ((Number) iterationsSpinner.getValue()).intValue();
        settings.regularization = regularizationSlider.getValue() / 1000.0;
        settings.strictNyquist = strictNyquistToggle.isSelected();
        settings.useCache = useCacheToggle.isSelected();
        settings.skipPreview = cliConfig != null
                && cliConfig.getDeconv() != null
                && cliConfig.getDeconv().isSkipPreview();
        settings.channelNames = channelNames;
        settings.selectedChannels = new boolean[channelRows.size()];
        for (int i = 0; i < channelRows.size(); i++) {
            settings.selectedChannels[i] = channelRows.get(i).toggle.isSelected();
        }
        settings.naOverride = parseNullableDouble(naRow.field.getText());
        settings.immersionRiOverride = parseNullableDouble(immersionRow.field.getText());
        settings.xyPixelSizeOverrideUm = parseNullableDouble(xyPixelRow.field.getText());
        settings.zStepOverrideUm = parseNullableDouble(zStepRow.field.getText());
        settings.emissionOverridesNm = parseWavelengths(emissionRow.field.getText(), channelNames.length);
        return settings;
    }

    private void showDeconvolutionHelpDialog() {
        PipelineDialog help = new PipelineDialog("3D Deconvolution - Options Help", PipelineDialog.Phase.SETUP);
        help.setPrimaryButtonText("Close");

        help.addHeader("What This Analysis Does");
        help.addComponent(helpParagraph(
                "<b>3D Deconvolution</b> sharpens z-stacks by using microscope settings to estimate "
                        + "how light from each real point spreads through the image. It can improve object "
                        + "boundaries, reduce haze, and make downstream intensity or object analysis cleaner. "
                        + "Use it when blur is limiting segmentation or fluorescence measurement. Avoid it when "
                        + "the raw data are already clean enough, when metadata are unreliable, or when you need "
                        + "to preserve raw intensities exactly."));

        help.addHeader("Guided Setup");
        help.addComponent(helpParagraph(
                "<b>Image Consultant</b> asks a few plain-language questions and fills in sensible "
                        + "engine, algorithm, PSF model, modality, iteration, regularization, and sample RI "
                        + "settings. Use it when you are unsure what deconvolution settings fit the image."));
        help.addComponent(helpParagraph(
                "<b>Preset</b> loads a saved settings set. Use presets when repeating the same microscope, "
                        + "objective, sample prep, and image style across experiments. <b>Save as preset</b> stores "
                        + "the current expert settings so future runs start from the same choices."));

        help.addHeader("Engine & Algorithm");
        help.addComponent(helpParagraph(
                "<b>Engine</b> chooses the plugin/runtime that performs the calculation. CLIJ2 is usually fastest "
                        + "on a suitable GPU and is useful for large batches. DeconvolutionLab2 is CPU-based and "
                        + "good for careful, reproducible runs. Iterative Deconvolve 3D is a fallback when that "
                        + "plugin is installed. Greyed engines are not available in this Fiji install."));
        help.addComponent(helpParagraph(
                "<b>Algorithm</b> chooses the mathematical method used by the selected engine. Richardson-Lucy is "
                        + "the common starting point for fluorescence images. Regularized variants can suppress "
                        + "noise and ringing. Linear methods such as Wiener or Tikhonov can be faster or gentler, "
                        + "but may not recover dim 3D structure as strongly."));
        help.addComponent(helpParagraph(
                "<b>PSF model</b> chooses how the point-spread function (PSF) is generated. Gibson &amp; Lanni is the "
                        + "best default for most high-NA microscope objectives because it models refractive index "
                        + "mismatch. Simpler PSF models are useful only when the detailed microscope metadata are "
                        + "missing or when you need a quick approximate run."));

        help.addHeader("Microscope & Sample");
        help.addComponent(helpParagraph(
                "<b>XY pixel size (um)</b> is the physical width/height of one pixel. It controls how the PSF is "
                        + "scaled in x and y. Use the microscope metadata when available; wrong values make the "
                        + "deconvolution too weak or too aggressive."));
        help.addComponent(helpParagraph(
                "<b>Numerical Aperture (NA)</b> describes the light-gathering power of the objective. Higher NA "
                        + "means a smaller PSF and better resolution. Use the objective NA printed on the lens or "
                        + "stored in the image metadata."));
        help.addComponent(helpParagraph(
                "<b>Immersion RI</b> is the refractive index of the immersion medium, such as air, water, glycerol, "
                        + "or oil. It is often inferred from objective immersion metadata. Change it only if the "
                        + "detected immersion medium is wrong."));
        help.addComponent(helpParagraph(
                "<b>Sample RI</b> is the refractive index of the tissue or mounting medium. This affects spherical "
                        + "aberration and z blur. Use the Image Consultant when unsure. Typical fixed tissue/mounting "
                        + "media are often around 1.45 to 1.52, but the right value depends on the mountant."));
        help.addComponent(helpParagraph(
                "<b>Emission wavelength (nm)</b> is the fluorescence emission wavelength for each channel, in channel "
                        + "order. The PSF is wider for longer wavelengths. Enter one value per channel, for example "
                        + "460, 520, 590, 670. Missing or wrong wavelengths reduce accuracy."));
        help.addComponent(helpParagraph(
                "<b>Z-step (um)</b> is the physical spacing between optical sections. This is critical for 3D data. "
                        + "If the z-step is too large for the objective/wavelength, deconvolution may create artifacts "
                        + "instead of recovering structure."));

        help.addHeader("Scope & Channels");
        help.addComponent(helpParagraph(
                "<b>Scope modality</b> tells the PSF model whether the data are confocal or widefield. Choose "
                        + "confocal for point-scanned or spinning-disk confocal stacks. Choose widefield for "
                        + "epifluorescence or other non-confocal stacks."));
        help.addComponent(helpParagraph(
                "<b>Pinhole (Airy units)</b> appears for confocal data. Smaller pinholes reject more out-of-focus "
                        + "light but reduce signal. Use the acquisition value when known; 1.0 Airy unit is a common "
                        + "default when the metadata do not record it."));
        help.addComponent(helpParagraph(
                "<b>Deconvolve channel</b> toggles decide which channels are processed. Deconvolve channels that "
                        + "will be segmented or measured downstream. Skip channels that are only references, already "
                        + "too noisy, saturated, or not needed for analysis."));

        help.addHeader("Parameters");
        help.addComponent(helpParagraph(
                "<b>Iterations</b> controls how far the iterative algorithm runs. More iterations sharpen more but "
                        + "also amplify noise and take longer. Start around 10 to 20 for typical fluorescence stacks; "
                        + "increase only if objects remain visibly blurred."));
        help.addComponent(helpParagraph(
                "<b>Regularization strength</b> dampens noise amplification and ringing. Higher values are safer for "
                        + "noisy data but can smooth real small structures. Lower values preserve detail but can make "
                        + "speckle and edge artifacts worse."));
        help.addComponent(helpParagraph(
                "<b>Strict Nyquist</b> makes the analysis reject or warn more strongly about undersampled data. Use "
                        + "it for publication-quality or quantitative runs where sampling must be defensible. Leave it "
                        + "off for exploratory cleanup when the stack is imperfect but still useful."));

        help.addHeader("Cache & Preview");
        help.addComponent(helpParagraph(
                "<b>Use .deconv_cache/</b> reuses existing deconvolved outputs when the input image and settings match. "
                        + "Keep it on for normal batch work because it saves time. Turn it off when intentionally "
                        + "rerunning after manual file changes or when checking whether a previous output is stale."));
        help.addComponent(helpParagraph(
                "<b>Clear cache</b> deletes cached deconvolution outputs for this project. Use it when disk space is "
                        + "an issue, when settings were changed outside FLASH, or when you suspect old cached outputs "
                        + "are being reused."));
        help.addComponent(helpParagraph(
                "<b>Preview before batch</b> is shown automatically when interactive mode is available. It runs a small "
                        + "center crop so you can compare raw versus deconvolved output before committing to the full "
                        + "batch. If the preview looks noisy, lower iterations or raise regularization."));

        help.addHeader("Status Text");
        help.addComponent(helpParagraph(
                "<b>Auto-detected</b> means FLASH read the value from image metadata. <b>Missing</b> means the value is "
                        + "required and must be entered manually. <b>Inferred</b> means FLASH estimated the value from "
                        + "nearby metadata, such as immersion medium. <b>Recommended</b> means the Image Consultant set "
                        + "the value. <b>From preset</b> means a saved preset filled it in. Editing a field clears its "
                        + "source tag because the value is now manual."));

        help.showDialog();
    }

    private ImageConsultantWizard.Availability wizardAvailability() {
        Set<String> available = new HashSet<String>();
        for (DeconvolutionEngine engine : availableEngines()) {
            available.add(engine.key());
        }
        return new ImageConsultantWizard.Availability(
                available.contains("CLIJ2"),
                available.contains("DL2"),
                available.contains("IterativeDeconvolve3D")
        );
    }

    private List<DeconvPreset> loadPresets(DeconvPresetIO presetIO) {
        try {
            return presetIO.listAll();
        } catch (IOException e) {
            IJ.log(TITLE + ": Could not list presets: " + e.getMessage());
            return Collections.emptyList();
        }
    }

    private void populatePresetChoice(JComboBox<String> combo, List<DeconvPreset> presets, String selection) {
        if (combo == null) return;
        combo.removeAllItems();
        combo.addItem(CUSTOM_PRESET_LABEL);
        if (presets != null) {
            for (DeconvPreset preset : presets) {
                combo.addItem(preset.getName());
            }
        }
        combo.setSelectedItem(selection == null ? CUSTOM_PRESET_LABEL : selection);
        if (combo.getSelectedIndex() < 0) {
            combo.setSelectedItem(CUSTOM_PRESET_LABEL);
        }
    }

    private void saveCurrentPreset(DeconvPresetIO presetIO,
                                   DialogBindings bindings,
                                   JComboBox<String> presetChoice,
                                   Runnable refreshAlgorithms,
                                   Runnable refreshEnablement) {
        String name = JOptionPane.showInputDialog(
                null,
                "Preset name:",
                TITLE,
                JOptionPane.PLAIN_MESSAGE);
        if (name == null) {
            return;
        }
        name = name.trim();
        if (name.isEmpty()) {
            showOrLogError("Preset name cannot be empty.");
            return;
        }

        try {
            DeconvPreset preset = buildPresetFromBindings(name, bindings);
            presetIO.save(preset);
            List<DeconvPreset> presets = loadPresets(presetIO);
            bindings.programmaticChange = true;
            try {
                populatePresetChoice(presetChoice, presets, preset.getName());
            } finally {
                bindings.programmaticChange = false;
            }
            applySourceValues(bindings,
                    preset.getEngineKey(),
                    preset.getAlgorithm(),
                    preset.getPsfModel(),
                    preset.getScopeModality(),
                    preset.getPinholeAU(),
                    preset.getSampleRI(),
                    null,
                    preset.getIterations(),
                    preset.getRegularization(),
                    "From preset: " + preset.getName(),
                    refreshAlgorithms,
                    refreshEnablement);
        } catch (IOException e) {
            showOrLogError("Could not save preset '" + name + "': " + e.getMessage());
        }
    }

    private DeconvPreset buildPresetFromBindings(String name, DialogBindings bindings) {
        EngineChoice engine = (EngineChoice) bindings.engineChoice.getSelectedItem();
        AlgorithmChoice algorithm = (AlgorithmChoice) bindings.algorithmChoice.getSelectedItem();
        PsfModel psfModel = (PsfModel) bindings.psfChoice.getSelectedItem();
        ScopeModality modality = (ScopeModality) bindings.modalityChoice.getSelectedItem();
        Double pinhole = modality == ScopeModality.CONFOCAL
                ? parseNullableDouble(bindings.pinholeField.getText())
                : null;
        return new DeconvPreset(
                name,
                null,
                engine == null ? defaultEngineKey() : engine.engine.key(),
                algorithm == null
                        ? defaultAlgorithm(resolveEngine(engine == null ? defaultEngineKey() : engine.engine.key()))
                        : algorithm.algorithm,
                psfModel == null ? PsfModel.GIBSON_LANNI : psfModel,
                ((Number) bindings.iterationsSpinner.getValue()).intValue(),
                bindings.regularizationSlider.getValue() / 1000.0,
                modality == null ? ScopeModality.WIDEFIELD : modality,
                pinhole,
                parseNullableDouble(bindings.sampleRiRow.field.getText())
        );
    }

    private void applySourceValues(DialogBindings bindings,
                                   String engineKey,
                                   Algorithm algorithm,
                                   PsfModel psfModel,
                                   ScopeModality scopeModality,
                                   Double pinholeAiryUnits,
                                   Double sampleRi,
                                   String mountingMedium,
                                   int iterations,
                                   double regularization,
                                   String tagText,
                                   Runnable refreshAlgorithms,
                                   Runnable refreshEnablement) {
        bindings.programmaticChange = true;
        try {
            selectEngineChoice(bindings.engineChoice, engineKey == null ? defaultEngineKey() : engineKey);
            refreshAlgorithms.run();
            selectAlgorithmChoice(bindings.algorithmChoice, algorithm);
            if (psfModel != null) {
                bindings.psfChoice.setSelectedItem(psfModel);
            }
            if (scopeModality != null) {
                bindings.modalityChoice.setSelectedItem(scopeModality);
            }
            bindings.pinholeField.setText(DeconvolutionIO.formatDouble(
                    pinholeAiryUnits == null ? 1.0 : pinholeAiryUnits.doubleValue()));
            bindings.sampleRiRow.field.setText(sampleRi == null ? "" : DeconvolutionIO.formatDouble(sampleRi.doubleValue()));
            bindings.iterationsSpinner.setValue(Integer.valueOf(iterations));
            bindings.regularizationSlider.setValue((int) Math.round(Math.max(0.0, Math.min(0.1, regularization)) * 1000.0));
            bindings.regularizationLabel.setText(String.format(Locale.ROOT, "%.3f", bindings.regularizationSlider.getValue() / 1000.0));
            bindings.mountingMedium = mountingMedium;

            setSourceTag(bindings.engineRow.sourceTagLabel, tagText);
            setSourceTag(bindings.algorithmRow.sourceTagLabel, tagText);
            setSourceTag(bindings.psfRow.sourceTagLabel, tagText);
            setSourceTag(bindings.modalityRow.sourceTagLabel, tagText);
            setSourceTag(bindings.sampleRiRow.sourceTagLabel, tagText);
            setSourceTag(bindings.iterationsRow.sourceTagLabel, tagText);
            setSourceTag(bindings.regularizationRow.sourceTagLabel, tagText);
            if (scopeModality == ScopeModality.CONFOCAL) {
                setSourceTag(bindings.pinholeRow.sourceTagLabel, tagText);
            } else {
                clearSourceTag(bindings.pinholeRow.sourceTagLabel);
            }
            refreshEnablement.run();
        } finally {
            bindings.programmaticChange = false;
        }
    }

    private void selectAlgorithmChoice(JComboBox<AlgorithmChoice> combo, Algorithm algorithm) {
        if (combo == null || combo.getItemCount() == 0) return;
        Algorithm target = algorithm;
        if (target == null) {
            AlgorithmChoice selected = combo.getItemAt(0);
            target = selected == null ? null : selected.algorithm;
        }
        for (int i = 0; i < combo.getItemCount(); i++) {
            AlgorithmChoice choice = combo.getItemAt(i);
            if (choice != null && choice.algorithm == target) {
                combo.setSelectedIndex(i);
                return;
            }
        }
        combo.setSelectedIndex(0);
    }

    private void clearAllSourceTags(DialogBindings bindings) {
        if (bindings == null) return;
        clearSourceTag(bindings.engineRow.sourceTagLabel);
        clearSourceTag(bindings.algorithmRow.sourceTagLabel);
        clearSourceTag(bindings.psfRow.sourceTagLabel);
        clearSourceTag(bindings.modalityRow.sourceTagLabel);
        clearSourceTag(bindings.pinholeRow.sourceTagLabel);
        clearSourceTag(bindings.sampleRiRow.sourceTagLabel);
        clearSourceTag(bindings.iterationsRow.sourceTagLabel);
        clearSourceTag(bindings.regularizationRow.sourceTagLabel);
    }

    private static void setSourceTag(JLabel label, String tagText) {
        if (label == null) return;
        String text = tagText == null || tagText.trim().isEmpty()
                ? ""
                : tagText.trim();
        setHelperLabelText(label, text);
        label.setForeground(TAG_BLUE);
    }

    private static void clearSourceTag(JLabel label) {
        if (label == null) return;
        setHelperLabelText(label, "");
    }

    private static void attachDocumentTagClearer(JTextField field, DialogBindings bindings, JLabel label) {
        if (field == null || bindings == null || label == null) return;
        field.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                clearIfNeeded();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                clearIfNeeded();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                clearIfNeeded();
            }

            private void clearIfNeeded() {
                if (!bindings.programmaticChange) {
                    clearSourceTag(label);
                }
            }
        });
    }

    private static void attachSpinnerTagClearer(JSpinner spinner, DialogBindings bindings, JLabel label) {
        if (spinner == null || bindings == null || label == null) return;
        spinner.addChangeListener(e -> {
            if (!bindings.programmaticChange) {
                clearSourceTag(label);
            }
        });
    }

    private static void attachSliderTagClearer(JSlider slider,
                                               JLabel valueLabel,
                                               DialogBindings bindings,
                                               JLabel tagLabel) {
        if (slider == null || valueLabel == null || bindings == null || tagLabel == null) return;
        slider.addChangeListener(e -> {
            valueLabel.setText(String.format(Locale.ROOT, "%.3f", slider.getValue() / 1000.0));
            if (!bindings.programmaticChange) {
                clearSourceTag(tagLabel);
            }
        });
    }

    private boolean isInteractiveHeadless() {
        return headless || suppressDialogs || GraphicsEnvironment.isHeadless() || IJ.getInstance() == null;
    }

    private DeconvPreviewDialog.Decision showPreviewBeforeBatch(String directory,
                                                                SeriesJob job,
                                                                String[] channelNames,
                                                                RunSettings settings) {
        if (settings == null || settings.skipPreview || isInteractiveHeadless()) {
            return DeconvPreviewDialog.Decision.RUN_FULL_BATCH;
        }

        int channelIndex = firstSelectedChannel(settings.selectedChannels);
        if (channelIndex < 0 || channelIndex >= channelNames.length) {
            return DeconvPreviewDialog.Decision.RUN_FULL_BATCH;
        }

        ImagePlus rawChannel = null;
        ImagePlus rawCrop = null;
        ImagePlus psf = null;
        ImagePlus deconvolved = null;
        ImagePlus rawProjection = null;
        ImagePlus deconvolvedProjection = null;
        try {
            rawChannel = openSeriesChannel(directory, job.seriesIndex, channelIndex);
            if (rawChannel == null) {
                return DeconvPreviewDialog.Decision.RUN_FULL_BATCH;
            }

            rawCrop = cropCenterStack(rawChannel, 256, 256, "Raw Preview");
            if (rawCrop == null) {
                return DeconvPreviewDialog.Decision.RUN_FULL_BATCH;
            }

            ResolvedSeriesSettings resolved = resolveSeriesSettings(job.seriesInfo, settings, channelNames.length);
            PsfSpec spec = new PsfSpec(
                    resolved.numericalAperture,
                    resolved.immersionRi,
                    resolved.sampleRi,
                    resolved.emissionWavelengthsNm[channelIndex],
                    resolved.xyPixelSizeUm * 1000.0,
                    resolved.zStepUm * 1000.0,
                    rawCrop.getWidth(),
                    rawCrop.getHeight(),
                    rawCrop.getStackSize(),
                    settings.scopeModality,
                    settings.scopeModality == ScopeModality.CONFOCAL
                            ? Double.valueOf(resolved.pinholeAiryUnits)
                            : null
            );
            psf = getOrCreatePsf(spec, settings.psfModel);
            if (psf == null) {
                return DeconvPreviewDialog.Decision.RUN_FULL_BATCH;
            }

            DeconvolutionEngine engine = resolveEngine(settings.engineKey);
            DeconvParams params = DeconvParams.builder(settings.algorithm)
                    .iterations(settings.iterations)
                    .regularization(settings.regularization)
                    .edgeHandling(EdgeHandling.REFLECT)
                    .build();
            deconvolved = engine.deconvolve(rawCrop, psf, params);
            if (deconvolved == null) {
                return DeconvPreviewDialog.Decision.RUN_FULL_BATCH;
            }

            double[] rawRange = stackDisplayRange(rawCrop);
            rawProjection = maxProject(rawCrop, "Raw");
            deconvolvedProjection = maxProject(deconvolved, "Deconvolved");
            applyDisplayRange(rawProjection, rawRange[0], rawRange[1]);
            applyDisplayRange(deconvolvedProjection, rawRange[0], rawRange[1]);

            String deconvolvedLabel = "Deconvolved (" + engine.displayName()
                    + ", " + settings.iterations + " iter, "
                    + settings.psfModel.displayName() + ")";
            return DeconvPreviewDialog.show(
                    new DeconvPreviewDialog.PreviewContent(
                            rawProjection,
                            deconvolvedProjection,
                            "Raw",
                            deconvolvedLabel
                    ),
                    false
            );
        } catch (Exception e) {
            IJ.log("Deconvolution preview failed: " + e.getMessage() + ". Continuing without preview.");
            return DeconvPreviewDialog.Decision.RUN_FULL_BATCH;
        } finally {
            closeQuietly(deconvolvedProjection);
            closeQuietly(rawProjection);
            closeQuietly(deconvolved);
            closeQuietly(psf);
            closeQuietly(rawCrop);
            closeQuietly(rawChannel);
        }
    }

    private static int firstSelectedChannel(boolean[] selectedChannels) {
        if (selectedChannels == null) return -1;
        for (int i = 0; i < selectedChannels.length; i++) {
            if (selectedChannels[i]) {
                return i;
            }
        }
        return -1;
    }

    private static ImagePlus cropCenterStack(ImagePlus image, int maxWidth, int maxHeight, String title) {
        if (image == null || image.getStack() == null) return null;

        int cropWidth = Math.min(maxWidth, image.getWidth());
        int cropHeight = Math.min(maxHeight, image.getHeight());
        int x = Math.max(0, (image.getWidth() - cropWidth) / 2);
        int y = Math.max(0, (image.getHeight() - cropHeight) / 2);

        ImageStack source = image.getStack();
        ImageStack cropped = new ImageStack(cropWidth, cropHeight);
        for (int z = 1; z <= source.getSize(); z++) {
            ImageProcessor processor = source.getProcessor(z).duplicate();
            processor.setRoi(x, y, cropWidth, cropHeight);
            cropped.addSlice(source.getSliceLabel(z), processor.crop());
        }

        ImagePlus result = new ImagePlus(title, cropped);
        if (image.getCalibration() != null) {
            result.setCalibration(image.getCalibration().copy());
        }
        return result;
    }

    private static ImagePlus maxProject(ImagePlus image, String title) {
        if (image == null) return null;
        ZProjector projector = new ZProjector(image);
        projector.setMethod(ZProjector.MAX_METHOD);
        projector.doProjection();
        ImagePlus projection = projector.getProjection();
        if (projection != null) {
            projection.setTitle(title);
            if (image.getCalibration() != null) {
                projection.setCalibration(image.getCalibration().copy());
            }
        }
        return projection;
    }

    private static double[] stackDisplayRange(ImagePlus image) {
        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;
        if (image == null || image.getStack() == null) {
            return new double[]{0.0, 0.0};
        }
        ImageStack stack = image.getStack();
        for (int z = 1; z <= stack.getSize(); z++) {
            ImageProcessor processor = stack.getProcessor(z);
            int pixelCount = processor.getPixelCount();
            for (int i = 0; i < pixelCount; i++) {
                float value = processor.getf(i);
                if (value < min) min = value;
                if (value > max) max = value;
            }
        }
        if (min == Double.POSITIVE_INFINITY || max == Double.NEGATIVE_INFINITY) {
            return new double[]{0.0, 0.0};
        }
        return new double[]{min, max};
    }

    private static void applyDisplayRange(ImagePlus image, double min, double max) {
        if (image == null) return;
        image.setDisplayRange(min, max);
    }

    private static SourceTaggedRow taggedRow(String label, JComponent component) {
        JPanel row = new JPanel();
        row.setOpaque(false);
        row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
        row.setBorder(BorderFactory.createEmptyBorder(0, 4, 0, 4));
        JLabel lbl = rowLabel(label);
        JLabel sourceTagLabel = new JLabel("");
        sourceTagLabel.setForeground(TAG_BLUE);
        sourceTagLabel.setFont(sourceTagLabel.getFont().deriveFont(Font.PLAIN, 11f));
        sourceTagLabel.setVisible(false);
        JPanel valueColumn = controlColumn(component);
        valueColumn.add(Box.createVerticalStrut(2));
        valueColumn.add(sourceTagLabel);
        row.add(lbl);
        row.add(Box.createHorizontalStrut(ROW_GAP));
        row.add(valueColumn);
        row.add(Box.createHorizontalGlue());
        return new SourceTaggedRow(row, component, sourceTagLabel);
    }

    private static JPanel groupedComponents(JComponent first, JComponent second) {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        panel.setOpaque(false);
        panel.add(first);
        panel.add(second);
        return panel;
    }

    private void runBatch(String directory, List<SeriesJob> jobs, String[] channelNames, RunSettings settings) {
        File rootDir = new File(directory);
        File outputDir = DeconvolutionIO.deconvOutDir(rootDir);
        try {
            ensureDirectory(outputDir);
        } catch (IOException e) {
            IJ.log("[FLASH] Could not create deconvolution output directory: " + e.getMessage());
            return;
        }
        Set<String> writtenPsfHashes = new HashSet<String>();
        DeconvolutionEngine engine = resolveEngine(settings.engineKey);
        long batchStarted = now();
        DeconvSummaryReport summaryReport = null;
        try {
            summaryReport = new DeconvSummaryReport(rootDir);
        } catch (IOException e) {
            IJ.log("Could not initialize deconvolution summary report: " + e.getMessage());
        }

        IJ.log("==========================================================");
        IJ.log("3D Deconvolution");
        IJ.log("==========================================================");
        IJ.log("Directory: " + directory);
        IJ.log("Engine: " + engine.displayName());
        IJ.log("Algorithm: " + settings.algorithm.displayName());
        IJ.log("PSF model: " + settings.psfModel.displayName());
        if (verboseLogging) {
            IJ.log("Strict Nyquist: " + settings.strictNyquist);
            IJ.log("Use cache: " + settings.useCache);
        }

        for (SeriesJob job : jobs) {
            long started = now();
            List<String> warnings = new ArrayList<String>();
            List<String> channelOutcomes = new ArrayList<String>();

            ResolvedSeriesSettings resolved = resolveSeriesSettings(job.seriesInfo, settings, channelNames.length);
            long peakUsedBytes = usedHeapBytes();
            List<String> missingFields = missingFieldsForSeries(job.seriesInfo, settings, resolved);
            if (!missingFields.isEmpty()) {
                warnings.add("Skipped: missing required metadata/overrides: " + joinList(missingFields));
                writeDetailsFile(rootDir, job, settings, resolved, channelNames,
                        channelOutcomes, warnings, started, now() - started, peakUsedBytes);
                IJ.log("Deconvolution skip [" + job.baseName + "]: " + warnings.get(0));
                continue;
            }
            if (!hasAnySelectedChannel(settings.selectedChannels)) {
                warnings.add("Skipped: no channels selected.");
                writeDetailsFile(rootDir, job, settings, resolved, channelNames,
                        channelOutcomes, warnings, started, now() - started, peakUsedBytes);
                IJ.log("Deconvolution skip [" + job.baseName + "]: no channels selected.");
                continue;
            }

            MetadataDiagnostics.NyquistCheckResult nyquist = MetadataDiagnostics.checkNyquist(
                    job.seriesInfo,
                    settings.scopeModality,
                    resolved.emissionWavelengthsNm,
                    resolved.sampleRi,
                    Double.valueOf(resolved.numericalAperture),
                    Double.valueOf(resolved.xyPixelSizeUm),
                    Double.valueOf(resolved.zStepUm),
                    settings.selectedChannels);
            if (nyquist != null && nyquist.hasWarning()) {
                if (settings.strictNyquist && nyquist.isUnderSampled()) {
                    warnings.add("Skipped: " + nyquist.getMessage());
                    writeDetailsFile(rootDir, job, settings, resolved, channelNames,
                            channelOutcomes, warnings, started, now() - started, peakUsedBytes);
                    IJ.log("Deconvolution skip [" + job.baseName + "]: " + nyquist.getMessage());
                    continue;
                }
                warnings.add(nyquist.getMessage());
                IJ.log("Deconvolution warning [" + job.baseName + "]: " + nyquist.getMessage());
            }

            for (int channelIndex = 0; channelIndex < channelNames.length; channelIndex++) {
                if (channelIndex >= settings.selectedChannels.length || !settings.selectedChannels[channelIndex]) {
                    continue;
                }

                long channelStarted = now();
                long channelPeakUsedBytes = usedHeapBytes();
                List<String> summaryWarnings = new ArrayList<String>();
                if (nyquist != null && nyquist.hasWarning()) {
                    summaryWarnings.add("nyquistUnder");
                }
                if (resolved.sampleRiInferred) {
                    summaryWarnings.add("riInferred");
                }

                File outFile = DeconvolutionIO.deconvFile(rootDir, job.baseName, channelIndex);
                File existingOutFile = DeconvolutionIO.firstExistingFile(
                        DeconvolutionIO.deconvFileReadCandidates(rootDir, job.baseName, channelIndex));
                if (skipExisting && existingOutFile != null) {
                    channelOutcomes.add(channelNames[channelIndex] + ": skipped existing output");
                    appendSummaryRow(summaryReport,
                            job.baseName,
                            channelNames[channelIndex],
                            engine,
                            settings,
                            sizeXYZ(job.seriesInfo),
                            now() - channelStarted,
                            channelPeakUsedBytes,
                            false,
                            summaryWarnings);
                    continue;
                }

                Map<String, String> hashParams = buildHashParams(settings, job.seriesInfo, resolved, channelIndex);
                String paramsHash = DeconvolutionIO.paramsHash(hashParams);
                File cacheFile = DeconvolutionIO.cacheFile(rootDir, paramsHash, job.baseName, channelIndex);
                File cacheHitFile = DeconvolutionIO.firstFreshFile(job.sourceFile,
                        DeconvolutionIO.cacheFileReadCandidates(rootDir, paramsHash, job.baseName, channelIndex));

                if (settings.useCache && cacheHitFile != null) {
                    boolean cacheHit = false;
                    try {
                        copyFile(cacheHitFile, outFile);
                        channelOutcomes.add(channelNames[channelIndex] + ": cache hit");
                        cacheHit = true;
                    } catch (IOException e) {
                        warnings.add("Cache copy failed for " + channelNames[channelIndex] + ": " + e.getMessage());
                        summaryWarnings.add("cacheCopyFailed");
                    }
                    if (cacheHit) {
                        appendSummaryRow(summaryReport,
                                job.baseName,
                                channelNames[channelIndex],
                                engine,
                                settings,
                                sizeXYZ(job.seriesInfo),
                                now() - channelStarted,
                                channelPeakUsedBytes,
                                true,
                                summaryWarnings);
                        continue;
                    }
                }

                ImagePlus channelStack = null;
                ImagePlus psf = null;
                ImagePlus deconvolved = null;
                String sizeXYZ = sizeXYZ(job.seriesInfo);
                try {
                    channelStack = openSeriesChannel(directory, job.seriesIndex, channelIndex);
                    channelPeakUsedBytes = Math.max(channelPeakUsedBytes, usedHeapBytes());
                    if (channelStack == null) {
                        channelOutcomes.add(channelNames[channelIndex] + ": skipped (channel could not be opened)");
                        summaryWarnings.add("openFailed");
                        continue;
                    }
                    sizeXYZ = sizeXYZ(channelStack);

                    long requiredBytes = requiredFor3DDeconv(channelStack);
                    long availableBytes = estimatedAvailableMemory();
                    if (requiredBytes > availableBytes) {
                        String message = "memory skip (" + humanMiB(requiredBytes) + " MiB required, "
                                + humanMiB(availableBytes) + " MiB available)";
                        channelOutcomes.add(channelNames[channelIndex] + ": " + message);
                        warnings.add(channelNames[channelIndex] + " " + message);
                        summaryWarnings.add("memorySkip");
                        continue;
                    }

                    PsfSpec spec = new PsfSpec(
                            resolved.numericalAperture,
                            resolved.immersionRi,
                            resolved.sampleRi,
                            resolved.emissionWavelengthsNm[channelIndex],
                            resolved.xyPixelSizeUm * 1000.0,
                            resolved.zStepUm * 1000.0,
                            channelStack.getWidth(),
                            channelStack.getHeight(),
                            channelStack.getStackSize(),
                            settings.scopeModality,
                            settings.scopeModality == ScopeModality.CONFOCAL
                                    ? Double.valueOf(resolved.pinholeAiryUnits)
                                    : null
                    );

                    psf = getOrCreatePsf(spec, settings.psfModel);
                    channelPeakUsedBytes = Math.max(channelPeakUsedBytes, usedHeapBytes());
                    if (psf == null) {
                        String message = "PSF synthesis failed or plugin is missing";
                        channelOutcomes.add(channelNames[channelIndex] + ": " + message);
                        warnings.add(channelNames[channelIndex] + " " + message);
                        summaryWarnings.add("psfFailed");
                        continue;
                    }

                    if (writtenPsfHashes.add(paramsHash)) {
                        writePsfPreview(psf, spec, settings.psfModel, outputDir);
                    }

                    DeconvParams params = DeconvParams.builder(settings.algorithm)
                            .iterations(settings.iterations)
                            .regularization(settings.regularization)
                            .edgeHandling(EdgeHandling.REFLECT)
                            .build();
                    deconvolved = engine.deconvolve(channelStack, psf, params);
                    channelPeakUsedBytes = Math.max(channelPeakUsedBytes, usedHeapBytes());
                    saveTiff(deconvolved, outFile);
                    if (settings.useCache) {
                        copyFile(outFile, cacheFile);
                    }
                    channelOutcomes.add(channelNames[channelIndex] + ": written (" + paramsHash + ")");
                } catch (DeconvolutionException e) {
                    warnings.add(channelNames[channelIndex] + " failed: " + e.getMessage());
                    channelOutcomes.add(channelNames[channelIndex] + ": failed");
                    summaryWarnings.add("failed");
                    IJ.log("Deconvolution failed [" + job.baseName + ", " + channelNames[channelIndex] + "]: " + e.getMessage());
                } catch (Exception e) {
                    warnings.add(channelNames[channelIndex] + " failed: " + e.getMessage());
                    channelOutcomes.add(channelNames[channelIndex] + ": failed");
                    summaryWarnings.add("failed");
                    IJ.log("Deconvolution failed [" + job.baseName + ", " + channelNames[channelIndex] + "]: " + e.getMessage());
                } finally {
                    closeQuietly(deconvolved);
                    closeQuietly(psf);
                    closeQuietly(channelStack);
                }
                peakUsedBytes = Math.max(peakUsedBytes, channelPeakUsedBytes);
                appendSummaryRow(summaryReport,
                        job.baseName,
                        channelNames[channelIndex],
                        engine,
                        settings,
                        sizeXYZ,
                        now() - channelStarted,
                        channelPeakUsedBytes,
                        false,
                        summaryWarnings);
            }

            try {
                writeMergedOutput(directory, rootDir, job, channelNames.length, settings.selectedChannels);
            } catch (Exception e) {
                warnings.add("Merged deconvolved output failed: " + e.getMessage());
                IJ.log("Could not write merged deconvolved output for " + job.baseName + ": " + e.getMessage());
            }

            long elapsed = now() - started;
            writeDetailsFile(rootDir, job, settings, resolved, channelNames,
                    channelOutcomes, warnings, started, elapsed, peakUsedBytes);
        }

        if (summaryReport != null) {
            try {
                summaryReport.finish(now() - batchStarted);
            } catch (IOException e) {
                IJ.log("Could not finalize deconvolution summary report: " + e.getMessage());
            }
        }

        if (!suppressDialogs && !headless) {
            JOptionPane.showMessageDialog(null, "3D deconvolution finished.", TITLE, JOptionPane.INFORMATION_MESSAGE);
        }
    }

    protected List<SeriesJob> listSeriesJobs(String directory) throws Exception {
        File lifFile = LifIO.requireSingleLifFile(directory);
        List<SeriesMeta> metas = readAllSeriesMetadata(lifFile);
        List<SeriesJob> jobs = new ArrayList<SeriesJob>();
        for (SeriesMeta meta : metas) {
            if (ImageNameParser.isPreviewSeriesName(meta.name)) {
                continue;
            }
            MetadataDiagnostics.SeriesInfo info;
            try {
                info = readSeriesInfo(lifFile, meta.index);
            } catch (Exception e) {
                IJ.log(TITLE + ": detailed metadata unavailable for series " + (meta.index + 1)
                        + " (" + nullToEmpty(meta.name) + "). Manual deconvolution fields will be requested. "
                        + e.getMessage());
                info = fallbackSeriesInfo(lifFile, meta);
            }
            if (info == null) continue;
            if (info.imageName == null || info.imageName.trim().isEmpty()) {
                info.imageName = meta.name;
            }
            String baseName = ImageNameParser.extractBioFormatsSeriesName(info.imageName);
            if (baseName == null || baseName.trim().isEmpty()) {
                baseName = "Series_" + (meta.index + 1);
            }
            jobs.add(new SeriesJob(lifFile, meta.index, baseName.trim(), info));
        }
        return jobs;
    }

    protected List<SeriesMeta> readAllSeriesMetadata(File lifFile) throws Exception {
        return LifIO.readAllSeriesMetadata(lifFile);
    }

    protected MetadataDiagnostics.SeriesInfo readSeriesInfo(File lifFile, int seriesIndex) throws Exception {
        return MetadataDiagnostics.readOneSeriesInfo(lifFile, seriesIndex);
    }

    private MetadataDiagnostics.SeriesInfo fallbackSeriesInfo(File lifFile, SeriesMeta meta) {
        MetadataDiagnostics.SeriesInfo info = new MetadataDiagnostics.SeriesInfo();
        info.file = lifFile == null ? "" : lifFile.getName();
        info.extension = lifFile == null ? "" : extension(lifFile.getName());
        info.seriesIndex = meta == null ? 0 : meta.index;
        info.imageName = meta == null ? null : meta.name;
        info.sizeX = meta == null ? 0 : meta.width;
        info.sizeY = meta == null ? 0 : meta.height;
        info.sizeZ = meta == null ? 0 : meta.nSlices;
        info.sizeC = meta == null ? 0 : meta.nChannels;
        if (meta != null && meta.isCalibrated()) {
            if (meta.pixelWidth > 0.0) {
                info.pixelSizeXUm = Double.valueOf(meta.pixelWidth);
            }
            if (meta.pixelDepth > 0.0) {
                info.pixelSizeZUm = Double.valueOf(meta.pixelDepth);
            }
        }
        int channels = Math.max(0, info.sizeC);
        info.emissionWavelengthNm = new double[channels];
        Arrays.fill(info.emissionWavelengthNm, Double.NaN);
        return info;
    }

    protected ImagePlus openSeriesChannel(String directory, int seriesIndex, int channelIndex) throws Exception {
        return LifIO.createDeferredSupplier(directory).openSeriesMaterializedChannel(seriesIndex, channelIndex);
    }

    protected List<DeconvolutionEngine> allEngines() {
        return EngineRegistry.all();
    }

    protected List<DeconvolutionEngine> availableEngines() {
        return EngineRegistry.available();
    }

    protected boolean isBioFormatsAvailable() {
        return FeatureDependencyGate.gate(DependencyId.BIO_FORMATS_RUNTIME,
                TITLE, "Bio-Formats metadata and image loading");
    }

    protected boolean isPsfGeneratorAvailable() {
        return DeconvolutionAvailability.isPsfGeneratorAvailable();
    }

    protected String installInstructionUrl(String key) {
        return DeconvolutionAvailability.installInstructionUrl(key);
    }

    protected DeconvolutionEngine resolveEngine(String key) {
        return EngineRegistry.byKey(key);
    }

    protected long requiredFor3DDeconv(ImagePlus stack) {
        return HeapBudget.requiredFor3DDeconv(stack);
    }

    protected long estimatedAvailableMemory() {
        return HeapBudget.estimatedAvailable();
    }

    protected ImagePlus getOrCreatePsf(PsfSpec spec, PsfModel model) {
        return PsfCache.get(spec, model);
    }

    protected void writePsfPreview(ImagePlus psf, PsfSpec spec, PsfModel model, File outputDir) {
        PsfQcWriter.writePsfPreview(psf, spec, model, outputDir);
    }

    protected void saveTiff(ImagePlus image, File target) throws IOException {
        ensureDirectory(target.getParentFile());
        FileSaver saver = new FileSaver(image);
        boolean ok = image.getStackSize() > 1
                ? saver.saveAsTiffStack(target.getAbsolutePath())
                : saver.saveAsTiff(target.getAbsolutePath());
        if (!ok) {
            throw new IOException("Could not save TIFF " + target.getAbsolutePath());
        }
    }

    private void writeMergedOutput(String directory,
                                   File rootDir,
                                   SeriesJob job,
                                   int channelCount,
                                   boolean[] selectedChannels) throws Exception {
        if (job == null || channelCount <= 0) return;

        File mergedFile = DeconvolutionIO.mergedDeconvFile(rootDir, job.baseName);
        File existingMergedFile = DeconvolutionIO.firstFreshFile(job.sourceFile,
                DeconvolutionIO.mergedDeconvFileReadCandidates(rootDir, job.baseName));
        if (skipExisting && existingMergedFile != null) {
            return;
        }

        ImagePlus[] channelImages = new ImagePlus[channelCount];
        try {
            for (int channelIndex = 0; channelIndex < channelCount; channelIndex++) {
                File channelFile = DeconvolutionIO.firstExistingFile(
                        DeconvolutionIO.deconvFileReadCandidates(rootDir, job.baseName, channelIndex));
                boolean useDeconvolvedChannel = selectedChannels != null
                        && channelIndex < selectedChannels.length
                        && selectedChannels[channelIndex]
                        && channelFile != null
                        && channelFile.isFile();
                if (useDeconvolvedChannel) {
                    channelImages[channelIndex] = new Opener().openImage(channelFile.getAbsolutePath());
                } else {
                    channelImages[channelIndex] = openSeriesChannel(directory, job.seriesIndex, channelIndex);
                }
                if (channelImages[channelIndex] == null) {
                    throw new IOException("Could not load channel " + channelIndex + " for merged output.");
                }
                channelImages[channelIndex].setTitle("C" + (channelIndex + 1) + "-" + job.baseName);
            }

            ImagePlus merged;
            if (channelCount == 1) {
                merged = channelImages[0].duplicate();
            } else {
                merged = RGBStackMerge.mergeChannels(channelImages, true);
            }
            if (merged == null) {
                throw new IOException("ImageJ failed to merge deconvolved channels.");
            }
            try {
                merged.setTitle(job.baseName + "_deconv");
                saveTiff(merged, mergedFile);
            } finally {
                closeQuietly(merged);
            }
        } finally {
            for (ImagePlus channelImage : channelImages) {
                closeQuietly(channelImage);
            }
        }
    }

    protected void browseInstallUrl(String url) {
        if (url == null || url.trim().isEmpty()) return;
        try {
            if (!Desktop.isDesktopSupported()) {
                IJ.log("Desktop browsing is not supported here. Install URL: " + url);
                return;
            }
            Desktop.getDesktop().browse(URI.create(url));
        } catch (Exception e) {
            IJ.log("Could not open install URL: " + url + " (" + e.getMessage() + ")");
        }
    }

    protected long now() {
        return System.currentTimeMillis();
    }

    private void appendSummaryRow(DeconvSummaryReport summaryReport,
                                  String imageName,
                                  String channelName,
                                  DeconvolutionEngine engine,
                                  RunSettings settings,
                                  String sizeXYZ,
                                  long elapsedMs,
                                  long peakUsedBytes,
                                  boolean cacheHit,
                                  List<String> warnings) {
        if (summaryReport == null) return;
        try {
            summaryReport.appendRow(new DeconvSummaryReport.Row(
                    imageName,
                    channelName,
                    engine == null ? settings.engineKey : engine.displayName(),
                    settings.algorithm == null ? "" : settings.algorithm.displayName(),
                    settings.iterations,
                    settings.regularization,
                    settings.psfModel == null ? "" : settings.psfModel.displayName(),
                    sizeXYZ,
                    elapsedMs,
                    peakUsedBytes / 1048576.0,
                    cacheHit,
                    warnings
            ));
        } catch (IOException e) {
            IJ.log("Could not append deconvolution summary row for " + imageName + " / " + channelName
                    + ": " + e.getMessage());
        }
    }

    private static String sizeXYZ(MetadataDiagnostics.SeriesInfo info) {
        if (info == null || info.sizeX <= 0 || info.sizeY <= 0 || info.sizeZ <= 0) return "";
        return info.sizeX + "x" + info.sizeY + "x" + info.sizeZ;
    }

    private static String sizeXYZ(ImagePlus image) {
        if (image == null) return "";
        return image.getWidth() + "x" + image.getHeight() + "x" + image.getNSlices();
    }

    private String[] resolveChannelNames(String directory, MetadataDiagnostics.SeriesInfo representative) {
        int channelCount = representative == null ? 0 : representative.sizeC;
        BinConfig config = null;
        try {
            config = BinConfigIO.readFromDirectory(directory);
            channelCount = Math.max(channelCount, config.numChannels());
        } catch (IOException ignored) {}

        if (channelCount <= 0) return new String[0];
        String[] names = new String[channelCount];
        for (int i = 0; i < channelCount; i++) {
            if (config != null && i < config.channelNames.size()) {
                names[i] = config.channelNames.get(i);
            } else {
                names[i] = "Channel " + (i + 1);
            }
        }
        return names;
    }

    private List<String> validateRequiredFields(MetadataDiagnostics.SeriesInfo representative, RunSettings settings) {
        ResolvedSeriesSettings resolved = resolveSeriesSettings(representative, settings, settings.channelNames.length);
        return missingFieldsForSeries(representative, settings, resolved);
    }

    private List<String> missingFieldsForSeries(MetadataDiagnostics.SeriesInfo info,
                                                RunSettings settings,
                                                ResolvedSeriesSettings resolved) {
        List<String> missing = new ArrayList<String>();
        if (resolved.numericalAperture <= 0.0) missing.add("Numerical Aperture");
        if (resolved.immersionRi <= 0.0) missing.add("Immersion RI");
        if (resolved.sampleRi <= 0.0) missing.add("Sample RI");
        if (resolved.zStepUm <= 0.0) missing.add("Z-step");
        if (resolved.xyPixelSizeUm <= 0.0) {
            missing.add("XY pixel size");
        }
        for (int i = 0; i < resolved.emissionWavelengthsNm.length; i++) {
            if (i < settings.selectedChannels.length && !settings.selectedChannels[i]) continue;
            double value = resolved.emissionWavelengthsNm[i];
            if (Double.isNaN(value) || value <= 0.0) {
                missing.add(settings.channelNames[i] + " emission wavelength");
            }
        }
        return missing;
    }

    private ResolvedSeriesSettings resolveSeriesSettings(MetadataDiagnostics.SeriesInfo info,
                                                         RunSettings settings,
                                                         int channelCount) {
        ResolvedSeriesSettings resolved = new ResolvedSeriesSettings();
        resolved.numericalAperture = firstPositive(settings.naOverride, info == null ? null : info.objectiveNA);

        double immersionInferred = info == null ? Double.NaN : RefractiveIndexEstimator.immersionRI(info.objectiveImmersion);
        if (!Double.isNaN(immersionInferred) && immersionInferred <= 0.0) immersionInferred = Double.NaN;
        resolved.immersionRi = firstPositive(settings.immersionRiOverride, Double.isNaN(immersionInferred) ? null : Double.valueOf(immersionInferred));

        double sampleRi = settings.sampleRiOverride == null ? Double.NaN : settings.sampleRiOverride.doubleValue();
        if (Double.isNaN(sampleRi) || sampleRi <= 0.0) {
            sampleRi = RefractiveIndexEstimator.inferSampleRI(
                    info == null ? null : info.objectiveImmersion,
                    settings.mountingMedium);
            resolved.sampleRiInferred = !Double.isNaN(sampleRi) && sampleRi > 0.0;
        }
        resolved.sampleRi = sampleRi;
        resolved.xyPixelSizeUm = firstPositive(settings.xyPixelSizeOverrideUm, info == null ? null : info.pixelSizeXUm);
        resolved.zStepUm = firstPositive(settings.zStepOverrideUm, info == null ? null : info.pixelSizeZUm);
        resolved.pinholeAiryUnits = settings.pinholeAiryUnits == null
                ? 1.0
                : settings.pinholeAiryUnits.doubleValue();
        resolved.emissionWavelengthsNm = mergeWavelengths(settings.emissionOverridesNm,
                info == null ? null : info.emissionWavelengthNm,
                channelCount);
        return resolved;
    }

    private double[] mergeWavelengths(double[] overrides, double[] metadata, int channelCount) {
        double[] merged = new double[channelCount];
        Arrays.fill(merged, Double.NaN);
        for (int i = 0; i < channelCount; i++) {
            double value = Double.NaN;
            if (overrides != null && i < overrides.length) value = overrides[i];
            if ((Double.isNaN(value) || value <= 0.0) && metadata != null && i < metadata.length) value = metadata[i];
            merged[i] = value;
        }
        return merged;
    }

    private void addReportSection(RunSettings settings, String[] channelNames) {
        if (qualityReport == null || !qualityReport.isEnabled()) return;
        Map<String, String> params = new LinkedHashMap<String, String>();
        params.put("Engine", settings.engineKey);
        params.put("Algorithm", settings.algorithm == null ? "" : settings.algorithm.displayName());
        params.put("PSF Model", settings.psfModel == null ? "" : settings.psfModel.displayName());
        params.put("Iterations", String.valueOf(settings.iterations));
        params.put("Regularization", String.format(Locale.ROOT, "%.3f", settings.regularization));
        params.put("Scope Modality", settings.scopeModality == null ? "" : settings.scopeModality.displayName());
        params.put("Strict Nyquist", String.valueOf(settings.strictNyquist));
        params.put("Use Cache", String.valueOf(settings.useCache));
        params.put("Channels", selectedChannelList(channelNames, settings.selectedChannels));
        qualityReport.addSection(TITLE, params);
    }

    private List<String> validateRequiredFields(SeriesJob representative, RunSettings settings) {
        return validateRequiredFields(representative.seriesInfo, settings);
    }

    private boolean isSelectedEngineAvailable(String engineKey) {
        for (DeconvolutionEngine engine : availableEngines()) {
            if (engine.key().equals(engineKey)) return true;
        }
        return false;
    }

    private void promptInstall(String message, String url) {
        if (headless || suppressDialogs) {
            IJ.log(TITLE + ": " + message + (url == null ? "" : " Install: " + url));
            return;
        }
        int choice = JOptionPane.showConfirmDialog(
                null,
                message + (url == null ? "" : "\n\nOpen install page now?"),
                TITLE,
                url == null ? JOptionPane.DEFAULT_OPTION : JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE);
        if (choice == JOptionPane.YES_OPTION && url != null) {
            browseInstallUrl(url);
        }
    }

    private void showValidationErrors(List<String> errors) {
        StringBuilder sb = new StringBuilder();
        sb.append("Please fill these required fields before running:\n\n");
        for (String error : errors) {
            sb.append("- ").append(error).append('\n');
        }
        showOrLogError(sb.toString().trim());
    }

    private void showOrLogError(String message) {
        IJ.log(TITLE + ": " + message);
        if (!headless && !suppressDialogs) {
            JOptionPane.showMessageDialog(null, message, TITLE, JOptionPane.ERROR_MESSAGE);
        }
    }

    private void writeDetailsFile(File rootDir,
                                  SeriesJob job,
                                  RunSettings settings,
                                  ResolvedSeriesSettings resolved,
                                  String[] channelNames,
                                  List<String> channelOutcomes,
                                  List<String> warnings,
                                  long started,
                                  long elapsedMs,
                                  long peakUsedBytes) {
        File detailsFile = DeconvolutionIO.detailsFile(rootDir, job.baseName);
        StringBuilder sb = new StringBuilder();
        sb.append("Image: ").append(job.displayName).append('\n');
        sb.append("Source File: ").append(job.sourceFile == null ? "" : job.sourceFile.getAbsolutePath()).append('\n');
        sb.append("Series Index: ").append(job.seriesIndex).append('\n');
        sb.append("Engine: ").append(settings.engineKey).append('\n');
        sb.append("Algorithm: ").append(settings.algorithm == null ? "" : settings.algorithm.name()).append('\n');
        sb.append("Iterations: ").append(settings.iterations).append('\n');
        sb.append("Regularization: ").append(String.format(Locale.ROOT, "%.6f", settings.regularization)).append('\n');
        sb.append("PSF Model: ").append(settings.psfModel == null ? "" : settings.psfModel.name()).append('\n');
        sb.append("Scope Modality: ").append(settings.scopeModality == null ? "" : settings.scopeModality.name()).append('\n');
        sb.append("Use Cache: ").append(settings.useCache).append('\n');
        sb.append("Strict Nyquist: ").append(settings.strictNyquist).append('\n');
        sb.append("Selected Channels: ").append(selectedChannelList(channelNames, settings.selectedChannels)).append('\n');
        if (resolved != null) {
            sb.append("XY Pixel Size (um): ").append(DeconvolutionIO.formatDouble(resolved.xyPixelSizeUm)).append('\n');
            sb.append("Numerical Aperture: ").append(DeconvolutionIO.formatDouble(resolved.numericalAperture)).append('\n');
            sb.append("Immersion RI: ").append(DeconvolutionIO.formatDouble(resolved.immersionRi)).append('\n');
            sb.append("Sample RI: ").append(DeconvolutionIO.formatDouble(resolved.sampleRi)).append('\n');
            sb.append("Z-step (um): ").append(DeconvolutionIO.formatDouble(resolved.zStepUm)).append('\n');
            if (settings.scopeModality == ScopeModality.CONFOCAL) {
                sb.append("Pinhole (AU): ").append(DeconvolutionIO.formatDouble(resolved.pinholeAiryUnits)).append('\n');
            }
            sb.append("Emission Wavelengths (nm): ")
                    .append(joinWavelengths(resolved.emissionWavelengthsNm, channelNames.length))
                    .append('\n');
        }
        sb.append("Started Epoch Ms: ").append(started).append('\n');
        sb.append("Elapsed Ms: ").append(elapsedMs).append('\n');
        sb.append("Peak Heap MiB: ").append(humanMiB(peakUsedBytes)).append('\n');
        sb.append('\n').append("Channel Outcomes:\n");
        if (channelOutcomes.isEmpty()) {
            sb.append("  none\n");
        } else {
            for (String outcome : channelOutcomes) {
                sb.append("  ").append(outcome).append('\n');
            }
        }
        sb.append('\n').append("Warnings:\n");
        if (warnings.isEmpty()) {
            sb.append("  none\n");
        } else {
            for (String warning : warnings) {
                sb.append("  ").append(warning).append('\n');
            }
        }
        try {
            ensureDirectory(detailsFile.getParentFile());
            Files.write(detailsFile.toPath(), sb.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            IJ.log("Could not write deconvolution details for " + job.baseName + ": " + e.getMessage());
        }
    }

    private Map<String, String> buildHashParams(RunSettings settings,
                                                MetadataDiagnostics.SeriesInfo info,
                                                ResolvedSeriesSettings resolved,
                                                int channelIndex) {
        Map<String, String> params = new HashMap<String, String>();
        params.put("engine", settings.engineKey);
        params.put("algorithm", settings.algorithm.name());
        params.put("iterations", String.valueOf(settings.iterations));
        params.put("regularization", DeconvolutionIO.formatDouble(settings.regularization));
        params.put("psfModel", settings.psfModel.name());
        params.put("scopeModality", settings.scopeModality.name());
        params.put("pinhole", settings.scopeModality == ScopeModality.CONFOCAL
                ? DeconvolutionIO.formatDouble(resolved.pinholeAiryUnits)
                : "");
        params.put("na", DeconvolutionIO.formatDouble(resolved.numericalAperture));
        params.put("immersionRi", DeconvolutionIO.formatDouble(resolved.immersionRi));
        params.put("sampleRi", DeconvolutionIO.formatDouble(resolved.sampleRi));
        params.put("wavelengthNm", DeconvolutionIO.formatDouble(resolved.emissionWavelengthsNm[channelIndex]));
        params.put("pixelSizeXUm", DeconvolutionIO.formatDouble(resolved.xyPixelSizeUm));
        params.put("pixelSizeZUm", DeconvolutionIO.formatDouble(resolved.zStepUm));
        params.put("sizeX", String.valueOf(info.sizeX));
        params.put("sizeY", String.valueOf(info.sizeY));
        params.put("sizeZ", String.valueOf(info.sizeZ));
        return params;
    }

    private List<EngineChoice> engineChoices() {
        List<EngineChoice> choices = new ArrayList<EngineChoice>();
        Set<String> available = new HashSet<String>();
        for (DeconvolutionEngine engine : availableEngines()) {
            available.add(engine.key());
        }
        for (DeconvolutionEngine engine : allEngines()) {
            boolean isAvailable = available.contains(engine.key());
            String label = engine.displayName() + (isAvailable ? "" : " - Install...");
            choices.add(new EngineChoice(engine, isAvailable, label,
                    DeconvolutionAvailability.installInstructionUrl(engine.key())));
        }
        return choices;
    }

    private void selectEngineChoice(JComboBox<EngineChoice> combo, String engineKey) {
        for (int i = 0; i < combo.getItemCount(); i++) {
            EngineChoice choice = combo.getItemAt(i);
            if (choice.engine.key().equals(engineKey)) {
                combo.setSelectedIndex(i);
                return;
            }
        }
        if (combo.getItemCount() > 0) {
            combo.setSelectedIndex(0);
        }
    }

    private void populateAlgorithms(JComboBox<AlgorithmChoice> combo, DeconvolutionEngine engine) {
        combo.removeAllItems();
        if (engine == null) return;
        for (Algorithm algorithm : engine.supportedAlgorithms()) {
            combo.addItem(new AlgorithmChoice(algorithm));
        }
        combo.setSelectedItem(new AlgorithmChoice(defaultAlgorithm(engine)));
    }

    private Algorithm defaultAlgorithm(DeconvolutionEngine engine) {
        if (engine != null && engine.supportedAlgorithms().contains(Algorithm.RL_TV)) {
            return Algorithm.RL_TV;
        }
        return Algorithm.RL;
    }

    private ScopeModality defaultScopeModality(MetadataDiagnostics.SeriesInfo info) {
        ScopeModality guessed = MetadataDiagnostics.guessScopeModality(info);
        return guessed == null ? ScopeModality.WIDEFIELD : guessed;
    }

    private String defaultEngineKey() {
        List<DeconvolutionEngine> available = availableEngines();
        if (!available.isEmpty()) {
            return available.get(0).key();
        }
        List<DeconvolutionEngine> all = allEngines();
        return all.isEmpty() ? "CLIJ2" : all.get(0).key();
    }

    private static JPanel labeledRow(String label, JComponent component) {
        JPanel row = new JPanel();
        row.setOpaque(false);
        row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
        row.setBorder(BorderFactory.createEmptyBorder(0, 4, 0, 4));
        JLabel lbl = new JLabel(label);
        lbl.setForeground(LABEL_COLOR);
        row.add(lbl);
        row.add(Box.createHorizontalGlue());
        row.add(component);
        component.setAlignmentX(Component.RIGHT_ALIGNMENT);
        return row;
    }

    private static JPanel labelAndTwoComponents(String label, JComponent first, JComponent second) {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        row.setOpaque(false);
        JLabel lbl = new JLabel(label);
        lbl.setForeground(LABEL_COLOR);
        row.add(lbl);
        row.add(first);
        row.add(second);
        return row;
    }

    private static JPanel buttonRow(JButton button) {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        row.setOpaque(false);
        row.add(button);
        return row;
    }

    private static JPanel topHelpRow(JButton helpButton) {
        JPanel row = new JPanel();
        row.setOpaque(false);
        row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
        row.setBorder(BorderFactory.createEmptyBorder(0, 4, 2, 4));
        row.add(Box.createHorizontalGlue());
        row.add(helpButton);
        return row;
    }

    private static void styleSoftBlueButton(JButton button) {
        button.setBackground(SOFT_BLUE_BG);
        button.setForeground(SOFT_BLUE_FG);
        button.setOpaque(true);
        button.setContentAreaFilled(true);
        button.setFocusPainted(false);
        button.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(SOFT_BLUE_BORDER),
                BorderFactory.createEmptyBorder(3, 10, 3, 10)));
    }

    private static void styleHelpButton(JButton button) {
        styleSoftBlueButton(button);
        Dimension size = new Dimension(28, 24);
        button.setPreferredSize(size);
        button.setMinimumSize(size);
        button.setMaximumSize(size);
        button.setMargin(new java.awt.Insets(0, 0, 0, 0));
    }

    private static JLabel helpParagraph(String html) {
        JLabel label = new JLabel("<html><body width='" + HELP_DIALOG_TEXT_WIDTH + "'>"
                + html + "</body></html>");
        label.setForeground(LABEL_COLOR);
        label.setFont(label.getFont().deriveFont(Font.PLAIN, 11f));
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        label.setBorder(BorderFactory.createEmptyBorder(0, 8, 5, 0));
        return label;
    }

    private static MetadataFieldRow metadataField(String label, String value, String tag, Color tagColor) {
        JTextField field = new JTextField(value == null ? "" : value, 16);
        setFixedControlSize(field, METADATA_FIELD_WIDTH);
        JLabel tagLabel = new JLabel("");
        tagLabel.setForeground(TAG_RED.equals(tagColor) ? TAG_RED : TAG_GREY);
        tagLabel.setFont(tagLabel.getFont().deriveFont(Font.PLAIN, 11f));
        setHelperLabelText(tagLabel, tag);
        JLabel sourceTagLabel = new JLabel("");
        sourceTagLabel.setForeground(TAG_BLUE);
        sourceTagLabel.setFont(sourceTagLabel.getFont().deriveFont(Font.PLAIN, 11f));
        sourceTagLabel.setVisible(false);

        JPanel row = new JPanel();
        row.setOpaque(false);
        row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
        row.setBorder(BorderFactory.createEmptyBorder(0, 4, 0, 4));
        JLabel lbl = rowLabel(label);
        JPanel valueColumn = controlColumn(field);
        valueColumn.add(Box.createVerticalStrut(2));
        valueColumn.add(tagLabel);
        valueColumn.add(sourceTagLabel);
        row.add(lbl);
        row.add(Box.createHorizontalStrut(ROW_GAP));
        row.add(valueColumn);
        row.add(Box.createHorizontalGlue());
        return new MetadataFieldRow(row, field, tagLabel, sourceTagLabel);
    }

    private static JLabel rowLabel(String label) {
        JLabel lbl = new JLabel(label);
        lbl.setForeground(LABEL_COLOR);
        Dimension labelSize = new Dimension(LABEL_COLUMN_WIDTH,
                Math.max(24, lbl.getPreferredSize().height));
        lbl.setPreferredSize(labelSize);
        lbl.setMinimumSize(labelSize);
        lbl.setMaximumSize(new Dimension(LABEL_COLUMN_WIDTH, Short.MAX_VALUE));
        return lbl;
    }

    private static JPanel controlColumn(JComponent component) {
        JPanel panel = new JPanel();
        panel.setOpaque(false);
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        component.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.add(component);
        return panel;
    }

    private static void setFixedControlSize(JComponent component, int width) {
        Dimension size = new Dimension(width, 24);
        component.setPreferredSize(size);
        component.setMinimumSize(size);
        component.setMaximumSize(size);
    }

    private static void setHelperLabelText(JLabel label, String text) {
        if (label == null) return;
        String trimmed = text == null ? "" : text.trim();
        if (trimmed.isEmpty()) {
            label.setText("");
            label.setVisible(false);
            return;
        }
        label.setText("<html><body width='" + HELPER_COLUMN_WIDTH + "'>"
                + htmlText(trimmed) + "</body></html>");
        label.setVisible(true);
    }

    private static String htmlText(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }

    private static ListCellRenderer enumRenderer() {
        return new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(javax.swing.JList list, Object value, int index,
                                                          boolean isSelected, boolean cellHasFocus) {
                super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                if (value instanceof PsfModel) {
                    setText(((PsfModel) value).displayName());
                } else if (value instanceof ScopeModality) {
                    setText(((ScopeModality) value).displayName());
                }
                return this;
            }
        };
    }

    private static boolean hasAllWavelengths(double[] wavelengths, int channelCount) {
        if (wavelengths == null || wavelengths.length < channelCount) return false;
        for (int i = 0; i < channelCount; i++) {
            if (Double.isNaN(wavelengths[i]) || wavelengths[i] <= 0.0) return false;
        }
        return true;
    }

    private static String joinWavelengths(double[] wavelengths, int channelCount) {
        double[] source = wavelengths == null ? new double[0] : wavelengths;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < channelCount; i++) {
            if (i > 0) sb.append(", ");
            double value = i < source.length ? source[i] : Double.NaN;
            if (Double.isNaN(value) || value <= 0.0) sb.append("");
            else sb.append(String.format(Locale.ROOT, "%.0f", value));
        }
        return sb.toString();
    }

    private static double[] parseWavelengths(String raw, int channelCount) {
        double[] values = new double[channelCount];
        Arrays.fill(values, Double.NaN);
        if (raw == null || raw.trim().isEmpty()) return values;
        String[] parts = raw.split("[,\\s]+");
        for (int i = 0; i < parts.length && i < channelCount; i++) {
            try {
                values[i] = Double.parseDouble(parts[i].trim());
            } catch (NumberFormatException ignored) {
                values[i] = Double.NaN;
            }
        }
        return values;
    }

    private static double[] copyWavelengths(double[] source, int length) {
        double[] copy = new double[length];
        Arrays.fill(copy, Double.NaN);
        if (source == null) return copy;
        for (int i = 0; i < source.length && i < copy.length; i++) {
            copy[i] = source[i];
        }
        return copy;
    }

    private static Double parseNullableDouble(String raw) {
        if (raw == null || raw.trim().isEmpty()) return null;
        try {
            return Double.valueOf(Double.parseDouble(raw.trim()));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static boolean hasAnySelectedChannel(boolean[] channels) {
        if (channels == null) return false;
        for (boolean channel : channels) {
            if (channel) return true;
        }
        return false;
    }

    private static double firstPositive(Double primary, Double fallback) {
        if (primary != null && primary.doubleValue() > 0.0) return primary.doubleValue();
        if (fallback != null && fallback.doubleValue() > 0.0) return fallback.doubleValue();
        return Double.NaN;
    }

    private static String humanMiB(long bytes) {
        return String.format(Locale.ROOT, "%.1f", bytes / 1048576.0);
    }

    private static long usedHeapBytes() {
        return Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
    }

    private static void ensureDirectory(File dir) throws IOException {
        if (dir == null) return;
        IoUtils.mustMkdirs(dir);
    }

    private static void copyFile(File source, File target) throws IOException {
        if (source == null || target == null) return;
        ensureDirectory(target.getParentFile());
        Files.copy(source.toPath(), target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.COPY_ATTRIBUTES);
    }

    private static void deleteRecursively(java.nio.file.Path root) throws IOException {
        if (root == null || !Files.exists(root)) return;
        Files.walkFileTree(root, new SimpleFileVisitor<java.nio.file.Path>() {
            @Override
            public java.nio.file.FileVisitResult visitFile(java.nio.file.Path file, BasicFileAttributes attrs)
                    throws IOException {
                Files.deleteIfExists(file);
                return java.nio.file.FileVisitResult.CONTINUE;
            }

            @Override
            public java.nio.file.FileVisitResult postVisitDirectory(java.nio.file.Path dir, IOException exc)
                    throws IOException {
                Files.deleteIfExists(dir);
                return java.nio.file.FileVisitResult.CONTINUE;
            }
        });
    }

    private static void closeQuietly(ImagePlus image) {
        if (image == null) return;
        image.changes = false;
        try {
            image.close();
        } finally {
            image.flush();
        }
    }

    private static String joinList(List<String> values) {
        if (values == null || values.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (String value : values) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(value);
        }
        return sb.toString();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String extension(String name) {
        if (name == null) return "";
        int dot = name.lastIndexOf('.');
        return dot < 0 || dot == name.length() - 1
                ? ""
                : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static String selectedChannelList(String[] names, boolean[] selected) {
        List<String> values = new ArrayList<String>();
        for (int i = 0; i < names.length; i++) {
            if (selected != null && i < selected.length && selected[i]) {
                values.add(names[i]);
            }
        }
        return values.isEmpty() ? "(none)" : joinList(values);
    }

    private static final class MetadataFieldRow {
        final JPanel panel;
        final JTextField field;
        final JLabel tagLabel;
        final JLabel sourceTagLabel;

        MetadataFieldRow(JPanel panel, JTextField field, JLabel tagLabel, JLabel sourceTagLabel) {
            this.panel = panel;
            this.field = field;
            this.tagLabel = tagLabel;
            this.sourceTagLabel = sourceTagLabel;
        }
    }

    private static final class SourceTaggedRow {
        final JPanel panel;
        final JComponent component;
        final JLabel sourceTagLabel;

        SourceTaggedRow(JPanel panel, JComponent component, JLabel sourceTagLabel) {
            this.panel = panel;
            this.component = component;
            this.sourceTagLabel = sourceTagLabel;
        }
    }

    private static final class ChannelToggleRow {
        final String channelName;
        final ToggleSwitch toggle;
        final JPanel panel;

        ChannelToggleRow(String channelName, boolean selected) {
            this.channelName = channelName;
            this.toggle = new ToggleSwitch(selected);
            this.panel = labeledRow("Deconvolve " + channelName, toggle);
        }
    }

    private static final class EngineChoice {
        final DeconvolutionEngine engine;
        final boolean available;
        final String label;
        final String installUrl;

        EngineChoice(DeconvolutionEngine engine, boolean available, String label, String installUrl) {
            this.engine = engine;
            this.available = available;
            this.label = label;
            this.installUrl = installUrl;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private static final class EngineChoiceRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(javax.swing.JList list, Object value, int index,
                                                      boolean isSelected, boolean cellHasFocus) {
            super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
            if (value instanceof EngineChoice) {
                EngineChoice choice = (EngineChoice) value;
                setText(choice.label);
                if (!choice.available && !isSelected) {
                    setForeground(UIManager.getColor("Label.disabledForeground"));
                    setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
                }
            }
            return this;
        }
    }

    private static final class AlgorithmChoice {
        final Algorithm algorithm;

        AlgorithmChoice(Algorithm algorithm) {
            this.algorithm = algorithm;
        }

        @Override
        public String toString() {
            return algorithm.displayName();
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof AlgorithmChoice)) return false;
            return algorithm == ((AlgorithmChoice) other).algorithm;
        }

        @Override
        public int hashCode() {
            return algorithm.hashCode();
        }
    }

    static final class SeriesJob {
        final File sourceFile;
        final int seriesIndex;
        final String displayName;
        final String baseName;
        final MetadataDiagnostics.SeriesInfo seriesInfo;

        SeriesJob(File sourceFile, int seriesIndex, String baseName, MetadataDiagnostics.SeriesInfo seriesInfo) {
            this.sourceFile = sourceFile;
            this.seriesIndex = seriesIndex;
            this.baseName = baseName;
            this.seriesInfo = seriesInfo;
            this.displayName = ImageNameParser.buildMultiSeriesDisplayLabel(
                    sourceFile == null ? "" : sourceFile.getName(),
                    seriesInfo == null ? baseName : seriesInfo.imageName);
        }
    }

    private static final class DialogBindings {
        boolean programmaticChange = false;
        String mountingMedium = null;
        JComboBox<String> presetChoice;
        JComboBox<EngineChoice> engineChoice;
        JComboBox<AlgorithmChoice> algorithmChoice;
        JComboBox<PsfModel> psfChoice;
        JComboBox<ScopeModality> modalityChoice;
        JTextField pinholeField;
        JSpinner iterationsSpinner;
        JSlider regularizationSlider;
        JLabel regularizationLabel;
        MetadataFieldRow sampleRiRow;
        SourceTaggedRow engineRow;
        SourceTaggedRow algorithmRow;
        SourceTaggedRow psfRow;
        SourceTaggedRow modalityRow;
        SourceTaggedRow pinholeRow;
        SourceTaggedRow iterationsRow;
        SourceTaggedRow regularizationRow;
    }

    private static final class RunSettings {
        boolean enabled;
        String engineKey;
        Algorithm algorithm;
        PsfModel psfModel;
        ScopeModality scopeModality;
        Double pinholeAiryUnits;
        Double sampleRiOverride;
        String mountingMedium;
        int iterations;
        double regularization;
        boolean strictNyquist;
        boolean useCache;
        boolean skipPreview;
        boolean[] selectedChannels;
        String[] channelNames;
        Double naOverride;
        Double immersionRiOverride;
        Double xyPixelSizeOverrideUm;
        Double zStepOverrideUm;
        double[] emissionOverridesNm;
    }

    private static final class ResolvedSeriesSettings {
        double numericalAperture;
        double immersionRi;
        double sampleRi;
        boolean sampleRiInferred;
        double xyPixelSizeUm;
        double zStepUm;
        double pinholeAiryUnits;
        double[] emissionWavelengthsNm;
    }
}
