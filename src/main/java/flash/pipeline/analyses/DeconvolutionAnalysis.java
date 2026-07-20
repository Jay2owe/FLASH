package flash.pipeline.analyses;

import flash.pipeline.bin.BinConfig;
import flash.pipeline.bin.BinConfigIO;
import flash.pipeline.bin.ChannelConfig;
import flash.pipeline.bin.ChannelConfigIO;
import flash.pipeline.cli.CLIArgumentParser;
import flash.pipeline.cli.CLIConfig;
import flash.pipeline.deconv.DeconvManifest;
import flash.pipeline.deconv.DeconvParamsHash;
import flash.pipeline.deconv.DeconvolutionChannelPublisher;
import flash.pipeline.deconv.DeconvolutionFamilyLock;
import flash.pipeline.deconv.DeconvolutionIO;
import flash.pipeline.deconv.RefractiveIndexEstimator;
import flash.pipeline.deconv.qc.DeconvPreviewDialog;
import flash.pipeline.deconv.qc.DeconvSummaryReport;
import flash.pipeline.deconv.engine.Algorithm;
import flash.pipeline.deconv.engine.DeconvParams;
import flash.pipeline.deconv.engine.DeconvSettings;
import flash.pipeline.deconv.engine.DeconvolutionEngine;
import flash.pipeline.deconv.engine.DeconvolutionException;
import flash.pipeline.deconv.engine.EdgeHandling;
import flash.pipeline.deconv.engine.EngineRegistry;
import flash.pipeline.deconv.psf.PsfCache;
import flash.pipeline.deconv.psf.PsfModel;
import flash.pipeline.deconv.psf.PsfQcWriter;
import flash.pipeline.deconv.psf.PsfSpec;
import flash.pipeline.deconv.psf.ScopeModality;
import flash.pipeline.deconv.routing.DeconvConfigBridge;
import flash.pipeline.deconv.routing.DeconvRouting;
import flash.pipeline.deconv.routing.DeconvRoutingGroup;
import flash.pipeline.deconv.preview.ChannelDeconvPreviewer;
import flash.pipeline.deconv.preview.PreviewImageSource;
import flash.pipeline.deconv.wizard.DeconvPreset;
import flash.pipeline.deconv.wizard.DeconvPresetIO;
import flash.pipeline.execution.DeconvPreflight;
import flash.pipeline.image.HeapBudget;
import flash.pipeline.image.WindowManagerLock;
import flash.pipeline.intelligence.MetadataDiagnostics;
import flash.pipeline.io.AsyncImageSaver;
import flash.pipeline.io.DeferredImageSupplier;
import flash.pipeline.io.FlashProjectLayout;
import flash.pipeline.io.ImageSourceDispatcher;
import flash.pipeline.io.IoUtils;
import flash.pipeline.io.SeriesMeta;
import flash.pipeline.naming.ImageNameParser;
import flash.pipeline.progress.AnalysisProgressReporter;
import flash.pipeline.report.QualityReport;
import flash.pipeline.runrecord.AnalysisRunContext;
import flash.pipeline.runrecord.LoadedRunParameterApplier;
import flash.pipeline.runrecord.LoadedRunParameters;
import flash.pipeline.runrecord.ParameterSnapshot;
import flash.pipeline.runrecord.RunRecordAware;
import flash.pipeline.runrecord.ui.LoadFromRunButton;
import flash.pipeline.runtime.DependencyId;
import flash.pipeline.runtime.FeatureDependencyGate;
import flash.pipeline.ui.NextStepLabels;
import flash.pipeline.ui.PipelineDialog;
import flash.pipeline.ui.ToggleSwitch;
import flash.pipeline.ui.config.ConfigQcContext;
import flash.pipeline.ui.config.ConfigQcDialog;
import flash.pipeline.ui.config.ConfigQcResult;
import flash.pipeline.ui.config.ConfigQcStage;
import flash.pipeline.ui.config.DeconvOpticsStage;
import flash.pipeline.ui.config.DeconvolutionStage;
import flash.pipeline.ui.preview.PreviewPairPanel;
import flash.pipeline.ui.variations.CropSpec;
import flash.pipeline.ui.variations.CustomCropPicker;
import flash.pipeline.ui.variations.DeconvVariationsDialog;
import flash.pipeline.ui.variations.DeconvolutionPreviewAdapter;
import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.WindowManager;
import ij.io.FileSaver;
import ij.io.Opener;
import ij.macro.Interpreter;
import ij.plugin.RGBStackMerge;
import ij.process.ImageProcessor;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JSlider;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.JTextField;
import javax.swing.ListCellRenderer;
import javax.swing.UIManager;
import javax.swing.event.ChangeListener;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.Window;
import java.io.File;
import java.io.IOException;
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
import java.util.function.Supplier;

/**
 * Standalone 3D deconvolution step that runs before downstream analyses.
 */
@SuppressWarnings("unchecked")
public class DeconvolutionAnalysis implements Analysis, RunRecordAware {

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
    private static final int MAX_PSF_SIZE_XY = 257;
    private static final int MAX_PSF_SIZE_Z = 127;
    private static final int PREVIEW_CROP_SIZE = 256;
    static final String CHANNEL_PREVIEW_READY_TEXT = "\u25CF Run Preview";
    static final String CHANNEL_PREVIEW_RUNNING_TEXT = "\u25CF Preview running...";
    static final String CHANNEL_PREVIEW_FINISHED_TEXT = "Preview finished";
    static final String CHANNEL_PREVIEW_FAILED_TEXT = "\u25CF Preview failed";

    private boolean headless = false;
    private boolean suppressDialogs = false;
    private boolean verboseLogging = false;
    private boolean skipExisting = false;
    private QualityReport qualityReport = null;
    private CLIConfig cliConfig = null;
    private AnalysisRunContext runRecordContext = null;
    private AnalysisProgressReporter deconvProgressReporter = AnalysisProgressReporter.disabled();

    private enum MergeOutcome {
        WRITTEN,
        SKIPPED_EXISTING,
        SKIPPED
    }

