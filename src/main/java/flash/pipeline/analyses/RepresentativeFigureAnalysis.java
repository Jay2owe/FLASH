package flash.pipeline.analyses;

import flash.pipeline.bin.BinField;
import flash.pipeline.cli.CLIConfig;
import flash.pipeline.io.ImageCache;
import flash.pipeline.representative.RepresentativeFigureConfig;
import flash.pipeline.representative.RepresentativePreviewRenderer;
import flash.pipeline.representative.RepresentativeSelection;
import flash.pipeline.representative.RepresentativeSelectionPanel;
import flash.pipeline.representative.RepresentativeStatLoader;
import flash.pipeline.representative.RepresentativeStatTable;
import flash.pipeline.representative.RepresentativeStatistic;
import flash.pipeline.representative.RepresentativeSeries;
import flash.pipeline.report.QualityReport;
import flash.pipeline.runrecord.LoadedRunParameters;
import flash.pipeline.ui.PipelineDialog;
import ij.IJ;

import javax.swing.JComboBox;
import java.awt.GraphicsEnvironment;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Scaffold analysis for building a representative image figure.
 */
public class RepresentativeFigureAnalysis implements Analysis {
    private final RepresentativeFigureConfig config = new RepresentativeFigureConfig();

    private boolean headless = true;
    private boolean verboseLogging = false;
    private boolean skipExisting = false;
    private int parallelThreads = 1;
    private ImageCache imageCache = null;
    private int loaderThreads = 1;
    private int loaderPercent = 50;
    private boolean useTifCache = false;
    private QualityReport qualityReport = null;
    private boolean suppressDialogs = false;
    private CLIConfig cliConfig = null;

    @Override
    public void execute(String directory) {
        if (headless || suppressDialogs || GraphicsEnvironment.isHeadless()) {
            config.statistic = RepresentativeStatistic.NONE;
            config.existingResult = null;
            config.statTable = new RepresentativeStatTable();
            IJ.log("[Representative Figure] Headed mode is required for representative image selection.");
            return;
        }

        try {
            StatisticChoice choice = showStatisticChooser(directory);
            if (choice == null) {
                IJ.log("[Representative Figure] Statistic chooser cancelled.");
                return;
            }

            config.statistic = choice.statistic;
            config.existingResult = choice.existingResult;
            config.statTable = RepresentativeStatLoader.load(
                    directory, choice.statistic, choice.existingResult, parallelThreads);

            IJ.log("[Representative Figure] Loaded statistic source: "
                    + choice.statistic.label() + " (" + statTableSummary(config.statTable) + ").");

            List<RepresentativeSeries> previewSeries = RepresentativePreviewRenderer.render(
                    directory, config, imageCache, parallelThreads, useTifCache);
            if (previewSeries.isEmpty()) {
                IJ.log("[Representative Figure] No image series were available for representative selection.");
                IJ.error("Representative Figure",
                        "No image series were available for representative selection.");
                return;
            }

            RepresentativeSelection selection = showSelectionGrid(previewSeries);
            if (selection == null) {
                IJ.log("[Representative Figure] Representative selection cancelled.");
                return;
            }

            config.selection = selection;
            IJ.log("[Representative Figure] Locked representatives for "
                    + selection.size() + " condition"
                    + (selection.size() == 1 ? "" : "s") + ".");
            // TODO(representative-image-figure stage 06): register the quantification
            // side-panel with RepresentativeSelectionPanel.addSelectionListener(...).
            // TODO(representative-image-figure stage 06): hide the quantification side-panel when statistic is NONE.
        } catch (Exception e) {
            IJ.log("[Representative Figure] Could not prepare representative selection: "
                    + e.getMessage());
            IJ.error("Representative Figure",
                    "Could not prepare representative selection:\n" + e.getMessage());
        }
    }

    @Override
    public Set<BinField> requiredBinFields() {
        return EnumSet.noneOf(BinField.class);
    }

    @Override
    public boolean benefitsFromRois() {
        return false;
    }

    @Override
    public boolean requiresHeadedMode() {
        return true;
    }

    @Override
    public void setHeadless(boolean headless) {
        this.headless = headless;
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
    public void setImageCache(ImageCache cache) {
        this.imageCache = cache;
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
    public void setQualityReport(QualityReport report) {
        this.qualityReport = report;
    }

    @Override
    public void setSuppressDialogs(boolean suppress) {
        this.suppressDialogs = suppress;
    }

    @Override
    public void setCliConfig(CLIConfig config) {
        this.cliConfig = config;
    }

    public LoadedRunParameters.Result applyLoadedParameters(Map<String, Object> parameters) {
        // TODO(representative-image-figure stage 10): restore persisted figure parameters into config.
        LoadedRunParameters.Result result = LoadedRunParameters.resultForKnownKeys(
                parameters, Collections.<String>emptySet());
        LoadedRunParameters.rememberLastResult(result);
        return result;
    }

    RepresentativeFigureConfig configForTests() {
        return config;
    }

    private StatisticChoice showStatisticChooser(String directory) {
        List<RepresentativeStatLoader.ExistingResultOption> existingOptions =
                RepresentativeStatLoader.discoverExistingResultOptions(directory);
        LinkedHashMap<String, RepresentativeStatLoader.ExistingResultOption> existingByLabel =
                new LinkedHashMap<String, RepresentativeStatLoader.ExistingResultOption>();
        String[] existingLabels;
        if (existingOptions.isEmpty()) {
            existingLabels = new String[]{RepresentativeStatLoader.NO_EXISTING_RESULT_OPTION};
        } else {
            existingLabels = new String[existingOptions.size()];
            for (int i = 0; i < existingOptions.size(); i++) {
                RepresentativeStatLoader.ExistingResultOption option = existingOptions.get(i);
                existingLabels[i] = option.label;
                existingByLabel.put(option.label, option);
            }
        }

        PipelineDialog dialog = new PipelineDialog(
                "Representative Image Figure - Statistic", PipelineDialog.Phase.EXPORT);
        dialog.addHeader("Guiding Statistic");
        dialog.addMessage("Choose how FLASH should compute the statistic shown beside images during representative selection.");
        dialog.addChoice("Statistic source",
                RepresentativeStatistic.labels(),
                config.statistic == null ? RepresentativeStatistic.QUICK.label() : config.statistic.label());
        JComboBox<String> existingChoice = dialog.addChoice(
                "Existing result column", existingLabels, existingLabels[0]);
        existingChoice.setEnabled(!existingOptions.isEmpty());
        dialog.addHelpText("Existing result is only used when Statistic source is set to Existing result.");
        dialog.setPrimaryButtonText("Continue");

        if (!dialog.showDialog()) {
            return null;
        }

        RepresentativeStatistic statistic =
                RepresentativeStatistic.fromLabel(dialog.getNextChoice());
        String existingLabel = dialog.getNextChoice();
        RepresentativeStatLoader.ExistingResultOption existing = existingByLabel.get(existingLabel);
        if (statistic == RepresentativeStatistic.EXISTING_RESULT && existing == null) {
            throw new IllegalStateException("No numeric existing result column is available.");
        }
        return new StatisticChoice(statistic, existing);
    }

    private static String statTableSummary(RepresentativeStatTable table) {
        if (table == null || table.isEmpty()) {
            return "no statistic values";
        }
        return table.rowCount() + " series, " + table.channelNames().size() + " channel"
                + (table.channelNames().size() == 1 ? "" : "s");
    }

    private RepresentativeSelection showSelectionGrid(List<RepresentativeSeries> previewSeries) {
        PipelineDialog dialog = new PipelineDialog(
                "Representative Image Figure - Select Images", PipelineDialog.Phase.EXPORT);
        dialog.addHeader("Select Representatives");
        final RepresentativeSelectionPanel selectionPanel =
                new RepresentativeSelectionPanel(previewSeries);
        dialog.addComponent(selectionPanel);
        dialog.setPrimaryButtonText("Lock in");
        dialog.setPrimaryButtonEnabled(selectionPanel.hasCompleteSelection());
        selectionPanel.addSelectionListener(
                new RepresentativeSelectionPanel.SelectionListener() {
                    @Override
                    public void selectionChanged(
                            RepresentativeSelectionPanel.SelectionEvent event) {
                        dialog.setPrimaryButtonEnabled(event.isComplete());
                    }
                });

        try {
            if (!dialog.showDialog()) {
                return null;
            }
            return selectionPanel.createSelection();
        } finally {
            selectionPanel.dispose();
        }
    }

    private static final class StatisticChoice {
        final RepresentativeStatistic statistic;
        final RepresentativeStatLoader.ExistingResultOption existingResult;

        StatisticChoice(RepresentativeStatistic statistic,
                        RepresentativeStatLoader.ExistingResultOption existingResult) {
            this.statistic = statistic == null ? RepresentativeStatistic.QUICK : statistic;
            this.existingResult = existingResult;
        }
    }
}