    enum ChannelPreviewButtonState {
        READY,
        RUNNING,
        FINISHED,
        FAILED
    }

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
    public void setRunRecordContext(AnalysisRunContext context) {
        this.runRecordContext = context;
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
            recordWarn("3D Deconvolution requires Bio-Formats metadata and image loading.");
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
                    : showStagedConfiguration(directory, channelNames, jobs, representative);
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

            if (!areSelectedChannelEnginesReady(settings)) {
                return;
            }
            // PSF synthesis is performed natively by ScalarPsfSynthesizer, so the EPFL
            // PSF Generator plugin is no longer required for deconvolution to run.

            // An accepted setup preview that still matches the final settings skips the duplicate
            // automatic pre-batch preview. Any edit after acceptance clears it, so the preview
            // reappears here.
            if (!settings.previewAccepted) {
                DeconvPreviewDialog.Decision previewDecision =
                        showPreviewBeforeBatch(directory, representative, channelNames, settings);
                if (previewDecision == DeconvPreviewDialog.Decision.RECONFIGURE) {
                    continue;
                }
                if (previewDecision == DeconvPreviewDialog.Decision.CANCEL) {
                    return;
                }
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
                String message = "Headless deconvolution needs CLI macro options. "
                        + "Run from the FLASH UI for interactive setup, or provide "
                        + "dir=[...] run_deconv deconv.enabled=true ...";
                IJ.log("[" + TITLE + "] " + message);
                recordWarn(message);
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
        fillUniformPerChannel(settings);
        applyPerChannelOverrides(settings, deconv.getPerChannel());
        return settings;
    }

    /** Apply {@code deconv.ch<N>.*} CLI overrides on top of the uniform per-channel defaults. */
    private static void applyPerChannelOverrides(RunSettings settings,
            java.util.Map<Integer, CLIConfig.DeconvConfig.ChannelOverride> overrides) {
        if (overrides == null || overrides.isEmpty() || settings.perChannel == null) {
            return;
        }
        for (java.util.Map.Entry<Integer, CLIConfig.DeconvConfig.ChannelOverride> entry
                : overrides.entrySet()) {
            int index = entry.getKey().intValue();
            if (index < 0 || index >= settings.perChannel.length) {
                continue;
            }
            CLIConfig.DeconvConfig.ChannelOverride override = entry.getValue();
            DeconvSettings ds = settings.perChannel[index];
            if (override.getEngine() != null) {
                ds = ds.withEngineKey(override.getEngine());
            }
            if (override.getAlgorithm() != null) {
                ds = ds.withAlgorithm(override.getAlgorithm());
            }
            if (override.getPsfModel() != null) {
                ds = ds.withPsfModel(override.getPsfModel());
            }
            if (override.getIterations() != null) {
                ds = ds.withIterations(override.getIterations().intValue());
            }
            if (override.getRegularization() != null) {
                ds = ds.withRegularization(override.getRegularization().doubleValue());
            }
            settings.perChannel[index] = ds;
        }
    }

    /**
     * Seed {@link RunSettings#perChannel} with a uniform copy of the run-level defaults.
     * Per-channel dialogs (and per-channel CLI overrides) replace individual entries
     * afterwards; until then every channel shares the same settings.
     */
    private static void fillUniformPerChannel(RunSettings settings) {
        int count = settings.channelNames == null ? 0 : settings.channelNames.length;
        DeconvSettings base = new DeconvSettings(settings.engineKey, settings.algorithm,
                settings.psfModel, settings.iterations, settings.regularization);
        DeconvSettings[] perChannel = new DeconvSettings[count];
        for (int i = 0; i < count; i++) {
            perChannel[i] = base;
        }
        settings.perChannel = perChannel;
    }

    // ============================================================================
    // Stage 17 — persisted-config batch entry (no interactive dialog).
    // Used by the run-coordinator preflight ("Deconvolve now") and the CreateBin
    // end-of-setup offer to run the full batch straight from channel_config.json.
    // ============================================================================

    /**
     * Enumerate the project's series as lightweight preflight refs (mirror base name + raw source
     * container), matching the exact base names {@link #runBatch} writes mirrors under. Lets the
     * Stage-17 preflight check per-channel mirror freshness without opening pixels.
     */
    public List<DeconvPreflight.SeriesRef> listDeconvSeriesRefs(String directory) throws Exception {
        List<SeriesJob> jobs = listSeriesJobs(directory);
        List<DeconvPreflight.SeriesRef> refs = new ArrayList<DeconvPreflight.SeriesRef>();
        for (SeriesJob job : jobs) {
            refs.add(new DeconvPreflight.SeriesRef(job.artifactKey, job.sourceFile,
                    job.artifactIdentity));
        }
        return refs;
    }

    /**
     * Run the deconvolution batch straight from the persisted {@code channel_config.json}, with no
     * interactive dialog. Builds {@link RunSettings} via {@link DeconvConfigBridge} from the persisted
     * optics + per-channel settings + emission, treats the opted-in channels as the selected set, then
     * reuses the standard {@link #runBatch} — the same Stage-01 atomic temp-and-move mirror + manifest
     * writes. Runs Skip-Existing so a re-run with unchanged settings is a cache/manifest no-op
     * (idempotent). Headless-safe: never opens a dialog.
     *
     * @return {@code true} when a batch actually ran; {@code false} when deconvolution is not
     *         configured, no series were found, or validation blocked the run.
     */
    public boolean deconvolveFromPersistedConfig(String directory) {
        if (directory == null || directory.trim().isEmpty()) {
            return false;
        }
        if (!isBioFormatsAvailable()) {
            recordWarn("3D Deconvolution requires Bio-Formats metadata and image loading.");
            return false;
        }
        File settingsDir = FlashProjectLayout.forDirectory(directory).configurationWriteDir();
        ChannelConfig cfg = readChannelConfigQuietly(settingsDir);
        if (cfg == null || !DeconvConfigBridge.isDeconvConfigured(cfg)) {
            IJ.log("[" + TITLE + "] No persisted deconvolution configuration for "
                    + directory + "; nothing to deconvolve.");
            return false;
        }

        List<SeriesJob> jobs;
        try {
            jobs = listSeriesJobs(directory);
        } catch (Exception e) {
            showOrLogError("Could not read image metadata: " + e.getMessage());
            return false;
        }
        if (jobs.isEmpty()) {
            IJ.log("[" + TITLE + "] No deconvolution-ready image series were found in " + directory);
            return false;
        }

        SeriesJob representative = jobs.get(0);
        String[] channelNames = resolveChannelNames(directory, representative.seriesInfo);
        if (channelNames.length == 0) {
            IJ.log("[" + TITLE + "] No channels were detected for deconvolution.");
            return false;
        }

        RunSettings settings = buildSettingsFromPersistedConfig(cfg, channelNames, representative);
        if (settings == null || !settings.enabled || !hasAnySelectedChannel(settings.selectedChannels)) {
            IJ.log("[" + TITLE + "] Persisted deconvolution configuration selects no channels; skipping.");
            return false;
        }

        List<String> validationErrors = validateRequiredFields(representative.seriesInfo, settings);
        if (!validationErrors.isEmpty()) {
            showValidationErrors(validationErrors);
            return false;
        }
        if (!areSelectedChannelEnginesReady(settings)) {
            return false;
        }

        boolean previousSkipExisting = skipExisting;
        skipExisting = true; // idempotent: a fresh mirror (params + source match) is a manifest no-op
        try {
            addReportSection(settings, channelNames);
            runBatch(directory, jobs, channelNames, settings);
        } finally {
            skipExisting = previousSkipExisting;
        }
        return true;
    }

    /**
     * Build a headless {@link RunSettings} from the persisted lenient config (Stage 03/04): shared
     * optics from {@code deconvOptics} (metadata fallback for anything absent), per-channel engine/
     * algorithm/iterations/regularization + emission from {@link DeconvConfigBridge#settingsFor}, and
     * the opted-in channels as the selected set. Mirrors {@link #hydrateStandaloneDeconvFromConfig}'s
     * read of the same persisted fields but produces a standalone batch skeleton (no dialog stores).
     */
    private RunSettings buildSettingsFromPersistedConfig(ChannelConfig cfg, String[] channelNames,
                                                         SeriesJob representative) {
        if (cfg == null || channelNames == null || representative == null) {
            return null;
        }
        MetadataDiagnostics.SeriesInfo info = representative.seriesInfo;
        RunSettings settings = new RunSettings();
        settings.enabled = true;
        settings.channelNames = channelNames;

        // Run-level engine defaults (conservative; per-channel settings below are authoritative).
        settings.engineKey = defaultEngineKey();
        DeconvolutionEngine engine = resolveEngine(settings.engineKey);
        settings.algorithm = defaultAlgorithm(engine);
        settings.psfModel = PsfModel.GIBSON_LANNI;
        settings.iterations = DeconvParams.DEFAULT_ITERATIONS;
        settings.regularization = DeconvParams.DEFAULT_REGULARIZATION;

        // Shared optics: persisted root block, with metadata fallback for anything absent. Honour a
        // persisted scope modality only when it names a real one (else keep the metadata guess).
        ScopeModality persistedModality = parseScopeModalityToken(
                cfg.deconvOptics == null ? null : cfg.deconvOptics.scopeModality);
        settings.scopeModality = persistedModality != null
                ? persistedModality : defaultScopeModality(info);
        if (cfg.deconvOptics != null) {
            settings.naOverride = cfg.deconvOptics.na;
            settings.immersionRiOverride = cfg.deconvOptics.immersionRi;
            settings.sampleRiOverride = cfg.deconvOptics.sampleRi;
            settings.pinholeAiryUnits = cfg.deconvOptics.pinholeAiryUnits;
        }
        if (settings.naOverride == null) {
            settings.naOverride = info.objectiveNA;
        }
        if (settings.immersionRiOverride == null) {
            settings.immersionRiOverride = info.objectiveImmersion == null
                    ? null
                    : Double.valueOf(RefractiveIndexEstimator.immersionRI(info.objectiveImmersion));
        }
        settings.xyPixelSizeOverrideUm = info.pixelSizeXUm;
        settings.zStepOverrideUm = info.pixelSizeZUm;

        // Selected channels = the opted-in set; per-channel settings + emission from the config.
        settings.selectedChannels = new boolean[channelNames.length];
        settings.emissionOverridesNm = info.emissionWavelengthNm == null
                ? new double[channelNames.length]
                : copyWavelengths(info.emissionWavelengthNm, channelNames.length);
        fillUniformPerChannel(settings);
        for (int c = 0; c < channelNames.length; c++) {
            ChannelConfig.Channel channel = (cfg.channels != null && c < cfg.channels.size())
                    ? cfg.channels.get(c) : null;
            if (!DeconvConfigBridge.isChannelDeconvOptedIn(channel)) {
                continue;
            }
            settings.selectedChannels[c] = true;
            DeconvSettings ds = DeconvConfigBridge.settingsFor(cfg, c);
            if (ds != null && settings.perChannel != null && c < settings.perChannel.length) {
                settings.perChannel[c] = ds;
            }
            if (channel.emissionWavelengthNm != null
                    && isPositiveFinite(channel.emissionWavelengthNm.doubleValue())
                    && settings.emissionOverridesNm != null
                    && c < settings.emissionOverridesNm.length) {
                settings.emissionOverridesNm[c] = channel.emissionWavelengthNm.doubleValue();
            }
        }

        // Seed run-level engine defaults from the first opted-in channel so header logging and the
        // resolveEngine(settings.engineKey) default path reflect the real engine in use.
        for (int c = 0; c < settings.selectedChannels.length; c++) {
            if (settings.selectedChannels[c] && settings.perChannel != null
                    && c < settings.perChannel.length && settings.perChannel[c] != null) {
                DeconvSettings ds = settings.perChannel[c];
                settings.engineKey = ds.engineKey();
                settings.algorithm = ds.algorithm();
                settings.psfModel = ds.psfModel();
                settings.iterations = ds.iterations();
                settings.regularization = ds.regularization();
                break;
            }
        }
        return settings;
    }

    // ============================================================================
    // Per-channel staged configuration flow:
    //   Stage 1  shared acquisition optics + channel selection + run options
    //   Stage 2  which images to preview/tune on
    //   Stage 3  sequential per-channel dialogs (engine/algorithm/iterations/
    //            regularization/PSF/wavelength) with raw|deconvolved preview and a
    //            parameter-variations grid button.
    // ============================================================================

    private enum PerChannelNav { NEXT, BACK, CANCEL }

    private static final class SharedAcquisitionSettings {
        Double naOverride;
        Double immersionRiOverride;
        Double sampleRiOverride;
        Double xyPixelSizeOverrideUm;
        Double zStepOverrideUm;
        ScopeModality scopeModality;
        Double pinholeAiryUnits;
        boolean[] selectedChannels;
        boolean strictNyquist;
        boolean useCache;
    }

    /**
     * Interactive standalone setup, rebuilt (Stage 09) on the SAME shared surfaces the Set Up
     * Configuration deconvolution flow uses: a trimmed channel/run-options card, then a single
     * {@link DeconvOpticsStage} plus one {@link DeconvolutionStage} per selected channel hosted in a
     * {@link ConfigQcDialog} ("look == setup"). Confirmed optics + per-channel settings + routing are
     * persisted into {@code channel_config.json} (read-modify-write) so a re-open pre-fills them.
     *
     * <p>The headless/CLI path ({@link #buildHeadlessSettings}) and {@link #runBatch} are untouched;
     * only this interactive path changed.</p>
     */
    private RunSettings showStagedConfiguration(String directory,
                                                String[] channelNames,
                                                List<SeriesJob> jobs,
                                                SeriesJob representative) {
        ChannelSelectionCard card = showChannelSelectionCard(
                channelNames, jobs != null && jobs.size() > 1);
        if (card == null) {
            return null;
        }

        List<Integer> selectedIdx = new ArrayList<Integer>();
        for (int i = 0; i < channelNames.length; i++) {
            if (i < card.selectedChannels.length && card.selectedChannels[i]) {
                selectedIdx.add(Integer.valueOf(i));
            }
        }
        if (selectedIdx.isEmpty()) {
            showOrLogError("Select at least one channel to deconvolve.");
            return null;
        }

        List<SeriesJob> previewJobs = selectPreviewJobs(jobs);
        if (previewJobs == null || previewJobs.isEmpty()) {
            return null;
        }
        SeriesJob previewJob = previewJobs.get(0);

        // ---- RunSettings skeleton (optics seeded from metadata; the optics stage fills nulls) ----
        MetadataDiagnostics.SeriesInfo info = representative.seriesInfo;
        RunSettings settings = new RunSettings();
        settings.enabled = true;
        settings.channelNames = channelNames;
        settings.selectedChannels = card.selectedChannels;
        settings.strictNyquist = card.strictNyquist;
        settings.useCache = card.useCache;
        settings.naOverride = info == null ? null : info.objectiveNA;
        settings.immersionRiOverride = (info == null || info.objectiveImmersion == null)
                ? null
                : Double.valueOf(RefractiveIndexEstimator.immersionRI(info.objectiveImmersion));
        double inferredSampleRi = info == null
                ? Double.NaN
                : (info.sampleRefractiveIndex == null
                        ? RefractiveIndexEstimator.inferSampleRI(info.objectiveImmersion, null)
                        : info.sampleRefractiveIndex.doubleValue());
        settings.sampleRiOverride = isPositiveFinite(inferredSampleRi)
                ? Double.valueOf(inferredSampleRi) : null;
        settings.xyPixelSizeOverrideUm = info == null ? null : info.pixelSizeXUm;
        settings.zStepOverrideUm = info == null ? null : info.pixelSizeZUm;
        settings.scopeModality = defaultScopeModality(info);
        settings.pinholeAiryUnits = Double.valueOf(1.0);
        settings.emissionOverridesNm = (info == null || info.emissionWavelengthNm == null)
                ? new double[channelNames.length]
                : copyWavelengths(info.emissionWavelengthNm, channelNames.length);

        // Seed per-channel settings uniformly (mirrors runPerChannelDialogs).
        if (settings.perChannel == null || settings.perChannel.length != channelNames.length) {
            settings.perChannel = new DeconvSettings[channelNames.length];
        }
        DeconvSettings seed = new DeconvSettings(defaultEngineKey(),
                defaultAlgorithm(resolveEngine(defaultEngineKey())),
                PsfModel.GIBSON_LANNI, 15, 0.01d);
        for (int i = 0; i < channelNames.length; i++) {
            if (settings.perChannel[i] == null) {
                settings.perChannel[i] = seed;
            }
        }

        // Per-channel routing vectors (default both groups = deconvolved for opted-in channels).
        boolean[] routeAnalysis = new boolean[channelNames.length];
        boolean[] routeDisplay = new boolean[channelNames.length];
        Arrays.fill(routeAnalysis, true);
        Arrays.fill(routeDisplay, true);

        // ---- Shared stages: optics once, then one deconvolution stage per selected channel -------
        List<ConfigQcStage> stages = new ArrayList<ConfigQcStage>();
        stages.add(new DeconvOpticsStage(
                new StandaloneOpticsStore(settings),
                new StandaloneOpticsSupport(directory, previewJob, channelNames, selectedIdx, settings)));
        for (int k = 0; k < selectedIdx.size(); k++) {
            int realIdx = selectedIdx.get(k).intValue();
            stages.add(new DeconvolutionStage(
                    new StandaloneDeconvStore(settings, realIdx, routeAnalysis, routeDisplay),
                    new StandaloneDeconvPreviewSource(directory, previewJob, channelNames,
                            settings, realIdx),
                    realIdx, channelNames[realIdx]));
        }

        // ---- Render host (same ConfigQcDialog the setup flow uses) --------------------------------
        File projectDir = new File(directory);
        File settingsDir = FlashProjectLayout.forDirectory(directory).configurationWriteDir();
        ChannelConfig existingConfig = readChannelConfigQuietly(settingsDir);
        // Pre-fill saved optics / per-channel settings / routing so re-opening shows what was saved
        // (the stores read these live), instead of overwriting them with metadata defaults.
        hydrateStandaloneDeconvFromConfig(existingConfig, settings, selectedIdx, routeAnalysis, routeDisplay);
        List<ConfigQcContext.ConfigQcImage> contextImages =
                new ArrayList<ConfigQcContext.ConfigQcImage>();
        contextImages.add(new ConfigQcContext.ConfigQcImage(
                previewJob.seriesIndex, previewJob.displayName, null));
        ConfigQcContext context = new ConfigQcContext(projectDir, settingsDir, existingConfig,
                contextImages, Arrays.asList(channelNames), selectedIdx.get(0).intValue());

        ConfigQcResult result = ConfigQcDialog.createModal(null, context, stages).showDialog();
        if (result != ConfigQcResult.DONE) {
            return null;
        }

        // ---- Persist confirmed optics + per-channel settings + routing (read-modify-write) -------
        // Only when the confirmed settings pass the same required-field gate execute() applies, so an
        // incomplete opt-in (e.g. a still-missing emission wavelength) is never written to
        // channel_config.json; execute() then surfaces the same validation errors below.
        if (validateRequiredFields(representative.seriesInfo, settings).isEmpty()) {
            persistDeconvConfig(settingsDir, channelNames, settings, selectedIdx, routeAnalysis, routeDisplay);
        }

        // ---- Derive run-level representative settings; return -------------------------------------
        int first = firstSelectedChannel(settings.selectedChannels);
        DeconvSettings representativeSettings = settings.channel(first < 0 ? 0 : first);
        settings.engineKey = representativeSettings.engineKey();
        settings.algorithm = representativeSettings.algorithm();
        settings.psfModel = representativeSettings.psfModel();
        settings.iterations = representativeSettings.iterations();
        settings.regularization = representativeSettings.regularization();
        // Per-channel previews were already shown on the shared stages, so skip the legacy single
        // pre-batch preview in execute().
        settings.previewAccepted = true;
        settings.skipPreview = false;
        return settings;
    }

    /** Result of the trimmed standalone card: which channels to deconvolve + the two run options. */
    private static final class ChannelSelectionCard {
        boolean[] selectedChannels;
        boolean strictNyquist;
        boolean useCache;
    }

    /**
     * Trimmed standalone card (a {@link PipelineDialog}): channel selection + Strict-Nyquist / reuse-
     * cache run options. The optics fields that the old shared-acquisition dialog collected now live
     * in {@link DeconvOpticsStage}, so they are intentionally absent here.
     */
    private ChannelSelectionCard showChannelSelectionCard(String[] channelNames,
                                                          boolean choosePreviewImagesNext) {
        PipelineDialog dialog = new PipelineDialog(TITLE, PipelineDialog.Phase.SETUP);
        dialog.setWorkflowTracker(new String[]{"Channels", "Image", "Deconvolve"}, 0);
        dialog.setPrimaryButtonText(
                NextStepLabels.deconvolutionAcquisitionPrimaryLabel(choosePreviewImagesNext));
        JButton helpButton = new JButton("?");
        styleHelpButton(helpButton);
        helpButton.setToolTipText("Explain every 3D Deconvolution option.");
        helpButton.addActionListener(e -> dialog.runChildWorkflow(new Runnable() {
            @Override public void run() {
                showDeconvolutionHelpDialog();
            }
        }));
        dialog.setNorthSlot(topHelpRow(helpButton, null));

        dialog.addHeader("Channels to deconvolve");
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

        dialog.addHeader("Run options");
        final ToggleSwitch strictNyquistToggle = new ToggleSwitch(false);
        dialog.addComponent(labeledRow("Strict Nyquist", strictNyquistToggle));
        final ToggleSwitch useCacheToggle = new ToggleSwitch(true);
        dialog.addComponent(labeledRow("Reuse matching cached outputs", useCacheToggle));

        if (!dialog.showDialog()) {
            return null;
        }

        ChannelSelectionCard card = new ChannelSelectionCard();
        card.selectedChannels = new boolean[channelRows.size()];
        for (int i = 0; i < channelRows.size(); i++) {
            card.selectedChannels[i] = channelRows.get(i).toggle.isSelected();
        }
        card.strictNyquist = strictNyquistToggle.isSelected();
        card.useCache = useCacheToggle.isSelected();
        return card;
    }

    /** Metadata-derived optics defaults shared by seeding and {@code OpticsSupport.metadataDefaults}. */
    private DeconvOpticsStage.Value metadataOpticsDefaults(MetadataDiagnostics.SeriesInfo info,
                                                           int selectedCount) {
        Double na = info == null ? null : info.objectiveNA;
        Double immersion = (info == null || info.objectiveImmersion == null)
                ? null
                : Double.valueOf(RefractiveIndexEstimator.immersionRI(info.objectiveImmersion));
        double sampleRi = info == null
                ? Double.NaN
                : (info.sampleRefractiveIndex == null
                        ? RefractiveIndexEstimator.inferSampleRI(info.objectiveImmersion, null)
                        : info.sampleRefractiveIndex.doubleValue());
        Double sample = isPositiveFinite(sampleRi) ? Double.valueOf(sampleRi) : null;
        ScopeModality modality = defaultScopeModality(info);
        boolean[] all = new boolean[Math.max(0, selectedCount)];
        Arrays.fill(all, true);
        return new DeconvOpticsStage.Value(na, immersion, sample, Double.valueOf(1.0), modality, all);
    }

    private static int countSelectedChannels(boolean[] selected) {
        int count = 0;
        if (selected != null) {
            for (int i = 0; i < selected.length; i++) {
                if (selected[i]) {
                    count++;
                }
            }
        }
        return count;
    }

    private static ChannelConfig readChannelConfigQuietly(File settingsDir) {
        try {
            return ChannelConfigIO.read(settingsDir);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Pre-fill the standalone {@link RunSettings} skeleton + routing vectors from an existing
     * {@code channel_config.json} so re-opening the standalone deconvolution flow shows the saved
     * optics / per-channel settings / emission / routing instead of the metadata-seeded defaults.
     * The stores hold a live reference to {@code settings}/{@code routeAnalysis}/{@code routeDisplay},
     * so mutating them here (before the dialog opens) is what the stages read on entry. Only opted-in
     * selected channels are hydrated; an unset persisted field leaves the metadata default intact.
     */
    private void hydrateStandaloneDeconvFromConfig(ChannelConfig cfg, RunSettings settings,
                                                   List<Integer> selectedIdx,
                                                   boolean[] routeAnalysis, boolean[] routeDisplay) {
        if (cfg == null || !DeconvConfigBridge.isDeconvConfigured(cfg)) {
            return;
        }
        if (cfg.deconvOptics != null) {
            ChannelConfig.DeconvOptics optics = cfg.deconvOptics;
            if (optics.na != null) settings.naOverride = optics.na;
            if (optics.immersionRi != null) settings.immersionRiOverride = optics.immersionRi;
            if (optics.sampleRi != null) settings.sampleRiOverride = optics.sampleRi;
            if (optics.pinholeAiryUnits != null) settings.pinholeAiryUnits = optics.pinholeAiryUnits;
            // Only override the metadata-inferred modality when the persisted token names a real one;
            // modalityFrom() would otherwise default to WIDEFIELD and clobber a CONFOCAL/SPINNING_DISK
            // guess for a config saved without (or with an unknown) scopeModality.
            ScopeModality persistedModality = parseScopeModalityToken(optics.scopeModality);
            if (persistedModality != null) {
                settings.scopeModality = persistedModality;
            }
        }
        DeconvRouting routing = DeconvConfigBridge.routingFrom(cfg);
        for (int k = 0; k < selectedIdx.size(); k++) {
            int idx = selectedIdx.get(k).intValue();
            ChannelConfig.Channel channel = (cfg.channels != null && idx >= 0 && idx < cfg.channels.size())
                    ? cfg.channels.get(idx) : null;
            if (!DeconvConfigBridge.isChannelDeconvOptedIn(channel)) {
                continue;
            }
            DeconvSettings ds = DeconvConfigBridge.settingsFor(cfg, idx);
            if (ds != null && settings.perChannel != null
                    && idx >= 0 && idx < settings.perChannel.length) {
                settings.perChannel[idx] = ds;
            }
            if (channel.emissionWavelengthNm != null
                    && isPositiveFinite(channel.emissionWavelengthNm.doubleValue())
                    && settings.emissionOverridesNm != null
                    && idx >= 0 && idx < settings.emissionOverridesNm.length) {
                settings.emissionOverridesNm[idx] = channel.emissionWavelengthNm.doubleValue();
            }
            if (idx >= 0 && idx < routeAnalysis.length) {
                routeAnalysis[idx] = routing.usesDeconv(DeconvRoutingGroup.ANALYSIS, idx);
            }
            if (idx >= 0 && idx < routeDisplay.length) {
                routeDisplay[idx] = routing.usesDeconv(DeconvRoutingGroup.DISPLAY, idx);
            }
        }
    }

    /** Parse a persisted scope-modality token to a real {@link ScopeModality}, or null if absent/unknown. */
    private static ScopeModality parseScopeModalityToken(String token) {
        if (token == null || token.trim().isEmpty()) {
            return null;
        }
        try {
            return ScopeModality.valueOf(token.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Persist the confirmed optics + per-channel deconvolution settings + two-group routing into
     * {@code channel_config.json} via read-modify-write, for exactly the selected channels. Never
     * builds the config from a {@link BinConfig}; both routes default to {@code "deconv"} for an
     * opted-in channel. Best-effort: a failure is logged and does not abort the run.
     */
    private void persistDeconvConfig(File settingsDir, String[] channelNames, RunSettings settings,
                                     List<Integer> selectedIdx,
                                     boolean[] routeAnalysis, boolean[] routeDisplay) {
        try {
            ChannelConfig cfg = readChannelConfigQuietly(settingsDir);
            if (cfg == null) {
                cfg = new ChannelConfig();
                cfg.writerId = "FLASH";
            }
            if (cfg.channels == null) {
                cfg.channels = new ArrayList<ChannelConfig.Channel>();
            }
            for (int i = cfg.channels.size(); i < channelNames.length; i++) {
                ChannelConfig.Channel created = new ChannelConfig.Channel();
                created.index = i;
                created.name = channelNames[i];
                cfg.channels.add(created);
            }

            ChannelConfig.DeconvOptics optics = cfg.deconvOptics == null
                    ? new ChannelConfig.DeconvOptics() : cfg.deconvOptics;
            optics.na = settings.naOverride;
            optics.immersionRi = settings.immersionRiOverride;
            optics.sampleRi = settings.sampleRiOverride;
            optics.scopeModality = settings.scopeModality == null ? null : settings.scopeModality.name();
            optics.pinholeAiryUnits = settings.pinholeAiryUnits;
            cfg.deconvOptics = optics;

            for (int k = 0; k < selectedIdx.size(); k++) {
                int idx = selectedIdx.get(k).intValue();
                if (idx < 0 || idx >= cfg.channels.size()) {
                    continue;
                }
                ChannelConfig.Channel channel = cfg.channels.get(idx);
                if (channel == null) {
                    continue;
                }
                DeconvSettings ds = settings.channel(idx);
                channel.deconvEngineKey = ds.engineKey();
                channel.deconvAlgorithm = ds.algorithm() == null ? null : ds.algorithm().name();
                channel.deconvPsfModel = ds.psfModel() == null ? null : ds.psfModel().name();
                channel.deconvIterations = Integer.valueOf(ds.iterations());
                channel.deconvRegularization = Double.valueOf(ds.regularization());
                double wl = settings.emissionOverridesNm != null
                        && idx < settings.emissionOverridesNm.length
                        ? settings.emissionOverridesNm[idx] : Double.NaN;
                if (isPositiveFinite(wl)) {
                    channel.emissionWavelengthNm = Double.valueOf(wl);
                }
                channel.routeAnalysis = (idx < routeAnalysis.length && routeAnalysis[idx])
                        ? "deconv" : "raw";
                channel.routeDisplay = (idx < routeDisplay.length && routeDisplay[idx])
                        ? "deconv" : "raw";
            }

            cfg.writtenAtMillis = System.currentTimeMillis();
            ChannelConfigIO.write(settingsDir, cfg);
        } catch (Exception e) {
            IJ.log("[" + TITLE + "] Could not persist deconvolution settings: " + e.getMessage());
        }
    }

    private static String rootMessageOf(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.trim().isEmpty()
                ? current.getClass().getSimpleName() : message;
    }

    /** {@link DeconvOpticsStage.OpticsStore} backed by the standalone {@link RunSettings}. */
    private final class StandaloneOpticsStore implements DeconvOpticsStage.OpticsStore {
        private final RunSettings settings;

        StandaloneOpticsStore(RunSettings settings) {
            this.settings = settings;
        }

        @Override public DeconvOpticsStage.Value get() {
            int selectedCount = countSelectedChannels(settings.selectedChannels);
            boolean[] all = new boolean[selectedCount];
            Arrays.fill(all, true);
            return new DeconvOpticsStage.Value(settings.naOverride, settings.immersionRiOverride,
                    settings.sampleRiOverride, settings.pinholeAiryUnits, settings.scopeModality, all);
        }

        @Override public void set(DeconvOpticsStage.Value value) {
            if (value == null) {
                return;
            }
            settings.naOverride = value.na;
            settings.immersionRiOverride = value.immersionRi;
            settings.sampleRiOverride = value.sampleRi;
            settings.pinholeAiryUnits = value.pinholeAiryUnits;
            if (value.modality != null) {
                settings.scopeModality = value.modality;
            }
            // value.selectedChannels is ignored: the card is authoritative for channel selection.
        }
    }

    /** {@link DeconvOpticsStage.OpticsSupport} for the standalone preview job. */
    private final class StandaloneOpticsSupport implements DeconvOpticsStage.OpticsSupport {
        private final String directory;
        private final SeriesJob previewJob;
        private final String[] channelNames;
        private final List<Integer> selectedIdx;
        private final RunSettings settings;

        StandaloneOpticsSupport(String directory, SeriesJob previewJob, String[] channelNames,
                                List<Integer> selectedIdx, RunSettings settings) {
            this.directory = directory;
            this.previewJob = previewJob;
            this.channelNames = channelNames;
            this.selectedIdx = selectedIdx;
            this.settings = settings;
        }

        @Override public String[] channelNames() {
            String[] names = new String[selectedIdx.size()];
            for (int i = 0; i < selectedIdx.size(); i++) {
                int idx = selectedIdx.get(i).intValue();
                names[i] = idx >= 0 && idx < channelNames.length
                        ? channelNames[idx] : ("Channel " + (idx + 1));
            }
            return names;
        }

        @Override public DeconvOpticsStage.Value metadataDefaults() {
            return metadataOpticsDefaults(previewJob.seriesInfo, selectedIdx.size());
        }

        @Override public String readOnlyGeometrySummary() {
            MetadataDiagnostics.SeriesInfo info = previewJob.seriesInfo;
            String xy = info != null && info.pixelSizeXUm != null
                    ? DeconvolutionIO.formatDouble(info.pixelSizeXUm.doubleValue()) : "?";
            String z = info != null && info.pixelSizeZUm != null
                    ? DeconvolutionIO.formatDouble(info.pixelSizeZUm.doubleValue()) : "?";
            return "XY pixel " + xy + " um / Z-step " + z + " um (from metadata)";
        }

        @Override public ImagePlus representativePreview() {
            return null;
        }

        @Override public String validate(DeconvOpticsStage.Value value) {
            if (value == null) {
                return "Enter the microscope and sample optics before continuing.";
            }
            int firstReal = selectedIdx.isEmpty() ? 0 : selectedIdx.get(0).intValue();
            WindowManagerLock.LOCK.lock();
            try {
                int[] before = snapshotOpenImageWindows();
                boolean previousBatchMode = Interpreter.batchMode;
                Interpreter.batchMode = true;
                ImagePlus rawCrop = null;
                try {
                    ImagePlus fullChannel = openSeriesChannel(directory, previewJob.seriesIndex, firstReal);
                    if (fullChannel == null) {
                        return "Could not open a preview crop to check the optics.";
                    }
                    try {
                        rawCrop = cropCenterStack(fullChannel, PREVIEW_CROP_SIZE, PREVIEW_CROP_SIZE, "Original");
                    } finally {
                        closeQuietly(fullChannel);
                    }
                    if (rawCrop == null) {
                        return "Could not open a preview crop to check the optics.";
                    }
                    ResolvedSeriesSettings resolved = resolvedOpticsForValidation(
                            value, previewJob.seriesInfo, channelNames.length, firstReal);
                    createPsfSpec(resolved, firstReal, rawCrop, value.modality);
                    return null;
                } catch (Exception e) {
                    return rootMessageOf(e);
                } finally {
                    closeQuietly(rawCrop);
                    Interpreter.batchMode = previousBatchMode;
                    closeStrayPreviewWindows(before);
                }
            } finally {
                WindowManagerLock.LOCK.unlock();
            }
        }

        @Override public void close(ImagePlus image) {
            closeQuietly(image);
        }

        private ResolvedSeriesSettings resolvedOpticsForValidation(DeconvOpticsStage.Value value,
                MetadataDiagnostics.SeriesInfo info, int channelCount, int firstReal) {
            RunSettings temp = new RunSettings();
            temp.naOverride = value.na;
            temp.immersionRiOverride = value.immersionRi;
            temp.sampleRiOverride = value.sampleRi;
            temp.pinholeAiryUnits = value.pinholeAiryUnits;
            temp.scopeModality = value.modality;
            temp.xyPixelSizeOverrideUm = info == null ? null : info.pixelSizeXUm;
            temp.zStepOverrideUm = info == null ? null : info.pixelSizeZUm;
            double repWavelength = representativeWavelengthNm(info, firstReal);
            double[] wavelengths = new double[Math.max(channelCount, firstReal + 1)];
            Arrays.fill(wavelengths, repWavelength);
            temp.emissionOverridesNm = wavelengths;
            temp.channelNames = channelNames;
            temp.selectedChannels = settings.selectedChannels;
            return resolveSeriesSettings(info, temp, channelCount);
        }

        private double representativeWavelengthNm(MetadataDiagnostics.SeriesInfo info, int firstReal) {
            if (info != null && info.emissionWavelengthNm != null
                    && firstReal >= 0 && firstReal < info.emissionWavelengthNm.length
                    && isPositiveFinite(info.emissionWavelengthNm[firstReal])) {
                return info.emissionWavelengthNm[firstReal];
            }
            if (settings.emissionOverridesNm != null
                    && firstReal >= 0 && firstReal < settings.emissionOverridesNm.length
                    && isPositiveFinite(settings.emissionOverridesNm[firstReal])) {
                return settings.emissionOverridesNm[firstReal];
            }
            return 500.0;
        }
    }

    /** Per-channel {@link DeconvolutionStage.DeconvStore} backed by the standalone {@link RunSettings}. */
    private final class StandaloneDeconvStore implements DeconvolutionStage.DeconvStore {
        private final RunSettings settings;
        private final int channelIndex;
        private final boolean[] routeAnalysis;
        private final boolean[] routeDisplay;

        StandaloneDeconvStore(RunSettings settings, int channelIndex,
                              boolean[] routeAnalysis, boolean[] routeDisplay) {
            this.settings = settings;
            this.channelIndex = channelIndex;
            this.routeAnalysis = routeAnalysis;
            this.routeDisplay = routeDisplay;
        }

        @Override public DeconvolutionStage.Value get() {
            DeconvSettings ds = settings.channel(channelIndex);
            double wl = settings.emissionOverridesNm != null
                    && channelIndex < settings.emissionOverridesNm.length
                    ? settings.emissionOverridesNm[channelIndex] : Double.NaN;
            Double wavelength = isPositiveFinite(wl) ? Double.valueOf(wl) : null;
            boolean analysis = channelIndex >= 0 && channelIndex < routeAnalysis.length
                    ? routeAnalysis[channelIndex] : true;
            boolean display = channelIndex >= 0 && channelIndex < routeDisplay.length
                    ? routeDisplay[channelIndex] : true;
            // Strict-Nyquist and cache are run-level in the standalone flow; surface the run-level
            // values in the per-channel advanced controls so the card and the stage never disagree.
            return new DeconvolutionStage.Value(ds, wavelength, analysis, display,
                    DeconvParams.DEFAULT_EDGE_HANDLING, settings.strictNyquist, settings.useCache);
        }

        @Override public void set(DeconvolutionStage.Value value) {
            if (value == null) {
                return;
            }
            if (value.settings != null && settings.perChannel != null
                    && channelIndex >= 0 && channelIndex < settings.perChannel.length) {
                settings.perChannel[channelIndex] = value.settings;
            }
            if (value.emissionWavelengthNm != null && settings.emissionOverridesNm != null
                    && channelIndex >= 0 && channelIndex < settings.emissionOverridesNm.length) {
                settings.emissionOverridesNm[channelIndex] = value.emissionWavelengthNm.doubleValue();
            }
            if (channelIndex >= 0 && channelIndex < routeAnalysis.length) {
                routeAnalysis[channelIndex] = value.useDeconvAnalysis;
            }
            if (channelIndex >= 0 && channelIndex < routeDisplay.length) {
                routeDisplay[channelIndex] = value.useDeconvDisplay;
            }
            // The standalone flow keeps strict-Nyquist and cache run-level (used by runBatch); mirror
            // the per-channel advanced toggles back so the last-edited channel wins consistently.
            settings.strictNyquist = value.strictNyquist;
            settings.useCache = value.cacheDeconvolved;
        }
    }

    /**
     * Per-channel {@link DeconvolutionStage.DeconvPreviewSource} for the standalone preview job. Does
     * its own {@link WindowManagerLock}/{@link Interpreter#batchMode}/stray-window handling, mirroring
     * {@link PreviewDeconvOps} + {@link ChannelDeconvPreviewer}.
     */
    private final class StandaloneDeconvPreviewSource implements DeconvolutionStage.DeconvPreviewSource {
        private final String directory;
        private final SeriesJob previewJob;
        private final String[] channelNames;
        private final RunSettings settings;
        private final int channelIndex;
        private ImagePlus cachedRawCrop;

        StandaloneDeconvPreviewSource(String directory, SeriesJob previewJob, String[] channelNames,
                                      RunSettings settings, int channelIndex) {
            this.directory = directory;
            this.previewJob = previewJob;
            this.channelNames = channelNames;
            this.settings = settings;
            this.channelIndex = channelIndex;
        }

        @Override public ImagePlus openRawCrop() throws Exception {
            WindowManagerLock.LOCK.lock();
            try {
                int[] before = snapshotOpenImageWindows();
                boolean previousBatchMode = Interpreter.batchMode;
                Interpreter.batchMode = true;
                try {
                    ImagePlus fullChannel = openSeriesChannel(directory, previewJob.seriesIndex, channelIndex);
                    if (fullChannel == null) {
                        throw new IllegalStateException("Could not open channel for preview.");
                    }
                    ImagePlus crop;
                    try {
                        crop = cropCenterStack(fullChannel, PREVIEW_CROP_SIZE, PREVIEW_CROP_SIZE, "Original");
                    } finally {
                        closeQuietly(fullChannel);
                    }
                    cachedRawCrop = crop;
                    return crop;
                } finally {
                    Interpreter.batchMode = previousBatchMode;
                    closeStrayPreviewWindows(before);
                }
            } finally {
                WindowManagerLock.LOCK.unlock();
            }
        }

        @Override public ImagePlus deconvolveCrop(DeconvolutionStage.Value liveValue) throws Exception {
            if (liveValue == null) {
                return null;
            }
            ImagePlus raw = cachedRawCrop;
            if (raw == null) {
                raw = openRawCrop();
            }
            return deconvolveCrop(raw, liveValue);
        }

        @Override public ImagePlus deconvolveCrop(ImagePlus rawCrop,
                                                  DeconvolutionStage.Value liveValue)
                throws Exception {
            if (rawCrop == null) {
                throw new IllegalArgumentException("rawCrop must not be null");
            }
            if (liveValue == null) {
                return null;
            }
            WindowManagerLock.LOCK.lock();
            try {
                int[] before = snapshotOpenImageWindows();
                boolean previousBatchMode = Interpreter.batchMode;
                Interpreter.batchMode = true;
                try {
                    String wavelengthText = liveValue.emissionWavelengthNm == null
                            ? ""
                            : DeconvolutionIO.formatDouble(liveValue.emissionWavelengthNm.doubleValue());
                    ResolvedSeriesSettings resolved = resolvePreviewSeriesSettings(
                            previewJob.seriesInfo, settings, channelNames.length,
                            channelIndex, wavelengthText);
                    ImagePlus copy = duplicateStack(rawCrop);
                    sanitizeInputForDeconvolution(copy);
                    try {
                        return DeconvolutionAnalysis.this.deconvolveCrop(
                                copy, resolved, channelIndex, liveValue.settings, settings.scopeModality);
                    } finally {
                        closeQuietly(copy);
                    }
                } finally {
                    Interpreter.batchMode = previousBatchMode;
                    closeStrayPreviewWindows(before);
                }
            } finally {
                WindowManagerLock.LOCK.unlock();
            }
        }

        @Override public void close(ImagePlus image) {
            if (image != null && image == cachedRawCrop) {
                cachedRawCrop = null;
            }
            closeQuietly(image);
        }
    }

    private SharedAcquisitionSettings showSharedAcquisitionDialog(String directory,
                                                                 String[] channelNames,
                                                                 SeriesJob representative,
                                                                 boolean choosePreviewImagesNext) {
        MetadataDiagnostics.SeriesInfo info = representative.seriesInfo;
        PipelineDialog dialog = new PipelineDialog(TITLE, PipelineDialog.Phase.SETUP);
        dialog.setWorkflowTracker(new String[]{"Acquisition", "Images", "Channels", "Run"}, 0);
        dialog.setPrimaryButtonText(
                NextStepLabels.deconvolutionAcquisitionPrimaryLabel(choosePreviewImagesNext));
        JButton helpButton = new JButton("?");
        styleHelpButton(helpButton);
        helpButton.setToolTipText("Explain every 3D Deconvolution option.");
        helpButton.addActionListener(e -> dialog.runChildWorkflow(new Runnable() {
            @Override public void run() {
                showDeconvolutionHelpDialog();
            }
        }));
        dialog.setNorthSlot(topHelpRow(helpButton, null));

        dialog.addHeader("Microscope & Sample (shared by all channels)");
        final MetadataFieldRow xyPixelRow = metadataField("XY pixel size (um)",
                info.pixelSizeXUm == null ? "" : DeconvolutionIO.formatDouble(info.pixelSizeXUm.doubleValue()),
                info.pixelSizeXUm == null ? "Missing - please enter" : "Auto-detected",
                info.pixelSizeXUm == null ? TAG_RED : TAG_GREEN);
        final MetadataFieldRow naRow = metadataField("Numerical Aperture (NA)",
                info.objectiveNA == null ? "" : DeconvolutionIO.formatDouble(info.objectiveNA.doubleValue()),
                info.objectiveNA == null ? "Missing - please enter" : "Auto-detected",
                info.objectiveNA == null ? TAG_RED : TAG_GREEN);
        final Double immersionRi = info.objectiveImmersion == null
                ? null
                : Double.valueOf(RefractiveIndexEstimator.immersionRI(info.objectiveImmersion));
        final MetadataFieldRow immersionRow = metadataField("Immersion RI",
                immersionRi == null ? "" : DeconvolutionIO.formatDouble(immersionRi.doubleValue()),
                immersionRi == null ? "Missing - please enter" : "Auto-detected",
                immersionRi == null ? TAG_RED : TAG_GREEN);
        final double defaultSampleRi = info.sampleRefractiveIndex == null
                ? RefractiveIndexEstimator.inferSampleRI(info.objectiveImmersion, null)
                : info.sampleRefractiveIndex.doubleValue();
        final MetadataFieldRow sampleRiRow = metadataField("Sample RI",
                Double.isNaN(defaultSampleRi) ? "" : DeconvolutionIO.formatDouble(defaultSampleRi),
                info.objectiveImmersion == null ? "Editable override" : "Inferred from immersion",
                TAG_GREEN);
        final MetadataFieldRow zStepRow = metadataField("Z-step (um)",
                info.pixelSizeZUm == null ? "" : DeconvolutionIO.formatDouble(info.pixelSizeZUm.doubleValue()),
                info.pixelSizeZUm == null ? "Missing - please enter" : "Auto-detected",
                info.pixelSizeZUm == null ? TAG_RED : TAG_GREEN);
        dialog.addComponent(xyPixelRow.panel);
        dialog.addComponent(naRow.panel);
        dialog.addComponent(immersionRow.panel);
        dialog.addComponent(sampleRiRow.panel);
        dialog.addComponent(zStepRow.panel);

        final JComboBox<ScopeModality> modalityChoice = new JComboBox<ScopeModality>(ScopeModality.values());
        modalityChoice.setMaximumSize(new Dimension(220, 24));
        modalityChoice.setRenderer(enumRenderer());
        ScopeModality guessedModality = defaultScopeModality(info);
        if (guessedModality != null) {
            modalityChoice.setSelectedItem(guessedModality);
        }
        final JTextField pinholeField = new JTextField("1.0", 6);
        final SourceTaggedRow modalityRow = taggedRow("Scope modality", modalityChoice);
        final SourceTaggedRow pinholeRow = taggedRow("Pinhole (Airy units)", pinholeField);
        dialog.addComponent(modalityRow.panel);
        dialog.addComponent(pinholeRow.panel);
        Runnable refreshPinhole = new Runnable() {
            @Override public void run() {
                pinholeRow.panel.setVisible(modalityChoice.getSelectedItem() == ScopeModality.CONFOCAL);
            }
        };
        modalityChoice.addActionListener(e -> refreshPinhole.run());
        refreshPinhole.run();

        dialog.addHeader("Channels to deconvolve");
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

        dialog.addHeader("Run options");
        final ToggleSwitch strictNyquistToggle = new ToggleSwitch(false);
        dialog.addComponent(labeledRow("Strict Nyquist", strictNyquistToggle));
        final ToggleSwitch useCacheToggle = new ToggleSwitch(true);
        dialog.addComponent(labeledRow("Reuse matching cached outputs", useCacheToggle));

        if (!dialog.showDialog()) {
            return null;
        }

        SharedAcquisitionSettings shared = new SharedAcquisitionSettings();
        shared.naOverride = parseNullableDouble(naRow.field.getText());
        shared.immersionRiOverride = parseNullableDouble(immersionRow.field.getText());
        shared.sampleRiOverride = parseNullableDouble(sampleRiRow.field.getText());
        shared.xyPixelSizeOverrideUm = parseNullableDouble(xyPixelRow.field.getText());
        shared.zStepOverrideUm = parseNullableDouble(zStepRow.field.getText());
        shared.scopeModality = (ScopeModality) modalityChoice.getSelectedItem();
        shared.pinholeAiryUnits = parseNullableDouble(pinholeField.getText());
        shared.selectedChannels = new boolean[channelRows.size()];
        for (int i = 0; i < channelRows.size(); i++) {
            shared.selectedChannels[i] = channelRows.get(i).toggle.isSelected();
        }
        shared.strictNyquist = strictNyquistToggle.isSelected();
        shared.useCache = useCacheToggle.isSelected();
        return shared;
    }

    /** Pick the representative image to preview/tune on; the batch still deconvolves every image. */
    private List<SeriesJob> selectPreviewJobs(List<SeriesJob> jobs) {
        if (jobs.size() <= 1) {
            return new ArrayList<SeriesJob>(jobs);
        }
        PipelineDialog dialog = new PipelineDialog(TITLE, PipelineDialog.Phase.SETUP);
        dialog.setWorkflowTracker(new String[]{"Acquisition", "Images", "Channels", "Run"}, 1);
        dialog.setPrimaryButtonText(NextStepLabels.DECONV_CHANNEL_SETTINGS);
        dialog.addHeader("Image to preview");
        dialog.addComponent(new JLabel("<html>Choose the image to tune settings on. "
                + "The batch still deconvolves all " + jobs.size() + " images.</html>"));
        final List<ToggleSwitch> toggles = new ArrayList<ToggleSwitch>();
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setOpaque(false);
        for (int i = 0; i < jobs.size(); i++) {
            ToggleSwitch toggle = new ToggleSwitch(i == 0);
            toggles.add(toggle);
            panel.add(labeledRow(new PreviewImageChoice(jobs.get(i)).toString(), toggle));
        }
        final boolean[] updatingToggles = new boolean[1];
        for (int i = 0; i < toggles.size(); i++) {
            final int selectedIndex = i;
            toggles.get(i).addChangeListener(new Runnable() {
                @Override public void run() {
                    if (updatingToggles[0]) {
                        return;
                    }
                    updatingToggles[0] = true;
                    try {
                        ToggleSwitch selectedToggle = toggles.get(selectedIndex);
                        if (!selectedToggle.isSelected()) {
                            selectedToggle.setSelected(true);
                            return;
                        }
                        for (int j = 0; j < toggles.size(); j++) {
                            if (j != selectedIndex) {
                                toggles.get(j).setSelected(false);
                            }
                        }
                    } finally {
                        updatingToggles[0] = false;
                    }
                }
            });
        }
        dialog.addComponent(panel);
        if (!dialog.showDialog()) {
            return null;
        }
        List<SeriesJob> selected = new ArrayList<SeriesJob>();
        for (int i = 0; i < toggles.size(); i++) {
            if (toggles.get(i).isSelected()) {
                selected.add(jobs.get(i));
                break;
            }
        }
        if (selected.isEmpty()) {
            selected.add(jobs.get(0));
        }
        return selected;
    }

    private boolean runPerChannelDialogs(String directory,
                                         String[] channelNames,
                                         List<SeriesJob> previewJobs,
                                         RunSettings settings) {
        List<Integer> selected = new ArrayList<Integer>();
        for (int i = 0; i < channelNames.length; i++) {
            if (i < settings.selectedChannels.length && settings.selectedChannels[i]) {
                selected.add(Integer.valueOf(i));
            }
        }
        if (selected.isEmpty()) {
            showOrLogError("Select at least one channel to deconvolve.");
            return false;
        }
        if (settings.perChannel == null || settings.perChannel.length != channelNames.length) {
            settings.perChannel = new DeconvSettings[channelNames.length];
        }
        DeconvSettings seed = new DeconvSettings(defaultEngineKey(),
                defaultAlgorithm(resolveEngine(defaultEngineKey())),
                PsfModel.GIBSON_LANNI, 15, 0.01d);
        for (int i = 0; i < channelNames.length; i++) {
            if (settings.perChannel[i] == null) {
                settings.perChannel[i] = seed;
            }
        }
        SeriesJob previewJob = previewJobs.get(0);
        int pos = 0;
        while (pos < selected.size()) {
            int channelIndex = selected.get(pos).intValue();
            String nextChannelName = pos < selected.size() - 1
                    ? channelNames[selected.get(pos + 1).intValue()]
                    : null;
            PerChannelNav nav = showPerChannelDialog(directory, channelNames, channelIndex,
                    previewJob, settings, pos, selected.size(), nextChannelName);
            if (nav == PerChannelNav.CANCEL) {
                return false;
            }
            if (nav == PerChannelNav.BACK) {
                if (pos == 0) {
                    return false;
                }
                pos--;
            } else {
                pos++;
            }
        }
        return true;
    }

    private PerChannelNav showPerChannelDialog(final String directory,
                                               final String[] channelNames,
                                               final int channelIndex,
                                               final SeriesJob previewJob,
                                               final RunSettings settings,
                                               int pos,
                                               int totalSelected,
                                               String nextChannelName) {
        final String channelName = channelNames[channelIndex];
        DeconvSettings current = settings.perChannel[channelIndex];

        final PipelineDialog dialog = new PipelineDialog(TITLE, PipelineDialog.Phase.SETUP);
        dialog.setWorkflowTracker(new String[]{"Acquisition", "Images", "Channels", "Run"}, 2);
        dialog.addHeader("Channel " + (pos + 1) + "/" + totalSelected + ": " + channelName);

        final JComboBox<EngineChoice> engineChoice =
                new JComboBox<EngineChoice>(engineChoices().toArray(new EngineChoice[0]));
        engineChoice.setMaximumSize(new Dimension(260, 24));
        engineChoice.setRenderer(new EngineChoiceRenderer());
        selectEngineChoice(engineChoice, current.engineKey());
        final JComboBox<AlgorithmChoice> algorithmChoice = new JComboBox<AlgorithmChoice>();
        algorithmChoice.setMaximumSize(new Dimension(260, 24));
        EngineChoice initialEngine = (EngineChoice) engineChoice.getSelectedItem();
        populateAlgorithms(algorithmChoice, initialEngine == null ? null : initialEngine.engine);
        if (current.algorithm() != null) {
            algorithmChoice.setSelectedItem(new AlgorithmChoice(current.algorithm()));
        }
        final JComboBox<PsfModel> psfChoice = new JComboBox<PsfModel>(PsfModel.values());
        psfChoice.setMaximumSize(new Dimension(260, 24));
        psfChoice.setRenderer(enumRenderer());
        psfChoice.setSelectedItem(current.psfModel());
        final JSpinner iterationsSpinner =
                new JSpinner(new SpinnerNumberModel(current.iterations(), 1, 100, 1));
        final JSlider regularizationSlider =
                new JSlider(0, 100, (int) Math.round(current.regularization() * 1000.0));
        final JLabel regularizationLabel =
                new JLabel(String.format(Locale.ROOT, "%.3f", current.regularization()));
        regularizationSlider.addChangeListener(e -> regularizationLabel.setText(
                String.format(Locale.ROOT, "%.3f", regularizationSlider.getValue() / 1000.0)));
        final SourceTaggedRow iterationsRow = taggedRow("Iterations", iterationsSpinner);
        final SourceTaggedRow regularizationRow = taggedRow("Regularization strength",
                groupedComponents(regularizationSlider, regularizationLabel));
        double initialWavelength = settings.emissionOverridesNm != null
                && channelIndex < settings.emissionOverridesNm.length
                ? settings.emissionOverridesNm[channelIndex] : 0.0;
        final JTextField wavelengthField = new JTextField(
                initialWavelength > 0 ? DeconvolutionIO.formatDouble(initialWavelength) : "", 6);

        final Runnable refreshAlgorithmParameters = new Runnable() {
            @Override public void run() {
                refreshAlgorithmParameterRows(engineChoice, algorithmChoice,
                        iterationsRow, regularizationRow);
            }
        };
        engineChoice.addActionListener(e -> {
            EngineChoice choice = (EngineChoice) engineChoice.getSelectedItem();
            populateAlgorithms(algorithmChoice, choice == null ? null : choice.engine);
            refreshAlgorithmParameters.run();
        });
        algorithmChoice.addActionListener(e -> refreshAlgorithmParameters.run());

        dialog.addComponent(taggedRow("Engine", engineChoice).panel);
        dialog.addComponent(taggedRow("Algorithm", algorithmChoice).panel);
        dialog.addComponent(taggedRow("PSF model", psfChoice).panel);
        dialog.addComponent(iterationsRow.panel);
        dialog.addComponent(regularizationRow.panel);
        dialog.addComponent(taggedRow("Emission wavelength (nm)", wavelengthField).panel);

        final PreviewPairPanel previewPair = new PreviewPairPanel(dialog.getWindow(),
                "Original", "Deconvolved", PreviewPairPanel.PreviewLayout.HORIZONTAL_SLIM);
        dialog.addComponent(previewPair);
        dialog.addComponent(previewPair.sharedZRow());

        final Supplier<ResolvedSeriesSettings> resolvedSupplier =
                new Supplier<ResolvedSeriesSettings>() {
                    @Override public ResolvedSeriesSettings get() {
                        return resolvePreviewSeriesSettings(previewJob.seriesInfo, settings,
                                channelNames.length, channelIndex, wavelengthField.getText());
                    }
                };
        final ImagePlus[] rawCropRef = new ImagePlus[1];

        JButton previewButton = dialog.addFooterButton(CHANNEL_PREVIEW_RUNNING_TEXT);
        flash.pipeline.ui.FlashIcons.apply(previewButton, flash.pipeline.ui.FlashIcons.play());
        stylePreviewActionButton(previewButton);
        final Runnable markChannelPreviewReady = new Runnable() {
            @Override public void run() {
                setChannelPreviewButtonState(previewButton, ChannelPreviewButtonState.READY);
            }
        };
        engineChoice.addActionListener(e -> markChannelPreviewReady.run());
        algorithmChoice.addActionListener(e -> markChannelPreviewReady.run());
        psfChoice.addActionListener(e -> markChannelPreviewReady.run());
        iterationsSpinner.addChangeListener(e -> markChannelPreviewReady.run());
        regularizationSlider.addChangeListener(e -> markChannelPreviewReady.run());
        attachPreviewStaleClearer(wavelengthField, markChannelPreviewReady);
        previewButton.addActionListener(e -> runChannelPreview(previewPair, directory, previewJob,
                channelIndex, resolvedSupplier.get(), settings.scopeModality,
                readChannelSettings(engineChoice, algorithmChoice, psfChoice,
                        iterationsSpinner, regularizationSlider),
                rawCropRef, previewButton));

        // Load the raw crop and an initial deconvolved preview off the EDT.
        runChannelPreview(previewPair, directory, previewJob, channelIndex, resolvedSupplier.get(),
                settings.scopeModality, current, rawCropRef, previewButton);

        JButton variationsButton = dialog.addFooterButton("Parameter variations...");
        variationsButton.addActionListener(e -> dialog.runChildWorkflow(new Runnable() {
            @Override public void run() {
                ImagePlus rawCrop = rawCropRef[0];
                if (rawCrop == null) {
                    showOrLogError("Preview is still loading; try again in a moment.");
                    return;
                }
                DeconvSettings base = readChannelSettings(engineChoice, algorithmChoice,
                        psfChoice, iterationsSpinner, regularizationSlider);
                ResolvedSeriesSettings resolved = resolvedSupplier.get();
                DeconvolutionPreviewAdapter adapter =
                        new ChannelPreviewAdapter(resolved, channelIndex, settings.scopeModality);
                DeconvVariationsDialog variations = new DeconvVariationsDialog(
                        dialog.getWindow(), channelName, rawCrop, base, adapter,
                        availableEngineKeys(), algorithmNamesFor(resolveEngine(base.engineKey())),
                        psfModelNames(),
                        new java.util.function.Consumer<DeconvSettings>() {
                            @Override public void accept(DeconvSettings chosen) {
                                applyChannelSettings(chosen, engineChoice, algorithmChoice,
                                        psfChoice, iterationsSpinner, regularizationSlider);
                                refreshAlgorithmParameters.run();
                                runChannelPreview(previewPair, directory, previewJob, channelIndex,
                                        resolvedSupplier.get(), settings.scopeModality,
                                        chosen, rawCropRef, previewButton);
                            }
                        });
                variations.setVisible(true);
            }
        }));
        refreshAlgorithmParameters.run();

        if (pos > 0) {
            JButton backButton = dialog.addFooterButton("Back");
            backButton.addActionListener(e -> dialog.closeWithAction("back"));
        }
        dialog.setPrimaryButtonText(NextStepLabels.deconvolutionChannelPrimaryLabel(nextChannelName));

        boolean primary = dialog.showDialog();
        if (primary) {
            settings.perChannel[channelIndex] = readChannelSettings(engineChoice, algorithmChoice,
                    psfChoice, iterationsSpinner, regularizationSlider);
            Double wavelength = parseNullableDouble(wavelengthField.getText());
            if (wavelength != null && settings.emissionOverridesNm != null
                    && channelIndex < settings.emissionOverridesNm.length) {
                settings.emissionOverridesNm[channelIndex] = wavelength.doubleValue();
            }
            return PerChannelNav.NEXT;
        }
        return "back".equals(dialog.getActionCommand()) ? PerChannelNav.BACK : PerChannelNav.CANCEL;
    }

    private static DeconvSettings readChannelSettings(JComboBox<EngineChoice> engineChoice,
                                                      JComboBox<AlgorithmChoice> algorithmChoice,
                                                      JComboBox<PsfModel> psfChoice,
                                                      JSpinner iterationsSpinner,
                                                      JSlider regularizationSlider) {
        EngineChoice ec = (EngineChoice) engineChoice.getSelectedItem();
        String engineKey = ec == null ? null : ec.engine.key();
        AlgorithmChoice ac = (AlgorithmChoice) algorithmChoice.getSelectedItem();
        Algorithm algorithm = ac == null ? null : ac.algorithm;
        PsfModel psf = (PsfModel) psfChoice.getSelectedItem();
        int iterations = ((Number) iterationsSpinner.getValue()).intValue();
        double regularization = regularizationSlider.getValue() / 1000.0;
        return new DeconvSettings(engineKey, algorithm, psf, iterations, regularization);
    }

    private static void stylePreviewActionButton(JButton button) {
        if (button == null) return;
        button.setBackground(SOFT_BLUE_BG);
        button.setForeground(SOFT_BLUE_FG);
        button.setOpaque(true);
        button.setContentAreaFilled(true);
        button.setFocusPainted(false);
        button.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(SOFT_BLUE_BORDER),
                BorderFactory.createEmptyBorder(3, 10, 3, 10)));
        setChannelPreviewButtonState(button, ChannelPreviewButtonState.READY);
    }

    static void setChannelPreviewButtonState(JButton button, ChannelPreviewButtonState state) {
        if (button == null) return;
        ChannelPreviewButtonState safeState = state == null
                ? ChannelPreviewButtonState.READY : state;
        switch (safeState) {
            case RUNNING:
                button.setText(CHANNEL_PREVIEW_RUNNING_TEXT);
                button.setToolTipText("Preview is running with the current settings.");
                break;
            case FINISHED:
                button.setText(CHANNEL_PREVIEW_FINISHED_TEXT);
                button.setToolTipText("Preview finished. Press again to rerun with the current settings.");
                break;
            case FAILED:
                button.setText(CHANNEL_PREVIEW_FAILED_TEXT);
                button.setToolTipText("Preview failed. Adjust settings or press again to retry.");
                break;
            case READY:
            default:
                button.setText(CHANNEL_PREVIEW_READY_TEXT);
                button.setToolTipText("Run a fresh deconvolution preview with the current settings.");
                break;
        }
    }

    private void applyChannelSettings(DeconvSettings chosen,
                                      JComboBox<EngineChoice> engineChoice,
                                      JComboBox<AlgorithmChoice> algorithmChoice,
                                      JComboBox<PsfModel> psfChoice,
                                      JSpinner iterationsSpinner,
                                      JSlider regularizationSlider) {
        if (chosen == null) {
            return;
        }
        if (chosen.engineKey() != null) {
            selectEngineChoice(engineChoice, chosen.engineKey());
            populateAlgorithms(algorithmChoice, resolveEngine(chosen.engineKey()));
        }
        if (chosen.algorithm() != null) {
            algorithmChoice.setSelectedItem(new AlgorithmChoice(chosen.algorithm()));
        }
        if (chosen.psfModel() != null) {
            psfChoice.setSelectedItem(chosen.psfModel());
        }
        iterationsSpinner.setValue(Integer.valueOf(chosen.iterations()));
        regularizationSlider.setValue((int) Math.round(chosen.regularization() * 1000.0));
    }

    /** Recompute the raw|deconvolved preview pair off the EDT for the current settings. */
    private void runChannelPreview(final PreviewPairPanel previewPair,
                                   final String directory,
                                   final SeriesJob previewJob,
                                   final int channelIndex,
                                   final ResolvedSeriesSettings resolved,
                                   final ScopeModality modality,
                                   final DeconvSettings channelSettings,
                                   final ImagePlus[] rawCropRef,
                                   final JButton previewButton) {
        previewPair.setAdjustedState(PreviewPairPanel.PreviewState.RUNNING, "Deconvolving preview...");
        setChannelPreviewButtonState(previewButton, ChannelPreviewButtonState.RUNNING);
        // The compute + the global-window-state handling (WindowManagerLock + batchMode + stray
        // mop-up) live inside the previewer; here we only supply the directory/series source and the
        // per-channel engine primitives, then drive it off the EDT exactly as before.
        final ChannelDeconvPreviewer previewer = new ChannelDeconvPreviewer(
                new SeriesPreviewImageSource(directory, previewJob.seriesIndex),
                new PreviewDeconvOps(resolved, channelIndex, channelSettings, modality));
        new SwingWorker<ImagePlus[], Void>() {
            @Override protected ImagePlus[] doInBackground() throws Exception {
                return previewer.renderCropPreview(channelIndex, rawCropRef);
            }

            @Override protected void done() {
                try {
                    ImagePlus[] result = get();
                    previewPair.setOriginal(result[0]);
                    if (result[1] == null) {
                        previewPair.setAdjustedState(PreviewPairPanel.PreviewState.ERROR,
                                "PSF synthesis failed for these settings.");
                        setChannelPreviewButtonState(previewButton, ChannelPreviewButtonState.FAILED);
                    } else {
                        previewPair.setAdjusted(result[1]);
                        previewPair.setAdjustedState(PreviewPairPanel.PreviewState.READY,
                                "Deconvolved preview");
                        setChannelPreviewButtonState(previewButton, ChannelPreviewButtonState.FINISHED);
                    }
                } catch (Exception e) {
                    Throwable cause = e.getCause() == null ? e : e.getCause();
                    previewPair.setAdjustedState(PreviewPairPanel.PreviewState.ERROR,
                            "Preview failed: " + cause.getMessage());
                    setChannelPreviewButtonState(previewButton, ChannelPreviewButtonState.FAILED);
                }
            }
        }.execute();
    }

    /** {@link PreviewImageSource} backed by the standalone directory/series pair. */
    private final class SeriesPreviewImageSource implements PreviewImageSource {
        private final String directory;
        private final int seriesIndex;

        SeriesPreviewImageSource(String directory, int seriesIndex) {
            this.directory = directory;
            this.seriesIndex = seriesIndex;
        }

        @Override public ImagePlus openRawChannel(int channelIndex) throws Exception {
            return openSeriesChannel(directory, seriesIndex, channelIndex);
        }

        @Override public void close(ImagePlus image) {
            closeQuietly(image);
        }
    }

    /**
     * Supplies the previewer with the per-channel crop + engine primitives, keeping the resolved
     * optics, engine settings, and window bookkeeping (all private to this analysis) on this side of
     * the package boundary. The {@link #deconvolve} body is byte-for-byte the pre-refactor inline
     * duplicate/sanitize/deconvolve/close sequence.
     */
    private final class PreviewDeconvOps implements ChannelDeconvPreviewer.DeconvOps {
        private final ResolvedSeriesSettings resolved;
        private final int channelIndex;
        private final DeconvSettings channelSettings;
        private final ScopeModality modality;

        PreviewDeconvOps(ResolvedSeriesSettings resolved, int channelIndex,
                         DeconvSettings channelSettings, ScopeModality modality) {
            this.resolved = resolved;
            this.channelIndex = channelIndex;
            this.channelSettings = channelSettings;
            this.modality = modality;
        }

        @Override public ImagePlus cropForPreview(ImagePlus fullChannel) {
            return cropCenterStack(fullChannel, PREVIEW_CROP_SIZE, PREVIEW_CROP_SIZE, "Original");
        }

        @Override public ImagePlus deconvolve(ImagePlus rawStack) throws Exception {
            ImagePlus copy = duplicateStack(rawStack);
            sanitizeInputForDeconvolution(copy);
            try {
                return deconvolveCrop(copy, resolved, channelIndex, channelSettings, modality);
            } finally {
                closeQuietly(copy);
            }
        }

        @Override public int[] snapshotWindows() {
            return snapshotOpenImageWindows();
        }

        @Override public void closeStrayWindows(int[] beforeIds) {
            closeStrayPreviewWindows(beforeIds);
        }
    }

    private List<String> availableEngineKeys() {
        List<String> keys = new ArrayList<String>();
        for (DeconvolutionEngine engine : EngineRegistry.available()) {
            keys.add(engine.key());
        }
        if (keys.isEmpty()) {
            for (DeconvolutionEngine engine : EngineRegistry.all()) {
                keys.add(engine.key());
            }
        }
        return keys;
    }

    private List<String> algorithmNamesFor(DeconvolutionEngine engine) {
        List<String> names = new ArrayList<String>();
        if (engine != null) {
            for (Algorithm algorithm : engine.supportedAlgorithms()) {
                names.add(algorithm.name());
            }
        }
        return names;
    }

    private static List<String> psfModelNames() {
        List<String> names = new ArrayList<String>();
        for (PsfModel model : PsfModel.values()) {
            names.add(model.name());
        }
        return names;
    }

    private static ImagePlus duplicateStack(ImagePlus source) {
        int width = Math.max(1, source.getWidth());
        int height = Math.max(1, source.getHeight());
        ij.ImageStack stack = source.getStack();
        int size = stack == null ? 1 : Math.max(1, stack.getSize());
        ij.ImageStack copyStack = new ij.ImageStack(width, height);
        for (int slice = 1; slice <= size; slice++) {
            ij.process.ImageProcessor processor = stack == null
                    ? source.getProcessor() : stack.getProcessor(slice);
            processor.setRoi(0, 0, width, height);
            ij.process.ImageProcessor copy = processor.crop();
            processor.resetRoi();
            copyStack.addSlice(stack == null ? null : stack.getSliceLabel(slice), copy);
        }
        ImagePlus duplicate = new ImagePlus(source.getTitle(), copyStack);
        if (source.getCalibration() != null) {
            duplicate.setCalibration(source.getCalibration().copy());
        }
        int channels = Math.max(1, source.getNChannels());
        int slices = Math.max(1, source.getNSlices());
        int frames = Math.max(1, source.getNFrames());
        if (channels * slices * frames == copyStack.getSize()) {
            duplicate.setDimensions(channels, slices, frames);
            duplicate.setOpenAsHyperStack(source.isHyperStack());
        }
        return duplicate;
    }

    /** Runs deconvolution previews for the variations grid against one preview image/channel. */
    private final class ChannelPreviewAdapter implements DeconvolutionPreviewAdapter {
        private final ResolvedSeriesSettings resolved;
        private final int channelIndex;
        private final ScopeModality modality;

        ChannelPreviewAdapter(ResolvedSeriesSettings resolved, int channelIndex, ScopeModality modality) {
            this.resolved = resolved;
            this.channelIndex = channelIndex;
            this.modality = modality;
        }

        @Override
        public ImagePlus deconvolvePreview(ImagePlus rawCrop, DeconvSettings settings) throws Exception {
            ImagePlus copy = duplicateStack(rawCrop);
            sanitizeInputForDeconvolution(copy);
            ImagePlus out;
            try {
                out = deconvolveCrop(copy, resolved, channelIndex, settings, modality);
            } finally {
                closeQuietly(copy);
            }
            if (out == null) {
                throw new IllegalStateException("PSF synthesis failed for these settings.");
            }
            return out;
        }

        @Override
        public void close(ImagePlus image) {
            closeQuietly(image);
        }
    }

    private RunSettings showConfigurationDialog(String directory,
                                                String[] channelNames,
                                                List<SeriesJob> jobs,
                                                SeriesJob representative) {
        PipelineDialog dialog = new PipelineDialog(TITLE, PipelineDialog.Phase.SETUP);
        dialog.setWorkflowTracker(new String[]{"Setup", "Preview", "Run"}, 0);
        final DialogBindings bindings = new DialogBindings();
        final DeconvPresetIO presetIO = new DeconvPresetIO(new File(directory));
        final PreviewState previewState = new PreviewState();
        final Runnable refreshPrimaryLabel = new Runnable() {
            @Override public void run() {
                dialog.setPrimaryButtonText(
                        NextStepLabels.afterDeconvolutionSetup(previewState.accepted));
            }
        };
        final Runnable markPreviewStale = new Runnable() {
            @Override public void run() {
                previewState.clear();
                refreshPrimaryLabel.run();
            }
        };
        JButton helpButton = new JButton("?");
        styleHelpButton(helpButton);
        helpButton.setToolTipText("Explain every 3D Deconvolution option.");
        helpButton.addActionListener(e -> dialog.runChildWorkflow(new Runnable() {
            @Override public void run() {
                showDeconvolutionHelpDialog();
            }
        }));

        dialog.addHeader("Presets");
        JComboBox<String> presetChoice = new JComboBox<String>(new String[]{CUSTOM_PRESET_LABEL});
        presetChoice.setMaximumSize(new Dimension(220, 24));
        bindings.presetChoice = presetChoice;
        JButton presetButton = new JButton("Save as preset...");
        flash.pipeline.ui.FlashIcons.apply(presetButton, flash.pipeline.ui.FlashIcons.save());
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
        dialog.addComponent(labeledRow("Reuse matching cached outputs", useCacheToggle));
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
                refreshAlgorithmParameterRows(engineChoice, algorithmChoice,
                        bindings.iterationsRow, bindings.regularizationRow);
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
                refreshAlgorithmParameterRows(engineChoice, algorithmChoice,
                        bindings.iterationsRow, bindings.regularizationRow);
            }
        };

        JButton loadRunButton = LoadFromRunButton.create("DeconvolutionAnalysis", new File(directory),
                new LoadedRunParameterApplier() {
                    @Override public LoadedRunParameters.Result applyLoadedParameters(
                            Map<String, Object> parameters) {
                        LoadedRunParameters.PresetLoad<DeconvPreset> load =
                                LoadedRunParameters.deconvPreset(parameters);
                        DeconvPreset preset = load.payload;
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
                                "From previous run",
                                refreshAlgorithms,
                                refreshEnablement);
                        bindings.programmaticChange = true;
                        try {
                            presetChoice.setSelectedItem(CUSTOM_PRESET_LABEL);
                        } finally {
                            bindings.programmaticChange = false;
                        }
                        return load.result;
                    }
                });
        dialog.setNorthSlot(topHelpRow(helpButton, loadRunButton));

        populatePresetChoice(presetChoice, loadPresets(presetIO), CUSTOM_PRESET_LABEL);

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
                showMissingEngineDependency(choice);
            }
            refreshAlgorithms.run();
            if (!bindings.programmaticChange) {
                clearSourceTag(bindings.engineRow.sourceTagLabel);
                clearSourceTag(bindings.algorithmRow.sourceTagLabel);
            }
        });
        algorithmChoice.addActionListener(e -> {
            refreshAlgorithmParameterRows(engineChoice, algorithmChoice,
                    bindings.iterationsRow, bindings.regularizationRow);
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

        // Any change to a setting that affects the rendered preview (including programmatic preset
        // or previous-run loads) invalidates a previously accepted preview. strictNyquist and
        // useCache do not change the rendered image, but are cleared here conservatively so any
        // edit re-triggers acceptance rather than silently keeping a stale "accepted" state.
        engineChoice.addActionListener(e -> markPreviewStale.run());
        algorithmChoice.addActionListener(e -> markPreviewStale.run());
        psfChoice.addActionListener(e -> markPreviewStale.run());
        modalityChoice.addActionListener(e -> markPreviewStale.run());
        iterationsSpinner.addChangeListener(e -> markPreviewStale.run());
        regularizationSlider.addChangeListener(e -> markPreviewStale.run());
        strictNyquistToggle.addChangeListener(markPreviewStale);
        useCacheToggle.addChangeListener(markPreviewStale);
        attachPreviewStaleClearer(pinholeField, markPreviewStale);
        attachPreviewStaleClearer(sampleRiRow.field, markPreviewStale);
        attachPreviewStaleClearer(naRow.field, markPreviewStale);
        attachPreviewStaleClearer(immersionRow.field, markPreviewStale);
        attachPreviewStaleClearer(xyPixelRow.field, markPreviewStale);
        attachPreviewStaleClearer(zStepRow.field, markPreviewStale);
        attachPreviewStaleClearer(emissionRow.field, markPreviewStale);
        for (ChannelToggleRow channelRow : channelRows) {
            channelRow.toggle.addChangeListener(markPreviewStale);
        }

        // Setup preview: render one representative cropped stack with the current values so the user
        // can confirm settings before committing to the full batch, mirroring Set Up Configuration.
        JButton previewButton = dialog.addFooterButton("Preview settings...");
        flash.pipeline.ui.FlashIcons.apply(previewButton, flash.pipeline.ui.FlashIcons.play());
        previewButton.setToolTipText("Render a raw vs deconvolved preview of one representative crop "
                + "using the current settings.");
        previewButton.addActionListener(e -> dialog.runChildWorkflow(new Runnable() {
            @Override public void run() {
                RunSettings current = buildRunSettingsFromDialog(channelNames, engineChoice,
                        algorithmChoice, psfChoice, modalityChoice, pinholeField, sampleRiRow, bindings,
                        iterationsSpinner, regularizationSlider, strictNyquistToggle, useCacheToggle,
                        channelRows, naRow, immersionRow, xyPixelRow, zStepRow, emissionRow);
                List<String> errors = validateRequiredFields(representative.seriesInfo, current);
                if (!errors.isEmpty()) {
                    showValidationErrors(errors);
                    return;
                }
                if (!areSelectedChannelEnginesReady(current)) {
                    return;
                }
                int channelIndex = firstSelectedChannel(current.selectedChannels);
                if (channelIndex < 0) {
                    showOrLogError("Select at least one channel before previewing.");
                    return;
                }
                String fingerprint = previewFingerprint(current, representative, channelIndex, PREVIEW_CROP_SIZE);
                // The user explicitly requested this preview, so always render it even if the CLI
                // skipPreview flag (meant for unattended runs) is set. `current` is a throwaway copy
                // and is never returned to execute(), so clearing it here is safe.
                current.skipPreview = false;
                PreviewSelection previewSelection = choosePreviewSelection(directory, jobs, representative,
                        channelNames, current.selectedChannels);
                if (previewSelection == null) {
                    return; // The user cancelled preview options: leave the setup dialog open.
                }
                List<String> previewErrors = validateRequiredFields(previewSelection.job.seriesInfo, current);
                if (!previewErrors.isEmpty()) {
                    showValidationErrors(previewErrors);
                    return;
                }
                DeconvPreviewDialog.Decision decision =
                        showPreviewBeforeBatch(directory, previewSelection.job, channelNames, current,
                                previewSelection.channels, previewSelection.cropSpec);
                if (decision == DeconvPreviewDialog.Decision.RUN_FULL_BATCH) {
                    previewState.accept(fingerprint);
                    refreshPrimaryLabel.run();
                    dialog.setTransientStatus("Preview accepted - press Run deconvolution "
                            + "to run the full batch, or keep editing to refine.");
                } else if (decision == DeconvPreviewDialog.Decision.CANCEL) {
                    dialog.closeWithAction("cancel");
                }
                // RECONFIGURE: leave the setup dialog open with no change.
            }
        }));

        refreshAlgorithms.run();
        refreshEnablement.run();
        refreshPrimaryLabel.run();

        if (!dialog.showDialog()) {
            return null;
        }

        RunSettings settings = buildRunSettingsFromDialog(channelNames, engineChoice, algorithmChoice,
                psfChoice, modalityChoice, pinholeField, sampleRiRow, bindings, iterationsSpinner,
                regularizationSlider, strictNyquistToggle, useCacheToggle, channelRows,
                naRow, immersionRow, xyPixelRow, zStepRow, emissionRow);
        int previewChannel = firstSelectedChannel(settings.selectedChannels);
        String finalFingerprint = previewFingerprint(settings, representative, previewChannel, PREVIEW_CROP_SIZE);
        settings.previewAccepted = previewState.matches(finalFingerprint);
        recordDeconvolutionRunParameters(bindings);
        return settings;
    }

    /**
     * Capture the confirmed Deconvolution dialog settings into the run record so
     * a later "Load settings from previous run" can restore them. Keys mirror
     * {@link DeconvPreset#toJsonObject()} so {@code LoadedRunParameters.DECONV_KEYS}
     * recognises them.
     */
    private void recordDeconvolutionRunParameters(DialogBindings bindings) {
        if (runRecordContext == null || bindings == null) {
            return;
        }
        try {
            DeconvPreset preset = buildPresetFromBindings("GUI Deconvolution run", bindings);
            runRecordContext.recordParameters(ParameterSnapshot.fromAnalysisPresetMap(
                    "DeconvolutionAnalysis", preset.toJsonObject()));
        } catch (RuntimeException e) {
            IJ.log("[FLASH] Could not capture Deconvolution run parameters: " + e.getMessage());
        }
    }

    /**
     * Collects the current in-dialog values into a {@link RunSettings}. Extracted so both the
     * final OK path and a setup-dialog preview button can read the live values without waiting
     * for the dialog to close.
     */
    private RunSettings buildRunSettingsFromDialog(
            String[] channelNames,
            JComboBox<EngineChoice> engineChoice,
            JComboBox<AlgorithmChoice> algorithmChoice,
            JComboBox<PsfModel> psfChoice,
            JComboBox<ScopeModality> modalityChoice,
            JTextField pinholeField,
            MetadataFieldRow sampleRiRow,
            DialogBindings bindings,
            JSpinner iterationsSpinner,
            JSlider regularizationSlider,
            ToggleSwitch strictNyquistToggle,
            ToggleSwitch useCacheToggle,
            List<ChannelToggleRow> channelRows,
            MetadataFieldRow naRow,
            MetadataFieldRow immersionRow,
            MetadataFieldRow xyPixelRow,
            MetadataFieldRow zStepRow,
            MetadataFieldRow emissionRow) {
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
        fillUniformPerChannel(settings);
        return settings;
    }

    /**
     * Deterministic fingerprint of the settings that change the rendered preview. Used to detect
     * when an accepted preview no longer matches the current setup values. Inspection-only:
     * never affects batch output or cache keys.
     */
    String previewFingerprint(RunSettings settings, SeriesJob representative, int channelIndex, int cropSize) {
        if (settings == null) return "";
        int channelCount = settings.channelNames == null ? 0 : settings.channelNames.length;
        StringBuilder sb = new StringBuilder();
        sb.append(settings.engineKey).append('|');
        sb.append(settings.algorithm == null ? "" : settings.algorithm.name()).append('|');
        sb.append(settings.psfModel == null ? "" : settings.psfModel.name()).append('|');
        sb.append(settings.scopeModality == null ? "" : settings.scopeModality.name()).append('|');
        DeconvSettings deconvSettings = settings.channel(channelIndex);
        sb.append(usesIterations(deconvSettings) ? String.valueOf(deconvSettings.iterations()) : "").append('|');
        sb.append(usesRegularization(deconvSettings)
                ? DeconvolutionIO.formatDouble(deconvSettings.regularization()) : "").append('|');
        sb.append(settings.pinholeAiryUnits).append('|');
        sb.append(settings.sampleRiOverride).append('|');
        sb.append(settings.naOverride).append('|');
        sb.append(settings.immersionRiOverride).append('|');
        sb.append(settings.xyPixelSizeOverrideUm).append('|');
        sb.append(settings.zStepOverrideUm).append('|');
        sb.append(joinWavelengths(settings.emissionOverridesNm, channelCount)).append('|');
        sb.append(settings.channelNames == null
                ? "(none)"
                : selectedChannelList(settings.channelNames, settings.selectedChannels)).append('|');
        sb.append(representative == null ? "" : representative.seriesIndex).append('|');
        sb.append(channelIndex).append('|').append(cropSize);
        return sb.toString();
    }

    private static void attachPreviewStaleClearer(JTextField field, Runnable onChange) {
        if (field == null || onChange == null) return;
        field.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { onChange.run(); }
            @Override public void removeUpdate(DocumentEvent e) { onChange.run(); }
            @Override public void changedUpdate(DocumentEvent e) { onChange.run(); }
        });
    }

    public LoadedRunParameters.Result applyLoadedParameters(Map<String, Object> parameters) {
        LoadedRunParameters.PresetLoad<DeconvPreset> load =
                LoadedRunParameters.deconvPreset(parameters);
        LoadedRunParameters.rememberLastResult(load.result);
        return load.result;
    }

    private void showDeconvolutionHelpDialog() {
        PipelineDialog help = new PipelineDialog("3D Deconvolution - Options Help", PipelineDialog.Phase.SETUP);
        help.setPrimaryButtonText("Close");

        help.addHeader("What This Analysis Does");
        help.addComponent(helpParagraph(
                "<b>3D Deconvolution</b> uses your microscope settings to estimate how each point of light spreads, "
                        + "then reverses that blur. Use it when blur limits segmentation or intensity measurement. "
                        + "Skip it when the raw data are already clean, metadata are unreliable, or you must preserve "
                        + "raw intensities exactly."));

        help.addHeader("Presets");
        help.addComponent(helpParagraph(
                "<b>Preset</b> loads a saved settings set, useful when repeating the same microscope, objective, and "
                        + "sample. <b>Save as preset</b> stores the current expert settings for next time."));

        help.addHeader("Engine & Algorithm");
        help.addComponent(helpParagraph(
                "<b>Engine</b> is the runtime that does the maths. CLIJ2 is fastest on a GPU (good for big batches); "
                        + "DeconvolutionLab2 is CPU-based and reproducible; Iterative Deconvolve 3D is a fallback. "
                        + "Greyed engines are not installed."));
        help.addComponent(helpParagraph(
                "<b>Algorithm</b> is the method the engine uses. Richardson-Lucy is the usual starting point; "
                        + "regularized variants suppress noise and ringing; linear methods (Wiener, Tikhonov) are "
                        + "faster and gentler but recover dim 3D structure less well."));
        help.addComponent(helpParagraph(
                "<b>PSF model</b> generates the point-spread function. Gibson &amp; Lanni is the best default for "
                        + "high-NA objectives because it models refractive-index mismatch; simpler models are only for "
                        + "missing metadata or a quick run."));

        help.addHeader("Microscope & Sample");
        help.addComponent(helpParagraph(
                "<b>XY pixel size (um)</b>: physical width of one pixel; scales the PSF in x and y. Use the metadata "
                        + "value - wrong values make deconvolution too weak or too strong."));
        help.addComponent(helpParagraph(
                "<b>Numerical Aperture (NA)</b>: the objective's light-gathering power. Higher NA gives a smaller PSF. "
                        + "Use the value printed on the lens or in the metadata."));
        help.addComponent(helpParagraph(
                "<b>Immersion RI</b>: refractive index of the immersion medium (air, water, glycerol, oil), usually "
                        + "inferred from metadata. Change only if the detected medium is wrong."));
        help.addComponent(helpParagraph(
                "<b>Sample RI</b>: refractive index of the tissue or mountant; affects spherical aberration and z "
                        + "blur. Fixed tissue is often around 1.45 to 1.52, depending on the mountant."));
        help.addComponent(helpParagraph(
                "<b>Emission wavelength (nm)</b>: one value per channel, in channel order (for example 460, 520, 590, "
                        + "670). Longer wavelengths give a wider PSF; missing or wrong values reduce accuracy."));
        help.addComponent(helpParagraph(
                "<b>Z-step (um)</b>: spacing between optical sections, critical for 3D. Too large for the "
                        + "objective/wavelength and deconvolution creates artifacts instead of recovering structure."));

        help.addHeader("Scope & Channels");
        help.addComponent(helpParagraph(
                "<b>Scope modality</b>: tells the PSF whether the data are confocal (point-scan or spinning-disk) or "
                        + "widefield (epifluorescence)."));
        help.addComponent(helpParagraph(
                "<b>Pinhole (Airy units)</b> (confocal only): smaller pinholes reject more out-of-focus light but "
                        + "lose signal. Use the acquisition value; 1.0 Airy unit is a common default."));
        help.addComponent(helpParagraph(
                "<b>Deconvolve channel</b>: process channels you will segment or measure; skip reference, saturated, "
                        + "or too-noisy channels."));

        help.addHeader("Parameters");
        help.addComponent(helpParagraph(
                "<b>Iterations</b>: more iterations sharpen more but amplify noise and take longer. Start at 10 to 20; "
                        + "raise only if objects stay blurred."));
        help.addComponent(helpParagraph(
                "<b>Regularization strength</b>: damps noise and ringing. Higher is safer for noisy data but smooths "
                        + "small structures; lower keeps detail but worsens speckle and edges."));
        help.addComponent(helpParagraph(
                "<b>Strict Nyquist</b>: rejects or warns about undersampled data. On for quantitative or "
                        + "publication runs; off for exploratory cleanup."));

        help.addHeader("Cache & Preview");
        help.addComponent(helpParagraph(
                "<b>Reuse matching cached outputs</b>: reuses existing results when the image and settings match. Keep "
                        + "it on for normal batches; turn it off after manual file changes or to check for stale output."));
        help.addComponent(helpParagraph(
                "<b>Clear cache</b>: deletes cached outputs for this project. Use it for disk space, settings changed "
                        + "outside FLASH, or suspected stale reuse."));
        help.addComponent(helpParagraph(
                "<b>Preview before batch</b>: runs a small centre crop so you can compare raw versus deconvolved "
                        + "before the full batch. If it looks noisy, lower iterations or raise regularization."));

        help.addHeader("Status Text");
        help.addComponent(helpParagraph(
                "<b>Auto-detected</b> = read from metadata. <b>Missing</b> = required, enter it. <b>Inferred</b> = "
                        + "estimated from nearby metadata. <b>From preset</b> = filled by a saved preset. Editing a "
                        + "field clears its source tag."));

        help.showDialog();
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
        return showPreviewBeforeBatch(directory, job, channelNames, settings, null, CropSpec.centre256());
    }

    /**
     * Renders a raw/deconvolved preview for each requested channel and shows the modal preview
     * dialog with one row per channel. {@code channelsToPreview} selects which channel indices to
     * render; when {@code null} every channel currently toggled on for deconvolution is previewed.
     */
    private DeconvPreviewDialog.Decision showPreviewBeforeBatch(String directory,
                                                                SeriesJob job,
                                                                String[] channelNames,
                                                                RunSettings settings,
                                                                int[] channelsToPreview) {
        return showPreviewBeforeBatch(directory, job, channelNames, settings,
                channelsToPreview, CropSpec.centre256());
    }

    private DeconvPreviewDialog.Decision showPreviewBeforeBatch(String directory,
                                                                SeriesJob job,
                                                                String[] channelNames,
                                                                RunSettings settings,
                                                                int[] channelsToPreview,
                                                                CropSpec cropSpec) {
        if (settings == null || settings.skipPreview || isInteractiveHeadless()) {
            return DeconvPreviewDialog.Decision.RUN_FULL_BATCH;
        }

        int[] channels = sanitizePreviewChannels(
                channelsToPreview != null ? channelsToPreview
                        : selectedChannelIndices(settings.selectedChannels),
                channelNames.length);
        if (channels.length == 0) {
            return DeconvPreviewDialog.Decision.RUN_FULL_BATCH;
        }

        CropSpec previewCrop = cropSpec == null ? CropSpec.centre256() : cropSpec;
        String[] channelLuts = resolveChannelLuts(directory, channelNames.length);
        List<DeconvPreviewDialog.ChannelPreview> entries =
                new ArrayList<DeconvPreviewDialog.ChannelPreview>();
        try {
            // The preview renders in-memory stacks that are displayed inside the Swing dialog, never
            // as ImageJ windows. Some deconvolution backends (notably DeconvolutionLab2) still leak an
            // orphan image window despite silent monitors, so we render under ImageJ batch mode
            // to suppress stray windows and close anything that slipped through afterwards. Without
            // this, each previewed channel could pop a loose window outside the preview dialog.
            WindowManagerLock.LOCK.lock();
            try {
                int[] windowsBeforeRender = snapshotOpenImageWindows();
                boolean previousBatchMode = Interpreter.batchMode;
                Interpreter.batchMode = true;
                try {
                    for (int channelIndex : channels) {
                        DeconvPreviewDialog.PreviewContent single;
                        try {
                            single = renderPreviewContent(directory, job, channelNames, settings,
                                    channelIndex, previewCrop);
                        } catch (Exception e) {
                            String message = "Deconvolution preview failed for " + channelNames[channelIndex]
                                    + ": " + e.getMessage() + ". Skipping this channel in the preview.";
                            IJ.log(message);
                            recordWarn(message);
                            continue;
                        }
                        if (single == null) {
                            continue;
                        }
                        String lut = channelIndex < channelLuts.length ? channelLuts[channelIndex] : null;
                        entries.add(new DeconvPreviewDialog.ChannelPreview(
                                single.rawStack, single.deconvolvedStack,
                                single.rawLabel, single.deconvolvedLabel,
                                channelNames[channelIndex], lut));
                    }
                } finally {
                    Interpreter.batchMode = previousBatchMode;
                    closeStrayPreviewWindows(windowsBeforeRender);
                }
            } finally {
                WindowManagerLock.LOCK.unlock();
            }
            if (entries.isEmpty()) {
                return DeconvPreviewDialog.Decision.RUN_FULL_BATCH;
            }
            return DeconvPreviewDialog.show(new DeconvPreviewDialog.PreviewContent(entries), false);
        } finally {
            // Ownership of the rendered stacks transfers here. The dialog is modal, so the stacks
            // stay valid until show(...) returns.
            for (DeconvPreviewDialog.ChannelPreview entry : entries) {
                closeQuietly(entry.deconvolvedStack);
                closeQuietly(entry.rawStack);
            }
        }
    }

    /**
     * Asks the user which image, channels, and ROI/crop to render in the setup preview. The crop row
     * intentionally mirrors the Parameter Variations dialog so segmentation and deconvolution setup
     * previews use the same visual language.
     */
    private PreviewSelection choosePreviewSelection(String directory,
                                                    List<SeriesJob> jobs,
                                                    SeriesJob defaultJob,
                                                    String[] channelNames,
                                                    boolean[] selectedChannels) {
        int[] selectable = selectedChannelIndices(selectedChannels);
        if (selectable.length == 0) {
            return null;
        }
        if (isInteractiveHeadless()) {
            return new PreviewSelection(defaultJob, selectable, CropSpec.centre256());
        }

        final PipelineDialog picker = new PipelineDialog("Preview Settings", PipelineDialog.Phase.SETUP);
        picker.setWorkflowTracker(new String[]{"Setup", "Preview", "Run"}, 1);
        List<PreviewImageChoice> imageChoices = previewImageChoices(jobs, defaultJob);
        final JComboBox<PreviewImageChoice> imageChoice =
                new JComboBox<PreviewImageChoice>(imageChoices.toArray(new PreviewImageChoice[0]));
        imageChoice.setMaximumSize(new Dimension(420, 24));
        selectPreviewImageChoice(imageChoice, defaultJob);

        picker.addHeader("Image to preview");
        picker.addComponent(labeledRow("Image", imageChoice));
        picker.addHelpText("This choice is only for the setup preview; the full batch still runs on every selected image.");

        picker.addHeader("Channels to preview");
        JPanel column = new JPanel();
        column.setLayout(new BoxLayout(column, BoxLayout.Y_AXIS));
        column.setOpaque(false);
        final List<ToggleSwitch> toggles = new ArrayList<ToggleSwitch>();
        for (int channelIndex : selectable) {
            ToggleSwitch toggle = new ToggleSwitch(true);
            toggles.add(toggle);
            column.add(labeledRow(channelNames[channelIndex], toggle));
        }
        picker.addComponent(column);

        picker.addHeader("Preview ROI");
        final CropSpec[] cropSpec = new CropSpec[]{CropSpec.centre256()};
        final JRadioButton fullCrop = new JRadioButton("full image");
        final JRadioButton centreCrop = new JRadioButton("centered 256 x 256");
        final JRadioButton customCrop = new JRadioButton("custom...");
        JPanel cropRow = previewCropRow(fullCrop, centreCrop, customCrop);
        picker.addComponent(cropRow);
        picker.addHelpText("Use a ROI for fast checks, or the full image when edge behavior and global blur matter.");
        selectPreviewCropButton(cropSpec[0], fullCrop, centreCrop, customCrop);

        fullCrop.addActionListener(e -> cropSpec[0] = CropSpec.full());
        centreCrop.addActionListener(e -> cropSpec[0] = CropSpec.centre256());
        customCrop.addActionListener(e -> {
            CropSpec previous = cropSpec[0];
            Rectangle initial = previous != null && previous.mode() == CropSpec.Mode.CUSTOM
                    ? previous.bounds()
                    : null;
            ImagePlus source = null;
            try {
                PreviewImageChoice selectedImage = (PreviewImageChoice) imageChoice.getSelectedItem();
                int channelIndex = firstCheckedPreviewChannel(selectable, toggles);
                if (selectedImage == null || selectedImage.job == null) {
                    throw new IOException("No preview image is selected.");
                }
                source = openSeriesChannel(directory, selectedImage.job.seriesIndex, channelIndex);
                if (source == null) {
                    throw new IOException("The selected image channel could not be opened.");
                }
                Rectangle chosen = CustomCropPicker.choose(picker.getWindow(), source, initial);
                if (chosen == null) {
                    cropSpec[0] = previous;
                    selectPreviewCropButton(previous, fullCrop, centreCrop, customCrop);
                    return;
                }
                cropSpec[0] = CropSpec.custom(chosen);
            } catch (Exception ex) {
                cropSpec[0] = previous;
                selectPreviewCropButton(previous, fullCrop, centreCrop, customCrop);
                IJ.showMessage("Preview ROI", "Could not choose a custom ROI:\n" + ex.getMessage());
            } finally {
                closeQuietly(source);
            }
        });
        imageChoice.addActionListener(e -> {
            if (customCrop.isSelected()) {
                cropSpec[0] = CropSpec.centre256();
                selectPreviewCropButton(cropSpec[0], fullCrop, centreCrop, customCrop);
                picker.setTransientStatus("Custom ROI reset for the newly selected image.");
            }
        });

        picker.setPrimaryButtonText(NextStepLabels.PREVIEW_DECONVOLUTION);
        if (!picker.showDialog()) {
            return null;
        }

        List<Integer> chosen = new ArrayList<Integer>();
        for (int i = 0; i < selectable.length; i++) {
            if (toggles.get(i).isSelected()) {
                chosen.add(selectable[i]);
            }
        }
        if (chosen.isEmpty()) {
            chosen.add(selectable[0]); // Always preview at least one channel.
        }
        PreviewImageChoice selectedImage = (PreviewImageChoice) imageChoice.getSelectedItem();
        SeriesJob selectedJob = selectedImage == null || selectedImage.job == null
                ? defaultJob
                : selectedImage.job;
        return new PreviewSelection(selectedJob, toIntArray(chosen),
                cropSpec[0] == null ? CropSpec.centre256() : cropSpec[0]);
    }

    private static List<PreviewImageChoice> previewImageChoices(List<SeriesJob> jobs,
                                                                SeriesJob defaultJob) {
        List<PreviewImageChoice> choices = new ArrayList<PreviewImageChoice>();
        if (jobs != null) {
            for (SeriesJob job : jobs) {
                if (job != null) {
                    choices.add(new PreviewImageChoice(job));
                }
            }
        }
        if (choices.isEmpty() && defaultJob != null) {
            choices.add(new PreviewImageChoice(defaultJob));
        }
        return choices;
    }

    private static void selectPreviewImageChoice(JComboBox<PreviewImageChoice> imageChoice,
                                                 SeriesJob defaultJob) {
        if (imageChoice == null || defaultJob == null) return;
        for (int i = 0; i < imageChoice.getItemCount(); i++) {
            PreviewImageChoice choice = imageChoice.getItemAt(i);
            if (choice != null && sameSeriesJob(choice.job, defaultJob)) {
                imageChoice.setSelectedIndex(i);
                return;
            }
        }
    }

    private static boolean sameSeriesJob(SeriesJob a, SeriesJob b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        String aSource = a.sourceFile == null ? "" : a.sourceFile.getAbsolutePath();
        String bSource = b.sourceFile == null ? "" : b.sourceFile.getAbsolutePath();
        return a.seriesIndex == b.seriesIndex
                && aSource.equals(bSource)
                && nullToEmpty(a.baseName).equals(nullToEmpty(b.baseName));
    }

    private static JPanel previewCropRow(JRadioButton fullCrop,
                                         JRadioButton centreCrop,
                                         JRadioButton customCrop) {
        JPanel row = new JPanel();
        row.setOpaque(false);
        row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
        row.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        ButtonGroup group = new ButtonGroup();
        group.add(fullCrop);
        group.add(centreCrop);
        group.add(customCrop);
        fullCrop.setOpaque(false);
        centreCrop.setOpaque(false);
        customCrop.setOpaque(false);
        row.add(new JLabel("ROI: "));
        row.add(fullCrop);
        row.add(Box.createHorizontalStrut(10));
        row.add(centreCrop);
        row.add(Box.createHorizontalStrut(10));
        row.add(customCrop);
        row.add(Box.createHorizontalGlue());
        return row;
    }

    private static void selectPreviewCropButton(CropSpec spec,
                                                JRadioButton fullCrop,
                                                JRadioButton centreCrop,
                                                JRadioButton customCrop) {
        CropSpec.Mode mode = spec == null ? CropSpec.Mode.CENTRE_256 : spec.mode();
        fullCrop.setSelected(mode == CropSpec.Mode.FULL);
        centreCrop.setSelected(mode == CropSpec.Mode.CENTRE_256);
        customCrop.setSelected(mode == CropSpec.Mode.CUSTOM);
    }

    private static int firstCheckedPreviewChannel(int[] selectable,
                                                  List<ToggleSwitch> toggles) {
        if (selectable == null || selectable.length == 0) return -1;
        if (toggles != null) {
            for (int i = 0; i < selectable.length && i < toggles.size(); i++) {
                ToggleSwitch toggle = toggles.get(i);
                if (toggle != null && toggle.isSelected()) {
                    return selectable[i];
                }
            }
        }
        return selectable[0];
    }

    /** Reads the per-channel LUT colour names from the project config; missing entries stay null. */
    private String[] resolveChannelLuts(String directory, int channelCount) {
        String[] luts = new String[Math.max(0, channelCount)];
        try {
            BinConfig config = BinConfigIO.readFromDirectory(directory);
            for (int i = 0; i < luts.length && i < config.channelColors.size(); i++) {
                luts[i] = config.channelColors.get(i);
            }
        } catch (IOException | RuntimeException e) {
            // Channel colours are cosmetic; an unreadable or malformed config must never abort the
            // preview, so fall back to grey LUTs rather than letting the failure propagate.
        }
        return luts;
    }

    /** Snapshot of the currently open ImageJ image-window IDs (empty when headless). */
    private static int[] snapshotOpenImageWindows() {
        if (GraphicsEnvironment.isHeadless()) return new int[0];
        int[] ids = WindowManager.getIDList();
        return ids == null ? new int[0] : ids.clone();
    }

    /**
     * Closes any ImageJ image window that appeared since {@code beforeIds} was captured. Used to
     * mop up orphan windows leaked by third-party deconvolution backends during a preview render.
     * The preview's own stacks are never registered as ImageJ windows, so this only closes strays.
     */
    private void closeStrayPreviewWindows(int[] beforeIds) {
        closeStrayImageWindows(beforeIds, "deconvolution-preview");
    }

    private void closeStrayDeconvolutionWindows(int[] beforeIds, ImagePlus... keepImages) {
        closeStrayImageWindows(beforeIds, "deconvolution", keepImages);
    }

    private void closeStrayImageWindows(int[] beforeIds,
                                        String context,
                                        ImagePlus... keepImages) {
        if (beforeIds == null) return;
        if (GraphicsEnvironment.isHeadless()) return;
        int[] afterIds = WindowManager.getIDList();
        if (afterIds == null) return;
        Set<Integer> before = new HashSet<Integer>();
        for (int id : beforeIds) {
            before.add(Integer.valueOf(id));
        }
        for (int id : afterIds) {
            if (before.contains(Integer.valueOf(id))) continue;
            ImagePlus stray = WindowManager.getImage(id);
            if (stray == null) continue;
            if (isProtectedStrayWindow(stray, keepImages)) continue;
            String title = stray.getTitle();
            stray.changes = false;
            try {
                stray.close();
            } finally {
                stray.flush();
            }
            IJ.log("[FLASH] Closed a stray " + safeWindowContext(context) + " window: " + title);
        }
    }

    private static String safeWindowContext(String context) {
        String value = context == null ? "" : context.trim();
        return value.isEmpty() ? "ImageJ" : value;
    }

    private static boolean isProtectedStrayWindow(ImagePlus image, ImagePlus... keepImages) {
        if (image == null || keepImages == null) {
            return false;
        }
        for (int i = 0; i < keepImages.length; i++) {
            if (image == keepImages[i]) {
                return true;
            }
        }
        return false;
    }

    private static int[] selectedChannelIndices(boolean[] selectedChannels) {
        if (selectedChannels == null) return new int[0];
        List<Integer> indices = new ArrayList<Integer>();
        for (int i = 0; i < selectedChannels.length; i++) {
            if (selectedChannels[i]) {
                indices.add(i);
            }
        }
        return toIntArray(indices);
    }

    private static int[] sanitizePreviewChannels(int[] channels, int channelCount) {
        if (channels == null) return new int[0];
        List<Integer> cleaned = new ArrayList<Integer>();
        Set<Integer> seen = new HashSet<Integer>();
        for (int channel : channels) {
            if (channel >= 0 && channel < channelCount && seen.add(channel)) {
                cleaned.add(channel);
            }
        }
        return toIntArray(cleaned);
    }

    private static int[] toIntArray(List<Integer> values) {
        int[] out = new int[values.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = values.get(i);
        }
        return out;
    }

    /**
     * Renders a raw/deconvolved representative preview using the same engine, PSF, crop,
     * and metadata code as the batch. The returned stacks are owned by the caller and
     * must not be closed here; every other temporary {@link ImagePlus} is released before
     * returning. Returns {@code null} when any required input cannot be produced.
     */
    DeconvPreviewDialog.PreviewContent renderPreviewContent(String directory,
                                                            SeriesJob job,
                                                            String[] channelNames,
                                                            RunSettings settings,
                                                            int channelIndex,
                                                            int cropWidth,
                                                            int cropHeight) throws Exception {
        return renderPreviewContent(directory, job, channelNames, settings,
                channelIndex, cropWidth, cropHeight, null);
    }

    DeconvPreviewDialog.PreviewContent renderPreviewContent(String directory,
                                                            SeriesJob job,
                                                            String[] channelNames,
                                                            RunSettings settings,
                                                            int channelIndex,
                                                            CropSpec cropSpec) throws Exception {
        return renderPreviewContent(directory, job, channelNames, settings,
                channelIndex, PREVIEW_CROP_SIZE, PREVIEW_CROP_SIZE,
                cropSpec == null ? CropSpec.centre256() : cropSpec);
    }

    private DeconvPreviewDialog.PreviewContent renderPreviewContent(String directory,
                                                                    SeriesJob job,
                                                                    String[] channelNames,
                                                                    RunSettings settings,
                                                                    int channelIndex,
                                                                    int cropWidth,
                                                                    int cropHeight,
                                                                    CropSpec cropSpec) throws Exception {
        ImagePlus rawChannel = null;
        ImagePlus rawCrop = null;
        ImagePlus deconvolved = null;
        try {
            rawChannel = openSeriesChannel(directory, job.seriesIndex, channelIndex);
            if (rawChannel == null) {
                return null;
            }

            rawCrop = cropSpec == null
                    ? cropCenterStack(rawChannel, cropWidth, cropHeight, "Raw Preview")
                    : cropStack(rawChannel, cropSpec, "Raw Preview");
            if (rawCrop == null) {
                return null;
            }

            ResolvedSeriesSettings resolved = resolveSeriesSettings(job.seriesInfo, settings, channelNames.length);
            DeconvSettings ch = settings.channel(channelIndex);
            sanitizeInputForDeconvolution(rawCrop);
            deconvolved = deconvolveCrop(rawCrop, resolved, channelIndex, ch, settings.scopeModality);
            if (deconvolved == null) {
                return null;
            }

            double[] rawRange = stackDisplayRange(rawCrop);
            rawCrop.setTitle("Raw Preview");
            deconvolved.setTitle("Deconvolved Preview");
            applyDisplayRange(rawCrop, rawRange[0], rawRange[1]);
            applyDisplayRange(deconvolved, rawRange[0], rawRange[1]);

            DeconvolutionEngine engine = resolveEngine(ch.engineKey());
            String parameterLabel = parameterSummary(engine, ch);
            String deconvolvedLabel = "Deconvolved (" + engine.displayName()
                    + (parameterLabel.isEmpty() ? "" : ", " + parameterLabel)
                    + ", " + ch.psfModel().displayName() + ")";

            // Ownership transfer: null the local references so the finally block does not
            // close the stacks that the caller/dialog still needs to display.
            ImagePlus rawOut = rawCrop;
            ImagePlus deconvOut = deconvolved;
            rawCrop = null;
            deconvolved = null;
            return new DeconvPreviewDialog.PreviewContent(rawOut, deconvOut, "Raw", deconvolvedLabel);
        } finally {
            closeQuietly(deconvolved);
            closeQuietly(rawCrop);
            closeQuietly(rawChannel);
        }
    }

    /**
     * Deconvolve an already-cropped, already-sanitized single-channel stack with the
     * given per-channel settings. Synthesizes the PSF from {@code resolved} optics, runs
     * the chosen engine/algorithm, and returns a new stack (caller owns it). Returns
     * {@code null} only when PSF synthesis fails; engine failures propagate. Does not
     * mutate {@code rawCrop}, so it is safe to call repeatedly on a shared crop (the
     * deconvolution variations sweep relies on this).
     */
    ImagePlus deconvolveCrop(ImagePlus rawCrop,
                             ResolvedSeriesSettings resolved,
                             int channelIndex,
                             DeconvSettings ch,
                             ScopeModality modality) throws Exception {
        ImagePlus psf = null;
        try {
            PsfSpec spec = createPsfSpec(resolved, channelIndex, rawCrop, modality);
            psf = getOrCreatePsf(spec, ch.psfModel());
            if (psf == null) {
                return null;
            }
            DeconvolutionEngine engine = resolveEngine(ch.engineKey());
            DeconvParams params = DeconvParams.builder(ch.algorithm())
                    .iterations(ch.iterations())
                    .regularization(ch.regularization())
                    .edgeHandling(EdgeHandling.REFLECT)
                    .build();
            return engine.deconvolve(rawCrop, psf, params);
        } finally {
            closeQuietly(psf);
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

    private static ImagePlus cropStack(ImagePlus image, CropSpec cropSpec, String title) {
        if (image == null || image.getStack() == null) return null;
        CropSpec spec = cropSpec == null ? CropSpec.centre256() : cropSpec;
        ImagePlus result = spec.mode() == CropSpec.Mode.FULL
                ? image.duplicate()
                : spec.apply(image);
        if (result != null) {
            result.setTitle(title);
        }
        return result;
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

    static void requireArtifactIdentitiesForPublication(List<SeriesJob> jobs) {
        if (jobs == null || jobs.isEmpty()) {
            throw new IllegalArgumentException("No deconvolution series jobs were supplied.");
        }
        for (int index = 0; index < jobs.size(); index++) {
            SeriesJob job = jobs.get(index);
            if (job == null || job.artifactIdentity == null
                    || !job.artifactIdentity.isPublishable()
                    || job.artifactIdentity.version != DeconvolutionIO.ArtifactIdentity.VERSION
                    || job.artifactKey == null || job.artifactKey.trim().isEmpty()) {
                throw new IllegalArgumentException("Series job " + (index + 1)
                        + " has no current source/container identity.");
            }
        }
    }

    private void runBatch(String directory, List<SeriesJob> jobs, String[] channelNames, RunSettings settings) {
        try {
            requireArtifactIdentitiesForPublication(jobs);
        } catch (IllegalArgumentException invalidIdentity) {
            recordWarn("Deconvolution stopped before publication: " + invalidIdentity.getMessage());
            return;
        }
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

        BatchStats batchStats = new BatchStats();
        deconvProgressReporter = createProgressReporter(TITLE, jobs.size());
        boolean batchFinished = false;
        try {
            deconvProgressReporter.setPhase("preparing batch");
            logDeconvBatchHeader(directory, outputDir, jobs, channelNames, settings, engine);
            deconvProgressReporter.setPhase("deconvolving stacks");

        Map<String, Integer> legacyBaseNameCounts = legacyBaseNameCounts(jobs);

        for (int jobIndex = 0; jobIndex < jobs.size(); jobIndex++) {
            SeriesJob job = jobs.get(jobIndex);
            int matchingLegacySeries = legacyBaseNameCounts.get(
                    DeconvolutionIO.legacyBaseNameToken(job.baseName)).intValue();
            int scnIndex = jobIndex + 1;
            long started = now();
            List<String> warnings = new ArrayList<String>();
            List<String> channelOutcomes = new ArrayList<String>();
            boolean[] deconvolvedChannelsForMerge = new boolean[channelNames.length];
            boolean selectedChannelFailed = false;
            ImageStats imageStats = new ImageStats();
            AnalysisProgressReporter.WorkHandle imageProgress =
                    beginDeconvImageProgress(scnIndex, jobs.size(), job, "resolving metadata");

            updateDeconvProgress(imageProgress, "validating metadata");
            ResolvedSeriesSettings resolved = resolveSeriesSettings(job.seriesInfo, settings, channelNames.length);
            long peakUsedBytes = usedHeapBytes();
            List<String> missingFields = missingFieldsForSeries(job.seriesInfo, settings, resolved, true);
            if (!missingFields.isEmpty()) {
                warnings.add("Skipped: missing required metadata/overrides: " + joinList(missingFields));
                writeDetailsFile(rootDir, job, settings, resolved, channelNames,
                        channelOutcomes, warnings, started, now() - started, peakUsedBytes);
                String reason = "missing required metadata/overrides: " + joinList(missingFields);
                logDeconvImageSkipped(scnIndex, jobs.size(), job, reason);
                batchStats.imagesSkipped++;
                skipDeconvProgress(imageProgress, deconvImageSummary(
                        scnIndex, jobs.size(), job, imageStats, now() - started, "skipped"));
                continue;
            }
            if (!hasAnySelectedChannel(settings.selectedChannels)) {
                warnings.add("Skipped: no channels selected.");
                writeDetailsFile(rootDir, job, settings, resolved, channelNames,
                        channelOutcomes, warnings, started, now() - started, peakUsedBytes);
                logDeconvImageSkipped(scnIndex, jobs.size(), job, "no channels selected");
                batchStats.imagesSkipped++;
                skipDeconvProgress(imageProgress, deconvImageSummary(
                        scnIndex, jobs.size(), job, imageStats, now() - started, "skipped"));
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
                    logDeconvImageSkipped(scnIndex, jobs.size(), job, nyquist.getMessage());
                    batchStats.imagesSkipped++;
                    skipDeconvProgress(imageProgress, deconvImageSummary(
                            scnIndex, jobs.size(), job, imageStats, now() - started, "skipped"));
                    continue;
                }
                warnings.add(nyquist.getMessage());
                IJ.log("[" + scnIndex + "/" + jobs.size() + "] WARNING "
                        + jobLogLabel(job) + ": " + nyquist.getMessage());
            }

            logDeconvImageStart(scnIndex, jobs.size(), job, channelNames, settings);
            for (int channelIndex = 0; channelIndex < channelNames.length; channelIndex++) {
                if (channelIndex >= settings.selectedChannels.length || !settings.selectedChannels[channelIndex]) {
                    continue;
                }

                String channelName = channelNameAt(channelNames, channelIndex);
                DeconvSettings ch = settings.channel(channelIndex);
                DeconvolutionEngine channelEngine = resolveEngine(ch.engineKey());
                long channelStarted = now();
                long channelPeakUsedBytes = usedHeapBytes();
                List<String> summaryWarnings = new ArrayList<String>();
                if (nyquist != null && nyquist.hasWarning()) {
                    summaryWarnings.add("nyquistUnder");
                }
                if (resolved.sampleRiInferred) {
                    summaryWarnings.add("riInferred");
                }

                File outFile = DeconvolutionIO.deconvFile(rootDir, job.artifactIdentity, channelIndex);
                File manifestFile = DeconvolutionIO.manifestFile(rootDir, job.artifactIdentity);
                updateDeconvProgress(imageProgress, channelProgressLabel(channelIndex, channelNames.length,
                        channelName, "checking output and cache"));

                Map<String, String> hashParams = buildHashParams(settings, job, resolved, channelIndex);
                String paramsHash = DeconvolutionIO.paramsHash(hashParams);
                DeconvManifest.SourceFingerprint sourceFingerprint = sourceFingerprintFor(job);
                File existingOutFile;
                boolean existingFresh;
                try (DeconvolutionFamilyLock.Handle ignored =
                             acquireFamilyLock(rootDir, job.artifactIdentity)) {
                    existingOutFile = DeconvolutionIO.firstExistingFile(
                            DeconvolutionIO.deconvFileReadCandidates(rootDir, job.artifactIdentity,
                                    channelIndex, job.baseName,
                                    DeconvolutionIO.LegacyBasenamePolicy.MIGRATE_IF_UNIQUE,
                                    matchingLegacySeries));
                    existingFresh = existingOutFile != null
                            && DeconvManifest.isFresh(manifestFile, channelIndex, paramsHash,
                                    sourceFingerprint, job.artifactIdentity);
                } catch (IOException lockOrRecoveryFailure) {
                    throw new IllegalStateException("Could not validate deconvolution family for "
                            + job.baseName + " / " + channelName + ".",
                            lockOrRecoveryFailure);
                }

                // Bug A fix: "Skip Existing" must verify the existing mirror was produced with the
                // current parameters AND the current source content (via the freshness manifest),
                // not merely that some output file exists. Changing params + re-running with Skip
                // Existing previously served stale pixels silently.
                if (skipExisting && existingFresh) {
                    deconvolvedChannelsForMerge[channelIndex] = true;
                    imageStats.skippedExistingChannels++;
                    batchStats.skippedExistingChannels++;
                    channelOutcomes.add(channelName + ": skipped existing output");
                    logDeconvChannelOutcome(scnIndex, jobs.size(), job, channelIndex, channelNames.length,
                            channelName, "skipped existing output", channelStarted, existingOutFile.getName());
                    appendSummaryRow(summaryReport,
                            job.baseName,
                            channelName,
                            channelEngine,
                            ch,
                            sizeXYZ(job.seriesInfo),
                            now() - channelStarted,
                            channelPeakUsedBytes,
                            false,
                            summaryWarnings);
                    continue;
                }
                if (skipExisting && existingOutFile != null && verboseLogging) {
                    IJ.log("    - " + channelName
                            + ": existing output is stale (parameters or source changed) - recomputing");
                }

                File cacheFile = DeconvolutionIO.cacheFile(rootDir, paramsHash,
                        job.artifactIdentity, channelIndex);
                File cacheHitFile = DeconvolutionIO.firstFreshFile(job.sourceFile,
                        DeconvolutionIO.cacheFileReadCandidates(rootDir, paramsHash,
                                job.artifactIdentity, channelIndex, job.baseName,
                                DeconvolutionIO.LegacyBasenamePolicy.REJECT, 0));
                boolean preservePriorPairOnFailure = false;

                // Bug B fix: no delete-before-write. saveTiff() and copyFile() both write to a
                // sibling temp and atomically move over the live mirror, so the previous good
                // mirror survives a crash/memory-skip/exception and a concurrent Dropbox reader
                // never observes a missing file that would make it fall back to raw.

                if (settings.useCache && cacheHitFile != null) {
                    boolean cacheHit = false;
                    try {
                        updateDeconvProgress(imageProgress, channelProgressLabel(channelIndex, channelNames.length,
                                channelName, "copying cached output"));
                        try (DeconvolutionFamilyLock.Handle ignored =
                                     acquireFamilyLock(rootDir, job.artifactIdentity)) {
                            File lockedCacheHit = DeconvolutionIO.firstFreshFile(job.sourceFile,
                                    DeconvolutionIO.cacheFileReadCandidates(rootDir, paramsHash,
                                            job.artifactIdentity, channelIndex, job.baseName,
                                            DeconvolutionIO.LegacyBasenamePolicy.REJECT, 0));
                            if (lockedCacheHit == null) {
                                throw new IOException("cache entry changed before publication");
                            }
                            File staged = stageFileCopy(lockedCacheHit, outFile);
                            preservePriorPairOnFailure = true;
                            DeconvolutionChannelPublisher.publish(rootDir, job.artifactIdentity,
                                    channelIndex, staged, new DeconvManifest.ChannelEntry(
                                            paramsHash, hashParams, sourceFingerprint, ch.engineKey(),
                                            DeconvManifest.ENGINE_STAMP_VERSION, -1));
                            recordOutput(outFile, "tif");
                        }
                        channelOutcomes.add(channelName + ": cache hit");
                        deconvolvedChannelsForMerge[channelIndex] = true;
                        imageStats.cacheHitChannels++;
                        batchStats.cacheHitChannels++;
                        logDeconvChannelOutcome(scnIndex, jobs.size(), job, channelIndex, channelNames.length,
                                channelName, "cache hit", channelStarted, cacheHitFile.getName());
                        cacheHit = true;
                    } catch (IOException e) {
                        warnings.add("Cache copy failed for " + channelName + ": " + e.getMessage());
                        summaryWarnings.add("cacheCopyFailed");
                        recordWarn("Cache copy failed for " + channelName
                                + ": " + e.getMessage());
                    }
                    if (cacheHit) {
                        appendSummaryRow(summaryReport,
                                job.baseName,
                                channelName,
                                channelEngine,
                                ch,
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
                int[] windowsBeforeDeconvolution = null;
                boolean channelWritten = false;
                String sizeXYZ = sizeXYZ(job.seriesInfo);
                try {
                    updateDeconvProgress(imageProgress, channelProgressLabel(channelIndex, channelNames.length,
                            channelName, "opening source stack"));
                    channelStack = openSeriesChannel(directory, job.seriesIndex, channelIndex);
                    channelPeakUsedBytes = Math.max(channelPeakUsedBytes, usedHeapBytes());
                    if (channelStack == null) {
                        channelOutcomes.add(channelName + ": failed (channel could not be opened)");
                        summaryWarnings.add("openFailed");
                        selectedChannelFailed = true;
                        imageStats.failedChannels++;
                        batchStats.failedChannels++;
                        logDeconvChannelOutcome(scnIndex, jobs.size(), job, channelIndex, channelNames.length,
                                channelName, "failed", channelStarted, "channel could not be opened");
                        appendSummaryRow(summaryReport,
                                job.baseName,
                                channelName,
                                channelEngine,
                                ch,
                                sizeXYZ,
                                now() - channelStarted,
                                channelPeakUsedBytes,
                                false,
                                summaryWarnings);
                        continue;
                    }
                    sizeXYZ = sizeXYZ(channelStack);

                    long requiredBytes = requiredFor3DDeconv(channelStack);
                    long availableBytes = estimatedAvailableMemory();
                    if (requiredBytes > availableBytes) {
                        String message = "memory skip (" + humanMiB(requiredBytes) + " MiB required, "
                                + humanMiB(availableBytes) + " MiB available)";
                        channelOutcomes.add(channelName + ": " + message);
                        warnings.add(channelName + " " + message);
                        summaryWarnings.add("memorySkip");
                        selectedChannelFailed = true;
                        imageStats.skippedChannels++;
                        batchStats.skippedChannels++;
                        logDeconvChannelOutcome(scnIndex, jobs.size(), job, channelIndex, channelNames.length,
                                channelName, "skipped", channelStarted, message);
                        appendSummaryRow(summaryReport,
                                job.baseName,
                                channelName,
                                channelEngine,
                                ch,
                                sizeXYZ,
                                now() - channelStarted,
                                channelPeakUsedBytes,
                                false,
                                summaryWarnings);
                        continue;
                    }

                    if (sanitizeInputForDeconvolution(channelStack)) {
                        String message = "input contained negative or non-finite pixels; sanitized before deconvolution";
                        warnings.add(channelName + " " + message);
                        summaryWarnings.add("inputSanitized");
                        if (verboseLogging) {
                            IJ.log("    - " + channelName + ": " + message);
                        }
                    }

                    updateDeconvProgress(imageProgress, channelProgressLabel(channelIndex, channelNames.length,
                            channelName, "building PSF"));
                    PsfSpec spec = createPsfSpec(resolved, channelIndex, channelStack, settings.scopeModality);

                    psf = getOrCreatePsf(spec, ch.psfModel());
                    channelPeakUsedBytes = Math.max(channelPeakUsedBytes, usedHeapBytes());
                    if (psf == null) {
                        String message = "PSF synthesis failed";
                        channelOutcomes.add(channelName + ": " + message);
                        warnings.add(channelName + " " + message);
                        summaryWarnings.add("psfFailed");
                        selectedChannelFailed = true;
                        imageStats.failedChannels++;
                        batchStats.failedChannels++;
                        logDeconvChannelOutcome(scnIndex, jobs.size(), job, channelIndex, channelNames.length,
                                channelName, "failed", channelStarted, message);
                        appendSummaryRow(summaryReport,
                                job.baseName,
                                channelName,
                                channelEngine,
                                ch,
                                sizeXYZ,
                                now() - channelStarted,
                                channelPeakUsedBytes,
                                false,
                                summaryWarnings);
                        continue;
                    }

                    if (writtenPsfHashes.add(paramsHash)) {
                        writePsfPreview(psf, spec, ch.psfModel(), outputDir);
                        if (verboseLogging) {
                            IJ.log("    - PSF preview written for " + channelName
                                    + " (" + ch.psfModel().displayName() + ")");
                        }
                    }

                    DeconvParams params = DeconvParams.builder(ch.algorithm())
                            .iterations(ch.iterations())
                            .regularization(ch.regularization())
                            .edgeHandling(EdgeHandling.REFLECT)
                            .build();
                    updateDeconvProgress(imageProgress, channelProgressLabel(channelIndex, channelNames.length,
                            channelName, "running " + channelEngine.displayName()));
                    windowsBeforeDeconvolution = snapshotOpenImageWindows();
                    deconvolved = channelEngine.deconvolve(channelStack, psf, params);
                    ImagePlus trimmedDeconvolved = trimTrailingBlankDeconvolutionSlice(
                            deconvolved, channelStack, channelName, warnings, summaryWarnings);
                    if (trimmedDeconvolved != deconvolved) {
                        closeQuietly(deconvolved);
                        deconvolved = trimmedDeconvolved;
                    }
                    channelPeakUsedBytes = Math.max(channelPeakUsedBytes, usedHeapBytes());
                    updateDeconvProgress(imageProgress, channelProgressLabel(channelIndex, channelNames.length,
                            channelName, "saving TIFF"));
                    try (DeconvolutionFamilyLock.Handle ignored =
                                 acquireFamilyLock(rootDir, job.artifactIdentity)) {
                        File staged = stageTiff(deconvolved, outFile);
                        preservePriorPairOnFailure = true;
                        DeconvolutionChannelPublisher.publish(rootDir, job.artifactIdentity,
                                channelIndex, staged, new DeconvManifest.ChannelEntry(
                                        paramsHash, hashParams, sourceFingerprint, ch.engineKey(),
                                        DeconvManifest.ENGINE_STAMP_VERSION,
                                        deconvolved == null ? -1 : deconvolved.getStackSize()));
                        recordOutput(outFile, "tif");
                        if (settings.useCache) {
                            try {
                                copyFile(outFile, cacheFile);
                            } catch (IOException e) {
                                warnings.add("Cache write failed for " + channelName + ": " + e.getMessage());
                                recordWarn("Cache write failed for " + channelName
                                        + ": " + e.getMessage());
                                summaryWarnings.add("cacheWriteFailed");
                            }
                        }
                    }
                    channelWritten = true;
                    deconvolvedChannelsForMerge[channelIndex] = true;
                    channelOutcomes.add(channelName + ": written (" + paramsHash + ")");
                    imageStats.writtenChannels++;
                    batchStats.writtenChannels++;
                    logDeconvChannelOutcome(scnIndex, jobs.size(), job, channelIndex, channelNames.length,
                            channelName, "written", channelStarted,
                            sizeXYZ + ", hash " + paramsHash + ", peak " + humanMiB(channelPeakUsedBytes) + " MiB");
                } catch (DeconvolutionException e) {
                    selectedChannelFailed = true;
                    warnings.add(channelName + " failed: " + e.getMessage());
                    channelOutcomes.add(channelName + ": failed");
                    summaryWarnings.add("failed");
                    String message = "Deconvolution failed [" + job.baseName + ", "
                            + channelName + "]: " + e.getMessage();
                    imageStats.failedChannels++;
                    batchStats.failedChannels++;
                    logDeconvChannelOutcome(scnIndex, jobs.size(), job, channelIndex, channelNames.length,
                            channelName, "failed", channelStarted, e.getMessage());
                    recordError(message, e);
                } catch (Exception e) {
                    selectedChannelFailed = true;
                    warnings.add(channelName + " failed: " + e.getMessage());
                    channelOutcomes.add(channelName + ": failed");
                    summaryWarnings.add("failed");
                    String message = "Deconvolution failed [" + job.baseName + ", "
                            + channelName + "]: " + e.getMessage();
                    imageStats.failedChannels++;
                    batchStats.failedChannels++;
                    logDeconvChannelOutcome(scnIndex, jobs.size(), job, channelIndex, channelNames.length,
                            channelName, "failed", channelStarted, e.getMessage());
                    recordError(message, e);
                } finally {
                    closeQuietly(deconvolved);
                    closeQuietly(psf);
                    closeQuietly(channelStack);
                    closeStrayDeconvolutionWindows(windowsBeforeDeconvolution);
                    if (!channelWritten && !preservePriorPairOnFailure) {
                        // Stage 01 keeps a good mirror across a temp-and-move recompute, but a FAILED
                        // recompute must not leave a STALE per-channel mirror on disk: with no fresh
                        // manifest a consumer's mtime fallback would treat it as fresh and serve stale
                        // pixels. Remove it only when it is not fresh (a genuinely-fresh mirror being
                        // force-recomputed is preserved).
                        removeStalePerChannelOutputOnFailure(rootDir, outFile, manifestFile,
                                existingOutFile, job.artifactIdentity, channelIndex,
                                paramsHash, sourceFingerprint);
                    }
                }
                peakUsedBytes = Math.max(peakUsedBytes, channelPeakUsedBytes);
                appendSummaryRow(summaryReport,
                        job.baseName,
                        channelName,
                        channelEngine,
                        ch,
                        sizeXYZ,
                        now() - channelStarted,
                        channelPeakUsedBytes,
                        false,
                        summaryWarnings);
            }

            if (selectedChannelFailed) {
                warnings.add("Merged deconvolved output skipped because at least one selected channel failed.");
                channelOutcomes.add("Merged: skipped (selected channel failed)");
                imageStats.mergeSkipped = true;
                batchStats.mergeSkipped++;
                updateDeconvProgress(imageProgress, "merged output skipped");
                IJ.log("[" + scnIndex + "/" + jobs.size() + "] Merge skipped for "
                        + jobLogLabel(job) + ": at least one selected channel failed.");
                try {
                    try (DeconvolutionFamilyLock.Handle ignored =
                                 acquireFamilyLock(rootDir, job.artifactIdentity)) {
                        deleteFilesIfExist(DeconvolutionIO.mergedDeconvFileReadCandidates(
                                rootDir, job.artifactIdentity, job.baseName,
                                DeconvolutionIO.LegacyBasenamePolicy.MIGRATE_IF_UNIQUE,
                                matchingLegacySeries));
                    }
                } catch (IOException e) {
                    warnings.add("Could not remove stale merged deconvolved output: " + e.getMessage());
                    String message = "Could not remove stale merged deconvolved output for "
                            + job.baseName + ": " + e.getMessage();
                    IJ.log(message);
                    recordWarn(message);
                }
            } else {
                try {
                    updateDeconvProgress(imageProgress, "writing merged output");
                    MergeOutcome mergeOutcome = writeMergedOutput(directory, rootDir, job,
                            channelNames.length, deconvolvedChannelsForMerge,
                            matchingLegacySeries);
                    if (mergeOutcome == MergeOutcome.WRITTEN) {
                        imageStats.mergeWritten = true;
                        batchStats.mergeWritten++;
                        channelOutcomes.add("Merged: written");
                        IJ.log("[" + scnIndex + "/" + jobs.size() + "] Merge written for "
                                + jobLogLabel(job) + " (" + formatDurationCompact(now() - started) + " elapsed).");
                    } else if (mergeOutcome == MergeOutcome.SKIPPED_EXISTING) {
                        imageStats.mergeSkippedExisting = true;
                        batchStats.mergeSkippedExisting++;
                        channelOutcomes.add("Merged: skipped existing output");
                        IJ.log("[" + scnIndex + "/" + jobs.size() + "] Merge skipped for "
                                + jobLogLabel(job) + ": existing output.");
                    } else {
                        imageStats.mergeSkipped = true;
                        batchStats.mergeSkipped++;
                        channelOutcomes.add("Merged: skipped");
                    }
                } catch (Exception e) {
                    warnings.add("Merged deconvolved output failed: " + e.getMessage());
                    channelOutcomes.add("Merged: failed");
                    imageStats.mergeFailed = true;
                    batchStats.mergeFailed++;
                    String message = "Could not write merged deconvolved output for "
                            + job.baseName + ": " + e.getMessage();
                    IJ.log("[" + scnIndex + "/" + jobs.size() + "] Merge failed for "
                            + jobLogLabel(job) + ": " + e.getMessage());
                    recordError(message, e);
                }
            }

            long elapsed = now() - started;
            writeDetailsFile(rootDir, job, settings, resolved, channelNames,
                    channelOutcomes, warnings, started, elapsed, peakUsedBytes);
            boolean imageFailed = selectedChannelFailed || imageStats.mergeFailed;
            logDeconvImageFinished(scnIndex, jobs.size(), job, imageStats, elapsed, imageFailed);
            if (imageFailed) {
                batchStats.imagesFailed++;
                failDeconvProgress(imageProgress, deconvImageSummary(
                        scnIndex, jobs.size(), job, imageStats, elapsed, "finished with failures"));
            } else {
                batchStats.imagesCompleted++;
                completeDeconvProgress(imageProgress, deconvImageSummary(
                        scnIndex, jobs.size(), job, imageStats, elapsed, "complete"));
            }
        }

        if (summaryReport != null) {
            try {
                summaryReport.finish(now() - batchStarted);
                recordOutput(summaryReport.getReportFile(), "txt");
            } catch (IOException e) {
                String message = "Could not finalize deconvolution summary report: " + e.getMessage();
                IJ.log(message);
                recordWarn(message);
            }
        }
        AsyncImageSaver.waitForAll();

        // Non-blocking completion signal. A modal dialog here would stall
        // unattended batch runs until a human clicked OK.
            String batchSummary = batchStats.summary(jobs.size(), now() - batchStarted);
            deconvProgressReporter.finish("3D deconvolution finished: " + batchSummary);
            IJ.log("3D deconvolution finished: " + batchSummary);
        IJ.showStatus("3D deconvolution finished.");
            batchFinished = true;
        } finally {
            if (!batchFinished) {
                deconvProgressReporter.finish("3D deconvolution stopped before completion.");
            }
            deconvProgressReporter = AnalysisProgressReporter.disabled();
        }
    }

    private AnalysisProgressReporter createProgressReporter(String analysisName, int totalUnits) {
        return AnalysisProgressReporter.create(analysisName, totalUnits,
                AnalysisProgressReporter.imageJSink(),
                new AnalysisProgressReporter.Recorder() {
                    @Override public void recordProgressSnapshot(Map<String, Object> snapshot) {
                        if (runRecordContext != null) {
                            runRecordContext.recordProgressSnapshot(snapshot);
                        }
                    }
                });
    }

    private void logDeconvBatchHeader(String directory,
                                      File outputDir,
                                      List<SeriesJob> jobs,
                                      String[] channelNames,
                                      RunSettings settings,
                                      DeconvolutionEngine engine) {
        int imageCount = jobs == null ? 0 : jobs.size();
        int selectedChannels = countSelectedChannels(channelNames, settings.selectedChannels);
        IJ.log("==========================================================");
        IJ.log("3D DECONVOLUTION");
        IJ.log("==========================================================");
        IJ.log("Directory: " + directory);
        IJ.log("Output folder: " + (outputDir == null ? "" : outputDir.getAbsolutePath()));
        IJ.log("Images: " + imageCount);
        IJ.log("Selected channels: " + selectedChannelList(channelNames, settings.selectedChannels)
                + " (" + selectedChannels + "/" + safeLength(channelNames) + ")");
        boolean mixedSettings = hasMixedSelectedChannelSettings(channelNames, settings);
        if (mixedSettings) {
            IJ.log("Engine: per-channel");
            IJ.log("Algorithm: per-channel");
            IJ.log("PSF model: per-channel");
        } else {
            IJ.log("Engine: " + (engine == null ? settings.engineKey : engine.displayName()));
            String parameterText = parameterSummary(engine, settings.channel(-1));
            IJ.log("Algorithm: " + (settings.algorithm == null ? "" : settings.algorithm.displayName())
                    + (parameterText.isEmpty() ? "" : ", " + parameterText));
            IJ.log("PSF model: " + (settings.psfModel == null ? "" : settings.psfModel.displayName()));
        }
        logPerChannelSettings(channelNames, settings);
        IJ.log("Scope modality: " + (settings.scopeModality == null ? "" : settings.scopeModality.displayName()));
        IJ.log("Cache: " + (settings.useCache ? "enabled" : "disabled")
                + "; skip existing: " + skipExisting
                + "; strict Nyquist: " + settings.strictNyquist);
    }

    private void logPerChannelSettings(String[] channelNames, RunSettings settings) {
        List<String> lines = perChannelSettingsLines(channelNames, settings);
        if (lines.isEmpty()) {
            return;
        }
        IJ.log("Per-channel settings:");
        for (String line : lines) {
            IJ.log("  " + line);
        }
    }

    private AnalysisProgressReporter.WorkHandle beginDeconvImageProgress(int scnIndex,
                                                                         int totalImages,
                                                                         SeriesJob job,
                                                                         String detail) {
        return deconvProgressReporter.begin(
                "image " + scnIndex + "/" + totalImages + ": " + jobLogLabel(job),
                detail);
    }

    private void updateDeconvProgress(AnalysisProgressReporter.WorkHandle handle, String detail) {
        deconvProgressReporter.update(handle, detail);
    }

    private void completeDeconvProgress(AnalysisProgressReporter.WorkHandle handle, String summary) {
        deconvProgressReporter.complete(handle, summary);
    }

    private void skipDeconvProgress(AnalysisProgressReporter.WorkHandle handle, String summary) {
        deconvProgressReporter.skip(handle, summary);
    }

    private void failDeconvProgress(AnalysisProgressReporter.WorkHandle handle, String summary) {
        deconvProgressReporter.fail(handle, summary);
    }

    private void logDeconvImageStart(int scnIndex,
                                     int totalImages,
                                     SeriesJob job,
                                     String[] channelNames,
                                     RunSettings settings) {
        String size = sizeXYZ(job == null ? null : job.seriesInfo);
        IJ.log("[" + scnIndex + "/" + totalImages + "] Deconvolving "
                + jobLogLabel(job) + (size.isEmpty() ? "" : " (" + size + ")"));
        IJ.log("  Channels: " + selectedChannelList(channelNames, settings.selectedChannels));
        if (verboseLogging && job != null) {
            IJ.log("  Source: " + (job.sourceFile == null ? "" : job.sourceFile.getAbsolutePath())
                    + " | series " + job.sourceSeriesIndex);
        }
    }

    private static void logDeconvImageSkipped(int scnIndex,
                                              int totalImages,
                                              SeriesJob job,
                                              String reason) {
        IJ.log("[" + scnIndex + "/" + totalImages + "] Skipped "
                + jobLogLabel(job) + ": " + safeText(reason));
    }

    private void logDeconvImageFinished(int scnIndex,
                                        int totalImages,
                                        SeriesJob job,
                                        ImageStats stats,
                                        long elapsedMs,
                                        boolean failed) {
        IJ.log("[" + scnIndex + "/" + totalImages + "] "
                + (failed ? "Finished with warnings/failures: " : "Complete: ")
                + jobLogLabel(job) + " in " + formatDurationCompact(elapsedMs)
                + " (" + stats.summary() + ")");
    }

    private void logDeconvChannelOutcome(int scnIndex,
                                         int totalImages,
                                         SeriesJob job,
                                         int channelIndex,
                                         int channelCount,
                                         String channelName,
                                         String outcome,
                                         long startedMillis,
                                         String detail) {
        String elapsed = formatDurationCompact(now() - startedMillis);
        StringBuilder sb = new StringBuilder();
        sb.append("[").append(scnIndex).append("/").append(totalImages).append("] ");
        sb.append("Channel ").append(channelIndex + 1).append("/").append(channelCount)
                .append(" ").append(channelName).append(" ").append(outcome);
        sb.append(" in ").append(elapsed);
        String safeDetail = safeText(detail);
        if (!safeDetail.isEmpty()) {
            sb.append(" (").append(safeDetail).append(")");
        }
        IJ.log(sb.toString());
        IJ.showStatus("3D deconvolution " + scnIndex + "/" + totalImages
                + ": " + channelName + " " + outcome);
    }

    private static String deconvImageSummary(int scnIndex,
                                             int totalImages,
                                             SeriesJob job,
                                             ImageStats stats,
                                             long elapsedMs,
                                             String outcome) {
        return "image " + scnIndex + "/" + totalImages + " "
                + safeText(outcome) + ": " + jobLogLabel(job)
                + " (" + formatDurationCompact(elapsedMs) + "; " + stats.summary() + ")";
    }

    private static String channelProgressLabel(int channelIndex,
                                               int channelCount,
                                               String channelName,
                                               String detail) {
        return "channel " + (channelIndex + 1) + "/" + channelCount
                + " " + channelName + ": " + safeText(detail);
    }

    private static String jobLogLabel(SeriesJob job) {
        if (job == null) return "unknown image";
        if (job.displayName != null && !job.displayName.trim().isEmpty()) {
            return job.displayName.trim();
        }
        if (job.baseName != null && !job.baseName.trim().isEmpty()) {
            return job.baseName.trim();
        }
        return "series " + job.seriesIndex;
    }

    private static String channelNameAt(String[] channelNames, int channelIndex) {
        if (channelNames != null
                && channelIndex >= 0
                && channelIndex < channelNames.length
                && channelNames[channelIndex] != null
                && !channelNames[channelIndex].trim().isEmpty()) {
            return channelNames[channelIndex].trim();
        }
        return "Channel " + (channelIndex + 1);
    }

    private static int countSelectedChannels(String[] channelNames, boolean[] selected) {
        int channelCount = safeLength(channelNames);
        int count = 0;
        for (int i = 0; i < channelCount; i++) {
            if (selected != null && i < selected.length && selected[i]) {
                count++;
            }
        }
        return count;
    }

    private static int safeLength(Object[] values) {
        return values == null ? 0 : values.length;
    }

    private static String safeText(String value) {
        return value == null ? "" : value.trim();
    }

    protected List<SeriesJob> listSeriesJobs(String directory) throws Exception {
        DeferredImageSupplier supplier = createImageSupplier(directory);
        List<SeriesMeta> metas = readAllInputMetadata(directory);
        File projectRoot = new File(directory).getCanonicalFile();
        List<SeriesJob> jobs = new ArrayList<SeriesJob>();
        Map<String, DeconvManifest.SourceFingerprint> sourceFingerprints =
                new HashMap<String, DeconvManifest.SourceFingerprint>();
        for (SeriesMeta meta : metas) {
            if (meta == null) {
                continue;
            }
            if (ImageNameParser.isPreviewSeriesName(meta.name)) {
                continue;
            }
            File sourceFile = sourceFileForSeries(supplier, meta.index);
            int sourceSeriesIndex = sourceSeriesIndexForSeries(supplier, meta.index);
            MetadataDiagnostics.SeriesInfo info;
            try {
                info = readSeriesInfo(sourceFile, sourceSeriesIndex);
            } catch (Exception e) {
                IJ.log(TITLE + ": detailed metadata unavailable for series " + (sourceSeriesIndex + 1)
                        + " (" + nullToEmpty(meta.name) + "). Manual deconvolution fields will be requested. "
                        + e.getMessage());
                info = fallbackSeriesInfo(sourceFile, meta.withIndex(sourceSeriesIndex));
            }
            if (info == null) continue;
            if (supplier != null && supplier.isTiffFolderMode()
                    && meta.name != null && !meta.name.trim().isEmpty()) {
                info.imageName = meta.name;
            } else if (info.imageName == null || info.imageName.trim().isEmpty()) {
                info.imageName = meta.name;
            }
            String baseName = ImageNameParser.extractBioFormatsSeriesName(info.imageName);
            if (baseName == null || baseName.trim().isEmpty()) {
                baseName = "Series_" + (sourceSeriesIndex + 1);
            }
            if (sourceFile == null) {
                throw new IOException("Deconvolution source file is missing for series "
                        + (sourceSeriesIndex + 1) + ".");
            }
            String sourceKey = sourceFile.getCanonicalPath();
            DeconvManifest.SourceFingerprint fingerprint = sourceFingerprints.get(sourceKey);
            if (fingerprint == null) {
                fingerprint = DeconvManifest.SourceFingerprint.of(sourceFile);
                sourceFingerprints.put(sourceKey, fingerprint);
            }
            DeconvolutionIO.ArtifactIdentity artifactIdentity =
                    DeconvolutionIO.ArtifactIdentity.of(projectRoot, sourceFile, fingerprint,
                            sourceSeriesIndex, baseName.trim());
            jobs.add(new SeriesJob(sourceFile, meta.index, sourceSeriesIndex,
                    baseName.trim(), info, artifactIdentity));
        }
        return jobs;
    }

    protected DeferredImageSupplier createImageSupplier(String directory) throws Exception {
        return ImageSourceDispatcher.createSupplier(directory);
    }

    protected List<SeriesMeta> readAllInputMetadata(String directory) throws Exception {
        return ImageSourceDispatcher.readAllMetadata(directory);
    }

    protected File sourceFileForSeries(DeferredImageSupplier supplier, int seriesIndex) {
        if (supplier == null) {
            return null;
        }
        try {
            return supplier.getContainerFileForSeries(seriesIndex);
        } catch (RuntimeException e) {
            return supplier.getContainerFile();
        }
    }

    protected int sourceSeriesIndexForSeries(DeferredImageSupplier supplier, int seriesIndex) {
        if (supplier == null) {
            return seriesIndex;
        }
        try {
            return supplier.getLocalSeriesIndexForSeries(seriesIndex);
        } catch (RuntimeException e) {
            return seriesIndex;
        }
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
        DeferredImageSupplier supplier = createImageSupplier(directory);
        File source = null;
        try {
            source = supplier.getContainerFileForSeries(seriesIndex);
        } catch (Exception ignored) {
        }
        AnalysisRunContext.InputHandle inputHandle = recordInputStart(source, seriesIndex);
        long started = now();
        try {
            ImagePlus image = supplier.openSeriesMaterializedChannel(seriesIndex, channelIndex);
            recordInputEnd(inputHandle, image == null ? "failed" : "processed", started);
            return image;
        } catch (Exception e) {
            recordInputEnd(inputHandle, "failed", started);
            throw e;
        }
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
        return FeatureDependencyGate.gate(DependencyId.EPFL_PSF_GENERATOR_RUNTIME,
                TITLE, "EPFL PSF synthesis");
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
        File temp = File.createTempFile(target.getName() + "-", ".tmp", target.getParentFile());
        boolean moved = false;
        try {
            FileSaver saver = new FileSaver(image);
            boolean ok = image.getStackSize() > 1
                    ? saver.saveAsTiffStack(temp.getAbsolutePath())
                    : saver.saveAsTiff(temp.getAbsolutePath());
            if (!ok) {
                throw new IOException("Could not save TIFF " + target.getAbsolutePath());
            }
            moveReplacing(temp, target);
            moved = true;
            recordOutput(target, "tif");
        } finally {
            if (!moved) {
                Files.deleteIfExists(temp.toPath());
            }
        }
    }

    private File stageTiff(ImagePlus image, File target) throws IOException {
        ensureDirectory(target.getParentFile());
        File staged = File.createTempFile(target.getName() + "-stage-", ".tmp",
                target.getParentFile());
        boolean complete = false;
        try {
            FileSaver saver = new FileSaver(image);
            boolean ok = image.getStackSize() > 1
                    ? saver.saveAsTiffStack(staged.getAbsolutePath())
                    : saver.saveAsTiff(staged.getAbsolutePath());
            if (!ok) throw new IOException("Could not stage TIFF " + target.getAbsolutePath());
            complete = true;
            return staged;
        } finally {
            if (!complete) Files.deleteIfExists(staged.toPath());
        }
    }

    private File stageFileCopy(File source, File target) throws IOException {
        ensureDirectory(target.getParentFile());
        File staged = File.createTempFile(target.getName() + "-stage-", ".tmp",
                target.getParentFile());
        boolean complete = false;
        try {
            Files.copy(source.toPath(), staged.toPath(), StandardCopyOption.REPLACE_EXISTING);
            complete = true;
            return staged;
        } finally {
            if (!complete) Files.deleteIfExists(staged.toPath());
        }
    }

    private ImagePlus trimTrailingBlankDeconvolutionSlice(ImagePlus image,
                                                          ImagePlus sourceImage,
                                                          String channelName,
                                                          List<String> warnings,
                                                          List<String> summaryWarnings) {
        if (!hasTrailingBlankSliceIntroducedByDeconvolution(image, sourceImage)) {
            return image;
        }
        ImagePlus trimmed = copyWithoutLastSlice(image);
        if (trimmed == image) {
            return image;
        }
        String message = "removed trailing blank deconvolution slice; downstream analyses will use "
                + trimmed.getStackSize() + " of " + image.getStackSize() + " slices";
        String safeChannelName = safeChannelName(channelName);
        if (warnings != null) {
            warnings.add(safeChannelName + " " + message);
        }
        if (summaryWarnings != null) {
            summaryWarnings.add("trimmedTrailingBlankSlice");
        }
        IJ.log("[FLASH] " + safeChannelName + ": " + message);
        return trimmed;
    }

    private static String safeChannelName(String channelName) {
        String value = channelName == null ? "" : channelName.trim();
        return value.isEmpty() ? "Channel" : value;
    }

    private static boolean hasTrailingBlankSliceWithEarlierSignal(ImagePlus image) {
        if (image == null || image.getStackSize() <= 1) {
            return false;
        }
        int lastSlice = image.getStackSize();
        if (!isBlankSlice(image, lastSlice)) {
            return false;
        }
        for (int slice = 1; slice < lastSlice; slice++) {
            if (!isBlankSlice(image, slice)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasTrailingBlankSliceIntroducedByDeconvolution(ImagePlus image,
                                                                          ImagePlus sourceImage) {
        if (!hasTrailingBlankSliceWithEarlierSignal(image)
                || sourceImage == null
                || sourceImage.getStackSize() != image.getStackSize()) {
            return false;
        }
        return !isBlankSlice(sourceImage, sourceImage.getStackSize());
    }

    private static boolean isBlankSlice(ImagePlus image, int slice) {
        ImageProcessor processor = processorForSlice(image, slice);
        if (processor == null) {
            return true;
        }
        int pixelCount = processor.getPixelCount();
        for (int i = 0; i < pixelCount; i++) {
            double value = processor.getf(i);
            if (Double.isFinite(value) && Math.abs(value) > 1.0e-12) {
                return false;
            }
        }
        return true;
    }

    private static ImageProcessor processorForSlice(ImagePlus image, int slice) {
        if (image == null) {
            return null;
        }
        ImageStack stack = image.getStack();
        if (stack == null || stack.getSize() <= 1) {
            return image.getProcessor();
        }
        int clamped = Math.max(1, Math.min(slice, stack.getSize()));
        return stack.getProcessor(clamped);
    }

    private static ImagePlus copyWithoutLastSlice(ImagePlus image) {
        if (image == null) {
            return image;
        }
        return copyFirstSlices(image, image.getStackSize() - 1);
    }

    private static ImagePlus copyFirstSlices(ImagePlus image, int outputSlices) {
        if (image == null || outputSlices <= 0 || outputSlices >= image.getStackSize()) {
            return image;
        }
        int width = Math.max(1, image.getWidth());
        int height = Math.max(1, image.getHeight());
        ImageStack sourceStack = image.getStack();
        ImageStack trimmedStack = new ImageStack(width, height);
        for (int slice = 1; slice <= outputSlices; slice++) {
            ImageProcessor processor = sourceStack == null
                    ? image.getProcessor()
                    : sourceStack.getProcessor(slice);
            String label = sourceStack == null ? null : sourceStack.getSliceLabel(slice);
            trimmedStack.addSlice(label, processor.duplicate());
        }
        ImagePlus trimmed = new ImagePlus(image.getTitle(), trimmedStack);
        if (image.getCalibration() != null) {
            trimmed.setCalibration(image.getCalibration().copy());
        }
        trimmed.setDimensions(1, Math.max(1, outputSlices), 1);
        trimmed.setOpenAsHyperStack(image.isHyperStack());
        return trimmed;
    }

    private MergeOutcome writeMergedOutput(String directory,
                                           File rootDir,
                                           SeriesJob job,
                                           int channelCount,
                                           boolean[] deconvolvedChannels,
                                           int matchingLegacySeries) throws Exception {
        if (job == null || channelCount <= 0) return MergeOutcome.SKIPPED;
        try (DeconvolutionFamilyLock.Handle ignored =
                     acquireFamilyLock(rootDir, job.artifactIdentity)) {
            return writeMergedOutputLocked(directory, rootDir, job, channelCount,
                    deconvolvedChannels, matchingLegacySeries);
        }
    }

    private MergeOutcome writeMergedOutputLocked(String directory,
                                                 File rootDir,
                                                 SeriesJob job,
                                                 int channelCount,
                                                 boolean[] deconvolvedChannels,
                                                 int matchingLegacySeries) throws Exception {
        if (job == null || channelCount <= 0) return MergeOutcome.SKIPPED;

        File mergedFile = DeconvolutionIO.mergedDeconvFile(rootDir, job.artifactIdentity);
        File manifestFile = DeconvolutionIO.manifestFile(rootDir, job.artifactIdentity);
        File existingMergedFile = DeconvolutionIO.firstExistingFile(
                DeconvolutionIO.mergedDeconvFileReadCandidates(rootDir, job.artifactIdentity,
                        job.baseName, DeconvolutionIO.LegacyBasenamePolicy.MIGRATE_IF_UNIQUE,
                        matchingLegacySeries));
        DeconvManifest.SourceFingerprint mergeSource = sourceFingerprintFor(job);
        List<Integer> mergedChannels = deconvolvedChannelList(channelCount, deconvolvedChannels);
        // Stage 18 (params-staleness): the merged _deconv.tif has no fingerprint of its own, so it is
        // trustworthy ONLY via its dedicated content record in the manifest, which ties it to the exact
        // per-channel params-hashes it was composed from. Skip the rewrite only when that record proves
        // the existing merged file already reflects the CURRENT per-channel mirrors; a params-only rerun
        // advances a per-channel hash so the record no longer matches and the merge is rewritten. This is
        // NOT an mtime comparison (a cache-hit copy preserves an older mtime; a failed rewrite leaves a
        // stale file), so it is sound across cache hits, partial reruns, and Dropbox re-hydration.
        if (skipExisting && existingMergedFile != null
                && DeconvManifest.load(manifestFile).isMergedFresh(mergeSource, mergedChannels,
                        null, job.artifactIdentity)) {
            return MergeOutcome.SKIPPED_EXISTING;
        }

        ImagePlus[] channelImages = new ImagePlus[channelCount];
        try {
            boolean hasDeconvolvedChannel = false;
            for (int channelIndex = 0; channelIndex < channelCount; channelIndex++) {
                File channelFile = DeconvolutionIO.firstExistingFile(
                        DeconvolutionIO.deconvFileReadCandidates(rootDir, job.artifactIdentity,
                                channelIndex, job.baseName,
                                DeconvolutionIO.LegacyBasenamePolicy.MIGRATE_IF_UNIQUE,
                                matchingLegacySeries));
                boolean useDeconvolvedChannel = deconvolvedChannels != null
                        && channelIndex < deconvolvedChannels.length
                        && deconvolvedChannels[channelIndex]
                        && channelFile != null
                        && channelFile.isFile();
                if (useDeconvolvedChannel) {
                    channelImages[channelIndex] = new Opener().openImage(channelFile.getAbsolutePath());
                    hasDeconvolvedChannel = true;
                } else {
                    channelImages[channelIndex] = openSeriesChannel(directory, job.seriesIndex, channelIndex);
                }
                if (channelImages[channelIndex] == null) {
                    throw new IOException("Could not load channel " + channelIndex + " for merged output.");
                }
                channelImages[channelIndex].setTitle("C" + (channelIndex + 1) + "-" + job.baseName);
            }
            if (hasDeconvolvedChannel) {
                normalizeMergeDepth(channelImages);
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
                // Record the merged content stamp ONLY after a successful write, so a failed/partial
                // rewrite never leaves a record that vouches for a stale merged file.
                writeMergedManifestRecord(manifestFile, job.artifactIdentity,
                        mergeSource, mergedChannels);
                return MergeOutcome.WRITTEN;
            } finally {
                closeQuietly(merged);
            }
        } finally {
            for (ImagePlus channelImage : channelImages) {
                closeQuietly(channelImage);
            }
        }
    }

    /** The channel indices that carry a deconvolved mirror in this merge (the merged content set). */
    private static List<Integer> deconvolvedChannelList(int channelCount, boolean[] deconvolvedChannels) {
        List<Integer> list = new ArrayList<Integer>();
        for (int c = 0; c < channelCount; c++) {
            if (deconvolvedChannels != null && c < deconvolvedChannels.length && deconvolvedChannels[c]) {
                list.add(Integer.valueOf(c));
            }
        }
        return list;
    }

    /**
     * Stamp the manifest's merged record with the current source fingerprint and, per deconvolved
     * channel, the params-hash of the mirror the manifest now describes — so a consumer can prove the
     * merged {@code _deconv.tif} reflects the current per-channel mirrors (see
     * {@code DeconvManifest#isMergedFresh}). A channel with no per-channel entry is simply omitted,
     * which makes the merged record fail the consumer check for that channel (safe: per-channel compose).
     */
    private void writeMergedManifestRecord(File manifestFile,
                                           DeconvolutionIO.ArtifactIdentity artifactIdentity,
                                           DeconvManifest.SourceFingerprint source,
                                           List<Integer> deconvolvedChannels) throws IOException {
        if (manifestFile == null) return;
        DeconvManifest manifest = DeconvManifest.load(manifestFile)
                .withArtifactIdentity(artifactIdentity);
        Map<Integer, String> channelHashes = new HashMap<Integer, String>();
        for (Integer channelIndex : deconvolvedChannels) {
            DeconvManifest.ChannelEntry entry = manifest.channel(channelIndex.intValue());
            if (entry != null && entry.paramsHash != null) {
                channelHashes.put(channelIndex, entry.paramsHash);
            }
        }
        manifest = manifest.withMerged(new DeconvManifest.MergedRecord(source, channelHashes));
        DeconvManifest.writeAtomic(manifestFile, manifest);
    }

    private static void normalizeMergeDepth(ImagePlus[] channelImages) {
        int targetSlices = Integer.MAX_VALUE;
        for (ImagePlus channelImage : channelImages) {
            if (channelImage != null) {
                targetSlices = Math.min(targetSlices, Math.max(1, channelImage.getStackSize()));
            }
        }
        if (targetSlices == Integer.MAX_VALUE) {
            return;
        }
        for (int i = 0; i < channelImages.length; i++) {
            ImagePlus channelImage = channelImages[i];
            if (channelImage == null || channelImage.getStackSize() <= targetSlices) {
                continue;
            }
            ImagePlus trimmed = copyFirstSlices(channelImage, targetSlices);
            if (trimmed != channelImage) {
                closeQuietly(channelImage);
                channelImages[i] = trimmed;
            }
        }
    }

    protected long now() {
        return System.currentTimeMillis();
    }

    private void appendSummaryRow(DeconvSummaryReport summaryReport,
                                  String imageName,
                                  String channelName,
                                  DeconvolutionEngine engine,
                                  DeconvSettings ch,
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
                    engine == null ? ch.engineKey() : engine.displayName(),
                    ch.algorithm() == null ? "" : ch.algorithm().displayName(),
                    ch.iterations(),
                    ch.regularization(),
                    ch.psfModel() == null ? "" : ch.psfModel().displayName(),
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
        } catch (IOException e) {
            IJ.log("    - WARNING: Could not read channel names from " + directory
                    + "; using metadata channel count only: " + e.getMessage());
        }

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
        return validateRequiredFields(representative, settings, true);
    }

    private List<String> validateRequiredFields(MetadataDiagnostics.SeriesInfo representative,
                                                RunSettings settings,
                                                boolean requireEmissionWavelengths) {
        ResolvedSeriesSettings resolved = resolveSeriesSettings(representative, settings, settings.channelNames.length);
        return missingFieldsForSeries(representative, settings, resolved, requireEmissionWavelengths);
    }

    private List<String> missingFieldsForSeries(MetadataDiagnostics.SeriesInfo info,
                                                RunSettings settings,
                                                ResolvedSeriesSettings resolved,
                                                boolean requireEmissionWavelengths) {
        List<String> missing = new ArrayList<String>();
        if (!isPositiveFinite(resolved.numericalAperture)) missing.add("Numerical Aperture");
        if (!isPositiveFinite(resolved.immersionRi)) missing.add("Immersion RI");
        if (isPositiveFinite(resolved.numericalAperture)
                && isPositiveFinite(resolved.immersionRi)
                && resolved.numericalAperture >= resolved.immersionRi) {
            missing.add("Numerical Aperture must be lower than Immersion RI");
        }
        if (!isPositiveFinite(resolved.sampleRi)) missing.add("Sample RI");
        if (!isPositiveFinite(resolved.zStepUm)) missing.add("Z-step");
        if (!isPositiveFinite(resolved.xyPixelSizeUm)) {
            missing.add("XY pixel size");
        }
        if (settings.scopeModality == ScopeModality.CONFOCAL
                && !isPositiveFinite(resolved.pinholeAiryUnits)) {
            missing.add("Pinhole");
        }
        if (requireEmissionWavelengths) {
            for (int i = 0; i < resolved.emissionWavelengthsNm.length; i++) {
                if (i < settings.selectedChannels.length && !settings.selectedChannels[i]) continue;
                double value = resolved.emissionWavelengthsNm[i];
                if (!isPositiveFinite(value)) {
                    missing.add(settings.channelNames[i] + " emission wavelength");
                }
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

    private ResolvedSeriesSettings resolvePreviewSeriesSettings(
            MetadataDiagnostics.SeriesInfo info,
            RunSettings settings,
            int channelCount,
            int channelIndex,
            String pendingWavelengthText) {
        return resolveSeriesSettings(info,
                snapshotWithChannelWavelengthForPreview(settings, channelCount,
                        channelIndex, pendingWavelengthText),
                channelCount);
    }

    static RunSettings snapshotWithChannelWavelengthForPreview(RunSettings settings,
                                                               int channelCount,
                                                               int channelIndex,
                                                               String pendingWavelengthText) {
        RunSettings snapshot = copyRunSettings(settings);
        snapshot.emissionOverridesNm = copyWavelengths(
                settings == null ? null : settings.emissionOverridesNm,
                channelCount);
        Double wavelength = parseNullableDouble(pendingWavelengthText);
        if (wavelength != null
                && channelIndex >= 0
                && channelIndex < snapshot.emissionOverridesNm.length) {
            snapshot.emissionOverridesNm[channelIndex] = wavelength.doubleValue();
        }
        return snapshot;
    }

    private static RunSettings copyRunSettings(RunSettings source) {
        RunSettings copy = new RunSettings();
        if (source == null) {
            return copy;
        }
        copy.enabled = source.enabled;
        copy.engineKey = source.engineKey;
        copy.algorithm = source.algorithm;
        copy.psfModel = source.psfModel;
        copy.scopeModality = source.scopeModality;
        copy.pinholeAiryUnits = source.pinholeAiryUnits;
        copy.sampleRiOverride = source.sampleRiOverride;
        copy.mountingMedium = source.mountingMedium;
        copy.iterations = source.iterations;
        copy.regularization = source.regularization;
        copy.strictNyquist = source.strictNyquist;
        copy.useCache = source.useCache;
        copy.skipPreview = source.skipPreview;
        copy.previewAccepted = source.previewAccepted;
        copy.selectedChannels = source.selectedChannels == null
                ? null : source.selectedChannels.clone();
        copy.channelNames = source.channelNames == null
                ? null : source.channelNames.clone();
        copy.naOverride = source.naOverride;
        copy.immersionRiOverride = source.immersionRiOverride;
        copy.xyPixelSizeOverrideUm = source.xyPixelSizeOverrideUm;
        copy.zStepOverrideUm = source.zStepOverrideUm;
        copy.emissionOverridesNm = source.emissionOverridesNm == null
                ? null : source.emissionOverridesNm.clone();
        copy.perChannel = source.perChannel == null ? null : source.perChannel.clone();
        return copy;
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
        boolean mixedSettings = hasMixedSelectedChannelSettings(channelNames, settings);
        if (mixedSettings) {
            params.put("Engine", "per-channel");
            params.put("Algorithm", "per-channel");
            params.put("PSF Model", "per-channel");
            params.put("Iterations", "per-channel");
            params.put("Regularization", "per-channel");
        } else {
            params.put("Engine", settings.engineKey);
            params.put("Algorithm", settings.algorithm == null ? "" : settings.algorithm.displayName());
            params.put("PSF Model", settings.psfModel == null ? "" : settings.psfModel.displayName());
            DeconvSettings reportSettings = settings.channel(-1);
            params.put("Iterations", usesIterations(reportSettings)
                    ? String.valueOf(reportSettings.iterations()) : "n/a");
            params.put("Regularization", usesRegularization(reportSettings)
                    ? String.format(Locale.ROOT, "%.3f", reportSettings.regularization()) : "n/a");
        }
        params.put("Scope Modality", settings.scopeModality == null ? "" : settings.scopeModality.displayName());
        params.put("Strict Nyquist", String.valueOf(settings.strictNyquist));
        params.put("Use Cache", String.valueOf(settings.useCache));
        params.put("Channels", selectedChannelList(channelNames, settings.selectedChannels));
        List<String> perChannelLines = perChannelSettingsLines(channelNames, settings);
        for (int i = 0; i < perChannelLines.size(); i++) {
            params.put("Channel Setting " + (i + 1), perChannelLines.get(i));
        }
        qualityReport.addSection(TITLE, params);
    }

    private List<String> validateRequiredFields(SeriesJob representative, RunSettings settings) {
        return validateRequiredFields(representative.seriesInfo, settings);
    }

    private boolean isSelectedEngineReady(String engineKey) {
        if (isSelectedEngineAvailable(engineKey)) {
            return true;
        }
        DependencyId dependencyId = dependencyIdForEngine(engineKey);
        if (dependencyId != null) {
            FeatureDependencyGate.GateDecision decision = FeatureDependencyGate.check(
                    dependencyId,
                    TITLE,
                    dependencyRequirementForEngineKey(engineKey));
            if (!decision.isAllowed()) {
                return false;
            }
        }
        if (isSelectedEngineAvailable(engineKey)) {
            return true;
        }
        showOrLogError("Selected deconvolution engine is still not available in this Fiji runtime. "
                + "Restart Fiji after installing its dependencies. CLIJ2 also needs a usable OpenCL GPU.");
        return false;
    }

    boolean areSelectedChannelEnginesReady(RunSettings settings) {
        if (settings == null) {
            return false;
        }
        Set<String> checked = new HashSet<String>();
        boolean[] selected = settings.selectedChannels;
        int channelCount = selected == null
                ? Math.max(1, settings.channelNames == null ? 0 : settings.channelNames.length)
                : selected.length;
        boolean anySelected = false;
        for (int channelIndex = 0; channelIndex < channelCount; channelIndex++) {
            if (selected != null && !selected[channelIndex]) {
                continue;
            }
            anySelected = true;
            DeconvSettings channelSettings = settings.channel(channelIndex);
            if (checked.add(settingsKey(channelSettings))
                    && !isSelectedChannelSettingsReady(channelSettings)) {
                return false;
            }
        }
        if (!anySelected) {
            return isSelectedChannelSettingsReady(settings.channel(-1));
        }
        return true;
    }

    private boolean isSelectedChannelSettingsReady(DeconvSettings settings) {
        if (settings == null) {
            return false;
        }
        String engineKey = settings.engineKey();
        if (!isSelectedEngineReady(engineKey)) {
            return false;
        }
        DeconvolutionEngine engine = resolveEngine(engineKey);
        if (engine == null) {
            showOrLogError("Selected deconvolution engine could not be resolved: " + engineKey);
            return false;
        }
        List<Algorithm> algorithms = engine.supportedAlgorithms();
        Algorithm algorithm = settings.algorithm();
        if (algorithm == null || algorithms == null || !algorithms.contains(algorithm)) {
            showOrLogError(engine.displayName() + " does not support "
                    + (algorithm == null ? "the selected algorithm" : algorithm.displayName()) + ".");
            return false;
        }
        PsfModel psfModel = settings.psfModel();
        try {
            if (!engine.supportsPsfModel(psfModel)) {
                showOrLogError(engine.displayName() + " does not support "
                        + (psfModel == null ? "the selected PSF model" : psfModel.displayName()) + ".");
                return false;
            }
        } catch (RuntimeException e) {
            showOrLogError(engine.displayName() + " could not validate the selected PSF model: "
                    + e.getMessage());
            return false;
        }
        return true;
    }

    private static String settingsKey(DeconvSettings settings) {
        if (settings == null) {
            return "";
        }
        return String.valueOf(settings.engineKey()) + '|'
                + String.valueOf(settings.algorithm()) + '|'
                + String.valueOf(settings.psfModel());
    }

    private boolean isSelectedEngineAvailable(String engineKey) {
        for (DeconvolutionEngine engine : availableEngines()) {
            if (engine.key().equals(engineKey)) return true;
        }
        return false;
    }

    private void showMissingEngineDependency(EngineChoice choice) {
        if (choice == null || choice.available) {
            return;
        }
        DependencyId dependencyId = dependencyIdForEngine(choice.engine.key());
        if (dependencyId == null) {
            showOrLogError(choice.engine.displayName() + " is not available in this Fiji runtime.");
            return;
        }
        FeatureDependencyGate.check(dependencyId, TITLE, dependencyRequirementForEngine(choice.engine));
    }

    private static DependencyId dependencyIdForEngine(String engineKey) {
        if ("CLIJ2".equals(engineKey)) {
            return DependencyId.DECONV_CLIJ2_RUNTIME;
        }
        if ("DL2".equals(engineKey)) {
            return DependencyId.DECONVOLUTIONLAB2_RUNTIME;
        }
        if ("IterativeDeconvolve3D".equals(engineKey)) {
            return DependencyId.ITERATIVE_DECONVOLVE_3D_RUNTIME;
        }
        return null;
    }

    private static String dependencyRequirementForEngine(DeconvolutionEngine engine) {
        if (engine == null) {
            return "selected deconvolution engine";
        }
        return dependencyRequirementForEngineKey(engine.key());
    }

    private static String dependencyRequirementForEngineKey(String engineKey) {
        if ("CLIJ2".equals(engineKey)) {
            return "CLIJ2 3D deconvolution engine";
        }
        if ("DL2".equals(engineKey)) {
            return "DeconvolutionLab2 3D deconvolution engine";
        }
        if ("IterativeDeconvolve3D".equals(engineKey)) {
            return "Iterative Deconvolve 3D engine";
        }
        return "selected deconvolution engine";
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
        recordWarn(message);
        if (!headless && !suppressDialogs) {
            JOptionPane.showMessageDialog(null, message, TITLE, JOptionPane.ERROR_MESSAGE);
        }
    }

    private AnalysisRunContext.InputHandle recordInputStart(File source, int seriesIndex) {
        if (runRecordContext == null) {
            return null;
        }
        return runRecordContext.recordInputStart(source, seriesIndex, null);
    }

    private void recordInputEnd(AnalysisRunContext.InputHandle inputHandle,
                                String status,
                                long startedMillis) {
        if (runRecordContext != null && inputHandle != null) {
            runRecordContext.recordInputEnd(inputHandle, status,
                    Math.max(0L, now() - startedMillis));
        }
    }

    private void recordOutput(File file, String kind) {
        if (runRecordContext != null && file != null) {
            runRecordContext.recordOutput(file, kind);
        }
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

    private void recordWarnings(SeriesJob job, List<String> warnings) {
        if (runRecordContext == null || warnings == null || warnings.isEmpty()) {
            return;
        }
        String prefix = job == null ? "Deconvolution warning" :
                "Deconvolution warning [" + job.baseName + "]";
        for (String warning : warnings) {
            if (warning != null && !warning.trim().isEmpty()) {
                runRecordContext.warn(prefix + ": " + warning);
            }
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
        File detailsFile = job.artifactIdentity == null
                ? DeconvolutionIO.detailsFile(rootDir, job.baseName)
                : DeconvolutionIO.detailsFile(rootDir, job.artifactIdentity);
        StringBuilder sb = new StringBuilder();
        sb.append("Image: ").append(job.displayName).append('\n');
        sb.append("Source File: ").append(job.sourceFile == null ? "" : job.sourceFile.getAbsolutePath()).append('\n');
        sb.append("Series Index: ").append(job.seriesIndex).append('\n');
        if (job.sourceSeriesIndex != job.seriesIndex) {
            sb.append("Source Series Index: ").append(job.sourceSeriesIndex).append('\n');
        }
        boolean mixedSettings = hasMixedSelectedChannelSettings(channelNames, settings);
        if (mixedSettings) {
            sb.append("Engine: per-channel\n");
            sb.append("Algorithm: per-channel\n");
            sb.append("Iterations: per-channel\n");
            sb.append("Regularization: per-channel\n");
            sb.append("PSF Model: per-channel\n");
        } else {
            sb.append("Engine: ").append(settings.engineKey).append('\n');
            sb.append("Algorithm: ").append(settings.algorithm == null ? "" : settings.algorithm.name()).append('\n');
            DeconvSettings detailsSettings = settings.channel(-1);
            sb.append("Iterations: ").append(usesIterations(detailsSettings)
                    ? String.valueOf(detailsSettings.iterations()) : "n/a").append('\n');
            sb.append("Regularization: ").append(usesRegularization(detailsSettings)
                    ? String.format(Locale.ROOT, "%.6f", detailsSettings.regularization()) : "n/a").append('\n');
            sb.append("PSF Model: ").append(settings.psfModel == null ? "" : settings.psfModel.name()).append('\n');
        }
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
        appendPerChannelSettings(sb, channelNames, settings);
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
            try (DeconvolutionFamilyLock.Handle ignored =
                         acquireFamilyLock(rootDir, job.artifactIdentity)) {
                ensureDirectory(detailsFile.getParentFile());
                File temp = File.createTempFile(detailsFile.getName() + "-", ".tmp", detailsFile.getParentFile());
                Files.write(temp.toPath(), sb.toString().getBytes(StandardCharsets.UTF_8));
                IoUtils.commitReplacingSmallFile(temp.toPath(), detailsFile.toPath());
                recordOutput(detailsFile, "txt");
            }
        } catch (IOException e) {
            String message = "Could not write deconvolution details for "
                    + job.baseName + ": " + e.getMessage();
            IJ.log(message);
            recordWarn(message);
        }
        recordWarnings(job, warnings);
    }

    private void appendPerChannelSettings(StringBuilder sb,
                                          String[] channelNames,
                                          RunSettings settings) {
        List<String> lines = perChannelSettingsLines(channelNames, settings);
        if (lines.isEmpty()) {
            return;
        }
        sb.append('\n').append("Per-Channel Settings:\n");
        for (String line : lines) {
            sb.append("  ").append(line).append('\n');
        }
    }

    private List<String> perChannelSettingsLines(String[] channelNames, RunSettings settings) {
        List<String> lines = new ArrayList<String>();
        if (settings == null || settings.selectedChannels == null) {
            return lines;
        }
        int channelCount = safeLength(channelNames);
        for (int channelIndex = 0; channelIndex < channelCount; channelIndex++) {
            if (channelIndex >= settings.selectedChannels.length
                    || !settings.selectedChannels[channelIndex]) {
                continue;
            }
            DeconvSettings ch = settings.channel(channelIndex);
            if (ch == null) {
                continue;
            }
            DeconvolutionEngine engine = resolveEngine(ch.engineKey());
            Algorithm algorithm = ch.algorithm();
            PsfModel psfModel = ch.psfModel();
            String iterationsText = usesIterations(ch)
                    ? String.valueOf(ch.iterations()) : "n/a";
            String regularizationText = usesRegularization(ch)
                    ? String.format(Locale.ROOT, "%.6f", ch.regularization()) : "n/a";
            lines.add(channelNameAt(channelNames, channelIndex)
                    + ": Engine=" + (engine == null ? safeText(ch.engineKey()) : engine.displayName())
                    + ", Algorithm=" + (algorithm == null ? "" : algorithm.displayName())
                    + ", Iterations=" + iterationsText
                    + ", Regularization=" + regularizationText
                    + ", PSF=" + (psfModel == null ? "" : psfModel.displayName()));
        }
        return lines;
    }

    private boolean hasMixedSelectedChannelSettings(String[] channelNames, RunSettings settings) {
        if (settings == null || settings.selectedChannels == null) {
            return false;
        }
        int channelCount = safeLength(channelNames);
        DeconvSettings first = null;
        for (int channelIndex = 0; channelIndex < channelCount; channelIndex++) {
            if (channelIndex >= settings.selectedChannels.length
                    || !settings.selectedChannels[channelIndex]) {
                continue;
            }
            DeconvSettings current = settings.channel(channelIndex);
            if (first == null) {
                first = current;
            } else if (current == null ? first != null : !current.equals(first)) {
                return true;
            }
        }
        return false;
    }

    private boolean usesIterations(DeconvSettings settings) {
        if (settings == null) {
            return true;
        }
        DeconvolutionEngine engine = resolveEngine(settings.engineKey());
        Algorithm algorithm = settings.algorithm() == null
                ? defaultAlgorithm(engine)
                : settings.algorithm();
        return engine.usesIterations(algorithm);
    }

    private boolean usesRegularization(DeconvSettings settings) {
        if (settings == null) {
            return true;
        }
        DeconvolutionEngine engine = resolveEngine(settings.engineKey());
        Algorithm algorithm = settings.algorithm() == null
                ? defaultAlgorithm(engine)
                : settings.algorithm();
        return engine.usesRegularization(algorithm);
    }

    private String parameterSummary(DeconvolutionEngine engine, DeconvSettings settings) {
        if (engine == null || settings == null) {
            return "";
        }
        Algorithm algorithm = settings.algorithm() == null
                ? defaultAlgorithm(engine)
                : settings.algorithm();
        List<String> parts = new ArrayList<String>();
        if (engine.usesIterations(algorithm)) {
            parts.add(settings.iterations() + " iter");
        }
        if (engine.usesRegularization(algorithm)) {
            parts.add("reg " + String.format(Locale.ROOT, "%.3f", settings.regularization()));
        }
        return String.join(", ", parts);
    }

    private Map<String, String> buildHashParams(RunSettings settings,
                                                SeriesJob job,
                                                ResolvedSeriesSettings resolved,
                                                int channelIndex) {
        // Delegates the field set to the single shared producer so this writer's stamp cannot drift
        // from the consumer/preflight's expected hash (see DeconvParamsHash). resolved.pinholeAiryUnits
        // is a primitive here (never null); the shared producer's null-default is a no-op on this path.
        DeconvSettings ch = settings.channel(channelIndex);
        Map<String, String> params = DeconvParamsHash.buildParams(
                ch,
                settings.scopeModality,
                resolved.numericalAperture,
                resolved.immersionRi,
                resolved.sampleRi,
                settings.scopeModality == ScopeModality.CONFOCAL
                        ? Double.valueOf(resolved.pinholeAiryUnits) : null,
                resolved.emissionWavelengthsNm[channelIndex],
                resolved.xyPixelSizeUm,
                resolved.zStepUm,
                job.seriesInfo.sizeX,
                job.seriesInfo.sizeY,
                job.seriesInfo.sizeZ);
        return DeconvParamsHash.withArtifactIdentity(params, job.artifactIdentity);
    }

    /**
     * Reconcile {@code channelIndex} against the resolved per-channel emission-wavelength array
     * before indexing it. In the standalone flow the config and series channel counts always agree,
     * so this never fires; the guard exists because the shared previewer can be driven by a setup
     * step where the config channel count may differ from the series {@code sizeC}. A descriptive
     * failure is far more actionable than a raw {@code ArrayIndexOutOfBoundsException}.
     */
    private static double emissionWavelengthForChannel(ResolvedSeriesSettings resolved, int channelIndex) {
        double[] wavelengths = resolved == null ? null : resolved.emissionWavelengthsNm;
        int channelCount = wavelengths == null ? 0 : wavelengths.length;
        if (channelIndex < 0 || channelIndex >= channelCount) {
            throw new IllegalArgumentException("Channel index " + channelIndex
                    + " is out of range for " + channelCount
                    + " configured emission wavelength(s); the configuration channel count does not"
                    + " match the series channel count.");
        }
        return wavelengths[channelIndex];
    }

    private static PsfSpec createPsfSpec(ResolvedSeriesSettings resolved,
                                         int channelIndex,
                                         ImagePlus image,
                                         ScopeModality scopeModality) {
        if (resolved == null) throw new IllegalArgumentException("resolved settings are required.");
        if (image == null) throw new IllegalArgumentException("image is required.");
        ScopeModality modality = scopeModality == null ? ScopeModality.WIDEFIELD : scopeModality;
        return new PsfSpec(
                resolved.numericalAperture,
                resolved.immersionRi,
                resolved.sampleRi,
                emissionWavelengthForChannel(resolved, channelIndex),
                resolved.xyPixelSizeUm * 1000.0,
                resolved.zStepUm * 1000.0,
                psfKernelSizeForImageDimension(image.getWidth(), MAX_PSF_SIZE_XY),
                psfKernelSizeForImageDimension(image.getHeight(), MAX_PSF_SIZE_XY),
                psfKernelSizeForImageDimension(image.getStackSize(), MAX_PSF_SIZE_Z),
                modality,
                modality == ScopeModality.CONFOCAL
                        ? Double.valueOf(resolved.pinholeAiryUnits)
                        : null
        );
    }

    private static int psfKernelSizeForImageDimension(int imageDimension, int maxOddSize) {
        int capped = Math.min(Math.max(1, imageDimension), Math.max(1, maxOddSize));
        if (capped > 1 && (capped % 2) == 0) {
            capped--;
        }
        return Math.max(1, capped);
    }

    private static boolean sanitizeInputForDeconvolution(ImagePlus image) {
        if (image == null || image.getStack() == null) return false;
        ImageStack stack = image.getStack();
        float minFinite = Float.POSITIVE_INFINITY;
        boolean changed = false;
        for (int z = 1; z <= stack.getSize(); z++) {
            ImageProcessor processor = stack.getProcessor(z);
            for (int i = 0; i < processor.getPixelCount(); i++) {
                float value = processor.getf(i);
                if (Float.isNaN(value) || Float.isInfinite(value)) {
                    changed = true;
                    continue;
                }
                if (value < minFinite) {
                    minFinite = value;
                }
            }
        }
        float offset = minFinite < 0.0f ? -minFinite : 0.0f;
        if (offset > 0.0f) {
            changed = true;
        }
        if (!changed) return false;

        for (int z = 1; z <= stack.getSize(); z++) {
            ImageProcessor processor = stack.getProcessor(z);
            for (int i = 0; i < processor.getPixelCount(); i++) {
                float value = processor.getf(i);
                if (Float.isNaN(value) || Float.isInfinite(value)) {
                    processor.setf(i, 0.0f);
                } else if (offset > 0.0f) {
                    processor.setf(i, value + offset);
                }
            }
        }
        return true;
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
            choices.add(new EngineChoice(engine, isAvailable, label));
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

    private static void refreshAlgorithmParameterRows(JComboBox<EngineChoice> engineChoice,
                                                      JComboBox<AlgorithmChoice> algorithmChoice,
                                                      SourceTaggedRow iterationsRow,
                                                      SourceTaggedRow regularizationRow) {
        EngineChoice engine = engineChoice == null ? null
                : (EngineChoice) engineChoice.getSelectedItem();
        AlgorithmChoice algorithm = algorithmChoice == null ? null
                : (AlgorithmChoice) algorithmChoice.getSelectedItem();
        DeconvolutionEngine selectedEngine = engine == null ? null : engine.engine;
        Algorithm selectedAlgorithm = algorithm == null ? null : algorithm.algorithm;
        boolean showIterations = selectedEngine == null || selectedAlgorithm == null
                || selectedEngine.usesIterations(selectedAlgorithm);
        boolean showRegularization = selectedEngine == null || selectedAlgorithm == null
                || selectedEngine.usesRegularization(selectedAlgorithm);
        setRowVisible(iterationsRow, showIterations);
        setRowVisible(regularizationRow, showRegularization);
        refreshDialogLayout(iterationsRow == null ? null : iterationsRow.panel);
        refreshDialogLayout(regularizationRow == null ? null : regularizationRow.panel);
    }

    private static void setRowVisible(SourceTaggedRow row, boolean visible) {
        if (row != null && row.panel != null) {
            row.panel.setVisible(visible);
        }
    }

    private static void refreshDialogLayout(JComponent component) {
        if (component == null) {
            return;
        }
        Container parent = component.getParent();
        while (parent != null) {
            parent.revalidate();
            parent.repaint();
            parent = parent.getParent();
        }
        Window window = SwingUtilities.getWindowAncestor(component);
        if (window != null && window.isShowing()) {
            window.pack();
        }
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

    private static JPanel topHelpRow(JButton helpButton, JButton loadRunButton) {
        JPanel row = new JPanel();
        row.setOpaque(false);
        row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
        row.setBorder(BorderFactory.createEmptyBorder(0, 4, 2, 4));
        if (loadRunButton != null) {
            row.add(loadRunButton);
        }
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
            double value = Double.parseDouble(raw.trim());
            return isPositiveFinite(value) ? Double.valueOf(value) : null;
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
        if (primary != null && isPositiveFinite(primary.doubleValue())) return primary.doubleValue();
        if (fallback != null && isPositiveFinite(fallback.doubleValue())) return fallback.doubleValue();
        return Double.NaN;
    }

    private static boolean isPositiveFinite(double value) {
        return !Double.isNaN(value) && !Double.isInfinite(value) && value > 0.0;
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
        File temp = File.createTempFile(target.getName() + "-", ".tmp", target.getParentFile());
        boolean moved = false;
        try {
            Files.copy(source.toPath(), temp.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.COPY_ATTRIBUTES);
            moveReplacing(temp, target);
            moved = true;
        } finally {
            if (!moved) {
                Files.deleteIfExists(temp.toPath());
            }
        }
    }

    private static void deleteFileIfExists(File file) throws IOException {
        if (file == null) return;
        Files.deleteIfExists(file.toPath());
    }

    /**
     * After a channel's recompute FAILED (open/PSF/engine error), remove its per-channel mirror unless
     * that mirror is still fresh for the current parameters + source (per the manifest). Removing a
     * stale mirror stops a downstream consumer from serving stale pixels via the mtime freshness
     * fallback; a genuinely-fresh mirror that was only being force-recomputed is preserved.
     */
    private void removeStalePerChannelOutputOnFailure(File outFile, File manifestFile, int channelIndex,
                                                      String paramsHash,
                                                      DeconvManifest.SourceFingerprint sourceFingerprint) {
        removeStalePerChannelOutputOnFailure(outFile, manifestFile, null, null,
                channelIndex, paramsHash, sourceFingerprint);
    }

    private void removeStalePerChannelOutputOnFailure(
            File rootDir,
            File outFile,
            File manifestFile,
            File existingOutFile,
            DeconvolutionIO.ArtifactIdentity artifactIdentity,
            int channelIndex,
            String paramsHash,
            DeconvManifest.SourceFingerprint sourceFingerprint) {
        try (DeconvolutionFamilyLock.Handle ignored = acquireFamilyLock(rootDir, artifactIdentity)) {
            removeStalePerChannelOutputOnFailure(outFile, manifestFile, existingOutFile,
                    artifactIdentity, channelIndex, paramsHash, sourceFingerprint);
        } catch (IOException e) {
            recordWarn("Could not lock stale per-channel deconvolution cleanup: " + e.getMessage());
        }
    }

    private void removeStalePerChannelOutputOnFailure(
            File outFile,
            File manifestFile,
            File existingOutFile,
            DeconvolutionIO.ArtifactIdentity artifactIdentity,
            int channelIndex,
            String paramsHash,
            DeconvManifest.SourceFingerprint sourceFingerprint) {
        if ((outFile == null || !outFile.exists())
                && (existingOutFile == null || !existingOutFile.exists())) {
            return;
        }
        boolean fresh = artifactIdentity == null
                ? DeconvManifest.isFresh(manifestFile, channelIndex, paramsHash, sourceFingerprint)
                : DeconvManifest.isFresh(manifestFile, channelIndex, paramsHash,
                        sourceFingerprint, artifactIdentity);
        if (fresh) {
            return;
        }
        try {
            deleteFileIfExists(outFile);
            if (existingOutFile != null && !existingOutFile.equals(outFile)) {
                deleteFileIfExists(existingOutFile);
            }
        } catch (IOException e) {
            recordWarn("Could not remove stale per-channel deconvolved output " + outFile.getName()
                    + ": " + e.getMessage());
        }
    }

    private static Map<String, Integer> legacyBaseNameCounts(List<SeriesJob> jobs) {
        Map<String, Integer> counts = new HashMap<String, Integer>();
        if (jobs == null) return counts;
        for (SeriesJob job : jobs) {
            if (job == null) continue;
            String token = DeconvolutionIO.legacyBaseNameToken(job.baseName);
            Integer count = counts.get(token);
            counts.put(token, Integer.valueOf(count == null ? 1 : count.intValue() + 1));
        }
        return counts;
    }

    private static void deleteFilesIfExist(List<File> files) throws IOException {
        if (files == null) return;
        for (File file : files) {
            deleteFileIfExists(file);
        }
    }

    private static DeconvolutionFamilyLock.Handle acquireFamilyLock(
            File rootDir, DeconvolutionIO.ArtifactIdentity artifactIdentity) throws IOException {
        return artifactIdentity == null
                ? null : DeconvolutionIO.lockFamilyForAccess(rootDir, artifactIdentity);
    }

    private static DeconvManifest.SourceFingerprint sourceFingerprintQuietly(File source) {
        try {
            return DeconvManifest.SourceFingerprint.of(source);
        } catch (IOException e) {
            return new DeconvManifest.SourceFingerprint(-1L, -1L, "");
        }
    }

    private static DeconvManifest.SourceFingerprint sourceFingerprintFor(SeriesJob job) {
        if (job != null && job.artifactIdentity != null) {
            return new DeconvManifest.SourceFingerprint(
                    job.artifactIdentity.sourceSize,
                    job.sourceFile == null ? -1L : job.sourceFile.lastModified(),
                    job.artifactIdentity.verifiedSourceContentHash);
        }
        return sourceFingerprintQuietly(job == null ? null : job.sourceFile);
    }

    private static void moveReplacing(File source, File target) throws IOException {
        // Atomic move with retry/backoff for transient locks (cloud-sync, AV).
        // No in-place fallback: deconvolution outputs can be large, never read into memory.
        IoUtils.moveReplacing(source.toPath(), target.toPath());
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

    private static String formatDurationCompact(long ms) {
        long safeMs = Math.max(0L, ms);
        if (safeMs < 1000L) {
            return safeMs + " ms";
        }
        long seconds = safeMs / 1000L;
        if (seconds < 60L) {
            return seconds + " s";
        }
        long minutes = seconds / 60L;
        long remSeconds = seconds % 60L;
        if (minutes < 60L) {
            return minutes + "m " + remSeconds + "s";
        }
        long hours = minutes / 60L;
        long remMinutes = minutes % 60L;
        return hours + "h " + remMinutes + "m";
    }

    private static void addCount(List<String> parts, int count, String singular, String plural) {
        if (parts != null && count > 0) {
            parts.add(count + " " + (count == 1 ? singular : plural));
        }
    }

    private static final class ImageStats {
        int writtenChannels;
        int cacheHitChannels;
        int skippedExistingChannels;
        int skippedChannels;
        int failedChannels;
        boolean mergeWritten;
        boolean mergeSkippedExisting;
        boolean mergeSkipped;
        boolean mergeFailed;

        String summary() {
            List<String> parts = new ArrayList<String>();
            addCount(parts, writtenChannels, "channel written", "channels written");
            addCount(parts, cacheHitChannels, "cache hit", "cache hits");
            addCount(parts, skippedExistingChannels, "existing channel skipped", "existing channels skipped");
            addCount(parts, skippedChannels, "channel skipped", "channels skipped");
            addCount(parts, failedChannels, "channel failed", "channels failed");
            if (parts.isEmpty()) {
                parts.add("no channel outputs");
            }
            parts.add(mergeSummary());
            return joinList(parts);
        }

        private String mergeSummary() {
            if (mergeWritten) return "merge written";
            if (mergeSkippedExisting) return "merge skipped existing";
            if (mergeFailed) return "merge failed";
            if (mergeSkipped) return "merge skipped";
            return "merge not attempted";
        }
    }

    private static final class BatchStats {
        int imagesCompleted;
        int imagesSkipped;
        int imagesFailed;
        int writtenChannels;
        int cacheHitChannels;
        int skippedExistingChannels;
        int skippedChannels;
        int failedChannels;
        int mergeWritten;
        int mergeSkippedExisting;
        int mergeSkipped;
        int mergeFailed;

        String summary(int totalImages, long elapsedMs) {
            List<String> imageParts = new ArrayList<String>();
            imageParts.add(totalImages + " " + (totalImages == 1 ? "image" : "images"));
            addCount(imageParts, imagesCompleted, "complete", "complete");
            addCount(imageParts, imagesSkipped, "skipped", "skipped");
            addCount(imageParts, imagesFailed, "failed", "failed");

            List<String> channelParts = new ArrayList<String>();
            addCount(channelParts, writtenChannels, "written", "written");
            addCount(channelParts, cacheHitChannels, "cache hit", "cache hits");
            addCount(channelParts, skippedExistingChannels, "existing skipped", "existing skipped");
            addCount(channelParts, skippedChannels, "skipped", "skipped");
            addCount(channelParts, failedChannels, "failed", "failed");

            List<String> mergeParts = new ArrayList<String>();
            addCount(mergeParts, mergeWritten, "written", "written");
            addCount(mergeParts, mergeSkippedExisting, "existing skipped", "existing skipped");
            addCount(mergeParts, mergeSkipped, "skipped", "skipped");
            addCount(mergeParts, mergeFailed, "failed", "failed");

            return joinList(imageParts)
                    + "; channels: " + (channelParts.isEmpty() ? "none" : joinList(channelParts))
                    + "; merges: " + (mergeParts.isEmpty() ? "none" : joinList(mergeParts))
                    + "; elapsed " + formatDurationCompact(elapsedMs);
        }
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

    private static final class PreviewSelection {
        final SeriesJob job;
        final int[] channels;
        final CropSpec cropSpec;

        PreviewSelection(SeriesJob job, int[] channels, CropSpec cropSpec) {
            this.job = job;
            this.channels = channels == null ? new int[0] : channels.clone();
            this.cropSpec = cropSpec == null ? CropSpec.centre256() : cropSpec;
        }
    }

    private static final class PreviewImageChoice {
        final SeriesJob job;

        PreviewImageChoice(SeriesJob job) {
            this.job = job;
        }

        @Override
        public String toString() {
            if (job == null) return "Image";
            String label = nullToEmpty(job.displayName);
            if (label.trim().isEmpty()) {
                label = nullToEmpty(job.baseName);
            }
            if (label.trim().isEmpty()) {
                label = "Series " + (job.seriesIndex + 1);
            }
            String size = sizeXYZ(job.seriesInfo);
            return size.isEmpty() ? label : label + " (" + size + ")";
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

        EngineChoice(DeconvolutionEngine engine, boolean available, String label) {
            this.engine = engine;
            this.available = available;
            this.label = label;
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
        final int sourceSeriesIndex;
        final DeconvolutionIO.ArtifactIdentity artifactIdentity;
        final String artifactKey;

        SeriesJob(File sourceFile, int seriesIndex, String baseName, MetadataDiagnostics.SeriesInfo seriesInfo) {
            this(sourceFile, seriesIndex, seriesIndex, baseName, seriesInfo);
        }

        SeriesJob(File sourceFile, int seriesIndex, int sourceSeriesIndex,
                  String baseName, MetadataDiagnostics.SeriesInfo seriesInfo) {
            this(sourceFile, seriesIndex, sourceSeriesIndex, baseName, seriesInfo,
                    artifactIdentityIfAvailable(sourceFile, sourceSeriesIndex, baseName), false);
        }

        SeriesJob(File sourceFile, int seriesIndex, int sourceSeriesIndex,
                  String baseName, MetadataDiagnostics.SeriesInfo seriesInfo,
                  DeconvolutionIO.ArtifactIdentity artifactIdentity) {
            this(sourceFile, seriesIndex, sourceSeriesIndex, baseName, seriesInfo,
                    artifactIdentity, true);
        }

        private SeriesJob(File sourceFile, int seriesIndex, int sourceSeriesIndex,
                          String baseName, MetadataDiagnostics.SeriesInfo seriesInfo,
                          DeconvolutionIO.ArtifactIdentity artifactIdentity,
                          boolean requirePublishableIdentity) {
            this.sourceFile = sourceFile;
            this.seriesIndex = seriesIndex;
            this.sourceSeriesIndex = sourceSeriesIndex;
            this.baseName = baseName;
            this.seriesInfo = seriesInfo;
            if (requirePublishableIdentity
                    && (artifactIdentity == null || !artifactIdentity.isPublishable())) {
                throw new IllegalArgumentException(
                        "Series job requires a valid deconvolution source/container identity.");
            }
            this.artifactIdentity = artifactIdentity;
            this.artifactKey = artifactIdentity == null ? null : artifactIdentity.artifactKey;
            this.displayName = ImageNameParser.buildMultiSeriesDisplayLabel(
                    sourceFile == null ? "" : sourceFile.getName(),
                    seriesInfo == null ? baseName : seriesInfo.imageName);
        }

        private static DeconvolutionIO.ArtifactIdentity artifactIdentityIfAvailable(
                File sourceFile, int sourceSeriesIndex, String baseName) {
            try {
                return DeconvolutionIO.ArtifactIdentity.of(sourceFile, sourceSeriesIndex, baseName);
            } catch (Exception e) {
                // Preview-only callers can use synthetic/nonexistent source references. They must
                // remain identity-less: runBatch rejects them before any output path is created.
                return null;
            }
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

    /**
     * Tracks whether the most recently accepted preview still matches the current setup values.
     * Local to the setup dialog; inspection-only and never persisted.
     */
    static final class PreviewState {
        boolean accepted;
        String acceptedFingerprint;

        void clear() {
            accepted = false;
            acceptedFingerprint = null;
        }

        void accept(String fingerprint) {
            accepted = true;
            acceptedFingerprint = fingerprint;
        }

        boolean matches(String fingerprint) {
            return accepted && acceptedFingerprint != null && acceptedFingerprint.equals(fingerprint);
        }
    }

    static final class RunSettings {
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
        /**
         * Inspection-only: set when the user accepted a setup-dialog preview whose fingerprint still
         * matches these final values. Lets the batch launch skip a duplicate automatic pre-batch
         * preview. Never affects batch output, cache keys, or CLI behavior.
         */
        boolean previewAccepted;
        boolean[] selectedChannels;
        String[] channelNames;
        Double naOverride;
        Double immersionRiOverride;
        Double xyPixelSizeOverrideUm;
        Double zStepOverrideUm;
        double[] emissionOverridesNm;
        /**
         * Authoritative per-channel deconvolution settings (engine, algorithm,
         * iterations, regularization, PSF model). When unset, {@link #channel(int)}
         * derives a uniform value from the run-level defaults above.
         */
        DeconvSettings[] perChannel;

        /** Per-channel deconvolution settings for {@code channelIndex}. */
        DeconvSettings channel(int channelIndex) {
            if (perChannel != null
                    && channelIndex >= 0
                    && channelIndex < perChannel.length
                    && perChannel[channelIndex] != null) {
                return perChannel[channelIndex];
            }
            return new DeconvSettings(engineKey, algorithm, psfModel, iterations, regularization);
        }
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
